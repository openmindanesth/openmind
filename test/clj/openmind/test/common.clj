(ns openmind.test.common
  (:require [clojure.core.async :as async]
            [openmind.datastore.backends.s3 :as s3]
            [openmind.datastore.impl :as dsi]
            [openmind.datastore.indexing :as indexing]
            [openmind.datastore.routing :as routing]
            [openmind.elastic :as es]
            [openmind.notification :as notify]))

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
  (indexing/flush-queues!)
  (loop []
    (when-not (queues-cleared?)
      (Thread/sleep 100)
      (recur))))

(defmacro stub-s3
  "Interact with a local atom instead of S3 as the datastore backend KV store."
  [f]
  `(let [s3-mock# (atom {})]
    (with-redefs [s3/exists? #(contains? @s3-mock# %)
                  s3/lookup #(get @s3-mock# % nil)
                  s3/write! (fn [k# o#] (swap! s3-mock# assoc k# o#))]
      ~f)))

(defmacro redirect-notifications
  "Redirects all notifications onto `notification-ch` to test delivery."
  [notification-ch f]
  `(with-redefs [notify/get-all-connections-fn (atom (fn [] ["uid1"]))

                 notify/send-fn (atom
                                 (fn [_# m#]
                                   (async/put! ~notification-ch m#)))]
    ~f))

(defmacro stub-elastic
  "Stubs out elasticsearch interaction with noops."
  [f]
  `(with-redefs [es/index-extract!   (fn [& _#] (async/go {:status 200}))
                 es/add-to-index     (fn [& _#] (async/go {:status 200}))
                 es/retract-extract! (fn [& _#] (async/go {:status 200}))]
    ~f))

(defmacro syncronise-publications
  "Replace async pub-sub system with syncronous message processing."
  [f]
  `(with-redefs [routing/publish! routing/test-publish!]
     ~f))
