# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

Linked Data Store (LDS), a product of Descoped: a server that gives REST and GraphQL access to versioned, linked
JSON documents described by a model read at start (a GraphQL schema or JSON Schema 2020-12 files). Writes go
through a saga: the transaction log, then persistence, then, when there is search, the index queue, from which a
worker sends each update to the search index (below). Persistence and search are pluggable. One Maven reactor,
group `io.descoped.lds`.

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
| `linked-data-store-search-provider-solr` | `io.descoped.lds.search.provider.solr` | provider `solr`, on the client of Solr 10 that uses the HTTP client of the JDK. It indexes into one core, named in `search.index.url`, whose schema it puts in itself (`SolrSchema`, the same for every model); an update completes when Solr has answered it, and fails when Solr refuses it or cannot be reached |
| `linked-data-store-index-queue-postgres` | `io.descoped.lds.index.queue.postgres` | the index queue `postgres`: the updates the search index has not taken, in the table `lds_index_queue` of the database of the transaction log (`txlog.config.postgres.*`), created at start. Several LDS share it (`FOR UPDATE SKIP LOCKED`) |
| `linked-data-store-core` | `io.descoped.lds.core` | the application: `UndertowApplication`, REST (`NamespaceController`), GraphQL, sagas, transaction log |
| `linked-data-store-server` | `io.descoped.lds.server` | `Server` (the main class), the defaults of the configuration, the example model (`schemas/`), the Dockerfile and the compose file (`compose.yaml`) |

| `linked-data-store-loadtest` | `io.descoped.lds.loadtest` | the load test, run by hand: scripts and a compose file that start LDS from the image of the server with one persistence provider and drive a load at it, and the program that turns the results into plot files (`HTTPLoadTestBaselineStatistics`). Not in the BOM and not in a release |

Not in the reactor: `docs/`, the documentation, and `site/`, the documentation site (see "The documentation" below).
The cluster test of before 3.0 is removed: it needed images and a saga log that do not exist any more. Its history
is in git.

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

A provider gives the documents of a read in the order of its range, forward or backward (`Range.isBackward`, kept
by `Range.unlimited`), each one's fragments by path and offset, so the bridge cuts them into documents as they
come and never sorts. A list passes its range with the limit to the store, which leaves deleted documents out so
the limit counts what is given (PostgreSQL puts it on the statement); the versions of a document and a find pass
it without the limit (the bridge leaves out what the store gives beyond it), and the read ends when the page is
full. The memory provider reads backward by walking its keys from the end for the ids and reading each document
forward. Do not add a sort.

A provider stores a document as it is given and gives it back the same: also a field the model does not have, a
value of another kind than the model says, a null, an array inside an array. Whether a document may be like that
is for the validation before a write to say, not for the store. The model tells a provider one thing: which
fields are links. A link is the text `/Entity/id` in such a field, naming an entity the field may link to; any
other value there is the value it is and is not followed. The shared suite holds every provider to this. One
thing differs, and LDS does not ask for it: memory and PostgreSQL also follow such a text in a field that the
model does not call a link, when asked for that path; Neo4j does not.

## The index queue

Decided by the owner (D1, D2): a write does not wait for the search index, and no update of the index is lost.
One exception, asked for by the client (D7, "only when asked"): a write with `searchable=true` waits after its
saga, with the lock of its document let go, until the queue holds no update of its document, then for the index to
make it searchable (`SearchWait`, `IndexQueue.holds`, `SearchIndex.refresh`), at most 10 s, and is then answered
as any write is, with `LDS-Searchable: true` or `false` (exposed by CORS). At most half of the request threads wait
at once (owner's decision); a write past that is answered when it is done, with `false`. A write reads its body
blocking, on the worker thread it was handed to (`startBlocking`): Undertow's read without blocking gives a body that
comes in parts to an I/O thread, which the saga and the wait would block.
The last step of the saga adds the update (namespace, entity, id, version with its zone, written or deleted) to
the index queue (`IndexQueue`, in the search API) and is done. `IndexQueueWorker` in core takes updates in
batches of at most 100, reads each written document from persistence at its version, sends the batch and waits
for the index's answer, then removes them; what the index does not take is put off (one second, doubled each
attempt, a minute at most), and a batch that fails is tried one update at a time. The queue is kept where the
transaction log is: `postgres` (its own module, found by the service loader under the name of the transaction log
provider), or in memory when the transaction log is `discard` or `memory`, which LDS says at start. Keep these:
the saga step only adds; the worker reads storage, so the index gets what storage holds at the version; a read
transaction is opened and closed on the worker's thread (the memory store holds a lock of the thread).

The start option `search.index.rebuild` rebuilds the index from storage (`IndexRebuild`, decided by the owner, D3):
every version of every document of every entity type, read with `readAllDocumentVersions` (the persistence API,
held by the shared suite), is added to the queue, a deletion as a delete and the versions before it as writes, and
the worker sends them. The index is never cleared, so search answers during a rebuild. Storage is read only as fast
as the queue takes, and each read transaction is opened and closed on the rebuild's own thread.

## Readiness

`/health/ready` (decided by the owner, D4) asks every part a write needs, side by side, each within 2 s
(`Readiness`, package `core.health`): the store (a read of one document), the saga log (its logs, from its
database), the transaction log (the last message of its topic), and with search the index (`SearchIndex.health()`,
a query of no rows on the core) and the index queue (its lag). The answer is a JSON body with the state of LDS and
an entry for each check: 503 when one that writes need fails (DOWN), 200 when only search fails or the oldest
update in the queue has waited over a minute (DEGRADED). A client that blocks (JDBC) is asked on a virtual thread of
its own, and the read transaction is opened and closed there. `/ping` and `/health/alive` answer 200 whenever the process does (a liveness probe asks `/health/alive`, a
readiness probe `/health/ready`).

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
- **Limits.** One set of limits for every provider, constants in bytes of UTF-8 (`Limits` in the persistence API):
  namespace 64, entity type 128, id 1024, path of a value 1024, 32 arrays on a path. They are set by the store
  with the least room, PostgreSQL (one primary key entry of at most 2,704 bytes holds all of them), not by
  configuration. Core refuses what goes past them (400 on a write, no start for names), and the shared suite
  keeps a document at every limit on every provider (`thatADocumentAtEveryLimitIsKept`). A limit is raised only
  when every provider keeps the new one.
- **The order of ids** is the order of their Unicode code points (the order of their UTF-8 bytes), in every
  provider and in core: `IdOrder` in the persistence API. Memory and PostgreSQL (`COLLATE "POSIX"`) compare the
  bytes; the bridge and core compare with `IdOrder.CODE_POINTS`, never `String.compareTo` (UTF-16, which puts a
  letter above U+FFFF before one of U+E000 to U+FFFF); Neo4j orders and bounds by the property `idOrder`
  (`IdOrder.key`, indexed with the namespace) of every document node. An id is whole text, no unpaired surrogate.
- **Time.** Document versions are stored and compared as UTC instants.
- **Transactions** commit only when asked and roll back otherwise.
- **Always the latest stable version** of every library, plugin, action, image and engine (Neo4j 2026, Solr 10).
  Pre-releases only on request. Old and outdated code and configuration is removed, not carried along. Renovate
  moves every dependency, and CI on JDK 27 is the gate. Two versions are held, by the owner's decision, and the
  owner says when each is raised: **PostgreSQL stays at 18** (19.0 came out in November 2026 and is too new;
  Renovate is told to stay below 19), and **Maven stays at 3** until Maven 4 has matured (`requireMavenVersion`
  `[3.9.0,4)` in the root POM; Renovate keeps the wrapper's Maven below 4). Both in `.github/renovate.json`.
- **No leftovers** of the earlier hosts and pipelines: no Statistics Norway (SSB) references, Azure Pipelines,
  Travis, Drone or OSSRH configuration.
- **Secrets.** Nothing but documented development defaults (the `lds` database user and the Neo4j password
  `lds-development` in tests and the compose file).
- **Releases only with the owner's go-ahead**, started by hand. **CI publishes SNAPSHOTs only**: after a green build,
  on a push to `master` (`X.Y.Z-SNAPSHOT`) or a start by hand on a branch (`X.Y.Z-<branch>-SNAPSHOT`), the job
  `snapshot` deploys every module but the load test (`maven.deploy.skip`) to Descoped's AWS CodeArtifact repository,
  read publicly at `https://maven.descoped.io/` (also where LDS reads the SNAPSHOTs of Descoped libraries). The job
  names the target on its command line (`-DaltSnapshotDeploymentRepository`, for SNAPSHOTs only); **the POM has no
  `<distributionManagement>`, and never gets one**: that is what keeps a release from deploying to maven.descoped.io.
  The job's one input is the organization secret `CODEARTIFACT_SNAPSHOTS_ROLE_ARN`; the AWS account and the endpoint
  are never written in the repository. **LDS never publishes to Maven Central** (owner's decision, 2026-11-23): no
  profile, plugin or job for it, neither SNAPSHOTs nor releases. CI publishes no release and no image. A release
  (`3.0.0`) is the release workflow (`.github/workflows/release.yml`, `gh workflow run release.yml -f version=3.0.0`):
  the three images of the server on Docker Hub (`descoped/lds`, linux/amd64 and linux/arm64, signed with cosign), a
  tag and a GitHub release with its notes (`RELEASE-NOTES.md`). **No jar is released.** Its first step refuses a
  SNAPSHOT dependency (`requireReleaseDeps`). Builds are repeatable (`project.build.outputTimestamp`, base images by
  digest, `SOURCE_DATE_EPOCH`), so a release run can be repeated until the tag is made. A milestone (`3.0.0-M1`) is a
  tag and a GitHub release, source only. Both as `docs/developer/build.md` says.
- **SonarCloud**: one project, `descoped_linked-data-store-project`, for the whole reactor.

Several of these describe where 3.0.0 is going and are not true of the pre-3.0 code yet (UTC instants in the
memory provider, `Transaction.close()` committing). One is met
in another way than it is worded: the Neo4j provider writes the name of an entity into Cypher as a quoted label
(`Cypher.label`), and does not validate it; a label as a parameter costs Neo4j its indexes for lists, and a
constraint cannot be created with one.
Everything else it is given is a parameter. New and revised code must meet them; a pull request that revises a
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
  image comes with; the provider puts its schema in. A write is in the index within the second the provider gives
  Solr to commit it (`search.index.commit-within`). Each test writes in a namespace of its own.
- The index queue's tests (`PostgresIndexQueueTest`) run on a real PostgreSQL 18 through Testcontainers. Core's
  `IndexQueueWorkerTest` runs the worker on the memory store with an index that can refuse, and
  `MemoryIndexQueueTest` the queue in memory.
- Every container of the tests is published on 127.0.0.1 only, the one Testcontainers starts itself (Ryuk)
  included: `LoopbackPorts`, which Testcontainers finds through `META-INF/services`, in each module that starts
  a container (PostgreSQL, Neo4j, Solr, the index queue, core, server). A module that starts containers needs its
  own. Without it Docker Desktop on macOS can give a container a port that another program listens on at
  127.0.0.1, and the build fails
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
  and not by `test`). `ServerImageIT` builds the three images with `docker build` and starts each as it is: the
  example model in memory, `/ping` under the prefix and on the port it is given, no health check declared, the
  user it runs as, that it stops when asked to, no shell in the
  distroless image and the JDK tools in the debug image. `ServerStackIT` starts
  `compose.yaml` with `docker compose`, with ports that Docker picks, and writes, reads, follows links and
  searches through LDS with Neo4j, PostgreSQL and Solr. So **the build needs the `docker` command with compose**,
  not only the Docker engine.
- Core's tests start the application in the JVM on a random port with the memory provider (`TestServerListener`).
  12 of them are disabled in the source (`enabled = false`, `@Ignore`), several because they hang; they are
  re-enabled or deleted with a reason when core is revised.
- A change that switches tests on is run about ten times before it is pushed
  (`for n in $(seq 1 10); do ./mvnw -B -q verify || break; done`).

## Maven Wrapper

- `./mvnw` pins Maven (`.mvn/wrapper/maven-wrapper.properties`, type `only-script`, so no jar in the repository) and
  checks the download against `distributionSha256Sum`. CI runs `./mvnw`. When Maven is bumped, by Renovate or by
  hand, set `distributionSha256Sum` to the SHA-256 of the new `apache-maven-<version>-bin.zip`, after checking that
  zip against the `.sha512` Maven publishes next to it.

## Build cache

- `maven-build-cache-extension` (`.mvn/extensions.xml`, `.mvn/maven-build-cache-config.xml`) skips a module whose
  inputs have not changed, its tests included; only a build that starts with `clean` is kept. CI on a pull request
  reads the cache; a push to master builds and tests everything (`-Dmaven.build.cache.skipCache=true`). Files a
  module's tests read outside `src` are named as inputs (`maven.build.cache.input.*`, as in the server's POM).
  Javadoc is checked in `verify`, never as a goal on the command line, which would keep nothing in the cache.

## Commands

```sh
./mvnw -T 1C clean verify                           # what CI runs: build, tests, Javadoc (needs Docker and JDK 27)
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

`linked-data-store-server/Dockerfile` makes three images of the same LDS from what Maven has packaged (`target/lib` and
the jar of the module), each a target: `regular` (the default, tag `3.0.0`), on the
distroless base with busybox (`gcr.io/distroless/base-nossl-debian13:debug`); `distroless` (`3.0.0-distroless`), on
the distroless base with no shell; `debug` (`3.0.0-debug`), as `regular` with the modules of the JDK for diagnostics
(remote JMX, JDWP, `jcmd`, `jstack`, `jmap`, `jhsdb`, JFR) and debug information. The Java runtimes are made by
`jlink` in a stage from `eclipse-temurin:27-jdk`: the JDK modules `jdeps` finds in the jars and the few used only by
name (`jdk.charsets`, `jdk.localedata`), with the class data sharing archive of the default settings of the JVM.
Every base is pinned by digest. The reactor is built first, and the context is the module directory. `compose.yaml`
next to it is a whole LDS for development: the regular image, Neo4j, PostgreSQL (saga log and transaction log, the
databases made by `postgres-init.sql`) and Solr, with the images the tests of the providers use. Keep these when
changing them:

- **LDS runs on the class path**, as the tests do. On the module path the libraries Undertow is made of (Undertow,
  XNIO, WildFly client config, SmallRye common) cannot make their message loggers: jboss-logging has to read
  each of their modules, which takes an `--add-reads` or `--add-opens` for every one, and a missing one shows
  only when the message is first written. The module descriptors are checked when the modules are compiled.
- **`java` is the process of the container** (`ENTRYPOINT` in exec form, no script), so the signal of
  `docker stop` reaches the JVM and LDS stops in order (`UndertowApplication.stop`); it then ends with 143.
- **The image is configured from outside by environment variables**, which set only keys a file has. So
  `application-server-defaults.properties` has the keys of every provider, with LDS in memory as the default and
  no password. A key is added there when the image needs to be given it.
- **Three images, one LDS**: they differ only in base and runtime; the rest (`app` stage, user, entry point) is
  the same in each and is kept so. Nothing is installed in an image. The distroless image has `java` as its one
  program. The user `lds` is added to `/etc/passwd` in the build stage.
- **No image declares a health check.** LDS answers `/ping` (the process answers) and `/health/ready` under
  `http.prefix`, and whoever runs it asks there, at the port and prefix it gave LDS (`compose.yaml` asks
  `/health/ready`, the load test's compose file `/ping`, Kubernetes a probe). Only LDS reads its configuration: nothing in the image reads an `LDS_` variable or starts a
  second JVM, which would read `JAVA_TOOL_OPTIONS` and clash with a JMX or JDWP port.
- **The images run as the user `lds`** and have the example model in
  `/lds/schemas`. They are published only by the release workflow (see Invariants).
- **Every port of the compose file is published on 127.0.0.1**, and can be moved (`SERVER_PORT`, ...), which is how
  `ServerStackIT` runs next to a stack that is up. No variable of the compose file starts with `LDS_`, the prefix
  of the server's own variables, which a server started on the machine would refuse. The passwords in it are
  development defaults.
- **The JDK of the image is the baseline**: Renovate is told to keep `eclipse-temurin` at 27. A JDK module that a
  library uses only by name at run time is added to the `jlink` line by hand; `jdeps` does not see it.

## The documentation

`docs/` is Markdown as GitHub renders it, in two parts with two readers, and a document is for exactly one of
them: `docs/user/` for those who use LDS (they run it, give it a model, read and write documents; no class, module
or package names there) and `docs/developer/` for those who change the code (`architecture.md` is the map, then
one document for each part of the code). `docs/README.md` is the index. Keep these when changing it:

- **Only what the code does.** A statement is one found in the code, in a test or by running LDS, and every
  request shown was run, with the answer that came.
- **The documents follow the release.** They describe the released version, and the site publishes them. A pull
  request that changes behaviour changes code and tests, not `docs/`; what the documents must then say is kept
  outside the repository, and they are brought up to date after the next release. The documents are not a
  changelog.
- **The code as it is, not its history.** LDS went through several designs. The documents describe the current
  code only: no commit ids, no dates of changes, no code or test models that were removed. Earlier code and the
  documents of earlier versions are read to understand the thinking behind the current code, and are not
  described.
- **What is unfinished is said where the reader meets it**, in a paragraph that starts with `Not finished:`. When
  the fault is corrected, the paragraph goes.
- **Established technical terms, each defined once** in `docs/glossary.md` (entity type, document key, embedded
  object, link, valid time, as-of read, snapshot, tombstone, saga, transaction log, fragment, ...), and one term
  for one thing. A new term goes into the glossary first, defined from the code. Present tense, no history.
- **The documents stand alone.** They never cite this file; a rule of the project is stated in
  `docs/developer/architecture.md`, a command is given as the command.
- **Figures are Mermaid** (` ```mermaid `, which GitHub draws in light and dark), and show what the code does,
  as everything else does: a model as a class diagram (a filled diamond for an embedded type, an arrow for a
  link), a timeline as a Gantt chart without the marker of today, a path as a flowchart. What is a table is a
  Markdown table. What a model can say and the timeline of versions and snapshots are drawn where they are
  explained; a change to them changes the figure.
- **Examples are formatted.** A JSON body and a JSON answer are pretty-printed (two spaces; an object with one
  field, or two plain ones, and a list of plain values may stay on one line), with the fields in the order LDS
  sent them. A GraphQL query without variables is sent as `application/graphql` and formatted, one field on a
  line. A request body opens on the line of `-d` and its fields are indented under it. A session of several
  commands and their answers is a `console` block with `$` before each command. An answer is an `http` block only
  when its body is JSON; an answer of text or HTML is a plain block, which GitHub does not colour as invalid.
  Every page is looked at as GitHub draws it, in light and dark, before it is pushed for review.
- **Every document starts with front matter**: `title` (its name in the sidebar of the site), `description` (one
  sentence, as in the index) and `order` (its place in its folder's group), then its own `# Title`. A new document gets
  it, and a row in `docs/README.md`.
- **The documentation site** (`site/`, `site/README.md`) is a krantho static site (Next.js) that makes pages of
  `docs/` and adds a landing page (`site/content/landing/home.md`, held to the same rules: only what the code does,
  the status said plainly). `.github/workflows/pages.yml` publishes it to GitHub Pages (https://lds.descoped.io/,
  its custom domain) when `docs/`, `site/` or the workflow changes on `master`, or when started by hand; CI does not
  build it. Its mark is Descoped's (`|>o|`). Check a change with `cd site && pnpm build`, and look at it in light
  and dark at phone and desktop widths.
- Lines of at most 120 characters, except a row of a table. A link to another document is a relative path, and
  a link to a section names the id GitHub gives its heading, so a heading that is renamed has its links renamed.

## The Claude Code plugin

The repository is a marketplace of plugins for Claude Code (`.claude-plugin/marketplace.json`, marketplace `lds`),
with one plugin, `lds` (`plugins/lds/`): five skills (`start`, `model`, `data`, `query`, `operate`) that let someone
who has never used LDS start it, make a model of a domain, and write, read, query and operate it, by asking. Claude
acts with `curl` in its shell; there is no MCP server. `docs/user/claude-code.md` is its page. Keep these when
changing it:

- **No version.** `plugin.json` has no `version`, so the plugin is what `master` holds and every commit reaches its
  users when they update the marketplace. A change of the plugin is never a reason for a release.
- **Only what LDS does**, as the documentation: every command a skill shows was run against the released image, and
  a skill links the documentation of the site for depth instead of copying it. A change of behaviour that a skill
  shows changes the skill after the release that holds it, as the documents.
- **Honest about the limits** where the user meets them: no authentication, a JSON Schema model gives no GraphQL
  API, one namespace, the model is read at start, search only of `languageText` fields, links not checked.
- **A GraphQL schema is the model it makes first**, JSON Schema on request.
- `claude plugin validate .` passes (its warning that there is no version is meant). The plugin starts no CI: CI
  runs only for the Maven paths.

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

## Search

Decided by the owner (the design of 2026-11-23 in the dossier): the search index holds one entry for each version of
each document, written or deleted, never changed, with the texts of the version (`IndexEntry`, made by
`IndexEntries` in core with the model). A text in an object with a `languageCode` and a `languageText` is searched
in the language of the code, with that language's stemmer (`Language`: Norwegian bokmål and nynorsk, Swedish,
English, German, French); every other text word for word. Each text is also in a field with its letters folded (ø,
ö, oe as o; å, ä, æ, aa, ae as a in Norwegian, Swedish and texts of no language; accents taken off), before the
stem, so a word typed without its letters is found, and a match as it is written counts twice and ranks first. A
link, a date, a time, a number and a boolean are not text, and an entity type marked `@domain(searchable: false)`
has no entries. A search is at a moment (`SearchQuery`): of each document the newest entry up to it, tested for the
words only then, so a word only an older version had does not find the document; `history` gives every matching
version. The Solr schema is LDS's own and does not depend on the model (`SolrSchema`), so a model that changes needs
nothing of Solr; what was indexed under an earlier model stays until the index is rebuilt. Keep these: an entry is
never updated in place; the words are a parameter that edismax reads in the text fields with `uf=-*`, never part of
the query; the collapse to the newest version comes before the test of the words. A search therefore looks at every
version of the namespace up to the snapshot; its cost grows with the history (accepted, to be measured with the load
test). Fields for each path (filters, facets, where a word matched) are left out until there is a need.
