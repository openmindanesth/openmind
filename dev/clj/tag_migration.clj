(ns tag-migration
  "This whole file is an inefficient ball of mud. I'm keeping it for historical
  reasons because the way the data store is designed, this is the only way to
  understand how it was built in the first place."
  (:require [openmind.s3 :as s3]
            [openmind.util :as util]))

(def the-domain
  "anaesthesia")

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

(def simple-mapping
  {"depth"    "level"
   "light"    "light"
   "moderate" "moderate"
   "deep"     "deep"

   "modality" "scale"

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

(def inv-simple
  (into {} (comp (map reverse) (map vec)) simple-mapping))

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

(def new-tag-tree
  (util/immutable
   (reduce (fn [the-map {:keys [domain name parents] :as tag}]
             (let [p'   (orphan-quest the-map name parents)
                   old-name (get inv-simple name)
                   prev (when (and old-name (not= old-name name))
                          (first
                           (filter #(= old-name (:name (val %))) old-tag-tree)))]
               (let [imm (util/immutable
                          (merge
                           (assoc tag :parents p')
                           (when prev
                             {:history/previous-version (key prev)})))]
                 (assoc the-map (:hash imm) (:content imm)))))
           {}
           (sort-by #(count (:parents %))
                    (create-tag-data2 the-domain tag-tree-mark2)))))

(defn find-by-name [name index]
   (key (first (filter #(= name (:name (val %))) index))))

(def tag-transfer-map
  (into {}
        (comp
         (map (fn [names]
                ;; This is probably too clever for my future self
                (mapv find-by-name names
                      [old-tag-tree (:content new-tag-tree)])))
         (remove nil?))
        simple-mapping))

(def lookup-tree-next
  (util/immutable
   (build-tag-tree-from-strings the-domain tag-tree-mark2
                                (:content new-tag-tree))))
(defn intern-all! []
  (s3/intern new-tag-tree)
  (s3/intern lookup-tree-next)

  (run! s3/intern
        (map util/immutable (vals (:content new-tag-tree)))))
