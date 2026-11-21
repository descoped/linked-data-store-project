#!/bin/sh
# Sends one GraphQL query to LDS and prints the answer.
#
#   query.sh <file.graphql | -> [variables as a JSON object | ""] [snapshot time]
#
# The query is read from the file, or from standard input with "-". The snapshot time makes it an as-of read
# (for example 2021-06-01T00:00:00Z). LDS is $LDS, else http://localhost:9090. With jq the answer is
# pretty-printed; set RAW=1 to get it as LDS sent it. The exit status is 0 when the answer has no errors,
# 1 when it has errors (they are printed with the answer), 2 when LDS did not answer 200.
set -eu

[ $# -ge 1 ] || { echo "usage: query.sh <file.graphql | -> [variables-json] [snapshot]" >&2; exit 2; }
LDS=${LDS:-http://localhost:9090}
file=$1
variables=${2:-}
[ -n "$variables" ] || variables='{}'
snapshot=${3:-}

if [ "$file" = "-" ]; then query=$(cat); else query=$(cat "$file"); fi

url="$LDS/graphql"
if [ -n "$snapshot" ]; then
  # In an address a + would be read as a space, and curl treats [ ] as a pattern.
  url="$url?snapshot=$(printf '%s' "$snapshot" | sed -e 's/+/%2B/g' -e 's/\[/%5B/g' -e 's/\]/%5D/g')"
fi

if command -v jq >/dev/null 2>&1; then
  body=$(jq -n --arg q "$query" --argjson v "$variables" '{query: $q, variables: $v}')
else
  # Without jq: the query on one line with \ and " escaped. GraphQL reads a line break as a space.
  escaped=$(printf '%s' "$query" | tr '\n\r\t' '   ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')
  body="{\"query\": \"$escaped\", \"variables\": $variables}"
fi

# charset=utf-8: without it LDS reads the body as ISO-8859-1 and a letter outside ASCII is read wrongly.
answer=$(curl -sS --fail-with-body "$url" -H 'Content-Type: application/json; charset=utf-8' \
              --data-binary "$body") || {
  printf '%s\n' "$answer" >&2
  echo "LDS did not answer 200 (see the status above). A 500 with no body is a request LDS could not read." >&2
  exit 2
}

if command -v jq >/dev/null 2>&1 && [ -z "${RAW:-}" ]; then
  printf '%s\n' "$answer" | jq .
else
  printf '%s\n' "$answer"
fi

case $answer in
  *'"errors":'*) exit 1 ;;
  *) exit 0 ;;
esac
