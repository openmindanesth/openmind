(ns openmind.datastore
  (:refer-clojure :exclude [intern])
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.datastore.impl :as impl]
            [openmind.datastore.indexing :as indexing]
            [openmind.datastore.routing :as routing]
            [openmind.spec :as spec]
            [openmind.hash :as h]
            [taoensso.timbre :as log]))

;; TODO: Use potemkin/import-vars

(def intern impl/intern)

(def lookup impl/lookup)

(def create-index indexing/create-index)

(def swap-index! indexing/swap-index!)

(def get-index indexing/get-index)

(def start-listener routing/start-listener)

(defn transact [{:keys [assertions context author time/created] :as tx}]
  (if (s/valid? ::spec/tx tx)
    (let [tx-items     (mapv (fn [[type hash]]
                               [type hash author created])
                             assertions)
          intern-items (map (fn [[h v]]
                              ;; Don't trust the hashes from the client. Though if
                              ;; they're wrong, the indicies will be corrupted, so
                              ;; we have to abort.
                              (when (= h (h/hash v))
                                {:hash         h
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
          {:status  :error
           :message "invalid data received from client"})
        (when (impl/append-tx-log! tx-items)
          (run! impl/intern intern-items)
          (routing/publish-transaction! (assoc tx :assertions tx-items))
          {:status  :success
           :message "transaction logged and awaiting processing."})))
    (do
      (log/error "invalid transaction received:\n" tx)
      {:status  :error
       :message "invalid transaction"})))
