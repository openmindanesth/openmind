FROM clojure

# Build cljs

RUN clojure -m cljs.main --optimizations advanced --output-to "resources/public/cljs-out/dev-main.js" -c openmind.core

# Run the server

RUN clojure -m openmind.server