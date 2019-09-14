(ns openmind.elastic
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [openmind.env :as env]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

(def index (env/read :elastic-extract-index))
(def tag-index :tags0)

(def mapping
  {:properties {:created-time {:type :date}
                :tags {:type :keyword}}})

;;;;; REST API wrapping

(def base-req
  {:basic-auth [(env/read :elastic-username) (env/read :elastic-password)]
   :headers {"Content-Type" "application/json"}
   :user-agent "Openmind server"})

(def base-url
  (str (env/read :elastic-url)))

(defn index-req [index doc]
  (merge base-req
         {:method :post
          :url (str base-url "/" (name index) "/_doc/")
          :body (json/write-str doc)}))

(defn search [index body]
  (let [qbody (json/write-str body)]
    (merge base-req
           {:method :get
            :url (str base-url "/" (name index) "/_search")
            :body qbody})))

;;;;; Init new index

(defn set-mapping [index]
  (merge base-req
         {:method :put
          :url (str base-url "/" (name index) "/_mapping")
          :body (json/write-str mapping)}))

(defn create-index [index]
  (assoc base-req
         :url (str base-url "/" (name index))
         :method :put))

;;;;; Tags in elastic

(defn index-tag [index tag-data]
  (assoc base-req
         :url (str base-url "/" (name index) "/_doc/")
         :method :post
         :body (json/write-str tag-data)))

;;;;; Wheel #6371

(defn parse-response
  "Interpret Elastic Search status codes and parse response appropriately."
  [{:keys [status body] :as res}]
  (if status
    (cond
      (<= 200 status 299) (json/read-str body :key-fn keyword)
      (= 404 status)      []
      :else               (log/error "Elastic Search error response:" res) )
    (log/error "No response from Elastic Search:" res)))

(defn send-off!
  "Sends HTTP request req and returns a core.async promise channel which will
  eventually contain the result."
  [req]
  (let [out-ch (async/promise-chan)]
    (log/trace "Elastic request: " (select-keys req [:method :url :body]))
    (http/request req (fn [res]
                        (log/trace "Response from elastic: "
                                  (-> res
                                       (select-keys [:body :opts :status :error])
                                       (update :opts select-keys
                                               [:method :body :url])))
                        (async/put! out-ch res)))
    out-ch))

(defmacro request<!
  "Must be called inside a go block. Sends request, and returns processed result
  list into current context."
  [req]
  `(-> ~req
       send-off!
       async/<!
       parse-response
       :hits
       :hits))

;;;;; Testing helpers

(def tx (atom nil))
(defn t [q] (async/go (reset! tx (async/<! (send-off! q)))))

(def cluster-settings
  (merge base-req
         {:method :get
          :url (str base-url "/_cluster/settings")}))

(def most-recent
  (search index {:sort {:created-time {:order :asc}}
                 :from 0
                 :size 10}))

;;;;; Tag init hack

(defn create-and-init!
  "Setup indicies on a blank instance and populate basic tags."
  []
  ;; FIXME: This doesn't work because of timing issues. Figure that out, or
  ;; write better documentation on how to initialise a new elastic DB.

  ;; (t create-index)
  ;; (t set-mapping)
  ;; (index-tag-tree tag-index tag-tree [])
  )
