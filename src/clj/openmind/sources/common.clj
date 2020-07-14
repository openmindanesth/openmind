(ns openmind.sources.common
  (:require [clojure.core.async :as async]
            [org.httpkit.client :as http]))

(defn fetch
  "Sends a GET request for `url` and returns a channel which will eventually
  yield the body of the response if the status is 200. The channel will close
  without emitting anything if the request fails."
  [url]
  (let [out (async/promise-chan)]
    (http/request {:method :get :url url}
                  (fn [res]
                    (if (= 200 (:status res))
                      (async/put! out (:body res))
                      (async/close! out))))
    out))
