# P10 单物理 Product Core 确定性 Lane 实施规范

> 状态：设计冻结，尚未实施。
>
> 本文定义 P10 的唯一实施方向。P10 不拆分物理 Product Core，不改变“一条产品线对应一个三节点
> Aeron Cluster”的部署边界，而是在同一个 Product Core 内增加确定性的 Matcher、Account 和 Treasury
> Lane。本文不是当前运行能力声明；只有完成文末全部门禁后，才能把 P10 标记为完成。

## 1. 目标与非目标

### 1.1 目标

- 保持一个 ProductLine 一个三节点 Product Core、一个 Aeron Cluster Log、一个全局 Core sequence 和一条事实链。
- 按 symbol 并行推进互不相关的 exchange-core matcher，消除全产品线只有一个 matcher command in flight 的瓶颈。
- 按 userId 将余额、冻结、订单元数据、持仓、风险和生命周期状态分给固定 owner 线程；同一用户严格串行，
  不同 Account Lane 可以并行。
- 将手续费、保险基金、亏损缺口、资金费残差、交割/行权和舍入残差分给固定 Treasury Lane。
- 跨 Lane 成交仍具有确定的原子可见性、资金守恒和唯一 Core Fact，不使用业务锁，不创建第二套权威状态。
- 快照同时覆盖 Lane topology、所有 Lane 状态、exchange-core 原生模块、全局序列和事实连续性，并且只允许
  一致的整组快照恢复。
- 为数百个合约、1,000 个以上活跃用户以及后续容量扩展提供线性可解释的并行度。

### 1.2 非目标

- 不把热点 symbol 部署到独立物理 Core，不引入跨 Core 两阶段提交。
- 不让 Gateway、Provider、Kafka、PostgreSQL 或 History Projector 参与在线资金裁决。
- 不在 Lane 之间共享可变 Map、订单对象、余额对象或 exchange-core 事件对象。
- 不复制 exchange-core 的可执行订单簿，也不按订单回放重建原生 book。
- 不支持运行中动态修改 Lane 数量、hash seed 或 symbol route。P10 第一版只支持重启前离线配置并从 fresh
  compatible state 启动；在线迁移另立版本和专项设计。
- 不为旧快照、旧协议或旧路由保留 legacy reader、fallback 或隐式迁移。

## 2. 不可破坏的权威边界

### 2.1 唯一权威

| 状态 | 唯一权威 | 禁止的第二权威 |
|---|---|---|
| 全局顺序、命令幂等、事实链 | Product Core Sequencer | Kafka offset、数据库主键、Provider 本地序列 |
| 盘口、价格时间优先、撮合结果 | 对应 Matcher Lane 中的 exchange-core | Core 自建盘口、投影表、行情缓存 |
| 用户资金、冻结、持仓和活动订单 | userId 所属 Account Lane | `TradingCoreState`、数据库、异步回调 |
| 费用、保险基金、残差和亏损缺口 | asset 所属 Treasury Lane | Projector 汇总、账户服务余额 |
| 恢复事实 | Cluster Log/Archive 与完整配对快照 | PostgreSQL、Kafka replay、逐单 rebuild |

`TradingRuntimeState` 继续是运行时业务权威的统一入口，但内部改为 Lane 容器和路由 facade；不得创建另一个
完整 `TradingRuntimeState` 或恢复 `TradingCoreState` 的热路径写入权。`TradingCoreState` 仍只是按确定顺序生成的
不可变 Snapshot State，用于快照、恢复、hash、Core Fact 和对账。

### 2.2 单一事实顺序

Aeron Cluster owner 接收的 command 先得到唯一 `coreSequence`。所有 Lane work、ACK、commit、Core Fact、outbox
和 query visibility 都绑定该 sequence。工作线程完成顺序可以不同，但对外提交顺序不能越过尚未完成的前序
sequence。

一个命令可以产生多个 Lane posting，但只能产生一个全局终态结果和一组确定排序的 `FundsDelta`。如果存在
matcher 前的资金预占，则预占本身是已经生效的权威状态，必须有自己的可恢复事实边界；不能为了追求“一个事件”
把真实冻结隐藏在内存 pending 状态中。

## 3. Lane 拓扑与确定性路由

### 3.1 三类 Lane

```text
Aeron Cluster owner / Sequencer
          |
          +-- MatcherLane[matcherLaneId]   owner: symbol 对应 exchange-core
          +-- AccountLane[accountLaneId]   owner: 固定 userId 集合
          +-- TreasuryLane[treasuryLaneId] owner: 固定 asset 集合
```

- **Sequencer**：分配全局 sequence、校验 source/command identity、选择路由、管理 credits、收集 ACK、推进全局
  commit、生成 Core Fact/outbox、执行 snapshot fence 和 readiness gate。
- **Matcher Lane**：独占一个 exchange-core 实例及其 technical user/symbol registry、matcher sequence、prefix digest、
  engine/book hash 和原生快照。一个 symbol 在一个 routeVersion 内只能属于一个 Matcher Lane。
- **Account Lane**：独占所属用户的余额、冻结、活动订单元数据、reservation、持仓、条件单、风险状态、强平状态、
  client-order/source identity 的用户侧索引。
- **Treasury Lane**：独占所属资产的 fee、insurance、deficit、funding residual、rounding residual、delivery/exercise
  clearing 子账本。

### 3.2 路由规则

- Matcher route 是 Instrument State 的版本化字段：`symbol -> matcherLaneId`。热点 symbol 可以独占 Lane，其余 symbol
  可以静态装箱；同一 symbol 永不跨 Lane 并发。
- Account route 固定为
  `accountLaneId = mix64(userId XOR accountLaneSeed) & (accountLaneCount - 1)`；`accountLaneCount` 必须是 2 的幂。
- Treasury route 固定为
  `treasuryLaneId = mix64(assetId XOR treasuryLaneSeed) & (treasuryLaneCount - 1)`；Lane 数少时可配置为 1。
- `routeVersion`、Lane counts、hash seeds、symbol route table 和 route table hash 都写入 Cluster state、Core Fact 证据和
  snapshot manifest。
- 外部 API 不接受也不返回 Lane ID；Provider 只提交业务 identity，防止客户端绑定内部拓扑。
- P10 v1 启动后禁止改变上述配置。收到不一致的 instrument route、snapshot route 或 member 配置时 fail closed。

### 3.3 容量与背压

每条 Lane 使用固定容量、启动时分配的队列和工作槽。Sequencer 在任何业务状态变化或 matcher 提交之前，一次性取得
本 command 所需的所有 queue credit、transaction slot 和 result slot。任一资源不足时立即返回明确的
`CORE_BACKPRESSURE`，不得先冻结资金、再等待队列；不得用无界队列或无限重试掩盖饱和。

credits 只能由 Sequencer 线程维护；Lane 通过固定 SPSC 完成队列归还 credit。不得为 credits 使用锁、CAS 竞争 Map
或跨线程共享计数器。

## 4. 线程、队列和内存模型

### 4.1 Owner 规则

- 每个 Lane 只有一个固定 owner thread；只有 owner 能修改该 Lane 的 primitive collections 和 exchange-core 实例。
- Sequencer 与 Lane 间只传递不可变值对象或预分配槽位的只读句柄。
- Lane 之间不能直接调用彼此的可变对象，也不能直接写另一 Lane 的 ACK、余额或状态。
- Query 不直接读取 Lane Map。Query 由 Sequencer 路由到有界 query queue，只返回已全局 commit 的版本。
- asynchronous callback 只能发布完成通知，不能修改 Runtime State。

### 4.2 队列

现有共享 `MatchingCompletionQueue` 的 many-to-one 模式改为每个生产者/消费者对一条固定 SPSC 队列，优先使用
Agrona `OneToOneConcurrentArrayQueue`。

| 方向 | 队列 | 单写者 | 单读者 |
|---|---|---|---|
| Sequencer -> Matcher Lane | command queue | Sequencer | Matcher owner |
| Matcher Lane -> Sequencer | result queue | Matcher owner | Sequencer |
| Sequencer -> Account Lane | posting/query queue | Sequencer | Account owner |
| Account Lane -> Sequencer | ACK/query result queue | Account owner | Sequencer |
| Sequencer -> Treasury Lane | posting queue | Sequencer | Treasury owner |
| Treasury Lane -> Sequencer | ACK queue | Treasury owner | Sequencer |

若一个 Sequencer 不能作为所有 Lane command queue 的单写者，必须先修改拓扑而不是将队列降级为 MPSC。禁止
`synchronized`、`Lock`、`ConcurrentHashMap` 业务状态、`parallelStream`、Future join、基于 wall clock 的批次提交。

### 4.3 分配约束

- command、matcher result、posting、ACK 和 transaction context 使用固定 ring/array slot；终态后清零引用并归还。
- exchange-core 开启 `EVENTS_POOLING`；`MatcherResult`、event list 与 market data 在发布后不可变，Core 直接消费，
  不再进行第二次 event/market-data 复制。
- `FundsDelta` 和 Snapshot State 只在事实或快照边界物化；普通处理中使用 primitive posting buffer。
- 风险扫描不得执行 `Set.copyOf`、`toArray` 或全局排序；使用 Lane 内稳定 primitive cursor。
- 所有容量都必须配置上限并导出 high-water mark、拒绝数和占用率。

### 4.4 复杂度预算

Lane 化只允许增加四类必要概念：静态 `LaneTopology`、三类 owner-local state、不可变 `SettlementPlan` 和固定容量
transaction slot。不得再引入 actor framework、通用事务协调器、动态 rebalance、分布式锁、MVCC 版本链、补偿 Saga、
第二套 Runtime 或 Lane 专用业务 kernel。

现有 facade、processor、六个 settlement kernel、Core Fact 和 snapshot codec 应原位演进；公共行为通过 primitive
posting/applier 复用。每个阶段如果需要多级 callback、递归调度、跨 Lane 直接调用或超过一个 prepared slot 才能正确，
说明阶段边界设计错误，应停止实施并收窄 in-flight，而不是继续叠加抽象。

## 5. 命令执行模型

### 5.1 单 Lane 命令

1. Sequencer 验证 envelope、source identity、routeVersion 和 credit。
2. 分配 `coreSequence` 与 transaction slot，发送不可变 posting。
3. Lane owner 校验本地 revision，原地应用并返回 hash/revision ACK。
4. Sequencer 按全局 sequence 提交可见性，生成结果、FundsDelta、Core Fact 和 outbox。

### 5.2 下单与撤单

普通下单不增加独立 Core preflight 往返。正式 `PLACE_ORDER` 的权威流程为：

1. Sequencer 固定 `coreSequence`，解析 symbol route 和 user route，预留全部 credits。
2. Account Lane 在一次 owner 转换中验证余额、平仓容量、风险参数、identity 和订单状态，并落地 reservation。
3. reservation 提交为可恢复事实后，Sequencer 才将 matcher command 发给对应 Matcher Lane。
4. Matcher Lane 只执行一次 exchange-core command，返回 fork 直接产生的不可变 `MatcherResult`、matcher sequence 和
   prefix before/after。
5. Sequencer 从结果生成确定的 `SettlementPlan`，发往涉及的 Account/Treasury Lane。
6. 所有 Lane prepare/apply ACK 后，Sequencer 按 sequence 发布 commit visibility、更新全局 hash、生成 terminal fact。

撤单沿用相同边界：先由 matcher 产生权威结果，再释放 Account Lane reservation。禁止外层先根据缓存订单状态推断撤单
成功。

### 5.3 Matcher 并行度

每个 Matcher Lane 同时最多执行一个 command；不同 Matcher Lane 可以并行。因此“one matcher command in flight”从
全产品线约束缩小为“每个 symbol owner Lane 一个”，既保持同一 book 的严格顺序，也允许数百 symbol 利用多个核。

Sequencer 可以连续派发多个无依赖的后续 sequence，但全局 commit cursor 只按 sequence 前进。为防止一个慢 symbol
无限阻塞所有结果，必须为每 Lane 设置有界 in-flight/window、超时监控和 fail-closed watchdog；不能跳过前序事实提交。

## 6. SettlementPlan 与跨 Lane 原子可见性

### 6.1 Plan 内容

`SettlementPlan` 是 Sequencer 根据不可变 MatcherResult 和命令前状态生成的不可变值，至少包含：

- `coreSequence`、`commandId`、`productLine`、`symbolId`、`routeVersion`、`matcherLaneId`；
- matcher sequence、prefix before/after、order/trade identities；
- 每个目标 Account/Treasury Lane 的期望 before revision/hash；
- 按 `(laneType, laneId, asset, ownerKind, ownerId, subledger)` 排序的 primitive postings；
- 订单状态、reservation 消耗/释放、持仓变化、费用、资金费、强平费、保险基金与舍入残差；
- 预期 after revision、局部 funds delta/hash 和最终结果类型。

六个 `SettlementKernel` 继续是唯一产品差异边界。Lane 化不能复制六套业务逻辑；kernel 负责计算 plan，统一 posting
applier 负责 owner-thread 原地应用。

### 6.2 Prepare、ACK、Commit

跨 Lane 原子性通过确定性可见性屏障实现，不通过锁或数据库事务实现：

1. Sequencer 先完整计算并验证 plan；所有会导致正常业务拒绝的检查必须在派发前完成。
2. 各 Lane owner 校验 expected revision，然后原地写入自己的 staged slot，记录 `preparedSequence`，返回 ACK。
3. prepared 数据不对 query、风险扫描、下一条冲突命令或事实 hash 可见。
4. Sequencer 收齐全部 ACK 后发布该 sequence 的 commit marker。
5. 各 Lane owner 将 staged revision 提升为 committed revision；Sequencer 收齐 commit ACK 后推进全局
   `committedCoreSequence`，再发布结果和 Core Fact。
6. revision 不匹配、重复/缺失 ACK、posting 不平衡或 commit gap 都是确定性致命错误；member fail closed，从上一完整
   snapshot + log 恢复，不做回滚、补偿或部分继续。

P10 v1 每个 Account/Treasury Lane 只允许一个 prepared transaction；Sequencer 不向该 Lane 派发下一条 posting，
其他不相交 Lane 仍可前进。这样只需固定双缓冲/undo-free staging slot，不需要锁、版本链或通用事务管理器。

### 6.3 资金守恒

每个 plan 在派发前和提交后都验证：

```text
用户可用 + 用户冻结 + 手续费 + 保险基金 + 亏损缺口
+ 资金费残差 + 舍入残差 + 交割/行权清算子账本
= 同资产命令前总额 + 明确外部充值/提现/调整
```

不得跨资产相加。所有 posting 使用整数最小单位和现有操作级舍入规则；禁止 Lane 层重新计算价格、PnL 或 fee。

## 7. 六产品线和生命周期

### 7.1 现货

- 买单 quote、卖单 base reservation 归用户 Account Lane。
- maker/taker 成交可以涉及两个 Account Lane 和至多两个 Treasury asset Lane。
- 部分成交按 matcher event 顺序消耗冻结；撤单释放剩余冻结。
- 触发单激活后使用同一正式下单路径，不建立独立订单权威。

### 7.2 U 本位永续与交割

- 仓位、开仓保证金、reduce-only close capacity 和未实现/已实现 PnL 归 Account Lane。
- 手续费、资金费残差、强平费和保险基金归 settlement asset Treasury Lane。
- 资金费扫描按 `(accountLaneId, userId, positionId)` 稳定 cursor 推进并记录进度。
- 交割先对 symbol 建立 lifecycle fence，排空 Matcher Lane，再向相关 Account Lane 派发结算 plan。

### 7.3 币本位永续与交割

- 币本位 PnL、保证金和费用只能由对应 inverse kernel 以整数/定点规则计算。
- 反向除法残差明确进入 Treasury rounding/funding residual，不允许由不同 Lane 各自舍入后丢失。
- 到期结算同样使用 symbol fence、matcher drain 和全局 commit barrier。

### 7.4 期权

- 权利金在买卖双方 Account Lane 与 Treasury fee Lane 间作为同一 plan 结算。
- 行权/到期失效先 fence symbol，冻结新订单和触发激活，排空 matcher，再按稳定 position cursor 计算。
- 买方权益、卖方保证金、exercise settlement 和 residual 必须在同一 sequence 的跨 Lane barrier 后可见。

### 7.5 标记价、风险、强平、ADL 与触发单

- mark update 先由 Sequencer 提交版本；维护 `symbol -> affected accountLane bitset`，只 fanout 到受影响 Lane。
- 每个 Account Lane 按本地稳定 `(symbolId, userId, positionId)` cursor 扫描，不复制集合、不全局排序。
- 强平决策在用户 Lane 内形成；强平订单走对应 Matcher Lane；强平费、保险基金和 deficit 走 Treasury Lane。
- ADL 候选排名在 Account Lane 内维护增量有序索引；Sequencer 用按 Lane ID 固定顺序的 deterministic k-way merge。
- 触发单状态归 Account Lane；触发后生成稳定 child identity，经正式 order command 流程进入 matcher。

## 8. 查询与可观测性

### 8.1 查询一致性

- online query 只读取 `committedCoreSequence` 及之前状态。
- 单用户查询路由到一个 Account Lane；book query 路由到一个 Matcher Lane；Treasury query 路由到一个 Treasury Lane。
- 跨 Lane 对账查询由 Sequencer 建立 read fence，收集相同 committed sequence 的局部结果后合并。
- 结果超过固定实体/字节上限时返回 `QUERY_RESPONSE_TOO_LARGE`，不能长时间占用 owner。

### 8.2 必须导出的指标

- 每 Lane queue depth、capacity、high-water mark、backpressure rejects；
- 每 Lane command/settlement/query/risk scan 延迟及最老 pending sequence；
- matcher in-flight、matcher sequence、prefix continuity、events depth、book/active order size；
- Account/Treasury prepared sequence、ACK/commit gap、revision mismatch；
- global dispatched/committed/exported sequence 与 lag；
- outbox depth/oldest age、Kafka publish lag、Projector lag、WebSocket fanout lag；
- heap、allocation rate、G1/ZGC pause、direct memory、Aeron buffers、thread CPU、context switch；
- 每资产 funds hash、state hash、Lane local hash、snapshot capture/restore 时长和失败原因。

标签只允许 productLine、laneType、laneId 和有界 result code；禁止 userId、orderId、symbol 全量高基数标签。

## 9. 快照一致性协议

### 9.1 Snapshot fence

Snapshot 只能在完整全局提交边界发布：

1. 记录 `snapshotFenceSequence`，停止接收会改变状态的新 command；只允许有界状态查询。
2. 停止向 Matcher Lane 派发新命令，排空所有 matcher in-flight、result queue 和 completion gap。
3. 排空已派发 Account/Treasury posting；等待所有 Lane `preparedSequence=0`、commit ACK 收齐且
   `committedCoreSequence=snapshotFenceSequence`。
4. 验证 deferred command、lifecycle partial batch、risk batch、pending matcher transaction 和 lane queue 全部为空。
5. 冻结 topology、instrument registry、identity ledger、outbox watermark、terminal retention 和 risk scan cursor。
6. 按 matcherLaneId 升序调用 exchange-core 原生 snapshot，记录每 Lane module、sequence、prefix、engine/book/order hash。
7. 按 accountLaneId、treasuryLaneId 升序，由各 owner 编码自己的不可变 section；snapshot 线程不直接遍历 owner Map。
8. Sequencer 编码全局 section、manifest、每 section CRC 和整体 digest。
9. 再次读取所有 Lane revision/sequence/hash；不一致则丢弃整组 snapshot，不能发布部分结果。
10. 三节点使用相同格式和确定排序完成 snapshot；成功后解除 fence。

任何 Lane capture 超时、原生 matcher snapshot 失败、队列非空、prefix 断裂、资金不平、CRC/digest 失败都使整次
snapshot 失败。旧快照继续有效；不得发布“其余 Lane 成功”的 snapshot。

### 9.2 Snapshot 结构

当前 `Trading snapshot v23` / `sectioned snapshot v13` 在 P10 实现时必须提升主版本，并删除旧版本读取路径。新格式至少
包含：

1. Header：magic、schema、productLine、`coreShardId=default`、snapshotId、fence/committed sequence。
2. Topology：routeVersion、三类 Lane count/seed、symbol route table/hash、queue capacities、配置 hash。
3. Sequencer：command/source identity ledger、global prefix、pending slot 必须为空、risk/lifecycle global cursor。
4. Instrument/identity registries：symbol、asset、user technical mapping 与版本 hash。
5. Account Lane sections：users、balances、orders、reservations、positions、triggers、risk、liquidations、ADL、indexes、
   revision、local state/funds hash。
6. Treasury Lane sections：fee、insurance、deficit、funding/rounding residual、clearing、revision 和 funds hash。
7. Matcher Lane sections：原生 `MATCHING_ENGINE_ROUTER/<laneId>`、`RISK_ENGINE/<laneId>` 载荷，matcher sequence、prefix、
   engine/book hash、active-order hash、fork artifact/config hash。
8. Outbox/terminal retention：未确认事实、export watermark、terminal identity、结果缓存和有界淘汰水位。
9. Footer：section directory、长度、逐 section CRC、全局 state/funds hash、整体 digest。

全局 hash 只按固定 `(laneType, laneId)` 顺序组合各 Lane hash，不能按线程完成顺序组合。Hash 输入必须覆盖 topology。

### 9.3 Snapshot 发布前交叉核对

- 每个 OPEN 业务订单在其 symbol 对应 Matcher Lane 中恰好存在一次。
- 每个 matcher 活动订单都有 Account Lane 订单元数据和有效 reservation；terminal 订单不在 matcher。
- 每个 user/asset 只存在于 hash 路由指定的 Account/Treasury Lane。
- position、trigger、risk/ADL index 与主状态双向一致。
- 每资产 Account + Treasury 资金守恒，Lane local hash 组合值等于 global funds hash。
- 所有 matcher prefix、Core fact prefix、outbox sequence 连续，snapshot 不包含 prepared transaction。

## 10. 恢复与 READY 门禁

恢复严格按以下顺序执行，任何一步失败都 fail closed：

1. 读取 header、section directory、长度、CRC 和整体 digest；拒绝未知主版本和截断数据。
2. 校验 productLine、`coreShardId=default`、routeVersion、Lane count/seed、symbol routes、fork/JAR/config hash。
3. 只构造固定 topology、owner threads、预分配 slots 和 SPSC queues；不接收流量。
4. 恢复 instrument、asset、technical user 和 identity registry，并校验无重复 ID 和 route hash。
5. 按 accountLaneId 恢复 Account Lane，逐用户重算 owner route，重建局部索引并验证 local state/funds hash。
6. 按 treasuryLaneId 恢复 Treasury Lane，验证 asset route、子账本和 local funds hash。
7. 按 matcherLaneId 只使用 exchange-core 原生 snapshot 恢复 matcher；禁止按业务订单 rebuild book。
8. 验证每 Lane matcher sequence、prefix、engine hash、book hash、active-order hash 和 fork config。
9. 执行订单/reservation/book、position/risk index、Account/Treasury 资金及 global hash 的全量交叉核对。
10. 恢复 command/source identity、outbox、terminal retention、risk/lifecycle cursor 和 exported watermark。
11. 从 snapshot position 继续按 Cluster Log 顺序 replay；路由和 commit barrier 与在线路径完全相同。
12. replay 结束后验证所有队列空、prepared=0、无 sequence/ACK gap、matcher/Core/outbox prefix 连续。
13. 三个 Member 完成相同门禁，leader 才发布 READY；Provider 在 READY 前不能降级到 clean start。

### 10.1 崩溃边界

| 崩溃位置 | 恢复结果 |
|---|---|
| reservation 之前 | command 未产生状态；按 identity 正常 replay |
| reservation fact 之后、matcher 之前 | 恢复 reservation，再按同一 matcher identity 继续一次提交 |
| matcher 提交后结果未知 | 依赖 Cluster Log 顺序和 native matcher snapshot/prefix 判定；不得盲目 resubmit |
| 部分 Lane prepared、commit 前 | 未发布 snapshot；fail closed，从上一完整 snapshot + log 重演 |
| commit marker 后、terminal fact 前 | replay 按 sequence 完成相同 commit/fact，不做补偿事务 |
| Core Fact 已入 outbox、Kafka ACK 前 | replicated outbox 用 fact identity 幂等重发 |
| snapshot 任一 Lane capture 失败 | 整组 snapshot 丢弃，上一快照保持可恢复 |

P10 实现前必须为“matcher 提交后结果未知”定义并验证精确的 log/prefix 状态机；不能无歧义判断时不得启用多
Matcher Lane。

## 11. 当前项目的具体修改清单

以下是实施 P10 时必须修改的边界，不代表当前已修改。

### 11.1 `surprising-aeron-protocol`

- `CoreRoute` / command header：加入或启用 `routeVersion`，不暴露调用方提供的 laneId。
- `UpsertInstrumentCommand`：携带受版本保护的 `matcherLaneId`，校验 symbol route 不可在线变更。
- `CoreMatcherTransition`：改为包含 `matcherLaneId` 的 lane-local sequence/prefix；全局 fact 仍绑定 coreSequence。
- `CoreExportEvent` 与 codec：加入 topology hash、Lane revision/hash 和 committed sequence；升级 marker 并 fail-old。
- query/snapshot view：明确 committed sequence 和 routeVersion，不能返回 prepared 数据。

### 11.2 `surprising-aeron-service/CoreProbeState`

- 从“一个 adapter + 一组全局 pending matching”改为 Sequencer、固定 `MatcherLane[]`、`AccountLane[]`、
  `TreasuryLane[]` 和 transaction slot ring。
- 将全局 one-in-flight 改为每 Matcher Lane one-in-flight，并维护按 coreSequence 的有界 commit window。
- ingress、credit、route、ACK、commit cursor、fact/outbox 和 snapshot fence 只由 Cluster owner 修改。
- callback 只写对应 SPSC result queue；snapshot 实现第 9 节完整 barrier。

### 11.3 `TradingCoreRuntime` 与 `TradingRuntimeState`

- `TradingCoreRuntime` 继续是统一 facade，新增 topology、lane route、committed sequence 和 read fence。
- `TradingRuntimeState` 不删除、不复制；内部拆成 `AccountLaneState[]`、`TreasuryLaneState[]` 与只读 topology。
- users、balances、orders、reservations、positions、triggers、liquidations、risk/ADL 和用户索引移动到 Account Lane。
- fee/insurance/deficit/funding residual/rounding/clearing 移到 Treasury Lane。
- 风险扫描集合复制/`toArray` 改成 Lane 内稳定 primitive cursor，并让 cursor 进入 snapshot。
- `RuntimeCommandProcessor` 拆成 plan 计算与 lane-local posting apply；不能直接跨 Lane 修改 Map。

### 11.4 Matcher 边界

- `DeterministicExchangeCoreAdapter` 每 Matcher Lane 一个实例；独占 exchange-core、registry、sequence/prefix 和 snapshot。
- `CoreMatchingResult` 直接携带不可变 fork `MatcherResult` 和 lane identity；不得再次复制 event/market data。
- `MatchingCompletionQueue` 替换为每 Lane 固定 SPSC command/result queues。
- `MatcherSnapshot` 升级为带 laneId 的重复 manifest；仍要求 native snapshot-only recovery。
- `EVENTS_POOLING` 必须开启，以不可变发布契约、对象生命周期测试和 JFR 分配证据证明不会复用已发布事件。

### 11.5 结算、风险与索引

- 六个 `SettlementKernel` 输出统一 `SettlementPlan`，不能直接修改多个 Lane。
- 新增轻量 primitive posting/applier 和固定 transaction slot；不引入通用 Saga、事务框架或对象图。
- 活动订单索引支持 `user -> orders` 与 `symbol -> affected accountLane bitset`，由 owner 增量维护。
- funding、delivery、exercise、liquidation、ADL、trigger cursor 全部带 laneId 并可快照恢复。
- 跨 Lane 业务测试逐资产核对 Account/Treasury posting 和 `FundsDelta` 完全相等。

### 11.6 Snapshot codec 与恢复

- `TradingStateSnapshotCodec`、`SectionedCoreSnapshotCodec/Writer/Parser/Recovery` 同步提升主版本。
- section directory 支持重复 lane section、明确 laneType/laneId、长度和 CRC；拒绝缺失、重复和越界 Lane。
- `CoreStateSnapshotCodec.VERSION=0` 的禁用 legacy wrapper 不得复活。
- `MatcherSnapshot`、`CoreMatcherTransition`、global/lane hash 和 topology manifest 使用同一 routeVersion。
- 所有恢复检查通过前不替换 active runtime、不发布 READY。

### 11.7 Exporter、Projector、WebSocket 与外围服务

- Audit Exporter 继续从 replicated outbox 按 global coreSequence 发布 Core Fact，不按 Lane 各建事实流。
- History Projector 只幂等投影，不参与 ACK/commit。
- WebSocket 直接消费 Kafka 事实 fanout；不恢复 `core_websocket_audit_projection` 或数据库中转。
- Provider、Gateway、做市和指数/标记价服务无感知 Lane；只处理 READY、backpressure 和 command identity。
- 运维工具增加 topology/hash/queue/commit gap 观测，但禁止修改运行中 route。

## 12. 实施阶段

不得一次性重写整个 Core。每阶段保持唯一权威和可恢复性，完成所有阶段代码后统一进入测试波次。

### P10-A：Topology 与证据，Lane count = 1

- 建立 route/topology、Lane local sequence/hash 和指标。
- 三类 Lane count 均为 1，行为与当前串行 Core 等价。
- 升级协议/快照版本并 fail-old；证明三节点恢复语义相同。

### P10-B：Matcher Lane

- 引入多个 adapter、每 Lane SPSC 和 per-lane one-in-flight。
- Account/Treasury 暂时仍单 Lane，先验证多 symbol matcher 并行、global commit 顺序和原生快照。
- 不允许 symbol 动态迁移。

### P10-C：Account Lane 预占与单 Lane 命令

- 按 userId 分离 owner state、identity、reservation 和 query。
- 覆盖充值/调整/下单预占/撤单释放；跨用户成交暂经串行兼容 barrier。

### P10-D：SettlementPlan 与 Commit barrier

- 六 kernel 输出 plan；实现固定 slot、prepare/ACK/commit visibility。
- 覆盖 maker/taker 跨 Account Lane、跨 Treasury Lane 和相同用户自成交策略。
- mismatch fail closed，不实现回滚框架。

### P10-E：风险与生命周期

- 分片 mark fanout、risk cursor、trigger、liquidation、ADL、funding、delivery 和 option exercise。
- 删除剩余全局集合复制和全量排序热点。

### P10-F：完整快照与恢复

- 完成 Lane section、native matcher snapshots、global manifest、交叉核对和 READY 门禁。
- 验证 leader kill/rejoin、follower lag、cold recovery、Archive replay 和 snapshot capture failure。

### P10-G：容量认证

- 根据真实 CPU、队列、GC、direct memory 和尾延迟调整静态 Lane 数与 symbol 装箱。
- 只有 1,000 用户/数百 symbol/高频做市/40 分钟真实 API 运行和更高 burst 门禁通过后，才标记 P10 完成。

## 13. 测试与验收矩阵

### 13.1 确定性和并发

- 同一输入日志在不同 worker 延迟、完成顺序和三节点上产生相同 state/funds/prefix hash。
- 同 symbol 价格时间优先不变；不同 Matcher Lane 确实并行。
- 同 user 所有命令严格顺序；不同 Account Lane 无共享写、无业务锁。
- 队列满在任何状态变化前拒绝；取消、超时和重试不产生双冻结、双成交或双事实。

### 13.2 业务与资金

- 六产品线分别覆盖下单、部分/全部成交、撤单、主动平仓、止盈止损、手续费和 PnL。
- 永续覆盖 mark/funding/强平/保险基金/ADL；交割覆盖到期结算；期权覆盖权利金/行权/失效。
- maker、taker 和 Treasury 位于不同 Lane 时逐资产守恒；用户和做市账号核对期初、外部调整、成交、费用、
  资金费、强平费、交割/行权和期末余额。
- self-trade、同用户双边订单、热点 user、热点 symbol、maker fill-depth 和大持仓矩阵均有确定结果。

### 13.3 快照和故障

- 每个第 10.1 节崩溃点注入故障，恢复后 state/funds/book/prefix/outbox hash 与无故障基线一致。
- snapshot 捕获期间查询只能看到 commit 边界；不能看到一半资金、一半持仓。
- 任一 Lane section 缺失、重复、CRC 错、route 错、fork 错、book/order 不一致都拒绝 READY。
- 三节点 leader kill/rejoin、follower lag、cold recovery 和真实 Archive replay 后继续下单并保持事实连续。

### 13.4 性能

- 真实 HTTP 开放环、100k/s 目标、200k/s burst 分阶段提升；未饱和则提升 symbol/user/订单和 maker 强度。
- 至少 1,000 用户、数百 symbol、高频 maker、深盘口、可控滑点、实时指数/标记价、批量爆仓和持续风险扫描。
- 40 分钟稳定运行，记录 API -> Core -> matcher -> settlement -> fact -> Kafka -> WebSocket/Projector 的尾延迟。
- 使用 JFR/async-profiler 验证 allocation、GC pause、direct memory、queue contention、CPU 和 context switch；对照 G1/ZGC。
- 热路径不得出现业务锁；Lane 数增加时吞吐应在 CPU/单热点 symbol 极限前呈可解释扩展。

## 14. 完成定义

只有以下条件全部满足，P10 才能从“设计冻结，尚未实施”改为“完成”：

- P10-A 至 P10-G 的生产代码和协议/快照版本全部落地，不存在兼容 fallback。
- 一个 ProductLine 仍只有一个物理三节点 Product Core、一个 global Core sequence 和一条 Core Fact 链。
- matcher/account/treasury 所有权、SPSC、bounded credit、prepare/commit、query visibility 均有自动化和真实表面证据。
- 六产品线资金、持仓、平仓/爆仓/触发/资金费/交割/行权全部正确且逐资产守恒。
- snapshot、恢复、Archive replay 和三节点故障矩阵全部 fail-safe，并通过 hash/prefix 连续性核对。
- 真实 API 生产模拟达到容量门禁，GC、堆外内存、延迟和吞吐有可复核报告。
- 根 README、Aeron Core README、协议和运维说明同步为实际版本；在此之前不得声称系统已 Lane 化。

