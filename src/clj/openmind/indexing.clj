(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

(def extract-metadata-uri
  "openmind.indexing/extract-metadata")

(defn extract-meta-ref [hash]
  (-> extract-metadata-uri
      s3/get-index
      :content
      (get hash)))

(defn extract-metadata [h]
  (-> h
      extract-meta-ref
      s3/lookup
      :content))

(defmulti update-indicies (fn [t d] t))

(defn index [imm]
  (let [t (first (:content (s/conform ::spec/immutable imm)))]
    (update-indicies t imm)))

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

(defn intern-and-swap! [inserted k imm]
  (when (s3/intern imm)
    (let [{:keys [success? value]}
          (s3/assoc-index extract-metadata-uri k (:hash imm))]
      (if success?
        value

        ;; Retry on failure
        ;; FIXME: clear risk of infinite regress
        (do (log/info "Retrying transaction" inserted)
            (index inserted))))))

(defmethod update-indicies :comment
  [_ {:keys [hash content] :as comment}]
  (let [{:keys [extract]} content
        old-meta          (extract-metadata extract)
        new-meta          (update old-meta
                                  :comments insert-comment comment)
        res (intern-and-swap! comment extract (util/immutable new-meta))]
    [extract (-> res :content (get extract))]))

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
        res (intern-and-swap! vote extract new-meta)]
    [extract (-> res :content (get extract))]))
