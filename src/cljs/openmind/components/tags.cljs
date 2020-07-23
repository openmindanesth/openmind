(ns openmind.components.tags
  (:require [clojure.string :as string]
            [openmind.components.common :as common]
            [openmind.edn :as edn]
            [re-frame.core :as re-frame]))

;; FIXME: This ns needs a serious overhaul. Protocols were a mistake. Had I just
;; stuck to maps of events, the logic would be a lot clearer.

(defn decode-url-filters
  "Takes the URL filter list and returns a sorted set of filter tags."
  [filters]
  (if (seq filters)
    (into #{}
          (map edn/read-string)
          (string/split filters #","))
    #{}))

(defn encode-url-filters
  "Given a set of filters, encode them for use in the URL."
  [filters]
  (apply str (interpose "," filters)))

(defn route->query
  "Parses the current search out of the URL query"
  [route]
  (-> route
      :parameters
      :query
      (update :filters decode-url-filters)))

;;;;; Subs

(re-frame/reg-sub
 ::search
 (fn [db]
   (::search db)))

(re-frame/reg-sub
 ::current-filter-edit
 :<- [::search]
 (fn [search]
   (:search/selection search)))

(re-frame/reg-sub
 ::search-filters
 :<- [:route]
 (fn [route]
   (:filters (route->query route))))

(re-frame/reg-sub
 ::tag-tree-root
 (fn [db]
   (:tag-tree-hash db)))

(re-frame/reg-sub
 ::tags
 :<- [::tag-tree-root]
 (fn [root _]
   @(re-frame/subscribe [:content root])))

(defn build-tag-lookup [{:keys [name id children] :as tag}]
  (into {id tag} (map build-tag-lookup) (vals children)))

(re-frame/reg-sub
 ::tag-lookup
 :<- [::tags]
 (fn [tags]
   (build-tag-lookup tags)))

(re-frame/reg-sub
 :tag-root
 (fn [db]
   (:tag-root-id db)))

;;;;; Events

(re-frame/reg-event-db
 ::set-filter-edit
 (fn [db [_ path add?]]
   (assoc-in db [::search :search/selection]
             (if add?
               path
               (vec (butlast path))))))

(defn update-filter-tags
  [cofx tags f]
  (let [query (-> cofx
                  :db
                  :openmind.router/route
                  route->query
                  (update :filters #(reduce f % (map :id tags)))
                  (update :filters encode-url-filters))]
    {:dispatch [:navigate {:route :search :query query}]}))

(re-frame/reg-event-fx
 ::add-filter-feature
 (fn [cofx [_ & tags]]
   (update-filter-tags cofx tags conj)))

(re-frame/reg-event-fx
 ::remove-filter-feature
 (fn [cofx [_ & tags]]
   (update-filter-tags cofx tags disj)))

(defprotocol TagDisplay
  "Methods to control the visual state of the tag selector."
  (get-path [this])
  (open-path [this path])
  (close-path [this path]))

(defprotocol TagSet
  "Methods to control the current selection of tags."
  (tags [this])
  (select [this path])
  (unselect [this path]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; View code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-descendents [tag]
  (when tag
    (conj (mapcat get-descendents (vals (:children tag))) tag)))

(defn cancel-descendents [data tag]
  (let [children (get-descendents tag)]
    (unselect data children)))

(defn cancel-button [onclick]
  [:a.border-circle.bg-white.text-black.border-black.relative.right
   {:style    {:top      "-1px"
               :right    "-9px"}
    :on-click (juxt common/halt onclick)}
   [:span.centre "x"]])

(defn nested-filter
  "Defines the view component for a filter that has subcategories that can be
  navigated."
  [{:keys [name id children] :as tag} display data]
  (let [selected? (contains? (tags data) (:id tag))]
    [:button.filter-button.border-round.mb1.text-white.mrh.mlh.no-wrap
     {:on-click (fn [_]
                  (if (contains? display tag)
                    (close-path display tag)
                    (open-path display tag))
                  (when-not selected?
                    (select data tag)))
      :class    (if (contains? display tag)
                  "bg-dark-blue"
                  "bg-blue")}
     [:div {:style (if selected? {:margin-left "20px"}
                       {:margin-left  "20px"
                        :margin-right "20px"})}
      (when selected?
        [cancel-button (fn [_]
                         (when (contains? display tag)
                           (close-path display tag))
                         (cancel-descendents data tag))])
      [:div.flex.flex-centre
       [:span  name]]
      (when selected?
        [:div
         [:span {:style {:margin-right "20px"}}
          (let [selected-children (->> children
                                       vals
                                       (filter #(contains? (tags data) (:id %)))
                                       (map :name))]
            (str "("
                 (if (seq selected-children)
                   (apply str (interpose " | " selected-children))
                   "ANY")
                 ")"))]])]]))

(defn leaf-filter [{:keys [name id] :as tag} display data]
  (let [active? (contains? (tags data) (:id tag))]
    [:button.border-round.mb1.filter-button.text-white.mrh.mlh.no-wrap
     {:class    (if active? "bg-light-blue" "bg-grey")
      :on-click #(if active?
                   (unselect data [tag])
                   (select data tag))}
     [:span.p2 name]]))

(defn filter-button [tag display data]
  (if (seq (:children tag))
    [nested-filter tag display data]
    [leaf-filter tag display data]))

(defn remove-prefix
  "Assuming v1 is a prefix sequence of v2, returns the rest of v2 after v1."
  [v1 v2]
  (cond
    (empty? v1)               v2
    (= (first v1) (first v2)) (recur (rest v1) (rest v2))
    :else                     (throw (js/Error. "Ah Ah Ah!"))))

(def tag-sort-hack
  {#{"species" "awake" "anaesthetic" "level" "physiology"
     "modality" "scale" "application"}
   [["species"] ["awake"] ["anaesthetic" "level" "physiology"]
    ["modality" "scale" "application"]]

   #{"human" "monkey" "rat" "mouse"}
   [["human" "monkey" "rat" "mouse"]]

   #{"propofol" "benzodiazepines" "editomidate" "barbitol"}
   [["propofol" "benzodiazepines" "editomidate" "barbitol"]]

   #{"isoflurane" "sevoflurane" "desflurane" "halothane"}
   [["isoflurane" "sevoflurane" "desflurane" "halothane"]]

   #{"(dex)metedetomidine" "xylazine"}
   [["(dex)metedetomidine" "xylazine"]]

   #{"GABAergic" "vapours" "α2 AR agonists" "NMDA antagonists"}
   [["GABAergic" "vapours" "α2 AR agonists" "NMDA antagonists"]]

   #{"light" "moderate" "deep"}
   [["light" "moderate" "deep"]]

   #{"hemodynamics" "blood pressure" "heart rate" "breathing rate" "other effects"}
   [["hemodynamics" "blood pressure" "heart rate" "breathing rate" "other effects"]]

   #{"non-invasive" "invasive"}
   [["non-invasive" "invasive"]]

   #{"hemodynamics" "electric" "magnetic" "Ca²⁺" "other ionic"}
   [["hemodynamics"] ["electric"] ["magnetic" "Ca²⁺" "other ionic"]]

   #{"large scale" "cortex" "ensemble" "neuron level" "brain slice"}
   [["large scale"] ["cortex"] ["ensemble" "neuron level"] ["brain slice"]]

   #{"visual" "auditory" "somatosensory" "olfactory"}
   [["visual" "auditory" "somatosensory" "olfactory"]]

   #{"nociceptive" "chronic"}
   [["nociceptive" "chronic"]]

   #{"optogenetics" "chemogenetics" "TMS" "FUS" "deep brain"}
   [["optogenetics" "chemogenetics" "TMS" "FUS" "deep brain"]]

   #{"sensory" "nociception & pain" "resting state" "brain stimulation"}
   [["sensory" "nociception & pain" "resting state" "brain stimulation"]]
   })

(defn tag-sort [display data nodes]
  (let [names (into #{} (map :name nodes))
        order (get tag-sort-hack names)]
    (if order
      (let [by-name (into {} (map (fn [{:keys [name] :as n}] [name n])) nodes)]
        (map (fn [group]
               (into [:div.flex.flex-wrap.space-between]
                     (map (fn [name]
                            (let [{:keys [id] :as sub-tag} (get by-name name)]
                              [filter-button sub-tag
                               display
                               data])))
                     group))
             order))
      (map (fn [{:keys [id] :as sub-tag}]
                         [filter-button sub-tag
                          display
                          data])
           nodes))))

(defn filter-view
  "The taxonomy of tags forms a tree, but from that tree, only one thread of
  nodes can be visible at a time in the interface. Given that display-path,
  recursively render the appropriate nodes."
  [tag display data]
  (when tag
    (let [path (remove-prefix (:parents tag) (get-path display))
          current (first path)]
      [:div.border-round.pl2.pr2.pb1.pt2.border-solid
       (into [:div.flex.flex-wrap.space-evenly]
             (tag-sort display data (vals (:children tag))))
       (let [sub-sel (second path)]
         (filter-view (get (:children tag) sub-sel) display data))])))

(defn create-display [sub event]
  (reify TagDisplay
    (get-path [_]
      @(re-frame/subscribe sub))
    (open-path [_ tag]
      (let [path (conj (:parents tag) (:id tag))]
        (re-frame/dispatch (into event [path true]))))
    (close-path [_ tag]
      (let [path (conj (:parents tag) (:id tag))]
        (re-frame/dispatch (into event [path false]))))

    ILookup
    (-lookup [this tag]
      (-lookup this tag nil))
    (-lookup [this tag not-found]
      (if (contains? (into #{} (get-path this)) (:id tag))
        (:id tag)
        not-found))))

(defn create-data-manager [sub add remove]
  (reify TagSet
    (tags [_]
      @(re-frame/subscribe sub))
    (select [_ tag]
      (re-frame/dispatch (into add [tag])))
    (unselect [_ tags]
      (re-frame/dispatch (into remove tags)))

    ILookup
    (-lookup [this tag]
      (-lookup this tag nil))
    (-lookup [this tag not-found]
      (get (tags this) tag not-found))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Search screen
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def search-tag-display
  (create-display [::current-filter-edit]
                  [::set-filter-edit]))

(def search-tag-data
  (create-data-manager [::search-filters]
                       [::add-filter-feature]
                       [::remove-filter-feature]))

(defn tag-view [display data]
  (let [tag-tree @(re-frame/subscribe [::tags])]
    (when (empty? (get-path display))
      (open-path display [(:id tag-tree)]))
    [filter-view tag-tree display data]))

(defn search-filter []
  [tag-view search-tag-display search-tag-data])

(defn tag-widget [{:keys [selection edit]}]
  [tag-view
   (create-display (:read selection) (:set selection))
   (create-data-manager (:read edit) (:add edit) (:remove edit))])
