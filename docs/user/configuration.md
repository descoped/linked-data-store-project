# Configuration of Linked Data Store

This document tells how Linked Data Store (LDS) is configured and lists every key that can be set: what it
means, its default and an example. It also shows which keys go together to choose where LDS keeps its data, and
what happens when LDS starts with a value that is wrong. It is for people who run LDS.

LDS is version `3.0.0-SNAPSHOT`, built from the repository; nothing is released.
[Getting started](getting-started.md) shows how to build and start it. The commands in this document are
for the directory `linked-data-store-server` of the built repository, and LDS listens on port 19306 in them.
Without configuration the port is 9090.

## How a key is set

A key has a name, such as `http.port`, and a value. LDS reads its configuration once, when it starts. A change
is seen at the next start.

### The sources and their order

LDS collects the keys from these sources, in this order. A later source wins over an earlier one.

1. The defaults that are built into LDS. With them LDS keeps everything in memory.
2. The file `conf/application.properties`.
3. The file `conf_dev/application.properties`.
4. The environment variables whose names start with `LDS_`.
5. The system properties of the JVM.

The paths of the files are relative to the working directory, which is the directory LDS is started in. In the
image the working directory is `/lds`, so the file is `/lds/conf/application.properties`. A file that is not
there is passed over.

The built-in defaults are two files inside LDS, `application-defaults.properties` and then
`application-server-defaults.properties`. A file with one of these names in the working directory is read
right after the built-in one of the same name and wins over it. Do not give another file one of these names.

A profile adds files. With the variable `CONFIG_PROFILES=test` or the system property `config.profiles=test`,
each file of the list is followed by a file with `-test` before `.properties`:
`conf/application-test.properties` is read right after `conf/application.properties` and wins over it, and
`conf_dev/application.properties` wins over both. The variable has no `LDS_` prefix. Several profiles are
separated by commas. Without the variable and the property there is no profile.

### The properties file

`conf/application.properties` has one key on a line, `key=value`. A line that starts with `#` is a comment. The
file is read as UTF-8.

- A backslash in a value is written twice. One backslash alone is taken away: `https://app\.example` in the
  file gives the value `https://app.example`, and `https://app\\.example` gives `https://app\.example`.
  In a variable and in a system property a backslash is written once.
- Spaces at the end of a line belong to the value. With a space after `persistence.provider=mem`, LDS does not
  start and prints `No persistence providers found for providerId: mem` with the space at its end. A number
  and a truth value are read without the spaces around them.
- LDS does not tell about a key it does not know. A key that is spelled wrong changes nothing and gives no
  message.

With this file:

```properties
http.port=19306
namespace.default=data
```

and LDS started with no variable:

```sh
java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

LDS listens on port 19306 and its namespace is `data`:

```sh
curl -s 'http://localhost:19306/data?schema'
```

```json
["/data/Company?schema","/data/Person?schema"]
```

### Environment variables

The name of the variable is `LDS_`, then the key in upper case with `_` for every character that is not a letter
or a digit:

| Key | Variable |
|---|---|
| `http.port` | `LDS_HTTP_PORT` |
| `neo4j.driver.url` | `LDS_NEO4J_DRIVER_URL` |
| `sagalog.config.connection-pool.max-size` | `LDS_SAGALOG_CONFIG_CONNECTION_POOL_MAX_SIZE` |

```sh
LDS_HTTP_PORT=19306 java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

```
... Listening on 0.0.0.0:19306
... Server started in 364ms..
```

A variable wins over the files. With the file of the section before and the variable
`LDS_NAMESPACE_DEFAULT=test`, the namespace is `test`:

```sh
LDS_NAMESPACE_DEFAULT=test java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
curl -s 'http://localhost:19306/test?schema'
```

```json
["/test/Company?schema","/test/Person?schema"]
```

A variable that is set and empty sets the key to an empty value. `LDS_GRAPHQL_SCHEMA=` is used that way in
[The model and the namespace](#the-model-and-the-namespace).

### System properties

A system property has the name of the key, with no prefix. It wins over a variable and over the files.

```sh
java -Dhttp.port=19306 -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

```
... Listening on 0.0.0.0:19306
```

Where the command that starts the JVM cannot be changed, as in the image, a system property is given in the
variable `JAVA_TOOL_OPTIONS`: `JAVA_TOOL_OPTIONS=-Dhttp.port=19306`. The JVM then prints
`Picked up JAVA_TOOL_OPTIONS: -Dhttp.port=19306` as its first line.

### A variable or a system property sets only a key that a file has

LDS takes a variable or a system property only for a key that is in one of the files: the built-in defaults,
`conf/application.properties` or `conf_dev/application.properties`. A variable for any other key is passed over
without a message.

The built-in defaults have the 60 keys of [The keys](#the-keys), so each of them can be set by a variable. The saga log
and the transaction log read some more keys, listed in [Keys that no file has](#keys-that-no-file-has). Such a key is
first written in `conf/application.properties`. After that a variable can set it too.

An example, with the transaction log in PostgreSQL. It opens 10 connections unless
`txlog.config.postgres.driver.max-pool-size` says another number, and no built-in file has that key.

- With only the variable `LDS_TXLOG_CONFIG_POSTGRES_DRIVER_MAX_POOL_SIZE=3`, LDS opened 10 connections.
- With the line `txlog.config.postgres.driver.max-pool-size=3` in `conf/application.properties`, LDS opened 3.
- With that line and the variable `LDS_TXLOG_CONFIG_POSTGRES_DRIVER_MAX_POOL_SIZE=5`, LDS opened 5.

### One key from another

In a value, `${key}` stands for the value of another key. The other key can be one of your own. This
`conf/application.properties` keeps the documents, the saga log and the transaction log in one PostgreSQL, and
names its address and its password once:

```properties
http.host=127.0.0.1
http.port=19306

db.host=localhost
db.port=5432
db.password=

persistence.provider=postgres
postgres.driver.host=${db.host}
postgres.driver.port=${db.port}
postgres.driver.password=${db.password}

sagalog.provider=no.cantara.sagalog.postgres.PostgresSagaLogInitializer
sagalog.config.cluster.owner=development
sagalog.config.cluster.name=lds
sagalog.config.postgres.driver.host=${db.host}
sagalog.config.postgres.driver.port=${db.port}
sagalog.config.postgres.driver.password=${db.password}

txlog.rawdata.provider=postgres
txlog.config.postgres.driver.host=${db.host}
txlog.config.postgres.driver.port=${db.port}
txlog.config.postgres.driver.password=${db.password}
```

`db.host`, `db.port` and `db.password` are now keys of a file, so variables can set them. Here PostgreSQL is on
port 19306 of the same machine, at the IPv6 address `::1`. LDS listens at `127.0.0.1`, which is another
address, so the two have the same port number without being in each other's way:

```sh
LDS_DB_HOST='[::1]' LDS_DB_PORT=19306 LDS_DB_PASSWORD=lds \
  java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
```

```
... Using persistence provider: postgres
... Using saga-log-pool provider-class: no.cantara.sagalog.postgres.PostgresSagaLogInitializer
... Using transaction-log provider: postgres
... Listening on 127.0.0.1:19306
... Server started in 1575ms..
```

Three more rules:

- `${key:default}` gives a value to use when no source has the key. The default does not make the key a key
  of a file: with `http.prefix=${my.prefix:/}` in the file and nothing else, the variable `LDS_MY_PREFIX=/lds`
  is passed over and the prefix stays `/`.
- A reference to a key that no source has, without a default, stops LDS at start. So do two keys that refer to
  each other. See [A value that is wrong](#a-value-that-is-wrong).
- `$${` is a literal `${`.

### The form of a value

- A truth value is `true` or `false`, in upper or lower case. Any other value stops the start. One key is
  read in another way, `neo4j.cypher.show`; see its row in [The keys](#the-keys).
- A number is a whole number. Any other value stops the start.
- A list is separated by commas, with no space after a comma. LDS does not take the spaces away: with the
  value `GET, PUT` for `http.cors.allow.methods`, the method `PUT` is not allowed.
- A value can be empty: `graphql.schema=` in a file, or `LDS_GRAPHQL_SCHEMA=` as a variable.

## Choosing where LDS keeps its data

LDS keeps four things, and a provider is chosen for each. [The concepts](concepts.md) tells what a saga
and the transaction log are.

| What | Key that chooses | Values | Default |
|---|---|---|---|
| The documents | `persistence.provider` | `mem`, `postgres`, `neo4j` | `mem`: in memory |
| The saga log, where LDS notes each step of a write so that a write that was cut off can be completed | `sagalog.provider` | `no.cantara.sagalog.memory.MemorySagaLogInitializer`, `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` | in memory |
| The transaction log, a list of every write | `txlog.rawdata.provider` | `discard`, `memory`, `postgres` | `discard`: none is kept |
| The search index | `graphql.search.enabled` and `search.index.provider` | `solr` | search is off |

Each choice is independent of the others. The log of a start says what LDS runs with, in the lines that begin
with `Using`.

The sections below show the keys that go together for each choice, as a `conf/application.properties`. Every
key can be given as a variable instead. The examples that name PostgreSQL were run with PostgreSQL 18 on the
same machine. It did not listen on port 5432 of `localhost` there, so its address was given from outside in
each run, with the variables of the `host` and `port` keys of the example.

No password has a default. The passwords in the examples (`lds`, `lds-development`) are those of the compose
file of the repository, which is for development.

### Everything in memory

This is LDS as it comes. Nothing needs to be set. The keys and their defaults are:

```properties
persistence.provider=mem
sagalog.provider=no.cantara.sagalog.memory.MemorySagaLogInitializer
txlog.rawdata.provider=discard
graphql.search.enabled=false
```

```
... Using persistence provider: mem
... Using saga-log-pool provider-class: no.cantara.sagalog.memory.MemorySagaLogInitializer
... GraphQL Search is disabled. Skipping search index initialisation
... Using transaction-log provider: discard
```

Nothing is left when LDS stops. The provider `mem` is for trying LDS and for tests.

### Documents in PostgreSQL

```properties
persistence.provider=postgres
postgres.driver.host=localhost
postgres.driver.port=5432
postgres.driver.user=lds
postgres.driver.password=lds
postgres.driver.database=lds
```

```
... Using persistence provider: postgres
... Persistence provider initialization attempt # 1
... Persistence service-provider configured
... Server started in 490ms..
```

The database and the user must be there before LDS starts. LDS makes its table in the database at start when
the table is missing, so the user needs the right to make a table. LDS is tested with PostgreSQL 18 and no
other version.

### Documents in Neo4j

The compose file of the repository runs LDS with the documents in Neo4j. These are the variables it gives LDS
for that:

```yaml
      LDS_PERSISTENCE_PROVIDER: neo4j
      LDS_NEO4J_DRIVER_URL: bolt://neo4j:7687
      LDS_NEO4J_DRIVER_USERNAME: neo4j
      LDS_NEO4J_DRIVER_PASSWORD: lds-development
```

The keys are `persistence.provider`, `neo4j.driver.url`, `neo4j.driver.username` and `neo4j.driver.password`.
At start LDS makes a constraint and an index in Neo4j for each entity of the model when they are missing, so
the user needs the right to make them. LDS is tested with Neo4j 2026.10, community edition, and with addresses
that start with `bolt://`.

### The saga log in PostgreSQL

```properties
sagalog.provider=no.cantara.sagalog.postgres.PostgresSagaLogInitializer
sagalog.config.cluster.owner=development
sagalog.config.cluster.name=lds
sagalog.config.cluster.instance-id=01
sagalog.config.postgres.driver.host=localhost
sagalog.config.postgres.driver.port=5432
sagalog.config.postgres.driver.user=lds
sagalog.config.postgres.driver.password=lds
sagalog.config.postgres.driver.database=sagalog
sagalog.config.connection-pool.max-size=50
```

```
... Using saga-log-pool provider-class: no.cantara.sagalog.postgres.PostgresSagaLogInitializer
... SagaLogPool provider initialization attempt # 1
... SagaLogPool initialized!
... Server started in 1481ms..
```

- The database (`sagalog` here) must be there before LDS starts. LDS makes a schema with the name of
  `sagalog.config.cluster.owner` in it, and its tables in that schema.
- `sagalog.config.cluster.owner` must be set. See the paragraph below.
- Every LDS that shares the saga log has the same `sagalog.config.cluster.owner` and
  `sagalog.config.cluster.name`, and its own `sagalog.config.cluster.instance-id`.
- `sagalog.config.connection-pool.max-size` must be at least `saga.number-of-logs` (30 by default): each open
  saga log holds one connection. With 30 saga logs and a pool of 5, LDS answered `/ping` with `200` and a write
  with `500` after 30 seconds, and ended by itself 90 seconds after its start with exit code 1 and the message
  `No database connection available within 30000 ms: the pool has 5 connections (5 active, 0 idle), and each
  open saga log holds one. Raise connection-pool.max-size or open fewer saga logs at once.` The log of that
  run never said `Server started`.

Not finished: the default of `sagalog.config.cluster.owner` is `my.org`, and the saga log in PostgreSQL takes
only letters, digits, `_` and `-` in that name. With the default LDS tries for 30 seconds and then ends with
exit code 1 and `java.lang.IllegalArgumentException: schema (cluster.owner) must match [A-Za-z0-9_-]+:
'my.org'`.

### The transaction log

In PostgreSQL:

```properties
txlog.rawdata.provider=postgres
txlog.config.postgres.driver.host=localhost
txlog.config.postgres.driver.port=5432
txlog.config.postgres.driver.user=lds
txlog.config.postgres.driver.password=lds
txlog.config.postgres.driver.database=txlog
```

```
... Using transaction-log provider: postgres
... Transaction-log provider initialization attempt # 1
... Transaction-log initialized
... Server started in 450ms..
```

The database (`txlog` here) must be there before LDS starts. LDS makes its tables in it when they are missing.

The value `memory` keeps the transaction log in memory until LDS stops, and needs no other key. With a
transaction log, LDS can tell the `sourceId` of the last write (see [the REST API](rest-api.md)):

```sh
LDS_TXLOG_RAWDATA_PROVIDER=memory LDS_HTTP_PORT=19306 \
  java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
curl -s -X PUT 'http://localhost:19306/ns/Company/acme?sync=true&source=billing&sourceId=b-17' \
     -H 'Content-Type: application/json' -d '{"name":"Acme"}'
curl -s 'http://localhost:19306/source/default'
```

```
{"saga-execution-id":"01a1f9e0-e0f7-75ca-9fad-619a8920b3ad"}
{"lastSourceId":"b-17"}
```

The write names the source `billing` and is found under the source `default`: as it comes, LDS keeps one
transaction log for every source. `txlog.split.sources=true` gives each source its own; see
[The transaction log](#the-transaction-log-1).

With `discard`, the same write is answered in the same way, and `/source/default` answers
`{"lastSourceId":null}`.

### Search with Solr

The compose file of the repository runs LDS with search. These are the variables it gives LDS for that:

```yaml
      LDS_GRAPHQL_SEARCH_ENABLED: "true"
      LDS_SEARCH_INDEX_PROVIDER: solr
      LDS_SEARCH_INDEX_URL: http://solr:8983/solr/lds-index
```

`search.index.provider` is `solr` by default, so `graphql.search.enabled=true` and the address are enough. The
address is that of one Solr core and ends with the name of the core. The core must be there; LDS does not make
it. LDS is tested with Solr 10.1.

LDS does not ask Solr anything when it starts. Started with `LDS_GRAPHQL_SEARCH_ENABLED=true` and no Solr, LDS
starts, and GraphQL has the query `Search`:

```
... Search index provider configured: solr
... Initializing SolrSearchIndex
... Creating root "Query" search field and search types
```

Search finds a document by the texts of its fields named `languageText`, and by nothing else. What search covers
is under review. [The GraphQL API](graphql-api.md) has the query `Search`, and
[Operations](operations.md) says what happens to writes and searches when Solr is away.

## The keys

These are the 60 keys of the built-in defaults, by what they are for. "Default" is the value LDS has when
nothing is set. An empty default is written "empty".

### HTTP

| Key | Default | Meaning | Example |
|---|---|---|---|
| `http.host` | `0.0.0.0` | The address LDS listens on. `0.0.0.0` is every address of the machine. | `127.0.0.1` |
| `http.port` | `9090` | The port LDS listens on. | `19306` |
| `http.prefix` | `/` | A path in front of every address of LDS, also `/ping`, `/health/alive`, `/health/ready`, `/graphql` and `/graphiql`. An address outside the prefix is answered `404`. | `/lds` |
| `http.request.dump` | `false` | `true` writes every request and its answer to the log: the address, the method, the query parameters, the status and every header as it was sent, an `Authorization` header too. For finding faults, not for daily use. | `true` |
| `http.cors.allow.origin` | `.*` | Which web pages may call LDS from a browser (CORS): regular expressions, separated by commas. A request whose `Origin` header matches one of them as a whole gets the CORS headers in its answer. `.*` is every origin. An empty value allows no origin. In a properties file each backslash is written twice. | `https://app\.example,https://.*\.example\.org` |
| `http.cors.allow.header` | `Content-Type,Authorization` | The request headers a browser may ask for before a call, separated by commas. LDS also gives the list in `Access-Control-Allow-Headers` and `Access-Control-Expose-Headers`. | `Content-Type,X-Request-Id` |
| `http.cors.allow.methods` | `POST,GET,PUT,DELETE,HEAD` | The methods a browser may ask for before a call, separated by commas. | `GET,PUT` |
| `http.cors.allow.credentials` | `false` | `true` adds `Access-Control-Allow-Credentials: true` to the answers. | `true` |
| `http.cors.allow.max-age` | `900` | The seconds a browser may keep the answer to its question before a call (`Access-Control-Max-Age`). | `60` |

With `http.prefix=/lds`:

```sh
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:19306/ping'
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:19306/lds/ping'
curl -s 'http://localhost:19306/lds/ns?schema'
```

```
404
200
["/ns/Company?schema","/ns/Person?schema"]
```

Not finished: with a prefix other than `/`, the addresses in the answer to `?schema` are without the prefix, as
above. The address `/source/{source}` does not work under a prefix: it answers `{"lastSourceId":null}`
whatever was written, and `500` with `txlog.split.sources=true`. The health check of the image asks `/ping`
without the prefix and on the port of the variable `LDS_HTTP_PORT`. So in the image, leave `http.prefix` as it
is and move the port with that variable, not in a file.

With the example values of the five CORS keys above, a browser's question before a `PUT` from
`https://app.example` is answered:

```sh
curl -s -i -X OPTIONS 'http://localhost:19306/ns/Company/acme' -H 'Origin: https://app.example' \
     -H 'Access-Control-Request-Method: PUT' -H 'Access-Control-Request-Headers: Content-Type'
```

```
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example
Vary: Origin
Access-Control-Allow-Headers: X-Request-Id,Content-Type
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 60
Access-Control-Allow-Methods: PUT,GET
Date: Fri, 20 Nov 2026 16:54:12 GMT
```

A request from an origin that is not allowed is still answered, without the CORS headers; the browser then
keeps the answer from the page. [The REST API](rest-api.md) has the rules of CORS in full.

### The model and the namespace

| Key | Default | Meaning | Example |
|---|---|---|---|
| `graphql.schema` | `schemas/schema.graphql` | The file with the GraphQL schema that is the model. The path is relative to the working directory, or absolute. When the value is empty, the model is read from `specification.schema`. | `models/library.graphql` |
| `specification.schema` | `schemas` | Read only when `graphql.schema` is empty. Files and folders, separated by commas. A file is the JSON Schema of one entity, and the name of the file without `.json` is the name of the entity. In a folder, every file whose name ends with `.json` is read. | `models/jsonschema` |
| `namespace.default` | `ns` | The namespace of this LDS: the first part of the address of every document, and the namespace GraphQL reads from. An LDS answers for one namespace. An address with another first part is answered `400` with `Unsupported namespace: "data"`. | `data` |

The default model is the example of the repository, with the entities `Person` and `Company`.
[The model](model.md) tells how to write one.

A model in JSON Schema files, in the folder `models/jsonschema` with the files `Company.json` and
`Person.json`:

```sh
LDS_GRAPHQL_SCHEMA= LDS_SPECIFICATION_SCHEMA=models/jsonschema LDS_HTTP_PORT=19306 \
  java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
curl -s 'http://localhost:19306/ns?schema'
```

```
... Creating specification using json-schema: models/jsonschema
... Managed domains: /Company /Person
```

```json
["/ns/Company?schema","/ns/Person?schema"]
```

With such a model LDS has no GraphQL API: a request to `/graphql` is answered `400` with
`Unsupported namespace: "graphql"`.

As it comes, the folder `schemas` has no `.json` file. So with `LDS_GRAPHQL_SCHEMA=` and nothing else, LDS
starts with no entity: the log says `No schemas configured!` and `/ns?schema` answers `[]`.

### GraphQL

| Key | Default | Meaning | Example |
|---|---|---|---|
| `graphql.enabled` | `true` | `false` takes away the GraphQL API (`/graphql`) and the page `/graphiql`; a request to them is answered `400`. The REST API is as before. `true` gives a GraphQL API only when `graphql.schema` names a model. | `false` |

The key that adds search to GraphQL, `graphql.search.enabled`, is under [Search](#search).

### Persistence: the documents

| Key | Default | Meaning | Example |
|---|---|---|---|
| `persistence.provider` | `mem` | Where the documents are kept: `mem` (in memory), `postgres` or `neo4j`. | `postgres` |
| `persistence.initialization.max-wait-seconds` | `60` | How long LDS keeps trying to start the persistence provider. After a try that fails, LDS waits one second and tries again, until this many seconds have passed since the first try. `0` is one try. See [An engine that is away](#an-engine-that-is-away). | `120` |
| `persistence.fragment.capacity` | `8192` | Provider `mem` only: the size in bytes of the pieces a long text is stored in. The text is read back whole. Leave it as it is. | `8192` |

Provider `postgres`:

| Key | Default | Meaning | Example |
|---|---|---|---|
| `postgres.driver.host` | `localhost` | The host of PostgreSQL. An IPv6 address is written `::1` or `[::1]`. | `[::1]` |
| `postgres.driver.port` | `5432` | The port of PostgreSQL. | `19306` |
| `postgres.driver.user` | `lds` | The user LDS connects as. | `lds` |
| `postgres.driver.password` | empty | The password of the user. It has no default and must be given. | `lds` |
| `postgres.driver.database` | `lds` | The database. It must be there; LDS makes its table in it. | `lds` |

Provider `neo4j`:

| Key | Default | Meaning | Example |
|---|---|---|---|
| `neo4j.driver.url` | `bolt://localhost:7687` | The address of Neo4j. | `bolt://neo4j:7687` |
| `neo4j.driver.username` | `neo4j` | The user LDS connects as. | `neo4j` |
| `neo4j.driver.password` | empty | The password of the user. It has no default and must be given. | `lds-development` |
| `neo4j.cypher.show` | `false` | `true` writes every statement LDS sends to Neo4j for a read, a write or a delete to the log, with the values of the documents in it. Any value but `true` is read as `false`. | `true` |

### Sagas: the writes

Every write is carried out by a saga. These keys set how many writes LDS carries out at one time and whether it
completes writes that were cut off. The defaults suit a first set-up.

| Key | Default | Meaning | Example |
|---|---|---|---|
| `saga.number-of-logs` | `30` | The number of saga logs of this LDS. A write holds one saga log from its start to its end, so this is also the most writes LDS carries out at one time. A write that gets no saga log within 10 seconds is answered `500`. | `10` |
| `saga.threadpool.core` | `50` | The threads LDS keeps for the sagas. It must be larger than `saga.threadpool.queue.capacity`. When it is not, LDS starts and writes a warning to the log. | `20` |
| `saga.threadpool.max` | `100` | The most threads for the sagas. Not smaller than `saga.threadpool.core`. | `40` |
| `saga.threadpool.keepalive.seconds` | `60` | How long a thread beyond `saga.threadpool.core` stays when it has no work. | `30` |
| `saga.threadpool.queue.capacity` | `10` | The places in the queue in front of the threads. At least 1. | `5` |
| `saga.recovery.enabled` | `true` | Whether LDS completes by itself the writes that were cut off: its own when it starts, and those of every LDS that shares the saga log, at intervals. [Operations](operations.md) tells more. | `false` |
| `saga.recovery.interval.seconds.min` | `30` | The shortest wait between two rounds of such completing. Each wait is a random number of seconds between this key and the next. From 1 to 86400. | `10` |
| `saga.recovery.interval.seconds.max` | `60` | The longest wait. From 1 to 86400, and not smaller than the key before. | `20` |
| `saga.commands.enabled` | `false` | For the tests of LDS itself: `true` lets a request make its own write fail. Leave it `false`. | `false` |

LDS also carries out no more writes at one time than half of the sum of `saga.threadpool.max` and
`saga.threadpool.queue.capacity`: 55 with the defaults. The smaller of that number and `saga.number-of-logs`
counts.

The warning, with `LDS_SAGA_THREADPOOL_QUEUE_CAPACITY=60`. It is one line of the log, broken in two here:

```
... WARN  ... Configuration: saga.threadpool.core (50) must be greater than
    saga.threadpool.queue.capacity (60) in order to avoid potential deadlocks.
```

### The saga log

| Key | Default | Meaning | Example |
|---|---|---|---|
| `sagalog.provider` | `no.cantara.sagalog.memory.MemorySagaLogInitializer` | Where the saga log is kept. The default keeps it in memory: a write that is cut off when LDS stops is lost. The other value keeps it in PostgreSQL. | `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` |
| `sagalog.provider.initialization.max-wait-seconds` | `30` | How long LDS keeps trying to start the saga log. | `60` |
| `sagalog.provider.initialization.retry-interval-seconds` | `2` | The wait between two tries. | `5` |
| `sagalog.config.cluster.owner` | `my.org` | In PostgreSQL: the name of the schema the saga log makes its tables in. Letters, digits, `_` and `-`. The default is refused there, so the key must be set. | `development` |
| `sagalog.config.cluster.name` | `lds-test` | The name of the cluster. Every LDS with the same owner and name shares the saga log and completes the writes of the others that were cut off. In PostgreSQL: letters, digits and `-`. | `lds` |
| `sagalog.config.cluster.instance-id` | `01` | The name of this LDS in the cluster. Each LDS of a cluster needs its own. In PostgreSQL: letters, digits and `-`. | `02` |
| `sagalog.config.postgres.driver.host` | `localhost` | The saga log in PostgreSQL: the host. | `postgres` |
| `sagalog.config.postgres.driver.port` | `5432` | The port. | `19306` |
| `sagalog.config.postgres.driver.user` | `lds` | The user. | `lds` |
| `sagalog.config.postgres.driver.password` | empty | The password. It has no default and must be given. | `lds` |
| `sagalog.config.postgres.driver.database` | `sagalog` | The database. It must be there. | `sagalog` |
| `sagalog.config.connection-pool.max-size` | `50` | The most connections of the saga log to PostgreSQL. At least `saga.number-of-logs`. With the default, LDS had opened all 50 within 10 seconds of its start. | `50` |

### The transaction log

| Key | Default | Meaning | Example |
|---|---|---|---|
| `txlog.rawdata.provider` | `discard` | Where the transaction log is kept. `discard`: none is kept. `memory`: in memory, until LDS stops. `postgres`: in PostgreSQL. | `postgres` |
| `txlog.rawdata.provider.initialization.max-wait-seconds` | `30` | How long LDS keeps trying to start the transaction log. | `60` |
| `txlog.rawdata.provider.initialization.retry-interval-seconds` | `2` | The wait between two tries. | `5` |
| `txlog.split.sources` | `false` | A write can name where it comes from, with the query parameter `source`. `false`: one transaction log for every write, that of the default source. `true`: one for each source. | `true` |
| `txlog.default-source` | `default` | The source of a write that names none. An empty value is read as `default`. | `main` |
| `txlog.rawdata.topic-prefix` | `txlog-` | A transaction log is stored under a name, its topic: this prefix and then the source. With the defaults the topic is `txlog-default`. | `lds-` |
| `txlog.config.postgres.driver.host` | `localhost` | The transaction log in PostgreSQL: the host. | `postgres` |
| `txlog.config.postgres.driver.port` | `5432` | The port. | `19306` |
| `txlog.config.postgres.driver.user` | `lds` | The user. | `lds` |
| `txlog.config.postgres.driver.password` | empty | The password. It has no default and must be given. | `lds` |
| `txlog.config.postgres.driver.database` | `txlog` | The database. It must be there. | `txlog` |

With `txlog.split.sources=true`, `txlog.default-source=main` and `txlog.rawdata.topic-prefix=lds-`, a write
with `source=billing` is stored under the topic `lds-billing` and a write that names no source under
`lds-main`.

Not finished: with `txlog.split.sources=true` the source becomes a part of a topic. A topic has at most 100
characters, of letters, digits, `_`, `.` and `-`, and starts with a letter or a digit. LDS does not check the
source of a request against that. A write with another source, such as one with a space in it, is answered
`500` and is not done.

Not finished: LDS does not check `txlog.default-source` and `txlog.rawdata.topic-prefix` at start either. With
a value that makes no topic, such as a default source with a space in it or a prefix that starts with `-`, LDS
starts and then answers every write `500`. That holds with `txlog.split.sources=false` and with `discard` too.

### Search

| Key | Default | Meaning | Example |
|---|---|---|---|
| `graphql.search.enabled` | `false` | `true` turns search on: every write and every delete of a document also goes to the search index, and GraphQL gets the query `Search`. | `true` |
| `search.index.provider` | `solr` | The search provider. `solr` is the only one. Read only when search is on. | `solr` |
| `search.index.url` | `http://localhost:8983/solr/lds-index` | The address of the Solr core, with the name of the core at its end. LDS does not check it at start. | `http://solr:8983/solr/lds-index` |

Not finished: search needs a model that is a GraphQL schema, because `Search` is a GraphQL query. With a model
of JSON Schema files, search can be turned on and nothing can search.

### Keys that no file has

The saga log and the transaction log in PostgreSQL read these keys too. No built-in file has them, so each is first
written in `conf/application.properties` (see [A variable or a system property sets only a key that a file
has](#a-variable-or-a-system-property-sets-only-a-key-that-a-file-has)).

| Key | Default | Meaning |
|---|---|---|
| `sagalog.config.connection-pool.connection-timeout` | `30000` | The milliseconds the saga log waits for a free connection. |
| `sagalog.config.postgres.driver.sslmode` | `prefer` | The `sslmode` of the connection to PostgreSQL. With `require` and a PostgreSQL without TLS, LDS does not start: `Failed to initialize pool: The server does not support SSL.` |
| `sagalog.config.postgres.driver.sslrootcert` | none | The file with the root certificate for that. |
| `sagalog.config.postgres.driver.socket-timeout` | `0` | The milliseconds a read from PostgreSQL may take, counted up to whole seconds. `0` is no limit. |
| `sagalog.config.postgres.driver.tcp-keep-alive` | `false` | Whether the connection uses TCP keep-alive. |
| `sagalog.config.postgres.statement-timeout` | `0` | The `statement_timeout` of PostgreSQL for the connections of the saga log, in milliseconds. `0` sets none. |
| `sagalog.config.postgres.lock-timeout` | `0` | The `lock_timeout` of PostgreSQL for the connections of the saga log, in milliseconds. `0` sets none. |
| `sagalog.config.postgres.driver.property.` and a name | none | A property of the PostgreSQL driver with that name, such as `sagalog.config.postgres.driver.property.ApplicationName=lds-saga`. A name the driver does not know stops the start: `Unknown PostgreSQL driver property in postgres.driver.property.nosuch: Unsupported property name: nosuch`. |
| `txlog.config.postgres.driver.max-pool-size` | `10` | The most connections of the transaction log to PostgreSQL. |
| `txlog.config.rawdata.postgres.consumer.prefetch-size` | `100` | How many entries a reader of the transaction log fetches at a time. At least 1. |
| `txlog.config.rawdata.postgres.consumer.prefetch-poll-interval-when-empty` | `250` | The milliseconds such a reader waits before it asks again when there was nothing. At least 1. |

A wrong value of one of these keys is tried again like an engine that is away, and stops the start when the
wait is over (see [A value that is wrong](#a-value-that-is-wrong)).

These were set and seen to work in a running LDS: `txlog.config.postgres.driver.max-pool-size`,
`sagalog.config.connection-pool.connection-timeout`, `sagalog.config.postgres.driver.sslmode` and
`sagalog.config.postgres.driver.property.ApplicationName`. LDS refused a value that is not a number above 0 for
the two `prefetch` keys, and started with a value for the two timeouts of PostgreSQL, the socket timeout and
TCP keep-alive; what those values then do was not looked at. `sagalog.config.postgres.driver.sslrootcert` was
not tried.

### What has no key

- The level of the log. It is `INFO`. [Operations](operations.md) tells how LDS is given another
  configuration of the log.
- Encryption (TLS) and users for the HTTP API. LDS listens with plain HTTP and asks no caller who it is.
- The documents in PostgreSQL: the number of connections (10) and any option of the connection, such as TLS.
- The documents in Neo4j: the name of the database, the number of connections and the timeouts.
- A user and a password for Solr.

With everything in one PostgreSQL, one LDS opens 70 connections with the defaults: 10 for the documents, 50
for the saga log and 10 for the transaction log.

## When LDS does not start

LDS has started when the log says `Server started`. When the start fails, the JVM prints the cause on standard error as
`Exception in thread "main"` with a stack trace. It is not a line of the log. The process then ends with exit code 1,
except in the cases of [A start that fails and a process that stays](#a-start-that-fails-and-a-process-that-stays).

### A value that is wrong

| What is wrong | What LDS prints |
|---|---|
| A truth value that is not `true` or `false` (`LDS_TXLOG_SPLIT_SOURCES=yes`) | `io.descoped.config.ConfigurationException: Configuration key 'txlog.split.sources' from env LDS_TXLOG_SPLIT_SOURCES is not a valid boolean: 'yes' (expected true or false)` |
| A number that is not a number (`LDS_HTTP_PORT=abc`) | `io.descoped.config.ConfigurationException: Configuration key 'http.port' from env LDS_HTTP_PORT is not a valid int: 'abc' (For input string: "abc")` |
| `${nowhere}` in the value of `http.prefix` in a file, and no key `nowhere` | `io.descoped.config.ConfigurationException: Invalid configuration references: 'http.prefix' from file .../conf/application.properties references missing key 'nowhere'` |
| Two keys that refer to each other | `io.descoped.config.ConfigurationException: Invalid configuration references: reference cycle a.b -> c.d -> a.b (...)` |
| A properties file that is not UTF-8 | `io.descoped.config.ConfigurationException: Cannot read file .../conf/application.properties: not valid UTF-8` |
| The file of `graphql.schema` is not there (`LDS_GRAPHQL_SCHEMA=models/missing.graphql`) | `java.lang.RuntimeException: java.nio.file.NoSuchFileException: models/missing.graphql` |
| A file of `specification.schema` is not there | `java.lang.IllegalArgumentException: Unable to find resource: models/jsonschema/Missing.json` |
| An unknown `persistence.provider` (`nope`) | `java.lang.RuntimeException: No persistence providers found for providerId: nope` |
| An unknown `sagalog.provider` | `java.util.NoSuchElementException: No value present`. The message does not name the key. |
| An unknown `txlog.rawdata.provider` (`nope`) | `io.descoped.service.provider.api.ProviderException: No provider named 'nope'; available: [discard, memory, postgres]`, after 30 seconds |
| An unknown `search.index.provider` (`nope`), with search on | `java.lang.RuntimeException: No search index provider found for providerId: nope` |
| The transaction log in PostgreSQL without a password | `java.lang.IllegalArgumentException: Missing configuration 'postgres.driver.password'`, after 30 seconds |
| The saga log in PostgreSQL with a `sagalog.config.cluster.name` that has other characters (`my_cluster`) | `java.lang.IllegalArgumentException: namespace (cluster.name) must match [A-Za-z0-9-]+: 'my_cluster'`, after 30 seconds |
| `http.host` is a name that is not known (`no.such.host.invalid`) | `java.lang.RuntimeException: java.net.UnknownHostException: no.such.host.invalid: ...` |
| The port is taken by another program | `java.lang.RuntimeException: java.net.BindException: Address already in use` |

In such a message the value of a key that looks like a secret is written `'****'`: a key whose last part
has `password`, `passwd`, `secret`, `token`, `credential`, `privatekey` or `apikey` in it.
`http.cors.allow.credentials` is one of them:
`Configuration key 'http.cors.allow.credentials' from env LDS_HTTP_CORS_ALLOW_CREDENTIALS is not a valid
boolean: '****' (java.lang.IllegalArgumentException)`.

Not finished: a wrong name or value of the saga log or of the transaction log is tried again like an engine
that is away. LDS ends only when `sagalog.provider.initialization.max-wait-seconds` or
`txlog.rawdata.provider.initialization.max-wait-seconds` have passed, 30 seconds by default, though no try can
succeed. That is the "after 30 seconds" in the table. The same holds for a wrong value of the persistence
provider, such as a `postgres.driver.port` that is not a number: LDS tries again until
`persistence.initialization.max-wait-seconds` have passed.

### An engine that is away

The engines are PostgreSQL, Neo4j and Solr. At start LDS tries an engine that does not answer again, until the wait of
its part is over: `persistence.initialization.max-wait-seconds` for the documents,
`sagalog.provider.initialization.max-wait-seconds` for the saga log and
`txlog.rawdata.provider.initialization.max-wait-seconds` for the transaction log. Then LDS ends with exit code 1 and the
cause of the last try. LDS does not ask Solr at start and starts without it.
[Operations](operations.md#how-long-lds-waits-for-an-engine) shows such a start, with its log, and says what LDS does
when an engine goes away while LDS runs.

A wrong password and a database that is not there are tried again in the same way. These are the causes LDS
ends with:

- PostgreSQL does not answer: `com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to
  initialize pool: Connection to 127.0.0.1:19306 refused. Check that the hostname and port are correct and that
  the postmaster is accepting TCP/IP connections.`
- no password: `Failed to initialize pool: The server requested SCRAM-based authentication, but the password is
  an empty string.`
- a wrong password: `Failed to initialize pool: FATAL: password authentication failed for user "lds"`
- no such database: `Failed to initialize pool: FATAL: database "nosuch" does not exist`
- Neo4j does not answer: `org.neo4j.driver.exceptions.ServiceUnavailableException: Unable to connect to
  127.0.0.1:19306, ensure the database is running and that there is a working network connection to it.`

### A start that fails and a process that stays

Not finished: for some keys a wrong value stops the start but not the process. The cause is printed as above,
LDS opens no port, and the process stays until it is ended from outside (`kill` ends it with exit code 143).
The health check of the image asks `/ping`, so a container with such an LDS runs and does not become healthy.
The keys are:

- every key that starts with `saga.`: a value that is not a number or not a truth value,
  `saga.threadpool.core` larger than `saga.threadpool.max`
  (`java.lang.IllegalArgumentException: maximumPoolSize must be greater than or equal to corePoolSize`),
  `saga.threadpool.queue.capacity=0` (`java.lang.IllegalArgumentException`, with no message),
  `saga.recovery.interval.seconds.min` larger than `saga.recovery.interval.seconds.max`
  (`java.lang.IllegalArgumentException: illegal configuration: intervalMaxSec < intervalMinSec`), and one of
  these two below 1 or above 86400 (`illegal configuration: intervalMaxSec > 86400`);
- `graphql.enabled` and `http.request.dump`: a value that is not a truth value;
- `http.cors.allow.credentials` and `http.cors.allow.max-age`: a value of the wrong kind;
- `http.cors.allow.origin`: a value that is not a regular expression, such as `[`
  (`java.util.regex.PatternSyntaxException: Unclosed character class near index 0`).

So after a change of one of these keys, look for `Server started` in the log, not only for a running process.
