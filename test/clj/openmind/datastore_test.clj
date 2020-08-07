(ns openmind.datastore-test
  (:require [openmind.datastore :as ds]
            [openmind.hash :as h]
            [openmind.test.common :as c]
            [clojure.test :as t]))

(def author {:name     "Dev Johnson"
             :orcid-id "NONE"})

(t/deftest get-what-you-save
  (ds/intern {:hash         #openmind.hash/ref "123"
              :time/created (java.util.Date.)
              :author       author
              :content      {:text    "I am a test example"
                             :extract #openmind.hash/ref "1234"}})

  (c/wait-for-queues)

  (t/is (= {:text    "I am a test example"
            :extract #openmind.hash/ref "1234"}
           (:content (ds/lookup #openmind.hash/ref "123")))))

(t/deftest transact
  (let [relation {:entity    #openmind.hash/ref "ab"
                  :attribute :contrast
                  :value     #openmind.hash/ref "cd"}
        hash     (h/hash relation)]
    (t/is (= :success
             (:status
              (ds/transact {:context      {hash relation}
                            :assertions   [[:assert hash]]
                            :author       author
                            :time/created (java.util.Date.)}))))

    (c/wait-for-queues)

    (t/is (= relation (:content (ds/lookup hash))))))

;; TODO: test that invalid transaction fail properly, test that clients can't
;; assert false hashes for data, test that large transactions work. If possible
;; test that the order of transactions is respected.

(defn test-ns-hook []
  (c/stub-s3 (get-what-you-save))
  (c/stub-s3 (transact)))
