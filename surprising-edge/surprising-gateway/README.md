# surprising-gateway


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
curl 'http://localhost:9094/api/v1/admin/support/users/1001/overview' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/compliance/users/1001' \
  -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/system/health' \
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

后台路径 `/api/v1/admin/...` 不使用普通前端的 `X-User-Id` fallback，只接受具备 `SUPPORT`、`ADMIN` 或 `SUPER_ADMIN` 的 Bearer Token，并继续用权限点限制实际访问范围。后台代理会向下游注入 `X-Admin-User-Id`、`X-Admin-Username`、`X-Admin-Roles`；客服只读接口 `/api/v1/admin/support/users/{userId}/overview` 只聚合 gateway 本地用户状态和合规摘要，不查询账户、订单、成交或风险在线服务；客服工单接口 `/api/v1/admin/support/tickets` 支持工单查询、创建、备注时间线和状态变更，写操作要求 `admin.support.write`。原跨域用户详情接口 `/api/v1/admin/users/{userId}/profile` 已移除。合规风控接口 `/api/v1/admin/compliance/...` 管理 KYC 档案、风险标签和 AML case。风控后台代理服务名 `risk-admin` 转发到 `/api/v1/admin/risk`，仅用于规则覆盖和爆仓候选后台分页查询。强平后台代理服务名 `liquidation-admin` 转发到 `/api/v1/admin/liquidations`，用于强平订单分页和候选取消运营动作。管理员 TOTP 2FA 可通过 `/api/v1/admin/security/mfa` 绑定、确认和关闭，生产环境可设置 `surprising.gateway.security.require-admin-mfa=true` 强制管理员登录提供动态码。

用户列表 `GET /api/v1/admin/users` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`；用户状态和角色写操作仍属于敏感操作，需要审批单。
会话列表 `GET /api/v1/admin/sessions` 与 `GET /api/v1/admin/users/{userId}/sessions` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`；撤销会话仍属于敏感操作，需要审批单。

客服工单列表 `GET /api/v1/admin/support/tickets` 支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc` 游标分页；工单备注时间线 `GET /api/v1/admin/support/tickets/{ticketId}/notes` 支持 `createdAt.asc`、`createdAt.desc` 游标分页；响应均返回 `nextCursor`、`hasMore`、`sort`、`limit`。创建工单、追加备注和状态变更需要 `admin.support.write`。

做市后台代理服务名 `market-maker` 转发到 `/api/v1/admin/market-maker`，覆盖策略状态、报价质量指标、策略参数覆盖、做市收益归因和策略运行日志。`/strategy-logs` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，返回 `nextCursor`、`hasMore`、`sort`、`limit`。

权限点 RBAC 由 `gateway_permissions` 和 `gateway_role_permissions` 驱动。gateway 会对本地 admin 路径校验 `admin.support.read`、`admin.users.read/write`、`admin.audit.read`、`admin.compliance.read/write`、`admin.permissions.write` 等权限，对后台代理路径校验 `admin.gateway.{service}.read/write`。角色和权限点接口位于 `/api/v1/admin/roles` 与 `/api/v1/admin/permissions`。`SUPER_ADMIN` 默认拥有 `admin.*`，`ADMIN` 默认拥有当前运营权限但不能修改权限点，`SUPPORT` 默认只拥有 `admin.support.read` 和 `admin.security.mfa`。

认证持久化按物理表拆分：用户、角色、权限、用户角色、角色权限、登录日志、MFA 和刷新会话分别由单表 Repository 负责，`AuthPersistenceService` 完成角色与权限聚合。客服工单和备注也分别落在单表 Repository，由 `SupportTicketService` 保证跨表写入事务。

合规风控数据落在 `gateway_user_kyc_profiles`、`gateway_user_risk_tags`、`gateway_user_aml_cases`。合规用户列表 `GET /api/v1/admin/compliance/users` 支持 `updatedAt.desc`、`updatedAt.asc` 游标分页；风险标签列表支持 `createdAt.desc`、`createdAt.asc`、`updatedAt.desc`、`updatedAt.asc`；AML case 列表支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`。响应返回 `nextCursor`、`hasMore`、`sort`、`limit`。KYC 更新、风险标签创建/解除、AML case 创建/状态更新均属于本地后台写操作，需要 `admin.compliance.write` 权限和匹配的已批准审批单。

用户 KYC 文件接口为 `POST /api/v1/compliance/kyc/documents`（multipart 字段 `documentType`、`file`）、`GET /api/v1/compliance/kyc/documents` 和 `GET /api/v1/compliance/kyc/documents/{documentId}`；提交 KYC 时必须携带已上传文件的 `documentIds`，服务端会校验文件归属、类型、大小、MIME 与文件头，并把 SHA-256 写入元数据。后台可通过对应的 `/api/v1/admin/compliance/users/{userId}/kyc/documents` 接口查看和读取文件，读取操作写入后台审计日志。文件存储默认关闭并 fail-closed；生产环境必须配置 `GATEWAY_KYC_DOCUMENTS_ENABLED=true`、`GATEWAY_KYC_DOCUMENTS_TYPE=s3`、bucket/endpoint/region/access-key/secret-key，或在受控开发环境使用 `filesystem` 类型。单文件默认上限为 15 MiB，可用 `GATEWAY_KYC_DOCUMENTS_MAX_FILE_SIZE_BYTES` 调整。

gateway 本地核心后台列表使用统一游标分页协议：`/api/v1/admin/approvals`、
`/api/v1/admin/audit/login-logs` 和 `/api/v1/admin/audit/operations` 支持 `limit`、`cursor`、
`sort`，响应保留原列表字段并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。
审批列表支持 `requestedAt.desc`、`requestedAt.asc`；审计日志支持
`createdAt.desc`、`createdAt.asc`。

跨表订单时间线、行情/交易运营指标、账户估值、资金对账和日终报表明确不在
gateway 中实现。后续 `surprising-finance-ops` 模块必须配置独立数据源和独立物理数据库，
通过领域事件、outbox 或受控 CDC 建立查询投影，禁止对交易主库执行报表 JOIN。

系统监控接口位于 `/api/v1/admin/system`：`/routes` 返回普通和后台路由配置，`/health` 统一巡检后端 `/actuator/health`，`/observability` 聚合 Kafka consumer lag、WebSocket 连接/订阅指标和各后端 `/actuator/prometheus` 抓取状态。这些接口需要 `admin.system.read`。Kafka lag 默认关闭，生产可通过 `ADMIN_KAFKA_LAG_ENABLED=true` 和 `ADMIN_KAFKA_BOOTSTRAP_SERVERS` 开启。依赖业务库聚合的 `/metrics` 与本地告警中心已移除；以后应从独立运营数据库或可观测性平台提供。

下游 `trading-orders` 和 `trading-trigger` 后台代理仍保留单一领域内、受限分页的客服操作明细。
跨领域时间线和聚合运营报表统一归属上述独立财务运营数据库。

账户后台代理路由位于 `/api/v1/admin/gateway/account`，由 gateway 校验后台 Bearer Token 和 `admin.gateway.account.read/write` 权限后转发到 account 服务的 `/api/v1/admin/accounts`。`/ledger`、`/product-ledger`、`/transfers` 和 `/adjustments` 支持 `limit`、`cursor`、`sort` 游标分页，排序白名单为 `createdAt.desc`、`createdAt.asc`，响应保留原列表字段并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。

钱包后台代理路由位于 `/api/v1/admin/gateway/wallet-admin`，gateway 校验后台权限后为 wallet-server 注入服务端 Basic Auth。wallet operations/finance 大列表 `/operations/addresses`、`/operations/balances`、`/operations/exceptions`、`/finance/deposits`、`/finance/withdrawals` 和 `/finance/withdrawal-reviews` 支持 `limit`、`cursor`、`sort`；主列表支持 `updatedAt.desc`、`updatedAt.asc`，提现审核审计支持 `createdAt.desc`、`createdAt.asc`，响应会返回 `nextCursor`、`hasMore`、`sort`、`limit`。

资金费后台查询通过 `/api/v1/admin/gateway/funding/admin` 转发到 funding 服务，`/rates/history` 支持 `eventTime.desc`、`eventTime.asc`，`/payments` 支持 `createdAt.desc`、`createdAt.asc`，均返回 `nextCursor`、`hasMore`、`sort`、`limit`。保险基金后台代理路由位于 `/api/v1/admin/gateway/insurance-admin`，`/ledger` 和 `/coverages` 支持 `createdAt.desc`、`createdAt.asc` 游标分页；基金调整仍是敏感写操作，需要审批。

费率后台代理路由位于 `/api/v1/admin/gateway/trading-fees`，由 gateway 转发到 trading provider 的 `/api/v1/admin/trading/fees`。`/schedules` 支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`、`effectiveTime.desc`、`effectiveTime.asc`；`/tiers` 支持 `priority.desc`、`priority.asc`。两者均返回 `nextCursor`、`hasMore`、`sort`、`limit`，费率和档位写操作属于敏感写操作，需要审批。

风控、强平和 ADL 后台单表列表使用同一 `limit/cursor/sort` 响应约定。
`/api/v1/admin/gateway/risk-admin/liquidation-candidates` 支持 `eventTime.desc`、`eventTime.asc`；
`/api/v1/admin/gateway/liquidation-admin/orders` 和 `/api/v1/admin/gateway/adl/admin/events`
支持 `createdAt.desc`、`createdAt.asc`；`/api/v1/admin/gateway/adl/admin/queue`
使用实时排名游标 `priorityScorePpm.desc`。高风险账户聚合不再查询交易主库，后续由财务运营系统的独立数据库提供。

不要把内部 provider 端口直接暴露到公网。公共客户端应使用：

- 开发/小规模部署：`surprising-edge-provider` 的 `9094` 同时提供 REST 和 `/ws/v1`。
- 拆分生产部署：`surprising-gateway-provider` 提供 REST，`surprising-websocket-provider` 或 ingress 到 `/ws/v1` 提供实时推送。

## 水平扩展

- Gateway 是无状态服务，可以挂在任意 L4/L7 负载均衡器后面。
- Gateway 至少部署 2 个实例。
- 不同环境通过内网负载均衡、DNS、服务发现或配置中心设置后端 `base-url`。
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
