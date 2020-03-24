(ns openmind.pubmed
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.xml :as xml]
            [openmind.env :as env]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

(defn filter-by-attr [attr val elem]
  (if (-> elem :attrs attr (= val))
    (list elem)
    (mapcat (partial filter-by-attr attr val) (:content elem))))

(defn filter-by-tag [tag elem]
  (if (-> elem :tag (= tag))
    (list elem)
    (mapcat (partial filter-by-tag tag) (:content elem))))

(def url "https://www.ncbi.nlm.nih.gov/pubmed/31544820")

(defonce body (atom nil))

(defn- grab-article [url]
  (let [out (async/promise-chan)]
    (http/request {:method :get :url url}
                  (fn [res]
                    (if (= 200 (:status res))
                      (async/put! out (:body res))
                      (async/close! out))))
    out))

(defn parse-xml-string [body]
  ;; HACK: Strip the DTD declaration manually. DTD just breaks clojure.xml
  ;; somehow.
  (let [no-dtd (apply str (drop 160 body))
        stream (java.io.ByteArrayInputStream. (.getBytes ^String no-dtd))]
    (xml/parse stream)))

(def es-date-formatter
  (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX"))

;; FIXME: What a monster.
(defn extract-article-info [xml]
  (let [main     (first (filter-by-attr :id "maincontent" xml))
        publine  (->> main (filter-by-attr :class "cit") first :content second)
        ;; HACK: Just leave the date as an arbitrary string because it isn't
        ;; always well formatted.
        date     (-> publine (string/split #";")
                     first
                     (string/split #" ")
                     rest
                     (->>
                      (interpose " ")
                      (apply str)))
        doi      (-> publine
                     (string/split #"doi:")
                     last
                     string/trim)
        doi      (subs doi 0 (dec (count doi)))
        title    (-> (filter-by-tag :h1 main) first :content first)
        journal  (->> main
                      (filter-by-attr :class "cit")
                      first :content first :attrs :title )
        authors  (->> main
                      (filter-by-attr :class "auths")
                      first
                      :content
                      (filter #(= :a (:tag %)))
                      (mapcat :content)
                      (into []))
        abstract (->> main
                      (filter-by-attr :class "abstr")
                      first
                      (filter-by-tag :p)
                      first
                      :content
                      (filter string?)
                      (apply str))]
    {:publication/date date
     :doi              doi
     :authors          authors
     :title            title
     :abstract         abstract
     :journal          journal}))

(defn article-info [url]
  (let [out (async/promise-chan)]
    (async/pipeline 1 out
                    (comp (map parse-xml-string)
                          (map extract-article-info))
                    (grab-article url))
    out))
