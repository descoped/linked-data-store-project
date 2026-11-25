---
title: "Linked Data Store"
description: >-
  A time machine for JSON documents and the links between them: Linked Data Store keeps every version of every
  document and reads any instant, over REST and GraphQL. LDS 3.0.1 is released as images on Docker Hub.
eyebrow: "LDS 3.0.1 is released"
headline: "A time machine for your documents and the links between them"
subline: >-
  Linked Data Store (LDS) keeps every version of every document, each valid from its own time. Read any instant, past,
  present or future, and every document comes back as it was then, with its links followed to the same instant.
primary-label: "Get started"
primary-href: "/docs/user/getting-started/"
secondary-label: "Read the concepts"
secondary-href: "/docs/user/concepts/"
---

## The problem

Most stores keep the present state of their data. When a record changes, the value before it is gone, and a question
such as "where did this person live on 1 June 2021" needs an audit table or a history put together afterwards.

## The time machine

Every write adds a version of a document, valid from the time given with the write, in the past, the present or the
future, or else from the time LDS receives it. A read at an instant gives the document as it was then, and a whole
namespace read at one instant is a snapshot: every document at its version valid then, every link resolved then.
[Concepts](/docs/user/concepts/)

## What a document is made of

A document is a JSON object at an address such as `/ns/Person/ola`. A field holds a value, an embedded object that is
versioned with the document, or a link to another document, which has versions of its own. A read follows the link to
the version that was valid at the same instant. [The model](/docs/user/model/)

## A write is a saga

The transaction log is written first, then the store, and with search an update is added to the index queue. Each
step is recorded in a saga log, and a write that did not complete, because a step failed or LDS stopped, is run again
from it later. [The write path](/docs/developer/core-write-path/)

## A model, read at start

You describe the entity types as a GraphQL schema or as JSON Schema 2020-12 files. LDS makes its REST addresses and
its GraphQL schema from the model, and checks every document against it before the document is written.

## Storage you choose

Documents are kept in memory, in PostgreSQL 18 or in Neo4j 2026, and searched with Solr 10. Every persistence
provider passes the same suite of tests against the real engine. [Architecture](/docs/developer/architecture/)

## See it

Start LDS from its image on Docker Hub, in memory, with an example model. Write Company/fjord, valid from 1 January
2020, and read it as of 1 June 2021.

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
```

```console
$ curl -X PUT \
    'http://localhost:9090/ns/Company/fjord?timestamp=2020-01-01T00:00:00Z' \
    -H 'Content-Type: application/json' -d '{"name": "Fjord Fish"}'
{"saga-execution-id": "01a211ec-0dfd-7c3c-9f3f-6cda8a834902"}
$ curl 'http://localhost:9090/ns/Company/fjord?timestamp=2021-06-01T00:00:00Z'
{"name": "Fjord Fish"}
```

## With Claude

Never used LDS? Ask for it in plain words. The plugin `lds` for Claude Code starts LDS, makes a model of your domain
with you, and writes, reads and queries the documents for you. [With Claude Code](/docs/user/claude-code/)

```text
/plugin install lds --marketplace descoped/linked-data-store-project
```

## When it fits

When the history of the data is part of the data: registers, master data and reference data that change over time
and are read as they were, and models whose documents link to each other.

## When it does not fit yet

- When you need every part finished: 3.0.1 has known gaps, listed in its release notes.
- When you need to know when a version was recorded as well as when it holds: LDS keeps valid time only.
- When a write must be refused because its link points at nothing: links are not checked when written.

## Status

LDS 3.0.1 is released as signed images on Docker Hub, `descoped/lds`, for Linux on amd64 and arm64.
[Release notes](https://github.com/descoped/linked-data-store-project/releases/tag/3.0.1)
