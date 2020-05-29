(ns openmind.components.common)

(defn error [text]
  [:p.text-red.small.pl1.mth.mb0 text])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))
