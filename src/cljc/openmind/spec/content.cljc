(ns openmind.spec.content
  #?@
   (:clj
    [(:require
      [clojure.spec.alpha :as s]
      [openmind.spec.comment :as comment]
      [openmind.spec.extract :as extract]
      [openmind.spec.figure :as figure]
      [openmind.spec.relation :as relation]
      [openmind.spec.tag :as tag])]
    :cljs
    [(:require
      [cljs.spec.alpha :as s]
      [openmind.spec.comment :as comment]
      [openmind.spec.extract :as extract]
      [openmind.spec.figure :as figure]
      [openmind.spec.relation :as relation]
      [openmind.spec.tag :as tag])]))

(s/def ::content
  (s/or
   :comment-vote ::comment/vote
   :comment   ::comment/comment
   :relation  ::relation/relation
   :extract   ::extract/extract
   :tag       ::tag/tag
   :figure    ::figure/figure))
