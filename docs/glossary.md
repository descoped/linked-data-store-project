---
title: "Glossary"
description: "The terms of this documentation, each with one meaning."
order: 2
---

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

### Limits

The largest names, ids and paths LDS keeps, the same in every store, in bytes of UTF-8: a namespace 64, an entity type
128, an id 1024, the path of a value 1024, and a value in at most 32 arrays. A write past them is refused with 400,
and a model or namespace past them stops the start. Code: `Limits`.

### Order of ids

The order of the ids of a list and of a page: the order of their Unicode code points, which is the order of their
bytes in UTF-8, in every store. An id is whole text, without an unpaired surrogate. Code: `IdOrder`.

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
that did not complete, because a step failed or LDS stopped before the end, is run again later; a step that was done
is not undone (forward recovery). A saga that fails again when it is run again is set aside and its write is not done
([Concepts](user/concepts.md#what-a-write-goes-through)). The steps run one after another: the transaction log entry,
then the version in the store, then, with search, the update in the index queue. Code: `SagaRepository`,
`SagaExecutionCoordinator`, on Cantara distributed saga.

### Saga log

The log in which a saga records each of its steps as it begins and completes it. Recovery reads it to complete
the sagas that were left unfinished. It is kept in memory (lost when LDS stops) or in PostgreSQL. LDS has a pool of
them, `saga.number-of-logs`, and a saga holds one from its start to its end. Code: `SagaLog`, `SagaLogPool`.

### Saga recovery

Running a saga that did not complete again from its saga log, with the input it was started with: a step the log
says ended is not run again, every other step is. It is done when a write is handed a saga log that holds entries,
when LDS starts, and by a timer (`saga.recovery.enabled`). Code: `SagaExecutionCoordinator`,
`SagaRecoveryTrigger`.

### Dead-saga log

The saga log `dead-saga` of an instance, which no saga is given. When a recovery started by a write fails, the
entries of the saga log are moved there, and the write they stand for is not done. Nothing reads it.

### Cluster instance

One LDS of several that share a saga log in PostgreSQL. They have the same cluster owner and cluster name
(`sagalog.config.cluster.owner`, `sagalog.config.cluster.name`), and each has an instance id of its own
(`sagalog.config.cluster.instance-id`), which names its saga logs. Each instance recovers the sagas the others left
unfinished.

### Document lock

The lock that lets one write at a time change a document, in one LDS: a write holds it from before it reads the
document until its saga has completed. A write that waits for it longer than 30 seconds is answered `503`. Code:
`DocumentLocks`.

### Searchable write

A write with the query parameter `searchable=true`: it is answered when a search finds it, or after 10 seconds, with
the header `LDS-Searchable: true` or `false`. Code: `SearchWait`.

### Transaction log

An append-only log of every write handed to a saga, with its document, method, address and valid time. Code:
`TxLogTools`, on a Rawdata topic.

### Synchronous write

A write answered when its saga has completed: a read that follows it sees the write (read-your-writes). A write of a
document, of a part of a document or of a link is synchronous unless the query parameter `sync=false` is given, and
always when it is a searchable write. A write with `sync=false` is answered at the hand-off, when the saga log has
recorded the start of the saga, and a read that follows at once may not see it yet (eventual consistency).

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

An implementation of the search interface (`SearchIndexProvider`): `solr`. The index is eventually consistent: a
write reaches it through the index queue, and can be found within the second that the provider gives Solr to commit
it (`search.index.commit-within`). A write made while search is off is not indexed until the index is rebuilt
(`search.index.rebuild`).

### Index queue

The updates the search index has not taken yet: for each write with search, the namespace, the entity type, the id
and the version, written or deleted. The last step of a saga adds the update; a worker sends it to the index, with the
document read from the store at that version, and removes it when the index has taken it, so no update is lost when
the index does not answer. It is kept in PostgreSQL, in the database of the transaction log, or in memory when the
transaction log is. Code: `IndexQueue`, `IndexQueueWorker`.

### Index entry

What the search index holds of one version of one document: its texts, each with the language it is searched in, or,
for a deletion, that the document is deleted from that version on. An entry is never changed: a new version is a new
entry. Code: `IndexEntry`.

### Index rebuild

The start option `search.index.rebuild`: LDS adds every version of every document of every entity type, deleted
ones included, to the index queue, and the worker sends them to the index. The index is not cleared first, so search
answers meanwhile. When every version is added and the queue is then empty, the index removes the entries of the
namespace it took before the rebuild started, less a minute: what storage no longer holds. Code: `IndexRebuild`.

### Stem

The form of a word that its inflections share in a language, as `hus` of `husene`. A text in an object with a
`languageCode` and a `languageText` is searched by the stems of its words in that language (Norwegian, Swedish,
English, German, French); any other text word for word. Code: `Language`.

## Running

### Lifecycle

A subsystem of LDS (the code says both) that is started and stopped with it: the store, a pool of threads, a client,
a worker, the HTTP server. LDS starts its subsystems in the order they were made, each after what it needs, and stops
them in the reverse order, also when a start fails part of the way. Code: `Lifecycle`, `Lifecycles`.

### Readiness

Whether LDS can take writes: `/health/ready` asks the store, the saga log, the transaction log and, with search, the
index and the index queue, each within 2 seconds, and answers `UP`, `DEGRADED` (only search fails or lags) or `DOWN`
(`503`). Code: `Readiness`.
