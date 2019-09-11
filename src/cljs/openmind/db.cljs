(ns openmind.db
 (:require [cljs.spec.alpha :as s]))

(def blank-new-extract
  {:new-extract/selection []
   :new-extract/content   {:tags      #{}
                           :comments  {0 ""}
                           :related   {0 ""}
                           :contrast  {0 ""}
                           :confirmed {0 ""}
                           :figures   {0 ""}}
   :errors                nil})

(def default-db
  {::domain         "anaesthesia"
   ::tag-tree       ::uninitialised
   ::tag-lookup     ::uninitialised
   ::status-message ""
   ::search         {:search/term      nil
                     :search/selection []
                     :search/filters   #{}}
   ::route          :openmind.views/search
   ::new-extract    blank-new-extract
   ::results        []})

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
