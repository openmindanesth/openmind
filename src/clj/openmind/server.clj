(ns openmind.server
  (:require [compojure.core :as c]
            [compojure.route :as route]
            [openmind.env :as env]
            [openmind.routes :as routes]
            [org.httpkit.server :as http]
            ring.middleware.anti-forgery
            ring.middleware.defaults
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit]))

;; REVIEW: I should disconnect and clean this up on reload?
(defonce socket
  (sente/make-channel-socket! (sente-http-kit/get-sch-adapter) {}))

(c/defroutes routes
  (route/resources "/")
  (c/GET "/" req (slurp "resources/public/index.html"))
  (c/GET "/elmyr" req (force ring.middleware.anti-forgery/*anti-forgery-token*))
  (c/GET "/chsk" req ((:ajax-get-or-ws-handshake-fn socket) req))
  (c/POST "/chsk" req ((:ajax-post-fn socket) req))
  (route/not-found "This is not a page."))

(def app
  ;; TODO: login. Ideally some open auth platform
  (ring.middleware.defaults/wrap-defaults
   routes
   ring.middleware.defaults/site-defaults))


(defonce ^:private stop-server! (atom nil))
(defonce ^:private router (atom nil))

(defn start-router! []
  (when (fn? @router)
    (@router))
  (reset! router
          (sente/start-server-chsk-router!
           (:ch-recv socket)
           #(routes/dispatch socket (select-keys % [:event :client-id])))))

(defn start-server! []
  (when (fn? @stop-server!)
    (@stop-server!))
  (let [port (env/read :port)]
    (reset! stop-server! (http/run-server #'app {:port port}))
    (println "Server listening on port:" port)))

(defn init! []
  (start-server!)
  (start-router!))

(defn -main [& args]
  (init!))
