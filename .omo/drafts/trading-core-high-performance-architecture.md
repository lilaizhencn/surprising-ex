---
slug: trading-core-high-performance-architecture
status: awaiting-approval
intent: clear
review_required: false
pending-action: await owner approval before creating .omo/plans/trading-core-high-performance-architecture.md
approach: six-boundary whole-chain redesign with exchange-core as the sole executable book, Aeron as the sole financial authority, bounded admission/export, product-line isolation, and evidence-gated migration
---

# Draft: trading-core-high-performance-architecture

## Goal

对交易入口、Aeron Core、exchange-core、账户/风控/生命周期、导出/投影/WebSocket、恢复/运维进行一次整体架构收敛，目标是：

- 高并发：入口和非冲突工作可水平/垂直扩展，但不破坏用户、symbol、风险域的确定性顺序；
- 低延迟：交易热路径不访问 JDBC、Kafka、Redis、HTTP，不创建每命令线程任务，不做全量扫描或正常路径 rebuild；
- 高稳定：所有队列有界，过载显式失败，撤单/强平保留容量，慢查询和慢消费者不反压交易 owner；
- 高可靠：已确认交易命令 RPO=0，资金守恒，恢复后 FIFO/book/hash/幂等一致，任何不一致 fail-closed；
- 可维护：每项状态只有一个权威，协议、产品线、snapshot、事件和运行门禁可版本化、可回滚。

## Components (topology ledger)

| id | outcome | status | evidence path |
|---|---|---|---|
| A | 入口完成认证、必填业务幂等键、payload fingerprint、按 `productLine:userId` 有界排序和明确的 NOT_ADMITTED/UNKNOWN/FINAL 结果语义 | active | `AeronClientPool.java:132-173,370-397`; `CoreProbeState.java:526-656` |
| B | 每个产品线 Core 是余额、预留、订单元数据、持仓、风险、Treasury、生命周期和幂等结果的唯一写权威；owner 热路径 O(delta) | active | `TradingCoreRuntime.java:42-73,187-203`; `CoreProbeState.java:216-680` |
| C | exchange-core 是唯一可执行盘口；Core 不保存 FIFO/盘口优先级；matcher 有一致的 snapshot/bulk restore、结构化 continuation 和有界执行分区 | active | `TradingCoreState.java:73-87,117-165`; `DeterministicExchangeCoreAdapter.java:149-172,228-303`; `SymbolMatchingLanes.java:10-52` |
| D | funding、trigger、risk scan、liquidation、ADL、insurance、delivery、option exercise/expiry 都是有界、幂等、可续跑的 Core 命令/continuation，四类业务语义隔离 | active | `ProductLine.java:6-12,58-79`; `TradingCommandCodec.java:462-582`; `CoreProbeState.java:1782+` |
| E | Aeron replicated outbox 负责可靠交接；Kafka/PG/查询/WebSocket 全部异步、幂等、可重建并公开 freshness/cursor | active | `CoreExportState.java:20-159`; `CoreStateSnapshotCodec.java:34-201`; `ReliableCoreExporter.java:46-71` |
| F | 三节点跨故障域部署，具备完整指标、容量阶梯、热 symbol、故障注入、24h soak、恢复与逐产品线切换/回滚门禁 | active | `compose.yaml:1+`; `docs/high-performance-trading-core-implementation.md:551-607,701-703` |

## Open assumptions (announced defaults)

| assumption | adopted default | rationale | reversible? |
|---|---|---|---|
| Core 扩容边界 | 默认一条产品线一个 Core Cluster；高频热点 symbol 允许迁移到独立 Core Cluster，但该 shard 必须拥有隔离的 user risk subaccount/collateral | 同一余额不能被默认 Core 和热点 Core 同时预留；隔离子账户可避免跨 Core 分布式资金事务 | yes, 若未来实现全局跨 Core 风险协议可扩展 |
| exchange-core fork | 采用现有 `/Users/atomex/Desktop/exchange-core` fork（`0.5.8-emporia`），通过其 `ISerializationProcessor`、`ApiPersistState`、snapshot restore 扩展点接入 Aeron snapshot；不以 Core 优先级副本兜底 | fork 已具备 snapshot-only 和 restore 基础接口；准确恢复 FIFO 的信息必须来自 matcher snapshot | yes, 可由经验证的新版本替换 |
| 端到端 SLO 尚无 owner 数值 | 初始门禁采用 p99 <= 10ms、p99.9 <= 25ms；热 symbol p99 <= 25ms；warm failover 5-10s；cold recovery <= 60s；RPO=0 | 5ms/10ms 对复制提交+异步 matcher 的完整路径缺少证据；先用保守且可测的目标 | yes |
| 业务峰值 TPS/活动订单规模未知 | 不凭空宣称 TPS；阶梯压测找到 SLO-bound throughput，生产只使用其 70%，且必须覆盖预测峰值至少 1.43 倍 | 当前只有 20 秒本地证据，不能推出生产容量 | yes |
| Kafka 导出是否需要“exactly once” | 使用 Aeron→Kafka at-least-once，所有消费者幂等；不增加同步 PG outbox | 当前 replicated/snapshotted outbox 已提供可靠 handoff，exactly-once 会提高耦合和延迟 | yes |
| 迁移方式 | 每产品线离线/只读 shadow replay 比对，维护窗口停写，snapshot+restore 后单点切权；禁止长期双写双权威 | 可验证又避免双写分叉 | yes |

## Findings (cited - path:lines)

### P0 / release blockers

1. **“唯一可执行盘口”尚未真正完成。** `TradingCoreState` 仍持有 `CoreBookState`，校验 `openOrders -> prioritySequence`，并将其纳入 snapshot/hash；恢复时 adapter 停止整个 exchange-core，再按 `priorityOrder()` 逐单异步放回。证据：
   - `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreState.java:73-87,117-165`
   - `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java:228-284`
   - 文档却将 P3 标为 DONE：`docs/high-performance-trading-core-implementation.md:600-607`。代码与完成声明冲突，发布前必须纠正。

2. **删除 `CoreBookState` 不能靠 Core 订单元数据重建。** FIFO/price-time priority 若不再由 Core 保存，就必须包含在 exchange-core 的 canonical snapshot 中。否则逐单按 orderId、timestamp 或 revision 重放都会重新引入“第二份优先级”或改变 FIFO。matcher snapshot 是休眠持久化制品，不是第二本活动盘口。

3. **恢复协议缺少原子 matcher watermark。** 当前 snapshot 保存 Core state 和 pending matching，但 matcher 通过 stop/start/replay 重建。目标必须在 snapshot barrier 下绑定 `coreSequence + matcherGeneration + matcherCommittedSequence + bookHash`，只在 Core 已提交的 matcher 结果水位截取 book，恢复后只重交水位之后的 pending intents。

4. **产品线金融状态不能直接按 symbol 横向拆分。** `TradingCoreRuntime` 同时更新余额、持仓、open interest、risk、liquidation、ADL 和 Treasury；CROSS margin、funding 与多 symbol liquidation 需要账户级原子视图。没有隔离 subaccount/collateral 之前，symbol 只能是执行/计算分区，不能成为独立金融权威。

5. **缺少生产可用性和容量证据。** 现有三节点 compose 是同机逻辑隔离；仓库只有短时 Core-only 容量/恢复证据，明确缺少真实 provider/Kafka/PG 故障和长时指标。`docs/high-performance-trading-core-implementation.md:562-607,701-703`。

### P1 / architecture blockers

1. **当前 symbol lane 是无界 Future 链，不是容量分区。** `SymbolMatchingLanes` 用无界 `HashMap<String, CompletableFuture<Void>>` 串接任务，没有 mailbox capacity、queue age、hot-symbol isolation 或 shed policy；adapter 仍配置 `matchingEnginesNum(1)`。`SymbolMatchingLanes.java:10-52`; `DeterministicExchangeCoreAdapter.java:294-303`。

2. **改单存在 split-success。** `replaceOrderAsync` 先 cancel 再 place；cancel 成功而 place 失败时 matcher 已改变。目标将同 symbol 改价/减量映射为 exchange-core 原子 move/amend；跨 symbol 不叫 replace，显式建模为 cancel + 新 order 两个业务操作。`DeterministicExchangeCoreAdapter.java:149-184`。

3. **入口并发模型放大尾延迟。** `commandAsync` 每命令进入线程池；同 user slot 饱和时 spin/park 直到 timeout；命令、查询和重连资源仍有竞争。目标改为固定单线程 Aeron agents + bounded MPSC mailbox，按 user 一致路由，查询/结果核验独立通道，过载立即返回明确状态。`AeronClientPool.java:132-173,370-397`。

4. **业务幂等不是完整持久契约。** Core 只保留 128 条 command result；同 source epoch 的旧 sequence 可防重，非空 clientOrderId 也会拒绝重复，但新 source epoch + 空 clientOrderId + unknown retry 仍可能生成第二个经济订单。该风险是条件性 P1；若生产允许空 clientOrderId 自动重试，则升级为 P0。`CoreProbeState.java:526-656`; `TradingCoreReducer.java:577-588`; `AeronClientPool.java:67-76,141-143`。

5. **Core 查询会抢占 owner。** 虽然查询有 limit/index，`USER_OPEN_ORDERS_QUERY`、risk、book 等仍由 ClusteredService service 线程处理。普通查询必须去投影；read-your-write 用 `coreSequence` 等待投影，Core 只保留有界审计/恢复查询。`CoreProbeState.java:216-520`。

6. **Aeron outbox 正确，但容量和下游契约未完成。** `CoreExportState` 随 Core 复制和 snapshot，1M events/64MiB 满前 fail-closed；不应再加同步 DB outbox。缺口是 outage sizing、oldest-age 告警、全消费者 inbox/fact idempotency、projection freshness 和 WebSocket cursor replay。`CoreExportState.java:20-159`; `CoreStateSnapshotCodec.java:34-201`。

7. **Kafka partition 0 是容量天花板，不是立即正确性缺陷。** 单 Core shard 的 canonical delta 需要全序；不能简单把同一命令的多实体变化随机分区。目标保留 canonical stream 按 core shard 排序，再由异步 dispatcher/Streams 按 `userId`、`symbol`、`instrument` 重分区；只有测得 canonical partition 饱和后才随 Core shard 扩容。

8. **WebSocket 慢连接隔离正确但恢复有损。** 当前 bounded queue 满会断开客户端，保护 Kafka consumer；私有订单/余额/持仓需要 snapshot + sequence + replay，公共深度需要 snapshot + delta/gap detection。

### P2 / optimizations after correctness

1. orderId 在 admission 前分配只导致 gap/浪费，本身不是资金正确性 P0；定义为 opaque、gap-tolerant 即可，优先修业务幂等。
2. exchange-core 内部 matching engine 数量可在 1/2/4 配置下压测，但 hot symbol 永远不能拆到多个 engine。
3. Core replicated outbox 可从对象 deque 优化为 compact encoded byte segments，但仍保持同一复制/快照/ACK 语义。
4. Snapshot/restore 对 N 个活动订单至少需要 O(N) 字节读取和对象/结构恢复；目标消除的是 O(N) 条异步 place 命令、future/join、matcher stop/restart 和重复优先级状态，不能虚假承诺数学上的次线性恢复。

## Decisions (with rationale)

### 1. Authoritative state ownership

- Aeron Cluster log/archive/snapshot：唯一命令顺序和业务恢复链。
- Core Runtime：余额、预留、订单业务元数据、持仓、风险、Treasury、触发/生命周期、幂等和必要索引的唯一写权威。
- exchange-core：可执行 bids/asks、price-time priority、matching sequence 的唯一权威。
- Matching snapshot：exchange-core 状态的版本化休眠快照；只用于恢复，不允许被 Core reducer 查询后参与正常裁决。
- Aeron replicated outbox：待导出事件的唯一可靠 handoff；Kafka/PG/Redis/WebSocket 都不是恢复权威。

### 2. Matcher snapshot and restore contract

新增 adapter 级契约，具体实现允许 pinned fork 或已验证升级版：

```text
beginBarrier()
  -> drain accepted matcher intents to Core-committed watermark M
  -> captureCanonicalSnapshot(schema, productLine, matcherGeneration, M,
                              symbolRegistryHash, openOrderCount, normalizedBookHash, bytes)
  -> endBarrier()

restore(snapshot)
  -> validate schema/product/symbol registry/checksum
  -> bulk restore exchange-core directly
  -> compare open order IDs/count + normalizedBookHash against Core metadata/index manifest
  -> resubmit only pending intents with sequence > M and same attemptId
  -> ready=true
```

- `TradingCoreState` 删除 `CoreBookState` 和任何 FIFO/prioritySequence。
- Core 仅保留 `CoreOrderState` 与 `ActiveOrderIndex(user/symbol/orderId)`，用于业务状态、撤单定位和恢复集合核对。
- snapshot barrier 必须有最大 pending/最大暂停门禁；无法在预算内 drain 时本次 snapshot 失败，不阻塞无限时间。
- bulk restore 仍为 O(N)，但禁止 N 次 API command/future；恢复门禁按 100K/1M/10M 活动订单（最终规模由 owner 确认）记录 decode、load、hash、ready 时间。

### 3. Matching continuation

- 每个 intent 记录 `commandId, attemptId, operation, symbol, expectedOrderRevision, matcherGeneration, sequence, phase`。
- phase 至少覆盖 `ACCEPTED -> SUBMITTED -> RESULT_RECEIVED -> CORE_COMMITTED`；snapshot 只捕获一致 watermark，未提交 intent 可幂等重交。
- matcher 结果只能由 Core owner 线程按 sequence 应用；attempt/generation/revision/result digest 任一不符即 fail-closed 并进入 reconciliation。
- 同 symbol replace 使用原子 matcher 命令；跨 symbol 是 cancel + new order，不提供伪原子语义。

### 4. Concurrency and scale boundary

- 第一阶段：每个产品线变体一个默认三节点 Core Cluster；Gateway 使用多个有界 Aeron client agents；exchange-core 内部按固定 symbol engine 分区。
- 同一 user 的金融命令保持总序；同一 symbol 的 matcher 命令保持总序；不同 user/symbol 允许在入口、校验、matcher engine 和下游投影并行。
- cancel、reduce-only、liquidation 只预留 admission/mailbox capacity，不越过同 user 已提交 sequence 乱序执行。
- 高频 symbol 可独占一个三节点 Core Cluster，但必须满足：
  1. 版本化 `CoreRouteTable(productLine, symbol, routeVersion) -> coreShardId`，所有 Gateway、mark price、funding、liquidation、export/query 都按同一版本路由；
  2. 一个 symbol 任一时刻只能有一个 active Core owner；迁移必须停写、drain/snapshot、核验、切 route 后再开放；
  3. 建立 `risk shard = disjoint symbol group + per-user subaccount + isolated collateral`，默认 Core 与热点 Core 不能同时消费同一份 available balance；
  4. 跨 shard 余额通过显式、幂等、双边可对账的资金转账完成；账户总览只聚合投影，不参与下单裁决；
  5. funding、liquidation、ADL、insurance 和 settlement 的资金池/排序范围必须随 shard 明确定义。
- 若要求全产品线 global cross margin 跨默认 Core 与热点 Core 共享，则不能直接拆分；必须另建中央账户/风险 authority 和两阶段 reservation/fill 协议，这不作为推荐默认方案。
- 不实施“symbol 独立 matcher cluster + 全局共享账户”这种隐式分布式事务。

### 4.1 Fork integration boundary

- 当前 Surprising 依赖 `exchange.core2:exchange-core:0.5.8-emporia`，但版本字符串不足以证明 fork provenance；应发布带 Git SHA/build metadata 的内部 artifact，并在启动日志和 snapshot manifest 记录 SHA。
- 当前 adapter 的 `ExchangeConfiguration` 仍使用 `SerializationConfiguration.DEFAULT` 和 `InitialStateConfiguration.cleanStart(...)`；目标改为自定义 Aeron-backed `ISerializationProcessor`，禁止依赖未复制的单机 snapshot 文件。
- `ApiPersistState(snapshotId)` 必须在 matching barrier 下执行；收集所有 matching/risk engine module blob 后，与 Core snapshot 的 `coreSequence/matcherSequence/routeVersion/symbolRegistryHash/bookHash/checksum` 绑定。
- 恢复使用相同 processor + `InitialStateConfiguration.fromSnapshotOnly(...)`；matcher hash、活动订单集合和 FIFO 门禁通过后才 `ready=true`。
- exchange-core journaling 保持关闭，避免建立第二套逐命令恢复权威；Aeron log 负责 matcher snapshot 之后的命令重放。

### 5. Ingress contract

- place 必填 `clientOrderId/idempotencyKey`，Core 保存 payload fingerprint 与原结果；同 key 同 payload 返回原结果，同 key 不同 payload 返回冲突。
- 超时状态明确区分 `NOT_ADMITTED / ADMITTED_UNKNOWN / FINAL_APPLIED / FINAL_REJECTED`；UNKNOWN 只能用原 commandId 查询/重试。
- 固定 client agent 线程直接 poll/offer，mailbox 有界；删除每命令 `supplyAsync` 和 slot spin-wait。
- command、result reconciliation、query、export 使用独立容量池；认证/限流只在 Gateway，业务裁决只在 Core。

### 6. Product-specific financial invariants

- SPOT：买卖资产冻结、成交扣减、手续费、撤单解冻与 maker/user 总资产守恒。
- PERPETUAL：CROSS/ISOLATED margin、mark price version、funding、强平费、insurance、ADL、主动平仓。
- DELIVERY：到期冻结新单、分批撤单、结算、position zero、手续费、重复/恢复游标。
- OPTION：premium、buyer/seller collateral、exercise/assignment、expiry worthless、cash settlement、组合保证金范围。
- 所有 lifecycle worker 只生成版本化计划/命令；Core 校验 expected revision、mark sequence、instrument version 后原子记账。

### 7. Export, projection and WebSocket

- 保留 Core replicated outbox；ACK crash window接受重复，禁止丢失。
- canonical delta topic 按 `productLine + coreShard` 保持 commit 顺序；异步派生 user/symbol/instrument topics 扩展消费并行度。
- 所有有副作用消费者使用 inbox/eventId + payload hash + aggregate revision；offset 在事实事务成功后提交。
- 查询返回 `projectionSequence/freshness`；read-your-write 等待目标 `coreSequence`，超时返回 STALE/PENDING 而非假装不存在。
- 私有 WebSocket 使用 snapshot-at-sequence + replay + live barrier；公共行情使用 book snapshot + delta sequence + gap resync。

### 8. HA, performance and rollout

- 每 Cluster 三 member 分布在独立主机/磁盘/故障域；Kafka/PG/exporter 不进入 quorum。
- 先完成指标，再调 GC/CPU affinity/threading；不能以 JVM flag 代替 profiling。
- 最低门禁：RPO=0、warm failover 5-10s、cold max-state restore <=60s、normal e2e p99<=10ms/p99.9<=25ms、hot p99<=25ms、export age p99<=2s、recovery drain>=2x peak、24h soak、生产负载<=SLO-bound capacity 70%。
- 迁移按 SPOT -> linear perpetual -> inverse perpetual -> delivery -> option；每条线先 shadow replay/hash/funds compare，再停写切换，禁止双写双权威。

## Migration waves

1. **Wave 0 - contracts/telemetry:** 冻结 authority、idempotency、result status、event envelope、snapshot schema 和 SLO 指标。
2. **Wave 1 - ingress isolation:** 固定 agent、bounded mailbox、query/result pool 分离、业务幂等和 backpressure。
3. **Wave 2 - matcher authority:** matcher snapshot/bulk restore 先落地并通过 FIFO/active-order recovery；随后删除 `CoreBookState`，收敛 replace/continuation。
4. **Wave 3 - Core financial lifecycle:** 按产品线补齐资金/风险/trigger/funding/liquidation/ADL/insurance/delivery/option invariant 与有界 continuation。
5. **Wave 4 - export/read path:** universal consumer idempotency、canonical+repartition topics、projection freshness、WebSocket replay。
6. **Wave 5 - HA/capacity:** 三故障域、最大活动订单恢复、Kafka/PG outage、hot/burst/step/24h soak、磁盘/GC/CPU 故障门禁。
7. **Wave 6 - product rollout:** 每条产品线独立 shadow replay、维护窗口切权、资金/盘口/hash/cursor 核验和回滚演练。

每个 wave 都必须是独立 snapshot/schema/protocol 版本；任何资金、FIFO、hash、幂等、export cursor 不一致立即停止后续 wave。

## Scope IN

- Gateway/order/account/risk/price/lifecycle provider 到 Aeron Core 的完整命令链。
- Aeron client/cluster service/Core runtime/exchange-core adapter/snapshot/exporter。
- SPOT、永续、交割、期权（含 linear/inverse 变体）的资金、持仓、风险与生命周期。
- Kafka/PG projection、查询 freshness、WebSocket fanout/replay。
- 生产拓扑、指标、压测、恢复、故障注入、切换和回滚。

## Scope OUT (Must NOT have)

- 自研另一套撮合盘口或在 Core 保存 FIFO/price-level/prioritySequence。
- Kafka、PostgreSQL、Redis、HTTP 回到交易同步裁决路径。
- 没有 subaccount/隔离 collateral 的跨 Cluster symbol 金融分片。
- 无界队列、无限 retry、UNKNOWN 自动生成新 commandId。
- 长期 dual-write/dual-authority、从 PG/Redis 猜资金或恢复 book。
- 在 profiling 前盲目增加 matcher engine、线程或 JVM 参数。
- 用平均 TPS、20 秒 smoke 或 micro benchmark 宣称生产容量。

## Open questions for owner approval

1. **热点 Core 的保证金边界：** 是否接受推荐默认“热点 symbol Core 使用独立 risk subaccount/collateral，用户通过显式资金划转分配额度”，还是必须与产品线默认 Core 共享 global cross margin？
2. **Fork artifact：** 默认把现有 fork 发布为带 Git SHA 的内部 Maven artifact，并实现 Aeron-backed serialization processor；是否有既定内部制品库/版本命名规则需要遵守？
3. **SLO/规模：** 是否接受当前临时 SLO；并请补充每产品线及热点 symbol 的 sustained/peak commands per second、最大活动订单数和 Kafka 可容忍中断时长，用于把容量、Core shard 和 outbox 大小变成确定门禁。

## Approval gate

status: awaiting-approval

批准方式：

- 回复“批准推荐默认值”，将按上述假设生成可执行 `.omo/plans/trading-core-high-performance-architecture.md`；或
- 对三个 open questions 给出修改，草案更新后再进入最终计划。

在批准前不修改业务代码、不生成执行计划、不启动迁移。
