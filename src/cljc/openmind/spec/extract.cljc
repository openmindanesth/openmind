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
          :opt-un [::figure ::source-material]))

(s/def ::figure
  ::u/hash)

(s/def ::source-material
  ::u/url)

(s/def :extract/type
  (s/and some? keyword?))

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def :extract/tags
  (s/coll-of ::u/hash :kind set?))

(s/def ::source
  (s/or
   :pubmed  ::pubmed-reference
   :labnote ::labnote-source
   :link    ::just-url))

(s/def ::just-url
  (s/keys :req-un [::u/url]))

(s/def ::pubmed-reference
  ;; TODO: Store the pubmed id separately from the URL.
  (s/keys :req [:publication/date]
          :opt-un [::volume ::issue]
          :req-un [::authors ::doi ::title ::abstract ::journal ::u/url]))

(s/def :publication/date
  inst?)

(s/def ::author-details
  (s/keys :req-un [::full-name]
          :opt-un [::u/orcid-id  ::short-name]))

(s/def ::full-name
  string?)

(s/def ::short-name
  string?)

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
