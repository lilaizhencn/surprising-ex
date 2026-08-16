# Trigger-order workload QA evidence

Date: 2026-08-16 (Asia/Shanghai)
Surface: local Maven unit tests and in-process JShell probes; no services, Kafka, wallet, or external systems started.

## Executed invocations

1. `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreRiskStateTest -Dsurefire.failIfNoSpecifiedTests=false test` — blocked by the project enforcer because the shell default was JDK 21 while this repository requires JDK 25.
2. `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreRiskStateTest -Dsurefire.failIfNoSpecifiedTests=false test` — BUILD SUCCESS; 7 tests, 0 failures/errors/skips. Covers 1,300-user bounded risk continuation, multiple symbols, newer-price restart, cross-margin portfolio risk.
3. `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home mvn -pl surprising-price/surprising-price-provider -am -Dtest=MarkPriceServiceTest,MarkPriceCorePublisherTest -Dsurefire.failIfNoSpecifiedTests=false test` — BUILD SUCCESS; 13 tests, 0 failures/errors/skips.
4. `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home mvn -pl surprising-trading/surprising-trigger-provider -am -Dtest=TriggerPropertiesTest,TriggerOrderMaintenanceTaskTest -Dsurefire.failIfNoSpecifiedTests=false test` — BUILD SUCCESS; 2 tests, 0 failures/errors/skips.
5. `JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest,CoreMatchingStateTest -Dsurefire.failIfNoSpecifiedTests=false test` — BUILD SUCCESS; 36 tests, 0 failures/errors/skips. Includes basic trigger execution, mark-price crossing, async trigger matching continuation, duplicate handling.
6. Explicit JDK-25 JShell probe against `TriggerOrderIndex` constructed 5,000 price-hit and 5,000 trailing pending triggers for `BTC-USDT`: `candidates("BTC-USDT", 100)` returned `10000`; `candidates("BTC-USDT", 1)` returned `5000`.
7. Explicit JDK-25 JShell probe against `TriggerOrderIndex` constructed 2,500 NET and 2,500 LONG pending triggers in one OCO group: `ocoSiblings(NET)` returned `2500`, `ocoSiblings(LONG)` returned `2500`, and crossing candidates were correctly partitioned only when filtered by position side (2,500 NET).

## Static boundary evidence

- `CoreProbeState.evaluateMarkPriceTriggers` iterates every ID returned by `TriggerOrderIndex.candidates` and has no per-command candidate/trigger budget.
- `TriggerOrderIndex.candidates` merges all eligible price buckets and all trailing IDs into an unbounded `TreeSet`.
- `CoreProbeState.cancelOcoSiblings` iterates the full OCO sibling set and has no sibling budget.
- Risk continuation is explicitly bounded by `ContinueRiskScanCommand`/`TradingCoreReducer` to `1..4096`; mark-price tests exercise the default 1,024-user chunk.
- Trigger maintenance has page size capped at 1,000 and max pages capped at 256, but no test exercised a page-boundary workload or stale retry fanout.

## QA conclusion

FAIL for the requested trigger-order workload-boundary criterion: thousands of price-hit and trailing candidates, and thousands of OCO siblings, are processed/returned in one mark-price path with no trigger or sibling budget. PASS for existing mark-price publication tests, risk continuation budget tests, basic trigger/async continuation tests, and cross-position index partitioning. End-to-end thousand-trigger execution, trailing state evolution, and OCO cancellation under load remain untested because no dedicated fixture/harness exists.
