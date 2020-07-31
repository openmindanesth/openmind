(ns openmind.components.common)

(defn error [text]
  [:p.text-red.small.pl1.mth.mb0 text])

(defn halt [e]
  (.preventDefault e)
  (.stopPropagation e))

(def scrollbox-style
  {:style {:max-height      "40rem"
           :padding         "0.1rem"
           :scrollbar-width :thin
           :overflow-y      :auto
           :overflow-x      :visible}})
