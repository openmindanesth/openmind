(ns openmind.components.extract
  (:require [re-frame.core :as re-frame]))

(defn figure-full-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [{:keys [figure]} @(re-frame/subscribe [:extract/content id])]
    [:img.p2 {:style {:max-width "95%"} :src figure}]))

(def routes
  [["/:id/figure"
    {:name :extract/figure
     :parameters {:path {:id any?}}
     :component  figure-full-page
     :controllers
     [{:parameters {:path [:id]}
       :start      (fn [{{id :id} :path}]
                     (re-frame/dispatch [:extract/init id]))
       :stop       (fn [{{id :id} :path}]
                     (re-frame/dispatch [:extract/clear id]))}]}]])
