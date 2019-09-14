(ns openmind.subs
  (:require
   [openmind.db :as db]
   [re-frame.core :as re-frame]))

;;;;; Server comms

(re-frame/reg-sub
 ::send-fn
 (fn [db]
   (:send-fn db)))

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

(re-frame/reg-sub
 ::new-extract
 (fn [db]
   (::db/new-extract db)))

(re-frame/reg-sub
 ::new-extract-content
 :<- [::new-extract]
 (fn [extract _]
   (:new-extract/content extract)))

(re-frame/reg-sub
 ::new-extract-form-errors
 :<- [::new-extract]
 (fn [extract _]
   (:errors extract)))

(re-frame/reg-sub
 ::form-input-data
 :<- [::new-extract-content]
 :<- [::new-extract-form-errors]
 (fn [[content errors] [_ k]]
   {:content (get content k)
    :errors  (get errors k)}))

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
   (:tags content)))
