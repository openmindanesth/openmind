(ns openmind.s3
  (:refer-clojure :exclude [intern])
  (:require [clojure.spec.alpha :as s]
            [openmind.env :as env]
            [openmind.hash]
            [openmind.spec :as spec]
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

(def ^:private master-index-url
  "openmind.index/master")

(def ^:private index-cache
  (atom {}))

;; TODO: subscription based logic and events tracking changes to the
;; master-index. We don't want 2 round trips for every lookup.
(defn master-index []
  (lookup master-index-url))

(defn index-compare-and-set! [old-index new-index]
  ;; TODO: lock between machines!!
  (if (and
       (s/valid? ::spec/immutable new-index)
       (s/valid? :openmind.spec.indexical/master-index (:content new-index)))
    (locking index-cache
      (let [current (master-index)]
        (if (= current old-index)
          (do
            ;; TODO: Check for successful write.
            (write! master-index-url new-index)
            true)
          false)))
    ;; Return false on fail, nil on error.
    (log/error "Invalid index" new-index)))

(defn get-index
  [h]
  (if-let [index (get @index-cache h)]
    (:content index)
    (let [index (lookup h)]
      (swap! index-cache assoc h index)
      (:content index))))
