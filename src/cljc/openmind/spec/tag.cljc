(ns openmind.spec.tag
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s]
               [openmind.hash :as h])]
    :cljs
    [(:require [cljs.spec.alpha :as s]
               [openmind.hash :as h])]))

(s/def ::tag
  (s/keys :req-un [::name
                   ::parents
                   ::domain]
          :req [:time/created]
          :opt-un [::previous-version]))

(s/def ::name
  string?)

(s/def ::domain
  string?)

(s/def ::parents
  (s/coll-of :openmind.spec.core/hash :distinct true :type vector))

(defn create [{:keys [domain name parents]}]
  (let [data {:domain  domain
              :name    name
              :parents parents
              :time/created (java.util.Date.)}]
    {:hash    (h/hash data)
     :content data}))
