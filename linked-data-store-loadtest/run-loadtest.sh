#!/usr/bin/env bash

#
# Runs the load test of LDS with one persistence provider: starts LDS, the load tester and the controller, fills
# LDS, warms it up, runs the load with 1 to 50 threads for ten seconds each, draws the plots and stops everything.
# It takes about seven minutes.
#
#   ./run-loadtest.sh memory|neo4j|postgres|postgres-logs
#
# The results are in lds<provider>/results. Build LDS first: ./mvnw package -DskipTests in the root of the
# repository.
#

. "$(dirname "$0")/functions.sh"

run_loadtest_all() {
  echo "Running loadtests with threads ranging from 1 to 50"
  for i in {1..10}; do run_loadtest $i 10; done
  for ((i=12;i<=20;i=i+2)) do run_loadtest $i 10; done
  for ((i=25;i<=50;i=i+5)) do run_loadtest $i 10; done
  sleep 5
}


###################################
## Program execution starts here ##
###################################

use_provider "${1:-}"
echo Output-folder: ${OUTPUTFOLDER}

echo Removing previous test-output
rm -rf "${RESULTS_FOLDER:?}"

start_test_containers
trap stop_test_containers EXIT
check_health_of_lds
prepopulate_data
check_health_of_loadtest
configure_loadtest
warm_up_lds 10 30
run_loadtest_all
create_gnuplot_files
