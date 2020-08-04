(ns openmind.spec
  #?@
   (:clj
    [(:require
      [clojure.spec.alpha :as s]
      [openmind.spec.content :as content]
      [openmind.spec.extract :as extract]
      [openmind.spec.indexical :as indexical]
      [openmind.spec.shared :as u])]
    :cljs
    [(:require
      [cljs.spec.alpha :as s]
      [openmind.spec.content :as content]
      [openmind.spec.extract :as extract]
      [openmind.spec.indexical :as indexical]
      [openmind.spec.shared :as u])]))

(s/def ::content ::content/content)

(s/def :immutable/content
  (s/or
   :content ::content
   :indexical ::indexical/indexical))

(s/def ::hash ::u/hash)

(s/def ::extract ::extract/extract)

(s/def ::immutable
  (s/keys :req-un [::u/hash :immutable/content ::indexical/author]
          :req [:time/created]))
