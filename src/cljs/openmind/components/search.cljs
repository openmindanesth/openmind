(ns openmind.components.search
  (:refer-clojure :exclude [or])
  (:require [openmind.components.extract.core :as core]
            [openmind.components.tags :as tags]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

;;;;; Subs

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (let [ids (::results db)]
     (->> ids
          (map (partial core/get-extract db))
          (map :content)))))

;; REVIEW: This is effectively global read-only state. It shouldn't be
;; namespaced. Or, presumably, in this namespace.
(re-frame/reg-sub
 :tag-lookup
 (fn [db]
   (:tag-lookup db)))

(re-frame/reg-sub
 :tag-root
 (fn [db]
   (:tag-root-id db)))

(re-frame/reg-sub
 ::sort-list-open?
 (fn [db]
   (::sort-list-open? db)))

(re-frame/reg-sub
 ::type-list-open?
 (fn [db]
   (::type-list-open? db)))

(def default-sort-order
  :extract-creation-date)

(re-frame/reg-sub
 ::sort-order
 :<- [:route]
 (fn [route]
   (-> route
       :parameters
       :query
       :sort-by
       keyword
       (cljs.core/or default-sort-order))))

(re-frame/reg-sub
 ::extract-type
 :<- [:route]
 (fn [route]
   (-> route
       :parameters
       :query
       :type
       keyword
       (cljs.core/or :all))))

;;;;; Events

(re-frame/reg-event-db
 ::open-type-list
 (fn [db _]
   (assoc db ::type-list-open? true)))

(re-frame/reg-event-db
 ::close-type-list
 (fn [db _]
   (assoc db ::type-list-open? false)))

(re-frame/reg-event-fx
 ::select-extract-type
 (fn [cofx [_ type]]
   (let [query (-> cofx :db :openmind.router/route :parameters :query)]
     {:dispatch [:navigate {:route :search
                            :query (assoc query :type type)}]})))

(re-frame/reg-event-db
 ::open-sort-list
 (fn [db _]
   (assoc db ::sort-list-open? true)))

(re-frame/reg-event-db
 ::close-sort-list
 (fn [db _]
   (assoc db ::sort-list-open? false)))

(re-frame/reg-event-fx
 ::select-sort-order
 (fn [cofx [_ type]]
   (let [query (-> cofx :db :openmind.router/route :parameters :query)]
     {:dispatch [:navigate {:route :search
                            :query (assoc query :sort-by type)}]})))

(defn or [a b]
  (cljs.core/or a b))

(defn prepare-search
  "Parse and prepare the query args for the server."
  [query nonce]
  (-> query
      (update :term or "")
      (update :filters tags/decode-url-filters)
      (update :time :or (js/Date.))
      (update :sort-by #(or (keyword %) default-sort-order))
      (update :type #(or (keyword %) :all))
      (assoc :nonce nonce)))

(re-frame/reg-event-fx
 ::search-request
 (fn [cofx [_ query]]
   ;;TODO: Time is currently ignored, but we do want time travelling search.
   (let [nonce (-> cofx :db ::nonce inc)]
     {:db       (assoc (:db cofx) ::nonce nonce)
      :dispatch [:openmind.events/try-send
                 [:openmind/search (prepare-search query nonce)]]})))

(re-frame/reg-event-fx
 :openmind/search-response
 (fn [{:keys [db]} [_ {:keys [::results ::nonce] :as e}]]
   ;; This is for slow connections: when typing, a new search is requested at
   ;; each keystroke, and these could come back out of order. When a response
   ;; comes back, if it corresponds to a newer request than that currently
   ;; displayed, swap it in, if not, just drop it.
   (if (< (::response-number db) nonce)
     {:db         (assoc db
                         ::results (map :hash results)
                         ::response-number nonce)
      :dispatch-n (mapv (fn [e] [::core/add-extract e]) results)})))

(re-frame/reg-event-fx
 ::update-term
 (fn [cofx [_ term]]
   (let [query (-> cofx :db :openmind.router/route :parameters :query)]
     {:dispatch [:navigate {:route :search
                            :query (assoc query :term term)}]})))

;;;;; Views

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
            float-content]))])))

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

(defn no-content []
  [:div.border-round.bg-grey.p1 {:style {:width       "1rem"
                                         :margin-left "2.5rem"}}])

(defn tag-hover [tags]
  (when (seq tags)
    (let [tag-lookup @(re-frame/subscribe [:tag-lookup])
          tag-root @(re-frame/subscribe [:tag-root])
          branches   (->> tags
                          (map tag-lookup)
                          (map (fn [{:keys [id parents]}] (conj parents id))))]
      [:div.bg-white.p1.border-round.border-solid
       (into [:div.flex.flex-column]
             (map (fn [t]
                    [tag-display tag-lookup t]))
             (get (tree-group branches) tag-root))])))


(defn comments-hover [comments]
  (when (seq comments)
    (into
     [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh]
     (map (fn [com]
            [:div.break-wrap.ph.border-round.border-solid.border-grey.mbh
             com]))
     comments)))

(defn figure-hover [figures]
  (when (seq figures)
    (into [:div.border-round.border-solid.bg-white]
          (map (fn [id]
                 (let [{:keys [image-data caption] :as fig}
                       @(re-frame/subscribe [:extract/content id])]
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

(defn edit-link [extract]
  (when-let [login @(re-frame/subscribe [:openmind.subs/login-info])]
    (when (= (:author extract) login)
      [:div.right.relative.text-grey.small
       {:style {:top "-2rem" :right "1rem"}}
       [:a {:on-click #(re-frame/dispatch [:navigate
                                           {:route :extract/edit
                                            :path  {:id (:hash extract)}}])}
        "edit"]])))

(defn citation [authors date]
  (let [full (apply str (interpose ", " authors))]
    (str
     (if (< (count full) 25) full (str (first authors) ", et al."))
     " (" date ")")))

(defn source-link [{{:keys [authors url date] :as source} :source}]
  (let [text (if (seq authors)
               (citation authors (.getFullYear (js/Date. date)))
               url)]
    [:a.link-blue {:href url} text]))

(defn source-hover [{:keys [source]}]
  (when (seq source)
    (let [{:keys [authors date journal abstract doi title]} source]
      [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh
       {:style {:max-width "700px"}}
       [:h2 title]
       [:span.smaller.pb1 (str "(" date ") " journal " doi: " doi)]
       [:em.small.small (apply str (interpose ", " authors))]
       [:p abstract]])))

(defn ilink [text route]
  [:a {:on-click #(re-frame/dispatch [:navigate route])}
        text])

(defn result [{:keys [text source comments details related figures tags]
               :as   extract}]
  [:div.search-result.padded
   [:div.break-wrap.ph text]
   [edit-link extract]
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [hover-link [ilink "comments" {:route :extract/comments
                                    :path  {:id (:hash extract)}}]
      [comments-hover comments]
      {:orientation :left}]
     [hover-link "history"]
     [hover-link "related" #_related]
     [hover-link "details" #_details]
     [hover-link "tags" [tag-hover tags] ]
     [hover-link [ilink "figure" {:route :extract/figure
                                  :path  {:id (:hash extract)}}]
      [figure-hover figures]
      {:orientation :right
       :style       {:max-width "75%"}}]
     [hover-link [source-link extract] [source-hover extract]
      {:orientation :right}]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::extracts])]
    (into [:div]
          (map (fn [r] [result r]))
          results)))

(defn radio [select-map set-event close-event state]
  (let [n (gensym)]
    (let [onclick (fn [value]
                    (fn [e]
                      (re-frame/dispatch [set-event value])
                      (re-frame/dispatch [close-event])))]
      (into [:div.flex.flex-column]
            (map (fn [[value label]]
                   [:div.pth
                    [:input (merge {:name      n
                                    :type      :radio
                                    :value     value
                                    :read-only true
                                    :on-click  (onclick value)}
                                   (when (= value @state)
                                     {:checked "checked"}))]
                    [:label.plh {:for      value
                                 :on-click (onclick value)}
                     label]]))
            select-map))))

(defn floating-radio-box [{:keys [open? content open! values set! close!
                                  align label]}]
  (let [open?   (re-frame/subscribe [open?])
        content (re-frame/subscribe [content])]
    (fn []
      [:div.relative
       [:button.border-round.text-white.bg-blue.ph
        {:on-click #(re-frame/dispatch [open!])}
        label (get values @content)]
       (when @open?
         [:div.border-round.border-solid.p1.bg-plain.absolute
          ;; REVIEW: I don't like this. Required keywords, basically a bespoke
          ;; and undocumented DSL...
          {:style {align     0
                   :top       "-.9rem"
                   :width     "100%"
                   :min-width :max-content
                   :opacity   0.9
                   :z-index   100}
           :on-mouse-leave #(re-frame/dispatch [close!])}
          [radio values set! close! content]])])))

(def sort-options
  {:extract-creation-date "extract creation date"
   :publication-date      "source publication date"})

(defn sort-order-selector []
  [floating-radio-box {:content ::sort-order
                       :open?   ::sort-list-open?
                       :open!   ::open-sort-list
                       :close!  ::close-sort-list
                       :set!    ::select-sort-order
                       :values  sort-options
                       :align   :right
                       :label   "sort by: "}])

(def extract-types
  {:all     "all"
   :article "article extracts"
   :labnote "lab notes"})

(defn extract-type-filter []
  [floating-radio-box {:content ::extract-type
                       :values  extract-types
                       :open?   ::type-list-open?
                       :open!   ::open-type-list
                       :close!  ::close-type-list
                       :set!    ::select-extract-type
                       :align   :left
                       :label   "extract type: "}])

(defn search-filters []
  [:div.flex.flex-column
   [tags/search-filter]
   [:div.flex.space-between.pt1
    [extract-type-filter]
    [sort-order-selector]]])

(defn search-view []
  [:div
   [search-filters]
   [:hr.mb1.mt1]
   [search-results]])

(def routes
  [["/" {:name        :search
         :component   search-view
         :controllers [{:parameters {:query [:term :filters :time :sort-by :type]}
                        :start (fn [route]
                                 (re-frame/dispatch
                                  [::search-request (:query route)]))}]}]])
