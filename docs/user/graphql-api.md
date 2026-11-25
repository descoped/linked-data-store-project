---
title: "The GraphQL API"
description: "The queries LDS makes from a model, paging, links, as-of reads, search."
order: 5
section: Reference
---

# The GraphQL API

This document describes the GraphQL API of Linked Data Store (LDS): its address, how a query is sent, the queries
LDS makes from a [model](../glossary.md#model), lists and their pages, [links](../glossary.md#link),
[as-of reads](../glossary.md#as-of-read), the scalars, search, and the errors. It is for people who read documents
from LDS with GraphQL. It is a reference: every query and every argument is here, each with a request that was run
and the answer that came.

## What the GraphQL API is

- It reads documents. It has queries only. There are no mutations and no subscriptions: documents are written
  and deleted with [the REST API](rest-api.md). A query changes nothing in LDS.
- LDS makes the GraphQL schema from the model when it starts. For every [entity type](../glossary.md#entity-type)
  of the model there is a query for one document and a query for the documents of the entity type, and a link
  of the model is a field that gives the documents the link points at.
- Its address is `/graphql`. `/graphql/` is the same address.
- It reads one [namespace](../glossary.md#namespace): the one named by the configuration key
  `namespace.default`, which is `ns` as LDS comes. A query cannot name another namespace.
- It is there when the model is a GraphQL schema and the key `graphql.enabled` is `true`. That is how LDS
  comes. A model given as JSON Schema files has no GraphQL API. Where there is no GraphQL API, `/graphql` is
  answered `400 Bad Request` with the text `Unsupported namespace: "graphql"`.
- When the key `http.prefix` is set, the address is under it: with `/api` it is `/api/graphql`.

[Configuration](configuration.md) has the keys. [The concepts](concepts.md) explains document,
entity type, namespace, version and link.

## The examples of this document

The examples were run on LDS as it comes: in memory, with the example model.
[Getting started](getting-started.md) tells how to start it. In the examples LDS answers at
`http://localhost:9090`.

The example model is the file `linked-data-store-server/schemas/schema.graphql`. This is the file without the
comment at its top. [The model](model.md) explains how such a file is written.

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
A person
"""
type Person @domain {
  name: String!
  born: DateTime
  address: Address
  employer: Company @link(reverseName: "employees")
  friends: [Person] @link
}

type Address {
  street: String
  city: String!
}

"""
A company
"""
type Company @domain {
  name: String!
  founded: DateTime
  about: [Text]
}

"""
A text in one language. Search finds the text in its language (nb, nn, no, sv, en, de or fr), by the stem of its words,
and any other text of a document word for word.
"""
type Text {
  languageCode: String
  languageText: String!
}

type Query {
  x: String
}

scalar Date
scalar DateTime
scalar Time
```

`Person` and `Company` are the entity types. `Address` and `Text` are embedded types: their values are
[embedded objects](../glossary.md#embedded-object), parts of documents.

The documents of the examples are written with the REST API. This shell function writes one version of a
document and prints the status code of the answer. `sync=true` makes LDS answer when the document is stored (a
[synchronous write](../glossary.md#synchronous-write)), and `timestamp` is the
[valid time](../glossary.md#valid-time) of the version.

```sh
put() {
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT "http://localhost:9090/ns/$1?sync=true&timestamp=$2" \
       -H 'Content-Type: application/json' -d "$3"
}
```

The documents:

```sh
put Company/acme 2020-01-01T00:00:00Z '
{
  "name": "Acme",
  "founded": "1999-05-01T08:00:00Z",
  "about": [
    {
      "languageCode": "en",
      "languageText": "Makes anvils and rockets"
    }
  ]
}
'
put Company/globex 2020-01-01T00:00:00Z '
{
  "name": "Globex",
  "founded": "2001-09-09T09:09:09.120+02:00"
}
'
put Person/kari 2020-01-01T00:00:00Z '
{
  "name": "Kari",
  "employer": "/Company/acme"
}
'
put Person/per 2020-01-01T00:00:00Z \
    '
{
  "name": "Per",
  "employer": "/Company/acme",
  "address": {"street": "Storgata 1", "city": "Oslo"}
}
'
put Person/anne 2020-01-01T00:00:00Z '{"name": "Anne"}'
put Person/ola 2020-01-01T00:00:00Z '
{
  "name": "Ola",
  "employer": "/Company/acme",
  "friends": ["/Person/kari"]
}
'
put Person/ola 2022-01-01T00:00:00Z '
{
  "name": "Ola Nordmann",
  "born": "1980-02-03T04:05:06.123456Z",
  "employer": "/Company/globex",
  "friends": ["/Person/kari", "/Person/per", "/Person/anne"]
}
'
```

```
201
201
201
201
201
201
201
```

The person `ola` has two versions. From 2020 Ola works at Acme and has one friend. From 2022 the name is Ola
Nordmann, the employer is Globex and there are three friends.

## Send a query

A query is sent with POST or with GET. The answer is the same for both.

### POST with a JSON body

The body is a JSON object with the query as the text of `query`, and the content type is `application/json`.

```sh
curl -i 'http://localhost:9090/graphql' -H 'Content-Type: application/json' \
     -d '{
       "query": "{ CompanyById(id: \"acme\") { name founded } }"
     }'
```

```http
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json
Content-Length: 73
Date: Tue, 24 Nov 2026 23:21:32 GMT

{
  "data": {
    "CompanyById": {"name": "Acme", "founded": "1999-05-01T08:00:00Z"}
  }
}
```

| Field of the body | Value | Meaning |
|---|---|---|
| `query` | a text | The query. It must be there. |
| `variables` | an object | The values of the variables of the query. It can be left out or be `null`. |
| `operationName` | a text | The name of the operation to run, for a `query` text that holds more than one. It can be left out or be `null`. |

A request with variables and an operation name:

```sh
curl 'http://localhost:9090/graphql' -H 'Content-Type: application/json' \
     -d '{
       "query": "query One($id: ID!) { PersonById(id: $id) { name } } query All { Person { edges { cursor } } }",
       "variables": {"id": "per"},
       "operationName": "One"
     }'
```

```json
{
  "data": {
    "PersonById": {"name": "Per"}
  }
}
```

Only the body of a POST is read. `query`, `variables` and `operationName` in the address of a POST are ignored.

### GET

The query is in the query parameter `query` of the address. `variables` (a JSON object as text) and
`operationName` are query parameters too. All three are URL-encoded; `curl -G --data-urlencode` does that.

```sh
curl -G 'http://localhost:9090/graphql' \
     --data-urlencode 'query=query One($id: ID!) { PersonById(id: $id) { name } }' \
     --data-urlencode 'variables={"id":"kari"}' \
     --data-urlencode 'operationName=One'
```

```json
{
  "data": {
    "PersonById": {"name": "Kari"}
  }
}
```

### POST with the query as the body

With the content type `application/graphql` the whole body is the query. Such a request has no variables and no
operation name.

```sh
curl 'http://localhost:9090/graphql' \
     -H 'Content-Type: application/graphql' \
     -d '{ CompanyById(id: "acme") { name } }'
```

```json
{
  "data": {
    "CompanyById": {"name": "Acme"}
  }
}
```

### Content types

| Content type of a POST | The body is read as |
|---|---|
| `application/json`, or one that ends in `+json` such as `application/vnd.example+json` | a JSON object with `query`, `variables` and `operationName` |
| `application/graphql`, or one that ends in `+graphql` | the query |
| another content type, or none | nothing: the answer is `415 Unsupported Media Type`, with an error that names the content types to send |

```sh
curl -i 'http://localhost:9090/graphql' \
     -H 'Content-Type: text/plain' \
     -d '{ CompanyById(id: "acme") { name } }'
```

```http
HTTP/1.1 415 Unsupported Media Type
Connection: keep-alive
Content-Type: application/json
Content-Length: 109
Date: Tue, 24 Nov 2026 23:21:32 GMT

{
  "errors": [
    {"message": "Unsupported Content-Type: text/plain. Send application/json or application/graphql"}
  ]
}
```

A POST with no content type gets the same answer, with `null` in the place of the content type.

Not finished: the content type is matched in the case it is written. `APPLICATION/JSON` is answered `415`.
Write the content type in small letters.

### Letters outside ASCII

The body of a POST is read as UTF-8, as JSON is always written: with no character set in the content type, and also
when the content type names another one. A GET with a URL-encoded query is read as UTF-8 too.

A person with such an id, and a query for it:

```sh
curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
     'http://localhost:9090/ns/Person/%C3%A5se?sync=true&timestamp=2020-01-01T00:00:00Z' \
     -H 'Content-Type: application/json' -d '{"name": "Åse"}'
```

```
201
```

```sh
curl 'http://localhost:9090/graphql' -H 'Content-Type: application/json' \
     -d '{"query": "{ PersonById(id: \"åse\") { name } }"}'
```

```json
{
  "data": {
    "PersonById": {"name": "Åse"}
  }
}
```

The answer is the same with `Content-Type: application/json; charset=iso-8859-1`, and with the query sent as
`application/graphql`.

### The answer

- The answer is JSON, on one line and without spaces, in UTF-8, with `Content-Type: application/json`. In this
  document answers are shown formatted so that they can be read.
- The status is `200 OK` whenever LDS could read the request, also when the query is wrong or a field fails. What went
  wrong is then in `errors` of the answer. [Errors and status codes](#errors-and-status-codes) has the other status
  codes.
- `data` holds what the query asked for, under the names of the query.

From here on a query is shown as GraphQL text and its answer as JSON. Each of these queries was sent with POST,
in the field `query` of a JSON body, as in the first example.

### Other methods

`OPTIONS /graphql` is answered `200 OK` with no body and with `Allow` headers that name `GET`, `POST` and
`OPTIONS`; `GET` is named twice. PUT, DELETE and HEAD are answered `405 Method Not Allowed`.

A browser that sends a query from a page of another origin first asks with a preflight request. LDS answers it
for `/graphql` as for every address; [the REST API](rest-api.md) tells how.

## The schema LDS makes from a model

LDS serves a schema that it makes from the model. This is what becomes of each thing in the model:

| In the model | In the GraphQL API |
|---|---|
| an entity type `X` (a type with `@domain`) | The type `X`, and two fields of `Query`: `XById(id: ID!): X` for one document, and `X(after: String, before: String, first: Int, last: Int): XConnection` for the documents of the entity type. |
| an embedded type (a type without `@domain`) | The same type. Its values are embedded objects, parts of documents, and are read inside the documents they are parts of. |
| a field that is not a link | The same field with the same type. |
| a field with `@link` that holds one link | The same field with the same type. It gives the document the link points at. |
| a field with `@link` that holds a list of links | The field keeps its name and becomes `field(after: String, before: String, first: Int, last: Int): TargetConnection`, a list that is read in pages. |
| a list of links with `@link(pagination: false)` | The same field with the same type: a plain list, without arguments. |
| every field with `@link` | One more field, on the entity type the link points at, for the [reverse link](../glossary.md#reverse-link): the documents that link to this one. Its name is the `reverseName` of the `@link`, or one that LDS makes, such as `reversePersonFriends` for `Person.friends`; [Reverse links](model.md#reverse-links) tells how. Its type is the connection of the entity type that has the link. |
| the type `Query` of the model | Not served. The model must have a `Query` type, and LDS puts its own fields in its place. |
| a description of a type or a field | The description of the same type or field. |

For every type `T` that is read in pages LDS adds the types `TConnection` and `TEdge`, and once the type `PageInfo`.
[Read the documents of an entity](#read-the-documents-of-an-entity) explains them. With search on LDS adds the field
`Search` and its types; see [Search](#search).

This is what LDS serves for the example model, written as a schema:

```graphql
type Query {
  Company(after: String, before: String, first: Int, last: Int): CompanyConnection
  CompanyById(id: ID!): Company
  Person(after: String, before: String, first: Int, last: Int): PersonConnection
  PersonById(id: ID!): Person
}

"""A person"""
type Person {
  address: Address
  born: DateTime
  employer: Company
  friends(after: String, before: String, first: Int, last: Int): PersonConnection
  name: String!
  reversePersonFriends(after: String, before: String, first: Int, last: Int): PersonConnection
}

type Address {
  city: String!
  street: String
}

"""A company"""
type Company {
  about: [Text]
  employees(after: String, before: String, first: Int, last: Int): PersonConnection
  founded: DateTime
  name: String!
}

"""
A text in one language. Search finds the text in its language (nb, nn, no, sv, en, de or fr), by the stem of its words,
and any other text of a document word for word.
"""
type Text {
  languageCode: String
  languageText: String!
}

type PersonConnection {
  edges: [PersonEdge!]!
  pageInfo: PageInfo!
}

type PersonEdge {
  cursor: String!
  node: Person!
}

type PageInfo {
  hasNextPage: Boolean!
  hasPreviousPage: Boolean!
}
```

`CompanyConnection` and `CompanyEdge` are like `PersonConnection` and `PersonEdge`. A connection field can be
`null` in the schema, also when the field of the model has `!`. The schema also declares the directives `@domain`,
`@link` and `@reverseLink`, and every other directive the model declares; introspection and the page for trying
queries show them.

Not finished: LDS does not keep the names it makes free; [Schemas that LDS refuses at
start](model.md#schemas-that-lds-refuses-at-start) has the names not to give a type of the model. One case shows in
the GraphQL API only: with an entity type `PageInfo` that no list of links points at, LDS starts, and the field
`pageInfo` of every list has that entity type as its type. A query for `pageInfo { hasNextPage }` is then refused.

### See the schema of your own model

Introspection, the way GraphQL has to ask a server for its schema, is always on. This query lists the queries
LDS made:

```graphql
{
  __type(name: "Query") {
    fields { name }
  }
}
```

```json
{
  "data": {
    "__type": {
      "fields": [
        {"name": "Company"},
        {"name": "CompanyById"},
        {"name": "Person"},
        {"name": "PersonById"}
      ]
    }
  }
}
```

A request can hold `__type` once and `__schema` once. A request that has one of them twice is refused with
the error `This request is not asking for introspection in good faith`; [Limits](#limits) has more. The page for trying
queries shows the whole schema; see [The page for trying queries](#the-page-for-trying-queries).

## Read one document

`XById` gives the document of the entity type `X` with the given id. Its one argument, `id`, is the id of the
document, a text. It must be there. A whole number is read as its text: `id: 42` is the id `42`.

```graphql
{
  PersonById(id: "ola") {
    name
    born
    address { city }
  }
}
```

```json
{
  "data": {
    "PersonById": {
      "name": "Ola Nordmann",
      "born": "1980-02-03T04:05:06.123456Z",
      "address": null
    }
  }
}
```

- The answer is the document as it is at the time of the query: an as-of read. That time is now, unless the
  request names another; see [Read at a time](#read-at-a-time).
- A field that the document does not have is `null`. Ola has no address.
- An embedded object is read as an object inside the document, and a list of embedded objects as a list. One
  request can ask for several fields of `Query`:

```graphql
{
  PersonById(id: "per") {
    name
    address { street city }
  }
  CompanyById(id: "acme") {
    name
    about { languageCode languageText }
  }
}
```

```json
{
  "data": {
    "PersonById": {
      "name": "Per",
      "address": {"street": "Storgata 1", "city": "Oslo"}
    },
    "CompanyById": {
      "name": "Acme",
      "about": [
        {
          "languageCode": "en",
          "languageText": "Makes anvils and rockets"
        }
      ]
    }
  }
}
```

### An id without a document

```graphql
{
  CompanyById(id: "acme") { name }
  PersonById(id: "nobody") { name }
}
```

```json
{
  "data": {
    "CompanyById": {"name": "Acme"},
    "PersonById": null
  }
}
```

When there is no document with the id at the time of the query, because it was never written, is written with a
later valid time, or is deleted, `XById` is `null`. That is not an error: the answer has no `errors`, and the other
fields of the same request are there, as `CompanyById` is here.

## Read the documents of an entity

The field `X` of `Query` gives the documents of the entity type `X` as a connection.

```graphql
{
  Person {
    edges {
      cursor
      node { name }
    }
    pageInfo { hasNextPage hasPreviousPage }
  }
}
```

```json
{
  "data": {
    "Person": {
      "edges": [
        {
          "cursor": "anne",
          "node": {"name": "Anne"}
        },
        {
          "cursor": "kari",
          "node": {"name": "Kari"}
        },
        {
          "cursor": "ola",
          "node": {"name": "Ola Nordmann"}
        },
        {
          "cursor": "per",
          "node": {"name": "Per"}
        },
        {
          "cursor": "åse",
          "node": {"name": "Åse"}
        }
      ],
      "pageInfo": {"hasNextPage": false, "hasPreviousPage": false}
    }
  }
}
```

### Connections, edges, cursors and page info

A connection is a list that can be read in pages. A connection of the entity type `X` has the type
`XConnection`:

| Type | Field | Type of the field | Meaning |
|---|---|---|---|
| `XConnection` | `edges` | `[XEdge!]!` | The documents of this page, one edge for each. |
| `XConnection` | `pageInfo` | `PageInfo!` | Whether there is more before and after this page. |
| `XEdge` | `node` | `X!` | The document. |
| `XEdge` | `cursor` | `String!` | The place of the document in the list. In a list of documents the cursor is the id of the document. |
| `PageInfo` | `hasNextPage` | `Boolean!` | `true` when there is a document after the last edge of this page. |
| `PageInfo` | `hasPreviousPage` | `Boolean!` | `true` when there is a document before the first edge of this page. |

`PageInfo` has no `startCursor` and no `endCursor`; take the cursors from the edges. A connection has no count
of its documents.

The edges come in the order of the ids, the same with every store, as [Read all documents of an
entity](rest-api.md#read-all-documents-of-an-entity) tells: `åse` comes after `per`. There is no argument to sort
the documents another way, and none to choose documents by the value of a field.

### Pages

Every connection takes the same four arguments:

| Argument | Type | Meaning |
|---|---|---|
| `first` | `Int` | At most this many documents, counted from the start. |
| `after` | `String` | Only documents that come after this cursor. |
| `last` | `Int` | At most this many documents, counted from the end. |
| `before` | `String` | Only documents that come before this cursor. |

To read a list page by page, ask for the first page with `first`:

```graphql
{
  Person(first: 2) {
    edges { cursor node { name } }
    pageInfo { hasNextPage hasPreviousPage }
  }
}
```

```json
{
  "data": {
    "Person": {
      "edges": [
        {
          "cursor": "anne",
          "node": {"name": "Anne"}
        },
        {
          "cursor": "kari",
          "node": {"name": "Kari"}
        }
      ],
      "pageInfo": {"hasNextPage": true, "hasPreviousPage": false}
    }
  }
}
```

Then ask for the next page with the cursor of the last edge as `after`, until `hasNextPage` is `false`:

```graphql
{
  Person(first: 2, after: "kari") {
    edges { cursor node { name } }
    pageInfo { hasNextPage hasPreviousPage }
  }
}
```

```json
{
  "data": {
    "Person": {
      "edges": [
        {
          "cursor": "ola",
          "node": {"name": "Ola Nordmann"}
        },
        {
          "cursor": "per",
          "node": {"name": "Per"}
        }
      ],
      "pageInfo": {"hasNextPage": true, "hasPreviousPage": true}
    }
  }
}
```

```graphql
{
  Person(first: 2, after: "per") {
    edges { cursor node { name } }
    pageInfo { hasNextPage hasPreviousPage }
  }
}
```

```json
{
  "data": {
    "Person": {
      "edges": [
        {
          "cursor": "åse",
          "node": {"name": "Åse"}
        }
      ],
      "pageInfo": {"hasNextPage": false, "hasPreviousPage": true}
    }
  }
}
```

What each combination of the arguments gives for a list of documents:

| Arguments | The answer |
|---|---|
| none | every document |
| `first: 2` | the first two |
| `first: 2, after: "kari"` | the first two with an id after `kari` |
| `after: "kari"` | every document with an id after `kari` |
| `after: "anne", before: "per"` | the documents with an id between the two, without the two themselves |
| `first: 1, after: "anne", before: "per"` | the first one of those between the two |
| `last: 2` | the last two, the last id first |
| `last: 2, before: "ola"` | the last two with an id before `ola`, the last id first |
| `before: "ola"` | every document with an id before `ola`, the last id first |
| `first` and `last` together | `last` is ignored |
| `first: 0` | no edges; `hasNextPage` and `hasPreviousPage` are both `false` |
| a negative `first` or `last` | an error, `The page size must not be negative: 'first'=-1`, and `null` for the field |

- A cursor does not have to be the id of a document. `after: "b"` gives the documents with an id after `b`.
- `first` has no upper limit. A `first` larger than the number of documents gives them all.
- `first` and `last` are numbers. `first: "2"` is refused.
- A page with no edges has `false` for both fields of `pageInfo`.

Not finished: reading backwards does not work as it should. With `last`, and with `before` alone, the edges come
with the last id first, and `hasNextPage` and `hasPreviousPage` are wrong: `Person(last: 2)` gives `åse` and `per`
and says `true` for both. Read forwards, with `first` and `after`.

## Follow links

### A link to one document

A field that holds one link gives the document the link points at. The fields of that document can be asked
for, its links too, link after link. [Limits](#limits) tells how deep a query can go.

```graphql
{
  PersonById(id: "ola") {
    name
    employer { name founded }
  }
}
```

```json
{
  "data": {
    "PersonById": {
      "name": "Ola Nordmann",
      "employer": {
        "name": "Globex",
        "founded": "2001-09-09T09:09:09.12+02:00"
      }
    }
  }
}
```

A document that does not have the field of the link links to nothing, and the field is `null`. Anne has no
`employer`: `PersonById(id: "anne") { name employer { name } }` gives `{"name": "Anne", "employer": null}`, with no
error.

### A list of links

A field that holds a list of links is a connection, with edges, cursors and the four arguments of [Pages](#pages).

```graphql
{
  PersonById(id: "ola") {
    friends {
      edges { cursor node { name } }
    }
  }
}
```

```json
{
  "data": {
    "PersonById": {
      "friends": {
        "edges": [
          {
            "cursor": "anne",
            "node": {"name": "Anne"}
          },
          {
            "cursor": "kari",
            "node": {"name": "Kari"}
          },
          {
            "cursor": "per",
            "node": {"name": "Per"}
          }
        ]
      }
    }
  }
}
```

The edges come in the order of the ids, not in the order of the links in the document. A document that does not
have the field gives no edges. With the documents in memory or in PostgreSQL, a link that is in the list twice gives
two edges for the same document; with Neo4j it gives one.

Not finished: the pages of a list of links do not work with the documents in memory or in PostgreSQL. `after` and
`before` are not used as cursors: `friends(first: 1, after: "anne")` gives `anne` again, and `before` alone gives
every document, the last id first. `first` alone gives the first documents and `last` alone the last, the last id
first. `hasNextPage` and `hasPreviousPage` are both `true` whenever there is an edge. With Neo4j the pages read
forwards, with `first` and `after`, are right; read backwards they have the fault of a list of documents (see
[Pages](#pages)).

### Reverse links

A reverse link gives the documents that link to this one. LDS adds the field; [The schema LDS makes from a
model](#the-schema-lds-makes-from-a-model) tells its name. In the example model `Company.employees` gives the persons
whose `employer` is the company, and `Person.reversePersonFriends` gives the persons who have the person among their
`friends`.

```graphql
{
  CompanyById(id: "acme") {
    name
    employees {
      edges { cursor node { name } }
      pageInfo { hasNextPage hasPreviousPage }
    }
  }
  PersonById(id: "kari") {
    reversePersonFriends {
      edges { node { name } }
    }
  }
}
```

```json
{
  "data": {
    "CompanyById": {
      "name": "Acme",
      "employees": {
        "edges": [
          {
            "cursor": "kari",
            "node": {"name": "Kari"}
          },
          {
            "cursor": "per",
            "node": {"name": "Per"}
          }
        ],
        "pageInfo": {
          "hasNextPage": false,
          "hasPreviousPage": false
        }
      }
    },
    "PersonById": {
      "reversePersonFriends": {
        "edges": [
          {
            "node": {"name": "Ola Nordmann"}
          }
        ]
      }
    }
  }
}
```

A reverse link is a connection. Reading it forwards with `first` and `after` works:
`employees(first: 1, after: "kari")` gives `per`. Reading it backwards has the fault that a list of documents
has; see [Pages](#pages).

Not finished: with the documents in memory a reverse link has two faults. Both come from a write or a delete at the
exact valid time of a version that is there, which replaces that version. A new version or a delete at a later time
does not have them.

- A link that was replaced in that way is still followed backward. A person was written with the `employer`
  `/Company/c1` and written again, with the same `timestamp`, with the `employer` `/Company/c2`. The person was
  then among the `employees` of both companies.
- A query for a reverse link can stop the store of LDS. When documents that link to a document are deleted, each
  with the `timestamp` of its version, a query for the reverse link of that document can get no answer, and then
  no read or write of a document is answered either. `/health/ready` answers that the store is down, while `/ping`
  and `/health/alive` still answer `200`. LDS must then be stopped and started again.

### A link to a document that is not there

LDS does not check that the target of a link exists when a document is written, so a link can point at nothing:
a dangling reference. This person links to a company and to a person that were never written:

```sh
put Person/nils 2020-01-01T00:00:00Z \
    '
{
  "name": "Nils",
  "employer": "/Company/initech",
  "friends": ["/Person/kari", "/Person/nobody"]
}
'
```

```
201
```

```graphql
{
  PersonById(id: "nils") {
    name
    employer { name }
    friends { edges { cursor } }
  }
}
```

```json
{
  "data": {
    "PersonById": {
      "name": "Nils",
      "employer": null,
      "friends": {
        "edges": [
          {"cursor": "kari"}
        ]
      }
    }
  }
}
```

- A link to one document gives `null`.
- In a list of links, read in pages or not, the link is left out; see the next section for a list without pages.
- A link to one document whose field has `!` in the model, such as `car: Car! @link`, cannot give `null`. The answer
  has an error for the field, and the document that holds the link is lost from the answer: `XById` is `null`, and a
  list of the entity type is `null` as a whole when one of its documents has such a link. The other fields of the
  request are kept.

With an entity type `Garage` whose field `car: Car! @link` points at nothing, `GarageById` is `null` and the error,
with the `classification` `NullValueInNonNullableField`, is:

```
The field at path '/GarageById/car' was declared as a non null type, but the code involved in retrieving data has
wrongly returned a null value.  The graphql specification requires that the parent field be set to null, or if
that is non nullable that it bubble up null to its parent and so on. The non-nullable type is 'Car' within
parent type 'Garage'
```

The link is resolved at the time of the query. When the company is written later, the same query gives it from
the valid time of its first version.

Not finished: with the documents in memory or in PostgreSQL, a link to a document that is not there still takes a
place in a page of a list of links. `friends(last: 1)` of `nils` gives no edge, and `first: 2` can give one edge
when the list has more.

### A list of links without pages, and a link to a union

In a model a list of links can be declared with `@link(pagination: false)`, and a link can point at a union of entity
types (`union Pet = Cat | Dog`): a document of any of them. The example model has neither. The examples of this section,
and of [Scalars and how values come out](#scalars-and-how-values-come-out), were run on LDS started with this model (the
key `graphql.schema` names the file):

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

enum Kind {
  SMALL
  LARGE
}

type Owner @domain {
  name: String!
  key: ID
  kind: Kind
  age: Int
  steps: Long
  height: Float
  member: Boolean
  since: Date
  wakes: Time
  seen: DateTime
  tags: [String]
  favourite: Pet @link
  pets: [Pet] @link
  cars: [Car] @link(pagination: false)
  animals: [Pet] @link(pagination: false)
  home: Place
}

type Cat @domain {
  name: String!
}

type Dog @domain {
  name: String!
}

union Pet = Cat | Dog

type Place {
  rooms: Int
}

type Car @domain {
  plate: String!
}

type Query {
}

scalar Date
scalar DateTime
scalar Time
scalar Long
```

The documents, written with the function `put` of [The examples of this document](#the-examples-of-this-document). The
owner links to a car, `/Car/zz`, that is not written.

```sh
put Cat/tom 2020-01-01T00:00:00Z '{"name": "Tom"}'
put Dog/fido 2020-01-01T00:00:00Z '{"name": "Fido"}'
put Dog/rex 2020-01-01T00:00:00Z '{"name": "Rex"}'
put Car/a1 2020-01-01T00:00:00Z '{"plate": "A1"}'
put Car/b2 2020-01-01T00:00:00Z '{"plate": "B2"}'
put Owner/eva 2020-01-01T00:00:00Z '
{
  "name": "Eva",
  "key": "k-1",
  "kind": "LARGE",
  "age": 41,
  "steps": 12345678901,
  "height": 1.72,
  "member": true,
  "since": "2019-03-11",
  "wakes": "06:30:00+01:00",
  "seen": "2019-03-11T10:15:30.120+01:00",
  "tags": ["a", "b"],
  "favourite": "/Cat/tom",
  "pets": ["/Dog/fido", "/Cat/tom", "/Dog/rex"],
  "cars": ["/Car/a1", "/Car/zz", "/Car/b2"],
  "animals": ["/Dog/rex", "/Cat/tom"]
}
'
```

```
201
201
201
201
201
201
```

A list of links with `@link(pagination: false)` is a plain list of documents, in the order of the links in the
document. It takes no arguments. A link to a document that is not there at the time of the query is left out:
`/Car/zz` gives nothing. A document that does not have the field gives `null`, not an empty list.

```graphql
{
  OwnerById(id: "eva") {
    cars { plate }
  }
}
```

```json
{
  "data": {
    "OwnerById": {
      "cars": [
        {"plate": "A1"},
        {"plate": "B2"}
      ]
    }
  }
}
```

A document of a plain list is read as any other: its own links and reverse links can be asked for.
`cars { plate reverseOwnerCars { edges { cursor } } }` gives `eva` for each car.

A link to a union gives a document of any entity type of the union. Ask for `__typename` to learn the entity
type of a document, and for the fields of each entity type with `... on`:

```graphql
{
  OwnerById(id: "eva") {
    favourite {
      __typename
      ... on Cat { name }
      ... on Dog { name }
    }
    pets {
      edges {
        cursor
        node {
          __typename
          ... on Cat { name }
          ... on Dog { name }
        }
      }
    }
  }
}
```

```json
{
  "data": {
    "OwnerById": {
      "favourite": {"__typename": "Cat", "name": "Tom"},
      "pets": {
        "edges": [
          {
            "cursor": "fido",
            "node": {"__typename": "Dog", "name": "Fido"}
          },
          {
            "cursor": "rex",
            "node": {"__typename": "Dog", "name": "Rex"}
          },
          {
            "cursor": "tom",
            "node": {"__typename": "Cat", "name": "Tom"}
          }
        ]
      }
    }
  }
}
```

A plain list of links to a union, `animals`, gives the documents in the order of the links in the document:

```graphql
{
  OwnerById(id: "eva") {
    animals {
      __typename
      ... on Cat { name }
      ... on Dog { name }
    }
  }
}
```

```json
{
  "data": {
    "OwnerById": {
      "animals": [
        {"__typename": "Dog", "name": "Rex"},
        {"__typename": "Cat", "name": "Tom"}
      ]
    }
  }
}
```

For a link to a union LDS adds the field of the reverse link to every entity type of the union. `Cat` and `Dog` both
have `reverseOwnerFavourite`, `reverseOwnerPets` and `reverseOwnerAnimals`:

```graphql
{
  DogById(id: "rex") {
    name
    reverseOwnerPets { edges { node { name } } }
    reverseOwnerFavourite { edges { node { name } } }
  }
}
```

```json
{
  "data": {
    "DogById": {
      "name": "Rex",
      "reverseOwnerPets": {
        "edges": [
          {
            "node": {"name": "Eva"}
          }
        ]
      },
      "reverseOwnerFavourite": {
        "edges": []
      }
    }
  }
}
```

Not finished: the pages of a list of links to a union are wrong, and the same request does not always get the same
answer. With the three pets above, `pets(first: 2)` gave `fido` and `tom` and left out `rex`, and `pets(first: 1)`
gave `tom` at one time and `fido` at another. `hasNextPage` and `hasPreviousPage` were `false` for every page. Read
such a list without arguments.

### Links in a part of a document

Not finished: a link in an embedded object (a field with `@link` in an embedded type) works only in part. A link to one
document is read as described above. A list of links gives `null` and an error. The field LDS adds for the reverse link
of such a link never gives a document. Its name holds the field of the embedded object too: a link `car` in the
field `home` of `Owner` gives `reverseOwnerHomeCar`.

## Read at a time

Every query is an as-of read: it is answered as of one time, and reads the
[snapshot](../glossary.md#snapshot) of that time. `XById` gives the version of the document that is valid at
that time, a list holds the documents that exist at that time, every link and reverse link is followed at that
time, and a search looks at the versions valid at that time (see [Search](#search)). Without a time in the request
the time is now.

The time is the query parameter `snapshot` of the address. It works for GET and for POST.

```sh
curl 'http://localhost:9090/graphql?snapshot=2021-06-01T00:00:00Z' \
     -H 'Content-Type: application/graphql' \
     -d '{
       PersonById(id: "ola") {
         name
         employer {
           name
         }
         friends {
           edges {
             cursor
           }
         }
       }
     }'
```

```json
{
  "data": {
    "PersonById": {
      "name": "Ola",
      "employer": {"name": "Acme"},
      "friends": {
        "edges": [
          {"cursor": "kari"}
        ]
      }
    }
  }
}
```

The time can also be given as the GraphQL variable `__snapshot`, with two underscores. The query does not
declare the variable. A query that declares it and does not use it is refused.

```sh
curl 'http://localhost:9090/graphql' -H 'Content-Type: application/json' \
     -d '{
       "query": "{ CompanyById(id: \"acme\") { employees { edges { cursor } } } }",
       "variables": {"__snapshot": "2021-06-01T00:00:00Z"}
     }'
```

```json
{
  "data": {
    "CompanyById": {
      "employees": {
        "edges": [
          {"cursor": "kari"},
          {"cursor": "ola"},
          {"cursor": "per"}
        ]
      }
    }
  }
}
```

In 2021 Ola worked at Acme. Now the employees of Acme are `kari` and `per`, as in [Follow links](#follow-links).

- When both are given, the parameter `snapshot` is used.
- The value is read as the REST API reads its `timestamp`: a date and a time with `Z` or an offset, a `+` in an
  address written `%2B`; [The time of a request](rest-api.md#the-time-of-a-request-timestamp) has every form. The
  GraphQL API reads `snapshot` only, not `timestamp`.
- A version is read from its valid time on. The second version of `ola` is valid from `2022-01-01T00:00:00Z`:
  `snapshot=2022-01-01T00:00:00Z` gives it, and `snapshot=2021-12-31T23:59:59Z` gives the first version.
- The time can be in the future. A version whose valid time has not come is not read now. It is read with a
  `snapshot` at or after its valid time.
- At a time before the first version of a document, and at a time after the document is deleted, `XById` is `null`,
  and the document is not in a list and not among the documents of a link.

A `snapshot` or a `__snapshot` that is not a time is refused: the query is not run, and the answer has an error
and no `data`. `snapshot=yesterday` gives:

```json
{
  "errors": [
    {
      "message": "The query parameter snapshot 'yesterday' is not an ISO-8601 time. Example of a valid formatted time is '2018-12-06T11:05:31.000+01:00'",
      "locations": [],
      "extensions": {"classification": "ValidationError"}
    }
  ]
}
```

More than one `snapshot` parameter is refused in the same way, with `more than one snapshot value in query`, and a
`__snapshot` that is neither a text nor `null` with `the variable __snapshot must be a string`.

## Scalars and how values come out

A value comes out in the form of the scalar of its field. These are the scalars a model can have:

| Scalar | In the answer | Example |
|---|---|---|
| `String` | a text | `"Eva"` |
| `Int` | a whole number of 32 bits, from -2147483648 to 2147483647 | `41` |
| `Long` | a whole number of 64 bits, from -9223372036854775808 to 9223372036854775807 | `12345678901` |
| `Float` | a number | `1.72` |
| `Boolean` | `true` or `false` | `true` |
| `ID` | a text | `"k-1"` |
| `DateTime` | a text: a date and a time of RFC 3339, with `Z` or an offset | `"2019-03-11T10:15:30.12+01:00"` |
| `Date` | a text: a date of RFC 3339 | `"2019-03-11"` |
| `Time` | a text: a time of RFC 3339, with `Z` or an offset | `"06:30:00+01:00"` |
| an enum of the model | the name of the value, as a text | `"LARGE"` |

`Long`, `DateTime`, `Date` and `Time` are there when the model declares them (`scalar Long`) and a field uses them.
A model cannot have other scalars of its own; [the model](model.md) tells more.

With the model and the owner of [A list of links without pages, and a link to a
union](#a-list-of-links-without-pages-and-a-link-to-a-union):

```graphql
{
  OwnerById(id: "eva") {
    name key kind age steps height member since wakes seen tags
  }
}
```

```json
{
  "data": {
    "OwnerById": {
      "name": "Eva",
      "key": "k-1",
      "kind": "LARGE",
      "age": 41,
      "steps": 12345678901,
      "height": 1.72,
      "member": true,
      "since": "2019-03-11",
      "wakes": "06:30:00+01:00",
      "seen": "2019-03-11T10:15:30.12+01:00",
      "tags": ["a", "b"]
    }
  }
}
```

A `DateTime` comes out as the same moment with the offset it was written with; it is not turned into UTC.

- The fraction of a second comes out without zeros at its end. The document has
  `2019-03-11T10:15:30.120+01:00` and the answer has `.12`. A fraction of zero is left out, and a fraction of
  nine digits comes out with nine digits.
- The offset `+00:00` comes out as `Z`.
- The REST API gives the text as it was written, with `.120`.
- A `Time` follows the same two rules: `06:30:00.500Z` comes out as `06:30:00.5Z`, and `06:30:00+00:00`
  as `06:30:00Z`.

LDS checks the value of a field against its scalar when a document is written
([schema validation](../glossary.md#schema-validation)), and refuses a value that the scalar cannot give with
`400 Bad Request`; [The model](model.md) tells what it checks. The text `"old"` in the `Int` field
`age` is refused with `Schema validation error: [$.age: string found, integer expected]`, and `2147483648` with
`Schema validation error: [$.age: must have a maximum value of 2147483647]`. A value that is written comes out as
the type of its field:

- In an `Int` field the number `2.0` comes out as `2`. In a `Float` field `2` comes out as `2.0`.
- An `ID` field is not checked. Its value comes out as a text: the number `5` as `"5"`, `true` as `"true"`.

Not finished: an object or a list in an `ID` field comes out as a text LDS makes of it that is not JSON: `{"a": 1}`
comes out as `"{a=1}"`.

Not finished: the fields of an embedded object that a field holds alone, such as `home: Place`, are not checked when
the document is written; those of a list of embedded objects are. See [Shapes that do not work as
written](model.md#shapes-that-do-not-work-as-written).
Such a value that is not of the type of its field is `null` in the answer, and the answer has an error. The text
`"many"` in `home.rooms`, an `Int`, gives this one, and the number `1.5` gives the same with `'Double'` at its end:

```
Can't serialize value (/OwnerById/home/rooms) : Expected a value that can be converted to type 'Int' but it was a
'String'
```

The time of a query is not a GraphQL argument, and no field takes a `DateTime` as an argument. See [Read at a
time](#read-at-a-time).

## Search

Search finds documents by the words of their texts. Every text of a document is searched, wherever it is in the
document: a text field of the model, a text in an embedded object or in a list, and a text in a field that the model
does not have. A link, a date, a time (a field of `DateTime`, `Date` or `Time`), a number and a boolean are not text
and are not searched, and neither is the id of the document.

Search is off as LDS comes. It is on when the key `graphql.search.enabled` is `true`, and it needs a search
index, which is Solr. The compose stack of [Getting started](getting-started.md) has search on.
[Configuration](configuration.md) has the keys and [Operations](operations.md) tells how Solr is
run. With search off there is no field `Search`, and a query for it is answered
`Field 'Search' in type 'Query' is undefined`.

With search on, LDS adds this to the schema. `TypeFilters` and `SearchResult` hold every entity type of the
model that is searched: an entity type marked `@domain(searchable: false)` in the model is not, and its documents
are never found. When no entity type is searched, there is no field `Search`. This is what LDS serves for the
example model:

```graphql
type Query {
  Company(after: String, before: String, first: Int, last: Int): CompanyConnection
  CompanyById(id: ID!): Company
  Person(after: String, before: String, first: Int, last: Int): PersonConnection
  PersonById(id: ID!): Person
  Search(
    after: String
    before: String
    filter: [TypeFilters]
    first: Int
    "Every version up to the snapshot that matches, and not only the one valid at it"
    history: Boolean = false
    last: Int
    query: String!
  ): SearchResultConnection
}

type SearchResultConnection {
  edges: [SearchResultEdge!]!
  pageInfo: PageInfo!
}

type SearchResultEdge {
  cursor: String!
  node: SearchResult!
}

"""Union type for possible search results"""
union SearchResult = Company | Person

"""Defines valid type filters"""
enum TypeFilters {
  Company
  Person
}
```

| Argument of `Search` | Type | Meaning |
|---|---|---|
| `query` | `String!` | The words to search for. It must be there. |
| `filter` | `[TypeFilters]` | Only documents of these entity types. The values are names of entity types, without quotes: `filter: [Company]`. Without it, or with an empty list, documents of every entity type that is searched are found. |
| `history` | `Boolean` | `true` gives every version of a document up to the time of the query that has the words, not only the version valid at that time. It is `false` when it is left out. See [Search at a time, and history](#search-at-a-time-and-history). |
| `first`, `after`, `last`, `before` | as in [Pages](#pages) | The page of the result. The cursors are those of a search; see [The pages of a search](#the-pages-of-a-search). |

The search examples were run on LDS in memory with the example model and search on, and with Solr 10 as the compose
stack starts it. A search finds a write within about a second. The documents of the examples are written with
`searchable=true` in the place of `sync=true`: LDS then answers the write when a search finds it, or after 10
seconds at most, with the header `LDS-Searchable: true` or `false`; [When search finds a write:
searchable](rest-api.md#when-search-finds-a-write-searchable) tells more.

```sh
put() {
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT "http://localhost:9090/ns/$1?searchable=true&timestamp=$2" \
       -H 'Content-Type: application/json' -d "$3"
}
```

The documents:

```sh
put Company/acme 2020-01-01T00:00:00Z \
    '
{
  "name": "Acme",
  "about": [
    {
      "languageCode": "en",
      "languageText": "Makes anvils and rockets"
    }
  ]
}
'
put Company/globex 2020-01-01T00:00:00Z '
{
  "name": "Globex",
  "about": [
    {"languageCode": "en", "languageText": "Sells rockets"},
    {"languageCode": "nb", "languageText": "Selger raketter og bøker"}
  ]
}
'
put Company/initech 2020-01-01T00:00:00Z \
    '
{
  "name": "Initech",
  "about": [
    {
      "languageCode": "en",
      "languageText": "Writes software for rockets"
    }
  ]
}
'
put Person/kari 2020-01-01T00:00:00Z '
{
  "name": "Kari",
  "employer": "/Company/acme"
}
'
put Person/ase 2020-01-01T00:00:00Z '
{
  "name": "Åse",
  "address": {"street": "Storgata 1", "city": "Oslo"}
}
'
put Person/aase 2020-01-01T00:00:00Z '{"name": "Aase"}'
```

```
201
201
201
201
201
201
```

A node of the result is a document of any entity type, so the query says which fields it wants for which entity
type, with `... on`:

```graphql
{
  Search(query: "rockets") {
    edges {
      cursor
      node {
        __typename
        ... on Company { name }
        ... on Person { name }
      }
    }
    pageInfo { hasNextPage hasPreviousPage }
  }
}
```

```json
{
  "data": {
    "Search": {
      "edges": [
        {
          "cursor": "cWM6MA",
          "node": {"__typename": "Company", "name": "Globex"}
        },
        {
          "cursor": "cWM6MQ",
          "node": {"__typename": "Company", "name": "Acme"}
        },
        {
          "cursor": "cWM6Mg",
          "node": {"__typename": "Company", "name": "Initech"}
        }
      ],
      "pageInfo": {"hasNextPage": false, "hasPreviousPage": false}
    }
  }
}
```

A search with a filter and a page size:

```graphql
{
  Search(query: "rockets", filter: [Company], first: 2) {
    edges {
      cursor
      node { ... on Company { name } }
    }
    pageInfo { hasNextPage hasPreviousPage }
  }
}
```

```json
{
  "data": {
    "Search": {
      "edges": [
        {
          "cursor": "cWM6MA",
          "node": {"name": "Globex"}
        },
        {
          "cursor": "cWM6MQ",
          "node": {"name": "Acme"}
        }
      ],
      "pageInfo": {"hasNextPage": true, "hasPreviousPage": false}
    }
  }
}
```

### How words are found

- A text in an object that has a `languageCode` and a `languageText`, both texts, is in the language of the code,
  such as a value of the embedded type `Text` of the example model. A word of it is found by its stem, the part
  that its forms share: `rocket` finds `rockets`, `make` finds `Makes`, and `raketten` finds `raketter`. The
  languages are Norwegian (`nb`, `nn` and `no`), Swedish (`sv`), English (`en`), German (`de`) and French (`fr`).
  The code can be written in capital letters and with a region: `NB`, `nb-NO`, `en_GB`. The code itself is not
  searched.
- Every other text, and a text with a code of another language, is found word for word: `Storgata`, `Oslo` and `1`
  find Åse by her address, and `acme` finds Acme by its name. `acme` does not find Kari, whose `employer` is a link.
- Capital and small letters are the same: `oslo` finds `Oslo`.
- A word is also found typed without the letters of its language: `boker` finds `bøker`, and `Aase` finds `Åse`.
  In Norwegian, Swedish and texts of no language `ø`, `ö` and `oe` are read as `o`, and `å`, `ä`, `æ`, `aa` and
  `ae` as `a`; in every language an accent is taken off (`é` as `e`). A letter and its accent written as a mark of
  its own after it, as some keyboards write it, are the same as the letter written whole.
- A word as it is written ranks first. `Aase` finds Aase, then Åse, and `Åse` finds Åse, then Aase.
- Several words find a document that has any of them, and one with more of them ranks higher. A word with `-` in
  front leaves out the documents that have it: `rockets -anvils` finds Globex and Initech. Words in quotes are found
  only together, in that order: `"for rockets"` finds Initech.
- Text that would be a query in the language of Solr does not make the search fail: `(rockets` finds what
  `rockets` finds, and an open bracket or a quote alone finds nothing. A field name, as in `name:Acme`, does not
  choose a field.
- No words, an empty text or only spaces, find nothing.

### Search at a time, and history

A search is an as-of read, as every query is; see [Read at a time](#read-at-a-time). It looks at the version of each
document that is valid at the time of the query, and finds the document when that version has the words. A word that
only an earlier version had does not find it. A document that is deleted at that time is not found, and is found at
a time before it was deleted.

Initech writes a new version in 2024, without the word `software`:

```sh
put Company/initech 2024-01-01T00:00:00Z \
    '
{
  "name": "Initech",
  "about": [
    {
      "languageCode": "en",
      "languageText": "Makes games"
    }
  ]
}
'
```

```
201
```

Now `Search(query: "software")` finds nothing. With `snapshot=2021-01-01T00:00:00Z` it finds Initech, as it was in
2021.

With `history: true` a search looks at every version up to the time of the query, and gives each version that has
the words. A node of the result is the version that has the words, read as it was at its own valid time:

```graphql
{
  Search(query: "initech", history: true) {
    edges {
      cursor
      node { ... on Company { name about { languageText } } }
    }
  }
}
```

```json
{
  "data": {
    "Search": {
      "edges": [
        {
          "cursor": "cWM6MA",
          "node": {
            "name": "Initech",
            "about": [
              {"languageText": "Makes games"}
            ]
          }
        },
        {
          "cursor": "cWM6MQ",
          "node": {
            "name": "Initech",
            "about": [
              {"languageText": "Writes software for rockets"}
            ]
          }
        }
      ]
    }
  }
}
```

`Search(query: "software", history: true)` gives one edge: the version of 2020. A deletion is never in the result.

### What a search gives

- The result comes in the order of how well each document has the words, the best first. The number of documents
  found is not in the answer.
- A value of `filter` that is not an entity type that is searched is refused, and so is a value in quotes.
- A document written while search was off is found after the index is rebuilt from the store;
  [Operations](operations.md) tells how.
- When LDS cannot reach Solr, the answer has an error for `Search`, such as
  `Exception while fetching data (/Search) : Connection refused (connect failed)`, and `Search` is `null`.

### The pages of a search

- The cursor of an edge is the place of the edge in the result, not an id. `cWM6MA` is the first place,
  `cWM6MQ` the second. Take the cursors from the edges of a search. A cursor of another list, such as an id, is not a
  place, and is mostly refused with an error: `acme` gives `expecting a valid cursor value, got acme`.
- Without `first` and `last` a search gives at most 10 documents.
- `first` and `last` give at most 100 documents. A larger number gives 100, and `hasNextPage` or
  `hasPreviousPage` tells that there are more.
- The page after a cursor starts after it, and the page before a cursor ends before it, so the edge of the
  cursor does not come again.
- `last` without `before` gives the end of the result.
- `hasPreviousPage` is `true` when the page does not start at the first place. `hasNextPage` is `true` when
  the result goes on after the page.

## Errors and status codes

### Status codes

| Status | When |
|---|---|
| `200 OK` | LDS read the request. The answer is JSON. A query that is wrong, or a field that failed, is told in `errors` of the answer. |
| `204 No Content` | The answer to the preflight request of a browser. |
| `400 Bad Request` | A request that is not a GraphQL request LDS can read: no query, a body or a `variables` parameter that is not JSON, a JSON body that is not an object, a parameter given twice. The answer is JSON, an error with no `data`. |
| `400 Bad Request` | There is no GraphQL API: the model is not a GraphQL schema, or `graphql.enabled` is `false`. The body is the text `Unsupported namespace: "graphql"`. |
| `404 Not Found` | `/graphql` when `http.prefix` puts the API at another address. |
| `405 Method Not Allowed` | A method other than GET, POST and OPTIONS. |
| `415 Unsupported Media Type` | A POST with a content type that LDS does not read, or with none. The answer is JSON, an error with no `data`; see [Content types](#content-types). |
| `500 Internal Server Error` | LDS failed in a way it did not foresee while it ran the query. The answer has no body, and the reason is in the log of LDS. |
| `503 Service Unavailable` | LDS is stopping; [Operations](operations.md) tells more. |

A request that LDS cannot read is a mistake of the caller, and it is answered `400` with one error that says
what is wrong, and no `data`:

| Request | `message` |
|---|---|
| A JSON body without `query`, or with a `query` that is not a text (`null`, a number) | `The body has no "query" text` |
| A GET without the parameter `query` | `The request has no "query" parameter` |
| An empty body, with `application/json` or `application/graphql` | `The body is empty` |
| A body that is not JSON, or has text after the JSON object, with `application/json` | `The body cannot be read as JSON at line 1, column 1: ...`, with the line, the column and the reason |
| A JSON body that is not an object, such as an array: several queries in one request are not possible | `The body is not a JSON object with the field "query"` |
| `variables` that is not an object, in a body or in a GET | `The variables are not a JSON object` |
| A `variables` parameter of a GET that is not JSON | `The parameter "variables" cannot be read as JSON at line 1, column 2: ...` |
| An empty `variables` parameter of a GET | `The parameter "variables" is empty` |
| More than one `query`, `variables` or `operationName` parameter in a GET | `more than one "query" parameter` |

```sh
curl -s 'http://localhost:9090/graphql' -H 'Content-Type: application/json' \
     -d '{"query": "{ PersonById(id: \"ola\") { name } }"} and more'
```

```json
{
  "errors": [
    {
      "message": "The body cannot be read as JSON at line 1, column 51: Unrecognized token 'and': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')"
    }
  ]
}
```

More than one `snapshot` parameter, or a `__snapshot` variable that is neither a text nor `null`, is answered
`200 OK` with an error, as a `snapshot` that is not a time is; see [Read at a time](#read-at-a-time) for the
messages.

A `query` that is an empty text is no such request: it is answered `200 OK` with an `InvalidSyntax` error.

### Errors in the answer

An error is an object in the list `errors`. It has a `message`, the `locations` in the query text as it was
sent (line and column) where there is one, the `path` to the field in the answer when a field failed, and a
`classification` in `extensions`. `data` holds what could be read; a field that failed is `null`.

```graphql
{
  PersonById(id: "ola") { nmae }
}
```

```json
{
  "errors": [
    {
      "message": "Validation error (FieldUndefined@[PersonById/nmae]) : Field 'nmae' in type 'Person' is undefined",
      "locations": [
        {"line": 2, "column": 27}
      ],
      "extensions": {"classification": "ValidationError"}
    }
  ]
}
```

| Case | `message` | `classification` |
|---|---|---|
| The query cannot be read as GraphQL, or is empty. Here the last `}` of `{ PersonById(id: "ola") { name } }` is left out | `Invalid syntax with offending token '<EOF>' at line 1 column 33` | `InvalidSyntax` |
| A field that the schema does not have | `Validation error (FieldUndefined@[PersonById/nmae]) : Field 'nmae' in type 'Person' is undefined` | `ValidationError` |
| An argument that must be there is left out | `Validation error (MissingFieldArgument@[PersonById]) : Missing field argument 'id'` | `ValidationError` |
| An argument of the wrong type | `Validation error (WrongType@[Person]) : argument 'first' with value 'StringValue{value='2'}' is not a valid 'Int' - Expected an AST type of 'IntValue' but it was a 'StringValue'` | `ValidationError` |
| A variable that must have a value has none | `Variable 'id' has an invalid value: Variable 'id' has coerced Null value for NonNull type 'ID!'` | `ValidationError` |
| A mutation or a subscription | `Validation error (UnknownOperation): The 'Mutation' operation is not supported by the schema` | `ValidationError` |
| Several operations and no `operationName` | `Must provide operation name if query contains multiple operations.` | `ValidationError` |
| An `operationName` that the query does not have | `Unknown operation named 'Z'.` | `ValidationError` |
| A link to one document whose field has `!` in the model, and whose document is not there | `The field at path '/GarageById/car' was declared as a non null type, but ...`; see [A link to a document that is not there](#a-link-to-a-document-that-is-not-there) | `NullValueInNonNullableField` |
| A `snapshot` or a `__snapshot` that is not a time | `The query parameter snapshot 'yesterday' is not an ISO-8601 time. ...`; see [Read at a time](#read-at-a-time) | `ValidationError` |
| A negative `first` or `last` | `Exception while fetching data (/Person) : The page size must not be negative: 'first'=-1` | `DataFetchingException` |
| A value in an embedded object that the scalar of its field cannot give | `Can't serialize value (/OwnerById/home/rooms) : Expected a value that can be converted to type 'Int' but it was a 'String'` | `DataFetchingException` |
| A query over a limit | see [Limits](#limits) | `InvalidSyntax`, `ValidationError` or `BadFaithIntrospection` |
| Search: a `filter` value that is not an entity type | `Validation error (WrongType@[Search]) : argument 'filter[0]' with value 'EnumValue{name='Address'}' is not a valid 'TypeFilters' - Literal value not in allowable values for enum 'TypeFilters' - 'EnumValue{name='Address'}'` | `ValidationError` |
| Search: a cursor that is not one of a search | `Exception while fetching data (/Search) : expecting a valid cursor value, got acme` | `DataFetchingException` |

### Limits

A query over one of these limits is not run. It is answered `200 OK` with the error of the table and without
`data`.

| Limit | `message` | `classification` |
|---|---|---|
| At most 15000 tokens in a query. A token is a piece of the query text: a name, a bracket, a value. | `More than 15,000 'grammar' tokens have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.` | `InvalidSyntax` |
| At most 200000 spaces and line breaks in a query | `More than 200,000 'whitespace' tokens have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.` | `InvalidSyntax` |
| At most 1048576 characters in a query | `More than 1,048,576 characters have been presented. To prevent Denial Of Service attacks, parsing has been cancelled.` | `InvalidSyntax` |
| Fields at most 100 deep, the field of `Query` counted as the first | `Query depth 101 exceeds maximum allowed depth 100` | `ValidationError` |
| At most 100000 fields in a request. The fields of a fragment count each time the fragment is used. | `Query has 100,091 fields which exceeds maximum allowed 100,000` | `ValidationError` |
| `__type` and `__schema` each once in a request | `This request is not asking for introspection in good faith - Query.__type is present too often!` | `BadFaithIntrospection` |
| In a query for the schema, no `fields` inside `fields` | `This request is not asking for introspection in good faith - __Type.fields is present too often!` | `BadFaithIntrospection` |

- A link to one document is one field deep, and a list of links that is read in pages is three: the field,
  `edges` and `node`. In `{ PersonById(id: "ola") { friends { edges { node { name } } } } }` the field `name` is
  five deep.
- The messages for the depth and for the number of fields do not tell the size of the query: counting stops
  when the limit is passed.
- A query that is far deeper than the limit, 300 fields deep when it was tried, is refused with another
  message: `More than 500 deep 'grammar' rules have been entered. To prevent Denial Of Service attacks, parsing
  has been cancelled.`
- `first` and `last` have no upper limit in a list (see [Pages](#pages)), and at most 100 in a search (see
  [The pages of a search](#the-pages-of-a-search)).

Everything else of GraphQL works as usual: aliases, fragments, variables with default values, `@skip` and
`@include`, `__typename` and introspection.

## The page for trying queries

LDS serves GraphiQL, a page for writing and sending queries in a browser, at `/graphiql`. It is there whenever
the GraphQL API is there. With LDS as it comes, open `http://localhost:9090/graphiql`.

- `/graphiql` answers with a redirect to `/graphiql/`, which is the page.
- The page shows the schema LDS serves, with every query, type and field, under `Docs`.
- The page sends its queries to `/graphql` with POST, as JSON.
- What is written in the variables pane of the page is sent as `variables`. For an as-of read, write
  `{"__snapshot": "2021-06-01T00:00:00Z"}` there; see [Read at a time](#read-at-a-time).
- The page keeps the query, the variables and the operation name in its own address, so the address can be
  kept or given to someone else. A page opened with such an address shows the query and the variables, and
  sends the query when the button of the page is pressed.

Not finished: LDS does not hold GraphiQL itself. The browser fetches GraphiQL 0.12.0 and its scripts from
`unpkg.com` when the page is opened, so the browser needs access to the internet.

