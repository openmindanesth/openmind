(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.transaction-fns :as txfns]
            [openmind.util :as util]
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
  "Returns metadata of doc with hash `h`."
  [h]
  (-> h
      extract-meta-ref
      s3/lookup
      :content))

(defmulti update-indicies (fn [t d] t))

(defn index [imm]
  (let [t (first (:content (s/conform ::spec/immutable imm)))]
    (update-indicies t imm)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Comments
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod update-indicies :comment
  [_ comment]
  (s3/swap-index! extract-metadata-index
                  (txfns/add-comment-to-meta comment)))

(defmethod update-indicies :comment-vote
  [_ vote]
  (s3/swap-index! extract-metadata-index (txfns/comment-vote vote)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extracts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod update-indicies :extract
  [_ extract]
  (s3/swap-index! extract-metadata-index (txfns/new-extract extract)))

;;;;; Figures

(defmethod update-indicies :figure
  [_ _]
  ;; No op. We don't index figures, just store them.
  true)

;;;;; Relations

(defmethod update-indicies :relation
  [_ rel]
  (s3/intern rel)
  (s3/swap-index! extract-metadata-index (txfns/new-relation rel)))

(defn update-relations [id rels]
  (s3/swap-index! extract-metadata-index
                  (txfns/update-relations id rels)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Updating extracts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn edit-relations [prev-id new-id rels]
  (update-relations (or new-id prev-id) rels))

(defn forward-metadata [prev id author]
  (s3/swap-index! extract-metadata-index
                  (txfns/forward-metadata prev id author)))
