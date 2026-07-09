# surprising-gateway

[English](README.md)

面向前端和 BFF 的无状态 REST API 网关。

主流交易系统通常在边缘放一个统一 public gateway 或 BFF，各业务模块仍维护自己的内部 API 和配置。这个模块采用同样方式：前端只访问一个 gateway 前缀，gateway 只代理白名单里的内部服务。

## 模块

- `surprising-gateway-provider`：Spring Boot 白名单代理。

## 入口

- HTTP 端口：`9094`
- Gateway 前缀：`/api/v1/gateway/{service}`
- 后台 Gateway 前缀：`/api/v1/admin/gateway/{service}`
- 后台本地接口前缀：`/api/v1/admin/...`

示例：

```bash
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
curl 'http://localhost:9094/api/v1/gateway/trading-trigger/open?userId=1001&symbol=BTC-USDT' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
curl 'http://localhost:9094/api/v1/gateway/market-maker/strategies' -H 'X-User-Id: ops'
```

后台本地接口示例：

```bash
curl 'http://localhost:9094/api/v1/admin/users/1001/profile?settleAsset=USDT&limit=50' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/compliance/users/1001' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/exports' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/exports?limit=100&sort=requestedAt.desc' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/system/health' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/system/metrics?windowMinutes=60' \
  -H 'Authorization: Bearer <admin-token>'
```

## 路由

| Gateway service | 内部目标 | 私有 |
| --- | --- | --- |
| `instrument` | `http://localhost:9080/api/v1/instruments` | 否 |
| `candlestick` | `http://localhost:9081/api/v1/candlestick` | 否 |
| `price-index` | `http://localhost:9082/api/v1/price/index` | 否 |
| `price-fx` | `http://localhost:9082/api/v1/price/fx` | 否 |
| `price-mark` | `http://localhost:9083/api/v1/price/mark` | 否 |
| `trading` | `http://localhost:9084/api/v1/trading/orders` | 是 |
| `trading-market` | `http://localhost:9085/api/v1/trading/market` | 否 |
| `trading-trigger` | `http://localhost:9084/api/v1/trading/trigger-orders` | 是 |
| `account` | `http://localhost:9086/api/v1/accounts` | 是 |
| `risk` | `http://localhost:9088/api/v1/risk` | 是 |
| `liquidation` | `http://localhost:9088/api/v1/liquidations` | 是 |
| `funding` | `http://localhost:9089/api/v1/funding` | 否 |
| `insurance` | `http://localhost:9090/api/v1/insurance` | 是 |
| `adl` | `http://localhost:9091/api/v1/adl` | 是 |
| `market-maker` | `http://localhost:9096/api/v1/market-maker` | 是 |
| `wallet` | `http://localhost:8002/wallet/v1` | 是 |

Gateway 会拒绝未知 service 名称。它不会把用户输入拼成任意后端主机名，也不会处理任何动态表名。

## 安全模型

当前实现要求私有路由携带 `X-User-Id` 或 `Authorization`。生产环境应在 gateway 前完成认证，只有 token/session 验证通过后才注入可信 `X-User-Id`。
`X-Trace-Id` 所有路由都可以带；gateway 会清洗、回写给客户端并转发给后端 provider。它只用于可观测性和排障，不能参与认证或鉴权判断。

后台路径 `/api/v1/admin/...` 不使用普通前端的 `X-User-Id` fallback，只接受具备 `SUPPORT`、`ADMIN` 或 `SUPER_ADMIN` 的 Bearer Token，并继续用权限点限制实际访问范围。后台代理会向下游注入 `X-Admin-User-Id`、`X-Admin-Username`、`X-Admin-Roles`；客服只读接口 `/api/v1/admin/support/users/{userId}/overview` 聚合用户基础状态、KYC 摘要、资产、订单和风险快照，不返回会话治理、角色编辑或登录审计；客服工单接口 `/api/v1/admin/support/tickets` 支持工单查询、创建、备注时间线和状态变更，写操作要求 `admin.support.write`。用户详情聚合接口 `/api/v1/admin/users/{userId}/profile` 会聚合账户、订单、条件单、风险、会话和登录日志，并把下游失败作为局部错误返回。市场健康接口 `/api/v1/admin/market/health` 汇总行情源、index、mark 和 K 线新鲜度。告警中心接口 `/api/v1/admin/alerts` 管理规则、事件、手动评估和事件确认。交易运营聚合接口 `/api/v1/admin/trading/metrics` 汇总订单、成交、撮合、条件单、持仓和 Symbol 指标。账户资产报表接口 `/api/v1/admin/reports/account-assets` 提供跨币种估值和日终快照。合规风控接口 `/api/v1/admin/compliance/...` 管理 KYC 档案、风险标签和 AML case。风控后台代理服务名 `risk-admin` 转发到 `/api/v1/admin/risk`，用于规则覆盖、高风险账户聚合和爆仓候选后台分页查询；强平后台代理服务名 `liquidation-admin` 转发到 `/api/v1/admin/liquidations`，用于强平订单分页、候选时间线和候选取消运营动作。导出任务接口 `/api/v1/admin/exports` 创建、查询和下载后台 CSV 导出；长查询任务接口 `/api/v1/admin/query-tasks` 创建、查询和查看受控 JSON 查询结果。管理员 TOTP 2FA 可通过 `/api/v1/admin/security/mfa` 绑定、确认和关闭，生产环境可设置 `surprising.gateway.security.require-admin-mfa=true` 强制管理员登录提供动态码。

用户列表 `GET /api/v1/admin/users` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`；用户状态和角色写操作仍属于敏感操作，需要审批单。
会话列表 `GET /api/v1/admin/sessions` 与 `GET /api/v1/admin/users/{userId}/sessions` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`；撤销会话仍属于敏感操作，需要审批单。

客服工单列表 `GET /api/v1/admin/support/tickets` 支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc` 游标分页；工单备注时间线 `GET /api/v1/admin/support/tickets/{ticketId}/notes` 支持 `createdAt.asc`、`createdAt.desc` 游标分页；响应均返回 `nextCursor`、`hasMore`、`sort`、`limit`。创建工单、追加备注和状态变更需要 `admin.support.write`。

做市后台代理服务名 `market-maker` 转发到 `/api/v1/admin/market-maker`，覆盖策略状态、报价质量指标、策略参数覆盖、做市收益归因和策略运行日志。`/strategy-logs` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，返回 `nextCursor`、`hasMore`、`sort`、`limit`。

权限点 RBAC 由 `gateway_permissions` 和 `gateway_role_permissions` 驱动。gateway 会对本地 admin 路径校验 `admin.support.read`、`admin.users.read/write`、`admin.audit.read`、`admin.market.read`、`admin.alerts.read/write`、`admin.trading.read`、`admin.reports.read/write`、`admin.compliance.read/write`、`admin.exports.read/write`、`admin.queries.read/write`、`admin.permissions.write` 等权限，对后台代理路径校验 `admin.gateway.{service}.read/write`。角色和权限点接口位于 `/api/v1/admin/roles` 与 `/api/v1/admin/permissions`。`SUPER_ADMIN` 默认拥有 `admin.*`，`ADMIN` 默认拥有当前运营权限但不能修改权限点，`SUPPORT` 默认只拥有 `admin.support.read` 和 `admin.security.mfa`。

合规风控数据落在 `gateway_user_kyc_profiles`、`gateway_user_risk_tags`、`gateway_user_aml_cases`。合规用户列表 `GET /api/v1/admin/compliance/users` 支持 `updatedAt.desc`、`updatedAt.asc` 游标分页；风险标签列表支持 `createdAt.desc`、`createdAt.asc`、`updatedAt.desc`、`updatedAt.asc`；AML case 列表支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`。响应返回 `nextCursor`、`hasMore`、`sort`、`limit`。KYC 更新、风险标签创建/解除、AML case 创建/状态更新均属于本地后台写操作，需要 `admin.compliance.write` 权限和匹配的已批准审批单。

导出任务数据落在 `gateway_admin_export_jobs`。当前支持 `USERS`、`LOGIN_LOGS`、`ADMIN_OPERATIONS`、`COMPLIANCE_USERS`、`ORDERS`、`TRIGGER_ORDERS`、`MATCH_TRADES`、`ACCOUNT_BALANCES`、`PRODUCT_BALANCES`、`POSITIONS`、`ACCOUNT_LEDGER`、`PRODUCT_LEDGER`、`PRODUCT_TRANSFERS`、`ACCOUNT_ADJUSTMENTS`，结果以 CSV 保存并设置过期时间；创建导出任务需要 `admin.exports.write` 权限和匹配的已批准审批单，查询和下载需要 `admin.exports.read`。

gateway 本地核心后台列表使用统一游标分页协议：`/api/v1/admin/exports`、`/api/v1/admin/query-tasks`、`/api/v1/admin/approvals`、`/api/v1/admin/audit/login-logs` 和 `/api/v1/admin/audit/operations` 支持 `limit`、`cursor`、`sort`，响应保留原列表字段并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。导出/查询/审批列表支持 `requestedAt.desc`、`requestedAt.asc`；审计日志支持 `createdAt.desc`、`createdAt.asc`。

长查询任务数据落在 `gateway_admin_query_tasks`。`/api/v1/admin/query-tasks` 只允许白名单 `queryType`，当前支持 `SYSTEM_OPERATION_LATENCY`、`OUTBOX_BACKLOG`、`APPROVAL_BACKLOG`、`ALERT_DELIVERY_FAILURES`、`ORDER_AUDIT_SEARCH`、`TRIGGER_ORDER_AUDIT_SEARCH`、`MATCH_TRADE_AUDIT_SEARCH`，异步执行后把 JSON 结果、行数、字节数和错误原因落库。订单审计类 queryType 会用和导出任务一致的核心过滤字段，从 `trading_orders`、`trading_trigger_orders` 和 `trading_match_trades` 返回受控 JSON 明细。`/limits` 暴露单管理员/全局活跃任务配额、窗口创建配额、保留结果字节数和可归档任务数；`/archive-expired` 会清空已过期或指定完成天数之前的结果 JSON，保留任务元数据并标记 `ARCHIVED`、`archived_at` 和 `archive_reason`。创建需要 `admin.queries.write`，配额耗尽返回 HTTP 429；查询详情需要 `admin.queries.read`。

账户资产报表接口位于 `/api/v1/admin/reports/account-assets`：

- `GET /valuation`：按账户类型、用户和资产查询当前余额估值，支持 `valuationValue.desc`、`valuationValue.asc` 的 `limit/cursor/sort` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`。
- `POST /snapshots`：把日终聚合快照写入 `gateway_admin_account_asset_snapshots`。
- `GET /snapshots`：查询已保存的日终快照，支持 `limit/cursor/sort` 游标分页，排序白名单为 `snapshotDate.desc`、`snapshotDate.asc`，响应保留 `snapshots/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

gateway 还内置 `AdminAccountAssetSnapshotScheduler`，由 `surprising.gateway.reports.account-asset-snapshots.*` 配置控制，默认在 UTC 00:05 生成前一日 `USDT` 估值快照。调度写入快照后会按账户类型/资产对比上一日总值，超过 PPM 或绝对值阈值时写入 `SYSTEM` 告警，metric key 为 `ACCOUNT_ASSET_SNAPSHOT_DIFF_PPM`，匹配的通知渠道会继续走现有告警投递 worker。

系统指标接口 `/api/v1/admin/system/metrics` 会从 `gateway_admin_operation_logs.duration_ms` 聚合后台代理请求耗时，返回总体和按 service 的 `p50DurationMs`、`p95DurationMs`、`p99DurationMs`，同时这些字段会进入 `ADMIN_OPERATIONS` CSV 导出。

系统监控接口位于 `/api/v1/admin/system`：`/routes` 返回普通和后台路由配置，`/health` 统一巡检后端 `/actuator/health`，`/metrics` 聚合 outbox backlog、后台 API 错误率、登录失败和审批积压，`/observability` 聚合 Kafka consumer lag、WebSocket 连接/订阅指标和各后端 `/actuator/prometheus` 抓取状态。这些接口需要 `admin.system.read`。Kafka lag 默认关闭，生产可通过 `ADMIN_KAFKA_LAG_ENABLED=true` 和 `ADMIN_KAFKA_BOOTSTRAP_SERVERS` 开启。

TraceId 查询接口位于 `/api/v1/admin/traces/{traceId}`，使用 `admin.traces.read` 权限，按时间线聚合 `trading_trigger_orders`、`trading_order_events`、`trading_match_results`、`trading_match_trades`、交易/account/risk outbox、`gateway_admin_operation_logs` 和审批消费记录。

市场健康接口位于 `/api/v1/admin/market`：`/health` 按产品聚合 index price、mark price、行情源 component、K 线更新时间、源延迟和 mark-index 偏离。该接口需要 `admin.market.read`。

告警中心接口位于 `/api/v1/admin/alerts`：`/rules` 管理告警规则，`/events` 查询和确认告警事件，`/channels` 管理通知渠道，`/deliveries` 查询和重试投递记录，`/evaluate` 按固定 metric key 手动评估当前系统/市场/交易状态并写入事件。规则和通知渠道支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc` 游标分页；事件列表支持 `lastSeenAt.desc`、`lastSeenAt.asc`、`createdAt.desc`、`createdAt.asc` 游标分页；投递记录支持 `createdAt.desc`、`createdAt.asc`、`updatedAt.desc`、`updatedAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`。读接口需要 `admin.alerts.read`，写接口需要 `admin.alerts.write`；规则和渠道新增、更新、启用和停用默认还需要匹配的已批准审批单。触发告警会按域和最小级别为启用渠道创建 `PENDING` 投递记录，gateway 内置 `AdminAlertDeliveryWorker` 使用 `FOR UPDATE SKIP LOCKED` 领取任务并发送 HTTP 通知。`WEBHOOK`、`SLACK`、`PAGERDUTY` 渠道会向 `endpoint` POST JSON，2xx 标记 `SENT`，失败按 `surprising.gateway.alerts.delivery-worker.max-attempts` 和 `retry-delay` 重试，耗尽后标记 `FAILED`；`EMAIL` 和 `OPS` 在未配置外部适配器时标记 `SKIPPED`。生产可通过 `ADMIN_ALERT_DELIVERY_WORKER_ENABLED`、`ADMIN_ALERT_DELIVERY_WORKER_BATCH_SIZE`、`ADMIN_ALERT_DELIVERY_WORKER_POLL_DELAY_MS`、`ADMIN_ALERT_DELIVERY_WORKER_RETRY_DELAY` 和 `ADMIN_ALERT_DELIVERY_WORKER_CLAIM_LEASE` 调整 worker。

交易运营接口位于 `/api/v1/admin/trading`：`/metrics` 按时间窗口聚合订单提交/拒绝、成交量、撮合拒绝率、条件单状态、持仓集中度和 Symbol 运营排名。下游 `trading-orders` 和 `trading-trigger` 后台服务路由还支持订单、条件单和成交明细游标分页：订单/条件单列表支持 `createdAt.desc`、`createdAt.asc`，成交列表支持 `eventTime.desc`、`eventTime.asc`，响应会返回 `nextCursor` 和 `hasMore`。这些接口需要 `admin.trading.read`。

账户后台代理路由位于 `/api/v1/admin/gateway/account`，由 gateway 校验后台 Bearer Token 和 `admin.gateway.account.read/write` 权限后转发到 account 服务的 `/api/v1/admin/accounts`。`/ledger`、`/product-ledger`、`/transfers` 和 `/adjustments` 支持 `limit`、`cursor`、`sort` 游标分页，排序白名单为 `createdAt.desc`、`createdAt.asc`，响应保留原列表字段并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

钱包后台代理路由位于 `/api/v1/admin/gateway/wallet-admin`，gateway 校验后台权限后为 wallet-server 注入服务端 Basic Auth。wallet operations/finance 大列表 `/operations/addresses`、`/operations/balances`、`/operations/exceptions`、`/finance/deposits`、`/finance/withdrawals` 和 `/finance/withdrawal-reviews` 支持 `limit`、`cursor`、`sort`；主列表支持 `updatedAt.desc`、`updatedAt.asc`，提现审核审计支持 `createdAt.desc`、`createdAt.asc`，响应会返回 `nextCursor`、`hasMore`、`sort`、`limit`。

资金费后台查询通过 `/api/v1/admin/gateway/funding/admin` 转发到 funding 服务，`/rates/history` 支持 `eventTime.desc`、`eventTime.asc`，`/payments` 支持 `createdAt.desc`、`createdAt.asc`，均返回 `nextCursor`、`hasMore`、`sort`、`limit`。保险基金后台代理路由位于 `/api/v1/admin/gateway/insurance-admin`，`/ledger` 和 `/coverages` 支持 `createdAt.desc`、`createdAt.asc` 游标分页；基金调整仍是敏感写操作，需要审批。

费率后台代理路由位于 `/api/v1/admin/gateway/trading-fees`，由 gateway 转发到 trading provider 的 `/api/v1/admin/trading/fees`。`/schedules` 支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`、`effectiveTime.desc`、`effectiveTime.asc`；`/tiers` 支持 `priority.desc`、`priority.asc`。两者均返回 `nextCursor`、`hasMore`、`sort`、`limit`，费率和档位写操作属于敏感写操作，需要审批。

风控、强平和 ADL 后台列表使用同一 `limit/cursor/sort` 响应约定。`/api/v1/admin/gateway/risk-admin/high-risk-accounts` 与 `/liquidation-candidates` 支持 `eventTime.desc`、`eventTime.asc`；`/api/v1/admin/gateway/liquidation-admin/orders` 和 `/api/v1/admin/gateway/adl/admin/events` 支持 `createdAt.desc`、`createdAt.asc`；`/api/v1/admin/gateway/adl/admin/queue` 使用实时排名游标 `priorityScorePpm.desc`。

不要把内部 provider 端口直接暴露到公网。公共客户端应使用：

- 开发/小规模部署：`surprising-edge-provider` 的 `9094` 同时提供 REST 和 `/ws/v1`。
- 拆分生产部署：`surprising-gateway-provider` 提供 REST，`surprising-websocket-provider` 或 ingress 到 `/ws/v1` 提供实时推送。

## 水平扩展

- Gateway 是无状态服务，可以挂在任意 L4/L7 负载均衡器后面。
- Gateway 至少部署 2 个实例。
- 不同环境通过 Kubernetes Service、服务发现或配置中心设置后端 `base-url`。
- REST 不需要 sticky session。
- 订单和账户路由的超时与重试策略要保守；重复 POST 应依赖客户端幂等键，而不是 gateway 盲目重试。
- 内置 HTTP client 有明确的连接/读取超时，避免后端故障时无限占用 gateway 工作线程。

## 配置

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
        base-url: http://surprising-trading-entry:9084
        target-prefix: /api/v1/trading/trigger-orders
        private-route: true
```

## 构建和测试

```bash
mvn -pl :surprising-gateway-provider -am test
mvn -pl :surprising-gateway-provider -am spring-boot:run
```
