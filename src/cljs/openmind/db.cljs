(ns openmind.db
 (:require [cljs.spec.alpha :as s]))

(def default-db
  {::domain                         "anaesthesia"
   ::tag-tree                       ::uninitialised
   :tag-lookup                     ::uninitialised
   ::status-message                 ""
   :openmind.components.tags/search {:search/selection []}
   :openmind.router/route           nil
   :openmind.componentes.extract-editor/new-extract
   {:new-extract/selection                      []
    :openmind.components.extract-editor/content {:comments  {0 ""}
                                                 :related   {0 ""}
                                                 :contrast  {0 ""}
                                                 :confirmed {0 ""}
                                                 :figures   {0 ""}
                                                 :tags      #{}}
    :errors                                     nil}
   ::results                        []})

(s/def ::db
  (s/keys :req [::new-extract
                ::tag-tree
                ::route
                ::results
                ::current-search]))

(s/def ::new-extract
  (s/keys :req [:new-extract/selection :new-extract/content]))

(s/def :new-extract/selection
  (s/coll-of string? :distinct true))

(s/def :new-extract/content
  (s/keys :req []))
