(ns openmind.elastic
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [openmind.env :as env]
            [org.httpkit.client :as http]))

(def mapping
  {:properties {:created {:type :date}}})

(def m2 {:_timestamp
         {:enabled true
          :store true}})

;;;;; Translation from client to Elastic Search

(defn tag-name [k]
  (str "tags." (name k)))

(defn build-filter-query [filters]
  (into []
        (comp (map (fn [[k v]]
                     (when (seq v)
                       (if (= 1 (count v))
                         {:term {(tag-name k) (first v)}}
                         {:terms_set {(tag-name k)
                                      {:terms (into [] v)
                                       ;; FIXME: This is positively
                                       ;; idiotic... but it works!
                                       :minimum_should_match_script
                                       {:source "1"}}}}))))
              (remove nil?))
        filters))

(defn search->elastic [{:keys [term filters]}]
  {:query
   {:bool
    (merge {}
           (when (seq filters)
             {:filter (build-filter-query filters)})
           (when (seq term)
             {:must {:match {:text term}}}))}})

;;;;; REST API wrapping

(def base-req
  {:basic-auth [(env/read :elastic-username) (env/read :elastic-password)]
   :headers {"Content-Type" "application/json"}
   :user-agent "Openmind server"})

(def base-url
  (str (env/read :elastic-url)))

(def cluster-settings
  (merge base-req
         {:method :get
          :url (str base-url "/_cluster/settings")}))


(defn index [index doc]
  (merge base-req
         {:method :post
          :url (str base-url "/" (name index) "/_doc/")
          :body (json/write-str doc)}))

(defn search [index body]
  (let [qbody (json/write-str body)]
    (merge base-req
           {:method :get
            :url (str base-url "/" (name index) "/_search")
            :body qbody})))

(def most-recent
  (search :test0 {:sort {:created {:order :desc}}
                  :from 0
                  :size 10}))

;;;;; Wheel #6371

(defn parse-response
  "Interpret Elastic Search status codes and parse response appropriately."
  [{:keys [status body] :as res}]
  (cond
    (<= 200 status 299) (json/read-str body :key-fn keyword)
    (= 404 status)      []
    :else               (do
                          (println "Elastic Error:" )
                          (pprint res))))

(defn send-off!
  "Sends HTTP request req and returns a core.async promise channel which will
  eventually contain the result."
  [req]
  (let [out-ch (async/promise-chan)]
    (http/request req #(async/put! out-ch %))
    out-ch))

;;;;; Testing helpers

(def tx (atom nil))
(defn t [q] (async/go (reset! tx (async/<! (send-off! q)))))

;; API
