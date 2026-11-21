# Query shapes

Every shape of query the GraphQL API of LDS answers, each with a request that was run on `descoped/lds:3.0.0`
and the answer it gave.

## Contents

- About the examples
- One document
- Several things in one request
- The documents of an entity type, and pages
- Links
- Reverse links
- Unions and plain lists of links
- At a time
- Search
- Scalars
- Errors and limits

## About the examples

The examples use the example model LDS comes with, because it is small. With a user, take every name from the
schema LDS made from the user's model (`scripts/schema.sh`): the entity types, the fields, the reverse links.
These are the main lines of the example model:

```graphql
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

type Company @domain {
  name: String!
  founded: DateTime
  about: [Text]
}

type Text {
  languageCode: String
  languageText: String!
}
```

The documents: the companies `acme` and `globex`, the persons `anne`, `kari` and `per`, and `ola` with two
versions. From 2020 Ola lives in Bergen, works at Acme and has the friend `kari`; from 2022 the name is Ola
Nordmann, the city Oslo, the employer Globex, and the friends `anne`, `kari` and `per`. Kari and Per work at Acme.
Anne has no employer.

Each query below was sent with `scripts/query.sh`, or as in the [Send a query](../SKILL.md#send-a-query)
section of the skill. The answers are formatted here; LDS sends them on one line.

## One document

`XById(id: "...")` gives the document of entity type `X` with that id, as it is at the time of the query.

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
      "address": {"city": "Oslo"}
    }
  }
}
```

- A field the document does not have is `null`.
- An embedded object (`address`) is an object inside the document; ask for its fields.
- When there is no document with the id at the time of the query, the answer is the error
  `The field at path '/PersonById' was declared as a non null type, ...` and `"data": null`, for the whole
  request. Every other field of the same request is lost with it. So ask for a document that may be missing in a
  request of its own, or check first with the REST API (`GET /ns/Person/<id>` answers `404`; `lds:data`).

## Several things in one request

Several fields of `Query`, aliases, fragments and variables work as GraphQL has them.

```graphql
{
  k: PersonById(id: "kari") { name }
  p: PersonById(id: "per") { name }
}
```

```json
{
  "data": {
    "k": {"name": "Kari"},
    "p": {"name": "Per"}
  }
}
```

```graphql
{
  PersonById(id: "ola") { name ...Work }
}

fragment Work on Person { employer { name } }
```

```json
{
  "data": {
    "PersonById": {
      "name": "Ola Nordmann",
      "employer": {"name": "Globex"}
    }
  }
}
```

A query with variables declares them (`query One($id: ID!) { PersonById(id: $id) { name } }`) and is sent as
JSON with `"variables": {"id": "ola"}`; `scripts/query.sh file.graphql '{"id": "ola"}'` does that. Variables
keep the user's values out of the query text, which is what a program should do.

## The documents of an entity type, and pages

`X` gives the documents of entity type `X` as a connection: `edges`, each with `cursor` and `node`, and
`pageInfo`. The edges come in the order of the ids, compared as text; the cursor of an edge is the id.

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

The next page: the cursor of the last edge as `after`, until `hasNextPage` is `false`.

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
      "pageInfo": {"hasNextPage": false, "hasPreviousPage": true}
    }
  }
}
```

| Arguments | The answer |
|---|---|
| none | every document |
| `first: 2` | the first two |
| `first: 2, after: "kari"` | the first two with an id after `kari` |
| `after: "anne", before: "per"` | the documents with an id between the two, without the two |
| `first: 0` | no edges, and `false` for both fields of `pageInfo` |
| `first: -1` | the error `The page size must not be negative: 'first'=-1`, and `null` for the field |
| `first: "2"` | refused: `first` is a number |
| `first` and `last` | `last` is ignored |

- A cursor does not have to be an id that exists: `after: "b"` gives the ids after `b`.
- `first` has no upper limit. There is no count of the documents, no `startCursor` or `endCursor`, no argument
  to sort another way, and none to choose documents by the value of a field: filter on the client.

Not finished: reading backwards does not work as it should. `Person(last: 2)` gave `per` then `ola`, the last
id first, and `true` for both fields of `pageInfo`. Read forwards with `first` and `after`.

Not finished: with LDS in memory or on PostgreSQL every document of the entity type is read before a page is
given, so a page of a large entity type takes as long as the whole list. With LDS in memory a document whose id
begins with a letter outside ASCII is left out of the list (on Neo4j it was there); `XById` finds it.

## Links

A field that holds one link gives the document it points at; ask for its fields, and its links, link after link.

```graphql
{
  PersonById(id: "ola") {
    name
    employer { name founded }
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
      "name": "Ola Nordmann",
      "employer": {
        "name": "Globex",
        "founded": "2001-09-09T09:09:09.12+02:00"
      },
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

- A list of links is a connection, with the arguments of pages. Its edges come in the order of the ids, not in
  the order of the links in the document.
- A link to a document that does not exist at the time of the query (never written, written for later, or
  deleted) gives `null` for one link, and is left out of a list read in pages. LDS does not check links when
  they are written; this is how a time machine works, not a fault.
- A link to one document whose field has `!` in the model cannot give `null`: the document that holds the link is
  lost from the answer with the error of [One document](#one-document).

Not finished: when the document does not have the link field at all, the field is `null` and the answer has an
error for it; the rest of the answer is there. Anne has no `employer`:

```json
{
  "errors": [
    {
      "message": "Exception while fetching data (/PersonById/employer) : Cannot invoke \"java.lang. ...",
      "locations": [
        {"line": 1, "column": 33}
      ],
      "path": ["PersonById", "employer"],
      "extensions": {"classification": "DataFetchingException"}
    }
  ],
  "data": {
    "PersonById": {"name": "Anne", "employer": null}
  }
}
```

Tell the user it means "no employer", not that something is broken in their data.

Not finished: with LDS in memory the pages of a list of links are wrong: `friends(first: 1, after: "anne")` gave
`anne` again, and `pageInfo` says `true` for both fields. Read a list of links whole.

Not finished: a link inside an embedded object works for one link only; a list of links there gives `null` and an
error, and its reverse link never gives a document.

## Reverse links

LDS adds to the target of every link a field that lists the documents linking to it: the `reverseName` of the
`@link`, or `reverse` + the entity type + the field (`Person.friends` gives `reversePersonFriends`). Find the
names in the schema LDS made. A reverse link is a connection.

```graphql
{
  CompanyById(id: "acme") {
    name
    employees {
      edges { cursor node { name } }
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
        ]
      }
    }
  }
}
```

Ola is not among them now: from 2022 Ola works at Globex. Forward pages work: `employees(first: 1, after:
"kari")` gave `per` with `hasNextPage` `false`. Reverse links are only in the GraphQL API, and only with a
GraphQL model.

Not finished: with LDS in memory, a write or a delete at the exact valid time of a version that is there can
leave the old link followed backwards, and a query for a reverse link after such deletes once stopped LDS from
answering at all; see https://lds.descoped.io/docs/user/graphql-api/#reverse-links.

## Unions and plain lists of links

These were run on LDS started with this model:

```graphql
type Owner @domain {
  name: String!
  favourite: Pet @link
  pets: [Pet] @link
  cars: [Car] @link(pagination: false)
}

type Cat @domain {
  name: String!
}

type Dog @domain {
  name: String!
}

union Pet = Cat | Dog

type Car @domain {
  plate: String!
}
```

The owner `eva` has `"favourite": "/Cat/tom"`, `"pets": ["/Dog/fido", "/Cat/tom"]` and
`"cars": ["/Car/a1", "/Car/zz", "/Car/b2"]`; `/Car/zz` was never written.

```graphql
{
  OwnerById(id: "eva") {
    cars { plate }
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
      "cars": [
        {"plate": "A1"},
        null,
        {"plate": "B2"}
      ],
      "favourite": {"__typename": "Cat", "name": "Tom"},
      "pets": {
        "edges": [
          {
            "cursor": "fido",
            "node": {"__typename": "Dog", "name": "Fido"}
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

- A list with `@link(pagination: false)` is a plain list in the order of the links, and a missing target is a
  `null` in it.
- A link to a union gives a document of any of its entity types: ask for `__typename` and for the fields of each
  with `... on`. Every entity type of the union gets the reverse link (`DogById(id: "fido") {
  reverseOwnerPets { edges { node { name } } } }` gave Eva).

Not finished: the pages of a list of links to a union are wrong and can differ from one request to the next; read
it without arguments. A list of links to a union with `@link(pagination: false)` is always empty. A document that
comes from a plain list cannot be asked for its connections (they are `null` with an error).

## At a time

Every query reads one point in time: now, or the time in the query parameter `snapshot` (or the variable
`__snapshot`). Every document, link, reverse link and search hit of the request is read at that time.
[time-machine.md](time-machine.md) has the rules and a tour.

```sh
scripts/query.sh ola.graphql '' 2021-06-01T00:00:00Z
```

## Search

Search exists when `Query` has the field `Search`: the key `graphql.search.enabled` is `true` and LDS has a Solr
index (the compose stack of `lds:start` has both). It finds a document by the texts of its fields named
`languageText`, wherever they are in the document, and by nothing else: not a name, a number, a date or the id.
A model meant for search therefore has an embedded type with a `languageText` field (`lds:model`).

| Argument | Meaning |
|---|---|
| `query: String!` | the text, given to Solr as it is: a query in Solr's language |
| `filter: [TypeFilters]` | only these entity types, written without quotes: `filter: [Company]` |
| `first`, `after`, `last`, `before` | the page; the cursors are those of the search |

The result is a union of every entity type, so ask with `... on`. These ran on the compose stack, with Solr; the
companies have the texts "Makes anvils and rockets" (Acme), "Sells rockets" (Globex) and "Writes software for
rockets" (Initech).

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

What Solr did with the query text on the stack:

| `query` | Found |
|---|---|
| `"rockets"`, `"Rockets"` | all three: capital letters do not matter |
| `"rocket"` | nothing: another form of the word is not found |
| `"anvils OR software"` | Initech and Acme |
| `"\"sells rockets\""` (a phrase in quotes) | Globex |
| `"Acme"` | nothing: the name is not a `languageText` field |

- Without `first` and `last` a search gives at most 10 documents. The cursors are places in the result
  (`cWM6MA` is the first), not ids; an id as cursor is refused with `expecting a valid cursor value, got acme`.
- A `filter` value that is not an entity type is refused (`... is not a valid 'TypeFilters' ...`).
- The order is Solr's. There is no count.
- A write is found within about five seconds; a search right after a write may miss it. Documents written while
  search was off are never found.
- Every document of the result is read at the time of the query; one that does not exist then is left out.
- When LDS cannot reach Solr, `Search` is `null` with an error such as `... java.net.ConnectException ...`
  (`lds:operate`).

Not finished: the page after a cursor starts at the cursor, not after it: `Search(query: "rockets", first: 2,
after: "cWM6MQ")` gave `cWM6MQ` (Acme) again, then Initech. Drop the first edge of each next page. `last`
without `before` counts back from the tenth place. Every entity type is searched; `@domain(searchable: false)`
changes nothing.

Not finished: every version of a document is in the index by itself. When Acme had a second version with the
same text, `Search(query: "anvils")` gave Acme twice, and at `snapshot=2022-01-01T00:00:00Z`, before that version,
still twice. A document is also found by a text that only another version of it has, and comes as it is at the
time of the query. Remove duplicates by `__typename` and id on the client; ask for a field that identifies the
document, since the cursor is not the id.

## Scalars

| Scalar | In the answer |
|---|---|
| `String`, `ID` | a text |
| `Int`, `Float` | a number |
| `Boolean` | `true` or `false` |
| `DateTime` | RFC 3339 with the offset it was written with: `"2001-09-09T09:09:09.12+02:00"` |
| `Date` | `"2019-03-11"` |
| `Time` | `"06:30:00+01:00"` |
| an enum | the name of the value as a text |

A `DateTime` is not turned into UTC; the fraction of a second loses its zeros at the end (`.120` comes out as
`.12`; the REST API gives `.120`) and `+00:00` comes out as `Z`. A value that does not fit the type of its field
gives `null` and an error such as `Can't serialize value (/OwnerById/age) : Expected a value that can be
converted to type 'Int' but it was a 'String'`.

## Errors and limits

`200` with `errors` in the answer is the usual way LDS tells what went wrong. An error has `message`,
`locations` in the query text, `path` when a field failed, and `extensions.classification`.

| Case | `message` (start) | `classification` |
|---|---|---|
| Not GraphQL, or empty | `Invalid syntax with offending token '<EOF>' at line 1 column 33` | `InvalidSyntax` |
| A field the schema does not have | `Validation error (FieldUndefined@[PersonById/nmae]) : Field 'nmae' in type 'Person' is undefined` | `ValidationError` |
| A required argument left out | `Validation error (MissingFieldArgument@[PersonById]) : Missing field argument 'id'` | `ValidationError` |
| An argument of the wrong type | `Validation error (WrongType@[Person]) : argument 'first' with value 'StringValue{value='2'}' is not a valid 'Int' ...` | `ValidationError` |
| A mutation | `Validation error (UnknownOperation): The 'Mutation' operation is not supported by the schema` | `ValidationError` |
| Several operations, no `operationName` | `Must provide operation name if query contains multiple operations.` | `ValidationError` |
| No document with the id at the time | `The field at path '/PersonById' was declared as a non null type, ...` | `NullValueInNonNullableField` |
| A negative `first` or `last` | `Exception while fetching data (/Person) : The page size must not be negative: 'first'=-1` | `DataFetchingException` |
| The document lacks the link field | `Exception while fetching data (/PersonById/employer) : ...` | `DataFetchingException` |
| `__type` or `__schema` twice | `This request is not asking for introspection in good faith - Query.__type is present too often!` | `BadFaithIntrospection` |

| Status | When |
|---|---|
| `200` | LDS read the request; see `errors` |
| `400`, text `Unsupported namespace: "graphql"` | no GraphQL API: a JSON Schema model, or `graphql.enabled` is `false` |
| `404` | `/graphql` when `http.prefix` moves the API |
| `405` | a method other than GET, POST, OPTIONS |
| `415` | a POST with another content type, none, or one in capital letters |
| `500`, no body | a request LDS could not read: no `query`, a JSON array or a body that is not JSON, `variables` that is not an object. Not finished: it should be `400`; the reason is only in the log |
| `503` | LDS is stopping |

Limits: at most 15000 tokens, 200000 spaces and line breaks and 1048576 characters in a query; fields at most
100 deep (a link is one level, a list read in pages three: the field, `edges`, `node`); at most 100000 fields;
`__type` and `__schema` once each, and no `fields` inside `fields` in a query for the schema. A query over a
limit is not run. `first` has no limit.

Not finished: a `snapshot` that is not a time is not refused: the query is answered as of now. See
[time-machine.md](time-machine.md#the-rules-of-the-time).
