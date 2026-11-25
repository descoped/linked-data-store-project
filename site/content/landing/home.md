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

In most databases an update overwrites the record, and the value before it is gone. Keeping the history takes
triggers and audit tables, or versioned rows joined on ranges of dates. A question such as "where did this person
live on 1 June 2021, and who employed them then" becomes a query across several histories that must line up at the
same instant.

## The solution

LDS keeps every version of every document, each valid from its own time, in the past, the present or the future. Ask
for an instant and every document comes back as it was then, with each link followed to the version that was valid
at the same instant. The history is the data: no audit tables, and no joins on ranges of dates.
[Concepts](/docs/user/concepts/)

## What a document is made of

A document is a JSON object at an address such as `/ns/Person/ola`. A field holds a value, an embedded object that is
versioned with the document, or a link to another document, which has versions of its own. A read follows the link to
the version that was valid at the same instant. [The model](/docs/user/model/)

## A write is a saga

A write reaches more than one place: the transaction log, the store and, with search, the index queue. No
transaction spans them, so a crash between two of them would leave them disagreeing. Each write is therefore a saga:
its steps run one after the other, and the saga log notes each step that is done. A write that was cut off is run
again from the saga log, with the request as it was first sent: the steps that are done are skipped, and the rest
are run. With the saga log in PostgreSQL this holds across a restart.
[Writes that fail](/docs/user/operations/#writes-that-fail-crashes-and-recovery)

## Describe your data once

Describe your entity types as a GraphQL schema or as JSON Schema 2020-12 files. LDS makes its REST API from the
model, and from a GraphQL schema its GraphQL API too, and checks every document against the model before it is
written. [The model](/docs/user/model/)

## Search

With Solr 10, every text of a document is searched through GraphQL. A text written with its language is found by
the stems of its words in Norwegian, Swedish, English, German or French, a word typed without its special letters
finds the word with them, and a search sees each document as it was at a moment.
[Search](/docs/user/graphql-api/#search)

## Storage options

The documents are kept in one of three stores, chosen in the configuration, and every store passes the same suite of
tests against the real engine. The saga log and the transaction log are kept in memory or in PostgreSQL, and a write
that a restart cut off is completed only with the saga log in PostgreSQL.
[Configuration](/docs/user/configuration/#choosing-where-lds-keeps-its-data)

### In memory

The default, for trying LDS and for tests. Nothing is kept when LDS stops.

### PostgreSQL 18

Every document in one table, which LDS makes when it starts.

### Neo4j 2026

Every document a graph of nodes, and every link a relationship to the document it points at. The community
edition is enough.

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

LDS fits data whose history is part of the data:

- registers, master data and reference data that change over time and are read as they were;
- reports and audits that must show the data as it stood at a past date;
- models whose documents link to each other, read together at one instant;
- changes known in advance, written now with a valid time in the future.

## When it does not fit

- You need to know when a version was recorded as well as when it holds: LDS keeps valid time only, and a write at
  the same valid time replaces the version that is there.
- You need LDS to control who reads and writes: it has no authentication, and whoever reaches its port can read and
  write every document.
- A write must be refused when its link points at nothing: links are not checked, so a link can name a document that
  is written later.
- You need several namespaces over REST, or a model that changes while LDS runs: the REST API answers for one
  namespace, and the model is read at start.
