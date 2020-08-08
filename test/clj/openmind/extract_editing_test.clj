(ns openmind.extract-editing-test
  (:require [clojure.core.async :as async]
            [clojure.test :as t]
            [openmind.datastore :as ds]
            [openmind.datastore.indicies.metadata :as mi]
            [openmind.hash :as h]
            [openmind.server :as server]
            [openmind.tags :as tags]
            [openmind.test.common :as c]))

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
  {:text         "I am and extract"
   :extract/type :article
   :tags         #{}
   :source       pma})

(def c1
  {:text "Look at this!"
   :extract (h/hash ex1)})

(def c2
  {:text "first! oops"
   :extract (h/hash ex1)})

(def ex2
  {:text         "I am another"
   :extract/type :article
   :tags         #{}
   :source       pma})

(def labnote
  {:text         "Nota bene"
   :tags         #{}
   :extract/type :labnote
   :source       {:lab              "1"
                  :investigator     "yours truly"
                  :institution      "wub"
                  :observation/date (java.util.Date.)}})

(def trel
  {:entity    (h/hash ex1)
   :attribute :related
   :value     (h/hash ex2)})

(def labrel
  {:entity    (h/hash labnote)
   :value     (h/hash ex1)
   :attribute :confirmed})

(defn cmap [& xs]
  (into {} (map (fn [x] [(h/hash x) x])) xs))

;;;;; Stubs

(def notifications (atom {:created #{}
                          :updated {}}))

(defn start-notifier! [ch]
  (async/go-loop []
    (when-let [[t v] (async/<! ch)]
      (case t
        :openmind/extract-created (swap! notifications
                                         update :created
                                         conj v)
        :openmind/updated-metadata (swap! notifications
                                          update :updated
                                          #(if (contains? % v)
                                             (update % v inc)
                                             (assoc % v 1)))
        nil))))

;;;;; Helpers

(defn relations
  "Returns all relations in metadata associated with x"
  [x]
  (:relations (mi/extract-metadata (if (h/value-ref? x)
                                     x
                                     (h/hash x)))))

(defn comments
  "Returns all comments associate with x via metadata"
  [x]
  (:comments (mi/extract-metadata (h/hash x))))

;;;;; The Tests

(t/deftest extract-creation
  (let [tx1 {:context      (cmap ex1 c1 c2 ex2 trel)
             :author       author
             :time/created (java.util.Date.)
             :assertions   [[:assert (h/hash ex1)]
                            [:assert (h/hash c1)]
                            [:assert (h/hash c2)]
                            [:assert (h/hash ex2)]
                            [:assert (h/hash trel)]]}
        tx2 {:context      (cmap labnote labrel)
             :author       author
             :time/created (java.util.Date.)
             :assertions   [[:assert (h/hash labnote)]
                            [:assert (h/hash labrel)]]}]
    (t/is (= :success (:status (ds/transact tx1))))
    (c/wait-for-queues)

    (t/is (= ex1 (:content (ds/lookup (h/hash ex1)))))
    (t/is (= (h/hash ex1) (:extract (mi/extract-metadata (h/hash ex1)))))

    (t/is (= #{(assoc trel :author author)}
             (relations ex1)
             (relations ex2)))

    (t/is (= (map :text [c1 c2])
             (map :text (comments ex1))))

    (t/is (= :success (:status (ds/transact tx2))))
    (c/wait-for-queues)

    ;; TODO: Test for creation notifications

    (t/is (= 2 (count (relations ex1))))))

(def figure
  {:image-data "data::"
   :caption    "I'm not real."})

(def labnote'
  (assoc labnote
         :figure (h/hash figure)
         :tags #{(key (first tags/tag-tree))}
         :history/previous-version (h/hash labnote)))

(t/deftest edit-extract-content
  (let [tx {:context      (cmap figure labnote')
            :assertions   [[:retract (h/hash labnote)]
                           [:assert (h/hash labnote')]]
            :author       author
            :time/created (java.util.Date.)}]
    (t/is (= :success (:status (ds/transact tx))))
    (c/wait-for-queues)

    (t/is (= figure (:content (ds/lookup (h/hash figure)))))

    (let [r' (assoc labrel :entity (h/hash labnote') :author author)
          oldr (assoc labrel :author author)]
      (t/is (contains? (relations labnote') r'))
      (t/is (contains? (relations ex1) r'))
      ;; relations on the retracted metadata aren't touched
      (t/is (contains? (relations labnote) oldr))
      (t/is (not (contains? (relations labnote') oldr)))
      (t/is (not (contains? (relations ex1) oldr))))))

(t/deftest retract-relations

  (t/is (= :success (:status
                     (ds/transact {:assertions   [[:retract (h/hash trel)]]
                                   :author       author
                                   :time/created (java.util.Date.)}))))
  (c/wait-for-queues)

  (t/is (= #{} (relations ex2)))
  (t/is (= 1 (count (relations ex1))))

  (let [rel     {:entity    (h/hash labnote')
                 :attribute :contrast
                 :value     (h/hash ex2)}
        labrel' (assoc labrel :entity (h/hash labnote'))
        tx      {:context      (cmap rel labrel')
                 :assertions   [[:retract (h/hash labrel')]
                                [:assert (h/hash rel)]]
                 :author       author
                 :time/created (java.util.Date.)}]
    (t/is (= :success (:status (ds/transact tx))))
    (c/wait-for-queues)

    (let [r' (assoc rel :author author)]
      (t/is (contains? (relations ex2) r'))
      (t/is (contains? (relations labnote') r')))

    ;; N.B.: labrel was added and labrel' was removed. This should remove it
    ;; from labnote' and ex1, but not from labrel
    ;;
    ;; TODO: Write another test which changes both :entity and :value of a
    ;; relation and check that it is removed properly from both.
    (let [r' (assoc labrel' :author author)]
      (t/is (not (contains? (relations labnote') r')))
      (t/is (not (contains? (relations ex1) r'))))))

(t/deftest comment-on-extract
  (let [c  {:text    "hi mom!"
            :extract (h/hash labnote')}
        tx {:context      (cmap c)
            :assertions   [[:assert (h/hash c)]]
            :author       author
            :time/created (java.util.Date.)}]

    (ds/transact tx)
    (c/wait-for-queues)

    (t/is (= c (select-keys (first (comments labnote')) [:text :extract])))))

;; TODO:
(t/deftest retract-comment
  (t/is (= 2 (count (comments ex1))))

  (t/is (= :success (:status (ds/transact {:assertions   [[:retract (h/hash c1)]]
                                           :author       author
                                           :time/created (java.util.Date.)}))))
  (c/wait-for-queues)

  (t/is (= 1 (count (comments ex1)))))

(t/deftest comment-reply
  (let [c     (first (comments labnote'))
        reply {:text     "I saw that"
               :extract  (:extract c)
               :reply-to (:hash c)}
        tx    {:context      (cmap reply)
               :assertions   [[:assert (h/hash reply)]]
               :author       author
               :time/created (java.util.Date.)}]
    (ds/transact tx)
    (c/wait-for-queues)

    (t/is (= 1 (count (comments labnote'))))

    (t/is (= (:text (first (:replies (first (comments labnote'))))) "I saw that"))))

;; TODO:
(t/deftest edit-comment)

(t/deftest comment-voting
  (let [c       (select-keys (first (comments labnote')) [:text :extract])
        like    {:extract (:extract c)
                 :comment (h/hash c)
                 :vote    1}
        dislike {:extract (:extract c)
                 :comment (h/hash c)
                 :vote    -1}
        vote    (fn [v]
                  (ds/transact
                   {:context      (cmap v)
                    :assertions   [[:assert (h/hash v)]]
                    :author       {:name (str (gensym)) :orcid-id ""}
                    :time/created (java.util.Date.)})
                  (c/wait-for-queues))]

    ;; Idempotency

    (ds/transact {:context      (cmap like)
                  :assertions   [[:assert (h/hash like)]
                                 [:assert (h/hash like)]]
                  :author       author
                  :time/created (java.util.Date.)})
    (c/wait-for-queues)
    (t/is (= 1 (:rank (first (comments labnote')))))

    (vote like)
    (t/is (= 2 (:rank (first (comments labnote')))))

    (vote dislike)
    (vote like)
    (vote dislike)

    (t/is (= 1 (:rank (first (comments labnote')))))

    ;; idempotency across transactions
    (ds/transact {:context      (cmap dislike)
                  :assertions   [[:assert (h/hash dislike)]]
                  :author       author
                  :time/created (java.util.Date.)})
    (c/wait-for-queues)

    (t/is (= 1 (:rank (first (comments labnote')))))))

(defn test-ns-hook []
  (let [notifications-ch (async/chan 256)]
    (start-notifier! notifications-ch)
    (->> (do (server/start-indicies!)
             ((juxt extract-creation
                    edit-extract-content
                    retract-relations
                    comment-on-extract
                    retract-comment
                    edit-comment
                    comment-reply
                    comment-voting)))
         c/syncronise-publications
         c/stub-elastic
         (c/redirect-notifications notifications-ch)
         c/stub-s3)))
