(ns openmind.validation
  (:require [cljs.spec.alpha :as s]
            [cljs.spec.gen.alpha :as gen]
            [clojure.test.check.generators]
            ))

(s/def ::extract
  (s/keys :req-un [::text ::reference ::tags ::created ::author]
          :opt-un []#_[::comment ::figure ::history ::related ::details]))

(s/def ::text
  (s/with-gen
    (s/and string? #(< 0 (count %) 250))
    (fn []
      (clojure.test.check.generators/fmap
       (fn [x] (apply str (interpose " " x)))
       (gen/vector clojure.test.check.generators/string-alphanumeric 2 10)))))

(s/def ::author string?)

(s/def ::reference string?)

(s/def ::created inst?)

(s/def ::tags
  (s/coll-of string? :distinct true))

;; (defn gen-vec [tag]
;;   (fn [] (gen/vector (gen/elements (keys (get search/filters tag))) 0 2)))

;; (s/def ::species
;;   (s/with-gen ::tag
;;     (gen-vec :species)))

;; (s/def ::modality (s/with-gen ::tag (gen-vec :modality)))

;; (s/def ::depth (s/with-gen ::tag (gen-vec :depth)))

;; (s/def ::application (s/with-gen ::tag (gen-vec :application)))

;; (s/def ::physiology (s/with-gen ::tag (gen-vec :physiology)))

(defn rando-tags [& [min max]]
  (let [min (or min 3)
        max (or max 10)
        n (+ 1 min (rand-int (- max min)))
        ;; HACK: I don't even know what to call this. It's just for
        ;; testing. Does that make it okay?
        opts (keys (:tag-lookup @re-frame.db/app-db))]
    (assert (< n (count opts)))
    (loop [tags #{}]
      (if (= (count tags) n)
        tags
        (recur (conj tags (rand-nth opts)))))))

(defn gen-examples [n]
  (let [templs (map first (s/exercise ::extract n))]
    (map (fn [x] (assoc x :tags [] #_(rando-tags))) templs )))

(comment
  (def example
    {:text      "Medetomidine has no dose-dependent effect on the BOLD response to subcutaneous electrostimulation (0.5, 0.7, 1 mA) in mice for doses of 0.1, 0.3, 0.7, 1.0, 2.0 mg/kg/h."
     :reference "Nasrallah et al., 2012"
     :created   (js/Date.)
     :author    "me"
     :type      :extract
     :tags      {:species  :human
                 :modality :cortex
                 :depth    :moderate}}))
