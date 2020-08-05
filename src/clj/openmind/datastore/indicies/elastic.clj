(ns openmind.datastore.indicies.elastic
  (:require [openmind.datastore.indexing :as indexing]
            [taoensso.timbre :as log]))

(def active-es-index
  (indexing/create-index
   "openmind.indexing/elastic-active"))

(defn add-to-index [id]
  (indexing/swap-index! active-es-index (fn [i]
                                    (if (empty? i)
                                      #{id}
                                      (conj i id)))))

(defn replace-in-index [old new]
  (indexing/swap-index! active-es-index (fn [i]
                                    (-> i
                                        (disj old)
                                        (conj new)))))

(defn remove-from-index [id]
  (log/info "Removing from elastic:" id)
  (indexing/swap-index! active-es-index (fn [i] (disj i id))))
