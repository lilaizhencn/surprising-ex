# Code quality and contract review — d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2

## Scope and evidence

- Reviewed exact commit `d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2` (`fix: allow inherited account route prefixes`), not prior review output.  It is `master`/`HEAD` at review time.
- Commit diff: four files — `HttpProductAccountClient.java`, its two focused test classes, and `docs/product-transfer-operations.md`.
- The working tree contained unrelated tracked/untracked work before review; it was not used as source evidence.
- Skill-perspective check: **ran**.  I loaded and applied `omo:remove-ai-slops` and `omo:programming`.  The production diff does not add needless data extraction, parsing/normalization, an untyped escape hatch, or a needless abstraction.  One test violates the remove-ai-slops/programming test perspective as described under MEDIUM-1.
- Test command run at this exact `HEAD`:

  ```sh
  mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am \
    -Dtest=HttpProductAccountClientTest,GatewayProductRoutesConfigurationTest,\
ProductTransferCoordinatorTest,ProductTransferGatewaySurfaceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```

  PASS. Surefire reports show 0 failures/errors: `GatewayProductRoutesConfigurationTest` 3 tests, `HttpProductAccountClientTest` 6, `ProductTransferCoordinatorTest` 7, and `ProductTransferGatewaySurfaceTest` 1.  Reviewable artifacts are under `surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`.

## Contract results

| Area | Result | Evidence |
|---|---|---|
| Six product route declarations | PASS | `application.yml:214-229` declares SPOT, LINEAR/INVERSE perpetual, LINEAR/INVERSE delivery, OPTION, each with its own `GATEWAY_ROUTE_ACCOUNT_*_BASE_URL` value. |
| Explicit child `base-url`; parent `target-prefix` inheritance | PASS | `HttpProductAccountClient.java:46-53` rejects a missing/blank selected child base URL before `resolve`; `GatewayProperties.java:1329-1335` inherits only a blank/null child prefix.  `application.yml:215-216` supplies the parent `/api/v1/accounts` prefix. |
| Bound YAML shape | PASS | `GatewayProductRoutesConfigurationTest.java:75-84` loads the actual classpath `application.yml` through Spring's `YamlPropertySourceLoader` and Binder. |
| Missing route / blank base URL fail closed | PASS | `HttpProductAccountClient.java:46-53`; tested for no product route at `HttpProductAccountClientTest.java:118-129` and blank selected base URL at `131-144`. |
| HMAC account-provider contract | PASS by code inspection; WATCH test coverage | Gateway canonicalizes the same length-prefixed fields and signs HMAC-SHA256 in `HttpProductAccountClient.java:96-117`; account provider checks audience, time window, constant-time signature, and same canonical fields in `AccountController.java:445-503`. |
| Cross-product idempotency/CAS/recovery | PASS by code inspection; WATCH test coverage | Unique `(user_id,idempotency_key)` creation at `ProductTransferRepository.java:30-52`, status-CAS at `62-75`, stable stage references at `ProductTransferCoordinator.java:161-163`, and account-side deterministic command ID at `AccountCommandGateway.java:82-125`. Recovery scans nonterminal rows at `ProductTransferRepository.java:78-84` and is scheduled at `ProductTransferReconciliationTask.java:20-23`. |
| Binance compatibility | PASS | `/sapi/v1/asset/transfer` maps supported Binance transfer types to the gateway coordinator (`BinanceApiController.java:139-163,237-248`), including CM futures aliases; tests cover UM and CM alias mapping.  MAIN↔SPOT deliberately fails as a same underlying account, consistent with docs. |
| Migration and operations docs | PASS | Forward-safe event-table supplement `migrations/20260806_gateway_product_transfer_events.sql:1-14`; deployment/recovery instructions and correct six-route requirement at `docs/product-transfer-operations.md:57-61`. |

## Findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

1. `GatewayProductRoutesConfigurationTest.java:57-72` — the new “every product line” test does not prove that every environment override actually binds to its own child `base-url`.  It asserts only `resolved.getBaseUrl().isNotBlank()` at line 70.  `BackendRoute.resolve` falls back to the parent default `http://localhost:9086` when a child value is missing (`GatewayProperties.java:1329-1332`), so a broken property key/binding for one or all six child routes still passes.  This is implementation-insensitive false confidence, and violates the remove-ai-slops/programming test perspective.  The test should assert the expected per-line base URL (and ideally drive `HttpProductAccountClient.adjust` for each) independently from the resolver implementation.

2. `ProductTransferRepository.java:54-58` — method `lock` is an ordinary select, not a database row lock.  Safety is currently recovered by status CAS (`62-75`) plus the account provider's deterministic command-ID idempotency (`AccountCommandGateway.java:114-125`), but there is no Postgres/concurrent execution test for duplicate gateway requests or reconciliation racing a foreground call.  `ProductTransferCoordinatorTest.java:122-158` is a single-threaded in-memory fake and cannot exercise that database/external-side-effect boundary.  This is a material regression-risk gap for a funds-moving saga, though not a demonstrated correctness defect given the downstream idempotency seam.

3. `HttpProductAccountClientTest.java:60-62` — the signature assertion calls `client.signature(...)`, the same implementation being checked.  It cannot detect a jointly wrong canonicalization or HMAC encoding.  The actual account-controller implementation currently matches by inspection, but no contract test invokes/verifies the provider endpoint for valid and invalid signatures.  This is tautological test coverage under the requested quality criteria.

### LOW

None.

## Recommendation

- `codeQualityStatus`: **WATCH**
- `recommendation`: **APPROVE**
- `blockers`: None.  The current change correctly implements the requested route inheritance while preserving explicit child-base-url fail-closed behavior.  Address the three MEDIUM test gaps before relying on the transfer saga as production-grade evidence.

