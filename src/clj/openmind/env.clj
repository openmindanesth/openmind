(ns openmind.env)

(def ^:dynamic *dev?*
	(System/getenv "DEV_MODE"))

(def els-user
	(System/getenv "ELASTIC_USERNAME"))

(def els-password
	(System/getenv "ELASTIC_PASSWORD"))
