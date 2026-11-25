# Moving to a newer image

LDS is the image `descoped/lds` on Docker Hub, for linux/amd64 and linux/arm64, in three variants: `3.0.1` (with
busybox, for looking around), `3.0.1-distroless` (only `java`, for production) and `3.0.1-debug` (with the JDK
tools). `3.0.1` names one release; `3.0` and `3` move to the newest release of that line, and `latest` to the
newest release. Read the release notes of the new version before any move (the GitHub releases of
`descoped/linked-data-store-project`).

## Pin what runs

Name the whole version, or the digest, so that what runs does not change by itself when a tag moves:

```sh
docker image inspect --format '{{index .RepoDigests 0}}' descoped/lds:3.0.0
docker run -d --name lds -p 127.0.0.1:9090:9090 \
  descoped/lds@sha256:0c3b06a05a8f767797f60536dcc97cfdb661d0708d374435788e8b47ce0e9b66
```

```
descoped/lds@sha256:0c3b06a05a8f767797f60536dcc97cfdb661d0708d374435788e8b47ce0e9b66
```

That digest is the index of both platforms of `3.0.0`; `docker buildx imagetools inspect descoped/lds:3.0.0` lists
the platform images under it. The same commands give the digest of any other version.

## Check the signature

Every published image is signed with cosign by the release workflow of the repository. The three variants of
3.0.0 each passed this check:

```sh
id=https://github.com/descoped/linked-data-store-project/.github/workflows/release.yml@refs/heads/master
cosign verify descoped/lds:3.0.0 --certificate-identity "$id" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

```
Verification for index.docker.io/descoped/lds:3.0.0 --
The following checks were performed on each of these signatures:
  - The cosign claims were validated
  - Existence of the claims in the transparency log was verified offline
  - The code-signing certificate was verified using trusted certificate authority certificates
```

## From 3.0.0 to 3.0.1

What 3.0.1 does with what 3.0.0 stored. The two stores of documents were tried: documents written by 3.0.0, then
3.0.1 started on the same store.

- **Documents in Neo4j**: 3.0.1 lists the documents of an entity type in the order of a property that 3.0.0 did not
  write. A document written by 3.0.0 is read by its id and is in the REST list, but GraphQL pages miss it:
  `Person(first: 1)` gave `hasNextPage: false` with three documents, and `after: "a"` gave no edges. Writing a
  document again, at the valid time of its last version, puts it back in the pages. 3.0.1 makes the index it needs
  at start (``Creating: CREATE INDEX IF NOT EXISTS FOR (n:`Person`) ON (n.`namespace`, n.`idOrder`)``).
- **Documents in PostgreSQL**: read as they are, in lists and pages too.
- **The search index**: LDS 3.0.1 puts its own schema in the Solr core, and does not find what 3.0.0 indexed. Start
  3.0.1 once with `LDS_SEARCH_INDEX_REBUILD=true` (`SKILL.md`, "Rebuild the search index"); the rebuild also
  removes the entries of 3.0.0.
- **Configuration**: a variable `LDS_...` that sets no key now stops the start, so remove any such variable
  before the move. The compose files of `lds:start` move the port with `SERVER_PORT`, not `LDS_PORT`, and ask
  `/health/ready` for the health of LDS; the image itself has no health check.

## The steps

1. **Read the release notes** of the new version. Look for changes to the configuration keys, the model and what
   is stored. A new key the image needs is given as an `LDS_` variable like the others.
2. **Pull and check** the new image: `docker pull descoped/lds:<new>`, then `cosign verify` as above.
3. **Stop LDS in order**: `docker stop -t 60 <container>` (exit code `143`). A forced stop would leave the writes
   under way to recovery.
4. **Check the saga log is empty** (`scripts/sagalog-entries.sql`, see
   [writes-and-recovery.md](writes-and-recovery.md#a-write-that-keeps-failing-dead-saga)). Rows there are writes
   not yet done: start the old LDS again and let a round of recovery finish them (about a minute), rather than
   hand them to another version. A `dead-saga` with rows is writes given up, to be sent again by their callers.
5. **Back up every store** while LDS is stopped ([backup.md](backup.md)). This is the way back.
6. **Start the new image** with the same variables, mounts, network and port: only the image changes. Keep the same
   `sagalog.config.cluster.instance-id`, so that it finds its own saga log.
7. **Check**: `sh scripts/health.sh http://localhost:9090 <container>` shows `Server started`, the same stores in
   "What it runs with", and `/health/ready` `200` with `UP`; read a document you know, and its timeline
   (`?timeline`).
8. **Keep the old container** (stopped, renamed) or its image tag until the new one has run for a while.

LDS makes its tables, constraints and indexes at start when they are missing, and never drops one; for an index
it no longer uses (Neo4j) it logs `Not using index: ...` with the statement that would drop it. Drop such an index
only once you will not go back.

## Going back

Stop the new LDS in order, then start the old image as it was. If the new version changed what is stored (the
release notes say), restore the backup of step 5 first. Writes made through the new version after the backup are
then lost, so decide quickly.

## Several LDS

With several LDS on one saga log, move them one at a time: stop one in order, start it on the new image, check it,
then the next. While one is stopped, the others complete the writes it left in its saga log within about a minute.
That two versions can share one saga log has not been shown; unless the release notes say they can, stop all and
move them together.

## With the compose file of the repository

The compose file builds LDS from the repository rather than pulling it. Moving to a newer version there is
taking the new source, building it as [Getting started](https://lds.descoped.io/docs/user/getting-started/) says
(`lds:start` covers it), and starting the stack again with `--build`; the engines and their volumes are left as they
are.

To run a published image in the stack instead, override the service with a small file next to the compose file,
as in [diagnostics.md](diagnostics.md#run-lds-on-the-debug-image), with `image: descoped/lds:<version>`.

## The engines

LDS is tested with PostgreSQL 18, Neo4j 2026.10 community edition and Solr 10.1, and with no other version. Move an
engine to another major version only when the release notes of LDS say it is supported, with the engine's own
upgrade procedure, and after a backup.
