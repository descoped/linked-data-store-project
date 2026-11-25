# Refused writes: what LDS said, what it means, the fix

Every row was caused on `descoped/lds:3.0.1` with the example model (`Person` has `name` required, `born` a
date-time, `address` an embedded object, `employer` a link to `Company`, `friends` a list of links to `Person`).
Replace the names with those of the user's model. Depth:
https://lds.descoped.io/docs/user/model/#what-lds-checks-when-a-document-is-written

## How to use this

1. Get the status and the body: `curl -s -w '\n%{http_code}\n' ...`. A 400 and a 415 have a text (one 400 has
   none, below); a 404 and a 500 never have one.
2. Find the row. Tell the user the plain meaning, then the fix, in their words and with their field names.
3. A 400, 404 or 415 wrote nothing. A 500 may have written, or may still write later (see the last section).

## Reading a `Schema validation error`

LDS checks a document in two steps, and the text tells which step refused it:

- `Schema validation error: [ ... ]`: the JSON Schema of the entity type found faults in values. Every fault
  is listed, separated by commas: the place, a colon, what is wrong. `$` is the document, `$.born` a field,
  `$.about[0]` the first item of a list, `$.about[0].languageText` a field in it.
- `Schema validation error: <reason>. Navigation: <field>: "<value>"`: the values were fine, and this is the
  **first** wrong link. Fix it and send again: there may be another.

Get the schema of the entity type to explain a fault: `curl -s "$LDS/ns/Person?schema"`. `required` lists the
fields that must be there; a `type` and a `format` say the kind of value; a sibling `_link_property_<field>`
names the entity types the field may link to.

## Faults in the document (400)

| LDS answered | In plain words | Fix |
|---|---|---|
| `[$.name: integer found, string expected]` | `name` must be a text, and a number was sent | Send `"name": "7"`, or fix the data |
| `[$: required property 'name' not found]` | Every `Person` must have a `name` | Add the field; if the data truly lacks it, the model must change (`lds:model`) |
| `[$: array found, object expected]` | The body was a list; a document is one JSON object | Send each item as its own document, at its own id |
| `[$.born: does not match the date-time pattern must be a valid RFC 3339 date-time]` | A date-time needs a date, `T`, a time with seconds, and `Z` or an offset. `yesterday`, `2026-11-20` and `2026-11-20T10:00:00` are refused | Send `2026-11-20T10:00:00Z` (or `...+01:00`) |
| `[$.friends: string found, array expected]` | `friends` holds a list of links, and one text was sent | Send `["/Person/ola"]` |
| `Not a valid link. Navigation: employer: "acme"` | A link is the text `/Entity/id`; `acme` lacks `/Company/` | Send `"/Company/acme"`. Also refused: `""`, `/ns/Company/acme` (no namespace in a link), `/Company/a/b` |
| `Not a valid link. Navigation: friends: "Person/kari"` | One item of the list of links lacks the leading `/` | Send `"/Person/kari"` |
| `Illegal linked-domain: "Person". Navigation: employer: "/Person/ola"` | `employer` may only point at a `Company` | Point it at an entity type the model allows, or change the model (`lds:model`) |
| `Payload was empty!` | The body was empty, or only spaces | Send the whole document as the body |
| `The body cannot be read at line 1, column 12: Unexpected end-of-input: expected close marker for Object. Send the body as JSON, with Content-Type: application/json` | The body is not JSON: here a `}` is missing at the end | Check it with `jq . file.json`; send one JSON value |
| `The body cannot be read at line 1, column 14: Unrecognized token 'x': ...` | Something comes after the JSON value | Send one JSON value and nothing after it |
| `Id was empty!` | The address has no id: `PUT /ns/Person` | Put the id last: `/ns/Person/ola` |
| `Managed resource id cannot be empty.` | The id was only spaces | Choose a real id |
| `An id cannot hold a '/': "a/b"` | The id has a `/`, also when it is sent as `%2F` | Choose an id without `/` |
| `An id is at most 1024 bytes in UTF-8` | The id is too long (a letter outside ASCII takes two to four bytes) | A shorter id |
| `An id cannot hold a control character` | The id has a line break, a tab or another control character | Take it out |

Two faults at once come in one answer (`{"born": "yesterday"}`):

```
Schema validation error: [$.born: does not match the date-time pattern must be a valid RFC 3339 date-time, $:
required property 'name' not found]
```

(one line in the answer, broken in two here).

A link to a document that does not exist is **not** a fault: LDS is a time machine, and the target may be
written later or may have existed earlier. Do not "fix" such links.

## Faults in the address or the time (400)

| LDS answered | In plain words | Fix |
|---|---|---|
| `The 'timestamp' query-parameter must follow the ISO-8601 standard. ...` | The valid time could not be read. It needs date, hours and minutes, and `Z` or an offset; a `+` must be `%2B` in a URL | `timestamp=2021-06-01T00:00:00Z` or `timestamp=2021-06-01T00:00:00%2B01:00`. The `+` in the example of the message must be sent as `%2B` too |
| `Unsupported namespace: "other"` | This LDS serves one namespace, and the address named another | Use the namespace of this LDS (`ns` unless configured) |
| `Not a managed resource name: "person"` | No entity type of that name; names are case-sensitive | Use the name as in `GET /ns?schema` (`Person`) |
| `Not a valid path element: "nosuch"` | No such field at that place in the model | Check the field name in `GET /ns/Person?schema` |
| `Not a valid path element: "city"` on `/ns/Person/ola/address/city` | With a GraphQL schema as model a path stops at an embedded object | Write the whole part: `PUT /ns/Person/ola/address` with `{"street": "...", "city": "..."}` |
| `Unsupported managed resource method: PATCH` (also `embedded`, `reference`) | LDS has no `PATCH`; a write is always of the whole document or of one whole part | `PUT` the whole document, or `PUT` the part |
| `Reference resource does not support link to managed-domain "Person". Must be one of: [Company]` | The link address names an entity type the field may not point at | Use one from the list |
| `Reference resource path-element: "employer" must be part of pattern ending like: ...` | The link address lacks the target id | `/ns/Person/kari/employer/Company/acme` |
| `Resource path cannot navigate through REF property: "employer"` | A path cannot go through a link into the target | Read the target at its own address: `/ns/Company/acme/name` |
| 400 with no body | The address has a `%` not followed by two hex digits | Percent-encode the id properly (`jq -rn --arg id "$ID" '$id\|@uri'`) |

## No document at that time (404, no body)

| Request | In plain words | Fix |
|---|---|---|
| `PUT`/`POST`/`DELETE` of a part, `PUT`/`POST` of a link | A part or a link can only be written into a document that exists at the time of the write, and there is none then (never written, deleted then, or the `timestamp` is before its first version) | Write the whole document first, or give a `timestamp` at which it exists. Check with `?timeline` |

## Not JSON (415)

| LDS answered | In plain words | Fix |
|---|---|---|
| `Unsupported Content-Type: application/x-www-form-urlencoded. Send the body as JSON, with Content-Type: application/json` | `curl -d` without `-H 'Content-Type: application/json'` | Add the header |
| `Unsupported Content-Type: text/plain. ...` | Another content type | Send JSON with the JSON content type |

## No answer text (500)

LDS gives no reason in the answer. The reason is in the log of LDS (`docker logs <container>`); the lines below
are what the log said for each.

| Cause | Log says | Fix |
|---|---|---|
| The address names an embedded type, not an entity type (`/ns/Address/a1`) | `No JSON Schema for the entity Address` | Embedded objects are written inside their document |
| The saga of the write failed (an engine away, a stop) | `Observed Saga failure during execution` | Send the same request again; see `lds:operate` |
| The write waited 10 seconds for its turn | | Send fewer writes at one time, then send it again |

After a 500 from a failed saga the document may be written already, or may be written later when LDS runs the
saga again. A request with a `timestamp` can be sent again safely: it writes the same version.

## Not refused, though a user may expect it

- A field the model does not have is stored, unless a JSON Schema model forbids extra fields.
- A field written as `null` is left out of the document: `{"name": "A", "employer": null}` is written as
  `{"name": "A"}`. A `null` in a required field counts as missing.
- A link to a document that does not exist is stored (by design).
- With a GraphQL schema as model, a single embedded object is not checked: `"address": 7` is accepted.
- A `source` other than letters, digits, `_`, `.`, `-` is accepted with the image as it comes; it fails with
  500 only when the transaction log is kept per source.
- `sync=false` does not skip the check: an invalid document is still answered 400.
