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
