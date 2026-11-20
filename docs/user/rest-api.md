# The REST API

This document is the reference for the REST API of Linked Data Store (LDS): every address, method and query
parameter, the body of each request, the status codes and what each answer holds. It is for people who read and
write documents over HTTP. The ideas behind the API (document, version, link, saga) are explained in
[Concepts](concepts.md), and [Getting started](getting-started.md) shows how to start LDS.

## About the examples

Every request in this document was run against LDS `3.0.0-SNAPSHOT`, started on the JDK with its defaults:
everything in memory, and the example model. Where an example needs another start, the text says so.

The example model has two entities:

- `Person` with `name` (a text, required), `born` (a date-time), `address` (an object with `street` and `city`),
  `employer` (a link to a `Company`) and `friends` (a list of links to `Person`).
- `Company` with `name` (a text, required), `founded` (a date-time) and `about` (a list of objects with
  `languageCode` and `languageText`).

In the examples `$LDS` is the address of LDS and `$J` is the header that says the body is JSON. With the default
port:

```sh
LDS=http://localhost:9090
J='Content-Type: application/json'
```

The LDS that the examples ran on had another port (`http.port`); nothing else differs.

An answer is shown as LDS sent it: the status line, the headers, an empty line and the body. The headers
`Connection`, `Content-Length` and `Date` are left out. LDS sends a JSON answer as one line; here it is
shown formatted, with the fields in the order LDS sent them. The ids of sagas and the times of versions differ
from run to run. The requests were sent on 2026-11-20, which matters where a request names no time.

LDS has no authentication. It answers every caller that reaches it, over plain HTTP.

## Addresses

| Address | What it is |
|---|---|
| `/{namespace}/{Entity}/{id}` | One document. See [Whole documents](#whole-documents). |
| `/{namespace}/{Entity}` | All documents of an entity. See [Read all documents of an entity](#read-all-documents-of-an-entity). |
| `/{namespace}/{Entity}/{id}/{field}`, and deeper: `.../{field}/{field}` | A part of a document. See [Parts of a document](#parts-of-a-document). |
| `/{namespace}/{Entity}/{id}/{link field}/{TargetEntity}/{target id}` | One link of a document. See [Links](#links). |
| `/{namespace}?schema` and `/{namespace}/{Entity}?schema` | The model, as JSON Schema. See [The model over HTTP](#the-model-over-http). |
| `/source/{source}` | The last write of a source. See [Sources and the transaction log](#sources-and-the-transaction-log). |
| `/ping`, `/health/ready`, `/health/alive` | Health. See [Health](#health). |
| `/graphql`, `/graphiql` | GraphQL. See [the GraphQL API](graphql-api.md). |

The texts that LDS sends with a status 400 use other words for the three kinds of resource: a "managed resource"
is a whole document, an "embedded resource" is a part of a document, a "reference resource" is a link, and a
"managed-domain" is an entity.

The rules for an address:

- `{namespace}` is the one namespace of this LDS. It is set with the key `namespace.default`, and is `ns`
  unless the configuration says otherwise. An address with another first segment, that is not one of the other
  addresses of the table above, is answered 400.
- `{Entity}` is the name of an entity of the model, with the same upper and lower case letters.
- `{id}` is the id of the document. The caller chooses it; LDS does not make ids. It is free text, and must
  not be empty or only spaces. See [Ids and encoding](#ids-and-encoding).
- `{field}` is a field that the model has at that place, with the same upper and lower case letters. LDS checks
  the address against the model before it looks for the document.
- A `/` at the end of an address is ignored: `/ns/Person/ola/` is `/ns/Person/ola`.
- The names of query parameters are compared letter by letter, upper and lower case too. A parameter that LDS
  does not know is ignored.

Addresses that are refused:

```console
$ curl -i "$LDS/other/Person/ola"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Unsupported namespace: "other"

$ curl -i "$LDS/ns/person/ola"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Not a managed resource name: "person"

$ curl -i "$LDS/ns/Person/%20"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Managed resource id cannot be empty.

$ curl -i "$LDS/ns/Person/ola/nosuch"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Not a valid path element: "nosuch"

$ curl -i "$LDS/ns"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Not a valid resource. The resource path must contain at least namespace, name, and id of managed resource

$ curl -i "$LDS/"
HTTP/1.1 404 Not Found
```

The text of the fifth answer names an id, but an address with a namespace and an entity is enough.

Not finished: the REST API answers for one namespace only.

Not finished: the name of an embedded type of the model, a type that is not an entity, is taken as an entity in
an address. With the example model, where `Address` is the type of the field `address`, `GET /ns/Address` is
answered 200 with `[]`. A `PUT` to `/ns/Address/a1` and `GET /ns/Address?schema` are answered 500 with no body.

### Ids and encoding

Every segment of an address is decoded as a URL. An id may hold spaces and letters outside ASCII when they are
percent-encoded as UTF-8 (`%C3%B8l` is the id `øl`).

```console
$ curl -i -X PUT "$LDS/ns/Person/id%20with%20space" -H "$J" -d '{"name": "spaced"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-fef1-702d-8a6b-1bb879bd6011"}

$ curl -i "$LDS/ns/Person/id+with+space"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "spaced"}
```

Not finished: a segment is decoded twice. This changes some ids:

- A `+` is read as a space, also when it is written `%2B`. An id with a `+` in it cannot be used.
- `%2541` is read as `A` (first `%41`, then `A`).
- An id with a `%` in it cannot be used. `GET /ns/Person/100%25` is answered 500 with no body.
- `%2F` gives an id with a `/` in it.
- A `%` that is not followed by two hexadecimal digits (`/ns/Person/x%zz`) is answered 400 with no body.

Not finished: with the memory provider an id cannot be much longer than 200 letters. Ran: a `PUT` with an id of
200 letters was answered 201. A `PUT` with an id of 240 letters was answered 500 with no body, and left a part of
the document behind: a `GET` of that id then gave one of the two fields that were sent, without the `name` that
the model requires.

### A prefix and another namespace

Two keys of the configuration change every address (see [Configuration](configuration.md)):

- `namespace.default` is the namespace.
- `http.prefix` is put in front of every address of LDS, the health addresses too. An address without the prefix
  is answered 404.

These requests ran on an LDS started with `namespace.default=data` and `http.prefix=/api`:

```console
$ curl -i -X PUT "$LDS/api/data/Company/acme?sourceId=d1" -H "$J" -d '{"name": "Acme"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d3-055b-7dca-ad21-63c944e55ab0"}

$ curl -i "$LDS/api/data/Company/acme"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Acme"}

$ curl -i "$LDS/data/Company/acme"
HTTP/1.1 404 Not Found

$ curl -i "$LDS/api/ns/Company/acme"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Unsupported namespace: "ns"

$ curl -i "$LDS/api/ping"
HTTP/1.1 200 OK
```

Not finished: two addresses do not follow the prefix. The addresses in the answer to `?schema` are written
without it (`GET /api/data?schema` answers `["/data/Company?schema","/data/Person?schema"]`). And
`/source/{source}` does not find the last write: in the same LDS, with the transaction log kept in memory,
`GET /api/source/default` answered `{"lastSourceId":null}` after the write above.

## Whole documents

### Write a document

`PUT` or `POST` to `/{namespace}/{Entity}/{id}`. The two methods do the same. The body is the whole document, a
JSON object.

| Query parameter | Meaning |
|---|---|
| `timestamp` | The time the new version is valid from. Without it: the time LDS receives the request. See [Versions and time](#versions-and-time). |
| `sync` | `sync=false`: LDS answers before the write is done. See [When LDS answers a write: sync](#when-lds-answers-a-write-sync). |
| `source`, `sourceId` | Where the write comes from. See [Sources and the transaction log](#sources-and-the-transaction-log). |

The write makes one version of the document, valid from the time of the request. It does not change the other
versions of the document. A version that exists at exactly that time is replaced.

Before it writes, LDS checks the document against the model (see [What LDS checks before it
writes](#what-lds-checks-before-it-writes)). It does not look for the documents that the links point at: a document may
link to a document that does not exist.

The answer is `201 Created` with `Content-Type: application/json` and the body `{"saga-execution-id":"<id>"}`.
The status is 201 also when the document existed before, and also with `sync=false`. `saga-execution-id` is the
id of the saga that carries out this write. It is a UUID of version 7, and each one is greater than those LDS
gave before it. No address of the REST API takes it.

```console
$ curl -i -X PUT "$LDS/ns/Company/acme" -H "$J" -d '{"name": "Acme"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f304-7991-96af-673510969d06"}

$ curl -i -X PUT "$LDS/ns/Person/ola" -H "$J" \
    -d '
{
  "name": "Ola",
  "address": {"street": "Storgata 1", "city": "Oslo"},
  "employer": "/Company/acme"
}
'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f33b-7585-b329-5bdbb423f121"}

$ curl -i -X POST "$LDS/ns/Person/kari" -H "$J" -d '{"name": "Kari"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f372-7cac-90ad-6fa3e7a21fc0"}
```

What is refused, in the order LDS checks it:

| Case | Answer |
|---|---|
| The body has no characters | 400, `text/plain`, `Payload was empty!` |
| The address has no id | 400, `text/plain`, `Id was empty!` |
| The content type is not JSON, or the body cannot be read as JSON | 500, no body. See [Bodies and content types](#bodies-and-content-types). |
| The document is not valid | 400, `text/plain`, `Schema validation error: ...` |
| The saga of the write fails | 500, no body. See [When a write fails](#when-a-write-fails). |

```console
$ curl -i -X PUT "$LDS/ns/Person/x1" -H "$J" -d ''
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Payload was empty!

$ curl -i -X PUT "$LDS/ns/Person" -H "$J" -d '{"name": "A"}'
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Id was empty!

$ curl -i -X PUT "$LDS/ns/Person/x1" -H "$J" -d '{"name": 7}'
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: [$.name: integer found, string expected]
```

### Read a document

`GET` of `/{namespace}/{Entity}/{id}` gives the document as it is at the time of the request: now, or the time
in `timestamp` (see [Versions and time](#versions-and-time)).

- 200, `Content-Type: application/json; charset=utf-8`, the document.
- 404 with no body, when the document has no version at that time, or is deleted at that time.

```console
$ curl -i "$LDS/ns/Person/ola"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "employer": "/Company/acme",
  "name": "Ola",
  "address": {"city": "Oslo", "street": "Storgata 1"}
}

$ curl -i "$LDS/ns/Person/nobody"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8
```

What comes back is the document that was written, with these differences (seen with the memory provider):

- The fields may come in another order than they were sent, as in the answer above.
- A whole number that does not fit in 64 bits, and a number with more digits than a double holds, come back
  changed: `12345678901234567890` came back as `1.2345678901234567E19`, and `1.234567890123456789012345` as
  `1.2345678901234567`.

With the example model a field that the model does not have is accepted, stored and comes back (ran: a
`Person` with a field `nickname`). Such a field cannot be addressed as a part of the document. Whether a model
accepts such fields is in [the model](model.md).

Not finished: a field whose name has a dot in it, or ends in a number in `[` and `]`, does not come back as it
was written. LDS takes the dot for a step into an object, and the brackets for a place in an array. Ran with the
memory provider: a `Person` written as `{"name":"Dot","a.b":1,"x[3]":2}` came back as
`{"name":"Dot","a":{"b":1},"x":[2]}`. Do not use such names for fields.

### Read all documents of an entity

`GET` of `/{namespace}/{Entity}` gives 200 and an array of the documents of the entity as they are at the time
of the request, in the order of their ids. The ids are compared as text, and upper case letters come before
lower case letters. A document that is deleted at that time is left out. The array is `[]` when there is none.

```console
$ curl -i "$LDS/ns/Person"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {"name": "spaced"},
  {"name": "Kari"},
  {
    "employer": "/Company/acme",
    "name": "Ola",
    "address": {"city": "Oslo", "street": "Storgata 1"}
  }
]
```

The first document of the answer is the one with the id `id with space`, written in [Ids and
encoding](#ids-and-encoding).

Not finished: the answer holds the documents and not their ids, and it is always the whole list. There are no
parameters for a range or for pages. [The GraphQL API](graphql-api.md) gives ids and pages.

Not finished: with the memory provider a document whose id begins with `~` or with a letter outside ASCII is
left out of the list. Ran: `Person/øl` and `Company/~tilde` were written and were read by their ids, and were
not in the lists of `Person` and `Company`.

The REST API has no search and cannot find a document by the value of a field.

### Delete a document

`DELETE` of `/{namespace}/{Entity}/{id}`. No body. The query parameters are those of a write: `timestamp`,
`sync`, `source` and `sourceId`.

A delete does not remove the document. It makes a version, valid from the time of the request, in which the
document is deleted. The versions before it and after it stay as they are. See
[What a delete means for earlier and later versions](#what-a-delete-means-for-earlier-and-later-versions).

- 204 with no body, when LDS waited for the write (the default).
- 202, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`, with `sync=false`.
- 400 `Id was empty!`, when the address has no id.
- 500 with no body, when the saga of the write fails.

A delete of a document that does not exist is answered in the same way, and makes a deleted version too.

LDS does not look for links when it deletes. A document that other documents link to can be deleted, and the
links to it stay in those documents.

### Other methods

Any other method is answered 400. This holds for `PATCH`, `HEAD` and `OPTIONS` too. (An `OPTIONS` request that a
browser sends before a request from another origin is answered in another way, see [CORS](#cors).)

```console
$ curl -i -X PATCH "$LDS/ns/Person/ola" -H "$J" -d '{"name": "x"}'
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Unsupported managed resource method: PATCH
```

## Versions and time

Every version of a document has a time from which it is valid. A request to a document, to a part of a document
or to a link has a time too:

- For a read it is the time the document is read at. The answer is made from the newest version whose time is
  that time or earlier.
- For a write (`PUT`, `POST`, `DELETE`) it is the time the new version is valid from.

The time may be in the past or in the future.

### The time of a request: timestamp

The query parameter `timestamp` gives the time. `t` is a second name for it, used only when the request has no
`timestamp`. Without both, the time is the moment LDS receives the request.

The value is a date and a time with an offset from UTC, in the form of ISO 8601. LDS turns it into UTC: the
versions `2022-01-01T00:00:00+01:00` and `2021-12-31T23:00:00Z` are the same version.

| Value | Read as |
|---|---|
| `2020-01-01T00:00:00Z` | a time in UTC |
| `2020-01-01T00:00:00.123Z` | with a fraction of a second |
| `2021-06-01T00:00Z` | the seconds may be left out |
| `2021-06-01T00:00:00%2B01:00` | an offset. The `+` must be written `%2B`: LDS reads a `+` in a query as a space |
| `2021-06-01T00:00:00%2B01` | an offset in hours |
| `2021-06-01T00:00:00Z%5BEurope/Oslo%5D` | a zone in `[` and `]`, written `%5B` and `%5D`, after the offset. The offset gives the time; the zone does not change it |
| `2021-06-01t00:00:00z` | `T` and `Z` may be in lower case |
| `2021-06-01`, `2021-06-01T00:00:00`, `2021-06-01T00Z` | refused: the date, the hour, the minute and an offset (or `Z`) are needed |
| `2021-06-01T00:00:00+01:00`, `2021-06-01T00:00:00%2B0100` | refused: a `+` that is not encoded, and an offset without `:` |
| an empty value, or `timestamp` with no `=` | refused |

A value that is refused is answered 400, with the same text also when the parameter was `t`:

```console
$ curl -i "$LDS/ns/Company/globex?timestamp=2021-06-01T00:00:00+01:00"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

The 'timestamp' query-parameter must follow the ISO-8601 standard. Example of a valid formatted timestamp is
'2018-12-06T11:05:31.000+01:00'
```

The example in that text has a `+`, which must be sent as `%2B` too.

When `timestamp` is given more than once, the last one is used. The name is in lower case: `TIMESTAMP` is a
parameter LDS does not know, and is ignored.

With the memory provider the time of a version is kept to the millisecond: a version written at
`2020-01-01T00:00:00.123456789Z` is the version `2020-01-01T00:00:00.123Z`.

### Write a version at a time

Three versions of one document, the second with an offset:

```console
$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2020-01-01T00:00:00Z" -H "$J" -d '{"name": "Globex 2020"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f3c2-7c66-8c78-fb463b7d0a07"}

$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2022-01-01T00:00:00%2B01:00" -H "$J" \
    -d '{"name": "Globex 2022"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f3cf-7e0c-b7fa-fcecc2df6325"}

$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2030-06-01T12:00:00Z" -H "$J" -d '{"name": "Globex 2030"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f3dc-7fba-8fdd-1356f78b32bc"}
```

Versions can be written in any order of time, see [Write between versions, and replace a
version](#write-between-versions-and-replace-a-version).

### Read a document as it was

```console
$ curl -i "$LDS/ns/Company/globex"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Globex 2022"}

$ curl -i "$LDS/ns/Company/globex?timestamp=2019-12-31T23:59:59Z"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

$ curl -i "$LDS/ns/Company/globex?timestamp=2021-06-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Globex 2020"}

$ curl -i "$LDS/ns/Company/globex?t=2031-01-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Globex 2030"}
```

The first read names no time, so the document is read at the time of the request (2026). The version of 2030 is
in the future, and a read without a time does not give it until its time has come. Before the first version the
answer is 404.

The list of an entity is read at a time in the same way:

```console
$ curl -i "$LDS/ns/Company?timestamp=2021-06-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {"name": "Globex 2020"}
]

$ curl -i "$LDS/ns/Company?timestamp=2019-01-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[]
```

(`Company/acme` is not in the first list: it was written in 2026, without a `timestamp`.)

### Read every version: timeline

`GET` of `/{namespace}/{Entity}/{id}?timeline` gives every version of the document, the oldest first.

- 200, an array. Each element is `{"version":"<time>","document":<the document>}`.
- 404 with no body, when the document has no version.

`timeline` is switched on by being there. Its value is not read: `?timeline=false` gives the timeline too.
`timestamp` has no effect on it. On the list of an entity (`/ns/Company?timeline`) it has no effect.

```console
$ curl -i "$LDS/ns/Company/globex?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

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
    "version": "2030-06-01T12:00Z[Etc/UTC]",
    "document": {"name": "Globex 2030"}
  }
]
```

`version` is the time in UTC, ending in `[Etc/UTC]`. The seconds are left out when they are zero
(`2020-01-01T00:00Z[Etc/UTC]`, `2026-11-20T16:40:45.499Z[Etc/UTC]`). The text can be given back as `timestamp`
when `[` and `]` are written `%5B` and `%5D`: `?timestamp=2020-01-01T00:00Z%5BEtc/UTC%5D`.

Not finished: the answer is always the whole list. There are no parameters for a range of time or for pages.

### Write between versions, and replace a version

A write at a time before versions that exist makes a version at that time and leaves the later versions as they
are. A write at exactly the time of a version that exists replaces that version; the same moment written with
another offset is the same time. The first write below replaces the version of 2020, the second adds a version
between 2020 and 2022.

```console
$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2020-01-01T01:00:00%2B01:00" -H "$J" \
    -d '{"name": "Globex 2020, written again"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f44a-748f-8937-74adc6915e01"}

$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2021-01-01T00:00:00Z" -H "$J" -d '{"name": "Globex 2021"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f459-748b-ab5b-82a01478e376"}

$ curl -i "$LDS/ns/Company/globex?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {"name": "Globex 2020, written again"}
  },
  {
    "version": "2021-01-01T00:00Z[Etc/UTC]",
    "document": {"name": "Globex 2021"}
  },
  {
    "version": "2021-12-31T23:00Z[Etc/UTC]",
    "document": {"name": "Globex 2022"}
  },
  {
    "version": "2030-06-01T12:00Z[Etc/UTC]",
    "document": {"name": "Globex 2030"}
  }
]
```

### What a delete means for earlier and later versions

A delete makes a version in which the document is deleted. From that time until the next version the document
is not there: a read is answered 404, and the document is not in the list of its entity. Before that time, and
from the next version on, the document is read as before.

```console
$ curl -i -X DELETE "$LDS/ns/Company/globex?timestamp=2025-01-01T00:00:00Z"
HTTP/1.1 204 No Content

$ curl -i "$LDS/ns/Company/globex?timestamp=2024-01-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Globex 2022"}

$ curl -i "$LDS/ns/Company/globex"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

$ curl -i "$LDS/ns/Company/globex?timestamp=2031-01-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Globex 2030"}

$ curl -i "$LDS/ns/Company"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {"name": "Acme"}
]
```

In the timeline the deleted version is there, with an empty document (seen with the memory provider):

```console
$ curl -i "$LDS/ns/Company/globex?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {"name": "Globex 2020, written again"}
  },
  {
    "version": "2021-01-01T00:00Z[Etc/UTC]",
    "document": {"name": "Globex 2021"}
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

A write at the time of a deleted version replaces it, and a delete at the time of a version replaces that
version.

A delete of a document that was never written, here with `sync=false`:

```console
$ curl -i -X DELETE "$LDS/ns/Company/initech?sync=false"
HTTP/1.1 202 Accepted
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f4bb-7c93-b20d-e9c0bb48f899"}

$ curl -i "$LDS/ns/Company/initech?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2026-11-20T16:40:45.499Z[Etc/UTC]",
    "document": {}
  }
]
```

Over HTTP a version cannot be removed for good.

## Parts of a document

A part of a document is a field of it, addressed by its path: `/{namespace}/{Entity}/{id}/{field}`. A path that
ends at a link field (`/ns/Person/ola/employer`) is a part too: its value is the link, or the list of links.

Every method first reads the document at the time of the request. When there is no document at that time, or it
is deleted, the answer is 404 with no body, whatever the method and the body.

### Read a part

`GET` gives 200 with `Content-Type: application/json; charset=utf-8`. The body has one of three forms:

- The part is an object or an array: the part as it is.
- The part is a text, a number, `true` or `false`: the value inside an array, `["Ola"]`.
- The document does not have the part: `[null]`.

```console
$ curl -i "$LDS/ns/Person/ola/address"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "city": "Oslo",
  "street": "Storgata 1"
}

$ curl -i "$LDS/ns/Person/ola/name"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["Ola"]

$ curl -i "$LDS/ns/Person/ola/employer"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["/Company/acme"]

$ curl -i "$LDS/ns/Person/ola/friends"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[null]

$ curl -i "$LDS/ns/Person/nobody/address"
HTTP/1.1 404 Not Found
```

Not finished: the form of the answer depends on the value, as listed above. A part that is not there and a part
that is `null` give the same answer, `[null]`.

### Write a part

`PUT` or `POST`; the two do the same. The body is the new value of the part, as JSON: an object, an array, or a
single value such as `"Oslo"` or `null`. The query parameters are those of a write of a whole document.

LDS reads the document at the time of the request, puts the new value in place of the part, checks the whole
document against the model, and writes the whole document as a new version, valid from the time of the request.

- The part is replaced as a whole. It is not merged with what was there.
- Objects on the way to the part that the document does not have are made.
- 200, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`.
- 400 `Schema validation error: ...`, when the document with the new part is not valid. Nothing is written.
- 404 with no body, when there is no document at that time.
- 500 with no body, when the body cannot be read as JSON, or the saga of the write fails.

Without `sync=true` LDS answers before the write is done, see [When LDS answers a write:
sync](#when-lds-answers-a-write-sync).

```console
$ curl -i -X PUT "$LDS/ns/Person/ola/address?sync=true" -H "$J" \
    -d '
{
  "street": "Karl Johans gate 2",
  "city": "Oslo"
}
'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f520-7589-8b10-a4255f5d800f"}

$ curl -i -X PUT "$LDS/ns/Person/ola/born?sync=true" -H "$J" -d '"1990-05-17T00:00:00Z"'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f52f-7810-aa13-dca6b7798932"}

$ curl -i "$LDS/ns/Person/ola"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "born": "1990-05-17T00:00:00Z",
  "employer": "/Company/acme",
  "name": "Ola",
  "address": {"city": "Oslo", "street": "Karl Johans gate 2"}
}

$ curl -i -X PUT "$LDS/ns/Person/ola/born?sync=true" -H "$J" -d '"not a date"'
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: [$.born: does not match the date-time pattern must be a valid RFC 3339 date-time]

$ curl -i -X PUT "$LDS/ns/Person/ola/address?sync=true&timestamp=2019-01-01T00:00:00Z" -H "$J" \
    -d '
{
  "street": "Too early",
  "city": "Oslo"
}
'
HTTP/1.1 404 Not Found
```

The last request is answered 404 because `Person/ola` has no version in 2019.

Not finished: an empty body is not refused with `Payload was empty!` as it is for a whole document. Ran with the
example model: an empty body to `name` is answered 400
`Schema validation error: [$.name: unknown found, string expected]`. An empty body to `address` is answered 500
with `sync=true` and 200 without it. Neither wrote anything at first. Less than a minute later LDS had written a
version for each, in which `address` is `null`. Always send a body.

### Remove a part

`DELETE` removes the field and writes the whole document as a new version. No body.

- 200, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`.
- 404 with no body, when there is no document at that time.

```console
$ curl -i -X DELETE "$LDS/ns/Person/ola/born?sync=true"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f562-786e-a63a-d8141a4a875d"}

$ curl -i "$LDS/ns/Person/ola/born"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[null]
```

Not finished: the document is not checked against the model after a part is removed, so a field that the model
requires can be removed. Ran: after `DELETE /ns/Person/req/name?sync=true` the document was `{}`, and the next
`PUT` of a part of it was refused with `Schema validation error: [$: required property 'name' not found]`. A new
version is written also when the document did not have the field.

Any other method is answered 400 `Unsupported embedded resource method: <METHOD>`.

### How deep a path can go

Not finished: a path cannot go into the elements of a list. `about` of a `Company` is a list of objects, and
`GET /ns/Company/big/about/languageText` is answered 400 `Not a valid path element: "languageText"`. The list is
read and written as a whole (`/ns/Company/big/about`).

A path goes below a field only when the model describes the fields below it. With a model that is a GraphQL
schema, a path cannot go below a field such as `address: Address` in the example model, whose type is an
embedded type (see [the model](model.md)):

```console
$ curl -i "$LDS/ns/Person/ola/address/city"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Not a valid path element: "city"
```

With a model of JSON Schema files, deeper paths work. The requests below ran on an LDS with such a model: a
`Company` has an object `address` with `street` and `city` (`city` is required), and an object `support` with
`contacts`, a list of links to `Person`.

```console
$ curl -i -X PUT "$LDS/ns/Company/acme" -H "$J" \
    -d '
{
  "name": "Acme",
  "address": {"street": "Storgata 1", "city": "Oslo"}
}
'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d3-53de-7b89-9934-4176969d37c6"}

$ curl -i "$LDS/ns/Company/acme/address/city"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["Oslo"]

$ curl -i -X PUT "$LDS/ns/Company/acme/address/city?sync=true" -H "$J" -d '"Bergen"'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d3-543b-7872-a575-ef2a9230698c"}

$ curl -i -X PUT "$LDS/ns/Company/acme/address/city?sync=true" -H "$J" -d 7
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: [$.address.city: integer found, string expected]

$ curl -i -X PUT "$LDS/ns/Company/acme/support/contacts/Person/kari?sync=true"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d3-5468-7051-8f6b-56d94e036e65"}

$ curl -i "$LDS/ns/Company/acme/support"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "contacts": ["/Person/kari"]
}
```

The fifth request writes a link whose field is two levels down, see [Links](#links). The document had no
`support` before it: LDS made the object on the way to the link.

## Links

In a document a link is the text `/{TargetEntity}/{target id}`, in a link field: the field holds one link, or
a list of links. A link can be written as a part of the document, or with the whole document. It also has an
address of its own:

`/{namespace}/{Entity}/{id}/{link field}/{TargetEntity}/{target id}`

`{TargetEntity}` must be an entity that the field may link to. The link field may be deeper in the document:
`.../{field}/{link field}/{TargetEntity}/{target id}`. Nothing may follow the target id.

LDS never looks for the target. A link may point at a document that does not exist, and the address of a link
says only whether the link is in the document.

### Ask for a link

`GET`:

- 200 with no body, when the document has the link at the time of the request.
- 404 with no body, when it does not, or there is no document at that time.

### Add a link

`PUT` or `POST`; the two do the same. A body is not needed, and is not read. The query parameters are those of
a write of a whole document.

- The document has the link already: 200 with no body. Nothing is written.
- Else the link is added, and the whole document is written as a new version. A list of links gets the link at
  its end. A field that holds one link gets the link as its value, in place of the link it had. The answer is
  200, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`.
- 404 with no body, when there is no document at the time of the request. The document is not made.

```console
$ curl -i "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 404 Not Found

$ curl -i -X PUT "$LDS/ns/Person/kari/employer/Company/acme?sync=true"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f5aa-7ac4-9622-98b5fbb8d32e"}

$ curl -i "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 200 OK

$ curl -i -X PUT "$LDS/ns/Person/kari/employer/Company/acme?sync=true"
HTTP/1.1 200 OK

$ curl -i -X PUT "$LDS/ns/Person/kari/friends/Person/ola?sync=true"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f5d0-754b-841f-6c851f991959"}

$ curl -i -X POST "$LDS/ns/Person/kari/friends/Person/per?sync=true"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f5dd-797c-90c6-3a605354c831"}

$ curl -i "$LDS/ns/Person/kari"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "employer": "/Company/acme",
  "name": "Kari",
  "friends": ["/Person/ola", "/Person/per"]
}
```

There is no document `Person/per`; the link to it is written all the same. A second link in `employer` takes
the place of the first:

```console
$ curl -i -X PUT "$LDS/ns/Person/kari/employer/Company/globex?sync=true"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f5f6-74a3-b929-94762185c810"}

$ curl -i "$LDS/ns/Person/kari/employer"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["/Company/globex"]
```

### Remove a link

`DELETE` is answered 200 in every case.

- There is no document at the time of the request, or the document does not have the link: nothing is written,
  no body.
- Else the link is removed, and the whole document is written as a new version. A list loses the link (the
  first one, when the list has the same link twice) and stays in the document, also when it becomes empty. A
  field that holds one link is removed.
- The body is empty when LDS waited for the write (`sync=true`). Else it is `{"saga-execution-id":"<id>"}`.

```console
$ curl -i -X DELETE "$LDS/ns/Person/kari/friends/Person/per?sync=true"
HTTP/1.1 200 OK

$ curl -i -X DELETE "$LDS/ns/Person/kari/friends/Person/per?sync=true"
HTTP/1.1 200 OK

$ curl -i -X DELETE "$LDS/ns/Person/kari/friends/Person/ola"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-f628-7020-a775-ae3103d07e02"}

$ curl -i "$LDS/ns/Person/kari"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "employer": "/Company/globex",
  "friends": [],
  "name": "Kari"
}
```

The last read was sent one second after the delete before it, which did not wait for the write.

Not finished: the document is not checked against the model when a link is added or removed.

### What is refused

```console
$ curl -i "$LDS/ns/Person/kari/employer/Person/ola"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Reference resource does not support link to managed-domain "Person". Must be one of: [Company]

$ curl -i "$LDS/ns/Person/kari/employer/Company/acme/name"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Resource path cannot navigate through REF property: "employer"

$ curl -i -X PUT "$LDS/ns/Person/nobody/employer/Company/acme?sync=true"
HTTP/1.1 404 Not Found

$ curl -i -X PATCH "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Unsupported reference resource method: PATCH
```

An address that ends at the target entity (`/ns/Person/kari/employer/Company`) is answered 400 with
`Reference resource path-element: "employer" must be part of pattern ending like: "/employer/<managed-domain>/<id>"`.

### The target of a link is deleted

A delete of a document does not change the documents that link to it:

```console
$ curl -i -X DELETE "$LDS/ns/Company/acme"
HTTP/1.1 204 No Content

$ curl -i "$LDS/ns/Company/acme"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

$ curl -i "$LDS/ns/Person/ola/employer"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["/Company/acme"]
```

### A part or a link at a time

A write to a part or to a link with a `timestamp` reads the document as it is at that time, changes it, and
writes it as the version of that time. Later versions are not changed: the change is in the new version only.

`Person/tm` is written at 2020, and its `address` is written at 2022. A link written at 2021 is in the version
of 2021 and not in the version of 2022. A write at 2019 is answered 404, because there is no document then.

```console
$ curl -i -X PUT "$LDS/ns/Person/tm?timestamp=2020-01-01T00:00:00Z" -H "$J" \
    -d '
{
  "name": "T",
  "address": {"street": "Old", "city": "Oslo"}
}
'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d0-051d-7ed0-b7b7-6c2e91d577e7"}

$ curl -i -X PUT "$LDS/ns/Person/tm/address?sync=true&timestamp=2022-01-01T00:00:00Z" -H "$J" \
    -d '
{
  "street": "New",
  "city": "Oslo"
}
'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d0-052d-71be-b99a-16ddd2a248d1"}

$ curl -i -X PUT "$LDS/ns/Person/tm/address?sync=true&timestamp=2019-01-01T00:00:00Z" -H "$J" \
    -d '
{
  "street": "Too early",
  "city": "Oslo"
}
'
HTTP/1.1 404 Not Found

$ curl -i -X PUT "$LDS/ns/Person/tm/employer/Company/acme?sync=true&timestamp=2021-01-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d0-054a-776c-a799-dd0e35a80ba0"}

$ curl -i "$LDS/ns/Person/tm?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {
      "name": "T",
      "address": {"city": "Oslo", "street": "Old"}
    }
  },
  {
    "version": "2021-01-01T00:00Z[Etc/UTC]",
    "document": {
      "employer": "/Company/acme",
      "name": "T",
      "address": {"city": "Oslo", "street": "Old"}
    }
  },
  {
    "version": "2022-01-01T00:00Z[Etc/UTC]",
    "document": {
      "name": "T",
      "address": {"city": "Oslo", "street": "New"}
    }
  }
]

$ curl -i "$LDS/ns/Person/tm/employer/Company/acme?timestamp=2021-06-01T00:00:00Z"
HTTP/1.1 200 OK

$ curl -i "$LDS/ns/Person/tm/employer/Company/acme"
HTTP/1.1 404 Not Found
```

## When LDS answers a write: sync

Every write (`PUT`, `POST` and `DELETE` of a document, of a part or of a link) is carried out by a saga: LDS
records the write in the transaction log, then writes the document with the persistence provider and, when
search is on, updates the search index. [Concepts](concepts.md) says more about sagas.

A write to a part or to a link is a write of the whole document: LDS reads the document, changes it, and writes
it as a new version.

The query parameter `sync` says whether LDS waits for the saga before it answers:

- LDS waits: the answer comes when the saga is done. A read that follows sees the write.
- LDS does not wait: the answer comes when LDS has noted the write in its saga log, the list of the writes it
  has to carry out. The document is written shortly after. A read that follows may not see the write yet. If
  the saga fails after the answer, the caller is not told.

The default is not the same for the three kinds of resource:

| Write to | `sync` not given | `sync=true` | `sync=false` | `sync` with no value, or with another value |
|---|---|---|---|---|
| a whole document | waits | waits | does not wait | waits |
| a part of a document | does not wait | waits | does not wait | does not wait |
| a link | does not wait | waits | does not wait | does not wait |

`true` and `false` are read without regard to upper and lower case.

The status code does not say whether LDS waited, but for the delete of a whole document (204 or 202). The body
of the delete of a link does (see [Remove a link](#remove-a-link)).

A write to a part without `sync`, and a read one second later:

```console
$ curl -i -X PUT "$LDS/ns/Person/ola/address" -H "$J" -d '
{
  "street": "Torggata 5",
  "city": "Oslo"
}
'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-fa9c-7a18-9d5e-64805b74ee9e"}

$ curl -i "$LDS/ns/Person/ola/address"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "city": "Oslo",
  "street": "Torggata 5"
}
```

### When a write fails

When LDS waits for a saga and the saga fails, the answer is 500 with no body. The reason is in the log of LDS
only. When LDS does not wait, the answer is the usual one, and nothing tells the caller of the failure.

A 500 does not say what was done:

- A step that was done stays done. A saga that fails after the document was written leaves the document
  written.
- LDS keeps a saga that failed and runs it again later. A write that was answered 500 can so be done after the
  answer. [Operations](operations.md) says when a saga is run again, and when it is lost.

The requests below show this. They ran on an LDS started with `saga.commands.enabled=true`. With that key the
query parameter `saga` makes a step of the saga fail; it is there for tests, and is ignored when the key is
`false` (the default). Its value is a command and a step with a space between them, `failBefore persistence`:
the commands are `failBefore` and `failAfter`, and the steps are `txlog`, `persistence`, and, when search is on,
`search-index-update` (a write) or `search-index-delete` (a delete). A value without a step, or with a step the
saga does not have, does nothing.

```console
$ curl -i -X PUT "$LDS/ns/Company/f1?saga=failBefore%20persistence" -H "$J" -d '{"name": "F1"}'
HTTP/1.1 500 Internal Server Error

$ curl -i "$LDS/ns/Company/f1"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

$ curl -i -X PUT "$LDS/ns/Company/f2?sync=false&saga=failBefore%20persistence" -H "$J" -d '{"name": "F2"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d1-afa6-7537-b62b-8d390c988175"}

$ curl -i "$LDS/ns/Company/f2"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

$ curl -i -X PUT "$LDS/ns/Company/f3?saga=failAfter%20persistence" -H "$J" -d '{"name": "F3"}'
HTTP/1.1 500 Internal Server Error

$ curl -i "$LDS/ns/Company/f3"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "F3"}
```

`f3` was answered 500 and is written. `f1` and `f2` were not there right after their answers. Less than a minute
later (37 seconds in this run) a `GET` of each was answered 200 with the document, and the version of `f1` has
the time of the request that was answered 500.

LDS carries out a limited number of writes at one time (see [Configuration](configuration.md)). A write
that has waited 10 seconds for its turn is answered 500 with no body, and is not done.

### Sending a request again

A request that got no answer can be sent again; no request is refused because it was sent before
([Concepts](concepts.md) says more). What the second request does:

- A `PUT` with the same `timestamp` and the same body writes the same version again. The timeline does not grow.
- A `PUT` of a link that is there writes nothing, and a `DELETE` of a link that is not there is answered 200.
- A `DELETE` of a document that is deleted, or that never was, is answered 204.
- A write without `timestamp` makes a new version each time it is sent, because its time is the time of the
  request.

`Company/twice` is written and then deleted twice, all without `timestamp`:

```console
$ curl -i -X PUT "$LDS/ns/Company/twice" -H "$J" -d '{"name": "Twice"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9e5-1e9b-76f5-bce9-3a8b8885dc99"}

$ curl -i -X DELETE "$LDS/ns/Company/twice"
HTTP/1.1 204 No Content

$ curl -i -X DELETE "$LDS/ns/Company/twice"
HTTP/1.1 204 No Content

$ curl -i "$LDS/ns/Company/twice?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2026-11-20T16:58:24.795Z[Etc/UTC]",
    "document": {"name": "Twice"}
  },
  {
    "version": "2026-11-20T16:58:24.806Z[Etc/UTC]",
    "document": {}
  },
  {
    "version": "2026-11-20T16:58:24.816Z[Etc/UTC]",
    "document": {}
  }
]
```

A caller that must not add a version when it sends a write again gives the write a `timestamp`.

### Two writes to one document at the same time

Not finished: writes to parts and links of one document are not kept apart. Each one reads the document, changes
it and writes the whole document, so two that run at the same time each leave out the change of the other. Ran:
20 requests `PUT /ns/Person/par/friends/Person/p<n>?sync=true`, sent at the same time, were all answered 200,
and `Person/par` then had 16 of the 20 links (11 in another run). The same 20 requests sent one after the other
left all 20.

## What LDS checks before it writes

LDS checks a document against the model on `PUT` and `POST` of a whole document, and on `PUT` and `POST` of a
part (the whole document with the new part is checked). It does not check on `DELETE` of a part, and not when a
link is added or removed by its address.

A document that is not valid is answered 400 with `Content-Type: text/plain` and a body that starts with `Schema
validation error:`. Nothing is written. The text after it has one of two forms: a list in `[` and `]` of every value
that is wrong, each with its place in the document, or the first link that is wrong, after the word `Navigation:`. [The
model](model.md#what-lds-checks-when-a-document-is-written) explains the two checks behind them and how to read the
texts. These are answers with the example model:

| Document sent as `Person` | Text after `Schema validation error:` |
|---|---|
| `{"name":7}` | `[$.name: integer found, string expected]` |
| `{}` | `[$: required property 'name' not found]` |
| `[]` | `[$: array found, object expected]` |
| `{"born":"yesterday"}` | `[$.born: does not match the date-time pattern must be a valid RFC 3339 date-time, $: required property 'name' not found]` |
| `{"name":"V","friends":"/Person/ola"}` | `[$.friends: string found, array expected]` |
| `{"name":"V","employer":null}` | `[$.employer: null found, string expected]` |
| `{"name":"V","employer":"acme"}` | `Not a valid link. Navigation: employer: "acme"` |
| `{"name":"V","friends":["/Person/ola","Person/kari"]}` | `Not a valid link. Navigation: friends: "Person/kari"` |
| `{"name":"V","employer":"/Person/ola"}` | `Illegal linked-domain: "Person". Navigation: employer: "/Person/ola"` |

What a model can say, and what LDS does not check, is in [the model](model.md).

## Bodies and content types

A request body is JSON, read as UTF-8.

- Send `Content-Type: application/json`. Upper and lower case do not matter, and what follows the type
  (`; charset=utf-8`) is not read. A request with no `Content-Type` header is read as JSON.
- The body is read as UTF-8 whatever `charset` the header names.
- The body must be exactly one JSON value. Text after it is refused.
- The body of a `GET` and of a `DELETE`, and the body and the content type of a `PUT` of a link, are not read.

Answers are JSON or plain text:

- `application/json; charset=utf-8` for documents, lists, timelines, parts, schemas and the answer of
  `/source/{source}`.
- `application/json` for `{"saga-execution-id":"<id>"}`.
- `text/plain` for the texts of a 400.

The `Accept` header is not read.

Not finished: a body that cannot be read as JSON, and any other content type, are answered 500 with no body, not
400. The reason is in the log of LDS only. `curl -d` without a `Content-Type` header sends
`application/x-www-form-urlencoded`, and gets this 500. A body of `application/msgpack` or
`application/x-msgpack` is answered 500 too.

```console
$ curl -i -X PUT "$LDS/ns/Person/c1" -H "$J" -d '{"name":"A"'
HTTP/1.1 500 Internal Server Error

$ curl -i -X PUT "$LDS/ns/Person/c1" -H 'Content-Type: text/plain' -d '{"name": "A"}'
HTTP/1.1 500 Internal Server Error

$ curl -i -X PUT "$LDS/ns/Person/c1" -d '{"name": "A"}'
HTTP/1.1 500 Internal Server Error

$ curl -i -X PUT "$LDS/ns/Person/c1" -H "$J" -d '{"name": "Blåbær ÆØÅ 日本"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-ff36-7266-a719-32fe84522429"}

$ curl -i "$LDS/ns/Person/c1"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Blåbær ÆØÅ 日本"}
```

## The model over HTTP

LDS gives the model as JSON Schema (2020-12), one schema for each entity. It is the schema that documents are
checked with.

| Request | Answer |
|---|---|
| `GET /{namespace}?schema` | 200, an array of the addresses of the schemas, in the order of the names of the entities |
| `GET /{namespace}?schema=embed` | 200, an array of the schemas themselves, in the same order |
| `GET /{namespace}?schema=<another value>` | 400 `Not a valid resource. ...` |
| `GET /{namespace}/{Entity}?schema` | 200, the schema of the entity. The value of `schema` is not read |
| `GET /{namespace}/{Entity}/{id}?schema` | the document: with an id, `schema` has no effect |

All are answered with `Content-Type: application/json; charset=utf-8`. The two requests on the namespace do not
take a `/` at the end (`/ns/?schema` is answered 400), and they are answered for every method, not only `GET`.

```console
$ curl -i "$LDS/ns?schema"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["/ns/Company?schema", "/ns/Person?schema"]

$ curl -i "$LDS/ns/Company?schema"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$ref": "#/$defs/Company",
  "$defs": {
    "Company": {
      "type": "object",
      "properties": {
        "about": {
          "displayName": "",
          "type": "array",
          "items": {"$ref": "#/$defs/Text"}
        },
        "founded": {
          "type": "string",
          "format": "date-time",
          "displayName": ""
        },
        "name": {"type": "string", "displayName": ""}
      },
      "required": ["name"],
      "displayName": "A company",
      "description": "A company"
    },
    "Text": {
      "type": "object",
      "properties": {
        "languageCode": {"type": "string", "displayName": ""},
        "languageText": {"type": "string", "displayName": ""}
      },
      "required": ["languageText"]
    }
  }
}

$ curl -i "$LDS/ns?schema=embed"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[{"$schema":"https://json-schema.org/draft/2020-12/schema","$ref":"#/$defs/Company","$defs":{"Company":...}},
 {"$schema":"https://json-schema.org/draft/2020-12/schema","$ref":"#/$defs/Person","$defs":{"Person":...}}]
```

What the schema holds is described in [the model](model.md).

## Sources and the transaction log

A write may say where it comes from, with two query parameters:

- `source`: a name for the system that sends the write.
- `sourceId`: the id that the write has in that system, for example the position in a feed.

Both are free text. LDS records them with the write in the transaction log. A system that feeds LDS can then ask
how far it got:

`GET /source/{source}` gives 200, `Content-Type: application/json; charset=utf-8`, and
`{"lastSourceId":"<sourceId>"}`: the `sourceId` of the last write of that source. The answer is
`{"lastSourceId":null}` when the source has no write, or when its last write had no `sourceId`. Any other method
is answered 400.

What the answer can be depends on the configuration (see [Configuration](configuration.md)):

- With the defaults LDS does not keep the transaction log (`txlog.rawdata.provider=discard`), and the answer is
  always `null`.
- With a transaction log that is kept, and `txlog.split.sources=false` (the default), there is one log for all
  writes. Only the source named in `txlog.default-source` (`default`) can be asked for. Its answer is the
  `sourceId` of the last write, whatever `source` that write named. Every other source is answered `null`.
- With `txlog.split.sources=true` each source has a log of its own. A write without `source` belongs to the
  default source.

With the defaults:

```console
$ curl -i -X PUT "$LDS/ns/Person/src1?source=A&sourceId=a1" -H "$J" -d '{"name": "S"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d4-ff9d-73f7-88d0-cb6b1710e9bd"}

$ curl -i "$LDS/source/A"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"lastSourceId": null}
```

On an LDS started with `txlog.rawdata.provider=memory` (the log is kept until LDS stops) and
`txlog.split.sources=true`:

```console
$ curl -i -X PUT "$LDS/ns/Person/s1?source=A&sourceId=a1" -H "$J" -d '{"name": "S1"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d1-ae0c-7f9d-a4e3-9f190e4e9cd4"}

$ curl -i -X PUT "$LDS/ns/Person/s2?source=B&sourceId=b1" -H "$J" -d '{"name": "S2"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f9d1-ae3d-7acc-ba7b-c33db1380bc8"}

$ curl -i -X PUT "$LDS/ns/Person/s1/address?sync=true&source=A&sourceId=a2" -H "$J" -d '{"city": "Oslo"}'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f9d1-ae5c-79a9-b6ce-9b67ace7d51d"}

$ curl -i -X DELETE "$LDS/ns/Person/s2?source=B&sourceId=b2"
HTTP/1.1 204 No Content

$ curl -i "$LDS/source/A"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"lastSourceId": "a2"}

$ curl -i "$LDS/source/B"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"lastSourceId": "b2"}

$ curl -i "$LDS/source/nobody"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"lastSourceId": null}
```

Things to know:

- "Last" is the write that LDS received last, not the write with the latest `timestamp`. After a write with
  `sourceId=late` at 2030 and then one with `sourceId=early` at 2020, the answer was `{"lastSourceId":"early"}`.
- A write with `source` and no `sourceId` is the last write too. The answer is then `null`.
- A request that writes nothing is not recorded. A `PUT` of a link that is there already, sent with
  `sourceId=a4`, left the answer for its source as it was.
- When `sourceId` is given more than once, the first one is used.

Not finished: with `txlog.split.sources=true` the source is part of the name of a log, and LDS does not check
it. A name may hold letters, digits, `_`, `.` and `-`. A write with another `source` (ran: `source=a%20b`) is
answered 500 with no body and is not done, and `GET /source/a%20b` is answered 500 too.

Not finished: LDS writes the transaction log and reads it only for `/source/{source}`. Nothing in LDS reads
documents back from it.

## CORS

A browser asks LDS for leave before a page from another origin may read an answer. LDS answers these questions
by its configuration (see [Configuration](configuration.md)). With the defaults every origin is allowed.

A request without an `Origin` header is answered as usual, with no CORS headers.

A request with an `Origin` that is allowed gets these headers on its answer, whatever the status:

- `Access-Control-Allow-Origin` with the origin that was sent, and `Vary: Origin`.
- `Access-Control-Expose-Headers` with the names in `http.cors.allow.header` (default
  `Content-Type,Authorization`).
- `Access-Control-Allow-Credentials: true`, when `http.cors.allow.credentials` is `true` (default `false`).

An origin is allowed when the whole of it matches one of the regular expressions in `http.cors.allow.origin`
(a list with commas between; default `.*`, every origin).

A preflight request is an `OPTIONS` request with an `Origin` that is allowed, one
`Access-Control-Request-Method` that is in `http.cors.allow.methods` (default `POST,GET,PUT,DELETE,HEAD`), and
no name in `Access-Control-Request-Headers` that is not in `http.cors.allow.header`. It is answered 204 with no
body, whatever the address:

```console
$ curl -i -H 'Origin: https://app.example' "$LDS/ns/Person/kari"
HTTP/1.1 200 OK
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Expose-Headers: Authorization,Content-Type
Content-Type: application/json; charset=utf-8

{
  "employer": "/Company/globex",
  "friends": [],
  "name": "Kari"
}

$ curl -i -X OPTIONS -H 'Origin: https://app.example' -H 'Access-Control-Request-Method: PUT' \
    -H 'Access-Control-Request-Headers: content-type' "$LDS/ns/Person/kari"
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Allow-Headers: Authorization,Content-Type
Access-Control-Max-Age: 900
Access-Control-Allow-Methods: PUT,GET,DELETE,POST,HEAD
```

`Access-Control-Max-Age` is `http.cors.allow.max-age`, in seconds. The order of the names in these headers is
not fixed: it changes from one start of LDS to the next.

LDS does not refuse a request whose origin, method or headers are not allowed. It answers it as a request
without an `Origin`, with no CORS headers, and the browser is what keeps the answer from the page. A write from
an origin that is not allowed is therefore still done. An `OPTIONS` request that is no preflight request gets
the answer of the address, which for a document is 400:

```console
$ curl -i -X OPTIONS "$LDS/ns/Person/kari"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Unsupported managed resource method: OPTIONS
```

On an LDS started with `http.cors.allow.origin=https://app\.example,https://.*\.example\.org` and
`http.cors.allow.credentials=true`:

```console
$ curl -i -H 'Origin: https://x.example.org' "$LDS/ping"
HTTP/1.1 200 OK
Access-Control-Allow-Origin: https://x.example.org
Vary: Origin
Access-Control-Allow-Credentials: true
Access-Control-Expose-Headers: Authorization,Content-Type

$ curl -i -H 'Origin: https://other.example' "$LDS/ping"
HTTP/1.1 200 OK
```

## Health

| Address | Answer | What it checks |
|---|---|---|
| `/ping` | 200, no body | Nothing. LDS answers HTTP. |
| `/health/ready` | 200, no body | Nothing. The same as `/ping`. |
| `/health/alive` | 200, no body. 503, no body, when the check fails | That the persistence provider can start a read. It does not check the search index or the transaction log. |

```console
$ curl -i "$LDS/ping"
HTTP/1.1 200 OK

$ curl -i "$LDS/health/alive"
HTTP/1.1 200 OK
```

Every method gets the same answer. The addresses are exact, with upper and lower case: `/ping/x` and `/PING`
are read as a namespace and answered 400. With `http.prefix` set, the prefix comes first (`/api/ping`).
[Operations](operations.md) says how the health addresses are used.

## While LDS stops

When LDS is told to stop, it first completes the requests it has received. From that moment every new request
is answered `503 Service Unavailable` with no body, the health addresses too. The 503 means that the request was
not carried out, and that it can be sent again, later or to another LDS.

In the run below LDS was told to stop while a `PUT` was still sending its body. The `PUT` was completed and
answered `201 Created`. The requests sent in the meantime:

```console
$ curl -i "$LDS/ns/Person/ola"
HTTP/1.1 503 Service Unavailable

$ curl -i "$LDS/ping"
HTTP/1.1 503 Service Unavailable

$ curl -i -H 'Origin: https://app.example' "$LDS/ns/Person/ola"
HTTP/1.1 503 Service Unavailable
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Expose-Headers: Authorization,Content-Type
```

The 503 has the CORS headers when the origin is allowed, so a page in a browser can read it. A preflight
request is still answered 204. When LDS has stopped, a request gets no connection.

LDS waits at most 10 seconds for the requests it has received. A request that is not completed by then loses
its connection without an answer. [Operations](operations.md) describes the whole order of a stop.

## Every query parameter

| Parameter | On | Meaning | Default |
|---|---|---|---|
| `timestamp` | every request to a document, a part or a link | The time read at, or the time the new version is valid from. See [The time of a request: timestamp](#the-time-of-a-request-timestamp). | the time of the request |
| `t` | the same | Another name for `timestamp`, used when `timestamp` is not given. |  |
| `timeline` | `GET` of one document | Every version of the document. A value is not needed. | not given |
| `sync` | `PUT`, `POST`, `DELETE` | Whether LDS waits for the write before it answers. See [When LDS answers a write: sync](#when-lds-answers-a-write-sync). | a whole document: waits. A part or a link: does not wait |
| `source` | `PUT`, `POST`, `DELETE` | The name of the system the write comes from. See [Sources and the transaction log](#sources-and-the-transaction-log). | not given |
| `sourceId` | `PUT`, `POST`, `DELETE` | The id of the write in that system. | not given |
| `schema` | `/{namespace}` and `/{namespace}/{Entity}` | The model as JSON Schema; `schema=embed` on the namespace. See [The model over HTTP](#the-model-over-http). | not given |
| `saga` | `PUT`, `POST`, `DELETE` | Makes a step of the saga fail, for tests. Read only with `saga.commands.enabled=true`. See [When a write fails](#when-a-write-fails). | not given |

## Status codes

| Status | When |
|---|---|
| 200 | A read of a document, a list, a timeline, a part, a schema or a source. A link that the document has (no body). A write or a removal of a part. A link that was added, or was there already (no body). A removal of a link, in every case. A health address. |
| 201 | A write of a whole document, also when the document existed, and also when LDS did not wait. |
| 202 | A delete of a whole document with `sync=false`. |
| 204 | A delete of a whole document that LDS waited for. A CORS preflight request. |
| 400 | The address is not valid: the namespace, the entity, a field, an empty id, the form of a link address. The `timestamp` cannot be read. The body of a whole document is empty, or the address of a write has no id. The document is not valid. The method is not one the address has. |
| 404 | There is no document at the time of the request, or it is deleted then: for a read of a document, for a timeline (no version at all), for every method on a part, and for `GET`, `PUT` and `POST` of a link. A link that the document does not have (`GET`). The address `/`, and an address outside `http.prefix`. |
| 500 | The body cannot be read as JSON, or the content type is not JSON. The saga of a write failed while LDS waited, or the write waited 10 seconds for its turn. Also the cases marked "Not finished" in this document: an id with a `%`, a `source` that cannot be the name of a log, an empty body to a part, the name of a type that is not an entity. |
| 503 | LDS is stopping. `/health/alive`: the persistence provider does not answer. |

A 400 has `Content-Type: text/plain` and a text that says what is wrong; the one 400 without a text is in
[Ids and encoding](#ids-and-encoding). A 404, a 500 and a 503 have no body.
