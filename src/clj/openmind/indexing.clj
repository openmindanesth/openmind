(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [openmind.datastore :as s3]
            [openmind.spec :as spec]
            [openmind.transaction-fns :as txfns]
            [taoensso.timbre :as log]))

(def extract-metadata-index
  (s3/create-index
   "openmind.indexing/extract-metadata"))

(defn extract-meta-ref
  "Returns the hash of the metadoc for doc referrenced by `hash`."
  [hash]
  (-> extract-metadata-index
      s3/get-index
      :content
      (get hash)))

(defn extract-metadata
  "Returns metadata of doc with hash `hash`."
  [hash]
  (-> hash
      extract-meta-ref
      s3/lookup
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
     (s3/swap-index! extract-metadata-index (f imm))
     ;; allow no-ops without triggering warnings.
     (when-not (contains? index-methods t)
       (log/warn "Attempt to index unconformable data:\n" imm)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Miscelanea
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-relations [id rels]
  (s3/swap-index! extract-metadata-index
                  (txfns/update-relations id rels)))

(defn edit-relations [prev-id new-id rels]
  (update-relations (or new-id prev-id) rels))

(defn forward-metadata [prev id editor]
  (s3/swap-index! extract-metadata-index
                  (txfns/forward-metadata prev id editor)))


(defn retract-extract! [id]
  (log/warn "Retracting:" id)
  (s3/swap-index! extract-metadata-index
                  (txfns/remove-other-relations id)))

(comment "IDs being messed with"
         { #openmind.hash/ref "eaa5d665f6526e48908bdedb814b7d94"
          #openmind.hash/ref "0a4b81f16835574a1b5211f64502b9c7"

          #openmind.hash/ref "eff3bdcea1b1bea37803df2b05055852"
          #openmind.hash/ref "5067d42ef40a34d380f09b232c14c751"})
