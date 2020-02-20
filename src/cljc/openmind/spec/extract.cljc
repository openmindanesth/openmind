(ns openmind.spec.extract
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s]
               [openmind.ref :as ref])]
    :cljs
    [(:require [cljs.spec.alpha :as s]
               [openmind.ref :as ref])]))

;; Here's an idea of how to use specs to generate form validation:
;; https://medium.com/@kirill.ishanov/building-forms-with-re-frame-and-clojure-spec-6cf1df8a114d
;; Give it a shot

(s/def ::immutable
  (s/keys :req-un [::hash ::content]))

(s/def ::hash ref/ref? ) ; FIXME: Type for hashes as in xyzzy

(s/def ::content
  (s/or
   :comment  ::comment
   :relation ::relation
   :extract  ::extract))

;;;;; General

(s/def ::extract
  (s/keys :req-un [::text
                   ::source
                   ::tags
                   ::created-time
                   ::author
                   :extract/type
                   ::figure
                   ::previous-version]))

(s/def ::comment
  (s/keys :req-un [::text
                   ::author
                   ::created-time
                   ::refers-to]))

(s/def ::relation
  (s/keys :req-un [:relation/type
                   :relation/subject
                   :relation/object
                   ::author
                   ::created-time]))

;;;;; Required

(s/def :extract/type
  (s/and some? keyword?))

(s/def ::text
  (s/and string? not-empty #(< (count %) 500)))

(s/def ::source
  (s/or
   :url ::url
   :pubmed-ref :pubmed/details))

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def ::created-time inst?)

(s/def ::tags
  (s/coll-of ::tag :distinct true))

(s/def ::tag
  ;; Tags are elastic hashes pointing to tag documents.
  ;; TODO: Spec for those tags.
  string?)

;;;; Optional

;; FIXME: collection of figures
(s/def ::figure
  (s/or :link ::url
        :upload ::file-reference))

(s/def ::file-reference
  ;; Base64 encoded image at present. Will upgrade to item from S3 Bucket
  ;; eventually.
  string?)

;; TODO: What are details?
(s/def ::details string?)

(s/def ::history
  (s/coll-of ::extract :kind vector?))

(s/def ::int int?)

(s/def ::reference-list
  (s/coll-of ::reference :kind vector?))

(s/def ::related ::reference-list)

(s/def ::confirmed ::reference-list)

(s/def ::contrast ::reference-list)

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

