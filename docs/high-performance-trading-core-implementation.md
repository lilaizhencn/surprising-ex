# Surprising-EX 高性能交易核心最终实施规格

> 状态：`IMPLEMENTATION_SOURCE_OF_TRUTH`
>
> 适用范围：Aeron Cluster、交易核心、exchange-core 0.5.15-emporia、账户资金、订单、风险、触发单、导出、查询、Gateway 和四类业务线（六个 `ProductLine` 变体）。
>
> 目标：单写者、无锁热路径、减少复制、减少往返、内存裁决、高吞吐、可恢复、资金守恒；在线交易运行时不依赖 PostgreSQL。
>
> 基线提交：`221b7f005e75af43f76b19d71abde0b1a053312e`；实施分支：`codex/aeron-unified-core`；本次源码复核提交：`8d81475`。
> 当前文档阶段：`P0-DONE / P1-DONE / P2-DONE / P3-DONE / P4-PARTIAL / P5-PARTIAL / P6-IN_PROGRESS`。W1/W2 已完成：exchange-core 是唯一可执行盘口，Core 只保存订单业务元数据和必要索引，恢复只导入 Aeron 配对的原生 matcher snapshot。P5 的导出/投影故障门禁已完成，但 Gateway Auth Cluster 和在线 Provider 无数据库启动出口尚未完成，因此阶段总状态按完整目标回退为 `PARTIAL`。
> 更新时间：2026-08-18

本文档是本轮源码审计、前序 Aeron 统一交易核心方案、exchange-core 单一盘口方案和本项目当前实现的合并实施规格。实现、代码审查、压测和上线门禁均以本文档为准。历史文档如果被删除，不代表其中的约束和验收项被删除；它们已经在本文档中合并保留。

## 1. 最终结论

交易裁决必须收敛为一条产品线内的一个确定性内存核心：

```text
HTTP/WebSocket/Gateway
        |
        v
稳定 source lane -> Aeron Cluster ingress
        |
        v
ProductExecutionCore（单写者、无锁、内存状态）
        |
        +-- R1：校验、幂等、资金/保证金预留
        |
        +-- exchange-core 0.5.15-emporia：唯一价格时间优先订单簿和撮合
        |
        +-- R2：成交、手续费、余额、持仓、风险和生命周期结算
        |
        +-- CommandDelta：权威响应、滚动哈希和复制导出事实
        |
        +--> Aeron 响应（一次命令往返）
        +--> Aeron subscription -> WebSocket、行情和只读查询索引
        +--> replicated outbox -> Audit Exporter -> Kafka
                                             +--> History Projector -> PostgreSQL
```

PostgreSQL、Redis、Kafka、HTTP、外部价格服务和系统时钟都不能参与下单、撤单、改单、撮合、资金预留、成交结算、强平和触发单执行的同步裁决。预先导入 Instrument 版本后，交易进程必须在审计 PostgreSQL、Kafka 和 Valkey 全部不可用时继续完成交易、查询、风控和恢复。交易状态只存在于 Aeron Cluster 的内存状态、Cluster Log、Archive 和快照中。Instrument 保持既有 PostgreSQL 管理逻辑；历史 PostgreSQL 只能由 Kafka projector 异步写入。

### 1.0 交易主链路数据库零依赖边界（强约束）

> **本次决策修订（2026-08-18）**：目标是交易命令和当前态查询不依赖数据库，不是删除 Instrument 的 PostgreSQL。当前代码尚未完全达到该目标；以下边界是必须完成的目标状态，不应将现有 W4 验证误称为脱库门禁。详见 `docs/adr/0001-aeron-authoritative-without-database.md`。

“交易主链路不依赖数据库”不是把 JDBC 查询从热路径挪到 Provider，而是交易命令完成条件不得包含数据库读写：

| 范围 | 权威来源 | PostgreSQL 依赖 |
| --- | --- | --- |
| 余额、冻结、持仓、活动订单、未成交单 | ProductExecutionCore 状态机 | 禁止 |
| 风控、标记价、强平、ADL、保险、资金费、交割/行权 | 同一 ProductExecutionCore 事件与状态 | 禁止 |
| 订单/持仓/资金查询、撤单、止盈止损操作 | Aeron Query/Command + 内存只读索引 | 禁止 |
| 用户和会话 | Gateway Auth Cluster 的状态快照；访问令牌使用签名 JWT | 禁止 |
| instrument 配置与生命周期管理 | Instrument 服务 + PostgreSQL；版本化 Aeron command 导入 Core | 保持既有逻辑；不参与单笔交易裁决 |
| 事件回放、崩溃恢复 | Aeron Cluster Log + Archive + snapshot manifest | 禁止 |
| 历史、报表、审计、对账导出 | Core outbox -> Kafka exporter -> Kafka projector | 仅 projector 异步写入 |

因此，所有参与订单、资金、风险和生命周期裁决的 `JdbcTemplate`、JPA、数据库 lease/sequence/projection repository 必须从交易 Provider 的构造图中移除。Instrument 数据访问保持不变。需要历史数据的接口读取 PostgreSQL 投影，但写入只能发生在独立 projector；Exporter 本身不持有 DataSource。

实施顺序必须先完成运行时脱库，再做压测：

1. 为每个 ProductLine 建立 Cluster 内的 funding、insurance、liquidation、ADL、trigger 和 account state command/query；Provider 只做调度、协议转换和订阅。
2. 将余额、持仓、活动订单、未完成触发单和生命周期进度从 JDBC repository 迁移到 Core snapshot 状态，并为每类状态增加恢复后的 invariant 校验。
3. 将 Risk、Maker、Funding、Insurance、Liquidation 的交易运行配置改为 `DB_REQUIRED=false`；Instrument 的数据库连接 Bean 保持不变。
4. Exporter 只发布 Kafka，独立 projector 消费 Kafka 并幂等写 PostgreSQL，禁止在线请求同步调用两者。
5. 预先完成 Instrument 初始化后，停止审计 PostgreSQL、Kafka、Valkey，执行六条产品线的资金、订单、风控、强平和恢复门禁；通过后才开始单产品线压测。

exchange-core 是唯一盘口权威。项目不得再维护一份价格桶、FIFO、剩余量排序或第二本可执行 book。外层核心只保留订单业务元数据、资金预留、必要的活动订单索引和恢复校验信息。

### 1.1 已确认部署拓扑

当前版本按“一个 `ProductLine` 变体一个逻辑 Core”设计。一个逻辑 Core 不是一个 JVM，而是一套三 Member
Aeron Cluster 状态机；三个 Member 各自运行确定性副本，只有 Leader 接收入站命令，Cluster Log 决定顺序。
该 Core 内包含本产品线全部 symbol、账户资金、订单元数据、持仓、风险、Treasury、生命周期状态和一个
exchange-core matcher 副本。六个部署单元固定为：

| 逻辑 Core | 包含范围 | 必须隔离的状态 |
| --- | --- | --- |
| `SPOT` | 全部现货 symbol | 现货账户、base/quote 冻结、book、topic、snapshot |
| `LINEAR_PERPETUAL` | 全部 U 本位永续 symbol | 全仓/逐仓、资金费、强平、ADL、保险基金 |
| `INVERSE_PERPETUAL` | 全部币本位永续 symbol | 币本位保证金、资金费、强平、ADL、保险基金 |
| `LINEAR_DELIVERY` | 全部 U 本位交割合约 | 全仓/逐仓、到期交割、结算进度 |
| `INVERSE_DELIVERY` | 全部币本位交割合约 | 币本位保证金、到期交割、结算进度 |
| `OPTION` | 全部期权合约 | 权利金、买卖方权益、行权、到期失效 |

目标协议、路由、snapshot manifest、事件和指标必须携带 `coreShardId=default` 与 `routeVersion=1`，但它们
只是兼容未来迁移的身份字段，不能据此创建第二个生产 Core。当前 wire schema 尚未增加字段时只允许隐式
`default/v1`，任何非默认路由配置都必须拒绝；W1 先落 manifest，W3 再版本化 command/event wire。当前不为
热点币对单独部署 Core，也不实现跨 Core 资金预留、撮合或风险事务。只有单 Core 的持续容量证据触及第 15.5 节阈值后，才重新评审分片。

### 1.2 全仓与逐仓边界

全仓和逐仓不改变当前部署拓扑，二者都在同一 ProductLine Core 内裁决：

- `CROSS` 的风险域覆盖同一用户在该 ProductLine Core 内的可用抵押资产和全部全仓持仓；不跨产品线、不跨 Core 共享可用余额。
- `ISOLATED` 的保证金、盈亏、维持保证金和强平状态绑定到明确的 position identity；初始划拨和追加/减少保证金仍由同一 Core 从用户余额原子完成。
- margin mode、symbol、position side 必须进入订单元数据、持仓键、幂等指纹、CommandDelta、snapshot 和恢复校验；模式冲突必须在 matcher 之前 fail-closed。
- 资金费、强平、ADL、保险基金、交割和行权仍由本 Core 基于相同资金与持仓状态原子推进，Risk Provider 不能旁路重算或执行。

未来若把热点 symbol 迁到独立 Core，必须先把该 shard 设计为独立风险子账户并划拨专属抵押资产；两个 Core
不得实时共享一份全仓 available balance。需要跨 Core 共享全仓权益时必须另立中央账户/风险权威并引入
两阶段预留和成交确认协议，这不属于当前方案，也不作为当前高频扩容路径。

## 2. 不变量和不可妥协项

### 2.1 交易顺序

1. 同一产品线同一 Aeron Cluster 内的命令按 Cluster position 顺序裁决。
2. 同一用户的余额、冻结、持仓、订单和 clientOrderId 必须按同一单写上下文修改。
3. 同一 symbol 的订单必须由 exchange-core 按价格时间优先顺序处理。
4. 不同用户、不同 symbol、不同产品线只能通过分区并行，不能共享可变状态或全局锁。

### 2.2 资金守恒

所有金额、数量、手续费和保证金在边界统一为定点整数，使用 `Math.*Exact`。任何异常、溢出、索引缺失、导出容量不足或 matcher 状态不一致均必须在资金或盘口变化前失败关闭。不得 catch 后继续，不得以数据库查询或清空 book 掩盖不变量异常。

每条资金命令必须满足：

```text
期初总额 + 充值/调整 + 成交现金流 + 手续费 + 资金费
       + 强平/保险/ADL + 交割/行权 = 期末总额
available + locked = accountTotal
```

四类业务线（六个 `ProductLine` 变体）边界不能混用：

| 产品线 | 必须独立的内容 |
| --- | --- |
| SPOT | base/quote 双资产冻结、成交扣减、解冻、手续费 |
| LINEAR_PERPETUAL | U 本位保证金、资金费、标记价、强平、ADL、保险基金 |
| INVERSE_PERPETUAL | 币本位保证金、资金费、标记价、强平、ADL、保险基金 |
| LINEAR/INVERSE_DELIVERY、OPTION | 到期结算、交割、权利金、行权、失效、持仓归零和对手方权益 |

每条产品线拥有独立 topic、consumer group、状态目录、快照、恢复水位、风险模型和 instrument 版本边界。

### 2.3 幂等和身份

- `commandId` 是一次业务意图的稳定幂等键。
- `sourceId + sourceEpoch + sourceSequence` 是入口顺序和重放边界。
- `orderId`、`clientOrderId`、`tradeId`、`exportSequence` 必须唯一且可恢复。
- 同一命令重复提交只能返回原裁决，不得再次预留、撮合、扣款或发布成交。
- 幂等结果必须有界但不能静默丢失：窗口外重复只能走显式状态查询/重试协议，不能当新命令。
- source sequence registry 必须有确定性租约/epoch 清理命令，不能无限增长。
- 订单终态主体和 clientOrderId tombstone 在其导出事实仍可能被重放期间不得删除；只有对应导出 ACK 后才允许清理零余额 reservation，主体历史使用明确的 retention/tombstone 规则收口。

## 3. 基线问题和当前处置状态

以下清单保留改造前的完整问题，便于后续阶段继续追踪。W1/W2 已将 exchange-core 升级并固定为
`0.5.15-emporia`，删除第二本 FIFO 和生产逐单恢复；其余 P4-P6 项仍按状态表推进。

### 3.1 状态复制、构造和历史增长

| 编号 | 当前问题 | 位置/机制 | 影响 |
| --- | --- | --- | --- |
| S01 | Delta map 经过深度上限后 materialize | `StateMapSupport.delta` | 长链变成整图复制和 GC 峰值 |
| S02 | `TradingCoreState` canonical constructor 对非 Delta users/orders 全量排序、校验 | `TradingCoreState` 构造器 | 每命令 O(U+O)，并非真正 O(delta) |
| S03 | `clientOrderIndex == null` 时重新遍历全部订单构建索引 | `TradingCoreState` 构造器、`replaceUser`/`adjustBalance` 调用链 | 与业务无关的命令也变成 O(O) |
| S04 | timers、triggers、algo map 每次构造仍全量排序/校验 | `TradingCoreState` 构造器 | 触发/定时命令放大分配 |
| S05 | `stampOrderChanges` 未知变更集时复制并比较全部 orders | `TradingCoreState.stampOrderChanges` | commit metadata 产生二次 O(O) |
| S06 | 用户内部 balances/reservations/positions 以及 book/risk/treasury 嵌套对象反复复制 | 各 immutable record 构造器 | 小改动变成多层对象树复制 |
| S07 | 终态 order、algo、trigger、liquidation 主体长期留在热 map | `TradingCoreState` | 历史增长导致 snapshot、hash、查询持续变慢 |
| S08 | W1/W2 前 `CoreBookState` 与 exchange-core 同时保存活动盘口 | 已删除 `CoreBookState`、priority codec/hash 和所有生产引用 | `DONE`：exchange-core 是唯一 FIFO/book 权威 |
| S09 | applyMatches 在当前代码已使用 Delta，但构造器/索引/验证仍会触发全量工作 | `TradingCoreReducer.applyMatches` + `TradingCoreState` | 表面修复不等于端到端 O(delta) |

### 3.2 隐藏全量扫描

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| Q01 | `upsertTriggerOrder` 扫全部 trigger 查 client ID（基线问题；当前已由 `TriggerOrderIndex` 消除） | 触发单规模增大后下单成本线性增长 |
| Q02 | `upsertAlgoOrder` 扫全部 algo 查 client ID | 同上 |
| Q03 | `ensureLiquidation` 扫全部 liquidation 查用户/symbol/side | 强平计划重复检查变慢 |
| Q04 | `adlCandidates` 扫全部 users/positions 并排序 | ADL 命令阻塞整条产品线 |
| Q05 | cancel-all 先扫描 book/order 再逐单完整 cancel | O(K×O) 级联放大 |
| Q06 | risk/funding/settlement continuation 依赖全量 values 列表 | 批量长任务可能接近 O(N²) |
| Q07 | open-orders、trigger、algo、timer 查询在 Core 内扫描并排序业务全集 | 查询抢占写者，写延迟抖动 |
| Q08 | `BOOK_STATE_QUERY` 请求 `Integer.MAX_VALUE` 深度并排序所有 symbol/level | 管理查询可造成巨大分配和长停顿 |

### 3.3 哈希、导出和快照

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| H01 | `businessStateHash()` 保留全量重建入口 | 每次误用即 O(U+O+book+risk+treasury) |
| H02 | export 通过 `changed*IdsSince` 反推变化，变更集合缺失时扫描全部实体 | 已知 delta 被重复发现 |
| H03 | `stateHash()` 还要叠加 commandResults、export 状态 | 查询/导出命令污染热路径 |
| H04 | export event 编码、digest、batch encode 存在重复工作 | CPU 和 byte[] 分配浪费 |
| H05 | pending export queue 在 Core 状态内，满容量才拒绝 | 队列大时 snapshot 和复制变重；容量判断必须在变更前完成 |
| H06 | snapshot 需要编码完整活动状态及 pending export | 大状态 snapshot 造成核心停顿，需要分片/低频处理 |

### 3.4 matcher、Aeron 和线程

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| M01 | place/cancel/replace 调用 `submitCommandAsyncFullResponse(...).join()` | 每命令同步跨 ring 等待，无法形成微批重叠 |
| M02 | W1/W2 前 cancel/replace 失败会触发 `matchingAdapter.rebuild(before)` | `DONE`：生产 rebuild 已删除，异常进入 sticky fail-closed |
| M03 | matcher 已变更后业务应用异常可能造成状态分叉 | `DONE`：该成员立即关闭并拒绝继续裁决，由一致快照/Cluster 恢复 |
| M04 | W1/W2 前恢复按 open orders 逐单重放 | `DONE`：恢复使用 `fromSnapshotOnly` 导入原生 ME0/RE0 |
| M05 | `orderBookLevels()` 查询所有 symbol、最大深度、逐个 join | 管理查询阻塞 matcher |
| M06 | `ensureSymbol` 使用哈希但不检查碰撞（当前已增加稳定 registry 和确定性碰撞探测） | 碰撞会把不同 symbol 混入同一盘口 |
| M07 | `ensureUser`/symbol 注册在首次交易同步 join | 首次请求有额外固定尾延迟 |
| M08 | `AeronClientPool.commandAsync` 每命令创建 CompletableFuture 任务；slot 内仍同步等待 | 高频并发分配和线程池竞争 |
| M09 | `SurprisingAeronClient.submit` offer/egress 是调用线程同步循环 | Gateway 线程被核心背压占用 |
| M10 | sourceId 含进程 epoch，重启后身份改变 | 重放/重试的 sequence 语义不稳定 |
| M11 | 同 user 的命令可能跨多个 client slot | 同用户顺序依赖上层运气 |
| M12 | batch API 逐条调用同步单命令 | N 个命令变 N 次往返，无法原子微批 |

### 3.5 数据库、触发单和外部边界

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| D01 | 触发单 API、价格触发和生命周期已统一通过 Aeron Core | Provider 不再有 JDBC/Redis/Kafka 第二裁决源 |
| D02 | Core 在同一有序状态机内校验价格、创建 reduce-only 子订单并撮合 | 避免跨服务 claim/execute 往返和竞态 |
| D03 | trading provider 只保留 Core 查询、命令转发和有界维护 | 数据库故障不改变在线触发裁决 |
| D04 | `OrderFeeSnapshotLookup` 缺失用户费率时回退 instrument default | 可能产生错误手续费和资金对账差异 |
| D05 | mark price、instrument、fee snapshot 如果陈旧/缺失没有严格版本门禁 | 交易规则不一致 |
| D06 | 订单号生成器仅内存 AtomicReference，跨重启无持久 epoch/租约 | 节点重启或多实例配置错误可能冲突 |
| D07 | 核心结果窗口只有 128，超窗重复请求的语义不够明确 | 客户端可能误把结果未知当失败/新命令 |
| D08 | Export backlog 达上限后整条产品线拒绝命令 | 正确但需要明确告警、drain 和恢复协议，不能无限膨胀 |

### 3.6 首轮性能热点逐项追踪

下面的表保留最初源码审计中已经确认的每一个热点，不以归并编号替代原始问题。`IMPLEMENTED` 表示热路径已满足目标，允许在 snapshot、恢复、审计或兼容构造器中保留冷路径遍历；`PARTIAL` 只表示仍有明确的热路径/状态容器优化遗留。后续实现必须在“状态”列写入证据，不能只把它标记为“已有优化”。

| 原始热点 | 归并问题 | 目标方案 | 当前状态 |
| --- | --- | --- | --- |
| `TradingCoreReducer.java:478` 每次下单复制完整 `orders` | S01/S06/S09 | `TradingCoreRuntime` 只修改变更订单，`CommandDelta` 携带 changed order IDs；禁止全量 map 复制 | `PARTIAL`：热路径已使用 persistent `DeltaMap`，但仍保留 immutable state shell；后续可再评估 mutable entity store |
| `TradingCoreReducer.java:1492` 修改用户复制完整 `users` | S06 | 单写 runtime 的用户实体/分片 mutable store，余额、持仓只更新 affected user | `PARTIAL`：用户及其 balances/reservations/positions 仍是 immutable record + delta map，未完成 mutable entity store |
| `TradingCoreState.java:30` 新状态复制并排序全部 users/orders | S02 | canonical constructor 不再负责热路径全量排序；全量排序只允许 snapshot/audit | `IMPLEMENTED`：权威 transition 使用 delta 只校验 changed keys；完整排序仅发生在非 delta 的冷路径 |
| `TradingCoreState.java:43` 构造时遍历订单重建 `clientOrderIndex` | S03 | index 由命令显式增量维护，缺失时 fail-closed，不隐式扫描 | `IMPLEMENTED`：权威 transition 缺失 index 直接拒绝；兼容构造器的派生只属于冷路径 |
| `TradingCoreReducer.java:534` 无成交的 `applyMatches` 仍复制 users/orders/book | S09 | matcher result 为空时只提交订单状态和 delta，不复制无关实体；业务状态与唯一 matcher 同一 transition | `IMPLEMENTED`：非即时空成交直接复用原状态，其余路径使用 delta；exchange-core 是唯一可执行 book |
| `TradingCoreState.java:156` 已应用订单再次遍历全部 orders 写提交元数据 | S05/H02 | `CommandDelta` 在 mutation 时记录 changed entities，stamp/export/response 复用同一事实 | `IMPLEMENTED`：`stampOrderChanges` 的权威调用传入 changed order IDs，空集合不扫描订单 |
| `CoreProbeState.java:416` Export 前扫描全部 users/orders 计算变更集合 | H02 | export 只消费 `CoreCommandDelta`，变更集合缺失时直接拒绝提交 | `IMPLEMENTED`：export 直接消费 command accumulators/delta，缺失 changed IDs fail-closed |
| `TradingCoreState.java:169` `businessStateHash()` 全量遍历业务状态 | H01 | 热路径使用增量 rolling hash；全量 hash 改名并限制在 snapshot/replay/audit | `IMPLEMENTED`：热路径使用 `RollingBusinessStateHash`；`fullBusinessStateHash()` 仅保留为包内冷路径校验入口 |
| `CoreProbeState.java:441` `stateHash()` 再次调用 business hash | H03 | 业务 hash 与完整复制状态 hash 分离；完整 hash 可包含 export/idempotency 元数据，查询不得改变 hash | `IMPLEMENTED`：`stateHash()` 使用缓存的 rolling business hash，查询不重算也不修改状态；export/idempotency 字段属于完整副本一致性 hash |
| `DeterministicExchangeCoreAdapter.java` 同步 facade / `submitCommandAsyncFullResponse(...).join()` | M01 | 只保留结构化异步结果和 continuation；调用层不等待 ring future | `IMPLEMENTED`，Core owner 由 timer continuation 按序接收结果；恢复与离线工具同样使用显式 drain |
| `CoreBookState` 与 exchange-core 同时保存活动盘口 | S08/M02 | exchange-core 是唯一可执行 book；外层只保留业务活动订单索引和恢复校验 | `IMPLEMENTED`，`CoreBookState` 与生产 replay/rebuild 已删除 |
| Risk Provider 与 Core 各自维护预警/强平阈值 | D05/H01 | Instrument 参数进入 Core；Risk 只展示 Core snapshot；动态阈值必须是版本化 Core RiskPolicy | `IMPLEMENTED`，当前 Core policy version 1 |
| `RiskLimitBracket.maintenanceMarginRatePpm` 已存储但未用于实时计算 | D05 | 以当前名义价值选择 bracket 的 maintenance rate，边界和超限 fail-closed | `IMPLEMENTED` |

### 3.7 历史迁移方案的不可丢失约束

前序迁移方案中的以下决策仍然有效，并已按当前 `exchange-core:0.5.15-emporia`、四条业务线和内存交易链路更新措辞：

1. Aeron Cluster Log/Archive/Snapshot 是交易核心唯一恢复权威；Kafka 仍承担外围事件和异步输入，不替代 Aeron。
2. PostgreSQL 不参与下单、撮合、资金预留、成交、风险、强平、交割或行权同步裁决，只做投影、历史、审计和对账。
3. Redis/Valkey 不保存可裁决的资金、订单、持仓、风险或强平状态；最多做限流、查询缓存和可重建会话索引。
4. 不做长期影子集群、不做双写；正确性和恢复门禁通过后直接以当前权威链路做性能压测。
5. 每次只验证一条产品线，做市进程保持运行；开发机和微基准不得推导生产 OPS。
6. 命令必须使用固定二进制协议和可演进 schema；`commandId` 重试保持不变，超时是结果未知，不是业务拒绝。
7. Exporter 允许 Kafka 成功而 Aeron ACK 丢失导致的重复事件，消费者必须幂等；不声称跨 Aeron/Kafka/PG exactly-once。
8. 所有长任务（风险扫描、资金费、交割、行权、强平、ADL）必须有最大工作量、确定性 cursor、可暂停续跑和幂等命令。
9. 保险基金/ADL 第一版可以保留外围审计模块，但任何用户资金变化必须通过 Core 命令执行；不能由外围数据库或队列直接改资金。
10. 撮合固定使用 exchange-core 0.5.15-emporia；不包装成第二本盘口，`GTX` 使用其原生 post-only 语义。

## 4. 目标状态和所有权

### 4.1 Instrument 唯一参数来源

保证金和持仓风险参数只有一个业务来源：Instrument Provider。Instrument Provider 可以使用数据库保存
配置和版本历史，但数据库中的值必须通过版本化 `UpsertInstrumentCommand` 进入 Core；Core 不在热路径
读取 Instrument Provider、Risk Provider 或 PostgreSQL。

```text
Instrument Provider
  -> versioned UpsertInstrumentCommand(symbol, instrumentVersion, ...)
  -> Aeron Core CoreInstrumentState
  -> CoreContractMath / TradingCoreReducer
       -> order reservation and opening margin
       -> position maintenance margin and risk ratio
       -> liquidation / ADL eligibility
  -> CoreRiskSnapshot / Core query response
  -> Risk Provider display-only query
```

Instrument 版本必须完整携带并在 Core snapshot/hash 中保留以下参数：

| 参数 | 唯一来源 | Core 使用位置 | Risk Provider 规则 |
| --- | --- | --- | --- |
| `initialMarginRatePpm` | Instrument Provider | 开仓保证金默认值/最低边界 | 不读取后重算 |
| `maintenanceMarginRatePpm` | Instrument Provider | 无 bracket 或 spot 默认维持保证金 | 不读取后重算 |
| `riskLimitBrackets.initialMarginRatePpm` | Instrument Provider | 按结果名义价值选择开仓档位 | 只展示 Core 结果 |
| `riskLimitBrackets.maintenanceMarginRatePpm` | Instrument Provider | 按 mark price 下的当前名义价值选择维持档位 | 只展示 Core 结果 |
| `maxLeveragePpm` | Instrument Provider | 下单杠杆上限和 bracket 杠杆门禁 | 不维护副本 |
| `maxPositionNotionalUnits` | Instrument Provider | projected position limit | 不维护副本 |
| 费率、乘数、tick、settle scale、expiry | Instrument Provider | 成交、手续费、生命周期和数学 | 不维护副本 |

Core 负责参数版本门禁、档位连续性、最大杠杆和最大名义价值覆盖校验。Risk Provider 的账户比例可以
做跨仓位的展示聚合，但只能使用 Core 返回的 wallet、equity、maintenance 和 `status`；它不得根据
自己的阈值改变 `NORMAL/WARNING/LIQUIDATION`。当前 `CoreRiskPolicy.VERSION=1` 是 Core 固定代码策略；
若以后需要动态预警/强平阈值，必须增加版本化 Core `RiskPolicy` 状态和命令，并与快照、hash、重放
一起原子切换。

### 4.2 运行时所有权

| 状态 | 唯一权威 | 热路径保存内容 |
| --- | --- | --- |
| 价格、数量、FIFO、盘口深度 | exchange-core | matcher 原生状态；不复制价格树 |
| 订单业务元数据 | ProductExecutionCore | user/symbol/client ID、订单状态、预留、产品字段 |
| 余额、冻结、持仓、保证金 | ProductExecutionCore | 单写、定点整数、增量更新 |
| trigger/algo/timer | ProductExecutionCore | 主体 + `byClientId/bySymbol/byDueTime` 索引 |
| risk/funding/settlement | ProductExecutionCore | cursor、affected-user index、批次进度 |
| 结果和导出 | replicated CommandDelta/outbox | 一次编码、单调 export sequence、有界 pending |
| 历史订单、成交、账单 | Kafka/JDBC projection | 可重建、异步、按 sequence 幂等 |
| HA、顺序、恢复 | Aeron Cluster Log/Archive/Snapshot | 唯一恢复链 |

目标核心对象不是每次构造新的全量 immutable graph，而是 ClusteredService 拥有的 `TradingCoreRuntime`：

```text
TradingCoreRuntime（只由 service 线程访问）
  usersById / ordersById / balances / positions
  activeOrderIndex / clientOrderIndex
  triggerById + triggerByClient + triggerDueIndex
  risk affected-user index + continuation cursors
  exchangeCore adapter（唯一 book）
  rollingHash + CommandDelta builder
```

Aeron 快照时将 runtime 按稳定顺序编码为 snapshot；正常命令只修改本次涉及的实体并记录 changed IDs。不得用 `ConcurrentHashMap` 冒充无锁，不得让后台线程直接修改 runtime。

## 5. 单命令执行协议

```text
Decode DirectBuffer
  -> productLine / source / command id validation
  -> idempotency lookup
  -> static instrument/fee/risk validation
  -> response/export capacity check
  -> R1 reserve user funds/margin
  -> exchange-core one command (GTC/IOC/FOK/GTX native)
  -> R2 consume fills and update maker/taker state
  -> lifecycle/risk/treasury updates
  -> rolling hash and CommandDelta commit
  -> response + replicated export event
```

规则：

1. 所有可预测拒绝在 matcher 修改前完成。
2. matcher 只接受已经通过资金预留和 instrument 版本校验的命令。
3. `GTX` 必须映射到 exchange-core 原生 `OrderType.GTX`，不允许先查盘口再模拟 post-only。
4. matcher 结果和业务状态必须在同一 Core 命令中应用。
5. matcher 已提交后若业务 reducer 或 delta 编码异常，当前成员必须进入 sticky fail-closed 并停止裁决；恢复只能从同一水位的 Aeron Core + matcher 配对快照和后续 Cluster Log 开始，禁止进程内 rebuild、retry 或 resubmit。
6. 响应直接携带 changed orders、executions、changed users 和 `coreSequence`，成功命令不再二次查询。

## 6. 状态增量化方案

### 6.1 CommandDelta

每条命令构造一个不可变 `CommandDelta`：

```text
coreSequence
commandId
resultCode
changedUserIds / changedUsers
changedOrderIds / changedOrders
executions
positionChanges
treasuryChanges
liquidationChanges
triggerChanges
exportSequence
deltaDigest
```

reducer 返回 `(runtime, delta)` 或直接在 runtime 上修改并由 `DeltaBuilder` 记录。export、response、rolling hash、position index 和 WebSocket 事件均消费同一个 delta，禁止提交后再次 before/after 全量 diff。

### 6.2 索引

所有高频查询/业务校验必须有增量索引：

- `(userId, clientOrderId) -> orderId`
- `userId -> active order IDs`
- `symbol -> active order IDs`（仅业务定位，不存盘口排序）
- `(userId, symbol, positionSide) -> position`
- `(symbol, position side) -> affected user IDs`
- `(userId, clientTriggerId) -> triggerId`
- `(symbol, due condition) -> trigger IDs`
- `userId/symbol -> algo/timer IDs`
- liquidation `(user,symbol,side) -> active plan`

索引更新和主体更新必须在同一命令内完成。索引缺失不得扫描全表；只能返回明确的 `CORE_INDEX_NOT_READY` 并触发恢复/重建流程。

### 6.3 哈希

热路径只维护：

```text
rollingHash = H(previousHash, coreSequence, commandDigest, deltaDigest)
```

全量 business hash 只用于 snapshot、离线回放和低频审计。若必须计算全量 hash，必须显式命名为 `fullBusinessStateHash`，不能从普通命令或普通查询隐式调用。

## 7. exchange-core 0.5.15-emporia 适配

1. 继续使用当前 fork，不自研撮合。父 POM 固定 `0.5.15-emporia`、仓库、完整 Git SHA、JDK 25 和整包 SHA-256；fork 从已认证提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和 JAR 内 provenance；service 的 Maven `validate` 同时校验依赖 JAR 整包 hash 与 JAR 内 provenance，无法证明来源的同版本制品不得进入生产。
2. LIMIT `GTC/IOC/FOK/GTX` 直接映射原生类型；MARKET 映射受保护的 IOC/FOK。`GTX` 的拒绝必须由 matcher 原子保证，外层不得 `requestOrderBook + place`。
3. symbol/user ID 使用稳定显式 registry，配置阶段完成碰撞校验；registry version/hash 进入 Core snapshot 和 matcher manifest，恢复后接受交易前必须完全一致。
4. adapter 只暴露结构化异步 place/cancel/replace/batch 和有界 book query；正常调用、恢复和离线工具都不能逐条 `join()`，不能用字符串推断结果。
5. 启动阶段固定 matcher/risk engine 数量和 wait strategy，参数进入 manifest；同一 symbol 的 FIFO 不能靠增加 engine 并行化。
6. 在 fork 的 `ISerializationProcessor` 扩展点实现 Aeron 托管的 `AeronMatchingSerializationProcessor`。它把 `ApiPersistState` 产生的 matching/risk module snapshot bytes 交给 Aeron snapshot writer/reader，不把节点本地磁盘文件当共享恢复权威。
7. Aeron snapshot 开始前建立全局 matcher barrier：停止新命令 admission，等待所有不大于 `matcherSequence` 的异步结果完成，冻结 Core runtime，触发 `ApiPersistState`，最后把 Core 状态、matcher blobs 和同一 manifest 写入一个配对快照。
8. manifest 至少包含 `schemaVersion`、`productLine`、`coreShardId`、`routeVersion`、`coreSequence`、`matcherSequence`、symbol/user registry hash、instrument/risk-policy version、Core rolling hash、规范化 book hash、fork Git SHA、matcher 配置和每个 blob 的长度/checksum。
9. 恢复必须使用 fork 的 snapshot-only 初始化能力（`InitialStateConfiguration.fromSnapshotOnly` 或等价受测入口）加载 matcher bytes，禁止在正常 HA 恢复中 `cleanStart` 后逐单 place。Core 状态与 matcher 恢复到同一水位并通过 open-order set、book hash、资金和索引校验后才设置 `ready=true`。
10. exchange-core command journal 保持关闭；Aeron Cluster Log/Archive 是 snapshot 水位之后的唯一命令恢复链。matcher snapshot 只是 Aeron 业务快照中的组成部分，不形成第二套独立提交或 fsync 权威。
11. `TradingCoreState` 已删除 `CoreBookState`、priority map/hash/codec 和 adapter 的逐单 `rebuild` 生产路径。Core 只保留活动订单业务元数据和 `ActiveOrderIndex`，不保留 FIFO priority sequence。
12. v6/v19 是未发布格式上的一次性不兼容升级，不提供在线双读或自动转换。首次上线必须创建全新 Cluster；旧二进制和旧 Archive 只保留离线诊断，生产故障恢复绝不退回 O(活动订单数) 命令回放。

adapter 必须保持 `RiskProcessingMode.MATCHING_ONLY` 和禁用 exchange-core margin trading。exchange-core 的
user/symbol registry 及随引擎存在的 risk module 都只是完成 matcher 命令所需的技术状态，业务余额、持仓、
保证金、强平和结算仍只以 ProductExecutionCore 为权威；这些技术 module 必须随 matcher snapshot 一起恢复，
但不得作为业务资金查询、导出或对账来源。

这里消除的是 O(活动订单数) 次命令提交、future、校验和对象分配；任何完整 book 的 snapshot 读取仍然至少
需要 O(snapshot bytes)，不能宣称恢复复杂度与活动订单数完全无关。验收关注的是有界顺序恢复时间、无第二本
FIFO 和尾延迟，而不是用不真实的 O(1) 描述替代测量。

## 8. 触发单和数据库边界

触发单必须改为 Core 内原子流程：

```text
MARK_PRICE_UPDATE(symbol, price, priceSequence)
  -> Core 根据 symbol/due index 找到命中 trigger
  -> 原子将 trigger 状态从 OPEN 改为 CLAIMED/EXECUTING
  -> 在同一命令内校验 instrument、费率、余额和订单意图
  -> 调用 exchange-core place（或生成下一条确定性内部命令）
  -> 写入 order/trigger/execution delta
  -> COMPLETE_TRIGGER 只允许匹配相同 claim token 和 core sequence
```

禁止：Core/mark-price 先查 DB，再 DB claim，再调用 OrderService，再 DB complete；禁止同一个触发单既由 DB 又由 Core 维护状态。

数据库仅异步保存 trigger history、审计和查询投影。维护任务、position closed、expire/reset、claim/complete 都改为 Core continuation command；数据库失败不能改变触发单裁决结果。

## 9. Gateway、Aeron client 和并发

- 一个进程共享 MediaDriver，client pool 只管理 session，不为每个 slot 启动独立 driver。
- 按 `productLine:userId` 固定 lane；同一 user 永不 round-robin 到不同在途 session。
- sourceId 是稳定配置身份，不包含每次重启随机 epoch；重启通过显式 `sourceEpoch` 注册/租约命令切换。
- offer 负值分类为 backpressure/closed/admin action；有界重试，不能无限循环。
- egress 使用单 dispatcher 将 response 按 correlation 分发，避免每命令创建线程和 `CompletableFuture.supplyAsync`。
- slot 饱和时立即返回有界 `CLIENT_BACKPRESSURED`，不要无限 park 或占用 HTTP worker。
- `commandAsync` 仅作为边界适配，不得在热路径生成额外线程任务。
- batch place/cancel/amend 使用协议级批量命令或核心微批，不能循环 N 次同步 command。
- 结果未知时必须携带原 `commandId` 重试或查询，不能把超时伪装为业务拒绝。

## 10. 导出、查询和投影

### 10.1 Exporter

Core 提交后把已编码的 CommandDelta 放入 replicated outbox。导出器按 `ack + 1` 批量读取，Kafka 使用 `(productLine, exportSequence)` 幂等键，只有整个 batch 发布成功才发送 ACK。ACK 由 Core 校验不能超前，队列同时受事件数和字节数限制。

队列满时必须在 R1 之前返回 `EXPORT_BACKLOG_FULL`；不得成交后发现不能导出再回滚。Exporter 不能周期性每 10ms 向 Core 发 status 查询，改为事件驱动、阈值告警和明确 drain。

### 10.2 查询

普通查询走异步投影或有界本地 cache，不进入核心写命令通道。需要 read-your-write 时携带 `coreSequence`，等待投影追平；低频管理员审计才允许查询 Core。每个查询必须有 limit、cursor、symbol/user 过滤和最大响应字节数。

### 10.3 PostgreSQL

PostgreSQL 负责历史订单、成交、账单、报表、审计和对账。投影按 export sequence 幂等，允许重放，不反向驱动 Core。普通下单、撤单、改单、撮合、资金和 trigger 不开 SQL 事务、不 `SELECT FOR UPDATE`、不依赖数据库 sequence。

## 11. 快照、恢复和故障语义

快照保存：

- 活动用户资金/持仓/风险状态。
- 活动订单业务元数据和 clientOrderId tombstone。
- exchange-core matching/risk module 原生 snapshot blobs；Core 不再编码 FIFO/价格树副本。
- trigger/algo/timer/liquidation cursor/index。
- `coreSequence`、rolling hash、source registry、幂等结果窗口。
- 未 ACK export cursor 和 pending events。
- 第 7 节定义的配对 manifest、各 blob checksum 和 matcher/Core 一致水位。

恢复流程：

```text
reject ingress -> load and checksum Core + matcher snapshot
  -> restore exchange-core from snapshot-only state
  -> restore Core metadata/index/outbox at the same sequence
  -> replay Aeron log after snapshot watermark through both states
  -> verify sequence/rolling hash/open-order set/book hash
  -> verify funds conservation and index consistency
  -> ready=true
```

恢复期间入口必须拒绝交易；不得空 book、空索引或从 PostgreSQL 猜状态后接受下单。Matcher 恢复后要验证
规范化 book hash 和 open order 集合，exchange-core 内部历史 hash 不能直接替代业务恢复 hash。任一 manifest、
水位、registry、fork SHA、checksum、资金或索引校验失败都使节点保持 `NOT_READY` 并退出选主候选；生产恢复
不得自动执行逐单 rebuild。快照过程中若 barrier 超时或任一 matcher blob 失败，放弃整个快照，不得发布半快照。

## 12. 横向扩展边界

当前生产基线固定为“每个 `ProductLine` 变体一个逻辑 Core、每个逻辑 Core 三个 Aeron Member”。Core 内通过
稳定 user ingress lane 和 exchange-core symbol lane 管理并发；lane 是执行调度，不是资金或盘口分片。节点数量、
matcher engine 数和业务 shard 数是三个不同概念，不能通过增加 Member 或 engine 让同一 symbol 并行撮合。

当前只预留：

1. 所有命令、事件、snapshot、指标和路由配置显式携带 `coreShardId=default`、`routeVersion=1`。
2. 路由表必须按 `(productLine, coreShardId, routeVersion)` 解析，未知版本 fail-closed；当前配置只能解析到 `default`。
3. order/trade/export sequence 在所属 Core 内唯一，不建立 Redis/数据库锁保护的跨 Core 全局序列。
4. 不实现热点自动识别、在线迁移、双写、跨 Core 撤单或跨 Core 全仓余额共享。

只有单 Core 在完成 24 小时 soak、资金守恒和 HA 门禁后，仍连续触及以下任一条件，才启动热点分片设计评审：
SLO 约束下可用容量超过 70%、单 symbol 占 matcher CPU/队列时间的 50% 以上、snapshot/recovery 超过第 15.5 节
目标，或单一产品线的增长预测在一个发布周期内越过容量线。评审后的第一选择是垂直优化和减少外围开销；确需
独立热点 Core 时，使用停机/受控迁移把 symbol、订单簿和专属风险子账户整体迁移，不允许共享 live CROSS 余额。

## 13. 按顺序实施台账

### P0：文档、基线和安全护栏

交付物：本文档、代码路径矩阵、四类业务线/六变体不变量 fixture、性能基线、失败语义表。

必须先完成：

- 修复并测试 `stampOrderChanges` 不丢 triggerOrders。
- 为 place/cancel/replace/matching reducer 增加命令前后资金和 book 一致性测试。
- 记录 commandId/sourceSequence/orderId/tradeId/exportSequence 规则。
- 记录无 Docker/基础设施时的替代验证范围，不把微基准当生产容量。

### P1：正确性与单往返

交付物：稳定 source lane、明确结果未知、一次命令响应、matcher 失败回滚护栏、fee/instrument 版本门禁。

顺序：

1. 给 `placeOrder`、`cancelOrder`、`replaceOrder` 统一包住 matcher 调用和业务应用，异常时恢复命令前状态。
2. 成功响应直接返回 changed orders/executions/users，删除成功后的重复 order query。
3. 保证 retry 使用同 commandId；结果未知返回可重试状态。
4. 把 sourceId/sourceEpoch/sourceSequence 变为稳定注册协议。
5. 缺失用户费率快照默认 fail-closed 或显式使用已签名的 instrument default 版本，不静默降级。

### P2：TradingCoreRuntime 和 O(delta) 状态

交付物：单写可变 runtime、CommandDelta、增量索引、增量 rolling hash、无全量 constructor scan。

顺序：

1. 将 reducer 输出改为 runtime mutation + changed IDs。
2. 用 `byClientId/byUser/bySymbol/byDue` 索引替代触发/algo/liquidation 全表扫描。
3. 取消 `clientOrderIndex == null` 的隐式全量重建；index 必须显式携带并在同命令增量维护。
4. stamp/export/response/hash 全部消费 CommandDelta。
5. 只有 snapshot/restore/audit 允许全量遍历。
6. 活动订单 map 只保留必要业务元数据；终态按 export ACK + tombstone retention 清理。

### P3：exchange-core 唯一盘口和调用路径

交付物：移除第二本 FIFO、GTX 原子语义、Aeron 托管的 matcher 原生快照、无生产逐单 rebuild。

顺序：

1. 固定并验证 fork 制品来源、Git SHA、JDK、依赖和 checksum。
2. 增加 stable symbol/user registry 和碰撞校验；adapter 保持结构化异步 API 与有界查询。
3. 实现 Aeron `ISerializationProcessor` bridge、snapshot barrier、配对 manifest 和 snapshot-only restore。
4. 通过 FIFO、部分成交、撤改、GTX、损坏快照、Leader 切换和冷启动一致性测试。
5. 删除 `CoreBookState` 及其 priority map/codec/hash，活动订单只由订单元数据和 `ActiveOrderIndex` 表达。
6. 删除 `cleanStart + priorityOrder + placeAsync` 的生产恢复和异常 rebuild；v6/v19 首次上线使用全新 Cluster，不提供在线旧格式回放。

### P4：触发单、风险和长任务

交付物：mark price 命中索引、trigger atomic claim/execute/complete、risk/funding/settlement/liquidation continuation。

必须保证：

- trigger 完全从 DB claim/complete/expire/reset 脱离。
- mark price 只处理 affected user index，不从 users 开头重复扫描。
- funding、settlement、exercise、ADL、liquidation 每次只处理 bounded batch，并保存确定性 cursor。
- 长任务有优先级和预算，不阻塞普通订单无限时间。

### P5：导出、查询和外围隔离

交付物：一次编码的 bounded replicated outbox、批量 exporter、幂等 PG projection、当前态查询旁路、
WebSocket 慢连接隔离，以及第 1.0 节要求的 Gateway Auth Cluster/JWT 会话恢复边界。前五项已有 W5
真实故障证据；Auth Cluster 和在线 Provider 无数据库启动门禁尚未完成，因此 P5 总状态不是 `DONE`。

### P6：恢复、压测和扩容门禁

交付物：snapshot checksum/manifest、leader/follower/cold recovery、单产品线压测、资金对账、容量报告和扩容决策。

### 13.1 从当前代码到最终架构的实施波次

后续实施按表中顺序推进；W1/W2 已完成，W3-W6 继续以单一盘口和配对快照为前置条件，不得重新引入双写。

| 波次 | 主要改造点 | 具体落点 | 完成门禁 |
| --- | --- | --- | --- |
| W0 架构契约 | 固定一个 ProductLine 一个逻辑 Core、margin scope、shard identity、当前/目标状态 | 本文档、根 README、Aeron README、protocol 字段说明 | 文档无 P3 已完成或热点 Core 已启用的冲突表述 |
| W1 fork snapshot | 固定 fork provenance；实现 Aeron-backed `ISerializationProcessor`、matcher manifest/blob codec、snapshot barrier | 父 POM、fork 构建发布、`matching` adapter、Aeron snapshot codec/service | 同进程和新进程 snapshot round-trip；字节损坏、版本、SHA、registry 不一致全部 fail-closed |
| W2 单一盘口 | adapter 改为 snapshot-only restore；移除 `CoreBookState`、priority sequence、逐单 replay/rebuild | `TradingCoreState`、`CoreBookState`、`CoreStateSnapshotCodec`、`DeterministicExchangeCoreAdapter`、runtime/index | FIFO/部分成交/撤改/GTX 在 Leader 切换与冷启动后完全一致；内存中无第二份 FIFO；最大状态恢复达标 |
| W3 入口并发 | 强制 clientOrderId/幂等指纹；固定 Aeron agents、有界 mailbox；区分拒绝、已接收结果未知和终态 | Gateway、`AeronClientPool`、protocol/result query | backpressure 不阻塞 owner；超时复用同一 commandId；查询流量不进入写通道 |
| W4 保证金与生命周期 | 锁定 CROSS 产品线域、ISOLATED position 域；覆盖资金费、强平、ADL、保险、交割、行权 | reducer、risk/position/treasury state、provider bridge | 六个 ProductLine 变体逐项资金守恒；全仓/逐仓互不串账；长任务均有 bounded cursor |
| W5 导出与查询 | CoreExportState 作为 replicated outbox；Kafka/PG/WebSocket 全链路幂等、慢消费者隔离、cursor 恢复和 Gateway Auth Cluster | exporter、projection、WebSocket fanout、查询 API、Gateway auth/session | Kafka/PG 故障不影响裁决；重复/乱序不重记账；projection/event age 达标；数据库停止时 JWT 校验、会话恢复和在线 Provider 启动通过 |
| W6 HA 与容量 | 三 Member 故障、冷恢复、磁盘/网络故障、24h soak、逐级容量与回滚演练 | 单产品线集成环境、manifest、runbook、监控告警 | RPO=0；恢复/SLO/资金门禁全通过；日常负载不超过 SLO-bound capacity 的 70% |

W1/W2 已完成并使 P3 达到 `DONE`。W3-W6 必须在新的单一盘口 snapshot 格式上重新执行受影响门禁。
`CoreState v6` / `TradingState v19` 不兼容未发布的旧格式，不提供双读；首次切换使用全新 Cluster，
升级失败时仅允许在尚未接收 v6 命令前回退整套旧二进制和旧 Archive，绝不能混跑或双写两本 FIFO。

## 14. 文件级改造矩阵

| 阶段 | 主要文件/模块 | 预期变化 |
| --- | --- | --- |
| P0/P1 | `TradingCoreState.java`、`TradingCoreReducer.java`、`CoreProbeState.java` | 失败一致性、delta 传播、结果直接返回 |
| P1 | `DeterministicExchangeCoreAdapter.java` | native GTX、结构化结果、稳定 registry、恢复 token |
| P1 | `AeronClientPool.java`、`SurprisingAeronClient.java` | stable lane、bounded backpressure、egress dispatcher |
| P1 | `AeronOrderIdGenerator.java`、订单 service | 稳定 order identity 和结果未知语义 |
| P2 | `TradingCoreRuntime`（新类）及 state/index 包 | mutable single-writer runtime、增量索引 |
| P2 | `StateMapSupport.java`、`TradingCoreState.java` | 删除隐式全量 materialize/constructor scan |
| P3 | 已删除的 `CoreBookState.java`、snapshot codec、matching adapter | exchange-core 唯一盘口和 native restore |
| P4 | `TriggerOrderService.java`、protocol trigger commands、reducer | DB trigger lifecycle 移入 Core |
| P5 | `CoreExportState.java`、exporter、projection | CommandDelta 一次编码、批量 ACK、查询旁路 |
| P6 | `scripts/`、product-line fixtures、recovery tests | 单线测试、恢复、资金对账和压测门禁 |

### 14.1 当前代码路径与责任矩阵

以下路径是实施时的最小边界。新增逻辑必须落在对应 owner 内；如果必须跨边界，先在本节和第 19 节
记录原因，不得在 Gateway、Provider 或 Repository 中偷偷增加第二份裁决状态。

| 责任 | 当前入口/核心文件 | 允许做什么 | 禁止做什么 |
| --- | --- | --- | --- |
| 协议 | `surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/` | 固定二进制 command/query/response/export schema、版本和边界校验 | Java serialization、无版本 JSON、把 DB id 当顺序权威 |
| Core ingress | `.../surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java` | 解码、幂等、命令路由、响应和 Core commit | 成功后再查询全量状态、调用 Provider/DB/HTTP |
| 单写 runtime | `.../TradingCoreRuntime.java` | 持有 reducer、唯一 matcher、实体/派生索引和 owner 校验 | 后台线程直接改状态、使用全局锁或并发 map 伪装无锁 |
| 业务 reducer | `.../state/TradingCoreReducer.java` | 资金、订单、成交、风险、生命周期和 bounded continuation | 通过外部 repository 决定交易结果 |
| 状态容器过渡期 | `.../state/TradingCoreState.java`、`StateMapSupport.java` | 支持 snapshot/replay、delta lineage 和显式 changed keys | 继续把全量 constructor scan 当最终方案 |
| Instrument | `.../state/CoreInstrumentState.java`、`protocol/UpsertInstrumentCommand.java` | 接收版本化全量参数、校验档位、提供 Core 数学输入 | Risk Provider/订单 Provider 自己复制保证金参数 |
| 撮合 | `.../matching/DeterministicExchangeCoreAdapter.java` | 0.5.15-emporia 原生 GTC/IOC/FOK/GTX、结构化 fill、原生配对快照 | 自建第二本 book、查盘口后模拟 GTX、正常路径 rebuild |
| 风险 | `.../state/CoreContractMath.java`、`CoreRiskPolicy.java` | bracket margin、风险快照、强平状态和 policy version | 由 Risk Provider 重新判定或覆盖 Core 状态 |
| 触发/长任务 | `.../state/*Index.java`、`CoreProbeState` command handlers | index 命中、claim token、bounded cursor、幂等续跑 | DB claim/complete 和无界全表扫描 |
| 导出 | `CoreCommandDelta.java`、`CoreExportState.java`、`surprising-aeron-exporter/` | 一次事实组装、编码、批量读取、连续 ACK 和幂等 projection | 提交后 before/after 全量 diff、超前 ACK |
| 查询 | Core 有界 query + 异步投影 | limit/cursor/symbol/user 过滤和 read-your-write sequence | 管理查询遍历全 symbol/全订单并阻塞写者 |
| 外围 provider | instrument/order/risk/gateway/maker | 鉴权、输入桥、展示、投影和非权威协调 | 同步裁决资金、撮合、风险、强平或恢复 |
| 测试与运行 | `surprising-aeron-core/compose.yaml`、未来 `scripts/`、`surprising-aeron-tools/` | 单产品线 smoke、recovery、funds reconcile、capacity | 并行启动多产品线推导容量、使用数据库流程脚本 |

## 15. 测试和验收门禁

### 15.1 纯逻辑和协议

- 所有协议 decode 拒绝截断、尾随字节和非法枚举。
- 定点算术溢出 fail-closed。
- clientOrderId、commandId、source sequence、orderId、tradeId 重放不重复扣款/成交。
- GTX 遇到会吃单时原子拒绝且订单簿、余额、状态完全不变。
- IOC/FOK 未成交余量在同一命令内终态并释放预留。

### 15.2 核心状态

- place/cancel/amend/replace 成功和异常后的用户、订单、book hash 一致。
- maker/taker 双边资金、手续费、持仓、reserved/available 守恒。
- trigger claim token 过期/重复 complete 不改变状态。
- funding、settlement、option exercise、liquidation、ADL 重复执行不重复记账。
- 每命令 changed entity 数量与 delta 记录一致；测试禁止隐式全量 diff。

### 15.3 Aeron 和恢复

- 单 Member、三 Member leader failover、follower rejoin、全 Cluster cold restart。
- matcher snapshot 必须覆盖同进程/新进程 round-trip、FIFO/部分成交/撤改/GTX，并拒绝损坏 blob、错误 fork SHA、registry/version 和不一致水位。
- 恢复后 `coreSequence`、rolling hash、规范化 book hash、资金守恒和幂等窗口一致。
- Export sink 失败时 Core 不丢事件、不提前 ACK；恢复后批量 drain 无重复资金效果。
- source lane 重启后的旧/新 epoch 规则可验证，结果未知命令可安全复用 commandId。

### 15.4 每条产品线

每次只启动一个 ProductLine 变体，做市进程保持运行，覆盖：充值/调整、下单、撤单、撮合、成交、持仓、主动平仓、强平、风控事件、私有/公共 WebSocket、投影和资金对账。六个变体分别验证各自的资金公式，不能用 SPOT 结果代替衍生品/期权结果；同一衍生品变体必须分别覆盖 CROSS 和 ISOLATED。

### 15.5 性能指标

必须同时报告吞吐、p50/p99/p99.9、GC、分配率、核心线程忙闲、Aeron offer backpressure、matcher ring latency、export lag、snapshot pause 和恢复时间。禁止只报告平均 TPS。

阶段出口最低要求：

1. 普通 place/cancel/amend 各自一个 Aeron 命令往返；成功不追加查询。
2. 热路径无 JDBC/Redis/Kafka/HTTP，无全量 users/orders/triggers 扫描。
3. 运行时只存在一份可执行 exchange-core book。
4. batch API 不再 N 次同步单命令。
5. failover/cold recovery 后 hash、book、资金和幂等一致。
6. export backlog 满时在 mutation 前拒绝并可恢复 drain。

在生产基线压测完成前采用以下暂定 SLO，任何 TPS 数字都必须在这些约束同时满足时报告：正常交易端到端
`p99 <= 10ms`、`p99.9 <= 25ms`；Warm Member/Leader 故障恢复 `<= 10s`；最大受支持状态冷恢复
`<= 60s`；核心命令 `RPO=0`；export event age `p99 <= 2s`；24 小时 soak 中资金差额、重复成交和 book
不一致均为 0。生产日常峰值不得超过满足上述 SLO 的实测容量 70%，未完成分层压测前不声明固定 TPS 能力。

## 16. 明确不做

- 不自研第二本撮合 book。
- 不用 `ConcurrentHashMap`、数据库锁、Redis 锁或全局 CAS 伪装无锁。
- 不把 Kafka/PG/Redis 重新放回同步裁决路径。
- 不在没有 profiling 前盲目增加 matching/risk engine 数量。
- 不为每条命令增加 fsync、Future、JSON 或网络往返。
- 不把 exchange-core native journal 当成第二业务权威。
- 不在没有跨 Cluster 资金协议前拆分会互相预留资金的用户/订单状态。
- 不用无界队列、不用无限 retry、不用空 book/DB 接受交易。

## 17. 变更和回滚规则

每个阶段单独提交、单独构建、单独记录测试证据。任何阶段发现资金守恒、顺序、book hash、幂等或恢复失败，停止后续阶段，保留前一阶段可运行版本并通过 feature flag/版本化 snapshot 隔离本阶段；不得通过放宽校验、吞异常、删除失败测试或绕过 Core 修复指标。

本规格的完成定义不是“编译通过”，而是所有 P0-P6 交付物、六个 ProductLine 变体资金门禁、Aeron 恢复门禁、单一盘口门禁和性能证据都已具备。未达到的项必须在阶段台账中明确标记，不得宣称完成。

## 18. 当前代码落地状态

### 18.1 阶段状态总表

状态只允许使用 `NOT_STARTED`、`IN_PROGRESS`、`PARTIAL`、`DONE`、`BLOCKED`。`DONE` 必须同时具备代码、
测试、运行时证据和本节记录；仅有单元测试不得标记为 `DONE`。

| 阶段 | 当前状态 | 本阶段交付物 | 当前证据 | 尚未满足的出口 |
| --- | --- | --- | --- | --- |
| P0 文档/基线 | `DONE` | 规格、问题追踪、所有权、验收/回滚规则、脚本矩阵 | 本文档、README、基线约束、Core-only canonical wrappers 和六个固定产品线 Provider 脚本已同步 | 无；真实业务门禁归 P4，容量与运行手册归 P6 |
| P1 正确性/单往返 | `DONE` | 失败回滚、稳定 lane、结果未知、fee/instrument version gate、直接响应 | Core 回滚护栏、bounded client、`COMMAND_RESULT_QUERY` 结果核验、native GTX 测试 | 无；source epoch 采用进程 epoch 编码 sourceId 的简单方案，不另建 registry |
| P2 Runtime/O(delta) | `DONE` | 单写 runtime、持久化 delta entity store、CommandDelta、增量 index、rolling hash | `TradingCoreRuntime`、DeltaMap lineage、各类派生 index、Core service 121 tests 和运行时 smoke；热点逐项状态见第 3.1/3.3 节 | 热路径已无隐式全量 constructor/diff/hash；用户/订单 immutable state shell 仍是可选的后续性能优化；历史实体 compaction 仍按运行手册执行 |
| P3 唯一盘口/恢复 | `DONE` | exchange-core 唯一 executable book、结构化 adapter、Aeron 托管 matcher snapshot、无逐单恢复 | fork 302 tests；六个 ProductLine 原生 FIFO restore；snapshot round-trip、损坏/版本/SHA/registry/订单集合不一致均 fail-closed；`CoreBookState` 和生产 rebuild 已删除 | 真实三 Member election、冷恢复和长时容量仍属于 P6 上线门禁 |
| P4 风险/生命周期 | `PARTIAL` | trigger/risk/funding/settlement/liquidation bounded continuation | 六条 ProductLine Core-only 基线通过；W4 静态配置门禁通过；`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION` 已通过宿主机三节点 Cluster 的真实 HTTP/做市/结算/资金守恒门禁；在线余额、持仓、活动订单、触发单和生命周期执行已由 Core 裁决 | SPOT 和两条永续仍需逐条执行真实 Provider 门禁；cursor 重启/缺口仍需验证；Funding 在线编排仍依赖 PostgreSQL lease/sequence/settlement reservation，Risk scan control 仍有数据库配置路径 |
| P5 导出/查询/外围 | `PARTIAL` | 一次编码 outbox、带 cursor 的批量 ACK、projection、查询旁路、慢连接隔离、Gateway Auth Cluster | `w5-export-final-1315` 通过 Kafka/PG 故障恢复、重复/乱序、Gateway/WebSocket offset、Core 独立和资金门禁；`w5-isolation-final-1345` 通过 PG 恢复、投影缺口回放、慢客户端隔离和清理；活动订单/单笔订单已直接查询 Core | Gateway 用户、MFA、challenge、refresh session 仍由 JDBC repository 持有，Gateway Auth Cluster 未实现；Trading/Account/Lifecycle 等 Provider 的 Spring 构造图仍强制创建 JDBC 历史/投影 Bean，尚未通过 PostgreSQL 停止后的在线启动门禁 |
| P6 HA/压测/扩容 | `IN_PROGRESS` | leader/follower/cold recovery、单线容量、manifest、runbook | SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION 六条产品线 recovery manifest 通过；六条产品线均有 20 秒 capacity PASS 和角色日志 | 先完成 P4/P5 出口；再完成六条线固定脚本真实 `test` 门禁、长时稳定容量、CPU/GC/Aeron/export/projection lag 指标、70% 生产容量线、扩容结论、24 小时 soak、网络/磁盘故障和生产 runbook；文档引用的 `.omo/evidence` 当前未纳入工作树，正式门禁证据需持久化到版本化报告目录 |

### 18.1.1 最新源码完成度复核（2026-08-18）

以下结论以实施分支提交 `8d81475` 的源码和已记录运行证据为准。`DONE` 表示目标代码边界已经落地；
`PARTIAL` 表示部分功能已切到 Core，但启动构造图、在线编排或真实运行出口仍未满足；历史查询使用
PostgreSQL 本身不算缺陷，前提是它不参与当前态查询和在线裁决。

| 能力/模块 | 状态 | 已完成实现 | 仍需完成 |
| --- | --- | --- | --- |
| 唯一盘口与恢复 | `DONE` | exchange-core 是唯一 executable book；原生 matcher snapshot、Cluster Log/Archive、配对 manifest 和 fail-closed 恢复已落地 | 长时 election/cold recovery 容量证据归 P6 |
| 账户当前态 | `DONE` | 余额、冻结、持仓、仓位模式和调整命令均走 Account Aeron gateway/Core；PostgreSQL 只用于账本、划转记录和后台审计查询 | 将历史查询 Bean 与在线 Provider 启动构造图解耦，归“运行时零数据库”出口 |
| 订单与触发单当前态 | `DONE` | 单笔订单、clientOrderId、活动订单分页、批量撤单选择和生命周期撤单均直接查询/命令 Core；历史订单和后台筛选继续读异步 PostgreSQL 投影 | 将 fee snapshot 初始化及历史投影 Bean 改为可离线/可选依赖，证明 PostgreSQL 停止时 Provider 可启动和交易 |
| Risk/Liquidation/Insurance/ADL | `PARTIAL` | 四域已合并为单一 Lifecycle Provider/API；风险快照、强平 work、保险基金、ADL queue 与资金变化由 Core 查询/命令裁决；未使用的 Insurance/ADL sequence 和 pending projection Repository、字段及构造注入已删除 | 将 Risk scan control 从 JDBC override 改为版本化 Core/显式离线配置；把仍在使用的历史查询 Repository 与在线执行构造图解耦，历史查询只能通过异步投影 |
| Funding | `PARTIAL` | 资金结算资金变化和 continuation progress 已进入 Core | `FundingService` 仍同步使用数据库 rate input、lease、sequence、settlement reservation 和 finalization；需迁移为 Core 状态/版本化输入，历史 rate/payment 查询再与在线服务解耦 |
| Exporter/Projector/WebSocket | `DONE` | Exporter 仅 Aeron→Kafka，Projector 独立 Kafka→PostgreSQL；批量 ACK、重复/乱序、缺口恢复和慢客户端隔离门禁已通过 | 生产规模 lag、背压和长时故障指标归 P6 |
| Gateway Auth | `NOT_STARTED` | 现有登录、MFA、challenge、refresh session 功能仍可通过 JDBC 工作 | 按第 1.0 节实现 Gateway Auth Cluster snapshot/JWT 边界，并验证数据库停止时登录态校验和会话恢复 |
| Provider 模块归并 | `DONE` | Order/Trigger 统一为 Trading Provider；Risk/Liquidation/Insurance/ADL 统一为 Derivatives Lifecycle Provider/API；Matching/Candlestick 统一为 Market Data Provider/API；旧 Provider reactor 入口已删除 | 合并 JVM 的真实六产品线 Kafka/Aeron/资金门禁仍归 P4/P6，不以编译通过替代 |
| Product API 边界 | `DONE` | `ProductLineSql` 已删除，`InstrumentSpecKey` 已迁入 Instrument API；`surprising-product-api` 只保留 ProductLine、配置与 Topic 命名等无 SQL 领域类型 | 无；不再把产品线通用领域类型强行并入 Instrument API |
| 固定六产品线脚本 | `DONE` | 六个独立脚本固定 JDK 25、宿主机三节点 DEDICATED Cluster、端口、启动顺序、Clash、已有 PostgreSQL/Kafka/Valkey 和按需 Provider | 当前只完成 SPOT、LINEAR_PERPETUAL 启停验证及其余脚本 dry-run；六条脚本的完整 `test` 结果归 P4/P6 |
| 运行时零数据库 | `PARTIAL` | Instrument 继续合法使用 PostgreSQL；在线资金/订单/风险权威状态已进入 Core；Exporter 不持有 DataSource | Trading、Account、Lifecycle、Funding、Maker、Market Data、Gateway 仍包含 JDBC/PostgreSQL 依赖，公共启动环境向所有 Provider 注入 DataSource；需拆分在线与历史查询进程/Bean 并通过 PostgreSQL/Kafka/Valkey 停止门禁 |

### 18.1.2 完成项与剩余出口清单

- [x] P0-P3：规范、单往返、O(delta) runtime、唯一 exchange-core 盘口和原生 snapshot 恢复。
- [x] W5 导出/投影功能：Kafka/PG 故障隔离、批量 ACK、重复/乱序、缺口恢复、WebSocket offset 与慢客户端隔离。
- [x] Trading、Derivatives Lifecycle、Market Data 三组 Provider/API 归并及旧运行入口清理。
- [x] Lifecycle Provider 未使用的 Insurance/ADL sequence、pending projection Repository 和构造依赖清理。
- [x] 六条产品线固定宿主机三节点 `DEDICATED` 启动脚本。
- [x] `LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION` 真实 HTTP/做市/生命周期/资金守恒门禁。
- [ ] SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL 真实 Provider 生命周期/资金守恒门禁。
- [ ] 生命周期 cursor 在 leader restart、follower catch-up、缺口和重复回调场景下的恢复门禁。
- [ ] Funding、Risk scan control、Trading fee snapshot 及仍在使用的历史查询 Repository 与各在线 Provider 构造图的 PostgreSQL 解耦。
- [ ] Gateway Auth Cluster、会话 snapshot/JWT 恢复和数据库停止门禁。
- [ ] 六条产品线逐条执行固定脚本完整 `test`，保存可复核 manifest，而不是只保留启动/dry-run 结果。
- [ ] 单产品线长时容量与分阶段边界报告：下单、撮合、风控扫描、强平、平仓、结算、标记价和整体 OPS。
- [ ] 完整 CPU/GC/Aeron/Kafka export/projection lag 指标、70% 生产容量线、24 小时 soak、网络/磁盘故障和部署/扩容 runbook。

### 18.2 文档、代码和证据同步规则

每个阶段必须按以下顺序落地，不得跳阶段：

1. 在本文档对应阶段增加“实施前检查”和明确的文件/协议边界。
2. 先补充能锁定资金、顺序、幂等和恢复行为的测试，再改生产代码。
3. 每个逻辑变更完成后运行该阶段最小测试；阶段出口再跑模块回归和对应人工/集群门禁。
4. 更新本节状态、实际文件、测试命令、结果、Git SHA、环境 manifest 和残留风险。
5. 若发现偏差，追加到“决策/偏差记录”，不得覆盖历史设计或静默降低门禁。
6. 阶段失败时只回滚本阶段新增代码和 snapshot/schema 版本，保留前一阶段可运行证据；数据库/Redis 不得成为运行时权威。

### 18.3 W4 Provider 生命周期入口（本轮实施）

本轮先以 `LINEAR_PERPETUAL` 为真实接入样板，补齐 Provider 到 Aeron Core 的可控运维触发边界；同一实现由各 Provider 的 ProductLine 配置隔离，未新增第二套生命周期引擎，也未让 PostgreSQL 选择或推进权威工作：

| Provider | HTTP 入口 | Core 调用 | 返回的有界结果 |
| --- | --- | --- | --- |
| Funding | `POST /api/v1/funding/admin/run-cycle` | `APPLY_FUNDING` + `FUNDING_PROGRESS_QUERY` | due/settled/pages/failed |
| Liquidation | `POST /api/v1/admin/liquidations/run-cycle` | bounded work query + `executeBatch` | offered/applied/pending/obsolete/processed |
| Insurance | `POST /api/v1/insurance/admin/run-cycle` | Core-selected insurance resolution + `RESOLVE_LIQUIDATION` | resolutions/covered/unresolved |
| ADL | `POST /api/v1/adl/admin/run-cycle` | Core-selected ADL resolution + `EXECUTE_ADL` | resolutions/executed |

所有入口要求非空 `X-Admin-User-Id`（生产环境由 Gateway 的 admin 权限过滤器负责身份和权限校验）。cycle 方法在 Provider 实例内串行执行；Core command 使用既有确定性 command ID、ProductLine/账户类型校验和 continuation cursor，因此重复请求只会推进下一有界页或返回已完成状态。定时任务继续调用同一 service 方法，不存在旁路资金逻辑。PostgreSQL 仅记录历史/投影/幂等账本，不参与在线余额、持仓、风险或生命周期裁决。

本轮验证命令（JDK 25）：

```text
mvn -pl :surprising-funding-provider,:surprising-derivatives-lifecycle-provider -am -DskipTests compile
mvn -pl :surprising-funding-provider,:surprising-derivatives-lifecycle-provider -am -Dtest=FundingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：受影响模块编译通过，4 个 Provider service 测试类共 21 个测试通过。该结果只证明 Provider→Core 代码边界和有界 continuation，不等价于真实 HTTP、做市、用户资金和 Treasury 对账门禁；这些仍是 P4 的下一出口。

### 18.3.1 无数据库运行时修订（2026-08-18）

此前表格中的“PostgreSQL 仅记录历史/投影”仍可能被误读为生命周期调用可以同步或异步直写数据库。本次明确修订为：Core 命令返回 `APPLIED` 即是在线业务完成条件，Provider 不写 PostgreSQL ledger、sequence、coverage 或 ADL event。`AeronLifecycleCoordinator.shared()` 提供跨 Funding/Liquidation/Insurance/ADL 的统一有界调度；保险覆盖、资金调整、清算费和 ADL 事实由 Core export event 进入 Kafka，再由独立 projector 幂等写历史库。

本次已验证 `surprising-aeron-client` 与 `surprising-derivatives-lifecycle-provider` 增量构建通过；Risk/Liquidation/Insurance/ADL 源码和 API contract 已由统一模块拥有。这不是完整无数据库门禁：Funding 的费率输入/租约/sequence/settlement reservation、Trading fee snapshot、Risk scan control 以及在线 Provider 构造图仍含 JDBC 依赖。Instrument 配置继续按既定例外保留 PostgreSQL，但必须先版本化导入 Core；其余在线依赖必须迁移到 Core snapshot/query 或显式离线配置后，才能宣称审计 PostgreSQL 可停止。

### 18.3.2 审计导出链路边界（2026-08-18）

审计 exporter 不直接写 PostgreSQL。链路固定为 `Aeron Core -> KafkaCoreExportSink -> ProductLine Kafka topic -> KafkaProjectionWorker -> JdbcCoreEventProjector -> PostgreSQL`：

- `ExporterMain` 只连接 Aeron、发布 Core export event 到 Kafka；它不创建 `DataSource`。
- `ProjectionMain` 才是可选的 PostgreSQL 投影消费者，从 Kafka topic 消费并幂等写历史表。
- Core、Provider、Gateway 和做市进程不调用 `JdbcCoreEventProjector`，也不等待投影 ACK。
- Kafka 或 PostgreSQL 停止时，只增加 export/projection lag，不改变订单、撮合、资金、风险或生命周期结果。

运行编排中的 `exporter` 与 `projector` 是两个独立进程；`DATABASE_URL` 仅注入 `projector`，用于明确禁止 exporter 直连数据库。

### 18.3.3 W4 交割产品线真实门禁（2026-08-18）

W4 已改用 W5 验证过的宿主机三节点 Aeron Cluster，Docker 只复用隔离的 PostgreSQL 与 Kafka。每次只启动一条产品线的必要 Provider，做市进程保持运行，wallet 不启动。

- `LINEAR_DELIVERY`：运行 `w4-linear-delivery-final9`，返回 `W4_MANIFEST=REAL_PASS`、`FUNDS_DIFFERENCE=0`、`maker=OBSERVED`、`cleanup=PASS`。
- `INVERSE_DELIVERY`：修复完成游标等待后运行 `w4-inverse-delivery-complete1`，返回 `W4_MANIFEST=REAL_PASS`、`FUNDS_DIFFERENCE=0`、`W4_SIX_LINE=PASS`、`cleanup=PASS`。
- 逆向交割两组 CROSS/ISOLATED 多空在 `100 -> 110` 结算时分别产生 `+91/-91`，结算后持仓归零、锁定余额为零；用户余额、手续费、保险基金合计守恒。
- W4 reconciliation manifest 记录每个参与用户的余额、预留和持仓，资金差异失败不再只有聚合数字。

此前 `w4-inverse-delivery-final1` 的 `difference=-91` 发生在旧结算等待语义下：驱动查到 progress 后立即对账，但没有要求分块结算完成。当前驱动等待 Instrument 结算事件被 Account Provider 回调到 Core，并确认 `complete=true && ordersComplete=true` 后才查询和对账。没有通过扩大用户集合、修改期初资金或放宽 `FUNDS_DIFFERENCE=0` 消除失败。

### 18.3.4 W4 OPTION/Aeron 超时闭环（2026-08-18）

OPTION 真实门禁最初停在已受理 Aeron 请求的结果未知超时。修复保持资金状态边界不变：Aeron 客户端在异步会话真正连接前不发送排队请求，排队和已受理请求分别有界超时；只读 Risk 查询允许在 `ResultUnknown`/`NOT_CONNECTED` 后使用新查询命令重试，Account 结算命令则只用原 `commandId` 查询结果，绝不重复提交资金命令。

生命周期 Kafka 消费改用单并发、逐记录 ACK、无限固定退避的专用失败关闭工厂，避免单分区 OPTION 行权事件被通用双并发批量 listener 反复 rebalance。Account 将 `OptionExerciseEvent.underlyingSettlementPriceUnits` 作为 Core 的 `settlementPriceTicks`，Core 依据权威 instrument 独立计算每张合约行权现金；事件中的现金值只用于一致性载荷，不再把零价格提交给异步 matcher 并导致 Cluster fatal divergence。

真实运行 `w4-option-final11` 返回：

- `W4_MANIFEST=REAL_PASS`，六行 `CALL/PUT × ITM/ATM/OTM` 全部通过，跨产品线 topic 拒绝通过。
- ITM 买卖双方分别产生 `+40/-40`，ATM/OTM 为零行权现金；所有参与账户持仓归零、锁定余额归零。
- `FUNDS_DIFFERENCE=0`、`maker=OBSERVED`、`wallet=ABSENT`、`W4_SIX_LINE=PASS`、`cleanup=PASS`。
- Account 定向测试 `ExpiringContractSettlementConsumerTest`、`ExpiringContractSettlementFanoutServiceTest`、`AccountAeronGatewayTest` 共 10 个测试通过；Aeron client 及 Risk gateway 的定向超时测试此前已通过。

### 18.3.5 固定产品线独立测试入口（2026-08-18）

为避免每次测试临时修改产品线、端口或启动参数，六个独立、固定配置的入口已恢复到
`scripts/`：

| 产品线 | 脚本 | 固定运行 ID |
| --- | --- | --- |
| SPOT | `spot.sh` | `spot-fixed` |
| LINEAR_PERPETUAL | `linear-perpetual.sh` | `linear-perpetual-fixed` |
| INVERSE_PERPETUAL | `inverse-perpetual.sh` | `inverse-perpetual-fixed` |
| LINEAR_DELIVERY | `linear-delivery.sh` | `linear-delivery-fixed` |
| INVERSE_DELIVERY | `inverse-delivery.sh` | `inverse-delivery-fixed` |
| OPTION | `option.sh` | `option-fixed` |

每个脚本只接受 `start`、`test`、`stop` 三个动作；省略动作等同于 `test`。每个文件只声明自身
`ProductLine`、固定运行 ID、固定启动顺序和 JDK 25。运行器固定采用 W5 已验证的宿主机三节点
Aeron Cluster，三个节点都使用 `DEDICATED` threading mode 和该运行 ID 的独立数据目录；不再在
Provider 联调时尝试 Docker Aeron 或临时切换连接方式。macOS 上进程由 `launchctl` 托管，脚本退出后
节点和 Provider 继续存活，`stop` 只清理由当前运行 ID 创建的进程。

`start` 按 Instrument、三节点 Core、Exporter、Projector、Price、Account、Trading、合并后的
Market Data、按产品线启用的 Derivatives Lifecycle/Funding、Gateway、Maker 顺序启动，并逐个检查
Core probe 或 `/actuator/health`。PostgreSQL 18、Kafka 和 Valkey 复用本机已有实例，不创建临时
中间件；数据库只执行幂等 baseline/migration，交易热路径权威状态仍在 Core。价格外部 HTTP 固定走
本机 Clash Verge `127.0.0.1:7897`，Wallet 固定不启动。

产品线差异固定为：SPOT 不启动 Derivatives Lifecycle 和 Funding；两条永续同时启动统一的
Derivatives Lifecycle 与 Funding；两条交割和 OPTION 只启动统一的 Derivatives Lifecycle。
`test` 使用独立测试运行 ID 和 fresh Core 数据，执行 `ProductLineLifecycleQaMain` 的真实 HTTP、做市、
生命周期和资金/Treasury 对账门禁，要求 manifest 同时包含 `TEST_STATUS=PASS` 与
`FUNDS_DIFFERENCE=0`，完成后自动清理。旧 `runtime/w3-w5/run.sh` 不作为运行依赖。

固定拓扑落地后已执行两条代表性真实启动验证：SPOT 的 12 个进程和 LINEAR_PERPETUAL 的 14 个
进程均完成启动、跨命令 `status` 和定向 `stop`；后者覆盖统一 Derivatives Lifecycle 与 Funding。
验证过程中修复了合并模块使用全限定 Bean 名生成器后 `markPriceConsumerProperties` 名称丢失的问题，
统一 Lifecycle 现可直接从固定脚本启动。其余四条线完成脚本语法、非法动作拒绝和固定拓扑 dry-run；
本次不重复执行六条完整业务门禁。

只执行一条产品线：

```bash
cd scripts
./spot.sh test
./linear-perpetual.sh test
./inverse-perpetual.sh test
./linear-delivery.sh test
./inverse-delivery.sh test
./option.sh test
```

脚本的语法和非法动作拒绝检查已通过；真实门禁仍按产品线逐条执行，不以一次多产品线运行结果
替代单产品线资金、生命周期和恢复证据。

### 18.4 Canonical 测试脚本矩阵

`scripts/aeron-core-local.sh` 继续保留为 Core-only Docker smoke 工具，但六条固定产品线脚本统一调用
`scripts/start-product-line-providers.sh` 的宿主机最终拓扑，不再复用 Docker smoke 连接方式。脚本仍
必须遵守只跑一条产品线、不启动 Wallet、不把数据库作为交易运行时权威的约束。脚本名称和职责固定如下：

| 脚本 | 作用 | 约束 |
| --- | --- | --- |
| `scripts/start-product-line-providers.sh` | 启动当前产品线宿主机三节点 Core、最终合并 Provider、Exporter/Projector、Gateway 和 Maker | 固定 JDK 25 + DEDICATED；复用本机 PostgreSQL/Kafka/Valkey；不启动 Wallet |
| `scripts/product-line-api-flow-smoke.sh` | 运行当前产品线现有 Core smoke，覆盖对应产品线已实现的下单/撤单/撮合/资金与持仓门禁 | 只走当前内存核心和 Core query；未实现的强平/生命周期场景不得伪造为通过 |
| `scripts/product-line-funds-reconcile.sh` | 按显式用户范围核对 Core 用户余额与 Treasury 聚合资产 | 当前工具报告 Core balance/treasury `fundsDiff=0`；成交、手续费、资金费、强平、交割/行权逐笔流水需接入对应 export 投影后才可宣称完整账账对平 |
| `scripts/live-runtime-trading-reconciliation.sh` | 运行时 Core probe 与 exporter 状态对账；外部投影/WebSocket 接入后再扩展其 lag 采样 | 当前只报告 Core state hash、applied command 和 export cursor，不虚报未接入投影指标 |
| `scripts/integration-smoke.sh` | 单产品线 Core smoke、查询和 exporter 状态检查 | 不隐式启动其他产品线；恢复 hash 使用 recovery matrix 单独验证 |
| `scripts/kafka-trading-smoke.sh` | 仅验证当前 Core input/export bridge 的编译、协议和 ACK 回归 | 当前环境未启动 Kafka 时不得声称完成端到端 Kafka 集群验证；Kafka 不进入同步交易裁决 |
| `scripts/aeron-core-local.sh` | 构建镜像、启动/停止三节点和工具容器 | volume/目录必须显式指定，禁止宽泛删除 |
| `scripts/run-product-line-recovery-matrix.sh` | node stop/rejoin、cold restart，并生成 state-hash 与 LEADER/FOLLOWER role manifest；exporter failure 通过 `aeron-core-tool.sh export-fail` 显式演练 | 每次只跑一条产品线；当前是受控本地三节点角色转换门禁，不等同生产网络/磁盘故障报告 |
| `scripts/run-product-line-capacity.sh` | 显式 `FRESH`/保留卷后调用 Core capacity/lifecycle tools，接受 warmup、duration、workers、connections 等单次实验参数并生成 manifest | 当前是可审计的单次实验入口；baseline/capacity-step/hot/burst/soak 编排和端到端指标仍是 P6 出口，不把 micro benchmark 当容量 |

脚本迁移规则：先列出脚本调用的服务、topic、数据库表和命令，逐项映射到当前工具；没有对应行为的
脚本直接删除，不保留替代入口。任何脚本改变产品线、source identity、数据目录或 Docker volume 前必须显式
打印并校验目标。`aeron-core-tool.sh` 只接受白名单产品线，并把探针、资金对账、容量和 exporter 演练绑定到
同一产品线的 compose project；`fresh` 是唯一允许删除该产品线卷的显式动作。

### 18.5 增量 JAR 构建规则（本轮实施）

构建不得默认执行根目录 `mvn package`。`scripts/build-incremental.sh` 根据模块路径或 artifactId 生成受影响模块闭包：
先纳入修改模块及其聚合子模块，再沿 Maven POM 依赖反向查找所有下游消费者，最后使用 Maven `-am` 补齐上游依赖。
因此 API、协议或共享 parent 变更会有证据地扩大构建范围，单个 Provider 变更不会触发无关产品线 JAR 重建。

```bash
scripts/build-incremental.sh surprising-trading/surprising-trading-provider
scripts/build-incremental.sh :surprising-aeron-client :surprising-aeron-tools
scripts/build-incremental.sh --changed --dry-run
scripts/build-incremental.sh --with-tests :surprising-aeron-service
```

脚本默认执行 `package -DskipTests`，只生成计划中模块的 JAR；`--with-tests` 才运行受影响测试，`--goal install` 才写入
本地 Maven 仓库。`--changed` 会忽略文档、脚本、报告、日志和构建产物；根 `pom.xml` 或 `surprising-parent/pom.xml`
改变时安全地选择全部 reactor 模块。脚本输出 `incrementalBuild=PLAN` 和最终模块列表，便于在部署/压测前审计。

本轮已经落地的护栏和增量改造：

1. `CoreProbeState.completeMatching` 在 matcher 结果与业务状态应用不一致时抛出 `FatalMatchingDivergence`，成员进入 sticky fail-closed 并停止继续裁决；不再尝试进程内恢复或异步重建 exchange-core。
2. `TradingCoreReducer` 的非订单命令统一把 users、orders、clientOrderIndex 作为 delta 传给 `TradingCoreState`，避免无关命令触发全量用户/订单校验；`stampOrderChanges` 在没有订单变化时使用空 changed-id 集合，不再复制和扫描全量订单。
3. `CoreProbeState` 为用户、订单和触发单设置显式 changed-id 集合；资金、清算、结算、余额和仓位命令不再依赖全量 diff。触发单按 symbol 建立可恢复的派生索引，clientTriggerOrderId 使用索引做幂等检查。
4. Aeron gateway source identity 与启动 epoch 分离：同一部署节点使用显式稳定 identity，进程重启获得新 epoch，避免 source sequence 回退后永久被 Core 判为 stale。
5. Aeron 触发单的到期、陈旧 `TRIGGERING` 重试、持仓归零撤单、instrument lifecycle 撤单和 mark-price 候选查询统一使用 Core 查询/命令；新增 `EXPIRE_TRIGGER_ORDER`、`RETRY_TRIGGER_ORDER`。
6. exchange-core symbol 注册增加进程内稳定 ID registry 和确定性碰撞探测；同一运行时不会把两个不同 symbol 注册为同一个 matcher symbol ID。
7. `TradingCoreRuntime` 作为 CoreProbeState 的单写运行时边界，集中拥有 reducer、matcher、position/open-interest/trigger 索引；状态过渡、索引更新、回滚恢复和资源关闭不再由调用点分别维护。
8. reducer 的未修改 leverage/algo/timer/trigger map 统一以 delta 传递；TradingCoreState、CoreTreasuryState 的 delta 分支只校验 changed keys，避免无关命令在状态构造器内全量遍历。
9. matcher 启动恢复导入 Aeron 配对快照中的原生 `ME0/RE0`，以 `InitialStateConfiguration.fromSnapshotOnly` 启动；规范化 symbol/user registry、配置、完整引擎 hash、book hash 和 OPEN 订单逐字段对账全部通过后才开放流量。
10. `EXECUTE_TRIGGER_ORDER` 已成为单一 Core 命令：Core 内完成 claim、instrument 版本/费率快照门禁、资金预留、exchange-core 撮合、成交应用和 trigger complete；触发命中不再同步往返 `OrderRpcApi`。触发单入 Core 时固化 instrument version 与 maker/taker fee，版本漂移 fail-closed；Aeron placement 使用 `instrumentVersion=0` 让 Core 从唯一 `CoreInstrumentState` 补齐费率快照，不再同步调用 `TradingFeeRpcApi`。
11. `BOOK_STATE_QUERY` 支持 symbol/depth 有界协议查询；空 payload 使用默认深度，深度上限为 1000，adapter 不再请求 `Integer.MAX_VALUE`。
12. 导出队列容量预检按最大协议事件预留字节，在命令改动业务状态前拒绝无法容纳的事件；不会把导出编码失败留到成交后再回滚。
13. `AeronClientPool.commandAsync` 使用有界队列和 `AbortPolicy`，客户端饱和时显式背压，不再创建无界任务队列。
14. `TradingCoreRuntime` 现在统一持有 active-order、algo、cancel-all timer、liquidation、ADL position、position/open-interest/trigger 派生索引；`CoreProbeState` 的 algo/timer/open-order/ADL/liquidation 查询和风险清算查重使用这些索引，索引在同一 transition 内增量更新。
15. `StateMapSupport` 保留 DeltaMap lineage，在达到压缩阈值时只 materialize 内部基线并保留父链，`changedKeysSince` 不因压缩退化为未知全量 diff；正常构造器仍只校验 changed keys。
16. exchange-core adapter 只提供结构化 `placeAsync`、`cancelBatchAsync` 等异步 API；调用层不再需要逐条同步等待才能形成批量操作。
17. snapshot 编码预先缓存 pending export event 的编码结果，避免计算长度和写入时重复编码；`CoreExportBatch` 携带 acknowledged cursor，exporter 有事件时不再先做独立 status 查询，正常周期由三次往返降为 batch+ack 两次。
18. Aeron client 提供显式 `sourceEpoch` 构造入口；默认每次进程启动生成新 epoch，并将 `sourceIdentity + productLine + epoch + lane` 编码进 sourceId，Core 继续按 sourceId 维护单调 sourceSequence。该设计不另建数据库/注册表，避免 source sequence 回退；若未来需要跨实例租约，再单独版本化协议。
19. `CoreCommandDelta` 在一次命令收尾阶段生成 changed users/orders/liquidations/treasury/triggers 视图，export 直接消费同一批事实；settlement/liquidation 的活动订单定位使用 `ActiveOrderIndex`，不再扫描 reservation 或整本 open-order map。
20. matcher 异常统一经过 `TradingCoreRuntime` owner 校验并 fail-closed；不在失败成员内 rebuild、retry 或 resubmit。settlement 的首批撤单使用 symbol 活动订单索引。
21. `CoreInstrumentState` 在 Core 状态边界再次校验风险档位连续性、最大杠杆和最大持仓名义价值覆盖；`CoreContractMath` 对衍生品开仓保证金和维持保证金按当前名义价值选择 `riskLimitBrackets`，档位边界和超限均 fail-closed。
22. `CoreRiskPolicy.VERSION=1` 在 Core 内统一执行预警/强平状态映射；Risk Provider 删除本地保证金阈值配置和裁决，只展示 Core 风险快照，运行时配置明确标识 `marginPolicySource=AERON_CORE_INSTRUMENT`，规则查询不回传本地可执行阈值。
23. `ActiveOrderIndex` 仅保存 Core 活动订单业务元数据；仪表查询、清算和结算不保存或读取第二本 FIFO，`TradingCoreReducer` 的 instrument/lifecycle 扫描以订单状态或增量索引为准。
24. `RiskSnapshotIndex` 按 userId 增量维护风险快照键，`RISK_STATE_QUERY` 不再为单用户请求遍历全局快照；快照恢复或 lineage 不可用时才全量重建。
25. 新增 `scripts/aeron-core-local.sh` 作为显式产品线的三节点本地启停入口，支持 build/up/down/status/smoke 和受控 node stop，拒绝未知产品线且默认不删除 Docker volume；`fresh` 会打印并只删除目标 compose project 的三节点 volume。
26. 衍生品加仓保证金按目标仓位总初始保证金计算，扣除已有仓位、待成交挂单和将由平仓释放的保证金后冻结增量；成交时再次按实际成交后的目标仓位补足档位升级差额，反向开仓不会因先释放旧仓位而欠保证金。
27. 风险扫描的标记价名义价值超过最高风险档位时，维持保证金使用最高档位的 `maintenanceMarginRatePpm` 继续计算；下单和仓位限额仍使用严格的档位上限拒绝，二者不混用。
28. 强平与交割/行权结算的活动订单撤销统一使用 `cursor + maxOrders` 有界批次，单命令最多处理 1,024 笔订单；结算在订单阶段完成后再分页处理用户，强平在最后一批撤单后才执行资金结算。进度随 `CoreLiquidationState`、`CoreTreasuryState.LifecycleProgress` 和 snapshot 保存，Liquidation Provider 对不同 action 使用有界 `commandAsync` 并发，单 action 按 cursor 续跑，不建立业务重试队列。
28. 杠杆设置和下单校验均使用对应风险档位的初始保证金率；Risk Provider 的规则 DTO、运行时配置请求和 `risk_admin_rule_overrides` 表不提供本地预警/强平保证金率字段，Core policy 仍是唯一裁决者。
29. matcher 恢复一次性导入原生 module bytes，不创建 place/cancel 命令对象；恢复后开放订单报告以 O(活动订单数) 单次遍历完成精确集合和字段对账，不排序、不重放。
30. `TradingCoreState` 不再保存 `CoreBookState` 或 priority sequence。正常盘口查询来自 exchange-core，活动订单业务定位来自 `ActiveOrderIndex`，两者通过恢复 manifest、完整 engine/book hash 和精确 OPEN 集合互相校验。
31. `TradingCoreState` canonical constructor 不再在缺少 `clientOrderIndex` 时隐式扫描全部订单；权威 transition 缺失索引直接 fail-closed，冷路径必须显式派生索引。
32. `CoreTreasuryState` 对已冻结且已规范化的余额、结算标记和进度 map 直接复用对象；资金/手续费命令不再因重建未变化 treasury map 而使 `changedAssetsSince` 退化为全量扫描。
33. Export changed-entity lineage 不可用时不再扫描 users/orders/treasury/liquidations/triggers 全表；Core 回滚本次 transition 并以 `INVALID_COMMAND` fail-closed，避免性能退化和状态分叉。
34. `TradingCoreRuntime.transition` 在任何派生索引更新前校验 users、orders、client-order、risk、trigger、liquidation、treasury 等 lineage；权威 transition 缺少任一 lineage 立即拒绝。matcher 已提交后的异常升级为 fatal divergence，不伪装成可回滚业务拒绝；全量业务索引 rebuild 只允许恢复/测试冷路径。
35. 新增 `aeron-core-tool.sh` 及九个显式产品线 wrapper：Core 启停、探针、export 状态/失败演练、资金对账、集成 smoke、容量和受控恢复矩阵均绑定同一 compose project；脚本不启动 wallet、不把数据库/Kafka/Provider 流程伪装成内存 Core 已验证能力，并为恢复 hash 和命令参数生成可审计 manifest。
36. Aeron trigger 的 mark-price、trigger-price、维护和持仓归零扫描统一使用降序 triggerOrderId 的有界分页；每页使用上页最后 ID 作为 before cursor，异常 cursor 立即停止并记录，达到页数上限显式告警，不再只扫描第一页或无界追赶。
37. Core 为 `ResultUnknownException` 增加显式只读 `COMMAND_RESULT_QUERY` 协议；结果查询返回原命令的 `commandStatus/resultCode/appliedCommandCount/stateHash/data`，未知 commandId fail-closed，不改变命令重放和幂等语义。
38. `SurprisingClusteredService` 使用有界 pending matching、Cluster timer continuation 和按序完成栅栏；普通下单/撤单/改单、盘口查询、强平、结算以及标记价触发的子单都先提交 exchange-core 异步命令，owner 线程不等待 ring future。存在 pending matching 时 snapshot 明确拒绝，不保存或恢复后重新提交未决命令；落后的结果不会越过前序命令。
39. W5 真实运行 A/B 复现嵌入式 Aeron `ThreadingMode.SHARED` 下三个 Core MediaDriver 在正常运行阶段停止心跳；同机切换 Core 和客户端到 `DEDICATED` 后超过相同窗口并连续完成五次状态查询。Core 和客户端默认固定为 `DEDICATED`，仅保留显式环境变量/系统属性用于受控诊断；直连客户端增加后台 keep-alive，避免导出器或故障驱动在 Kafka/PG 等外围等待期间丢失 Aeron 会话；W5 本地编排升级到 PostgreSQL 18 的新版数据目录布局。

仍未宣称完成的交付物：

- P1 的 source epoch registry v2 不作为当前简单设计的生产依赖，进程 epoch 已编码进 sourceId，跨重启不会复用旧 source sequence。P4 的在线触发路径只有 Aeron Core，但 SPOT 和两条永续的真实 Provider 门禁以及 cursor 故障恢复仍未完成。P5 的 Kafka/PG/Projector/Gateway/WebSocket 导出故障语义、慢客户端隔离和资金门禁已由单产品线真实运行完成；Gateway Auth Cluster 与在线 Provider 无数据库启动门禁未完成，所以 P5 保持 `PARTIAL`。P2 采用单写者持有的 persistent `DeltaMap`，保留不可变状态壳但 mutation 只创建 O(delta) lineage。P3/W1/W2 已完成：fork 原生 snapshot、snapshot-only restore、单一 executable book 和 O(活动订单数) 精确对账已落地，`CoreBookState`、priority sequence 和生产 replay/rebuild 均已删除。CommandDelta 的 Core 内单次实体事实组装和 export wire acknowledged cursor 已完成。
- 当前 Core 风险策略是固定代码版本 1；若预警/强平阈值需要动态调整，仍应新增带版本的 Core `RiskPolicy` 状态和命令，由 Core 原子切换并随快照恢复，不能把参数重新放回 Risk Provider。
- 标记价超档位只在风险计算中采用最高档维持保证金率；若业务需要把超档位本身作为立即强平原因，应新增 Core 风险状态字段和版本化策略，不能让 Risk Provider 旁路裁决。
- W1/W2 受影响 reactor 在 JDK 25 下通过 193 个测试；service provenance 正向门禁通过，错误整包 SHA 和错误 fork Git SHA 均被拒绝。完整 root 测试不属于本轮影响面，真实三 Member、Provider、Kafka/PG、做市、资金守恒和 24 小时 soak 仍按 P4-P6 上线门禁执行。

## 19. 本轮验证证据

- `mvn -pl surprising-aeron-core/surprising-aeron-service -am test`：service 95 个测试全部通过，包含异步下单和异步触发 continuation；`COMMAND_RESULT_QUERY` 覆盖超时后结果核验。
- `bash -n scripts/aeron-core-local.sh`、未知 `PRODUCT_LINE` 拒绝和空集群 `status` 验证通过。
- `mvn -pl :surprising-derivatives-lifecycle-provider -am -DskipTests package`：统一 Provider 源码构建通过，覆盖四个领域的 Controller/Service/Repository 和单一生命周期入口。
- `mvn -pl surprising-trading/surprising-trading-provider -am test`：统一 Trading Provider 138 个测试全部通过。
- 三节点 compose 人工烟测：SPOT `spotMatchSmoke=PASS`；LINEAR_PERPETUAL `derivativeSmoke=PASS`；独立产品线门禁对 `LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`OPTION` 均报告 `fundsDiff=0 bookLevels=0`。
- 本轮通过 `PRODUCT_LINE=SPOT scripts/aeron-core-local.sh up` + `smoke` 观察到 `spotMatchSmoke=PASS seller=6000003001 buyer=7000003001 btcTotal=5 usdtTotal=500`，随后用同一入口 `down` 清理容器和网络，未删除 volume。
- 本轮通过 `PRODUCT_LINE=LINEAR_PERPETUAL scripts/aeron-core-local.sh up` + `smoke` 观察到 `derivativeSmoke=PASS productLine=LINEAR_PERPETUAL longUser=6100005001 shortUser=7100005001 usdtTotal=2000 fundingNet=0`，随后用同一入口 `down` 清理容器和网络。
- 本轮通过 `PRODUCT_LINE=LINEAR_PERPETUAL scripts/aeron-core-local.sh fresh` + `status` + `smoke` 观察到三节点均 `Up`，并得到 `derivativeSmoke=PASS productLine=LINEAR_PERPETUAL longUser=6100005001 shortUser=7100005001 usdtTotal=2000 fundingNet=0`；随后使用同一入口 `down` 清理容器和网络，保留数据卷。
- 多个三节点集群并行运行会耗尽 Docker `/dev/shm`，表现为客户端 `ResultUnknown`；停止其他集群后同一 LINEAR_DELIVERY 门禁稳定通过。这是测试环境容量门禁，不能当成业务失败或生产容量结论。
- W5 Aeron 线程模式 A/B 和完整 `LINEAR_PERPETUAL` 导出/投影故障门禁记录在 `.omo/evidence/w5-aeron-threading-ab-20260817.md`；`w5-export-final-1315` 已通过 Kafka/PG/Exporter/Projector/Gateway/WebSocket、重复乱序、Core 独立裁决和资金门禁，`W5_EXPORT_PROJECTION=PASS`。隔离复跑因 Docker Desktop 停止未完成，不影响已通过的功能门禁。
- `CoreInMemoryBenchmark 200 20`：`PASS`，本轮测得约 18.3 orders/s、p50 6.4ms、p95 301ms；该结果包含 exchange-core ring/future 和 export/hash 成本，不能作为生产容量结论，后续 P6 仍需分阶段基准、真实集群长时压测和完整资源指标。
- W4 执行记录：六条 ProductLine Core-only 基线逐条通过；`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION` 已完成真实 HTTP、做市、用户资金与 Treasury 对账门禁，其中 OPTION 使用 `w4-option-final11` 验证六种行权组合且 `FUNDS_DIFFERENCE=0`。SPOT、两条永续及 cursor 重启/缺口仍待真实验证，因此 P4 保持 `PARTIAL`。
- 技术遗留复核：`TradingCoreReducer` 的下单/成交路径、`TradingCoreState` 的 delta lineage、`CoreCommandDelta`、`RollingBusinessStateHash` 和 `CoreProbeState` 已逐项核对。S02/S03/S05/H01/H02/H03 的 `PARTIAL` 原状态属于文档滞后，已更新为 `IMPLEMENTED`；S01/S06 仍为真实遗留，因为用户/订单实体继续使用 immutable record + persistent `DeltaMap`，尚未改为 mutable entity store。相关 reducer/state-map 测试已覆盖 delta、changed keys、显式 client-order index 和 rolling hash。
- canonical wrappers：`bash -n scripts/*.sh` 全部通过；SPOT `integration-smoke.sh` 返回 `spotMatchSmoke=PASS`、`status=OK`、`exportStatus=PASS`；SPOT `live-runtime-trading-reconciliation.sh` 返回 `status=OK` 和 `exportStatus=PASS`；SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION 六条产品线 recovery matrix 均生成 node stop/rejoin/cold restart 相同 hash、`ROLE_EVIDENCE=PASS`、`EXPORT_FAILURE=PASS` 和 `FUNDS_DIFFERENCE=0` 的 manifest；六条产品线各执行 20 秒 fresh `run-product-line-capacity.sh`，均返回 `capacity=PASS` 且 0 failures/fundsDiff=0；`PRODUCT_LINE=SPOT scripts/kafka-trading-smoke.sh` 返回 `kafkaTradingSmoke=PASS productLine=SPOT scope=CORE_INPUT_EXPORT_BRIDGE`。这些是 Core-only/受控本地证据，真实 API/provider/做市/Kafka 集群全链路、生产网络/磁盘故障、长时容量和 projection lag 仍不能由上述结果代替。
- 逐条命令、输出和边界记录在 `.omo/evidence/manual-qa-canonical-core-20260815.md`。

### 19.1 Risk/Liquidation/Insurance/ADL 合并（2026-08-18）

四组 Risk/Liquidation/Insurance/ADL 源码已迁入 `surprising-derivatives-lifecycle`：每条 ProductLine 只启动一个生命周期 JVM（端口 `9087`），保留原有 `/api/v1/risk`、`/api/v1/liquidations`、`/api/v1/insurance` 和 `/api/v1/adl` 路径。业务配置和 Kafka group-id 仍按领域隔离，但 Aeron client pool、数据库/健康检查配置、Instrument snapshot cache/initializer 已统一为一份；状态边界和 Core 裁决权不变。

新项目直接编译四组源码，通过 fully-qualified Bean name 避免领域组件同名冲突，并排除四个旧 Application 类，确保不会重复创建 Spring Context、Feign 注册或 Aeron 客户端。旧模块不再作为生命周期 JAR 依赖；运行脚本和 W5 构建脚本只构建、启动统一 JAR；Funding 保持独立。

验证：`mvn -pl surprising-derivatives-lifecycle -am -DskipTests package` 通过；启动脚本已统一使用 JDK 25 Aeron `--add-exports` 参数。交割、逆向交割和期权已有历史真实门禁；SPOT 与两条永续仍需按既定单产品线顺序执行，且六条固定拓扑脚本都要补留最终回归 manifest，不能以本次构建或 dry-run 替代资金守恒验证。

### 19.2 Matching/Candlestick 合并（2026-08-18）

Matching 行情投影与 Candlestick 已迁入 `surprising-market-data`。`surprising-market-data-api` 是唯一新增
API 子模块，继续承载原 K 线 contract；`surprising-market-data-provider` 直接编译两组 Provider 源码，不依赖
旧 Provider artifact。旧 `surprising-candlestick` 与 `surprising-matching-provider` 模块已经从 reactor 删除。

统一 Provider 按 ProductLine 独立部署，HTTP/Actuator/数据源只保留一份并使用端口 `9081`。外部
`/api/v1/candlestick` 与 `/api/v1/trading/market` 路径保持不变，Gateway、做市与 Feign 调用已统一改到
`9081`。Matching 的 Aeron client、非阻塞 Kafka publisher 与 Candlestick 的 Kafka Streams
application-id/RocksDB/changelog 状态仍按职责隔离；合并 JVM 没有改变 Aeron Core 的唯一订单簿和资金裁决权。
PostgreSQL 仍只承载 K 线历史和可重建查询投影，不进入撮合裁决。

验证：`mvn -pl surprising-market-data/surprising-market-data-provider -am test` 通过，统一 Provider 迁移的
27 个测试及受影响依赖共 162 个测试无失败；`GatewayProxyServiceTest` 28 个路由测试通过。该验证覆盖模块/API
迁移与路由契约，不替代每条 ProductLine 的真实 Kafka Streams state-dir、Aeron 会话和公共行情端到端门禁。

### 19.3 Lifecycle 未使用 JDBC 构造依赖清理（2026-08-18）

`InsuranceService` 已移除未使用的 `InsuranceSequenceRepository` 与
`CoreInsuranceProjectionRepository` 构造参数；`AdlService` 已移除未使用的
`AdlSequenceRepository` 与 `CoreAdlProjectionRepository` 构造参数。四个无调用方的 Repository 类同步删除，
避免 Spring 组件扫描继续创建无业务用途的 JDBC Bean。仍在使用的 liquidation、insurance ledger/coverage、
ADL event 和 risk projection Repository 保留为历史查询路径，本次未将它们误删。

验证：JDK 25 下执行
`mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider -am -DskipTests compile`
通过，统一 Lifecycle Provider 的 52 个生产源码文件重新编译成功；全项目静态搜索不存在上述四个已删除类型的
残余引用。本次只清理无使用依赖，不代表 Risk scan control 或整个 Provider 的 PostgreSQL 解耦已经完成。
