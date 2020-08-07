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
(s/def ::indexical ::indexical/indexical)

(s/def ::tx ::indexical/tx)
(s/def ::metadata ::indexical/extract-metadata)

(s/def ::hash ::u/hash)

(s/def ::extract ::extract/extract)

(s/def ::meta-indexical
  (s/keys :req-un [::u/hash :indexical/content]
          :req [:time/created]
          :opt [:history/previous-version]))

(s/def :indexical/content ::indexical)

(s/def ::meta-content
  (s/keys :req-un [::u/hash ::content ::indexical/author]
          :req [:time/created]))

(s/def ::internable
  (s/or
   :indexical ::meta-indexical
   :content ::meta-content))
