(ns openmind.util
  (:require [clojure.spec.alpha :as s]
            [openmind.hash :as h]
            [openmind.spec :as spec]))

(defn immutable
  "Returns an immutable entry for content which can be added to the datastore."
  [content]
  (if (s/valid? ::spec/content content)
    (let [h (h/hash content)]
      {:hash         h
       :time/created #?(:clj (java.util.Date.) :cljs (js/Date.))
       :content      content})
    (s/explain ::spec/content content)))
