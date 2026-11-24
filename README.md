[![CI](https://github.com/descoped/linked-data-store-project/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/descoped/linked-data-store-project/actions/workflows/ci.yml) [![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=descoped_linked-data-store-project&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=descoped_linked-data-store-project) [![Reliability rating](https://sonarcloud.io/api/project_badges/measure?project=descoped_linked-data-store-project&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=descoped_linked-data-store-project) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=descoped_linked-data-store-project&metric=coverage)](https://sonarcloud.io/component_measures?id=descoped_linked-data-store-project&metric=coverage) [![Project status: Active](https://www.repostatus.org/badges/latest/active.svg)](https://www.repostatus.org/#active) [![Docker Hub](https://img.shields.io/docker/v/descoped/lds?sort=semver&label=docker%20hub)](https://hub.docker.com/r/descoped/lds) [![License](https://img.shields.io/github/license/descoped/linked-data-store-project)](LICENSE)

# Linked Data Store

Linked Data Store (LDS) is a server that gives REST and GraphQL access to versioned, linked JSON documents. The
model is read at start, from a GraphQL schema or JSON Schema 2020-12 files, so the same server serves any structured
data.
Every document version has a "valid from" time, and a read returns the document as it was at the time asked for.
Persistence and search are pluggable.

**Status: 3.0.1 is released** as images of the server on Docker Hub,
[`descoped/lds`](https://hub.docker.com/r/descoped/lds), for Linux on amd64 and arm64; see the
[release notes](RELEASE-NOTES.md#301). This repository was put together in
November 2026 from the 14 repositories LDS used to live in, with their full history. What 3.0.1 does not have yet
is said in the release notes and in the documentation, in paragraphs that start with "Not finished:".

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
| `linked-data-store-index-queue-postgres` | The index queue in PostgreSQL: the updates the search index has not taken yet |
| `linked-data-store-core` | The application: REST, GraphQL, sagas, transaction log |
| `linked-data-store-server` | The main class, configuration and container image |
| `linked-data-store-bom` | Aligns the versions of the modules above |
| `linked-data-store-loadtest` | A load test of LDS with each persistence provider, run by hand (see its [README](linked-data-store-loadtest/README.md)) |

The documentation is in [`docs/`](docs/README.md), in two parts: for those who use LDS (getting started, concepts,
the model, the REST and GraphQL APIs, configuration, running LDS) and for those who develop and maintain it (the
architecture and one document for each part of the code). The same documents, with a landing page, are published at
https://lds.descoped.io/.

## Build

Needs JDK 27 and Docker with its `docker` command and `docker compose`: the tests start PostgreSQL 18, Neo4j 2026
and Solr 10 in containers, build the image of the server and start the whole stack of the compose file.

```sh
./mvnw verify
```

## Run

The quickest way is the image on Docker Hub. As it comes, LDS keeps everything in memory and has an example model
(`linked-data-store-server/schemas/schema.graphql`, a person and a company); it answers on port 9090.

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
```

From the repository, build first, then start LDS in one of three ways.

```sh
./mvnw package -DskipTests
```

**In a container:**

```sh
docker build -t lds-server linked-data-store-server
docker run --rm -p 127.0.0.1:9090:9090 lds-server
```

**The whole stack**, with Neo4j for the documents, PostgreSQL for the saga log and the transaction log, and Solr
for search. For development only: the passwords are written in the file.

```sh
docker compose -f linked-data-store-server/compose.yaml up --build
```

**On the JDK**, from the module directory:

```sh
cd linked-data-store-server
java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

Then:

```sh
curl -X PUT 'http://localhost:9090/ns/Company/acme?sync=true' -H 'Content-Type: application/json' \
     -d '{"name":"Acme"}'
curl -X PUT 'http://localhost:9090/ns/Person/ola?sync=true' -H 'Content-Type: application/json' \
     -d '{"name":"Ola","employer":"/Company/acme"}'
curl 'http://localhost:9090/ns/Person/ola'
curl 'http://localhost:9090/graphql' -H 'Content-Type: application/json' \
     -d '{"query":"{ CompanyById(id: \"acme\") { name employees { edges { node { name } } } } }"}'
```

### Configuration

The main class is `io.descoped.lds.server.Server` in `linked-data-store-server`. Configuration is read in this
order, later sources winning: the defaults of core
(`linked-data-store-core/src/main/resources/application-defaults.properties`), the defaults of the server
(`linked-data-store-server/src/main/resources/application-server-defaults.properties`, which lists the keys of
every provider), `conf/application.properties` and `conf_dev/application.properties` under the working directory
(`/lds` in the image), environment variables prefixed `LDS_`, and system properties.

The configuration is read with dynamic-configuration 3, which has these rules:

- An environment variable is the key in upper case with `_` for every other character, after the prefix:
  `LDS_HTTP_PORT` sets `http.port`, `LDS_NEO4J_DRIVER_URL` sets `neo4j.driver.url`.
- An environment variable or a system property (`-Dhttp.port=8080`) sets only a key that one of the properties
  files has. The defaults of the server have the keys LDS is usually configured with; any other key of a provider
  is given in `conf/application.properties`, and can then be set from outside as well. An environment variable that
  starts with `LDS_` and sets no key, such as one spelled wrong, stops LDS at start with its name.
- `${key}` in a value is the value of another key, and `${key:default}` gives it a default.
- A boolean is `true` or `false`; anything else stops LDS at start with the name of the key.

In the image, the model is read from `/lds/schemas` (mount your own there), options of the JVM are given in
`JAVA_TOOL_OPTIONS`, and LDS runs as the user `lds`. The image declares no health check: LDS answers `/ping` and
`/health/alive` whenever it runs, and `/health/ready` with 200 when it can take writes and 503 when it cannot, under
`http.prefix` and on its port, and whoever runs it asks there (the compose file asks `/health/ready`). The image is
published on Docker Hub as `descoped/lds` in three variants: the regular image with a shell (`3.0.1`), one without
(`3.0.1-distroless`) and one with the tools of the JDK for diagnostics (`3.0.1-debug`).

Search finds a document by every text in it. A text in an object with a `languageCode` and a `languageText` is found
by the stems of its words in its language (Norwegian bokmål and nynorsk, Swedish, English, German or French), and
every other text word for word; a word is also found when it is typed without its special letters or accents. Links,
dates, times, numbers and booleans are not searched. A write does not wait for search unless it asks to
(`searchable=true`).

## With Claude Code

The repository is also a marketplace of plugins for Claude Code. Its plugin `lds` starts LDS, makes a model of a
domain with you, and writes, reads and queries documents when you ask in plain words:

```text
/plugin install lds --marketplace descoped/linked-data-store-project
```

[With Claude Code](docs/user/claude-code.md) tells what it can do. The plugin is in `plugins/lds`.

## History

LDS was first written at Statistics Norway for GSIM-based systems and has been maintained by Descoped since 2024.
The tags of the old repositories are kept with the repository name as prefix, for example
`linked-data-store-core/v0.16`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
