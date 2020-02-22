(ns openmind.spec.relation
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s])]
    :cljs
    [(:require [cljs.spec.alpha :as s])]))

(s/def ::relation
  (s/keys :req-un [::type
                   ::subject
                   ::object
                   :openmind.spec.core/author]
          :req [:time/created]))

(s/def ::type
  string?)

(s/def ::subject
  :openmind.spec.core/hash)

(s/def ::object
  :openmind.spec.core/hash)
