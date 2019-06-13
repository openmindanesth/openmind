(ns openmind.db
  (:require [openmind.search :as search]))

(def empty-filters
  (into {} (map (fn [[k v]] [k #{}])) search/filters))

(def default-db
  {:search  {:term    nil
             :filters empty-filters}
   :route   :openmind.views/search
   ;; TODO: FIXME:
   :user    (gensym)
   :results []})
