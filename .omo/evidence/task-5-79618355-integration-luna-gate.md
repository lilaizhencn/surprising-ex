# Task 5 independent integration Luna gate

Date: 2026-08-17 (Asia/Shanghai)
Worktree: `/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree`
Target: `79618355b1eda43edb281456559ca7ceb428dc26`
Base reviewed: `e508b8cdd5aa2315eeeadb49a03d3636a6453e6d`

## Verdict

**NEEDS_FIX**

The exact SHA and all requested focused suites pass, but the shared `OrderCommandReceipt` JSON contract is not runtime-type-safe for the maker's live RPC path. `OrderCommandReceipt.result` is declared as `Object` (`surprising-trading-api/src/main/java/com/surprising/trading/api/order/OrderCommandReceipt.java:7-25`), while maker result handling requires the concrete runtime type (`surprising-maker/src/main/java/com/surprising/marketmaker/provider/service/MarketMakerService.java:1118-1120`).

Independent IBM Semeru JDK25 Jackson 3.0.4 round-trip evidence:

```text
decoded.result().getClass()                         -> java.util.LinkedHashMap
OrderBatchResponse.class.isInstance(decoded.result()) -> false
```

The test fake returns a typed Java object directly (`MarketMakerServiceTest.java:632-635`), so the passing maker suite does not exercise this wire boundary. In production this makes batch placement (`MarketMakerService.java:752-755`), single placement (`:892-897`), and batch cancellation (`:1090-1115`) treat successful terminal JSON responses as missing/wrongly typed results. `AeronOrderCommandService.commandResult` also stores terminal poll data as raw response bytes (`surprising-order-provider/src/main/java/com/surprising/order/provider/service/AeronOrderCommandService.java:302-305`), which is inconsistent with the typed initial receipt.

No product code was changed, committed, or pushed during this gate.

## Exact SHA and worktree gate

- `git rev-parse HEAD` = `79618355b1eda43edb281456559ca7ceb428dc26`.
- `git rev-parse refs/remotes/origin/codex/w3-w5-production-closure` = the same SHA.
- `git ls-remote --heads origin codex/w3-w5-production-closure` = `79618355b1eda43edb281456559ca7ceb428dc26`.
- `git diff-index --quiet HEAD --` and `git diff --cached --quiet` both exited `0`.
- Before this report was created, the worktree had 23 untracked `.omo` artifacts and no tracked changes. They were preserved.
- `git ls-files -u` returned no entries; an exact-line conflict-marker scan returned no entries.
- `git diff --check e508b8c..79618355` and current `git diff --check` both exited `0`.

The target history is linear for this integration: `79618355` has parent `17b563507839e19a63bf129992eff45d00832911`, whose parent is `e508b8c`. `git show --cc --stat 79618355` contains only the Task 5 evidence document. The Task 5 implementation/API/test path set compared from the source integration point `c21cf1b2a592ca9fd3887372eb304ef74c3c8f2a` to `17b563507839e19a63bf129992eff45d00832911` returned exit `0`, so the implementation was not dropped during integration.

## Requested Semeru JDK25 tests

All commands below used IBM Semeru:
`/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home` (`openjdk 25.0.2`, IBM Semeru/OpenJ9).

| Scope | Command/test selection | Result |
|---|---|---|
| API contract | `mvn -pl :surprising-trading-api -am -Dtest=OrderRpcApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 1 run, 0 failures/errors/skips; BUILD SUCCESS |
| Order provider | `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest,OrderBatchServiceTest,OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 39 run, 0 failures/errors/skips; BUILD SUCCESS |
| Maker | `mvn -pl :surprising-maker -am -Dtest=MarketMakerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 12 run, 0 failures/errors/skips; BUILD SUCCESS |
| Core batch/W1W2 equivalents | `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest,DeterministicExchangeCoreAdapterTest,TradingOrderBatchCodecTest,SurprisingClusteredServiceTest,W1W2InvariantFenceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 16 run, 0 failures/errors/skips; BUILD SUCCESS |

A preliminary invocation inherited JDK21 and was rejected by the project JDK25 enforcer before tests; it was not counted as a gate result. The commands above were rerun with IBM Semeru JDK25.

## Integration and semantic audit

- `e508b8c..79618355` is 17 files, 1,300 insertions, 219 deletions. It contains the order-provider/API/maker receipt and native batch changes plus evidence; it does not change `surprising-aeron-core` implementation code.
- Pending/202 behavior is wired through `OrderCommandReceipt`, `OrderRpcApi`, `OrderController`, and `AeronOrderCommandService`; controller mapping sends `MATCHING_PENDING` and `RESULT_UNKNOWN` to 202, preserves the command-result URL, and maps outside-retention to 410 (`OrderController.java:290-300`). Focused controller/service tests cover the pending identity and URL behavior.
- Native cancellation is bounded and non-atomic: `CancelOrderBatchCommand` enforces wire version 1 and max 50; `CoreProbeState` advances per item after rejection and emits per-item results. `OrderService.cancelOrderChunks` groups by user in insertion order, chunks at 50, maps results back to original indexes, and sorts the final result by original index (`OrderService.java:613-653`). The provider tests cover 123-item 50/50/23 chunking and verify no single-order fallback. The explicit single admin cancel endpoint remains a separate single-command operation, not a batch fallback.
- No per-order fallback exists in the maker batch paths: missing/unknown batch results are retained as failed/uncertain, as shown at `MarketMakerService.java:1090-1115`.
- Protocol audit: `CoreProtocol.SCHEMA_VERSION = 2`; `CoreRoute.DEFAULT` is route/shard 0 with protocol version 1; command/query headers use that default; place/amend/cancel batch wire version is 1; batch limits are 20/20/50.
- W1/W2 uses one executable exchange-core path. `W1W2InvariantFenceTest` passed and asserts snapshot-only restore with index rebuilding, not order replay/rebuilt matcher state. The pinned exchange-core artifact is SHA `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`, SHA-256 `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`, with clean fork provenance; the core Maven verification passed.
- Ordinary and history reads in `OrderService` use the projection repository (`OrderService.java:296-385`); cancellation-open selection also starts from projected open orders. No new read-side Aeron fallback was found.
- The reviewed integration diff has no wallet/runtime/factorypath/.idea/.local-logs/data artifacts, and no wallet service was started. No direct financial-model regression was found in this diff. The gate nevertheless remains **NEEDS_FIX** because the shared receipt wire contract breaks live maker order success/cancellation handling.

## Required disposition

Fix and add a real JSON/Feign contract test for polymorphic `OrderCommandReceipt.result` (including `OrderResponse` and `OrderBatchResponse`) before accepting this SHA as an integration PASS. Do not use per-order fallback to mask a failed batch contract.

## Task 5 blocker closure

## Final verdict

**PASS after closure** — the exact JSON polymorphism blocker is green, and the implementation plus semantic evidence are committed and pushed.

Date: 2026-08-17 (Asia/Shanghai)
Validation worktree: `/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree`

The blocker is fixed in the post-base worktree with a stable explicit wire contract:

- `surprising-trading/surprising-trading-api/src/main/java/com/surprising/trading/api/model/OrderCommandResult.java` defines the only four allowed receipt result variants and stable `resultType` names: `order`, `order-batch`, `amend`, and `amend-batch`.
- `OrderCommandReceipt.result` is `OrderCommandResult`, not `Object`; `OrderResponse`, `OrderBatchResponse`, `AmendOrderResponse`, and `AmendOrderBatchResponse` implement that sealed interface.
- `MarketMakerService.receiptResult` consumes the typed result directly. The maker fixture can enable a real Jackson 3 JSON write/read round-trip before returning receipts; the cancellation/reposting test uses this mode, so batch placement and batch cancellation are consumed after deserialization rather than from a direct Java object.
- The concurrent order-provider adapter alignment preserves the typed initial command result and no longer places raw `byte[]` data into the polymorphic receipt result. Command status, code, URL, and known export sequence fields remain unchanged.

### Required red-to-green contract evidence

Failing-first command under IBM Semeru OpenJ9 JDK25:

```text
env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:$PATH mvn -pl :surprising-trading-api -am -Dtest=OrderCommandReceiptJsonContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Before the model change, the six parameterized mutation cases failed with `Tests run: 6, Failures: 6`; the decoded `OrderResponse` result was `java.util.LinkedHashMap`.

After the model change, `OrderCommandReceiptJsonContractTest` passed 6/6. It covers `place`, `place-batch`, `amend`, `amend-batch`, `cancel`, and `cancel-batch`, asserts the explicit `resultType`, concrete result class/equality, and exact `TERMINAL`/`NONE`/URL/prospective IDs/export sequence fields.

### Semeru JDK25 verification

IBM Semeru: `/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home`, OpenJ9 `25.0.2`.

| Scope | Command | Result |
|---|---|---|
| API focused | `mvn -pl :surprising-trading-api -am -Dtest=OrderRpcApiContractTest,OrderCommandReceiptJsonContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 7 run, 0 failures/errors/skips; BUILD SUCCESS |
| API full reactor | `mvn -pl :surprising-trading-api -am test` | API module 20 run, 0 failures/errors/skips; BUILD SUCCESS |
| Order provider focused | `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest,OrderBatchServiceTest,OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 39 run, 0 failures/errors/skips; BUILD SUCCESS |
| Order provider full reactor | `mvn -pl :surprising-order-provider -am test` | Provider module 136 run, 0 failures/errors/skips; BUILD SUCCESS |
| Maker focused | `mvn -pl :surprising-maker -am -Dtest=MarketMakerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 12 run, 0 failures/errors/skips; BUILD SUCCESS |
| Maker full reactor | `mvn -pl :surprising-maker -am test` | Maker module 34 run, 0 failures/errors/skips; BUILD SUCCESS |

The provider reactor included the existing Aeron protocol contract tests (53 protocol tests passed). No Aeron protocol types, runtime/Task16/17 files, wallet service, or per-order fallback were changed. `git diff --check` passed for the fix worktree.

### Completed handoff

- Implementation commit: `6067928e0d9cd8160b42920a24ff9e04d24335ff` (`fix(order): type mutation receipt wire results`).
- Semantic evidence file is committed on the branch after the implementation commit.
- Pushed branch: `codex/w3-w5-production-closure`.
- Final remote verification matched the local `HEAD`.
