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
  ;; exist yet doesn't mean it never will (though the odds of that being a
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

(def ^:private intern-queue
  (ref (clojure.lang.PersistentQueue/EMPTY)))

(defn- get-all [q]
  (dosync
   (let [xs @q]
     (ref-set q (clojure.lang.PersistentQueue/EMPTY))
     xs)))

(defn- intern-from-queue [obj]
  (let [key (:hash obj)]
      (if (exists? key)
        (if (not= (:content obj) (:content (lookup key)))
          (log/error "Collision in data store! Failed to add" obj)
          (log/info "Attempting to add data with hash:" key
                    "which already exists. Doing nothing."))
        (do
          (log/trace "Interning object\n" obj)
          (write! key obj)))))

(def running
  (atom #{}))

(defn- drain-intern-queue! []
  (let [runv @running]
    (when-not (contains? runv intern-queue)
      (if (compare-and-set! running runv (conj runv intern-queue))
        (.start (Thread. (fn []
                         (let [xs (get-all intern-queue)]
                           (run! intern-from-queue xs)
                           (if (seq @intern-queue)
                             (recur)
                             (swap! running disj intern-queue))))))
        (recur)))))

(def index-map
  (atom #{}))

(defn intern
  "If obj is a valid :openmind.spec/immutable, then it is stored in S3 with key
  equal to its :openmind.hash/ref hash. Returns AWS api put object result on
  success (of the call, you should check the put), or nil on failure. Failure
  cause is logged, but not returned. "
  [obj]
  (if (s/valid? :openmind.spec/immutable obj)
    (do
      (dosync
       (alter intern-queue conj obj))
      (drain-intern-queue!)
      {:status  :success
       :message "queued for permanent storage."})
    (log/error "Invalid data received to intern\n" obj)))

(defn create-index
  "An index is stored durably on S3, but operations on that index are performed
  and cached locally.
  In order for this to be consistent there must be a single source of truth,
  that is to say that each index must have a dedicated transactor which can
  totally order all operations on that index."
  [key]
  (let [index {:bucket key
               :current-value (atom (lookup key))
               ;; This doesn't need to be a ref, it just makes draining the
               ;; queue a little simpler. Is that sufficient reason?
               :tx-queue (ref (clojure.lang.PersistentQueue/EMPTY))}]
    (swap! index-map conj index)
    index))

(defn get-index
  "Cached get for indexed data. Index updates need to invalidate the cache, so
  this doesn't use clojure.core.memoize."
  ;; REVIEW: Though maybe it should...
  [index]
  (:content @(:current-value index)))

(defn update-index [txs]
  (fn [v0]
    (util/immutable
     (reduce (fn [v tx]
               (let [v' (apply (first tx) v (rest tx))]
                 (if (s/valid? (:openmind.spec.indexical/indexical v'))
                   v'
                   (do
                     (log/error "Invalid index value produced by:\n" tx
                                "\napplied to\n" v "\n\nskipping transaction")
                     v))))
             v0 txs))))

(defn drain-index-queue [index]
  (let [runv @running]
    (when (contains? runv index)
      (if (compare-and-set! running runv (conj runv index))
        (.start (Thread. (fn []
                           (let [txs (get-all (:tx-queue index))]
                             (swap! (:current-value index)
                                    (update-index txs))
                             (write! (:bucket index) @(:current-value index))
                             (when (seq @(:tx-queue index))
                               (recur))))))
        (recur index)))))

(defn assoc-index
  "Set key `k` in (associative) index `index` to `v` using the semantics of
  `swap!`. Applies the change transactionally and retries until success."
  [index k v & kvs]
  (assert
   (= 0 (mod (count kvs) 2))
   "assoc-index requires an whole number of key-value pairs just as assoc")
  (dosync
   (alter (:tx-queue index) conj (into [assoc k v] kvs)))
  (drain-index-queue index)
  {:status :success
   :message "Index update queued."})

(defn update-index [index f & args]
  (dosync
   (alter (:tx-queue index) conj (into [update f] args)))
  (drain-index-queue index)
  {:status :success
   :message "Index update queued."})

(defn flush-queues! []
  (when (seq @intern-queue)
    (drain-intern-queue intern-queue intern-from-queue []))
  (run! drain-index-queue @index-map))

(def cleanup-on-shutdown
  (.addShutdownHook (Runtime/getRuntime) (Thread. #'flush-queues!)))
