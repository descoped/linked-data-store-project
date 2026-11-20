---
title: "The server"
description: "The main class, the defaults of the configuration, the image, the compose file."
order: 13
---

# The server module, its image and the compose file

This document covers the module `linked-data-store-server` of Linked Data Store (LDS): the main class, the
order in which the configuration is read, the defaults of the server, the container image and the compose file,
and the two tests of the image. It is for developers and maintainers who change how LDS is started, configured
or packaged.

## What the module is for

The module makes LDS something that can be started. Everything LDS does is in the other modules. The server
module adds five things:

- the main class, `io.descoped.lds.server.Server`, which reads the configuration, starts the application of core
  (`UndertowApplication`) and stops it when the process is asked to end;
- the defaults of the configuration, with which LDS starts in memory with nothing else running;
- the dependencies that put every provider on the class path: the three
  [persistence providers](../glossary.md#persistence-provider), the Solr
  [search index provider](../glossary.md#search-index-provider), the [saga log](../glossary.md#saga-log) in PostgreSQL
  and the [transaction log](../glossary.md#transaction-log) in PostgreSQL;
- the container image (`Dockerfile`) and a whole LDS for development (`compose.yaml`);
- an example [model](../glossary.md#model) (`schemas/schema.graphql`).

The module has no code that handles a request or a document.

## Where things are

All paths are under `linked-data-store-server`.

| File | What it holds |
|---|---|
| `src/main/java/io/descoped/lds/server/Server.java` | The main class. One method, `main`. |
| `src/main/java/module-info.java` | The module `io.descoped.lds.server`. It requires core, dynamic-configuration, SLF4J and Commons Logging. The comment on the last says that the Solr search provider needs it; the POM brings it as `jcl-over-slf4j`. |
| `src/main/resources/application-server-defaults.properties` | The defaults of the server: 47 keys, read over the 38 keys of core. |
| `src/main/resources/logback.xml` | The log format: one line for each event on standard output, level `INFO`. |
| `schemas/schema.graphql` | The example model: `Person`, `Address`, `Company`, `Text`. |
| `pom.xml` | The dependencies, and three plugin executions that prepare the image and run its tests. |
| `Dockerfile`, `.dockerignore` | The image. |
| `compose.yaml`, `postgres-init.sql` | LDS with Neo4j, PostgreSQL and Solr, for development. |
| `src/test/java/io/descoped/lds/server/ServerImageIT.java` | The test of the image as it is. |
| `src/test/java/io/descoped/lds/server/ServerStackIT.java` | The test of the compose file. |
| `src/test/java/io/descoped/lds/server/Lds.java` | The HTTP client the two tests share: `get`, `put` (a [synchronous write](../glossary.md#synchronous-write), with `sync=true`) and `graphql`. |
| `src/test/java/io/descoped/lds/server/LoopbackPorts.java` | Publishes the ports of the test containers on 127.0.0.1 only. Testcontainers finds it through `src/test/resources/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier`. |

`conf/` and `conf_dev/` in the module directory are not in the repository. `.gitignore` names both, so that a
developer can keep a local `conf/application.properties` there.

## How LDS starts and stops

`Server.main` does this, in this order:

1. It builds the configuration (see [Configuration](#configuration)).
2. It calls `UndertowApplication.initializeUndertowApplication(configuration)` in `linked-data-store-core`. That
   call reads the model, starts the persistence provider, the saga log, the search index and the transaction
   log, makes the thread pools of the sagas and builds the HTTP handlers. It does not open the port.
3. It adds a shutdown hook that logs `ShutdownHook triggered..` and calls `application.stop()`.
4. It calls `application.start()`, which opens the port and, when `saga.recovery.enabled` is `true`, starts the
   recovery of unfinished [sagas](../glossary.md#saga). Then `main` logs `Server started in ...ms..`.
5. It calls `application.enableSagaExecutionAutomaticDeadlockDetectionAndResolution()`, which starts the watchdog
   of the saga thread pool.
6. The main thread waits for ever (`Thread.currentThread().join()`).

When the process gets the signal to end, the JVM runs the shutdown hook, and `UndertowApplication.stop` stops the
application in order: new requests are answered 503 while those being handled complete, the running sagas
complete, then their thread pool, the saga log pool and the stores are closed. The last log line is
`Leaving.. Bye!`. The process then ends with 143, the code of a process ended by that signal. `stop()` waits
10 seconds at most for the requests, and then 10 seconds at most for the sagas
(`UndertowApplication.RUNNING_SAGAS_TIMEOUT_SECONDS`). What `initializeUndertowApplication`, `start` and `stop`
do inside is in [the application](core-application.md).

`main` also calls `application.stop()` in a `finally`. That is what stops the application when
`application.start()` throws, for example when the port is taken: LDS then logs `Leaving.. Bye!` and the
process ends with 1 and `java.lang.RuntimeException: java.net.BindException: Address already in use`. `stop`
does its work once; a second call returns at once.

The shutdown hook is added in step 3, after the whole of step 2. A signal that comes while LDS is still
starting, for example while it waits for an engine, ends the process at once with 143: nothing is stopped in
order, and neither `ShutdownHook triggered..` nor `Leaving.. Bye!` is logged.

Steps 1 and 2 are outside the `try`. See [What is not finished or known to be
wrong](#what-is-not-finished-or-known-to-be-wrong) for what follows from that.

An exception that ends the start is not a log line. The JVM prints it on standard error, as
`Exception in thread "main" ...` with its stack trace.

## Configuration

### The order of the sources

`Server.main` gives dynamic-configuration 3 (`io.descoped.config.DynamicConfiguration`) four names, and adds
each name twice: as a resource on the class path, then as a file under the working directory. Then come the
environment and the system properties. A later source wins over an earlier one.

| Order | Source | Where it is |
|---|---|---|
| 1 | `application-defaults.properties` on the class path | In the jar of `linked-data-store-core`. The name comes from `UndertowApplication.getDefaultConfigurationResourcePath()`. |
| 2 | `application-defaults.properties` under the working directory | Not there unless someone puts it there. |
| 3 | `application-server-defaults.properties` on the class path | In the jar of this module. |
| 4 | `application-server-defaults.properties` under the working directory | Not there unless someone puts it there. |
| 5, 6 | `conf/application.properties` on the class path, then under the working directory | The file a user writes. In the image the working directory is `/lds`, so the file is `/lds/conf/application.properties`. |
| 7, 8 | `conf_dev/application.properties` on the class path, then under the working directory | For a developer's own machine. |
| 9 | Environment variables that start with `LDS_` | Set only keys that sources 1 to 8 have. |
| 10 | System properties | Set only keys that sources 1 to 8 have. |

A source that is missing is skipped. On the class path only the first match of a name is read. The
configuration is read once, at start; a change of a file or a variable has no effect on a running LDS.

With the logger `io.descoped.config` at `DEBUG`, the library logs every source. `logback.xml` has no such
logger, so another logback file is given for it (see [Logging](#logging)). This is the start of LDS in a directory
without `conf` and `conf_dev`, with `LDS_HTTP_PORT` set (paths shortened):

```
DEBUG i.d.c.i.Resolver - Loaded 38 key(s) from classpath jar:file:...!/application-defaults.properties
DEBUG i.d.c.i.Resolver - Optional source not found: file .../application-defaults.properties
DEBUG i.d.c.i.Resolver - Loaded 47 key(s) from classpath jar:file:...!/application-server-defaults.properties
DEBUG i.d.c.i.Resolver - Optional source not found: file .../application-server-defaults.properties
DEBUG i.d.c.i.Resolver - Optional source not found: classpath conf/application.properties
DEBUG i.d.c.i.Resolver - Optional source not found: file .../conf/application.properties
DEBUG i.d.c.i.Resolver - Optional source not found: classpath conf_dev/application.properties
DEBUG i.d.c.i.Resolver - Optional source not found: file .../conf_dev/application.properties
DEBUG i.d.c.i.Resolver - 1 override(s) of environment (prefix LDS_): [http.port]
```

### The rules of dynamic-configuration 3

These rules are the library's. `Server` chooses only the sources and the prefix `LDS_`.

- **A variable or a system property never adds a key.** It sets a key only when one of the properties files has
  that key. A variable for a key that no file has is ignored, without a message.
- **The name of the variable.** For the key `http.port` the library looks for `LDS_http.port`, then
  `LDS_http_port`, then `LDS_HTTP_PORT`, and takes the first that is set. Every character of the key that is not
  a letter or a digit becomes `_`: `sagalog.config.connection-pool.max-size` is
  `LDS_SAGALOG_CONFIG_CONNECTION_POOL_MAX_SIZE`.
- **Two keys, one variable.** When one variable matches two keys, the configuration is refused:
  `environment variable LDS_MY_KEY matches both 'my.key' and 'my-key'`. Do not add a key to a defaults file
  that differs from another key only in `.`, `-` or `_`.
- **A system property has the name of the key**, without a prefix: `-Dhttp.port=8080`.
- **A variable that is set and empty sets the key to an empty value.** `LDS_GRAPHQL_SCHEMA=` makes LDS read
  JSON Schema files.
- **References.** `${key}` in a value is the value of another key, and `${key:default}` gives a default for a
  key that is missing. `$${` is a literal `${`. A reference to a missing key without a default, or a
  cycle, stops LDS at start: `Invalid configuration references: 'postgres.driver.host' from file
  .../conf/application.properties references missing key 'nowhere'`.
- **Typed values.** A boolean is `true` or `false`, in any case. Anything else throws a `ConfigurationException`
  that names the key and its source: `Configuration key 'graphql.enabled' from env LDS_GRAPHQL_ENABLED is not a
  valid boolean: 'yes' (expected true or false)`. A number that is not a number fails the same way. A value is
  checked when core reads the key, not when the configuration is built.
- **Secrets.** A key whose last part contains `password`, `passwd`, `secret`, `token`, `credential`, `privatekey`
  or `apikey` is a secret. Its value is written as `****` in `describe()` and in error messages. This also
  holds for `http.cors.allow.credentials`, which is a boolean.
- **Files are UTF-8.** A properties file that is not valid UTF-8 stops LDS at start.
- **Profiles.** `Server` does not set profiles, so the library takes them from the system property
  `config.profiles` or the variable `CONFIG_PROFILES`. With the profile `test`, each file is followed by a
  sibling with `-test` in its name: `conf/application-test.properties` after `conf/application.properties`,
  `application-server-defaults-test.properties` after the defaults of the server. Nothing in LDS uses this.

Core reads some keys with `getBoolean(key, false)` or `find(key)`. The default in such a call is for a key
that is missing, and with the configuration `Server` builds no such key is missing: every key core reads is in
`application-defaults.properties`. A default never covers a wrong value: `getBoolean("graphql.enabled", false)`
throws for `yes`.

### Why the keys of every provider are in the server defaults

The image is configured from outside with environment variables, and a variable sets only a key that a file
has. So `application-server-defaults.properties` has the keys of every provider LDS can be given: the three
persistence providers, the saga log in PostgreSQL, the transaction log in PostgreSQL and Solr. Without them,
`LDS_NEO4J_DRIVER_URL` would do nothing.

There is a second reason, for the persistence providers. `PersistenceConfigurator` in core asks the provider
for its keys (`PersistenceInitializer.configurationKeys()`) and refuses to start it when the configuration
lacks one of them. Core's own defaults have none of these keys, so without the server defaults no persistence
provider starts, not even `mem`. Seen with a configuration made of core's defaults, the example model and
`persistence.provider=mem`:

```
java.lang.IllegalArgumentException: Configuration missing for: [persistence.fragment.capacity]
```

The values keep LDS in memory: `persistence.provider=mem`, the memory saga log, the transaction log `discard`,
search off. The keys of the providers that are not in use have values that are not read.

No password has a default. The four password keys are there, empty.

A key is added to this file when the image needs to be given it. A key of the saga log, the transaction log
or search that is not in the file can still be used: write it in `conf/application.properties`. From then on
a variable can set it too. The saga log is given every key that starts with `sagalog.config.`, the
transaction log every key that starts with `txlog.config.`, and the search provider the whole configuration.
A persistence provider is different: it is given only the keys that its `configurationKeys()` names, so
another key does not reach it from any source.

### The keys of the server defaults

The second column is the value in `application-server-defaults.properties`. The third is the value in core's
`application-defaults.properties`; "none" means that core does not have the key. The last column names the
class that reads the key and says in a few words what the key is; how the value is used is in the document of
that part, such as [the application](core-application.md) and [search](search.md).

| Key | Server | Core | What reads it |
|---|---|---|---|
| `graphql.enabled` | `true` | `true` | `UndertowApplication`: whether `/graphql` and `/graphiql` are served. They are served only when `graphql.schema` is also set. |
| `graphql.schema` | `schemas/schema.graphql` | empty | `UndertowApplication`: the GraphQL schema that is the model. Looked for on the class path, then as a file under the working directory. Empty: the model is read from `specification.schema`. |
| `specification.schema` | `schemas` | empty | `JsonSchemaBasedSpecification.create`: folders and files with JSON Schema, separated by commas. Read only when `graphql.schema` is empty. |
| `persistence.provider` | `mem` | empty | `PersistenceConfigurator`: `mem`, `neo4j` or `postgres`. |
| `persistence.initialization.max-wait-seconds` | `60` | `0` | `PersistenceConfigurator`: how long it keeps trying to start the provider. It tries again after a second. |
| `persistence.fragment.capacity` | `8192` | none | `MemoryInitializer`: the largest size in bytes of one fragment of a value. |
| `neo4j.driver.url` | `bolt://localhost:7687` | none | `Neo4jInitializer`. |
| `neo4j.driver.username` | `neo4j` | none | `Neo4jInitializer`. |
| `neo4j.driver.password` | empty | none | `Neo4jInitializer`. |
| `neo4j.cypher.show` | `false` | none | `Neo4jInitializer`: whether every Cypher statement is logged. |
| `postgres.driver.host` | `localhost` | none | `PostgresDbInitializer`. |
| `postgres.driver.port` | `5432` | none | `PostgresDbInitializer`. |
| `postgres.driver.user` | `lds` | none | `PostgresDbInitializer`. |
| `postgres.driver.password` | empty | none | `PostgresDbInitializer`. |
| `postgres.driver.database` | `lds` | none | `PostgresDbInitializer`. |
| `saga.number-of-logs` | `30` | `50` | `SagaExecutionCoordinator`: how many saga logs this instance registers. |
| `saga.threadpool.core` | `50` | `100` | `UndertowApplication`: the thread pool of the sagas. Core logs a warning when `saga.threadpool.queue.capacity` is not smaller than this. |
| `saga.threadpool.max` | `100` | `200` | The same pool. The number of sagas that may run or wait at one time is (`saga.threadpool.max` + `saga.threadpool.queue.capacity`) / 2, so 55 with these values. |
| `saga.threadpool.keepalive.seconds` | `60` | `60` | The same pool. |
| `saga.threadpool.queue.capacity` | `10` | `20` | The same pool. |
| `saga.recovery.enabled` | `true` | `true` | `UndertowApplication`: whether unfinished sagas are recovered by themselves: those of this instance when it starts, then those of the cluster at intervals. |
| `saga.recovery.interval.seconds.min` | `30` | `30` | `SagaRecoveryTrigger`: the shortest wait between two recoveries. From 1 to 86400. |
| `saga.recovery.interval.seconds.max` | `60` | `60` | `SagaRecoveryTrigger`: the longest wait. From 1 to 86400, and not smaller than the shortest. Each wait is a random number of seconds from the shortest to the longest. |
| `sagalog.provider` | `no.cantara.sagalog.memory.MemorySagaLogInitializer` | the same | `UndertowApplication.configureSagaLogProvider`: the class name of the saga log. The other one is `no.cantara.sagalog.postgres.PostgresSagaLogInitializer`. |
| `sagalog.provider.initialization.max-wait-seconds` | `30` | `0` | The same method: how long it keeps trying. |
| `sagalog.provider.initialization.retry-interval-seconds` | `2` | `1` | The same method: the wait between two tries. |
| `sagalog.config.cluster.instance-id` | `01` | `01` | The saga log. Every key that starts with `sagalog.config.` is given to it without that prefix. |
| `sagalog.config.postgres.driver.host` | `localhost` | none | `PostgresSagaLogInitializer`. |
| `sagalog.config.postgres.driver.port` | `5432` | none | `PostgresSagaLogInitializer`. |
| `sagalog.config.postgres.driver.user` | `lds` | none | `PostgresSagaLogInitializer`. |
| `sagalog.config.postgres.driver.password` | empty | none | `PostgresSagaLogInitializer`. |
| `sagalog.config.postgres.driver.database` | `sagalog` | none | `PostgresSagaLogInitializer`. |
| `sagalog.config.connection-pool.max-size` | `50` | none | `PostgresSagaLogInitializer`: its connection pool. Each open saga log holds one connection, so the file says to keep it at least as large as `saga.number-of-logs`. Without the key the saga log takes 10. |
| `txlog.split.sources` | `false` | `false` | `TxlogRawdataPool`: whether every source has a topic of its own. |
| `txlog.default-source` | `default` | `default` | `TxlogRawdataPool`: the source of a write that names none. |
| `txlog.rawdata.topic-prefix` | `txlog-` | `txlog-` | `TxlogRawdataPool`: put in front of the source to make the name of the topic. |
| `txlog.rawdata.provider` | `discard` | `discard` | `UndertowApplication.configureTxLogRawdataClient`: `discard`, `memory` or `postgres`. |
| `txlog.rawdata.provider.initialization.max-wait-seconds` | `30` | `0` | The same method: how long it keeps trying. |
| `txlog.rawdata.provider.initialization.retry-interval-seconds` | `2` | `1` | The same method: the wait between two tries. |
| `txlog.config.postgres.driver.host` | `localhost` | none | `PostgresRawdataClientInitializer`. Every key that starts with `txlog.config.` is given to the transaction log without that prefix. |
| `txlog.config.postgres.driver.port` | `5432` | none | `PostgresRawdataClientInitializer`. |
| `txlog.config.postgres.driver.user` | `lds` | none | `PostgresRawdataClientInitializer`. |
| `txlog.config.postgres.driver.password` | empty | none | `PostgresRawdataClientInitializer`. It refuses an empty password. |
| `txlog.config.postgres.driver.database` | `txlog` | none | `PostgresRawdataClientInitializer`. |
| `graphql.search.enabled` | `false` | `false` | `SearchIndexConfigurator`: whether a search index is used. |
| `search.index.provider` | `solr` | empty | `SearchIndexConfigurator`: read only when search is on. |
| `search.index.url` | `http://localhost:8983/solr/lds-index` | none | `SolrProvider`: the address of the Solr core. |

Thirteen keys are in core's file only, and the server takes them as they are: the nine `http.` keys
(`http.host`, `http.port`, `http.prefix`, `http.request.dump` and the five `http.cors.allow.` keys),
`namespace.default`, `sagalog.config.cluster.owner`, `sagalog.config.cluster.name` and `saga.commands.enabled`.
Together the two files have 60 keys.

### Keys that no file has

These keys are read by a provider and are in neither defaults file. A variable cannot set them until
`conf/application.properties` has them.

- The saga log in PostgreSQL (`PostgresSagaLogInitializer`), each after `sagalog.config.`:
  `postgres.driver.sslmode`, `postgres.driver.sslrootcert`, `postgres.driver.socket-timeout`,
  `postgres.driver.tcp-keep-alive`, `postgres.statement-timeout`, `postgres.lock-timeout`,
  `connection-pool.connection-timeout`, and `postgres.driver.property.<name>` for a property of the driver.
- The transaction log in PostgreSQL (`PostgresRawdataClientInitializer`), each after `txlog.config.`:
  `postgres.driver.max-pool-size`, `rawdata.postgres.consumer.prefetch-size`,
  `rawdata.postgres.consumer.prefetch-poll-interval-when-empty`.

## Logging

`logback.xml` writes every event as one line on standard output, with the date, the offset of the time, the
thread, the level and the logger. There are no colour codes: the lines are read by whatever collects the output
of the container. The level is `INFO` for every logger. No key of the configuration changes it.

```
2026-11-20T16:50:09.577+01:00 [main] INFO  io.descoped.lds.server.Server - Server started in 445ms..
```

`logback.xml` is in the jar of the module, and no other jar of the class path has a logback file. Another
file is given with the system property of logback, `logback.configurationFile`. That is how the `DEBUG` lines
of the configuration above were made, with a file that sets the logger `io.descoped.config` to `DEBUG`:

```sh
LDS_HTTP_PORT=19212 java -Dlogback.configurationFile=/path/to/logback-debug.xml \
    -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

## The example model

`schemas/schema.graphql` has two [entity types](../glossary.md#entity-type), `Person` and `Company` (types with
`@domain`), and two types of [embedded objects](../glossary.md#embedded-object), `Address` and `Text`.
`Person.employer` and `Person.friends` are [links](../glossary.md#link) (`@link`).
`Company.about` is a list of `Text`, and `Text` has a field named `languageText`. That field is there so that
search can be tried: the Solr provider indexes the texts of the fields named `languageText` and nothing else.
What search covers is under review.

The file also declares the two directives, a type `Query` and the scalars `Date`, `DateTime` and `Time`. LDS
does not start with a model that lacks the directive declarations or the type `Query`; the process ends with 1.
Seen with each of them taken out of the example:

```
SchemaProblem{errors=['Person' [@6:1] tried to use an undeclared directive 'domain', ...]}
SchemaProblem{errors=[A schema MUST have a 'query' operation defined]}
```

The file is used in three places: the server defaults name it (`graphql.schema=schemas/schema.graphql`,
relative to the working directory), the Dockerfile copies `schemas/` into the image, and the compose file
mounts `schemas/` over the copy in the image. Both tests of the image write and read documents of this model,
so a change of the model is a change of the tests.

## The image

### What Maven prepares

The image is made of files that Maven has made. Three executions in `pom.xml` matter:

| Plugin | Phase | What it does |
|---|---|---|
| `maven-clean-plugin`, `remove-the-jars-of-an-earlier-build` | `prepare-package` | Deletes `target/*.jar` and `target/lib`. Without it, a library whose version has changed since the last build would be in `target/lib` twice, and in the image twice. |
| `maven-dependency-plugin`, `copy-runtime-dependencies` | `package` | Copies every dependency of scope `runtime` (which includes `compile`) to `target/lib`: core, the providers, and all they need. |
| `maven-failsafe-plugin` | `integration-test`, `verify` | Runs the tests named `*IT`, after the jars are made. |

The jar of the module itself (`target/linked-data-store-server-3.0.0-SNAPSHOT.jar`) holds `Server`, the module
descriptor, `application-server-defaults.properties` and `logback.xml`.

The providers are dependencies of scope `runtime`: `linked-data-store-persistence-provider-memory`, `-neo4j`
and `-postgres`, `linked-data-store-search-provider-solr`, `sagalog-postgres` and
`rawdata-client-provider-postgres`. `Server` does not name them; core finds them at run time: the persistence
providers, the saga log and the search provider with `ServiceLoader`, the transaction log with
`RawdataClient.create`. The memory saga log is in `sagalog-api`, and the transaction logs `discard` and
`memory` are in `rawdata-client-api`; both come with core. To put another provider into the image, add it here
with scope `runtime` and add its keys to `application-server-defaults.properties`.

### The Dockerfile

The context of the build is the module directory. `.dockerignore` leaves everything out of the context but
`target/lib/`, the jar of the module (not its `-sources` and `-javadoc` jars) and `schemas/`. The reactor is
packaged first; the comment at the top of the Dockerfile gives the two commands.

| Line | What it gives |
|---|---|
| `FROM eclipse-temurin:27-jre-alpine` | The JRE of the baseline JDK, on Alpine. |
| `RUN addgroup -S lds && adduser -S -D -H -G lds -h /lds lds` | A system group and a system user `lds`, without a password, with `/lds` as its home. `-H` says that `adduser` does not make the home; the next line makes `/lds`. |
| `WORKDIR /lds` | The working directory. Relative paths of the configuration start here: `schemas/schema.graphql` is `/lds/schemas/schema.graphql`, and the file a user mounts is `/lds/conf/application.properties`. |
| `COPY target/lib/ lib/`, `COPY target/linked-data-store-server-*.jar lib/` | Every jar in one folder, `/lds/lib`. |
| `COPY schemas/ schemas/` | The example model, `/lds/schemas/schema.graphql`. |
| `USER lds` | LDS does not run as root. No `COPY` line has `--chown`, and `/lds` is made before this line: nothing in the Dockerfile gives a file or a folder to the user `lds`. |
| `EXPOSE 9090` | The port of `http.port` as it is by default. |
| `HEALTHCHECK` | See below. |
| `ENTRYPOINT ["java", "-cp", "/lds/lib/*", "io.descoped.lds.server.Server"]` | See below. |

**The health check** runs `wget` against `http://127.0.0.1:${LDS_HTTP_PORT:-9090}/ping` inside the container.
For the first 60 seconds it asks every second (`--start-period=60s --start-interval=1s`), after that every 10
seconds, with 3 seconds for an answer; three failures in a row make the container unhealthy. `/ping` answers
200 as soon as the HTTP server is up, without asking a store.

The health check knows the port only from the variable `LDS_HTTP_PORT`, and knows no prefix. A port set in a
mounted `conf/application.properties`, or any `http.prefix` but `/`, makes the container unhealthy while LDS
runs. With `http.prefix=/lds`, `/ping` answers 404 and `/lds/ping` answers 200.

**The entry point** is in exec form and names `java`, with no script in between. So `java` is the process of the
container, and the signal of `docker stop` reaches the JVM. LDS then stops in order and the container ends
with 143. The comment in the Dockerfile says it: the signal that stops the container must reach `java`, so
that LDS answers what it is handling and completes its sagas before it ends. Options of the JVM are given in
the variable `JAVA_TOOL_OPTIONS`, which the JVM reads itself; the entry point has no place for them.

Neither the Dockerfile nor the compose file says how long Docker waits before it ends a container that was
asked to stop (no `STOPSIGNAL`, no `stop_grace_period`), so the time is Docker's own. `ServerImageIT` gives its
container 60 seconds.

### Why the class path and not the module path

LDS runs on the class path in the image (`-cp /lds/lib/*`), as it does in every test. The modules of LDS are
named modules and have descriptors, and those are checked when the modules are compiled. They are not used at
run time.

The reason: on the module path the libraries that Undertow is made of (Undertow, XNIO, WildFly client config,
SmallRye common) cannot make their message loggers. jboss-logging has to read each of their modules, which takes an
`--add-reads` or `--add-opens` for every one. A missing one shows only when that message is first written.

## The compose file

`compose.yaml` starts a whole LDS on one machine. It is for development: the passwords are written in the
file. The project name is `lds`. Every published port is on 127.0.0.1, and each can be moved with a variable.
The comment at the top of the file gives the commands that start the stack and that remove its data.

| Service | Image | Port on the machine | What it is for |
|---|---|---|---|
| `lds` | built from the `Dockerfile` (`build: .`) | 9090, moved with `LDS_PORT` | The server. It starts when the three other services are healthy. |
| `neo4j` | `neo4j:2026.10-community` | 7474 (`NEO4J_HTTP_PORT`), 7687 (`NEO4J_BOLT_PORT`) | The documents. User `neo4j`, password `lds-development`. |
| `postgres` | `postgres:18-alpine` | none | The saga log (database `sagalog`) and the transaction log (database `txlog`). User `lds`, password `lds`. |
| `solr` | `solr:10.1` | 8983 (`SOLR_PORT`) | Search, in the core `lds-index`. |

**`lds`.** The environment turns the defaults of the server into this stack:

| Variables | What they set |
|---|---|
| `LDS_PERSISTENCE_PROVIDER`, `LDS_NEO4J_DRIVER_URL`, `LDS_NEO4J_DRIVER_USERNAME`, `LDS_NEO4J_DRIVER_PASSWORD` | Persistence in Neo4j at `bolt://neo4j:7687`. |
| `LDS_SAGALOG_PROVIDER`, `LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_HOST`, `..._USER`, `..._PASSWORD`, `..._DATABASE` | The saga log in PostgreSQL, host `postgres`, database `sagalog`. |
| `LDS_SAGALOG_CONFIG_CLUSTER_OWNER`, `LDS_SAGALOG_CONFIG_CLUSTER_NAME`, `LDS_SAGALOG_CONFIG_CLUSTER_INSTANCE_ID` | `development`, `lds`, `01`. The owner is the name of the schema the saga log makes its tables in. The saga log in PostgreSQL refuses the default of core, `my.org` (see [What is not finished or known to be wrong](#what-is-not-finished-or-known-to-be-wrong)). |
| `LDS_TXLOG_RAWDATA_PROVIDER`, `LDS_TXLOG_CONFIG_POSTGRES_DRIVER_HOST`, `..._USER`, `..._PASSWORD`, `..._DATABASE` | The transaction log in PostgreSQL, host `postgres`, database `txlog`. |
| `LDS_GRAPHQL_SEARCH_ENABLED`, `LDS_SEARCH_INDEX_PROVIDER`, `LDS_SEARCH_INDEX_URL` | Search on, in Solr at `http://solr:8983/solr/lds-index`. |

Every one of these variables sets a key that a defaults file has. The ports of PostgreSQL are not set; they
are the 5432 of the server defaults. `./schemas` is mounted read-only as
`/lds/schemas`, so the model can be changed without building the image; the server is restarted to read it.
The service has no health check of its own; the one of the image holds.

**`neo4j`.** The data is in the volume `neo4j` (`/data`). The health check runs `cypher-shell` with `RETURN 1`.

**`postgres`.** The image makes the user `lds` and the database `lds`. `postgres-init.sql` is mounted into
`/docker-entrypoint-initdb.d` and makes the databases `sagalog` and `txlog`; the image runs it once, when the
data directory is empty. The data is in the volume `postgres` (`/var/lib/postgresql`). The health check is
`pg_isready` over TCP against the database `txlog`: PostgreSQL answers over TCP only when `postgres-init.sql`
has been run. The database `lds` is not used by the stack as it is configured; it is the database that
`postgres.driver.database` names by default. PostgreSQL has no port on the machine.

**`solr`.** The command `solr-precreate lds-index` makes the core `lds-index` from the configuration the image
comes with. The data is in the volume `solr` (`/var/solr`). The health check asks the ping of the core.

Each of the three health checks has a start period of 60 seconds in which it asks every 2 seconds.

The images are the ones the tests of the providers use. PostgreSQL stays at 18 by a decision that is one of
[the invariants](architecture.md#the-invariants) of the project.

## The tests

The module has no unit test. Its tests are the two tests of the image, JUnit 6 with AssertJ, run by Failsafe
because they need what the `package` phase makes. So `verify` runs them and `test` does not. How the tests of
every module run is in [the build](build.md). The command that runs only these two is `verify` for this module
and what it needs, with a `-Dtest` that matches no unit test:

```sh
./mvnw verify -pl linked-data-store-server -am -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
```

Both need the `docker` command on the path, not only the Docker engine: `ServerImageIT` calls `docker build`
and `docker image rm`, and `ServerStackIT` lets Testcontainers call `docker compose`. Both name their file
(`.` for the build, `compose.yaml`) relative to the working directory, so they expect to run in the module
directory.

| Test | What it promises |
|---|---|
| `ServerImageIT` | Builds the image with a tag of its own (`lds-server-it:<uuid>`), starts it with no configuration and waits for the health check, two minutes at most. Then: a `Company` and a `Person` are written (201) and the person is read back (200); a link is followed both ways with GraphQL (`employer`, and the reverse `employees`); a document with a number as `name` is refused with 400 and `Schema validation error: [$.name: integer found, string expected]`, and is not stored (404); `/ns/Company?schema` is JSON Schema 2020-12 and has `languageText`; `id -un` in the container says `lds`; a second container that is stopped ends with 143 and has logged `ShutdownHook triggered` and `Leaving.. Bye!`. The container and the image are removed afterwards. |
| `ServerStackIT` | Starts `compose.yaml` with `LDS_PORT`, `NEO4J_HTTP_PORT`, `NEO4J_BOLT_PORT` and `SOLR_PORT` set to `0`, so Docker picks the ports and the test runs next to a stack that is up. It builds the image (`withBuild`), waits for the health check of `lds`, five minutes at most, and removes the images it built (`RemoveImages.LOCAL`). Then: the log of the server names all four providers (`Using persistence provider: neo4j`, the saga log class of PostgreSQL, `Using transaction-log provider: postgres`, `Search index provider configured: solr`); a document is written, read back and its link followed both ways; a `Company` with a `languageText` is found by `Search(query: "fjord")`, asked again for up to 30 seconds. |

`LoopbackPorts` is this module's own copy of a class that every module with containers has. Testcontainers
publishes a port on every address of the machine; `LoopbackPorts.modify` changes that to 127.0.0.1, also for
the container Testcontainers starts itself. A port that is published on an address the test names is left as
it is. `LoopbackPorts.check` fails the test when a port of a started container is on another address; its
message asks whether the `META-INF/services` file is among the test resources. `ServerImageIT` calls it for
each container it starts. `ServerStackIT` calls it for the container of the service `lds`, whose port the
compose file publishes on 127.0.0.1.

These tests were not run for this document, and no image was built: what is said above about the image, the
compose file and the tests is read from the files.

## Running LDS from the module directory

After the reactor is packaged, the module directory has what LDS needs: the jars in `target` and `target/lib`,
and the model in `schemas`. The class path is every jar of both folders. The commands below were run in a
folder laid out that way, on JDK 27, with the port moved to 19212.

```sh
cd linked-data-store-server
LDS_HTTP_PORT=19212 java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

```
... INFO  io.descoped.lds.core.UndertowApplication - Creating specification using GraphQL schema: schemas/schema.graphql
... INFO  i.d.lds.core.persistence.PersistenceConfigurator - Using persistence provider: mem
...
... INFO  io.descoped.lds.core.UndertowApplication - Listening on 0.0.0.0:19212
... INFO  io.descoped.lds.server.Server - Server started in 445ms..
```

```sh
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19212/ping
```

```
200
```

A system property does the same as the variable, and wins over it:

```sh
LDS_HTTP_PORT=19999 java -Dhttp.port=19212 -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

```
... INFO  io.descoped.lds.core.UndertowApplication - Listening on 0.0.0.0:19212
```

`kill` with the process id (the log has it: `Started Linked Data Store server. PID ...`) stops LDS in order.
The exit code is 143.

```
... [Thread-0] WARN  io.descoped.lds.server.Server - ShutdownHook triggered..
... [Thread-0] INFO  io.undertow - stopping server: Undertow - 2.4.4.Final
... [Thread-0] INFO  io.descoped.lds.core.UndertowApplication - Leaving.. Bye!
```

The working directory matters: `graphql.schema` and `conf/application.properties` are relative to it. Started
from another directory, LDS ends with `java.nio.file.NoSuchFileException: schemas/schema.graphql`, unless
`LDS_GRAPHQL_SCHEMA` names the file with its whole path.

In some runs, after a number of requests, the JVM printed four lines on standard error that start with
`WARNING: A terminally deprecated method in sun.misc.Unsafe has been called` and name
`io.undertow.server.DirectByteBufferDeallocator` of Undertow 2.4.4. Every request of those runs was answered.

## Rules to keep

These come from the rules of the project and from the comments in the files of the module.

- **LDS runs on the class path.** Do not move the image to the module path (see above). Keep the module
  descriptor correct all the same: it is compiled.
- **`java` is the process of the container.** The entry point stays in exec form, with no script.
- **The image is configured from outside by environment variables.** So a key the image must be given is in
  `application-server-defaults.properties`, LDS in memory is the default, and no password has a default.
- **The image runs as the user `lds`**, has a health check on `/ping` and has the example model in `/lds/schemas`.
  `ServerImageIT` checks all three.
- **Every port of the compose file is published on 127.0.0.1 and can be moved.** `ServerStackIT` depends on the
  four variables.
- **Secrets.** The only passwords in the module are the development defaults of the compose file (`lds`,
  `lds-development`).
- **The JDK of the image is the baseline.** `java.version` in the root POM, the workflows and the `FROM` line
  are raised together, by hand. `.github/renovate.json` keeps `eclipse-temurin` at 27 and `postgres` at 18.
- **Nothing is published.** No image goes to a registry; CI builds the image only to test it.
- **Providers are found at run time.** `Server` names no provider. A provider is a dependency of scope `runtime`.
- **Lint and Javadoc.** The module has no exception from `-Xlint:all -Werror` or from doclint. Keep it so.
- **A module that starts containers has its own `LoopbackPorts`** and the `META-INF/services` file for it.
- **The image holds only what `.dockerignore` lets in.** A new file the image needs is added there too.

## What is not finished or known to be wrong

Not finished: a start that fails late leaves the process running. `Server.main` calls
`UndertowApplication.initializeUndertowApplication` before its `try`, and that method starts the thread
`saga-observer` (`SagasObserver.start`), which is not a daemon thread. When anything after that point throws,
`main` ends with the exception, nothing stops the thread, and the JVM stays up without an open port. The keys
that are read after that point are the `saga.` keys, and the keys the constructor of `UndertowApplication`
reads: `graphql.enabled`, `http.request.dump` and the `http.cors.allow.` keys. The constructor also builds the
GraphQL schema of the API (`GraphQLSchemaBuilder.getGraphQL`), after that point. Seen with
`LDS_GRAPHQL_ENABLED=yes`, `LDS_HTTP_REQUEST_DUMP=yes`, `LDS_HTTP_CORS_ALLOW_CREDENTIALS=yes`,
`LDS_HTTP_CORS_ALLOW_MAX_AGE=x`, `LDS_HTTP_CORS_ALLOW_ORIGIN=[` (not a regular expression),
`LDS_SAGA_THREADPOOL_CORE=x`, `LDS_SAGA_NUMBER_OF_LOGS=x`, `LDS_SAGA_COMMANDS_ENABLED=yes`,
`LDS_SAGA_RECOVERY_ENABLED=yes` and `LDS_SAGA_RECOVERY_INTERVAL_SECONDS_MIN=90` (larger than the `max`): the
exception is printed and the process is still there when it is ended from outside, 8 seconds later. Each
time `/ping` was not answered and a thread dump showed `saga-observer` as the only thread of LDS that is not
a daemon thread. For an image this means a container that runs and never becomes healthy. A failure before
that point ends the process with 1; seen with a wrong `http.port`, `LDS_TXLOG_SPLIT_SOURCES=yes`,
`LDS_GRAPHQL_SEARCH_ENABLED=yes`, an unknown provider, a model that is not found and an engine that does not
answer.

Not finished: a wrong configuration of the saga log or the transaction log is tried again like an engine
that is not up yet. `UndertowApplication.configureSagaLogProvider` and `configureTxLogRawdataClient` try
again after every exception until the `max-wait-seconds` of the provider are over. Seen:
`LDS_TXLOG_RAWDATA_PROVIDER=nope` ends after the 30 seconds of the server defaults and 16 tries with
`ProviderException: No provider named 'nope'; available: [discard, memory, postgres]`, and
`LDS_TXLOG_RAWDATA_PROVIDER=postgres` without a password ends only when the wait is over, with
`IllegalArgumentException: Missing configuration 'postgres.driver.password'`. An unknown `sagalog.provider`
and an unknown `persistence.provider` end the process at once, the first with only
`java.util.NoSuchElementException: No value present`.

Not finished: the default of `sagalog.config.cluster.owner` does not work with the saga log in PostgreSQL. The
value in core's defaults is `my.org`, and `PostgresSagaLogInitializer` takes letters, digits, `_` and `-`
only. LDS tries again for the whole of `sagalog.provider.initialization.max-wait-seconds` before it ends with
the message below, though no try can succeed. The compose file sets `development`.

```
java.lang.IllegalArgumentException: schema (cluster.owner) must match [A-Za-z0-9_-]+: 'my.org'
```

Not finished: the health check of the image asks `/ping`, which answers 200 whenever the HTTP server is up.
`/health/alive` is the address that opens a transaction in the persistence provider (`HealthCheckHandler` in
core), and its comments say that the search index and the logs are not checked yet. The health check also
does not follow `http.prefix` or a port from a mounted file.

Not finished: `Server.main` has a commented-out call, `application.triggerRecoveryOfIncompleteSagas()`. No
such method exists in `UndertowApplication`. Recovery is started by `UndertowApplication.start`.

Not finished: the log level cannot be set with a key. `logback.xml` is fixed at `INFO`.

Not finished: nothing says whether the profiles of dynamic-configuration are meant to be used. They work:
`CONFIG_PROFILES` or `-Dconfig.profiles` makes LDS read more files than the table of the sources lists. No
file of the repository names them.

Not finished: search finds a document by the texts of its fields named `languageText` only. What search covers
is under review.
