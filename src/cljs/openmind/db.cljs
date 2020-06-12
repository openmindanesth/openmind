(ns openmind.db
 (:require [openmind.edn :as edn]
           ;; We need to read in openmind.hash here, otherwise the reader can't
           ;; parse #openmind.hash/ref tags.
           [openmind.hash]))

(def default-db
  {::domain         "anaesthesia"
   :tag-tree-hash   (edn/read-string
                     "#openmind.hash/ref \"f6930475ab0646ad37ccb6420d6492ed\""
                     )
   ;; FIXME: Hardcoded root of tag tree.
   :tag-root-id     (edn/read-string
                     "#openmind.hash/ref \"ad5f984737860c4602f4331efeb17463\"")
   ::tag-tree       ::uninitialised
   :tag-lookup      ::uninitialised
   ::status-message ""

   :openmind.components.tags/search {:search/selection []}
   :openmind.router/route           nil})
