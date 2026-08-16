# Task 16 static W4 evidence

Date: 2026-08-17
Worktree: `/Users/atomex/Desktop/surprising/w3w5-t16-real-w4`
Branch: `codex/w3w5-t16-real-w4`
Base before changes: `e508b8cdd5aa2315eeeadb49a03d3636a6453e6d`
Plan source: `/Users/atomex/Desktop/surprising/surprising-ex/.omo/plans/w3-w5-production-closure.md`, Task 16

## Static checks

```text
bash -n surprising-aeron-core/runtime/w3-w5/run.sh surprising-aeron-core/runtime/w3-w5/scenarios/common.sh surprising-aeron-core/runtime/w3-w5/scenarios/w4-six-line.sh surprising-aeron-core/runtime/w3-w5/tests/w4-static.sh
PASS

./surprising-aeron-core/runtime/w3-w5/tests/w4-static.sh
W4_STATIC=PASS manifests=6 order=required wallet=absent maker=required

W4_STATIC_ONLY=true ... run.sh scenario w4-faults
W4_FAULTS=PASS productLine=LINEAR_PERPETUAL cleanup=PASS wallet=ABSENT

git diff --check
PASS
```

The static path produced six uniquely named manifests in this exact order:

```text
1-SPOT.manifest
2-LINEAR_PERPETUAL.manifest
3-INVERSE_PERPETUAL.manifest
4-LINEAR_DELIVERY.manifest
5-INVERSE_DELIVERY.manifest
6-OPTION.manifest
```

The static assertions cover the required SPOT conservation/control rows, perpetual CROSS/ISOLATED rows, delivery CROSS/ISOLATED rows, option CALL/PUT ITM/ATM/OTM rows, CORE selection authority, maker required, wallet absent, cursor policy, and `FUNDS_DIFFERENCE=0` manifest fields. The runtime driver includes bounded Core lifecycle calls for mark, funding, risk, liquidation, insurance/ADL work queries, delivery settlement, option settlement, state/progress snapshots, cross-line rejection, and funds reconciliation.

## JDK 25 compile/test

```text
task_java_home=$(/usr/libexec/java_home -v 25) && export JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" && java -version
openjdk version "25.0.2"

mvn -pl :surprising-aeron-tools -am -DskipTests compile
BUILD SUCCESS

mvn -pl :surprising-aeron-tools -am -Dtest=OfflineReplayMainTest -Dsurefire.failIfNoSpecifiedTests=false test
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Scope boundary

No real runtime was started, no runtime lock was acquired, and no wallet service was started. This is static implementation evidence only; it does not claim a real six-line W4 QA pass. The later executor must run the real line-scoped stack and retain each `UP=PASS`, maker-last, `MAIN_WORKTREE_PROTECTED=PASS`, and `CLEANUP=PASS` receipt.
