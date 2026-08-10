# Code review: 262e3e27 — enforce api key IP allowlists

## Verdict

- **PASS/FAIL:** FAIL
- **codeQualityStatus:** BLOCK
- **recommendation:** REQUEST_CHANGES
- **Scope:** exact commit `262e3e278c0467d00e85cc44b52ab8647e8b42e0` only. It changes eight files, all directly related to the requested schema/API-key allowlist or Binance capital-config work. No scope-creep finding.

## Findings

### P0 / CRITICAL

None.

### P1 / HIGH

1. **API-key IP enforcement uses the TCP peer, not the trusted client IP.**  
   `GatewayApiKeyService.authenticate` calls `requireIpAllowlist(..., request.getRemoteAddr())` at `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java:90`. Behind a reverse proxy this is the proxy address. A user allowlisting their actual address is therefore rejected; allowlisting the proxy address permits every client routed through that proxy. This repository already implements the required trust boundary in `AdminIpWhitelistFilter.clientIp` (only honors `X-Forwarded-For` when `getRemoteAddr()` is in `trusted-proxy-ip-allowlist`). Reuse an equivalent trusted-proxy client-IP resolver for API-key allowlists and test both trusted and untrusted proxy cases.

2. **CIDR validation accepts DNS names when a prefix is appended, then resolves them again while authenticating.**  
   `validCidr` at `GatewayApiKeyService.java:220-233` passes `parts[0]` directly to `InetAddress.getByName`; unlike the no-prefix branch at line 228, the CIDR branch never proves that `parts[0]` is an IPv4/IPv6 literal. Thus `localhost/32` (and a DNS name with an A/AAAA record) is accepted. `matchesCidr` resolves the name anew at lines 237-264, so the stored authorization changes with DNS rather than the submitted IP. This violates the advertised IP/CIDR-only allowlist and makes the policy mutable after sensitive verification. Reject hostnames before DNS resolution; parse only numeric IPv4/IPv6 literals, validate the family/prefix, and store a canonical numeric network.

### P2 / MEDIUM

1. **New tests do not execute the security-critical authorization path.**  
   `GatewayApiKeyServiceTest.java:38-48` only calls the package-private normalizer on a service constructed with `repository`, `authService`, and `verificationService` all `null` (`:12-13`). It has no exact IPv4/CIDR/IPv6 matching test, no rejected remote-IP test, no hostname-with-prefix case, no `PATCH` ownership/sensitive-verification test, and no trusted-proxy test. The test named `rejectsHostNamesInIpAllowlist` verifies only an unprefixed hostname, which misses the accepted `hostname/32` input. Add behavioral tests that pass through `authenticate` and the controller/service mutation boundary.

2. **The changed service is now an oversized multi-responsibility class.**  
   `GatewayApiKeyService.java` is 280 nonblank/non-comment lines after this commit and now combines credential issuance, signature authentication, permission authorization, persistence orchestration, and hand-written IP/CIDR parsing/matching. This is a maintainability regression at a security boundary; isolate the parsed IP allowlist value/matcher behind a narrow, tested component rather than growing this service further.

### LOW

None.

## Required-focus checks

| Check | Result | Evidence |
|---|---|---|
| Migration/init/repository wiring | PASS | `init.sql` adds `ip_allowlist TEXT NOT NULL DEFAULT ''`; migration is idempotent; repository INSERT/SELECT/record/update all carry the field. |
| API permission and sensitive verification | PASS | Creation and PATCH authenticate bearer credentials, use `SECURITY_SETTINGS` verification, and updates are constrained by both `user_id` and active API key. API request authorization performs IP check before permission/signature use. |
| Secret exposure | PASS | Ciphertext remains repository-only; views contain API-key metadata and allowlist, while plaintext secret is returned only by the existing create-once `CreatedApiKey` contract. No changed logging/error path prints it. |
| IPv4/IPv6/CIDR validation | FAIL | P1 DNS-with-prefix acceptance above. |
| Binance `capital/config/getall` source of enabled chains/assets/withdrawal state | PASS, within gateway evidence | `BinanceApiController.java:203-229` exclusively consumes authenticated `CustodyWalletClient.chains()`, filters `enabled`, uses `assetSymbols`, and maps `withdrawalEnabled`; it no longer uses local asset scales or withdrawal-address IDs. |
| Wallet tenant binding | UNPROVEN outside this repository | `CustodyWalletClient.chains()` sends the configured custody credentials to `/custody/api/v1/chains`, but the surprising-wallet server and its credential-to-tenant authorization are not present here. The gateway unit test uses a mock and cannot establish that external tenant contract. |

## Test and evidence review

- Targeted command passed: `mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am -Dtest=GatewayApiKeyServiceTest,BinanceApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` — 15 tests passed.
- Full gateway-module command was **not green**: `mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am test` — 195 run, 1 error, 22 skipped. The error is in unchanged `GatewayProductionSecurityConfigurationTest`: unresolved `${GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT}` cannot bind to `BigDecimal`. It is not attributable to this eight-file commit, but it prevents claiming a green full-suite gate.
- `git show --check 262e3e27` reported no whitespace errors.

## Skill-perspective check

Ran before judging tests/maintainability: `omo:programming` and `omo:remove-ai-slops`.

- **programming perspective:** violated. The IP policy is a security boundary but is bound to the wrong network identity behind trusted proxies; the added parsing also expands an already-large service instead of a narrow typed/parsed boundary.
- **remove-ai-slops perspective:** violated on behavior coverage, not on superficial test slop. The two added tests are not deletion-only, tautological, prompt-text, or constant-mirroring tests, and the production parsing is required by the feature. They nevertheless leave the observable enforcement behavior uncovered and give false confidence for the specific hostname-with-CIDR defect.

## Blockers before approval

1. Resolve API-key client IP through the same trusted-proxy boundary used for admin IP controls; do not whitelist the reverse proxy as a substitute for the caller.
2. Reject hostnames in every allowlist form, including CIDR notation; parse and store canonical literal addresses/networks.
3. Add behavioral regression coverage for the two fixes, including IPv4, IPv6, CIDR match/non-match, hostname-with-prefix rejection, and proxy trust behavior.
