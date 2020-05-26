(ns openmind.components.common)

(defn source-content [{:keys [authors publication/date journal
                            abstract doi title]}]
  [:div
   [:h2 title]
   [:span.smaller.pb1
    [:span (str "(" date ")")]
    [:span.plh  journal]
    [:span.plh " doi: " doi]
    [:em.small.pl1 (apply str (interpose ", " authors))]]
   [:p abstract]])

(defn error [text]
  [:p.text-red.small.pl1.mth.mb0 text])
