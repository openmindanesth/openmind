(ns openmind.views
  (:require
   [re-frame.core :as re-frame]
   [openmind.subs :as subs]))


(defn title-bar []
  [:div
   [:div.columns.five [:span "open" [:span.darker "mind"] ".org"]]
   [:div.columns.three "widgets go here"]
   [:div.columns.three [:input {:type :text
                                :placeholder "specific term"}]]])

(defn search []
  [:div "Search more"])

(defn results []
  [:div "Results!"])

(defn main-panel []
  [:div
   [:div.row [title-bar]]
   [:div.row [search]]
   [:div.row [results]]])

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

(defn editor-panel []
  [:div
   [:div.row [:h2 "Add a new extract"]]
   [:div.row [text-box :extract "Extract"]]
   [:div.row [text-box :author "Author"]]
   [:div.row [text-box :reference "Reference"]]])
