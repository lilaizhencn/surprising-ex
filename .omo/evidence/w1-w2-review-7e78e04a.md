# W1/W2 final evidence ledger

- Main SHA: `7e78e04ae4dac16d364117392f960a65a4f4db2d`
- Fork SHA: `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`
- Fork artifact: `exchange.core2:exchange-core:0.5.15-emporia`
- Fork whole-JAR SHA-256: `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`

## Verification

- Fork full JDK 25 suite: PASS, 302 tests, 0 failures/errors/skips.
- Fork reproducibility: PASS, two clean builds produced the exact whole-JAR SHA above.
- Fork provenance: PASS, build inputs came from an immutable archive of the clean commit; the packaged JAR embeds the
  exact fork SHA and `fork.git.dirty=false`; package-phase re-attestation passed.
- Fork packaging adversary: PASS, a tracked-file mutation after initial attestation was excluded from immutable build
  inputs and rejected after JAR creation with `exchange-core source changed while packaging`.
- Main affected JDK 25 reactor: PASS, 193 tests, 0 failures/errors/skips.
- Main dependency gate: PASS for the exact JAR; wrong whole dependency-JAR SHA and wrong embedded fork SHA were
  independently rejected.
- Main order-book projection test: PASS after clean reactor compilation, 4 tests, 0 failures/errors/skips.
- Debugging runtime audit: PASS on exact main SHA `7e78e04ae4dac16d364117392f960a65a4f4db2d`, 13 tests,
  0 failures/errors/skips. Coverage includes all six ProductLine FIFO restores, native round-trip, exact OPEN metadata
  divergence, oversized/corrupt snapshot rejection, fatal divergence propagation, Aeron timer backpressure retry,
  and active-order lifecycle indexing.
- LSP aggregate diagnostics: PASS for the changed Aeron modules; matching projection Java files report no diagnostics.

## Final review lanes

- Goal and constraints | main `7e78e04ae4dac16d364117392f960a65a4f4db2d`, fork
  `627ddf68fbb0594b07e4b59a1a0e3377354e26b9` | PASS | `.omo/evidence/w1-w2-goal-constraint-verification-gate-review.md`
- Code quality | same exact SHAs | PASS | `.omo/evidence/w1-w2-code-review.md`
- Security and reliability | same exact SHAs | PASS | `.omo/evidence/security-reliability-w1w2-gate-review.md`
- Context mining | same exact SHAs | PASS | direct agent report; standalone fork `ProductionSimulation` journaling is
  unreachable from Surprising EX, whose deployed adapter uses `fromSnapshotOnly` and `enableJournaling(false)`.
- Hands-on QA | same exact SHAs | PASS, 105 focused tests | `.omo/evidence/w1-w2-focused-jdk25-20260816/`

## Known unrelated baseline failure

- `KafkaPublicTradePublisherTest` and `KafkaOrderBookDepthPublisherTest`: 7 failures caused by test fixtures leaving
  `ProductLine` null. The same 7 failures reproduce on pre-change main SHA `fdfe21140ab622f857e94cb20cf84a2819545134`;
  no production or test file in those publishers belongs to W1/W2.
