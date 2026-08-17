# W1/W2 Single-Book Native Matcher Snapshot

## TL;DR
> Summary:      Release a generic in-memory snapshot processor from the pinned exchange-core fork, then make Aeron Core snapshots own the exchange-core ME0/RE0 blobs and remove the duplicate Java FIFO book and replay recovery. Restore is snapshot-only, configuration- and checksum-bound, and any matcher/Core divergence fails the cluster member closed.
> Deliverables:
> - `exchange-core:0.5.9-emporia` at a recorded immutable fork commit, with thread-safe import/export and snapshot-only tests
> - CoreState snapshot v6 with matcher modules, provenance, configuration fingerprint, symbol/user registries, and nested CRC32C checksums
> - TradingState snapshot v19 without `CoreBookState`; active orders derive from `CoreOrderState.status()` and `ActiveOrderIndex`
> - Barrier-fenced capture, `InitialStateConfiguration.fromSnapshotOnly` restore, reconciliation, fatal divergence semantics, and six-product-line recovery tests
> - Coordinated pre-launch rollout/rollback instructions and updated Chinese architecture documentation
> Effort:       XL
> Risk:         High - this changes the sole recovery path for matching state and removes a duplicate order-book representation that currently masks restore failures.

## Scope
### Must have
- Preserve both repositories before work: fork `/Users/atomex/Desktop/exchange-core-lilaizhencn` is currently clean and pushed at `f3ab4953878491620629e405b2a251fd5a209710`, a direct descendant of required baseline `4715e96880b82d57c7390499b5853084657eccab`, with the first in-memory processor WIP already committed; never reset it. Main `/Users/atomex/Desktop/surprising/surprising-ex` remains untouched on dirty `codex/aeron-unified-core`. The dirty baseline was actively changing during planning and, at final inspection, covered `CoreProbeState`, `CoreSnapshotManifest`, `CoreStateSnapshotCodec`, `SurprisingClusteredService`, `TradingCoreRuntime`, `DeterministicExchangeCoreAdapter`, `CoreBookState` deletion, rolling hash/reducer/state/TradingState codec, `surprising-parent/pom.xml`, untracked `MatcherSnapshot`/`MatcherSnapshotCodec`, and staged/unstaged `WebSocketRuntimeHints`; capture the exact live inventory immediately before execution because more concurrent edits may exist.
- Execute main tasks in a clean sibling worktree on `codex/w1-w2-single-book-snapshot`, based on the recorded `codex/aeron-unified-core` HEAD. Export a binary patch plus copies/checksums of all dirty/untracked baseline files to evidence before work. Reconcile the relevant matcher WIP into the implementation branch without modifying, staging, committing, stashing, or cleaning the original dirty worktree; omit unrelated gateway work. Do not merge/cherry-pick back into the dirty branch until the caller approves F1-F4.
- Complete `exchange.core2.core.processors.journaling.InMemorySerializationProcessor` through the existing `ISerializationProcessor` contract with exact public APIs `InMemorySerializationProcessor()`, `void importSnapshot(Collection<SerializedModule>)`, `List<SerializedModule> exportSnapshot(long snapshotId)`, and `void removeSnapshot(long snapshotId)`. `SerializedModule(snapshotId, sequence, timestampNs, type, instanceId, data)` exposes a computed unsigned CRC32C `checksum()` and defensive `data()`. Store immutable copies keyed by `(snapshotId, moduleType, instanceId)`, import atomically, and support concurrent ME/RE stores and loads.
- Keep journaling disabled. Snapshot-only startup may invoke the existing replay hook with `journalTimestampNs == 0`, which is a no-op; any attempt to enable/write/replay a real journal is rejected rather than silently accepted.
- Release fork coordinate `exchange.core2:exchange-core:0.5.9-emporia`, update the benchmark module and changelog, install it with JDK 25, push its atomic commits, and record the resulting immutable fork SHA in main.
- Complete the existing WIP as one authoritative outer matcher snapshot value: `MatcherSnapshot(exchangeId, artifactVersion, forkSha, snapshotId, snapshotBaseSequence, outerAppliedCommandCount, configurationChecksum, registryChecksum, matchingEngineCount, riskEngineCount, symbols, users, modules, checksum)`. Its nested `Module(moduleTypeCode, instanceId, sequence, timestampNs, checksum, payload)` defensively copies payloads; replace WIP book/hash shortcuts that do not satisfy exact reconciliation rather than introducing a duplicate `CoreMatcherSnapshot` type.
- Bind the matcher snapshot to `ProductLine`, exchange ID, artifact version, fork SHA, one matching engine, one risk engine, `MATCHING_ONLY`, margin trading disabled, symbol/user registries, and the outer applied-command watermark. Reject any mismatch before the member becomes ready.
- Capture inside `SymbolMatchingLanes.barrier`: close admission, wait all lane tails and callbacks, require `pendingMatching` and completed-but-unapplied matching results to be empty, submit `ApiPersistState`, export exactly ME0 and RE0, encode/publish the outer snapshot, then reopen admission. The exact rejection is `cannot snapshot while matching commands are pending`.
- Restore imported ME0/RE0 blobs before building exchange-core, using `InitialStateConfiguration.fromSnapshotOnly(EXCHANGE_ID, snapshotId, snapshotBaseSequence)` and a `SerializationConfiguration` whose processor factory returns the imported in-memory processor and whose journaling flag is false.
- Rebuild only derived indexes. Reconcile every outer `CoreOrderState` with `status == OPEN` against exchange-core user reports before readiness: exact order ID, UID, symbol ID, side, price, remaining quantity, order type/time-in-force, and reserve price. Extra/missing/mismatched engine orders, users, symbols, or module state are fatal.
- Remove `CoreBookState` and unused `CoreBookOrder` from state, reducers, codecs, hashes, constructors, and tests. Remove production priority-sequence/FIFO reconstruction; exchange-core is the only FIFO owner.
- Remove production matcher rebuild/retry/resubmission and generation recovery. Expected command rejections follow the existing deterministic rejection path; asynchronous failure, timeout, malformed result, lifecycle mismatch, snapshot failure, or matcher/Core divergence throws `FatalMatchingDivergenceException` and escapes the Aeron callback.
- CoreState v6 and TradingState v19 explicitly reject earlier versions. No pending matching command is serialized. Snapshot inspection validates all lengths, identities, counts, configuration/registry/module checksums, and the outer checksum without starting exchange-core.
- Keep funds and positions in Aeron `TradingCoreState`; verify user and market-maker conservation across capture/restore and subsequent Aeron log replay for all six isolated product lines.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- Do not use shell redirection, copy commands, generated rewrites, or editor writes for downstream source changes; use `apply_patch` only.
- Do not stash, reset, clean, amend, or broadly stage the dirty main worktree. Never include `surprising-gateway/src/main/java/com/surprising/websocket/provider/WebSocketRuntimeHints.java` unless the user separately assigns it.
- Do not add DB, Kafka, Redis, wallet, disk snapshots, or network I/O to the matching hot path or snapshot processor.
- Do not add a v5/v18 compatibility shim, live-order replay fallback, clean-start restore fallback, partial module restore, best-effort continuation, or automatic selection of a mismatched older snapshot.
- Do not alter `MATCHING_ONLY`, enable margin trading, increase ME/RE counts beyond one, mix product-line state, or implement hot-symbol sharding.
- Do not move funds/positions authority into exchange-core or make aggregate reports a substitute for exact order reconciliation.
- Do not preserve `CoreBookState`, priority sequence, or any second FIFO representation in production under a renamed type.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD + JUnit 5 through Maven Surefire; write each regression/contract test before its production change, then run the exact affected module command with JDK 25.
- QA policy: every task has agent-executed scenarios
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` — under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`
- Baseline policy: before creating the sibling worktree, capture `git status --short`, `git diff --binary`, `git diff --cached --binary`, SHA-256 for every modified/untracked file, and the branch/HEAD. After every task, re-run the same checks in the original dirty worktree and prove byte contents, index stages, branch, and HEAD are unchanged. Commit only inside the clean implementation worktree with exact pathspecs.
- Worktree preflight: set `W1_W2_WORKTREE=/Users/atomex/Desktop/surprising/w1-w2-single-book-snapshot-worktree`; require that path not exist; run `git -C /Users/atomex/Desktop/surprising/surprising-ex worktree add -b codex/w1-w2-single-book-snapshot "$W1_W2_WORKTREE" "$(git -C /Users/atomex/Desktop/surprising/surprising-ex rev-parse codex/aeron-unified-core)"`. Recreate only relevant preserved WIP hunks in the new worktree with `apply_patch` after their patches/checksums are captured; all main commands below run in `$W1_W2_WORKTREE`.
- Runtime policy: every Maven invocation selects JDK 25 with `task_java_home=$(/usr/libexec/java_home -v 25)`; no test starts wallet or more than one product line at a time.

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave. <3 per wave (except final) = under-splitting.
> Extract shared dependencies as Wave-1 tasks to maximize parallelism.
> This migration is deliberately serialization-heavy: fork release, main dependency pin, native restore, and duplicate-state deletion are hard gates. Parallelism is used only where an independently buildable commit cannot create an unrecoverable intermediate main state.

Wave 1 (no dependencies):
- Task 1: harden and unit-test the committed fork in-memory processor contract

Wave 2 (after Wave 1):
- Task 2: prove real ME0/RE0 snapshot-only restore and release `0.5.9-emporia`; depends [1]
- Task 3: reconcile the existing main matcher snapshot WIP into the bounded value model/section codec; depends [1]

Wave 3 (after Wave 2):
- Task 4: pin the fork release and add native adapter capture/restore/reconciliation; depends [2, 3]

Wave 4 (after Wave 3):
- Task 5: switch CoreState to v6 and integrate barrier-fenced CoreProbe/runtime snapshot restore; depends [4]

Wave 5 (after Wave 4):
- Task 6: remove duplicate book/FIFO and all rebuild/retry recovery, with TradingState v19; depends [5]

Wave 6 (after Wave 5):
- Task 7: enforce clustered-service fail-closed lifecycle and complete six-line recovery/documentation; depends [6]

Critical path: Task 1 -> Task 2 -> Task 4 -> Task 5 -> Task 6 -> Task 7

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1    | none       | 2, 3   | none                 |
| 2    | 1          | 4      | 3                    |
| 3    | 1          | 4      | 2                    |
| 4    | 2, 3       | 5      | none                 |
| 5    | 4          | 6      | none                 |
| 6    | 5          | 7      | none                 |
| 7    | 6          | F1-F4  | none                 |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 1. Harden the committed generic fork in-memory serialization processor

  What to do: Audit and complete the clean, pushed `f3ab4953878491620629e405b2a251fd5a209710` WIP rather than recreating or reverting it. Lock missing unit tests for concurrency, duplicate/corrupt input, and journal rejection. Preserve the existing `SerializedModule` constructor/accessors and add computed unsigned CRC32C `checksum()` plus `removeSnapshot(long snapshotId)`. Marshal each `BytesOut` payload before taking the map write lock; atomically publish a unique key; reject duplicate keys; export only a stable, canonically sorted copy (`moduleType` code then instance ID); atomically validate every module and then import the collection under one write lock. Defensive-copy every inbound/outbound byte array. `loadData` validates key and checksum before unmarshalling. `checkSnapshotExists` is exact-key based. `findAllSnapshotPoints` is deterministic and never null. Real journal enable/write/replay calls throw `UnsupportedOperationException`; only the startup replay call with `journalTimestampNs == 0` is a no-op so `fromSnapshotOnly` can start with journaling disabled. Keep the processor generic over module counts; completeness is a caller policy.
  Must NOT do: Do not touch disk, add a global singleton, return mutable payloads, accept duplicate or corrupt modules, infer ME/RE cardinality, or enable journal replay.

  Parallelization: Can parallel: NO | Wave 1 | Blocks: [2, 3] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/journaling/ISerializationProcessor.java:29-65` - synchronous thread-safe store/load contract.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/journaling/InMemorySerializationProcessor.java:31-170` - committed WIP to harden in place.
  - API/Type: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/journaling/ISerializationProcessor.java:68-124` - journal hooks, snapshot existence, and `SerializedModuleType` codes.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/journaling/DiskSerializationProcessor.java:132-209` - existing Chronicle marshal/unmarshal pattern, not its file I/O.
  - API/Type: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/common/config/InitialStateConfiguration.java:84-100` - strict snapshot-only configuration and zero journal timestamp.
  - Test:     `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/test/java/exchange/core2/tests/integration/InMemorySerializationProcessorTest.java:25-114` - committed FIFO/defensive-copy WIP tests to retain and extend.
  - Test:     `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/test/java/exchange/core2/tests/integration/PersistenceTestsModule.java:40-119` - persistence test assertions and hash/funds conventions.
  - External: `https://github.com/exchange-core/exchange-core/commit/830b226791d71b63ae120ab8fa1f3e299bdbf1bf` - upstream origin of initial-state and journaling configuration APIs.

  Acceptance criteria (agent-executable only):
  - [ ] `test "$(git -C /Users/atomex/Desktop/exchange-core-lilaizhencn rev-parse HEAD)" = f3ab4953878491620629e405b2a251fd5a209710 && git -C /Users/atomex/Desktop/exchange-core-lilaizhencn merge-base --is-ancestor 4715e96880b82d57c7390499b5853084657eccab HEAD && test -z "$(git -C /Users/atomex/Desktop/exchange-core-lilaizhencn status --short)"` passes before the first Task 1 patch.
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=InMemorySerializationProcessorTest test'` passes in the fork.
  - [ ] Tests deterministically cover concurrent ME/RE stores, concurrent export/load, defensive copies, canonical order, duplicate keys, missing keys, truncation/bit corruption, remove semantics, zero-timestamp no-op, and rejection of every journaling operation.
  - [ ] `rg -n 'Files\.|Path\.|FileChannel|MappedByteBuffer|DiskSerializationProcessor' src/main/java/exchange/core2/core/processors/journaling/InMemorySerializationProcessor.java` returns no matches.

  QA scenarios (MANDATORY - task incomplete without these):
  > Name the exact tool AND its exact invocation - not "verify it works". Browser use: in Codex, use `browser:control-in-app-browser` first when available and no authenticated/persistent user browser profile is required; otherwise use Chrome to drive the page, or agent-browser (https://github.com/vercel-labs/agent-browser) when Chrome is unavailable. Computer use: OS-level GUI automation for a non-browser desktop app.
  ```
  Scenario: concurrent matching/risk module round-trip
    Tool:     bash
    Steps:    cd /Users/atomex/Desktop/exchange-core-lilaizhencn && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=InMemorySerializationProcessorTest#storesAndLoadsConcurrentModulesAtomically test
    Expected: Maven exits 0; exported modules are exactly the test's canonical set and every loaded object equals its source.
    Evidence: <attemptDir>/task-1-fork-memory-processor.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: corrupt and journaling inputs fail closed
    Tool:     bash
    Steps:    cd /Users/atomex/Desktop/exchange-core-lilaizhencn && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=InMemorySerializationProcessorTest#rejectsCorruptDuplicateAndJournalOperations test
    Expected: Maven exits 0; corruption/duplicates produce the asserted validation exception and nonzero journal replay produces `UnsupportedOperationException`.
    Evidence: <attemptDir>/task-1-fork-memory-processor-error.txt
  ```

  Commit: YES | Message: `test(snapshot): harden in-memory serialization processor` | Files: [`src/main/java/exchange/core2/core/processors/journaling/InMemorySerializationProcessor.java`, `src/test/java/exchange/core2/tests/integration/InMemorySerializationProcessorTest.java`]

- [ ] 2. Prove native snapshot-only restore and release the fork artifact

  What to do: Add a dedicated real `ExchangeCore` integration fixture configured with one ME, one RE, `MATCHING_ONLY`, margin trading disabled, and the new processor. Create users/symbols and same-price orders, partially fill the first order, call `ApiPersistState`, require both persist results to succeed, export exactly `(MATCHING_ENGINE_ROUTER,0)` and `(RISK_ENGINE,0)`, stop, import into a new processor, and start a fresh core with `InitialStateConfiguration.fromSnapshotOnly`. Prove state hash, user reports, balances, open-order details, partial quantity, GTX behavior, and same-price FIFO after another crossing order. Add missing/duplicate/swapped/corrupt module and nonzero-journal negative tests. Retain root `0.5.9-emporia` from `f3ab495`, bump `benchmarks/pom.xml` to the same version, complete the changelog entry, run all fork tests, commit and push, record `FORK_RELEASE_SHA=$(git rev-parse HEAD)`, install that exact source locally, and record SHA-256 of `/Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.9-emporia/exchange-core-0.5.9-emporia.jar`. Never rebuild the same version from a different SHA.
  Must NOT do: Do not alter matching/risk serialization formats, add disk fallback, publish before all tests pass, or use a snapshot missing either module.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [4] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/ExchangeApi.java:383-394` - combined persistence future.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/ExchangeApi.java:558-593` - adjacent matching/risk persist commands sharing a dump ID.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/MatchingEngineRouter.java:119-150` - ME snapshot-only load order and shard identity.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/MatchingEngineRouter.java:204-213` - ME persist callback.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/RiskEngine.java:115-159` - RE snapshot load.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/RiskEngine.java:304-313` - RE persist callback.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/RiskEngine.java:371-380` - separate `MATCHING_ONLY` and margin gates.
  - Test:     `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/test/java/exchange/core2/tests/integration/PersistenceTestsModule.java:40-119` - native round-trip test pattern.
  - Test:     `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/test/java/exchange/core2/tests/integration/GtxJournalingTest.java:35-115` - GTX, state-hash, and funds assertions.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/pom.xml:5-8` - artifact coordinate/version.
  - Pattern:  `/Users/atomex/Desktop/exchange-core-lilaizhencn/pom.xml:43-55` - Java 25 and JUnit configuration.
  - External: `https://github.com/lilaizhencn/exchange-core/tree/4715e96880b82d57c7390499b5853084657eccab` - immutable starting fork source.

  Acceptance criteria (agent-executable only):
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=InMemorySerializationProcessorTest,InMemorySnapshotRestoreTest test'` passes in the fork.
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn test'` passes in the fork with no disk snapshot fixture used by the new test.
  - [ ] `rg -n '0\.5\.9-emporia' pom.xml benchmarks/pom.xml CHANGELOG.md` reports all three release locations and `rg -n '0\.5\.8-emporia' pom.xml benchmarks/pom.xml` reports none.
  - [ ] After the release commit and push, `git status --short` is empty, `git rev-parse HEAD` equals `git rev-parse origin/master`, and `mvn -q help:evaluate -Dexpression=project.version -DforceStdout` prints `0.5.9-emporia` under JDK 25.
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -DskipTests install'` succeeds from the recorded release SHA.
  - [ ] `shasum -a 256 /Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.9-emporia/exchange-core-0.5.9-emporia.jar` is captured with `FORK_RELEASE_SHA` and both exact values are used by Task 3/4 provenance fields.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: fresh-core snapshot-only FIFO and partial-fill restore
    Tool:     bash
    Steps:    cd /Users/atomex/Desktop/exchange-core-lilaizhencn && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=InMemorySnapshotRestoreTest#restoresMatchingAndRiskModulesWithoutJournal test
    Expected: Maven exits 0; maker 1's remainder fills before maker 2 after restore, hashes/reports/funds match, and no journal is enabled.
    Evidence: <attemptDir>/task-2-fork-native-restore.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: incomplete or wrong module collection is rejected
    Tool:     bash
    Steps:    cd /Users/atomex/Desktop/exchange-core-lilaizhencn && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -Dtest=InMemorySnapshotRestoreTest#rejectsIncompleteSwappedOrCorruptModules test
    Expected: Maven exits 0; each invalid fixture fails startup before command admission with the asserted validation error.
    Evidence: <attemptDir>/task-2-fork-native-restore-error.txt
  ```

  Commit: YES | Message: `chore(release): prepare 0.5.9-emporia` | Files: [`src/test/java/exchange/core2/tests/integration/InMemorySnapshotRestoreTest.java`, `pom.xml`, `benchmarks/pom.xml`, `CHANGELOG.md`]

- [ ] 3. Reconcile the main matcher snapshot WIP into the bounded binary contract

  What to do: Start from the dirty baseline's `MatcherSnapshot` and `MatcherSnapshotCodec` only after copying their exact contents into the isolated implementation worktree and preserving the originals in evidence. Refactor them to the contract below and add `MatcherSnapshotLimits`; do not add parallel `CoreMatcherSnapshot*` types. Replace the WIP's book-state hash, FNV registry hashes, big-endian stream codec, ordinal module IDs, hard-coded provisional fork/artifact hashes, and aggregate-only restore check with the specified CRC32C/little-endian/provenance/exact-reconciliation design. The only valid production configuration is exchange ID `aeron-authoritative-book`, release `0.5.9-emporia`, the Task 2 release SHA, one ME, one RE, `MATCHING_ONLY`, and margin disabled. Compute `configurationChecksum` over schema version, product-line code, exchange ID, release, SHA, counts, matching mode, and margin flag. Compute `registryChecksum` over symbols sorted by UTF-8 name and users sorted numerically. Module order is type code then instance ID and must be exactly ME0+RE0. Use CRC32C and little endian throughout. Reject lengths/counts before allocation with exact constants: total encoded matcher section at most `1_073_741_824` bytes, each module at most `536_870_912` bytes, symbols at most `1_000_000`, users at most `10_000_000`, exchange/version strings at most 64 UTF-8 bytes, SHA exactly 40 lowercase hex bytes.

  Define matcher section v1 exactly: 80-byte header `[magic int32=0x4D58534E, version uint16=1, flags uint16=0, snapshotId int64, snapshotBaseSequence int64, outerAppliedCommandCount int64, matchingEngineCount int32=1, riskEngineCount int32=1, moduleCount int32=2, symbolCount int32, userCount int32, exchangeIdLength int32, artifactVersionLength int32, forkShaLength int32, registryChecksum uint64, configurationChecksum uint64]`; then exchange ID/version/SHA UTF-8; symbols sorted as `[nameLength int32, name bytes, symbolId int32]`; users sorted as `[userId int64]`; modules sorted as 40-byte header `[typeCode int32, instanceId int32, sequence int64, timestampNs int64, payloadLength int32, reserved int32=0, checksum uint64]` plus payload; trailing `matcherChecksum uint64` over all prior matcher-section bytes. Use `Math.addExact`/`multiplyExact`, reject trailing bytes, duplicate names/IDs/users/modules, unknown type codes, reserved/flag bits, nonpositive IDs, and checksum mismatch.
  Must NOT do: Do not put matcher blobs in `TradingStateSnapshotCodec`, serialize pending commands, use Java serialization, expose mutable arrays/maps, or accept unknown module types for forward compatibility.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [4] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:23-106` - current little-endian outer codec and CRC32C idiom.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreSnapshotManifest.java:6-19` - current manifest value style.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshot.java:1-151` - user-owned WIP value model to reconcile; preserve useful registry/provenance intent but remove book-hash and provisional constants.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshotCodec.java:1-192` - user-owned WIP codec to convert from ordinal/big-endian/aggregate validation to the fixed bounded layout.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:43-61` - symbol maps, user set, and matcher lanes that supply registry state.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:363-378` - stable symbol-ID derivation and collision behavior to preserve in fresh state.
  - API/Type: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/processors/journaling/ISerializationProcessor.java:118-124` - persisted module type codes.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:709-730` - current snapshot checksum/version rejection pattern.
  - External: `https://github.com/exchange-core/exchange-core` - engine project contract; no stable cross-fork binary snapshot compatibility is promised, so provenance is mandatory.

  Acceptance criteria (agent-executable only):
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MatcherSnapshotCodecTest test'` passes.
  - [ ] A golden-byte test asserts every offset, canonical ordering, nested checksum, exact two-module set, and decode/encode byte identity.
  - [ ] Parameterized negative tests cover every limit, integer overflow, truncation at each variable field, duplicate symbol name/ID, duplicate user/module, wrong SHA/version/product/configuration, unknown module type, nonzero reserved/flags, and all three checksum layers.
  - [ ] `test -z "$(rg -n 'CoreMatcherSnapshot|CoreMatcherSnapshotCodec|bookStateHash' surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshot*.java || true)"` passes; the implementation worktree contains only `MatcherSnapshot`, `MatcherSnapshotCodec`, and `MatcherSnapshotLimits` for this section.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: canonical matcher-section golden bytes
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MatcherSnapshotCodecTest#encodesCanonicalVersionOneLayout test
    Expected: Maven exits 0 and the encoded bytes exactly match the checked-in offset-by-offset fixture.
    Evidence: <attemptDir>/task-3-matcher-section.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: bounded parser rejects adversarial lengths and checksums
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MatcherSnapshotCodecTest#rejectsMalformedLengthsIdentityAndChecksums test
    Expected: Maven exits 0; every malformed fixture raises the exact `IllegalArgumentException` before unbounded allocation.
    Evidence: <attemptDir>/task-3-matcher-section-error.txt
  ```

  Commit: YES | Message: `feat(snapshot): define matcher snapshot section` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshot.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshotCodec.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/MatcherSnapshotLimits.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/MatcherSnapshotCodecTest.java`]

- [ ] 4. Pin the fork and implement native adapter capture/restore

  What to do: Reconcile the dirty baseline's parent-POM/adapter/runtime WIP into the isolated branch, then update the managed dependency to `0.5.9-emporia` and replace every provisional SHA/hash with the exact Task 2 release SHA and reproducible artifact SHA-256. Refactor `DeterministicExchangeCoreAdapter` to own an `InMemorySerializationProcessor`, expose fresh-start and `fromSnapshot(MatcherSnapshot, ProductLine, long outerAppliedCommandCount)` factories, and expose `CompletableFuture<MatcherSnapshot> captureSnapshotAsync(long outerAppliedCommandCount)`. Capture runs entirely inside `matchingLanes.barrier`: close the gate, drain all lane tails, submit `ApiPersistState(snapshotId)`, require success from both modules, export exactly ME0+RE0, snapshot the forward symbol map and user set, encode checksums, remove the transient processor dump, and reopen the gate in `whenComplete`. Allocate monotonically increasing positive snapshot IDs; after restore start at `restored.snapshotId + 1` with overflow rejection. Set `snapshotBaseSequence` to the maximum module sequence.

  Restore validates provenance/config/registry/module collection first, imports modules, and builds exchange-core with `fromSnapshotOnly`; use the configured in-memory factory and journaling false. Keep one ME/RE, `MATCHING_ONLY`, margin disabled, and busy-spin performance settings. Derive reverse symbols from the saved forward map and reject collisions. Before setting adapter ready, issue exchange-core user reports for every saved user and compare the exact OPEN-order set to the supplied outer orders: order ID, UID, symbol ID, side, price, remaining `size-filled`, order type/TIF, and reserve price. Require engine state hash equality across immediate repeated restores; RE0 must restore and participate in that engine hash even though main funds remain authoritative and engine risk mutation is disabled. Add `FatalMatchingDivergenceException` carrying operation, outer sequence/watermark, snapshot ID, and exchange result/cause. Keep the old rebuild entry point only until Task 5 has switched every caller; mark it for mandatory deletion in Task 6 and do not add new callers.
  Must NOT do: Do not restore with `cleanStart`, iterate/replay `priorityOrder`, synthesize users/symbols from outer orders, accept an extra engine order/user/symbol, or set ready before reconciliation succeeds.

  Parallelization: Can parallel: NO | Wave 3 | Blocks: [5] | Blocked by: [2, 3]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-parent/pom.xml:35` - current exchange-core version property.
  - API/Type: `surprising-parent/pom.xml:60-64` - managed exchange-core dependency.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/pom.xml:33-36` - service dependency consumer.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:43-61` - adapter ownership and registries.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:199-238` - existing barrier-based matcher reads.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:241-321` - rebuild/clean-start path to replace and configuration flags to preserve.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/SymbolMatchingLanes.java:42-83` - gate/barrier completion semantics.
  - API/Type: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/common/config/SerializationConfiguration.java:20-47` - disabled-journal custom processor factory.
  - API/Type: `/Users/atomex/Desktop/exchange-core-lilaizhencn/src/main/java/exchange/core2/core/common/config/InitialStateConfiguration.java:84-100` - required snapshot-only restore factory.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapterTest.java:14-52` - adapter lifecycle test style.

  Acceptance criteria (agent-executable only):
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DeterministicExchangeCoreAdapterTest test'` passes.
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am dependency:tree -Dincludes=exchange.core2:exchange-core'` reports only `0.5.9-emporia`.
  - [ ] `zsh -lc 'expected=$(mvn -q -N -f surprising-parent/pom.xml help:evaluate -Dexpression=exchange-core.sha256 -DforceStdout); actual=$(shasum -a 256 /Users/atomex/.m2/repository/exchange/core2/exchange-core/0.5.9-emporia/exchange-core-0.5.9-emporia.jar); actual=${actual%% *}; test "$expected" = "$actual"'` passes, and the adjacent repository/git-SHA/build-JDK properties equal Task 2 evidence.
  - [ ] Tests prove queued/submitted/completed-callback barrier races cannot leak across capture, capture returns exactly ME0+RE0, two restores are deterministic, snapshot IDs advance, and the adapter remains non-ready on missing/extra/field-mismatched orders.
  - [ ] A source assertion proves the restore branch contains `InitialStateConfiguration.fromSnapshotOnly` and the configured serialization processor; no restore branch invokes `cleanStart` or order replay.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: adapter captures and snapshot-only restores native FIFO
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DeterministicExchangeCoreAdapterTest#capturesAndRestoresNativeModulesWithFifo test
    Expected: Maven exits 0; the restored adapter is ready, preserves the partial maker and fills it before the second same-price maker.
    Evidence: <attemptDir>/task-4-adapter-native-restore.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: outer/engine divergence prevents readiness
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DeterministicExchangeCoreAdapterTest#failsClosedOnOrderRegistryOrConfigurationMismatch test
    Expected: Maven exits 0; missing, extra, and field-mismatched orders plus wrong registry/config provenance each throw `FatalMatchingDivergenceException` before ready.
    Evidence: <attemptDir>/task-4-adapter-native-restore-error.txt
  ```

  Commit: YES | Message: `feat(matching): restore exchange core from native snapshot` | Files: [`surprising-parent/pom.xml`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/FatalMatchingDivergenceException.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapterTest.java`]

- [ ] 5. Integrate CoreState v6 with fenced capture and snapshot-only runtime restore

  What to do: Replace CoreState v5 with v6 and extend `CoreSnapshotManifest`. The outer envelope is the sole owner of matcher blobs; TradingState contains none. Define v6 exactly in little endian: 48-byte header `[magic int32=0x5358534E, version uint16=6, productLine uint8, flags uint8=0, appliedCommandCount int64, probeValue int64, commandResultCount int32, sourceSequenceCount int32, exportSectionLength int32, tradingStateLength int32, matcherSectionLength int32, reserved int32=0]`; source entries remain 24 bytes `[sourceCode int32, reserved int32, sourceId int64, sequence int64]`; command-result entries remain the existing 40-byte v5 layout; export section remains `[acknowledgedCommandCount int64, nextEventId int64, eventCount int32, repeated eventLength int32 + event bytes]`; then TradingState bytes, matcher-section v1 bytes, and outer `checksum uint64` CRC32C over all prior bytes. Remove the pending-matching count and entries. Cross-check matcher `outerAppliedCommandCount` against the header and matcher configuration product line against the header. `CoreSnapshotManifest` exposes outer/trading/matcher versions, lengths/checksums, fork SHA/version, snapshot ID/base sequence, engine counts, registry/config checksums, and module manifests.

  Keep `CoreProbeState.snapshot()` synchronous for the Aeron callback, but implement it as a blocking wrapper around the adapter's fenced future: reject immediately if pending or completed-but-unapplied matching exists, invoke adapter capture, join only that barrier-fenced capture, re-check the deterministic watermark before the adapter reopens admission, encode, and propagate the unwrapped failure. Restore decodes first, constructs `TradingCoreRuntime` with the matcher snapshot, rebuilds only `ActiveOrderIndex` and other derived non-FIFO indexes, performs Task 4 reconciliation, and reports ready only after all checks pass. There is no pending resume. Update snapshot inspection and existing tests. Explicitly reject CoreState v5 and any TradingState version other than the then-current version; Task 6 will advance TradingState to 19.
  Must NOT do: Do not block the Aeron thread waiting on an unfenced matcher future, snapshot pending callbacks, duplicate matcher data in TradingState, accept trailing bytes, or fall back to clean start.

  Parallelization: Can parallel: NO | Wave 4 | Blocks: [6] | Blocked by: [4]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:23-106` - current v5 layout and encode order.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:109-202` - current manifest validation.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java:204-331` - current decode and pending restore to remove.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreSnapshotManifest.java:6-19` - manifest to expand.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:124-215` - runtime construction, pending-order exclusions, and restore factory to replace.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1963-1972` - snapshot/decode/inspection API entry points.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java:38-60` - current runtime creation and rebuild call.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java:187-242` - derived-index transitions and current matcher rebuild.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:315-350` - current pending snapshot behavior to invert.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java:709-730` - checksum/version failures.

  Acceptance criteria (agent-executable only):
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreProbeStateTest,MatcherSnapshotCodecTest test'` passes.
  - [ ] Golden-layout tests assert the 48-byte header, absence of pending entries, exact nested-section offsets, and outer/matcher/module CRCs; v5, wrong product line, wrong fork/version/config, malformed lengths, and trailing bytes are rejected.
  - [ ] A pending command, completed-but-unapplied callback, or changing watermark causes exact rejection `cannot snapshot while matching commands are pending`; no snapshot bytes are published.
  - [ ] Restore remains non-ready until native restore, registry/order reconciliation, and derived-index rebuild all complete; all restore failures escape rather than create a clean core.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: CoreState v6 round-trip restores the matcher before readiness
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreProbeStateTest#roundTripsVersionSixWithNativeMatcherSnapshot test
    Expected: Maven exits 0; manifest fields match the captured adapter, restored state hash/open orders match, and readiness occurs only after reconciliation.
    Evidence: <attemptDir>/task-5-corestate-v6.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: pending matching and corrupt nested snapshot are rejected
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreProbeStateTest#rejectsSnapshotWhileMatchingPending,CoreProbeStateTest#rejectsCorruptMatcherSectionBeforeRestore test
    Expected: Maven exits 0; pending capture returns the exact gate error and corruption prevents adapter construction/readiness.
    Evidence: <attemptDir>/task-5-corestate-v6-error.txt
  ```

  Commit: YES | Message: `feat(snapshot): embed native matcher state in core snapshot` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreStateSnapshotCodec.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreSnapshotManifest.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/matching/MatcherSnapshotCodecTest.java`]

- [ ] 6. Remove duplicate FIFO state and all production rebuild/retry recovery

  What to do: Advance `TradingStateSnapshotCodec` to v19 and remove `CoreBookState` from `TradingCoreState`, all canonical/auxiliary constructors, validation, lineage/full hash, rolling hash aggregates, and codec bytes. Delete `CoreBookState` and unused `CoreBookOrder`. Update all constructor call sites returned by `rg -n 'new TradingCoreState\(' surprising-aeron-core/surprising-aeron-service/src/main surprising-aeron-core/surprising-aeron-service/src/test` (currently 44 by task context) and reject v18 explicitly. Refactor `TradingCoreReducer` so place/cancel/match/lifecycle operations update only `CoreOrderState`, users, treasury, positions, and reservations. An order is active iff `status == OPEN`; `ActiveOrderIndex` is rebuilt/updated from that status. Resting remainders stay OPEN, terminal orders release reservations, and no reducer assigns FIFO priority.

  Delete adapter `rebuildAsync`/`rebuildUnlanedAsync` and `PlaceRequest`; delete runtime matcher generations/rebuild; simplify `PendingMatching` to runtime-only sequence, operation, command, and deadline; remove retry counters, persisted pending restore, `pendingMatchingOrderIds`, recovery scheduling, resubmission, and resume methods. Update `SurprisingClusteredService.onStart` in the same atomic task so it no longer calls removed pending-resume APIs and so restore admission remains closed until runtime readiness. Classify only documented deterministic engine rejections (including post-only failure) as normal reducer results. Any asynchronous exception, timeout, null/invalid result, cancel/move partial failure, unknown symbol/order, stale callback, or reducer/matcher mismatch throws `FatalMatchingDivergenceException`; do not convert it to a retryable command result. Rewrite `CoreMatchingStateTest` as a black-box exchange-core test for FIFO/partial/GTX/snapshot behavior with no Java priority sequence.
  Must NOT do: Do not derive FIFO from order ID/time, retain a hidden priority map, recover by replaying OPEN orders, catch fatal divergence and continue, or broaden normal rejection classification without a named test.

  Parallelization: Can parallel: NO | Wave 5 | Blocks: [7] | Blocked by: [5]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:10-23` - record field list containing the duplicate book.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:73-88` - book validation to delete.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:461-477` - FIFO/book full hash to delete.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:534-556` - lineage contribution to remove.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java:6-57` - duplicate priority map to delete.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookOrder.java:5-20` - unused priority-bearing state to delete.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingStateSnapshotCodec.java:22-24` - current v18 version.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingStateSnapshotCodec.java:100-106` - book serialization to remove.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingStateSnapshotCodec.java:361-368` - book decode to remove.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/RollingBusinessStateHash.java:10-39` - rolling book aggregates.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreReducer.java:638-844` - cancel/reject/match book mutations to replace with order lifecycle only.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreReducer.java:1877-1918` - lifecycle cancel path already consulting `ActiveOrderIndex` but mutating book.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/index/ActiveOrderIndex.java:100-127` - canonical `status == OPEN` derivation.
  - API/Type: `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreOrderState.java:11-54` - authoritative order lifecycle fields.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1311-1365` - timeout/hash and retry path.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java:1794-1878` - production rebuild/retry/resume block to delete.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreMatchingStateTest.java:53-139` - FIFO/partial/GTX expectations to retain through native matcher assertions.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/TradingStateSnapshotCodecTest.java` - codec round-trip/version fixture.

  Acceptance criteria (agent-executable only):
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreMatchingStateTest,TradingStateSnapshotCodecTest,CoreProbeStateTest,DeterministicExchangeCoreAdapterTest,ActiveOrderIndexTest test'` passes.
  - [ ] `test -z "$(rg -n 'CoreBookState|CoreBookOrder|prioritySequence|priorityOrder\(|nextPrioritySequence|rebuildAsync|rebuildUnlanedAsync|rebuildMatcherAsync|scheduleMatchingRecovery|scheduleMatcherRecovery|resumePendingMatching' surprising-aeron-core/surprising-aeron-service/src/main || true)"` passes.
  - [ ] `rg -n 'VERSION = 19' surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingStateSnapshotCodec.java` matches, and a v18 fixture is rejected with the asserted unsupported-version message.
  - [ ] Parameterized reducer tests prove OPEN/terminal transitions, reservations, balances, positions, treasury, and rolling/full-hash equality without any book aggregate.
  - [ ] Fatal-path tests prove timeout, async exception, malformed result, cancel mismatch, and stale callback escape as `FatalMatchingDivergenceException`; expected post-only rejection remains deterministic and nonfatal.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: active lifecycle and native FIFO work without duplicate book state
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreMatchingStateTest#preservesFifoAcrossNativeSnapshotWithoutCoreBookState,ActiveOrderIndexTest test
    Expected: Maven exits 0; FIFO/partial behavior is observed through exchange-core, while active membership is exactly the OPEN-status set.
    Evidence: <attemptDir>/task-6-single-book.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: divergence is fatal and cannot schedule recovery
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreProbeStateTest#failsClosedWithoutRetryOnMatcherDivergence test
    Expected: Maven exits 0; one fatal exception escapes, no retry/rebuild command is submitted, and no later command is accepted on that state instance.
    Evidence: <attemptDir>/task-6-single-book-error.txt
  ```

  Commit: YES | Message: `refactor(core): make exchange core the only order book` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookState.java` (delete), `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/CoreBookOrder.java` (delete), `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingStateSnapshotCodec.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/RollingBusinessStateHash.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreReducer.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/PendingMatching.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/TradingCoreRuntime.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java`, `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java`, all exact Task 6 `TradingCoreState` constructor call-site files, and their directly corresponding test files]

- [ ] 7. Enforce cluster fail-closed behavior and complete six-line recovery validation

  What to do: Update `SurprisingClusteredService` so startup does not resume pending matching, command/query admission remains closed until snapshot decode, native matcher restore, reconciliation, and index rebuild succeed, and `onTakeSnapshot` publishes only the completed fenced v6 bytes. Snapshot capture/decode/restore failures and `FatalMatchingDivergenceException` must escape the Aeron callback so the member terminates/fails leadership; do not catch and continue. Keep timer scheduling only for live pending operations, with no recovery generation. Add service tests for capture backpressure, timeout, leadership-loss/stale callback, corrupted follower restore, and snapshot plus subsequent Aeron-log replay. Add a parameterized six-product-line test that starts one line per invocation and covers snapshot/restore, post-snapshot replay, same-price FIFO/partial/GTX, cancel and applicable IOC/FOK/trigger/liquidation/settlement/expiry boundaries. For every funds/position-affecting case, compare user and market-maker opening funds, adjustments, trades, fees, funding/liquidation/settlement/option flows as applicable, treasury, positions, and closing funds.

  Update root and Aeron README text to state the completed single-book architecture, exact release/SHA, v6/v19 rejection, fresh-cluster pre-launch cutover, and fail-closed recovery. Rollout choice is a coordinated fresh-cluster reset because snapshots are unshipped: automation must abort if existing state is detected and must never delete it. Retain the old binary and v5/v18 snapshot/archive externally for diagnosis. Binary rollback is allowed only before accepting commands on v6; after v6 accepts commands, roll forward with the exact pinned artifact/snapshot pair rather than loading v6 in the old binary. A failed partial rollout is stopped, not mixed. Record tested and untested scopes. Run the affected module suite, `git diff --check`, commit only task-owned main files, push the branch, and verify the pre-existing dirty path remains unchanged.
  Must NOT do: Do not swallow fatal callback exceptions, auto-delete Aeron state, mix old/new members, start wallet, start multiple product lines together, claim full environment coverage if unavailable, or document removed `scripts/` paths.

  Parallelization: Can parallel: NO | Wave 6 | Blocks: [F1, F2, F3, F4] | Blocked by: [6]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:44-57` - current startup and pending resume.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:101-114` - current synchronous snapshot publication.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:170-180` - timer completion boundary.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:201-218` - snapshot load and state replacement.
  - Pattern:  `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java:267-281` - pending timer scheduling and current catch boundary.
  - Pattern:  `README.md:21-31` - current duplicate-book/rebuild status and ownership statement to update.
  - Pattern:  `README.md:104` - current fork version.
  - Pattern:  `README.md:153-154` - currently missing fork SHA/manifest/registry readiness.
  - Pattern:  `surprising-aeron-core/README.md:8-11` - current duplicate book/rebuild description.
  - Pattern:  `surprising-aeron-core/README.md:50-59` - target adapter and current retry/barrier status.
  - Test:     `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreProbeStateTest.java` - command, restore, funds, and failure fixtures.
  - External: `https://github.com/lilaizhencn/exchange-core` - fork provenance link for release documentation.

  Acceptance criteria (agent-executable only):
  - [ ] `zsh -lc 'task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am test'` passes without wallet.
  - [ ] A six-value `ProductLine` parameterized restore/replay suite passes one invocation at a time and records user/market-maker funds, positions, treasury, active orders, matcher reports, and hashes before capture, after restore, and after replay.
  - [ ] Service tests prove capture is not published while pending/backpressured, corrupted restore never admits commands/queries, fatal matcher errors escape callbacks, and no retry/rebuild/resume method is invoked.
  - [ ] `git diff --check` passes; `rg -n '0\.5\.9-emporia|CoreState v6|TradingState v19|fromSnapshotOnly|fail.closed|失败关闭' README.md surprising-aeron-core/README.md` reports the completed contract; neither README references a deleted `scripts/` path.
  - [ ] Original-worktree branch/HEAD, `git status --short`, staged/unstaged binary patches, untracked-file SHA-256 manifest, and index stages exactly match the preflight evidence; no implementation commit contains `WebSocketRuntimeHints.java` or any unrelated baseline path.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: all six isolated product lines survive snapshot plus log replay
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreNativeSnapshotProductLineTest test
    Expected: Maven exits 0; each isolated line restores exact orders/funds/positions/treasury, preserves native FIFO, and applies post-snapshot commands exactly once.
    Evidence: <attemptDir>/task-7-six-line-recovery.txt   (attemptDir = currentAttemptDir from `omo ulw-loop status --json`, .omo/evidence/ulw/<session>/<goalId>/a<attempt>)

  Scenario: corrupt follower restore and fatal runtime divergence stop the member
    Tool:     bash
    Steps:    cd "$W1_W2_WORKTREE" && task_java_home=$(/usr/libexec/java_home -v 25) && JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SurprisingClusteredServiceTest#doesNotAdmitAfterCorruptSnapshot,SurprisingClusteredServiceTest#propagatesFatalMatcherDivergence test
    Expected: Maven exits 0; both fixtures terminate the service callback path, publish no successful snapshot/result, and accept no subsequent command.
    Evidence: <attemptDir>/task-7-six-line-recovery-error.txt
  ```

  Commit: YES | Message: `feat(core): enforce fail-closed snapshot recovery` | Files: [`surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/SurprisingClusteredServiceTest.java`, `surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/CoreNativeSnapshotProductLineTest.java`, `README.md`, `surprising-aeron-core/README.md`]

## Final verification wave (MANDATORY - after all implementation tasks)
> Runs in PARALLEL. ALL must APPROVE. Surface results to the caller and wait for an explicit "okay" before declaring complete.
- [ ] F1. Plan compliance audit - every task done, every acceptance criterion met
- [ ] F2. Code quality review - diagnostics clean, idioms match, no dead code
- [ ] F3. Real manual QA - every QA scenario executed with evidence captured
- [ ] F4. Scope fidelity - nothing extra shipped beyond Must-Have, nothing Must-NOT-Have introduced

F1 must additionally prove the dependency matrix was followed, both repository SHAs/versions are recorded, every task commit contains only its declared files, and the dirty main baseline is preserved. F2 must run JDK 25 compilation/tests, inspect all `TradingCoreState` constructor sites, validate bounded parsing and defensive copies, and prove there is no disk/journal/hot-path I/O. F3 must rerun the fork native restore, main v6/v19 round-trip, barrier races, six-line conservation, and fatal service scenarios from fresh JVMs. F4 must run source searches for duplicate FIFO/rebuild/retry/fallback/sharding and inspect the final dependency tree. If any reviewer rejects, fix in a new atomic commit, rerun all four reviewers in parallel, and do not declare completion until the caller explicitly says `okay`.

## Commit strategy
- One logical change per commit. Conventional Commits (`<type>(<scope>): <subject>` body + footer).
- Atomic: every commit builds and passes tests on its own.
- No "WIP" / "fix typo squash later" commits on the final branch - clean up before merge.
- Reference the plan file path in the final commit footer: `Plan: .omo/plans/w1-w2-single-book-snapshot.md`.
- Fork checkpoints: push Task 1, then Task 2; record the Task 2 release SHA and never republish `0.5.9-emporia` from different source. Main checkpoints: after every passing task, use exact pathspecs in the isolated worktree, inspect `git show --stat --oneline HEAD`, and push `codex/w1-w2-single-book-snapshot`; do not advance or mutate dirty `codex/aeron-unified-core` before F1-F4 and caller approval.
- Rollback: never reset/stash/clean either repository. Before v6 accepts commands, revert main commits in reverse order with new revert commits, redeploy the old binary, and use only its retained v5/v18 snapshot/archive. After v6 accepts commands, old binaries are binary-incompatible: stop the rollout, preserve Aeron data, and roll forward with the exact pinned `0.5.9-emporia` SHA and a v6/v19 snapshot; do not load v6 in old code or auto-delete state. Revert fork Task 2 then Task 1 only after no main build references `0.5.9-emporia`.
- Dirty-worktree checkpoint: relevant WIP is reconciled only from the preserved baseline copy into the isolated branch; the original files remain untouched. If post-baseline concurrent edits appear, refresh evidence and port compatible hunks only after proving no existing hunk is dropped; the gateway path remains out of scope.

## Success criteria
- All Must-Have shipped; all QA scenarios pass with captured evidence; F1-F4 approved; commit history clean.
- Fork is clean and pushed at a recorded release SHA; main resolves only that `0.5.9-emporia` artifact and preserves all pre-existing dirty-worktree state.
- CoreState v6 contains exactly one canonical matcher section with ME0/RE0, provenance, configuration/registry/module checksums, and no pending commands; TradingState v19 contains no book/FIFO state.
- Every restore uses `fromSnapshotOnly`, reconciles exact OPEN orders before readiness, and never replays live orders into a clean matcher.
- Unexpected matcher/Core divergence stops the member; no production rebuild, retry, fallback, hidden FIFO, journaling, hot-symbol sharding, or hot-path external service remains.
