(ns openmind.server
  (:require [compojure.core :as c]
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

(defonce authed-socket
  ;;"Web socket server for logged in users."
  (sente/make-channel-socket-server!
   (sente-http-kit/get-sch-adapter)
   {:user-id-fn (fn [req]
                  (-> req
                      :oauth2/access-tokens
                      :orcid
                      :orcid-id))}))

(defonce anonymous-socket
  ;;"Web socket server for anonymous users."
  (sente/make-channel-socket-server!
   (sente-http-kit/get-sch-adapter)
   {:csrf-token-fn nil
    :user-id-fn (constantly nil)}))

(def logout-response
  {:status  200
   :session nil
   :headers {"Content-Type" "text/html"}
   :body    "Goodbye"})

(defn check-login [req]
  (let [token (force ring.middleware.anti-forgery/*anti-forgery-token*)]
    (println token)
    (clojure.pprint/pprint req)
    req))

(defn orcid-login [req]
  (update (redirect "/oauth2/orcid")
          :session assoc
          :stay-logged-in (-> req :query-params (get "stay"))))

(c/defroutes public-routes
  (route/resources "/")
  (c/GET "/" req (slurp "resources/public/index.html"))
  (c/GET "/login" req (slurp "resources/public/index.html"))
  (c/GET "/chsk" req ((:ajax-get-or-ws-handshake-fn anonymous-socket) req))
  (c/POST "/chsk" req ((:ajax-post-fn anonymous-socket) req))
  (c/GET "/logout" req  logout-response))

(c/defroutes logged-in-routes
  (c/GET "/new" req (slurp "resources/public/index.html"))
  (c/GET "/edit/:id" req (slurp "resources/public/index.html"))
  (c/GET "/login/orcid" req (orcid-login req))
  (c/GET "/elmyr" req (check-login req))
  (c/GET "/chsk-secure" req ((:ajax-get-or-ws-handshake-fn authed-socket) req))
  (c/POST "/chsk-secure" req ((:ajax-post-fn authed-socket) req)))

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

(def app
  (c/routes
   (-> public-routes
       wrap-keyword-params
       wrap-params
       wrap-content-type)

   (-> logged-in-routes
       (wrap-oauth2 oauth2/sites)
       (wrap-defaults
        (-> site-defaults
            (assoc-in [:session :cookie-attrs :same-site] :lax))))

   (route/not-found "This is not a page.")))

(defonce ^:private stop-server! (atom nil))
(defonce ^:private public-router (atom nil))
(defonce ^:private private-router (atom nil))

(defn clean-req [req]
  (dissoc req :ring-req :ch-recv))

(defn stop-router! []
  (when (fn? @public-router)
    (@public-router))
  (when (fn? @private-router)
    (@private-router)))

(defn start-router! []
  (stop-router!)
  (reset! public-router
          (sente/start-server-chsk-router!
           (:ch-recv anonymous-socket)
           (fn [msg]
             (routes/public-dispatch (clean-req msg)))))

  (reset! private-router
          (sente/start-server-chsk-router!
           (:ch-recv authed-socket)
           (fn [msg]
             (let [oauth (-> msg :ring-req :oauth2/access-tokens)]
               (routes/dispatch (-> msg
                                    clean-req
                                    (assoc :tokens oauth))))))))


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
  (init!))
