(ns openmind.pubmed
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.string :as string]
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

(defn- pubmed-efetch-url [url]
  (let [path  (-> url
                  (string/split #"\?")
                  first
                  (string/split #"#")
                  first)
        parts (string/split path #"/")
        id    (if (string/ends-with? path "/")
                (last (butlast path))
                (last path))]
    (str "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi"
         "?db=pubmed&retmode=xml&id="
         id)))

(defn- grab-article [url]
  (let [out (async/promise-chan)
        url (pubmed-efetch-url url)]
    (http/request {:method :get :url url}
                  (fn [res]
                    (if (= 200 (:status res))
                      (async/put! out (:body res))
                      (async/close! out))))
    out))

(defn parse-xml-string [body]
  ;; HACK: Strip the DTD declaration manually. DTD just breaks clojure.xml
  ;; somehow.
  (let [stream (java.io.ByteArrayInputStream. (.getBytes ^String body))]
    (try
      (xml/parse stream)
      (catch Exception e (log/error "XML parser error:" e)))))

(def sup-map
  {"TM" "™"})

(defn extract-text [{:keys [content]}]
  (apply str
         (remove nil?
                 (map (fn [s]
                        (if (string? s)
                          s
                          (get sup-map (first (:content s)))))
                      content))))

(defn content [x]
  (-> x first :content first))

(defn pubdate [issue]
  (str (content (filter-by-tag :Year issue)) " "
       (content (filter-by-tag :Month issue)) " "
       (content (filter-by-tag :Day issue))))

(defn parse-author [author]
  ;; REVIEW: This has a lot more info than we're taking: Orcid id, affilliation, etc.
  (str (content (filter-by-tag :ForeName author)) " "
       (content (filter-by-tag :Initials author)) " "
       (content (filter-by-tag :LastName author))))

;; FIXME: What a monster.
(defn extract-article-info [xml]
  (let [article       (first (filter-by-tag :Article xml))
        title         (content (filter-by-tag :ArticleTitle article))
        journal-item  (first (filter-by-tag :Journal article))
        journal-name  (content (filter-by-tag :ISOAbbreviation journal-item))
        journal-issue (first (filter-by-tag :JournalIssue journal-item))
        volume        (content (filter-by-tag :Volume journal-issue))
        issue         (content (filter-by-tag :Issue journal-issue))
        doi           (content (filter-by-attr :EIdType "doi" article))
        author-list   (filter-by-tag :Author article)
        abstract      (extract-text (first (filter-by-tag :AbstractText article)))]
    {:publication/date (pubdate journal-issue)
     :title            title
     :abstract         abstract
     :journal          journal-name
     :issue            issue
     :volume           volume
     :doi              doi
     :authors          (mapv parse-author author-list)}))

(defn article-info [url]
  (async/go
    (-> url
        grab-article
        async/<!
        parse-xml-string
        extract-article-info)))
