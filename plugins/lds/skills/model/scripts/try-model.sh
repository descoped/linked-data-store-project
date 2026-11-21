#!/bin/sh
# Try a model of Linked Data Store (LDS) in a throwaway container, before the real LDS gets it.
#
#   try-model.sh [--keep] <model-folder> [port]
#
# The folder holds either one GraphQL schema (a file ending in .graphql) or JSON Schema files (*.json), and is
# mounted read-only at /lds/schemas. LDS runs in memory on localhost:<port> (9098 when not given) in a container
# named lds-model-try. The script waits until LDS answers, ends or hangs, and prints what LDS read or why it did
# not start. Without --keep the container is removed at the end; with it, LDS keeps running for test writes.
# The image is descoped/lds:3.0.0, or the one named in the variable LDS_IMAGE; the container name can be set
# in LDS_TRY_NAME.
# Exit status: 0 when LDS read the model and answers, 1 when it did not, 2 on wrong use.

keep=
if [ "$1" = --keep ]; then keep=1; shift; fi
[ -d "$1" ] || { echo "usage: try-model.sh [--keep] <model-folder> [port]" >&2; exit 2; }
folder=$(cd "$1" && pwd)
port=${2:-9098}
image=${LDS_IMAGE:-descoped/lds:3.0.0}
name=${LDS_TRY_NAME:-lds-model-try}
command -v docker >/dev/null 2>&1 || { echo "docker is not installed or not on the PATH" >&2; exit 2; }

# Which form: a GraphQL schema wins, as in LDS. Several .graphql files: LDS reads one, so say which.
graphql=$(cd "$folder" && ls -1 -- *.graphql 2>/dev/null)
count=$(printf '%s' "$graphql" | grep -c . )
if [ "$count" -gt 1 ]; then
  echo "The folder has $count .graphql files. LDS reads one: keep one in the folder." >&2; exit 2
elif [ "$count" -eq 1 ]; then
  form="GraphQL schema ($graphql)"
  set -- -e "LDS_GRAPHQL_SCHEMA=schemas/$graphql"
else
  json=$(cd "$folder" && ls -1 -- *.json 2>/dev/null | tr '\n' ' ')
  [ -n "$json" ] || { echo "The folder has no .graphql file and no .json file." >&2; exit 2; }
  form="JSON Schema files ($json)"
  set -- -e LDS_GRAPHQL_SCHEMA=
fi

docker rm -f "$name" >/dev/null 2>&1
echo "Trying the $form with $image on http://localhost:$port"
docker run -d --name "$name" -p "127.0.0.1:$port:9090" -v "$folder:/lds/schemas:ro" "$@" "$image" >/dev/null || exit 1

reason() {
  docker logs "$name" 2>&1 | grep -E '^Exception in thread|^Caused by|Exception: |StackOverflowError' \
    | grep -v '^[[:space:]]*at ' | cut -c1-400 | head -3
}

result=hang
i=0
while [ $i -lt 30 ]; do
  if [ "$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null)" = exited ]; then result=ended; break; fi
  if docker logs "$name" 2>&1 | grep -q 'No schemas configured!'; then result=empty; break; fi
  if [ "$(curl -s -o /dev/null -m 1 -w '%{http_code}' "http://localhost:$port/ping")" = 200 ]; then
    result=up; break
  fi
  sleep 1; i=$((i + 1))
done

status=1
case $result in
  up)
    docker logs "$name" 2>&1 | grep -E 'Managed domains|Unknown keyword' | sed 's/^.* - //' | cut -c1-200
    list=$(curl -s "http://localhost:$port/ns?schema")
    echo "GET /ns?schema: $list"
    status=0
    # Each entity type must answer its list of documents; a JSON Schema file LDS could not use answers 400.
    for entity in $(printf '%s' "$list" | tr -d '[]"' | tr ',' '\n' | sed 's|^/ns/||; s|?schema$||'); do
      answer=$(curl -s -w ' %{http_code}' "http://localhost:$port/ns/$entity")
      case $answer in
        *' 200') ;;
        *) echo "GET /ns/$entity is answered: $answer"; status=1 ;;
      esac
    done
    [ $status -eq 0 ] && echo "Every entity type answers." ;;
  ended)
    echo "LDS ended at start. The reason, from its log:"; reason ;;
  empty)
    echo "LDS found no entity type in the model (No schemas configured!)." ;;
  hang)
    echo "LDS did not answer within 30 seconds and did not end: it hangs. From its log:"; reason ;;
esac

if [ -n "$keep" ] && [ $status -eq 0 ]; then
  echo "LDS keeps running. Remove it with: docker rm -f $name"
else
  docker rm -f "$name" >/dev/null
fi
exit $status
