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
        id (last (string/split path #"/"))]
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
                          (let [c (first (:content s))]
                            (get sup-map c c))))
                      content))))

(defn content [x]
  (-> x first :content first))

(def months
  "Why does this need to be done?"
  {"jan"       0
   "january"   0
   "feb"       1
   "february"  1
   "mar"       2
   "march"     2
   "apr"       3
   "april"     3
   "may"       4
   "jun"       5
   "june"      5
   "jul"       6
   "july"      6
   "aug"       7
   "august"    7
   "sep"       8
   "sept"      8
   "september" 8
   "oct"       9
   "october"   9
   "nov"       10
   "november"  10
   "dec"       11
   "december"  11})

(defn parse-month [s]
  (try
    (dec (Long/parseLong s))
    (catch Exception e
      (or (get months (string/lower-case s)) 0))))

(defn pubdate [issue]
  (let [y (Long/parseLong (content (filter-by-tag :Year issue)))
        m (parse-month (content (filter-by-tag :Month issue)))
        d (Long/parseLong (or (content (filter-by-tag :Day issue)) "1"))]
    (.getTime ^java.util.Calendar (java.util.GregorianCalendar. y m d))))

(defn parse-author [author]
  ;; REVIEW: This has a lot more info than we're taking: Orcid id, affilliation, etc.
  (let [first    (content (filter-by-tag :ForeName author))
        initials (content (filter-by-tag :Initials author))
        last     (content (filter-by-tag :LastName author))
        orcid-id (content (filter-by-attr :Source "ORCID" author))]
    (merge
     {:full-name  (str first " " last)
      :short-name (str last ", " initials)}
     (when orcid-id
       {:orcid-id orcid-id}))))

(defn extract-article-info [xml]
  (let [article       (first (filter-by-tag :Article xml))
        title         (extract-text (first (filter-by-tag :ArticleTitle article)))
        journal-item  (first (filter-by-tag :Journal article))
        journal-name  (content (filter-by-tag :ISOAbbreviation journal-item))
        journal-issue (first (filter-by-tag :JournalIssue journal-item))
        volume        (content (filter-by-tag :Volume journal-issue))
        issue         (content (filter-by-tag :Issue journal-issue))
        doi           (content (filter-by-attr :EIdType "doi" article))
        author-list   (filter-by-tag :Author article)
        abstract      (extract-text (first (filter-by-tag :AbstractText article)))
        pdate         (try (pubdate journal-issue)
                           (catch Exception _
                             (pubdate (content (filter-by-tag :PubmedArticle xml)))))]
    {:publication/date pdate
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
