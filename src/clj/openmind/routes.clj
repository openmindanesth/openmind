(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [openmind.elastic :as es]
            [openmind.env :as env]
            [openmind.indexing :as index]
            [openmind.notification :as notify]
            [openmind.s3 :as s3]
            [openmind.sources :as sources]
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

    :else (log/warn "No way to return response to sender." uid msg))
  true)

(defn intern-and-index [imm]
 (when (s3/intern imm)
   (index/index imm)))

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

(defmethod dispatch :openmind/article-lookup
  [{[_ {:keys [term res-id] :as ev}] :event :as req}]
  (async/go
    (respond-with-fallback
     req
     ;; TODO: this should be cached. That data is already in our store, we just
     ;; need an index.
     [:openmind/article-details
      (assoc ev :source (async/<! (sources/lookup (string/trim term))))])))

(defn write-extract!
  "Saves extract to S3 and indexes it in elastic."
  [extract]
  (async/go
    (when (s3/intern extract)
      (let [res (async/<! (es/index-extract! extract))]
        (es/add-to-index (:hash extract))
        (notify/extract-created (:hash extract))
        res))))

(defmethod dispatch :openmind/index
  [{:keys [uid tokens] [_ {:keys [extract extras]}] :event :as req}]
  (when (or (not= uid :taoensso.sente/nil-uid) env/dev-mode?)
    (async/go
      (when (check-author (select-keys (:orcid tokens) [:name :orcid-id])
                          (:author (:content extract)))
        (when (valid? extract)
          (notify/notify-on-creation uid (:hash extract))
          (when-let [res (write-extract! extract)]
            (let [res (async/<! res)]
              (if (and (:status res) (<= 200 (:status res) 299))
                (do
                  (index/index extract)
                  (run! intern-and-index extras))
                (log/error "Failed to index new extract:\n" extract
                           "\nresponse from elastic:\n" res)))))))
    (respond-with-fallback
     req [:openmind/index-result {:status :success}])))

(defn update-extract!
  [{id :hash {prev :history/previous-version author :author} :content :as imm}
   editor]
  (async/go
    (when-not (= id prev)
      (when (s3/intern imm)
        (index/forward-metadata prev id editor)
        (async/<! (es/retract-extract! prev))
        (async/<! (es/index-extract! imm))
        (es/replace-in-index prev (:hash imm))
        (notify/extract-edited prev id)))))

(defn valid-edit?
  "Checks that the edit is legitimate. Currently that only means that the author
  hasn't been changed."
  [prev-id new-extract]
  (or (empty new-extract)
      (= (:author new-extract) (-> prev-id s3/lookup :content :author))))

(defmethod dispatch :openmind/update
  [{:keys [client-id send-fn ?reply-fn uid tokens]
    [_ {:keys [previous-id new-extract figure relations editor]}] :event :as req}]
  (when (or (not= uid :taoensso.sente/nil-uid) env/dev-mode?)
    (async/go
      (when (check-author (select-keys (:orcid tokens) [:name :orcid-id]) editor)
        (when (valid-edit? previous-id new-extract)
          (notify/notify-on-creation uid (:hash new-extract))
          (when new-extract
            (when (valid? new-extract)
              (async/<! (update-extract! new-extract editor))
              (when figure
                (s3/intern figure))))

          (index/edit-relations previous-id (:hash new-extract) relations))))
    ;; FIXME: No feedback if something goes wrong.
    (respond-with-fallback
     req [:openmind/update-response {:status :success
                                     :id     previous-id}])))

(defmethod dispatch :openmind/intern
  [{[_ imm] :event :as req}]
  ;; TODO: Author check. Validation is already done in several places.
  (when-let [res (intern-and-index imm)]
    (respond-with-fallback req [:openmind/extract-metadata res])))

(defmethod dispatch :openmind/extract-metadata
  [{[ev hash] :event :as req}]
  (respond-with-fallback req [ev hash (index/extract-meta-ref hash)]))

(defn- delete-extract!
  "Soft delete of extracts. Removes them from the search index and removes
  relations to them from other extracts, but the extract is never removed from
  the store, nor is its metadata, so we can restore it at any time if need be."
  [id]
  (es/remove-from-index id)
  (index/retract-extract! id))
