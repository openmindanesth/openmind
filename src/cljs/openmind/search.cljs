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

;; New plan:
;;
;; Tags form a tree or arbitrary depth. When you create a new tag, you're really
;; creating a new leaf in a tree. That leaf may be at any level.
;;
;; A main design goal is to have an open ended but somehow controlled set of
;; tags. I want to be able to refer to blood pressure in the context of
;; physiological abnormality as well as in the context of data included in the
;; dataset, and I don't want to confound these two.
;;
;; To that end, each tag node will be an entity with an id: anaesthesia will be
;; e0001, say, then anasthesia::species will be e0002 which will point back not
;; to the word anaesthesia, but to e0001, and so on. So the UI will always
;; contain plain terms, but under the hood, those tags will be resolved to
;; unique IDs. This allows users to create their own tags and search each
;; others' tags explicitely. That's important for quality control. Furthermore,
;; it's important to be able to collapse tag sets, when it is inevitably
;; discovered that redundancy has been introduced. Then we have the tedious
;; errand of reconcilling slightly differing taxonomies. Never fun, impossible
;; to automate, and the bane of all classificatory work. But at least we must
;; leave a door open by which to do it.
;;
;; At first we'll mitigate this by not allowing the creation of new tags via the
;; main interfaces. That is to say we will curate all taxonomies. Of course
;; we'll make the same errors users will, but at least they'll be our errors and
;; hopefully easier to reconcile... At the very least there are only two of us
;; to mess things up instead of a world of researchers.
;;
;; I wonder how long that state of affairs will last. I give it a week before
;; each new researcher asks for the ability to add tags themselves. I forsee
;; myself being flooded by taxonomical work I haven't a great deal of heart for
;; and giving in, but I must resist until someone points out a robust potential
;; solution.
;;
;; Open question: I want to be able to grab the entire taxonomy efficiently. It
;; may have to be chunked eventually, early on we can certainly shove it all
;; into a single request. In principle in any case. How to do that efficiently
;; with elastic is a another task for me.
