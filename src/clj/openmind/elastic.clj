(ns openmind.elastic
  (:require [clojurewerkz.elastisch.rest :as es]
            [openmind.env :as env]))

(def conn
  (es/connect (env/read :elastic-url)
              {:basic-auth [(env/read :elastic-username)
                            (env/read :elastic-password)]}))
