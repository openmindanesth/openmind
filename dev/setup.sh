#!/usr/bin/env bash

docker run -d -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" elasticsearch:7.4.0

echo "waiting for cluster to start"

sleep 10

echo "populating search index"

clj -A:dev -m setup
