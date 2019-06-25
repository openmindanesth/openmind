(ns openmind.db
  (:require [openmind.search :as search]))

(def default-db
  {:domain   "anaesthesia"
   :tag-tree nil
   :search   {:term    nil
              :filters {}}
   :route    :openmind.views/search
   ;; TODO: FIXME:
   :filter-selection nil
   :user     (gensym)
   :results  []})
