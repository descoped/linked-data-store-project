---
title: "Running LDS"
description: "The image and the compose file, starting and stopping, health, logs, what is stored where."
order: 7
---

# Running Linked Data Store

This document is about running Linked Data Store (LDS): the container image and the compose file, starting and
stopping, the health addresses, the logs, what LDS stores where, what happens to a write when LDS or one of its
stores fails, and what to keep safe. It is for people who run LDS, for themselves or for others. It says what
LDS does today, and says plainly what it does not do.

## About what is shown

Everything here is about LDS 3.0.1, as the images on Docker Hub and as it is built from the repository.

- The requests were run against LDS, started on the JDK or from its image, on a port of its own. The addresses are
  written with the default port, 9090; nothing else differs. An answer is shown as its status line and its body.
- The runs with PostgreSQL used PostgreSQL 18, on the same machine or in a container of its own. Its address is
  in some of the messages below. The runs with search used Solr 10.1 in a container of its own.
- What LDS does when Neo4j goes away while it runs was run with the compose file.
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

LDS is published on Docker Hub as `descoped/lds`, for Linux on amd64 and arm64, in three variants of the same LDS:

| Tags | What it is for |
|---|---|
| `3.0.1`, `3.0`, `3`, `latest` | The regular image. It has a shell and the small tools of busybox (`sh`, `wget`, `ls`, `ps`, `top`, `vi`), for looking around in a running container. |
| `3.0.1-distroless`, `3.0-distroless`, `3-distroless`, `distroless` | No shell and no program but `java`. For production, and for platforms where no shell is used, such as Cloud Run and Fargate. `docker exec` can run `java` in it and nothing more. |
| `3.0.1-debug`, `3.0-debug`, `3-debug`, `debug` | The regular image with the tools of the JDK for diagnostics: remote JMX, a remote debugger, `jcmd`, `jstack`, `jmap`, `jstat`, `jhsdb`, `jfr` and heap dumps. See [Diagnostics with the debug image](#diagnostics-with-the-debug-image). |

`3.0.1` names one release; `3.0` and `3` move to the newest release of that line, and `latest` to the newest
release. Name the whole version, or the digest, where what runs must not change on its own.

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
```

The same images are built from the repository with three commands, after LDS is built
([Getting started](getting-started.md) has the command):

```sh
docker build -t lds-server linked-data-store-server
docker build -t lds-server:distroless --target distroless linked-data-store-server
docker build -t lds-server:debug --target debug linked-data-store-server
```

Every published image has a list of what it holds (an SBOM) and the record of its build (provenance), and is
signed with cosign by the release workflow of the repository. Check the signature with:

```sh
cosign verify descoped/lds:3.0.1 \
  --certificate-identity https://github.com/descoped/linked-data-store-project/.github/workflows/release.yml@refs/heads/master \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

What is in each image:

- A Java runtime, version 27, with only the parts of Java that LDS uses, made from the JDK of the image
  `eclipse-temurin:27-jdk`. The debug image has the parts for diagnostics as well.
- The C library glibc, CA certificates and time zones, from Debian 13 (Google's distroless images), and in the
  regular and the debug image busybox in `/busybox`. No package manager.
- LDS in the directory `/lds`: its jar files in `/lds/lib`, and the example model in
  `/lds/schemas/schema.graphql`.
- No script. The Java program is the process of the container.

How it runs:

- LDS is started in `/lds`, so a relative path in the configuration is a path under `/lds`.
- It runs as the user `lds`, not as root.
- It listens on port 9090 in the container. The `docker run` above makes that port 9090 on the machine, on the
  address 127.0.0.1, localhost, only.
- With no configuration it is LDS as it comes: everything in memory, with the example model.

How it is given a model and a configuration:

- The model is read from `/lds/schemas`. Mount a folder of your own there; [The model](model.md) tells
  what the folder holds.
- The configuration is given by environment variables that start with `LDS_`, such as
  `LDS_PERSISTENCE_PROVIDER` and `LDS_NEO4J_DRIVER_URL`, or by a file mounted as
  `/lds/conf/application.properties`. [Configuration](configuration.md) has the keys and the rules.
- Options of the JVM are given in the variable `JAVA_TOOL_OPTIONS`.

No image declares a health check, so a container started with `docker run` alone has no health status. Whoever
runs LDS asks it, at the port and under the prefix (`http.prefix`) it gave LDS: `/health/ready` to know whether LDS
can take writes, and `/health/alive` or `/ping` to know that the process answers. [The health
addresses](#the-health-addresses) tells what each answers. The compose file of the repository asks
`/health/ready`. With `LDS_HTTP_PREFIX=/lds` the addresses are `/lds/ping` and `/lds/health/ready`; `/ping` is then
answered `404`.

`docker stop` sends the signal TERM to the Java program. LDS then stops in order, as [Stopping](#stopping) describes,
and the container ends with exit code 143. A stop takes about 40 seconds at most when a write is held up. A container
that is ended by force before LDS has stopped has crashed, as far as its writes go; see [Writes that fail, crashes and
recovery](#writes-that-fail-crashes-and-recovery). Docker ends a container by force 10 seconds after `docker stop`
unless it is given longer, so give LDS a minute: `docker stop -t 60 <container>`, and in a compose file of your own
`stop_grace_period: 60s`, as the compose file of the repository has.

### Diagnostics with the debug image

The debug image holds the tools of the JDK. They run in the container, as the user `lds`, and attach to LDS, the
process with the number 1:

```sh
docker exec <container> jcmd 1 VM.version
docker exec <container> jstack 1
docker exec <container> jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp <container>:/tmp/heap.hprof .
```

Remote JMX and a remote debugger are switched on by options of the JVM. Give them in `JDK_JAVA_OPTIONS`, which only
the `java` that starts LDS reads, and not in `JAVA_TOOL_OPTIONS`, which every tool of the JDK reads as well: a tool
would then try to open the same port, and fail. This run opened JMX on 9010 and the debugger on 5005, both on
localhost of the machine, and LDS went on answering:

```sh
docker run --rm -p 127.0.0.1:9090:9090 -p 127.0.0.1:9010:9010 -p 127.0.0.1:5005:5005 \
  -e JDK_JAVA_OPTIONS="-Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010 \
    -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false \
    -Djava.rmi.server.hostname=127.0.0.1 -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" \
  descoped/lds:3.0.1-debug
```

JMX without a password and a debugger give anyone who reaches the port the whole of LDS. Publish such ports on
localhost only, as here, and use the debug image only for as long as the investigation takes.

## The compose file: a whole LDS for development

`linked-data-store-server/compose.yaml` starts LDS with an engine for each of the four things it keeps. Build
LDS first, then, in the root of the repository:

```sh
docker compose -f linked-data-store-server/compose.yaml up --build
```

| Service | Image | What it is for | Port on the machine |
|---|---|---|---|
| `lds` | built from the Dockerfile | LDS. It answers on `http://localhost:9090`. | 9090, moved with `SERVER_PORT` |
| `neo4j` | `neo4j:2026.10-community` | The documents. The browser of Neo4j is on `http://localhost:7474`. | 7474, moved with `NEO4J_HTTP_PORT`; 7687 (Bolt, the protocol of Neo4j), moved with `NEO4J_BOLT_PORT` |
| `postgres` | `postgres:18-alpine` | The saga log, in the database `sagalog`, and the transaction log, in the database `txlog`. | none |
| `solr` | `solr:10.1` | The search index, in the core `lds-index`. The admin pages of Solr are on `http://localhost:8983`. | 8983, moved with `SOLR_PORT` |

- **Ports.** Every port is published on localhost (127.0.0.1) only, so the stack is reached from the machine it runs
  on and from nowhere else. PostgreSQL has no port on the machine. A port that is taken is moved with its variable:
  write `SERVER_PORT=9190` in front of `docker compose`. No variable of the compose file starts with `LDS_`, the
  prefix of the variables LDS reads itself.
- **Passwords.** They are written in the file: user `neo4j` with the password `lds-development` for Neo4j, user
  `lds` with the password `lds` for PostgreSQL. They are for development. Do not use the file as it is for
  anything else.
- **Order and health.** LDS is started when Neo4j, PostgreSQL and Solr each say they are healthy. The service
  `lds` is healthy when `/health/ready` answers `200`: when the store, the saga log, the transaction log and the
  index queue answer, also when search is away or behind.
- **A small Neo4j.** Neo4j is set for development: its transaction log is not made 256 MiB in advance, is
  rotated at 16 MiB and keeps two files, and it uses a heap of 256 to 512 MiB and a page cache of 256 MiB,
  instead of a share of the memory of the machine. For a larger store, change the variables `NEO4J_...` of the
  service `neo4j`.
- **Stopping.** LDS is given a minute to stop (`stop_grace_period: 60s`), so `docker compose stop` and `down`
  let it stop in order also when a write is held up.
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

In the stack the saga log has the cluster owner `development`, the cluster name `lds` and the instance id `01`, and
the index queue is kept in the database `txlog`.
[Several LDS on one saga log](#several-lds-on-one-saga-log) tells what these are for.

## Starting

LDS does this when it starts, in this order:

1. It reads the configuration. The configuration is read once; a change needs a new start. A variable that starts
   with `LDS_` and sets no key stops the start.
2. It reads the model. A model with no entity type stops the start.
3. It connects to the store of the documents, which makes what it needs there (see [What is stored
   where](#what-is-stored-where)).
4. It connects to the saga log. In PostgreSQL the saga log takes every connection of its pool first.
5. With search on, it makes the search index and the index queue. It does not wait for Solr, so LDS starts when
   Solr is away.
6. It connects to the transaction log.
7. It starts its parts. It begins to complete the writes that its own saga log holds from an earlier run, and from
   then on looks for unfinished writes at intervals (see [Writes that fail, crashes and
   recovery](#writes-that-fail-crashes-and-recovery)). With `search.index.rebuild=true` it begins to rebuild the
   search index (see [Rebuild the search index](#rebuild-the-search-index)).
8. It opens its port. Until now no request is answered, `/ping` included.

LDS has started when the log says `Server started`. Some of the lines before it say what LDS runs with. These
are from a start of LDS as it comes:

```
23:42:42.352 INFO  Managed domains: /Company /Person
23:42:42.352 INFO  Creating specification using GraphQL schema: schemas/schema.graphql
23:42:42.420 INFO  Using persistence provider: mem
23:42:42.433 INFO  Using saga-log-pool provider-class: no.cantara.sagalog.memory.MemorySagaLogInitializer
23:42:42.437 INFO  GraphQL Search is disabled. Skipping search index initialisation
23:42:42.437 INFO  Using transaction-log provider: discard
23:42:42.583 INFO  Started Linked Data Store server. PID 1
23:42:42.583 INFO  Listening on 0.0.0.0:9090
23:42:42.583 INFO  Server started in 395ms..
```

`Managed domains` names the [entity types](../glossary.md#entity-type) of the model. With search on, the line about
search is `Search index provider configured: solr`, and a line says where the index queue is kept. Between these
lines the log names each part as it starts: `Starting Store ...`, `Starting Saga log pool ...` and so on, to
`Starting HTTP server ...`.

### How long LDS waits for an engine

An engine that does not answer at start is tried again:

| Part | Key that sets the longest wait | Default | Pause after a try that failed |
|---|---|---|---|
| The documents | `persistence.initialization.max-wait-seconds` | 60 seconds | 0.5 to 1.5 seconds |
| The saga log | `sagalog.provider.initialization.max-wait-seconds` | 30 seconds | 1 to 3 seconds (`sagalog.provider.initialization.retry-interval-seconds`, 2, give or take half) |
| The transaction log | `txlog.rawdata.provider.initialization.max-wait-seconds` | 30 seconds | 1 to 3 seconds (`txlog.rawdata.provider.initialization.retry-interval-seconds`, 2, give or take half) |

The pause is chosen at random each time, so that several LDS that start together do not try at the same moments.
Each try is one line of the log. Why a try failed is not said until the wait is over. In this run LDS had its
documents, its saga log and its transaction log in PostgreSQL, and PostgreSQL was started six seconds after LDS:

```
23:43:31.053 INFO  Persistence provider initialization attempt # 1
23:43:33.509 INFO  Persistence provider initialization attempt # 2
23:43:35.650 INFO  Persistence provider initialization attempt # 3
23:43:37.217 INFO  Persistence provider initialization attempt # 4
23:43:39.663 INFO  Persistence provider initialization attempt # 5
23:43:42.158 INFO  Persistence provider initialization attempt # 6
23:43:42.250 INFO  Persistence service-provider configured
23:43:42.256 INFO  SagaLogPool provider initialization attempt # 1
23:43:44.134 INFO  SagaLogPool initialized!
23:43:44.136 INFO  Transaction-log provider initialization attempt # 1
23:43:44.161 INFO  Transaction-log initialized
23:43:44.319 INFO  Server started in 13494ms..
```

While LDS waited, a request to `/ping` got no connection.

When the wait is over, LDS stops what it had made, logs `LDS could not be made; what was made is stopped` at the
level `ERROR`, and ends with exit code 1. The cause is printed by the JVM on standard error, as
`Exception in thread "main"` with a stack trace; it is not a line of the log. With
`LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=5` and the name of a PostgreSQL host that was not there, LDS ended
after its tries with:

```
Exception in thread "main" com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize
pool: The connection attempt failed.
```

With Neo4j one try takes long, because the connection to Neo4j tries again by itself inside the try, with a
warning and a stack trace in the log each time. With the default wait and a Neo4j host that was not there, LDS made
two tries and ended after 67 seconds with exit code 1, a log of 2151 lines and
`org.neo4j.driver.exceptions.ServiceUnavailableException: Unable to connect to nowhere-neo:7687, ensure the database
is running and that there is a working network connection to it.` The key does not cut a try short. The waits inside
a try are random, so the time is not the same in every run.

A signal that comes while LDS still waits for an engine ends it at once, with exit code 143 and no line in the
log. Nothing is stopped in order then.

[Configuration](configuration.md#an-engine-that-is-away) has the messages of a start that fails: a wrong
password, a database that is not there, a value that is wrong. Every start that fails ends the process.

## The health addresses

| Address | Answer | What it says |
|---|---|---|
| `/ping` | `200` | The process answers HTTP. Nothing else is asked. |
| `/health/alive` | `200` | The same as `/ping`. |
| `/health/ready` | `200` or `503`, with a JSON body | Whether LDS can take writes: every part a write needs is asked. |

`/health/ready` asks each part side by side, and gives each 2 seconds to answer:

| Check | What is asked | When it fails |
|---|---|---|
| `persistence` | the store of the documents: a read of one document | `DOWN` |
| `sagaLog` | the saga log: the list of its saga logs, from its database | `DOWN` |
| `transactionLog` | the transaction log: its last entry | `DOWN` |
| `search` | with search on, Solr: a query of no rows | `DEGRADED` |
| `indexQueue` | with search on, the index queue: how many updates it holds, and when the oldest was added | `DOWN`; `DEGRADED` when the oldest update has waited more than a minute |

The answer has the state of LDS, the worst state of the checks, and an entry for each check, with the
milliseconds it took. `UP` and `DEGRADED` are answered `200`, `DOWN` `503`: search is not needed for a write, as
the index queue keeps what Solr has not taken. LDS as it comes, in memory:

```console
$ curl -s -i 'http://localhost:9090/health/ready'
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 8},
    "sagaLog": {"status": "UP", "millis": 2, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 0, "keepsMessages": false}
  }
}
```

The stack of the compose file, with search:

```json
{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 6},
    "sagaLog": {"status": "UP", "millis": 0, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 0, "keepsMessages": true},
    "search": {"status": "UP", "millis": 2},
    "indexQueue": {"status": "UP", "millis": 0, "durable": true, "updates": 0}
  }
}
```

- `logs` is the number of saga logs of the cluster, those of every LDS that shares the saga log.
- `keepsMessages` is `false` when no transaction log is kept (`txlog.rawdata.provider=discard`).
- `durable` is `false` when the index queue is in memory; `updates` is the number of updates it holds, and `oldest`,
  there when it holds some, the time the oldest was added.
- A check that failed has `error`: `no answer within 2000 ms`, or the kind of the error. The whole message is in the
  log, at the level `WARN`: `The readiness check search failed: java.net.ConnectException`.

PostgreSQL, which held the documents and the saga log, was paused:

```console
$ curl -s -i 'http://localhost:9090/health/ready'
HTTP/1.1 503 Service Unavailable
Content-Type: application/json; charset=utf-8

{
  "status": "DOWN",
  "checks": {
    "persistence": {"status": "DOWN", "millis": 2007, "error": "no answer within 2000 ms"},
    "sagaLog": {"status": "DOWN", "millis": 2005, "error": "no answer within 2000 ms"},
    "transactionLog": {"status": "UP", "millis": 1, "keepsMessages": false}
  }
}
```

`/ping` and `/health/alive` answered `200` at the same time, and the log had `LDS is DOWN: [...]` with the checks
that failed. A second after PostgreSQL went on again, `/health/ready` answered `200`.

Solr was paused, with an update waiting in the index queue for more than a minute:

```console
$ curl -s -i 'http://localhost:9090/health/ready'
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "status": "DEGRADED",
  "checks": {
    "persistence": {"status": "UP", "millis": 3},
    "sagaLog": {"status": "UP", "millis": 0, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 0, "keepsMessages": false},
    "search": {"status": "DEGRADED", "millis": 2000, "error": "no answer within 2000 ms"},
    "indexQueue": {
      "status": "DEGRADED",
      "millis": 0,
      "durable": false,
      "updates": 1,
      "oldest": "2026-11-24T23:33:28.055609667Z"
    }
  }
}
```

How to use them:

- A liveness probe, one that restarts a process that does not answer, asks `/health/alive`. A process whose
  engines are away answers it, and a restart does not bring the engines back.
- A readiness probe, one that sends requests only to an LDS that can take them, asks `/health/ready`. The compose
  file of the repository asks it.
- `/health/ready` holds no thread while it waits, and answers within about 2 seconds also when an engine does not
  answer at all.
- While LDS stops, all three answer `503`.

[The REST API](rest-api.md#health) has the details of the three addresses.

## Stopping

LDS stops in order when its process gets the signal TERM, which is what `kill <pid>` and `docker stop` send. It
stops its parts in the reverse order of the one they were started in, so that a part stops before what it needs:

1. The HTTP server. Every new request is answered `503 Service Unavailable`. The requests that LDS is handling are
   completed; LDS waits 10 seconds at most for them. A request that is not completed by then loses its connection
   without an answer, also one whose body has not all arrived. Then the port is closed.
2. The readiness checks.
3. The sagas. LDS stops looking for unfinished writes. The writes that are under way are completed; LDS waits 10
   seconds at most for them. A write that is not completed by then fails, and stays in the saga log.
4. The threads of the sagas, given 10 seconds to end and 10 more after they are interrupted; then the saga
   observer and the transaction log.
5. With search on: the rebuild of the search index, if it runs; the sending of the index queue; the index queue;
   and the connection to Solr. An update that is in the index queue in PostgreSQL stays there and is sent after the
   next start.
6. The saga log, the threads the store is read on, and the store of the documents.

The waits have no key. When a wait runs out, the log has one of these lines, at the level `WARN`:
`Requests were still being handled after PT10S. They lose their connection when the HTTP server stops.` or
`Sagas were still running after PT10S. They fail when their thread pool is shut down, and are recovered from their
saga logs.` A part that fails to stop is logged as `... did not stop cleanly`, at the level `ERROR`, and the parts
after it are stopped all the same. LDS has stopped when the log says `Leaving.. Bye!`.

In this run a `PUT` had sent the first 8 bytes of its body when LDS was told to stop. It sent the rest three
seconds later and was answered `201 Created`. A request sent in between:

```console
$ curl -s -i 'http://localhost:9090/ping'
HTTP/1.1 503 Service Unavailable
```

The log of that stop:

```
23:29:07.923 WARN  ShutdownHook triggered..
23:29:10.079 INFO  stopping server: Undertow - 2.4.4.Final
23:29:10.083 INFO  Leaving.. Bye!
```

The process ended with exit code 143, which is the code of a process that was ended by TERM. After that a
request got no connection. An LDS with nothing to complete stopped within a few milliseconds. When the rest of
the body did not come, the request lost its connection 10 seconds after the signal, the log had the line about
requests, and LDS ended right after.

Ctrl-C in the terminal of an LDS on the JDK sends the signal INT. LDS stops in the same order; in a run the
exit code was then 130.

A stop takes longer than the first wait when a write is held up. In two runs LDS had its documents and its saga log
in PostgreSQL, and a write of a document was held up there, by a lock on the table of the documents, when LDS was
told to stop:

- The write had been answered already (it was sent with `sync=false`). LDS waited 10 seconds, logged the line
  about sagas, logged `Pool did not terminate` at the level `ERROR` 20 seconds after that, and ended 30 seconds
  after the signal. The write was in the saga log, and the next start of LDS completed it.
- The caller of the write was still waiting for its answer. It lost its connection 11 seconds after the signal.
  LDS logged both lines and `Pool did not terminate`, and ended 41 seconds after the signal, while the write was
  still held up. The next start of LDS completed the write.

A process that is ended with `kill -9`, or a machine that loses its power, stops nothing in order. In the runs the exit
code was then 137. [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery) tells what happens
to the writes that were under way.

## Logs

LDS writes its log to standard output, one line for each event, and to nowhere else. In a container it is the
log of the container. A line has the date and the time with the offset from UTC (`Z` in the image, which runs
in UTC), the thread, the level, the part of LDS that wrote it, and the message:

```
2026-11-24T23:42:42.583Z [main] INFO  io.descoped.lds.server.Server - Server started in 395ms..
```

- The level is `INFO`, and no configuration key sets it.
- An error is followed by the lines of a stack trace.
- What ends a start is printed by the JVM on standard error and is not a line of the log. The log has
  `LDS could not be made; what was made is stopped` before it.
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
| `LDS could not be made; what was made is stopped` | ERROR | The start failed. The cause follows on standard error. |
| `LDS is DOWN: [...]`, `LDS is DEGRADED: [...]` | WARN | `/health/ready` was asked and a check failed. The checks that failed are in the line. |
| `The readiness check ... failed: ...` | WARN | Why a check of `/health/ready` failed. |
| `The index queue is in memory, as the transaction log (discard) is: ...` | WARN | At start, with search on: what the index queue holds when LDS stops is lost. See [The search index](#the-search-index). |
| `The search index did not take the update of ... (attempt 1); tried again in PT1S: ...` | WARN, ERROR from the tenth attempt | Solr did not take a version. It is sent again later; see [The search index](#the-search-index). |
| `Every version is added to the index queue (...)`, then `The search index is rebuilt from storage: ...` | INFO | A rebuild of the search index; see [Rebuild the search index](#rebuild-the-search-index). |
| `The schema of the search index is put in (...)` | INFO | LDS has put the fields it needs into the Solr core. |
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
| `... did not stop cleanly` | ERROR | A part of LDS failed to stop. The parts after it were stopped all the same. |

The log can hold what the callers send. Two keys make LDS log much more: `http.request.dump` logs every request with its
headers, the header `Authorization` included, and `neo4j.cypher.show` logs every statement sent to Neo4j, with
the values of the documents in it. [Configuration](configuration.md) has both.

In some runs the JVM printed four lines on standard error that start with `WARNING:` and name
`sun.misc.Unsafe::invokeCleaner` and a class of Undertow, the HTTP library of LDS. LDS went on answering.

## What is stored where

### The documents

**In memory** (`persistence.provider=mem`). Nothing is stored. The documents are gone when LDS stops.

Not finished: the memory provider has known faults. One of them stops every read and write of documents;
`/health/ready` then answers that the store is down, while `/ping` and `/health/alive` still answer `200`:
[the GraphQL API](graphql-api.md#reverse-links) tells which query does it. The
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
  and the indexes it reads with, when they are missing. Each one it makes is a line `Creating: ...` in the log.
- LDS never drops an index or a constraint. For one that is there and that it does not use, it writes a line
  `Not using index: ...` to the log, with the statement that drops it.
- A delete of a document adds a tombstone, a version that marks it as deleted. No request to LDS removes a document from
  Neo4j.
- LDS is tested with Neo4j 2026.10, community edition.

### The saga log

**In memory** (the default). A write that is under way when LDS ends is lost.

**In PostgreSQL** (`sagalog.provider=no.cantara.sagalog.postgres.PostgresSagaLogInitializer`).

- The database must be there. LDS does not make it.
- At start LDS makes a schema with the name of `sagalog.config.cluster.owner`, `lds` as LDS comes, and in it one
  table for each saga log and a table `Locks`. A table has the name
  `SAGALOG_<cluster name>_<instance id>_<number>`. With the defaults there are 30 saga logs; in a run with the
  cluster `lds` and the instance `01` the tables were `SAGALOG_lds_01_00` to `SAGALOG_lds_01_29`.
- A write uses one saga log from its start to its end. While it is under way, the table holds the request, the
  document included. When it is done, the table is empty. So a saga log with rows is a write that is under way
  or that failed.
- LDS opens 50 connections at start and keeps them (`sagalog.config.connection-pool.max-size`).
- [Several LDS on one saga log](#several-lds-on-one-saga-log) tells what the saga log keeps, and how it is shared.

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
  it. Before the first write or search that reaches Solr, LDS puts the fields it needs into the schema of the core,
  and tells the core not to make a field of a name it does not know. The log then says `The schema of the search
  index is put in (...)`. The documents of every namespace and every entity type go to that one core.
- Every version of every document is an entry of its own, written once and never changed. A delete is an entry
  too. A search reads the newest entry of each document up to its time; [the GraphQL API](graphql-api.md#search)
  tells how to search.
- A write does not wait for Solr. Its last step adds an update to the index queue, and LDS sends the queue to Solr,
  up to 100 updates at a time, each read from the store at its version. Solr makes it searchable within
  `search.index.commit-within` milliseconds, 1000 as LDS comes. In four runs, with the documents in PostgreSQL, a
  search found a write 1.25 to 1.5 seconds after the write was answered. A write sent with `searchable=true` is
  answered when search finds it; see [the REST API](rest-api.md#when-search-finds-a-write-searchable).
- When Solr does not take an update, it stays in the index queue and is sent again after one second, then after
  twice as long each time, a minute at most. No update is lost that way. The log says so at each attempt; see
  [When an engine is away while LDS runs](#when-an-engine-is-away-while-lds-runs).
- The index queue is kept where the transaction log is. With the transaction log in PostgreSQL it is the table
  `lds_index_queue` of that database, made at start when it is missing; it keeps its updates when LDS stops, and
  several LDS on one database share it. With the transaction log in memory or not kept, the index queue is in
  memory: the updates Solr has not taken when LDS stops are lost, and only a rebuild brings those versions into the
  index. LDS says so in its log at start.
- A document that was written while search was off is not in the index until the index is rebuilt.

### Rebuild the search index

With `search.index.rebuild=true` (the variable `LDS_SEARCH_INDEX_REBUILD=true`), LDS rebuilds the search index from
the store of the documents when it starts: every version of every document of every entity type, the versions of
deleted documents included, is added to the index queue, a delete as a delete, and the queue sends them to Solr.
The index is not cleared first, so search answers while the rebuild runs. LDS reads the store only as fast as the
index queue takes the versions.

When every version is added and the index queue is then empty, Solr removes the entries of the namespace that it
took more than a minute before the rebuild started, by its own clock. So an entry of a version the store no longer
has, of an entity type that is no longer searched, or of an older form of the index, is gone after the rebuild.
An entry of a document that the rebuild sent again is kept. A run, with the documents in PostgreSQL:

```
23:34:53.193 INFO  Rebuilding the search index from storage: [Company, Person] in the namespace ns
23:34:53.193 WARN  search.index.rebuild is on: every start of every LDS with it rebuilds the search index. It is
                   meant for one start of one LDS; turn it off when the rebuild is done
23:34:53.237 INFO  Every version is added to the index queue (12); the entries the search index took before the
                   rebuild are removed when the queue is empty
23:34:54.261 INFO  The search index is rebuilt from storage: 12 versions sent, and the entries it took before the
                   rebuild removed
```

- Turn the key off again after the start: with it on, every start of every LDS that has it rebuilds the index.
- An index queue that is never empty, because writes keep coming, puts the removal off. The log says so every five
  minutes, with the number of updates the queue holds and the time the oldest was added.
- An LDS that is stopped before the rebuild is done logs `The rebuild of the search index was stopped before it
  was done`. What it had added to the index queue is sent; the old entries stay until a rebuild is done.
- When the rebuild fails, the log says `The rebuild of the search index failed, or its clean-up; ...` at the level
  `ERROR`, with the cause.

### Connections to PostgreSQL

| Part in PostgreSQL | Connections of one LDS |
|---|---|
| The documents | 10 |
| The saga log | 50 |
| The transaction log | 10 |
| The index queue, with search on, in the database of the transaction log | 4 |

LDS opens them at start. A PostgreSQL as it comes takes 100 connections, so two LDS with their documents and their
saga log in it do not fit. In a run the second LDS tried for 31 seconds and ended with exit code 1 and
`no.cantara.sagalog.postgres.PostgresSagaLogException: initialize failed on the connection pool: the database gave
the pool 30 of its 50 connections (connection-pool.max-size) and refused more. ...`; the whole message is in
[Configuration](configuration.md#the-saga-log-in-postgresql). It never opened its port. Give PostgreSQL room for
every LDS that uses it (`max_connections`).

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
needed: in a run `/health/ready` answered `200` a second after a paused PostgreSQL went on again. While an engine
that writes need is away, `/health/ready` answers `503`, and `/ping` and `/health/alive` answer `200`.

| What is away | What LDS does |
|---|---|
| The store of the documents (run with PostgreSQL) | A read is answered `500`, at once in the runs. A write is answered `500` after 30 seconds, and is done later if the saga log could note it (see [Writes that fail, crashes and recovery](#writes-that-fail-crashes-and-recovery)). `/health/ready` answers `503`, with `persistence` `DOWN`. |
| The saga log (run with PostgreSQL) | A read works. Every write is answered `500` after 30 seconds, also one sent with `sync=false`, and is not done later. `/health/ready` answers `503`, with `sagaLog` `DOWN`. A write sent after PostgreSQL was started again was done. |
| The store of the documents (run with Neo4j) | A read is answered `500` after about 22 seconds. A write is answered `500` at once, and is done later: the saga log had noted it, and the next round of recovery completed it about 25 seconds after Neo4j was back. `/health/ready` answers `503`, with `persistence` `DOWN` and the error `ServiceUnavailableException`. |
| Solr | A write and a delete of a document are answered as always, and their updates wait in the index queue until Solr takes them. A search gets no answer until Solr answers, a minute at most. `/health/ready` answers `200`, with `search` `DEGRADED`, and with `indexQueue` `DEGRADED` too when an update has waited more than a minute. |

A `500` has no body. The cause is in the log, after the line `UT005071: Undertow request failed ...`. The log
also gets many lines from the connections to PostgreSQL: about 700 lines for 70 seconds without PostgreSQL in
a run.

The run with Solr away: search on, the documents in PostgreSQL, and the container of Solr paused:

```console
$ curl -s -i -X PUT 'http://localhost:9090/ns/Company/down?sync=true' -H 'Content-Type: application/json' \
       -d '{
         "name": "Down",
         "about": [
           {
             "languageCode": "en",
             "languageText": "written while solr slept"
           }
         ]
       }'
HTTP/1.1 201 Created
```

The write was answered in 5 milliseconds. `/health/ready` then answered as [The health
addresses](#the-health-addresses) shows. A minute after the write the log had, at the level `WARN`, one line broken
here:

```
23:34:28.159 WARN  The search index did not take the update of ns/Company/c-down at
                   2026-11-24T23:33:28.052464959Z[Etc/UTC] (attempt 1); tried again in PT1S:
                   java.net.http.HttpTimeoutException: request timed out
```

Solr was let go on, and a second later a search for `slept` found the document. A delete of it, sent with Solr
paused again, was answered `204 No Content`, and once Solr went on, the search no longer found it.

## Several LDS on one saga log

This section is for running the saga log in PostgreSQL, alone or shared by several LDS.

### What the saga log keeps

Every write is a [saga](../glossary.md#saga): a set of steps that LDS runs in order, and notes in the saga log
before and after each one. The first entry of a write holds the request as it was sent, the document included. The
last says that the write is done. An LDS has `saga.number-of-logs` saga logs, 30 as LDS comes, and a write holds one
of them from its first entry to its last. A saga log in PostgreSQL is a table, as [The saga
log](#the-saga-log) tells; a table with rows holds a write that is under way or that did not complete.

### What recovery does after a crash

A write whose last entry is not there was cut off: the process ended, or a step failed. Recovery runs it again,
with the same id and the request of its first entry. A step whose end is in the saga log is not run again; every
other step is, also one that had started and may have been done. So a step can run twice. The version that is
written gets the valid time of the first request, not the time of the recovery. Recovery always goes forward, to
the end of the write; it does not undo the steps that were done.

Each LDS completes the writes in its own saga logs when it starts, and at intervals of 30 to 60 seconds it looks
at the saga logs of every LDS of the cluster. [Writes that fail, crashes and
recovery](#writes-that-fail-crashes-and-recovery) shows a crash and its recovery. With the saga log in memory,
nothing is left to run again after a crash.

### A cluster

Several LDS can share one saga log in PostgreSQL. They are a cluster: every LDS has the same
`sagalog.config.cluster.owner` and `sagalog.config.cluster.name`, and its own
`sagalog.config.cluster.instance-id`. The instance id is in the names of the tables: instance `02` of the cluster
`lds` has the tables `SAGALOG_lds_02_00` to `SAGALOG_lds_02_29`. Each LDS needs the same store of the documents,
in PostgreSQL or Neo4j, so that one can complete what another wrote.

The default of the instance id is `01`, so it must be set for every LDS but one. An LDS holds each of its saga logs
with a lock in PostgreSQL while it runs. In a run two LDS had the id `01`. The second started, its `/health/ready`
answered `200`, and a read through it worked, but every write through it was answered `500` at once: the first
held every saga log of the instance `01`. Its log had, for each write, a line such as:

```
no.cantara.sagalog.SagaLogBusyException: Unable to acquire exclusive lock on PostgresSagaLogId{schema='lds',
table='SAGALOG_lds_01_00'} with lock-key 338747657.
```

The writes refused in this way were not done, nothing was written for them, and the first LDS went on writing.
Started again with `LDS_SAGALOG_CONFIG_CLUSTER_INSTANCE_ID=02`, the second LDS wrote, and its `/health/ready`
said `"logs": 60`, the saga logs of both.

At intervals each LDS looks at the saga logs of the whole cluster:

- The saga logs of an LDS that is running are left alone, as it holds them. In a run with two LDS, each still had
  its 30 tables after more than a minute.
- The saga logs of an LDS that is not running are taken over: their unfinished writes are completed, and their
  tables are removed. In a run instance `01` was ended with `kill -9`; instance `02` removed its 30 tables 64
  seconds later, with a line `Deleted unused empty saga-log: ...` for each. In another run instance `01` was
  ended with a write under way, and instance `02` completed that write 47 seconds later.
- The tables of an LDS that was stopped in order are removed in the same way. The LDS makes them again when it
  starts.
- The saga log `dead-saga` is not looked at. In a run the table `SAGALOG_lds_01_dead-saga` and its rows were
  still there after instance `02` had removed the other tables of `01`.

So the writes of an LDS that is gone are completed by another LDS within about a minute.

### Connections and the pool

A saga log holds one connection to PostgreSQL while it is open, and an LDS keeps its saga logs open. Recovery
opens up to 10 saga logs of other LDS at once, and the pool asks PostgreSQL for the list of saga logs on one more.
So `sagalog.config.connection-pool.max-size` must be at least `saga.number-of-logs` and 11 more: 41 with 30 saga
logs. LDS checks this at start and does not start with fewer; 50 as LDS comes. The saga log takes every connection
of its pool when it starts, so an LDS that does not fit in PostgreSQL ends at start instead of failing writes
later ([Connections to PostgreSQL](#connections-to-postgresql)). Give PostgreSQL room for the pools of every LDS of
the cluster together, with the connections of the store and the transaction log.

### The transaction log of a cluster

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
| The transaction log | A record of every write. LDS runs without it and cannot build the documents from it. It answers `GET /source/{source}`. Keep it if the callers use that address, or if the record matters to you. Its database also holds the index queue, the updates Solr has not taken yet. |
| The search index | It is made from the documents. If it is lost, a start with `search.index.rebuild=true` makes it again from the store of the documents (see [Rebuild the search index](#rebuild-the-search-index)). |
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
  removes entries of the transaction log. The one removal is that of old entries of the search index after a
  rebuild.
- **No key for the level of the log**, and no log file.
- **No encryption or user for Solr, and no option for the connection to the PostgreSQL of the documents**, such as
  TLS. [Configuration](configuration.md) lists what has no key.

As it comes, LDS listens on every address of the machine (`http.host` is `0.0.0.0`). The `docker run` and the
compose file of this document publish its port on localhost only. Who can reach the port, and whether the way
there is encrypted, is decided outside LDS.
