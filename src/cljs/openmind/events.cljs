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

(re-frame/reg-event-db
 ::server-message
 (fn [db e]
   ;; TODO: re route
   db))

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
