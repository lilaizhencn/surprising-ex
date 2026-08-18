# surprising-ex


Surprising-EX 是基于 Java 25、Aeron Cluster、PostgreSQL、Kafka 和 Valkey 的交易所后端。
仓库覆盖现货、永续、交割和欧式现金结算期权四类业务线（六个 `ProductLine` 变体）；每个变体使用独立的
三节点 Aeron Cluster、Topic、投影和账户类型。

本 README 与各模块 README 记录当前架构和验收边界；真实 provider、做市进程和基础设施由部署编排单独管理。

## 已确认架构基线

- 一个 `ProductLine` 变体对应一个逻辑 ProductExecutionCore；逻辑 Core 是一套三 Member Aeron Cluster，
  不是单进程。该 Core 管理本产品线全部 symbol、账户、订单元数据、持仓、风险和生命周期。
- CROSS 与 ISOLATED 都在同一个 Core 内。CROSS 只共享本产品线 Core 内的权益；ISOLATED 绑定 position identity，
  保证金划拨仍由同一 Core 原子完成。产品线之间不共享 live available balance。
- 当前不为热点币对单独部署 Core。协议、路由、事件、指标和 snapshot 只预留
  `coreShardId=default`、`routeVersion=1` 的语义；字段按主规格 W1/W3 版本化落地，完成前拒绝任何非默认路由。
  只有容量门禁证明单 Core 不足后才评审独立风险子账户式分片。
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
- Kafka 保留外部输入缓冲与 WebSocket、K 线、通知、数据仓库等外围事件分发，不恢复核心资金状态。
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
边界约束由对应模块的源码、Maven 测试和主规格维护；canonical 检查脚本按单产品线、资金对账、恢复和
容量职责执行。

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

### 增量 JAR 构建

不要直接执行根目录 `mvn package`。使用 `scripts/build-incremental.sh` 指定修改的模块；脚本会读取 Maven reactor
依赖关系，同时加入所有直接或间接依赖该模块的下游消费者，最后交给 Maven `-am` 补齐必要的上游模块。没有受影响的
模块时不会启动 Maven，也不会删除其他模块的 `target/`。

```bash
# 只改了一个模块，默认跳过测试并生成受影响模块的 JAR
scripts/build-incremental.sh surprising-trading/surprising-command-provider

# 也可以使用 artifactId 或多个模块
scripts/build-incremental.sh :surprising-aeron-client :surprising-aeron-tools

# 从当前工作区变更自动解析模块；文档、脚本、报告和日志不会触发 Maven
scripts/build-incremental.sh --changed

# 先查看计划，不执行构建；需要测试时显式打开
scripts/build-incremental.sh --changed --dry-run
scripts/build-incremental.sh --with-tests :surprising-aeron-service
```

源码或模块 `pom.xml` 变化只会构建影响闭包；根 `pom.xml`、`surprising-parent/pom.xml` 或共享公共 API 变化会安全地
扩大范围，这是依赖传播而不是无条件全量构建。`package` 是默认目标；需要发布到本地仓库时显式使用
`--goal install`。脚本默认 `-DskipTests`，跨账户、撮合、风控或协议变更必须使用 `--with-tests` 执行受影响模块测试。

matching 使用 `exchange.core2:exchange-core:0.5.15-emporia` 及其 Chronicle/OpenHFT 传递依赖，必须使用
以下 JVM 参数：

```text
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

Chronicle 版本由父 POM 的 BOM 统一管理，避免旧版在 JDK 25 中触发 `unmap0`/`Bytes` 初始化错误。
默认合并进程和端口：

| Provider | 端口 |
|---|---:|
| instrument | 9080 |
| market-data（matching projection/candlestick） | 9081 |
| price-provider（index/mark/fx） | 9082 |
| command | 9084 |
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

Topic、端口、磁盘、监控阈值和故障演练的精确清单待生产 Runbook 补充；不得改变上述所有权和恢复边界。

### W1/W2 快照与发布契约

- fork 固定为 `exchange-core 0.5.15-emporia`，源码提交
  `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`，可复现 JAR SHA-256
  `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`；fork 构建拒绝 dirty
  worktree，从已认证提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和内嵌 provenance；
  Aeron service 的 Maven `validate` 阶段同时校验 provenance 与整包 hash。
- `CoreState v6` 同时封装 Core 业务状态和 exchange-core 原生 `ME0/RE0`；`TradingState v19` 不包含盘口。
  恢复只使用 `InitialStateConfiguration.fromSnapshotOnly`，不允许 clean-start、活动订单回放或第二本 FIFO。
- capture 在 symbol-lane barrier 内完成；存在 pending matching 时精确拒绝快照。恢复在开放流量前执行
  CRC32C、产品线/路由、fork/config、注册表、完整引擎哈希、盘口哈希和全部 OPEN 订单逐字段对账。
- v5/v18 未发布兼容层。首次上线采用全体 Member 同版本的新 Cluster；检测到旧状态时只中止，不自动删除。
  v6 接受命令前可回退旧二进制及其保留的旧快照；v6 接受命令后只能保留数据并用上述精确制品向前恢复，
  禁止新旧 Member 混跑或让旧二进制读取 v6/v19。

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
