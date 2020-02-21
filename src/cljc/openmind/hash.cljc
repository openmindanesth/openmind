(ns openmind.hash
  (:refer-clojure :exclude [hash])
  (:require [clojure.data.json]
            [hasch.core :as h]
            [hasch.base64 :as b64]
            [hasch.benc :as benc]
            [hasch.platform :as platform]
            #?(:cljs [cljs.reader])))

(def b64-chars
  (into #{} "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/="))

(defn b64-str [r]
  (b64/encode (#?(:clj byte-array :cljs clj->js) (.-bytes r))))

(def tag 'openmind.hash/sha512)

(deftype ValueRef [bytes]
  benc/PHashCoercion
  (-coerce [this md-create-fn write-handlers]
    (benc/-coerce (b64-str this) md-create-fn write-handlers))
  Object
  (equals [this o]
    (boolean
     (if (identical? this o)
       true
       (when (instance? ValueRef o)
         (= (.-bytes this) (.-bytes o))))))
  (hashCode [_]
    (.hashCode bytes))
  (toString [this]
    (str "#" (str tag) " \"" (b64-str this) "\"")))

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

(defn bytes->ref [bytes]
  (ValueRef. bytes))

(defn value-ref? [x]
  (instance? ValueRef x))

(defn read-hash [s]
  {:pre [(string? s)
         (= 0 (mod (count s) 4))
         (every? b64-chars s)]}
  (bytes->ref (map #(mod % 256) (b64/decode s))))

(defn hash
  [edn]
  (bytes->ref (h/edn-hash edn)))

#?(:cljs
   ;;FIXME: Why does (cljs.reader/read-sting "#ref \"12\"") work, but entering
   ;;`#ref "12"` in the repl give a "tag not found" error?
   (cljs.reader/register-tag-parser! tag read-hash))
