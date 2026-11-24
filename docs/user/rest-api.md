---
title: "The REST API"
description: "Every address, method, parameter and answer."
order: 4
---

# The REST API

This document is the reference for the REST API of Linked Data Store (LDS): every address, method and query
parameter, the body of each request, the status codes and what each answer holds. It is for people who read and
write documents over HTTP. The ideas behind the API ([document](../glossary.md#document),
[version](../glossary.md#version), [link](../glossary.md#link), [saga](../glossary.md#saga)) are explained in
[Concepts](concepts.md), and [Getting started](getting-started.md) shows how to start LDS.

## About the examples

Every request in this document was run against LDS `3.0.1`, the image `descoped/lds:3.0.1` started with its
defaults: everything in memory, and the example [model](../glossary.md#model). Where an example needs another
start, the text says so.

The example model has two [entity types](../glossary.md#entity-type):

- `Person` with `name` (a text, required), `born` (a date-time), `address` (an
  [embedded object](../glossary.md#embedded-object) with `street` and `city`), `employer` (a link to a `Company`) and
  `friends` (a list of links to `Person`).
- `Company` with `name` (a text, required), `founded` (a date-time) and `about` (a list of embedded objects with
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
from run to run. The requests were sent on 2026-11-24 (UTC), which matters where a request names no time.

LDS has no authentication. It answers every caller that reaches it, over plain HTTP.

## Addresses

| Address | What it is |
|---|---|
| `/{namespace}/{Entity}/{id}` | One document. See [Whole documents](#whole-documents). |
| `/{namespace}/{Entity}` | All documents of an entity type. See [Read all documents of an entity](#read-all-documents-of-an-entity). |
| `/{namespace}/{Entity}/{id}/{field}`, and deeper: `.../{field}/{field}` | A part of a document. See [Parts of a document](#parts-of-a-document). |
| `/{namespace}/{Entity}/{id}/{link field}/{TargetEntity}/{target id}` | One link of a document. See [Links](#links). |
| `/{namespace}?schema` and `/{namespace}/{Entity}?schema` | The model, as JSON Schema. See [The model over HTTP](#the-model-over-http). |
| `/source/{source}` | The last write of a source. See [Sources and the transaction log](#sources-and-the-transaction-log). |
| `/ping`, `/health/ready`, `/health/alive` | Health. See [Health](#health). |
| `/graphql`, `/graphiql` | GraphQL. See [the GraphQL API](graphql-api.md). |

The texts that LDS sends with a status 400 use other words for the three kinds of resource: a "managed resource"
is a whole document, an "embedded resource" is a part of a document, a "reference resource" is a link, and a
"managed-domain" is an entity type.

The rules for an address:

- `{namespace}` is the one [namespace](../glossary.md#namespace) of this LDS. It is set with the key
  `namespace.default`, and is `ns` unless the configuration says otherwise. An address with another first
  segment, that is not one of the other addresses of the table above, is answered 400.
- `{Entity}` is the name of an entity type of the model, with the same upper and lower case letters.
- `{id}` is the [id](../glossary.md#id) of the document. The caller chooses it; LDS does not make ids. It is
  free text of at most 1024 bytes in UTF-8, must not be empty or only spaces, and cannot hold a `/` or a control
  character. See [Ids and encoding](#ids-and-encoding).
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

The text of the fifth answer names an id, but an address with a namespace and an entity type is enough.

Not finished: the REST API answers for one namespace only.

Not finished: the name of an embedded type of the model, a type that is not an entity type, is taken as an entity
type in an address. With the example model, where `Address` is the type of the field `address`, `GET /ns/Address`
is answered 200 with `[]`. A `PUT` to `/ns/Address/a1` and `GET /ns/Address?schema` are answered 500 with no body.

### Ids and encoding

Every segment of an address is decoded once, as UTF-8: `%C3%B8l` is the id `øl`, `%20` is a space, `%25` is a
`%` and `%2B` is a `+`. A `+` in an address is a `+`, not a space. Letters outside ASCII must be percent-encoded
(curl does it when they are typed in the address); sent as they are, they are answered 400 with no body, and so
is a `%` that two hexadecimal digits do not follow.

```console
$ curl -i -X PUT "$LDS/ns/Person/id%20with%20space" -H "$J" -d '{"name": "spaced"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ecc4-7d1a-9965-e5f399ae99f8"}

$ curl -i "$LDS/ns/Person/id%20with%20space"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "spaced"}

$ curl -i "$LDS/ns/Person/id+with+space"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8
```

An id is at most 1024 bytes in UTF-8: 1024 letters of ASCII, or fewer letters that take more bytes (`ø` takes two,
`😀` four). It cannot hold a `/` (`%2F`) or a control character such as a line break, and its bytes must be UTF-8.
Such an id is answered 400 with a text that says which rule it breaks:

| Id in the address | Answer |
|---|---|
| `a%2Fb` | 400, `An id cannot hold a '/': "a/b"` |
| `a%0Ab` | 400, `An id cannot hold a control character` |
| 1025 times `a` | 400, `An id is at most 1024 bytes in UTF-8` |
| 513 times `%C3%B8` (`ø`, 1026 bytes) | 400, `An id is at most 1024 bytes in UTF-8` |
| `%ED%A0%80`, bytes that are not UTF-8 | 400, `Not UTF-8 in the path: "%ED%A0%80"` |

An id of 1024 times `a` was written and answered 201. The same rules hold for the id of the target in the address
of a link (`/ns/Person/ola/employer/Company/{id}`), and for the id in a link that a document holds. JSON can write
half of a character, which is no text: a document with `"employer": "/Company/\ud800"` is refused with
`Schema validation error: A link names an id that is not valid: An id cannot hold half of a character (an unpaired
surrogate). Navigation: employer`.

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

{"saga-execution-id": "01a20fe2-b8ab-763c-a440-8abc5f987763"}

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
`/source/{source}` does not find the last write: in the same LDS, with the
[transaction log](../glossary.md#transaction-log) kept in memory, `GET /api/source/default` answered
`{"lastSourceId":null}` after the write above.

## Whole documents

### Write a document

`PUT` or `POST` to `/{namespace}/{Entity}/{id}`. The two methods do the same. The body is the whole document, a
JSON object.

| Query parameter | Meaning |
|---|---|
| `timestamp` | The valid time of the new version. Without it: the time LDS receives the request. See [Versions and time](#versions-and-time). |
| `sync` | `sync=false`: LDS answers before the write is done. See [When LDS answers a write: sync](#when-lds-answers-a-write-sync). |
| `searchable` | `searchable=true`: LDS answers when search finds the write. See [When search finds a write: searchable](#when-search-finds-a-write-searchable). |
| `source`, `sourceId` | Where the write comes from. See [Sources and the transaction log](#sources-and-the-transaction-log). |

The write makes one version of the document, with the time of the request as its
[valid time](../glossary.md#valid-time). It does not change the other versions of the document. A version that
exists at exactly that time is replaced.

A field whose value is `null` is written as a field that is not there, at any depth of the document: in the
document, in an embedded object and in an object inside an array. A `null` that is an item of an array is a value,
and is kept. Ran: `{"name":"Nul","extra":[1,null,{"k":null}],"about":[{"languageCode":null,"languageText":"x"}]}`
written as a `Company` came back as `{"name":"Nul","extra":[1,null,{}],"about":[{"languageText":"x"}]}`.

Before it writes, LDS checks the document against the model
([schema validation](../glossary.md#schema-validation), see
[What LDS checks before it writes](#what-lds-checks-before-it-writes)). It does not look for the documents that
the links point at: a document may link to a document that does not exist.

LDS also checks two limits, the same with every persistence provider, after the `null` fields are taken out. The
[path](../glossary.md#path) of a value, written as `$`, then `.` and the name of each field, and `[]` for each array
(`$.address.street`, `$.friends[]`), is at most 1024 bytes in UTF-8. And a value is inside at most 32 arrays. A
document that goes past one of them is refused with 400, `text/plain`, and a text that names the path, cut after 80
characters:

| Document sent as `Person` | Answer |
|---|---|
| `{"name": "L"}` with a field whose name is 1030 times `f` | `The path of a value is at most 1024 bytes in UTF-8: $.ffff...` |
| `{"name": "L", "deep": [[[...[1]...]]]}` with 33 arrays | `A value is in at most 32 arrays: $.deep[][]...[]` |

The same document with a name of 1020 times `f`, or with 32 arrays, was answered 201.

The answer is `201 Created` with `Content-Type: application/json` and the body `{"saga-execution-id":"<id>"}`.
The status is 201 also when the document existed before, and also with `sync=false`. `saga-execution-id` is the
id of the saga that carries out this write. It is a UUID of version 7, and each one is greater than those LDS
gave before it. No address of the REST API takes it.

```console
$ curl -i -X PUT "$LDS/ns/Company/acme" -H "$J" -d '{"name": "Acme"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ed24-72f6-8bc0-af18417d4eae"}

$ curl -i -X PUT "$LDS/ns/Person/ola" -H "$J" \
    -d '{
      "name": "Ola",
      "address": {"street": "Storgata 1", "city": "Oslo"},
      "employer": "/Company/acme"
    }'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ed36-747f-84ba-4a6ceed50f82"}

$ curl -i -X POST "$LDS/ns/Person/kari" -H "$J" -d '{"name": "Kari", "born": null}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ed48-7619-8df1-c231a36f5e56"}

$ curl -i "$LDS/ns/Person/kari"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Kari"}
```

What is refused, in the order LDS checks it:

| Case | Answer |
|---|---|
| The body has no characters | 400, `text/plain`, `Payload was empty!` |
| The address has no id | 400, `text/plain`, `Id was empty!` |
| The content type is not JSON | 415, `text/plain`, `Unsupported Content-Type: ...`. See [Bodies and content types](#bodies-and-content-types). |
| The body has only spaces | 400, `text/plain`, `Payload was empty!` |
| The body cannot be read as JSON | 400, `text/plain`, `The body cannot be read at line ..., column ...: ...`. See [Bodies and content types](#bodies-and-content-types). |
| A path is too long, or a value is in too many arrays | 400, `text/plain`, `The path of a value is at most 1024 bytes in UTF-8: ...` or `A value is in at most 32 arrays: ...` |
| The document is not valid | 400, `text/plain`, `Schema validation error: ...` |
| The saga of the write fails | 500, no body. See [When a write fails](#when-a-write-fails). |

Nothing is written when a write is refused. Before all of these, the write waits for its turn: one write at a
time to a document, see [One write at a time to a document](#one-write-at-a-time-to-a-document).

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

`GET` of `/{namespace}/{Entity}/{id}` gives the document as of the time of the request (an
[as-of read](../glossary.md#as-of-read)): now, or the time in `timestamp` (see [Versions and time](#versions-and-time)).

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
- A field that was sent as `null` is not there (see [Write a document](#write-a-document)).
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

`GET` of `/{namespace}/{Entity}` gives 200 and an array of the documents of the entity type as of the time of
the request, in the order of their ids. A document that is deleted at that time is left out. The array is `[]`
when there is none.

The ids are in the order of their Unicode code points, which is the order of their bytes in UTF-8, with every
persistence provider: a digit comes before an upper case letter, an upper case letter before a lower case letter,
and every letter of ASCII before `ø` (U+00F8). The letters above U+FFFF, such as `😀` (U+1F600), come after all
the others, also after `ﬀ` (U+FB00) and a full-width `Ａ` (U+FF21). Three persons with such ids, and the list:

```console
$ curl -i -X PUT "$LDS/ns/Person/%EF%AC%80" -H "$J" -d '{"name": "ﬀ"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-edbf-7880-a7c3-ecb2f5656365"}

$ curl -i -X PUT "$LDS/ns/Person/%EF%BC%A1" -H "$J" -d '{"name": "Ａ"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-edcd-7435-8075-b68b8f134dd6"}

$ curl -i -X PUT "$LDS/ns/Person/%F0%9F%98%80" -H "$J" -d '{"name": "😀"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-eddc-7fd6-aebf-80ee759f97bc"}

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
  },
  {"name": "ﬀ"},
  {"name": "Ａ"},
  {"name": "😀"}
]
```

The first document of the answer is the one with the id `id with space`, written in [Ids and
encoding](#ids-and-encoding). The name of each of the last three is its id.

Not finished: the answer holds the documents and not their ids, and it is always the whole list. There are no
parameters for a range or for pages. [The GraphQL API](graphql-api.md) gives ids and pages.

The REST API has no search and cannot find a document by the value of a field. Search is in
[the GraphQL API](graphql-api.md#search); a write can wait until search finds it, see
[When search finds a write: searchable](#when-search-finds-a-write-searchable).

### Delete a document

`DELETE` of `/{namespace}/{Entity}/{id}`. No body. The query parameters are those of a write: `timestamp`,
`sync`, `searchable`, `source` and `sourceId`.

A delete does not remove the document. It writes a [tombstone](../glossary.md#tombstone): a version, valid from
the time of the request, in which the document is deleted. The versions before it and after it stay as they are.
See
[What a delete means for earlier and later versions](#what-a-delete-means-for-earlier-and-later-versions).

- 204 with no body, when LDS waited for the write (the default).
- 202, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`, with `sync=false`.
- 400 `Id was empty!`, when the address has no id.
- 500 with no body, when the saga of the write fails.

A delete of a document that does not exist is answered in the same way, and writes a tombstone too.

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

Every version of a document has a valid time, from which it holds. A request to a document, to a part of a
document or to a link has a time too:

- For a read it is the time of the as-of read. The answer is made from the newest version whose valid time is
  that time or earlier.
- For a write (`PUT`, `POST`, `DELETE`) it is the valid time of the new version.

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

With the memory provider the valid time of a version is kept to the millisecond: a version written at
`2020-01-01T00:00:00.123456789Z` is the version `2020-01-01T00:00:00.123Z`.

### Write a version at a time

Three versions of one document, the second with an offset:

```console
$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2020-01-01T00:00:00Z" -H "$J" -d '{"name": "Globex 2020"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ee1e-7b4b-b385-7f3b3de61ff2"}

$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2022-01-01T00:00:00%2B01:00" -H "$J" \
    -d '{"name": "Globex 2022"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ee2c-7a1e-82ae-1315f1ece418"}

$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2030-06-01T12:00:00Z" -H "$J" -d '{"name": "Globex 2030"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ee39-7881-af64-56ea6cac0e70"}
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

The first read names no time, so the document is read as of the time of the request (2026). The version of 2030
is in the future, and a read without a time does not give it until its time has come. Before the first version
the answer is 404.

The list of an entity type is read as of a time in the same way:

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

`GET` of `/{namespace}/{Entity}/{id}?timeline` gives the [timeline](../glossary.md#timeline) of the document: every
version, the oldest first.

- 200, an array. Each element is `{"version":"<time>","document":<the document>}`.
- 404 with no body, when the document has no version.

`timeline` is switched on by being there. Its value is not read: `?timeline=false` gives the timeline too.
`timestamp` has no effect on it. On the list of an entity type (`/ns/Company?timeline`) it has no effect.

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

`version` is the valid time in UTC, ending in `[Etc/UTC]`. The seconds are left out when they are zero
(`2020-01-01T00:00Z[Etc/UTC]`, `2026-11-24T23:24:23.681Z[Etc/UTC]`). The text can be given back as `timestamp`
when `[` and `]` are written `%5B` and `%5D`: `?timestamp=2020-01-01T00:00Z%5BEtc/UTC%5D`.

Not finished: the answer is always the whole list. There are no parameters for a range of time or for pages.

### Write between versions, and replace a version

A write at a valid time before versions that exist makes a version at that time and leaves the later versions
as they are. A write at exactly the valid time of a version that exists replaces that version; the same moment
written with another offset is the same time. The first write below replaces the version of 2020, the second
adds a version between 2020 and 2022.

```console
$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2020-01-01T01:00:00%2B01:00" -H "$J" \
    -d '{"name": "Globex 2020, written again"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ee9a-72dc-aac9-a9fe3c308299"}

$ curl -i -X PUT "$LDS/ns/Company/globex?timestamp=2021-01-01T00:00:00Z" -H "$J" -d '{"name": "Globex 2021"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-eea6-71c4-b3fb-33604cce1bef"}

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

A delete writes a tombstone, a version in which the document is deleted. From that time until the next version
the document is not there: a read is answered 404, and the document is not in the list of its entity type. Before
that time, and from the next version on, the document is read as before.

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

In the timeline the tombstone is there, with an empty document (seen with the memory provider):

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

A write at the valid time of a tombstone replaces it, and a delete at the valid time of a version replaces that
version.

A delete of a document that was never written, here with `sync=false`:

```console
$ curl -i -X DELETE "$LDS/ns/Company/initech?sync=false"
HTTP/1.1 202 Accepted
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ef01-7992-bc1b-befa76662ec2"}

$ curl -i "$LDS/ns/Company/initech?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2026-11-24T23:24:23.681Z[Etc/UTC]",
    "document": {}
  }
]
```

Over HTTP a version cannot be removed for good.

## Parts of a document

A part of a document is a field of it, addressed by its [path](../glossary.md#path):
`/{namespace}/{Entity}/{id}/{field}`. A path that ends at a link field (`/ns/Person/ola/employer`) is a part too:
its value is the link, or the list of links.

Every method first reads the document as of the time of the request. When there is no document at that time, or
it is deleted, the answer is 404 with no body, whatever the method and the body.

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

Not finished: the form of the answer depends on the value, as listed above.

### Write a part

`PUT` or `POST`; the two do the same. The body is the new value of the part, as JSON: an object, an array, or a
single value such as `"Oslo"`. The body `null` removes the part, as a field whose value is `null` is not written
(see [Write a document](#write-a-document)). The query parameters are those of a write of a whole document.

LDS reads the document as of the time of the request, puts the new value in place of the part, checks the whole
document against the model, and writes the whole document as a new version, valid from the time of the request.

- The part is replaced as a whole. It is not merged with what was there.
- Objects on the way to the part that the document does not have are made.
- 200, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`.
- 400 `Schema validation error: ...`, when the document with the new part is not valid. Nothing is written.
- 404 with no body, when there is no document at that time.
- 400 or 415, as for a whole document, when the body is empty, cannot be read as JSON or has another content
  type, and 400 when the document with the new part goes past a limit of
  [Write a document](#write-a-document). Nothing is written. See [Bodies and content types](#bodies-and-content-types).
- 500 with no body, when the saga of the write fails.

LDS answers when the write is done, as for a whole document; with `sync=false` it answers before, see
[When LDS answers a write: sync](#when-lds-answers-a-write-sync).

```console
$ curl -i -X PUT "$LDS/ns/Person/ola/address" -H "$J" \
    -d '{
      "street": "Karl Johans gate 2",
      "city": "Oslo"
    }'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ef52-7d42-b6ae-ab7d432c56ee"}

$ curl -i -X PUT "$LDS/ns/Person/ola/born" -H "$J" -d '"1990-05-17T00:00:00Z"'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ef60-7a33-84f3-81f919c9fd38"}

$ curl -i "$LDS/ns/Person/ola"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "born": "1990-05-17T00:00:00Z",
  "employer": "/Company/acme",
  "name": "Ola",
  "address": {"city": "Oslo", "street": "Karl Johans gate 2"}
}

$ curl -i -X PUT "$LDS/ns/Person/ola/born" -H "$J" -d '"not a date"'
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: [$.born: does not match the date-time pattern must be a valid RFC 3339 date-time]

$ curl -i -X PUT "$LDS/ns/Person/ola/address?timestamp=2019-01-01T00:00:00Z" -H "$J" \
    -d '{
      "street": "Too early",
      "city": "Oslo"
    }'
HTTP/1.1 404 Not Found
```

The last request is answered 404 because `Person/ola` has no version in 2019.

The body `null` removes a part. Ran: a `PUT` of `null` to `/ns/Person/nul/address` was answered 200, and the
document then had no `address`. A `PUT` of `null` to `/ns/Person/ola/name` was refused with
`Schema validation error: [$: required property 'name' not found]`, as the model requires `name`.

### Remove a part

`DELETE` removes the field and writes the whole document as a new version. No body.

- 200, `Content-Type: application/json`, `{"saga-execution-id":"<id>"}`.
- 404 with no body, when there is no document at that time.

```console
$ curl -i -X DELETE "$LDS/ns/Person/ola/born"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-ef91-75a9-87ad-126bd5095fe5"}

$ curl -i "$LDS/ns/Person/ola/born"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[null]
```

Not finished: the document is not checked against the model after a part is removed, so a field that the model
requires can be removed. Ran: after `DELETE /ns/Person/req/name` the document was `{}`, and the next
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
    -d '{
      "name": "Acme",
      "address": {"street": "Storgata 1", "city": "Oslo"}
    }'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fe5-04e0-77f7-abff-d769013d1877"}

$ curl -i "$LDS/ns/Company/acme/address/city"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["Oslo"]

$ curl -i -X PUT "$LDS/ns/Company/acme/address/city" -H "$J" -d '"Bergen"'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fe5-0561-7d50-92a1-943f5b139622"}

$ curl -i -X PUT "$LDS/ns/Company/acme/address/city" -H "$J" -d 7
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: [$.address.city: integer found, string expected]

$ curl -i -X PUT "$LDS/ns/Company/acme/support/contacts/Person/kari"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fe5-05a8-7366-806b-116011e81e59"}

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

`{TargetEntity}` must be an entity type that the field may link to. The link field may be deeper in the document:
`.../{field}/{link field}/{TargetEntity}/{target id}`. Nothing may follow the target id.

LDS never looks for the target. A link may point at a document that does not exist (a dangling reference), and
the address of a link says only whether the link is in the document.

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

$ curl -i -X PUT "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-efc4-72b1-8603-6eb789a79373"}

$ curl -i "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 200 OK

$ curl -i -X PUT "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 200 OK

$ curl -i -X PUT "$LDS/ns/Person/kari/friends/Person/ola"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-efeb-76de-80fe-7e2360595b50"}

$ curl -i -X POST "$LDS/ns/Person/kari/friends/Person/per"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-effd-7adc-be9e-92e5437f4a16"}

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
$ curl -i -X PUT "$LDS/ns/Person/kari/employer/Company/globex"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-f020-7598-bb88-70b29736fe36"}

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
- The body is empty when LDS waited for the write (the default). With `sync=false` it is
  `{"saga-execution-id":"<id>"}`, with `Content-Type: application/json`.

```console
$ curl -i -X DELETE "$LDS/ns/Person/kari/friends/Person/per"
HTTP/1.1 200 OK

$ curl -i -X DELETE "$LDS/ns/Person/kari/friends/Person/per"
HTTP/1.1 200 OK

$ curl -i -X DELETE "$LDS/ns/Person/kari/friends/Person/ola?sync=false"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-f065-7848-9322-00be3d31db01"}

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

$ curl -i -X PUT "$LDS/ns/Person/nobody/employer/Company/acme"
HTTP/1.1 404 Not Found

$ curl -i -X PATCH "$LDS/ns/Person/kari/employer/Company/acme"
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Unsupported reference resource method: PATCH
```

An address that ends at the target entity type (`/ns/Person/kari/employer/Company`) is answered 400 with
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

A write to a part or to a link with a `timestamp` reads the document as of that time, changes it, and writes it
as the version of that time. Later versions are not changed: the change is in the new version only.

`Person/tm` is written at 2020, and its `address` is written at 2022. A link written at 2021 is in the version
of 2021 and not in the version of 2022. A write at 2019 is answered 404, because there is no document then.

```console
$ curl -i -X PUT "$LDS/ns/Person/tm?timestamp=2020-01-01T00:00:00Z" -H "$J" \
    -d '{
      "name": "T",
      "address": {"street": "Old", "city": "Oslo"}
    }'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-f4d5-7448-aa84-62a05b472c58"}

$ curl -i -X PUT "$LDS/ns/Person/tm/address?timestamp=2022-01-01T00:00:00Z" -H "$J" \
    -d '{
      "street": "New",
      "city": "Oslo"
    }'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-f4e1-7ffc-88d6-0ea05afd2f08"}

$ curl -i -X PUT "$LDS/ns/Person/tm/address?timestamp=2019-01-01T00:00:00Z" -H "$J" \
    -d '{
      "street": "Too early",
      "city": "Oslo"
    }'
HTTP/1.1 404 Not Found

$ curl -i -X PUT "$LDS/ns/Person/tm/employer/Company/acme?timestamp=2021-01-01T00:00:00Z"
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-f4f9-7966-ba18-4b94a9fcfbaf"}

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
records the write in the transaction log, then writes the document with the
[persistence provider](../glossary.md#persistence-provider) and, when search is on, adds the change to the index
queue, from which LDS sends it to the search index. [Concepts](concepts.md) says more about sagas.

A write to a part or to a link is a write of the whole document: LDS reads the document, changes it, and writes
it as a new version.

The query parameter `sync` says whether LDS waits for the saga before it answers:

- LDS waits: the answer comes when the saga is done. A read that follows sees the write
  ([read-your-writes](../glossary.md#synchronous-write)). This is the default, for a whole document, a part and
  a link alike.
- LDS does not wait, with `sync=false`: the answer comes when LDS has noted the write in its
  [saga log](../glossary.md#saga-log), the list of the writes it has to carry out. The document is written
  shortly after. A read that follows may not see the write yet (eventual consistency). If the saga fails after the
  answer, the caller is not told.

| `sync` | LDS |
|---|---|
| not given | waits |
| `sync=true` | waits |
| `sync=false`, in upper or lower case | does not wait |
| with no value, or another value | waits |

A write waits for the saga, and not for search: search finds the write shortly after, unless the write asks for
more, see [When search finds a write: searchable](#when-search-finds-a-write-searchable).

The status code does not say whether LDS waited, but for the delete of a whole document (204 or 202). The body
of the delete of a link does (see [Remove a link](#remove-a-link)).

A write to a part with `sync=false`, and a read one second later:

```console
$ curl -i -X PUT "$LDS/ns/Person/ola/address?sync=false" -H "$J" \
     -d '{
       "street": "Torggata 5",
       "city": "Oslo"
     }'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fdf-f52a-744b-a411-65e3a1d5624d"}

$ curl -i "$LDS/ns/Person/ola/address"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "city": "Oslo",
  "street": "Torggata 5"
}
```

### When search finds a write: searchable

When search is on, a write is answered when its saga is done, and search finds it shortly after: LDS sends the
change from its index queue to the search index, and the search index makes it searchable within about a second
(the key `search.index.commit-within`, see [Configuration](configuration.md)). A write does not wait for search,
and a search index that does not answer does not slow it.

A write that is to be found by the search that follows it asks for it with `searchable=true`, on `PUT`, `POST` and
`DELETE` of a document, a part or a link:

- LDS waits for the saga of the write, also with `sync=false`, and then until search finds the write, at most 10
  seconds. The answer is then the one the write has without `searchable`.
- The answer has the header `LDS-Searchable`: `true` when search finds the write, `false` when the 10 seconds were
  not enough. A page in a browser can read it (see [CORS](#cors)).
- At most half as many writes as LDS has threads for requests (four for each processor) wait for search at one
  time. A write past that is answered when its saga is done, with `LDS-Searchable: false`.
- Without search, `searchable=true` makes the write wait for its saga, and the answer has no `LDS-Searchable`.

These requests ran on an LDS in memory with search on (`graphql.search.enabled=true`, Solr 10):

```console
$ curl -i -X PUT "$LDS/ns/Company/fjord?searchable=true" -H "$J" \
    -d '{
      "name": "Fjord",
      "about": [{"languageCode": "nb", "languageText": "Fisk fra fjorden"}]
    }'
HTTP/1.1 201 Created
LDS-Searchable: true
Content-Type: application/json

{"saga-execution-id": "01a20feb-5c80-7a59-a7da-bc9ec8d2fb77"}

$ curl -i -X PUT "$LDS/ns/Company/fjord/about?searchable=true&sync=false" -H "$J" \
    -d '[{"languageCode": "nb", "languageText": "Laks fra fjordene"}]'
HTTP/1.1 200 OK
LDS-Searchable: true
Content-Type: application/json

{"saga-execution-id": "01a20feb-5edb-7e08-b84f-804735cbf3c6"}

$ curl -i -X DELETE "$LDS/ns/Company/gone?searchable=true"
HTTP/1.1 204 No Content
LDS-Searchable: true

$ curl -i "$LDS/graphql" -H 'Content-Type: application/graphql' \
    -d '{
      Search(query: "laks") {
        edges {
          node {
            ... on Company {
              name
            }
          }
        }
      }
    }'
HTTP/1.1 200 OK
Content-Type: application/json

{
  "data": {
    "Search": {
      "edges": [
        {
          "node": {"name": "Fjord"}
        }
      ]
    }
  }
}
```

With Solr stopped, the same LDS answered a write after 10 seconds, and the write was done:

```console
$ curl -i -X PUT "$LDS/ns/Company/slow?searchable=true" -H "$J" -d '{"name": "Slow"}'
HTTP/1.1 201 Created
LDS-Searchable: false
Content-Type: application/json

{"saga-execution-id": "01a20feb-62b6-7314-b390-63719e1b7da8"}
```

### When a write fails

When LDS waits for a saga and the saga fails, the answer is 500 with no body. The reason is in the log of LDS
only. When LDS does not wait, the answer is the usual one, and nothing tells the caller of the failure.

A 500 does not say what was done:

- A step that was done stays done. A saga that fails after the document was written leaves the document
  written.
- A step that fails leaves nothing of what it was writing: the persistence provider writes the whole version or
  none of it.
- LDS keeps a saga that failed and runs it again later. A write that was answered 500 can so be done after the
  answer. [Operations](operations.md) says when a saga is run again, and when it is lost.

The requests below show this. They ran on an LDS started with `saga.commands.enabled=true`. With that key the
query parameter `saga` makes a step of the saga fail; it is there for tests, and is ignored when the key is
`false` (the default). Its value is a command and a step with a space between them, `failBefore persistence`:
the commands are `failBefore` and `failAfter`, and the steps are `txlog`, `persistence`, and, when search is on,
`index-queue`. A value without a step, or with a step the saga does not have, does nothing.

```console
$ curl -i -X PUT "$LDS/ns/Company/f1?saga=failBefore%20persistence" -H "$J" -d '{"name": "F1"}'
HTTP/1.1 500 Internal Server Error

$ curl -i "$LDS/ns/Company/f1"
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

$ curl -i -X PUT "$LDS/ns/Company/f2?sync=false&saga=failBefore%20persistence" -H "$J" -d '{"name": "F2"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fe2-da61-7085-be96-d9ae913485f9"}

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
later (55 seconds in this run) a `GET` of each was answered 200 with the document, and the version of `f1` has
the time of the request that was answered 500.

LDS carries out a limited number of writes at one time (see [Configuration](configuration.md)). A write
that has waited 10 seconds for a place among them is answered 500 with no body, and is not done.

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

{"saga-execution-id": "01a20fdf-f942-7f32-ae68-4a977819c624"}

$ curl -i -X DELETE "$LDS/ns/Company/twice"
HTTP/1.1 204 No Content

$ curl -i -X DELETE "$LDS/ns/Company/twice"
HTTP/1.1 204 No Content

$ curl -i "$LDS/ns/Company/twice?timeline"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[
  {
    "version": "2026-11-24T23:24:26.306Z[Etc/UTC]",
    "document": {"name": "Twice"}
  },
  {
    "version": "2026-11-24T23:24:26.327Z[Etc/UTC]",
    "document": {}
  },
  {
    "version": "2026-11-24T23:24:26.340Z[Etc/UTC]",
    "document": {}
  }
]
```

A caller that must not add a version when it sends a write again gives the write a `timestamp`.

### One write at a time to a document

LDS carries out the writes to one document one after the other. A write waits for its turn before it reads the
document, and keeps it until its saga is done, also when it does not wait for the saga (`sync=false`). So a write
to a part or to a link, which reads the document, changes it and writes the whole document, always reads what the
write before it wrote. Ran: 20 requests `PUT /ns/Person/par/friends/Person/p<n>`, sent at the same time, were all
answered 200, and `Person/par` then had all 20 links.

A write without `timestamp` gets as its valid time the moment it has its turn, so its version comes after the
version of the write before it.

A write waits at most 30 seconds for its turn. Then it is answered `503 Service Unavailable`, `text/plain`, with
`The document is being written by another request. Try again.`, and nothing is written. While a write waits, no
thread of LDS waits with it, and the reads and the writes of other documents go on.

The turns are kept in one LDS: two LDS that write to the same store do not wait for each other.

## What LDS checks before it writes

LDS checks a document against the model on `PUT` and `POST` of a whole document, and on `PUT` and `POST` of a
part (the whole document with the new part is checked). It does not check on `DELETE` of a part, and not when a
link is added or removed by its address.

A document that is not valid is answered 400 with `Content-Type: text/plain` and a body that starts with `Schema
validation error:`. Nothing is written. The text after it has one of two forms: a list in `[` and `]` of every value
that is wrong, each with its place in the document, or the first link that is wrong, with its field after the word
`Navigation:`. [The
model](model.md#what-lds-checks-when-a-document-is-written) explains the two checks behind them and how to read the
texts. These are answers with the example model:

| Document sent as `Person` | Text after `Schema validation error:` |
|---|---|
| `{"name":7}` | `[$.name: integer found, string expected]` |
| `{}` | `[$: required property 'name' not found]` |
| `[]` | `[$: array found, object expected]` |
| `{"born":"yesterday"}` | `[$.born: does not match the date-time pattern must be a valid RFC 3339 date-time, $: required property 'name' not found]` |
| `{"name":"V","friends":"/Person/ola"}` | `[$.friends: string found, array expected]` |
| `{"name":"V","employer":"acme"}` | `Not a valid link. Navigation: employer: "acme"` |
| `{"name":"V","friends":["/Person/ola","Person/kari"]}` | `Not a valid link. Navigation: friends: "Person/kari"` |
| `{"name":"V","employer":"/Person/ola"}` | `Illegal linked-domain: "Person". Navigation: employer: "/Person/ola"` |
| `{"name":"V","employer":"/Company/\ud800"}` | `A link names an id that is not valid: An id cannot hold half of a character (an unpaired surrogate). Navigation: employer` |

A field whose value is `null` is taken out before the check: `{"name":"V","employer":null}` is written as
`{"name":"V"}`.

What a model can say, and what LDS does not check, is in [the model](model.md).

## Bodies and content types

A request body is JSON, read as UTF-8.

- Send `Content-Type: application/json`. Upper and lower case do not matter, and what follows the type
  (`; charset=utf-8`) is not read. A request with no `Content-Type` header is read as JSON.
- The body is read as UTF-8 whatever `charset` the header names.
- The body must be exactly one JSON value. Text after it is refused.
- The body of a `GET` and of a `DELETE`, and the body and the content type of a `PUT` of a link, are not read.

Answers are JSON or plain text:

- `application/json; charset=utf-8` for documents, lists, timelines, parts, schemas, the answer of
  `/source/{source}` and the answer of `/health/ready`.
- `application/json` for `{"saga-execution-id":"<id>"}`.
- `text/plain` for the texts of a 400, a 409, a 415 and the 503 of a write that waited too long for its turn.

The `Accept` header is not read.

A body LDS does not read is answered with a text that says what is wrong, and nothing is written:

- 415 when the content type is not JSON. `curl -d` without a `Content-Type` header sends
  `application/x-www-form-urlencoded`, and gets this answer.
- 400 when the body is empty or only spaces (`Payload was empty!`), or cannot be read as JSON. The text names the
  line and the column where reading stopped.
- A body of `application/msgpack` or `application/x-msgpack` is answered 400: LDS takes the content type, but
  does not read MessagePack.

```console
$ curl -i -X PUT "$LDS/ns/Person/c1" -H "$J" -d '{"name":"A"'
HTTP/1.1 400 Bad Request
Content-Type: text/plain

The body cannot be read at line 1, column 12: Unexpected end-of-input: expected close marker for Object. Send the
body as JSON, with Content-Type: application/json

$ curl -i -X PUT "$LDS/ns/Person/c1" -H 'Content-Type: text/plain' -d '{"name": "A"}'
HTTP/1.1 415 Unsupported Media Type
Content-Type: text/plain

Unsupported Content-Type: text/plain. Send the body as JSON, with Content-Type: application/json

$ curl -i -X PUT "$LDS/ns/Person/c1" -d '{"name": "A"}'
HTTP/1.1 415 Unsupported Media Type
Content-Type: text/plain

Unsupported Content-Type: application/x-www-form-urlencoded. Send the body as JSON, with Content-Type:
application/json

$ curl -i -X PUT "$LDS/ns/Person/c1" -H "$J" -d '{"name": "Blåbær ÆØÅ 日本"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fdf-fa10-7445-a383-e078c60ff553"}

$ curl -i "$LDS/ns/Person/c1"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"name": "Blåbær ÆØÅ 日本"}
```

## The model over HTTP

LDS gives the model as JSON Schema (2020-12), one schema for each entity type. It is the schema that documents are
checked with.

| Request | Answer |
|---|---|
| `GET /{namespace}?schema` | 200, an array of the addresses of the schemas, in the order of the names of the entity types |
| `GET /{namespace}?schema=embed` | 200, an array of the schemas themselves, in the same order |
| `GET /{namespace}?schema=<another value>` | 400 `Not a valid resource. ...` |
| `GET /{namespace}/{Entity}?schema` | 200, the schema of the entity type. The value of `schema` is not read |
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

- With the defaults LDS does not keep the transaction log (`txlog.rawdata.provider=discard`), and does not know
  the last write of a source. The answer is `409 Conflict`, `Content-Type: text/plain`, with
  `No transaction log is kept (txlog.rawdata.provider=discard), so the last source id of a source is not known`.
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

{"saga-execution-id": "01a20fdf-fa51-74b1-9f58-23883f06ff7d"}

$ curl -i "$LDS/source/A"
HTTP/1.1 409 Conflict
Content-Type: text/plain

No transaction log is kept (txlog.rawdata.provider=discard), so the last source id of a source is not known
```

On an LDS started with `txlog.rawdata.provider=memory` (the log is kept until LDS stops) and
`txlog.split.sources=true`:

```console
$ curl -i -X PUT "$LDS/ns/Person/s1?source=A&sourceId=a1" -H "$J" -d '{"name": "S1"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fe4-44d7-71eb-981b-c4384c059766"}

$ curl -i -X PUT "$LDS/ns/Person/s2?source=B&sourceId=b1" -H "$J" -d '{"name": "S2"}'
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fe4-4502-7ac0-a65e-57fd2bd20278"}

$ curl -i -X PUT "$LDS/ns/Person/s1/address?source=A&sourceId=a2" -H "$J" -d '{"city": "Oslo"}'
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fe4-451e-737a-879e-2c73f67abfdc"}

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
  `Content-Type,Authorization`) and `LDS-Searchable`, the header of a write that asked to be searchable.
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
Access-Control-Expose-Headers: Authorization,Content-Type,LDS-Searchable
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
Access-Control-Allow-Headers: Content-Type,Authorization
Access-Control-Max-Age: 900
Access-Control-Allow-Methods: PUT,HEAD,POST,DELETE,GET
```

`Access-Control-Max-Age` is `http.cors.allow.max-age`, in seconds. The order of the names in
`Access-Control-Allow-Headers` and `Access-Control-Allow-Methods` is not fixed: it changes from one start of LDS
to the next.

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
Access-Control-Expose-Headers: Authorization,Content-Type,LDS-Searchable

$ curl -i -H 'Origin: https://other.example' "$LDS/ping"
HTTP/1.1 200 OK
```

## Health

| Address | Answer | What it checks |
|---|---|---|
| `/ping` | 200, no body | Nothing. LDS answers HTTP. |
| `/health/alive` | 200, no body | Nothing. The same as `/ping`. |
| `/health/ready` | 200 or 503, `application/json; charset=utf-8` | Whether LDS can take writes: it asks each part a write needs, at the same time, and gives each at most 2 seconds. |

`/health/ready` asks the persistence provider (`persistence`), the saga log (`sagaLog`), the transaction log
(`transactionLog`), and, when search is on, the search index (`search`) and the index queue (`indexQueue`). The
answer has the state of LDS in `status`, and in `checks` an entry for each part: its state, the milliseconds it
took to answer, what it told, and `error` when it failed. The state is one of:

| `status` | Status code | When |
|---|---|---|
| `UP` | 200 | Every part answers. |
| `DEGRADED` | 200 | LDS takes writes, but the search index does not answer, or the oldest change in the index queue has waited more than a minute. |
| `DOWN` | 503 | A part that writes need does not answer: the persistence provider, the saga log, the transaction log or the index queue. |

```console
$ curl -i "$LDS/ping"
HTTP/1.1 200 OK

$ curl -i "$LDS/health/alive"
HTTP/1.1 200 OK

$ curl -i "$LDS/health/ready"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 3},
    "sagaLog": {"status": "UP", "millis": 4, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 0, "keepsMessages": false}
  }
}
```

The LDS with search of [When search finds a write: searchable](#when-search-finds-a-write-searchable), after Solr
was stopped:

```console
$ curl -i "$LDS/health/ready"
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "status": "DEGRADED",
  "checks": {
    "persistence": {"status": "UP", "millis": 3},
    "sagaLog": {"status": "UP", "millis": 0, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 0, "keepsMessages": false},
    "search": {"status": "DEGRADED", "millis": 2000, "error": "no answer within 2000 ms"},
    "indexQueue": {
      "status": "UP",
      "millis": 0,
      "durable": false,
      "updates": 1,
      "oldest": "2026-11-24T23:36:54.201672013Z"
    }
  }
}
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
Access-Control-Expose-Headers: Authorization,Content-Type,LDS-Searchable
```

The 503 has the CORS headers when the origin is allowed, so a page in a browser can read it. A preflight
request is still answered 204. When LDS has stopped, a request gets no connection.

LDS waits at most 10 seconds for the requests it has received. A request that is not completed by then loses
its connection without an answer. [Operations](operations.md) describes the whole order of a stop.

## Every query parameter

| Parameter | On | Meaning | Default |
|---|---|---|---|
| `timestamp` | every request to a document, a part or a link | The time of an as-of read, or the valid time of the new version. See [The time of a request: timestamp](#the-time-of-a-request-timestamp). | the time of the request |
| `t` | the same | Another name for `timestamp`, used when `timestamp` is not given. |  |
| `timeline` | `GET` of one document | Every version of the document. A value is not needed. | not given |
| `sync` | `PUT`, `POST`, `DELETE` | Whether LDS waits for the write before it answers. See [When LDS answers a write: sync](#when-lds-answers-a-write-sync). | waits |
| `searchable` | `PUT`, `POST`, `DELETE` | `searchable=true`: LDS answers when search finds the write, at most 10 seconds, with the header `LDS-Searchable`. See [When search finds a write: searchable](#when-search-finds-a-write-searchable). | not given |
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
| 400 | The address is not valid: the namespace, the entity type, a field, an id that is empty or breaks a rule of [Ids and encoding](#ids-and-encoding), the form of a link address, an escape that is not one. The `timestamp` cannot be read. The body is empty or cannot be read as JSON, or the address of a write has no id. A path of a value is too long, or a value is in too many arrays. The document is not valid. The method is not one the address has. |
| 404 | There is no document at the time of the request, or it is deleted then: for a read of a document, for a timeline (no version at all), for every method on a part, and for `GET`, `PUT` and `POST` of a link. A link that the document does not have (`GET`). The address `/`, and an address outside `http.prefix`. |
| 409 | `GET /source/{source}` when no transaction log is kept. |
| 415 | The content type of a body is not JSON. |
| 500 | The saga of a write failed while LDS waited, or the write waited 10 seconds for a place among the writes LDS carries out at one time. Also the cases marked "Not finished" in this document: a `source` that cannot be the name of a log, the name of a type that is not an entity type. |
| 503 | LDS is stopping. A write waited 30 seconds for its turn, see [One write at a time to a document](#one-write-at-a-time-to-a-document). `/health/ready`: a part that writes need does not answer. |

A 400, a 409 and a 415 have `Content-Type: text/plain` and a text that says what is wrong; the 400 without a text,
for a letter outside ASCII or an escape that is not one, is in [Ids and encoding](#ids-and-encoding). A 404 and a
500 have no body. A 503 has no body while LDS stops, a text when a write waited too long for its turn, and the
JSON of [Health](#health) from `/health/ready`.
