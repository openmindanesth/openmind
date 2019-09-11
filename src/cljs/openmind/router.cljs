(ns openmind.router
  (:require [openmind.views :as views]
            [re-frame.core :as re-frame]
            [reitit.core :as r]
            [reitit.frontend.easy :as rfe]))

(def router
  (r/router views/routes))

(defn init! []
  (rfe/start!
   router
   (fn [m]
     (when m
       (re-frame/dispatch [:openmind.events/navigated m])))
   {:use-fragment false}))

(re-frame/reg-event-fx
 ::nav-from-url
 [(re-frame/inject-cofx :openmind.events/current-url)]
 (fn [{:keys [:openmind.events/current-url]}]
   (println (r/match-by-path router  current-url))
   (when-let [match 1])
   ))
