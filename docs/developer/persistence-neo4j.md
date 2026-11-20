# The Neo4j persistence provider

This document describes the [persistence provider](../glossary.md#persistence-provider) `neo4j` of Linked Data Store
(LDS), the module `linked-data-store-persistence-provider-neo4j`: how a document is laid out as a graph, how it is
written, read and deleted, and what must be kept when the code is changed. It is for developers who change this module.

## What this part is for

The provider keeps the documents of LDS in a Neo4j database. LDS uses it when `persistence.provider` is `neo4j`.
Core finds it through the service loader and gives it the entity types of the model (`PersistenceConfigurator` in
`linked-data-store-core`).

The provider implements `RxJsonPersistence` of [the persistence interface](persistence-api.md) directly. It does not
store [fragments](../glossary.md#fragment) and does not use `RxJsonPersistenceBridge`, as the memory and PostgreSQL
providers do. A document is a tree of nodes in the graph, and a read is one statement that returns one record for each
document.

One database holds every [namespace](../glossary.md#namespace). The tests run on Neo4j 2026, community edition (the
image `neo4j:2026.10-community`). The driver is `org.neo4j.driver:neo4j-java-driver`; its version is the property
`neo4j-java-driver.version` in the root POM.

## Layout

The JPMS module is `io.descoped.lds.persistence.neo4j`. Its one package is
`io.descoped.lds.core.persistence.neo4j`. The module exports nothing. It provides `PersistenceInitializer` with
`Neo4jInitializer`, in `module-info.java` and in `META-INF/services`. Of the classes below only `Neo4jInitializer`
is public.

Core picks the initializer whose annotation `@ProviderName` has the value of `persistence.provider`, and then
checks that `persistenceProviderId()` returns the same value. Both are `neo4j` in `Neo4jInitializer`.

| Class | Responsible for |
|---|---|
| `Neo4jInitializer` | The start of the provider: the configuration keys, the driver, the constraints and indexes. It returns the `Neo4jPersistence` that LDS uses. |
| `Neo4jIndexManagement` | The constraints and indexes the provider needs for each [entity type](../glossary.md#entity-type). It creates what is missing. |
| `Neo4jPersistence` | The implementation of `RxJsonPersistence`. It makes the statement of every read and every delete, and makes a document of a record (`toDocument`). |
| `Neo4jDocumentWriter` | Writes. It makes the statement that writes the documents of one entity type, and lays a document out as the parameters of that statement. |
| `Neo4jTransactionFactory`, `Neo4jTransaction` | A transaction of the driver in a session of its own. `Neo4jTransaction.executeCypherAsync` gives the records of a statement as a stream. |
| `Cypher` | Writes a name into Cypher as a quoted label (`Cypher.label`). |
| `Neo4jQueryAndParams` | A record: a statement and its parameters. |
| `JavaUtilLoggerBridge` | Sends `java.util.logging` to SLF4J. |

## The graph of a document

| Part | Label or type | Properties | What it is |
|---|---|---|---|
| Document node | The name of the entity type, for example `Person` | `namespace`, `id` | One node for each document. `namespace` and `id` are unique together. The node is also made for a document that is only linked to, or only marked as deleted. |
| `VERSION` relationship | From the document node to a root | `from`, `to` | One version of the document. It is valid from `from`, its [valid time](../glossary.md#valid-time), until `to`; `to` is not part of it. Both are date-times in the zone `Etc/UTC`. |
| Root | `<entity>_E`, for example `Person_E` | `type: 'map'`, `path: '$'`, and `deleted: true` on a version that marks the document as deleted (a [tombstone](../glossary.md#tombstone)) | The object that the document of one version is. |
| Node below the root | `<entity>_E` | `type`, `path`. A node that holds a value also has `value`, `hashOrValue` and `namespace`. | One node for every object, array, value and null of the document. |
| `EMBED` relationship | From a node to a node below it | `path`, and `index` for an item of an array | `path` is the name of the field, or `[]` for an item of an array. `index` is the place of the item in the array. |
| `REF` relationship | From the root or a node to a document node | `path`, and `index` for an item of an array | A [link](../glossary.md#link). It starts at the object that has the link field, or at the array that holds the link, and points at the document node of the target, in the same namespace. |

### Node types and values

`type` is what the document holds at that place, not what the model says: `map`, `array`, `string`, `number`,
`boolean` or `null`. `path` is the path from the root with `[]` for an item of an array: `$.address.city`,
`$.friends[]`. The place of an item is not in `path`; it is `index` on the relationship that leads to the node.

A node of type `string`, `number` or `boolean` has a `value`. A whole number that fits in a `long` is stored as a
whole number. Every other number is stored as a `double`, also a whole number that a `long` does not hold. A node
of type `map`, `array` or `null` has no `value`.

`hashOrValue` is what a value is looked up by. It is the value, except for a text that is longer than 40
(`Neo4jDocumentWriter.LONGEST_TEXT_LOOKED_UP_AS_IT_IS`, compared with `String.length`): then it is the SHA-1
hash of the text in UTF-8, as hexadecimal digits (`Neo4jDocumentWriter.hashOf`). `value` always holds the whole
text. `namespace` is on the nodes that have a value and on no other node below the document node. The index of
`<entity>_E` is on `namespace`, `path` and `hashOrValue`.

### Links

The model tells the writer one thing: which fields are links, and to which entity types. A link is the text
`/Entity/id` in such a field, where `Entity` is one of the entity types the field may link to. The whole text must
be of that form, and neither the entity nor the id holds a slash (the pattern `LINK` of `Neo4jDocumentWriter`).
It is written as a `REF` relationship and not as a node. The document node it points at is created with the link
when it is not there, so a link may point at a document that is not written yet (a dangling reference).

Any other value in a link field is a node of the type it has: a text that is not `/Entity/id`, a text that names
an entity type the field may not link to, `/Entity/` without an id, `Entity/id` without the first slash, a number, a
null. A text `/Entity/id` in a field that the model does not call a link is a node of type `string`.

A request to LDS does not bring such a value to the provider. Core validates a document before the write, and
with the example model it answers 400 to each of these values in a link field (`Schema validation error: Not a
valid link. ...`, `Illegal linked-domain: ...`, `... integer found, string expected`). This was run with the
provider `mem`; the validation is in `ManagedResourceHandler` of core, before the saga, and does not ask the
provider.

When a document is read, a `REF` gives the text `/` + the label of the target node + `/` + its `id`.

### Versions

The versions of a document follow each other without a gap. A new version is valid until the version after it,
and the version before it is valid until the new one. The last version is valid until
`9999-01-01T00:00:00Z[Etc/UTC]`. A version written with the `from` of a version that is there replaces it: the
old root and everything below it are deleted first.

A tombstone is a version whose root has `deleted: true` and nothing below it. An
[as-of read](../glossary.md#as-of-read) leaves it out. A read of the versions of a document gives it as an empty
document.

Every date-time that goes to Neo4j is first moved to the zone `Etc/UTC` (`Neo4jPersistence.inUtc`). Neo4j tells
two values of the same instant apart when their zones differ, also `Z` and `Etc/UTC`, and a version is matched by
`from` being equal.

### An example

The document `ola` of the entity type `Person` of the example model (`linked-data-store-server/schemas/schema.graphql`),
in the namespace `ns`, written with the version `2026-11-20T10:15:30+01:00[Europe/Oslo]`:

```json
{
  "name": "Ola",
  "born": "1990-05-17T00:00:00Z",
  "address": {"street": "Storgata 1", "city": "Oslo"},
  "employer": "/Company/acme",
  "friends": ["/Person/kari", "not a link"],
  "shoe": 43,
  "vegetarian": false,
  "nickname": null,
  "motto": "A text of more than forty characters is looked up by its hash."
}
```

`Neo4jDocumentWriter.record` makes these parameters of it. `employer` and `friends` are link fields in the
model; `shoe`, `vegetarian`, `nickname` and `motto` are not in the model. The line of `motto` is broken here.

```
namespace: ns
id: ola
version: 2026-11-20T09:15:30Z[Etc/UTC]
nodes:
  {parent=0, name=name, type=string, path=$.name, value=Ola}
  {parent=0, name=born, type=string, path=$.born, value=1990-05-17T00:00:00Z}
  {parent=0, name=address, type=map, path=$.address}
  {parent=3, name=street, type=string, path=$.address.street, value=Storgata 1}
  {parent=3, name=city, type=string, path=$.address.city, value=Oslo}
  {parent=0, name=friends, type=array, path=$.friends}
  {parent=6, name=[], index=1, type=string, path=$.friends[], value=not a link}
  {parent=0, name=shoe, type=number, path=$.shoe, value=43}
  {parent=0, name=vegetarian, type=boolean, path=$.vegetarian, value=false}
  {parent=0, name=nickname, type=null, path=$.nickname}
  {parent=0, name=motto, type=string, path=$.motto, value=A text of more than forty characters is looked up
   by its hash., hash=cdf914ba5c916046eb64e464f2f063c460d45864}
links:
  [{parent=0, name=employer, id=acme}]
  [{parent=6, name=[], index=0, id=kari}]
```

`nodes` is in the order of the document, parents before their children. `parent` is the place of the parent in
the list: 0 is the root of the version, 1 the first node of the list. `links` has one list for each entity type the
document may link to, here `Company` and `Person`, in that order. The write statement makes this graph of the
parameters (the properties of most value nodes are left out here):

```
(:Person {namespace: 'ns', id: 'ola'})
  -[:VERSION {from, to}]-> (:Person_E {type: 'map', path: '$'})
     -[:EMBED {path: 'name'}]-> (:Person_E {type: 'string', path: '$.name', value: 'Ola',
                                           hashOrValue: 'Ola', namespace: 'ns'})
     -[:EMBED {path: 'born'}]-> (:Person_E {type: 'string', path: '$.born', ...})
     -[:EMBED {path: 'address'}]-> (:Person_E {type: 'map', path: '$.address'})
        -[:EMBED {path: 'street'}]-> (:Person_E {type: 'string', path: '$.address.street', ...})
        -[:EMBED {path: 'city'}]-> (:Person_E {type: 'string', path: '$.address.city', ...})
     -[:EMBED {path: 'friends'}]-> (:Person_E {type: 'array', path: '$.friends'})
        -[:REF {path: '[]', index: 0}]-> (:Person {namespace: 'ns', id: 'kari'})
        -[:EMBED {path: '[]', index: 1}]-> (:Person_E {type: 'string', path: '$.friends[]', ...})
     -[:EMBED {path: 'shoe'}]-> (:Person_E {type: 'number', path: '$.shoe', value: 43, ...})
     -[:EMBED {path: 'vegetarian'}]-> (:Person_E {type: 'boolean', path: '$.vegetarian', value: false, ...})
     -[:EMBED {path: 'nickname'}]-> (:Person_E {type: 'null', path: '$.nickname'})
     -[:EMBED {path: 'motto'}]-> (:Person_E {type: 'string', path: '$.motto', value: 'A text of ...',
                                            hashOrValue: 'cdf914ba...', namespace: 'ns'})
     -[:REF {path: 'employer'}]-> (:Company {namespace: 'ns', id: 'acme'})
```

## Constraints and indexes

`Neo4jIndexManagement` wants two things for each entity type of the model (`Specification.getManagedDomains`):

- a constraint that makes `namespace` and `id` unique together on the label of the entity type. A document node is
  matched by these two properties, and a list of documents is ordered by `id`.
- an index on `namespace`, `path` and `hashOrValue` of the label `<entity>_E`. `findDocument` looks a value up
  in it.

For the example model these are the statements of the first start. The constraint of an entity type comes before its
index. The order of the entity types is not fixed: they come as a set.

```cypher
CREATE CONSTRAINT IF NOT EXISTS FOR (n:`Company`) REQUIRE (n.`namespace`, n.`id`) IS UNIQUE
CREATE INDEX IF NOT EXISTS FOR (n:`Company_E`) ON (n.`namespace`, n.`path`, n.`hashOrValue`)
CREATE CONSTRAINT IF NOT EXISTS FOR (n:`Person`) REQUIRE (n.`namespace`, n.`id`) IS UNIQUE
CREATE INDEX IF NOT EXISTS FOR (n:`Person_E`) ON (n.`namespace`, n.`path`, n.`hashOrValue`)
```

The constructor reads what the database has (`SHOW INDEXES YIELD name, labelsOrTypes, properties,
owningConstraint WHERE labelsOrTypes IS NOT NULL`). An index that a constraint owns counts as that constraint; the
first label of an index is its label, and an index without a label is passed over. Nothing else of an index is
read: not what kind of index it is, and not whether it is on nodes or on relationships. `createMissingIndices`
then does two things:

- For each index of the database that is not wanted, it writes a line to the log at `INFO`:
  `Not using index: ... Use the following cypher statement to drop: ...`. It drops nothing. The database is
  read as a whole, so an index that something else has made in the same database gets such a line at every
  start.
- For each wanted constraint or index that is not there, it logs `Creating: <statement>` and runs the statement.

Two indexes are the same when the label, the properties in their order, and being a constraint or not are the
same (`Index.equals`). So a plain index on `namespace` and `id` does not stand in for the constraint: the
provider asks for the constraint, Neo4j refuses it while the index is there, and the start fails with an error
that names the entity type (`Neo4jProviderTest.aPlainIndexDoesNotStandInForTheConstraint`).

## Start

`Neo4jInitializer.initialize` is given the default namespace (which it does not use), the configuration and the
entity types of the model. It does this, in order:

1. It sends `java.util.logging` to SLF4J, once for the JVM (`JavaUtilLoggerBridge`). This resets the configuration
   of `java.util.logging` for the whole process, puts the bridge to SLF4J in place of the handlers of its root
   logger and sets the level of the root logger to `INFO`.
2. It opens the driver with `neo4j.driver.url` and basic authentication. No other setting of the driver is
   given, and no database is named, so the driver uses its defaults.
3. It opens a session and runs `Neo4jIndexManagement` in a write transaction that the driver manages
   (`Session.executeWriteWithoutResult`). The driver runs the transaction again when Neo4j says "try again".
   Instances of LDS that start together ask for the same constraints and can end in a deadlock, which Neo4j
   resolves by failing one of them in that way (`Neo4jProviderTest.instancesThatStartTogetherAllStart`).
4. When that fails, it closes the driver and the error goes to the caller. Otherwise it returns a
   `Neo4jPersistence` with a `Neo4jTransactionFactory` that holds the driver.

`Neo4jPersistence.close` closes the driver. Core calls it when LDS stops (`UndertowApplication.stop`).

## A write

What comes in: `createOrOverwrite(transaction, documents, specification)`, with the documents as a `Flowable` of
`JsonDocument`. Core calls it with one document for each write (`PersistenceCreateOrOverwriteSagaAdapter`).

1. `Neo4jPersistence.createOrOverwrite` collects the documents into a list and groups them by entity type, in the
   order in which the entity types first come. So the documents of one call are all in memory at once, and the
   documents of one entity type are written before those of the next, whatever order they came in.
2. For each entity type, `Neo4jDocumentWriter.write` gives the statement of that entity type and one parameter, `batch`:
   a list with one entry for each document.
3. The statements are run one after the other in the transaction. Nothing is run before the returned
   `Completable` is subscribed to.

### The parameters of a document

`Neo4jDocumentWriter.record` walks the document (`addFields`, `add`). The document must be a JSON object;
anything else is refused with an `IllegalArgumentException`, `A document is a JSON object, and this is not:
<key>`, which fails the `Completable` of the write. Every field and every item
becomes one entry of `nodes`, or, when it is a link, one entry of `links` (`addLink`). The walk has the element
of the specification for the place at hand, or null where the specification says nothing. It uses the element
for one question only: is this place a link field (`linkElement`: the element itself is of type `REF`, or it is
the item of an array that is). Only what a node has is sent: `index` for an item, `value` for a value, `hash`
for a long text.

### The statement of an entity

`Neo4jDocumentWriter.statement` makes the statement from two things: the name of the entity type, and the entity types a
document of it may link to (`linkTargets`: the `getRefTypes` of every element of type `REF` below the entity type,
sorted). The statement is the same for every document of the entity type, so that Neo4j plans it once. It is kept in
a map (`statements`) by entity type and link targets. No name of a field is part of the Cypher.

This is the statement of `Person` of the example model. The two long lines are broken here.

```cypher
UNWIND $batch AS record
CALL (record) {
  MERGE (r:`Person` {namespace: record.namespace, id: record.id})
  WITH r, record
  CALL (r, record) {
    OPTIONAL MATCH (r)-[:VERSION {from: record.version}]->(old)
    OPTIONAL MATCH (old)-[:EMBED*]->(below)
    DETACH DELETE old, below
  }
  OPTIONAL MATCH (r)-[before:VERSION]->() WHERE before.from <= record.version AND record.version < before.to
  CALL (r, record) {
    OPTIONAL MATCH (r)-[after:VERSION]->() WHERE after.from > record.version
    RETURN min(after.from) AS next
  }
  CREATE (r)-[:VERSION {from: record.version,
      to: coalesce(before.to, next, datetime('9999-01-01T00:00:00.0Z[Etc/UTC]'))}]->
      (m:`Person_E` {type: 'map', path: '$'})
  SET before.to = record.version
  WITH m, record
  CALL (record) {
    UNWIND range(0, size(record.nodes) - 1) AS i
    WITH i, record.nodes[i] AS n ORDER BY i
    CREATE (x:`Person_E` {type: n.type, path: n.path, value: n.value, hashOrValue: coalesce(n.hash, n.value),
        namespace: CASE WHEN n.value IS NULL THEN null ELSE record.namespace END})
    WITH i, x ORDER BY i
    RETURN collect(x) AS created
  }
  WITH [m] + created AS nodes, record
  CALL (nodes, record) {
    UNWIND range(0, size(record.nodes) - 1) AS i
    WITH nodes[record.nodes[i].parent] AS parent, nodes[i + 1] AS child, record.nodes[i] AS n
    CREATE (parent)-[:EMBED {path: n.name, index: n.index}]->(child)
  }
  CALL (nodes, record) {
    UNWIND record.links[0] AS link
    MERGE (target:`Company` {namespace: record.namespace, id: link.id})
    WITH nodes[link.parent] AS parent, link, target
    CREATE (parent)-[:REF {path: link.name, index: link.index}]->(target)
  }
  CALL (nodes, record) {
    UNWIND record.links[1] AS link
    MERGE (target:`Person` {namespace: record.namespace, id: link.id})
    WITH nodes[link.parent] AS parent, link, target
    CREATE (parent)-[:REF {path: link.name, index: link.index}]->(target)
  }
}
```

What it does for each document of the batch, in order:

1. It finds or creates the document node.
2. It deletes the version with the same `from`, with everything below its root. The root is matched on its own,
   because a version that holds nothing, or links only, has no node below the root.
3. It finds the version that the new one falls into (`before`) and the first version after it (`next`), creates
   the `VERSION` relationship and the root, and ends `before` at the new version.
4. It creates the nodes in the order of the list and collects them. The order is stated again after the
   `CREATE`, because the places of the list are what joins a node to its parent. A property that is null is not
   written.
5. It joins each node to its parent with `EMBED`. Place 0 is the root.
6. For each entity type that may be linked to, it finds or creates the target document node and creates the `REF`.
   `record.links[n]` holds the links to `linkTargets.get(n)`.

Each document is written by itself (`CALL (record) { ... }`), one after the other, so that several versions of
one document in a batch see each other.

An entity type that the specification does not have is written all the same, with a statement without links. That
statement is not kept, so that names from outside do not fill the memory. Such an entity type has no constraint and no
index. Core refuses a write to an entity type that the specification does not have.

## A read

Every read is one statement that ends in the same way (`RETURN_DOCUMENT`):

```cypher
RETURN r, v, m, [p = (m)-[:EMBED|REF*]->(e) | [relationships(p), e]] AS leaves
```

`r` is the document node, `v` the version, `m` its root. `leaves` holds every node below the root together with
the relationships that lead to it from the root, and for every link the document node it points at. So one record
is one document.

### From a record to a document

`Neo4jPersistence.toDocument` makes the document of a record:

1. The key is the namespace and the entity type that were asked for, the `id` of `r` and the `from` of `v`.
2. For each leaf, `computePath` makes the path from the relationships: `.name` for a field, `[index]` for an item
   of an array.
3. `toLeaf` makes a `FlattenedDocumentLeafNode`. A node with the property `id` is the document node that a link
   points at, and gives the text `/Label/id`. Any other node gives what its `type` says. A number is given as the
   text of the number.
4. `FlattenedDocument` and `FlattenedDocumentToJson` of the persistence interface build the JSON of the leaves.
   Neo4j gives a leaf for every object and array, not only for the empty ones; `FlattenedDocumentToJson` is made
   for that.

The leaves are sorted by path before the JSON is built (`FlattenedDocument` sorts them with `PathComparator`). So
the fields of every object of a document that is read are in the order of their names, not in the order in which
they were written: names of digits only come first, as numbers, and the other names follow as `String.compareTo`
orders them, capital letters before small ones. The items of an array keep their places.

### The statements

| Method | The statement |
|---|---|
| `readDocument` | Matches the document node by label, `namespace` and `id`, and the version that is valid at the time and is not a tombstone. The first record is the answer. |
| `readDocuments` | Matches the document nodes of the label in the namespace, with the version that is valid at the time and is not a tombstone. The range is on `r.id`. |
| `readDocumentVersions` | Matches every version of one document, also a tombstone. The bounds of the range are on `v.from`, and the order is `v.from`. |
| `readTargetDocuments` | Goes from the source document and its version at the time, down `EMBED` to the node whose `path` is the path of the link without its last part, along the `REF` whose `path` is the last part, to the target document node. The bounds of the range are on the id of the target. Each target is taken once. Then it matches the version of the target that is valid at the time and is not a tombstone. The order and the limit come after that, so a limit counts the targets that are written. |
| `readSourceDocuments` | The same path from the other end: from the target document node, back along `REF` and `EMBED` to the root and the document node of the source, with the version of the source that is valid at the time. Each source is taken once. The range is on the id of the source. The versions of the target are not looked at: a document is a source also when the target is not written, or is marked as deleted at the time. |
| `findDocument` | Matches a value node of `<entity>_E` by `namespace`, `path` and `hashOrValue` (the index), goes up `EMBED` to the root and the document node, and takes the version that is valid at the time. A value of more than 40 characters is looked up by its hash. A document is taken once, also when an array holds the value twice. The range is on `r.id`. |
| `hasPrevious`, `hasNext` | `readDocuments` with `Range.lastBefore(1, id)` or `Range.firstAfter(1, id)`: true when a document comes back. |

"Valid at the time" is `v.from <= $snapshot AND $snapshot < v.to`. "Not a tombstone" is
`m.deleted IS NULL`.

A range becomes conditions and an order (`idConditions`, `orderAndLimit`). `after` and `before` are both
exclusive. The order is ascending, or descending when the range is backward (`Range.isBackward`: it has `last`,
or it has `before` and no `after`). The documents of a backward range come in descending order; the provider
does not turn them around. A range with `first` or `last` adds `LIMIT`. The bounds and the limit are parameters.

This is `readDocuments` for `Person` with `Range.firstAfter(3, "kari")`, with the parameters written into it as
`Neo4jTransaction.interactive` writes them. Long lines are broken here, also in the next statement.

```cypher
MATCH (r:`Person` {namespace: 'ns'})-[v:VERSION]->(m)
WHERE v.from <= datetime('2026-11-20T12:00Z[Etc/UTC]') AND datetime('2026-11-20T12:00Z[Etc/UTC]') < v.to
  AND m.deleted IS NULL AND 'kari' < r.id
WITH r, v, m ORDER BY r.id LIMIT 3
RETURN r, v, m, [p = (m)-[:EMBED|REF*]->(e) | [relationships(p), e]] AS leaves
```

And `readTargetDocuments` from the person `ola` along `$.friends[]` with `Range.first(10)`:

```cypher
MATCH (elem:`Person` {namespace: 'ns', id: 'ola'})-[version:VERSION]->(root:`Person_E`)-[:EMBED*0..]->
      (edge:`Person_E` {path: '$.friends'})-[:REF {path: '[]'}]->(r:`Person`)
WHERE version.from <= datetime('2026-11-20T12:00Z[Etc/UTC]') AND datetime('2026-11-20T12:00Z[Etc/UTC]') < version.to
WITH DISTINCT r
MATCH (r)-[v:VERSION]->(m) WHERE v.from <= datetime('2026-11-20T12:00Z[Etc/UTC]')
  AND datetime('2026-11-20T12:00Z[Etc/UTC]') < v.to AND m.deleted IS NULL
WITH r, v, m ORDER BY r.id LIMIT 10
RETURN r, v, m, [p = (m)-[:EMBED|REF*]->(e) | [relationships(p), e]] AS leaves
```

`EMBED*0..` is there because the node that holds a link is the root itself when the link is at the top of the
document.

### Records on demand

`Neo4jTransaction.executeCypherAsync` gives the records of a statement as a `Flowable`
(`Flowable.generate`), a [reactive stream](../glossary.md#reactive-stream):

- The statement is run when the stream is subscribed to, not when the method is called.
- One record is taken from the driver for each one that is asked for. The driver fetches the records from the
  server in batches; the provider does not set their size, and the default of the driver is 1000 records.
- When the stream is cancelled or ends, the result is consumed (`Result::consume`), which discards what is left
  on the server. A cancelled read is not in the way of the next statement of the transaction.
- A statement that fails gives the error of the driver to the stream, as it is.

`Neo4jPersistence.documents` maps each record to a document, so a document is built when it is asked for. The
provider uses the blocking session of the driver (`org.neo4j.driver.Session`, `Result.hasNext`), so the thread
that asks for a record waits for it.

`Neo4jProviderTest.aDocumentIsTakenFromTheStoreWhenItIsAskedFor` and
`Neo4jTransactionTest.aStatementIsRunWhenItsStreamIsSubscribedTo` hold this.

## Deleting

| Method | What it does |
|---|---|
| `markDocumentDeleted` | Writes a tombstone: a version whose root has `deleted: true`. It finds or creates the document node, replaces a version of the same time, and keeps the versions without a gap as a write does. Core calls it for a delete (`PersistenceDeleteSagaAdapter`). |
| `deleteDocument` | Removes one version, with everything below its root. The version before it becomes valid until the end of the removed one, as if the removed version was never written. The document node is removed when no relationship is left on it. |
| `deleteAllDocumentVersions` | Removes every version of one document. The document node is removed unless a relationship points at it (a link from another document). |
| `deleteAllEntities` | The same for every document of an entity type in one namespace. |

None of them uses the `PersistenceDeletePolicy` it is given, and `deleteAllEntities` does not use its
`Specification`.

## Transactions

`Neo4jTransactionFactory.createTransaction(readOnly)` opens a session of the driver, with the access mode `READ`
for a read-only transaction and `WRITE` otherwise, and begins a transaction in it (`Neo4jTransaction`). When the
transaction does not begin, the session is closed and the error is thrown.

`commit` and `cancel` end the transaction once: the first call commits or rolls back, closes the session, and
completes the future. All of that is done on the thread that calls, so the future is complete when it is
returned. The session is closed in every case, so its connection goes back to the pool of the
driver. A failure completes the future exceptionally. A later call of either does nothing and returns the same
future. The `TransactionStatistics` of the future are always empty.

`Neo4jTransaction` does not override `close`. `Transaction.close` of the persistence interface commits, and
cancels when the commit fails. A statement that fails ends the transaction on the server: the commit after it
fails, and so does `close` (`Neo4jTransactionTest.aCommitThatFailsFailsItsFutureAndClosesTheSession`).

`Neo4jTransaction` has no lock. `Neo4jPersistence` casts the `Transaction` it is given to `Neo4jTransaction`.

The health check of core (`HealthCheckHandler`, `/health/alive`) opens a read-only transaction and cancels it.
With this provider that is a session of the driver and a transaction in it for every check.

With `neo4j.cypher.show` set to `true`, `Neo4jTransaction` logs every statement before it runs it, at `INFO`,
with the parameters written into the statement (`interactive`). The statements of the start
(`Neo4jIndexManagement`) do not go through `Neo4jTransaction` and are not logged in this way.

```
... [main] INFO  i.d.lds.core.persistence.neo4j.Neo4jTransaction - :: CYPHER ::
MATCH (r:`Person` {namespace: 'ns', id: 'ola'})-[v:VERSION]->(m) WHERE v.from <= ... AND m.deleted IS NULL
RETURN r, v, m, [p = (m)-[:EMBED|REF*]->(e) | [relationships(p), e]] AS leaves
```

## The name of an entity in Cypher

A statement is given everything as a parameter: namespaces, ids, values, paths, names of fields, bounds and
limits. The one exception is the name of an entity type, which is a label: of the document node, of the `<entity>_E`
nodes, and of the document nodes that are linked to.

`Cypher.label` writes the label in backticks and doubles a backtick in the name. Whatever the name holds, it
stays a name. The provider does not validate the name. `Neo4jProviderTest.aNameOfAnyCharactersStaysAName` writes,
reads, follows and deletes with an entity type and a field of these names:

```
Odd `entity`) DETACH DELETE (r
it's a \ field
```

Neo4j can take a label as a parameter (`(r:$($entity))`). The provider does not do that, for two reasons that
the Javadoc of `Cypher` gives:

- Neo4j then plans without the label. Measured on Neo4j 2026.10, a page of three from a list of 50,000
  documents read all 50,000 and sorted them. With the label in the statement, the same page is a seek in the
  index of `namespace` and `id`, which is already in order.
- A constraint or an index cannot be created with a label as a parameter.

`Neo4jIndexManagement` writes the names of properties and the names of the constraints and indexes it reports
through `Cypher.label` as well.

## Configuration

Core gives the provider the keys that `Neo4jInitializer.configurationKeys` names, and no other. Each of them
must be a key of the configuration, or core stops with `Configuration missing for: [...]`
(`PersistenceConfigurator`). An empty value is enough. The defaults are in
`application-server-defaults.properties` of `linked-data-store-server`, so that an environment variable can set
each key.

| Key | Meaning | Default of the server |
|---|---|---|
| `neo4j.driver.url` | The address of Neo4j, as the driver takes it. | `bolt://localhost:7687` |
| `neo4j.driver.username` | The user. | `neo4j` |
| `neo4j.driver.password` | The password. | empty |
| `neo4j.cypher.show` | `true` logs every statement. It is read with `Boolean.parseBoolean`: any other value is `false`. | `false` |

A new key is added to `configurationKeys` and to `application-server-defaults.properties`.

Two keys of core decide about the provider: `persistence.provider` (`neo4j`) and
`persistence.initialization.max-wait-seconds`. Core calls `initialize` again, one second after a failure, until
that many seconds have passed since the first call.

When Neo4j cannot be reached, one call of `initialize` takes from about half a minute to about a minute, because
the driver tries its transaction again by itself. It waits about 1, 2, 4, 8 and 16 seconds between its tries,
each a little more or less. It gives up when 30 seconds have passed, which is the default of the driver
(`Config.maxTransactionRetryTimeMillis`). When they have not passed after the fifth wait, it waits about 32
seconds more and tries one last time. Each failed try is in the log at `WARN` with its stack trace, about 100
lines.

This was run in a folder with every jar of LDS in `lib`, with nothing listening on port 19205:

```sh
LDS_HTTP_PORT=19205 LDS_PERSISTENCE_PROVIDER=neo4j LDS_NEO4J_DRIVER_URL=bolt://127.0.0.1:19205 \
  LDS_NEO4J_DRIVER_PASSWORD=lds-development LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=0 \
  java -cp "lib/*" io.descoped.lds.server.Server
```

The lines of the log are shortened, and the last one is broken:

```
...T16:49:01.182+01:00 ... PersistenceConfigurator - Persistence provider initialization attempt # 1
...T16:49:01.255+01:00 ... DriverFactory - Driver instance 1747702724 created for server uri 'bolt://127.0.0.1:19205'
...T16:49:01.323+01:00 ... ExponentialBackoffRetryLogic - Transaction failed and will be retried in 1062ms
...
...T16:49:31.149+01:00 ... ExponentialBackoffRetryLogic - Transaction failed and will be retried in 29724ms
...T16:50:00.881+01:00 ... InternalDriver - Closing driver instance 1747702724
Exception in thread "main" org.neo4j.driver.exceptions.ServiceUnavailableException: Unable to connect to
  127.0.0.1:19205, ensure the database is running and that there is a working network connection to it.
```

LDS then ends with exit code 1, 60 seconds after the attempt began. Nothing listens on the HTTP port of LDS in
that time: the port is opened after the provider has started.

With the default of the server, 60 seconds, LDS ends after one call or after two. The command was run again
without `LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS` and without the password. The first call took 32
seconds. Fewer than 60 seconds had passed, so core called `initialize` again one second later. The second call
took 66 seconds, and LDS ended with exit code 1, 101 seconds after it was started. When the first call takes 60
seconds or more, there is no second.

## Rules to keep

These come from the rules of the project (see [the invariants](architecture.md#the-invariants)) and from the
comments of the code.

- **A read stays a stream.** A read is one statement that returns one record for each document, and a record is
  taken from the driver when a document is asked for. Do not replace a stream by a call that returns a complete
  list, and do not add a blocking call to an interface.
- **A document is stored as it is given and read back the same**: also a field the model does not have, a value of
  another kind than the model says, a null, an array inside an array. Whether a document may be like that is for the
  [schema validation](../glossary.md#schema-validation) before a write to say. The model tells the provider one thing:
  which fields are links. The [shared suite](../glossary.md#shared-suite) holds every provider to this
  ([the shared suite](persistence-tck.md)).
- **A link is the text `/Entity/id`**, as it is for the providers that keep it as text, so that every provider
  follows the same texts and reads back the text that was written.
- **Only labels are written into Cypher**, and only through `Cypher.label`. Namespaces, ids, values, paths and
  names of fields are parameters. Do not make the label a parameter.
- **The statement of an entity does not change from document to document.** Do not put a name of a field or a value
  into its text. Do not keep a statement for a name that comes from outside the specification.
- **Every date-time is moved to `Etc/UTC` before it goes to Neo4j** (`inUtc`), also the bounds of a range. The end
  of time in the statements is written with the same zone.
- **The versions of a document have no gap.** A write, a tombstone and the deletion of a version each set
  `to` of the versions around them. A change of one of them is a change of all three.
- **The nodes of a document are joined by their places in the list.** The order of the created nodes must be the
  order of `record.nodes`.
- **Constraints and indexes are created with `IF NOT EXISTS`, in a transaction that the driver runs again**, so
  that instances can start together. Nothing is dropped at start.
- **One persistence contract for every provider**, checked by the shared suite against a real Neo4j in a
  container, not a substitute.
- **Transactions commit only when asked and roll back otherwise.**
- **JPMS.** The provider is registered with `provides` in `module-info.java` and in `META-INF/services`.
- **Lint and Javadoc.** The module has no override of the rule of the root POM: it compiles with
  `-Xlint:all -Werror` and its Javadoc passes doclint `all`.
- **The latest stable version** of Neo4j and of the driver. The image is named in `Neo4j2026` of the tests and in
  `compose.yaml` of the server. 3.0.0 is a clean break: the provider reads and writes one layout of the graph,
  the one of this document, and has no migration code.

## Tests

The tests need Docker, except `Neo4jDocumentWriterTest`. `Neo4j2026` starts one container of
`neo4j:2026.10-community` on first use, with the password `lds-development`, and every test class of the module
uses that one. Testcontainers stops it when the JVM ends. The test classes share the database, so each test uses
namespaces, entity types or ids of its own.

| Class | Runs on | What it holds |
|---|---|---|
| `Neo4jPersistenceIntegrationTest` | Neo4j, TestNG | The shared suite, `PersistenceIntegrationTest` of `linked-data-store-persistence-provider-tck`. The class only starts the provider and closes it. |
| `Neo4jProviderTest` | Neo4j, JUnit | What the suite does not cover: a document, a version, a link and each delete stay in their own namespace; a link at the top of a document; the limit of linked documents; a document that is linked twice; nulls; names of any characters; the range of `findDocument`; a list of 1000 documents; documents on demand; a version written again; a document written at the time it was marked as deleted; a version in another zone; a cancelled transaction; ending a transaction twice; instances that start together; a plain index in place of the constraint. |
| `Neo4jRoundTripTest` | Neo4j, JUnit | Documents of different shapes written and read back: the JSON files in `src/test/resources`. |
| `Neo4jTransactionTest` | Neo4j, JUnit | The session is closed at commit and at cancel; a statement is run when its stream is subscribed to; a statement that fails fails its stream and nothing else; a commit that fails; the statement as it is logged. |
| `Neo4jDocumentWriterTest` | No Neo4j, JUnit | The parameters of a document and the text of the statement: order and parents of the nodes, links and what is not a link, long texts, large numbers, a document that is no object, the statement made once. |

`SpecificationBuilder` and `TestSpecificationElement` build a specification for the tests. `LoopbackPorts`
publishes every port of a container on 127.0.0.1 only; Testcontainers finds it through
`src/test/resources/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier`, and `Neo4j2026` checks
it (`LoopbackPorts.check`). The module needs its own copy of that class.

The tests run with the build ([the build](build.md)). The command for one module and what it needs is
`./mvnw verify -pl <module> -am`, and the command for one test class is `./mvnw test -pl <module> -Dtest=<Class>`.
With the name of this module they are the two below. Their output is not shown here.

```sh
./mvnw verify -pl linked-data-store-persistence-provider-neo4j -am
./mvnw test -pl linked-data-store-persistence-provider-neo4j -Dtest=Neo4jDocumentWriterTest
```

## What is unfinished or known to be wrong

Not finished: no delete uses its `PersistenceDeletePolicy`. Core passes `FAIL_IF_INCOMING_LINKS` to
`markDocumentDeleted`, and a document that others link to is marked as deleted all the same. The shared suite
expects that: `thatDeleteAllWithIncomingRefWorks` passes `FAIL_IF_INCOMING_LINKS` to `deleteAllDocumentVersions`,
then deletes an entity type whose documents another document still links to, and asserts that they are gone.

Not finished: `Transaction.close` commits. The rule is that a transaction commits only when asked.
`Neo4jTransaction` inherits `close` from the persistence interface, so a `try` block that is left by an exception
commits what its statements have done so far, unless a statement itself failed.

Not finished: the statement that `neo4j.cypher.show` logs cannot always be run by hand. `interactive` writes
nothing for a boolean (`{name: 'vegetarian', value: }`) and does not escape a quote in a text
(`value: 'it's mine'`). The log also holds the values of the documents.

Not finished: `TransactionStatistics` are never filled.

Not finished: nothing removes a document node that was created for a link, when the link is gone and the
document was never written. `deleteAllDocumentVersions` and `deleteAllEntities` remove it when they are called for
that document or its entity type; core calls neither. Core calls `createOrOverwrite`, `markDocumentDeleted` and the
reads, and does not call `deleteDocument`, `deleteAllDocumentVersions`, `deleteAllEntities` or `findDocument`.

Not finished: an entity type that the specification does not have gets no constraint and no index. Its documents are
found by a scan of the label, and two writers at once can make two document nodes for one id.

Not finished: the driver can only be given an address, a user and a password. There is no key for the database,
the connection pool or the time the driver keeps trying.

Not finished: `persistence.initialization.max-wait-seconds` does not bound the start when Neo4j cannot be
reached. One call takes from about half a minute to about a minute by itself, also when the key is 0 (see
Configuration).

Not finished: a document with a field named `[]` is written and then cannot be read. The writer gives the
`EMBED` relationship of that field the `path` `[]` and no `index`, as `Neo4jDocumentWriter.record` shows, and
`Neo4jPersistence.computePath` takes every `EMBED` with the `path` `[]` for an item of an array and reads its
`index`: `toDocument` fails with `Uncoercible: Cannot coerce NULL to Java Number`. A list that holds such a
document fails in the same way. This was found by running `record` and `toDocument` without Neo4j; it was not
tried on a Neo4j. Core accepts such a field in a document of the example model (answer 201, run with the provider
`mem`).

Not finished: the name of a field is not told from a path when a document is read. `computePath` joins the
names with dots, so a field named `a.b` is read back as an object `a` with a field `b`, and a field named
`x[3]` as an array `x` with one item. Run without Neo4j, a document written as `{"a.b":1,"x[3]":2}` is read as
`{"a":{"b":1},"x":[2]}`. Core accepts such fields in a document of the example model, and the provider `mem`
reads them back in the same way (run through LDS).

Other things to know:

- `Neo4jIndexManagement` tells an index by its first label and its properties only. Any row of `SHOW INDEXES`
  with the label `<entity>_E` and the properties `namespace`, `path`, `hashOrValue`, and no constraint, counts as
  the index that is wanted, whatever kind of index it is, and the wanted one is then not created.
- `findDocument` is given the value as a text and compares it with `hashOrValue`. A number or a boolean is stored
  as a number or a boolean, so it is not found by its text.
- A whole number that a `long` does not hold is written as the nearest `double`, and is read back as that.
- Memory and PostgreSQL also follow a text `/Entity/id` in a field that the model does not call a link, when
  asked for that path. Neo4j does not. LDS does not ask for it.
- The label of the nodes of a document is the name of the entity type with `_E` after it. Nothing checks that the
  model has no entity type of that name.
