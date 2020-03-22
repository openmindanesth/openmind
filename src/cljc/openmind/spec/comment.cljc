(ns openmind.spec.comment
  (:require [openmind.spec.shared :as u]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::comment
  (s/keys :req-un [::u/text
                   ::u/author
                   ::extract]
          :opt-un [::reply-to]))

(s/def ::vote
  (s/keys :req-un [::extract
                   :openmind.spec.comment.vote/comment
                   :openmind.spec.comment.vote/vote
                   ::u/author]))

(s/def :openmind.spec.comment.vote/comment
  ::u/hash)

(s/def ::extract
  ::u/hash)

(s/def ::reply-to
  ::u/hash)

(s/def :openmind.spec.comment.vote/vote
  #{1 -1})
