(ns openmind.s3-test
  (:require [openmind.datastore.backends.s3 :as s3]
            [openmind.hash :as h]
            [clojure.test :as t]))

(t/deftest no-nils
  (t/is (nil? (s3/write! (h/hash nil) nil))
        "nil should never be stored.")
  (t/is (not (s3/exists? (h/hash nil)))
        "nil should never be in the datastore."))

(def ex
  {:demo [:map :with "stuff"]
   :x    42})

(t/deftest put
  (t/is (s3/write! (h/hash ex) ex)))

(t/deftest exists?
  (t/is (s3/exists? (h/hash ex)))
  (t/is (not (s3/exists? #openmind.hash/ref "1234"))))

(t/deftest fetch
  (t/is (= ex (s3/lookup-raw (h/hash ex))))
  (t/is (= ex (s3/lookup (h/hash ex))))
  (t/is (thrown? software.amazon.awssdk.services.s3.model.NoSuchKeyException
                 (s3/lookup-raw #openmind.hash/ref "abc123")))
  (t/is (nil? (s3/lookup #openmind.hash/ref "abc123"))))
