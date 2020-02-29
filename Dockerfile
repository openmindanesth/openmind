from openjdk:11-jre-slim

ADD target/openmind.jar openmind.jar

EXPOSE ${PORT}

ENTRYPOINT java $JVM_OPTS -server -cp openmind.jar clojure.main -m openmind.server
