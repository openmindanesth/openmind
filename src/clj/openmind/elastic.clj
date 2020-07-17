(ns openmind.elastic
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.env :as env]
            [openmind.json :as json]
            [openmind.s3 :as s3]
            [openmind.tags :as tags]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import openmind.hash.ValueRef))

(def index (env/read :elastic-extract-index))

(def mapping
  {:properties {:time/created             {:type :date}
                :history/previous-version {:type  :keyword
                                           :index false}
                :extract/type             {:type :keyword}

                :text      {:type :search_as_you_type}
                :hash      {:type  :keyword
                            :index false}
                :figure    {:type  :text
                            :index false}
                :resources {:type  :object
                            :index false}
                :source    {:type       :object
                            :properties {:publication/date {:type :date}
                                         :observation/date {:type :date}
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
                                         :authors          {:type  :object
                                                            :index false}
                                         :lab              {:type  :text
                                                            :index false}
                                         :institution      {:type  :text
                                                            :index false}
                                         :investigator     {:type  :text
                                                            :index false}}}
                :tags      {:type :keyword}
                :tag-names {:type :text}
                :author    {:type       :object
                            :properties {:full-name  {:type :text}
                                         :short-name {:type :text}
                                         :orcid-id   {:type :keyword}}}}})

;;;;; REST API wrapping

(def base-req
  {:headers {"Content-Type" "application/json"}
   :user-agent "Openmind server"})

(def base-url
  (let [^String url (env/read :elastic-url)]
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

(defn set-mapping [index]
  (merge base-req
         {:method :put
          :url (str base-url "/" index "/_mapping")
          :body (json/write-str mapping)}))

(defn create-index [index]
  (assoc base-req
         :url (str base-url "/" index)
         :method :put))

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
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def active-es-index
  (s3/create-index
   "openmind.indexing/elastic-active"))

(defn add-to-index [id]
  (s3/swap-index! active-es-index (fn [i]
                                    (if (empty? i)
                                      #{id}
                                      (conj i id)))))

(defn replace-in-index [old new]
  (s3/swap-index! active-es-index (fn [i]
                                    (-> i
                                        (disj old)
                                        (conj new)))))

(defn remove-from-index [id]
  (log/warn "Removing from elastic:" id)
  (s3/swap-index! active-es-index (fn [i] (disj i id)))
  (send-off! (delete-req index (.-hash-string ^ValueRef id))))

(defn index-extract!
  "Given an immutable, index the contained extract in es."
  [{{:keys [tags]} :content :as imm}]
  (async/go
    (if (s/valid? :openmind.spec.extract/extract (:content imm))
      ;; TODO: Index the nested object instead of flattening it.
      (let [tag-names (map #(:name (get tags/tag-tree %)) tags)
            ext       (assoc (:content imm)
                             :tag-names tag-names
                             :hash (:hash imm)
                             :time/created (:time/created imm))
            key       (.-hash-string ^ValueRef (:hash imm))
            res       (async/<! (send-off!
                                 (index-req index ext key)))]
        (log/trace "Indexed" (:hash imm) res)
        res)
      (log/error "Trying to index invalid extract:" imm))))

(defn retract-extract! [^ValueRef hash]
  (async/go
    (let [res (-> (delete-req index (.-hash-string hash))
                  send-off!
                  async/<!)]
      (log/trace "Retracted " hash res)
      res)))

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
