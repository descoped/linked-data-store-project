---
title: "The saga engine"
description: >-
  Cantara distributed saga as LDS uses it: the saga of a write, the saga log, recovery, the cluster, the thread
  pools, how LDS waits for a saga and how it stops them.
order: 12
---

# The saga engine

Linked Data Store (LDS) carries every write with a [saga](../glossary.md#saga), run by the saga engine of Cantara,
[distributed saga](https://github.com/Cantara/distributed-saga) 2.2.0. This document says what a developer of LDS
needs to know of the engine: how LDS defines its saga, what the engine writes to the
[saga log](../glossary.md#saga-log) and reads back, how LDS recovers a saga that did not complete, what the three
names of a cluster do, which threads run a saga, how LDS waits for a saga without holding a thread, how it stops
the sagas, and the limits of the engine that matter to LDS. What the steps do to the stores is in
[the write path](core-write-path.md). For the engine itself, read its own documentation:
[concepts](https://github.com/Cantara/distributed-saga/blob/master/docs/concepts.md),
[architecture](https://github.com/Cantara/distributed-saga/blob/master/docs/architecture.md),
[known limitations](https://github.com/Cantara/distributed-saga/blob/master/docs/limitations.md) and
[design decisions](https://github.com/Cantara/distributed-saga/blob/master/docs/design-decisions.md).

## The libraries

LDS uses these modules of the engine, all of version 2.2.0 (the property `cantara-saga.version` of the root
`pom.xml`). They are not on Maven Central; the root `pom.xml` names the repository they are fetched from.

| Artifact | What LDS takes from it |
|---|---|
| `no.cantara.saga:saga-api` | `Saga`, `SagaNode` and the builder of a saga. |
| `no.cantara.saga:saga-execution` | `SagaExecution`, which runs a saga; `Adapter`, `AdapterLoader` and `AbortSagaException`. |
| `no.cantara.saga:sagalog-api` | `SagaLog`, `SagaLogPool`, `SagaLogInitializer`, and the saga log in memory. |
| `no.cantara.saga:sagalog-postgres` | The saga log in PostgreSQL. The server has it at run time. |
| `no.cantara.saga:sagalog-file` | The saga log in files. In the tests of core only. |
| `no.cantara.concurrent.futureselector:future-selector` | `SelectableThreadPoolExecutor`, the thread pool of the sagas, and the `FutureSelector` of the observer. |

The module descriptor of core requires `no.cantara.saga.api`, `no.cantara.saga.execution`, `no.cantara.sagalog`
and `no.cantara.concurrent.futureselector`, and declares `uses no.cantara.sagalog.SagaLogInitializer`. LDS does not
use `saga-serialization`: its sagas are built in code, and there is no JSON definition of a saga.

## What a saga is in the engine

A saga is a named graph of steps that starts in the step `S` and ends in the step `E`, both of the engine. Every
other step names an adapter, the code that does its work. The engine runs a step when every step before it has
ended, and writes each step to a saga log: a Start entry before it runs, and an End entry after. The first entry of
an execution is the Start of `S`, with the name of the saga and its input; the last is the End of `E`. A saga log
that holds an execution without an End of `E` holds a saga that did not complete, and running it again with the
same input continues it: a step whose last entry is End is not run again, every other step is (forward recovery).
So a step runs at least once, and may run more than once. A step that throws `AbortSagaException` makes the engine
run the compensating actions of the steps before it; any other exception only stops the saga.

## The saga of a write

`SagaRepository` builds two sagas when LDS starts, with the builder of `saga-api`. It is the one place that says
which steps a write has.

| Saga | Used for | Steps |
|---|---|---|
| `Create or update managed resource` | A write of a document, of a part of it or of a link, and the removal of a part or a link: the whole document is written as a new version. | `S`, `txlog`, `persistence`, `index-queue`, `E` |
| `Delete managed resource` | The delete of a document: a [tombstone](../glossary.md#tombstone). | `S`, `txlog`, `persistence`, `index-queue`, `E` |

The steps follow one another, and each starts when the one before it has ended. The step `index-queue` is in the
saga only when LDS has search (`graphql.search.enabled`); without it `persistence` links to `E`.

```mermaid
flowchart LR
    S(["S"]) --> txlog["txlog<br/>the transaction log"]
    txlog --> persistence["persistence<br/>the store"]
    persistence --> queue["index-queue<br/>the index queue,<br/>only with search"]
    queue --> E(["E"])
    persistence -. "without search" .-> E
    style S fill:#e2e8f0,stroke:#64748b,color:#0f172a
    style E fill:#e2e8f0,stroke:#64748b,color:#0f172a
    linkStyle default stroke:#64748b,stroke-width:2px
```

The order is the design: the transaction log has the write before the store is touched, and the
[index queue](../glossary.md#index-queue) gets the update only when the store holds the version, so the worker that
sends the queue to the search index finds the document at that version. Each step names an adapter, registered by
its name in one `AdapterLoader`:

| Step | Adapter of a write | Adapter of a delete |
|---|---|---|
| `txlog` | `AppendTxLogAdapter`, `TxLog-put-entry` | `DeleteTxLogAdapter`, `TxLog-delete-entry` |
| `persistence` | `PersistenceCreateOrOverwriteSagaAdapter`, `Persistence-Create-or-Overwrite` | `PersistenceDeleteSagaAdapter`, `Persistence-Delete` |
| `index-queue` | `UpdateIndexSagaAdapter`, `Persistence-Index-Create-or-Overwrite` | `DeleteIndexSagaAdapter`, `Persistence-Index-Delete` |

Every adapter extends the engine's `Adapter<JsonNode>`, overrides `executeAction`, returns `null` and has no
compensating action. A `null` output is not written to the End entry and is read back as `null`, so no step passes
anything to the next. With no compensating action, an `AbortSagaException` (which the `persistence` step of a write
throws for any failure) undoes nothing: the engine writes an Abort entry for the step and the End of `E`, and the
saga has failed. The saga log of a failed saga keeps its entries, so LDS recovers it as any saga that did not
complete (see [Recovery](#recovery)).

### The input of a step

The input of the saga is one JSON object, `SagaInput`, made by the handler of the request. Every step gets the same
object as its input: the transaction id (`txid`, also the execution id of the saga and the id of the entry in the
transaction log), the method (`PUT` or `DELETE`), the namespace, the entity type, the id, the version (the valid time
in UTC), the `source` and `sourceId` of the request when it has them, and the document (`data`, not for a delete).
The engine writes the input to the Start entry of `S` as JSON text (`toString()` of the tree), and recovery reads it
back from there (`JsonTools.toJsonNode`). So a recovered saga writes the document and the version of the request that
started it, not of the time of the recovery. [The write path](core-write-path.md#the-saga-input) lists the fields.

The name of a saga and the ids of its steps are written to the saga log, and recovery finds the saga by the name in
the Start entry of `S` (`SagaRepository.get`). A saga log written by an earlier build of LDS is read by the next:
renaming a saga makes its unfinished sagas fail recovery, and renaming a step makes recovery run that step again.

## The saga log

A saga log is an append-only log of the entries of the sagas run with it. LDS has a pool of them (`SagaLogPool`),
made at start by the provider whose class `sagalog.provider` names: `UndertowApplication.configureSagaLogProvider`
finds the `SagaLogInitializer` of that class with `ServiceLoader`, and gives its `initialize` every key that starts
with `sagalog.config.`, without that prefix. A failure is tried again for
`sagalog.provider.initialization.max-wait-seconds`, with a wait of about
`sagalog.provider.initialization.retry-interval-seconds` between tries.

The `SagaExecutionCoordinator` registers `saga.number-of-logs` logs of its own instance with the pool, named `00`,
`01` and so on, and makes the id of one more, `dead-saga`, which it does not register, so the pool never hands it out
for a saga. A saga holds one log from its start to its end, and a log holds one saga at a time. When the saga has
completed, the coordinator truncates the log if the saga succeeded, then releases it. So a log that holds entries
holds a saga that did not complete.

| Provider | `sagalog.provider` | Where the logs are |
|---|---|---|
| Memory | `no.cantara.sagalog.memory.MemorySagaLogInitializer` (in `sagalog-api`) | In the memory of the process. The default. The logs are gone when the process ends, so nothing is recovered after a restart. |
| PostgreSQL | `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` (in `sagalog-postgres`) | One table for each log, in a schema of a PostgreSQL database. The logs outlive the process, and every instance of the cluster can read them. |
| File | `no.cantara.sagalog.file.FileSagaLogInitializer` (in `sagalog-file`) | One file for each log, in the folder `filesagalog.folder`. Only the tests of core use it (`application_test.properties`); the server does not have it. |

The engine calls a saga log on the threads of the saga, and every call of the PostgreSQL and the file log does its
I/O on the calling thread: a write of an entry returns when it is stored. This is the design of the engine, which
waits for each entry before it goes on.

### The saga log in PostgreSQL

The provider makes the schema named by `cluster.owner` and, in it, a table `Locks` and one table for each log,
`SAGALOG_<cluster.name>_<cluster.instance-id>_<log name>`, when they are missing. A log is opened when it is first
taken, and stays open with a connection of its own, which holds an advisory lock on the log, so one process at a
time writes a log. The recovery at start (on by default) takes every log of the instance, so all of them are open
after a start.
Started with its saga log in PostgreSQL and nothing else set but the host and the password of the database, LDS
made these tables:

```console
$ psql -U lds -d sagalog -c '\dt lds.*'
                 List of tables
 Schema |          Name          | Type  | Owner
--------+------------------------+-------+-------
 lds    | Locks                  | table | lds
 lds    | SAGALOG_lds-test_01_00 | table | lds
 lds    | SAGALOG_lds-test_01_01 | table | lds
 ...
```

Thirty logs (the default of the server) and `Locks`, in the schema `lds`, the default owner of core. The connection
pool of the provider (`sagalog.config.connection-pool.max-size`, 50 in the defaults of the server, 10 in the provider)
holds all of its connections while LDS runs: the provider takes them all at start, and ends the start when the
database does not give them. LDS checks the size of the pool before that, without a connection: the pool needs one
connection for each of its logs, one for each of the 10 logs of other instances that recovery opens at once, and
one for its own queries (`UndertowApplication.checkSagaLogPool`). Run with 30 logs and a pool of 40, LDS did not start:

```
Exception in thread "main" java.lang.IllegalStateException: The saga log has 40 connections in its pool
(sagalog.config.connection-pool.max-size) for 30 saga logs (saga.number-of-logs): each open saga log holds a
connection, recovery opens up to 10 logs of other instances at once, and the pool needs one for its own queries.
Give the pool at least 41 connections, or use fewer saga logs.
```

A database that has room for one pool of 50 and not for two refuses a second LDS: run against the PostgreSQL of a
running LDS, the second ended its start with `PostgresSagaLogException: initialize failed on the connection pool:
the database gave the pool 36 of its 50 connections (connection-pool.max-size) and refused more`, and the numbers of
the database. The provider reads more keys than the defaults files have (TLS, the timeouts of a statement, of a
lock, of a socket); [the write path](core-write-path.md#configuration) lists them.

## The cluster: owner, name and instance id

Several LDS that share one saga log in PostgreSQL are a cluster: each recovers the sagas the others did not
complete. Three keys, each under `sagalog.config.`, say which cluster an LDS belongs to and which
[cluster instance](../glossary.md#cluster-instance) it is.

| Key | Default of core | Memory | PostgreSQL | File |
|---|---|---|---|---|
| `cluster.owner` | `lds` | Not read. | The schema of the tables. Letters, digits, `_` and `-`. | Not read. |
| `cluster.name` | `lds-test` | Not read. | Part of the name of every table. The tables of the schema whose names start with `SAGALOG_<cluster.name>_` are the logs of the cluster. Letters, digits and `-`. | Not read. |
| `cluster.instance-id` | `01` | The instance of every log id. The logs of the cluster are the logs of this instance. | Part of the name of every table of the instance. Letters, digits and `-`. | Part of the name of every file of the instance; the files of the folder are the logs of the cluster. |

The PostgreSQL provider checks the three names before it opens a connection, because they become SQL identifiers.
The instance id names the `dead-saga` log too. The compose file of the server sets the owner `development`, the name
`lds` and the instance id `01`.

Each LDS of a cluster needs an instance id of its own. Every LDS that is not told otherwise has `01`, and a second
LDS with the same id as a running one finds every log of its instance held by the first: its writes fail. Run with
two LDS on one saga log in PostgreSQL, both with the instance id `01`, a write to the second was answered `500`, and
its log said:

```
no.cantara.sagalog.SagaLogBusyException: Unable to acquire exclusive lock on
PostgresSagaLogId{schema='lds', table='SAGALOG_lds-test_01_00'} with lock-key 829871626.
```

Not finished: the instance id is not derived from anything that differs between processes, so an LDS started a
second time beside a running one, as an autoscaler does, cannot write until it is given an id of its own.

## Recovery

[Saga recovery](../glossary.md#saga-recovery) runs a saga that did not complete again from its saga log. The engine
does not look for such sagas: LDS finds them (`SagaExecutionCoordinator`) and calls `SagaExecution.executeSaga` again
with `recovery = true`, the execution id and the input of the Start entry of `S`. The engine then skips every step
whose last entry is End and runs the others. A log that has been recovered is truncated. There are three occasions:

| When | Which logs | Started by |
|---|---|---|
| A write is handed a log that holds entries | That log | `SagaExecutionCoordinator.handoff`, always. The log is given to recovery on the recovery thread pool, and the write asks for another log. |
| LDS starts | The logs of the instance (`completeLocalIncompleteSagas`) | The lifecycle of the sagas, when `saga.recovery.enabled` is true. |
| Again and again | The logs of the cluster, without the `dead-saga` logs (`completeClusterWideIncompleteSagas`) | `SagaRecoveryTrigger`, started when the recovery at start is done, when `saga.recovery.enabled` is true. It waits a random time from `saga.recovery.interval.seconds.min` to `.max` between passes. |

What each saga log does for the cluster follows from the table of the cluster: in memory the logs of the cluster
are the logs of the instance, so nothing of another instance is recovered, and nothing at all after a restart; in
PostgreSQL the timer of each LDS also takes the logs of the other instances, when no process holds them. A log of
another instance that is empty, or whose sagas are recovered, is removed from the pool and its table dropped
(`SagaLogPool.delete`). A log that another process holds (`SagaLogBusyException`) is left to it.

A recovery that fails does not end the same way on every occasion. Started by a write, every entry of the log is
moved to the instance's `dead-saga` log (the [dead-saga log](../glossary.md#dead-saga-log)), and the log is
released empty. Started at start or by the timer, the log is released with its entries, and the next pass meets them
again. Nothing reads the `dead-saga` log. [The write path](core-write-path.md#recovery-of-an-unfinished-saga) shows
each with a run.

## The threads

| Pool | Threads | What runs there |
|---|---|---|
| The saga thread pool | `sec-1`, `sec-2`, ...: a `SelectableThreadPoolExecutor` of `saga.threadpool.core` to `saga.threadpool.max` threads, a queue of `saga.threadpool.queue.capacity` and `AbortPolicy` | Every step of every saga, its writes to the saga log, and the truncate and release of its log when it completes. Also the sagas of recovery. |
| The recovery thread pool | `saga-recovery-1` to `saga-recovery-10` | The recovery of one saga log at a time each: reading the log and starting its sagas, which then run on the saga thread pool. |
| The recovery trigger | `saga-recovery-trigger-N` | The timer. |
| The observer | `saga-observer` | Waits for every saga to complete and logs one that failed. |
| The watchdog | `Saga-Threadpool-Watchdog` | Started by `Server.main` (`enableSagaExecutionAutomaticDeadlockDetectionAndResolution`). It looks at the saga thread pool every second and makes it grow when every thread is busy and nothing has completed since the last look. |

The engine starts no thread of its own. A step runs on a thread of the saga thread pool from its Start to its End,
and it may block there: the steps of LDS wait for the transaction log, for the store (`WriteTransaction`) and for the
index queue, and the saga log waits for its database. This is on purpose. The pool is bounded, so its threads cap
how many steps run at once: when a store slows down the sagas slow down with it, and when the pool and its queue are
full a new saga is refused at once (`RejectedExecutionException`) instead of piling up work on the store. An
asynchronous step would only move the wait of a blocking client to a pool without a bound. The rule is that blocking
belongs on this pool and nowhere else: never on an I/O thread, a request thread or an Rx scheduler.

Since 2.2.0 a step that waits for several steps before it holds no thread, so a pool smaller than the sagas makes them
slower, not stuck. The sagas of LDS have one step after another and no such join. Two more limits are LDS's own:

- **Permits.** `handoff` takes a permit of a semaphore before it starts a saga, and the saga gives it back when it
  completes. There are `(saga.threadpool.max + saga.threadpool.queue.capacity) / 2` of them. The sagas of recovery
  take none.
- **Saga logs.** A saga holds a log from its start to its end, so `saga.number-of-logs` sagas run at once at most.

With the defaults of the server (30 logs, core 50, max 100, queue 10) there are 55 permits and 30 logs: the logs are
the limit met first. [The write path](core-write-path.md#the-thread-pools-and-their-limits) says what happens at each.

## The observer

`SagasObserver` keeps the completion of every saga, started by a write or by recovery, in a `FutureSelector`, and its
thread `saga-observer` takes each as it completes. A saga that failed is logged as an error, with its name and its
execution id: `Observed Saga failure during execution. Saga-name: "Create or update managed resource", executionId:
"..."`. A saga that succeeded is logged at the level trace. The observer only logs: the answer to the client and the
release of the saga log do not wait for it.

## How LDS waits for a saga

`SagaExecutionCoordinator.handoff` starts a saga and gives back one of the two futures of the engine: the completion
future, when the request waits for the saga (`sync`, true unless `sync=false`), or the handoff future, which
completes when the Start entry of `S` is in the saga log. The handler does not wait for either on its thread:

1. `handoff` gives the completion future of the saga to `DocumentLocks.holdUntil`, so the
   [document lock](../glossary.md#document-lock) of the write is kept until the saga has completed, also when the
   request does not wait for it.
2. `AsyncAnswer.afterSaga` keeps the request open (Undertow's `dispatch` with `SameThreadExecutor`), and the handler
   returns: no thread waits.
3. When the future completes, and the [searchable write](../glossary.md#searchable-write) has waited for search
   when the request asked for it (`SearchWait`), the answer is sent from the thread that completed it: a thread of the
   saga thread pool, or the thread that ended the wait for search. A saga that failed is answered `500` with no body.

```mermaid
sequenceDiagram
    participant H as handler (request thread)
    participant C as SagaExecutionCoordinator
    participant P as saga thread pool (sec-N)
    participant L as saga log
    H->>C: handoff(sync, saga, input)
    C->>L: take a saga log
    C->>P: executeSaga
    C-->>H: the completion future (or the handoff future)
    Note over H: dispatch, keep the request open, return
    P->>L: Start of S, then Start and End of each step
    P->>L: End of E, truncate, release the log
    P-->>H: the future completes: the answer is sent
```

Not finished: taking a saga log and a permit still waits on the request thread. `handoff` asks the pool for a log
and waits up to 10 seconds for one (`SagaLogPool.tryAcquire` with a timeout; the engine's pool has no other way),
and then waits for a permit without a time limit. With logs and permits free neither waits.

## How LDS stops the sagas

The sagas are a [lifecycle](../glossary.md#lifecycle) of their own, `Sagas` (`UndertowApplication.sagas`). It is made
after the saga thread pool, the observer, the transaction log, the index queue and the saga log pool, so it stops
before them, and after the HTTP server has stopped taking requests:

1. The recovery stops: the trigger and the recovery thread pool are shut down (`SagaRecoveryTrigger.stop`), or the
   recovery thread pool alone when recovery is off.
2. The running sagas are waited for, at most 10 seconds (`awaitRunningSagas`). Each still has its thread pool, its
   saga log and the stores it writes to.
3. The coordinator stops its watchdog (`shutdown`).

Then the saga thread pool is shut down: it takes no new step, waits up to 10 seconds for the steps that run, and then
interrupts them (`shutdownNow`). A saga that is interrupted or refused fails, its saga log keeps what it did, and
recovery runs it again, after a restart when the log is in PostgreSQL. The observer stops, and later the saga log
pool is shut down: after that it gives out no log (`SagaPoolShutdownException`, which the trigger takes as the stop
and not as a fault).
[The application](core-application.md#stop) has the whole order.

## The readiness of the saga log

`/health/ready` asks the saga log for the list of the logs of the cluster (`SagaLogPool.clusterWideLogIds`), on a
virtual thread of its own, within 2 seconds. In PostgreSQL that is a query of the tables of the schema; in memory the
list of the instance's logs. The entry `sagaLog` has the number of logs, and LDS is `DOWN` when the saga log does not
answer in time or fails. Asked of an LDS with its saga log and its transaction log in PostgreSQL, and search:

```sh
curl -s http://localhost:19104/health/ready
```

```json
{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 18},
    "sagaLog": {"status": "UP", "millis": 9, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 21, "keepsMessages": true},
    "search": {"status": "UP", "millis": 6},
    "indexQueue": {"status": "UP", "millis": 7, "durable": true, "updates": 0}
  }
}
```

Not finished: the check asks only whether the saga log answers. An LDS with no saga log a write can take
(`saga.number-of-logs=0` was run) is `UP`, and each write is answered `500` after 10 seconds.

## The limits that matter to LDS

These are limits of the engine, each with what LDS does about it. The engine's
[known limitations](https://github.com/Cantara/distributed-saga/blob/master/docs/limitations.md) have the detail.

- **At least once.** A step whose End did not reach the saga log is run again by recovery, so every step must give
  the same result the second time. The store replaces a version written at the same valid time; the transaction log
  takes the same message again as nothing new and refuses another message under the same id (`TxLogStepTest`); the
  index queue gets the update twice, and the worker sends the same entry twice.
- **Only an abort rolls back, and LDS has nothing to roll back.** No step has a compensating action. Any failure,
  an abort or not, leaves the saga log for forward recovery.
- **No timeouts.** The engine has no timeout on a step or on the saga log. A step that never returns holds its
  thread, its saga, its saga log and the lock of its document; the next writes of that document are answered `503`
  after their wait of 30 seconds. The PostgreSQL saga log has no timeout of a statement, a lock or a socket unless
  one is configured (the keys are in [the write path](core-write-path.md#configuration)).
- **A late rerun.** Recovery can run a saga after newer writes of the same document. It writes the version of the
  request that started it, which is its valid time, so it replaces that version only.
- **The saga log is read whole.** A recovery reads every entry of a log into memory. LDS truncates a log when its saga
  has succeeded, so a log holds one saga at most, and the entries of the sagas that failed.
- **The rejection policy.** A policy that runs a task on the thread that submits it cannot run a saga. LDS uses
  `AbortPolicy`: a saga the full pool refuses fails at once and gives back its log and its permit.
- **A saga left in the queue at the stop.** The saga thread pool is shut down with `shutdownNow` when it has not
  ended within 10 seconds, and the tasks it gives back are not cancelled, so a saga whose next step was still queued
  then does not complete. Its saga log keeps it.

## Rules to keep

- **The steps stay synchronous, on the saga thread pool.** A step may block there and nowhere else.
- **A step can be run again.** It must give the same result the second time.
- **The names in the saga log stay.** The name of a saga, the ids of its steps and the fields of the saga input are
  read back by recovery, by another instance of the cluster and by the next build.
- **A saga log is released on every path,** after its truncate has finished, and by the owner that took it. A log
  that stays taken is lost to every write after.
- **The pool rejects; it never runs a task on the submitting thread.** Keep `AbortPolicy`.
- **The sagas stop before what they need.** A new subsystem a step uses is made before the lifecycle `Sagas`, so it
  stops after it.
