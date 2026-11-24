---
name: query
description: >
  Asks questions of the data in Linked Data Store (LDS) with its GraphQL API, written from the user's own model.
  Use this whenever the user wants to read, list, page through or find documents in LDS, follow links between
  them ("who works at", "which documents point at this one"), search their texts, see the data as it was on a
  date, walk the history of a document, find what changed between two dates, or call LDS from TypeScript, Python
  or Java, even if they never say "GraphQL". Phrases: "query LDS", "ask LDS", "show me all persons", "follow the
  link", "reverse link", "as of 2021", "what did it look like then", "time machine", "what changed since",
  "search LDS", "GraphiQL", "LDS client code". Fetches the schema LDS made, writes and runs the query, explains
  errors and limits in plain words. Writing data is lds:data; the model is lds:model.
compatibility: LDS 3.0; Claude Code
metadata:
  lds-version: 3.0.1
  docs: https://lds.descoped.io/docs/user/graphql-api/
---

# Query LDS with GraphQL

## Contents

- Workflow
- Send a query
- What LDS makes from a model
- What the GraphQL API cannot do
- Errors the user meets most
- References

LDS keeps versioned, linked JSON documents. Its GraphQL API at `/graphql` reads them; it has queries only, no
mutations: documents are written with the REST API (skill `lds:data`). Every query reads one point in time, now
unless the request names another. LDS has **no authentication**: whoever reaches the port reads everything.

## Workflow

Follow these steps with a user who knows nothing of LDS or GraphQL.

1. **Find LDS.** Ask for its address; `http://localhost:9090` is how LDS comes. Check it answers:
   `curl -i http://localhost:9090/ping` gives `200`. No answer: LDS is not running; use `lds:start`. Keep the
   address in `LDS` (`export LDS=http://localhost:9090`); the scripts read it.
2. **Read the schema LDS made from the user's model** before writing any query:

   ```sh
   sh "${CLAUDE_SKILL_DIR}/scripts/schema.sh"
   ```

   Its scripts are in `${CLAUDE_SKILL_DIR}/scripts/`: run them as `sh "${CLAUDE_SKILL_DIR}/scripts/<name>"`, from any
   folder. The references name them `scripts/<name>`, and `<skill dir>` there is `${CLAUDE_SKILL_DIR}`. The names of
   queries, fields and types come from the user's model, and LDS adds names of its own (`PersonById`,
   `PersonConnection`, `reversePersonFriends`), so a query written from memory or from the example model fails with
   `FieldUndefined`. With jq it prints GraphQL text; without jq it prints the introspection answer as JSON, which you
   read the same way. Read it for: the entity types (each has `XById` and `X` in `Query`), which fields are links (a
   field whose type is an entity type or its `Connection`), the reverse links LDS added, and whether `Query` has
   `Search` (search is on).
   - `400` with `Unsupported namespace: "graphql"`: there is no GraphQL API. The model is JSON Schema, or the
     key `graphql.enabled` is `false`. A JSON Schema model never gets a GraphQL API, reverse links or search;
     the REST API (`lds:data`) still reads it. To get GraphQL the model has to become a GraphQL schema
     (`lds:model`) and LDS restarted.
3. **Ask what the user wants to know**, in their words, and turn it into a shape. Ask, do not guess, which
   entity type and which fields they mean when the schema has several candidates.

   | The user wants | Shape | Reference |
   |---|---|---|
   | one thing they know the id of | `XById(id: "...")` | [query-shapes.md](references/query-shapes.md#one-document) |
   | all things of a kind, or the first n | `X(first: n) { edges { cursor node { ... } } pageInfo { hasNextPage } }` | [query-shapes.md](references/query-shapes.md#the-documents-of-an-entity-type-and-pages) |
   | what a document points at | the link field, with the fields of the target | [query-shapes.md](references/query-shapes.md#links) |
   | what points at a document | the reverse-link field | [query-shapes.md](references/query-shapes.md#reverse-links) |
   | documents by words in their texts | `Search(query: "...")` | [query-shapes.md](references/query-shapes.md#search) |
   | the data as it was or will be | the same query with `snapshot` | [time-machine.md](references/time-machine.md) |
   | the history of a document, or what changed | timeline, then two as-of reads compared | [time-machine.md](references/time-machine.md) |
   | the query in their own program | a small client | [clients.md](references/clients.md) |

4. **Write the query to a file and run it**, so the user sees exactly what was sent:

   ```sh
   Q="${CLAUDE_SKILL_DIR}/scripts/query.sh"
   sh "$Q" person.graphql '{"id": "ola"}'                       # variables
   sh "$Q" person.graphql '{"id": "ola"}' 2021-06-01T00:00:00Z  # as of a time
   echo '{ Person(first: 5) { edges { cursor node { name } } } }' | sh "$Q" -
   ```

   Ask only for the fields the user needs; the answer holds exactly what is asked. Show the query and the
   answer, then say in plain words what it means. The exit status is 1 when the answer has `errors`; explain
   them with [Errors the user meets most](#errors-the-user-meets-most).
5. **Offer the next step** that fits: page on with `after`, follow a link, the same question at another time
   (the time machine is what sets LDS apart; [time-machine.md](references/time-machine.md) has a tour on the
   user's own data), or the query as code ([clients.md](references/clients.md)). For trying queries by hand,
   point to GraphiQL at `http://localhost:9090/graphiql`; it shows the whole schema under `Docs`, and needs
   internet access, because the browser fetches GraphiQL from `unpkg.com`.

## Send a query

`POST /graphql` with one of two bodies; `GET /graphql?query=...` works too and gives the same answer.

```sh
curl http://localhost:9090/graphql -H 'Content-Type: application/graphql' \
     -d '{
       PersonById(id: "ola") {
         name
       }
     }'
curl http://localhost:9090/graphql -H 'Content-Type: application/json' \
     -d '{
       "query": "query One($id: ID!) { PersonById(id: $id) { name } }",
       "variables": {"id": "ola"}
     }'
```

```json
{
  "data": {
    "PersonById": {"name": "Ola Nordmann"}
  }
}
```

- `application/graphql`: the body is the query, with no variables. `application/json`: the body is
  `{"query", "variables", "operationName"}`. Any other content type, or none, is answered `415`. Write the content
  type in small letters; `APPLICATION/JSON` is answered `415`.
- The body is read as UTF-8, whatever charset the content type names: `PersonById(id: "åse")` finds `åse` with
  or without `charset=utf-8`.
- The time of an as-of read is the query parameter `snapshot` of the address (`?snapshot=2021-06-01T00:00:00Z`),
  or the variable `__snapshot`, which the query does not declare. Write it with `Z`; a time LDS cannot read is
  refused with an error. More in [time-machine.md](references/time-machine.md).
- The answer is `200` with JSON on one line whenever LDS could read the request, also when the query is wrong:
  what went wrong is in `errors`, and `data` holds what could be read. A request LDS cannot read (no body, no
  `query`, `variables` that are not an object) is answered `400` with the reason in `errors`.
- When the key `http.prefix` is set, the address is under it (`/api/graphql`). The GraphQL API reads one namespace,
  the one of `namespace.default` (`ns` as LDS comes); a query cannot name another.

## What LDS makes from a model

| In the model | In the GraphQL API |
|---|---|
| an entity type `X` (a type with `@domain`) | `XById(id: ID!): X` and `X(after, before, first, last): XConnection` in `Query` |
| an embedded type (no `@domain`) | the same type, read inside the document that holds it |
| a field with `@link` to one document | the same field; it gives the document the link points at |
| a field with `@link` to a list | a connection field with `after`, `before`, `first`, `last` |
| a list with `@link(pagination: false)` | a plain list, in the order of the links, no arguments |
| any field with `@link` | a reverse link on the target: its `reverseName`, else `reverse` + entity type + field (`Person.friends` gives `reversePersonFriends`) |
| a link to a union | `__typename` and `... on Cat { }` in the query pick the fields of each entity type |
| search on (`graphql.search.enabled`) | `Search(query, filter, history, first, after, ...)`, with the union `SearchResult` and the enum `TypeFilters` |

A connection has `edges { cursor node }` and `pageInfo { hasNextPage hasPreviousPage }`. In a list of documents
the cursor is the id. There is no count, no `startCursor` or `endCursor`, and the edges come in the order of the
ids, letter by letter as Unicode numbers them.

## What the GraphQL API cannot do

Say these plainly when the user asks for them, and offer what comes closest.

| The user asks for | LDS | Closest |
|---|---|---|
| write, change or delete | no mutations | the REST API, `lds:data` |
| documents whose field has a value ("persons in Oslo") | no argument filters by a field | read the list and filter on the client; for a word in a text, search |
| another order than by id | no sort argument | sort on the client |
| how many | no count | count the edges of a full read |
| two times in one request | one `snapshot` for the whole request | two requests, compared on the client: `scripts/compare.sh` |
| what changed between two times | no diff | `scripts/compare.sh`, see [time-machine.md](references/time-machine.md) |
| search in a number, a date, a link or the id | search covers texts only, and needs Solr | filter on the client |
| another namespace | one namespace per LDS | another LDS |
| a changed model | the model is read at start | change it and restart LDS (`lds:model`) |

Not finished: reading backwards (`last`, `before` alone) gives the edges in the wrong order and a wrong
`pageInfo`; read forwards with `first` and `after`.

## Errors the user meets most

| In `errors` | What it means | What to do |
|---|---|---|
| `"PersonById": null`, no error | No document with that id at the time of the query: never written, written for a later time, or deleted then. The rest of the answer is there | Check the id and the time |
| `The query parameter snapshot '...' is not an ISO-8601 time` | The time of the query cannot be read; the query was not run | A date, a time and `Z`: `2021-06-01T00:00:00Z` |
| `Field 'nmae' in type 'Person' is undefined` | A name the schema does not have | Read the schema again (`scripts/schema.sh`) |
| `Field 'Search' in type 'Query' is undefined` | Search is off | Search needs Solr and `graphql.search.enabled`; the compose stack has it on (`lds:start`) |
| `The 'Mutation' operation is not supported by the schema` | GraphQL in LDS only reads | Write with REST, `lds:data` |
| `This request is not asking for introspection in good faith` | `__type` or `__schema` twice, or `fields` inside `fields` | One introspection field per request; `scripts/schema.sh` stays inside the limit |
| status `400` with `The body has no "query" text`, `The variables are not a JSON object`, ... | LDS could not read the request | Check the body |

The full list, the limits (depth 100, 15000 tokens) and every `Not finished:` are in
[query-shapes.md](references/query-shapes.md#errors-and-limits).

A link may point at a document that does not exist, or not yet: LDS does not check links when they are written,
by design, since a document may be written for a later time than the one that links to it. Such a link gives
`null` (one link) or is left out (a list). That is not an error. A document without the link field gives `null`
too.

## References

- [references/query-shapes.md](references/query-shapes.md): read when writing any query beyond `XById`: pages,
  links, reverse links, unions, plain lists, search, scalars, errors and limits, each with a request that was run.
- [references/time-machine.md](references/time-machine.md): read when the user mentions a date, the past or
  the future, history, versions, or what changed. A tour on the user's own data.
- [references/clients.md](references/clients.md): read when the user wants the query in a program. TypeScript,
  Python and Java, each run against LDS.
- `scripts/schema.sh`, `scripts/query.sh`, `scripts/compare.sh`: their first lines say how to call them. They
  need `curl`; `compare.sh` needs jq, the others use it when it is there.
- The published reference: https://lds.descoped.io/docs/user/graphql-api/, and the time machine:
  https://lds.descoped.io/docs/user/concepts/#snapshots-the-time-machine.
