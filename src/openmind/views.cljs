(ns openmind.views
	(:require
	 [re-frame.core :as re-frame]
	 [openmind.subs :as subs]
	 ))


(defn title-bar []
	[:div "Title stuff"])

(defn search []
	[:div "Search stuff"])

(defn results []
	[:div "Results!"])

(defn main-panel []
	[:div
	 [:div.row [title-bar]]
	 [:div.row [search]]
	 [:div.row [results]]])
