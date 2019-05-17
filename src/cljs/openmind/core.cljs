(ns ^:figwheel-hooks openmind.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [goog.net.XhrIo]
            [openmind.config :as config]
            [openmind.events :as events]
            [openmind.views :as views]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [taoensso.sente :as sente]))

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
  (println e)
  (re-frame/dispatch [::events/server-message e]))

(defn connect-chsk []
  (let [csrf-ch (async/promise-chan)]
    (goog.net.XhrIo/send "/elmyr"
                         (fn [e]
                           (->> e
                                .-target
                                .getResponseText
                                (async/put! csrf-ch))))
    (go
      (let [token (async/<! csrf-ch)
            chsk (sente/make-channel-socket-client!
                  "/chsk" token {:type :auto})]
        (sente/start-client-chsk-router! (:ch-recv chsk) ch-handler)
        (re-frame/dispatch [::events/server-connection (:send-fn chsk)])))))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [views/window views/search-results]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (mount-root)
  (connect-chsk))
