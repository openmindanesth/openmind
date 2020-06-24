(ns openmind.transaction-fns
  "Transaction functions operating on the metadata index. The reason these are
  in a separate namespace is to make sure that I never refer to the index
  itself, thus accidentally reaching out of the transaction. That was getting to
  be a problem when everything was in the same ns."
  (:require [clojure.walk :as walk]
            [openmind.notification :as notify]
            [openmind.s3 :as s3]
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
      (log/info "invalid metadata:\n" metadata)
      index)))

(defn alter-meta
  "Set the metadata of `id` to `(f (metadata id))`."
  [id f]
  (fn [index]
    (log/trace "old metadata\n" (metadata index id))
    (log/trace "new metadata\n" (f (metadata index id)))
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

(defn add-relation [{{:keys [entity value]} :content :as content}]
  (let [update-entity (add-1 entity content)
        update-value  (add-1 value  content)]
    (comp update-entity update-value)))

(defn- retract-1 [id rel]
  (alter-meta id (fn [m] (update m :relations disj rel))))

(defn retract-relation [{:keys [entity value] :as rel}]
  (let [entity (retract-1 entity rel)
        value (retract-1 value rel)]
    (println "retracting " rel)
    (comp entity value)))

(defn update-relations-meta [rels m]
  (let [old-rels (:relations metadata)
        add      (map util/immutable (remove #(contains? old-rels %) rels))
        retract  (remove #(contains? rels %) old-rels)]
    (println "+" (count add))
    (println "-" (count retract))
    (apply comp (concat (map add-relation add)
                        (map retract-relation retract)))))

(defn update-relations [id rels]
  "Given an id and a set of rels, start a transaction in which you figure out
  which rels must be added and which removed from the existing metadata to bring
  it inline with the new set."
  (println "update relations")
  (fn [index]
    (println "acting on index")
    (let [m (metadata index id)]
      ((update-relations-meta rels m) index)))
  #_(fn [index]
    (let [metadata (metadata index id)
          old-rels (:relations metadata)
          add      (map util/immutable (remove #(contains? old-rels %) rels))
          retract  (remove #(contains? rels %) old-rels)]
      (as-> index %
        (reduce (fn [index rel] ((new-relation rel) index)) % add)
        (reduce (fn [index rel] ((retract-relation rel) index)) % retract)))))
