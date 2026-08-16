# Runtime availability and partial-runtime evidence

## Aeron cluster probe

Surface: real Aeron client through existing tool wrapper.

Exact invocation: `PRODUCT_LINE=SPOT PROBE_MODE=query PROBE_SOURCE_ID=920001 scripts/aeron-core-tool.sh probe`

Observed: exit 1. The tool created a transient `surprising-aeron-spot_cluster` network and probe container, then the container exited. The client failed before a Core request/response round trip:

```text
io.aeron.exceptions.TimeoutException: ERROR - cluster connect timeout: state=AWAIT_PUBLICATION_CONNECTED messageTimeout=10s ingressChannel=aeron:udp ingressEndpoints=0=node0:20002,1=node1:20102,2=node2:20202 ...
Suppressed: io.aeron.exceptions.RegistrationException: ERROR - java.net.UnknownHostException: unresolved - endpoint=node2:20202
Suppressed: io.aeron.exceptions.RegistrationException: ERROR - java.net.UnknownHostException: unresolved - endpoint=node1:20102
Suppressed: io.aeron.exceptions.RegistrationException: ERROR - java.net.UnknownHostException: unresolved - endpoint=node0:20002
```

Post-probe cleanup: `docker ps -a --filter name=surprising-aeron-spot-probe-run` returned no container; `docker network rm surprising-aeron-spot_cluster` exited 0; `docker compose ls --all` returned no compose project.

## Provider HTTP availability

Exact invocations, each using the faithful HTTP channel: `curl -i --max-time 1 http://127.0.0.1:<port>/actuator/health` for ports 9088 (liquidation), 9086 (account), 9091 (ADL), 9087 (risk), and 9094 (Gateway).

Observed for all five: `curl: (7) Failed to connect to 127.0.0.1 port <port> ... Couldn't connect to server`.

No provider, Gateway, matching, maker, or wallet process was started by this audit. Local Kafka and PostgreSQL listeners existed, but their presence does not establish a running application chain or Aeron Core cluster.

## Synchronous client-side failure probe

Exact invocation: `env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin jshell -J--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --execution local --class-path "$PWD/surprising-aeron-core/surprising-aeron-client/target/classes:$PWD/surprising-aeron-core/surprising-aeron-protocol/target/classes:$PWD/surprising-product-api/target/classes:/Users/atomex/.m2/repository/io/aeron/aeron-all/1.52.2/aeron-all-1.52.2.jar:/Users/atomex/.m2/repository/org/agrona/agrona/1.15.1/agrona-1.15.1.jar:/Users/atomex/.m2/repository/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"` with this input:

```java
try (var pool = new AeronClientPool("sync-probe", ProductLine.SPOT,
        List.of("node0", "node1", "node2"), "probe", Duration.ofMillis(250), 1)) {
    pool.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), 1L, new byte[0]);
}
```

Observed: `queryFailure=io.aeron.exceptions.RegistrationException elapsedMillis=662 message=ERROR - java.net.UnknownHostException: unresolved - endpoint=probe:0`.

Interpretation: the synchronous `query` call did not return immediately and surfaced a client-side connection failure. It did not reach a Core request/response exchange, so this is not evidence of production round-trip latency.

## Historical artifacts verified, not promoted

The prior `.omo/evidence/fullchain-runtime-qa-20260815` artifacts were read and checked. They document 102 targeted tests and historical six-product-line capacity/recovery artifacts, while explicitly recording that no current Core/provider chain was running. Those historical results support only the claims stated there and are not current live-cluster evidence.
