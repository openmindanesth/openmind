(ns openmind.subs
  (:require
   [re-frame.core :as re-frame]))

(re-frame/reg-sub
 ::search
 (fn [db]
   (:search db)))

(re-frame/reg-sub
 ::search-filters
 :<- [::search]
 (fn [search _]
   (:filters search)))

(re-frame/reg-sub
 ::create
 (fn [db]
   (:create db)))

(re-frame/reg-sub
 ::editor-tag-view-selection
 :<- [::create]
 (fn [create _]
   (:selection create)))

(re-frame/reg-sub
 ::editor-selected-tags
 :<- [::create]
 (fn [create _]
   (:tags create)))

(re-frame/reg-sub
 ::send-fn
 (fn [db]
   (:send-fn db)))

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (:results db)))

(re-frame/reg-sub
 ::route
 (fn [db]
   (:route db)))

(re-frame/reg-sub
 ::current-filter-edit
 (fn [db]
   (:filter-selection db)))

(re-frame/reg-sub
 ::new-extract
 (fn [db]
   (:create db)))

(re-frame/reg-sub
 ::tags
 (fn [db]
   (:tag-tree db)))

(re-frame/reg-sub
 ::tag-lookup
 (fn [db]
   (:tag-lookup db)))

(def extract-fields
  [:extract :figure :link :comments :confirmed :contrast :related])

(run!
 (fn [k]
   (re-frame/reg-sub
     k
    (fn [_ _]
      (re-frame/subscribe [::new-extract]))
    (fn [extract _]
      (get extract k))))
 extract-fields)
