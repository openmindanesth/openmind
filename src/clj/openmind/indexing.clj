(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.spec.indexical :as ind]
            [openmind.util :as util]))

(def active-extracts
  (util/immutable
   [#openmind.hash/ref "85030087c5b052dfd47e7843fe7c045a"
    #openmind.hash/ref "f36943c4806322268b4083f3fcd33acd"
    #openmind.hash/ref "095f133af97fd2e84844934808f381de"
    #openmind.hash/ref "b535a5691ea7c5be9fdbba37a20824b3"]))

(def extract-comments
  (util/immutable
   (zipmap (:content active-extracts)
           (repeat #openmind.hash/ref "917935a68abd27b5174f4eff93989ada"))))

(def rav
  (util/immutable
   (zipmap (:content active-extracts)
           (repeat {:refers-to []}))))

(def master-index*
  (util/immutable
   {:active-extracts   (:hash active-extracts)
    :extract-comments  (:hash extract-comments)
    :extract-relations #openmind.hash/ref "be688838ca8686e5c90689bf2ab585ce"
    :rav               (:hash rav)}))


(defn one-time-init!
  "Creates a new index with a hard-coded set of things"
  ;; REVIEW: Do we gain anything from storing the master indicies as chunks in
  ;; the datastore?
  (run! s3/intern [active-extracts extract-comments rav master-index*])
  (s3/index-comapre-and-set! (s3/master-index) master-index*))

;; I need an index that tracks
;; comment -> refers-to (of :comment spec) -> all views involving comment

;; so a value->attribute->view index

;; The idea is that when I get a new comment, it :refers-to the thing it's
;; about. I need to be able to invert that link and insert this new comment into
;; a tree. Which tree is something I don't yet know.
;;
;; So the index gives me all views the comment applies to. I look at those and
;; pick the ones I want to insert the comment into. Then I walk the tree and
;; find the appropriate place and update the tree. Save the new tree as an
;; immutable, update the extract comments table, and update the master index.
;;
;; Updating the master index requires atomic compare and swap. The rest does
;; not, but if the CAS fails, then the whole transaction has to replay because
;; we don't know where in the tree the conflict was. On the up side, if the
;; conflict was high up in the tree, then we can use memoisation to avoid
;; redoing work unnecessarily.
;;
;; Of course this will result in orphan nodes in the datastore. I'm not
;; convinced that's a bad thing. GC is possible, but only if you can syncronise
;; all past readers.

(defmulti update-indicies (fn [t d] t))

(defn index [imm]
  (let [t (first (:content (s/conform ::spec/immutable imm)))]
    (update-indicies t imm)))

(defmethod update-indicies :comment
  [_ {:keys [hash content] :as comment}]
  (let [{:keys [refers-to]} content]
    (let [master-index (s3/master-index)
          rav (s3/get-index (:rav (:content master-index)))
          views (get-in rav [refers-to :refers-to])]
      (if (empty? views)
        1 2)
      )
    ))
