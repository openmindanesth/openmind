(ns openmind.pubmed
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.string :as string]
            [openmind.url :as url]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; XML parsing nonsense
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn filter-by-attr [attr val elem]
  (if (-> elem :attrs attr (= val))
    (list elem)
    (mapcat (partial filter-by-attr attr val) (:content elem))))

(defn filter-by-tag [tag elem]
  (if (-> elem :tag (= tag))
    (list elem)
    (mapcat (partial filter-by-tag tag) (:content elem))))


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
     :peer-reviewed?   true
     :doi              doi
     :authors          (mapv parse-author author-list)}))

(defn- fetch
  "Sends a GET request for `url` and returns a channel which will eventually
  yield the body of the response if the status is 200. The channel will close
  without emitting anything if the request fails."
  [url]
  (let [out (async/promise-chan)]
    (http/request {:method :get :url url}
                  (fn [res]
                    (if (= 200 (:status res))
                      (async/put! out (:body res))
                      (async/close! out))))
    out))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; ID conversion
;;
;; Pubmed central provides a very handy service to translate between pubmed IDs,
;; PMC IDs, DOIs, and Manuscript IDs (which I don't expect we'll be terribly
;; concerned with).
;;
;; This would be awesome, except that it doesn't work. I've found dozens of
;; articles on pubmed that are there if you search for them, but for which this
;; api returns ID errors for the DOI. Sometimes it seems this API expects the
;; DOI in a different format (it works if you remove some suffix of the DOI),
;; but for others I can't find any pattern to the failure.
;;
;; TODO: investigate further. This would be a much better way to resolve things.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def id-converter-base
  "https://www.ncbi.nlm.nih.gov/pmc/utils/idconv/v1.0/")

(defn id-query-uri [id]
  (str id-converter-base "?ids="
       (java.net.URLEncoder/encode id)
       (when (doi? id) "&idtype=doi")
       "&format=json"))

(defn id-conversion [id]
  (async/go
    (-> id
        id-query-uri
        fetch
        async/<!
        (json/read-str :key-fn keyword)
        :records
        first)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Web logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-pm-short "https://pubmed.ncbi.nlm.nih.gov/22945293/")
(def test-url "https://www.ncbi.nlm.nih.gov/pubmed/31544820/")
(def test-pmc "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6075724/")

(defn pm-url
  "Returns pubmed url for the given pmid."
  [id]
  (str  "https://pubmed.ncbi.nlm.nih.gov/" id))

(defn pmid
  "Returns the pubmed id from a pubmed url, or the pmcid from a pubmed central
  url."
  [url]
  (let [{:keys [^String domain ^String path]} (url/parse url)]
    (when (.contains domain "ncbi.nlm.nih.gov")
      (last (string/split path #"/")))))

(defn- pubmed-efetch-url [id]
  (str "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi"
       "?db=pubmed&retmode=xml&id="
       id))

(defn- pubmed-search-url [id]
  (str "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
       "?db=pubmed&format=json&term="
       (java.net.URLEncoder/encode id)))

(defn- resolve-id [id]
  (async/go
    (-> id
        pubmed-search-url
        fetch
        async/<!
        (json/read-str :key-fn keyword)
        :esearchresult
        :idlist
        first)))

(defn article-info [pmid]
  (async/go
    (when-let [article (async/<! (fetch (pubmed-efetch-url pmid)))]
      (try
        (-> article
            parse-xml-string
            extract-article-info)
        (catch Exception _ ::failed)))))

(defn doi?
  "Tests whether a string is a DOI."
  [s]
  ;; FIXME: Not a great test...
  (or
   (string/starts-with? s "10.")
   (string/starts-with? s "https://doi.org/10.")))

(defn doi
  [s]
  (if (string/starts-with? s "10.")
    s
    (string/replace-first s #"^https?://doi.org/" "")))

(defn find-id [s]
  (if (doi? s)
    (doi s)
    (pmid s)))

(defn find-article [search-term]
  (async/go
    (let [id          (find-id search-term)
          pmid        (async/<! (resolve-id id))
          source-info (async/<! (article-info pmid))]
      (when-not (= ::failed source-info)
        (assoc source-info
               :url (pm-url pmid)
               :pubmed/id pmid)))))
