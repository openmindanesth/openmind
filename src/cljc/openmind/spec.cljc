(ns openmind.spec
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s])]
    :cljs
    [(:require [cljs.spec.alpha :as s])]))


(s/def ::immutable :openmind.spec.core/immutable)
