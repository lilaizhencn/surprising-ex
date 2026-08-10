# Code review: `221927f44588f7d6605c46c9567bf6733f179a18`

## Verdict

**BLOCKED — REQUEST_CHANGES**

The commit correctly changes a pure-query Binance request to authenticate the raw query string with the `signature` field removed, retaining parameter order and percent encoding. However, it introduces a high-risk signature coverage regression for requests that contain both a query string and form parameters. The actual order and transfer handlers consume all servlet parameters, while authentication signs only the query whenever one is present.

## Reviewed scope

Exact commit: `221927f44588f7d6605c46c9567bf6733f179a18`  
Parent: `9a20d2f2e85921476ea7c9c8142ef48ca89d57c5`  
Subject: `fix: accept Binance raw query api signatures`

Changed files:

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyAuthenticationIpTest.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`

The worktree has unrelated modified and untracked documentation/script/evidence files. `git diff --quiet <SHA> -- <three changed paths>` returned status 0, confirming the reviewed source paths still match the exact commit.

## Evidence and commands

Commands run (all from `/Users/atomex/Desktop/surprising/surprising-ex` unless noted):

```text
git rev-parse --verify 221927f44588f7d6605c46c9567bf6733f179a18^{commit}
git show --no-ext-diff --format=fuller --stat --summary 221927f44588f7d6605c46c9567bf6733f179a18
git diff --no-ext-diff --find-renames 221927f44588f7d6605c46c9567bf6733f179a18^ 221927f44588f7d6605c46c9567bf6733f179a18
git diff --check 221927f44588f7d6605c46c9567bf6733f179a18^ 221927f44588f7d6605c46c9567bf6733f179a18
git show <SHA>:surprising-edge/.../GatewayApiKeyService.java | nl -ba
nl -ba surprising-edge/.../controller/BinanceApiController.java | sed -n '153,178p;314,344p;580,587p'
mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am -Dtest=GatewayApiKeyServiceTest,GatewayApiKeyAuthenticationIpTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The Maven command completed successfully: 8 tests run, 0 failures, 0 errors. Maven emitted an existing/tooling warning that Mockito dynamically self-attaches a Java agent; this did not affect the result. `git diff --check` emitted no whitespace errors.

## Findings

### CRITICAL

None.

### HIGH

1. **A non-empty query makes form business parameters unsigned.**
   - `GatewayApiKeyService.java:139-143` selects `canonicalQuery(queryString)` whenever the query is nonblank. Therefore `authenticate` at `:115-120` signs only query components and excludes all form parameters that appear in `request.getParameterMap()`.
   - `BinanceApiController.java:314-339` reads `params` before authenticating and uses those values to create an order. `:580-585` constructs `params` from the complete servlet parameter map, which includes both query and form parameters. The same pattern is used by transfer at `:153-174`.
   - A `POST` can consequently carry a valid, fresh signed query such as `timestamp=...&signature=...` and unsigned `application/x-www-form-urlencoded` parameters such as `symbol`, `side`, `type`, `quantity`, and `price`. The server accepts the signature over the query, then executes the unsigned values. This is a regression from the parent implementation, which canonicalized the full parameter map and included every execution parameter in the HMAC input.
   - Required fix: preserve the raw-query compatibility path only when all security-relevant parameters are in that raw query, or define and enforce a signature input that includes every accepted parameter source without changing/dropping raw bytes. Add an integration-level regression test for a signed query plus tampered form order/transfer field and assert rejection.

### MEDIUM

1. **The requested no-query fallback has no direct regression test.**
   - `GatewayApiKeyService.java:139-143` adds the branch that calls `canonicalParameters(request.getParameterMap())` when no raw query exists, but `GatewayApiKeyServiceTest.java:37-45` only checks the retained `canonicalRequest` helper. It does not invoke `binanceCanonicalQuery` on an `HttpServletRequest` with a null/blank query string, so it cannot catch a fallback regression.
   - Add a focused test with a request that has no query string and parameters in non-sorted insertion order (including `signature`), asserting deterministic encoded canonical output.

2. **Authentication is tested through the trusted-proxy path but not through the new raw-query signature acceptance path.**
   - `GatewayApiKeyAuthenticationIpTest.java:19-47` verifies trusted/untrusted proxy IP behavior; its helper was updated at `:62-65` only so those existing tests can authenticate after the signing contract changed.
   - `GatewayApiKeyServiceTest.java:23-29` checks the extraction helper directly, rather than an accepted/rejected call to `authenticate`. The production HMAC comparison, timestamp validation, and raw-query extraction are thus not covered together.

### LOW

None.

## Skill-perspective check

Ran: yes. I explicitly loaded and applied `omo:remove-ai-slops` and `omo:programming` before judging test relevance and maintainability.

- `remove-ai-slops`: no deletion-only test, tautological test, implementation-constant mirror, or unnecessary production parsing/normalization was found in this small diff. Extracting `canonicalParameters` is necessary to retain the explicit no-query fallback; it is not needless production complexity. The new helper test asserts the externally required raw-order/encoding contract, rather than an incidental implementation detail.
- `programming`: no untyped escape hatch, needless abstraction, or brittle prompt test was introduced. The diff does violate the perspective's boundary/contract principle at the security boundary: authentication must bind the complete request data that the controller later trusts and executes. The missing integration regression is also insufficient for this security-sensitive behavioral change.

## Success-criteria assessment

- Raw query order and encoding: implemented and unit-checked.
- Exact `signature` key removal: implemented more correctly than the parent prefix match.
- Deterministic no-query canonicalization: implementation present, but not directly tested.
- Trusted-proxy authentication regression: target tests pass.
- Security behavior: **not satisfied** because mixed query/form requests no longer bind execution parameters to the HMAC.

## Blockers before approval

1. Fix the mixed query/form signature-coverage regression in `GatewayApiKeyService` so every parameter consumed by a signed order or transfer is cryptographically bound.
2. Add regression coverage proving the server rejects a valid signed query combined with changed form business parameters.
3. Add a direct test for the no-query canonicalization fallback.

Final status: **BLOCKED**. Recommendation: **REQUEST_CHANGES**.
