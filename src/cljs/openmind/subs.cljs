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

(re-frame/reg-sub
 ::route
 (fn [db]
   (::db/route db)))

;;;; Tag tree

(re-frame/reg-sub
 ::tags
 (fn [db]
   (::db/tag-tree db)))

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
 ::tag-lookup
 (fn [db]
   (:tag-lookup db)))

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

;;;;; HACK: Should pull this out of a spec

(def extract-fields
  [:text :figure :source :comments :confirmed :contrast :related])

(run!
 (fn [k]
   (re-frame/reg-sub
     k
    (fn [_ _]
      (re-frame/subscribe [::new-extract-content]))
    (fn [extract _]
      (get extract k))))
 extract-fields)
