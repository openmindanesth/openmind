(ns openmind.elastic
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.env :as env]
            [openmind.json :as json]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import openmind.hash.ValueRef))

(def index (env/read :elastic-extract-index))

(def mapping
  {:properties {:time/created {:type :date}
                ;; FIXME: Going back to 7.1 means no search_as_you_type
                ;; that could be something of a problem.
                :text         {:type :text}
                :hash         {:type :keyword}
                :source       {:type       :object
                               :properties {:publication/date {:type :text}}}
                :figure       {:type :text}
                :tags         {:type :keyword}
                :extract/type {:type :keyword}

                :author {:type :object}}})

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

(defn index-extract!
  "Given an immutable, index the contained extract in es."
  [imm]
  (async/go
    (if (s/valid? :openmind.spec.extract/extract (:content imm))
      ;; TODO: Index the nested object instead of flattening it.
      (let [ext (assoc (:content imm)
                       :hash (:hash imm)
                       :time/created (:time/created imm))
            key (.-hash-string ^ValueRef (:hash imm))
            res (async/<! (send-off!
                           (index-req index ext key)))]
        (log/trace "Indexed" res)
        res)
      (log/error "Trying to index invalid extract:" imm))))

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

(defn all-ids []
  ;; HACK: Not all
  (async/go
    (map :_id (request<! most-recent))))

(defn update-type-req [index id]
  (async/go
    (let [body (-> (lookup index id)
                   send-off!
                   async/<!
                   parse-response
                   :_source
                   (assoc :extract-type :article))]
      (update-doc index id body))))

(defn add-extract-to-all []
  (async/go
    (let [ids (async/<! (all-ids))]
      (run! #(async/go
               (println
                (:status
                 (async/<!
                  (send-off! (async/<! (update-type-req index %)))))))
            ids))))
