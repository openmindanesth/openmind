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

(defn hlink [text float-content]
  (let [hover? (reagent/atom false)]
    (fn [text float-content]
      [:div
       [:a.plh.prh.link-blue {:on-mouse-over #(reset! hover? true)
                              :on-mouse-out  #(reset! hover? false)}
        text]
       (when @hover?
         [:div.absolute
          float-content])])))

(defn comments-link []
  [hlink "comments"])

(defn history-link []
  [hlink "history"])

(defn reference-link [ref]
  [hlink ref])

(defn format-tags [tags]
  ;; FIXME: flex isn't the right solution here.
  (let [tag-lookup @(re-frame/subscribe [::tag-lookup])]
    (when (seq tags)
      (into [:div.flex.flex-column.bg-white.p1.border-round]
            (map (fn [id]
                   [:div (get tag-lookup id)]))
            tags))))

(defn result [{:keys [text reference]
               {:keys [comments details related figure] :as tags} :tags}]
  [:div.search-result.padded
   [:div.break-wrap.ph text]
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [comments-link (:comments tags)]
     [history-link]
     [hlink "related" related]
     [hlink "details" details]
     [hlink "tags" (format-tags tags)]
     [hlink "figure" figure]
     [reference-link reference]]]])

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
