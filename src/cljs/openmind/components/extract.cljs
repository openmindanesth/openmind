(ns openmind.components.extract
  (:require [clojure.string :as string]
            [openmind.components.comment :as comment]
            [openmind.components.extract.core :as core]
            [openmind.components.tags :as tags]
            [openmind.edn :as edn]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

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
      [:div.bg-white.p1.border-round.border-solid
       (into [:div.flex.flex-column]
             (map (fn [t]
                    [tag-display tag-lookup t]))
             (get (tree-group branches) tag-root))])))


(defn comments-hover [id]
  [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh
   [comment/comment-page-content id]])

(defn figure-hover [figures]
  (when (seq figures)
    (into [:div.border-round.border-solid.bg-white]
          (map (fn [id]
                 (let [{:keys [image-data caption] :as fig}
                       @(re-frame/subscribe [:content id])]
                   [:div
                    (when image-data
                      [:img.relative.p1 {:src image-data
                                         :style {:max-width "95%"
                                                 :max-height "50vh"
                                                 :left "2px"
                                                 :top "2px"}}])
                    (when (seq caption)
                      [:div.p1 caption])])))
          figures)))

(defn edit-link [hash]
  ;; Users must be logged in to edit extracts
  (when @(re-frame/subscribe [:openmind.subs/login-info])
    [:div.right.relative.text-grey.small.ph.pr1
     [:a {:on-click #(re-frame/dispatch [:navigate
                                         {:route :extract/edit
                                          :path  {:id hash}}])}
      "edit"]]))

(defn citation [authors date]
  (let [full (apply str (interpose ", " authors))]
    (str
     (if (< (count full) 25) full (str (first authors) ", et al."))
     " (" date ")")))

(defn source-link [{:keys [authors url publication/date]}]
  (let [text (if (seq authors)
               (citation authors (first (string/split date #"[- ]")))
               url)]
    (when text
      [:a.link-blue {:href url} text])))

(defn source-content [{:keys [authors publication/date journal
                            abstract doi title volume issue]}]
  [:div
   [:h2 title]
   [:span.smaller.pb1
    [:span (str "(" date ")")]
    [:span.plh  journal
     (when volume
       (str " " volume "(" issue ")"))]
    [:span.plh " doi: " doi]
    [:em.small.pl1 (apply str (interpose ", " authors))]]
   [:p abstract]])

(defn source-hover [source]
  [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh
   {:style {:max-width "calc(60vh)"}}
   [source-content source]])

(defn ilink [text route]
  [:a {:on-click #(re-frame/dispatch [:navigate route])}
        text])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn hover-link [link float-content
                  {:keys [orientation style force?]}]
  (let [hover? (reagent/atom false)]
    (fn [text float-content {:keys [orientation style force?]}]
      [:div.plh.prh
       {:on-mouse-over #(reset! hover? true)
        :on-mouse-out  #(reset! hover? false)
        :style         {:cursor :pointer}}
       [:div.link-blue link]
       (when float-content
         ;; dev hack
         (when (or force? @hover?)
           [:div.absolute.ml1.mr1
            {:style (merge
                     style
                     (cond
                       (= :left orientation)  {:left "0"}
                       (= :right orientation) {:right "0"}
                       :else                  {:transform "translateX(-50%)"}))}
            [:div.relative.z101
             {:style {:max-width     "calc(75vw)"
                      :on-mouse-over halt
                      :on-mouse-out  halt}}
             float-content]]))])))

(re-frame/reg-sub
 ::relations
 (fn [[_ id]]
   (re-frame/subscribe [:content id]))
 (fn [meta [_ id]]
   (:relations meta)))

(def type-chars
  {:labnote    {:char  "⃤"
                :title "lab note"}
   :unreviewed {:char  "◯"
                :title "extract from unreviewed article"}
   :article    {:char  "⬤"
                :title "extract from peer reviewed article"}})

(defn type-indicator [{:keys [extract/type]}]
  (let [{:keys [char title]} (get type-chars type)]
    [:span.pr2.blue
     {:title title
      :style {:cursor :help}}
     char]))

(defn metadata [{:keys [time/created author] :as extract}]
  [:span.small.no-wrap
   [type-indicator extract]
   [:a.unlink {:href (str "https://orcid.org/" (:orcid-id author))}
        [:span.text-dark-grey [:b (:name author)]]]
   [:span.pl1 [:em (.format comment/dateformat created)]]])

(declare summary)

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
      [metadata extract]]]))

(defn related-extracts [id]
  (let [metaid    @(re-frame/subscribe [:extract-metadata id])
        relations (when metaid @(re-frame/subscribe [::relations metaid]))]
    (when relations
      (into
       [:div.flex.flex-column.bg-plain
        {:style {:width "calc(75vh)"}}]
       (map-indexed
        (fn [i {:keys [entity attribute value]}]
          (let [other (if (= entity id) value entity)
                odata @(re-frame/subscribe [:content other])]
            (with-meta
              [summary odata
               {:edit-link?   false
                :pb0?         true
                :i            i
                :c            (count relations)
                :meta-display (relation-meta attribute)}]
              {:key (str id "-" attribute "-" other)}))))
       relations))))

(defn summary [{:keys [text author source figures tags hash] :as extract}
               & [{:keys [edit-link? controls meta-display pb0? c i] :as opts
                   :or   {meta-display metadata
                          edit-link?   true}}]]
  [:div.search-result.ph
   {:style (merge {:height :min-content}
                  (when pb0?
                    {:margin-bottom "1px"})
                  (when (and i c (= i c))
                    {:margin-bottom 0}))}
   (when edit-link?
     [edit-link hash])
   (when meta-display
     [:div.ph [meta-display extract]])
   [:div.break-wrap.ph text]
   (when controls
     [controls extract])
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [hover-link [ilink "comments" {:route :extract/comments
                                    :path  {:id hash}}]
      [comments-hover hash]
      {:orientation :left}]
     [hover-link "history"]
     [hover-link "related" [related-extracts hash]
      ;; N.B.: Do not add :force? true here, it will launch an infinite render.
      {:orientation :right}]
     [hover-link "tags" [tag-hover tags] ]
     [hover-link [ilink "figure" {:route :extract/figure
                                  :path  {:id hash}}]
      [figure-hover figures]
      {:orientation :right}]
     [hover-link [source-link source] [source-hover source]
      {:orientation :right}]]]])

;;;;; Figure page

(defn figure-page
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [id                (edn/read-string id)
        {:keys [figures]} @(re-frame/subscribe [:content id])]
    (if (seq figures)
      (into [:div]
            (map (fn [fid]
                   (let [{:keys [image-data caption]}
                         @(re-frame/subscribe [:content fid])]
                     [:div
                      [:img.p2 {:style {:max-width "95%"} :src image-data}]
                      [:p.pl1.pb2 caption]])))
            figures)
      [:span.p2 "This extract doesn't have an associated figure."])))

;;;;; Comments

(def routes
  [["/:id/figure"
    {:name :extract/figure
     :parameters {:path {:id any?}}
     :component  figure-page
     :controllers core/extract-controllers}]])
