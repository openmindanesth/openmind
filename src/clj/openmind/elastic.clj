(ns openmind.elastic
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [openmind.env :as env]
            [openmind.tags :refer [tag-tree]]
            [org.httpkit.client :as http]))

;; FIXME:
(def index :extracts0)
(def tag-index :tags0)

(def mapping
  {:properties {:created {:type :date}}})

;;;;; Translation from client to Elastic Search

(defn tag-name [k]
  (str "tags." (name k)))

(defn build-filter-query [filters]
  (into []
        (comp (map (fn [[k v]]
                     (when (seq v)
                       (if (= 1 (count v))
                         {:term {(tag-name k) (first v)}}
                         {:terms_set {(tag-name k)
                                      {:terms (into [] v)
                                       ;; FIXME: This is positively
                                       ;; idiotic... but it works!
                                       :minimum_should_match_script
                                       {:source "1"}}}}))))
              (remove nil?))
        filters))

(defn search->elastic [{:keys [term filters]}]
  {:sort  {:created {:order :desc}}
   :from  0
   :size  20
   :query {:bool (merge {}
                        (when (seq filters)
                          {:filter (build-filter-query filters)})
                        (when (seq term)
                          ;; TODO: Better prefix search:
                          ;; https://www.elastic.co/guide/en/elasticsearch/guide/master/_index_time_search_as_you_type.html
                          ;; or
                          ;; https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters-completion.html
                          ;; or
                          ;; https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-edgengram-tokenizer.html
                          {:must {:match_phrase_prefix {:text term}}}))}})

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

(def set-mapping
  (merge base-req
         {:method :put
          :url (str base-url "/" (name index) "/_mapping")
          :body (json/write-str mapping)}))

(def create-index
  (assoc base-req
         :url (str base-url "/" (name index))
         :method :put))

;;;;; Tags in elastic

(defn get-doc [index id]
  (assoc base-req
         :method :get
         :url (str base-url "/" (name index) "/_doc/" id)))

(defn find-id [res]
  (when (:body res)
    (when-let [body (json/read-str (:body res))]
      (when (= "created" (get body "result"))
        (get body "_id")))))

(defn index-tag [index tag-data]
  (assoc base-req
         :url (str base-url "/" (name index) "/_doc/")
         :method :post
         :body (json/write-str tag-data)))

(defn subtag-lookup [index root]
  (let [query {:size 1000 :query {:match {:parents root}}}]
    (search index query)))

(defn top-level-tags [index]
  (search index {:query {:bool {:must_not {:exists {:field :parents}}}}}))

;;;;; Wheel #6371

(defn parse-response
  "Interpret Elastic Search status codes and parse response appropriately."
  [{:keys [status body] :as res}]
  (if (seq res)
    (cond
      (<= 200 status 299) (json/read-str body :key-fn keyword)
      (= 404 status)      []
      :else               (do
                            (println "Elastic Error:" )
                            (pprint res)))
    (println "Error nil response!")))

(defn send-off!
  "Sends HTTP request req and returns a core.async promise channel which will
  eventually contain the result."
  [req]
  (let [out-ch (async/promise-chan)]
    (http/request req (fn [res]
                        ;; TODO: Logging
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
  (search index {:sort {:created {:order :desc}}
                  :from 0
                  :size 10}))

;;;;; Tag init hack

(defn index-tag-tree [index tree parents]
  (run! (fn [[k v]]
          (async/go
            (let [tag-data {:tag-name k
                            :parents parents}
                  id (find-id (async/<! (send-off! (index-tag index tag-data))))]
              (tap> [k id parents])
              (index-tag-tree index v (conj parents id)))))
        tree))
