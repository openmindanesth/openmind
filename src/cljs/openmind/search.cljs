(ns openmind.search)

(def filters
  {:species     {:human  "human"
                 :monkey "monkey"
                 :rat    "rat"
                 :mouse  "mouse"}
   :modality    {:neuron      "neuron level"
                 :ensemble    "ensemble (LFP)"
                 :cortex      "cortex (EEG)"
                 :large-scale "large scale (fMRI)"}
   :application {:some "Some" :none "None"}
   :depth       {:light    "light"
                 :moderate "moderate"
                 :deep     "deep"}
   :physiology  {:bp   "blood pressure"
                 :hr   "heart rate"
                 :heme "hemodynamics"
                 :br   "breathing rate"}})
