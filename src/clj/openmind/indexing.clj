(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [openmind.spec :as spec]))

(defmulti update-indicies (fn [t d] t))

(defn index [imm]
 let [t (first (:content (s/conform ::spec/immutable imm)))]
     (update-indicies t imm))

(defmethod update-indicies :comment
  [_ {:keys [hash content]}]
  (let [{:keys [refers-to]} content]
    (println refers-to)))
