# P10 单物理 Product Core 确定性 Lane 实施规范

> 状态：P10-A 至 P10-F 代码、协议和快照迁移已落地；P10-G 三节点容量认证必须在目标环境生成 artifact 后单独签字。
>
> 本文定义 P10 的唯一实施方向。P10 不拆分物理 Product Core，不改变“一条产品线对应一个三节点
> Aeron Cluster”的部署边界。每个 Product Core 只运行一个共享的 `ExchangeCore`；Matcher Lane 仅指该实例内部
> exchange-core 原生 `MatchingEngineRouter` shard。Account Lane 是 P10 默认且必须实施的运行时边界，Treasury 继续由
> Sequencer 串行裁决。本文同时记录当前写格式和剩余迁移/生产认证边界；只有完成文末全部门禁后，才能把 P10 标记为完成。

当前唯一写版本为 command/envelope schema v4、route v2、Core Export marker v9、matcher snapshot v3、
Trading State v24 和 sectioned snapshot v14。生产默认 `matchingEngineCount=4`、`accountLaneCount=4`；旧协议、旧 route
和旧快照均 fail closed。关键落点是 `LaneTopology`、`AccountLaneState[]`、`LaneCommandContextRing`、
`DeterministicExchangeCoreAdapter`、`CoreProbeState`、`SectionedCoreSnapshotWriter/Parser/Recovery` 和
`HttpOpenLoopWorkloadMain`。`AccountLaneWorker[]` 为每个 Lane 创建固定 platform owner thread；账户 Map mutation、
immutable result fanout、ACK、owner-routed query/read fence 和 snapshot capture/restore 使用有界双向 SPSC ring，
Sequencer 不直接遍历 Lane Map。P10-G 使用 `qualification=P10`，会拒绝少于 1,000 用户、200 symbol、100k/s、40 分钟或
没有活动 JFR 的运行；输出的 HDR、events JSONL、accounting JSON、资金对账和三节点故障记录必须一起归档。

## 1. 目标与非目标

### 1.1 目标

- 保持一个 ProductLine 一个三节点 Product Core、一个 Aeron Cluster Log、一个全局 Core sequence 和一条事实链。
- 在一个共享 `ExchangeCore` 内按 symbol 使用原生 matching shard，并允许有界 matcher pipeline；不复制 engine。
- 按 userId 将余额、冻结、订单元数据、持仓、风险和生命周期状态分给固定 owner 线程；同一用户严格串行，
  不同 Account Lane 可以并行。生产默认 `accountLaneCount=4`，只能在 fresh compatible state 启动前配置为其他 2 的幂。
- 手续费、保险基金、亏损缺口、资金费残差、交割/行权和舍入残差默认保留在 Sequencer owner；不为低成本 O(1)
  posting 预先引入 Treasury worker 或跨 Lane 事务。
- 跨 Lane 成交仍具有确定的原子可见性、资金守恒和唯一 Core Fact，不使用业务锁，不创建第二套权威状态。
- 快照同时覆盖 Lane topology、所有 Lane 状态、exchange-core 原生模块、全局序列和事实连续性，并且只允许
  一致的整组快照恢复。
- 为数百个合约、1,000 个以上活跃用户以及后续容量扩展提供线性可解释的并行度。

### 1.2 非目标

- 不把热点 symbol 部署到独立物理 Core，不引入跨 Core 两阶段提交。
- 不为 Matcher Lane 创建多个 `ExchangeCore`、Adapter、Disruptor、RiskEngine、registry 或 snapshot 根。
- 不让 Gateway、Provider、Kafka、PostgreSQL 或 History Projector 参与在线资金裁决。
- 不在 Lane 之间共享可变 Map、订单对象、余额对象或 exchange-core 事件对象。
- 不复制 exchange-core 的可执行订单簿，也不按订单回放重建原生 book。
- 不支持运行中动态修改 native matcher shard 数、Account Lane 数量或 hash seed。P10 第一版只支持重启前离线配置并从 fresh
  compatible state 启动；在线迁移另立版本和专项设计。
- 不为旧快照、旧协议或旧路由保留 legacy reader、fallback 或隐式迁移。

## 2. 不可破坏的权威边界

### 2.1 唯一权威

| 状态 | 唯一权威 | 禁止的第二权威 |
|---|---|---|
| 全局顺序、命令幂等、事实链 | Product Core Sequencer | Kafka offset、数据库主键、Provider 本地序列 |
| 盘口、价格时间优先、撮合结果 | Product Core 内唯一共享 `ExchangeCore` | 多个 ExchangeCore、Core 自建盘口、投影表、行情缓存 |
| 用户资金、冻结、持仓和活动订单 | userId 所属 Account Lane | `TradingCoreState`、数据库、异步回调 |
| 费用、保险基金、残差和亏损缺口 | Sequencer owner 的 Treasury Runtime | Treasury worker、Projector 汇总、账户服务余额 |
| 恢复事实 | Cluster Log/Archive 与完整配对快照 | PostgreSQL、Kafka replay、逐单 rebuild |

`TradingRuntimeState` 继续是运行时业务权威的统一入口，但内部改为 Lane 容器和路由 facade；不得创建另一个
完整 `TradingRuntimeState` 或恢复 `TradingCoreState` 的热路径写入权。`TradingCoreState` 仍只是按确定顺序生成的
不可变 Snapshot State，用于快照、恢复、hash、Core Fact 和对账。

### 2.2 单一事实顺序

Aeron Cluster owner 接收的 command 先得到唯一 `coreSequence`。所有 Lane work、ACK、commit、Core Fact、outbox
和 query visibility 都绑定该 sequence。工作线程完成顺序可以不同，但对外提交顺序不能越过尚未完成的前序
sequence。

一个命令可以产生多个内部 posting，但只能产生一个全局终态结果和一组确定排序的 `FundsDelta`。matcher 前的
reservation 由用户所属 Account Lane 以 `coreSequence` 标记为 pending：它参与同一用户后续命令的权威校验，但在该
sequence 全局提交前不对 query、Core Fact 或 snapshot 可见。matcher 和结算完成后，reservation、订单、成交、持仓和
资金一次提交为一个 terminal Core Fact；matcher 拒绝时由同一 owner 释放 pending reservation。Aeron Cluster Log 已保存
原始命令，snapshot fence 又禁止携带未提交命令，因此不需要额外 reservation fact、通用事务对象或第二份 Runtime。
若未来产品明确要求等待撮合期间立即对外展示冻结余额，必须另行评审双事实模型，不能暗中改变此边界。

## 3. Lane 拓扑与确定性路由

### 3.1 拓扑

```text
Aeron Cluster owner / Sequencer
          |
          +-- Shared DeterministicExchangeCoreAdapter
          |      `-- one ExchangeCore
          |             +-- MatchingEngineRouter[0..N-1]
          |             `-- RiskEngine[0] (MATCHING_ONLY technical state)
          +-- AccountLane[0..M-1]（P10 默认必需）
          `-- Treasury Runtime（Sequencer owner）
```

- **Sequencer**：分配全局 sequence、校验 source/command identity、选择路由、管理 credits、收集 ACK、推进全局
  commit、生成 Core Fact/outbox、执行 snapshot fence 和 readiness gate。
- **共享 Matcher**：一个 `DeterministicExchangeCoreAdapter` 独占一个 `ExchangeCore`。exchange-core 内部的
  `MatchingEngineRouter` 根据 `symbolId & shardMask` 处理各自 symbol；所有 shard 共享一个 API/ring、technical
  registry、native sequence、不可变结果出口和配对 snapshot。一个 symbol 始终由一个 native shard 串行处理。
- **Account Lane**：独占所属用户的余额、冻结、活动订单元数据、reservation、持仓、条件单、风险状态、强平状态、
  client-order/source identity 的用户侧索引。P10 生产默认 `accountLaneCount=4`；`accountLaneCount=1` 只用于
  P10-A 等价性 characterization，不能作为 P10 完成后的生产配置。
- **Treasury Runtime**：fee、insurance、deficit、funding residual、rounding residual、delivery/exercise clearing
  继续由 Sequencer owner O(1) 更新。U 本位全部集中于 USDT，按 asset 创建 worker 不能消除热点，反而增加提交协议。

### 3.2 路由规则

- Matcher route 不建立第二张业务路由表，固定为
  `matcherShardId = symbolId & (matchingEngineCount - 1)`；`matchingEngineCount` 必须是 2 的幂。`stableSymbolId`、
  shard count、shard mask 和映射 hash 进入 snapshot manifest。热点 symbol 如需独占 shard，只能在 instrument 首次
  注册前选择满足低位约束且无冲突的稳定 symbolId；运行中不能迁移。
- Account route 固定为
  `accountLaneId = mix64(userId XOR accountLaneSeed) & (accountLaneCount - 1)`；`accountLaneCount` 必须是 2 的幂且不超过
  64，P10 v1 使用一个 primitive `long` 表示 Lane mask。
- `routeVersion`、matchingEngineCount、shardMask、Account Lane count/seed 和 route hash 写入 Cluster state、Core Fact
  证据和 snapshot manifest。没有 Treasury lane count/seed。
- 外部 API 不接受也不返回 Lane ID；Provider 只提交业务 identity，防止客户端绑定内部拓扑。
- P10 v1 启动后禁止改变上述配置。收到不一致的 instrument route、snapshot route 或 member 配置时 fail closed。

### 3.3 容量与背压

背压分成两个边界，禁止混用：

- **进入 Cluster Log 前**：Provider 根据 READY/credit 与 Aeron offer 结果返回 `CORE_BACKPRESSURE`；此时没有命令事实。
- **进入 Cluster Log 后**：命令不得因为某个 Member 的本地 queue depth、worker 速度、GC 或 wall clock timeout 被业务
  拒绝。三个 Member 的调度不同，本地容量判断不能成为确定性状态输入。

Sequencer 使用有界 in-flight command context 和 matcher dispatch window。context 只保存命令身份、不可变结果引用、
`expectedLaneMask/ackLaneMask` 及 Lane 返回值，不保存第二份订单、资金或 event。window 只控制何时派发已记录命令，
不改变命令业务结果；溢出表示容量配置或节点健康故障，必须 fail closed，不能生成拒绝 Core Fact。Account Lane credits
只由 Sequencer 按逻辑 dispatched/committed sequence 维护，Lane 完成时归还；不得用本地瞬时 queue occupancy 决定业务结果。

## 4. 线程、队列和内存模型

### 4.1 Owner 规则

- 共享 `ExchangeCore` 只由 exchange-core 自己的 Disruptor/MatchingEngineRouter 线程修改；Core 不再创建 matcher owner。
- 每个 Account Lane 只有一个固定 owner thread；只有 owner 能修改其 primitive collections。
- Sequencer 与 Lane 间只传递不可变值对象或预分配槽位的只读句柄。
- Lane 之间不能直接调用彼此的可变对象，也不能直接写另一 Lane 的 ACK、余额或状态。
- Query 不直接读取 Lane Map。Query 由 Sequencer 路由到有界 query queue，只返回已全局 commit 的版本。
- asynchronous callback 只能发布完成通知，不能修改 Runtime State。

### 4.2 队列

Matcher command 由 Sequencer 直接提交共享 `ExchangeApi`；不在 Core 外再建 per-shard command queue。exchange-core
原生 ResultsHandler 每个 native command 只产生一个不可变 `MatcherResult`。`MatchingCompletionQueue` 只把包含
`coreSequence` 的 `CoreMatchingResult` 引用返回 Sequencer，不再分配 `Completion` wrapper，也不复制 event list 或
market data。这个有界引用队列用于隔离 exchange-core callback thread 与 Product Core owner，并保持后续 Account Lane
队列为 Sequencer 单写；它不是第二条事实流。只有验证正常和失败完成都来自同一 producer thread 后才能收敛为 SPSC，
否则保留有界 MPSC。in-flight command context 持有结果强引用，直到 `ackLaneMask == expectedLaneMask` 并完成全局提交后
才清零，保证任一 Lane 消费期间对象都不会被归还、覆盖或复用。

| 方向 | 队列 | 单写者 | 单读者 |
|---|---|---|---|
| Sequencer -> Account Lane | result/command/query reference queue | Sequencer | Account owner |
| Account Lane -> Sequencer | ACK/query result queue | Account owner | Sequencer |
| exchange-core ResultsHandler -> Sequencer | `CoreMatchingResult` reference queue | 需验证为单 producer | Sequencer |

Account Lane 队列必须保持 Sequencer 单写、Lane owner 单读。禁止
`synchronized`、`Lock`、`ConcurrentHashMap` 业务状态、`parallelStream`、Future join、基于 wall clock 的批次提交。

### 4.3 分配约束

- command、matcher result reference、ACK 和有界 in-flight context 使用固定 ring/array slot；终态后清零引用并归还。
- exchange-core 开启 `EVENTS_POOLING`；`MatcherResult`、event list 与 market data 在发布后不可变，Core 直接消费，
  不再进行第二次 event/market-data 复制。
- 同一个不可变 `CoreMatchingResult` 引用可以扇出到多个 Account Lane；不创建 per-Lane event list、fill DTO 或
  `SettlementPlan`。Lane 只处理 userId 路由归自己所有的 active/maker side。
- 默认 4 个 Lane 各自扫描同一只读 event list，接受最多 4 倍的简单线性读取以换取零复制和更小状态机；只有 JFR 证明
  该扫描成为瓶颈后，才允许用预分配 primitive event-index bucket 优化，仍不得复制 event 或改变事实顺序。
- `FundsDelta` 和 Snapshot State 只在事实或快照边界物化；Lane ACK 使用固定 primitive 字段返回局部资金和 Treasury
  增量。
- 风险扫描不得执行 `Set.copyOf`、`toArray` 或全局排序；使用 Lane 内稳定 primitive cursor。
- 所有容量都必须配置上限并导出 high-water mark、拒绝数和占用率。

### 4.4 复杂度预算

Lane 化只允许增加三类必要概念：静态 `LaneTopology`、`AccountLaneState[]` 和只保存引用/位图/ACK 聚合的有界
in-flight command context。不得增加 `SettlementPlan` 对象图、第二个 ExchangeCore、Treasury worker、actor framework、
通用事务协调器、动态 rebalance、分布式锁、MVCC 版本链、补偿 Saga、第二套 Runtime 或 Lane 专用业务 kernel。

现有 facade、processor、六个 settlement kernel、Core Fact 和 snapshot codec 应原位演进；公共行为通过 primitive
owner-filtered applier 复用。每个阶段如果需要多级 callback、递归调度、跨 Lane 直接调用、结果复制或回滚链才能正确，
说明阶段边界设计错误，应停止实施并收窄 in-flight，而不是继续叠加抽象。

## 5. 命令执行模型

### 5.1 单 Lane 命令

1. Sequencer 验证 envelope、source identity 和 routeVersion；进入 Log 后不再执行本地容量业务拒绝。
2. 分配 `coreSequence` 与有界 command context，把不可变命令引用发送到目标 Lane。
3. Lane owner 校验本地 revision，原地应用所属用户变化并返回 revision、hash、资金和 Treasury 增量 ACK。
4. Sequencer 收齐 `expectedLaneMask` 后一次应用聚合 Treasury 增量。
5. Sequencer 按全局 sequence 提交可见性，生成结果、FundsDelta、Core Fact 和 outbox。

### 5.2 下单与撤单

普通下单不增加独立 Core preflight 往返。正式 `PLACE_ORDER` 的权威流程为：

1. Sequencer 固定 `coreSequence`，解析 native matcher shard 和 active user route，创建有界 command context。
2. Account owner 在一次转换中验证余额、平仓容量、风险参数、identity 和订单状态，将 reservation 写成该用户的 pending
   状态；它阻止同用户冲突命令，但在全局提交前不进入 query、snapshot 或 Core Fact。
3. Sequencer 依 Core sequence 顺序把命令提交给唯一共享 `ExchangeApi`；exchange-core 原生 shard 根据 symbolId 路由。
4. 共享 ExchangeCore 只执行一次 command，返回 fork 直接产生的不可变 `MatcherResult`、native sequence 和全局
   matcher prefix before/after。
5. Sequencer 只扫描一次 result：active/taker user 来自 command，maker user 来自 native event 的
   `matchedOrderUid`；据此计算 `expectedLaneMask`，把同一个不可变 `CoreMatchingResult` 引用扇出到所有受影响 Lane。
   一个结果可能同时涉及 taker Lane、多个 maker Lane 和 Sequencer-owned Treasury，不能按单一 userId 只投递一个 Lane。
6. 每个 Lane 再按 owner route 过滤 result，只处理本 Lane 用户，并返回一个包含局部 revision/hash、资金增量和 Treasury
   增量的 ACK；不创建 per-Lane event 副本。同一用户的 taker/maker side 仍由同一个 Lane 串行处理。
7. `ackLaneMask == expectedLaneMask` 后，Sequencer 校验逐资产守恒并一次应用聚合 Treasury 增量，再按 sequence 发布
   reservation/order/fill/position/funds 的 commit visibility、状态 hash 和 terminal Core Fact。

`MatcherResult` 的唯一性边界是 native matcher command。批量撤单、cancel-then-place 等一个高层 Core command 展开成
多个 native command 时，每个 native command 各有一个唯一 result；现有 pending-command context 按
`coreSequence + native part order` 聚合完整后，才执行上述 Lane 扇出并生成一个 terminal Core Fact。

撤单沿用相同边界：先由 matcher 产生权威结果，再释放 Account Lane reservation。禁止外层先根据缓存订单状态推断撤单
成功。

### 5.3 Matcher 并行度

删除 Adapter 当前按完成串行的全局 `matcherTail`，但不创建多个 Adapter。Sequencer 单线程按 Core sequence 将最多
`matcherWindowSize` 个命令送入同一个 exchange-core ring；同一 native shard 在自己的 owner 线程按 ring sequence 串行，
不同 shard 由 exchange-core 并行处理。ResultsHandler 仍按 native ring sequence 完成结果，Adapter 验证 native sequence
严格递增并推进一个全局 matcher prefix。

全局 commit cursor 仍只按 Core sequence 前进，所以慢 symbol 会造成有界 head-of-line blocking。这是保留单 Core Fact
链的明确代价，不能通过乱序事实或第二个 ExchangeCore 绕过。watchdog 只能触发节点 fail closed，不能把本地超时转换成
不同 Member 可能不一致的业务拒绝。

## 6. 不可变结果扇出与跨 Lane 原子可见性

### 6.1 唯一结果与 Lane-local apply

- exchange-core 对每个 native command 发布一个不可变 `MatcherResult`；Product Core 不重建 event、market data 或 fill。
- Sequencer 的一次线性扫描只计算受影响用户的 `expectedLaneMask`，不计算或物化全局 `SettlementPlan`。
- 同一 `CoreMatchingResult` 引用发送给 mask 中的每个 Lane。Lane 使用 command context、native event 和现有产品线
  `SettlementKernel`，只计算并应用 owner route 归本 Lane 的用户侧变化。
- 六个 `SettlementKernel` 继续是唯一产品差异边界；它们增加 owner filter/局部 accumulator 输入，不复制成 Lane 专用
  kernel。每个成交 side 只由其 userId 的 owner Lane 处理一次。
- Lane ACK 通过固定 primitive 字段返回局部 revision/hash、逐资产资金增量和应计入 Treasury 的 fee、insurance、
  deficit、funding/rounding residual、delivery/exercise clearing 增量；Sequencer 只做聚合、守恒校验和一次 Treasury apply。

### 6.2 Apply、ACK 与全局 Commit

跨 Lane 原子性通过确定性可见性屏障实现，不通过锁或数据库事务实现：

1. 所有会导致正常业务拒绝的 active-user 校验必须在 matcher 前由其 Account Lane 完成；matcher 后的状态不一致不是
   可继续业务拒绝，而是确定性致命错误。
2. Sequencer 计算 `expectedLaneMask` 并扇出同一不可变结果引用；每个 Account Lane 按收到的 coreSequence 顺序原地应用
   本 Lane 用户变化，更新 `appliedSequence`，然后返回一次 ACK。
3. Lane 已应用但全局尚未提交的 sequence 不对外可见。query/read fence 由 Sequencer 按序插入 Lane 队列，只能在目标
   sequence 已全局提交、且该 query 之前没有更晚 Lane work 时执行，因此无需 staged Map、MVCC 或旧版本副本。
4. Sequencer 以 `ackLaneMask |= 1L << laneId` 收集 ACK；重复 ACK、未知 Lane 或 mask 越界立即 fail closed。
5. `ackLaneMask == expectedLaneMask` 后，Sequencer 校验各 Lane 资金增量与聚合 Treasury 增量，原地应用唯一 Treasury
   Runtime，推进 `committedCoreSequence`，再发布结果、状态 hash 和 Core Fact。无需第二轮 commit ACK。
6. revision 不匹配、缺失 ACK、资金不平或 commit gap 都是确定性致命错误；member fail closed，从上一完整
   snapshot + log 恢复，不做回滚、补偿或部分继续。

Account Lane 可以按 Lane-local coreSequence 顺序连续处理有界 work；同一用户天然串行，不相交 Lane 可以并行。
Sequencer 的 bounded window 限制未提交 context 数，并在 query、snapshot 和 lifecycle fence 前停止新派发并排空。
由于未提交状态绝不进入 snapshot，节点在部分 Lane 已应用时崩溃只需从上一完整 snapshot + Cluster Log 重演，不需要
undo、版本链、prepare state 或通用事务管理器。

### 6.3 资金守恒

每个 command context 在提交前都验证：

```text
用户可用 + 用户冻结 + 手续费 + 保险基金 + 亏损缺口
+ 资金费残差 + 舍入残差 + 交割/行权清算子账本
= 同资产命令前总额 + 明确外部充值/提现/调整
```

不得跨资产相加。所有 posting 使用整数最小单位和现有操作级舍入规则；禁止 Lane 层重新计算价格、PnL 或 fee。

## 7. 六产品线和生命周期

### 7.1 现货

- 买单 quote、卖单 base reservation 归用户 Account Lane。
- maker/taker 成交可以涉及两个 Account Lane；base/quote Treasury posting 由 Sequencer 同步应用。
- 部分成交按 matcher event 顺序消耗冻结；撤单释放剩余冻结。
- 触发单激活后使用同一正式下单路径，不建立独立订单权威。

### 7.2 U 本位永续与交割

- 仓位、开仓保证金、reduce-only close capacity 和未实现/已实现 PnL 归 Account Lane。
- 手续费、资金费残差、强平费和保险基金归 Sequencer-owned settlement asset Treasury Runtime。
- 资金费扫描按 `(accountLaneId, userId, positionId)` 稳定 cursor 推进并记录进度。
- 交割先对 symbol 建立 lifecycle fence，排空共享 ExchangeCore 中该 symbol 的在途命令，再向相关 Account Lane 扇出
  不可变生命周期命令引用。

### 7.3 币本位永续与交割

- 币本位 PnL、保证金和费用只能由对应 inverse kernel 以整数/定点规则计算。
- 反向除法残差明确进入 Treasury rounding/funding residual，不允许由不同 Lane 各自舍入后丢失。
- 到期结算同样使用 symbol fence、matcher drain 和 ACK 位图全局 commit barrier。

### 7.4 期权

- 权利金由买卖双方 Account Lane 在同一 command context 中结算，Treasury 由 Sequencer 汇总后一次应用。
- 行权/到期失效先 fence symbol，冻结新订单和触发激活，排空 matcher，再按稳定 position cursor 计算。
- 买方权益、卖方保证金、exercise settlement 和 residual 必须在同一 sequence 的跨 Lane barrier 后可见。

### 7.5 标记价、风险、强平、ADL 与触发单

- mark update 先由 Sequencer 提交版本；维护 `symbol -> affected accountLane bitset`，只 fanout 到受影响 Lane。
- 每个 Account Lane 按本地稳定 `(symbolId, userId, positionId)` cursor 扫描，不复制集合、不全局排序。
- 强平决策在用户 Lane 内形成；强平订单进入共享 ExchangeCore 的对应 native shard；强平费、保险基金和 deficit
  由 Sequencer 更新 Treasury。
- ADL 候选排名在 Account Lane 内维护增量有序索引；Sequencer 用按 Lane ID 固定顺序的 deterministic k-way merge。
- 触发单状态归 Account Lane；触发后生成稳定 child identity，经正式 order command 流程进入 matcher。

## 8. 查询与可观测性

### 8.1 查询一致性

- online query 只读取 `committedCoreSequence` 及之前状态。
- 单用户查询路由到一个 Account Lane；book query 通过共享 Adapter 查询对应 native shard；Treasury query 由 Sequencer 回答。
- 跨 Lane 对账查询由 Sequencer 建立 read fence，收集相同 committed sequence 的局部结果后合并。
- 结果超过固定实体/字节上限时返回 `QUERY_RESPONSE_TOO_LARGE`，不能长时间占用 owner。

### 8.2 必须导出的指标

- matcher global dispatch window、completion queue depth/capacity/high-water mark，以及每 Account Lane queue depth；
- 每 Lane command/settlement/query/risk scan 延迟及最老 pending sequence；
- shared ExchangeCore in-flight、native shard 数、每 shard CPU/book/order size、全局 native sequence/prefix continuity；
- Account applied/uncommitted sequence、expected/ack Lane mask、commit gap、revision mismatch；
- global dispatched/committed/exported sequence 与 lag；
- outbox depth/oldest age、Kafka publish lag、Projector lag、WebSocket fanout lag；
- heap、allocation rate、G1/ZGC pause、direct memory、Aeron buffers、thread CPU、context switch；
- 每资产 funds hash、state hash、Lane local hash、snapshot capture/restore 时长和失败原因。

标签只允许 productLine、laneType、laneId 和有界 result code；禁止 userId、orderId、symbol 全量高基数标签。

## 9. 快照一致性协议

### 9.1 Snapshot fence

Snapshot 只能在完整全局提交边界发布：

1. 记录 `snapshotFenceSequence`，停止接收会改变状态的新 command；只允许有界状态查询。
2. 停止向共享 ExchangeCore 派发新命令，排空 matcher dispatch window、completion queue 和 completion gap。
3. 排空已派发 Account work；等待所有 context 的 `ackLaneMask == expectedLaneMask`、Lane 无 applied/uncommitted gap 且
   `committedCoreSequence=snapshotFenceSequence`。
4. 验证 deferred command、lifecycle partial batch、risk batch、pending matcher transaction 和 lane queue 全部为空。
5. 冻结 topology、instrument registry、identity ledger、outbox watermark、terminal retention 和 risk scan cursor。
6. 对唯一共享 ExchangeCore 调用一次 native snapshot。该 snapshot 必须同时产出按 `instanceId` 排序的全部
   `MATCHING_ENGINE_ROUTER/0..N-1` 与 `RISK_ENGINE/0` module，并记录全局 native sequence/prefix、每 shard
   engine/book/order hash、shared registry 和 config hash。
7. 按 accountLaneId 升序，由各 owner 编码自己的不可变 section；Sequencer 编码 Treasury section。snapshot 线程不直接
   遍历 Account owner Map。
8. Sequencer 编码全局 section、manifest、每 section CRC 和整体 digest。
9. 再次读取所有 Lane revision/sequence/hash；不一致则丢弃整组 snapshot，不能发布部分结果。
10. 三节点使用相同格式和确定排序完成 snapshot；成功后解除 fence。

任何 Lane capture 超时、原生 matcher snapshot 失败、队列非空、prefix 断裂、资金不平、CRC/digest 失败都使整次
snapshot 失败。旧快照继续有效；不得发布“其余 Lane 成功”的 snapshot。

### 9.2 Snapshot 结构

当前唯一写格式已提升为 `Trading snapshot v24` / `sectioned snapshot v14` / matcher snapshot v3，并删除旧版本读取路径。
新格式包含：

1. Header：magic、schema、productLine、`coreShardId=default`、snapshotId、fence/committed sequence。
2. Topology：routeVersion、matchingEngineCount/shardMask、symbolId-to-shard hash、Account Lane count/seed、
   matcher window 和 queue capacities、配置 hash。
3. Sequencer：command/source identity ledger、global prefix、in-flight context 必须为空、risk/lifecycle global cursor。
4. Instrument/identity registries：symbol、asset、user technical mapping 与版本 hash。
5. Account Lane sections：users、balances、orders、reservations、positions、triggers、risk、liquidations、ADL、indexes、
   revision、local state/funds hash。
6. Treasury section：Sequencer-owned fee、insurance、deficit、funding/rounding residual、clearing、revision 和 funds hash。
7. Shared matcher section：一个 Adapter/ExchangeCore manifest，加原生 `MATCHING_ENGINE_ROUTER/0..N-1`、
   `RISK_ENGINE/0` 载荷，全局 native matcher sequence/prefix、每 shard engine/book/order hash、active-order hash、
   fork artifact/config hash。module 数必须是 `matchingEngineCount + 1`，不能再固定为两个或要求全部 instanceId 为 0。
8. Outbox/terminal retention：未确认事实、export watermark、terminal identity、结果缓存和有界淘汰水位。
9. Footer：section directory、长度、逐 section CRC、全局 state/funds hash、整体 digest。

全局 hash 只按固定 `(laneType, laneId)` 顺序组合各 Lane hash，不能按线程完成顺序组合。Hash 输入必须覆盖 topology。

### 9.3 Snapshot 发布前交叉核对

- 每个 OPEN 业务订单在唯一共享 ExchangeCore 中、由 `symbolId & shardMask` 指定的 native shard 恰好存在一次。
- 每个 matcher 活动订单都有 Account Lane 订单元数据和有效 reservation；terminal 订单不在 matcher。
- 每个 user 只存在于 hash 路由指定的 Account Lane；Treasury asset 在 Sequencer Runtime 中只存在一次。
- position、trigger、risk/ADL index 与主状态双向一致。
- 每资产 Account + Treasury 资金守恒，Lane local hash 组合值等于 global funds hash。
- 所有 matcher prefix、Core fact prefix、outbox sequence 连续，snapshot 不包含未提交 context 或 pending reservation。

## 10. 恢复与 READY 门禁

恢复严格按以下顺序执行，任何一步失败都 fail closed：

1. 读取 header、section directory、长度、CRC 和整体 digest；拒绝未知主版本和截断数据。
2. 校验 productLine、`coreShardId=default`、routeVersion、matchingEngineCount/shardMask、Account Lane count/seed、
   symbolId-to-shard hash、fork/JAR/config hash。
3. 构造一个共享 Adapter/ExchangeCore、固定 Account topology、预分配有界 contexts 和 queues；不接收流量。
4. 恢复 instrument、asset、technical user 和 identity registry，并校验无重复 ID 和 route hash。
5. 按 accountLaneId 恢复 Account Lane，逐用户重算 owner route，重建局部索引并验证 local state/funds hash。
6. 在 Sequencer owner 恢复唯一 Treasury Runtime，验证子账本和 funds hash。
7. 向唯一共享 ExchangeCore 一次导入全部原生 module；要求 `MATCHING_ENGINE_ROUTER/0..N-1` 与 `RISK_ENGINE/0`
   齐全，禁止为每 shard 创建 Adapter，也禁止按业务订单 rebuild book。
8. 验证全局 native sequence/prefix、每 shard engine/book/active-order hash、shared registry 和 fork config。
9. 执行订单/reservation/book、position/risk index、Account/Treasury 资金及 global hash 的全量交叉核对。
10. 恢复 command/source identity、outbox、terminal retention、risk/lifecycle cursor 和 exported watermark。
11. 从 snapshot position 继续按 Cluster Log 顺序 replay；路由和 commit barrier 与在线路径完全相同。
12. replay 结束后验证所有队列和 in-flight context 为空、无 applied/uncommitted 或 sequence/ACK gap、
    matcher/Core/outbox prefix 连续。
13. 三个 Member 完成相同门禁，leader 才发布 READY；Provider 在 READY 前不能降级到 clean start。

### 10.1 崩溃边界

| 崩溃位置 | 恢复结果 |
|---|---|
| reservation 之前 | command 未产生状态；按 identity 正常 replay |
| pending reservation 之后、matcher 之前 | snapshot 不包含 pending state；从 Log 命令重新确定性执行 |
| matcher 提交后结果未知 | 依赖 Cluster Log 顺序和 native matcher snapshot/prefix 判定；不得盲目 resubmit |
| 部分 Lane 已 apply、全局 commit 前 | 未发布 snapshot；fail closed，从上一完整 snapshot + log 重演 |
| 全局 commit 后、terminal fact 前 | replay 按 sequence 完成相同 commit/fact，不做补偿事务 |
| Core Fact 已入 outbox、Kafka ACK 前 | replicated outbox 用 fact identity 幂等重发 |
| snapshot 任一 Lane capture 失败 | 整组 snapshot 丢弃，上一快照保持可恢复 |

P10 实现前必须为“matcher 提交后结果未知”定义并验证精确的 log/native-sequence/prefix 状态机；不能无歧义判断时
不得扩大共享 ExchangeCore 的 dispatch window。

## 11. 当前项目的具体修改清单

以下是实施 P10 时必须修改的边界，不代表当前已修改。

### 11.1 `surprising-aeron-protocol`

- `CoreRoute` / command header：加入或启用 `routeVersion`，不暴露调用方提供的 laneId。
- `UpsertInstrumentCommand`：不增加调用方可选 matcherLaneId；Core 从稳定 symbolId 和 shardMask 派生 shard。
- `CoreMatcherTransition`：保留共享 ExchangeCore 的全局 native sequence/prefix，可附带派生 matcherShardId 作为证据，
  但 shardId 不形成第二条事实序列。
- `CoreExportEvent` 与 codec：加入 topology hash、Lane revision/hash 和 committed sequence；升级 marker 并 fail-old。
- query/snapshot view：明确 committed sequence 和 routeVersion，不能返回 applied-but-uncommitted Lane 数据。

### 11.2 `surprising-aeron-service/CoreProbeState`

- 保留一个 Adapter；把“一组全局 pending matching + 全局完成门”改为有界 in-flight context ring、共享 matcher
  dispatch window、按 coreSequence 的 result-reference buffer 和 commit cursor。context 仅保存引用、
  `expectedLaneMask/ackLaneMask` 及 ACK 聚合字段。
- 不创建 `MatcherLane[]` 或 `TreasuryLane[]`；P10-C 必须创建固定 `AccountLane[]`。
- ingress、credit、route、ACK、commit cursor、fact/outbox 和 snapshot fence 只由 Cluster owner 修改。
- callback 只把 `CoreMatchingResult` 引用写入有界 completion queue；不能修改 Runtime，也不分配 `Completion` wrapper。
  snapshot 实现第 9 节共享 ExchangeCore barrier。

### 11.3 `TradingCoreRuntime` 与 `TradingRuntimeState`

- `TradingCoreRuntime` 继续是统一 facade，新增 matcher topology、account route、committed sequence 和 read fence。
- `TradingRuntimeState` 不删除、不复制；P10-C 在内部拆成固定 `AccountLaneState[]` 与只读 topology；Treasury 始终由
  Sequencer owner 持有。
- users、balances、orders、reservations、positions、triggers、liquidations、risk/ADL 和用户索引移动到 Account Lane。
- fee/insurance/deficit/funding residual/rounding/clearing 不移动，不创建 Treasury worker。
- 风险扫描集合复制/`toArray` 改成 Lane 内稳定 primitive cursor，并让 cursor 进入 snapshot。
- `RuntimeCommandProcessor` 改为 Sequencer route/mask 计算与 owner-filtered lane-local apply；不能物化全局
  `SettlementPlan`，也不能直接跨 Lane 修改 Map。

### 11.4 Matcher 边界

- `DeterministicExchangeCoreAdapter` 在每个 Product Core 中只能有一个生产实例，内部只能启动一个 ExchangeCore。
- 使用 exchange-core 原生 `matchingEnginesNum` 与 `symbolId & shardMask`；删除完成串行的全局 `matcherTail`，改为
  Sequencer 单写的有界 dispatch window，不能创建多个 Adapter。
- `CoreMatchingResult` 直接携带不可变 fork `MatcherResult` 和派生 shard identity；结算直接遍历 native events，删除
  `CoreMatch` 中间列表及第二次 fill 分配。
- `MatchingCompletionQueue` 保持一个有界引用出口，队列元素直接为 `CoreMatchingResult`，删除 `Completion` record；只有
  证明单 producer 后才改 SPSC。
- `MatcherSnapshot` 升级为一个共享 manifest，接受 N 个 `MATCHING_ENGINE_ROUTER` module 和一个 `RISK_ENGINE/0`；
  仍要求 native snapshot-only recovery。
- `EVENTS_POOLING` 必须开启，以不可变发布契约、对象生命周期测试和 JFR 分配证据证明不会复用已发布事件。

### 11.5 结算、风险与索引

- 六个 `SettlementKernel` 复用同一 owner-filtered apply 入口，直接消费 command context 与不可变 native events；每个
  kernel 只修改调用它的 owner Lane，不输出全局 `SettlementPlan`。
- P10-C/D 新增固定字段 Lane ACK accumulator 与有界 command context；不引入 per-Lane event copy、通用 Saga、事务框架
  或 posting 对象图。
- 活动订单索引支持 `user -> orders` 与 `symbol -> affected accountLane bitset`，由 owner 增量维护。
- funding、delivery、exercise、liquidation、ADL、trigger cursor 全部带 laneId 并可快照恢复。
- 跨 Account Lane 业务测试逐资产核对 Account posting、Sequencer Treasury posting 和 `FundsDelta` 完全相等。

### 11.6 Snapshot codec 与恢复

- `TradingStateSnapshotCodec`、`SectionedCoreSnapshotCodec/Writer/Parser/Recovery` 同步提升主版本。
- section directory 支持 Account Lane section，并在 shared matcher section 内保存所有 native module 的 type/instanceId、
  长度和 CRC；拒绝缺失、重复和越界 shard。
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

不得一次性重写整个 Core。P10-A 至 P10-G 全部是固定必做阶段；每阶段保持唯一权威和可恢复性，所有阶段代码完成后
统一进入测试波次。`accountLaneCount=1` 只用于 P10-A characterization，P10-C 起生产默认使用 4 个 Account Lane。

### P10-A：共享 ExchangeCore topology 与等价基线

- 明确每 Product Core 只有一个 Adapter/ExchangeCore；建立 matchingEngineCount/shardMask、全局 native
  sequence/prefix、dispatch window 和指标。
- `matchingEngineCount=1`、`accountLaneCount=1` 时与当前串行 Core 等价，Treasury 保持 Sequencer owner。
- 升级协议/快照版本并 fail-old；证明三节点恢复语义相同。

### P10-B：共享 ExchangeCore 原生 matcher shard

- 仍使用一个 Adapter/ExchangeCore，把 `matchingEngineCount` 提升到 2/4，并删除 Adapter 完成串行的全局
  `matcherTail`；Sequencer 按 Core sequence 向一个 ring 进行有界 pipeline。
- 快照接收全部 native matching module；验证多 symbol 并行、global commit 顺序、native sequence/prefix 和恢复。
- Account 保持单 owner，Treasury 保持 Sequencer owner。禁止 symbol 动态迁移。

### P10-C：Account Lane 预占与单 Lane 命令

- 本阶段是固定必做项，不以账户 CPU 是否已成为瓶颈为前置条件。
- 生产默认创建 4 个 Account Lane，按 userId 分离 owner state、identity、reservation 和 query。
- reservation 使用 user-owned pending state 并绑定 coreSequence，不产生单独 Core Fact；覆盖充值/调整/下单/撤单。
- 热点 maker 必须通过多个业务独立的 maker 子账户扩展；同一用户不能跨 Lane 并发。

### P10-D：不可变结果扇出与 ACK barrier

- Sequencer 一次扫描 immutable result 得到 `expectedLaneMask`，同一引用扇出；Lane owner-filtered apply 后返回一次 ACK。
- 实现有界 context、`ackLaneMask`、Treasury 聚合和单一 `committedCoreSequence` 可见性；不实现 SettlementPlan、
  prepare Map 或第二轮 commit ACK。
- 覆盖 maker/taker 跨 Account Lane、Sequencer Treasury posting 和相同用户自成交策略。
- mismatch fail closed，不实现回滚框架。

### P10-E：风险与生命周期

- 分片 mark fanout、risk cursor、trigger、liquidation、ADL、funding、delivery 和 option exercise。
- 删除剩余全局集合复制和全量排序热点。

### P10-F：完整快照与恢复

- 完成 Account Lane section、共享 ExchangeCore 全部 native modules、global manifest、交叉核对和 READY 门禁。
- 验证 leader kill/rejoin、follower lag、cold recovery、Archive replay 和 snapshot capture failure。

### P10-G：容量认证

- 根据真实 CPU、队列、GC、direct memory 和尾延迟调整 native matchingEngineCount、matcher window 和 Account Lane 数。
- 只有 1,000 用户/数百 symbol/高频做市/40 分钟真实 API 运行和更高 burst 门禁通过后，才标记 P10 完成。

## 13. 测试与验收矩阵

### 13.1 确定性和并发

- 同一输入日志在不同 worker 延迟、完成顺序和三节点上产生相同 state/funds/prefix hash。
- 同 symbol 价格时间优先不变；共享 ExchangeCore 的不同 native matcher shard 确实并行。
- 同 user 所有命令严格顺序；不同 Account Lane 无共享写、无业务锁。
- Cluster Log 前的入口背压不产生命令事实；进入 Log 后，本地 queue depth/timeout 不改变业务结果。取消、超时和重试
  不产生双冻结、双成交或双事实。

### 13.2 业务与资金

- 六产品线分别覆盖下单、部分/全部成交、撤单、主动平仓、止盈止损、手续费和 PnL。
- 永续覆盖 mark/funding/强平/保险基金/ADL；交割覆盖到期结算；期权覆盖权利金/行权/失效。
- maker、taker 位于不同 Account Lane 且 Treasury 由 Sequencer 应用时逐资产守恒；用户和做市账号核对期初、外部调整、成交、费用、
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

只有以下条件全部满足，P10 才能从“代码迁移完成、待生产认证”改为“完成”：

- P10-A 至 P10-G 的生产代码和协议/快照版本全部落地；Account Lane 默认启用且生产默认数为 4，不存在兼容 fallback。
- 一个 ProductLine 仍只有一个物理三节点 Product Core、一个 global Core sequence 和一条 Core Fact 链。
- 一个共享 ExchangeCore、native matcher shard、bounded dispatch window、不可变结果引用扇出、默认 Account ownership、
  expected/ack mask commit barrier、Sequencer Treasury 和 query visibility 均有自动化和真实表面证据。
- 六产品线资金、持仓、平仓/爆仓/触发/资金费/交割/行权全部正确且逐资产守恒。
- snapshot、恢复、Archive replay 和三节点故障矩阵全部 fail-safe，并通过 hash/prefix 连续性核对。
- 真实 API 生产模拟达到容量门禁，GC、堆外内存、延迟和吞吐有可复核报告。
- 根 README、Aeron Core README、协议和运维说明同步为实际版本；在此之前不得声称系统已 Lane 化。
