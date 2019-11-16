(ns openmind.components.extract.editor
  (:require [cljs.spec.alpha :as s]
            [openmind.components.tags :as tags]
            [openmind.components.extract.core :as core]
            [openmind.spec.extract :as exs]
            [re-frame.core :as re-frame]
            [reagent.core :as r]
            [taoensso.timbre :as log]))

(defn validate-extract
  "Checks form data for extract creation/update against the spec."
  [db id]
  (let [author  @(re-frame/subscribe [:openmind.subs/login-info])
         extract (-> db
                     (get-in [::core/extracts id :content])
                     (assoc :author author
                            :created-time (js/Date.)))]
     (if (s/valid? ::exs/extract extract)
       {:valid extract}
       (let [err (s/explain-data ::exs/extract extract)]
         (log/warn "Bad extract" id err)
         {:errors (exs/interpret-explanation err)}))))

(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   {:dispatch [::form-errors (:errors (validate-extract db id)) id]}))

;;;;; Subs

(re-frame/reg-sub
 ::extract-form-errors
 (fn [[_ k]]
   (re-frame/subscribe [:extract k]))
 (fn [extract _]
   (:errors extract)))

(re-frame/reg-sub
 ::form-input-data
 (fn [[_ dk k] _]
   [(re-frame/subscribe [:extract/content dk])
    (re-frame/subscribe [::extract-form-errors dk])])
 (fn [[content errors] [_ dk k]]
   {:content (get content k)
    :errors  (get errors k)}))

;; tags

(re-frame/reg-sub
 ::editor-tag-view-selection
 (fn [[_ k]]
   (re-frame/subscribe [:extract k]))
 (fn [extract _]
   (:selection extract)))

(re-frame/reg-sub
 ::editor-selected-tags
 (fn [[_ k]]
   (re-frame/subscribe [:extract/content k]))
 (fn [content _]
   (:tags content)))

(re-frame/reg-event-db
 ::set-editor-selection
 (fn [db [_ id path add?]]
   (assoc-in db [::core/extracts id :selection]
             (if add?
               path
               (vec (butlast path))))))

(re-frame/reg-event-db
 ::add-editor-tag
 (fn [db [_ id tag]]
   (update-in db [::core/extracts id :content :tags] conj (:id tag))))

(re-frame/reg-event-db
 ::remove-editor-tag
 (fn [db [_ id & tags]]
   (update-in db [::core/extracts id :content :tags]
              #(reduce disj % (map :id tags)))))

;;;;; Events

(re-frame/reg-event-db
 ::form-edit
 (fn [db [_ id k v]]
   (assoc-in db (concat [::core/extracts id :content] k) v)))

(re-frame/reg-event-fx
 ::load-figure
 (fn [cofx [_ id extract]]
   (println id)
   (let [event (if (= ::new id) :openmind/index :openmind/update)
         figure (:figure extract)]
     (if (or (empty? figure) (string? figure))
       {:dispatch [:openmind.events/try-send [event extract]]}
       (let [reader (js/FileReader.)]
         (set! (.-onload reader)
               (fn [e]
                 (let [img (->> e
                                .-target
                                .-result)]
                   (re-frame/dispatch [:openmind.events/try-send
                                       [event (assoc extract :figure img)]]))))
         (.readAsDataURL reader figure))))))

(re-frame/reg-event-db
 ::form-errors
 (fn [db [_ errors id]]
   (assoc-in db [::core/extracts id :errors] errors)))

(re-frame/reg-event-fx
 ::update-extract
 (fn [{:keys [db]} [_ id]]
   (let [{:keys [valid errors]} (validate-extract db id)]
     (if errors
       {:dispatch [::form-errors errors id]}
       {:dispatch-n [[::form-errors nil id]
                     [::load-figure id valid]]}))))

(defn success? [status]
  (<= 200 status 299))

(re-frame/reg-event-fx
 :openmind/index-result
 (fn [_ [_ status]]
   (if (success? status)
     {:dispatch-n [[:extract/clear ::new]
                   [:notify {:status  :success
                             :message "Extract Successfully Created!"}]
                   [:navigate {:route :search}]]}
     ;;TODO: Fix notification bar.
     {:dispatch [:notify {:status :error
                          :message "Failed to create extract."}]})))

(re-frame/reg-event-fx
 :openmind/update-response
 (fn [cofx [_ status]]
   (if (success? status)
     {:dispatch-n [[:notify {:status :success
                             :message "changes saved successfully"}]
                   [:navigate {:route :search}]]}
     {:dispatch [:notify {:status :error :message "failed to save changes."}]})))

(re-frame/reg-event-db
 ::clear-status-message
 (fn [db]
   (dissoc db :status-message)))

(defn pass-edit [id ks]
  (fn [ev]
    (re-frame/dispatch [::form-edit id ks (-> ev .-target .-value)])))

;;;; Components

(defn add-form-data [id {:keys [key] :as elem}]
  (-> elem
      (assoc :data-key id)
      (merge @(re-frame/subscribe [::form-input-data id key]))))

(defn error [text]
  [:p.text-red.small.pl1.mth.mb0 text])

(defn text
  [{:keys [label key required? placeholder spec errors content data-key]}]
  [:div.full-width
   [:input.full-width-textarea
    (merge {:id        (name key)
            :type      :text
            :on-change (juxt (pass-edit data-key [key])
                             #(when errors
                                (re-frame/dispatch [::revalidate data-key])))}
           (cond
             (seq content) {:value content}
             placeholder   {:value       nil
                            :placeholder placeholder})
           (when errors
             {:class "form-error"}))]
   (when errors
     [error errors])])

(defn textarea
  [{:keys [label key required? placeholder spec errors content data-key]}]
  [:div
   [:textarea.full-width-textarea
    (merge {:id        (name key)
            :rows      2
            :style     {:resize :vertical}
            :type      :text
            :on-change (juxt (pass-edit data-key [key])
                             #(when errors
                                (re-frame/dispatch [::revalidate data-key])))}
           (cond
             (seq content) {:value content}
             placeholder   {:value       nil
                            :placeholder placeholder})
           (when errors
             {:class "form-error"}))]
   (when errors
     [error errors])])

(defn text-input-list
  [{:keys [key placeholder spec errors content data-key]}]
  (conj
   (into [:div.flex.flex-wrap]
         (map-indexed
          (fn [i c]
            (let [err (get errors i)]
              [:div
               [:input.full-width-textarea
                (merge {:type      :text
                        :on-change (pass-edit data-key [key i])}
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
                             [::form-edit data-key [key (count content)] ""]))}
    "[+]"]))

(defn textarea-list
  [{:keys [key placeholder spec errors content data-key] :as e}]
  [:div
   (into [:div]
         (map-indexed
          (fn [i c]
            (let [err (get errors i)]
              [:div
               [:textarea.full-width-textarea
                (merge {:id        (name (str key i))
                        :style     {:resize :vertical}
                        :rows      2
                        :type      :text
                        :on-change (pass-edit data-key [key i])}
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
                        [::form-edit data-key [key (count content)] ""]))}
    "[+]"]])

(defn tag-selector
  [{id :data-key}]
  [tags/tag-widget {:selection {:read [::editor-tag-view-selection id]
                                :set  [::set-editor-selection id]}
                    :edit      {:read   [::editor-selected-tags id]
                                :add    [::add-editor-tag id]
                                :remove [::remove-editor-tag id]}}])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn drop-upload
  "Extracts the dropped image from the drop event and adds it to the app state."
  ;;FIXME: Is there some sort of standard regarding the dragging of images from
  ;;a browser? How can I be sure the first item will always contain a URL?
  [dk k e]
  (let [item (-> e .-dataTransfer .-items (aget 0))]
    (if-let [file (.getAsFile item)]
      (re-frame/dispatch [::form-edit dk [k] file])
      (.getAsString item #(re-frame/dispatch
                           [::form-edit dk [k] %])))))


(defn select-upload [dk k e]
  (let [f (-> e .-target .-files (aget 0))]
    (re-frame/dispatch [::form-edit dk [k] f])))

(defn image-drop
  [opts]
  (let [id          (str (gensym))
        drag-hover? (r/atom false)]
    (fn [{:keys [key placeholder content data-key]}]
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
                                             (partial drop-upload
                                                      data-key key))}]
        [:div.mt1.mb2
         (if content
           [:img.border-round.p1
            (merge drop-state
                   {:src   (if (string? content)
                             content
                             (js/URL.createObjectURL content))
                    :on-click #(.click (.getElementById js/document id))})]
           [:label.p2.border-round drop-state placeholder])
         [:input {:type      :file
                  :id        id
                  :style     {:visibility :hidden}
                  :accept    "image/png,image/gif,image/jpeg"
                  :on-change (partial select-upload data-key key)}]]))))

(defn source-selector [{:keys [key content data-key errors] :as opts}]
  ;; For lab notes we want to get the PI, institution (corp), and date of
  ;; observation.
  ;; For article extracts, we can autofill from pubmed, but if that doesn't
  ;; work, we want the title, author list, publication, and date.
  [:div.flex.flex-column
   [:div.flex.flex-start
    (when (and errors (not content))
      {:class "form-error border-round border-solid ph"
       :style {:width "max-content"}})
    [:button.p1.text-white.border-round
     {:class    (if (= content :article)
                  "bg-dark-blue"
                  "bg-blue")
      :on-click #(do (re-frame/dispatch
                       [::form-edit data-key [key] :article])
                     (when errors
                       (re-frame/dispatch [::revalidate data-key])))}
     "published article"]
    [:button.p1.ml1.text-white.border-round
     {:class    (if (= content :labnote)
                  "bg-dark-blue"
                  "bg-blue")
      :on-click #(do (re-frame/dispatch
                      [::form-edit data-key [key] :labnote])
                     (when errors
                       (re-frame/dispatch [::revalidate data-key])))}
     "lab note"]]
   (when errors
     [error errors])
   (when content
     [:div.mth.mb1.flex
      [:div.flex.vcenter
       [:label.pr1.pl1 {:for (name key)
                    :style {:width "9rem"}}
        [:b (if (= content :article)
              "link to article"
              "reference")
         [:span.text-red.super.small " *"]]]]
      (let [placeholder (if (= content :article)
                          "www.ncbi.nlm.nih.gov/pubmed/..."
                          "investigator, lab, institution, date of observation")
            opts        (assoc opts
                               :key :source
                               :placeholder placeholder)]
        [text (add-form-data data-key opts)])])])

(defn responsive-two-column [l r]
  [:div.vcenter.mb1h.mbr2
   [:div.left-col l]
   [:div.right-col r]])

(defn input-row
  [{:keys [label required? full-width? component] :as field}]
  (let [label-span [:span [:b label] (when required?
                                       [:span.text-red.super.small " *"])]]
    (if full-width?
      [:div
       [:h4.ctext label-span]
       [component field]]
      [responsive-two-column
       label-span
       [component field]])))

(def extract-creation-form
  [{:component   textarea
    :label       "extract"
    :key         :text
    :required?   true
    :placeholder "what have you discovered?"}
   {:component   source-selector
    :label       "source"
    :key         :extract-type
    :required?   true}
   {:component   image-drop
    :label       "figure"
    :key         :figure
    :placeholder [:span [:b "choose a file"] " or drag it here"]}
   {:component   text
    :label       "image caption"
    :key         :figure-caption
    :placeholder "additional info about figure"}
   {:component   textarea-list
    :label       "comments"
    :key         :comments
    :placeholder "anything you think is important"}
   {:component   text-input-list
    :label       "confirmed by"
    :key         :confirmed
    :placeholder "link to paper"}
   {:component   text-input-list
    :label       "in contrast to"
    :key         :contrast
    :placeholder "link to paper"}
   {:component   text-input-list
    :label       "related results"
    :key         :related
    :placeholder "link to paper"}
   {:component   tag-selector
    :label       "add filter tags"
    :key         :tags
    :full-width? true}])

(defn extract-editor
  [{{:keys [id] :or {id ::new}} :path-params}]
  (into
   [:div.flex.flex-column.flex-start.pl2.pr2
    [:div.flex.space-around
     [:h2 (if (= ::new id)
            "create a new extract"
            "modify extract")]]
    [:div.flex.pb1.space-between.mb2
     [:button.bg-red.border-round.wide.text-white.p1
      {:on-click (fn [_]
                   (when (= id ::new)
                     (re-frame/dispatch [:extract/clear ::new]))
                   (re-frame/dispatch [:navigate {:route :search}]))
       :style {:opacity 0.6}}
      "CANCEL"]

     [:button.bg-dark-grey.border-round.wide.text-white.p1
      {:on-click (fn [_]
                   (re-frame/dispatch [::update-extract id]))}
      (if (= ::new id) "CREATE" "SAVE")]]]
   (map input-row (map (partial add-form-data id) extract-creation-form))))

(def routes
  [["/new" {:name      :extract/create
            :component extract-editor
            :controllers
            [{:start (fn [_]
                       (re-frame/dispatch [:extract/mark ::new]))}]}]
   ["/:id/edit" {:name       :extract/edit
                 :parameters {:path {:id any?}}
                 :component  extract-editor
                 :controllers core/extract-controllers}]])
