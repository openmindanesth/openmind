(ns openmind.events
  (:require
   [re-frame.core :as re-frame]
   [openmind.db :as db]))

(re-frame/reg-event-db
 ::initialise-db
 (fn [_ _]
   db/default-db))

(re-frame/reg-event-db
 ::server-connection
 (fn [db [_ chsk]]
   (assoc db :chsk chsk)))

(re-frame/reg-event-db
 ::toggle-edit
 (fn [db _]
   (update db :route
           #(if (= % :openmind.views/search)
              :openmind.views/create
              :openmind.views/search))))

;; TODO: Logged in usage will not work without this.
(re-frame/reg-event-fx
 ::server-message
 (fn [cofx [t & args]]
   (cond
     (= t :chsk/ws-ping) (println "ping!:")
     :else (println t args))))

(re-frame/reg-event-db
 ::set-filter-edit
 (fn [db [_ sel]]
   (assoc db :filter sel)))

(re-frame/reg-event-db
 ::results
 (fn [db [_ {:keys [results nonce] :as e}]]
   (if (< (get-in db [:search :response-number]) nonce)
     (-> db
         (assoc-in [:search :response-number] nonce)
         (assoc :results results))
     db)))

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

(reg-search-updater
 ::add-filter-feature
 (fn [db [_ feature value]]
   (update-in db [:search :filters feature] conj value)))

(reg-search-updater
 ::remove-filter-feature
 (fn [db [_ feature value]]
   (update-in db [:search :filters feature] disj value)))

(re-frame/reg-event-fx
 ::server-search-request
 (fn [cofx _]
   (let [search  (get-in cofx [:db :search])
         search  (update search :nonce inc)
         chsk    (get-in cofx [:db :chsk])
         send-fn (:send-fn chsk)
         open?   (when-let [s (:state chsk)] (:open? @s))
         user    (get-in cofx [:db :user])]
     (if open?
       {:db           (assoc (:db cofx) :search search)
        ::send-search {:search search :send-fn send-fn :user user}}
       {:dispatch-later [{:ms 100 :dispatch [::server-search-request]}]}))))

(re-frame/reg-fx
 ::send-search
 (fn [{:keys [send-fn] :as req}]
   (send-fn [:openmind/search (dissoc req :send-fn)]
            10000
            ;; TODO: When we have logged in users, we should use their user
            ;; ids. I don't know how to do that for anonymous users though
            ;; (random IDs, I suppose, but this is just as good).
            (fn [[_ res]]
              (println res)
              (re-frame/dispatch [::results res])))))

(defn index-doc [send-fn doc]
  (send-fn [:openmind/index doc]))

(defn unsafe-index [doc]
  (index-doc (:send-fn @re-frame.db/app-db) doc))
