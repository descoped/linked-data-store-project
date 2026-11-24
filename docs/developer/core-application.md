---
title: "Core: the application"
description: "How LDS is put together, started and stopped."
order: 8
---

# Core: how the application is put together, started and stopped

This document covers the part of `linked-data-store-core` that builds the LDS application from its configuration,
starts it, routes an HTTP request to its handler, says whether LDS is ready, and stops the application in order. It is
for developers and maintainers who change this code. What the handlers do with a document is in
[the REST handlers](core-rest.md), [GraphQL](core-graphql.md) and [the write path](core-write-path.md). The index
queue and the rebuild of the search index are in [search](search.md). The main class and the files of the
configuration are in [the server](server.md).

## What this part is for

`UndertowApplication` is the whole of LDS as one object. Given a configuration it reads the
[model](../glossary.md#model), finds the providers, builds the [sagas](../glossary.md#saga), the parts of search and
the HTTP handlers, and holds them until it is stopped. Each part that holds threads, connections or a client is a
subsystem with a life of its own (a `Lifecycle`), started in the order the subsystems need each other and stopped in
the reverse order. The application is itself a `Lifecycle`. It has no `main` method:
`io.descoped.lds.server.Server` in `linked-data-store-server` builds the configuration and calls it, and the tests
of core call it in the same way.

## Layout

All of it is in `linked-data-store-core`, under `src/main/java/io/descoped/lds/core`.

| Class | Responsibility |
|---|---|
| `UndertowApplication` | `initializeUndertowApplication` makes every subsystem in a fixed order and adds each to the application's `Lifecycles` as it is made. The records `Parts`, `SagaParts` and `Indexing` carry what was made to the constructor, which builds the HTTP handler chain and the Undertow server. `start` and `stop` start and stop the subsystems. It also finds the saga log (`configureSagaLogProvider`) and the transaction log (`configureTxLogRawdataClient`). |
| `lifecycle.Lifecycle` | A subsystem: a name, `start` (nothing by default, for a subsystem that is live once it is made) and `stop(timeout)`. `Lifecycle.of` makes one from a name and the actions. |
| `lifecycle.Lifecycles` | The subsystems in the order they were added. Starts them in that order and stops them in the reverse order, once. |
| `persistence.PersistenceConfigurator` | Finds the persistence provider named in `persistence.provider`, checks that its keys are configured, and initializes it, trying again for a time. |
| `persistence.StoreReads` | The threads the store is read on, so that no thread of a request waits for it: a read transaction is opened, read and closed on one of them. Also the threads a GraphQL query goes on on after a search. |
| `search.SearchIndexConfigurator` | Finds the search provider named in `search.index.provider` when `graphql.search.enabled` is true. Otherwise it returns null, and LDS runs without search. |
| `search.IndexQueueConfigurator`, `search.MemoryIndexQueue` | Makes the index queue where the transaction log is kept: the queue of a provider with the name of the transaction log provider (`postgres`), or a queue in memory when the transaction log is `discard` or `memory`. |
| `search.IndexQueueWorker`, `search.IndexEntries`, `search.IndexRebuild` | The worker that sends the updates of the index queue to the search index, what an update becomes in the index, and the rebuild of the index from storage. See [search](search.md). |
| `health.Readiness`, `health.ReadinessCheck`, `health.Health`, `health.ReadinessHandler` | The readiness: every part a write needs is asked, side by side, and `/health/ready` answers with the worst state and an entry for each part. |
| `controller.CORSHandler` | The first handler of every request. Answers a CORS preflight request and adds the CORS headers to other requests. |
| `controller.NamespaceController` | Answers every path that is not a health path or a GraphQL path. It sends a path of the namespace to `DataController` and a path under `/source/` to `SourceHandler`. It answers the list of schemas itself. It holds the locks of the documents (`DocumentLocks`). |
| `controller.DataController` | Reads the time asked for, builds the `ResourceContext` of the path, takes the lock of the document for a write, and hands the request to the handler of a link, a document or a part of a document. |
| `controller.SourceHandler` | Answers `GET /source/{source}` with the last `sourceId` the transaction log holds for that source. |
| `persistence.PersistenceCreateOrOverwriteSagaAdapter`, `persistence.PersistenceDeleteSagaAdapter`, `search.UpdateIndexSagaAdapter`, `search.DeleteIndexSagaAdapter` | The steps of a saga that write to the persistence provider and add an update to the index queue. `SagaRepository` registers them. They are described in [the write path](core-write-path.md). |
| `search.TestSearchIndex`, `search.SimpleSearchIndex` | A search provider in memory with the id `testSearchIndex`, used by the tests of core. See [Not finished](#not-finished). |
| `utils.Jitter` | The wait between two attempts at start: the length given, give or take half, drawn anew each time. |
| `utils.RequestTime` | Reads the time a request names, in a REST `timestamp` or a GraphQL `snapshot`. |
| `utils.FileAndClasspathReaderUtils` | Reads a text from a file, or from the class path when there is no such file. `JsonSchemaBasedSpecification` reads the JSON Schema files with it, and tests read their example documents with it. |

The resources are `application-defaults.properties`, the defaults of every key core reads, and
`io/descoped/lds/graphql/graphiql/graphiql.html`, the page served at `/graphiql`.

## How the application is built

`UndertowApplication.initializeUndertowApplication(configuration)` reads `http.port` and calls
`initializeUndertowApplication(configuration, port)`. The tests call the second form with a port of their own.
The parts are made in this order, on the calling thread. Each later part is given the earlier ones. A part that is a
subsystem is added to the application's `Lifecycles` as soon as it is made, under the name in the last column, so
the order of the subsystems is the order in which they were made.

| Step | What is made | Subsystem |
|---|---|---|
| 1 | **The model.** When `graphql.schema` is not empty, the GraphQL schema file is parsed (`parseSchemaFile`), `GraphQLToJsonConverter` writes a JSON Schema for every [entity type](../glossary.md#entity-type), and `createJsonSpecification` builds the `JsonSchemaBasedSpecification` from them. When it is empty, the [specification](../glossary.md#specification) is built from the JSON Schema files and folders named in `specification.schema`, separated by commas (`JsonSchemaBasedSpecification.create`). A model with no entity type stops the start here, and so do a namespace and a name of an entity type longer than the stores have room for (`checkNames`). See [the model](core-model.md). | none |
| 2 | **The persistence provider.** `PersistenceConfigurator.configurePersistence` gives an `RxJsonPersistence`. | `Store` |
| 3 | **The threads the store is read on.** `new StoreReads()`: as many threads as there are processors, at least two, named `lds-store-read-<n>`. | `Store reads` |
| 4 | **The [saga log](../glossary.md#saga-log) pool.** `configureSagaLogProvider` checks the size of the pool of a saga log that has one (`checkSagaLogPool`) and gives a `SagaLogPool`. | `Saga log pool` |
| 5 | **Search**, only when `SearchIndexConfigurator.configureSearchIndex` gives a `SearchIndex`: the search index, the index queue (`IndexQueueConfigurator`), its worker, and the rebuild when `search.index.rebuild` is true. The saga repository is given the queue. | `Search index`, `Index queue`, `Index queue worker`, `Index rebuild` |
| 6 | **The [transaction log](../glossary.md#transaction-log).** `configureTxLogRawdataClient` gives a `RawdataClient`. It is wrapped in a `TxlogRawdataPool` with `txlog.split.sources`, `txlog.default-source` and `txlog.rawdata.topic-prefix`, and with whether it keeps messages: not when `txlog.rawdata.provider` is `discard`. | `Transaction log` |
| 7 | **The saga repository.** `SagaRepository.Builder.build` registers the adapters and the two sagas, one for a write and one for a delete. Each saga has a step that adds to the index queue only when there is search. | none |
| 8 | **The saga observer.** `new SagasObserver(sagaRepository).start()` starts the thread `saga-observer`. | `Saga observer` |
| 9 | **The thread pool of the sagas.** A `SelectableThreadPoolExecutor` with `saga.threadpool.core`, `saga.threadpool.max`, `saga.threadpool.keepalive.seconds` and a queue of `saga.threadpool.queue.capacity`. Its threads are named `sec-1`, `sec-2`, and so on. A task that does not fit is refused (`AbortPolicy`). A warning is logged when the queue capacity is not less than the core size. | `Saga thread pool` |
| 10 | **The recovery thread pool.** A scheduled pool of 10 threads (`SAGA_RECOVERY_THREADS`) named `saga-recovery-1` and so on. | in `Sagas` |
| 11 | **The saga execution coordinator.** `SagaExecutionCoordinator` registers `saga.number-of-logs` saga logs in the pool. | in `Sagas` |
| 12 | **The recovery trigger.** A `SagaRecoveryTrigger` when `saga.recovery.enabled` is true, with `saga.recovery.interval.seconds.min` and `saga.recovery.interval.seconds.max`. Otherwise there is none. | `Sagas`: the coordinator, the trigger and the recovery thread pool |
| 13 | **The namespace controller.** `NamespaceController` with `namespace.default`, the sagas and, with search, the wait of a write that asks to be searchable (`SearchWait`), which lets at most half of the request threads wait at once. | none |
| 14 | **The readiness checks** (in the constructor of `UndertowApplication`): the store, the saga log, the transaction log and, with search, the search index and the index queue. | `Readiness checks` |
| 15 | **The HTTP handlers and the Undertow server** (in the constructor). The chain described in [The HTTP handler chain](#the-http-handler-chain) and an Undertow server with one HTTP listener on `http.host` and the port, an I/O thread for each processor (at least two) and eight request threads for each I/O thread. The server is not started yet. | `HTTP server` |

The log of a start shows the order. This is the log of LDS started from its image, in memory with the example model,
each line shown from its message on. The lines of the GraphQL schema builder are left out.

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
```

```
Initializing Linked Data Store (LDS) server ...
Initializing specification ...
Managed domains: /Company /Person
Creating specification using GraphQL schema: schemas/schema.graphql
Initializing primary persistence ...
Using persistence provider: mem
Persistence provider initialization attempt # 1
Persistence service-provider configured
Initializing saga-log pool ...
Using saga-log-pool provider-class: no.cantara.sagalog.memory.MemorySagaLogInitializer
SagaLogPool provider initialization attempt # 1
SagaLogPool initialized!
Initializing search-index ...
GraphQL Search is disabled. Skipping search index initialisation
Initializing transaction-log ...
Using transaction-log provider: discard
Transaction-log provider initialization attempt # 1
Transaction-log initialized
Initializing saga repository ...
Initializing saga observer ...
Initializing saga thread-pool ...
Initializing saga-recovery thread-pool ...
Initializing saga-execution-coordinator ...
Initializing saga-recovery-trigger ...
Initializing namespace-controller ...
Initializing Http handlers ...
Initializing GraphQL Web API ...
...
Initializing health handlers ...
Using http prefix: /
Initializing CORS-handler ...
Initializing Undertow ...
Starting RxJava schedulers ...
Starting Linked Data Store ...
Starting Store ...
Starting Store reads ...
Starting Saga log pool ...
Starting Transaction log ...
Starting Saga observer ...
Starting Saga thread pool ...
Starting Sagas ...
Starting saga-recovery-trigger ...
Starting Readiness checks ...
Starting HTTP server ...
starting server: Undertow - 2.4.4.Final
...
Started Linked Data Store server. PID 1
Listening on 0.0.0.0:9090
Server started in 309ms..
```

With search on, the log has `Search index provider configured: solr` after the saga log pool, and the start has
`Starting Search index ...`, `Starting Index queue ...` and `Starting Index queue worker ...` between
`Starting Saga log pool ...` and `Starting Transaction log ...`. With a transaction log that keeps nothing durable the
index queue is in memory, and a warning says so:

```
The index queue is in memory, as the transaction log (discard) is: updates the search index has not taken when LDS
stops are lost, and are found again only by a rebuild of the index
```

The GraphQL schema file is parsed twice: once for the model, and once more in the constructor for the GraphQL
API.

### How the providers are found

Every provider is found with `java.util.ServiceLoader`. LDS runs on the class path, so a provider is found
through its file in `META-INF/services`. The `provides` clause of its module descriptor is not read then.

| Part | Service interface | Chosen by | Configuration it is given |
|---|---|---|---|
| Persistence | `io.descoped.lds.api.persistence.PersistenceInitializer` | `persistence.provider` equals the value of the `@ProviderName` annotation on the class. The instance must give the same id from `persistenceProviderId()`. | The keys the provider names in `configurationKeys()`. Each must be configured, or the start fails with `Configuration missing for: [...]`. It is also given `namespace.default` and the names of the entity types. |
| Saga log | `no.cantara.sagalog.SagaLogInitializer` | `sagalog.provider` equals the name of the class. | Every key that starts with `sagalog.config.`, without that prefix. |
| Search | `io.descoped.lds.api.search.SearchIndexProvider` | `search.index.provider` equals `getProviderId()`. Only when `graphql.search.enabled` is true. | The whole configuration as a map. `SearchIndexProvider.configurationKeys()` is not read, so core does not check that the keys of the search provider are configured. |
| Index queue | `io.descoped.lds.api.search.IndexQueueProvider` | `txlog.rawdata.provider` equals `providerId()`. Only with search, and not when the transaction log is `discard` or `memory`. | The whole configuration as a map. The queue in PostgreSQL reads the keys of the transaction log in PostgreSQL. |
| Transaction log | Found by `RawdataClient.create` of `io.descoped.rawdata:rawdata-client-api`. | `txlog.rawdata.provider` is the name of the provider. | Every key that starts with `txlog.config.`, without that prefix. |

The persistence providers and their ids are in [the persistence interface](persistence-api.md). The search
provider and the index queue are in [search](search.md).

### Trying again at start

A store may not be up when LDS starts. Three of the parts are therefore initialized in a loop that tries again until
a time has passed. A wait of 0 means one attempt. The time between two attempts is drawn anew each time, from half
the interval to one and a half times it (`Jitter.around`), so that several LDS that start together do not keep
trying at the same moments; it never goes past the end of the longest wait.

| Part | Longest wait | Time between attempts | What is tried again |
|---|---|---|---|
| Persistence | `persistence.initialization.max-wait-seconds` | One second, give or take half. There is no key for it. | A `RuntimeException` from `PersistenceInitializer.initialize` |
| Saga log | `sagalog.provider.initialization.max-wait-seconds` | `sagalog.provider.initialization.retry-interval-seconds`, give or take half | Any exception from `SagaLogInitializer.initialize` |
| Transaction log | `txlog.rawdata.provider.initialization.max-wait-seconds` | `txlog.rawdata.provider.initialization.retry-interval-seconds`, give or take half | Any exception from `RawdataClient.create` |

The search provider and the index queue are not tried again.

Each attempt writes one line to the log, `... initialization attempt # n`. The exception of an attempt that fails
is not logged. When the wait is over, the exception of the last attempt is thrown and the start fails. This is
LDS started with the PostgreSQL provider and nothing listening on the port, with a wait of 3 seconds:

```sh
docker run --rm -e LDS_PERSISTENCE_PROVIDER=postgres -e LDS_POSTGRES_DRIVER_PORT=1 \
  -e LDS_POSTGRES_DRIVER_PASSWORD=x -e LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=3 descoped/lds:3.0.1
```

```
... 23:23:14.651Z [main] INFO  ... - Persistence provider initialization attempt # 1
... 23:23:16.439Z [main] INFO  ... - Persistence provider initialization attempt # 2
... 23:23:17.655Z [main] INFO  ... - Persistence provider initialization attempt # 3
... 23:23:18.657Z [main] ERROR ...UndertowApplication - LDS could not be made; what was made is stopped
Exception in thread "main" com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize ...
...
Caused by: org.postgresql.util.PSQLException: Connection to localhost:1 refused. Check that the hostname and ...
...
Caused by: java.net.ConnectException: Connection refused (connect failed)
```

The process ended with exit code 1.

The loop does not tell a store that is not up from a configuration that is wrong. A persistence provider or a
saga log that is not found fails at once, before the loop, and so do a `persistence.provider` that is not set, a
model with no entity type and a saga log pool that is too small. A transaction log provider that is not found, and a
configuration that the provider refuses, are tried again until the wait is over:

```sh
docker run --rm -e LDS_TXLOG_RAWDATA_PROVIDER=nosuch \
  -e LDS_TXLOG_RAWDATA_PROVIDER_INITIALIZATION_MAX_WAIT_SECONDS=3 descoped/lds:3.0.1
```

```
... 23:23:19.465Z [main] INFO  ... - Transaction-log provider initialization attempt # 1
... 23:23:20.498Z [main] INFO  ... - Transaction-log provider initialization attempt # 2
... 23:23:22.208Z [main] INFO  ... - Transaction-log provider initialization attempt # 3
... 23:23:22.465Z [main] INFO  ... - Transaction-log provider initialization attempt # 4
... 23:23:22.466Z [main] ERROR ...UndertowApplication - LDS could not be made; what was made is stopped
Exception in thread "main" ...ProviderException: No provider named 'nosuch'; available: [discard, memory, postgres]
```

### The saga log pool, checked at start

A saga log whose provider keeps its logs in a pool of connections (the saga log in PostgreSQL, whose
`configurationOptionsAndDefaults()` has `connection-pool.max-size`) holds one connection for each log that is open.
`checkSagaLogPool` refuses, before the provider is initialized, a pool that has fewer connections than
`saga.number-of-logs` + 11: one for each saga log, one for each of the 10 recovery threads, which may each open a log
of another instance at once, and one for the queries of the pool itself. The size is
`sagalog.config.connection-pool.max-size`, or the default of the provider when the key is not set. The message
names both keys:

```
The saga log has 10 connections in its pool (sagalog.config.connection-pool.max-size) for 30 saga logs
(saga.number-of-logs): each open saga log holds a connection, recovery opens up to 10 logs of other instances at
once, and the pool needs one for its own queries. Give the pool at least 41 connections, or use fewer saga logs.
```

The message is one line in the log. A saga log without a pool (in memory, in files) is not checked.

### When the start fails

When a part cannot be made, `initializeUndertowApplication` logs `LDS could not be made; what was made is stopped`,
stops every subsystem made before it, in the reverse order and with the time of a stop for each, and throws what went
wrong. `Server.main` then stops what the process made (RxJava's shared schedulers) and lets the exception end the
main thread: it is printed as `Exception in thread "main" ...`, and the process ends with exit code 1.

This holds wherever the start fails: in a part that is tried again (the two examples above), and in a part made later,
such as the saga observer or the thread pools, whose threads are stopped. Each of these ended the process with exit
code 1, within a second:

| Variable | What it ended with |
|---|---|
| `LDS_HTTP_CORS_ALLOW_ORIGIN=[` | `java.util.regex.PatternSyntaxException: Unclosed character class near index 0` |
| `LDS_SAGA_RECOVERY_INTERVAL_SECONDS_MIN=90` (more than the `max`) | `java.lang.IllegalArgumentException: illegal configuration: intervalMaxSec < intervalMinSec` |
| `LDS_HTTP_REQUEST_DUMP=yes` | `ConfigurationException: Configuration key 'http.request.dump' from env LDS_HTTP_REQUEST_DUMP is not a valid boolean: 'yes' (expected true or false)` |
| `LDS_SAGA_THREADPOOL_CORE=x` | `ConfigurationException: Configuration key 'saga.threadpool.core' from env LDS_SAGA_THREADPOOL_CORE is not a valid int: 'x' (For input string: "x")` |
| `LDS_GRAPHQL_SEARCH_ENABLED=true LDS_SEARCH_INDEX_PROVIDER=testSearchIndex` | `java.lang.IllegalStateException: No search index provider found for providerId: testSearchIndex` |

A failure in `start` is handled by `Lifecycles.start`: when a subsystem cannot start, every subsystem is stopped,
those after it that did not start included, and the start fails with `IllegalStateException: <name> could not
start`. A port that is taken fails so: the log shows every subsystem started up to `Starting HTTP server ...`, and
then

```
Exception in thread "main" java.lang.IllegalStateException: Linked Data Store could not start
...
Caused by: java.lang.IllegalStateException: HTTP server could not start
...
Caused by: java.lang.RuntimeException: java.net.BindException: Address already in use
...
Caused by: java.net.BindException: Address already in use
```

The process ended with exit code 1. That was a second LDS started in the network of a running one
(`docker run --network container:<the first> descoped/lds:3.0.1`).

## The HTTP handler chain

The constructor of `UndertowApplication` builds one chain. A request passes it from the top:

```
Undertow listener (http.host, http.port)
  CORSHandler                          answers a preflight request itself
    GracefulShutdownHandler            counts the requests in flight; answers 503 while the application stops
      PathHandler for http.prefix      404 for a path outside the prefix
        request dump                   only when http.request.dump is true
          PathHandler
            /graphql        exact      GraphqlHttpHandler   (only with GraphQL)
            /graphiql       exact      the GraphiQL page    (only with GraphQL)
            /health/alive   exact      200
            /health/ready   exact      ReadinessHandler
            /ping           exact      200
            /               prefix     NamespaceController
```

The `PathHandler` for the prefix is left out when `http.prefix` is empty. LDS then answers as with the prefix `/`.

The `GracefulShutdownHandler` is inside the `CORSHandler`, so that its 503 has the CORS headers and a browser can
read the answer. The comment in the constructor says so, and `ApplicationStopTest` checks it. A handler that
must count as a request in flight goes inside the `GracefulShutdownHandler`.

There is no handler for authentication, and the listener is plain HTTP.

### The paths

| Path | Handler | Answer |
|---|---|---|
| `/ping`, `/health/alive` | `ResponseCodeHandler` | 200 with no body, for every method, whenever the process answers. Nothing is checked. |
| `/health/ready` | `ReadinessHandler` | 200 when LDS is `UP` or `DEGRADED`, 503 when it is `DOWN`, with a JSON body, for every method. See [Readiness](#readiness). |
| `/graphql` | `GraphqlHttpHandler` | Only when `graphql.enabled` is true and `graphql.schema` is not empty. See [GraphQL](core-graphql.md). |
| `/graphiql` | Undertow resource handler | Only with `/graphql`. Redirects to `/graphiql/`, which gives `graphiql.html` from the class path. The page loads its scripts from `unpkg.com` and sends its queries to `/graphql` under the same prefix. |
| Every other path | `NamespaceController` | See below. |

These paths are matched exactly, and the case of the letters counts. One `/` at the end is the same path:
`/ping/` gives 200. `/ping/x` is not a health path. It goes to the `NamespaceController`, which answers 400
`Unsupported namespace: "ping"`, and `/PING` is answered in the same way. So is `/graphql` when GraphQL is off.

`NamespaceController.handleRequest` takes the path after the prefix (`exchange.getRelativePath()`) and tests it
in this order. The namespace is `namespace.default`. LDS answers for this one namespace.

1. The path is `/` or empty: 404 with no body.
2. The path is the namespace and the query has `schema` with no value: 200 with a JSON array of the addresses of
   the schemas of every entity type, sorted by name.
3. The path is the namespace and the query has `schema=embed`: 200 with a JSON array of the JSON Schemas of every
   entity type, sorted by name.
4. The path is the namespace or begins with the namespace and `/`: a new `DataController` handles it.
5. The path begins with `/source/`: a new `SourceHandler` handles it.
6. Anything else: 400 with the text `Unsupported namespace: "<first part of the path>"`.

```sh
curl -s -i 'http://localhost:9090/ns?schema'
```

```http
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json; charset=utf-8
Content-Length: 42
Date: Tue, 24 Nov 2026 23:22:31 GMT

["/ns/Company?schema", "/ns/Person?schema"]
```

```sh
curl -s -i http://localhost:9090/bad/Person/1
```

```
HTTP/1.1 400 Bad Request
Connection: keep-alive
Content-Type: text/plain
Content-Length: 28
Date: Tue, 24 Nov 2026 23:22:31 GMT

Unsupported namespace: "bad"
```

`DataController.handleRequest` moves to a worker thread, then reads the time the request asks for (the
[valid time](../glossary.md#valid-time) of a write, the instant of an [as-of read](../glossary.md#as-of-read))
from the query parameter `timestamp`, or `t` when there is no `timestamp`. It takes the last value given. Without
the parameter the time is now. The value is read by `RequestTime.parse`: with `ZonedDateTime.parse`, a second time
after URL decoding when the first parse fails, and moved to UTC. A value that cannot be read is answered 400 with a
text that names `timestamp`, also when the parameter was `t`. Undertow has decoded the query before the controller
reads it, and it reads a `+` as a space. So the `+` of an offset is sent as `%2B`:
`timestamp=2026-11-20T10:00:00+01:00` is answered 400, and `timestamp=2026-11-20T10%3A00%3A00%2B01%3A00` is read.

Then `ResourceContext.createResourceContext` reads the path as it was sent (`rawRelativePath`), and a
`ResourceException` from it is answered 400 with its message. A write (any method but `GET`, `HEAD` and `OPTIONS`)
then takes the lock of its document (`DocumentLocks`), without holding a thread while it waits, and a write without a
`timestamp` gets the time at which it took the lock. The request goes to `ReferenceResourceHandler`,
`ManagedResourceHandler` or `EmbeddedResourceHandler`, tested in that order because a link is also a part of a
document. These handlers and the lock are in [the REST handlers](core-rest.md) and
[the write path](core-write-path.md).

`SourceHandler` answers `GET /source/{source}` with `{"lastSourceId":...}`: the
[`sourceId`](../glossary.md#source-and-source-id) in the last entry of the transaction log of that source, or
null when there is none. Any other method is answered 400 with `Unsupported managed resource method: <method>`,
`HEAD` included. When `txlog.split.sources` is false there is one transaction log, and only the source named in
`txlog.default-source` has an answer other than null (`TxlogRawdataPool.getLastMessage`). That answer is the
`sourceId` of the last write, whatever source the write named. For every other source the transaction log is not
asked. The entry and its `sourceId` are in [the write path](core-write-path.md). With `txlog.rawdata.provider=discard`,
the default, no entry is kept, and every source is answered 409:

```sh
curl -s -i http://localhost:9090/source/default
```

```
HTTP/1.1 409 Conflict
Connection: keep-alive
Content-Type: text/plain
Content-Length: 107
Date: Tue, 24 Nov 2026 23:22:31 GMT

No transaction log is kept (txlog.rawdata.provider=discard), so the last source id of a source is not known
```

### The CORS handler

`CORSHandler` is given the list of origin patterns, the methods and the headers from the configuration. The
patterns are regular expressions, split at commas, and an origin must match one of them as a whole. The preflight
answer has the status 204.

- A request without an `Origin` header is passed on unchanged.
- A request with an `Origin` that matches, and a method other than `OPTIONS`, is passed on with
  `Access-Control-Allow-Origin` set to the origin sent, `Vary: Origin`, and `Access-Control-Expose-Headers` set to
  the list of `http.cors.allow.header` and `LDS-Searchable`, the header that says whether a write is searchable,
  sorted by name. With `http.cors.allow.credentials` it also has `Access-Control-Allow-Credentials: true`.
- An `OPTIONS` request with an `Origin` is a preflight request. It is answered by the handler and not passed on
  when the origin matches, there is one `Access-Control-Request-Method` and it is in `http.cors.allow.methods`,
  and every name in `Access-Control-Request-Headers` is in `http.cors.allow.header` (the case of a header name
  does not matter). The answer has the allowed origin, methods and headers and `Access-Control-Max-Age`, and
  with `http.cors.allow.credentials` also `Access-Control-Allow-Credentials: true`.
- A request that fails one of these tests, or has more than one `Origin` header, is passed on without CORS
  headers. It is not refused. The next handler answers it as it answers any request.

```sh
curl -s -i -X OPTIONS -H 'Origin: https://app.example' -H 'Access-Control-Request-Method: PUT' \
  -H 'Access-Control-Request-Headers: content-type' http://localhost:9090/ns/Person/1
```

```
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Allow-Headers: Content-Type,Authorization
Access-Control-Max-Age: 900
Access-Control-Allow-Methods: PUT,HEAD,POST,DELETE,GET
Date: Tue, 24 Nov 2026 23:22:31 GMT
```

A preflight request for a method that is not allowed is passed on, and the handler of a document answers the
`OPTIONS` request itself:

```sh
curl -s -i -X OPTIONS -H 'Origin: https://app.example' -H 'Access-Control-Request-Method: PATCH' \
  http://localhost:9090/ns/Person/1
```

```
HTTP/1.1 400 Bad Request
Connection: keep-alive
Content-Type: text/plain
Content-Length: 44
Date: Tue, 24 Nov 2026 23:22:31 GMT

Unsupported managed resource method: OPTIONS
```

The methods and headers of a preflight answer are kept in a `Set`, so their order in the answer differs from one
start to the next.

### The prefix and the request dump

With `http.prefix=/lds` every path above is under `/lds`, and a path outside it is answered 404 with no body.
The default is `/`. Seen with `LDS_HTTP_PREFIX=/lds`: `/lds/ping` gives 200 and `/ping` gives 404.

With `http.request.dump=true` the chain has the request dump handler of Undertow (`Handlers.requestDump`). It
writes every request and its answer, headers included, to the log at level INFO under the logger
`io.undertow.request.dump`.

Not finished: two things do not follow the prefix. `SourceHandler` takes the source from the whole path
(`exchange.getRequestPath()`) and cuts the length of `/source/` from it, so with `http.prefix=/lds` it asks for
the source `rce/A` when the address is `/lds/source/A`. With `txlog.split.sources=true` and the transaction log
in memory that address is answered 500. The addresses in the answer to `?schema` are written without the prefix:
`/lds/ns?schema` gives `["/ns/Company?schema","/ns/Person?schema"]`.

## Readiness

`/health/ready` says whether LDS can take writes. `Readiness` asks every part a write needs, side by side, each
within 2 seconds (`CHECK_TIMEOUT`), and `ReadinessHandler` answers with the worst of their states and one entry for
each, in the order of the checks. It holds no thread while it waits for the answers.

| Check | What is asked | When it fails |
|---|---|---|
| `persistence` | A read of one document of the first entity type by name, in a read transaction opened and closed on a thread of `StoreReads`. | `DOWN` |
| `sagaLog` | The ids of the saga logs of the cluster (`SagaLogPool.clusterWideLogIds`), which the database of the saga log is asked for. The entry has `logs`, their number. | `DOWN` |
| `transactionLog` | The last message of the topic of the default source. The entry has `keepsMessages`, false for `discard`. | `DOWN` |
| `search` | Only with search: `SearchIndex.health()`, which the Solr provider answers with a query of no rows on its core. | `DEGRADED` |
| `indexQueue` | Only with search: the number of updates in the index queue and the time the oldest was added (`IndexQueue.lag`). The entry has `durable` (false for the queue in memory), `updates` and, when there are updates, `oldest`. | `DOWN`. It is `DEGRADED` when it answers and the oldest update has waited more than a minute (`INDEX_LAG_DEGRADED`). |

The saga log and the transaction log have clients that block (JDBC), so they are asked on a virtual thread of their
own, which is interrupted when the check is given up. A check that does not answer in 2 seconds has the entry
`"error": "no answer within 2000 ms"`; a check that fails has the simple name of the class of the exception as its
`error`, and its message goes to the log only, as a warning, since it can name hosts and databases. Every answer
that is not `UP` is also logged as a warning: `LDS is DEGRADED: [...]`.

The answer of LDS in memory, as the image starts:

```sh
curl -s http://localhost:9090/health/ready
```

```json
{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 9},
    "sagaLog": {"status": "UP", "millis": 2, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 0, "keepsMessages": false}
  }
}
```

The answer of the whole stack of the [compose file](server.md#the-compose-file), with search:

```json
{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 34},
    "sagaLog": {"status": "UP", "millis": 1, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 1, "keepsMessages": true},
    "search": {"status": "UP", "millis": 3},
    "indexQueue": {"status": "UP", "millis": 0, "durable": true, "updates": 0}
  }
}
```

The answer of LDS in memory with search on and nothing listening at `search.index.url`, with the status 200:

```json
{
  "status": "DEGRADED",
  "checks": {
    "persistence": {"status": "UP", "millis": 8},
    "sagaLog": {"status": "UP", "millis": 0, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 1, "keepsMessages": false},
    "search": {"status": "DEGRADED", "millis": 2, "error": "ConnectException"},
    "indexQueue": {"status": "UP", "millis": 0, "durable": false, "updates": 0}
  }
}
```

`/ping` and `/health/alive` answer 200 whenever the process does, so that a liveness probe does not restart LDS for
a part it needs that does not answer. `Readiness.close`, the stop of the subsystem `Readiness checks`, ends the
threads of checks that still wait.

## Start

`UndertowApplication.start` starts the subsystems in the order they were made (`Lifecycles.start`), and logs
`Starting <name> ...` for each. Most are live once they are made and do nothing more. Four do:

- `Index queue worker` starts the thread of the worker, `lds-index-queue-<n>`, which sends the queue to the index.
- `Index rebuild`, when it was asked for, starts the rebuild on a virtual thread, `lds-index-rebuild`.
- `Sagas`, when there is a recovery trigger, asks the coordinator to complete the unfinished sagas in the saga logs
  of this instance (`SagaExecutionCoordinator.completeLocalIncompleteSagas`, on the recovery thread pool), and starts
  the trigger when that is done, whether it failed or not. The start does not wait for the recovery.
- `HTTP server` starts the Undertow server, which opens the port.

Then `start` logs the process id and the address it listens on.

`Server.main` then calls `enableSagaExecutionAutomaticDeadlockDetectionAndResolution`, which starts the thread
`Saga-Threadpool-Watchdog` of the coordinator. The test server does not call it.

## Stop

`UndertowApplication.stop()` stops the subsystems one at a time, in the reverse order of the one they were made in
(`Lifecycles.stop`), so that a subsystem stops before what it needs does. A saga hands each of its steps to the
thread pool of the sagas, writes each step to its saga log and writes to the stores and the index queue; so the sagas
that are running complete before any of those is stopped. Each subsystem is given the time of the stop,
10 seconds (`STOP_TIMEOUT`), for the work it is doing.

| Order | Subsystem | What its stop does |
|---|---|---|
| 1 | `HTTP server` | `GracefulShutdownHandler.shutdown` makes every request from here on get 503 Service Unavailable, and the stop waits for the requests that are being handled, 10 seconds at most; after that a warning is logged. Then the Undertow server stops, closing a connection that is still open one second later (`SHUTDOWN_TIMEOUT`), so a client that sends its body slowly does not hold the stop. |
| 2 | `Readiness checks` | The threads of checks that still wait are ended. |
| 3 | `Sagas` | The recovery stops: `SagaRecoveryTrigger.stop`, which also shuts the recovery thread pool down, or without a trigger the recovery thread pool alone, because a write also uses it for recovery. Then the stop waits for the sagas that are running, 10 seconds at most, and logs a warning after that. Then the coordinator and its watchdog stop (`SagaExecutionCoordinator.shutdown`). |
| 4 | `Saga thread pool` | Shut down: 10 seconds, then `shutdownNow` and 10 seconds more. |
| 5 | `Saga observer` | Shut down. |
| 6 | `Transaction log` | Its client is closed. |
| 7 | `Index rebuild` | Only when it was asked for. A rebuild that is not done is given up, with a warning, and the stop waits for its read transaction to be closed. |
| 8 | `Index queue worker` | Stops after the round it is sending, 10 seconds at most; the updates it has not removed stay in the queue. |
| 9 | `Index queue` | Closed. |
| 10 | `Search index` | Closed: the Solr provider closes its client. |
| 11 | `Saga log pool` | Shut down. After that it gives out no log. |
| 12 | `Store reads` | No read starts after this; the reads that run are given 10 seconds, and are then interrupted. |
| 13 | `Store` | `RxJsonPersistence.close`. |

The subsystems that were not made (search without a search index, the rebuild when it was not asked for) are not in
the list. `Server` stops RxJava's shared schedulers after the application (see [the server](server.md)). A saga that
is still running when its thread pool is shut down fails if it hands a step to the pool. Its saga log holds what it
did, and the saga is recovered from it later.

`stop(timeout)` is the same with another time. Three more things hold:

- A subsystem that fails to stop is logged at level ERROR (`<name> did not stop cleanly`), and the subsystems after
  it are stopped all the same.
- The application is stopped once. A second call returns at once and does not wait for the first. `Server.main`
  calls `stop` from its shutdown hook and from a `finally` block, so this matters.
- A stop on a thread that is interrupted does not wait, and still stops every subsystem.

This is what a client sees. A `PUT` was sent with half of its body, the process was then sent `SIGTERM`, and
half a second later these requests were sent:

```sh
curl -s -i http://localhost:9090/ping
```

```
HTTP/1.1 503 Service Unavailable
Connection: keep-alive
Content-Length: 0
Date: Tue, 24 Nov 2026 23:22:41 GMT
```

```sh
curl -s -i -H "Origin: https://app.example" http://localhost:9090/ns/Company/acme
```

```
HTTP/1.1 503 Service Unavailable
Connection: keep-alive
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Expose-Headers: Authorization,Content-Type,LDS-Searchable
Content-Length: 0
Date: Tue, 24 Nov 2026 23:22:41 GMT
```

The rest of the body was sent two seconds after the `SIGTERM`. The `PUT` was answered `HTTP/1.1 201 Created`,
and then the application went on with its stop:

```
... 23:22:52.702Z [Thread-0] WARN  io.descoped.lds.server.Server - ShutdownHook triggered..
... 23:22:54.700Z [Thread-0] INFO  io.undertow - stopping server: Undertow - 2.4.4.Final
... 23:22:54.703Z [Thread-0] INFO  io.descoped.lds.core.UndertowApplication - Leaving.. Bye!
```

The process ends with exit code 143 after `SIGTERM`. `/health/alive` and `/health/ready` are answered 503 during
the stop of the HTTP server as `/ping` is. A preflight request is answered 204 then as well, because the
`CORSHandler` answers it before the `GracefulShutdownHandler` sees it.

## Configuration

Core reads its configuration from a `DynamicConfiguration` (`io.descoped.config:dynamic-configuration`). The
caller builds it. `UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH` is the name of the file with the
defaults of core, `application-defaults.properties`, which `Server` and the tests read first. `Server` reads the
defaults of the server over them, and those differ for some keys: see [the server](server.md).

An environment variable or a system property sets only a key that one of the files has. So a key that core reads gets a
default in `application-defaults.properties` when it is added. The rule holds for the keys under `sagalog.config.` and
`txlog.config.` too: with the defaults of core alone, `LDS_SAGALOG_CONFIG_X=1` sets nothing, and `Server` refuses to
start with it (see [the server](server.md#the-rules-of-dynamic-configuration-3)).

| Key | Default in core | Meaning |
|---|---|---|
| `http.host` | `0.0.0.0` | The address the HTTP listener binds to. |
| `http.port` | `9090` | The port of the HTTP listener. |
| `http.prefix` | `/` | The path every address of LDS is under. |
| `http.request.dump` | `false` | True writes every request and its answer to the log. |
| `http.cors.allow.origin` | `.*` | Regular expressions for the origins that are allowed, separated by commas. |
| `http.cors.allow.header` | `Content-Type,Authorization` | The request headers a preflight request may ask for, separated by commas. Also sent, with `LDS-Searchable`, as `Access-Control-Expose-Headers`. |
| `http.cors.allow.methods` | `POST,GET,PUT,DELETE,HEAD` | The methods a preflight request may ask for, separated by commas. |
| `http.cors.allow.credentials` | `false` | True adds `Access-Control-Allow-Credentials: true`. |
| `http.cors.allow.max-age` | `900` | The value of `Access-Control-Max-Age`, in seconds. |
| `namespace.default` | `ns` | The namespace LDS answers for. Also given to the persistence provider and to the GraphQL schema builder. At most 64 bytes in UTF-8. |
| `graphql.schema` | empty | The GraphQL schema file that is the model. Empty: the model is read from `specification.schema`. |
| `specification.schema` | empty | JSON Schema files and folders, separated by commas. Read only when `graphql.schema` is empty. |
| `graphql.enabled` | `true` | True adds `/graphql` and `/graphiql`, when `graphql.schema` is not empty. |
| `graphql.search.enabled` | `false` | True makes LDS find a search provider and make the index queue. A write then also adds an update to the queue. |
| `search.index.provider` | empty | The id of the search provider. Read only when `graphql.search.enabled` is true. |
| `search.index.rebuild` | `false` | True rebuilds the search index from storage at start. See [search](search.md). |
| `search.index.commit-within` | `1000` | Within how many milliseconds Solr makes a write searchable. Read by the Solr provider. |
| `persistence.provider` | empty | The id of the persistence provider. It has no default: a start without it ends with `persistence.provider is not set: name the persistence provider, such as mem, postgres or neo4j`. |
| `persistence.initialization.max-wait-seconds` | `0` | The longest time to try to initialize the persistence provider. |
| `sagalog.provider` | `no.cantara.sagalog.memory.MemorySagaLogInitializer` | The class name of the saga log initializer. |
| `sagalog.provider.initialization.max-wait-seconds` | `0` | The longest time to try to initialize the saga log. |
| `sagalog.provider.initialization.retry-interval-seconds` | `1` | The time between two attempts, give or take half. |
| `sagalog.config.*` | `cluster.owner=lds`, `cluster.name=lds-test`, `cluster.instance-id=01` | Given to the saga log initializer without the prefix. The owner is the schema of the tables of the saga log in PostgreSQL, so letters, digits, `_` and `-`. |
| `saga.number-of-logs` | `50` | The number of saga logs the coordinator registers. |
| `saga.threadpool.core`, `saga.threadpool.max`, `saga.threadpool.keepalive.seconds`, `saga.threadpool.queue.capacity` | `100`, `200`, `60`, `20` | The thread pool of the sagas. The comments in `application-defaults.properties` say how to size it. |
| `saga.recovery.enabled` | `true` | True makes LDS recover unfinished sagas at start and then at intervals. |
| `saga.recovery.interval.seconds.min`, `saga.recovery.interval.seconds.max` | `30`, `60` | The time between two recovery passes is a random number of seconds between these. |
| `saga.commands.enabled` | `false` | True passes the commands in the `saga` query parameter of a write to the saga. |
| `txlog.rawdata.provider` | `discard` | The name of the transaction log provider. It also chooses the index queue. |
| `txlog.rawdata.provider.initialization.max-wait-seconds` | `0` | The longest time to try to initialize the transaction log. |
| `txlog.rawdata.provider.initialization.retry-interval-seconds` | `1` | The time between two attempts, give or take half. |
| `txlog.config.*` | none | Given to the transaction log provider without the prefix. |
| `txlog.split.sources` | `false` | True gives every source a transaction log of its own. |
| `txlog.default-source` | `default` | The source of a write that names none. The only source when `txlog.split.sources` is false. |
| `txlog.rawdata.topic-prefix` | `txlog-` | Put before the source to make the name of a topic of the transaction log. |

The keys of the saga and the transaction log are explained in [the write path](core-write-path.md), and those of
search in [search](search.md).

The code has a default of its own for some keys, used when no file has the key: `http.request.dump`,
`graphql.enabled`, `graphql.search.enabled`, `search.index.rebuild`, `http.cors.allow.credentials`,
`txlog.split.sources`, `saga.commands.enabled` and `saga.recovery.enabled` are then false, `txlog.default-source`
is `default`, `txlog.rawdata.topic-prefix` is empty and `txlog.rawdata.provider` is `discard` for the index queue. The
numbers (`http.port`, `http.cors.allow.max-age`, the thread pool, the number of saga logs, the waits and intervals)
and `specification.schema` are read with `getInt` and `get`, which throw `Missing configuration key` when no file has
the key. The other texts are read with `find(key).orElse(null)` and are not checked for null. The three lists of
`http.cors.allow.*` are split at once, so a configuration without them ends in a `NullPointerException`. A
configuration without `http.prefix` gets no prefix.

The lists of `http.cors.allow.*` are split at commas and not trimmed, so a space after a comma becomes part of
the next value.

## The module descriptor

The module is `io.descoped.lds.core` (`src/main/java/module-info.java`).

- It `requires` the two LDS interfaces (`io.descoped.lds.persistence.api`, `io.descoped.lds.search.api`), the
  configuration (`io.descoped.dynamic.config`), the saga library (`no.cantara.saga.api`,
  `no.cantara.saga.execution`, `no.cantara.sagalog`, `no.cantara.concurrent.futureselector`), the transaction log
  (`io.descoped.rawdata.api`), Undertow (`undertow.core`, `xnio.api`), GraphQL (`com.graphqljava`,
  `com.graphqljava.extendedscalars`), RxJava (`io.reactivex.rxjava3`), Jackson (`tools.jackson.core`,
  `tools.jackson.databind`, `jackson.dataformat.msgpack`), the validator (`com.networknt.schema`), Tika
  (`org.apache.tika.core`), `org.slf4j`, and from the JDK `java.net.http`, `java.xml` and `jdk.unsupported`.
- It `uses` the four service interfaces it loads: `PersistenceInitializer`, `SearchIndexProvider`,
  `IndexQueueProvider` and `SagaLogInitializer`.
- It `provides` `SearchIndexProvider` with `TestSearchIndex`.
- It `exports` `io.descoped.lds.core`, which holds `UndertowApplication`, `io.descoped.lds.core.lifecycle`, which
  `Server` uses, and `io.descoped.lds.test.server`.
- It `opens` `io.descoped.lds.graphql.graphiql`, the package of the GraphiQL page.

LDS runs on the class path, so the descriptor is checked when the module is compiled and is not used at run time.

Not finished: the descriptor has parts that wait for the revision of core. `java.xml` is required with the
comment that it belongs in test scope. `io.descoped.lds.test.server` is exported for one empty interface,
`Dummy`, with the comment that it is needed to run tests in an IDE. No `requires` is `transitive`, though the public
methods of `UndertowApplication` return types of other modules and of packages that are not exported. The
compiler does not report this, because the lint categories `exports` and `requires-automatic` are among those
the POM of core turns off.

## Tests

The tests named here start the application, or a part of it, in the JVM with the memory provider, and do not need
Docker. Other tests of core do: see [the write path](core-write-path.md).

`TestServer` (`src/test/java/io/descoped/lds/test/server`) calls
`UndertowApplication.initializeUndertowApplication(configuration, port)` in its constructor and gives the
address of a path with `testURL`. The host of that address is `http.host`, which is `0.0.0.0` in the tests.

`TestServerListener` is a TestNG listener. A test class names it with `@Listeners(TestServerListener.class)` and
declares a field of type `TestServer` or `TestClient` with `@Inject`. Before every test method the listener:

1. builds a configuration from `application-defaults.properties`, then `application_test.properties`, then the
   pairs of key and value in the `@ConfigurationOverride` annotation of the method, if it has one;
2. stops the server of the test before, when that one had another configuration;
3. starts a server for the configuration if none is running, on a free port between 9000 and 9499
   (`findFreePort`), and puts it, or a `TestClient` for it, into the fields.

So tests that follow each other with the same configuration share one server and its data. A test whose
configuration differs from that of the test before gets a new server. The listener does not stop the last
server. It ends with the JVM of the tests.

`application_test.properties` sets the provider `mem`, the namespace `data`, the model
`gqlschema/schema.graphql`, search on with the provider `testSearchIndex` (so the index queue is in memory, as the
transaction log is `discard`), a saga log in files under `./target/test-sagalog`, no recovery, and
`graphql.enabled=false`.

A test class that needs to stop its server starts one itself, as `ApplicationStopTest` does: it builds the
configuration, makes a `TestServer` on a free port between 9500 and 9999 and calls `getApplication().start()`.
`ApplicationStopTest` is in the package `io.descoped.lds.core.saga`, because it reads fields of
`SagaExecutionCoordinator` that are not public.

| Test class | What it holds this part to |
|---|---|
| `ApplicationStopTest` (JUnit) | The stop. The subsystems stop in the reverse order of the one they were made in, `HTTP server` first and `Store` last. The worker of the index queue runs from the start and its thread has ended after the stop; so have the threads the store is read on, and a read asked for after that fails. A saga that is running completes before the application stops. The stop waits for a saga for as long as it is told. A saga in the middle of its steps completes. A saga that outlasts the wait fails and leaves its log for recovery. A request that arrives while the application stops is answered 503, with the CORS headers, while the request in flight is answered 201. A request that is not completed in time does not hold the stop up. An interrupted stop does not wait. A part that fails to stop does not keep the rest from stopping. After the stop the saga log pool gives out no log. Its saga log is `SlowTruncateSagaLogInitializer`, whose writes take time, so that a saga is still running when the server is stopped. |
| `LifecyclesTest` (JUnit) | Subsystems start in the order they were added and stop in the reverse order, once. One that cannot start stops every subsystem, also those not started. One that fails to stop does not keep the others running. None is added while they stop. Each is given the time of the stop. |
| `ReadinessTest`, `ReadinessHandlerTest` (JUnit) | The state is the worst of the checks, and the entries keep the order of the checks. A part that writes need is `DOWN` with its error, search is `DEGRADED`. Checks that do not answer are given up side by side after the time limit, and the thread of a check that is given up is interrupted. The index queue is `DEGRADED` when its oldest update has waited over a minute and `DOWN` when it does not answer. Over HTTP: 200 for `UP` and `DEGRADED`, 503 for `DOWN`, with an entry for each check. |
| `ModelAtStartTest` (JUnit) | A model with no entity type, or no model, stops the start at once with a text that names it. A namespace or an entity type over its limit stops the start. |
| `SagaLogPoolAtStartTest` (JUnit) | A pool with room for the logs, the recovery and its own queries is taken; one connection short is refused with the keys to change; the default of the provider is checked when the key is not set; a saga log without a pool is not checked. |
| `CORSHandlerTest` (TestNG), `CORSHandlerRulesTest` (JUnit) | The CORS headers of a request and of a preflight request, on an Undertow server of its own. A request the rules do not allow goes on without them. |
| `NamespaceControllerTest` (TestNG) | 404 for `/`, and 400 `Unsupported namespace` for another namespace, also one that only begins with the name of the namespace. |
| `NamespaceSchemaTest` (TestNG) | `?schema` and `?schema=embed` on the namespace give a JSON array with more than one element. |
| `DataControllerTest` (TestNG) | 400 `Not a managed resource name: "bull"` for a path with an entity type the model does not have. Its other five tests are disabled. |
| `SourceTest` (TestNG) | `/source/{source}` with the transaction log in memory, split by source and not split, and 409 with `discard`. |
| `PersistenceConfiguratorTest`, `SearchIndexConfiguratorTest` (TestNG) | A provider is found by its id. A key that is missing and a provider that is not there fail. |
| `JitterTest`, `RequestTimeTest` (JUnit) | The wait between attempts, and the reading of the time a request names. |
| `ServerTest` (TestNG) | The test server has a running HTTP listener. |

One test class of core is run with `./mvnw test -pl linked-data-store-core -Dtest=<Class>`, with the name of a
class above. A new test class that starts a server of its own is JUnit 6 with AssertJ. A test that takes its
server from `TestServerListener` is TestNG until core is revised.

`DevServer` (`src/test/java/io/descoped/lds/server`) is a main class for starting core from an IDE with the
memory provider. It does not start as it stands: see [Not finished](#not-finished).

## Rules to keep

- **Every subsystem is a `Lifecycle`**, added to the application's `Lifecycles` as it is made, after what it needs.
  They start in that order and stop in the reverse order, also when the start fails part of the way. A new pool of
  threads, client or worker is added there, never kept as a static or global pool. `ApplicationStopTest` holds the
  order of the stop.
- **A new handler goes inside the `GracefulShutdownHandler`**, or its requests are not counted and not completed
  before the stop goes on. The `CORSHandler` stays outside it.
- **No thread waits.** A handler that calls a store moves off the I/O thread first (`exchange.isInIoThread()`, then
  `exchange.dispatch(this)`), as `DataController` and `SourceHandler` do. A read of the store that is composed runs
  on a thread of `StoreReads`, which opens and closes its transaction there. A client that only blocks is asked on a
  thread of its own, as the readiness asks the saga log and the transaction log.
- **A provider is registered in `META-INF/services` and with `provides`.** LDS runs on the class path, where only
  the file in `META-INF/services` is read.
- **The process is configured from outside by environment variables**, which set only keys that a file has. A new
  key gets a default in `application-defaults.properties`.
- **`Server` stops the application when the process is asked to end.** The image relies on it. Do not add a thread
  or a hook that keeps `stop` from being reached.
- **No name from a request goes into a key, a topic or a path without being checked.** See the source name below.
- **The lint and Javadoc overrides in the POM of core are removed, not widened**, when core is revised.
- **3.0.0 is a clean break.** No alias for a key that is renamed, and no code for an earlier configuration.

## Not finished

Not finished: this part of core is not revised for 3.0 yet. What is known to be incomplete or wrong is listed
here and where it belongs above.

- `SourceHandler` and the answer to `?schema` do not follow `http.prefix`. See [The prefix and the request
  dump](#the-prefix-and-the-request-dump).
- `SourceHandler` puts the source of the address into the name of a topic without checking it. With
  `txlog.split.sources=true` and the transaction log in memory, `GET /source/a/b` is answered 500 with no body; the
  client of the transaction log refuses the name.
- `parseSchemaFile` opens what it finds on the class path as a file (`new File(systemResource.getPath())`). A
  `graphql.schema` that names a resource inside a jar ends the start with `java.nio.file.NoSuchFileException`
  for a path of the form `file:/.../lib/<jar>!/<resource>`.
- `SearchIndexConfigurator` does not read `SearchIndexProvider.configurationKeys()`, so core does not tell at
  start that a key of the search provider is missing, as it does for the persistence provider.
- LDS answers for one namespace, `namespace.default`. Any other first part of a path is answered 400.
- `TestSearchIndex` is a search provider for tests that is in the main code. The module descriptor provides it,
  but its file in `META-INF/services` is among the test resources. A running LDS does not find it:
  `LDS_GRAPHQL_SEARCH_ENABLED=true LDS_SEARCH_INDEX_PROVIDER=testSearchIndex` ends the start with
  `No search index provider found for providerId: testSearchIndex`.
- The attempts at start do not log why an attempt failed, and try a wrong configuration of the transaction log
  again as if the store were not up.
- The comment on `search.index.provider` in `application-defaults.properties` names a class. The key takes the
  id of the provider, such as `solr`.
- `TestServerListener.findFreePort` looks for a free port first and the server binds it afterwards, so another
  process can take the port in between. `CORSHandlerTest` lets the system pick the port and says why in a
  comment.
- `DevServer` names the model files `spec/demo/contact.json` and `spec/demo/provisionagreement.json`. The test
  resources have no such files; the two schemas are in `spec/schemas`. Started with the test resources on the
  class path, `DevServer` ends with `java.lang.IllegalArgumentException: Unable to find resource:
  spec/demo/contact.json`.
- Six tests of the package `controller` are disabled in the source (`enabled = false`): five in
  `DataControllerTest` and `NamespaceControllerTest.thatPUTOnLegalNamespaceAndDocumentAcceptsWith200`.
