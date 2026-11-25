---
title: "Roadmap"
description: "What LDS is to do next, and what is being considered: security, the model, tools, writes and reads, running it."
order: 2
---

# Roadmap

What LDS is to do next, and what is being considered. Nothing on this page is in LDS yet: each item says what LDS
does today and links to where the documentation says so. The items have no dates.

- **Next** is planned and worked on first.
- **Later** is planned and follows.
- **To be evaluated** is considered, not promised: whether it is done, and how, is decided after a closer look.

Sign-in and access rights come first. The admin console and a model registered over HTTP build on them: neither is
safe in an LDS that lets every caller in.

## Security

| Item | Status | What it gives | Today |
|---|---|---|---|
| Sign-in | Next | LDS accepts a token from an identity provider you run (OpenID Connect) and refuses a request without a valid one. | LDS asks no caller who it is: [What LDS does not have](user/operations.md#what-lds-does-not-have). |
| Access rights | Next | Roles that allow reading or writing, for each namespace and each entity type. | Whoever can reach the port can read, write and delete every document. |
| Who changed a version | Next | Each version records who wrote it, so the history of a document says who changed what, and when. | A write records no user. |
| Erase a document's history | To be evaluated | Remove every version of a document for good, as the right to erasure asks. | A delete adds a version in which the document is deleted, and the earlier versions stay: [What a delete means](user/rest-api.md#what-a-delete-means-for-earlier-and-later-versions). |
| TLS in LDS | To be evaluated | LDS ends TLS itself, without a proxy in front. | LDS listens with plain HTTP: [What LDS does not have](user/operations.md#what-lds-does-not-have). |

## The model

| Item | Status | What it gives | Today |
|---|---|---|---|
| Register a model while LDS runs | Next | Give LDS a new or changed model over HTTP, without a restart, for those whose role allows it. Documents already stored are not changed by a new model. | LDS reads its model when it starts, and a changed model needs a restart: [Give LDS a model](user/model.md#give-lds-a-model). |
| Several namespaces | Later | One LDS serves several namespaces, each with a model of its own. | LDS answers for one namespace: [A prefix and another namespace](user/rest-api.md#a-prefix-and-another-namespace). |
| The model on the timeline | To be evaluated | A read as of a past date is checked and answered with the model of that date. | Every snapshot is served with the model LDS was started with: [Snapshots](user/concepts.md#snapshots-the-time-machine). |
| GraphQL for a model of JSON Schema files | To be evaluated | The GraphQL API also for a model written as JSON Schema. | GraphQL is made only from a model that is a GraphQL schema: [A model as JSON Schema files](user/model.md#a-model-as-json-schema-files). |

## Tools

| Item | Status | What it gives | Today |
|---|---|---|---|
| A command-line tool | Next | One command, `lds`, to check a model before LDS starts, import and export documents, read as of a date, compare two dates and see the health of LDS. | This is done with `curl`, or by asking Claude through the plugin and its scripts: [With Claude Code](user/claude-code.md). |
| An admin console | Next, after sign-in | A web console to browse entity types and documents, move along the timeline of a document, follow links both ways, and see the health of writes and search. | LDS serves GraphiQL, a page to write GraphQL queries in: [The GraphQL API](user/graphql-api.md). |

## Writes and reads

| Item | Status | What it gives | Today |
|---|---|---|---|
| A clear answer when a write fails | Next | The answer says whether LDS will still finish the write, and a write it gave up can be found. | A failed write is answered 500 with no body, and may be finished later: [When a write fails](user/rest-api.md#when-a-write-fails). |
| Pages over REST | Next | Lists and timelines given a page at a time, as GraphQL gives them. | A list and a timeline come back whole: [Read all documents of an entity](user/rest-api.md#read-all-documents-of-an-entity). |
| A feed of changes | Later | Every write, in order, read from where the reader stopped. | The transaction log answers one question, the last write of a source: [Sources and the transaction log](user/rest-api.md#sources-and-the-transaction-log). |
| Several LDS writing | Later | Several LDS write the same documents, and writes to one document take turns across them. | Writes take turns within one LDS only: [One write at a time to a document](user/rest-api.md#one-write-at-a-time-to-a-document). The transaction log in PostgreSQL has one writer: [The transaction log of a cluster](user/operations.md#the-transaction-log-of-a-cluster). |
| Links checked on delete | To be evaluated | A delete may be refused while other documents link to the document, where the model asks for it. | A delete leaves the links to the document in place: [The target of a link is deleted](user/rest-api.md#the-target-of-a-link-is-deleted). |
| What LDS knew at a past moment | To be evaluated | A read by the time a version was written, as well as by the time it is valid. | A read asks by valid time only: [Snapshots](user/concepts.md#snapshots-the-time-machine). |
| Filters and facets in search | To be evaluated | A search narrowed, and its results counted, by the value of a field. | A search is narrowed by entity type and time only: [Search](user/graphql-api.md#search). |

## Running LDS

| Item | Status | What it gives | Today |
|---|---|---|---|
| Metrics | Next | An address with the metrics of LDS in a standard format, for the monitoring you run. | LDS has the health addresses only: [What LDS does not have](user/operations.md#what-lds-does-not-have). |
| The level of the log | Next | A configuration key for how much LDS logs. | LDS has no key for it: [What LDS does not have](user/operations.md#what-lds-does-not-have). |
| Backup and retention | To be evaluated | A way to copy the stores, and to remove versions older than a limit. | Nothing in LDS copies a store or removes old versions: [What LDS does not have](user/operations.md#what-lds-does-not-have). |
