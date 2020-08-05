(ns openmind.datastore.routing
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.datastore.impl :as impl]
            [taoensso.timbre :as log]))

(def tx-ch
  (async/chan 128))

(def multi-ch
  (async/mult tx-ch))

(defn tx-log
  "Returns a channel which will emit every value asserted or transacted by any
  connected client."
  []
  (let [ch (async/chan 128)]
    (async/tap multi-ch ch)
    ch))

(defn destroy-tx-log-watcher [ch]
  (async/untap multi-ch ch)
  (async/close! ch))

(defn- publish! [[t h _ _ :as tx] context]
  (async/put! tx-ch (conj tx (get context h))))

(defn publish-transaction! [{:keys [assertions context]}]
  (run! #(publish! % context) assertions))
