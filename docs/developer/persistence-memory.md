# The memory provider

This document covers the module `linked-data-store-persistence-provider-memory`: the persistence provider that
keeps every document in the memory of the LDS process. It is for developers who change this module, or who need
to know what LDS does when it runs without a database.

## What it is for

The provider has the name `mem`. It is the provider LDS starts with when nothing else is configured
(`persistence.provider=mem` in `application-server-defaults.properties` of `linked-data-store-server`), and the
provider the tests of `linked-data-store-core` run on.

It is for tests and for trying LDS. It is not for production:

- Nothing is written to disk. When LDS stops, every document is gone.
- The store belongs to one process. Two LDS processes do not share it.
- One lock guards the whole store. One write runs at a time, and no read runs while a write does.
- It has known faults. They are listed under [Not finished](#not-finished).

The provider keeps *fragments*, not documents. A fragment is one leaf value of one version of one document, or
one piece of it when the value is a text longer than the fragment capacity. The
bridge of `linked-data-store-persistence-provider-api` (`RxJsonPersistenceBridge`) cuts a document into fragments
before a write and puts fragments together into documents after a read. The fragments, the bridge and the
interface the provider implements are described in [the persistence interface](persistence-api.md).

## Layout

All classes are in the package `io.descoped.lds.core.persistence.memory`. The JPMS module is
`io.descoped.lds.persistence.memory`. It exports no package: LDS finds the provider as a service and uses it
through the interface of the API module.

| Class | What it is responsible for |
|---|---|
| `MemoryInitializer` | The entry point, a `PersistenceInitializer`. It reads the configuration, builds the store and gives LDS a `RxJsonPersistenceBridge` around a `MemoryRxPersistence`. It keeps both, for the tests (`getPersistence`, `getRxJsonPersistence`). |
| `MemoryRxPersistence` | The logic of the provider, an `RxPersistence`. It turns each call into operations on ordered keys: which key a fragment gets, and which range of keys a read needs. It knows nothing about maps or locks. |
| `OrderedKeyValueTransaction` | The interface `MemoryRxPersistence` is written against: `set`, `clear`, `clearRange` and two forms of `getRange` on a named index of keys ordered as unsigned bytes. |
| `MemoryTransaction` | The only implementation of that interface. One sorted map for each index, the lock, the undo log, and how a `KeySelector` is resolved to a key. |
| `MemoryTransactionFactory` | Owns the maps, the lock and a pool of 8 threads (`Memory-Pool-Thread-N`). Makes transactions. |
| `MemoryDirectory`, `Subspace` | The directory gives each namespace, entity and path a short prefix. A `Subspace` is the part of the key space that starts with one prefix; it packs a tuple into a key and unpacks a key into a tuple. |
| `KeySelector`, `KeyValue` | Records. A `KeySelector` points at a key relative to a given key. A `KeyValue` is one entry of the store. |
| `AsyncIterable`, `AsyncIterator`, `MemoryAsyncIterable`, `MemoryAsyncIterator` | A range of entries that is read one entry at a time. `MemoryAsyncIterator` answers "is there a next entry" on a thread of the pool. |
| `AsyncIterablePublisher` | A Reactive Streams `Publisher` over an `AsyncIterable`. It delivers one entry for each one that is asked for. |

The provider is registered twice, as the build rules ask: `provides PersistenceInitializer with
MemoryInitializer` in `module-info.java`, and the file
`META-INF/services/io.descoped.lds.api.persistence.PersistenceInitializer`. `PersistenceConfigurator` in
`linked-data-store-core` picks the initializer whose `@ProviderName` equals `persistence.provider`, and checks
that `persistenceProviderId()` gives the same name. Both say `mem`.

The module depends on the API module, on `io.descoped.tuple:java-tuple` and on RxJava 3. `java-tuple` packs a
list of values into bytes whose order as unsigned bytes is the order of the values.

## The store

### Two sorted maps

`MemoryTransactionFactory.indexByIndex` holds one `ConcurrentSkipListMap<byte[], byte[]>` for each index name.
A map is made the first time its index name is used (`MemoryTransaction.getMapByIndex`). The keys are compared
with `Arrays.compareUnsigned`. `MemoryRxPersistence` uses two index names:

| Index | What it holds |
|---|---|
| `Primary` | Every fragment of every document: the key says which fragment, the value is the bytes of the fragment. |
| `PathValueIndex` | One entry for each first piece of a value (offset 0), with an empty value. It answers "which documents have this value at this path", which is how a link is followed backward. |

Both maps hold every namespace and every entity. What keeps them apart is the prefix of the key.

### The directory and the prefixes

`MemoryDirectory.createOrOpen` gives a tuple a `Subspace` with a prefix of 2 bytes. The first tuple gets `0000`,
the next `0001`, and so on. The same tuple always gets the same subspace. `MemoryRxPersistence` asks for two
kinds:

- `getPrimary(namespace, entity)`: the tuple `(namespace, "Primary", entity)`.
- `getIndex(namespace, entity, path)`: the tuple `(namespace, "PathValueIndex", entity, path)`, where the path has
  no array indices: `$.friends[0]` becomes `$.friends[]` (`Fragment.computeIndexUnawarePath`).

A subspace is made the first time it is asked for, also by a read. The directory is never emptied.

### The key of a fragment

In the subspace of its entity, a fragment has the key

```
(id, version, path, [type code], offset)  =  the bytes of the fragment
```

- `version` is a nested tuple of seven numbers, each negated: year, month, day, hour, minute, second,
  millisecond (`MemoryRxPersistence.toTuple`). Negated numbers make a newer version sort before an older one.
- `path` is the path of the leaf, with array indices: `$.name`, `$.friends[0]`.
- `[type code]` is one byte, the code of `FragmentType`: 0 null, 1 deleted, 2 empty object, 3 empty array,
  4 text, 5 number, 6 boolean.
- `offset` is where this piece starts in the value. A text longer than the fragment capacity is cut into pieces
  by the bridge; each piece is a fragment of its own.

So the entries of one document lie together, ordered by id, then by version with the newest first, then by path.
That order is what every read relies on.

A deletion marker is the key `(id, version, "", [1], 0)` with an empty value. It is the only entry of its
version.

### The key of an index entry

In the subspace of its entity and path, the first piece of a value has the key

```
(SHA-1 of the bytes of the piece, id, version, ((array indices of the path)))  =  empty
```

`Fragment.truncatedValue` gives the SHA-1. The method has that name, but it hashes and does not cut. The key may
be at most 256 bytes (`MAX_DESIRED_KEY_LENGTH`); a longer key fails the write with
`Document fragment key is too big for index, at most 256 bytes allowed. Was: N bytes.`

### One document in the maps

This is what the maps hold after one write and one deletion marker, with a fragment capacity of 8 bytes so that a
text is cut. It was printed from `MemoryTransactionFactory.indexByIndex` by a program in the package of the
provider. The document, of the entity `Person` with the id `ola` in the namespace `ns`, version
`2020-01-02T03:04:05.006Z`:

```json
{
  "name": "Ola Nordmann",
  "age": 42,
  "alive": true,
  "friends": ["/Person/kari"],
  "none": null
}
```

The deletion marker is at `2021-01-01T00:00:00Z`.

```
directory
  0000 = ("ns", "Primary", "Person")
  0001 = ("ns", "PathValueIndex", "Person", "$.age")
  0002 = ("ns", "PathValueIndex", "Person", "$.alive")
  0003 = ("ns", "PathValueIndex", "Person", "$.name")
  0004 = ("ns", "PathValueIndex", "Person", "$.none")
  0005 = ("ns", "PathValueIndex", "Person", "$.friends[]")

map Primary, 8 entries (the value of $.alive is the one byte 01)
  0000 ("ola", (-2021, -1, -1, 0, 0, 0, 0), "", b"\x01", 0) = ""
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.age", b"\x05", 0) = "42"
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.alive", b"\x06", 0) = ...
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.friends[0]", b"\x04", 0) = "/Person/"
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.friends[0]", b"\x04", 8) = "kari"
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.name", b"\x04", 0) = "Ola Nord"
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.name", b"\x04", 8) = "mann"
  0000 ("ola", (-2020, -1, -2, -3, -4, -5, -6), "$.none", b"\x00", 0) = ""

map PathValueIndex, 5 entries, each with an empty value
  0001 (b"\x92\xcf\xce\xb3...", "ola", (-2020, -1, -2, -3, -4, -5, -6), (()))
  0002 (b"\xbf\x8bE0...", "ola", (-2020, -1, -2, -3, -4, -5, -6), (()))
  0003 (b"\xd5\x0d\x9c9...", "ola", (-2020, -1, -2, -3, -4, -5, -6), (()))
  0004 (b"\xda9\xa3\xee...", "ola", (-2020, -1, -2, -3, -4, -5, -6), (()))
  0005 (b"\xaf\x92\xb1~...", "ola", (-2020, -1, -2, -3, -4, -5, -6), ((0)))
```

The key of `$.age` in bytes, in hexadecimal, with a space between the parts:

```
0000  02 6f6c61 00  05 12f81b 13fe 13fd 13fc 13fb 13fa 13f9 00  02 242e616765 00  01 05 00  14
```

`0000` is the prefix. `02 ... 00` is a text (`ola`, `$.age`). `05 ... 00` is the nested tuple of the version,
with `12f81b` for -2020 and `13fe` for -1. `01 05 00` is the byte of the type code. `14` is the offset 0.

## How a write goes

1. Core calls `createOrOverwrite` on the bridge with a document. The bridge flattens it into fragments and calls
   `MemoryRxPersistence.createOrOverwrite(tx, fragments)`.
2. For each fragment, the provider gets the subspace of the namespace and entity.
3. It clears the range `(id, version)` in `Primary`, once for each such range in one call (`clearedRanges`). So a
   version that is written again replaces the earlier one, and the fragments of one call do not clear each other.
4. It sets the key of the fragment in `Primary`.
5. If the offset is 0, it sets the index entry in `PathValueIndex`. This is where a key over 256 bytes fails.

Index entries of the replaced version are not cleared. The comment in `createOrOverwrite` gives the reason:
clearing them costs a read and a write for each fragment, and a wrong match in the index is always followed by a
look in `Primary`. See [Not finished](#not-finished) for where that is not enough.

`set`, `clear` and `clearRange` change the map at once. For each change, `MemoryTransaction` puts what was
there before into its undo log (`transactionLog`).

A write takes no thread of the pool: the directory answers with a future that is already complete, and `set` and
`clearRange` are plain calls on the thread of the caller. What a write waits for is the lock (see
[Transactions](#transactions)).

## How a read goes

Every read is one or more ranges of keys, each turned into a stream with `AsyncIterablePublisher`, and each entry
turned into a `Fragment` with `MemoryRxPersistence.toFragment`.

### Ranges and key selectors

`OrderedKeyValueTransaction.getRange` has two forms:

- With a `Range` of `java-tuple`: from a key, inclusive, to a key, exclusive. `Subspace.range(tuple)` gives the
  range of every key that is a longer tuple starting with the given one. `Subspace.range()` gives the whole
  subspace.
- With two `KeySelector`s. A selector names a key that need not be in the store, and resolves to one that is:
  `firstGreaterOrEqual(key)` and `lastLessOrEqual(key)` are the two with a factory method.
  `MemoryTransaction.keySelectorFunction` resolves a selector, and `getRange` then takes the entries from the
  first key to before the second. A selector that resolves to nothing means "before the first key" or "after the
  last key", depending on its offset.

Both forms take a limit; `ROW_LIMIT_UNLIMITED` (0) means every entry.

`getRange` copies the entries of the range into a new map and gives an `AsyncIterable` over the copy. So a range
does not change after it is taken, and it can be read after the transaction has ended. It also means that the
whole range is in memory twice while it is read: what is on demand is the delivery of the entries, not the
copying. The limit does not make the copy smaller: `MemoryAsyncIterator` counts what it has given, so a range
with the limit 1 still copies every entry between its two keys.

### One document at a time: `read`

1. `findAnyOneMatchingFragmentInPrimary` takes one entry: the first key at or after `(id, snapshot)` and before
   `(id + " ")`. Because versions are negated, that is an entry of the newest version that is not after the
   snapshot. An entry of another id or another subspace means there is no such version.
2. If the entry is a deletion marker, the stream is one fragment of type `DELETED`.
3. Otherwise the stream is the range `(id, version)`: every fragment of that version.

### The versions of a document: `readVersions`

The range starts at the first key at or after `(id, before - 1 ms)`. It ends after the version that is valid at
`after`, which is found as in `read`: at the first key at or after `(id, that version - 1 ms)`. When no
version is valid at `after`, it ends at the first key at or after `(id, after)`. A missing `after` is the year 1,
a missing `before` the year 9999. So the stream holds the version valid at `after` and every later version before
`before`, newest first.

### The documents of an entity: `readAll`

One range over the ids, from the first key at or after `(after + " ")` to the first key at or after the id made
by `lexicoPrev(before)`. Without bounds, `after` is `" "` and `before` is `"~"`. A filter then keeps, for each
id, the fragments of the first version that is not after the snapshot. Deletion markers pass; the bridge drops
them.

Of the range it is given, `readAll` uses `after` and `before` only. It does not use the limit (`first`, `last`):
the bridge takes the limit away before it calls (`Range.unlimited`) and counts documents itself. So a list of
two documents still copies and reads every version of every document of the entity between the bounds.

### The documents with a value at a path: `find`

1. The range in `PathValueIndex` of every key that starts with the SHA-1 of the value, in the subspace of the path
   without array indices. The entries are ordered by id, then by version with the newest first.
2. An entry is dropped when its version is after the snapshot, or when its id is the id of the entry that was
   kept last (an older version of the same document).
3. For each entry left, `findAnyOneMatchingFragmentInPrimary` finds the version of that document at the snapshot.
   If it is another version than the index entry says, the entry is dropped: the value was in an older version.
   If it is a deletion marker, the entry is dropped and a clean-up is started (see [Not finished](#not-finished)).
4. Otherwise the stream goes on with every fragment of that version from `Primary`.

`find` does not use the range it is given. The bridge applies it to the documents.

`find` can give a document that does not have the value: when the first piece of two values is the same, and
when a version was written again with another value. The caller must check. `RxJsonPersistenceBridge.findDocument`
does, but nothing in `linked-data-store-core` calls `findDocument`. Core follows a link backward with
`RxJsonPersistenceBridge.readSourceDocuments`, which also uses `find` and does not check.

The bridge sorts the fragments of `readAll`, `readVersions` and `find` before it makes documents of them
(`Flowable.sorted` in `RxJsonPersistenceBridge`), so those three reads hold all their fragments once more. That
is in the API module, and described in [the persistence interface](persistence-api.md).

## AsyncIterablePublisher and demand

`AsyncIterablePublisher` is what makes a range a stream. It is checked with the Reactive Streams TCK. How it
works:

- The publisher takes the iterator from the `AsyncIterable` in its constructor. A publisher is for one
  subscriber.
- `subscribe` makes a subscription and calls `onSubscribe`. The flag `publicationPending` starts as taken, and
  is released when `onSubscribe` has returned. So an entry that is requested inside `onSubscribe` is delivered
  after `onSubscribe` has returned, also when another thread delivers it. A publisher without an iterator
  signals `onError` after `onSubscribe`.
- `request(n)` adds `n` to the count `requested`, up to `Long.MAX_VALUE`, and tries to start a publication. A
  request for 0 or less ends the stream with `onError` (`requested amount must be > 0`).
- A publication (`asyncIterate`) is one entry. It asks `AsyncIterator.onHasNext()`, a `CompletableFuture` that
  `MemoryAsyncIterator` completes on a thread of the pool. When the future completes, the publisher takes the
  entry with `next()` and calls `onNext`, or calls `onComplete` when there is no next entry. That happens on the
  thread of the pool that completed the future, or, when the future is complete before the publisher has
  attached to it (`whenComplete`), on the thread that started the publication: the one that called `subscribe`
  or `request`, or the one that delivered the entry before. Both were observed: four entries of one range came
  on `main` and on three threads of the pool. A subscriber cannot rely on the thread.
- Only one publication runs at a time (`publicationPending`). After `onNext` returns, the next one starts if
  more is requested than is published.
- `onError` and `onComplete` cancel the subscription first, so nothing follows them. `cancel` drops the
  subscriber.
- A failure of `onHasNext()`, of `next()` or of the `onNext` of the subscriber ends the stream with `onError`.

What follows from this:

- Nothing is taken from the iterator without demand. The end of the range is also found only when an entry is
  asked for: an empty range completes after the first `request`, not before.
- Every entry costs one task on the pool of 8 threads.
- A range that is being read needs a free thread in the pool for each entry.

## Transactions

`MemoryTransactionFactory.createTransaction(readOnly)` makes a `MemoryTransaction`. Its constructor takes the
lock of the store and waits for it: the read lock when `readOnly`, the write lock otherwise. The lock is one
`ReentrantReadWriteLock` for the whole store, so read transactions run together and a write transaction runs
alone. There is no limit to the wait. A thread that is interrupted while it waits gets a `RuntimeException`
around the `InterruptedException`.

A thread that has a write transaction can make a read transaction as well. The other way does not work: a thread
that has a read transaction and makes a write transaction waits for its own read lock, and never gets on. Both
were observed by calling the classes.

| Call | What it does |
|---|---|
| `commit()` | Releases the lock. Nothing else: the changes are in the map already. The undo log is not emptied. |
| `cancel()` | Puts back what the undo log holds, last change first, empties the log, and releases the lock. |
| `close()` | With `cancelTxOnClose` false: the same as `commit()`. With `cancelTxOnClose` true: the same as `cancel()`. |

`cancelTxOnClose` is false in a running LDS (see [Configuration](#configuration)). So in LDS, **closing a transaction
commits it**, also when the work in it failed. This is the opposite of the rule "transactions commit only when asked and
roll back otherwise" in `.claude/CLAUDE.md`, which says of itself that the memory provider does not meet it yet.

After `commit` or `cancel`, a call that touches the map throws `IllegalStateException` (`Attempting to access
database from within a closed transaction.`). That holds for `cancel()` itself: after `commit()`, a `cancel()` of
a transaction that changed something throws it, and changes nothing.

`commit()` and `cancel()` give a `TransactionStatistics`. The provider counts nothing in it.

The lock belongs to the thread that made the transaction. `commit`, `cancel` and `close` must be called on that
thread. On another thread they throw `IllegalMonitorStateException`, and the lock is then never released. Core
makes and closes each transaction on one thread (`try (Transaction tx = ...)` around a blocking call), which is
why this does not show.

`runAsyncInIsolatedTransaction` runs a function in a transaction of its own on a thread of the pool.

## Deleting

| Call | What it does |
|---|---|
| `markDeleted` | Clears the range `(id, version)` in `Primary` and sets a deletion marker there. This is what a `DELETE` request of LDS does (`PersistenceDeleteSagaAdapter` in core). The index is not touched. |
| `delete` | Clears the range `(id, version)` in `Primary`: the version, or the marker, is gone. |
| `deleteAllVersions` | Reads every fragment of the id, clears the index entry of each one at offset 0, then clears the range `(id)` in `Primary`. |
| `deleteAllEntities` | Clears the whole subspace of the entity in `Primary`, and the subspace of each path it is given in `PathValueIndex`. The bridge gives it the paths it makes from the specification (`RxJsonPersistenceBridge.deleteAllEntities`). The index subspace of any other path is not cleared. |

Core calls only `markDeleted`. The other three are called by the shared suite. None of them uses the
`PersistenceDeletePolicy` it is given.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `persistence.provider` | `mem` in the server | `mem` selects this provider. Read by `PersistenceConfigurator` in core. |
| `persistence.fragment.capacity` | `8192` in the server | The largest number of bytes of one piece of a text. `MemoryInitializer` gives it to the bridge. It is the one key in `MemoryInitializer.configurationKeys()`, so LDS does not start without it: `PersistenceConfigurator` throws `Configuration missing for: [persistence.fragment.capacity]` (`PersistenceConfiguratorTest` in core). `MemoryInitializer.initialize` has a default of its own, 8192, for a map without the key; LDS never gives it such a map. |
| `persistence.mem.transaction.cancel-on-close` | `false` | Whether `close()` of a transaction cancels. `MemoryInitializer.initialize` reads it from the map it is given. |

`PersistenceConfigurator` gives `initialize` only the keys that `configurationKeys()` names. The key
`persistence.mem.transaction.cancel-on-close` is not among them, so it cannot be set in the configuration of
LDS: LDS was started with the key set to `true` in `conf/application.properties` and in the environment, and a
write was kept as before. Only code that calls `MemoryInitializer.initialize` itself can set it. The shared suite
does.

`initialize` does not use the default namespace and the managed domains it is given. Each call builds a new,
empty store with its own pool of threads. `RxJsonPersistence.close()` shuts the pool down
(`MemoryTransactionFactory.close`: it waits up to 10 seconds, then interrupts and waits 10 more).

LDS started with the defaults of the server logs:

```
... PersistenceConfigurator - Using persistence provider: mem
... PersistenceConfigurator - Persistence provider initialization attempt # 1
... PersistenceConfigurator - Persistence service-provider configured
```

## What differs from the other providers

| Subject | The memory provider |
|---|---|
| The engine | None. The store is two maps in the process. No container, no schema, no connection. |
| The interface it implements | `RxPersistence` (fragments), behind `RxJsonPersistenceBridge`, as the PostgreSQL provider does. The Neo4j provider implements `RxJsonPersistence` (documents) itself. |
| Links | As with PostgreSQL, a text of the form `/Entity/id` is followed also in a field that the model does not call a link, when that path is asked for. That is the bridge. Neo4j does not (`.claude/CLAUDE.md`). |
| Transactions | One lock for the whole store. `close()` commits. |
| Fragment capacity | `persistence.fragment.capacity` is read by this provider only, and only here is a long text kept in several pieces. The PostgreSQL provider gives the bridge `Integer.MAX_VALUE` (`PostgresDbInitializer`). |
| Tests | The shared suite runs on the provider itself. There is no engine to start, so the tests of this module need no Docker. |

## Rules to keep

They are from `.claude/CLAUDE.md` and from the code.

- **It streams.** A read is a `Flowable` that gives entries as they are asked for. Do not replace
  `AsyncIterablePublisher` with a call that gives a complete list, and do not add a blocking call to an
  interface. `AsyncIterablePublisher` must keep passing the Reactive Streams TCK.
- **One contract for every provider.** The shared suite (`PersistenceIntegrationTest`) must pass unchanged. A
  provider stores a document as it is given and gives it back the same, also a field the model does not have and
  a value of another kind than the model says.
- **The order of the keys is the logic.** Every read depends on keys ordered by id, then version with the newest
  first, then path, compared as unsigned bytes. A change to the key of a fragment, to `toTuple`, or to the
  comparator of the maps changes every read.
- **Names are never spliced into keys.** A namespace, an entity, a path and an id go into a key as elements of a
  tuple, which `java-tuple` packs with its own end marks. Keep it so. The two places that do make a new text
  from an id, `lexicoNext` and `lexicoPrev`, are the cause of a fault listed below.
- **Time.** Versions are to be stored and compared as UTC instants. The provider does not meet this yet; a
  revision must.
- **Transactions** are to commit only when asked and roll back otherwise. The provider does not meet this yet; a
  revision must.
- **JPMS and services.** The provider stays registered both in `module-info.java` and in `META-INF/services`. The
  name in `@ProviderName` and the answer of `persistenceProviderId()` must be the same.
- **A key that the provider needs is in `configurationKeys()`**, and then in
  `application-server-defaults.properties` of the server, or LDS does not start.
- **Lint and Javadoc.** The POM of the module sets doclint to `all,-missing`, because much of the module has no
  Javadoc. The override is removed when the module is revised, not widened.
- **Tests.** New tests are JUnit 6 with AssertJ. A class that extends the shared suite or the Reactive Streams TCK
  stays TestNG.

## Tests

| Class | Framework | What it checks |
|---|---|---|
| `MemoryPersistenceIntegrationTest` | TestNG | The shared suite, described in [the shared suite](persistence-tck.md). It starts the provider with a fragment capacity of 1024 and with `persistence.mem.transaction.cancel-on-close` set to `true`. The suite closes its transactions without committing, so with that setting every test leaves the store as it found it. |
| `AsyncIterablePublisherTest` (package `reactivestreams`) | TestNG | The Reactive Streams TCK (`PublisherVerification`) for `AsyncIterablePublisher`. The publisher under test is a range over at most 1000 entries of an index named `AnyIndex`, in a write transaction that is cancelled after each test. The publisher that must fail is one whose iterable gives no iterator. Timeouts are 100 ms, and 500 ms for the check that a publisher lets go of its subscriber. |
| `AsyncIterablePublisherSignalsTest` | JUnit | Four cases the TCK does not pin: nothing is delivered before `onSubscribe` has returned; nothing follows the error of an iterator that fails; nothing follows the error of a request for nothing; an iterator that cannot tell whether there is more fails the stream. |
| `KeySelectorTest` | JUnit | What each kind of selector resolves to in a store with the keys 1, 3 and 5; that a range runs from its first selector to before its second; the limit; equality of `KeySelector` and `KeyValue`. |
| `SubspaceTest` | JUnit | Packing and unpacking, `contains`, the two ranges, and that a subspace keeps its own copy of the prefix. |
| `MemoryTransactionTest` | TestNG | That `cancel` puts back what `set` and `clearRange` changed, and that the transaction cannot be used after it. |
| `MemoryDirectoryTest` | TestNG | That the directory gives 65536 different prefixes of 2 bytes. |

The tests of `linked-data-store-core` also run on this provider: `TestServerListener` starts the application
with `persistence.provider=mem` (`application_test.properties` in core).

The tests run in the build of the module; the commands are in [the build](build.md). They run on the class
path, JUnit and TestNG together.

No test of the module covers: `close()` after `commit()`; a write that fails part of the way; ids outside the
bounds of `readAll`; the clean-up in `find`; that `delete` leaves the index alone.

## Not finished

The module has not been revised for 3.0. Each paragraph below is something that was found in the code and,
where it says "observed", seen in a running LDS or by calling the classes of the built module.

Not finished: **a read that follows links backward can stop the whole store.** When `find` meets a document whose
version at the snapshot is a deletion marker with the same time as the index entry, it starts a clean-up with
`runAsyncInIsolatedTransaction(..., false)`. That task runs on the pool and waits for the write lock, which it
cannot get while the read that started it holds the read lock. Each such document takes one of the 8 threads.
When all 8 wait, the read gets no thread to deliver its entries, never ends, and never releases its lock. Every
later read and write then waits too. Observed: nine documents written with a link to the same target and deleted
with the same `timestamp` as their version, then a GraphQL query for the links to the target; the query, a
`GET` and a `PUT` after it had no answer in 10 seconds, and the 8 threads were parked in
`MemoryTransaction.<init>`. `/health/alive`, which makes a read transaction (`HealthCheckHandler` in core), had no
answer either; `/ping` and `/health/ready`, which do not touch the store, answered `200`. After that, LDS had not
stopped two minutes after a `SIGTERM`. With eight such documents there was no hang, and none with twelve
documents that were deleted at a later time than their version.

Not finished: **that clean-up clears nothing.** It calls `clear` on the index `PathValueIndex` with the key of the
entry in `Primary`. No index entry has that key.

Not finished: **a write that fails is kept in part.** The fragments set before the failure stay in the map, and
`close()` commits. Observed: a `PUT` of `{"name":"Ola","born":"2000-01-01T00:00:00Z"}` with an id of 240
characters is answered `500`, and a `GET` of that id then answers `200` with `{"born":"2000-01-01T00:00:00Z"}`.

Not finished: **an id can be too long.** The index key holds the id and may be 256 bytes. In what was tried, the
longest id that could be written was between 204 and 212 bytes. It depends on the time of the version and on the
arrays in the document: each array around a value puts one more number into the index key. The key in `Primary`
is set before the index key is checked, which is how the failed write above is kept in part.

Not finished: **some ids are left out of a list.** `readAll` reads from the id `"  "` (two spaces) to the id
`"}~"`. A document whose id sorts outside that, for example one that starts with `~` or with a letter outside
ASCII, is read by its id but is not in the list of its entity. Observed with the ids `~tilde` and `øl`, over
REST and over GraphQL.

Not finished: **ids next to a cursor are left out of a page.** With `before`, `readAll` stops at the id made by
`lexicoPrev`: the cursor with its last character one lower, and `~` after it. With `after`, it starts at the
cursor with a space after it. Ids between those and the cursor are not read. Observed with the ids `a`, `a~x`,
`aø`, `b` and `c`: the GraphQL query `Person(last: 10, before: "b")` gave `a` only, and by calling the classes,
the ten first after `a` did not hold an id that is `a`, a tab and `x`.

Not finished: **a link that was replaced is still followed backward.** Index entries of a version that is written
again are not cleared, and `RxJsonPersistenceBridge.readSourceDocuments` does not check the documents `find`
gives it. Observed: a `Person` written with a link to one `Company` and written again, with the same
`timestamp`, with a link to another, is still listed under the first company.

Not finished: **time is not an instant.** `toTuple` takes the year, month, day, hour, minute and second of the
`ZonedDateTime` as they are, without moving it to UTC, and `toTimestamp` reads them back as UTC. REST moves every
time to UTC before it reaches the provider (`DataController` in core). GraphQL does not
(`GraphQLUndertowContext`). Observed: a GraphQL query with `snapshot=2022-05-05T12:00:00+02:00`, which is 10:00
UTC, reads a version that is valid from 10:30 UTC.

Not finished: **a version has the precision of a millisecond.** What is below a millisecond is cut off. Two
versions within one millisecond are one version: the later write replaces the earlier. Observed over REST.

Not finished: **`close()` after `commit()` fails when `cancelTxOnClose` is true.** `commit` does not empty the
undo log, so `close` tries to cancel and throws `IllegalStateException`. Observed by calling the classes, with a
transaction that wrote; one that only read closes without it. The write stays.

Not finished: **`delete` does not clear the index.** `doDelete` builds a stream that would clear the index entries
of the version, and never subscribes to it. Only `Primary` is cleared. Observed by calling the classes.

Not finished: **a read-only transaction can write.** `readOnly` only chooses the lock. `set` and `clear` are not
refused.

Not finished: **the directory runs out without saying so.** After 65536 subspaces the counter of prefixes starts
again at `0000`, and two subspaces then share a prefix. A subspace is one namespace and entity, or one
namespace, entity and path. Observed by calling `MemoryDirectory`.

Not finished: **one publisher, one subscriber.** A second subscriber to an `AsyncIterablePublisher` shares the
iterator of the first and gets the entries the first did not take. And when the pool is shut down, `subscribe`
throws `RejectedExecutionException`; it does not signal `onError`. Both observed by calling the classes.

Not finished: **the fragment capacity is not checked.** A value that is not a number, or an empty value, fails
each attempt to start the provider; LDS tries again every second for
`persistence.initialization.max-wait-seconds` (60 in the server) and then ends with `NumberFormatException`.
With `0`, LDS starts, and a write of a document with a text never ends: the thread of the saga stays in
`FlattenedDocumentLeafNode.valueByOffset` of the API module with the write lock, so no read gets an answer after
it, and LDS had not stopped 30 seconds after a `SIGTERM`. With `-1`, LDS starts, and a write of a document with
a text is answered `500`. All observed.

Not finished: `createOrOverwrite` has the comment `TODO add batch-size`. `MemoryRxPersistence.hasPrevious` and
`hasNext` are not called by the bridge, which has its own. `MemoryRxPersistence.tickForward` is not called at
all. Tests of this module that are still TestNG without having to be: `MemoryTransactionTest` and
`MemoryDirectoryTest`.
