(ns openmind.transaction-fns
  "Transaction functions operating on the metadata index. The reason these are
  in a separate namespace is to make sure that I never refer to the index
  itself, thus accidentally reaching out of the transaction. That was getting to
  be a problem when everything was in the same ns."
  (:require [clojure.walk :as walk]
            [openmind.datastore :as s3]
            [openmind.notification :as notify]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

(defn metadata [index hash]
  (-> index
      (get hash)
      s3/lookup
      :content))

(defn set-meta [index id metadata]
  (if-let [imm (util/immutable metadata)]
    (do
      (s3/intern imm)
      (notify/metadata-update id imm)
      (assoc index id (:hash imm)))
    (do
      (log/warn "invalid metadata:\n" metadata)
      index)))

(defn alter-meta
  "Set the metadata of `id` to `(f (metadata id))`."
  [id f]
  (fn [index]
    (if-let [m (metadata index id)]
      (set-meta index id (f m))
      (do
        (log/error "extract" id "has no metadata!")
        index))))

;;;;; Extract Creation

(defn new-extract [{:keys [hash]}]
  (fn [index]
    (set-meta index hash {:extract hash})))

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
  (if comment-tree
    (walk/prewalk (fn [node]
                    (if (and (map? node) (contains? node :extract))
                      (assoc node :extract id)
                      node))
                  comment-tree)
    []))

(defn forward-metadata [prev id editor]
  (fn [index]
    (let [prev-meta (metadata index prev)
          relations (into #{}
                          (map (fn [{:keys [entity value] :as rel}]
                                 (if (= entity prev)
                                   (assoc rel :entity id)
                                   (assoc rel :value id))))
                          (:relations prev-meta))
          new-meta  (-> prev-meta
                        (assoc :extract id)
                        (assoc :relations relations)
                        (update :comments set-extract id)
                        (update :history #(or % []))
                        (update :history
                                conj
                                {:history/previous-version prev
                                 :time/created             (java.util.Date.)
                                 :author                   editor}))]
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

(defn add-relation [{{:keys [entity value] :as content} :content}]
  (let [update-entity (add-1 entity content)
        update-value  (add-1 value  content)]
    (comp update-entity update-value)))

(defn- retract-1 [id rel]
  (alter-meta id (fn [m] (update m :relations disj rel))))

(defn retract-relation [{:keys [entity value] :as rel}]
  (let [entity (retract-1 entity rel)
        value (retract-1 value rel)]
    (comp entity value)))

(defn update-relations-meta [rels m]
  (let [old-rels (:relations m)
        add      (map util/immutable (remove #(contains? old-rels %) rels))
        retract  (remove #(contains? rels %) old-rels)]
    (apply comp (concat (map add-relation add)
                        (map retract-relation retract)))))

(defn update-relations [id rels]
  "Given an id and a set of rels, start a transaction in which you figure out
  which rels must be added and which removed from the existing metadata to bring
  it inline with the new set."
  (fn [index]
    (let [m (metadata index id)]
      ((update-relations-meta rels m) index))))

(defn remove-other-relations
  "Intended soley for the exceptional deletion of extracts.

  Removes all relations pointing to id in the metadata of other extracts. Does
  not alter the metadata of id, since it will be needed to restore this extract
  later if we want to."
  [id]
  (fn [index]
    (let [m (metadata index id)
          rels (:relations m)]
      ((apply comp (map (fn [{:keys [entity value] :as rel}]
                          (if (= entity id)
                            (retract-1 value rel)
                            (retract-1 entity rel)))
                        rels))
       index))))
