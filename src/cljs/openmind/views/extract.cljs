(ns openmind.views.extract
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [openmind.events :as events]
            [openmind.spec.extract :as exs]
            [openmind.subs :as subs]
            [openmind.views.tags :as tags]))

(defn pass-edit [ks]
  (fn [ev]
    (.log js/console ev)
    (re-frame/dispatch [::events/form-edit ks (-> ev .-target .-value)])))

(defn text-box
  [k label & [{:keys [placeholder class]}]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex.vcenter.mb1h
     [:span.basis-12 [:b label]]
     [:input.grow-4 (merge {:id        (name k)
                            :type      :text
                            :on-change (pass-edit [k])}
                           (cond
                             (seq content) {:value content}
                             placeholder   {:value       nil
                                            :placeholder placeholder})
                           (when class
                             {:class class}))]]))


(defn list-element [k i c]
  [:input (merge {:type :text
                  :on-change (pass-edit [k i])}
                 (if (seq c)
                   {:value c}
                   {:value nil
                    :placeholder "link to paper"}))])

(defn addable-list
  [k label & [opts]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex.vcenter.mb1h
     [:span.basis-12 [:b label]]
     (into [:div]
        (map (fn [[i c]]
               [list-element k i c]))
        content)
     [:a.plh {:on-click (fn [_]
                          (re-frame/dispatch
                           [::events/form-edit [k (count content)] ""]))}
      "[+]"]]))

(defn add-form-data [{:keys [key] :as elem}]
  (merge elem @(re-frame/subscribe [::subs/form-input-data key])))

(defmulti input-component :type)

(defmethod input-component :text
  [{:keys [label key required? placeholder spec error content]}]
  [:div.flex.vcenter.mb1h
   [:span.basis-12
    [:b label]
    (when required? [:span.text-red.super.small " *"])]
   [:input.grow-4 (merge {:id        (name key)
                          :type      :text
                          :on-change (pass-edit [key])}
                         (cond
                           (seq content) {:value content}
                           placeholder   {:value       nil
                                          :placeholder placeholder})
                         (when error
                           {:class "form-error"}))]])


#_(defmethod input-component :textarea
  [{:keys [label key required? placeholder spec error content]}]
  [:div.flex.vcenter.mb1h
   [:span.basis-12 [:b label] (when required? [:span.text-red.super.small " *"])]
   [:textarea (merge {:id        (name key)
                      :type      :text
                      :on-change (pass-edit [key])}
                     (cond
                       (seq content) {:value content}
                       placeholder   {:value       nil
                                      :placeholder placeholder})
                     (when error
                       {:class "form-error"}))]])

(defmethod input-component :default
  [_])

(def extract-creation-form
  [{:type        :textarea
    :label       "extract"
    :key         :text
    :required?   true
    :placeholder "an insight or takeaway from the paper"
    :spec        ::exs/text}
   {:type        :text
    :label       "source article"
    :key         :source
    :required?   true
    :placeholder "https://www.ncbi.nlm.nih.gov/pubmed/..."
    :spec        ::exs/source}
   {:type        :text-input-list
    :label       "drag and drop image, or enter url"
    :key         :figures
    :placeholder "link to a figure that demonstrates your point"
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
   {:type :tag-selector
    :label "add filter tags"
    :key :tags
    :spec ::exs/tags}])

(defn editor-panel []
  (into
   [:div.flex.flex-column.flex-start.pl2.pr2
    [:div.flex.pb1.space-between
     [:h2 "create a new extract"]
     [:button.bg-grey.border-round.wide
      {:on-click (fn [_]
                   (re-frame/dispatch [::events/create-extract]))}
      "CREATE"]]]
   (map input-component (map add-form-data extract-creation-form))
    ;; [text-box :text "extract"
    ;;  {:placeholder "an insight or takeaway from the paper"}]
    ;; [text-box :source "source article"
    ;;  {:placeholder "https://www.ncbi.nlm.nih.gov/pubmed/..."}]
    ;; [text-box :figure "figure link"
    ;;  {:placeholder "link to a figure that demonstrates your point"}]
    ;; [text-box :comments "comments"
    ;;  {:placeholder "anything you think is important"}]
    ;; [addable-list :confirmed "confirmed by"]
    ;; [addable-list :contrast "in contrast to"]
    ;; [addable-list :related "related results"]
    ;; [:h4.ctext "add filter tags"]
    ;; [tags/tag-selector]
    ))
