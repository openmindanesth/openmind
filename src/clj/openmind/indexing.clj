(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

(def active-extracts-stub
  [#openmind.hash/ref "723701947793c75d6816580e5b6aa131"
   #openmind.hash/ref "552fa91af86fc432a292091c0b1331ab"

   #openmind.hash/ref "d594f8e73658cc09a9ab473d08de5095"
   #openmind.hash/ref "b88867f28b17626b736c19e4e2454ddb"
   #openmind.hash/ref "96ea2c806cd229176c43e40d001b16ea"])

(def em-stub
  (mapv (fn [h] (util/immutable {:extract h})) active-extracts-stub))

(def extract-metadata-stub
  (util/immutable
   (zipmap active-extracts-stub
           (map :hash em-stub))))

(def ^:private extract-metadata-uri
  "openmind.indexing/extract-metadata")

(defn wipe-metadata!
  "Creates a new index with a hard-coded set of things and resets the head
  pointer. Make sure you write down the old one in case you want to go back."
  []
  ;; REVIEW: Do we gain anything from storing the master indicies as chunks in
  ;; the datastore?
  (when-let [current (s3/lookup extract-metadata-uri)]
    (println "Replacing index head: " (:hash current)
             "with: " (:hash extract-metadata-stub)))
  ;; TODO: git reset analog
  (run! s3/intern em-stub)
  (s3/intern extract-metadata-stub)
  (@#'s3/index-compare-and-set! extract-metadata-uri
   (s3/lookup extract-metadata-uri)
   extract-metadata-stub))

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
