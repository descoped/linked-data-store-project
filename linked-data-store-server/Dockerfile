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
# The JDK is the baseline of LDS and is raised by hand, together with java.version in the root POM. Both stages are
# Alpine (musl) of the same release, the one the JDK image is built on.

# The Java runtime of LDS: the modules of the JDK that LDS and its libraries use, and nothing else. jdeps finds
# them in the jars; jlink links them from the JDK itself (it has no jmods). LDS runs on the class path, so the
# libraries do not have to be modules. Three modules are added that no class names: jdk.charsets (the character
# sets a database may send that java.base lacks), jdk.crypto.ec and jdk.localedata. The modules are not compressed
# by jlink: the layers of the image are, and a pull is then smaller (73 MB against 82 MB) and the start quicker.
FROM eclipse-temurin:27-jdk-alpine AS runtime

COPY target/lib/ /lds/lib/
COPY target/linked-data-store-server-*.jar /lds/lib/

# jdeps writes a warning for each package two jars share before the list of modules, its last line.
RUN modules="$(jdeps --ignore-missing-deps --multi-release 27 --print-module-deps \
            --module-path /lds/lib --class-path '/lds/lib/*' /lds/lib/*.jar | tail -n 1)" \
    && echo "jdeps: ${modules}" \
    && jlink --add-modules "${modules},jdk.charsets,jdk.crypto.ec,jdk.localedata" \
            --strip-debug --no-man-pages --no-header-files --output /jre \
    && /jre/bin/java --list-modules

FROM alpine:3.24

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN addgroup -S lds && adduser -S -D -H -G lds -h /lds lds

COPY --from=runtime /jre ${JAVA_HOME}

WORKDIR /lds

COPY target/lib/ lib/
COPY target/linked-data-store-server-*.jar lib/
COPY schemas/ schemas/

USER lds

EXPOSE 9090

HEALTHCHECK --start-period=60s --start-interval=1s --interval=10s --timeout=3s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${LDS_HTTP_PORT:-9090}/ping" || exit 1

# java is the process of the container, so that the signal that stops the container reaches it: LDS then answers
# what it is handling and completes its sagas before it ends.
ENTRYPOINT ["java", "-cp", "/lds/lib/*", "io.descoped.lds.server.Server"]
