(ns openmind.views
  (:require
   [reagent.core :as reagent]
   [re-frame.core :as re-frame]
   [openmind.events :as events]
   [openmind.search :as search]
   [openmind.subs :as subs]))

;; TODO: Look into tailwind

(defn pass-off [k]
  (fn [ev]
    (re-frame/dispatch [::events/form-data k (-> ev .-target .-value)])))

(defn text-box
  [k label & [{:keys [placeholder class]}]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex
     [:label.basis-12 {:for (name k)} label]
     [:input.grow-4 (merge {:id        (name k)
                            :type      :text
                            :on-change (pass-off k)}
                           (cond
                             content     {:value content}
                             placeholder {:placeholder placeholder})
                           (when class
                             {:class class}))]]))

(defn addable-list
  [k label & [opts]]
  )

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

(defn feature [feat [value display] selected?]
  [:button.feature-button
   {:class (when selected? "active")
    :on-click #(re-frame/dispatch [(if selected?
                                     ::events/remove-filter-feature
                                     ::events/add-filter-feature)
                                   feat value])}
   display])

(defn filter-chooser [sel current]
  (into [:div.flex.flex-wrap.space-evenly.filter-choose]
        (map (fn [feat] [feature sel feat (contains? current (key feat))]))
        (get search/filters sel)))

(defn filter-button [n v sel]
  [:button.blue.filter-button
   {:on-click (fn [_]
                (re-frame/dispatch [::events/set-filter-edit (when-not (= n sel)
                                                               n)]))
    :class    (when (= n sel) "selected")}
   [:span (name n)
    (when (seq v)
      [:span
       [:br]
       (str "(" (apply str (interpose " or " (map name v))) ")")])]])

(defn display-filters [fs]
  (let [selection @(re-frame/subscribe [::subs/current-filter-edit])]
    [:div
     (into [:div.flex.flex-wrap.space-evenly]
           (map (fn [[k v]] [filter-button k (get fs k) selection]))
           search/filters)
     (when selection
       [filter-chooser selection (get fs selection)])]))

(defn filter-view [fs]
  [:div.filter-set
   [display-filters fs]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hlink [text]
  [:a.tag {:on-click (constantly nil)} text])

(defn comments-link []
  [hlink "comments"])

(defn history-link []
  [hlink "history"])

(defn reference-link [ref]
  [hlink ref])

(defn format-tags [tags]
  (apply str
         (interpose ", \n"
                    (map (fn [[k v]]
                           (when k
                             (str (name k) "=["
                                  (apply str (interpose ", " (map name v)))
                                  "]")))
                         tags))))

(defn result [{:keys [text reference tags]}]
  [:div.search-result.padded
   [:div.extract.break-wrap text]
   [:div.vspacer
    [:div.flex.flex-wrap.space-evenly
     [comments-link (:comments tags)]
     [history-link]
     [hlink "related"]
     [hlink "details"]
     [:a.tag.tooltip {:data-tooltip (format-tags tags)} "tags"]
     [hlink "figure"]
     [reference-link reference]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::subs/extracts])]
    (into [:div.row]
          (map (fn [r] [result r]) results))))

(defn search-view []
  (let [current-search @(re-frame/subscribe [::subs/search])]
    [:div
     [filter-view (:filters current-search)]
     [search-results]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Editor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn editor-panel []
  [:div.flex.flex-column.flex-start.pl2.pr2
   [:h2 "new extract"]
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
