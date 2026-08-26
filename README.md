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
| P2 | deterministic `MATCHING_ONLY` 推进与成对快照：adapter 每次仅执行一个按 Core sequence 排序的 matcher 命令，先取得不可变结果并推进可恢复的 matcher prefix digest，才执行下一条。 |
| P3 | TradingRuntimeState 生产写入权威：六条产品线的 Runtime owner 线程是唯一交易裁决状态，immutable `TradingCoreState` 仅是快照、事实增量、恢复、hash 与对账投影。 |
| P4 | six isolated settlement kernels：Spot、LinearPerpetual、InversePerpetual、LinearDelivery、InverseDelivery、Option 各有穷尽且隔离的结算内核。 |
| P5 | FundsDelta、Treasury、操作级舍入与连续性：每命令/资产资金变动不可变且确定性排序，Treasury 子账本、残差和 Core Fact 前后状态证据显式核算。 |
| P6 | randomized properties / model campaign：随机化属性与模型验证，不是 P0-P5 行为改动。 |
| P7 | fatal / readiness campaign：致命故障和就绪性验证，不是 P0-P5 行为改动。 |
| P8 | three-node recovery certification：三节点恢复认证，不是 P0-P5 行为改动。 |
| P9 | 1,000-user / 40-minute certification：千用户、四十分钟验证，不是 P0-P5 行为改动。 |
| P10 | single-Core deterministic lanes / capacity：每个 Product Core 只运行一个共享 ExchangeCore，以原生 symbol matcher shard 和默认启用的 user Account Lane 扩展；同一不可变 matcher result 引用按 userId 扇出，以 expected/ack Lane mask 提交，Treasury 保持 Sequencer owner，不包含物理 Core shard。实施规范见 [`docs/P10-DETERMINISTIC-LANES.md`](docs/P10-DETERMINISTIC-LANES.md)。 |

当前实现状态：P0-P5 与 P10-A 至 P10-C、P10-F 的主体迁移已完成；每个 Account Lane 使用固定 platform owner thread，
账户状态 mutation、query/read fence 和 snapshot capture/restore 都经过有界双向 SPSC ring。P10-D/E 正在做缺陷闭环：
当前 immutable result 已按 expected Lane mask 扇出并有 ACK/commit fence，但撮合结算仍存在 Sequencer 编排的同步逐访问
Lane 往返，Treasury ACK 仍需替换为 Lane 原生 per-asset delta；在该门禁完成前不得宣称 P10-A 至 P10-F 全部完成。
P10-G 使用真实 HTTP 开放环门禁，只有保存 1,000 用户、
至少 200 symbol、100k/s offered rate、40 分钟、JFR 和资金/盘口核对 artifact 后才可标记生产认证完成。普通下单只提交一次正式 `PLACE_ORDER`，
由 Product Core 在同一权威转换内完成 P1 的预占、平仓容量和费用校验；显式 dry-run 接口仍可调用只读 preflight，
但不在正式下单前额外往返 Core。`DeterministicExchangeCoreAdapter` 向一个共享 ExchangeCore 的原生 matcher shard
提交有界 pipeline，直接持有
exchange-core 产出的不可变 `MatcherResult`、event list 和 market data，不再复制 matcher 证据，并用
`matcherSequence + MatcherPrefix(before, after)` 绑定命令结果；prefix digest 随配对快照恢复，断裂或 malformed
结果立即 fail closed。普通命令不再生成逐命令全量 `BookHashes`，完整 `bookStateHash` 只保留在 snapshot、恢复和
显式审计边界。`TradingRuntimeState` 是 P3 唯一交易裁决权威；`TradingCoreState` 仅在每个事实边界按 changed-key
生成一次不可变投影，承担 Cluster snapshot、Core Fact、恢复、状态 hash 和对账。P4 使用六个穷尽且隔离的
`SettlementKernel`。P5 以确定性 `FundsDelta`、Treasury 子账本、状态/资金 hash 和 replicated outbox 形成连续事实链。
P10 使用 `routeVersion=2`、默认 4 个 native matcher shard 和 4 个 Account Lane；pending reservation 在 commit 前不进入
query、Snapshot State 或 Core Fact，immutable matcher result 以 expected/ACK mask 提交，Core Fact 仍严格按全局 sequence 发布。

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
- 当前不为热点币对单独部署 Core。协议固定 `coreShardId=default`、`routeVersion=2`；symbol 只路由到同一共享
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
- Core Exporter 以连续 Export Sequence 向 Kafka at-least-once 发布并幂等写 PostgreSQL；只有完整批次
  成功后才向 Aeron 提交 ACK，不新增数据库 outbox 或应用 WAL。
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
    Exporter --> Projection[PostgreSQL 异步投影 / 对账]
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
`nextLiquidationId`。连续 mark/continuation 由 owner thread 在持久 Runtime 原地增量提交，
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

## 构建与本地验证

要求 JDK 25。Topic 创建和三节点部署入口仍在整理；PostgreSQL 首发初始化统一使用根目录 `init.sql`：

线性永续 Product Core 的局部热路径使用独立 JMH 模块验证。它直接驱动内存状态机和内嵌 exchange-core，
不启动 wallet、PostgreSQL、Kafka、Valkey 或 Aeron Cluster；JMH worker 固定为一个 owner thread，Account Lane
在 Core 内部并行。默认覆盖限价挂单、吃单成交、撤单、部分成交、多 Lane 撮合、风险扫描、强平执行和配对快照恢复：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am clean package
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/linear-perpetual-benchmarks.jar \
  'LinearPerpetualCoreBenchmark.*' -p accountLanes=4,8 -prof gc
```

快速确认 JMH 打包与场景可执行时可缩短迭代；该命令只用于 smoke，不作为容量结论：

```bash
java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/linear-perpetual-benchmarks.jar \
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
JMH 结果只代表本地微基准，Aeron、HTTP/WebSocket、Kafka、故障切换和端到端资金对账仍需独立门禁。

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

matching 使用 `exchange.core2:exchange-core:0.5.16-emporia` 及其 Chronicle/OpenHFT 传递依赖，必须使用
以下 JVM 参数：

```text
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

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

- fork 固定为 `exchange-core 0.5.16-emporia`，源码提交
  `4c4d163b6ba736a43360b325cdd7b9fb8c20648d`，可复现 JAR SHA-256
  `d4ab72853924edc32069ab7158e7bcc5d374ecc1bcd594df04128ab459732b86`；fork 构建拒绝 dirty
  worktree，从已认证提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和内嵌 provenance；
  Aeron service 的 Maven `validate` 阶段同时校验 provenance 与整包 hash。
- 当前唯一写格式为 command/envelope schema v4、Core Export marker v9、`TradingState v24`、sectioned snapshot v14
  和 matcher snapshot v3；所有旧主版本立即 fail closed，没有 legacy reader。sectioned snapshot 按 laneId 升序保存
  4 个实际 Account Lane state section，matcher section 保存 `matchingEngineCount` 个原生 matcher module 加一个 risk module。
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
