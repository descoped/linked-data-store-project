#!/usr/bin/env bash

#
# The steps of a load test one at a time, to try a load by hand:
#
#   ./interactive.sh memory|neo4j|postgres COMMAND [ARGUMENTS]
#
# Start with "up", end with "down". The results are in lds<provider>/results.
#

. "$(dirname "$0")/functions.sh"

if [ "${1:-}" = "help" ] || [ $# -lt 2 ]; then
  echo "Usage: $0 memory|neo4j|postgres COMMAND"
  echo "Commands: up, configure, prepopulate, warmup THREADS SECONDS, run THREADS SECONDS, plot, down"
  exit 0
fi

use_provider "$1"

case "$2" in

  "up")
    start_test_containers
    check_health_of_lds
    check_health_of_loadtest
    configure_loadtest
    ;;

  "configure")
    configure_loadtest
    ;;

  "prepopulate")
    prepopulate_data
    ;;

  "warmup")
    echo warmup
    warm_up_lds "$3" "$4"
    ;;

  "run")
    echo run
    run_loadtest "$3" "$4"
    ;;

  "plot")
    echo plot
    create_gnuplot_files
    ;;

  "down")
    stop_test_containers
    ;;

  *)
    echo "Unknown command: $2 (try: $0 help)"
    exit 1
    ;;

esac
