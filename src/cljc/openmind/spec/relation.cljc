(ns openmind.spec.relation
  (:require [openmind.spec.shared :as u]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::relation
  (s/keys :req-un [::type
                   ::subject
                   ::object
                   ::u/author]))

(s/def ::type
  string?)

(s/def ::subject
  ::u/hash)

(s/def ::object
  ::u/hash)
