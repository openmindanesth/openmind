(ns setup
  (:require [clojure.core.async :as async]
            [openmind.elastic :as elastic]
            [openmind.indexing :as index]
            [openmind.s3 :as s3]
            [openmind.util :as util]))

;;;;; Elasticsearch setup

(defn init-cluster! []
  (async/go
    ;; These have to happen sequentially.
    (println
     (async/<!
      (elastic/send-off!
       (assoc (elastic/create-index elastic/index) :method :delete))))
    (println
     (async/<! (elastic/send-off! (elastic/create-index elastic/index))))
    (println
     (async/<! (elastic/send-off! (elastic/set-mapping elastic/index))))))

(defn load-es-from-s3! []
  (let [extract-ids (-> elastic/active-es-index
                        s3/lookup
                        :content)
        extracts (map s3/lookup extract-ids)]
    (async/go
      (async/<! (init-cluster!))
      (run! elastic/index-extract! extracts))))

(defn dump-elastic!
  "Dump last 100 extracts from elastic to edn file.
  This isn't the recommended way to save/restore elastic. For that use the s3
  datastore and `load-es-from-s3!`."
  [filename]
  (async/go
    (->> elastic/most-recent
         elastic/send-off!
         async/<!
         elastic/parse-response
         :hits
         :hits
         (mapv :_source)
         (spit filename))))

;;;;; S3 datastore index init

(def active-extracts-stub
  [#openmind.hash/ref "723701947793c75d6816580e5b6aa131"
   #openmind.hash/ref "552fa91af86fc432a292091c0b1331ab"

   #openmind.hash/ref "d594f8e73658cc09a9ab473d08de5095"
   #openmind.hash/ref "b88867f28b17626b736c19e4e2454ddb"
   #openmind.hash/ref "96ea2c806cd229176c43e40d001b16ea"])

(def em-stub
  (mapv (fn [h] (util/immutable {:extract h})) active-extracts-stub))

(def extract-metadata-stub
  (util/immutable
   (zipmap active-extracts-stub
           (map :hash em-stub))))

(defn wipe-metadata!
  "Creates a new index with a hard-coded set of things and resets the head
  pointer. Make sure you write down the old one in case you want to go back."
  []
  (when-let [current (s3/lookup index/extract-metadata-uri)]
    (println "Replacing index head: " (:hash current)
             "with: " (:hash extract-metadata-stub)))
  ;; TODO: git reset analog
  (run! s3/intern em-stub)
  (s3/intern extract-metadata-stub)
  (@#'s3/index-compare-and-set! index/extract-metadata-uri
   (s3/lookup index/extract-metadata-uri)
   extract-metadata-stub))
