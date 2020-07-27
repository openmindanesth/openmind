(ns openmind.sources-test
  (:require [clojure.core.async :as async]
            [clojure.test :as t]
            [openmind.sources :as s]))

(let [d1      "https://doi.org/10.1038/srep17230"
      d2      "10.1038/srep17230"
      pm      "https://pubmed.ncbi.nlm.nih.gov/26821826/"
      pmc     "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4731789/"
      bio     "https://www.biorxiv.org/content/10.1101/2020.07.26.222125v1"
      bio-doi "https://doi.org/10.1101/2020.07.26.222125"]
  (t/deftest id-sorting
    (t/is (every? #(= (s/find-id %) ::s/doi) [d1 d2 bio-doi]))
    (t/is (= ::s/pubmed (s/find-id pm)))
    (t/is (= ::s/pubmed (s/find-id pmc)))
    (t/is (= ::s/biorxiv (s/find-id bio))))

  (t/deftest doi-resolution
    (t/is (= (s/doi d1) (s/doi d2))))

  ;; Ideally, I'd test the fetched results and make sure that fetching by pmurl
  ;; pmc url, doi, etc, all return the same info. Unfortuantely the api is
  ;; flakey and if you call it four times at once it will fail three of those.
  )
