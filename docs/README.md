---
title: "Documentation"
description: >-
  The documentation of Linked Data Store, for those who use it and for those who develop it, with a glossary of its
  terms.
order: 0
---

# Linked Data Store: documentation

Linked Data Store (LDS) is a server that gives REST and GraphQL access to versioned, linked JSON documents. The
[model](glossary.md#model) of the documents is read when LDS starts. LDS is a
[time machine](glossary.md#time-machine): every version of a document holds from its
[valid time](glossary.md#valid-time), and an [as-of read](glossary.md#as-of-read) gives the document as it was at the
instant asked for.

This documentation is in two parts, for two kinds of reader, and a [glossary](glossary.md) of the terms both use.
Every document is written from the code as it is, and every request shown in it was run. LDS 3.0.0 is released as
images on Docker Hub (`descoped/lds`) and is built from the repository, where its releases are tagged. What is
unfinished is said where a reader meets it, in a paragraph that starts with "Not finished:".

## For those who use LDS

You run LDS, give it a model, and read and write documents. You do not need to read the code.

| Document | What it is |
|---|---|
| [Getting started](user/getting-started.md) | From nothing to a first document: start LDS, write a document, read it, read it as it was, link two documents, ask with GraphQL. |
| [Concepts](user/concepts.md) | The ideas: document, entity type, namespace, version and valid time, link, model, provider, what a write goes through. |
| [The model](user/model.md) | How to write a model, as a GraphQL schema or as JSON Schema files, and what LDS checks against it. |
| [The REST API](user/rest-api.md) | Every address, method, parameter and answer. |
| [The GraphQL API](user/graphql-api.md) | The queries LDS makes from a model, paging, links, as-of reads, search. |
| [Configuration](user/configuration.md) | Every key, its default and its meaning, and how keys are set. |
| [Running LDS](user/operations.md) | The image and the compose file, starting and stopping, health, logs, what is stored where. |
| [Glossary](glossary.md) | The terms of this documentation, each with one meaning. |

## For those who develop and maintain LDS

You change the code, add a provider, or look for a fault.

Start with [the architecture](developer/architecture.md): the modules, the path of a read and of a write,
the ideas that hold the code together and the rules a change must keep. Then one document for each part:

| Document | The part of the code |
|---|---|
| [The build](developer/build.md) | The reactor, the BOM, the rules of the build, the tests, CI. |
| [The persistence interface](developer/persistence-api.md) | What a persistence provider implements: fragments, documents, reactive streams, transactions. |
| [The shared suite](developer/persistence-tck.md) | The tests every persistence provider passes. |
| [The memory provider](developer/persistence-memory.md) | Persistence in memory, for tests and for trying LDS. |
| [The PostgreSQL provider](developer/persistence-postgres.md) | Persistence in one table of PostgreSQL. |
| [The Neo4j provider](developer/persistence-neo4j.md) | Persistence as a graph in Neo4j. |
| [Search](developer/search.md) | The search interface and the Solr provider. |
| [Core: the application](developer/core-application.md) | How LDS is put together, started and stopped. |
| [Core: the model](developer/core-model.md) | How a model is read and how a document is checked against it. |
| [Core: the REST API](developer/core-rest.md) | How a request becomes a read or a write of a document, a part of it, or a link. |
| [Core: the write path](developer/core-write-path.md) | Sagas, the saga log, the transaction log, recovery. |
| [Core: GraphQL](developer/core-graphql.md) | How the GraphQL schema is built and how a query is answered. |
| [The server](developer/server.md) | The main class, the defaults of the configuration, the image, the compose file. |
| [The load test](developer/loadtest.md) | What a run of the load test does and what it is made of. |
