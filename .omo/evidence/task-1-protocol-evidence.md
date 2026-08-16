# Task 1 protocol evidence

Evidence root: `.omo/evidence/` (no active `ulw-loop` attempt was present).

## Wire registry and complete targeted protocol suite

- Scenario: additive command/result wire codes, preserved single-action constructors and cursors, work-v2 continuation/cursor payload, sorted/unique bounded batch actions, immutable action list, malformed counts/cursors, all truncations, and trailing-byte rejection.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=TradingCommandCodecTest,CoreLiquidationWorkCodecTest,CoreLiquidationBatchCodecTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: exit `0`; `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`; reactor `BUILD SUCCESS` on IBM Semeru JDK 25.0.2.
- Captured artifact: `.omo/evidence/task-1-protocol-full.log`

## Canonical byte-identical round trip

- Scenario: encode the same batch twice, decode/re-encode it, and round-trip the bounded result payload.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=CoreLiquidationBatchCodecTest#roundTripsCanonicalBatchAndResult -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: exit `0`; `Tests run: 1, Failures: 0, Errors: 0`; reactor `BUILD SUCCESS`.
- Captured artifact: `.omo/evidence/task-1-protocol.log`

## Cancellation budget rejection

- Scenario: wire payloads with shared cancellation budgets `0` and `1025` both fail with `ProtocolException` and produce no command.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=CoreLiquidationBatchCodecTest#rejectsZeroAndOverLimitCancellationBudgets -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: exit `0`; `Tests run: 1, Failures: 0, Errors: 0`; reactor `BUILD SUCCESS`.
- Captured artifact: `.omo/evidence/task-1-protocol-error.log`

## Static and failing-first evidence

- Scenario: changed Java sources and tests have no language-server diagnostics.
- Invocation: `mcp__lsp__diagnostics` on each of the 20 changed protocol/test files.
- Binary observable: `20 changed files: 0 LSP diagnostics`.
- Captured artifact: this evidence record; compilation output is also captured in `.omo/evidence/task-1-protocol-full.log`.
- Scenario: whitespace/static diff validation.
- Invocation: `git diff --check`.
- Binary observable: exit `0`.
- Captured artifact: `.omo/evidence/task-1-static-checks.txt`.
- Failing-first artifact: `.omo/evidence/task-1-failing-first.log` records the expected missing-contract compilation failure before production implementation.

## Existing service source compatibility

- Scenario: cleanly recompile all direct Aeron service sources against the changed protocol without editing CoreProbeState or service code.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am clean -DskipTests compile`
- Binary observable: exit `0`; protocol compiled 99 source files and service compiled 53 source files; reactor `BUILD SUCCESS`.
- Captured artifact: `.omo/evidence/task-1-downstream-compile.log`.

No CoreProbeState, adapter, provider, or documentation file was edited for this task. No commit was created.
