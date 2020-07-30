(ns openmind.components.search
  (:require [clojure.string :as string]
            [openmind.components.extract :as extract]
            [openmind.components.tags :as tags]
            [openmind.events :as events]
            [re-frame.core :as re-frame]))

;;;;; Subs

(re-frame/reg-sub
 ::results
 (fn [db]
   (::results db)))

(re-frame/reg-sub
 ::extracts
 :<- [::results]
 :<- [::events/table]
 (fn [[results table] _]
   (->> results
        (map (partial get table))
        (map :content))))

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

(defn prepare-search
  "Parse and prepare the query args for the server."
  [query nonce]
  (-> query
      (update :term #(or % ""))
      (update :filters tags/decode-url-filters)
      (update :time :or (js/Date.))
      (update :sort-by #(or (keyword %) default-sort-order))
      (update :type #(or (keyword %) :all))
      (assoc :nonce nonce)))

(re-frame/reg-event-fx
 ::search-request
 (fn [cofx [_ query id]]
   ;; TODO: Time is currently ignored, but we do want time travelling search.
   ;; TODO: infinite scroll
   (let [nonce (-> cofx :db ::nonce inc)]
     {:db       (assoc (:db cofx) ::nonce nonce)
      :dispatch [:->server
                 [:openmind/search (assoc (prepare-search query nonce)
                                          :search-id id)]]})))

(re-frame/reg-event-fx
 ::refresh-search
 (fn [{:keys [db]} _]
   (let [query (-> db :openmind.router/route :query-params)]
     {:dispatch [::search-request query ::results]})))

(re-frame/reg-event-fx
 :openmind/search-response
 (fn [{:keys [db]} [_ {:keys [::results ::nonce ::meta-ids ::search-id] :as e}]]
   ;; This is for slow connections: when typing, a new search is requested at
   ;; each keystroke, and these could come back out of order. When a response
   ;; comes back, if it corresponds to a newer request than that currently
   ;; displayed, swap it in, if not, just drop it.
   (when (< (::response-number db) nonce)
     {:db         (-> db
                      (assoc search-id (map :hash results)
                             ::response-number nonce)
                      (update :openmind.components.extract.core/metadata
                              merge meta-ids))
      :dispatch-n (into [[:openmind.components.window/unspin]]
                        (mapv (fn [e]
                                [:openmind.components.extract.core/add-extract
                                 e (get meta-ids (:hash e))])
                              results))})))

(re-frame/reg-event-fx
 ::search
 (fn [cofx [_ term]]
   (let [query (-> cofx :db :openmind.router/route :parameters :query)]
     {:dispatch-n [[:navigate {:route :search
                                :query (assoc query :term term)}]
                   [:openmind.components.window/spin]]})))

(re-frame/reg-event-db
 ::update-term
 (fn [db [_ term]]
   (assoc db ::temp-search term)))

(re-frame/reg-sub
 ::term
 (fn [db]
   (::temp-search db)))

(defn search-box []
  (let [search-term @(re-frame/subscribe [::term])]
    [:div.flex {:style {:height "100%"}}
     [:input.grow-2 (merge {:type      :text
                            :style     {:height "100%"}
                            :on-change (fn [e]
                                         (let [v (-> e .-target .-value)]
                                           (re-frame/dispatch
                                            [::update-term v])))
                            :on-key-press (fn [e]
                                            (when (= 13 (.-which e))
                                              (let [t (-> e .-target .-value
                                                          string/trim)]
                                                (re-frame/dispatch [::search t])
                                                (re-frame/dispatch
                                                 [::update-term t]))))}
                    (if (empty? search-term)
                      {:value       nil
                       :placeholder "specific term"}
                      {:value search-term}))]
     [:button.border-round.text-white.bg-blue.ph.mlh
      {:on-click (fn [_]
                   (re-frame/dispatch [::search search-term]))
       :style    {:height "100%"}}
      "search"]]))

(defn search-results []
  (let [results @(re-frame/subscribe [::extracts])]
    [:div (map (fn [r]
                 ^{:key (str (:hash r))} [extract/summary r])
               (remove nil? results))]))

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
  [:div.search-view
   [search-filters]
   [:hr.mb1.mt1]
   [search-results]])

(def routes
  [["/" {:name        :search
         :component   search-view
         :controllers [{:parameters {:query [:term :filters :time :sort-by :type]}
                        :start (fn [route]
                                 (re-frame/dispatch
                                  [::search-request (:query route) ::results]))}]}]])
