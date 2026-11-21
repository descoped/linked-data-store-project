# Clients

A small function that sends one query to LDS, in TypeScript, Python and Java, each with no library beyond what
the language comes with. Each was run against `descoped/lds:3.0.0` as it is written here (TypeScript with Node 26,
Bun and `tsc --strict`; Python 3.13; Java 27 as a single source file).

To fit them to the user:

- Take the query from what was written and tried with `scripts/query.sh`, and the type of the answer from the
  schema LDS made (`scripts/schema.sh`): the user's entity types and fields, not `Person`.
- Keep the values of the user out of the query text: declare variables (`$id: ID!`) and send them in
  `variables`. That way an id with a quote in it cannot break the query.
- Keep `charset=utf-8` in the content type. Without it LDS reads the body as ISO-8859-1, and an id or a text with
  a letter outside ASCII is not found. Most HTTP clients leave the charset out unless told.
- Look at `errors` as well as the status. LDS answers `200` when a query is wrong or a field fails, with the
  reason in `errors` and what could be read in `data`. These snippets treat any error as a failure; a program
  that should keep the partial `data` (a document without its link field gives an error for that field,
  see [query-shapes.md](query-shapes.md#links)) checks the `path` of each error instead.
- An as-of read is the query parameter `snapshot`, a time with `Z`. LDS answers a time it cannot read as of now,
  without an error ([time-machine.md](time-machine.md#the-rules-of-the-time)), so build the time from a date type
  of the language rather than from text the user typed.
- LDS has no authentication, so there is no token to send. Do not put an LDS that has data of worth where others
  can reach it (`lds:operate`).

The examples ask for a person and the name of the employer, now and as of 2021, with the example model.

## TypeScript (fetch)

`fetch` is in Node 18 and later, Bun, Deno and every browser. A page served from another origin than LDS is
answered by the CORS rules of LDS (https://lds.descoped.io/docs/user/rest-api/#cors).

```ts
const LDS = "http://localhost:9090";

type GraphQLAnswer<T> = { data?: T | null; errors?: { message: string; path?: (string | number)[] }[] };

export async function ldsQuery<T>(
  query: string,
  variables: Record<string, unknown> = {},
  snapshot?: string,
): Promise<T> {
  const url = new URL("/graphql", LDS);
  if (snapshot) url.searchParams.set("snapshot", snapshot); // an as-of read; write the time with Z
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ query, variables }),
  });
  if (!response.ok) throw new Error(`LDS answered ${response.status}`);
  const answer = (await response.json()) as GraphQLAnswer<T>;
  if (answer.errors?.length) throw new Error(answer.errors.map((e) => e.message).join("\n"));
  return answer.data as T;
}

type Person = { name: string; employer: { name: string } | null };

const query = `
  query One($id: ID!) {
    PersonById(id: $id) {
      name
      employer { name }
    }
  }`;

const now = await ldsQuery<{ PersonById: Person }>(query, { id: "ola" });
const then = await ldsQuery<{ PersonById: Person }>(query, { id: "ola" }, "2021-06-01T00:00:00Z");
console.log(now.PersonById, then.PersonById);
```

```
{ name: 'Ola Nordmann', employer: { name: 'Globex' } } { name: 'Ola', employer: { name: 'Acme' } }
```

`new Date(...).toISOString()` gives a time LDS reads (`2021-06-01T00:00:00.000Z`).

## Python (urllib)

The standard library only; nothing to install.

```python
import json
import urllib.parse
import urllib.request

LDS = "http://localhost:9090"


def lds_query(query, variables=None, snapshot=None):
    url = LDS + "/graphql"
    if snapshot:  # an as-of read; write the time with Z
        url += "?" + urllib.parse.urlencode({"snapshot": snapshot})
    body = json.dumps({"query": query, "variables": variables or {}}).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(request) as response:
        answer = json.load(response)
    if answer.get("errors"):
        raise RuntimeError("\n".join(e["message"] for e in answer["errors"]))
    return answer["data"]


QUERY = """
query One($id: ID!) {
  PersonById(id: $id) {
    name
    employer { name }
  }
}
"""

now = lds_query(QUERY, {"id": "ola"})
then = lds_query(QUERY, {"id": "ola"}, snapshot="2021-06-01T00:00:00Z")
print(now["PersonById"], then["PersonById"])
```

```
{'name': 'Ola Nordmann', 'employer': {'name': 'Globex'}} {'name': 'Ola', 'employer': {'name': 'Acme'}}
```

`urlopen` raises `HTTPError` for a status other than 2xx (a `415` or a `500`). A time from
`datetime.now(timezone.utc).isoformat()` ends in `+00:00`, which LDS reads; `urlencode` writes its `+` as `%2B`.

## Java (java.net.http)

The JDK only. The project's own JSON library (Jackson, Gson) should write the body and read the answer; here a
small function writes the query as a JSON text so the example stands alone, and the answer is printed as LDS sent
it.

```java
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class LdsQuery {

    static final String LDS = "http://localhost:9090";
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    // Sends one query and gives the answer as JSON text: {"data": ..., "errors": [...]}.
    static String ldsQuery(String query, String variablesJson, String snapshot) throws Exception {
        String url = LDS + "/graphql";
        if (snapshot != null) { // an as-of read; write the time with Z
            url += "?snapshot=" + URLEncoder.encode(snapshot, StandardCharsets.UTF_8);
        }
        String body = "{\"query\": " + jsonText(query) + ", \"variables\": " + variablesJson + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("LDS answered " + response.statusCode());
        }
        return response.body();
    }

    static String jsonText(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return b.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        String query = """
                query One($id: ID!) {
                  PersonById(id: $id) {
                    name
                    employer { name }
                  }
                }
                """;
        System.out.println(ldsQuery(query, "{\"id\": \"ola\"}", null));
        System.out.println(ldsQuery(query, "{\"id\": \"ola\"}", "2021-06-01T00:00:00Z"));
    }
}
```

```sh
java LdsQuery.java
```

```
{"data":{"PersonById":{"name":"Ola Nordmann","employer":{"name":"Globex"}}}}
{"data":{"PersonById":{"name":"Ola","employer":{"name":"Acme"}}}}
```

The variables are JSON text here too; with a JSON library, build them as an object so a value cannot break them.
`Instant.parse("2021-06-01T00:00:00Z").toString()`, or `Instant.now().toString()`, gives a time LDS reads.
