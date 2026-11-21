# Diagnostics with the debug image

For a hang, high CPU, growing memory or a slow request. The tools of the JDK are only in the debug image,
`descoped/lds:3.0.0-debug`: the regular image has no `jcmd` (`exec: "jcmd": executable file not found`), and the
distroless image has nothing but `java`. The debug image is the regular one plus these tools, with the same LDS,
user, port, health check and configuration.

## Run LDS on the debug image

Start the same LDS with the image changed to `-debug`: the same variables, mounts, network and published port.
Stop the running one in order first (`docker stop -t 60 <container>`); two LDS on one saga log need different
instance ids (see [writes-and-recovery.md](writes-and-recovery.md#several-lds-on-one-saga-log)).

```sh
docker run -d --name lds-debug -p 127.0.0.1:9090:9090 descoped/lds:3.0.0-debug
```

With the compose file, add the variant in an override file next to it and start the service again:

```yaml
services:
  lds:
    build: !reset null
    image: descoped/lds:3.0.0-debug
```

```sh
docker compose -f compose.yaml -f debug.yaml up -d lds
```

A problem that has to be caught as it happens needs LDS to be on the debug image before it happens. When the
problem is understood, go back to the regular or the distroless image.

## The tools

They run in the container as the user `lds` and attach to LDS, which is process 1:

```sh
docker exec lds-debug jcmd 1 VM.version
docker exec lds-debug jstack 1 > threads-1.txt
docker exec lds-debug jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp lds-debug:/tmp/heap.hprof .
```

```
1:
OpenJDK 64-Bit Server VM version 27.0.1.0.2+1
JDK 27.0.1

1:
Dumping heap to /tmp/heap.hprof ...
Heap dump file created [18737384 bytes in 0.029 secs]
```

- **A hang or a slow request**: take `jstack 1` three times, a few seconds apart, and compare: threads that stay in
  the same place are the ones to read. An LDS as it comes had 73 threads. `XNIO-1 task-...` threads handle requests,
  `sec-...` threads run sagas, `saga-recovery-...` threads look for unfinished writes; the log names the thread of
  each line in `[...]`. `jcmd 1 Thread.print` gives the same dump.
- **Memory**: `jstat -gcutil 1` shows how full each part of the heap is and how much time went to garbage
  collection; take it a few times. A heap dump shows what holds the memory: open the `.hprof` in a heap analyser
  (Eclipse MAT, VisualVM, IntelliJ). The dump of an idle LDS was 18 MB; the file is written in the container's
  `/tmp`, so it needs room there, and it holds the documents that were in memory: treat it as the data itself.
- **A recording over time** with JDK Flight Recorder:

```sh
docker exec lds-debug jcmd 1 JFR.start duration=60s filename=/tmp/lds.jfr
docker exec lds-debug jcmd 1 JFR.check
docker exec lds-debug jcmd 1 JFR.dump name=1 filename=/tmp/lds.jfr
docker cp lds-debug:/tmp/lds.jfr .
docker exec lds-debug jfr summary /tmp/lds.jfr
```

  `JFR.dump` writes what is recorded so far; at the end of `duration` the file is written by itself. Open it in JDK
  Mission Control.

The busybox tools of the regular image are there too (`ps`, `top`): `docker exec lds-debug ps` shows
`java -cp /lds/lib/* io.descoped.lds.server.Server` as process 1.

## Remote JMX and a debugger

Give the options in `JDK_JAVA_OPTIONS`, which only the `java` that starts LDS reads, not in `JAVA_TOOL_OPTIONS`,
which every JDK tool reads as well (a tool would then try to open the same port and fail). This run opened JMX on
9010 and a debugger on 5005, both on 127.0.0.1 of the machine; the container stayed healthy and `jcmd` still
worked:

```sh
docker run --rm -p 127.0.0.1:9090:9090 -p 127.0.0.1:9010:9010 -p 127.0.0.1:5005:5005 \
  -e JDK_JAVA_OPTIONS="-Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010 \
    -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false \
    -Djava.rmi.server.hostname=127.0.0.1 -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" \
  descoped/lds:3.0.0-debug
```

The log then starts with `NOTE: Picked up JDK_JAVA_OPTIONS: ...` and `Listening for transport dt_socket at address:
5005`. Connect JConsole or VisualVM to `127.0.0.1:9010`, and a Java debugger to `127.0.0.1:5005`.

JMX without a password and a debugger give whoever reaches the port the whole process and its data. Publish them on
127.0.0.1 only, as here; on a remote machine reach them through an SSH tunnel, never an open port. Remove them when
the investigation is done. Do not put them in the distroless image: its health check is a `java` of its own that
reads the same options, tries the same port, fails, and the container is called unhealthy.

## Another log level

No key sets the level of the log; it is `INFO`. Give LDS a logback file of your own with the system property
`logback.configurationFile`, in `JAVA_TOOL_OPTIONS`. This one has the level `WARN`:

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="WARN">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

```sh
docker run -d --name lds -p 127.0.0.1:9090:9090 -v "$PWD/logconf:/lds/logconf:ro" \
  -e JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=/lds/logconf/logback.xml descoped/lds:3.0.0
```

A start and a stop then logged `Picked up JAVA_TOOL_OPTIONS: ...` and one line, `ShutdownHook triggered..`. With
`WARN` there is no `Server started` line, which the health report looks for, so use it knowingly.

Two keys make LDS log much more for an investigation: `http.request.dump=true` logs every request with its
headers (an `Authorization` header too), and `neo4j.cypher.show=true` logs every statement sent to Neo4j with the
values of the documents in it. Both put data and secrets in the log: switch them off afterwards.

[Running LDS: Diagnostics with the debug
image](https://lds.descoped.io/docs/user/operations/#diagnostics-with-the-debug-image).
