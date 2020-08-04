(ns openmind.datastore
  (:refer-clojure :exclude [intern])
  (:require [clojure.spec.alpha :as s]
            [clojure.stacktrace :as st]
            [openmind.datastore.impl :as impl]
            [openmind.s3 :as s3]
            [openmind.spec :as spec]
            [openmind.util :as util]
            [taoensso.timbre :as log]))

;; TODO: Use potemkin/import-vars

(def intern impl/intern)

(def lookup impl/lookup)
