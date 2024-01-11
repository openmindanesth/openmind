(ns openmind.xhrio
  (:require goog.net.XhrIo))

(defn send! [path cb]
  (goog.net.XhrIo/send path cb))
