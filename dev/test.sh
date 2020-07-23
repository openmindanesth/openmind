#!/usr/bin/env bash

set -e

clojure -A:test -m openmind.test-core
