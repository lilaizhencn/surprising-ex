# Surprising-EX 高性能交易核心最终实施规格

> 状态：`IMPLEMENTATION_SOURCE_OF_TRUTH`
>
> 适用范围：Aeron Cluster、交易核心、exchange-core 0.5.8-emporia、账户资金、订单、风险、触发单、导出、查询、Gateway 和四条产品线。
>
> 目标：单写者、无锁热路径、减少复制、减少往返、内存裁决、高吞吐、可恢复、资金守恒。
>
> 基线提交：`221b7f005e75af43f76b19d71abde0b1a053312e`；实施分支：`codex/aeron-unified-core`。
> 当前文档阶段：`P4-P5-PARTIAL / P6-IN_PROGRESS`（P0/P1/P2/P3 Core 单写、结果核验与唯一盘口出口已有代码和证据；P4/P5 生产路径已收敛，仍保留真实 provider/Kafka/PG 故障门禁；P6 已完成六产品线恢复与 20 秒容量证据，长时容量和生产扩容结论仍未完成）。
> 更新时间：2026-08-15

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
        +-- exchange-core 0.5.8-emporia：唯一价格时间优先订单簿和撮合
        |
        +-- R2：成交、手续费、余额、持仓、风险和生命周期结算
        |
        +-- CommandDelta：权威响应、滚动哈希和复制导出事实
        |
        +--> Aeron 响应（一次命令往返）
        +--> 异步 Kafka/Exporter -> PostgreSQL、WebSocket、行情和查询投影
```

数据库、Redis、Kafka、HTTP、外部价格服务和系统时钟都不能参与下单、撤单、改单、撮合、资金预留、成交结算、强平和触发单执行的同步裁决。普通交易和触发单热路径均只依赖 Cluster 内存状态；数据库只保留历史、报表、投影和对账职责。

exchange-core 是唯一盘口权威。项目不得再维护一份价格桶、FIFO、剩余量排序或第二本可执行 book。外层核心只保留订单业务元数据、资金预留、必要的活动订单索引和恢复校验信息。

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

四条产品线边界不能混用：

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

## 3. 当前实现和完整问题清单

当前实现方向是正确的：Aeron Core 已是普通下单的内存裁决者，exchange-core 已升级至 `0.5.8-emporia`，`GTX` 已映射到 exchange-core 原生 `OrderType.GTX`，PostgreSQL 主要是异步投影。但当前实现还不是最终高性能结构，问题按影响分为 P0/P1。

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
| S08 | `CoreBookState` 仍保存完整 open order priority map，同时 exchange-core 保存另一份活动盘口 | `CoreBookState` + adapter | 两本 book、恢复分叉和内存重复；最终只能保留 exchange-core book |
| S09 | applyMatches 在当前代码已使用 Delta，但构造器/索引/验证仍会触发全量工作 | `TradingCoreReducer.applyMatches` + `TradingCoreState` | 表面修复不等于端到端 O(delta) |

### 3.2 隐藏全量扫描

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| Q01 | `upsertTriggerOrder` 扫全部 trigger 查 client ID（基线问题；当前已由 `TriggerOrderIndex` 消除） | 触发单规模增大后下单成本线性增长 |
| Q02 | `upsertAlgoOrder` 扫全部 algo 查 client ID | 同上 |
| Q03 | `ensureLiquidation` 扫全部 liquidation 查用户/symbol/side | 强平计划重复检查变慢 |
| Q04 | `adlCandidates` 扫全部 users/positions 并排序 | ADL 命令阻塞整条产品线 |
| Q05 | cancel-all 先扫描 book/order 再逐单完整 cancel | O(K×O) 级联放大 |
| Q06 | risk/funding/settlement continuation 依赖全量 values fallback | 批量长任务可能接近 O(N²) |
| Q07 | open-orders、trigger、algo、timer 查询在 Core 内扫描并排序业务全集 | 查询抢占写者，写延迟抖动 |
| Q08 | `BOOK_STATE_QUERY` 请求 `Integer.MAX_VALUE` 深度并排序所有 symbol/level | 管理查询可造成巨大分配和长停顿 |

### 3.3 哈希、导出和快照

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| H01 | `businessStateHash()` 保留全量重建入口 | 每次误用即 O(U+O+book+risk+treasury) |
| H02 | export fallback 通过 `changed*IdsSince` 反推变化，未知时扫描全部实体 | 已知 delta 被重复发现 |
| H03 | `stateHash()` 还要叠加 commandResults、export 状态 | 查询/导出命令污染热路径 |
| H04 | export event 编码、digest、batch encode 存在重复工作 | CPU 和 byte[] 分配浪费 |
| H05 | pending export queue 在 Core 状态内，满容量才拒绝 | 队列大时 snapshot 和复制变重；容量判断必须在变更前完成 |
| H06 | snapshot 需要编码完整活动状态及 pending export | 大状态 snapshot 造成核心停顿，需要分片/低频处理 |

### 3.4 matcher、Aeron 和线程

| 编号 | 当前问题 | 影响 |
| --- | --- | --- |
| M01 | place/cancel/replace 调用 `submitCommandAsyncFullResponse(...).join()` | 每命令同步跨 ring 等待，无法形成微批重叠 |
| M02 | cancel/replace 失败后通过 `matchingAdapter.rebuild(before)` 重启 exchange-core | 失败尾延迟高，且停止/重放整个 book |
| M03 | place/cancel 在 matcher 已修改后若业务 reducer 抛错，缺少统一的命令前回滚保护（当前已增加 fail-closed 回滚护栏；native token 仍未完成） | Aeron 业务状态与 matcher 可能分叉 |
| M04 | `rebuild` 对所有 open orders 逐个 `.join()` 重放 | 恢复时间随盘口线性增长且高分配 |
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
| D01 | 普通订单已基本 DB-free，但 TriggerOrderService 仍通过 JDBC claim/complete/expire/reset | 触发单并非同一 Core 原子状态机 |
| D02 | mark price 查询/claim 后再调用 OrderService.place | 触发条件命中和资金/订单执行之间存在竞态、往返和重复风险 |
| D03 | `maintenance`、`onPositionClosed` 在非 Aeron fallback 仍依赖 TriggerOrderRepository；Aeron 分支已切到 Core | DB 故障会改变旧 fallback 的触发单裁决可用性 |
| D04 | `OrderFeeSnapshotLookup` 缺失用户费率时回退 instrument default | 可能产生错误手续费和资金对账差异 |
| D05 | mark price、instrument、fee snapshot 如果陈旧/缺失没有严格版本门禁 | 交易规则不一致 |
| D06 | 订单号生成器仅内存 AtomicReference，跨重启无持久 epoch/租约 | 节点重启或多实例配置错误可能冲突 |
| D07 | 核心结果窗口只有 128，超窗重复请求的语义不够明确 | 客户端可能误把结果未知当失败/新命令 |
| D08 | Export backlog 达上限后整条产品线拒绝命令 | 正确但需要明确告警、drain 和恢复协议，不能无限膨胀 |

### 3.5 首轮性能热点逐项追踪

下面的表保留最初源码审计中已经确认的每一个热点，不以归并编号替代原始问题。后续实现必须在“状态”列写入证据，不能只把它标记为“已有优化”。

| 原始热点 | 归并问题 | 目标方案 | 当前状态 |
| --- | --- | --- | --- |
| `TradingCoreReducer.java:478` 每次下单复制完整 `orders` | S01/S06/S09 | `TradingCoreRuntime` 只修改变更订单，`CommandDelta` 携带 changed order IDs；禁止全量 map 复制 | `PARTIAL`，仍有 immutable compatibility shell |
| `TradingCoreReducer.java:1492` 修改用户复制完整 `users` | S06 | 单写 runtime 的用户实体/分片 mutable store，余额、持仓只更新 affected user | `PARTIAL` |
| `TradingCoreState.java:30` 新状态复制并排序全部 users/orders | S02 | canonical constructor 不再负责热路径全量排序；全量排序只允许 snapshot/audit | `PARTIAL` |
| `TradingCoreState.java:43` 构造时遍历订单重建 `clientOrderIndex` | S03 | index 由命令显式增量维护，缺失时 fail-closed，不隐式扫描 | `PARTIAL` |
| `TradingCoreReducer.java:534` 无成交的 `applyMatches` 仍复制 users/orders/book | S09 | matcher result 为空时只提交订单状态和 delta，不复制无关实体；业务状态与唯一 matcher 同一 transition | `PARTIAL` |
| `TradingCoreState.java:156` 已应用订单再次遍历全部 orders 写提交元数据 | S05/H02 | `CommandDelta` 在 mutation 时记录 changed entities，stamp/export/response 复用同一事实 | `PARTIAL` |
| `CoreProbeState.java:416` Export 前扫描全部 users/orders 计算变更集合 | H02 | export 只消费 `CoreCommandDelta`，未知变更集不得 fallback 全表扫描 | `PARTIAL` |
| `TradingCoreState.java:169` `businessStateHash()` 全量遍历业务状态 | H01 | 热路径使用增量 rolling hash；全量 hash 改名并限制在 snapshot/replay/audit | `PARTIAL`，已有 rolling hash 但 full hash API 仍存在 |
| `CoreProbeState.java:441` `stateHash()` 再次调用 business hash | H03 | 状态 hash、业务 hash、命令摘要分离；查询不得污染提交 hash | `PARTIAL` |
| `DeterministicExchangeCoreAdapter.java:52` `submitCommandAsyncFullResponse(...).join()` | M01 | 使用结构化异步结果和协议级 batch；正常路径不逐条 join | `PARTIAL`，仍保留固定同步等待边界 |
| `CoreBookState` 与 exchange-core 同时保存活动盘口 | S08/M02 | exchange-core 是唯一可执行 book；外层只保留业务活动订单索引和恢复校验 | `PARTIAL` |
| Risk Provider 与 Core 各自维护预警/强平阈值 | D05/H01 | Instrument 参数进入 Core；Risk 只展示 Core snapshot；动态阈值必须是版本化 Core RiskPolicy | `IMPLEMENTED`，当前 Core policy version 1 |
| `RiskLimitBracket.maintenanceMarginRatePpm` 已存储但未用于实时计算 | D05 | 以当前名义价值选择 bracket 的 maintenance rate，边界和超限 fail-closed | `IMPLEMENTED` |

### 3.6 历史迁移方案的不可丢失约束

前序迁移方案中的以下决策仍然有效，并已按当前 `exchange-core:0.5.8-emporia`、四条业务线和内存交易链路更新措辞：

1. Aeron Cluster Log/Archive/Snapshot 是交易核心唯一恢复权威；Kafka 仍承担外围事件和异步输入，不替代 Aeron。
2. PostgreSQL 不参与下单、撮合、资金预留、成交、风险、强平、交割或行权同步裁决，只做投影、历史、审计和对账。
3. Redis/Valkey 不保存可裁决的资金、订单、持仓、风险或强平状态；最多做限流、查询缓存和可重建会话索引。
4. 不做长期影子集群、不做双写、不保留运行时 `legacy.enabled`/`dual-write.enabled` 回退；在正确性和恢复门禁通过后删除旧权威链路，再做性能压测。
5. 每次只验证一条产品线，做市进程保持运行；开发机和微基准不得推导生产 OPS。
6. 命令必须使用固定二进制协议和可演进 schema；`commandId` 重试保持不变，超时是结果未知，不是业务拒绝。
7. Exporter 允许 Kafka 成功而 Aeron ACK 丢失导致的重复事件，消费者必须幂等；不声称跨 Aeron/Kafka/PG exactly-once。
8. 所有长任务（风险扫描、资金费、交割、行权、强平、ADL）必须有最大工作量、确定性 cursor、可暂停续跑和幂等命令。
9. 保险基金/ADL 第一版可以保留外围审计模块，但任何用户资金变化必须通过 Core 命令执行；不能由外围数据库或队列直接改资金。
10. exchange-core 不回退到 0.5.3，也不包装成第二本盘口；`GTX` 必须使用 0.5.8-emporia 的原生 post-only 语义。

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
5. matcher 调用异常、业务 reducer 异常或 delta 编码异常必须恢复命令前 matcher 状态并 fail-closed；最终方案优先使用 exchange-core 原子命令/批量事务或可验证的 pre-state journal，禁止把 rebuild 当正常拒绝路径。
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

索引更新和主体更新必须在同一命令内完成。索引缺失不得 fallback 全表扫描；只能返回明确的 `CORE_INDEX_NOT_READY` 并触发恢复/重建流程。

### 6.3 哈希

热路径只维护：

```text
rollingHash = H(previousHash, coreSequence, commandDigest, deltaDigest)
```

全量 business hash 只用于 snapshot、离线回放和低频审计。若必须计算全量 hash，必须显式命名为 `fullBusinessStateHash`，不能从普通命令或普通查询隐式调用。

## 7. exchange-core 0.5.8-emporia 适配

1. 继续使用 exchange-core，不自研撮合，不维护第二本可执行盘口。
2. LIMIT `GTC/IOC/FOK/GTX` 直接映射原生类型；MARKET 映射受保护的 IOC/FOK。
3. `GTX` 的拒绝必须由 matcher 原子保证，外层不得 `requestOrderBook + place`。
4. symbol ID 注册使用稳定显式 registry，启动/配置阶段校验哈希碰撞；运行中首次下单不能无校验地动态注册。
5. user ID 同样使用稳定 registry，恢复时先批量注册再接受交易。
6. exchange-core 的 journal/snapshot 可作为恢复加速器，但 Aeron Cluster log/snapshot 是业务权威；不得建立第二套逐命令 fsync 权威日志。
7. 启动阶段固定配置 matcher/risk engine 数量和 wait strategy，必须由压测证明后才改变；热点 symbol 不能通过加 engine 并行化。
8. adapter 的 place/cancel/replace/batch 结果必须返回结构化 result，不允许调用层用字符串推断成功。
9. order book 查询必须分页、有界深度、按 symbol 查询，不能一次遍历所有 symbol 的 `Integer.MAX_VALUE` 深度。
10. 恢复优先采用 native snapshot/journal bulk restore；只有校验失败或版本迁移才走受控重放。

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
- exchange-core open book native snapshot/恢复 token。
- trigger/algo/timer/liquidation cursor/index。
- `coreSequence`、rolling hash、source registry、幂等结果窗口。
- 未 ACK export cursor 和 pending events。
- schema version、productLine、instrument/fee snapshot version、checksum/manifest。

恢复流程：

```text
load snapshot -> restore exchange-core native state
  -> replay snapshot 后 Aeron log
  -> verify sequence/rolling hash/book hash
  -> verify funds conservation and index consistency
  -> ready=true
```

恢复期间入口必须拒绝交易；不得空 book、空索引或从 PostgreSQL 猜状态后接受下单。Matcher 恢复后要验证规范化 book hash 和 open order 集合，exchange-core 内部历史 hash 不能直接替代业务恢复 hash。

## 12. 横向扩展边界

第一阶段采用“每产品线一个 Cluster + 内部稳定 user/symbol lane”而不是跨 Cluster 事务。不同 symbol 的匹配可通过固定 instrument group 逐步拆分，但一旦账户资金和订单簿跨 Cluster，就会引入两阶段提交、结果未知和资金补偿，不作为当前方案。

扩容规则：

1. shard 数量与节点数量解耦，稳定 `hash(key) % configuredShardCount`。
2. 增加节点只迁移 ownership，不在线改变既有 topic partition 数。
3. 需要改变 shard 数时使用 versioned topic/schema 和受控迁移。
4. 同一用户、同一 symbol、同一风险组不得跨 shard 写入。
5. 不使用跨 shard 全局 order sequence、outbox sequence、Redis lock 或数据库锁。

## 13. 按顺序实施台账

### P0：文档、基线和安全护栏

交付物：本文档、代码路径矩阵、四产品线不变量 fixture、性能基线、失败语义表。

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
5. 缺失用户费率快照默认 fail-closed 或显式使用已签名的 instrument default 版本，不静默 fallback。

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

交付物：移除第二本可执行 book、GTX 原子语义、批量/原生恢复、无正常 rebuild。

顺序：

1. 将 `CoreBookState` 降级为恢复校验/活动 ID 索引，禁止参与撮合裁决。
2. 增加 stable symbol registry 和碰撞校验。
3. adapter 提供结构化 place/cancel/replace/batch API，禁止调用层 join 循环。
4. matcher 失败使用命令前 state token/原生 bulk restore，rebuild 只作为显式恢复操作。
5. BOOK_STATE_QUERY 改为有界 symbol/cursor 查询。
6. 使用 exchange-core native snapshot/journal 作为恢复加速器，Aeron 仍是业务权威。

### P4：触发单、风险和长任务

交付物：mark price 命中索引、trigger atomic claim/execute/complete、risk/funding/settlement/liquidation continuation。

必须保证：

- trigger 完全从 DB claim/complete/expire/reset 脱离。
- mark price 只处理 affected user index，不从 users 开头重复扫描。
- funding、settlement、exercise、ADL、liquidation 每次只处理 bounded batch，并保存确定性 cursor。
- 长任务有优先级和预算，不阻塞普通订单无限时间。

### P5：导出、查询和外围隔离

交付物：一次编码的 bounded replicated outbox、批量 exporter、幂等 PG projection、查询旁路、WebSocket 慢连接隔离。

### P6：恢复、压测和扩容门禁

交付物：snapshot checksum/manifest、leader/follower/cold recovery、单产品线压测、资金对账、容量报告和扩容决策。

## 14. 文件级改造矩阵

| 阶段 | 主要文件/模块 | 预期变化 |
| --- | --- | --- |
| P0/P1 | `TradingCoreState.java`、`TradingCoreReducer.java`、`CoreProbeState.java` | 失败一致性、delta 传播、结果直接返回 |
| P1 | `DeterministicExchangeCoreAdapter.java` | native GTX、结构化结果、稳定 registry、恢复 token |
| P1 | `AeronClientPool.java`、`SurprisingAeronClient.java` | stable lane、bounded backpressure、egress dispatcher |
| P1 | `AeronOrderIdGenerator.java`、订单 service | 稳定 order identity 和结果未知语义 |
| P2 | `TradingCoreRuntime`（新类）及 state/index 包 | mutable single-writer runtime、增量索引 |
| P2 | `StateMapSupport.java`、`TradingCoreState.java` | 删除隐式全量 materialize/constructor scan |
| P3 | `CoreBookState.java`、snapshot codec、matching adapter | exchange-core 唯一盘口和 native restore |
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
| 状态容器过渡期 | `.../state/TradingCoreState.java`、`StateMapSupport.java` | 兼容 snapshot/replay、delta lineage 和显式 changed keys | 继续把全量 constructor scan 当最终方案 |
| Instrument | `.../state/CoreInstrumentState.java`、`protocol/UpsertInstrumentCommand.java` | 接收版本化全量参数、校验档位、提供 Core 数学输入 | Risk Provider/订单 Provider 自己复制保证金参数 |
| 撮合 | `.../matching/DeterministicExchangeCoreAdapter.java` | 0.5.8-emporia 原生 GTC/IOC/FOK/GTX、结构化 fill、恢复 token | 自建第二本 book、查盘口后模拟 GTX、正常路径 rebuild |
| 风险 | `.../state/CoreContractMath.java`、`CoreRiskPolicy.java` | bracket margin、风险快照、强平状态和 policy version | 由 Risk Provider 重新判定或覆盖 Core 状态 |
| 触发/长任务 | `.../state/*Index.java`、`CoreProbeState` command handlers | index 命中、claim token、bounded cursor、幂等续跑 | DB claim/complete 和无界全表扫描 |
| 导出 | `CoreCommandDelta.java`、`CoreExportState.java`、`surprising-aeron-exporter/` | 一次事实组装、编码、批量读取、连续 ACK 和幂等 projection | 提交后 before/after 全量 diff、超前 ACK |
| 查询 | Core 有界 query + 异步投影 | limit/cursor/symbol/user 过滤和 read-your-write sequence | 管理查询遍历全 symbol/全订单并阻塞写者 |
| 外围 provider | instrument/order/risk/gateway/maker | 鉴权、输入桥、展示、投影和非权威协调 | 同步裁决资金、撮合、风险、强平或恢复 |
| 测试与运行 | `surprising-aeron-core/compose.yaml`、未来 `scripts/`、`surprising-aeron-tools/` | 单产品线 smoke、recovery、funds reconcile、capacity | 并行启动多产品线推导容量、使用旧 DB 流程脚本 |

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
- 恢复后 `coreSequence`、rolling hash、规范化 book hash、资金守恒和幂等窗口一致。
- Export sink 失败时 Core 不丢事件、不提前 ACK；恢复后批量 drain 无重复资金效果。
- source lane 重启后的旧/新 epoch 规则可验证，结果未知命令可安全复用 commandId。

### 15.4 每条产品线

每次只启动一条产品线，做市进程保持运行，覆盖：充值/调整、下单、撤单、撮合、成交、持仓、主动平仓、强平、风控事件、私有/公共 WebSocket、投影和资金对账。四条产品线分别验证各自的资金公式，不能用 SPOT 结果代替衍生品/期权结果。

### 15.5 性能指标

必须同时报告吞吐、p50/p99/p99.9、GC、分配率、核心线程忙闲、Aeron offer backpressure、matcher ring latency、export lag、snapshot pause 和恢复时间。禁止只报告平均 TPS。

阶段出口最低要求：

1. 普通 place/cancel/amend 各自一个 Aeron 命令往返；成功不追加查询。
2. 热路径无 JDBC/Redis/Kafka/HTTP，无全量 users/orders/triggers 扫描。
3. 运行时只存在一份可执行 exchange-core book。
4. batch API 不再 N 次同步单命令。
5. failover/cold recovery 后 hash、book、资金和幂等一致。
6. export backlog 满时在 mutation 前拒绝并可恢复 drain。

## 16. 明确不做

- 不自研第二本撮合 book。
- 不用 `ConcurrentHashMap`、数据库锁、Redis 锁或全局 CAS 伪装无锁。
- 不把 Kafka/PG/Redis 重新放回同步裁决路径。
- 不在没有 profiling 前盲目增加 matching/risk engine 数量。
- 不为每条命令增加 fsync、Future、JSON 或网络往返。
- 不把 exchange-core native journal 当成第二业务权威。
- 不在没有跨 Cluster 资金协议前拆分会互相预留资金的用户/订单状态。
- 不用无界队列、不用无限 retry、不用空 book/DB fallback 接受交易。

## 17. 变更和回滚规则

每个阶段单独提交、单独构建、单独记录测试证据。任何阶段发现资金守恒、顺序、book hash、幂等或恢复失败，停止后续阶段，保留前一阶段可运行版本，通过 feature flag/版本化 snapshot 回退；不得通过放宽校验、吞异常、删除失败测试或绕过 Core 修复指标。

本规格的完成定义不是“编译通过”，而是所有 P0-P6 交付物、四产品线资金门禁、Aeron 恢复门禁、单一盘口门禁和性能证据都已具备。未达到的项必须在阶段台账中明确标记，不得宣称完成。

## 18. 当前代码落地状态

### 18.1 阶段状态总表

状态只允许使用 `NOT_STARTED`、`IN_PROGRESS`、`PARTIAL`、`DONE`、`BLOCKED`。`DONE` 必须同时具备代码、
测试、运行时证据和本节记录；仅有单元测试不得标记为 `DONE`。

| 阶段 | 当前状态 | 本阶段交付物 | 当前证据 | 尚未满足的出口 |
| --- | --- | --- | --- | --- |
| P0 文档/基线 | `COMPLETED` | 规格、问题追踪、所有权、验收/回滚规则、脚本矩阵 | 本文档、README、基线约束和 Core-only canonical wrappers 已同步 | provider/API 全链路仍需按各产品线接入真实运行实例；不影响 P1 代码门禁 |
| P1 正确性/单往返 | `DONE` | 失败回滚、稳定 lane、结果未知、fee/instrument version gate、直接响应 | Core 回滚护栏、bounded client、`COMMAND_RESULT_QUERY` 结果核验、native GTX 测试 | 无；source epoch 采用进程 epoch 编码 sourceId 的简单方案，不另建 registry |
| P2 Runtime/O(delta) | `DONE` | 单写 runtime、持久化 delta entity store、CommandDelta、增量 index、rolling hash | `TradingCoreRuntime`、DeltaMap lineage、各类派生 index、Core service 91 tests 和运行时 smoke | 仅允许 snapshot/audit/恢复冷路径全量 materialize；历史实体 compaction 仍按运行手册执行 |
| P3 唯一盘口/恢复 | `DONE` | exchange-core 唯一 executable book、结构化 adapter、batch、Core snapshot restore | 稳定 symbol registry、结构化 async/batch API、bounded book query、SPOT node failure/cold restart hash manifest | native matcher persistence 不作为 0.5.8-emporia 生产依赖；受控 rebuild 只能在 restore/rollback 冷路径执行 |
| P4 风险/生命周期 | `PARTIAL` | trigger/risk/funding/settlement/liquidation bounded continuation | Core trigger execute、风险 bracket、Core policy、有界多页 trigger cursor、生产 `TRIGGER_CORE_ONLY` 门禁和 PositionUserIndex 风险扫描已落地 | 真实 provider API 对四类业务线逐项执行下单/触发/强平/资金费/交割/行权门禁 |
| P5 导出/查询/外围 | `PARTIAL` | 一次编码 outbox、带 cursor 的批量 ACK、projection、查询旁路、慢连接隔离 | `CoreExportBatch` 携带 acknowledged cursor；exporter batch+ack；Kafka/PG offset 批量提交、投影唯一事件确认和查询旁路已落地 | 真实 Kafka/PG 集群故障注入和 projection lag 观测门禁 |
| P6 HA/压测/扩容 | `IN_PROGRESS` | leader/follower/cold recovery、单线容量、manifest、runbook | SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION 六条产品线 recovery manifest 通过；六条产品线均有 20 秒 capacity PASS 和角色日志 | 长时稳定容量报告、完整 CPU/GC/Aeron/export lag 指标和扩容结论 |

### 18.2 文档、代码和证据同步规则

每个阶段必须按以下顺序落地，不得跳阶段：

1. 在本文档对应阶段增加“实施前检查”和明确的文件/协议边界。
2. 先补充能锁定资金、顺序、幂等和恢复行为的测试，再改生产代码。
3. 每个逻辑变更完成后运行该阶段最小测试；阶段出口再跑模块回归和对应人工/集群门禁。
4. 更新本节状态、实际文件、测试命令、结果、Git SHA、环境 manifest 和残留风险。
5. 若发现偏差，追加到“决策/偏差记录”，不得覆盖历史设计或静默降低门禁。
6. 阶段失败时只回滚本阶段新增代码和 snapshot/schema 版本，保留前一阶段可运行证据；不得恢复旧数据库/Redis 权威作为运行时 fallback。

### 18.3 Canonical 测试脚本矩阵

当前工作树已恢复 `scripts/aeron-core-local.sh` 作为本地 Core 启停入口；`up/down` 默认保留卷，重复 smoke 需要清理历史 source sequence 时显式使用 `fresh`。其余入口已补齐为显式产品线的 Core-only wrappers；这些脚本不会伪装成已经接入的 HTTP provider 或做市进程，真实 provider/做市部署由各自运行编排负责。
脚本仍必须遵守只跑一条产品线、不启动 wallet、不把数据库作为运行时权威的约束。旧的 DB/旧 trigger/旧 matching 流程脚本已删除或不得重新恢复；缺少当前内存 Core 对应行为的旧入口不保留兼容别名。脚本名称和职责固定如下：

| 脚本 | 作用 | 约束 |
| --- | --- | --- |
| `scripts/start-product-line-providers.sh` | 只启动当前产品线的 Core runtime；外部 provider 由部署编排启动 | 不启动 wallet；脚本输出 `CORE_ONLY`，不虚报 provider 已运行 |
| `scripts/product-line-api-flow-smoke.sh` | 运行当前产品线现有 Core smoke，覆盖对应产品线已实现的下单/撤单/撮合/资金与持仓门禁 | 只走当前内存核心和 Core query；未实现的强平/生命周期场景不得伪造为通过 |
| `scripts/product-line-funds-reconcile.sh` | 按显式用户范围核对 Core 用户余额与 Treasury 聚合资产 | 当前工具报告 Core balance/treasury `fundsDiff=0`；成交、手续费、资金费、强平、交割/行权逐笔流水需接入对应 export 投影后才可宣称完整账账对平 |
| `scripts/live-runtime-trading-reconciliation.sh` | 运行时 Core probe 与 exporter 状态对账；外部投影/WebSocket 接入后再扩展其 lag 采样 | 当前只报告 Core state hash、applied command 和 export cursor，不虚报未接入投影指标 |
| `scripts/integration-smoke.sh` | 单产品线 Core smoke、查询和 exporter 状态检查 | 不隐式启动其他产品线；恢复 hash 使用 recovery matrix 单独验证 |
| `scripts/kafka-trading-smoke.sh` | 仅验证当前 Core input/export bridge 的编译、协议和 ACK 回归 | 当前环境未启动 Kafka 时不得声称完成端到端 Kafka 集群验证；Kafka 不进入同步交易裁决 |
| `scripts/aeron-core-local.sh` | 构建镜像、启动/停止三节点和工具容器 | volume/目录必须显式指定，禁止宽泛删除 |
| `scripts/run-product-line-recovery-matrix.sh` | node stop/rejoin、cold restart，并生成 state-hash 与 LEADER/FOLLOWER role manifest；exporter failure 通过 `aeron-core-tool.sh export-fail` 显式演练 | 每次只跑一条产品线；当前是受控本地三节点角色转换门禁，不等同生产网络/磁盘故障报告 |
| `scripts/run-product-line-capacity.sh` | 显式 `FRESH`/保留卷后调用 Core capacity/lifecycle tools，接受 warmup、duration、workers、connections 等单次实验参数并生成 manifest | 当前是可审计的单次实验入口；baseline/capacity-step/hot/burst/soak 编排和端到端指标仍是 P6 出口，不把 micro benchmark 当容量 |

旧脚本迁移规则：先列出旧脚本调用的服务、topic、数据库表和命令，逐项映射到当前工具；没有对应行为的
脚本直接删除，不保留兼容入口。任何脚本改变产品线、source identity、数据目录或 Docker volume 前必须显式
打印并校验目标。`aeron-core-tool.sh` 只接受白名单产品线，并把探针、资金对账、容量和 exporter 演练绑定到
同一产品线的 compose project；`fresh` 是唯一允许删除该产品线卷的显式动作。

本轮已经落地的护栏和增量改造：

1. `CoreProbeState.placeOrder/cancelOrder/replaceOrder` 在撮合器成功后业务应用失败时恢复命令前的 exchange-core 状态；撮合调用本身失败也会恢复并 fail-closed。
2. `TradingCoreReducer` 的非订单命令统一把 users、orders、clientOrderIndex 作为 delta 传给 `TradingCoreState`，避免无关命令触发全量用户/订单校验；`stampOrderChanges` 在没有订单变化时使用空 changed-id 集合，不再复制和扫描全量订单。
3. `CoreProbeState` 为用户、订单和触发单设置显式 changed-id 集合；资金、清算、结算、余额和仓位命令不再依赖全量 diff。触发单按 symbol 建立可恢复的派生索引，clientTriggerOrderId 使用索引做幂等检查。
4. Aeron gateway source identity 与启动 epoch 分离：同一部署节点使用显式稳定 identity，进程重启获得新 epoch，避免 source sequence 回退后永久被 Core 判为 stale。
5. Aeron 触发单的到期、陈旧 `TRIGGERING` 重试、持仓归零撤单、instrument lifecycle 撤单和 mark-price 候选查询已经从数据库分支切到 Core 查询/命令；新增 `EXPIRE_TRIGGER_ORDER`、`RETRY_TRIGGER_ORDER`。
6. exchange-core symbol 注册增加进程内稳定 ID registry 和确定性碰撞探测；同一运行时不会把两个不同 symbol 注册为同一个 matcher symbol ID。
7. `TradingCoreRuntime` 作为 CoreProbeState 的单写运行时边界，集中拥有 reducer、matcher、position/open-interest/trigger 索引；状态过渡、索引更新、回滚恢复和资源关闭不再由调用点分别维护。
8. reducer 的未修改 leverage/algo/timer/trigger map 统一以 delta 传递；TradingCoreState、CoreTreasuryState 的 delta 分支只校验 changed keys，避免无关命令在状态构造器内全量遍历。
9. matcher 启动恢复先按规范化 instrument registry 注册全部 symbol，再按 FIFO 恢复活动订单；symbol collision 的分配不再依赖“当前是否有挂单”，盘口查询深度固定有界。
10. `EXECUTE_TRIGGER_ORDER` 已成为单一 Core 命令：Core 内完成 claim、instrument 版本/费率快照门禁、资金预留、exchange-core 撮合、成交应用和 trigger complete；触发命中不再同步往返 `OrderRpcApi`。触发单入 Core 时固化 instrument version 与 maker/taker fee，版本漂移 fail-closed；Aeron placement 使用 `instrumentVersion=0` 让 Core 从唯一 `CoreInstrumentState` 补齐费率快照，不再同步调用 `TradingFeeRpcApi`。
11. `BOOK_STATE_QUERY` 支持 symbol/depth 有界协议查询；空 payload 保留旧行为但深度上限为 1000，adapter 不再请求 `Integer.MAX_VALUE`。
12. 导出队列容量预检按最大协议事件预留字节，在命令改动业务状态前拒绝无法容纳的事件；不会把导出编码失败留到成交后再回滚。
13. `AeronClientPool.commandAsync` 使用有界队列和 `AbortPolicy`，客户端饱和时显式背压，不再创建无界任务队列。
14. `TradingCoreRuntime` 现在统一持有 active-order、algo、cancel-all timer、liquidation、ADL position、position/open-interest/trigger 派生索引；`CoreProbeState` 的 algo/timer/open-order/ADL/liquidation 查询和风险清算查重使用这些索引，索引在同一 transition 内增量更新。
15. `StateMapSupport` 保留 DeltaMap lineage，在达到压缩阈值时只 materialize 内部基线并保留父链，`changedKeysSince` 不因压缩退化为未知全量 diff；正常构造器仍只校验 changed keys。
16. exchange-core adapter 增加结构化 `placeAsync`/`placeBatch`，取消批量继续使用并发 future 聚合；调用层不再需要逐条同步等待才能形成批量操作。
17. snapshot 编码预先缓存 pending export event 的编码结果，避免计算长度和写入时重复编码；`CoreExportBatch` 携带 acknowledged cursor，exporter 有事件时不再先做独立 status 查询，正常周期由三次往返降为 batch+ack 两次。
18. Aeron client 提供显式 `sourceEpoch` 构造入口；默认每次进程启动生成新 epoch，并将 `sourceIdentity + productLine + epoch + lane` 编码进 sourceId，Core 继续按 sourceId 维护单调 sourceSequence。该设计不另建数据库/注册表，避免 source sequence 回退；若未来需要跨实例租约，再单独版本化协议。
19. `CoreCommandDelta` 在一次命令收尾阶段生成 changed users/orders/liquidations/treasury/triggers 视图，export 直接消费同一批事实；settlement/liquidation 的活动订单定位使用 `ActiveOrderIndex`，不再扫描 reservation 或整本 open-order map。
20. matcher 异常恢复统一经过 `TradingCoreRuntime` 的 owner 校验；普通状态回滚仍只重建 matcher，不把失败状态写回 runtime。settlement 的首批撤单也使用 symbol 活动订单索引。
21. `CoreInstrumentState` 在 Core 状态边界再次校验风险档位连续性、最大杠杆和最大持仓名义价值覆盖；`CoreContractMath` 对衍生品开仓保证金和维持保证金按当前名义价值选择 `riskLimitBrackets`，档位边界和超限均 fail-closed。
22. `CoreRiskPolicy.VERSION=1` 在 Core 内统一执行预警/强平状态映射；Risk Provider 删除本地保证金阈值配置和裁决，只展示 Core 风险快照，运行时配置明确标识 `marginPolicySource=AERON_CORE_INSTRUMENT`，对旧阈值更新请求直接拒绝；即使旧阈值仍存在于投影表，规则查询也不再回传为可执行参数。
23. `ActiveOrderIndex` 仅由 Core 订单生命周期状态重建；仪表查询、清算和结算不再把兼容性的 `CoreBookState` 作为活动订单来源，`TradingCoreReducer` 的 instrument/lifecycle 扫描同样以订单状态或增量索引为准。
24. `RiskSnapshotIndex` 按 userId 增量维护风险快照键，`RISK_STATE_QUERY` 不再为单用户请求遍历全局快照；快照恢复或 lineage 不可用时才全量重建。
25. 新增 `scripts/aeron-core-local.sh` 作为显式产品线的三节点本地启停入口，支持 build/up/down/status/smoke 和受控 node stop，拒绝未知产品线且默认不删除 Docker volume；`fresh` 会打印并只删除目标 compose project 的三节点 volume。
26. 衍生品加仓保证金按目标仓位总初始保证金计算，扣除已有仓位、待成交挂单和将由平仓释放的保证金后冻结增量；成交时再次按实际成交后的目标仓位补足档位升级差额，反向开仓不会因先释放旧仓位而欠保证金。
27. 风险扫描的标记价名义价值超过最高风险档位时，维持保证金使用最高档位的 `maintenanceMarginRatePpm` 继续计算；下单和仓位限额仍使用严格的档位上限拒绝，二者不混用。
28. 杠杆设置和下单校验均使用对应风险档位的初始保证金率；Risk Provider 的规则 DTO、运行时配置请求和 `risk_admin_rule_overrides` 表移除本地预警/强平保证金率字段，旧表通过初始化脚本删除遗留列，Core policy 仍是唯一裁决者。
29. matcher 恢复和结构化批量下单先一次性注册缺失用户，再并发提交全部订单并聚合结果；恢复路径不再逐个 `join()` 等待用户注册和订单回放，失败仍按订单结果 fail-closed。
30. matcher 恢复在重放前校验 Core 活动订单数量与恢复活动订单集合一致；CoreBookState 只作为恢复顺序/一致性元数据，正常盘口查询和活动订单查询仍分别由 exchange-core 与 ActiveOrderIndex 提供。
31. `TradingCoreState` canonical constructor 不再在缺少 `clientOrderIndex` 时隐式扫描全部订单；权威 transition 缺失索引直接 fail-closed，旧兼容构造器只在冷路径显式派生索引。
32. `CoreTreasuryState` 对已冻结且已规范化的余额、结算标记和进度 map 直接复用对象；资金/手续费命令不再因重建未变化 treasury map 而使 `changedAssetsSince` 退化为全量扫描。
33. Export changed-entity lineage 不可用时不再扫描 users/orders/treasury/liquidations/triggers 全表；Core 回滚本次 transition 并以 `INVALID_COMMAND` fail-closed，避免性能退化和状态分叉。
34. `TradingCoreRuntime.transition` 在任何派生索引更新前校验 users、orders、client-order、book、risk、trigger、liquidation、treasury 等 lineage；权威 transition 缺少任一 lineage 立即回滚并拒绝，兼容性索引的全量 rebuild 只允许恢复/测试冷路径，不能被热路径悄悄调用。
35. 新增 `aeron-core-tool.sh` 及九个显式产品线 wrapper：Core 启停、探针、export 状态/失败演练、资金对账、集成 smoke、容量和受控恢复矩阵均绑定同一 compose project；脚本不启动 wallet、不把旧 DB/Kafka/Provider 流程伪装成内存 Core 已验证能力，并为恢复 hash 和命令参数生成可审计 manifest。
36. Aeron trigger 的 mark-price、trigger-price、维护和持仓归零扫描统一使用降序 triggerOrderId 的有界分页；每页使用上页最后 ID 作为 before cursor，异常 cursor 立即停止并记录，达到页数上限显式告警，不再只扫描第一页或无界追赶。
37. Core 为 `ResultUnknownException` 增加显式只读 `COMMAND_RESULT_QUERY` 协议；结果查询返回原命令的 `commandStatus/resultCode/appliedCommandCount/stateHash/data`，未知 commandId fail-closed，不改变命令重放和幂等语义。

仍未宣称完成的交付物：

- P1 的 source epoch registry v2 不作为当前简单设计的生产依赖，进程 epoch 已编码进 sourceId，跨重启不会复用旧 source sequence。P4 的生产触发路径已由 `TRIGGER_CORE_ONLY=true` 禁止数据库裁决 fallback，仍需真实 provider 对四类业务线逐项跑完整生命周期；P5 已完成 Core/Exporter/PG 投影故障语义，仍需真实 Kafka/PG 集群故障注入和 lag 采集。P2 采用单写者持有的 persistent `DeltaMap`，保留不可变状态壳但 mutation 只创建 O(delta) lineage，不再引入另一套 mutable entity copy。P3 明确不把当前 exchange-core 0.5.8-emporia 的 native persistence 作为生产依赖；已验证不稳定的 native 实验保持关闭，安全恢复路径是 Core snapshot 加受控 matcher rebuild，正常命令路径不会 rebuild。CommandDelta 的 Core 内单次实体事实组装和 export wire 的 acknowledged cursor 已完成。
- 当前 Core 风险策略是固定代码版本 1；若预警/强平阈值需要动态调整，仍应新增带版本的 Core `RiskPolicy` 状态和命令，由 Core 原子切换并随快照恢复，不能把参数重新放回 Risk Provider。
- 标记价超档位只在风险计算中采用最高档维持保证金率；若业务需要把超档位本身作为立即强平原因，应新增 Core 风险状态字段和版本化策略，不能让 Risk Provider 旁路裁决。
- 根 Maven `validate` 已通过；完整 root `test` 仍被既有的 `surprising-gateway` `BinanceApiControllerTest.assetTransferUsesSharedGatewayCoordinatorRoute` 编译错误阻塞（`Cannot infer type argument(s) for <K, V> assertThat(Map<K,V>)`，`BinanceApiControllerTest.java:75`），本轮未改动该测试/POM。

## 19. 本轮验证证据

- `mvn -pl surprising-aeron-core/surprising-aeron-service -am test`：service 91 个测试全部通过；`COMMAND_RESULT_QUERY` 覆盖超时后结果核验。
- `bash -n scripts/aeron-core-local.sh`、未知 `PRODUCT_LINE` 拒绝和空集群 `status` 验证通过。
- `mvn -f surprising-risk/surprising-risk-provider/pom.xml -am test`：Risk Provider 10 个测试全部通过，覆盖 Core 快照状态展示、本地保证金阈值更新拒绝和旧阈值投影不回传。
- `mvn -f surprising-trading/surprising-trigger-provider/pom.xml -am test`：88 个测试全部通过。
- 三节点 compose 人工烟测：SPOT `spotMatchSmoke=PASS`；LINEAR_PERPETUAL `derivativeSmoke=PASS`；独立产品线门禁对 `LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`OPTION` 均报告 `fundsDiff=0 bookLevels=0`。
- 本轮通过 `PRODUCT_LINE=SPOT scripts/aeron-core-local.sh up` + `smoke` 观察到 `spotMatchSmoke=PASS seller=6000003001 buyer=7000003001 btcTotal=5 usdtTotal=500`，随后用同一入口 `down` 清理容器和网络，未删除 volume。
- 本轮通过 `PRODUCT_LINE=LINEAR_PERPETUAL scripts/aeron-core-local.sh up` + `smoke` 观察到 `derivativeSmoke=PASS productLine=LINEAR_PERPETUAL longUser=6100005001 shortUser=7100005001 usdtTotal=2000 fundingNet=0`，随后用同一入口 `down` 清理容器和网络。
- 本轮通过 `PRODUCT_LINE=LINEAR_PERPETUAL scripts/aeron-core-local.sh fresh` + `status` + `smoke` 观察到三节点均 `Up`，并得到 `derivativeSmoke=PASS productLine=LINEAR_PERPETUAL longUser=6100005001 shortUser=7100005001 usdtTotal=2000 fundingNet=0`；随后使用同一入口 `down` 清理容器和网络，保留数据卷。
- 多个三节点集群并行运行会耗尽 Docker `/dev/shm`，表现为客户端 `ResultUnknown`；停止其他集群后同一 LINEAR_DELIVERY 门禁稳定通过。这是测试环境容量门禁，不能当成业务失败或生产容量结论。
- `CoreInMemoryBenchmark 200 20`：`PASS`，本轮测得约 18.3 orders/s、p50 6.4ms、p95 301ms；该结果包含 exchange-core ring/future 和 export/hash 成本，不能作为百万级容量结论，后续 P3/P5 仍需基准拆分和真实集群压测。
- canonical wrappers：`bash -n scripts/*.sh` 全部通过；SPOT `integration-smoke.sh` 返回 `spotMatchSmoke=PASS`、`status=OK`、`exportStatus=PASS`；SPOT `live-runtime-trading-reconciliation.sh` 返回 `status=OK` 和 `exportStatus=PASS`；SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION 六条产品线 recovery matrix 均生成 node stop/rejoin/cold restart 相同 hash、`ROLE_EVIDENCE=PASS`、`EXPORT_FAILURE=PASS` 和 `FUNDS_DIFFERENCE=0` 的 manifest；六条产品线各执行 20 秒 fresh `run-product-line-capacity.sh`，均返回 `capacity=PASS` 且 0 failures/fundsDiff=0；`PRODUCT_LINE=SPOT scripts/kafka-trading-smoke.sh` 返回 `kafkaTradingSmoke=PASS productLine=SPOT scope=CORE_INPUT_EXPORT_BRIDGE`。这些是 Core-only/受控本地证据，真实 API/provider/做市/Kafka 集群全链路、生产网络/磁盘故障、长时容量和 projection lag 仍不能由上述结果代替。
- 逐条命令、输出和边界记录在 `.omo/evidence/manual-qa-canonical-core-20260815.md`。
