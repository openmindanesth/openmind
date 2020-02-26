(ns openmind.spec.tag
  (:require [taoensso.timbre :as log]
            [openmind.hash :as h]
            [openmind.spec.shared :as u]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::tag
  (s/keys :req-un [::name
                   ::parents
                   ::domain]))

(s/def ::name
  string?)

(s/def ::domain
  string?)

(s/def ::parents
  (s/coll-of ::u/hash :distinct true :type vector))

(defn validate [tag]
  (if (s/valid? ::tag tag)
    tag
    (log/error "Invalid tag: " (s/explain ::tag tag))))
