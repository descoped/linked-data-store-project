---
title: "The shared suite"
description: "The tests every persistence provider passes."
order: 3
---

# The shared suite for persistence providers

This document covers the module `linked-data-store-persistence-provider-tck`: the tests that every persistence
provider must pass, the [shared suite](../glossary.md#shared-suite) or technology compatibility kit (TCK) of the
persistence interface, written here as the contract a provider meets. It is for developers who change the suite,
change a provider, or add a provider.

## What the suite is for

LDS has one persistence contract for every provider. The interface is `RxJsonPersistence` in
`linked-data-store-persistence-provider-api`. The interface says which calls there are. The suite says what they
answer.

The suite is one abstract class, `PersistenceIntegrationTest`, with 32 tests. Each provider module has one class
that extends it and gives it a running provider. The same 32 tests then run against the memory provider, against
PostgreSQL and against Neo4j. A difference between providers that a test of the suite can see is a fault in a
provider.

The suite has no tests of its own to run: the module has no test sources, and its build runs no test.

## Layout

The module has three classes, all in the package `io.descoped.lds.core.persistence.test`, and all in
`src/main/java`. They are in `src/main` because they are what the module delivers: a provider depends on the
module with test scope and gets the classes from its jar.

| Class | What it is responsible for |
|---|---|
| `PersistenceIntegrationTest` | The 32 tests, the specification they use (`buildSpecification`), and the helpers that make documents (`createPerson`, `createAddress`, `toDocument`). |
| `SpecificationBuilder` | Static methods that build a `Specification` in a few lines: `stringNode`, `numericNode`, `booleanNode`, `objectNode`, `arrayNode`, `refNode` (a link), `arrayRefNode` (an array of links) and `createSpecificationAndRoot`. |
| `TestSpecificationElement` | The `SpecificationElement` those methods build: a name, a kind (`ROOT`, `MANAGED`, `EMBEDDED`, `REF`), the JSON types, the entity types a link may point at, the properties, and the items of an array. |

`src/main/resources/logback.xml` is in the jar too, so it is on the test class path of every provider module. It
logs to the console at level `INFO`. The PostgreSQL and Neo4j modules have no `logback.xml` of their own. The
memory module has one with the same content.

## What the suite needs from a provider

The suite needs three things. It makes one of them itself.

| What | Where it comes from |
|---|---|
| The namespace | The subclass gives it to the constructor: `super("lds-provider-memory-integration-test")`. The suite uses this one namespace in every call. The three subclasses also give it to the initializer as the default namespace. No initializer uses that argument. |
| The specification | The suite builds it in its constructor with `buildSpecification()`. A subclass does not give one, and none of the three subclasses overrides the method. An override runs before the constructor of the subclass has run. |
| The persistence | The field `protected RxJsonPersistence persistence`. The subclass sets it in a method with `@BeforeClass` of TestNG, and closes it in a method with `@AfterClass`. |

The specification of the suite has three [entity types](../glossary.md#entity-type):

- `Person`: the texts `firstname` and `lastname`, the numbers `born` and `bornWeightKg`, the boolean `isHuman`,
  and the object `history`. `history` has three link fields: `currentAddress`, `workAddress` and the array
  `previousAddresses`. Each may link to an `Address` or a `FunkyLongAddress`.
- `Address`: the texts `city`, `state`, `country`.
- `FunkyLongAddress`: the same three texts.

Five tests build a specification of their own with the entity type `People` (an array of texts, an array of
objects, or both), and one test writes a document of the entity type `Stranger`, which no specification has.

The suite reads no configuration. The subclass gives the configuration of the provider to its initializer.

## How a provider module extends the suite

A provider module does three things:

1. It depends on `linked-data-store-persistence-provider-tck` with test scope and `${project.version}`. TestNG,
   AssertJ, JSONAssert and Logback come with it, because the suite has them with compile scope. The three
   provider modules also name TestNG and AssertJ themselves, and the memory module names Logback. JSONAssert
   reaches a provider through the suite only.
2. It has one class in `src/test/java` that extends `PersistenceIntegrationTest`. The class has a public
   constructor without arguments that names the namespace, a `@BeforeClass` method that starts the provider through
   its `PersistenceInitializer` and sets `persistence`, and an `@AfterClass` method that closes it.
3. If the provider needs an engine, the module starts the real one in a container, and has a `LoopbackPorts` of its
   own (see [Rules to keep](#rules-to-keep)).

The three modules do it in three ways:

|  | Memory | PostgreSQL | Neo4j |
|---|---|---|---|
| Class | `MemoryPersistenceIntegrationTest` | `PostgresIntegrationTest` | `Neo4jPersistenceIntegrationTest` |
| Namespace | `lds-provider-memory-integration-test` | `lds-provider-postgres-integration-test` | `lds-provider-neo4j-integration-test` |
| Engine | None. The store is in the JVM of the test. | A container of `postgres:18-alpine`, started by the helper `Postgres18`. | A container of `neo4j:2026.10-community`, started by the helper `Neo4j2026`. |
| Initializer | `MemoryInitializer` | `PostgresDbInitializer` | `Neo4jInitializer` |
| Configuration | `persistence.fragment.capacity` = `1024`, `persistence.mem.transaction.cancel-on-close` = `true` | `Postgres18.configuration()`: the five `postgres.driver.` keys of the container (database, user and password are `lds`) | `Neo4j2026.configuration()`: `neo4j.driver.url`, `neo4j.driver.username` (`neo4j`), `neo4j.driver.password` (`lds-development`), `neo4j.cypher.show` = `false` |
| What the suite calls | `RxJsonPersistenceBridge` over `MemoryRxPersistence` | `RxJsonPersistenceBridge` over `PostgresPersistence` | `Neo4jPersistence`, which implements `RxJsonPersistence` itself |
| When a transaction of a test is closed | It is cancelled. What the test wrote is gone. | It is committed. What the test wrote stays. | It is committed. What the test wrote stays. |

Three things follow from the table:

- For memory and PostgreSQL the suite also tests the bridge of the API module, because the bridge makes the
  documents from their [fragments](../glossary.md#fragment). For Neo4j it does not.
- In the suite the bridge of the memory provider cuts a text into fragments of 1024 bytes (the default is 8192),
  so the long text of `thatBigValueWorks` is stored as many fragments. The bridge of the PostgreSQL provider
  does not cut a text: `PostgresDbInitializer` gives it `Integer.MAX_VALUE` as the size.
- A helper starts one container on first use, and every test class of its module uses that one. Testcontainers
  stops it when the JVM ends. So the suite shares its PostgreSQL or Neo4j with the other tests of the module,
  and is kept apart from them by its namespace.

All three subclasses give the initializer the entity types `Person`, `Address` and `FunkyLongAddress`. The Neo4j
provider creates its constraints and indexes for these. The memory and PostgreSQL providers do not use the set.
The suite also writes `People` and `Stranger`, which are not in the set.

The details of each provider are in [the memory provider](persistence-memory.md),
[the PostgreSQL provider](persistence-postgres.md) and [the Neo4j provider](persistence-neo4j.md). The
interface is in [the persistence API](persistence-api.md).

## How a test runs

A test goes through these steps:

1. TestNG makes one object of the subclass. The constructor of the suite keeps the namespace and builds the
   specification.
2. The `@BeforeClass` method of the subclass starts the provider and sets `persistence`. All 32 tests use it.
3. A test opens one transaction: `try (Transaction tx = persistence.createTransaction(false))`. Everything the
   test does is inside it.
4. Most tests first remove what they are about to write, with `deleteAllDocumentVersions` for a document or
   `deleteAllEntities` for an entity type, because another test may have left the document there.
5. The test writes documents with `createOrOverwrite`. A document is a `JsonDocument`: a `DocumentKey` (namespace,
   entity type, id, [valid time](../glossary.md#valid-time) of the version) and a Jackson tree.
6. The test reads through the interface and compares. Each call returns a type of RxJava 3 (`Completable`,
   `Maybe`, `Single`, or `Flowable`, a [reactive stream](../glossary.md#reactive-stream)), and the test waits for
   it with `blockingAwait`, `blockingGet` or `blockingIterable`.
7. The transaction is closed at the end of the `try`. What that does depends on the provider (see the table
   above).

The suite never calls `commit` or `cancel`. On PostgreSQL and Neo4j what a test wrote is committed because
`Transaction.close` commits. That also holds for a test that fails: the `try` closes the transaction, and what
the test wrote before it failed stays in the store.

The suite compares in four ways. Know which one a test uses before you change what a provider gives back:

- `JSONAssert.assertEquals(..., true)`: the same fields and values, and arrays in the same order. The order of
  the fields of an object is not compared.
- `JsonNode.equals` (in `testReadDocuments` and `testReadDocumentVersions`): Jackson compares the kind of a
  number too, so the whole number 1998 is not equal to the same number as a `long` or a `double`. The order of
  the fields of an object is not compared.
- The JSON text, `jackson().toString()` (in `thatSimpleArrayValuesAreIntact` and
  `thatComplexArrayValuesAreIntact`): here the order of the fields of an object counts. The objects of the test
  have the fields `first` and `last`, written in that order.
- `assertEquals` of TestNG, which takes the actual value first and the expected value second.

The memory provider does not give the fields of an object back in the order they were written. This document
was written to it:

```json
{
  "zz": "1",
  "city": "C",
  "aa": "2",
  "mm": {"y": 1, "x": 2}
}
```

It was read back as:

```json
{
  "aa": "2",
  "city": "C",
  "zz": "1",
  "mm": {"x": 2, "y": 1}
}
```

## The contract

Each group below is what a provider must do, with the tests that check it. Times are the valid times of
versions.

### A document is read as it was written

- A document that is written is read back equal, at the valid time of its version: `thatBasicCreateThenReadWorks`.
  The document that is read is another object than the one that was written.
- Texts, whole numbers, numbers with a fraction and booleans are kept: `createPerson` has one of each.
- An array of texts and an array of objects are read back in the order they were written:
  `thatSimpleArrayValuesAreIntact`, `thatComplexArrayValuesAreIntact`.
- A text of 86 015 characters is stored, and is whole in the document that `findDocument` gives:
  `thatBigValueWorks`.
- Several documents of several entity types can be written in one call, as a `Flowable`:
  `thatBatchCreationWorks`.
- A document with links is read back with the links as the texts they were written as: `thatRefWorks`.

### Versions

- A write at the valid time of a version that is there replaces that version. There is then one version at that
  time, not two: `thatCreateWithSameVersionDoesOverwriteInsteadOfCreatingDuplicateVersions`,
  `thatBasicTimeBasedVersioningWorks`. These two tests count the versions. They do not read the version back to
  see that it holds what was written last.
- Versions may be written in any order of time: `thatBasicTimeBasedVersioningWorks`.
- A version is valid from its own valid time until the valid time of the next version. Before the first version
  there is no document. This also holds when several versions of one document are written in one call, not in
  order of time: `thatVersionsOfADocumentWrittenTogetherAreEachValidUntilTheNext`.
- `readDocument` as of a time (an [as-of read](../glossary.md#as-of-read)) gives the version that is valid at that
  time. A read at the exact valid time of a version gives that version: `thatBasicCreateThenReadWorks`,
  `thatAVersionThatIsDeletedLeavesTheVersionBeforeItValid`.
- `readDocumentVersions` with an unbounded range gives every version of the document, in order of time. The
  order is checked by the tests that use the helper `versionsOf`, for example
  `thatVersionsOfADocumentWrittenTogetherAreEachValidUntilTheNext`. `thatReadAllVersionsWorks` counts the
  versions only, and `testReadDocumentVersions` takes them in any order.
- The key of a version that is read has the time that was written, in the zone `Etc/UTC`:
  `thatBasicTimeBasedVersioningWorks` compares the keys.

### Deleting

There are four ways to take something away, and they are different things:

| Call | What it must do |
|---|---|
| `deleteDocument` (one version) | The version is gone. The version before it is then valid until the version after it, as if the deleted version was never written. If the last version is deleted, the one before it is valid from then on. If the first version is deleted, there is no document before the next one. `thatAVersionThatIsDeletedLeavesTheVersionBeforeItValid`. |
| `deleteAllDocumentVersions` | No version of the document is left: `thatDeleteAllVersionsWorks`. The call completes when the document is not there; many tests start with such a call. |
| `deleteAllEntities` | No document of the entity type is left. Documents of other entity types stay, also those that link to the deleted ones: `thatDeleteAllWithIncomingRefWorks`. |
| `markDocumentDeleted` | See the next section. |

### A document marked as deleted

`markDocumentDeleted` writes a mark, a [tombstone](../glossary.md#tombstone), at a time. The tombstone is a version
of the document.

- From the valid time of the tombstone until the next version there is no document: `readDocument` gives nothing,
  the document is not in the list of `readDocuments`, and `findDocument` does not find it. Before the tombstone
  and from the next version on, the document is read as before:
  `thatADocumentMarkedAsDeletedIsNotReadFromTheTimeOfTheMark`.
- `readDocumentVersions` gives the tombstone as a version with an empty document (`{}`), at its place in time. So
  the number of versions goes up by one: `thatDeleteMarkerWorks`.
- `deleteDocument` at the valid time of the tombstone removes it. The version before it is the document again:
  `thatDeleteMarkerWorks`, `thatADocumentMarkedAsDeletedIsNotReadFromTheTimeOfTheMark`.
- A tombstone at the valid time of a version takes the place of that version. A version written at the valid time
  of a tombstone takes the place of the tombstone. The versions around them stay valid as before:
  `thatADeleteMarkerAndAVersionAtTheSameTimeReplaceEachOther`.
- A document may be marked as deleted before it is written. Versions written later, before and after the
  tombstone, are valid as the rules above say: `thatADocumentMayBeMarkedAsDeletedBeforeItIsWritten`.

### Lists and ranges

`readDocuments` gives the documents of an entity type as they are at a time, one for each id. A `Range` limits it
by id. `readDocumentVersions` takes a `Range` of valid times in the same way. `testReadDocuments` checks this
with twelve documents, `person00` to `person11`, and `testReadDocumentVersions` with twelve versions of one
document:

| Range | Answer for the ids `person00` to `person11` |
|---|---|
| `unbounded()` | all twelve |
| `first(3)` | `person00`, `person01`, `person02` |
| `firstAfter(3, "person03")` | `person04`, `person05`, `person06` |
| `firstBetween(2, "person06", "person10")` | `person07`, `person08` |
| `firstBetween(4, "person06", "person10")` | `person07`, `person08`, `person09` |
| `last(3)` | `person11`, `person10`, `person09` |
| `lastBefore(3, "person10")` | `person09`, `person08`, `person07` |
| `lastBetween(2, "person06", "person10")` | `person09`, `person08` |
| `lastBetween(4, "person06", "person10")` | `person09`, `person08`, `person07` |

So both bounds of a range are exclusive, a `first` range is read in ascending order, and a `last` range is read
from the end in descending order. The suite does not check the order of the ids of an unbounded list.

- `between(after, before)` on versions gives only the versions whose valid time is inside the bounds. The version
  that is valid at the lower bound, but written before it, is not given: `thatReadVersionsInRangeWorks`.
- `readDocuments` as of a time gives each document in the version that is valid at that time: `thatFindAllWorks`.
- `hasPrevious` and `hasNext` say whether the entity type has a document with an id before or after the given id:
  `testHasNextAndHasPrevious`.

### Links

A [link](../glossary.md#link) is the text `/Entity/id` in a field that the specification calls a link.

- `readTargetDocuments` gives the documents that a document links to, at a path and of one entity type. A link
  field that may link to two entity types is asked once for each. A path through an array is written with `[]`:
  `$.history.previousAddresses[]`: `thatReadLinkedDocumentsWork`.
- `readSourceDocuments` goes the other way: the documents of one entity type that link to a document at a path.
  `thatReadLinkedDocumentsWork` compares them with the links that were written.
- A link is resolved when it is read: it is followed to its target as the target is at the time asked for. A
  target that is marked as deleted at that time is not read. A source that is marked as deleted at that time is
  not a source: `thatALinkToOrFromADocumentMarkedAsDeletedReadsNothing`. A source is still given when its target
  is marked as deleted at that time.
- LDS does not check that the target of a link exists, so a link may be a dangling reference. A document whose
  targets are deleted is still read as it was written: `thatDeleteAllWithIncomingRefWorks`.
- A link field is read as it was written, whatever it holds: null, a text that is no link, a link to an entity type
  the field does not name, a number, a text where the specification says array, a link without its first slash,
  a link without an id, and an array that holds links, a null and a text that is no link:
  `thatALinkFieldIsReadAsItWasWritten`. Such a value is not a link. The test checks for two of them that nothing
  is read when the link is followed: the link to an entity type the field does not name, and the link without its
  first slash.
- A link is followed in both directions in a document that holds more than the specification says. A text that
  is no link, next to a link in the same array, is passed over:
  `thatALinkIsFollowedInADocumentThatHoldsMoreThanTheSpecificationSays`.

### Finding a document by a value

`findDocument` gives the documents of an entity type that have a given text at a given path, as of a time.

- A document that is found is given once, in the version that is valid at the time:
  `thatFindAllWithPathAndValueWorks`, `thatFindSimpleWithPathAndValueWorks`.
- The whole text must be equal. A text of 86 015 characters is found, and the same text with one more character
  finds nothing: `thatBigValueWorks`.
- A document is found by a field that the specification does not have: at the top, inside objects
  (`$.in.deep.er`), in an array of texts (`$.list[]`), in a field that only some objects of an array have
  (`$.name[].middle`), and by the empty text: `thatADocumentIsFoundByAFieldTheSpecificationDoesNotHave`. A text
  that another field of the same array has does not find the document.

### More than the specification says

A provider stores a document as it is given. Whether a document may be like that is for the validation before a
write to say, not for the store. The specification tells a provider one thing: which fields are links.

- A field that the specification does not have is kept, of any kind and at any depth: a text, a number, a
  boolean, null, an empty text, an object, an array, an empty object, an empty array, arrays inside arrays, and
  field names such as the empty name, `7`, `007` and `my field`: `thatAFieldTheSpecificationDoesNotHaveIsKept`.
- A value of another kind than the specification says is kept: null, a number, an object or an array where it
  says text; a text where it says number, boolean, object or array; null where it says object or array; null
  inside an array: `thatAValueOfAnotherKindThanTheSpecificationSaysIsKept`.
- A document of an entity type that the specification does not have is stored, read and found like any other:
  `thatADocumentOfAnEntityTheSpecificationDoesNotHaveIsKept`. The comment of the test says that such a document
  has no links. The test does not check that: the document holds the text `/Address/a`, and the test does not
  follow it.

## What the suite does not cover

The suite uses one namespace, one transaction for each test, times in UTC, and small amounts of data. What is
outside that is tested by each provider in its own module:

| Not in the suite | Memory | PostgreSQL | Neo4j |
|---|---|---|---|
| More than one namespace | No test. | `PostgresProviderTest`: `aDocumentIsSeenInItsOwnNamespaceOnly` | `Neo4jProviderTest`: `aDocumentIsSeenInItsOwnNamespaceOnly`, `aLinkPointsAtTheDocumentOfItsOwnNamespace`, `aNewVersionInOneNamespaceLeavesTheVersionsOfTheOthers`, and the four tests whose names end in `LeavesTheOtherNamespaces` |
| Commit and cancel of a transaction | `MemoryTransactionTest` | `PostgresProviderTest`: `aCancelledTransactionWritesNothing`, `endingATransactionAgainDoesNothing`, `aCommitThatFailsFailsItsFuture` | `Neo4jProviderTest`: `aCancelledTransactionWritesNothing`, `endingATransactionAgainDoesNothing`; `Neo4jTransactionTest` |
| That the store is read only when a reader asks for more (backpressure) | `AsyncIterablePublisherTest` (the Reactive Streams TCK), `AsyncIterablePublisherSignalsTest` | `ResultSetPublisherIntegrationTest` (the Reactive Streams TCK), `ResultSetPublisherSignalsTest` | `Neo4jProviderTest`: `aDocumentIsTakenFromTheStoreWhenItIsAskedFor` |
| A version written with a time in another zone | No test. | `PostgresProviderTest`: `aVersionIsTheInstantWhateverZoneItWasWrittenWith` | `Neo4jProviderTest`: `aVersionIsTheInstantWhateverZoneItWasWrittenWith` |
| A range on `findDocument` or on the documents a link leads to; a document that is linked to twice | No test. | `PostgresProviderTest`: `findReturnsEveryMatchAndHonoursTheRange` | `Neo4jProviderTest`: `findHonoursTheRange`, `theLimitOfLinkedDocumentsCountsThoseThatAreWritten`, `aDocumentThatIsLinkedTwiceIsReadOnce` |
| Large documents and long lists | No test. | `PostgresProviderTest`: `aDocumentOfAnySizeIsRead`, `aListOfAnySizeIsRead`, `aLongTextIsStoredAndFound` | `Neo4jProviderTest`: `aListOfAnySizeIsRead` |
| The start of the provider | No test. | `PostgresProviderTest`: `instancesThatStartTogetherOnAnEmptyDatabaseAllStart` | `Neo4jProviderTest`: `instancesThatStartTogetherAllStart`, `aPlainIndexDoesNotStandInForTheConstraint` |
| What is particular to the store of the provider | `KeySelectorTest`, `SubspaceTest`, `MemoryDirectoryTest` | `PostgresProviderTest`: `theLimitOfAListCountsDocumentsAndABackwardRangeIsReadFromTheEnd`, `theVersionsOfADocumentAreReadInOrderOfTime` | `Neo4jRoundTripTest`, `Neo4jDocumentWriterTest` |

The suite also leaves these out, and they are not in the table because the suite says nothing for or against
them:

- **The delete policy.** The suite gives a `PersistenceDeletePolicy` to every call that takes one, but no test
  checks what a policy does. No provider decides anything by the argument.
- **Errors.** No test checks how a call fails.
- **`deleteAllEntities` for an entity type that the specification does not have.** The suite calls it for
  `Person`, `Address` and `FunkyLongAddress` only. Run against the memory provider with the entity type
  `Stranger`, the call fails with a `NullPointerException`. It comes from
  `RxJsonPersistenceBridge.deleteAllEntities`, which looks the entity type up in the specification.
- **A text that looks like a link in a field that is not a link.** The memory and PostgreSQL providers follow it
  when asked for that path, the Neo4j provider does not, and LDS does not ask for either. So the suite must not.
  Run against the memory provider, the text is followed in both directions, also in a document of an entity type
  that the specification does not have.
- **A time finer than a millisecond.** Every version in the suite is at a whole millisecond.
- **`findDocument` after a version is deleted with `deleteDocument`.**

The suite checks four calls that the rest of LDS does not make: `deleteDocument`, `deleteAllDocumentVersions`,
`deleteAllEntities` and `findDocument`. Core deletes with `markDocumentDeleted` (`PersistenceDeleteSagaAdapter`).

## TestNG and the class path

The suite is written with TestNG: its tests have `org.testng.annotations.Test`. A class that extends the suite is
therefore a TestNG class, and its set-up and tear-down use `@BeforeClass` and `@AfterClass` of TestNG. New tests
in LDS are JUnit 6 with AssertJ, but a class that extends the suite stays TestNG, because the suite is. Both
kinds run in one build, on the JUnit Platform: Surefire runs TestNG through the TestNG engine for the platform
(see [Two frameworks, one run](build.md#two-frameworks-one-run)).

The module has no `module-info.java`, so it is on the class path. The provider modules are named modules, but
Surefire runs every test on the class path (`useModulePath` is `false` in the root POM), because the tests are in
the same packages as the code they test. `PostgresProviderTest`, for one, is in the package of the PostgreSQL
provider and uses `PostgresPersistence`, which is visible in that package only.

The root POM sets `detectOfflineLinks` of Javadoc to `false` for every module. One of its two reasons is this
module: javadoc warns when a module on the class path links to a named one.

## Rules to keep

- **One contract, every provider.** A test that is added to the suite must pass on the memory provider, on
  PostgreSQL and on Neo4j. A test that only one provider can pass belongs in the module of that provider.
- **The real engine.** A provider runs the suite against its real engine in a container, not against a
  substitute.
- **A test must pass with and without what other tests wrote.** On PostgreSQL and Neo4j the documents of a test
  stay in the store for the next test. On memory they are gone when the transaction closes. So a test first
  removes what it will write, and uses ids of its own.
- **Two tests read a whole entity type that they have not emptied, and expect an exact answer.** `thatFindAllWorks`
  expects two `Person` documents at 2011-12-04, and `thatFindAllWithPathAndValueWorks` expects two with the
  `lastname` `Smith` at 2018-09-06. A new test must not leave a `Person` behind that is valid at those times.
  Write it at 2020-01-01, as `thatAFieldTheSpecificationDoesNotHaveIsKept` does, or remove it at the end, as
  `thatALinkToOrFromADocumentMarkedAsDeletedReadsNothing` does.
- **Six tests empty an entity type before they write.** They call `deleteAllEntities` for `Person`, and four of them
  also for `Address` and `FunkyLongAddress`. A new test must not need a document that another test wrote.
- **The [time machine](../glossary.md#time-machine) asks for no order.** Versions and tombstones may be written in
  any order of time, and a link may point at a document that does not exist. Do not add a test that needs an
  order or an existing target.
- **The store does not judge a document.** Do not add a test that expects a provider to refuse or change a
  document because of the specification.
- **The suite stays TestNG**, and so does every class that extends it.
- **The dependencies of the module have compile scope.** That is how JSONAssert reaches the tests of a provider,
  and Logback those of PostgreSQL and Neo4j. The classes stay in `src/main/java`, so that they are in the jar.
- **The lint override is removed, not widened.** See [How the module is built](#how-the-module-is-built).
- **Containers are published on 127.0.0.1 only.** A module that starts a container has its own `LoopbackPorts`,
  named in `META-INF/services/org.testcontainers.core.CreateContainerCmdModifier` among its test resources. The
  helper that starts the container calls `LoopbackPorts.check`.
- **No secrets.** The passwords in the helpers (`lds`, `lds-development`) are development defaults.
- **A change that switches tests on is run about ten times before it is pushed:**
  `for n in $(seq 1 10); do ./mvnw -B -q verify || break; done`.

## How the module is built

The `pom.xml` of the module differs from the rule of the root POM in three places:

| Setting | Value and reason |
|---|---|
| `compilerArgs` | `-Xlint:all,-this-escape` and `-Werror`. The constructor of the suite calls `buildSpecification()`, which a subclass can override. Every other warning fails the build. |
| `doclint` | `all,-missing`. Javadoc that is there is checked; missing Javadoc is not an error. |
| `sonar.sources`, `sonar.tests` | `pom.xml` and `src/main/java`. SonarCloud analyses the classes as tests. |

The module is in the BOM (`linked-data-store-bom`).

## Running the suite

The suite runs with the tests of a provider module. The command for one module and what it needs is
`./mvnw verify -pl <module> -am`. For the memory provider, which needs no Docker, it is:

```sh
./mvnw verify -pl linked-data-store-persistence-provider-memory -am
```

For PostgreSQL and Neo4j, give `linked-data-store-persistence-provider-postgres` or
`linked-data-store-persistence-provider-neo4j` as the module. Those two need Docker. The whole build
(`./mvnw verify javadoc:javadoc`) runs the suite for all three.

Surefire reports the suite under the name of the subclass, for example `MemoryPersistenceIntegrationTest`, with
32 tests.

## Not finished

Not finished: the module is not revised for 3.0. It has the lint and doclint overrides above and no
`module-info.java`.

Not finished: `thatReadVersionsInRangeWorks` has a comment that asks whether the expected number of versions is
one or two. It is not settled whether a range of versions should give the version that is valid at its lower
bound. The suite expects that it does not. Below the documents it is the other way:
`PostgresProviderTest.theVersionsOfADocumentAreReadInOrderOfTime` expects that version from
`RxPersistence.readVersions`, and `RxJsonPersistenceBridge` then leaves it out.

Not finished: the tests are not independent of each other. Several use the same ids (`john`, `jane`, `newyork`,
`People/1`), and two expect that no other `Person` is valid at their time (see [Rules to keep](#rules-to-keep)).
`testReadDocumentVersions` writes twelve versions of `Person/person00` and expects exactly those, but does not
remove the document first. It passes because the two other tests that write `person00`, `testReadDocuments` and
`testHasNextAndHasPrevious`, remove it at their end.

Not finished: the suite leaves the commit to `Transaction.close`. A rule of the project is that a transaction
commits only when asked (see [the invariants](architecture.md#the-invariants)), and `Transaction.close` does not
meet it yet.

Not finished: the delete policy has no effect in any provider, and the suite does not say what it should do.
`thatDeleteAllWithIncomingRefWorks` is named after links that come in. But the document it deletes with
`FAIL_IF_INCOMING_LINKS` has none, and `deleteAllEntities`, which deletes the documents that are linked to,
takes no policy.

Not finished: the Neo4j module has its own copies of `SpecificationBuilder` and `TestSpecificationElement` among
its tests. They differ from the classes here in the package only. A change to one is not seen by the other.

Not finished: `src/test/resources/logback.xml` is a copy of the file in `src/main/resources`. The module has no
tests that would use it.
