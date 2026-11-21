#!/bin/sh
# Writes each record of an NDJSON file to LDS at its valid time. Safe to run again.
# A record:  {"entity": "Person", "id": "ola", "validFrom": "2020-01-01T00:00:00Z", "document": {...}}
# A delete:  {"entity": "Person", "id": "ola", "validFrom": "2024-01-01T00:00:00Z", "deleted": true}
# Usage:     LDS=http://localhost:9090 NS=ns sh lds-load.sh records.ndjson
# Each write carries its valid time (timestamp), so a write sent again replaces the same version: no duplicates.
# Records that are not written go to records.failed.ndjson, with the answer of LDS on stderr; fix and run that file.
set -u
LDS=${LDS:-http://localhost:9090}
NS=${NS:-ns}
in=${1:?usage: lds-load.sh records.ndjson}
for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "lds-load.sh needs $tool, which is not installed." >&2; exit 2; }
done
[ -r "$in" ] || { echo "Cannot read $in" >&2; exit 2; }
curl -s -f -o /dev/null "$LDS/ping" || { echo "LDS does not answer at $LDS/ping" >&2; exit 2; }
failed=${in%.ndjson}.failed.ndjson
body=$(mktemp)
: > "$failed"
ok=0 bad=0
while IFS= read -r rec || [ -n "$rec" ]; do
  [ -z "$rec" ] && continue
  # An id with + or % cannot be used in LDS (it is decoded twice); a record without validFrom has no valid time.
  check=$(printf '%s' "$rec" | jq '(.id | tostring | test("[+%]")) or (.validFrom == null)' 2>/dev/null)
  if [ "$check" != false ]; then
    bad=$((bad + 1)); printf '%s\n' "$rec" >> "$failed"
    echo "skipped (not JSON, no validFrom, or an id with + or %): $rec" >&2; continue
  fi
  url=$(printf '%s' "$rec" | jq -r --arg base "$LDS/$NS" \
    '"\($base)/\(.entity | @uri)/\(.id | @uri)?timestamp=\(.validFrom | @uri)"')
  if [ "$(printf '%s' "$rec" | jq '.deleted == true')" = true ]; then
    code=$(curl -s -o "$body" -w '%{http_code}' -X DELETE "$url")
  else
    code=$(printf '%s' "$rec" | jq -c '.document' |
      curl -s -o "$body" -w '%{http_code}' -X PUT "$url" \
        -H 'Content-Type: application/json' --data-binary @-)
  fi
  case $code in
    201|204) ok=$((ok + 1)) ;;
    *) bad=$((bad + 1))
       printf '%s\n' "$rec" >> "$failed"
       echo "$code $url $(cat "$body")" >&2 ;;
  esac
done < "$in"
rm -f "$body"
echo "written: $ok, not written: $bad (see $failed)"
