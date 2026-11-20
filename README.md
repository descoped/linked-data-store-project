# Linked Data Store

Linked Data Store (LDS) is a server that gives REST and GraphQL access to versioned, linked JSON documents. The
model is read at start, from a GraphQL schema or JSON Schema files, so the same server serves any structured data.
Every document version has a "valid from" time, and a read returns the document as it was at the time asked for.
Persistence and search are pluggable.

**Status: 3.0.0 is in development and nothing is released yet.** This repository was put together in November 2026
from the 14 repositories LDS used to live in, with their full history. The modules are being revised one by one;
until that is done, treat the code as work in progress.

## Modules

| Module | What it is |
|---|---|
| `linked-data-store-persistence-provider-api` | The interface a persistence backend implements |
| `linked-data-store-persistence-provider-tck` | The tests every persistence provider must pass |
| `linked-data-store-persistence-provider-postgres` | PostgreSQL |
| `linked-data-store-persistence-provider-neo4j` | Neo4j |
| `linked-data-store-persistence-provider-memory` | In memory, for tests and trials |
| `linked-data-store-search-provider-api` | The interface a search index implements |
| `linked-data-store-search-provider-solr` | Apache Solr |
| `linked-data-store-core` | The application: REST, GraphQL, sagas, transaction log |
| `linked-data-store-server` | The main class, configuration and container image |
| `linked-data-store-bom` | Aligns the versions of the modules above |

`docs/` holds the documentation from before the merge; it describes the earlier layout and is being rewritten.
`linked-data-store-loadtest/` and `lds-cluster-test/` are earlier tooling that is not part of the build.

## Build

Needs JDK 27 and Docker (the PostgreSQL tests start PostgreSQL 18 in a container).

```sh
./mvnw verify
```

## Run

The main class is `io.descoped.lds.server.Server` in `linked-data-store-server`. Configuration is read in this
order, later sources winning: the defaults in
`linked-data-store-core/src/main/resources/application-defaults.properties` (port 9090), the server's own defaults
(in-memory persistence), `conf/application.properties`, `conf_dev/application.properties`, environment variables
prefixed `LDS_`, and system properties.

The configuration is read with dynamic-configuration 3, which has these rules:

- An environment variable is the key in upper case with `_` for every other character, after the prefix:
  `LDS_HTTP_PORT` sets `http.port`.
- An environment variable or a system property (`-Dhttp.port=8080`) sets only a key that one of the properties
  files has. The keys of a provider (`postgres.driver.*`, `neo4j.driver.*`, `search.index.url`, `sagalog.config.*`,
  `txlog.config.*`) are in no default file: give them in `conf/application.properties`, and they can be set from
  outside as well.
- `${key}` in a value is the value of another key, and `${key:default}` gives it a default.
- A boolean is `true` or `false`; anything else stops LDS at start with the name of the key.

The `conf/` and `conf_dev/` files, the Dockerfiles and the compose file in `linked-data-store-server` are from
before the merge: they expect Neo4j, PostgreSQL and Solr from that compose file and a schema folder that a script
used to fetch. They are being rebuilt with the server module, so there is no supported way to start a full LDS from
this repository yet. The tests in `linked-data-store-core` start the application in memory and show how it is
configured.

## History

LDS was first written at Statistics Norway for GSIM-based systems and has been maintained by Descoped since 2024.
The tags of the old repositories are kept with the repository name as prefix, for example
`linked-data-store-core/v0.16`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
