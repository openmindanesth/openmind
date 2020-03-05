(ns openmind.db
 (:require [cljs.spec.alpha :as s]))

(def default-db
  {::domain       "anaesthesia"
   :tag-tree-hash "c687683618491158660527fc338fc02f"
   :s3-bucket     "test-data-17623"

   ::tag-tree                       ::uninitialised
   :tag-lookup                      ::uninitialised
   ::status-message                 ""
   :openmind.components.tags/search {:search/selection []}
   :openmind.router/route           nil})
