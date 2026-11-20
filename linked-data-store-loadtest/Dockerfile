# The controller of the load test: the tools the scripts run inside the network of the test (curl, jq, zip,
# gnuplot) and the program that turns the results into plot files. It is made of what Maven has built, so build
# the reactor first (./mvnw package -DskipTests); the scripts build this image themselves.
#
# The JDK is the baseline of LDS and is raised by hand, together with java.version in the root POM.
FROM eclipse-temurin:27-jre-alpine

RUN apk --no-cache add bash curl gnuplot jq perl zip

COPY target/lib/ /opt/plotgen/lib/
COPY target/linked-data-store-loadtest-*.jar /opt/plotgen/lib/
COPY prepopulate.sh /prepopulate.sh

WORKDIR /loadtest

# It only waits: the scripts run their commands in it.
CMD ["sleep", "infinity"]
