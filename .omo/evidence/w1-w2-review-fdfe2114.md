# W1/W2 final evidence ledger

- Main SHA: `fdfe21140ab622f857e94cb20cf84a2819545134`
- Fork SHA: `310235eadea617fb9a893cd65cd1fb9eef1cb923`
- Fork artifact: `exchange.core2:exchange-core:0.5.14-emporia`
- Fork whole-JAR SHA-256: `16a55192a9f6df85e396fefadbfe23d7d354dc729609beecccf5a8eed09ded27`

## Verification

- Fork full JDK 25 suite: PASS, 302 tests, 0 failures/errors/skips.
- Fork reproducibility: PASS, two clean builds produced the exact whole-JAR SHA above.
- Fork provenance: PASS, embedded exact fork SHA and `fork.git.dirty=false`.
- Fork dirty-source gate: PASS, tracked dirty source was rejected before artifact production.
- Main affected JDK 25 reactor: PASS, 193 tests, 0 failures/errors/skips.
- Main dependency gate: PASS for exact JAR; wrong whole dependency-JAR SHA and wrong embedded fork SHA were independently rejected.
- LSP diagnostics for changed Java: PASS, no diagnostics.
- Debugging runtime audit: PASS on exact main SHA `fdfe21140ab622f857e94cb20cf84a2819545134`.
  The 13 executed cases cover all six ProductLine FIFO restores, native snapshot round-trip, exact OPEN metadata divergence,
  oversized/corrupt snapshot rejection, fatal divergence propagation, Aeron timer backpressure retry, and active-order lifecycle indexing.

## Final review lanes

- Goal and constraints: PENDING
- QA execution: PENDING
- Code quality: PENDING
- Security and reliability: PENDING
- Context mining: PENDING
