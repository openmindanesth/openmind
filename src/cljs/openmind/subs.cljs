(ns openmind.subs
  (:require
   [re-frame.core :as re-frame]))

(re-frame/reg-sub
 ::filters
 (fn [db]
   (:filters db)))

(re-frame/reg-sub
 ::search
 (fn [db]
   (:search db)))

(re-frame/reg-sub
 ::send-fn
 (fn [db]
   (:send-fn db)))

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (:results db)))
