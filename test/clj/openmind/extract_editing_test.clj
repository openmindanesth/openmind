(ns openmind.extract-editing-test
  (:require [clojure.test :as t]
            [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [openmind.datastore :as ds]
            [openmind.s3 :as s3]
            [openmind.indexing :as indexing]
            [openmind.elastic :as es]
            [openmind.notification :as notify]
            [openmind.routes :as routes]
            [openmind.sources :as sources]
            [openmind.util :as util]))

;;;;; Dummy data

(def pma
  {:pubmed/id        "26821826",
   :abstract
   "Combining mouse genomics and functional magnetic resonance imaging (fMRI) provides a promising tool to unravel the molecular mechanisms of chronic pain. Probing murine nociception via the blood oxygenation level-dependent (BOLD) effect is still challenging due to methodological constraints. Here we report on the reproducible application of acute noxious heat stimuli to examine the feasibility and limitations of functional brain mapping for central pain processing in mice. Recent technical and procedural advances were applied for enhanced BOLD signal detection and a tight control of physiological parameters. The latter includes the development of a novel mouse cradle designed to maintain whole-body normothermia in anesthetized mice during fMRI in a way that reflects the thermal status of awake, resting mice. Applying mild noxious heat stimuli to wildtype mice resulted in highly significant BOLD patterns in anatomical brain structures forming the pain matrix, which comprise temporal signal intensity changes of up to 6% magnitude. We also observed sub-threshold correlation patterns in large areas of the brain, as well as alterations in mean arterial blood pressure (MABP) in response to the applied stimulus. ",
   :journal          "Sci Rep",
   :doi              "10.1038/srep17230",
   :title
   "Normothermic Mouse Functional MRI of Acute Focal Thermostimulation for Probing Nociception.",
   :volume           "6",
   :url              "https://pubmed.ncbi.nlm.nih.gov/26821826",
   :peer-reviewed?   true,
   :authors
   [{:full-name "Henning Matthias Reimann", :short-name "Reimann, HM"}
    {:full-name "Jan Hentschel", :short-name "Hentschel, J"}
    {:full-name "Jaroslav Marek", :short-name "Marek, J"}
    {:full-name "Till Huelnhagen", :short-name "Huelnhagen, T"}
    {:full-name "Mihail Todiras", :short-name "Todiras, M"}
    {:full-name "Stefanie Kox", :short-name "Kox, S"}
    {:full-name "Sonia Waiczies", :short-name "Waiczies, S"}
    {:full-name "Russ Hodge", :short-name "Hodge, R"}
    {:full-name "Michael Bader", :short-name "Bader, M"}
    {:full-name "Andreas Pohlmann", :short-name "Pohlmann, A"}
    {:full-name "Thoralf Niendorf", :short-name "Niendorf, T"}],
   :publication/date #inst "2016-01-29T05:00:00.000-00:00"})

(def ex1
  (util/immutable
   {:text         "I am and extract"
    :extract/type :article
    :tags         #{}
    :author       {:name     "Dev Johnson"
                   :orcid-id "0000-NONE"}
    :source       pma}))

(def ex2
  (util/immutable
   {:text         "I am another"
    :extract/type :article
    :tags         #{}
    :author       {:name     "Dev Johnson"
                   :orcid-id "0000-NONE"}
    :source       pma}))

(def labnote
  (util/immutable
   {:text         "Nota bene"
    :author       {:name     "Dev Johnson"
                   :orcid-id "0000-NONE"}
    :tags         #{}
    :extract/type :labnote
    :source       {:lab              "1"
                   :investigator     "yours truly"
                   :institution      "wub"
                   :observation/date (java.util.Date.)}}))
;;;;; Stubs

(def s3-mock (atom {}))

(def notifications (atom []))

;;;;; tests

(defn queues-cleared []
  (dosync
   (and (empty? @ds/running)
        (empty? (ensure @#'ds/intern-queue))
        (every? empty? (map (comp ensure :tx-queue) @ds/index-map)))))

(defn wait-for-queues
  "Polls intern-queue until all writes are finished. good enough for tests."
  []
  (loop []
    (when-not (queues-cleared)
      (Thread/sleep 100)
      (recur))))

(defn ws-req [t ex]
  {:uid    "uid1"
   :id     t
   :event  [t {:extract ex}]
   :tokens {:name     "Dev Johnson 1"
            :orcid-id "0000-NONE"}})

(defn summarise-notifications []
  {:created (->> @notifications
                 (filter #(= (first %) :openmind/extract-created))
                 (map second)
                 (into #{}))
   :updated (->> @notifications
                 (filter #(= (first %) :openmind/updated-metadata))
                 (map second)
                 (map :extract)
                 frequencies)})

(t/deftest extract-lifecycle
  (run! #(async/<!! (routes/write-extract! % [] "uid1"))
        [ex1 ex2 labnote])

  (wait-for-queues)

  ;; Extracts added to datastore
  (t/is (every? #(contains? @s3-mock (:hash %)) [ex1 ex2 labnote]))

  ;; blank metadata was added to each extract
  (t/is (every? #(= (indexing/extract-metadata (:hash %))
                    {:extract (:hash %)})
                [ex1 ex2 labnote]))

  ;; Creation notifications
  (t/is (every? #(contains? (:created (summarise-notifications)) (:hash %))
                [ex1 ex2 labnote]))

  ;; Metadata creation (update is creation in this context) notifications
  (t/is (every? #(contains? (:updated (summarise-notifications)) (:hash %))
                [ex1 ex2 labnote]))


  )

(defn test-ns-hook []
  (with-redefs [s3/exists? #(contains? @s3-mock %)
                s3/lookup  #(get @s3-mock % nil)
                s3/write!  (fn [k o] (swap! s3-mock assoc k o))

                notify/get-all-connections-fn (atom (fn [] ["uid1"]))
                notify/send-fn                (atom
                                               (fn [_ m]
                                                 (swap! notifications conj m)))

                es/index-extract!   (fn [_] (async/go {:status 200}))
                es/add-to-index     (constantly nil)
                es/retract-extract! (constantly nil)
                es/replace-in-index (constantly nil)]
    (extract-lifecycle)))
