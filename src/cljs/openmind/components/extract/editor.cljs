(ns openmind.components.extract.editor
  (:require [cljs.spec.alpha :as s]
            [clojure.set :as set]
            [openmind.components.comment :as comment]
            [openmind.components.common :as common]
            [openmind.components.extract :as extract]
            [openmind.components.extract.core :as c]
            [openmind.components.extract.editor.figure :as figure]
            [openmind.components.extract.editor.relations :as relations]
            [openmind.components.forms :as forms]
            [openmind.components.tags :as tags]
            [openmind.edn :as edn]
            [openmind.events :as events]
            [openmind.spec.extract :as exs]
            [openmind.spec.validation :as validation]
            [openmind.util :as util]
            [re-frame.core :as re-frame]
            [reagent.core :as r]
            [taoensso.timbre :as log]))

(defn validate-source [{:keys [extract/type source] :as extract}]
  (let [spec (if (= type :article)
               ::exs/article-details
               ::exs/labnote-source)
        err (s/explain-data spec (or source {}))]
    (when err
      (log/trace "invalid source\n" err)
      (validation/interpret-explanation err))))

(defn validate-resources [{:keys [resources]}]
  (mapv (fn [r]
          (validation/interpret-explanation
           (s/explain-data ::exs/resource r)))
        resources))

(defn validate-extract
  "Checks form data for extract creation/update against the spec."
  [extract]
  (if (s/valid? ::exs/extract extract)
    {:valid extract}
    (let [err (s/explain-data ::exs/extract extract)
          source-err (validate-source extract)]
      (log/trace "Bad extract\n" err)
      {:errors (assoc (validation/interpret-explanation err)
                      :source source-err
                      :resources (validate-resources extract))})))

(def extract-keys
  [:text
   :author
   :tags
   :source
   :extract/type
   :figure
   :resources
   :history/previous-version])

;; TODO: Pull these out of the specs.
(def article-keys
  [:publication/date
   :url
   :title
   :authors
   :peer-reviewed?
   :doi
   :abstract
   :journal
   :volume
   :issue])

(def labnote-keys
  [:lab
   :investigator
   :institution
   :observation/date])

(defn prepare-extract
  [{:keys [extract/type] :as extract}]
  (cond-> (select-keys extract extract-keys)

    (= :article type)
    (update-in [:source :authors]
               #(into [] (remove (fn [{:keys [short-name full-name]}]
                                   (and (empty? full-name)
                                        (empty? short-name))))
                      %))

    true (update :resources (fn [r]
                              (into []
                                    (remove #(every? empty? (vals %)))
                                    r)))

    ;; We have to do this in case someone fills in data for both a labnote and
    ;; an article. We don't select-keys, because there may be other stuff not in
    ;; the spec we want to keep around.
    (= :article type) (update :source #(apply dissoc % labnote-keys))
    (= :labnote type) (update :source #(apply dissoc % article-keys))))

(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   (let [extract (prepare-extract (get-in db [::extracts id :content]))]
     {:dispatch [::form-errors (:errors (validate-extract extract)) id]})))

;;;;; New extract init

(def extract-template
  {:selection []
   :content   {:tags      #{}
               :comments  [""]
               :source    {:authors [{:full-name ""}]}
               :resources [{:label "" :link ""}]
               :relations #{}}
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
   (let [k (if (vector? k) k [k])]
     (assoc-in db (concat [::extracts id :content] k) v))))

(re-frame/reg-event-db
 ::clear-form-element
 (fn [db [_ id k]]
   (update-in db (concat [::extracts id :content] (butlast k))
              dissoc (last k))))

(re-frame/reg-event-db
 ::form-errors
 (fn [db [_ errors id]]
   (assoc-in db [::extracts id :errors] errors)))

(defn sub-new [id] (fn [{:keys [entity] :as rel}]
    (if (= entity ::new)
      (assoc rel :entity id)
      rel)))

(defn imap
  "Converts a coll of immutables into a map from hashes to content."
  [imms]
  (into {}
        (map (fn [{:keys [hash content]}] [hash content]))
        imms))

(defn new-extract [prepared {:keys [figure-data relations comments figure]}]
  (let [imm      (util/immutable prepared)
        id       (:hash imm)
        rels     (map util/immutable (map (sub-new (:hash imm)) relations))
        comments (map (fn [t] (util/immutable {:text t :extract id}))
                      (remove empty? comments))]
    {:context    (merge
                  {id prepared}
                  (when figure
                    {(:hash figure-data) (:content figure-data)})
                  (imap rels)
                  (imap comments))
     :assertions (into [[:assert id]]
                       (remove nil?)
                       (concat
                        (map (fn [{:keys [hash]}] [:assert hash]) rels)
                        (map (fn [{:keys [hash]}] [:assert hash])
                             (remove empty? comments))))}))

(defn extract-changed? [old new]
  (when-not (= (:hash old) (:hash (util/immutable new)))
    (util/immutable
     (assoc new :history/previous-version (:hash old)))))

(defn update-relations [oldid newid relations]
  (if newid
    (into #{}
          (map (fn [{:keys [entity value] :as rel}]
                 (if (= entity oldid)
                   (assoc rel :entity newid)
                   (assoc rel :value newid))))
          relations)
    relations))

(def rr
  :openmind.components.extract.editor.relations/retracted-relations)

(def nr
  :openmind.components.extract.editor.relations/new-relations)

(defn changed? [original new meta]
  (not
   (and (= (:hash original) (:hash new))
        (empty? (rr meta))
        (empty? (nr meta)))))

(defn update-extract [original ometa new new-meta]
  (let [core-change? (not= (:hash original) (:hash new))
       ]
    (if core-change?
      (let [new' (util/immutable
                  (assoc (:content new)
                         :history/previous-version
                         (:hash original)))
            new-rels (imap (map util/immutable
                                (update-relations
                                 (:hash original) (:hash new') (nr new-meta))))
            r-rels (imap (map util/immutable
                              (update-relations
                               (:hash original) (:hash new') (rr new-meta))))]
        {:context (merge
                   {(:hash new') (:content new')}
                   (when-not (= (:figure (:content new'))
                                (:figure (:content original)))
                     {(:hash (:figure-data (:content new-meta)))
                      (:content (:figure-data (:content new-meta)))})
                   new-rels
                   r-rels)
         :assertions (into [[:retract (:hash original)]
                            [:assert (:hash new')]]
                           (concat
                            (map (fn [r] [:assert r]) (keys new-rels))
                            (map (fn [r] [:retract r]) (keys r-rels))))}))))

(re-frame/reg-event-fx
 ::update-extract
 (fn [{:keys [db]} [_ id]]
   (let [base                   (get-in db [::extracts id :content])
         author                 (get db :login-info)
         extract                (prepare-extract base)
         {:keys [valid errors]} (validate-extract extract)]
     (if errors
       {:dispatch [::form-errors errors id]}
       (if (= id ::new)
         {:dispatch [:->server [:openmind/tx
                                (assoc (new-extract extract base)
                                       :author author)]]}
         (let [original (events/table-lookup db id)
               imm      (util/immutable extract)
               ometa    (c/metadata db id)
               new-meta (get-in db [::extracts id])]
           (if (changed? original imm new-meta)
             {:dispatch [:->server
                         [:openmind/tx
                          (assoc (update-extract original ometa imm new-meta)
                                 :author author)]]}
             ;; no change, just go back to search
             {:dispatch-n [[:notify {:status  :warn
                                     :message "no changes to save"}]
                           [:navigate {:route :search}]]})))))))

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
     {:dispatch [:notify {:status  :error
                          :message "failed to create extract"}]})))

(re-frame/reg-event-fx
 :openmind/update-response
 (fn [cofx [_ {:keys [status message id]}]]
   (if (= :success status)
     {:dispatch-n [[::clear id]
                   [:notify {:status :success
                             :message
                             (str "changes submitted successfully\n"
                                  "it may take a moment for the changes"
                                  " to be reflected in your results.")}]
                   [:navigate {:route :search}]]}
     {:dispatch [:notify {:status :error :message "failed to save changes"}]})))

;;;; Components

(defn add-form-data [id {:keys [key] :as elem}]
  (-> elem
      (assoc :data-key id)
      (merge @(re-frame/subscribe [::form-input-data id key]))))

(defn collapsible-row [{:keys [initially-open?]}]
  (let [open? (r/atom initially-open?)]
    (fn [{:keys [open closed label] :as opts}]
      [:div
       [:div.left-col
        [:a.super.right.plh.prh
         {:on-click (fn [_] (swap! open? not))
          :title (if @open? "collapse" "expand")}
         [:div (when @open? {:style {:transform "rotate(90deg)"}}) "➤"]]
        [:span [:b label]]]
       [:div.right-col
        (if @open?
          [open opts]
          [closed opts])]])))

(defn tag-selector
  [{id :data-key}]
  [:div {:style {:min-height "40rem"}}
   [tags/tag-widget {:selection {:read [::editor-tag-view-selection id]
                                 :set  [::set-editor-selection id]}
                     :edit      {:read   [::editor-selected-tags id]
                                 :add    [::add-editor-tag id]
                                 :remove [::remove-editor-tag id]}}]])


(defn source-preview [{:keys [data-key] :as opts}]
  (let [{:keys [source extract/type]}
        @(re-frame/subscribe [::content data-key])]
    (when (and (= type :article) (:abstract source))
      [extract/source-content source])))

(re-frame/reg-event-fx
 ::article-lookup
 (fn [cofx [_ id url]]
   (let [last-searched (-> cofx :db ::extracts (get id) :content :source :url)]
     (when-not (= url last-searched)
       {:dispatch-n
        [[:->server [:openmind/article-lookup {:res-id id :term url}]]
                     [:openmind.components.window/spin]]}))))

(re-frame/reg-event-fx
 :openmind/article-details
 (fn [{:keys [db]} [_ {:keys [res-id term source]}]]
   (let [current (get-in db [::extracts res-id :content :article-search])]
     (if (and (= term current) (seq source))
       {:db         (-> db
                        (update-in [::extracts res-id :content :source]
                                   merge source)
                        (assoc-in [::extracts res-id ::found-article?] true))
        :dispatch-n [[:openmind.components.window/unspin]
                     [:notify {:status :success :message "article found"}]]}
       {:db         (-> db
                        (update-in [::extracts res-id :content] dissoc :source)
                        (update-in [::extracts res-id] dissoc ::found-article?))
        :dispatch-n [[:openmind.components.window/unspin]
                     [:notify {:status :error
                               :message
                               (str "we couldn't find that article\n"
                                    "please enter its details below")}]]}))))

;; N.B.: This has to be called as a function, never as a component since react
;; and I disagree on the meaning of the argument `:key`. I've gotten complacent
;; about language keywords while using clojure.
(defn select-button [{:keys [key value content label errors data-key]}]
  [:button.p1.text-white.border-round
   {:class    (if (= content value)
                "bg-dark-blue"
                "bg-blue")
    :on-click #(do (re-frame/dispatch
                    [::form-edit data-key key value])
                   (when errors
                     (re-frame/dispatch [::revalidate data-key])))}
   label])

(defn select-buttons [{:keys [content errors options] :as opts}]
  [:div.flex.flex-column
   (into
    [:div.flex.flex-start
     (when (and errors (not content))
       {:class "form-error border-round border-solid ph"
        :style {:width "max-content"}})]
    (interpose [:div.ml1]
               (map (fn [v] (select-button (merge opts v)))
                    options)))])


(defn source-selector [opts]
  [select-buttons (merge opts {:options [{:value :article
                                          :label "article"}
                                         {:value :labnote
                                          :label "lab note"}]})])

(defn peer-review-widget [opts]
  [select-buttons (merge opts {:options [{:value true
                                          :label "peer reviewed article"}
                                         {:value false
                                          :label "preprint"}]})])

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
  [{:component forms/text
    :label "institution"
    :placeholder "university, company, etc."
    :key [:source :institution]
    :required? true}
   {:component forms/text
    :label "lab"
    :placeholder "lab name"
    :key [:source :lab]
    :required? true}
   {:component forms/text
    :label "investigator"
    :placeholder "principle investigator"
    :key [:source :investigator]
    :required? true}
   {:component forms/date
    :label "observation date"
    :required? true
    :key [:source :observation/date]}])

(defn article-search [{:keys [data-key key content] :as opts}]
  (let [waiting? @(re-frame/subscribe [:openmind.components.window/spinner])]
    [:div.flex
     [forms/text opts]
     [:button.bg-blue.ph.mlh.text-white.border-round
      {:style (when waiting? {:cursor :wait})
       :on-click #(re-frame/dispatch [::article-lookup data-key content])}
      "search"]]))

(def source-details-inputs
  ;; For article extracts, we can autofill from pubmed, but if that doesn't
  ;; work, we want the title, author list, publication, and date.
  [{:key         [:article-search]
    :component   article-search
    :label       "find paper"
    :placeholder "article url or DOI"}
   {:component   forms/text
    :label       "link to article"
    :key         [:source :url]
    :placeholder "www.ncbi.nlm.nih.gov/pubmed/..."
    :required?   true}
   {:component peer-review-widget
    :label     "status"
    :title     "is this article peer reviewed, or a preprint?"
    :key       [:source :peer-reviewed?]
    :required? true}
   {:component forms/text
    :label     "doi"
    :key       [:source :doi]
    :required? true}
   {:component forms/textarea
    :label     "title"
    :key       [:source :title]
    :required? true}
   {:component forms/text-input-list
    :label     "authors"
    :key       [:source :authors]
    :sub-key   :full-name
    :required? true}
   {:component forms/date
    :label     "publication date"
    :key       [:source :publication/date]
    :required? true}
   {:component forms/textarea
    :label     "abstract"
    :key       [:source :abstract]}
   {:component forms/text
    :label     "journal"
    :key       [:source :journal]}
   {:component forms/text
    :label     "volume"
    :key       [:source :volume]}
   {:component forms/text
    :label     "issue"
    :key       [:source :issue]}])

(re-frame/reg-sub
 ::found-article?
 (fn [db [_ id]]
   (get-in db [::extracts id ::found-article?])))

(re-frame/reg-event-db
 ::force-edit
 (fn [db [_ id]]
   (assoc-in db [::extracts id ::found-article?] false)))

(defn compact-source-preview [opts]
  [collapsible-row
   (merge opts
          {:label "article details"
           :open  (fn [{:keys [content data-key]}]
                    [:div
                     [:button.right.p1.text-white.border-round.bg-blue
                      {:on-click #(re-frame/dispatch [::force-edit data-key])}
                      "edit"]
                     [extract/source-content content]])
           :closed (fn [{:keys [content]}]
                     [extract/citation content])})])

(defn source-details [{:keys [data-key content] :as opts}]
  (let [extract @(re-frame/subscribe [::content data-key])
        type    (:extract/type extract)]
    (if (and (= type :article)
             @(re-frame/subscribe [::found-article? data-key]))
      ;; FIXME: This is really kludgy
      [:div.flex.flex-column
       (input-row (merge (first source-details-inputs)
                         {:data-key data-key}
                         @(re-frame/subscribe
                           [::nested-form-data data-key [:article-search]])))
       [compact-source-preview opts]]
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
              [])))))

(defn comment-widget [{:keys [data-key] :as opts}]
  (if (= data-key ::new)
    [forms/textarea-list opts]
    [comment/comment-page-content data-key]))

(defn search-results [{:keys [key data-key]}]
  (let [results @(re-frame/subscribe [key])
        relations @(re-frame/subscribe
                    [:openmind.components.extract.editor.relations/active-relations
                     data-key])
        selected (into {}
                       (map (fn [{:keys [entity value] :as rel}]
                              [(if (= entity data-key) value entity) rel]))
                       relations)]
    (into [:div.flex.flex-column common/scrollbox-style]
          (into []
                (comp
                 (remove (fn [id] (contains? selected id)))
                 (map (fn [id] @(re-frame/subscribe [:content id])))
                 (map (fn [extract]
                        [extract/summary extract
                         {:controls (relations/related-buttons data-key)
                          :edit-link? false}])))
                results))))

(defn ellipsis []
  [:div.pl1 {:style {:padding-bottom "0.3rem"}}
   [:b "..."]])

(defn similar-extracts [{:keys [data-key] :as opts}]
  (let [content @(re-frame/subscribe [::content data-key])
        similar @(re-frame/subscribe [::similar])]
    (when (and (< 4 (count (:text content))) (seq similar))
      [collapsible-row (merge opts {:open            search-results
                                    :closed          ellipsis
                                    :label           "possibly similar extracts"
                                    :initially-open? true})])))

(defn shared-source [{:keys [data-key] :as opts}]
  (let [content      @(re-frame/subscribe [::content data-key])
        same-article @(re-frame/subscribe [::article-extracts])]
    (when (and (= :article (:extract/type content)) (seq same-article))
      [collapsible-row
       (merge opts
              {:open            (fn [] [:div.pl1 "placeholder"])
               :closed          (fn [] [:div.pl1 "placeholder"])
               :label           "extracts based on the same article"
               :initially-open? true})])))

(defn extract-search-results [{:keys [data-key]}]
  [search-results {:key ::related-search-results :data-key data-key}])

(defn resources-widget [{:keys [data-key content errors key] :as opts}]
  [:div.flex.full-width
   (into [:div.full-width]
         (map-indexed
          (fn [i c]
            (let [err (get errors i)]
              [:div.flex
               [:span.pr1
                {:style {:flex-grow 2}}
                [forms/text {:content  (:label c)
                       :key      [key i :label]
                       :placeholder "type, e.g. data, code, toolkit"
                       :data-key data-key
                       :errors   (:label err)}]]
               [:span
                {:style {:flex-grow 2}}
                [forms/text {:content (:link c)
                       :key [key i :link]
                       :placeholder "link to repository"
                       :data-key data-key
                       :errors (:link err)}]]]))
          content))
   [:a.plh.ptp.bottom-right {:on-click
                     (fn [_]
                       (if (nil? content)
                         (re-frame/dispatch [::form-edit data-key [key] [{}]])
                         (re-frame/dispatch
                          [::form-edit data-key
                           [key (count content)] {}])))}
    "[+]"]])

(def extract-creation-form
  [{:component   forms/textarea
    :label       "extract"
    :rows 4
    :on-change   #(when (< 4 (count %))
                    (re-frame/dispatch
                     [:openmind.components.search/search-request
                      {:term %} ::similar]))
    :key         :text
    :required?   true
    :placeholder "what have you discovered?"}
   {:component   similar-extracts
    :key         ::similar
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
   {:component   figure/figure-select
    :label       "figure"
    :key         :figure
    :placeholder [:span [:b "choose a file"] " or drag it here"]}
   {:component   resources-widget
    :label       "repos"
    :placeholder "link to any code / data that you'd like to share"
    :key         :resources}
   {:component   comment-widget
    :label       "comments"
    :key         :comments
    :placeholder "anything you think is important"}
   {:component   forms/text
    :placeholder "find extract that might be related to this one"
    :on-change   #(re-frame/dispatch
                   (if (< 2 (count %))
                     [:openmind.components.search/search-request
                      {:term %} ::related-search-results]
                     [::clear-related-search]))
    :label       "search for related extracts"
    :key         :search}
   {:component extract-search-results}
   {:component relations/related-extracts
    :label     "related extracts"
    :sublabel  relations/relation-summary
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
                     (re-frame/dispatch [::clear id])
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
                            (re-frame/dispatch [::clear nil])
                            (let [id (edn/read-string id)]
                              (when-not @(re-frame/subscribe [::extract id])
                                (re-frame/dispatch
                                 [:ensure id [::editing-copy id]]))))}]}]])
