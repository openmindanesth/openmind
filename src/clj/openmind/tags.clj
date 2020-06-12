(ns openmind.tags
  (:require [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]))

(def the-domain
  "anaesthesia")

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

(defn build-tag-map [domain tree]
  (into {} (map (fn [{:keys [hash content]}] [hash content]))
        (create-tag-data domain tree)))

(defn build-tag-tree-from-strings [domain tree tag-map]
  (let [tags    (create-tag-data domain tree)
        root    (first tags)]
    (invert-tag-tree tag-map (assoc (:content root) :id (:hash root)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Example tag tree, and tag creation logic.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def demo-tag-tree
  "For development purposes, I'm encoding the tress of tags here. Once we've got
  the system up and running it will need to be an interactive search through
  elastic. Something like graphiql (like graphiql) would be great."
  {the-domain {"species"     {"human"  {}
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

(def tag-tree-mark2
  "For development purposes, I'm encoding the tress of tags here. Once we've got
  the system up and running it will need to be an interactive search through
  elastic. Something like graphiql (like graphiql) would be great."
  {the-domain {"species"     {"human"  {}
                              "monkey" {}
                              "rat"    {}
                              "mouse"  {}}
               "awake"       {}
               "anaesthetic" {"GABAergic"        {"propofol"        {}
                                                  "editomidate"     {}
                                                  "benzodiazepines" {}
                                                  "barbitol"        {}}
                              "vapours"          {"isoflurane"  {}
                                                  "sevoflurane" {}
                                                  "desflurane"  {}
                                                  "halothane"   {}}
                              "α2 AR agonists"   {"(dex)metedetomidine" {}
                                                  "xylazine"            {}}
                              "NMDA antagonists" {"ketamine" {}}}
               "level"       {"light"    {}
                              "moderate" {}
                              "deep"     {}}
               "physiology"  {"blood pressure" {}
                              "heart rate"     {}
                              "hemodynamics"   {}
                              "breathing rate" {}
                              "other effects"  {}}
               "modality"    {"hemodynamics" {"non-invasive" {}
                                              "invasive"     {}}
                              "electric"     {"non-invasive" {}
                                              "invasive"     {}}
                              "magnetic"     {}
                              "Ca²⁺"         {}
                              "other ionic"  {}}
               "scale"       {"neuron level" {}
                              "ensemble"     {}
                              "cortex"       {}
                              "large scale"  {}
                              "brain slice"  {}}
               "application" {"sensory"            {"visual"        {}
                                                    "auditory"      {}
                                                    "olfactory"     {}
                                                    "somatosensory" {}}
                              "nociception & pain" {"nociceptive" {}
                                                    "chronic"     {}}

                              "resting state" {"functional connectivity" {}}

                              "brain stimulation" {"deep brain"    {}
                                                   "optogenetics"  {}
                                                   "chemogenetics" {}
                                                   "TMS"           {}
                                                   "FUS"           {}}}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; core logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def tag-lookup-hash
  ;;FIXME: This should *not* be hardcoded
  #openmind.hash/ref "42e59ff5ec64661ce31241ac101df39e"
  ;#openmind.hash/ref "34101d8a82a2714923a446f4bb203a31"
  )

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Tag migration #2
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def old-tag-lookup-hash
  ;;FIXME: This should *not* be hardcoded
  #openmind.hash/ref "34101d8a82a2714923a446f4bb203a31")

(def
  ^{:private true
    :doc "Tree of taxonomy tags fetched from datastore."}
  old-tag-tree
  (-> old-tag-lookup-hash
      s3/lookup
      :content))

(def new (into {} (map (fn [{:keys [hash content]}] [hash content])
                        (create-tag-data "anaesthesia" tag-tree-mark2))))

(def d  (remove #(contains? new (key %)) old-tag-tree ))

(def simple-mapping
  {"depth"    "level"
   "light"    "light"
   "moderate" "moderate"
   "deep"     "deep"

   "a2 AR antagonists"   "α2 AR agonists"
   "(dex)metedetomidine" "(dex)metedetomidine"
   "xylazine"            "xylazine"

   "volatile ethers" "vapours"
   "isoflurane"      "isoflurane"
   "sevoflurane"     "sevoflurane"

   "neuron level"       "neuron level"
   "cortex (EEG)"       "cortex"
   "large scale (fMRI)" "large scale"
   "ensemble (LFP)"     "ensemble"

   "sensory stimuli" "sensory"
   "somatosensory"   "somatosensory"
   "olfactory"       "olfactory"
   "auditory"        "auditory"
   "visual"          "visual"

   "nociceptive" "nociceptive"})

(def extra
  {#openmind.hash/ref "90f270725ca50bb799bf2d77afe9ce2e"
   #openmind.hash/ref "146e347748eec1108ac092c7ab0c3aff"})

(def key-transfer
  (let [new-by-name (into {} (map (fn [[id {:keys [name] :as content}]]
                                    [name content]))
                          new)]
    (into {}
          (map (fn [[id {:keys [name]}]]
                 (let [t (get new-by-name (get simple-mapping name))]
                   [id (util/immutable (assoc t :history/previous-version id))])))
          d)))

(def tag-update-map
  (into extra
        (map (fn [[k {:keys [hash]}]]
               [k hash]))
         key-transfer))

(def tags-to-add
  (concat
   (into [(-> new
              (get (val (first extra)))
              (assoc :history/previous-version (key (first extra)))
              util/immutable)]
         (comp
          (remove #(contains? old-tag-tree (key %)))
          (remove (fn [[k {:keys [name]}]]
                    (contains? (into #{} (vals simple-mapping)) name)))
          (map val)
          (map util/immutable))
         (dissoc new (val (first extra))))
   (vals key-transfer)))

(defn create-tag-data2 [domain tree]
  (letfn [(inner [tree parents]
            (mapcat (fn [[k v]]
                      (when-let [t {:domain  domain
                                    :name    k
                                    :parents parents}]
                        (conj (inner v (conj parents k))
                              t)))
                    tree))]
    (inner tree [])))

(defn doctored [name parents]
  (let [matches (filter #(and (= name (:name (:content %)))
                              (= parents (:parents (:content %))))
                        tags-to-add)]
    (if (< 1 (count matches))
      (throw (Exception. "???"))
      (first matches))))

(defn orphan-quest [m n parents]
  (loop [ids []
         [p & ps] parents]
    (if p
      (let [cs (filter #(= p (:name (val %))) m)]
        (if (= 1 (count cs))
          (recur (conj ids (key (first cs))) ps)
          (let [c' (filter #(= ids (:parents (val %))) cs)]
            (if (= 1 (count c'))
              (recur (conj ids (key (first c'))) ps)
              (throw (Exception. "danger will robinson"))))))
      ids)))

(def full-list
  (util/immutable
   (reduce (fn [the-map {:keys [domain name parents]}]
             (let [p' (orphan-quest the-map name parents)]
))
           {}
           (sort-by #(count (:parents %))
                    (create-tag-data2 "anaesthesia" tag-tree-mark2)))))


(def lookup-tree-next
  (util/immutable
   (build-tag-tree-from-strings the-domain tag-tree-mark2
                                (:content full-list))))
