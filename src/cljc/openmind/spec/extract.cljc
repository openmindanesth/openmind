(ns openmind.spec.extract
  #?@
   (:clj
    [(:require
      [clojure.spec.alpha :as s]
      [openmind.spec.shared :as u]
      [openmind.tags :as tags])]
    :cljs
    [(:require [cljs.spec.alpha :as s] [openmind.spec.shared :as u])]))

(s/def ::extract
  (s/keys :req-un [::u/text
                   ::u/author
                   :extract/tags
                   ::source]
          :req [:extract/type]
          :opt [:history/previous-version]
          :opt-un [::figure ::resources]))

(s/def ::figure
  ::u/hash)

(s/def ::resources
  (s/coll-of ::resource :kind vector?))

(s/def ::resource
  (s/keys :req-un [::label ::link]))

(s/def ::label
  (s/and
   string?
   not-empty))

(s/def ::link
  ::u/url)

(s/def :extract/type
  #{:article :labnote})

(s/def :extract/tags
  (s/coll-of ::tag :kind set?))

(s/def ::tag
  #?(:clj (into #{} (keys tags/tag-tree))
     :cljs ::u/hash))

(s/def ::source
  (s/or
   :article ::article-details
   :pubmed  ::pubmed-reference
   :labnote ::labnote-source))

(s/def ::article-details
  (s/keys :req [:publication/date]
          :req-un [::u/url ::authors ::peer-reviewed? ::doi ::title]
          :opt-un [::abstract ::journal ::volume ::issue]))

(s/def ::issue
  (s/or :nil nil?
        :string string?))

(s/def ::volume
  (s/or :nil nil?
        :string string?))

(s/def ::peer-reviewed?
  boolean?)

(s/def ::pubmed-reference
  (s/keys :req [:publication/date]
          :opt-un [::volume ::issue]
          :req-un [::authors ::doi ::title ::abstract ::journal ::u/url]))

(s/def :publication/date
  ::u/inst)

(s/def ::authors
  (s/coll-of ::author-details :kind vector? :min-count 1))


(s/def ::author-details
  (s/and
   (s/keys :req-un []
                 :opt-un [::u/orcid-id  ::short-name ::full-name])
   not-empty))

(s/def ::string
  (s/and
   string?
   not-empty))

(s/def ::full-name
  ::string)

(s/def ::short-name
  ::string)

(s/def ::doi
  ::string)

(s/def ::title
  ::string)

(s/def ::abstract
  ::string)

(s/def ::journal
  ::string)

(s/def ::labnote-source
  (s/keys :req-un [:labnote/lab :labnote/investigators :labnote/institution]
          :req [:observation/date]))

(s/def :observation/date
  ::u/inst)

(s/def :labnote/lab
  ::string)

(s/def :labnote/investigators
  (s/coll-of :labnote/investigator :kind vector? :min-count 1))

(s/def :labnote/investigator
  (s/keys :req-un [::name]))

(s/def ::name
  string?)

(s/def :labnote/institution
  ::string)

(s/def :lab/name
  ::string)
