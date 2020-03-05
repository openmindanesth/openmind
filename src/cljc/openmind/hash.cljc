(ns openmind.hash
  (:refer-clojure :exclude [hash])
  (:require [hasch.core :as h]
            [hasch.base64 :as b64]
            [hasch.benc :as benc]
            #?(:cljs [cljs.reader])))

(def hex-chars
  (into #{} "abcdef0123456789"))

(def ^:private byte-length
  "Length of hashes in bytes"
  16)

(def tag
  "128 bit prefix of sha512."
  'openmind.hash/ref)

(deftype ValueRef [hash-string]
  benc/PHashCoercion
  (-coerce [this md-create-fn write-handlers]
    (benc/-coerce hash-string md-create-fn write-handlers))
  Object
  (equals [this o]
    (boolean
     (if (identical? this o)
       true
       (when (instance? ValueRef o)
         (= hash-string (.hash-string ^ValueRef o))))))
  (hashCode [_]
    (.hashCode hash-string))
  (toString [_]
    (str "#" (str tag) " \"" hash-string "\"")))

#?(:clj
   (defn- print-ref
     [^ValueRef ref ^java.io.Writer w]
     (.write w (.toString ref))))

#?(:clj
   (defmethod print-method ValueRef
     [^ValueRef r ^java.io.Writer w]
     (print-ref r w)))

#?(:clj
   (defmethod print-dup ValueRef
     [^ValueRef r ^java.io.Writer w]
     (print-ref r w)))

#?(:cljs
   (extend-protocol IPrintWithWriter
     ValueRef
     (-pr-writer [obj writer _]
       (write-all writer (str obj)))))

(defn value-ref? [x]
  (instance? ValueRef x))

(defn read-hash [s]
  {:pre [(string? s)
         (every? hex-chars s)]}
  (ValueRef. s))

(defn hash
  [edn]
  (read-hash (h/hash->str (take byte-length (h/edn-hash edn)))))

#?(:cljs
   ;;FIXME: Why does (cljs.reader/read-sting "#ref \"12\"") work, but entering
   ;;`#ref "12"` in the repl give a "tag not found" error?
   (cljs.reader/register-tag-parser! tag read-hash))
