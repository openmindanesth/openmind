(ns openmind.spec
  (:require [openmind.hash :as h]
            [openmind.spec.comment :as comment]
            [openmind.spec.extract :as extract]
            [openmind.spec.indexical :as indexical]
            [openmind.spec.relation :as relation]
            [openmind.spec.shared :as u]
            [openmind.spec.tag :as tag]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::immutable
  (s/keys :req-un [::u/hash ::content]
          :req [:time/created]
          :opt [:history/previous-version]))

(s/def ::content
  (s/or
   :comment   ::comment/comment
   :relation  ::relation/relation
   :extract   ::extract/extract
   :tag       ::tag/tag
   :indexical ::indexical/indexical))

(s/def ::hash ::u/hash)

(s/def ::extract ::extract/extract)
