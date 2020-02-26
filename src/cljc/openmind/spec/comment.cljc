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
