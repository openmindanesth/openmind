(ns openmind.events
  (:require
   [re-frame.core :as re-frame]
   [openmind.db :as db]
   ))

(re-frame/reg-event-db
 ::initialize-db
 (fn [_ _]
   db/default-db))

(re-frame/reg-event-db
 ::server-connection
 (fn [db [_ send]]
   (assoc db :send-fn send)))

#_(re-frame/reg-event-fx
 ::server-message
 (fn [cofx e]
   (println e)

   ))

(re-frame/reg-event-db
 ::set-filter-edit
 (fn [db [_ sel]]
   (assoc db :filter sel)))

(re-frame/reg-event-db
 ::add-filter-feature
 (fn [db [_ feature value ]]
   (update-in db [:search :filters feature] conj value)))

(re-frame/reg-event-db
 ::remove-filter-feature
 (fn [db [_ feature value]]
   (update-in db [:search :filters feature] disj value)))

(defn reg-search-updater [key update-fn]
  (re-frame/reg-event-fx
   key
   (fn [cofx e]
     {:db       (update-fn (:db cofx) e)
      :dispatch [::server-search-request]})))

(reg-search-updater
 ::search
 (fn [db [_ term]]
   (assoc-in db [:search :term] term)))

(re-frame/reg-event-fx
 ::server-search-request
 (fn [cofx _]
   (let [search  (get-in cofx [:db :search])
         search  (update search :nonce inc)
         send-fn (get-in cofx [:db :send-fn])]
     {:db           (assoc (:db cofx) :search search)
      ::send-search {:search search :send-fn send-fn}})))

(re-frame/reg-fx
 ::send-search
 (fn [{:keys [send-fn search]}]
   (send-fn [:openmind/search search])))
