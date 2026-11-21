# Exporting the documents of an entity type at an instant

Read this when the user wants the data out of LDS: a file of all customers as they were on a date, a CSV for
a spreadsheet, a copy before a change. The exporter is `scripts/lds-export.sh` in this skill's directory. Every
run below was against `descoped/lds:3.0.0`.

## Why it is a script and not one request

`GET /ns/<Entity>?timestamp=<instant>` gives the documents as of that instant, but **without their ids** and
with no pages. An export without ids is rarely what a user wants. The GraphQL API lists the ids (the cursor of
each edge is the id), as of an instant (`snapshot`). So the script asks GraphQL for the ids, page by page, and
then reads each document over REST at the same instant. One fixed instant is used for both, so the export is
one consistent snapshot even while others write: when no instant is given it takes "now" once, at the start.

## Run it

```sh
export LDS=http://localhost:9090 NS=ns
X="<skill dir>/scripts/lds-export.sh"
sh "$X" Person 2020-06-01T00:00:00Z json > persons.json
sh "$X" Person 2020-06-01T00:00:00Z csv > persons.csv
sh "$X" Company > companies-now.json
```

- The instant is in UTC and ends in `Z`. Convert a local time first (`2020-06-01T00:00:00+02:00` is
  `2020-05-31T22:00:00Z`): the GraphQL API does not read other offsets correctly, and the script refuses them.
- It needs `curl` and `jq` and says so when one is missing.
- Documents that are deleted at the instant, or not yet written, are not in the export.

JSON, in the order of the ids (the first two documents):

```json
[
  {
    "id": "anne",
    "document": {
      "employer": "/Company/acme",
      "name": "Anne",
      "address": {"city": "Bergen"}
    }
  },
  {
    "id": "bo",
    "document": {"name": "Bo"}
  }
]
```

CSV: a header row, then one row a document. Nested fields become `address.city`, a list (of links, of embedded
objects) is one cell of JSON text, and a field named `id` in the document becomes `document.id` so it does not
clash with the id of LDS (the first rows):

```
"id","about","address.city","address.street","born","employer","name"
"anne",,"Bergen",,,"/Company/acme","Anne"
"bo",,,,,,"Bo"
"id with space","{}",,,,,"Spaced, ""quoted"""
"kari",,,,"1990-05-17T00:00:00Z",,"Kari"
"ola",,"Bergen","Storgata 1",,"/Company/acme","Ola"
```

Columns are the union of the fields of all documents, sorted after `id`; a document without a field has an
empty cell.

## Speed

One request per document: 505 documents took 16 seconds (memory provider, a laptop). For the documents alone,
without ids, one request is enough: `curl -s "$LDS/ns/Person?timestamp=2020-06-01T00:00:00Z" > persons.json`.

## Limits

- **A model of JSON Schema files has no GraphQL API** (`/graphql` answers 400 `Unsupported namespace:
  "graphql"`). The script then says so and exports the documents with `"id": null`. Ids are then only
  available when the documents hold their own key in a field.
- **With the memory provider**, documents whose id begins with `~` or a letter outside ASCII are left out of
  every list, GraphQL included, so they are not exported. They can be read one by one with their id.
- **The history** is not in an export at an instant. For every version of one document use
  `GET /ns/<Entity>/<id>?timeline`; for an export at several dates run the script once per date.
- **Without jq**, read the JSON list with whatever the user has (Python's `json` module) or, for a few
  documents, read the answer yourself.
