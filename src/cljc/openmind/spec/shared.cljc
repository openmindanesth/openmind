(ns openmind.spec.shared
  (:require [openmind.hash :as h]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def :time/created inst?)

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def ::hash
  h/value-ref?)

(s/def :history/previous-version
  ::hash)

(s/def ::text
  (s/and string? not-empty #(< (count %) 500)))
