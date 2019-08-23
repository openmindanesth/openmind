(ns openmind.oauth2
  (:require [openmind.env :as env]))

(def sites
  {:orcid
   {:authorize-uri    (env/read :orcid-authorize-uri)
    :access-token-uri (env/read :orcid-access-token-uri)
    :client-id        (env/read :orcid-client-id)
    :client-secret    (env/read :orcid-client-secret)
    :scopes           ["/authenticate"]
    :launch-uri       "/oauth2/orcid"
    :redirect-uri     "/oauth2/orcid/redirect"
    :landing-uri      "/"}})
