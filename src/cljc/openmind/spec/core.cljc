(ns openmind.spec.core
  #?@
   (:clj
    [(:require [clojure.spec.alpha :as s]
               [openmind.hash :as h])]
    :cljs
    [(:require [cljs.spec.alpha :as s]
               [openmind.hash :as h])]))

(s/def :time/created inst?)

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def :history/previous-version
  ::hash)

(s/def ::text
  (s/and string? not-empty #(< (count %) 500)))

;;;;; Comments
;;
;; There isn't enough going on for it's own namespace yet.

(s/def :openmind.spec.comment/comment
  (s/keys :req-un [:openmind.spec.core/text
                   :openmind.spec.core/author
                   :openmind.spec.comment/refers-to]))

(s/def :openmind.spec.comment/refers-to
  ::hash)
