# Production Gate Re-audit — `a8d29deb`

- exact SHA: `a8d29deb52920a6faeebf92835f18ffb7d1646ba`
- verdict: **PASS**
- recommendation: **APPROVE**
- review mode: read-only; the only write is this required report
- prior rejection under review: `262e3e278c0467d00e85cc44b52ab8647e8b42e0`, criterion `C3`

## Original intent

Re-audit the exact backend commit as a production gate and decide whether the prior API-key IP allowlist rejection is resolved. The required boundary is: derive the client address safely through configured trusted proxies, resist forged forwarding headers, accept only literal IPv4/IPv6 and valid CIDR rules without hostname lookup, enforce API-key allow/deny behavior, preserve `AdminIpWhitelistFilter`, and keep the commit scoped.

## Desired outcome

API-key authentication and admin filtering use one trustworthy client-IP policy. Requests behind configured trusted proxies are checked against the first untrusted hop, forwarding headers from untrusted peers cannot spoof the source, hostname rules are rejected, IPv4/IPv6 CIDRs are family- and prefix-correct, and focused regressions pass at the exact SHA.

## Success criteria

- `C1`: Review and test exact SHA `a8d29deb52920a6faeebf92835f18ffb7d1646ba`.
- `C2`: Trusted-proxy `X-Forwarded-For` chains resolve from right to left to the first untrusted hop; untrusted peers cannot spoof the client IP.
- `C3`: Allowlist rules accept literal IPv4/IPv6 and valid family-specific CIDR prefixes only, and do not resolve hostnames.
- `C4`: API-key authentication has observable allow and deny coverage using the resolved client IP.
- `C5`: Existing `AdminIpWhitelistFilter` behavior and spoof-resistance regressions remain green.
- `C6`: The exact commit is cleanly scoped and contains no unrelated workspace changes.

## User outcome review

### `C1` — PASS

`git rev-parse HEAD` returned the exact requested SHA. The focused Maven command completed with exit 0 and `BUILD SUCCESS`:

```text
mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am \
  -Dtest=ClientIpResolverTest,GatewayApiKeyAuthenticationIpTest,GatewayApiKeyServiceTest,AdminIpWhitelistFilterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Observed result: 16 tests, 0 failures, 0 errors, 0 skipped; all four reactor projects succeeded.

### `C2` — PASS

`ClientIpResolver.resolve` starts with `request.getRemoteAddr()`, ignores `X-Forwarded-For` unless that TCP peer matches `trustedProxyIpAllowlist`, and then walks the forwarded chain right-to-left while the current hop remains trusted. It returns the first untrusted hop, preventing a client-controlled leftmost value from overriding an appended real client address.

Evidence:

- `ClientIpResolverTest.resolvesFirstUntrustedHopFromTrustedForwardedChain`
- `ClientIpResolverTest.ignoresForwardedHeaderFromUntrustedPeer`
- `AdminIpWhitelistFilterTest.appendedForwardedIpCannotBeSpoofedByLeftmostHeaderValue`
- `AdminIpWhitelistFilterTest.untrustedRemoteCannotSpoofForwardedAdminIp`

All passed in this review run.

### `C3` — PASS

`ClientIpResolver.parseLiteral` rejects non-numeric candidates before calling `InetAddress.getByName`: IPv4 must match four decimal octets and each octet must be at most 255; IPv6 candidates may contain only hexadecimal digits, colons, and dots. Therefore hostname strings never reach name resolution. `isValidRule` rejects malformed slash forms and bounds prefixes using the parsed address family (32 bits for IPv4, 128 for IPv6). `matchesRule` rejects mixed address families and applies byte/prefix masking.

Evidence:

- `ClientIpResolverTest.neverResolvesHostnameInAllowlistOrForwardedHeader` proves `api.example.com/32` and hostname client values are denied while an IPv4 CIDR matches.
- Existing `GatewayApiKeyServiceTest.normalizesAndValidatesIpAllowlist` and `rejectsHostNamesInIpAllowlist` pass.
- Direct adversarial source review confirmed malformed, blank, extra-slash, out-of-range IPv4, out-of-range prefix, and family-mismatch paths fail closed. IPv6 parsing/CIDR support is source-proven; there is no dedicated IPv6 regression in this commit (residual risk, not a failed stated outcome).

### `C4` — PASS

`GatewayApiKeyService.authenticate` now calls `clientIpResolver.resolve(request)` before `requireIpAllowlist`. `GatewayApiKeyAuthenticationIpTest.authenticatesUsingForwardedClientIpWhenPeerIsTrusted` exercises the full signed authentication path and permits a forwarded `10.8.2.3` against `10.8.0.0/16`; `rejectsForwardedClientIpWhenPeerIsNotTrusted` verifies the same header is denied when the TCP peer is not trusted. Both passed.

### `C5` — PASS

`AdminIpWhitelistFilter` now delegates resolution and CIDR checks to the shared resolver. Its six existing tests all passed, including empty allowlist, public-route bypass, trusted forwarded CIDR allow, spoofed-leftmost deny, untrusted-peer forwarded-header deny, and outside-allowlist deny.

### `C6` — PASS

The exact commit contains five files only: one new shared resolver, two production integrations, and two focused test classes. `git show --check a8d29deb...` reported no whitespace errors. Existing dirty/untracked docs, scripts, and `.omo` evidence are not members of the commit. No production feature beyond the prior allowlist rejection was added.

## Direct `remove-ai-slops` / `programming` pass

- Tests are behavior-oriented: the authentication tests traverse the real service authorization path and distinguish trusted from untrusted peers; Admin tests assert observable HTTP status. They are not deletion-only, requested-removal pins, tautologies, prose assertions, or expected values derived from the implementation output.
- `ClientIpResolverTest.neverResolvesHostnameInAllowlistOrForwardedHeader` combines three related assertions, but each distinguishes a security input class; it does not create false confidence about API authentication because full-path allow/deny tests exist separately.
- The new extraction is justified rather than needless: it removes duplicated IP/CIDR behavior from `GatewayApiKeyService` and `AdminIpWhitelistFilter` and provides one shared trust boundary used by both consumers.
- No dead code, broad exception swallowing, speculative compatibility layer, debug output, unnecessary normalization, or scope drift was found in the five-file diff.
- Maintenance note: `InetAddress.getByName` remains behind strict literal syntax screening. This satisfies the no-hostname-resolution boundary, though a dedicated numeric parser would make that property more explicit. This is not a success-criterion failure.

No exact-SHA executor report, independent code-review report, manual-QA matrix, or notepad was found under `.omo/evidence`. The direct source/diff review and reproduced focused tests provide the required completion evidence; the missing secondary reports are recorded as evidence gaps, not blockers.

## Checked artifact paths

- `/Users/atomex/Desktop/surprising/surprising-ex/.git` (HEAD, exact commit metadata, parent diff, status)
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/262e3e27-gate-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/262e3e27-code-review.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/ClientIpResolver.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/config/AdminIpWhitelistFilter.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/ClientIpResolverTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/AdminIpWhitelistFilterTest.java`
- Maven Surefire output produced during this gate run for the four listed test classes.

## Exact evidence gaps and residual risks

- No dedicated IPv6 allow/mismatch/prefix-bound regression test is present; IPv6 behavior was verified by direct implementation inspection only.
- No test instruments DNS APIs to prove zero resolver invocation; the strict pre-screen plus hostname-denial test establishes the intended boundary, but does not mechanically spy on `InetAddress`.
- Proxy safety depends on operators configuring only actual trusted proxy ranges and on the nearest trusted proxy appending/preserving the chain correctly. Over-broad trusted ranges remain an operational risk.
- The focused suite emitted Mockito's future-JDK dynamic-agent warning; it did not affect this run.
- No independent exact-SHA review/QA/notepad artifact exists for cross-checking.

## Blockers

None.

## Final recommendation

**PASS / APPROVE.** The prior `262e3e27` API-key allowlist rejection is resolved at exact SHA `a8d29deb52920a6faeebf92835f18ffb7d1646ba`: the API-key path now uses the shared trusted-proxy resolver, spoofed forwarding headers fail closed, hostname rules are rejected, allow/deny behavior is exercised, Admin regressions remain green, and the commit is scoped to the fix.
