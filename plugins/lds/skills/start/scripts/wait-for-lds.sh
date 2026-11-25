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
# Exit 0: LDS answers /ping with 200 and /health/ready with 200 (UP, or DEGRADED: search is away or behind).
# Exit 1: it did not; the reason is printed, or the answer of /health/ready when a part a write needs is DOWN.

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
  cause=
  [ -n "$container" ] && cause=$(current_log | grep -E 'Exception in thread|^Caused by' | head -n 4)
  if [ -n "$cause" ]; then
    echo "--- the cause, from the log of $container:" >&2
    printf '%s\n' "$cause" >&2
  fi
  exit 1
}

i=0
while [ "$i" -lt "$seconds" ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url/ping")
  if [ "$code" = 200 ]; then
    echo "LDS answers at $url"
    ready=$(curl -s --max-time 10 -w '\n%{http_code}' "$url/health/ready")
    status=$(printf '%s' "$ready" | sed -n 's/^{"status":"\([A-Z]*\)".*/\1/p')
    echo "Ready: ${status:-no answer}"
    if [ -n "$container" ]; then
      lines='Managed domains|Using (persistence|saga-log|transaction-log)'
      lines="$lines|Search index provider|GraphQL Search is disabled"
      current_log | grep -E "$lines" | sed 's/^.* - //'
    fi
    if [ "$(printf '%s' "$ready" | tail -n 1)" != 200 ]; then
      echo "A part that writes need does not answer; /health/ready answered:" >&2
      printf '%s\n' "$ready" | sed '$d' >&2
      exit 1
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
    if [ "$i" -ge 10 ] && current_log | grep -q 'Server started'; then
      failure "it has started in the container and does not answer at $url: is http.host set, or does the \
port on the machine lead to another port than LDS listens on?"
    fi
  fi
  if [ "$code" != 000 ]; then
    failure "$url/ping answered $code; is something else on that port, or is http.prefix set?"
  fi
  i=$((i + 1))
  sleep 1
done
failure "no answer at $url/ping within $seconds seconds"
