(ns openmind.components.extract
  (:require [clojure.string :as string]
            [openmind.components.comment :as comment]
            [openmind.components.extract.core :as core]
            [openmind.components.tags :as tags]
            [openmind.edn :as edn]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [reagent.dom :as rdom]))

;;;;; Extract summary (search result or hover view)

(defn tg1 [bs]
  (into {}
        (comp
         (map (fn [[k vs]]
                [k (map rest vs)]))
         (remove (comp nil? first)))
        (group-by first bs)))

(defn tree-group
  "Given a sequence of sequences of tags, return a prefix tree on those
  sequences."
  [bs]
  (when (seq bs)
    (into {} (map (fn [[k v]]
                    [k (tree-group v)]))
          (tg1 bs))))

(defn tag-display [tag-lookup [k children]]
  (when k
    [:div.flex
     [:div {:class (str "bg-blue border-round p1 mbh mrh "
                        "text-white flex flex-column flex-centre")}
      [:div
       (:name (tag-lookup k))]]
     (into [:div.flex.flex-column]
           (map (fn [b] [tag-display tag-lookup b]))
           children)]))

(defn tag-hover [tags]
  (when (seq tags)
    (let [tag-lookup @(re-frame/subscribe [::tags/tag-lookup])
          tag-root @(re-frame/subscribe [:tag-root])
          branches   (->> tags
                          (map tag-lookup)
                          (map (fn [{:keys [id parents]}] (conj parents id))))]
      [:div.p1
       (into [:div.flex.flex-column]
             (map (fn [t]
                    [tag-display tag-lookup t]))
             (get (tree-group branches) tag-root))])))


(defn comments-hover [id]
  [:div.flex.flex-column.p1
   [comment/comment-page-content id]])

(defn figure-img [figure {:keys [style]}]
  (when figure
    (let [{:keys [image-data]} @(re-frame/subscribe [:content figure])]
      (when image-data
        [:img {:style style
               :src   image-data}]))))

(defn figure-hover [figure]
  (when figure
    (let [{:keys [caption]} @(re-frame/subscribe [:content figure])]
      [:div
       [:div.p1
        [figure-img figure {:style {:max-width  "95%"
                                    :max-height "50vh"}}]]
       (when (seq caption)
         [:div.p1 caption])])))

(defn edit-link [hash]
  ;; Users must be logged in to edit extracts
  (when @(re-frame/subscribe [:openmind.subs/login-info])
    [:div.right.text-grey.small.ph.pr1
     [:a {:on-click #(re-frame/dispatch [:navigate
                                         {:route :extract/edit
                                          :path  {:id hash}}])}
      "edit"]]))

(defn citation [authors date]
  (let [full (apply str (interpose ", " (map :full-name authors)))]
    (str
     (if (< (count full) 25) full (str (:short-name (first authors)) ", et al."))
     " (" date ")")))

(def dateformat
  (new (.-DateTimeFormat js/Intl) "en-GB"
       (clj->js {:year   "numeric"
                 :month  "long"
                 :day    "numeric"})))

(defn article-source-link [{:keys [authors url publication/date]}]
  (let [text (when (seq authors)
               (citation authors (.getFullYear date)))]
    text))

(defn labnote-source-link [{:keys [investigator observation/date]}]
   (str investigator " (" (.getFullYear date) ")"))

(defn source-link [type source]
  (case type
    :article [article-source-link source]
    :labnote [labnote-source-link source]
    nil))

(defn source-content [{:keys [authors publication/date journal url
                            abstract doi title volume issue]}]
  [:div
   [:h2 [:a.link-blue {:href url} title]]
   [:span.smaller.pb1
    [:span (str "(" (.format dateformat date) ")")]
    [:span.plh  journal
     (when volume
       (str " " volume "(" issue ")"))]
    [:span.plh " doi: " doi]
    [:em.small.pl1 (apply str (interpose ", " (map :full-name authors)))]]
   [:p abstract]])

(defn source-hover [source]
  [:div.flex.flex-column.p1
   [source-content source]])

(defn ilink [text route]
  [:a {:on-click #(re-frame/dispatch [:navigate route])}
        text])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn cljsify [o]
  {:width  (.-width o)
   :height (.-height o)
   :x      (.-x o)
   :y      (.-y o)})

(defn size-reflector [com size]
  (letfn [(getsize [this] (when-let [node (rdom/dom-node this)]
                            (let [box (-> node
                                           .getBoundingClientRect
                                           cljsify
                                           (assoc :top (.-offsetTop node)))]
                              (when-not (= box @size)
                                (reset! size box)))))]
    (reagent/create-class
     {:reagent-render       (fn [] com)
      :component-did-mount  getsize
      :component-did-update getsize})))

;; REVIEW: Eegahd
(defn fit-to-screen [{:keys [width height]} {:keys [x y top]}]
  (let [de (.-documentElement js/document)
        vh (.-clientHeight de)
        vw (.-clientWidth de)]
    (when (and width height)
      (merge {:right :unset
              :left  :unset}
             (cond
               (< vw (+ x (/ width 2))) {:right 0}
               (< (- x (/ width 2)) 0)  {:left 0}
               :else                    {:transform "translateX(-50%)"})
             ;; HACK: I don't see any way to not use a concrete pixel value, but
             ;; it isn't ideal.
             (when (< vh (+ height y 32))
               {:top (str "calc(100vh - 1.5rem + "
                          top "px - " y "px - " height "px) ")})
             (when (< (* 0.8 vh) height)
               ;; Mixing CSS with control data!
               {:will-overflow true})))))

(defn hover-link [text float-content
                  {:keys [orientation style hover?]}]
  (let [open?      (reagent/atom false)
        float-size (reagent/atom nil)
        link-size  (reagent/atom false)]
    (fn [text float-content {:keys [orientation style]}]
      (let [wrapper (size-reflector float-content float-size)
            link    (size-reflector [:div.link-blue text] link-size)]
        [:div.plh.prh
         (merge
          {:on-mouse-leave #(reset! open? false)}
          (if hover?
            {:on-mouse-over #(reset! open? true)}
            {:on-click #(reset! open? true)
             :style    {:cursor :pointer}}))
         [link]
         (when float-content
           ;; dev hack
           (when @open?
             (let [position (fit-to-screen @float-size @link-size)]
               [:div.absolute.ml1.mr1.hover-link.border-round.bg-plain.border-solid
                {:style         (merge {:padding "0.1rem"
                                        :cursor  :default
                                        :z-index 1001}
                                       (when orientation
                                         {orientation 0})
                                       position
                                       (when-not @float-size
                                         {:display :none}))
                 :on-mouse-over halt
                 :on-mouse-out  halt}
                [:div (when (:will-overflow position)
                        {:style {:height          "calc(80vh)"
                                 :scrollbar-width :thin
                                 :overflow-y      :auto}})
                 [wrapper]]])))]))))


(defn thumbnail [eid figure]
  [hover-link
   [figure-img figure {:style {:height        "100%"
                               :width         "100%"
                               :margin-left   "-0.6em"
                               :margin-bottom "-0.6em"
                               :margin-top    "-0.1em"}}]
   [figure-hover figure]
   {:orientation :left
    :hover?      true
    :route       {:route :extract/figure :path {:id eid}}}])

(re-frame/reg-sub
 ::relations
 (fn [[_ id]]
   (re-frame/subscribe [:extract-metadata id]))
 (fn [meta [_ id]]
   (:relations meta)))

(def type-chars
  {:labnote    {:char  [:span {:style {:padding-left "0.8rem"
                                       :padding-right "0.45rem"}} "⃤"]
                :title "lab note"}
   :unreviewed {:char  "◯"
                :title "extract from unreviewed article"}
   :reviewed    {:char  "⬤"
                :title "extract from peer reviewed article"}})

(defn type-hack
  "We're jumping through hoops for historical reasons here. This will need to be
  cleaned up."
  [{:keys [extract/type source]}]
  (let [k (if (= type :labnote)
            :labnote
            (if (:peer-reviewed? source)
              :reviewed
              :unreviewed))]
    (get type-chars k)))

(defn type-indicator [extract]
  (let [{:keys [char]} (type-hack extract)]
    [:span.blue char]))

(defn metadata [{:keys [time/created author extract/type] :as extract}]
  [:div.flex.flex-column.p1.no-wrap
   [:div.pbh
    [:a.unlink {:href (str "https://orcid.org/" (:orcid-id author))}
     [:span.text-black (:name author)]]]
   [:div.pbh [:em (.format comment/dateformat created)]]
   [:div.pbh
    [:span "no voting yet"]]
   [:div
    [type-indicator extract]
    [:span.plh.blue (:title (type-hack extract))]]])

(declare summary)

(def relation-names
  {:contrast  "in contrast to"
   :confirmed "confirmed by"
   :related   "related to"})

(defn relation-meta [attribute]
  (fn [extract]
    [:div.ph
     [:span.border-round.border-solid.ph.mr1.bg-light-blue.no-wrap
      (get relation-names attribute)]]))

(defn related-extracts [id]
  (let [relations @(re-frame/subscribe [::relations id])]
    (when (seq relations)
      (into
       [:div.flex.flex-column.bg-plain]
       (map-indexed
        (fn [i {:keys [entity attribute value]}]
          (let [other (if (= entity id) value entity)
                odata @(re-frame/subscribe [:content other])]
            (with-meta
              [summary odata
               {:edit-link? false
                :pb0?       true
                :i          i
                :c          (count relations)
                :controls   (relation-meta attribute)}]
              {:key (str id "-" attribute "-" other)}))))
       relations))))

(defn history-control [author created]
  (fn []
    [:div.nowrap
     [:span "edited by "]
     [comment/author-attrib author]
     [:span " on "]
     [:em (.format comment/dateformat created)]]))

(defn edit-history [id]
  (let [history (:history @(re-frame/subscribe [:extract-metadata id]))]
    (when (seq history)
      (into [:div.flex.flex-column.bg-plain]
            (map-indexed
             (fn [i {:keys [author time/created history/previous-version]}]
               (let [pv @(re-frame/subscribe [:content previous-version])]
                 (with-meta
                   [summary pv {:edit-link? false
                                :pb0? true
                                :i i
                                :c (count history)
                                :controls (history-control author created)}]
                   {:key (str "previous-" (.-hash-string previous-version))}))))
            (reverse history)))))

(defn summary [{:keys [text author source figure tags hash] :as extract}
               & [{:keys [edit-link? controls pb0? c i] :as opts
                   :or   {edit-link? true}}]]
  [:div.search-result.ph.flex
   {:style (merge {:height :min-content}
                  (when pb0?
                    {:margin-bottom "1px"})
                  (when (and i c (= i c))
                    {:margin-bottom 0}))}
   [:div {:style {:width      "10rem"
                  :max-height "8rem"
                  :overflow   :hidden}}
    [thumbnail hash figure]]
   [:div {:style {:flex 1}}
    (when edit-link?
      [edit-link hash])
    (when controls
      [controls extract])
    [:div.flex.flex-column.space-between
     [:div.break-wrap.ph text]
     [:div.pth.flex.full-width
      [:div
       [hover-link [type-indicator extract] [metadata extract]
        {:orientation :left
         :hover?      true}]]
      [:div.flex.flex-wrap.space-between.full-width

       [hover-link "comments"
        [comments-hover hash]
        {:orientation :left}]
       [hover-link "history" [edit-history hash]]
       [hover-link "related" [related-extracts hash]
        {:orientation :right}]
       [hover-link "tags" [tag-hover tags] ]

       [hover-link [source-link (:extract/type extract) source]
        [source-hover source]
        {:orientation :right}]]]]]])
