(ns populate-elastic
  (:require [clojure.core.async :as async]
            [openmind.elastic :as elastic]
            [openmind.spec :as s]
            [openmind.datastore :as ds]
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
                        ds/get-index
                        :content)
        extracts (map ds/lookup extract-ids)]
    (async/go
      (async/<! (init-cluster!))
      (println (str "loading " (count extract-ids) " extracts"))
      (run! (fn [{:keys [hash content time/created author]}]
              (elastic/index-extract! hash created author content))
            extracts))))

(defn -main [& args]
  (async/<!! (load-es-from-s3!)))
