(ns ^:figwheel-hooks openmind.core
  (:require [openmind.components.extract-editor :as extract-editor]
            [openmind.components.extract :as extract]
            [openmind.components.search :as search]
            [openmind.components.window :as window]
            [openmind.config :as config]
            [openmind.events :as events]
            [openmind.router :as router]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [taoensso.timbre :as log]))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (log/info "dev mode")))

(def routes
  "Aggregated routing table for the app."
  (concat search/routes
          extract-editor/routes
          extract/routes
          window/other-routes))


(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (router/init! routes)
  (reagent/render [window/main router/page]
                  (.getElementById js/document "app")))


(defn ^:export init []
  (re-frame/dispatch-sync [::events/initialise-db])
  (re-frame/dispatch [::events/update-tag-tree])
  (re-frame/dispatch [::events/login-check])
  (dev-setup)
  (mount-root))
