---
title: "The REST API"
description: "How a request becomes a read or a write of a document, a part of it, or a link."
order: 12
section: Core
---

# Core: the REST API of documents

This document describes the code in `linked-data-store-core` that answers REST requests for documents: how a
request path becomes a resource, the three handlers, the checks of a write (the lock of its document, its body, its
ids and limits, [schema validation](../glossary.md#schema-validation)), and how a write is answered. It is for
developers and maintainers who change that code. What a user of the API needs is in
[the REST API](../user/rest-api.md).

## What this part is for

A REST request names a [document](../glossary.md#document), a part of a document (an
[embedded object](../glossary.md#embedded-object) or a value, by its [path](../glossary.md#path)), or a
[link](../glossary.md#link) from a document. This part finds out which of the three it is, reads what is stored,
and for a write builds the whole new document, checks it and hands it to a [saga](../glossary.md#saga). It does
not store anything itself. Storing is done by the saga ([the write path](core-write-path.md)).

The packages are `io.descoped.lds.core.controller`, `io.descoped.lds.core.domain` and
`io.descoped.lds.core.validation`. The module does not export them (`module-info.java` exports
`io.descoped.lds.core`, `io.descoped.lds.core.lifecycle` and `io.descoped.lds.test.server` only).

## Layout

| Class | Responsibility |
|---|---|
| `NamespaceController` (`core.controller`) | The way in for every path that is not a fixed one: the namespace, `?schema` on it, `/source/...`. Holds the one `DocumentLocks` of the application. |
| `DataController` (`core.controller`) | Moves the request to a worker thread, reads the time of the request, builds the `ResourceContext`, takes the lock of the document for a write, and picks the handler. |
| `ResourceContext` (`domain.resource`) | The request path, checked against the model: the namespace, a chain of `ResourceElement` and the time. Says which kind of resource the path is, and checks an id (`whatIsWrongWithId`). Also walks a document along the chain (`subTree`, `referenceToExists`, `navigateAndCreateJson`). |
| `ResourceElement` (`domain.resource`) | One step of the path: the name, the element of the specification it is, an id (of the document for the first step, the link `/Entity/id` for the last step of a reference) and the next step. |
| `ResourceException` (`domain.resource`) | A path that the model does not have, or an id LDS does not take. Its message is the body of the 400 answer. |
| `ManagedResourceHandler` (`domain.managed`) | The whole document: read one, read all of an entity type, read the versions, write, delete, and give out the JSON Schema of the entity type. |
| `EmbeddedResourceHandler` (`domain.embedded`) | A part of a document: read it, replace it, remove it. |
| `ReferenceResourceHandler` (`domain.reference`) | One link of a document: ask whether it is there, add it, remove it. |
| `ReferenceJsonHelper` (`domain.reference`) | Puts a link into a document and takes it out, for a single link and for an array of links. |
| `DocumentLocks` (`domain`) | One write at a time to a document. |
| `AsyncAnswer` (`domain`) | Reads the body of a request as it comes, and answers a write when its saga (and its wait for search) has completed. |
| `SyncParameter` (`domain`) | Whether a write is answered when it is done: the query parameter `sync`. |
| `SearchWait` (`domain`) | The wait of a write that asks to be searchable (`searchable=true`): [search](search.md#a-write-that-asks-to-be-searchable). |
| `NullFields` (`domain`) | Removes every field whose value is `null` from a document that is written. |
| `BodyParser`, `BodyException` (`domain`) | Reads the body of a request into a Jackson tree, by the content type, and says why it cannot. |
| `ResourceJsonTraversalTuple` (`domain.resource`) | The last step of a path and the object of the document that holds it, as `navigateAndCreateJson` gives them to its caller. |
| `LinkedDocumentValidator` (`validation`) | Says whether a document may be written: the JSON Schema of the entity type, then the links. It throws `LinkedDocumentValidationException`. |
| `Limits`, `IdOrder` (the persistence API, `io.descoped.lds.api.persistence`) | The limits of ids, names and paths in bytes of UTF-8, and the order of ids. |

A new `DataController`, a new handler and a new `ResourceContext` are made for every request; a write without a
`timestamp` makes a second `ResourceContext` when it has the lock of its document (see
[The time of the request](#the-time-of-the-request)). They hold no state between requests; the locks are held by
`NamespaceController`.

## From a request to a resource

### The way in

`UndertowApplication` puts `NamespaceController` on the path `/`, behind the exact paths `/ping`,
`/health/alive` and `/health/ready`, and behind `/graphql` and `/graphiql` when `graphql.enabled` is true and
`graphql.schema` is not empty. Without a GraphQL schema those two paths reach `NamespaceController` and are
answered as a namespace that is not served. `NamespaceController` looks at the path, in this order:

- A path of one character or less is answered 404 with no body.
- The path is exactly the namespace and the first value of `schema` is blank (`/<namespace>?schema`): the list
  of the addresses of the schemas is answered there, sorted by entity type:
  `["/ns/Company?schema","/ns/Person?schema"]`.
- The path is exactly the namespace and one value of `schema` is `embed`: the JSON Schemas of every entity type
  are answered there, in one JSON array sorted by the name of the entity type. Both are
  `application/json; charset=utf-8`, are answered for every method, and are not answered for `/<namespace>/?schema`.
- A path that is the namespace, or starts with it and a `/`, goes to a new `DataController`.
- A path that starts with `/source/` goes to `SourceHandler` (the [transaction log](../glossary.md#transaction-log),
  not this part).
- Anything else is answered 400 with `Unsupported namespace: "<first segment>"`.

`CORSHandler` is in front of all of this ([the application](core-application.md)). An OPTIONS request that is a
preflight its policy allows is answered 204 by it and does not reach this part: an `Origin` that
`http.cors.allow.origin` matches, an `Access-Control-Request-Method` in `http.cors.allow.methods` (by default
`POST,GET,PUT,DELETE,HEAD`), and no requested header outside `http.cors.allow.header`. Any other OPTIONS request
goes on to the handler, which answers it 400 (see [The handlers](#the-handlers)): a preflight for `PATCH` got
`Unsupported managed resource method: OPTIONS`.

The namespace is the value of `namespace.default`. REST serves this one namespace. `ResourceContext` takes the
namespace from the path and gives it to the store with every read and write.

`DataController.handleRequest` first calls `exchange.dispatch(this)` when it is on an I/O thread, so what follows
runs on a worker thread of Undertow. A read (GET) reads the store on that thread and waits for it (`blockingGet`,
`blockingIterable`). A write (any other method) holds no thread while it waits: for the lock of its document, for
its body and for its saga (see [A write](#a-write)).

`NamespaceController` reads the path with `exchange.getRelativePath()`, so a value of `http.prefix` is not part
of the path that is checked. `DataController` gives `ResourceContext` the same path as it was sent, not decoded
(`DataController.rawRelativePath`), so that each segment is decoded once. It takes the last segments of the
request URI, which Undertow keeps as it was sent. With the options of Undertow 2.4 that LDS leaves at their defaults
(`RequestParser`, `URLUtils.decode`):

- Each segment of the relative path is one segment of the URI: an encoded `/` or `\` (`%2F`, `%5C`) is left
  encoded, and does not split a segment.
- The path parameters (from `;` to the next `/`) are left out of each segment.
- A request line in absolute form (`GET http://host/path`, as a proxy sends it) has its scheme and host left out.
- A byte outside ASCII, and a `%` that two hexadecimal digits do not follow, are refused by Undertow with 400 and no
  body before a handler runs.

### The time of the request

`DataController` reads the time before anything else:

1. The parameter `timestamp`. When the request has no `timestamp`, the parameter `t`. The names are in lower
   case. When the parameter is given more than once, the last value is used.
2. No parameter: the time is now, in UTC. For a write it is taken again when the write has the lock of its
   document, from `DocumentLocks.timeOfTurn`, and a second `ResourceContext` is made with it (see below).
3. The value is read with `RequestTime.parse`: `ZonedDateTime.parse`, which needs a date, the hour and the minute,
   and an offset or `Z` (`2018-12-06T11:05:31.000+01:00`). Seconds may be left out. A zone in brackets after the
   offset is accepted. When that fails, the value is decoded as a URL once and read again. A `+` in the offset is
   sent as `%2B`: a `+` that is not encoded arrives as a space, and the value is refused.
4. When both fail the answer is 400 with a fixed text (see [Where an error answer comes
   from](#where-an-error-answer-comes-from)). A parameter with no value (`?timestamp=`, `?timestamp`) fails too.
5. The time is moved to UTC (`Etc/UTC`).

This one time is used for the whole request. A read is an [as-of read](../glossary.md#as-of-read): it gives the
document as it is at that time. A write makes the [version](../glossary.md#version) whose
[valid time](../glossary.md#valid-time) is that time. So a write without `timestamp` makes a version valid from
the moment it got the lock of its document, and a write with a `timestamp` in the past or the future makes a
version there.

`DocumentLocks.timeOfTurn` gives the time of a write without `timestamp`: now, cut to the microsecond, or a
microsecond after the turn before it when the clock has not moved on since or has gone back. The turns of every
document of the process share this one clock, so the version of a write comes after the version of the write before
it. When the clock is more than a second behind the last turn, a WARN says so once, and an INFO when it has caught
up. The memory and the PostgreSQL providers keep a version to the millisecond
([memory](persistence-memory.md#not-finished), [PostgreSQL](persistence-postgres.md#versions)), so with them two
writes without `timestamp` to one document within one millisecond make one version: the later replaces the earlier.

### The path

`ResourceContext.createResourceContext(specification, path, timestamp)` splits the path at `/` with
`String.split`, which drops empty segments at the end, so a `/` at the end of a path is ignored. Every segment
is decoded once (`urlDecode`): each `%XX` is a byte of UTF-8, and every other character stands for itself, a `+`
too. Bytes that are not UTF-8 are refused (`Not UTF-8 in the path: "<segment>"`). `urlDecode` also has a text for a
`%` that two hexadecimal digits do not follow (`Not a valid escape in the path: "<segment>". Write a '%' as %25`),
which only a direct call meets: over HTTP, Undertow refuses such a path first. The path is
`/{namespace}/{Entity}/{id}/{field}/.../{Entity}/{id}`:

1. Fewer than two segments: `ResourceException`.
2. The second segment must be an [entity type](../glossary.md#entity-type) of the model (a property of the root
   element of the [specification](../glossary.md#specification), `Specification`).
3. The third segment, when there is one, is the [id](../glossary.md#id). An id that is empty or only spaces is
   refused, and so is one that `whatIsWrongWithId` names (see [Ids and limits](#ids-and-limits)).
4. Every segment after the id must be a property of the element before it in the specification
   (`navigateSpecAndCreateResourceContext`).
5. When a segment is a link field (`SpecificationElementType.REF`):
   - nothing after it: the path ends at the field, which is an embedded resource whose value is the link or links;
   - one segment after it: refused, an id is missing;
   - two segments after it: the entity type and the id of the target. The entity type must be one the field may
     link to (`getRefTypes`), and the id is checked with `whatIsWrongWithId` only: an id of only spaces, refused
     for a document, passes here (`/ns/Person/ola/friends/Person/%20` names the link `/Person/ `). The last
     `ResourceElement` gets the id `/Entity/id`;
   - more than two: refused, a path does not go through a link.

The result is a chain of `ResourceElement`. `getResourceType` reads the kind from it:

| Kind | The chain | Example |
|---|---|---|
| `MANAGED` | Only the first element. The id may be missing (all documents of the entity type). | `/ns/Person/ola`, `/ns/Person` |
| `EMBEDDED` | More elements, and the last has no id. | `/ns/Person/ola/address` |
| `REFERENCE` | More elements, and the last has an id. | `/ns/Person/ola/employer/Company/acme` |

`DataController` asks `isReference()` first, then `isManaged()`, then `isEmbedded()`, and makes the handler. It
ends with a 404 and the text `Unsupported resource path: <path>`; no request gets there, as every `ResourceContext`
is one of the three kinds.

The names in the path are checked against the model, and only names of the model pass. The id is given to the
store as a value. `DataController` catches `ResourceException` and answers 400 with its text.

### Ids and limits

`ResourceContext.whatIsWrongWithId` says what is wrong with an id, for the id of a document and for the id a link
names, in a path or in a document (`LinkedDocumentValidator`), so that every document a link names can be read
through REST:

| The id | The text of the 400 |
|---|---|
| more than 1024 bytes in UTF-8 (`Limits.ID_BYTES`) | `An id is at most 1024 bytes in UTF-8` |
| holds a `/` (also as `%2F`) | `An id cannot hold a '/': "<id>"` |
| holds a control character | `An id cannot hold a control character` |
| holds half of a character (an unpaired surrogate) | `An id cannot hold half of a character (an unpaired surrogate)` |

The [limits](../glossary.md#limits) are one set for every persistence provider, in bytes of UTF-8 (`Limits` in the
persistence API); why they are what they are is in [the persistence API](persistence-api.md#limits). Core refuses
what goes past them before a store sees it: an id as above, and in a document that is written (`Limits.overLimit`,
after the null fields are removed):

| What | The text of the 400 |
|---|---|
| the path of a value as the stores keep it (`$`, `.` and the name of each field, `[]` for each array) longer than 1024 bytes (`PATH_BYTES`) | `The path of a value is at most 1024 bytes in UTF-8: <the path, its first 80 characters and ...>` |
| a value in more than 32 arrays (`ARRAY_LEVELS`) | `A value is in at most 32 arrays: <the path>` |

The namespace (64 bytes) and the names of the entity types (128 bytes) are checked at start
([the model](core-model.md#the-limits-of-names)).

REST orders no ids itself: a list of documents comes in the order the store gives, the
[order of ids](../glossary.md#order-of-ids).

Run against LDS in memory with the example model, on port 19106 (`$long` is 1025 letters `a`):

```console
$ curl -s -X PUT "http://localhost:19106/ns/Person/$long" -H 'Content-Type: application/json' -d '{"name": "P"}'
An id is at most 1024 bytes in UTF-8
$ curl -s -X PUT 'http://localhost:19106/ns/Person/a%2Fb' -H 'Content-Type: application/json' -d '{"name": "P"}'
An id cannot hold a '/': "a/b"
$ curl -s -X PUT 'http://localhost:19106/ns/Person/a%ED%A0%80' -H 'Content-Type: application/json' -d '{"name": "P"}'
Not UTF-8 in the path: "a%ED%A0%80"
$ curl -s -X PUT 'http://localhost:19106/ns/Person/p2' -H 'Content-Type: application/json' \
       -d '{"name": "P", "employer": "/Company/x\ud800"}'
Schema validation error: A link names an id that is not valid: An id cannot hold half of a character (an unpaired
surrogate). Navigation: employer
```

The last answer is one line. An id of 512 letters `é` (1024 bytes) was written (201), and `/ns/Person/a+b` is the
id `a+b`.

## The handlers

Each handler looks at the method. GET, PUT, POST and DELETE are answered. PUT and POST run the same code. Any
other method, HEAD and OPTIONS included, is answered 400 with `Unsupported managed resource method: <METHOD>`
(or `embedded`, or `reference`). An OPTIONS request that `CORSHandler` answers as a preflight does not get this far
(see above).

A handler does not read the `Accept` header. A document, a list, a timeline, a part and a saga id are answered as
JSON, and an error as `text/plain` (see [Where an error answer comes from](#where-an-error-answer-comes-from)). A
reference GET, a reference PUT of a link that is there already, and a DELETE of a document or of a link that waited
for its saga are answered with no body.

The examples below were run against LDS in memory with the example model
(`linked-data-store-server/schemas/schema.graphql`), on port 19106. The headers `Connection`, `Content-Length` and
`Date` are left out.

### Managed: the whole document

| Request | What happens | Answer |
|---|---|---|
| GET, no id, `?schema` | `SchemaRepository.getJsonSchema().getSchemaJson(entity)` | 200, the JSON Schema of the entity |
| GET, no id | `readDocuments` at the time, unbounded. Deleted documents are left out. | 200, an array of documents in the order of their ids, `[]` when there are none |
| GET, id, `?timeline` | `readDocumentVersions`, unbounded. The time of the request is not used. | 200, an array of `{"version": ..., "document": ...}`, oldest first; 404 when there is no version |
| GET, id | `readDocument` at the time | 200, the document; 404 when there is none at that time or it is deleted |
| PUT or POST | See [A write](#a-write). The body is the whole document. | 201 `{"saga-execution-id":"..."}` |
| DELETE | The saga `Delete managed resource`. The store marks the document deleted from the time. | 204 when the write waited for its saga, else 202 `{"saga-execution-id":"..."}` |

`version` in the [timeline](../glossary.md#timeline) is `ZonedDateTime.toString()` of the UTC time:
`2020-01-01T00:00Z[Etc/UTC]`. `timeline` and `schema` are looked for as names only (`containsKey`);
`?timeline=false` gives the timeline too. `schema` is looked at only when the path has no id.

`putManaged` checks in this order: an empty body (400 `Payload was empty!`), a missing id (400 `Id was empty!`),
then `BodyParser`, then the null fields are removed, then the limits, then the validator.

```sh
curl -i -X PUT 'http://localhost:19106/ns/Person/ola?timestamp=2020-01-01T00:00:00Z' \
     -H 'Content-Type: application/json' \
     -d '{
       "name": "Ola",
       "employer": "/Company/acme",
       "born": null
     }'
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a20fe0-3d81-7a7e-a190-c2947f057f1e"}
```

```sh
curl -i 'http://localhost:19106/ns/Person/ola?timestamp=2021-06-01T00:00:00Z'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"employer": "/Company/acme", "name": "Ola"}
```

`born` was `null`, and is not there: see [A null is a field that is not there](#a-null-is-a-field-that-is-not-there).

A DELETE does not look for the document first. A DELETE of a document that does not exist is answered 204 as
well, and the store gets a deleted version, a [tombstone](../glossary.md#tombstone), at that time. With the memory
provider the timeline shows a tombstone as `{"version":"2020-01-01T00:00Z[Etc/UTC]","document":{}}`.

### Embedded: a part of a document

All three methods first read the document at the time of the request. When there is none, or it is deleted, the
answer is 404 with no body. For a PUT that is before the body is parsed, so a body that cannot be read is
answered 404 when there is no document.

| Method | What happens | Answer |
|---|---|---|
| GET | `ResourceContext.subTree` walks the document along the chain. | 200. An object or an array as it is. A simple value inside an array: `["Ola"]`. A part the document does not have: `[null]`. |
| PUT or POST | The body is the new value of the part. `mergeJson` sets it in the document; `navigateAndCreateJson` makes the objects on the way that the document does not have. The null fields are removed, the limits checked, and the whole document is validated, then written with the saga `Create or update managed resource`. | 200 `{"saga-execution-id":"..."}`; 400 or 415 when the body cannot be read (`BodyException`, as for the managed PUT); 400 when the document goes past the limits or is not valid; 404 |
| DELETE | The field is removed from the document and the document is written. It is not validated. | 200 `{"saga-execution-id":"..."}`; 404 |

The body `null` removes the part, as a field that is `null` in a document is not there:
`PUT /ns/Person/ola/born` with `null` wrote a version without `born`, and `GET /ns/Person/ola/born` at that time
answered `[null]`.

```sh
curl -i 'http://localhost:19106/ns/Person/ola/name'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["Ola"]
```

```sh
curl -i -X PUT 'http://localhost:19106/ns/Person/ola/born?timestamp=2022-01-01T00:00:00Z' \
     -H 'Content-Type: application/json' -d '"1990-05-17T00:00:00Z"'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fe0-3dc6-7b39-94bf-a738b6b549e9"}
```

A write to a part makes a new version of the whole document, valid from the time of the request. It is built
from the document as it is at that time (an as-of read), not from the newest version.

### Reference: one link

| Method | What happens | Answer |
|---|---|---|
| GET | `ResourceContext.referenceToExists`: the field holds the link, or the array of the field has it. | 200 when the link is there; 404 when it is not, or there is no document. No body. |
| PUT or POST | 404 when there is no document at the time. When the link is there, nothing is written. Else `ReferenceJsonHelper.createReferenceJson`: an array field gets the link added, a single field gets it as its value, in place of what it held. Then the saga `Create or update managed resource`. The body of the request is read and not used. | 200 with no body when the link was there; 200 `{"saga-execution-id":"..."}` when it was written; 404 |
| DELETE | When there is no document, or the link is not there, nothing is written. Else `ReferenceJsonHelper.deleteReferenceJson`: the first equal element is taken out of the array, or the single field is removed. Then the same saga. | 200. No body when nothing was written or the write waited for its saga, else `{"saga-execution-id":"..."}`. |

The document is not validated after a link is added or removed. The path was checked against the model, so the
link is to an entity type the field may link to, and its id was checked. `navigateAndCreateJson` makes the objects
on the way to the field that the document does not have. An array that becomes empty stays in the document as `[]`.

```sh
curl -i -X PUT 'http://localhost:19106/ns/Person/ola/friends/Person/kari?timestamp=2023-01-01T00:00:00Z'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a20fe0-3dda-714a-a314-15de95d0342e"}
```

```sh
curl -s 'http://localhost:19106/ns/Person/ola?timeline'
```

```json
[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {"employer": "/Company/acme", "name": "Ola"}
  },
  {
    "version": "2022-01-01T00:00Z[Etc/UTC]",
    "document": {
      "born": "1990-05-17T00:00:00Z",
      "employer": "/Company/acme",
      "name": "Ola"
    }
  },
  {
    "version": "2023-01-01T00:00Z[Etc/UTC]",
    "document": {
      "born": "1990-05-17T00:00:00Z",
      "employer": "/Company/acme",
      "name": "Ola",
      "friends": ["/Person/kari"]
    }
  }
]
```

There is no document `Person/kari` in this LDS. A link may point at a document that does not exist (a dangling
reference), and no handler looks for the target.

## A write

Every write ends the same way, in all three handlers. How the saga runs and when the answer is sent is in
[the write path](core-write-path.md#from-a-request-to-an-answer); this is what the REST part does.

### One write at a time to a document

`DataController` takes the [lock of the document](../glossary.md#document-lock) of every write (every method but
GET, HEAD and OPTIONS) before the handler reads anything, and the write keeps it until its saga has completed; how a
write waits for it without a thread, for at most 30 seconds before a 503, is in
[the write path](core-write-path.md#the-lock-of-a-document).

### The body

A write reads its body as it comes (`AsyncAnswer.withBody`, `receiveFullString` in UTF-8), and goes on on a worker
thread when it has all come. `BodyParser.deserializeBody(contentType, body)` reads it. The content type is the
first `Content-Type` header, or `application/json` when the request has none. Apache Tika parses it
(`MediaType.parse`), so parameters such as `; charset=utf-8` and the case of the letters do not matter.

- `application/json`: read with Jackson 3. A text with something after the document is refused.
- `application/msgpack`, `application/x-msgpack`: handed to a `MessagePackMapper`, which cannot read a text (see
  [Not finished](#not-finished)).
- Any other type, and a value that is no media type: `BodyException` 415, `Unsupported Content-Type: <type>. Send
  the body as JSON, with Content-Type: application/json`. `curl -d` without `-H` sends
  `application/x-www-form-urlencoded`, and is refused so.
- A body of no characters or of only spaces: `BodyException` 400, `Payload was empty!`.
- A body Jackson cannot read: `BodyException` 400, `The body cannot be read at line <l>, column <c>: <reason>.` and
  the same advice. The reason is Jackson's own message (`getOriginalMessage`) without the place of the start of an
  object that Jackson adds, whose source is redacted; `BodyParser.describe` makes this text, and the GraphQL handler
  uses it too.

The body is always read as UTF-8. A `charset` in the header changes nothing. The managed and the embedded PUT
answer a `BodyException` with its status and its text as `text/plain`, and nothing is written.

```console
$ curl -s -X PUT 'http://localhost:19106/ns/Person/p1' -H 'Content-Type: text/plain' -d '{"name": "P"}'
Unsupported Content-Type: text/plain. Send the body as JSON, with Content-Type: application/json
$ curl -s -X PUT 'http://localhost:19106/ns/Person/p1' -H 'Content-Type: application/json' -d '{"name": "P",'
The body cannot be read at line 1, column 14: Unexpected end-of-input within/between Object entries. Send the body
as JSON, with Content-Type: application/json
```

The second answer is one line.

### A null is a field that is not there

Before a document is checked and stored, `NullFields.removeFrom` removes every field whose value is `null`, in the
document and in every object inside it, also an object in an array. A `null` in an array is a value of the array,
and is kept. So a document written with `"born": null` is read back without `born`. The managed and the embedded
PUT do this; the embedded DELETE and the reference handler change a document read from the store, which has no null
field.

A document is stored as it is given otherwise, also with fields the model does not have:

```console
$ curl -s -X PUT 'http://localhost:19106/ns/Person/p4?timestamp=2020-01-01T00:00:00Z' \
       -H 'Content-Type: application/json' \
       -d '{"name": "P", "nick": "Pelle", "tags": [1, null, [2]], "address": {"city": "Bergen", "zip": null}}' \
       -o /dev/null -w '%{http_code}\n'
201
$ curl -s 'http://localhost:19106/ns/Person/p4'
{"name":"P","nick":"Pelle","address":{"city":"Bergen"},"tags":[1,null,[2]]}
```

### The saga

1. The handler has the whole document as a Jackson tree. For a managed PUT it is the body. For the others it is
   the stored document with the change made in it.
2. `SyncParameter.waits` reads the query: the write waits for its saga unless a value of `sync` is `false` (in any
   case), and always when it asks to be searchable (`searchable=true`, a
   [searchable write](../glossary.md#searchable-write)). It is read the same way in all three handlers.
3. The saga is taken from `SagaRepository` by name: `SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE`, or
   `SAGA_DELETE_MANAGED_RESOURCE` for the DELETE of a whole document.
4. A `SagaInput` is made with a new transaction id, the method, the document key with the time as its valid time,
   the [source and source id](../glossary.md#source-and-source-id) from the query when given, and the document (none
   for a DELETE): [the saga input](core-write-path.md#the-saga-input).
5. `SagaExecutionCoordinator.handoff(sync, ...)` starts the saga and keeps the lock of the document until the saga
   has completed ([the handoff](core-write-path.md#the-handoff)).
6. `AsyncAnswer.afterSaga` answers the write when the future of `handoff` completes, after the wait for search when
   the write asked for it; `saga-execution-id` in the answer is the transaction id
   ([the answer](core-write-path.md#the-answer)).

A write that waits is a [synchronous write](../glossary.md#synchronous-write), and one with `sync=false` is answered
when its saga is in its [saga log](../glossary.md#saga-log) ([the answer](core-write-path.md#the-answer)).

A saga that fails while the write waits is answered 500 with no body
([a saga that fails](core-write-path.md#a-saga-that-fails)).

The query parameter `saga` is given to `SagaCommands.getSagaAdminParameterCommands`. It makes a step of the saga
fail, and is used only when `saga.commands.enabled` is true ([the saga commands](core-write-path.md#the-saga-commands)).

A write with the same id and the same valid time as a version that exists replaces that version. That is what makes a
repeated PUT with a `timestamp` safe.

## Validation

The managed PUT and the embedded PUT call `LinkedDocumentValidator.validate(entity, document)` on the whole
document, after the null fields are removed and the limits are checked, and answer 400 with
`Schema validation error: ` and the message; what it checks, in which order, is in
[the model](core-model.md#validation-of-a-document).

## Where an error answer comes from

| Status | Body | From |
|---|---|---|
| 400 | `Unsupported namespace: "<first segment>"` | `NamespaceController` |
| 400 | `The 'timestamp' query-parameter must follow the ISO-8601 standard. Example of a valid formatted timestamp is '2018-12-06T11:05:31.000+01:00'` | `DataController` |
| 400 | `Not a valid resource. The resource path must contain at least namespace, name, and id of managed resource` | `ResourceContext`, fewer than two segments |
| 400 | `Not a managed resource name: "<name>"` | `ResourceContext` |
| 400 | `Managed resource id cannot be empty.` | `ResourceContext`, the id of a document that is empty or only spaces |
| 400 | the texts of [Ids and limits](#ids-and-limits), and `Not UTF-8 in the path: "<segment>"` | `ResourceContext`; the limits of a document from the managed and the embedded PUT |
| 400 | `Not a valid path element: "<name>"` | `ResourceContext` |
| 400 | `Reference resource path-element: "<field>" must be part of pattern ending like: "/<field>/<managed-domain>/<id>"` | `ResourceContext` |
| 400 | `Resource path cannot navigate through REF property: "<field>"` | `ResourceContext` |
| 400 | `Reference resource does not support link to managed-domain "<entity>". Must be one of: [<entities>]` | `ResourceContext` |
| 400 | `Payload was empty!` | `ManagedResourceHandler`, a PUT with an empty body; `BodyParser` |
| 400 | `The body cannot be read at line <l>, column <c>: <reason>. Send the body as JSON, with Content-Type: application/json` | `BodyParser` |
| 400 | `Id was empty!` | `ManagedResourceHandler`, PUT or DELETE without an id |
| 400 | `Schema validation error: <message>` | the managed and the embedded PUT |
| 400 | `Unsupported <kind> resource method: <METHOD>` | each handler |
| 400 | none | Undertow: a byte outside ASCII in the path, a `%` that two hexadecimal digits do not follow |
| 404 | none | no document, no version, no link; a path of one character or less (`NamespaceController`) |
| 415 | `Unsupported Content-Type: <type>. Send the body as JSON, with Content-Type: application/json` | `BodyParser` |
| 500 | none | a saga that fails while the write waits (`AsyncAnswer`); an exception that no handler catches |
| 500 | `Error: <message>` | `ManagedResourceHandler`, a PUT whose body cannot be received |
| 503 | `The document is being written by another request. Try again.` | `DataController`, the lock of the document not had within 30 seconds |
| 503 | none | `DataController`, a write that had waited for the lock when LDS is stopping and takes no more work; every request but a CORS preflight once the stop has begun (`UndertowApplication`, its `GracefulShutdownHandler`) |

The answers with a text are `text/plain`. The managed handler sets `Content-Type: application/json; charset=utf-8`
before it reads, so its 404 has that header and no body.

## Rules to keep

- **Time is UTC.** `DataController` moves the time of the request to UTC before anything uses it.
- **Names come from the model.** The entity type, every field of the path and the entity type of a link are
  looked up in the `Specification`. Keep it so: no name from a request reaches a store without that check.
- **One set of limits, in bytes of UTF-8.** Ids, paths and arrays are checked against `Limits` before a store sees
  them, and an id the same way in a path and in a link.
- **A handler does not write to the store.** It reads with `persistence.createTransaction(true)` in a
  try-with-resources block, and writes through a saga. A write that skips the saga skips the transaction log and
  the search index.
- **A write holds no thread while it waits.** It waits for its lock, its body and its saga as futures, and is
  answered from their completion (`DocumentLocks`, `AsyncAnswer`). Keep the `dispatch` in `DataController`: a
  read of the store never runs on an I/O thread. No blocking method and no method that returns a complete list is
  added to the persistence interface to make a handler shorter.
- **One write at a time to a document**, from before it reads the document to the end of its saga.
- **A null field is not there.** It is removed before the document is checked and stored.
- **No check that a link target exists.** Do not add one to the validator or to a handler.
- **Every call can be sent again.** A PUT of a link that is there writes nothing. A DELETE of a link or of a
  document that is not there is answered as a success. A PUT with a `timestamp` replaces the version at that
  time. A write without `timestamp` that is sent again makes one more version, at the time of the second
  request.
- **A text is taken out of a node with `JsonTools.text`** unless the node is known to be a text. It gives null
  for a node of another kind, where Jackson 3 itself throws.
- **The error texts are part of the API.** Tests compare them letter by letter: `DataControllerTest`,
  `NamespaceControllerTest`, `ManagedResourceHandlerTest`, `DocumentValidationTest`, `BodyParserTest`,
  `ResourceIdTest`.
- **The answer of the validator keeps its form**: the place in the document as LDS writes a path, then the message
  of the validator (`$.tags[1]: integer found, string expected`).

The rules of every part of LDS (UTC instants, one set of limits, the order of ids, lint) are
[the invariants](architecture.md#the-invariants).

## Configuration

The handlers read no configuration themselves. These keys decide what they are given:

| Key | Default | Meaning for this part |
|---|---|---|
| `namespace.default` | `ns` | The namespace REST serves. A leading `/` is added when it is missing (`NamespaceController`). |
| `graphql.schema`, `specification.schema` | see [the model](core-model.md) | The model. It decides which entity types, fields and links a path may name, and what a document is validated with. |
| `http.prefix` | `/` | A prefix in front of every path. The handlers do not see it. |
| `saga.commands.enabled` | `false` | Whether the query parameter `saga` is used. |
| `graphql.search.enabled` | `false` | With search, `searchable=true` waits for search ([search](search.md)). |

`ManagedResourceHandler` and `EmbeddedResourceHandler` log the method, the path and the body of a PUT at
TRACE, and a validation error at DEBUG. `ManagedResourceHandler` logs an empty body at ERROR. The other logs of a
write:

- WARN `A write of /ns/Person/ola waited PT30S for the lock of the document and was answered 503`
  (`DataController`).
- ERROR `A write of <path> failed`, with its cause, when the saga or the answer fails (`AsyncAnswer`).
- WARN with the exception when a body cannot be received (each of the three handlers).
- WARN once when the clock is more than a second behind the time of the last write, and INFO when it has caught up
  (`DocumentLocks`).

## Tests

The tests are in `linked-data-store-core/src/test/java/io/descoped/lds/core`.

| Test | What it holds |
|---|---|
| `domain/resource/ResourceContextTest` | Which kind a path is, for single links and arrays of links, at three depths; `navigateAndCreateJson` on an empty, a filled and a partly filled document. Its specification is built in the test (`SpecificationBuilder`). |
| `domain/resource/ResourceGraphqlContextTest` | The paths that are refused. It checks that a `ResourceException` is thrown, not its text. |
| `domain/resource/ResourceIdTest` | An id decoded once (`+`, `%25`, `%2B`, UTF-8), and the ids that are refused, with their texts: a bad escape, a `/`, a control character, half of a character, more than 1024 bytes; the same for the id of a link. |
| `domain/managed/ManagedResourceHandlerTest` | PUT and GET, DELETE (204, and 400 without an id), 404, `?schema`, the answer to a document that is not valid, a body that is not JSON (400) and another content type (415), ids with `+`, `%` and path parameters over HTTP and the ids that are refused, `?timeline`. |
| `domain/managed/ManagedTimeBasedVersioningTest` | Three versions read at five times; a `timestamp` that cannot be read is 400. |
| `domain/embedded/EmbeddedResourceHandlerTest` | GET of an array and of a missing part (`[null]`), PUT and DELETE of an object and an array, also two levels down, an empty body and one that is not JSON (400, nothing written), a part that takes the document over the limits (400, nothing written), a part written before it is answered when `sync` is not given, with a null field in it left out, and a part written as `null` removed. |
| `domain/reference/ReferenceResourceHandlerTest` | GET, PUT and DELETE of single links and links in arrays, PUT on a missing and on a deleted document (404), DELETE sent twice, a link to an entity type the field may not link to (400). |
| `domain/reference/ReferenceJsonHelperTest` | Adding, changing and removing a link in a document, without a server. |
| `domain/BodyParserTest` | JSON; an empty body, a body that is not JSON (400, with the texts) and another content type (415); that a MessagePack body is refused. |
| `domain/DocumentLocksTest` | Writes that wait get the lock in the order they asked; a write keeps it until its saga has completed; a write whose wait is over does not get it; a request answered before it got the lock never takes it. |
| `domain/AsyncAnswerTest` | A write whose saga fails, or whose answer fails, is answered 500 and lets the lock go. |
| `domain/NullFieldsTest` | A null field is removed at any depth, and a null in an array is kept. |
| `domain/SearchWaitTest` | The wait of a write that asks to be searchable ([search](search.md#tests)). |
| `validation/DocumentValidationTest` | The messages of the validator, date-times that are valid and not, links, nulls where a link could be. |
| `validation/LinkedDocumentValidatorTest` | Links to another entity type and links of another form are refused. |
| `controller/DataControllerTest`, `controller/NamespaceControllerTest`, `controller/NamespaceSchemaTest` | The way in: a path the model does not have, another namespace, `?schema` on the namespace. |
| `controller/RawRelativePathTest` | The relative path as it was sent: a prefix and path parameters left out, a request line in absolute form. |

`BodyParserTest`, `ResourceIdTest`, `DocumentValidationTest`, `DocumentLocksTest`, `AsyncAnswerTest`,
`NullFieldsTest`, `SearchWaitTest` and `RawRelativePathTest` are JUnit 6 with AssertJ. The others are TestNG. Those
that send requests take a server from `TestServerListener`: LDS in the JVM on a free port, in memory, with the
namespace `data` (`src/test/resources/application_test.properties`). The model of that server is the GraphQL schema
`src/test/resources/gqlschema/schema.graphql`, which has the entity types `contact` and `provisionagreement` among
others. The file also sets `specification.schema` to the JSON Schema files `spec/schemas/contact.json` and
`spec/schemas/provisionagreement.json`, but `UndertowApplication.initialize` (which `initializeUndertowApplication`
calls) does not read that key while `graphql.schema` is set. `ResourceGraphqlContextTest` reads the two JSON Schema
files itself. `TestClient.put` sends the header `Origin: localhost` and no `Content-Type` header, so those tests use
the default of the handlers; `TestClient.putAs` sends the content type it is given. A new test class that takes its
server from `TestServerListener` is TestNG and asserts with AssertJ; a test added to an existing class follows that
class.

Core is built and tested with the modules it needs by `./mvnw verify -pl linked-data-store-core -am`, and one
test class is run with `./mvnw test -pl linked-data-store-core -Dtest=<Class>`. The build needs JDK 27 and
Docker.

## Not finished

Not finished: a read waits for the store on a worker thread of Undertow. The GET of each handler, and the read of
the document inside a write of a part or a link, call `blockingGet` or `blockingIterable` on the persistence
interface. A write holds no thread; a read does.

Not finished: a path does not go into the elements of an array. `ResourceContext`, `ReferenceJsonHelper` and
`EmbeddedResourceHandler` have comments that say array-navigation is not supported, and
`ResourceJsonTraversalTuple` throws `UnsupportedOperationException("array-navigation not supported")` when the
parent of the last step is not an object. A part of an object that is an element of an array has no address:
`/ns/Company/big/about/languageText` is answered `Not a valid path element: "languageText"`.

Not finished: with a model from a GraphQL schema, a field whose type is another object type (not a list) has no
content in the JSON Schema LDS writes: `"address":{"displayName":""}`. Such a field has no properties in the
specification either. So `/ns/Person/ola/address/city` is answered `Not a valid path element: "city"`, the
content of `address` is not validated, and a link inside it is not known. The cause is where the JSON Schema is
written from the GraphQL schema, not in this part. With JSON Schema files that describe the object in place, the
same path works.

Not finished: a MessagePack body is never read. The body reaches `BodyParser` as a text, and MessagePack is not
text: the `MessagePackMapper` throws `UnsupportedOperationException`, and the answer is 400 `The body cannot be
read: Cannot create parser for character-based (not byte-based) source. Send the body as JSON, with Content-Type:
application/json`.
`BodyParserTest.aMessagePackBodyIsRefusedBecauseItIsHandedOverAsText` holds this as it is.

Not finished: the status of a managed PUT is 201 whether or not the document existed. A write of a part answers 200
with a saga id whether or not it waited. A reference DELETE and PUT answer 200 with or without a body.

Not finished: the embedded DELETE and the reference PUT and DELETE write the document without validating it. An
embedded DELETE of a field the schema requires is written. An embedded DELETE also writes a version when the
field was not there, and makes the objects on the way that the document does not have. With the JSON Schema
files of the tests as the model, `DELETE /ns/provisionagreement/d1/address/street` on a document without
`address` wrote a version with `"address":{}`, which that schema does not allow.

Not finished: the validator does not check a link field inside an object that is an element of an array
([the model](core-model.md#not-finished-and-known-to-be-wrong)).

Not finished: the read of all documents of an entity type and the read of the versions are not paged, and the
read of the versions takes no range of time. Both ask the store for everything (`Range.unbounded()`) and build
the answer in memory before it is sent.

Not finished: the form of the answer of an embedded GET depends on the value: an object or an array as it is, a
simple value in an array, `[null]` for nothing. A comment in the code says that one form for all is not
decided.

Not finished: `SagaInput` has a field `schema`, and every handler gives it the text `"TODO"`.

Not finished: the error callback of the read of the body in the embedded and the reference handler sends a text
and sets no status.

Not finished: `ResourceContext.subTree` throws `IllegalStateException` when the parent of the last step is a simple
value, and an embedded GET is then answered 500 with no body, as an exception that no handler catches is.

Not finished: a method that is not answered gets 400, not 405, and HEAD is one of them.

Not finished: six tests of the REST API are switched off in the source: five in `DataControllerTest` and one in
`NamespaceControllerTest`.

Not finished: REST reaches one namespace, the one in `namespace.default`. The stores keep the namespace with
every document, but no request can name another.
