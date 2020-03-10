(ns openmind.tags
  (:require [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Creating tags
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-tag-data [domain tree]
  (letfn [(inner [tree parents]
            (mapcat (fn [[k v]]
                      (when-let [t (util/immutable {:domain  domain
                                                    :name    k
                                                    :parents parents})]
                        (conj (inner v (conj parents (:hash t)))
                              t)))
                    tree))]
    (inner tree [])))


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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Example tag tree, and tag creation logic.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private demo-tag-tree
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

;;;;; Initialisation logic

(def tags
  (create-tag-data (key (first demo-tag-tree)) demo-tag-tree ))

(def new-tag-map
  (into {} (map (fn [x] [(:hash x) (:content x)])) new-tags) )

;; NB: This guys can be saved to S3 and read back, or just recalculated at
;; will. The hashing means that they are indexed by value, and so long as we
;; don't change the demo-tag-tree above, the two will always coincide. Reading a
;; hash back from S3 ensures that we never cheat though.
;;
;; To evolve the tree in the future, we need indirection in the form of an index
;; that can change transactionally over time and points to the latest hash for
;; these values. That's still to come though.
(def new-tag-map-imm
  (util/immutable new-tag-map))

(def new-tag-tree
  (invert-tag-tree new-tag-map
                   {:id #openmind.hash/ref "ad5f984737860c4602f4331efeb17463"
                    :name "anaesthesia" :domain "anaesthesia" :parents []}))

(def new-tags-imm
  (util/immutable new-tag-tree))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; core logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def tag-lookup-hash
  ;;FIXME: This should *not* be hardcoded
  "34101d8a82a2714923a446f4bb203a31")

(def
  ^{:private true
    :doc "Tree of taxonomy tags fetched from datastore."}
  tag-tree
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
         (map #(map :tag %))
         (map (fn [ts] {:terms {:tags ts}})))))
