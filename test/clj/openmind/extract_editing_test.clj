(ns openmind.extract-editing-test
  (:require [clojure.core.async :as async]
            [clojure.test :as t]
            [openmind.datastore :as ds]
            [openmind.test.common :as c]
            [openmind.datastore.indicies.metadata :as mi]
            [openmind.routes :as routes]
            [openmind.tags :as tags]
            [openmind.hash :as h]))

;;;;; Dummy data

(defn immutable [content author]
  {:hash         (h/hash content)
   :content      content
   :author       author
   :time/created (java.util.Date.)})

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
  (immutable
   {:text         "I am and extract"
    :extract/type :article
    :tags         #{}
    :source       pma}
   author))

(def ex2
  (immutable
   {:text         "I am another"
    :extract/type :article
    :tags         #{}
    :source       pma}
   author))

(def labnote
  (immutable
   {:text         "Nota bene"
    :tags         #{}
    :extract/type :labnote
    :source       {:lab              "1"
                   :investigator     "yours truly"
                   :institution      "wub"
                   :observation/date (java.util.Date.)}}
   author))

(def trel
  (immutable
   {:entity (:hash ex1)
    :attribute :related-to
    :value (:hash ex2)}
   author))

(def labrel
  (immutable {:entity    (:hash labnote)
              :value     (:hash ex1)
              :attribute :confirmed-by}
             author))

;;;;; Stubs

(def notifications-ch (async/chan 256))

(def notifications (atom {:created #{}
                          :updated {}}))

(async/go-loop []
  (when-let [[t v] (async/<! notifications-ch)]
    (case t
      :openmind.extract-created (swap! notifications
                                       update :created
                                       conj v)
      :openmind.updated-metadata (swap! notifications
                                        update :updated
                                        #(if (contains? % v)
                                           (update % v inc)
                                           (assoc % v 1))))))

;;;;; The Tests

(t/deftest extract-creation
  (let [tx1 {:context }]))

#_(t/deftest extract-creation
  (run! #(async/<!! (routes/write-extract! % [] "uid1"))
        [ex1 ex2 labnote])

  (c/wait-for-queues)

  ;; Extracts added to datastore
  (t/is (every? #(contains? @s3-mock (:hash %)) [ex1 ex2 labnote]))

  ;; blank metadata was added to each extract
  (t/is (every? #(= (mi/extract-metadata (:hash %))
                    {:extract (:hash %)})
                [ex1 ex2 labnote]))

  ;; Creation notifications
  (t/is (every? #(contains? (:created @notifications) (:hash %))
                [ex1 ex2 labnote]))

  ;; Metadata creation (update is creation in this context) notifications
  (t/is (every? #(contains? (:updated @notifications) (:hash %))
                [ex1 ex2 labnote]))


  (routes/intern-and-index trel)

  (c/wait-for-queues)

  ;; Relation was added to both extracts
  (t/is (= #{(:content trel)}
           (:relations (mi/extract-metadata (:hash ex2)))
           (:relations (mi/extract-metadata (:hash ex1)))))

  (async/<!! (routes/update-extract! {:editor      author
                                      :previous-id (:hash ex2)
                                      :relations   #{}}))

  (c/wait-for-queues)

  ;; Relation was deleted from both extracts
  (t/is (= #{}
           (:relations (mi/extract-metadata (:hash ex2)))
           (:relations (mi/extract-metadata (:hash ex1)))))

  (async/<!! (routes/update-extract!
              {:editor      author
               :previous-id (:hash labnote)
               :relations   #{(:content labrel)}}))

  (c/wait-for-queues)

  (t/is (= #{(:content labrel)}
           (:relations (mi/extract-metadata (:hash labnote)))
           (:relations (mi/extract-metadata (:hash ex1)))))

  (let [figure (immutable
                {:image-data "data::"
                 :caption    "I'm not real."}
                author)
        nl     (immutable
                (assoc (:content labnote)
                       :history/previous-version (:hash labnote)
                       :figure (:hash figure)
                       :tags #{(key (first tags/tag-tree))})
                author)]
    (async/<!!
     (routes/update-extract!
      {:editor      author
       :figure      figure
       :new-extract nl
       :previous-id (:hash labnote)}))

    (c/wait-for-queues)

    (t/is (= (get @s3-mock (:hash figure)) figure)
          "Figure was not interned during update.")

    (t/is (= (assoc (:content labrel)
                    :entity (:hash nl))
             (first (:relations (mi/extract-metadata (:hash nl)))))
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

      (c/wait-for-queues)

      ;; FIXME: This is one big reason why relations should change to reflect
      ;; new versions of extracts.
      (t/is (= (:content labrel)
               (first (:relations (indexing/extract-metadata (:hash ex1))))))

      (t/is (= #{r3}
               (:relations (indexing/extract-metadata (:hash ex2)))
               (:relations (indexing/extract-metadata (:hash nl))))))))

(t/deftest edit-extract-content)

(t/deftest edit-extract-relations)

(t/deftest comment-on-extract)

(t/deftest comment-reply)

(t/deftest comment-voting)

(defn test-ns-hook []
  (->> (juxt extract-lifecycle
             edit-extract-content
             edit-extract-relations
             comment-on-extract
             comment-reply
             comment-voting)
       ()
       c/stub-elastic
       (c/redirect-notifications notifications-ch)
       c/stub-s3))
