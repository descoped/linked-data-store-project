# The time machine

LDS keeps every document through time. A write adds a version, valid from its valid time (the `timestamp` of
the write) until the next version; a delete adds an empty version, a tombstone. A query reads the **snapshot** of
one instant: every document in the version valid then, and every link, reverse link and search hit followed as of
then. This file is the rules of that time in the GraphQL API, and a tour to give the user on their own data.

The examples ran on the compose stack with the example model: the person `ola` has a version from 2020 (Ola,
Bergen, works at Acme, friend `kari`) and one from 2022 (Ola Nordmann, Oslo, works at Globex, friends `anne`,
`kari`, `per`); `per` is deleted from 2025; the company `globex` has a version written now for 2030.

## The rules of the time

- The time is the query parameter `snapshot`: `/graphql?snapshot=2021-06-01T00:00:00Z`, for GET and POST. Or the
  variable `__snapshot` in a JSON body, which the query does not declare (a query that declares it and does not use
  it is refused with `Unused variable '__snapshot'`). When both are there, `snapshot` wins.
  `scripts/query.sh file '' <time>` sets `snapshot`.
- One time for the whole request. Two times are two requests.
- Write the time as a date and a time with `Z`: `2021-06-01T00:00:00Z`. The `version` text of a timeline is
  taken as it is (`2022-01-01T00:00Z[Etc/UTC]`, with `[` and `]` written `%5B` and `%5D` in an address;
  `scripts/query.sh` does that).
- A version is read from its valid time on: at `2022-01-01T00:00:00Z` `ola` is Ola Nordmann, at
  `2021-12-31T23:59:59Z` still Ola.
- Before the first version, and from a delete until the next version, the document is not there: `XById` gives
  the error `The field at path '/PersonById' was declared as a non null type, ...` and `data` is `null`, and the
  document is not in lists, links or reverse links.
- A time in the future is allowed. A version written for the future is not read now; it is read with a
  `snapshot` at or after its time.
- The model is the same for every time: LDS reads one model at start and serves every snapshot with it.

Not finished: a `snapshot` that is not a time (`yesterday`, `2020-06-01` without a time, a time without `Z` or an
offset) is not refused: the query is answered as of now, without an error. A typo therefore looks like a correct
answer for today. Check the time you send, and say to the user which time the answer is for.

Not finished: a time with an offset other than `Z` can give the wrong version (with LDS in memory, half an hour
before a version, `+01:00` gave that version). Turn the user's local time into UTC and write it with `Z`. In an
address a `+` must be `%2B`, or it becomes a space and the time is not read; `scripts/query.sh` encodes it.

## The tour

Give it on the user's own data, one step at a time, saying what each answer shows. Use their entity types and
fields from `scripts/schema.sh`; the names below are the example model's.

### 1. Find a document with a history

The history of one document is its timeline, which the REST API gives (`lds:data` has the details): every
version, oldest first. GraphQL has no field for it. Ask the user which document they care about; when they do not
know, count the versions of the documents of an entity type:

```sh
for id in $(echo '{ Person { edges { cursor } } }' | RAW=1 scripts/query.sh - | jq -r '.data.Person.edges[].cursor')
do
  printf '%s %s\n' "$id" "$(curl -s "$LDS/ns/Person/$id?timeline" | jq length)"
done
```

```
anne 1
kari 1
ola 2
åse 1
```

A document deleted now is not in the list of now; ask for the list at an earlier `snapshot` to find it. An id with
letters outside ASCII or a `/` needs URL-encoding in the REST address (`lds:data`).

### 2. Show the timeline

```sh
curl "$LDS/ns/Person/ola?timeline"
```

```json
[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {
      "address": {"city": "Bergen"},
      "employer": "/Company/acme",
      "friends": ["/Person/kari"],
      "name": "Ola"
    }
  },
  {
    "version": "2022-01-01T00:00Z[Etc/UTC]",
    "document": {
      "address": {"city": "Oslo"},
      "born": "1980-02-03T04:05:06.123456Z",
      "employer": "/Company/globex",
      "friends": ["/Person/kari", "/Person/per", "/Person/anne"],
      "name": "Ola Nordmann"
    }
  }
]
```

`version` is the valid time in UTC. A tombstone is a version whose `document` is `{}`:

```json
[
  {
    "version": "2020-01-01T00:00Z[Etc/UTC]",
    "document": {
      "address": {"city": "Oslo", "street": "Storgata 1"},
      "employer": "/Company/acme",
      "name": "Per"
    }
  },
  {
    "version": "2025-01-01T00:00Z[Etc/UTC]",
    "document": {}
  }
]
```

Draw it for the user when it helps, as a Mermaid Gantt chart: one bar per version from its time to the next,
a `crit` bar for a deleted span, and `todayMarker off`.

The timeline holds links as they are stored, as text (`/Company/acme`). What they led to is the next step.

### 3. Read the document at each version, links and all

Read the same query at the time of each version. The links are followed at that time too, so the user sees the
employer as it was, not as it is.

```sh
for t in $(curl -s "$LDS/ns/Person/ola?timeline" | jq -r '.[].version'); do
  echo "$t"; scripts/query.sh ola.graphql '' "$t"
done
```

with `ola.graphql`:

```graphql
{
  PersonById(id: "ola") {
    name
    address { city }
    employer { name }
    friends { edges { cursor } }
  }
}
```

```
2020-01-01T00:00Z[Etc/UTC]
{
  "data": {
    "PersonById": {
      "name": "Ola",
      "address": {"city": "Bergen"},
      "employer": {"name": "Acme"},
      "friends": {
        "edges": [
          {"cursor": "kari"}
        ]
      }
    }
  }
}
2022-01-01T00:00Z[Etc/UTC]
{
  "data": {
    "PersonById": {
      "name": "Ola Nordmann",
      "address": {"city": "Oslo"},
      "employer": {"name": "Globex"},
      "friends": {
        "edges": [
          {"cursor": "anne"},
          {"cursor": "kari"},
          {"cursor": "per"}
        ]
      }
    }
  }
}
```

The answers are formatted here; jq prints every object over several lines.

### 4. What changed between two instants

LDS has no diff. `scripts/compare.sh` sends one query at two times and lists every value that differs: one line
for each, with `change` `changed`, `added` or `removed`. A list read in pages is compared by its cursors, so ask
for `cursor` in every `edges { }`; then a document that came or went shows as `added` or `removed`, not as every
later place shifting.

```sh
scripts/compare.sh ola.graphql 2021-06-01T00:00:00Z 2022-06-01T00:00:00Z
```

```
{"path":"PersonById.address.city","change":"changed","before":"Bergen","after":"Oslo"}
{"path":"PersonById.employer.name","change":"changed","before":"Acme","after":"Globex"}
{"path":"PersonById.friends.anne","change":"added","before":null,"after":null}
{"path":"PersonById.friends.per","change":"added","before":null,"after":null}
{"path":"PersonById.name","change":"changed","before":"Ola","after":"Ola Nordmann"}
```

Tell it in words: Ola moved from Bergen to Oslo, changed employer from Acme to Globex, took the name Ola Nordmann,
and gained two friends. For `added` and `removed` the cursor (the id) is in the path; `null` there only means the
query asked for no fields of that document.

When the document is missing at one of the times, `compare.sh` first prints the error of that time
(`{"at": "...", "error": "The field at path '/PersonById' ..."}`) and then every value as `added` or `removed`:
the document came into being or was deleted between the two.

### 5. The whole snapshot: what changed in an entity type

The same on a list compares two snapshots of an entity type: which documents came, went or changed, and what
links to them.

```graphql
{
  Company {
    edges {
      cursor
      node {
        name
        employees { edges { cursor } }
      }
    }
  }
}
```

```sh
scripts/compare.sh companies.graphql 2021-06-01T00:00:00Z 2026-11-21T00:00:00Z
```

```
{"path":"Company.acme.employees.ola","change":"removed","before":null,"after":null}
{"path":"Company.acme.employees.per","change":"removed","before":null,"after":null}
{"path":"Company.acme.name","change":"changed","before":"Acme","after":"Acme Corp"}
{"path":"Company.globex.employees.ola","change":"added","before":null,"after":null}
```

Ola left Acme for Globex, Per is deleted, and Acme was renamed. A deleted document is `removed`:

```sh
scripts/compare.sh persons.graphql 2024-01-01T00:00:00Z 2026-01-01T00:00:00Z
```

```
{"path":"Person.per.name","change":"removed","before":"Per","after":null}
```

This reads every document of the entity type twice, so for a large entity type ask for few fields.

### 6. The future

A version can be written for a time that has not come. `globex` has one for 2030:

```sh
scripts/compare.sh companies.graphql 2026-11-21T00:00:00Z 2031-01-01T00:00:00Z
```

```
{"path":"Company.globex.name","change":"changed","before":"Globex","after":"Globex Group"}
```

Now it is Globex; from 2030 it will be Globex Group, with no write needed then.

### What the tour cannot show

- When a version was written. LDS keeps the valid time only; the time of the write is not read by any query.
- A version that was replaced: a write at the exact valid time of a version replaces it, and the old one is gone.
- The model a version was written under. Every snapshot is served with the model LDS runs with now.
- Search at a time: hits are read at the time of the query, but a document is also found by a text that only
  another of its versions has ([query-shapes.md](query-shapes.md#search)).
