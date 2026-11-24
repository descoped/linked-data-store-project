# The interview

For a user who describes a domain in their own words, or has nothing written down. Ask in plain words, a few
questions at a time, and write down the answers as a list of things, their facts and how they point at each other.
Do not mention GraphQL, JSON Schema, directives or entity types to the user unless they use such words first; say
"thing", "record", "part of", "points at".

## Round 1: the things

1. "What are the things you want to keep track of? Name them as you would to a colleague." Collect nouns: books,
   members, loans.
2. For each: "Do you look one up on its own, list them, or change one without touching anything else?" and "Do
   several other things refer to the same one?" Yes to either: it is a record of its own (an entity type). No to
   both, and it only ever lives inside one other thing: it is a part (an embedded type).
3. "How do you tell two of them apart? Is there a number or code people already use?" That becomes the id in the
   address (`/ns/Member/M-17`). An id is any text without a `/` or a control character, of at most 1024 bytes in
   UTF-8; ASCII letters, digits, `-`, `_` and `.` are the easiest to write in an address. If there is no natural
   key, suggest one the user's system can make (a UUID, a running number).

## Round 2: the facts of each thing

For each thing, ask: "What do you write down about one of them?" For each fact:

| Ask | Model |
|---|---|
| "Is it a word or a text?" | `String` |
| "A yes/no?" | `Boolean` |
| "A moment in time (date and clock time)?" | `DateTime`, written `2026-11-20T10:00:00Z` |
| "A calendar date only?" | `Date`, written `2026-11-20` |
| "A clock time only?" | `Time`, written `10:00:00Z` or `10:00:00+01:00` |
| "A number? Whole or with decimals? Can it be above about two billion?" | `Int` for whole numbers up to 2147483647; `Long` for larger whole numbers; `Float` for decimals |
| "Is it one of a fixed set of values?" Ask for the list. | an enum |
| "Can there be several?" | a list: `[String]`, `[Address]` |
| "Must every one have it?" | `!` (required) |
| "Is it itself made of several facts (street, city)?" | an embedded type |
| "Is it another thing from round 1?" | a link (round 3) |

Leave out facts that only say when the record was made or changed, or whether it is deleted: LDS keeps every
version with the time it holds from (round 4).

Ask "Must every one have it?" with care: a required field must be in every document written, also in old data that
lacks it, and a field sent as `null` counts as missing. When in doubt, leave it optional.

## Round 3: how things point at each other

1. For each pair of things that relate: "Does a loan point at one member, or at several?" One: a link field. Several:
   a list of links.
2. "Which side would you write it on?" Put the link on the side that has one (a loan has one member), not on the
   side with many: the many side is answered by a reverse link in GraphQL. Ask what to call it from the other side
   ("a member's loans"): that is the `reverseName`.
3. "Can it point at different kinds of things?" (a rights holder that is an author or a publisher): a link to a
   union.
4. "Does the relation itself have facts, such as dates or a role?" Yes: the relation is a thing of its own (a loan,
   a membership), with a link to each side.
5. "Do you need to ask from the other end?" When a link sits inside a part (an order line inside an order) and the
   user wants "which orders hold this product", make the part a thing of its own, or put the link on the order:
   a reverse link through a part was answered empty.
6. Say once, plainly: "LDS does not check that the thing pointed at exists. You can write them in any order, and a
   link can point at something that only exists from a later time."

## Round 4: time

1. "Do these things change over time, and will you want to see one as it was on a given day?" LDS always keeps
   every version, valid from a time the writer gives. So a member's new address is a new version of the member, not
   a list of addresses with from and to dates.
2. "Do you have history today, in a history table or with from/to dates?" It is loaded as versions at their times
   (`lds:data`), not modelled as fields.
3. "Are there dates that mean something in your world?" (born, published, due): those are fields.

## Round 5: search and use

1. "Do you need to find records by words in a text?" Search finds a record by every text in it; it needs a GraphQL
   schema, search turned on and Solr. "Is the text in a language, such as a description?" Model it as `[Text]`
   with `languageCode` and `languageText`, so search finds other forms of its words (see
   [graphql-schema.md](graphql-schema.md#search)). "Are there kinds of records no one searches for?" Mark them
   `@domain(searchable: false)`.
2. "Will programs query this, following links both ways?" Yes: a GraphQL schema. "Must every value be checked
   strictly, with limits such as a smallest number, and is querying less important?" Then JSON Schema files. Say what
   each gives up ([SKILL.md](../SKILL.md#the-two-forms)).
3. "Is there anything that must be unique across records, or computed from other records?" No model can say that;
   the program that writes must see to it.

## Write it back

Before writing any file, give the user the result in plain lines, one per thing:

```
Member (a record, id = member number): name (required), email, joined (date), status (active or suspended),
  addresses (a list of parts: street, city).
Loan (a record): points at one Copy and one Member; lent, due (required), returned; renewals (a list of parts).
```

Then go on with step 3 of the workflow in [SKILL.md](../SKILL.md#workflow).
