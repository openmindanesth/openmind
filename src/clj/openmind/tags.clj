(ns openmind.tags
  "This ns is only about search filtering. To see about creating tags in the
  fist place, go to `dev/tag_migration.clj`"
  (:require [openmind.s3 :as s3]))

(def tag-lookup-hash
  ;;FIXME: This should *not* be hardcoded
  #openmind.hash/ref "fff379f5824c511757d5868c3270f046")

(def tag-tree
   "Tree of taxonomy tags fetched from datastore."
  (-> tag-lookup-hash
      s3/lookup
      :content))

;; TODO: This is a monster. I've forgotten how it works already. Either rewrite
;; it a lot more clearly or write docs.
(defn tags-filter-query [root tags]
  (let [parents (into #{} (mapcat :parents (map #(get tag-tree %) tags)))]
    (->> tags
         (remove #(contains? parents %))
         (select-keys tag-tree)
         (map (fn [[k v]] (assoc v :tag k)))
         (group-by :parents)
         vals
         (map #(mapv :tag %))
         (mapv (fn [ts] {:terms {:tags ts}})))))
