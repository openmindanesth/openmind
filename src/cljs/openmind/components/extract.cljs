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

(defn edit-link [author hash]
  (when-let [login @(re-frame/subscribe [:openmind.subs/login-info])]
    (when true #_(= author login)
      [:div.right.relative.text-grey.small
       {:style {:top "-2rem" :right "1rem"}}
       [:a {:on-click #(re-frame/dispatch [:navigate
                                           {:route :extract/edit
                                            :path  {:id hash}}])}
        "edit"]])))

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
                            abstract doi title]}]
  [:div
   [:h2 title]
   [:span.smaller.pb1
    [:span (str "(" date ")")]
    [:span.plh  journal]
    [:span.plh " doi: " doi]
    [:em.small.pl1 (apply str (interpose ", " authors))]]
   [:p abstract]])

(defn source-hover [source]
  [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh
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
             {:style {:max-width     "45rem"
                      :on-mouse-over halt
                      :on-mouse-out  halt}}
             float-content]]))])))

(defn metadata [{:keys [time/created author] :as extract}]
  [:div
   [:a.unlink {:href (str "https://orcid.org/" (:orcid-id author))}
        [:span.text-black.small.no-wrap [:b (:name author)]]]
   [:span.pl1.no-wrap [:em (.format comment/dateformat created)]]])

(defn summary [data opts]
  (let [meta-open? (reagent/atom false)]
    (fn [{:keys [text author source comments figures tags hash] :as extract}
         & [{:keys [edit-link? controls] :as opts}]]
      [:div.search-result.ph
       {:style         {:height :min-content}
        :on-mouse-over #(reset! meta-open? true)
        :on-mouse-out  #(reset! meta-open? false)}
       (when @meta-open?
         [:div {:style {:height   0
                        :position :relative}}
          [:div.search-result.bg-plain.pt1.pl1
           {:style {:border-bottom :none
                    :border-radius "0.5rem 0.5rem 0 0"
                    :opacity       0.85
                    :position      :absolute
                    :top           "-2.5rem"
                    :left          "calc(-0.5rem - 1px)"
                    :width         "100%"
                    :height        "2rem"}}
           [metadata extract]]])
       [:div.break-wrap.ph text]
       (when edit-link?
         [edit-link author hash])
       (when controls
         [controls extract])
       [:div.pth
        [:div.flex.flex-wrap.space-evenly
         [hover-link [ilink "comments" {:route :extract/comments
                                        :path  {:id hash}}]
          [comments-hover hash]
          {:orientation :left}]
         [hover-link "history"]
         [hover-link "related" #_related]
         [hover-link "tags" [tag-hover tags] ]
         [hover-link [ilink "figure" {:route :extract/figure
                                      :path  {:id hash}}]
          [figure-hover figures]
          {:orientation :right}]
         [hover-link [source-link source] [source-hover source]
          {:orientation :right}]]]])))

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
