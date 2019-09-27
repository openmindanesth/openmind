(ns openmind.dev
  (:require [openmind.routes :as routes]
            [taoensso.timbre :as log]))

(defmulti offline-dispatch :id)

(defmethod offline-dispatch :openmind/verify-login
  [req]
  (routes/respond-with-fallback
   req [:openmind/identity {:name "t" :orcid-id "xyzzy"}]))

(defmethod offline-dispatch :default
  [msg]
  (log/warn "Unhandled message:" (:id msg)))

(defmethod offline-dispatch :openmind/search
  [{:keys [event] :as req}]
  (routes/respond-with-fallback
   req
   [:openmind/search-response
    ;; REVIEW: This is coupling. A search result is something independent of the
    ;; component that displays it. This naming forces us to keep them bound.
    #:openmind.components.search
    {:results [{:text "asdasd"}]
     :nonce   (:openmind.components.search/nonce (second event))}]))

(defn set-offline-mode! []
  (alter-var-root #'openmind.server/dispatch-fn (constantly offline-dispatch)))
