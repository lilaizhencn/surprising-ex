# Code review: `bac65f16b03348feb08cadf19a09d4716f6a6bc2`

## Result

**BLOCKED / REQUEST_CHANGES**

- `codeQualityStatus`: `BLOCK`
- `recommendation`: `REQUEST_CHANGES`
- Exact reviewed SHA: `bac65f16b03348feb08cadf19a09d4716f6a6bc2`
- Parent: `221927f44588f7d6605c46c9567bf6733f179a18`

The commit does pass the supplied focused tests, but it implements a signing payload that is incompatible with Binance's documented HMAC signed-request contract when both a query string and a form body are present. This rejects correctly signed client requests and makes the new test suite assert the incompatible behavior.

## Scope inspected

Exact `221927f4..bac65f16` diff (82 insertions, 12 deletions):

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`

The repository HEAD was the requested SHA. The worktree had unrelated pre-existing modified/untracked docs, scripts, and `.omo/` content; none was treated as part of this review.

## Evidence and commands

Inspected:

```text
git rev-parse HEAD
git show -s --format='%H%n%P%n%s' bac65f16b03348feb08cadf19a09d4716f6a6bc2
git diff --no-ext-diff --find-renames 221927f4 bac65f16b03348feb08cadf19a09d4716f6a6bc2
git diff --no-ext-diff bac65f16^ bac65f16 --check
git grep -n "binanceCanonicalQuery\|apiKeyService.authenticate(request" bac65f16 -- surprising-edge/surprising-gateway/surprising-gateway-provider/src
mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am \
  -Dtest=GatewayApiKeyServiceTest,GatewayApiKeyAuthenticationIpTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Results:

- `git diff --check`: clean.
- Focused Maven run: `BUILD SUCCESS`; 11 tests run, 0 failures, 0 errors, 0 skipped (8 `GatewayApiKeyServiceTest`, 3 `GatewayApiKeyAuthenticationIpTest`). Mockito emitted its existing dynamic-agent warning only.
- Official Binance Spot REST request-security documentation was checked. It specifies that the signing payload is the query string concatenated with the HTTP body **without a separator**, with non-ASCII input percent-encoded before signing: <https://developers.binance.com/docs/binance-spot-api-docs/rest-api/request-security>.

## Findings

### CRITICAL

None.

### HIGH

1. **The query/body signing payload inserts an unsupported separator, breaking Binance compatibility.**

   - `GatewayApiKeyService.java:152-154`
   - `GatewayApiKeyServiceTest.java:33-40`

   For a raw query `timestamp=...` and body `symbol=BTCUSDT&side=BUY`, the implementation signs `timestamp=...&symbol=BTCUSDT&side=BUY`. Binance requires the raw query concatenated directly with the raw HTTP body: `timestamp=...symbol=BTCUSDT&side=BUY`. The separator is semantically material because it is included in the HMAC input. Therefore a standard-compatible client signature is rejected, while a nonstandard signature is accepted.

   The newly added `combinesBinanceQueryAndFormBodyForSigning` asserts the inserted `&`, so it codifies the defect instead of detecting it. Correct the canonicalization to preserve the exact raw concatenation rule and replace/add a test using the documented no-separator payload. Include an accepted signed request and a body-tamper rejection through `authenticate` or the controller, rather than only the package-private canonicalizer.

### MEDIUM

None.

### LOW

None.

## Security and API assessment

- Passing `body` into `authenticate` for transfer, withdraw, account, and order is appropriately scoped and closes the prior query-only coverage gap for those routes.
- With the implementation's own (incorrect) payload, a changed body changes the HMAC input; the added tamper-rejection test demonstrates that narrow property. It does not demonstrate compatibility with a real Binance-formatted signed request.
- The no-query fallback test exercises the existing deterministic parameter-map fallback and is relevant, but it does not establish raw form-body acceptance or endpoint wiring.

## Skill-perspective check

Ran: `omo:remove-ai-slops` and `omo:programming` instructions were loaded before judging test relevance and maintainability.

- `remove-ai-slops`: violated by the added test that locks an implementation-specific separator rather than the required observable protocol behavior; this is false confidence, elevated to HIGH because it enforces the actual compatibility regression. No deletion-only or tautological tests found.
- `programming`: violated by the same implementation-mirroring test and missing behavior-level acceptance coverage for the compatibility boundary. No untyped escape hatches or needless abstractions were introduced by this diff.

## Required blocker resolution

1. Remove the inserted separator between non-empty raw query and raw body so the signing input is their exact direct concatenation.
2. Replace the canonicalizer-only assertion with a protocol-level positive test for a correctly signed query-plus-form-body request, and retain a tamper-rejection test that fails after body modification.

