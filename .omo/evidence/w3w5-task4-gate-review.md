# Task 4 Gate Review

## recommendation

REJECT (`NEEDS_FIX`) for commit `39510455f85fbc7bb13b80ecc4fe429c7f14c19d`.

## originalIntent

Replace request-scoped Aeron execution, spin/park, publication retry, and per-call egress polling with fixed bounded owner agents and isolated reserved control capacity, while preserving immediate typed admission semantics and exact fixed capacities.

## desiredOutcome

Four command owner sessions plus one reserved control owner, bounded independent mailboxes and in-flight limits (256/64 and 64/32), an effective egress fragment limit of 32, startup validation, one publication offer per request, typed negative admission without retry, admitted timeout as `ResultUnknown` carrying the original command ID, local rejection of ordinary reads, and no dynamic growth or hidden resubmission.

## blockers

1. **violatedCriterion: T4-WHAT-TO-DO / negative Publication codes map immediately to typed NotAccepted with zero retry**
   - `tryCommandOnce` returns `SENT` whenever the owner agent has not yet dequeued and offered the request. A disconnected publication therefore reports `SENT`, not `NOT_READY`/typed `NOT_CONNECTED`; the fresh affected-module test reproduces this (`expected NOT_READY but was SENT`). This means the public immediate admission API acknowledges admission before the single Aeron offer has happened.
   - **evidencePointer:** `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:184`; `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/surprising-aeron-core/surprising-aeron-client/src/test/java/com/surprising/aeron/client/AeronClientPoolTest.java:77`; fresh command `mvn -pl :surprising-aeron-client -am clean test`, failure `AeronClientPoolTest.tryCommandOnceDoesNotWaitForClusterConnection`.

2. **violatedCriterion: T4-WHAT-TO-DO / exact egress fragment limit 32 and one egress dispatcher replacing per-call polling**
   - Each of the five lane threads calls `pollSession`; the shared `EgressDispatcher` is only a stateless helper, not one dispatcher owner. More importantly, `SurprisingAeronClient.pollEgress(int fragmentLimit)` validates the argument but discards it and calls parameterless `cluster.pollEgress()`. Thus the configured/default value 32 is not applied to production egress polling, and the test only pins the record value.
   - **evidencePointer:** `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:401`; `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java:496`; `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java:182`.

3. **violatedCriterion: T4-AC1 / green behavior must replace baseline executor paths without breaking affected client behavior**
   - A fresh JDK 25 affected-module run fails 4 of 16 client tests: two stale executor-reflection tests, the immediate `tryCommandOnce` semantic regression above, and closed-query behavior. Agent shutdown during connection startup also emits uncaught `ClosedByInterruptException` and reports agents that do not terminate. The narrow named 8-test gate passes but does not establish the affected module is green.
   - **evidencePointer:** fresh command `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn -pl :surprising-aeron-client -am clean test`; Surefire report `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/surprising-aeron-core/surprising-aeron-client/target/surefire-reports/TEST-com.surprising.aeron.client.AeronClientPoolTest.xml`.

## userOutcomeReview

Partially delivered: exact capacity values and positive validation exist; command/control queues are separate and bounded; positive offer followed by timeout produces `ResultUnknown(originalCommandId)` in the fake-session path; negative offer mapping preserves all Aeron codes and raw unknown values; ordinary reads are rejected locally; source scans show no request-level `supplyAsync`, `ThreadPoolExecutor`, `LockSupport`, offer retry loop, dynamic collection growth, or hidden resubmit in the changed production scope.

The shipped artifact does not fully satisfy the user-visible outcome because immediate admission can claim `SENT` before Aeron admission, and production does not enforce the requested egress fragment limit or a single dispatcher ownership model.

## checkedArtifacts

- Plan: `/Users/atomex/Desktop/surprising/surprising-ex/.omo/plans/w3-w5-production-closure.md` Task 4.
- Exact repository: `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents`.
- Baseline/diff: `7e78e04ae4dac16d364117392f960a65a4f4db2d..39510455f85fbc7bb13b80ecc4fe429c7f14c19d` (9 declared Aeron-client files only).
- Remote: `refs/heads/codex/w3w5-t04-aeron-agents` resolves exactly to `39510455f85fbc7bb13b80ecc4fe429c7f14c19d`.
- Executor evidence directory: `/Users/atomex/Desktop/surprising/w3w5-t04-aeron-agents/.omo/evidence/task-4-aeron-agents/`.
- Production sources and all five client test classes, including pre-existing `AeronClientPoolTest`.
- Narrow JDK 25 gate reproduced: 8 tests pass.
- Fresh JDK 25 affected reactor reproduced: 16 client tests, 2 failures and 2 errors.
- Scope guards: no protected WebSocket path, `.factorypath`, wallet, docs/scripts, or unrelated tracked file in the commit; `git diff --check` passes.

## remove-ai-slopsAndProgrammingPass

- Direct pass found false confidence from a narrow selected suite: it excludes the existing pool tests and pins the fragment-limit configuration value without proving production applies it.
- `AeronClientPool.java` is a 733-line changed class with multiple responsibilities. This is maintenance debt/NOTE, not an independent blocker because Task 4 does not state a file-size criterion.
- No deletion-only/removal-text tests were added. The new timeout and typed mapping tests assert observable outcomes; the paused-agent mailbox test is implementation-coupled but relevant to the explicit capacity criterion.
- No unnecessary parsing/normalization or speculative production abstraction was found beyond the ineffective dispatcher helper noted in blocker 2.

## exactEvidenceGaps

- No separate code-review report explicitly covering programming plus remove-ai-slops/overfit criteria was supplied.
- No manual QA matrix or notepad path was supplied.
- No real Aeron cluster behavior artifact proves the effective fragment limit or single dispatcher ownership.
- No adversarial execution proves zero offer retries for every negative Publication code; only `ADMIN_ACTION` executes the offer-count assertion, while the rest only test pure mapping.
- These report gaps did not replace the direct artifact pass; blockers above are based on reproduced production/test evidence.

