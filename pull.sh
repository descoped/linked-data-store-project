#!/bin/sh

echo "git -C linked-data-store-persistence-provider-api/ pull --rebase"
git -C linked-data-store-persistence-provider-api/ pull --rebase

echo "git -C linked-data-store-search-provider-api/ pull --rebase"
git -C linked-data-store-persistence-provider-api/ pull --rebase

echo "git -C linked-data-store-search-provider-solr/ pull --rebase"
git -C linked-data-store-search-provider-solr/ pull --rebase

echo "git -C linked-data-store-persistence-provider-memory/ pull --rebase"
git -C linked-data-store-persistence-provider-memory/ pull --rebase

echo "git -C linked-data-store-persistence-provider-neo4j/ pull --rebase"
git -C linked-data-store-persistence-provider-neo4j/ pull --rebase

echo "git -C linked-data-store-persistence-provider-postgres/ pull --rebase"
git -C linked-data-store-persistence-provider-postgres/ pull --rebase

echo "git -C linked-data-store-core/ pull --rebase"
git -C linked-data-store-core/ pull --rebase

echo "git -C linked-data-store-docker-memory/ pull --rebase"
git -C linked-data-store-docker-memory/ pull --rebase

echo "git -C linked-data-store-docker-neo4j/ pull --rebase"
git -C linked-data-store-docker-neo4j/ pull --rebase

echo "git -C linked-data-store-docker-postgres/ pull --rebase"
git -C linked-data-store-docker-postgres/ pull --rebase

echo "git -C linked-data-store-docker-foundationdb/ pull --rebase"
git -C linked-data-store-docker-foundationdb/ pull --rebase

echo "git -C linked-data-store-persistence-provider-foundationdb/ pull --rebase"
git -C linked-data-store-persistence-provider-foundationdb/ pull --rebase

echo "git -C linked-data-store-documentation/ pull --rebase"
git -C linked-data-store-documentation/ pull --rebase

echo "git -C linked-data-store-loadtest/ pull --rebase"
git -C linked-data-store-loadtest/ pull --rebase

echo "git -C linked-data-store-docker-build-base/ pull --rebase"
git -C linked-data-store-docker-build-base/ pull --rebase

echo "git -C linked-data-store-persistence-provider-test/ pull --rebase"
git -C linked-data-store-persistence-provider-test/ pull --rebase

echo "git -C lds-cluster-test/ pull --rebase"
git -C lds-cluster-test/ pull --rebase

echo "git -C txlog-api/ pull --rebase"
git -C txlog-api/ pull --rebase

echo "git -C txlog-pulsar/ pull --rebase"
git -C txlog-pulsar/ pull --rebase
