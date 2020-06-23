(ns openmind.notification
  (:require [clojure.core.async :as async]))

(def metadata-chan
  (async/chan (async/sliding-buffer 128)))

(defn metadata-update [id metadata]
  (async/put! metadata-chan [id metadata]))

(defn extract-created [extract])

(def metadata-pub
  (async/pub metadata-chan first))

(defn watch-extract [id]
  ;; Only the most recent metadata is relevant
  (let [ch (async/chan (async/sliding-buffer 1))]
    (async/sub metadata-pub id ch)
    ch))

(defn unwatch [id ch]
  ;; REVIEW: What if I just close the subscribing channel? Will that clean up
  ;; correctly or break something internal?
  (async/unsub metadata-pub id ch))
