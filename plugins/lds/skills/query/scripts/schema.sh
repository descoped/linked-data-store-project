#!/bin/sh
# Prints the GraphQL schema that LDS made from the model it was started with.
#
#   schema.sh [address of LDS]        (default: $LDS, else http://localhost:9090)
#
# With jq it prints the schema as GraphQL text: every type, union and enum, each field with its arguments and type.
# Without jq it prints the answer to the introspection query as LDS sent it (JSON on one line).
set -eu

LDS=${1:-${LDS:-http://localhost:9090}}

# One __schema and no "fields" inside "fields": LDS refuses an introspection query that has more.
query='{
  __schema {
    types {
      name
      kind
      description
      fields {
        name
        args { name type { ...T } }
        type { ...T }
      }
      possibleTypes { name }
      enumValues { name }
    }
  }
}
fragment T on __Type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }'

answer=$(curl -sS --fail-with-body "$LDS/graphql" -H 'Content-Type: application/graphql' \
              --data-binary "$query") || {
  printf '%s\n' "$answer" >&2
  echo "LDS at $LDS gave no schema. A 400 with 'Unsupported namespace: \"graphql\"' means there is no GraphQL API:" \
       "the model is JSON Schema, or graphql.enabled is false." >&2
  exit 1
}

if ! command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$answer"
  exit 0
fi

printf '%s\n' "$answer" | jq -r '
def t: if .kind == "NON_NULL" then (.ofType | t) + "!"
  elif .kind == "LIST" then "[" + (.ofType | t) + "]"
  else .name end;
def args: if (.args | length) > 0
  then "(" + ([.args[] | "\(.name): \(.type | t)"] | join(", ")) + ")" else "" end;
if .errors then (.errors[] | "error: \(.message)") else
.data.__schema.types[]
| select(.name | startswith("__") | not)
| select(.kind == "OBJECT" or .kind == "UNION" or .kind == "ENUM")
| (if .description then "\"\"\"\(.description)\"\"\"\n" else "" end)
  + if .kind == "OBJECT" then
      "type \(.name) {\n" + ([.fields[] | "  \(.name)\(args): \(.type | t)"] | join("\n")) + "\n}\n"
    elif .kind == "UNION" then "union \(.name) = " + ([.possibleTypes[].name] | join(" | ")) + "\n"
    else "enum \(.name) { " + ([.enumValues[].name] | join(" ")) + " }\n" end
end'
