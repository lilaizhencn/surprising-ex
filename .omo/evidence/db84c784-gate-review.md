# Production Gate Review — db84c784

## Recommendation

**PASS / APPROVE**

- Exact SHA reviewed: `db84c78488dbbb8ed9063603e8eb2e13dced42a8`
- Parent SHA: `a8d29deb52920a6faeebf92835f18ffb7d1646ba`
- Subject: `feat: expose Binance product api namespaces`
- Review mode: final gate, read-only inspection of the exact commit; tests executed from temporary `git archive` exports so the existing worktree was not used as test input.

## Original Intent

Ship a bounded Binance compatibility increment that exposes the futures/options API namespaces and uses the namespace as a product-line default without overriding an explicit product selector. Confirm the increment is isolated, has no unrelated staged changes, and passes relevant tests.

## Desired Outcome

1. Spring exposes `/fapi/v1/**`, `/dapi/v1/**`, and `/eapi/v1/**` alongside the existing Binance-compatible namespaces.
2. With no explicit selector, path defaults resolve as:
   - `/fapi/v1` → `LINEAR_PERPETUAL`
   - `/dapi/v1` → `INVERSE_PERPETUAL`
   - `/eapi/v1` → `OPTION`
3. Explicit header, query, or request-body selectors retain precedence over path defaults.
4. The exact commit is limited to the intended production and regression-test files, with no unrelated staged changes.
5. Relevant tests compile and pass; broader failures are classified against the parent baseline.

## User Outcome Review

The exact commit delivers the requested bounded compatibility surface. The controller annotation contains all three new namespaces. `GatewayProxyService.productLine(...)` appends `pathProductLine(request.getRequestURI())` after every explicit header, query, and body selector in `firstNonBlank(...)`, so the path is a fallback and cannot override an explicit nonblank selector. Exact root namespace and child paths are accepted; lookalike prefixes such as `/fapi/v10` do not match.

The product-line route tests use distinct expected routes for linear perpetual, inverse perpetual, and option, so they would detect incorrect namespace-to-product mapping. Existing tests separately cover query, body, and header selectors. There is no conflict-case regression test combining an explicit selector with a contradictory namespace, but direct inspection proves the required ordering; this is recorded as a residual risk rather than a blocker because the stated behavior is implemented and the bounded criterion does not require a specific conflict test artifact.

## Gate Checks

| Check | Result | Evidence |
|---|---|---|
| Exact SHA identity | PASS | `git rev-parse HEAD` and `git cat-file -t` resolved `db84c78488dbbb8ed9063603e8eb2e13dced42a8`; HEAD equals the requested SHA. |
| Commit scope | PASS | `git diff-tree --no-commit-id --name-only -r db84c784...` reports exactly four Java files: two production files and their two test files. Commit stat is 59 insertions, 2 deletions. |
| Namespace exposure | PASS | `BinanceApiController.handle(...)` maps `/api/v3/**`, `/sapi/v1/**`, `/fapi/v1/**`, `/dapi/v1/**`, `/eapi/v1/**`; `exposesBinanceProductApiNamespaces` asserts the three additions. |
| Product-line path defaults | PASS | `pathProductLine(...)` maps fapi/dapi/eapi to `LINEAR_PERPETUAL`/`INVERSE_PERPETUAL`/`OPTION`; `binanceProductPathSelectsProductRoute` exercises all three against distinct backend URLs. |
| Explicit selector precedence | PASS | `productLine(...)` orders `X-Product-Line`, `X-Account-Type`, `X-Contract-Type`, query aliases, and `bodyProductLine(body)` before `pathProductLine(...)`. `firstNonBlank` selects the first nonblank value. Existing tests exercise query, body, and header selectors independently. |
| Prefix boundary safety | PASS | Matching requires exact `/fapi/v1`, `/dapi/v1`, `/eapi/v1` or the same prefix followed by `/`; unrelated versions/prefixes are not selected. |
| Diff hygiene | PASS | `git diff --check db84c784^ db84c784` returned no errors. No dependency, configuration, migration, security, or financial-accounting files changed. |
| No unrelated staged changes | PASS | `git diff --cached --name-status` returned empty. The worktree contains unrelated unstaged/untracked user files; they were not part of the commit, not staged, and were not modified by this review. |
| Targeted exact-SHA tests | PASS | Temporary export `/tmp/surprising-db84c784.KFkfca`: `mvn -f <export>/pom.xml -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am -Dtest=BinanceApiControllerTest,GatewayProxyServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` → 37 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`. |
| Module exact-SHA suite | BASELINE-QUALIFIED PASS | Same export, full module command ran 204 tests: 0 failures, 1 error, 22 skipped. The sole error was `GatewayProductionSecurityConfigurationTest.productionYamlBindsTheFailClosedSecurityBoundary`, caused by unresolved `${GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT}` binding to `BigDecimal`. |
| Parent-baseline comparison | PASS | Temporary parent export `/tmp/surprising-a8d29deb.gUOjfl`: the exact failing configuration test reproduced the same bind error (1 test, 1 error). The failure predates and is unrelated to this four-file commit. |
| AI-slop/overfit direct pass | PASS | No deletion-only tests, requested-removal tests, tautological expected values, production-output-derived expectations, unnecessary extraction/parsing/normalization, dead code, debug code, or scope drift were added. The annotation test is structural contract coverage; the route test asserts observable target URLs. |
| Programming-maintenance pass | PASS | The production delta is local, dependency-free, and preserves existing public signatures and selector parsing. No new broad catch, null-defense layer, logging, type escape hatch, or speculative abstraction was introduced. |
| Code review report coverage | NOTE | No commit-specific `db84c784...-code-review.md` was available under `.omo/evidence`. Per gate policy, direct artifact inspection and direct skill-perspective coverage above support completion; older unrelated reports were not treated as evidence for this SHA. |

## Changed Artifacts Checked

- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/GatewayProxyService.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/controller/BinanceApiControllerTest.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/service/GatewayProxyServiceTest.java`
- Exact commit patch and metadata for `db84c78488dbbb8ed9063603e8eb2e13dced42a8`
- Exact parent metadata and baseline test at `a8d29deb52920a6faeebf92835f18ffb7d1646ba`
- Surefire output under the two temporary archive exports named above
- `.omo/evidence/` inventory and `.omo/ultrawork-notepad-20260804.md` inventory; neither contains commit-specific executor/manual-QA evidence for this SHA.

## Blockers

None.

## Exact Evidence Gaps

1. No commit-specific executor report, code-review report, manual QA matrix, or test log was present in `.omo/evidence` before this gate. This review independently reproduced the exact-SHA tests and inspected the artifacts, so the absence does not violate a stated success criterion.
2. No dedicated regression test combines a contradictory explicit selector with an fapi/dapi/eapi path. Implementation ordering directly satisfies explicit-selector precedence, and independent selector tests exist; this remains a test-strength gap, not evidence of a failed criterion.
3. No full Spring MockMvc request proves each new namespace reaches `handle(...)`; the reflection test verifies the runtime-consumed `@RequestMapping` metadata. This is a bounded integration-depth gap, not a failed namespace criterion.
4. The complete gateway-provider suite is not globally green in an environment without `GATEWAY_PRODUCT_TRANSFER_VERIFICATION_THRESHOLD_USDT`; the identical failure is reproduced on the parent SHA. Twenty-two Postgres tests are skipped without their external test environment.

## Residual Risks

- Future JDKs will disallow Mockito's current dynamic self-attachment by default; this warning is pre-existing and unrelated to the commit.
- Existing changed classes are larger than the `remove-ai-slops` 250-pure-LOC guideline. The size predates this commit, and this bounded change neither creates a new module nor adds a new responsibility outside existing controller/routing ownership; it is maintenance debt, not a blocker tied to this increment.
- Namespace mapping exposes only endpoint names already handled by suffix dispatch. Unsupported futures/options endpoints correctly remain `404` with the existing Binance-compatible error response; broader endpoint compatibility was not part of this increment.

## Final Decision

**PASS / APPROVE** for production gating of exact commit `db84c78488dbbb8ed9063603e8eb2e13dced42a8` within the stated bounded Binance compatibility scope.
