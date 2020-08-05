(ns openmind.datastore
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.async :as async]
            [openmind.datastore.impl :as impl]
            [openmind.datastore.indexing :as indexing]
            [openmind.datastore.routing :as routing]
            [openmind.hash :as h]
            [taoensso.timbre :as log]))

;; TODO: Use potemkin/import-vars

(def intern impl/intern)

(def lookup impl/lookup)

(def tx-log routing/tx-log)

(def create-index indexing/create-index)

(def swap-index! indexing/swap-index!)

(def get-index indexing/get-index)

(defn transact [{:keys [assertions context author created] :as tx}]
  ;; 1) Intern context items in IHS

  ;; 2) Commit the transaction to the tx-log

  ;; 3) Return a channel that tells the caller the transaction has been
  ;; logged. That's the best we can do at present since the centre doesn't
  ;; know who is consuming transactions, let alone when they're done

  ;; 4) Apply tx-rows to appropriate indicies
  ;; I think the best way to accomplish that last step is simply to publish
  ;; them to a pub-sub system.

  (async/go
    (let [tx-items     (mapv (fn [[type hash]]
                               [type hash author created])
                             assertions)
          intern-items (map (fn [[h v]]
                              ;; Don't trust the hashes from the client. Though if
                              ;; they're wrong, the indicies will be corrupted, so
                              ;; we have to abort.
                              (when (= h (h/hash v))
                                {:hash         (h/hash h)
                                 :time/created created
                                 :author       author
                                 :content      v}))
                            context)]
      (if (some nil? intern-items)
        (do
          (log/error "client data has mangled hashes in context:\n"
                     "client: " author "\n"
                     context "\n"
                     assertions)
          {:status :failure
           :message "invalid data received from client"})
        (when (impl/append-tx-log! tx-items)
          (run! impl/intern intern-items)
          (routing/publish-transaction! (assoc tx :assertions tx-items))
          {:status :success
           :message "transaction logged and awaiting processing."})))))
