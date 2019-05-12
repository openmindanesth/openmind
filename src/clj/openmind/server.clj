(ns openmind.server
	(:require [clj-http.client :as http]
						))

(defn handler [req]
	(println req)
	)

(defn start-server! []
	#_(jetty/run-jetty handler {:port 3003} ))
