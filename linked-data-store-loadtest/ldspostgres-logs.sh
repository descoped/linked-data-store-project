#!/usr/bin/env bash

# The load test of LDS with the postgres provider, and the saga log and the transaction log in PostgreSQL as well.
# The results are in ldspostgres-logs/results.
exec "$(dirname "$0")/run-loadtest.sh" postgres-logs
