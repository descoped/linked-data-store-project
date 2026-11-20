# The PostgreSQL persistence provider

This document covers the module `linked-data-store-persistence-provider-postgres`: the persistence provider
`postgres`, which keeps every document in one table of a PostgreSQL database. It is for developers who change the
provider or look for a fault in it.

## What the provider is for

LDS keeps its documents in a [persistence provider](../glossary.md#persistence-provider) that is chosen in the
configuration. This provider keeps them in PostgreSQL. It stores a document as rows, one row for each value of
the document, and it reads them back as a [reactive stream](../glossary.md#reactive-stream) of rows that
PostgreSQL gives out as they are asked for.

The provider implements `RxPersistence`, the interface that carries [fragments](../glossary.md#fragment). A
fragment is one leaf value of one version of one document. `RxJsonPersistenceBridge` in
`linked-data-store-persistence-provider-api` turns documents into fragments and fragments into documents, so core
sees `RxJsonPersistence`. The interface and the bridge are described in
[the persistence interface](persistence-api.md).

One database holds any number of [namespaces](../glossary.md#namespace). The namespace is the first part of the
key of every row.

## The examples in this document

The outputs shown here were taken from LDS `3.0.0-SNAPSHOT` with the example model
(`linked-data-store-server/schemas/schema.graphql`) and this provider. PostgreSQL was version 18.7, on the same
machine, listening on the address `::1` and the port 19104, with a database `lds` and a user `lds` with the
password `lds`. It was started with `log_statement=all`, so its log shows every statement LDS sent. LDS listened
on `127.0.0.1`, port 19104. It was started from a folder that has every jar of the build in `lib`:

```sh
LDS_HTTP_PORT=19104 LDS_HTTP_HOST=127.0.0.1 LDS_PERSISTENCE_PROVIDER=postgres \
  LDS_POSTGRES_DRIVER_HOST='[::1]' LDS_POSTGRES_DRIVER_PORT=19104 LDS_POSTGRES_DRIVER_PASSWORD=lds \
  java -cp "lib/*" io.descoped.lds.server.Server > lds.log 2>&1 &
```

```
... PersistenceConfigurator - Using persistence provider: postgres
... PersistenceConfigurator - Persistence provider initialization attempt # 1
... com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Starting...
... com.zaxxer.hikari.pool.HikariPool - HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@3e1162e7
... com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Start completed.
... PersistenceConfigurator - Persistence service-provider configured
...
... io.descoped.lds.server.Server - Server started in 467ms..
```

No test in the repository runs LDS as a whole on this provider (see [Not finished](#not-finished)).

## Layout

The Maven module is `linked-data-store-persistence-provider-postgres`. Its JPMS module is
`io.descoped.lds.persistence.postgres`. All classes are in the package
`io.descoped.lds.core.persistence.postgres`. The module exports no package. Only `PostgresDbInitializer` is
public, because the service loader creates it.

| Class or file | What it is responsible for |
|---|---|
| `PostgresDbInitializer` | The entry of the provider (`PersistenceInitializer`, name `postgres`). It names the configuration keys, opens the connection pool, creates the table when it is missing, and returns the provider wrapped in `RxJsonPersistenceBridge`. |
| `PostgresPersistence` | Every statement the provider sends. It implements `RxPersistence`. The inner class `WriteBatch` holds the statements of one write. The inner class `Query` builds the text of a statement and its parameters side by side. |
| `ResultSetPublisher` | A Reactive Streams `Publisher` over a JDBC `ResultSet`. It moves the cursor one row for each row that is requested. |
| `PostgresTransactionFactory` | Owns the connection pool. Opens a transaction on a connection of the pool. |
| `PostgresTransaction` | One connection with a transaction open on it. Commit or cancel ends it and gives the connection back. |
| `JavaUtilLoggerBridge` | Sends what the JDBC driver logs with `java.util.logging` to SLF4J. It resets the configuration of `java.util.logging` once and sets the level of the root logger (`INFO`, given by `PostgresDbInitializer`). |
| `init-db.sql` | The statements that create the table and its index. It is a resource next to `PostgresDbInitializer`. |

`module-info.java` requires the persistence API, `java.sql`, `com.zaxxer.hikari`, `org.postgresql.jdbc`,
`java.logging`, `jul.to.slf4j` and `io.reactivex.rxjava3`. The JDBC driver of PostgreSQL is an automatic module,
so the descriptor has `@SuppressWarnings("requires-automatic")`. The provider is registered in two places:
`provides PersistenceInitializer with PostgresDbInitializer` in `module-info.java`, and the file
`META-INF/services/io.descoped.lds.api.persistence.PersistenceInitializer`.

The versions of HikariCP and of the JDBC driver are the properties `hikaricp.version` and `postgresql.version` in
the root POM.

## The table `fragment`

There is one table. One row is one leaf value of one version of one document.

| Column | Type | What it holds |
|---|---|---|
| `namespace` | `varchar`, collation `POSIX`, not null | The namespace of the document. |
| `entity` | `varchar`, collation `POSIX`, not null | The [entity type](../glossary.md#entity-type) of the document, for example `Person`. |
| `id` | `varchar`, collation `POSIX`, not null | The id of the document. |
| `version` | `timestamp(3) with time zone`, not null | The [valid time](../glossary.md#valid-time) of this version. An instant with millisecond precision. |
| `path` | `varchar`, collation `POSIX`, not null | The path of the value in the document, with the array positions taken out: the value at `$.friends[1]` has the path `$.friends[]`. Empty in a [tombstone](../glossary.md#tombstone). |
| `indices` | `integer[]`, not null | The array positions that were taken out of the path, in order: `{1}` for `$.friends[1]`. `{}` when the path has no array. |
| `type` | `smallint`, not null | The kind of value, a code of `FragmentType`: 0 `NULL`, 1 `DELETED`, 2 `EMPTY_OBJECT`, 3 `EMPTY_ARRAY`, 4 `STRING`, 5 `NUMERIC`, 6 `BOOLEAN`. |
| `value` | `bytea`, null allowed | The bytes of the value, as the bridge gives them. Null in a tombstone. |

The primary key is `(namespace, entity, id, version, path, indices)`. Its order is the order LDS stores and reads
in: document, then version, then path.

The index `fragment_value_idx` is on `(sha256(value), namespace, entity, path)`. It finds the documents that hold
a value at a path. The comments in `init-db.sql` give the two reasons for its form:

- It is on a hash of the value, because an index row has a size limit that a long value exceeds.
- The hash comes first, so that only a search for a value can use the index. With the namespace and the entity
  type first, PostgreSQL may pick it in place of the primary key for a read or a delete of one document while the
  table has no statistics yet, and then walks every row of the entity type.

The collation `POSIX` orders text by its bytes, which is the order of code points. Companies with the ids `Zeta`,
`acme`, `alpha`, `big`, `kinds`, `~tilde` and `Ærlig` were listed in this order.

This is the table as PostgreSQL describes it after the first start of LDS:

```sh
PGPASSWORD=lds psql -h ::1 -p 19104 -U lds -d lds -X -c '\d fragment'
```

```
                         Table "public.fragment"
  Column   |            Type             | Collation | Nullable | Default
-----------+-----------------------------+-----------+----------+---------
 namespace | character varying           | POSIX     | not null |
 entity    | character varying           | POSIX     | not null |
 id        | character varying           | POSIX     | not null |
 version   | timestamp(3) with time zone |           | not null |
 path      | character varying           | POSIX     | not null |
 indices   | integer[]                   |           | not null |
 type      | smallint                    |           | not null |
 value     | bytea                       |           |          |
Indexes:
    "fragment_pkey" PRIMARY KEY, btree (namespace, entity, id, version, path, indices)
    "fragment_value_idx" btree (sha256(value), namespace, entity, path)
```

A document as rows. A company `acme` with `{"name":"Acme"}` was written first. This request then writes a person
with an [embedded object](../glossary.md#embedded-object) (`address`), a [link](../glossary.md#link)
(`employer`) and an array of links (`friends`):

```sh
curl -s -i -X PUT 'http://127.0.0.1:19104/ns/Person/ola?sync=true&timestamp=2020-01-01T00:00:00Z' \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Ola",
    "address": {"street": "Storgata 1", "city": "Oslo"},
    "employer": "/Company/acme",
    "friends": ["/Person/kari", "/Person/per"]
  }'
```

```
HTTP/1.1 201 Created
...
```

```sh
PGPASSWORD=lds psql -h ::1 -p 19104 -U lds -d lds -X -c "select namespace, entity, id, version at time zone
  'UTC' as version_utc, path, indices, type, convert_from(value, 'UTF8') as value from fragment
  order by namespace, entity, id, version, path, indices"
```

```
 namespace | entity  |  id  |     version_utc     |       path       | indices | type |     value
-----------+---------+------+---------------------+------------------+---------+------+---------------
 ns        | Company | acme | 2020-01-01 00:00:00 | $.name           | {}      |    4 | Acme
 ns        | Person  | ola  | 2020-01-01 00:00:00 | $.address.city   | {}      |    4 | Oslo
 ns        | Person  | ola  | 2020-01-01 00:00:00 | $.address.street | {}      |    4 | Storgata 1
 ns        | Person  | ola  | 2020-01-01 00:00:00 | $.employer       | {}      |    4 | /Company/acme
 ns        | Person  | ola  | 2020-01-01 00:00:00 | $.friends[]      | {0}     |    4 | /Person/kari
 ns        | Person  | ola  | 2020-01-01 00:00:00 | $.friends[]      | {1}     |    4 | /Person/per
 ns        | Person  | ola  | 2020-01-01 00:00:00 | $.name           | {}      |    4 | Ola
(7 rows)
```

A link is a text value like any other. There is no table or column for links.

The bytes of a value are made by the bridge (`FlattenedDocumentLeafNode.valueByOffset` in the API module), not
by this provider. A document `{"name":"Kinds","about":[],"employees":17,"listed":true,"ratio":0.5,"extra":{}}`
was stored as these rows (`value_as_text` is `convert_from(value, 'UTF8')`):

```
  id   |    path     | indices | type |    value     | value_as_text
-------+-------------+---------+------+--------------+---------------
 kinds | $.about     | {}      |    3 | \x           |
 kinds | $.employees | {}      |    5 | \x3137       | 17
 kinds | $.extra     | {}      |    2 | \x           |
 kinds | $.listed    | {}      |    6 | \x01         | \x01
 kinds | $.name      | {}      |    4 | \x4b696e6473 | Kinds
 kinds | $.ratio     | {}      |    5 | \x302e35     | 0.5
(6 rows)
```

A text is its UTF-8 bytes. A number is its text (`17`, `0.5`). `true` is one byte, 1, and `false` is one byte, 0.
An empty array and an empty object are one row each with an empty value. A null is one row of type 0 with an
empty value: `{"name":"N","listed":false,"extra":null}` was stored with `$.extra` as type 0 and `\x`, and
`$.listed` as type 6 and `\x00`. Only a tombstone has no value (SQL null).

A value is never split over several rows. `PostgresDbInitializer` gives the bridge a fragment capacity of
`Integer.MAX_VALUE`, and `WriteBatch.add` refuses a fragment whose offset is not 0 with
`IllegalStateException: Postgres fragments must have offset == 0, illegal offset: ...`.

## How the table is created at start

`PostgresDbInitializer.initialize` does this, in order:

1. It installs the bridge from `java.util.logging` to SLF4J (`JavaUtilLoggerBridge`).
2. It opens the connection pool (`openDataSource`): a `PGSimpleDataSource` with the host, port, user, password and
   database of the configuration, inside a `HikariDataSource` with auto-commit off and at most 10 connections
   (`POOL_SIZE`).
3. It runs `init-db.sql` on one connection, in one transaction, and commits (`createTableIfMissing`).
4. It creates `PostgresPersistence` and returns it inside `RxJsonPersistenceBridge`.

If step 3 fails, the pool is closed and the exception goes to the caller. The arguments `defaultNamespace` and
`managedDomains` are not used: the namespace comes with every call, and one table holds every entity type.

`init-db.sql` has three statements:

1. `SELECT pg_advisory_xact_lock(7308327816128787041)`. Instances of LDS that start at the same time run the file
   one after the other. The comment in the file gives the reason: `CREATE ... IF NOT EXISTS` is not safe when two
   transactions create the same table at once. The lock is released when the transaction ends. The number only
   has to be the same for every instance.
2. `CREATE TABLE IF NOT EXISTS fragment (...)`.
3. `CREATE INDEX IF NOT EXISTS fragment_value_idx ...`.

The file runs at every start. It creates what is missing and changes nothing that exists. The provider has no
other statement that changes the table definition. The database and its user must exist before LDS starts, and
LDS creates the table as that user.

`PostgresProviderTest.instancesThatStartTogetherOnAnEmptyDatabaseAllStart` starts four instances at once on a new
database, five times. `PostgresProviderTest.startingLeavesNoConnectionInUse` checks that the connection of step 3
is back in the pool.

The caller of `initialize` is `PersistenceConfigurator` in core. When `initialize` throws, it waits a second and
tries again, until `persistence.initialization.max-wait-seconds` have passed. With PostgreSQL not running and the
60 seconds of the server defaults, LDS made 31 attempts, one every two seconds, and then ended with exit code 1
and this text:

```
Exception in thread "main" com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize
pool: Connection to [::1]:19104 refused. Check that the hostname and port are correct and that the postmaster is
accepting TCP/IP connections.
```

When PostgreSQL was started 7 seconds after LDS, the fifth attempt succeeded and LDS started.

`PersistenceConfigurator` catches every `RuntimeException` and does not look at what failed. A wrong password, a
database that does not exist and a port that is not a number are tried again in the same way until the wait is
over. With a wait of 3 seconds, a wrong password gave two attempts and the port `notanumber` gave four, and
LDS then ended with the exception of the last attempt.

## Writing

`PostgresPersistence.createOrOverwrite` takes a transaction and a stream of fragments. The stream may hold the
fragments of several documents and of several versions of a document. For one write, `WriteBatch` prepares two
statements:

```sql
DELETE FROM fragment WHERE namespace = ? AND entity = ? AND id = ? AND version = ?

INSERT INTO fragment(namespace, entity, id, version, path, indices, type, value) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
```

For each fragment, `WriteBatch.add` does this:

1. It refuses a fragment whose offset is not 0.
2. The first time it sees a document version (`DocumentKey`: namespace, entity type, id and valid time), it adds
   one `DELETE` for that version to the delete batch. So a version that is written again is replaced whole: a
   value that the new document does not have is gone.
3. It splits the path into the path without positions and the positions (`Fragment.computeIndexUnawarePath`).
4. It adds one `INSERT` to the insert batch.
5. After every 100 inserts (`WRITE_BATCH_SIZE`) it sends the batches: first the deletes that wait, then the
   inserts (`WriteBatch.execute`).

When the stream ends, `WriteBatch.complete` sends what is left. Both statements are closed when the write ends,
also when it fails. The write does not commit; the transaction of the caller does.

The 100 counts the inserts of the whole write, not of one document. A document with 151 values was written as
one `DELETE` and 151 `INSERT` in the log of PostgreSQL. `WriteBatch` keeps the `DocumentKey` of every document
version it has seen until the write ends (`deletedDocuments`), so the memory of one write grows with the number
of document versions in it.

A statement that fails in `WriteBatch.add` (a batch that is sent after 100 inserts) reaches the caller as
`PersistenceException`. One that fails in `WriteBatch.complete` (the last batch) reaches the caller as the
`SQLException` of the driver.

This is what PostgreSQL logged for the request above (the six inserts shortened to one):

```
LOG:  statement: BEGIN
LOG:  execute <unnamed>: DELETE FROM fragment WHERE namespace = $1 AND entity = $2 AND id = $3 AND version = $4
DETAIL:  Parameters: $1 = 'ns', $2 = 'Person', $3 = 'ola', $4 = '2020-01-01 01:00:00+01'
LOG:  execute S_2: INSERT INTO fragment(namespace, entity, id, version, path, indices, type, value) VALUES ($1,
      $2, $3, $4, $5, $6, $7, $8)
DETAIL:  Parameters: $1 = 'ns', $2 = 'Person', $3 = 'ola', $4 = '2020-01-01 01:00:00+01', $5 = '$.employer',
      $6 = '{}', $7 = '4', $8 = '\x2f436f6d70616e792f61636d65'
...
LOG:  execute S_1: COMMIT
```

PostgreSQL writes a time in the zone of its session; `2020-01-01 01:00:00+01` is the instant
`2020-01-01T00:00:00Z`. The `COMMIT` comes from core: `PersistenceCreateOrOverwriteSagaAdapter` opens the
transaction in a try-with-resources block, and `Transaction.close` commits.

## Reading

### One statement for each read

Every read of `RxPersistence` is one `SELECT`. It returns the columns `id, version, path, indices, type, value`,
ordered by document, then version, then path, then positions. `PostgresPersistence.toFragment` makes a fragment
of each row: it puts the positions back into the path (`Fragment.computePathFromIndexUnawarePathAndIndices`) and
reads the version as a time in `Etc/UTC`. The namespace and the entity type of the fragment are those of the
call; the statement does not select them. The offset is 0.

A range is backward when it has `last`, or when it has `before` and no `after` (`Range.isBackward`). So a range
with only an upper bound is read from the end, also without a limit.

| Method | What its statement selects |
|---|---|
| `read` | The rows of one document at the greatest version that is not after the snapshot. Ordered by path and positions. |
| `readVersions` | The rows of every version of one document, oldest first, or newest first for a backward range. `before` is exclusive. `after` is not a strict bound: the version that was valid at that time is the first one. The limit counts versions. |
| `readAll` | For every id of the entity type inside the range, the rows of the version valid at the snapshot. By ascending id, or descending for a backward range. Both bounds are exclusive. The limit counts documents. A document that is marked as deleted at the snapshot comes as its tombstone. |
| `find` | The rows of every document whose version valid at the snapshot holds the given value at the given path. Same order, bounds and limit as `readAll`. |
| `hasNext`, `hasPrevious` | `readAll` with the range `Range.firstAfter(1, id)` or `Range.lastBefore(1, id)`; the answer is whether a row came. Nothing calls these two: the bridge has its own (see [Not finished](#not-finished)), and no test calls them. |

`read` is one statement with a subquery for the version. This is the statement of a `GET /ns/Person/ola`, from
the log of PostgreSQL (wrapped here):

```
LOG:  execute <unnamed>/C_3: SELECT f.id, f.version, f.path, f.indices, f.type, f.value FROM fragment f
      WHERE f.namespace = $1 AND f.entity = $2 AND f.id = $3 AND f.version = (SELECT max(c.version) FROM
      fragment c WHERE c.namespace = $4 AND c.entity = $5 AND c.id = $6 AND c.version <= $7)
      ORDER BY f.path, f.indices
DETAIL:  Parameters: $1 = 'ns', $2 = 'Person', $3 = 'ola', $4 = 'ns', $5 = 'Person', $6 = 'ola',
      $7 = '2026-11-20 16:30:17.233+01'
```

The other three reads have two parts. An inner query picks the document versions: ids with their version, in
the order of the result. `PostgresPersistence.documentsOf` completes the statement: it puts the limit of the
range on the inner query, so that the limit counts document versions and not rows, and it fetches the rows of
each picked version with `CROSS JOIN LATERAL`, one version after the other. The condition of the lateral part is
on the first four columns of the primary key (namespace, entity, id, version). This is the statement of a list
(`readAll`, no bounds), from the log:

```
LOG:  execute <unnamed>/C_12: SELECT f.id, f.version, f.path, f.indices, f.type, f.value FROM (SELECT c.id,
      max(c.version) AS version FROM fragment c WHERE c.namespace = $1 AND c.entity = $2 AND c.version <= $3
      GROUP BY c.id ORDER BY c.id) d CROSS JOIN LATERAL (SELECT r.id, r.version, r.path, r.indices, r.type,
      r.value FROM fragment r WHERE r.namespace = $4 AND r.entity = $5 AND r.id = d.id AND r.version =
      d.version ORDER BY r.path, r.indices) f ORDER BY d.id, f.path, f.indices
```

The Javadoc of `documentsOf` gives the reason for the lateral form. Written as a join of the two, PostgreSQL
chooses the way to run it from its statistics, and without statistics (a table filled a moment ago) it compares
every row with every document. In the lateral form the first document is returned before the next one is looked
up, whatever the statistics say.

How PostgreSQL reads the rows of one version is still its choice. `EXPLAIN` of the `find` statement, on the table
of these examples with 210 rows, showed `Index Scan using fragment_value_idx` for the inner query,
`Index Only Scan Backward using fragment_pkey` for the newest version, and a sequential scan for the rows of each
version. With sequential scans switched off (`set enable_seqscan = off`) it read those rows through
`fragment_pkey`, at a cost it counted as a little higher on a table that small.

### Rows on demand: `ResultSetPublisher`

`PostgresPersistence.fragments` turns a statement into a stream:

```java
return Flowable.using(
        () -> query.prepare(connection),
        statement -> {
            statement.setFetchSize(FETCH_SIZE);
            return Flowable.fromPublisher(new ResultSetPublisher(statement.executeQuery()))
                    .map(row -> toFragment(row, namespace, entity));
        },
        PreparedStatement::close);
```

- The connection is taken from the transaction when the read method is called. The statement is prepared and run
  when the stream is subscribed to.
- The statement is closed when the stream completes, fails or is cancelled. Closing it closes the result set and
  the cursor on the server.
- The fetch size is 10 (`FETCH_SIZE`). The connection has auto-commit off. With both, the JDBC driver opens a
  cursor on the server and fetches ten rows at a time, when the result set is moved past the rows it holds. With
  auto-commit on, the driver opens no cursor: a statement with fetch size 10 that was run on a connection with
  auto-commit on left no cursor on the server after its first row, and one with auto-commit off left one.

`ResultSetPublisher` is the part that asks. It calls `ResultSet.next()` once for each row that the subscriber
requested, and never a row ahead of the demand. It has no thread of its own: a row is delivered on the thread
that requested it, and the JDBC call blocks that thread. These are the things to know before changing it:

- The item it gives is the result set itself, positioned on the new row, and the same object for every row. The
  subscriber must read the row inside `onNext`. `fragments` does that with `map`.
- It does not close the result set. `Flowable.using` closes the statement.
- `requested` and `published` are running totals. `publicationPending` is held by the one call that delivers
  rows, so two threads that request at once do not move the cursor together. `publish` is a loop and not a call
  for each row, so a result of any size is walked at the same stack depth.
- A request for zero or fewer rows ends the stream with `onError(IllegalArgumentException)`, with the message
  `requested amount must be > 0`, and nothing follows it.
- A publisher that is given no result set (null) signals `onSubscribe` and then `onError`
  (`NullPointerException`). The Reactive Streams TCK uses that as its failed publisher.
- The end is seen only when a row is requested that is not there: `onComplete` follows the `ResultSet.next()`
  that returns false. A subscriber that requests exactly as many rows as the result has gets no `onComplete`
  until it requests one more.
- An exception of `ResultSet.next()` goes to `onError` as it is (a `SQLException`), unless the subscription was
  cancelled. An exception that `onNext` throws cancels the subscription and goes to `onError` of the same
  subscriber.

A list of 27 rows shows the cursor in the log of PostgreSQL: the statement is run once with a named cursor
(`C_12`) and then fetched from twice.

```
LOG:  statement: BEGIN
LOG:  execute <unnamed>/C_12: SELECT f.id, f.version, ...
LOG:  execute fetch from <unnamed>/C_12: SELECT f.id, f.version, ...
LOG:  execute fetch from <unnamed>/C_12: SELECT f.id, f.version, ...
LOG:  execute S_1: COMMIT
```

The name before the slash is the name of the prepared statement. It is `<unnamed>` at first. After the JDBC
driver has run the same text several times on a connection, it keeps the statement prepared on the server, and
the log shows a name (`execute S_9/C_16: SELECT ...`).

`PostgresProviderTest.aReadLeavesNoCursorOpenOnTheServer` counts the cursors of the connection in `pg_cursors`:
none after a read to the end, none after a stream that is cancelled after its first row, none after `hasNext`.

### Versions

A version is stored and compared as an instant, cut to whole milliseconds (`PostgresPersistence.instantOf`). The
zone a time was given in is not stored. A version is read back as a time in `Etc/UTC`. The snapshot of a read is
cut the same way.

A document written with `timestamp=2020-01-01T00:00:00.123456Z` was stored with the version
`2020-01-01 00:00:00.123`. A read with `timestamp=2020-01-01T00:00:00.1234Z` found it, and a read with
`timestamp=2020-01-01T00:00:00.122Z` answered 404. A document written with
`timestamp=2021-06-01T12:00:00+02:00` was stored with the version `2021-06-01 10:00:00` UTC.

An [as-of read](../glossary.md#as-of-read) gives the version with the greatest valid time that is not after the
time of the read. So a version is valid until the next one, and a time before the first version gives nothing.
The versions of a document are rows with the same namespace, entity type and id and different `version`.
Nothing marks a version as the current one.

`readVersions` with a lower bound starts at the version that was valid at the bound, not at the first version
after it. The statement does that with
`v.version >= coalesce((SELECT max(c.version) ... AND c.version <= ?), '-infinity')`.
`PostgresProviderTest.theVersionsOfADocumentAreReadInOrderOfTime` holds it to that. `RxJsonPersistenceBridge`
then leaves out the versions that are not after the bound, so a caller of the bridge does not see that version.

### Deleting

| Method | What it does |
|---|---|
| `markDeleted` | Deletes the rows of the document at the given time, then inserts one row at that time: the tombstone, with an empty path, the positions `{}`, the type 1 (`DELETED`) and no value. The tombstone is a version of its own. |
| `delete` | Deletes the rows of one version. The version before it is then the one that is valid at that time. |
| `deleteAllVersions` | Deletes every row of one document. |
| `deleteAllEntities` | Deletes every row of one entity type in one namespace. The argument `paths` is not used. |

No delete uses its argument `PersistenceDeletePolicy`. None looks for documents that link to the one that is
deleted.

Core calls only `markDocumentDeleted` (`PersistenceDeleteSagaAdapter`). So a delete through LDS removes no
version: the earlier versions stay, and an as-of read at an earlier time finds them. After
`DELETE /ns/Person/ola?sync=true&timestamp=2022-01-01T00:00:00Z` (answer 204) the table held this, next to the
earlier versions:

```
 id  |     version_utc     |       path       | indices | type |            value
-----+---------------------+------------------+---------+------+------------------------------
...
 ola | 2021-06-01 10:00:00 | $.name           | {}      |    4 | \x4f6c61204e6f72646d616e6e
 ola | 2022-01-01 00:00:00 |                  | {}      |    1 |
```

A tombstone and a version at the same time replace each other: `markDeleted` deletes the rows at that time
first, and a write deletes the rows of the version it writes, the tombstone among them.

`markDeleted` does not look whether the document exists. `DELETE /ns/Person/neverwas?sync=true&timestamp=...`
for an id that was never written answered 204 and left one tombstone row for that id.

`read` returns the tombstone when it is the version valid at the snapshot. The bridge turns it into a deleted
document and leaves it out. `find` does not return a document whose valid version is a tombstone: the tombstone
has no row with the path that is searched for, and an earlier version is not the valid one.

### Links and `find`

A link is stored as the text `/Entity/id` in the row of its field. The provider knows nothing more about links.
The bridge follows them with the reads above:

- From a document to the documents it links to (`RxJsonPersistenceBridge.readTargetDocuments`): one `read` of the
  document, then one `read` for each link that is followed. A dangling link, to a document that does not exist,
  gives nothing.
- From a document to the documents that link to it (`readSourceDocuments`): one `find`, with the path of the link
  field and the bytes of `/Entity/id` as the value.

`find` takes the path without array positions (`$.friends[]`) and the value as it is stored. It compares the
path and the bytes, not the column `type`: a text `17` and a number `17` at the same path are the same bytes.
Its inner query compares the hash and then the value, so the index can be used and a hash that two values share
does no harm:

```
... FROM (SELECT DISTINCT m.id, m.version FROM fragment m WHERE m.namespace = $1 AND m.entity = $2 AND
m.path = $3 AND sha256(m.value) = sha256($4) AND m.value = $5 AND m.version <= $6 AND m.version = (SELECT
max(c.version) FROM fragment c WHERE c.namespace = m.namespace AND c.entity = m.entity AND c.id = m.id AND
c.version <= $7) ORDER BY m.id) d CROSS JOIN LATERAL ...
DETAIL:  Parameters: $1 = 'ns', $2 = 'Person', $3 = '$.employer', $4 = '\x2f436f6d70616e792f61636d65',
$5 = '\x2f436f6d70616e792f61636d65', $6 = '2026-11-20 16:30:59.688+01', $7 = '2026-11-20 16:30:59.688+01',
$8 = 'ns', $9 = 'Person'
```

That statement came from the GraphQL query `{ CompanyById(id: "acme") { name employees { edges { node { name } } } } }`.

- `DISTINCT` gives a document once when an array holds the value twice
  (`PostgresProviderTest.findSeesAValueInsideAnArrayOnce`).
- Only the version valid at the snapshot counts. A document that held the value in an earlier version only is
  not returned (`findLooksAtTheVersionValidAtTheSnapshot`).
- A value of any length is stored and found (`aLongTextIsStoredAndFound`, 200 000 characters).

## Transactions and the connection pool

The pool is a `HikariDataSource` with at most 10 connections (`POOL_SIZE` in `PostgresDbInitializer`) and
auto-commit off. Nothing else of the pool is set, so the rest are the defaults of HikariCP. After the start of
LDS, PostgreSQL showed 10 idle connections of the user `lds`.

`PostgresTransactionFactory.createTransaction` takes a connection from the pool, sets auto-commit off and returns
a `PostgresTransaction`. Its argument `readOnly` is not used.

A `PostgresTransaction` holds its connection until it ends. `commit` and `cancel` both go to `end`: commit or
roll back, then close the connection, which gives it back to the pool. The first call wins; a later `commit` or
`cancel` does nothing and returns a completed future. A commit that fails returns a future that failed with
`PersistenceException`. The class does not override `Transaction.close`, and the default in the API commits.

The provider does not commit by itself, with one exception: the creation of the table at start.

The provider has no thread and no scheduler. A statement runs on the thread that subscribes to the stream or
requests from it, and that thread waits for PostgreSQL.

When all 10 connections are in use, the next `createTransaction` waits for one, for 30 seconds at most (the
default of HikariCP), and then throws `PersistenceException`. With every read held by a lock on the table for 40
seconds, 13 requests at once gave 10 that waited in PostgreSQL and were answered 200 when the lock was gone, and
3 that were answered `500 Internal Server Error` after 30 seconds, with this in the log of LDS:

```
io.descoped.lds.api.persistence.PersistenceException: java.sql.SQLTransientConnectionException: HikariPool-1 -
Connection is not available, request timed out after 30001ms (total=10, active=10, idle=0, waiting=2)
```

The pool finds its way back by itself. PostgreSQL was stopped while LDS ran. `GET /ns/Person/kari` was answered
500: at once in a run where LDS had used a connection a second before the stop, and after 30 seconds in a run
where it had not. `/health/alive` was answered 503 after 30 seconds. After PostgreSQL was started again, both
were answered 200 without a restart of LDS.

`createTransaction` throws `PersistenceException`, and a commit or a roll back that fails gives a future that
failed with it. Everything else gives the `SQLException` of the driver as it is: a read (through the stream), a
delete, and the last batch of a write.

After a statement has failed, PostgreSQL refuses every later statement of that transaction
(`current transaction is aborted`) and answers its `COMMIT` with a roll back. In a transaction with one write
that succeeded and one that failed, a read was refused, `commit` then gave a future that completed without an
error, and the first write was not in the table afterwards. `Transaction.close` in place of `commit` did not
throw either (see [Not finished](#not-finished)).

`PostgresPersistence.close` closes the factory, and the factory closes the pool.

## Configuration

The provider is chosen with `persistence.provider=postgres`. It reads five keys
(`PostgresDbInitializer.configurationKeys`). `PersistenceConfigurator` in core refuses to start when one of them
is not in the configuration (`Configuration missing for: [...]`). The defaults are in
`application-server-defaults.properties` of `linked-data-store-server`, so each key can be set by an environment
variable.

| Key | Environment variable | Default | Meaning |
|---|---|---|---|
| `postgres.driver.host` | `LDS_POSTGRES_DRIVER_HOST` | `localhost` | The host of PostgreSQL. |
| `postgres.driver.port` | `LDS_POSTGRES_DRIVER_PORT` | `5432` | The port. With a value that is not a number every attempt to start the provider fails with `NumberFormatException`, and LDS ends when the wait is over. |
| `postgres.driver.user` | `LDS_POSTGRES_DRIVER_USER` | `lds` | The user LDS connects as. |
| `postgres.driver.password` | `LDS_POSTGRES_DRIVER_PASSWORD` | empty | The password of the user. |
| `postgres.driver.database` | `LDS_POSTGRES_DRIVER_DATABASE` | `lds` | The database. It must exist. |

`persistence.initialization.max-wait-seconds` (default 60 in the server, 0 in core) is read by
`PersistenceConfigurator`: for how long it tries `initialize` again.

Nothing else can be configured: not the size of the pool, not the fetch size, not the size of a write batch, not
the name of the table. There is no key for TLS or for other options of the JDBC driver.

## Rules to keep

Most of these are rules of the project (see [the invariants](architecture.md#the-invariants)). The others are
from the comments of the code.

- **A read is a reactive stream.** A read is one statement whose rows are fetched as they are asked for. Do not
  replace a stream by a call that returns a complete list, and do not add a blocking call to an interface.
- **Keep auto-commit off and the fetch size.** Together they make the JDBC driver fetch a result in parts. With
  auto-commit on, the driver opens no cursor.
- **Close the statement of every read.** A statement that is read in parts holds a cursor on the server until it
  is closed. `Flowable.using` closes it on every way out.
- **Rows come in storage order:** id, version, path, positions. The bridge cuts the stream into documents where the
  key changes (`RxGroupByTools.groupByOrdered`), so the rows of one document version must come together.
- **No spliced identifiers.** Only text that is written in `PostgresPersistence` becomes part of a statement.
  Every value from outside (namespace, entity type, id, path, value, time, limit) is a parameter (`Query.value`). The
  names of the table and its columns are fixed text.
- **Time.** A version is stored and compared as a UTC instant, cut to milliseconds. Every time that goes into a
  statement goes through `instantOf`.
- **Transactions commit only when asked.** The provider never commits a transaction of a caller.
- **The limit counts documents**, or versions in `readVersions`, never rows. It belongs on the inner query.
- **Keep the lateral form** of `documentsOf` and **keep the hash first** in `fragment_value_idx`. Both are there for
  a table without statistics; the reasons are given above.
- **The start is safe for instances that start together.** Keep the advisory lock as the first statement of
  `init-db.sql`.
- **The type codes are stored.** `WriteBatch.add` and `markDeleted` write `FragmentType.ordinal()`, and
  `toFragment` reads with `FragmentType.fromTypeCode`. The two agree as long as every constant of `FragmentType`
  has its position as its code.
- **One persistence contract.** The provider passes [the shared suite](persistence-tck.md) on a real
  PostgreSQL. A change that needs a change of the suite changes the contract of every provider.
- **JPMS.** The provider is registered with `provides` in `module-info.java` and in `META-INF/services`. Keep both.
- **Lint and Javadoc.** The module has no override in its POM, so `-Xlint:all -Werror` and doclint `all` of the
  root POM apply to it.
- **3.0.0 is a clean break.** No code that migrates an older table.
- **PostgreSQL stays at 18**, see below.

## PostgreSQL is held at 18

A rule of the project is that every engine is at its latest stable version, with one exception: PostgreSQL stays
at 18 by the owner's decision, and the owner says when it is raised. It is written in three places:

- `Postgres18` in this module: the image `postgres:18-alpine` of the tests.
- `linked-data-store-server/compose.yaml` and `SagaLogPostgresTest` in core: the same image.
- `.github/renovate.json`: the rule for the Docker image `postgres` allows only versions that match
  `/^18([.-]|$)/`.

The JDBC driver is not held. It is a Maven dependency and follows the latest version.

## Tests

The tests need Docker. They run on a real PostgreSQL 18 in a container, on the class path, in the build of the
module.

| Class | Framework | What it checks |
|---|---|---|
| `PostgresIntegrationTest` | TestNG | The [shared suite](../glossary.md#shared-suite): it extends `PersistenceIntegrationTest` and gives it the provider. 32 tests, in the namespace `lds-provider-postgres-integration-test`. See [the shared suite](persistence-tck.md). |
| `ResultSetPublisherIntegrationTest` | TestNG | The Reactive Streams TCK (`PublisherVerification`) for `ResultSetPublisher`, over `SELECT * FROM GENERATE_SERIES(1, n)` with at most 1000 rows. It says that a call of `request` inside `onNext` goes at most one level deep (`boundedDepthOfOnNextAndRequestRecursion`). |
| `ResultSetPublisherSignalsTest` | JUnit | That nothing follows the error of a request for zero rows. It needs no database: the result set is a proxy. |
| `PostgresProviderTest` | JUnit | What the provider must do beyond the suite. 20 tests, each with an entity type of its own. |

`PostgresProviderTest` covers:

- The start: no connection is left in use; four instances that start together on an empty database all start.
- Namespaces: a document is seen in its own namespace only, by every kind of read and by a delete.
- Ids: a list holds every id in the order of Java, with ids that have a space, `!`, `~`, `Æ`, `Å` and `日本`.
- Versions: a version is the instant, whatever zone it was written with.
- `find`: every match, the range, the version valid at the snapshot, no document marked as deleted, a value in
  an array once.
- Long values: texts of three-byte characters, and a text of 200 000 characters that is stored and found.
- Large results: a document with 100 000 values, a list of 100 000 documents, a million rows through
  `ResultSetPublisher`.
- The reads of the provider itself: the limit counts documents and versions, a backward range is read from the
  end.
- Statements and transactions: no cursor is left open, a cancelled transaction writes nothing, ending a
  transaction again does nothing, a commit that fails fails its future.

The tests are in the package of the provider and reach its parts directly. For them
`PostgresDbInitializer.getPostgresPersistence`, `PostgresPersistence.transactionFactory`,
`PostgresTransactionFactory.dataSource` and `PostgresTransaction.connection` are not private.

`Postgres18` starts one container for every test class of the module, at first use, with the database, user and
password `lds`. Testcontainers stops it when the JVM ends. `Postgres18.configuration()` gives the five keys for
that container. `Postgres18.configurationOfNewDatabase(n)` creates an empty database `empty_n` in it.

`LoopbackPorts` publishes the ports of every container of these tests on 127.0.0.1 only, also of the container
Testcontainers starts itself (Ryuk). Testcontainers finds it through
`src/test/resources/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier`. `Postgres18` calls
`LoopbackPorts.check` after the start; it fails when a port is published on another address, which means the
services file is missing. The reason is in the Javadoc of the class and in [the build](build.md).

The module has no `logback.xml`. The one of the shared suite's jar is on its test class path.

The command for one module and what it needs is `./mvnw verify -pl <module> -am`. For this module it is the one
below. It was not run for this document.

```sh
./mvnw verify -pl linked-data-store-persistence-provider-postgres -am
```

## Not finished

Not finished: a list is not read on demand through the bridge. `RxJsonPersistenceBridge.readDocuments`,
`readDocumentVersions` and `findDocument` take the limit off the range (`Range.unlimited`) and sort the whole
stream of fragments (`Flowable.sorted`) before they cut it into documents. So the statement has no `LIMIT`, and
every row of it is fetched before the first document is given out. The GraphQL query below asks for two
companies. It ran the list statement without `LIMIT` and fetched it to the end, the 33 rows of all three
companies. It then ran two more statements without `LIMIT` for `hasNextPage` and `hasPreviousPage`, each with a
bound on the id (`c.id < $4`, `c.id > $4`). The limit of the provider is reached only through
`readSourceDocuments`, which gives its range to `find` as it is: a GraphQL query for `employees(first: 1)` of
one company ran the `find` statement with `ORDER BY m.id LIMIT $8`. `PostgresPersistence.hasNext` and
`hasPrevious` are not called by the bridge, which has its own.

```graphql
{ Company(first: 2) { edges { node { name } } pageInfo { hasNextPage hasPreviousPage } } }
```

```json
{
  "data": {
    "Company": {
      "edges": [
        {
          "node": {"name": "Acme"}
        },
        {
          "node": {"name": "Big"}
        }
      ],
      "pageInfo": {"hasNextPage": true, "hasPreviousPage": false}
    }
  }
}
```

Not finished: the order of ids can differ between PostgreSQL and the bridge. PostgreSQL orders ids by their
UTF-8 bytes, the order of code points. The bridge sorts by UTF-16 code units, as Java does. The Javadoc of
`PostgresPersistence` says where the two differ: between a character from U+E000 to U+FFFF and one above U+FFFF.
For ids with both, `hasNext` and `hasPrevious` can disagree with the list.

Not finished: a transaction that is closed commits. `Transaction.close` in the API commits, and
`PostgresTransaction` does not override it. A rule of the project is that a transaction commits only when asked
and rolls back otherwise, and [the invariants](architecture.md#the-invariants) name this as not true yet. Core
closes its read transactions the same way, so a read ends with `COMMIT` too.

Not finished: a commit does not tell that PostgreSQL rolled the transaction back. After a statement of the
transaction has failed, `PostgresTransaction.commit` sends `COMMIT`, PostgreSQL rolls back, and the future
completes without an error. In core the failure is still seen: a write that fails throws out of the
try-with-resources block of `PersistenceCreateOrOverwriteSagaAdapter`, and the close that follows is this roll
back.

Not finished: the argument `readOnly` of `createTransaction`, the argument `PersistenceDeletePolicy` of the
deletes and the argument `paths` of `deleteAllEntities` are not used.

Not finished: one write cannot hold the same document version twice. Two documents of one value each, in one
stream, with the same id and the same time, with times that are the same instant in two zones, or with times
less than a millisecond apart, failed with
`ERROR: duplicate key value violates unique constraint "fragment_pkey"`. `WriteBatch` sends the deletes of a
batch before its inserts, and adds one delete for each `DocumentKey`. So the rows of the first document are not
deleted before the second document inserts the same rows. Core writes one document in each transaction, so LDS
does not do this.

Not finished: no test runs LDS as a whole on this provider. The tests of core use the memory provider, and
`ServerStackIT` uses Neo4j for the documents. What this document shows of LDS on PostgreSQL was run by hand.

Not finished: the pool size (10), the time a caller waits for a connection, and options of the JDBC driver such
as TLS cannot be configured.
