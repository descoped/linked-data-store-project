# Bulk loading existing data into LDS

Read this when the user has data elsewhere (CSV, JSON, a database export, another system) and wants it in LDS.
The loader is `scripts/lds-load.sh` in this skill's directory. Every step below was run against
`descoped/lds:3.0.0`.

## Why each record carries its valid time

LDS is a time machine: every version of a document holds from its valid time, and every read asks "as of
when". So the valid time is what a load must get right, for two reasons:

- **History.** Data that changed over time (an address that moved, a company renamed) becomes one version per
  change, each at the date it took effect. An as-of read then answers as the data was at any date.
- **Safe to run again.** A write with a `timestamp` writes the version of that time; sent again, it replaces
  that version with the same document. A write without `timestamp` takes the time it arrives, so each run would
  add a new version of every document. The loader therefore requires a valid time on every record.

## The steps

1. **Look at a sample** of the source (`head`, a few records) and at the model:
   `curl -s "$LDS/ns?schema"` and `curl -s "$LDS/ns/<Entity>?schema"`. Note required fields, date-time fields
   and link fields (a sibling `_link_property_<field>` names the targets).
2. **Map each source record to a document** of an entity type, with the user. Decide:
   - the **id**: a stable key from the source (a customer number, an org number), so the same thing always gets
     the same id. LDS makes no ids. Avoid `+` and `%` in ids (they cannot be used in LDS 3.0) and field names
     with a `.` (they come back as nested objects).
   - the **valid time** of each record: the date the record took effect ("valid from", "effective date",
     "changed at"). When the source holds only the current state, use one instant for all records, such as the
     date of the extract, and tell the user that history before it is not in LDS. A date alone becomes
     `2021-11-15T00:00:00Z`: LDS needs a time and `Z` or an offset.
   - **links**: a link is the text `/<TargetEntity>/<target id>`, made from the foreign key of the source. The
     targets need not be loaded first: LDS does not check that they exist, so the order of records does not
     matter.
   - **date-times** inside the document need `Z` or an offset too (RFC 3339).
   - **deletes**: a record that ended (a customer closed at a date) becomes a delete record at that date: a
     tombstone, after which the document is not there.
3. **Write the records as NDJSON**, one JSON object a line (examples below).
4. **Try three to five records first**, read them back (`?timeline`), and show the user. A mapping fault found
   now costs nothing; found after a load, the versions are there (no request removes a version; writing the
   same valid time again replaces it).
5. **Load everything** with the loader. It prints each refused record with the answer of LDS, and collects them
   in `<file>.failed.ndjson`.
6. **Fix and run the failed file**. Explain each refusal with [refused-writes.md](refused-writes.md). Running
   the whole file again is also safe.
7. **Check** with a count or an export at an instant: `scripts/lds-export.sh` (see SKILL.md).

## The record format

```json
{"entity": "Person", "id": "ola", "validFrom": "2016-01-01T00:00:00Z", "document": {"name": "Ola"}}
{"entity": "Person", "id": "per", "validFrom": "2022-01-01T00:00:00Z", "deleted": true}
```

- `entity`: the entity type, spelled as the model spells it.
- `validFrom`: the valid time; `Z` or an offset such as `+01:00` (the loader encodes the `+`).
- `document`: the whole document of that version. Each version is the whole document, not a change.
- `deleted: true` instead of `document` writes a tombstone at `validFrom`.
- Several versions of one document are several records with different `validFrom`, in any order.

## Run the loader

```sh
LDS=http://localhost:9090 NS=ns sh "<skill dir>/scripts/lds-load.sh" records.ndjson
```

It needs `curl` and `jq` and says so when one is missing; it checks `$LDS/ping` first. When LDS has an
`http.prefix`, put it in `LDS` (`http://localhost:9090/api`). Output, from a file with seven good records and
two bad ones (each refusal is one line; cut here):

```
400 http://localhost:9090/ns/Person/bad?timestamp=2020-01-01T00%3A00%3A00Z Schema validation error: [$.name: ...
400 http://localhost:9090/ns/Person/bad2?timestamp=2020-01-01 The 'timestamp' query-parameter must follow the ...
written: 7, not written: 2 (see people.failed.ndjson)
```

Run again, it wrote the same seven, and the timelines kept the same number of versions. A record that is not
JSON, has no `validFrom`, or has `+` or `%` in its id is skipped and put in the failed file before any request.

A 201 or 204 means the version is written (a whole-document write waits for its saga). A 500 means the saga
failed or waited too long; the record is in the failed file, and LDS may still complete it later. Sending it
again is safe.

**Speed.** One loader wrote 500 records in 14 seconds (memory provider, a laptop). For more, split the file
and run up to four loaders at once: 450 records, 150 documents with three versions each spread over four
parts, were written in under 5 seconds, every timeline complete. Writes of whole documents do not interfere,
also not versions of one document. LDS carries out at most 30 writes at one time with its defaults.

```sh
split -l 10000 records.ndjson part- && for f in part-*; do mv "$f" "$f.ndjson"; done
for f in part-*.ndjson; do LDS=http://localhost:9090 sh "<skill dir>/scripts/lds-load.sh" "$f" & done; wait
```

## Converting the source to NDJSON

Write the conversion for the user's data; these are patterns that were run.

**A JSON array** (`companies.json` holds `[{"orgnr": "910000001", "name": "Fjord Fish", "registered":
"2019-03-01"}, ...]`), with jq:

```sh
jq -c '.[] | {entity: "Company", id: .orgnr, validFrom: "\(.registered)T00:00:00Z",
  document: {name: .name, founded: "\(.registered)T00:00:00Z"}}' companies.json > companies.ndjson
```

**A CSV file** with a header, one row a version (`id,name,city,employer_id,valid_from`). Python's `csv` reads
quoted fields with commas correctly:

```sh
python3 -c '
import csv, json, sys
for r in csv.DictReader(open(sys.argv[1], newline="", encoding="utf-8")):
    doc = {"name": r["name"]}
    if r["city"]: doc["address"] = {"city": r["city"]}
    if r["employer_id"]: doc["employer"] = "/Company/" + r["employer_id"]
    rec = {"entity": "Person", "id": r["id"], "validFrom": r["valid_from"], "document": doc}
    print(json.dumps(rec, ensure_ascii=False, separators=(",", ":")))
' people.csv > people.ndjson
```

The same with jq alone, for a CSV with no quoted fields:

```sh
jq -R -c 'select(length > 0) | split(",")' people.csv | jq -c -s '
  .[0] as $h | .[1:][] | [$h, .] | transpose | map({(.[0]): .[1]}) | add
  | {entity: "Person", id: .id, validFrom: .valid_from,
     document: ({name: .name}
       + (if .city != "" then {address: {city: .city}} else {} end)
       + (if .employer_id != "" then {employer: "/Company/\(.employer_id)"} else {} end))}' > people.ndjson
```

Leave a field out when the source has no value: a `null` is refused for most fields
(`$.employer: null found, string expected`).

## What a load cannot do

- **Remove a version.** A version written by mistake can be replaced (same valid time) or followed by a
  tombstone, never removed. Running a corrected file again replaces the versions at the same valid times; a
  version whose valid time is no longer in the file stays.
- **Check that links lead somewhere.** That is by design; check the source data instead if it matters.
- **Find "the last record loaded".** `source` and `sourceId` can be sent with writes, but with the image as it
  comes the transaction log is not kept and `/source/<name>` always answers `{"lastSourceId":null}`. Resume by
  running the failed file, or the whole file, again.
- **Load several namespaces.** One LDS serves one namespace.
