(ns openmind.db
 (:require [openmind.edn :as edn]
           ;; We need to read in openmind.hash here, otherwise the reader can't
           ;; parse #openmind.hash/ref tags.
           [openmind.hash]))

(def default-db
  {::domain         "anaesthesia"
   :tag-tree-hash   (edn/read-string
                     "#openmind.hash/ref \"eae48c022e8f3af8b85829509c9ecdf4\"")
   ;; FIXME: Hardcoded root of tag tree.
   :tag-root-id     (edn/read-string
                     "#openmind.hash/ref \"ad5f984737860c4602f4331efeb17463\"")
   ::tag-tree       ::uninitialised
   :tag-lookup      ::uninitialised

   :openmind.components.tags/search {:search/selection []}
   :openmind.router/route           nil})
