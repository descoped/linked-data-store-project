---
title: "Search"
description: "The search interface and the Solr provider."
order: 7
---

# Search: the interface and the Solr provider

This document describes the search interface of Linked Data Store (LDS) and the provider that implements it on
Apache Solr: what the interface promises, how a provider is found, what the Solr provider puts in the index and
how it asks for it. It is for developers who change this code or write another search provider.

## What this part is for

Search finds documents by text. LDS keeps an index next to the
[persistence provider](../glossary.md#persistence-provider). A write puts the document in the index, a delete
takes it out, and a search asks the index which documents match. The index answers with
[document keys](../glossary.md#document-key), never with the documents. Core reads the documents from the
persistence provider.

Search is optional. It is off unless `graphql.search.enabled` is `true`.

A search is asked for through the GraphQL field `Search` and in no other way. The REST handlers do not read the index.
The field is there only when LDS has a GraphQL schema as its model (see [How a provider is
found](#how-a-provider-is-found)).

The Solr provider indexes the texts of the fields whose path contains `languageText`, and nothing else. What
search covers is under review.

## Layout

| Module | JPMS module | Package | Holds |
|---|---|---|---|
| `linked-data-store-search-provider-api` | `io.descoped.lds.search.api` | `io.descoped.lds.api.search` | The interface: `SearchIndexProvider`, `SearchIndex`, and what a search gives back, `SearchResponse` and `SearchResult`. |
| `linked-data-store-search-provider-solr` | `io.descoped.lds.search.provider.solr` | `io.descoped.lds.core.search.solr` | The provider `solr`: `SolrProvider` makes the index, `SolrSearchIndex` is the index. |

The interface module depends on `linked-data-store-persistence-provider-api`, because a document is a
`JsonDocument` and a result is a `DocumentKey`, and on RxJava 3. Its POM and its `module-info.java` also name
Jackson 3 (`tools.jackson.core`, `tools.jackson.databind`), which no class of the module uses. The Solr module
depends on the interface module, on the persistence interface (it flattens a document with
`JsonToFlattenedDocument`), on `solr-solrj` and on SLF4J.

Core depends on the interface module only. The server module (`linked-data-store-server`) brings the Solr
provider. The classes of core that use the interface are these; they belong to core and are named here to show
where the interface is called. The sagas are described in [the write path](core-write-path.md), the
GraphQL schema in [GraphQL](core-graphql.md), and the start and the stop of LDS in
[the application](core-application.md).

| Class in `linked-data-store-core` | What it does with search |
|---|---|
| `SearchIndexConfigurator` | Finds the provider at start and makes the index. |
| `SagaRepository` | Adds the index step to the two sagas when there is an index. |
| `UpdateIndexSagaAdapter`, `DeleteIndexSagaAdapter` | The index steps: they call `createOrOverwrite` and `delete`. |
| `GraphQLSchemaBuilder`, `AddSearchTypesVisitor`, `RegistrySetupVisitor` | Add the field `Search` to the GraphQL schema when there is an index, and give it its fetcher. |
| `QueryConnectionFetcher` | The fetcher of `Search`: it calls `search` and reads the documents that are found. |
| `TestSearchIndex`, `SimpleSearchIndex` | A provider `testSearchIndex` that keeps documents in a map in memory, for the tests of core. |

`SimpleSearchIndex` is not a small Solr. It keeps one entry for each document id, whatever the namespace, the
entity type and the version are. A search finds a document when any text in it contains the query, in upper or lower
case; it does not look at `languageText`, and it does not use the filter, `from` or `size`. A test of core that
searches therefore says nothing about what the Solr provider indexes or finds.

## The interface

### SearchIndexProvider

A [search index provider](../glossary.md#search-index-provider) is a class that implements `SearchIndexProvider`
and has a constructor without parameters.

| Method | What it promises |
|---|---|
| `String getProviderId()` | The name the provider is chosen by. It is compared with the value of `search.index.provider`. The Solr provider answers `solr`. |
| `Set<String> configurationKeys()` | The configuration keys the provider reads. The Solr provider answers `search.index.url`. Nothing calls this method for a search provider. |
| `SearchIndex getSearchIndex(Map<String, String> configuration)` | Makes the index. Core calls it once, at start, with the whole configuration of LDS as a map (`DynamicConfiguration.asMap`), not only the keys of `configurationKeys()`. |

### SearchIndex

Every method returns a type of RxJava 3 (`Completable`, `Single`).

| Method | What it promises |
|---|---|
| `Completable createOrOverwrite(JsonDocument document)` | Puts one version of a document in the index, or replaces it when it is there. The key of the document (`DocumentKey`: namespace, entity type, id, the valid time of the version) says which. |
| `Completable createOrOverwrite(Collection<JsonDocument> documents)` | The same for several documents. |
| `Completable delete(JsonDocument document)` | Takes a document out of the index. Only the key of the document is of use: core gives a document with no content here. |
| `Completable deleteAll()` | Empties the index. |
| `Single<SearchResponse> search(String query, Set<String> typeFilter, long from, long size)` | Searches. `query` is passed to the index as it is, in the query language of the index. `typeFilter` narrows the result to the entity types it names. `from` is the number of results to skip, and `size` is the number of results to return. |

A `SearchResponse` holds the total number of hits (`getTotalHits`), one page of results (`getResults`), and the
`from` and `size` that were asked for. A `SearchResult` holds one `DocumentKey`.

The interface has no method that closes an index.

## How a provider is found

`SearchIndexConfigurator.configureSearchIndex` in core is called once when the application is made
(`UndertowApplication.initializeUndertowApplication`), after the persistence provider and the saga log are
made:

1. It reads `graphql.search.enabled` (default `false`). When it is `false`, the answer is `null`, and the log
   says `GraphQL Search is disabled. Skipping search index initialisation`.
2. It reads `search.index.provider` and loads every `SearchIndexProvider` with `java.util.ServiceLoader`.
3. The first provider whose `getProviderId()` equals the value makes the index, and the log says
   `Search index provider configured: solr`.
4. When no provider has that name, it throws
   `RuntimeException: No search index provider found for providerId: <the value>` and LDS does not start (the
   process ends with exit code 1). Nothing closes the persistence provider that was made before.

A provider is registered in two places: `provides SearchIndexProvider with ...` in its `module-info.java`, and a
file `META-INF/services/io.descoped.lds.api.search.SearchIndexProvider` with the name of the class. LDS runs on
the class path, where the file is the one that is read. Core declares `uses SearchIndexProvider` in its
`module-info.java`.

When there is no index, `SagaRepository` builds both sagas without an index step, and `GraphQLSchemaBuilder`
adds no `Search` field: a GraphQL request for it is answered
`Field 'Search' in type 'Query' is undefined`.

When there is an index, the sagas always get their index step. The `Search` field needs more: the GraphQL
schema is built only when `graphql.enabled` is `true` and `graphql.schema` names a file (the constructor of
`UndertowApplication`). With a model of JSON Schema files and no GraphQL schema, every write goes to the index
and nothing can search it: LDS has no `/graphql` then.

`AddSearchTypesVisitor` makes the enum `TypeFilters` and the union `SearchResult` from every type of the model
that has `@domain`, and adds `Search(query: String!, filter: [TypeFilters])` to the query type.
`AddConnectionVisitor` then gives it the paging arguments (`first`, `after`, `last`, `before`) and the type
`SearchResultConnection`. `RegistrySetupVisitor` finds the field by the applied directive `search`
(`AddSearchTypesVisitor.SEARCH_NAME`) and gives it a `QueryConnectionFetcher`.

`TestSearchIndex` is in the main code of core and in its `module-info.java`, but its `META-INF/services` file
is among the test resources of core. A running LDS does not find it: with
`search.index.provider=testSearchIndex`, LDS stops at start with
`No search index provider found for providerId: testSearchIndex`.

## How core calls the index

This is core's code. It is told here in short, because it decides what a provider is given.

- **A write.** The [saga](../glossary.md#saga) `Create or update managed resource` has the steps `txlog`, `persistence`
  and, when there is an index, `search-index-update`. The last two both follow `txlog`. `UpdateIndexSagaAdapter`
  (adapter name `Persistence-Index-Create-or-Overwrite`) makes a `JsonDocument` from the input of the saga (namespace,
  entity type, id, version, and the document as it was written) and calls `createOrOverwrite(document).blockingAwait()`.

- **A delete.** The saga `Delete managed resource` has the step `search-index-delete` in the same place.
  `DeleteIndexSagaAdapter` (adapter name `Persistence-Index-Delete`) calls `delete(document).blockingAwait()`.
  The document it gives has a key and no content.

- **The version.** In both sagas the version of the key is the [valid time](../glossary.md#valid-time) of the REST
  request: the time the request arrives, or the time in its `timestamp` parameter, always in the zone `Etc/UTC`
  (`DataController`). So the key of a delete has the time of the delete as its version, not the time of a version that
  was written.

- **A search.** `QueryConnectionFetcher` turns the paging arguments of the GraphQL field `Search` into `from` and `size`
  (`IndexBasedRange.fromRange`; 0 and 10 when none is given), calls `search(query, filter, from, size).blockingGet()`,
  and reads every document that is named from the persistence provider (`readDocument`). It reads the document with an
  [as-of read](../glossary.md#as-of-read) at the snapshot time of the GraphQL request: the time of the request, or the
  time in its `snapshot` parameter. It uses the namespace, the entity type and the id of a result, and not its version.
  The filter is the list of the argument `filter` as a set, or `null` when the argument is not given.

  A result whose document is not found at that time is left out, and `Cound not find document for key ...` is
  written to the log at ERROR. The cursor of an edge is `from` plus its place among the edges that are returned,
  so it is not the place of the result in the index when a result was left out. `hasNextPage` is
  `from + size < getTotalHits()`.

  `from` is not checked. `last: 20` without `before` gives `from` -10 and `size` 20, and the Solr provider sends
  `start=-10`.

## The Solr provider

### The client

`SolrSearchIndex` has two clients from `solr-solrj` (version `solr.version` in the root POM, 10.1.0):

- `HttpJdkSolrClient`, the client that uses the HTTP client of the JDK, made from the address in
  `search.index.url`. It sends the queries.
- `ConcurrentUpdateJdkSolrClient`, made for the same address on top of the first client. It sends the updates.
  It keeps additions in a queue, and threads of its own send them over the connection of the first client.

Both are made with the defaults of their builders. Nothing closes them.

The address names one core, for example `http://localhost:8983/solr/lds-index`. Every namespace and every
entity type is in that one core. The provider sends its requests to `/update` (`POST`) and `/select` (`GET`) under
the address, and asks for the answer in the binary format of Solr (`wt=javabin`). Before its first update the
client sends one `HEAD` request to `/update`.

The provider does not make the core and does not define its fields. The tests and the compose file of the
server make the core with the command `solr-precreate lds-index` of the Solr image, from the configuration the
image comes with. The provider sends fields that the core has not been told about, and relies on Solr to take
them.

The address is not tried at start. LDS starts when nothing answers at `search.index.url`, and also when the
value is empty or is not an address; the first request to Solr then fails. The start fails only when the
configuration does not have the key: `SolrProvider.getSearchIndex` then gives `null` to the builder, which
throws `IllegalArgumentException: Cannot create HttpSolrClient without a valid baseSolrUrl!`. The defaults of
the server have the key; the defaults of core do not.

### What a document becomes in the index

One version of a document becomes one entry in the index (`SolrSearchIndex.createOrOverwrite`). The entry has
these fields:

| Field | Value |
|---|---|
| `id` | `{namespace}/{entity}/{id}/{version}`. The version is the valid time of the version as `ZonedDateTime.toString()` writes it (`getIdString`). In a running LDS: `ns/Company/brew/2026-11-20T15:44:46.369361Z[Etc/UTC]`, and `ns/Company/old/2020-01-01T00:00Z[Etc/UTC]` for a write with `timestamp=2020-01-01T00:00:00Z`. |
| `entity` | The name of the [entity type](../glossary.md#entity-type). |
| one field for each text that is indexed, named by its path | The text. A path is written as `JsonToFlattenedDocument` writes it: `$.about[0].languageText`. |
| `searchfield` | Every text that is indexed, as one value each. A search looks here unless the query names another field. |

`addFields` decides which texts are indexed. The document is flattened to its leaf values by path. A leaf is
indexed when it is a text (`FragmentType.STRING`) and its path contains `languageText`. So:

- A text in another field (`name`), a number and a date are not indexed. The namespace and the version are
  in `id` only.
- The test is `String.contains` on the whole path, and upper and lower case differ. A field `languageTextual`
  or `MylanguageText` is indexed, and so is every text under an object named `languageTextBlock`. A field
  `LanguageText` is not.
- A field `languageText` that holds a number, `true`, `false` or `null` is not indexed. An empty text is
  indexed, as an empty value.
- A field `languageText` that holds an array of texts gives one field for each text
  (`$.title.languageText[0]`, `$.title.languageText[1]`).
- A document with no such text still gets an entry, with `id` and `entity` only.
- Every entity type is indexed. The argument `searchable` of `@domain` is not read (see
  [What is unfinished or known to be wrong](#what-is-unfinished-or-known-to-be-wrong)).

This document, with the key `ns`, `Company`, `brew` and the version `2026-11-20T15:29:31.800Z[Etc/UTC]`:

```json
{
  "name": "Nordic Brewing",
  "founded": "2001-05-01T00:00:00Z",
  "employees": 12,
  "about": [
    {
      "languageCode": "en",
      "languageText": "A small brewery by the fjord"
    },
    {
      "languageCode": "nb",
      "languageText": "Et lite bryggeri ved fjorden"
    }
  ],
  "languageTextual": "a text field whose name only starts with languageText",
  "notes": {"languageText": 42},
  "languageTextBlock": {
    "inner": "a text under an object whose name starts with languageText"
  }
}
```

was given to the provider while a program that prints every request stood where Solr is. The provider sent
`POST /solr/lds-index/update?wt=javabin` with the parameter `commitWithin=5000` and one entry:

| Field | Value |
|---|---|
| `id` | `ns/Company/brew/2026-11-20T15:29:31.800Z[Etc/UTC]` |
| `entity` | `Company` |
| `$.about[0].languageText` | `A small brewery by the fjord` |
| `$.about[1].languageText` | `Et lite bryggeri ved fjorden` |
| `$.languageTextual` | `a text field whose name only starts with languageText` |
| `$.languageTextBlock.inner` | `a text under an object whose name starts with languageText` |
| `searchfield` | the four texts above |

The time of the version is part of `id`. A write of another version of the same document has another `id`, so
it adds an entry and leaves the earlier ones. For a second write of the same version, the provider relies on
Solr to keep one entry for each `id`; no test checks it.

### Writing, and the five seconds

`createOrOverwrite` calls `add(entries, 5000)` on the update client. The number is `commitWithin` in
milliseconds: Solr is asked to commit the addition within five seconds. A search finds the document when Solr
has committed, so up to five seconds after the write: the index is eventually consistent.

Nothing is done until the `Completable` is subscribed to. Then the update client puts the addition in its
queue and returns, and the provider writes `Added 1 documents to index` to the log. `createOrOverwrite`
therefore completes when the addition is queued, not when Solr has it. When the request fails later, a thread
of the client (`concurrentUpdateScheduler-...`) writes it to the log (`ConcurrentUpdateBaseSolrClient - error`)
and the caller is not told.

An empty collection is sent too, as an update with no entry.

### Deleting

`delete` calls `deleteById(id, 5000)` with the `id` made from the key it is given, version included. It takes
out the entry of that one version. The request is sent when the `Completable` is subscribed to, and the thread
that subscribes waits for the answer of Solr, so a failure reaches the subscriber.

`deleteAll` calls `deleteByQuery("*:*")` and then `commit()`, so the index is empty when it completes. Only the
tests call it.

### Searching: the query and its filters

`search` builds one `SolrQuery`:

| Parameter | Value |
|---|---|
| `start` | `from`, narrowed to an `int` |
| `rows` | `size`, narrowed to an `int` |
| `q` | the query text as it is given, followed by the filter |
| `df` | `searchfield`, the field a word is looked for in when the query does not name a field |
| `hl` | `true` |
| `hl.fl` | `*languageText` |

The filter is added by `createFilters` when the set of entity types is not `null` and not empty. It is the text
`AND (entity:A OR entity:B)`, with one `entity:` for each name, after a space. The names come in the order the
set gives them.

The query text is not changed and not checked. It is in the query language of Solr, so `*:*` matches every
entry, and a query can name a field (`entity:Company`). An empty text gives `q=`, and `null` gives `q=null`.

The provider writes each query to the log before it sends it (`Executing query:`). These are from a running
LDS. Each pair is the arguments of the GraphQL field `Search` and the query the provider built from them:

```
query: "fjord"
    start=0&rows=10&q=fjord&df=searchfield&hl=true&hl.fl=*languageText

query: "fjord", filter: [Company]
    start=0&rows=10&q=fjord+AND+(entity:Company)&df=searchfield&hl=true&hl.fl=*languageText

query: "fjord", filter: [Company, Person]
    start=0&rows=10&q=fjord+AND+(entity:Company+OR+entity:Person)&df=searchfield&hl=true&hl.fl=*languageText

query: "fjord", first: 3
    start=0&rows=3&q=fjord&df=searchfield&hl=true&hl.fl=*languageText

query: "brewery OR entity:Person", filter: [Company]
    start=0&rows=10&q=brewery+OR+entity:Person+AND+(entity:Company)&df=searchfield&hl=true&hl.fl=*languageText
```

The query is sent as `GET /solr/lds-index/select` with these parameters and `wt=javabin`.

### What is returned

From the answer of Solr, `search` reads the number of hits (`numFound`) and the field `id` of each result.
`fromIdString` splits the `id` at every `/`: the first three parts are the namespace, the entity type and the id of
the document, and the fourth and fifth, joined with `/`, are the version, parsed with
`DateTimeFormatter.ISO_ZONED_DATE_TIME`. The results are in the order Solr gives them.

The answer is `new SearchResponse(numFound, results, from, size)`.

Highlighting is asked for (`hl`, `hl.fl`), but the highlights in the answer are not read.

The provider writes the number of hits to the log at INFO (`Number of hits:`), as it does the query.

`search` sends the query when it is called, not when the `Single` is subscribed to, and waits for the answer.
When anything fails, it writes `An error ocurred` to the log and throws a `RuntimeException` from the call. In
a running LDS the GraphQL request is then answered with status 200 and an error for the field, for example
`Exception while fetching data (/Search) : java.net.ConnectException: Connection refused (connect failed)`.

## Configuration

| Key | Default in core | Default in the server | Meaning |
|---|---|---|---|
| `graphql.search.enabled` | `false` | `false` | `true` makes an index at start, adds the index step to the sagas and the field `Search` to the GraphQL schema. |
| `search.index.provider` | empty | `solr` | The name of the provider (`getProviderId()`). Read only when search is enabled. The comment above the key in `application-defaults.properties` says it is a class name; that is wrong. |
| `search.index.url` | not there | `http://localhost:8983/solr/lds-index` | Read by the Solr provider: the address of the core. |

The defaults of core are in `application-defaults.properties` in `linked-data-store-core`. The defaults of the
server are in `application-server-defaults.properties` in `linked-data-store-server`. An environment variable
sets only a key that one of the properties files has; the server has all three, so `LDS_GRAPHQL_SEARCH_ENABLED`,
`LDS_SEARCH_INDEX_PROVIDER` and `LDS_SEARCH_INDEX_URL` work. The compose file of the server (`compose.yaml`)
sets them to `true`, `solr` and `http://solr:8983/solr/lds-index`.

A new provider that reads keys of its own needs them in a properties file before they can be set from outside.

## Rules to keep

These are from the rules of the project (see [the invariants](architecture.md#the-invariants)) and from the code.

- **Core does not know Solr.** Core depends on the interface module, and a provider is found by its name. Keep
  Solr types out of the interface.
- **JPMS.** Both modules are named modules. The interface module has `requires transitive` for RxJava 3 and for
  the persistence interface, because its methods give and take their types. A provider is registered with
  `provides` and in `META-INF/services`, both. `uses SearchIndexProvider` is in core, which calls
  `ServiceLoader`; the interface module has no `uses`.
- **The id of an entry is read back.** `fromIdString` must be able to read what `getIdString` writes. It relies on
  the version having a zone name with exactly one `/`. Core gives every version the zone `Etc/UTC`
  (`DataController`), which is written `...Z[Etc/UTC]`. Change the two methods together. A caller that makes a
  version with `ZonedDateTime.now()` gets the zone of the machine, which may have no `/` (`UTC`) or two.
- **One core for every namespace.** The namespace is part of `id` and is nowhere else in an entry.
- **The newest stable Solr.** The client is `solr.version` in the root POM. The image is `solr:10.1` in `Solr10`
  (the tests) and in `compose.yaml` of the server. Keep the two images the same: the tests start Solr as the
  compose file does.
- **Jackson.** The client of Solr brings Jackson 2 for its own use. LDS code uses Jackson 3 and does not use that
  one.
- **Lint and Javadoc.** Both modules still override the rule of the root POM: `-Xlint:all,-requires-automatic,-exports`
  in the interface module, `-Xlint:all,-requires-automatic` in the Solr module, and doclint `all,-missing` in
  both. An override is removed, not widened, when the module is revised. `solr-solrj` has no module name of its
  own (`requires solr.solrj` is taken from the name of the jar), which is why `requires-automatic` is off.
- **No spliced identifiers.** The rule says that names and search text are checked and never joined into a
  query. The Solr provider does not meet it yet; see below. New code must.
- **Tests run on a real Solr**, in a container published on 127.0.0.1 only.

## Tests

The Solr provider has one test class, `SolrSearchIndexTest` (JUnit 6, AssertJ). It runs on a real Solr 10 that
Testcontainers starts, so it needs Docker.

- `Solr10`: Starts one container of `solr:10.1` for the module, with the command `solr-precreate lds-index`, and
  waits until `/solr/lds-index/admin/ping` answers 200. `Solr10.configuration()` is the provider configuration
  that reaches it.

- `LoopbackPorts`: Publishes every port of the containers on 127.0.0.1 only. Testcontainers finds it through
  `META-INF/services/org.testcontainers.core.CreateContainerCmdModifier` in the test resources.
  `LoopbackPorts.check` fails the start when a port is published on another address.

- `SolrSearchIndexTest`: Makes the index through `SolrProvider`, as LDS does, and empties it before each test
  with `deleteAll`. It has two tests:

  - `aDocumentIsFoundByItsTextAndNotAfterItIsDeleted`: a document is found by a word of a `languageText` (`Norway`) with
    a filter on its entity type, the result has the namespace, entity type, id and version of the document, and after
    `delete` the word finds nothing.
  - `aSearchIsNarrowedToTheEntitiesItNamesAndToItsPage`: with three documents of two entity types, `*:*` finds three, a
    filter on one entity type finds two, a filter on both finds three, and `from` 2 with `size` 2 gives one result and
    still three hits in total.

A write is in the index within the five seconds of `commitWithin`, so the tests search again every 100
milliseconds until the number of hits is the one they expect, for at most 30 seconds (`await`).

The test documents are the JSON files in `src/test/resources`. The tests use three of the six. Each document
gets the version `ZonedDateTime.now()`, so its `id` has the zone of the machine the tests run on.

The tests do not cover: what is indexed and what is not (every test document has `languageText` fields, and no
test searches for a text outside them), a second version of a document, a delete with another version than the
one that was written, a failed request, and a search on a core that has no `searchfield` yet.

The interface module has no tests.

Tests in other modules touch this part:

| Test | What it checks |
|---|---|
| `SearchIndexConfiguratorTest` in core (TestNG) | A provider is loaded by its name (`testSearchIndex`), none is loaded when search is off, and a name that no provider has (the empty text) fails. |
| `QueryVisitorsTest` in core (JUnit) | The `Search` field, `TypeFilters` and `SearchResult` that `AddSearchTypesVisitor` makes. |
| `GraphQLSchemaGoldenTest` in core (JUnit) | The whole GraphQL schema that is built when there is an index, compared with files. |
| `GraphQLIntegrationTest` in core (TestNG) | `thatSeachByContactNameWorks` and `thatNestedSeachWorks` write, search and delete through LDS with the provider `testSearchIndex`. Core's tests run with search on (`application_test.properties`). |
| `ServerStackIT` in the server (JUnit) | `aDocumentIsFoundByATextOfIt` starts the compose file and finds a document through LDS and Solr by a word of its `languageText`. It asks again for up to 30 seconds. |

The tests of the module run with the build of the module and what it needs. The other commands are in
[the build](build.md):

```sh
./mvnw verify -pl linked-data-store-search-provider-solr -am
```

## What is unfinished or known to be wrong

Both modules hold code that is not revised for 3.0 yet.

Not finished: what search covers is under review. The Solr provider indexes only texts whose path contains
`languageText`. With a model that has no such field, search finds nothing. The field `searchfield` is in the
core only after the first such text is written. On a new core, a search before that is answered by Solr with
`undefined field searchfield`, and no test covers it.

Not finished: the argument `searchable` of `@domain` is not read. `DomainDirective.isSearchable` is called by
nothing. An entity type with `@domain(searchable: false)` is in `TypeFilters` and `SearchResult`, is indexed and is
found, like every other entity type.

Not finished: with a model of JSON Schema files and no GraphQL schema, search can be on, and every write goes
to the index, but LDS has no `Search` field and so nothing reads the index.

Not finished: the search text is joined into the query (`text + createFilters(typeFilters)`), which the rule
"No spliced identifiers" forbids. The text can name any field of the index. The filter is added without
brackets around the text, so `brewery OR entity:Person` with the filter `Company` becomes
`brewery OR entity:Person AND (entity:Company)`. The names of the entity types of the filter are joined in as
well, without a check; core gives the values of the GraphQL enum `TypeFilters` there.

Not finished: a failed addition is not seen. With no Solr at the address, a `PUT` with `sync=true` (a
[synchronous write](../glossary.md#synchronous-write)) is answered 201, the log has the error of the update
client, and the document is not in the index. Nothing sends it again.

Not finished: a delete fails when Solr cannot be reached. With no Solr at the address, a `DELETE` with
`sync=true` is answered 500 with no text, the log says `Observed Saga failure during execution`, and the
document is deleted from the persistence provider: the next `GET` is answered 404.

Not finished: a delete takes out one entry at most. `DeleteIndexSagaAdapter` gives the time of the delete as
the version, and `delete` removes the entry with exactly that `id`. The entries of the versions that were
written before have another time in their `id` and stay in the index. An entry is removed only when the
`DELETE` has the same `timestamp` as the `PUT` that wrote it. A search that matches an entry that stayed gets
no document for it (seen with the memory provider), leaves it out, and writes
`Cound not find document for key ...` to the log at ERROR, every time.

Not finished: there is one entry for each version, and a search returns one result for each entry that
matches. Core reads the document as it is at the snapshot time of the request for each result, and does not
use the version of the result. A document with two versions that match comes twice in the result, both times
as the same version, and a text that only an earlier version has still finds the document.

Not finished: nothing in a query says which namespace is searched. An LDS serves one namespace
(`namespace.default`), and a core that several LDS share answers each of them with the entries of all.
`QueryConnectionFetcher` reads a result from the namespace in its `id`.

Not finished: `fromIdString` reads only an `id` with exactly four `/`. A version without a zone name (`...Z`)
or with a zone name that has no `/` (`...Z[UTC]`) gives `ArrayIndexOutOfBoundsException`. A zone name with two
`/`, or a namespace, an entity type or a document id with a `/` in it, gives `DateTimeParseException`. One such
entry among the results fails the whole search.

Not finished: `SolrSearchIndexTest` passes only where the zone of the machine has exactly one `/` in its name
(`Europe/Oslo`, `Etc/UTC`), because its documents get the version `ZonedDateTime.now()`. Where the zone is
`UTC`, the `id` ends in `Z[UTC]` and `fromIdString` cannot read it.

Not finished: the index is not stopped. `SearchIndex` has no method to close it, `UndertowApplication.stop`
does not name the search index, and the two clients of Solr are never closed. An addition that is still in the
queue of the update client when LDS stops is not waited for.

Not finished: `search` runs when it is called and blocks the calling thread, and `delete` and `deleteAll`
block the thread that subscribes until Solr has answered. `from` and `size` are narrowed from `long` to `int`
without a check (`from` 4294967298 is sent as `start=2`), and a negative `from` is sent as it is.

Not finished: highlighting is asked for in every query and its result is thrown away.

Not finished: `SearchIndexProvider.configurationKeys()` is not called by anything for search.

Not finished: `UpdateIndexSagaAdapter` and `DeleteIndexSagaAdapter` are given the `Specification` and do not
use it.

Not finished: `TestSearchIndex` and `SimpleSearchIndex` are in the main code of core, not in its tests.

Not finished: the comment above `search.index.provider` in `application-defaults.properties` of core says the
value is the class name of a provider. The value is the name `getProviderId()` answers.

Not finished: the lint and doclint overrides in both POMs, the missing Javadoc, the Jackson dependency of the
interface module that no class uses, its test dependencies that no test uses (TestNG, AssertJ, JSONassert), and
the three test documents no test reads.
