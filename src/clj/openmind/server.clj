(ns openmind.server
  (:require [compojure.core :as c]
            [compojure.route :as route]
            [openmind.env :as env]
            [org.httpkit.server :as http]
            ring.middleware.keyword-params
            ring.middleware.params
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit]))


(def socket
  (sente/make-channel-socket! (sente-http-kit/get-sch-adapter) {}))

(c/defroutes routes
  (c/GET "/chsk" req (-> socket :ajax-get-or-ws-handshake-fn req))
  (c/POST "/chsk" req (-> socket :ajax-post-fn req))
  (route/resources "/")

  )

(def app
  (-> routes
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))


(defonce ^:private stop-server! (atom nil))

(defn start-server! []
  (when (fn? @stop-server!)
    (@stop-server!))
  (reset! stop-server! (http/run-server #'app (env/read :port))))
