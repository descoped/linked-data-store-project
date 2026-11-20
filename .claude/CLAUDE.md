# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

Linked Data Store (LDS), a product of Descoped: a server that gives REST and GraphQL access to versioned, linked
JSON documents described by a model read at start (a GraphQL schema or JSON Schema 2020-12 files). Writes go
through a saga (transaction log first, then persistence and the search index side by side). Persistence and
search are pluggable. One Maven reactor, group `io.descoped.lds`.

| Module | JPMS module | Role |
|---|---|---|
| `linked-data-store-project` (root) | n/a | parent and aggregator |
| `linked-data-store-bom` | n/a | BOM of the modules below |
| `linked-data-store-persistence-provider-api` | `io.descoped.lds.persistence.api` | the persistence interface, packages `io.descoped.lds.api.persistence..` (`PersistenceInitializer`, `RxJsonPersistence`, `RxPersistence`, the bridge, fragments), and `Specification` |
| `linked-data-store-persistence-provider-tck` | none yet (class path) | `PersistenceIntegrationTest` (TestNG), the suite every persistence provider extends |
| `linked-data-store-persistence-provider-memory` | `io.descoped.lds.persistence.memory` | provider `mem`: fragments in a sorted map in memory, the keys packed as tuples with `io.descoped.tuple:java-tuple`, behind the bridge (`MemoryRxPersistence`). For tests and for trying LDS, not for production |
| `linked-data-store-persistence-provider-postgres` | `io.descoped.lds.persistence.postgres` | provider `postgres`, revised for 3.0: one table, `fragment`, created at start, holding every namespace; every read is one statement whose rows are fetched as they are asked for |
| `linked-data-store-persistence-provider-neo4j` | `io.descoped.lds.persistence.neo4j` | provider `neo4j`, revised for 3.0 on Neo4j 2026 (community edition is enough): a document is a node with its entity as label and `namespace` and `id` as properties, unique together, so one database holds every namespace; a version is a `VERSION` relationship (`from`, `to`) to the root of a tree of `<entity>_E` nodes, one node for every object, array and value the document holds (`Neo4jDocumentWriter`); a link is a `REF` relationship to the document node it points at. The constraints and indexes are created at start |
| `linked-data-store-search-provider-api` | `io.descoped.lds.search.api` | the search SPI (`SearchIndexProvider`, `SearchIndex`) |
| `linked-data-store-search-provider-solr` | `io.descoped.lds.search.provider.solr` | provider `solr`, on the client of Solr 10 that uses the HTTP client of the JDK. It indexes into one core, named in `search.index.url` |
| `linked-data-store-core` | `io.descoped.lds.core` | the application: `UndertowApplication`, REST (`NamespaceController`), GraphQL, sagas, transaction log |
| `linked-data-store-server` | `io.descoped.lds.server` | `Server` (the main class), the defaults of the configuration, the example model (`schemas/`), the Dockerfile and the compose file (`compose.yaml`) |

| `linked-data-store-loadtest` | `io.descoped.lds.loadtest` | the load test, run by hand: scripts and a compose file that start LDS from the image of the server with one persistence provider and drive a load at it, and the program that turns the results into plot files (`HTTPLoadTestBaselineStatistics`). Not in the BOM and not in a release |

Not in the reactor: `docs/`, the documentation (see "The documentation" below). The cluster test of before 3.0
is removed: it needed images and a saga log that do not exist any more. Its history is in git.

Until 2026-11 every module was a repository of its own; their histories were imported here with `git filter-repo`
(old tags prefixed with the repository name, e.g. `linked-data-store-core/v0.16`). Nothing was released under
`io.descoped.lds` before. 3.0.0 is the first version built from this repository, and the work toward it goes module
by module: build first, then the persistence API and each provider, search, core, the server image.

## The persistence interface

LDS streams. Its persistence interface is the reactive one in `io.descoped.lds.api.persistence..`, on RxJava 3
(`Flowable`, `Maybe`, `Single`, `Completable`): `RxPersistence` carries fragments (one leaf value of one version of
one document; storage order is id, version, path), `RxJsonPersistence` carries documents, and
`RxJsonPersistenceBridge` with `RxGroupByTools.groupByOrdered` cuts one ordered fragment stream into documents,
asking the source for more one document at a time. The PostgreSQL and memory providers read their
store only on demand (`ResultSetPublisher`, `AsyncIterablePublisher`, both checked with the Reactive Streams TCK).
Neo4j implements `RxJsonPersistence` directly: a read is one statement that returns one record for each document,
and a record is taken from the driver when a document is asked for (`Neo4jTransaction.executeCypherAsync`). Keep
the mechanism when changing this code: a stream is not replaced by a call that returns a complete list, and no
blocking call is added to an interface.

A provider stores a document as it is given and gives it back the same: also a field the model does not have, a
value of another kind than the model says, a null, an array inside an array. Whether a document may be like that
is for the validation before a write to say, not for the store. The model tells a provider one thing: which
fields are links. A link is the text `/Entity/id` in such a field, naming an entity the field may link to; any
other value there is the value it is and is not followed. The shared suite holds every provider to this. One
thing differs, and LDS does not ask for it: memory and PostgreSQL also follow such a text in a field that the
model does not call a link, when asked for that path; Neo4j does not.

## The GraphQL schema

Core builds its GraphQL schema from the schema file with a chain of visitors (`GraphQLSchemaBuilder`), on
graphql-java 26. The visitors were written for graphql-java 12, and what they produce follows from the order in
which the schema is walked and from how types are built. LDS therefore has its own `TypeTraverser` (the walk of
graphql-java 12), `TypeCollectingVisitor` and `SchemaElements` (builders that keep the order of names, and replace a
directive by name). Keep them when changing this code: the `SchemaTraverser` of graphql-java walks differently.
`DateTimeScalar` gives a date-time out with the fraction of a second the value has, as LDS always did; the scalar
of graphql-java-extended-scalars now always writes three digits.

## JSON

LDS reads and writes JSON with Jackson 3 (`tools.jackson`, the modules `tools.jackson.core` and
`tools.jackson.databind`). A document is a tree (`JsonNode`); LDS binds no classes. Jackson 3 throws where Jackson 2
answered null, an empty text or 0 for a node of another kind, so a text is taken out of a node with
`JsonTools.text` (the text, or null when the node holds anything else) unless the node is known to be a text. An
entry of the transaction log is MessagePack (`TxLogTools`, on `org.komamitsu:jackson-dataformat-msgpack`, the
MessagePack format for Jackson 3); `TxLogToolsTest` holds the bytes of a message as they have always been
written. Jackson 3 refuses a text with something after the document, where Jackson 2 read the document and
ignored the rest: the body of a REST request (`BodyParser`), and the body and the `variables` parameter of a
GraphQL request (`GraphqlHttpHandler`). The client of Solr brings Jackson 2 for its own use; LDS code does not use
it.

## The JSON Schema of an entity

Every entity has a JSON Schema, in JSON Schema 2020-12 and nothing earlier: an object with `$defs` and a `$ref` to
the definition that is the entity (`{"$ref": "#/$defs/Person", "$defs": {"Person": {...}}}`). It is read from a
file (`specification.schema`), or written from the GraphQL schema (`GraphQLToJsonConverter`), and is what `?schema`
gives out. `JsonSchemaBuilder` reads it: the definitions the specification is built from, and the schema a
document is validated with before a write (`LinkedDocumentValidator`, on `com.networknt:json-schema-validator`,
which validates the Jackson tree of the document). A schema that at its root says it is another draft, or has
`definitions`, is refused at start, and so is a `$ref` that leads nowhere. Three things are LDS's own and are kept:

- A link is a text field with a sibling `_link_property_<field>`, an object whose properties are the entities the
  field may link to. The validator of JSON Schema sees two ordinary properties; LDS checks the link itself.
- `format` is checked (2020-12 only describes with it unless asked), so a text in a `date-time` field must be a
  date-time of RFC 3339.
- What is wrong with a document is answered as the place in the document, written as LDS writes a path, and the
  message of the validator: `$.tags[1]: integer found, string expected`.

## Invariants (never violate)

- **JDK 27 baseline** (`maven.compiler.release` 27). LDS is a product: it is built, tested and run on one JDK, and CI
  uses JDK 27 only. Raising it is a deliberate change of `java.version`, the workflows and the image together.
- **3.0.0 is a clean break.** Nothing was released before it, so no backward-compatibility shims, deprecated
  aliases, data-migration code or migration guides.
- **One reactor, one version.** Every module inherits the root POM and is released with it; modules depend on each
  other with `${project.version}`. Dependency versions are properties in the root POM, not `dependencyManagement`,
  so the BOM manages the LDS modules only.
- **JPMS.** LDS modules are named modules. A module whose exported API exposes another module's types `requires
  transitive` it. The module that defines a service interface declares `uses` for it. Providers are registered both
  with `provides` in `module-info.java` and in `META-INF/services`.
- **Lint and Javadoc.** `-Xlint:all -Werror` and doclint `all` with warnings as errors are the rule in the root POM.
  A module that still holds pre-3.0 code overrides them with the exact categories it is not clean of; the override
  is removed, not widened, when the module is revised.
- **One persistence contract for every provider**: the reactive interface, checked by the shared suite
  (`PersistenceIntegrationTest`) against the real engine (a container), not a substitute.
- **No spliced identifiers.** Entity, field, namespace and topic names and user search text are validated and never
  concatenated into SQL, Cypher, Solr queries, keys or file paths.
- **Time.** Document versions are stored and compared as UTC instants.
- **Transactions** commit only when asked and roll back otherwise.
- **Always the latest stable version** of every library, plugin, action, image and engine (Neo4j 2026, Solr 10).
  Pre-releases only on request. Old and outdated code and configuration is removed, not carried along. Renovate
  moves every dependency, and CI on JDK 27 is the gate. One version is held, by the owner's decision: **PostgreSQL
  stays at 18** (19.0 came out in November 2026 and is too new). The owner says when it is raised; Renovate is
  told to stay below 19 (`.github/renovate.json`).
- **No leftovers** of the earlier hosts and pipelines: no Statistics Norway (SSB) references, Azure Pipelines,
  Travis, Drone or OSSRH configuration.
- **Secrets.** Nothing but documented development defaults (the `lds` database user and the Neo4j password
  `lds-development` in tests and the compose file).
- **Releases only with the owner's go-ahead**, by hand. **CI publishes nothing**: no SNAPSHOTs, no releases, no
  images. Maven Central goes through the Central Portal (profiles `central,sign`).
- **SonarCloud**: one project, `descoped_linked-data-store-project`, for the whole reactor.

Several of these describe where 3.0.0 is going and are not true of the pre-3.0 code yet (UTC instants in the
memory provider, `Transaction.close()` committing). One is met in another way than it is worded: the Neo4j provider
writes the name of an entity into Cypher as a quoted label (`Cypher.label`), and does not validate it; a label as a
parameter costs Neo4j its indexes for lists, and a constraint cannot be created with one. Everything else it is
given is a parameter. New and revised code must meet them; a pull request that revises a
module makes that module meet them.

## Testing

- New and revised tests are JUnit 6 with AssertJ. The older tests are TestNG 7. Both run in one build and on the
  JUnit Platform (Surefire 3.6 runs TestNG through the TestNG engine for the platform, which it brings itself),
  on the class path (`useModulePath` false), because they share packages with the code. Tests move to JUnit as
  each module is revised; a class that extends the shared suite or the Reactive Streams TCK stays TestNG, because
  those are. Until core is revised, a test in core that takes its server from the TestNG listener
  (`TestServerListener`) is TestNG: a new test class asserts with AssertJ, a test added to an existing class
  follows that class. A new test class that starts a server of its own (`ApplicationStopTest`) is JUnit.
- The PostgreSQL provider's tests (the shared suite, the Reactive Streams TCK for its publisher, and
  `PostgresProviderTest` for what the suite does not cover) run on a real PostgreSQL 18 (`postgres:18-alpine`)
  through Testcontainers, so **the build needs Docker**.
- The Neo4j provider's tests (the shared suite, `Neo4jProviderTest` for what the suite does not cover,
  `Neo4jRoundTripTest` for documents of every shape and `Neo4jTransactionTest`) run on a real Neo4j 2026
  (`neo4j:2026.10-community`) through Testcontainers. `Neo4jDocumentWriterTest` checks the statement that writes
  and its parameters, without Neo4j.
- The Solr provider's tests run on a real Solr 10 (`solr:10.1`) through Testcontainers, started as the compose
  file of the server starts it: the image as it is, with the core `lds-index` created from the configuration the
  image comes with. A write is in the index within the five seconds the provider gives Solr to commit it.
- Every container of the tests is published on 127.0.0.1 only, the one Testcontainers starts itself (Ryuk)
  included: `LoopbackPorts`, which Testcontainers finds through `META-INF/services`, in each module that starts
  a container (PostgreSQL, Neo4j, Solr, core, server). A module that starts containers needs its own. Without it Docker
  Desktop on macOS can give a container a port that another program listens on at 127.0.0.1, and the build fails
  with "Could not connect to Ryuk" or with an engine that never answers. The helper that starts a container
  checks it (`LoopbackPorts.check`).
- The shared suite runs for every provider: memory, PostgreSQL and Neo4j. The memory provider also runs the
  Reactive Streams TCK for `AsyncIterablePublisher`.
- Core's `SagaLogPostgresTest` runs the write path with the saga log in a PostgreSQL 18 container: concurrent
  writes, recovery of an unfinished saga by the next write, after a restart and by another instance of the cluster.
- Core's `ApplicationStopTest` checks the order in which the application stops (`UndertowApplication.stop`): new
  requests are answered 503 while those being handled complete, the running sagas complete, then their thread
  pool, the saga log pool and the stores.
- The server's tests are the tests of the image (`*IT`, run by Failsafe after the jars are made, so by `verify`
  and not by `test`). `ServerImageIT` builds the image with `docker build` and starts it as it is: the example
  model in memory, the health check, the user it runs as, and that it stops when asked to. `ServerStackIT` starts
  `compose.yaml` with `docker compose`, with ports that Docker picks, and writes, reads, follows links and
  searches through LDS with Neo4j, PostgreSQL and Solr. So **the build needs the `docker` command with compose**,
  not only the Docker engine.
- Core's tests start the application in the JVM on a random port with the memory provider (`TestServerListener`).
  13 of them are disabled in the source (`enabled = false`, `@Ignore`), several because they hang; they are
  re-enabled or deleted with a reason when core is revised.
- A change that switches tests on is run about ten times before it is pushed
  (`for n in $(seq 1 10); do ./mvnw -B -q verify || break; done`).

## Maven Wrapper

- `./mvnw` pins Maven (`.mvn/wrapper/maven-wrapper.properties`, type `only-script`, so no jar in the repository) and
  checks the download against `distributionSha256Sum`. CI runs `./mvnw`. When Maven is bumped, by Renovate or by
  hand, set `distributionSha256Sum` to the SHA-256 of the new `apache-maven-<version>-bin.zip`, after checking that
  zip against the `.sha512` Maven publishes next to it.

## Commands

```sh
./mvnw verify javadoc:javadoc                       # what CI runs: build, tests, Javadoc (needs Docker and JDK 27)
./mvnw verify -pl linked-data-store-server -am -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false   # the image tests only
./mvnw verify -pl linked-data-store-core -am        # one module and what it needs
./mvnw test -pl linked-data-store-core -Dtest=DataControllerTest
./mvnw verify -P coverage                           # with JaCoCo reports
```

The main class is `io.descoped.lds.server.Server` in `linked-data-store-server`. It reads core's
`application-defaults.properties`, `application-server-defaults.properties`, `conf/application.properties` and
`conf_dev/application.properties` (relative to the working directory), then `LDS_`-prefixed environment variables
and system properties (dynamic-configuration 3: the variable `LDS_HTTP_PORT` sets `http.port`, and a variable or a
system property sets only a key that one of the files has).

## The server image

`linked-data-store-server/Dockerfile` makes the image from what Maven has packaged (`target/lib` and the jar of
the module) on `eclipse-temurin:27-jre-alpine`; the reactor is built first, and the context is the module
directory. `compose.yaml` next to it is a whole LDS for development: the image, Neo4j, PostgreSQL (saga log and
transaction log, the databases made by `postgres-init.sql`) and Solr, with the images the tests of the providers
use. Keep these when changing them:

- **LDS runs on the class path**, as the tests do. On the module path the libraries Undertow is made of (Undertow,
  XNIO, WildFly client config, SmallRye common) cannot make their message loggers: jboss-logging has to read
  each of their modules, which takes an `--add-reads` or `--add-opens` for every one, and a missing one shows
  only when the message is first written. The module descriptors are checked when the modules are compiled.
- **`java` is the process of the container** (`ENTRYPOINT` in exec form, no script), so the signal of
  `docker stop` reaches the JVM and LDS stops in order (`UndertowApplication.stop`); it then ends with 143.
- **The image is configured from outside by environment variables**, which set only keys a file has. So
  `application-server-defaults.properties` has the keys of every provider, with LDS in memory as the default and
  no password. A key is added there when the image needs to be given it.
- **The image runs as the user `lds`**, has a health check on `/ping`, and has the example model in
  `/lds/schemas`. No image is published (see Invariants).
- **Every port of the compose file is published on 127.0.0.1**, and can be moved (`LDS_PORT`, ...), which is how
  `ServerStackIT` runs next to a stack that is up. The passwords in it are development defaults.
- **The JDK of the image is the baseline**: Renovate is told to keep `eclipse-temurin` at 27.

## The documentation

`docs/` is AsciiDoc that GitHub renders, in two parts with two readers, and a document is for exactly one of them:
`docs/user/` for those who use LDS (they run it, give it a model, read and write documents; no class, module or
package names there) and `docs/developer/` for those who change the code (`architecture.adoc` is the map, then one
document for each part of the code). `docs/README.adoc` is the index. Keep these when changing it:

- **Only what the code does.** A statement is one found in the code, in a test or by running LDS, and every
  request shown was run, with the answer that came. A change of behaviour changes the document in the same pull
  request.
- **What is unfinished is said where the reader meets it**, in a paragraph that starts with `Not finished:`. When
  the fault is corrected, the paragraph goes.
- **Plain words, present tense, no history**: not how the code got this way, no earlier hosts or models.
- **One word for one thing**: document, entity, namespace, id, version, link, model, provider, saga.
- Lines of at most 120 characters, links between documents with `xref:` and a relative path.

## The load test

`linked-data-store-loadtest` is the load test LDS had before 3.0, brought up to date and kept as it was: the same
steps, the same load (80 reads to 20 writes, 1 to 50 threads, ten seconds each), the same model and templates
(`setup/gsim`, the schemas now JSON Schema 2020-12) and the same statistics and plots. `./ldsmemory.sh`,
`./ldsneo4j.sh` and `./ldspostgres.sh` run it; its README says what a run does. Keep these when changing it:

- **It is not part of the build**: nothing in `verify` or in CI starts it. Maven compiles and tests the
  statistics program with the reactor, and that is all.
- **It tests the image of the server module**, built by its compose file from `../linked-data-store-server`,
  with the images of the engines the tests of the providers use. Nothing is published on the machine.
- **The load tester is not ours**: `cantara/httploadtest-baseline:latest`, an image from 2021 for amd64 only,
  which starts only with the start options in `httploadtest/service_override.properties` mounted over its own.
- **A run leaves about 2 GB** in `lds<provider>/results` (every request is recorded), which git ignores.
- What the owner wants the load test to become is not decided. Until it is, it is changed only to keep it
  running.

The Solr provider indexes the texts of the fields named `languageText` and nothing else, which is from the model
LDS was first written for. The example model has such a field (`Company.about`) so that search can be tried.
