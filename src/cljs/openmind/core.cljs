(ns ^:figwheel-hooks openmind.core
  (:require [openmind.config :as config]
            [openmind.events :as events]
            [openmind.views :as views]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [views/main-view]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (re-frame/dispatch-sync [::events/initialise-db])
  (re-frame/dispatch [::events/update-tag-tree])
  (re-frame/dispatch [::events/search-request])
  (re-frame/dispatch [::events/login-check])
  (dev-setup)
  (mount-root))
