(ns openmind.datastore.indicies.metadata
  (:require [clojure.spec.alpha :as s]
            [openmind.datastore :as ds]
            [openmind.spec :as spec]
            [openmind.transaction-fns :as txfns]
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
  {:comment      txfns/add-comment-to-meta
   :comment-vote txfns/comment-vote
   :extract      txfns/new-extract
   :relation     txfns/add-relation
   :figure       nil})

(defn index [imm]
  (let [t (first (:content (s/conform ::spec/immutable imm)))]
    (if-let [f (get index-methods t)]
     (ds/swap-index! extract-metadata-index (f imm))
     ;; allow no-ops without triggering warnings.
     (when-not (contains? index-methods t)
       (log/warn "Attempt to index unconformable data:\n" imm)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Miscelanea
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-relations [id rels]
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
