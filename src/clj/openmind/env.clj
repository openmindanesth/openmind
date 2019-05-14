(ns openmind.env
  (:refer-clojure :exclude [read]))

(def env-keys
  {:elastic-cluster  "ELASTIC_CLUSTER"
   :elastic-username "ELASTIC_USERNAME"
   :elastic-password "ELASTIC_PASSWORD"
   :elastic-hostname "ELASTIC_HOSTNAME"
   :elastic-url      "ELASTIC_URL"
   :elastic-port     "ELASTIC_PORT"
   :dev?             "DEV_MODE"
   :port             "PORT"})

(defn read-config []
  (try
    (read-string (slurp "conf.edn"))
    (catch Exception e nil)))

(defn read-env []
  (into {} (comp (map (fn [[k v]] [k (System/getenv v)]))
                 (remove (fn [[k v]] (nil? v))))
        env-keys))

;;;; Env vars override config file.
(def ^:private config
  (merge (read-config) (read-env)))

(defn read
  "Read key from environment. key must be defined either in conf.end in the
  project root or as an env var according to env-keys."
  [key]
  (when (contains? config key)
    (get config key)))
