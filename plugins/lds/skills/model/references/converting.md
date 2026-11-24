# From what the user has to a model

Contents: [The rules for every source](#the-rules-for-every-source) · [Types](#types) ·
[Sample JSON](#sample-json) · [CSV](#csv) · [SQL DDL](#sql-ddl) · [OpenAPI](#openapi) ·
[After converting](#after-converting)

Each example below was converted as shown, read by `descoped/lds:3.0.1`, and its documents written and queried.
The examples are small and come from different domains; the user's own domain is the normal case.

## The rules for every source

1. **What has identity becomes an entity type**: a table with a primary key that other tables point at, a JSON
   object with an `id` that is referred to elsewhere, an OpenAPI schema with a path of its own (`/pets/{petId}`).
2. **What only lives inside one other thing becomes an embedded type**: a child table whose rows belong to one
   parent and are never pointed at (order lines, addresses), a nested JSON object without an id, an OpenAPI schema
   used only inside another.
3. **A reference by id becomes a link**: a foreign key column `customer_id`, a JSON field `"venueId": "v-7"` or a
   nested object that is a copy of another thing. Name the field for what it points at (`customer`, `venue`), and
   its value becomes `/Customer/<id>`.
4. **A join table** with only two foreign keys becomes a list of links on one side; one with columns of its own (a
   loan with dates) becomes an entity type with two links.
5. **The key becomes the id** in the address, not a field. A composite key becomes one text (`1001-1`), or the row
   becomes an embedded object of its parent.
6. **History becomes versions, not fields**: drop `created_at`, `updated_at`, `valid_from`, `valid_to`,
   `version`, `deleted` and audit tables; when the data is loaded, `created_at` or `valid_from` becomes the
   `timestamp` of the write (`lds:data`). Keep dates that mean something in the domain.
7. **Check the two-of-a-kind rules** of [checklist.md](checklist.md#starts-and-does-not-do-what-the-model-says) as
   you go: two `[String]` fields, two fields of one enum or two lists of one embedded type in one type each need a
   change.
8. **Ask** where the source cannot tell: whether a nested object is a part or a copy of a thing of its own, which
   fields are required, which texts are searched.

## Types

| Source | GraphQL schema | JSON Schema file |
|---|---|---|
| `VARCHAR`, `TEXT`, `CHAR`, `UUID`; a JSON text; `type: string` | `String` | `{"type": "string"}` |
| `BOOLEAN`; `true`/`false` | `Boolean` | `{"type": "boolean"}` |
| `TIMESTAMPTZ`; a date-time with a zone; `format: date-time` | `DateTime` | `{"type": "string", "format": "date-time"}` |
| `TIMESTAMP` without a zone; a date-time without one | `DateTime`; the values need a zone (`Z`) when loaded, or they are refused | the same |
| `DATE`; `2026-11-20`; `format: date` | `Date` | `{"type": "string", "format": "date"}` |
| `TIME`; `format: time` | `Time`, values with a zone: `10:00:00Z` | `{"type": "string", "format": "time"}` |
| `SMALLINT`, `INTEGER`; whole numbers | `Int` (from -2147483648 to 2147483647) | `{"type": "integer"}` |
| `BIGINT` | `Long` (64 bits; declare `scalar Long`) | `{"type": "integer"}` |
| `NUMERIC`, `DECIMAL`, `REAL`, `DOUBLE`; decimals | `Float`; `String` for amounts with more digits than a double holds | `{"type": "number"}` |
| `ENUM`, `CHECK (x IN (...))`; `enum`; a CSV column with a few repeated values | an enum, when every value is a GraphQL name (letters, digits, `_`; not `in-progress`), else `String` | `{"type": "string", "enum": [...]}` |
| an `ARRAY` column; a JSON array; `a;b` in a CSV cell; columns `tag1`, `tag2` | a list: `[String]`, `[Address]` | `{"type": "array", "items": ...}` |
| `JSONB` with a known shape; a nested JSON object | an embedded type | a definition in `$defs` |
| `BYTEA`, `BLOB`, files | not a fit: keep the file elsewhere and its address or key as a `String` | the same |
| `NOT NULL`; `required` | `!` | `"required": [...]` |
| `PRIMARY KEY` | the id in the address | the same |
| `FOREIGN KEY ... REFERENCES t` | `field: T @link(reverseName: "...")` | `_link_property_field` |
| `UNIQUE`, `DEFAULT`, indexes, triggers | cannot be said | cannot be said |
| `CHECK (price >= 0)`, `LIKE`, lengths | cannot be said | `minimum`, `pattern`, `minLength`, `maxLength` |

Tell the user which of their checks will not be in the model, so that the program that writes keeps them.

## Sample JSON

The user's sample:

```json
{
  "id": "evt-42",
  "title": "Jazz night",
  "startsAt": "2026-12-05T19:00:00+01:00",
  "cancelled": false,
  "venue": {"id": "v-7", "name": "Blue Hall", "city": "Oslo"},
  "performers": [
    {"id": "p-1", "name": "Ada Trio"}
  ],
  "tickets": [
    {"kind": "standard", "price": 250},
    {"kind": "vip", "price": 600}
  ],
  "tags": ["jazz", "live"]
}
```

Read it as: the event has an id, so it is an entity type. `venue` and `performers` have ids of their own and other
events use the same ones: entity types, and links from the event. `tickets` have no id and belong to this event:
an embedded type. `tags`: a list of texts. Ask the user whether `kind` is a fixed set.

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
An event
"""
type Event @domain {
  title: String!
  startsAt: DateTime!
  cancelled: Boolean
  venue: Venue @link(reverseName: "events")
  performers: [Performer] @link(reverseName: "events")
  tickets: [Ticket]
  tags: [String]
}

"""
A venue
"""
type Venue @domain {
  name: String!
  city: String
}

"""
A performer
"""
type Performer @domain {
  name: String!
}

type Ticket {
  kind: TicketKind!
  price: Float
}

enum TicketKind {
  standard
  vip
}

type Query {
}

scalar DateTime
```

The sample becomes three documents, the ids in the addresses and the nested things replaced by links:

```sh
curl -X PUT 'http://localhost:9090/ns/Venue/v-7?sync=true' -H 'Content-Type: application/json' \
     -d '{"name": "Blue Hall", "city": "Oslo"}'
curl -X PUT 'http://localhost:9090/ns/Performer/p-1?sync=true' -H 'Content-Type: application/json' \
     -d '{"name": "Ada Trio"}'
curl -X PUT 'http://localhost:9090/ns/Event/evt-42?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "title": "Jazz night",
       "startsAt": "2026-12-05T19:00:00+01:00",
       "cancelled": false,
       "venue": "/Venue/v-7",
       "performers": ["/Performer/p-1"],
       "tickets": [
         {"kind": "standard", "price": 250},
         {"kind": "vip", "price": 600}
       ],
       "tags": ["jazz", "live"]
     }'
```

All three were answered `201`, and GraphQL followed the links both ways (`VenueById(id: "v-7") { events { ... } }`
gave the event).

With several samples, merge them: a field in some samples only is optional; a field with `null` in a sample is
optional (LDS writes a field that is `null` as a field that is not there).

## CSV

The header and a row:

```
member_no,name,email,joined,status,city,postcode,favourite_genres
M-17,Kari Nordmann,kari@example.org,2024-03-01,active,Oslo,0150,fantasy;crime
```

Read it as: one entity type per file, one document per row. `member_no` is the id. Columns that belong together
(`city`, `postcode`) become an embedded type. A cell with several values becomes a list. A column that holds the
key of a row in another file becomes a link. Look at the values for the types: `joined` is a date only,
`postcode` keeps its leading zero, so it is a text, and `status` has a few repeated values.

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
A member
"""
type Member @domain {
  name: String!
  email: String
  joined: Date
  status: MemberStatus
  address: Address
  favouriteGenres: [String]
}

enum MemberStatus {
  active
  suspended
}

type Address {
  city: String
  postcode: String
}

type Query {
}

scalar Date
```

The row as a document:

```sh
curl -X PUT 'http://localhost:9090/ns/Member/M-17?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "name": "Kari Nordmann",
       "email": "kari@example.org",
       "joined": "2024-03-01",
       "status": "active",
       "address": {"city": "Oslo", "postcode": "0150"},
       "favouriteGenres": ["fantasy", "crime"]
     }'
```

Answered `201`; `"joined": "2024-03-01T00:00:00Z"` was refused, as not a date. Here `address` (a single embedded
object) is not checked; say so, and offer `addresses: [Address]` or JSON Schema files if that matters. Turning every
row into a request is `lds:data`.

## SQL DDL

```sql
CREATE TABLE customer (
  id          VARCHAR(20) PRIMARY KEY,
  name        VARCHAR(200) NOT NULL,
  email       VARCHAR(200) UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE product (
  sku         VARCHAR(40) PRIMARY KEY,
  title       TEXT NOT NULL,
  price       NUMERIC(10,2) NOT NULL CHECK (price >= 0),
  status      VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED'))
);
CREATE TABLE product_tag (
  product_sku VARCHAR(40) REFERENCES product(sku),
  tag         VARCHAR(40),
  PRIMARY KEY (product_sku, tag)
);
CREATE TABLE purchase_order (
  id          BIGINT PRIMARY KEY,
  customer_id VARCHAR(20) NOT NULL REFERENCES customer(id),
  placed_at   TIMESTAMPTZ NOT NULL,
  updated_at  TIMESTAMPTZ
);
CREATE TABLE order_line (
  order_id    BIGINT REFERENCES purchase_order(id),
  line_no     INT,
  product_sku VARCHAR(40) NOT NULL REFERENCES product(sku),
  quantity    INT NOT NULL,
  PRIMARY KEY (order_id, line_no)
);
```

Read it as: `customer`, `product` and `purchase_order` are entity types, their keys the ids. `product_tag` holds
only a key and a text: a list of texts on the product. `order_line` belongs to one order and nothing points at it:
an embedded type, a list on the order. `customer_id` and `product_sku` are links. `created_at` becomes the time of
the customer's first version, `updated_at` goes. `UNIQUE` on `email` and `CHECK (price >= 0)` cannot be said.

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
A customer
"""
type Customer @domain {
  name: String!
  email: String
}

"""
A product for sale
"""
type Product @domain {
  title: String!
  price: Float!
  status: ProductStatus!
  tags: [String]
}

enum ProductStatus {
  ACTIVE
  RETIRED
}

"""
An order placed by a customer
"""
type PurchaseOrder @domain {
  customer: Customer! @link(reverseName: "orders")
  placedAt: DateTime!
  lines: [OrderLine]
}

type OrderLine {
  lineNo: Int!
  product: Product! @link
  quantity: Int!
}

type Query {
}

scalar DateTime
```

Rows as documents, the customer at the time of its `created_at`:

```sh
curl -X PUT 'http://localhost:9090/ns/Customer/C-0042?sync=true&timestamp=2024-05-02T08:15:00Z' \
     -H 'Content-Type: application/json' -d '{"name": "Nordlys AS", "email": "post@nordlys.example"}'
curl -X PUT 'http://localhost:9090/ns/Product/BK-0001?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "title": "A Wizard of Earthsea",
       "price": 129.5,
       "status": "ACTIVE",
       "tags": ["fantasy", "classic"]
     }'
curl -X PUT 'http://localhost:9090/ns/PurchaseOrder/1001?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "customer": "/Customer/C-0042",
       "placedAt": "2026-11-20T10:00:00Z",
       "lines": [
         {"lineNo": 1, "product": "/Product/BK-0001", "quantity": 2}
       ]
     }'
```

Each was answered `201`; an order line without `product` was refused with
`$.lines[0]: required property 'product' not found`. GraphQL followed `customer` and each line's `product`, and a
customer's `orders` gave the order. The link from an order line to a product is in a list of embedded objects: it
is not checked on write, and a reverse link from the product was answered empty. When the user needs "which orders
hold this product", make `OrderLine` an entity type with links to its order and its product.

## OpenAPI

```yaml
paths:
  /pets/{petId}: ...
  /owners/{ownerId}: ...
components:
  schemas:
    Pet:
      type: object
      required: [name]
      properties:
        id: {type: string}
        name: {type: string}
        species: {type: string, enum: [dog, cat]}
        birthDate: {type: string, format: date}
        owner: {$ref: '#/components/schemas/Owner'}
        vaccinations:
          type: array
          items: {$ref: '#/components/schemas/Vaccination'}
    Owner:
      type: object
      required: [name]
      properties:
        id: {type: string}
        name: {type: string}
        phone: {type: string}
    Vaccination:
      type: object
      required: [vaccine]
      properties:
        vaccine: {type: string}
        given: {type: string, format: date-time}
```

Read it as: `Pet` and `Owner` have paths of their own: entity types. `Vaccination` is only used inside `Pet`: an
embedded type. The `$ref` to `Owner` is a link, the `$ref` to `Vaccination` a part. `id` is the id in the address.
`oneOf` of several entity types becomes a link to a union; `allOf` that extends a base schema becomes the fields of
both written out in one type (do not use an interface as the type of a field). A map (`additionalProperties` with
a schema) has no form in a model: make it a list of an embedded type with a `key` field.

```graphql
directive @domain(searchable: Boolean) on OBJECT
directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION

"""
A pet
"""
type Pet @domain {
  name: String!
  species: Species
  birthDate: Date
  owner: Owner @link(reverseName: "pets")
  vaccinations: [Vaccination]
}

enum Species {
  dog
  cat
}

type Vaccination {
  vaccine: String!
  given: DateTime
}

"""
An owner of pets
"""
type Owner @domain {
  name: String!
  phone: String
}

type Query {
}

scalar Date
scalar DateTime
```

A pet written with `"owner": "/Owner/o-1"` was answered `201`, and `OwnerById(id: "o-1") { pets { ... } }` gave it.
The same as JSON Schema files, which also check the single embedded objects a model may have, is the example in
[json-schema.md](json-schema.md#a-whole-example).

## After converting

1. Go through [checklist.md](checklist.md).
2. Draw it ([diagram.md](diagram.md)) and confirm it with the user, with the list of the source's checks that the
   model cannot keep.
3. Try it with `scripts/try-model.sh`, and write one converted record of each entity type.
4. Loading all the data, with ids turned into links and history into versions at their times, is `lds:data`.
