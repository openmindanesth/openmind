(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.walk :as walk]
            [openmind.elastic :as es]))


(defn launch-search [query]
  (async/go
    (es/parse-response
     (async/<!
      (es/send-off! (es/search es/index (es/search->elastic query)))))))


(defn parse-search-response [res]
  (mapv :_source (:hits (:hits res))))

(defmulti dispatch (fn [e] (first (:event e))))

(defmethod dispatch :chsk/ws-ping
  [_])

(defmethod dispatch :chsk/uidport-open
  [_])

(defmethod dispatch :default
  [e]
  (println "Unhandled client event:" e)
  ;; REVIEW: Dropping unhandled messages is suboptimal.
  nil)

(defmethod dispatch :openmind/search
  [{[_ {:keys [user query]}] :event send-fn :send-fn}]
  (let [nonce (:nonce query)]
    (async/go
      (let [res (parse-search-response (async/<! (launch-search query)))]
        (send-fn user [:openmind/search-response {:results res :nonce nonce}])))))

(defn prepare-doc [doc]
  (let [formatter (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX")]
    (walk/prewalk
     (fn [x] (if (inst? x) (.format formatter x) x))
     doc)))

(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn] [_ doc] :event}]
  (async/go
    (async/<! (es/send-off! (es/index-req es/index (prepare-doc doc))))))
