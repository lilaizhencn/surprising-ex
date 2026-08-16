# Task 16 static W4 evidence

Date: 2026-08-17
Worktree: `/Users/atomex/Desktop/surprising/w3w5-t16-real-w4`
Branch: `codex/w3w5-t16-real-w4`
Reviewed baseline: `36febc5e495f6fdedbea1660926da759474a6511`
Plan source: `/Users/atomex/Desktop/surprising/surprising-ex/.omo/plans/w3-w5-production-closure.md`, Task 16

## Failing-first evidence

The new assertions were run against the baseline before implementation:

```text
bash -x surprising-aeron-core/runtime/w3-w5/tests/w4-static.sh
status=1
failure: expected W4_STATIC_PLAN=STATIC_PREP; baseline emitted the unqualified W4_SIX_LINE=PASS path

mvn -pl :surprising-aeron-tools -am -Dtest=W4LifecycleQaMainTest -Dsurefire.failIfNoSpecifiedTests=false test
BUILD FAILURE: 15 test-compilation errors for the missing exact-row, pending-capability,
sequence-assignment, and manifest-reconciliation guards
```

The independent pre-fix gate report remains preserved unchanged at
`.omo/evidence/task-16-36febc5-luna-gate.md`.

## Static checks after the fix

```text
bash -n surprising-aeron-core/runtime/w3-w5/run.sh surprising-aeron-core/runtime/w3-w5/scenarios/common.sh surprising-aeron-core/runtime/w3-w5/scenarios/w4-six-line.sh surprising-aeron-core/runtime/w3-w5/tests/w4-static.sh
PASS

bash surprising-aeron-core/runtime/w3-w5/tests/w4-static.sh
W4_STATIC_PREP=PASS manifests=6 order=required wallet=absent maker=required faults=not-exercised checker=fail-closed

git diff --check
PASS
```

The static path produces six uniquely named manifests in this exact order:

```text
1-SPOT.manifest
2-LINEAR_PERPETUAL.manifest
3-INVERSE_PERPETUAL.manifest
4-LINEAR_DELIVERY.manifest
5-INVERSE_DELIVERY.manifest
6-OPTION.manifest
```

Every manifest is checked for an exact `rows=` value, product-line identity,
CORE selection/projection authority, wallet absence, and monotonic cursor policy.
Static manifests are explicitly `mode=STATIC_PREP` and `W4_STATUS=STATIC_PREP`,
with `fundsReconciliation=NOT_RUN`; they contain no `FUNDS_DIFFERENCE=0`.
Static line start/stop receipts use `STATIC_PREP` and `cleanup=STATIC_PREP`.
The adversarial checker test removes a required SPOT row and observes
`ROWS_MISMATCH productLine=SPOT` with a non-zero exit. Static fault preparation
records `faults=NOT_EXERCISED`; it does not label cursor/PG attacks as rejected.

## JDK 25 compile/test

IBM Semeru JDK 25 was selected with `/usr/libexec/java_home -v 25`:
`IBM Semeru Runtime Open Edition 25.0.2.1` / OpenJ9 `25.0.2+10-LTS`.
Maven's `require-jdk-25` enforcer passed.

```text
mvn -pl :surprising-aeron-tools -am -DskipTests compile
BUILD SUCCESS

mvn -pl :surprising-aeron-tools -am -Dtest=W4LifecycleQaMainTest,OfflineReplayMainTest -Dsurefire.failIfNoSpecifiedTests=false test
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The W4 driver tests prove that real mode fails before `SurprisingAeronClient`
connection or manifest creation with:
`W4_REAL_CAPABILITY_PENDING missing=provider-to-core-lifecycle,cursor-repeat-gap,pg-selected,maker-user-treasury-reconciliation`.
The query allocator assigns the increment back to `sequence`. Manifest writing
is guarded by observed provider-boundary, maker, and user/treasury reconciliation
state; no verify/fault/static path can emit `FUNDS_DIFFERENCE=0` or a real pass
without those observations.

## Scope boundary

No real runtime was started, no runtime lock was acquired, and no wallet service
was started. The repository has separate provider modules/controllers, but this
Task16 driver has no wired provider-to-Core lifecycle/fault boundary. Therefore
real `execute`, `verify`, and `faults` are deliberately fail-closed as pending;
there is no claimed real W4 pass and no synthetic cursor-repeat/gap or PG-selected
rejection evidence. A later executor must add/use the real provider boundary,
exercise those faults with before/after state checks, and retain exact user,
maker, treasury, lifecycle, and cleanup receipts before changing this scope.
