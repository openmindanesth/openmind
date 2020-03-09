(ns openmind.components.extract
  (:require [clojure.edn :as edn]
            [openmind.components.extract.core :as core]
            [openmind.components.extract.editor :as editor]
            [re-frame.core :as re-frame]))

(defn figure-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [id                (edn/read-string id)
        {:keys [figures]} @(re-frame/subscribe [:extract/content id])]
    (if (seq figures)
      (into [:div]
            (map (fn [fid]
                   (let [{:keys [image-data caption]} @(re-frame/subscribe [:extract/content fid])]
                     [:div
                      [:img.p2 {:style {:max-width "95%"} :src image-data}]
                      [:p.pl1.pb2 caption]])))
            figures)
      [:span.p2 "This extract doesn't have an associated figure."])))

(defn comments-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [comments (:comments @(re-frame/subscribe [:extract/content id]))]
    (if (seq comments)
      (into
       [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh]
       (map (fn [com]
              [:div.break-wrap.ph.border-round.border-solid.border-grey.mbh
               com]))
       comments)
      [:span.p2 "No one has commented on this extract yet."])))

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
