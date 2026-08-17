# Task 8 semantic integration

Date: 2026-08-17 (Asia/Shanghai)

## Identity and ordered integration

- Worktree: `/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree`
- Branch: `codex/w3-w5-production-closure`
- Base HEAD: `f256a35b5cbebb70d7b242a992d3ca4efedc08a2`
- Task8 source commit 1: `0a15ff8478a9307e876da8a316ea0d070e941b9f`
- Task8 source commit 2: `8b9ac9c6a32ccc9c4c81941c657fff1a64037837`
- Integrated commit 1: `c39384721a5f7948a6bbcdcd5790df1fc946ea84`
- Integrated commit 2: `8b1a4ba766325812d6820c6513bd66c53cbaf855`

The two source commits were cherry-picked in the requested order. Stable patch
IDs match source to integration for both commits (`50d619f6f8e743eb656c586fbb3789a730e2e8d1`
and `6749558918a279c871bb36b9f427e240fbd06e49`). The Task3 native ordered-batch
files remain unchanged from the base, and the merged settlement path retains
Task7's margin-mode-aware `applyLiquidationCash` behavior for isolated
liquidation collateral.

## JDK25 verification

Runtime: IBM Semeru Runtime Open Edition 25.0.2.1 / OpenJ9; Maven 3.9.16.
No wallet service was started.

| Gate | Command/result |
| --- | --- |
| Exact Task8 set | `mvn -pl :surprising-aeron-service -am -Dtest=CoreDeliveryOptionFinancialMatrixTest,CoreContractMathTest,CoreLifecycleStateTest -Dsurefire.failIfNoSpecifiedTests=false test`; 25 passed: matrix 10, lifecycle 14, contract math 1. |
| New oracle/leakage/snapshot/no-funding tests | Four individual JDK25 invocations, 1 passed each: `inverseDeliveryRoundingOracleUsesIndependentSignedHalfUpFormula`, `settlingIsolatedLossKeepsUnrelatedCrossStateAndReservationIntact`, `snapshotCursorResumeCompletesLinearAndInverseIsolatedDelivery`, `rejectsFundingForDeliveryAndOptionWithoutStateMutation`. |
| Task7 matrix | `mvn -pl :surprising-aeron-service -am -Dtest=CorePerpetualFinancialMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test`; 3 passed. |
| Task3 blocker/batch and W1/W2 fence | `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest,DeterministicExchangeCoreAdapterTest,TradingOrderBatchCodecTest,SurprisingClusteredServiceTest,W1W2InvariantFenceTest -Dsurefire.failIfNoSpecifiedTests=false test`; 20 passed: 6 + 5 + 4 + 4 + 1. |
| Full affected reactor | `mvn -pl :surprising-aeron-service,:surprising-order-provider -am clean test`; 13 reactor projects, 405 tests, 0 failures, 0 errors, 0 skipped. Test-bearing counts: product-api 18, aeron-protocol 53, instrument-api 11, aeron-service 159, aeron-client 20, trading-api 13, price-consumer 8, order-provider 123. |

Core lifecycle is included in the exact Task8 set. Task8's 10-row delivery/
option matrix and Task7's 72-row perpetual matrix both assert expected and
observed `FUNDS_DIFFERENCE=0`. The Task3 gate includes native ordered batches,
matcher adapter state-change handling, and `W1W2InvariantFenceTest`; the clean
reactor also passed matcher lane/state coverage.

## Integrity and scope checks

- `git diff --check`: passed.
- No unmerged index entries or conflict markers.
- No changed wallet or `.factorypath` paths; `find` found no `.factorypath`.
- No Maven, Surefire, or wallet process remained after verification.
- Existing untracked `.omo/evidence/` and `.omo/plans/` artifacts were preserved.
- No plan, ledger, runtime, wallet, or `.factorypath` file was edited.

The final branch SHA is recorded by the push verification accompanying this
evidence commit.
