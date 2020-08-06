(ns openmind.datastore.indicies.metadata.transaction-fns
  "Transaction functions operating on the metadata index. The reason these are
  in a separate namespace is to make sure that I never refer to the index
  itself, thus accidentally reaching out of the transaction. That was getting to
  be a problem when everything was in the same ns."
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.datastore.impl :as ds]
            [openmind.hash :as h]
            [openmind.notification :as notify]
            [taoensso.timbre :as log]))

(defn metadata [index hash]
  (-> index
      (get hash)
      ds/lookup
      :content))

(defn wrap-meta [content]
  (when (s/valid? :openmind.spec/indexical content)
    {:hash         (h/hash content)
     :time/created (java.util.Date.)
     :content      content}))

(defn set-meta [index id metadata]
  (if-let [imm (wrap-meta metadata)]
    (do
      (ds/intern imm)
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

(defn set-extract
  "Recursively set `:extract` to `id` in every node of `comment-tree`."
  [comment-tree id]
  (if comment-tree
    (walk/prewalk (fn [node]
                    (if (and (map? node) (contains? node :extract))
                      (assoc node :extract id)
                      node))
                  comment-tree)
    []))

(defn forward-metadata [prev id author created]
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
                        (update :history
                                (fnil conj [])
                                {:history/previous-version prev
                                 :time/created             created
                                 :author                   author}))]
      (set-meta index id new-meta))))

(defn create-extract [[_ hash author created] content]
  (if-let [previous (:history/previous-version content)]
    (forward-metadata previous hash author)
    (fn [index]
      (set-meta index hash {:extract      hash
                            :author       author
                            :time/created created}))))

;;;;; Comments

(defn insert-comment
  [comment-tree hash {:keys [reply-to] :as comment}]
  (let [c* (-> comment
               :content
               (dissoc :reply-to)
               (assoc :hash hash
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

(defn add-comment-to-meta [hash {:keys [extract] :as comment}]
  (alter-meta extract (fn [m] (update m :comments insert-comment hash comment))))

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
        update-value  (add-1 value content)]
    (comp update-entity update-value)))

(defn- retract-1 [id rel]
  (alter-meta id (fn [m] (update m :relations disj rel))))

(defn retract-relation [{:keys [entity value] :as rel}]
  (let [entity (retract-1 entity rel)
        value (retract-1 value rel)]
    (comp entity value)))

#_(defn update-relations-meta [rels m]
  (let [old-rels (:relations m)
        add      (map util/immutable (remove #(contains? old-rels %) rels))
        ;; FIXME: This logic should be performed in the client, and the client
        ;; should issue retractions for relations. Retractions should be values
        ;; which get stored just like relations themselves (which are really
        ;; assertions of realtions, not the relations themselves, whatever those
        ;; are).
        retract  (remove #(contains? rels %) old-rels)]
    (apply comp (concat (map add-relation add)
                        (map retract-relation retract)))))

#_(defn update-relations [id rels]
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
