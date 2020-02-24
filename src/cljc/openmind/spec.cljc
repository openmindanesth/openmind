(ns openmind.spec
  (:require [openmind.hash :as h]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::hash
  h/value-ref?)

(s/def ::immutable
  (s/keys :req-un [::hash ::content]
          :req [:time/created]
          :opt [:history/previous-version]))

(s/def ::content
  (s/or
   :comment  :openmind.spec.comment/comment
   :relation :openmind.spec.relation/relation
   :extract  :openmind.spec.extract/extract
   :tag      :openmind.spec.tag/tag))
