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
;;;;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn remove-prefix
  "Assuming v1 is a prefix sequence of v2, returns the rest of v2 after v1."
  [v1 v2]
  (cond
    (empty? v1) v2
    (= (first v1) (first v2)) (recur (rest v1) (rest v2))
    :else (throw (js/Error. "Ah Ah Ah!"))))

(defn find-tag-in-tree
  ([tree tag] (find-tag-in-tree tree tag nil))
  ([tree tag not-found]
   (let [trace (:parents tag)]
     (if (= (:id tree) (first trace))
       (loop [tree  tree
              trace (rest trace)]
         (if (empty? trace)
           (get (:children tree) (:id tag) not-found)
           (if-let [child (get tree (first trace))]
             (recur child (rest trace))
             not-found)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; View code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cancel-button [display data path]
  [:a.border-circle.plh.prh.bg-dull
   {:on-click (fn [e]
                (.stopPropagation e)
                (.preventDefault e)
                (close-path display path)
                (unselect data path))}
   "remove"])

(defn nested-filter
  "Defines the view component for a filter that has subcategories that can be
  navigated."
  [{:keys [tag-name id children]} activity selected? path opts]
  (let [activity   (get activity id)
        tag-lookup @(re-frame/subscribe [::subs/tag-lookup])
        cset       (into #{} (comp (filter #(seq (:children %))) (map :id)) children)]
    [:button.blue.filter-button.border-round.mb1
     {:on-click (fn [_]
                  (re-frame/dispatch [::events/set-filter-edit
                                      path
                                      (not selected?)]))
      :class    (when selected? "selected")}
     [:div
      [:span.prh tag-name]
      (when (seq activity)
        [cancel-button path])
      (when (seq activity)
        [:div
         [:span
          (str "(" (apply
                    str (interpose
                         " OR " (->> activity
                                     (remove (fn [[k v]]
                                               (and (empty? v)
                                                    (contains? cset k))))
                                     (map key)
                                     (map #(get tag-lookup %)))))")")]])]]))

(defn leaf-filter [{:keys [tag-name id] :as tag} display data path]
  (let [active? (contains? (tags data) tag)]
    [:button.feature-button.border-round.mb1
     {:class (when active? "active")
      :on-click #(re-frame/dispatch [(if active?
                                       (unselect data tag)
                                       (select data tag))
                                     path])}
     tag-name]))

(defn filter-button [tag display data path]
  (if (seq (:children tag))
    [nested-filter tag display data path]
    [leaf-filter tag display data path]))

(defn filter-view
  "The taxonomy of tags forms a tree, but from that tree, only one thread of
  nodes can be visible at a time in the interface. Given that display-path,
  recursively render the appropriate nodes."
  [tag display data path]
  (let [current (first (remove-prefix (:parents tag) (get-path display)))]
    [:div.border-blue.border-round.pl2.pr2.pb1.pt2
     (into [:div.flex.flex-wrap.space-evenly]
           (map (fn [{:keys [id] :as sub-tag}]
                  [filter-button
                   sub-tag
                   display
                   data
                   (conj path id)]))
           (vals (:children tag)))
     (when next
       (let [child     (get tag next)
             next-path (conj path next)]
         (filter-view child display data next-path)))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Search screen
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def search-tag-display
  (reify TagDisplay
    (get-path [_]
      @(re-frame/subscribe [::subs/current-filter-edit]))
    (open-path [_ path]
      (re-frame/dispatch [::events/set-filter-edit path true]))
    (close-path [_ path]
        (re-frame/dispatch [::events/set-filter-edit path false]))))

(def search-tag-data
  (reify TagSet
    (tags [_]
      @(re-frame/subscribe [::subs/search-filters]))
    (select [_ tag]
      (re-frame/dispatch [::events/add-filter-feature
                          (conj (:parents tag) (:id tag))]))
    (unselect [_ tag]
       (re-frame/dispatch [::events/remove-filter-feature
                           (conj (:parents tag) (:id tag))]))

    ILookup
    (-lookup [this tag]
      (-lookup this tag nil))
    (-lookup [this tag not-found]
      (find-tag-in-tree (tags this) tag not-found))))

(defn search-filter []
  (let [tag-tree @(re-frame/subscribe [::subs/tags])]
    ;; HACK: Select anaesthesia automatically.
    (when (empty? (get-path search-tag-display))
      (open-path search-tag-display (:id tag-tree)))
    [filter-view tag-tree search-tag-data search-tag-display [(:id tag-tree)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tag-selector []
  [:div])
