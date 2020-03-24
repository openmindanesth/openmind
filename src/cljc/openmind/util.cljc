(ns openmind.util
  (:require [clojure.spec.alpha :as s]
            [openmind.hash :as h]
            [openmind.spec :as spec]
            [taoensso.timbre :as log]))

(defn immutable
  "Returns an immutable entry for content which can be added to the datastore."
  [content]
  (when content
    (if (s/valid? ::spec/content content)
      (let [h (h/hash content)]
        {:hash         h
         :time/created #?(:clj (java.util.Date.) :cljs (js/Date.))
         :content      content})
      (log/error "Can't parse immutable"
                 (s/explain-data ::spec/content content)))))
