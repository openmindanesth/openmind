(ns ^:figwheel-hooks openmind.core
  (:require [openmind.config :as config]
            [openmind.events :as events]
            [openmind.router :as router]
            [openmind.views :as views]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [taoensso.timbre :as log]))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (log/info "dev mode")))

(defn ^:after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (router/init! views/routes)
  (reagent/render [views/main router/page]
                  (.getElementById js/document "app")))


(defn ^:export init []
  (re-frame/dispatch-sync [::events/initialise-db])
  (re-frame/dispatch [::events/update-tag-tree])
  (dev-setup)
  (mount-root))
