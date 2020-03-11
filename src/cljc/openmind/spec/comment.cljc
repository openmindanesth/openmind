(ns openmind.spec.comment
  (:require [openmind.spec.shared :as u]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::comment
  (s/keys :req-un [::u/text
                   ::u/author
                   ::refers-to]))

(s/def ::refers-to
  ::u/hash)

(s/def ::vote
  #(1 -1))

(s/def ::comment-vote
  (s/keys :req-un [::refers-to ::vote ::u/author]))
