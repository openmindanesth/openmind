(ns openmind.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [goog.net.XhrIo]
            [openmind.config :as config]
            [openmind.events :as events]
            [openmind.views :as views]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [taoensso.sente :as sente]))

(defn connect-chsk []
  (let [csrf-ch (async/promise-chan)]
    ;; TODO: This gets the wrong token (throwaway session). Need to set client
    ;; ID correctl
    (goog.net.XhrIo/send "/elmyr"
                         (fn [e]
                           (->> e
                                .-target
                                .getResponseText
                                (async/put! csrf-ch))))
    (go
      (let [token (async/<! csrf-ch)]
        (println token)
        (println (js/encodeURIComponent token))
        (let [chsk (sente/make-channel-socket-client!
                    "/chsk" token {:type :auto})]
          (js/timeout (fn [] (.log js/console @(:state chsk)))
                      1000)
          #_(sente/chsk-send! (:chsk chsk) {:data :packet} 1000 js/console.log))))))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [views/editor-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (connect-chsk)
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (mount-root)
  )
