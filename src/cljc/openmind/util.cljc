(ns openmind.util
  (:require [clojure.spec.alpha :as s]
            [openmind.hash :as h]))

(defn immutable
  "Returns an immutable entry for content which can be added to the datastore."
  [content]
  {:pre  [(s/valid? :openmind.spec/content content)]
   :post [#(s/valid? :openmind.spec/immutable %)]}
  (let [h (h/hash content)]
    {:hash         h
     :time/created (java.util.Date.)
     :content      content}))
