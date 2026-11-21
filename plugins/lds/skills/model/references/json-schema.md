# Writing the model as JSON Schema files

With JSON Schema files LDS checks every value the schema describes, and has **no GraphQL API, no reverse links and
no search**: `/graphql` is answered `400 Unsupported namespace: "graphql"`. Use this form when the user asks for it,
or when strict checking matters more than querying. Everything here was run against `descoped/lds:3.0.0`; the
shapes that break are in [checklist.md](checklist.md). Depth:
[The model](https://lds.descoped.io/docs/user/model/#a-model-as-json-schema-files).

## The files

- One file for each entity type, named as the entity type: `Member.json` is the entity type `Member`.
- All files in one folder. LDS reads every file whose name ends in `.json`, skips other files, and does not read
  subfolders.
- LDS is started with `LDS_GRAPHQL_SCHEMA=` (empty), and reads the folder `schemas` (in the image, `/lds/schemas`).

## The form of a file

JSON Schema 2020-12 and nothing earlier. At the root a `$ref` to the definition of the entity type in `$defs`, which
has the same name as the file. `$schema` may be left out.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$ref": "#/$defs/Member",
  "$defs": {
    "Member": {
      "type": "object",
      "description": "A member of the library",
      "properties": {
        "name": {"type": "string", "minLength": 1},
        "email": {"type": "string", "format": "email"},
        "joined": {"type": "string", "format": "date"},
        "status": {
          "type": "string",
          "enum": ["ACTIVE", "SUSPENDED"]
        },
        "addresses": {
          "type": "array",
          "items": {"$ref": "#/$defs/Address"}
        }
      },
      "required": ["name", "joined", "status"]
    },
    "Address": {
      "type": "object",
      "properties": {
        "street": {"type": "string"},
        "postalCode": {"type": "string"},
        "city": {"type": "string"}
      },
      "required": ["city"]
    }
  }
}
```

- An embedded type is a further definition in `$defs` of the same file, used as `{"$ref": "#/$defs/Address"}`. A
  `$ref` must have this form and name a definition of the same file; a `$ref` to another file is refused at start.
  So an embedded type used by two entity types is written in both files, with the same content in each.
- A single embedded object written with `$ref` is checked, and so are the links in it (unlike a GraphQL schema).
- What JSON Schema 2020-12 says about a value is checked on write. Tried and working: `type` (one name or a list),
  `required`, `enum`, `minLength`, `minimum`, `pattern`, `format` (`date-time`, `date`, `time`, `email`), `items`,
  `$ref`, `anyOf` and `additionalProperties`.
- `"type": ["string", "null"]` accepts `null`. Without `null` in the type, a `null` is refused.
- `"additionalProperties": false` refuses fields the schema does not name; without it they are stored.
- `default` gives a document nothing: a field that is left out stays out.
- `displayName` may be written anywhere and is given out in `?schema`. Any other word that is not JSON Schema
  (`"shownAs"`) starts fine and logs a warning: `Unknown keyword shownAs - you should define your own Meta Schema.`
- `?schema` on an entity type gives the file as it is, without white space.

## Links

A link field holds a text (or an array of texts) and has a second property beside it named `_link_property_` and
the field name, an object whose `properties` name the entity types the field may link to:

```json
"owner": {"type": "string"},
"_link_property_owner": {
  "type": "object",
  "properties": {
    "Owner": {"type": "null"}
  }
}
```

- A list of links: `"authors": {"type": "array", "items": {"type": "string"}}` with `_link_property_authors`.
- Several names in `properties`: the field may link to any of them (as a union). No `properties`: every link in it
  is refused.
- The value of each name must be a JSON object; `{"type": "null"}` is the form LDS writes itself.
- A link inside an embedded object has its second property beside it, in the same `properties`.
- A link in the document is `"/Owner/o-1"`, the same as with a GraphQL schema. LDS checks the form and the entity
  type, not that the document is there: `Schema validation error: Illegal linked-domain: "Pet". Navigation: owner:
  "/Pet/rex"`.
- A link may be `null` where the type allows it (`"type": ["string", "null"]`).
- Links inside the items of a list of embedded objects are not checked.

## Paths

With JSON Schema files a path can go into an embedded object: `GET /ns/Publisher/ace/address/city` and a `PUT` of
it work, where a GraphQL schema stops at `address`. Elements of a list still cannot be addressed. `lds:data` covers
parts of a document.

## From OpenAPI 3.1 or an existing JSON Schema

OpenAPI 3.1 schemas and other JSON Schema documents are close to this form. Bring them in with these changes, then
run [checklist.md](checklist.md):

| In the source | In an LDS file |
|---|---|
| `definitions` (draft-07 and earlier), `$schema` of an earlier draft | `$defs`, and `$schema` 2020-12 or none |
| `#/components/schemas/Owner` or `other.json#/...` | a link field with `_link_property_` when `Owner` is an entity type; a copy of the definition in this file's `$defs` when it is an embedded type |
| `nullable: true` (OpenAPI 3.0) | `"type": ["string", "null"]` |
| the entity at the root (`type`, `properties` at the top) | moved into `$defs`, with a root `$ref` to it |
| `id` property | the id in the address; keep it as a field only when the user wants it in the document |
| `readOnly`, `example`, `xml`, `discriminator` | removed (unknown words only log a warning, but they say nothing to LDS) |

## A whole example

From an OpenAPI document with a `Pet` (with an owner and vaccinations) and an `Owner`. `Pet.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$ref": "#/$defs/Pet",
  "$defs": {
    "Pet": {
      "type": "object",
      "properties": {
        "name": {"type": "string"},
        "species": {"type": "string", "enum": ["dog", "cat"]},
        "birthDate": {"type": "string", "format": "date"},
        "owner": {"type": "string"},
        "_link_property_owner": {
          "type": "object",
          "properties": {
            "Owner": {"type": "null"}
          }
        },
        "vaccinations": {
          "type": "array",
          "items": {"$ref": "#/$defs/Vaccination"}
        }
      },
      "required": ["name"]
    },
    "Vaccination": {
      "type": "object",
      "properties": {
        "vaccine": {"type": "string"},
        "given": {"type": "string", "format": "date-time"}
      },
      "required": ["vaccine"]
    }
  }
}
```

`Owner.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$ref": "#/$defs/Owner",
  "$defs": {
    "Owner": {
      "type": "object",
      "properties": {
        "name": {"type": "string"},
        "phone": {"type": "string"}
      },
      "required": ["name"]
    }
  }
}
```

LDS read both (`Managed domains: /Owner /Pet`). A pet that fits was answered `201`:

```sh
curl -X PUT 'http://localhost:9090/ns/Pet/rex?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "name": "Rex",
       "species": "dog",
       "birthDate": "2021-04-01",
       "owner": "/Owner/o-1",
       "vaccinations": [
         {"vaccine": "rabies", "given": "2025-05-01T10:00:00Z"}
       ]
     }'
```

A pet with a date-time as `birthDate` and a vaccination with no vaccine and a wrong date was answered:

```
HTTP/1.1 400 Bad Request

Schema validation error: [$.birthDate: does not match the date pattern must be a valid RFC 3339 full-date,
$.vaccinations[0].given: does not match the date-time pattern must be a valid RFC 3339 date-time,
$.vaccinations[0]: required property 'vaccine' not found]
```
