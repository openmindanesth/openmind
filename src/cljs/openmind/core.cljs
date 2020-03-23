(ns ^:figwheel-hooks openmind.core
  (:require [openmind.components.comment :as comment]
            [openmind.components.extract :as extract]
            [openmind.components.search :as search]
            [openmind.components.window :as window]
            [openmind.config :as config]
            [openmind.dev :as dev]
            [openmind.events :as events]
            [openmind.hash]
            [openmind.router :as router]
            [re-frame.core :as re-frame]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (dev/fake-login!)
    (log/info "dev mode")))

(def routes
  "Aggregated routing table for the app."
  (concat search/routes
          extract/routes
          comment/routes
          window/other-routes))


(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (router/init! routes)
  (rdom/render [window/main router/page]
                  (.getElementById js/document "app")))


(defn ^:export init []
  (re-frame/dispatch-sync [::events/initialise-db])
  (re-frame/dispatch [::events/login-check])
  (dev-setup)
  (mount-root))
