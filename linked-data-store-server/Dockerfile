# The image of the LDS server, in three variants. It is made of what Maven has built, so build the reactor first;
# the context is this directory:
#
#   ./mvnw package -DskipTests
#   docker build -t lds-server linked-data-store-server                          # the regular image, with a shell
#   docker build -t lds-server:distroless --target distroless linked-data-store-server
#   docker build -t lds-server:debug --target debug linked-data-store-server
#
# The regular image (the last stage, built when no target is named) has a shell and the small tools of busybox, for
# looking around in a running container. The distroless image has no program but java, for production, and above
# all where no shell is ever used (Cloud Run, Fargate, Lambda). The debug image is the regular one with the tools
# of the JDK for diagnostics: remote JMX, a remote debugger, jcmd, jstack, jmap, jstat, jhsdb, JFR and heap dumps.
# All three hold the same LDS and the same configuration.
#
# LDS listens on 9090 and reads its model from /lds/schemas, which holds an example. It is configured by
# environment variables (LDS_PERSISTENCE_PROVIDER, LDS_NEO4J_DRIVER_URL, ...) or by a file mounted as
# /lds/conf/application.properties; options of the JVM are given in JAVA_TOOL_OPTIONS.
#
# The health check asks /ping on the port of LDS_HTTP_PORT (9090 without it). Give the port that way, and leave
# http.prefix as it is: a port or a prefix that is set in the mounted file is one the health check does not know,
# and the container is then said to be unhealthy while LDS runs.
#
# The JDK is the baseline of LDS and is raised by hand, together with java.version in the root POM. The bases are
# Google's distroless images of Debian 13 (glibc, CA certificates, time zones). Every image the build starts from is
# named by its digest, so a build of the same commit gives the same image (with SOURCE_DATE_EPOCH and
# rewrite-timestamp, as the release does); Renovate moves the digests.

# No shell, no package manager, no program.
FROM gcr.io/distroless/base-nossl-debian13:latest@sha256:3a2dbf10c2fdafe5865e2b03a4a3b9b23f2e280229a81730415379b76dc70592 AS base-distroless

# The same, with busybox in /busybox: sh, wget, ls, ps, top, vi, ...
FROM gcr.io/distroless/base-nossl-debian13:debug@sha256:2548abf86d3e319cdfd0184dce7417a61087724c94ca29daf9b4bddba677f5f5 AS base-shell

# The Java runtimes. jdeps finds the modules of the JDK that LDS and its libraries use in the jars of target/lib (the
# jar of this module uses nothing they do not, and leaving it out keeps the runtimes from being linked again when only
# it changes); jlink links them from the JDK itself (it has no jmods). LDS runs on the class path, so the libraries do
# not have to be modules. Two modules are added that no class names: jdk.charsets (character sets a client or a
# database may name that java.base lacks) and jdk.localedata. The modules are not compressed by jlink: the layers of
# the image are, and a pull is then smaller and the start quicker. The JDK is built for glibc, as the bases are.
#
# /jre, of the regular and the distroless image, has no debug information, and of the archives of classes (CDS) that
# jlink writes for four settings of the JVM only the one of the default settings (the others are for heaps of more
# than 32 GB and for object headers that are not compact, which then start without one); the archive halves the start
# of a JVM, the one of the health check included. /jre-debug, of the debug image, keeps the debug information and has
# the modules of diagnostics as well.
FROM eclipse-temurin:27-jdk@sha256:f67511a7888677dbc4fb64ee6f17f9237c24d2b110f65a61716a3b516b6b3fa8 AS runtime

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
    && /jre/bin/java -Xshare:on --list-modules \
    && jlink --add-modules "${modules},jdk.charsets,jdk.localedata,java.instrument,java.management.rmi,jdk.attach,jdk.hotspot.agent,jdk.jcmd,jdk.jdi,jdk.jdwp.agent,jdk.jfr,jdk.jstatd,jdk.management,jdk.management.agent,jdk.management.jfr" \
            --no-man-pages --no-header-files --generate-cds-archive --output /jre-debug \
    && rm /jre-debug/lib/server/classes_no*.jsa \
    && /jre-debug/bin/jcmd -h > /dev/null

# The users of the bases, and lds (1000), without a password and with no shell to log in to: the bases have no
# adduser. The two bases have the same users.
COPY --from=base-distroless /etc/passwd /etc/group /users/
RUN echo 'lds:x:1000:1000:lds:/lds:/sbin/nologin' >> /users/passwd \
    && echo 'lds:x:1000:' >> /users/group

# LDS: every jar in /lds/lib, the class of the health check on a class path of its own, the example model, and the
# users. Nothing in it belongs to the user lds.
FROM scratch AS app

COPY --from=runtime /users/passwd /users/group /etc/
COPY target/lib/ /lds/lib/
COPY target/linked-data-store-server-*.jar /lds/lib/
COPY target/classes/io/descoped/lds/server/HealthCheck.class /lds/health/io/descoped/lds/server/
COPY schemas/ /lds/schemas/

# The three images. They differ in their base and their runtime; the rest is the same in each, and is kept the same.
#
# The health check of the distroless image is a class of its own on a class path of its own, run by a JVM that is
# made to start quickly and to use little memory; it uses only java.base. That JVM also reads JAVA_TOOL_OPTIONS and
# JDK_JAVA_OPTIONS: its heap options come after them and win, and it names no garbage collector, since a second one
# given there would stop it from starting. An option there that opens a port (JMX, JDWP) would clash with LDS. The images with a shell ask with wget of busybox, so that no JVM but the one of LDS reads
# the options of the JVM: an option that opens a port (remote JMX, a remote debugger) would fail in a second JVM.
#
# java is the process of the container, so that the signal that stops the container reaches it: LDS then answers
# what it is handling and completes its sagas before it ends.

FROM base-distroless AS distroless

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
COPY --from=runtime /jre ${JAVA_HOME}
COPY --from=app / /
WORKDIR /lds
USER lds
EXPOSE 9090
HEALTHCHECK --start-period=60s --start-interval=1s --interval=10s --timeout=3s --retries=3 \
    CMD ["java", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-Xms8m", "-Xmx8m", \
         "-cp", "/lds/health", "io.descoped.lds.server.HealthCheck"]
ENTRYPOINT ["java", "-cp", "/lds/lib/*", "io.descoped.lds.server.Server"]

FROM base-shell AS debug

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/busybox"
COPY --from=runtime /jre-debug ${JAVA_HOME}
COPY --from=app / /
WORKDIR /lds
USER lds
EXPOSE 9090
HEALTHCHECK --start-period=60s --start-interval=1s --interval=10s --timeout=3s --retries=3 \
    CMD ["/busybox/sh", "-c", "wget -q -O /dev/null \"http://127.0.0.1:${LDS_HTTP_PORT:-9090}/ping\" || exit 1"]
ENTRYPOINT ["java", "-cp", "/lds/lib/*", "io.descoped.lds.server.Server"]

FROM base-shell AS regular

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/busybox"
COPY --from=runtime /jre ${JAVA_HOME}
COPY --from=app / /
WORKDIR /lds
USER lds
EXPOSE 9090
HEALTHCHECK --start-period=60s --start-interval=1s --interval=10s --timeout=3s --retries=3 \
    CMD ["/busybox/sh", "-c", "wget -q -O /dev/null \"http://127.0.0.1:${LDS_HTTP_PORT:-9090}/ping\" || exit 1"]
ENTRYPOINT ["java", "-cp", "/lds/lib/*", "io.descoped.lds.server.Server"]
