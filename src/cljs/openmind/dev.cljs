(ns openmind.dev)

(defn fake-login! []
  (swap! re-frame.db/app-db update :login-info
         (fn [login]
           (if (empty? login)
             {:name "Dev Johnson" :orcid-id "NONE"}
             login)))
  nil)
