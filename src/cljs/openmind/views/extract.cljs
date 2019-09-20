(ns openmind.views.extract
  (:require [openmind.events :as events]
            [openmind.spec.extract :as exs]
            [openmind.subs :as subs]
            [openmind.views.tags :as tags]
            [re-frame.core :as re-frame]))

(defn pass-edit [ks]
  (fn [ev]
    (re-frame/dispatch [::events/form-edit ks (-> ev .-target .-value)])))

(defn add-form-data [{:keys [key] :as elem}]
  (merge elem @(re-frame/subscribe [::subs/form-input-data key])))

(defn error [text]
  [:p.text-red.small.pl1.mth.mb0 text])

(defmulti input-component :type)

(defmethod input-component :text
  [{:keys [label key required? placeholder spec errors content]}]
  [:div
   [:input.full-width-textarea
    (merge {:id        (name key)
            :type      :text
            :on-change (pass-edit [key])}
           (cond
             (seq content) {:value content}
             placeholder   {:value       nil
                            :placeholder placeholder})
           (when errors
             {:class "form-error"}))]
   (when errors
     [error errors])])

(defmethod input-component :textarea
  [{:keys [label key required? placeholder spec errors content]}]
  [:div
   [:textarea.full-width-textarea
    (merge {:id        (name key)
            :rows      2
            :style     {:resize :vertical}
            :type      :text
            :on-change (pass-edit [key])}
           (cond
             (seq content) {:value content}
             placeholder   {:value       nil
                            :placeholder placeholder})
           (when errors
             {:class "form-error"}))]
   (when errors
     [error errors])])

(defmethod input-component :text-input-list
  [{:keys [key placeholder spec errors content]}]
  (conj
   (into [:div.flex.flex-wrap]
         (map (fn [[i c]]
                (let [err (get errors i)]
                  [:div
                   [:input.full-width-textarea (merge {:type      :text
                                   :on-change (pass-edit [key i])}
                                  (when err
                                    {:class "form-error"})
                                  (if (seq c)
                                    {:value c}
                                    {:value       nil
                                     :placeholder placeholder}))]
                   (when err
                     [:div.mbh
                      [error err]])])))
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
                (let [err (get errors i)]
                  [:div
                   [:textarea.full-width-textarea
                    (merge {:id        (name (str key i))
                            :style     {:resize :vertical}
                            :rows      2
                            :type      :text
                            :on-change (pass-edit [key i])}
                           (cond
                             (seq content) {:value c}
                             placeholder   {:value       nil
                                            :placeholder placeholder})
                           (when err
                             {:class "form-error"}))]
                   (when err
                     [:div.mbh
                      [error err]])]))
              content))
   [:a.bottom-right {:on-click
                     (fn [_]
                       (re-frame/dispatch
                        [::form-edit [key (count content)] ""]))}
    "[+]"]])

(defmethod input-component :tag-selector
  [opts]
  [tags/tag-selector])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn drop-upload [k e]
  (.log js/console (.-target e))
  (let [file (-> e .-dataTransfer .-files (aget 0))]
    (.log js/console (-> e .-dataTransfer .-items (aget 0)
                         (.getAsFile js/console.log)
                        ))))

(defn select-upload [k e]
  (let [f (-> e .-target .-files (aget 0))]
    (re-frame/dispatch [::form-edit k f]))
  )

(defmethod input-component :image-drop
  [opts]
  (let [id          (str (gensym))
        drag-hover? (r/atom false)]
    (fn [{:keys [key placeholder]}]
      [:div.mt1.mb2
       [:label.p2.border-round
        {:style         {:border     :dashed
                         :cursor     :pointer
                         :min-height "250px"
                         :max-width  "250px"}
         :class         (if @drag-hover?
                          :border-blue
                          :border-grey)
         :for           id
         :on-drag-enter (juxt halt #(reset! drag-hover? true))
         :on-drag-over  (juxt halt #(reset! drag-hover? true))
         :on-drag-leave (juxt halt #(reset! drag-hover? false))
         :on-drop       (juxt halt #(reset! drag-hover? false)
                              (partial drop-upload key))}
        placeholder]
       [:input {:type      :file
                :id        id
                :style     {:visibility :hidden}
                :accept    "image/png,image/gif,image/jpeg"
                :on-change (partial select-upload key)}
        ]])))

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
       [input-component com]]
      [responsive-two-column
       label-span
       [input-component com]])))

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
   {:type        :image-drop
    :label       "figure"
    :key         :figure
    :placeholder [:span [:b "choose a file"] " or drag it here"]
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

(def routes
  [["/new" {:name      ::new
            :component editor-panel}]])
