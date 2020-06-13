(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.elastic :as es]
            [openmind.env :as env]
            [openmind.indexing :as index]
            [openmind.pubmed :as pubmed]
            [openmind.s3 :as s3]
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

    :else (log/warn "No way to return response to sender." uid msg))
  true)

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
  {:sort  {:time/created {:order :desc}}
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
  (mapv (fn [e]
          (-> e
              :_source
              (update :tags set)))
        res))

(defmethod dispatch :openmind/search
  [{[_ query] :event :as req}]
  (async/go
    (let [res   (-> (search->elastic query)
                    search-req
                    es/request<!
                    parse-search-response)
          event [:openmind/search-response
                 #:openmind.components.search
                 {:results   res
                  :search-id (:search-id query)
                  :nonce     (:nonce query)
                  :meta-ids  (into {}
                                   (comp
                                    (map :hash)
                                    (map (fn [e]
                                           [e (index/extract-meta-ref e)])))
                                   res)}]]
      (respond-with-fallback req event))))

;;;;; Login

(defmethod dispatch :openmind/verify-login
  [{:keys [tokens] :as req}]
  (let [res [:openmind/identity (select-keys (:orcid tokens) [:orcid-id :name])]]
    (respond-with-fallback req res)))

;;;;; Create extract

(defn check-author
  "Validates that the author in the client is in fact the author logged in from
  the server's point of view.

  Note: in dev mode the check always returns true."
  [token author]
  (if (or env/dev-mode? (= token author))
    true
    (do
      (log/error "Login mismatch, possible attack:" token author)
      false)))

(defn valid?
  "Checks that `doc` is a valid extract based on its spec."
  [doc]
  (when doc
    (if (s/valid? :openmind.spec.extract/extract (:content doc))
      true
      (log/warn "Invalid extract received from client:" doc))))

(defmethod dispatch :openmind/pubmed-lookup
  [{[_ {:keys [url res-id] :as ev}] :event :as req}]
  (async/go
    (respond-with-fallback
     req
     ;; TODO: this should be cached. That data is already in our store, we just
     ;; need an index.
     [:openmind/pubmed-article
      (assoc ev :source (async/<! (pubmed/article-info url)))])))

(defn write-extract!
  "Saves extract to S3 and indexes it in elastic."
  [extract]
  (async/go
    (when (s3/intern extract)
      (let [res (async/<! (es/index-extract! extract))]
        (es/add-to-index (:hash extract))
        res))))

(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn ?reply-fn uid tokens] [_ imm] :event :as req}]
  ;; TODO: This should just be another subbranch on :openmind/intern
  (when (or (not= uid :taoensso.sente/nil-uid) env/dev-mode?)
    (async/go
      (when (check-author (select-keys (:orcid tokens) [:name :orcid-id])
                          (:author (:content imm)))
        (when (valid? imm)
          (when-let [res (write-extract! imm)]
            (let [res (async/<! res)]
              (if (and (:status res) (<= 200 (:status res) 299))
                (index/index imm)
                (log/error "Failed to index new extact\n" imm
                           "\nresponse from elastic\n" res))
              (respond-with-fallback
               req [:openmind/index-result (:status res)]))))))))

(defn update-extract!
  [{id :hash {prev :history/previous-version author :author} :content :as imm}]
  (async/go
    (when-not (= id prev)
      (when (s3/intern imm)
        (index/forward-metadata prev id author)
        (async/<! (es/retract-extract! prev))
        (async/<! (es/index-extract! imm))
        (es/replace-in-index (:hash prev) (:hash imm))))))

(defmethod dispatch :openmind/update
  [{:keys [client-id send-fn ?reply-fn uid tokens]
    [_ {:keys [extract figure relations]}] :event :as req}]
  #_(when (or (not= uid :taoensso.sente/nil-uid) env/dev-mode?)
    (async/go
      (when (check-author (select-keys (:orcid tokens) [:name :orcid-id])
                          (:author (:content extract)))
        (when (valid? extract)
          (when extract
            (async/<! (update-extract! extract)))
          (when figure
            (s3/intern figure))
          (index/edit-relations extract relations)

          ;; TODO: how to detect errors?
          (respond-with-fallback
           req [:openmind/update-response 200]))))))

(defmethod dispatch :openmind/intern
  [{[_ imm] :event :as req}]
  ;; TODO: Author check. Validation is already done in several places.
  (when (s3/intern imm)
    (when-let [res (index/index imm)]
      ;; REVIEW: This could be broadcast to all connected clients.
      ;;
      ;; That would be technically correct and make the interface more "live",
      ;; but my fear is that it will break the reproducibility I'm trying to
      ;; build in if comments and votes and relations just pop without you
      ;; taking any action.
      ;;
      ;; I think we need to distinguish between "fixed" mode which operates on a
      ;; snapshot in time with perfect reproducability and "live" mode which
      ;; queries the latest data.
      (respond-with-fallback req (into [:openmind/extract-metadata] res)))))

(defmethod dispatch :openmind/extract-metadata
  [{[ev hash] :event :as req}]
  (respond-with-fallback req [ev hash (index/extract-meta-ref hash)]))
