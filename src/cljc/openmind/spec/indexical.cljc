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
   :tag-lookup-table ::tag-lookup
   :master ::master-index
   :comment-tree ::comment-tree))

(s/def ::master-index
  (s/keys :req-un [::active-extracts
                   ::extract-comments
                   ::extract-relations]))

(s/def ::active-extracts
  (s/coll-of ::u/hash :distinct true))

(s/def ::extract-comments
  (s/map-of ::u/hash ::u/hash))

(s/def ::extract-relations
  (s/map-of ::u/hash ::u/hash))

(s/def ::comment-tree
  (s/coll-of ::comment :distinct true))

(s/def ::comment
  (s/keys :req-un [::u/text ::u/author ::rank ::replies ::u/hash]))

(s/def ::replies ::comment-tree)

(s/def ::rank int?)
