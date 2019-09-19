(ns openmind.search
  (:require [openmind.views.tags :as tags]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

;;;;; Subs

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (::results db)))

(re-frame/reg-sub
 ::tag-lookup
 (fn [db]
   (:openmind.views.tags/tag-lookup db)))

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
 (fn [db [_ {:keys [::results ::nonce]}]]
   ;; This is for slow connections: when typing, a new search is requested at
   ;; each keystroke, and these could come back out of order. When a response
   ;; comes back, if it corresponds to a newer request than that currently
   ;; displayed, swap it in, if not, just drop it.
   (if (< (::response-number db) nonce)
     (-> db
         (assoc-in [::response-number] nonce)
         (assoc ::results results))
     db)))

(re-frame/reg-event-fx
 ::update-term
 (fn [cofx [_ term]]
   (let [query (-> cofx :db :openmind.router/route :parameters :query)]
     {:dispatch [:openmind.router/navigate {:route ::search
                                            :query (assoc query :term term)}]})))

;;;;; Views

(defn hlink [text float-content orientation]
  (let [hover? (reagent/atom false)]
    (fn [text float-content]
      [:div
       [:a.plh.prh.link-blue {:on-mouse-over #(reset! hover? true)
                              :on-mouse-out  #(reset! hover? false)}
        text]
       (when float-content
         (when @hover?
           [:div.absolute
            {:style (cond
                      (= :left orientation)  {:left "10px"}
                      (= :right orientation) {:right "10px"}
                      :else                  {:transform "translateX(-50%)"})}
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
  (if (seq tags)
    (let [tag-lookup @(re-frame/subscribe [::tag-lookup])
          branches   (->> tags
                          (map tag-lookup)
                          (map (fn [{:keys [id parents]}] (conj parents id))))]
      [:div.bg-white.p1.border-round.border-solid
       (into [:div.flex.flex-column]
             (map (fn [t]
                    [tag-display tag-lookup t]))
             (get (tree-group branches) "8PvLV2wBvYu2ShN9w4NT"))])
    [no-content]))


(defn comments-hover [comments]
  (if (seq comments)
    (into
     [:div.flex.flex-column.border-round.bg-white.border-solid.p1.pbh]
     (map (fn [com]
            [:div.break-wrap.ph.border-round.border-solid.border-grey.mbh
             com]))
     comments)
    [no-content]))

(defn result [{:keys [text reference comments details related figure tags]}]
  [:div.search-result.padded
   [:div.break-wrap.ph text]
   [:div.right.relative.text-grey.small
    {:style {:top "-2rem" :right "1rem"}}
    [:a "edit"]]
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [hlink "comments" [comments-hover comments] :left]
     [hlink "history"]
     [hlink "related" #_related]
     [hlink "details" #_details]
     [hlink "tags" [tag-hover tags]]
     [hlink "figure" #_figure]
     [hlink reference]]]])

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
  [["/" {:name        ::search
         :component   search-view
         :controllers [{:parameters {:query [:term :filters :time]}

                        :start (fn [{{:keys [term filters time]} :query}]
                                 (re-frame/dispatch
                                  [::search-request term filters time]))}]}]])
