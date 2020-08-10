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
            [openmind.util :as util]
            [openmind.tags :as tags]))

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

(def author
  {:name     "Dev Johnson"
   :orcid-id "0000-NONE"})

(def ex1
  (util/immutable
   {:text         "I am and extract"
    :extract/type :article
    :tags         #{}
    :author       author
    :source       pma}))

(def ex2
  (util/immutable
   {:text         "I am another"
    :extract/type :article
    :tags         #{}
    :author       author
    :source       pma}))

(def labnote
  (util/immutable
   {:text         "Nota bene"
    :author       author
    :tags         #{}
    :extract/type :labnote
    :source       {:lab              "1"
                   :investigators    [{:name "yours truly"}]
                   :institution      "wub"
                   :observation/date (java.util.Date.)}}))

(def trel
  (util/immutable
   {:entity (:hash ex1)
    :attribute :related-to
    :value (:hash ex2)
    :author author}))

(def labrel
  (util/immutable {:entity    (:hash labnote)
                   :value     (:hash ex1)
                   :attribute :confirmed-by
                   :author    author}))

;;;;; Stubs

(def s3-mock (atom {}))

(def notifications (atom []))

;;;;; tests

(defn queues-cleared? []
  (dosync
   (and (empty? @ds/running)
        (empty? (ensure @#'ds/intern-queue))
        (every? empty? (map (comp ensure :tx-queue) @ds/index-map)))))

(defn wait-for-queues
  "Polls intern and indexing queues until all writes are finished. good enough
  for tests."
  []
  (loop []
    (when-not (queues-cleared?)
      (Thread/sleep 100)
      (recur))))

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


  (routes/intern-and-index trel)

  (wait-for-queues)

  ;; Relation was added to both extracts
  (t/is (= #{(:content trel)}
           (:relations (indexing/extract-metadata (:hash ex2)))
           (:relations (indexing/extract-metadata (:hash ex1)))))

  (async/<!! (routes/update-extract! {:editor      author
                                      :previous-id (:hash ex2)
                                      :relations   #{}}))

  (wait-for-queues)

  ;; Relation was deleted from both extracts
  (t/is (= #{}
           (:relations (indexing/extract-metadata (:hash ex2)))
           (:relations (indexing/extract-metadata (:hash ex1)))))

  (async/<!! (routes/update-extract!
              {:editor      author
               :previous-id (:hash labnote)
               :relations   #{(:content labrel)}}))

  (wait-for-queues)

  (t/is (= #{(:content labrel)}
           (:relations (indexing/extract-metadata (:hash labnote)))
           (:relations (indexing/extract-metadata (:hash ex1)))))

  (let [figure (util/immutable
                {:image-data "data::"
                 :author     author
                 :caption    "I'm not real."})
        nl     (util/immutable
                (assoc (:content labnote)
                       :history/previous-version (:hash labnote)
                       :figure (:hash figure)
                       :tags #{(key (first tags/tag-tree))}))]
    (async/<!!
     (routes/update-extract!
      {:editor      author
       :figure      figure
       :new-extract nl
       :previous-id (:hash labnote)}))

    (wait-for-queues)

    (t/is (= (get @s3-mock (:hash figure)) figure)
          "Figure was not interned during update.")

    (t/is (= (assoc (:content labrel)
                    :entity (:hash nl))
             (first (:relations (indexing/extract-metadata (:hash nl)))))
          "relation entity was not updated during metadata migration.")

    ;; REVIEW: I don't think that this is the behaviour we want. But it is the
    ;; behaviour we have.
    ;;
    ;; Instead the indexed relation in the metadata of ex1 should change to
    ;; reflect the new version of labnote (nl).
    (t/is (= (:content labrel)
             (first (:relations (indexing/extract-metadata (:hash ex1))))))


    (let [r3 {:author author
              :entity (:hash nl)
              :attribute :contrast-to
              :value (:hash ex2)}]
      (async/<!!
       (routes/update-extract!
        {:editor author
         :previous-id (:hash nl)
         :relations #{r3}}))

      (wait-for-queues)

      ;; FIXME: This is one big reason why relations should change to reflect
      ;; new versions of extracts.
      (t/is (= (:content labrel)
               (first (:relations (indexing/extract-metadata (:hash ex1))))))

      (t/is (= #{r3}
               (:relations (indexing/extract-metadata (:hash ex2)))
               (:relations (indexing/extract-metadata (:hash nl))))))

    )


  )

(defn isolate [f]
  (with-redefs [s3/exists? #(contains? @s3-mock %)
                s3/lookup  #(get @s3-mock % nil)
                s3/write!  (fn [k o] (swap! s3-mock assoc k o))

                notify/get-all-connections-fn (atom (fn [] ["uid1"]))
                notify/send-fn                (atom
                                               (fn [_ m]
                                                 (swap! notifications conj m)))

                es/index-extract!   (fn [_] (async/go {:status 200}))
                es/add-to-index     (fn [_] (async/go {:status 200}))
                es/retract-extract! (fn [_] (async/go {:status 200}))
                es/replace-in-index (fn [_ _] (async/go {:status 200}))]
    (f)))

(defn test-ns-hook []
  (isolate extract-lifecycle))
