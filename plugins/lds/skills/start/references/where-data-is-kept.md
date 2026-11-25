# Where LDS keeps its data

LDS keeps four things and a store is chosen for each, independently of the others. This reference gives the keys
for each choice as variables for `docker run` or a compose file, what an engine must have before LDS starts, and
what the two compose files of this skill choose. The documentation:
https://lds.descoped.io/docs/user/configuration/#choosing-where-lds-keeps-its-data.

## The four things

| What | Chosen by | Values | As LDS comes | PostgreSQL file | Full stack |
|---|---|---|---|---|---|
| The documents: every version of every document | `LDS_PERSISTENCE_PROVIDER` | `mem`, `postgres`, `neo4j` | `mem` | `postgres` | `neo4j` |
| The saga log: the writes under way, so that one cut off by a crash is completed later | `LDS_SAGALOG_PROVIDER` | `no.cantara.sagalog.memory.MemorySagaLogInitializer`, `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` | memory | PostgreSQL | PostgreSQL |
| The transaction log: one entry for every write | `LDS_TXLOG_RAWDATA_PROVIDER` | `discard`, `memory`, `postgres` | `discard` (none) | `postgres` | `postgres` |
| The search index | `LDS_GRAPHQL_SEARCH_ENABLED` and `LDS_SEARCH_INDEX_URL` | `true` and the address of a Solr core | off | off | Solr |

- **The documents are the data.** Nothing else in LDS can build them again. In memory they are gone when LDS stops;
  that is fine for a try and nothing more.
- **The saga log matters at a crash.** In memory, a write that is under way when LDS is killed is lost; in
  PostgreSQL the next start completes it.
- **The transaction log** is a record of every write. LDS runs without it and cannot build the documents from it;
  it answers `GET /source/{source}` (`lds:data`), which without it is answered `409`.
- **The search index** is made from the documents. Search finds documents by the words of their texts, and needs a
  GraphQL schema as the model. Each write is put in an **index queue**, from which LDS sends it to Solr; the queue
  is kept where the transaction log is (in PostgreSQL, the table `lds_index_queue` of its database; in memory when
  the transaction log is not kept), so no update of the index is lost while Solr is away. LDS can build the index
  again from the documents (`lds:operate`).

The `Using ...` lines of the log, which the wait script prints, say what LDS runs with:

```
Using persistence provider: postgres
Using saga-log-pool provider-class: no.cantara.sagalog.postgres.PostgresSagaLogInitializer
GraphQL Search is disabled. Skipping search index initialisation
Using transaction-log provider: postgres
```

With search on, the log also says `Search index provider configured: solr` and
`The index queue is kept in postgres, where the transaction log is`.

## Which to suggest

| The user wants | Suggest |
|---|---|
| To try LDS, follow a tutorial, run tests | The image as it comes, in memory. |
| To keep data, on one machine | `assets/compose-postgres.yaml`: one PostgreSQL holds all three. |
| Search, or Neo4j (the store LDS is developed with) | `assets/compose-stack.yaml`. |
| Their own PostgreSQL, Neo4j or Solr | `docker run` with the keys below, or a copy of a compose file with the engine services taken out and the hosts changed. |

## The keys for each store

Every key below can be given as a variable (`-e` in `docker run`, `environment:` in compose) or as a line of a
mounted `/lds/conf/application.properties` (`postgres.driver.host=...`). No password has a default; the values
here are the development defaults of the compose files. Hosts are as the container sees them; see
[configuration.md](configuration.md#engines-outside-the-container).

### Documents in PostgreSQL

```
LDS_PERSISTENCE_PROVIDER=postgres
LDS_POSTGRES_DRIVER_HOST=postgres
LDS_POSTGRES_DRIVER_PORT=5432
LDS_POSTGRES_DRIVER_USER=lds
LDS_POSTGRES_DRIVER_PASSWORD=lds
LDS_POSTGRES_DRIVER_DATABASE=lds
```

The database and the user must be there; LDS makes its table `fragment` and an index in it at start, so the user
needs the right to make a table. LDS is tested with PostgreSQL 18 and no other version. No key sets TLS or the
number of connections (10) for the documents.

### Documents in Neo4j

```
LDS_PERSISTENCE_PROVIDER=neo4j
LDS_NEO4J_DRIVER_URL=bolt://neo4j:7687
LDS_NEO4J_DRIVER_USERNAME=neo4j
LDS_NEO4J_DRIVER_PASSWORD=lds-development
```

LDS makes a constraint and an index for each entity type of the model at start, when they are missing, and logs
each as `Creating: ...`. After a change of the model the restart makes those of the new entity types. LDS is tested
with Neo4j 2026.10, community edition, and addresses that start with `bolt://`. No key names the database.

### The saga log in PostgreSQL

```
LDS_SAGALOG_PROVIDER=no.cantara.sagalog.postgres.PostgresSagaLogInitializer
LDS_SAGALOG_CONFIG_CLUSTER_OWNER=development
LDS_SAGALOG_CONFIG_CLUSTER_NAME=lds
LDS_SAGALOG_CONFIG_CLUSTER_INSTANCE_ID=01
LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_HOST=postgres
LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_USER=lds
LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_PASSWORD=lds
LDS_SAGALOG_CONFIG_POSTGRES_DRIVER_DATABASE=sagalog
```

- The database must be there. LDS makes a schema named as the cluster owner in it, and its tables there. The
  owner is `lds` when it is not set; it takes letters, digits, `_` and `-`. The cluster name and instance id take
  letters, digits and `-`.
- It opens its whole pool, 50 connections, at start and keeps them; LDS does not start when PostgreSQL does not
  give them. The pool must be at least the number of saga logs (`saga.number-of-logs`, 30) and 11. Several LDS on
  one saga log are `lds:operate`'s.

### The transaction log

In PostgreSQL:

```
LDS_TXLOG_RAWDATA_PROVIDER=postgres
LDS_TXLOG_CONFIG_POSTGRES_DRIVER_HOST=postgres
LDS_TXLOG_CONFIG_POSTGRES_DRIVER_USER=lds
LDS_TXLOG_CONFIG_POSTGRES_DRIVER_PASSWORD=lds
LDS_TXLOG_CONFIG_POSTGRES_DRIVER_DATABASE=txlog
```

The database must be there; LDS makes its tables, and with search the table of the index queue.
`LDS_TXLOG_RAWDATA_PROVIDER=memory` keeps it in memory until LDS stops and needs no other key.

### Search with Solr

```
LDS_GRAPHQL_SEARCH_ENABLED=true
LDS_SEARCH_INDEX_URL=http://solr:8983/solr/lds-index
```

The address is that of one Solr core and ends with its name. The core must be there: LDS does not make it (the
full stack's Solr makes `lds-index` with `solr-precreate`), and puts its own schema in it. A Solr that cannot be
reached does not stop the start: `/health/ready` says `DEGRADED`, writes are answered `201` and wait in the index
queue until Solr takes them, and a search answers with an error. LDS is tested with Solr 10.1. Search is a GraphQL
query, so with JSON Schema files as the model nothing can search. A write is found within about a second
(`LDS_SEARCH_INDEX_COMMIT_WITHIN`, in milliseconds, default 1000).

## One PostgreSQL, named once

A mounted `application.properties` can name the host and the password once with keys of its own. This ran on the
network of the PostgreSQL compose file and wrote and read a document:

```properties
db.host=postgres
db.port=5432
db.password=lds

persistence.provider=postgres
postgres.driver.host=${db.host}
postgres.driver.port=${db.port}
postgres.driver.password=${db.password}

sagalog.provider=no.cantara.sagalog.postgres.PostgresSagaLogInitializer
sagalog.config.cluster.owner=development
sagalog.config.cluster.name=lds
sagalog.config.postgres.driver.host=${db.host}
sagalog.config.postgres.driver.port=${db.port}
sagalog.config.postgres.driver.password=${db.password}

txlog.rawdata.provider=postgres
txlog.config.postgres.driver.host=${db.host}
txlog.config.postgres.driver.port=${db.port}
txlog.config.postgres.driver.password=${db.password}
```

```sh
docker run -d --name lds -p 127.0.0.1:9090:9090 \
  -v "$PWD/application.properties:/lds/conf/application.properties:ro" descoped/lds:3.0.1
```

`db.host` and `db.password` are now keys of a file, so `-e LDS_DB_HOST=...` can move them. The user `lds` and the
databases `lds`, `sagalog` and `txlog` must be there.

## Connections to PostgreSQL

One LDS with all three in PostgreSQL opens 70 connections at start and keeps them: 10 for the documents, 50 for the
saga log, 10 for the transaction log; with search, 4 more for the index queue. The full stack, with the documents in
Neo4j, opens 64. PostgreSQL as it comes takes 100, 3 of them kept for its superuser. So a second LDS on the same
PostgreSQL does not fit: in a run it ended at start after 30 seconds with `PostgresSagaLogException: initialize
failed on the connection pool: the database gave the pool 20 of its 50 connections ... FATAL: sorry, too many
clients already`. Give each LDS its own PostgreSQL, or raise `max_connections` of PostgreSQL.

## What is in the volumes

| Compose file | Volume | Holds |
|---|---|---|
| PostgreSQL | `postgres` | The documents (database `lds`, table `fragment`), the saga log (`sagalog`), the transaction log (`txlog`). |
| Full stack | `neo4j` | The documents. |
| Full stack | `postgres` | The saga log, the transaction log and the index queue; the database `lds` is not used. |
| Full stack | `solr` | The search index. |

`docker compose down -v` removes them. LDS has no backup tool and no tool that copies documents from one store to
another; backups are `lds:operate`'s, exporting and loading documents `lds:data`'s.
