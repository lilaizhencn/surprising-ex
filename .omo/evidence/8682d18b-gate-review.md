# Production Gate Review — `8682d18b`

## Recommendation

**PASS / APPROVE**

- `recommendation`: `APPROVE`
- `blockers`: none
- Exact reviewed commit: `8682d18bb5dd4731c288ad7584849f28074e4e7f`
- Parent: `a8d29debd859b52a72125b203934123123425229`
- Review date: 2026-08-06 (Asia/Shanghai)

## Original intent

Release the exact commit that accepts Binance HMAC-SHA256 signatures regardless of hexadecimal letter case, while preserving Binance's official raw query/body signing payload, constant-time digest comparison, request-body propagation, timestamp/`recvWindow` enforcement, trusted-client-IP allowlisting, and a narrowly scoped commit. The required production evidence is the complete 209-test gateway-provider suite.

## Desired outcome

Uppercase, lowercase, and mixed-case hexadecimal encodings of the same valid HMAC authenticate identically. Authentication must still bind every accepted raw query/body parameter and must fail closed for invalid signatures, stale/invalid timing, disallowed client IPs, and insufficient permission.

## User outcome review

The exact commit satisfies the requested user-visible outcome. It normalizes only the supplied hexadecimal signature with `toLowerCase(Locale.ROOT)` and then compares its UTF-8 bytes against the gateway's lowercase HMAC output using `MessageDigest.isEqual`. It does not normalize the secret or signed payload. The controller passes the same raw `@RequestBody byte[]` into API-key authentication and business parameter handling. Query and body signing follows Binance's official rule: raw query string and HTTP body are concatenated without a separator, with the `signature` field excluded from the canonical payload.

## Checks

| Criterion | Result | Reproduced evidence |
|---|---|---|
| C1 — exact SHA | PASS | `git rev-parse HEAD` returned exactly `8682d18bb5dd4731c288ad7584849f28074e4e7f`; `git cat-file -t` returned `commit`. The isolated test tree was created with `git archive` from that exact object at `/private/tmp/surprising-8682d18b.xkizul`. |
| C2 — full 209-test gateway suite | PASS | In the exact-SHA archive, `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT=10000 mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am clean test` exited 0 with `BUILD SUCCESS`. Gateway provider: **209 tests, 0 failures, 0 errors, 22 skipped**. Independent aggregation of every Surefire XML reproduced the same totals. Product API dependency tests also passed 18/18. |
| C3 — official Binance raw query/body concatenation | PASS | Binance's official Spot REST documentation states that the signature payload is the query string concatenated **without a separator** to the HTTP body and that HMAC signature values are case-insensitive: <https://developers.binance.com/en/docs/products/spot/rest-api>. `GatewayApiKeyService.binanceCanonicalQuery` preserves raw ordering/encoding, removes `signature`, and returns `query + requestBody`; the fixed test expects `timestamp=123symbol=BTCUSDT&side=BUY`, with no inserted `&`. |
| C4 — case-insensitive HMAC plus constant-time comparison | PASS | The one-line production delta lowercases the supplied signature with `Locale.ROOT`, then passes equal-format byte arrays to `MessageDigest.isEqual`. `GatewayApiKeyAuthenticationIpTest.authenticatesUsingForwardedClientIpWhenPeerIsTrusted` submits an uppercase signature through the complete `authenticate` boundary and passed. Lowercase signatures also pass in the body-tamper authentication test; tampered payloads are rejected. |
| C5 — body propagation and binding | PASS | `BinanceApiController.handle` receives `@RequestBody(required = false) byte[] body`; order, transfer, withdrawal, and account routes pass that same byte array to `authenticate(..., body)`. `GatewayApiKeyService.authenticate` includes it in `binanceCanonicalQuery`. `rejectsSignedQueryWhenFormBodyIsChanged` first accepts the signed query+body, then changes only the body and observes `invalid api signature`. |
| C6 — timestamp / `recvWindow` invariant | PASS | Unchanged authentication order requires and parses `timestamp`; defaults absent `recvWindow` to 5000 ms; permits only 1–60000 ms; and rejects requests whose absolute clock skew exceeds the effective window before HMAC acceptance. The exact-SHA complete suite passed. |
| C7 — IP allowlist invariant | PASS | Unchanged code resolves the client through `ClientIpResolver`, applies `requireIpAllowlist` before permission/timing/signature checks, and fails closed when no configured rule matches. Exact-SHA tests passed for trusted-proxy forwarded IP acceptance and untrusted-peer rejection; `ClientIpResolverTest` and `AdminIpWhitelistFilterTest` also passed. |
| C8 — clean scope | PASS | Commit diff contains exactly two modified files: `GatewayApiKeyService.java` and `GatewayApiKeyAuthenticationIpTest.java`. `git diff ... --check` is clean. The production delta is one line and the test delta is directly related. No dependency, configuration, schema, generated artifact, or unrelated runtime change is included. Existing worktree documentation/script changes are outside the commit and outside the isolated archive. |
| C9 — programming / remove-ai-slops perspective | PASS | Direct review found no deletion-only test, requested-removal assertion, tautological expected value, implementation-mirroring HMAC computation, speculative extraction, new parsing/normalization layer, dead code, broad catch, or scope drift. The uppercase fixture is necessary boundary coverage and uses the production authenticator, while the expected acceptance is independently defined by Binance's protocol. No excessive or useless tests were added. |

## Full suite evidence

- Exact-SHA archive: `/private/tmp/surprising-8682d18b.xkizul`
- Command: `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT=10000 mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am clean test`
- Reactor result: parent SUCCESS; product API SUCCESS; gateway parent SUCCESS; gateway provider SUCCESS.
- Gateway Surefire directory: `/private/tmp/surprising-8682d18b.xkizul/surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`
- Gateway XML aggregate: `tests=209 failures=0 errors=0 skipped=22`.
- Relevant exact-SHA reports: `GatewayApiKeyServiceTest` 8/8 passed; `GatewayApiKeyAuthenticationIpTest` 3/3 passed; `ClientIpResolverTest` 3/3 passed; `AdminIpWhitelistFilterTest` 6/6 passed; `GatewayProductionSecurityConfigurationTest` 8/8 passed; `BinanceApiControllerTest` 11/11 passed.

## Slop, overfit, and maintenance review

- The commit does not add a standalone test merely asserting a requested deletion or source token. It extends an existing full-boundary authentication fixture to send a valid uppercase HMAC.
- The test does not derive the expected HMAC acceptance from a duplicate case-insensitive comparator. It submits the real HMAC in another valid hexadecimal representation and observes production authentication succeed.
- No production helper, abstraction, parser, or compatibility shim was introduced. `Locale.ROOT` prevents locale-dependent case conversion, and `MessageDigest.isEqual` remains the comparison primitive.
- The uppercase assertion shares the trusted-proxy acceptance scenario, so a failure could reflect either signature handling or proxy resolution. This is weaker diagnosis than a dedicated test but still exercises both explicit invariants and is not false-positive/tautological coverage. It does not violate a stated criterion.
- The exact-SHA code-review report/manual-QA matrix requested by the generic gate protocol was not found. Direct inspection and exact-SHA reproduction cover the requested criteria; their absence is an evidence note, not a stated-criterion failure.

## Residual risks and exact evidence gaps

1. `CustodyWithdrawalReconciliationPostgresTest` contributed all 22 conditional skips because `SURPRISING_WITHDRAWAL_IT_DATABASE_URL` was unavailable. The complete 209-test suite was discovered and run, but 187 gateway tests executed. The skipped PostgreSQL reconciliation cases are unrelated to this two-file authentication change, so this is a disclosed non-blocking residual risk.
2. There is no dedicated test for a mixed-case signature containing both uppercase and lowercase A–F; the implementation lowercases the entire supplied value with `Locale.ROOT`, and full-uppercase plus lowercase acceptance establish the behavior. This is not evidence of a criterion failure.
3. The service rejects non-hex or wrong-length values through digest mismatch rather than pre-validating a 64-character hex grammar. This behavior predates the commit, remains fail-closed, and is outside the requested criteria.
4. The timestamp check uses absolute skew, whereas current Binance documentation describes an asymmetric future-time allowance and a second server-time check. The requested invariant was preservation, not exact timing-semantic parity; this commit does not change it.
5. The isolated archive and Surefire reports are under `/private/tmp` and may be removed by the operating system. The exact command and aggregate results are recorded above; this report is the durable evidence artifact.

## Checked artifact paths

- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/ClientIpResolver.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/ClientIpResolverTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/AdminIpWhitelistFilterTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/GatewayProductionSecurityConfigurationTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/controller/BinanceApiControllerTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/221927f4-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/221927f4-gate-review.md`
- `/private/tmp/surprising-8682d18b.xkizul/surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`

## Exact evidence gaps

- No exact-`8682d18...` executor report was supplied/found.
- No exact-`8682d18...` code-review report was supplied/found.
- No exact-`8682d18...` manual-QA matrix was supplied/found.
- No notepad path was supplied/found.
- No PostgreSQL integration URL was available for the 22 conditional reconciliation tests.

## Final decision

**PASS / APPROVE** for exact commit `8682d18bb5dd4731c288ad7584849f28074e4e7f`. No requested production-gate criterion is contradicted by the exact artifact or reproduced evidence.
