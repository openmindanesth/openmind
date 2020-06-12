(ns reindexing
  (:require [clojure.core.async :as async]
            [openmind.elastic :as es]
            [openmind.indexing :as index]
            [openmind.pubmed :as pubmed]
            [openmind.s3 :as s3]
            [openmind.tags :as tags]
            [openmind.util :as util]
            setup))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Full scale migration #2 (10 June 2020)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-keys
  [:text
   :author
   :tags
   :source
   :extract/type
   :figure
   :source-material
   :history/previous-version])

(defn refetch* [{:keys [url]}]
  (assoc (async/<!! (pubmed/article-info url)) :url url))

(defonce refetch (memoize refetch*))

(defn transfer-tags [tags]
  (into #{} (map #(get tags/tag-update-map % %)) tags))

(defn update-extract [eid]
  (let [current (:content (s3/lookup eid))]
    (let [f (or (:figure current) (first (:figures current)))
          source (refetch (:source current))]
      (-> current
          (update :tags transfer-tags)
          (assoc :source source)
          (select-keys valid-keys)
          (merge (when f {:figure f}))
          util/immutable))))

(defn indexify [cs]
  (reduce index/insert-comment [] cs))

(defn flatten-comment-tree [eid ql comments reply-to]
  (mapcat (fn [{:keys [replies] :as com}]
            (let [c (-> com
                        (select-keys [:text :author])
                        (assoc :extract eid)
                        (merge (when reply-to {:reply-to reply-to}))
                        util/immutable)]
              (into [c] (flatten-comment-tree eid ql replies (ql (:hash c))))))
          comments))

(defn update-comment-tree [eid ql comments]
  (flatten-comment-tree (ql eid) ql comments nil))

(defn update-metadata [new-extracts-map [eid metaid]]
  (let [ql   (into {} (map (fn [[k v]] [k (:hash v)])) new-extracts-map)
        eid' (get ql eid)

        {:keys [comments relations history] :as om} (:content (s3/lookup metaid))

        rels
        (into #{} (map (fn [{:keys [entity value time/created] :as rel}]
                         (-> rel
                             (dissoc :hash :time/created)
                             (update :entity ql)
                             (update :value ql)
                             util/immutable
                             (assoc :time/originally-created created))))
              relations)
        comms (update-comment-tree eid ql comments)]
    {:new-relations rels
     :new-comments comms
     :old-id eid
     :meta
     (util/immutable
      {:extract   eid'
       :history   (mapv #(update % :history/previous-version ql)
                        history)
       :relations (into #{} (map :content rels))
       :comments  (indexify comms)})}))

(defn v2-upgrade-all! []
  ;; Add new tags

  (let [old-meta-map     (:content (s3/lookup index/extract-metadata-uri))
        new-extracts-map (into {}
                               (map (fn [[id _]]
                                      (println "updating" id)
                                      [id (update-extract id)]))
                               old-meta-map)
        new-meta         (map (partial update-metadata new-extracts-map)
                              old-meta-map)
        new-rels         (into [] (comp (map :new-relations) cat) new-meta)
        new-comments     (into [] (comp (map :new-comments) cat) new-meta)
        meta             (into {} (map (fn [{:keys [old-id meta]}]
                                         [old-id meta]))
                               new-meta)
        es-index         (into #{} (map :hash) (vals new-extracts-map))
        meta-index       (into {} (map (fn [id]
                                         [(:hash (get new-extracts-map id))
                                          (:hash (get meta id))]))
                               (keys old-meta-map))]
    (run! s3/intern (concat
                     tags/tags-to-add
                     new-rels
                     new-comments
                     (vals new-extracts-map)
                     (vals meta)))

    (@#'s3/write! es/active-es-index (util/immutable es-index))
    (@#'s3/write! index/extract-metadata-uri (util/immutable meta-index))

    (setup/load-es-from-s3!)))
