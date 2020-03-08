(ns openmind.json
  (:require  [clojure.data.json :as json]
             [clojure.walk :as walk]
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

(defn read-str [s]
  (binding [*read-eval* nil]
    (walk/postwalk (fn [o]
                     (if (and (string? o) (.startsWith ^String o "#"))
                       (read-string o)
                       o))
                   (json/read-str s :key-fn keyword))))
