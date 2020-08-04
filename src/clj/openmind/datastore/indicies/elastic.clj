(ns openmind.datastore.indicies.elastic
  (:require [openmind.datastore :as ds]
            [taoensso.timbre :as log]))

(def active-es-index
  (ds/create-index
   "openmind.indexing/elastic-active"))

(defn add-to-index [id]
  (ds/swap-index! active-es-index (fn [i]
                                    (if (empty? i)
                                      #{id}
                                      (conj i id)))))

(defn replace-in-index [old new]
  (ds/swap-index! active-es-index (fn [i]
                                    (-> i
                                        (disj old)
                                        (conj new)))))

(defn remove-from-index [id]
  (log/info "Removing from elastic:" id)
  (ds/swap-index! active-es-index (fn [i] (disj i id))))
