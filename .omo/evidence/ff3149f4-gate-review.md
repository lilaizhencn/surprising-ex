# Production Gate Review — `ff3149f4`

## Recommendation

**PASS / APPROVE**

- `recommendation`: `APPROVE`
- `blockers`: none
- Exact reviewed SHA: `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`
- Parent SHA: `bac65f16b03348feb08cadf19a09d4716f6a6bc2`
- Review date: 2026-08-06 (Asia/Shanghai)

## Original intent

Ship the exact corrective commit that makes mixed Binance query-string/form-body HMAC verification follow the official no-separator concatenation protocol. The production gate requires exact-SHA verification, the full 209-test gateway result, body binding and propagation across order, transfer, withdrawal, and account API-key authentication, preservation of timestamp/`recvWindow` and trusted client-IP allowlist controls, and a clean, narrowly scoped commit.

## Desired outcome

A Binance-compatible client can sign the raw query string directly concatenated with the form body, without an inserted separator, and authenticate successfully. Changing the body after signing must fail. All sensitive handlers must pass the original request body into API-key authentication, while timestamp-window, permission, IP allowlist, constant-time HMAC comparison, and post-success key-use behavior remain fail-closed.

## User outcome review

The exact commit satisfies the requested production outcome. `GatewayApiKeyService.binanceCanonicalQuery(request, body)` now combines non-empty query and form-body payloads as `query + requestBody`. This matches Binance's official statement that the signature payload is the query string concatenated **without separator** to the HTTP body. The changed authentication test first accepts a correctly signed mixed query/body request and then rejects a modified body with `invalid api signature`. The exact artifact retains body propagation from `BinanceApiController` for order, transfer, withdrawal, and account flows. No stated criterion fails.

## Checks

| Criterion | Result | Reproduced evidence |
|---|---|---|
| C1 — exact SHA | PASS | `git rev-parse HEAD` returned exactly `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`; `git cat-file -t` returned `commit`; `git show` identified parent `bac65f16b03348feb08cadf19a09d4716f6a6bc2`. Tests ran from an independent `git archive` export at `/tmp/surprising-ff3149f4.BvaJ8n`. |
| C2 — full 209-test gateway suite | PASS with disclosed conditional skips | `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT=10000 mvn -f /tmp/surprising-ff3149f4.BvaJ8n/pom.xml -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am clean test` produced gateway-provider **209 tests, 0 failures, 0 errors, 22 skipped** and reactor `BUILD SUCCESS`. Independent aggregation of 37 Surefire XML files produced the same totals. Product API also passed 18/18. The 22 skips are all external-PostgreSQL-conditioned `CustodyWithdrawalReconciliationPostgresTest` cases. |
| C3 — official Binance concatenation semantics | PASS | Official Binance Spot REST documentation states that the signature payload is the query string concatenated without separator to the HTTP body: <https://developers.binance.com/en/docs/products/spot/rest-api>. Production now returns `query + requestBody`; `GatewayApiKeyServiceTest.combinesBinanceQueryAndFormBodyForSigning` independently expects `timestamp=123symbol=BTCUSDT&side=BUY`. |
| C4 — cryptographic mixed-body behavior | PASS | `GatewayApiKeyAuthenticationIpTest.rejectsSignedQueryWhenFormBodyIsChanged` signs `query + signedBody`, successfully authenticates the unchanged body, then verifies that an ETH-substituted body is rejected as `invalid api signature`. This executes repository lookup, secret decryption, canonicalization, HMAC, and constant-time comparison rather than only testing a helper. |
| C5 — body propagation across sensitive auth | PASS | Exact-SHA `BinanceApiController` passes the original `byte[] body` to `authenticate` for account (`READ`), asset transfer (`TRADE`), withdrawal apply (`WITHDRAW`), and order (`READ` for GET/DELETE, otherwise `TRADE`). The shared helper forwards that body to `apiKeyService.authenticate(request, permission, body)` for API-key requests. |
| C6 — timestamp and `recvWindow` invariants | PASS | The commit changes only mixed-payload concatenation. `timestamp` remains mandatory and parsed as `long`; absent `recvWindow` defaults to 5000 ms; accepted `recvWindow` remains 1–60000 ms; out-of-window requests are rejected before signature success. Full gateway tests passed. |
| C7 — IP allowlist/auth invariants | PASS | Active-key lookup, trusted-proxy client-IP resolution, API-key IP allowlist, permission check, timestamp window, required signature, HMAC-SHA256, `MessageDigest.isEqual`, and `markUsed` after successful verification remain unchanged. Relevant suite results: `GatewayApiKeyAuthenticationIpTest` 3/3, `ClientIpResolverTest` 3/3, and `AdminIpWhitelistFilterTest` 6/6. |
| C8 — clean scope | PASS | `git diff-tree` lists exactly three modified files: one production service and two directly related tests. Diff size is 5 insertions/3 deletions; `git diff --check` is clean. No dependency, configuration, migration, generated artifact, controller rewrite, or unrelated business change is in the commit. Existing dirty docs/scripts and untracked `.omo` files are outside this commit and outside the tested Java source inputs. |
| C9 — `remove-ai-slops` / `programming` gate | PASS | Direct review found no deletion-only/requested-removal tests, tautological expected-value derivation, implementation-produced expected HMAC, prose pins, needless production extraction, new parser/normalizer, dead code, broad error suppression, or scope drift. The helper assertion pins an external protocol contract, and the boundary test contains both positive acceptance and tamper rejection. The available parent code-review report explicitly applied both skill perspectives and identified the separator defect; this gate independently repeated the pass on the correcting diff and exact production/test artifacts. |

## Commit scope

Exact files changed by `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`:

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`

## Slop, overfit, and maintenance review

- The canonicalizer unit test uses a fixed, externally specified no-separator payload; it does not derive expected output by calling production code.
- The authentication test proves both acceptance and rejection. It would fail if the separator were restored, body binding were removed, or body tampering were ignored.
- The test is not a deletion assertion, requested-removal pin, snapshot, prose pin, or mock-return tautology. Mocking is limited to the repository boundary; real canonicalization, secret encryption/decryption, and HMAC comparison execute.
- The production change is one operator-level correction at the shared signing seam. It introduces no abstraction, normalization layer, dependency, or duplicated per-handler fix.
- No maintenance burden or false-confidence issue introduced by this three-file diff violates a stated success criterion.

## Residual risks and exact evidence gaps

1. Twenty-two PostgreSQL reconciliation integration cases were discovered but skipped because `SURPRISING_WITHDRAWAL_IT_DATABASE_URL` was not supplied. They are unrelated to API-key canonicalization and do not violate the explicitly requested 209-test result, which records all 209 discovered tests and the skip count.
2. There is no exact-SHA Spring MVC/live-socket scenario sending a mixed query/form request through each of the four handlers. Body propagation is established by direct exact-SHA production-code inspection, while acceptance and tamper rejection are executed at the API-key authentication boundary. This is a non-blocking evidence gap because no stated criterion requires a live transport test.
3. No executor report, manual-QA matrix, notepad path, or dedicated code-review report for `ff3149f4` was found. The parent report `.omo/evidence/bac65f16-code-review.md` explicitly includes the required skill-perspective coverage and identifies the exact separator defect corrected here. This gate did not trust that report as proof: it inspected the exact diff/artifacts, checked official documentation, and independently reproduced the full suite.
4. The full build emitted pre-existing Java deprecation/unchecked warnings and Mockito's dynamic-agent warning. They are outside this commit and do not fail a stated criterion.
5. The working tree contains unrelated modified/untracked documentation, scripts, and evidence. They are not in the exact commit and did not enter the temporary exact-SHA build. The commit itself is cleanly scoped; the repository working tree is not globally clean.

## Checked artifact paths

- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/bac65f16-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/bac65f16-gate-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/ClientIpResolver.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/ClientIpResolverTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/AdminIpWhitelistFilterTest.java`
- `/tmp/surprising-ff3149f4.BvaJ8n/surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`
- Binance official Spot REST documentation: <https://developers.binance.com/en/docs/products/spot/rest-api>

## Final decision

**PASS / APPROVE** exact commit `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`. No blockers.
