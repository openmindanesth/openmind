(ns openmind.tags
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [openmind.elastic :as es]))

(def tag-tree
  "For development purposes, I'm encoding the tress of tags here. Once we've got
  the system up and running it will need to be an interactive search through
  elastic. Something like graphiql (like graphiql) would be great."
  {"anaesthesia" {"species"     {"human"  {}
                                 "monkey" {}
                                 "rat"    {}
                                 "mouse"  {}}
                  "modality"    {"neuron level"       {}
                                 "ensemble (LFP)"     {}
                                 "cortex (EEG)"       {}
                                 "large scale (fMRI)" {}}
                  "application" {"sensory stimuli"   {"visual"        {}
                                                      "auditory"      {}
                                                      "olfactory"     {}
                                                      "somatosensory" {}
                                                      "nociceptive"   {}}
                                 "resting state"     {}
                                 "brain stimulation" {"deep brain"    {}
                                                      "optogenetics"  {}
                                                      "chemogenetics" {}}}
                  "anaesthetic" {"GABAergic"         {"propofol"        {}
                                                      "editomidate"     {}
                                                      "benzodiazepines" {}
                                                      "barbitol"        {}}
                                 "volatile ethers"   {"isoflurane"  {}
                                                      "sevoflurane" {}}
                                 "a2 AR antagonists" {"(dex)metedetomidine" {}
                                                      "xylazine"            {}}
                                 "NMDA antagonists"  {"ketamine" {}}}
                  "depth"       {"light"    {}
                                 "moderate" {}
                                 "deep"     {}}
                  "physiology"  {"blood pressure" {}
                                 "heart rate"     {}
                                 "hemodynamics"   {}
                                 "breathing rate" {}
                                 "other effects"  {}}}})

(defonce
  ^{:private true
    :doc "Top level tag domains. These are presently invisible to the client since the
  only option is anaesthesia."}
  top-level-tags
  (atom nil))

(defonce
  ^{:private true
    :doc "Tag cache (this is going to be looked up a lot)."}
  tags
  (atom {}))

(defn find-id [res]
  (when (:body res)
    (when-let [body (json/read-str (:body res))]
      (when (= "created" (get body "result"))
        (get body "_id")))))


(defn subtag-lookup-query [index root]
  (let [query {:size 1000 :query {:match {:parents root}}}]
    (es/search index query)))

(defn top-level-tags-query [index]
  (es/search index {:query {:bool {:must_not {:exists {:field :parents}}}}}))

(defn get-top-level-tags []
  (async/go
    (if @top-level-tags
      @top-level-tags
      (let [t (into {}
                    (map
                     (fn [{id :_id {tag :tag-name} :_source}]
                       [tag id]))
                    (-> (top-level-tags-query es/tag-index)
                        es/request<!))]
        (reset! top-level-tags t)
        t))))

(defn lookup-tags [root]
  (async/go
    (->> (subtag-lookup-query es/tag-index root)
         es/request<!
         (map (fn [{:keys [_id _source]}]
                [_id _source]))
         (into {}))))

(defn get-tag-tree [root]
  (async/go
    (if (contains? @tags root)
      (get @tags root)
      ;; Wasteful, but at least it's consistent
      (let [v (async/<! (lookup-tags root))]
        (swap! tags assoc root v)
        v))))

(defn reconstruct [root re]
  (assoc root :children (into {}
                              (map (fn [c]
                                     (let [t (reconstruct c re)]
                                       [(:id t) t])))
                              (get re (:id root)))))

(defn invert-tag-tree [tree root-node]
  (let [id->node (into {} tree)
        parent->children (->> tree
                              (map (fn [[id x]] (assoc x :id id)))
                              (group-by :parents)
                              (map (fn [[k v]] [(last k) v]))
                              (into {}))]
    (reconstruct root-node parent->children)))

(defn tags-filter-query [root tags]
  (async/go
    (let [root-id (get (async/<! (get-top-level-tags)) root)
          tag-tree (async/<! (get-tag-tree root-id))
          parents (into #{} (mapcat :parents (map #(get tag-tree %) tags)))]
      (->> tags
           (remove #(contains? parents %))
           (select-keys tag-tree)
           (map (fn [[k v]] (assoc v :tag k)))
           (group-by :parents)
           vals
           (map #(map :tag %))
           (map (fn [ts] {:terms {:tags ts}}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Initialising the DB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index-tag-tree [index tree parents]
  (run! (fn [[k v]]
          (async/go
            (let [tag-data {:tag-name k
                            :parents parents}
                  id (find-id (async/<! (es/send-off! (es/index-tag index tag-data))))]
              (tap> [k id parents])
              (index-tag-tree index v (conj parents id)))))
        tree))
