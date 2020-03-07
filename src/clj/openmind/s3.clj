(ns openmind.s3
  (:refer-clojure :exclude [intern])
  (:require [clojure.spec.alpha :as s]
            [openmind.env :as env]
            [taoensso.timbre :as log])
  (:import com.amazonaws.auth.BasicAWSCredentials
           [com.amazonaws.services.s3 AmazonS3Client AmazonS3ClientBuilder]
           openmind.hash.ValueRef))

(def ^String bucket
  (env/read :s3-data-bucket))

(def ^AmazonS3Client client
  (if (true? (env/read :dev-mode))
    (AmazonS3Client. (BasicAWSCredentials.
                      (env/read :aws-key) (env/read :aws-secret)))

    (AmazonS3ClientBuilder/defaultClient)))

(defn lookup [^String key]
  (try
    (-> (.getObject client bucket key)
        .getObjectContent
        slurp
        read-string)
    (catch Exception e nil)))

(defn exists? [^String key]
  (try
    (.doesObjectExist client bucket key)
    (catch Exception e nil)))

(defn- write! [^String k obj]
  (.putObject client bucket k (str obj)))

(defn intern [obj]
  {:pre [(s/valid? :openmind.spec/immutable obj)]}
  (let [key (.-hash-string ^ValueRef (:hash obj))]
    ;; TODO: Locking. We really want to catch and abort on collisions, as
    ;; improbable as they may be.
    (if (exists? key)
      (if (not= (:content obj) (:content (lookup key)))
        (log/error "Collision in data store! Failed to add" obj)
        (log/info "Attempting to add data with hash:" key
                  "which already exists. Doing nothing."))
      (write! key obj))))
