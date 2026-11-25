---
title: "With Claude Code"
description: "The plugin lds for Claude Code: start LDS, make a model of a domain, write, read and query documents by asking."
order: 8
section: Operate
---

# Linked Data Store with Claude Code

The repository of LDS is also a marketplace of plugins for [Claude Code](https://code.claude.com/). Its plugin, `lds`,
lets you work with LDS by asking in plain words, also when you have never used it: Claude starts LDS, makes a model of
your domain with you, and writes, reads and queries the documents for you. It sends the requests that this
documentation shows with `curl` in your shell, some of them through small scripts of the plugin.

## Install it

In Claude Code:

```text
/plugin install lds --marketplace descoped/linked-data-store-project
```

With a Claude Code older than 2.1.275, add the marketplace first and then install the plugin from it:

```text
/plugin marketplace add descoped/linked-data-store-project
/plugin install lds@lds
```

The plugin has no version of its own: it is what the branch `master` of the repository holds. `/plugin marketplace
update lds` fetches the newest, or Claude Code does it by itself when automatic updates are switched on for the
marketplace in `/plugin`. `claude plugin update lds@lds`, in a shell, updates the installed plugin; Claude Code uses
the update once it is started again.

## What it can do

The plugin is five skills. Claude picks the one that fits what you ask; you can also name one, such as `/lds:model`.

| Skill | Ask it to | For example |
|---|---|---|
| `lds:start` | start LDS: in memory to try it, or keeping the data, with PostgreSQL alone or with Neo4j, PostgreSQL and Solr (the full stack, with search); start it with your own model; find out why it does not start | "Start LDS so I can try it" |
| `lds:model` | make a model of your domain, from a description, sample JSON, a CSV file, an SQL schema or an OpenAPI description; check it; draw it; try it in a throwaway LDS; give it to LDS | "Model a library with books, authors and loans" |
| `lds:data` | write, read and delete documents, versions at a time, links and parts of documents over REST; load existing data with its history; export | "Load these members from members.csv" |
| `lds:query` | write GraphQL queries from your own model, follow links, read at an instant, search (with the full stack); show what changed between two dates | "Which books did Ada have on loan on 1 March?" |
| `lds:operate` | check whether LDS can take writes, read logs, recover after a crash, back up, rebuild the search index, diagnose with the debug image, move to a newer image | "LDS answers 503, what is wrong?" |

## What it needs

- Claude Code, which runs the commands in a shell on your machine and asks before it runs one, as you have set it to.
- Docker, to start LDS from [its image](operations.md#the-image), and Docker Compose to keep the data. The plugin
  tries every model in a throwaway LDS in Docker, also when an LDS already runs. To write, read and query, an LDS
  that already runs is enough.
- `curl`. `jq` is needed to load existing data, to export and to show what changed between two dates; the other
  commands use it when it is there.
- Access to the internet. The plugin starts the image of a released version that its skills name, not `latest`,
  so the first start downloads it from Docker Hub. The page for trying queries loads its scripts from `unpkg.com`
  ([GraphiQL](graphql-api.md#the-page-for-trying-queries)).

The model is made as a GraphQL schema unless you ask for JSON Schema, or need the stricter checks that only JSON
Schema files give; the plugin then says what that costs: a model of JSON Schema files gives
[no GraphQL API](model.md#what-a-model-says), and with it no reverse links and no search.

The plugin tells you of the limits of LDS where you meet them:

- One LDS has one model and one [namespace](concepts.md#document-entity-namespace-and-id). A second model is a
  second LDS on another port.
- LDS reads the model when it starts, so a change of the model needs a restart
  ([the model](model.md#what-a-model-says)).
- LDS does not check that the document a link points at is there ([what is not checked](model.md#what-is-not-checked)).
- Search needs a GraphQL schema and Solr, which the full stack has. It finds a word by its stem only in an object
  with a `languageCode` and a `languageText`, and every other text word for word
  ([how words are found](graphql-api.md#how-words-are-found)).

LDS has no authentication: whoever reaches its port can read and write every document. The plugin starts LDS on
`localhost` only. Before LDS is made reachable from other machines, the plugin goes through this with you: put
something that asks callers to log in in front of LDS ([what LDS does not have](operations.md#what-lds-does-not-have)).

## What it leaves on your machine

- **LDS in memory**: a container `lds`, on `127.0.0.1:9090`. Every document is gone when the container stops or is
  started again, also when the plugin gives LDS a new model
  ([everything in memory](configuration.md#everything-in-memory)). `docker rm -f lds` removes it.
- **LDS that keeps the data**: a `compose.yaml` in a folder of its own (`lds` unless you name another), and named
  volumes that hold the data. The container of LDS is `lds-postgres-lds-1` with PostgreSQL alone, `lds-lds-1` with
  the full stack, which also publishes Neo4j and Solr on `127.0.0.1`. `docker compose down` in that folder stops it
  and keeps the data; `docker compose down -v` removes the data too.
- **A model being tried**: a container `lds-model-try` on `127.0.0.1:9098`, removed when the try ends unless it is
  kept for test writes. `docker rm -f lds-model-try` removes it.
- **The model**: its files in a folder `model/`, or in `schemas` next to `compose.yaml` when LDS keeps the data.
