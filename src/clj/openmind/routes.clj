(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [openmind.datastore :as ds]
            [openmind.datastore.indicies.metadata :as meta-index]
            [openmind.elastic :as es]
            [openmind.env :as env]
            [openmind.notification :as notify]
            [openmind.sources :as sources]
            [openmind.spec :as spec]
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

;;;;; Pubmed / Biorxiv searching

(defmethod dispatch :openmind/article-lookup
  [{[_ {:keys [term res-id] :as ev}] :event :as req}]
  (async/go
    (respond-with-fallback
     req
     ;; TODO: this should be cached. That data is already in our store, we just
     ;; need an index.
     [:openmind/article-details
      (assoc ev :source (async/<! (sources/lookup (string/trim term))))])))

;;;;; Get metadata (data can't contain its own metadata).

(defmethod dispatch :openmind/extract-metadata
  [{[ev hash] :event :as req}]
  (respond-with-fallback req [ev hash (meta-index/extract-meta-ref hash)]))

;;;;; Assertions about the universe

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

(defmethod dispatch :openmind/tx
  [{[ev tx] :event :keys [uid tokens] :as req}]
  (when (s/valid? ::spec/tx tx)
    (let [{:keys [author context assertions]} tx]
      (when (check-author tokens author)
        (run!
         (fn [[t id]]
           (case t
             :assert  (notify/notify-on-assertion uid id)
             :retract (notify/notify-on-retraction uid id)))
         (:assertions tx))
        (ds/transact (assoc tx :time/created (java.util.Date.)))
        #_(respond-with-fallback
         req (ds/transact (assoc tx :created (java.util.Date.))))))))
