---
title: "The build"
description: "The reactor, the BOM, the rules of the build, the tests, CI."
order: 1
---

# The build

This document covers how Linked Data Store (LDS) is built: the Maven reactor and its modules, the rules the build
enforces, how the tests run, CI, Renovate, and how a release is made. It is for developers and maintainers
who change a POM, a module descriptor, a workflow or the setup of a test.

## What the build is for

The build makes every module of LDS from one repository, with one version, on one JDK. It compiles the modules
as named modules with compiler warnings as errors, runs the tests against the real engines in containers, checks
the Javadoc, and puts together the jars the server image is made of.

A build publishes nothing: no artifact and no image leaves the machine that builds. Two workflows publish. CI
publishes the SNAPSHOTs of the modules to Descoped's Maven repository, read at <https://maven.descoped.io/> (see
[CI](#ci)). A release is published by a workflow the owner starts by hand: the images of the server on Docker Hub, a
tag and a release on GitHub (see [How a release is made](#how-a-release-is-made)). No jar is released, and nothing of
LDS is published to Maven Central.

## Where things are

| File | What it holds |
|---|---|
| `pom.xml` (root) | Parent and aggregator, `io.descoped.lds:linked-data-store-project`. The list of modules, the JDK, the version of every dependency (properties), the version and the rules of the plugins (`pluginManagement`), the profiles, and the enforcer plugin with its rules (`<build><plugins>`). |
| `linked-data-store-bom/pom.xml` | The BOM. It manages the versions of the LDS modules and nothing else. |
| `<module>/pom.xml` | The dependencies of the module, and the exceptions from the lint and Javadoc rules that the module still has. |
| `<module>/src/main/java/module-info.java` | The module descriptor. Every module with code has one, except the TCK. |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | The Maven wrapper: which Maven is used, and the checksum of its download. |
| `.github/workflows/ci.yml` | The build in GitHub Actions, and the publication of the SNAPSHOTs. |
| `.github/workflows/release.yml` | The release: the images on Docker Hub, the tag and the release on GitHub. |
| `.github/workflows/claude.yml` | The review of a pull request by Claude, and its answer to an `@claude` mention. It builds nothing by itself. |
| `.github/workflows/pages.yml` | The build of the [documentation site](#the-documentation-site) and its publication to GitHub Pages. |
| `.github/renovate.json` | What Renovate moves, and the versions it is told to leave. |
| `.mvn/extensions.xml`, `.mvn/maven-build-cache-config.xml` | The [build cache](#the-build-cache) and its settings. |
| `site/` | The documentation site. |
| `.gitignore`, `.gitattributes` | Build output, local configuration and release leftovers are ignored. Every text file is checked out with LF line ends, so that a checkout on any system makes the same sources jar. |

## The modules

The reactor has the root and twelve modules, 13 projects in all. Maven builds the root first and then the modules
in an order that follows their dependencies. `<modules>` in the root POM is written in that order, so the build
order is the order of `<modules>` and of this table.

| Maven module | JPMS module | LDS modules it depends on |
|---|---|---|
| `linked-data-store-bom` | none (packaging `pom`) | none |
| `linked-data-store-persistence-provider-api` | `io.descoped.lds.persistence.api` | none |
| `linked-data-store-persistence-provider-tck` | none (class path) | persistence API |
| `linked-data-store-persistence-provider-memory` | `io.descoped.lds.persistence.memory` | persistence API; TCK (test) |
| `linked-data-store-persistence-provider-neo4j` | `io.descoped.lds.persistence.neo4j` | persistence API; TCK (test) |
| `linked-data-store-persistence-provider-postgres` | `io.descoped.lds.persistence.postgres` | persistence API; TCK (test) |
| `linked-data-store-search-provider-api` | `io.descoped.lds.search.api` | persistence API |
| `linked-data-store-search-provider-solr` | `io.descoped.lds.search.provider.solr` | search API, persistence API |
| `linked-data-store-index-queue-postgres` | `io.descoped.lds.index.queue.postgres` | search API |
| `linked-data-store-core` | `io.descoped.lds.core` | persistence API, search API; memory provider (test) |
| `linked-data-store-server` | `io.descoped.lds.server` | core; the memory, Neo4j and PostgreSQL providers, the Solr provider and the index queue in PostgreSQL (runtime) |
| `linked-data-store-loadtest` | `io.descoped.lds.loadtest` | none: the [load test](loadtest.md), whose program reads the results of a run |

Of the LDS modules, core depends on the two interfaces only. It does not depend on a provider, except on the
memory provider for its tests. The server module is the one place where core and every provider meet: its POM
names the providers and the index queue in PostgreSQL with scope `runtime`, and also the
[saga log](../glossary.md#saga-log) in PostgreSQL (`no.cantara.saga:sagalog-postgres`) and the
[transaction log](../glossary.md#transaction-log) in PostgreSQL
(`io.descoped.rawdata:rawdata-client-provider-postgres`).

The TCK, the [shared suite](../glossary.md#shared-suite) of the
[persistence providers](../glossary.md#persistence-provider), holds its test classes in `src/main/java`, because the
tests are what the module is made for. A provider depends on it with scope `test` and extends
`PersistenceIntegrationTest`. The TCK names TestNG, AssertJ, JSONassert, `slf4j-api` and `logback-classic` without
a scope, so a provider gets them for its tests through the TCK. The TCK POM tells SonarCloud to read
`src/main/java` as tests (`sonar.tests`) and only `pom.xml` as sources (`sonar.sources`).

### The module descriptors

| JPMS module | Requires | Exports | Services |
|---|---|---|---|
| `io.descoped.lds.persistence.api` | `transitive io.reactivex.rxjava3`, `transitive tools.jackson.databind`, `tools.jackson.core` | `io.descoped.lds.api.persistence` and its packages `flattened`, `streaming`, `json`, `reactivex`; `io.descoped.lds.api.specification`; `io.descoped.lds.api.json` | none |
| `io.descoped.lds.search.api` | `transitive io.descoped.lds.persistence.api`, `transitive io.reactivex.rxjava3`, Jackson | `io.descoped.lds.api.search` | none |
| `io.descoped.lds.persistence.memory` | persistence API, `io.descoped.tuple`, RxJava | nothing | provides `PersistenceInitializer` with `MemoryInitializer` |
| `io.descoped.lds.persistence.neo4j` | persistence API, Jackson, `org.neo4j.driver`, `java.logging`, `jul.to.slf4j`, `org.slf4j`, RxJava | nothing | provides `PersistenceInitializer` with `Neo4jInitializer` |
| `io.descoped.lds.persistence.postgres` | persistence API, `java.sql`, `com.zaxxer.hikari`, `org.postgresql.jdbc`, `java.logging`, `jul.to.slf4j`, RxJava | nothing | provides `PersistenceInitializer` with `PostgresDbInitializer` |
| `io.descoped.lds.search.provider.solr` | search API, persistence API, `solr.solrj`, `tools.jackson.databind`, `org.slf4j`, RxJava | nothing | provides `SearchIndexProvider` with `SolrProvider` |
| `io.descoped.lds.index.queue.postgres` | search API, `java.sql`, `com.zaxxer.hikari`, `org.postgresql.jdbc`, `org.slf4j` | nothing | provides `IndexQueueProvider` with `PostgresIndexQueueProvider` |
| `io.descoped.lds.core` | the two interfaces, dynamic-configuration, the saga libraries, `io.descoped.rawdata.api`, Undertow and XNIO, graphql-java and its extended scalars, Jackson and its MessagePack format, `com.networknt.schema`, Tika, RxJava, `org.slf4j`, `java.net.http`, `java.xml`, `jdk.unsupported` | `io.descoped.lds.core`, `io.descoped.lds.core.lifecycle`, `io.descoped.lds.test.server` | uses `PersistenceInitializer`, `SearchIndexProvider`, `IndexQueueProvider` and `SagaLogInitializer`; provides `SearchIndexProvider` with `TestSearchIndex`; opens `io.descoped.lds.graphql.graphiql` |
| `io.descoped.lds.server` | `io.descoped.lds.core`, `io.descoped.dynamic.config`, RxJava, `org.slf4j`, `org.apache.commons.logging` | nothing | none |
| `io.descoped.lds.loadtest` | Jackson, `commons.math3` | nothing | none |

A provider exports nothing: core finds it as a service and uses it through the interface. The packages of the
providers are named `io.descoped.lds.core.persistence.<name>`, `io.descoped.lds.core.search.solr` and
`io.descoped.lds.core.search.queue.postgres`, although they are not in core.

The descriptor of a built jar can be read with the `jar` tool. Here it is run in a folder that has every jar in
`lib`, as `linked-data-store-server/target` has after a build:

```sh
jar --describe-module --file lib/linked-data-store-search-provider-solr-3.0.1-SNAPSHOT.jar
```

```
io.descoped.lds.search.provider.solr@3.0.1-SNAPSHOT jar:file:///.../module-info.class
requires io.descoped.lds.persistence.api
requires io.descoped.lds.search.api
requires io.reactivex.rxjava3
requires java.base mandated
requires org.slf4j
requires solr.solrj
requires tools.jackson.databind
provides io.descoped.lds.api.search.SearchIndexProvider with io.descoped.lds.core.search.solr.SolrProvider
contains io.descoped.lds.core.search.solr
```

## What a build does, in order

What comes in is the sources and the libraries Maven fetches: from Maven Central, from the release repository
of Cantara (`cantara-releases` in the root POM) for the saga libraries, which are not on Maven Central, and the
SNAPSHOTs of Descoped's libraries from <https://maven.descoped.io/> (`descoped-snapshots-public`), read without
credentials, when a version of one is a SNAPSHOT.

For each module, in reactor order:

1. `enforcer:enforce` checks the JDK and Maven (see [JDK 27](#jdk-27)). The root and the BOM have packaging `pom`: for
   them this is the only step, apart from the last.
2. The resources are copied and the main code is compiled, as a named module where there is a `module-info.java`,
   with the lint rule (see [Every compiler warning is an error](#every-compiler-warning-is-an-error)).
3. The test code is compiled with the same compiler arguments.
4. Surefire runs the tests on the class path (see [How the tests run](#how-the-tests-run)).
5. The jar is made. Every entry of it has the time in `project.build.outputTimestamp` of the root POM, so the same
   sources give the same bytes.
6. In the server module only: before the jar is made, the jars of an earlier build are removed (`target/*.jar` and
   `target/lib`); after it, every library LDS runs with is copied to `target/lib` (`copy-dependencies`, scope
   `runtime`), also when the build cache gives the module; then Failsafe runs the tests of the image (`*IT`).
7. In the phase `verify`, the Javadoc of the module is checked (`javadoc-no-fork`, see [Javadoc](#javadoc)).

Before these steps, the build cache looks the module up (see [The build cache](#the-build-cache)). When an earlier
build of the same inputs is there, steps 2 to 7 are not run: the jar and the outputs the cache keeps are put back in
`target`, and the log says `Skipping plugin execution (cached)` for each step.

With `-T 1C`, as CI runs it, Maven builds the modules that do not depend on each other at the same time, one
thread for each core: the three providers and core run their tests side by side.

What goes out is one jar for each module in its `target` folder, and in `linked-data-store-server/target` the jar
of the server and the folder `lib`. Those two are what the image is made of; see
[the server and its image](server.md). The jar of the server has no `Main-Class`, so LDS is started with the
class path and the name of the main class. In the module directory, after a build:

```sh
cd linked-data-store-server
LDS_HTTP_PORT=19105 java -cp "target/*:target/lib/*" io.descoped.lds.server.Server
curl -i http://127.0.0.1:19105/ping
```

```
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 0
Date: Tue, 24 Nov 2026 23:34:45 GMT
```

These are the commands of the build. `./mvnw -T 1C clean verify` is what CI runs, and it needs Docker and
JDK 27. The commands were not run for this document, and their output is not shown.

```sh
./mvnw -T 1C clean verify                      # what CI runs: build, tests, Javadoc
./mvnw package -DskipTests                     # the jars, without running the tests
./mvnw verify -pl linked-data-store-core -am   # one module and what it needs
./mvnw test -pl linked-data-store-core -Dtest=DataControllerTest
./mvnw verify -P coverage                      # with JaCoCo reports
# the tests of the image only:
./mvnw verify -pl linked-data-store-server -am -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
```

## The rules of the build

The rules are those of the project (see [the invariants](architecture.md#the-invariants)) and those of the comments
in the POMs. Keep them when changing the build.

### One reactor, one version

Every module has the root POM as its parent and takes its version from it. A module depends on another LDS module
with `${project.version}`. Do not give a module a version of its own: the modules are released together, and
`versions:set` (below) sets the version of every module at once.

The modules also share the address of the project. The root POM switches off Maven's habit of adding the
`artifactId` of a module to the inherited `url` and `scm` (`child.project.url.inherit.append.path="false"` and
the three `child.scm...` attributes), because the modules are folders of one repository.

### Dependency versions are properties

The version of every library is a property in the root POM (`jackson.version`, `testcontainers.version`, ...),
and each module writes `<version>${jackson.version}</version>` where it uses the library. The root POM has no
`dependencyManagement`. This is on purpose: the BOM inherits the root POM, and anything in the
`dependencyManagement` of the root would become part of the BOM. No module POM has a version number written in
it, apart from the version of the parent.

The versions of the plugins are in `pluginManagement` of the root POM. Two plugins have their version where they
are used: the enforcer plugin, in `<build><plugins>` of the root POM, and the Sonar scanner, in the profile `sonar`.

### The BOM

`linked-data-store-bom` manages ten artifacts, all with `${project.version}`: the persistence API, the TCK, the
three persistence providers, the index queue in PostgreSQL, the search API, the Solr provider, core and the server.
It manages no library of another project, and not the load test. A new module is added to `<modules>` of the root
POM and, unless it is run only by hand as the load test is, to the BOM.

### JDK 27

LDS is built, tested and run on JDK 27. The number is written in these places, and they are changed together:

| Where | What |
|---|---|
| root `pom.xml`, `java.version` | Gives `maven.compiler.release`, so the class files are for Java 27 (class file version 71), and the version the enforcer rule `requireJavaVersion` asks for. |
| `.github/workflows/ci.yml` | `actions/setup-java` with `java-version: '27'`, distribution `temurin`. |
| `.github/workflows/claude.yml` | The same, in the job `respond`, which may run the build. |
| `linked-data-store-server/Dockerfile` | `FROM eclipse-temurin:27-jdk AS runtime`, the stage that links the runtime of the image. |
| `.github/renovate.json` | Two rules that stop Renovate from raising the JDK of the workflows and of the image. |

The enforcer rule gives the lowest JDK: a newer JDK passes it too, and the compiler then still writes class files
for Java 27. The same execution asks for Maven from 3.9.0 and below 4 (`requireMavenVersion` `[3.9.0,4)`): 3.9 is
what the build cache extension needs, and Maven stays at 3 until Maven 4 has matured, by the owner's decision.
Renovate is told the same (see [Renovate](#renovate)).

### Every compiler warning is an error

The root POM gives the compiler `-Xlint:all -Werror`. A module that has not been revised for 3.0 replaces these
arguments (`combine.self="override"`) with a list that names the categories it is not clean of. When a module is
revised, its exception is removed. An exception is never made wider.

| Module | Categories that are switched off |
|---|---|
| persistence API | `requires-automatic`, `exports`, `serial`, `missing-explicit-ctor`, `rawtypes`, `deprecation` |
| TCK | `this-escape` |
| Solr provider | `requires-automatic` |
| core | `requires-automatic`, `exports`, `serial`, `missing-explicit-ctor`, `rawtypes`, `unchecked`, `cast`, `deprecation`, `lossy-conversions`, `static` |
| memory, Neo4j, PostgreSQL, search API, index queue, server, load test | none: the rule of the root POM holds |

The PostgreSQL provider and the index queue in PostgreSQL have one exception in their code and not in their POM:
their `module-info.java` has `@SuppressWarnings("requires-automatic")`, because the JDBC driver of PostgreSQL is an
automatic module. So has the load test, for `commons.math3`.

Not finished: some of the categories that are switched off give no warning in the main code. Compiling the main
sources of these modules with `javac --release 27 -Xlint:all`, as a module with the jars LDS runs with on the module
path, gives these warnings in the categories of the lint rule:

| Module | Warnings in the main code | Switched off, but no warning in the main code |
|---|---|---|
| persistence API | `missing-explicit-ctor` 2 | `requires-automatic`, `exports`, `serial`, `rawtypes`, `deprecation` |
| Solr provider | `requires-automatic` 1 (`solr.solrj`) | none |
| core | `exports` 16, `deprecation` 13, `requires-automatic` 6, `rawtypes` 4, `serial` 2, `missing-explicit-ctor` 1 | `unchecked`, `cast`, `lossy-conversions`, `static` |

The test sources, which the same arguments compile, and the TCK, whose main code needs TestNG, were not measured,
so a category without a warning here may still be needed for them. This was measured with `javac` and not with Maven.
Remove a category from a POM only after a build with Maven passes without it.

The six automatic modules that core requires are `undertow.core`, `xnio.api`, `jackson.dataformat.msgpack`,
`org.apache.tika.core`, `com.graphqljava` and `com.graphqljava.extendedscalars`.

Not finished: the first three of them, and `solr.solrj` of the Solr provider, have no `Automatic-Module-Name` in
the manifest of their jar, so the name of the module is made from the name of the jar file. The compiler plugin
of Maven says so in every build, as a warning for core and as a line of information for the Solr provider and
for the server:

```
Required filename-based automodules detected: [jackson-dataformat-msgpack-3.0.0.jar, undertow-core-2.4.4.Final.jar,
xnio-api-3.8.17.Final.jar]. Please don't publish this project to a public artifact repository!
```

For the Solr provider the jar is `solr-solrj-10.1.0.jar`. The message does not fail the build. The message is
shown here on two lines; Maven writes it on one.

### Javadoc

The root POM gives the Javadoc plugin `doclint` `all` and `failOnWarnings` `true`: every public element has
Javadoc, and a fault in it fails the build. Four modules still replace `doclint` with `all,-missing`, which checks
the Javadoc that is there and does not ask for what is missing: the persistence API, the TCK, the memory provider
and core. The Neo4j provider, the PostgreSQL provider, the search API, the Solr provider, the index queue, the server
and the load test follow the rule of the root.

The Javadoc is checked in every build, in the phase `verify` (the execution `check-javadoc` of the root POM, goal
`javadoc-no-fork`, which writes it to `target/reports/apidocs`). It is part of the lifecycle and not a goal named
on the command line, because the build cache keeps no result of a build that names `javadoc:javadoc`.
`detectOfflineLinks` is `false`: the Javadoc of the modules is not published
anywhere, and `javadoc` warns when a module on the class path (the TCK) links to a named module.

### JPMS

- Every LDS module with code is a named module, except the TCK, which has no `module-info.java`.
- A module whose exported API shows the types of another module `requires transitive` it. The persistence API
  does so for RxJava and `tools.jackson.databind`, the search API for the persistence API and RxJava.
- A provider is registered twice: with `provides` in `module-info.java`, and with a file in `META-INF/services`.
  Both are needed. LDS runs on the class path, in the tests and in the image, and there only the file in
  `META-INF/services` is read. The `provides` line is checked by the compiler.
- `uses` is declared by core, which is the module that calls `ServiceLoader` (`PersistenceConfigurator`,
  `SearchIndexConfigurator`, `IndexQueueConfigurator`, `UndertowApplication`). The two modules that define the
  interfaces declare no `uses`. The rule of the project is worded the other way: the module that defines a service
  interface declares `uses` for it. The code and the rule do not agree.
- The module descriptors are checked when the modules are compiled, and nowhere else: nothing in the build runs
  LDS on the module path.

Not finished: the descriptor of core has three things that are there for tests.

- It provides `SearchIndexProvider` with `TestSearchIndex`, a class in `src/main/java`. The file in
  `META-INF/services` that names it is in the test resources of core, so the jar of core does not have it.
- It exports `io.descoped.lds.test.server`. In `src/main/java` that package holds the empty interface `Dummy`; the
  classes that matter (`TestServer`, `TestServerListener`) are in `src/test/java`. The comment says the export is
  needed to run tests in IntelliJ.
- It has `requires java.xml` with the comment that it belongs in test scope only. No class of core, in the main
  code or in the tests, names a type of `java.xml`.

### Libraries that are left out or pinned in core

The POM of core leaves these out of what its libraries bring: `reactive-streams` from dynamic-configuration,
`okhttp` from `graphql-java-extended-scalars`, `jackson-dataformat-yaml` from the JSON Schema validator (LDS
reads no schema written as YAML), and `jboss-threads`, `wildfly-common` and `jboss-logging` from `xnio-api`. It
names `jboss-threads` itself, with a version of its own (`jboss-threads.version`) and again without
`jboss-logging`, and `xnio-nio`, both with scope `runtime`. Only the YAML exclusion has its reason written in the
POM.

### Other rules that show in the build

- Always the latest stable version of every library, plugin, action, image and engine. Renovate moves them, and
  CI is the gate (see [Renovate](#renovate)).
- No secret but the documented development defaults of the tests and the compose file.
- CI publishes SNAPSHOTs only, and never to Maven Central. The root POM has no `<distributionManagement>`, and
  never gets one: the job that publishes the SNAPSHOTs names its target on its command line, for SNAPSHOTs only, so
  a release version has nowhere to be deployed (see [CI](#ci)). A release is started by hand, with the owner's
  go-ahead (see [How a release is made](#how-a-release-is-made)).

## The profiles

| Profile | What it does |
|---|---|
| `coverage` | Runs the tests with the JaCoCo agent (`prepare-agent`, which writes its data to `target/jacoco/jacoco.exec`, a folder the build cache keeps) and writes the report of each module in the phase `prepare-package`. In the phase `verify`, `linked-data-store-server` also writes one report of every module it is made of, from the tests of all of them (`report-aggregate`, in `target/site/jacoco-aggregate`): a class is covered by the tests of any module that runs it, as the shared suite runs the persistence API in each provider. The tests of the image run no code of the reactor in the JVM of the build and add nothing. |
| `sonar` | Sets the address, the organisation (`descoped`) and the project key (`descoped_linked-data-store-project`) of SonarCloud, and the version of the scanner. There is one SonarCloud project for the whole reactor. Coverage is read from the joined report of the `coverage` profile, and from a module's own report for the load test, which is outside it. The rules that do not fit a file are switched off for that file (`sonar.issue.ignore.multicriteria`), and the comment above the profile gives the reason of each. The scanner runs only when its goal is named, as CI does. |
No profile is active by default. No profile publishes: LDS is never published to Maven Central, and the POM has no
profile, plugin or setting for it.

## The Maven wrapper

`./mvnw` is a script and nothing else (`distributionType=only-script`): no jar of the wrapper is in the
repository. On first use it downloads the Maven named in `.mvn/wrapper/maven-wrapper.properties`, now 3.10.1,
compares the download with `distributionSha256Sum`, and unpacks it under `~/.m2/wrapper/dists`. If the checksum
differs, the script stops. CI runs `./mvnw`. Besides the properties of the wrapper, the folder `.mvn` holds the build
cache: the extension that adds it (`extensions.xml`) and its settings (`maven-build-cache-config.xml`).

## The build cache

`maven-build-cache-extension` (in `.mvn/extensions.xml`) keeps the result of each module in `~/.m2/build-cache`,
under a SHA-256 of the module's inputs:

- its sources, test sources and resources, its effective POM (the profiles of the build count), and the
  checksums of the modules and libraries it depends on;
- in the server module, also the files the tests of the image read outside `src`: `Dockerfile`, `.dockerignore`,
  `compose.yaml`, `postgres-init.sql` and `schemas` (the properties `maven.build.cache.input.*` of its POM).

A build that finds a module's inputs unchanged does not compile, test or document it again. It puts back the jar and
the folders of `target` that SonarCloud and the joined coverage report read: `classes`, `test-classes`, `jacoco`,
`site`, `surefire-reports` and `failsafe-reports`. A module whose inputs changed, and every module that depends on
it, is built and tested as always. So a change in core tests core and the server again, and takes the providers from
the cache; a change in the persistence API tests every module.

Only a build that starts with `clean` is kept (`mandatoryClean`): a build without it can take a plugin's result as
up to date from what an earlier build left in `target`, as the Javadoc plugin does, and a success kept from it
would not be one. A build that also names `javadoc:javadoc` on the command line keeps nothing.

A result is used again only for a build that asks for the same tests (`executionControl` in the settings): one kept from
a build limited with `-Dtest`, `-Dit.test`, `-Dgroups` or `-DexcludedGroups` is not taken for a build that runs them
all, which builds the module again. A build that skips the tests (`-DskipTests`) may take any result.

`-Dmaven.build.cache.skipCache=true` builds everything without looking the cache up, and keeps the result;
`-Dmaven.build.cache.enabled=false` builds without the cache.

The checksum in the file is the SHA-256 of the zip that `distributionUrl` names:

```sh
curl -sS -f -L -o apache-maven-3.10.1-bin.zip \
  https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.1/apache-maven-3.10.1-bin.zip
shasum -a 256 apache-maven-3.10.1-bin.zip
```

```
9c1ef9174ef250a5009ed0dec97cbe8699acf7479add4e62bf7b0ea791c0adb5  apache-maven-3.10.1-bin.zip
```

When Maven is raised, by Renovate or by hand, Renovate does not change the checksum, and the wrapper refuses the
new download until it is set. Set `distributionSha256Sum` to the SHA-256 of the new
`apache-maven-<version>-bin.zip`, after comparing that zip with the `.sha512` that Maven publishes next to it.
A Maven 4 does not pass the enforcer rule, and Renovate does not offer one.

## How the tests run

### Two frameworks, one run

New and revised tests are JUnit 6 with AssertJ. The older tests are TestNG 7. Surefire 3.6.1 runs both on the
JUnit Platform: it picks its provider for the platform, and when TestNG is on the class path it adds the TestNG
engine for the platform by itself. A module needs no configuration for this; it names `junit-jupiter`, `testng`
or both as test dependencies. The log of a build shows it:

```
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] TestNG is present. Adding per default org.junit.support:testng-engine:1.1.0
```

No POM names `testng-engine`. Its version is the one Surefire picks, and no property of the root POM sets it.

A class that extends the shared suite (`PersistenceIntegrationTest`) or the Reactive Streams TCK stays TestNG,
because those are. In core, a test that takes its server from `TestServerListener` is TestNG; a new test class
that starts a server of its own is JUnit.

A test that waits for something to happen waits with Awaitility (`org.awaitility:awaitility`, a test dependency
of core, the Solr provider, the index queue and the server): it asks again at an interval until the condition holds,
for at most a given time, or it checks that a condition holds for a given time.

Not finished: some tests wait a fixed time with `Thread.sleep` to see that something does not happen in it:
`IndexQueueWorkerTest` (100 ms), `IndexQueueWorkerLifecycleTest` (ten polls of the worker, and 300 ms),
`PostgresIndexQueueTest` (700 ms, twice) and `SolrSearchIndexTest` (twice the idle timeout of the connections to
Solr). The sleep of five minutes in `ReadinessTest` is the check that does not answer, which the test interrupts.

### On the class path

`useModulePath` is `false` for Surefire and Failsafe in the root POM. The tests are in the same packages as the
code they test, and they run on the class path together with it. So a test finds a provider through
`META-INF/services`, not through `module-info.java`.

### Surefire and Failsafe

Surefire runs the classes it finds by its default name patterns (`*Test`, which `*IntegrationTest` also matches)
in the phase `test`. Failsafe is set up in the server module only. It runs `ServerImageIT` and `ServerStackIT`
after the jars are made, so `verify` runs them and `test` and `package` do not.

Surefire finds no test to run in three modules: the TCK (its suite is an abstract class in `src/main/java`, and
it runs in the providers), the search API (it has no tests) and the server (its tests are the `*IT` classes). The load
test has one test, of the program that reads its results.

### Containers

The tests run on the real engines, in containers that Testcontainers starts. The build needs Docker, and for the
server module also the `docker` command with `compose`.

| Module | Image | Named in |
|---|---|---|
| PostgreSQL provider | `postgres:18-alpine` | `Postgres18` |
| Neo4j provider | `neo4j:2026.10-community` | `Neo4j2026` |
| Solr provider | `solr:10.1` | `Solr10` |
| index queue | `postgres:18-alpine` | `PostgresIndexQueueTest` |
| core | `postgres:18-alpine` | `SagaLogPostgresTest` |
| server | the image built from the `Dockerfile`; the images of `compose.yaml` | `ServerImageIT`, `ServerStackIT` |

In the provider modules one container serves every test class of the module. It is started on first use and
removed by Testcontainers when the JVM ends. In core, `SagaLogPostgresTest`, and in the index queue,
`PostgresIndexQueueTest`, start a container before their tests and stop it after them.

### LoopbackPorts

Each module that starts a container has a class `LoopbackPorts` in its tests, and a file
`META-INF/services/org.testcontainers.core.CreateContainerCmdModifier` in its test resources that names it. There
are six: in the PostgreSQL provider, the Neo4j provider, the Solr provider, the index queue, core and the server. The
six classes are the same but for the package.

Testcontainers calls `LoopbackPorts.modify` for every container it creates, also the one it starts for itself to
clean up (Ryuk). The method changes each port that is published without an address to be published on 127.0.0.1.
Without it a port is published on every address of the machine, and on macOS Docker Desktop can then pick a port
that another program listens on at 127.0.0.1. The test reaches that program and not the container, and the build
fails with "Could not connect to Ryuk" or with an engine that never answers.

The code that starts a container calls `LoopbackPorts.check` on it. The check fails with a message that names the
file in `META-INF/services` when a port is published on another address. A new module that starts containers
needs its own copy of the class and of the file.

### The server of the tests in core

The tests of core start LDS in the JVM with the memory provider (`TestServerListener`), one LDS for each
configuration the tests ask for. The port is picked at random between 9000 and 9499. A port is taken only when it
is free in two ways: on every address of the machine, and on 127.0.0.1 alone
(`TestServerListener.findFreePort`).

### Tests that are disabled

12 tests in core are disabled in the source. They are TestNG tests, and the build does not count them, not as run
and not as skipped.

| Class | Disabled |
|---|---|
| `DataControllerTest` | `testGetEmptyDomainList`, `testPutDomain`, `testGetDomainList`, `testGetDomain`, `testGetMissingDomain` (`enabled = false`) |
| `NamespaceControllerTest` | `thatPUTOnLegalNamespaceAndDocumentAcceptsWith200` (`enabled = false`) |
| `PersistenceLinksConnectionFetcherTest` | `testForwardPagination`, `testBackwardPagination` (`@Ignore`; the comment says the method hangs) |
| `PersistenceRootConnectionFetcherTest` | the whole class, four tests (`@Ignore`; the comment says the tests hang) |

The two test classes of the Reactive Streams TCK (`AsyncIterablePublisherTest` in the memory provider,
`ResultSetPublisherIntegrationTest` in the PostgreSQL provider) each report 11 of their 38 tests as skipped. Those
are not disabled in the source of LDS.

A change that switches tests on is run about ten times before it is pushed:

```sh
for n in $(seq 1 10); do ./mvnw -B -q verify || break; done
```

## CI

`.github/workflows/ci.yml` has two jobs: `Build (JDK 27)` (`build`), on `ubuntu-latest` with a limit of 30
minutes, and `Publish SNAPSHOTs` (`snapshot`), which runs after it.

It runs on:

- a push to `master`;
- a push of a tag of the form `1.2.3`;
- a pull request to `master`;
- a start by hand (`workflow_dispatch`), on any branch.

A push or a pull request starts it only when it changes what can change the products: the root `pom.xml`,
`mvnw`, `.mvn/`, a file in a module folder (`linked-data-store-*/`) that is not a Markdown file, or `ci.yml`
itself. A change of the documentation (`docs/`, `README.md`, any Markdown file), the site (`site/`), the Claude Code
plugin (`plugins/`, `.claude-plugin/`), `LICENSE`, `.claude/`, `claude.yml`, `renovate.json` or `.gitignore` alone
does not start it. A tag starts it whatever it changes.
A newer push to the same pull request ends the run that is in progress. A run on a branch is not ended by the next,
so a publication of SNAPSHOTs is not stopped halfway.

The job `build` checks out the code, sets up JDK 27 (Temurin) with the Maven cache, restores the build cache, and
runs one of two commands:

| When | Command |
|---|---|
| a pull request from a fork, or a start by hand | `./mvnw --batch-mode --no-transfer-progress -T 1C clean verify` |
| a push, or a pull request from this repository | the same with `-P coverage`, then the SonarCloud scanner with `-P sonar` and the build cache off |

The build cache (see [The build cache](#the-build-cache)) is kept between runs with `actions/cache`, one entry for each
run. A pull request starts from the newest entry of its own branch, else of `master`, and takes the modules that have
not changed from it. The name of an entry holds the version of the JDK and a hash of `mvnw`, `.mvn/` and `ci.yml`, which
the cache does not count as inputs of a module: a pull request that changes one of them, as Renovate does when it moves
an action, the wrapper or the extension, finds no entry and tests every module. A push to `master` or of a tag, and a
start by hand, give `-Dmaven.build.cache.skipCache=true`: they build and test every module, so every engine is tested at
every merge, and their entry is the one the pull requests start from.

The SonarCloud step is not a gate: it has `continue-on-error`, so a fault of SonarCloud or of its token (the
secret `SONARCLOUD_TOKEN`) does not fail the build. A pull request from a fork gets no secrets, so it gets no
analysis. The checkout fetches the whole history only when the analysis runs.

The job `build` has the permission `contents: read` and no step that deploys, pushes or uploads.

The job `snapshot` publishes the SNAPSHOTs of every module but the load test (`maven.deploy.skip` in its POM), each
jar with its sources jar, to Descoped's repository of SNAPSHOTs in AWS CodeArtifact, which is read publicly at
<https://maven.descoped.io/>. It runs when `build` has passed, and only on a push to `master`, with the version of the
POMs (`3.0.1-SNAPSHOT`), or on a start by hand on a branch, with a version of the branch's own,
`<version>-<branch>-SNAPSHOT`, so that it never replaces the SNAPSHOT of `master`; never on a pull request or a
tag. It refuses a version that is not a SNAPSHOT. It gets short-lived credentials of AWS by OpenID Connect, for a
role whose name is the one input of the job, the organisation secret `CODEARTIFACT_SNAPSHOTS_ROLE_ARN`; the account
and the address to publish to are looked up while it runs and are masked in the log, and are written nowhere in the
repository. It deploys with `-DaltSnapshotDeploymentRepository`, which names the target on its command line and holds
for SNAPSHOTs only: the root POM has no `<distributionManagement>`, so a release version has nowhere to be deployed.
Two runs on one branch do not run at the same time. CI publishes no release and no image, and nothing to Maven
Central.

The actions are pinned by commit, with the version in a comment. Renovate moves them.

`.github/workflows/claude.yml` has two jobs. `review` reads a pull request when it is opened or reopened and
posts one review; it does not run the tests, and its token cannot change the code. It reviews code: a pull
request that changes nothing but Markdown files, `docs/`, `site/`, the Claude Code plugin (`plugins/`,
`.claude-plugin/`), `LICENSE`, `.claude/`, `claude.yml`, `renovate.json` or `.gitignore` does not start it.
`respond` answers an `@claude` mention from the owner, a member or a collaborator, in a pull request or an issue,
and may run `./mvnw ... verify` or `package` on JDK 27 before it pushes a fix. Both jobs need the secret
`CLAUDE_CODE_OAUTH_TOKEN`.

## Renovate

`.github/renovate.json` extends `config:recommended` and has the dependency dashboard.

| Rule | What it does |
|---|---|
| managers `maven` and `github-actions` | Updates are merged by themselves (`automerge`); the description of the rule says that CI is the gate. The rule names these two managers only, so it does not hold for the images of the `Dockerfile` and of `compose.yaml`. |
| `java-jdk` in the workflows | Switched off. The JDK of the workflows is raised by hand. |
| image `eclipse-temurin` | Only versions that start with 27. |
| image `postgres` | Only versions that start with 18. |
| manager `maven-wrapper`, `org.apache.maven:apache-maven` | Only versions that start with 3. The wrapper itself still moves. |
| manager `maven-wrapper` | The pull request gets the label `maven-wrapper` and a note that says to set `distributionSha256Sum` by hand. |
| vulnerability alerts | Label `security`, merged by themselves. |

Three versions are held on purpose:

- **JDK 27**, the baseline. It is raised in `java.version`, the workflows and the image together.
- **PostgreSQL 18**, by the owner's decision. The owner says when it is raised.
- **Maven 3**, by the owner's decision, until Maven 4 has matured. The owner says when it is raised.

The rule for `postgres` is about the image. The JDBC driver of PostgreSQL (`postgresql.version`) is a Maven
dependency and moves like the others.

The images of the tests are also written as text in Java classes (`Postgres18`, `Neo4j2026`, `Solr10`,
`SagaLogPostgresTest`). `renovate.json` sets up no manager that reads Java sources. The rule is that the compose
file uses the images the tests of the providers use, so compare the two by hand when one of them moves.

## The documentation site

The documents in `docs/` are also a site, which GitHub Pages serves at <https://lds.descoped.io/>, with a landing
page in front of them. The site is in `site/`, outside the reactor: a static site made with krantho and Next.js,
which Maven does not build. `site/README.md` says how to build it and where each page comes from.

`lds.descoped.io` is the custom domain of the repository's Pages, set in its settings, with HTTPS enforced. Its DNS
record, a CNAME to `descoped.github.io`, is kept outside this repository. The site is on the root of the domain, and
GitHub forwards `descoped.github.io/linked-data-store-project/` to it.

`.github/workflows/pages.yml` builds the site with pnpm on Node 26 and publishes it to Pages on a push to `master`
that changes `docs/`, `site/` or the workflow, and on a start by hand. It has two jobs: `build`, with the permission
`contents: read`, and `deploy`, with `pages: write` and `id-token: write`, which only GitHub Pages is given.

Each document starts with front matter that the site reads: `title`, `description` and `order`. GitHub shows it as a
table at the top of the file.

## How a release is made

A milestone and a release are both made by hand, with the owner's go-ahead for each one.

### A milestone

A milestone, such as `3.0.0-M1`, is a tag of the source and a release on GitHub with its notes. Nothing is built for
it and nothing is published: no jar, no image, nothing on Maven Central. It is made with two pull requests and a tag:

1. The first sets the version of every POM to the milestone, sets `project.build.outputTimestamp` in the root POM
   to the time of the milestone, sets `softwareVersion` in `site/krantho.config.ts`, and adds the milestone's notes
   to `RELEASE-NOTES.md`, in a section of its own, with links that are whole addresses so they also lead somewhere
   in the release on GitHub. CI builds and tests it, as any pull request that changes code.
2. Its commit on `master` is tagged with the version alone (`3.0.0-M1`), and the release on GitHub is made from the
   tag with the milestone's section of the notes, without its heading:

   ```sh
   git tag -a 3.0.0-M1 -m 3.0.0-M1 && git push origin 3.0.0-M1
   awk '/^## 3.0.0-M1$/{f=1;next} /^## /{f=0} f' RELEASE-NOTES.md > notes.md
   gh release create 3.0.0-M1 --prerelease --title "LDS 3.0.0-M1" --notes-file notes.md
   ```
3. The second pull request sets the versions back to `3.0.0-SNAPSHOT`.

The push of a milestone's tag does not start CI: its tags are of the form `1.2.3` only.

### A release

A release, such as `3.0.0`, is three images of the server on Docker Hub (`descoped/lds`), for linux/amd64 and
linux/arm64, a tag of the version and a release on GitHub with its notes. No jar is released, and nothing is
published to Maven Central. It is made with two pull requests and a start of the release workflow by hand:

1. The first pull request sets the version of every POM to the release, sets `project.build.outputTimestamp` in
   the root POM to the time of the release, and adds the release's notes to `RELEASE-NOTES.md` in a section
   `## <version>`, with links that are whole addresses. `versions:set` sets the version of every module, and the
   time with it:

   ```sh
   ./mvnw versions:set -DnewVersion=3.0.0 -DgenerateBackupPoms=false -DprocessAllModules=true
   ```

   CI builds and tests it, as any pull request that changes code, and again on `master` when it is merged.
2. The owner starts the release on `master`:

   ```sh
   gh workflow run release.yml -f version=3.0.0
   ```

   `.github/workflows/release.yml` runs in the environment `release`, which waits for the owner's approval and
   allows `master` only. It logs in to Docker Hub with the secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` of the
   organisation `descoped`, given to this repository. It checks first, and publishes
   nothing when a check fails: the version has the form `1.2.3`, the POMs on `master` have it, its tag does not
   exist, `RELEASE-NOTES.md` has its section, no dependency is a SNAPSHOT (the enforcer rule `requireReleaseDeps`,
   since a SNAPSHOT would make the release whatever that SNAPSHOT was that day), and CI (`Build (JDK 27)`) passed on
   the commit. It then packages the
   jars without testing them again, builds the three images of the Dockerfile for both platforms with an SBOM and
   provenance, pushes them, signs each by its digest with cosign (keyless, by the identity of the workflow on
   GitHub), and at the end tags the commit with the version alone and makes the release on GitHub from the notes.
3. The second pull request sets the versions to the next SNAPSHOT. The site (its landing page, and
   `softwareVersion` in `site/krantho.config.ts`) is changed for the release then, when the images are there: a
   change of `site/` is published at once.

| Image | Tags | Target of the Dockerfile |
|---|---|---|
| regular | `3.0.0`, `3.0`, `3`, `latest` | `regular` |
| distroless | `3.0.0-distroless`, `3.0-distroless`, `3-distroless`, `distroless` | `distroless` |
| debug | `3.0.0-debug`, `3.0-debug`, `3-debug`, `debug` | `debug` |

A run can be repeated until the tag is made. The jars of one commit are the same bytes in every build
(`project.build.outputTimestamp`), and so are the images: every image the Dockerfile starts from is named by its
digest, and the workflow sets the time of every file and layer to the time of the commit (`SOURCE_DATE_EPOCH`,
`rewrite-timestamp`). Two builds of one commit without a cache gave the same digest. A run again after a failure
pushes the same images. The signatures, the SBOM and the provenance are made again.

The tags `3.0`, `3` and `latest` move to every release, so a release of an older line moves them backwards.

## Not finished

- The TCK is not a named module.
- Four modules have an exception from the Javadoc rule and four from the lint rule; some of the lint categories that
  are switched off give no warning in the main code (see
  [Every compiler warning is an error](#every-compiler-warning-is-an-error)).
- The descriptor of core provides a class that is for tests, exports a package that is for tests, and requires
  `java.xml`, which no class of core uses (see JPMS above).
- The JPMS rule of the project says that the module that defines a service interface declares `uses` for it. In
  the code core declares `uses`, and the two modules that define the interfaces do not.
- Core and the Solr provider require automatic modules whose names are made from the names of jar files, and Maven warns
  against publishing such a project (see [Every compiler warning is an error](#every-compiler-warning-is-an-error)).
- 12 tests in core are disabled.
- Some tests wait a fixed time with `Thread.sleep` (see [Two frameworks, one run](#two-frameworks-one-run)).
- The search API declares `testng`, `assertj-core` and `jsonassert` as test dependencies and has no tests. Core
  declares `commons-math3` as a test dependency, and no class uses it.
- The root POM has a repository of its own for the saga libraries. Its comment says the repository goes when core
  stops using them.
