---
title: "What's new"
description: "What each release of LDS gives those who use it, in a few words: what you can do now, and what was fixed."
order: 1
---

# What's new

What each release gives you, told from your side: what you can do, what was fixed, and what to do when you move up
from the release before. The full notes of each release are on
[GitHub](https://github.com/descoped/linked-data-store-project/releases).

## 3.0.1

Search that finds what you wrote, writes that never lose a change, and an LDS that says how it is.

### Search

- **Every text is searched**, also in embedded objects, in lists and in fields the model does not have. Before, only
  fields named `languageText` were.
- **By language.** Write a text as `{"languageCode": "nb", "languageText": "..."}` and *luftskip* finds
  *luftskipene*: words match by their stem in Norwegian bokmål and nynorsk, Swedish, English, German and French.
- **Without the special letters.** *boker* finds *bøker*, *Aase* finds *Åse*; the word as written ranks first.
- **At a moment.** A search with a `snapshot` sees each document as it was then; `history: true` gives every version
  that matched.
- **Within a second**, and `?searchable=true` on a write answers only when a search finds it (`LDS-Searchable: true`).
- **No update is lost** when Solr is slow or away: it waits in a queue until Solr takes it.
- **Rebuild** the index from storage with `search.index.rebuild=true`, while search keeps answering.

[The GraphQL API](user/graphql-api.md#search) tells how to search.

### Writes

- **Writes to one document take turns**, so a part and a link written at the same moment are both kept.
- **A write is never put in the past**, also when the clock of the machine is set back.
- **Parts and links are answered when they are written**, as whole documents are.
- **A failed write leaves nothing behind.** A field written as `null` is kept as absent.
- **Clear refusals:** a body that is not JSON gets 400 with the line and column; the wrong content type 415; an id
  or a path over its limit 400 that says which.

### Reads

- **A missing document is `null` in GraphQL**, not an error, and the rest of the answer is kept.
- **New `Long`** for 64-bit numbers; `Int`, `Float`, `Date` and `Time` are checked when written.
- **The same order of ids in every store**, and lists that stream from the store.

### Running it

- **`/health/ready`** says whether LDS can take writes, and which part is down when it cannot: `UP`, `DEGRADED` or
  `DOWN`. `/health/alive` answers while the process runs.
- **A stop in order:** requests being handled finish, new ones get 503, and nothing is left running.
- **A misspelled `LDS_...` variable stops the start** and names it, instead of being passed over.

### Moving up from 3.0.0

- **Solr:** start once with `search.index.rebuild=true`, so search finds what 3.0.0 indexed.
- **Neo4j:** a document written by 3.0.0 is left out of GraphQL pages until it is written again. Reads by id and
  REST lists find it.
- **Clients:** `XById` can now be `null`; a field written as `null` is read back absent.
- **Health checks:** the images declare none. Ask `/health/ready`, as the compose file does.

## 3.0.0

The first release of LDS 3: a time machine for JSON documents and the links between them. You describe your data
once, and LDS keeps every version of every document and gives it back as it was at any moment.

### Keep every version

- **Every write adds a version**, valid from its own time: the time you give, in the past, the present or the
  future, or else now. Nothing is overwritten.
- **Read any moment.** Add `?timestamp=` to a read and get the document as it was then, or 404 before it existed.
- **Follow links in time.** A link is followed to the version of the other document that was valid at the same
  moment, so a whole namespace can be read as one snapshot.
- **A delete is a version too:** the document is gone from that time until it is written again, and is still there
  in reads of earlier moments.
- **See the history** of a document with `?timeline`: every version and the time it holds from.

[Concepts](user/concepts.md#snapshots-the-time-machine) explains the time machine.

### Describe your data once

- **A model** as a GraphQL schema, or as JSON Schema 2020-12 files, read when LDS starts.
- **A REST API and a GraphQL API** made from it, with nothing to code.
- **Every document is checked** against the model before it is written, and a refusal says what is wrong and where:
  `$.tags[1]: integer found, string expected`.
- **Links** are fields that name the entity types they may point at; GraphQL follows them both ways.
- `?schema` gives the JSON Schema LDS checks with.

[The model](user/model.md) tells how to write one.

### The REST API

- **Whole documents** at `/{namespace}/{Entity}/{id}`: write with `PUT`, read with `GET`, delete with `DELETE`, and
  list every document of an entity type.
- **Parts of a document** by their path, such as `/ns/Person/ola/address`, read and written on their own.
- **Links** added and removed one at a time: `PUT /ns/Person/ola/employer/Company/acme`.
- **Answered when written** with `sync=true`, or at once, with the id of the write to follow it.
- **Safe to send again:** a write sent twice with the same time gives the same version.
- **A prefix and CORS** for running behind a proxy and for calls from a browser.

[The REST API](user/rest-api.md) has every address and answer.

### The GraphQL API

- **Queries made from the model:** one document by id, every document of an entity type, in pages.
- **Links followed** in one query, also backwards: who links to this document.
- **Read at a time:** a `snapshot` makes the whole query read as of that moment.
- **Search** by words, with Solr.
- **A page for trying queries** in the browser, at `/graphiql`.

[The GraphQL API](user/graphql-api.md) shows every kind of query.

### Writes you can trust

- **Every write is logged before it is done**, in the transaction log, and is then written to the store and the
  search index.
- **A write that was cut off is finished**: by the next write, after a restart, or by another LDS on the same saga
  log.

[Running LDS](user/operations.md#writes-that-fail-crashes-and-recovery) tells what happens when a write fails.

### Your choice of store

- **In memory**, to try LDS and for tests.
- **PostgreSQL 18**, in one table.
- **Neo4j 2026**, as a graph, with every link a relationship.

The same requests and the same answers whichever you pick.
[Configuration](user/configuration.md#choosing-where-lds-keeps-its-data) tells how to choose.

### Run it anywhere

- **Images on Docker Hub**, `descoped/lds`, for Intel and ARM, signed: start LDS with one `docker run`.
- **Three variants:** the regular image with a small shell, `-distroless` with nothing but Java for production, and
  `-debug` with the JDK's tools for diagnostics.
- **Configured by environment variables**, `LDS_...`, one setting each.
- **A compose file** that starts LDS with Neo4j, PostgreSQL and Solr, for development.

[Getting started](user/getting-started.md) goes from nothing to a first document.

### Not finished in 3.0.0

- Two writes to parts or links of one document at the same moment can lose a change (fixed in 3.0.1).
- Search finds only texts in fields named `languageText` (fixed in 3.0.1).
- LDS answers for one namespace, and lists over REST are not paged.
- A failed write is answered 500 without saying whether it will be finished later.
