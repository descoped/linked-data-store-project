# Configuring LDS in a container

How the image `descoped/lds:3.0.0` is given its configuration, the rules that make a setting work or be passed
over in silence, and the keys to leave alone in the image. Every key, its default and its meaning:
https://lds.descoped.io/docs/user/configuration/#the-keys.

## How a key is set

LDS reads its configuration once, when it starts. A change needs a new start of the container
(`docker rm -f lds` and `docker run` again, or `docker compose up -d` after a change of `compose.yaml`, which
makes the container anew).

The sources, a later one winning over an earlier one:

1. The defaults built into LDS: everything in memory, the example model, port 9090, namespace `ns`.
2. The file `/lds/conf/application.properties` in the container, when one is mounted there.
3. The file `/lds/conf_dev/application.properties`, when one is mounted there.
4. Environment variables whose names start with `LDS_`.
5. System properties of the JVM, given in the variable `JAVA_TOOL_OPTIONS`.

**A variable** is `LDS_`, then the key in upper case with `_` for every character that is not a letter or a digit:

| Key | Variable |
|---|---|
| `namespace.default` | `LDS_NAMESPACE_DEFAULT` |
| `neo4j.driver.url` | `LDS_NEO4J_DRIVER_URL` |
| `sagalog.config.connection-pool.max-size` | `LDS_SAGALOG_CONFIG_CONNECTION_POOL_MAX_SIZE` |

```sh
docker run -d --name lds -p 127.0.0.1:9090:9090 -e LDS_NAMESPACE_DEFAULT=data descoped/lds:3.0.0
curl -s 'http://localhost:9090/data?schema'
```

```json
["/data/Company?schema","/data/Person?schema"]
```

**A file** has one `key=value` on a line, `#` for a comment, read as UTF-8. Mount it read-only:

```sh
docker run -d --name lds -p 127.0.0.1:9090:9090 \
  -v "$PWD/application.properties:/lds/conf/application.properties:ro" descoped/lds:3.0.0
```

With `namespace.default=data` in the file, `/data?schema` answered as above and `/ns?schema` answered
`Unsupported namespace: "ns"`. A file suits many keys; variables suit a compose file.

**A system property** goes in `JAVA_TOOL_OPTIONS` and wins over a variable. With
`-e JAVA_TOOL_OPTIONS=-Dnamespace.default=sys -e LDS_NAMESPACE_DEFAULT=env` the namespace was `sys`. The log then
starts with `Picked up JAVA_TOOL_OPTIONS: ...`.

## The rules that bite

- **A variable sets only a key that a file has.** The built-in defaults hold the 60 keys of the documentation, so
  each of those can be set by a variable. A few keys of the saga log and the transaction log in PostgreSQL are in no
  file (https://lds.descoped.io/docs/user/configuration/#keys-that-no-file-has): write such a key in a mounted
  `application.properties` first; after that a variable can set it too.
- **A key that is spelled wrong is passed over without a message.** `LDS_NAMESPACE_DEFALT=x` changed nothing and
  the log did not name it. So after setting a key, check its effect: the `Using ...` lines of the log name the stores,
  `/ns?schema` shows the namespace and the model.
- **Values**: a truth value is `true` or `false`; a number is a whole number; a list is separated by commas with no
  space after a comma; spaces at the end of a line in the file belong to the value (`persistence.provider=mem ` with
  a space does not start). A wrong truth value or number stops the start; see
  [start-failures.md](start-failures.md).
- **`${key}`** in a value stands for another key, which can be one of your own in the file: name a host or a password
  once and use it for several keys. A reference to a key that no source has stops the start.

## Keys to leave alone in the image

| Key | Why |
|---|---|
| `http.host` | It is `0.0.0.0`, every address of the container, so Docker can forward the port. With `LDS_HTTP_HOST=127.0.0.1` the container was healthy (its check asks from inside) and nothing answered from the machine. |
| `http.port` | Keep 9090 inside and move the port on the machine with `-p 127.0.0.1:9191:9090`. If it must change, set it only with `LDS_HTTP_PORT` (the health check reads that variable, not a file) and publish that port: `-p 127.0.0.1:9191:9191 -e LDS_HTTP_PORT=9191` was healthy. |
| `http.prefix` | With `LDS_HTTP_PREFIX=/lds`, `/ping` answered `404` and `/lds/ping` `200`; the image's health check asks `/ping`, so the container is called unhealthy while LDS runs. Put a prefix in a proxy in front instead. |

## Engines outside the container

Inside the container `localhost` is the container itself. LDS with `LDS_POSTGRES_DRIVER_HOST` left at
`localhost` and no PostgreSQL in the container ended with `Connection to localhost:5432 refused`.

- **In the same compose file**: the service name is the host (`postgres`, `neo4j`, `solr`), as in the files of this
  skill.
- **On the user's machine**: with Docker Desktop (macOS, Windows) the machine is `host.docker.internal`. This
  reached a PostgreSQL published on the machine's port 19415:

  ```sh
  docker run -d --name lds -p 127.0.0.1:9090:9090 \
    -e LDS_PERSISTENCE_PROVIDER=postgres \
    -e LDS_POSTGRES_DRIVER_HOST=host.docker.internal \
    -e LDS_POSTGRES_DRIVER_PORT=19415 \
    -e LDS_POSTGRES_DRIVER_PASSWORD=lds \
    descoped/lds:3.0.0
  ```

  On Linux with Docker Engine the name exists only when the container is started with
  `--add-host=host.docker.internal:host-gateway` (a rule of Docker), and the engine must listen on an address the
  container reaches, not only on 127.0.0.1.
- **Elsewhere on the network**: its host name or address, as for any program.

## What has no key

The level of the log (it is `INFO`), TLS and users for the HTTP API, the number of connections and TLS for the
PostgreSQL of the documents, the database name, connections and timeouts for Neo4j, a user for Solr. LDS listens
with plain HTTP and asks no caller who it is.
