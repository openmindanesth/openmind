(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.walk :as walk]
            [openmind.elastic :as es]))

;; FIXME:
(def index :test0)

(defn launch-search [query]
  (async/go
    (es/parse-response
     (async/<!
      (es/send-off! (es/search index (es/search->elastic query)))))))

(defmulti dispatch (fn [socket e] (first (:event e))))

(defmethod dispatch :default
  [socket e]
  (println "Unhandled client event:" e)
  ;; REVIEW: Dropping unhandled messages is suboptimal.
  nil)

(defmethod dispatch :openmind/search
  [{:keys [send-fn]} {id :client-id [_ query] :event}]
  (let [nonce (:nonce query)]
    (async/go
      (let [res (async/<! (launch-search query))]
        (clojure.pprint/pprint res)
        (send-fn id [:openmind/search-response {:results res :nonce nonce}])))))

(defn prepare-doc [doc]
  (let [formatter (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX")]
    (walk/prewalk
     (fn [x] (if (inst? x) (.format formatter x) x))
     doc)))

(defmethod dispatch :openmind/index
  [{:keys [send-fn]} {:keys [client-id] [_ doc] :event}]
  (clojure.pprint/pprint (prepare-doc doc))
  (async/go
    (clojure.pprint/pprint (async/<! (es/send-off! (es/index index (prepare-doc doc)))))))
