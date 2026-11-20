#!/usr/bin/env bash

# The load test of LDS with the memory provider. The results are in ldsmemory/results.
exec "$(dirname "$0")/run-loadtest.sh" memory
