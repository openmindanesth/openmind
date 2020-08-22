(ns openmind.components.extract
  (:require [clojure.string :as string]
            [openmind.components.comment :as comment]
            [openmind.components.common :as common]
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


(defn resource-hover [resources]
  (when (seq resources)
    (into
     [:div.flex.flex-column.p1]
     (map (fn [{:keys [label link]}]
            (let [abslink (if (string/starts-with? link "http")
                         link
                         (str "http://" link))]
              [:h4
               [:a.link-blue {:href abslink}
                [:b.text-black label ":"]
                [:span.plh link]]])))
     resources)))

(defn comments-hover [id]
  [:div.flex.flex-column.p1
   [comment/comment-page-content id]])

(defn figure-img [figure {:keys [style on-load id]}]
  (when figure
    (let [{:keys [image-data]} @(re-frame/subscribe [:content figure])]
      (when image-data
        [:img (merge {:style style
                      :src   image-data}
                     (when id
                       {:id id})
                     (when on-load
                       {:on-load on-load}))]))))

(defn figure-caption [figure]
  (let [{:keys [caption]} @(re-frame/subscribe [:content figure])]
    (when (seq caption)
      [:div.p1 caption])))

(defn figure-hover [figure]
  (when figure
    [:div
     [:div.p1
      [figure-img figure {:style {:max-width  "90vw"
                                  :max-height "50vh"}}]]
     [figure-caption figure]]))

(defn edit-link [hash]
  ;; Users must be logged in to edit extracts
  (when @(re-frame/subscribe [:openmind.subs/login-info])
    [:div.right.text-grey.small.ph.pr1
     [:a {:on-click #(re-frame/dispatch [:navigate
                                         {:route :extract/edit
                                          :path  {:id hash}}])}
      "edit"]]))

(defn date-year [d]
  (if (inst? d)
    (.getFullYear d)
    " - "))

(defn citation [{:keys [authors publication/date]}]
  (let [full (->> authors
                  (map (fn [{:keys [short-name full-name]}]
                         (or full-name short-name)))
                  (interpose ", ")
                  (apply str))]
    (str
     (if (< (count full) 25)
       full
       (let [{:keys [short-name full-name]} (first authors)]
         (str (or short-name full-name) ", et al.")))
     " (" (date-year date) ")")))

(def dateformat
  (new (.-DateTimeFormat js/Intl) "en-GB"
       (clj->js {:year   "numeric"
                 :month  "long"
                 :day    "numeric"})))

(defn article-source-link [{:keys [authors] :as source}]
  (let [text (when (seq authors)
               (citation source))]
    text))

(defn labnote-source-link [{:keys [investigators observation/date investigator]}]
  ;; FIXME: investigator and investigators. vestigial compatibility.
  (if investigator
   (str investigator
        " (" (date-year date) ")")
   (str (:name (first investigators))
        (when (< 1 (count investigators))
          " et al. ")
        " (" (date-year date) ")")))

(defn source-link [type source]
  [:div
   {:style {:text-align :right
            :min-width  "20ch"}}
   (case type
     :article (article-source-link source)
     :labnote (labnote-source-link source)
     nil)])

(defn source-content [{:keys [authors publication/date journal url
                            abstract doi title volume issue]}]
  [:div
   [:h2 {:style {:margin-top 0}} [:a.link-blue {:href url} title]]
   [:span.smaller.pb1
    [:span (str "(" (.format dateformat date) ")")]
    [:span.plh  journal
     (when volume
       (str " " volume "(" issue ")"))]
    [:span.plh " doi: " doi]
    [:em.small.pl1 (->> authors
                        (map (fn [{:keys [full-name short-name]}]
                               (or full-name short-name)))
                        (interpose ", ")
                        (apply str))]]
   [:p abstract]])

(defn labnote-source-content [{:keys [lab investigators institution
                                      investigator observation/date]}]
  ;; FIXME: investigator and investigators. vestigial compatibility.
  [:div.pbh
   [:div.small.pbh
    [:span (str "(" (.format dateformat date) ")")]
    [:span.plh lab]
    [:b.plh institution]]
   [:div.small "investigators: "
    (if investigator
      [:em investigator]
      [:em (apply str (interpose "; " (map :name investigators)))])]])

(defn source-hover [type source]
  [:div.flex.flex-column.p1
   (case type
     :article [source-content source]
     :labnote [labnote-source-content source]
     nil)])

(defn ilink [text route]
  [:a {:on-click #(re-frame/dispatch [:navigate route])}
        text])

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
(defn fit-to-screen [{:keys [width height]} {:keys [x y top]} & [thumb?]]
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
             ;;
             ;; The current maximum height of an extract is 10rem plus 1rem
             ;; padding (half top, half bottom). We don't care about the top, so
             ;; 170px. Sloppy, bound to break somewhere. Seems to work.
             (when (< vh (+ height y (if thumb? 170 24)))
               {:top (str "calc(100vh - 1.5rem + "
                          top "px - " y "px - min(80vh , "  height "px)) ")})
             (when (< (* 0.8 vh) height)
               ;; Mixing CSS with control data!
               {:will-overflow true})))))

(defn hover-link [text float-content
                  {:keys [orientation style hover? hide-if thumb?]}]
  (let [open?      (reagent/atom false)
        float-size (reagent/atom nil)
        link-size  (reagent/atom false)]
    (fn [text float-content {:keys [orientation style]}]
      (if (and (fn? hide-if) (hide-if))
        [:div.text-grey
         {:style {:height "100%"
                  :cursor :default}}
         text]
        (let [wrapper (size-reflector float-content float-size)
              link    (size-reflector [:div.link-blue
                                       text]
                                      link-size)]
          [:div.plh.prh
           (merge
            {:on-mouse-leave #(reset! open? false)}
            (if hover?
              {:on-mouse-over #(reset! open? true)}
              {:on-click #(swap! open? not)
               :style    {:cursor :pointer}}))
           [link]
           (when float-content
             (when @open?
               (let [position (fit-to-screen @float-size @link-size thumb?)]
                 [:div.absolute.ml1.mr1.hover-link.border-round.bg-plain.border-solid
                  {:style (merge {:padding "0.1rem"
                                  :cursor  :default
                                  :z-index 1001}
                                 (when orientation
                                   {orientation 0})
                                 position
                                 (when-not @float-size
                                   {:display :none}))

                   :on-mouse-over common/halt
                   :on-mouse-out  common/halt}
                  [:div (when (:will-overflow position)
                          {:style {:height          "calc(80vh)"
                                   :scrollbar-width :thin
                                   :overflow-y      :auto}})
                   [wrapper]]])))])))))

(defn sizes [id]
  (when-let [node (.getElementById js/document id)]
    (when-let [parent (.-parentNode node)]
      [(.-clientHeight node) (.-clientHeight parent)])))

(re-frame/reg-event-fx
 ::recentre
 (fn [{:keys [db]} [_ id]]
   (when-let [[h ph] (sizes id)]
     (let [offset (quot (- ph h) 2)]
       (when (pos? offset)
         {:db (assoc-in db [::thumbnail-offsets id] offset)})))))

(re-frame/reg-sub
 ::offsets
 (fn [db _]
   (::thumbnail-offsets db)))

(re-frame/reg-sub
 ::offset
 :<- [::offsets]
 (fn [offsets [_ id]]
   (get offsets id)))

(defn thumbnail [eid figure & [opts]]
  (let [id (name (gensym "thumbnail"))]
    (fn [eid figure]
      (let [offset @(re-frame/subscribe [::offset id])
            size   @(re-frame/subscribe [:responsive-size])]
        [hover-link
         [:div
          {:style {:height "100%"}}
          [figure-img figure {:id      id
                              :on-load #(re-frame/dispatch [::recentre id])
                              :style   (merge {:max-width  "10rem"
                                               :max-height "10rem"
                                               :display    :block
                                               :margin     :auto}
                                              opts
                                              (when offset
                                                {:transform (str "translateY("
                                                                 offset
                                                                 "px)")}))}]]
         (if (contains? #{:mobile :tablet} size)
           [figure-caption figure]
           [figure-hover figure])
         {:orientation :left
          :thumb?      true
          :route       {:route :extract/figure :path {:id eid}}}]))))

(re-frame/reg-sub
 ::relations
 (fn [[_ id]]
   (re-frame/subscribe [:extract-metadata id]))
 (fn [meta [_ id]]
   (:relations meta)))

(def type-chars
  {:labnote    {:char  [:span {:style {:padding-left  "0.8rem"
                                       :padding-right "0.45rem"}} "⃤"]
                :title "lab note"}
   :unreviewed {:char  "◯"
                :title "extract from unreviewed article"}
   :reviewed   {:char  "⬤"
                :title "extract from peer reviewed article"}})

(defn type-hack
  "We're jumping through hoops for historical reasons here. This will need to be
  cleaned up."
  [{:keys [extract/type source]}]
  (let [k (if (= type :labnote)
            :labnote
            (if (false? (:peer-reviewed? source))
              :unreviewed
              :reviewed))]
    (get type-chars k)))

(defn type-indicator [extract]
  (let [{:keys [char]} (type-hack extract)]
    [:span.blue char]))

(defn metadata [{:keys [time/created author extract/type hash] :as extract}]
  [:div
   [:span.right [edit-link hash]]
   [:div.flex.flex-column.p1.no-wrap
    [:div.pbh
     [:a.unlink {:href (str "https://orcid.org/" (:orcid-id author))}
      [:span.text-black (:name author)]]]
    [:div.pbh [:em (.format comment/dateformat created)]]
    [:div.pbh
     [:span "no voting yet"]]
    [:div
     [type-indicator extract]
     [:span.plh.blue (:title (type-hack extract))]]]])

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
                                :pb0?       true
                                :i          i
                                :c          (count history)
                                :controls   (history-control author created)}]
                   {:key (str "previous-" (.-hash-string previous-version))}))))
            (reverse history)))))

(defn summary [{:keys [text author source figure tags hash resources]
                :as   extract}
               & [{:keys [edit-link? controls pb0? c i] :as opts
                   :or   {edit-link? true}}]]
  (let [size @(re-frame/subscribe [:responsive-size])]
    (if (contains? #{:mobile :tablet} size)
      [:div.flex-column.search-result.ph
       [:div.flex.space-around
        [thumbnail hash figure {:max-height "100%"
                                :max-width "100%"}]]
       [:div.break-wrap.ph
        {:style {:margin      :auto
                 :margin-left 0}}
          text]
       [:div.pth.full-width.flex
        {:style {:flex-wrap       :wrap
                 :gap             "0.7rem"
                 :justify-content :space-evenly}}
        [hover-link [type-indicator extract] [metadata extract]]
        [hover-link "comments"
         [comments-hover hash]]
        [hover-link "history" [edit-history hash]
         {:hide-if
          #(empty? (:history @(re-frame/subscribe [:extract-metadata hash])))}]
        [hover-link "related" [related-extracts hash]
         {:hide-if
          #(empty? (:relations @(re-frame/subscribe [:extract-metadata hash])))}]
        [hover-link "repos" [resource-hover resources]
         {:hide-if #(empty? resources)}]
        [hover-link "tags" [tag-hover tags]
         {:hide-if #(empty? tags)}]
        [hover-link [source-link (:extract/type extract) source]
         [source-hover (:extract/type extract) source]]]]
      [:div.search-result.flex.ph
       {:style (merge {:height :min-content}
                      (when pb0?
                        {:margin-bottom "1px"})
                      (when (and i c (= i c))
                        {:margin-bottom 0}))}
       [:div.flex-column.space-around
        {:style {:width "10rem"}}
        [thumbnail hash figure]]
       [:div {:style {:flex       1
                      :min-height "100%"}}
        [:div.flex.flex-column.space-between
         (when (= :desktop size)
           {:style {:height "100%"}})
         (when controls
           [controls extract])
         [:div.break-wrap.ph
          {:style {:margin      :auto
                   :margin-left 0}}
          text]
         [:div.pth.flex.full-width
          [:div
           [hover-link [type-indicator extract] [metadata extract]
            {:orientation :left}]]
          [:div.flex.flex-wrap.space-between.full-width
           [hover-link "comments"
            [comments-hover hash]
            {:orientation :left}]
           [hover-link "history" [edit-history hash]
            {:hide-if
             #(empty? (:history @(re-frame/subscribe [:extract-metadata hash])))}]
           [hover-link "related" [related-extracts hash]
            {:hide-if
             #(empty? (:relations @(re-frame/subscribe [:extract-metadata hash])))}]
           [hover-link "repos" [resource-hover resources]
            {:hide-if #(empty? resources)}]
           [hover-link "tags" [tag-hover tags]
            {:hide-if #(empty? tags)}]
           [hover-link [source-link (:extract/type extract) source]
            [source-hover (:extract/type extract) source]
            {:orientation :right}]]]]]])))
