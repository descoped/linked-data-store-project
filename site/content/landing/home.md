---
title: "Linked Data Store"
description: >-
  A time machine for JSON documents and the links between them: Linked Data Store keeps every version of every
  document and reads any instant, over REST and GraphQL. LDS 3.0 is in development.
eyebrow: "LDS 3.0 is in development"
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
such as "where did this person live on 1 June 2021" needs an audit table, a copy of the database or a history put
together afterwards. Data that refers to other data makes it harder still: a reference has to mean the version that
was valid at the time asked about.

## The time machine

Every write adds a version of a document, and the version holds from its valid time: the time given with the write,
in the past, the present or the future, or else the time LDS receives it. A version holds until the next one begins.

A read at an instant is an as-of read: it gives the document as it was then, or nothing before its first version.
Read a whole namespace at one instant and you have a snapshot: every document at its version valid then, and every
link resolved then. A delete is a version too: the document is not there from that time until its next version.
[Concepts](/docs/user/concepts/)

## What a document is made of

A document is a JSON object of an entity type of the model, at an address such as `/ns/Person/ola`. A field holds a
value, an embedded object that is part of the document and versioned with it, or a link to another document. A link
is the address of that document, `/Company/nordlys`, which has versions of its own. A read follows it to the version
that was valid at the same instant. [The model](/docs/user/model/)

## How it works

### A model, read at start

You describe the entity types as a GraphQL schema or as JSON Schema 2020-12 files. LDS makes its REST addresses and
its GraphQL schema from the model, and checks every document against it before the document is written.
[The model](/docs/user/model/)

### A write is a saga

The transaction log is written first, then the store and the search index side by side. Each step is recorded in a
saga log, and a write that did not complete, because a step failed or LDS stopped, is run again from it later.
[The write path](/docs/developer/core-write-path/)

### Storage you choose

Documents are kept in memory, in PostgreSQL 18 or in Neo4j 2026, and searched with Solr 10. Every persistence
provider passes the same suite of tests against the real engine. [Architecture](/docs/developer/architecture/)

## See it

Read Company/fjord as of 1 June 2021:

```sh
curl 'http://localhost:9090/ns/Company/fjord?timestamp=2021-06-01T00:00:00Z'
```

```json
{"name": "Fjord Fish"}
```

Without `timestamp` the same address gives the version valid now, and `?timeline` lists every version with its valid
time. [Getting started](/docs/user/getting-started/) runs a session like it from the start.

## When it fits

LDS fits when the history of the data is part of the data: registers, master data and reference data that change over
time and are read as they were, and models whose documents link to each other.

It does not fit, as it is today, when:

- you need a released product: 3.0.0 is not released, and no jar and no image is published;
- you need to know when a version was recorded as well as when it holds: LDS keeps valid time only and is not
  bitemporal, and a version written again at the same valid time replaces the one before;
- you need a write to be refused when its link points at nothing: links are not checked when written.

## Status

LDS 3.0 is in development. Its first milestone, 3.0.0-M1, is a tag of the source with release notes; nothing is
published, and LDS is built from the repository on JDK 27. What is unfinished is
said in the documentation where a reader meets it, in a paragraph that starts with "Not finished:".
