# Task 5 semantic integration

Date: 2026-08-17 (Asia/Shanghai)
Worktree: `/Users/atomex/Desktop/surprising/w3-w5-production-closure-worktree`
Branch: `codex/w3-w5-production-closure`

## Exact Git inputs and result

- Base SHA: `e508b8cdd5aa2315eeeadb49a03d3636a6453e6d`
- Source SHA: `c21cf1b2a592ca9fd3887372eb304ef74c3c8f2a`
- Source Task 5 implementation parent: `b76552e7e914194df96e73e3a0c1e6e1d1605c12`
- Cherry-pick result SHA: `17b563507839e19a63bf129992eff45d00832911`

The source commit is based on the Task 5 implementation parent, which is not
an ancestor of the integration branch. The authorized Task 5 provider/API and
test surface was therefore resolved to the final source-tree versions, so the
verified native gateway behavior and the required contract tests were not
dropped while resolving the cherry-pick. The Task 5 source gate report was
retained; the parent-only evidence report was not imported.

## Semantic resolution

- Order placement, amend, and cancel batches use one typed outer command and
  the existing Core native ordered, bounded, non-atomic batch protocol. Limits
  remain 20/20/50, item order and partial success remain Core-authoritative,
  and no provider N-call fallback was restored.
- Command admission and command-result lookup retain typed outcomes, the
  original command identity, required export sequence, and reserved control
  query capacity. Ordinary order reads remain projection-only.
- Protocol v2/default route 1, Core authority, Task 7/8 financial Core code,
  and W1/W2 single-book invariants were not modified by this integration.

## JDK 25 verification

Runtime: IBM Semeru Runtime Open Edition `25.0.2.1` / OpenJ9; Maven `3.9.16`.
All valid commands below used `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home`.
No wallet service or external runtime was started.

| Gate | Command | Result |
| --- | --- | --- |
| API contract | `mvn -pl :surprising-trading-api -am -Dtest=OrderRpcApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 1 passed, 0 failures/errors/skips |
| Order provider | `mvn -pl :surprising-order-provider -am -Dtest=OrderControllerTest,OrderBatchServiceTest,OrderServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 39 passed: 8 + 4 + 27, 0 failures/errors/skips |
| Market maker | `mvn -pl :surprising-maker -am -Dtest=MarketMakerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 12 passed, 0 failures/errors/skips |
| Core batch/contract fence | `mvn -pl :surprising-aeron-service -am -Dtest=CoreOrderedOrderBatchTest,DeterministicExchangeCoreAdapterTest,TradingOrderBatchCodecTest,SurprisingClusteredServiceTest,W1W2InvariantFenceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 16 passed: 6 + 5 + 4 + 4 + 1, 0 failures/errors/skips |

A preliminary invocation was rejected by the Maven JDK-25 enforcer before
tests because Maven inherited JDK 21; it ran no tests. The gates above were
rerun with the explicit JDK 25 `JAVA_HOME` and passed.

## Hygiene

- `git diff --check e508b8cdd5aa2315eeeadb49a03d3636a6453e6d HEAD`: pass.
- `git ls-files -u`: empty; tracked source contains no conflict markers.
- The integration diff contains no `surprising-aeron-core` paths outside the
  existing base, and no paths matching `.factorypath`, `wallet`, or `runtime`.
- `find . -name .factorypath -print`: empty.
- The 23 pre-existing untracked `.omo/evidence/` and `.omo/plans/` artifacts
  were preserved and not staged by the integration commit.
