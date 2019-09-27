(ns openmind.components.search
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

;;;;; Events

(defn prepare-search
  "Parse and prepare the query args for the server."
  [term filters time nonce]
  {::term    (or term "")
   ::filters (tags/decode-url-filters filters)
   ::time    (or time (js/Date.))
   ::nonce   nonce})

(re-frame/reg-event-fx
 ::search-request
 (fn [cofx [_ term filters time]]
   ;;TODO: Time is currently ignored, but we do want time travelling search.
   (let [nonce (-> cofx :db ::nonce inc)]
     {:db       (assoc (:db cofx) ::nonce nonce)
      :dispatch [:openmind.events/try-send
                 [:openmind/search (prepare-search term filters time nonce)]]})))

(re-frame/reg-event-db
 :openmind/search-response
 (fn [db [_ {:keys [::results ::nonce] :as e}]]
   ;; This is for slow connections: when typing, a new search is requested at
   ;; each keystroke, and these could come back out of order. When a response
   ;; comes back, if it corresponds to a newer request than that currently
   ;; displayed, swap it in, if not, just drop it.
   (if (< (::response-number db) nonce)
     (-> db
         (assoc-in [::response-number] nonce)
         (#(reduce core/add-extract db results))
         (assoc ::results (map :id results)))
     db)))

(re-frame/reg-event-fx
 ::update-term
 (fn [cofx [_ term]]
   (let [query (-> cofx :db :openmind.router/route :parameters :query)]
     {:dispatch [:navigate {:route :search
                            :query (assoc query :term term)}]})))

;;;;; Views

(defn hover-link [text float-content route
                  {:keys [orientation style force?]}]
  (let [hover? (reagent/atom false)]
    (fn [text float-content route {:keys [orientation style force?]}]
      [:div
       [:a.plh.prh.link-blue {:on-click #(re-frame/dispatch
                                          [:navigate route])
                              :on-mouse-over #(reset! hover? true)
                              :on-mouse-out  #(reset! hover? false)}
        text]
       (when float-content
         ;; dev hack
         (when (or force? @hover?)
           [:div.absolute
            {:style (merge
                     style
                     (cond
                       (= :left orientation)  {:left "1rem"}
                       (= :right orientation) {:right "2rem"}
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
  (when (seq k)
    [:div.flex
     [:div {:class (str "bg-blue border-round p1 mbh mrh "
                        "text-white flex flex-column flex-centre")}
      [:div
       (:tag-name (tag-lookup k))]]
     (into [:div.flex.flex-column]
           (map (fn [b] [tag-display tag-lookup b]))
           children)]))

(defn no-content []
  [:div.border-round.bg-grey.p1 {:style {:width       "1rem"
                                         :margin-left "2.5rem"}}])

(defn tag-hover [tags]
  (when (seq tags)
    (let [tag-lookup @(re-frame/subscribe [:tag-lookup])
          branches   (->> tags
                          (map tag-lookup)
                          (map (fn [{:keys [id parents]}] (conj parents id))))]
      [:div.bg-white.p1.border-round.border-solid
       (into [:div.flex.flex-column]
             (map (fn [t]
                    [tag-display tag-lookup t]))
             (get (tree-group branches) "8PvLV2wBvYu2ShN9w4NT"))])))


(defn comments-hover [comments]
  (when (seq comments)
    (into
     [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh]
     (map (fn [com]
            [:div.break-wrap.ph.border-round.border-solid.border-grey.mbh
             com]))
     comments)))

(defn figure-hover [figure caption]
  (when figure
    [:div.border-round.border-solid.bg-white
     [:img.relative.p1 {:src figure
                        :style {:max-width "99%"
                                :max-height "50vh"
                                :left "2px"
                                :top "2px"}}]
     (when (seq caption)
       [:div.p1 caption])]))

(defn edit-link [extract]
  (when-let [login @(re-frame/subscribe [:openmind.subs/login-info])]
    (when (= (:author extract) login)
      [:div.right.relative.text-grey.small
       {:style {:top "-2rem" :right "1rem"}}
       [:a {:on-click #(re-frame/dispatch [:navigate
                                           {:route :extract/edit
                                            :path  {:id (:id extract)}}])}
        "edit"]])))

(defn result [{:keys [text source comments details related figure tags]
               :as   extract}]
  [:div.search-result.padded
   [:div.break-wrap.ph text]
   [edit-link extract]
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [hover-link "comments" [comments-hover comments]
      {:route :extract/comments
       :path {:id (:id extract)}}
      {:orientation :left}]
     [hover-link "history"]
     [hover-link "related" #_related]
     [hover-link "details" #_details]
     [hover-link "tags" [tag-hover tags]]
     [hover-link "figure" [figure-hover figure (:figure-caption extract)]
      {:route :extract/figure
       :path {:id (:id extract)}}
      {:orientation :right
       :style       {:max-width "75%"}}]
     [:a.link-blue {:href source} source]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::extracts])]
    (into [:div]
          (map (fn [r] [result r]) results))))

(defn search-view []
  [:div
   [tags/search-filter]
   [:hr.mb1.mt1]
   [search-results]])

(def routes
  [["/" {:name        :search
         :component   search-view
         :controllers [{:parameters {:query [:term :filters :time]}

                        :start (fn [{{:keys [term filters time]} :query}]
                                 (re-frame/dispatch
                                  [::search-request term filters time]))}]}]])
