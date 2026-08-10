# Code quality review — 8682d18bb5dd4731c288ad7584849f28074e4e7f

## Result

**PASS / APPROVE**

- `codeQualityStatus`: `CLEAR`
- `recommendation`: `APPROVE`
- `blockers`: none

## Scope verified

Reviewed the exact commit `8682d18bb5dd4731c288ad7584849f28074e4e7f` against its direct parent `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`.

Changed files:

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`

The working-tree `HEAD` resolved to the reviewed SHA. Unrelated tracked/untracked working-tree changes were present and excluded from this review.

## Evidence inspected

- Exact commit metadata, parent diff, and changed-file stat via `git show`/`git diff`.
- Signing implementation and route call chain: `GatewayApiKeyService.authenticate` (lines 101–127 in the reviewed revision), `GatewayApiKeyService.sign` (lines 195–203), and `BinanceApiController.authenticate` (lines 569–581).
- The new coverage in `GatewayApiKeyAuthenticationIpTest` (lines 21–33 and 90–105).
- Reproduced the focused test from the reviewed checkout:

  ```text
  mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am \
    -Dtest=GatewayApiKeyAuthenticationIpTest \
    -Dsurefire.failIfNoSpecifiedTests=false test

  Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
  ```

## Assessment

The production change lowercases only the received signature with `Locale.ROOT` before `MessageDigest.isEqual` (`GatewayApiKeyService.java:121-122`). The generated HMAC remains canonical lowercase hex, the comparison remains constant-time, and `Locale.ROOT` makes accepted ASCII hex casing independent of the JVM default locale. The signature is checked after API-key lookup, IP allowlist, permission, and timestamp-window checks; all private Binance-compatible controller paths with `X-MBX-APIKEY` delegate through this same service method.

The changed test supplies an actual uppercase HMAC to the public authentication method while retaining trusted-proxy/IP enforcement (`GatewayApiKeyAuthenticationIpTest.java:30`, `:98-104`). It would fail against the direct parent, so it is a relevant regression test rather than a deletion-only, tautological, or implementation-mirroring assertion.

## Skill-perspective check

Ran and consulted `omo:remove-ai-slops` and `omo:programming` before judging maintainability and test relevance.

- `remove-ai-slops`: no deletion-only test, tautological test, implementation-constant mirror, or unnecessary production extraction/parsing/normalization was introduced. The one normalization is the requested boundary behavior and is minimal.
- `programming`: no untyped escape hatch, needless abstraction, brittle prompt test, or unnecessary production validation/parsing was introduced. `Locale.ROOT` is the appropriate locale-stable choice for protocol data.

Neither skill perspective is violated by this diff.

## Findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

None.

### LOW

None.
