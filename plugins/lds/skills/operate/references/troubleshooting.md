# Troubleshooting LDS

Symptom, cause, what to do. Run `sh scripts/health.sh <address> <container>` first: most rows start from what it
prints. When LDS itself fails to start, the container log ends with the JVM's `Exception in thread "main" ...`;
the cause is in that line. Ask before you stop or restart anything.

## Contents

- LDS does not start
- The container runs and is not healthy
- LDS answers, but requests fail
- A write seems lost, or happens late
- Slow or stuck
- Messages in the log

## LDS does not start

The container exits (exit code `1`) or keeps restarting, and the log has no `Server started`.

| Symptom (in the log or from Docker) | Cause | What to do |
|---|---|---|
| `docker: Error response from daemon: ... Bind for 127.0.0.1:9090 failed: port is already allocated` | Another container or program has the port on the machine. Docker created the container and did not start it. | Remove the container that was created (`docker rm <container>`), then publish another port on the machine (`-p 127.0.0.1:9190:9090`), or stop what has the port. With the compose file: `SERVER_PORT=9190 docker compose ... up`. |
| `java.lang.RuntimeException: java.net.BindException: Address already in use` | LDS run outside a container, and the port of `http.port` is taken. | Set another port (`LDS_HTTP_PORT=9190`) or stop what has it. In a container move the port on the machine, not inside. |
| `... initialization attempt # 2`, `# 3`, ... and then `Exception in thread "main"` | LDS waited for an engine at start and gave up: 60 seconds for the documents, 30 for the saga log and for the transaction log. The cause of the last try is in the exception, see the rows below. | Make the engine reachable, then start LDS again. Start the engine before LDS (the compose file waits for each engine to be healthy). A longer wait: `LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=120`. |
| `HikariPool$PoolInitializationException: Failed to initialize pool: Connection to <host>:<port> refused` | PostgreSQL is not running there, or not on that port. | Start PostgreSQL, or correct `..postgres.driver.host` and `..port`. |
| `Failed to initialize pool: The connection attempt failed.` | The host name of PostgreSQL does not resolve, for example a compose service that is stopped. | Start the engine's container, or correct the host. |
| `Failed to initialize pool: FATAL: password authentication failed for user "lds"` | Wrong PostgreSQL password. | Correct `postgres.driver.password`, `sagalog.config.postgres.driver.password` or `txlog.config.postgres.driver.password`: the stack trace says which part. Each part has its own key. |
| `Failed to initialize pool: The server requested SCRAM-based authentication, but the password is an empty string.` | No password given. No password key has a default. | Give the password of that part. |
| `Failed to initialize pool: FATAL: database "nosuch" does not exist` | LDS makes no database. | Make it in PostgreSQL first, or correct `..driver.database`. |
| `org.neo4j.driver.exceptions.AuthenticationException: The client is unauthorized due to authentication failure.` | Wrong Neo4j password. | Correct `neo4j.driver.password` (and `neo4j.driver.username`). |
| `org.neo4j.driver.exceptions.SecurityException: The client has provided incorrect authentication details too many times in a row.` | Wrong Neo4j password, tried several times: Neo4j locks the user for a short while. | Correct the password, wait a few seconds, start again. |
| `org.neo4j.driver.exceptions.ServiceUnavailableException: Unable to connect to ...` | Neo4j is away. Each try takes half a minute, so this start took a minute and logged over a thousand lines. | Start Neo4j, or correct `neo4j.driver.url` (an address that starts with `bolt://`). |
| `IllegalArgumentException: schema (cluster.owner) must match [A-Za-z0-9_-]+: 'my.org'` | `sagalog.config.cluster.owner` has a character the saga log in PostgreSQL does not take. | Letters, digits, `_` and `-`, or leave it out: its default is `lds`. |
| `IllegalArgumentException: namespace (cluster.name) must match [A-Za-z0-9-]+: ...` | A cluster name with other characters, such as `_`. | Letters, digits and `-` only. |
| `PostgresSagaLogException: initialize failed on the connection pool: the database gave the pool 20 of its 50 connections ... FATAL: sorry, too many clients already`, after 30 seconds | PostgreSQL is full: the saga log takes its whole pool at start. Two LDS with everything in one PostgreSQL need 140 of its 100. | Raise `max_connections` of PostgreSQL, or give each LDS its own PostgreSQL. |
| `The saga log has 30 connections in its pool (sagalog.config.connection-pool.max-size) for 30 saga logs (saga.number-of-logs): ... Give the pool at least 41 connections, or use fewer saga logs.` | The pool of the saga log is too small for the number of saga logs. | Keep the pool at least `saga.number-of-logs` + 11. |
| `ConfigurationException: environment variable(s) LDS_... match no configuration key: ...` | A variable that starts with `LDS_` sets no key: misspelled, or its key is in no file. | Correct the name (`lds:start` has the rules). |
| `SchemaProblem{errors=[...]}`, `NoSuchFileException`, `The JSON Schema of ... says it is ...` | The model is refused. `[@5:1]` is the line and column in the file. | Fix the model: `lds:model` explains each message. Example: `SchemaProblem{errors=['Company' [@5:1] tried to use an undeclared directive 'domain']}` is a model that uses `@domain` without declaring it. |
| `The model schemas/schema.graphql has no entity type: ...` | A GraphQL model with no type marked `@domain`, or a folder with no JSON Schema file. | `lds:model`. |
| `ClassCastException`, `NullPointerException`, `type map already contains SearchResult` after a model change | A shape of the model LDS cannot make its GraphQL API from (a link to an interface, an entity named `...Connection`, `...Edge` or `SearchResult`). | `lds:model`, then start again. |
| `ConfigurationException: Configuration key '...' from env LDS_... is not a valid boolean` (or `int`) | A wrong value. | Correct it: a truth value is `true` or `false`, a number a whole number. |
| `No persistence providers found for providerId: ...` | `persistence.provider` is not `mem`, `postgres` or `neo4j`, or has a space at its end in a file. | Correct it. |

The full list of messages: [Configuration: When LDS does not
start](https://lds.descoped.io/docs/user/configuration/#when-lds-does-not-start).

## The container runs and is not healthy

The image declares no health check; the compose files ask `/health/ready` on port 9090 inside the container, with
`wget`. A container started with `docker run` has no health status (`health check: none` in the report).

| Symptom | Cause | What to do |
|---|---|---|
| `/health/ready` answers `503`, `/ping` `200` | A part that writes need does not answer: the entry with `DOWN` names it. | The rows of the next section. LDS connects again by itself; no restart. |
| `/health/ready` answers `200` with `DEGRADED` | Solr does not answer, or the oldest update of the index queue has waited over a minute. Writes are taken. | Bring Solr back; the queue sends what waits. |
| LDS answers on `/lds/ping` but the container is unhealthy | `http.prefix` is set. The health check asks `/health/ready` without the prefix. | Leave `http.prefix` as `/` in the image. |
| LDS answers on another port inside the container, unhealthy | The port was moved (`http.port`). The health check asks 9090. | Keep 9090 inside and move the port on the machine. |
| The distroless image in a compose file is unhealthy | It has no `wget` for the health check. | The regular image in a compose file, or a probe from outside (Kubernetes asks over HTTP). |

## LDS answers, but requests fail

| Symptom | Cause | What to do |
|---|---|---|
| Reads `500` (the first took 30 seconds), writes `500` at once, `/health/ready` `503` with `persistence` `DOWN` | Neo4j, the store of the documents, is away. | Bring Neo4j back. LDS connects again by itself: `/health/ready` was `200` seven seconds after Neo4j was started. A write that failed is in the saga log and is done by the next round of recovery (in the run, within 15 seconds of Neo4j being back). No restart. |
| Reads work, every write `500` after 30 seconds (also with `sync=false`), `/health/ready` `503` with `sagaLog`, `transactionLog` and `indexQueue` `DOWN` | The PostgreSQL of the saga log, the transaction log and the index queue is away. These writes are not done later: LDS could not note them. | Bring PostgreSQL back (`/health/ready` was `200` 12 seconds later). Then send the writes that got `500` again; every call is safe to send again. |
| Writes `201`, search does not find them yet, a search answers with an error; `/health/ready` `200` `DEGRADED` | Solr is away, or `search.index.url` is wrong. The writes wait in the index queue (`indexQueue.updates`). A write with `searchable=true` is answered after 10 seconds with `LDS-Searchable: false`. | Bring Solr back: the queue sends what waits (in the run the queue was empty and search found the writes within a minute of Solr's start). No write is to be sent again. |
| Every write `500` at once, `Unable to acquire exclusive lock on ...` in the log | Two LDS with the same `sagalog.config.cluster.instance-id`. | Give each LDS its own instance id. |
| Every write through one LDS `500` at once, `only one process may write a topic` | Two LDS write one transaction log in PostgreSQL. | Give each its own `txlog.rawdata.topic-prefix` (`txlog-` and `txlog-02-`). |
| Some writes `500` after 10 seconds, `No available saga-logs, please check configuration.` | More writes at one time than saga logs (30). | Fewer writes at once from the callers, or a higher `saga.number-of-logs` with a pool at least 11 larger. |
| LDS starts, and every write is `500` | A `txlog.default-source` or `txlog.rawdata.topic-prefix` that makes no valid topic, or a `source` with a space when `txlog.split.sources=true`. | Letters, digits, `_`, `.`, `-`, starting with a letter or digit. |
| `400` with `Unsupported namespace: "..."` | The first part of the address is not this LDS's namespace (`ns` as it comes), or an address LDS does not have (such as `/metrics`), or `/graphql` with a JSON Schema model. | Use the namespace of `namespace.default`. Other `400`s are `lds:data`. |
| `503` to everything | LDS is stopping. | Wait for it to end, then start it. |

After a fix, run the health report again and send a read and a write that you can check.

## A write seems lost, or happens late

Read [references/writes-and-recovery.md](references/writes-and-recovery.md). In short: a write answered `500` or
not answered at all may still be done by recovery within about a minute, or never; send it again, which is safe.
A write that keeps failing is given up into the saga log `dead-saga`, which LDS never reads. Look for it with:

```sh
docker compose exec -T postgres \
  psql -U lds -d sagalog -v owner=development < scripts/sagalog-entries.sql
```

```
 saga_log | entries
----------+---------
(0 rows)
```

No rows means no write is under way, waiting or given up. A row for `SAGALOG_lds_01_04` with 8 entries was one
write that failed while Neo4j was away; after recovery the table was empty again.

## Slow or stuck

| Symptom | What to do |
|---|---|
| Requests hang, CPU is high, or memory grows | Take thread dumps and a heap dump with the debug image (`references/diagnostics.md`). |
| A stop takes 30 seconds or more | A write is held up in a store. Let it finish (`docker stop -t 60`); a kill turns it into a crash. |
| Many lines from the PostgreSQL connection pool | PostgreSQL is away. Bring it back. |
| Many lines `The readiness check ... failed` and `LDS is DEGRADED` or `LDS is DOWN` | Each ask of `/health/ready` while a part is away logs them (the health check of a compose file asks every 10 seconds). They stop when the part is back. |

## Messages in the log

| Message | Meaning |
|---|---|
| `Server started in ...ms..` | LDS has started and answers. |
| `... initialization attempt # 2`, and higher | LDS waits at start for an engine. |
| `UT005071: Undertow request failed HttpServerExchange{ PUT /ns/Company/x}` | A request was answered `500`. The stack trace after it has the cause. |
| `Observed Saga failure during execution. Saga-name: "...", executionId: "..."` | A write failed. The id is the `saga-execution-id` of the write. It comes again each time the write is tried. |
| `LDS is DOWN: [...]`, `LDS is DEGRADED: [...]`, `The readiness check persistence failed: ...` | `/health/ready` found a part that does not answer; the line names it and the error. |
| `The search index did not take the update of ns/Company/acme at ... (attempt 3); tried again in PT4S: ...` | Solr did not take an update; it waits in the index queue and is tried again, after one second, doubled each time, at most a minute apart. |
| `The search index did not take a batch of 2 updates; each is tried alone: ...` | The same, for a batch of updates. |
| `The index queue is in memory, as the transaction log (discard) is: ...` | At start: updates Solr has not taken when LDS stops are lost; a rebuild of the index finds them again. |
| `Started recovery of saga ...`, `Recovery of saga succeeded, ...` | An unfinished write was run again and is done. |
| `Recovery of saga failed, ...` (level INFO), `Error while attempting to run cluster-wide saga recovery` | It failed again and is tried at the next round. |
| `Failed to retry saga-executions from saga-log ..., moving all entries to dead-saga-log.` | A write was given up and is not done. |
| `Timeout. No available saga-logs, please check configuration.` | More writes at once than saga logs. |
| `Unable to acquire exclusive lock on ...` | Another LDS has the same instance id. |
| `Another client published to topic '...': only one process may write a topic` | Another LDS writes the same transaction log. |
| `Not using index: ...` (Neo4j) | An index LDS does not use; the line has the statement that drops it. LDS never drops one itself. |
| `ShutdownHook triggered..`, then `Leaving.. Bye!` | LDS was told to stop and stopped in order. |
| `WARNING: sun.misc.Unsafe::invokeCleaner ...` (four lines) | From the HTTP library; harmless. |
