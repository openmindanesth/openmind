(ns openmind.test-core
  "Test runner entry point.

  Don't run the elastic-test ns here. It needs an elasticsearch cluster to talk
  to."
  (:require  [clojure.test :as t]
             openmind.sources-test
             openmind.extract-editing-test
             openmind.s3-test))

(defn -main [& args]
  (let [summary (t/run-tests 'openmind.s3-test
                             'openmind.sources-test
                             'openmind.extract-editing-test)]
    (println summary)
    (if (and (= 0 (:fail summary))
             (= 0 (:error summary)))
      (System/exit 0)
      (System/exit 1))))
