(ns openmind.spec.indexical
  (:require [taoensso.timbre :as log]
            [openmind.hash :as h]
            [openmind.spec.shared :as u]
            [openmind.spec.tag :as tag]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::tag-lookup
  (s/map-of ::u/hash ::tag/tag))

(s/def ::indexical
  (s/or
   :tag-lookup-table ::tag-lookup))
