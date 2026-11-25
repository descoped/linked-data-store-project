---
title: "What's new"
description: "What each release of LDS gives those who use it, in a few words: what you can do now, and what was fixed."
order: 1
---

# What's new

What each release gives you, told from your side: what you can do now that you could not before, and what no longer
goes wrong. The full notes of a release, with every change, are on
[GitHub](https://github.com/descoped/linked-data-store-project/releases).

## 3.0.1

### Search finds what you wrote

- **Every text of a document is searched**, also in embedded objects and lists, not only fields named
  `languageText`.
- **In its language.** Write a text as `{"languageCode": "nb", "languageText": "..."}` and a search for *luftskip*
  finds *luftskipene*: words are matched by their stem in Norwegian, Swedish, English, German and French.
- **Without the special letters.** *boker* finds *bøker*, *Aase* finds *Åse*; the word as you wrote it ranks first.
- **At a moment.** A search with a `snapshot` finds the documents as they were then, and `history: true` finds every
  version that matched.
- **Found within a second.** Add `?searchable=true` to a write, and LDS answers when a search finds it, with the
  header `LDS-Searchable: true`.

### Writes you can rely on

- **Nothing is lost when writes meet.** Writes to one document take turns, so two parts or links written at the same
  time are both kept, also when the clock of the machine is set back.
- **A failed write leaves nothing behind.**
- **Clear answers.** A body that is not JSON gets a 400 that says where and why; a wrong content type a 415; an id
  that is too long a 400 that says so.

### Reading

- **A missing document is `null`, not an error.** `PersonById` for an id that has no document answers `null` and
  keeps the rest of the answer; a link to nothing is `null`.
- **Numbers and dates are checked** when they are written: `Int`, the new `Long`, `Float`, `Date` and `Time`.
- **Lists in one order everywhere**, whichever store LDS runs on.

### Running it

- **`/health/ready` tells you whether LDS can take writes**, and which part is down when it cannot.
- **A stop in order.** Requests being handled finish; nothing is left running.
- **A misspelled setting stops the start** and names the variable, instead of being passed over.

### If you ran 3.0.0

- With Solr: start once with `search.index.rebuild=true`, so that search finds what 3.0.0 indexed.
- With Neo4j: a document written by 3.0.0 is not in GraphQL pages until it is written again.
- The images have no health check of their own: ask `/health/ready` (the compose file does).

## 3.0.0

The first release of LDS 3: a server that keeps every version of your JSON documents and the links between them, and
gives them back as they were at any moment.

- **Describe your data once** as a GraphQL schema or as JSON Schema files, and get a REST API and a GraphQL API for it.
- **A time machine.** Every write is a new version from its own time; read any document, or follow its links, as they
  were at an instant.
- **Your choice of store**: in memory to try it, PostgreSQL, or Neo4j.
- **Images on Docker Hub** for Intel and ARM: start it with one `docker run`.
