(ns openmind.subs
  (:require
   [openmind.db :as db]
   [re-frame.core :as re-frame]))

;;;;; Search

(re-frame/reg-sub
 ::search
 (fn [db]
   (::db/search db)))

(re-frame/reg-sub
 ::current-filter-edit
 :<- [::search]
 (fn [search]
   (:search/selection search)))

(re-frame/reg-sub
 ::search-filters
 :<- [::search]
 (fn [search _]
   (:search/filters search)))

;;;;; Server comms

(re-frame/reg-sub
 ::send-fn
 (fn [db]
   (:send-fn db)))

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (::db/results db)))

;;;; Tag tree

(re-frame/reg-sub
 ::tags
 (fn [db]
   (::db/tag-tree db)))

;;;;; Misc

(re-frame/reg-sub
 ::tag-lookup
 (fn [db]
   (:tag-lookup db)))

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
