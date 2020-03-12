(ns openmind.components.extract
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [openmind.components.extract.core :as core]
            [openmind.components.extract.editor :as editor]
            [openmind.util :as util]
            [re-frame.core :as re-frame]))

(defn figure-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [id                (edn/read-string id)
        {:keys [figures]} @(re-frame/subscribe [:extract/content id])]
    (if (seq figures)
      (into [:div]
            (map (fn [fid]
                   (let [{:keys [image-data caption]}
                         @(re-frame/subscribe [:extract/content fid])]
                     [:div
                      [:img.p2 {:style {:max-width "95%"} :src image-data}]
                      [:p.pl1.pb2 caption]])))
            figures)
      [:span.p2 "This extract doesn't have an associated figure."])))

;;;;; Comments

(defn- get-comment [db id]
  (get-in db [::new-comments id]))

(re-frame/reg-sub
 ::new-comment
 (fn [db [_ id]]
   (get-comment db id)))

(re-frame/reg-event-db
 ::new-comment
 (fn [db [_ id text]]
   (assoc-in db [::new-comments id :text] text)))

(re-frame/reg-event-fx
 ::save-new-comment
 (fn [{:keys [db]} [_ id]]
   (let [{:keys [text]} (get-comment db id)]
     (if (empty? text)
       {:db (assoc-in db [::new-comments id :errors] "Comments can't be blank.")}
       (let [author  (:login-info db)
             comment (util/immutable {:text text :author author :refers-to id})]
         ;; TODO: Clear comment entry area on successful intern
         {:dispatch [:openmind.events/try-send [:openmind/intern comment]]})))))

(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   (let [{:keys [text]} (get-comment db id)]
     (when (seq text)
       {:db (assoc-in db [::new-comments id :errors] nil)}))))

(defn new-comment [id]
  (let [{:keys [text errors]} @(re-frame/subscribe [::new-comment id])]
    [:div.flex.full-width
     [:div.full-width.pr1
      [:textarea.full-width-textarea
       (merge
        {:type :text
         :rows 2
         :style {:resize :vertical}
         :on-change (fn [ev]
                      (let [v (-> ev .-target .-value)]
                        (when errors
                          (re-frame/dispatch [::revalidate id]))
                        (re-frame/dispatch [::new-comment id v])))}
        (when text
          {:value text})
        (when errors
          {:class "form-error"}))]
      (when errors
        [editor/error errors])]
     [:button.bg-dark-grey.border-round.wide.text-white.p1
      {:style {:max-height "4rem"
               :right 0}
       :on-click (fn [_]
                   (re-frame/dispatch [::save-new-comment id]))}
      "COMMENT"]]))

(defn comments-page
  [{{:keys [id]} :path-params}]
  (let [comments (:comments @(re-frame/subscribe [:extract/content id]))]
    [:div.flex.flex-column
     [new-comment (edn/read-string id)]
     (if (seq comments)
       (into
        [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh]
        (map (fn [com]
               [:div.break-wrap.ph.border-round.border-solid.border-grey.mbh
                com]))
        comments)
       [:span.p2 "No one has commented on this extract yet."])]))

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
