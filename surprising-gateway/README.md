# surprising-gateway

本模块现为统一业务应用：身份在 `com.surprising.gateway.provider`，订单在
`com.surprising.trading`，账户在 `com.surprising.account.provider`，合约在
`com.surprising.instrument.provider`。四者共享一个启动入口、HTTP 端口及数据源；
Core 仍独立。公共接口继续经过 Gateway 的身份、审批与审计，再调用本地业务入口。
后台“产品与市场”维护产品线接入，数据库热加载，各自隔离连接、缓存、消费者和定时任务。
托管与划转直接调用对应产品账户方法，无需额外的 SPOT Gateway。
详见 [多产品 Gateway 配置与验证](../docs/multi-product-gateway.md) 和
[合并及部署说明](../docs/business-application-merge.md)。


面向前端和 BFF 的无状态 REST API 网关。

## 订单入口简化

`OrderService.place/placeCommand/placeCommandAsync` 在入口一次完成请求标准化、BBO 解析、
产品线/对冲方向与订单规则校验，再把最终请求交给 `AeronOrderCommandService` 编码提交。
校验通过后不再传递 `ValidationResult`、包装准备结果或维护平行校验结果列表；批量请求在
全部本地校验通过后才提交。试单继续返回规则版本及 Core 预检结果。

`OrderPlacementStateService.requireClosePosition` 从同一份 Core 用户状态校验产品线、仓位模式、
保证金模式与方向，直接返回非零有符号数量；平仓入口据此构造 reduce-only 订单。
`OrderMarginMath` 仅保留实际订单名义价值校验使用的价格上下界，资金及保证金裁决继续由 Core 承担。
同步命令结果的防御性载荷只读取一次，用于空值检查和解码，不改变命令 ID、回执或未知结果语义。

`ProductBusinessConfiguration` 显式注册必需的 `ValkeyUserQueries`。普通订单、余额、持仓及用户
触发单的当前查询读取 realtime 视图，不因发布开关关闭或视图不可用回查 Core。按订单 ID 查询
只读取目标 `ORDER:<id>` 与快照/水位元数据；活动视图内没有该普通订单时，只从 realtime 可靠
导出提交的 `core_order_projection` 读取 FILLED/CANCELED/REJECTED 终态，避免旧的 OPEN 投影
覆盖当前视图。按 clientOrderId 查询同样保留终态与 minExportSequence 检查。读视图未初始化、
过期、源 epoch 改变或水位不足直接报错；已提交历史投影的水位不足返回 PROJECTION_LAG。
后台跨用户普通订单查询与撤单预览复用提交投影，选中订单之后仍由 Core 裁决撤单。

用户命令回执查询入口是 `/api/v1/gateway/trading/commands/{commandId}`，网关将内部回执地址
改写为该路径并保留产品参数；`trading-orders` 仅属于后台服务路由。Web/client 已修正误用此名
导致的查询 404。全栈启动默认启用 realtime 和可靠提交导出。2026-10-19 单节点 FUSED 现货实测
确认真实回执 URL 可回查命令、已成交订单可按订单号和客户端订单号查询、余额与 Core 一致；
见 [查询验证记录](../docs/validation/public-realtime-query-20261019.json)。维护、恢复与交易预检控制
协议仍保留。算法委托用户查询已于 2026-10-20 迁入 realtime 提交投影，后台触发单等专用查询仍需单独梳理；见 [算法委托查询](../docs/algo-read-projection.md)。

## 用户安全与设备限制

`AuthService` 在登录、二次验证、刷新令牌和签发会话时校验当前账户的设备 ID 与客户端 IP。Web 客户端以 `X-Device-Id` 发送浏览器安装 ID；`GatewayRefreshSessionRepository` 将它保存在 `gateway_refresh_sessions.device_id`。历史会话没有设备 ID 时仍可按会话踢出。`ClientIpResolver` 仅信任配置的代理地址发送的转发头。部署新 Gateway 前，应先执行 `init.sql` 中 `gateway_refresh_sessions.device_id` 和 `gateway_user_access_blocks` 的增量 DDL，并配置可信代理 IP 列表。

`UserSecurityController` 提供 `/api/v1/security/devices`、`/ips` 列表，设备踢出，以及设备/IP 的禁止和解除接口。禁止操作需 `SECURITY_SETTINGS` 邮件验证码和已启用时的 TOTP 验证；禁止后立即撤销对应的活跃刷新会话。`AuthService.authenticateBearer` 拒绝被禁 IP 的现有会话请求，`GatewayApiKeyService` 也拒绝被禁 IP 的签名请求。禁止记录按用户隔离，设备 ID 是浏览器存储标识，清理浏览器存储会生成新 ID；如需物理设备级封禁，需要另接可信设备识别机制。

主流交易系统通常在边缘放一个统一 public gateway 或 BFF，各业务模块仍维护自己的内部 API 和配置。这个模块采用同样方式：前端只访问一个 gateway 前缀，gateway 只代理白名单里的内部服务。

## 模块

- `surprising-gateway`：Spring Boot 白名单代理、WebSocket 连接管理和 Kafka fanout。

## 入口

- HTTP 端口：`9094`
- WebSocket 路径：`/ws/v1`

`ClientConnection` 为每条连接保留一个串行发送虚拟线程和一个超时监视虚拟线程。
发送队列为空时阻塞等待；监视线程在空闲时无限等待，发送开始或完成时才被唤醒，
发送期间直接等待本次 deadline。发送状态和超时判定使用同一把短锁，socket 写入在锁外执行，
避免旧发送的 deadline 关闭新发送。队列溢出或写入超时仍只关闭该慢连接，不阻塞其他连接的 fanout。

2026-10-08 本地 HotSpot JDK 27 / Corretto 验证：200 条空闲连接、每次采样 2 秒，
同一临时程序分别加载修改前后的 `ClientConnection`，使用进程 CPU 时间及 JFR ExecutionSample。
CPU 时间从 2096ms 降至 9ms，采样窗口内 ExecutionSample 从 21 降至 0。
这是空闲连接的局部对比，不代表整机或交易吞吐的改善幅度；本轮临时 JFR、程序和日志在结果入档后清理。
回归覆盖空闲等待、发送完成、后续发送的新 deadline、阻塞发送超时、队列上限和慢连接隔离。

同轮 Aeron 1.53.3 实测，应用内嵌 Driver 在 DEDICATED 下有 conductor、sender、receiver、
network resolver 四个平台线程，SHARED 下合为一个。跨客户端、行情、生命周期、可靠成交导出、
Gateway 和做市模块的回归按最新结果去重后共 383 项：379 通过，4 项因未配置 PostgreSQL 集成环境跳过。
本次没有修改数据库逻辑，也未启动钱包服务、远端服务或六产品线在线压测；数据库集成及线上吞吐/P99 不在本轮验证范围内。
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
| `instrument` | `local:/api/v1/instruments` | 否 |
| `candlestick` | `http://localhost:9095/api/v1/candlestick` | 否 |
| `price-index` | `http://localhost:9082/api/v1/price/index` | 否 |
| `price-fx` | `http://localhost:9082/api/v1/price/fx` | 否 |
| `price-mark` | `http://localhost:9082/api/v1/price/mark` | 否 |
| `trading` | `local:/api/v1/trading/orders` | 是 |
| `trading-market` | 进程内盘口查询 | 否 |
| `trading-trigger` | `local:/api/v1/trading/trigger-orders` | 是 |
| `account` | `local:/api/v1/accounts` | 是 |
| `risk` | `http://localhost:9087/api/v1/risk` | 是 |
| `liquidation` | `http://localhost:9087/api/v1/liquidations` | 是 |
| `funding` | `http://localhost:9087/api/v1/funding` | 否 |
| `insurance` | `http://localhost:9087/api/v1/insurance` | 是 |
| `adl` | `http://localhost:9087/api/v1/adl` | 是 |
| `market-maker` | `http://localhost:9096/api/v1/market-maker` | 是 |
| `wallet` | `http://localhost:8002/wallet/v1` | 是 |

### 公共行情列表

`GET /api/v1/gateway/instrument/list?productLine=LINEAR_PERPETUAL&status=TRADING&includeMarketSummary=true&includeTrend=true`
由 `InstrumentRequestService.list` 一次返回合约配置与行情首屏摘要。
`MarketSummaryRepository` 从已关闭的 1 分钟 K 线读取最近 24 小时，给每个币对添加
`lastPrice`、`change24h`、`high24h`、`low24h`、`volume24h`（基础币）、
`quoteVolume24h`（计价币）和最多 32 个收盘价组成的 `trend`。无成交时 `trend` 为空，
其他摘要字段缺席；不伪造价格。趋势只在 HTTP 列表里返回，WebSocket 继续发送现有
逐笔、标记价等轻量增量，不发送趋势数组。合约 ID 若在多个产品线重复，当前 K 线表
无法区分归属，列表跳过该 ID 的摘要，避免跨产品线串价。
不需要行情摘要的订单与资产页面省略 `includeMarketSummary`，只接收合约配置。
交易页只带 `includeMarketSummary=true` 获取当前摘要，不接收趋势数组。

首页和 Markets 页只请求一次列表与精度，之后使用 WebSocket 更新实时价格；
交易页只请求所选产品线、所选合约和所选周期的 K 线历史。

Gateway 会拒绝未知 service 名称。它不会把用户输入拼成任意后端主机名，也不会处理任何动态表名。

## 安全模型

当前实现要求私有路由携带经过校验的 `Authorization: Bearer`。gateway 完成认证后向下游注入可信 `X-User-Id`。

后台路径 `/api/v1/admin/...` 只接受具备 `SUPPORT`、`ADMIN` 或 `SUPER_ADMIN` 的 Bearer Token，并继续用权限点限制实际访问范围。后台代理会向下游注入 `X-Admin-User-Id`、`X-Admin-Username`、`X-Admin-Roles`；客服只读接口 `/api/v1/admin/support/users/{userId}/overview` 只聚合 gateway 本地用户状态和合规摘要，不查询账户、订单、成交或风险在线服务；客服工单接口 `/api/v1/admin/support/tickets` 支持工单查询、创建、备注时间线和状态变更，写操作要求 `admin.support.write`。原跨域用户详情接口 `/api/v1/admin/users/{userId}/profile` 已移除。合规风控接口 `/api/v1/admin/compliance/...` 管理 KYC 档案、风险标签和 AML case。风控后台代理服务名 `risk-admin` 转发到 `/api/v1/admin/risk`，仅用于规则覆盖和爆仓候选后台分页查询。强平后台代理服务名 `liquidation-admin` 转发到 `/api/v1/admin/liquidations`，用于强平订单分页和候选取消运营动作。管理员 TOTP 2FA 可通过 `/api/v1/admin/security/mfa` 绑定、确认和关闭，生产环境可设置 `surprising.gateway.security.require-admin-mfa=true` 强制管理员登录提供动态码。

用户列表 `GET /api/v1/admin/users` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`；用户状态和角色写操作仍属于敏感操作，需要审批单。
会话列表 `GET /api/v1/admin/sessions` 与 `GET /api/v1/admin/users/{userId}/sessions` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，响应返回 `nextCursor`、`hasMore`、`sort`、`limit`；撤销会话仍属于敏感操作，需要审批单。

客服工单列表 `GET /api/v1/admin/support/tickets` 支持 `updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc` 游标分页；工单备注时间线 `GET /api/v1/admin/support/tickets/{ticketId}/notes` 支持 `createdAt.asc`、`createdAt.desc` 游标分页；响应均返回 `nextCursor`、`hasMore`、`sort`、`limit`。创建工单、追加备注和状态变更需要 `admin.support.write`。

做市后台代理服务名 `market-maker` 转发到 `/api/v1/admin/market-maker`，覆盖策略状态、报价质量指标、策略参数覆盖、做市收益归因和策略运行日志。`/strategy-logs` 支持 `createdAt.desc`、`createdAt.asc` 游标分页，返回 `nextCursor`、`hasMore`、`sort`、`limit`。

内部做市账户的杠杆由管理员通过 `POST /api/v1/admin/gateway/trading-leverage/settings` 设置。网关校验管理员身份和高风险写审批，并由 `TradingLocalRoutes -> LeverageRequestService -> LeverageService` 沿用合约、产品线及杠杆范围校验，最终交给 Core 的 `UPDATE_LEVERAGE` 命令；普通用户路由仍只允许修改自己的杠杆。Core 有挂单或持仓时会拒绝改杠杆，清理敞口后可重试；每次尝试使用新的命令 ID，避免旧拒绝结果阻止重试。

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
gateway 中实现。后续财务运营模块必须配置独立数据源和独立物理数据库，
通过领域事件、outbox 或受控 CDC 建立查询投影，禁止对交易主库执行报表 JOIN。

系统管理接口位于 `/api/v1/admin/system`：`/routes` 返回普通和后台路由配置，`/health` 统一巡检后端 `/actuator/health`。这些接口需要 `admin.system.read`。后台 Kafka lag、WebSocket 连接指标和 Prometheus 抓取不在 Gateway 中实现，应由独立可观测性平台提供。

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

- 开发/生产部署：`surprising-gateway` 的 `9094` 同时提供 REST 和 `/ws/v1` 实时推送。

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
        base-url: http://surprising-realtime:9095
        target-prefix: /api/v1/candlestick
        private-route: false
      account:
        base-url: "local:"
        target-prefix: /api/v1/accounts
        private-route: true
      trading-trigger:
        base-url: "local:"
        target-prefix: /api/v1/trading/trigger-orders
        private-route: true
```

## 构建和测试

```bash
mvn -pl :surprising-gateway -am test
mvn -pl :surprising-gateway -am spring-boot:run
```


## 内嵌应用侧 Aeron MediaDriver（2026-09-22）

`config/GatewayMediaDriverConfiguration.java` 在 `surprising.realtime.enabled=true` 时，
于 gateway JVM 内启动 `appMediaDriver`，模式固定 SHARED。
必填 `surprising.realtime.directory`（环境变量 `SURPRISING_REALTIME_DIRECTORY`）；
统一脚本从 `APP_AERON_DIR` 设置，price/realtime 继续连接相同目录。
关闭实时功能不创建 Driver，也不要求配置该目录。

`RealtimeWebSocketBridge` 明确依赖此 Driver：先创建 Driver，再启动接收器；Spring 关闭时先关闭接收器，再关闭 Driver。
不强制删除现有目录，Aeron 检查心跳后拒绝覆盖活跃 Driver；正常重启复用目录，启动失败也由 Spring 清理已创建资源。
Core 保持原有 Driver 生命周期，交易命令不迁移到实时应用驱动。

`product/ProductBusinessConfiguration.productCommandMediaDriver` 为每条启用产品线创建一个命令驱动。
账户、订单、条件单、维护四个业务池借用此驱动，保留四套独立队列、会话、来源标识和容量。
Spring 先销毁依赖该驱动的业务池，再关闭驱动；关闭一个池不会关闭其他池的传输。
不同产品容器使用独立目录和驱动，热下线仅释放对应产品的资源。单永续 Gateway 为一个命令驱动加一个实时驱动。

迁移先停止旧独立 app-media-driver，再启动新 gateway；同目录只允许一个所有者。
启动顺序为 Core → gateway（内嵌 Driver）→ price → realtime（含成交导出/K 线）→ derivatives-lifecycle → maker。
完整 U 永续单节点为 6 个 Java 进程，不包含 PostgreSQL/Kafka/Valkey。
gateway 重启时，同机共享该 Driver 的实时链路会暂时断开；共享 JVM 的 GC/CPU/内存故障域也扩大，未据此承诺性能提升。

验证：HotSpot Corretto JDK 27 / Maven 3.9.16。gateway 525 项通过、34 项外部环境条件跳过；
realtime API 7 项和 realtime provider 47 项通过，合计 **579 通过、34 跳过、无失败**。
7 项新 Driver 测试覆盖开关/必填目录、跨 JVM 的 IPC/UDP、WebSocket 关闭顺序、活跃目录排他及重启重连。
24 组产品线/开关 dry-run 通过，U 永续均为 6 个 JVM；打包确认包含内嵌 Driver 配置且不包含 Core service 运行依赖。
未测完整部署、共享 JVM 内存预算、持续吞吐及 p99，不能据此声称零性能影响。
命令、逐类结果/跳过原因和 JAR SHA-256 见
[验证摘要](../docs/validation/gateway-media-driver-20260922.json)。本轮测试进程已结束，临时日志及已汇总报告已清理。


## 合并后冗余配置清理（2026-09-22）

账户包没有业务读者的合约快照已整体删除：`InstrumentSnapshotConfiguration`、
`InstrumentSnapshotInitializer`、`InstrumentSnapshotConsumer`；同时删除无读写方的 `PositionSnapshotConfiguration`。
账户查询和命令仍使用原 Core/本地服务路径，不新增另一份缓存。
订单包的 `orderInstrumentSnapshotCache`、启动加载和增量消费者仍用于下单规则、手续费与 `InstrumentCoreSyncService`，必须保留。

删除无 KafkaListener 引用的账户批量 listener factory 及其 concurrency 配置；
交割/行权使用的逐条确认 listener factory 和无限重试设置不变。
删除 YAML 中无对应字段的 account.cache / account.position-margin，
以及 account group-id/各业务 topic、trading fee-schedule-events-topic 的无效字段/setter（topic 实际始终按 ProductLine 生成）。
实际使用的 account.aeron 参数保留；内部凭证机制已于 2026-09-22 删除，Core 连接不变。

`initialize_database` 的数据库建表/种子初始化和 Core 合约注册仍是必要步骤，不能因为 instrument 合入 gateway 就删除。
其他独立进程的合约快照初始化也保留。

本轮验证：gateway 537 通过、34 外部环境条件跳过；derivatives-lifecycle 34 通过；合计 571 通过、34 跳过。
六产品验证仅加载一次订单快照/保留资产精度/空快照拒绝启动，五衍生品验证唯一消费者能更新共享合约状态。
构建包检查确认已删除类不再进入 JAR，必要的订单初始化和 Core 同步仍保留。
未测完整外部数据库/Kafka/Core 重启链路，未重新压测；Core 主链路和快照算法未改。
[逐类结果、命令和产物校验](../docs/validation/merged-config-cleanup-20260922.json)。测试进程已退出，本轮临时日志及已汇总报告已清理。

### 合并后的本地调用边界（2026-09-22）

公共请求统一从 `GatewayProxyController` / `BinanceApiController` 进入，完成鉴权后由本地路由绑定参数并调用 Service，不再调用其他 Controller。原订单、账户、合约等 Controller 的请求校验、业务编排和结果转换已迁到相应 `*RequestService`；重复的公共/管理 HTTP Controller 已删除。盘口、合约同步和 WebSocket 指标直接调用已有 Service/Registry；没有新增状态、接口或异步阶段。

独立 maker 仍需要 HTTP 接入，故保留 `OrderInternalController`、`AccountInternalController`、`MarketDataInternalController`，内部调用无需凭证。账户内部入口还服务跨产品线资金操作，已去掉不再使用的管理端别名。`InstrumentInternalController` 等原有内部查询/划转接口继续保留。管理 WebSocket 指标仅从 gateway 管理路由进入，原始 `/api/v1/admin/websocket/metrics` HTTP 映射已删除。

合并业务和 `websocket-admin` 默认路由均为 `local:`。`AdminSystemService` 将同 JVM 路由聚合为一次本地 `HealthEndpoint` 检查，返回实际健康状态，本地结果 `httpStatus` 为 null；远程服务仍通过 HTTP 探测。跨产品线现货资金接入必须显式配置远端地址。

测试以删除前的 81 个 API 路径作为本地分派契约基线，并检查 maker 的三个 Feign 契约均仍有内部 HTTP 映射。本轮不改变 Core 协议、事件可靠投递、产品线隔离或资金结算规则。

内部认证清理：删除 `BusinessEndpointConfiguration`、业务 token 请求头、账户内部 HMAC/时间戳/audience 校验及发送端签名。`BUSINESS_INTERNAL_TOKEN`、`ACCOUNT_INTERNAL_SERVICE_SECRET`、`GATEWAY_SPOT_ACCOUNT_INTERNAL_SECRET` 不再需要。内部控制器仍执行 DTO 校验、产品线检查和原有业务拒绝处理；公共用户 JWT、管理员权限/审批及外部托管钱包签名保持原有行为。

合并后仅本应用使用的账户、订单、条件单、费用和合约管理请求/响应类型已迁入本模块 `src/main/java/com/surprising/{account,trading,instrument}/api`。保留原包名和内容，HTTP/JSON 契约不变；跨进程共享类型仍来自对应 API 模块。

### 私有消息通配订阅

`SubscriptionRegistry` 的 `*` 仅用于选择订阅全部合约的连接。普通与带时间批量推送均保留事件本身的 `symbol`、`productLine` 和 `userId`，尤其成交回报不能把真实合约替换为 `*`，否则客户端无法选择数量单位或核对成交资金。

`LocalBusinessApi` 对 `CoreCommandOutcome.NotAcceptedException` 返回 503，明确请求未被交易核心接收；同步和异步入口一致处理，不自动重复下单。结果未知、业务拒绝和已接单的状态仍按各自契约返回。

## 前端运行能力

公开只读接口 `GET /api/v1/runtime` 返回当前整合网关实际服务的 `productLines`，来源为 `LocalBusinessApi.productLine()`，与账户和交易配置校验一致。前端据此订阅私有账户状态，避免等待未启用产品线的快照。该接口不包含账户数据或连接配置。

## 公开未平仓量推送

`OpenInterestPublisher` 服务当前 gateway 产品线的 `openInterest` WebSocket 频道。
订阅必须指定衍生品产品线与 symbol；其他产品线和现货订阅被拒绝。
存在订阅时每秒至多发起一轮已有 Core 未平仓量查询，多个币对/连接共享结果，前端无需 REST 轮询。
公开数量为各分片多/空总量的较大值（单边口径），不是多空相加或当前用户持仓；
`openInterestSteps`、`sequence` 以整数字符串输出。完整快照不存在该币对时为零，查询失败则
status=UNAVAILABLE，不用零代替失败。注册表拥有订阅生命周期，发布器不维护第二份持仓缓存。
该频道由 gateway 查询出口直接发布，不注册 Aeron 实时路由，不更改 Core 提交或 Kafka topic。

用户实际费率由私有路由 `GET /api/v1/gateway/trading-fees/effective?userId=...&symbol=...&productLine=...` 查询，映射到现有 `TradingFeeRequestService`。普通用户路由与管理员费率修改路由分离，沿用登录与账户归属校验。

## 两步登录与安全设置（2026-09-28）

`AuthService.login` 先校验账号密码，`LoginVerificationService.begin` 在用户行锁内读取已验证且已启用的登录因素。
无启用因素才直接返回 `AuthResponse`；否则只返回 `requiresVerification=true`、随机 `challengeToken`、5 分钟有效期和脱敏的 `methods`，不创建 access/refresh 会话。
第二步 `POST /api/v1/auth/login/verify` 提交 `{challengeToken,emailCode,phoneCode,totpCode}`，必须满足挑战记录中的全部方式。
验证码不可在第一步直接提交；前端在模态框中按服务器要求显示输入框，第二步成功后才保存会话。

安全设置接口统一为：

- `GET /api/v1/security/login-verification`：EMAIL、PHONE、TOTP 的绑定/开启状态；账户注册联系方式在绑定其他方式时必须验证，但不会自动开启登录二次验证。
- `POST /api/v1/security/login-verification/{method}/bind`：`{currentPassword,destination,enabled}`。开启/绑定要求密码、新绑定目标的验证码，以及另外两个已绑定方式的验证码，不受另外两个登录开关影响；关闭要求密码及目标自身验证码。省略 destination 使用已有绑定。
- `POST /api/v1/security/login-verification/{method}/confirm`：`{currentPassword,codes:{challengeToken,emailCode,phoneCode,totpCode}}`。目标地址、目标开关、必填方式均以服务端挑战为准，客户端不能在确认时改变。
- 首次 Google 绑定额外返回 secret/provisioningUri；前端只在本次弹窗展示二维码，不持久化密钥。已绑定的 Google 开关关闭后保留绑定，不允许未经验证覆盖密钥。
- 原用户和管理员 `/mfa/enroll`、`/mfa/confirm`、`/mfa/disable` HTTP 写入口已移除，避免绕过密码及交叉验证。管理员也使用上述统一设置接口，生产强制管理员 MFA 时不允许关闭 TOTP。

状态由 PostgreSQL `gateway_login_factors`、`gateway_login_challenges` 和现有 `gateway_user_mfa` 持有。
每用户每用途至多一条挑战，新挑战替换旧挑战，数据库只保存 token/验证码摘要；用户行锁将设置变更和验证串行化。
每用途 60 秒发送冷却、10 分钟最多 10 次发送/5 次失败；重发不清空当前窗口失败次数。
`VerificationFailure` 明确不回滚失败计数和已失效状态，真正的数据/会话写入异常仍整体回滚。
密码、账户状态、角色或验证设置变化会使旧挑战失效；Google 时间步在登录和设置验证之间共享一次性消费，阻止重放。

已有库先执行 `deployment/migrations/20260928-login-verification.sql`，再部署 gateway 与前端；新库已合并到 `init.sql`。
此迁移不修改交易资金、订单、持仓、行情或产品线状态。
短信使用 `SmsMessageSender` 供应商接口，本轮按要求不接服务商；无实现时 PHONE 的绑定和验证明确拒绝，不返回假验证码、不跳过因素。
邮件沿用 Resend 配置并增加连接/请求超时；没有有效邮件配置时不可完成需要邮箱验证码的操作。

本轮验证：HotSpot JDK 27 的 67 项认证/安全及直接关联测试全部通过（包括 4 项独立 PostgreSQL schema 测试，结束后删除 schema）；前端 90 项测试、lint/build 通过。
Chrome 桌面及 390px 手机检查双语三验证码弹窗、设置开关及导航；5174/9094 实际部署后使用独立临时账号验证两步 TOTP 登录、缺码拒绝、重放拒绝、设置密码检查、旧接口不能绕过，结束后删除该账号及认证记录。
没有向外部真实收件人发送验证码；邮件/短信发送失败和多因素逻辑以可控发送器测试，短信真实投递待服务商配置。
未重跑撮合/结算路径，因为仅修改认证域和前端认证页面，不改变交易协议、资金或持仓。运行证据位于本地 `verification/login-mfa-20260928/`。

## 币种主目录与充提网络

`com.surprising.asset` 在现有 Gateway MVC 中提供币种配置：`AssetConfigurationController` 校验管理员权限和原始请求审批，`AssetConfigurationService` 按币种加行锁、校验版本和业务规则，同一事务保存配置与审计。三个 Repository 分别管理 `assets`、`asset_networks`、`asset_configuration_changes`，没有新增运行时缓存。

- `GET/POST /api/v1/admin/assets`：币种列表及新增/修改；`listedOnly=true` 返回已上线且允许配置市场的币种。
- `GET/POST /api/v1/admin/assets/{assetId}/networks`：同一币种的多个网络配置。
- 写操作携带配置 `revision`、`reason`，使用现有 `X-Admin-Approval-Id` 审批；新建的 revision 为 0。旧版本写入返回 409。
- `assets.scale_units` 是账务精度唯一来源；删除钱包网关配置中的 `asset-scales-json`，充值入账和提现金额换算由币种目录读取。网络 `chain_decimals` 独立，网络金额必须同时可用账务单位与链上单位精确表达。
- 币种 ID、账务代码、账务精度不可变；显示名称和 Logo 可调整。网络 ID、币种归属、网络标识、合约和链上精度不可变。启用网络要求币种上线，下线币种前必须关闭其所有充提网络。
- 合约配置改用四个币种 ID；数据库外键拒绝不存在的币种，创建及重新启用市场时进一步校验上线资格。现有市场的交易状态仍由各产品线合约状态控制，币种目录开关不隐式清退持仓。

数据库定义统一位于根 `init.sql`。项目未上线，使用空库初始化目标结构，不在运行时保留名称身份兼容分支。


## 本机流动性运维接口

缺少后台交互账号的服务器运维使用 `/internal/v1/operations/liquidity`，复用正式业务服务，
无需伪造用户 JWT。默认关闭；显式配置 `SURPRISING_GATEWAY_OPERATIONS_TOKEN`（至少 32 字符）后，
请求须携带 `X-Operations-Token`，并从 127.0.0.1/::1 直连。带 Forwarded/X-Forwarded-For 的代理请求一律拒绝。
密钥只保存在服务器权限为 600 的运维配置中，不放在浏览器、仓库或日志里。

- POST `/balance-adjustments`：`ProductBalanceAdjustmentRequest`，必须指定当前产品线的 accountType、
  userId、asset、amountUnits、referenceId、reason。沿用 AccountCommandGateway 的确定性资金命令 ID。
  超时先查账户/命令结果；需重试时保持相同 referenceId 和金额，禁止换引用重复充值。
- POST `/leverage`：`LeverageSettingRequest`，显式 productLine、userId、instrumentId、marginMode、leveragePpm、reason。
- POST `/instruments?reason=...`：完整 `InstrumentUpsertRequest`，沿用 InstrumentService 的校验、变更记录和发布流程。
  contractType 必须属于当前服务产品线。只在核对当前配置后修改所需字段；网络超时先查 latest/changeId，避免重复版本发布。

杠杆及合约请求须携带 `X-Operation-Id`；业务引用及原因均为 1–128 字符。审计记录以
`SYSTEM:LIQUIDITY_OPERATIONS` 为操作者，写入 `gateway_admin_operation_logs`，执行前强制落下意图记录，
失败则不发业务命令；完成后记录结果。完成记录失败可能返回失败但命令已执行，资金重试仍由 Core 幂等保护。
金额、冻结、持仓仍由 Core 掌管，接口不直接写余额表，也不自动绕过保证金和风险档位。

后台管理员使用既有鉴权/审批入口；新增 GET `/api/v1/admin/gateway/trading-leverage/settings`
可按当前产品线读取指定做市账号的杠杆，不必冒充该用户。管理页面不使用上述运维密钥。

验证（JDK 27）：gateway 676 项，0 失败，42 项既有环境相关跳过；包含内部运维鉴权、
产品线隔离、审计失败时不发资金命令、资金引用透传及管理员读取杠杆。直接依赖 Core 1004 项，
0 失败，2 项既有跳过。未把跳过的数据库/外部环境测试记为通过；线上资金调整另做前后核对。


## 盘口推送编码复用（2026-10-08）

`SubscriptionRegistry.publishDepth` 在一次推送内，用 IdentityHashMap 为每个相同的不可变 CoreOrderBookView 基线复用差量计算和 JSON。null 基线统一生成首次快照；不同基线仍单独计算，不仅凭 sequence 推断内容相同。临时映射由当前调用持有，结束即释放，不跨币对、产品或推送保留第二份盘口状态。

每条连接仍单独检查版本、入有界队列；仅 send 成功后更新它的基线，失败按原规则移除慢连接。注册表同步边界保持不变，没有改变 websocket 协议或增加工作线程。

HotSpot JDK 27，对比 `243158a8` 的该方法与当前实现，使用相同 ObjectMapper、100 个成功接收的 stub 连接、每本盘口 100 档；预热 30 次，测量 200 次推送并录制最多 16MiB JFR。编码次数 20,000 → 200，调用线程分配量 606.521MB → 154.903MB，CPU 时间 832.942ms → 333.210ms。统计含 stub 连接调用成本，不含真实 socket、网络或客户端绘图，不能视为服务器整体 CPU 降幅。

新增测试验证相同基线只编码一次，新订阅者收到独立快照，下一轮共享新基线；原有断开/取消订阅、旧版本、慢连接、产品隔离测试通过。本轮未部署服务器或进行真实多用户长稳。临时 JFR 和程序汇总后清理。

### 做市周期批量输入与单项账户查询（2026-10-08）

`MakerQuoteInputsController` 在原产品线业务容器提供
`POST /internal/v1/trading/maker/quote-inputs`。请求明确产品线、永久合约 ID、合约版本、保证金模式和
1～64 个不重复账户；先检查产品归属及当前合约版本，再返回每个账户的库存与实时有效 maker 费率。
现货按基础资产权益/数量步长计算库存，其他产品读取 NET 持仓。保留账户费率覆盖和到期校验，
数据缺失或版本错配时整批失败，不用过期缓存补齐。返回顺序与请求账户一致，供做市在下单前整体校验。

`AccountService.balance/productBalance/position/positionMargin` 在启用实时读视图时使用目标字段读取，
不再为单项查询解码无关订单。全量查询沿用原入口；不可用读视图不会回查数据库或 Core。
六产品线 HTTP 测试使用真实 Redis 快照和真实 `AccountService`，验证库存单位、账户顺序和产品隔离；
有效费率在该测试中模拟，另由原 `TradingFeeServiceTest` / `FeeScheduleSnapshotCacheTest` 覆盖费率选择。

## realtime 盘口查询（2026-10-19）

`MatchingMarketDataService` 的 HTTP 盘口及 BBO 参考价使用 `ValkeyMarketData`，不再依赖
`OrderAeronGateway` 的 BOOK_STATE_QUERY。公共和内部 URL 不变，按产品/合约读取最多每侧
50 档的完整视图。无视图、超过 5 秒未刷新或 source epoch 已改变返回 503，不返回伪空盘口。
详细状态所有权、初始化和部署顺序见 [realtime README](../surprising-realtime/README.md#行情查询与交易-core-分离2026-10-19)。

## 现货撤单恢复查询（2026-10-19）

`OrderService.cancellation` 沿用实际撤单的 StableOrderIdentity，只向 `AeronOrderProjectionRepository` 查询 realtime 已提交回执，并验证类型为 CANCEL_ORDER。用户入口 `GET /api/v1/gateway/trading/cancellations/{orderId}` 与 MVC/local FUSED 路由一致，认证用户不能被 query 覆盖。200 确认、202 未知、503 不可用，no-store；不回查 Core、不重发撤单。两端提前持久保存原订单号，响应中断后通过该入口恢复。部署顺序、边界和本轮 145 项定向后端测试范围见 [说明](../docs/realtime-command-results.md)。

## 现货改单处理结果查询（2026-10-19）

`GET /api/v1/gateway/trading/amendments?clientRequestId=<原请求号>` 通过原产品与认证用户的 replacementCommandId 读取 realtime 已提交回执，并验证类型为 AMEND_ORDER。MVC/local FUSED 路由及路径清单同步，未知返回 202，不可用 503，no-store；不访问 Core。两端补齐 clientRequestId 并在发送前保存，响应丢失后只读恢复。先更新 gateway 再更新两端，沿用既有 SQL 回执表和历史回补；详见 [说明](../docs/realtime-command-results.md)。

## 诊断字段清理（2026-10-21）

HTTP、Kafka、WebSocket 和事件 DTO 已移除字符串追踪元数据；后台操作与审批保留管理员、请求摘要、业务引用、双人审批和一次性消费校验。新库使用 `init.sql`；已有库先停止旧版 gateway、maker、price 和 realtime，执行 `deployment/migrations/20261021-remove-trace-metadata.sql`，再启动新版。迁移只删除四个诊断字段及其依赖索引，保留业务行；删除后不能直接回滚旧版应用。详见 [运行诊断](../docs/observability/runtime-diagnostics.md)。

## 交易维护的请求、事务与调度

维护入口为 `com.surprising.trading.maintenance.controller.AdminMaintenanceController`，由现有
`TradingLocalRoutes` 本地分发，不增加 HTTP 路由。入口检查身份、产品头、游标和64位字符串参数，
转换为 `model.MaintenanceCommand`；Service检查现货限制、清场范围、价格组合和请求幂等身份。

`service.MaintenanceService` 在同一事务持有任务行锁，读取持久动作、执行一个Core命令，
再完成动作与推进任务。`repository.MaintenanceRepository` 仅访问任务表，
`repository.MaintenanceActionRepository` 仅访问动作表。动作以 `MaintenanceActionPlan` 保存最终
Core命令字节，重启或结果未知时仍使用原命令身份与原载荷；不依赖Core命令对象的JSON属性。
`task.MaintenanceTaskRunner` 保留原1000毫秒默认调度。维护池仍保留独立队列和来源标识，
六产品线任务不能混用。清场、撤单、部分成交续办、快照恢复与动作/任务写故障由
`MaintenanceIntegrationTest` 结合真实PostgreSQL和Core验证。

GatewayProxyService、OrderService与TriggerOrderService的依赖统一由唯一实际构造器传入。
产品选择、本地路由、当前查询视图及历史投影依赖以final字段持有；不使用后续反射填充或
省略依赖的生产便捷构造器。六产品Spring接线和查询不回Core的验证见
[网关注入验证](../docs/validation/java-mvc-injection-gateway-20261021.json)。

账户收益由PortfolioIncomeService在同一REPEATABLE_READ只读事务内聚合。
PortfolioIncomeFlowRepository只读资金流；PortfolioIncomeCutoffRepository关联截止前缀与用户权益，
完整前缀内缺少用户行才能证明零。PortfolioIncomeSpotPriceRepository关联已提交现货成交、
合约/资产单位与覆盖水位认证，限制同产品、历史时窗和提交前缀；价格时窗及收益估值归Service。
三类原生行归provider.model.PortfolioIncomeRows。并发补录与六产品历史读取验证见
[收益读取验证](../docs/validation/java-mvc-portfolio-readers-20261021.json)。

公共行情由MarketSummaryService聚合24小时涨跌幅、高低、成交量和最多32点趋势。
MarketSummaryRepository只批量提供已关闭K线与真实期初收盘价，并以instruments认证ID唯一产品归属；
跨产品重复ID不展示无产品键K线摘要。合约Repository关联assets只补充四类资产名称，写入与锁仍仅归合约表。
[行情读取验证](../docs/validation/java-mvc-market-readers-20261021.json)记录数据库边界与负例。

C2C运行配置与R2属性归c2c.config；产品业务容器配置归gateway.config，产品属性和健康Bean归provider.config。三个HTTP Filter归provider.interceptor，保留拦截顺序、缓存正文限制及权限/IP语义。验证见 [配置拦截归包记录](../docs/validation/java-mvc-infra-packages-20261021.json)。

C2C续办聚合归c2c.service.C2cSettlement，定时线程入口归c2c.task.C2cSettlementTask并保留专用调度器；合约同步、持仓推送和实时租约分别由trading.order.task及websocket.provider.task触发原Service。验证见 [定时职责记录](../docs/validation/java-mvc-task-boundaries-20261021.json)。

分页工具归account.api.utils、trading.api.utils和gateway.provider.auth.utils；金额工具归c2c.utils.C2cMoney。Repository仍只接收原有数据访问参数；Controller参数绑定的剩余整改另行跟踪。验证见 [工具归包记录](../docs/validation/java-mvc-utils-packages-20261021.json)。

C2C入口归c2c.controller，HTTP异常映射归controller.advice；业务聚合归service，持久化归repository。
异常归exception，输入与原生视图归model，金额/游标/响应字段工具归utils，聊天连接适配归websocket，
定时入口归task，配置归config。原始完整请求审批摘要、用户/产品范围、事务和权限检查保持。
[归包验证](../docs/validation/java-mvc-c2c-packages-20261021.json)记录包迁移与HTTP/数据库回归；HTTP业务边界仍按整改表继续处理。

认证模块按auth.controller、auth.service、auth.repository、auth.model、auth.client及auth.utils分工；
KYC/OIDC/邮件的协议接入不与业务聚合混放。LoginVerificationModels保存登录挑战及设置的九个请求/响应记录，
与AuthModels的sealed LoginResult同包，保持响应字段和校验注解；Service只引用模型，模型不反向依赖Service。
原登录状态、MFA失败计数、权限、审批摘要、幂等及事务顺序保持。
[认证归包验证](../docs/validation/java-mvc-auth-packages-20261021.json)包含433项实际回归与未测范围。

实时状态入口归websocket.provider.controller，条件单历史持久化归trading.trigger.repository；保留原路由和用户/产品筛选。验证见 [其余归包记录](../docs/validation/java-mvc-remaining-packages-20261021.json)。

合约目录本地分发仍由 `gateway.provider.local.InstrumentLocalRoutes` 调用 `instrument.provider.controller.InstrumentRequestController`，不新增内部HTTP路由。Controller解析产品选择、分页及HTTP异常；`InstrumentService.publicCatalog/publicLatest` 负责公开可见性和行情聚合。Repository接收原生 `InstrumentPageQuery`，SQL列只在Repository映射；合约编辑仍在原事务中持行锁检查版本并写配置/Outbox。

C2C后台的原始请求头、method/path/query、User-Agent和IP由 `c2c.controller.C2cAdminRequestBinding` 绑定为 `model.C2cAdminRequest`；审批正文保持原始字节摘要。Service只产生原生 `C2cProblem` 原因，`controller.advice.C2cHttpErrors` 映射HTTP并对未知内部异常脱敏。内部资金/对账状态故障保留PROCESSING回执供核查和原身份续办，不伪造为用户校验拒绝。

账户/订单/成交/条件单读视图未就绪、落后、过期或无效使用原生 `ReadViewUnavailableException`；公共本地分发由 `LocalBusinessApi` 映射原503响应，产品内部HTTP由 `ReadViewHttpErrors` / 行情Controller处理。C2C对账直接读取 `AccountService`，仍分别续租并读取托管/手续费账户，任一不可用都不能按零余额放行。

账户和交易本地协议请求组件统一位于各领域 `controller` 包（`AccountRequestController`、`OrderRequestController`、`LeverageRequestController`、`TradingFeeRequestController` 及管理/条件单请求组件）。它们以@Component共享内部HTTP与本地分发的校验/响应逻辑，不声明额外路由；状态变化、下单及查询业务由原Service承担。

划转安全Service的估值不可用/需要验证以原生 `ProductTransferSecurityException` 表示，网关协议入口维持503/428并阻止资金协调器调用。算法单已提交视图中不存在时由Service返回原生NoSuchElementException，订单请求Controller映射404；未就绪仍使用读视图不可用契约，不回退Core或命令存储。

算法单、成交及条件单Repository各读取一个实际业务投影；必要的初始化标记按同产品在同一条SQL中关联，用途已在 `readAssociated` 注释说明。未初始化返回原生不可用，健康空数据返回空页；标记和数据共享SQL快照，不分两次读取，排序在外层明确保持。原始请求绑定的剩余问题仍列于补充整改清单。

交易统计的 `TradingMetricsRepository` 只读取 `core_trading_statistics_snapshot` 并返回 `StoredTradingMetrics` 原生行。`TradingMetricsService` 校验版本、产品、位置、窗口、时间和计数后截取榜单；Controller校验请求范围。响应保留 `committed-trading-v1` 的完整JSON字段及大整数节点，未知统计字段不会被DTO转换丢弃，也不转double。

算法单查询的请求身份、分页、永久合约ID和客户端请求号由 `OrderRequestController` 校验/规范化；`AlgoOrderProjectionRepository` 接收原生查询条件，继续负责持久化父单事实的身份、进度一致性和必要初始化关联。

成交查询由 `OrderRequestController` / `AdminOrderRequestController` 绑定范围、排序及作用域游标，Service 和 `TradeProjectionRepository` 只接收 `TradeReadCursor` 的精确 long/int 坐标；`TradeReadCursors` 保持原有令牌格式。SQL保留同表 maker/taker 双侧事实与必要就绪关联，输出游标依据最后事实行编码。

条件单历史查询由 `AdminTriggerOrderRequestController` 校验原始筛选、状态和分页协议，经 `TriggerReadCursors` 绑定为 `CoreTriggerOrderStatus`、`TriggerReadCursor` 和排序方向；Service保留产品归属检查，Repository只处理原生SQL条件和持久化事实。

合约配置由 `InstrumentRequestController` 在调用业务前通过 `InstrumentConfigurationFormats` 检查标识、表示和连接协议格式；`InstrumentValidator` 聚合产品、保证金、资金费、档位及指数源业务规则。内部配置安装入口和基于持久化配置的状态更新仍显式检查表示完整性，均不依赖HTTP Controller。

网关原 `GatewayProxyService` 的请求绑定、路由和HTTP响应转换已合入 `GatewayProxyController`，兼容协议入口复用同一Controller。账户写限制由 `AuthService` 检查；高风险审批策略、匹配和审计持久化经 `AdminApprovalService`。`AdminOperationRecord` 位于 auth.model，审批摘要基于原始正文，查询字符串保持原样。

划转HTTP入口先认证，再直接按BigDecimal绑定金额，验证正整数及long范围，经 `ProductTransferAmount` 的精确long原子单位调用安全估值，随后绑定账户枚举/完整命令并调用资金业务。`ProductTransferSecurityService` 和 `ProductTransferCoordinator` 均不再读JSON或HTTP正文；Command/Result/Status/WireRequest位于provider.model，资金命令使用AccountType原生枚举，公开结果返回规范账户代码。资产精度仍由业务查询资产配置决定。

本机流动性运维入口由 `InternalLiquidityOperationsController` 完成loopback/token、合约自定义格式和请求元数据校验；`LiquidityOperationsService` 检查产品并保证审计意图先持久化、再调用资金/杠杆/合约业务、最后记录结果，审计失败不触发额外资金重试。`LiquidityOperationMetadata` 只包含绑定后的审计事实，不带Servlet或Repository对象。

站内通知由 `UserNotificationController` 绑定认证身份、原limit范围和HTTP结果；`UserNotificationService` 按该用户作用域操作，决定已读时间，并在同一事务内完成单条已读更新及结果读取。`UserNotificationRepository` 仅访问gateway_user_notifications，查询结果 `NotificationView` 位于auth.model，缺失事实由Service转为原生未找到，再由入口映射404。

共享数据结果按职责归model：`gateway.provider.model.ProductAccountAdjustment` 表示已应用/拒绝/未知结果，`websocket.provider.model` 的 `DepthUpdate`、`WebSocketAdminMetrics`、`ChannelMetric` 和 `TimedPayload` 承载深度、指标和载荷时间。字段、深度绝对数量/零删除及序号字符串协议保持；SubscriptionRegistry继续唯一拥有订阅和缓存状态。

Kafka职责归包：独立快照、审计、结算/排空及推送消费入口统一放在各自provider.consumer（价格为index.consumer/mark.consumer），业务和缓存继续由原Service/状态所有者负责。监听topic、group、启动条件与处理顺序保持；做市事件唤醒组件为consumer.MakerQuoteWakeups，原唯一信号状态不变。详见 docs/validation/java-mvc-consumer-packages-20261022.json。

Core订单/杠杆终态拒绝通过 `trading.order.exception.OrderCommandRejectedException` 传递原生CoreResultCode和业务提示。HTTP入口由LeverageRequestController/OrderRejectionHttpAdvice映射409及code，本地网关仍返回原status/message/code JSON；未知结果与未接纳继续走原独立语义。业务异常不引用Spring HTTP类型。

WebSocket具体接入处理器位于 `websocket.provider.handler.ClientWebSocketHandler`，连接/有界发送队列/超时监视位于 `websocket.provider.session.ClientConnection`；SubscriptionRegistry等Service继续拥有订阅与业务路由状态。权限、协议字段、慢连接背压、关闭码和线程生命周期保持。

托管钱包请求与提现法币估值适配位于 `provider.client.CustodyWalletClient` 和 `provider.client.WithdrawalValuationClient`；提现、对账及划转安全判断仍由原Service聚合。

后台系统Service只做授权、路由去重与健康汇总；`provider.client.SystemHealthClient`负责本地Actuator及远端HTTP健康探测，响应模型归 `provider.model`。

KYC文档上传在 `auth.controller.UserComplianceController` 完成鉴权、Multipart绑定和文件格式校验，传入原生 `model.KycDocumentUpload`；`KycDocumentService`协调摘要、文件写入、元数据落库及失败清理。实际存储分别位于 `provider.storage.KycDocumentStorageService` 和 `c2c.storage.C2cR2Storage`；对象路径和签名验证仍在存储边界。

鉴权入口通过 `auth.controller.AuthClientMetadataBinding` 将Servlet字段绑定为 `auth.model.AuthClientMetadata`。登录/注册/刷新/Telegram Service只接收原生数据；Bearer的当前IP经 `auth.port.CurrentAuthClient` 从既有Spring请求上下文读取，不新增ThreadLocal，也不扩大Bearer设备校验。账号、IP/设备封禁、MFA、会话轮换仍由Service负责。
