# Code review: API-key client-IP allowlist

- Exact commit reviewed: `a8d29deb52920a6faeebf92835f18ffb7d1646ba`
- Verdict: **PASS**
- Code quality status: **CLEAR**
- Recommendation: **APPROVE**
- Scope: exact commit diff only (5 files); no worktree source changes made.

## Criterion-linked findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

None.

### LOW

1. Test coverage is not exhaustive for a chain containing two consecutive trusted proxies, nor does the API-key boundary test explicitly pass `api.example.com/32` to `normalizeIpAllowlist`. This is a coverage gap, not a correctness blocker: `ClientIpResolver.resolve` walks right-to-left, only advances while the current hop matches the trusted-proxy list, and `GatewayApiKeyService.normalizeIpAllowlist` calls `isValidRule`, which rejects the hostname before matching. Relevant lines: `ClientIpResolver.java:33-45`, `ClientIpResolver.java:60-76`, `GatewayApiKeyService.java:196-211`.

## Verification

1. **Resolve real client through configured trusted-proxy chain — PASS.** `GatewayApiKeyService.authenticate` now uses `clientIpResolver.resolve(request)` before enforcing the API-key allowlist (`GatewayApiKeyService.java:97-102`). The resolver starts at `getRemoteAddr()`, processes XFF right-to-left, and returns the first non-trusted hop (`ClientIpResolver.java:21-45`). This prevents a leftmost client-supplied value from becoming authoritative when an untrusted intermediary is present.

2. **Ignore forwarded headers from untrusted peers — PASS.** The resolver returns the literal remote address before reading XFF unless it matches the configured trusted-proxy allowlist (`ClientIpResolver.java:25-31`). Both resolver and API-key authentication tests cover this case.

3. **Reject hostnames / DNS-dependent CIDR rules — PASS.** Both rule validation and matching require a syntactically constrained IP literal before calling `InetAddress.getByName`; hostnames cannot reach DNS resolution (`ClientIpResolver.java:60-76`, `118-144`). API-key create/update use this validation (`GatewayApiKeyService.java:196-211`), while persisted invalid rules fail closed during matching.

4. **Keep `AdminIpWhitelistFilter` behavior compatible — PASS.** The filter retains its admin-route and empty-allowlist behavior, delegates authorization matching to the same resolver, and its existing six behavior tests pass (`AdminIpWhitelistFilter.java:30-53`). The shared resolver closes the earlier proxy-chain spoofing issue without widening access.

## Test commands and results

```text
mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider \
  -Dtest=ClientIpResolverTest,GatewayApiKeyAuthenticationIpTest,AdminIpWhitelistFilterTest test
Result: BUILD SUCCESS; 11 tests, 0 failures, 0 errors, 0 skipped.

mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider \
  -Dtest=GatewayApiKeyServiceTest,GatewayApiKeyAuthenticationIpTest,ClientIpResolverTest,AdminIpWhitelistFilterTest test
Result: BUILD SUCCESS; 16 tests, 0 failures, 0 errors, 0 skipped.
```

Maven emitted non-fatal remote snapshot-metadata TLS warnings and Mockito dynamic-agent warnings; neither affected compilation or the passing test results.

## Skill-perspective check

Ran the available `omo:remove-ai-slops` and `omo:programming` perspectives before assessing maintainability/test relevance. No violation found: no deletion-only, tautological, prompt, or implementation-mirroring tests; the shared resolver is a justified seam used by both API-key and admin filtering, and literal parsing is required at the untrusted request/configuration boundary rather than needless production normalization.

## Blockers

None.
