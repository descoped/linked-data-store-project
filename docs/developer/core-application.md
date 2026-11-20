# Core: how the application is put together, started and stopped

This document covers the part of `linked-data-store-core` that builds the LDS application from its configuration,
starts it, routes an HTTP request to its handler, and stops the application in order. It is for developers and
maintainers who change this code. What the handlers do with a document is in
[the REST handlers](core-rest.md), [GraphQL](core-graphql.md) and
[the write path](core-write-path.md). The main class and the files of the configuration are in
[the server](server.md).

## What this part is for

`UndertowApplication` is the whole of LDS as one object. Given a configuration it reads the
[model](../glossary.md#model), finds the providers, builds the [sagas](../glossary.md#saga) and the HTTP handlers,
and holds them until it is stopped. It has no `main` method:
`io.descoped.lds.server.Server` in `linked-data-store-server` builds the configuration and calls it, and the tests
of core call it in the same way.

## Layout

All of it is in `linked-data-store-core`, under `src/main/java/io/descoped/lds/core`.

| Class | Responsibility |
|---|---|
| `UndertowApplication` | `initializeUndertowApplication` builds every part in a fixed order. The constructor builds the HTTP handler chain and the Undertow server. `start` and `stop` start and stop the parts. It also finds the saga log (`configureSagaLogProvider`) and the transaction log (`configureTxLogRawdataClient`). |
| `persistence.PersistenceConfigurator` | Finds the persistence provider named in `persistence.provider`, checks that its keys are configured, and initializes it, trying again for a time. |
| `search.SearchIndexConfigurator` | Finds the search provider named in `search.index.provider` when `graphql.search.enabled` is true. Otherwise it returns null, and LDS runs without a search index. |
| `controller.CORSHandler` | The first handler of every request. Answers a CORS preflight request and adds the CORS headers to other requests. |
| `controller.HealthCheckHandler` | Answers `/health/alive` by opening and cancelling a read transaction in the persistence provider. It also holds the three health paths as constants. |
| `controller.NamespaceController` | Answers every path that is not a health path or a GraphQL path. It sends a path of the namespace to `DataController` and a path under `/source/` to `SourceHandler`. It answers the list of schemas itself. |
| `controller.DataController` | Reads the time asked for, builds the `ResourceContext` of the path and hands the request to the handler of a link, a document or a part of a document. |
| `controller.SourceHandler` | Answers `GET /source/{source}` with the last `sourceId` the transaction log holds for that source. |
| `persistence.PersistenceCreateOrOverwriteSagaAdapter`, `persistence.PersistenceDeleteSagaAdapter`, `search.UpdateIndexSagaAdapter`, `search.DeleteIndexSagaAdapter` | The steps of a saga that write to the persistence provider and to the search index. `SagaRepository` registers them. They are described in [the write path](core-write-path.md). |
| `search.TestSearchIndex`, `search.SimpleSearchIndex` | A search provider in memory with the id `testSearchIndex`, used by the tests of core. See [Not finished](#not-finished). |
| `utils.FileAndClasspathReaderUtils` | Reads a text from a file, or from the class path when there is no such file. `JsonSchemaBasedSpecification` reads the JSON Schema files with it, and tests read their example documents with it. |

The resources are `application-defaults.properties`, the defaults of every key core reads, and
`io/descoped/lds/graphql/graphiql/graphiql.html`, the page served at `/graphiql`.

## How the application is built

`UndertowApplication.initializeUndertowApplication(configuration)` reads `http.port` and calls
`initializeUndertowApplication(configuration, port)`. The tests call the second form with a port of their own.
The parts are built in this order, on the calling thread. Each later part is given the earlier ones.

1. **The model.** When `graphql.schema` is not empty, the GraphQL schema file is parsed (`parseSchemaFile`),
   `GraphQLToJsonConverter` writes a JSON Schema for every [entity type](../glossary.md#entity-type), and
   `createJsonSpecification` builds the `JsonSchemaBasedSpecification` from them. When it is empty, the
   [specification](../glossary.md#specification) is built from the JSON Schema files and folders named in
   `specification.schema`, separated by commas (`JsonSchemaBasedSpecification.create`). `parseSchemaFile` looks
   for the path on the class path first and then
   as a file. What it finds on the class path it opens as a file, so a schema inside a jar is not read: see
   [Not finished](#not-finished). See [the model](core-model.md).
2. **The persistence provider.** `PersistenceConfigurator.configurePersistence` gives an `RxJsonPersistence`.
3. **The [saga log](../glossary.md#saga-log) pool.** `configureSagaLogProvider` gives a `SagaLogPool`.
4. **The search index.** `SearchIndexConfigurator.configureSearchIndex` gives a `SearchIndex` or null.
5. **The [transaction log](../glossary.md#transaction-log).** `configureTxLogRawdataClient` gives a
   `RawdataClient`. It is wrapped in a
   `TxlogRawdataPool` with `txlog.split.sources`, `txlog.default-source` and `txlog.rawdata.topic-prefix`.
6. **The saga repository.** `SagaRepository.Builder.build` registers the adapters and the two sagas, one for a write
   and one for a delete. Each saga has a step for the search index only when there is a search index.
7. **The saga observer.** `new SagasObserver(sagaRepository).start()` starts the thread `saga-observer`. It is the
   first thread that `UndertowApplication` itself starts. See [When the start fails](#when-the-start-fails).
8. **The thread pool of the sagas.** A `SelectableThreadPoolExecutor` with `saga.threadpool.core`,
   `saga.threadpool.max`, `saga.threadpool.keepalive.seconds` and a queue of `saga.threadpool.queue.capacity`. Its
   threads are named `sec-1`, `sec-2`, and so on. A task that does not fit is refused (`AbortPolicy`). A warning is
   logged when the queue capacity is not less than the core size.
9. **The recovery thread pool.** A scheduled pool of 10 threads named `saga-recovery-1` and so on.
10. **The saga execution coordinator.** `SagaExecutionCoordinator` registers `saga.number-of-logs` saga logs in the
    pool.
11. **The recovery trigger.** A `SagaRecoveryTrigger` when `saga.recovery.enabled` is true, with
    `saga.recovery.interval.seconds.min` and `saga.recovery.interval.seconds.max`. Otherwise there is none.
12. **The namespace controller.** `NamespaceController` with `namespace.default`.
13. **The HTTP handlers and the Undertow server.** The constructor of `UndertowApplication` builds the chain described
    in [The HTTP handler chain](#the-http-handler-chain) and an Undertow server with one HTTP listener on `http.host`
    and the port. The server is not started yet.

The log of a start shows the order. This is the log of LDS started in memory with the example model, each line
shown from its message on. The commands of this document were run in a folder with every jar of LDS in `lib` and
the example model in `schemas/schema.graphql`. In the repository the class path is `target/*:target/lib/*` under
`linked-data-store-server`: see [the server](server.md).

```sh
LDS_HTTP_PORT=19107 java -cp "lib/*" io.descoped.lds.server.Server
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
Starting Undertow ...
starting server: Undertow - 2.4.4.Final
...
Starting saga-recovery (instance-local) ...
Starting saga-recovery-trigger ...
Started Linked Data Store server. PID 24637
Listening on 0.0.0.0:19107
Server started in 368ms..
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
| Transaction log | Found by `RawdataClient.create` of `io.descoped.rawdata:rawdata-client-api`. | `txlog.rawdata.provider` is the name of the provider. | Every key that starts with `txlog.config.`, without that prefix. |

The persistence providers and their ids are in [the persistence interface](persistence-api.md). The search
provider is in [search](search.md).

### Trying again at start

A store may not be up when LDS starts. Three of the four parts are therefore initialized in a loop that tries
again until a time has passed. A wait of 0 means one attempt.

| Part | Longest wait | Time between attempts | What is tried again |
|---|---|---|---|
| Persistence | `persistence.initialization.max-wait-seconds` | One second. There is no key for it. | A `RuntimeException` from `PersistenceInitializer.initialize` |
| Saga log | `sagalog.provider.initialization.max-wait-seconds` | `sagalog.provider.initialization.retry-interval-seconds` | Any exception from `SagaLogInitializer.initialize` |
| Transaction log | `txlog.rawdata.provider.initialization.max-wait-seconds` | `txlog.rawdata.provider.initialization.retry-interval-seconds` | Any exception from `RawdataClient.create` |

The search provider is not tried again.

Each attempt writes one line to the log, `... initialization attempt # n`. The exception of an attempt that fails
is not logged. When the wait is over, the exception of the last attempt is thrown and the start fails. This is
LDS started with the PostgreSQL provider and nothing listening on the port, with a wait of 3 seconds:

```sh
LDS_HTTP_PORT=19107 LDS_PERSISTENCE_PROVIDER=postgres LDS_POSTGRES_DRIVER_PORT=1 \
  LDS_POSTGRES_DRIVER_PASSWORD=x LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=3 \
  java -cp "lib/*" io.descoped.lds.server.Server
```

```
... 16:31:13.742+01:00 [main] INFO  ... - Persistence provider initialization attempt # 1
... 16:31:15.785+01:00 [main] INFO  ... - Persistence provider initialization attempt # 2
Exception in thread "main" com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize ...
...
Caused by: org.postgresql.util.PSQLException: Connection to localhost:1 refused. Check that the hostname and ...
...
Caused by: java.net.ConnectException: Connection refused (connect failed)
```

The process ended with exit code 1.

The loop does not tell a store that is not up from a configuration that is wrong. A persistence provider or a
saga log that is not found fails at once, before the loop. A transaction log provider that is not found, and a
configuration that the provider refuses, are tried again until the wait is over:

```sh
LDS_HTTP_PORT=19107 LDS_TXLOG_RAWDATA_PROVIDER=nosuch \
  LDS_TXLOG_RAWDATA_PROVIDER_INITIALIZATION_MAX_WAIT_SECONDS=3 java -cp "lib/*" io.descoped.lds.server.Server
```

```
... 16:31:10.218+01:00 [main] INFO  ... - Transaction-log provider initialization attempt # 1
... 16:31:12.224+01:00 [main] INFO  ... - Transaction-log provider initialization attempt # 2
... 16:31:13.223+01:00 [main] INFO  ... - Transaction-log provider initialization attempt # 3
Exception in thread "main" ...ProviderException: No provider named 'nosuch'; available: [discard, memory, postgres]
```

### When the start fails

`initializeUndertowApplication` throws what went wrong, and `Server.main` lets it end the main thread. Nothing
that was built before the failure is stopped or closed.

What then happens to the process depends on where the start failed:

- Before the saga observer is started (steps 1 to 6), the process ends with exit code 1. Seen with
  `LDS_PERSISTENCE_PROVIDER=nosuch` (`No persistence providers found for providerId: nosuch`),
  `LDS_SAGALOG_PROVIDER=nosuch` (`java.util.NoSuchElementException: No value present`),
  `LDS_TXLOG_SPLIT_SOURCES=yes` and `LDS_GRAPHQL_SCHEMA=/no/such/file.graphql`
  (`java.nio.file.NoSuchFileException`), and in the two examples above.
- After the saga observer is started (steps 8 to 13), the thread `saga-observer` is left. It is not a daemon
  thread, so the process does not end. The HTTP server was never started.

Not finished: a start that fails after the saga observer is started leaves the process running without an HTTP
server. Seen with `LDS_HTTP_REQUEST_DUMP=yes`, with `LDS_HTTP_CORS_ALLOW_ORIGIN=[` and with
`LDS_SAGA_RECOVERY_INTERVAL_SECONDS_MIN=0`: the main thread ended with the exception, and after 12 seconds the
process was still there with `saga-observer` as its only thread of LDS. A number that is not a number in
`LDS_SAGA_THREADPOOL_CORE` or `LDS_SAGA_NUMBER_OF_LOGS` does the same.

A failure in `start`, such as a port that is taken, is different: `Server.main` calls `stop` in a `finally`
block, the application stops in order, and the process ends with exit code 1
(`java.net.BindException: Address already in use`).

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
            /health/alive   exact      HealthCheckHandler
            /health/ready   exact      200
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
| `/ping`, `/health/ready` | `ResponseCodeHandler` | 200 with no body, for every method. Nothing is checked. |
| `/health/alive` | `HealthCheckHandler` | 200 when a read transaction can be opened in the persistence provider and cancelled, 503 when that throws. No body, every method. The handler moves to a worker thread first, because it calls the persistence provider. |
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
curl -s -i 'http://localhost:19107/ns?schema'
```

```http
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json; charset=utf-8
Content-Length: 42
Date: Fri, 20 Nov 2026 15:29:14 GMT

["/ns/Company?schema", "/ns/Person?schema"]
```

```sh
curl -s -i http://localhost:19107/bad/Person/1
```

```
HTTP/1.1 400 Bad Request
Connection: keep-alive
Content-Type: text/plain
Content-Length: 28
Date: Fri, 20 Nov 2026 15:29:14 GMT

Unsupported namespace: "bad"
```

`DataController.handleRequest` moves to a worker thread, then reads the time the request asks for (the
[valid time](../glossary.md#valid-time) of a write, the instant of an [as-of read](../glossary.md#as-of-read))
from the query parameter `timestamp`, or `t` when there is no `timestamp`. It takes the last value given. Without
the parameter the time is now. The value is parsed with `ZonedDateTime.parse`, a second time after URL decoding
when the first parse fails, and is moved to UTC. A value that cannot be parsed is answered 400 with a text that
names `timestamp`, also when the parameter was `t`. Undertow has decoded the query before the controller reads
it, and it reads a `+` as a space. So the `+` of an offset is sent as `%2B`: `timestamp=2026-11-20T10:00:00+01:00`
is answered 400, and `timestamp=2026-11-20T10%3A00%3A00%2B01%3A00` is read.

Then `ResourceContext.createResourceContext` reads the path, and a `ResourceException` from it is answered 400
with its message. The request goes to `ReferenceResourceHandler`, `ManagedResourceHandler` or
`EmbeddedResourceHandler`, tested in that order because a link is also a part of a document. These handlers are
in [the REST handlers](core-rest.md).

`SourceHandler` answers `GET /source/{source}` with `{"lastSourceId":...}`: the
[`sourceId`](../glossary.md#source-and-source-id) in the last entry of the transaction log of that source, or
null when there is none. Any other method is answered 400 with `Unsupported managed resource method: <method>`,
`HEAD` included. When `txlog.split.sources` is false there is
one transaction log, and only the source named in `txlog.default-source` has an answer other than null
(`TxlogRawdataPool.getLastMessage`). That answer is the `sourceId` of the last write, whatever source the write
named. For every other source the transaction log is not asked. The entry and its `sourceId` are in
[the write path](core-write-path.md).

### The CORS handler

`CORSHandler` is given the list of origin patterns, the methods and the headers from the configuration. The
patterns are regular expressions, split at commas, and an origin must match one of them as a whole. The preflight
answer has the status 204.

- A request without an `Origin` header is passed on unchanged.
- A request with an `Origin` that matches, and a method other than `OPTIONS`, is passed on with
  `Access-Control-Allow-Origin` set to the origin sent, `Vary: Origin`, and `Access-Control-Expose-Headers` set to
  the list of `http.cors.allow.header`. With `http.cors.allow.credentials` it also has
  `Access-Control-Allow-Credentials: true`.
- An `OPTIONS` request with an `Origin` is a preflight request. It is answered by the handler and not passed on
  when the origin matches, there is one `Access-Control-Request-Method` and it is in `http.cors.allow.methods`,
  and every name in `Access-Control-Request-Headers` is in `http.cors.allow.header` (the case of a header name
  does not matter). The answer has the allowed origin, methods and headers and `Access-Control-Max-Age`, and
  with `http.cors.allow.credentials` also `Access-Control-Allow-Credentials: true`.
- A request that fails one of these tests, or has more than one `Origin` header, is passed on without CORS
  headers. It is not refused. The next handler answers it as it answers any request.

```sh
curl -s -i -X OPTIONS -H 'Origin: https://app.example' -H 'Access-Control-Request-Method: PUT' \
  -H 'Access-Control-Request-Headers: content-type' http://localhost:19107/ns/Person/1
```

```http
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Allow-Headers: Authorization,Content-Type
Access-Control-Max-Age: 900
Access-Control-Allow-Methods: DELETE,POST,HEAD,PUT,GET
Date: Fri, 20 Nov 2026 15:29:18 GMT
```

A preflight request for a method that is not allowed is passed on, and the handler of a document answers the
`OPTIONS` request itself:

```sh
curl -s -i -X OPTIONS -H 'Origin: https://app.example' -H 'Access-Control-Request-Method: PATCH' \
  http://localhost:19107/ns/Person/1
```

```
HTTP/1.1 400 Bad Request
Connection: keep-alive
Content-Type: text/plain
Content-Length: 44
Date: Fri, 20 Nov 2026 15:29:18 GMT

Unsupported managed resource method: OPTIONS
```

The methods and headers are kept in a `Set`, so their order in the answer differs from one start to the next.

### The prefix and the request dump

With `http.prefix=/lds` every path above is under `/lds`, and a path outside it is answered 404 with no body.
The default is `/`. Seen with `LDS_HTTP_PREFIX=/lds`: `/lds/ping` gives 200 and `/ping` gives 404.

With `http.request.dump=true` the chain has the request dump handler of Undertow (`Handlers.requestDump`). It
writes every request and its answer, headers included, to the log at level INFO under the logger
`io.undertow.request.dump`.

Not finished: two things do not follow the prefix. `SourceHandler` takes the source from the whole path
(`exchange.getRequestPath()`) and cuts the length of `/source/` from it, so with `http.prefix=/lds` it asks for
the source `rce/A` when the address is `/lds/source/A`. With `txlog.split.sources=true` and the transaction log
in memory that address is answered 500 (`Invalid topic name ...: 'txlog-rce/A'`). With `txlog.split.sources=false`
every address under `/lds/source/` is answered `{"lastSourceId":null}`, `/lds/source/default` after a write
included. The addresses in the answer to `?schema` are written without the prefix: `/lds/ns?schema` gives
`["/ns/Company?schema","/ns/Person?schema"]`.

## Start

`UndertowApplication.start` starts the Undertow server. When there is a recovery trigger it then asks the
coordinator to complete the unfinished sagas in the saga logs of this instance
(`SagaExecutionCoordinator.completeLocalIncompleteSagas`, on the recovery thread pool), and starts the trigger
when that is done, whether it failed or not. `start` does not wait for the recovery. It logs the process id and
the address it listens on.

`Server.main` then calls `enableSagaExecutionAutomaticDeadlockDetectionAndResolution`, which starts the thread
`Saga-Threadpool-Watchdog` of the coordinator. The test server does not call it.

## Stop

`UndertowApplication.stop()` stops the application one part at a time, in the order the parts need each other. A
saga hands each of its steps to the thread pool of the sagas, writes each step to its saga log and writes to the
stores. So the sagas that are running complete before any of those is shut down.

| Step | What is stopped | How long it waits |
|---|---|---|
| 1 | New requests. `GracefulShutdownHandler.shutdown` makes every request from here on get 503 Service Unavailable. The requests that are being handled are completed. | 10 seconds at most (`RUNNING_SAGAS_TIMEOUT_SECONDS`). After that a warning is logged and the stop goes on. |
| 2 | The Undertow server. A request that is still being handled loses its connection without an answer. |  |
| 3 | The recovery of unfinished sagas: `SagaRecoveryTrigger.stop`, which also shuts the recovery thread pool down. Without a trigger the recovery thread pool is shut down by itself, because a write also uses it for recovery. | 10 seconds for each pool, then `shutdownNow` and 10 seconds more |
| 4 | Nothing. The stop waits for the sagas that are running (`SagaExecutionCoordinator.awaitRunningSagas`). | 10 seconds at most. After that a warning is logged and the stop goes on. |
| 5 | The watchdog of the coordinator (`SagaExecutionCoordinator.shutdown`), then the thread pool of the sagas, then the saga observer. | The thread pool: 10 seconds, then `shutdownNow` and 10 seconds more |
| 6 | The saga log pool. After that it gives out no log. |  |
| 7 | The persistence provider (`RxJsonPersistence.close`), then the client of the transaction log. |  |

A saga that is still running when its thread pool is shut down fails if it hands a step to the pool. Its saga
log holds what it did, and the saga is recovered from it later.

`stop(timeout, unit)` is the same with another time for step 1 and for step 4. Three more things hold:

- A part that fails to stop is logged at level ERROR, and the parts after it are stopped all the same
  (`stop(String, Stopping)`).
- The application is stopped once. A second call returns at once and does not wait for the first. `Server.main`
  calls `stop` from its shutdown hook and from a `finally` block, so this matters.
- A stop on a thread that is interrupted does not wait, and still stops every part.

`SearchIndex` has no method to close it, so the search index is not part of the stop.

This is what a client sees. A `PUT` was sent with half of its body, the process was then sent `SIGTERM`, and
half a second later these requests were sent:

```sh
curl -s -i http://localhost:19107/ping
```

```http
HTTP/1.1 503 Service Unavailable
Connection: keep-alive
Content-Length: 0
Date: Fri, 20 Nov 2026 15:29:37 GMT
```

```sh
curl -s -i -H "Origin: https://app.example" http://localhost:19107/ns/Company/acme
```

```http
HTTP/1.1 503 Service Unavailable
Connection: keep-alive
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Expose-Headers: Authorization,Content-Type
Content-Length: 0
Date: Fri, 20 Nov 2026 15:29:37 GMT
```

The rest of the body was sent two seconds after the `SIGTERM`. The `PUT` was answered `HTTP/1.1 201 Created`,
and then the application went on with its stop:

```
... 16:29:37.242+01:00 [Thread-0] WARN  io.descoped.lds.server.Server - ShutdownHook triggered..
... 16:29:39.401+01:00 [Thread-0] INFO  io.undertow - stopping server: Undertow - 2.4.4.Final
... 16:29:39.405+01:00 [Thread-0] INFO  io.descoped.lds.core.UndertowApplication - Leaving.. Bye!
```

The process ends with exit code 143 after `SIGTERM`. `/health/alive` and `/health/ready` are answered 503 during
step 1 as `/ping` is. A preflight request is answered 204 during step 1 as well, because the `CORSHandler`
answers it before the `GracefulShutdownHandler` sees it.

## Configuration

Core reads its configuration from a `DynamicConfiguration` (`io.descoped.config:dynamic-configuration`). The
caller builds it. `UndertowApplication.getDefaultConfigurationResourcePath()` gives the name of the file with the
defaults of core, `application-defaults.properties`, which `Server` and the tests read first. `Server` reads the
defaults of the server over them, and those differ for some keys: see [the server](server.md).

An environment variable or a system property sets only a key that one of the files has. So a key that core
reads gets a default in `application-defaults.properties` when it is added. The rule holds for the keys under
`sagalog.config.` and `txlog.config.` too: with the defaults of core alone, `LDS_SAGALOG_CONFIG_X=1` sets nothing.

| Key | Default in core | Meaning |
|---|---|---|
| `http.host` | `0.0.0.0` | The address the HTTP listener binds to. |
| `http.port` | `9090` | The port of the HTTP listener. |
| `http.prefix` | `/` | The path every address of LDS is under. |
| `http.request.dump` | `false` | True writes every request and its answer to the log. |
| `http.cors.allow.origin` | `.*` | Regular expressions for the origins that are allowed, separated by commas. |
| `http.cors.allow.header` | `Content-Type,Authorization` | The request headers a preflight request may ask for, separated by commas. Also sent as `Access-Control-Expose-Headers`. |
| `http.cors.allow.methods` | `POST,GET,PUT,DELETE,HEAD` | The methods a preflight request may ask for, separated by commas. |
| `http.cors.allow.credentials` | `false` | True adds `Access-Control-Allow-Credentials: true`. |
| `http.cors.allow.max-age` | `900` | The value of `Access-Control-Max-Age`, in seconds. |
| `namespace.default` | `ns` | The namespace LDS answers for. Also given to the persistence provider and to the GraphQL schema builder. |
| `graphql.schema` | empty | The GraphQL schema file that is the model. Empty: the model is read from `specification.schema`. |
| `specification.schema` | empty | JSON Schema files and folders, separated by commas. Read only when `graphql.schema` is empty. |
| `graphql.enabled` | `true` | True adds `/graphql` and `/graphiql`, when `graphql.schema` is not empty. |
| `graphql.search.enabled` | `false` | True makes LDS find a search provider. A write then also goes to the search index. |
| `search.index.provider` | empty | The id of the search provider. Read only when `graphql.search.enabled` is true. |
| `persistence.provider` | empty | The id of the persistence provider. It has no default that works: the caller sets it. |
| `persistence.initialization.max-wait-seconds` | `0` | The longest time to try to initialize the persistence provider. |
| `sagalog.provider` | `no.cantara.sagalog.memory.MemorySagaLogInitializer` | The class name of the saga log initializer. |
| `sagalog.provider.initialization.max-wait-seconds` | `0` | The longest time to try to initialize the saga log. |
| `sagalog.provider.initialization.retry-interval-seconds` | `1` | The time between two attempts. |
| `sagalog.config.*` | `cluster.owner=my.org`, `cluster.name=lds-test`, `cluster.instance-id=01` | Given to the saga log initializer without the prefix. |
| `saga.number-of-logs` | `50` | The number of saga logs the coordinator registers. |
| `saga.threadpool.core`, `saga.threadpool.max`, `saga.threadpool.keepalive.seconds`, `saga.threadpool.queue.capacity` | `100`, `200`, `60`, `20` | The thread pool of the sagas. The comments in `application-defaults.properties` say how to size it. |
| `saga.recovery.enabled` | `true` | True makes LDS recover unfinished sagas at start and then at intervals. |
| `saga.recovery.interval.seconds.min`, `saga.recovery.interval.seconds.max` | `30`, `60` | The time between two recovery passes is a random number of seconds between these. |
| `saga.commands.enabled` | `false` | True passes the commands in the `saga` query parameter of a write to the saga. |
| `txlog.rawdata.provider` | `discard` | The name of the transaction log provider. |
| `txlog.rawdata.provider.initialization.max-wait-seconds` | `0` | The longest time to try to initialize the transaction log. |
| `txlog.rawdata.provider.initialization.retry-interval-seconds` | `1` | The time between two attempts. |
| `txlog.config.*` | none | Given to the transaction log provider without the prefix. |
| `txlog.split.sources` | `false` | True gives every source a transaction log of its own. |
| `txlog.default-source` | `default` | The source of a write that names none. The only source when `txlog.split.sources` is false. |
| `txlog.rawdata.topic-prefix` | `txlog-` | Put before the source to make the name of a topic of the transaction log. |

The keys of the saga and the transaction log are explained in [the write path](core-write-path.md).

The code has a default of its own for some keys, used when no file has the key: `http.request.dump`,
`graphql.enabled`, `graphql.search.enabled`, `http.cors.allow.credentials`, `txlog.split.sources`,
`saga.commands.enabled` and `saga.recovery.enabled` are then false, `txlog.default-source` is `default`, and
`txlog.rawdata.topic-prefix` is empty. The numbers (`http.port`, `http.cors.allow.max-age`, the thread pool, the
number of saga logs, the waits and intervals) and `specification.schema` are read with `getInt` and `get`, which
throw `Missing configuration key` when no file has the key. The other texts are read with `find(key).orElse(null)`
and are not checked for null. The three lists of `http.cors.allow.*` are split at once, so a configuration
without them ends in a `NullPointerException`. A configuration without `http.prefix` gets no prefix.

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
- It `uses` the three service interfaces it loads: `PersistenceInitializer`, `SearchIndexProvider` and
  `SagaLogInitializer`.
- It `provides` `SearchIndexProvider` with `TestSearchIndex`.
- It `exports` `io.descoped.lds.core`, which holds `UndertowApplication`, and `io.descoped.lds.test.server`.
- It `opens` `io.descoped.lds.graphql.graphiql`, the package of the GraphiQL page.

LDS runs on the class path, so the descriptor is checked when the module is compiled and is not used at run time.

Not finished: the descriptor has parts that wait for the revision of core. `java.xml` is required with the
comment that it belongs in test scope. `io.descoped.lds.test.server` is exported for one empty class, `Dummy`,
with the comment that it is needed to run tests in an IDE. No `requires` is `transitive`, though the public
methods of `UndertowApplication` return types of other modules and of packages that are not exported. The
compiler does not report this, because the lint categories `exports` and `requires-automatic` are among those
the POM of core turns off.

## Tests

The tests start the application in the JVM with the memory provider. The tests named here do not need Docker.
Other tests of core do: see [the write path](core-write-path.md).

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
`gqlschema/schema.graphql`, the search provider `testSearchIndex`, a saga log in files under
`./target/test-sagalog`, no recovery, and `graphql.enabled=false`.

A test class that needs to stop its server starts one itself, as `ApplicationStopTest` does: it builds the
configuration, makes a `TestServer` on a free port between 9500 and 9999 and calls `getApplication().start()`.
`ApplicationStopTest` is in the package `io.descoped.lds.core.saga`, because it reads fields of
`SagaExecutionCoordinator` that are not public.

| Test class | What it holds this part to |
|---|---|
| `ApplicationStopTest` (JUnit) | The stop. A saga that is running completes before the application stops. The stop waits for a saga for as long as it is told. A saga in the middle of its steps completes. A saga that outlasts the wait fails and leaves its log for recovery. A request that arrives while the application stops is answered 503, with the CORS headers, while the request in flight is answered 201. A request that is not completed in time does not hold the stop up. An interrupted stop does not wait. A part that fails to stop does not keep the rest from stopping. After the stop the thread pool is terminated and the saga log pool gives out no log. Its saga log is `SlowTruncateSagaLogInitializer`, whose writes take time, so that a saga is still running when the server is stopped. |
| `CORSHandlerTest` (TestNG) | The CORS headers of a request and of a preflight request, on an Undertow server of its own. |
| `NamespaceControllerTest` (TestNG) | 404 for `/`, and 400 `Unsupported namespace` for another namespace, also one that only begins with the name of the namespace. |
| `NamespaceSchemaTest` (TestNG) | `?schema` and `?schema=embed` on the namespace give a JSON array with more than one element. |
| `DataControllerTest` (TestNG) | 400 `Not a managed resource name: "bull"` for a path with an entity type the model does not have. Its other five tests are disabled. |
| `SourceTest` (TestNG) | `/source/{source}` with the transaction log in memory, split by source and not split. |
| `PersistenceConfiguratorTest`, `SearchIndexConfiguratorTest` (TestNG) | A provider is found by its id. A key that is missing and a provider that is not there fail. |
| `ServerTest` (TestNG) | The test server has a running HTTP listener. |

One test class of core is run with `./mvnw test -pl linked-data-store-core -Dtest=<Class>`, with the name of a
class above. A new test class that starts a server of its own is JUnit 6 with AssertJ. A test that takes its
server from `TestServerListener` is TestNG until core is revised.

`DevServer` (`src/test/java/io/descoped/lds/server`) is a main class for starting core from an IDE with the
memory provider. It does not start as it stands: see [Not finished](#not-finished).

## Rules to keep

- **Keep the order of the stop.** Requests first, then the sagas that are running, then their thread pool, the
  saga log pool and the stores. `ApplicationStopTest` checks this order. A new part that sagas use is stopped
  after the sagas have completed.
- **A new handler goes inside the `GracefulShutdownHandler`**, or its requests are not counted and not completed
  before the stop goes on. The `CORSHandler` stays outside it.
- **A handler that calls a store moves off the I/O thread first** (`exchange.isInIoThread()`, then
  `exchange.dispatch(this)`), as `HealthCheckHandler`, `DataController` and `SourceHandler` do. An I/O thread of
  Undertow must not block.
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

- A start that fails after the saga observer is started leaves the process running. See [When the start
  fails](#when-the-start-fails).
- `SourceHandler` and the answer to `?schema` do not follow `http.prefix`. See [The HTTP handler
  chain](#the-http-handler-chain).
- `SourceHandler` puts the source of the address into the name of a topic without checking it. With
  `txlog.split.sources=true`, `GET /source/a/b` is answered 500 with no body
  (`Invalid topic name (1-100 of A-Z a-z 0-9 _ . -, starting with a letter or digit): 'txlog-a/b'`); the client
  of the transaction log refuses the name. Seen with the transaction log providers `memory` and `discard`.
- `parseSchemaFile` opens what it finds on the class path as a file (`new File(systemResource.getPath())`). A
  `graphql.schema` that names a resource inside a jar ends the start with `java.nio.file.NoSuchFileException`
  for a path of the form `file:/.../lib/<jar>!/<resource>`.
- `SearchIndexConfigurator` does not read `SearchIndexProvider.configurationKeys()`, so core does not tell at
  start that a key of the search provider is missing, as it does for the persistence provider.
- LDS answers for one namespace, `namespace.default`. Any other first part of a path is answered 400.
- `HealthCheckHandler` checks the persistence provider only. Its comments name the search index and the logs as
  things to check. `/health/ready` and `/ping` check nothing.
- `TestSearchIndex` is a search provider for tests that is in the main code. The module descriptor provides it,
  but its file in `META-INF/services` is among the test resources. A running LDS does not find it:
  `LDS_GRAPHQL_SEARCH_ENABLED=true LDS_SEARCH_INDEX_PROVIDER=testSearchIndex` ends the start with
  `No search index provider found for providerId: testSearchIndex`.
- The attempts at start do not log why an attempt failed, and try a wrong configuration again as if the store
  were not up.
- The default `sagalog.config.cluster.owner=my.org` is refused by the PostgreSQL saga log
  (`schema (cluster.owner) must match [A-Za-z0-9_-]+: 'my.org'`). A configuration with that saga log sets
  another value.
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
