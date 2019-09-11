(ns openmind.views.extract
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [openmind.events :as events]
            [openmind.spec.extract :as exs]
            [openmind.subs :as subs]
            [openmind.views.tags :as tags]))

(defn pass-edit [ks]
  (fn [ev]
    (re-frame/dispatch [::events/form-edit ks (-> ev .-target .-value)])))

(defn add-form-data [{:keys [key] :as elem}]
  (merge elem @(re-frame/subscribe [::subs/form-input-data key])))

(defmulti input-component :type)

(defmethod input-component :text
  [{:keys [label key required? placeholder spec errors content]}]
  [:input (merge {:id        (name key)
                  :type      :text
                  :style     {:width      "100%"
                              :box-sizing "border-box"}
                  :on-change (pass-edit [key])}
                 (cond
                   (seq content) {:value content}
                   placeholder   {:value       nil
                                  :placeholder placeholder})
                 (when errors
                   {:class "form-error"}))])


(defmethod input-component :textarea
  [{:keys [label key required? placeholder spec errors content]}]
  [:textarea.full-width-textarea
   (merge {:id        (name key)
           :rows      2
           :type      :text
           :on-change (pass-edit [key])}
          (cond
            (seq content) {:value content}
            placeholder   {:value       nil
                           :placeholder placeholder})
          (when errors
            {:class "form-error"}))])

(defmethod input-component :text-input-list
  [{:keys [key placeholder spec errors content]}]
  (conj
   (into [:div.flex.flex-wrap]
         (map (fn [[i c]]
                [:input (merge {:type      :text
                                :on-change (pass-edit [key i])}
                               (when (get errors i)
                                 {:class "form-error"})
                               (if (seq c)
                                 {:value c}
                                 {:value       nil
                                  :placeholder placeholder}))]))
         content)
   [:a.plh.ptp {:on-click (fn [_]
                            (re-frame/dispatch
                             [::events/form-edit [key (count content)] ""]))}
    "[+]"]))

(defmethod input-component :textarea-list
  [{:keys [key placeholder spec errors content]}]
  [:div
   (into [:div]
         (map (fn [[i c]]
                [:textarea.full-width-textarea
                 (merge {:id        (name key)
                         :rows      2
                         :type      :text
                         :on-change (pass-edit [key])}
                        (cond
                          (seq content) {:value c}
                          placeholder   {:value       nil
                                         :placeholder placeholder})
                        (when (get errors i)
                          {:class "form-error"}))]))
         content)
   [:a.bottom-right {:on-click
                     (fn [_]
                       (re-frame/dispatch
                        [::events/form-edit [key (count content)] ""]))}
    "[+]"]])

(defmethod input-component :tag-selector
  [{:keys [label]}]
  [tags/tag-selector])

(defn responsive-two-column [l r]
  [:div.vcenter.mb1h.mbr2
   [:div.left-col l]
   [:div.right-col r]])

(defn input-row
  [{:keys [label required? full-width?] :as com}]
  (let [label-span [:span [:b label] (when required?
                                       [:span.text-red.super.small " *"])]]
    (if full-width?
      [:div
       [:h4.ctext label-span]
       (input-component com)]
      [responsive-two-column
       label-span
       (input-component com)])))

(def extract-creation-form
  [{:type        :textarea
    :label       "extract"
    :key         :text
    :required?   true
    :placeholder "an insight or takeaway from the paper"
    :spec        ::exs/text
    :error-message
    "extracts must be between 1 and 500 characters. If you need to elaborate,
    use comments."}
   {:type        :text
    :label       "source article"
    :key         :source
    :required?   true
    :placeholder "https://www.ncbi.nlm.nih.gov/pubmed/..."
    :spec        ::exs/source
    :error-message "you must reference a source article."}
   {:type        :text-input-list
    :label       "figures"
    :key         :figures
    :placeholder "link to a figure"
    :spec        ::exs/image}
   {:type        :textarea-list
    :label       "comments"
    :key         :comments
    :placeholder "anything you think is important"
    :spec        ::exs/comment}
   {:type        :text-input-list
    :label       "confirmed by"
    :key         :confirmed
    :placeholder "link to paper"
    :spec        ::exs/reference}
   {:type        :text-input-list
    :label       "in contrast to"
    :key         :contrast
    :placeholder "link to paper"
    :spec        ::exs/reference}
   {:type        :text-input-list
    :label       "related results"
    :key         :related
    :placeholder "link to paper"
    :spec        ::exs/reference}
   {:type        :tag-selector
    :label       "add filter tags"
    :key         :tags
    :full-width? true
    :spec        ::exs/tags}])

(defn editor-panel []
  (into
   [:div.flex.flex-column.flex-start.pl2.pr2
    [:div.flex.pb1.space-between
     [:h2 "create a new extract"]
     [:button.bg-grey.border-round.wide
      {:on-click (fn [_]
                   (re-frame/dispatch [::events/create-extract]))}
      "CREATE"]]]
   (map input-row (map add-form-data extract-creation-form))))
