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
   :active-extracts ::active-extracts-node
   :extract-comments ::extract-comments-node
   :extact-relations ::extract-relations-node
   :comment-tree ::comment-tree
   :rav ::rav-node))

(s/def ::master-index
  (s/keys :req-un [::active-extracts
                   ::extract-comments
                   ::extract-relations
                   ::rav]))

(s/def ::rav
  ::u/hash)

(s/def ::rav-node
  (s/map-of ::u/hash ::attribute-values))

(s/def ::attribute-values
  (s/map-of keyword? (s/coll-of ::u/hash :distinct true)))

(s/def ::active-extracts
  ::u/hash)

(s/def ::extract-comments
  ::u/hash)

(s/def ::extract-relations
  ::u/hash)

(s/def ::active-extracts-node
  (s/coll-of ::u/hash :distinct true))

(s/def ::extract-comments-node
  (s/map-of ::u/hash ::u/hash))

(s/def ::extract-relations-node
  (s/map-of ::u/hash ::u/hash))

(s/def ::comment-tree
  (s/coll-of ::comment :distinct true))

(s/def ::comment
  (s/keys :req-un [::u/text ::u/author ::rank ::replies ::u/hash]))

(s/def ::replies
  ::comment-tree)

(s/def ::rank
  int?)
