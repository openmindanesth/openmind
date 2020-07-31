(ns openmind.components.extract.editor.figure
  (:require [clojure.string :as string]
            [openmind.components.common :as common :refer [halt]]
            [openmind.components.forms :as forms]
            [openmind.events :as events]
            [openmind.util :as util]
            [re-frame.core :as re-frame]
            [reagent.core :as r]))


(defn drop-upload
  "Extracts the dropped image from the drop event and adds it to the app state."
  ;;FIXME: Is there some sort of standard regarding the dragging of images from
  ;;a browser? How can I be sure the first item will always contain a URL?
  [dk k e]
  (let [item (-> e .-dataTransfer .-items (aget 0))]
    (if-let [file (.getAsFile item)]
      (re-frame/dispatch [::load-figure dk file])
      (.getAsString
       item #(let [url (first (string/split % #"\n"))]
               (re-frame/dispatch [::add-figure dk url]))))))

(re-frame/reg-event-db
 ::remove-figure
 (fn [db [_ id]]
   (update-in db [::extracts id :content] dissoc :figure :figure-data)))

(defn select-upload [dk e]
  (let [f (-> e .-target .-files (aget 0))]
    (re-frame/dispatch [::load-figure dk f])))

(re-frame/reg-event-db
 ;; REVIEW: This is a kludge that's necessary due to the fact that rehashing the
 ;; figure on every keystroke isn't feasible. This is clearly a sign that I'm
 ;; doing something wrong, but it seems to work for the time being.
 ;;
 ;; And on top of all that, it's still too slow with big images (100+ KB, not
 ;; that big even)
 ::update-caption
 (fn [db [_ dk]]
   (let [fid        (get-in db [::extracts dk :content :figure])
         author     (:login-info db)
         new        (get-in db [::extracts dk :content :figure-data :content])
         original   (:content (events/table-lookup db fid))
         image-data (or (:image-data new)
                        (:image-data original))
         caption    (or (:caption new)
                        (:caption original))]
     (if (and (= image-data (:image-data original))
              (= caption (:caption original)))
       db
       (let [fdata (util/immutable {:author     author
                                    :caption    caption
                                    :image-data image-data})]
         (-> db
             (update-in [::extracts dk :content] assoc
                        :figure (:hash fdata)
                        :figure-data fdata)))))))

(defn figure-preview [{:keys [data-key content]}]
  ;; Figure is either just uploaded, or in the data store. Except that one can
  ;; edit the caption without changing the figure, and we have to deal with
  ;; that...
  (let [image-data (or
                    (-> @(re-frame/subscribe [::content data-key])
                        :figure-data
                        :content
                        :image-data)
                    (:image-data
                     @(re-frame/subscribe [:content content])))
        caption    (or (-> @(re-frame/subscribe [::content data-key])
                           :figure-data
                           :content
                           :caption)
                       (:caption
                        @(re-frame/subscribe [:content content])))]
    [:div.flex.flex-column
     [:div.flex
      [:img.border-round.mb1
       {:src   image-data
        :style {:width      "100%"
                :height     "auto"
                :max-height "30vh"
                :object-fit :contain
                :display    :block
                :max-width  "calc(90vw - 16rem)"}}]
      [:a.text-dark-grey.pl1
       {:on-click (juxt halt #(re-frame/dispatch [::remove-figure data-key]))}
       [:span "remove"]]]
     [forms/textarea {:key         [:figure-data :content :caption]
                :rows        4
                :data-key    data-key
                :on-blur     #(re-frame/dispatch [::update-caption data-key])
                :content     caption
                :placeholder "additional info about figure"} ]]))

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
        [:div.mt3.mb4
         [:label.p3.border-round drop-state placeholder]
         [:input {:type      :file
                  :id        id
                  :style     {:visibility :hidden}
                  :accept    "image/png,image/gif,image/jpeg"
                  :on-change (partial select-upload data-key)}]]))))

(defn figure-select [{:keys [content] :as opts}]
  (if content
    [figure-preview opts]
    [image-drop opts]))
