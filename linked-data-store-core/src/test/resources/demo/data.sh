#!/bin/bash

# The demo sends its requests with HTTPie, which shows each request and its answer.
if ! command -v http >/dev/null 2>&1; then
  echo "The demo needs HTTPie (the command http), which is not installed. Install it with:" >&2
  echo >&2
  echo "  brew install httpie      (macOS)" >&2
  echo "  pipx install httpie      (Linux, or see https://httpie.io/cli)" >&2
  exit 1
fi

read
echo
echo
echo
echo "Create ProvisionAgreement Money Bin"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c < 1-money-bin.json

read
echo
echo
echo
echo "Get Money Bin"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Add Address to Money Bin"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c/address < 2-money-bin-address.json

read
echo
echo
echo
echo "Get Money Bin"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Create contact Scrooge"
echo
http -p HBhb -j PUT localhost:9090/data/contact/4b2ef < 3-scrooge.json

read
echo
echo
echo
echo "Get Scrooge"
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
echo "Add Scrooge to Money Bin contacts"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c/contacts/contact/4b2ef

read
echo
echo
echo
echo "Get Money Bin"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Add Donald to Money Bin contacts"
echo
http -p HBhb -j PUT localhost:9090/data/provisionagreement/2a41c/contacts/contact/821aa

read
echo
echo
echo
echo "Get Money Bin"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c

read
echo
echo
echo
echo "Delete Scrooge from Money Bin contacts"
echo
http -p HBhb DELETE localhost:9090/data/provisionagreement/2a41c/contacts/contact/4b2ef

read
echo
echo
echo
echo "Get Money Bin"
echo
http -p HBhb localhost:9090/data/provisionagreement/2a41c
