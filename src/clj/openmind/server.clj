(ns openmind.server
  (:require [compojure.core :as c]
            [compojure.route :as route]
            [openmind.env :as env]
            [openmind.oauth2 :as oauth2]
            [openmind.routes :as routes]
            [org.httpkit.server :as http]
            ring.middleware.anti-forgery
            ring.middleware.defaults
            ring.middleware.oauth2
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit]))

(defonce socket
  (sente/make-channel-socket! (sente-http-kit/get-sch-adapter)
                              {:user-id-fn (fn [req]
                                             (-> req
                                                 :oauth2/access-tokens
                                                 :orcid
                                                 :orcid-id))}))

(c/defroutes routes
  (route/resources "/")
  (c/GET "/" req (slurp "resources/public/index.html"))
  (c/GET "/elmyr" req (force ring.middleware.anti-forgery/*anti-forgery-token*))
  (c/GET "/chsk" req ((:ajax-get-or-ws-handshake-fn socket) req))
  (c/POST "/chsk" req ((:ajax-post-fn socket) req))
  (route/not-found "This is not a page."))

(defonce
  ^{:doc "Import of private fn ring.middleware.oauth2/format-access-token"}
  ring-format-access-token
  @#'ring.middleware.oauth2/format-access-token)

(defonce my-format-access-token
  (fn [res]
    (assoc (ring-format-access-token res)
           :orcid-id (-> res :body :orcid)
           :name     (-> res :body :name))))

;; KLUDGE: monkey patching works, but oh boy...
(alter-var-root #'ring.middleware.oauth2/format-access-token
                (constantly my-format-access-token))
(def app
  (-> routes
      (ring.middleware.oauth2/wrap-oauth2 oauth2/sites)
      (ring.middleware.defaults/wrap-defaults
       (-> ring.middleware.defaults/site-defaults
           (assoc-in [:session :cookie-attrs :same-site] :lax)))))

(defonce ^:private stop-server! (atom nil))
(defonce ^:private router (atom nil))

(def req (atom nil))

(defn start-router! []
  (when (fn? @router)
    (@router))
  (reset! router
          (sente/start-server-chsk-router!
           (:ch-recv socket)
           (fn [msg]
             (let [oauth (-> msg :ring-req :oauth2/access-tokens)]
               (reset! req msg)
               (routes/dispatch (-> msg
                                    (dissoc :ring-req :ch-recv)
                                    (assoc :tokens oauth))))))))

(defn start-server! []
  (when (fn? @stop-server!)
    (@stop-server!))
  (let [port (env/port)]
    (reset! stop-server! (http/run-server #'app {:port port}))
    (println "Server listening on port:" port)))

(defn init! []
  (start-server!)
  (start-router!))

(defn stop! []
  (when @router
    (@router))
  (when @stop-server!
    (@stop-server!)))

(defn -main [& args]
  (init!))
