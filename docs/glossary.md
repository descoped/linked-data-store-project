# Glossary

The terms the documentation uses, each with one meaning. Where the code or the API has its own name for the same
thing, the name is given after the definition.

## The data model

### Model

The schema that LDS reads when it starts: one GraphQL SDL file (`graphql.schema`) or a folder of JSON Schema files
(`specification.schema`). It declares the entity types and their fields. Every write is validated against it
(schema-on-write), and LDS derives its GraphQL schema from it. One model applies to every point in time; see
[Snapshot](#snapshot).

### Specification

The form of the model inside LDS: a tree of elements, each `MANAGED` (an entity type), `EMBEDDED` (a value or an
embedded object) or `REF` (a link). Code: `Specification`, `SpecificationElement`.

### Namespace

The top-level partition of the store, and the first segment of every address. A running LDS serves one namespace
(`namespace.default`, `ns` by default); one store can hold several. Code: `namespace`, `ns`.

### Entity type

A type of document that the model declares: a GraphQL object type with `@domain`, or one JSON Schema file. It is
the second segment of an address, as in `/ns/Person`. Code: managed domain, `MANAGED`; in an address and in the
persistence interface, `entity`.

### Document

A JSON object of one entity type with an id of its own. A document has versions. In the REST API it is a managed
resource.

### Id

The identifier of a document within its entity type. The client chooses it; LDS generates none.

### Document key

The identity of a document: namespace, entity type and id. Its address is `/{namespace}/{entityType}/{id}`. With
a valid time it identifies one version. Code: `DocumentKey`.

### Embedded object

An object inside a document, of a type that the model declares without `@domain` (an embedded type). It is a value
object: it has no id and no versions of its own, and is written, versioned and deleted with the document that
holds it (composition). It is reached by its path, as in `/ns/Person/ola/address`. In the REST API it is an
embedded resource. A list of an entity type without `@link` is, in one case, held as an embedded object; see
[What a field holds](user/concepts.md#what-a-field-holds).

### Link

A typed reference from a field of a document to another document, stored as the text `/{entityType}/{id}`. A link
is resolved when it is read, at the time of the read (see [As-of read](#as-of-read)). It is not checked when it is
written: it may point at a document that does not exist yet, or no longer does (a dangling reference). Between
two documents there is association, not composition. A link may name one of several entity types (a union). The
model marks it with `@link` (GraphQL) or `_link_property_<field>` (JSON Schema). Code: `REF`. In the REST API a
link is a reference resource, as in `/ns/Person/ola/employer/Company/acme`.

### Reverse link

A field of the GraphQL API (`@reverseLink`) that lists the documents whose link points at a document: the inverse
of a link, or backlink. It is computed when a query asks for it, at the time of the query, and is not stored.

### Path

The address of a part of a document: an embedded object, a value or a link, as in `/ns/Person/ola/address`.

## Time

### Valid time

The instant from which a version of a document holds, kept in UTC. The client gives it with the query parameter
`timestamp`; without it, it is the time LDS receives the request. A version holds until the valid time of the next
version of the same document. It may be in the past, the present or the future. Also called application time.

### Version

The state of a document from its valid time on. A write at a valid time that the document already has replaces
that version.

### Timeline

Every version of one document, ordered by valid time: `GET /{namespace}/{entityType}/{id}?timeline`.

### Tombstone

The version that a delete writes: an empty document, valid from the time of the delete. The versions before it
stay, and an as-of read before that time still finds them. Code: `markDocumentDeleted`.

### As-of read

A read of the versions valid at an instant T: the query parameter `timestamp` of the REST API, `snapshot` of the
GraphQL API. Without it the read is as of now. A link followed in an as-of read is resolved as of T too.

### Snapshot

The state of a whole namespace as of an instant T: every document at its version valid at T, and every link
resolved as of T. The snapshots T1, T2, …, Tn are as-of reads of one store at different instants. Each is
validated and served with the model LDS was started with. The instant T itself is the snapshot time: the
GraphQL query parameter and the argument of the persistence interface that carry it are named `snapshot`.

### Time machine

The name LDS uses for valid-time versioning with as-of reads: any snapshot, past, present or future, can be read.

### Uni-temporal

LDS versions documents on valid time only. When a write was recorded (its transaction time) is kept in the
transaction log, whose entry ids are time-ordered UUIDv7, but no read asks for it, and a replaced version is gone
from the store. LDS is therefore not bitemporal.

## Writing

### Schema validation

The check of a document against the JSON Schema that LDS derives from the model, before it is written. A document
that fails it is refused with `400` and the reasons, and nothing is written. Not every shape of a model is checked
as written; [The model](user/model.md#shapes-that-do-not-work-as-written) lists those that are not.

### Saga

The unit of work of one write: an ordered set of steps that LDS records in the saga log as it runs them, so that a write
that has started also completes, when a step failed or LDS stopped before the end (forward recovery). The first step
writes the transaction log entry; the steps for persistence and for the search index follow it, in parallel. Code:
`SagaExecutionCoordinator`, on Cantara distributed saga.

### Saga log

The log in which a saga records each of its steps as it begins and completes it. Recovery reads it to complete
the sagas that were left unfinished. It is kept in PostgreSQL or in memory.

### Transaction log

An append-only log of every write handed to a saga, with its document, method, address and valid time. Code:
`TxLogTools`, on a Rawdata topic.

### Synchronous write

A write answered when its saga has completed: a read that follows it sees the write (read-your-writes). A write
of a whole document is synchronous unless the query parameter `sync=false` is given. A write of a part of a
document or of a link is synchronous only with `sync=true`. Any other write is answered at the hand-off, when the
saga log has recorded the start of the saga, and a read that follows at once may not see it yet (eventual
consistency).

### Source and source id

Two optional query parameters of a write, `source` and `sourceId`, recorded in the transaction log. A client uses
them to find the last write it made: `GET /source/{source}`.

## Storing and searching

### Persistence provider

An implementation of the persistence interface (`RxJsonPersistence`), found with `ServiceLoader` by its name:
`mem`, `postgres` or `neo4j`.

### Fragment

One leaf value of one version of one document, with its JSON path, or one piece of it: the memory provider stores a text
longer than `persistence.fragment.capacity` as several fragments. It is the unit of storage of the providers that
implement `RxPersistence` (memory and PostgreSQL). A document is shredded into fragments when it is written, and rebuilt
from an ordered stream of fragments when it is read.

### Reactive stream

A read of the persistence interface returns a `Flowable` (RxJava 3, Reactive Streams). The consumer signals demand,
and the provider reads only as much from its store as is asked for (backpressure).

### Shared suite

`PersistenceIntegrationTest`: the tests that every persistence provider passes, against its real engine. It is the
technology compatibility kit (TCK) of the persistence interface.

### Search index provider

An implementation of the search interface (`SearchIndexProvider`): `solr`. The saga indexes each write. The index
is eventually consistent: a write can be found within the five seconds that the provider gives Solr to commit it.
A write made while search is off or Solr does not answer is not indexed later.
