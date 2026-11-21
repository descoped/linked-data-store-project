#!/bin/bash

# The demo sends its requests with HTTPie, which shows each request and its answer.
if ! command -v http >/dev/null 2>&1; then
  echo "The demo needs HTTPie (the command http), which is not installed. Install it with:" >&2
  echo >&2
  echo "  brew install httpie" >&2
  exit 1
fi

read
echo
echo
echo
echo "Create ProvisionAgreement Pengebingen"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c < 1-pengebingen.json

read
echo
echo
echo
echo "Get Pengebingen"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Add Address to Pengebingen"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c/address < 2-pengebingen-address.json

read
echo
echo
echo
echo "Get Pengebingen"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Create contact Skrue"
echo
http -p HBhb -j PUT localhost:9090/data/contact/4b2ef < 3-skrue.json

read
echo
echo
echo
echo "Get Skrue"
echo
http -p HBhb localhost:9090/data/contact/4b2ef

read
echo
echo
echo
echo "Create contact Donald"
echo
http -p HBhb -j PUT localhost:9090/data/contact/821aa < 4-donald.json

read
echo
echo
echo
echo "Get Donald"
echo
http -p HBhb localhost:9090/data/contact/821aa

read
echo
echo
echo
echo "Add Skrue to Pengebingen contacts"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c/contacts/contact/4b2ef

read
echo
echo
echo
echo "Get Pengebingen"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Add Donald to Pengebingen contacts"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c/contacts/contact/821aa

read
echo
echo
echo
echo "Get Pengebingen"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Delete Skrue from Pengebingen contacts"
echo
http -p HBhb DELETE localhost:9090/data/provisionagreement/2a41c/contacts/contact/4b2ef

read
echo
echo
echo
echo "Get Pengebingen"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c
