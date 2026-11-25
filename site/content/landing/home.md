---
title: "Linked Data Store"
description: >-
  A time machine for JSON documents and the links between them: Linked Data Store keeps every version of every
  document and reads any instant, over REST and GraphQL. LDS 3.0.1 is released as images on Docker Hub.
eyebrow: "LDS 3.0.1 is released"
headline: "A time machine for your documents and the links between them"
subline: >-
  LDS keeps every version of every JSON document you write. Ask for any moment, past, present or future, and your
  data comes back as it was then, with every link followed to that same moment.
primary-label: "Get started"
primary-href: "/docs/user/getting-started/"
secondary-label: "Read the concepts"
secondary-href: "/docs/user/concepts/"
---

## The problem

When a record changes, most databases overwrite it and the old value is gone. To answer where Kari Nordmann lived
last June, teams add audit tables, triggers or versioned rows, and every query then joins those histories on ranges
of dates.
When records refer to each other, every history has to line up at the same moment, and that is where it gets hard.

## How LDS solves it

LDS makes history part of the data. Every write adds a version that is valid from a date you choose, in the past,
now or in the future. Ask for a moment and every document comes back as it was then, with every link followed to the
version valid at that same moment. There are no audit tables to maintain and no date-range joins to write.
[Read the concepts](/docs/user/concepts/)

## Documents that link to each other

A document is JSON at an address such as `/ns/Person/ola`, shaped by your model. It holds plain values, embedded
objects that are versioned with it, and links to other documents that keep versions of their own. Read Ola Nordmann
as of June 2022, and LDS follows his employer link to the company as it was in June 2022 too.
[The model](/docs/user/model/)

## Writes that pick up where they stopped

Every write touches more than one system: a log of every change, the document store and, when search is on, the
queue that feeds the search index. No single transaction covers all three, so LDS runs each write as a saga.

A saga is a write broken into steps that run in a fixed order, and each finished step is noted in a saga log before
the next one starts. If LDS stops halfway, the saga log tells it where the write was. When LDS runs again, it skips
the steps that are done and completes the rest with the data that was first sent.

Keep the saga log in PostgreSQL, and this holds across restarts and across several LDS that share it.
[When a write fails](/docs/user/operations/#writes-that-fail-crashes-and-recovery)

## One model gives you both APIs

Describe your entity types once, as a GraphQL schema or as JSON Schema files. LDS turns the model into a REST API
and, from a GraphQL schema, a GraphQL API whose links you can follow in both directions. Every document is checked
against the model before it is written, and a rejected write names the field that is wrong.
[The model](/docs/user/model/)

## Query any moment with GraphQL

Give a GraphQL query a snapshot, and the whole query reads your data as it was at that moment: every document, every
link and every link back to it. Search works the same way. With Solr, find documents by the words they held then,
matched by their stems in Norwegian, Swedish, English, German and French, with ø and å optional.
[The GraphQL API](/docs/user/graphql-api/)

## Choose where your documents are stored

Start with the in-memory store, then move to PostgreSQL or Neo4j when you are ready. Every store passes the same
suite of tests against the real database, so your requests and answers stay the same whichever you choose. Whichever
store holds your documents, keep the saga log in PostgreSQL so that a write cut off by a restart is completed.
[Configuration](/docs/user/configuration/#choosing-where-lds-keeps-its-data)

### In memory

The default, made for trying LDS and for tests. Nothing is kept when LDS stops.

### PostgreSQL 18

Every document in one table, which LDS creates when it starts. Reads are streamed from the database as you ask for
them.

### Neo4j 2026

Your documents as a graph, where every link is a relationship to the document it points at. The community edition is
enough.

## Run it on your machine

Start LDS with Docker. The first command runs it in memory with an example model, so there is nothing to set up. The
second starts the whole stack instead, with Neo4j for the documents, PostgreSQL for the saga log and the change log,
and Solr for search, and keeps your data until `docker compose -p lds down -v` removes it.

Then write a company that is valid from 1 January 2020 and read it back as of 1 June 2021. The write answers with the
id of its saga, and the read gives the version that was valid on that date.
[Getting started](/docs/user/getting-started/) goes further.

```sh
docker run --rm -p 127.0.0.1:9090:9090 descoped/lds:3.0.1
```

```sh
curl -fsSL https://lds.descoped.io/compose.yaml \
  | docker compose -f - up -d --wait
```

```console
$ curl -X PUT \
    'http://localhost:9090/ns/Company/fjord?timestamp=2020-01-01T00:00:00Z' \
    -H 'Content-Type: application/json' -d '{"name": "Fjord Fish"}'
{"saga-execution-id": "01a211ec-0dfd-7c3c-9f3f-6cda8a834902"}
$ curl 'http://localhost:9090/ns/Company/fjord?timestamp=2021-06-01T00:00:00Z'
{"name": "Fjord Fish"}
```

## Or ask Claude to do it

Never used LDS? Install the `lds` plugin for Claude Code and describe your domain in plain words. Claude starts LDS,
builds the model with you, and writes, reads and queries your documents. Ask, for example, "model a library with
books, authors and loans, and load these members from members.csv". [With Claude Code](/docs/user/claude-code/)

```text
/plugin install lds --marketplace descoped/linked-data-store-project
```

## Where LDS fits

LDS fits data whose history is part of the data.

- **Registers and master data.** Data that changes over time and is read as it was.
- **Reports and audits.** The data as it stood on a past date.
- **Linked models.** Documents that link to each other, read together at one moment.
- **Planned changes.** Written today, with a date in the future.

## Where LDS does not fit

These are facts of LDS 3.0.

- **Recorded time.** You need to know when a version was recorded as well as when it is valid. LDS keeps the valid
  time only.
- **Access control.** LDS has no authentication, so anyone who reaches its port can read and write.
- **Checked links.** You need a link checked on write, or a delete refused while other documents link to it. LDS
  does neither.
- **Several writers.** Several LDS write the same document at the same time. Writes to one document take turns
  within one LDS, not across several.
