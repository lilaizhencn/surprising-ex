# Surprising-EX 高性能交易核心最终实施规格

> 状态：`IMPLEMENTATION_SOURCE_OF_TRUTH`
> 
> 适用范围：Aeron Cluster、交易核心、exchange-core 0.5.8-emporia、账户资金、订单、风险、触发单、导出、查询、Gateway 和四条产品线。
> 
> 目标：单写者、无锁热路径、减少复制、减少往返、内存裁决、高吞吐、可恢复、资金守恒。
> 
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
| Q01 | `upsertTriggerOrder` 扫全部 trigger 查 client ID | 触发单规模增大后下单成本线性增长 |
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
| M03 | place/cancel 在 matcher 已修改后若业务 reducer 抛错，缺少统一的命令前回滚保护 | Aeron 业务状态与 matcher 可能分叉 |
| M04 | `rebuild` 对所有 open orders 逐个 `.join()` 重放 | 恢复时间随盘口线性增长且高分配 |
| M05 | `orderBookLevels()` 查询所有 symbol、最大深度、逐个 join | 管理查询阻塞 matcher |
| M06 | `ensureSymbol` 使用哈希但不检查碰撞 | 碰撞会把不同 symbol 混入同一盘口 |
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
| D03 | `maintenance`、`onPositionClosed` 仍依赖 TriggerOrderRepository | DB 故障会改变触发单裁决可用性 |
| D04 | `OrderFeeSnapshotLookup` 缺失用户费率时回退 instrument default | 可能产生错误手续费和资金对账差异 |
| D05 | mark price、instrument、fee snapshot 如果陈旧/缺失没有严格版本门禁 | 交易规则不一致 |
| D06 | 订单号生成器仅内存 AtomicReference，跨重启无持久 epoch/租约 | 节点重启或多实例配置错误可能冲突 |
| D07 | 核心结果窗口只有 128，超窗重复请求的语义不够明确 | 客户端可能误把结果未知当失败/新命令 |
| D08 | Export backlog 达上限后整条产品线拒绝命令 | 正确但需要明确告警、drain 和恢复协议，不能无限膨胀 |

## 4. 目标状态和所有权

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

本轮已经落地的护栏和增量改造：

1. `CoreProbeState.placeOrder/cancelOrder/replaceOrder` 在撮合器成功后业务应用失败时恢复命令前的 exchange-core 状态；撮合调用本身失败也会恢复并 fail-closed。
2. `TradingCoreReducer` 的非订单命令统一把 users、orders、clientOrderIndex 作为 delta 传给 `TradingCoreState`，避免无关命令触发全量用户/订单校验；`stampOrderChanges` 在没有订单变化时使用空 changed-id 集合，不再复制和扫描全量订单。
3. `CoreProbeState` 为用户、订单和触发单设置显式 changed-id 集合；资金、清算、结算、余额和仓位命令不再依赖全量 diff。触发单按 symbol 建立可恢复的派生索引，clientTriggerOrderId 使用索引做幂等检查。
4. Aeron gateway source identity 与启动 epoch 分离：同一部署节点使用显式稳定 identity，进程重启获得新 epoch，避免 source sequence 回退后永久被 Core 判为 stale。
5. Aeron 触发单的到期、陈旧 `TRIGGERING` 重试、持仓归零撤单、instrument lifecycle 撤单和 mark-price 候选查询已经从数据库分支切到 Core 查询/命令；新增 `EXPIRE_TRIGGER_ORDER`、`RETRY_TRIGGER_ORDER`。

仍未宣称完成的交付物：

- 触发单 claim、实际订单创建和 complete 仍经过 `OrderRpcApi`，尚未变成同一个 Core 原子命令；这仍是 P4 的下一项资金安全改造，不能以当前 DB-free 状态误认为原子执行。
- `TradingCoreState` 仍是不可变兼容状态壳，delta 深度达到上限时仍会 materialize；P2 的 single-writer mutable runtime、P3 的 exchange-core native restore、P5 的一次编码导出和 P6 的 failover/压测门禁尚未完成。
- 根 Maven 构建当前受工作树中用户已进行的 `surprising-market-maker` 到 `surprising-maker` 重命名影响；本轮使用 `surprising-aeron-core` 和各受影响 provider 的独立 reactor 构建验证，未改动该用户变更。
