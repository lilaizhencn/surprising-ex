# Aeron Client and Realtime Transport

`SurprisingAeronClient` and `AeronClientPool` own application command sessions, source identities, correlation, and backpressure. Embedded command MediaDrivers default to `SHARED`. JVM property `surprising.aeron.client.threading-mode` overrides `AERON_CLIENT_THREADING_MODE`; `DEDICATED` requests separate driver threads. Invalid modes fail startup. This does not change Core/Archive threading.

Gateway creates one command MediaDriver per product business container. Account, order, trigger, and maintenance pools borrow it while retaining separate identities, sessions, mailboxes, and budgets. Pools close their sessions; the product container closes the shared driver after all pools. Standalone clients own drivers they create. Realtime transport uses a separate application driver.

## Request lifecycle

`Session.drainResponses` correlates responses and completes in-flight requests. `EgressDispatcher` serializes offer, poll, heartbeat, and shutdown on its I/O thread. Standalone clients serialize Cluster access under their monitor.

`AeronClientPool.Request` uses generation and atomic state to coordinate send, cancellation, and completion. Stale generations cannot cancel reused slots. Completion clears references before returning a slot. `ADMIN_ACTION` preserves sending state and the original source sequence. Unknown funds outcomes must not be blindly resubmitted.

`AsyncConnection` has separate connection and business-response deadlines. Configure connection time using `surprising.aeron.client.connect-timeout-ms`; repeated reconnection is not a substitute for handling a business timeout.

## Realtime boundaries

`AeronRealtimeSender` owns its Publication. `AeronRealtimeReceiver` reassembles fragments and copies callback data. Business threads write `RealtimeOutbox` rather than calling external transports directly.

The outbox is single-producer/single-consumer and bounded by slots and bytes. `begin/stage/commit` publishes a complete callback; capacity failure drops the entire callback and `abort` clears staged data. This is lossy output, not a durable event source. Registering a waiting consumer is followed by a queue recheck to avoid missed wakeups.

Realtime frames carry product, user, cluster position, ordinal, event time, snapshot identity, and entity identity. Validate contiguous ordinals and complete COMMIT/SNAPSHOT boundaries. Independent price-source sequences are not comparable to Core log positions. Current format limits are defined by the protocol source.

Receiver transport failures close the old subscription before reconnecting. Shutdown wakes or interrupts waiting components and releases only owned resources.

## Verification

```bash
mvn -pl surprising-aeron-core/surprising-aeron-client -am test
```

Test correlation, generations, leases, budgets, timeout handling, and real MediaDriver transport independently from financial/Cluster qualification.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
