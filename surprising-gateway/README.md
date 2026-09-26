# surprising-gateway

本模块现为统一业务应用：身份在 `com.surprising.gateway.provider`，订单在
`com.surprising.trading`，账户在 `com.surprising.account.provider`，合约在
`com.surprising.instrument.provider`。四者共享一个启动入口、HTTP 端口及数据源；
Core 仍独立。公共接口继续经过 Gateway 的身份、审批与审计，再调用本地业务入口。
详见 [合并及部署说明](../docs/business-application-merge.md)。


面向前端和 BFF 的无状态 REST API 网关。

主流交易系统通常在边缘放一个统一 public gateway 或 BFF，各业务模块仍维护自己的内部 API 和配置。这个模块采用同样方式：前端只访问一个 gateway 前缀，gateway 只代理白名单里的内部服务。

## 模块

- `surprising-gateway`：Spring Boot 白名单代理、WebSocket 连接管理和 Kafka fanout。

## 入口

- HTTP 端口：`9094`
- WebSocket 路径：`/ws/v1`
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

Gateway 会拒绝未知 service 名称。它不会把用户输入拼成任意后端主机名，也不会处理任何动态表名。

## 安全模型

当前实现要求私有路由携带经过校验的 `Authorization: Bearer`。gateway 完成认证后向下游注入可信 `X-User-Id`。
`X-Trace-Id` 所有路由都可以带；gateway 会清洗、回写给客户端并转发给后端 provider。它只用于可观测性和排障，不能参与认证或鉴权判断。

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
Core 及订单客户端池原有 Driver 保持各自生命周期，交易主链路不迁移到应用侧 Driver。

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
