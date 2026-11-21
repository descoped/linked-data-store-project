# When LDS does not start

LDS has started when its log says `Server started in ...ms..` and `/ping` answers `200`. When the start fails, the
cause is one line that starts with `Exception in thread "main"`, printed with a stack trace; the wait script
prints it with its `Caused by` line. Find the message below, tell the user the meaning in plain words, and fix it.

To read the cause by hand: `docker logs lds 2>&1 | grep -E 'Exception in thread|^Caused by'` (in a stack:
`docker compose logs lds`). The full table of messages:
https://lds.descoped.io/docs/user/configuration/#when-lds-does-not-start.

## Contents

- Docker refuses to start the container
- The container ends at once (exit code 1)
- The container ends after a wait: an engine
- The container runs, and nothing answers
- It answers, and something else is wrong

## Docker refuses to start the container

| Message | Meaning | Fix |
|---|---|---|
| `Bind for 127.0.0.1:9090 failed: port is already allocated` | Another container or program has the port on the machine. Docker leaves the new container behind as `Created`. | `docker rm lds`, then start on another port: `-p 127.0.0.1:9191:9090`. In a stack: `LDS_PORT=9191 docker compose up -d --wait`. |
| `Conflict. The container name "/lds" is already in use` | A container of that name exists, running or stopped. | `docker ps -a --filter name=lds`; use it, or `docker rm -f lds` and start again. |
| `Cannot connect to the Docker daemon` | Docker is not running. | Start Docker Desktop, or the Docker service on Linux. |

## The container ends at once (exit code 1)

`docker ps -a` shows `Exited (1)`. These came within a second or two of the start.

| Message (after `Exception in thread "main"`) | Meaning | Fix |
|---|---|---|
| `SchemaProblem{errors=[... tried to use an undeclared directive 'domain' ...]}`, `A schema MUST have a 'query' operation defined`, `The field type 'DateTime' is not present ...` | The GraphQL schema of the model cannot be read. The numbers in `[@4:3]` are line and column. | Fix the model; `lds:model` knows what the file must hold (the directive declarations, a `type Query`, `scalar DateTime`). |
| `NoSuchFileException: schemas/schema.graphql` | No `schema.graphql` in the mounted folder: the folder was missing (Docker then mounts an empty one), the file has another name, or the folder holds JSON Schema files. | Mount the right folder with an absolute path; name the file with `-e LDS_GRAPHQL_SCHEMA=schemas/<file>`; for JSON Schema files give `-e LDS_GRAPHQL_SCHEMA=` (empty). |
| `Unable to find resource: schemas/Missing.json` | A file named in `LDS_SPECIFICATION_SCHEMA` is not there. A space after a comma in that list also gives this. | Correct the path; no spaces in the list. |
| `Configuration key 'http.port' from env LDS_HTTP_PORT is not a valid int: 'abc'` | A number is not a whole number. | Correct the value. |
| `Configuration key '...' from env ... is not a valid boolean: 'yes' (expected true or false)` | A truth value is not `true` or `false`. For some keys the process does not end; see below. | Correct the value. |
| `No persistence providers found for providerId: nope` | `persistence.provider` is not `mem`, `postgres` or `neo4j`. With `mem ` (a space at the end of the line in a file) the message ends with that space. | Correct the value; take away spaces at the end of lines. |
| `Invalid configuration references: '...' references missing key 'my.hst'` | A `${key}` in a value names a key no source has. | Correct the name, or add the key to the file. |
| `No search index provider found for providerId: ...` | Search is on and `search.index.provider` is not `solr`. | Set it to `solr` or leave it out. |

## The container ends after a wait: an engine

LDS tries an engine that does not answer again, logging `... initialization attempt # 2` and higher, and says why
only when the wait is over: 60 seconds for the store of the documents, 30 for the saga log and for the transaction
log. While it waits, `/ping` gets no connection. Then the container ends with exit code 1.

| Message | Meaning | Fix |
|---|---|---|
| `Failed to initialize pool: Connection to localhost:5432 refused` | PostgreSQL cannot be reached at that address. Inside a container `localhost` is the container itself. | Give the host as LDS sees it: the service name in a compose file, `host.docker.internal` for the user's machine; see [configuration.md](configuration.md). Is PostgreSQL running? |
| `Failed to initialize pool: FATAL: password authentication failed for user "lds"` | Wrong password. | Correct `..._PASSWORD`. |
| `The server requested SCRAM-based authentication, but the password is an empty string.` | No password given; no password has a default. | Set the password of that part. |
| `Failed to initialize pool: FATAL: database "nosuch" does not exist` | LDS makes tables, never databases. | Make the database first (the compose files of this skill make `sagalog` and `txlog`). |
| `schema (cluster.owner) must match [A-Za-z0-9_-]+: 'my.org'` | The saga log in PostgreSQL with the default owner, which it refuses. | Set `LDS_SAGALOG_CONFIG_CLUSTER_OWNER`, for example `development`. |
| `namespace (cluster.name) must match [A-Za-z0-9-]+: 'my_cluster'` | The saga log's cluster name has a character it does not take. | Letters, digits and `-` only. |
| `No provider named 'nope'; available: [discard, memory, postgres]` | Wrong `txlog.rawdata.provider`. | One of the three. |
| `Missing configuration 'postgres.driver.password'` | The transaction log in PostgreSQL without a password. | Set `LDS_TXLOG_CONFIG_POSTGRES_DRIVER_PASSWORD`. |
| `ServiceUnavailableException: Unable to connect to ...` | Neo4j cannot be reached. One try takes half a minute or more, so this comes after about 90 seconds. | As for PostgreSQL: the address as the container sees it, and is Neo4j running? Neo4j can take a minute to start; in a compose file LDS waits for it to be healthy. |

Shorten the wait while fixing such a fault: `-e LDS_PERSISTENCE_INITIALIZATION_MAX_WAIT_SECONDS=5` gave the cause
after about 6 seconds with PostgreSQL away. For Neo4j the key does not cut a try short.

Solr is not asked at start: LDS starts without it, and what happens to writes and searches then is `lds:operate`'s.

## The container runs, and nothing answers

| Sign | Meaning | Fix |
|---|---|---|
| The log has `Exception in thread "main"`, the container stays `Up`, health stays `starting` and then `unhealthy`. | Not finished in LDS: for some keys a wrong value stops the start but not the process, and no port is opened. The keys: every `saga.` key, `graphql.enabled`, `http.request.dump`, the `http.cors.` keys. Seen with `LDS_GRAPHQL_ENABLED=yes`. | `docker rm -f lds`, correct the value, start again. Always look for `Server started`, not only for a running container. |
| The log ends with an exception about the model and the process stays: `ClassCastException`, `NullPointerException`, `AssertException: All types within a GraphQL schema must have unique names`, `type map already contains SearchResult`. | Not finished in LDS: some models make the start hang. | Stop the container and fix the model with `lds:model` (a link to an interface, a type named `...Connection`, `...Edge`, `PageInfo` or `SearchResult`). |
| The log says `No schemas configured!` and then `Persistence provider initialization attempt` once a second; after 60 seconds `NullPointerException`. | Not finished in LDS: a GraphQL schema with no type marked `@domain`. | Mark the entity types with `@domain`; `lds:model`. |
| The log says `Server started`, the container is healthy, and `curl` from the machine gets no answer. | `http.host` was set to `127.0.0.1` inside the container, or the `-p` mapping points at another port than LDS listens on. | Leave `http.host` as it is; publish `-p 127.0.0.1:<machine port>:9090`. |
| `/ping` answers `404`, the container turns unhealthy. | `http.prefix` is set; `/ping` is then under the prefix. | Leave `http.prefix` alone in the image. |
| `/ns?schema` answers `Unsupported namespace: "ns"`. | `namespace.default` names another namespace. | Use that namespace in the addresses, or take the setting away. |

## It answers, and something else is wrong

- `/graphql` answers `400 Unsupported namespace: "graphql"`: the model is JSON Schema files, which give no GraphQL
  API. A GraphQL schema gives both; `lds:model`.
- `/ns?schema` answers `[]`: JSON Schema as the model and no `.json` file in the folder.
- Writes answer `500` while `/ping` answers `200`, or the container stops being healthy later: `lds:operate`.
- The container ended with exit code 143: it was told to stop (`docker stop`) and stopped in order. 137: it was
  ended by force, or the machine ran out of memory for it.
