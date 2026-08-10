# Production Gate Review — 221927f4

## Recommendation

**PASS / APPROVE**

- `recommendation`: `APPROVE`
- `blockers`: none
- Exact reviewed commit: `221927f44588f7d6605c46c9567bf6733f179a18`
- Parent: `9a20d2f2e85921476ea7c9c8142ef48ca89d57c5`
- Review time: 2026-08-06 (Asia/Shanghai)

## Original intent

Ship the exact commit that makes API-key authentication compatible with Binance-style signatures over the raw query payload, while retaining timestamp/`recvWindow`, permission, trusted-client-IP allowlist, API-key, and constant-time signature security checks. The production gate also requires the complete gateway test result and a clean, narrowly scoped commit.

## Desired outcome

A Binance-compatible client can sign the original query parameter order and percent encoding (excluding the `signature` field itself), and the gateway accepts that signature without weakening the existing authentication and authorization invariants or introducing unrelated changes.

## User outcome review

The shipped artifact satisfies the requested outcome. `GatewayApiKeyService.authenticate` now signs `binanceCanonicalQuery(request)`. When the servlet exposes a nonblank raw query, that method preserves the raw order and encoding and removes only parameters whose exact raw key is `signature`; when no raw query exists, it falls back to the existing parameter-map canonicalization. The HMAC remains SHA-256, the supplied signature is still compared with `MessageDigest.isEqual`, and successful authentication alone marks the key used.

## Checks

| Criterion | Result | Reproduced evidence |
|---|---|---|
| C1 — exact SHA | PASS | `git rev-parse HEAD` returned exactly `221927f44588f7d6605c46c9567bf6733f179a18`; `git cat-file -t` returned `commit`; the commit is an ancestor of `origin/master` (exit 0). |
| C2 — full gateway test evidence | PASS with disclosed conditional skips | `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT=10000 mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am clean test` exited 0 with `BUILD SUCCESS`. Reactor: parent, product-api, gateway, and gateway-provider all SUCCESS. Product API: 18/18 passed. Gateway provider: 206 tests, 0 failures, 0 errors, 22 skipped. The 22 skips are all `CustodyWithdrawalReconciliationPostgresTest` and explicitly report missing `SURPRISING_WITHDRAWAL_IT_DATABASE_URL`; they are unrelated PostgreSQL integration scenarios. |
| C3 — Binance raw-query signature compatibility | PASS | Production code uses the request's raw query string, preserves `timestamp=123&symbol=BTC%2FUSDT`, and removes exact-key `signature=abc`. `GatewayApiKeyServiceTest.preservesBinanceRawQueryOrderAndEncoding` passed. Independent OpenSSL HMAC-SHA256 for secret `secret` and raw payload `timestamp=123&symbol=BTC%2FUSDT` produced `a0c20e165af76d7f8a667dc42111a134f2e4c3d86bf88764abbc1d13f169b9f0`; the existing fixed-vector HMAC test also passed. |
| C4 — timestamp and recvWindow invariants | PASS | The commit does not alter parsing or enforcement: `timestamp` is required and parsed as `long`; absent `recvWindow` defaults to 5000 ms; accepted range remains 1–60000 ms; requests outside the effective window are rejected before signature verification. Full gateway tests passed after supplying the required production test environment value. |
| C5 — IP allowlist invariant | PASS | The commit retains `clientIpResolver.resolve(request)` followed by `requireIpAllowlist` before permission and signature checks. The full authentication tests for a trusted forwarded client and an untrusted peer both passed; `ClientIpResolverTest` and `AdminIpWhitelistFilterTest` also passed. |
| C6 — security invariants | PASS | API-key format/active lookup, IP allowlist, required permission, timestamp window, required signature, HMAC-SHA256, constant-time comparison, and mark-used-after-success ordering are preserved. No secret logging, fallback authentication, permission bypass, or fail-open branch was added. |
| C7 — scope cleanliness | PASS | `git diff-tree --no-commit-id --name-status -r 221927f...` lists exactly three modified files: one production service and two directly related test classes. `git diff 221927f^ 221927f --check` is clean. No dependency, configuration, schema, generated, or unrelated application changes are in the commit. Existing dirty docs/scripts and untracked `.omo`/test-plan artifacts are outside the commit and were not modified by this review. |
| C8 — programming and remove-ai-slops perspectives | PASS | Direct diff pass found no deletion-only/removal assertion, tautological output-vs-itself assertion, implementation-mirroring expected value for the raw-query behavior, speculative abstraction, broad catch addition, duplicated security boundary, dead code, or scope drift. The extracted `canonicalParameters` is used by both the legacy method/path canonicalizer and the no-raw-query fallback, so it is not a single-use helper. The new raw-query test independently pins observable ordering/encoding behavior. |

## Full test reproduction detail

The first unqualified run, `mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am test`, reached all gateway-provider tests but ended with 1 error because `GatewayProductionSecurityConfigurationTest.productionYamlBindsTheFailClosedSecurityBoundary` did not supply the pre-existing required production placeholder `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT`. That property and test predate this commit. Supplying the required value and rerunning with `clean test` produced the successful result above. This environment dependency is recorded rather than hidden.

Surefire artifacts inspected:

- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`
- `TEST-com.surprising.gateway.provider.auth.GatewayApiKeyServiceTest.xml` — 6 passed
- `TEST-com.surprising.gateway.provider.auth.GatewayApiKeyAuthenticationIpTest.xml` — 2 passed
- `TEST-com.surprising.gateway.provider.auth.ClientIpResolverTest.xml` — 3 passed
- `TEST-com.surprising.gateway.provider.config.AdminIpWhitelistFilterTest.xml` — 6 passed
- `TEST-com.surprising.gateway.provider.config.GatewayProductionSecurityConfigurationTest.xml` — 8 passed
- `TEST-com.surprising.gateway.provider.controller.BinanceApiControllerTest.xml` — 11 passed
- `TEST-com.surprising.gateway.provider.service.CustodyWithdrawalReconciliationPostgresTest.xml` — 22 conditionally skipped for missing integration DB URL

## Slop / overfit and maintenance review

- The added compatibility test asserts the externally relevant raw payload rather than reproducing the stream/filter implementation.
- The IP test still drives the complete `authenticate` boundary; changing its signing setup from the obsolete method/path payload to a raw Binance query is necessary fixture maintenance, not a deletion-only or requested-removal test.
- `canonicalQuery` now parses the raw key up to the first `=` before excluding it. This is narrowly required to avoid mistakenly dropping keys merely prefixed with `signature=` semantics and does not normalize attacker-controlled values.
- `GatewayApiKeyService.java` measures 257 nonblank/non-comment lines. This exceeds the consulted skill's 250-line maintenance threshold, but the file was already above the threshold before this 15-line compatibility delta and no stated success criterion requires a refactor. It is therefore a NOTE, not a blocker.
- No exact-SHA code-review report or manual-QA matrix was found. The gate reviewer directly inspected the exact commit, source/tests, prior security review artifacts, and freshly generated Surefire evidence. Under the gate rules, absent reports do not replace or invalidate direct evidence.

## Residual risks / exact evidence gaps

1. The 22 PostgreSQL reconciliation integration scenarios were not executed because `SURPRISING_WITHDRAWAL_IT_DATABASE_URL` is absent. They are outside the changed authentication path; this is not a blocker for the stated commit criteria.
2. There is no socket-level test using a real servlet container and an independently signed Binance request. Coverage composes an independent raw-query expectation, a fixed HMAC vector, and full-boundary signed IP tests. The production flow was also inspected directly. This is a residual coverage gap, not evidence of a failed stated criterion.
3. No new dedicated tests were added for timestamp lower/upper bounds, stale/future timestamps, or every `recvWindow` edge in this commit. Those checks are unchanged and were inspected in production code; the full gateway suite passed.
4. No exact-commit executor report, code-review report, manual-QA matrix, or notepad path was supplied/found. Exact artifacts checked directly are listed below.

## Checked artifact paths

- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/ClientIpResolver.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/ClientIpResolverTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/AdminIpWhitelistFilterTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/GatewayProductionSecurityConfigurationTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/controller/BinanceApiControllerTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/a8d29deb-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/a8d29deb-gate-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/production-gateway-security-baseline-code-review.md`

## Final decision

**PASS / APPROVE** for exact commit `221927f44588f7d6605c46c9567bf6733f179a18`. No stated success criterion is contradicted by the inspected artifact or reproduced evidence.
