#!/usr/bin/env bash

echo "Tesing elasticsearch queries"

echo "starting cluster"

DOCKER_CID=$(docker run -d -p 9222:9200 -p 9333:9300 -e "discovery.type=single-node" elasticsearch:7.4.0)

export ELASTIC_URL="http://localhost:9222/"

sleep 10

echo "intialising index mapping"

clojure -A:dev -e "(do (require 'setup) (setup/init-cluster!))"

echo "running tests"

clojure -A:test -m openmind.elastic-test

RESULT=$?

echo "killing cluster"

docker kill $DOCKER_CID

exit $RESULT
