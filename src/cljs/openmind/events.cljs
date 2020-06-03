(ns openmind.events
  (:require [cljs.core.async :as async]
            [openmind.edn :as edn]
            goog.net.XhrIo
            [openmind.config :as config]
            [openmind.db :as db]
            [openmind.hash :as h]
            [re-frame.core :as re-frame]
            [reagent.ratom :as ratom]
            [taoensso.sente :as sente]
            [taoensso.timbre :as log])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Other
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-db
 ::initialise-db
 (fn [_ _]
   db/default-db))

(re-frame/reg-event-db
 ::open-menu
 (fn [db _]
   (assoc db :menu-open? true)))

(re-frame/reg-event-db
 ::close-menu
 (fn [db _]
   (assoc db :menu-open? false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Server Comms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;; login

(re-frame/reg-event-fx
 ::login-check
 [(re-frame/inject-cofx :storage/get :orcid)]
 (fn [cofx _]
   {:dispatch [:->server [:openmind/verify-login]]}))

(re-frame/reg-event-fx
 :openmind/identity
 (fn [cofx [_ id]]
   (when-not (empty? id)
     {:storage/set [:orcid id]
      :db          (assoc (:db cofx) :login-info id)})))

(re-frame/reg-event-fx
 ::logout
 (fn [cofx _]
   {:storage/remove :orcid
    ::server-logout nil
    :dispatch [:navigate {:route :search}]}))

(re-frame/reg-fx
 ::server-logout
 (fn [_]
   (goog.net.XhrIo/send "/logout"
                        ;; TODO: Timeout and handle failure to logout.
                        (fn [e]
                          (when (= 200 (-> e .-target .getStatus))
                            (re-frame/dispatch [::complete-logout]))))))

(re-frame/reg-event-db
 ::complete-logout
 (fn [db _]
   (dissoc db :login-info)))

;;;;; sessionStorage

(re-frame/reg-fx
 :storage/set
 (fn [[k v]]
   (.setItem (.-sessionStorage js/window) (str k) (str v))))

(re-frame/reg-fx
 :storage/remove
 (fn [k]
   (.removeItem (.-sessionStorage js/window) (str k)))

 (re-frame/reg-cofx
  :storage/get
  (fn [cofx k]
    (assoc cofx
           :storage/get
           (-> js/window
               .-sessionStorage
               (.getItem (str k))
               edn/read-string)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Static datastore
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Lifted from
;; https://github.com/cemerick/url/blob/master/src/cemerick/url.cljx
;; with great thanks.
(defn url-encode
  [hash]
  (some-> hash
          str
          (js/encodeURIComponent)
          (.replace "+" "%20")))

(re-frame/reg-fx
 ::s3-xhr
 (fn [hash]
   (let [bucket config/s3-bucket
         url    (str "https://"
                     bucket
                     ".s3.eu-central-1.amazonaws.com/"
                     (url-encode hash))]
     (log/info "fetching" url)
     (goog.net.XhrIo/send url
                          (fn [e]
                            (let [response (->> e
                                                .-target
                                                .getResponseText
                                                edn/read-string)]
                              (re-frame/dispatch
                               [::s3-receive response])))))))

(re-frame/reg-event-fx
 ::s3-receive
 (fn [{:keys [db]} [_ res]]
   (when-let [hash (:hash res)]
     (let [value (assoc res :fetched (js/Date.))]
       {:db (assoc-in db [::table hash] value)}))))

(re-frame/reg-event-fx
 :s3-get
 (fn [{:keys [db]} [_ hash]]
   (if-not (h/value-ref? hash)
     (log/error "Invalid ref fetch" hash)
     (when-not (contains? (::table db) hash)
       {:db      (assoc-in db [::table hash] ::uninitialised)
        ::s3-xhr hash}))))

(defn extract [db id]
  (get (::table db) id))

(re-frame/reg-event-fx
 :ensure
 (fn [{:keys [db]} [_ id event]]
   (let [ex (extract db id)]
     (if (and (some? ex) (not= ::uninitialised ex))
       {:dispatch [event id]}
       {:dispatch [:s3-get id]
        :dispatch-later [{:ms 100 :dispatch [:ensure id event]}]}))))

(re-frame/reg-sub-raw
 ::lookup
 (fn [db [_ hash]]
   (when-not (contains? (::table @db) hash)
     (re-frame/dispatch [:s3-get hash]))
   (ratom/make-reaction
    (fn [] (get-in @db [::table hash]))
    :on-dispose (fn []
                  ;; TODO: Consider some simple ref counting and gc on ::table
                  ))))

(re-frame/reg-sub
 :content
 (fn [[_ id]]
   (re-frame/subscribe [::lookup id]))
 (fn [imm [_ id]]
   (when-not (= ::uninitialised imm)
     (assoc (:content imm) :hash (:hash imm)))))

(re-frame/reg-sub
 ::table
 (fn [db]
   (::table db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Connection management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 :->server
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
     (merge {:db (assoc db :chsk chsk :request-queue [] :connecting? false)
             :dispatch-n
             (into [[::login-check]]
                   (when (seq pending)
                     (map (fn [ev] [:->server ev]) pending)))}))))

(let [connecting? (atom false)]
  (re-frame/reg-fx
   ::connect-chsk!
   (fn [_]
     (when-not @connecting?
       (reset! connecting? true)
       (log/info "Connecting to server...")
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
             (sente/start-client-chsk-router! (:ch-recv chsk)
              (fn [message]
                (when-let [event (:event message)]
                  (re-frame/dispatch event))))
             (re-frame/dispatch-sync [::server-connection chsk]))))))))

;;;;; Sente internal events.

(re-frame/reg-event-fx
 :chsk/handshake
 (fn [_ _]))

(re-frame/reg-event-fx
 :chsk/state
 (fn [_ _]))

(re-frame/reg-event-fx
 :chsk/timeout
 (fn [_ _]
   (log/warn "Server websocker connection timed out.")))

(defmulti broadcast-dispatch first)

(defmethod broadcast-dispatch :default
  [e]
  (log/error "Received broadcast message from server"
                e
                "Broadcast support is not currently implemented."))

(defmethod broadcast-dispatch :chsk/ws-ping
  [_]
  (log/info "caught a ping"))

(re-frame/reg-event-fx
 :chsk/recv
 (fn [_ [_ e]]
   (broadcast-dispatch e)))
