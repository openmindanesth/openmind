(ns openmind.indexing
  (:require [clojure.spec.alpha :as s]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.spec.indexical :as ind]))

(def master-index
  {:active-extracts []
   :extract-comments {}
   :extract-relations {}
   :rav {}})

;; I need an index that tracks
;; comment -> refers-to(of :comment spec) -> all views involving comment

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
    (println refers-to)))
