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

(defmethod ch-handler :chsk/timeout
  [_]
  (println "Connection timed out."))

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
 ::nav-create-extract
 (fn [db _]
   (assoc db :route :openmind.views/create)))

(re-frame/reg-event-db
 ::nav-search
 (fn [db _]
   (assoc db :route :openmind.views/search)))

;; TODO: What is this needed for?
(re-frame/reg-event-fx
 ::server-message
 (fn [cofx [t & args]]
   (cond
     (= t :chsk/ws-ping) (println "ping!:")
     :else (println t args))))

(re-frame/reg-event-db
 ::open-menu
 (fn [db _]
   (assoc db :menu-open? true)))

(re-frame/reg-event-db
 ::close-menu
 (fn [db _]
   (assoc db :menu-open? false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-db
 ::form-data
 (fn [db [_ k v]]
   (assoc-in db [:create k] v)))

(re-frame/reg-event-db
 ::nested-form
 (fn [db [_ k i v]]
   (assoc-in db [:create k i] v)))

(re-frame/reg-event-fx
 ::create-extract
 (fn [cofx _]
   ;; TODO: validation and form feedback
   ;; TODO: incorporate author info
   (let [extract (-> cofx
                     (get-in [:db :create])
                     (dissoc :selection)
                     (update :tags #(mapv :id %)))]
     {:dispatch [::try-send [:openmind/index extract]]})))

(defn success? [status]
  (<= 200 status 299))

(re-frame/reg-event-fx
 :openmind/index-result
 (fn [{:keys [db]} [_ status]]
   (if (success? status)
     {:db (assoc db
                 :create {:selection [] :tags #{}}
                 :status-message {:status  :success
                         :message "Extract Successfully Created!"}
                 :route :openmind.views/search)

      :dispatch-later [{:ms 2000 :dispatch [::clear-status-message]}
                       {:ms 0 :dispatch [::search-request]}]}
     {:db (assoc db :status-message
                 {:status :error :message "Failed to create extract."})})))

(re-frame/reg-event-db
 ::clear-status-message
 (fn [db]
   (dissoc db :status-message)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-db
 ::set-editor-selection
 (fn [db [_ path add?]]
   (if add?
     (assoc-in db [:create :selection] path)
     (assoc-in db [:create :selection] (vec (butlast path))))))

(re-frame/reg-event-db
 ::add-editor-tag
 (fn [db [_ tag]]
   (update-in db [:create :tags] conj tag)))

(re-frame/reg-event-db
 ::remove-editor-tag
 (fn [db [_ tag]]
   (update-in db [:create :tags] disj tag)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Server Comms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;; search

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

(defn format-search [search]
  (update search :filters #(mapv :id %)))

(re-frame/reg-event-fx
 ::search-request
 (fn [cofx _]
   (let [search  (get-in cofx [:db :search])
         search  (update search :nonce inc)]
     {:db        (assoc (:db cofx) :search search)
      :dispatch [::try-send [:openmind/search
                             {:search (format-search search)}]]})))

(re-frame/reg-event-db
 :openmind/search-response
 (fn [db [_ {:keys [results nonce] :as e}]]
   (if (< (get-in db [:search :response-number]) nonce)
     (-> db
         (assoc-in [:search :response-number] nonce)
         (assoc :results results))
     db)))

(re-frame/reg-event-db
 ::set-filter-edit
 (fn [db [_ path add?]]
   (if add?
     (assoc db :filter-selection path)
     (assoc db :filter-selection (vec (butlast path))))))

(reg-search-updater
 ::add-filter-feature
 (fn [db [_ tag]]
   (update-in db [:search :filters] conj tag)))

(reg-search-updater
 ::remove-filter-feature
 (fn [db [_ tag]]
   (update-in db [:search :filters] disj tag)))

(re-frame/reg-event-fx
 ::login-check
 (fn [cofx _]
   {:dispatch [::try-send [:openmind/verify-login]]}))

(re-frame/reg-event-db
 :openmind/identity
 (fn [db [_ id]]
   (assoc db :login-info id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Tag tree (taxonomy)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::update-tag-tree
 (fn [{{:keys [chsk domain]} :db} _]
   {:dispatch [::try-send [:openmind/tag-tree domain]]}))

(defn build-tag-lookup [{:keys [tag-name id children]}]
  (into {id tag-name} (map build-tag-lookup) (vals children)))

(re-frame/reg-event-db
 :openmind/tag-tree
 (fn [db [_ tree]]
   (assoc db
          :tag-tree tree
          :tag-lookup (build-tag-lookup tree))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Connection management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::try-send
 (fn [{{:keys [chsk]} :db} [_ ev]]
   (if (and (satisfies? IDeref (:state chsk))
            (:open? @(:state chsk)))
     {::send! {:ev ev :send-fn (:send-fn chsk)}}
     {::connect-chsk! true
      :dispatch       [::enqueue-request ev]})))

(re-frame/reg-event-db
 ::enqueue-request
 (fn [db [_ ev]]
   (update db :request-queue conj ev)))

(re-frame/reg-fx
 ::send!
 (fn [{:keys [send-fn ev]}]
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
         ;; TODO: timeout, retry, backoff.
         (go
           (let [token (async/<! csrf-ch)
                 chsk  (sente/make-channel-socket-client!
                        "/chsk" token {:type :auto})]
             ;; Wait for a message so that we know the channel is open.
             (async/<! (:ch-recv chsk))
             (reset! connecting? false)
             (sente/start-client-chsk-router! (:ch-recv chsk) ch-handler)
             (re-frame/dispatch-sync [::server-connection chsk]))))))))
