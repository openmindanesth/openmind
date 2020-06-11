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
   :extract-metadata ::extract-metadata
   :extract-metadata-table ::extract-metadata-table))

(s/def ::tx-log
  (s/coll-of ::u/hash :kind vector?))

(s/def ::extract-metadata-table
  (s/map-of ::u/hash ::u/hash))

(s/def ::extract-metadata
  (s/keys :req-un [::extract]
          :opt-un [::comments ::relations ::history]))

(s/def ::extract
  ::u/hash)

(s/def ::history
  (s/coll-of ::edit :kind vector?))

(s/def ::edit
  (s/keys :req [:history/previous-version
                :time/created]
          :req-un [::u/author]))

(s/def ::comments
  (s/coll-of ::comment :kind vector? :distinct true))

(s/def ::relations
  (s/coll-of ::relation :kind set?))

;; TODO: Somehow we have to sync this with the :openmind.spec.comment/comment
;; spec. This is a strict extension. Same goes for ::relation
(s/def ::comment
  (s/keys :req-un [::u/text ::u/author ::u/hash]
          :req    [:time/created]
          :opt-un [::replies ::rank ::votes]))

(s/def ::vote-summary
  (s/keys :req-un [:openmind.spec.comment.vote/vote
                   ::u/hash]))

(s/def ::votes
  (s/map-of ::u/author ::vote-summary))

(s/def ::replies
  ::comments)

(s/def ::rank
  int?)

(s/def ::relation
  (s/keys :req-un [::u/hash
                   ::u/author
                   ::rel/entity
                   ::rel/attribute
                   ::rel/value]
          :req [:time/created]))
