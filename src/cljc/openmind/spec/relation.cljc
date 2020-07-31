(ns openmind.spec.relation
  (:require [openmind.spec.shared :as u]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::relation
  (s/keys :req-un [::entity
                   ::attribute
                   ::value]))

(s/def ::attribute
  #{:related :confirmed :contrast})

(s/def ::entity
  (s/or :new #(= :openmind.components.extract.editor/new %)
        :ref ::u/hash))

(s/def ::value
  ::u/hash)
