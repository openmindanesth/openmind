(ns openmind.notification
  "Currently every client gets notified on every change, which will not
  scale. But it makes for nice reactivity and a simple demo."
  (:require [clojure.core.async :as async]))

(defonce send-fn (atom nil))

(defonce get-all-connections-fn (atom nil))

(defn notify-all! [message]
  (when-let [f @send-fn]
    (when (fn? @get-all-connections-fn)
      (run! #(f % message)
            (@get-all-connections-fn)))))

(defn init-notification-system!
  "The notification system needs to be given a function that it can call to
  notify clients, but such a function doesn't exist until the server is up and
  running. Hence..."
  [send clients]
  (reset! send-fn send)
  (reset! get-all-connections-fn clients))

(def creation-listeners
  (atom {}))

(defn notify-on-creation [uid hash]
  (swap! creation-listeners update hash conj uid))

(defn notify-on-assertion [uid hash])

(defn notify-on-retraction [uid hash])

(def metadata-chan
  (async/chan (async/sliding-buffer 128)))

(defn metadata-update [id metadata]
  (notify-all! [:openmind/updated-metadata {:extract  id
                                        :metadata metadata}])
  #_(async/put! metadata-chan [id metadata]))

(defn extract-created [extract]
  (when-let [send @send-fn]
    (run! #(send % [:openmind/extract-created extract])
          (get @creation-listeners extract))
    ;; REVIEW: This looks like a possible race condition where last instant
    ;; listeners don't get notified, but who is actually capable of listening
    ;; for the creation of an extract that doesn't exist yet? No one but the
    ;; person who initiated the creation.
    ;;
    ;; So I'm running on the assumption this is fine. I might be wrong.
    (swap! creation-listeners dissoc extract)))

(defn extract-edited [oldhash newhash]
  ;; we don't really listen for changes, just for the creation of the new
  ;; extract. That may change.
  (extract-created (or newhash oldhash)))

(def metadata-pub
  (async/pub metadata-chan first))

(defn watch-extract [id]
  ;; Only the most recent metadata is relevant
  (let [ch (async/chan (async/sliding-buffer 1))]
    (async/sub metadata-pub id ch)
    ch))

(defn unwatch [id ch]
  (async/unsub metadata-pub id ch))
