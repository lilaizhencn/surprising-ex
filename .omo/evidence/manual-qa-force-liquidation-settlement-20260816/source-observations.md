# Source observations supporting the runtime QA boundary

This file records source-level observations used to select tests. They are not substitutes for live-cluster execution.

## Force liquidation and provider work

- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java:31-45` calls `aeron.work(properties.getCoordinator().getWorkBatchSize())`, iterates only the returned `work.actions()`, executes each action sequentially, and optionally calls `continueRiskScan` once. `LiquidationProperties.Coordinator` caps `workBatchSize` at 1,000 and `riskScanBatchSize` at 4,096.
- `LiquidationServiceTest` exercises one returned action, disabled execution, and projection mapping. It does not create a real Aeron connection or load a large batch.
- `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java` includes `liquidationWorkQueryReturnsOnlyCurrentBoundedPlansAndScanReadiness`; current targeted execution passed it as part of 23 CoreProbeState tests.

## Settlement progress

- `surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/ExpiringContractSettlementFanoutService.java:47-62` uses `for (;;)`, submits `SETTLE_INSTRUMENT`, decodes progress, and exits on complete. It rejects non-advancing cursors but has no explicit maximum number of cursor iterations in the provider method.
- `ExpiringContractSettlementFanoutServiceTest.resumesSettlementFromCoreCursor` proves one persisted cursor is carried into the next command and that a completed response exits. It does not prove behavior for a very large user population, a permanently advancing stream, or slow Core.
- `CoreLifecycleStateTest` covers delivery/option settlement, fund conservation, and settlement cursor persistence/exactly-once behavior in the in-memory Core reducer. It does not exercise Kafka or a live account provider.

## Aeron call shape

- `surprising-liquidation/src/main/java/com/surprising/liquidation/provider/service/LiquidationAeronGateway.java:32-62` calls `AeronClientPool.query` for work and `AeronClientPool.command` for risk-scan continuation and each liquidation action.
- `surprising-account/.../ExpiringContractSettlementFanoutService.java:51-55` calls `AccountAeronGateway.command` once per settlement cursor page.
- `surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:147-159,225-236,504-...` exposes synchronous `command`/`query` methods that call `submit`; `SurprisingAeronClient.submit` offers to the cluster and polls until a correlated response or timeout. `commandAsync` and `tryCommandOnce` are separate non-blocking/async alternatives and are not used by the liquidation or settlement gateway methods inspected.
- A JShell probe invoking `AeronClientPool.query` against unresolved `node0,node1,node2` returned `io.aeron.exceptions.RegistrationException` after `elapsedMillis=662`; this is real client-side blocking/error evidence, but no Core request/response round trip occurred because the cluster could not connect.

## Audit conclusion from source plus runtime

No reproducible runtime evidence of batch blocking, unbounded settlement work, or a completed synchronous Aeron round trip was obtained locally. The first two remain coverage gaps requiring a live Core/account/provider setup and controlled large-batch or slow-cluster load. Synchronous call structure is confirmed by source, and a pre-send synchronous failure is reproduced by the JShell probe; wire-level request/response latency is not proven.
