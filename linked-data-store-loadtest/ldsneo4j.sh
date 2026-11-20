#!/usr/bin/env bash

# The load test of LDS with the neo4j provider. The results are in ldsneo4j/results.
exec "$(dirname "$0")/run-loadtest.sh" neo4j
