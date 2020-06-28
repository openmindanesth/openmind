(ns openmind.s3
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.memoize :as memo]
            [clojure.spec.alpha :as s]
            [clojure.stacktrace :as st]
            [openmind.edn :as edn]
            [openmind.env :as env]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log])
  (:import com.amazonaws.auth.BasicAWSCredentials
           [com.amazonaws.services.s3 AmazonS3Client AmazonS3ClientBuilder]))

(def recently-added
  "This cache creates read what you've written behaviour for a fundamentally
  fire and forget datastore. This will break down if we move this out to a
  separate process. We'll need to replace it with reddis, or maybe some sort of
  log processing."
  (atom {}))

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
  (memo/lru lookup-raw :lru/threshold 128))

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
    ;; FIXME: This is very confusing
    (catch Exception e (get @recently-added k))))

(defn exists?
  "Returns `true` if key exists in datastore."
  [key]
  (try
    (.doesObjectExist client bucket (str key))
    (catch Exception e nil)))

(defn- write! [k obj]
  (if (nil? obj)
    (log/error "attempting to store nil under" k)
    (.putObject client bucket (str k) (str obj))))

(def ^:private intern-queue
  (ref (clojure.lang.PersistentQueue/EMPTY)))

(defn- get-all [q]
  (dosync
   (let [xs (seq @q)]
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
          (write! key obj)
          (swap! recently-added dissoc key)))))

(def running
  (atom #{}))

(defn- intern-loop []
  (let [xs (get-all intern-queue)]
    (run! intern-from-queue xs)
    (when (seq @intern-queue)
      (recur))))

(defn- drain-intern-queue! []
  (let [runv @running]
    (when-not (contains? runv intern-queue)
      (if (compare-and-set! running runv (conj runv intern-queue))
        (.start (Thread. (fn []
                           (try
                             (intern-loop)
                             (finally
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
      (swap! recently-added assoc (:hash obj) obj)
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
  "Indicies cache their own current value. This just hides that fact and makes
  it appear we're reading from S3 every time (which doesn't work well, but also
  isn't as bad as you think...)"
  [index]
  @(:current-value index))

(defn process-index-txs [txs]
  (fn [v0]
    (util/immutable
     (reduce (fn [v tx]
               (let [v' (tx v)]
                 (log/trace "applying\n" tx)
                 (if (s/valid? :openmind.spec.indexical/indexical v')
                   v'
                   (do
                     (log/error "Invalid index value:\n" v'
                                "\nproduced by:\n" tx
                                "\napplied to\n" v "\n\nskipping transaction")
                     v))))
             (:content v0)
             txs))))

(defn- inner-loop [index]
  (let [txs (get-all (:tx-queue index))]
    (swap! (:current-value index)
           (process-index-txs txs))
    (write! (:bucket index) @(:current-value index))
    (when (seq @(:tx-queue index))
      (recur index))))

(defn drain-index-queue! [index]
  (let [runv @running]
    (when-not (contains? runv index)
      (if (compare-and-set! running runv (conj runv index))
        (.start (Thread. (fn []
                           (try
                             (inner-loop index)
                             (catch Exception e
                               (log/error "error processing" (:bucket index)
                                          ":\n" (with-out-str
                                                  (st/print-stack-trace e))))
                             (finally
                               (swap! running disj index))))))
        (recur index)))))

(defn swap-index! [index f]
  (log/trace "queueing tx on:" (:bucket index) "\n" f)
  (dosync
   (alter (:tx-queue index) conj f))
  (drain-index-queue! index)
  {:status  :success
   :message "Index update queued."})

(defn flush-queues! []
  (when (seq @intern-queue)
    (drain-intern-queue!))
  (run! drain-index-queue! @index-map))

(def cleanup-on-shutdown
  (.addShutdownHook (Runtime/getRuntime) (Thread. #'flush-queues!)))
