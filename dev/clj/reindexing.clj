(ns reindexing
  (:require [clojure.core.async :as async]
            [openmind.edn :as edn]
            [openmind.util :as util]
            [openmind.s3 :as s3]
            [openmind.json :as json]
            [openmind.elastic :as es]
            [openmind.tags :as tags]))


;;;;; Transitional logic

(def old-tags (edn/read-string (slurp "tags.edn")))

(def old-tag-map
  (into {"anaesthesia" (key (first old-tags))}
        (map (fn [[k {:keys [tag-name]}]]
               [tag-name k]))
        (val (first old-tags))))

(def old-tag-tree
  (tags/invert-tag-tree
   (assoc (val (first old-tags)) "8PvLV2wBvYu2ShN9w4NT"
          {:tag-name "anaesthesia" :parents []})
   {:tag-name "anaesthesia" :id "8PvLV2wBvYu2ShN9w4NT"}))

(def new-tags
  (tags/create-tag-data (key (first tags/demo-tag-tree)) tags/demo-tag-tree ))

(def tag-id-map
  "Map from old es tag ids to hashes"
  (into {}
        (map (fn [t]
               (let [tname (-> t :content :name)]
                 [(get old-tag-map tname) (:hash t)])))
        new-tags))


(def new-tag-map
  (into {} (map (fn [x] [(:hash x) (:content x)])) new-tags))

(def new-tag-map-imm
  (util/immutable new-tag-map))

(def new-tag-tree
  (tags/invert-tag-tree new-tag-map
                   {:id #openmind.hash/ref "ad5f984737860c4602f4331efeb17463"
                    :name "anaesthesia" :domain "anaesthesia" :parents []}))

(def new-tags-imm
  (util/immutable new-tag-tree))


(def extracts*
  (-> "extracts.edn" slurp edn/read-string))

(def eids
  "IDs of the extracts I know are correct."
  #{"V_tDYW0BvYu2ShN9-IQM"
    "WftGYW0BvYu2ShN9_4Ra"
    "W_vcZG0BvYu2ShN90ITc"
    "VvtDYW0BvYu2ShN9pYRI"
    "b_shRG4BvYu2ShN9qYQU"})

(def extracts
  (filter #(contains? eids (:id %)) extracts*))


(def figrep
  "https://github.com/openmindanesth/openmind/raw/27d246d42bbe8512ec3db67d75a820307ffe2e14/B8D82E08-3E2C-4F48-9A7A-ED7B92DBE7F6.png")

(defn extract-figure [{:keys [figure figure-caption author text]}]
  (when figure
    (merge
     {:image-data (if (< (count figure) 200) figrep figure)
      :author     (or author
                      {:orcid-id "0000-0003-1053-9256"
                       :name     "Henning Matthias Reimann"})}
     (when figure-caption
       {:caption figure-caption}))))

(def figures
  (into {}
        (map (fn [extract]
               (let [f (extract-figure extract)]
                 (when f
                   [(:id extract) (util/immutable f)]))))
        extracts))

(def new-extracts
  (map (fn [extract]
         (let [sd (:source-detail extract)]
           (merge
            {:text         (:text extract)
             :source       (-> sd
                               (assoc  :url (:source extract))
                               (assoc :publication/date
                                      (:date sd))
                               (dissoc :date))
             :tags         (mapv tag-id-map (:tags extract))
             :author       (or (:author extract)
                               {:orcid-id "0000-0003-1053-9256"
                                :name "Henning Matthias Reimann"})
             :extract/type (keyword (:extract-type extract))}
            (when-let [f (:hash (get figures (:id extract)))]
              {:figures [f]})
            {:time/created (if-let [t (:created-time extract)]
                             (.parse  json/dateformat t)
                             (java.util.Date.))})))
       (filter #(contains? eids (:id %))
               extracts)))

(def imm-extracts
  (mapv util/immutable new-extracts))


(defn transfer! []
  (run! s3/intern new-tags)
  (s3/intern new-tag-map-imm)
  (s3/intern new-tags-imm)
  (run! s3/intern (vals figures))
  (run! s3/intern imm-extracts))

(defn index []
  (run! es/index-extract! imm-extracts))
