(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [openmind.datastore :as ds]
            [openmind.elastic :as es]
            [openmind.env :as env]
            [openmind.datastore.indicies.metadata :as meta-index]
            [openmind.notification :as notify]
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
    (fn? ?reply-fn) (?reply-fn msg)

    (and send-fn (not= :taoensso.sente/nil-uid uid)) (send-fn uid msg)

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

(defn format-search-response [res]
  (mapv (fn [e]
          (-> e
              :_source
              (update :tags set)))
        res))

(defmethod dispatch :openmind/search
  [{[_ query] :event :as req}]
  (async/go
    (let [res   (-> (es/search-q query)
                    es/request<!
                    format-search-response)
          event [:openmind/search-response
                 #:openmind.components.search
                 {:results   res
                  :search-id (:search-id query)
                  :nonce     (:nonce query)
                  :meta-ids  (into {}
                                   (comp
                                    (map :hash)
                                    (map (fn [e]
                                           [e (meta-index/extract-meta-ref e)])))
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
  [tokens author]
  (let [tauth (select-keys (:orcid tokens) [:name :orcid-id])]
    (if (or env/dev-mode? (= tauth author))
      true
      (do
        (log/error "Login mismatch, possible attack:" tokens author)
        false))))

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

#_(defn write-extract!
  "Saves extract to S3 and indexes it in elastic."
  [extract extras uid]
  (async/go
    (when (valid? extract)
      (notify/notify-on-creation uid (:hash extract))
      (when (ds/intern extract)
        (let [res (async/<! (es/index-extract! extract))]
          (notify/extract-created (:hash extract))
          (if (and (:status res) (<= 200 (:status res) 201))
            (do
              (index/index extract)
              (run! intern-and-index extras))
            (log/error "Failed to index new extract:\n" extract
                       "\nresponse from elastic:\n" res)))))))


(defn valid-edit?
  "Checks that the edit is legitimate. Currently that only means that the author
  hasn't been changed."
  [prev-id new-extract]
  (or (empty? new-extract)
      (= (:author (:content new-extract))
         (-> prev-id ds/lookup :content :author))))

#_(defn update-extract!
  "Handles all of the updating logic after."
  [{{id :hash {author :author} :content :as imm} :new-extract
    :keys [editor figure relations previous-id]}]
  (async/go
    (when (valid? imm)
      (when-not (= id previous-id)
        (when (ds/intern imm)
          (index/forward-metadata previous-id id editor)
          (async/<! (es/retract-extract! previous-id))
          (async/<! (es/index-extract! imm))
          (notify/extract-edited previous-id id)
          (when figure
            (ds/intern figure)))))

    ;; FIXME: If relations is nil (not present), then do nothing, but if it's
    ;; empty, wipe all relations. I don't like this, it's too dangerous.
    (when relations
      (index/edit-relations previous-id (:hash imm) relations))))

(defmethod dispatch :openmind/extract-metadata
  [{[ev hash] :event :as req}]
  (respond-with-fallback req [ev hash (meta-index/extract-meta-ref hash)]))

(defmethod dispatch :openmind/tx
  ;; gather assertions and retractions into sets which are processed all
  ;; together, or not at all.
  ;;
  ;; Note that this does not mean that they are committed transactionally.
  ;;
  ;; So what good is this? That's a good question...
  [{[ev tx] :event :keys [uid tokens] :as req}]
  (when (s/valid? :openmind.spec.indexical/tx tx)
    (let [{:keys [author context assertions]} tx]
      (when (check-author tokens author)
        (run!
         (fn [t id]
           (case t
             :assert  (notify/notify-on-assertion id)
             :retract (notify/notify-on-retraction id)))
         (:assertions tx))
        (ds/transact (assoc tx :created (java.util.Date.)))))))

#_(defn- delete-extract!
  "Soft delete of extracts. Removes them from the search index and removes
  relations to them from other extracts, but the extract is never removed from
  the store, nor is its metadata, so we can restore it at any time if need be."
  [id]
  (es/retract-extract! id)
  (index/retract-extract! id))
