(ns openmind.spec.extract
  #?(:cljs (:require [cljs.spec.alpha :as s]
                     [cljs.spec.gen.alpha :as gen]
                     [clojure.test.check.generators])
     :clj  (:require [clojure.spec.alpha :as s]
                     [clojure.spec.gen.alpha :as gen]
                     [clojure.test.check.generators])))


;; Here's an idea of how to use specs to generate form validation:
;; https://medium.com/@kirill.ishanov/building-forms-with-re-frame-and-clojure-spec-6cf1df8a114d
;; Give it a shot

;;;;; General

;; TODO: URL spec
(s/def ::url string?)

;; TODO: a reference can be a body of text, or a URL. Most of the time it should
;; be a URL.
(s/def ::reference ::url)

(s/def ::extract
  (s/keys :req-un [::text ::source ::tags ::created-time ::author]
          :opt-un [::comments ::figures ::history ::related ::details
                   ::confirmed ::contrast]))

;;;;; Required

(s/def ::text
  (s/and string? not-empty #(< (count %) 500)))

(s/def ::source
  (s/and ::reference
         not-empty))

(s/def ::author
  (s/keys :req-un [:author/name ::orcid-id]))

(s/def :author/name string?)

;; TODO: What makes a valid Orcid ID? Is this the right place to validate it?
(s/def ::orcid-id
  (s/and string? not-empty))

(s/def ::created-time inst?)

(s/def ::tags
  (s/coll-of string? :distinct true))

(s/def ::file-reference
  ;; REVIEW: S3 object reference. It's more than a string, but is this the place
  ;; to check that?
  string?)

;;;; Optional

(s/def ::comments
  ;; FIXME: This should be a list of comments. Eaxh comment should have data of
  ;; its own such as author, authoring time, etc..
  (s/map-of ::int ::comment))

(s/def ::comment
  string?)

(s/def ::image
  (s/or :link ::url
        :upload ::file-reference))

;; FIXME: collection of figures
(s/def ::figures
  (s/map-of ::int ::image))

;; TODO: What are details?
(s/def ::details string?)

(s/def ::history
  (s/coll-of ::extract))

(s/def ::int int?)

(s/def ::numbered-reference-list
  (s/and
   (s/every-kv ::int ::reference)
   #(let [ks (keys %)]
      (= (range (count ks)) (sort ks)))))

(s/def ::related ::numbered-reference-list)

(s/def ::confirmed ::numbered-reference-list)

(s/def ::contrast ::numbered-reference-list)

(comment
  (def example
    {:text      "Medetomidine has no dose-dependent effect on the BOLD response to subcutaneous electrostimulation (0.5, 0.7, 1 mA) in mice for doses of 0.1, 0.3, 0.7, 1.0, 2.0 mg/kg/h."
     :reference "Nasrallah et al., 2012"
     :created   (js/Date.)
     :author    "me"
     :type      :extract
     :tags      {:species  :human
                 :modality :cortex
                 :depth    :moderate}}))

(defn describe-problem [{:keys [pred via path]}]
  ;; HACK: This is an horrid way to get human messages from specs. There's got to be a better way...
  (cond
    (= pred 'cljs.core/not-empty)
    "field cannot be left blank"

    (and  (= pred '(cljs.core/fn [%] (cljs.core/< (cljs.core/count %) 500)))
          (= path [:text]))
    "extracts should be concise, less than 500 characters. use the comments if you need to make additional points."))

(defn required? [{:keys [pred path]}]
  (and (= path [])
       (= 'cljs.core/fn (first pred))
       (= 'cljs.core/contains? (first (nth pred 2)))))

(defn missing-required [{:keys [pred]}]
  [(last (nth pred 2)) "field cannot be left blank"])

(defn mk
  "Hack to interpret spec's use of indicies in map specs."
  [path in]
  (if (= path in)
    path
    (butlast in)))

(defn interpret-explanation [{:keys [cljs.spec.alpha/problems]}]
  (let [missing (->> problems
                     (filter required?)
                     (map missing-required)
                     (into {}))]
    (reduce (fn [acc {:keys [path in pred] :as problem}]
              (assoc-in acc (mk path in) (describe-problem problem)))
            missing (remove required? problems))))
