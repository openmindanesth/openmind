(ns openmind.components.extract
  (:require [openmind.components.extract.core :as core]
            [openmind.components.extract.editor :as editor]
            [re-frame.core :as re-frame]))

(defn figure-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [{:keys [figure]} @(re-frame/subscribe [:extract/content id])]
    [:img.p2 {:style {:max-width "95%"} :src figure}]))

(defn comments-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  )

(def routes
  (concat editor/routes
          [["/:id/figure"
            {:name :extract/figure
             :parameters {:path {:id any?}}
             :component  figure-page
             :controllers core/extract-controllers}]
           ["/:id/comments"
            {:name :extract/comments
             :parameters {:path {:id any?}}
             :component  comments-page
             :controllers core/extract-controllers}]]))
