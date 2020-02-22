(ns openmind.spec.core
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s]
               [openmind.hash :as h])]
    :cljs
    [(:require [cljs.spec.alpha :as s]
               [openmind.hash :as h])]))

(s/def ::hash
  h/value-ref?)

(s/def ::immutable
  (s/keys :req-un [::hash ::content]))

(s/def ::content
  (s/or
   :comment  :openmind.spec.comment/comment
   :relation :openmind.spec.relation/relation
   :extract  :openmind.spec.extract/extract
   :tag      :openmind.spec.tag/tag))

(s/def :time/created inst?)

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

(s/def :history/previous-version
  ::hash)

(s/def ::text
  (s/and string? not-empty #(< (count %) 500)))

;;;;; Comments
;;
;; There isn't enough going on for it's own namespace yet.

(s/def :openmind.spec.comment/comment
  (s/keys :req-un [:openmind.spec.core/text
                   :openmind.spec.core/author
                   :openmind.spec.comment/refers-to]
          :req [:time/created]
          :opt [:history/previous-version]))

(s/def :openmind.spec.comment/refers-to
  ::hash)
