# Backup and restore

LDS has no backup tool: each store is backed up with the tools of its engine. Every command here was run against
the compose stack of the repository (project `lds`, so its volumes are `lds_neo4j`, `lds_postgres` and
`lds_solr`), and each restore was checked by reading a document written before the backup (`200`) and one written
after it (`404`). For another deployment, keep the engine command and change how it is reached.

## What to back up

| Store | Back up? | Why |
|---|---|---|
| The documents (PostgreSQL table `fragment`, or the Neo4j database) | Always | This is the data: every version of every document. Nothing in LDS can build it again. |
| The saga log (PostgreSQL database `sagalog` in the stack) | When LDS runs with writes under way; not needed after a stop in order with an empty saga log | It holds the writes under way and the writes given up (`dead-saga`). It is empty when every write is done. |
| The transaction log (PostgreSQL database `txlog` in the stack) | If callers use `GET /source/{source}`, or the record of every write matters to you | LDS runs without it and cannot rebuild the documents from it. |
| The search index (the Solr core) | If search is used | LDS cannot rebuild it from the documents: a document lost from the index is found again only when written again. |
| The model and the configuration (your files) | Always, in version control | LDS reads them at start and stores them nowhere. |
| Memory (`mem`, the default) | Cannot be | Nothing is kept; everything is gone when LDS stops. Move to PostgreSQL or Neo4j (`lds:start`). |

Back up every store at the same moment, so that they agree: stop LDS in order first (`docker stop -t 60`), check
that the saga log is empty (`scripts/sagalog-entries.sql`, see
[writes-and-recovery.md](writes-and-recovery.md#a-write-that-keeps-failing-dead-saga)), back up, start LDS. A Solr
index restored from an earlier moment than the documents misses the documents written in between, and LDS does
not notice.

Restore in the same order: stop LDS, restore each store, start LDS, read a document you know.

## Documents in PostgreSQL

The table `fragment` in the database of `postgres.driver.database` (with its index `fragment_value_idx`). LDS
makes the table at start only when it is missing, so a restored table is used as it is. Online, with LDS running,
`pg_dump` takes a consistent copy of the table; with LDS stopped, the copy also agrees with the other stores.

```sh
docker compose exec -T postgres \
  pg_dump -U lds -d lds -t fragment -Fc > lds-documents.dump
```

Restore, with LDS stopped (here into the same database, after the table is dropped):

```sh
docker compose exec -T postgres \
  psql -U lds -d lds -c 'DROP TABLE fragment'
docker compose exec -T postgres \
  pg_restore -U lds -d lds < lds-documents.dump
```

The compose stack keeps the documents in Neo4j; this was run with an LDS whose documents were in the stack's
database `lds` (`LDS_PERSISTENCE_PROVIDER=postgres`, `LDS_POSTGRES_DRIVER_HOST=postgres`,
`LDS_POSTGRES_DRIVER_PASSWORD=lds`).

## Saga log and transaction log in PostgreSQL

Whole databases:

```sh
docker compose exec -T postgres \
  pg_dump -U lds -d sagalog -Fc > lds-sagalog.dump
docker compose exec -T postgres \
  pg_dump -U lds -d txlog -Fc > lds-txlog.dump
```

Restore each with `pg_restore -U lds -d <database>` into an empty database, LDS stopped. The saga log is in a
schema named by `sagalog.config.cluster.owner` (`development` in the stack); the transaction log is the tables
`rawdata_message`, `rawdata_content` and `rawdata_metadata`. These databases need to be there before LDS starts:
LDS makes tables, never databases.

## Documents in Neo4j

LDS is tested with Neo4j community edition, whose `neo4j-admin database dump` needs the database stopped. In a
container that means stopping Neo4j and running the dump in a one-off container on the same volume. Stop LDS
first: with Neo4j away its writes fail.

```sh
mkdir -p backup
docker compose stop -t 60 lds
docker compose stop neo4j
docker compose run --rm --no-deps -v "$PWD/backup:/backups" neo4j \
  neo4j-admin database dump neo4j --to-path=/backups
docker compose up -d --wait
```

```
Done: 40 files, 258.1MiB processed in 0.258 seconds.
... INFO  [o.n.c.d.DumpCommand] Dump completed successfully
```

The file is `backup/neo4j.dump`. `neo4j` is the database LDS uses (no key names another). Restore, the same way:

```sh
docker compose stop -t 60 lds
docker compose stop neo4j
docker compose run --rm --no-deps -v "$PWD/backup:/backups" neo4j \
  neo4j-admin database load neo4j --from-path=/backups --overwrite-destination=true
docker compose up -d --wait
```

Without compose, the same with `docker run --rm -v <neo4j data volume>:/data -v "$PWD/backup:/backups"
neo4j:2026.10-community neo4j-admin database ...`, the Neo4j container stopped. LDS makes the constraints and
indexes it needs again at start when they are missing.

## The search index in Solr

Copy the Solr volume while Solr is stopped. The Solr image has `tar`:

```sh
mkdir -p backup
docker compose stop -t 60 lds
docker compose stop solr
docker compose run --rm --no-deps -v "$PWD/backup:/backup" \
  --entrypoint tar solr czf /backup/solr.tgz -C /var/solr .
docker compose up -d --wait
```

Restore, Solr stopped:

```sh
docker compose run --rm --no-deps -v "$PWD/backup:/backup" \
  --entrypoint sh solr -c 'rm -rf /var/solr/* && tar xzf /backup/solr.tgz -C /var/solr'
```

Count what the core holds before and after, on the Solr port of the machine (8983 in the stack):

```sh
curl -s 'http://localhost:8983/solr/lds-index/select?q=*:*&rows=0' | jq '.response.numFound'
```

In the run the count was 2 at the backup, 3 after one more write, and 2 after the restore.

## A whole stack at once

The simplest consistent backup of the compose stack is all of it stopped: `docker compose ... stop -t 60`, then
the three commands above (each one-off container works on a stopped service), then `docker compose ... up -d
--wait`. `docker compose ... down -v` removes the volumes and with them every store: never use `-v` on a stack
whose data is wanted.

[Running LDS: What to keep safe](https://lds.descoped.io/docs/user/operations/#what-to-keep-safe).
