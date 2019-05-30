(ns openmind.db
  (:require [openmind.search :as search]))

(def dummy-results
  [{:text "Medetomidine has no dose-dependent effect on the BOLD response to subcutaneous electrostimulation (0.5, 0.7, 1 mA) in mice for doses of 0.1, 0.3, 0.7, 1.0, 2.0 mg/kg/h."
    :reference "Nasrallah et al., 2012"
    :tags
    {:type :extract
     :species :human
     :modality :cortex
     :depth :moderate}}
   {:text "Medetomidine has been shown to promote vasoconstriction in rats measured by decrease in central arterial diameter."
    :reference "Another et al., 1996"
    :tags
    {:type :extract
     :species :mouse
     :modality :cortex
     :depth :moderate}}
   {:text "BOLD responses under medetomidine are attenuated and onset is delayed in mechanically ventilated mice."
    :reference "Schroeter et al., 2014"
    :tags
    {:type :extract
     :species :human
     :modality :cortex
     :depth :moderate}}])

(def empty-filters
  (into {} (map (fn [[k v]] [k #{}])) search/filters))

(def default-db
  {:search {:term nil
            :filters empty-filters}
   :route :openmind.views/search
   :results dummy-results})
