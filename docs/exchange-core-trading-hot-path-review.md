# exchange-core 交易主链路性能与恢复审计报告

> 状态：`P0_RUNTIME_HOT_PATH_REMEDIATED; P1_CONTINUATION_ALLOCATION_REMEDIATED; SIX_LINE_SERIAL_GATE_PASS; OPEN_LOOP_100K_PENDING`
>
> 审计分支：`codex/aeron-unified-core`
>
> 审计日期：2026-08-20
>
> 目标：单产品线稳定完成 100,000 条最终裁决交易命令/秒；JVM 长稳、故障可恢复；非成交热路径平均 `O(1)`；禁止与全局状态规模成比例的复制、扫描和分配。

## 1. 结论

当前架构方向正确，但现状仍没有 100,000 条最终裁决命令/秒的容量认证证据。exchange-core 撮合器不是首要
瓶颈；本轮已消除 Product Core 在线路径中的 treasury 全量重建、永续 processor 全局扫描、delta 静默 rebuild
和 pending matching 逐条调度，协议复制与 outbox 编码仍是后续容量工作。
普通 `adoptState` 当前已经使用增量 Runtime transition；旧审计中“每条命令完整 materialize/parity”的表述已过时，
容量认证仍缺少三节点、开放环和长稳证据；代码层面的 treasury 同步、永续成交、资金费和风险续跑全量工作已在本轮消除。

本轮 P0 改造不是增加 Matching Engine 数量或单独调整 GC，而是让 `TradingRuntimeState` 成为在线唯一
可变状态，由 Product Core owner thread 对本次命令触及的实体执行原地、可验证的增量提交。完整
`TradingCoreState` 只应在快照、恢复、离线对账或抽样一致性检查中生成。

本报告是当前源码和本地诊断基准的现状审计。P0 代码已接入并通过受影响模块回归；六条产品线已经完成
三节点 Core、provider、常驻做市、Aeron 在线资金/持仓守恒和串行容量基线，但这仍不等于 100k/s、开放环、
故障恢复或长稳生产门禁通过。凡涉及 100k/s、无全量复制和热路径复杂度的结论，以本报告列出的源码出口和
正式容量门禁为准。

## 2. exchange-core 可借鉴的设计

项目固定使用 `exchange.core2:exchange-core:0.5.15-emporia`，fork commit 为
`627ddf68fbb0594b07e4b59a1a0e3377354e26b9`。其核心流水线为：

```text
API / RingBuffer
    -> Grouping / optional Journal
    -> Risk R1
    -> Matching Engine（按 symbol 分片）
    -> Risk R2
    -> Results Handler
```

固定版本源码：

- [ExchangeCore 流水线](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/ExchangeCore.java)
- [Direct OrderBook](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/orderbook/OrderBookDirectImpl.java)
- [PerformanceConfiguration](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/common/config/PerformanceConfiguration.java)
- [官方基准说明](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/README.md)

值得保留和借鉴的机制：

- Disruptor 预分配 `OrderCommand` ring，避免传统线程池、锁竞争和无界任务队列。
- 撮合与风险状态由固定 owner thread 修改，不让多个线程并发写同一本订单簿。
- symbol 固定映射到 Matching Engine shard，订单簿不会在处理过程中跨线程迁移。
- Direct OrderBook 使用订单 ID 索引、价格桶和桶内双向链表；按订单查找、取消和 FIFO 摘除不扫描整本订单簿。
- 订单、价格桶和 matcher event 可通过池化降低稳定负载下的 GC 压力。
- 核心命令通过固定流水线推进，而不是为每笔交易创建任意异步任务。
- snapshot、serialization 和 journal 都与确定性状态边界对齐。

exchange-core README 中的峰值不能直接换算为 Surprising-EX 端到端容量。其基准主要覆盖内存撮合和 Risk
阶段，不包含 Aeron Cluster、业务账户、资金预留、持仓、replicated outbox、Kafka、网络、快照和故障恢复。

还必须接受两个复杂度边界：

- 增加 Matching Engine 数量只能提高多 symbol 总吞吐，不能并行处理同一热门 symbol 的价格时间优先队列。
- 一笔 taker 订单命中 `k` 个 maker 时，处理下界为 `O(k)`，不能宣称严格 `O(1)`。

## 3. 当前架构中应保留的边界

当前分支已经具备正确的可靠性骨架：

- 每个 `ProductLine` 使用独立 Product Core，六条产品线的账户、订单、topic、instrument、风险和快照保持隔离。
- exchange-core 是唯一可执行订单簿，Product Core 不维护第二本价格时间优先 book。
- Aeron Cluster Log/Archive 是命令权威；exchange-core command journal 被关闭，避免双权威日志。
- Product Core 在 matcher 前裁决业务校验与资金预留，matcher 结果回到 owner thread 后再完成资金、持仓和风险提交。
- Audit Exporter、Kafka 和 History Projector 位于在线裁决链路之外，PostgreSQL 不参与当前态交易决策。
- 业务快照和 exchange-core 原生 `ME/RE` 快照配对恢复；manifest、hash 或 open-order set 不一致时 fail closed。
- matcher continuation 异步返回，Aeron owner thread 不直接阻塞等待撮合 future。

以上边界不应为了吞吐量被推倒。性能改造应集中在 Product Core 内部状态表示、continuation 调度、协议复制和
outbox 编码上。

## 4. 当前源码状态：在线增量路径与残留全量工作

### 4.1 普通状态迁移已经改为增量，但必须防止退化

[`CoreProbeState.adoptState`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java)
当前在线执行：

1. `RuntimeStateDeltaApplier.apply` 将候选 immutable state 的差分应用到 Runtime。
2. `TradingCoreRuntime.transition` 依据 changed keys 增量更新索引。
3. `RollingBusinessStateHash.update` 依据 changed keys 增量更新业务 hash。
4. 只有快照、恢复、模拟或测试 parity 才应该执行完整 projection/materialization。

本轮已将 [`RuntimeStateDeltaApplier`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateDeltaApplier.java)
和 [`TreasuryRuntime`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TreasuryRuntime.java)
改为只应用 changed assets/symbols；在线 treasury 不再 `clear()` 后全量重建。所有核心 runtime index 也会对非 delta
transition 直接 fail closed，不再静默调用 `rebuild(after)`。

[`RuntimeStateMaterializer`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateMaterializer.java)
会重建全部用户、余额、订单、reservation、position、risk、liquidation、treasury、client-order index、algo、
timer 和 trigger map。它在每个用户循环内再次扫描全局 reservation 和 position 集合，因此冷路径最坏复杂度
可能接近：

```text
O(U * R + U * P + O + Risk + Treasury)
```

其中 `U` 为用户数，`R` 为 reservation 数，`P` 为 position 数，`O` 为订单数。这是恢复、快照、离线 parity
的风险，不应重新引入普通命令热路径。解决方案见 5.6.14。

[`RuntimeStateParityChecker`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateParityChecker.java)
适合作为迁移期 fail-closed 门禁，但不能重新放回 100k/s 的每命令生产热路径。

### 4.2 永续 processor 的在线残留问题不是完整投影，而是局部全量扫描

`RuntimeStateProjector.project(before, identities)` 当前主要位于构造、恢复和 `simulate*` 冷路径。在线
`CoreProbeState` 已持有 Runtime，但以下处理器仍有局部全量工作：

- `RuntimePerpetualMatchProcessor`
- `RuntimePerpetualFundingProcessor`
- `RuntimePerpetualRiskProcessor`
- `RuntimePerpetualLiquidationProcessor` 的 `simulate*` 仍只允许冷路径调用。

本轮已修复 [`RuntimePerpetualMatchProcessor`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessor.java)
只按 authoritative changed users 更新 revision；资金费通过 `(symbolId,userId) -> position keys` 有序索引访问非零持仓；
风险续跑通过 `NavigableSet.higher(cursor)` 前进。上述 online 路径不再按全局 positions 排序或扫描无序候选集合。

Runtime processor 的在线状态应继续由 Product Core owner thread 持有并原地增量更新；immutable reducer 可以在
迁移期作为 shadow/parity 参照，不能与 Runtime 在每条生产命令上重复执行完整业务计算。

### 4.3 本地诊断基准

本轮使用当前分支执行了短基准，仅用于定位瓶颈，不作为生产容量证明：

| 测试 | 结果 |
| --- | ---: |
| exchange-core adapter，20,000 个 IOC 无成交订单 | 134,127 completion/s |
| 完整 Core 内存链路，200 组下单+撤单 | 51.3 组/s |
| 跳过真实 matcher submit 后的完整 Core | 54.8 组/s |

完整 Core 的 prepare/apply 平均时间会随状态增长，从约 `2.6/1.8 ms` 增长到约 `6.4/5.6 ms`；同一轮中
exchange-core 阶段约为 `0.2 ms`。跳过 matcher 只提升约 7%，说明当前主要耗时位于业务状态包装层。

限制：该基准状态规模小、无真实 fill、无三节点 Aeron、无 Kafka、不是开放环 JMH，并使用本地开发机 JVM；
因此只能用于定位，不能用于宣称已经达到或接近 100k/s。

## 5. 其他高优先级问题

### 5.1 pending matching 调度已改为有界单唤醒

[`SurprisingClusteredService`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java)
本轮已在 [`CoreProbeState`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java)
和 [`SurprisingClusteredService`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java)
落地有界 completion queue、单一非 Aeron missing-value sentinel wakeup、`commandId -> sequence` 直接索引以及
`MAX_PENDING_MATCHING` admission backpressure。异步 matcher callback 只入队，owner thread drain 后按 head sequence
完成状态提交；Aeron timer backpressure 不再在 matching 路径 busy-spin。

剩余容量风险不再是当前 P0 代码缺口，而是尚未执行的开放环/长稳认证：

- `matchingSequence(commandId)` 通过 stream 扫描全部 pending command，平均工作量为 `O(P)`。
- 单次完成会产生 `O(P)` timer 调度调用。
- 一批 `P` 个请求完成时，累计调度调用可能接近 `O(P^2)`。
- `scheduleTimer` 失败时在 Aeron owner thread 上 busy idle。

当前实现使用 `ArrayBlockingQueue` 作为有界跨线程完成边界；队列溢出进入确定性 matcher divergence fail-closed，
不允许无界增长。

### 5.2 协议入口和出口重复复制

`SurprisingClusteredService.onSessionMessage` 先把 `DirectBuffer` 复制到新 `byte[]`；
[`CoreMessageCodec.decode`](../surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java)
随后又为 payload 创建第二个 `byte[]`。响应编码也会创建新的完整数组。

目标实现：

- 增加 `DirectBuffer + offset + length` flyweight decoder。
- 只解析当前命令需要的字段。
- pending 生命周期必须持有数据时，只复制一次到紧凑 command struct。
- 响应直接编码到复用 Agrona buffer。
- 禁止把 Aeron callback 的临时 slice 保留到 callback 生命周期之外。

### 5.3 replicated outbox 存在对象、编码和 ACK 放大

[`CoreExportState`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java)
为每个事实创建 event、payload 和 `CoreMessage`；ACK 时重新解码 event 以查找终态订单。batch 查询和 Aeron
响应还会再次组合编码。

建议保留 replicated outbox 的可靠性边界，但改为：

- entry 保存预编码 frame 和 terminal metadata。
- batch 查询只做一次连续 buffer copy。
- ACK 按 sequence 和 side metadata 清理，不重新 decode event。
- 以 pending bytes 为主要容量门禁。
- 审查 matching pending 和 completion 是否都必须暴露为 Kafka Core Fact；若外部只需要最终事实，内部
  continuation 可只保留在 Aeron Log 中。

### 5.4 快照可能产生长 owner-thread 暂停

当前 snapshot 需要 pending matching 清空，并同步生成 business snapshot 和 matcher snapshot。持续高流量下，
如果没有 admission fence，很难自然进入稳定的零 pending 窗口。

建议流程：

```text
关闭新入口
    -> drain 已提交 continuation
    -> 固定 snapshot epoch
    -> 按确定性顺序分段编码 Runtime State
    -> 配对 matcher snapshot
    -> 恢复入口
```

snapshot 和 recovery 可以是 `O(N)` 冷路径，但必须限制 owner-thread 暂停时间；避免先生成完整 immutable state，
再生成一份完整 snapshot byte array。还需按目标用户数和订单数验证当前 snapshot 大小上限与 Archive retention。

### 5.5 JVM 与依赖稳定性风险

[`surprising-aeron-core/compose.yaml`](../surprising-aeron-core/compose.yaml) 默认使用 `-Xms512m -Xmx512m`。
该容量需要同时容纳 matcher、Runtime State、replicated outbox、snapshot、当前 materialize 瞬时副本和 GC
安全余量，不能作为生产默认容量。

构建 shaded jar 时还观察到：

- Aeron 与 exchange-core 传递的 Agrona 类重叠。
- LZ4 1.10.1 与 1.8.0 类重叠。
- JDK 25 对 Unsafe、native access 和 Chronicle internal access 给出警告。
- Docker 只配置 `jdk.internal.misc` 的 `--add-opens`，还未显式覆盖全部 native/export 要求。

必须统一 Aeron、Agrona、LZ4 版本并增加 duplicate-class 构建门禁；固定 Temurin/HotSpot 25 发行版和参数；
根据真实 live set 决定 heap，使用真实负载比较 ZGC 与 G1。保留 `Xms=Xmx` 和 pre-touch，同时为 direct
memory、Archive page cache 和操作系统留足物理内存。

### 5.6 逐项解决方案、理由与安全边界

以下方案按“先消除全局复杂度，再减少复制和异步开销，最后处理冷路径与下游”排序。所有方案都必须保留
ProductLine 隔离、Aeron Cluster sequence 顺序、幂等语义和资金守恒，不以牺牲确定性换取吞吐量。

#### 5.6.1 幂等结果账本：从全量 hash/copy 改为增量摘要

当前 [`CoreProbeState.stateHash`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java)
会遍历最多 128 个 `StoredResult`，而 `responseData()` 还会复制完整响应；`storeResult` 又会重新统计整个账本的
字节数。

方案：

- `StoredResult` 写入时一次性计算 `responseDigest` 和 `encodedBytes`。
- Core 内部增加 `responseDataUnsafe()` 或只读 owned view，防御性复制只保留在协议边界。
- 维护 `resultLedgerBytes`、`resultLedgerCount` 和增量 `ledgerDigest`。
- 插入、替换、淘汰只更新受影响的摘要节点，不重新遍历所有响应。
- 如果要求严格可复现的有序 hash，使用按 commandId/retentionSequence 排序的轻量 Merkle/持久树；不要只依赖
  简单 XOR。
- 继续保留 128 条和 32MiB 上限，超限时确定性淘汰旧结果或拒绝超大响应。

理由：

结果账本属于每条命令都会触碰的状态。将成本从“历史响应总字节数”降为 `O(log 128)` 或近似 `O(1)`，才能
避免大响应把普通下单的延迟和 GC 一起放大。

验证：

- 多节点对相同 command sequence 的 ledger digest 必须一致。
- 重试、重复、幂等冲突和淘汰后的 command result 必须保持原语义。
- 32MiB 账本压力下，单命令不得复制整个账本。

#### 5.6.2 永续成交：只处理 touched users

[`RuntimePerpetualMatchProcessor.applyTransition`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessor.java)
不应遍历 `expected.users()`。

方案：

- 使用 reducer 产生的 `changedUserIds`。
- 额外加入 taker user 和每个 maker user，使用 primitive long set 去重。
- 只对 touched user 更新 revision、余额、reservation、position 和相关 index。
- debug/parity 模式下断言 touched 集合覆盖 immutable expected 的 changed users；生产路径不执行全量断言。
- 对同一批次的 maker user 只执行一次 runtime update。

理由：成交影响范围是 `O(1 + k)`，其中 `k` 是 maker/fill 数；不应随全局用户数 `U` 增长。

#### 5.6.3 风险续跑：使用有序索引游标

[`RuntimePerpetualRiskProcessor.nextUser`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualRiskProcessor.java)
应与 [`TradingCoreReducer.nextRiskUser`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingCoreReducer.java)
保持同一实现策略。

方案：

- 将 symbol user index 暴露为 `NavigableSet<Long>`。
- 直接使用 `higher(lastUserId)` 获取下一用户。
- 风险扫描状态持久化 `lastUserId`，续跑不重新从集合头部扫描。
- 非有序索引只允许测试或离线 fallback；在线路径记录错误并 fail-closed。

理由：每个 continuation 应为 `O(batch)`，完整用户扫描应为 `O(U)`，不能退化成 `O(U²)`。

#### 5.6.4 资金费：增加 `(symbol, user)` 持仓索引

方案：

- 在 Runtime State 中维护 `symbolId + userId -> position keys`。
- 位置创建、修改、归零时增量维护索引。
- 资金费直接读取指定 user/symbol 的非零持仓，不扫描全局 positions。
- position key 预先保持有序，移除每用户临时 `ArrayList` 和 `sort()`。
- 每个 continuation 继续使用 `maxUsers`，并把 `O(B*P)` 降为 `O(B + touchedPositions)`。

理由：资金费即使分批，也必须限制每一批的真实工作量；分批不能掩盖每用户扫描全局集合的问题。

资金安全：保留原有 funding payment 对账、insurance adjustment、用户余额和 treasury 守恒断言；索引只作为
访问加速结构，不能成为第二份资金权威。

#### 5.6.5 pending matching：有界 completion queue 和单一 wakeup（已落地）

方案：

- `pendingMatching` 使用 sequence ring 或 primitive map。
- 增加 `commandId -> sequence` 直接索引，取消 `stream().filter()` 扫描。
- matcher callback 只写有界 completion slot/队列，不直接修改 Core 状态。
- owner thread 通过一个 wakeup/timer drain completion，状态提交仍在 owner thread 完成。
- 只为当前 sequence、重试 deadline 和超时任务设置 timer。
- 增加显式 `MAX_PENDING_MATCHING`、每 session pending client 上限和 completion queue 上限。
- 队列满时拒绝新命令或进入确定性 fail-closed，不能无限增长。
- 保留 `takeMatchingResult` 的 head sequence gate，禁止 out-of-order 资金提交。

理由：当前每完成一条 continuation 都重新遍历 pending，累计可达 `O(P²)`；单一 wakeup 可将调度成本降到与
实际完成数和超时数相关。10ms timer 只作为兜底，不应成为正常完成延迟来源。

#### 5.6.6 Delta 和 rolling hash：禁止静默全量退化

方案：

- 在线 reducer 所有核心 map 必须返回 delta map。
- `adoptState` 记录非 delta transition 的计数和 map 名称。
- 交易热路径遇到普通 map 时，使用明确的 touched-key diff；不要默认 `rebuildMap`。
- `RollingBusinessStateHash` 只对 changed keys 更新。
- 对 instrument、risk scan、treasury 等固定结构使用 numeric field hash，减少 `toString()` 和 UTF-8 临时数组。
- 全量 rebuild 只放在 snapshot、restore、offline parity。

理由：当前增量架构只有在所有 reducer 遵守 delta 契约时才成立；任何一个普通 map 都可能把整条命令退化为全局
扫描。

#### 5.6.7 Runtime 与 immutable reducer：分阶段取消双重业务计算

方案：

1. 第一阶段：Runtime 继续在线提交，immutable reducer 作为 shadow/parity，只在采样命令、测试、快照边界或
   follower 上执行。
2. 第二阶段：Runtime 产生 compact touched change set，由 snapshot/export 层按需生成 immutable view。
3. 第三阶段：普通命令不再同时构造完整 immutable candidate 和 Runtime candidate。
4. 每个 ProductLine 独立启用 feature flag，不能跨产品线一次性切换。

理由：当前 reducer + Runtime processor + index transition 重复执行交易逻辑，虽然提高迁移期安全性，但会直接
   翻倍 CPU 和对象分配。必须以 parity 证据逐产品线迁移，不能一次删除校验。

#### 5.6.8 协议、fingerprint 和内部 ownership：只复制一次

方案：

- 增加 `DirectBuffer + offset + length` flyweight decoder。
- Aeron callback 中解析成 compact typed command；pending 生命周期只保存 typed command 或一次 owned copy。
- Core 内部使用 owned read-only view，外部 API 仍保留 defensive copy。
- 响应直接写入复用的 Agrona buffer。
- `CommandFingerprint` 使用复用的 `MessageDigest` 和 canonical buffer，避免每条命令创建 digest、ByteBuffer 和
  payload clone。
- 不得把 Aeron callback 的临时 slice 跨 callback 生命周期保存。

理由：协议边界需要内存所有权，但不需要在同一线程内反复复制。此方案同时降低 CPU、Young GC 和网络前的延迟。

#### 5.6.9 matcher Future、Stream 和重复 decode

方案：

- 为 exchange-core adapter 增加 callback 或批量提交 API，逐步减少每命令 `CompletableFuture` 链。
- matching result 使用紧凑数组或受控对象池，保留明确生命周期。
- `Stream.concat().distinct().toList()` 改为 primitive touched set。
- `REPLACE/AMEND` payload 只 decode 一次，结果存入 `PendingMatching`。
- batch cancel/amend 使用直接循环；只有跨线程边界才创建 Future。

理由：这些分配不一定改变算法大 O，但会增加短命对象、GC 次数和尾延迟。JFR 已经观察到相关数组和对象分配。

#### 5.6.10 replicated outbox：预编码 frame 和 ACK metadata

方案：

- outbox entry 保存预编码 frame、sequence、frame length、terminal order IDs、digest 和产品线信息。
- batch query 只复制连续 frame，不创建 `CoreMessage` 列表后再次编码。
- ACK 按 entry metadata 清理，不重新 decode event。
- pending bytes 作为主容量门禁，event count 作为辅助门禁。
- 明确 pending fact 是否为外部审计契约；如果外部只需要最终事实，pending continuation 留在 Aeron Log，不进入
  Kafka Core Fact。
- 如果 pending fact 必须保留，使用紧凑 progress fact，不重复携带完整 changed users/orders。

Kafka exporter 保持 `acks=all` 和幂等 producer，但改为批量发送后使用单一 metadata barrier；ACK 仍必须在全部消息
成功发布后提交，不能为了吞吐提前确认。

理由：outbox 位于 Product Core owner-thread 热路径，编码和 ACK decode 都会直接消耗交易裁决预算。

#### 5.6.11 下单入口：移除热路径 preflight，避免同步 HTTP 占满线程

方案：

- 普通下单只提交一次真实 `PLACE_ORDER` command。
- Core 在同一 command 内完成最终校验、reservation 和资金冻结。
- Trading Provider 继续执行格式、instrument、fee 等本地校验。
- preflight 保留为显式可选 API，不作为真实下单前置步骤。
- HTTP 优先使用 async command admission：返回 commandId 和 `202 Accepted`，通过 command result 查询最终状态。
- 若同步兼容接口必须保留，使用有界等待 executor；线程池满时直接返回 backpressure。

理由：preflight 无法替代真实 command 的最终校验，却增加一次 Aeron 往返；同步 `.join()` 会把 Core 背压传导成
Web 容器线程耗尽。

#### 5.6.12 热门 user 的 AgentLane

方案：

- 保留默认 user affinity，避免破坏 source sequence 顺序。
- 优先为做市账户增加 batch command 和专用高容量 lane。
- 只有确认单 user lane 成为瓶颈后，才增加 per-user sequencer、source sequence 重排和多 lane 发送。
- 多 lane 必须有 reorder buffer 上限；超限时 backpressure，不允许无界缓存。

理由：简单随机分散同一 user 的命令会破坏顺序、幂等和资金状态可预测性。

#### 5.6.13 treasury、pending client 和 egress queue

方案：

- `RuntimeStateDeltaApplier` 增加 `TreasuryRuntime.applyDelta()`，只同步 changed assets/symbols，不再
  `treasury.clear()` 后重建。
- `PendingClient` 不保存完整 `CoreMessage`，只保存 session、response type、correlation/header 必需字段。
- 对同一 commandId 的重复等待可以合并。
- `doBackgroundWork()` 维护 active egress set，只扫描真正有排队数据的 session。
- 每 session egress、pending client 和 response queue 都设置硬上限，超限返回明确 backpressure 或关闭慢连接。

本轮已完成 [`RuntimeStateDeltaApplier.syncTreasury`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateDeltaApplier.java)
的 changed assets/symbols 增量同步；成交和资金费不再因 treasury state 变化触发全量 map 重建。pending client compact
header、active egress set 和协议层一次复制仍归入 P1。

理由：这些改动不改变交易语义，但会显著减少高并发时的 payload retention、空 session 扫描和 treasury 重建。

#### 5.6.14 Materializer、snapshot 和恢复

方案：

- materializer 只允许用于 snapshot、restore、offline parity 和模拟。
- materializer 先按 user 建立 reservation/position 索引，避免 `O(U*R + U*P)` 的重复全局扫描。
- snapshot 在 admission fence 后固定 epoch，等待 pending matching drain，再生成业务和 matcher snapshot。
- 使用分段 writer 直接写 snapshot publication 或 bounded sink，避免 `ByteArrayOutputStream + toByteArray()` 双份
  大数组。
- 对 snapshot size、encode duration、owner pause、restore duration 设置硬门禁。
- snapshot/restore 必须校验业务 hash、matcher hash、open-order set 和 ProductLine。

理由：snapshot 可以是 `O(N)` 冷路径，但不能在高流量期间制造不可控 owner pause 或瞬时堆峰值。

#### 5.6.15 Online query、busy-spin 和 matcher engine

方案：

- 增加 `(userId, symbol, marginMode) -> leverage` 索引，消除 user state query 对全局 leverage 的扫描。
- 同一热门 symbol 仍只允许一个价格时间优先 owner；增加 matcher engine 只用于多个 symbol 分片。
- busy-spin 只部署在专用 CPU 上；低负载环境使用 backoff/yield strategy。
- 根据 symbol 热点和物理核心数配置 engine，避免 Aeron owner、matcher、Kafka、HTTP 线程过度订阅。

理由：查询也在 owner thread 上执行；busy-spin 能降低延迟，但 CPU 过载会反过来放大所有尾延迟。

#### 5.6.16 Kafka、History Projector 和 WebSocket

方案：

- History Projector 批量写 PostgreSQL，在数据库事务完成后再提交 Kafka offset。
- 维护 projected watermark，WebSocket 只在 watermark 缺口或跨越时校验数据库，不要每条事件都同步 query。
- Kafka listener 使用 batch listener，按 topic/用户聚合 fanout。
- 订单、成交、资金类消息不能无条件 coalesce；行情类消息可以按 symbol 做 coalesce。
- WebSocket 继续使用有界 per-connection queue，慢连接主动断开，不能把背压传回 Product Core。
- 监控 Kafka lag、projected watermark、fanout queue depth、慢连接数和重连率。

理由：Kafka/WebSocket 不属于交易裁决权威，但同步数据库校验和逐条 fanout 会造成审计链路积压，最终表现为用户
推送延迟。

#### 5.6.17 JVM、GC 和可观测性

方案：

- 固定 JDK、Aeron、Agrona、LZ4 版本并增加 duplicate-class 门禁。
- 为 heap、direct memory、Aeron publication backpressure、pending matching、outbox bytes、result ledger bytes、
  completion age、owner apply duration 建立指标。
- 使用 JFR allocation profile、GC pause、native memory tracking 和 heap histogram 分离 Java heap 与 direct memory。
- 不先验选择 G1 或 ZGC；在同一 JDK、状态规模、开放环负载和 heap 下做对照。
- 任何优化都必须同时观察 p50、p99、p99.9、allocation rate、GC pause、CPU steal 和 queue depth。

理由：仅看平均吞吐不能发现 continuation backlog、direct memory、HTTP worker exhaustion 或慢客户端导致的尾部问题。

## 6. 目标热链路

```text
Aeron owner thread
    -> flyweight decode
    -> O(1) idempotency/source-sequence lookup
    -> 产品线独立 handler
    -> 校验本次涉及的账户、订单、余额、持仓和 reservation
    -> Runtime State 原地提交
    -> matcher ring submit
    -> bounded completion queue
    -> 按 global sequence 完成资金/持仓裁决
    -> append compact Core Fact
    -> direct buffer response
```

实施约束：

- Runtime State 只允许 Product Core owner thread 写。
- 命令使用 validate-before-commit；复杂资金变更使用只记录 touched entities 的 compact undo/change set。
- 正常命令不生成完整 `TradingCoreState`。
- parity checker 只运行在 debug、抽样 replay、快照边界或 follower shadow check。
- 热路径只校验 touched 用户、余额、订单、reservation、position 和 treasury delta。
- 资金守恒使用增量断言，不扫描全部账户。
- 每个用户维护直接的 reservation、position 和 active-order 索引，禁止按用户扫描全局集合。
- 六产品线共享 Runtime 基础设施，但账户、保证金、资金费、交割、期权行权继续由独立 handler 裁决。
- 如果修改 exchange-core fork，优先增加 sequence/callback 或 batch publish API，减少每命令
  `CompletableFuture`、lambda 和结果对象分配；不得改变价格时间优先语义。

## 7. 复杂度门禁

| 操作 | 目标复杂度 |
| --- | --- |
| 非成交限价单校验、冻结和索引插入 | 平均 `O(1)` |
| 按 orderId/clientOrderId 查询与撤单 | 平均 `O(1)` |
| 最优价访问 | `O(1)` |
| 单次成交 | `O(k)`，`k` 为 maker/fill 数 |
| 市价/FOK 扫多个价格档 | `O(k + levels)` |
| 用户余额、持仓、冻结读取 | 平均 `O(1)` |
| 资金费、风险扫描、ADL、交割、行权 | `O(batch)` continuation |
| snapshot/recovery | `O(N)`，仅允许出现在冷路径 |
| treasury 同步 | `O(changed assets/symbols)`，不得 `clear()` 后重建全量 map |
| 幂等结果账本更新 | `O(1)` 或 `O(log R)`，`R <= 128`，不得扫描响应正文 |
| pending matching completion | `O(completions + timeouts)`，不得按在途数重复全量 schedule |
| 热路径分配 | 不随全局状态规模增长，持续压到近零 |

任何单用户下单、撤单或单笔成交都不得产生与全局用户数、订单数、持仓数成比例的扫描、排序、状态复制或
对象分配。

## 8. 分阶段改造路线

### P0：消除在线全局工作（代码已落地，容量门禁待执行）

- ~~保持 Runtime State 为在线 owner-thread 状态；确认普通 `adoptState` 不调用完整 materializer/parity。~~
- ~~结果账本 hash/copy 已改为增量维护，不扫描历史响应正文。~~
- ~~永续成交已改为 touched-user revision/update。~~
- ~~资金费已使用 `(symbol, user)` 持仓索引，风险续跑已使用 `higher(cursor)`。~~
- ~~treasury 已改为 changed assets/symbols 增量更新，禁止 `clear()` 后重建全量 map。~~
- ~~pending matching 已具备直接 commandId 索引、有界 completion queue、单一 wakeup、max in-flight 和入口背压。~~
- ~~所有 Product Core runtime index 的非 delta transition 已增加 fail-closed 门禁，禁止静默全量 rebuild。~~
- ~~P0 回归已覆盖成交、资金费、风险、treasury、快照和服务调度；六产品线串行系统基线已通过，~~开放环 100k/s、
  故障恢复和长稳仍属于后续容量/系统验收。

### P1：控制 continuation 与分配

> 状态：**✅ 已完成（代码、定向回归与 3-fork benchmark/JFR）**。本状态表示 P1 代码出口已落地并完成本地观测，不表示 100k/s、三节点故障恢复或长稳容量门禁已通过。

- ~~[x] DirectBuffer flyweight 解码、一次 owned copy 和复用响应 buffer：`CoreMessageFlyweightDecoder`、`CoreMessage.owned`、`SurprisingClusteredService.PendingEgress`。~~
- ~~[x] fingerprint digest、matcher result、Stream、`toList`、重复 decode 和 Future 链分配优化：ThreadLocal SHA-256、bounded completion queue、热路径 changed-id 构造、export codec 显式循环、`matcherReady` 已完成时直达 matcher。~~
- ~~[x] outbox 预编码、terminal metadata、pending fact 契约收敛和 ACK metadata 化：`CoreExportState.PendingExport` 保存编码 fact 与 terminal order ids，ACK 不再重新 decode event。~~
- ~~[x] 去掉普通下单 preflight，HTTP 入口改为异步 admission；保留稳定 commandId：普通下单主链路沿用现有 asynchronous admission，preflight 仅保留为显式查询契约。~~
- ~~[x] 统一依赖版本和 JDK 25 模块参数：沿用 parent 的集中版本与 JDK 25 编译门禁，本轮 reactor 编译和测试均通过。~~

### P2：快照与故障恢复

- materializer 只留在快照、恢复和 parity；预先按 user 建索引，避免 `O(U*R + U*P)`。
- snapshot admission barrier、固定 epoch 和分段 Runtime snapshot。
- 禁止 `ByteArrayOutputStream + toByteArray()` 双份完整 snapshot buffer。
- 三节点 leader kill、snapshot corruption、Archive 重放测试。
- Kafka 不可用、outbox 达上限、Archive 磁盘满和 follower 落后测试。

### P3：容量与分片

优先把单 Product Core owner thread 做到目标。Matching Engine 可以按 symbol 扩展，但 Product Core 不能简单
按 symbol 拆分：同一用户的跨 symbol 余额、全仓保证金和风险属于同一个 risk domain。真正按账户/风险域分片
会引入订单簿和结算跨 shard 协调，只能在单核热链路完成 P0-P2 后重新评审。

实施顺序固定为：

```text
测量与状态 hash 门禁
    -> P0 全局扫描和结果账本
    -> pending matching 与入口背压
    -> 协议/outbox/ Future 分配
    -> HTTP、Kafka、WebSocket 异步链路
    -> snapshot/restore 冷路径
    -> 开放环容量、故障和长稳验收
```

每完成一个阶段，必须先通过该阶段对应的单产品线测试和资金守恒，再进入下一阶段；不得因为 benchmark 吞吐
提升而跳过回归或扩大产品线范围。

## 9. 100k/s 验收口径

本报告把“100k/s”定义为：单个产品线每秒完成 100,000 条已经被 Aeron Cluster 提交并完成最终业务裁决的
交易命令，不是 HTTP 收包数、matcher ring publish 数或短时峰值。

正式容量门禁至少包括：

- 开放环 100k/s 持续 60 分钟，不能用闭环客户端隐藏排队。
- 2 倍突发持续 10 秒，验证有界背压而不是 OOM。
- 24 小时 soak，heap occupancy、direct memory、pending 和 outbox 不持续增长。
- 至少 100 万用户和 400 万活动订单的固定状态规模。
- 热门 symbol 和热门用户倾斜。
- 无成交、1 maker、10 maker、100 maker 扫单分别测试。
- 下单、撤单、改单、成交、标记价、资金费、强平、ADL、交割和行权按产品线分别覆盖。
- p50/p99/p99.9 使用 acceptance-to-finalization 延迟，并修正 coordinated omission。
- 无 Full GC、无不可接受的 safepoint、无状态 hash 分歧。
- 满负载 kill leader 后，无已提交命令丢失、无重复最终事实。
- Kafka/Projector 停止期间交易按设计继续；outbox 达上限后确定性背压。
- 用户和做市账户逐项核对期初、充值/调整、成交、手续费、资金费、强平费、交割/行权和期末资金守恒。

### 9.1 改造完成判定

每个方案只有同时满足“性能证据”和“业务安全证据”才算完成：

| 改造面 | 性能完成条件 | 业务安全完成条件 |
| --- | --- | --- |
| 结果账本 hash/copy | 单命令不扫描历史响应正文；账本 bytes 增量维护 | 重试、重复、幂等冲突、快照恢复后的 command result 完全一致 |
| 成交/资金费/风险索引 | 单次工作量只随 touched users、positions 或 batch 增长 | reducer/runtime parity、用户 revision、资金费 payment 和 risk cursor 一致 |
| pending matching | pending、completion、timer 都有硬上限；无 `O(P²)` 调度 | 只能按 global sequence 完成资金和持仓裁决，不能 out-of-order |
| 协议/outbox | payload、response、event 不重复复制；outbox ACK 不重新 decode | sequence 连续、Kafka 不重复最终事实、ACK 只能确认已发布事实 |
| HTTP/客户端背压 | Core 慢时不耗尽 HTTP worker、Aeron lane 或堆内存 | commandId 稳定，重试仍幂等，未提交命令明确返回未接受 |
| snapshot/restore | owner pause、snapshot size、restore time 在预算内 | business hash、matcher hash、open orders、ProductLine 和资金状态一致 |
| Kafka/WebSocket | projector lag、fanout queue 和慢连接有界 | 订单/成交/资金消息不丢失、不乱序；慢客户端只影响自身 |

任何一项业务安全条件失败，都不得以性能基准通过为理由上线。

## 10. 本轮验证范围

已执行：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests package
mvn -pl surprising-aeron-core/surprising-aeron-service \
  -Dtest=CorePerpetualEndToEndBenchmarkTest test
mvn -pl surprising-aeron-core/surprising-aeron-tools -am \
  -Dtest=ClusterCapacityMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

构建通过，并执行 exchange-core adapter、完整 Core、skip-matcher 对照短基准以及永续 maker-depth 契约测试。
本轮 P1 在保留既有未提交 P0 改动的工作区上完成；未覆盖的改动均未被重置或清理。

独立资金/撮合回归共通过 `61` 个测试：`CoreProbeStateTest` 30、`CorePerpetualFinancialMatrixTest` 5、
`RuntimePerpetualMatchProcessorTest` 6、`CoreMatchingStateTest` 20；这些测试覆盖成交、手续费、持仓、资金费、
风险和余额守恒边界。当前 benchmark 本身仍不宣称可以替代这些状态断言。

本轮此前未执行三节点 Aeron 容量、Kafka/PostgreSQL 历史链路、leader failover、快照恢复、磁盘故障、六产品线
资金守恒和长时间 JVM soak；六产品线串行系统基线已在下文补充。仍未完成的是 100k/s 开放环、故障恢复、磁盘
故障和长时间 JVM soak，这些不能由短时容量基线替代。

### P1 continuation 与分配验证

P1 已完成并由以下定向证据闭环：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am clean test \
  -Dtest=CoreMessageFlyweightDecoderTest,MatchingCompletionQueueTest,SurprisingClusteredServiceTest,CoreProbeStateTest,CoreMatchingStateTest,CoreResultLedgerTest,CoreOrderedOrderBatchTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -pl surprising-aeron-core/surprising-aeron-protocol -am test \
  -Dtest=CoreMessageCodecTest,CoreExportCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -pl surprising-aeron-core/surprising-aeron-service -am test \
  -Dtest=CoreProbeStateTest,CoreMatchingStateTest,CoreOrderedOrderBatchTest,CorePerpetualEndToEndBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：第一组 service reactor 定向回归 `73` 个测试通过；protocol 编解码回归 `12` 个测试通过；加入 matcher-ready
fast path 后再次运行 continuation、撮合、批量和永续端到端回归 `63` 个测试通过；永续资金矩阵 `5` 个测试和端到端
撮合基准 `1` 个测试通过。新增 DirectBuffer 解码测试验证了 source frame 修改不会污染 owned payload，completion queue
测试验证了有界溢出、顺序消费和 overflow 信号，cluster service 测试验证了单 wakeup timer、head sequence gate 和
egress backpressure。

本轮还执行了 `git diff --check`，以及 protocol/service 变更文件的 JDTLS error diagnostics，结果均为无错误。三节点
容量、100k/s 开放环、Kafka/Projector、leader failover、snapshot corruption、direct memory 和长时间 soak 仍未完成；
六产品线逐项资金守恒已由下文的 Aeron 在线查询完成，不再列为未执行项。

本轮随后执行单产品线本地 benchmark 与 JFR：

```bash
env JAVA_HOME=<Temurin-25.0.4.1>/Contents/Home \
  scripts/run-exchange-core-hot-path-stage.sh \
  --stage p1-continuation-jfr --attempt-dir /tmp/surprising-p1-continuation-jfr \
  --benchmark-suite baseline --forks 3 --maker-depth 1 --jfr-settings profile
```

结果为 `stageResult=PASS`。3 个 fork 均完成 50/50 最终裁决、成交数量 25，且 `pendingMatching=0`；最终裁决
吞吐中位数 `678.997/s`，修正后 acceptance-to-finalization 延迟为 p50/p99/p99.9 `1,359/2,918/2,918 us`，
并发 completion queue 最大深度 `50`。JFR 三个 recording 均约 3 秒，CPU 样本主要为 Disruptor busy-spin 等待；
分配样本主要为 `ObjectsPool.ArrayStack` 初始化、`ConcurrentHashMap.initTable` 和 `Arrays.copyOf`。三份 JFR
均未记录 `ContinuationFreeze`/`ContinuationThaw`；每份有 1 次 Young GC 和 1 次 Old GC，列出的最长 pause 为
Young `5.05-6.27 ms`、Old `1.79-1.86 ms`。该结果是本地短样本观测，不替代 100k/s 容量、长稳、三节点故障恢复
或资金守恒验收；benchmark runner 本身不暴露 funds delta、state hash 和 book-empty 断言。

为扩大 P1 观测，本轮继续使用同一 runner 执行 `makerDepth=10` 与 `makerDepth=100`，每档 3 个 fork、Temurin
25、JFR `profile`：

| maker depth | 每 fork 最终裁决 / 成交 | 吞吐中位数 | 修正后 p50 / p99 / p99.9 | matching 残留 |
| ---: | ---: | ---: | ---: | ---: |
| 10 | `275 / 250` | `766.366/s` | `1,060 / 6,733 / 8,740 us` | `0` |
| 100 | `2,525 / 2,500` | `374.049/s` | `2,140 / 10,231 / 14,032 us` | `0` |

两档所有 fork 均为 `stageResult=PASS`。depth100 的 JFR 显示 `String.encodeUTF8` 分配压力升至 `48.22%-52.56%`，
并从每 fork 1 次 Young GC 增至 6 次，最长列出的 Young GC pause 为 `8.72-9.38 ms`；六份 JFR 均未记录
`ContinuationFreeze`/`ContinuationThaw`。扩大后的结果证明 P1 continuation、completion drain 和有界队列在更深
撮合工作量下无残留任务，但 depth100 的 p99.9 已超过 `10 ms` 预算，仍不能标记为容量 SLO 通过。

### 10.1 分阶段证据

| 阶段 | 结果 | parent commit | UTC | 固定负载 | 最终裁决吞吐中位数 | 修正后 p50 / p99 / p99.9 | 证据 |
| --- | --- | --- | --- | --- | ---: | ---: | --- |
| Stage 1 | **PASS（基准工具门禁）** | `9ec69899a8096d3e2c1b74e33ea393d26b1853c3` | `2026-08-20T04:54:40Z` | seeds `9901..9903`，每 fork：adapter 500、accept/freeze 25、完整内存 25、并发入口 50、永续最终裁决 50 | `325.373/s` | `3,848 / 12,541 / 13,541 us` | `.omo/evidence/task-1/task-1-baseline-result.json`；三个 `task-1-baseline-fork-*.jfr`；五个 `jfr-*.txt` |
| Stage 2 | **PASS（maker-depth 资金路径覆盖）** | `0c393ea17ecd1cbc88ae2a5cdc03491e88c3d9d6` | `2026-08-20T08:40:04Z` 至 `08:43:31Z` | 每 fork、25 measured cycles、JDK 25、3 forks；maker depth `1/10/100`，每 cycle 为 `k` 个 GTC maker 加 1 个 IOC taker | `555.385 / 543.990 / 442.549/s`（depth 1/10/100） | `1,620 / 3,391 / 3,391`；`1,569 / 9,158 / 12,156`；`1,859 / 15,523 / 22,740 us` | `/tmp/surprising-p0-maker-depth-{1,10,100}/p0-maker-depth-*-result.json`；每档 3 个 JFR 和五类 `jfr-*.txt` |

Stage 2 的逐档守恒断言为：depth 1/10/100 分别最终裁决 `50/275/2525` 单、成交数量 `25/250/2500`；每 fork 的 offered、accepted、finalized 完全相等，`perpetualPendingMatching=0`。这些数量由 benchmark 实际下单、撮合和 `completeMatching` 返回值产生，runner 还会逐 fork 拒绝计数偏差或残留撮合任务。

这组数据验证了深度参数确实扩大了真实撮合工作量，但不是容量认证。JFR 还观察到 depth 1/10/100 三档跨三个 fork 的 Young/Old GC 次数分别为 `3/3`、`6/3`、`39/3`；最大列出的 GC pause 约为 `6.41/10.30/17.70 ms`。深度 100 的 p99.9 已明显高于 10 ms 预算，因此当前结论是“资金/撮合路径可复现且无残留任务”，不是“满足延迟 SLO”。

JFR 的 `hot-methods` 在 depth 10/100 捕获了 `CorePerpetualEndToEndBenchmark.placeAndComplete` 和 `CoreProbeState.completeMatching`；后者在源码中通过 `adoptPerpetualMatchRuntimeState` 调用 `RuntimePerpetualMatchProcessor.applyTransition`。allocation view 的主要压力随深度增加集中到 `CoreProbeState$StoredResult.responseData()`、UTF-8 编码、`CommandFingerprint` 和数组拷贝。JFR 没有提供可用的 direct-memory 数值，safepoint duration 显示 `Indefinite`，因此这两项仍不能作为稳定性门禁。

Stage 1 的 PASS 仅表示可重复执行的度量/JFR 契约通过，不是生产容量认证。该短样本明显低于 100k/s，且
`p99=12.541 ms` 未达到后续容量门禁的 `10 ms` 默认预算；后续阶段不得把本行解释为性能 SLO 已通过。

运行环境为 macOS 26.7（Darwin 25.6.0）、Intel i9-9880H、16 logical CPU、16 GiB RAM；运行时未置于
容器，数据卷剩余约 98 GiB。`/usr/libexec/java_home -V` 没有 Temurin 25；本次从官方 Adoptium API 下载临时
Temurin 25.0.4.1 HotSpot archive 后运行，`java.vendor=Eclipse Adoptium`，`java.vm.name=OpenJDK 64-Bit
Server VM`，`java.runtime.version=25.0.4.1+1-LTS`，`java.home=/tmp/task-1-temurin-25/extracted/jdk-25.0.4.1+1/Contents/Home`。
runner 现在在创建目录、构建或启动 benchmark child 前拒绝非 Temurin 25；JSON 记录实际路径、vendor、VM 和
runtime version。固定参数为
`-Xms256m -Xmx256m -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED`
以及 `jdk.internal.misc` 的 `--add-opens/--add-exports`，JFR settings 为 `profile`。

精确功能测试为：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-tools -am \
  -Dtest=ClusterCapacityMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

RED 在缺少 `CapacityMetrics` 时以 AssertJ assertion 失败；GREEN 验证 offered/accepted/finalized 分离、
outbox sequence gauge，以及 `recordValueWithExpectedInterval` 对 `100 ms` observation、`10 ms` expected
interval 生成 10 个修正样本。基准命令为：

```bash
export JAVA_HOME=/tmp/task-1-temurin-25/extracted/jdk-25.0.4.1+1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
scripts/run-exchange-core-hot-path-stage.sh --stage task-1-baseline \
  --attempt-dir .omo/evidence/task-1 --benchmark-suite baseline --forks 3 --jfr-settings profile
```

Stage 2 三档 benchmark 命令为：

```bash
export JAVA_HOME=/Users/atomex/Desktop/surprising-jfr-work/jdk-25.0.4.1+1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
for depth in 1 10 100; do
  scripts/run-exchange-core-hot-path-stage.sh --stage p0-maker-depth-$depth \
    --attempt-dir /tmp/surprising-p0-maker-depth-$depth --benchmark-suite baseline \
    --forks 3 --maker-depth "$depth" --jfr-settings profile
done
```

Stage 2 的 JSON 将 `fundsDelta` 保留为 `null`，因为这个 local baseline 没有暴露签名资金 hash；资金/手续费/持仓守恒由
`CorePerpetualFinancialMatrixTest` 和 `RuntimePerpetualMatchProcessorTest` 的独立回归覆盖，不能用 benchmark 的订单计数替代。

三个 fork 的最终裁决速率为 `323.190 / 326.114 / 325.373 per second`。并发入口观察到的
`pendingMatching` 最大值和 ingress queue 最大值均为 `50`；当前短本地基准没有暴露 replicated outbox
占用，JSON 使用 `null`，不得解释为零。JFR 每 fork 观察到两次 Young GC 和一次 `Old Garbage Collection`，
GC 后 heap 为 `15.7-16.0 MiB`（第二次 Young GC 后为 `60.1-60.3 MiB`），最长列出的 pause 为 `7.08 ms`；没有 `Full GC` 标签。该 JFR view 未提供
可用的 direct-memory 数值，safepoint duration 显示 `Indefinite`，两项保留为未测，不能据此通过长稳门禁。

CPU top frames 为 `BusySpinWaitStrategy.waitFor`、`Util.getMinimumSequence`、`WaitSpinningHelper.tryWaitFor` 和
`ProcessingSequenceBarrier.getCursor`。allocation top frames 为 `ObjectsPool$ArrayStack.<init>`、
`String.encodeUTF8`、`ConcurrentHashMap.initTable`、`Arrays.copyOf(byte[], int)` 和
`Unsafe.allocateUninitializedArray`；`RuntimeStateMaterializer.materialize` 仍出现在 allocation view，符合
本报告的 P0 诊断。Stage 1 没有暴露可签名 state hash，也没有执行逐项资金守恒查询，因此 JSON 明确记录
`stateHash=not-exposed-by-local-baseline`、`fundsDelta=null`、`bookEmpty=not-queried`。

与 4.3 的旧短基准不做数值增益宣称：旧值是 200 组下单+撤单的 raw closed-loop `51.3 groups/s`，本行是
25 个永续成交 cycle、50 条最终裁决命令的修正后短样本，分子和负载不同。Stage 1 的 rollback boundary 是
单独回退 `perf(core): establish JFR hot-path baseline`；它未修改 Product Core 资金、订单或撮合语义。本行的
原始 parent commit 保持为 `9ec69899`；上述 Temurin evidence 是其后 follow-up runtime gate 修复的重跑，
不改变原提交的时间线。

### 10.2 Decision register

| 决策 | Stage 1 结论 | 证据 / 后续归属 |
| --- | --- | --- |
| HdrHistogram vs in-repo histogram | 采用 HdrHistogram `2.2.2` | `ClusterCapacityMetricsTest` 直接验证 coordinated-omission 修正计数和 percentiles。 |
| simple validate-before-commit vs multi-entity change set | simple 使用 validate-before-commit；多实体使用 touched-entity compact change set | Stage 1 不改交易状态；Task 2 用相同 runner 验证。 |
| primitive map vs dense ring | 默认 primitive map | matching sequence 允许 gap，未取得可证明的密度约束；Task 5 负责实测。 |
| pooled owned command vs compact owned copy | 默认一次 compact owned copy | Aeron callback 生命周期外必须拥有数据；Task 6 负责 allocation 对照。 |
| segmented snapshot writer API | Aeron-publication chunk writer | Stage 1 只冻结 API 方向；Task 10 验证 pause、checksum 和恢复。 |
| G1 vs ZGC | 当前系统基线采用 ZGC | 六产品线串行基线均使用 ZGC 并记录 JFR/GC；同负载 G1 对照与生产规模矩阵仍待 Task 9。 |

### 10.3 not yet run

- 三节点 Aeron committed-command 开放环负载、leader kill、follower lag 和 Archive replay。
- 60 分钟 100k/s、10 秒 200k/s burst、24 小时 soak，以及四个连续 15 分钟增长窗口。
- 100 万用户、400 万活动订单、热门 symbol/user，以及 0 maker fill-depth。
- 六产品线串行基线已完成用户/做市账户、Treasury、持仓、资金费、保险覆盖、交割和期权结算守恒；仍缺生产规模
  的 maker fill-depth 与长时间混合负载。
- Kafka/Projector outage、outbox 上限、Archive 磁盘满、snapshot corruption 和 fail-closed restore。
- direct memory、有效 safepoint duration、replicated outbox maxima、签名 state/funds hash 和 G1/ZGC 对照。

剩余风险：Stage 1 负载很短且状态很小，fork 间最终裁决吞吐离散约 19%；JFR 本身和 JVM 启动占比较高；
accept/freeze 与并发入口基准刻意不完成 matching，只作为分阶段 control，不可纳入最终裁决分子。

### 10.4 本次 P0 落地与验证

本轮已落地：touched-user 成交更新、`(symbol,user)` 持仓索引、风险 `higher(cursor)`、所有 runtime index 的
非 delta fail-closed、treasury 增量同步，以及 pending matching 的有界 completion queue、单一 wakeup、直接
commandId 索引和 max in-flight backpressure。

clean 构建与受影响 reactor 全量测试：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am clean test
```

结果：service 232、protocol 59、instrument-api 13、product-api 12 个测试全部通过；另外定向 P0 回归 31 个测试、
风险/生命周期回归 29 个测试均通过。测试覆盖本地 runtime parity、撮合、资金费、风险、treasury、snapshot、
matching timer backpressure 和服务调度。六产品线系统级串行守恒与容量基线见 10.5；仍未执行项目 9.1 所列
100k/s 开放环、leader failover、Kafka/Projector 和长时间 soak，因此本报告不宣称 100k/s 或生产 SLO 已通过。

### 10.5 六产品线串行系统基线（2026-08-21）

本次按单产品线启动三节点 Core、对应 provider 和常驻做市进程，`WALLET_ENABLED=false`；每条线先执行真实
交易门禁，再由 `ClusterFundsReconcileMain` 通过 Aeron 查询用户、持仓、Treasury 和 liquidation work，最后在
独立 symbol/用户范围运行 `capacity-workers=1`、`PLACE_ONLY`、2 秒 warmup + 8 秒 measured 的串行容量基线。
六条线均为 ZGC；provider 和容量进程均开启 JFR `profile`、`FlightRecorderOptions=stackdepth=256`、GC/safepoint
日志。容量结果如下：

| ProductLine | 门禁/资金守恒 | accepted/finalized | p50 / p99 / p99.9 (us) | JFR 最长 GC pause |
| --- | --- | ---: | ---: | ---: |
| `SPOT` | PASS；BTC `2000=2000`，USDT `1000000002=1000000002` | `182/182` | `39256 / 120651 / 144441` | `0.0444 ms` |
| `LINEAR_PERPETUAL` | PASS；Core 用户/做市/Treasury 对账差 `0` | `218/218` | `27295 / 127008 / 525598` | `0.0443 ms` |
| `INVERSE_PERPETUAL` | PASS；BTC `2360=2360`，持仓/未完成 work 清零 | `198/198` | `37421 / 95158 / 102039` | `0.0437 ms` |
| `LINEAR_DELIVERY` | PASS；USDT `2000=2000`，结算后持仓归零 | `145/145` | `50888 / 164757 / 290193` | `0.1520 ms` |
| `INVERSE_DELIVERY` | PASS；BTC `2000=2000`，结算后持仓归零 | `134/134` | `52264 / 189792 / 207224` | `0.0432 ms` |
| `OPTION` | PASS；USDT `4000=4000`，行权/结算后持仓归零 | `137/137` | `48824 / 160301 / 231473` | `0.0426 ms` |

JFR 热点在六条线都以 Aeron/Chronicle `ScopedMemoryAccess.putByteInternal` 为首，容量短样本中占比约
`9.72%-19.51%`；其余样本主要落在 Aeron egress/driver、协议编码和有限集合遍历。六条线的 `jdk.ZYoungGarbageCollection`
与 `jdk.ZOldGarbageCollection` 均有记录，未观察到 Full GC；这些是 12-13 秒短样本，不能外推长稳或生产 p99。

证据文件保存在各自运行目录的 `evidence/*-capacity-serial.jfr`，例如：

```text
/tmp/surprising-mm-runtime-20260820-option2/mm-option-jfr-20260820b/evidence/option-capacity-serial.jfr
/tmp/surprising-mm-runtime-20260820-inverse-perp8/mm-inverse-perp-jfr-20260820i/evidence/inverse-perp-capacity-serial.jfr
```

本轮仍未完成 100k/s 开放环、multi-worker source-sequence 压测、leader/follower/cold recovery、Kafka/Projector
故障、direct-memory 账本和 24 小时 soak；因此当前结论是“六产品线可运行并通过串行资金安全基线”，不是“达到
100k/s 生产容量”。
