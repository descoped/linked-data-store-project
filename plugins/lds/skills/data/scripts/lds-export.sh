#!/bin/sh
# Exports the documents of one entity type of LDS as they were at one instant, as JSON or CSV.
# Usage: LDS=http://localhost:9090 NS=ns sh lds-export.sh Entity [instant] [json|csv] > out
#   instant: a time in UTC ending in Z, such as 2024-01-01T00:00:00Z. Default: now.
# JSON: [{"id": "...", "document": {...}}, ...] in the order of the ids.
# CSV: one row per document; nested fields as a.b, lists as JSON text; a field "id" of the document as document.id.
# The ids come from the GraphQL API. A model of JSON Schema files has none: the documents then come without ids.
set -u
LDS=${LDS:-http://localhost:9090}
NS=${NS:-ns}
entity=${1:?usage: lds-export.sh Entity [instant] [json|csv]}
at=${2:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
format=${3:-json}
page=500

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "lds-export.sh needs $tool, which is not installed." >&2; exit 2; }
done
case $entity in *[!A-Za-z0-9_]*|[0-9]*|'') echo "Not an entity type name: $entity" >&2; exit 2 ;; esac
case $at in *Z) ;; *) echo "Give the instant in UTC ending in Z, such as 2024-01-01T00:00:00Z." >&2; exit 2 ;; esac
case $format in json|csv) ;; *) echo "The format is json or csv." >&2; exit 2 ;; esac
at_q=$(printf '%s' "$at" | jq -sRr @uri)

# The ids of the documents that exist at the instant, page by page, from the GraphQL API.
ids=$(mktemp); docs=$(mktemp); trap 'rm -f "$ids" "$docs"' EXIT
after=""
while :; do
  q=$(jq -n -c --arg e "$entity" --arg a "$after" --argjson n "$page" '{
    query: "query($n: Int, $a: String) { \($e)(first: $n, after: $a) { edges { cursor } pageInfo { hasNextPage } } }",
    variables: ({n: $n} + (if $a == "" then {} else {a: $a} end))}')
  ans=$(curl -s "$LDS/graphql?snapshot=$at_q" -H 'Content-Type: application/json' -d "$q")
  if ! printf '%s' "$ans" | jq -e --arg e "$entity" '.data[$e].edges' >/dev/null 2>&1; then
    if printf '%s' "$ans" | grep -q 'Unsupported namespace: "graphql"'; then
      # A model of JSON Schema files has no GraphQL API: the REST list gives the documents without their ids.
      echo "This LDS has no GraphQL API (its model is JSON Schema files): the documents come without ids." >&2
      list=$(curl -s -f "$LDS/$NS/$entity?timestamp=$at_q") \
        || { echo "LDS refused: GET $LDS/$NS/$entity?timestamp=$at_q" >&2; exit 1; }
      printf '%s' "$list" | jq -c '.[] | {id: null, document: .}' > "$docs"
      break
    fi
    echo "The GraphQL API gave no list of $entity:" >&2
    printf '%s\n' "$ans" | head -c 500 >&2; echo >&2
    exit 1
  fi
  printf '%s' "$ans" | jq -r --arg e "$entity" '.data[$e].edges[].cursor' >> "$ids"
  [ "$(printf '%s' "$ans" | jq --arg e "$entity" '.data[$e].pageInfo.hasNextPage')" = true ] || break
  after=$(tail -n 1 "$ids")
done

# Each document as of the instant, from the REST API.
while IFS= read -r id; do
  id_q=$(printf '%s' "$id" | jq -sRr @uri)
  doc=$(curl -s -w '\n%{http_code}' "$LDS/$NS/$entity/$id_q?timestamp=$at_q")
  code=$(printf '%s' "$doc" | tail -n 1)
  [ "$code" = 200 ] || { echo "Skipped $id: LDS answered $code" >&2; continue; }
  printf '%s' "$doc" | sed '$d' | jq -c --arg id "$id" '{id: $id, document: .}' >> "$docs"
done < "$ids"

if [ "$format" = json ]; then
  jq -s '.' "$docs"
else
  jq -s -r '
    def flat($p): to_entries | map(
      (if $p == "" then .key else "\($p).\(.key)" end) as $k
      | if (.value | type) == "object" and (.value | length) > 0 then .value | flat($k) | to_entries[]
        elif (.value | type) == "array" or (.value | type) == "object" then {key: $k, value: (.value | tojson)}
        else {key: $k, value: .value} end) | from_entries;
    map({id} + (.document | flat("") | with_entries(if .key == "id" then .key = "document.id" else . end))) as $rows
    | (["id"] + ($rows | map(keys_unsorted[]) | unique - ["id"])) as $cols
    | ($cols | @csv), ($rows[] | [.[$cols[]]] | @csv)' "$docs"
fi
