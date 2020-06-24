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

(defn set-meta [index id metadata]
  (let [imm (util/immutable metadata)]
    (s3/intern imm)
    (notify/metadata-update id imm)
    (assoc index id (:hash imm))))

(defn alter-meta
  "Set the metadata of `id` to `(f (metadata id))`."
  [id f]
  (fn [index]
    (set-meta index id (f (metadata index id)))))

;;;;; Extract Creation

(defn new-extract [{:keys [hash]}]
  (alter-meta hash (fn [_] {:extract hash})))

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

(defn set-extract [comment-tree id]
  (walk/prewalk (fn [node]
                  (if (and (map? node) (contains? node :extract))
                    (assoc node :extract id)
                    node))
                comment-tree))

(defn forward-metadata [prev id author]
  (fn [index]
    (let [prev-meta (metadata index prev)
          relations (into #{} (map #(assoc % :entity id)) (:relations prev-meta))
          new-meta  (-> prev-meta
                        (assoc :extract id)
                        (assoc :relations relations)
                        (update :comments set-extract id)
                        (update :history #(or % []))
                        (update :history
                                conj
                                {:history/previous-version prev
                                 :time/created             (java.util.Date.)
                                 :author                   author}))]
      (set-meta index id new-meta))))

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

(defn add-comment-to-meta [{{:keys [extract]} :content :as comment}]
  (alter-meta extract (fn [m] (update m :comments insert-comment comment))))

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
  (alter-meta extract (fn [m] (update m :comments update-votes vote))))

;;;;; Relations

(defn add-1 [id rel]
  (alter-meta id (fn [m] (update m :relations
                                 #(if (seq %)
                                    (conj % rel)
                                    #{rel})))))

(defn add-relation [{:keys [hash content time/created] :as rel}]
  (let [{:keys [entity value]} content
        update-entity          (add-1 entity content)
        update-value           (add-1 value  content)]
    (comp update-entity update-value)))

(defn- retract-1 [id rel]
  (alter-meta id (fn [m] (update m :relations disj rel))))

(defn retract-relation [{:keys [entity value] :as rel}]
  (let [entity (retract-1 entity rel)
        value (retract-1 value rel)]
    (comp entity value)))

(defn update-relations [id rels]
  "Given an id and a set of rels, start a transaction in which you figure out
  which rels must be added and which removed from the existing metadata to bring
  it inline with the new set."
  (fn [index]
    (let [metadata (metadata index id)
          old-rels (:relations metadata)
          add      (map util/immutable (remove #(contains? old-rels %) rels))
          retract  (remove #(contains? rels %) old-rels)]
      (println "hash" id)
      (println "meta" metadata)
      (println "+" add)
      (println "-" retract)
      (as-> index %
        (reduce (fn [index rel] ((new-relation rel) index)) % add)
        (reduce (fn [index rel] ((retract-relation rel) index)) % retract)))))
