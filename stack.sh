#!/bin/sh
# Starts LDS with Neo4j for the documents, PostgreSQL for the saga log and the transaction log, and Solr for search,
# on this machine, from the compose file of the master branch. For development: the passwords are in the file.
#
#   curl -fsSL https://raw.githubusercontent.com/descoped/linked-data-store-project/master/stack.sh | sh
#   curl -fsSL https://raw.githubusercontent.com/descoped/linked-data-store-project/master/stack.sh | sh -s down
#
# up (the default) starts the stack and waits until it is healthy; down stops it and removes its data.
set -eu
compose=https://raw.githubusercontent.com/descoped/linked-data-store-project/master/plugins/lds/skills/start/assets/compose-stack.yaml
project=${COMPOSE_PROJECT_NAME:-lds}
case "${1:-up}" in
  up) curl -fsSL "$compose" | docker compose -p "$project" -f - up -d --wait
      echo "LDS answers on http://localhost:${SERVER_PORT:-9090}" ;;
  down) docker compose -p "$project" down -v ;;
  *) echo "usage: sh stack.sh [up|down]" >&2; exit 2 ;;
esac
