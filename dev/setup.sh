#!/usr/bin/env bash

docker run -d -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" elasticsearch:7.4.0

clj -A:dev -m setup
