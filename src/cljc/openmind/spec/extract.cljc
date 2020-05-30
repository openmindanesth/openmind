(ns openmind.spec.extract
  (:require [openmind.spec.shared :as u]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

;; Here's an idea of how to use specs to generate form validation:
;; https://medium.com/@kirill.ishanov/building-forms-with-re-frame-and-clojure-spec-6cf1df8a114d
;; Give it a shot

;;;;; General

(s/def ::extract
  (s/keys :req-un [::u/text
                   ::u/author
                   :extract/tags
                   ::source]
          :req [:extract/type]
          :opt [:history/previous-version]
          :opt-un [::figures ::source-material]))

(s/def ::figures
  (s/coll-of ::u/hash :distinct true))

(s/def ::source-material
  string?)

(s/def :extract/type
  (s/and some? keyword?))

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def :extract/tags
  (s/coll-of ::u/hash :distinct true))

(s/def ::source
  (s/or
   :pubmed  ::pubmed-reference
   :labnote ::labnote-source
   :link    ::url))

(s/def ::url
  ;; TODO: Validate url
  string?)

(s/def ::pubmed-reference
  (s/keys :req [:publication/date]
          :opt-un [::volume ::issue]
          :req-un [::authors ::doi ::title ::abstract ::journal ::url]))

(s/def :publication/date
  ;; As per scraping in general, these dates aren't generally well formatted, so
  ;; strings are the best I can do at present.
  string?)

(s/def ::authors
  (s/coll-of string?))

(s/def ::doi
  string?)

(s/def ::title
  string?)

(s/def ::abstract
  string?)

(s/def ::journal
  string?)

(s/def ::labnote-source
  (s/keys :req-un [:lab/name]))

(s/def :lab/name
  string?)
