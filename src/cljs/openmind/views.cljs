(ns openmind.views
  (:require
   [reagent.core :as reagent]
   [re-frame.core :as re-frame]
   [openmind.events :as events]
   [openmind.search :as search]
   [openmind.subs :as subs]))

(defn pass-off [k]
  (fn [ev]
    (re-frame/dispatch [k (-> ev .-target .-value)])))

(defn text-box
  [k label & [{:keys [placeholder class]}]]
  [:div.row
   [:div.columns.one]
   (when label
     [:div.columns.two [:label {:for (name k)} label]])
   [:div.columns.seven [:input.u-full-width {:id        (name k)
                                             :type      :text
                                             :class     class
                                             :on-change (pass-off k)}]]])



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Shared
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn four-o-four []
  [:h2 "You're in a bad place."])

(defn title-bar []
  [:div
   [:div.row
    [:div.columns.one
     [:button.narrow
      {:on-click #(re-frame/dispatch [::events/toggle-edit])}
      [:span.ham "Ξ"]]]
    [:div.columns.one [:span "open" [:span.darker "mind"]]]
    [:div.columns.two [:button "Login"]]
    [:div.columns.four [:b "N.B.: if it isn't obvious, the data below is garbage. For test purposes only."]]
    [:div.columns.three [:input {:type :text
                                 :on-change (fn [e]
                                              (let [v (-> e .-target .-value)]
                                                (re-frame/dispatch
                                                 [::events/search v])))
                                 :placeholder "specific term"}]]]])
(defn window [content]
  [:div.padded
   [title-bar]
   [content]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Editor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn editor-panel []
  [:div
   [:div.row [:h2 "Add a new extract"]]
   [:div.row [text-box :extract "Extract"]]
   [:div.row [text-box :author "Author"]]
   [:div.row [text-box :reference "Reference"]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tag [text]
  [:a.tag {:on-click (constantly nil)} text])

(defn comments-tag []
  [tag "comments"])

(defn history-tag []
  [tag "history"])

(defn reference-tag [ref]
  [tag ref])

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
  [:div.row.search-result.padded
   [:div.row.extract text]
   [:div.row.vspacer
    [:div.flex-container
     [comments-tag (:comments tags)]
     [history-tag]
     [tag "related"]
     [tag "details"]
     [:a.tag.tooltip {:data-tooltip (format-tags tags)} "tags"]
     [tag "figure"]
     [reference-tag reference]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::subs/extracts])]
    (into [:div.row]
          (map (fn [r] [result r]) results))))

(defn feature [feat [value display] selected?]
  [:button.feature-button
   {:class (when selected? "active")
    :on-click #(re-frame/dispatch [(if selected?
                                     ::events/remove-filter-feature
                                     ::events/add-filter-feature)
                                   feat value])}
   display])

(defn filter-chooser [sel current]
  (into [:div.flex-container.filter-choose]
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
       (str "(" (apply str (interpose ", " (map name v))) ")")])]])

(defn display-filters [fs]
  (let [selection @(re-frame/subscribe [::subs/current-filter-edit])]
    [:div
     [:div.row
      (into [:div.flex-container]
            (map (fn [[k v]] [filter-button k (get fs k) selection]))
            search/filters)]
     (when selection
       [filter-chooser selection (get fs selection)])]))

(defn filter-view [fs]
  [:div.row.filter-set
   [display-filters fs]])

(defn search-view []
  (let [current-search @(re-frame/subscribe [::subs/search])]
    [:div.row
     [:div [filter-view (:filters current-search)]]
     [search-results]]))

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
