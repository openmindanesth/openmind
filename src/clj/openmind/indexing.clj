(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

(def extract-metadata-uri
  "openmind.indexing/extract-metadata")

(defn extract-meta-ref
  "Returns the hash of the metadoc for doc referrenced by `hash`."
  [hash]
  (-> extract-metadata-uri
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

(defn intern-and-swap!
  "Interns immutable document `imm` in the datastore and then attempts to update
  the global index to point to this new value."
  [k imm]
  (when (s3/intern imm)
    (s3/assoc-index extract-metadata-uri k (:hash imm))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Comments
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn insert-comment
  [comment-tree {{:keys [reply-to]} :content :as comment}]
  (let [c* (-> comment
               :content
               (dissoc :reply-to)
               (assoc :hash (:hash comment)
                      :time/created (:time/created comment)))]
    (if reply-to
      (walk/postwalk
       (fn [node]
         (if (and (map? node) (= (:hash node) reply-to))
           (update node :replies conj c*)
           node))
       comment-tree)
      (if comment-tree
        (conj comment-tree c*)
        [c*]))))

(defmethod update-indicies :comment
  [_ {:keys [hash] {:keys [extract history/previous-version]} :content
      :as   comment}]
  (let [old-meta (extract-metadata extract)
        new-meta (update old-meta
                         :comments insert-comment comment)
        res      (intern-and-swap! extract (util/immutable new-meta))]
    [extract (-> res :content (get extract))]))

;;;; Comment ranking

(defn update-votes [comments {h :hash {:keys [vote author comment]} :content}]
  (walk/postwalk
   (fn [node]
     (if (= (:hash node) comment)
       (-> node
           (update :rank (fnil + 0) vote)
           (update :votes assoc author {:vote vote :hash h}))
       node))
   comments))

(defmethod update-indicies :comment-vote
  [_ {{:keys [extract]} :content :as vote}]
  (let [new-meta (-> extract
                     extract-metadata
                     (update :comments update-votes vote)
                     util/immutable)
        res (intern-and-swap! extract new-meta)]
    ;; TODO: Test for successful write
    (when res
      [extract (:hash new-meta)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extracts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod update-indicies :extract
  [_ {:keys [hash] {:keys [history/previous-version]} :content}]
  ;; TODO: updates
  (let [blank-meta (util/immutable {:extract hash})]
    (s3/intern blank-meta)
    (s3/assoc-index extract-metadata-uri hash (:hash blank-meta))))

;;;;; Figures

(defmethod update-indicies :figure
  [_ _]
  ;; No op. We don't index figures, just store them.
  true)

;;;;; Relations

;; FIXME: I'm still glossing over the difference between names and values.

(defn update-entity-attr-list [id key val]
  (let [new (-> id
                extract-metadata
                (update key conj val)
                util/immutable)]
    (when (s3/intern new)
      new)))

(defmethod update-indicies :relation
  [_ {:keys [hash content time/created] :as rel}]
  (let [{:keys [entity value]} content

        meta-rel    (assoc rel
                           :time/created created
                           :hash hash)
        entity-meta (update-entity-attr-list entity :relations rel)
        value-meta  ((update-entity-attr-list value :relations rel))]
    (s3/assoc-index extract-metadata-uri
                    entity (:hash entity-meta)
                    value (:hash value-meta))))
