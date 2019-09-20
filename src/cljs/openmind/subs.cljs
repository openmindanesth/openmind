(ns openmind.subs
  (:require
   [openmind.db :as db]
   [re-frame.core :as re-frame]))

;;;;; Misc

(re-frame/reg-sub
 ::status-message
 (fn [db]
   (:status-message db)))

(re-frame/reg-sub
 ::login-info
 (fn [db]
   (:login-info db)))

(re-frame/reg-sub
 ::menu-open?
 (fn [db]
   (:menu-open? db)))

;;;;; Extract creation

;; tags

(re-frame/reg-sub
 ::editor-tag-view-selection
 :<- [::new-extract]
 (fn [extract _]
   (:new-extract/selection extract)))

(re-frame/reg-sub
 ::editor-selected-tags
 :<- [::new-extract-content]
 (fn [content _]
   (into #{} (map :id (:tags content)))))
