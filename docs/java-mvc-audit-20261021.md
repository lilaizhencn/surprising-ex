# Surprising EX Java 分层与职责审查

审查日期：2026-10-21。依据当前工作区源码、项目 AGENTS.md 和本轮明确的包与职责规则。HEAD 参考为 `1f3e1ec95`，工作区已有大量未提交改动，结论不只针对该提交。业务源码未修改。

本轮覆盖 **1860 个 Java 文件**，其中生产及基准源码 **1279 个**、测试源码 **581 个**，并核对 24 个 Maven POM。全量结构扫描产生候选，再人工复核本文说明的具体职责、SQL、事务与调用链。逐文件规则清单有 **234 条确认命中，涉及 172 个文件**，另有 **14 条跨表读取例外待复核**。命中包含包归类和维护性问题，同一文件可命中多个规则。

最集中的问题是 C2C 和 Gateway 身份安全包内职责混放、Repository 多表写入、Service 内直接 SQL、调度入口进入业务 Service，以及 HTTP 响应与参数校验落入数据层。另确认工单事务自调用缺陷和敏感资料读取的审计缺口，应先于包迁移处理。

## 审查规则与范围

| 职责 | 本轮采用的归属 | 判断边界 |
| --- | --- | --- |
| 请求入口 | `controller` | 绑定 DTO、必填/格式/长度/分页等参数校验、协议响应和异常映射 |
| 业务聚合 | `service` | 准入、权限场景、库存/余额/订单状态约束、多个 Repository 调用及事务 |
| 数据访问 | `repository` | 一个 Repository 对应一张表；必要关联读取须具体注释用途、关联对象和边界 |
| 定时任务 | `task` | 定时/批次/重试触发，调用 Service；业务处理与 SQL 不留在任务入口 |
| 请求拦截 | `interceptor` | 请求拦截、过滤、前置访问控制；Servlet Filter 保持其原执行机制 |
| 工具 | `utils` | 无业务状态的辅助函数，保留原业务模块或协议模块边界 |
| 配置与异常 | `config`、`exception` | 按“规则依次类推”检查；启动应用类仍可位于模块根包 |

可以保留 `c2c/controller`、`c2c/service` 这样的业务子包，不要求把所有产品线的类合并到一个全局包。DTO 上的约束注解由 Controller 的 `@Valid` 触发，同样属于入口参数校验。

余额是否足够、广告库存是否够用、收款账号是否已审核、订单能否迁移状态是业务约束，仍在 Service/领域逻辑验证。Core、Lane、撮合器、二进制协议、快照和重放按各自状态/线程/恢复边界审查，不机械套用 HTTP MVC。读取投影的身份与完整性检查也不属于应删掉的 Controller 参数校验。

## 覆盖清单

| 顶层模块 | 生产及基准 Java 文件 |
| --- | ---: |
| `surprising-account-api` | 22 |
| `surprising-aeron-core` | 481 |
| `surprising-derivatives-lifecycle` | 113 |
| `surprising-gateway` | 374 |
| `surprising-instrument-api` | 26 |
| `surprising-maker` | 42 |
| `surprising-price` | 90 |
| `surprising-product-api` | 4 |
| `surprising-realtime` | 60 |
| `surprising-trading-api` | 67 |

- [逐文件规则命中](audit/java-mvc-findings-20261021.csv)：文件、行号、规则、优先级、确认状态和问题说明。
- [全部 Java 源码清单](audit/java-source-inventory-20261021.csv)：包括测试；保存职责注解、行数、扫描特征和 SHA-256。
- [全部 Repository 的表职责](audit/java-repository-tables-20261021.csv)：83 个 Repository 的读表、写表、SQL 位置、关联查询特征和注释摘要。

## 优先处理的正确性问题

### P1 工单管理入口绕过事务代理

[SupportTicketService.java:62](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/SupportTicketService.java#L62) 的 `adminCreateTicket` 直接调用本类 `createTicket`；[SupportTicketService.java:113](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/SupportTicketService.java#L113) 的 `adminUpdateStatus` 直接调用本类 `updateStatus`。事务注解只在被调用的 [SupportTicketService.java:248](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/SupportTicketService.java#L248)、[SupportTicketService.java:283](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/SupportTicketService.java#L283) 上，两个管理入口自身没有事务，调用的 Controller 也没有外层事务。

结果：创建工单后写初始备注失败，或更新工单状态后写状态备注失败，前一条 SQL 可能已经提交。当前使用 Spring 代理式事务，同类直接调用不会触发被调用方法的事务拦截。[Spring 官方事务说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。

建议将事务放在实际被外部调用的业务聚合入口，保留身份/参数处理在事务外的必要边界；用真实 Spring 代理和隔离数据库注入第二次写失败，断言工单与备注共同回滚。仅 mock Controller 或直接 `new Service` 无法验证该问题。

### P1 KYC 原件读取的审计失败可被吞掉

[ComplianceService.java:178](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/ComplianceService.java#L178) 读取管理员请求的 KYC 原件后调用 `recordDocumentAccess`，后者在 [ComplianceService.java:397](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/ComplianceService.java#L397) 调用 `AdminAuditRepository.record`。[AdminAuditRepository.java:30](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/AdminAuditRepository.java#L30) 捕获 `DataAccessException` 后只记日志，调用方仍返回原件。

结果：数据库审计写入失败时，敏感资料仍可被读取且没有对应持久审计。建议敏感读取使用必需审计接口，确认写入成功后才返回原件；普通运维日志可以保留单独的尽力记录策略。

### P2 MFA 裁决和审计在不同的职责与提交边界

[MfaRecoveryController.java:73](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/MfaRecoveryController.java#L73) 先调用事务化 `recovery.decide`，返回后再由 Controller 调用可吞异常的 `audit.record`。[MfaRecoveryService.java:121](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/MfaRecoveryService.java#L121) 的事务已经负责裁决及 MFA 变更，但没有包含该审计。

建议由 Service 聚合裁决、MFA 状态变化和必需审计，保持同一数据库事务。Controller 只负责参数与操作者上下文。

## C2C 的职责混用

### P2 C2cAdminQueries 同时承担多表查询 参数校验和视图组装

[C2cAdminQueries.java:8](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cAdminQueries.java#L8) 标注 `@Repository`，实际包含商家、收款账号、广告、订单、资金意图、申诉、举报七类独立列表，SQL 涉及至少 11 张已知表。[C2cAdminQueries.java:120](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cAdminQueries.java#L120) 校验分页和状态；[C2cAdminQueries.java:65](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cAdminQueries.java#L65)、[C2cAdminQueries.java:75](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cAdminQueries.java#L75) 逐条读取市场，再通过 `C2cViews` 组装 HTTP 字段。

建议：Controller 校验查询 DTO；Service 聚合查询结果和必要市场信息；各 Repository 管自己的表。商家列表等确需关联 KYC/质押/资料的读取，可保留专门查询方法，并注释关联目的、字段来源、权限和分页边界。不能用一个大查询类包办所有独立后台列表。

### P2 C2cQueries 从 Repository 反向调用 Service

[C2cQueries.java:25](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cQueries.java#L25) 依赖 `C2cPaymentAccounts` Service。`offers` 在 [C2cQueries.java:41](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cQueries.java#L41) 校验请求格式、在 50 行附近判断 VIP 准入；`orderDetails` 在 [C2cQueries.java:178](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cQueries.java#L178) 组织收款明文、参与者权限和 HTTP 详情。查询层已经同时承担业务聚合与展示。

建议把 VIP 准入、参与者权限、收款资料处理放在业务 Service；Repository 返回明确的行/投影模型；Controller 负责响应契约。

### P2 C2cController 编排完整业务动作

[C2cController.java:80](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cController.java#L80)、[C2cController.java:155](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cController.java#L155) 在 Controller 中组织幂等受理、状态分支、业务调用、拒绝记录和回执。[C2cController.java:121](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cController.java#L121) 的快捷报价循环选择广告和收款账号并逐个尝试报价；[C2cController.java:150](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cController.java#L150) 直接读取报价 Repository 并检查归属。

这部分应收敛到发布广告、创建订单、快捷报价对应的 Service 聚合入口。保留原有请求身份、未知结果和资金续办语义，不能在整理层次时改变幂等状态机。

### P2 C2cMarkets 将配置读取和业务准入放在 Repository

[C2cMarkets.java:43](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cMarkets.java#L43) 校验质押资金意图，[C2cMarkets.java:52](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cMarkets.java#L52) 判断商家质押，[C2cMarkets.java:60](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cMarkets.java#L60) 拒绝未开放市场。该类还独立访问配置、市场、质押政策、质押记录、资产五张表。

建议 Repository 提供单表事实；市场开放、商家质押与资金意图一致性由 Service 聚合判断。只读市场与资产精度的关联可用注释声明具体用途。

## Repository 表职责问题

### P2 九个 Repository 写入多张表

下列是实际写入，属于单表职责违规；用途注释不能把跨表业务写入变成“关联查询例外”。拆分后仍由 Service 维持原事务、锁顺序、幂等键和检查点原子性。

| Repository | 已识别的写入表 |
| --- | --- |
| [C2cOrdersRepository.java:26](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cOrdersRepository.java#L26) | `c2c_order_events`、`c2c_orders`、`c2c_quotes` |
| [AnnouncementRepository.java:46](../surprising-gateway/src/main/java/com/surprising/gateway/provider/announcement/AnnouncementRepository.java#L46) | `gateway_announcement_audit`、`gateway_announcement_reads`、`gateway_announcement_targets`、`gateway_announcement_translations`、`gateway_announcements` |
| [ComplianceKycRepository.java:31](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/ComplianceKycRepository.java#L31) | `gateway_kyc_provider_events`、`gateway_user_kyc_profiles` |
| [GatewayAuthChallengeRepository.java:26](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/GatewayAuthChallengeRepository.java#L26) | `gateway_auth_challenges`、`gateway_users` |
| [LoginVerificationRepository.java:22](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/LoginVerificationRepository.java#L22) | `gateway_login_challenges`、`gateway_login_factors`、`gateway_user_mfa`、`gateway_users` |
| [TelegramLoginRepository.java:18](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/TelegramLoginRepository.java#L18) | `gateway_telegram_identities`、`gateway_telegram_login_flows`、`gateway_users` |
| [CustodyWithdrawalRepository.java:46](../surprising-gateway/src/main/java/com/surprising/gateway/provider/repository/CustodyWithdrawalRepository.java#L46) | `gateway_wallet_withdrawal_actions`、`gateway_wallet_withdrawal_events`、`gateway_wallet_withdrawals` |
| [MaintenanceRepository.java:17](../surprising-gateway/src/main/java/com/surprising/trading/maintenance/MaintenanceRepository.java#L17) | `trading_maintenance_action`、`trading_maintenance_task` |
| [CommittedOrderProjectionRepository.java:34](../surprising-realtime/surprising-realtime-provider/src/main/java/com/surprising/realtime/provider/export/CommittedOrderProjectionRepository.java#L34) | `account_product_ledger_entries`、`core_command_result_projection`、`core_execution_projection`、`core_execution_projection_initialization`、`core_funding_page_projection`、`core_funding_payment_projection`、`core_funding_settlement_projection`、`core_order_projection`、`core_projection_watermark`、`core_trigger_order_projection`、`core_trigger_projection_initialization` |

其中 [C2cOrdersRepository.java:49](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cOrdersRepository.java#L49) 的 `create` 还决定买卖双方、初始订单状态、付款截止时间并写订单事件；[AnnouncementRepository.java:265](../surprising-gateway/src/main/java/com/surprising/gateway/provider/announcement/AnnouncementRepository.java#L265) 在持久层删除并重建翻译与发布目标；[LoginVerificationRepository.java:39](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/LoginVerificationRepository.java#L39) 保存因子后修改用户联系方式，另有消费 MFA 的写入。这些都是应由 Service 聚合的跨表动作。

Realtime 的 [CommittedOrderProjectionRepository.java:24](../surprising-realtime/surprising-realtime-provider/src/main/java/com/surprising/realtime/provider/export/CommittedOrderProjectionRepository.java#L24) 还聚合订单、成交、触发、资金费、命令结果、账本与水位等写入。它属于提交投影基础设施，不是 HTTP MVC；但按本轮 Repository 单表规则仍需拆出单表访问，由现有提交投影 Service 保持整批事务与重放幂等。

### P2 跨表读取与用途注释需要分别处理

83 个 Repository 中，静态 SQL 在 24 个类识别到多表引用：9 个多表写入，另外 15 个主要为读取。后者中 [ComplianceUserProjectionRepository.java:18](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/ComplianceUserProjectionRepository.java#L18) 已明确说明合规控制台投影、关联 KYC/风险标签/AML、同一快照分页的不可拆原因，以及不得扩展到交易/资金库，符合例外要求。

其余 14 个列入跨表读取复核清单：有些类已有简短用途注释，需逐项判断是否充分解释关联关系；有些包含多个独立查询职责，不能仅补注释了事。数据表和所有注释摘要见 Repository CSV。

特别需要纠正的三处：

- [ComplianceKycRepository.java:18](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/ComplianceKycRepository.java#L18) 注释“只负责 gateway_user_kyc_profiles 表”，实际另写 `gateway_kyc_provider_events`；应拆事件 Repository，并修正文档。
- [GatewayUserMfaRepository.java:37](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/GatewayUserMfaRepository.java#L37) 在 MFA Repository 锁 `gateway_users`；应由 Service 通过用户 Repository 获取用户锁，再调用 MFA Repository。
- [InstrumentRepository.java:24](../surprising-gateway/src/main/java/com/surprising/instrument/provider/repository/InstrumentRepository.java#L24) 注释“只负责 instruments 表”，实际在 [InstrumentRepository.java:102](../surprising-gateway/src/main/java/com/surprising/instrument/provider/repository/InstrumentRepository.java#L102) 等方法关联 `assets` 解析资产代码；可保留必要关联读取，但必须说明资产字段来源及用途。

`GatewayUserAccessBlockRepository.knownIps` 独立读取登录日志；这部分应回到已有登录日志 Repository，由用户安全 Service 聚合，而非扩展封禁表 Repository 的职责。

## Service 和 Controller 的边界

### P2 十五个 Service 直接编写 SQL

C2C 的 11 个类：`C2cAccess`、`C2cAdminMarkets`、`C2cAdminReview`、`C2cAds`、`C2cAttachments`、`C2cConversations`、`C2cMerchantBonds`、`C2cMessages`、`C2cOrders`、`C2cPaymentAccounts`、`C2cProfiles`。

另外四个：`GatewayProductSettings`、`OrderBusinessSettingsService`、`PriceBusinessSettingsService`、`LifecycleBusinessSettingsService`。逐个文件和首个 SQL 位置已列入 `SERVICE_SQL` 规则。

例如 [C2cProfiles.java:55](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cProfiles.java#L55) 在同一个设置更新方法里做昵称格式校验、验证码场景验证、锁用户、查版本、更新设置和删除授权。整理方式应是入口校验参数、Service 聚合业务、单表 Repository 访问数据；不是把整个方法移到 Repository。

### P2 请求格式校验下沉到 Service 或 Repository

明确示例：`C2cAdminQueries.page/state`、`C2cQueries.offers/requireLimit`、`C2cProfiles` 的昵称格式校验、[GatewayProductSettings.java:38](../surprising-gateway/src/main/java/com/surprising/gateway/provider/product/GatewayProductSettings.java#L38) 的 JSON 字段集合及类型解析、[OrderBusinessSettingsService.java:36](../surprising-gateway/src/main/java/com/surprising/trading/order/config/OrderBusinessSettingsService.java#L36) 的 JSON 结构校验、[PriceBusinessSettingsService.java:111](../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/settings/PriceBusinessSettingsService.java#L111) 的设置字段类型校验。

建议 Controller 使用明确请求 DTO 和约束；未知字段拒绝、十进制字符串、精度和 ID 协议应保持原契约。涉及市场配置、当前库存、原订单快照的校验继续作为 Service 的业务规则。

### P2 九个 RequestService 承担 HTTP 层职责

`AccountRequestService`、`InstrumentRequestService`、`AdminMaintenanceRequestService`、`AdminOrderRequestService`、`LeverageRequestService`、`OrderRequestService`、`TradingFeeRequestService`、`AdminTriggerOrderRequestService`、`TriggerOrderRequestService`。

例如 [OrderRequestService.java:60](../surprising-gateway/src/main/java/com/surprising/trading/order/service/OrderRequestService.java#L60) 构造 `ResponseEntity`、HTTP Cache-Control，[OrderRequestService.java:351](../surprising-gateway/src/main/java/com/surprising/trading/order/service/OrderRequestService.java#L351) 把命令回执转换成 HTTP 状态。按本轮约定，这些属于 Controller 或其协议适配代码；业务 Service 应承担订单操作等聚合。它们不是因为有 `RequestService` 名称就违规，而是实际做了 HTTP 响应和异常转换。

原本 `LocalBusinessApi/*LocalRoutes` 是有文档依据的同进程 HTTP 契约适配边界，可以保留；收拢 HTTP 职责时必须继续覆盖对应路由的参数校验和错误响应。

### P2 用户安全 Controller 承担事务和持久化编排

[UserSecurityController.java:181](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/UserSecurityController.java#L181)、196、213、229 行对应四个 `@Transactional` 入口，直接执行设备/IP 封禁、身份验证和会话撤销。应放到已有 `UserSecurityService` 聚合，Controller 只进行绑定、格式校验和调用。

六个 Controller 直接持有 Repository/查询组件是审查线索，不按“直连即错误”机械判定。例如简单通知已读 CRUD，新增纯透传 Service 没有业务价值；若需统一接口，应接入承担实际通知业务的 Service。C2C、用户封禁、MFA 审计这些已有多步骤动作则应明确下沉。

### P2 HTTP 状态进入 Repository 和业务 Service

六个 Repository 直接构造 `ResponseStatusException`：`AlgoOrderProjectionRepository`、`TradeProjectionRepository`、`TradingMetricsRepository`、`TriggerOrderProjectionRepository`、`InMemoryMarketMakerStrategyOverrideStore`、`MarketMakerBusinessSettingsStore`。

另在 12 个业务 Service 记录到同类问题，逐文件见 `BUSINESS_SERVICE_HTTP`；包括订单/杠杆/条件单、公告、设置、资金验证、期权报价。数据层返回未就绪、版本冲突等业务/存储结果，再由 Controller 或统一异常处理映射 HTTP。切勿把“投影未就绪”改成空列表或自动改查另一权威来源。

共享实时 API 中 [ValkeyUserQueries.java:34](../surprising-realtime/surprising-realtime-api/src/main/java/com/surprising/realtime/api/ValkeyUserQueries.java#L34)、[ValkeyMarketData.java:64](../surprising-realtime/surprising-realtime-api/src/main/java/com/surprising/realtime/api/ValkeyMarketData.java#L64) 也直接抛 HTTP 异常；该模块还包含六个 Redis/发布实现组件，应明确共享客户端实现与纯契约的边界，至少先移除底层 HTTP 异常耦合。

## 调度 拦截 工具和包归类

| 类型 | 不在约定包中的类数 | 处理方式 |
| --- | ---: | --- |
| Controller | 25 | 归 `controller` |
| Service | 43 | 先纠正职责，再归 `service` |
| Repository | 37 | 先按单表拆职责，再归 `repository` |
| 定时触发类 | 14 | 调度入口归 `task`，Service 保留业务方法 |
| 请求过滤组件 | 3 | 归专门 `interceptor` 包，保留 Filter 的原拦截语义 |
| 配置类/配置属性 | 14 | 归 `config`，保留产品子容器隔离和扫描范围 |
| 静态工具辅助类 | 13 | 归模块内 `utils` |
| ControllerAdvice | 1 | `C2cHttpErrors` 归 `exception` |

这些数字可以重叠，例如 C2C 质押 Service 同时包含定时入口。完整路径见逐文件 CSV，包归类问题与功能缺陷分开处理。

### 十四个应调整的调度入口

| 当前类和入口位置 | 问题 |
| --- | --- |
| [LifecycleBusinessSettingsService.java:76](../surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider/src/main/java/com/surprising/derivatives/lifecycle/LifecycleBusinessSettingsService.java#L76) | 调度入口位于 LifecycleBusinessSettingsService；提取到 task，业务处理留在 service |
| [C2cChatRealtime.java:73](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cChatRealtime.java#L73) | 调度入口位于 C2cChatRealtime；提取到 task，业务处理留在 service |
| [C2cMerchantBonds.java:120](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cMerchantBonds.java#L120) | 调度入口位于 C2cMerchantBonds；提取到 task，业务处理留在 service |
| [C2cNotificationJobs.java:16](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cNotificationJobs.java#L16) | 调度入口位于 C2cNotificationJobs；提取到 task，业务处理留在 service |
| [C2cSettlement.java:30](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cSettlement.java#L30) | 调度入口位于 C2cSettlement；提取到 task，业务处理留在 service |
| [GatewayProductServices.java:32](../surprising-gateway/src/main/java/com/surprising/gateway/provider/product/GatewayProductServices.java#L32) | 调度入口位于 GatewayProductServices；提取到 task，业务处理留在 service |
| [MaintenanceWorker.java:10](../surprising-gateway/src/main/java/com/surprising/trading/maintenance/MaintenanceWorker.java#L10) | 调度入口位于 MaintenanceWorker；提取到 task，业务处理留在 service |
| [OrderBusinessSettingsService.java:66](../surprising-gateway/src/main/java/com/surprising/trading/order/config/OrderBusinessSettingsService.java#L66) | 调度入口位于 OrderBusinessSettingsService；提取到 task，业务处理留在 service |
| [InstrumentCoreSyncService.java:34](../surprising-gateway/src/main/java/com/surprising/trading/order/service/InstrumentCoreSyncService.java#L34) | 调度入口位于 InstrumentCoreSyncService；提取到 task，业务处理留在 service |
| [OpenInterestPublisher.java:21](../surprising-gateway/src/main/java/com/surprising/websocket/provider/service/OpenInterestPublisher.java#L21) | 调度入口位于 OpenInterestPublisher；提取到 task，业务处理留在 service |
| [RealtimeWebSocketBridge.java:89](../surprising-gateway/src/main/java/com/surprising/websocket/provider/service/RealtimeWebSocketBridge.java#L89) | 调度入口位于 RealtimeWebSocketBridge；提取到 task，业务处理留在 service |
| [MarkPriceMarketSubscription.java:64](../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/mark/service/MarkPriceMarketSubscription.java#L64) | 调度入口位于 MarkPriceMarketSubscription；提取到 task，业务处理留在 service |
| [PriceBusinessSettingsService.java:98](../surprising-price/surprising-price-provider/src/main/java/com/surprising/price/settings/PriceBusinessSettingsService.java#L98) | 调度入口位于 PriceBusinessSettingsService；提取到 task，业务处理留在 service |
| [OptionQuoteService.java:123](../surprising-realtime/surprising-realtime-provider/src/main/java/com/surprising/realtime/provider/option/OptionQuoteService.java#L123) | 调度入口位于 OptionQuoteService；提取到 task，业务处理留在 service |

设置类里的 `reload`、商家质押里的 `confirm`、行情订阅里的 `renewSubscriptions` 等不能只整体移动到 task：应把定时触发提取到 task，保留被调用的业务/订阅方法。

`SurprisingAeronClient` 和外部 WebSocket 客户端内部的协议心跳/连接生命周期计时属于技术机制，单独保留在对应客户端边界，不与 Spring 业务批次任务混为一类。

三个请求过滤组件为 `AdminIpWhitelistFilter`、`AdminPermissionFilter`、`ProductRequestBodyFilter`，当前位于 `config` 或 `product` 包。

13 个工具辅助类为 9 个 `AdminCursorPage`，以及 `C2cMoney`、`InstrumentIds`、`ProductTopicNames`、`InstrumentSnapshotSupport`。响应组装器 `C2cViews` 承担表示层转换，不按“所有 static 类都放 utils”机械处理；二进制 Codec、业务计算器和状态索引也保持其真实职责。

## 重复规则 查询和异常处理

### P2 市场列表和价格预警的资格筛选不一致

[C2cQueries.java:65](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cQueries.java#L65) 的市场列表要求商家质押为 `LOCKED` 且满足当前政策；[C2cNotificationJobs.java:42](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cNotificationJobs.java#L42) 的预警 SQL 校验商家、用户、KYC 和市场，但没有相同的质押过滤。

结果是预警有机会根据市场列表不会展示的广告触发。这是把业务规则复制到调度 SQL 后已经出现的差异。应由 Service 统一广告可用资格；关联读取 Repository 按该业务用途提供查询，任务只负责触发。

### P2 列表中逐条查询形成 N 加一

- [C2cAdminQueries.java:65](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cAdminQueries.java#L65)、75 行，广告/订单每行查市场，且先为 `limit+1` 行全部组装再截取。
- [C2cQueries.java:145](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cQueries.java#L145)、164 行，本人广告/订单每行查市场。
- [C2cConversations.java:105](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cConversations.java#L105) 每个会话继续查询会话、昵称、末条消息、未读数，以及订单信息；订单会话页可能约为 `1+5N` 次查询。
- [UserSecurityController.java:169](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/UserSecurityController.java#L169)、210 行，每个设备/IP 查询封禁状态；底层设备和历史 IP 列表还缺少分页上限。

建议由 Service 按本页 ID 批量取必要事实，或使用有明确用途注释的关联投影查询。不要为了减少查询新增一份长期业务缓存。

### P2 泛用异常映射与 Map 契约掩盖职责

[C2cHttpErrors.java:36](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cHttpErrors.java#L36) 把所有 `IllegalStateException` 当作 409；内部状态损坏、配置错误等也会按预期业务冲突返回。[C2cProblem.java:6](../surprising-gateway/src/main/java/com/surprising/gateway/c2c/C2cProblem.java#L6) 又直接持有 `HttpStatus`。建议使用明确业务异常类型，在异常处理边界映射状态。

C2C 41 个类中 22 个使用 `Map<String,Object>`，Controller、Service 和 Repository 靠字符串字段及运行期强转交接。例如快捷报价从响应 Map 反向提取 `adId/paymentOptions`，[TradingMetricsRepository.java:22](../surprising-gateway/src/main/java/com/surprising/trading/order/repository/TradingMetricsRepository.java#L22) 在持久层解释并裁剪展示字段。优先为这些跨层结果使用明确 DTO/投影模型，不要求为每个局部临时 Map 创建类。

### P2 通知发送进入事务且失败无持久补偿

[MfaRecoveryService.java:130](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/MfaRecoveryService.java#L130) 在裁决事务中发送成功/拒绝邮件，提交若随后失败，邮件已无法撤回；发送异常被吞掉。[ComplianceService.java:276](../surprising-gateway/src/main/java/com/surprising/gateway/provider/auth/ComplianceService.java#L276) 的 KYC 状态通知也吞掉发送失败。

业务成功状态应与待发通知意图共同持久化；由 task 触发 Service 发送、记录失败和重试。事务提交后发送能避免回滚后的假通知，但若要求可靠送达，仍需持久意图。

### P3 注入与可维护性

六个生产类混用字段注入与构造器注入，见 `FIELD_INJECTION`：`GatewayProxyService`、`OrderService`、`TriggerOrderService`、`IndexPriceService`、`MarkPriceService`、`CandlestickStreamConfiguration`。建议在实际改动路径统一为显式构造依赖，可选依赖也应明确表达。

`OrderService`、`MarketMakerService`、`TradingRuntimeState` 等大文件列为后续职责复核候选。行数只表示审查成本，不据此直接判定违规；Core 的线程所有权、复用缓冲和恢复协议不能因 MVC 整理而拆散。

父 POM 有 JDK Enforcer 和依赖检查，但当前审查未见针对这些 MVC 依赖、单表 Repository 和 task 入口位置的自动门禁。适合在规则稳定后加入少量针对性架构检查，而不是只按类名和行数限制。

## 建议处理顺序与验证

1. 修正工单事务和敏感操作审计边界，使用代理事务及数据库故障测试验证回滚/阻断。
2. C2C 先分离 Controller 参数/HTTP、Service 业务聚合、单表 Repository；保留资金意图、审批、锁顺序和未知结果续办。
3. 拆多表写入 Repository，由 Service 维持原事务。Realtime 提交投影必须继续验证批次原子提交、检查点和重放幂等。
4. 将调度、拦截、工具和配置归位，同时核对组件扫描、产品子容器、定时 Bean、Mapper 与包内可见性。
5. 批量化列表查询，补齐真正需要保留的关联读取说明，最后补静态门禁。

本轮仅新增审查材料，未运行 Java 构建、Maven 测试或业务服务；没有验证资金/订单运行结果。已执行源码范围、表职责、文件指纹、报告引用和格式静态核验。

CodeGraph CLI 已同步并用于现有调用关系查询，但同步后仍缺少部分当前源码，因而本轮用 Maven Java 源目录的完整文件清单补齐结构扫描。Tree-sitter Java 对 `AeronClientPool` 两处合法 record pattern 语法未完整解析，已人工核对相应代码，未据此报编译错误。表清单基于能从静态 SQL 识别出的已知表；动态 SQL 与非 SQL 存储不凭空判定合规。

工作区在审查期间仍有 Core 文件调整。源码清单保存本轮快照的 SHA-256，后续修改应按实际变更复核，不能将此报告当作后续代码的通过证明。
