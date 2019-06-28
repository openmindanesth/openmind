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
   (:create-extract db)))

(re-frame/reg-sub
 ::tags
 (fn [db]
   (:tag-tree db)))

(re-frame/reg-sub
 ::tag-lookup
 (fn [db]
   (:tag-lookup db)))

(def extract-data
  [:extract :figure :link :comments])

(run!
 (fn [k]
   (re-frame/reg-sub
    k
    (fn [_ _]
      (re-frame/subscribe [::new-extract]))
    (fn [extract _]
      (get extract k))))
 extract-data)
