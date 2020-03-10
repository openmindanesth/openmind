(ns openmind.elastic
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.env :as env]
            [openmind.json :as json]
            [openmind.s3 :as s3]
            [openmind.tags :as tags]
            [openmind.util :as util]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.text SimpleDateFormat]
           [openmind.hash ValueRef]))

(def index (env/read :elastic-extract-index))

(def mapping
  {:properties {:created-time {:type :date}
                ;; FIXME: Going back to 7.1 means no search_as_you_type
                ;; that could be something of a problem.
                :text         {:type :text}
                :hash         {:type :keyword}
                :source       {:type :object}
                :figure       {:type :text}
                :tags         {:type :keyword}
                :extract-type {:type :keyword}
                :author       {:type :object}}})

;;;;; REST API wrapping

(def base-req
  (merge
   {:headers {"Content-Type" "application/json"}
    :user-agent "Openmind server"}
   (when env/dev-mode?
     {:basic-auth [(env/read :elastic-username) (env/read :elastic-password)]})))

(def base-url
  (let [^String url (env/read :elastic-url)]
    (if (.endsWith url "/")
      (apply str (butlast url))
      url)))

(defn index-req [index doc & [key]]
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

(def dateformat
  (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))

(defn parse-dates [doc]
  (walk/prewalk
   (fn [x] (if (inst? x) (.format ^SimpleDateFormat dateformat x) x))
   doc))

(defn prepare-extract [ext]
  (-> ext
      parse-dates))

(defn index-extract!
  "Given an immutable, index the contained extract in es."
  [imm]
  (async/go
    (if (s/valid? :openmind.spec.extract/extract (:content imm))
      (let [ext (assoc (:content imm) :hash (:hash imm))
            key (.-hash-string ^ValueRef (:hash imm))
            res (async/<! (send-off!
                           (index-req index (prepare-extract ext) key)))]
        (log/info res))
      (log/error "Trying to index invalid extract:" imm))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Initialising the DB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn init-elastic []
  (async/go
    ;; These have to happen sequentially.
    (println (async/<! (send-off! (assoc (create-index index) :method :delete))))
    (println (async/<! (send-off! (create-index index))))
    (println (async/<! (send-off! (set-mapping index))))))

;; TODO: Invaluable dev helpers.
;;
;; we should dump all of the extracts from ES as well as any chucks they depend
;; on.
(defn dump-es! [filename])

(defn restore-es! [filename])
