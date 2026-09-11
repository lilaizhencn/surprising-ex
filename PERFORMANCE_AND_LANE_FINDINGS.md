# 单节点 Aeron Cluster 性能与 Lane 问题清单

## 1. 测试范围和结论口径

- 环境：Google Cloud 16 vCPU / 32 GiB，单节点 Aeron Cluster，4 个 Lane、1 个 Matcher，本机单节点压测模型。
- 窗口：64、128；业务：MIXED、FILL_HEAVY；每轮预热 30 秒、测量 60 秒。
- 最终性能验收只采用 ZGC。此前使用 G1 的服务器轮次保留为历史诊断，不能与 ZGC 结果直接比较。
- 业务资金、终态、未完成请求和数据完整性均通过；因此当前主要是吞吐、尾延迟和 CPU 分布问题。
- 按用户要求，600 秒剩余产品线验证已停止；未执行的恢复项不声明通过。

## 2. 服务器压测发现

### 2.1 ZGC 结果

| 场景 | 吞吐（ops/s） | PLACE_ORDER p99（us） | Owner | Matcher | Lane0–3 |
|---|---:|---:|---:|---:|---|
| MIXED / 64 / plain | 222,232 | 5,660 | 98.87% | 31.58% | 99.95/99.95/99.97/99.95% |
| MIXED / 128 / plain | 263,248 | 10,895 | 99.12% | 34.52% | 99.98/99.98/99.96/99.98% |
| FILL_HEAVY / 64 / plain | 228,960 | 5,423 | 98.45% | 32.21% | 99.94/99.96/99.96/99.92% |
| FILL_HEAVY / 128 / plain | 237,144 | 11,108 | 99.08% | 31.54% | 99.94/99.96/99.96/99.96% |
| MIXED / 64 / profile | 229,198 | 5,631 | 97.08% | 33.56% | 99.14/99.18/99.16/99.20% |
| MIXED / 128 / profile | 226,194 | 12,574 | 97.86% | 33.03% | 99.13/99.13/99.15/99.17% |
| FILL_HEAVY / 64 / profile | 217,548 | 5,578 | 97.77% | 30.68% | 99.16/99.16/99.17/99.19% |
| FILL_HEAVY / 128 / profile | 213,439 | 12,820 | 98.14% | 29.49% | 99.17/99.21/99.19/99.21% |

600 秒 FILL_HEAVY / 128 / profile：

- 216,887 ops/s，PLACE_ORDER p99 为 12,648 us。
- 130,143,911 个业务操作，61,900,800 个 fills，unfinished=0。
- 资金和终态校验通过。
- ZGC pause 总量约 2.4–3.0 ms/60 秒，最大约 0.114 ms；并发 GC 时间约 6.5–8.1 秒。
- RSS 约 2.85–2.89 GiB，无 swap 增量。

结论：ZGC 停顿不是主要尾延迟来源；Owner、Lane 的持续 CPU 饱和和状态处理成本才是主因。

### 2.2 Owner、Matcher、Lane 利用率差异

**现象**

- Owner 长期 97%–99%。
- 4 个 Lane 长期约 99%。
- Matcher 只有约 29%–35%。
- Lane 已经持续饱和，不能再简单解释为“下游没有被压满”。
- Matcher 没有直接把完成结果交给 Lane；当前仍由 Owner 收集 Matcher completion，再进行终态提交和结果索引。

**原因**

1. Owner 串行执行 drainMatchingCompletions、命令轮询、终态提交、tombstone/result index 和 Owner publication。
2. Matcher 产出的结果必须经过 Owner 中转，增加 completion queue、状态同步和可见性维护成本。
3. Lane 虽然已经饱和，但 Matcher 的匹配计算量小于 Lane/Owner 的状态提交量，因此 Matcher CPU 低不表示系统还有可用吞吐。
4. 窗口从 64 增加到 128 后，排队、批量提交和结果可见性延迟增加，p99 明显恶化。

**解决方案**

- 将 Matcher completion 转换为按 Lane 分片的 LaneDelta，直接写入 Lane 的单写者输入队列。
- Owner 只处理顺序确认、跨 Lane 聚合和外部可见结果，不再逐 completion 重建 Lane 状态。
- 保留 sequence、终态和失败回滚语义，不能让 Matcher 直接修改 Owner 可读的共享 Map。
- 在改造前增加 match -> lane apply -> prepare -> publish -> owner commit 分阶段计时，验证收益来自路径缩短，而不是改变测试条件。

### 2.3 服务器上确认的 Owner 热点

JFR self samples 和 allocation 结果显示：

- MatcherPipelineGroup.drainMatchingCompletions
- SurprisingClusteredService.pollCommands/progressCommandsInScope
- TerminalTombstoneStore.indexEntity/unlinkClient
- PublishedLaneChanges.commitTerminalToOwner
- ConcurrentHashMap.get/replaceNode
- TradingOrderBatchCodec.decodeCommand
- OrderRuntime
- MatcherResult、CoreMatchingResult
- LanePublishedMap.Version
- ByteBuffer 和数组分配

**原因**

Owner 同时承担命令解码、completion drain、终态索引、Client identity 清理、Owner publication 和结果可见性，形成单线程串行瓶颈。

**解决方案**

- completion 先按 Lane 分片并在 Lane 内完成状态聚合。
- 终态结果和业务状态使用一次性 delta 提交，避免多次扫描同一实体。
- tombstone、client identity、result index 合并为一次终态索引操作。
- 对 ConcurrentHashMap 的 Owner 热路径做单写者结构替换或批量更新，避免每个实体单独 get/replaceNode。

### 2.4 分配和 GC 问题

**现象**

JFR 分配权重主要来自 Matcher/Lane/Owner 线程；热点对象包括：

- OrderRuntime
- ReservationRuntime
- BalanceRuntime
- PositionRuntime
- MatcherResult
- CoreMatchingResult
- LanePublishedMap.Version
- 临时数组、ByteBuffer、解码对象

**原因**

- 每个 fill 都复制订单、预留、余额、仓位等运行时状态。
- 一个实体在同一 settlement 内被多次更新时，重复创建中间版本。
- 每次 Lane publication 都创建 Version，重复更新会形成 previous chain。
- Matcher 结果先生成中间结果对象，再由 Owner 转换成 Lane/Owner publication 对象。

**解决方案**

- 在一个 settlement 内使用 lane-local mutable accumulator 或 FillAccumulator。
- 每个订单、用户、余额、预留、仓位在 settlement 结束时最多生成一个最终对象。
- Spot 路径补齐类似 derivative FillCursor 的 maker/taker 聚合。
- Matcher 结果改为紧凑的 primitive delta，避免 List.copyOf 和重复结果对象。
- 用可复用的 per-lane publication ring 替代每次更新创建 LanePublishedMap.Version。

### 2.5 30 万 ops/s 与当前 20 多万的差异

约 300,565 ops/s 的结果来自 G1、线程 CPU affinity 已修正的轮次；该轮普通下单 p99 仍为 4,739 us，未达到 p99 门槛。当前 20 多万是 ZGC、未采用同样绑核条件的结果。

因此差异来自多个测试条件变化：

- G1 与 ZGC 不同；
- 是否固定 Owner/Lane/Matcher CPU 不同；
- profile 会引入 JFR 采样成本；
- 单轮测量，没有统计置信区间；
- 机器调度、JIT、缓存和批处理节奏也会影响单轮结果。

不能把 300k 直接当作当前代码的稳定基线，也不能把 ZGC 的下降归因于单一 GC 原因。

### 2.6 其他服务器实验发现

- 强制自旋版本在 W64/plain 只有约 249,721 ops/s，Owner 约 97.6%，Matcher 约 35.4%；没有证明强制自旋能提升性能，后续轮次按门槛停止。
- 将 Owner 唤醒标记做 cache-line 隔离后，W64/plain 约 246,716 ops/s，仍未恢复；字段相邻提供了潜在 false sharing 证据，但没有 PMU 或时间线证据证明它是唯一原因。
- 第一轮 CPU affinity 脚本把新建的 Matcher/Lane 线程错误地继承到 CPU0，导致初始化条件无效；重试后才确认 Owner 固定 CPU0、其他节点线程避开 CPU0/8。
- 绑核后的 G1 轮次达到约 300,565 ops/s，但普通下单 p99 仍为 4,739 us，且这是不同 JVM/部署条件下的单轮结果，不能作为稳定基线。
- G1 轮次约有 1.214% 测量时间在 Young GC，最大暂停约 6.731 ms；MaxGCPauseMillis=2 只是调节目标，不是暂停上限，不能据此把 p99 慢请求逐一归因到 GC。
- JFR/profile 会改变 CPU 和分配行为，profile 结果只用于热点诊断，不能与 plain 结果混作性能结论。
- 恢复夹具期间发现过进程组清理和 Aeron CNC liveness 等测试夹具问题，已修正；它们不是产品吞吐缺陷。
- 已完成的恢复校验覆盖 SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL 的 execute→kill→replay→snapshot；LINEAR_DELIVERY 只完成 execute/replay，INVERSE_DELIVERY 和 OPTION 未执行，不能声明全部恢复场景通过。

## 3. Lane 处理逻辑的瓶颈

### 3.1 每个 fill 重建不可变状态

**问题**

Spot 每个成交会更新 taker、maker 的订单、余额和预留；Derivative 只对 taker 使用 FillCursor 聚合，maker 仍按 fill 更新。OrderRuntime、ReservationRuntime、余额和仓位对象被重复创建。

**原因**

当前状态模型以不可变 record/替换为主，replaceOrder、replaceBalance、replaceReservation、replacePosition 会在成交过程中立即落地。

**方案**

- 增加 settlement 级 FillAccumulator。
- 累积订单 fill、剩余预留、余额变化、仓位变化和手续费。
- settlement 结束时一次性生成最终状态并提交。
- Maker 和 taker 使用同一套聚合逻辑。

### 3.2 一次更新维护多套二级索引

**问题**

订单更新同时触碰：

- orders
- activeOrderIdsByUser
- admissionOrderIndex
- clientOrderIndex
- reservation 相关索引
- 用户 revision

余额、仓位和预留也有多层 Map、反向索引和集合。

**原因**

这些索引服务于不同查询，但目前在每次成交中同步维护，造成多次哈希查找、替换和集合操作。

**方案**

- 热路径只更新权威 Lane state 和 dirty bit。
- settlement 结束时批量更新二级索引。
- 将同一用户的订单/预留/仓位索引收敛为 UserLaneIndex，减少嵌套 Map 和重复用户定位。
- 保留确实需要 O(1) 校验的索引，不盲目删除。

### 3.3 Lane 到 Owner 的重复物化和发布

**问题**

当前链路包含：

Lane state -> PublishedLaneChanges -> RuntimeStateMaterializer -> Runtime index value -> LanePublishedMap.Version -> Owner changed sets -> published maps -> result index

同一批订单、仓位、预留会被多次遍历和转换。

**原因**

Lane 状态、Owner 可见状态、终态结果索引和回滚结构各自维护一份中间表达。

**方案**

- 用一个 LaneDelta 表达 key、dirty mask、最终值、terminal flag、sequence。
- Owner 只按 sequence 应用一次。
- rollback 只保留失败路径需要的 before snapshot，不参与成功路径的重复物化。
- 将 prepareLaneTerminal、preparePublication、commitTerminalToOwner 合并成明确的两阶段：Lane finalize、Owner apply。


### 3.4 中间状态和阶段状态过多

**问题**

一次 settlement 同时经过 MatcherSettlementEvent、MatcherSettlementChanges、PublishedLaneChanges、RuntimeIndexedChangeBuffer、LaneBalancePatches、LanePublishedMap.Version、Owner changed sets 和终态索引。OrderBatchPending 还维护多个 admission/matching/commit/finish 阶段标志。对象池降低了部分分配，但没有消除多层引用、遍历和状态切换成本。

**原因**

业务状态、Owner 可见状态、回滚 before snapshot、结果索引和批处理协议状态没有统一的变更模型，各模块分别保存自己的中间结果。

**方案**

- 成功路径统一使用一个按 Lane 分片的 LaneDelta；每个实体只记录一次 dirty mask、最终值和终态信息。
- rollback before snapshot 只在需要恢复时建立，不让成功路径重复构造。
- 将 OrderBatchPending 的多个布尔字段收敛成有限阶段枚举加少量异常标志，保留重试和提交顺序语义。
- 将 prepared index value、published value、terminal index value 能复用的部分合并，避免同一实体重复 materialize。

### 3.5 LanePublishedMap.Version 链和并发 Map 成本

**问题**

每次 stage/put 都创建 Version，保留 previous 链；Owner 读取时还要解析可见版本。Owner 侧 ConcurrentHashMap.get/replaceNode 已出现在热点中。

**方案**

- 每 Lane 建立单写者 publication ring。
- 每个实体只保留最新未提交值和 sequence。
- Owner 按顺序消费并发布；提交后直接回收旧值。
- 保留 publication visibility 和 receipt 顺序，不允许跨线程直接修改 Lane 权威状态。

### 3.6 Lane 内重复查找和校验

**问题**

MatcherSettlementEvent 进入 Lane 后，仍需按成交逐笔查询订单、确认 open 状态、解析用户/资产/方向，再执行状态更新。

**方案**

- Matcher 生成 settlement plan 时预计算 maker/taker 的 lane、user、asset、side、终态信息。
- Lane 只做必要的一致性校验和状态应用。
- 生产路径减少重复拓扑查询；调试或抽样模式保留完整校验。

### 3.7 pending reservation 状态镜像

**问题**

Lane 的 pendingReservation* 与 Owner 的 PendingReservationTracker 都保存 sequence、用户计数、总数和批次状态。

**原因**

Owner 需要按顺序提交和恢复，但目前同时维护了较多业务计数镜像。

**方案**

- Lane 保留 pending reservation 的权威计数和金额。
- Owner 只保留 sequence 到未完成订单/批次的引用和必要的 in-flight 标量。
- 用户维度计数从 LaneDelta 或完成事件派生，避免双写。

### 3.8 热状态和冷状态混在 AccountLaneState

**问题**

订单、余额、预留、仓位等热状态与 risk snapshot、trigger、liquidation、leverage、snapshot/rollback 等冷状态位于同一大对象中。

**影响**

不是每个 Map 都是当前吞吐瓶颈，但状态边界不清晰，增加缓存和维护复杂度，也容易在热路径误触冷结构。

**方案**

按职责拆分：

- HotTradingState
- RiskState
- TriggerState
- LiquidationState
- SnapshotRollbackState

先迁移结构，不改变业务语义，再根据 JFR/计数确认哪些结构真正进入热路径。

## 4. 设计中需要保留、不能直接删除的部分

- openReduceOnlyQuantity 扫描活动订单可以优化，但主要是 trigger admission 冷路径，不是当前持续成交主瓶颈。
- OrderBatchPending 的多个阶段标志较多，但承担批处理、重试、异常恢复和提交顺序；应合并状态机，而不是直接删除字段。
- rollback、terminal sequence、publication visibility 是正确性约束；优化必须保持 crash/replay、终态顺序和 Owner 可见性。
- 不建议让 Matcher 直接写 Owner 共享 Map，也不建议把所有业务状态合并成一个无边界的共享大 Map。

## 5. 建议实施顺序

1. 先增加 Lane 分阶段指标：applyFill、索引维护、prepareLaneTerminal、publication、Owner commit、tombstone/result index。
2. 实现 settlement 级 FillAccumulator，先覆盖 Spot，再统一 Derivative maker/taker。
3. 将多次索引更新改成 dirty bit + settlement 末批量更新。
4. 实现 Matcher 到 Lane 的按 Lane LaneDelta 直接路径。
5. 合并 Lane→Owner 的物化和提交路径。
6. 用 publication ring 替换 LanePublishedMap.Version 链。
7. 最后拆分冷状态和整理批处理状态机。
8. 每一步使用相同 ZGC、相同 CPU 配置、64/128 两个窗口，重复 plain/profile，并同时比较吞吐、p99、Owner/Lane CPU、分配速率和业务校验。

当前优先级结论：

> 第一优先级不是继续调 GC，而是减少每个 fill 的状态复制和 Lane→Owner 的重复提交；第二优先级是实现 Matcher→Lane 的直接 delta 路径；第三优先级才是整理二级索引、冷状态和阶段状态。

## 4. 本次修复（第 2、3、4、6 项）

- **第 2 项：Lane→Owner 变更模型**：将 Lane 交接缓冲统一命名并收敛为 `LaneDelta`；订单、预留、持仓、用户及终态发布收据由同一个 Lane delta 交接，旧 `PublishedLaneChanges` 仅保留兼容别名，不再产生第二份状态。
- **第 3 项：发布版本和终态索引开销**：`LanePublishedMap.stage` 的正常写入从“get + 失败 replace + put”改为一次定位后的 replace/putIfAbsent；终态订单改为批量 sink，tombstone 使用一次实体探测的 `putIfAbsent`，避免同一终态 ID 先 contains 再 put。
- **第 4 项：pending reservation 与批处理阶段状态**：Matcher settlement 的 pending reservation 取消按用户合并计数后一次更新 Owner 镜像；`OrderBatchPending` 的 8 个独立布尔阶段压为一个生命周期位图，保留原有阶段语义和重试顺序。
- **第 6 项：Owner 终态提交链**：Lane delta 先收集终态订单，再一次批量提交 tombstone/result retention；成功路径不再逐订单调用终态 sink，保留旧 sink 的兼容默认实现。

验证：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`，820 tests，0 failures/errors。此次未重新执行 GCP 16c32g 64/128 的吞吐压测，因此 CPU、吞吐和 p99 的收益仍需下一轮短测确认；Matcher→Lane 绕过 Owner 的直接路径（第 1 项）及 AccountLaneState 冷热拆分（第 5 项）未在本次范围内改动。
