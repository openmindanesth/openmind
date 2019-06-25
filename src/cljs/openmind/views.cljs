(ns openmind.views
  (:require
   [reagent.core :as reagent]
   [re-frame.core :as re-frame]
   [openmind.events :as events]
   [openmind.subs :as subs]))

;; TODO: Look into tailwind

(defn pass-off [k]
  (fn [ev]
    (re-frame/dispatch [::events/form-data k (-> ev .-target .-value)])))

(defn text-box
  [k label & [{:keys [placeholder class]}]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex.vcenter.mb1h
     [:span.basis-12  [:b label]]
     [:input.grow-4 (merge {:id        (name k)
                            :type      :text
                            :on-change (pass-off k)}
                           (cond
                             content     {:value content}
                             placeholder {:placeholder placeholder})
                           (when class
                             {:class class}))]]))

;; FIXME: stub
(defn pass-edit [k]
  (fn [ev]))

(defn addable-list
  [k label & [opts]]
  [:div.flex.vcenter.mb1h
   [:span.basis-12 [:b label]]
   [:input (merge {:type :text
                   :placeholder "link to paper"
                   :on-change (pass-edit k)})]
   [:a.pl1 "[+]"]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Page Level
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn four-o-four []
  [:h2 "You're in a bad place."])

(defn title-bar []
  [:div.flex.space-between
   [:button
    {:on-click #(re-frame/dispatch [::events/toggle-edit])}
    [:span.ham "Ξ"]]
   [:div.ctext.grow-1.pl1.pr1 "open" [:b "mind"]]
   [:input.grow-2 {:type :text
                   :on-change (fn [e]
                                (let [v (-> e .-target .-value)]
                                  (re-frame/dispatch
                                   [::events/search v])))
                   :placeholder "specific term"}]])

(defn window [content]
  [:div.padded
   [title-bar]
   [:div.vspacer]
   [content]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Filter tag selector
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cancel-button [path]
  [:a.border-circle.plh.prh.bg-dull
   {:on-click (fn [e]
                (.stopPropagation e)
                (.preventDefault e)
                (re-frame/dispatch [::events/remove-filter-feature path])
                (re-frame/dispatch [::events/set-filter-edit path false]))}
   "remove"])

(defn nested-filter
  "Defines the view component for a filter that has subcategories that can be
  navigated."
  [{:keys [tag-name id children]} activity selected? path]
  (let [activity (get activity id)
        tag-lookup @(re-frame/subscribe [::subs/tag-lookup])
        cset (into #{} (comp (filter #(seq (:children %))) (map :id)) children)]
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
                                     (map #(get tag-lookup %))
                                )))")")]])]]))

(defn leaf-filter [{:keys [tag-name id]} activity path]
  (let [active? (contains? activity id)]
    [:button.feature-button.border-round.mb1
     {:class (when active? "active")
      :on-click #(re-frame/dispatch [(if active?
                                       ::events/remove-filter-feature
                                       ::events/add-filter-feature)
                                     path])}
     tag-name]))

(defn filter-button [tag activity selected? path]
  (if (seq (:children tag))
    [nested-filter tag activity selected? path]
    [leaf-filter tag activity path]))

(defn tag-child
  "Returns the child of the given tag which has the provided id. Returns nil if
  no such tag is found."
  [tag id]
  (first (filter (fn [x] (= (:id x) id)) (:children tag))))

(defn filter-view
  "The taxonomy of tags forms a tree, but from that tree, only one thread of
  nodes can be visible at a time in the interface. Given that display-path,
  recursively render the appropriate nodes."
  [tag active-filters display-path current-path]
  {:pre [(= (:id tag) (first display-path))]}
  (let [[current & tail] display-path
        next (first tail)
        active-filters (get active-filters (:id tag))]
    [:div.bc-dull.border-round.pl2.pr2.pb1.pt2
     (into [:div.flex.flex-wrap.space-evenly]
           (map (fn [{:keys [id] :as sub-tag}]
                  [filter-button
                   sub-tag
                   active-filters
                   (= id next)
                   (conj current-path id)]))
           (:children tag))
     (when next
       (let [child (tag-child tag next)]
         (filter-view child active-filters
                      tail (conj current-path next))))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Display
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hlink [text float-content]
  (let [hover? (reagent/atom false)]
    (fn [text float-content]
      [:div
       [:a.tag {:on-mouse-over #(reset! hover? true)
                :on-mouse-out  #(reset! hover? false)}
        text]
       (when @hover?
         [:div.absolute.absolute-c
          float-content])])))

(defn comments-link []
  [hlink "comments"])

(defn history-link []
  [hlink "history"])

(defn reference-link [ref]
  [hlink ref])

(defn format-tags [tags]
  ;; FIXME: flex isn't the right solution here.
  (into [:div.flex.flex-column.bg-white.p1.border-round]
        (comp (remove nil?)
              (map (fn [[k v]]
                     (when (and k (seq v))
                       [:div
                        (into
                         [:div.flex.space-between
                          [:span.basis-12 (str (name k) ":")]]
                         (interpose [:span.pl1.shrink-2 ","]
                                    (map (fn [x] [:span.pl1 (name x)]) v)))]))))
        tags))

(defn result [{:keys [text reference tags]}]
  [:div.search-result.padded
   [:div.extract.break-wrap text]
   [:div.vspacer
    [:div.flex.flex-wrap.space-evenly
     [comments-link (:comments tags)]
     [history-link]
     [hlink "related"]
     [hlink "details"]
     [hlink "tags" (format-tags tags)]
     [hlink "figure"]
     [reference-link reference]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::subs/extracts])]
    (into [:div]
          (map (fn [r] [result r]) results))))

(defn search-view []
  (let [current-search @(re-frame/subscribe [::subs/search])
        tag-tree @(re-frame/subscribe [::subs/tags])
        selection @(re-frame/subscribe [::subs/current-filter-edit])
        ;; HACK: Automatically select anaesthesia for now.
        selection (or selection [(:id tag-tree)])]
    [:div
     [filter-view tag-tree (:filters current-search) selection [(:id tag-tree)]]
     [search-results]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Editor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn editor-panel []
  [:div.flex.flex-column.flex-start.pl2.pr2
   [:h2 "create a new extract"]
   [text-box :extract "extract"
    {:placeholder "an insight or takeaway from the paper"}]
   [text-box :link "source article"
    {:placeholder "https://www.ncbi.nlm.nih.gov/pubmed/..."}]
   [text-box :figure "figure link"
    {:placeholder "link to a figure that demonstrates your point"}]
   [text-box :comments "comments"
    {:placeholder "anything you think is important"}]
   [addable-list :confirmed "confirmed by"]
   [addable-list :contrast "in contrast to"]
   [addable-list :related "related results"]
   [:h4.ctext "add filter tags"]
   [filter-view {}]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Entry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn main-view []
  (let [route @(re-frame/subscribe [::subs/route])]
    [window
     (cond
       (= route ::search) search-view
       (= route ::create) editor-panel
       :else              four-o-four)]))
