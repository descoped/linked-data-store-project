# Checklist: what LDS refuses, hangs on, or does not read as written

Contents: [How to use it](#how-to-use-it) · [GraphQL schema](#graphql-schema) ·
[JSON Schema files](#json-schema-files) · [Loading](#loading) ·
[From a message to the fix](#from-a-message-to-the-fix)

Each row is what `descoped/lds:3.0.0` did when the shape was tried, and the messages are as LDS wrote them. Depth:
[Shapes that do not work as written](https://lds.descoped.io/docs/user/model/#shapes-that-do-not-work-as-written),
[Schemas that LDS refuses at start](https://lds.descoped.io/docs/user/model/#schemas-that-lds-refuses-at-start),
[Files that LDS refuses at start](https://lds.descoped.io/docs/user/model/#files-that-lds-refuses-at-start),
[Files that do not work](https://lds.descoped.io/docs/user/model/#files-that-are-not-refused-and-do-not-work).

## How to use it

Go through the model with each section of its form before trying it. There are three kinds of fault, and the last
two are the reason this list exists:

- **Ends at start**: LDS stops, with a line in its log that starts with `Exception in thread "main"`. In
  `[@6:3]` the numbers are line and column in the file.
- **Hangs**: LDS writes an exception to its log and neither ends nor answers on its port. The container stays
  "running" and turns unhealthy. Stop it.
- **Starts, and does not do what the model says**: no message at all. Only this list, or a test write, finds it.

Then run `scripts/try-model.sh` on the folder; it reports the first two kinds and the JSON Schema files LDS lists
but cannot use. Test writes find the third kind.

## GraphQL schema

### Ends at start

| Look for | Message | Fix |
|---|---|---|
| A directive used and not declared | `SchemaProblem{errors=['A' [@3:1] tried to use an undeclared directive 'domain']}` | Add both `directive` lines of [graphql-schema.md](graphql-schema.md#the-file). |
| An argument the declaration lacks | `SchemaProblem{errors=['b' [@6:3] use an unknown argument 'pagination' on directive 'link']}` | Use the declarations as written there. |
| No type `Query` | `SchemaProblem{errors=[A schema MUST have a 'query' operation defined]}` | Add `type Query {` and `}`. |
| A scalar LDS does not know (`Long`, `JSON`, `BigDecimal`) | `SchemaProblem{errors=[There is no scalar implementation for the named  'Long' scalar type]}` | Use `Float`, `Int`, `String` or `ID`. |
| `DateTime`, `Date` or `Time` used, no `scalar` line; or a type named that is not in the file | `SchemaProblem{errors=[The field type 'DateTime' is not present when resolving type 'A' [@5:1]]}` | Add `scalar DateTime`; or define the missing type, or fix its spelling. |
| A fault in the writing, such as `name String` | `SchemaProblem{errors=[[@5:16] The schema definition text contains a non schema definition language (SDL) element 'OperationDefinition']}` | The place is the `{` of the type; the fault is in a line below it. |
| An embedded type that holds itself, also through a list | `java.lang.StackOverflowError` | Make it an entity type with `@link` to itself. |
| The file is not there | `java.lang.RuntimeException: java.nio.file.NoSuchFileException: schemas/schema.graphql` | See [Loading](#loading). |
| No type with `@domain` | Not at once: `No schemas configured!`, then `Persistence provider initialization attempt # 1`, `# 2`, ... once a second, and after 60 seconds an end with `java.lang.NullPointerException`. | Mark at least one type `@domain`. |

### Hangs

| Look for | Message in the log | Fix |
|---|---|---|
| A link to an interface: `friends: [Named] @link` | `java.lang.ClassCastException: class graphql.schema.GraphQLInterfaceType cannot be cast to class graphql.schema.GraphQLObjectType ...` | Link to a union of the entity types. |
| An entity type whose name ends in `Connection`, or a type named as an entity type plus `Edge` (`BookEdge`) | `java.lang.NullPointerException: Cannot invoke "graphql.schema.GraphQLFieldDefinition.getType()" ...` | Rename the type. |
| A type named `PageInfo` that a list of links points at | `graphql.AssertException: All types within a GraphQL schema must have unique names. ...` | Rename it. |
| A type named `SearchResult` while search is on | `java.lang.IllegalArgumentException: type map already contains SearchResult` | Rename it. Also avoid `TypeFilters`, which search makes too. |
| `@reverseLink` declared and written in the model | `java.lang.IllegalArgumentException: json-path must start with '$' in its first element` | Remove it; use `reverseName` on the `@link`. |

### Starts, and does not do what the model says

| Look for | What LDS does | Fix |
|---|---|---|
| The same list type twice in one type: `a: [String]`, `b: [String]` | Only the first by field name is a list; the other must be a single value: `$.b: array found, string expected`. | Vary the spelling: `[String]`, `[String!]`, `[String]!`, `[String!]!` are four list types, each checked. |
| Two lists of one embedded type in one type: `chapters: [Chapter]`, `appendices: [Chapter]` | Only the first by field name is checked. | Give the second its own type: `appendices: [Appendix]`. |
| One enum on two fields of one type: `earlier: State`, `state: State!` | Only the first by field name is checked. | A second enum with the same names: `earlier: PreviousState`. |
| A list of an enum: `states: [State]` | An array is refused (`$.states: array found, string expected`). | `[String]`, with the names in the description. |
| A list of lists: `[[String]]` | Read as `[String]`. | Flatten it, or use a list of an embedded type that holds a list. |
| A list of another scalar: `[Int]`, `[DateTime]` | Must be an array; the items are not checked. | Tell the user; JSON Schema files check items. |
| A single embedded field: `address: Address` | Not checked at all (`"address": 7` is written). Fields under it cannot be addressed by a path. | A list (`addresses: [Address]`) is checked; or JSON Schema files. Tell the user. |
| `Int`, `Float`, `ID`, `Date`, `Time` fields | Not checked on write. GraphQL answers a wrong value, and an `Int` above 2147483647, with `null` and an error. | Tell the user; `Float` for large whole numbers. |
| A field whose type is an entity type, without `@link`: `owner: Person` | Read as a link (an object there is refused with `$.owner: object found, string expected`), with no reverse link. | Write `@link`. |
| A list of an entity type without `@link`, when that entity type has a link of its own: `support: [Support]` | Holds one object, not a list (`$.support: array found, object expected`); GraphQL answers it with `null` and `type mismatch error, expected type LIST`. | `@link` for a link; an embedded type for a part. |
| `!` inside a list of links: `[Book!] @link`, `[Book!]! @link` | When `Book` has a link of its own, every array is refused: `$.works: array found, object expected`. | `[Book] @link` or `[Book]! @link`. |
| `@link` on a field whose type is embedded: `cover: Cover @link` | Wants a text `/Cover/x`, which points at nothing; an object is refused. | Remove `@link`. |
| A field whose type is an interface: `known: Labelled` | The field is not checked, and the fields of the interface become fields of the entity type (with `label: String!`, every document needs `label`). | Use a union with `@link`, or an embedded type. |
| A field with an argument that has `!`: `title(upper: Boolean!): String`; or a directive of one's own declared with such an argument | The field becomes required. | No arguments on fields; no directives but `@domain` and `@link`. |
| `reverseName` equal to a field of the target: `reverseName: "name"` | In GraphQL the target's own `name` is replaced by the reverse link. | Choose a name the target does not have. |
| A link inside a list of embedded objects (`lines: [OrderLine]` with `product: Product @link`) | Not checked on write; the reverse link was answered empty. | An entity type for the part when the link matters. |
| A `null` the user's data has in a checked field | Refused: `$.email: null found, string expected`. | Leave the field out of the document. |
| `@domain(searchable: false)` | No effect. | Remove it, or leave it. |
| `@deprecated` on a field | No effect. | |

## JSON Schema files

### Ends at start

`T` and `Old` stand for the entity type of the file.

| Look for | Message | Fix |
|---|---|---|
| `$schema` of another draft | `java.lang.IllegalArgumentException: The JSON Schema of Old says it is "http://json-schema.org/draft-04/schema#". LDS reads JSON Schema 2020-12: https://json-schema.org/draft/2020-12/schema` | `https://json-schema.org/draft/2020-12/schema`, or no `$schema`. |
| `definitions` at the root | `java.lang.IllegalArgumentException: The JSON Schema of Old has "definitions", which is the keyword of an earlier draft. In JSON Schema 2020-12 it is "$defs".` | Rename to `$defs`, and every `#/definitions/` to `#/$defs/`. |
| A `$ref` to a definition that is not there | `java.lang.IllegalStateException: Referenced definition does not exist: #/$defs/Missing` | Add the definition to this file. |
| A `$ref` to another file | `java.lang.IllegalStateException: $ref present but unable to resolve: other.json` | A link field for an entity type; a copy in `$defs` for an embedded type. |
| The root `$ref` names a definition that is not there | `com.networknt.schema.InvalidSchemaRefException: Reference /$defs/Missing cannot be resolved` | Name the definition of the file. |
| The file is not a JSON object | `java.lang.IllegalArgumentException: In the JSON Schema of T, the schema is not an object` | |
| A property that is not an object: `"a": "text"` | `java.lang.IllegalArgumentException: In the JSON Schema of T, a is not an object` | `"a": {"type": "string"}`. |
| `required` that is not an array | `java.lang.IllegalArgumentException: In the JSON Schema of T, required of T is not an array` | `"required": ["a"]`. |
| The file is not JSON | `java.lang.RuntimeException: tools.jackson.core.exc.StreamReadException: Unrecognized token 'this': ...` | Check with `jq . T.json`. |
| A definition that holds itself through `$ref` | `java.lang.StackOverflowError` | Make it an entity type and link it. |
| A file or folder named in `LDS_SPECIFICATION_SCHEMA` is not there | `java.lang.IllegalArgumentException: Unable to find resource: schemas/Nobody.json` | See [Loading](#loading). |

### Starts, and does not work

| Look for | What happens | Fix |
|---|---|---|
| No `$defs`: `type` and `properties` at the root | Listed in `?schema`; every request to it is answered `400 Not a managed resource name: "T"`. | Move the schema into `$defs` and add the root `$ref`. |
| The definition has another name than the file (`Person.json` with `Human`) | The same for `Person`; a write to `/ns/Human/1` is answered `500` with no body. | Name the definition as the file. |
| `$defs` but no root `$ref` | Documents are not checked at all: `{}` is written although a field is required. | Add `"$ref": "#/$defs/T"`. |
| A definition of one name in two files, with different content | A document is checked by its own file, its links by the file LDS read first, in an order that is not fixed. | Give such definitions the same content in every file. |
| A file named in `LDS_SPECIFICATION_SCHEMA` whose name does not end in `.json` (`Firm.schema`) | The last five characters are cut: the entity type is `Firm.s`. | Name files `<Entity>.json`. |
| A word that is not JSON Schema (`"shownAs"`) | A warning at start: `Unknown keyword shownAs - you should define your own Meta Schema. ...` | Remove it, or use `displayName`. |
| A link inside a list of embedded objects | Not checked. | Tell the user. |
| No `.json` file in the folder, or only in subfolders | `No schemas configured!`; LDS runs with no entity type, `?schema` gives `[]`. | Put the files in the folder itself. |

## Loading

| Look for | What happens | Fix |
|---|---|---|
| A GraphQL schema in the folder not named `schema.graphql` | `java.nio.file.NoSuchFileException: schemas/schema.graphql` | Rename it, or `-e LDS_GRAPHQL_SCHEMA=schemas/<file>.graphql`. |
| JSON Schema files without `LDS_GRAPHQL_SCHEMA=` (empty) | LDS looks for `schemas/schema.graphql`: the same message. | Add `-e LDS_GRAPHQL_SCHEMA=`. |
| Both a `schema.graphql` and `.json` files | Only the GraphQL schema is read. | Keep one form in the folder. |
| A space after a comma in `LDS_SPECIFICATION_SCHEMA` | `Unable to find resource:  schemas/Book.json` (two spaces). | No spaces: `schemas/Author.json,schemas/Book.json`. |
| A changed model, LDS not restarted | The old model is still in use. | Restart LDS; check `?schema`. |
| The name of an embedded type in an address: `/ns/Address` | `GET` is answered `200 []`, `?schema` and a `PUT` with `500`. | Only entity types have addresses. |

## From a message to the fix

| The log or the answer says | Section |
|---|---|
| `SchemaProblem{...}` | [GraphQL, ends at start](#ends-at-start) |
| `StackOverflowError` | A type that holds itself, in either form |
| `No schemas configured!` | No entity type: no `@domain`, or no `.json` file in the folder |
| `ClassCastException`, `NullPointerException`, `AssertException`, `type map already contains`, `json-path must start with '$'`, and no answer on the port | [GraphQL, hangs](#hangs) |
| `The JSON Schema of ...`, `Referenced definition`, `$ref present but unable to resolve`, `InvalidSchemaRefException`, `Unable to find resource` | [JSON Schema, ends at start](#ends-at-start-1) |
| `NoSuchFileException` | [Loading](#loading) |
| `400 Not a managed resource name: "T"` for a type listed in `?schema` | [JSON Schema, starts and does not work](#starts-and-does-not-work) |
| `400 Not a managed resource name` for a type not listed | The name is not in the model, or its case differs (`/ns/member`). |
| `Schema validation error: [...]` on a write | The document does not fit; `lds:data` explains it to the user. If the model is wrong, the rows above. |
