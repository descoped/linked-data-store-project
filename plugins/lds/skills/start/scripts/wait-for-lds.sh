#!/bin/sh
# Wait until Linked Data Store (LDS) answers, or say why it did not start.
#
#   wait-for-lds.sh [url] [container] [seconds]
#
#   url        where LDS answers, default http://localhost:9090
#   container  the name of the container of LDS (docker run --name, or <project>-lds-1 in a compose stack);
#              with it the script stops early when the start fails, and prints what LDS runs with
#   seconds    how long to wait, default 120 (LDS waits up to 60 s for the store of the documents, and with
#              Neo4j away it takes about 90 s to give up)
#
# Exit 0: LDS answers /ping with 200. Exit 1: it did not; the reason is printed.

url=${1:-http://localhost:9090}
url=${url%/}
container=$2
seconds=${3:-120}

# the log of the current run of the container only, not of the runs before a restart
current_log() {
  docker logs --since "$(docker inspect -f '{{.State.StartedAt}}' "$container")" "$container" 2>&1
}

failure() {
  echo "LDS did not start: $1" >&2
  if [ -n "$container" ]; then
    echo "--- the cause, from the log of $container:" >&2
    current_log | grep -E 'Exception in thread|^Caused by' | head -n 4 >&2
  fi
  exit 1
}

i=0
while [ "$i" -lt "$seconds" ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url/ping")
  if [ "$code" = 200 ]; then
    echo "LDS answers at $url"
    if [ -n "$container" ]; then
      lines='Managed domains|Using (persistence|saga-log|transaction-log)'
      lines="$lines|Search index provider|GraphQL Search is disabled"
      current_log | grep -E "$lines" | sed 's/^.* - //'
    fi
    exit 0
  fi
  if [ -n "$container" ]; then
    running=$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)
    if [ -z "$running" ]; then
      failure "there is no container named $container"
    fi
    if [ "$running" != true ]; then
      failure "the container has ended with exit code $(docker inspect -f '{{.State.ExitCode}}' "$container")"
    fi
    if current_log | grep -q 'Exception in thread "main"'; then
      failure "the start failed and the process stays without opening its port; stop the container"
    fi
  fi
  if [ "$code" != 000 ]; then
    failure "$url/ping answered $code; is something else on that port, or is http.prefix set?"
  fi
  i=$((i + 1))
  sleep 1
done
failure "no answer at $url/ping within $seconds seconds"
