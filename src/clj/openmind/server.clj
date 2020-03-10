(ns openmind.server
  (:require clj-http.client
            [clojure.java.io :as io]
            [compojure.core :as c]
            [compojure.route :as route]
            [openmind.env :as env]
            [openmind.oauth2 :as oauth2]
            [openmind.routes :as routes]
            [org.httpkit.server :as http]
            ring.middleware.anti-forgery
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.middleware.params :refer [wrap-params]]
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

(def redirect-response
  {:status  301
   :session nil
   :headers {"Content-Type" "text/html"
             "Location"     "https://openmind.macroexpanse.com:443/"}
   :body    "<html><head><title>301 Moved Permanently</title></head><body bgcolor=\"white\"><center><h1>301 Moved Permanently</h1></center></body></html>"})

(defn orcid-login [req]
  (update (redirect "/oauth2/orcid")
          :session assoc
          :stay-logged-in (-> req :query-params (get "stay"))))

(c/defroutes routes
  (c/GET "*" req redirect-response))

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
