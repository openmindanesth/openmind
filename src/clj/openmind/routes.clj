(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.elastic :as es]
            [openmind.indexing :as index]
            [openmind.pubmed :as pubmed]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.tags :as tags]
            [openmind.util :as util]
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

;;FIXME: This is a rather crummy search. We want to at least split on tokens in
;;the query and match all of them...
(defn search->elastic [{:keys [term filters sort-by type]}]
  {:sort  {:created-time {:order :desc}}
   :from  0
   :size  20
   :query {:bool (merge {:filter (tags/tags-filter-query
                                  ;; FIXME: Hardcoded anaesthesia
                                  "anaesthesia" filters)}
                        {:must_not {:term {:deleted? true}}
                         :must (into []
                                     (remove nil?)
                                     [(when (seq term)
                                        {:match_phrase_prefix {:text term}})
                                      (when (and type (not= type :all))
                                        {:term {:extract/type type}})])})}})
;; TODO: Better prefix search:
;; https://www.elastic.co/guide/en/elasticsearch/guide/master/_index_time_search_as_you_type.html
;; or
;; https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters-completion.html
;; or
;; https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-edgengram-tokenizer.html

(defn search-req [query]
  (es/search es/index query))

(defn parse-search-response [res]
  (mapv :_source res))

(defmethod dispatch :openmind/search
  [{[_ query] :event :as req}]
  (async/go
    (let [res   (-> (search->elastic query)
                    search-req
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

(defn valid? [author doc]
  (cond
    (not= author (:author doc))
    (log/error "Login mismatch, possible attack:" author doc)

    (not (s/valid? :openmind.spec.extract/extract doc))
    (log/warn "Invalid extract received from client:"
              author doc (s/explain-data :openmind.spec.extract/extract doc))

    :else doc))

(defn write-extract!
  "Saves extract to S3 and indexes it in elastic."
  [extract]
  (s3/intern extract)
  (es/index-extract! extract))

;; FIXME: This is doing too many things at once. We need to separate this into
;; layers; data completion, validation, sending to elastic, and error handling.
(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn ?reply-fn uid tokens] [_ doc] :event :as req}]
  (when (not= uid :taoensso.sente/nil-uid)
    (async/go
      (when (valid? (select-keys (:orcid tokens) [:name :orcid-id]) doc)
        (let [source-info (-> doc
                              :source
                              pubmed/article-info
                              async/<!)
              extract     (util/immutable
                           (assoc doc :source source-info))
              res         (async/<! (write-extract! extract))]
          (when-not (<= 200 (:status res) 299)
            (log/error "Failed to index new extact" res))
          (respond-with-fallback req [:openmind/index-result (:status res)]))))))

;; TODO: We shouldn't allow updating extracts until we get this sorted.
#_(defmethod dispatch :openmind/update
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

(defmethod dispatch :openmind/intern
  [{[_ imm] :event :as req}]
  (s3/intern imm)
  (index/index imm))
