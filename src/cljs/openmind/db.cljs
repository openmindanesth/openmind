(ns openmind.db
 (:require [cljs.spec.alpha :as s]))

(def default-db
  {::domain                               "anaesthesia"
   ::tag-tree                             ::uninitialised
   :tag-lookup                            ::uninitialised
   ::status-message                       ""
   :openmind.components.tags/search       {:search/selection []}
   :openmind.router/route                 nil})
