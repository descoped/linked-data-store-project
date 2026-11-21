# The image of the LDS server. It is made of what Maven has built, so build the reactor first; the context is
# this directory:
#
#   ./mvnw package -DskipTests
#   docker build -t lds-server linked-data-store-server
#
# LDS listens on 9090 and reads its model from /lds/schemas, which holds an example. It is configured by
# environment variables (LDS_PERSISTENCE_PROVIDER, LDS_NEO4J_DRIVER_URL, ...) or by a file mounted as
# /lds/conf/application.properties; options of the JVM are given in JAVA_TOOL_OPTIONS.
#
# The health check asks /ping on the port of LDS_HTTP_PORT (9090 without it). Give the port that way, and leave
# http.prefix as it is: a port or a prefix that is set in the mounted file is one the health check does not know,
# and the container is then said to be unhealthy while LDS runs.
#
# The JDK is the baseline of LDS and is raised by hand, together with java.version in the root POM. The image is
# distroless: glibc, CA certificates and time zones, and no shell, package manager or other program but java.

# The users of the image: those of the base, and lds.
FROM gcr.io/distroless/base-nossl-debian13:latest@sha256:3a2dbf10c2fdafe5865e2b03a4a3b9b23f2e280229a81730415379b76dc70592 AS base

# The Java runtime of LDS: the modules of the JDK that LDS and its libraries use, and nothing else. jdeps finds
# them in the jars of target/lib (the jar of this module uses nothing they do not, and leaving it out keeps the
# runtime from being linked again when only it changes); jlink links them from the JDK itself (it has no jmods).
# LDS runs on the class path, so the libraries do not have to be modules. Two modules are added that no class
# names: jdk.charsets (character sets a client or a database may name that java.base lacks) and jdk.localedata.
# The modules are not compressed by jlink: the layers of the image are, and a pull is then smaller and the start
# quicker. The JDK is built for glibc, as the image is. jlink writes an archive of the classes of the JDK (CDS), which
# halves the start of a JVM, the one of the health check included, for each of four settings of the JVM; only the
# one of the default settings is kept (14 MB; the others are 42 MB more, for heaps of more than 32 GB and for object
# headers that are not compact, which then start without it).
FROM eclipse-temurin:27-jdk AS runtime

COPY target/lib/ /lds/lib/

# jdeps writes a warning for each package two jars share before the list of modules, its last line. pipefail and
# the test make a failure of jdeps stop the build with its own message.
SHELL ["/bin/bash", "-c"]
RUN set -o pipefail \
    && modules="$(jdeps --ignore-missing-deps --multi-release 27 --print-module-deps \
            --module-path /lds/lib --class-path '/lds/lib/*' /lds/lib/*.jar | tail -n 1)" \
    && echo "jdeps: ${modules}" \
    && [ -n "${modules}" ] \
    && jlink --add-modules "${modules},jdk.charsets,jdk.localedata" \
            --strip-debug --no-man-pages --no-header-files --generate-cds-archive --output /jre \
    && rm /jre/lib/server/classes_no*.jsa \
    && /jre/bin/java -Xshare:on --list-modules

# The user lds, added to the users of the base: the image has no adduser.
COPY --from=base /etc/passwd /etc/group /users/
RUN echo 'lds:x:1000:1000:lds:/lds:/sbin/nologin' >> /users/passwd \
    && echo 'lds:x:1000:' >> /users/group

FROM base

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

COPY --from=runtime /users/passwd /users/group /etc/
COPY --from=runtime /jre ${JAVA_HOME}

WORKDIR /lds

COPY target/lib/ lib/
COPY target/linked-data-store-server-*.jar lib/
COPY target/classes/io/descoped/lds/server/HealthCheck.class health/io/descoped/lds/server/
COPY schemas/ schemas/

USER lds

EXPOSE 9090

# The check is a class of its own on a class path of its own, run by a JVM that is made to start quickly and to use
# little memory; it uses only java.base. The options here come after those of JAVA_TOOL_OPTIONS, and win.
HEALTHCHECK --start-period=60s --start-interval=1s --interval=10s --timeout=3s --retries=3 \
    CMD ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:-UsePerfData", "-Xms8m", "-Xmx8m", \
         "-cp", "/lds/health", "io.descoped.lds.server.HealthCheck"]

# java is the process of the container, so that the signal that stops the container reaches it: LDS then answers
# what it is handling and completes its sagas before it ends.
ENTRYPOINT ["java", "-cp", "/lds/lib/*", "io.descoped.lds.server.Server"]
