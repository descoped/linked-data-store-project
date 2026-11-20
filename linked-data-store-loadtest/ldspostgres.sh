#!/usr/bin/env bash

# The load test of LDS with the postgres provider. The results are in ldspostgres/results.
exec "$(dirname "$0")/run-loadtest.sh" postgres
