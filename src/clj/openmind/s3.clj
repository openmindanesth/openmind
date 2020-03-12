(ns openmind.s3
  (:refer-clojure :exclude [intern])
  (:require [clojure.spec.alpha :as s]
            [openmind.env :as env]
            [openmind.hash]
            [taoensso.timbre :as log])
  (:import com.amazonaws.auth.BasicAWSCredentials
           [com.amazonaws.services.s3 AmazonS3Client AmazonS3ClientBuilder]
           openmind.hash.ValueRef))

(def ^String bucket
  (env/read :s3-data-bucket))

(def ^AmazonS3Client client
  (try
    (if env/dev-mode?
      (AmazonS3Client. (BasicAWSCredentials.
                        (env/read :aws-access-key) (env/read :aws-secret-key)))

      (AmazonS3ClientBuilder/defaultClient))
    (catch Exception e nil)))

(defn lookup [key]
  (try
    (let [content (-> (.getObject client bucket (str key))
                      .getObjectContent
                      slurp)]
      (binding [*read-eval* nil]
        (read-string content)))
    (catch Exception e nil)))

(defn exists? [key]
  (try
    (.doesObjectExist client bucket (str key))
    (catch Exception e nil)))

(defn- write! [k obj]
  (.putObject client bucket (str k) (str obj)))

(defn intern [obj]
  (if (s/valid? :openmind.spec/immutable obj)
    (let [key (:hash obj)]
      ;; TODO: Locking. We really want to catch and abort on collisions, as
      ;; improbable as they may be.
      (if (exists? key)
        (if (not= (:content obj) (:content (lookup key)))
          (log/error "Collision in data store! Failed to add" obj)
          (log/info "Attempting to add data with hash:" key
                    "which already exists. Doing nothing."))
        (write! key obj)))
    (log/error "Invalid data received to intern"
               (s/explain-data :openmind.spec/immutable obj))))
