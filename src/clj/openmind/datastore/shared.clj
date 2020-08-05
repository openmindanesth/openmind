(ns openmind.datastore.shared)

(defn get-all [q]
  (dosync
   (let [xs (seq (ensure q))]
     (ref-set q (clojure.lang.PersistentQueue/EMPTY))
     xs)))
