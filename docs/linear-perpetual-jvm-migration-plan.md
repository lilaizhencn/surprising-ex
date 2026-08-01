# 永续交易链路 JVM 单写者迁移执行计划

## 1. 目标和不可突破的边界

本计划只切换 `LINEAR_PERPETUAL`，现货、交割和期权继续使用各自的账户、风险、撮合和 Topic。永续切换完成后，交易热路径的可变资金事实由账户单写者维护，其他模块只提交命令或消费事件，不得直接修改账户事实。

必须保证：

- 同一产品线、同一用户的余额、冻结、保证金、持仓、持仓模式、未平仓量和账本变化按固定顺序执行。
- 账户、下单、成交、撤单、资金费、强平、ADL、保险基金和 WebSocket 事件使用稳定的 `commandId`、`eventId`、版本号和产品线键，重复投递只能产生一次业务结果。
- JVM 快照丢失、Redis 不可用、Kafka 重平衡、服务重启或节点故障时，可以从持久事件和本地快照重建；任何缺失数据都不能被解释为零余额或零持仓。
- 数据库成为异步投影、审计、恢复和对账存储后，仍必须按事件版本幂等写入，不能出现第二个可写事实源。
- 永续的资金费、标记价、指数价、风险、强平、ADL 和保险基金均按 `LINEAR_PERPETUAL` 独立隔离。

本计划不允许一次性删除数据库 Repository、Redis 投影或现有 outbox。每个热路径切换都要经过影子计算、对账和故障演练后才能关闭旧路径。

## 2. 当前实现盘点

### 2.1 账户和持仓

当前账户命令由 [`AccountUserCommandProcessor`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountUserCommandProcessor.java) 在一个 PostgreSQL 事务中处理，先注册 `account_commands`，再调用 `AccountService`、`AccountSettlementService` 和各 Repository 修改余额、保证金、持仓、账本和未平仓量。单用户 Kafka key 已经是 `LINEAR_PERPETUAL:userId`，这是后续单写者的基础。

成交侧由 [`AccountService`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountService.java) 依次执行锁仓、盈亏、保证金、手续费、仓位更新和结算侧完成记录。`PositionUpdatedEvent` 当前由 `AccountOutboxService` 读取数据库事务内最终快照后写入 outbox，revision 来自 PostgreSQL。

### 2.2 Redis 持仓投影

[`PositionCacheProjectionConsumer`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/PositionCacheProjectionConsumer.java) 消费 `account.position.events.v1`，调用 [`RedisPositionCache`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/RedisPositionCache.java) 的 Lua revision CAS 更新持仓、保证金和 revision。`PositionCacheAfterCommitSynchronizer` 负责在数据库事务提交后进行本机加速写入，`PositionCacheCoordinator` 当前可以扫描 PostgreSQL 重建 Redis。

Redis 是查询投影，不应承担资金条件判断。迁移后保留 Redis 供 API、WebSocket 和后台查询，但投影输入必须统一来自账户事件，不能再由业务模块直接写入。

### 2.3 风控

[`RiskService`](../surprising-margin-ops/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskService.java) 已有 `localGroups`，但 `scanPositionUpdates` 在本地组缺失或过期时仍通过 `RedisRiskStateStore` 和 `RiskRepository.cachedRiskGroup` 恢复；`scanMarkPrice` 通过 Redis 反向索引找到风险组，再从 Redis 批量读取状态。风险快照、风险事件和强平候选在数据库事务中落库。

### 2.4 强平

[`LiquidationService`](../surprising-margin-ops/surprising-liquidation-provider/src/main/java/com/surprising/liquidation/provider/service/LiquidationService.java) 当前执行候选时会：

1. 从标记价缓存检查价格新鲜度；
2. 通过 `LiquidationCandidateRepository` 在数据库中 claim 候选；
3. 通过 [`LiquidationPositionRepository`](../surprising-margin-ops/surprising-liquidation-provider/src/main/java/com/surprising/liquidation/provider/repository/LiquidationPositionRepository.java) 对 `account_positions` 执行 `FOR UPDATE`；
4. 通过 [`LiquidationRepository`](../surprising-margin-ops/surprising-liquidation-provider/src/main/java/com/surprising/liquidation/provider/repository/LiquidationRepository.java) JOIN 风险快照并复核最新状态；
5. 创建减仓市价单并写审计。

[`RedisLiquidationCandidateQueue`](../surprising-margin-ops/surprising-liquidation-provider/src/main/java/com/surprising/liquidation/provider/service/RedisLiquidationCandidateQueue.java) 已提供优先级、租约、重试和跨节点协调，但当前只负责候选队列，不负责持仓事实。

### 2.5 其他必须纳入迁移的链路

- 下单：`OrderMarginSnapshotCache`、`OpenInterestSnapshotCache`、持仓模式和未成交订单索引仍有数据库初始化、校验或安全回退。
- Trigger：触发索引故障时仍可能回退数据库扫描，触发单的持仓模式、可平数量和未成交订单需要统一快照。
- 行情：标记价、指数价和 K 线消费者需要丢弃过期消息，重启时先初始化最新快照，不能用旧价格触发风控或强平。
- WebSocket：成交、订单、持仓和风险事件需要有界队列、背压和事件版本，不能让慢连接阻塞 Kafka 消费。
- Funding、ADL、Insurance：都只能发账户命令，不能直接写账户表或另建一套余额事实。

## 3. 目标数据分层

| 数据 | 永续唯一事实源 | JVM 快照 | Redis | PostgreSQL |
| --- | --- | --- | --- | --- |
| 余额、冻结、保证金、持仓、账本 | 账户单写者事件日志 | 账户模块本地状态 | 查询投影 | 异步投影、审计、对账 |
| 持仓事件 | 账户事件 Topic | 风控、强平、订单各自消费 | 持仓查询投影 | 事件归档 |
| Instrument/Risk 配置 | instrument 服务事件 | 各模块不可变快照 | 不作为事实源 | 管理事实和恢复源 |
| 标记价、指数价 | 行情事件 Topic | 各模块最新价格缓存 | 可选查询投影 | 仅存归档/恢复数据 |
| 强平候选 | 风控事件 Topic | 强平本地候选状态 | 优先级、租约、重试 | 审计和恢复 |
| 未成交订单、触发单 | 订单/触发事件 Topic | 订单、触发模块 JVM 索引 | 跨节点协调和重建辅助 | 审计和恢复 |
| 未平仓量 | 账户 OpenInterest 事件 | 订单和风控 JVM 快照 | 可选查询投影 | 最终归档和恢复 |

任何一个模块都不能同时把数据库、Redis 和 JVM 当成可写事实源。

## 4. 分阶段执行

### 阶段 0：永续隔离、基线和影子指标

改动：

- 为账户、风险、强平、订单、Trigger、行情和 WebSocket 增加明确的 `LINEAR_PERPETUAL` readiness、lag、revision 和拒绝原因指标。
- 检查所有 Kafka key、Topic、consumer group 和 `ProductTopicNames`，禁止消费共享或其他产品线 Topic。
- 建立每个用户和每个持仓键的资金、持仓、未平仓量、风险和事件版本基线。
- 运行现有永续 API 流程、资金核对和压测，记录数据库结果作为影子对照，不改变旧事实源。

验收：影子计算与数据库结果逐项一致；任何跨产品线事件、revision 回退和过期行情均能被拒绝并告警。

### 阶段 1：账户事件模型和 JVM 持仓快照

改动：

- 增加永续账户单写者状态，按 `LINEAR_PERPETUAL:userId` 串行应用账户命令。
- 将余额、冻结、订单保证金、持仓、逐仓保证金、持仓模式、未平仓量和账本变更组合为一个可回放状态变更。
- 为账户状态增加单调 `accountRevision`，为每个持仓增加 `positionRevision`；现有 `PositionUpdatedEvent` 兼容期间保留 `revision`，新字段先以影子方式发布。
- 事件先写入持久事件日志/WAL，再由 Kafka 发布；JVM 崩溃可从 WAL 和 Kafka 重放。数据库 outbox 在过渡期继续保留。
- 所有事件带 `productLine`、用户键、symbol、instrumentVersion、commandId、eventId、traceId 和前置版本。
- 增加本地有界快照缓存，旧 revision 不能覆盖新 revision，发现事件间隙时暂停该用户而不是猜测状态。

验收：重复命令、乱序事件、进程中断、批量重放和单用户并发提交后，账户状态、余额守恒和持仓结果一致。

### 阶段 2：账户热路径影子切换

改动：

- JVM 单写者与当前 PostgreSQL 事务并行计算，但只有旧路径写入正式结果。
- 比较余额、冻结、保证金、仓位数量、开仓价、已实现盈亏、手续费、资金费、未平仓量和账本流水。
- 对差异保存完整 command/event/revision 上下文，不允许自动修正资金。

验收：连续压测、Kafka 重平衡、数据库慢查询、Redis 故障、服务重启后无差异；差异为零后才能进入阶段 3。

### 阶段 3：永续账户正式切换

改动：

- 永续命令由 JVM 单写者执行，数据库只由异步投影器写入。
- 订单、撮合、Funding、ADL、Insurance、Liquidation 只发送 `AccountUserCommand`。
- 账户单写者成为余额、冻结、持仓、保证金、持仓模式和未平仓量的唯一写入边界。
- PostgreSQL 投影按 `eventId` 和 revision 幂等写入，投影延迟不能阻塞交易。
- 节点租约增加 fencing token，旧节点失去租约后不能继续发布或应用事件。

验收：账户数据库投影延迟期间下单、成交、撤单、平仓和资金费仍正确；重启后从快照和事件恢复到切换前状态。

### 阶段 4：持仓 Redis 投影切换

改动：

- `PositionCacheProjectionConsumer` 只消费 canonical `PositionUpdatedEvent`。
- `PositionCacheAfterCommitSynchronizer` 从数据库事务快照改为事件提交后的本机投影适配器。
- `PositionCacheCoordinator` 启动优先从本地快照和 Kafka 重放，数据库仅保留人工/灾备重建入口。
- Redis 仍用 revision CAS；未就绪、revision 间隙或 Redis 故障时查询返回明确不可用，不返回零仓位。

验收：Redis 清空、消息重复、旧消息重放和滚动升级后，查询结果最终与 JVM 快照一致，且不会影响资金判断。

### 阶段 5：风控 JVM 风险组

改动：

- `scanPositionUpdates` 直接应用账户持仓事件，不再把 `RiskRepository.cachedRiskGroup` 作为实时回退。
- `scanMarkPrice` 直接按本地 `LINEAR_PERPETUAL:userId:settleAsset` 风险组计算，不依赖 Redis MGET。
- Redis 风险状态保留为跨节点协调、可选反向索引和恢复投影。
- 风险事件携带 `positionRevision`、`riskRevision`、`markSequence` 和行情时间。
- 标记价/指数价过期、instrumentVersion 不匹配或状态不完整时停止强平候选生成。

验收：同一持仓和价格在重放、重复、乱序和多节点下只产生一个有效风险状态；风险恢复不能把过期行情当成最新行情。

### 阶段 6：强平 JVM 快照和版本校验

当前已完成的前置改动：风险计算结果和强平候选已携带 `positionRevision`，并写入
`risk_liquidation_candidates.position_revision`。旧构造方式和旧数据在读取时使用
`snapshotId` 兼容，避免历史候选重放失败；这一步只增加校验信息，尚未切换强平实时数据库路径。
强平服务另外以独立消费组消费账户持仓事件，维护按产品线和 revision 隔离的 JVM 快照；该快照目前仅用于影子校验与恢复准备，未替代实时执行中的数据库复核。
风控持仓触发消费者也在风险计算成功后推进同一类 JVM 快照；若风险计算失败，事件重试不会提前污染快照。
风险持仓事件路径已在快照未就绪或风险组缺失时 fail-closed 并等待 Kafka 重试，不再从 `RiskRepository.cachedRiskGroup` 读取数据库；数据库查询仅由启动/定时恢复扫描使用。
强平执行在本机快照明确领先候选版本时先取消陈旧候选并写审计；快照缺失或落后不做推断，继续走现有锁定和数据库复核。

改动：

- 强平模块消费持仓事件和风险事件，维护本地持仓、保证金和风险状态。
- `LiquidationService` 移除实时 `account_positions FOR UPDATE`、风险快照 JOIN 和数据库标记价回退。
- `LiquidationCandidateEvent` 增加候选对应的持仓版本、风险版本、行情序列和预期数量。
- Redis 队列只做优先级、租约和重试；最终动作发送带版本条件的 reduce-only 账户命令。
- 账户单写者再次校验持仓版本和数量，不一致时取消旧候选并等待新风险事件。
- 原有强平 Repository 保留为审计、恢复和对账，禁止在实时执行路径调用。

验收：同一候选重复执行、候选过期、仓位先被主动平仓、Redis 租约丢失、强平节点重启时均不会重复平仓或漏平仓。

### 阶段 7：下单、Trigger、未成交订单、行情和 WebSocket

改动：

- 下单保证金只使用 JVM 余额/保证金/Instrument/费率/OpenInterest 快照，缓存未就绪直接拒绝，不查数据库兜底。
- 持仓模式、逐仓保证金、reduce-only 数量、未成交订单和触发单通过事件建立本地索引；数据库只做恢复和审计。
- Trigger 的 Redis 索引保留跨节点租约，但不能回退为高频数据库扫描。
- 标记价和指数价消费组启动先通过最新快照/RPC 初始化，再从当前 offset 消费；所有模块按序列和最大年龄校验。
- WebSocket 对私有事件使用不丢失的有界持久队列，对公共行情使用明确可丢弃的合并队列；慢连接不能阻塞 Kafka。
- canonical 成交事件与可丢弃公共行情事件分离，K 线不能消费丢失的公共成交队列。

验收：下单、撤单、撮合、成交、平仓、强平、Trigger、持仓查询、未成交订单查询、WebSocket 私有/公共推送均在事件重复和服务重启后保持一致。

### 阶段 8：关闭旧热路径和生产演练

改动：

- 删除或隔离永续实时路径对数据库持仓、余额、风险和强平 JOIN 的调用。
- 关闭旧路径前保留只读审计开关和紧急回滚开关；回滚只能切换消费/投影版本，不能双写两个事实源。
- 完成多节点租约、Kafka broker 故障、Redis 清空、数据库不可用、进程 SIGKILL、磁盘恢复和时钟漂移演练。

验收：所有功能脚本、资金守恒、持仓守恒、事件完整性和恢复演练通过后，才允许标记永续迁移完成。

## 5. 资金安全不变量

- 任何资产：期初余额 + 充值/调整 + 成交盈亏 - 手续费 + 资金费 - 强平费用 + 其他结算 = 期末余额。
- 订单冻结、成交消费、撤单释放和强平释放只能由账户单写者改变。
- 持仓数量、保证金、开仓价和已实现盈亏必须由同一用户事件顺序更新。
- 事件 revision 必须单调；缺失、回退、跨产品线和 instrumentVersion 不匹配必须拒绝。
- 强平只能使用新鲜标记价和匹配的持仓/风险版本。
- Redis、数据库投影和 WebSocket 延迟不能改变资金事实。

## 6. 验证命令和提交门槛

每完成一个阶段，先只验证永续：

```bash
mvn -pl surprising-account/surprising-account-api,surprising-account/surprising-account-provider -am test
mvn -pl surprising-margin-ops/surprising-risk-provider,surprising-margin-ops/surprising-liquidation-provider -am test
PRODUCT_LINES=LINEAR_PERPETUAL ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_PERPETUAL ./scripts/product-line-funds-reconcile.sh
./scripts/check-account-single-writer.sh
./scripts/check-persistence-boundaries.sh
```

跨模块阶段再运行 `integration-smoke.sh`、`kafka-trading-smoke.sh` 和 `live-runtime-trading-reconciliation.sh`。所有新增注释和文档使用中文；每个模块通过测试后单独提交，禁止提交运行产物。
