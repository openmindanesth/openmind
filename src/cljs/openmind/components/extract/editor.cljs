(ns openmind.components.extract.editor
  (:require [cljs.spec.alpha :as s]
            [clojure.data :as set]
            [clojure.string :as string]
            [openmind.components.comment :as comment]
            [openmind.components.common :as common :refer [halt]]
            [openmind.components.extract :as extract]
            [openmind.components.extract.core :as core]
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
   :figure
   :source-material
   :history/previous-version])

(defn prepare-extract
  [author extract]
  (let [ex (select-keys extract extract-keys)]
    (if (empty? (:author ex))
      (assoc ex :author author)
      ex)))

(defn sub-new [id]
  (fn [{:keys [entity] :as rel}]
    (if (= entity ::new)
      (assoc rel :entity id)
      rel)))

(defn finalise-extract [prepared {:keys [figure-data relations comments figure]}]
  (let [imm (util/immutable prepared)
        rels (map (sub-new (:hash imm)) relations)
        id (:hash imm)
        author (:author prepared)]
    {:imm imm
     :snidbits (concat (when figure [figure-data])
                       (map util/immutable rels)
                       (map (fn [t]
                              (util/immutable
                               {:author author :text t :extract id}))
                            (remove empty? comments)))}))

(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   (let [extract (prepare-extract
                  (:login-info db)
                  (get-in db [::extracts id :content]))]
     {:dispatch [::form-errors (:errors (validate-extract extract)) id]})))

;;;;; New extract init

(def extract-template
  {:selection []
   :content   {:tags          #{}
               :comments      [""]
               :relations     #{}}
   :errors    nil})

(re-frame/reg-event-fx
 ::new-extract-init
 (fn [{:keys [db]} _]
   (when (empty? (-> db ::extracts ::new :content))
     {:db (assoc-in db [::extracts ::new] extract-template)})))

(re-frame/reg-event-db
 ::clear
 (fn [db [_ id]]
   (-> db
       (update ::extracts dissoc id)
       (dissoc ::similar ::related-search-results))))

(re-frame/reg-event-fx
 ::editing-copy
 (fn [{:keys [db]} [_ id]]
   (let [content (:content (events/table-lookup db id))]
     {:db (update db ::extracts assoc id {:content content})})))

(re-frame/reg-event-db
 ::reconcile-metadata
 (fn [db [_ hash metadata]]
   (update-in db [::extracts hash :content]
              assoc :relations (:relations metadata))))

(re-frame/reg-event-db
 ::set-figure-data
 (fn [db [_ fid id]]
   (assoc-in db [::extracts id :content :figure-data]
             (events/table-lookup db fid))))

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
 ::nested-form-data
 (fn [[_ dk k] _]
   [(re-frame/subscribe [::content dk])
    (re-frame/subscribe [::extract-form-errors dk])])
 (fn [[content errors] [_ dk k]]
   {:content (get-in content k)
    :errors  (get-in errors k)}))

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
              (assoc-in [::extracts id :content :figure] (:hash img))
              (assoc-in [::extracts id :content :figure-data] img))})))

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

(defn extract-changed? [old new]
  (let [content (dissoc (:content old) :hash :time/created)]
    (when-not (= content new)
      (util/immutable
       (assoc new :history/previous-version (:hash old))))))

(defn changed? [imm fig extract base]
  (let [rels (:relations @(re-frame/subscribe [:extract-metadata (:hash extract)]))]
    (or (some? imm)
        (some? fig)
        (not= rels (:relations base)))))

(defn update-relations [oldid newid relations]
  (if newid
    (into #{}
          (map (fn [{:keys [entity value] :as rel}]
                 (if (= entity oldid)
                   (assoc rel :entity newid)
                   (assoc rel :value newid))))
          relations)
    relations))

(re-frame/reg-event-fx
 ::update-extract
 (fn [{:keys [db]} [_ id]]
   (let [base                   (get-in db [::extracts id :content])
         extract                (prepare-extract (get db :login-info) base)
         {:keys [valid errors]} (validate-extract extract)]
     (if errors
       {:dispatch [::form-errors errors id]}
       (if (= id ::new)
         (let [{:keys [imm snidbits]} (finalise-extract extract base)]
           {:dispatch [:->server [:openmind/index
                                  {:extract imm :extras snidbits}]]})
         (let [original (events/table-lookup db id)
               fig      (when-not (= (:figure extract) (:figure original))
                          (:figure-data base))
               imm      (extract-changed? original extract)]
           (if (changed? imm fig original base)
             {:dispatch [:->server [:openmind/update
                                    {:new-extract imm
                                     :editor      (get db :login-info)
                                     :previous-id (:hash original)
                                     :figure      fig
                                     :relations   (update-relations
                                                   id (:hash imm)
                                                   (:relations base))}]]}
             ;; no change, just go back to search
             {:dispatch-n [[:notify {:status  :warn
                                     :message "no changes to save"}]
                           [:navigate {:route :search}]]})) )))))

(re-frame/reg-event-fx
 :openmind/index-result
 (fn [_ [_ {:keys [status message]}]]
   (if (= :success status)
     {:dispatch-n [[::clear ::new]
                   [:notify {:status  :success
                             :message
                             (str "new extract successfully submitted\n"
                                  "your search results will reflect the search"
                                  " index once it has been updated")}]
                   [:navigate {:route :search}]]}
     ;;TODO: Fix notification bar.
     {:dispatch [:notify {:status  :error
                          :message "failed to create extract"}]})))

(re-frame/reg-event-fx
 :openmind/update-response
 (fn [cofx [_ {:keys [status message id]}]]
   (if (= :success status)
     {:dispatch-n [[::clear id]
                   [:notify {:status  :success
                             :message (str "changes submitted successfully\n"
                                           "it may take a moment for the changes"
                                           " to be reflected in your results.")}]
                   [:navigate {:route :search}]]}
     {:dispatch [:notify {:status :error :message "failed to save changes"}]})))

(re-frame/reg-event-db
 ::clear-status-message
 (fn [db]
   (dissoc db :status-message)))

(re-frame/reg-event-db
 ::add-relation
 (fn [db [_ id object-id type]]
   (let [author (:login-info db)
         rel    {:attribute type
                 :value     object-id
                 :entity    id
                 :author    author}]
     (if (get-in db [::extracts id :content :relations])
       (update-in db [::extracts id :content :relations] conj rel)
       (assoc-in db [::extracts id :content :relations] #{rel})))))

(re-frame/reg-event-db
 ::remove-relation
 (fn [db [_ id rel]]
   (update-in db [::extracts id :content :relations] disj rel)))

;;;; Components

(defn pass-edit [id ks & [sub-key]]
  (fn [ev]
    (let [v (-> ev .-target .-value)
          v' (if sub-key {sub-key v} v)]
      (re-frame/dispatch [::form-edit id ks v']))))

(defn add-form-data [id {:keys [key] :as elem}]
  (-> elem
      (assoc :data-key id)
      (merge @(re-frame/subscribe [::form-input-data id key]))))

(defn text
  [{:keys [label key placeholder errors content data-key on-change on-blur]
    :as   opts}]
  (let [ks (if (vector? key) key [key])]
    [:div.full-width
     [:input.full-width-textarea
      (merge {:id        (apply str ks)
              :type      :text
              :on-blur   #(when on-blur (on-blur opts))
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
    (merge {:id        (str key)
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
  [{:keys [key placeholder spec errors content data-key sub-key]}]
  (conj
   (into [:div.flex.flex-wrap]
         (map-indexed
          (fn [i c]
            (let [err (get errors i)]
              [:div
               {:style {:padding-right "0.2rem"}}
               [:input.full-width-textarea
                (merge {:type      :text
                        :on-change (pass-edit data-key (conj key i) sub-key)}
                       (when err
                         {:class "form-error"})
                       (if (seq c)
                         {:value (if sub-key (get c sub-key) c)}
                         {:value       nil
                          :placeholder placeholder}))]
               (when err
                 [:div.mbh
                  [common/error err]])])))
         content)
   [:a.plh.ptp {:on-click (fn [_]
                            (if (nil? content)
                              (re-frame/dispatch
                               [::form-edit data-key key [""]])
                              (re-frame/dispatch
                               [::form-edit data-key
                                (conj key (count content)) ""])))}
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
  [:div {:style {:min-height "40rem"}}
   [tags/tag-widget {:selection {:read [::editor-tag-view-selection id]
                                 :set  [::set-editor-selection id]}
                     :edit      {:read   [::editor-selected-tags id]
                                 :add    [::add-editor-tag id]
                                 :remove [::remove-editor-tag id]}}]])

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
        caption (or (-> @(re-frame/subscribe [::content data-key])
                        :figure-data
                        :content
                        :caption)
                    (:caption
                     @(re-frame/subscribe [:content content])))]
    [:div.flex.flex-column
     [:div.p1.flex
      {:style {:max-width "40rem"}}
      [:img.border-round
       {:src   image-data
        :style {:width :max-content}}]
      [:a.text-dark-grey.pl1
       {:on-click (juxt halt #(re-frame/dispatch [::remove-figure data-key]))}
       [:span "remove"]]]
     [text {:key         [:figure-data :content :caption]
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
        [:div.mt1.mb2.flex.flex-column
         [:label.p2.border-round drop-state placeholder]
         [:input {:type      :file
                  :id        id
                  :style     {:visibility :hidden}
                  :accept    "image/png,image/gif,image/jpeg"
                  :on-change (partial select-upload data-key)}]]))))

(defn figure-select [{:keys [content] :as opts}]
  (if content
    [figure-preview opts]
    [image-drop opts]))

(defn source-preview [{:keys [data-key] :as opts}]
  (let [{:keys [source extract/type]} @(re-frame/subscribe [::content data-key])]
    (when (and (= type :article) (:abstract source))
      [extract/source-content source])))

(re-frame/reg-event-fx
 ::pubmed-lookup
 (fn [cofx [_ id url]]
   (let [last-searched (-> cofx :db ::extracts (get id) :content :source :url)]
     (when-not (= url last-searched)
       {:dispatch-n [[:->server [:openmind/pubmed-lookup {:res-id id :url url}]]
                     [:openmind.components.window/spin]]}))))

(re-frame/reg-event-fx
 :openmind/pubmed-article
 (fn [{:keys [db]} [_ {:keys [res-id url source]}]]
   (let [current (get-in db [::extracts res-id :content :article-search])]
     (if (and (= url current) (seq source))
       {:db         (update-in db [::extracts res-id :content :source] merge source)
        :dispatch-n [[:openmind.components.window/unspin]
                     [:notify {:status :success :message "article found"}]]}
       {:db         (update-in db [::extracts res-id :content] dissoc :source)
        :dispatch-n [[:openmind.components.window/unspin]
                     [:notify {:status :error
                               :message
                               (str "we couldn't find that article\n"
                                    "please enter its details below")}]]}))))

(defn source-selector [{:keys [key content data-key errors] :as opts}]
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
     "article"]
    [:button.p1.ml1.text-white.border-round
     {:class    (if (= content :labnote)
                  "bg-dark-blue"
                  "bg-blue")
      :on-click #(do (re-frame/dispatch
                      [::form-edit data-key [key] :labnote])
                     (when errors
                       (re-frame/dispatch [::revalidate data-key])))}
     "lab note"]]])

;; FIXME: cut and paste!!
(defn peer-review-widget [{:keys [key content data-key errors] :as opts}]
  [:div.flex.flex-column
   [:div.flex.flex-start
    (when (and errors (not content))
      {:class "form-error border-round border-solid ph"
       :style {:width "max-content"}})
    [:button.p1.text-white.border-round
     {:class    (if (true? content)
                  "bg-dark-blue"
                  "bg-blue")
      :on-click #(do (re-frame/dispatch
                       [::form-edit data-key key true])
                     (when errors
                       (re-frame/dispatch [::revalidate data-key])))}
     "peer reviewed article"]
    [:button.p1.ml1.text-white.border-round
     {:class    (if (false? content)
                  "bg-dark-blue"
                  "bg-blue")
      :on-click #(do (re-frame/dispatch
                      [::form-edit data-key key false])
                     (when errors
                       (re-frame/dispatch [::revalidate data-key])))}
     "preprint"]]])

(defn date [{:keys [content]}]
  (when content
    [:div (str content)]))

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
  [{:keys [label required? full-width? component feedback sublabel] :as field}]
  (let [label-span [:div  [:span [:b label]
                           (when required?
                             [:span.text-red.super.small " *"])]
                    (when sublabel [sublabel field])]]
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

(def labnote-details-inputs
  ;; For lab notes we want to get the PI, institution (corp), and date of
  ;; observation.
  [{:component text
    :label "institution"
    :placeholder "university, company, etc."
    :key [:source :institution]
    :required? true}
   {:component text
    :label "lab"
    :placeholder "lab name"
    :key [:source :lab]
    :required? true}
   {:component text
    :label "investigator"
    :placeholder "principle investigator"
    :key [:source :investigator]
    :required? true}
   {:component date
    :label "date of observation"
    :required? true
    :key [:source :observation/date]}])

(defn article-search [{:keys [data-key key content] :as opts}]
  (let [waiting? @(re-frame/subscribe [:openmind.components.window/spinner])]
    [:div.flex
     [text opts]
     [:button.bg-blue.ph.mlh.text-white.border-round
      {:style (when waiting? {:cursor :wait})
       :on-click #(re-frame/dispatch [::pubmed-lookup data-key content])}
      "search"]]))

(def source-details-inputs
  ;; For article extracts, we can autofill from pubmed, but if that doesn't
  ;; work, we want the title, author list, publication, and date.
  [{:key         [:article-search]
    :component   article-search
    :label       "find paper"
    :placeholder "www.ncbi.nlm.nih.gov/pubmed/..."
    ;; This is the goal#_"article doi or url to pubmed/bioarxiv"
    }
   {:component   text
    :label       "link to article"
    :key         [:source :url]
    :placeholder "www.ncbi.nlm.nih.gov/pubmed/..."
    :required?   true}
   {:component peer-review-widget
    :label     "status"
    :title     "is this article peer reviewed, or a preprint?"
    :key       [:source :peer-reviewed?]
    :required? true}
   {:component text
    :label     "doi"
    :key       [:source :doi]
    :required? true}
   {:component textarea
    :label     "title"
    :key       [:source :title]
    :required? true}
   {:component text-input-list
    :label     "authors"
    :key       [:source :authors]
    :sub-key   :full-name
    :required? true}
   {:component date
    :label     "publication date"
    :key       [:source :publication/date]
    :required? true}
   {:component textarea
    :label     "abstract"
    :key       [:source :abstract]}
   {:component text
    :label     "journal"
    :key       [:source :journal]}
   {:component text
    :label     "volume"
    :key       [:source :volume]}
   {:component text
    :label     "issue"
    :key       [:source :issue]}])

(defn source-details [{:keys [data-key content] :as opts}]
  (let [extract @(re-frame/subscribe [::content data-key])
        type    (:extract/type extract)]
    (into [:div.flex.flex-column]
          (comp
           (map (fn [{:keys [key] :as opts}]
                  (merge opts
                         {:data-key data-key}
                         @(re-frame/subscribe
                           [::nested-form-data data-key key]))))
           (map input-row))
          (case type
            :article source-details-inputs
            :labnote labnote-details-inputs
            []))))

(defn comment-widget [{:keys [data-key] :as opts}]
  (if (= data-key ::new)
    [textarea-list opts]
    [comment/comment-page-content data-key]))

(defn relation-button [text event]
  [:button.text-white.ph.border-round.bg-dark-grey
   {:on-click #(re-frame/dispatch event)}
   text])

(defn related-buttons [extract-id]
  (fn  [{:keys [hash] :as extract}]
    (let [ev [::add-relation extract-id hash]]
      (into [:div.flex.space-evenly]
            (map (fn [a]
                   [relation-button (get extract/relation-names a) (conj ev a)]))
            [:related :confirmed :contrast]))))

(defn cancel-button [onclick]
  [:a.border-circle.bg-white.text-black.border-black.relative.right
   {:style    {:cursor   :pointer
               :z-index  105
               :top      "-1px"
               :right    "-1px"}
    :title    "remove relation"
    :on-click (juxt common/halt onclick)}
   [:span.absolute
    {:style {:top   "-2px"
             :right "5px"}}
    "x"]])

(defn relation-summary [{:keys [content]}]
  (let [summary (into {} (map (fn [[k v]] [k (count v)]))
                      (group-by :attribute content))]
    (into [:div.flex.flex-column]
          (map (fn [a]
                 (let [c (get summary a)]
                   (when (< 0 c)
                     [:div {:style {:margin-top "3rem"
                                    :max-width "12rem"}}
                      [:span
                       {:style {:display :inline-block
                                :width "70%"}}
                       (get extract/relation-names a)]
                      [:span.p1.border-solid.border-round
                       {:style {:width "20%"}}
                       c]]))))
          [:related :confirmed :contrast])))

(defn relation [data-key {:keys [attribute value entity author] :as rel}]
  (let [other (if (= data-key entity) value entity)
        extract @(re-frame/subscribe [:content other])
        login @(re-frame/subscribe [:openmind.subs/login-info])]
    [:span
     (when (= login author)
       [cancel-button #(re-frame/dispatch [::remove-relation data-key rel])])
     [extract/summary extract
      {:controls (extract/relation-meta attribute)
       :edit-link?   false}]]))

(def scrollbox-style
  {:style {:max-height      "40rem"
           :padding         "0.1rem"
           :scrollbar-width :thin
           :overflow-y      :auto
           :overflow-x      :visible}})

(defn related-extracts [{:keys [content data-key]}]
  (into [:div.flex.flex-column scrollbox-style]
        (map (partial relation data-key))
        content))

(defn search-results [key data-key]
  (let [results @(re-frame/subscribe [key])
        selected (into {}
                       (map (fn [{:keys [entity value] :as rel}]
                              [(if (= entity data-key) value entity) rel]))
                       (:relations @(re-frame/subscribe [::content data-key])))]
    (into [:div.flex.flex-column scrollbox-style]
          (concat
           (into []
                 (comp
                  (remove (fn [id] (contains? selected id)))
                  (map (fn [id] @(re-frame/subscribe [:content id])))
                  (map (fn [extract]
                         [extract/summary extract
                          {:controls (related-buttons data-key)
                           :edit-link? false}])))
                 results)
           (into []
                 (comp
                  (map #(get selected %))
                  (remove nil?)
                  (map (partial relation data-key)))
                 results)))))

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
    :required? true}
   {:component   source-details
    :key         :source
    :full-width? true}
   {:component   shared-source
    :key         :same-article
    :full-width? true}
   {:component   figure-select
    :label       "figure"
    :key         :figure
    :placeholder [:span [:b "choose a file"] " or drag it here"]}
   {:component   text
    :label       "source materials"
    :placeholder "link to any code / data that you'd like to share"
    :key         :source-material}
   {:component   comment-widget
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
    :sublabel  relation-summary
    :key       :relations}
   {:component   tag-selector
    :label       "add filter tags"
    :key         :tags
    :full-width? true}])

(re-frame/reg-sub
 ::invisihack
 (fn [db [_ id]]
   (contains? (::invisihack db) id)))

(re-frame/reg-event-db
 ::start-hack
 (fn [db [_ id]]
   (if (contains? db ::invisihack)
     (update db ::invisihack conj id)
     (assoc db ::invisihack #{id}))))

(re-frame/reg-event-db
 ::end-hack
 (fn [db [_ id]]
   (update db ::invisihack disj id)))

(defn invisihack [id]
  (when-not (= id ::new)
      (let [content @(re-frame/subscribe [::content id])
            metadata @(re-frame/subscribe [:extract-metadata id])]
        (when metadata
          (when (not= (:relations content) (:relations metadata))
            ;; HACK: Issuing events from the component is not recommended. The
            ;; problem is that I need an event to react to a subscription that
            ;; reacts to events that ... Subscribptions are beautifully
            ;; reactive, but trying to have an event only process if a chain
            ;; of previous events are already complete eludes me. Hence this.
            (re-frame/dispatch [::reconcile-metadata id metadata]))
          (re-frame/dispatch [::end-hack id]))))
  [:div {:style {:display :none}}])

(defn extract-editor
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [id (if (= ::new id) id (edn/read-string id))
        invisihack? @(re-frame/subscribe [::invisihack id])]
    (into
     [:div.flex.flex-column.flex-start.pl2.pr2
      (when invisihack?
        [invisihack id])
      [:div.flex.space-around
       [:h2 (if (= ::new id)
              "create a new extract"
              "modify extract")]]
      [:div.flex.pb1.space-between.mb2
       [:button.bg-red.border-round.wide.text-white.p1
        {:on-click (fn [_]
                     (re-frame/dispatch [::clear id])
                     (re-frame/dispatch [:navigate {:route :search}]))
         :style {:opacity 0.6}}
        "CANCEL"]

       [:button.bg-dark-grey.border-round.wide.text-white.p1
        {:on-click (fn [_]
                     ;; TODO: Spinning cursor while waiting for response from
                     ;; server.
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
                            (re-frame/dispatch [::clear nil])
                            (let [id (edn/read-string id)]
                              (when-not @(re-frame/subscribe [::extract id])
                                (re-frame/dispatch [::start-hack id])
                                (re-frame/dispatch
                                 [:ensure id [::editing-copy id]]))))}]}]])
