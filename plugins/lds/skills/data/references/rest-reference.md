# The REST API of LDS 3.0.1: every address, parameter and answer

Every answer here was seen on `descoped/lds:3.0.1` with the example model (`Person`, `Company`). With the
user's own model the entity types and fields differ; the rules do not. In the examples `$LDS` is the address of
LDS (`http://localhost:9090` by default) and `$J` is `Content-Type: application/json`. Depth:
https://lds.descoped.io/docs/user/rest-api/

## Contents

- [Addresses](#addresses)
- [Rules of an address](#rules-of-an-address)
- [Whole documents](#whole-documents)
- [Time: timestamp and timeline](#time-timestamp-and-timeline)
- [Parts of a document](#parts-of-a-document)
- [Links](#links)
- [sync: when the answer comes](#sync-when-the-answer-comes)
- [Sources](#sources)
- [Every query parameter](#every-query-parameter)
- [Status codes](#status-codes)
- [Bodies and content types](#bodies-and-content-types)
- [Known gaps](#known-gaps)

## Addresses

| Address | What it is |
|---|---|
| `/{namespace}/{Entity}/{id}` | One document |
| `/{namespace}/{Entity}` | All documents of an entity type (read only) |
| `/{namespace}/{Entity}/{id}/{field}`, deeper `.../{field}/{field}` | A part of a document |
| `/{namespace}/{Entity}/{id}/{link field}/{TargetEntity}/{target id}` | One link of a document |
| `/{namespace}?schema`, `/{namespace}?schema=embed`, `/{namespace}/{Entity}?schema` | The model as JSON Schema 2020-12 |
| `/source/{source}` | The `sourceId` of the last write of a source |
| `/ping`, `/health/alive`, `/health/ready` | Health: see `lds:operate` |
| `/graphql`, `/graphiql` | GraphQL: see `lds:query` (only with a model that is a GraphQL schema) |

In its messages LDS says "managed resource" for a document, "embedded resource" for a part, "reference
resource" for a link and "managed-domain" for an entity type.

## Rules of an address

- `{namespace}` is the one namespace of this LDS: `ns` unless `namespace.default` says otherwise. Another one
  is answered 400 `Unsupported namespace: "other"`.
- `{Entity}` is spelled as the model spells it: `/ns/person/ola` is 400 `Not a managed resource name: "person"`.
- `{id}` is chosen by the writer; LDS makes no ids. It is free text of at most 1024 bytes of UTF-8, with no `/`
  and no control character. Each segment of the address is decoded once: `%C3%B8l` is `øl`, `%20` a space, `%25` a
  `%`, `%2B` a `+`, and a `+` stays a `+`. Percent-encode everything but letters, digits and `-._~`
  (`jq -rn --arg id "$ID" '$id|@uri'`).

  | Id in the address | Answer |
  |---|---|
  | empty, or only spaces (`%20`) | 400 `Managed resource id cannot be empty.` |
  | `a%2Fb` | 400 `An id cannot hold a '/': "a/b"` |
  | 1025 letters | 400 `An id is at most 1024 bytes in UTF-8` |
  | `x%zz` (a `%` not followed by two hex digits) | 400, no body |
  | `a+b`, `a%2Bb`, `100%25` | the ids `a+b`, `a+b`, `100%` |
- `{field}` must be a field of the model at that place: else 400 `Not a valid path element: "nosuch"`.
- A `/` at the end is ignored. `/ns` alone is 400 `Not a valid resource. ...`; `/` is 404.
- With `http.prefix` set (for example `/api`) every address starts with it, and the address without it is 404.
- Names of query parameters are case-sensitive; an unknown parameter is ignored.

## Whole documents

| Request | Answer |
|---|---|
| `PUT` or `POST /ns/{Entity}/{id}`, body the whole document | 201 `{"saga-execution-id":"<uuid>"}`, also when the document existed. Adds one version; replaces only a version at exactly that valid time |
| `GET /ns/{Entity}/{id}` | 200 the document as of now (or `timestamp`); 404 no body when there is none then, or it is deleted then |
| `GET /ns/{Entity}` | 200 an array of the documents as of now (or `timestamp`), in the order of their ids (by Unicode code point), **without the ids**; `[]` when none. No pages, no filter |
| `DELETE /ns/{Entity}/{id}` | 204 no body (202 with `{"saga-execution-id":...}` when `sync=false`). Writes a tombstone; also for an id never written |
| any other method (`PATCH`, `HEAD`, `OPTIONS`) | 400 `Unsupported managed resource method: PATCH` |

```sh
curl -i -X PUT "$LDS/ns/Person/ola" -H "$J" \
     -d '{
       "name": "Ola",
       "address": {"street": "Storgata 1", "city": "Oslo"},
       "employer": "/Company/acme"
     }'
```

```
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id":"01a20fe2-e905-7e09-bb15-5f76c62a5f07"}
```

A read gives the document back as written, with these differences: the fields may come in another order, a field
written as `null` is left out (at any depth; a `null` item of a list is kept), and a whole number beyond 64 bits or
a decimal with more digits than a double holds comes back changed. A field the
model does not have is stored and given back (unless the model forbids it, which only JSON Schema files can).

## Time: timestamp and timeline

`timestamp` (or `t` when `timestamp` is not given) sets the time of a request: the valid time of a write or a
delete, the instant of a read. Without it the time is the moment LDS receives the request.

| Value | |
|---|---|
| `2020-01-01T00:00:00Z`, `2020-01-01T00:00:00.123Z`, `2021-06-01T00:00Z` | read |
| `2021-06-01T00:00:00%2B01:00`, `2021-06-01T00:00:00%2B01` | read: an offset; `+` must be sent as `%2B` |
| `2021-06-01T00:00:00Z%5BEurope/Oslo%5D`, lower-case `t` and `z` | read; the zone does not change the time |
| `2021-06-01`, `2021-06-01T00:00:00` (no offset), an unencoded `+`, `+0100` without `:`, empty | 400 `The 'timestamp' query-parameter must follow the ISO-8601 standard. ...` |

Valid times are kept in UTC (to the millisecond with the memory provider): `2022-01-01T00:00:00%2B01:00` and
`2021-12-31T23:00:00Z` are one version.

```sh
curl -X PUT "$LDS/ns/Company/globex?timestamp=2020-01-01T00:00:00Z" -H "$J" -d '{"name": "Globex 2020"}'
curl -X PUT "$LDS/ns/Company/globex?timestamp=2022-01-01T00:00:00%2B01:00" -H "$J" -d '{"name": "Globex 2022"}'
curl -X PUT "$LDS/ns/Company/globex?timestamp=2030-06-01T12:00:00Z" -H "$J" -d '{"name": "Globex 2030"}'
```

| Read | Answer (asked in November 2026) |
|---|---|
| `GET /ns/Company/globex` | `{"name":"Globex 2022"}`: the 2030 version is not valid yet |
| `GET /ns/Company/globex?timestamp=2019-12-31T23:59:59Z` | 404: before the first version |
| `GET /ns/Company/globex?timestamp=2021-06-01T00:00:00Z` | `{"name":"Globex 2020"}` |
| `GET /ns/Company/globex?t=2031-01-01T00:00:00Z` | `{"name":"Globex 2030"}` |
| `GET /ns/Company?timestamp=2021-06-01T00:00:00Z` | `[{"name":"Globex 2020"}]` |

`GET /ns/{Entity}/{id}?timeline` gives every version, oldest first; 404 when there is none. Its value is not
read (`?timeline=false` gives it too), `timestamp` does not change it, and on a list it does nothing. No range,
no pages. After `DELETE /ns/Company/globex?timestamp=2025-01-01T00:00:00Z`:

```json
[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {"name": "Globex 2020"}
  },
  {
    "version": "2021-12-31T23:00Z[Etc/UTC]",
    "document": {"name": "Globex 2022"}
  },
  {
    "version": "2025-01-01T00:00Z[Etc/UTC]",
    "document": {}
  },
  {
    "version": "2030-06-01T12:00Z[Etc/UTC]",
    "document": {"name": "Globex 2030"}
  }
]
```

A tombstone is the version with `{}`. Between 2025 and 2030 the document is not there (404, left out of the
list); before and after it is. `version` can be sent back as `timestamp` with `[` `]` as `%5B` `%5D`:
`?timestamp=2020-01-01T00:00Z%5BEtc/UTC%5D`. A write at the time of a tombstone replaces it; a delete at the
time of a version replaces that version. No request removes a version for good.

## Parts of a document

A part is a field, addressed by its path: `/ns/Person/ola/address`. Each method first reads the document at the
time of the request; when there is none then, every method is answered 404 with no body.

| Request | Answer |
|---|---|
| `GET` | 200: an object or array as it is; a single value inside an array (`["Ola"]`); a missing part or `null` as `[null]` |
| `PUT` or `POST`, body the new value as JSON (`{...}`, `[...]`, `"Oslo"`) | 200 `{"saga-execution-id":...}`. The whole document with the new part is checked and written as a new version. The part is replaced, not merged. `null` removes the field (`PUT .../name` with `null` is 400 `[$: required property 'name' not found]`). An empty body is 400 `Payload was empty!` |
| `DELETE` | 200 `{"saga-execution-id":...}`. The field is removed and the document written as a new version. Not checked against the model |
| other methods | 400 `Unsupported embedded resource method: PATCH` |

- A path cannot go into the items of a list: `/ns/Company/big/about/languageText` is 400. Write the whole list.
- With a GraphQL schema as the model a path cannot go below a field of an embedded type:
  `/ns/Person/ola/address/city` is 400 `Not a valid path element: "city"`. With JSON Schema files it can.
- A path cannot go through a link: `/ns/Person/kari/employer/Company/acme/name` is 400
  `Resource path cannot navigate through REF property: "employer"`.

## Links

A link is the text `/{TargetEntity}/{id}` in a link field (one link, or a list of them). It has no namespace.
LDS never looks for the target: a link may point at a document that does not exist yet, or no longer does.

| Request to `/ns/Person/kari/employer/Company/acme` | Answer |
|---|---|
| `GET` | 200 no body when the document has the link at that time; 404 when not, or no document |
| `PUT` or `POST` (no body needed) | Link there already: 200 no body, nothing written. Else 200 `{"saga-execution-id":...}`: a list gets it at its end, a single link field gets it in place of the old one. 404 when there is no document (it is not made) |
| `DELETE` | Always 200. Removes the link (the first one in a list; an emptied list stays as `[]`; a single link field is removed). No body; `{"saga-execution-id":...}` with `sync=false` when there was a link to remove |
| a target entity the field may not link to | 400 `Reference resource does not support link to managed-domain "Person". Must be one of: [Company]` |
| an address that ends at the entity (`.../employer/Company`) | 400 `Reference resource path-element: "employer" must be part of pattern ending like: "/employer/<managed-domain>/<id>"` |
| other methods | 400 `Unsupported reference resource method: PATCH` |

Adding or removing a link at its address is not checked against the model. Deleting a document leaves the
links to it as they are.

## sync: when the answer comes

Every write is carried out by a saga: the transaction log, then the store, then (with search on) the index
queue, from which LDS sends it to Solr.

Every write, of a whole document, a part or a link, waits until it is done, unless it has `sync=false` (in any
letter case): then the answer comes when the saga is recorded, a read right after may not see the write yet, and
a failure later is not reported to the caller. Writes of one document are done one at a time, in the order they
come.

`searchable=true` (with search on) also waits until a search finds the write, at most 10 seconds, and then answers
as without it, with the header `LDS-Searchable: true`, or `false` when the 10 seconds ran out or too many writes
already wait for search (then it is answered when it is done). It makes the write wait even with `sync=false`.
Without search it waits for the write only, and has no header. Run with the full stack:

```console
$ curl -s -i -X PUT "$LDS/ns/Company/acme?searchable=true" -H "$J" \
    -d '{"name": "Acme", "about": [{"languageCode": "en", "languageText": "Makes anvils and rockets"}]}'
HTTP/1.1 201 Created
LDS-Searchable: true
Content-Type: application/json

{"saga-execution-id":"01a20fe3-4c8c-7ccd-97a1-95a8945ab573"}
```

With Solr stopped the same kind of write was answered `201` after 10 seconds with `LDS-Searchable: false`; it was
found when Solr was back.

A write whose saga fails while LDS waits is answered 500 with no body. Done steps stay done, and LDS runs the
saga again later, so the write may still happen after the 500. A write that waits 10 seconds for a free saga
is answered 500 and is not done. Sending the same request again is safe (see SKILL.md, "Sending again").

## Sources

`source` (name of the sending system) and `sourceId` (the id of the write there) are recorded in the
transaction log. `GET /source/{source}` answers `{"lastSourceId":"<sourceId>"}` of the last write received
(`{"lastSourceId":null}` when none came with a `sourceId`). With the image as it comes the transaction log is not
kept, and the answer is `409 Conflict`, `text/plain`: `No transaction log is kept
(txlog.rawdata.provider=discard), so the last source id of a source is not known`. With `txlog.rawdata.provider`
set to `memory` or `postgres` (the compose files of `lds:start`) and `txlog.split.sources=false` (the default) only
`/source/default` is answered. With `txlog.split.sources=true`
each source has its own log, and a `source` with a character other than letters, digits, `_`, `.` and `-` makes
the write fail with 500.

## Every query parameter

| Parameter | On | Meaning | Default |
|---|---|---|---|
| `timestamp` | documents, parts, links | Instant of a read; valid time of a write or delete | the time of the request |
| `t` | the same | Same as `timestamp`, used only without `timestamp` | |
| `timeline` | `GET` of one document | Every version | |
| `sync` | `PUT`, `POST`, `DELETE` | `false`: the answer does not wait for the write | waits |
| `searchable` | `PUT`, `POST`, `DELETE` | `true`: the answer also waits until search finds the write | |
| `source`, `sourceId` | `PUT`, `POST`, `DELETE` | Where the write comes from | |
| `schema` | `/ns`, `/ns/{Entity}` | The model as JSON Schema; `schema=embed` on `/ns` gives all schemas at once | |
| `saga` | `PUT`, `POST`, `DELETE` | Makes a step fail, for tests; only with `saga.commands.enabled=true` | |

## Status codes

| Status | When |
|---|---|
| 200 | A read; a write or removal of a part; a link added or there already; every link removal; health |
| 201 | A write of a whole document, new or not, waited for or not |
| 202 | A delete of a whole document with `sync=false` |
| 204 | A delete of a whole document that LDS waited for |
| 400 | A bad address, id, `timestamp`, method; an empty body or one that is not JSON; a missing id; an invalid document. `text/plain` with the reason (no body for a `%` that is no escape) |
| 404 | No document at that time (read, timeline, any method on a part, `GET`/`PUT`/`POST` of a link); a link not there; `/` |
| 409 | `/source/{source}` when no transaction log is kept |
| 415 | A body whose content type is not JSON; `text/plain` with the reason |
| 500 | A failed saga while waiting, a write that waited 10 seconds for a saga, and the gaps below. No body: the reason is in the log of LDS |
| 503 | LDS is stopping; `/health/ready` when a part a write needs does not answer |

## Bodies and content types

- Send `Content-Type: application/json`. Another type is 415: `curl -d` without `-H` sends a form type and gets
  `Unsupported Content-Type: application/x-www-form-urlencoded. Send the body as JSON, with Content-Type:
  application/json`. Bodies are read as UTF-8.
- An empty body, or one of only spaces, is 400 `Payload was empty!`. The body is exactly one JSON value: one that
  cannot be read, or has anything after it, is 400 with where reading stopped:
  `The body cannot be read at line 1, column 12: Unexpected end-of-input: expected close marker for Object. ...`.
  Nothing is written.
- Answers: `application/json; charset=utf-8` for documents, lists, timelines, parts, schemas and sources;
  `application/json` for `{"saga-execution-id":...}`; `text/plain` for a 400, 409 and 415. The `Accept` header is
  not read.

## Known gaps

These are the "Not finished" points of LDS 3.0.1 that a user of the REST API meets. Say them when they matter.

- One namespace per LDS.
- The list of an entity type has no ids and no pages; the REST API has no search and no filter by a field.
- A field name with a dot or ending in `[n]` does not come back as written: `{"a.b":1}` comes back as
  `{"a":{"b":1}}`.
- The name of an embedded type in an address (`/ns/Address`) gives `[]` for `GET` and 500 for `PUT`.
- Removing a part, and adding or removing a link at its address, are not checked against the model; a required
  field can be removed this way.
- With a GraphQL schema as model, a single embedded object (such as `address`) is not checked inside, nor are links
  inside items of a list of embedded objects.
- With `http.prefix`, the addresses in `?schema` lack the prefix, and `/source/...` does not find the last write.
