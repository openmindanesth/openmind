FROM clojure:tools-deps


# Run the server

RUN clojure -m openmind.server
