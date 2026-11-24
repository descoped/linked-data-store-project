---
name: data
description: >
  Writes, reads, deletes, links, bulk loads and exports the documents of Linked Data Store (LDS) over its REST
  API, with curl. Use it whenever the user wants to put data into LDS, read it back, see how a record looked on
  a date or its whole history, correct or delete a record, link records to each other, load a CSV or JSON file
  or a database export with its dates, export an entity to JSON or CSV, or understand why LDS refused a write
  (a 400 "Schema validation error", a 404, a 500), even if they never say "REST". Phrases: "store documents in
  LDS", "write a person to LDS", "what did this look like in 2020", "show the history", "import my data",
  "load this CSV into LDS", "export to CSV", "why was my write refused". For the model use lds:model, for
  GraphQL queries lds:query, to run LDS lds:start, to keep it running lds:operate.
compatibility: LDS 3.0; Claude Code
metadata:
  lds-version: 3.0.1
---

# Reading and writing data in LDS

LDS keeps JSON documents of the entity types its model declares, and keeps them **through time**: a write adds
a version that holds from its valid time, and a read asks "as of when". Everything here goes through the REST
API with `curl`; `jq` helps when present. Depth: https://lds.descoped.io/docs/user/rest-api/ and
https://lds.descoped.io/docs/user/concepts/

## Workflow

1. **Find LDS.** Ask for its address, or try the default: `curl -s -o /dev/null -w '%{http_code}\n'
   http://localhost:9090/ping` gives `200`. No answer: LDS is not running; use `lds:start`. Set
   `LDS=http://localhost:9090` and `J='Content-Type: application/json'` for the commands.
2. **Learn the user's model** before writing anything: `curl -s "$LDS/ns?schema"` lists the entity types,
   `curl -s "$LDS/ns/<Entity>?schema"` gives one as JSON Schema (see "Reading the model" below). The namespace
   is `ns` unless the user configured another (a 400 `Unsupported namespace` says so). If the model lacks what
   the user needs, that is `lds:model`.
3. **Ask what they want and at what time.** For every write, settle the valid time: "from when is this true?"
   Without `timestamp` the time of the request is used; that is right for "as of now", wrong for history and
   for anything that may be sent again.
4. **Show the request, run it, show the answer**, then read back what was written. Use `curl -s -w
   '\n%{http_code}\n'` so the status is visible: 404 and 500 have no body.
5. **When LDS refuses**, explain it in plain words with the fix: look the answer up in
   [references/refused-writes.md](references/refused-writes.md).
6. **For many records** (a file, an export from another system) use the loader, following
   [references/bulk-load.md](references/bulk-load.md). **To get data out**, use the exporter, following
   [references/export.md](references/export.md).

Read [references/rest-reference.md](references/rest-reference.md) when a request is not covered here: every
address, method, parameter, status code and known gap, with the answers seen.

## Reading the model

The answer of `?schema` is JSON Schema 2020-12: `{"$ref": "#/$defs/Person", "$defs": {"Person": {...}}}`.

- `properties` are the fields; `required` the fields a document must have.
- `"type": "string", "format": "date-time"` needs a date-time with `Z` or an offset: `2020-01-01T00:00:00Z`.
- A field with a sibling `_link_property_<field>` is a **link**: its value is the text `/<Entity>/<id>` (a list
  of them when the field is an array), and the properties of the sibling name the entity types it may point at.
- A `$ref` to another definition (such as `Address`) is an **embedded object**: part of the document, with no
  id or versions of its own.

## Writing

```sh
curl -s -w '\n%{http_code}\n' -X PUT "$LDS/ns/Person/ola?timestamp=2020-01-01T00:00:00Z" -H "$J" \
     -d '{
       "name": "Ola",
       "address": {"street": "Storgata 1", "city": "Oslo"},
       "employer": "/Company/acme"
     }'
```

```
{"saga-execution-id":"01a20fe2-e905-7e09-bb15-5f76c62a5f07"}
201
```

- `PUT` and `POST` do the same. The body is the **whole document**; there is no `PATCH`. The id is chosen by
  the writer; LDS makes none. 201 also when the document existed: the write added a version.
- Always send `-H 'Content-Type: application/json'`: plain `curl -d` sends a form type, which LDS answers 415
  `Unsupported Content-Type: application/x-www-form-urlencoded. ...`.
- The answer is the id of the saga that carried the write, not the document. It comes when the write is done.
- A field written as `null` is left out: `{"name": "A", "employer": null}` is read back as `{"name": "A"}`.
- Before writing, LDS checks the document against the model (400 when it does not fit, nothing written). It
  does **not** check that a link's target exists: LDS is a time machine, so a link may point at a document
  written later, or deleted. Do not treat such links as errors.
- A field the model does not have is stored and given back (unless a JSON Schema model forbids it).
- With search on, `?searchable=true` on a write answers when a search finds it, at most 10 seconds later, with the
  header `LDS-Searchable: true` (or `false` when search did not find it in time). Use it when a search comes right
  after the write.

## Time: valid time, as-of reads, history

- `?timestamp=` on a write is the valid time of the new version; on a read, the instant to read as of. It
  needs a time and `Z` or an offset; in a URL a `+` is `%2B` (`2022-01-01T00:00:00%2B01:00`). Times are kept in
  UTC.
- A version holds from its valid time until the next one. Versions can be written in any order; a write at
  exactly the valid time of a version replaces that version and touches no other.
- A read gives the latest version not after the instant: `GET "$LDS/ns/Company/globex?timestamp=2021-06-01T00:00:00Z"`.
  Before the first version: 404. Without `timestamp`: as of now, so a version in the future is not seen yet.
- `GET "$LDS/ns/Company/globex?timeline"` gives every version, oldest first:
  `[{"version": "2020-01-01T00:00Z[Etc/UTC]", "document": {...}}, ...]`.
- To show what changed between two dates, read both instants (or the timeline) and compare them yourself;
  LDS has no diff. `lds:query` has a guided tour of the time machine.

## Reading

| Request | Answer |
|---|---|
| `GET /ns/<Entity>/<id>` | 200 the document, 404 (no body) when there is none at that time |
| `GET /ns/<Entity>` | 200 every document as of the instant, in the order of the ids (by Unicode code point), **without ids**, no pages |
| `GET /ns/<Entity>/<id>?timeline` | 200 every version, 404 when there is none |
| `GET /ns/<Entity>/<id>/<field>` | 200 the part: an object as it is, a single value as `["Ola"]`, missing as `[null]` |

The REST API has no search and no filter by field value. Lists with ids and pages, links followed, reverse links
and search are in the GraphQL API (`lds:query`; only with a model that is a GraphQL schema).

## Deleting

`DELETE "$LDS/ns/Person/per?timestamp=2022-01-01T00:00:00Z"` answers 204. It writes a **tombstone**, an empty
version: from that time until the next version the document is not there (404, not in lists); earlier versions
are still read at their times. No request removes a version for good. Links to a deleted document stay as they
are. A delete of an id never written also writes a tombstone.

## Parts and links

```sh
curl -s -w '\n%{http_code}\n' -X PUT "$LDS/ns/Person/ola/address" -H "$J" \
     -d '{"street": "Torggata 5", "city": "Oslo"}'
curl -s -w '\n%{http_code}\n' -X PUT "$LDS/ns/Person/ola/employer/Company/globex"
```

Both are answered `200` with `{"saga-execution-id":"..."}` when the write is done.

- A part is a field by its path. `PUT` replaces that field (not a merge), checks the whole document, and writes
  the whole document as a new version. `DELETE` of a part removes the field (not checked against the model).
- A link has its own address, `/<link field>/<TargetEntity>/<target id>`. `PUT` adds it (a list gets it at its
  end; a single link field is replaced), `GET` answers 200 when it is there and 404 when not, `DELETE` removes
  it and is always 200, with no body. A link there already is answered 200 with no body, and nothing is written.
- Both read the document at the time of the request first: when it has none then, the answer is 404.
- Parts and links wait for the write, as whole documents do; `sync=false` answers before it is done.
- Paths stop at a list (`/ns/Company/c/about/languageText` is 400) and, with a GraphQL schema as model, at an
  embedded object (`/ns/Person/ola/address/city` is 400): write the whole part instead.
- Writes of one document are done one at a time, in the order they come.

## Sending again

Every request is safe to send again when no answer came or the answer was 500: no request is refused for
having been sent before. With a `timestamp`, a write sent again writes the same version (the timeline does not
grow). Without one, each send adds a version at its own arrival time. That is why bulk loads and anything a
program may retry carry a `timestamp`. A 500 on a write does not say whether it was done; LDS may complete it
later.

## Bulk loading and exporting

Two scripts do this the same way every time. Use them rather than writing loops anew. Its scripts are in
`${CLAUDE_SKILL_DIR}/scripts/`: run them as `sh "${CLAUDE_SKILL_DIR}/scripts/<name>"`, from any folder. The references
name them `scripts/<name>`, and `<skill dir>` there is `${CLAUDE_SKILL_DIR}`.

- `sh "${CLAUDE_SKILL_DIR}/scripts/lds-load.sh" records.ndjson` writes records `{"entity", "id", "validFrom",
  "document"}` (or `"deleted": true`) each at its valid time, collects refusals in `records.failed.ndjson`, and is safe
  to run again. [references/bulk-load.md](references/bulk-load.md) has the steps with the user, the mapping decisions,
  converters from CSV and JSON, and speed.
- `sh "${CLAUDE_SKILL_DIR}/scripts/lds-export.sh" <Entity> [instant] [json|csv]` writes the documents of an entity type
  as of one instant (UTC, ending in `Z`), with their ids, as JSON or CSV. [references/export.md](references/export.md)
  has the output forms and limits.

Both take `LDS` (with the `http.prefix` when there is one) and `NS` from the environment, need `curl` and
`jq`, and say so when one is missing.

## What to tell the user plainly

- **LDS has no authentication or authorisation.** Anyone who reaches the port can read, change and delete
  everything. Do not load sensitive data into an LDS that others can reach (`lds:operate`).
- One LDS serves one namespace, and its model is read at start: a new field or entity type takes a model change
  and a restart (`lds:model`).
- The list of an entity type over REST has no ids and no pages; with a model of JSON Schema files there is no
  GraphQL API either, so ids come only from the documents themselves.
- An id is free text of at most 1024 bytes of UTF-8 with no `/` and no control character; percent-encode it in the
  address (`jq -rn --arg id "a+b" '$id|@uri'`). Field names with `.` come back as nested objects. With the memory
  provider everything is gone when LDS stops (`lds:start` for keeping data).
- Search (GraphQL only, needs Solr) finds documents by the words of their texts; not by numbers, dates or links.
- A body that is not JSON is answered 400 with the line and column where reading stopped; another content type
  415.
