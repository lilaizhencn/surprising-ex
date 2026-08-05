# 产品线划转运行手册 / Product Transfer Operations

## 范围 / Scope

面向用户的划转入口是 gateway：

`POST /api/v1/gateway/account/transfers`

Binance 兼容入口是：

`POST /sapi/v1/asset/transfer`

账户 provider 的 `/api/v1/accounts/transfers` 不是用户公开入口。账户 provider 按单产品线运行，不能独立完成跨产品线划转；该入口固定 fail-closed 并返回 HTTP 409，禁止回退到直接数据库余额写入。跨产品线流程由 gateway 负责编排，并通过各产品线的签名 `product-balance-adjustments` 命令进入用户分区 WAL/reducer。

The gateway owns the user-facing transfer flow. The account provider is a single-product-line command worker and must not implement cross-product transfers by writing balance tables directly. Its direct transfer contract always fails closed with HTTP 409 unless a future multi-account command lane is introduced.

## 账户类型 / Account types

| API account type | Provider product line |
| --- | --- |
| `FUNDING`, `SPOT` | `SPOT` (same underlying funding account) |
| `USDT_PERPETUAL` | `LINEAR_PERPETUAL` |
| `COIN_PERPETUAL` | `INVERSE_PERPETUAL` |
| `USDT_DELIVERY` | `LINEAR_DELIVERY` |
| `COIN_DELIVERY` | `INVERSE_DELIVERY` |
| `OPTION` | `OPTION` |

`FUNDING` 与 `SPOT` 不产生实际账户间移动，互转会被拒绝。每条产品线必须在 gateway 配置中显式提供非空的 `base-url`；`target-prefix` 可以继承 account 父路由，但解析后的最终路由仍必须完整，否则 fail-closed。

`FUNDING` and `SPOT` are aliases of the same underlying funding account and cannot be transferred between each other. Each product route must explicitly provide a non-blank `base-url`; `target-prefix` may inherit from the account parent route, but the resolved route must be complete or fail closed.

## 幂等与状态 / Idempotency and states

客户端必须发送 `Idempotency-Key`。同一用户的 key 只能对应一个请求指纹；复用 key 提交不同请求返回冲突。Binance 入口使用 `clientTranId`，其次使用 `clientOrderId` 或 `Idempotency-Key`。

The durable state machine is:

`PENDING -> SOURCE_DEBITED -> COMPLETED`

未知结果进入 `SOURCE_DEBIT_UNKNOWN`、`TARGET_CREDIT_UNKNOWN` 或 `COMPENSATION_REQUIRED`。这些状态不会向客户端报告成功。每个 provider 子操作使用 `gateway-transfer:<transferId>:debit|credit|compensate`，重试不会复用其他 transfer 的 command reference。状态转换使用 expected-status 条件更新，状态事件写入 `gateway_product_transfer_events`。

## 内部账户接口 / Internal account endpoint

Gateway 调用每条产品线路由的：

`POST /api/v1/accounts/admin/product-balance-adjustments`

请求头包含：

- `X-Internal-Service: surprising-gateway`
- `X-Internal-Audience: /api/v1/accounts/admin/product-balance-adjustments`
- `X-Internal-Timestamp`
- `X-Internal-Signature`

Signature uses HMAC-SHA256 over the ordered, length-prefixed UTF-8 fields: service, audience, timestamp, user ID, account type, asset, amount units, reference ID, and reason. The account provider validates the audience, timestamp window, and shared secret before submitting the user-partition command.

## 恢复与部署 / Recovery and deployment

全新数据库先执行 `init.sql`。已执行过旧版 `20260805_gateway_product_transfer.sql` 的环境还必须执行 `migrations/20260806_gateway_product_transfer_events.sql`，因为它是事件表的前向补充迁移；若新版本的 `20260805` 已包含事件表，执行 `20260806` 仍然安全且幂等。每个迁移文件都要由部署系统登记并按版本顺序执行，升级后验证事件表、外键和索引存在。Gateway 的 `ProductTransferReconciliationTask` 按 `GATEWAY_PRODUCT_TRANSFER_RECONCILIATION_DELAY` 和 `GATEWAY_PRODUCT_TRANSFER_RECONCILIATION_BATCH_SIZE` 扫描所有非终态并重试；生产必须为六条产品线路配置独立、可达且使用正确 product-line 的 account route。

For a new database, apply `init.sql`. For an installation that already recorded the old `20260805` migration, also apply `migrations/20260806_gateway_product_transfer_events.sql`; it is the forward-only event-table supplement. When the current `20260805` already contains the event table, applying `20260806` remains safe and idempotent. Register both files in the deployment migration ledger, apply them in version order, and verify the event table, foreign key, and index after upgrade. The reconciliation task scans every non-terminal state. Configure reachable, correctly product-scoped account routes for all six product lines and monitor `COMPENSATION_REQUIRED` as a financial incident.
