(ns openmind.views.tags
  (:require [openmind.events :as events]
            [openmind.subs :as subs]
            [re-frame.core :as re-frame]))

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
  (when (contains? data tag)
    (unselect data tag))
  (run! #(cancel-descendents data %)
        (vals (:children tag))))

(defn cancel-button [tag display data]
  [:a.border-circle.plh.prh.bg-dull
   {:on-click (fn [e]
                (.stopPropagation e)
                (.preventDefault e)
                (when (contains? display tag)
                  (close-path display tag))
                (cancel-descendents data tag))}
   "remove"])

(defn nested-filter
  "Defines the view component for a filter that has subcategories that can be
  navigated."
  [{:keys [tag-name id children] :as tag} display data]
  (let [selected? (contains? data tag)]
    [:button.blue.filter-button.border-round.mb1
     {:on-click (fn [_]
                  (if (contains? display tag)
                    (close-path display tag)
                    (open-path display tag))
                  (when-not selected?
                    (select data tag)))
      :class    (when (contains? display tag) "selected")}
     [:div
      [:span.prh tag-name]
      (when selected?
        [cancel-button tag display data])
      (when selected?
        [:div
         [:span
          (let [selected-children (->> children
                                       vals
                                       (filter #(contains? data %))
                                       (map :tag-name))]
            (str "("
                 (if (seq selected-children)
                   (apply str (interpose " | " selected-children))
                   "ANY")
                 ")"))]])]]))

(defn leaf-filter [{:keys [tag-name id] :as tag} display data]
  (let [active? (contains? (tags data) tag)]
    [:button.feature-button.border-round.mb1.filter-button
     {:class (when active? "active")
      :on-click #(if active?
                   (unselect data tag)
                   (select data tag))}
     tag-name]))

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
      [:div.border-blue.border-round.pl2.pr2.pb1.pt2
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
  (create-display ::subs/current-filter-edit
                  ::events/set-filter-edit))

(def search-tag-data
  (create-data-manager ::subs/search-filters
                       ::events/add-filter-feature
                       ::events/remove-filter-feature))

(defn tag-view [display data]
  (let [tag-tree @(re-frame/subscribe [::subs/tags])]
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
