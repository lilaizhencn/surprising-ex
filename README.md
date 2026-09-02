# surprising-ex


Surprising-EX 是基于 Java 25、Aeron Cluster、PostgreSQL、Kafka 和 Valkey 的交易所后端。
仓库覆盖现货、永续、交割和欧式现金结算期权四类业务线（六个 `ProductLine` 变体）；每个变体使用独立的
三节点 Aeron Cluster、Topic、投影和账户类型。

本 README 与各模块 README 记录当前架构和验收边界；真实 provider、做市进程和基础设施由部署编排单独管理。

## P0-P10 规范阶段注册（当前权威）

下列阶段名称是本分支唯一的 P0-P10 注册表。历史草案只能按这里的名称解释，不能重新定义阶段边界。

| 阶段 | 当前唯一名称与默认边界 |
|---|---|
| P0 | canonical 中文 P0-P5 规范注册与分支安全：先发布根 README 契约，保留既有脏改动。 |
| P1 | reservation、`PositionCloseCapacity`、价格分离与累计手续费：衍生品普通单为全量开仓风险预留，reduce-only 才可省略开仓保证金。 |
| P2 | deterministic `MATCHING_ONLY` 推进与成对快照：Aeron owner 通过有界 SPSC pipeline 调用单个同步 matcher worker；native sequence/prefix 与 Core 状态成对快照，Core commit 与 Core Fact 按全局 Core sequence 确定提交。 |
| P3 | TradingRuntimeState 生产写入权威：六条产品线的 Runtime owner 线程是唯一交易裁决状态，immutable `TradingCoreState` 仅是快照、事实增量、恢复、hash 与对账投影。 |
| P4 | six isolated settlement kernels：Spot、LinearPerpetual、InversePerpetual、LinearDelivery、InverseDelivery、Option 各有穷尽且隔离的结算内核。 |
| P5 | FundsDelta、Treasury、操作级舍入与连续性：每命令/资产资金变动不可变且确定性排序，Treasury 子账本、残差和 Core Fact 前后状态证据显式核算。 |
| P6 | randomized properties / model campaign：随机化属性与模型验证，不是 P0-P5 行为改动。 |
| P7 | fatal / readiness campaign：致命故障和就绪性验证，不是 P0-P5 行为改动。 |
| P8 | three-node recovery certification：三节点恢复认证，不是 P0-P5 行为改动。 |
| P9 | 1,000-user / 40-minute certification：千用户、四十分钟验证，不是 P0-P5 行为改动。 |
| P10 | single-Core deterministic lanes / capacity：每个 Product Core 只运行一个同步 matching engine；Account Lane 是有固定线程所有权的确定性资金隔离与执行边界，不创建物理 Core shard。实施边界以本 README 和 Aeron 模块 README 为准。 |

当前实现状态：P0-P5 与 P10-A 至 P10-F 的主体迁移已完成。撮合输出构造成一条不可变
`MatcherSettlementEvent`，按确定性的 Lane mask 直接写入各 Account Lane 的有界 SPSC ring；每个 Lane 线程永久拥有并
串行修改本 Lane 的账户、余额、订单、冻结和持仓。Coordinator 只读取 completion bitmap、按 Core sequence 发布
Core Fact 并推进 committed watermark，不做逐 Lane `release/bind/await`。Lane 允许连续消费多个 applied sequence；
查询和 snapshot 只越过 committed watermark。观察到撮合事实后的任何 Lane、资金、hash、index 或发布不变量错误均
fail-stop，实例必须从 snapshot 和 Cluster Log 恢复，热路径不再尝试分布式 rollback。
P10-G 使用真实 HTTP 开放环门禁，只有保存 1,000 用户、至少 200 symbol、100k/s offered rate、
计量窗口内至少 100k/s 实际终态吞吐、40 分钟、JFR 和资金/盘口核对 artifact 后才可标记生产认证完成。普通下单只提交一次正式 `PLACE_ORDER`，
由 Product Core 在同一权威转换内完成 P1 的预占、平仓容量和费用校验；显式 dry-run 接口仍可调用只读 preflight，
但不在正式下单前额外往返 Core。`DeterministicExchangeCoreAdapter` 向一个共享 ExchangeCore 的原生 matcher shard
提交有界 pipeline，直接持有
exchange-core 产出的不可变 `MatcherResult`、event list 和 market data，不再复制 matcher 证据，并用
全局唯一 `matcherSequence + matcherShardId + MatcherPrefix(before, after)` 绑定命令结果；每个 native shard 和
control shard 各自推进 prefix digest，随配对快照恢复，单 shard 断裂、倒退或 malformed
结果立即 fail closed。普通命令不再生成逐命令全量 `BookHashes`，完整 `bookStateHash` 只保留在 snapshot、恢复和
显式审计边界。`TradingRuntimeState` 是 P3 唯一交易裁决权威；`TradingCoreState` 仅在每个事实边界按 changed-key
生成一次不可变投影，承担 Cluster snapshot、Core Fact、恢复、状态 hash 和对账。P4 使用六个穷尽且隔离的
`SettlementKernel`。P5 以确定性 `FundsDelta`、Treasury 子账本、状态/资金 hash 和 replicated outbox 形成连续事实链。
P10 使用 `routeVersion=3`、1 个 matching engine、0 个 exchange-core risk engine 和默认 4 个逻辑 Account Lane；业务风控仍由 owner 执行。pending reservation
在 commit 前不进入 query、Snapshot State 或 Core Fact，Core Fact 仍严格按全局 sequence 发布。

### 分支安全与验证契约

- 当前工作树中的脏改动是必需输入；不得新建 fresh worktree，也不得 stash、reset 或 checkout 以清理它们。
- `CoreProbeState`、`SurprisingClusteredService`、运行时/快照/hash codec、export/projector、gateway fanout 和
  `init.sql` 等共享 hot files 同一时刻只能有一名 serial owner 修改；其他任务必须避开这些文件。
- 文档和实现验证均直接记录精确的 Maven 或 Java 命令及输出 artifact，不以 `scripts/` 作为验证入口，亦不以
  `docs/` 链接作为权威规范。验证前先保存既有 dirty patch，提交时只显式选择本任务文件。

## 已确认架构基线

- Runtime 状态、immutable Snapshot 状态和 exchange-core 热路径的当前约束由本文、对应模块 README、源码和 Maven
  测试共同记录；不存在可替代这些约束的独立文档入口。

- 一个 `ProductLine` 变体对应一个逻辑 ProductExecutionCore；逻辑 Core 是一套三 Member Aeron Cluster，
  不是单进程。该 Core 管理本产品线全部 symbol、账户、订单元数据、持仓、风险和生命周期。
- CROSS 与 ISOLATED 都在同一个 Core 内。CROSS 只共享本产品线 Core 内的权益；ISOLATED 绑定 position identity，
  保证金划拨仍由同一 Core 原子完成。产品线之间不共享 live available balance。
- 当前不为热点币对单独部署 Core。协议固定 `coreShardId=default`、`routeVersion=3`；symbol 只路由到同一共享
  ExchangeCore 的原生 `MatchingEngineRouter` shard，user 只路由到静态 Account Lane。matcher/Lane 数和 seed 只能在
  fresh compatible state 启动前配置，运行中不 rebalance，也不产生第二条 Core Fact 链。
- exchange-core 已是唯一可执行盘口和 FIFO/价格树权威；Core 只保留订单元数据、资金、持仓、风险状态和
  必要派生索引。`CoreBookState`、优先级副本、逐单 rebuild、恢复 retry/resubmit 已从生产代码删除。

## 核心边界

- Aeron Cluster 中的 ProductExecutionCore 是资金、订单业务状态、持仓、Risk、强平和 Treasury 的唯一
  在线权威；其内嵌 exchange-core 是唯一可执行盘口权威。Cluster Log、Archive 和配对 Snapshot 是唯一核心恢复链。
- Exchange Core 作为确定性订单簿执行器嵌入每个 Aeron Member，不运行独立 command journal；同一核心命令
  原子完成资金预占、撮合、成交结算、风险更新和必要的强平状态推进。
- exchange-core 固定为 `MATCHING_ONLY` 且禁用其 margin trading；引擎内部 user/symbol/risk module 只是
  matcher 技术状态，不能成为业务余额、持仓、保证金、强平或对账来源。
- PostgreSQL 只保存 Core Export 查询投影、审计、报表和对账数据；投影延迟或不可用不能改变交易裁决。
- 产品线资金划转由 Gateway 使用稳定 `transferId` 同步提交 `TRANSFER_OUT -> TRANSFER_IN -> COMPLETE_TRANSFER`；
  源 Core Runtime 在扣款后只保留有界 pending 记录，目标 Core 幂等入账，失败只做前向重试。Gateway 和
  PostgreSQL 不保存在线 Saga 状态；`TRANSFER_IN` Core Fact 经 History Projector 异步写入
  `account_product_transfers`，该表只用于历史查询。
- Kafka 保留外部输入缓冲与 WebSocket、K 线、通知、数据仓库等外围事件分发，不恢复核心资金状态。
- 公共逐笔统一使用产品线 `match.trades` / `PublicTradeEvent`：Gateway、标记价与 K 线不再消费
  `trade.events`。K 线逐笔链路只生成并落库关闭的 1 分钟数据，高周期由 `candle.events` 上的
  `CLOSED + 1m` 事件异步聚合；已关闭 K 线不可修订，水位线之前的迟到实时数据允许丢弃，历史高周期查询
  从 PostgreSQL 的 1 分钟行计算。
- Valkey 只承担限流和非权威缓存，不保存 Risk 状态、强平候选、资金或订单恢复进度。
- Risk 按 symbol 保存确定性有界扫描游标；强平 Work、触发价格序列、仓位身份、执行、强平费和
  Insurance Treasury 全部由 Aeron 校验并原子提交。
- 保证金率、risk brackets、杠杆和持仓上限只由 Instrument Provider 版本化下发到 `CoreInstrumentState`；
  Core 是唯一计算/执行来源，Risk Provider 只查询并展示 Core 风险快照，不再维护本地保证金阈值副本。
- `surprising-derivatives-lifecycle` 是无状态生命周期协调器：统一承载 Risk、Liquidation、Insurance 和 ADL，查询 Aeron 状态后提交有序工作；四类 API 路径保持独立。
  continuation 合并为一次 `EXECUTE_LIQUIDATION_BATCH`，按 `productLine + canonical payload` 生成稳定 `commandId`。
  Core 共享最多 1,024 笔撤单预算并持久化 cursor；provider 正常周期不逐 action 往返、不单独续跑 Risk Scan，也不维护
  Redis 队列或 PostgreSQL 强平事务。
- Core Exporter 以连续 Export Sequence 向 Kafka at-least-once 发布；只有完整 Kafka 批次成功后才向 Aeron 提交 ACK。
  独立 History Projector 批量消费 Kafka，在一个 PostgreSQL 事务内写业务投影和 offset；任何一条失败都会回滚整批，
  不新增数据库 outbox 或应用 WAL。
- Matching Provider 只做 Market Data Projection：启动从 Aeron 强查询恢复 L2 和 watermark，随后消费
  单分区连续 Core Event 发布公共深度与成交；历史成交和 24h 查询读取 PG 投影。
- 四条业务线必须隔离部署和验证；压测前当前变体必须达到 `functional-gate=PASS`、`funds-diff=0`。

## 永续架构图

```mermaid
flowchart LR
    Client[交易客户端 / 模拟用户 API] --> Gateway[Gateway REST / WebSocket]
    Gateway --> Trading[Trading Provider]
    Gateway --> Account[Account Provider]
    Gateway --> Risk[Derivatives Lifecycle Provider]
    Gateway --> Funding[Funding Provider]

    Trading --> Core[LINEAR_PERPETUAL ProductExecutionCore<br/>三节点 Aeron Cluster]
    Account --> Core
    Risk --> Core
    Funding --> Core
    Core --> Matcher[exchange-core<br/>唯一可执行盘口与 FIFO]
    Core --> Exporter[Core Exporter<br/>序列化 Export ACK]
    Exporter --> Kafka[Kafka<br/>公共事件 / WebSocket / 行情]
    Kafka --> Projection[History Projector<br/>PostgreSQL 异步投影 / 对账]
    Market[Price / Market Data Provider] --> Core
    Maker[Maker Provider<br/>被动 GTX 报价] --> Trading
    Gateway --> Kafka
```

永续的余额、订单预留、成交、持仓、保证金、风险和强平裁决都在同一个
`LINEAR_PERPETUAL` Core 内完成；PostgreSQL、Kafka 和 Valkey 不参与在线资金裁决。

## 永续成交流程图

```mermaid
sequenceDiagram
    participant U as 用户 / 做市账号
    participant G as Gateway
    participant T as Trading Provider
    participant C as Aeron Core
    participant M as exchange-core
    participant E as Exporter
    participant K as Kafka / WebSocket

    U->>G: 下单
    G->>T: 校验产品线、账户与订单参数
    T->>C: PLACE_ORDER(commandId)
    C->>C: 校验 instrument / leverage / margin
    C->>C: 原子冻结结算资产并写入 reservation
    C->>M: 提交撮合请求
    M-->>C: FIFO 撮合结果
    C->>C: 原子结算 maker/taker、手续费、持仓与风险
    C->>C: 释放已终结订单预留
    C-->>T: APPLIED + command result
    T-->>G: 订单与成交响应
    C->>E: 发布 Core Event / Export Sequence
    E->>K: 公共成交、盘口和私有推送
    E->>E: ACK 仅在投影与事件成功后提交

    Note over C,T: 已接受但响应超时不会自动重发
    C-->>T: ResultUnknown(commandId)
    T->>C: COMMAND_RESULT_QUERY(commandId)
    C-->>T: 已提交结果或 RESULT_UNKNOWN_OUTSIDE_RETENTION
```

`ResultUnknown` 表示命令可能已经进入 Core，客户端不能把它当作“未执行”重发；必须用同一
`commandId` 查询结果。这次压测发现的故障并非该语义本身，而是用户余额 delta lineage
校验把同一撮合命令内“成交更新 → 释放预留”的合法多层 delta 误判为状态损坏，已在
`StateMapSupport` 与 `CoreUserState` 修复并以状态测试和三节点永续 capacity 测试验证。

永续 Runtime 迁移当前已完成下单、撤单、成交、资金费、强平、ADL 和风险扫描的原生计算。
风险快照包含 mark price、cross/isolated 结果、分页 scan cursor、liquidation plan 和
`nextLiquidationId`。标记价命令只更新价格并初始化持久化 risk/trigger cursor，不扫描用户、不执行触发单；
后续 `CONTINUE_RISK_SCAN` 才按版本化批次上限推进风险与触发单工作。连续 mark/continuation 由 owner thread 在持久 Runtime 原地增量提交，
active liquidation 使用 primitive 分层索引精确定位；生产路径不再通过 immutable reducer 候选结果
反向覆盖 Runtime。不可变 Core 视图只由 Runtime changed-key 生成，用于事实、hash、快照、恢复和对账，
Runtime/materialization 等价检查仅留在测试源码。

## 产品线

| 产品线 | `ProductLine` | 账户类型 | Topic 前缀 |
|---|---|---|---|
| 现货 | `SPOT` | `SPOT` | `surprising.spot` |
| U 本位永续 | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `surprising.linear-perp` |
| U 本位交割 | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `surprising.linear-delivery` |
| 欧式期权 | `OPTION` | `OPTION` | `surprising.option` |

`INVERSE_PERPETUAL` 和 `INVERSE_DELIVERY` 已有公共枚举和 Topic 映射；Core recovery/capacity 门禁按六个
产品枚举逐条执行，业务 API 的四类生命周期仍按产品边界单独验收。

## 模块

| 模块 | 职责 |
|---|---|
| `surprising-product-api` | 产品线、账户类型和 Topic 命名 |
| `surprising-instrument` | symbol、合约规格、风险档位和生命周期 |
| `surprising-price` | 独立指数价、标记价和汇率服务 |
| `surprising-trading` | Provider/API 边界；最终订单、条件单、算法单和 exchange-core 裁决由 Aeron Core 完成 |
| `surprising-account` | 余额、账本、账户指令、结算、持仓和保证金 |
| `surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api` | Risk、强平、保险、ADL 统一 API contracts |
| `surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider` | Risk、强平、保险、ADL 统一 Provider/JVM |
| `surprising-funding` | 资金费 API 和独立资金费服务 |
| `surprising-market-data/surprising-market-data-api` | K 线查询 contract 与共享 DTO |
| `surprising-market-data/surprising-market-data-provider` | Matching Aeron 行情投影与 Kafka Streams + RocksDB K 线统一 Provider/JVM |
| `surprising-gateway` | REST gateway、WebSocket fanout 和统一对外入口 |
| `surprising-maker` | 内部做市和交易链路压测 |

Repository 默认只操作一张物理表，由 Service 在事务内聚合。在线交易、风控和结算链路若因一致性或原子性
必须跨表，源码需要逐项写明中文“不可拆原因”。后台订单时间线、资金对账和运营报表不得在交易主库新增
多表 JOIN；后续财务运营模块应消费领域事件，并使用独立数据库建立查询投影。
边界约束由对应模块的源码、Maven 测试和本 README 的阶段注册维护；按单产品线、资金对账、恢复和容量职责
执行直接 Maven/Java 验证命令并保存输出 artifact。

Controller 只负责 HTTP 参数校验、请求上下文提取和响应映射，不直接访问 Repository，也不承载事务或
业务编排。`task` 包只负责声明定时触发时机，所有实际执行都委托给 Service。入口层边界由源码审查和
对应 Maven 测试维护。

生命周期 Provider 还提供受保护的单周期运维入口，便于真实门禁和故障恢复演练；请求必须由 Gateway
注入非空 `X-Admin-User-Id`，资金/持仓变更仍只由 Aeron Core 原子执行：

| Provider | 单周期入口 | 说明 |
|---|---|---|
| funding | `POST /api/v1/funding/admin/run-cycle` | 有界资金费 continuation |
| liquidation | `POST /api/v1/admin/liquidations/run-cycle` | 有界强平 work/action 批次 |
| insurance | `POST /api/v1/insurance/admin/run-cycle` | Core 选择的保险覆盖批次 |
| adl | `POST /api/v1/adl/admin/run-cycle` | Core 选择的 ADL 批次 |

定时任务与 HTTP 入口调用同一 Service 方法并串行化；重复请求不会绕过 Core cursor 或在 PostgreSQL
中直接修改在线余额。接口返回已处理数量和失败/未完成数量，便于记录 `ProductLine`、Core sequence
和资金对账证据。

Product Core 的热状态与不可变状态投影通过 `RuntimeCommitPatch` 分界。命令在 Account Lane 内完成原地
裁决后，owner 只封存本命令涉及的用户、余额、订单、冻结、仓位和风险 typed before/after image，生成一次不可变事实增量；
`CoreProbeState` 不再额外保存 LaneCommit、lane revision 或局部 hash。entry 不持有命令前后的完整
`TradingCoreState`；滚动资金/业务 hash、资金守恒、Treasury 合并、投影和 Core Fact 共同消费同一份
primitive `RuntimeFundsDelta`/typed change。滚动 hash 直接使用各 domain 的增量 aggregate，不维护第二套 owner-domain
aggregate；typed change 容器使用 generation reset，避免每条命令 `HashMap.clear` 和 entry 重建。持久化 immutable map root 由有界
`RuntimeCommitPatch` 内部只保存一套连续 canonical sequence，Core 与 projection 访问器映射到同一值；Account Lane groups
与 global owner group 分开保存，不再额外物化派生 owner group 列表。owner 的 prepare/seal/hash/index/publish
由单一提交事务封装；提交失败后毒化实例并恢复，不在已应用的 Lane 之间执行反向补偿。
`RuntimeCommitJournal` 在 owner 内只保存准入、连续 sequence、rolling hash 和诊断计数；每个 entry 自带轻量
`RuntimeProjectionPoint`，不再维护 Snapshot projector 或热 projection replica。显式 Snapshot/query fence 直接从权威
runtime 物化 immutable image 并复算业务/资金 hash；section 编码在同一确定性 snapshot fence 内完成，不创建 encoder 线程。
Core Fact outbox 在 owner 上登记确定性的 typed change、资金 posting 和容量预留，完整协议字节由有界
`core-fact-materializer` 按 export sequence 构造；materializer 不等待 projection，owner 也不等待 materializer。Audit Exporter
异步物化期间，command-level `RuntimeFundsDelta` 可能比对应 `PatchChain` 存活更久；draft 因此保留稳定的
`RuntimeIdentityRegistry` 作为资金 posting 的资产解析兜底，正常路径仍优先使用 patch-local identity，避免延长整条
patch chain 生命周期或重新引入 owner 物化。缺失资产必须 fail-fast，不能静默忽略资金 posting。
查询只返回已完成的连续前缀；`ACK_EXPORT` 作为审计控制命令可以在无关 matcher window 未提交时确认已经物化的连续
前缀；ACK 裁剪只消费随 outbox entry 保存的 primitive 终态订单 ID，不等待 Core Fact view/materializer，
也不把审计 ACK 变成交易 owner 的 projection/matching fence。异步物化失败保留为 sticky fatal failure；
snapshot/outbox 持久化才建立显式 fence。matcher 之前的业务拒绝不会修改权威状态；matcher 已接受后若提交阶段
再出现校验或发布错误则 fail closed，不能伪回滚已经产生的订单簿事实；
批量订单则保留整批累计 delta，直到批次原子提交。产品尚未上线，因此生产代码没有 legacy、fallback、
双写或 feature flag 路径。

Matcher continuation 与 Lane command context 共用按 Core sequence 定位的预分配 ring；pending 顺序也由 primitive
sequence ring 保存。owner 不为撮合命令维护 Future、active set 或 completed-result map；一个 matcher worker 从有界 SPSC
ring 按序取命令，owner 按 Core sequence 批量收割连续 completion 并落账。默认关闭的 matching phase 诊断不会执行 `nanoTime`
或写计时 map。永续订单
准入的 open-interest 比例计算和 risk bracket 选择使用精确 long fast path，只有真实 long 乘法溢出才进入
`BigInteger` fallback；同一命令的杠杆和 matcher evidence 不再重复查询或重复解码。

性能采样必须按 `LinearPerpetualWorkload` JFR 事件的正式 measurement 窗口归因，trial 初始化和快照模板分配不能
计入交易热链路。热窗口内的 wire enum 解码使用直接分支，不创建 Stream；runtime mutation 直接接管本命令构造的
有序 change-set，避免重复复制 `TreeMap`/`TreeSet`；lazy state delta 使用按变更键索引的原子值槽，不为每张 delta map
创建 `ConcurrentHashMap`。批量订单结果在最终响应缓冲区中直接写 frame，状态查询 writer 按精确编码长度预分配。

Trading Provider 的普通单和触发单统一使用异步 Aeron gateway，HTTP 线程不阻塞等待 Core；History Projector
按 Kafka poll 批次提交 PostgreSQL，WebSocket fanout 也按 poll 批量解码、分组和发布。数据库、Kafka 或慢订阅者
只会形成各自有界 backlog，不会在 Product Core 交易 owner 上执行。

## 构建与本地验证

要求 JDK 25。Topic 创建和三节点部署入口仍在整理；PostgreSQL 首发初始化统一使用根目录 `init.sql`：

六条 Product Core 产品线的局部热路径使用独立 JMH 模块验证。它直接驱动内存状态机和内嵌 exchange-core，
不启动 wallet、PostgreSQL、Kafka、Valkey 或 Aeron Cluster；JMH 主裁决固定为一个 Product Core owner，短操作不跨线程；
达到并行结算阈值的多 Lane 衍生成交则覆盖真实的 Lane direct-apply 与 owner completion bitmap。默认覆盖限价挂单、
吃单成交、撤单、部分成交、至少 8 笔成交的多 Lane 撮合、风险扫描、强平执行和配对快照恢复。
`productionMixedWorkload` 额外把多币对做市、触发单、资金费、风险扫描、强平、保险基金和 ADL 放入同一条
确定性 owner command stream，模拟生产中同时到达、由 Product Core 串行裁决的混合负载：

永续正式资格验证统一使用 `surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual.sh`。
入口会拒绝非 HotSpot JDK 25，默认使用 Oracle GraalVM HotSpot 25、固定 4 GiB heap、ZGC、AlwaysPreTouch、
禁用显式 GC、JDK 25 native access、1 个同步 matching engine、4 个逻辑 Account Lane，并把 JMH JSON、JFR、GC/safepoint
日志、JVM 参数和 JDK 版本写入 benchmark `target/qualification/<run-id>/`。正式主吞吐使用无 profiler、无
Native Memory Tracking 的 `throughput` 模式，默认对 1k/10k 用户分别执行 5 轮各 5 秒预热、5 轮各 5 秒计量
和 3 个独立 fork；`gc` 仅用于 `-prof gc` 分配归因，`profile` 独立打开 JFR 与 NMT，二者的吞吐不能作为
主吞吐结论。`e2e` 执行 10,000 个永续实际订单周期。`all` 在源码全部完成后依次执行受影响 reactor 测试和
这四类相互隔离的验证；`jmh` 保留为 `throughput` 的命令别名。每个 JMH 模式都通过 `jq` 拒绝空结果、
accepted/terminal 不一致或 unfinished 非零，避免 fork 启动失败却被 JMH 的零退出码误判为通过：

```bash
surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual.sh all
```

可通过 `SURPRISING_JAVA_HOME` 和 `QUALIFICATION_HEAP` 显式固定环境。ZGC 是资格验证默认值；同步 matcher、
逻辑 Account Lane 和按需状态物化没有 wait strategy，不得再通过旧 completion/projection 参数制造另一套运行路径。

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am clean package
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  'LinearPerpetualCoreBenchmark.*' -p accountLanes=4 -prof gc
```

混合场景默认同时运行 4 个 symbol，测试 1,000/10,000 个真实持仓用户和 4 个 Account Lane。所有活跃用户
都有 1..4 档不等的仓位；为避免把不现实的全量密集挂单预装成本当作生产吞吐，最多 128 个用户持有 0..3 笔
未成交单，共 192 笔静态挂单，其余用户为零挂单。每个 symbol 每轮默认用协议上限 20 笔的批次执行做市
高频挂单、部分成交和撤单，并在前 4 轮各穿插一个 symbol 的触发单、资金费分片和风险扫描分片；资金费与
风险扫描每条命令最多处理 64 个用户，未完成工作通过确定性 cursor 留给后续调度轮次，最后一个 symbol 同轮
完成强平、保险基金覆盖和 ADL。完整清空 10k 用户资金费与风险扫描是独立容量维度，不连续占满交易窗口。
可只选择该场景并把 `terminalBusinessOperations` 作为实际完成的业务操作吞吐读取：

```bash
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  '.*productionMixedWorkload.*' -p activeUsers=1000,10000 -p symbols=4 -p hftBatchSize=20 \
  -p accountLanes=4 -wi 4 -w 2s -i 3 -r 4s -f 1 \
  -jvmArgsAppend '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dsurprising.aeron.matching-engines=1'
```

主指标 `ops/s` 是完整混合 invocation/秒，不是业务 TPS。辅助指标 `terminalBusinessOperations` 按 batch item
计数，是已经产生终态响应的真实业务操作/秒；`terminalCoreMessages` 是未按 batch 展开的 Core command/query/ack
消息/秒。`acceptedBusinessOperations`、`terminalBusinessOperations` 必须相等，两个 `unfinished*` 指标都必须为零。Trial setup
会通过正常命令建仓、挂单并生成快照，invocation setup 从快照恢复；两者以及 teardown
中的批量响应逐项解码、资金总量、触发单、资金费、风险扫描、强平、保险基金和 ADL 终态校验均不进入
计时区间。Core 生成并编码完整响应仍在计时区间，只有基准客户端的重复反序列化校验被移到 teardown。
2026-08-27 primitive commit journal 与异步 Core Fact 改造后，使用 GraalVM JDK 25.0.1、4 matcher / 1 risk
engine、4 Account Lane、4 symbol、96 轮 HFT、batch 20、2 轮预热和 3 轮各 3 秒计量的本机运行中，BUSY_SPIN
下 1,000 用户终态业务操作平均 `19149.495 ops/s`，10,000 用户平均 `19477.474 ops/s`；同配置 10,000 用户
BLOCKING 对照为 `15730.088 ops/s`，带 JFR 的 10,000 用户单轮为 `14716.816 ops/s`。
每轮接受与终态业务操作相等、两个未完成指标为零，teardown 的期初/期末资金守恒校验通过。最终 JFR 的
2,729 个执行样本中，7 个 exchange-core busy-spin 线程占 2,191 个，owner 为 274 个，Fact worker 为 235 个；
owner 栈中没有 `CoreExportCodec`，`RuntimeStateMaterializer.materializeTransition` 为零，lazy `transitionView` 为
8 个样本。Fact worker 的主要成本是 `TreeMap` 组装和协议写入；owner 分配样本仍占 98.22%，下一阶段应优先
消除命令响应/回滚游标中的 Core value 转换和集合分配。当前结果越过 10k/s 功能目标但离单 Product Core
`100k/s` 仍约 5 倍；不能把全仓账户、共享订单簿、保险基金或 ADL 直接拆到多 owner，因为这会破坏同一命令
资金原子性。只有先定义可独立结算的 shard key、禁止跨 shard 全仓与共享资金池，或把跨 shard 操作纳入确定性
协调协议后，才能把分片作为容量方案。该本机结果也不能替代隔离 CPU 的生产同型机器三节点 HTTP P10 长稳门禁。

2026-08-28 继续移除 owner 同步投影后，typed commit 通过 one-shot `RuntimeProjectionPoint` 把不可变
Snapshot root、终态保留观察和 Core Fact 完整视图移到 projection/fact worker；普通及批量订单校验直接读取
`OrderRuntime`，matcher 的前置撤单只传递 `(orderId, userId, symbol)`，不再构造 `CoreOrderState`。同一台
GraalVM HotSpot JDK 25.0.1、4 Account Lane、4 symbol、96 轮 HFT、batch 20、2 轮预热和 3 轮各 3 秒
计量中，1,000 用户为 `23770.228 terminal business ops/s`，10,000 用户为 `25660.077 ops/s`；对应
Core message 为 `2290.545 msg/s` 和 `2472.651 msg/s`，accepted=terminal、unfinished=0。分配率分别为
`459.885 MB/s` 和 `502.399 MB/s`；相对上一阶段 10,000 用户的 `27111.281 ops/s`/`739.170 MB/s`
基线，本轮吞吐受本机噪声影响为 `-5.35%`，分配率降低 `32.03%`。独立 JFR 单轮为
`24117.126 terminal business ops/s`；精确 measurement 窗口的 4,125 个 CPU 样本中，owner
`RuntimeStateMaterializer` 和 `orderSnapshot` 均为 0，46 个 materialization 样本全部位于
`core-projection-linear_perpetual`。当前主要 CPU 是 exchange-core/disruptor wait，主要 owner 分配转为
persistent tree node、命令解码、结果保留和匹配结算集合。20,000 单永续实际路径为 `4030.250 orders/s`，
`p50=189 us`、`p95=443 us`、`p99=695 us`、`max=16431 us`、`pendingMatching=0`。这些本机数据证明
projection 阶段已安全移出 owner，但仍不是每产品线 100k/s 的生产认证结果。

2026-08-28 对剩余五类热点继续收敛：matcher completion 改为有界自旋后带超时阻塞，persistent tree
对 no-op 更新复用原 root 并缩小 node，`PendingMatching` 缓存 typed command，终态保留只拥有一份响应字节，
永续批量结算复用 primitive validation scratch 并直接累计 treasury delta。先前记录的 1,000 用户
`14203.060 ops/s`、10,000 用户 `17578.160 ops/s` 已撤回：该次主运行错误加载了 `-prof gc`，且只有
2 轮各 2 秒预热、3 轮各 3 秒计量和单 fork，不能作为正式主吞吐。

修正后的 HotSpot JDK 25.0.1 + ZGC 无 profiler、无 NMT 正式运行使用 5 轮各 5 秒预热、5 轮各 5 秒计量
和 3 fork；1,000 用户为 `24019.719 terminal business ops/s`，10,000 用户为 `22894.430 ops/s`，对应
Core message 为 `2314.608 msg/s` 和 `2206.152 msg/s`，accepted=terminal、unfinished=0。独立 `-prof gc`
归因中，1,000/10,000 用户分别为 `23583.513`/`20334.009 ops/s`、`542.701`/`478.844 MB/s`，折算约
`23.0`/`23.5 KB` 每个终态业务操作；这些带 profiler 数值只用于分配归因。独立 JFR/NMT 单轮为
`22307.596 ops/s`，70,120 个执行样本中 exchange-core/disruptor wait 占主导，owner 的首个业务热点仍是
`TradingRuntimeState.copyBalances` 和 primitive map 遍历/扩容；JFR `DataLoss=0`，ZGC 最长采样暂停
`0.0663 ms`。进程退出 NMT 显示总 committed `4.595 GB`，其中 Java heap committed `4.295 GB`；单点
NMT 只能作为后续泄漏对照基线，不能单独证明无泄漏。20,000 单实际永续路径通过，`3249.709 orders/s`、
`p50=256 us`、`p95=561 us`、`p99=914 us`、`max=13598 us`、`pendingMatching=0`。当前正式主吞吐是
单产品线 100k/s 目标的约 `22.9%`；不能再用已撤回的 `17.6%` 评价本轮优化效果。

线性永续的规模矩阵使用独立入口
`surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh`。它在 4 个逻辑 Account Lane、
1 个同步 matching engine、HotSpot JDK 25、ZGC 下只运行能暴露规模问题的 10,000 用户、512 个挂牌及活跃 symbol；
小用户数、少 symbol 和半数休眠场景不再进入规模资格矩阵。矩阵包含均匀、80/20、单热点、标记价风暴、
每用户最多 5 仓位/10 挂单以及 512 symbol 全量生命周期 sweep。20 仓位/100 挂单的极端状态密度通过
`capacity` 模式独立执行，避免初始化容量边界污染持续吞吐结论。
`probe` 把每次完整生命周期 sweep 的吞吐、延迟、matcher backlog、未完成风险/资金费分片、快照大小和恢复时间
写入根目录 `target/qualification/<run-id>-scale/scale-matrix.json`；`jmh`、`gc`、`profile`、`soak` 分别保存
无 profiler 吞吐、分配归因、JFR/NMT 和默认 40 分钟的持续状态演进/GC 日志，避免混用采样吞吐。
脚本在 `clean package` 后强制检查 shaded JAR 内的 JMH BenchmarkList/CompilerHints，防止运行旧 benchmark metadata。
每个计量 invocation 同时执行做市挂撤单、IOC/部分成交、触发单、标记价、风险扫描、资金费、强平、保险基金和
ADL；Probe/JMH 使用不可变模板隔离 fork，soak 则在同一个 Product Core 上持续演进状态并在结束时验证快照恢复和
资金守恒：

```bash
QUALIFICATION_RUN_ID=linear-perpetual-scale \
  surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh all
```

撮合持续饱和能力必须与上述重生命周期矩阵分开测量。`saturation` 模式只使用一个共享 Product Core、一个
确定性 owner 和 1 个同步 matching engine，在 10,000 个零售用户（另有按 symbol 隔离的基准基础设施账户）、
512 个活跃 symbol 上连续提交方向相反且最终净持仓归零的
maker/taker 订单；driver 固定 `256 in-flight` 上限，不采集其他档位。owner 连续准备命令，单个 matcher worker
按序撮合；owner 同时批量提交上一段连续 completion 的账户结算和终态响应。结果以 terminal business operations/s 为主吞吐，以
terminal Core messages/s 解释协议开销，同时从 JFR 自定义事件记录三段尾延迟；matcher 期末 backlog 必须为零，
matcher SPSC pipeline 与 driver 的最大在途上限均为 256，owner 每次最多批量提交 64 个连续完成结果，
结束后校验未完成命令为零、活动订单不增长和资金守恒：

```bash
QUALIFICATION_RUN_ID=linear-perpetual-saturation \
  surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation
```

2026-08-29 本机 8 核/16 线程 Intel i9-9880H、16 GiB 内存、8 GiB heap 的最终大规模门禁中，8 个 Probe、
7 个三 fork JMH、GC、JFR/NMT 和 3 分钟诊断 soak 全部通过，所有结果均为 `fundsInvariant=true`、
accepted=terminal、unfinished=0。Probe 的 10,000 用户均匀流量为：256 symbol `5117.737`、512 symbol
`5642.802 terminal business ops/s`；512 symbol、最多 5 仓位/10 挂单为 `5320.613`，80/20 为 `5518.546`，
绝大多数休眠为 `5291.430`，标记价风暴为 `1112.581`，单热点 order book 为 `318.788`，512 symbol 全量
生命周期 sweep 为 `4659.989 ops/s`。对应三 fork JMH 为 `5027.363±961.297`、`5175.617±1405.498`、
`3827.983±2482.701`、`5255.074±1487.556`、`4781.690±983.714`、`1241.243±312.410` 和
`3449.188±1910.780 ops/s`；高密度与全量 sweep 的置信区间很宽，不能把均值当作稳定生产容量。

本轮一次性完成五项优化：persistent tree 的 no-op 更新不再建 node、初始树改为 O(n) 平衡构建并复用 node entry；
Snapshot v16 移除每个 Account Lane 重复保存的全局 `TradingCoreState`，rolling manifest/hash 复用缓存且全量审计在
低优先级线程执行；风险扫描按最落后 symbol 公平续跑并与 trigger scan 共享全局预算；资金费等重生命周期工作按
4 个 Lane owner 并行后由 Sequencer 确定性合并；规模 workload 改为有预算的 symbol 轮转、持续状态 soak 和
大规模专用矩阵。验收又发现 frozen `DeltaMap` 强引用上一版本造成不可变 projection 历史链无法回收；修复后只以
弱 lineage 保留校验身份并合并本命令 changed-key。相同 10k×512 高密度进程强制 GC 后，存活堆从
`5,116,982,168` 降到 `689,630,192 bytes`，`DeltaMap` 从 `11,681,193` 降到 `287,844`，persistent tree node
从 `19,707,803` 降到 `658,296`。最终 3 分钟持续演进完成 `1,203,736` 个终态业务操作，平均
`6443.707 ops/s`，堆在约 2.0–5.6 GiB 间回落、DirectBuffer 峰值 1 byte、快照 `27,772,795 bytes`、恢复
`3995.030 ms`，没有观察到单调增长，但默认 40 分钟 soak 和生产同型机长稳仍是上线门禁。

`gc` 归因运行是 `5609.437 ops/s`、`371.039 MB/s`，约 `126.0 KiB/terminal business op`；新的 JFR 中
exchange-core/Disruptor busy-spin 等待约占执行样本 `84%`，`RuntimeStateMaterializer.materializeTransition`、
Snapshot 编码和 Core Fact 构造均未进入主要 CPU 热点。剩余业务分配集中在 `TreeMap`、`StateMapSupport.delta`、
lineage freeze、mutation delta 和状态 hash。历史 `25660.077 ops/s` 使用 4 symbol、96 轮 HFT、batch 20，
主要测持续轻交易；本矩阵每轮覆盖 512 symbol 并混入风险、资金费、强平、保险基金、ADL 和快照恢复，不能
直接比较。与口径相同的旧 10k×512 全量 sweep 是 `4248.228 ops/s`，新 Probe 为 `4659.989 ops/s`，因此不是
从 25k 回退，而是旧文档混用了两种 workload。当前单 JVM 重生命周期场景离每产品线 100k/s 仍很远，且矩阵不含
Aeron Cluster、HTTP、Kafka、WebSocket；不得标记为 100k/s 生产认证。

当前 `MatcherEvidenceLedger` 保存单 matching engine 的严格单调 native sequence 与连续 prefix，并随 matcher snapshot
校验完整进度。跨 symbol 批次、前置撤单和生命周期 matcher 工作仍使用同一同步引擎，不存在 shard 乱序完成。
普通成交由不可变 matcher fact 直接 fan-out 到固定 Account Lane worker；每个 Lane 只串行执行本 Lane 的资产和持仓变化，
Coordinator 依据 completion bitmap 和 sequence watermark 发布终态。多用户重生命周期工作沿用相同 Lane 所有权边界。

同机、同一 7 GiB ZGC/JVM 参数、10,000 用户/512 活跃 symbol 的改动前后 A/B 为
`2251.935 -> 2752.499 terminal business ops/s`，提升 `22.23%`；该主机同时运行桌面负载且只有 16 GiB，绝对值有明显换页和 CPU 争用，
因此这个 A/B 只证明改造方向，不替代隔离服务器容量结论。最终 512-only 大矩阵、5 个三 fork JMH、GC、JFR/NMT
和 3 分钟诊断 soak 全部 PASS，均为 `fundsInvariant=true`、accepted=terminal、unfinished=0。无 profiler JMH：
均匀低密度 `2571.970±683.121`、高密度 `2605.934±467.229`、80/20 `2518.355±791.503`、标记价风暴
`610.364±99.517`、512 symbol 全量 lifecycle sweep `2337.144±513.754 ops/s`。soak 完成 `601,870` 个终态
业务操作，平均 `3199.540 ops/s`，初始/期末/峰值堆为 `3.92/3.03/4.90 GiB`，DirectBuffer 峰值 1 byte，
快照 `27,239,765 bytes`，恢复 `3685.434 ms`，没有观察到单调增长型泄漏。

GC 归因运行是 `2689.811 ops/s`、`176.481 MB/s`，折算约 `67.19 KiB/terminal business op`；JFR
`DataLoss=0`，9 次 ZGC 的最长暂停 `0.192 ms`、allocation stall 为 0。JFR 执行样本中
`ProcessingSequenceBarrier.checkAlert` 56.95%、`WaitSpinningHelper.tryWaitFor` 11.03%、ring cursor/minimum
sequence 14.81%、busy-spin/`Sequence.get` 2.19%，共享 ExchangeCore/Disruptor 等待合计约 84.98%。首个应用层
热点 `CoreStateHash.mix` 仅 0.78%；主要分配仍在 `TreeMap`、persistent tree/delta lineage、mutation delta 和
Treasury delta 合并。因此当前仍未达到每产品线 100k/s；下一阶段应围绕共享 ring/等待拓扑做物理隔离实验，
同时保持同 symbol 串行、Core commit/资金/Treasury 全局确定性，不应再给普通成交引入同步 Lane barrier。

2026-08-29 owner commit/typed fact 阶段继续一次性完成四项边界收敛：ready matcher result 按全局 Core sequence
最多批量提交 64 条；runtime mutation 以 hash scratch 捕获并只对 dirty key 排序，不在 owner 构造 `TreeMap`/
`TreeSet` change-set；Core Fact 使用上述 typed journal 在线程外构造完整 view/协议字节；Audit ACK 不等待无关的
matching window 或 projection。`CoreProbeStateTest` 与 `RuntimeStateProjectorTest` 的失败优先用例分别锁定 ACK
旁路和确定性 dirty-key 顺序，核心服务 374 项测试、基准夹具 8 项测试全部通过。

同一台 8 核/16 线程本机、HotSpot JDK 25.0.1、ZGC、8 GiB heap、4 Account Lane、4 matcher、10,000 用户、
512 个活跃 symbol、每 invocation 16,384 条 maker/taker 指令的无 profiler 饱和矩阵中，BUSY_SPIN 的
64/256/1024 window 分别为 `4138.608`、`4238.495`、`4039.654 terminal business ops/s`，YIELDING 256 为
`4702.306 ops/s`；相对同口径旧值，64/256/YIELDING 分别提升约 `42.0%`、`42.1%`、`44.8%`，1024 基本持平。
全部 accepted=terminal、unfinished=0，trial teardown 逐轮核对期初/期末资金和活动订单均一致。独立 JFR/NMT
单轮为 `4129.358 ops/s`，平均 matcher backlog `200.5/256`、最大 256；四个 invocation 的完成延迟范围为
`p50 32.3–47.5 ms`、`p99 45.7–71.9 ms`、`p99.9 47.3–84.9 ms`。43 次 ZGC 暂停总计 `0.615 ms`、
最大 `0.0602 ms`，allocation stall/OOM 为 0，DirectBuffer 为 0–1 byte。

JFR 的 27,062 个执行样本中，exchange-core/Disruptor wait/cursor 约占 `86.8%`；这主要是 14 个等待线程在
8 核主机上与 owner、projection、fact worker 争用 CPU，YIELDING 比 BUSY_SPIN 高约 `11%` 也印证了过度忙等。
owner 的 2,528 个样本中首项为 matcher completion 的 `CompletableFuture.isDone`（416），之后是业务 hash
和仍属权威 runtime map 的查询/更新；owner 分配则以 `HashMap` 扩容、`ValueChanges`、`HashSet` iterator、
命令解码和 Treasury delta 合并为主。projection/fact materialization 已不在 owner 上，但当前约 4.0–4.7k/s
仍远低于每产品线 100k/s，不能标记为生产容量认证；下一阶段应先减少 owner dirty-delta/hash/命令解码分配，
并在隔离 CPU 的生产同型机上验证 matcher 等待策略和线程绑核，而不是继续放大 in-flight window。

2026-08-29 completion/delta 阶段移除了 `LaneCommandContextRing` 对逐命令 `CompletableFuture` 的保留和
`isDone` 轮询，matching callback 只向有界 completion queue 发布带 Core sequence 的结果，owner 通过单调
publication cursor 批量 drain；关闭流程等待 queue submission 归零。matching 命令在 prepare、生命周期冲突
检查、预撤单和 `PendingMatching` 间只解码一次 typed view。runtime mutation dirty key/value 使用排序数组和
primitive `int[]` 开放寻址索引，不创建逐项 `HashMap` node；business/funds rolling hash 缓存根值，无序贡献
索引不再使用 `TreeMap`。首次线性 value lookup 实现被饱和测试识别为 `643 ops/s` 回归，JFR 显示其几乎占满
owner 有效样本，修正为开放寻址后同一控制样本恢复到 `3708 ops/s`，该失败 artifact 不作为容量结果。

最终 HotSpot JDK 25.0.1、ZGC、4 Account Lane、4 matcher、10,000 用户、512 活跃 symbol 的 16,384 指令
无 profiler 饱和矩阵中，BUSY_SPIN 64/256/1024 window 分别为 `4925.756`、`4985.047`、`4622.785 terminal
business ops/s`，相对上一同口径结果提升约 `19.0%`、`17.6%`、`14.4%`；全部 accepted=terminal、
unfinished=0，teardown 的资金和活动订单不变量通过。YIELDING 256 在矩阵后段为 `2328.412 ops/s`，同时随后
BUSY_SPIN JFR 运行也降至 `2174.032 ops/s`，表明本机连续 8 GiB 多进程采样存在明显热/调度漂移；等待策略仍可
切换，但这两个后段数值不能作为隔离服务器上的策略 A/B。

最终 JFR `DataLoss=0`，`CompactValueMap.get` 从失败样本的 `3.56%` 降至 `0.40%`，owner top 已无
`CompletableFuture.isDone`；exchange-core/Disruptor wait/cursor 仍约占 `86.2%` 全线程样本。7 次 ZGC 的最大
暂停 `0.062 ms`，allocation stall/OOM 均为 0；带 profiler 的三个 16,384 指令 invocation 延迟为 p50
`58.8–78.6 ms`、p99 `85.8–147 ms`、p99.9 `102–191 ms`。这些结果证明本阶段消除了 Future/线性 delta
回归并提高当前单 Core 上限，但离每产品线 `100k/s` 仍约 20 倍，不能标记为生产容量认证。下一阶段应直接削减
owner 上的 persistent tree/delta lineage、fact materialization 临时集合和 commit allocation，并在隔离 CPU、
足够物理核与内存的服务器上重新做 BUSY_SPIN/YIELDING 随机顺序 A/B。

2026-08-29 sparse delta/fact 阶段让空 mutation family 共享不可变空值，只有实际存在 after-image 时才建立
lane/owner map；少量 dirty value 使用紧凑查询，空资金 posting 共享 `RuntimeFundsDelta`。projection 对未变化的
order、instrument、risk、treasury 等 map/root 直接复用上一版本，只为相关 dirty family 建立 delta；Core Fact
的 user/liquidation/trigger/treasury view 改为确定性循环与 primitive ID 去重，不再创建 stream/`LinkedHashMap`/
`TreeSet` 临时集合。重复饱和测试同时发现 Core Fact 尚未异步登记终态订单时 Audit ACK 可先完成，导致两个已终态
订单错过当前裁剪；ACK 现在利用 outbox 随提交保存的 primitive 终态订单 ID 在 owner 上登记后裁剪，迟到的 fact
观察不能越过 tombstone 重新保留同一订单。对应竞态回归连续 5 轮通过，核心服务 378 项和 benchmark 10 项测试
全部通过。

同一 HotSpot JDK 25.0.1、ZGC、8 GiB、4 Account Lane、4 matcher、10,000 用户、512 活跃 symbol、每 invocation
16,384 条 maker/taker 指令下，首次完整矩阵的 BUSY_SPIN 64/256/1024 为 `4575.316`/`5421.945`/
`4670.304 terminal business ops/s`，YIELDING 256 为 `5064.447 ops/s`。竞态修复后的最终同源码运行因连续
8 分钟测试与 busy-spin 线程在 8 核本机争用，BUSY_SPIN 三点降为 `2994.058`/`2745.905`/`2143.459`，但同轮
YIELDING 256 回升到 `5884.217 ops/s`，随后 BUSY_SPIN JFR 单轮也回升到 `4911.514 ops/s`；因此这些本机矩阵
只能证明正确性和调度敏感性，不能把单个低值或高值当作隔离服务器容量。所有运行均 accepted=terminal、
unfinished=0，teardown 的资金总量和活动订单不变量通过。

最终 JFR 的四个 16,384 指令 invocation 延迟收敛到 p50 `24.4–44.2 ms`、p99 `30.7–82.5 ms`、p99.9
`32.0–105.6 ms`。相对上一同配置 JFR 的 allocation sample weight，owner 从 `7.379 GiB` 降至 `7.023 GiB`
（约 `-4.8%`），projection 从 `2.799 GiB` 降至 `2.153 GiB`（约 `-23.1%`），fact materializer 从
`1.817 GiB` 降至 `1.253 GiB`（约 `-31.0%`）；这是采样权重而不是精确分配率。JFR `DataLoss=0`，7 次 ZGC
最大暂停 `0.275 ms`，allocation stall/OOM 为 0。owner 的 2,218 个执行样本中，completion publication
cursor 等待占 504（`22.7%`），其后是 runtime `TreeMap`、rolling hash 和 small `CompactValueMap` 查询；
projection/fact 分别只有 344/263 个样本。当前最佳同轮终态吞吐仍不足 6k/s，离每产品线 `100k/s` 很远；
下一阶段的主攻点已经从 projection/fact 临时集合转为 owner completion 协调、权威 runtime map/hash 和物理 CPU
隔离，不能继续靠增大 in-flight window 获得目标容量。

2026-08-29 本阶段一次性收敛六项 owner 热路：matcher 之前完成触发单和 replacement 身份校验，matcher 接受后取消
业务回滚；completion 从 MPSC queue/publication cursor 改成按 Core sequence 定位的预分配 mailbox；Core Fact 的
order view 和协议编码继续在线程外生成，ACK 只携带 primitive 终态保留元数据；runtime mutation/commit/funds ledger
的 long/int key 与 lane after-image 使用 primitive 容器；逐提交资金 posting 按资产守恒，完整资金总量和 immutable
snapshot 则在 teardown/soak 核对；查询分派通过单一 `QUERY` 分支与命令热路隔离。回归过程中还修复了 mailbox
“槽位先发布、depth 后增加”的消费竞态，以及无 pending sequence 时 overflow 未优先 fail closed 的问题。

HotSpot JDK 25.0.1、ZGC、8 GiB、4 Account Lane、4 matcher、10,000 用户、512 活跃 symbol、16,384 指令、
BUSY_SPIN/256 window 的 5×5 秒预热、5×5 秒计量、3 forks A/B 中，当前为
`5744.730 ± 276.257 terminal business ops/s`，固定基线 `2fd9fb77` 为
`5419.854 ± 475.267 ops/s`，均值提高 `5.99%`；99.9% 区间仍重叠，因此只判定没有可确认回退且方向正向，
不声明统计显著提升。最后一项 fail-closed 门禁加入后的同配置 1 fork 复测为 `5858.194 ops/s`，
accepted=terminal、unfinished=0。service 全模块 `380` 个测试及 benchmark 模块 `10` 个测试均为 0 失败；
3 分钟 10k×512 soak 完成 `1,400,899` 个终态业务操作，摘要为
`fundsInvariant=true`，平均 `7509.324 ops/s`，snapshot `27,947,612 bytes`、恢复 `3416.690 ms`，
期末 heap 比期初低，direct memory 为 0。`-prof gc` 的重生命周期场景为 `400.255 MB/s`，折算约
`139,571 B/terminal business op`；JFR `DataLoss=0`、ZGC allocation stall/OOM 为 0，暂停主要为
`0.01–0.06 ms`，最终采样最大 ZGC pause 为 `0.163 ms`。JFR 有效分配热点仍是 reservation rolling hash、
`CompactKeyList`、mutation capture 和 persistent tree；全线程执行样本约 `84.6%` 是
exchange-core/Disruptor 的前四项 wait/cursor，属于本机 8 物理核上等待线程
与 owner 的 CPU 争用。当前结果仍不是每产品线 100k/s 生产认证，且 3 分钟只属于诊断 soak，正式门禁仍要求 40 分钟。

其余五条衍生品线统一使用 `DerivativeCoreBenchmark.productionMixedWorkload`，通过 `productLine` 参数选择
`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY` 或 `OPTION`。场景固定
4 个 Account Lane、4 个 symbol 和 1,000/10,000 个真实持仓用户，混合批量挂单/撤单、双向 IOC、部分成交、
多 symbol 做市、风险扫描；永续额外执行资金费。每个 trial teardown 都要求 accepted=terminal、unfinished=0，
逐用户检查非负余额和终态 reservation，并按用户资金、手续费、保险、强平费、资金费残差、舍入残差、清算
损益及保险缺口精确核对结算资产总量，最后验证 immutable snapshot 恢复后的 business hash 和资金总量相同。
交割到期结算及期权行权/失效属于生命周期容量，不混入持续交易 TPS；其资金流水、订单释放、持仓终态和分片
cursor 由 `CoreDeliveryOptionFinancialMatrixTest` 独立验证。

```bash
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  'DerivativeCoreBenchmark.productionMixedWorkload' \
  -p productLine=LINEAR_PERPETUAL,INVERSE_PERPETUAL,LINEAR_DELIVERY,INVERSE_DELIVERY,OPTION \
  -p accountLanes=4 -p activeUsers=1000,10000 -p symbols=4 -p hftRounds=8 -p hftBatchSize=20 \
  -wi 3 -w 1s -i 5 -r 2s -f 1
```

2026-08-28 OpenJ9 JDK 25 的本机诊断采样使用 1 次 1 秒预热和 1 次 2 秒计量；1,000 用户用 32 轮 HFT，
10,000 用户用 8 轮 HFT。1,000 用户终态业务吞吐分别为 `9053.336`、`8607.368`、
`9066.590`、`8613.550`、`9442.274 ops/s`；10,000 用户为 `11365.815`、`11003.816`、
`11574.263`、`11346.806`、`11389.755 ops/s`。前四条 10k 数据采于通用杠杆计算优化前，期权数据采于优化后；
全部场景未完成业务/Core 消息均为零，资金守恒与快照恢复通过。期权热路径修复了买方零开仓保证金仍提前计算
权利金/风险档位的溢出与冗余工作，并把四处 `BigInteger` 杠杆保证金率计算收敛为精确、无分配的 `long`
向上取整。JMH 明确不支持 OpenJ9，因此这些数字只能用于本机缺陷定位，不能替代 HotSpot JDK 25、隔离 CPU、
多轮计量和三节点端到端压测的生产容量结论。

现货使用独立的 `SpotCoreBenchmark.productionMixedWorkload`。场景在 4 个 symbol 和 4 个 Account Lane 上预装
1,000/10,000 个异构用户余额及 0..3 笔静态挂单；计时区间循环执行批量挂单/撤单、双向 IOC 成交、部分成交后
撤余单和 Audit Export ACK。每个依赖阶段先提交 4 个相互独立的 symbol，再按 Core sequence 确定性完成 matcher，
不会在 symbol 之间逐笔同步等待；JMH observable 使用 Core 的缓存 rolling state hash，不在计量区间额外遍历全量用户。
teardown 逐币种核对用户可用/冻结余额与 treasury，要求 accepted=terminal、
unfinished=0、所有保留的终态 reservation remaining=0，并对当前状态再做一次配对快照恢复和 business hash 校验。
正式命令如下：

```bash
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  '.*SpotCoreBenchmark.productionMixedWorkload.*' \
  -p accountLanes=4 -p activeUsers=1000,10000 -p symbols=4 -p hftRounds=24 -p hftBatchSize=20 \
  -wi 2 -w 2s -i 3 -r 3s -f 1 \
  -jvmArgsAppend '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Xms4g -Xmx4g -Dsurprising.aeron.matching-engines=1'
```

2026-08-27 本机 GraalVM JDK 25.0.1 优化后正式运行中，1,000 用户的终态业务吞吐平均为
`16814.776 ops/s`，10,000 用户为 `19344.692 ops/s`；对应 Core message 吞吐分别为
`1612.718 msg/s` 和 `1855.365 msg/s`。与相同正式参数的优化前 10,000 用户结果 `13446.509 ops/s`
相比提升 43.86%；同一轮短 A/B 从 `7996.295 ops/s` 提升到 `9899.442 ops/s`，提升 23.80%。
10,000 用户带 JFR 的 8 秒计量为 `15355.472 ops/s`。三组均 accepted=terminal、unfinished=0，逐资产
资金守恒与快照恢复校验通过，因此当前现货本机热路径稳定越过 10k/s，但没有达到 100k/s。

修复后的 JFR top-25 已不再出现全量 `RollingBusinessStateHash.compute` 和 `projectSnapshotNow`；
`drainMatchingCompletions` 只占 0.13%。生产 owner 的空 `RuntimeMutationDelta` 也直接复用原不可变 map，
不再为未变化的状态域分配 `LazyDeltaMap`、`TreeSet` 和并发缓存。整段记录仍约 87% 落在
exchange-core/disruptor busy-spin，这是 matcher 等待策略的 CPU/尾延迟取舍，不再是计量 owner 的 projection
瓶颈；可以按部署 CPU 隔离情况切换为 `YIELDING` 或 `BLOCKING`。

该场景还复现并修复了一个恢复缺陷：只有 instrument、没有活动订单/风险引用的现货 symbol，在快照恢复后没有
重建 runtime symbol identity，首笔批量订单会在冻结资金前 fail-fast。`RuntimeStateProjector` 现在从权威
instrument map 预备全部 symbol identity，聚焦红测和完整现货场景均已覆盖。

同步 matcher 固定为一个 engine。Account Lane 使用固定 SPSC worker，settlement wait strategy 只影响 Lane 空闲等待，
不改变事件顺序、完成位图或 committed watermark 语义。
`RuntimeCommitJournal` 只做当前 owner transaction 的有界准入和连续 sequence 记录，没有 Snapshot projector、
projection wait strategy 或批量 flush。Snapshot/query fence 直接物化权威 runtime；Core Fact materializer 保留有界
异步编码，但 owner 不等待它。容量耗尽、sequence 缺口或 fence hash 不一致都会 fail-fast，不存在 legacy 双写路径。

快速确认 JMH 打包与场景可执行时可缩短迭代；该命令只用于 smoke，不作为容量结论：

```bash
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  'LinearPerpetualCoreBenchmark.*' -p accountLanes=4 -wi 0 -i 1 -r 100ms -f 1
```

`multiLaneMatching` 的一次 taker 会吃掉分布在全部 Account Lane 上的 maker 深度；默认测试
`makerDepth=1000,10000`，`riskScan` 默认测试 `riskUsers=1000,10000`。大规模 maker 和持仓用户在
Trial setup 中预装并生成快照，每次 invocation 从同一快照恢复，计时区间只包含目标撮合或风险扫描；
预装期间会模拟 Audit Exporter 确认 Core Fact，避免把 64 MiB replicated outbox 上限误当成交易容量上限。
撮合夹具为每个 Account Lane 创建一个 maker 并轮询分配 1k/10k 笔独立订单；风险夹具创建 1k/10k 个
独立持仓用户，并由一张足额安全对手单完成建仓，因此 `riskUsers` 是真实扫描用户数而不是名义数量。
正式采样应使用 HotSpot 兼容的 JDK 25；OpenJ9 可用于 smoke，但 JMH 会禁用 compiler hints，
其输出不能作为容量或延迟基线。
JMH 结果只代表单个 Product Core 的本地内存基准。它不包含 Aeron ingress/replication、
HTTP/WebSocket、Kafka、数据库、Cluster 故障切换和端到端资金对账，这些外围链路仍需独立门禁；多个 lane
是账户隔离、路由、局部 hash 和恢复校验边界，不会把唯一 owner 状态机变成多写者，也不会产生逐命令跨线程往返。

```bash
createdb surprising_exchange
psql -v ON_ERROR_STOP=1 \
  postgresql://surprising:surprising@localhost:5432/surprising_exchange \
  -f init.sql
```

`init.sql` 是 PostgreSQL 18+ 的完整首发基线，在同一事务内创建配置、历史、审计、对账和 Aeron Core 投影表，
并写入 `surprising_schema_metadata`。首发 instrument 使用 BTC、ETH、SOL、XRP、DOGE、BNB、ADA、AVAX、
LINK、DOT、LTC、BCH、TRX、TON、SUI、APT、NEAR、UNI、AAVE、ETC，共 20 个主流资产；六个
`ProductLine` 各初始化 20 个 symbol，总计 120 个。衍生品 symbol 使用产品线/到期日后缀，避免同库同名冲突。

产品上线后不得通过修改 `init.sql` 升级存量数据库；新增 SQL 必须遵循 `migrations/README.md` 的版本、事务和
验证规则。当前上线前日期补丁已全部折叠进基线，不需要再次执行。

当前优先执行受影响模块测试：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am test
```

### 受影响模块构建

不要在未界定影响范围时直接执行根目录 `mvn package`。先按变更边界选择 Maven module，再使用 `-am` 补齐必要
上游模块；文档改动只运行静态检查。每次验证都要保存完整命令和输出 artifact，不删除其他模块的 `target/`。

```bash
# 受影响模块的构建和测试
mvn -pl surprising-trading/surprising-trading-provider -am package
mvn -pl surprising-aeron-core/surprising-aeron-service -am test
```

源码或模块 `pom.xml` 变化按 Maven reactor 依赖闭包扩大范围；根 `pom.xml`、`surprising-parent/pom.xml` 或共享公共
API 变化需要重新界定直接受影响消费者。`package` 是默认构建目标；需要发布到本地仓库时显式使用 `install`。跨账户、
撮合、风控或协议变更必须执行受影响模块测试。

matching 使用 `exchange.core2:exchange-core:0.5.18-emporia` 及其 Chronicle/OpenHFT 传递依赖，必须使用
以下 JVM 参数：

```text
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

Product Core 固定使用 Aeron `1.53.0`。外部风控已经由 Product Core 完成的订单进入 exchange-core 的
`MATCHING_ONLY` 直达管线 `Grouping → Matching → Results`，不创建 R1/R2 风险处理器；热路径通过有界关联环提交，
不创建 exchange-core promise/Future，也不执行 matcher 用户注册。通用 exchange-core 配置仍保持原 R1/R2 与多生产者语义。

### 单产品线做市与 JFR 压测

真实链路一次只启动一个 `ProductLine`，做市进程保持运行；在线余额、冻结、持仓、订单簿和 Treasury
必须通过 Aeron Core 查询，不以 PostgreSQL 投影作为裁决来源。启动脚本默认使用 ZGC；短时采样打开
JFR，并同时记录 GC/safepoint 日志：

```bash
PRODUCT_LINE=LINEAR_PERPETUAL RUN_ID=mm-lp-jfr-<run> ACTION=up \
  POSTGRES_MODE=native POSTGRES_DB=surprising_exchange_qa_linear_perp \
  JAVA_HOME=/path/to/jdk-25 JVM_GC=ZGC JFR_ENABLED=true \
  PRICE_HTTP_PROXY_ENABLED=false PRICE_INDEX_REQUIRED_SYMBOLS=BTC-USDT-SWAP \
  PRICE_CONSUMER_REQUIRED_SYMBOLS=BTC-USDT-SWAP MM_SYMBOL=BTC-USDT-SWAP \
  MM_BASE_QUANTITY_STEPS=20 MM_ORDER_LEVELS=20 \
  bash scripts/start-product-line-providers.sh up
```

使用 `ClusterProductLineGateMain` 覆盖下单、撮合、资金费/交割/行权或强平路径，再用
`ClusterFundsReconcileMain` 逐资产核对用户、做市账户和 Treasury 的总额、冻结、持仓及未完成 work；只有
`fundsReconcile=PASS`、`fundsDiff=0` 且盘口清空后才进入容量测试。容量测试应使用独立 symbol 和用户范围，
先执行 `capacity-workers=1` 的串行基线，记录 accepted/finalized、失败数、p50/p99/p99.9 和 book/funds
结果。压测 JVM 使用以下等价配置，`stackdepth` 是 `FlightRecorderOptions` 参数，不是
`StartFlightRecording` 的 `.jfc` setting：

```text
-XX:+UseZGC -Xms512m -Xmx512m -XX:+AlwaysPreTouch
-XX:FlightRecorderOptions=stackdepth=256
-XX:StartFlightRecording=filename=<run>.jfr,settings=profile,dumponexit=true
-Xlog:gc*,safepoint:file=<run>-gc.log:time,uptime,level,tags
```

采样结束后使用 `jfr summary <run>.jfr`、`jfr view hot-methods <run>.jfr` 和
`jfr view gc <run>.jfr`，同时保留每条产品线的容量输出和资金对账结果；JFR 只用于性能分析，不改变
Core 的资金权威边界。

Chronicle 版本由父 POM 的 BOM 统一管理，避免旧版在 JDK 25 中触发 `unmap0`/`Bytes` 初始化错误。
默认合并进程和端口：

| Provider | 端口 |
|---|---:|
| instrument | 9080 |
| market-data（matching projection/candlestick） | 9081 |
| price-provider（index/mark/fx） | 9082 |
| trading-provider | 9084 |
| account | 9086 |
| derivatives-lifecycle（risk/liquidation/insurance/adl） | 9087 |
| funding | 9089 |
| websocket | 9097 |
| gateway | 9094 |
| market-maker | 9096 |

## 测试

```bash
mvn test
mvn -pl :surprising-aeron-client,:surprising-aeron-tools -am test
```

当前 Maven 测试覆盖构建和核心 Aeron 客户端/工具链。恢复门禁按六个 `ProductLine` 分别启动一个 Core，
验证原生 matcher snapshot、同价 FIFO、订单/资金/持仓状态和失败关闭；未通过真实三节点、provider、做市、
Kafka 集群或长时间容量验证的场景必须在交付记录中明确标记。
交易裁决和结算在 Aeron Core 内存状态机完成；PostgreSQL 只用于异步投影、审计和对账。

## 生产部署

生产部署 Runbook、基础设施初始化和容量基线正在重新整理，当前不提供可直接执行的部署命令。
即使部署文档尚未恢复，四类业务线的六个 `ProductLine` 变体仍必须独立配置和验证：

- 每个变体只配置一个 `coreShardId=default` 的三 Member Cluster；cluster id、端口、Archive、snapshot、
  data volume、gateway route、Topic、consumer group 和账户类型不得复用。
- 三个 Member 必须跨故障域部署，使用本地持久盘保存 Archive；启动时校验 fork SHA、matcher/Core snapshot
  manifest、完整 engine/book hash、registry hash 和恢复水位，任一不一致直接失败关闭，不进入 `READY`。
- Gateway 通过固定 Aeron agents 和有界 mailbox 向 Core 提交命令；Kafka、Order Provider、PostgreSQL 和
  Redis/Valkey 不参与资金预留、撮合或成交结算的同步裁决，也不承担核心恢复。
- 关闭 Kafka 自动建 Topic，使用经过审查的配置创建外围输入和 Core Export Topic；不在已有 symbol-keyed
  Topic 上直接增加分区，扩容使用版本化 Topic 和受控投影重建。
- PostgreSQL、Kafka 或 WebSocket 故障只能造成投影/推送延迟；Exporter 必须按 Core export sequence
  at-least-once 发布，消费者按稳定事件键幂等，积压达到上限时 Core 在 mutation 前显式背压。
- 上线前按单个 ProductLine 变体运行做市、全链路资金守恒、Leader/follower/cold recovery、24 小时 soak
  和容量门禁；生产峰值不超过满足 SLO 的实测容量 70%。
- 监控 Aeron election/commit position、Archive/snapshot、matcher queue/latency、Core p99/p99.9、GC、
  export event age、Kafka/PG/WebSocket lag、资金差额和 book hash；不以平均 TPS 代替容量结论。
- `ClusterProbeMain` 使用 `-Dsurprising.aeron.probe-mode=metrics` 从 Product Core 的 committed query surface
  获取 Lane 指标并输出 Prometheus text format；指标覆盖 matcher/completion/context queue、每 Lane applied/committed gap、
  queue depth/capacity/high-water、拒绝数、oldest pending 以及 command/settlement/query/risk 延迟，标签只含
  `product_line`、`lane_type`、`lane_id` 和有界 `operation`。

Topic、端口、磁盘、监控阈值和故障演练的精确清单待生产 Runbook 补充；不得改变上述所有权和恢复边界。

### P10 快照与发布契约

- fork 固定为 `exchange-core 0.5.18-emporia`，源码提交
  `4636c44b19de90be0bd6c85afdd0e4fa190da9f0`，可复现 JAR SHA-256
  `4a6e41ae66822eddf8539fa8bb80fe77ffc3cc4adc7376d6666b45cf24ee874e`；fork 构建拒绝 dirty
  worktree，从已认证提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和内嵌 provenance；
  Aeron service 的 Maven `validate` 阶段同时校验 provenance 与整包 hash。
- 当前唯一写格式为 command/envelope schema v4、Core Export marker v9、`TradingState v24`、sectioned snapshot v15
  和 matcher snapshot v5；所有旧主版本立即 fail closed，没有 legacy reader。sectioned snapshot 按 laneId 升序保存
  4 个实际 Account Lane state section，matcher section 保存 `matchingEngineCount` 个原生 matcher module 和
  `MATCHING_ONLY` 直达模式不生成 risk module。
  恢复只使用 `InitialStateConfiguration.fromSnapshotOnly`，不允许 clean-start、活动订单回放或第二本 FIFO。
- capture 在单共享 ExchangeCore 与 Core state 的配对 snapshot fence 内完成；存在 pending matching 时精确拒绝快照。恢复在开放流量前执行
  CRC32C、产品线/路由、snapshot ID、Core/matcher sequence、Cluster position、source/outbox digest、
  fork/config/artifact、注册表、完整引擎哈希、盘口哈希和全部 OPEN 订单逐字段对账；全部通过后才替换内存状态。
- 任一 Lane section 缺失、重复、CRC/route/hash 错误，或 native module 数、prefix、book/order pairing 不一致时直接中止 READY。
- Risk Scan Control 的当前值仅存在于 Product Core 状态、Cluster Log/Archive 和 snapshot；Provider 通过
  `RISK_SCAN_CONTROL_QUERY` 查询，并以 `expectedVersion` 提交更新。PostgreSQL 只通过 Core Export→Kafka
  异步保留审计事实，不保存或覆盖当前配置。

## 文档

当前保留的文档入口：

- 根目录架构、构建和测试说明：本文档；
- [账户模块 README](surprising-account/README.md)；
- [Aeron 核心模块 README](surprising-aeron-core/README.md)；
- [撮合交易模块 README](surprising-trading/README.md)；
- [网关模块 README](surprising-gateway/README.md)；
- [Gateway WebSocket 说明](surprising-gateway/README-websocket.md)；
- 其他模块的 README 位于各自模块目录下。

新的部署、产品线、资金模型、撮合和压测文档请按主题补回，并同步更新本文档入口。
