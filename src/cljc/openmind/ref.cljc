(ns openmind.ref
  (:refer-clojure :exclude [ref])
  (:require [hasch.core :as h]
            #?(:cljs [cljs.reader])))

(def hex-chars
  (into #{\a \b \c \d \e \f}
        (map (comp first str))
        (range 10)))

(deftype Ref [bytes]
  Object
  (toString [_]
    (str "#ref \"" (h/hash->str bytes) "\"")))

#?(:clj
   (defn- print-ref
     [^Ref ref ^java.io.Writer w]
     (.write w (.toString ref))))

#?(:clj
   (defmethod print-method Ref
     [^Ref r ^java.io.Writer w]
     (print-ref r w)))

#?(:clj
   (defmethod print-dup Ref
     [^Ref r ^java.io.Writer w]
     (print-ref r w)))

#?(:cljs
   (extend-protocol IPrintWithWriter
     Ref
     (-pr-writer [obj writer _]
       (write-all writer (str obj)))))

(defn ref [bytes]
  (Ref. bytes))

(defn ref? [x]
  (instance? Ref x))

(defn read-ref [hex]
  {:pre [(string? hex)
         (every? hex-chars hex)]}
  ;; FIXME: I don't like using stings for refs --- strings are only suitable for
  ;; encoding natural language --- but '07bf is not a valid symbol; it's a
  ;; malformed number...
  (ref (map #(#?(:clj  Integer/parseInt
                 :cljs js/parseInt)
               (apply str %) 16)
            (partition 2 hex))))

#?(:cljs
   ;;FIXME: Why does (cljs.reader/read-sting "#ref \"12\"") work, but entering
   ;;`#ref "12"` in the repl give a "tag not found" error?
   (cljs.reader/register-tag-parser! 'ref read-ref))
