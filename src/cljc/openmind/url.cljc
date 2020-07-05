(ns openmind.url)

(def url-regex
  (re-pattern "^(([^:/?#]+)://)?(([^/?#]*))([^?#]*)?(\\?([^#]*))?(#(.*))?"))

(defn parse [url]
  (let [[_ _ prot _ domain path _ query _ hash] (re-matches url-regex url)]
    (into {}
          (remove (comp nil? val))
          {:protocol prot
           :domain   domain
           :path     path
           :query    query
           :hash     hash})))
