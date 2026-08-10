# Production Gate Review — `bac65f16`

## Recommendation

**PASS / APPROVE**

- `recommendation`: `APPROVE`
- `blockers`: none
- Exact reviewed commit: `bac65f16b03348feb08cadf19a09d4716f6a6bc2`
- Parent: `221927f44588f7d6605c46c9567bf6733f179a18`
- Review date: 2026-08-06 (Asia/Shanghai)

## Original intent

Approve or block the exact corrected commit that closes the previously identified Binance mixed raw-query/form-body signature gap. The production gate requires exact-SHA verification, a complete 209-test gateway result, cryptographic coverage of raw query plus form body, body propagation to sensitive handlers, preservation of timestamp/`recvWindow` and client-IP allowlist invariants, and a clean, narrowly scoped commit.

## Desired outcome

Every request value consumed by Binance-compatible sensitive endpoints is bound to the API-key HMAC even when parameters are split between the raw query and an `application/x-www-form-urlencoded` body. Tampering with the body after signing must fail authentication, while existing timestamp-window, permission, IP allowlist, constant-time comparison, and post-success key-use behavior remain intact.

## User outcome review

The exact commit satisfies the requested production outcome. `GatewayApiKeyService.authenticate(request, permission, body)` constructs its HMAC payload from the raw query and raw form body, excluding the exact `signature` key from each. The controller now propagates the request body into authentication for order, transfer, withdrawal, and account flows. The regression test signs one form body and supplies a changed body to `authenticate`; it receives `invalid api signature`. No stated criterion is contradicted by the implementation, diff, or reproduced test evidence.

## Checks

| Criterion | Result | Reproduced evidence |
|---|---|---|
| C1 — exact corrected SHA | PASS | `git rev-parse HEAD` and `git rev-parse bac65f16...^{commit}` returned exactly `bac65f16b03348feb08cadf19a09d4716f6a6bc2`. The exact commit was independently exported with `git archive` to `/tmp/surprising-gate-bac65f16.vmVU0e` for the clean test run. The four reviewed source/test paths in the main worktree matched the commit (`git diff --quiet <SHA> -- <paths>` exit 0). |
| C2 — full 209-test gateway evidence | PASS with disclosed conditional skips | In the exact-SHA export, `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT=10000 mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am clean test` exited 0 with `BUILD SUCCESS`. Gateway provider result: **209 tests, 0 failures, 0 errors, 22 skipped**. The 22 skips are the unchanged `CustodyWithdrawalReconciliationPostgresTest` cases gated on an external PostgreSQL integration database. The upstream product API also passed 18/18. |
| C3 — Binance raw query plus form-body signature coverage | PASS | `binanceCanonicalQuery(request, body)` preserves the raw query and raw body, removes exact-key `signature`, and combines both nonblank sources. `GatewayApiKeyServiceTest.combinesBinanceQueryAndFormBodyForSigning` passed and expects `timestamp=123&symbol=BTCUSDT&side=BUY`. `GatewayApiKeyAuthenticationIpTest.rejectsSignedQueryWhenFormBodyIsChanged` passed through the full authentication boundary and proves a changed body is rejected. The deterministic no-raw-query fallback test also passed. |
| C4 — body propagation on sensitive endpoints | PASS | `BinanceApiController` passes `body` into authentication for asset transfer (`TRADE`, line 155), withdrawal apply (`WITHDRAW`, line 244), order (`READ` for GET/DELETE, otherwise `TRADE`, line 317), and account (`READ`, line 110). Withdrawal parsing also changed from `parameters(request, null)` to `parameters(request, body)`. The shared controller helper forwards the body only for API-key authentication and leaves bearer authentication semantics unchanged. |
| C5 — timestamp and `recvWindow` invariants | PASS | The commit leaves enforcement unchanged at `GatewayApiKeyService` lines 107–114: timestamp remains mandatory and numeric; `recvWindow` defaults to 5000 ms, must be 1–60000 ms, and stale/future requests outside the effective window are rejected before signature acceptance. The complete gateway suite passed. |
| C6 — IP allowlist and authorization invariants | PASS | API-key lookup, trusted client-IP resolution/allowlist enforcement, required permission, timestamp window, signature presence, HMAC-SHA256, `MessageDigest.isEqual`, and `markUsed` only after successful verification remain in the same fail-closed order at service lines 102–126. `GatewayApiKeyAuthenticationIpTest` ran 3/3, including allowed and rejected forwarded-client scenarios plus form-body tampering; `ClientIpResolverTest` ran 3/3 and `AdminIpWhitelistFilterTest` ran 6/6. |
| C7 — clean commit scope | PASS | `git diff-tree --no-commit-id --name-status -r <SHA>` lists exactly four modified files: two directly related production classes and two directly related test classes, totaling 82 insertions and 12 deletions. No dependency, configuration, migration, generated, documentation, or unrelated business changes are included. `git diff <SHA>^ <SHA> --check` exited 0. Existing dirty docs/scripts and untracked `.omo` artifacts are outside the commit. |
| C8 — programming and remove-ai-slops review | PASS | Direct review found no deletion-only test, requested-removal assertion, tautological output-vs-output comparison, prose pin, implementation-mirroring expected-value derivation, needless parser/normalizer, speculative abstraction, dead code, broad error suppression, or unrelated scope drift. The body-tampering test is a meaningful security regression test: reverting body inclusion causes it to fail. The no-query fallback test covers a previously documented evidence gap. The overload keeps unchanged callers compatible without duplicating authentication logic. |

## Commit scope

Exact files in `bac65f16b03348feb08cadf19a09d4716f6a6bc2`:

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`

## Slop / overfit and maintenance pass

- `combinesBinanceQueryAndFormBodyForSigning` pins the externally required HMAC payload contract; it does not recompute expected output with production code.
- `rejectsSignedQueryWhenFormBodyIsChanged` exercises repository lookup, IP/permission/timestamp checks, decryption, canonicalization, HMAC comparison, and rejection. It is neither tautological nor a test that merely verifies code deletion.
- `usesDeterministicParametersWhenRawQueryIsAbsent` protects a real fallback branch identified as uncovered in the parent review.
- The new authentication overload centralizes the changed boundary and avoids per-handler reimplementation. No unnecessary production extraction, generic parsing framework, normalization layer, or dependency was introduced.
- The exact corrected commit has no dedicated code-review report. The parent report `.omo/evidence/221927f4-code-review.md` explicitly applies `remove-ai-slops` and `programming` and identifies the mixed query/form defect fixed here, but it is not treated as coverage for this new SHA. This gate therefore performed the same skill-perspective and overfit/slop pass directly over the corrected diff, full changed files, and tests.

## Residual risks and exact evidence gaps

1. The 22 PostgreSQL reconciliation integration cases did not execute because no integration database URL was supplied. They are unrelated to the changed API-key canonicalization/controller path and do not violate the stated 209-test criterion, whose reproduced result explicitly includes 22 conditional skips.
2. There is no real-socket/embedded-container test that sends a mixed query plus form body through Spring MVC into `BinanceApiController`. Coverage instead combines direct controller flow inspection, a canonical-payload unit test, and a full `GatewayApiKeyService.authenticate` tamper-rejection test. This is a non-blocking residual evidence gap because the stated body-propagation criterion is directly established in production code and the authentication boundary behavior is executed.
3. The regression test proves changed form data is rejected, but does not separately execute an accepted mixed query/form request through the controller. The canonical payload expectation and full authentication rejection distinguish the corrected behavior; no stated criterion requires a live endpoint acceptance scenario.
4. No exact-SHA executor report, exact-SHA code-review report, manual-QA matrix, or notepad path was found. The exact commit, diff, complete source/test artifacts, parent defect report, clean exported test run, and generated Surefire XML were inspected directly. This is documented as an evidence gap, not a blocker, because direct reproduction supports every stated success criterion.
5. Maven reports pre-existing deprecation/unchecked warnings and Mockito dynamic-agent warnings. They are outside this commit and do not fail any stated gate criterion.

## Checked artifact paths

- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/221927f4-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/221927f4-gate-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`
- `/tmp/surprising-gate-bac65f16.vmVU0e/surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`

## Final decision

**PASS / APPROVE** exact commit `bac65f16b03348feb08cadf19a09d4716f6a6bc2`. No blockers.
