# Manual QA: trigger-order workload boundaries

## manualQa

### surfaceEvidence

| scenario id | criterion reference | surface | exact invocation | verdict | artifactRefs |
|---|---|---|---|---|---|
| TOW-01 | mark-price candidate budget | TriggerOrderIndex in-process | JDK-25 JShell: 5,000 price-hit + 5,000 trailing pending triggers; call `candidates("BTC-USDT", 100)` and `candidates("BTC-USDT", 1)` | FAIL | E1, S1 |
| TOW-02 | risk continuation budget | Aeron core Maven tests | `JAVA_HOME=...open-25... mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreRiskStateTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS | E1, S2 |
| TOW-03 | trigger command/async continuation | Aeron core Maven tests | `JAVA_HOME=...open-25... mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest,CoreMatchingStateTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS | E1, S3 |
| TOW-04 | mark-price publication | Price provider Maven tests | `JAVA_HOME=...open-25... mvn -pl surprising-price/surprising-price-provider -am -Dtest=MarkPriceServiceTest,MarkPriceCorePublisherTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS | E1, S4 |
| TOW-05 | trigger maintenance configuration/delegation | Trigger provider Maven tests | `JAVA_HOME=...open-25... mvn -pl surprising-trading/surprising-trigger-provider -am -Dtest=TriggerPropertiesTest,TriggerOrderMaintenanceTaskTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS | E1, S5 |
| TOW-06 | OCO and cross-position workload | TriggerOrderIndex in-process | JDK-25 JShell: 2,500 NET + 2,500 LONG same OCO group; call `ocoSiblings` for each side | FAIL | E1, S1 |

### adversarialCases

| scenario id | criterion reference | adversarial class | expected behavior | verdict | artifactRefs |
|---|---|---|---|---|---|
| ADV-01 | mark-price workload boundary | 10,000 eligible price-hit/trailing triggers | Per-mark-price command should enforce a finite candidate/trigger budget or continuation | FAIL | E1, S1 |
| ADV-02 | OCO workload boundary | 2,500 same-position OCO siblings | Trigger should bound sibling cancellation work or continue it | FAIL | E1, S1 |
| ADV-03 | cross-position isolation | NET/LONG triggers share symbol and OCO group | Candidate/OCO index must not mix position sides | PASS | E1, S1 |
| ADV-04 | risk continuation input boundary | `maxUsers` <=0 or >4096 | Reject invalid continuation batch; valid workload progresses in bounded chunks | PASS | E1, S2 |
| ADV-05 | maintenance pagination > one page | More than 1,000 expired/stale trigger orders | Page and scan bounds should be exercised end-to-end | INCONCLUSIVE — no fixture/harness creates this workload; source caps are not treated as execution evidence | E1, S5 |
| ADV-06 | trailing-stop state evolution under thousands of updates | Large trailing set with activation/high-low updates | Updates and triggers should be bounded and preserve state | INCONCLUSIVE — no dedicated executable fixture exists | E1, S1 |

### artifactRefs

| id | kind | description | path |
|---|---|---|---|
| E1 | evidence-log | Exact commands, pass counts, JShell cardinalities, and static boundary observations | [trigger-order-workload-evidence.md](/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/trigger-order-workload-evidence.md) |
| S1 | source | Trigger candidate/OCO index and mark-price evaluation implementation | [TriggerOrderIndex.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TriggerOrderIndex.java), [CoreProbeState.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java) |
| S2 | test | Bounded risk continuation and cross-margin tests | [CoreRiskStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/state/CoreRiskStateTest.java) |
| S3 | test | Core command, trigger execution, async matching continuation tests | [CoreProbeStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java), [CoreMatchingStateTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java) |
| S4 | test | Mark-price service/core publisher tests | [MarkPriceServiceTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/test/java/com/surprising/price/mark/service/MarkPriceServiceTest.java), [MarkPriceCorePublisherTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-price/surprising-price-provider/src/test/java/com/surprising/price/mark/service/MarkPriceCorePublisherTest.java) |
| S5 | test | Trigger configuration and maintenance delegation tests | [TriggerPropertiesTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-trading/surprising-trigger-provider/src/test/java/com/surprising/trading/trigger/config/TriggerPropertiesTest.java), [TriggerOrderMaintenanceTaskTest.java](/Users/atomex/Desktop/surprising/surprising-ex/surprising-trading/surprising-trigger-provider/src/test/java/com/surprising/trading/trigger/task/TriggerOrderMaintenanceTaskTest.java) |
