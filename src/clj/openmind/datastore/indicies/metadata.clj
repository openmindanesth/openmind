(ns openmind.datastore.indicies.metadata
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.datastore :as ds]
            [openmind.datastore.indicies.metadata.transaction-fns :as txfns]
            [taoensso.timbre :as log]))

(def extract-metadata-index
  (ds/create-index
   "openmind.indexing/extract-metadata"))

(defn extract-meta-ref
  "Returns the hash of the metadoc for doc referrenced by `hash`."
  [hash]
  (-> extract-metadata-index
      ds/get-index
      :content
      (get hash)))

(defn extract-metadata
  "Returns metadata of doc with hash `hash`."
  [hash]
  (-> hash
      extract-meta-ref
      ds/lookup
      :content))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Indexing of internable objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def index-methods
  "Indexing dispatch table. I know this should be a multi method, but openness
  isn't really an issue here and the rest is all just boilerplate."
  {[:assert :comment]       txfns/add-comment-to-meta
   [:retract :comment]      #(throw (Exception. "Not implemented"))
   [:assert :comment-vote]  txfns/comment-vote
   [:retract :comment-vote] #(throw (Exception. "Not implemented"))
   [:assert :extract]       txfns/create-extract
   [:assert :relation]      txfns/add-relation
   [:retract :relation]     txfns/retract-relation})

(defn handler [[assertion hash _ _ obj :as tx]]
  (let [content (or obj (:content (ds/lookup hash)))]
    (when-let [t (first (s/conform :openmind.spec/content content))]
      (when-let [f (get index-methods [assertion t])]
        (ds/swap-index! extract-metadata-index (f tx content))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Miscelanea
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(defn update-relations [id rels]
  (ds/swap-index! extract-metadata-index
                  (txfns/update-relations id rels)))

(defn edit-relations [prev-id new-id rels]
  (update-relations (or new-id prev-id) rels))

(defn forward-metadata [prev id editor]
  (ds/swap-index! extract-metadata-index
                  (txfns/forward-metadata prev id editor)))

(defn retract-extract! [id]
  (log/warn "Retracting:" id)
  (ds/swap-index! extract-metadata-index
                  (txfns/remove-other-relations id)))
