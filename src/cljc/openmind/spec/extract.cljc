(ns openmind.spec.extract
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s])]
    :cljs
    [(:require [cljs.spec.alpha :as s])]))

;; Here's an idea of how to use specs to generate form validation:
;; https://medium.com/@kirill.ishanov/building-forms-with-re-frame-and-clojure-spec-6cf1df8a114d
;; Give it a shot

;;;;; General

(s/def ::extract
  (s/keys :req-un [:openmind.spec.core/text
                   :openmind.spec.core/author
                   :extract/tags
                   :extract/type
                   ::source
                   ::figure]
          :req [:time/created]
          :opt [:history/previous-version]))

;;;;; Required

(s/def :extract/type
  (s/and some? keyword?))

(s/def ::source
  (s/or
   :url        ::url
   :pubmed-ref :pubmed/details))

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def :extract/tags
  (s/coll-of ::hash :distinct true))

;;;; Optional

;; FIXME: collection of figures
(s/def ::figure
  (s/or :link ::url
        :upload ::file-reference))

(s/def ::file-reference
  ;; Base64 encoded image at present. Will upgrade to item from S3 Bucket
  ;; eventually.
  string?)

;; TODO: URL spec
(s/def ::url string?)

(s/def ::reference ::url)

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
