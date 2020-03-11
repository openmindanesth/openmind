(ns openmind.dev)

(defn fake-login! []
  (swap! re-frame.db/app-db assoc :login-info {:name "Dev Johnson" :orcid-id "NONE"})
  nil)
