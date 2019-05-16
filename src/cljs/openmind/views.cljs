(ns openmind.views
  (:require
   [re-frame.core :as re-frame]
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
  [:a {:on-click (constantly nil)} text])

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
    [:div.columns.nine
     [:div.row
      [:div.columns.two [comments-tag (:comments tags)]]
      [:div.columns.two [history-tag]]
      [:div.columns.two [tag "related"]]
      [:div.columns.two [tag "details"]]
      [:div.columns.two [tag "tags"]]
      [:div.columns.two [tag "figure"]]]]
    [:div.columns.three [reference-tag reference]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::subs/extracts])]
    (into [:div.row]
          (map result results))))
