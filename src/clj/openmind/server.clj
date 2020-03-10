(ns openmind.server
  (:require [clojure.java.io :as io]
            [compojure.core :as c]
            [compojure.route :as route]
            [openmind.env :as env]
            [openmind.oauth2 :as oauth2]
            [openmind.routes :as routes]
            [org.httpkit.server :as http]
            ring.middleware.anti-forgery
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.util.response :refer [redirect]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit]
            [taoensso.timbre :as log]))

;;;;; Hacks

(defonce
  ^{:doc "Import of private fn ring.middleware.oauth2/format-access-token"}
  ring-format-access-token
  @#'ring.middleware.oauth2/format-access-token)

(defonce my-format-access-token
  (fn [res]
    (assoc (ring-format-access-token res)
           :orcid-id (-> res :body :orcid)
           :name     (-> res :body :name))))

;; HACK: monkey patching works, but oh boy...
;;
;; I can't get see any other way to do this without making a second request.
;; REVIEW: Would that be so bad?
(alter-var-root #'ring.middleware.oauth2/format-access-token
                (constantly my-format-access-token))

;;;;; Websocket server

(defonce socket
  (sente/make-channel-socket-server!
   (sente-http-kit/get-sch-adapter)
   {:user-id-fn (fn [req]
                  (-> req
                      :oauth2/access-tokens
                      :orcid
                      :orcid-id))}))

;;;;; Routes

(def index
  "The SP in SPA"
  (io/resource "public/index.html"))

(def logout-response
  {:status  200
   :session nil
   :headers {"Content-Type" "text/html"}
   :body    "Goodbye"})

(defn orcid-login [req]
  (update (redirect "/oauth2/orcid")
          :session assoc
          :stay-logged-in (-> req :query-params (get "stay"))))

(c/defroutes routes
  (route/resources "/")

  (c/GET "/login/orcid" req (orcid-login req))
  (c/GET "/logout" req  logout-response)

  (c/GET "/elmyr" req (force ring.middleware.anti-forgery/*anti-forgery-token*))
  (c/GET "/chsk" req ((:ajax-get-or-ws-handshake-fn socket) req))
  (c/POST "/chsk" req ((:ajax-post-fn socket) req))

  (c/GET "/favicon.ico" req {:status 404})
  ;; REVIEW: defer to the SPA code to route everything else. Are there any
  ;; problems with this? Particularly regarding security?
  (c/GET "*" req index))

(def app
  (-> routes
      (wrap-oauth2 oauth2/sites)
      (wrap-defaults
       (-> site-defaults
           (assoc-in [:session :cookie-attrs :same-site] :lax)))))

;;;;; Main server

(defonce ^:private stop-server! (atom nil))
(defonce ^:private router (atom nil))

(defn stop-router! []
  (when (fn? @router)
    (@router)))

(defn clean-req [msg]
  (select-keys msg [:event :id :?reply-fn :send-fn :client-id :uid]))

(def dispatch-fn
  routes/dispatch)

(defn dispatch-msg [msg]
  (let [oauth (-> msg :ring-req :oauth2/access-tokens)]
    (dispatch-fn (-> msg
                     clean-req
                     (assoc :tokens oauth)))))

(defn start-router! []
  (stop-router!)
  (reset! router
          (sente/start-server-chsk-router! (:ch-recv socket) #'dispatch-msg)))


(defn start-server! []
  (when env/dev-mode?
    (set! *warn-on-reflection* true))
  (when (fn? @stop-server!)
    (@stop-server!))
  (let [port (env/port)]
    (reset! stop-server! (http/run-server #'app {:port port}))
    (log/info "Server listening on port:" port)))

(defn init! []
  (start-server!)
  (start-router!))

(defn stop! []
  (stop-router!)
  (when @stop-server!
    (@stop-server!)))

(defn -main [& args]
  (try
    (init!)
    (catch Exception e
      (.printStackTrace e))))
