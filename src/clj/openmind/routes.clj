(ns openmind.routes
  (:require [clojure.core.async :as async]
            [openmind.elastic :as es]))

;; FIXME:
(def index :test0)

(defn launch-search [query]
  (async/go
    (es/parse-response
     (async/<!
      (es/send-off! (es/search index (es/search->elastic query)))))))

(defmulti dispatch (fn [socket e] (first (:event e))))

(defmethod dispatch :openmind/search
  [{:keys [send-fn]} {id :client-id [_ query] :event}]
  (let [nonce (:nonce query)]
    (async/go
      (let [res (async/<! (launch-search query))]
        (clojure.pprint/pprint res)
        (send-fn id [:openmind/search-response {:results res :nonce nonce}])))))

(defmethod dispatch :default
  [socket e]
  (println "Unhandled client event:" e)
  ;; REVIEW: Dropping unhandled messages is suboptimal.
  nil)
