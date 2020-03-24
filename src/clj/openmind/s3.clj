(ns openmind.s3
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.memoize :as memo]
            [openmind.edn :as edn]
            [clojure.spec.alpha :as s]
            [openmind.env :as env]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log])
  (:import com.amazonaws.auth.BasicAWSCredentials
           [com.amazonaws.services.s3 AmazonS3Client AmazonS3ClientBuilder]))

(def ^String bucket
  (env/read :s3-data-bucket))

(def ^AmazonS3Client client
  (try
    (if env/dev-mode?
      (AmazonS3Client. (BasicAWSCredentials.
                        (env/read :aws-access-key) (env/read :aws-secret-key)))

      (AmazonS3ClientBuilder/defaultClient))
    (catch Exception e nil)))

(defn- lookup-raw [key]
  (let [content (-> (.getObject client bucket (str key))
                    .getObjectContent
                    slurp)]
    (edn/read-string content)))

(def ^:private lookup*
  (memo/lru lookup-raw :lru/threshold 2))

(defn lookup
  "Returns full doc from S3 associated with key `k`."
  [k]
  ;; REVIEW: We don't want to cache nils, because the fact that a doc does not
  ;; exist yet, doesn't mean it never will (though the odds of that being a
  ;; problem are astronomically small, I'd rather rule it out). This seems
  ;; sufficient, but it breaks the snapshot capability. But I think that's
  ;; because of the retrying which isn't a problem. In fact if we retry forever
  ;; we're guaranteed to eventually get something. Whether that's what we want,
  ;; or a collision is actually an ill posed question, since how can we know
  ;; what we want when we never had a thing in the first place but only a hash
  ;; created by some side channel?
  (try
    (lookup* k)
    (catch Exception e nil)))

(defn exists?
  "Returns `true` if key exists in datastore."
  [key]
  (try
    (.doesObjectExist client bucket (str key))
    (catch Exception e nil)))

(defn- write! [k obj]
  (.putObject client bucket (str k) (str obj)))

(defn intern
  "If obj is a valid :openmind.spec/immutable, then it is stored in S3 with key
  equal to its :openmind.hash/ref hash. Returns AWS api put object result on
  success (of the call, you should check the put), or nil on failure. Failure
  cause is logged, but not returned. "
  [obj]
  (if (s/valid? :openmind.spec/immutable obj)
    (let [key (:hash obj)]
      ;; TODO: Locking. We really want to catch and abort on collisions, as
      ;; improbable as they may be.
      (if (exists? key)
        (if (not= (:content obj) (:content (lookup key)))
          (log/error "Collision in data store! Failed to add" obj)
          (log/info "Attempting to add data with hash:" key
                    "which already exists. Doing nothing."))
        (do
          (log/trace "Interning object" obj)
          (write! key obj))))
    (log/error "Invalid data received to intern"
               (s/explain-data :openmind.spec/immutable obj))))

;; TODO: subscription based logic and events tracking changes to the
;; master-index. We don't want 2 round trips for every lookup.
(def ^:private index-cache
  (atom {}))

(defn get-index
  "Cached get for indexed data. Index updates need to invalidate the cache, so
  this doesn't use clojure.core.memoize."
  ;; REVIEW: Though maybe it should...
  [index]
  (if-let [v (get @index-cache index)]
    v
    (let [v (lookup-raw index)]
      (swap! index-cache assoc index v)
      v)))

(defn- index-compare-and-set!
  "Set `index` to new-value iff current value is `old-value`.

  N.B.: This is atomic for a single machine setup, but not currently safe for a
  cluster."
  [index old-value new-value]
  ;; TODO: lock between machines!! Zookeeper, et al..
  (if (s/valid? :openmind.spec.indexical/indexical (:content new-value))
    (locking index
      (let [current (lookup-raw index)]
        (if (= current old-value)
          (do
            ;; TODO: Check for successful write.
            (write! index new-value)
            (swap! index-cache assoc index new-value)
            {:success? true
             :value new-value})
          (do
            (swap! index-cache assoc index current)
            {:success? false}))))
    ;; Return false on fail, nil on error.
    (log/error "Invalid write to index" index new-value)))

(defn assoc-index
  "Set key `k` in (associative) index `index` to `v` using the semantics of
  `swap!`. Applies the change transactionally and retries until success."
  [index k v]
  (let [old (get-index index)
        new (util/immutable (assoc (:content old) k v))]
    (intern new)
    (let [{:keys [success? value]} (index-compare-and-set! index old new)]
      (if success?
        value
        ;; REVIEW: There's the risk of an infinite loop here. How do we impose a
        ;; finite number of retries without breaking callers who assume this is
        ;; like assoc? RTFM isn't exactly an excuse.
        ;; TransactionCouldNotCompleteException maybe...
        (recur index k v)))))
