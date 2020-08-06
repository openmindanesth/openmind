(ns openmind.datastore.indexing
  (:require [clojure.spec.alpha :as s]
            [clojure.stacktrace :as st]
            [openmind.datastore.backends.s3 :as s3]
            [openmind.datastore.impl :as impl]
            [openmind.datastore.shared :refer [get-all]]
            [openmind.hash :as h]
            [taoensso.timbre :as log]))

(def index-map
  (atom #{}))

(defn create-index
  "An index is stored durably on S3, but operations on that index are performed
  and cached locally.
  In order for this to be consistent there must be a single source of truth,
  that is to say that each index must have a dedicated transactor which can
  totally order all operations on that index."
  [key]
  (let [index {:bucket key
               :current-value (atom (impl/lookup key))
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

(defn wrap-meta [prev content]
  {:hash                     (h/hash content)
   :content                  content
   :history/previous-version prev
   :time/created             (java.util.Date.)})

(defn process-index-txs [txs]
  (fn [v0]
    (wrap-meta
     (:hash v0)
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
    (s3/write! (:bucket index) @(:current-value index))
    (when (seq @(:tx-queue index))
      (recur index))))

(def running
  (atom #{}))

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
  (run! drain-index-queue! @index-map))

(def cleanup-on-shutdown
  (.addShutdownHook (Runtime/getRuntime) (Thread. #'flush-queues!)))
