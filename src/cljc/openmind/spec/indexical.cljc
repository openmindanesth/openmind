(ns openmind.spec.indexical
  (:require [taoensso.timbre :as log]
            [openmind.hash :as h]
            [openmind.spec.relation :as rel]
            [openmind.spec.shared :as u]
            [openmind.spec.tag :as tag]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::tag-lookup
  (s/map-of ::u/hash ::tag/tag))

(s/def ::indexical
  (s/or
   :tag-lookup-table ::tag-lookup
   :tx-log ::tx-log
   :extact-metadata ::extract-metadata
   :extract-metadata-table ::extract-metadata-table))

(s/def ::tx-log
  (s/coll-of ::u/hash :kind vector?))

(s/def ::extract-metadata-table
  (s/map-of ::u/hash ::u/hash))

(s/def ::extract-metadata
  (s/keys :req-un [::extract]
          :opt-un [::comments ::related]))

(s/def ::extract
  ::u/hash)

(s/def ::extract-comments
  (s/coll-of ::comment :distinct true))

(s/def ::extract-relations
  (s/coll-of ::relation :distinct true))

;; TODO: Somehow we have to sink this with the :openmind.spec.comment/comment
;; spec. This is a strict extension. Same goes for ::relation
(s/def ::comment
  (s/keys :req-un [::u/text ::u/author ::u/hash]
          :req    [:time/created]
          :opt-un [::replies ::rank]))

(s/def ::replies
  ::extract-comments)

(s/def ::rank
  int?)

(s/def ::relation
  (s/keys :req-un [::rel/type
                   ::rel/subject
                   ::rel/object
                   ::u/author
                   ::u/hash]
          :req    [:time/created]))
