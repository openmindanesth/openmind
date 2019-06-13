(ns openmind.events
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [goog.net.XhrIo]
            [re-frame.core :as re-frame]
            [openmind.db :as db]
            [taoensso.sente :as sente]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; WS router
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti ch-handler
  "Dispatch events from server."
  :id)

(defmethod ch-handler :default
  [e]
  (println "Unknown server event:" e))

(defmethod ch-handler :chsk/state
  [_]
  "Connection state change.")

(defmethod ch-handler :chsk/handshake
  [_]
  "WS handshake.")

(defmethod ch-handler :chsk/recv
  [e]
  (re-frame/dispatch [::server-message e]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Other
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-db
 ::initialise-db
 (fn [_ _]
   db/default-db))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-db
 ::form-data
 (fn [db [_ k v]]
   (let [k (if (vector? k) k [k])]
     (assoc-in db (into [:create-extract] k) v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Server Comms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: real indexing event

(defn index-doc [send-fn doc]
  (send-fn [:openmind/index doc]))

(defn unsafe-index [doc]
  (index-doc (:send-fn @re-frame.db/app-db) doc))

(defn reg-search-updater [key update-fn]
  (re-frame/reg-event-fx
   key
   (fn [cofx e]
     {:db       (update-fn (:db cofx) e)
      :dispatch [::search-request]})))

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
 ::search-request
 (fn [cofx _]
   (let [search  (get-in cofx [:db :search])
         search  (update search :nonce inc)]
     {:db        (assoc (:db cofx) :search search)
      :dispatch [::try-send [:openmind/search {:search search}]]})))

(re-frame/reg-event-db
 :openmind/search-response
 (fn [db [_ {:keys [results nonce] :as e}]]
   (if (< (get-in db [:search :response-number]) nonce)
     (-> db
         (assoc-in [:search :response-number] nonce)
         (assoc :results results))
     db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Connection management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::try-send
 (fn [{{:keys [chsk user]} :db} [_ ev]]
   (if (and (satisfies? IDeref (:state chsk))
            (:open? @(:state chsk)))
     {::send! {:ev ev :send-fn (:send-fn chsk) :user user}}
     {::connect-chsk! true
      :dispatch       [::enqueue-request ev]})))

(re-frame/reg-event-db
 ::enqueue-request
 (fn [db [_ ev]]
   (update db :request-queue conj ev)))

(re-frame/reg-fx
 ::send!
 (fn [{:keys [send-fn ev user]}]
   ;; FIXME: For mutli-device logged in users, this will duplicate responses.
   (send-fn ev 10000 re-frame/dispatch)))


(re-frame/reg-event-fx
 ::server-connection
 (fn [{:keys [db]} [_ chsk]]
   (let [pending (:request-queue db)]
     (merge {:db (assoc db :chsk chsk :request-queue [] :connecting? false)}
            (when (seq pending)
              {:dispatch-n (mapv (fn [ev] [::try-send ev]) pending)})))))

(let [connecting? (atom false)]
  (re-frame/reg-fx
   ::connect-chsk!
   (fn [_]
     (when-not @connecting?
       (reset! connecting? true)
       (println "Connecting to server...")
       (let [csrf-ch (async/promise-chan)]
         (goog.net.XhrIo/send "/elmyr"
                              (fn [e]
                                (->> e
                                     .-target
                                     .getResponseText
                                     (async/put! csrf-ch))))
         (go
           (let [token (async/<! csrf-ch)
                 chsk  (sente/make-channel-socket-client!
                        "/chsk" token {:type :auto})]
             ;; Wait for a message so that we know the channel is open.
             (async/<! (:ch-recv chsk))
             (reset! connecting? false)
             (sente/start-client-chsk-router! (:ch-recv chsk) ch-handler)
             (re-frame/dispatch [::server-connection chsk]))))))))
