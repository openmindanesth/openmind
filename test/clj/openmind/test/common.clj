(ns openmind.test.common
  (:require [openmind.datastore.impl :as dsi]
            [openmind.datastore.backends.s3 :as s3]
            [openmind.datastore.indexing :as indexing]))

(defn queues-cleared? []
  (dosync
   (and (empty? @indexing/running)
        (empty? @dsi/running)
        (empty? (ensure @#'dsi/intern-queue))
        (every? empty? (map (comp ensure :tx-queue) @indexing/index-map)))))

(defn wait-for-queues
  "Polls intern and indexing queues until all writes are finished. good enough
  for tests."
  []
  (loop []
    (when-not (queues-cleared?)
      (Thread/sleep 100)
      (recur))))

(def s3-mock (atom {}))

(defn stub-s3 [f]
  (with-redefs [s3/exists? #(contains? @s3-mock %)
                s3/lookup #(get @s3-mock % nil)
                s3/write! (fn [k o] (swap! s3-mock assoc k o))]
    (reset! s3-mock {})
    (f)))
