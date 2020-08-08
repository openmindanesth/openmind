(ns openmind.datastore.routing
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.datastore.impl :as impl]
            [taoensso.timbre :as log]))

(defonce tx-ch
  (async/chan 128))

(defonce multi-ch
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

(defn- publish! [assertions]
  (async/onto-chan! tx-ch assertions false))

(defn publish-transaction! [{:keys [assertions context]}]
  (publish! (mapv (fn [[_ h  _ _ :as tx]]
                    (conj tx (get context h)))
                  assertions)))

(defn start-listener [handler]
  (let [tx-ch (tx-log)]
    (async/go-loop []
      (when-let [tx (async/<! tx-ch)]
        (try
          (handler tx)
          (catch Exception e
            (log/error "exception caught in indexer loop: " e
                       "\n" (with-out-str (.printStackTrace e)))))
        (recur)))
    (fn [] (async/close! tx-ch))))

;;;;; test mock

(defn test-publish!
  "Only use this from tests."
  [assertions]
  (run! #(async/>!! tx-ch %) assertions))
