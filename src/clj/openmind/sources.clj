(ns openmind.sources
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [openmind.sources.pubmed :as pubmed]
            [openmind.sources.biorxiv :as biorxiv]
            [openmind.url :as url]
            [taoensso.timbre :as log]))

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
    ::doi
    (let [{:keys [^String domain]} (url/parse s)]
      (cond
        (.contains domain "ncbi.nlm.nih.gov") ::pubmed
        (.contains domain "biorxiv.org")      ::biorxiv
        :else                                 ::unknown))))


(defmulti article-details find-id)

(defmethod article-details ::unknown
  [s]
  (async/go
    (log/warn "attempt to lookup unknown article:" s)))

(defmethod article-details ::doi
  [s]
  (pubmed/find-article (doi s)))

(defmethod article-details ::pubmed
  ;; Can be pubmed or pubmed central. Both hit the same API.
  [s]
  (pubmed/find-article (pubmed/pmid s)))

(defmethod article-details ::biorxiv
  [s]
  (biorxiv/find-article s))

(defn lookup [s]
  (async/go
    (try
      (let [ch (article-details s)]
        (let [[val port] (async/alts! [ch (async/timeout 10000)])]
          (when (= port ch)
            (if (s/valid? :openmind.spec.extract/source val)
              val
              (log/info "Invalid source retrieved:\n"
               (s/explain-data :openmind.spec.extract/source val))))))
      (catch Exception e
        (log/error "failure in article lookup\n"
                   e)))))
