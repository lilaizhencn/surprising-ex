# Code review: `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`

## Result

**BLOCKED / REQUEST_CHANGES**

- `codeQualityStatus`: `BLOCK`
- `recommendation`: `REQUEST_CHANGES`
- Exact reviewed SHA: `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`
- Parent: `bac65f16b03348feb08cadf19a09d4716f6a6bc2`
- Report path: `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/ff3149f4-code-review.md`

The exact follow-up correctly changes the mixed raw-query/form-body signing payload from `query + "&" + body` to the Binance-required direct concatenation `query + body`. The newly corrected tests execute both acceptance and body-tamper rejection. However, the same authentication implementation still rejects legal uppercase HMAC signatures, while the official Binance contract specifies that HMAC signature values are case-insensitive. This is a real compatibility failure at the signed-request boundary and blocks approval.

## Scope and evidence inspected

Exact final diff, `bac65f16..ff3149f4`, contains only:

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`

The final worktree is at the requested exact SHA. It contains unrelated pre-existing modified/untracked docs, scripts, and `.omo/` files; none were reviewed as commit content or modified apart from this requested report.

Commands and directly inspected artifacts:

```text
git rev-parse ff3149f4e5a73fcf515a4941e6ff22138ed0a545^{commit}
git show --format=fuller --find-renames ff3149f4e5a73fcf515a4941e6ff22138ed0a545
git diff bac65f16..ff3149f4e5a73fcf515a4941e6ff22138ed0a545
git diff --check bac65f16 ff3149f4e5a73fcf515a4941e6ff22138ed0a545
rg -n -C 5 'authenticate\\(|binanceCanonicalQuery|ContentCachingRequestWrapper' surprising-edge/surprising-gateway -g '*.java'
mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am \
  -Dtest=GatewayApiKeyServiceTest,GatewayApiKeyAuthenticationIpTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Results:

- Exact SHA resolved to `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`.
- `git diff --check` was clean.
- Focused Maven run passed: 11 tests, 0 failures, 0 errors, 0 skipped (8 `GatewayApiKeyServiceTest`, 3 `GatewayApiKeyAuthenticationIpTest`). The only output warnings were Mockito/JDK dynamic-agent warnings.
- `BinanceApiController` passes the captured request body to API-key authentication for order, transfer, withdrawal, and account paths; the changed canonicalization is therefore on the live sensitive-write authentication path.
- Current official Binance Spot REST documentation was checked: it requires query string concatenated directly with HTTP body, and separately specifies that HMAC signature values are case-insensitive: <https://developers.binance.com/en/docs/products/spot/rest-api>.

## Findings

### CRITICAL

None.

### HIGH

1. **Legal uppercase HMAC signatures are rejected, contrary to the Binance-compatible contract.**

   - `GatewayApiKeyService.java:120-123`
   - Missing regression coverage in `GatewayApiKeyAuthenticationIpTest.java:52-76`

   `sign` emits lowercase hex, then `MessageDigest.isEqual` compares its UTF-8 bytes directly to the supplied `signature`. A client that uppercases a valid HMAC digest is legal under Binance's HMAC protocol but necessarily fails this byte-for-byte comparison. This prevents otherwise valid signed requests from reaching account, trade, transfer, and withdrawal operations.

   Preserve constant-time comparison while making the received HMAC hex case-insensitive, and add an authentication-boundary test that accepts an uppercase form of a known-valid signature. Do not weaken the body-tamper rejection test.

### MEDIUM

None.

### LOW

None.

## Corrected behavior verified

- `GatewayApiKeyService.java:147-161` now removes only the `signature` field from each raw source and directly concatenates non-empty raw query and raw form body at line 153. This matches the official mixed-placement signing rule.
- `GatewayApiKeyServiceTest.java:33-40` independently pins the externally specified payload `timestamp=123symbol=BTCUSDT&side=BUY`; it is not a deletion-only or implementation-derived test.
- `GatewayApiKeyAuthenticationIpTest.java:52-76` first accepts the correctly signed body through `authenticate`, then verifies a changed body is rejected with `invalid api signature`. This is a meaningful protocol/security regression test, not a tautology.

## Skill-perspective check

Ran before judging test relevance and maintainability: `omo:programming` and `omo:remove-ai-slops`.

- `remove-ai-slops`: no violation in the final three-file diff. No deletion-only test, test merely checking a requested removal, tautological test, or unnecessary data extraction/parsing/normalization was introduced. The two corrected tests cover a protocol contract and an adversarial tamper boundary.
- `programming`: no violation in the final diff. The tests do not pin prose or merely mirror an implementation constant; no untyped escape hatch or needless abstraction was added. The missing uppercase-HMAC contract test is a compatibility coverage gap, represented by the HIGH finding above.

## Blockers

1. Accept case-insensitive HMAC hexadecimal signature input while retaining constant-time verification, per Binance's HMAC protocol.
2. Add a protocol-level authentication test proving an uppercase valid HMAC signature is accepted; retain acceptance of direct query/body concatenation and rejection of tampered body data.

## Final decision

**BLOCKED / REQUEST_CHANGES** for exact SHA `ff3149f4e5a73fcf515a4941e6ff22138ed0a545`.
