(ns openmind.edn
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [openmind.hash :as hash]))

(def readers
  {hash/tag hash/read-hash})

(defn read-string [s]
  (edn/read-string {:eof nil
                    :readers readers
                    :default tagged-literal}
                   s))
