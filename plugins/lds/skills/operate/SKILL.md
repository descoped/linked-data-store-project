---
name: operate
description: >
  Runs and looks after a Linked Data Store (LDS) in use: health checks, logs, stopping it safely, what is stored
  where, writes that failed and how LDS completes them, an engine (PostgreSQL, Neo4j, Solr) that is away, several
  LDS on one saga log, backup and restore for each store, thread dumps, heap dumps and JFR with the debug image,
  moving to a newer image, and security. Use this whenever LDS is slow, unhealthy, answers 500 or 503, will not
  start, crashed, lost a write, needs a backup or a restore, an upgrade, a heap dump or a thread dump, or is about
  to be put on a server, in Kubernetes or in the cloud, even if the user never says "operations". Also for "is LDS
  up", "check LDS", "back up LDS", "LDS logs", "secure LDS", "LDS in production".
compatibility: LDS 3.0; Claude Code
---

# Operate Linked Data Store

LDS is one Java process in a container (`descoped/lds`). It keeps four things, each with its own store: the
documents (memory, PostgreSQL or Neo4j), the saga log (memory or PostgreSQL), the transaction log (none, memory
or PostgreSQL) and the search index (off, or Solr). Most questions about running it come down to which of these
is where, and which one is away.

Other skills own the neighbouring topics: starting LDS for the first time and choosing stores is `lds:start`; the
model and why it is refused is `lds:model`; writing and reading documents, and a refused write, is `lds:data`;
GraphQL queries and search are `lds:query`.

## Workflow

1. **Find the LDS.** Ask where it runs (its address, and the container or compose project) unless it is clear.
   Ask nothing more until you have looked: run the health report (below). It tells you what LDS runs with.
2. **Run the health report** with the address and, when there is one, the container name:
   `sh "${CLAUDE_SKILL_DIR}/scripts/health.sh" http://localhost:9090 <container>`. It asks
   the three health addresses, and reads the container's state and the log since its last start: the stores LDS
   uses, whether it says `Server started`, why a start failed, and the events that tell of a fault. It only reads.
3. **Match the symptom** to a row of [references/troubleshooting.md](references/troubleshooting.md) and follow
   it. Explain the cause in plain words before you change anything, and say what the change will do.
4. **Before anything that stops LDS or an engine** (a restart, a restore, an upgrade, a change of configuration),
   say so and get a yes. A stop is safe when it is done in order (below); a forced stop is a crash for the writes
   under way.
5. **For a backup, a restore or an upgrade**, follow [references/backup.md](references/backup.md) or
   [references/upgrade.md](references/upgrade.md) step by step, and check the result by reading a document you
   know.
6. **For a hang, high CPU or memory**, use the debug image as
   [references/diagnostics.md](references/diagnostics.md) says.
7. **Whenever LDS is to be reached from anywhere but the machine it runs on**, go through the security section
   below with the user first, even if they did not ask.
8. Finish by running the health report again and telling the user, in plain words, what was wrong, what was done
   and what is left.

## Scripts

Both only read; run them rather than retyping their steps. Its scripts are in `${CLAUDE_SKILL_DIR}/scripts/`: run them
as `sh "${CLAUDE_SKILL_DIR}/scripts/<name>"`, from any folder. The references name them `scripts/<name>`, and `<skill
dir>` there is `${CLAUDE_SKILL_DIR}`.

The `docker compose` commands of this skill and its references are run in the folder of the user's stack, the one
that holds its `compose.yaml` (as `lds:start` sets it up; the services are `lds`, `postgres`, and in the full stack
`neo4j` and `solr`). With another file or folder, add `-f <file>` or `--project-directory <folder>`.

| Script | Use |
|---|---|
| `scripts/health.sh [address] [container]` | The health report of step 2. Without a container it asks only the three addresses, for an LDS that is not in Docker or not on this machine. |
| `scripts/sagalog-entries.sql` | Which saga logs in PostgreSQL hold entries: writes under way, waiting for recovery, or given up (`dead-saga`). No rows means every write is done. Run it with `psql -U lds -d sagalog -v owner=<sagalog.config.cluster.owner> < "${CLAUDE_SKILL_DIR}/scripts/sagalog-entries.sql"`, in the stack through `docker compose ... exec -T postgres`. |

## When to read each reference

| Read | When |
|---|---|
| [references/troubleshooting.md](references/troubleshooting.md) | Something is wrong: no answer, `500`, `503`, a start that fails, a container that is unhealthy, a write that is missing. |
| [references/writes-and-recovery.md](references/writes-and-recovery.md) | A write failed, LDS crashed, an engine was away, the user asks whether a write "will happen", or runs several LDS on one saga log. |
| [references/backup.md](references/backup.md) | What to back up, how, and how to restore, for each store. |
| [references/diagnostics.md](references/diagnostics.md) | Thread dumps, heap dumps, JFR, `jstat`, remote JMX or a debugger, another log level. |
| [references/upgrade.md](references/upgrade.md) | Moving to a newer image, pinning a version, checking the signature, going back. |

## The health addresses

| Address | Answer | What it says |
|---|---|---|
| `/ping` | `200` | LDS answers HTTP. No store is asked. The health check of the image asks this. |
| `/health/ready` | `200` | The same as `/ping`. |
| `/health/alive` | `200`, or `503` | LDS can reach the store of the documents. Nothing else is asked. |

- While LDS stops, all three answer `503`. While it starts, or waits for an engine at start, nothing answers.
- `/health/alive` can take 30 seconds to answer `503` when the documents are in PostgreSQL and PostgreSQL is away;
  give the request that long. With Neo4j away it answered `503` at once.
- No address tells that the saga log, the transaction log or Solr is away: with the saga log away, `/health/alive`
  answers `200` while every write fails. Look in the log for that.
- So a healthy container means LDS answers HTTP, not that it can write. A container that runs and never becomes
  healthy is an LDS whose start failed while the process stayed (see troubleshooting).
- There is no address for metrics (`/metrics` is answered `400`, `Unsupported namespace: "metrics"`).

## Logs

LDS logs to standard output only, at the level `INFO`: in a container it is the container log.

```sh
docker logs --since 10m <container>
docker compose logs lds
```

- LDS has started when the log says `Server started in ...ms..`. Look for that line, not only for a running process.
- A start that fails is not a line of the log: the JVM prints `Exception in thread "main" ...` on standard error,
  which is in the container log too.
- A `500` has no body; its cause is in the log after `UT005071: Undertow request failed HttpServerExchange{ ... }`.
- The log can hold what callers send: every search text, and with `http.request.dump=true` every header, an
  `Authorization` header too. Treat the log as data that needs the same care as the documents.
- A container that was started again keeps the log of its earlier runs; `docker logs --since <time>` limits it.

The messages to look for, and what each means, are in
[references/troubleshooting.md](references/troubleshooting.md#messages-in-the-log).

## Stopping

`docker stop` sends TERM to the Java program, and LDS stops in order: new requests get `503`, the requests being
handled get up to 10 seconds, the writes under way get up to 10 seconds more, then the stores are closed. The
container ends with exit code `143`. An LDS with nothing to finish stops within milliseconds.

Docker kills a container that has not stopped 10 seconds after `docker stop`, and an LDS with a write held up in a
store can need 30 seconds or more. A kill before LDS has stopped is a crash for the writes under way. So give it
time:

```sh
docker stop -t 60 <container>
docker compose stop -t 60 lds
```

Exit code `137` means the process was killed (`kill -9`, `docker kill`, a stop that ran out of time, or the
memory limit). Exit code `1` means the start failed. What happens to the writes of a crash is in
[references/writes-and-recovery.md](references/writes-and-recovery.md).

## What is stored where

| What | As LDS comes (gone when it stops) | Kept in |
|---|---|---|
| Documents, every version | memory | PostgreSQL: table `fragment`. Neo4j: the database LDS connects to |
| Saga log: writes under way and writes given up | memory: a crash loses the writes under way | PostgreSQL: a schema named by `sagalog.config.cluster.owner`, tables `SAGALOG_<cluster>_<instance>_<n>` |
| Transaction log: one entry for every write | not kept | PostgreSQL: tables `rawdata_message`, `rawdata_content`, `rawdata_metadata` |
| Search index | search is off | Solr: the one core named at the end of `search.index.url` |
| Model and configuration | your files | read at start, stored nowhere by LDS |

- LDS makes its own tables, constraints and indexes at start. It makes no database, no user and no Solr core:
  those must be there before it starts.
- Nothing in LDS removes old versions or transaction log entries: the stores grow with every write and delete.
- One LDS opens 70 connections to PostgreSQL when all three parts are in it (10 documents, 50 saga log, 10
  transaction log). A PostgreSQL as it comes takes 100, so two such LDS on one PostgreSQL do not fit.
- The search index cannot be rebuilt from the documents. A document written while search was off or Solr was away
  is not in the index until it is written again.

Detail: [Running LDS: What is stored where](https://lds.descoped.io/docs/user/operations/#what-is-stored-where).

## Security: LDS has no authentication

LDS asks no caller who it is. Whoever reaches its port can read, write and delete every document, and the GraphQL
page `/graphiql` gives them a browser tool to do it. It speaks plain HTTP only (no TLS), and as it comes it
listens on every address of the machine (`http.host=0.0.0.0`). So who can reach the port is decided entirely
outside LDS. Tell the user this plainly, and keep to these, because each one is what stands between the data and
anyone on the network:

- **Publish the port on 127.0.0.1** (`-p 127.0.0.1:9090:9090`, as the compose file does), or keep LDS on a
  private network that only its callers reach (a Docker network, a Kubernetes namespace with a network policy, a
  VPC without a public address). `-p 9090:9090` publishes it on every address of the machine.
- **When others must reach it**, put something that authenticates in front: a reverse proxy or API gateway with
  TLS and a login, an identity-aware proxy, or a VPN. Decide there who may write: LDS cannot tell readers from
  writers.
- **Never give LDS a public address of its own**, on a cloud platform that offers one (Cloud Run, Fargate, a load
  balancer), without such a front.
- **The engines carry the same data.** The Neo4j browser, the Solr admin pages and PostgreSQL are as open as
  their passwords. The compose file's passwords (`lds`, `lds-development`) are development defaults: set others
  everywhere else, and do not publish engine ports outside the machine.
- **Diagnostics open doors.** Remote JMX without a password and a remote debugger give whoever reaches the port
  the whole process. Publish them on localhost only, and only while investigating.
- **CORS is not access control.** `http.cors.allow.origin` (default `.*`) only tells browsers which pages may read
  answers; any other client is answered all the same.
- **The log can hold secrets** (see Logs), so keep `http.request.dump` and `neo4j.cypher.show` off outside a
  short investigation.
- **Pin the image and check its signature** ([references/upgrade.md](references/upgrade.md)). The image runs as the
  user `lds`, not root; the distroless variant has no shell, which suits production.

Detail: [Running LDS: What LDS does not have](https://lds.descoped.io/docs/user/operations/#what-lds-does-not-have).

## What LDS does not have

No authentication, no TLS, no metrics address, no backup or restore tool, no clean-up of old versions, no key for
the log level, no rebuild of the search index, and no health address for the saga log, the transaction log or
Solr. Say so when the user expects one, and offer the way around it from the references.
