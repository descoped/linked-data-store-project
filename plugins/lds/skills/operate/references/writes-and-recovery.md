# Writes that fail, crashes and recovery

What happens to a write when LDS or a store fails, and when it is done later. Use it to answer "was my write
done?" and "what happens if LDS crashes?". Sending writes is `lds:data`.

## What a write goes through

Every write (a document, a part, a link, a delete) is a saga, a short list of steps that LDS notes in the saga log
as it does them:

1. an entry in the transaction log;
2. the document written to (or marked deleted in) the store of the documents;
3. the search index updated, when search is on.

Step 1 comes first; 2 and 3 follow side by side. A step that was done is not undone when a later one fails. A saga
that does not complete stays in the saga log and is run again later with the request as it was first sent, the
same document and the same valid time (forward recovery).

The answer to a write is `{"saga-execution-id": "..."}`, the id of the saga and of its transaction log entry. A
write that LDS waits for (a synchronous write: a whole document by default, or any write with `sync=true`) is
answered when every step has ended. Any other write is answered as soon as the saga is in the saga log: a `2xx`
then means "noted", not "done". [Concepts: What a write goes
through](https://lds.descoped.io/docs/user/concepts/#what-a-write-goes-through).

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
| Neo4j (documents) away | `500` at once | Yes, if the saga log is kept: in a run the next round after Neo4j came back did it. |
| PostgreSQL (documents) away | `500` after 30 seconds | Yes, by a round after PostgreSQL is back, if the saga log could note it. |
| PostgreSQL (saga log) away | `500` after 30 seconds, also with `sync=false` | No: LDS could note it nowhere. |
| Solr away | `201`; the document is stored, not indexed | The document yes; the index entry never. A delete is `500`, the document is deleted, and the index step keeps failing. |
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

| Away | Reads | Writes | `/health/alive` | Back |
|---|---|---|---|---|
| Neo4j | `500` (the first after about 20 s) | `500` at once, done later | `503` at once | `200` 4 s after Neo4j started; the failed write done at the next round |
| PostgreSQL with the documents | `500` at once | `500` after 30 s, done later if noted | `503`, after up to 30 s | `200` within 3 s |
| PostgreSQL with the saga log | work | `500` after 30 s, not done later | `200` | the next write works (the first took about 7 s) |
| Solr | work, search answers `200` with an error in its body | `201`, not indexed; a delete `500` | `200` | new writes are indexed; earlier ones only when written again |

## Several LDS on one saga log

Several LDS can share one saga log in PostgreSQL and one store of documents (PostgreSQL or Neo4j), as a cluster.
Each needs:

- the same `sagalog.config.cluster.owner` and `sagalog.config.cluster.name`;
- its own `sagalog.config.cluster.instance-id` (the default `01` is the same for all; two with one id make every
  write through the second `500` with `Unable to acquire exclusive lock on ...`);
- its own transaction log: its own `txlog.rawdata.topic-prefix` when they share PostgreSQL (`txlog-02-`), because
  only one LDS can write one transaction log (not finished in LDS).

A second LDS joined the compose stack with these variables, and writes through both were answered `201` and read
through either:

```sh
docker run -d --name lds-02 --network lds_default -p 127.0.0.1:9091:9090 \
  -e LDS_PERSISTENCE_PROVIDER=neo4j -e LDS_NEO4J_DRIVER_URL=bolt://neo4j:7687 \
  -e LDS_NEO4J_DRIVER_PASSWORD=lds-development \
  -e LDS_SAGALOG_PROVIDER=no.cantara.sagalog.postgres.PostgresSagaLogInitializer \
  -e LDS_SAGALOG_CONFIG_CLUSTER_OWNER=development -e LDS_SAGALOG_CONFIG_CLUSTER_NAME=lds \
  -e LDS_SAGALOG_CONFIG_CLUSTER_INSTANCE_ID=02 \
  -e LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_HOST=postgres -e LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_PASSWORD=lds \
  -e LDS_TXLOG_RAWDATA_PROVIDER=postgres -e LDS_TXLOG_RAWDATA_TOPIC_PREFIX=txlog-02- \
  -e LDS_TXLOG_CONFIG_POSTGRES_DRIVER_HOST=postgres -e LDS_TXLOG_CONFIG_POSTGRES_DRIVER_PASSWORD=lds \
  descoped/lds:3.0.0
```

(`lds_default` is the network of the compose project `lds`; the passwords are the development defaults.)

What the cluster does:

- Each LDS completes its own unfinished writes when it starts.
- At each round each LDS takes over the saga logs of an LDS that is not running: completes their writes and
  removes their tables. In a run instance `02` completed a write of a killed `01` within 47 seconds.
- The saga logs of a running LDS are left alone, and `dead-saga` is never looked at.
- Each LDS has its own transaction log, so `GET /source/{source}` answers for the writes of the LDS asked.
- Mind the connections: 70 per LDS in one PostgreSQL, which takes 100 as it comes.

An LDS with its documents in memory shares them with no one; a cluster needs PostgreSQL or Neo4j.

[Running LDS: Several LDS on one saga
log](https://lds.descoped.io/docs/user/operations/#several-lds-on-one-saga-log).
