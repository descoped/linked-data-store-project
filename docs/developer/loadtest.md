---
title: "The load test"
description: "What a run of the load test does and what it is made of."
order: 2
section: Overview
---

# The load test

This document describes the load test of Linked Data Store (LDS): what a run does, the files it is made of, the
program that turns its results into plots, and what must be kept when it is changed. It is for developers who
run the load test or change it. How to start a run is in the module's
[`README.md`](https://github.com/descoped/linked-data-store-project/blob/master/linked-data-store-loadtest/README.md).

## What this part is for

The load test starts one LDS with one [persistence provider](../glossary.md#persistence-provider), fills it, and sends
reads and writes at it from 1 to 50 threads. One of its runs also keeps the [saga log](../glossary.md#saga-log) and
the [transaction log](../glossary.md#transaction-log) in PostgreSQL, so that it measures a write as a whole LDS makes
it. It then draws throughput and latency by the number of threads. It answers two questions: do the requests to
LDS keep succeeding under load, and where does its throughput stop growing. It does not check what LDS answers.

It is run by hand on a developer's machine. The build and CI do not start it. Maven compiles and tests the
statistics program with the reactor, and that is all the build knows of it.

What the load test is to become is not decided. Until it is, it is changed only to keep it running.

## Layout

Everything is in the module `linked-data-store-loadtest`.

| File | What it is |
|---|---|
| `ldsmemory.sh`, `ldsneo4j.sh`, `ldspostgres.sh` | A whole run with one provider, the saga log in memory and no transaction log. Each calls `run-loadtest.sh` with the name of the provider. |
| `ldspostgres-logs.sh` | A whole run with the PostgreSQL provider, and the saga log and the transaction log in PostgreSQL as well. It calls `run-loadtest.sh` with `postgres-logs`. |
| `run-loadtest.sh` | The steps of a run in order. |
| `interactive.sh` | The same steps one at a time: `up` (steps 1, 2, 4 and 5 below; LDS is not filled), `configure`, `prepopulate`, `warmup` and `run` (each with the threads and the seconds), `plot`, `down`. The containers stay until `down` or the next `up`. |
| `functions.sh` | The steps as shell functions, used by both scripts. |
| `compose.yaml` | The containers of a run: `lds`, `neo4j` (profile `neo4j`), `postgres` (profile `postgres`), `httploadtest` and `controller`. Its project name is `lds-loadtest`. |
| `Dockerfile`, `.dockerignore` | The image of the controller: `eclipse-temurin:27-jre-alpine` with `bash`, `curl`, `gnuplot`, `jq`, `perl` and `zip`, the jars of the statistics program, and `prepopulate.sh`. |
| `prepopulate.sh` | Fills LDS. It runs inside the controller. |
| `httploadtest/service_override.properties` | The start options of the load tester, mounted over the file its image comes with. |
| `setup/gsim/schemas` | The [model](../glossary.md#model): 20 JSON Schema 2020-12 files, one for each [entity type](../glossary.md#entity-type). |
| `setup/gsim/templates` | One document for each entity type. `#mrid` becomes the id and `#now` the time. |
| `setup/gsim/read.json`, `write.json`, `benchmark.json`, `config.json` | What the load tester is given: what a read and a write is, the limits it reports against, and the load of a run. |
| `src/main/java` | The statistics program: `HTTPLoadTestBaselineStatistics`, `Statistics`, `GnuplotFileGenerator`. |
| `doc` | The plots the README shows. |

## How a run works

`use_provider` in `functions.sh` takes `memory`, `neo4j`, `postgres` or `postgres-logs`, and sets the variables
`compose.yaml` reads: `LDS_PERSISTENCE_PROVIDER` (`mem`, `neo4j` or `postgres`), `COMPOSE_PROFILES` (which engine is
started) and `RESULTS_FOLDER` (`lds<name>/results`, such as `ldspostgres-logs/results`). For `postgres-logs` it also
sets `LDS_SAGALOG_PROVIDER` to the saga log in PostgreSQL and `LDS_TXLOG_RAWDATA_PROVIDER` to `postgres`; for the
others it unsets them, so the compose file gives the saga log in memory and the transaction log `discard`. They are
variables of the shell, which Docker Compose reads to fill in the compose file. `compose.yaml` does not start
without `RESULTS_FOLDER`, so it is used only through the scripts.

`run-loadtest.sh` first deletes the results folder of its provider, so the results of the last run with that
provider are gone. Then it takes these steps:

1. `start_test_containers` checks that Maven has built the server module and this module, removes what an
   earlier run left (`docker compose down --volumes`), and starts the containers with `--build --wait`. `--wait`
   returns when each container is running and, when it has a health check, healthy: every one but the controller
   has one. The run stops when they do not start.
2. `check_health_of_lds` asks `/ping` from inside the controller, and fails the run unless LDS answers 200.
3. `prepopulate_data` runs `prepopulate.sh` in the controller: for each of the 20 entity types it writes 256
   documents with `PUT /ns/<Entity>/<id>`, the ids `00C8D2B7-...` to `FFC8D2B7-...`, with the time
   `2018-10-16T12:01:21Z` for `#now`. It fails the run when LDS does not accept a document.
4. `check_health_of_loadtest` asks `/HTTPLoadTest-baseline/health` of the load tester from inside the controller,
   and fails the run unless it answers 200.
5. `configure_loadtest` gives the load tester a zip file of `setup/gsim`, which holds the templates, and then
   `read.json`, `write.json` and `benchmark.json`.
6. `warm_up_lds 10 30` runs 10 threads for 30 seconds, waits 10 seconds more, and moves the result files to
   `results/warmup`, so the warm-up is not in the plots.
7. `run_loadtest_all` in `run-loadtest.sh` calls `run_loadtest <threads> 10` for 1 to 10, 12 to 20 by two, and
   25 to 50 by five threads: 21 runs. `run_loadtest` sends `config.json` with the number of threads and the seconds
   set by `jq`, with 80 reads to 20 writes and no pause, and then sleeps for the length of the run and two seconds
   more.
8. `create_gnuplot_files` runs the statistics program on the results folder, which writes
   `performance_by_threads.dat` and five gnuplot scripts, `performance_by_threads*.gnu`, there. Then gnuplot draws
   each script into the SVG file of its name, `performance_by_threads*.svg`. Both run in the controller. It fails
   the run when one of them fails.
9. `stop_test_containers` runs when the script ends after step 1, also after a failure (`trap ... EXIT`). It copies
   the log of LDS and the log of the load tester to the results folder and removes the containers and their data.
   When step 1 fails, it does not run: the containers that started are left, and
   `./interactive.sh <provider> down` removes them.

The load takes about five minutes: 42 seconds of warm-up and 21 runs of 12 seconds. Building the images, starting
the engines and filling LDS with 5 120 documents come on top.

A read is `GET http://lds:9090/ns/<Entity>/<id>` and a write is `POST` of the template of the entity type to the same
address with `?sync=true`. The parameter changes nothing: a write is a
[synchronous write](../glossary.md#synchronous-write) unless it has `sync=false`, and so are the writes of
`prepopulate.sh`. The load tester picks the entity type and one of the 256 ids at random for every request
(`#fizzle(option:...)` and `#fizzle(HEX:12)` in `read.json` and `write.json`; `HEX:12` gives as many hexadecimal
digits as its argument has characters, two). A write puts the time of the load tester's clock, in UTC, in `#now`
(`#fizzle(timestamp)`).

The load tester gives up on a read after 2.5 seconds and on a write after 5 seconds
(`command_timeout_milliseconds`), and the request has failed. It does not look at what LDS answers
(`command_response_map` is empty): a request succeeds or fails by its status and its time.

## The containers

- `lds`:
  Built from `../linked-data-store-server` and what Maven has built there: the regular image of the server's
  `Dockerfile`, made of the code of the working tree, not a released image. It is configured by environment
  variables only: an empty `LDS_GRAPHQL_SCHEMA` and `LDS_SPECIFICATION_SCHEMA=schemas` make it read the JSON
  Schema files, which are mounted at `/lds/schemas`. Search is off. In the runs of `ldsmemory.sh`, `ldsneo4j.sh`
  and `ldspostgres.sh` the saga log is in memory and the transaction log is `discard`, so a run measures LDS and the
  persistence provider. In the run of `ldspostgres-logs.sh` the saga log (database `sagalog`) and the transaction
  log (database `txlog`) are in the same PostgreSQL as the documents (database `lds`), as in the compose file of the
  server, with the owner `development` and the name `lds` of the cluster of the saga log. The image declares no
  health check, so the service has one of its own: the `wget` of busybox on `/ping`, which `--wait` waits for.

- `neo4j`, `postgres`:
  The images the tests of the providers use, with the development passwords of the server's compose file.
  `depends_on` with `required: false` makes LDS wait for the one that is started. `postgres` runs the
  `postgres-init.sql` of the server module at its first start, which makes the databases `sagalog` and `txlog`; they
  stay empty unless the run is `postgres-logs`.

- `httploadtest`:
  `cantara/httploadtest-baseline:latest`, an image from 2021 for amd64. `compose.yaml` gives it
  `platform: linux/amd64`; a file of start options in place of its own, because the file of the image gives the JVM
  an option the JDK of the image does not have; the results folder, where it writes its result files; a health
  check, `wget` on `/HTTPLoadTest-baseline/health`; and two `sysctls`, described under
  [The ports of the load tester](#the-ports-of-the-load-tester).

- `controller`:
  Does nothing by itself (`sleep infinity`). The scripts run their commands in it with `docker compose exec`, so
  that every request comes from inside the network of the run. Nothing is published on the machine.

## The ports of the load tester

The load tester opens a connection for a request and closes it itself. The operating system then holds the
port of that connection for a minute. A container has 28 232 ports for outgoing connections (32768 to 60999).
When the load tester opens connections faster than their ports come free, it has none left after some time, and
every request then fails in the load tester without reaching LDS. In the result files such a request has the text
`TAG - Application authentication failed to execute` and no status.

`compose.yaml` therefore sets `net.ipv4.ip_local_port_range` to `1024 65535` and `net.ipv4.tcp_tw_reuse` to
`1` for that container. Keep them. Without them a fast provider looks as if it fails.

## The statistics program

The load tester leaves three files for each run in the results folder: `<run>.csv`, `<run>.json` with one
object for each request, and `<run>_load_specififation.json` with the load asked for. The name is spelled
that way by the load tester.

`HTTPLoadTestBaselineStatistics.main` takes the results folder, the folder to write to, and the start of the
names of the plot files. With other arguments, or a folder that is not there or cannot be written to, it says so
on standard error and ends with exit status 0 without writing anything; in a run, gnuplot then finds no script and
the run fails. For each `.csv` file it reads the `.json` file of the same name, one request at a time, because a
file holds tens of megabytes. A request is a read when its `test_tags` holds `(Read-URL:` and a write when it holds
`(Write-URL:`. A request that is neither is counted and reported on standard error, and is in none of the statistics
of reads and writes; its concurrency is recorded all the same (`wc-`, `cc-`). A read or a write whose
`test_deviation_flag` is set is counted and reported on standard error too, and otherwise kept as any other.

`Statistics` keeps the latencies of reads and writes that succeeded and that failed, and the concurrency the
load tester reports. `sample` closes a run: it stores 23 statistics (count, count each second, minimum, mean,
percentiles, maximum and others) for each of 8 kinds (`r-`, `w-`, none, `rf-`, `wf-`, `f-`, `wc-`, `cc-`) at
the number of threads of the run.

`GnuplotFileGenerator` writes one data file, `<name>.dat`, with a block for each statistic, and five gnuplot
scripts that draw it. Each script writes an SVG file of its own name.

Keep the files it writes the same when changing it: a plot of today can then be compared with a plot of later.

## Rules to keep

- The load test is not part of the build. Nothing in `verify` or in CI starts a container of it.
- It tests the image of the server module, not a build of its own.
- The module is not in the BOM and is not released, and CI does not publish it as a SNAPSHOT: its POM sets
  `maven.deploy.skip`.
- The images of the engines are those the tests of the providers use. PostgreSQL stays at 18.
- The results folders (`ldsmemory`, `ldsneo4j`, `ldspostgres`, `ldspostgres-logs`) are ignored by git. A run
  leaves about 2 GB.

## Tests

`HTTPLoadTestBaselineStatisticsTest` writes small result files and checks the data file and the scripts the
program makes from them. Run it with `./mvnw verify -pl linked-data-store-loadtest -am`.

The scripts and the compose file have no test. They are checked by running the load test.

## What is not finished

Not finished: the load tester is an image that is not maintained. It runs under emulation on a processor that
is not amd64, which lowers the highest throughput it can ask for. It logs an exception every time its health
check is asked, which is harmless.

Not finished: in some runs the throughput drops, from some number of threads on, to about 700 to 900 requests a
second and stays there, with most of the workers of the load tester idle, LDS answering in a few milliseconds and
nothing in the log of LDS. Not every run shows it. The cause is not found.
