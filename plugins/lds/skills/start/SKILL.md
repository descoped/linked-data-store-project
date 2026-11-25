---
name: start
description: >-
  Gets Linked Data Store (LDS) running on the user's machine and answering: the released Docker image
  descoped/lds:3.0.1 in memory to try it, or a compose stack that keeps the data (PostgreSQL alone, or Neo4j,
  PostgreSQL and Solr with search); mounts the user's own model; checks that LDS answers; explains why LDS
  refuses to start and fixes it. Use this whenever the user wants to try, install, run, start, restart or set up
  Linked Data Store or LDS, run it with their own model or schema, keep its data between runs, choose PostgreSQL
  or Neo4j for it, turn search on, move it to another port, or when LDS does not start, exits, never becomes
  healthy or does not answer, even if they do not say "docker" or "compose". Also for "start LDS", "spin up
  LDS", "is LDS running", "LDS won't start".
compatibility: LDS 3.0; Claude Code
metadata:
  lds-version: 3.0.1
  docs: https://lds.descoped.io/docs/user/getting-started/
---

# Start Linked Data Store

LDS is a server that keeps versioned, linked JSON documents and answers over HTTP: REST for reading and writing,
GraphQL for asking. It is one process, released as the image `descoped/lds:3.0.1` on Docker Hub. This skill gets
it running and answering. What comes next belongs to the other skills of the plugin: `lds:model` (a model of the
user's domain), `lds:data` (writing and reading documents), `lds:query` (GraphQL), `lds:operate` (running it for
real: logs, recovery, backups, upgrades).

The skill's directory, `${CLAUDE_SKILL_DIR}`, holds `scripts/wait-for-lds.sh`, `assets/compose-postgres.yaml`,
`assets/compose-stack.yaml` and `references/`. Its scripts are in `${CLAUDE_SKILL_DIR}/scripts/`: run them as `sh
"${CLAUDE_SKILL_DIR}/scripts/<name>"`, from any folder. The references name them `scripts/<name>`, and `<skill dir>`
there is `${CLAUDE_SKILL_DIR}`.

## Workflow

1. **Ask one question with a suggested answer**: does the user want to try LDS (in memory, nothing is kept when it
   stops), or keep the data (PostgreSQL alone, or the full stack with Neo4j and search)? And do they have a model of
   their own? Suggest trying first, with the example model, when they are unsure. A model of their own domain is
   `lds:model`'s work; come back here to load it.
2. **Check the tools**: `docker version` (the engine must answer), and `docker compose version` for a stack.
   Without Docker, point to Docker Desktop (macOS, Windows) or Docker Engine (Linux); LDS needs nothing else to
   try. `curl` is used to talk to LDS.
3. **Look for an LDS that runs already** before starting another, so two do not fight over port 9090:
   `docker ps --filter ancestor=descoped/lds:3.0.1 --format '{{.Names}} {{.Ports}}'`. If one answers, ask whether
   to use it.
4. **Start it** with the command of the chosen set-up below. Run it detached (`-d`, `up -d`), so the shell is free.
5. **Wait until it answers** with `sh "${CLAUDE_SKILL_DIR}/scripts/wait-for-lds.sh" <url> <container>`. It waits for
   `/ping`, asks `/health/ready` whether every part a write needs answers, prints the entity types and the stores
   LDS runs with, or stops early with the cause when the start fails. On a failure, read
   [references/start-failures.md](references/start-failures.md) and fix the cause.
6. **Show the user that it works**: `curl -s 'http://localhost:9090/ns?schema'` lists the entity types.
7. **Tell the user** in a few lines: the address, where the data is kept and whether it survives a stop, how to
   stop it, and that LDS has no authentication (below). Then offer the next step: `lds:model` for their own model,
   `lds:data` to write a first document.

## Try it: the image, in memory

```sh
docker run -d --name lds -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
sh "${CLAUDE_SKILL_DIR}/scripts/wait-for-lds.sh" http://localhost:9090 lds
```

```
LDS answers at http://localhost:9090
Ready: UP
Managed domains: /Company /Person
Using persistence provider: mem
Using saga-log-pool provider-class: no.cantara.sagalog.memory.MemorySagaLogInitializer
GraphQL Search is disabled. Skipping search index initialisation
Using transaction-log provider: discard
```

As it comes, LDS keeps everything in memory, has an example model (the entity types `Person` and `Company`), and
answers on port 9090 with the namespace `ns`: a document's address is `/ns/{Entity}/{id}`. It starts in under a
second. Everything is gone when the container stops, so this is for trying LDS and for tests.

Not finished: the memory store has known faults. A GraphQL query for a reverse link after some deletes stops every
read and write while `/ping` still answers `200` (see `lds:query`, reverse links). For anything beyond a first try,
keep the data in PostgreSQL.

Publish the port on localhost only, as above, and not as `-p 9090:9090`; Docker takes no name there, so the
command says `127.0.0.1`. LDS asks no caller who it is: whoever reaches the port can read, write and delete every
document. Without the address Docker publishes the port on every network of the machine. The same holds for every
port of the compose files, which publish on localhost only.

## Check that LDS answers

| Ask | Answer | It says |
|---|---|---|
| `curl -s -i http://localhost:9090/ping` | `200`, no body | LDS answers HTTP. No store is asked. `/health/alive` answers the same. |
| `curl -s http://localhost:9090/health/ready` | `200` or `503`, JSON | Whether every part a write needs answers: the store, the saga log, the transaction log, and with search Solr and the index queue. |
| `curl -s 'http://localhost:9090/ns?schema'` | `["/ns/Company?schema","/ns/Person?schema"]` | The entity types of the model LDS read. |
| `docker logs lds` | `... Server started in 305ms..` | The log; the lines that start with `Using` name the stores. |

In memory `/health/ready` answered:

```json
{
  "status": "UP",
  "checks": {
    "persistence": {"status": "UP", "millis": 14},
    "sagaLog": {"status": "UP", "millis": 3, "logs": 30},
    "transactionLog": {"status": "UP", "millis": 1, "keepsMessages": false}
  }
}
```

`/ping` answering means the process runs, not that PostgreSQL, Neo4j or Solr can be reached; `/health/ready` asks
them. The image declares no health check of its own: whoever runs LDS asks `/health/ready` (the compose files of
this skill do). What each answer means is `lds:operate`'s.

## Keep the data: a compose stack

Two compose files come with this skill, both on the released image. Copy one into a folder of the user's as
`compose.yaml`, and start it there:

| Set-up | File | Documents | Saga log and transaction log | Search |
|---|---|---|---|---|
| PostgreSQL alone | `assets/compose-postgres.yaml` | PostgreSQL | PostgreSQL | off |
| The full stack | `assets/compose-stack.yaml` | Neo4j | PostgreSQL | Solr |

Suggest PostgreSQL alone unless the user wants search or Neo4j: it is one engine, starts in seconds, and is all
LDS needs to keep data. The full stack is the set-up LDS is developed with; its Neo4j browser and Solr admin pages
are on `http://localhost:7474` and `http://localhost:8983`.

```sh
mkdir lds && cd lds
cp "${CLAUDE_SKILL_DIR}/assets/compose-postgres.yaml" compose.yaml
docker compose up -d --wait
sh "${CLAUDE_SKILL_DIR}/scripts/wait-for-lds.sh" http://localhost:9090 lds-postgres-lds-1
```

`--wait` returns when every service is healthy, LDS last: LDS is healthy when `/health/ready` answers `200`. The
first start pulls the images. With the images pulled, a fresh PostgreSQL file was up in 8 seconds in a run; the full
stack waits for Neo4j, which took from under a minute to nearly three on a busy machine. The container of LDS is
`<project>-lds-1`: `lds-postgres-lds-1` for the PostgreSQL file, `lds-lds-1` for the full stack.

- **The data is kept** in named volumes when the stack is stopped (`docker compose down`) and started again. A
  document written before `down` was read back after `up`, with both files. `docker compose down -v` removes the
  volumes, and with them every document.
- **Search** finds a document by the words of its texts, and only with a GraphQL schema as the model; a write is
  found within about a second. Searching is `lds:query`'s.
- **Passwords** are the development defaults written in the files (user `lds`, password `lds`; Neo4j `neo4j`,
  `lds-development`). They are for a machine of one's own only.
- **A port that is taken** is moved with a variable in front of the command: `SERVER_PORT=9190 docker compose up
  -d --wait`, and `NEO4J_HTTP_PORT`, `NEO4J_BOLT_PORT`, `SOLR_PORT` in the full stack. PostgreSQL has no port on the
  machine. No variable of the compose files starts with `LDS_`: LDS would refuse such a variable when it is in the
  environment of the shell that starts it.

[references/where-data-is-kept.md](references/where-data-is-kept.md): read it when the user has a PostgreSQL,
Neo4j or Solr of their own, wants another mix of stores, or asks what each store holds and what an engine must
have before LDS starts.

The repository of LDS has a compose file of its own (`linked-data-store-server/compose.yaml`) that builds the image
from source; use it only when the user works on LDS itself (see "Build from source" below).

## The user's own model

LDS reads its model once, at start, from `/lds/schemas` in the container. Mount a folder of the user's there:

```sh
docker run -d --name lds -p 127.0.0.1:9090:9090 -v "$PWD/schemas:/lds/schemas:ro" descoped/lds:3.0.1
```

In a compose file, uncomment the two `volumes` lines under `lds`; `./schemas` is then next to `compose.yaml`.

| The model is | The folder holds | Also give |
|---|---|---|
| A GraphQL schema (suggest this) | `schema.graphql` | nothing |
| A GraphQL schema with another file name | `library.graphql` | `-e LDS_GRAPHQL_SCHEMA=schemas/library.graphql` |
| JSON Schema 2020-12 files, one per entity type | `Book.json`, `Author.json`, ... | `-e LDS_GRAPHQL_SCHEMA=` (empty) |

- Mount a folder, with an absolute path (`"$PWD/schemas"`). A folder that does not exist is made empty by Docker,
  and LDS then ends with `NoSuchFileException: schemas/schema.graphql`.
- A GraphQL schema gives both REST and GraphQL, with reverse links and search. JSON Schema files give REST only:
  `/graphql` answers `400` with `Unsupported namespace: "graphql"`. That is why a GraphQL schema comes first.
- A change of the model needs a restart: `docker rm -f lds` and the `docker run` again (in memory the documents are
  then gone), or `docker compose restart lds` in a stack, which keeps them. Then run the wait script again and check
  `?schema`: after adding a type `Publisher` and a restart, `/ns?schema` listed it.
- One LDS has one model and one namespace (`ns` unless `LDS_NAMESPACE_DEFAULT` names another). A second
  model is a second LDS on another port.
- A model LDS cannot read stops the start with a `SchemaProblem{errors=[...]}`. What a model must hold, and the
  fixes, are `lds:model`'s.

## Configuration in a container

The image is configured from outside: environment variables `LDS_` plus the key in upper case with `_` (`-e
LDS_NAMESPACE_DEFAULT=data`), or a file mounted as `/lds/conf/application.properties`. Inside the container
`localhost` is the container itself, not the user's machine. Read
[references/configuration.md](references/configuration.md) before setting any key: a variable `LDS_...` that sets no
key of LDS (a misspelled one) stops the start, and some keys must be left alone in the image.

## Stop, restart, remove

| Set-up | Stop | What is left |
|---|---|---|
| `docker run` | `docker stop lds`, then `docker rm lds` | Nothing; the documents in memory are gone. |
| A compose stack | `docker compose down` in its folder | The volumes, with every document. |
| A compose stack, and its data | `docker compose down -v` | Nothing. |

`docker stop` lets LDS finish the requests and writes it is on before it ends (exit code 143, the log ends with
`Leaving.. Bye!`). An LDS with nothing to finish stopped in well under a second.

## When LDS does not start

Run the wait script with the container's name: when the start fails LDS ends (exit code 1), and the script prints
the cause from the log. Then use [references/start-failures.md](references/start-failures.md): the messages, what
each means and the fix. The common ones: the port is taken (Docker says `port is already allocated`), the model file
is missing or cannot be read, a variable is misspelled or a value of a key is wrong, an engine cannot be reached from
inside the container.

## Several LDS side by side

Give each its own name and port on the machine: `docker run -d --name lds-books -p 127.0.0.1:9191:9090 ...`. Keep
`9090` inside the container; the health check of the compose files asks that port. For a second compose stack, use
another folder and a project name: `docker compose -p lds-books up -d --wait`, with `SERVER_PORT` and the other
port variables moved.

## Other images

`descoped/lds:3.0.1-distroless` is the same LDS with no shell, and no `wget` for the health check of the compose
files; `descoped/lds:3.0.1-debug` adds the tools of the JDK. Choosing between them is `lds:operate`'s. Name the whole
version: `3.0` and `latest` move with new releases.

## Build from source

Only for work on LDS itself, which needs the repository, JDK 27 and Docker: follow
https://lds.descoped.io/docs/user/getting-started/#build-lds. A user who only uses LDS needs the image.

## Further reading

- Getting started: https://lds.descoped.io/docs/user/getting-started/
- The image and the compose file: https://lds.descoped.io/docs/user/operations/#the-image
- Where LDS keeps its data: https://lds.descoped.io/docs/user/configuration/#choosing-where-lds-keeps-its-data
- When LDS does not start: https://lds.descoped.io/docs/user/configuration/#when-lds-does-not-start
- A model in a container: https://lds.descoped.io/docs/user/model/#in-a-container
