# Query shapes

Every shape of query the GraphQL API of LDS answers, each with a request that was run on LDS 3.0.1
(`descoped/lds:3.0.1`) and the answer it gave.

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

- A field the document does not have is `null`. LDS keeps no `null` values: a field written as `null` is left
  out of the document, and is `null` here.
- An embedded object (`address`) is an object inside the document; ask for its fields.
- When there is no document with the id at the time of the query (never written, written for a later time, or
  deleted then), `XById` is `null`, with no error. The rest of the answer is there:

```graphql
{
  PersonById(id: "nosuch") { name }
  k: PersonById(id: "kari") { name }
}
```

```json
{
  "data": {
    "PersonById": null,
    "k": {"name": "Kari"}
  }
}
```

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
`pageInfo`. The edges come in the order of the ids, letter by letter as Unicode numbers them (`Z` before `a`,
`~` before `ø`); the cursor of an edge is the id.

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
| `after: "anne", before: "per"` | the documents with an id between the two, without the two (`kari`, `ola`) |
| `first: 0` | no edges, and `false` for both fields of `pageInfo` |
| `first: -1` | the error `The page size must not be negative: 'first'=-1`, and `null` for the field |
| `first: "2"` | refused: `first` is a number |
| `first` and `last` | `last` is ignored |

- A cursor does not have to be an id that exists: `after: "b"` gives the ids after `b`.
- `first` has no upper limit. There is no count of the documents, no `startCursor` or `endCursor`, no argument
  to sort another way, and none to choose documents by the value of a field: filter on the client.

Not finished: reading backwards does not work as it should. `Person(last: 2)` gave `per` then `ola`, the last
id first, and `true` for both fields of `pageInfo`; `Person(before: "ola")` gave `kari` then `anne`. Read forwards
with `first` and `after`.

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
- A document without the link field gives `null`, with no error: Anne has no `employer`, and
  `PersonById(id: "anne") { name employer { name } }` gave `{"name": "Anne", "employer": null}`.
- A link to a document that does not exist at the time of the query (never written, written for later, or
  deleted) gives `null` for one link, and is left out of a list read in pages: a person with
  `"employer": "/Company/nosuch"` and `"friends": ["/Person/nosuch", "/Person/anne"]` gave `"employer": null` and
  the one edge `anne`. LDS does not check links when they are written; this is how a time machine works, not a
  fault.
- A link to one document whose field has `!` in the model cannot give `null`. When its target is not there, the
  document that holds the link is `null` in the answer, with the error `The field at path '/TagById/owner' was
  declared as a non null type, ...`; the rest of the answer is there.

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
leave the old link followed backwards, and a query for a reverse link after such deletes stopped LDS from
answering any read or write (nine persons with one employer, each deleted at the time of its version; `/ping`
still answered); see https://lds.descoped.io/docs/user/graphql-api/#reverse-links.

## Unions and plain lists of links

These were run on LDS in memory, started with this model:

```graphql
type Owner @domain {
  name: String!
  favourite: Pet @link
  pets: [Pet] @link
  cars: [Car] @link(pagination: false)
  animals: [Pet] @link(pagination: false)
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

The owner `eva` has `"favourite": "/Cat/tom"`, `"pets": ["/Dog/fido", "/Cat/tom"]`,
`"cars": ["/Car/a1", "/Car/zz", "/Car/b2"]` and `"animals": ["/Dog/fido", "/Cat/nosuch", "/Cat/tom"]`; `/Car/zz`
and `/Cat/nosuch` were never written.

```graphql
{
  OwnerById(id: "eva") {
    cars { plate }
    animals {
      __typename
      ... on Cat { name }
      ... on Dog { name }
    }
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
        {"plate": "B2"}
      ],
      "animals": [
        {"__typename": "Dog", "name": "Fido"},
        {"__typename": "Cat", "name": "Tom"}
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

- A list with `@link(pagination: false)` is a plain list in the order of the links, and a missing target is
  left out of it. A document of a plain list can be asked for its links and reverse links like any other
  (`cars { plate reverseOwnerCars { edges { cursor } } }` gave `eva` for each car).
- A link to a union gives a document of any of its entity types: ask for `__typename` and for the fields of each
  with `... on`. Every entity type of the union gets the reverse link (`DogById(id: "fido") {
  reverseOwnerPets { edges { node { name } } } }` gave Eva).

Not finished: the pages of a list of links to a union are wrong and can differ from one request to the next
(`pets(first: 1, after: "fido")` gave `fido` once and `tom` twice, and `pets(first: 1)` said `false` for
`hasNextPage`); read it without arguments, or as a plain list.

## At a time

Every query reads one point in time: now, or the time in the query parameter `snapshot` (or the variable
`__snapshot`). Every document, link, reverse link and search hit of the request is read at that time.
[time-machine.md](time-machine.md) has the rules and a tour.

```sh
scripts/query.sh ola.graphql '' 2021-06-01T00:00:00Z
```

## Search

Search exists when `Query` has the field `Search`: the key `graphql.search.enabled` is `true` and LDS has a Solr
index (the compose stack of `lds:start` has both). It finds a document by every text in it, in any field,
whatever its name: a name, a city, a `languageText`. It does not look at links, dates, times, numbers, `true` and
`false`, or the id. An entity type marked `@domain(searchable: false)` in the model is never found.

- A text in an object that has `languageCode` and `languageText` is searched in that language when the code is
  `nb`, `nn` or `no` (Norwegian), `sv`, `en`, `de` or `fr` (any case, also with a region: `nb-NO`): another form of
  a word finds it (`rocket` finds "rockets", `selling` finds "Sells"). Every other text is searched word for word.
- A word is also found when it is typed without its special letters (`boker` finds "bøker", `Aase` the person
  Åse); the word as it is written ranks first.
- Capital letters do not matter.

| Argument | Meaning |
|---|---|
| `query: String!` | the words |
| `filter: [TypeFilters]` | only these entity types, written without quotes: `filter: [Company]` |
| `history: Boolean` | `true`: every version that has the words, not only the version at the time of the query |
| `first`, `after`, `last`, `before` | the page; the cursors are those of the search |

The result is a union of every entity type, so ask with `... on`. These ran on the compose stack, with Solr; the
companies have the texts "Makes anvils and rockets" (Acme), "Sells rockets" (Globex) and "Writes software for
rockets" (Initech), each with `"languageCode": "en"`.

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

What the words found on the stack:

| `query` | Found |
|---|---|
| `"rockets"`, `"Rockets"`, `"rocket"` | all three |
| `"anvils software"`, `"anvils OR software"` | Acme and Initech: a document with any of the words |
| `"\"sells rockets\""` (a phrase in quotes) | Globex; `"\"rockets sells\""` found nothing |
| `"-rockets"` | the documents without the word (here every person) |
| `"Acme"` | Acme, by its `name` |
| `"Oslo"` | the two persons whose `address` has the city Oslo |
| `"1999"` | nothing: `founded` is a date, not a text |
| `""`, `"   "` | nothing, and no error |

- The words are never refused: an open quote, a bracket, a `\` or an operator alone (`OR`) is read as words, and
  found what those words found, or nothing.
- Without `first` and `last` a search gives at most 10 documents; `first` and `last` give at most 100. The cursors
  are places in the result (`cWM6MA` is the first), not ids; an id as cursor is refused with
  `expecting a valid cursor value, got acme`.
- The page after a cursor starts after it: `Search(query: "rockets", first: 2, after: "cWM6MA")` gave Acme and
  Initech, with `hasPreviousPage` `true`. `last: 1` gave the last place (Initech), `last: 2, before: "cWM6Mg"`
  Globex and Acme.
- A `filter` value that is not an entity type is refused (`... is not a valid 'TypeFilters' ...`).
- The order is Solr's: the best match first. There is no count.
- A write is found within about a second; a search right after a write may miss it. A write sent with
  `?searchable=true` is answered when search finds it (`lds:data`).
- Documents written while search was off are found after the index is rebuilt (`lds:operate`).
- When LDS cannot reach Solr, `Search` is `null` with an error `Exception while fetching data (/Search) : ...`
  (`lds:operate`).

A search is at the time of the query, as every read is: of each document the version valid then is tested for the
words, and a hit is read at that version. A company written with
`{"languageCode": "nb", "languageText": "Vi selger bøker og gaver"}` was found by `boker`, `bok` and `gave`. After a
later version with "Vi selger bøker", `gaver` found nothing at a time after it, and found the earlier version with
`history: true`. `Search(query: "bøker", history: true)` gave both versions, the newest first, each with its own
text. Without `history` a document comes once.

## Scalars

| Scalar | In the answer |
|---|---|
| `String`, `ID` | a text |
| `Int`, `Long`, `Float` | a number |
| `Boolean` | `true` or `false` |
| `DateTime` | RFC 3339 with the offset it was written with: `"2001-09-09T09:09:09.12+02:00"` |
| `Date` | `"2020-01-01"` |
| `Time` | `"10:00:00+01:00"` |
| an enum | the name of the value as a text |

A `DateTime` is not turned into UTC; the fraction of a second loses its zeros at the end (`.120` comes out as
`.12`; the REST API gives `.120`) and `+00:00` comes out as `Z`. `Long` is a whole number of 64 bits
(`9223372036854775807` came back as written), `Int` one of 32 bits. A value that does not fit the type of its
field is refused when it is written (`lds:data`), so a query gives back what was written.

## Errors and limits

`200` with `errors` in the answer is the usual way LDS tells what went wrong. An error has `message`,
`locations` in the query text, `path` when a field failed, and `extensions.classification`.

| Case | `message` (start) | `classification` |
|---|---|---|
| Not GraphQL | `Invalid syntax with offending token '<EOF>' at line 1 column 32` | `InvalidSyntax` |
| A field the schema does not have | `Validation error (FieldUndefined@[PersonById/nmae]) : Field 'nmae' in type 'Person' is undefined` | `ValidationError` |
| A required argument left out | `Validation error (MissingFieldArgument@[PersonById]) : Missing field argument 'id'` | `ValidationError` |
| An argument of the wrong type | `Validation error (WrongType@[Person]) : argument 'first' with value 'StringValue{value='2'}' is not a valid 'Int' ...` | `ValidationError` |
| A mutation | `Validation error (UnknownOperation): The 'Mutation' operation is not supported by the schema` | `ValidationError` |
| Several operations, no `operationName` | `Must provide operation name if query contains multiple operations.` | `ValidationError` |
| A `snapshot` that is not a time | `The query parameter snapshot 'yesterday' is not an ISO-8601 time. ...` | `ValidationError` |
| A negative `first` or `last` | `Exception while fetching data (/Person) : The page size must not be negative: 'first'=-1` | `DataFetchingException` |
| A link with `!` whose target is not there | `The field at path '/TagById/owner' was declared as a non null type, ...` | `NullValueInNonNullableField` |
| `__type` or `__schema` twice | `This request is not asking for introspection in good faith - Query.__type is present too often!` | `BadFaithIntrospection` |
| Fields too deep | `Query depth 101 exceeds maximum allowed depth 100` | `ValidationError` |
| Too long | `More than 15,000 'grammar' tokens have been presented. ...` | `InvalidSyntax` |

| Status | When |
|---|---|
| `200` | LDS read the request; see `errors` |
| `400`, `{"errors":[{"message": ...}]}` | LDS could not read the request: `The body is empty`, `The body has no "query" text`, `The body is not a JSON object with the field "query"`, `The body cannot be read as JSON at line 1, column 1: ...`, `The variables are not a JSON object`, and for a GET `The request has no "query" parameter` or `more than one "query" parameter` |
| `400`, text `Unsupported namespace: "graphql"` | no GraphQL API: a JSON Schema model, or `graphql.enabled` is `false` |
| `404` | `/graphql` when `http.prefix` moves the API |
| `405` | a method other than GET, POST, OPTIONS |
| `415`, `{"errors":[{"message": "Unsupported Content-Type: ..."}]}` | a POST with another content type, none, or one in capital letters |
| `500`, no body | the query failed in a way LDS did not foresee; the reason is in the log |
| `503` | LDS is stopping |

Limits: at most 15000 tokens, 200000 spaces and line breaks and 1048576 characters in a query; fields at most
100 deep (a link is one level, a list read in pages three: the field, `edges`, `node`); at most 100000 fields;
`__type` and `__schema` once each, and no `fields` inside `fields` in a query for the schema. A query over a
limit is not run. `first` has no limit in a list of documents; in a search it is at most 100.
