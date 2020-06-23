(ns openmind.transaction-fns
  (:require [clojure.walk :as walk]
            [openmind.notification :as notify]
            [openmind.s3 :as s3]
            [openmind.util :as util]))

(defn metadata [index hash]
  (-> index
      (get hash)
      s3/lookup
      :content))

;; TODO: All of these could be massively simplified by creating a macro that
;; takes a function from the old metadata to the new metadata. Almost none of
;; these need anything more complicated and ~70% of the code below is
;; boilerplate. Boilerplate which if incorrect or missing will break everything.

;;;;; Extract Creation

(defn new-extract [{:keys [hash] {:keys [history/previous-version]} :content}]
  (fn [index]
    (when-not (contains? index hash)
    (let [blank-meta (util/immutable {:extract hash})]
      (s3/intern blank-meta)
      (notify/metadata-update hash blank-meta)
      (assoc index hash (:hash blank-meta))))))


;;;;; Update Extract
;;
;; An important part of updating an extract is carrying the metadata from the
;; old version forward to the new one. There are still some open questions about
;; this like: do we create *new* comments and relations which target the new
;; version, or do we just leave the originals in the datastore and munge the
;; metadata?
;;
;; So far we're going with the latter. This way we keep provenance, and all we
;; really want for the UI is the metadata, so it seems to work out.

(defn forward-metadata [prev id author]
  (fn [index]
    (let [prev-meta (metadata index prev)
          relations (into #{} (map #(assoc % :entity id)) (:relations prev-meta))
          new-meta  (-> prev-meta
                        (assoc :extract id)
                        (assoc :relations relations)
                        (update :history #(or % []))
                        (update :history
                                conj
                                {:history/previous-version prev
                                 :time/created             (java.util.Date.)
                                 :author                   author})
                        util/immutable)]
      (s3/intern new-meta)
      (run! #(s3/intern (util/immutable %)) relations)
      (notify/metadata-update id new-meta)
      (assoc index id (:hash new-meta)))))

;;;;; Comments

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

(defn add-comment-to-meta [{:keys [hash] {:keys [extract]} :content :as comment}]
  (fn [index]
    (let [old-meta (metadata index extract)
          new-meta (if (nil? old-meta)
                     {:extract  extract
                      :comments (insert-comment [] comment)}
                     (update old-meta
                             :comments insert-comment comment))
          imm      (util/immutable new-meta)]
      (s3/intern imm)
      (notify/metadata-update extract imm)
      (assoc index extract (:hash imm)))))

(defn update-votes [comments {h :hash {:keys [vote author comment]} :content}]
  (walk/postwalk
   (fn [node]
     (if (= (:hash node) comment)
       (-> node
           (update :rank (fnil + 0) vote)
           (update :votes assoc author {:vote vote :hash h}))
       node))
   comments))

(defn comment-vote [{{:keys [extract]} :content :as vote}]
  (fn [index]
    (let [new-meta (-> (metadata index extract)
                     (update :comments update-votes vote)
                     util/immutable)]
      (s3/intern new-meta)
      (notify/metadata-update extract new-meta)
      (assoc index extract (:hash new-meta)))))

;;;;; Relations

(defn metarel [{:keys [hash content time/created] :as rel}]
  (assoc content
         :time/created created
         :hash hash))

(defn add-relation [index id rel]
  (let [new (-> (metadata index id)
                (assoc :extract id)
                (update :relations
                        #(if (seq %)
                           (conj % rel)
                           #{rel}))
                util/immutable)]
    (s3/intern new)
    (notify/metadata-update id new)
    new))

(defn new-relation [{:keys [hash content time/created] :as rel}]
  (fn [index]
    (let [{:keys [entity value]} content
          meta-rel               (metarel rel)
          entity-meta            (add-relation index entity meta-rel)
          value-meta             (add-relation index value  meta-rel)]
      (assoc index
             entity (:hash entity-meta)
             value (:hash value-meta)))))

(defn- retract-1 [index id rel]
  (let [m' (-> (metadata index id)
               (update :relations disj rel)
               util/immutable)]
    (s3/intern m')
    (notify/metadata-update id m')
    m'))

(defn retract-relation [{:keys [entity value] :as rel}]
  (fn [index]
    (let [emeta (retract-1 index entity rel)
          vmeta (retract-1 index value rel)]
      (assoc index
             entity (:hash emeta)
             value (:hash vmeta)))))

(defn update-relations [id rels]
  "Given an id and a set of rels, start a transaction in which you figure out
  which rels must be added and which removed from the existing metadata to bring
  it inline with the new set."
  (fn [index]
    (let [metadata (metadata index id)
          old-rels (:relations metadata)
          add      (map util/immutable (remove #(contains? old-rels %) rels))
          retract  (remove #(contains? rels %) old-rels)]
      (as-> index index
        (reduce (fn [index rel] ((new-relation rel) index)) index add)
        (reduce (fn [index rel] ((retract-relation rel) index)) index retract)))))
