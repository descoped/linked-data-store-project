# Release notes

## 3.0.1

The second release of Linked Data Store 3: the images of the server on Docker Hub, `descoped/lds:3.0.1`, for
linux/amd64 and linux/arm64, in the same three variants as 3.0.0 (regular, `-distroless`, `-debug`), signed with
cosign by the release workflow of this repository. What a client sees differently from 3.0.0 is said below.

### Search

- **Every text of a document is searched**, not only fields named `languageText`: texts in embedded objects, in
  lists and in fields the model does not have. Links, dates, times, numbers and booleans are not searched, and
  `@domain(searchable: false)` keeps an entity type out.
- **By language:** a text in an object with `languageCode` and `languageText` is searched by the stems of its words
  in Norwegian (bokmål and nynorsk), Swedish, English, German or French; every other text word for word. A word is
  also found typed without its letters (`boker` finds "bøker", `Aase` finds "Åse"), and the word as written ranks
  first. A letter and its accent written as a mark of its own are the same letter.
- **At a moment:** a search looks at each document as it was at the snapshot, so a word only an older version had
  does not find it; `history: true` gives every matching version, each read at that version.
- **Found within about a second** (`search.index.commit-within`), and a write sent with `?searchable=true` is
  answered when a search finds it, at most 10 seconds, with the header `LDS-Searchable: true` or `false`.
- **No update of the index is lost:** a write does not wait for search; its update waits in an index queue (in
  PostgreSQL with the transaction log, or in memory) until Solr has taken it.
- **A rebuild:** the start option `search.index.rebuild` sends every version of every document to the index again,
  while search answers, and then removes what storage no longer holds. A Solr core used by 3.0.0 needs it once.
- LDS puts its own schema into the Solr core. No words find nothing; words that are no Solr query are read as words.

### Writes

- **One write at a time to a document**, in each LDS: writes of parts and links to one document at the same time no
  longer lose changes. A write waits for its turn without holding a thread, at most 30 seconds, then 503.
- **Parts and links wait** for their write, as documents do, unless `sync=false`.
- **A failed write leaves nothing.** A `null` field is written as absent.
- **Clear answers:** a body that is not JSON is 400 with the line, the column and the reason; another content type
  415; ids and paths over the limits (an id is at most 1024 bytes of UTF-8) are 400 with a text that says which.
  `/source` is 409 when no transaction log is kept.

### Reads and GraphQL

- **Lists stream** from the store, and a list is in the order of the code points of its ids in every store.
- **`XById` is nullable:** an id no document has answers `null` for that field and the rest of the answer is kept.
  An absent link is `null`; a list of links leaves out what is missing.
- **Typed scalars:** `Int` is 32 bits, `Long` (new) 64 bits, `Float`, `Date` and `Time` are checked when written.
- GraphQL answers 400 or 415 with a JSON error for a request it cannot read, and reads a body as UTF-8.

### Running it

- **Readiness:** `/health/ready` asks the store, the saga log, the transaction log, and with search Solr and the
  index queue, each within 2 seconds, and answers `UP`, `DEGRADED` (200) or `DOWN` (503) with a JSON body.
  `/ping` and `/health/alive` answer whenever the process does.
- **The images declare no health check**: whoever runs LDS asks `/ping` or `/health/ready` at the port and prefix
  it gave (the compose file asks `/health/ready`).
- **A stop in order:** every part of LDS is stopped in the reverse order of its start; requests being handled
  complete, new ones are answered 503. A start that fails part of the way stops what it made and ends the process.
- **Configuration:** a variable `LDS_...` that sets no key stops the start; the saga log's owner defaults to `lds`,
  which the saga log in PostgreSQL takes; LDS does not start when the saga log's pool is too small for its logs.
- Saga 2.2.0, rawdata 3.1.0. SNAPSHOTs of the modules are published to <https://maven.descoped.io/>; no jar is
  released.

### From 3.0.0

- **Solr:** a core used by 3.0.0 needs one start with `search.index.rebuild=true`: entries of 3.0.0 are not found by
  the search of 3.0.1, and the rebuild removes them.
- **Neo4j:** a document written by 3.0.0 has no place in the order of ids that 3.0.1 pages by, so GraphQL pages leave
  it out until it is written again. A read by id and a list over REST find it. PostgreSQL and memory need nothing.
- **Clients:** `XById` can be `null`; a part or a link written without `sync` waits for its write; a field written as
  `null` is read back absent.
- **Running it:** the images declare no health check; a misspelled `LDS_` variable stops the start.

### Not finished

The large items, each said where it is met in the documentation, in a paragraph that starts with "Not finished:":

- Reads of the store still hold a request thread, and lists over REST are not paged.
- LDS answers for one namespace over HTTP, the one in `namespace.default`.
- A failed saga is answered 500 with no body, and the caller cannot tell whether recovery will do the write.
- Several LDS in one cluster need an instance id each, which nothing derives, and cannot share a topic of the
  transaction log in PostgreSQL. The turn of a write to a document is kept in each LDS.
- No provider reads the delete policy.
- A single field of an embedded type of a GraphQL schema is not validated inside.
- The memory provider has known faults.
- Modules still override the lint or the Javadoc rule, and 12 tests of core are disabled.

### Run it

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
```

LDS then answers on port 9090, in memory, with an example model. [Getting started](https://lds.descoped.io/docs/user/getting-started/)
goes on from there. To build it from the source you need JDK 27, and Docker with `docker compose` for the tests:

```sh
git clone --branch 3.0.1 https://github.com/descoped/linked-data-store-project.git
cd linked-data-store-project
./mvnw verify
```

## 3.0.0

The first release of Linked Data Store 3, and the first that is published: as images of the server on Docker Hub,
for linux/amd64 and linux/arm64. It is built from this repository, which holds the 14 repositories LDS used to live
in, with their full history. Nothing was released of LDS 3 before it, so it carries no compatibility with earlier
versions.

### What LDS is

LDS is a time machine for JSON documents and the links between them. It is a server that gives REST and GraphQL
access to versioned, linked JSON documents described by a model read at start.

- Every write adds a version of a document, valid from its own time: the time given with the write, in the past, the
  present or the future, or else the time LDS receives it.
- A read at an instant gives the document as it was then. A link is followed to the version of the other document
  that was valid at the same instant, so a whole namespace can be read as a snapshot.
- A delete is a version too: the document is not there from that time until its next version.

### The images

`descoped/lds` on Docker Hub, in three variants of the same LDS:

| Tags | What it is for |
|---|---|
| `3.0.0`, `3.0`, `3`, `latest` | The regular image. It has a shell and the small tools of busybox (`sh`, `wget`, `ls`, `ps`, ...), for looking around in a running container. |
| `3.0.0-distroless`, `3.0-distroless`, `3-distroless`, `distroless` | No shell and no program but `java`: for production, and where no shell is ever used (Cloud Run, Fargate). |
| `3.0.0-debug`, `3.0-debug`, `3-debug`, `debug` | The regular image with the tools of the JDK for diagnostics: remote JMX, a remote debugger, `jcmd`, `jstack`, `jmap`, `jstat`, `jhsdb`, JFR and heap dumps. |

Each holds a Java 27 runtime made with `jlink` from the modules of the JDK that LDS uses, on Google's distroless
images of Debian 13 (glibc, CA certificates, time zones). LDS runs as the user `lds`, answers on port 9090, has a
health check on `/ping`, and stops in order when the container is stopped. It is configured by environment variables
(`LDS_...`). Every image has an SBOM and the provenance of its build, and is signed with cosign by the release
workflow of this repository:

```sh
cosign verify descoped/lds:3.0.0 \
  --certificate-identity https://github.com/descoped/linked-data-store-project/.github/workflows/release.yml@refs/heads/master \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

### What is in it

- **The model.** Entity types are described as a GraphQL schema or as JSON Schema 2020-12 files. LDS makes its REST
  addresses and its GraphQL schema from the model, and checks every document against it before it is written.
- **The REST API.** Documents, parts of documents and links, read as of any instant (`?timestamp=`), with the list
  of a document's versions (`?timeline`) and its JSON Schema (`?schema`).
- **The GraphQL API.** Queries made from the model, with paging, links followed at the same instant, as-of reads
  and search.
- **The write path.** A write is a saga: the transaction log is written first, then the store and the search index
  side by side. The saga log, in PostgreSQL, lets a write that did not complete be run again: by the next write,
  after a restart, or by another instance of LDS.
- **Persistence.** Three providers, chosen by configuration: memory (for tests and for trying LDS), PostgreSQL 18
  (one table) and Neo4j 2026 (a graph). The interface streams, on RxJava 3. Every provider passes the same suite of
  tests against the real engine in a container.
- **Search.** Solr 10, in one core.
- **The server.** One main class, the images on Docker Hub, and a compose file that starts LDS with Neo4j,
  PostgreSQL and Solr for development.
- **The build.** One Maven reactor on JDK 27, every module with code a named module, compiler warnings as errors,
  the tests against PostgreSQL, Neo4j and Solr in containers, and a SonarCloud analysis in CI.
- **The documentation.** `docs/`, for those who use LDS and for those who develop it, with a glossary. It is also
  published as a site at <https://lds.descoped.io/>.

### Since 3.0.0-M1

What changed since the milestone
[3.0.0-M1](https://github.com/descoped/linked-data-store-project/releases/tag/3.0.0-M1), a tag of the source:

- The images: three variants, a runtime of only the modules LDS uses, on distroless, for amd64 and arm64, published
  by a release workflow that is started by hand.
- The health check of the image is a small Java program, so it needs no shell.
- More tests: coverage of the code is about 88 %, and the SonarCloud analysis has no open bug or code smell.
- CI builds independent modules at the same time and takes unchanged modules from a build cache.

### Not finished

These are the large items that are not finished in 3.0.0; the documentation says where each one is met, in a paragraph
that starts with "Not finished:".

- Streaming ends in the HTTP handlers, and lists over REST are not paged.
- LDS answers for one namespace over HTTP, the one in `namespace.default`.
- A start that fails late leaves the process running without an open port.
- A failed saga is answered 500 with no body, and the caller cannot tell whether recovery will do the write.
- Two writes to parts or links of one document at the same time lose changes.
- A transaction that is closed commits, also after a failure in its block, and no provider reads the delete policy.
- A single field of an embedded type of a GraphQL schema is not validated.
- Search finds a document by the texts of its fields named `languageText` only, and what search covers is under
  review.
- The memory provider has known faults, one of which can stop every read and write.
- Six modules still override the lint or the Javadoc rule, and 13 tests of core are disabled.

### Run it

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.0
```

LDS then answers on port 9090, in memory, with an example model. [Getting started](https://lds.descoped.io/docs/user/getting-started/)
goes on from there, and [Operations](https://lds.descoped.io/docs/user/operations/) says how to run it with
PostgreSQL, Neo4j and Solr. To build it from the source you need JDK 27, and Docker with `docker compose` for the
tests:

```sh
git clone --branch 3.0.0 https://github.com/descoped/linked-data-store-project.git
cd linked-data-store-project
./mvnw verify
```

## 3.0.0-M1

The first milestone of Linked Data Store 3.0, and the first version built from this repository, which holds the
14 repositories LDS used to live in, with their full history.

A milestone is a tag of the source. Nothing is published for it: no jar, no image, nothing on Maven Central. LDS is
built from the source on JDK 27. 3.0.0 is not released, and a milestone promises no compatibility with the next.

### What LDS is

LDS is a time machine for JSON documents and the links between them. It is a server that gives REST and GraphQL
access to versioned, linked JSON documents described by a model read at start.

- Every write adds a version of a document, valid from its own time: the time given with the write, in the past, the
  present or the future, or else the time LDS receives it.
- A read at an instant gives the document as it was then. A link is followed to the version of the other document
  that was valid at the same instant, so a whole namespace can be read as a snapshot.
- A delete is a version too: the document is not there from that time until its next version.

### What is in it

- **The model.** Entity types are described as a GraphQL schema or as JSON Schema 2020-12 files. LDS makes its REST
  addresses and its GraphQL schema from the model, and checks every document against it before it is written.
- **The REST API.** Documents, parts of documents and links, read as of any instant (`?timestamp=`), with the list
  of a document's versions (`?timeline`) and its JSON Schema (`?schema`).
- **The GraphQL API.** Queries made from the model, with paging, links followed at the same instant, as-of reads
  and search.
- **The write path.** A write is a saga: the transaction log is written first, then the store and the search index
  side by side. The saga log, in PostgreSQL, lets a write that did not complete be run again: by the next write,
  after a restart, or by another instance of LDS.
- **Persistence.** Three providers, chosen by configuration: memory (for tests and for trying LDS), PostgreSQL 18
  (one table) and Neo4j 2026 (a graph). The interface streams, on RxJava 3. Every provider passes the same suite of
  tests against the real engine in a container.
- **Search.** Solr 10, in one core.
- **The server.** One main class, an image built on your machine from `linked-data-store-server/Dockerfile`
  (JRE 27, run as the user `lds`, a health check on `/ping`, a stop in order), and a compose file that starts LDS
  with Neo4j, PostgreSQL and Solr for development.
- **The build.** One Maven reactor on JDK 27, every module with code a named module, compiler warnings as errors,
  the tests against PostgreSQL, Neo4j and Solr in containers, and a SonarCloud analysis in CI.
- **The documentation.** `docs/`, for those who use LDS and for those who develop it, with a glossary. It is also
  published as a site at <https://lds.descoped.io/>.

### Not finished

LDS 3.0 is in development. These are the large items; the documentation says where each one is met, in a paragraph
that starts with "Not finished:".

- Streaming ends in the HTTP handlers, and lists over REST are not paged.
- LDS answers for one namespace over HTTP, the one in `namespace.default`.
- A start that fails late leaves the process running without an open port.
- A failed saga is answered 500 with no body, and the caller cannot tell whether recovery will do the write.
- Two writes to parts or links of one document at the same time lose changes.
- A transaction that is closed commits, also after a failure in its block, and no provider reads the delete policy.
- A single field of an embedded type of a GraphQL schema is not validated.
- Search finds a document by the texts of its fields named `languageText` only, and what search covers is under
  review.
- The memory provider has known faults, one of which can stop every read and write.
- Six modules still override the lint or the Javadoc rule, and 13 tests of core are disabled.

### Build and run

You need JDK 27, and Docker with `docker compose` for the tests and for the two ways to start LDS in containers.

```sh
git clone --branch 3.0.0-M1 https://github.com/descoped/linked-data-store-project.git
cd linked-data-store-project
./mvnw package -DskipTests
docker build -t lds-server linked-data-store-server
docker run --rm -p 127.0.0.1:9090:9090 lds-server
```

LDS then answers on port 9090, in memory, with an example model. [Getting started](https://lds.descoped.io/docs/user/getting-started/)
goes on from there, and `./mvnw verify` builds with every test.
