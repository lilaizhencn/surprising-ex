# Code review: surprising-ex `d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2`

## Scope and method

- Goal: independently review the exact commit `d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2` for account product routing, configuration binding, fail-closed behavior, and cross-product transfer safety.
- Commit verified with `git rev-parse`; it is the repository `HEAD` at review time. The working tree has unrelated uncommitted docs/scripts and `.omo/` files; no conclusion relies on them.
- ULW status returned `ULW_LOOP_PLAN_MISSING`, so the required fallback artifact location is used. No attempt-specific evidence/notepad was available to trust or reuse.
- Skill-perspective check: RAN. Read and applied `omo:remove-ai-slops` and `omo:programming` before judging tests/maintainability. The production diff does not introduce needless parsing/normalization, abstraction, untyped escape hatches, or other slop. One test-quality finding below violates the programming perspective because it mirrors the implementation under test.

## Verdict

- Status: WATCH
- Recommendation: APPROVE
- Blocking findings: none

## Findings

### CRITICAL

None.

### HIGH

None.

### MEDIUM

1. The HMAC assertion does not independently verify the gateway-to-account protocol. `HttpProductAccountClientTest` computes the expected header by calling `client.signature(...)`, the same implementation that generated the header, at [HttpProductAccountClientTest.java:60](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/service/HttpProductAccountClientTest.java#L60). This is implementation-mirroring/tautological coverage under the `programming` perspective: a compatible-looking but mutually incorrect canonicalization can pass. The account-side test suite has no test for `adjustProductBalance` / `requireInternalProductService` (the selected `AccountControllerInternalAuthTest` covers the older balance-adjustment protocol only). Add a narrow cross-module contract test or independently constructed HMAC vector covering audience, account type, normalization, and rejection on tamper. This is MEDIUM because the two current implementations were manually compared and match, but regression protection is incomplete.

### LOW

None.

## Verified behavior

| Area | Result | Review evidence |
| --- | --- | --- |
| Six account product routes and YAML shape | PASS | `application.yml` declares SPOT, LINEAR_PERPETUAL, INVERSE_PERPETUAL, LINEAR_DELIVERY, INVERSE_DELIVERY, OPTION as `product-routes`, each with its own environment-backed `base-url` at [application.yml:214](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/resources/application.yml#L214). The parent supplies `/api/v1/accounts` at [application.yml:216](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/resources/application.yml#L216). Binding test loads the real YAML and all six overrides at [GatewayProductRoutesConfigurationTest.java:57](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/config/GatewayProductRoutesConfigurationTest.java#L57). |
| Explicit base URL and inherited prefix | PASS | `HttpProductAccountClient` refuses absent/blank selected product `baseUrl` before `resolve`, while `resolve` inherits only the blank product `targetPrefix`; the resolved route is rechecked for both fields at [HttpProductAccountClient.java:44](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/HttpProductAccountClient.java#L44). The target construction is verified by the route inheritance test at [HttpProductAccountClientTest.java:67](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/service/HttpProductAccountClientTest.java#L67). |
| Missing route / empty base URL fail-closed | PASS | Absent product-route map and blank selected `baseUrl` both throw before network I/O: [HttpProductAccountClient.java:48](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/HttpProductAccountClient.java#L48), tests [HttpProductAccountClientTest.java:119](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/service/HttpProductAccountClientTest.java#L119) and [HttpProductAccountClientTest.java:131](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/service/HttpProductAccountClientTest.java#L131). |
| Cross-product transfer idempotency and CAS | PASS | Per-user unique `(user_id, idempotency_key)` index is in [20260805_gateway_product_transfer.sql:27](../../migrations/20260805_gateway_product_transfer.sql#L27); request fingerprint collision is rejected at [ProductTransferCoordinator.java:39](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/ProductTransferCoordinator.java#L39); transitions use `WHERE transfer_id = ? AND status = ?` at [ProductTransferRepository.java:62](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/ProductTransferRepository.java#L62). The coordinator suite exercises duplicate keys, conflict, compensation, unknown outcomes, and unique provider references at [ProductTransferCoordinatorTest.java:13](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/service/ProductTransferCoordinatorTest.java#L13). |
| Recovery and financial safety | PASS | Unknown debit/credit and failed compensation remain non-terminal; reconciliation selects only those states and retries with stable provider references: [ProductTransferCoordinator.java:90](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/ProductTransferCoordinator.java#L90), [ProductTransferRepository.java:78](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/ProductTransferRepository.java#L78). The scheduled task runs from configured delay/batch size at [ProductTransferReconciliationTask.java:20](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/task/ProductTransferReconciliationTask.java#L20). |
| HMAC/audience implementation | WATCH | Gateway signs length-prefixed service/audience/timestamp/user/account/asset/amount/reference/reason at [HttpProductAccountClient.java:65](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/HttpProductAccountClient.java#L65); account provider validates the same sequence, audience, time window, and constant-time comparison at [AccountController.java:445](../../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/controller/AccountController.java#L445). See the MEDIUM coverage gap. |
| Binance compatibility and migration/docs | PASS | Binance transfer maps supported type pairs into the gateway `/transfers` command at [BinanceApiController.java:139](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java#L139), with tests in [BinanceApiControllerTest.java:36](../../surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/controller/BinanceApiControllerTest.java#L36). Forward-compatible event-table migration is idempotent at [20260806_gateway_product_transfer_events.sql:1](../../migrations/20260806_gateway_product_transfer_events.sql#L1); deployment/recovery and six-route operations are documented bilingually at [product-transfer-operations.md:57](../../docs/product-transfer-operations.md#L57). |

## Reproducible test evidence

1. `mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am -Dtest=GatewayProductRoutesConfigurationTest,HttpProductAccountClientTest,ProductTransferCoordinatorTest,ProductTransferGatewaySurfaceTest,BinanceApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
   - Exit 0, 24 tests, 0 failures/errors/skips, `BUILD SUCCESS`.
   - Detailed reports: `surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/TEST-com.surprising.gateway.provider.config.GatewayProductRoutesConfigurationTest.xml`, `TEST-com.surprising.gateway.provider.service.HttpProductAccountClientTest.xml`, `TEST-com.surprising.gateway.provider.service.ProductTransferCoordinatorTest.xml`, `TEST-com.surprising.gateway.provider.service.ProductTransferGatewaySurfaceTest.xml`, `TEST-com.surprising.gateway.provider.controller.BinanceApiControllerTest.xml`.
2. `mvn -pl surprising-account/surprising-account-provider -am -Dtest=AccountControllerInternalAuthTest,AccountCommandGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`
   - Exit 0, 4 tests, 0 failures/errors/skips, `BUILD SUCCESS`. `AccountCommandGatewayTest` does not exist and was safely ignored because `surefire.failIfNoSpecifiedTests=false`; this command therefore supports only the existing common internal-auth tests, not the product-adjustment protocol gap described above.
   - Detailed report: `surprising-account/surprising-account-provider/target/surefire-reports/TEST-com.surprising.account.provider.controller.AccountControllerInternalAuthTest.xml`.

`git diff --check d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2^ d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2` exited 0. The exact commit changes four files only (one production line, two focused tests, one bilingual operations clarification); it has no scope drift or unnecessary production parsing/normalization.
