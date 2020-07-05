(ns openmind.spec.shared
  (:require [clojure.string :as string]
            [openmind.hash :as h]
            [openmind.url :as url]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def :time/created inst?)

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def :openmind.spec.shared/author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

(s/def ::hash
  h/value-ref?)

(s/def :history/previous-version
  ::hash)

(s/def ::text
  (s/and string? not-empty #(< (count %) 500)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; URLs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::url-record
  (s/keys :opt-un [:url/protocol :url/path :url/query :url/hash]
          :req-un [:url/domain]))

(s/def :url/protocol
  #(contains? #{"http" "https"} %))

(s/def :url/path
  (s/and string?
         (s/or :empty empty?
               :path #(string/starts-with? % "/"))))

(s/def :url/query
  string?)

(s/def :url/hash
  string?)

(s/def :url/domain
  (s/and string?
         not-empty
         ;; REVIEW: This excludes top level domains. I think that's fine, but...
         #(string/includes? % ".")))

(s/def ::url
  (s/and
   string?
   ;; REVIEW: This works, but breaks explain, conform, etc. Spec includes regex
   ;; operators, would the right way be to use those directly on the string?
   #(s/valid? ::url-record (url/parse %))))
