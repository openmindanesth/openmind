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

;; REVIEW: should the same image with different captions share the image-data?
;; That is, should figures be nested one level deeper so that we can share the
;; same image-data among different figures?

(s/def ::caption
  string?)

(s/def ::image-data
  string?)
