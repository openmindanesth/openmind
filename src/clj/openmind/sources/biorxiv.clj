(ns openmind.sources.biorxiv
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [openmind.sources.common :as common]
            [openmind.url :as url]))

(defn biorxiv-api-url [doi]
  (str "https://api.biorxiv.org/details/biorxiv/" doi))

(defn biorxiv [url]
  (-> url
        url/parse
        :path
        (string/replace-first #"/content/" "")
        (string/split #"v")
        first
        biorxiv-api-url
        common/fetch))

(defn parse-authors [s]
  (mapv (fn [x]
          {:short-name (string/trim x)})
       (string/split s #";")))

(def ^java.text.SimpleDateFormat formatter
  (java.text.SimpleDateFormat. "yyyy-MM-dd"))

(defn date-from [s]
  (.parse formatter s))

(defn format-source [{[{:keys [published date authors] :as res}] :collection}]
  (merge (select-keys res [:title :abstract :doi])
         {:peer-reviewed? (not= published "NA")
          :authors (parse-authors authors)
          :publication/date (date-from date)}))

(defn find-article [url]
  (async/go
    (try
      (let [res (biorxiv url)]
        (-> res
            async/<!
           (json/read-str :key-fn keyword)
           format-source
           (assoc :url url)))
      (catch Exception _
        nil))))
