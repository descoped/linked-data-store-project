# Writes that fail, crashes and recovery

What happens to a write when LDS or a store fails, and when it is done later. Use it to answer "was my write
done?" and "what happens if LDS crashes?". Sending writes is `lds:data`.

## What a write goes through

Every write (a document, a part, a link, a delete) is a saga, a short list of steps that LDS notes in the saga log
as it does them:

1. an entry in the transaction log;
2. the document written to (or marked deleted in) the store of the documents;
3. with search on, an update added to the index queue.

The steps run in that order. A step that was done is not undone when a later one fails. A saga that does not
complete stays in the saga log and is run again later with the request as it was first sent, the same document and
the same valid time (forward recovery). The search index is not a step: a worker of LDS sends the updates of the
queue to Solr, reads each document from the store at its version, and keeps an update Solr does not take, trying
it again (after one second, doubled each time, at most a minute apart). So no write fails because of Solr, and no
update of the index is lost.

Writes of one document are done one at a time, in the order they came; writes of different documents run side
by side.

The answer to a write is `{"saga-execution-id": "..."}`, the id of the saga and of its transaction log entry (a
delete of a whole document LDS waited for is `204` with no body). Every write, of a document, a part or a link, is
answered when every step has ended, unless it says `sync=false`: then it is answered as soon as the saga is in the
saga log, and a `2xx` means "noted", not "done". A write with `searchable=true` is answered when search finds it as
well, at most 10 seconds later, with the header `LDS-Searchable: true` or `false` (`lds:data`).
[Concepts: What a write goes through](https://lds.descoped.io/docs/user/concepts/#what-a-write-goes-through).

## When a write is run again

- **When LDS starts**, for the writes in its own saga log. This needs the saga log in PostgreSQL; in memory it is
  gone with the process.
- **At intervals**, for every saga log of the cluster: every 30 to 60 seconds, chosen anew each time
  (`saga.recovery.interval.seconds.min` and `.max`). One such look is a round.
- **When a later write is given the same saga log.** There are 30 saga logs, given out in turn.

The first two need `saga.recovery.enabled=true`, the default.

## What an answer tells the caller

A `500` or a lost connection does not say whether the write will be done:

| Situation | Answer | Done later? |
|---|---|---|
| Neo4j (documents) away | `500` at once; `201` with `sync=false` | Yes, if the saga log is kept: in a run the next round after Neo4j came back did both. |
| PostgreSQL (saga log, transaction log, index queue) away | `500` after 30 seconds, also with `sync=false` | No: LDS could note it nowhere. |
| Solr away | `201`, a delete `204`; the document is stored | Yes: the update waits in the index queue and is sent when Solr is back. |
| LDS crashed (`kill -9`, power, a stop that ran out of time) | no answer | Yes with the saga log in PostgreSQL (at the next start, or by another LDS of the cluster within about a minute). No with the saga log in memory. |

So tell callers: **a write that got no `2xx` is sent again**. Every call of the HTTP API is idempotent and safe to
send again, and a write with a `timestamp` writes exactly the same version again. A write without a `timestamp`
gets the time it arrives as valid time, so sending it again adds a version.

## A crash, with the saga log in PostgreSQL

In a run a write was held up in PostgreSQL and LDS was killed. The saga log held four rows (the start of the saga
with the request, the transaction log step started and ended, the document step started); the document was not
stored. At the next start:

```
INFO  Starting saga-recovery (instance-local) ...
INFO  Started recovery of saga with sagaLog: PostgresSagaLogId{schema='development',
      table='SAGALOG_lds_01_00'} and executionId: 01a1f9f0-97cc-73b6-8c5d-7d0cf461b182
INFO  Recovery of saga succeeded, sagaLog: PostgresSagaLogId{schema='development',
      table='SAGALOG_lds_01_00'}, executionId: 01a1f9f0-97cc-73b6-8c5d-7d0cf461b182
INFO  Server started in 1507ms..
```

The document was then there, with the valid time of the first request.

## A write that keeps failing: dead-saga

A write whose cause does not go away fails at every round (`Recovery of saga failed, ...`). Not finished in LDS:
when a later write is handed its saga log and it fails once more, LDS gives it up. It moves its rows to a saga log
named `dead-saga` (in PostgreSQL the table `SAGALOG_<cluster>_<instance>_dead-saga`) and logs `Failed to retry
saga-executions from saga-log ..., moving all entries to dead-saga-log.` Nothing in LDS reads that log: the write
is not done, and LDS says so nowhere else. Many can be given up at once.

To see what is under way, waiting or given up, run the query of this skill against the saga log database, with
the cluster owner as `owner`:

```sh
docker compose exec -T postgres \
  psql -U lds -d sagalog -v owner=development < scripts/sagalog-entries.sql
```

```
     saga_log      | entries
-------------------+---------
 SAGALOG_lds_01_04 |       8
(1 row)
```

That row was one write that failed while Neo4j was away; it was gone after recovery. A saga log whose name ends in
`dead-saga` holds writes that were given up. Its rows hold the requests, for a person to read with `psql`; have the
callers send those writes again, which is safe.

## When an engine is away while LDS runs

LDS keeps running, does not end, and connects again by itself when the engine is back. No restart:

These ran on the full stack of `lds:start` (Neo4j for the documents; PostgreSQL for the saga log, the transaction
log and the index queue):

| Away | Reads | Writes | `/health/ready` | Back |
|---|---|---|---|---|
| Neo4j | `500` (the first after 30 s) | `500` at once, done later | `503`, `persistence` `DOWN` | `200` 7 s after Neo4j started; the failed writes done at the next round |
| PostgreSQL | work | `500` after 30 s, not done later | `503`, `sagaLog`, `transactionLog`, `indexQueue` `DOWN` | `200` 12 s after PostgreSQL started; the next write works |
| Solr | work, search answers `200` with an error in its body | `201`, wait in the index queue | `200`, `DEGRADED` | the queue sends what waited; search found every write |

## Several LDS on one saga log

Several LDS can share one saga log in PostgreSQL, one store of documents (PostgreSQL or Neo4j) and one Solr, as a
cluster. Each needs:

- the same `sagalog.config.cluster.owner` and `sagalog.config.cluster.name`;
- its own `sagalog.config.cluster.instance-id` (the default `01` is the same for all; two with one id make every
  write through the second `500` with `Unable to acquire exclusive lock on ...`);
- its own transaction log: its own `txlog.rawdata.topic-prefix` when they share PostgreSQL (`txlog-02-`), because
  only one LDS can write one transaction log (not finished in LDS);
- room in PostgreSQL: the full stack's LDS holds 64 of its 100 connections, and a second LDS with the defaults
  (64 more) ends at start with `PostgresSagaLogException: initialize failed on the connection pool: ...`. Raise
  `max_connections` of PostgreSQL, or give the second LDS fewer saga logs and a smaller pool, as below.

The index queue in PostgreSQL is shared: each update is sent by one of them. A second LDS joined the compose stack
with these variables; a write through it was answered `201` and read through the first, a write through the first
was read through it, and search found both:

```sh
docker run -d --name lds-02 --network lds_default -p 127.0.0.1:9091:9090 \
  -e LDS_PERSISTENCE_PROVIDER=neo4j -e LDS_NEO4J_DRIVER_URL=bolt://neo4j:7687 \
  -e LDS_NEO4J_DRIVER_PASSWORD=lds-development \
  -e LDS_SAGALOG_PROVIDER=no.cantara.sagalog.postgres.PostgresSagaLogInitializer \
  -e LDS_SAGALOG_CONFIG_CLUSTER_OWNER=development -e LDS_SAGALOG_CONFIG_CLUSTER_NAME=lds \
  -e LDS_SAGALOG_CONFIG_CLUSTER_INSTANCE_ID=02 \
  -e LDS_SAGA_NUMBER_OF_LOGS=5 -e LDS_SAGALOG_CONFIG_CONNECTION_POOL_MAX_SIZE=16 \
  -e LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_HOST=postgres -e LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_PASSWORD=lds \
  -e LDS_TXLOG_RAWDATA_PROVIDER=postgres -e LDS_TXLOG_RAWDATA_TOPIC_PREFIX=txlog-02- \
  -e LDS_TXLOG_CONFIG_POSTGRES_DRIVER_HOST=postgres -e LDS_TXLOG_CONFIG_POSTGRES_DRIVER_PASSWORD=lds \
  -e LDS_GRAPHQL_SEARCH_ENABLED=true -e LDS_SEARCH_INDEX_URL=http://solr:8983/solr/lds-index \
  descoped/lds:3.0.1
```

(`lds_default` is the network of the compose project `lds`; the passwords are the development defaults. Five saga
logs and a pool of 16 fit in the room the first LDS leaves; with a PostgreSQL of more connections, leave the two
variables out.)

What the cluster does:

- Each LDS completes its own unfinished writes when it starts.
- At each round each LDS takes over the saga logs of an LDS that is not running: completes their writes and
  removes their tables. In a run instance `02` completed a write of a killed `01` within 47 seconds.
- The saga logs of a running LDS are left alone, and `dead-saga` is never looked at.
- Each LDS has its own transaction log, so `GET /source/{source}` answers for the writes of the LDS asked.
- Mind the connections: 64 to 74 for each LDS with the defaults, in a PostgreSQL that takes 100 as it comes.

An LDS with its documents in memory shares them with no one; a cluster needs PostgreSQL or Neo4j.

[Running LDS: Several LDS on one saga
log](https://lds.descoped.io/docs/user/operations/#several-lds-on-one-saga-log).
