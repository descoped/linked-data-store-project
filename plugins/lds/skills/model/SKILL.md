---
name: model
description: >
  Designs the model of Linked Data Store (LDS) for the user's own domain: the entity types, their fields,
  embedded objects and links, written as a GraphQL schema (the first choice) or as JSON Schema 2020-12 files,
  checked against every shape LDS refuses or does not read as written, drawn as a Mermaid class diagram for the
  user to confirm, tried in a throwaway LDS and then loaded. Use this whenever the user wants to keep their own
  kind of data in LDS, says "model my domain", "write a schema for LDS", "what types do I need", describes things,
  fields or relations to keep, brings sample JSON, a CSV file or header, an SQL schema (DDL) or an OpenAPI spec to
  bring into LDS, wants to add or change a type or a field, or LDS refuses, hangs on or ignores their schema at
  start, even when they never say "model" or "schema".
compatibility: LDS 3.0; Claude Code
---

# Model a domain for LDS

The model is what LDS is told when it starts: the **entity types** (the kinds of document, each with documents of
its own at `/ns/{Entity}/{id}`), the fields of each, which fields a document must have, the **embedded types**
(parts of a document, with no id of their own) and the **links** (a field that points at another document). Every
write is checked against it, the REST addresses follow from it, and with a GraphQL schema LDS makes its GraphQL API
from it. The user owns the domain; this skill turns what they know into a file LDS reads, and proves that it does.

The user may know nothing of LDS. Ask in plain words about their things, never about directives or schemas, and
show them the result as a diagram, not as code, before anything else.

## Workflow

1. **Find out what the user has.** Only a description, or nothing written down: run the interview in
   [references/interview.md](references/interview.md). Sample JSON, a CSV, SQL DDL or an OpenAPI document: convert it
   with [references/converting.md](references/converting.md), then ask the interview questions the source does not
   answer (which things are documents of their own, what changes over time, what is searched for).
2. **Sort every thing** into entity type, embedded type or link with the rules in
   [Entity, embedded or link](#entity-embedded-or-link). Tell the user the result in a few plain lines ("A loan is
   a record of its own; it points at one copy and one member; a renewal is part of the loan") and let them correct
   it before any file is written.
3. **Choose the form**: a GraphQL schema, unless the user asks for JSON Schema or needs what only JSON Schema can say
   ([The two forms](#the-two-forms)). Say what the choice costs.
4. **Write the model**: [references/graphql-schema.md](references/graphql-schema.md) or
   [references/json-schema.md](references/json-schema.md). Put it in a folder of its own (`model/`), one file
   `schema.graphql`, or one `<Entity>.json` for each entity type.
5. **Check it line by line** against [references/checklist.md](references/checklist.md). Several shapes look right,
   start without a word, and then check nothing or the wrong thing; others make LDS hang. Fix them before LDS sees
   the file, and tell the user about any limit that stays (for example that a single embedded object is not checked
   in a GraphQL schema).
6. **Draw it** as a Mermaid class diagram ([references/diagram.md](references/diagram.md)): a filled diamond for an
   embedded type, an arrow for a link. Show it with a short list of what LDS will refuse and what it will take as it
   comes, and ask the user to confirm or correct. Go back to step 2 for each correction.
7. **Try it** in a throwaway LDS: [Try the model](#try-the-model). Then write one document of each entity type,
   read it back, and send one wrong document to show the user a refusal.
8. **Load it** into the user's LDS ([Load the model](#load-the-model)) and see that LDS read it with `?schema`.
9. **Hand over**: writing, loading and reading documents is `lds:data`; GraphQL queries are `lds:query`; running
   LDS with a database is `lds:start`.

## Entity, embedded or link

Ask these about each thing the user names, in this order:

| Question | Yes means |
|---|---|
| Is it looked up, listed or changed on its own, or do several other things point at the same one? | An **entity type**: documents of its own, with an id the user chooses and versions of its own. |
| Is it only ever part of one other thing, written and read with it, with no identity of its own (an address, an order line, a chapter)? | An **embedded type**: a value inside the document. |
| Does one thing refer to another thing that is an entity type? | A **link** field on the thing that refers. One link or a list of links. |
| Can the field point at one of several entity types (a rights holder that is an author or a publisher)? | A link to a **union** of those entity types. |

- A link is a typed reference, the text `/Entity/id` in the document. LDS checks its form and that the field may
  link to that entity type; it does not check that the document is there. That is by design: LDS is a time machine,
  documents can be written in any order, and the target may exist only from a later time. Tell the user so when
  they expect a foreign key check.
- Put a link on the side that has one: a book links to its publisher, not the publisher to its books. The other
  direction comes for free in GraphQL as a **reverse link** (`reverseName`), computed at read time.
- A many-to-many relation with no data of its own is a list of links on one side (`authors: [Author] @link`). One
  that has data of its own (a loan with dates, a membership with a role) is an entity type with two links.
- A link inside an embedded object is followed forward in GraphQL, but its reverse link was answered empty, and in a
  list of embedded objects such a link is not checked on write. When the user needs to ask from the other end
  ("which orders hold this product"), make the part an entity type or put the link on the entity itself.
- A field whose type is an entity type is read as a link even without `@link`, so it cannot hold a copy of that
  entity's values. When the document must keep the values as they were (a price at the time of the order), use an
  embedded type with those fields.

## Time and ids are not fields

LDS versions every document on its own: a write adds a version valid from the time in the `timestamp` parameter
(or from now), a read gives the version valid at a time, and `?timeline` lists them all. So:

- Leave out fields that only record history: `created_at`, `updated_at`, `valid_from`, `valid_to`, `version`,
  `is_deleted`, and history or audit tables. A change is a new version at its time; a delete is a delete at its time.
  Keep a date that means something in the domain (born, published, due), as a field.
- The id is the last part of the address (`/ns/Member/m-17`), chosen by whoever writes, not a field. A natural key
  (a barcode, an ISBN, a member number) makes a good id. Keep it as a field too when the user will list documents
  over REST: the REST list of an entity type gives the documents without their ids (in a GraphQL list the `cursor` of
  each edge is the id).
- One model holds for every time. Documents already stored are not checked again when the model changes, and are
  given back as they were written.

`lds:data` covers writing at a time and reading as of a time.

## The two forms

| | GraphQL schema (one file) | JSON Schema 2020-12 (one file per entity type) |
|---|---|---|
| GraphQL API, reverse links, search | yes | no: `/graphql` answers `400 Unsupported namespace: "graphql"` |
| Text, `true`/`false`, date-time, enum name, list of texts, list of embedded objects | checked | checked |
| Numbers, dates, times of day, a single embedded object | not checked | checked |
| `null` as a value | refused in a checked field | accepted where the schema says so |
| Limits (shortest text, smallest number, a pattern), no fields beyond the model | cannot be said | checked |

Suggest the GraphQL schema first, because most users want to query and follow links both ways, and only it gives
the GraphQL API, reverse links and search. Choose JSON Schema files when strict checking of every value matters more
than querying; the user can't have both in one LDS, since LDS reads one form, never both.

Neither form can say: that a linked document exists, anything about ids, versions or namespaces, a rule across
documents (a unique email), or a default value for a missing field.
[What a model cannot say](https://lds.descoped.io/docs/user/model/#what-a-model-cannot-say).

## A GraphQL schema in brief

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
A member of the library
"""
type Member @domain {
  name: String!
  joined: DateTime!
  status: MemberStatus
  addresses: [Address]
}

enum MemberStatus {
  ACTIVE
  SUSPENDED
}

type Address {
  street: String
  city: String!
}

"""
A loan to a member
"""
type Loan @domain {
  member: Member! @link(reverseName: "loans")
  due: DateTime!
}

type Query {
}

scalar DateTime
```

The two `directive` lines, the type `Query` and a `scalar` line for each of `DateTime`, `Date`, `Time` in use are
required; LDS ends at start without them. `@domain` makes an entity type, a type without it is embedded, `!` makes a
field required, `@link` makes a link. Everything else is in
[references/graphql-schema.md](references/graphql-schema.md).

## Search

Search finds a document only by the texts of fields named `languageText`, anywhere in the document, and only with
a GraphQL schema, with search turned on and Solr running (`lds:start`). When the user wants to find documents by
text, give the entity a list of an embedded type with that field:

```graphql
type Text {
  languageCode: String
  languageText: String!
}
```

and a field such as `description: [Text]`. No other field is searched, whatever its name, and `@domain(searchable:
...)` has no effect. Tell the user plainly that search covers only these texts.

## One namespace

One LDS serves one namespace (`ns` as it comes), and every entity type of the model is in it. Two unrelated
domains in one LDS share one model; to keep them apart, run two LDS. Entity type names are case-sensitive and are
used as written in every address: `/ns/Member`, not `/ns/member`.

## Try the model

Run the script of this skill on the model folder. Its scripts are in `${CLAUDE_SKILL_DIR}/scripts/`: run them as `sh
"${CLAUDE_SKILL_DIR}/scripts/<name>"`, from any folder. The references name them `scripts/<name>`, and `<skill dir>`
there is `${CLAUDE_SKILL_DIR}`. It starts `descoped/lds:3.0.0` in memory in a throwaway container (`lds-model-try`, on
`127.0.0.1:9098`), waits until LDS answers, ends or hangs, and prints what LDS read, or the reason from its log. It also
asks every entity type for its list, which catches JSON Schema files that LDS lists and cannot use.

```sh
sh "${CLAUDE_SKILL_DIR}/scripts/try-model.sh" model
```

```
Trying the GraphQL schema (schema.graphql) with descoped/lds:3.0.0 on http://localhost:9098
Managed domains: /Loan /Member
GET /ns?schema: ["/ns/Loan?schema","/ns/Member?schema"]
Every entity type answers.
```

Exit status 0 means LDS read the model; 1 means it ended, hung or cannot use a file, and the reason is printed;
[references/checklist.md](references/checklist.md) has every message with its fix. Give a port as the second
argument when 9098 is taken. With `--keep` before the folder LDS keeps running, for test writes on that port:

```sh
sh "${CLAUDE_SKILL_DIR}/scripts/try-model.sh" --keep model
curl -i -X PUT 'http://localhost:9098/ns/Member/m-17?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "name": "Kari Nordmann",
       "joined": "2024-03-01T00:00:00Z",
       "status": "ACTIVE"
     }'
curl -i 'http://localhost:9098/ns/Member/m-17'
docker rm -f lds-model-try
```

The write is answered `201` with `{"saga-execution-id": "..."}`, the read with the document. A wrong document is
answered `400` with `Schema validation error: [...]`, each fault as its place and what is wrong, for example
`$.joined: does not match the date-time pattern must be a valid RFC 3339 date-time`. A date-time needs a time and
a time zone: `2024-03-01T00:00:00Z`.

## Load the model

LDS reads the model once, at start. To load or change it, give LDS the folder and start it again. With LDS in
memory (as it comes) a restart loses every document; say so before restarting the user's LDS.

```sh
docker rm -f lds
docker run -d --name lds -p 127.0.0.1:9090:9090 -v "$PWD/model:/lds/schemas:ro" descoped/lds:3.0.0
```

- The folder is mounted at `/lds/schemas`. A GraphQL schema there must be named `schema.graphql`, or named in
  `-e LDS_GRAPHQL_SCHEMA=schemas/<file>.graphql`.
- JSON Schema files need `-e LDS_GRAPHQL_SCHEMA=` (empty) as well; LDS then reads every `*.json` file of the folder,
  not its subfolders. Each file is one entity type, named as the file without `.json`.
- In a compose stack set up by `lds:start`, the model is the folder `schemas` next to its `compose.yaml` (the
  `volumes` lines under `lds`); change the file there and run `docker compose restart lds`, which keeps the
  documents. Then wait for LDS as `lds:start` does. Running and stopping LDS is `lds:start`.

Then see what LDS read:

```sh
curl 'http://localhost:9090/ns?schema'
curl 'http://localhost:9090/ns/Member?schema'
```

The first lists the entity types (`["/ns/Loan?schema", "/ns/Member?schema"]`). The second gives the JSON Schema
that documents of that entity type are checked with; from a GraphQL schema it is the one LDS made, and a field
without `"type"` in it is not checked. Read it when LDS takes or refuses a document and the user does not see why.

## The references

| Read | When |
|---|---|
| [references/interview.md](references/interview.md) | The user describes a domain, or has nothing written down. |
| [references/converting.md](references/converting.md) | The user brings sample JSON, CSV, SQL DDL or OpenAPI. |
| [references/graphql-schema.md](references/graphql-schema.md) | Writing or changing a GraphQL schema: every field kind, links, unions, reverse links, a whole example. |
| [references/json-schema.md](references/json-schema.md) | The user wants JSON Schema files, or brings JSON Schema or OpenAPI 3.1. |
| [references/checklist.md](references/checklist.md) | Before every try, and whenever LDS ends, hangs or checks the wrong thing at start. |
| [references/diagram.md](references/diagram.md) | Drawing the model for the user to confirm. |

The documentation behind this skill: [The model](https://lds.descoped.io/docs/user/model/),
[Concepts](https://lds.descoped.io/docs/user/concepts/#what-a-field-holds) and
[the glossary](https://lds.descoped.io/docs/glossary/).
