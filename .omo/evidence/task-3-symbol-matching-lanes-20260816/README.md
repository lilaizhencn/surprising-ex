# Task 3 evidence

## Same-symbol serialization and cross-symbol independence

- Scenario: symbol 10 operation B waits for controlled operation A, while symbol 11 operation C submits immediately.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=SymbolMatchingLanesTest#sameSymbolSerializesWhileDifferentSymbolsProceed -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: Maven exit 0; one test, zero failures/errors/skips; assertion fixes submission trace as `symbol10-A, symbol11-C, symbol10-B`.
- Artifact: `task-3-symbol-lanes.log`.

## Lane failure recovery and all-lane barrier ordering

- Scenario: an exceptional symbol future is followed by successful same-symbol work; a barrier waits two controlled lane tails, post-barrier work waits it, and a second barrier runs last.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=SymbolMatchingLanesTest,DeterministicExchangeCoreAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: Maven exit 0; four tests, zero failures/errors/skips. The barrier test fixes trace as `barrier-1, symbol10-after, barrier-2`.
- Artifact: `task-3-targeted-tests.log`.

## Ordered partial cancellation

- Scenario: controlled order 1 succeeds, order 2 returns `MATCHING_INVALID_ORDER_ID`, and order 3 is never submitted but retains its ordered `NOT_SUBMITTED` slot.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -Dtest=DeterministicExchangeCoreAdapterTest#cancelBatchStopsAtFirstFailureAndReturnsSuccessfulPrefix -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: Maven exit 0; one test, zero failures/errors/skips; assertions verify submissions `[1, 2]`, successful prefix `[SUCCESS]`, exact rejection, and ordered result codes.
- Artifact: `task-3-symbol-lanes-error.log`.

## Required reactor validation

- Scenario: compile affected module and dependencies, then execute both Task 3 test classes.
- Invocation: `/usr/bin/env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SymbolMatchingLanesTest,DeterministicExchangeCoreAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Binary observable: Maven exit 0; all six reactor projects succeed; four tests pass with zero failures/errors/skips.
- Artifact: `task-3-reactor-tests.log`.

## Stop-hook direct re-verification

- Final-source reactor invocation repeated at 2026-08-16 13:54 Asia/Shanghai.
- Binary observable: exit 0, all six reactor projects `SUCCESS`, four Task 3 tests run with zero failures, errors, or skips.
- Java LSP observable: matching package scanned four Java files with zero diagnostics.
- Static observable: `git diff --check` exit 0 and `matchingEnginesNum(1)` remains present at the adapter configuration.
- Artifacts: `hook-verification-tests.log` and `hook-verification-static.log`.
