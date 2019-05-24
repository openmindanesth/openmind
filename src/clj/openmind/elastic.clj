(ns openmind.elastic
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [openmind.env :as env]
            [org.httpkit.client :as http]))

(def base-req
  {:basic-auth [(env/read :elastic-username) (env/read :elastic-password)]
   :headers {"Content-Type" "application/json"}
   :user-agent "Openmind server"})

(def base-url
  (str (env/read :elastic-url)))

(def cluster-settings
  (merge base-req
         {:method :get
          :url (str base-url "/_cluster/settings")}))

(defn index [index doc]
  (merge base-req
         {:method :post
          :url (str base-url "/" (name index) "/_doc/")
          :body (json/write-str doc)}))

(defn search [index filters]
  (let [qbody (json/write-str {:query
                               {:term {:search.species :mouse
                                       :search.method :awesome}}}) ]
    (merge base-req
           {:method :get
            :url (str base-url "/" (name index) "/_search")
            :body qbody})))

(defn send-off!
  "Sends HTTP request req and returns a core.async promise channel which will
  eventually contain the body of the result."
  [req]
  (let [out-ch (async/promise-chan)]
    (http/request
     req
     (fn [{:keys [status body] :as res}]
       (println res)
       ;; TODO: Basic resiliency...
       (if (<= 200 status 299)
         (async/put! out-ch (json/read-str body :key-fn keyword))
         (async/close! out-ch))))
    out-ch))
