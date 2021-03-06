(ns openmind.elastic-test
  "This test, exceptionally, requires a running elasticsearch instance. Its main
  purpose is to test that various kinds of searches return sensible results."
  ;; TODO: Change this to grab extracts from the index and test queries on
  ;; them. Real data is much better than spec data for testing queries. We also
  ;; don't need to test that putting new extracts into elastic works. Though it
  ;; is good to test that the mapping is valid on the version of elastic we're
  ;; using.
  ;; TODO: Separate the infrastructure test from the query tests
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :as t]
            [openmind.elastic :as es]
            [openmind.spec.extract :as exs]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

(def N
  "Number of random searches to try of each type"
  10)

(defn sync-req [req]
  (-> req
      es/send-off!
      async/<!!
      es/parse-response
      :hits
      :hits))

(defonce extracts
  (mapv (fn [[e _]]
         (util/immutable e))
       (s/exercise ::exs/extract 100)))

(defn by-id
  "Finds generated extract by hash. Essential for figuring out failed tests."
  [id]
  (first (filter #(= id (:hash %)) extracts)))

(t/deftest populate-elastic
  (doall
   (map (fn [e]
          (let [res
                (-> e
                    es/index-extract!
                    async/<!!)]
            ;; Elastic returns 200 or 201 according to a whim I can't find
            ;; documents for.
            (t/is (<= 200 (:status res) 201)
                  res)))
        extracts)))

(defn result-set [q]
  (->> (assoc q :limit 200)
       es/search-q
       sync-req
       (map (comp :hash :_source))
       (into #{})))

(defn most-recent-order? [ds]
  (boolean
   (reduce (fn [^java.util.Date acc ^java.util.Date x]
             (if (or (.equals x acc) (.before x acc))
               x
               (reduced false)))
           ds)))

(t/deftest sort-order
  (->> {:sort-by :publication-date
        :limit   100}
       es/search-q
       sync-req
       (map (comp :es/pub-date :_source))
       most-recent-order?
       t/is)
  (->> {:sort-by :extract-creation-date
        :limit   100}
       es/search-q
       sync-req
       (map (comp :time/created :_source))
       most-recent-order?
       t/is))

(t/deftest term-search
  ;; token search
  (dotimes [i N]
    (let [ex   (rand-nth extracts)
          term (first (string/split (:text (:content ex)) #" "))
          hits (result-set {:term term})]
      (t/is (contains? hits (:hash ex))))
    ;; prefix search
    (let [ex   (rand-nth extracts)
          term (apply str (take 3 (:text (:content ex))))
          hits (result-set {:term term})]
      (when (> 2 (count term))
        (t/is (contains? hits (:hash ex)))))))

(t/deftest tag-filter
  (dotimes [i N]
    (let [ex   (rand-nth extracts)
          tags (:tags (:content ex))
          hits (result-set {:filters tags})
          hits-1 (result-set {:filters (take 1 tags)})]
      (t/is (contains? hits (:hash ex)))
      (t/is (contains? hits-1 (:hash ex))))))

(t/deftest search-by-doi
  (dotimes [i N]
    (let [ex  (rand-nth extracts)
          doi (-> ex :content :source :doi)]
      (when doi
        (let [hits (result-set {:term doi})]
          (t/is (contains? hits (:hash ex))))))))

(t/deftest search-by-author
  (dotimes [i N]
    (let [ex (rand-nth extracts)
          author (-> ex
                     :content
                     :author
                     :name)]
      (when author
        (let [hits (result-set {:term (apply str (take 5 author))})]
          (t/is (contains? hits (:hash ex))))))))

(t/deftest advanced-search)

(defn test-ns-hook []
  (populate-elastic)
  (sort-order)
  (term-search)
  (tag-filter)
  (search-by-doi)
  (search-by-author))
