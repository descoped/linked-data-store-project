#!/usr/bin/env bash

#
# The steps of a load test, for run-loadtest.sh and interactive.sh. Everything runs in the containers of
# compose.yaml: LDS, the load tester (HTTPLoadTest-baseline) and the controller, which has the tools.
#

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# use_provider memory|neo4j|postgres|postgres-logs: which persistence provider LDS is tested with, and where the
# results go. postgres-logs is postgres with the saga log and the transaction log in PostgreSQL as well; the others
# keep the saga log in memory and no transaction log.
use_provider() {
  unset LDS_SAGALOG_PROVIDER LDS_TXLOG_RAWDATA_PROVIDER
  case "${1:-}" in
    memory)   export LDS_PERSISTENCE_PROVIDER=mem      COMPOSE_PROFILES= ;;
    neo4j)    export LDS_PERSISTENCE_PROVIDER=neo4j    COMPOSE_PROFILES=neo4j ;;
    postgres) export LDS_PERSISTENCE_PROVIDER=postgres COMPOSE_PROFILES=postgres ;;
    postgres-logs)
      export LDS_PERSISTENCE_PROVIDER=postgres COMPOSE_PROFILES=postgres \
        LDS_SAGALOG_PROVIDER=no.cantara.sagalog.postgres.PostgresSagaLogInitializer \
        LDS_TXLOG_RAWDATA_PROVIDER=postgres
      ;;
    *)
      echo "ERROR: the provider is memory, neo4j, postgres or postgres-logs, not '${1:-}'" >&2
      exit 1
      ;;
  esac
  OUTPUTFOLDER="lds$1"
  export RESULTS_FOLDER="${HERE}/${OUTPUTFOLDER}/results"
}

compose() {
  docker compose -f "${HERE}/compose.yaml" "$@"
}

in_controller() {
  compose exec -T controller "$@"
}

check_that_lds_is_built() {
  if ! ls "${HERE}"/target/linked-data-store-loadtest-*.jar >/dev/null 2>&1 \
     || ! ls "${HERE}"/../linked-data-store-server/target/linked-data-store-server-*.jar >/dev/null 2>&1; then
    echo "ERROR: LDS is not built. Run this in the root of the repository first: ./mvnw package -DskipTests" >&2
    exit 1
  fi
}

# Starts LDS with its provider, the load tester and the controller, and waits until each answers.
start_test_containers() {
  check_that_lds_is_built
  mkdir -p "${RESULTS_FOLDER}"
  # the load tester writes its results as a user of its own
  chmod a+rwx "${RESULTS_FOLDER}"
  echo "Removing what an earlier test left"
  compose down --volumes --remove-orphans
  echo "Starting LDS (${LDS_PERSISTENCE_PROVIDER}), the load tester and the controller"
  if ! compose up --detach --build --wait; then
    echo "ERROR: the containers of the load test did not start" >&2
    exit 1
  fi
}

stop_test_containers() {
  # what LDS and the load tester logged says why a request failed, and is gone with the containers
  if [ -d "${RESULTS_FOLDER}" ]; then
    compose logs --no-color --no-log-prefix lds > "${RESULTS_FOLDER}/lds.log" 2>&1
    # the load tester writes its log to a file of its own, not to the log of its container
    compose cp httploadtest:/home/HTTPLoadTest-baseline/logs/microservice-baseline.log "${RESULTS_FOLDER}/httploadtest.log" > /dev/null 2>&1
  fi
  echo "Stopping the containers of the load test"
  compose down --volumes --remove-orphans
}

check_health_of_lds() {
  echo Checking health of LDS service
  if [ "$(in_controller curl -s -o /dev/null --write-out "%{http_code}" http://lds:9090/ping)" != "200" ]; then
    echo ERROR: LDS is not responding correctly.
    exit 1
  fi
}

prepopulate_data() {
  echo Pre-populating data for managed domains:
  if ! in_controller /prepopulate.sh; then
    echo ERROR: Unable to pre-populate LDS.
    exit 1
  fi
}

check_health_of_loadtest() {
  echo Checking health of httploadtest-baseline container
  if [ "$(in_controller curl -s -o /dev/null --write-out "%{http_code}" http://httploadtest:8086/HTTPLoadTest-baseline/health)" != "200" ]; then
    echo ERROR: HTTPLoadTest-baseline is not responding correctly.
    exit 1
  fi
}

configure_loadtest() {
  echo Configuring loadtest ...
  echo Uploading zip-file with templates
  if [ "$(in_controller sh -c 'rm -f gsim.zip && zip -qr gsim.zip gsim && curl -s -o /dev/null --write-out "%{http_code}" -F "file=@gsim.zip" http://httploadtest:8086/HTTPLoadTest-baseline/loadTest/zip')" != "200" ]; then echo ERROR: Unable to upload zip-file; exit 1; fi
  echo Uploading read-specification
  if [ "$(in_controller curl -s -o /dev/null --write-out "%{http_code}" --data-urlencode 'jsonConfig@/loadtest/gsim/read.json' http://httploadtest:8086/HTTPLoadTest-baseline/loadTest/form/read)" != "200" ]; then echo ERROR: Unable to upload read-specification; exit 1; fi
  echo Uploading write-specification
  if [ "$(in_controller curl -s -o /dev/null --write-out "%{http_code}" --data-urlencode 'jsonConfig@/loadtest/gsim/write.json' http://httploadtest:8086/HTTPLoadTest-baseline/loadTest/form/write)" != "200" ]; then echo ERROR: Unable to upload write-specification; exit 1; fi
  echo Uploading benchmark-configuration
  if [ "$(in_controller curl -s -o /dev/null --write-out "%{http_code}" --data-urlencode 'jsonConfig@/loadtest/gsim/benchmark.json' http://httploadtest:8086/HTTPLoadTest-baseline/loadTest/form/benchmark)" != "200" ]; then echo ERROR: Unable to upload benchmark-configuration; exit 1; fi
}

# run_loadtest THREADS SECONDS: 80 reads to 20 writes, without a pause between the requests of a thread.
run_loadtest() {
  if [ "$(in_controller bash -c 'jq ".test_no_of_threads='"$1"' | .test_sleep_in_ms=0 | .test_read_write_ratio=80 | .test_duration_in_seconds='"$2"' | .test_randomize_sleeptime=false" /loadtest/gsim/config.json | curl -s -o /dev/null --write-out "%{http_code}" -H "Content-Type: application/json" -d "@-" http://httploadtest:8086/HTTPLoadTest-baseline/loadTest')" != "200" ]; then
    echo "ERROR: Unable to trigger loadtest with $1 threads"
    exit 1
  fi
  echo "Running test with $1 threads for $2 seconds"
  sleep "$2"
  sleep 2
}

warm_up_lds() {
  echo "Warming up . . ."
  run_loadtest "$1" "$2"
  sleep 10
  mkdir -p "${RESULTS_FOLDER}/warmup"
  mv "${RESULTS_FOLDER}"/*.csv "${RESULTS_FOLDER}"/*.json "${RESULTS_FOLDER}/warmup"
}

create_gnuplot_files() {
  GP_FILE_PREFIX=performance_by_threads
  echo "Generating gnuplot files"
  if ! in_controller java -cp "/opt/plotgen/lib/*" io.descoped.lds.loadtest.HTTPLoadTestBaselineStatistics /results /results $GP_FILE_PREFIX; then
    echo ERROR: Unable to turn the results into plot files.
    exit 1
  fi
  echo "Generating svg file using gnuplot"
  for plot in "" _load _total_throughput _read_latency _write_latency; do
    if ! compose exec -T -w /results controller gnuplot "${GP_FILE_PREFIX}${plot}.gnu"; then
      echo "ERROR: gnuplot did not draw ${GP_FILE_PREFIX}${plot}.gnu"
      exit 1
    fi
  done
}
