# Troubleshooting LDS

Symptom, cause, what to do. Run `sh scripts/health.sh <address> <container>` first: most rows start from what it
prints. When LDS itself fails to start, the container log ends with the JVM's `Exception in thread "main" ...`;
the cause is in that line. Ask before you stop or restart anything.

## Contents

- LDS does not start
- The container runs and never becomes healthy
- LDS answers, but requests fail
- A write seems lost, or happens late
- Slow or stuck
- Messages in the log

## LDS does not start

The container exits (exit code `1`) or keeps restarting, and the log has no `Server started`.

| Symptom (in the log or from Docker) | Cause | What to do |
|---|---|---|
| `docker: Error response from daemon: ... Bind for 127.0.0.1:9090 failed: port is already allocated` | Another container or program has the port on the machine. Docker created the container and did not start it. | Remove the container that was created (`docker rm <container>`), then publish another port on the machine (`-p 127.0.0.1:9190:9090`), or stop what has the port. With the compose file: `LDS_PORT=9190 docker compose ... up`. |
| `java.lang.RuntimeException: java.net.BindException: Address already in use` | LDS run outside a container, and the port of `http.port` is taken. | Set another port (`LDS_HTTP_PORT=9190`) or stop what has it. In a container move the port on the machine, not inside. |
| `... initialization attempt # 2`, `# 3`, ... and then `Exception in thread "main"` | LDS waited for an engine at start and gave up: 60 seconds for the documents, 30 for the saga log and for the transaction log. The cause of the last try is in the exception, see the rows below. | Make the engine reachable, then start LDS again. Start the engine before LDS (the compose file waits for each engine to be healthy). A longer wait: `LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=120`. |
| `HikariPool$PoolInitializationException: Failed to initialize pool: Connection to <host>:<port> refused` | PostgreSQL is not running there, or not on that port. | Start PostgreSQL, or correct `..postgres.driver.host` and `..port`. |
| `Failed to initialize pool: The connection attempt failed.` | The host name of PostgreSQL does not resolve, for example a compose service that is stopped. | Start the engine's container, or correct the host. |
| `Failed to initialize pool: FATAL: password authentication failed for user "lds"` | Wrong PostgreSQL password. | Correct `postgres.driver.password`, `sagalog.config.postgres.driver.password` or `txlog.config.postgres.driver.password`: the stack trace says which part. Each part has its own key. |
| `Failed to initialize pool: The server requested SCRAM-based authentication, but the password is an empty string.` | No password given. No password key has a default. | Give the password of that part. |
| `Failed to initialize pool: FATAL: database "nosuch" does not exist` | LDS makes no database. | Make it in PostgreSQL first, or correct `..driver.database`. |
| `org.neo4j.driver.exceptions.AuthenticationException: The client is unauthorized due to authentication failure.` | Wrong Neo4j password. | Correct `neo4j.driver.password` (and `neo4j.driver.username`). |
| `org.neo4j.driver.exceptions.SecurityException: The client has provided incorrect authentication details too many times in a row.` | Wrong Neo4j password, tried several times: Neo4j locks the user for a short while. | Correct the password, wait a few seconds, start again. |
| `org.neo4j.driver.exceptions.ServiceUnavailableException: Unable to connect to ...` | Neo4j is away. Each try takes half a minute or more, so this start can take 90 seconds and log over a thousand lines. | Start Neo4j, or correct `neo4j.driver.url` (an address that starts with `bolt://`). |
| `IllegalArgumentException: schema (cluster.owner) must match [A-Za-z0-9_-]+: 'my.org'` | The saga log in PostgreSQL with `sagalog.config.cluster.owner` left at its default. | Set it, for example `LDS_SAGALOG_CONFIG_CLUSTER_OWNER=development`. |
| `IllegalArgumentException: namespace (cluster.name) must match [A-Za-z0-9-]+: ...` | A cluster name with other characters, such as `_`. | Letters, digits and `-` only. |
| `No database connection available within 30000 ms`, after a minute, with `/ping` answering for a while | Too few connections: PostgreSQL is full (two LDS with everything in one PostgreSQL need 140 of its 100), or `sagalog.config.connection-pool.max-size` is below `saga.number-of-logs` (30). | Raise `max_connections` of PostgreSQL, give each LDS its own PostgreSQL, or keep the pool at least `saga.number-of-logs`. |
| `SchemaProblem{errors=[...]}`, `NoSuchFileException`, `The JSON Schema of ... says it is ...` | The model is refused. `[@5:1]` is the line and column in the file. | Fix the model: `lds:model` explains each message. Example: `SchemaProblem{errors=['Company' [@5:1] tried to use an undeclared directive 'domain']}` is a model that uses `@domain` without declaring it. |
| `No schemas configured!`, attempts once a second, then `NullPointerException` after 60 seconds | A GraphQL model with no type marked `@domain`. | `lds:model`. |
| `ConfigurationException: Configuration key '...' from env LDS_... is not a valid boolean` (or `int`) | A wrong value. | Correct it: a truth value is `true` or `false`, a number a whole number. |
| `No persistence providers found for providerId: ...` | `persistence.provider` is not `mem`, `postgres` or `neo4j`, or has a space at its end in a file. | Correct it. |

The full list of messages: [Configuration: When LDS does not
start](https://lds.descoped.io/docs/user/configuration/#when-lds-does-not-start).

## The container runs and never becomes healthy

| Symptom | Cause | What to do |
|---|---|---|
| The process runs, nothing answers on the port, the log has an exception and no `Server started` | Not finished in LDS: for some keys a wrong value stops the start but not the process: every `saga.*` key, `graphql.enabled`, `http.request.dump`, the `http.cors.*` keys. Run with `LDS_GRAPHQL_ENABLED=yes`, the container stayed `running`, nothing answered. | Read the exception, correct the value, then stop the container (`docker stop` ends it with `143`) and start it again. [The list of keys](https://lds.descoped.io/docs/user/configuration/#a-start-that-fails-and-a-process-that-stays). |
| The same, after a model change, with `ClassCastException`, `NullPointerException` or `type map already contains SearchResult` | Not finished in LDS: some models (a link to an interface, an entity named `...Connection`, `...Edge`, `PageInfo` or `SearchResult`) make LDS log an exception and stay without a port. | `lds:model`, then restart. |
| LDS answers on `/lds/ping` but the container is unhealthy | `http.prefix` is set. The health check asks `/ping` without the prefix. | Leave `http.prefix` as `/` in the image. |
| LDS answers on another port inside the container, unhealthy | The port was moved in a mounted file. The check reads only `LDS_HTTP_PORT`. | Move the port with `LDS_HTTP_PORT`, or better, keep 9090 inside and move the port on the machine. |
| Distroless image, unhealthy, with remote JMX or a debugger in `JAVA_TOOL_OPTIONS` | The health check of the distroless image is a small `java` of its own, which reads the same options and tries the same port. | Use the debug image for JMX and a debugger (`references/diagnostics.md`). |

## LDS answers, but requests fail

| Symptom | Cause | What to do |
|---|---|---|
| Reads `500` (the first took about 20 seconds), writes `500` at once, `/health/alive` `503`, `/ping` `200` | Neo4j, the store of the documents, is away. The log has `unexpected exception during health check`. | Bring Neo4j back. LDS connects again by itself: `/health/alive` was `200` four seconds after Neo4j was started. A write that failed is in the saga log and is done by the next round of recovery (in the run, about 25 seconds after Neo4j was back). No restart. |
| Reads `500` at once, writes `500` after 30 seconds, `/health/alive` `503` (after up to 30 seconds), `/ping` `200` | PostgreSQL with the documents is away. The log has `health check failed: ...`. | Bring PostgreSQL back; LDS connects again by itself. A write that failed is done later if the saga log could note it. |
| Reads work, every write `500` after 30 seconds, `/health/alive` `200` | The saga log (PostgreSQL) is away. These writes are not done later: LDS could not note them. | Bring PostgreSQL back. Then send the writes that got `500` again; every call is safe to send again. |
| Writes `201` but search does not find them; a delete `500` while the document is deleted | Solr is away, or `search.index.url` is wrong. LDS does not ask Solr at start. Those documents stay out of the index. | Bring Solr back; write the documents that were written meanwhile again so that they are indexed. The `500` delete keeps failing in the saga log and is given up later (`references/writes-and-recovery.md`). |
| Every write `500` at once, `Unable to acquire exclusive lock on ...` in the log | Two LDS with the same `sagalog.config.cluster.instance-id`. | Give each LDS its own instance id. |
| Every write through one LDS `500` at once, `only one process may write a topic` | Two LDS write one transaction log in PostgreSQL. | Give each its own `txlog.rawdata.topic-prefix` (`txlog-` and `txlog-02-`). |
| Some writes `500` after 10 seconds, `No available saga-logs, please check configuration.` | More writes at one time than saga logs (30). | Fewer writes at once from the callers, or a higher `saga.number-of-logs` with a pool at least as large. |
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
| Many lines from the PostgreSQL connection pool | PostgreSQL is away: about 700 lines in 70 seconds. Bring it back. |

## Messages in the log

| Message | Meaning |
|---|---|
| `Server started in ...ms..` | LDS has started and answers. |
| `... initialization attempt # 2`, and higher | LDS waits at start for an engine. |
| `UT005071: Undertow request failed HttpServerExchange{ PUT /ns/Company/x}` | A request was answered `500`. The stack trace after it has the cause. |
| `Observed Saga failure during execution. Saga-name: "...", executionId: "..."` | A write failed. The id is the `saga-execution-id` of the write. It comes again each time the write is tried. |
| `health check failed: ...`, `unexpected exception during health check ...` | `/health/alive` answered `503`. |
| `Started recovery of saga ...`, `Recovery of saga succeeded, ...` | An unfinished write was run again and is done. |
| `Recovery of saga failed, ...` (level INFO), `Error while attempting to run cluster-wide saga recovery` | It failed again and is tried at the next round. |
| `Failed to retry saga-executions from saga-log ..., moving all entries to dead-saga-log.` | A write was given up and is not done. |
| `Timeout. No available saga-logs, please check configuration.` | More writes at once than saga logs. |
| `Unable to acquire exclusive lock on ...` | Another LDS has the same instance id. |
| `Another client published to topic '...': only one process may write a topic` | Another LDS writes the same transaction log. |
| `Cound not find document for key ...` (ERROR) | Search found the index entry of a deleted document; harmless. |
| `Not using index: ...` (Neo4j) | An index LDS does not use; the line has the statement that drops it. LDS never drops one itself. |
| `ShutdownHook triggered..`, then `Leaving.. Bye!` | LDS was told to stop and stopped in order. |
| `WARNING: sun.misc.Unsafe::invokeCleaner ...` (four lines) | From the HTTP library; harmless. |
