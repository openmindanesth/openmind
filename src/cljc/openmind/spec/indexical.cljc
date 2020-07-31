(ns openmind.spec.indexical
  (:require [taoensso.timbre :as log]
            [openmind.hash :as h]
            [openmind.spec.relation :as rel]
            [openmind.spec.shared :as u]
            [openmind.spec.tag :as tag]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

(s/def ::tag-lookup
  (s/map-of ::u/hash ::tag/tag))

(s/def ::indexical
  (s/or
   :tag-lookup-table ::tag-lookup
   :es-index ::searchable-index
   :tx-log-item ::tx-log-item
   :extract-metadata ::extract-metadata
   :extract-metadata-table ::extract-metadata-table))

(s/def ::searchable-index
  (s/coll-of ::u/hash :kind set?))

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
          :req-un [::author]))

(s/def ::comments
  (s/coll-of ::comment  :distinct true))

(s/def ::relations
  (s/coll-of ::relation :kind set?))

(s/def ::relation
  (s/keys :req-un [::rel/entity
                   ::rel/attribute
                   ::rel/value
                   ::author]))

(s/def ::comment
  (s/keys :req-un [::u/text ::author ::u/hash]
          :req    [:time/created]
          :opt-un [::replies ::rank ::votes]))

(s/def ::vote-summary
  (s/keys :req-un [:openmind.spec.comment.vote/vote
                   ::u/hash]))

(s/def ::votes
  (s/map-of ::author ::vote-summary))

(s/def ::replies
  ::comments)

(s/def ::rank
  int?)

(s/def ::tx-log-item
  (s/cat :type  ::assertion-type
         :item  ::u/hash
         :agent ::author
         :time  :time/created))

(s/def ::assertion-type
  #{:assert :retract})
