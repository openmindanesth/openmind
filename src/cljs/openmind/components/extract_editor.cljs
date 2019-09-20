(ns openmind.components.extract-editor
  (:require [cljs.spec.alpha :as s]
            [openmind.spec.extract :as exs]
            [openmind.components.tags :as tags]
            [reagent.core :as r]
            [re-frame.core :as re-frame]))

;;;;; Subs

(re-frame/reg-sub
 ::new-extract
 (fn [db]
   (::new-extract db)))

(re-frame/reg-sub
 ::new-extract-content
 :<- [::new-extract]
 (fn [extract _]
   (::content extract)))

(re-frame/reg-sub
 ::new-extract-form-errors
 :<- [::new-extract]
 (fn [extract _]
   (:errors extract)))

(re-frame/reg-sub
 ::form-input-data
 :<- [::new-extract-content]
 :<- [::new-extract-form-errors]
 (fn [[content errors] [_ k]]
   {:content (get content k)
    :errors  (get errors k)}))

;; tags

(re-frame/reg-sub
 ::editor-tag-view-selection
 :<- [::new-extract]
 (fn [extract _]
   (:selection extract)))

(re-frame/reg-sub
 ::editor-selected-tags
 :<- [::new-extract-content]
 (fn [content _]
   (into #{} (map :id (:tags content)))))

(re-frame/reg-event-db
 ::set-editor-selection
 (fn [db [_ path add?]]
   ;; REVIEW: These handlers should be part of the extract editor logical group,
   ;; and the event / sub names should be passed into the tags component.
   (assoc-in db [::extracts ::new :selection]
             (if add?
               path
               (vec (butlast path))))))

(re-frame/reg-event-db
 ::add-editor-tag
 (fn [db [_ tag]]
   (update-in db [::extracts ::new :content :tags] conj tag)))

(re-frame/reg-event-db
 ::remove-editor-tag
 (fn [db [_ & tags]]
   (update-in db [:extracts ::new :content :tags] #(reduce disj % tags))))

;;;;; Events

(re-frame/reg-event-db
 ::form-edit
 (fn [db [_ k v]]
   (assoc-in db (concat [::extracts ::new :content] k) v)))

(re-frame/reg-event-fx
 ::create-extract
 (fn [cofx _]
   (let [author  @(re-frame/subscribe [:openmind.subs/login-info])
         extract (-> cofx
                     (get-in [:db ::extracts ::new :content])
                     (assoc :author author
                            :created-time (js/Date.))
                     (update :tags #(mapv :id %)))]

     (if (s/valid? ::exs/extract extract)
       {:dispatch [::try-send [:openmind/index extract]]}
       {:db (assoc-in (:db cofx) [::new-extract :errors]
                      (exs/interpret-explanation
                       (s/explain-data ::exs/extract extract)))}))))


(defn success? [status]
  (<= 200 status 299))

(def blank-new-extract
  {:new-extract/selection []
   ::content   {:tags      #{}
                           :comments  {0 ""}
                           :related   {0 ""}
                           :contrast  {0 ""}
                           :confirmed {0 ""}
                           :figures   {0 ""}}
   :errors                nil})

(re-frame/reg-event-db
 ::clear-extract
 (fn [db [_ id]]
   (update db ::extracts dissoc id)))

(re-frame/reg-event-db
 ::init-new-extract
 (fn [db]
   (update-in db [::extracts ::new] #(if (nil? %)
                                       blank-new-extract
                                       %))))

(re-frame/reg-event-fx
 :openmind/index-result
 (fn [{:keys [db]} [_ status]]
   (if (success? status)
     {:db (assoc db
                 ::new-extract blank-new-extract
                 ;; TODO: This should be an event
                 :openmind.db/status-message
                 {:status  :success
                  :message "Extract Successfully Created!"})

      :dispatch-later [{:ms       2000
                        :dispatch [:openmind.events/clear-status-message]}
                       {:ms       500
                        :dispatch [:openmind.router/navigate
                                   {:route :search}]}]}
     {:db (assoc db :status-message
                 ;; FIXME: So should this
                 {:status :error :message "Failed to create extract."})})))

(re-frame/reg-event-db
 ::clear-status-message
 (fn [db]
   (dissoc db :status-message)))
(defn pass-edit [ks]
  (fn [ev]
    (re-frame/dispatch [::form-edit ks (-> ev .-target .-value)])))

;;;; Components

(defn add-form-data [{:keys [key] :as elem}]
  (merge elem @(re-frame/subscribe [::form-input-data key])))

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
                             [::form-edit [key (count content)] ""]))}
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
  [tags/tag-widget {:selection {:read ::editor-tag-view-selection
                                :set  ::set-editor-selection}
                    :edit      {:read ::editor-selected-tags
                                :add ::add-editor-tag
                                :remove ::remove-editor-tag}}])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn drop-upload
  "Extracts the dropped image from the drop event and adds it to the app state."
  ;;FIXME: Is there some sort of standard regarding the dragging of images from
  ;;a browser? How can I be sure the first item will always contain a URL?
  [k e]
  (let [item (-> e .-dataTransfer .-items (aget 0))]
    (if-let [file (.getAsFile item)]
      (re-frame/dispatch [::form-edit [k] {:type :file :value file}])
      (.getAsString item #(re-frame/dispatch
                           [::form-edit [k] {:type :url :value %}])))))


(defn select-upload [k e]
  (let [f (-> e .-target .-files (aget 0))]
    (re-frame/dispatch [::form-edit [k] {:type :file :value f}])))

(defmethod input-component :image-drop
  [opts]
  (let [id          (str (gensym))
        drag-hover? (r/atom false)]
    (fn [{:keys [key placeholder content]}]
      (let [drop-state {:style         {:border    :dashed
                                        :cursor    :pointer
                                        :max-width "250px"}
                        :class         (if @drag-hover?
                                         :border-blue
                                         :border-grey)
                        :for           id
                        :on-drag-enter (juxt halt #(reset! drag-hover? true))
                        :on-drag-over  (juxt halt #(reset! drag-hover? true))
                        :on-drag-leave (juxt halt #(reset! drag-hover? false))
                        :on-drop       (juxt halt #(reset! drag-hover? false)
                                             (partial drop-upload key))}]
        [:div.mt1.mb2
         (if content
           [:img.border-round.p1
            (merge drop-state
                   {:src   (if (= :file (:type content))
                             (js/URL.createObjectURL (:value content))
                             (:value content))
                    :on-click #(.click (.getElementById js/document id))})]
           [:label.p2.border-round drop-state placeholder])
         [:input {:type      :file
                  :id        id
                  :style     {:visibility :hidden}
                  :accept    "image/png,image/gif,image/jpeg"
                  :on-change (partial select-upload key)}]]))))

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
                   (re-frame/dispatch [::create-extract]))}
      "CREATE"]]]
   (map input-row (map add-form-data extract-creation-form))))

(def routes
  [["/new" {:name      :extract/create
            :component editor-panel}]])
