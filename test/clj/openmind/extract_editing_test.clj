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

;; REVIEW: I'm prempting the notification system to make sure that critical
;; messages are sent, but I'm not reverting it. This might not play too well
;; with other tests.
;;
;; N.B.: You'll have to restart the server if you're running it in the same repl as this test.

(notify/init-notification-system!
 (fn [_ m] (swap! notifications conj m))
 (fn [] ["uid1"]))

;;;;; tests

(defn wait-for-intern
  "Polls intern-queue until all writes are finished. good enough for tests."
  []
  (let [q @#'ds/intern-queue]
    (loop []
      (when (or (seq @q)
                (contains? @ds/running q))
        (Thread/sleep 100)
        (recur)))))

(defn ws-req [t ex]
  {:uid    "uid1"
   :id     t
   :event  [t {:extract ex}]
   :tokens {:name     "Dev Johnson"
            :orcid-id "0000-NONE"}})

(t/deftest extract-lifecycle
  (with-redefs [s3/exists?          #(contains? @s3-mock %)
                s3/lookup           #(get @s3-mock % nil)
                s3/write!           (fn [k o] (swap! s3-mock assoc k o))
                es/add-to-index     (constantly nil)
                es/retract-extract! (constantly nil)
                es/replace-in-index (constantly nil)]

    (routes/dispatch (ws-req :openmind/index ex1))
    (routes/dispatch (ws-req :openmind/index ex2))
    (routes/dispatch (ws-req :openmind/index labnote))

    (wait-for-intern)

    (t/is (every? #(contains? @s3-mock (:hash %)) [ex1 ex2 labnote]))
    (t/is (every? #(= (indexing/extract-metadata (:hash %))
                     {:extract (:hash %)})
                  [ex1 ex2 labnote]))
    ))
