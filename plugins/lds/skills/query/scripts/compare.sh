#!/bin/sh
# Compares what one query gives at two times. LDS has no diff; this is it, on the client.
#
#   compare.sh <file.graphql> <earlier time> <later time> [variables as a JSON object]
#
# Sends the query twice (as-of reads at the two times, through query.sh) and prints one line of JSON for every
# value that differs: {"path", "change" (changed, added or removed), "before", "after"}. A list that is read in
# pages (edges) is compared by cursor, not by place, so ask for "cursor" in every edges { } of the query.
# Errors of either answer are printed first. Needs jq. LDS is $LDS, else http://localhost:9090.
set -eu

[ $# -ge 3 ] || { echo "usage: compare.sh <file.graphql> <earlier> <later> [variables-json]" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "compare.sh needs jq" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)

a=$(RAW=1 "$here/query.sh" "$1" "${4:-}" "$2") || [ $? -eq 1 ] || exit 2
b=$(RAW=1 "$here/query.sh" "$1" "${4:-}" "$3") || [ $? -eq 1 ] || exit 2

printf '%s\n' "$a" | jq -c --arg t "$2" '.errors[]? | {at: $t, error: .message}'
printf '%s\n' "$b" | jq -c --arg t "$3" '.errors[]? | {at: $t, error: .message}'

jq -n -c --argjson a "$a" --argjson b "$b" '
def norm: walk(if type == "object" and has("edges")
  then reduce .edges[] as $e ({}; . + {($e.cursor): $e.node}) else . end);
def leaves: [paths(type != "object" and type != "array")];
($a.data | norm) as $a | ($b.data | norm) as $b | ($a | leaves) as $la | ($b | leaves) as $lb
| ($la + $lb | unique)[] as $p
| any($la[]; . == $p) as $ina | any($lb[]; . == $p) as $inb
| {path: ($p | map(tostring) | join(".")),
   change: (if $ina and $inb then "changed" elif $inb then "added" else "removed" end),
   before: (if $ina then $a | getpath($p) else null end),
   after: (if $inb then $b | getpath($p) else null end)}
| select(.change != "changed" or .before != .after)'
