(ns openmind.json
  (:require  [clojure.data.json :as json]
             [openmind.edn :as edn]
             [clojure.walk :as walk]
             openmind.hash)
  (:import [java.text SimpleDateFormat]
           [openmind.hash ValueRef]))

(def ^SimpleDateFormat dateformat
  (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))

(extend-protocol json/JSONWriter
  ValueRef
  (-write [this out]
    (@#'json/write-string (str this) out))
  java.util.Date
  (-write [this out]
    (@#'json/write-string (.format dateformat this) out)))

(defn key-writer [k]
  (if (keyword? k)
    (str (symbol k))
    (str k)))

(defn write-str [obj]
  (json/write-str obj :key-fn key-writer))

(defn val-reader [k v]
  (case k
    :extract/type     (keyword v)
    :publication/date (.parse dateformat v)
    :time/created     (.parse dateformat v)
    v))

(defn read-str [s]
  (walk/postwalk (fn [o]
                   (if (and (string? o) (.startsWith ^String o "#"))
                     (edn/read-string o)
                     o))
                 (json/read-str s
                                :key-fn keyword
                                :value-fn val-reader)))
