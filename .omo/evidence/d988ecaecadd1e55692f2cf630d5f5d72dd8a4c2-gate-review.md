# Gate review — d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2

## recommendation

APPROVE (user-facing classification: PASS; no release blocker)

## blockers

None.

## originalIntent

Review only the exact commit `d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2`, without carrying forward conclusions from older commits. Verify that `HttpProductAccountClient` requires explicit non-blank product-specific base URLs for all six account product routes while allowing `target-prefix` inheritance from the parent account route; verify real `application.yml` binding and fail-closed behavior; and independently audit cross-product transfer idempotency, CAS, recovery, HMAC, Binance compatibility, migrations, and documentation.

## desiredOutcome

The committed gateway can resolve each of the six explicitly configured account product endpoints using the parent `/api/v1/accounts` prefix, refuses missing routes and blank product base URLs, signs the exact internal adjustment contract accepted by account providers, and retains a recoverable/idempotent cross-product transfer flow with compatible public surfaces and deployable schema documentation.

## userOutcomeReview

- PASS / HIGH — Six-route explicit base URL + inherited prefix: `HttpProductAccountClient.java:44-53` rejects a missing product entry and a null/blank product `baseUrl` before calling `BackendRoute.resolve`; `GatewayProperties.java:1321-1343` inherits only blank product fields from the parent; final resolved base URL and prefix are checked again. `application.yml:214-230` has exactly the six `ProductLine` keys, each with an environment-backed `base-url`, while `target-prefix` exists only on the parent at line 216.
- PASS / HIGH — Real binding shape: `GatewayProductRoutesConfigurationTest.java:55-73` loads the actual classpath YAML, supplies six distinct environment overrides, iterates `ProductLine.values()`, and verifies non-blank resolved base URLs plus inherited `/api/v1/accounts`. This avoids the fallback-equals-override false-positive class.
- PASS / HIGH — Fail closed: `HttpProductAccountClientTest.java:91-121` verifies absent selected product route and blank selected product base URL throw instead of falling back to the parent base URL. `HttpProductAccountClient.java:48-53` performs the corresponding production checks.
- PASS / CRITICAL — Idempotency/CAS/recovery: `ProductTransferCoordinator.java:28-47` fingerprints requests and rejects idempotency-key reuse with a different fingerprint; `ProductTransferRepository.java:30-51` uses `(user_id,idempotency_key)` conflict handling; `ProductTransferRepository.java:62-75` performs status-CAS updates; `ProductTransferCoordinator.java:90-171` and `ProductTransferRepository.java:78-83` preserve and retry all non-terminal states; provider references are transfer/stage-stable at `ProductTransferCoordinator.java:161-163`.
- PASS / CRITICAL — HMAC and provider contract: gateway canonicalization/signing and headers are at `HttpProductAccountClient.java:55-73,96-113`; account validates service, audience, timestamp window, secret, and constant-time signature at `AccountController.java:445-503`. The endpoint assembled by the gateway matches `AccountController.java:99-106`.
- PASS / HIGH — Binance compatibility: `BinanceApiController.java:139-163` maps Binance transfer types, quantity scale, and key precedence (`clientTranId`, then `clientOrderId`, then `Idempotency-Key`) and returns `tranId`.
- PASS / HIGH — Migration and docs: `migrations/20260805_gateway_product_transfer.sql:1-47` creates transfer/event objects and indexes; `migrations/20260806_gateway_product_transfer_events.sql:1-14` is an idempotent forward supplement. `docs/product-transfer-operations.md:1-61` documents routes, six account mappings, idempotency/states, signed endpoint, recovery, migration ordering, and explicit base URL/inherited prefix requirements.
- WATCH / MEDIUM — No commit-specific PostgreSQL integration evidence was supplied for `ProductTransferRepository` or the two migration scripts. Static SQL and unit/fake-store behavior are coherent, but the local gateway run skipped 22 PostgreSQL-conditioned tests. This is an evidence gap, not proof of a stated criterion failure, so it does not block this route-resolution commit.
- WATCH / LOW — The existing code-review report is for commit `0a64c93...`, not this commit. It cannot establish review coverage here. The gate reviewer independently ran the `remove-ai-slops` and programming perspective checks, so this report mismatch is not a blocker.

## remove-ai-slops / programming direct pass

- Diff scope: four files, 45 insertions and 5 deletions.
- No deletion-only test, test of a requested removal, tautological assertion, prose pin, snapshot, or expected value derived from production output was introduced.
- The inherited-prefix test observes the final HTTP URI. The YAML binding test loads the real resource and uses six distinct product base URLs whose values differ from the parent fallback.
- No unnecessary production extraction, parser/normalizer, wrapper, abstraction, compatibility shim, dead code, broad catch, or scope expansion was introduced. The production change is the minimum one-condition removal needed to permit documented parent-prefix inheritance while retaining post-resolution validation.
- Existing Mockito usage is narrow to the HTTP boundary. It does not replace the real YAML binding test. No new maintenance-burden finding violates a stated criterion.

## reproducedEvidence

- Exact snapshot: `/tmp/surprising-ex-gate.neWKPl` created from `git archive d988ecaecadd1e55692f2cf630d5f5d72dd8a4c2`; no repository source file was edited.
- Targeted gateway tests: 24 tests, 0 failures, 0 errors, 0 skipped; Surefire artifacts under `/tmp/surprising-ex-gate.neWKPl/surprising-edge/surprising-gateway/surprising-gateway-provider/target/surefire-reports/`.
- Full gateway reactor: 174 tests, 0 failures, 0 errors, 22 skipped; `BUILD SUCCESS`; same Surefire artifact directory plus `/tmp/surprising-ex-gate.neWKPl/surprising-product-api/target/surefire-reports/`.
- Account internal-auth test: 4 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`; artifact `/tmp/surprising-ex-gate.neWKPl/surprising-account/surprising-account-provider/target/surefire-reports/TEST-com.surprising.account.provider.controller.AccountControllerInternalAuthTest.xml`.
- `git diff-tree --name-status` and `git show --unified=100` were inspected for the exact commit.

## checkedArtifactPaths

- `docs/product-transfer-operations.md`
- `migrations/20260805_gateway_product_transfer.sql`
- `migrations/20260806_gateway_product_transfer_events.sql`
- `init.sql`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/resources/application.yml`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/config/GatewayProperties.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/HttpProductAccountClient.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/ProductTransferCoordinator.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/ProductTransferRepository.java`
- `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/controller/AccountController.java`
- Changed tests: `GatewayProductRoutesConfigurationTest.java`, `HttpProductAccountClientTest.java`
- Related tests: `ProductTransferCoordinatorTest.java`, `ProductTransferGatewaySurfaceTest.java`, `BinanceApiControllerTest.java`, `AccountControllerInternalAuthTest.java`
- Existing report inspected but not relied upon: `.omo/evidence/production-gateway-security-baseline-code-review.md`
- Existing notepad inspected but not relied upon: `.omo/ultrawork-notepad-20260804.md`

## exactEvidenceGaps

1. No code-review report tied to `d988eca...` explicitly records the same programming and overfit/slop coverage. Direct gate review supplies that coverage.
2. No manual-QA matrix specific to this commit was present. Automated route/YAML/HMAC/compatibility tests and direct artifact inspection cover the requested behavior.
3. No live PostgreSQL execution evidence for the product-transfer migration/CAS path was present; 22 PostgreSQL-conditioned gateway tests skipped locally.
4. No ULW-loop plan exists for the current session, so the required fallback report location `.omo/evidence/<goal>-gate-review.md` was used.
