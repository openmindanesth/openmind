(ns openmind.components.extract.editor
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as string]
            [openmind.components.common :as common :refer [halt]]
            [openmind.components.extract :as extract]
            [openmind.components.tags :as tags]
            [openmind.edn :as edn]
            [openmind.events :as events]
            [openmind.spec.extract :as exs]
            [openmind.spec.validation :as validation]
            [openmind.util :as util]
            [re-frame.core :as re-frame]
            [reagent.core :as r]
            [taoensso.timbre :as log]))

(defn validate-extract
  "Checks form data for extract creation/update against the spec."
  [extract]
  (if (s/valid? ::exs/extract extract)
    {:valid extract}
    (let [err (s/explain-data ::exs/extract extract)]
      (log/trace "Bad extract" err)
      {:errors (validation/interpret-explanation err)})))

(def extract-keys
  [:text
   :author
   :tags
   :source
   :extract/type
   :figures
   :source-material
   :history/previous-version])

(defn prepare-extract
  [author {:keys [figure-data figures relations] :as extract}]
  (let [extract (-> extract
                    (assoc :author author)
                    (select-keys extract-keys))]
    {:snidbits (concat (map (partial get figure-data) figures) relations)
     :extract  extract}))

(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   (let [{:keys [extract]} (prepare-extract
                            (:login-info db)
                            (get-in db [::extracts id :content]))]
     {:dispatch [::form-errors (:errors (validate-extract extract)) id]})))

;;;;; New extract init

(def extract-template
  {:selection []
   :content   {:tags          #{}
               :figures       []
               :figure-data   {}
               :comments      [""]
               :relations     #{}}
   :errors    nil})

(re-frame/reg-event-fx
 ::new-extract-init
 (fn [{:keys [db]} _]
   (when (empty? (-> db ::extracts ::new))
     {:db (assoc-in db [::extracts ::new] extract-template)})))

(re-frame/reg-event-db
 ::clear
 (fn [db [_ id]]
   (-> db
       (assoc-in [::extracts id] extract-template)
       (dissoc ::similar ::related-search-results))))

(re-frame/reg-event-db
 ::editing-copy
 (fn [db [_ id]]
   (update db ::extracts assoc id (events/extract db id))))

;;;;; Subs

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (::extracts db)))

(re-frame/reg-sub
 ::extract
 :<- [::extracts]
 (fn [extracts [_ id]]
   (get extracts id)))

(re-frame/reg-sub
 ::content
 (fn [[_ id]]
   (re-frame/subscribe [::extract id]))
 (fn [extract _]
   (:content extract)))

(re-frame/reg-sub
 ::extract-form-errors
 (fn [[_ k]]
   (re-frame/subscribe [::extract k]))
 (fn [extract _]
   (:errors extract)))

(re-frame/reg-sub
 ::form-input-data
 (fn [[_ dk k] _]
   [(re-frame/subscribe [::content dk])
    (re-frame/subscribe [::extract-form-errors dk])])
 (fn [[content errors] [_ dk k]]
   {:content (get content k)
    :errors  (get errors k)}))

(re-frame/reg-sub
 ::similar
 (fn [db]
   (::similar db)))

(re-frame/reg-sub
 ::article-extracts
 (fn [db]
   (::article-extracts db)))

(re-frame/reg-sub
 ::related-search-results
 (fn [db]
   (::related-search-results db)))

;; tags

(re-frame/reg-sub
 ::editor-tag-view-selection
 (fn [[_ k]]
   (re-frame/subscribe [::extract k]))
 (fn [extract _]
   (:selection extract)))

(re-frame/reg-sub
 ::editor-selected-tags
 (fn [[_ k]]
   (re-frame/subscribe [::content k]))
 (fn [content _]
   (:tags content)))

(re-frame/reg-event-db
 ::set-editor-selection
 (fn [db [_ id path add?]]
   (assoc-in db [::extracts id :selection]
             (if add?
               path
               (vec (butlast path))))))

(re-frame/reg-event-db
 ::add-editor-tag
 (fn [db [_ id tag]]
   (update-in db [::extracts id :content :tags] conj (:id tag))))

(re-frame/reg-event-db
 ::remove-editor-tag
 (fn [db [_ id & tags]]
   (update-in db [::extracts id :content :tags]
              #(reduce disj % (map :id tags)))))

;;;;; Events

(re-frame/reg-event-db
 ::clear-related-search
 (fn [db]
   (dissoc db ::related-search-results)))

(re-frame/reg-event-db
 ::form-edit
 (fn [db [_ id k v]]
   (assoc-in db (concat [::extracts id :content] k) v)))

(re-frame/reg-event-fx
 ::add-figure
 (fn [{:keys [db]} [_ id data-url]]
   (let [author (:login-info db)
         img    (util/immutable {:image-data data-url
                                 :caption ""
                                 :author     author})]
     {:db (-> db
              (update-in [::extracts id :content :figures] conj (:hash img))
              (update-in [::extracts id :content :figure-data]
                         assoc (:hash img) img))})))

(re-frame/reg-event-fx
 ::load-figure
 (fn [cofx [_ id file]]
   (let [reader (js/FileReader.)]
     (set! (.-onload reader)
           (fn [e]
             (let [img (->> e
                            .-target
                            .-result)]
               (re-frame/dispatch
                [::add-figure id img]))))
     (.readAsDataURL reader file))))

(re-frame/reg-event-db
 ::form-errors
 (fn [db [_ errors id]]
   (assoc-in db [::extracts id :errors] errors)))

(re-frame/reg-event-fx
 ::update-extract
 (fn [{:keys [db]} [_ id]]
   (let [{:keys [snidbits extract]}
         (prepare-extract (get db :login-info)
                          (get-in db [::extracts id :content]))
         {:keys [valid errors]} (validate-extract extract)]
     (println errors)
     (if errors
       {:dispatch [::form-errors errors id]}
       ;; TODO: create an intern-all endpoint and don't send a slew of messages
       ;; unnecessaril
       {:dispatch-n (into [[:->server [:openmind/index extract]]]
                          (map #(do [:->server [:openmind/intern %]]))
                          snidbits)}))))

(defn success? [status]
  (<= 200 status 299))

(re-frame/reg-event-fx
 :openmind/index-result
 (fn [_ [_ status]]
   (if (success? status)
     {:dispatch-n [[::clear ::new]
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

(re-frame/reg-event-db
 ::add-relation
 (fn [db [_ id object-id type]]
   (let [author (:login-info db)
         rel {:attribute type
              :value object-id
              :entity id
              :author author}]
     (update-in db [::extracts id :content :relations] conj rel))))

(re-frame/reg-event-db
 ::remove-relation
 (fn [db [_ id rel]]
   (update-in db [::extracts id :content :relations] disj rel)))

;;;; Components

(defn pass-edit [id ks]
  (fn [ev]
    (re-frame/dispatch [::form-edit id ks (-> ev .-target .-value)])))

(defn add-form-data [id {:keys [key] :as elem}]
  (-> elem
      (assoc :data-key id)
      (merge @(re-frame/subscribe [::form-input-data id key]))))

(defn text
  [{:keys [label key placeholder errors content data-key on-change on-blur]}]
  (let [ks (if (vector? key) key [key])]
    [:div.full-width
     [:input.full-width-textarea
      (merge {:id        (apply str ks)
              :type      :text
              :on-blur   #(when on-blur (on-blur %))
              :on-change (juxt (pass-edit data-key ks)
                               #(when on-change
                                  (on-change (-> % .-target .-value)))
                               #(when errors
                                  (re-frame/dispatch [::revalidate data-key])))}
             (cond
               (seq content) {:value content}
               placeholder   {:value       nil
                              :placeholder placeholder})
             (when errors
               {:class "form-error"}))]
     (when errors
       [common/error errors])]))

(defn textarea
  [{:keys [label key required? placeholder spec errors content data-key on-change]}]
  [:div
   [:textarea.full-width-textarea
    (merge {:id        (name key)
            :rows      2
            :style     {:resize :vertical}
            :type      :text
            :on-change (juxt (pass-edit data-key [key])
                             #(when on-change
                                (on-change (-> % .-target .-value)))
                             #(when errors
                                (re-frame/dispatch [::revalidate data-key])))}
           (cond
             (seq content) {:value content}
             placeholder   {:value       nil
                            :placeholder placeholder})
           (when errors
             {:class "form-error"}))]
   (when errors
     [common/error errors])])

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
                  [common/error err]])])))
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
              ;; REVIEW: Is this just cut and paste of the textarea component?!?!
              [:div
               [:textarea.full-width-textarea
                (merge {:id          (name (str key i))
                        :style       {:resize :vertical}
                        :rows        2
                        :placeholder placeholder
                        :type        :text
                        :on-change   (pass-edit data-key [key i])}
                       (when (seq content)
                         {:value c})
                       (when err
                         {:class "form-error"}))]
               (when err
                 [:div.mbh
                  [common/error err]])]))
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
 (fn [db [_ id i]]
   (let [fid (get-in db [::extracts id :content :figures i])]
     (-> db
         (update-in [::extracts id :content :figure-data] dissoc fid)
         (update-in [::extracts id :content :figures]
                    #(let [[head [_ & tail]] (split-at i %)]
                       (into (empty %) (concat head tail))))))))

(defn select-upload [dk e]
  (let [f (-> e .-target .-files (aget 0))]
    (re-frame/dispatch [::load-figure dk f])))

(re-frame/reg-event-db
 ;; REVIEW: This is a kludge that's necessary due to the fact that rehashing the
 ;; figure on every keystroke isn't feasible. This is clearly a sign that I'm
 ;; doing something wrong, but it seems to work for the time being.
 ::update-caption
 (fn [db [_ dk index]]
   (let [id  (get-in db [::extracts dk :content :figures index])
         fig (get-in db [::extracts dk :content :figure-data id :content])
         new (util/immutable fig)]
     (if (= fig new)
       db
       (-> db
           (update-in [::extracts dk :content :figure-data]
                      #(-> %
                           (dissoc id)
                           (assoc (:hash new) new)))
           (update-in [::extracts dk :content :figures]
                      #(let [[head [_ & tail]] (split-at index %)]
                         (into (empty %) (concat head [(:hash new)] tail)))))))))

(defn figure-preview [dk {:keys [hash content]} index]
  (let [{:keys [image-data caption]} content]
    [:div.flex.flex-column
     [:div.p1.flex
      {:style {:max-width "40rem"}}
      [:img.border-round
       {:src   image-data
        :style {:width :max-content}}]
      [:a.text-dark-grey.pl1
       {:on-click (juxt halt #(re-frame/dispatch [::remove-figure dk index]))}
       [:span "remove"]]]
     [text {:key         [:figure-data hash :content :caption]
            :data-key    dk
            :on-blur      #(re-frame/dispatch [::update-caption dk index])
            :content     caption
            :placeholder "additional info about figure"} ]]))

(defn figure-list [{:keys [data-key content] :as opts}]
  (let [{:keys [figures figure-data]} @(re-frame/subscribe [::content data-key])]
    [:div.flex.flex-column
     (map-indexed (fn [i f]
                    ^{:key (str "fig-" i)}
                    [figure-preview data-key (get figure-data f) i])
                  figures)]))

(defn image-drop
  [opts]
  (let [id          (str (gensym))
        drag-hover? (r/atom false)]
    (fn [{:keys [key placeholder content data-key]}]
      (let [figures    (get content key)
            drop-state {:style         {:border    :dashed
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
        [:div.mt1.mb2.flex.flex-column
         [:label.p2.border-round drop-state placeholder]
         [:input {:type      :file
                  :id        id
                  :style     {:visibility :hidden}
                  :accept    "image/png,image/gif,image/jpeg"
                  :on-change (partial select-upload data-key)}]]))))

(defn source-preview [{:keys [data-key] :as opts}]
  (let [{:keys [source extract/type]} @(re-frame/subscribe [::content data-key])]
    (when (and (= type :article) (:abstract source))
      [extract/source-content source])))

(re-frame/reg-event-fx
 ::pubmed-lookup
 (fn [cofx [_ id url]]
   (when (.includes url "ncbi.nlm.nih.gov")
     {:dispatch [:->server [:openmind/pubmed-lookup {:res-id id :url url}]]})))

(re-frame/reg-event-fx
 :openmind/pubmed-article
 (fn [{:keys [db]} [_ {:keys [res-id url source]}]]
   (let [current (get-in db [::extracts res-id :content :source :url])]
     (when (and (= url current) (seq source))
       {:db (update-in db [::extracts res-id :content :source] merge source)}))))

(defn source-article [{:keys [content data-key] :as opts}]
  [text (assoc opts
               :content (:url content)
               :on-blur #(re-frame/dispatch
                          [::pubmed-lookup data-key (:url content)])
               :key [:source :url])])

(defn source-labnote [opts]
  [:div "work in progress"])

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
     [common/error errors])
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
        [(if (= :article content) source-article source-labnote )
         (add-form-data data-key opts)])])])

(defn responsive-two-column [l r]
  [:div.vcenter.mb1h.mbr2
   [:div.left-col l]
   [:div.right-col r]])

(defn responsive-three-column [l r f]
  [:div.vcenter.mb1h.mbr2
   [:div.left-col l]
   [:div.right-col
    [:div.middle-col r]
    [:div.feedback-col f]]])

(defn input-row
  [{:keys [label required? full-width? component feedback] :as field}]
  (let [label-span [:span [:b label] (when required?
                                       [:span.text-red.super.small " *"])]]
    (if full-width?
      [:div
       (when label
         [:h4.ctext label-span])
       [component field]]
      (if feedback
        [responsive-three-column
         label-span
         [component field]
         [feedback field]]
        [responsive-two-column
         label-span
         [component field]]))))

(defn relation-button [text event]
  [:button.text-white.ph.border-round.bg-dark-grey
   {:on-click #(re-frame/dispatch event)}
   text])

(defn related-buttons [extract-id]
  (fn  [{:keys [hash] :as extract}]
    (let [ev [::add-relation extract-id hash]]
      [:div.flex.space-evenly
       [relation-button "related extract" (conj ev :related)]
       [relation-button "in contrast to" (conj ev :contrast)]
       [relation-button "confirmed by" (conj ev :confirmed)]])))

(def relation-names
  {:contrast  "in contrast to"
   :confirmed "confirmed by"
   :related   "related to"})

(defn relation-meta [attribute]
  (fn [extract]
    [:span
     [:span.border-round.border-solid.ph.bg-light-blue
      (get relation-names attribute)]
     [:span.pl1
      [extract/metadata extract]]]))

(defn cancel-button [onclick]
  [:a.border-circle.bg-white.text-black.border-black
   {:style    {:position :relative
               :float    :right
               :top      "-1px"
               :right    "-1px"}
    :title "remove relation"
    :on-click (juxt common/halt onclick)}
   [:span.absolute
    {:style {:top "-2px"
             :right "5px"}}
    "x"]])

(defn relation [{:keys [attribute value entity] :as rel}]
  (let [extract @(re-frame/subscribe [:content value])]
    [:span
     [cancel-button #(re-frame/dispatch [::remove-relation entity rel])]
     [extract/summary extract
      {:meta-display (relation-meta attribute)
       :edit-link?   false}]]))

(defn related-extracts [{:keys [content]}]
  (into [:div.flex.flex-column]
        (map relation)
        content))

(defn search-results [key data-key]
  (let [results @(re-frame/subscribe [key])]
    (into [:div.flex.flex-column]
          (map (fn [id]
                 [extract/summary @(re-frame/subscribe [:content id])
                  {:controls (related-buttons data-key)
                   :edit-link? false}]))
          results)))

(defn similar-extracts [{:keys [data-key] :as opts}]
  (let [open? (r/atom true)]
    (fn [opts]
      (let [content @(re-frame/subscribe [::content data-key])
            similar @(re-frame/subscribe [::similar])]
        (when (and (< 4 (count (:text content))) (seq similar))
          [:div
           [:div.left-col
            [:a.super.right.plh.prh
             {:on-click (fn [_] (swap! open? not))
              :title (if @open? "collapse" "expand")}
             [:div (when @open? {:style {:transform "rotate(90deg)"}}) "➤"]]
            [:span [:b "possibly similar extracts"]]]
           [:div.right-col
            (if @open?
              [search-results ::similar data-key]
              [:div.pl1 {:style {:padding-bottom "0.3rem"}}
               [:b "..."]])]])))))

(defn shared-source [{:keys [data-key]}]
  (let [open? (r/atom true)]
    (fn [opts]
      (let [content @(re-frame/subscribe [::content data-key])
            same-article @(re-frame/subscribe [::article-extracts])]
        (when (and (= :article (:extract/type content)) (seq same-article))
          [:div
           [:div.left-col
            [:a.super.right.plh.prh
              {:on-click (fn [_] (swap! open? not))}
              [:div (when @open? {:style {:transform "rotate(90deg)"}}) "➤"]]
            [:span [:b "extracts based on this article"]]]
           [:div.right-col
            [:div.border-round.border-solid.ph
             {:style {:border-color :lightgrey
                      :box-shadow "1px 1.5px grey inset"}}
             (if @open?
               [:div.pl1 "placeholder"]
               [:div.pl1
                [:b "not implemented"]])]]])))))

(defn extract-search-results [{:keys [data-key]}]
  [search-results ::related-search-results data-key])

(def extract-creation-form
  [{:component   textarea
    :label       "extract"
    :on-change   #(when (< 4 (count %))
                    (re-frame/dispatch
                     [:openmind.components.search/search-request
                      {:term %} ::similar]))
    :key         :text
    :required?   true
    :placeholder "what have you discovered?"}
   {:component   similar-extracts
    :key         :similar
    :full-width? true}
   {:component source-selector
    :label     "source"
    :key       :extract/type
    :required? true
    :feedback  source-preview}
   {:component   shared-source
    :key         :same-article
    :full-width? true}
   {:component   image-drop
    :label       "figures"
    :key         :figures
    :placeholder [:span [:b "choose a file"] " or drag it here"]}
   {:component figure-list}
   {:component   text
    :label       "source materials"
    :placeholder "link to any code / data that you'd like to share"
    :key         :code-repo}
   {:component   textarea-list
    :label       "comments"
    :key         :comments
    :placeholder "anything you think is important"}
   {:component   text
    :placeholder "find extract that might be related to this one"
    :on-change   #(re-frame/dispatch
                   (if (< 2 (count %))
                     [:openmind.components.search/search-request
                      {:term %} ::related-search-results]
                     [::clear-related-search]))
    :label       "search for related extracts"
    :key         :search}
   {:component extract-search-results}
   {:component related-extracts
    :label     "related extracts"
    :key       :relations}
   {:component   tag-selector
    :label       "add filter tags"
    :key         :tags
    :full-width? true}])

(defn extract-editor
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [id (if (= ::new id) id (edn/read-string id))]
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
                       (re-frame/dispatch [::clear ::new]))
                     ;; TODO: Reset any chanes to edited extracts on cancel
                     (re-frame/dispatch [:navigate {:route :search}]))
         :style {:opacity 0.6}}
        "CANCEL"]

       [:button.bg-dark-grey.border-round.wide.text-white.p1
        {:on-click (fn [_]
                     (re-frame/dispatch [::update-extract id]))}
        (if (= ::new id) "CREATE" "SAVE")]]]
     (map input-row (map (partial add-form-data id) extract-creation-form)))))

(def routes
  [["/new" {:name      :extract/create
            :component extract-editor
            :controllers
            [{:start (fn [_]
                       (re-frame/dispatch [::new-extract-init]))}]}]

   ["/:id/edit" {:name       :extract/edit
                 :component  extract-editor
                 :controllers
                 [{:parameters {:path [:id]}
                   :start (fn [{{id :id} :path}]
                            (let [id (edn/read-string id)]
                              (when-not @(re-frame/subscribe [::extract id])
                                (re-frame/dispatch
                                 [:ensure id ::editing-copy]))))}]}]])
