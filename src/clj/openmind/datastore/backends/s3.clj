(ns openmind.datastore.backends.s3
  (:require [clojure.core.memoize :as memo]
            [openmind.edn :as edn]
            [openmind.env :as env]
            [taoensso.timbre :as log])
  (:import [software.amazon.awssdk.auth.credentials
            AwsBasicCredentials StaticCredentialsProvider]
           software.amazon.awssdk.core.sync.RequestBody
           software.amazon.awssdk.regions.Region
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model
            GetObjectRequest HeadObjectRequest PutObjectRequest]))

(def ^String bucket
  (env/read :s3-data-bucket))

(def ^S3Client client
  (try
    (if env/dev-mode?
      (.build (doto (S3Client/builder)
                (.credentialsProvider
                 (StaticCredentialsProvider/create
                  (AwsBasicCredentials/create
                   (env/read :aws-access-key)
                   (env/read :aws-secret-key))))
                (.region Region/EU_CENTRAL_1)))
      (S3Client/create))
    (catch Exception e nil)))

(defn- ^HeadObjectRequest headreq
  [k]
  (.build (doto (HeadObjectRequest/builder)
            (.bucket bucket)
            (.key (str k)))))

(defn exists?
  "Returns `true` if key exists in datastore, `false` otherwise."
  [key]
  (boolean
   (try
     (.headObject client (headreq key))
     (catch Exception e false))))

(defn- ^GetObjectRequest getreq
  "Returns an aws GetObjectRequest which requests the object with key `k`."
  [k]
  (.build (doto (GetObjectRequest/builder)
            (.bucket bucket)
            (.key (str k)))))

(defn lookup-raw [key]
  (-> (.getObject client (getreq key))
      slurp
      edn/read-string))

(def ^:private lookup*
  (memo/lru lookup-raw :lru/threshold 128))

(defn lookup
  "Returns full doc from S3 associated with key `k`."
  [k]
  (try
    (lookup* k)
    (catch Exception _ nil)))

(defn ^PutObjectRequest putreq [k]
  (.build (doto (PutObjectRequest/builder)
            (.bucket bucket)
            (.key (str k)))))

(defn ^RequestBody rb [obj]
  (RequestBody/fromString (str obj)))

(defn write!
  "Write `obj` to key `k`. Won't write `nil`."
  [k obj]
  (if (nil? obj)
    (log/error "attempting to store nil under" k)
    (.putObject client (putreq k) (rb obj))))
