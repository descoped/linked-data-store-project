# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

Linked Data Store (LDS), a product of Descoped: a server that gives REST and GraphQL access to versioned, linked
JSON documents described by a model read at start (a GraphQL schema or JSON Schema files). Writes go through a saga
(transaction log, then persistence, then the search index). Persistence and search are pluggable. One Maven
reactor, group `io.descoped.lds`.

| Module | JPMS module | Role |
|---|---|---|
| `linked-data-store-project` (root) | n/a | parent and aggregator |
| `linked-data-store-bom` | n/a | BOM of the modules below |
| `linked-data-store-persistence-provider-api` | `io.descoped.lds.persistence.api` | the persistence interface, packages `io.descoped.lds.api.persistence..` (`PersistenceInitializer`, `RxJsonPersistence`, `RxPersistence`, the bridge, fragments), and `Specification` |
| `linked-data-store-persistence-provider-tck` | none yet (class path) | `PersistenceIntegrationTest` (TestNG), the suite every persistence provider extends |
| `linked-data-store-persistence-provider-memory` | `io.descoped.lds.persistence.memory` | provider `mem`: fragments in a sorted map in memory, the keys packed as tuples with `io.descoped.tuple:java-tuple`, behind the bridge (`MemoryRxPersistence`). For tests and for trying LDS, not for production |
| `linked-data-store-persistence-provider-postgres` | `io.descoped.lds.persistence.postgres` | provider `postgres`, revised for 3.0: one table, `fragment`, created at start, holding every namespace; every read is one statement whose rows are fetched as they are asked for |
| `linked-data-store-persistence-provider-neo4j` | `io.descoped.lds.persistence.neo4j` | provider `neo4j` |
| `linked-data-store-search-provider-api` | `io.descoped.lds.search.api` | the search SPI (`SearchIndexProvider`, `SearchIndex`) |
| `linked-data-store-search-provider-solr` | `io.descoped.lds.search.provider.solr` | provider `solr` |
| `linked-data-store-core` | `io.descoped.lds.core` | the application: `UndertowApplication`, REST (`NamespaceController`), GraphQL, sagas, transaction log |
| `linked-data-store-server` | `io.descoped.lds.server` | `Server` (the main class), configuration, Dockerfile and compose file |

Not in the reactor: `docs/` (AsciiDoc, describes the pre-3.0 layout), `linked-data-store-loadtest/` and
`lds-cluster-test/` (pre-3.0 tooling that does not work as it stands; replaced or removed in a later pull request).

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
store only on demand (`ResultSetPublisher`, `AsyncIterablePublisher`, both checked with the Reactive Streams TCK);
Neo4j implements `RxJsonPersistence` directly. Keep the mechanism when changing this code: a stream is not replaced
by a call that returns a complete list, and no blocking call is added to an interface.

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
- **Always the latest stable version** of every library, plugin, action, image and engine (PostgreSQL 18, Neo4j
  2026, Solr 10). Pre-releases only on request. Old and outdated code and configuration is removed,
  not carried along. The exception is the list under "Held versions".
- **No leftovers** of the earlier hosts and pipelines: no Statistics Norway (SSB) references, Azure Pipelines,
  Travis, Drone or OSSRH configuration.
- **Secrets.** Nothing but documented development defaults (the `lds` database user in tests and compose files).
- **Releases only with the owner's go-ahead**, by hand. **CI publishes nothing**: no SNAPSHOTs, no releases, no
  images. Maven Central goes through the Central Portal (profiles `central,sign`).
- **SonarCloud**: one project, `descoped_linked-data-store-project`, for the whole reactor.

Several of these describe where 3.0.0 is going and are not true of the pre-3.0 code yet (identifiers in Cypher,
UTC instants, `Transaction.close()` committing). New and revised code must meet them; a pull request that revises a
module makes that module meet them.

## Held versions

These stay at the version the code was written for until the pull request that revises that code, which moves them
to the latest stable and removes them from this list, from the "held" block in the root POM and from the rule in
`.github/renovate.json`:

| Dependency | Held at | Moves with |
|---|---|---|
| neo4j-java-driver | 4.0.2 | the Neo4j provider |
| solr-solrj, solr-test-framework (and the Restlet repository in the Solr module) | 7.7.1 | the Solr provider |
| graphql-java, extended scalars | 12.0, 1.0 | core, GraphQL |
| Undertow, xnio, jboss-threads | 2.0.30, 3.8.0, 2.3.3 | core, HTTP |
| everit json-schema, org.json, tika-core, okhttp | 1.14.6, 20260814, 1.24.1, 3.14.0 | core, validation (replaced or removed) |
| dynamic-configuration | 2.0.0 | core, housekeeping |

## Testing

- New and revised tests are JUnit 6 with AssertJ. The older tests are TestNG 7. Both run in one build and on the
  JUnit Platform (Surefire 3.6 runs TestNG through the TestNG engine for the platform, which it brings itself),
  on the class path (`useModulePath` false), because they share packages with the code. Tests move to JUnit as
  each module is revised; a class that extends the shared suite or the Reactive Streams TCK stays TestNG, because
  those are. Until core is revised, a test in core that needs the running application is TestNG, because the
  test server is started by a TestNG listener: a new test class asserts with AssertJ, a test added to an
  existing class follows that class.
- The PostgreSQL provider's tests (the shared suite, the Reactive Streams TCK for its publisher, and
  `PostgresProviderTest` for what the suite does not cover) run on a real PostgreSQL 18 (`postgres:18-alpine`)
  through Testcontainers, so **the build needs Docker**.
- The shared suite runs for the memory provider and for PostgreSQL. `Neo4jPersistenceIntegrationTest` is
  excluded in its module's POM, with the reason, until that provider is revised against a container. The memory
  provider also runs the Reactive Streams TCK for `AsyncIterablePublisher`.
- Core's `SagaLogPostgresTest` runs the write path with the saga log in a PostgreSQL 18 container: concurrent
  writes, recovery of an unfinished saga by the next write, after a restart and by another instance of the cluster.
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
./mvnw verify -pl linked-data-store-core -am        # one module and what it needs
./mvnw test -pl linked-data-store-core -Dtest=DataControllerTest
./mvnw verify -P coverage                           # with JaCoCo reports
```

The main class is `io.descoped.lds.server.Server` in `linked-data-store-server`. It reads core's
`application-defaults.properties`, `application-server-defaults.properties`, `conf/application.properties` and
`conf_dev/application.properties` (relative to the working directory), then `LDS_`-prefixed environment variables
and system properties. The `conf` files, the Dockerfiles and the compose file there are from before the monorepo
(they expect Neo4j, PostgreSQL and Solr from that compose file) and are rebuilt with the server module.
