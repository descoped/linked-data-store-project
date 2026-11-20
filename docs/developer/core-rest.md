# Core: the REST API of documents

This document describes the code in `linked-data-store-core` that answers REST requests for documents: how a
request path becomes a resource, the three handlers, validation and the reading of a request body. It is for
developers and maintainers who change that code. What a user of the API needs is in
[the REST API](../user/rest-api.md).

## What this part is for

A REST request names a document, a part of a document, or a link from a document. This part finds out which of
the three it is, reads what is stored, and for a write builds the whole new document, validates it and hands it
to a saga. It does not store anything itself. Storing is done by the saga
([the write path](core-write-path.md)).

The packages are `io.descoped.lds.core.domain` and `io.descoped.lds.core.validation`. The module does not export
them (`module-info.java` exports `io.descoped.lds.core` and `io.descoped.lds.test.server` only).

## Layout

| Class | Responsibility |
|---|---|
| `DataController` (`core.controller`) | The way in. Moves the request to a worker thread, reads the time of the request, builds the `ResourceContext` and picks the handler. |
| `ResourceContext` (`domain.resource`) | The request path, checked against the model: the namespace, a chain of `ResourceElement` and the time. Says which kind of resource the path is. Also walks a document along the chain (`subTree`, `referenceToExists`, `navigateAndCreateJson`). |
| `ResourceElement` (`domain.resource`) | One step of the path: the name, the element of the specification it is, an id (of the document for the first step, the link `/Entity/id` for the last step of a reference) and the next step. |
| `ResourceException` (`domain.resource`) | A path that the model does not have. Its message is the body of the 400 answer. |
| `ManagedResourceHandler` (`domain.managed`) | The whole document: read one, read all of an entity, read the versions, write, delete, and give out the JSON Schema of the entity. |
| `EmbeddedResourceHandler` (`domain.embedded`) | A part of a document: read it, replace it, remove it. |
| `ReferenceResourceHandler` (`domain.reference`) | One link of a document: ask whether it is there, add it, remove it. |
| `ReferenceJsonHelper` (`domain.reference`) | Puts a link into a document and takes it out, for a single link and for an array of links. |
| `BodyParser` (`domain`) | Reads the body of a request into a Jackson tree, by the content type. |
| `ResourceJsonTraversalTuple` (`domain.resource`) | The last step of a path and the object of the document that holds it, as `navigateAndCreateJson` gives them to its caller. |
| `LinkedDocumentValidator` (`validation`) | Says whether a document may be written: the JSON Schema of the entity, then the links. It throws `LinkedDocumentValidationException`. |

A new handler and a new `ResourceContext` are made for every request. They hold no state between requests.

## From a request to a resource

### The way in

`UndertowApplication` puts `NamespaceController` on the path `/`, behind the exact paths `/ping`,
`/health/alive` and `/health/ready`, and behind `/graphql` and `/graphiql` when `graphql.enabled` is true and
`graphql.schema` is not empty. Without a GraphQL schema those two paths reach `NamespaceController` and are
answered as a namespace that is not served. `NamespaceController` looks at the path, in this order:

- A path of one character or less is answered 404 with no body.
- The path is exactly the namespace and the first value of `schema` is blank (`/<namespace>?schema`): the list
  of the addresses of the schemas is answered there.
- The path is exactly the namespace and one value of `schema` is `embed`: the schemas themselves are answered
  there. Both are answered for every method, and not for `/<namespace>/?schema`.
- A path that is the namespace, or starts with it and a `/`, goes to a new `DataController`.
- A path that starts with `/source/` goes to `SourceHandler` (the transaction log, not this part).
- Anything else is answered 400 with `Unsupported namespace: "<first segment>"`.

`CORSHandler` is in front of all of this ([the application](core-application.md)). An OPTIONS request with
the headers `Origin` and `Access-Control-Request-Method` is answered 204 by it and does not reach this part.

The namespace is the value of `namespace.default`. REST serves this one namespace. `ResourceContext` takes the
namespace from the path and gives it to the store with every read and write.

`DataController.handleRequest` first calls `exchange.dispatch(this)` when it is on an I/O thread. Everything after
that runs on a worker thread of Undertow, and the handlers block on it: `blockingGet` and `blockingIterable` on
the reads, `join` on the saga.

`NamespaceController` and `DataController` read the path with `exchange.getRelativePath()`, so a value of
`http.prefix` is not part of the path that is checked.

### The time of the request

`DataController` reads the time before anything else:

1. The parameter `timestamp`. When the request has no `timestamp`, the parameter `t`. The names are in lower
   case. When the parameter is given more than once, the last value is used.
2. No parameter: the time is now, `ZonedDateTime.now(ZoneId.of("Etc/UTC"))`.
3. The value is read with `ZonedDateTime.parse`, which needs a date, the hour and the minute, and an offset or `Z`
   (`2018-12-06T11:05:31.000+01:00`). Seconds may be left out. A zone in brackets after the offset is accepted.
   When that fails, the value is decoded as a URL once and read again.
4. When both fail the answer is 400 with a fixed text (see [Where an error answer comes
   from](#where-an-error-answer-comes-from)). A parameter with no value (`?timestamp=`, `?timestamp`) fails too.
5. The time is moved to UTC with `withZoneSameInstant(ZoneId.of("Etc/UTC"))`.

This one time is used for the whole request. A read gives the document as it is at that time. A write makes the
version that is valid from that time. So a write without `timestamp` makes a version from the moment the request
arrived, and a write with a `timestamp` in the past or the future makes a version there.

### The path

`ResourceContext.createResourceContext(specification, path, timestamp)` splits the path at `/` with
`String.split`, which drops empty segments at the end, so a `/` at the end of a path is ignored. Every segment
is decoded as a URL (`urlDecode`). The path is `/{namespace}/{Entity}/{id}/{field}/.../{Entity}/{id}`:

1. Fewer than two segments: `ResourceException`.
2. The second segment must be an entity of the model (a property of the root element of the `Specification`).
3. The third segment, when there is one, is the id. An id that is empty or only spaces is refused.
4. Every segment after the id must be a property of the element before it in the specification
   (`navigateSpecAndCreateResourceContext`).
5. When a segment is a link field (`SpecificationElementType.REF`):
   - nothing after it: the path ends at the field, which is an embedded resource whose value is the link or links;
   - one segment after it: refused, an id is missing;
   - two segments after it: the entity and the id of the target. The entity must be one the field may link to
     (`getRefTypes`). The last `ResourceElement` gets the id `/Entity/id`;
   - more than two: refused, a path does not go through a link.

The result is a chain of `ResourceElement`. `getResourceType` reads the kind from it:

| Kind | The chain | Example |
|---|---|---|
| `MANAGED` | Only the first element. The id may be missing (all documents of the entity). | `/ns/Person/ola`, `/ns/Person` |
| `EMBEDDED` | More elements, and the last has no id. | `/ns/Person/ola/address` |
| `REFERENCE` | More elements, and the last has an id. | `/ns/Person/ola/employer/Company/acme` |

`DataController` asks `isReference()` first, then `isManaged()`, then `isEmbedded()`, and makes the handler.

`DataController` ends with a 404 and the text `Unsupported resource path: <path>`. No request gets there:
every `ResourceContext` is one of the three kinds.

The names in the path are checked against the model, and only names of the model pass. The id is not checked
beyond being non-empty. It is given to the store as a value. The id of the target of a reference is not checked
at all: `/ns/Person/ola/employer/Company/%20` is the link `/Company/` followed by a space.

`DataController` catches `ResourceException` and nothing else. `URLDecoder.decode` throws
`IllegalArgumentException` for a `%` that two hexadecimal digits do not follow, so such a segment is answered 500
(see [Not finished](#not-finished)).

## The handlers

Each handler looks at the method. GET, PUT, POST and DELETE are answered. PUT and POST run the same code. Any
other method, HEAD and OPTIONS included, is answered 400 with `Unsupported managed resource method: <METHOD>`
(or `embedded`, or `reference`). An OPTIONS request that is a CORS preflight does not get this far (see above).

The examples below were run against LDS in memory with the example model
(`linked-data-store-server/schemas/schema.graphql`), started on port 19108. Headers are left out except where
they matter.

### Managed: the whole document

| Request | What happens | Answer |
|---|---|---|
| GET, no id, `?schema` | `SchemaRepository.getJsonSchema().getSchemaJson(entity)` | 200, the JSON Schema of the entity |
| GET, no id | `readDocuments` at the time, unbounded. Deleted documents are left out. | 200, an array of documents, `[]` when there are none |
| GET, id, `?timeline` | `readDocumentVersions`, unbounded. The time of the request is not used. | 200, an array of `{"version": ..., "document": ...}`, oldest first; 404 when there is no version |
| GET, id | `readDocument` at the time | 200, the document; 404 when there is none at that time or it is deleted |
| PUT or POST | See [A write becomes a saga](#a-write-becomes-a-saga). The body is the whole document. | 201 `{"saga-execution-id":"..."}` |
| DELETE | The saga `Delete managed resource`. The store marks the document deleted from the time. | 204 when the saga was awaited, else 202 `{"saga-execution-id":"..."}` |

`version` in the timeline is `ZonedDateTime.toString()` of the UTC time: `2020-01-01T00:00Z[Etc/UTC]`.
`timeline` and `schema` are looked for as names only (`containsKey`); `?timeline=false` gives the timeline too.
`schema` is looked at only when the path has no id.

`putManaged` checks in this order: an empty body (400 `Payload was empty!`), a missing id (400 `Id was empty!`),
then `BodyParser`, then the validator. Only a body of no characters is empty. For a body of spaces Jackson gives a
missing node, and the validator answers `[$: unknown found, object expected]`.

```sh
curl -i -X PUT 'http://localhost:19108/ns/Person/ola?sync=true&timestamp=2020-01-01T00:00:00Z' \
     -H 'Content-Type: application/json' \
     -d '{
       "name": "Ola",
       "employer": "/Company/acme"
     }'
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"saga-execution-id": "01a1f996-a2fa-70bc-9813-014051609244"}
```

```sh
curl -i 'http://localhost:19108/ns/Person/ola?timestamp=2021-06-01T00:00:00Z'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "employer": "/Company/acme",
  "name": "Ola"
}
```

A DELETE does not look for the document first. A DELETE of a document that does not exist is answered 204 as
well, and the store gets a deleted version at that time. With the memory provider the timeline shows a deleted
version as `{"version":"2020-01-01T00:00Z[Etc/UTC]","document":{}}`.

### Embedded: a part of a document

All three methods first read the document at the time of the request. When there is none, or it is deleted, the
answer is 404 with no body. For a PUT that is before the body is parsed, so a body that cannot be read is
answered 404 when there is no document.

| Method | What happens | Answer |
|---|---|---|
| GET | `ResourceContext.subTree` walks the document along the chain. | 200. An object or an array as it is. A simple value inside an array: `["Ola"]`. A part the document does not have: `[null]`. |
| PUT or POST | The body is the new value of the part. `mergeJson` sets it in the document; `navigateAndCreateJson` makes the objects on the way that the document does not have. The whole document is validated, then written with the saga `Create or update managed resource`. | 200 `{"saga-execution-id":"..."}`; 400 when the document is not valid; 404 |
| DELETE | The field is removed from the document and the document is written. It is not validated. | 200 `{"saga-execution-id":"..."}`; 404 |

The body `null` is a value: where the schema allows it, the field is set to null
(`PUT /ns/Person/ola/address` with `null` gave `"address":null` with the example model).

```sh
curl -i 'http://localhost:19108/ns/Person/ola/name'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

["Ola"]
```

```sh
curl -i -X PUT 'http://localhost:19108/ns/Person/ola/born?sync=true&timestamp=2022-01-01T00:00:00Z' \
     -H 'Content-Type: application/json' -d '"1990-05-17T00:00:00Z"'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f996-a32c-7800-b3c6-e4fc44007c40"}
```

A write to a part makes a new version of the whole document at the time of the request. It is built from the
document as it is at that time, not from the newest version.

### Reference: one link

| Method | What happens | Answer |
|---|---|---|
| GET | `ResourceContext.referenceToExists`: the field holds the link, or the array of the field has it. | 200 when the link is there; 404 when it is not, or there is no document. No body. |
| PUT or POST | 404 when there is no document at the time. When the link is there, nothing is written. Else `ReferenceJsonHelper.createReferenceJson`: an array field gets the link added, a single field gets it as its value, in place of the link it had. Then the saga `Create or update managed resource`. The body of the request is read and not used. | 200 with no body when the link was there; 200 `{"saga-execution-id":"..."}` when it was written; 404 |
| DELETE | When there is no document, or the link is not there, nothing is written. Else `ReferenceJsonHelper.deleteReferenceJson`: the first equal element is taken out of the array, or the single field is removed. Then the same saga. | 200 always. No body when nothing was written or the saga was awaited, else `{"saga-execution-id":"..."}`. |

The document is not validated after a link is added or removed. The path was checked against the model, so the
link is to an entity the field may link to. `navigateAndCreateJson` makes the objects on the way to the field
that the document does not have. An array that becomes empty stays in the document as `[]`.

```sh
curl -i -X PUT 'http://localhost:19108/ns/Person/ola/friends/Person/kari?sync=true&timestamp=2023-01-01T00:00:00Z'
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"saga-execution-id": "01a1f996-a33a-72cc-8a99-78d0a8d4c231"}
```

```sh
curl -i 'http://localhost:19108/ns/Person/ola?timeline'
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

The answer is one line; it is broken here to be read. There is no document `Person/kari` in this LDS. A link may
point at a document that does not exist, and no handler looks for the target.

## A write becomes a saga

Every write ends the same way, in all three handlers:

1. The handler has the whole document as a Jackson tree. For a managed PUT it is the body. For the others it is
   the stored document with the change made in it.
2. `sync` is read from the query (see below).
3. The saga is taken from `SagaRepository` by name: `SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE`, or
   `SAGA_DELETE_MANAGED_RESOURCE` for the DELETE of a whole document.
4. A `SagaInput` is made: a new transaction id (`SagaExecutionCoordinator.generateTxId`, a UUIDv7), the method
   (`PUT` or `DELETE`), the namespace, the entity, the id, the time as the version, `source` and `sourceId` from
   the query when given, and the document. A DELETE has no document.
5. `SagaExecutionCoordinator.handoff(sync, ...)` starts the saga and gives a future. The handler calls `join` on
   it and then answers. `saga-execution-id` in the answer is the transaction id.

When the handler passes `true` as `sync`, the future is the completion of the saga: the answer comes when the
transaction log, the store and, when there is one, the search index have been written. With `false` the future
is the handoff: the answer comes when the saga is written to its saga log, and the saga goes on after the
answer. A saga that fails after that is not seen by the client; it is logged by `SagasObserver`.

`sync` is not read the same way everywhere:

| Handler | The saga is awaited when |
|---|---|
| `ManagedResourceHandler` (PUT, POST, DELETE) | no value of `sync` is `false` (any case). So also when `sync` is not given. |
| `EmbeddedResourceHandler`, `ReferenceResourceHandler` | a value of `sync` is `true` (any case). So not when `sync` is not given, and not for `?sync` with no value. |

The query parameter `saga` is given to `SagaCommands.getSagaAdminParameterCommands`. It makes a step of the saga
fail, and is used only when `saga.commands.enabled` is true ([the write path](core-write-path.md)).

When the saga fails while the handler waits, `join` throws. No handler catches it. Undertow logs
`UT005071: Undertow request failed` and answers 500 with no body. The 500 does not say how far the saga came:
with `saga=failAfter persistence` the answer was 500 and the document was in the store.

No handler holds a lock between its read and its write. A write to a part or to a link reads the document,
changes it and writes the whole document as a new version, so two such writes to one document at the same time
each start from the document without the change of the other (see [Not finished](#not-finished)).

A write with the same id and the same time as a version that exists replaces that version. That is what makes a
repeated PUT with a `timestamp` safe.

## Validation

`LinkedDocumentValidator.validate(entity, document)` is called by the managed PUT and the embedded PUT, on the
whole document. It throws `LinkedDocumentValidationException`, and the handler answers 400 with
`Schema validation error: ` and the message.

It checks two things, in this order:

1. **The JSON Schema of the entity** (`JsonSchema.validate`, on `com.networknt:json-schema-validator`). `format` is
   checked, so a text in a `date-time` field must be a date-time of RFC 3339. Everything that is wrong is
   collected. The message is the list as Java prints it: `[$.name: integer found, string expected]`.
2. **The links.** The specification of the entity is walked (`SpecificationTraversal`). For every link field the
   value is looked up in the document. A field that is missing or null is not checked, and neither is a null in
   an array of links, nor a field whose parent in the document is missing or is not an object. The schema is
   checked first, so a null gets this far only where the schema allows it. The schema LDS writes from a GraphQL
   schema says `"type":"string"` for a link, and a null there is answered
   `[$.employer: null found, string expected]`. A value that is not a text is checked as its JSON text. A value
   must be a `/`, an entity, a `/` and an id, with no other `/` in it (`linkPattern`), or the message is
   `Not a valid link. Navigation: <path>: "<value>"`. The entity in it must be one the field may link to, or the
   message is `Illegal linked-domain: "<entity>". Navigation: <path>: "<value>"`. The first link that is wrong
   ends the check.

The validator does not look for the document a link points at. That is on purpose: LDS keeps versions in the
past and the future, and a link may point at a document that is written later.

```sh
curl -i -X PUT 'http://localhost:19108/ns/Person/per?sync=true' \
     -H 'Content-Type: application/json' \
     -d '{
       "name": "Per",
       "employer": "/Person/ola"
     }'
```

```
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: Illegal linked-domain: "Person". Navigation: employer: "/Person/ola"
```

The schema the validator uses, and the specification it walks, are built at start from the model
([the model](core-model.md)).

## The body of a request

The handlers read the body with `receiveFullString(..., StandardCharsets.UTF_8)`, so the body is a text when it
reaches `BodyParser.deserializeBody(contentType, body)`. The content type is the first `Content-Type` header, or
`application/json` when the request has none. Apache Tika parses it (`MediaType.parse`), so parameters such as
`; charset=utf-8` and the case of the letters do not matter.

- `application/json`: read with Jackson 3. A text with something after the document is refused. A text of no
  characters or of only spaces is not refused: `readTree` gives a missing node.
- `application/msgpack`, `application/x-msgpack`: handed to a `MessagePackMapper`.
- Any other type: `IllegalArgumentException("Unsupported Content-Type: ...")`.
- A value that is no media type (`Content-Type: garbage`): `MediaType.parse` gives null and the next line throws
  `NullPointerException`.

The body is always read as UTF-8. A `charset` in the header changes nothing.

A `JacksonException` is wrapped in a `RuntimeException`. No handler catches what `BodyParser` throws, so the
answer is 500 with no body (see [Not finished](#not-finished)).

Answers are JSON. The handlers do not read the `Accept` header.

## Where an error answer comes from

| Status | Body | From |
|---|---|---|
| 400 | `Unsupported namespace: "<first segment>"` | `NamespaceController` |
| 400 | `The 'timestamp' query-parameter must follow the ISO-8601 standard. Example of a valid formatted timestamp is '2018-12-06T11:05:31.000+01:00'` | `DataController` |
| 400 | `Not a valid resource. The resource path must contain at least namespace, name, and id of managed resource` | `ResourceContext`, fewer than two segments |
| 400 | `Not a managed resource name: "<name>"` | `ResourceContext` |
| 400 | `Managed resource id cannot be empty.` | `ResourceContext`, an id that is empty or only spaces |
| 400 | `Not a valid path element: "<name>"` | `ResourceContext` |
| 400 | `Reference resource path-element: "<field>" must be part of pattern ending like: "/<field>/<managed-domain>/<id>"` | `ResourceContext` |
| 400 | `Resource path cannot navigate through REF property: "<field>"` | `ResourceContext` |
| 400 | `Reference resource does not support link to managed-domain "<entity>". Must be one of: [<entities>]` | `ResourceContext` |
| 400 | `Payload was empty!` | `ManagedResourceHandler`, PUT with an empty body |
| 400 | `Id was empty!` | `ManagedResourceHandler`, PUT or DELETE without an id |
| 400 | `Schema validation error: <message>` | the managed and the embedded PUT |
| 400 | `Unsupported <kind> resource method: <METHOD>` | each handler |
| 404 | none | no document, no version, no link; a path of one character or less (`NamespaceController`) |
| 500 | none | an exception that no handler catches: a body that cannot be read, a content type that is not known, a saga that fails while the handler waits, a path segment with a `%` that cannot be decoded |

The 400 answers are `text/plain`. The managed handler sets `Content-Type: application/json; charset=utf-8`
before it reads, so its 404 has that header and no body.

## Rules to keep

- **Time is UTC.** `DataController` moves the time of the request to UTC before anything uses it. Versions are
  stored and compared as UTC instants (`.claude/CLAUDE.md`, Invariants).
- **Names come from the model.** The entity, every field of the path and the entity of a link are looked up in
  the `Specification`. Keep it so: no name from a request reaches a store without that check.
- **A handler does not write to the store.** It reads with `persistence.createTransaction(true)` in a
  try-with-resources block, and writes through a saga. A write that skips the saga skips the transaction log and
  the search index.
- **A handler blocks on a worker thread only.** Keep the `dispatch` in `DataController`. The handlers wait for
  the reactive persistence interface where they use it; no blocking method and no method that returns a complete
  list is added to that interface to make a handler shorter (`.claude/CLAUDE.md`, The persistence interface).
- **No check that a link target exists.** Do not add one to the validator or to a handler.
- **Every call can be sent again.** A PUT of a link that is there writes nothing. A DELETE of a link or of a
  document that is not there is answered as a success. A PUT with a `timestamp` replaces the version at that
  time. A write without `timestamp` that is sent again makes one more version, at the time of the second
  request.
- **A text is taken out of a node with `JsonTools.text`** unless the node is known to be a text. It gives null
  for a node of another kind, where Jackson 3 itself throws (`.claude/CLAUDE.md`, JSON).
- **The error texts are part of the API.** Tests compare them letter by letter: `DataControllerTest`,
  `NamespaceControllerTest`, `ManagedResourceHandlerTest`, `DocumentValidationTest`.
- **The answer of the validator keeps its form**: the place in the document as LDS writes a path, then the message
  of the validator (`$.tags[1]: integer found, string expected`).
- **Lint.** Core overrides `-Xlint:all` and doclint in its `pom.xml` with the categories its older code is not
  clean of. The override is removed, not widened (`.claude/CLAUDE.md`, Invariants).

## Configuration

The handlers read no configuration themselves. These keys decide what they are given:

| Key | Default | Meaning for this part |
|---|---|---|
| `namespace.default` | `ns` | The namespace REST serves. A leading `/` is added when it is missing (`NamespaceController`). |
| `graphql.schema`, `specification.schema` | see [the model](core-model.md) | The model. It decides which entities, fields and links a path may name, and what a document is validated with. |
| `http.prefix` | `/` | A prefix in front of every path. The handlers do not see it. |
| `saga.commands.enabled` | `false` | Whether the query parameter `saga` is used. |

`ManagedResourceHandler` and `EmbeddedResourceHandler` log the method, the path and the body of a PUT at
TRACE, and a validation error at DEBUG. `ManagedResourceHandler` logs an empty body at ERROR.

## Tests

The tests are in `linked-data-store-core/src/test/java/io/descoped/lds/core`.

| Test | What it holds |
|---|---|
| `domain/resource/ResourceContextTest` | Which kind a path is, for single links and arrays of links, at three depths; `navigateAndCreateJson` on an empty, a filled and a partly filled document. Its specification is built in the test (`SpecificationBuilder`). |
| `domain/resource/ResourceGraphqlContextTest` | The paths that are refused. It checks that a `ResourceException` is thrown, not its text. |
| `domain/managed/ManagedResourceHandlerTest` | PUT and GET, DELETE (204, and 400 without an id), 404, `?schema`, the answer to a document that is not valid, `?timeline`. |
| `domain/managed/ManagedTimeBasedVersioningTest` | Three versions read at five times; a `timestamp` that cannot be read is 400. |
| `domain/embedded/EmbeddedResourceHandlerTest` | GET of an array and of a missing part (`[null]`), PUT and DELETE of an object and an array, also two levels down. |
| `domain/reference/ReferenceResourceHandlerTest` | GET, PUT and DELETE of single links and links in arrays, PUT on a missing and on a deleted document (404), DELETE sent twice, a link to an entity the field may not link to (400). |
| `domain/reference/ReferenceJsonHelperTest` | Adding, changing and removing a link in a document, without a server. |
| `domain/BodyParserTest` | JSON, JSON that is refused, another content type, and that a MessagePack body is refused. |
| `validation/DocumentValidationTest` | The messages of the validator, date-times that are valid and not, links, nulls where a link could be. |
| `validation/LinkedDocumentValidatorTest` | Links to another entity and links of another form are refused. |
| `controller/DataControllerTest`, `controller/NamespaceControllerTest`, `controller/NamespaceSchemaTest` | The way in: a path the model does not have, another namespace, `?schema` on the namespace. |

`BodyParserTest` and `DocumentValidationTest` are JUnit 6 with AssertJ. The others are TestNG. Those that send
requests take a server from `TestServerListener`: LDS in the JVM on a free port, in memory, with the namespace
`data` (`src/test/resources/application_test.properties`). The model of that server is the GraphQL schema
`src/test/resources/gqlschema/schema.graphql`, which has the entities `contact` and `provisionagreement` among
others. The file also sets `specification.schema` to the JSON Schema files `spec/schemas/contact.json` and
`spec/schemas/provisionagreement.json`, but `UndertowApplication.initializeUndertowApplication` does not read
that key while `graphql.schema` is set. `ResourceGraphqlContextTest` reads the two JSON Schema files itself.
`TestClient.put` sends the header `Origin: localhost` and no `Content-Type` header, so those tests use the
default of the handlers. A new test class that takes its server from `TestServerListener` is TestNG and asserts
with AssertJ; a test added to an existing class follows that class (`.claude/CLAUDE.md`, Testing).

The Maven commands for one module and for one test class are in `.claude/CLAUDE.md` under Commands. The build
needs JDK 27 and Docker.

## Not finished

Not finished: a path does not go into the elements of an array. `ResourceContext`, `ReferenceJsonHelper` and
`EmbeddedResourceHandler` have `TODO` notes about array-navigation, and `ResourceJsonTraversalTuple` throws
`UnsupportedOperationException("array-navigation not supported")` when the parent of the last step is not an
object. A part of an object that is an element of an array has no address:
`/ns/Company/big/about/languageText` is answered `Not a valid path element: "languageText"`.

Not finished: with a model from a GraphQL schema, a field whose type is another object type (not a list) has no
content in the JSON Schema LDS writes: `"address":{"displayName":""}`. Such a field has no properties in the
specification either. So `/ns/Person/ola/address/city` is answered `Not a valid path element: "city"`, the
content of `address` is not validated, and a link inside it is not known. The cause is where the JSON Schema is
written from the GraphQL schema, not in this part. With JSON Schema files that describe the object in place, the
same path works.

Not finished: a body that cannot be read is answered 500 with no body, not 400. The same holds for a content
type that is not JSON or MessagePack. `curl -d` without a `Content-Type` header sends
`application/x-www-form-urlencoded` and gets this answer.

Not finished: only the managed PUT answers `Payload was empty!`. The embedded PUT has no such check. An empty
body becomes a missing node that is set in the document. Where the schema gives the field a type the validator
answers 400 (`[$.name: unknown found, string expected]`). Where it does not (`address` in the example model) the
document goes to the saga, and the memory provider fails with
`Type tools.jackson.databind.node.MissingNode not supported for path [$, address]`: with `sync=true` the answer
is 500, and without it the answer is 200 with a `saga-execution-id`. Neither writes anything then. The saga
stays in its saga log, and recovery runs it again: in a run with the memory provider both writes were recovered
two seconds later (`Recovery of saga succeeded` in the log), and each document then had a new version with
`"address":null`.

Not finished: two writes to parts or links of one document at the same time lose changes. 20 requests
`PUT /ns/Person/par/friends/Person/p<n>?sync=true` sent at once were all answered 200, and the document had 12
of the 20 links afterwards in one run and 8 in another. By the same code, a write that follows a write that was
not awaited starts from the store as it is then, with or without the first write. That was not seen in a run:
20 such writes sent one after the other all came through with the memory provider.

Not finished: an id or a field name with a `%` that two hexadecimal digits do not follow is answered 500.
`GET /ns/Person/100%25` reaches `ResourceContext.urlDecode` as `100%`, and the `IllegalArgumentException` of
`URLDecoder` is not caught.

Not finished: a MessagePack body is never read. The body reaches `BodyParser` as a text, and MessagePack is not
text. `BodyParserTest.aMessagePackBodyIsRefusedBecauseItIsHandedOverAsText` holds this as it is.

Not finished: `sync` has two meanings, as shown in [A write becomes a saga](#a-write-becomes-a-saga). A managed write
waits for the saga unless told not to; an embedded or a reference write does not wait unless told to.

Not finished: the status of a managed PUT is 201 whether or not the document existed and whether or not the saga
was awaited. A reference DELETE and PUT answer 200 with or without a body.

Not finished: the embedded DELETE and the reference PUT and DELETE write the document without validating it. An
embedded DELETE of a field the schema requires is written. An embedded DELETE also writes a version when the
field was not there, and makes the objects on the way that the document does not have. With the JSON Schema
files of the tests as the model, `DELETE /ns/provisionagreement/d1/address/street` on a document without
`address` wrote a version with `"address":{}`, which that schema does not allow.

Not finished: the validator checks links only in objects it reaches through fields. A link field inside an
object that is an element of an array is not checked (`SpecificationTraversal` walks `properties`, not `items`).
With a model where `Company.offices` is a list of a type that has the link `manager`, a document with
`"offices":[{"city":"Oslo","manager":"not a link"}]` was written.

Not finished: a path segment is decoded twice, by Undertow and by `ResourceContext.urlDecode`. An id with `+`
becomes an id with a space, and `%2541` becomes `A`. `%2F` reached `ResourceContext` as it was sent:
`/ns/Person/a%2Fb` is the id `a/b`. `ResourceContextIntegrationTest`, the only test of encoded names, is
switched off with `@Ignore`.

Not finished: the read of all documents of an entity and the read of the versions are not paged. The read of
the versions has `TODO Support pagination or time-range based query parameters`. Both ask the store for
everything (`Range.unbounded()`) and build the answer in memory before it is sent.

Not finished: the form of the answer of an embedded GET depends on the value: an object or an array as it is, a
simple value in an array, `[null]` for nothing. The code has a `TODO` about one form for all.

Not finished: `SagaInput` has a field `schema`, and every handler gives it the text `"TODO"`.

Not finished: the error callback of `receiveFullString` in the embedded and the reference handler sends a text
and sets no status. `ResourceContext.subTree` throws `RuntimeException("")` when the parent of the last step is
a simple value. `ReferenceJsonHelper.createReferenceJson` calls `equals` on the result of `JsonTools.text`, which
is null when a single link field holds something that is not a text.

Not finished: a method that is not answered gets 400, not 405, and HEAD is one of them.

Not finished: seven tests of the REST API are switched off in the source: five in `DataControllerTest`, one in
`NamespaceControllerTest` and the one in `ResourceContextIntegrationTest`.

Not finished: REST reaches one namespace, the one in `namespace.default`. The stores keep the namespace with
every document, but no request can name another.
