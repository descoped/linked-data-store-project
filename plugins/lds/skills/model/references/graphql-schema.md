# Writing the model as a GraphQL schema

Contents: [The file](#the-file) · [Names](#names) · [Fields](#fields) · [Lists](#lists) · [Enums](#enums) ·
[Embedded types](#embedded-types) · [Links](#links) · [Reverse links](#reverse-links) ·
[Descriptions](#descriptions) · [Search](#search) · [A whole example](#a-whole-example)

Every rule here was run against `descoped/lds:3.0.1`. The shapes that break are collected, with their messages, in
[checklist.md](checklist.md). Depth: [The model](https://lds.descoped.io/docs/user/model/#a-model-as-a-graphql-schema).

## The file

One file, `schema.graphql`. Beside the types of the domain it must have three things, or LDS ends at start:

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

type Query {
}

scalar DateTime
```

- The two `directive` lines, as written. LDS does not add them, and an argument not declared is refused.
- A type `Query`. LDS makes its own queries and does not use its fields; an empty `Query` is accepted.
- A `scalar` line for each of `DateTime`, `Date`, `Time` and `Long` the file uses. The other scalars LDS knows are
  `String`, `Boolean`, `Int`, `Float` and `ID`, which are not declared. Any other scalar (`JSON`, `BigDecimal`) is
  refused.

## Names

- An entity type is named as it is written, and that name is used in every address and in GraphQL:
  `type Member @domain` gives `/ns/Member/{id}`, `MemberById` and `Member`. Use one capitalised singular noun
  (`PurchaseOrder`, not `purchase_orders`).
- Do not name a type `PageInfo`, `SearchResult` or `TypeFilters`, or anything ending in `Connection`, or an entity
  type's name followed by `Edge`: LDS makes types of these names for GraphQL. Such a model ends at start, or, with
  `PageInfo`, starts and gives pages without `hasNextPage`.
- Field names: letters, digits and `_`, not starting with a digit or `__`. No dots and no `[n]` at the end: such
  names do not come back from LDS as written. Do not start one with `_link_property_`, the name LDS uses for links.
- Keep field names as the user's data has them (`favouriteGenres`, `postal_code`); a document must use exactly
  these names.

## Fields

| Field | Value in a document | Checked on write | Notes |
|---|---|---|---|
| `String` | a text | yes | |
| `Boolean` | `true` or `false` | yes | |
| `Int` | a whole number from -2147483648 to 2147483647 | yes | `3000000000` is refused: `$.visits: must have a maximum value of 2147483647`; so is `1.5`. |
| `Long` | a whole number of 64 bits, up to 9223372036854775807 | yes | Declare it: `scalar Long`. For a `BIGINT` and any count that can pass two billion. |
| `Float` | a number | yes | A decimal with more digits than a 64-bit floating point number holds comes back changed. |
| `DateTime` | `2026-11-20T10:00:00Z`, `2026-11-20T10:00:00+01:00` | yes | A time zone is required; `2026-11-20` and `2026-11-20T10:00:00` are refused. |
| `Date` | `2026-11-20` | yes | A date-time is refused: `$.joined: does not match the date pattern must be a valid RFC 3339 full-date`. |
| `Time` | `10:00:00Z`, `10:00:00+01:00` | yes | A time zone is required: `10:00:00` is refused. |
| an enum: `status: MemberStatus` | one of its names, as a text: `"ACTIVE"` | yes | One field per enum in a type: see [Enums](#enums). |
| `ID` | a text or a number | no | |
| `[String]`, `[Int]`, `[Date]`, other scalar lists | an array | yes, each item by its type | One list type per type: see [Lists](#lists). |
| a list of an embedded type: `[Address]` | an array of objects | yes, each by its fields | |
| a single embedded type: `address: Address` | an object | no | Not checked at all; see [Embedded types](#embedded-types). |
| an entity type with `@link` | a link | yes | See [Links](#links). |

A value that does not fit is refused with `400 Schema validation error: [...]`, each fault as its place and what is
wrong: `$.counts[1]: string found, integer expected`. What is not checked (an `ID`, a single embedded object, the
fields listed in [checklist.md](checklist.md)) is stored as it comes, and a wrong value is found only when GraphQL
reads it and answers it with `null` and an error. Tell the user which of their fields are not checked, and that the
program that writes must get them right; JSON Schema files check them too ([json-schema.md](json-schema.md)).

**Required.** `!` after the type (`name: String!`) means the field must be in every document. A field without
`!` may be left out.

**`null`.** A field whose value is `null` is taken as a field that is not there, at any depth: `"email": null` is
written as a document without `email`, and read back without it. So `null` in an optional field is accepted, and in
a required one is refused as missing (`$: required property 'name' not found`). A `null` item of an array is kept,
and refused where the items are checked (`$.counts[1]: null found, integer expected`).

**Fields not in the model** are accepted, stored and given back by REST, but GraphQL cannot reach them, and they
cannot be addressed as a part of the document. A GraphQL schema cannot refuse them.

## Lists

LDS reads a list type only once per type, by the order of the field names, not the order in the file. With
`subjects: [String]` and `keywords: [String]` in one type, `keywords` (first by name) is a list and `subjects`
must be a single text: `"subjects": ["x"]` is refused with `$.subjects: array found, string expected`.

The four spellings of a list of texts are four list types, and each is checked as a list of texts:

```graphql
type Book @domain {
  keywords: [String]
  subjects: [String!]
  genres: [String]!
  audiences: [String!]!
}
```

`[String]!` and `[String!]!` also make the field required. So a type can have two optional lists of texts
(`[String]` and `[String!]`) and two required ones. For more, move lists into an embedded type of their own, or
make the values an embedded type with one field. The same holds for any other list type used twice in one type
(two `[Int]`).

A list of lists, `[[String]]`, must be an array; its items are not checked. Lists of links are not touched by this
rule: a type can have any number of `[X] @link` fields.

## Enums

An enum lists the names a field may hold:

```graphql
enum MemberStatus {
  ACTIVE
  SUSPENDED
}
```

- One field per enum in a type. With `earlier: State` and `state: State`, only the first by name is checked and the
  other takes any value. For a second field, declare a second enum with the same names
  (`enum PreviousState { ... }`); both are then checked.
- A list of an enum (`states: [State]`) must be an array, and its items are not checked: `["Z", 7]` is written,
  and GraphQL answers such an item with `null` and an error. Tell the user.
- The names are written in documents as they are in the enum, case and all (`"ACTIVE"`, or `"active"` when the
  enum says `active`).

## Embedded types

A type without `@domain` is embedded: its values are objects inside the document, with no id or versions of their
own, written and read with the document.

- **A list of an embedded type** (`addresses: [Address]`) is checked: each object by the fields of the type, with
  `!` and all. Use a list when the part must be checked, even when there is usually one.
- **A single embedded field** (`address: Address`) is not checked at all, in a GraphQL schema: `"address": 7` is
  written. It is read whole at `/ns/Publisher/ace/address`, but no field under it can be addressed
  (`/ns/Publisher/ace/address/city` is answered `400 Not a valid path element: "city"`).
- **Two lists of one embedded type in one type** (`chapters: [Chapter]` and `appendices: [Chapter]`): only the first
  by name is checked. Give the second a type of its own (`appendices: [Appendix]`).
- **An embedded type may not hold itself**, also not through a list (`type Branch { branches: [Branch] }`): LDS ends
  at start with `java.lang.StackOverflowError`. For a tree, make the node an entity type and link it
  (`parent: Category @link`, `children: [Category] @link`).
- **One embedded type may be used in several entity types** (`Address` in `Publisher` and `Member`); each is checked.
- An element of a list cannot be addressed by a path: the list is read and written whole.

## Links

```graphql
type Book @domain {
  publisher: Publisher @link(reverseName: "books")
  authors: [Author] @link(reverseName: "books")
  rights: RightsHolder @link
}

union RightsHolder = Author | Publisher
```

| Field | Value in a document |
|---|---|
| `publisher: Publisher @link` | `"/Publisher/ace"` |
| `authors: [Author] @link` | `["/Author/le-guin", "/Author/tolkien"]`; an empty array is valid |
| `rights: RightsHolder @link`, a union of entity types | `"/Author/le-guin"` or `"/Publisher/ace"` |

- A link is `/`, the entity type, `/`, the id. No namespace: `/ns/Author/x` is refused, and so is `x`.
- LDS checks the form and the entity type (`Illegal linked-domain: "Author". Navigation: publisher: "/Author/x"`),
  not that the document is there.
- Write `@link` on every field whose type is an entity type or a union of entity types. Without it the field is still
  read as a link for checking, but it gets no reverse link.
- Write a list of links as `[X] @link` or `[X]! @link`, never `[X!]` or `[X!]!`: when `X` has a link of its own, such
  a field is no longer a list of links and every array is refused.
- `!` on a single link (`member: Member! @link`) is fine: the link must be there.
- A type may link to itself (`friends: [Person] @link`, `parent: Category @link`).
- A field may have any number of links to one entity type (`author` and `illustrator`, both `Person @link`).
- Do not write `@link` on a field whose type is an embedded type: it then wants a text `/Cover/x` that points at
  nothing.
- Do not link to an interface (`friends: [Named] @link`): LDS ends at start. Link to a union of the entity types.
- A link inside an embedded type works forward in GraphQL. In a list of embedded objects it is not checked on write,
  and its reverse link was answered empty.
- `pagination: false` makes a list of links a plain list in GraphQL, without pages (`lds:query`).

## Reverse links

For every link LDS adds a field to the entity type it points at, in GraphQL only: the documents that link to this
one, at the time of the query. It is not stored and not in REST.

- `reverseName: "loans"` names it. Without it the name is `reverse` + the linking entity type + the field, each
  capitalised: `authors` of `Book` gives `reverseBookAuthors` on `Author`. Suggest a `reverseName` for every link the
  user will ask from the other end.
- Pick a name the target entity type does not have as a field. LDS does not check it: `reverseName: "name"` on a
  link to `Publisher` hides the publisher's own `name` in GraphQL.
- Two links to different entity types may share a reverse name (`books` on `Author` and on `Publisher`).
- Do not write `@reverseLink` in a model: LDS adds it, and a model that declares and uses it ends at start.

## Descriptions

A text in triple quotes above a type or field is its description, given in GraphQL and in `?schema`. A `#` comment
right above a type or field is taken as its description too, so put file comments at the top, away from types. In
`?schema` a field that holds one link shows the description of the entity type it links to, not its own.

## Search

Search finds a document by every text in it, anywhere in it: texts, enum names, texts of embedded objects, and texts
in fields the model does not have. Links, dates, times, numbers and `true`/`false` are not searched. It needs a
GraphQL schema, search turned on and Solr running (`lds:start`, `lds:query`). A text is found word for word; a text
in a language is found by the stem of its words when it is in this form, the one the example model of LDS uses:

```graphql
"""
A text in one language
"""
type Text {
  languageCode: String
  languageText: String!
}
```

with a field such as `summary: [Text]`. The `languageCode` names the language: `nb`, `nn` or `no` (Norwegian), `sv`,
`en`, `de` or `fr`, in any case, also with a region (`nb-NO`); with another code, or none, the text is found word for
word. In the example below `learning` and `wizards` find the book by its English summary (`A young wizard learns`),
`New York` finds the publisher by its address, `GOOD` the copy by its condition; `183` (a number) and `1968` (a date)
find nothing.

`@domain(searchable: false)` keeps the documents of an entity type out of search. Such a type is not in the union of
search results nor among its filters, so a search query cannot ask for it (`... on Loan` is an error).

## A whole example

A lending library. LDS read it, a document of each entity type was written and read back, the reverse links
answered, and search found the books, authors, publishers and copies by their texts and never a member or a loan.

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
A book: one title, of which the library may hold several copies
"""
type Book @domain {
  title: String!
  isbn: String
  published: Date
  pages: Int
  subjects: [String]
  summary: [Text]
  authors: [Author] @link(reverseName: "books")
  publisher: Publisher @link(reverseName: "books")
}

"""
A copy of a book on the shelf
"""
type Copy @domain {
  barcode: String!
  book: Book! @link(reverseName: "copies")
  condition: Condition
  shelf: String
}

enum Condition {
  NEW
  GOOD
  WORN
  DAMAGED
}

"""
An author
"""
type Author @domain {
  name: String!
  born: Date
}

"""
A publisher
"""
type Publisher @domain {
  name: String!
  address: Address
}

"""
A member of the library
"""
type Member @domain(searchable: false) {
  name: String!
  email: String
  joined: DateTime!
  status: MemberStatus!
  addresses: [Address]
}

enum MemberStatus {
  ACTIVE
  SUSPENDED
}

"""
A loan of one copy to one member
"""
type Loan @domain(searchable: false) {
  copy: Copy! @link(reverseName: "loans")
  member: Member! @link(reverseName: "loans")
  lent: DateTime!
  due: DateTime!
  returned: DateTime
  renewals: [Renewal]
}

type Renewal {
  renewed: DateTime!
  due: DateTime!
}

type Address {
  street: String
  postalCode: String
  city: String!
}

"""
A text in one language
"""
type Text {
  languageCode: String
  languageText: String!
}

type Query {
}

scalar Date
scalar DateTime
```

`Publisher.address` is a single embedded object and is not checked; `Member.addresses` is a list and is. Members and
loans are kept out of search. Documents that fit, written in this order (any order works, since links are not
checked for existence):

```sh
curl -X PUT 'http://localhost:9090/ns/Author/le-guin?sync=true' -H 'Content-Type: application/json' \
     -d '{"name": "Ursula K. Le Guin", "born": "1929-10-21"}'
curl -X PUT 'http://localhost:9090/ns/Book/earthsea?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "title": "A Wizard of Earthsea",
       "isbn": "978-0-553-26250-0",
       "published": "1968-11-01",
       "pages": 183,
       "subjects": ["fantasy", "magic"],
       "summary": [
         {"languageCode": "en", "languageText": "A young wizard learns the price of power."}
       ],
       "authors": ["/Author/le-guin"],
       "publisher": "/Publisher/ace"
     }'
curl -X PUT 'http://localhost:9090/ns/Publisher/ace?sync=true' -H 'Content-Type: application/json' \
     -d '{"name": "Ace Books", "address": {"street": "Madison Avenue 200", "city": "New York"}}'
curl -X PUT 'http://localhost:9090/ns/Copy/c-0001?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "barcode": "0001",
       "book": "/Book/earthsea",
       "condition": "GOOD",
       "shelf": "F-12"
     }'
curl -X PUT 'http://localhost:9090/ns/Member/m-17?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "name": "Kari Nordmann",
       "email": "kari@example.org",
       "joined": "2024-03-01T00:00:00Z",
       "status": "ACTIVE",
       "addresses": [
         {"street": "Storgata 1", "city": "Oslo"}
       ]
     }'
curl -X PUT 'http://localhost:9090/ns/Loan/l-1?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "copy": "/Copy/c-0001",
       "member": "/Member/m-17",
       "lent": "2026-11-01T12:00:00Z",
       "due": "2026-11-22T12:00:00Z",
       "renewals": [
         {"renewed": "2026-11-20T09:00:00Z", "due": "2026-12-06T12:00:00Z"}
       ]
     }'
```

Each was answered `201` with `{"saga-execution-id": "..."}`. A member written with `"joined": "2026-11-01"`, the
status `GONE` and an address without a city was answered:

```
HTTP/1.1 400 Bad Request

Schema validation error: [$.addresses[0]: required property 'city' not found, $.joined: does not match the
date-time pattern must be a valid RFC 3339 date-time, $.status: does not have a value in the enumeration
["ACTIVE", "SUSPENDED"]]
```

A book with `"pages": "many"` and `"published": "1968"` was answered:

```
HTTP/1.1 400 Bad Request

Schema validation error: [$.pages: string found, integer expected, $.published: does not match the date pattern
must be a valid RFC 3339 full-date]
```
