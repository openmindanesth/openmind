(ns openmind.json
  (:require  [clojure.data.json :as json]
             openmind.hash)
  (:import [openmind.hash ValueRef]))

(extend-protocol json/JSONWriter
  ValueRef
  (-write [this out]
    (@#'json/write-string (str this) out))
  java.util.Date
  (-write [this out]
    (@#'json/write-string (with-out-str (print this)) out)))

(defn write-str [obj]
  (json/write-str obj))

(def json-tags
  #{:hash :time/created})

(set! *read-eval* nil)

(defn read-str [s]
  (json/read-str s
                 :key-fn keyword
                 :value-fn (fn [k v]
                             (if (contains? json-tags k)
                               (read-string v)
                               v))))
