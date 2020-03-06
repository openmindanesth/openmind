(ns openmind.spec.figure
  (:require [taoensso.timbre :as log]
            [openmind.hash :as h]
            [openmind.spec.shared :as u]
            [openmind.spec.tag :as tag]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::figure
  (s/keys
   :req-un [::image-data ::u/author]
   :opt-un [::caption]))

(s/def ::caption
  string?)

(s/def ::image-data
  ;; data:image/???;base64...
  ;; REVIEW: Is a spec an appropriate place to validate the data url format?
  string?)
