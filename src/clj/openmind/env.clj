(ns openmind.env
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as string]
            [openmind.edn :as edn]))

(defn var-name [key]
  (-> key
      name
      (string/split #"-")
      (->> (map string/upper-case)
           (interpose "_")
           (apply str))))

(defn read-config []
  (try
    (edn/read-string (slurp "conf.edn"))
    (catch Exception e nil)))

(def ^:private config
  (read-config))

(defn read
  "Read key from environment. key must be defined either in conf.edn in the
  project root or as an env var. Env var wins if both are defined."
  [key]
  (or (System/getenv (var-name key))
      (get config key)))

(defn port
  "Special case: port needs to be a number."
  []
  (let [p (read :port)]
    (cond
      (number? p) p
      (string? p) (Integer/parseInt p)
      :else       (throw (Exception. "Invalid port specified.")))))

(def dev-mode?
  "True iff the server is running in development mode."
  (let [m (read :dev-mode)]
    (or (true? m) (= "TRUE" m))))
