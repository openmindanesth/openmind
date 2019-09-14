(ns openmind.views.tags
  (:require [clojure.string :as string]
            [openmind.events :as events]
            [openmind.subs :as subs]
            [re-frame.core :as re-frame]))

;; FIXME: This ns needs a serious overhaul. Protocols were a mistake. Had I just
;; stuck to maps of events, the logic would be a lot clearer.

(defn decode-url-filters
  "Takes the URL filter list and returns a sorted set of filter tags."
  [filters]
  (if (seq filters)
    (into (sorted-set)
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
 :<- [:openmind.router/route]
 (fn [route]
   (:filters (route->query route))))

(re-frame/reg-sub
 ::tags
 (fn [db]
   (::tag-tree db)))

;;;;; Events

(defn build-tag-lookup [{:keys [tag-name id children]}]
  (into {id tag-name} (map build-tag-lookup) (vals children)))

(re-frame/reg-event-db
 :openmind/tag-tree
 (fn [db [_ tree]]
   (assoc db
          ::tag-tree tree
          ::tag-lookup (build-tag-lookup tree))))

(re-frame/reg-event-db
 ::set-filter-edit
 (fn [db [_ path add?]]
   (assoc-in db [::search :search/selection]
             (if add?
               path
               (vec (butlast path))))))

(defn update-filter-tags
  [cofx tag f]
  (let [query (-> cofx
                  :db
                  :openmind.router/route
                  route->query
                  (update :filters f (:id tag))
                  (update :filters encode-url-filters))]
    {:dispatch [:openmind.router/navigate
                {:route :openmind.search/search
                 :query query}]}))

(re-frame/reg-event-fx
 ::add-filter-feature
 (fn [cofx [_ tag]]
   (update-filter-tags cofx tag conj)))

(re-frame/reg-event-fx
 ::remove-filter-feature
 (fn [cofx [_ tag]]
   (update-filter-tags cofx tag disj)))

;;;;; REVIEW: Are Protocols really the way to encapsulate chunks of re-frame state?

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

(defn cancel-descendents [data tag]
  (when (contains? (tags data) (:id tag))
    (unselect data tag))
  (run! #(cancel-descendents data %)
        (vals (:children tag))))

(defn cancel-button [tag display data]
  [:a.border-circle.bg-white.text-black.border-black
   {:style    {:position :relative
               :float    :right
               :top      "-1px"
               :right    "-9px"}
    :on-click (fn [e]
                (.stopPropagation e)
                (.preventDefault e)
                (when (contains? display tag)
                  (close-path display tag))
                (cancel-descendents data tag))}
   [:span.centre "x"]])

(defn nested-filter
  "Defines the view component for a filter that has subcategories that can be
  navigated."
  [{:keys [tag-name id children] :as tag} display data]
  (let [selected? (contains? (tags data) (:id tag))]
    [:button.filter-button.border-round.mb1.text-white.mrh.mlh
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
        [cancel-button tag display data])
      [:div.flex.flex-centre
       [:span  tag-name]]
      (when selected?
        [:div
         [:span {:style {:margin-right "20px"}}
          (let [selected-children (->> children
                                       vals
                                       (filter #(contains? (tags data) (:id %)))
                                       (map :tag-name))]
            (str "("
                 (if (seq selected-children)
                   (apply str (interpose " | " selected-children))
                   "ANY")
                 ")"))]])]]))

(defn leaf-filter [{:keys [tag-name id] :as tag} display data]
  (let [active? (contains? (tags data) (:id tag))]
    [:button.border-round.mb1.filter-button.text-white.mrh.mlh
     {:class    (if active? "bg-light-blue" "bg-grey")
      :on-click #(if active?
                   (unselect data tag)
                   (select data tag))}
     [:span.p2 tag-name]]))

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
             (map (fn [{:keys [id] :as sub-tag}]
                    [filter-button sub-tag
                     display
                     data]))
             (vals (:children tag)))
       (let [sub-sel (second path)]
         (filter-view (get (:children tag) sub-sel) display data))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Search screen
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-display [sub event]
  (reify TagDisplay
    (get-path [_]
      @(re-frame/subscribe [sub]))
    (open-path [_ tag]
      (let [path (conj (:parents tag) (:id tag))]
        (re-frame/dispatch [event path true])))
    (close-path [_ tag]
      (let [path (conj (:parents tag) (:id tag))]
        (re-frame/dispatch [event path false])))

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
      @(re-frame/subscribe [sub]))
    (select [_ tag]
      (re-frame/dispatch [add tag]))
    (unselect [_ tag]
       (re-frame/dispatch [remove tag]))

    ILookup
    (-lookup [this tag]
      (-lookup this tag nil))
    (-lookup [this tag not-found]
      (get (tags this) tag not-found))))

(def search-tag-display
  (create-display ::current-filter-edit
                  ::set-filter-edit))

(def search-tag-data
  (create-data-manager ::search-filters
                       ::add-filter-feature
                       ::remove-filter-feature))

(defn tag-view [display data]
  (let [tag-tree @(re-frame/subscribe [::tags])]
    ;; HACK: Select anaesthesia automatically.
    (when (empty? (get-path display))
      (open-path display [(:id tag-tree)]))
    [filter-view tag-tree display data]))

(defn search-filter []
  [tag-view search-tag-display search-tag-data])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def edit-display
  (create-display ::subs/editor-tag-view-selection
                  ::events/set-editor-selection))

(def edit-data
  (create-data-manager ::subs/editor-selected-tags
                       ::events/add-editor-tag
                       ::events/remove-editor-tag))

(defn tag-selector []
  [tag-view edit-display edit-data])
