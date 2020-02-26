(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.elastic :as es]
            [openmind.pubmed :as pubmed]
            [openmind.spec :as spec]
            [openmind.tags :as tags]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; routing table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn respond-with-fallback
  "Tries to respond directly to sender, if that fails, tries to respond to all
  connected devices logged into the account of the sender."
  [{:keys [send-fn ?reply-fn uid]} msg]
  (cond
    ;; REVIEW: If you're logged in on your phone and your laptop, and you
    ;; search on your laptop, should the search on your phone change
    ;; automatically? I don't think so...
    (fn? ?reply-fn) (?reply-fn msg)

    ;; But if it's the only way to return the result to you...
    (not= :taoensso.sente/nil-uid uid) (send-fn uid msg)

    :else (log/warn "No way to return response to sender." uid msg))  )

(defmulti dispatch (fn [{:keys [id] :as e}]
                     ;; Ignore all internal sente messages at present
                     (when-not (= "chsk" (namespace id))
                       id)))

(defmethod dispatch nil
  [e]
  (log/trace "sente message" e))

(defmethod dispatch :default
  [e]
  (log/warn "Unhandled client event:" e))

;;;;; Search

(defn search->elastic [{:keys [term filters sort-by type]}]
  (async/go
    {:sort  {:created-time {:order :desc}}
     :from  0
     :size  20
     :query {:bool (merge {:filter (async/<! (tags/tags-filter-query
                                              ;; FIXME: Hardcoded anaesthesia
                                              "anaesthesia" filters))}
                          {:must_not {:term {:deleted? true}}
                           :must (into []
                                       (remove nil?)
                                       [(when (seq term)
                                         {:match_phrase_prefix {:text term}})
                                        (when (and type (not= type :all))
                                          {:term {:extract-type type}})])})}}))
;; TODO: Better prefix search:
;; https://www.elastic.co/guide/en/elasticsearch/guide/master/_index_time_search_as_you_type.html
;; or
;; https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters-completion.html
;; or
;; https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-edgengram-tokenizer.html

(defn search-req [query]
  (async/go
    (es/search es/index (async/<! query))))

(defn parse-search-response [res]
  (mapv (fn [ex]
          (assoc (:_source ex) :id (:_id ex)))
        res))

(defmethod dispatch :openmind/search
  [{[_ query] :event :as req}]
  (async/go
    (let [res   (-> (search->elastic query)
                    search-req
                    async/<!
                    es/request<!
                    parse-search-response)
          event [:openmind/search-response
                 #:openmind.components.search
                 {:results res :nonce (:nonce query)}]]
      (respond-with-fallback req event))))

;;;;; Login

(defmethod dispatch :openmind/verify-login
  [{:keys [tokens] :as req}]
  (let [res [:openmind/identity (select-keys (:orcid tokens) [:orcid-id :name])]]
    (respond-with-fallback req res)))

;;;;; Create extract

(defn parse-dates [doc]
  (let [formatter (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX")]
    (walk/prewalk
     (fn [x] (if (inst? x) (.format formatter x) x))
     doc)))

(defn validate [author doc]
  (cond
    (not= author (:author doc))
    (log/error "Login mismatch, possible attack:" author doc)

    (not (s/valid? ::spec/extract doc))
    (log/warn "Invalid extract received from client:"
              author doc (s/explain-data ::spec/extract doc))

    :else doc))

(defn remove-empty [doc]
  (let [map-keys [:comments :contrast :confirmed :related]]
    (reduce (fn [doc k]
              (update doc k #(remove empty? %)))
            doc map-keys)))

(defn prepare [doc]
  (-> (if (empty? (:figure doc))
        (dissoc doc :figure)
        doc)
      remove-empty
      ;; Prefer server timestamp over what came from client
      (assoc :created-time (java.util.Date.))
      parse-dates))

;; FIXME: This is doing too many things at once. We need to separate this into
;; layers; data completion, validation, sending to elastic, and error handling.
(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn ?reply-fn uid tokens] [_ doc] :event :as req}]
  (when (not= uid :taoensso.sente/nil-uid)
    (async/go
      (let [doc (some->> doc
                         (validate
                          (select-keys (:orcid tokens) [:name :orcid-id]))
                         prepare)
            source-info (-> doc
                            :source
                            pubmed/article-info
                            async/<!)
            res (some->> (assoc doc :source-detail source-info)
                         (es/index-req es/index)
                         es/send-off!
                         async/<!)]
        (when-not (<= 200 (:status res) 299)
          (log/error "Failed to index new extact" res))
        (respond-with-fallback req [:openmind/index-result (:status res)])))))

(defmethod dispatch :openmind/update
  [{:keys [client-id send-fn ?reply-fn uid tokens] [_ doc] :event :as req}]
  (let [auth (select-keys (:orcid tokens) [:name :orcid-id])]
    (when (= uid (:orcid-id (:orcid tokens)))
      (async/go
        (let [res (->> doc
                       (validate auth)
                       remove-empty
                       parse-dates
                       (es/update-doc es/index (:id doc))
                       es/send-off!
                       async/<!)]
          (when-not (<= 200 (:status res) 299)
            (log/error "failed to update doc" (:id doc) res))
          (respond-with-fallback req [:openmind/update-response (:status res)]))))))

;;;;; Extract editing

(defn fetch-response [res]
  [:openmind/fetch-extract-response
   (-> res
       :_source
       (assoc :id (:_id res))
       (update :tags set))])

(defmethod dispatch :openmind/fetch-extract
  [{[_ id] :event :as req}]
  (async/go
    (->> (es/lookup es/index id)
         es/send-off!
         async/<!
         es/parse-response
         fetch-response
         (respond-with-fallback req))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Dev Kludges
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; REVIEW: We're done with this. Is there any valid reason to keep it around?
(defn expand-source-info
  "updates an extract with extra source info from pubmed."
  [id]
  (async/go
    (let [doc         (->> (es/lookup es/index id)
                           es/send-off!
                           async/<!
                           es/parse-response
                           fetch-response
                           second)
          source-info (->> doc
                           :source
                           pubmed/article-info
                           async/<!)]
      (->> (assoc doc :source-detail source-info)
           (es/update-doc es/index id)
           es/send-off!
           async/<!
           :status
           (println id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Tag Hierarchy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod dispatch :openmind/tag-tree
  [{[_ root] :event :as req}]
  (async/go
    (when-let [root-id (get (async/<! (tags/get-top-level-tags)) root)]
      (let [tree    (async/<! (tags/get-tag-tree root-id))
            event   [:openmind/tag-tree (tags/invert-tag-tree
                                         tree
                                         {:tag-name root :id root-id})]]
        (respond-with-fallback req event)))))
