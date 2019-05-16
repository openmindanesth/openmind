(ns openmind.server
  (:require [compojure.core :as c]
            [compojure.route :as route]
            [openmind.env :as env]
            [org.httpkit.server :as http]
            ring.middleware.anti-forgery
            ring.middleware.keyword-params
            ring.middleware.params
            ring.middleware.session
            ring.middleware.defaults
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit]))


(def socket
  (sente/make-channel-socket! (sente-http-kit/get-sch-adapter) {}))

(c/defroutes routes
  (route/resources "/")
  (c/GET "/" req (slurp "resources/public/index.html"))
  (c/GET "/elmyr" req (force ring.middleware.anti-forgery/*anti-forgery-token*))
  (c/GET "/chsk" req ((:ajax-get-or-ws-handshake-fn socket) req))
  (c/POST "/chsk" req ((:ajax-post-fn socket) req))
  (route/not-found "This is not a page."))

(def app
  (ring.middleware.defaults/wrap-defaults
   routes
   ring.middleware.defaults/site-defaults))


(defonce ^:private stop-server! (atom nil))

(defn start-server! []
  (when (fn? @stop-server!)
    (@stop-server!))
  (reset! stop-server! (http/run-server #'app {:port (env/read :port)})))
