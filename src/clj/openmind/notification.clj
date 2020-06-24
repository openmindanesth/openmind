(ns openmind.notification
  (:require [clojure.core.async :as async]))

(defonce notifier (atom nil))

(defn notify! [message]
  (when-let [f @notifier]
    (f message)))

(defn init-notification-system!
  "The notification system needs to be given a function that it can call to
  notify clients, but such a function doesn't exist until the server is up and
  running. Hence..."
  [f]
  (reset! notifier f))

(def metadata-chan
  (async/chan (async/sliding-buffer 128)))

(defn metadata-update [id metadata]
  (notify! [:openmind/updated-metadata {:extract  id
                                        :metadata metadata}])
  (async/put! metadata-chan [id metadata]))

(defn extract-created [extract])

(defn extract-edited [oldhash newhash])

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
