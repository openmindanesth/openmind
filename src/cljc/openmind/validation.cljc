(ns openmind.validation
  #?(:cljs (:require [cljs.spec.alpha :as s]
                     [cljs.spec.gen.alpha :as gen]
                     [clojure.test.check.generators])
     :clj  (:require [clojure.spec.alpha :as s]
                     [clojure.spec.gen.alpha :as gen]
                     [clojure.test.check.generators])))


;; Here's an idea of how to use specs to generate form validation:
;; https://medium.com/@kirill.ishanov/building-forms-with-re-frame-and-clojure-spec-6cf1df8a114d
;; Give it a shot

(s/def ::extract
  (s/keys :req-un [:extract/extract ::source ::tags ::created-time ::author]
          :opt-un [::comments ::figures ::history ::related ::details]))

(s/def :extract/extract
  (s/with-gen
    (s/and string? #(< 1 (count %) 500))
    (fn []
      (clojure.test.check.generators/fmap
       (fn [x] (apply str (interpose " " x)))
       (gen/vector clojure.test.check.generators/string-alphanumeric 2 10)))))

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id string?)

(s/def ::related
  (s/coll-of ::url :distinct true))

(s/def ::created-time inst?)

(s/def ::tags
  (s/coll-of string? :distinct true))

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
