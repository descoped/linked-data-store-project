---
title: "Linked Data Store"
description: >-
  A server for versioned, linked JSON documents: it checks each document against a model, keeps every version, and
  reads a document as it was at any instant, over REST and GraphQL.
eyebrow: "Open source · Apache License 2.0 · 3.0 in development"
headline: "Every version of your documents, and the links between them"
subline: >-
  Linked Data Store (LDS) keeps JSON documents described by a model. Each write is a new version that holds from its
  valid time, so a document, and the documents it links to, can be read as they were at any instant.
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

## How it works

### A model, read at start

You describe the entity types as a GraphQL schema or as JSON Schema 2020-12 files. LDS makes its REST addresses and
its GraphQL schema from the model, and checks every document against it before the document is written.
[The model](/docs/user/model/)

### Every write is a version

A version holds from its valid time: the time you give with the write, in the past, the present or the future, or
else the time LDS receives it. A read with a time is an as-of read and gives each document as it was then.
[Concepts](/docs/user/concepts/)

### Links are read at the same instant

A field may link to another document as `/Person/ola`. A link followed in an as-of read is resolved as of the same
instant. A link is not checked on write, so it may point at a document that is written later.
[The GraphQL API](/docs/user/graphql-api/)

### A write is a saga

The transaction log is written first, then the store and the search index side by side. A write that has started
also completes when a step failed or LDS stopped before the end. [The write path](/docs/developer/core-write-path/)

### Storage you choose

Documents are kept in memory, in PostgreSQL 18 or in Neo4j 2026, and searched with Solr 10. Every persistence
provider passes the same suite of tests against the real engine. [Architecture](/docs/developer/architecture/)

## See it

Ola moved from Bergen to Oslo. Read Ola as of 1 June 2021:

```sh
curl 'http://localhost:9090/ns/Person/ola?timestamp=2021-06-01T00:00:00Z'
```

```json
{
  "name": "Ola",
  "address": {"city": "Bergen", "street": "Bryggen 3"}
}
```

Without `timestamp` the same address gives Ola as Ola is now, in Oslo. `?timeline` lists every version with its valid
time. [Getting started](/docs/user/getting-started/) runs it from the start.

## When it fits

LDS fits when the history of the data is part of the data: registers, master data and reference data that change over
time and are read as they were, and models whose documents link to each other.

It does not fit, as it is today, when:

- you need a released product: nothing is released yet;
- you need to know when a version was recorded as well as when it holds: LDS keeps valid time only and is not
  bitemporal, and a version written again at the same valid time replaces the one before;
- you need a write to be refused when its link points at nothing: links are not checked when written.

## Status

LDS 3.0 is in development. The version is 3.0.0-SNAPSHOT, built from the repository on JDK 27, and no package or
container image is published. What is unfinished is said in the documentation where a reader meets it, in a paragraph
that starts with "Not finished:".
