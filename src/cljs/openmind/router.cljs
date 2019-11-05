(ns openmind.router
  (:require [re-frame.core :as re-frame]
            [reitit.core :as r]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]))

;; This is the "public" event. Events intended to tie the app together are not
;; namespace qualified since that tends to tightly couple namespaces across the
;; codebase that aren't otherwise related.
(re-frame/reg-event-fx
 :navigate
 (fn [_ [_ args]]
   {::navigate! args}))

(re-frame/reg-sub
 :route
 (fn [db]
   (::route db)))

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

;;;;; Events

(re-frame/reg-cofx
 ::current-url
 (fn [cofx]
   (let [loc (.-location js/document)]
     (assoc cofx ::current-url {:path  (.-pathname loc)
                                :query (.-search loc)
                                :hash  (.-hash loc)}))))

(re-frame/reg-fx
 ::navigate!
 (fn [{:keys [route path query]}]
   (rfe/push-state route path query)))

(re-frame/reg-event-fx
 ::navigated
 (fn [{:keys [db]} [_ match]]
   {:db (update db ::route
                (fn [previous]
                  (assoc match :controllers
                         (rfc/apply-controllers (:controllers previous) match))))
    :dispatch [:openmind.events/close-menu]}))

(re-frame/reg-event-fx
 ::init-router
 [(re-frame/inject-cofx ::current-url)]
 (fn [cofx [_ router]]
   (let [path (:path (::current-url cofx))]
     {:db (assoc (:db cofx)
                 ::route (r/match-by-path router path))})))

;;;;; view components

(defn four-o-four []
  [:h2 "You're in a bad place."])

(defn page []
  (let [route @(re-frame/subscribe [:route])]
    (if route
      [(-> route :data :component) route]
      [four-o-four])))
