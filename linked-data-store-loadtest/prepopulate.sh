#!/usr/bin/env bash

# Fills LDS before a load test: 256 documents of each entity of the model, with the ids the load tester reads.
# Run in the controller, where the model and the templates are in /loadtest/gsim.
failed=0
for x in $(ls /loadtest/gsim/schemas | perl -n -e'/(.*)\.json/ && print "$1 "'); do
  echo $x
  for a in 0 1 2 3 4 5 6 7 8 9 A B C D E F; do
    for b in 0 1 2 3 4 5 6 7 8 9 A B C D E F; do
       status=$(cat /loadtest/gsim/templates/$x.json | sed "s/#now/2018-10-16T12:01:21Z/" | sed "s/#mrid/${a}${b}C8D2B7-0EB3-4A6D-91BB-A7451649F2F6/" | curl -s -o /dev/null --write-out "%{http_code}" -H "Content-Type: application/json" -X PUT -d @- http://lds:9090/ns/$x/${a}${b}C8D2B7-0EB3-4A6D-91BB-A7451649F2F6)
       case "$status" in
         2*) ;;
         *) failed=$((failed + 1)) ;;
       esac
    done;
  done;
done
if [ "$failed" -gt 0 ]; then
  echo "ERROR: $failed documents were not accepted by LDS"
  exit 1
fi
