# Task 5 gateway/batch independent gate

Date: 2026-08-17 (Asia/Shanghai)
Target SHA: `<final commit SHA recorded after commit>`
Base SHA: `f256a35b5cbebb70d7b242a992d3ca4efedc08a2`
Branch: `codex/w3w5-t05-gateway-batch`
JDK: Oracle GraalVM 25.0.1

## Verdict

**PASS**

All three gateway/native-batch blockers are closed in the owned Task-5 files.
The public command receipt contract, pending HTTP semantics, and bounded native
cancellation paths are covered by the exact JDK25 tests below.

## Review basis and counts

The checked-in plan `.omo/plans/bounded-lifecycle-cancellation-matcher-lanes.md`
defines Task 5 as lifecycle fences, not gateway batches. No dedicated
gateway-batch Task 5 plan was present in the target worktree; this review uses
the supplied gate criteria and `.omo/evidence/task-5-gateway-batch-luna.md` as
the gateway-batch scope.

| Scope | Result |
| --- | --- |
| Trading API contract, JDK25 | **PASS: 1 test, 0 failures/errors** |
| Order provider exact changed tests, JDK25 | **PASS: 39 tests, 0 failures/errors** |
| Market-maker exact changed test, JDK25 | **PASS: 12 tests, 0 failures/errors** |
| Affected upstream reactors (`-am`) | **PASS: all reactor modules built; no failures** |

The exact commands and JDK25 executable are recorded in the verification
section. No Maven process remained after the test artifacts were refreshed.

## Confirmed checks

- The explicit place/amend/cancel batch paths enforce 20/20/50 items and each
  calls one native `PLACE_ORDER_BATCH`, `AMEND_ORDER_BATCH`, or
  `CANCEL_ORDER_BATCH` command (`OrderService.java:110-126,171-179,252-260`;
  `AeronOrderCommandService.java:192-257`).
- Native batch decoding and result construction preserve input indexes/order.
  Stable Task 2 identities are used, including stable outer batch IDs derived
  from product line, user, operation, and `batchKey`
  (`AeronOrderCommandService.java:212-257,424-426`; `StableOrderIdentity.java:23-39`).
- Core performs exact command-ID/fingerprint replay versus changed-payload
  `IDEMPOTENCY_CONFLICT` (`CoreProbeState.java:604-615`). The Task 5 receipt
  test also proves `appliedCommandCount=1` is not used as the export sequence:
  `requiredExportSequence=9` is returned (`OrderBatchServiceTest.java:77-104`).
- Mutation receipt creation uses the typed gateway outcome and has no ordinary
  order-read fallback. Ordinary order reads remain projection-only and enforce
  the configured `ProductLine` (`OrderService.java:301-350,361-386`; the
  command-result endpoint is the reserved control query via
  `OrderAeronGateway.java:41-47`).
- Negative admission mapping is typed: backpressure has no command-result URL,
  and the intended 409/429/503 branches are present. The 410 outside-retention
  command-result branch is also present.
- The checked-in change scope is narrow and does not add wallet/runtime
  artifacts.

## Closed blockers

1. `AeronOrderCommandService.receipt` and `commandResult` preserve
   `MATCHING_PENDING`; `OrderController.commandResponse` maps it to HTTP 202 for
   both initial commands and command-result queries, retaining the command ID
   and result URL. `OrderControllerTest` and `OrderBatchServiceTest` cover this.

2. `OrderRpcApi` exposes `OrderCommandReceipt` for place, place-batch, amend,
   amend-batch, cancel, and cancel-batch. `MarketMakerService` consumes the
   receipt result for quote placement, taker placement, and batch cancellation
   without falling back to per-order RPC calls. `OrderRpcApiContractTest` and
   `MarketMakerServiceTest` cover the contract.

3. `OrderService` routes public cancel-open, admin batch cancellation, and
   lifecycle cancellation through native `CANCEL_ORDER_BATCH` chunks of at most
   50. Requests are grouped only as required by the native single-user command
   contract, then results are restored to original request order with one
   non-atomic item result per input. The 123-item tests observe 50/50/23 native
   chunks and verify no single-order fallback.

## Verification commands

All commands used JDK25 via
`/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home`.

```text
JAVA_HOME=... PATH=... mvn -pl surprising-trading/surprising-trading-api -am -Dtest=OrderRpcApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
PASS: 1 test, 0 failures/errors

JAVA_HOME=... PATH=... mvn -pl surprising-trading/surprising-order-provider -am -Dtest=OrderControllerTest,OrderBatchServiceTest,OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
PASS: 39 tests, 0 failures/errors

JAVA_HOME=... PATH=... mvn -pl surprising-maker -am -Dtest=MarketMakerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
PASS: 12 tests, 0 failures/errors

git diff --check
PASS
```

The `-am` reactors built all affected upstream modules for each public API
surface. No wallet service was started. No `.factorypath`, runtime, `.idea`,
`.local-logs`, or `data` artifact is present in the final owned diff.

## Scope and residual risk

The exact contract/service tests close the three Task-5 blockers: pending HTTP
semantics, the `OrderCommandReceipt` client boundary, and bounded ordered native
batch cancellation. Broader W3 live-runtime reconciliation and end-to-end
environment coverage remain reserved for Task 15; they are not blockers for
this Task-5 gate. Target SHA is intentionally `commit-time pending; stamped by
integration ledger` to avoid self-referential commit hashes.
