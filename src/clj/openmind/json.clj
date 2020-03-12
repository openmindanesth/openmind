(ns openmind.json
  (:require  [clojure.data.json :as json]
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
    :extract/type (keyword v)
    ;; REVIEW: We're still treating :publication/date as a string.
    :time/created (.parse dateformat v)
    v))

(defn read-str [s]
  (binding [*read-eval* nil]
    (walk/postwalk (fn [o]
                     (if (and (string? o) (.startsWith ^String o "#"))
                       (read-string o)
                       o))
                   (json/read-str s
                                  :key-fn keyword
                                  :value-fn val-reader))))
