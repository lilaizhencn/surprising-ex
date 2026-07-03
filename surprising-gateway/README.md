# surprising-gateway

[简体中文](README_CN.md)

Stateless public REST gateway for frontend and BFF traffic.

The mainstream exchange layout is a unified public gateway or BFF at the edge, while each business module still owns its internal API and configuration. This module follows that pattern: frontend clients call one gateway prefix, and the gateway proxies only allowlisted routes to internal services.

## Module

- `surprising-gateway-provider`: Spring Boot allowlisted proxy.

## Endpoint

- HTTP port: `9094`
- Gateway prefix: `/api/v1/gateway/{service}`
- Admin gateway prefix: `/api/v1/admin/gateway/{service}`
- Local admin API prefix: `/api/v1/admin/...`

Examples:

```bash
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-trigger/open?userId=1001&symbol=BTC-USDT' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/market-maker/strategies' -H 'X-User-Id: ops'
```

Local admin API examples:

```bash
curl 'http://localhost:9094/api/v1/admin/users/1001/profile?settleAsset=USDT&limit=50' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/compliance/users/1001' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/exports' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/system/health' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/system/metrics?windowMinutes=60' \
  -H 'Authorization: Bearer <admin-token>'
```

## Routes

| Gateway service | Internal target | Private |
| --- | --- | --- |
| `instrument` | `http://localhost:9080/api/v1/instruments` | no |
| `candlestick` | `http://localhost:9081/api/v1/candlestick` | no |
| `price-index` | `http://localhost:9082/api/v1/price/index` | no |
| `price-fx` | `http://localhost:9082/api/v1/price/fx` | no |
| `price-mark` | `http://localhost:9083/api/v1/price/mark` | no |
| `trading` | `http://localhost:9084/api/v1/trading/orders` | yes |
| `trading-market` | `http://localhost:9085/api/v1/trading/market` | no |
| `trading-trigger` | `http://localhost:9095/api/v1/trading/trigger-orders` | yes |
| `account` | `http://localhost:9086/api/v1/accounts` | yes |
| `risk` | `http://localhost:9087/api/v1/risk` | yes |
| `liquidation` | `http://localhost:9088/api/v1/liquidations` | yes |
| `funding` | `http://localhost:9089/api/v1/funding` | no |
| `insurance` | `http://localhost:9090/api/v1/insurance` | yes |
| `adl` | `http://localhost:9091/api/v1/adl` | yes |
| `market-maker` | `http://localhost:9096/api/v1/market-maker` | yes |
| `wallet` | `http://localhost:8002/wallet/v1` | yes |

The gateway rejects unknown service names. It does not concatenate arbitrary backend hostnames or table names from user input.

## Security Model

The current implementation requires either `X-User-Id` or `Authorization` on private routes. In production, put authentication before this gateway and inject a trusted `X-User-Id` only after token/session validation.
`X-Trace-Id` is accepted on every route, normalized, returned to the caller, and forwarded to backend providers. It is for observability only and must never be used as an authentication or authorization input.

Admin paths under `/api/v1/admin/...` do not use the public frontend `X-User-Id` fallback and accept only Bearer tokens with `SUPPORT`, `ADMIN`, or `SUPER_ADMIN`, with permission points still limiting actual access. The admin proxy injects `X-Admin-User-Id`, `X-Admin-Username`, and `X-Admin-Roles` downstream. The support read-only endpoint `/api/v1/admin/support/users/{userId}/overview` aggregates user status, KYC summary, assets, orders, and risk snapshots without returning session management, role editing, or login-audit data. Support ticket endpoints under `/api/v1/admin/support/tickets` provide ticket search, creation, note timelines, and status changes; writes require `admin.support.write`. The local admin user profile endpoint `/api/v1/admin/users/{userId}/profile` aggregates account, order, trigger order, risk, session, and login-log data and returns downstream failures as partial section errors. The market health endpoint `/api/v1/admin/market/health` aggregates source, index, mark, and candlestick freshness. The alert center endpoint `/api/v1/admin/alerts` manages rules, events, manual evaluation, and acknowledgements. The trading operations endpoint `/api/v1/admin/trading/metrics` aggregates order, trade, matching, trigger-order, position, and symbol metrics. Account asset report endpoints under `/api/v1/admin/reports/account-assets` expose cross-currency valuation and daily snapshot reports. Compliance endpoints under `/api/v1/admin/compliance/...` manage KYC profiles, risk tags, and AML cases. The `risk-admin` admin proxy service forwards to `/api/v1/admin/risk` for risk rule overrides, high-risk account aggregation, and admin liquidation-candidate paging. The `liquidation-admin` admin proxy service forwards to `/api/v1/admin/liquidations` for liquidation-order paging, candidate timelines, and candidate cancel operations. Export endpoints under `/api/v1/admin/exports` create, list, and download admin CSV exports. Long-running query endpoints under `/api/v1/admin/query-tasks` create, list, and inspect controlled JSON query results. Admin TOTP 2FA can be enrolled, confirmed, and disabled through `/api/v1/admin/security/mfa`; production deployments can set `surprising.gateway.security.require-admin-mfa=true` to require a TOTP code for admin login.

User lists at `GET /api/v1/admin/users` support cursor paging with `createdAt.desc` and
`createdAt.asc`, returning `nextCursor`, `hasMore`, `sort`, and `limit`. User status and role
writes remain sensitive operations and require approval.
Session lists at `GET /api/v1/admin/sessions` and `GET /api/v1/admin/users/{userId}/sessions`
support cursor paging with `createdAt.desc` and `createdAt.asc`, returning `nextCursor`,
`hasMore`, `sort`, and `limit`. Session revoke operations remain sensitive and require approval.

Support ticket lists at `GET /api/v1/admin/support/tickets` support cursor paging with
`updatedAt.desc`, `updatedAt.asc`, `createdAt.desc`, and `createdAt.asc`, returning `nextCursor`,
`hasMore`, `sort`, and `limit`. Ticket note timelines at
`GET /api/v1/admin/support/tickets/{ticketId}/notes` support cursor paging with `createdAt.asc`
and `createdAt.desc`, returning the same metadata. Ticket creation, notes, and status changes
require `admin.support.write`.

The `market-maker` admin proxy service forwards to `/api/v1/admin/market-maker` for strategy status, quote-quality metrics, strategy config overrides, PnL attribution, and strategy run logs. `/strategy-logs` supports cursor paging with `createdAt.desc` and `createdAt.asc`, returning `nextCursor`, `hasMore`, `sort`, and `limit`.

Permission-point RBAC is backed by `gateway_permissions` and `gateway_role_permissions`. The gateway checks local admin paths against permissions such as `admin.support.read`, `admin.users.read/write`, `admin.audit.read`, `admin.market.read`, `admin.alerts.read/write`, `admin.trading.read`, `admin.reports.read/write`, `admin.compliance.read/write`, `admin.exports.read/write`, `admin.queries.read/write`, and `admin.permissions.write`; admin proxy paths are checked against `admin.gateway.{service}.read/write`. Role and permission APIs are exposed under `/api/v1/admin/roles` and `/api/v1/admin/permissions`. `SUPER_ADMIN` has `admin.*` by default, `ADMIN` has current operations permissions but cannot modify permission assignments, and `SUPPORT` has only `admin.support.read` plus `admin.security.mfa`.

Compliance data is stored in `gateway_user_kyc_profiles`, `gateway_user_risk_tags`, and `gateway_user_aml_cases`. Compliance user lists at `GET /api/v1/admin/compliance/users` support cursor paging with `updatedAt.desc` and `updatedAt.asc`; risk-tag lists support `createdAt.desc`, `createdAt.asc`, `updatedAt.desc`, and `updatedAt.asc`; AML case lists support `updatedAt.desc`, `updatedAt.asc`, `createdAt.desc`, and `createdAt.asc`. Responses return `nextCursor`, `hasMore`, `sort`, and `limit`. KYC updates, risk-tag create/resolve actions, and AML case create/status updates are local admin write operations that require `admin.compliance.write` and a matching approved admin approval request.

Export job data is stored in `gateway_admin_export_jobs`. Current export types are `USERS`, `LOGIN_LOGS`, `ADMIN_OPERATIONS`, `COMPLIANCE_USERS`, `ORDERS`, `TRIGGER_ORDERS`, `MATCH_TRADES`, `ACCOUNT_BALANCES`, `PRODUCT_BALANCES`, `POSITIONS`, `ACCOUNT_LEDGER`, `PRODUCT_LEDGER`, `PRODUCT_TRANSFERS`, and `ACCOUNT_ADJUSTMENTS`; results are stored as CSV with an expiry timestamp. Creating export jobs requires `admin.exports.write` and a matching approved admin approval request, while listing and downloading require `admin.exports.read`.

Core local admin lists use a common cursor paging contract. `/api/v1/admin/exports`, `/api/v1/admin/query-tasks`, `/api/v1/admin/approvals`, `/api/v1/admin/audit/login-logs`, and `/api/v1/admin/audit/operations` accept `limit`, `cursor`, and `sort`, keep their original list fields, and additionally return `nextCursor`, `hasMore`, `sort`, and `limit`. Export/query/approval lists support `requestedAt.desc` and `requestedAt.asc`; audit logs support `createdAt.desc` and `createdAt.asc`.

Long-running query task data is stored in `gateway_admin_query_tasks`. `/api/v1/admin/query-tasks` accepts only allowlisted `queryType` values: `SYSTEM_OPERATION_LATENCY`, `OUTBOX_BACKLOG`, `APPROVAL_BACKLOG`, `ALERT_DELIVERY_FAILURES`, `ORDER_AUDIT_SEARCH`, `TRIGGER_ORDER_AUDIT_SEARCH`, and `MATCH_TRADE_AUDIT_SEARCH`. Tasks run asynchronously and store JSON results, row counts, byte sizes, and failure messages. The order-audit query types return bounded JSON rows from `trading_orders`, `trading_trigger_orders`, and `trading_match_trades` with the same core filters used by export jobs. `/limits` exposes per-admin/global active quotas, per-window creation quota, retained result bytes, and expired tasks ready for archive. `/archive-expired` clears expired or age-selected result JSON while retaining metadata with `ARCHIVED` status, `archived_at`, and `archive_reason`. Creating tasks requires `admin.queries.write` and is blocked with HTTP 429 when quotas are exhausted; listing and detail lookup require `admin.queries.read`.

Account asset reports are exposed under `/api/v1/admin/reports/account-assets`:

- `GET /valuation`: current balance valuation by account type, user, and asset, with `limit/cursor/sort` keyset paging. Allowed sort values are `valuationValue.desc` and `valuationValue.asc`; responses include `nextCursor`, `hasMore`, `sort`, and `limit`.
- `POST /snapshots`: writes a daily aggregate snapshot into `gateway_admin_account_asset_snapshots`.
- `GET /snapshots`: queries saved daily snapshots with `limit/cursor/sort` keyset paging. Allowed sort values are `snapshotDate.desc` and `snapshotDate.asc`; responses keep `snapshots/count` and also return `nextCursor`, `hasMore`, `sort`, and `limit`.

The gateway also runs `AdminAccountAssetSnapshotScheduler`. It is controlled by `surprising.gateway.reports.account-asset-snapshots.*` and defaults to generating the previous UTC day's `USDT` snapshot at 00:05 UTC. After a scheduled snapshot is written, the scheduler compares account-type/asset totals with the previous day and raises a `SYSTEM` alert with metric key `ACCOUNT_ASSET_SNAPSHOT_DIFF_PPM` when the configured PPM or absolute threshold is exceeded. Matching alert channels receive normal delivery rows through the existing alert delivery worker.

System metrics under `/api/v1/admin/system/metrics` aggregate admin proxy request durations from
`gateway_admin_operation_logs.duration_ms`, returning overall and per-service `p50DurationMs`,
`p95DurationMs`, and `p99DurationMs`. The same duration field is included in `ADMIN_OPERATIONS` CSV exports.

System monitoring endpoints live under `/api/v1/admin/system`: `/routes` returns public and admin route configuration, `/health` probes backend `/actuator/health`, `/metrics` aggregates outbox backlog, admin API failure rate, failed logins, and approval backlog, and `/observability` aggregates Kafka consumer lag, WebSocket connection/subscription metrics, and backend `/actuator/prometheus` scrape status. These endpoints require `admin.system.read`. Kafka lag is disabled by default; enable it in production with `ADMIN_KAFKA_LAG_ENABLED=true` and `ADMIN_KAFKA_BOOTSTRAP_SERVERS`.

TraceId lookup lives at `/api/v1/admin/traces/{traceId}` and requires `admin.traces.read`. It builds a timeline from `trading_trigger_orders`, `trading_order_events`, `trading_match_results`, `trading_match_trades`, trading/account/risk outbox rows, `gateway_admin_operation_logs`, and consumed admin approval records.

Market health endpoints live under `/api/v1/admin/market`: `/health` aggregates index price, mark price, source components, candlestick freshness, source latency, and mark-index deviation by symbol. This endpoint requires `admin.market.read`.

Alert center endpoints live under `/api/v1/admin/alerts`: `/rules` manages alert rules, `/events` lists and acknowledges alert events, `/channels` manages notification channels, `/deliveries` lists and retries delivery records, and `/evaluate` evaluates fixed metric keys for current system, market, and trading state and writes events. Rule and channel lists support cursor paging with `updatedAt.desc`, `updatedAt.asc`, `createdAt.desc`, and `createdAt.asc`; event lists support `lastSeenAt.desc`, `lastSeenAt.asc`, `createdAt.desc`, and `createdAt.asc`; delivery lists support `createdAt.desc`, `createdAt.asc`, `updatedAt.desc`, and `updatedAt.asc`, returning `nextCursor`, `hasMore`, `sort`, and `limit`. Reads require `admin.alerts.read`; writes require `admin.alerts.write`; rule and channel create, update, enable, and disable actions also require a matching approved admin approval request by default. Triggered alerts enqueue `PENDING` deliveries by domain and minimum severity. The gateway includes `AdminAlertDeliveryWorker`, which claims deliveries with `FOR UPDATE SKIP LOCKED` and dispatches HTTP notifications. `WEBHOOK`, `SLACK`, and `PAGERDUTY` channels POST JSON to `endpoint`; 2xx responses mark `SENT`, failures retry according to `surprising.gateway.alerts.delivery-worker.max-attempts` and `retry-delay`, and exhausted attempts mark `FAILED`. `EMAIL` and `OPS` are marked `SKIPPED` until an external adapter is configured. Production deployments can tune the worker with `ADMIN_ALERT_DELIVERY_WORKER_ENABLED`, `ADMIN_ALERT_DELIVERY_WORKER_BATCH_SIZE`, `ADMIN_ALERT_DELIVERY_WORKER_POLL_DELAY_MS`, `ADMIN_ALERT_DELIVERY_WORKER_RETRY_DELAY`, and `ADMIN_ALERT_DELIVERY_WORKER_CLAIM_LEASE`.

Trading operations endpoints live under `/api/v1/admin/trading`: `/metrics` aggregates order submissions/rejections, trade volume, matching rejection rate, trigger-order state, position concentration, and symbol rankings over a bounded time window. Downstream `trading-orders` and `trading-trigger` admin proxy routes also support cursor paging for order, trigger-order, and match-trade detail lists. Order and trigger-order lists support `createdAt.desc` and `createdAt.asc`; match-trade lists support `eventTime.desc` and `eventTime.asc`; responses include `nextCursor` and `hasMore`. These endpoints require `admin.trading.read`.

Account admin proxy routes live under `/api/v1/admin/gateway/account`. The gateway validates the
admin Bearer token and `admin.gateway.account.read/write` permissions before forwarding to the
account service's `/api/v1/admin/accounts` namespace. `/ledger`, `/product-ledger`, `/transfers`,
and `/adjustments` support cursor paging with `limit`, `cursor`, and `sort`; supported sort values
are `createdAt.desc` and `createdAt.asc`, and responses keep the original list fields plus
`nextCursor`, `hasMore`, `sort`, and `limit`.

Wallet admin proxy routes live under `/api/v1/admin/gateway/wallet-admin`. The gateway validates
admin permissions and injects server-side Basic Auth for wallet-server. Wallet operations/finance
large lists (`/operations/addresses`, `/operations/balances`, `/operations/exceptions`,
`/finance/deposits`, `/finance/withdrawals`, and `/finance/withdrawal-reviews`) accept `limit`,
`cursor`, and `sort`. Primary lists support `updatedAt.desc` and `updatedAt.asc`; withdrawal review
audit supports `createdAt.desc` and `createdAt.asc`; responses include `nextCursor`, `hasMore`,
`sort`, and `limit`.

Funding admin queries are proxied through `/api/v1/admin/gateway/funding/admin`. `/rates/history`
supports `eventTime.desc` and `eventTime.asc`; `/payments` supports `createdAt.desc` and
`createdAt.asc`; both return `nextCursor`, `hasMore`, `sort`, and `limit`. Insurance-fund admin
proxy routes live under `/api/v1/admin/gateway/insurance-admin`; `/ledger` and `/coverages` support
`createdAt.desc` and `createdAt.asc` cursor paging. Fund adjustment remains a sensitive write and
requires approval.

Trading fee admin proxy routes live under `/api/v1/admin/gateway/trading-fees` and forward to the
trading provider's `/api/v1/admin/trading/fees` namespace. `/schedules` supports `updatedAt.desc`,
`updatedAt.asc`, `createdAt.desc`, `createdAt.asc`, `effectiveTime.desc`, and `effectiveTime.asc`;
`/tiers` supports `priority.desc` and `priority.asc`. Both return `nextCursor`, `hasMore`, `sort`,
and `limit`; fee and tier writes remain sensitive writes and require approval.

Risk, liquidation, and ADL admin lists use the same `limit/cursor/sort` response contract.
`/api/v1/admin/gateway/risk-admin/high-risk-accounts` and `/liquidation-candidates` support
`eventTime.desc` and `eventTime.asc`; `/api/v1/admin/gateway/liquidation-admin/orders` and
`/api/v1/admin/gateway/adl/admin/events` support `createdAt.desc` and `createdAt.asc`;
`/api/v1/admin/gateway/adl/admin/queue` uses the live-ranking cursor sort `priorityScorePpm.desc`.

Do not expose internal provider ports directly to the internet. Public clients should use:

- REST: `surprising-gateway`
- Realtime: `surprising-websocket` or an ingress route to `/ws/v1`

## Horizontal Scaling

- The gateway is stateless and can run behind any L4/L7 load balancer.
- Deploy at least two gateway instances.
- Use Kubernetes Services, service discovery, or config management to set backend `base-url` values per environment.
- Gateway nodes do not need sticky sessions for REST.
- Keep timeouts and retry policy conservative for order and account routes; duplicate POST retries should be driven by client idempotency keys, not blind gateway retries.
- The built-in HTTP client has explicit connect/read timeouts so a down backend cannot hang gateway worker threads indefinitely.

## Configuration

```yaml
surprising:
  gateway:
    security:
      user-id-header: X-User-Id
      require-identity-for-private-routes: true
      require-admin-mfa: true
      mfa-secret-encryption-key: ${GATEWAY_MFA_SECRET_ENCRYPTION_KEY}
    http-client:
      connect-timeout: 1s
      read-timeout: 30s
    routes:
      candlestick:
        base-url: http://surprising-candlestick:9081
        target-prefix: /api/v1/candlestick
        private-route: false
      account:
        base-url: http://surprising-account:9086
        target-prefix: /api/v1/accounts
        private-route: true
      trading-trigger:
        base-url: http://surprising-trigger:9095
        target-prefix: /api/v1/trading/trigger-orders
        private-route: true
```

## Build And Test

```bash
mvn -pl :surprising-gateway-provider -am test
mvn -pl :surprising-gateway-provider -am spring-boot:run
```
