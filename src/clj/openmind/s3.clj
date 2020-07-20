(ns openmind.s3
  (:require [clojure.core.memoize :as memo]
            [openmind.data-caching :refer [recently-added]]
            [openmind.edn :as edn]
            [openmind.env :as env]
            [taoensso.timbre :as log])
  (:import com.amazonaws.auth.BasicAWSCredentials
           [com.amazonaws.services.s3 AmazonS3Client AmazonS3ClientBuilder]))

(def ^String bucket
  (env/read :s3-data-bucket))

(def ^AmazonS3Client client
  (try
    (if env/dev-mode?
      (AmazonS3Client. (BasicAWSCredentials.
                        (env/read :aws-access-key) (env/read :aws-secret-key)))

      (AmazonS3ClientBuilder/defaultClient))
    (catch Exception e nil)))

(defn- lookup-raw [key]
  (try
    (let [content (-> (.getObject client bucket (str key))
                      .getObjectContent
                      slurp)]
      (edn/read-string content))
    ;; S3 throws an exception if the object doesn't exist. We just return nil.
    (catch Exception - nil)))

(defn- active-cache-lookup [k]
  (or
   (get @recently-added k)
   (lookup-raw k)
   ;; If we don't throw, then lookup* will cache nil, which is wrong. We might
   ;; have received a reference to something that hasn't made it to our local
   ;; datastore yet.
   (throw (Exception. "don't cache this!"))))

(def ^:private lookup*
  (memo/lru lookup-raw :lru/threshold 128))

(defn lookup
  "Returns full doc from S3 associated with key `k`."
  [k]
  (try
    (lookup* k)
    (catch Exception _ nil)))

(defn exists?
  "Returns `true` if key exists in datastore, `false` otherwise."
  [key]
  (try
    (.doesObjectExist client bucket (str key))
    (catch Exception e false)))

(defn write!
  "Write `obj` to key `k`. Won't write `nil`."
  [k obj]
  (if (nil? obj)
    (log/error "attempting to store nil under" k)
    (.putObject client bucket (str k) (str obj))))
