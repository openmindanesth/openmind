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
    [:div.columns.four [:span "open" [:span.darker "mind"] ".org"]]
    [:div.columns.two [:button "Login"]]
    [:div.columns.two "widgets go here"]
    [:div.columns.three [:input {:type :text
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

(defn result [{:keys [text reference tags]}]
  [:div.row.search-result.padded
   [:div.row.extract text]
   [:div.row.vspacer
    [:div.flex-container
     [comments-tag (:comments tags)]
     [history-tag]
     [tag "related"]
     [tag "details"]
     [tag "tags"]
     [tag "figure"]
     [reference-tag reference]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::subs/extracts])]
    (into [:div.row]
          (map result results))))

(defn feature [feat [value display] selected?]
  [:button.filter-button
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

(defn filter-button [n sel]
  [:button.blue.filter-button
   {:on-click (fn [_]
                (re-frame/dispatch [::events/set-filter-edit (when-not (= n sel)
                                                               n)]))
    :class (when (= n sel) "selected")}
   (name n)])

(defn display-filters [fs]
  (let [selection @(re-frame/subscribe [::subs/current-filter-edit])]
    [:div
     [:div.row
      (into [:div.flex-container]
            (map (fn [[k _]] [filter-button k selection]))
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
