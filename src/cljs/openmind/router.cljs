(ns openmind.router
  (:require [re-frame.core :as re-frame]
            [reitit.core :as r]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]))

;;;; Nav Router

(defn init! [routes]
  (let [router (r/router routes)]
    (re-frame/dispatch [::init-router router])
    (rfe/start!
     router
     (fn [m]
       (when m
         (re-frame/dispatch [::navigated m])))
     {:use-fragment false})))

;;;;; Subs

(re-frame/reg-sub
 ::route
 (fn [db]
   (::route db)))

;;;;; Events

(re-frame/reg-cofx
 ::current-url
 (fn [cofx]
   (let [loc (.-location js/document)
         url (str (.-pathname loc) (.-search loc) (.-hash loc))]
     (assoc cofx ::current-url url))))

(re-frame/reg-fx
 ::navigate!
 (fn [route]
   (apply rfe/push-state route)))

(re-frame/reg-event-db
 ::navigated
 (fn [db [_ match]]
   (update db ::route
           (fn [previous]
             (assoc match :controllers
                    (rfc/apply-controllers (:controllers previous) match))))))

(re-frame/reg-event-fx
 ::navigate
 (fn [db [_ match]]))

(re-frame/reg-event-fx
 ::init-router
 [(re-frame/inject-cofx ::current-url)]
 (fn [cofx [_ router]]
   (let [url (::current-url cofx)]
     {:db (assoc (:db cofx)
                 ::route (r/match-by-path router url))})))

;;;;; view components

(defn four-o-four []
  [:h2 "You're in a bad place."])

(defn page []
  (let [route @(re-frame/subscribe [::route])]
    (if route
      [(-> route :data :component)]
      [four-o-four])))
