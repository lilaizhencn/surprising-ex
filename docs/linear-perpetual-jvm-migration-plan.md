# 永续交易链路 JVM 单写者迁移执行计划

> 当前实现说明：本文保留迁移过程中的历史基线。订单、账户命令、风险计算和资金费结算的生产热路径
> 已切换到用户分区 WAL、本地 reducer/JVM 快照或 RocksDB；文中仍出现的旧 outbox、数据库锁和 JOIN
> 仅表示当时的风险清单，不能作为当前生产路径或新增代码的设计依据。

## 1. 目标和不可突破的边界

本计划只切换 `LINEAR_PERPETUAL`，现货、交割和期权继续使用各自的账户、风险、撮合和 Topic。永续切换完成后，交易热路径的可变资金事实由账户单写者维护，其他模块只提交命令或消费事件，不得直接修改账户事实。

必须保证：

- 同一产品线、同一用户的余额、冻结、保证金、持仓、持仓模式、未平仓量和账本变化按固定顺序执行。
- 账户、下单、成交、撤单、资金费、强平、ADL、保险基金和 WebSocket 事件使用稳定的 `commandId`、`eventId`、版本号和产品线键，重复投递只能产生一次业务结果。
- JVM 快照丢失、Redis 不可用、Kafka 重平衡、服务重启或节点故障时，可以从持久事件和本地快照重建；任何缺失数据都不能被解释为零余额或零持仓。
- 数据库成为异步投影、审计、恢复和对账存储后，仍必须按事件版本幂等写入，不能出现第二个可写事实源。
- 永续的资金费、标记价、指数价、风险、强平、ADL 和保险基金均按 `LINEAR_PERPETUAL` 独立隔离。

迁移期间不能一次性删除恢复、审计和投影 Repository；每个热路径切换都要经过顺序、幂等、资金守恒和崩溃恢复验证后才能关闭旧路径。

## 2. 当前实现盘点

### 2.1 账户和持仓

当前账户命令由 [`AccountUserCommandProcessor`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountUserCommandProcessor.java) 在一个 PostgreSQL 事务中处理，先注册 `account_commands`，再调用 `AccountService`、`AccountSettlementService` 和各 Repository 修改余额、保证金、持仓、账本和未平仓量。单用户 Kafka key 已经是 `LINEAR_PERPETUAL:userId`，这是后续单写者的基础。

账户单写者只生成 `account.state.events.v1` 完整状态事件。风险服务从同一事件中的余额、欠款、持仓保证金
和订单冻结计算钱包，并通过 JVM/Redis 风险组消费；风险实时路径缺失快照时失败关闭，不再回查账户库。

成交侧由 [`AccountService`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/AccountService.java) 依次执行锁仓、盈亏、保证金、手续费、仓位更新和结算侧完成记录。`PositionUpdatedEvent` 当前由 `AccountOutboxService` 读取数据库事务内最终快照后写入 outbox，revision 来自 PostgreSQL。

### 2.2 Redis 持仓投影

[`PositionCacheProjectionConsumer`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/PositionCacheProjectionConsumer.java) 消费 `account.position.events.v1`，调用 [`RedisPositionCache`](../surprising-account/surprising-account-provider/src/main/java/com/surprising/account/provider/service/RedisPositionCache.java) 的 Lua revision CAS 更新持仓、保证金和 revision。`PositionCacheAfterCommitSynchronizer` 负责在数据库事务提交后进行本机加速写入，`PositionCacheCoordinator` 当前可以扫描 PostgreSQL 重建 Redis。

Redis 是查询投影，不应承担资金条件判断。迁移后保留 Redis 供 API、WebSocket 和后台查询，但投影输入必须统一来自账户事件，不能再由业务模块直接写入。

风险持仓事件和完整账户状态事件都在 `RedisRiskStateStore.replace` 的风险组租约内合并，避免不同 Kafka
消费线程或不同节点用旧的另一半字段覆盖新状态。账户状态的 `accountRevision` 只接受更大的修订号；
相同事件重放是幂等的。

#### Redis 持仓投影、风控和强平的影响

这次永续迁移不会删除 Redis 持仓投影，也不会把 Redis 变成资金事实源。三者的边界必须保持如下：

| 组件 | 现在的作用 | 永续迁移后的作用 | 不能做的事 |
| --- | --- | --- | --- |
| `RedisPositionCache` / `PositionCacheProjectionConsumer` | 消费 `PositionUpdatedEvent`，按持仓键和 revision 做 Lua CAS，提供查询投影 | 继续作为 API、WebSocket 和后台查询的低延迟读模型；从账户 canonical 事件重建 | 不能决定可用余额、可平数量或是否允许强平；Redis 异常不能被解释为零仓位 |
| `PositionSnapshotCache` | 风控、强平、订单各自维护的 JVM 持仓读模型，旧 revision 不覆盖新状态 | 作为各模块实时计算输入；事件缺失、产品线不符或快照未就绪时 fail-closed | 不能在没有版本栅栏时单独批准资金变更 |
| `RedisRiskStateStore` | 风险组状态、反向索引、租约和重建协调 | 继续承担跨节点协调、租约、恢复投影；风险计算直接使用本节点 JVM 风险组 | 不能在本地快照缺失时回查数据库拼装风险组 |
| `LiquidationService` 的持仓查询 | 当前仍用候选 claim、`account_positions FOR UPDATE` 和风险快照 JOIN 做最终安全复核 | 账户版本栅栏上线后改为发送带 `positionRevision` 的强平账户命令；账户单写者原子拒绝旧候选 | 在版本栅栏完成前删除数据库最终行锁或 JOIN |

因此，Redis 清空、节点重启、Kafka 重放或投影延迟只会影响查询可用性和计算 readiness，不得改变账户余额、持仓或强平事实。迁移顺序必须是“账户事件/版本栅栏 → JVM 快照 → 影子对账 → 强平命令切换”，不能先删 Redis 或先删除强平数据库复核。

### 2.3 风控

[`RiskService`](../surprising-margin-ops/surprising-risk-provider/src/main/java/com/surprising/risk/provider/service/RiskService.java) 使用 `localGroups` 和 Redis 风险组快照；`scanPositionUpdates` 在快照缺失时失败关闭，不再通过 `RiskRepository.cachedRiskGroup` 回查数据库。标记价触发只读取 Redis 反向索引命中的 JVM 风险组。风险计算结果先进入 `RiskLocalProjectionStore` 的 RocksDB 队列，再由异步投影器在数据库事务中落库。

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
- 风险计算结果先追加 `RiskLocalProjectionStore` 的 RocksDB 本地队列，再由独立任务异步写入数据库读模型；数据库不可用时不阻塞风险计算，恢复后按投影水位重放。
- 标记价/指数价过期、instrumentVersion 不匹配或状态不完整时停止强平候选生成。

验收：同一持仓和价格在重放、重复、乱序和多节点下只产生一个有效风险状态；风险恢复不能把过期行情当成最新行情。

### 阶段 6：强平 JVM 快照和版本校验

当前已完成的前置改动：风险计算结果和强平候选已携带 `positionRevision`，并写入
`risk_liquidation_candidates.position_revision`。旧构造方式和旧数据在读取时使用
`snapshotId` 兼容，避免历史候选重放失败；这一步只增加校验信息，尚未切换强平实时数据库路径。
强平服务另外以独立消费组消费账户持仓事件，维护按产品线和 revision 隔离的 JVM 快照；该快照目前仅用于影子校验与恢复准备，未替代实时执行中的数据库复核。
风控持仓触发消费者也在风险计算成功后推进同一类 JVM 快照；若风险计算失败，事件重试不会提前污染快照。
风险持仓事件路径已在快照未就绪或风险组缺失时 fail-closed 并等待 Kafka 重试，不再从 `RiskRepository.cachedRiskGroup` 读取数据库；风险事件热路径只读 JVM/Redis 快照，数据库查询仅由启动/定时恢复扫描和异步读模型投影使用。
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

## 7. 当前代码审计结果（2026-08）

以下结论来自当前源码，而不是目标设计。标记为“已完成”的内容只表示相应边界已经存在，不能等同于永续整链路已经完成 JVM 单写者切换。

### 7.1 已存在且可以继续复用的边界

| 边界 | 当前实现 | 迁移时的处理 |
| --- | --- | --- |
| 产品线隔离 | `ProductLine`、`ProductTopicNames`、provider 的 `product-line` 配置和 Topic/key 校验 | 保留，不允许为了性能合并产品线 Topic 或账户状态 |
| Instrument | instrument provider 是管理入口，其他模块消费事件维护 `InstrumentSnapshotCache` | 保留 RPC 初始化加 Kafka 增量通知；业务模块不得保留 `InstrumentRepository` |
| 费率 | order provider 使用 `FeeScheduleSnapshotCache`，缺失时使用 Instrument 默认费率 | 保留统一优先级；数据库只作为初始化和管理入口 |
| 持仓事件 | `AccountOutboxService` 发布 `PositionUpdatedEvent`，风险、强平、订单和 Redis 投影分别消费 | 将 revision 语义从数据库全局序列扩展为用户/持仓可检测的版本链 |
| Redis 持仓投影 | `RedisPositionCache` 使用 Lua revision CAS，`PositionSnapshotCache` 提供 JVM 读模型 | Redis 只做投影和跨节点查询，不参与资金决策 |
| 风险组 | `RedisRiskStateStore` 提供租约、重建、反向索引和原子替换 | 保留协调能力，实时计算使用 JVM 风险组，缺失时失败关闭 |
| 未平仓量 | `OpenInterestShardRepository` 原子调整分片并发布快照事件，订单侧有 JVM 缓存 | 后续将分片事实移入账户单写者，数据库分片只保留恢复/审计 |
| 强平候选 | 候选带 `positionRevision`，强平消费持仓事件维护 JVM 快照 | 在账户命令版本栅栏完成前保留数据库最终锁定 |

### 7.2 永续热路径与数据库边界

1. 账户命令由 `AccountUserStateCommandWorker` 按 `LINEAR_PERPETUAL:userId` 顺序消费本地 WAL，
   `AccountUserStateReducer` 在 RocksDB 状态快照中原子裁决余额、预占、持仓和资金命令。缺失快照、
   序号断裂或 reducer 不支持时分区失败关闭，不查询数据库猜测余额。
2. 订单由 `OrderUserStateService` 使用同一用户分区 WAL 和单写入 lane 管理。下单、撤单、账户结果、
   撮合结果和算法单状态都从本地状态读取；`OrderRepository` 与 `OrderStateProjectionWorker` 只做
   `trading_orders` 异步投影、后台查询和审计，不提供热路径回退。
3. `trading_cancel_all_after` 和旧的 `RedisOpenOrderView` 数据库/Redis 事实路径已删除。倒计时、开放
   订单查询和撤单裁决都来自本地用户状态；Redis 如继续部署只能作为查询或协调投影。
4. 撮合恢复、Risk、Trigger、Funding、ADL、Insurance、强平和 WebSocket 仍可能使用各自的恢复、
   审计或跨节点协调 Repository。它们不得把这些 Repository 重新接入订单/账户热路径；后续迁移需
   逐模块以事件日志、检查点和 JVM 快照替换，并保持产品线隔离。
5. 数据库状态由独立投影消费者按修订号或连续 WAL 序号幂等替换。投影落后只影响后台查询，不影响
   资金、订单或持仓裁决；投影失败必须停在当前水位并报警。

### 7.3 当前最危险的竞态

| 竞态 | 可能后果 | 必须使用的保护 |
| --- | --- | --- |
| 主动平仓与强平同时读取同一持仓 | 重复平仓或超量平仓 | 账户单写者按 `positionRevision` 和预期数量原子拒绝旧命令；切换前保留数据库行锁 |
| 订单冻结成功但事件尚未到达风险服务 | 风控钱包暂时偏大，错误放行后续风险判断 | 账户事务内写完整钱包事件；风险快照未就绪时暂停计算，不查库猜测 |
| 持仓事件乱序/重复 | Redis、风控、强平回退到旧持仓 | 按用户键分区、事件 ID 幂等、持仓版本 CAS；发现版本间隙时暂停用户 |
| Redis 清空或租约丢失 | 多节点重复重建或读到空状态 | 代次/租约/fencing token；只有完整重建后标记 ready |
| Kafka 重平衡期间旧价格继续使用 | 过期标记价触发错误强平 | 分区重新分配时从最新位置开始，并在使用时再次校验序列、时间和 instrument 版本 |
| 订单投影落后于交易事实 | 未成交订单容量、reduce-only 和查询结果不一致 | 交易事实事件先提交；投影落后返回未就绪，不回退成不完整空集合 |
| 多个跨表事务按不同顺序加锁 | 死锁、吞吐下降 | 最终切换为用户单写者；过渡期统一锁顺序并限制跨用户批处理 |

## 8. 详细执行顺序与代码改造清单

### 阶段 A：建立审计基线（先做，当前阶段）

- 固定永续产品线的 Topic、key、consumer group、分区数和每个事件的 schema。
- 给账户、持仓、订单、风险、强平、行情、Trigger 和 WebSocket 增加 readiness、lag、applied revision、rejected reason、rebuild generation 指标。
- 将每个热路径数据库调用登记到代码清单；新增调用必须在 Repository 边界测试中声明“恢复/审计/最终安全校验”用途。
- 建立资金守恒基线：余额、冻结、订单预占、成交、手续费、资金费、强平费、保险基金、ADL、持仓保证金、未平仓量逐项对账。

门禁：基线脚本和单模块测试通过，未完成项必须有明确 owner、输入事件和退出条件。

### 阶段 B：账户单写者过渡层

涉及：`AccountUserStateCommandWorker`、`AccountUserStateReducer`、`AccountService`、`AccountStateProjectionService`。

1. 引入永续用户状态快照，至少包含余额/冻结、欠款、订单预占、逐仓保证金、持仓、持仓模式、杠杆、未平仓量和每个子状态版本。
2. 命令进入固定的 `LINEAR_PERPETUAL:userId` 单用户执行队列；事实先写入本地 WAL，数据库仅异步接收完整快照投影。
3. 事件必须带 `commandId`、`eventId`、账户修订号、产品线、合约版本、`traceId` 和前置版本，并由
   `UserPartitionWal` 的事件指纹索引幂等。
4. 账户重启先加载最新检查点，再按用户键重放事件；事件缺失、版本回退或校验失败时该用户保持 not-ready，不得返回零余额/零持仓。
5. 为账户命令增加 fencing token。节点失去用户租约后，不能再提交命令结果或发布后续事件。

门禁：同一用户并发下单/撤单/成交/资金费/强平、重复命令、乱序事件、进程中断和重放后，本地
事实状态逐字段一致，数据库投影最终与最新快照一致。

### 阶段 C：下单、订单状态和下单模式

涉及：`OrderService`、`OrderPlacementStateService`、`OrderUserStateService`、`OrderMarginSnapshotCache`、
`OrderLocalStateCoordinator`、`OrderStateProjectionWorker`。

- 先消费账户余额/持仓/持仓模式/账户命令结果事件，形成完整的订单入口 JVM 快照。
- `OrderService` 的永续校验只读 Instrument、费率、标记价、余额和订单索引快照；快照未 ready 直接拒绝并返回明确原因。
- 账户预占命令携带 `accountRevision`、预期可用余额和订单版本，由账户单写者原子接受或拒绝；订单 provider 不再自己用数据库余额判断后再发命令。
- 未成交订单索引由订单事件建立，Redis 只保存查询/协调投影；投影缺失不回退为“无订单”。
- 持仓模式、逐仓/全仓、HEDGE/ONE_WAY、reduce-only 可用数量必须来自同一个用户快照，禁止分别查询再组装。

门禁：下单、改单、撤单、批量撤单、平仓和触发单在余额不足、模式冲突、缓存延迟、重复请求及重启后结果一致。

### 阶段 D：撮合、结算和资金相关模块

涉及：`ExchangeCoreEngine`、`MatchingOrderBookRecoveryRepository`、`MatchingProtectionRepository`、`AccountService`、Funding/ADL/Insurance providers。

- 撮合保护（自成交、用户挂单索引、订单版本）迁移到按 symbol 分片的无锁/单写者内存索引；Kafka 事件是增量来源，启动用检查点和事件重放恢复。
- 订单簿恢复改成“持久事件日志 + 周期检查点 + 未确认尾部重放”，数据库恢复仓储只保留灾备入口。
- 成交只发布一个 canonical `MatchResult/MatchTrade` 事件；账户按 `tradeId + participantRole` 幂等结算，手续费、资金费、强平费和 ADL/Insurance 均转成账户命令。
- 账户结算完成后再发布持仓、未平仓量和订单状态事件；禁止模块自行写持仓或余额表。

门禁：撮合重启、重复成交事件、Kafka 重放、订单簿检查点损坏、结算节点切换后，成交数、持仓、余额和流水逐项一致。

### 阶段 E：风险、行情和强平

涉及：`RiskService`、`RedisRiskStateStore`、`LatestMarkPriceCache`、`LatestIndexPriceCache`、`LiquidationService`、`LiquidationRepository`。

- 风险 JVM 组以账户完整快照和持仓事件为输入，Redis 仅做跨节点租约/协调/恢复；实时计算不调用 `RiskRepository.cachedRiskGroup`。
- 风险事件必须走可靠 outbox，不能只依赖异步 `KafkaTemplate.send` 的日志回调；强平消费组必须能从事件恢复风险状态。
- 标记价、指数价必须带 `sequence`、`instrumentVersion`、事件时间和最大年龄；价格缺失或过期时停止产生强平候选。
- 强平候选保存 `positionRevision`、`riskRevision`、`markSequence`、预期数量和账户版本。当前阶段保留数据库 claim 与 `FOR UPDATE`；版本栅栏上线并完成演练后才删除实时风险 JOIN。
- 强平最终发账户命令，账户单写者原子校验持仓版本/数量/风险状态；校验失败只取消旧候选，不允许猜测新数量。

门禁：价格断流、持仓先被主动平仓、重复候选、多节点租约丢失、Redis 清空和强平节点重启均不重复平仓、不漏平仓。

### 阶段 F：Trigger、WebSocket、API 与读模型

- Trigger 本地索引按产品线/symbol 分片，Redis 负责租约和跨节点协调；触发单事件版本落后时丢弃，重建期间不执行触发。
- WebSocket 私有资金/订单/持仓/风险事件使用可恢复有界队列，公共行情使用合并和背压队列；慢连接不能阻塞 canonical Kafka 消费。
- API 查询区分事实写入、JVM 快照和 Redis/数据库读模型；快照未 ready 返回可诊断的暂不可用，不返回空资金或空持仓。
- cursor、订单状态墓碑、事件版本和产品线必须贯穿 API、WebSocket 和后台查询。

### 阶段 G：正式切换与关闭旧热路径

只有阶段 B-F 的门禁全部通过后，才允许：

1. 永续账户 JVM 单写者成为唯一资金/持仓写入边界。
2. 数据库降级为异步投影、恢复、审计和对账；删除永续实时查库回退，但保留显式灾备/人工入口。
3. Redis、JVM、Kafka 和数据库投影做一致性抽样与全量资金对账。
4. 进行滚动升级、双节点/多节点、Kafka 重平衡、Redis 故障、数据库不可用、SIGKILL、时钟漂移和检查点损坏演练。
5. 通过永续全链路脚本后，再考虑推广到其他产品线；任何产品线不得共享永续账户状态或 Topic。

## 9. 当前第一批实际动作

本轮先不删除任何 Repository，也不关闭强平数据库最终校验，按以下顺序继续：

1. 已把本节审计结果作为后续改造的唯一清单；账户 outbox 已修复产品线模式下遗漏未平仓量和风险钱包 Topic 的问题。
2. 已增加 `PerpetualAccountStateUpdatedEvent` 和 `PerpetualAccountStateSnapshotCache` 的基础协议；当前事件由数据库事务组合生成，仍属于影子迁移层，尚未用于放行资金操作。
3. 将 `PositionUpdatedEvent` 的全局缓存 revision 与用户账户 revision 分离，建立可检测的用户事件连续性；账户完整状态事件已经复用同一 `accountRevision`。
4. 已让风险和强平模块使用独立消费组接收完整账户状态事件，并维护各自的影子 JVM 快照；当前尚未把该快照作为放行资金或强平的唯一依据，启动恢复就绪协议完成后再切换。
5. 已接入订单模块的永续完整账户状态消费和持仓模式快照读取；模式快照未追赶到 Kafka 高水位或缺少用户状态时拒绝下单，不回查账户模式表。永续订单预占现在携带 `expectedAccountRevision`，账户单写者在冻结前校验当前 revision，过期命令直接拒绝，避免旧快照错误冻结；余额/冻结事实仍由账户单写者确认。
6. 每完成一个子模块执行对应 Maven 测试、`check-*` 边界脚本和永续资金核对，独立提交。

## 10. 永续真实业务演练门禁

代码测试通过不等于交易链路正确。永续切换前必须按生产启动顺序运行真实进程和模拟用户 API，
先启动 instrument provider，确认所有业务模块的 instrument JVM 快照就绪，再启动账户、行情、
撮合、下单、风控、强平、Trigger、WebSocket 和做市进程。演练只启用永续产品线，不启动 wallet 服务。

演练必须覆盖下单、撤单、部分成交、完全成交、主动平仓、只减仓、强平、Trigger、资金费、服务重启、
Kafka 重平衡、Redis 清空、数据库只读/不可用和双节点并发。每个场景逐项核对余额、冻结、持仓、
保证金、手续费、资金费、强平费用、未成交订单和 WebSocket 私有/公共事件；发现任何资金、并发、
幂等、顺序或恢复问题，先固定最小复现并修复，再继续下一项，不允许带缺陷切换下一条产品线。

永续全部门禁通过后，使用相同的事件协议、快照就绪、版本栅栏、单写者和故障演练模板逐条迁移现货、
交割和期权；四条产品线继续使用独立 Topic、账户类型、instrument 快照和风险模型。
