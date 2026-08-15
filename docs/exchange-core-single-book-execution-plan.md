# Exchange Core 单一盘口与内存交易核心最终方案

## 1. 文档状态

| 字段 | 当前值 |
| --- | --- |
| 文档状态 | `APPROVED_FOR_IMPLEMENTATION` |
| 适用范围 | Aeron Cluster、订单、撮合、账户、风险、清算、导出和查询链路 |
| 当前执行依据 | 本文档 |
| 历史迁移记录 | [Aeron 统一交易核心迁移实施方案](aeron-unified-trading-core-migration-plan.md) |
| 设计目标 | 无锁单写、高吞吐、低分配、低往返、资金安全 |
| 更新时间 | 2026-08-15 |

本文档整理前几轮源码审计、exchange-core 对照审计和本轮架构讨论的最终结论。实现必须按第 15 节顺序推进；未通过对应测试和恢复门禁，不得把阶段标记为完成。

## 2. 最终结论

系统采用“一条产品线、一个确定性内存核心、一份活动盘口、一次命令往返”的模型：

```text
Gateway
  -> Aeron Cluster：排序、复制、故障切换
  -> ProductExecutionCore：单写、无锁、内存状态
       -> R1：风险检查和资金预冻结
       -> exchange-core：唯一订单簿和撮合
       -> R2：成交、持仓、手续费和资金结算
       -> CommandDelta / replicated outbox
  -> 直接返回权威命令结果
  -> 异步 Kafka -> JDBC / WebSocket / 行情 / 查询投影
```

数据库不参与下单、撮合、风险、强平和结算的同步裁决。Gateway 入口只读取本地 JVM instrument、费率和行情快照后发送命令，不在提交前开数据库事务；Aeron Cluster Log、Archive 和 Snapshot 是核心恢复链；Kafka、PostgreSQL 和 WebSocket 是异步外围。

exchange-core 继续负责撮合，并且是唯一盘口权威。项目不再在外层维护第二份完整 `CoreBookState` 盘口；当前只保留活动订单优先级索引，组合 matcher 异常的重放兜底仍是 P3 收尾项。

## 3. 不可改变的架构原则

1. **exchange-core 是唯一盘口。** 价格桶、FIFO、盘口剩余量和撮合顺序不能在 `TradingCoreState` 中重新复制。
2. **核心状态全内存。** 账户、冻结、持仓、风险、资金、强平和触发状态由产品线核心的单写线程维护。
3. **历史数据不进入热状态。** 成交历史和账单由增量事件异步投影；核心保留终态订单身份用于防重，导出 ACK 后删除其零锁 reservation，终态订单主体的容量/retention 仍必须由后续 tombstone 规则收口。
4. **命令只执行一次。** `commandId`、`sourceSequence`、`clientOrderId` 和交易流水必须幂等。
5. **业务拒绝发生在撮合修改前。** 正常拒绝不能触发整本 book rebuild。
6. **核心不做外部 I/O。** 不访问 JDBC、Kafka、Redis、HTTP 或 wall-clock；外部事件先进入 Aeron 顺序流。
7. **不使用共享可变状态加锁。** 核心由单写线程拥有状态；Gateway 和 Exporter 使用有界队列和异步 agent。
8. **只复制 changed entities。** 每条命令产生 `CommandDelta`，禁止提交后扫描全部 users/orders 计算变更。
9. **哈希不在每笔命令上全量执行。** 热路径使用 sequence/delta rolling hash；全量 hash 只在 snapshot、审计和离线回放执行。
10. **先降低复杂度，再调线程。** 在确认 O(本次变更量) 前，不扩大 matching/risk engine 数量，不引入跨 Cluster 分布式事务。

## 4. 当前实现的完整问题清单

### 4.1 状态复制和历史膨胀

| 编号 | 问题 | 源码位置 | 后果 |
| --- | --- | --- | --- |
| P01 | 每次下单复制完整 users/orders | `TradingCoreReducer.placeOrder` | O(U/O) 分配和 GC |
| P02 | `applyMatches` 零成交也复制 users/orders/book | `TradingCoreReducer.applyMatches` | 拒单和无成交命令也付出全量成本 |
| P03 | `TradingCoreState` 构造时重新复制、排序和校验 | `TradingCoreState` canonical constructor | 每轮状态转换再次 O(N log N) |
| P04 | clientOrderIndex 在构造链上重复派生 | `TradingCoreState` overloads | 重复遍历订单 |
| P05 | `CoreUserState` 内部再次复制 balances/reservations/positions | `CoreUserState` constructor | 用户局部状态双重复制 |
| P06 | `CoreBookState`、`CoreRiskState`、`CoreTreasuryState` 重复 normalize/copy | 对应 constructors | 嵌套状态继续放大分配 |
| P07 | 终态 orders、历史 liquidation/trigger/algo 仍按主体保留；零 reservation 已在导出 ACK 后清理 | `TradingCoreReducer` state maps | 主体历史仍可能增长；清理必须先有可恢复的身份 tombstone/retention 规则 |
| P08 | `stampOrderChanges` 再复制完整 orders | `TradingCoreState.stampOrderChanges` | 每笔已应用订单再次 O(O) |
| P09 | 业务状态曾保留第二份 book | `CoreBookState` + exchange-core | 数据重复、恢复和一致性复杂；本轮已降为活动订单优先级索引 |

### 4.2 隐藏扫描和复杂度放大

| 编号 | 问题 | 后果 |
| --- | --- | --- |
| P10 | 版本冲突检查扫描整个 open book（已移除） | 原普通下单依赖盘口规模；instrument 升级边界已保证活动订单版本一致 |
| P11 | position identity 检查扫描全部订单 | 现货路径也可能付出全量扫描 |
| P12 | projected position notional 扫描全部订单且部分路径调用两次 | 衍生品下单风险成本随历史订单增长 |
| P13 | open interest 每次扫描全部 users/positions | 开仓命令随用户数增长；已改为核心内存聚合索引，待 profiling 验收 |
| P14 | reduce-only capacity 扫描全部订单 | 平仓和风控不稳定 |
| P15 | cancel-all 先全量找订单，再逐笔完整 cancel | O(K×O) 级联重建 |
| P16 | liquidation/risk scan 每批从 users 开头重复跳过前缀 | 多批风险扫描接近 O(U²/batch) |
| P17 | funding/settlement 一条命令扫描全部用户和持仓 | 长任务阻塞整个 Cluster service |

### 4.3 哈希、导出、快照和 I/O

| 编号 | 问题 | 后果 |
| --- | --- | --- |
| P18 | Export 前扫描全部 users/orders/liquidations/triggers/treasury 找 changed set | 已知变更被重复发现 |
| P19 | 一条命令至少两次 `businessStateHash()`（热路径已改 rolling） | 原全量状态和盘口排序重复计算；显式 full audit 仍是 O(N) |
| P20 | `CoreBookState.stateHash()` 对 open orders 再排序 | hash 含 O(B log B) |
| P21 | exporter 空闲状态每 10ms 发 status 查询 | 空闲时仍抢占 Cluster lane 并全量 hash |
| P22 | status/batch/ack/status 一轮多次往返 | export 与订单竞争核心线程 |
| P23 | event append、digest、batch size、batch encode 重复编码 | 序列化分配和 CPU 浪费 |
| P24 | snapshot 全量编码、复制 pending、分配大 buffer、同步 CRC | snapshot 造成核心停顿 |
| P25 | Export 容量预估不包含实际 changed entities | 撮合后才发现容量不足并触发 rebuild |
| P26 | Kafka/JDBC 每事件发送、等待、建连接/事务、逐条 UPDATE/INSERT | 外围吞吐低且反压核心 |

### 4.4 Aeron、撮合和线程模型

| 编号 | 问题 | 后果 |
| --- | --- | --- |
| P27 | ClusteredService 同步执行全部业务 | 整条产品线一个串行长临界路径 |
| P28 | 查询也进入共识命令通道 | 查询抢占写入并污染日志 |
| P29 | `submitCommandAsyncFullResponse(...).join()` 每条命令同步等待 | exchange-core pipeline 无法形成批量重叠 |
| P30 | post-only 先 order book query 再 place | 额外 ring/响应往返，且语义分散 |
| P31 | 正常拒绝、Export 失败或校验异常可触发全 book stop/rebuild | 尾延迟和拒绝服务风险 |
| P32 | `AeronClientPool` 用 `synchronized(slot)` 包住阻塞请求 | 默认 4 个 slot 限制在途请求 |
| P33 | 每个 client slot 启动独立 MediaDriver | 线程、内存和 IPC 资源膨胀 |
| P34 | offer 负值无限重试，decode failure 不返回明确错误（已拆分处理） | 原核心线程或客户端 slot 可永久占用；client offer 已分类失败，service egress 已有界排队 |
| P35 | payload/response 多次 clone 和 byte[] allocate | 一次请求产生大量协议分配 |
| P36 | same-user 命令 round-robin 多 session | 并发请求可能重排 |

### 4.5 风险、外部投影和正确性

| 编号 | 问题 | 后果 |
| --- | --- | --- |
| P37 | mark price/risk/funding/settlement 与订单共用无优先级 lane | 长任务可饿死订单 |
| P38 | BigInteger、String.matches、字符串 normalize 进入高频资金路径 | 复制消除后成为下一层分配热点 |
| P39 | WebSocket fanout 单分区、同步 publish、每消息 watchdog | 慢连接和 fanout 放大尾延迟 |
| P40 | market data 先构造完整深度再 coalesce | 无效快照 CPU 已经消耗 |
| P41 | `commandResults` 只有 128 条，`lastSourceSequences` 不清理（已设上限） | 响应窗口仍有界；source registry 达 65,536 时 fail-closed，长期 lease/epoch 清理仍需产品规则 |
| P42 | `stampOrderChanges` 重载没有传 triggerOrders | 订单变化提交可能清空已有 triggerOrders |
| P43 | 数据库/Redis/缓存若被误放入裁决路径 | 故障、延迟和恢复组合爆炸 |
| P44 | 旧 `scripts/matching-engine-benchmark.sh` 引用了不存在的 `ExchangeCoreEngineBenchmark` 类（已替换） | 修复前基准脚本无法执行；现在可产生内存核心基线，但不等同于真实 Cluster 全链路 |

## 5. 目标状态权威

| 状态 | 唯一权威 | 说明 |
| --- | --- | --- |
| 价格档位、FIFO、活动盘口 | exchange-core | 不能在外层再维护 book 排序副本 |
| 订单业务附加信息 | ProductExecutionCore | 只保存活动字段和必要幂等 tombstone |
| available/frozen/position margin | ProductExecutionCore | 单写、定点整数、命令内原子更新 |
| 持仓、PnL、资金费、强平 | ProductExecutionCore | 按产品线隔离模型 |
| trigger/algo/timer | ProductExecutionCore | 使用命中索引，不全量扫描 |
| CommandDelta/outbox | replicated core state | 有界、单调序号、幂等 ACK |
| 历史订单、成交、账单、查询 | Kafka/JDBC projection | 只读、异步、可重建 |
| 顺序、HA、恢复 | Aeron Cluster | Log + Archive + Snapshot 唯一恢复链 |

`OrderBusinessState` 可以保存用户、symbol、产品线、clientOrderId、reservation 和产品字段，但不得重新保存价格树、FIFO 或独立盘口排序。订单剩余量必须以 exchange-core matcher 结果为准，并在同一命令中更新账户业务状态。

## 6. 命令执行协议

```text
Decode
  -> Idempotency
  -> Static/Risk validation
  -> Reserve response/delta/event capacity
  -> R1: reserve user funds and margin
  -> exchange-core: one atomic matching command
  -> R2: consume fills, release holds, update balances/positions
  -> product lifecycle: funding/liquidation/delivery/exercise if applicable
  -> Commit revision + rolling hash + delta/outbox + cached result
  -> single response containing authoritative order/account changes
```

### 6.1 Decode and protocol

- Aeron `DirectBuffer` 只在 callback 内解析到预分配 command slot。
- 核心禁止 payload getter 反复 clone。
- symbol、asset、productLine 在边界转换为稳定整数 ID。
- 使用固定字段编码，事件只编码一次。
- 不在热路径执行 `String.matches`、symbol normalize 或 JSON 序列化。

### 6.2 Idempotency

- Gateway 为每条 lane 使用 `sourceId + epoch + sourceSequence`。
- 核心保存每个活动 source 的最后 sequence 和有界响应 ring。
- 同一 command 重放只能返回原响应。
- `orderId` 由稳定分配器生成，重复 ID 永不重新解释为新订单。
- `orderId/clientOrderId` 在终态订单主体保留期间继续阻止重用；零锁 reservation 只在对应导出序列 ACK 后清理。主体历史改为 tombstone 前，不能静默删除 orders，否则旧命令重放可能被解释为新订单。
- source 清理必须是确定性命令，不能依赖物理 map 的自然增长。

### 6.3 资金安全

所有可预测错误在 matcher 修改前返回。进入 R1 后，撮合和 R2 只能执行已经验证过的整数运算；不在成交中途访问外部资源。

预留必须覆盖本命令的响应、delta 和最大事件容量。容量不足时在任何余额、盘口变化前返回 `CORE_BACKPRESSURED`。未知不变量异常不能 catch 后继续，也不能通过全 book rebuild 掩盖；应停止节点，由 Aeron failover/replay 恢复。

### 6.4 CommandDelta

每条命令直接生成：

```text
coreSequence
commandId
resultCode
changedOrders
changedUsers
executions
positionChanges
treasuryChanges
liquidationChanges
triggerChanges
```

提交、响应和 exporter 都消费此 delta，禁止使用 before/after 全量 diff。

## 7. 导出、查询和数据库边界

下单、撮合、风险、强平、结算不访问 JDBC、Kafka、Redis 或 HTTP。Aeron Cluster 的 Archive/Snapshot 是持久性机制，但它们不是业务 SQL 事务。

Exporter 采用：

1. 核心提交后把编码一次的 delta 放入 replicated outbox。
2. 尽力 push 给 exporter，不在核心线程无限重试。
3. exporter 携带最后 ACK，断线后从 `ack + 1` 批量获取。
4. ACK 按 batch 提交。
5. 满容量必须在命令提交前拒绝，不能成交后才回滚。
6. Kafka 使用 `productLine + exportSequence` 幂等键。

普通查询只读 Kafka/JDBC/内存投影；命令响应携带 `coreSequence`，投影携带自己的 sequence，需要 read-your-write 时等待投影追上 sequence。低频管理员一致性报告才进入核心。

## 8. 长任务和产品线隔离

- mark price 只通过 `symbol -> affected users` 索引处理持仓用户。
- funding 固定周期参数和费率版本，再按 position cursor 分批执行。
- settlement、delivery、exercise、liquidation 都使用有界 continuation command。
- cancel-all 使用 `user -> active orders` 索引，不能逐笔重新走全量状态构造。
- 现货、永续、交割、期权的资金公式、账户类型、topic 和生命周期保持隔离。
- 不把不同产品线的账户状态放入一个共享全局模型。

## 9. 线程和背压

核心线程模型保持简单：

```text
HTTP threads
  -> bounded MPSC ingress
  -> shared Aeron Client Agent
  -> Aeron ClusteredService single writer
  -> asynchronous exporter/projection agents
```

- 不用 `synchronized(slot)` 包住完整请求。
- 一个进程尽量共享 MediaDriver。
- 核心不等待 Kafka/JDBC/WS。
- offer 负值按 backpressure、admin action、closed 分类处理；不能无限 retry。
- 每个队列有界，队列满时在 mutation 前拒绝。
- 慢 WebSocket 只关闭当前连接，不能阻塞 Kafka listener 或核心。

## 10. 哈希、快照和恢复

热路径维护：

```text
rollingHash = H(previousHash, coreSequence, commandDigest, deltaDigest)
```

全量 business hash 仅用于 snapshot、离线回放和低频审计。Snapshot 只保存活动状态、matcher open orders、风险、定时器、幂等窗口和未 ACK outbox，不保存完整历史。

恢复过程：

```text
load snapshot
  -> restore exchange-core open book and account state
  -> replay post-snapshot Aeron log
  -> verify rolling hash, book hash and funds conservation
  -> mark product line ready
```

恢复失败必须保持交易入口不可用；禁止以空索引或数据库兜底接受下单。

## 11. 不做的设计

第一阶段不引入：

- 跨 Aeron Cluster 的同步资金事务。
- 多套共享账户 Cluster。
- exchange-core 之外的第二本 book。
- 通用 MVCC、分布式锁或 actor 框架。
- Merkle Tree；先使用 rolling hash 和 snapshot full audit。
- 独立 exchange-core journal 作为第二权威日志。
- 后台线程自由修改 Aeron replicated state。
- 未经 profiling 就开启多个 matching/risk engine。

## 12. 实施计划

### P0：基线和安全护栏

目标：在任何性能改造前锁定行为和资金安全。

任务：

- 增加 triggerOrders 保留的回归测试，并修复 `stampOrderChanges` 构造器参数丢失。
- 为 SPOT、LINEAR_PERPETUAL、LINEAR_DELIVERY、OPTION 固化撮合、资金、持仓、生命周期和 snapshot/replay fixtures。
- 建立 `coreSequence`、commandId、tradeId、exportSequence 的不变量测试。
- 建立单产品线的全量资金守恒测试。
- 记录当前工作区和性能基线，不把已有 `scripts/aeron-product-line-runtime.sh` 用户修改混入。

验收：相关 Maven 模块测试通过；四条产品线至少各有一条可重放资金场景。

当前进度（2026-08-15）：已完成 `CoreProbeStateTest` 的 trigger order 保留回归测试。测试先复现了 `stampOrderChanges` 重载未传递 `triggerOrders` 导致已有触发单被清空的问题，随后修复 `TradingCoreState.stampOrderChanges` 的构造器参数传递。现有 `CoreMatchingStateTest`/`CoreLifecycleStateTest` 已覆盖 SPOT 撮合和 snapshot、LINEAR_PERPETUAL funding/risk/liquidation、LINEAR_DELIVERY settlement、OPTION 权利金与结算，并额外覆盖 INVERSE_PERPETUAL/INVERSE_DELIVERY 的保证金和成交路径；这些是内存核心回归证据，不等同于真实 Aeron Cluster 多节点恢复。使用 IBM Semeru JDK 25 执行定向测试和 `surprising-aeron-service` 全模块测试（80/80）均通过，`scripts/integration-smoke.sh` 通过。新增 `CoreInMemoryBenchmark` 修复失效 benchmark 入口，1000 单测量样本输出 `ordersPerSec=169.270`、`p50=4603us`、`p99=15523us`、`max=20824us`，仅作为本机微基线，不能外推生产容量。真实三节点 Cluster 已有 SPOT Kafka 投影证据（core event 1247、core order projection 624、lag 0），但 Docker 当前不可用，四产品线真实资金场景、leader failover、恢复 fixture 和 JFR/async-profiler 基线仍是 P0 门禁；持久化边界脚本仍只发现 gateway 既有多表 Repository 注释缺口，核心同步链路未发现 JDBC/Kafka 访问。

### P1：消除额外往返和明显正确性问题

目标：一个命令一次核心往返，响应直接携带最终状态。

任务：

- place/cancel/replace response 直接返回权威 order view 和 executions。
- 删除成功命令后的重复 order query。
- 同用户命令使用稳定 source lane 和 sequence。
- 统一幂等响应窗口，清理无界 source sequence。
- post-only 优先改为 exchange-core 单次原子检查；若当前依赖版本没有原生 GTX/POST_ONLY 能力，必须显式保留 adapter 两步路径，并将其作为受控兼容边界，不得宣称已经是单往返。

验收：普通 place/cancel/amend 的 Aeron 请求数量分别为 1/1/1；重复请求结果一致。post-only 另行验收为“拒绝不得改变业务状态”，在 exchange-core 未提供原子命令时记录其两步查询成本和竞态边界。响应超过缓存上限时只允许走明确的幂等查询回退。

当前进度（2026-08-15）：`PLACE_ORDER`、`CANCEL_ORDER`、`REPLACE_ORDER` 的成功命令响应新增 `CoreCommandResultView`，直接携带本命令变更的订单和成交；订单服务优先消费响应，只有旧响应、超出 1 MiB 的响应或 duplicate 响应没有缓存增量时才回退到状态查询。成功命令的响应数据现在随 128 项幂等窗口有界缓存，重复下单/撤单在窗口内可直接返回同一增量；当前 snapshot codec 仍不持久化这段大响应，snapshot 后 duplicate 会安全回退查询。place 入口已删除 clientOrderId 前置查询；amend 现在发送只包含变更字段的 `AMEND_ORDER` patch，由核心在同一命令内读取原单、补齐 instrument/资金参数并完成 cancel+place，订单入口不再前置读取原单，达到 1/1/1 的单命令往返。commandId 对一次已分配 replacementOrderId 的完整业务意图稳定；**跨 HTTP 重试若重新分配 replacementOrderId，当前请求模型没有上游 idempotency key，不能宣称自动去重，需后续补充稳定请求键或由调用方复用同一命令。**同一 clientOrderId 携带不同意图仍由核心拒绝。订单 command 按 userId 固定 source lane，source sequence 不再在 round-robin slot 间漂移。source sequence watermark 现在限制为 65,536 个 source；达到上限时 fail-closed 返回 `SOURCE_SEQUENCE_TRACKING_FULL`，不淘汰旧 watermark。协议、核心和订单服务回归测试均通过。由于当前 exchange-core 版本没有原生 GTX/POST_ONLY command，post-only 仍是 adapter 内部的 order-book query + place 两步，不能把 P1 阶段误标为全部完成；需要在 matcher API 升级或引入受控单写原子扩展后再收口。长期 source lease/epoch 仍可进一步把上限收紧。

### P2：状态增量化

目标：交易命令复杂度与 changed entities 相关，而非 users/orders 总量。

任务：

- 引入命令级 dirty IDs / `CommandDelta`。
- 为 user/order/symbol/position/liquidation 建立增量索引。
- `applyMatches` 无成交时复用未变状态。
- stamp 只处理 changed order IDs。
- cancel-all、risk、OI、reduce-only 改用索引。
- 终态订单/reservation/liquidation 使用明确 retention/tombstone。

验收：固定活动状态、逐步增加历史 orders/users 时，下单和撤单 p99 不随历史量线性增长。

当前进度（2026-08-15）：状态构造器的兼容重载不再预先派生一次 `clientOrderIndex`，由 canonical constructor 统一校验和生成；已排序的 `NavigableMap` 现在在冻结时复用底层结构，避免 Reducer 和 constructor 对 users/orders 等顶层 map 双重复制；订单命令的 `stampOrderChanges` 接收本命令 changed order IDs，place/cancel/replace 不再为提交元数据遍历全量订单。衍生品下单的 projected position、reduce-only capacity、position-margin 冲突检查，以及执行强平前按用户撤销同品种订单，均改为沿当前用户的 reservation/order-id 集合读取；执行强平和交割前的 matcher 取消列表改为沿活动订单索引读取，不再扫描全量历史 orders；风险 continuation 现在直接从有序 users 的 `tailMap(lastUserId)` 继续，不再每批从头扫描并跳过已处理用户。现在 `StateMapSupport` 提供深度受限的不可变 copy-on-write `DeltaMap`：place/cancel/applyMatches 的 users、orders、openOrders 和 clientOrderIndex 只记录 changed keys，达到深度上限后才一次性 materialize，避免每条普通命令复制完整顶层 map；本轮进一步将 balances/reservations/positions、mark/snapshot/liquidation/scan、treasury asset/settlement、funding/settlement/liquidation/ADL，以及 trigger/timer/algo/instrument/leverage map 的单 key 更新改为同一受限 DeltaMap，保留 `CoreUserState` 的锁一致性校验。`CoreBookState.openOrders` 已从完整 `CoreBookOrder` 降为 `orderId -> prioritySequence` 活动索引；价格、方向、剩余量只保留在 `TradingCoreState.orders`，撮合盘口仍只由 exchange-core 持有。订单和余额 value object 的固定 ASCII symbol/asset 校验已去掉 `String.matches` 正则路径，保留原长度和字符合同，减少高频状态构造分配。线性合约的名义价值、PnL、保证金、手续费和期权 premium/风险路径增加 checked-`long` 快路径，逆向除法和溢出仍保留原 `BigInteger` 舍入路径。`CoreProbeState` 现在维护由权威用户持仓增量更新的非复制 `PositionUserIndex` 和 `OpenInterestIndex`，下单风险校验直接复用 open-interest 聚合，funding/settlement 只遍历持有该 symbol 持仓的用户，open-interest query 直接读取 long/short 聚合；索引在 snapshot restore 时重建，无法识别增量基座时回退重建。`RollingBusinessStateHash` 已将热路径业务哈希改为按 changed keys 增量维护，快照恢复只做一次全量初始化；`ACK_EXPORT` 现在只收集已确认导出事件里的终态订单 ID，并按用户 DeltaMap 删除对应零锁 reservation，同时保留 terminal order 和 clientOrderIndex 防重；服务测试新增导出 ACK、快照和重复 orderId 回归。终态 orders、历史 liquidation/trigger/algo 主体的容量 retention/tombstone 仍需产品规则后再删除，P2 总体验收仍待 profiling。

无成交的 GTC/非 MARKET 下单现在只复制并更新活动盘口 map，复用已预留的 users/orders；IOC/Market 无成交仍走原有取消和资金释放路径。以上嵌套 map 的 DeltaMap 优化已通过核心撮合、生命周期和风险回归测试。

### P3：exchange-core 唯一盘口和恢复

目标：删除外层第二本 book 和正常 rebuild。

任务：

- Adapter 只负责 command/result/event mapping。
- `CoreBookState` 不再作为运行时盘口权威。
- matcher open state 纳入 Aeron snapshot/replay。
- 所有预期业务拒绝在 matcher mutation 前完成。
- 未知 invariant 失败改为 failover/replay，禁止正常路径 rebuild。

验收：snapshot/replay/failover 后 exchange-core book、订单状态和资金状态一致；不存在正常拒单 rebuild。

当前进度（2026-08-15）：place/cancel 的单步匹配正常拒绝路径已不再调用 `rebuild`，未知运行时异常会向 Aeron Cluster 暴露并交给 failover/replay；replace、liquidation、settlement 因仍是多个 matcher mutation，暂时保留异常恢复兜底，直到后续切片改成可证明的原子/批量 matcher 操作。强平和交割的多笔撤单现在先全部异步排入 exchange-core，再一次等待并逐项检查结果，避免 N 个逐单 `.join()` 往返；业务 reducer 随后把同一批订单的余额、reservation、orders、活动索引合并为一次状态提交，避免 K 次重复构造 `TradingCoreState`。这只优化排队/等待和 reducer 分配，不改变部分失败时的恢复语义。当前 adapter 只在 Aeron 单写核心线程中被调用，因此 post-only 的 query+place 不会被本服务的另一条命令插入，但它仍是两次 exchange-core API 往返，且不是库级原子 GTX。`BOOK_STATE_QUERY` 已改为读取 exchange-core 原生 L2 snapshot；运行态不再保存完整 `CoreBookOrder` 盘口副本，`CoreBookState` 只保留活动订单优先级索引，订单业务字段由 `TradingCoreState.orders` 权威保存。adapter 的恢复入口从活动优先级索引取得 FIFO 顺序，再从权威 `TradingCoreState.orders + instruments` 取得价格、方向、剩余量等字段；不能按订单最近修改的 `clusterPosition` 排序，因为成交会更新该字段而不能改变原始 FIFO。snapshot v14 已将快照盘口段压缩为 `orderId + prioritySequence`，仍兼容读取 v13 及更早的完整字段。Export 事件若在撮合变更后才发现容量不变量，现在会恢复业务状态、两个派生索引及必要 matcher 状态后以 `EXPORT_BACKLOG_FULL` 拒绝；matcher sidecar snapshot 和组合 matcher 原子操作仍待 exchange-core 原子 API。

### P4：哈希、导出和查询旁路

任务：

- rolling hash 替代每命令 business hash。
- changed set 直接来自 delta。
- event encode 一次并缓存。
- exporter 从 polling 改为 push/reconnect/batch ACK。
- JDBC/Kafka/WebSocket 全部 batch 化。
- 查询从 consensus command lane 迁移到投影。

验收：exporter 空闲时不持续发 full hash status；Kafka/JDBC 慢不会改变已提交核心状态；投影可从 delta 重建。

当前进度（2026-08-15）：订单命令已记录 changed user/order IDs，Export 对 place/cancel/replace 直接按 dirty IDs 生成变更集合；普通订单命令不再扫描全量 liquidation/trigger map，风险、trigger 和 treasury asset 事件优先沿 DeltaMap 链取得 changed keys，快照 materialize 或基座不可识别时才保留全量兜底。`CoreProbeState` 现在用 `RollingBusinessStateHash` 按 DeltaMap changed keys 增量维护用户、订单、盘口索引、风控、资金和派生索引哈希；快照恢复只做一次全量初始化，命令拒绝、状态哈希和查询响应复用缓存，成功命令不再调用全量 `businessStateHash()`。哈希采用可逆 sum/xor 聚合并保留 domain/count，恢复后与权威状态全量计算结果回归一致。`CoreBookState.stateHash()` 改按稳定 orderId 顺序迭代，不再为哈希再次按 priority 排序。ACK_EXPORT 响应现在携带提交后的 export status，Exporter 在正常 ACK 路径不再额外发一次 status 查询，旧/重复 ACK 仍兼容回退。Exporter 主循环在空闲时采用 10ms 起步、最高 1s 的指数退避，事件到达后恢复基础间隔，降低空闲查询对核心 lane 的干扰。快照编码现在直接遍历 pending outbox，避免为同一 pending 队列重复构造完整只读列表；快照 wire 格式不变。push exporter 和投影旁路仍未完成。

### P5：长任务和客户端资源

任务：

- mark/funding/settlement/liquidation 使用 true cursor continuation。
- Aeron client 共享 MediaDriver 和异步 correlation agent。
- 移除 slot monitor 和无限 offer retry。
- WebSocket 和行情在 coalesce 前减少无效快照构造。

验收：风险扫描和 funding 不阻塞订单 p99；慢客户端不阻塞 exporter；client slot 不成为并发上限。

当前进度（2026-08-15）：`AeronClientPool` 的每个 slot 不再用 `synchronized(slot)` 串行化请求，改为 `AtomicBoolean` CAS 占用、释放和关闭排空；同一 `SurprisingAeronClient` 仍保持单飞，避免其响应表和 egress poll 被并发访问。连接池现在按 pool/client 名称和产品线隔离 Aeron directory，并懒加载一个共享 `MediaDriver`，slot 只建立独立 `AeronCluster` 会话；共享 driver 的初始化和关闭仅在冷路径使用窄锁，热路径仍为 CAS，无并发创建同一 Aeron directory 的竞态。关闭后拒绝新请求且关闭幂等。slot 获取现在受 `responseTimeout` 限制，满载时退避并明确返回饱和错误，不再无限自旋占用 CPU。Cluster service 的 egress offer 改为每 session 有界队列并在 `doBackgroundWork` 非阻塞排空，慢客户端不再阻塞核心裁决线程；队列满关闭 session，客户端以 commandId 重试/去重。`SurprisingAeronClient` 现在区分不可恢复的 publication closed/max-position 结果，并将 egress 解码异常转换为 session failure。funding 和 delivery/option settlement 现在都使用按 userId 排序的有界 cursor，进度保存在核心 treasury state、snapshot 和进度 query 中；provider 在重启或命令响应被裁剪后先 query 再从游标继续，完成后才写外部最终态。异步 correlation agent 仍未完成；当前 client 保持单飞模型，避免在未验证的后台 egress 线程中引入并发状态。

### P6：微批次和可选流水线

只有 P1-P5 完成并通过 profiling 才执行：

- Gateway 发送有界 batch command。
- batch 内按确定序列执行 R1 -> exchange-core -> R2。
- 只等待 batch barrier，不允许后台线程脱离 Aeron snapshot 边界修改状态。
- 仅在单写核心 CPU 成为明确瓶颈时评估 exchange-core multi-engine pipeline。

## 13. 性能和安全验收指标

- place/cancel/amend 单次 Aeron command round trip。
- 订单热路径没有 JDBC、Kafka、Redis、HTTP。
- 热路径没有全量 users/orders scan。
- no-match command 不复制完整 users/orders/book。
- active state 与历史状态分离，历史数据增长不造成相同比例 p99 增长。
- JFR/async-profiler 记录 allocation rate、GC pause、核心线程 CPU、Aeron backlog、export lag、Kafka lag。
- 报告 p50/p95/p99/p999/max，不使用协调遗漏的闭环延迟作为唯一结果。
- 固定活动订单，分别测试 1K/10K/100K/1M 历史订单 cardinality。
- no-match、单笔成交、扫多 maker、reject、post-only reject、cancel-all、mark-price、funding、snapshot、export backlog 都必须单独测量。
- 四条产品线分别完成余额、冻结、成交、手续费、资金费、强平、交割/行权和期末账账核对。
- leader failover、snapshot restore、client retry、export retry、Kafka rebalance 均不得重复扣款或丢事件。

## 14. 官方和源码参考

- [exchange-core README](https://github.com/exchange-core/exchange-core)：Direct order book、primitive state、R1/ME/R2、journal/snapshot 和 benchmark 边界。
- [exchange-core ExchangeCore.java](https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/ExchangeCore.java)：Grouping、journaling、risk hold、matching、risk release 和 result pipeline。
- [exchange-core MatchingEngineRouter.java](https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/processors/MatchingEngineRouter.java)：symbol shard 和 order book ownership。
- [exchange-core OrderBookDirectImpl.java](https://github.com/exchange-core/exchange-core/blob/master/src/main/java/exchange/core2/core/orderbook/OrderBookDirectImpl.java)：价格桶、FIFO、orderId index 和对象池。
- [Aeron Efficient Business Logic](https://aeron.io/docs/aeron-cluster/efficient-business-logic/)：确定性、短热路径、长任务拆分和 shard 约束。
- [Aeron Cluster Performance](https://aeron.io/docs/cluster-quickstart/aeron-cluster-performance/)：复制状态机顺序执行和 Little's Law。

## 15. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-08-15 | 汇总前几轮性能审计和 exchange-core 对照结果；确定 exchange-core 单一盘口、内存单写、数据库异步投影和分阶段实施方案。 |
| 2026-08-15 | 增加 triggerOrders 提交保留回归测试；修复 `stampOrderChanges` 丢失触发单状态的构造器参数；定向测试和 Aeron service 全模块测试通过。 |
| 2026-08-15 | 订单命令响应新增 `CoreCommandResultView`，place/cancel/replace 成功路径直接返回变更订单和成交；保留空响应兼容查询回退；协议、核心和订单服务测试通过。 |
| 2026-08-15 | `CoreMessage` 增加无复制的 payload 长度访问；Export batch 使用已知线性长度，不再为容量估算重复编码整条消息。 |
| 2026-08-15 | `TradingCoreState` 重载移除重复的 clientOrderIndex 预派生；订单提交元数据改按 changed order IDs 处理；核心回归测试通过。 |
| 2026-08-15 | 同一命令内复用已计算的 `businessStateHash`，Export append 和最终 `stateHash` 不再各自重新遍历业务状态；完整 rolling hash 替换仍留在 P4。 |
| 2026-08-15 | place/cancel 的单步匹配正常拒绝不再重启并重建第二本盘口；组合 matcher 操作继续保留异常恢复，待 P3 原子批量接口替换。 |
| 2026-08-15 | place/cancel/replace 的 Export changed users/orders 改用命令 dirty IDs；非订单命令继续全量兜底。 |
| 2026-08-15 | `TradingCoreState` 对已排序 `NavigableMap` 采用冻结复用，消除顶层 map 的二次复制；Aeron service 全量 68 个测试通过。 |
| 2026-08-15 | `applyMatches` 对无成交 GTC/非 MARKET 下单走轻量盘口更新分支，避免复制 users/orders 工作 map；核心 47 个撮合/状态测试通过。 |
| 2026-08-15 | `CoreUserState` 和 `CoreBookState` 冻结时复用已排序 map，降低嵌套状态二次复制。 |
| 2026-08-15 | `AeronClientPool` 移除 per-slot `synchronized`，采用 CAS slot ownership 和关闭排空；保留单 client 单飞约束，客户端模块测试通过。 |
| 2026-08-15 | `AeronClientPool` 懒加载并共享一个 embedded `MediaDriver`，slot 仅复用 driver 建立 `AeronCluster` 会话；关闭时先排空 slot 再释放共享 driver。 |
| 2026-08-15 | 连接池为 embedded `MediaDriver` 使用按 client/product/process 派生的独立 directory，避免多 gateway 在同一 JVM 互相删除 Aeron IPC 目录。 |
| 2026-08-15 | 客户端对 Aeron publication closed/max-position 做快速失败，并将 egress decode 异常转成 session failure；客户端模块 3 个测试通过。 |
| 2026-08-15 | place/amend 删除 clientOrderId 前置 Aeron 查询；commandId 纳入完整下单意图，duplicate 无增量时按 orderId/clientOrderId 兼容回查；订单服务 11 个定向测试通过。 |
| 2026-08-15 | Aeron command 按 userId 选择稳定 slot，保证同一 source lane 的 sequence 单调；query 继续使用 CAS slot 池。 |
| 2026-08-15 | Export 对普通订单命令跳过无关的全量 liquidation/trigger 差异扫描；风险/trigger 命令保留全量兜底，核心 47 个撮合/状态测试通过。 |
| 2026-08-15 | `CoreRiskState` 的 mark/snapshot/liquidation/scan map 复用已排序结构。 |
| 2026-08-15 | 衍生品下单的用户级订单检查改沿用户 reservation/order-id 集合读取，消除 projected position、reduce-only 和 margin 冲突路径的全局 orders 扫描；service 全量 68 个测试通过。 |
| 2026-08-15 | 风险 continuation 使用有序 users 的 `tailMap(lastUserId)`，消除每批从 user=最小值开始重复跳过前缀；service 全量 68 个测试通过。 |
| 2026-08-15 | `CoreProbeState` 缓存已提交业务哈希，查询、拒绝和 `stateHash` 不再重复遍历业务状态；成功命令仅刷新一次，service 全量 68 个测试通过。 |
| 2026-08-15 | `ACK_EXPORT` 直接返回确认后的 export status，ReliableCoreExporter 将正常导出轮次从四次核心往返降为三次；exporter 全部 10 个测试通过。 |
| 2026-08-15 | Export pending digest 改为直接混入消息 header 和 payload，避免为 digest 再构造完整 `CoreMessage` 编码；service/exporter 回归测试通过。 |
| 2026-08-15 | 命令直接结果超过协议容量时安全降级为空结果，让 Gateway 使用幂等查询回退，避免成交已提交后因响应编码容量异常再次触发核心失败。 |
| 2026-08-15 | 强平前撤销用户同品种订单改沿该用户 reservation/order-id 集合读取，避免在全局 orders 上扫描历史订单。 |
| 2026-08-15 | 强平和交割在驱动 exchange-core 取消活动订单时改沿 `CoreBookState.openOrders` 活动索引读取，避免扫描已终态订单；相关生命周期/风险/撮合 27 个测试通过。 |
| 2026-08-15 | 用户 open-orders 查询在带 userId 时改沿该用户 reservation/order-id 集合读取；全局管理查询仍保留全量路径；service 相关 41 个测试通过。 |
| 2026-08-15 | 交割取消同品种订单的 reducer 路径同样沿活动 `CoreBookState.openOrders` 读取，避免对历史终态订单做全量过滤。 |
| 2026-08-15 | Exporter 空闲轮询增加 10ms 到 1s 的退避，发布事件后恢复基础间隔，避免空闲状态持续抢占核心查询 lane。 |
| 2026-08-15 | 幂等窗口缓存可复用的 command response data（单项上限 1 MiB），重复命令不再因空响应触发额外查询；超大响应安全保留查询回退。 |
| 2026-08-15 | source sequence watermark 增加 65,536 项硬上限；新 source 达到容量时 fail-closed，快照恢复同样拒绝超限，避免无界 source map 侵蚀核心内存或被旧命令重放绕过。 |
| 2026-08-15 | 新增 `AMEND_ORDER` patch 协议；核心从权威原单补齐 instrument、资金预留和手续费参数，订单入口删除 amend 前置查询，核心/订单服务测试验证单命令完成改单。 |
| 2026-08-15 | 新增有界不可变 `StateMapSupport.DeltaMap`；place/cancel/applyMatches 改为按 changed key 更新顶层 users/orders/book/client index，保留深度上限后的确定性 materialize；service 全量 69 个测试通过。 |
| 2026-08-15 | Export 事件在撮合状态变更后若仍发生容量不变量失败，会恢复业务状态、派生索引和必要 matcher 状态后以 `EXPORT_BACKLOG_FULL` 拒绝；正常容量不足仍在命令执行前拒绝。 |
| 2026-08-15 | BOOK_STATE_QUERY 改从 exchange-core 的原生 L2 snapshot 读取并聚合，外层 `CoreBookState` 仅保留活动订单优先级索引，不再作为对外盘口权威。 |
| 2026-08-15 | `CoreBookState` 从完整 `CoreBookOrder` 降为 `orderId -> prioritySequence` 索引；订单价格、方向、剩余量只从 `TradingCoreState.orders` 读取，旧 snapshot wire 字段仍兼容解析。 |
| 2026-08-15 | `CoreBookState.stateHash()` 改按稳定 orderId 迭代优先级索引；盘口业务哈希由 `TradingCoreState` 结合权威订单字段计算，不再为 hash 排序完整 book。 |
| 2026-08-15 | matcher rebuild 入口使用活动优先级索引恢复 FIFO 顺序、使用 `TradingCoreState.orders + instruments` 恢复订单字段，避免用成交后更新的 clusterPosition 错误重排；replace/liquidation/settlement 的异常兜底仍待原子 matcher 操作替换。 |
| 2026-08-15 | snapshot codec 升级到 v14：新快照只保存活动订单 ID 和 priority sequence；v13 及更早版本继续兼容读取完整旧字段；部分成交后快照恢复 FIFO 回归测试通过。 |
| 2026-08-15 | 强平/交割撤单改为批量异步提交、一次等待并逐项校验；service 全量 70 个测试通过。 |
| 2026-08-15 | 强平/交割在 matcher 批量撤单成功后，业务 reducer 将同一批用户/订单/活动索引变更合并为一次状态提交，避免逐单构造完整状态；生命周期和撮合回归通过。 |
| 2026-08-15 | ACK_EXPORT 和 no-op applied 命令复用已缓存的 business hash，避免同一核心命令末尾再次遍历全量业务状态；service 全量 70 个测试通过。 |
| 2026-08-15 | 新增 `RollingBusinessStateHash`：用户、订单、盘口、风控、资金和索引按 DeltaMap changed keys 增量更新；快照恢复后与权威业务哈希一致，`CoreProbeStateTest` 回归通过。 |
| 2026-08-15 | Aeron client slot 获取增加 responseTimeout 有界等待和微退避，满载明确失败，避免连接池饱和时无限自旋；client 4 个测试在 JDK25 加入 Aeron 所需模块导出参数后通过。 |
| 2026-08-15 | 下单前置校验移除对全部活动盘口订单的 instrument-version 扫描；instrument 升级本身已要求无活动订单，核心只保留该不变量的写入边界校验；preflight 同样复用 OpenInterestIndex。 |
| 2026-08-15 | Cluster egress 从无限背压等待改为每 session 64 条有界队列和 `doBackgroundWork` 排空；慢客户端不再卡住核心单写线程，队列满时关闭 session 交由 commandId 重试恢复。 |
| 2026-08-15 | snapshot 编码直接遍历 pending outbox，去掉两次 `List.copyOf` 引用复制；快照格式和恢复测试保持通过。 |
| 2026-08-15 | `scripts/integration-smoke.sh` 使用临时 PostgreSQL 完成交易、账户、风险和强平数据链路检查；脚本通过，但该脚本不启动真实 Aeron Cluster，不能替代四产品线 Cluster failover 验收。 |
| 2026-08-15 | `OrderReservation` 和 `AssetBalance` 用固定 ASCII 校验替换 `String.matches`，保留原 symbol/asset 合同；新增 3 个边界测试，service 全量 73 个测试通过。 |
| 2026-08-15 | funding per-position 计算增加无溢出 `long` 快路径，溢出仍回退 `BigInteger` 并保持原截断/溢出语义；funding、风险和 service 全量测试通过。 |
| 2026-08-15 | 线性合约的名义价值、PnL、保证金、期权 premium/风险、手续费和加权开仓价增加 checked-`long` 快路径；逆向除法和溢出继续使用原 `BigInteger` 舍入路径，instrument 与 service 测试通过。 |
| 2026-08-15 | `CoreUserState` 的 balances/reservations/positions、`CoreRiskState` 的 mark/snapshot/liquidation/scan，以及 treasury asset/settlement map 改为保留受限 DeltaMap；新增嵌套增量断言，service 全量 73 个测试通过。 |
| 2026-08-15 | Reducer 剩余的 trigger/timer/algo/instrument/leverage，以及 funding/settlement/liquidation/ADL 的顶层 users/map 更新改为受限 DeltaMap；避免这些非下单命令再次复制完整顶层状态，service 全量 73 个测试通过。 |
| 2026-08-15 | 增加非复制 `PositionUserIndex`，按权威用户持仓 changed IDs 增量维护 symbol 到用户集合；funding 和 instrument settlement 不再扫描无持仓用户，snapshot restore 会重建索引；服务测试 75/75 通过。跨命令 funding/settlement cursor 仍待 P5。 |
| 2026-08-15 | 增加非复制 `OpenInterestIndex`，按 changed user 增量维护 long/short 聚合；`OPEN_INTEREST_QUERY` 不再遍历全部 users/positions，服务测试 76/76 通过。 |
| 2026-08-15 | funding 与 delivery/option settlement 增加有界 user cursor、核心进度状态、snapshot/query 恢复和 provider 续跑；旧四/五字段命令仍可解码为单次全量兼容路径，新增 cursor 快照与跨命令完成测试。 |
| 2026-08-15 | Export changed users/orders/liquidation/trigger/treasury assets 优先沿 DeltaMap 链取得 changed keys，只有快照 materialize 或基座不可识别时才回退全量 diff；新增嵌套 delta 删除回归测试，service 全量 74 个测试通过。 |
| 2026-08-15 | `CoreUserState.validateLocks` 对无 reservation/position 用户增加零分配快速路径，并将仅用于校验的临时锁汇总从 `TreeMap` 改为 `HashMap`；仍保留全量用户内局部扫描作为后续安全优化项。 |
| 2026-08-15 | `CoreProbeState` 将 source sequence watermark 汇总为增量 digest，state hash 不再随 source registry 规模逐项遍历；下单风险校验直接复用 `OpenInterestIndex` 的 symbol 聚合。 |
| 2026-08-15 | 幂等窗口更新移除每条命令先写占位 `StoredResult` 再覆盖的热路径分配，state hash 直接混入当前结果并按窗口边界淘汰最旧项；核心回归 80/80 通过。 |
| 2026-08-15 | 修复 `scripts/matching-engine-benchmark.sh` 的失效入口，新增 `CoreInMemoryBenchmark` 覆盖内存核心、exchange-core、业务状态和 export ACK；1000 单测量样本可输出 p50/p95/p99/max，明确不代表真实 Cluster 容量。 |
| 2026-08-15 | 导出 ACK 解析已确认事件中的终态订单，按用户增量删除零锁 reservation；terminal order 和 clientOrderIndex 继续保留，防止历史 orderId/clientOrderId 在重放或重试中被重新解释。新增 ACK、快照和重复 orderId 回归测试。 |
| 2026-08-15 | 真实 SPOT API smoke 的前两次在 provider 启动前受 Topic 前置条件拦截；随后以 JDK 25 和真实三节点 Cluster 运行，Kafka core event 投影追到 1247、`core_order_projection` 624、projection lag 0，证明内存核心→Aeron→Kafka→JDBC 投影链路已工作。旧 smoke 脚本仍查询已不再作为权威的 `trading_orders`（因此误报 open quotes=0），该脚本需要迁移到 `core_*_projection`/API 断言，不能将其误判为核心丢单。 |
| 2026-08-15 | `AeronClientPool` 增加共享 `MediaDriver` 冷路径双检锁；新增 8 线程首次初始化竞态测试，客户端模块 4/4（含 Java 25 Agrona export 参数）通过。 |
