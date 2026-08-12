# Commit-level code quality review

## Scope and method

- Frontend reviewed at `stitch_surprising_ex` commit `8f7a94515db39f96a9726080cc19b85476f656a6`.
- Backend reviewed at `surprising-ex` commits `eaf711dc4aff6a258d0f64c5d83b70761f820e4f` and `341a4892f4addf035a332ea1e5c18796ec76c614`.
- Uncommitted migration work was excluded. Both requested commits are the checked-out frontend HEAD and the backend's historical snapshot respectively.
- `git diff --check` completed without whitespace errors.

## Skill-perspective check

Ran: yes. I loaded and applied `omo:remove-ai-slops` and `omo:programming` before evaluating tests and maintainability.

- `remove-ai-slops`: violated. The diff adds substantial behavior without behavioral regression coverage and adds further responsibilities to already oversized page modules.
- `programming`: violated. Financial values are converted through floating point in production paths; the changed modules also exceed the size ceiling. The existing schema fixtures are not adequate regression tests for the new side-effecting behavior.

## Findings

### CRITICAL

None.

### HIGH

1. **Access token is disclosed in the private WebSocket URL.**
   - Evidence: `stitch_surprising_ex/src/hooks/useRealtime.ts:72` creates the private connection from `withPrivateCredentials`; lines `154-159` append `session.accessToken` as `?token=` (and user ID) before calling `new WebSocket` at line `76`.
   - Impact: the bearer credential becomes part of the handshake request target and can be retained by reverse-proxy/access logs, monitoring, browser history/devtools exports, and intermediary telemetry. Anyone obtaining it can use the bearer token until it expires.
   - Required fix: use a WebSocket authentication mechanism that does not place bearer credentials in the URL (for example a short-lived, single-use WS ticket or a protocol/first-message flow supported and verified by the server). Add a regression/integration test that proves no URL contains an access token.

2. **Money and order quantities are converted through IEEE-754 `number`, so valid values can be rejected or submitted with a rounded integer tick/unit value.**
   - Evidence: `stitch_surprising_ex/src/features/assets/FundingPage.tsx:578-586` computes transfer units with `Number(value) * scale`; `src/features/trading/TradePage.tsx:229-277` parses price/quantity with `Number`, then emits `Math.round(... * 10 ** precision)` as `priceTicks` and `quantitySteps`; lines `794-826` also converts account units/balances through `number`.
   - The commit's own contract says frontend financial balances must not be produced by floating-point arithmetic: `docs/api/assets.md:13-15`.
   - Impact: decimal values such as a valid `0.00000029` at an eight-decimal scale can become a non-integer after multiplication and be rejected; sufficiently large prices/quantities or integer-unit balances lose precision. The trading path has no `Number.isSafeInteger` check before sending ticks/steps, so rounded values can reach the order API.
   - Required fix: represent backend integer units/ticks as strings/`bigint` at the client boundary and convert decimal input deterministically using the advertised scale/precision (without `Number`). Test exact conversion, precision rejection, and values above `Number.MAX_SAFE_INTEGER`.

### MEDIUM

1. **Single-session revoke is not correct for a user with more than 500 sessions.**
   - Evidence: `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/UserSecurityController.java:142-149` authorizes a session only by loading the first 500 rows. `GatewayRefreshSessionRepository.java:90` clamps page size to `MAX_QUERY_LIMIT`, which is `500` at line `18`.
   - Impact: an owned session outside that first result set is reported as 404 and cannot be revoked through the security endpoint.
   - Required fix: make the authorization-and-revoke operation a single conditional update/query scoped by both `session_id` and `user_id`, and test an owned session beyond the page limit.

2. **The UI's pre-trade balance check can use an unrelated asset balance.**
   - Evidence: `stitch_surprising_ex/src/features/trading/TradePage.tsx:167-178` loads all balances but retains `balanceRows[0]`; lines `253-262` compare that value with either base-asset sell quantity or quote-asset buy notional.
   - Impact: ordering or product changes can cause a false “insufficient funds” block or an inaccurate available balance display. The backend remains the final authority, but the frontend check is incorrect and gives false confidence.
   - Required fix: select the balance by required asset/account type (base for sell, quote for buy), or remove the client-side balance verdict and present backend validation only. Cover both BUY and SELL with multiple balance assets.

3. **Documentation says “revoke other sessions”, while implementation revokes every active refresh session, including the current one.**
   - Evidence: `docs/api/security.md:11` describes `revoke-all` as “撤销其他会话”; `UserSecurityController.java:157-164` calls `revokeUserRefreshSessions`; `GatewayRefreshSessionRepository.java:131-142` updates every active session for `user_id` without excluding the calling session.
   - Impact: API/document behavior is ambiguous at a security boundary and clients cannot implement the documented semantics reliably.
   - Required fix: decide and document the intended behavior; if it is “other sessions”, pass and exclude the current refresh session. Otherwise change the documentation/UI wording to “all sessions”.

4. **The first backend commit leaves an unbuilt duplicate controller and test tree at repository root.**
   - Evidence: `eaf711dc` adds `surprising-gateway/src/main/java/.../UserSecurityController.java` and its test, but the root reactor at that commit lists `surprising-edge` and has no `surprising-gateway/pom.xml`. Commit `341a4892` copies the same 217-LOC controller and test into `surprising-edge/surprising-gateway/surprising-gateway-provider` rather than removing the unattached original.
   - Impact: the unattached copy is dead/drifting production code; a later edit can fix one controller but deploy the other.
   - Required fix: retain exactly the controller/test in the built module, or add a module declaration if the root path is intentional.

5. **Tests do not cover the changed high-risk behavior; existing schema tests provide false confidence for this commit.**
   - Evidence: frontend `src/api/types.test.ts:13-69` only parses small static fixtures and contains no tests for `amountToUnits`, order ticks, session endpoints, WebSocket URL generation, or realtime reconnection. Backend `UserSecurityControllerTest.java:27-71` uses mocks and covers only the happy scoped page, foreign-session rejection, and revoke-all; it does not cover the 500-session boundary, current-session semantics, or persistence update conditions.
   - Impact: the passing frontend 7 tests cannot detect either HIGH issue or the revoke-page defect.
   - Required fix: add observable behavior tests described in the findings; do not add deletion-only or implementation-constant assertions.

6. **The diff worsens oversized, multi-responsibility frontend modules.**
   - Evidence (pure LOC at the reviewed commit): `src/features/trading/TradePage.tsx` 884, `src/features/security/SecurityPage.tsx` 717, and `src/features/assets/FundingPage.tsx` 584. This commit adds trading/realtime, security-session, and funding-transfer responsibilities to those files.
   - Impact: the size and mixed responsibilities make financial/security review and regression isolation materially harder.
   - Required fix: split by feature responsibility (API conversion/financial input, realtime transport, and display components), preserving explicit typed contracts. Do not introduce generic helper dumps.

### LOW

None.

## Verification evidence

- Frontend snapshot (checked out at the reviewed SHA): `npm run typecheck`, `npm run lint`, and `npm test` passed. Vitest reported 3 files and 7 tests.
- Backend snapshot: Maven reactor construction reached the selected gateway module but Maven Enforcer rejected the environment before compilation/tests because it requires JDK 25. No backend test pass is claimed.
- No code changes were made during the review; this report is the sole review artifact.

## Decision

- `codeQualityStatus`: **BLOCK**
- `recommendation`: **REQUEST_CHANGES**
- `blockers`:
  1. Remove bearer access tokens from WebSocket URLs and cover the replacement authentication boundary.
  2. Replace floating-point monetary/tick conversion with exact units/ticks handling and boundary tests.
