(ns openmind.subs
  (:require
   [openmind.db :as db]
   [re-frame.core :as re-frame]))

;;;;; Misc

(re-frame/reg-sub
 ::login-info
 (fn [db]
   (:login-info db)))

(re-frame/reg-sub
 ::menu-open?
 (fn [db]
   (:menu-open? db)))
