# Core: the model

This document covers the part of `linked-data-store-core` that reads the model when LDS starts and uses it
afterwards: the JSON Schema of every entity, the specification that is built from it, the validation of a document
before it is written, and the conversion of a GraphQL schema into JSON Schema. It is for developers and maintainers
who change this code.

## What this part is for

LDS knows nothing about the documents it keeps until it has read a model. The model says which entities there are,
which fields a document of an entity has, and which of the fields are links. This part turns the model into three
things that the rest of LDS uses:

- a **JSON Schema** (2020-12) for every entity. A document is validated with it before a write, and it is what
  `?schema` gives out.
- a **specification**: a tree of elements (`SpecificationElement`) that says for every field whether it is a plain
  part of the document or a link, and to which entities a link may point. The REST paths, the link check, the
  search index adapters and the persistence providers read it.
- the **answer to a document that is not valid**: the text of the 400.

The code uses an older word for an entity: "managed domain" (`Specification.getManagedDomains`,
`managedDomain` in parameters and log lines). It is the same thing.

## Where the code is

All of it is in `linked-data-store-core`, except the interfaces of the specification, which are in
`linked-data-store-persistence-provider-api` because the providers read them.

| Class | Responsibility |
|---|---|
| `UndertowApplication.initializeUndertowApplication` (package `io.descoped.lds.core`) | Decides which model is read (`graphql.schema` or `specification.schema`) and builds the specification before anything else is started. `parseSchemaFile` finds the GraphQL file; `createJsonSpecification` reads the JSON Schemas that the converter wrote. |
| `GraphQLToJsonConverter` (package `io.descoped.lds.graphql.jsonSchema`) | Makes one JSON Schema for every type with `@domain` of a GraphQL schema. The work is done by two visitors: `ParseObjectTypesVisitor` (one schema for each entity) and `AddDefinitionVisitor` (the properties of a definition, the link notation, the definitions of embedded types). |
| `JsonSchemaBuilder` (package `io.descoped.lds.core.schema`) | Reads the JSON Schema of one entity into a `JsonSchema`: checks that it is 2020-12, keeps its text, reads its `$defs` into `JsonSchemaDefinitionElement` trees, and makes the `Schema` of the validator. It holds the dialect of LDS (`DIALECT_OF_LDS`) and the registry of the validator (`REGISTRY`). |
| `JsonSchema` | The result for all entities: the validator schema, the text and the definitions, by name. `validate` validates one document. `SchemaRepository` is the interface the handlers get it through. |
| `JsonSchemaDefinitionElement` | One node of a definition as LDS reads it: `types`, `properties`, `items`, `required`, `description`, `title`, `format`, and `name` when it is a definition of `$defs`. |
| `JsonSchemaBasedSpecification` (package `io.descoped.lds.core.specification`) | The `Specification` of LDS, and also the `SchemaRepository`. Its static `create` reads JSON Schema files and folders. |
| `SpecificationJsonSchemaBuilder`, `SpecificationElementBuilder`, `ImmutableSpecificationElement` | Build the tree of elements from the definitions. `SpecificationElementBuilder` holds the rules: what is `ROOT`, `MANAGED`, `EMBEDDED` and `REF`, and how `_link_property_<field>` is read. |
| `LinkedDocumentValidator` (package `io.descoped.lds.core.validation`) | Says whether a document may be written: valid by the JSON Schema, and every link a link to an entity the field may link to. Throws `LinkedDocumentValidationException` with the text for the client. |
| `Specification`, `SpecificationElement`, `SpecificationElementType`, `SpecificationTraversal` (module `linked-data-store-persistence-provider-api`, package `io.descoped.lds.api.specification`) | The interfaces, and the walk over the tree that the link check uses. |
| `DomainDirective`, `LinkDirective`, `ReverseLinkDirective` (package `io.descoped.lds.graphql.directives`) | The directives of LDS and their arguments. `DomainDirective.hasDomainDirective` is what the converter asks. |
| `TypeTraverser` (package `io.descoped.lds.graphql.schemas`) | The walk over a GraphQL schema that the visitors are shown. What the converter writes follows from it. |

The module descriptor (`module-info.java`) requires `com.networknt.schema`, `com.graphqljava`,
`com.graphqljava.extendedscalars`, `tools.jackson.core` and `tools.jackson.databind` for this part. Of the
packages of this part only `io.descoped.lds.core` (with `UndertowApplication`) is exported. The `pom.xml` of core
takes `com.networknt:json-schema-validator` without its YAML reader (`jackson-dataformat-yaml` is excluded): LDS
reads no schema written as YAML.

## How it works

The data goes one way:

```
graphql.schema  -> GraphQLToJsonConverter -> JSON Schema of each entity -+
                                                                         +-> JsonSchemaBuilder -> JsonSchema
specification.schema -> files, one for each entity ----------------------+        |
                                                                                  +-> validator schema (validate)
                                                                                  +-> text (?schema)
                                                                                  +-> definitions -> specification
```

### Which model is read

`UndertowApplication.initializeUndertowApplication` reads the model first, before persistence, sagas and HTTP.

1. When `graphql.schema` is not empty, the GraphQL file is the model. `parseSchemaFile` looks for the path on the
   class path first (`ClassLoader.getSystemResource`) and then as a file. `GraphQLSchemaBuilder.parseSchema` makes
   a schema of it, `GraphQLToJsonConverter.createSpecification` makes the JSON Schemas, and
   `createJsonSpecification` reads each of them with `JsonSchemaBuilder` and builds the specification.
   `specification.schema` is not looked at. This is so also when `graphql.enabled` is `false`: that key only decides
   whether the GraphQL API is served.
2. Otherwise `specification.schema` is split at every comma (the parts are not trimmed) and given to
   `JsonSchemaBasedSpecification.create`. A part that is a folder gives every file in it whose name ends in `.json`,
   in any case of the letters; folders inside it are not read. Any other part is one file, read from the file
   system when it exists and else from the class path (`FileAndClasspathReaderUtils.readFileOrClasspathResource`).
   The name of the entity is the name of the file without its last five characters.
3. When both keys are empty, LDS starts with no entity and logs `No schemas configured!`. The same happens when
   `specification.schema` names only folders without a `.json` file. With the defaults of the server that is the
   case as soon as `graphql.schema` alone is set to empty: `specification.schema` is `schemas` there, and that
   folder holds only `schema.graphql`.

The log says which was used. With a GraphQL file the converter has done its work before the line of
`UndertowApplication` is written, so the entities come first:

```
... GraphQLToJsonConverter - Managed domains: /Company /Person
... UndertowApplication - Creating specification using GraphQL schema: schemas/schema.graphql
```

The GraphQL file is parsed a second time in the constructor of `UndertowApplication`, for the GraphQL API, when
`graphql.enabled` is `true`. That is described in [the GraphQL schema](core-graphql.md).

### From a GraphQL schema to JSON Schema

`GraphQLSchemaBuilder.parseSchema` builds the schema with graphql-java. It knows three scalars beside those of
GraphQL itself: `DateTime`, `Date` and `Time`. A file that declares another scalar is refused
(`There is no scalar implementation for the named  'Long' scalar type`). The file must declare the directives it
uses, with the arguments it uses; graphql-java refuses the file otherwise. The parts of a type are put in the
order of their names (`SchemaElements.ORDER`), so the converter meets fields, enum values and the members of a
union in that order.

`GraphQLToJsonConverter.createSpecification` walks all types of the schema with `ParseObjectTypesVisitor`. For a
type with `@domain` it starts a schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$ref": "#/$defs/Person",
  "$defs": {
    "Person": {
      "type": "object",
      "properties": {},
      "required": []
    }
  }
}
```

puts `displayName` (the first line of the description of the type) and `description` on the definition when the
type has a description, and then walks the fields of the type with an `AddDefinitionVisitor`. The description is
cut into lines at `System.lineSeparator()`. A type without `@domain` gives no schema of its own, and an embedded
type gets neither `displayName` nor `description` on its definition. A line that starts with `#` right above a
type or a field is its description, as a text in quotes is. The entities come in the order of
`GraphQLSchema.getTypeMap()`; in every run and in every golden file that is the order of their names.

`AddDefinitionVisitor.visitGraphQLFieldDefinition` writes one property for each field: `displayName` (always an
empty text), `description` when the field has one, the name in `required` when the type of the field ends in `!`,
and a type for the scalars it knows. Then the walk goes on into what is under the field, and the other `visit`
methods add to the same property. Under a field are its type, its arguments and its directives with the arguments
of their declarations. `@deprecated` is not looked at. The arguments are walked as the type is, which is described
in the next section. What comes out:

| Field in the GraphQL schema | Property in the JSON Schema |
|---|---|
| `String` | `{"type": "string"}` |
| `Boolean` | `{"type": "boolean"}` |
| `DateTime` | `{"type": "string", "format": "date-time"}` |
| `Int`, `Float`, `ID`, `Date`, `Time` | no `type`: any value is valid |
| an enum | `{"type": "string", "enum": [...]}`, the values in the order of their names |
| `[String]` | `{"type": "array", "items": {"type": "string"}}` |
| a list of another scalar | `{"type": "array", "items": {}}`. `[DateTime]` also keeps `"format": "date-time"` beside `"type": "array"`; the items are not checked. |
| a list of an enum | `{"type": "array"}` |
| an embedded type (a type without `@domain`) | no `type` and no `$ref`: any value is valid. The type is still written as a definition in `$defs`. |
| a list of an embedded type | `{"type": "array", "items": {"$ref": "#/$defs/Address"}}`, and the type as a definition in `$defs` |
| an entity with `@link` | `{"type": "string"}` and beside it `_link_property_<field>` with the entity as its one property |
| a list of an entity with `@link` | `{"type": "array", "items": {"type": "string"}}` and beside it `_link_property_<field>` |
| a union with `@link`, or a list of one | as a link to an entity, with one property in `_link_property_<field>` for each member of the union |

The arguments of `@domain` (`searchable`) and of `@link` (`pagination`, `reverseName`) change nothing in the JSON
Schema. They are read when the GraphQL schema is built.

This is the schema of `Person` of the example model (`linked-data-store-server/schemas/schema.graphql`), asked
from a running LDS. The answer is one line; it is shown here on several.

```sh
curl -s 'http://localhost:19110/ns/Person?schema'
```

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$ref": "#/$defs/Person",
  "$defs": {
    "Person": {
      "type": "object",
      "properties": {
        "address": {"displayName": ""},
        "born": {
          "type": "string",
          "format": "date-time",
          "displayName": ""
        },
        "employer": {
          "displayName": "",
          "type": "string",
          "description": "A company"
        },
        "_link_property_employer": {
          "properties": {
            "Company": {
              "type": "null"
            }
          },
          "type": "object"
        },
        "friends": {
          "displayName": "",
          "type": "array",
          "items": {"type": "string"}
        },
        "_link_property_friends": {
          "properties": {
            "Person": {
              "type": "null"
            }
          },
          "type": "object"
        },
        "name": {"type": "string", "displayName": ""}
      },
      "required": ["name"],
      "displayName": "A person",
      "description": "A person"
    },
    "Address": {
      "type": "object",
      "properties": {
        "city": {"type": "string", "displayName": ""},
        "street": {"type": "string", "displayName": ""}
      },
      "required": ["city"]
    }
  }
}
```

### What follows from the walk of a GraphQL schema

`TypeTraverser` shows a type to the visitor once in a walk. When the same enum, entity, union, interface or list
type is met a second time in the walk of one type, the visitor is told of a back reference and its `visit` method
is not called. One walk covers the fields of one entity; an embedded type gets a walk of its own. The visitor also
has no method for some shapes and falls through to the walk of what is under the field. The result is exact but
not always what the schema file says. All of this was seen on a running LDS with models written for it, or is in
the golden files:

- **Two fields with the same list type in one type.** Only the first by name is written as a list. With
  `a: [String]` and `b: [String]`, `b` is `{"type": "string"}`, and a document with an array in `b` is refused
  (`$.b: array found, string expected`). With `c: [Part]` and `d: [Part]`, `d` has no type. The `!` counts: after
  `[T!]!`, both `[T!]!` and `[T!]` are the same list type, and after `[T]!` so is `[T]`. The other way round is
  not: `[T]!` after `[T]` is written as a list, because `visitGraphQLNonNull` calls `visitGraphQLList` itself.
  Lists of links (`@link`) are not affected.
- **Two fields with the same enum in one type.** Only the first by name gets `type` and `enum`. With
  `earlier: State` and `state: State!`, `state` has no type and takes any value.
- **A field whose type is an entity, without `@link`,** is written as a link (`job: Job` gives `"type": "string"`
  and `_link_property_job`). This holds for the first such field by name only: a second field of the same entity
  without `@link` has no type and no `_link_property_<field>`.
- **A list of an entity without `@link`** is written as a list of links when that entity has no field whose type
  is an entity. When it has (`jobs: [Job]`, and `Job` has `boss: Person`), the field is written as
  `"type": "object"` with two properties for each field of that entity whose type is a list without `!` after it:
  an array of texts, and a `_link_property_<field>` that names the type of the items, whatever that type is
  (`String` also). A document with an array there is refused (`$.jobs: array found, object expected`). A field
  with `@reverseLink` written in the file is such a list. A second list of the same entity in the type has no type.
- **`[Entity!]` with `@link`** is written as a list of links when the entity has no field of an entity type, and as
  `{"type": "object", "properties": {}}` without `_link_property_<field>` when it has. `[Entity]` with `@link` is
  always a list of links.
- **A union without `@link`** is written as a link to one member: the last by name of the members that the walk has
  not met before (with `union Thing = Job | House`, `c: Thing` links to `Job`, and to `House` when a field
  `a: Job` comes before it). A second field of the same union without `@link` has no type. A list of a union
  without `@link` is `{"type": "array"}`.
- **A field whose type is an interface** has no type. The fields of the interface are written into the definition
  the field is in, as if they were its own: a field of the same name is replaced when the interface is walked
  after it, a field of the interface with `!` is in `required` of the entity, and a name can come twice in
  `required` (the validator then gives the error twice).
- **A single link to an entity** takes the description of that entity in place of its own, and has none when the
  entity has none. A link to a union and a list of links keep their own.
- **A list of lists** (`[[String]]`) is written as a list (`"items": {"type": "string"}`).
- **The arguments of a field** are walked as its type is, and what they are is written on the field. With
  `b(upper: Boolean!): String`, `b` is in `required`. With `c(tags: [String]): String`, `c` is
  `{"type": "array", "items": {"type": "string"}}`. With `d(kind: Kind): String`, `d` gets the `enum` of `Kind`.
  An argument that is a scalar without `!` changes nothing. A field that `@link` makes a link is not walked
  further, so its arguments change nothing.
- **The arguments of the declaration of another directive on a field** are walked in the same way. With
  `directive @mark(level: Int!) on FIELD_DEFINITION`, a field `e: String @mark(level: 2)` is in `required`. With
  `directive @tagged(tags: [Int]) on FIELD_DEFINITION`, a field `g: String @tagged(tags: [1])` is
  `{"type": "array", "items": {}}`. The arguments of `@link` as the example model declares them are scalars
  without `!` and change nothing. A directive on a type is not walked.
- **`@link` on a field whose type is an embedded type** is written as a link to the name of that type, and the type
  gets no definition from that field.

`GraphQLSchemaGoldenTest` holds what the converter writes for twelve schema files, among them several of these
shapes (`syn-embedded`, `syn-shared-embedded`, `syn-abstract`).

### Reading a JSON Schema

`JsonSchemaBuilder.build` is called once for each entity, with the `JsonSchema` that holds what was read so far.
It does this, in order:

1. The schema must be a JSON object. `$schema`, when there, must be the text
   `https://json-schema.org/draft/2020-12/schema`. A schema with `definitions` at its root is refused. Both are an
   `IllegalArgumentException` that names the entity.
2. The text of the schema is kept for `?schema`: `schemaNode.toString()`, which is the JSON without white space and
   with the keys in the order they came.
3. Every definition of `$defs` is read into a `JsonSchemaDefinitionElement` with `buildElement`, unless a schema
   read before gave a definition of that name. A property that is `{"$ref": "#/$defs/Name"}` becomes the very
   element of that definition (the same object). A `$ref` of another form, or to a definition the schema does not
   have, is an `IllegalStateException`.
4. The validator schema is made (`REGISTRY.getSchema`) and its validators are made at once
   (`initializeValidators`), so that a `$ref` at the root that leads nowhere stops LDS at start and not at the
   first write.

What `buildElement` reads of a node: `type` (a text, or a list of texts), else the types of the alternatives of
`anyOf`, else `object`; `properties`; `items`; `required`; `description` (an empty text when there is none);
`title` and `format`. Every other keyword is left to the validator. `title` and `format` are read and not used.
A node must be a JSON object, also the value of each property of a `_link_property_<field>` object (`{}` is
enough); anything else is an `IllegalArgumentException` that names the entity and the node. Of a node with `$ref`
nothing else is read. The `$ref` at the root of the schema is not read here: only the validator follows it, so
which definition is the entity is decided by the name the schema was given, not by that `$ref`.

`DIALECT_OF_LDS` is JSON Schema 2020-12 with one more keyword, `displayName`, as an annotation. Without it the
validator would warn about `displayName` at every start, because the converter writes it. A keyword the validator
does not know is still warned about, and the schema is read:

```
WARN  c.networknt.schema.keyword.UnknownKeywordFactory - Unknown keyword shownAs - you should define your own
Meta Schema. ...
```

`REGISTRY` sets `PathType.JSON_PATH`, so that the validator writes a place in a document as LDS writes a path
everywhere else (`$.tags[1]`).

### Building the specification

`SpecificationJsonSchemaBuilder.build` makes the root element; `ImmutableSpecificationElement` asks its
`SpecificationElementBuilder` for its children when it is made, so the whole tree is built at once and does not
change afterwards.

- The **root** has the name `root`, the type `ROOT`, and one property for each definition that was read, of every
  entity. A definition whose name is the name of an entity is `MANAGED`. Any other definition is `EMBEDDED`.
- The properties of an element are the properties of its definition, without those whose name starts with
  `_link_property_`. A property is `EMBEDDED`, or `REF` when the definition has a property
  `_link_property_<name>` beside it. The link targets (`getRefTypes`) of a `REF` are the names of the properties of
  that object. Only the names are read. A `_link_property_<name>` object without `properties` gives a `REF` with
  no link target, and every link in that field is then refused.
- `getItems` is an element for `items`. It has no element type. Its name is the name of the definition when
  `items` is a `$ref`, and an empty text otherwise.
- `getJsonTypes` are the types read by `JsonSchemaBuilder`. `getDescription` is the description, an empty text
  when there is none, and null for the root.
- `getRequired` holds names only on the children of the root, so `isRequired` is true only for a property that is
  written directly in a definition of `$defs` and is asked through that definition.
- `getValidators` is always empty. `SpecificationValidator` has no method and no implementation.
- Apart from the classes that implement an element (here and in the shared suite of the persistence providers),
  no main code calls `getDescription`, `getRequired`, `isRequired` or `getValidators`.

What each part of a model becomes:

| In the model | In the JSON Schema that is read | Element of the specification |
|---|---|---|
| `type Person @domain`, or the file `Person.json` | a definition named as an entity | `MANAGED`, a child of `ROOT` |
| `type Address`, without `@domain` | any other definition | `EMBEDDED`, a child of `ROOT` |
| `name: String!`, a value | a property | `EMBEDDED` |
| `address: Address`, an embedded object | a property | `EMBEDDED` |
| `employer: Company @link` | `employer` and `_link_property_employer` | `REF`, with `getRefTypes` `[Company]` |
| `employer: Company`, without `@link` | the same as with `@link` | `REF` |

The specification of the example model, printed by walking `getRootElement()`:

```
root ROOT [object]
  Text EMBEDDED [object]
    languageCode EMBEDDED [string]
    languageText EMBEDDED [string] required
  Company MANAGED [object]
    about EMBEDDED [array]
      items: Text [object]
        languageCode EMBEDDED [string]
        languageText EMBEDDED [string]
    founded EMBEDDED [string]
    name EMBEDDED [string] required
  Person MANAGED [object]
    address EMBEDDED [object]
    born EMBEDDED [string]
    employer REF [string] links to [Company]
    friends REF [array] links to [Person]
      items: [string]
    name EMBEDDED [string] required
  Address EMBEDDED [object]
    city EMBEDDED [string] required
    street EMBEDDED [string]
```

`address` has no properties here because the converter wrote no `$ref` for a single embedded type (see above).
The files `*.specification.txt` in `src/test/resources/graphql-golden` show the specification of other models in
full.

Who reads the specification: `ResourceContext` (which path is a document, a part of one, or a link),
`LinkedDocumentValidator`, the saga adapters of persistence and search, `JsonNavigationPath.toSpecificationElement`
in the persistence API, and the Neo4j provider, which takes from it which fields are links. Persistence is given
the names of the entities at start (`PersistenceConfigurator`).

### Validation of a document

`ManagedResourceHandler.putManaged` validates the body of a `PUT` or `POST` of a document.
`EmbeddedResourceHandler.putEmbedded` (`PUT` or `POST` of a part) validates the whole document after the part was
put into it. Both make a `LinkedDocumentValidator` and answer 400 with `Schema validation error: ` and the message
of the exception. A link that is put through its own path is not validated here; `ResourceContext` checks its
entity (`Reference resource does not support link to managed-domain "Person". Must be one of: [Company]`).

`LinkedDocumentValidator.validate` does two things, in this order:

1. **The JSON Schema.** `JsonSchema.validate` runs the validator on the Jackson tree, with format assertions switched
   on (`formatAssertionsEnabled(true)`). JSON Schema 2020-12 only describes with `format` unless asked; LDS asks,
   so a text in a `date-time` field must be a date-time of RFC 3339. Every error becomes `Error.toString()`: the
   place and the message of the validator. When there are errors the exception carries the list as Java writes a
   list: `[$.name: integer found, string expected, $.kind: ...]`. All errors are given.
2. **The links**, only when the JSON Schema found nothing. `SpecificationTraversal.depthFirstPreOrderFullTraversal`
   walks the properties of the entity. For each `REF` the value is looked up in the document through the names of
   its ancestors. No value, a null, or an ancestor that is not an object in the document: nothing to check. A value
   of an array field is checked item by item, nulls left out. An array field is one whose types
   (`getJsonTypes`) include `array`; when the schema also lets such a field be a text
   (`"type": ["array", "string"]`), a text there is not checked. A link must match `/([^/]+)/([^/]+)` as a whole
   (`/Entity/id`), and the entity must be among the link targets. The first link that fails ends the check:
   `Not a valid link. Navigation: employer: "acme"` or
   `Illegal linked-domain: "Person". Navigation: employer: "/Person/ola"`.

That the document a link points at exists is not checked, by intent: a link may point at a document that does not
exist yet.

Seen on a running LDS with the example model:

```sh
curl -s -i -X PUT 'http://localhost:19110/ns/Person/p1?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "name": 7,
       "born": "yesterday",
       "friends": "x"
     }'
```

```
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: [$.born: does not match the date-time pattern must be a valid RFC 3339 date-time,
$.friends: string found, array expected, $.name: integer found, string expected]
```

```sh
curl -s -i -X PUT 'http://localhost:19110/ns/Person/p1?sync=true' -H 'Content-Type: application/json' \
     -d '{
       "name": "Per",
       "friends": ["/Person/ola", "/Company/acme"]
     }'
```

```
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Schema validation error: Illegal linked-domain: "Company". Navigation: friends: "/Company/acme"
```

(The first answer is one line.)

### `?schema`

`NamespaceController.handleRequest` answers two requests on the default namespace itself, and
`ManagedResourceHandler.getManaged` one on an entity:

| Request | Answer |
|---|---|
| `GET /ns?schema` | a JSON array with the path of the schema of every entity, in the order of the names: `["/ns/Company?schema","/ns/Person?schema"]` |
| `GET /ns?schema=embed` | a JSON array with the JSON Schema of every entity, in the order of the names |
| `GET /ns/Person?schema` | the JSON Schema of the entity: `JsonSchema.getSchemaJson` |

`ns` stands for the value of `namespace.default`. The content type is `application/json; charset=utf-8`. On a
document (`/ns/Person/ola?schema`) the parameter is not looked at.

On the namespace the path must be exactly `/ns` (`/ns/?schema` is answered 400), and `NamespaceController` does not
look at the method: a `PUT` or a `DELETE` of `/ns?schema` gets the same answer as a `GET`. The list is given when
the first value of `schema` is blank, the schemas when one of its values is `embed`. Any other value goes on to
`DataController` and is answered 400 with `Not a valid resource. The resource path must contain at least
namespace, name, and id of managed resource`. On an entity only a `GET` gives the schema, and the value of the
parameter is not looked at. An entity the model does not have is answered 400 with
`Not a managed resource name: "Nothing"` (`ResourceContext.createResourceContext`).

## Rules to keep

- **JSON Schema 2020-12 and nothing earlier.** A schema that says it is another draft, or has `definitions`, is
  refused at start, and so is a `$ref` that leads nowhere. Do not add a reader for an earlier draft: 3.0.0 is a
  clean break, and a schema of an earlier draft read as 2020-12 would be an empty model without a word.
- **Three things are LDS's own and stay:** the link notation (`_link_property_<field>` beside the field; the
  validator sees two ordinary properties and LDS checks the link itself), the check of `format`, and the form of
  an error (the place as LDS writes a path, then the message of the validator). Clients read these texts.
- **What the converter writes is pinned.** `GraphQLSchemaGoldenTest` compares the JSON Schema and the specification
  of twelve schema files with the files in `src/test/resources/graphql-golden`. A difference is a change in what LDS
  gives its clients and stores as links; understand it before an expected file is changed.
- **Keep `TypeTraverser`.** The visitors depend on the order of its walk and on where it stops. The
  `SchemaTraverser` of graphql-java walks differently.
- **The specification is what the providers are told about links.** A provider stores a document as it is given;
  whether a document may be like that is for the validation before a write to say. The one thing a provider takes
  from the model is which fields are links (`REF`, `getRefTypes`, `getItems`). A change in how those are built
  changes what Neo4j stores as a link.
- **JSON is Jackson 3 trees.** A text is taken out of a node only when the node is known to be a text
  (`isString()` before `stringValue()`); `JsonSchemaBuilder.text` and `LinkedDocumentValidator.text` do this.
  Jackson 3 throws where a node is of another kind.
- **Fail at start, not at the first write.** A model that cannot be read stops LDS with a message that names the
  entity. `initializeValidators` is called for this reason.
- **Names are not spliced.** The name of an entity comes from the model (the name of a file, or of a GraphQL type)
  and is not validated here. Code that puts a name into a query or a path must not concatenate it.
- **Tests.** A new test class is JUnit 6 with AssertJ (`JsonSchemaBuilderTest`, `DocumentValidationTest`,
  `GraphQLSchemaGoldenTest`). The older classes of this part are TestNG and stay so until core is revised.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `graphql.schema` | empty in core (`application-defaults.properties`), `schemas/schema.graphql` in the server (`application-server-defaults.properties`) | The path of a GraphQL schema file. When it is not empty it is the model. |
| `specification.schema` | empty in core, `schemas` in the server | Folders and files of JSON Schema, separated by commas. Read only when `graphql.schema` is empty. |
| `graphql.enabled` | `true` | Whether the GraphQL API is served. It does not change which model is read. |
| `namespace.default` | `ns` | The namespace on which `?schema` is answered. |

A path is relative to the working directory of the process. To start the built LDS with JSON Schema files
(`$M` is a folder with one `.json` file for each entity; the command was run in a folder with every jar in `lib`,
and in the repository the class path is `target/*:target/lib/*` under `linked-data-store-server`, as the README
says):

```sh
LDS_HTTP_PORT=19110 LDS_GRAPHQL_SCHEMA= LDS_SPECIFICATION_SCHEMA=$M java -cp "lib/*" io.descoped.lds.server.Server
```

```
... UndertowApplication - Creating specification using json-schema: .../json2
... JsonSchemaBasedSpecification - Managed domains: /Company /Person
```

The GraphQL API is not served then (`/graphql` is answered `Unsupported namespace: "graphql"`).

## Tests

All are in `linked-data-store-core/src/test`.

| Class | Kind | What it checks |
|---|---|---|
| `JsonSchemaBuilderTest` | JUnit | What is read from a schema, the text kept for `?schema`, a schema without `$schema`, `displayName` and an unknown keyword, definitions shared between entities, earlier drafts refused, a `$ref` that leads nowhere, what is no schema. |
| `DocumentValidationTest` | JUnit | The text of every kind of error, that all errors are given, date-times that are valid and texts that are not, links, a null where a link could be. |
| `GraphQLSchemaGoldenTest` | JUnit | The JSON Schema and the specification the converter gives for twelve schema files, against the files in `graphql-golden` (written with `CanonicalText`). Also the GraphQL schema that is served. |
| `GraphQLToJsonConverterTest` | TestNG | The conversion of `spec/abstract/graphqlschemas/schema.graphql`: dialect, `$ref`, `displayName`, `required`. |
| `JsonSchemaBasedSpecificationTest`, `SpecificationJsonSchemaBuilderTest` | TestNG | That link fields are `REF` and others `EMBEDDED`, and the count of elements of each type for `spec/agent/Agent.json`. |
| `LinkedDocumentValidatorTest` | TestNG | Links to the right and the wrong entity, single and in an array, with `spec/schemas`. |
| `NamespaceSchemaTest`, `ManagedResourceHandlerTest` | TestNG | `?schema` on the namespace and on an entity, and the 400 for a document that is not valid, through HTTP. |

The tests that go through HTTP take their server from `TestServerListener`, which reads
`application_test.properties`. That file sets both `specification.schema` and `graphql.schema`, so the model of
those tests is the GraphQL file `gqlschema/schema.graphql`.

The example models are in `src/test/resources`: `spec/schemas` (two entities with links of every shape, as JSON
Schema), `spec/abstract` (the same model as GraphQL and as JSON Schema, with a union), `spec/agent`,
`gqlschema/schema.graphql`, and the files of `graphql-golden`.

To run them, as `.claude/CLAUDE.md` gives the commands (they were not run for this document; a build needs
JDK 27 and Docker):

```sh
./mvnw test -pl linked-data-store-core -Dtest=JsonSchemaBuilderTest
./mvnw verify -pl linked-data-store-core -am
```

A model can also be tried without a build of the tests: start the built LDS with it, as shown under Configuration,
and ask `?schema`.

## Not finished and known to be wrong

Not finished: the converter does not write what the GraphQL schema says for the shapes listed under "What follows
from the walk of a GraphQL schema". The golden files hold several of them, so a correction changes expected files.

Not finished: a single field of an embedded type gets no `$ref` from the converter. Such a field is not
validated, its links are not checked, and a path into it is refused (`Not a valid path element: "street"`).

Not finished: the links inside the items of an array of objects are not checked. `SpecificationTraversal` walks
`getProperties` and not `getItems`, so `LinkedDocumentValidator` never comes to them. A document with
`"homes": [{"city": "Oslo", "registeredBy": "no link at all"}]` is written, where `registeredBy` is a link field of
the definition of the items.

Not finished: the name of every definition is a property of the root, also of an embedded one, and
`ResourceContext.createResourceContext` takes any property of the root as an entity. With the example model
`GET /ns/Address` is answered 200 with `[]`, and `GET /ns/Address?schema` and `PUT /ns/Address/a1` are answered
500 (`JsonSchema.getSchemaJson` gives null; `JsonSchema.validate` throws `No JSON Schema for the entity Address`).

Not finished: when two files define a definition of the same name in `$defs`, the specification keeps the one
that was read first and the validator uses the one of each file. Links and paths then follow one definition and
validation another. The order in which a folder is read is the order the file system gives.

Not finished: a file model is not checked for being usable. An entity whose file has no `$defs`, or whose file
name is not the name of its definition, is listed by `?schema` and every request to it is answered
`Not a managed resource name`. A file without `$ref` at its root is read, and its documents are then not validated.
A file given by name that does not end in `.json` loses its last five characters all the same (`Firm.schema`
becomes the entity `Firm.s`). A folder without a `.json` file gives a model with no entity, and LDS starts.

Not finished: a definition that holds itself, directly or through others, ends the start with a
`StackOverflowError`. This is so for `$ref` in a file and for embedded types in a GraphQL schema
(`type Branch { branches: [Branch] }`). A link from an entity to itself is no such case.

Not finished: a GraphQL schema with no type with `@domain` gives no specification
(`createJsonSpecification` returns null). The start then fails with a `NullPointerException` in
`PersistenceConfigurator`, after it has tried for `persistence.initialization.max-wait-seconds`.

A list of an entity without `@link` (`support: [Support]`) is held by value when that entity has a field whose type is
an entity: `AddDefinitionVisitor.visitGraphQLList` then writes the field as `"type": "object"` and calls
`visitEmbeddedType`, which writes each list field of the entity into it as a list of links. The specification makes it
`EMBEDDED`, since it has no `_link_property_support`, so the REST API reaches it and the links in it by path. It came in
November 2019 (`bdcc5b1`, `5b4a2ff`), with a test model that has `support: [support]` in `provisionagreement`; that
model is no longer in the repository. A list of an entity that has no such field is a list of links
(`visitListLinkProperty`), and a single field of an entity without `@link` is a link.

Not finished: `visitEmbeddedType` writes every list field of the entity as a list of links, with
`_link_property_<field>` naming the type of the items, also for `[String]`. The other fields are left out, so they
are not checked. It makes the field one object, so a list is refused, and it writes each field definition and the
result to standard output with `System.out.println`. The GraphQL schema keeps the field as a list
of the entity, and a query of it is answered with `type mismatch error, expected type LIST`. It ends the start
with a `NullPointerException` when it meets a list of a list.

Not finished: a list of links to an interface gets a JSON Schema and a specification, and the GraphQL schema is
then refused with a `ClassCastException` (`GraphQLSchemaGoldenTest.aListOfLinksToAnInterfaceIsRefusedAsItWas`).
The exception comes in the constructor of `UndertowApplication`, after the thread pools of the sagas are made, and
the process stays up without listening.

Not finished: `JsonSchemaBuilder.build` has the comment `TODO remove link directives from schema`. The
`_link_property_<field>` objects stay in the schema that validates, so a document may hold a field of that name
when its value is an object.

Not finished: `io.descoped.lds.graphql.schemas.JsonSchemaBuilder` is an empty class. Several test resources are
not used by any test (`spec/strangeschema`, `spec/v1`, `io/descoped/lds/graphql/schemas/examples`, the `.raml`
files under `spec/abstract/schemas`), and several tests set `specification.schema` to files that do not exist
(`spec/demo/contact.json`), which goes unseen because `graphql.schema` is set as well.
`SpecificationJsonSchemaBuilderTest.thatSpecificationBuiltFromJsonSchemaWithCustomLinkingWorks` asserts nothing.
