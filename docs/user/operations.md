# Running Linked Data Store

This document is about running Linked Data Store (LDS): the container image and the compose file, starting and
stopping, the health addresses, the logs, what LDS stores where, what happens to a write when LDS or one of its
stores fails, and what to keep safe. It is for people who run LDS, for themselves or for others. It says what
LDS does today, and says plainly what it does not do.

## About what is shown

LDS 3.0 is in development and nothing is released. Everything here is about version `3.0.0-SNAPSHOT`, built
from the repository.

- The requests were run against LDS started on the JDK. The addresses are written with the default port, 9090.
  The LDS of the runs had another port; nothing else differs. An answer is shown as its status line and its
  body.
- The runs with PostgreSQL used a PostgreSQL 18.7 on the same machine, at the address `::1` and the port 19407.
  That address is in some of the messages below.
- The commands that need Docker are those of the repository. They were not run for this document. What is said
  about the image and the compose file is read from the two files and from the tests that build and start them.
- No Neo4j and no Solr were at hand. What is said about Neo4j, and about a Solr that answers, is read from the
  code and its tests. What LDS does when Solr does not answer was run.
- A line of the log is shown with its time, its level and its message. A long line is broken here.

## The parts of a running LDS

LDS is one process. It keeps four things. The configuration chooses a provider for each, and the provider keeps
it in a store, in the memory of the process or outside LDS:

| What | As LDS comes | Can be kept in |
|---|---|---|
| The documents: every version of every document | in memory | PostgreSQL or Neo4j |
| The [saga log](../glossary.md#saga-log): the writes that are under way | in memory | PostgreSQL |
| The [transaction log](../glossary.md#transaction-log): one entry for every write | not kept | memory or PostgreSQL |
| The search index | search is off | Solr |

As it comes, LDS keeps everything in memory and nothing is left when it stops. In a run a document was written,
LDS was stopped and started again, and the document was answered `404 Not Found`. That is for trying LDS.

[Concepts](concepts.md#what-a-write-goes-through) tells what a [saga](../glossary.md#saga) and the two logs are.
[Configuration](configuration.md) has the keys that choose each store. In this document PostgreSQL, Neo4j
and Solr are called the engines.

## The image

The image is built on your machine from the repository. It is not fetched from anywhere: no image of LDS is
published. Build LDS first ([Getting started](getting-started.md) has the command). Then, in the root of
the repository:

```sh
docker build -t lds-server linked-data-store-server
docker run --rm -p 127.0.0.1:9090:9090 lds-server
```

What is in the image:

- A Java runtime, version 27, on Alpine Linux (the image `eclipse-temurin:27-jre-alpine`).
- LDS in the directory `/lds`: its jar files in `/lds/lib`, and the example model in
  `/lds/schemas/schema.graphql`.
- No script. The Java program is the process of the container.

How it runs:

- LDS is started in `/lds`, so a relative path in the configuration is a path under `/lds`.
- It runs as the user `lds`, not as root.
- It listens on port 9090 in the container. The `docker run` above makes that port 9090 on the machine, on the
  address 127.0.0.1 only.
- With no configuration it is LDS as it comes: everything in memory, with the example model.

How it is given a model and a configuration:

- The model is read from `/lds/schemas`. Mount a folder of your own there; [The model](model.md) tells
  what the folder holds.
- The configuration is given by environment variables that start with `LDS_`, such as
  `LDS_PERSISTENCE_PROVIDER` and `LDS_NEO4J_DRIVER_URL`, or by a file mounted as
  `/lds/conf/application.properties`. [Configuration](configuration.md) has the keys and the rules.
- Options of the JVM are given in the variable `JAVA_TOOL_OPTIONS`.

The health check of the image asks `/ping` inside the container: once a second in the first 60 seconds, then
every 10 seconds. An answer must come within 3 seconds, and after 3 checks that fail the container is called
unhealthy. Three things follow:

- The check knows the port from the variable `LDS_HTTP_PORT`, and uses 9090 without it. Move the port in the
  container with that variable, not in a mounted file.
- Leave `http.prefix` as it is. With a prefix, `/ping` is answered `404`: in a run with `LDS_HTTP_PREFIX=/lds`,
  `/ping` gave `404` and `/lds/ping` gave `200`. The container is then called unhealthy while LDS runs.
- `/ping` asks no store. A healthy container means that LDS answers HTTP, not that its stores can be reached.
  See [The health addresses](#the-health-addresses).

`docker stop` sends the signal TERM to the Java program. LDS then stops in order, as [Stopping](#stopping) describes,
and the container ends with exit code 143. A stop takes 30 seconds or more when a write is held up. A container that is
ended by force before LDS has stopped has crashed, as far as its writes go; see [Writes that fail, crashes and
recovery](#writes-that-fail-crashes-and-recovery).

## The compose file: a whole LDS for development

`linked-data-store-server/compose.yaml` starts LDS with an engine for each of the four things it keeps. Build
LDS first, then, in the root of the repository:

```sh
docker compose -f linked-data-store-server/compose.yaml up --build
```

| Service | Image | What it is for | Port on the machine |
|---|---|---|---|
| `lds` | built from the Dockerfile | LDS. It answers on `http://localhost:9090`. | 9090, moved with `LDS_PORT` |
| `neo4j` | `neo4j:2026.10-community` | The documents. The browser of Neo4j is on `http://localhost:7474`. | 7474, moved with `NEO4J_HTTP_PORT`; 7687 (Bolt, the protocol of Neo4j), moved with `NEO4J_BOLT_PORT` |
| `postgres` | `postgres:18-alpine` | The saga log, in the database `sagalog`, and the transaction log, in the database `txlog`. | none |
| `solr` | `solr:10.1` | The search index, in the core `lds-index`. The admin pages of Solr are on `http://localhost:8983`. | 8983, moved with `SOLR_PORT` |

- **Ports.** Every port is published on 127.0.0.1 only, so the stack is reached from the machine it runs on and
  from nowhere else. PostgreSQL has no port on the machine. A port that is taken is moved with its variable:
  write `LDS_PORT=9190` in front of `docker compose`.
- **Passwords.** They are written in the file: user `neo4j` with the password `lds-development` for Neo4j, user
  `lds` with the password `lds` for PostgreSQL. They are for development. Do not use the file as it is for
  anything else.
- **Order.** LDS is started when Neo4j, PostgreSQL and Solr each say they are healthy.
- **The model.** The folder `linked-data-store-server/schemas` is mounted as `/lds/schemas`, read-only. LDS reads
  the model at start, so restart the service `lds` after a change of the model.
- **What the stack makes before LDS starts.** PostgreSQL makes the databases `sagalog` and `txlog` from the file
  `linked-data-store-server/postgres-init.sql`, once, when its data directory is empty. It also makes a database
  `lds`, which this stack does not use. Solr makes the core `lds-index`. LDS makes none of these itself.
- **Stopping.** The service `lds` is the image of the section above: when its container is told to stop, LDS
  stops in order, as [Stopping](#stopping) describes.
- **The data.** It is in three volumes, `neo4j`, `postgres` and `solr`, and is kept when the stack is stopped and
  started again. This command removes the stack and the three volumes:

```sh
docker compose -f linked-data-store-server/compose.yaml down -v
```

In the stack the saga log has the cluster owner `development`, the cluster name `lds` and the instance id `01`.
[Several LDS on one saga log](#several-lds-on-one-saga-log) tells what these are for.

## Starting

LDS does this when it starts, in this order:

1. It reads the configuration. The configuration is read once; a change needs a new start.
2. It reads the model.
3. It starts the persistence provider, which connects to the store of the documents and makes what it needs
   there (see [What is stored where](#what-is-stored-where)).
4. It starts the saga log.
5. It makes the search index, if search is on. It does not ask Solr anything, so LDS starts when Solr is away.
6. It starts the transaction log.
7. It opens its port. Until now no request is answered, `/ping` included.
8. It completes the writes that its own saga log holds from an earlier run, and from then on looks for unfinished writes
   at intervals (see [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery)).

LDS has started when the log says `Server started`. Some of the lines before it say what LDS runs with. These
are from a start of LDS as it comes:

```
18:08:00.087 INFO  Managed domains: /Company /Person
18:08:00.088 INFO  Creating specification using GraphQL schema: schemas/schema.graphql
18:08:00.146 INFO  Using persistence provider: mem
18:08:00.151 INFO  Using saga-log-pool provider-class: no.cantara.sagalog.memory.MemorySagaLogInitializer
18:08:00.155 INFO  GraphQL Search is disabled. Skipping search index initialisation
18:08:00.155 INFO  Using transaction-log provider: discard
18:08:00.292 INFO  Started Linked Data Store server. PID 3439
18:08:00.292 INFO  Listening on 0.0.0.0:19407
18:08:00.292 INFO  Server started in 343ms..
```

`Managed domains` names the [entity types](../glossary.md#entity-type) of the model. With search on, the line about
search is `Search index provider configured: solr`.

### How long LDS waits for an engine

An engine that does not answer at start is tried again:

| Part | Key that sets the longest wait | Default | Pause after a try that failed |
|---|---|---|---|
| The documents | `persistence.initialization.max-wait-seconds` | 60 seconds | 1 second |
| The saga log | `sagalog.provider.initialization.max-wait-seconds` | 30 seconds | 2 seconds (`sagalog.provider.initialization.retry-interval-seconds`) |
| The transaction log | `txlog.rawdata.provider.initialization.max-wait-seconds` | 30 seconds | 2 seconds (`txlog.rawdata.provider.initialization.retry-interval-seconds`) |

Each try is one line of the log. Why a try failed is not said until the wait is over. In this run LDS had its
documents, its saga log and its transaction log in PostgreSQL, and PostgreSQL was started six seconds after LDS:

```
18:18:43.437 INFO  Persistence provider initialization attempt # 1
18:18:45.475 INFO  Persistence provider initialization attempt # 2
18:18:47.485 INFO  Persistence provider initialization attempt # 3
18:18:49.496 INFO  Persistence provider initialization attempt # 4
18:18:49.560 INFO  Persistence service-provider configured
18:18:49.562 INFO  SagaLogPool provider initialization attempt # 1
18:18:49.584 INFO  SagaLogPool initialized!
18:18:49.585 INFO  Transaction-log provider initialization attempt # 1
18:18:49.599 INFO  Transaction-log initialized
18:18:50.769 INFO  Server started in 7517ms..
```

While LDS waited, a request to `/ping` got no connection.

When the wait is over, LDS ends with exit code 1. The cause is printed by the JVM on standard error, as
`Exception in thread "main"` with a stack trace; it is not a line of the log. With
`LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=5` and no PostgreSQL, LDS made three tries and ended with:

```
Exception in thread "main" com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize
pool: Connection to ::1:19407 refused. Check that the hostname and port are correct and that the postmaster is
accepting TCP/IP connections.
```

With Neo4j one try takes long, because the connection to Neo4j tries again by itself inside the try, with a
warning and a stack trace in the log each time. With the default wait and no Neo4j, LDS made two tries of half
a minute or more each and ended after 91 seconds with exit code 1, a log of 1679 lines and
`org.neo4j.driver.exceptions.ServiceUnavailableException: Unable to connect to ...`. The key does not cut a try
short: with `LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=0` LDS made one try and ended after 36 seconds. The
waits inside a try are random, so the time is not the same in every run.

A signal that comes while LDS still waits for an engine ends it at once, with exit code 143 and no line in the
log. Nothing is stopped in order then.

[Configuration](configuration.md#an-engine-that-is-away) has the messages of a start that fails: a wrong
password, a database that is not there, a value that is wrong.

Not finished: for some keys a wrong value stops the start but not the process. In a run with
`LDS_GRAPHQL_ENABLED=yes` the JVM printed the cause, LDS opened no port, and the process was still there until it
was ended with `kill`. A container with such an LDS runs and its health check never succeeds. So look for
`Server started` in the log, not only for a running process.
[Configuration](configuration.md#a-start-that-fails-and-a-process-that-stays) lists the keys.

## The health addresses

| Address | Answer | What it says |
|---|---|---|
| `/ping` | `200` | LDS answers HTTP. No store is asked. |
| `/health/ready` | `200` | The same as `/ping`. |
| `/health/alive` | `200`, or `503` when the check fails | LDS can reach the store of the documents. The saga log, the transaction log and Solr are not asked. |

```console
$ curl -s -i 'http://localhost:9090/ping'
HTTP/1.1 200 OK

$ curl -s -i 'http://localhost:9090/health/ready'
HTTP/1.1 200 OK

$ curl -s -i 'http://localhost:9090/health/alive'
HTTP/1.1 200 OK
```

How to use them:

- Ask `/ping` to see that LDS is up. It is what the health check of the image asks.
- Ask `/health/alive` to see that reads and writes of documents can work. Give it time: with the documents in
  PostgreSQL and PostgreSQL stopped, the first two answers were still `200`, and a later one was `503` and came
  after 30 seconds. A caller that waits less than that for the answer sees no answer, not the `503`.
- No address tells that the saga log, the transaction log or Solr is away. With the saga log in PostgreSQL and
  PostgreSQL stopped, `/health/alive` answered `200` while a write was answered `500`. Look in the log; see
  [When an engine is away while LDS runs](#when-an-engine-is-away-while-lds-runs).
- While LDS stops, all three answer `503`.

[The REST API](rest-api.md#health) has the details of the three addresses.

## Stopping

LDS stops in order when its process gets the signal TERM, which is what `kill <pid>` and `docker stop` send. The
order is:

1. Every new request is answered `503 Service Unavailable`. The requests that LDS is handling are completed. LDS
   waits 10 seconds at most for them; a request that is not completed by then loses its connection without an
   answer. Then the port is closed.
2. LDS stops looking for unfinished writes.
3. The writes that are under way are completed. LDS waits 10 seconds at most for them. A write that is not
   completed by then fails, and stays in the saga log.
4. The saga log is closed.
5. The store of the documents and the transaction log are closed.

The two waits of 10 seconds have no key. The search index is not closed: a document that is waiting to be sent
to Solr at that moment is not waited for.

In this run a `PUT` had sent half of its body when LDS was told to stop with `kill <pid>`. It sent the rest
two seconds later and was answered `201 Created`. A request sent in between:

```console
$ curl -s -i 'http://localhost:9090/ping'
HTTP/1.1 503 Service Unavailable
```

The log of that stop:

```
18:08:36.601 WARN  ShutdownHook triggered..
18:08:38.746 INFO  stopping server: Undertow - 2.4.4.Final
18:08:38.749 INFO  Leaving.. Bye!
```

The process ended with exit code 143, which is the code of a process that was ended by TERM. After that a
request got no connection. An LDS with nothing to complete stopped within a few milliseconds.

Ctrl-C in the terminal of an LDS on the JDK sends the signal INT. LDS stops in the same order; in a run the
exit code was then 130.

When a wait runs out, the log has one of these lines, at the level `WARN`:
`Requests were still being handled after 10 SECONDS. They lose their connection when the HTTP server stops.` or
`Sagas were still running after 10 SECONDS. They fail when their thread pool is shut down, and are recovered from
their saga logs.` A part that fails to stop is logged as `Error while shutting down: ...`, and the parts after
it are stopped all the same.

A stop takes longer than the two waits when a write is held up. In two runs LDS had everything in PostgreSQL,
and a write of a document was held up there when LDS was told to stop:

- The write had been answered already (it was sent with `sync=false`). LDS waited 10 seconds, logged the line
  about sagas, logged `Pool did not terminate` at the level `ERROR` 20 seconds after that, and ended 30 seconds
  after the signal. The write was in the saga log, and the next start of LDS completed it.
- The caller of the write was still waiting for its answer. It lost its connection 10 seconds after the signal.
  LDS did not end then: it ended 42 seconds after the signal, when the write was no longer held up and was done.

Not finished: the second of these waits is none of the two waits of 10 seconds. In the run it lasted until the
write was done, and no key sets a limit for it.

A process that is ended with `kill -9`, or a machine that loses its power, stops nothing in order. In the runs the exit
code was then 137. [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery) tells what happens
to the writes that were under way.

## Logs

LDS writes its log to standard output, one line for each event, and to nowhere else. In a container it is the
log of the container. A line has the date and the time with the offset from UTC, the thread, the level, the
part of LDS that wrote it, and the message:

```
2026-11-20T18:08:00.292+01:00 [main] INFO  io.descoped.lds.server.Server - Server started in 343ms..
```

- The level is `INFO`, and no configuration key sets it.
- An error is followed by the lines of a stack trace.
- What ends a start is printed by the JVM on standard error and is not a line of the log.
- LDS takes another logback file from the system property `logback.configurationFile`. In a run with
  `JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=/path/logback-warn.xml`, a file with the level `WARN`, the log
  of a start and a stop had one line, `ShutdownHook triggered..`. With that level there is no line
  `Server started`. The JVM itself printed `Picked up JAVA_TOOL_OPTIONS: ...` on standard error first.

What to look for:

| Message | Level | Meaning |
|---|---|---|
| `Server started in ...ms..` | INFO | LDS has started and answers. |
| `... initialization attempt # 2`, and higher numbers | INFO | LDS waits at start for the store of the documents, the saga log or the transaction log. |
| `UT005071: Undertow request failed HttpServerExchange{ PUT /ns/Company/down1}` | ERROR | A request was answered `500`. The stack trace after the line has the cause. |
| `Observed Saga failure during execution. Saga-name: "...", executionId: "..."` | ERROR | A write failed. The id is the `saga-execution-id` of the write. The line comes again each time the write is tried again and fails. |
| `health check failed: ...` | WARN | `/health/alive` answered `503`. |
| `Started recovery of saga with sagaLog: ... and executionId: ...` | INFO | An unfinished write is run again. |
| `Recovery of saga succeeded, ...` | INFO | That write is now done. |
| `Recovery of saga failed, ...` | INFO | That write failed again. Note the level. |
| `Error while attempting to run cluster-wide saga recovery` | ERROR | A round of recovery had a write that failed again. It is tried again at the next round. |
| `Failed to retry saga-executions from saga-log ..., moving all entries to dead-saga-log.` | WARN | A write was given up. It is not done and LDS does not try it again; see [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery). |
| `Timeout. No available saga-logs, please check configuration.` | in the stack trace of a request that failed | There were more writes at one time than LDS has saga logs (`saga.number-of-logs`, 30). A write waits 10 seconds for a saga log and is then answered `500`. In a run 32 writes were held up at one time; 2 of them were answered `500` after 10 seconds. |
| `Unable to acquire exclusive lock on ...` | in the stack trace of a request that failed | Another LDS has the same instance id; see [Several LDS on one saga log](#several-lds-on-one-saga-log). |
| `Another client published to topic '...': only one process may write a topic` | in the stack trace of a request that failed | Another LDS writes the same transaction log; see [Several LDS on one saga log](#several-lds-on-one-saga-log). |
| `Deleted unused empty saga-log: ...` | INFO | LDS removed a saga log of an LDS that is not running; see [Several LDS on one saga log](#several-lds-on-one-saga-log). |
| `ShutdownHook triggered..`, then `Leaving.. Bye!` | WARN, INFO | LDS was told to stop, and has stopped in order. |

The log can hold what the callers send. With search on, every search text is logged
(`Executing query: ...`). Two keys make LDS log much more: `http.request.dump` logs every request with its
headers, the header `Authorization` included, and `neo4j.cypher.show` logs every statement sent to Neo4j, with
the values of the documents in it. [Configuration](configuration.md) has both.

In some runs the JVM printed four lines on standard error that start with `WARNING:` and name
`sun.misc.Unsafe::invokeCleaner` and a class of Undertow, the HTTP library of LDS. LDS went on answering.

## What is stored where

### The documents

**In memory** (`persistence.provider=mem`). Nothing is stored. The documents are gone when LDS stops.

Not finished: the memory provider has known faults. One of them stops every read and write of documents, while
`/ping` still answers `200`: [the GraphQL API](graphql-api.md#reverse-links) tells which query does it. The
others are said where a caller meets them, in [the REST API](rest-api.md) and
[the GraphQL API](graphql-api.md), each with the words "the memory provider".

**In PostgreSQL** (`persistence.provider=postgres`).

- The database and the user must be there. LDS does not make them.
- At start LDS makes one table, `fragment`, and one index besides its primary key, `fragment_value_idx`, when
  they are missing. In the runs they were made in the schema `public`. A table that is there is left as it is.
- One row is one [fragment](../glossary.md#fragment): one value of one version of one document. The table holds
  the documents of every namespace.
- A delete of a document writes a [tombstone](../glossary.md#tombstone): it adds a row and removes none. A write at the
  [valid time](../glossary.md#valid-time) of a version that is there replaces the rows of that version. No other request
  to LDS removes a row, so the table grows with every new version and every delete.
- LDS opens 10 connections at start and keeps them.
- LDS is tested with PostgreSQL 18 and with no other version.

**In Neo4j** (`persistence.provider=neo4j`).

- A document is a node that has its entity type as label and `namespace` and `id` as properties. Its versions hang
  from it, and a link is a relationship to the node of the document it points at. One database holds the
  documents of every namespace. No key names the database.
- At start LDS makes, for each entity type of the model, a constraint that makes `namespace` and `id` unique together
  and an index, when they are missing. Each one it makes is a line `Creating: ...` in the log.
- LDS never drops an index or a constraint. For one that is there and that it does not use, it writes a line
  `Not using index: ...` to the log, with the statement that drops it.
- A delete of a document adds a tombstone, a version that marks it as deleted. No request to LDS removes a document from
  Neo4j.
- LDS is tested with Neo4j 2026.10, community edition.

### The saga log

**In memory** (the default). A write that is under way when LDS ends is lost.

**In PostgreSQL** (`sagalog.provider=no.cantara.sagalog.postgres.PostgresSagaLogInitializer`).

- The database must be there. LDS does not make it.
- At start LDS makes a schema with the name of `sagalog.config.cluster.owner`, and in it one table for each saga
  log and a table `Locks`. A table has the name `SAGALOG_<cluster name>_<instance id>_<number>`. With the
  defaults there are 30 saga logs; in a run with the cluster `lds` and the instance `01` the tables were
  `SAGALOG_lds_01_00` to `SAGALOG_lds_01_29`.
- A write uses one saga log from its start to its end. While it is under way, the table holds the request, the
  document included. When it is done, the table is empty. So a saga log with rows is a write that is under way
  or that failed.
- LDS opens 50 connections at start and keeps them (`sagalog.config.connection-pool.max-size`).

Not finished: `sagalog.config.cluster.owner` has the default `my.org`, which the saga log in PostgreSQL refuses.
Set it; [Configuration](configuration.md) says more.

### The transaction log

**Not kept** (`txlog.rawdata.provider=discard`, the default). Nothing is stored.

**In memory** (`txlog.rawdata.provider=memory`). The entries are gone when LDS stops.

**In PostgreSQL** (`txlog.rawdata.provider=postgres`).

- The database must be there. LDS does not make it.
- At start LDS makes three tables when they are missing: `rawdata_message`, `rawdata_content` and
  `rawdata_metadata`.
- Every write adds one entry. Its id is the `saga-execution-id` that the caller got. It holds the whole document as it
  is after the write, and the method, the namespace, the entity type, the id, the valid time of the version and the
  `source` and `sourceId` of the request. The entry of a delete of a document has no document.
- Nothing in LDS removes an entry, so the log grows with every write.
- LDS opens 10 connections at start and keeps them.

Not finished: LDS reads the transaction log back for one thing only, `GET /source/{source}` of
[the REST API](rest-api.md#sources-and-the-transaction-log). Nothing in LDS builds the documents again from it.

### The search index

- The index is one Solr core, named at the end of `search.index.url`. The core must be there; LDS does not make
  it. The documents of every namespace and every entity type go to that one core.
- The index is behind the documents: it is [eventually consistent](../glossary.md#search-index-provider). LDS asks
  Solr to make a write searchable within five seconds, so a search right after a write can miss the document, also
  when the write was sent with `sync=true`.
- The index can stay behind. A document that was written while search was off, or while Solr was away, is not in the
  index, and LDS does not send it again (see [When an engine is away while LDS
  runs](#when-an-engine-is-away-while-lds-runs)).
- Every version of a document is an entry of its own in the index. A delete of a document takes out one entry
  at most: the entry of a version that has exactly the valid time of the delete. The entries of the other versions
  stay. A search does not give a deleted document all the same, because LDS reads every document it finds at the
  time of the query. For each such entry a search finds, LDS writes a line `Cound not find document for key ...`
  to the log, at the level `ERROR`.

Not finished: nothing in LDS builds the index again from the documents that are stored. A document comes into
the index when it is written. Search finds a document by the texts of its fields named `languageText`; what
search covers is under review. [The GraphQL API](graphql-api.md#search) tells how to search.

### Connections to PostgreSQL

| Part in PostgreSQL | Connections of one LDS |
|---|---|
| The documents | 10 |
| The saga log | 50 |
| The transaction log | 10 |

LDS opens them at start. A PostgreSQL as it comes takes 100 connections, so two LDS with all three parts in it
do not fit. In a run the second LDS opened its port, answered `/ping` with `200`, waited at
`Starting saga-recovery (instance-local) ...` and ended after a minute with exit code 1 and
`No database connection available within 30000 ms`. Its log never had the line `Server started`.

## Writes that fail, crashes and recovery

Every write is carried by a saga, and each step of it is noted in the saga log.
[Concepts](concepts.md#what-a-write-goes-through) tells how. A write that does not complete stays in the saga log, and
LDS runs it again later, with the request as it was first sent. This is recovery (forward recovery). It happens at
three times:

- **When LDS starts**, for the writes in its own saga log. This needs a saga log that is kept, so PostgreSQL.
- **At intervals**, for the writes in every saga log of the cluster: after a wait of 30 to 60 seconds, chosen
  anew each time (`saga.recovery.interval.seconds.min` and `.max`). One such look is called a round below.
- **When a later write is given the same saga log.** The saga logs are given out in turn, so with 30 saga logs
  and writes that come one at a time, that is at the latest the thirtieth write after it.

The first two need `saga.recovery.enabled=true`, which is the default.

### A crash

In this run LDS had its documents, its saga log and its transaction log in PostgreSQL. A write was held up in
the store of the documents, and LDS was ended with `kill -9`:

```console
$ curl -s -X PUT 'http://localhost:9090/ns/Company/crash1?sync=true' \
       -H 'Content-Type: application/json' -d '{"name": "Written before the crash"}'
$ kill -9 <pid>
```

The caller lost its connection and got no answer. One table of the saga log then held four rows: the start of
the saga with the request, the start and the end of the step that writes the transaction log, and the start of
the step that writes the document. The document was not stored. Then LDS was started again:

```
18:11:08.178 INFO  Starting saga-recovery (instance-local) ...
18:11:08.222 INFO  Started recovery of saga with sagaLog: PostgresSagaLogId{schema='development',
                   table='SAGALOG_lds_01_00'} and executionId: 01a1f9f0-97cc-73b6-8c5d-7d0cf461b182
18:11:08.245 INFO  Recovery of saga succeeded, sagaLog: PostgresSagaLogId{schema='development',
                   table='SAGALOG_lds_01_00'}, executionId: 01a1f9f0-97cc-73b6-8c5d-7d0cf461b182
18:11:09.212 INFO  Server started in 1507ms..
```

```console
$ curl -s -i 'http://localhost:9090/ns/Company/crash1'
HTTP/1.1 200 OK

{"name": "Written before the crash"}
```

The version has the valid time of the first request, not the time of the recovery. The saga log was empty again.

With the saga log in memory, the same crash loses the write: nothing is left to run again.

### What an answer tells the caller

A `500`, or a connection that is lost, does not say whether the write will be done:

- **Done later.** With the documents in PostgreSQL and PostgreSQL stopped, a write was answered `500` after 30
  seconds. PostgreSQL was started again, and the next round of recovery did the write, 38 seconds later: the
  document was there. The saga log was in memory, in an LDS that kept running.
- **Never done.** With the saga log in PostgreSQL and PostgreSQL stopped, a write was answered `500` after 30
  seconds and was not done later: LDS could not note it anywhere.

So a caller that gets no `2xx` sends the write again. Every call of the HTTP API is idempotent and safe to send
again. [The REST API](rest-api.md#when-a-write-fails) says what a failed write can leave behind.

A `2xx` to a write that LDS did not wait for (not a [synchronous write](../glossary.md#synchronous-write)) says that
the write is in the saga log, not that it is done.
[The REST API](rest-api.md#when-lds-answers-a-write-sync) tells which writes LDS waits for.

### A write that keeps failing

A write whose cause does not go away fails again at every round. Each round logs
`Recovery of saga failed, ...`, `Observed Saga failure during execution. ...` and
`Error while attempting to run cluster-wide saga recovery`.

Not finished: such a write is given up when a later write is handed its saga log and it fails once more. LDS
then moves its rows to a saga log named `dead-saga` and logs `Failed to retry saga-executions from saga-log ...,
moving all entries to dead-saga-log.` and `Moved all saga-log-entries from ... to ...`. In a run with the saga
log in PostgreSQL that was the table `SAGALOG_lds_01_dead-saga`. Nothing in LDS reads this log: the write is
not done, and LDS says so nowhere but in those lines. The rows are there for a person to read.

Many writes can be given up at one time. In that run 33 writes failed one after the other, which is more than
the 30 saga logs. Within the same second 31 of them were in `dead-saga`.

## When an engine is away while LDS runs

LDS keeps running. It does not end, and it connects again by itself when the engine is back. No restart is
needed: in a run `/health/alive` answered `200` within three seconds after PostgreSQL was started again.

| What is away | What LDS does |
|---|---|
| The store of the documents (run with PostgreSQL) | A read is answered `500`, at once in the runs. A write is answered `500` after 30 seconds, and is done later if the saga log could note it (see [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery)). `/health/alive` answers `503`. `/ping` answers `200`. |
| The saga log (run with PostgreSQL) | A read works. Every write is answered `500` after 30 seconds, also one sent with `sync=false`, and is not done later. `/health/alive` and `/ping` answer `200`. A write sent after PostgreSQL was started again was done. |
| Solr | A write of a document is answered `201` and the document is stored, but it is not in the index, and LDS does not send it to Solr again. A search is answered `200` with an error in its body. A delete of a document is answered `500`, and the document is deleted all the same. `/health/alive` and `/ping` answer `200`. |

A `500` has no body. The cause is in the log, after the line `UT005071: Undertow request failed ...`. The log
also gets many lines from the connections to PostgreSQL: about 700 lines for 70 seconds without PostgreSQL in
a run.

The run with Solr away, with search on and nothing listening at `search.index.url`:

```console
$ curl -s -i -X PUT 'http://localhost:9090/ns/Company/brew?sync=true' -H 'Content-Type: application/json' \
       -d '{
         "name": "Nordic Brewing",
         "about": [
           {
             "languageCode": "en",
             "languageText": "A small brewery by the fjord"
           }
         ]
       }'
HTTP/1.1 201 Created

{"saga-execution-id": "01a1f9f5-cb3f-7a97-8b60-ebb3970e5696"}

$ curl -s -i -X DELETE 'http://localhost:9090/ns/Company/brew?sync=true'
HTTP/1.1 500 Internal Server Error
```

After the `PUT` the log says `Added 1 documents to index`, though nothing reached Solr, and then has an error of the
connection to Solr. The delete stays in the saga log as a write that keeps failing, as [Writes that fail, crashes and
recovery](#writes-that-fail-crashes-and-recovery) describes: in the run it failed again at the next round, and was moved
to `dead-saga` when 31 more writes had been sent.

What LDS does when Neo4j goes away while LDS runs was not tried.

## Several LDS on one saga log

Several LDS can share one saga log in PostgreSQL. They are a cluster: every LDS has the same
`sagalog.config.cluster.owner` and `sagalog.config.cluster.name`, and its own
`sagalog.config.cluster.instance-id`. The instance id is in the names of the tables.

The default of the instance id is `01`, so it must be set for every LDS but one. In a run two LDS had the id
`01`. The second started, and a read through it worked, but every write through it was answered `500`, with
`Unable to acquire exclusive lock on ...` and the name of a table of the saga log in its log. The first went on
writing.

Each LDS completes its own unfinished writes when it starts. At intervals each LDS also looks at the saga logs
of the whole cluster:

- The saga logs of an LDS that is running are left alone. In a run with two LDS, each still had its 30 tables
  after more than a minute.
- The saga logs of an LDS that is not running are taken over: their unfinished writes are completed, and their
  tables are removed. In a run instance `01` was ended with `kill -9` with a write under way. Instance `02`
  completed that write 47 seconds later and removed the 30 tables of `01`.
- The tables of an LDS that was stopped in order are removed in the same way. The LDS makes them again when it
  starts.
- The saga log `dead-saga` is not looked at. In a run the table `SAGALOG_lds_01_dead-saga` and its rows were
  still there after instance `02` had removed the other tables of `01`.

So the writes of an LDS that is gone are completed by another LDS within about a minute, if both have the same
store of the documents. The documents must be in PostgreSQL or Neo4j for that: an LDS with its documents in
memory shares them with no one.

Not finished: only one LDS can write a transaction log in PostgreSQL. In a run two LDS had the same transaction log. The
first write through each of them was answered `201`. From then on every write through the LDS that had written first was
answered `500`, at once, with `Another client published to topic 'txlog-default': only one process may write a topic` in
its log, while the other LDS went on writing. The writes that were answered `500` stayed in the saga log of their LDS.
When that LDS was stopped, the other completed them at its next round, 19 seconds later. More such writes than there are
saga logs are given up, as [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery) describes.
In another run each LDS had its own `txlog.rawdata.topic-prefix` (`txlog-` and `txlog-02-`), and both wrote. Each then
has a transaction log of its own, and `GET /source/{source}` answers for the writes of the LDS that is asked.

## What to keep safe

LDS has no tool that makes a backup or restores one. Use the tools of the engine.

| Store | What losing it means |
|---|---|
| The store of the documents | This is the data: every version of every document. In PostgreSQL it is the table `fragment` in the database of `postgres.driver.database`. In Neo4j it is the database LDS connects to. Losing it loses the documents. Nothing else in LDS can build them again. |
| The saga log | It holds the writes that are under way and the writes that failed, `dead-saga` included. It has no rows when every write is done. Losing it loses the writes it holds. It matters at the moment of a crash. |
| The transaction log | A record of every write. LDS runs without it and cannot build the documents from it. It answers `GET /source/{source}`. Keep it if the callers use that address, or if the record matters to you. |
| The search index | It is made from the writes, and LDS cannot make it again from the documents. If it is lost, a document is found again only after it is written again. |
| The model and the configuration | They are files of your own. LDS reads them at start and stores them nowhere. |

In the compose file the stores are the three volumes `neo4j`, `postgres` and `solr`.

## What LDS does not have

Each of these was checked in the code, and some also by a request.

- **No authentication and no authorization.** LDS asks no caller who it is. Whoever can reach the port can read,
  write and delete every document.
- **No TLS.** LDS listens with plain HTTP only. A request with `https` to its port failed.
- **No address for metrics.** There are the three health addresses and nothing else. `GET /metrics` was answered
  `400`, as an address LDS does not have.
- **No backup tool, and no clean-up.** Nothing in LDS copies a store, removes old versions of a document or
  removes entries of the transaction log.
- **No key for the level of the log**, and no log file.
- **No encryption or user for Solr, and no option for the connection to the PostgreSQL of the documents**, such as
  TLS. [Configuration](configuration.md) lists what has no key.

As it comes, LDS listens on every address of the machine (`http.host` is `0.0.0.0`). The `docker run` and the
compose file of this document publish its port on 127.0.0.1 only. Who can reach the port, and whether the way
there is encrypted, is decided outside LDS.
