(ns openmind.datastore.impl
  (:refer-clojure :exclude [intern])
  (:require [clojure.spec.alpha :as s]
            [clojure.stacktrace :as st]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

(def recently-added
  "This cache creates read what you've written behaviour for a fundamentally
  fire and forget datastore. This will break down if we move this out to a
  separate process. We'll need to replace it with reddis, or maybe some sort of
  log processing."
  (atom {}))

(defn lookup
  "Returns full doc from S3 associated with key `k`."
  [k]
  (or
   (s3/lookup k)
   (get @recently-added k)))

(def ^:private intern-queue
  (ref (clojure.lang.PersistentQueue/EMPTY)))

(defn- get-all [q]
  (dosync
   (let [xs (seq (ensure q))]
     (ref-set q (clojure.lang.PersistentQueue/EMPTY))
     xs)))

(defn- intern-from-queue [obj]
  (let [key (:hash obj)]
      (if (s3/exists? key)
        (if (not= (:content obj) (:content (lookup key)))
          (log/error "Collision in data store! Failed to add" obj)
          (log/info "Attempting to add data with hash:" key
                    "which already exists. Doing nothing."))
        (do
          (log/trace "Interning object\n" obj)
          (s3/write! key obj)))
      (swap! recently-added dissoc key)
      nil))

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

(defn flush-queues! []
  (when (seq @intern-queue)
    (drain-intern-queue!)))

(def cleanup-on-shutdown
  (.addShutdownHook (Runtime/getRuntime) (Thread. #'flush-queues!)))
