(ns openmind.spec
  (:require [openmind.hash :as h]
            [openmind.spec.comment :as comment]
            [openmind.spec.extract :as extract]
            [openmind.spec.figure :as figure]
            [openmind.spec.indexical :as indexical]
            [openmind.spec.relation :as relation]
            [openmind.spec.shared :as u]
            [openmind.spec.tag :as tag]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::content
  (s/or
   :comment-vote ::comment/vote
   :comment   ::comment/comment
   :relation  ::relation/relation
   :extract   ::extract/extract
   :tag       ::tag/tag
   :figure    ::figure/figure
   :indexical ::indexical/indexical))

(s/def ::hash ::u/hash)

(s/def ::extract ::extract/extract)

(s/def ::immutable
  (s/keys :req-un [::u/hash ::content]
          :req [:time/created]
          :opt [:history/previous-version]))
