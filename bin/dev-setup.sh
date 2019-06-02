#!/usr/bin/env bash

docker run -d -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" elasticsearch:7.1.0

# I'll use minio to spoof S3 locally for storing figures, but that isn't necessary yet.
docker run -d -p 9000:9000 minio/minio server /data
