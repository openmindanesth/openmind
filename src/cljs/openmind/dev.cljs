(ns openmind.dev
  (:require [re-frame.core :as re-frame]))

(defn login! [name id]
  (swap! re-frame.db/app-db update :login-info
         (fn [login]
           {:name name :orcid-id id}))
  nil)

(defn fake-login! []
  (when (empty? @(re-frame/subscribe [:openmind.subs/login-info]))
    (login! "Dev Johnson" "NONE")))

(defn random-login! []
  (login! (str (gensym)) (str (gensym))))
