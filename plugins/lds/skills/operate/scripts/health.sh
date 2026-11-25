#!/bin/sh
# Health report of one Linked Data Store (LDS).
#
#   sh health.sh [base-url] [container]
#
# base-url   where LDS answers, default http://localhost:9090
# container  the name of its Docker container, optional; with it the report also has the state of the
#            container and what its log says about the start, the stores and recent errors
#
# It only reads: it asks /ping and /health/ready and, with a container, reads `docker inspect` and
# `docker logs`. It writes nothing to LDS.

url=${1:-http://localhost:9090}
url=${url%/}
container=$2

echo "== LDS at $url"
out=$(curl -s -o /dev/null -m 10 -w '%{http_code} %{time_total}' "$url/ping")
case ${out%% *} in
  000) echo "/ping: no answer (no connection, or none within 10 s)" ;;
  *) echo "/ping: ${out%% *} in ${out#* }s" ;;
esac
# /health/ready asks every part LDS needs, each within 2 s: 200 UP or DEGRADED (search away or behind), 503 DOWN
ready=$(curl -s -m 10 -w '\n%{http_code}' "$url/health/ready")
code=$(printf '%s' "$ready" | tail -n 1)
body=$(printf '%s' "$ready" | sed '$d')
case $code in
  000) echo "/health/ready: no answer" ;;
  *) echo "/health/ready: $code"
     if command -v jq >/dev/null 2>&1 && printf '%s' "$body" | jq -e .status >/dev/null 2>&1; then
       printf '%s' "$body" | jq -r '"  LDS is \(.status)", (.checks | to_entries[] |
         "  \(.key): \(.value.status)" + ([.value | del(.status) | to_entries[] | " \(.key)=\(.value)"] | join(",")))'
     else
       echo "  $body"
     fi ;;
esac

[ -z "$container" ] && exit 0

echo
echo "== Container $container"
if ! docker inspect "$container" >/dev/null 2>&1; then
  echo "no container with that name"
  exit 0
fi
docker inspect -f 'state: {{.State.Status}}, exit code: {{.State.ExitCode}}, restarts: {{.RestartCount}}' \
  "$container"
docker inspect -f '{{if .State.Health}}health check: {{.State.Health.Status}}{{else}}health check: none{{end}}' \
  "$container"
docker inspect -f 'image: {{.Config.Image}}' "$container"

# only the log of the last start: a container that was started again keeps the log of its earlier runs
started=$(docker inspect -f '{{.State.StartedAt}}' "$container")
log=$(docker logs --since "$started" "$container" 2>&1)

echo
echo "== What it runs with (log since $started)"
# a line of the log without its time, thread, level and logger
message='s/^[^ ]+ \[[^]]*\] [A-Z]+ +[^ ]+ - //'
echo "$log" | grep -E 'Managed domains|Creating specification|Using persistence provider|Using saga-log-pool' |
  sed -E "$message"
echo "$log" | grep -E 'Using transaction-log|Search index provider configured|GraphQL Search is disabled|index queue is' |
  sed -E "$message"
echo "$log" | grep -E 'Rebuilding the search index|search index is rebuilt|Listening on' |
  sed -E "$message"

echo
echo "== Start"
if echo "$log" | grep -q 'Server started'; then
  echo "$log" | grep 'Server started' | tail -n 1 | sed -E "$message"
else
  echo "no line 'Server started' in the log: LDS has not started"
fi
echo "$log" | grep -E '^Exception in thread "main"' | tail -n 1 | cut -c1-400
# a second try or more: LDS waited for an engine at start
echo "$log" | grep -E 'initialization attempt # ([2-9]|[1-9][0-9]+)$' | tail -n 3 | sed -E "$message"

echo
echo "== Events in the log"
found=
for pattern in 'UT005071: Undertow request failed' 'Observed Saga failure' 'LDS is DOWN' 'LDS is DEGRADED' \
  'The readiness check' 'The search index did not take' 'Recovery of saga succeeded' \
  'Recovery of saga failed' 'moving all entries to dead-saga-log' 'No available saga-logs' \
  'Unable to acquire exclusive lock' 'only one process may write a topic' \
  'The rebuild of the search index failed' 'ShutdownHook triggered'; do
  n=$(echo "$log" | grep -c -- "$pattern")
  [ "$n" -gt 0 ] && echo "$n x $pattern" && found=1
done
[ -z "$found" ] && echo "none of the events that tell of a fault"

echo
echo "== Last errors and warnings (at most 5)"
echo "$log" | grep -E '^[0-9TZ:.+-]+ \[[^]]*\] (ERROR|WARN) ' | tail -n 5 |
  sed -E 's/^([^ ]+) \[[^]]*\] /\1 /' | cut -c1-300
exit 0
