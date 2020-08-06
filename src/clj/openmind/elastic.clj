(ns openmind.elastic
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [openmind.datastore :as ds]
            [openmind.env :as env]
            [openmind.json :as json]
            [openmind.tags :as tags]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import openmind.hash.ValueRef))

(def index (env/read :elastic-extract-index))

(def source-mapping
  {:type       :object
   :properties {:publication/date {:type  :date
                                   :index false}
                :observation/date {:type  :date
                                   :index false}
                :doi              {:type :keyword}
                :abstract         {:type  :text
                                   :index false}
                :title            {:type  :text
                                   :index false}
                :url              {:type  :text
                                   :index false}
                :journal          {:type  :text
                                   :index false}
                :volume           {:type  :text
                                   :index false}
                :issue            {:type  :text
                                   :index false}
                :lab              {:type  :text
                                   :index false}
                :institution      {:type  :text
                                   :index false}
                :investigator     {:type  :text
                                   :index false}
                :authors          {:type :object
                                   :properties
                                   {:short-name {:type  :text}
                                    :full-name  {:type  :text}
                                    :orcid-id   {:type  :keyword
                                                 :index false}}}}})

(def mapping
  {:properties {:time/created             {:type :date}
                ;; hack to combine labnotes and extracts
                :es/pub-date              {:type :date}
                :history/previous-version {:type  :keyword
                                           :index false}
                :extract/type             {:type :keyword}

                :source source-mapping

                :text      {:type     :text
                            :analyzer :english}
                :hash      {:type  :keyword
                            :index false}
                :figure    {:type  :text
                            :index false}
                :resources {:type       :object
                            :properties {:label {:type  :text
                                                 :index false}
                                         :link  {:type  :keyword
                                                 :index false}}}
                :tags      {:type :keyword}
                :tag-names {:type :search_as_you_type}
                :author    {:type       :object
                            :properties {:name     {:type :text}
                                         :orcid-id {:type :keyword}}}}})

;;;;; REST API wrapping

(def base-req
  {:headers {"Content-Type" "application/json"}
   :user-agent "Openmind server"})

(def base-url
  (when-let [^String url (env/read :elastic-url)]
    (if (.endsWith url "/")
      (apply str (butlast url))
      url)))

(defn index-req [index doc key]
  (merge base-req
         {:method :post
          :url (str base-url "/" index "/_doc/" key)
          :body (json/write-str doc)}))

(defn delete-req [index key]
  (-> (index-req index nil key)
      (dissoc :body)
      (assoc :method :delete)))

(defn search [index body]
  (let [qbody (json/write-str body)]
    (merge base-req
           {:method :get
            :url (str base-url "/" index "/_search")
            :body qbody})))

(defn lookup [index id]
  (assoc base-req
         :url (str base-url "/" index "/_doc/" id)))

(defn update-doc [index id body]
  (let [body (json/write-str body)]
    (assoc base-req
           :url (str base-url "/" index "/_doc/" id)
           :method :put
           :body body)))

;;;;; Init new index

(defn create-index [index]
  (assoc base-req
         :url (str base-url "/" index)
         :method :put))

(defn set-mapping [index]
  (merge base-req
         {:method :put
          :url (str base-url "/" index "/_mapping")
          :body (json/write-str mapping)}))

;;;;; Searching

(def sorter-map
  {:publication-date      {:es/pub-date :desc}
   :extract-creation-date {:time/created :desc}})

;; FIXME: This is utterly attrocious. It's basically impossible to get the
;; nesting of maps and vectors right without running the tests over an over like
;; a trained monkey.
(defn search-all [term]
  (let [tokens (remove empty? (string/split term #"\s"))]
    {:dis_max
     {:queries
      (into [{:match {:text term}}]
            (comp cat (remove nil?))
            (concat
             [(when (< 2 (count term))
                [{:match_phrase_prefix {:text term}}])]
             (map (fn [t]
                    (concat
                     [{:multi_match
                       {:query  t
                        :fields [:source.doi
                                 :author.name
                                 :author.orcid-id]}}]
                     (when (< 2 (count t))
                       [{:match_phrase_prefix {:tag-names t}}
                        {:match_phrase_prefix {:author.name t}}])))
                  tokens)))}}))

(defn elasticise [{:keys [term filters sort-by type limit offset]}]
  (let [term (string/trim term)]
    (merge
     {:from  0
      :size  20
      ;; TODO: extract votes in mapping
      ;; TODO: Advanced search
      :query {:bool {:filter (tags/tags-filter-query
                              ;; FIXME: Hardcoded anaesthesia
                              "anaesthesia" filters)
                     :must (into []
                                 (comp cat (remove nil?))
                                 (concat
                                  [(when (and type (not= type :all))
                                     [{:term {:extract/type type}}])]
                                  [(when (seq term)
                                     [(search-all term)])]))}}}
     (when sort-by
       {:sort (get sorter-map (or sort-by :extract-creation-date))})
     (when offset
       {:from offset})
     (when limit
       {:size limit}))))

(defn search-q [q]
  (search index (elasticise q)))

;;;;; Wheel #6371

(defn parse-response
  "Interpret Elastic Search status codes and parse response appropriately."
  [{:keys [status body] :as res}]
  (if status
    (cond
      (<= 200 status 299) (json/read-str body)
      (= 404 status)      []
      :else               (log/error "Elastic Search error response:" res) )
    (log/error "No response from Elastic Search:" res)))

(defn send-off!
  "Sends HTTP request req and returns a core.async promise channel which will
  eventually contain the result."
  [req]
  (if (:url req)
    (let [out-ch (async/promise-chan)]
      (log/trace "Elastic request: " (select-keys req [:method :url :body]))
      (http/request req (fn [res]
                          (log/trace "Response from elastic: "
                                     (-> res
                                         (select-keys
                                          [:body :opts :status :error])
                                         (update :opts select-keys
                                                 [:method :body :url])))
                          (async/put! out-ch res)))
      out-ch)
    (log/error "No Elastic URL set")))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract indexing
;;
;; Should this be in the datastore/indicies folder? I'm honestly conflicted
;; about that; indicies are business logic, whereas the datastore is a
;; library. Or such is the design...
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def active-es-index
  (ds/create-index
   "openmind.indexing/elastic-active"))

(defn add-to-index [id]
  (log/trace "adding to elastic: " id)
  (ds/swap-index! active-es-index (fn [i]
                                    (if (empty? i)
                                      #{id}
                                      (conj i id)))))

(defn remove-from-index [id]
  (log/trace "Removing from elastic:" id)
  (ds/swap-index! active-es-index (fn [i] (disj i id))))

(defn index-extract!
  "Index extract in elastic and add it to the active-es-index."
  [^ValueRef hash created author content]
  (async/go
    (if (s/valid? :openmind.spec.extract/extract content)
      (let [{:keys [tags source]} content]
        (add-to-index hash)
        (let [tag-names (map #(:name (get tags/tag-tree %)) tags)
              date      (or (:publication/date source)
                            (:observation/date source))
              ext       (merge content
                               {:tag-names tag-names
                                :es/pub-date date
                                :hash hash
                                :time/created created}
                               (when author
                                 {:author author}))
              key       (.-hash-string hash)
              res       (async/<! (send-off!
                                   (index-req index ext key)))]
          (log/trace "Indexed" hash res)
          res))
      (log/error "Trying to index invalid extract:" content))))

(defn retract-extract! [^ValueRef hash]
  (async/go
    (let [res (-> (delete-req index (.-hash-string hash))
                  send-off!
                  async/<!)]
      (remove-from-index hash)
      (log/trace "Retracted " hash res)
      res)))

(defn handle [[assertion hash author created obj]]
  (let [content (or obj (:content (ds/lookup hash)))]
    (when (s/valid? :openmind.spec/extract content)
      (case  assertion
        :assert  (index-extract! hash created author content)
        :retract (retract-extract! hash)))))

;; TODO: This boilerplate is identical to the metadata index and will likely be
;; repeated in all indicies. Create a higher order registration fn.
(defonce tx-ch (atom nil))

(defn start-indexing! []
  (when-let [ch @tx-ch]
    (async/close! ch))
  (reset! tx-ch (ds/tx-log))
  (async/go-loop []
    (when-let [assertion (async/<! @tx-ch)]
      (handle assertion)
      (recur))))

(defn stop-indexing! []
  (async/close! @tx-ch))

;;;;; Testing helpers

(def tx (atom nil))
(defn t [q] (async/go (reset! tx (async/<! (send-off! q)))))

(def cluster-settings
  (merge base-req
         {:method :get
          :url (str base-url "/_cluster/settings")}))

(def most-recent
  (search index {:sort {:time/created {:order :asc}}
                 :from 0
                 :size 100}))
