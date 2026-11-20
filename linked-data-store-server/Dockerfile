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
# The JDK is the baseline of LDS and is raised by hand, together with java.version in the root POM.
FROM eclipse-temurin:27-jre-alpine

RUN addgroup -S lds && adduser -S -D -H -G lds -h /lds lds

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
