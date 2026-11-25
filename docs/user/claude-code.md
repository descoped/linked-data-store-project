---
title: "With Claude Code"
description: "The plugin lds for Claude Code: start LDS, make a model of a domain, write, read and query documents by asking."
order: 8
section: Operate
---

# Linked Data Store with Claude Code

The repository of LDS is also a marketplace of plugins for [Claude Code](https://code.claude.com/). Its plugin, `lds`,
lets you work with LDS by asking in plain words, also when you have never used it: Claude starts LDS, makes a model of
your domain with you, and writes, reads and queries the documents for you, with the commands that this documentation
shows.

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

The plugin has no version of its own: it is what the branch `master` of the repository holds. Claude Code takes the
newest when the marketplace is updated, with `/plugin marketplace update lds`, or by itself when automatic updates
are switched on for the marketplace in `/plugin`.

## What it can do

The plugin is five skills. Claude picks the one that fits what you ask; you can also name one, such as `/lds:model`.

| Skill | Ask it to | For example |
|---|---|---|
| `lds:start` | start LDS, in memory or with PostgreSQL, Neo4j and Solr, and find out why it does not start | "Start LDS so I can try it" |
| `lds:model` | make a model of your domain, from a description, sample JSON, a CSV file, an SQL schema or an OpenAPI description; check it; draw it; give it to LDS | "Model a library with books, authors and loans" |
| `lds:data` | write, read and delete documents, versions at a time, links and parts of documents over REST; load existing data with its history; export | "Load these members from members.csv" |
| `lds:query` | write GraphQL queries from your own model, follow links, read at an instant, search; show what changed between two dates | "Which books did Ada have on loan on 1 March?" |
| `lds:operate` | check whether LDS can take writes, read logs, recover after a crash, back up, rebuild the search index, diagnose with the debug image, move to a newer image | "LDS answers 503, what is wrong?" |

## What it needs

- Claude Code, which runs the commands in a shell on your machine and asks before it runs one, as you have set it to.
- Docker, to start LDS from [its image](operations.md#the-image), or an LDS that already runs.
- `curl`. `jq` is used when it is there.

The model is made as a GraphQL schema unless you ask for JSON Schema: a model of JSON Schema files gives
[no GraphQL API](model.md#what-a-model-says).

LDS has no authentication: whoever reaches its port can read and write every document. The plugin starts LDS on
`localhost` only, and says so before anything is deployed where others can reach it.
