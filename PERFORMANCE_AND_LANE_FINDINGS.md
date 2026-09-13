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

- Matcher completion 在 worker 槽位绑定 sequence；Owner 完成一次 plan 校验后，按 Lane 派发不可变 `MatcherSettlementEvent`，事件只进入对应 Lane 的单写者输入队列。
- Owner 只处理顺序确认、跨 Lane 聚合和外部可见结果，不再为 completion 重新复制 `CoreMatchingResult`；Lane 负责状态应用和 `LaneDelta` 生成。
- 保留 sequence、终态和失败回滚语义，不能让 Matcher 直接修改 Owner 可读的共享 Map。
- 在改造前增加 match -> lane apply -> prepare -> publish -> owner commit 分阶段计时，验证收益来自路径缩短，而不是改变测试条件。

### 2.3 服务器上确认的 Owner 热点

JFR self samples 和 allocation 结果显示（发布链路简化前的基线）：

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

Owner 同时承担命令解码、completion drain、终态索引、Client identity 清理、Owner publication 和结果提交，形成单线程串行瓶颈。上面标为旧实现的 `PublishedLaneChanges`、`ConcurrentHashMap` 和 `LanePublishedMap.Version` 已在本轮简化中删除，不能作为当前版本的热点结论。

**解决方案**

- completion 先按 Lane 分片并在 Lane 内完成状态聚合。
- 终态结果和业务状态使用一次性 delta 提交，避免多次扫描同一实体。
- tombstone、client identity、result index 合并为一次终态索引操作。
- 对 Owner 热路径继续做批量更新，避免每个实体单独重复索引。

### 2.4 分配和 GC 问题

**现象**

JFR 分配权重主要来自 Matcher/Lane/Owner 线程；热点对象包括：

- OrderRuntime
- ReservationRuntime
- BalanceRuntime
- PositionRuntime
- MatcherResult
- CoreMatchingResult
- 临时数组、ByteBuffer、解码对象

**原因**

- 每个 fill 都复制订单、预留、余额、仓位等运行时状态。
- 一个实体在同一 settlement 内被多次更新时，重复创建中间版本。
- （旧实现）每次 Lane publication 都创建 Version，重复更新会形成 previous chain。
- Matcher 结果先生成中间结果对象，再由 Owner 转换成 Lane/Owner publication 对象。

**解决方案**

- 在一个 settlement 内使用 lane-local mutable accumulator 或 FillAccumulator。
- 每个订单、用户、余额、预留、仓位在 settlement 结束时最多生成一个最终对象。
- Spot 路径补齐类似 derivative FillCursor 的 maker/taker 聚合。
- Matcher 结果改为紧凑的 primitive delta，避免 List.copyOf 和重复结果对象。
- 已用 Owner-only primitive map 加 `LanePublication` 批量槽位替代 Version 链；仍需通过压测确认 Owner 写入成本的变化。

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

Lane state -> MatcherSettlementChanges/LaneDelta -> LanePublication -> Owner published maps -> Owner changed sets/result index

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

一次 settlement 仍会经过 MatcherSettlementEvent、MatcherSettlementChanges、RuntimeIndexedChangeBuffer、LaneBalancePatches、LaneDelta、Owner changed sets 和终态索引；但旧的 `PublishedLaneChanges`、Version 链和可见性回收队列已经删除。剩余复杂度主要来自回滚、资金核对和终态顺序，不能直接删掉。

**原因**

业务状态、Owner 可见状态、回滚 before snapshot、结果索引和批处理协议状态没有统一的变更模型，各模块分别保存自己的中间结果。

**方案**

- 成功路径统一使用一个按 Lane 分片的 LaneDelta；每个实体只记录一次 dirty mask、最终值和终态信息。
- rollback before snapshot 只在需要恢复时建立，不让成功路径重复构造。
- 将 OrderBatchPending 的多个布尔字段收敛成有限阶段枚举加少量异常标志，保留重试和提交顺序语义。
- 将 prepared index value、published value、terminal index value 能复用的部分合并，避免同一实体重复 materialize。

### 3.5 Lane publication 的旧版本链（已处理）

**问题**

旧实现每次 stage/put 都创建 Version，保留 previous 链；Owner 读取时还要解析可见版本，并产生并发 Map 成本。

**方案**

- 已改为 Owner-only primitive map 加 `LanePublication` 批量槽位。
- Owner 在有序提交点一次应用整批 key/value，然后立即清空槽位。
- 不再有 Version previous 链、visible/commit 状态或额外回收队列。
- 仍保留 publication 顺序，不允许跨线程直接修改 Lane 权威状态。

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
5. 合并 Lane→Owner 的物化和提交路径（已完成：Owner 直接应用 LanePublication 批次，Lane 只填充发布槽位）。
6. 用 Owner-only primitive map 和 `LanePublication` 批量槽位替换 LanePublishedMap.Version 链（已完成；无 visible/commit 状态和额外回收队列）。
7. 最后拆分冷状态和整理批处理状态机。
8. 每一步使用相同 ZGC、相同 CPU 配置、64/128 两个窗口，重复 plain/profile，并同时比较吞吐、p99、Owner/Lane CPU、分配速率和业务校验。

当前优先级结论：

> 第一优先级不是继续调 GC，而是减少每个 fill 的状态复制和 Lane→Owner 的重复提交；第二优先级是实现 Matcher→Lane 的直接 delta 路径；第三优先级才是整理二级索引、冷状态和阶段状态。

## 4. 本次修复（第 2、3、4、6 项）

- **第 2 项：Lane→Owner 变更模型**：将 Lane 交接缓冲统一命名并收敛为 `LaneDelta`；订单、预留、持仓、用户及终态发布收据由同一个 Lane delta 交接，旧 `PublishedLaneChanges` 兼容别名已删除。
- **第 3 项：发布版本和终态索引开销**：`LanePublishedMap.stage` 的正常写入改为 Owner 提交点的 primitive key/value 批量发布，移除不可见 `Version`/`previous` 链、CHM 和额外回收任务。终态订单继续使用批量 sink，tombstone 使用一次实体探测的 `putIfAbsent`，避免同一终态 ID 先 contains 再 put。
- **第 4 项：pending reservation 与批处理阶段状态**：Matcher settlement 的 pending reservation 取消按用户合并计数后一次更新 Owner 镜像；`OrderBatchPending` 的 8 个独立布尔阶段压为一个生命周期位图，保留原有阶段语义和重试顺序。
- **第 6 项：Owner 终态提交链**：Lane delta 先收集终态订单，再一次批量提交 tombstone/result retention；成功路径不再逐订单调用终态 sink，保留旧 sink 的兼容默认实现。Matcher completion drain 同时去掉每个结果的 route `get`，完成头所属 shard 直接执行一次 `removeKey`。

验证：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`，821 tests，0 failures/errors。此次未重新执行 GCP 16c32g 64/128 的吞吐压测，因此 CPU、吞吐和 p99 的收益仍需下一轮短测确认；Matcher→Lane 绕过 Owner 的直接路径（第 1 项）仍受 sequence、回滚和权威状态约束，详见下一节。

## 6. 本轮顺序修复

- **Matcher completion 热路径**：`MatcherCommandPipeline` 在 Matcher worker 发布槽位时绑定 `coreSequence`。Owner 的 `publishMatchingCompletion` 只校验并转交不可变结果，不再在 `drainMatchingCompletions` 中调用 `withCoreSequence` 创建第二个 `CoreMatchingResult`。控制命令仍保持原返回类型。
- **Lane 冷状态边界**：新增 `LaneColdState`，将清算、风险快照、杠杆、算法单和触发单及其反向索引从 `AccountLaneState` 热对象移出；仍由原 Lane 单线程拥有，快照、回滚、查询和状态哈希沿原路径访问。订单、余额、预留、持仓及其成交索引保持热路径布局。
- **成交级合并**：Spot 和 Derivative 的 settlement accumulator 已在前一轮完成，maker/taker 同一结算内只发布一次最终订单、余额、预留和仓位状态；本轮未重复引入第二套 accumulator。

当前仍明确保留的边界：Matcher 线程不能直接修改 Lane。Matcher 只有撮合结果，没有可用于校验、回滚和重放的 Lane 权威状态；让它跨线程写 Lane 会破坏 sequence、失败回滚和 snapshot fence。因此当前安全路径是 Owner 完成一次 plan 校验后，把一个不可变 `MatcherSettlementEvent` 按 Lane 投递，Lane 完成状态应用，Owner 只做有序收据和终态索引提交。Lane→Owner 的 published map 已改为 Owner 交接点应用 `LanePublication` 批量收据；不再保留 `Version` previous 链、visible/commit 状态或额外回收队列。

本轮验证：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`，821 tests，0 failures/errors；发布/Matcher/tombstone 和 RuntimeChangeBuffer 定向路径均通过；本机 ZGC JMH/JFR 结果已追加到 `PERFORMANCE_VALIDATION.md`。尚未重新执行 GCP 16c32g 的 64/128 ZGC 压测，所以 Owner CPU、吞吐、p99 和分配速率的云端收益不能提前宣称。

## 7. Owner 空转门禁（2026-09-12）

持续 Owner 在调用完整 `pollCommands()` 前增加廉价门禁：只有入站命令、在途窗口或 Matcher/Lane 完成通知存在时才进入命令推进；空轮直接交给既有退避和唤醒协议。该改动只减少空队列下的作用域重建与扫描，不改变日志顺序、完成等待或 Owner 唯一写权限。相关定向测试 232 项、service 全量 821 项通过；尚未用云端数据宣称 CPU/吞吐收益。

本机持续 Owner 短测（ZGC、LINEAR_PERPETUAL、4 Lane/1 Matcher、BLOCKING、batch1）为 98.544 次 `place+cancel` 调用/s，约 50,455 业务操作/s；终态与接收计数一致。该短测只证明真实 Owner 输入/完成/退避路径可运行，不能替代 GCP 64/128 窗口复测。

随后按此前 `productionMixedWorkload` 条件复测（ZGC、4 Lane/1 Matcher、1000 users、4 symbols、16 rounds、batch20、预热5×5s、测量3×5s），无 profiler 为 `220435.137 ± 50114.671 ops/s`，此前同口径为 `222773.790 ± 35414.744 ops/s`，均值差约 -1.05%；因此当前门禁在持续饱和负载下没有确认吞吐提升，Owner 的高负载串行瓶颈仍需云端和热点级改造验证。

## 8. 第二轮结构清理（2026-09-13）

- `RuntimeChangeBuffer` 的发布表入口改为 `drainToPublishedMap`；Eclipse/Agrona 两类目标 Map 也分别命名，删除始终为 `null` 的 lane 元数据参数，避免把三条不同交接路径混成一个重载。
- `SettlementLaneWorker.requestedHandoff` 改为 volatile，明确 Owner 写入到 Lane 读取的交接可见性。
- 未删除 `RuntimeChangeBuffer.swapStorage`、`LaneSequenceQueue`、控制派发器和回滚缓冲：它们仍被索引变更子类、顺序通知或恢复流程实际使用，删除会改变所有权和重放语义。
- 本轮定向测试 58 项通过；随后服务模块全量 **821** 项通过。

## 全交易链路整改跟踪（2026-09-13，重新按当前代码审查）

目标：Owner 只作顺序协调；运行中的账户状态始终由所属 Lane 写入；减少完整状态往返。最终验证为本机真实单成员 Aeron Cluster，HotSpot JDK 25 + ZGC。以下项目全部完成且有证据前不得宣称整体完成。

| 编号 | 要求 | 完成证据 | 当前状态 |
|---|---|---|---|
| A1 | 收缩账户/symbol/对手方依赖及入口队首阻塞 | 依赖正确性、共享 maker、同账户有序用例；实际 fence/队列等待 | 撤单已按被撤流动性方向/价格收缩；普通单交叉与账户依赖仍待重构，未完成 |
| A2 | 减少 Owner 准入/撮合/结算/提交重复处理 | 事件流及消费次数核对；分阶段 CPU/延迟 | 延期推进已从全表扫描改为既有队首选择；单项batch重复拒单检查已移除；撮合收集/提交重复阶段仍未完成 |
| A3 | 移除运行中账户写入权交接 | 所有控制/批量/失败路径由 Lane 写入；线程所有权测试 | 显式/内部触发扫描、顺序PLACE准入、AMEND冻结及原生拒绝已迁移永久Lane；顺序批单/改单新增零handoff断言通过。其余控制、单笔非流水路径及失败边界仍待核对，未完成 |
| A4 | Owner 区分可推进工作和等待，避免在途空转 | 通知交错/无丢唤醒测试；有效工作与等待 CPU | 初步实现；通知与服务回调测试通过，真实集群调度待验 |
| M1 | 普通订单结算计划和数组复用 | 跨命令/跨 Lane/拒单复用正确性；稳态分配 | 序号槽复用已实现；39项核心及六产品线普通单/batch功能通过，稳态待验 |
| M2 | 变长 batch storage 容量复用 | 大小交替、尾槽不执行、不泄漏前代引用 | 已实现；六产品线功能通过，性能待验 |
| M3 | pending reservation 小 Map 周期性分配 | 冻结/消耗/释放/回滚与容量边界 | 已改为按资产复用；功能通过，JFR/性能待验收 |
| M4 | 缩小 Lane 到 Owner 的索引和状态转换 | 活跃订单/持仓/ADL/风险/查询一致性 | 发布缓冲复用已实现；活跃订单索引已复用Lane不可变OrderRuntime，删除热路径CoreOrderState预计算副本及订单prepared列分配；其它完整状态往返仍待精简 |
| M5 | 幂等结果一次构造，消除重复包装 | 重复命令、淘汰、响应字节所有权、恢复 | 已实现提交路径一次构造；功能通过，性能待验 |
| M6 | 入口/出口包装及 payload 生命周期 | 背压、断连、角色切换、缓冲不提前复用 | 普通PLACE/CANCEL及其流水批次已一次生成最终撮合结果/证据，撤单移除逐项Supplier链；内部OCO分页不复制ID列表；其余入口出口包装及payload生命周期仍未完成 |
| S1 | 合并重复在途状态及结果引用 | 阶段转移、乱序完成、拒单、回收、重放 | 独立批次Map和装箱序号已删除，批次上下文归命令环；删除无生产调用的整份PendingMatching复制分支，payload续写保留身份；其它重复阶段/结果引用仍待收敛 |
| T1 | Lane 等待/通知和热点账户调度 | 无丢通知；park/自旋、各 Lane 队列和线程指标 | 待实现 |
| V1 | 更新实际受影响的基准与正确性用例 | 真实调用路径；资金守恒、订单/持仓/冻结、快照恢复 | 待验证 |
| V2 | 本机真实单节点性能和 JFR 验证 | 预锁场景；terminal ops/messages/fills、分配、GC、尾延迟、NMT、线程/锁、长期状态 | SPOT与U永续真实单成员/JFR已执行；U永续128币对资金、内部触发与同Archive/snapshot重启已核对；吞吐目标、全产品和完整指标长稳未验收 |

30 万 terminal business ops/s 为待验证目标，不预先承诺已达到。普通命令、batch items、fills 分开计数；初始化/恢复分配不计入稳态。性能记录只追加至 PERFORMANCE_VALIDATION.md；本轮生成的大体积产物在摘要记录后清理。


### A3 补充进展（2026-09-13 08:26）

PROBE_INCREMENT、VERIFY_STATE_HASH、UPDATE_CANCEL_ALL_AFTER 已移除无账户写入的准备期全 Lane 交接；服务实测断言交接代次不增长，六产品线定时器控制与成交/恢复功能通过。其余账户路径、失败回滚交接仍待迁移，不能视为 A3 完成。验证记录见 PERFORMANCE_VALIDATION.md 当次追加记录。


### A3 算法单迁移（2026-09-13 08:32）

UPSERT_ALGO_ORDER 已由所属 Lane 异步执行；Owner 仅读取已发布不可变引用并在完成后更新索引。创建重复 client 检查不再构造全 Lane TreeMap；恢复/回滚同步新增 Owner 索引。829 项 service 测试及六产品线算法更新/成交/恢复组合功能通过；故障注入与真实性能仍待验证。其余交接、回滚架构尚未完成。


### A3 回滚拆分进展（2026-09-13 08:38）

冷账户状态恢复与 Owner 发布索引恢复已分离；每 Lane 一次恢复已有 before images，替换每个变更键遍历全部 Lane 的任务组织。算法单写入后全局版本失败，回滚并再次更新通过；六产品线故障/交易/恢复功能通过。尚需把余额、订单、持仓、预留等账户回滚一起异步派发，再移除调用方交接，不能标为完成。


### A3 异步控制失败回滚（2026-09-13 08:46）

账户前值恢复与Owner发布索引恢复已分离。异步控制失败由永久Lane完成普通/批量预留、订单、持仓、余额、用户和冷状态恢复，Owner等待后收尾；移除ControlLaneDispatcher失败交接与pollDirectCommand失败交接。写入后故障全过程无交接断言通过；service831项和六产品线18项组合功能通过。同步提交收尾异常和其他控制路径仍待迁移，真实单节点性能未执行；A3仍不能标为完成。


### A3 提交收尾拒绝（2026-09-13 08:52）

异步直接命令的派生撮合容量失败、盖章IllegalStateException已转入现有Lane回滚续步；失败不直接写账户，拒绝留存账本并保持重复查询协议。故障专项与六产品线故障后成交/恢复通过。成功收尾的同步账户操作和其他控制入口仍待迁移，A3未完成。


### A3/M4 成功盖章路径（2026-09-13 08:58）

普通异步撮合已在MatcherSettlementEvent内盖章，不能误报为Owner重复盖章。通用盖章已删临时ID列表，但仍同步；runtime.replaceOrder同时写账户和Owner变更集合，直接搬到多个Lane会形成共享集合写入风险。下一步应分离Lane账户盖章与Owner发布，尽量合入LaneCommitEvent现有任务。833项service与六产品线连续改单功能通过，实际分配/CPU及真Cluster未测。


### A3 异步直接命令盖章合并（2026-09-13 09:09）

订单盖章已合入LaneCommitEvent现有任务，Owner只预检查输入并在收集后发布变更；Lane不写Owner变更集合。primitive ID与结果引用缓冲按需复用、有效范围清理。全量834项、六产品线与事件池21项组合及发布回收定向测试通过。成功收尾触发取消等剩余路径尚未迁移，A3整体未完成；真Cluster/JMH/JFR未执行。


### A3 直接命令关闭持仓后的触发取消（2026-09-13 09:19）

Owner仅选择ID，LaneCommitEvent在原任务内取消触发；Owner收集后统一版本/索引发布。移除按触发拼接持仓身份字符串。双Lane可见性/版本/复用与18项组合功能通过。内部OCO/扫描同步路径仍待迁移。新增必须补的生命周期验证：普通成交平仓前不显式撤销止盈止损，验证平仓后自动清理；既有benchmark提前显式撤销，不能覆盖此项。


### 普通成交平仓触发遗漏已复现并修复（2026-09-13 09:29）

五条衍生品线均复现普通平仓后PENDING止损残留。已在Lane结算终态按最终持仓为0取消匹配的PENDING触发，使用现有LaneDelta回传、Owner合并版本；无新增账户任务。五产品完全平仓与部分平仓保留边界通过；835项service和16项组合功能通过，资金/恢复正常。HEDGE、反向穿零、保留触发的批量平仓专项与真实性能仍待验证。


### 2026-09-13 风险续扫收尾迁移进展
- 已去除无触发工作的普通风险续扫/空续扫账户接管；批量风险收尾改为异步Lane提交和Owner顺序发布，消除该阶段的全Lane交接和同步等待。
- 批量初始准备、实际触发/OCO等路径仍保留同步交接，A3仍部分完成；A1/A2及其余内存/中间状态问题未据此关闭。
- 服务835项、五衍生品风险/批量平仓15项通过；性能尚未采集，详细参数、失败修复和产物清理记录仅在PERFORMANCE_VALIDATION.md。


### A1 依赖诊断及撤单范围收缩

`SurprisingClusteredService` 修正已在排空时漏计阻塞的路径，按订单ID、撮合范围、持仓量、账户、重复请求区分决策；真实单节点已确认客户端在途量不能代表服务端窗口并行度，数值和采集范围见 `PERFORMANCE_VALIDATION.md` 本轮记录。计数包含重新判定，不等于不同命令数或阻塞耗时。

`TradingCoreRuntime.addCancelScope` 使用被撤订单的方向和撮合价格，移除普通撤单的整个symbol屏障。保留同账户、同订单、可能吃到被撤流动性的依赖，以及衍生品已有持仓量约束；六产品线串行一致性和快照恢复用例已覆盖。普通PLACE之间的交叉范围等待、Owner重复协调和A3剩余所有权交接未解决；不能将此项当作A1整体完成。

### M4 准入索引聚合复用

`AccountLaneState.LaneAdmissionOrderIndex` 在账户、币对、方向、仓位/保证金模式和reduceOnly范围相同时直接更新剩余数量，保留原聚合及用户索引；元数据盖章不再经过删除最后一项、销毁空聚合、重建聚合的中间状态。跨范围、终态移除和失败回滚保持业务语义，功能用例覆盖多订单与枚举边界；完整状态发布仍待精简，性能收益尚未证明。


### M4 发布索引周转重建

真实混合交易JFR定位 `LanePublishedMap.applyPublished` 下 `LongObjectHashMap.rehashAndGrow` 持续分配。Owner独占发布表改用已有Agrona primitive map，删除压紧探测链；准入序号为0时移除元数据条目。没有新增账户状态副本。原周转测试只检查Map身份，现已改为检查内部keys/values/sequence数组身份，并覆盖准入→终态→重入及key0。全量功能及同场景真实JMH/JFR复采已完成，目标旧分配栈未再采到；吞吐尚未证明提升，整体架构未完成。详情和数值只记录在 `PERFORMANCE_VALIDATION.md`。


### A3 显式触发执行与真实恢复（2026-09-13）

显式触发的OCO取消、claim、子单冻结和本地pending标记由所属Lane一次执行；Owner保留条件/身份准备、全局pending索引及子单顺序协调。未增加触发阶段容器。6产品成交0/1/10、ID冲突、余额不足与溢出回滚功能覆盖通过，service852项通过。U永续128币对真实单成员触发、同Archive重放、有效snapshot重启金融状态核对通过。内部价格扫描路径仍使用同步执行，A3未完成；A1/A2及完整状态往返、稳态分配和吞吐仍待解决。性能与失败轮次摘要仅见PERFORMANCE_VALIDATION.md。


### A3 单项普通PLACE batch（2026-09-13）

删除“少于2项必须顺序执行”的分流，单项普通batch复用已有Lane批量准入/撮合/结算。Lane已拒绝并回滚唯一项时直接保留拒绝结果有序提交，不再重复准入；无剩余项不请求全Lane交接。六产品挂单/成交/溢出拒绝的交接代次不增长、状态/快照一致性通过；215项流水测试及24项批量/故障测试通过。前置无法流水的校验、reduce-only、AMEND与多项部分拒绝仍需处理，不能视为整个顺序batch迁移完成。真实性能结果仅追加到PERFORMANCE_VALIDATION.md。


### A2 延期推进与空撤单容器（2026-09-13）

Owner以现有batch/deferred登记表的序号队首推进，删除在途表predicate全扫；未新增索引或状态。没有平仓容量冲突时复用已排序自成交撤单数组，双空不再建立合并HashSet。跨队首/全拒绝batch顺序与后继唤醒、859项service测试通过；128币对真实金融和snapshot恢复通过，采样未见原findFirst栈。整体分配仍高，账户活动单/预留/持仓索引的Set反复分配、完整状态往返及Owner完成收集尚待处理；性能指标仅见PERFORMANCE_VALIDATION.md。


### M4 Lane索引成员变化（2026-09-13）

预留金额/资产替换合并为一次Lane操作，保留同一用户/订单的索引成员；持仓数量或元数据变化保留同一user/position及活动symbol成员，只在新增、归零/重开或symbol变化时维护索引。未加入池/缓存；移除最后成员仍清空索引。59项专项及861项service通过，128币对batch20真实金融和snapshot恢复通过。原预留/持仓更新拆建Set栈未在JFR中出现；新订单索引首次创建仍分配Set。完整订单/撮合结果对象及Owner阶段重复未完成，指标只见PERFORMANCE_VALIDATION.md。


### Owner 活跃订单重复状态：当前源码核对

- `TradingRuntimeState.MatcherSettlementChanges.prepareLaneTerminal` 为每个仍OPEN的订单调用 `RuntimeStateMaterializer.orderSnapshot`，同时LanePublication发布原始不可变OrderRuntime；`RuntimeIndexedChangeBuffer<OrderRuntime, CoreOrderState>`因此同时保存同一订单的完整运行态与完整快照。
- `RuntimeFactIndexes.preparedOrder` 把快照交给 `ActiveOrderIndex.ordersById`，后者长期保留CoreOrderState。真正准入/参与方索引只读user/symbol/side/matchingPrice/remainingQuantity/reduceOnly/marginMode/positionSide，快照其余字段主要供orders()/activeOrder()返回。生产activeOrder读取只有TradingCoreRuntime的撤单路由与依赖范围两处，不需要完整快照。
- 不能直接删除发布表：Owner目前order()/reservation()/position()、风险/查询及提交边界仍读取已提交版本；改为直读Lane最新可变表会暴露尚未提交状态。也不能只删除Lane预计算而把orderSnapshot搬回Owner，那只是转移分配和CPU。
- 已实施：活跃索引引用同一不可变OrderRuntime；Owner每个活跃订单仅保留一个可复用条目（订单引用、symbol身份），更新只替换引用，不生成完整CoreOrderState。完整快照物化限制到orders()/查询及恢复边界；热路径两处改读索引必需字段。订单不分配prepared快照列，preparedOrder分支已删除，持仓预计算保留。
- 验证必须覆盖：部分成交后remainingQuantity、同账户改价/改symbol/改side参与方计数、最后订单移除、撤单依赖收缩、快照重建与查询字段等价；真实128币对单成员JMH/JFR确认CoreOrderState原Lane物化调用栈消失。该项实现及service866项测试已通过，JFR验证记录另列；其它完整状态往返仍未完成，M4保持部分完成。


### 跨页控制命令参与Lane登记遗漏（回归实测）

- 现象：RiskBatchBudgetTest跨币对/快照恢复时，LIQUIDATION_BATCH终态提交实际Lane mask=5，Context只登记4，触发fatal divergence；不是资金差或撮合结果不一致。
- 原因：异步风险续页最终只从当前runtime delta登记Lane，挂起/恢复期间累计在commandChangedUserIds中的前页账户被漏掉。
- 修复：控制续页完成后先汇总全部变更账户，在dispatchLaneMutation前统一登记其Lane；删除异步风险末页局部登记，保留重复完成/非法Lane/遗漏完成的严格检查。没有新增容器或任务阶段。
- 覆盖：RiskBatchBudget/LaneContext/RuntimeCommitRecovery25项通过，随后service866项通过；真实网络初始化新增交替风险批次续页路径，性能与资金恢复结果另见PERFORMANCE_VALIDATION.md。A3其余账户交接仍未完成。

- 活跃订单重复快照项验证完成：service866/bench282通过，真实128币对单成员plain/profile资金及snapshot重启通过；测量JFR未采到CoreOrderState，原Lane物化调用栈已删除。总分配仍约2.86KB/业务项，Owner批量解码、撤单分片准备及索引增长仍需处理；不是M4或全部架构完成。


### A3 内部触发扫描（2026-09-13）

`CONTINUE_RISK_SCAN` 不再取得 Owner 账户写入权。风险阶段完成后，现有命令续步推进有界触发页：过期、移动止损、OCO取消和子单冻结由所属永久Lane执行，Owner只保留候选游标、身份准备和子单顺序。显式与扫描触发复用同一Lane执行及收集方法；OCO任务读取控制边界内稳定的索引ID区间，不新增账户副本或复制sibling列表。跨命令页游标仍写入原RiskScanRuntime，恢复不依赖内存续步对象。

service872项通过；新增五条衍生品的预算1 OCO跨页、过期、移动止损、中途快照与同步重放一致性，并扩展0/1/10成交、子单ID冲突和无交接断言。测试夹具曾因新行情时间晚于命令时间触发STALE_MARK_PRICE，已修正时间顺序，业务校验保留。真实128币对JMH/JFR及重启结果只追加到PERFORMANCE_VALIDATION.md。顺序batch、改单/其他控制交接及A1/A2/S1/T1仍未完成。


### S1 批次生命周期归命令环（2026-09-13）

删除`pendingOrderBatches`及`sequenceKey`：按序号查找只走现有PendingMatching环，batch作为命令上下文持有；批次仅保留前后链接/计数，保证延期队首选择仍为O(1)，不改成全环扫描。提交前摘链、清命令引用，之后回收batch；关闭按同一归属关系清理。没有新Map、wrapper或账户状态副本。

实际风险/撤单续步只改payload，因此删除只在测试中使用的整份PendingMatching复制构造。续写必须保留已接受的header，先完成解码再替换字段，避免非法payload留下半更新命令。874项service通过，覆盖中间/头/尾摘除、环槽复用、命令改写失败、六产品批量及故障/恢复路径；真实128币对30/60秒plain/JFR结果见PERFORMANCE_VALIDATION.md。

本轮未迁移顺序batch/AMEND的账户写入；A3仍不满足全路径永久Lane所有权。S1剩余阶段/结果引用及A1/A2、M4/M6、T1仍保留未完成状态。


### 诊断补充：Owner索引增长与本机性能限制（2026-09-13）

JFR导出工具此前默认只保留5帧；改为显式深栈后，Owner的primitive map/set增长定位到`ActiveOrderIndex.add()`，应继续验证固定活跃订单周转下的删除/重插容量行为，并精简其索引维护；不能把该栈归给已删除的批次Map。该问题仍未修复。

本机真实测量结束附近发现macOS `CPU_Speed_Limit=62`，不是实际频率读数，不能线性换算吞吐；缺少全程限制时间线，现有短轮不能隔离代码与环境影响。后续采集脚本每5秒记录限制值，测量内受限的结果仅作诊断；无吞吐提升证据不改写为优化成功。详细指标保留在PERFORMANCE_VALIDATION.md。


### A3进展：顺序批项结算与批次终态（2026-09-13）

- 原因：顺序现货PLACE/AMEND在每项撮合后同步等待结算，Owner持写入权时还会直接执行账户变更；批次收尾另有同步stamp和Lane提交。
- 修改：批项结算释放Owner写入权后派发永久Lane，复用MatcherSettlementEvent；仅持有一个待完成事件，完成通知后合并本批资金端点，再推进下一项。撮合进度、成交和结果索引只应用一次。批次订单元数据与序号合并到现有LaneCommitEvent，完成后才发布终态；删除无调用的阻塞结算接口和自旋超时函数。
- 正确性：覆盖同批前项成交所得供后项冻结、改单冻结复用、逐项部分成功、Lane算术失败后的停机/快照重放；真实集群异步作用域补测暴露并修复收尾同步写入。性能参数与结果只记录在PERFORMANCE_VALIDATION.md。
- 未完成：顺序项准入/改单replacement冻结及部分拒绝处理仍可接管Lane；A3全路径永久Lane所有权尚未实现。A1/A2、Owner索引周转分配、M4/M6和其余阶段精简仍未完成，不将本次改动视作四项架构问题全部解决。

### A3进展：顺序准入、改单冻结和拒绝（2026-09-13）

- 顺序批次不再调用`tryEnterSequentialLaneStage`，删除该接口。准入只读校验保留在Owner；一项只挂一个有界续步，所属Lane安装订单/冻结/本地pending标记，Owner收集发布回执后登记全局pending索引并提交撮合。没有传递账户/订单快照。
- AMEND原生撮合成功后，所属Lane在同一任务内取消现货原冻结并冻结replacement；衍生品沿用原延期结算边界。撮合拒绝复用MatcherSettlementEvent的rejectTaker，无Owner解冻分支。
- 杠杆是低频配置，现有每Lane表改为ConcurrentHashMap；Lane单写，Owner只读标量。解决只读准入误入同步onLane的问题，不增加配置副本。
- 删除TradingCoreRuntime中已无调用的batch同步reserve重载及AMEND分支。异步准入挂起时保留原批次revision作为拒绝边界，续步只在完成后清除，不重复准入/撮合；改单后的异常仍要求恢复。
- 功能测试增加顺序下单/改单不发生handoff、post-only GTX原生拒绝后余额不足拒绝的资金及快照检查。网络JMH加入128币对相同拒绝分支；详细结果仅见PERFORMANCE_VALIDATION.md。
- 未完成A1/A2、其它控制/单笔路径的交接核对、Owner索引周转分配和剩余中间状态精简；本轮不据此宣布整体目标完成。

后续A3已定位到显式ADL、强平结案、触发子单拒绝及部分前置撤单；不能因顺序批单零handoff就关闭全路径所有权问题。带行号JFR也确认Owner索引增长来自ActiveOrderIndex.ordersById、每用户LongHashSet及每币对LongHashSet，下一步按这些实际容器处理删除/重插分配。详见统一验证记录。

### A3进展：显式 ADL 与保险结案账户写入（2026-09-13）

- `RuntimeDerivativeLiquidationProcessor.beginAdl`：Owner 校验全局行情、强平计划和保险顺序；目标 Lane 读取并更新余额、仓位，强平账户 Lane 更新结案状态。复用现有控制派发，每个相关 Lane 一个任务；同 Lane 两项合并，不传回完整余额/仓位，仅返回 TreasuryDelta。
- `beginResolution`：账户 Lane 替换强平状态，Owner 在完成后应用全局保险/赤字差额与版本。在线 EXECUTE_ADL/RESOLVE_LIQUIDATION 不再取得账户写权限；同步入口只用于离线应用。
- 原子性：任一 Lane 业务拒绝沿用命令回滚，必须恢复已更新的另一 Lane；测试覆盖1/4 Lane、拒绝前后全状态相等、拒绝快照恢复后成功重试、保险/ADL前后快照切点和重复重放，以及U/币本位×全仓/逐仓对照。
- 真实128币对单成员 plain/profile 的强平、保险、ADL、全账户资金与重启后业务哈希检查通过。有限生命周期含初始化和查询，不代表持续吞吐或 ADL 单次延迟；指标与产物摘要只记 PERFORMANCE_VALIDATION.md。
- A3仍未完成：触发子单拒绝及前置撤单等剩余账户交接。A1/A2/A4其余协调成本、Owner活动订单索引周转分配、M4/M6及剩余中间状态也仍需处理。

### Owner活动订单索引删除周转（2026-09-13）

- 已将 `ActiveOrderIndex.ordersById`、`idsByUser`、每用户/币对的订单ID集合改为Agrona删除压缩容器。删除时压缩探测链，不保留删除标记再周期重建；没有增加表、缓存或完整订单副本。
- 独立迭代器保留嵌套查询语义；仅查询边界生成 primitive 数组并排序，未引入热路径装箱。空用户/币对索引继续移除，首次建立仍有正常分配。
- 881项service测试通过；新增4096次周转保持主表存储、嵌套查询不破坏外层游标、金额汇总和最后订单删除用例。真实单成员128币对 plain/JFR资金与快照重启通过。旧主表删除重建栈未采到；每用户集合真实扩容仍有采样，不能宣布索引分配全部消失。两轮均受CPU限速，吞吐收益未证明；完整指标见 PERFORMANCE_VALIDATION.md。

### A3进展：触发子订单原生拒绝（2026-09-13）

- TRIGGER成功/拒绝统一使用已有MatcherSettlementEvent：所属Lane执行前置撤单、拒单解冻、订单终态与触发失败状态，不再由Owner调用rejectPlaceOrderRuntime/completeTriggerOrderRuntime。删除已无调用的Owner拒单包装。
- 保留协议语义：外层触发动作APPLIED，子单REJECTED、触发单TRIGGER_FAILED、placedOrderId=0；全局revision仍计拒单和触发失败两次。OCO、资金检查及终态发布顺序不变。
- 887项service通过，六产品线新增GTX跨价拒单、无handoff、reservation释放、OCO及快照核对；真实128币对单成员plain/profile和重启核对通过。有限生命周期耗时不代表吞吐，详细采样只记PERFORMANCE_VALIDATION.md。
- 未完成：REPLACE/AMEND拒绝后的前置撤单仍由Owner协调账户变更；A1/A2/A4剩余协调、完整状态往返及中间对象精简继续保留开放项。

### 改单原生拒绝语义缺陷（新确认，未解决）

六产品线组合用例：先撤自成交前置单，再AMEND原单为跨价GTX。Matcher采用cancel-then-place，原单已经取消、新单拒绝；Core保护检测到原单取消不属于预期前置撤单，触发FatalMatchingDivergence。仅迁移Owner撤单不能修复；需实现明确的原子改单或可核对的部分完成语义，保持资金、订单、重放一致性后再验收，不能直接放宽恢复保护。

### 改单已知撤单前缀修复进展（2026-09-13）

原生replace API不能同时更换ID/TIF；当前新ID接口维持撤旧下新，取消已发生后新单拒绝则提交旧单CANCELED并返回REJECTED。Owner仅接受准入原单及预期前置单、无成交的撤单前缀，由一个既有LaneCancelEvent完成释放与终态；删除原Owner集合重建和cancelOrderRuntime包装。六产品专项已越过旧停机问题并核对退役/冻结/原单响应/快照；全service首轮发现无单笔admission的批量异常用例被误访问，已收紧只在有单笔准入证据时认可原单，批量恢复保护保留。最终回归和真实网络验证未完成，尚不能关闭该项。

### 改单已知撤单前缀验证完成（2026-09-13）

893项service回归及真实单成员128币对plain/JFR、有效snapshot重启核对通过。两次网络失败为基准把保留卖单预占11误写为10：U本位SELL预占价为max(limit100,mark×1.01=101)，10%保证金向上取整11；修正独立预期后重跑，业务资金逻辑未改。

此项实现的是确定的撤旧下新部分完成语义，未知执行前缀保护保持；不等于原子改单，也不证明吞吐提升。A1/A2/A4、剩余账户写入路径审计、完整状态往返和中间对象精简仍未完成。

### A2直接结果路径的结构约束核对（2026-09-13，尚未实现）

- `MatcherPipelineGroup.drainMatchingCompletions`先交给Owner；`MatcherSettlementPlan.buildInternal`再读Owner订单、使用runtime共享去重/剩余量scratch、注册taker/maker仓位身份；之后才构造事件。不是简单删一个completion队列就能安全直达Lane。
- `SettlementLaneWorker`是Owner单生产者SPSC。不能让Matcher成为第二生产者；可行方向是Owner预先按序发布既有在途事件，Matcher只发布结果就绪，Lane按队首就绪消费。必须验证后续命令不越序、BLOCKING无丢唤醒、关闭/失败不挂死、容量回收与事件代次。
- 结算计划中的账户余额/订单剩余量校验应归所属Lane；Matcher只整理不可变原生成交事实及路由。现有Runtime共享scratch不能跨Owner/Matcher共用，也不能复制全活动订单表作为替代。
- `RuntimeIdentityRegistry.preparedPositionKey`要求Sequencer先注册身份，而目前身份注册发生在结果计划阶段；直接路径必须明确身份所有权及恢复方式。不能简单为所有未成交挂单永久预注册仓位身份，否则会增加常驻中间状态。
- 本次仅完成源码边界核对，未新增队列/事件状态或开性能轮次，A2直接路径保持未完成。下一实现需同时处理上述边界，不能仅用一条特殊路径的吞吐测试宣布整体完成。

A2小步实现：非批量单事件计划不再维护订单剩余量临时表；直接数量比较保留超量保护，多事件/跨批项累计校验不变。定向测试通过，性能尚未验证。未删除Owner账户读取/仓位身份注册，不能据此关闭直接结果路径。

### 2026-09-13 撤单/替换派发的Owner写入分支
- 原因：dispatchCancel、dispatchCancelBatch、dispatchReplace在ownerLaneAccess为true时直接event.execute，导致账户写入线程取决于上游是否借过Lane权限；同一API实际有两套执行顺序。
- 修复：三个派发入口归还临时权限，仅向所属Lane的SPSC队列提交；不增加任务、状态副本或结果容器。新增普通/批量撤单测试，阻塞Lane前序任务时必须未完成，释放后冻结正确清零。六产品线服务回归通过；网络JMH/JFR及恢复结果见PERFORMANCE_VALIDATION.md本轮追加。
- 删除尚未接入生产的Command.ready队首门控原型；它增加热路径判断且改变单方法任务协议。Matcher→Lane直接结果交付仍未完成：必须保证前序成交在同Lane的派发顺序，不能仅在Matcher回调中抢先提交撤单。

### 2026-09-13 准入和提交的Owner借权执行清理
- 普通/批量Place准入及已启动Lane后的账户提交也有Owner内联执行分支；已删除，派发前归还借权，账户任务只在Lane执行。恢复前尚未启动Lane的初始化仍由启动线程执行。
- finishAppliedMatching原先同步等待stageLaneMutation，真正异步后会触发服务终止；改为复用controlCommitEvent与原挂起上下文，Owner只轮询完成。移除两个同步包装方法和一次用户数组物化。
- 等待提交时恢复上下文会清除风险/强平响应DTO，导致成功响应内容丢失；终态边界一次编码并保留最终byte[]，提交完成后释放，不保留账户全状态副本。风险批次五条衍生品线及恢复回归覆盖该问题。
- A3尚未整体完成：上游部分控制准备及通用onLane仍可借权；Matcher→Lane直接结果路径和全局派发顺序也仍未完成。

### 2026-09-13 普通订单准备阶段的多余全Lane借权
- 原因：PLACE/CANCEL/REPLACE/AMEND及CANCEL_BATCH即使只读Owner已发布索引、再派发Lane任务，非流水准入分支仍先暂停全部Lane并借权。
- 修复：这五类命令的准备不再申请Owner账户权限；账户修改沿用已迁移的Lane任务，不放宽账户、订单或撮合依赖屏障。
- 验证：六产品线成功改单/替换/批量撤单/不存在订单拒绝及原生GTX改单拒绝，laneHandoffEpoch均不增加；账户余额与快照恢复一致。真实128币对网络结果见本轮PERFORMANCE_VALIDATION.md。
- 剩余：强平/到期等控制准备及通用onLane仍存在借权；Matcher直接交付Lane、派发顺序及剩余中间状态尚未解决，不能视为全部架构完成。

### 2026-09-13 删除无调用的“部分成功后改写命令再继续”
- 删除OrderedCommitCoordinator四个旧prefix方法（119行）、其唯一使用的结算推进包装及PendingMatching.withCommand。无生产调用方，且现行强平/结算部分结果要求恢复；保留旧代码会造成两套语义和不必要的可变命令接口。
- 已接受命令在同一PendingMatching生命周期内不再被替换或重解码；运行元数据更新仍保留原指纹/命令身份。907项服务测试通过，减少的1项仅针对已删除API。该清理不产生已证明的运行期分配收益。
- 下一处实际账户迁移边界：RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime/applyCancellationAdvanceRuntime；批量强平仍逐动作同步调用。需复用控制Lane任务，将账户计算/撤单放入所属Lane，Owner只收TreasuryDelta和进度，再处理批量续程；不要恢复已删除的命令改写逻辑。

### 2026-09-13 强平账户计算归属修正

- 原因：Owner 读取余额/持仓并生成完整结算后状态，再交 Lane 写入，账户计算跨越所有权边界。
- 修改：RuntimeDerivativeLiquidationProcessor.executeAccountLiquidation 在账户 Lane 内读取、计算、更新；仅返回既有 TreasuryDelta，Owner 应用保险/赤字/清算差额及 revision。未新增容器或账户快照。
- 未完成：撤单与执行仍为同步串行阶段，批量强平继续由 Owner 循环编排；不能据此关闭 Owner 瓶颈或 Matcher→Lane 直达路径。

- 线程归属限制：上述改动合并了账户计算与写入作用域，尚未消除 Owner 借用 Lane 执行。直接释放访问权会被异步命令的 onLane 防护拒绝（恢复测试两项失败），已撤销该尝试；必须连同强平 completion/批量 continuation 迁移到 dispatchControlLanes，不能仅删除访问权保护。

### 2026-09-13 强平异步 Lane 执行与批量推进

- 已改：真实 Cluster 单笔/批量强平使用现有 ControlLaneDispatcher；撤单和账户结算在同一 Lane 任务完成，Owner 只处理 treasury 差额、全局 revision 和终态。跨线程输入只带撤单 ID 及必要不可变参数，不传递结算后的完整账户状态。
- 已改：批量仅保留动作索引、剩余预算/计数及当前在途回调，复用命令 primitive 变更 ID 累积器，删除额外 changedOrders/changedUsers 列表及 distinct 中间列表。
- 已改：准备阶段通过现有 Owner 活跃强平索引检查生命周期冲突，去掉强平准备的全 Lane 交接；完成回调消费一次，后续等待提交不再重复执行 Lane poll。
- 未完成：同步直调入口仍保留 executeUserSettlement；其他重控制路径、Matcher→Lane 直接结果路径及总体30万目标仍需继续，不把本模块通过等同于全部架构问题关闭。

- 验证结果：服务端910项测试均有通过报告；单笔/批量分页、跨账户共享预算及中途恢复通过。真实单节点128币对plain/JFR两轮资金及快照恢复PASS；JFR捕获到core-account-lane-0上的强平执行栈。有限生命周期采样不能证明30万吞吐或稳态分配收益。

### 2026-09-13 强平模块同步入口收敛

- 已改：强平执行/分页推进共用execute及finishExecution；保险结案、ADL共用同一个账户操作，删除旧独立撤单和重复写入逻辑。同步入口在Lane已启动时释放Owner访问权并提交Lane，异步入口提交同一操作；空撤单不分配ID数组。
- 验证：服务端全量910项通过；新增同步/异步跨账户分页对照后RiskBatchBudgetTest13项通过（服务端共911个唯一用例），同步用例先主动借用Owner访问权，再验证执行后释放，并与异步恢复实例比较响应、状态与资金。
- 范围：关闭本模块同步入口仍借用Owner写账户的问题；其他重控制及Matcher→Lane直达尚未完成，不宣称整体Owner瓶颈或30万目标完成。

- 真单节点128币对ZGC的三页撤单/强平/保险/ADL及快照恢复PASS；JFR已采集，原始产物已清理。该有限场景仍不作为稳态吞吐、分配率或30万目标证据。

### 2026-09-13 Matcher路由索引收敛

- 原因：已有Core序号槽仍另建shardByToken哈希表，每次提交/消费重复维护同一条路由。
- 修改：删除该Map；路由放回既有LaneCommandContextRing.Context的一个int字段，提交、队列拒绝回退、消费和复用均在同一槽生命周期管理。没有新增队列、Map或逐命令对象。
- 保留：重复提交拒绝、跨分片完成校验、队列满后的可重试、未消费路由禁止正常回收；多分片独立推进及原单生产者队列不变。
- 未完成：Owner仍负责结果验证、计划构建和派发；持仓身份首次登记、累计成交校验及Lane顺序约束仍是直接结果路径的依赖，不能把删除路由Map称为Matcher→Lane直达。

- 验证：服务端913、benchmarks282项通过。128币对真实网络plain16.43万/profile17.18万终态业务项/s，两轮资金和恢复通过，但CPU限值降至60/62，不能判断优化收益。JFR约462MB/s、2689字节/业务项，分配仍高；下一步需处理Lane订单/预留表扩容、发布缓冲区及OrderRuntime中间状态，并继续解除Owner结果处理依赖。原始产物已清理。

### 2026-09-13 批量准入发布缓冲区复用

- 原因：PlaceBatchAdmissionEvent已池化，但每批成功准入仍新建LanePublication及3组数组；batch20发布41项导致反复扩容。
- 修改：容量随现有事件复用，Owner回收时清空引用及序号，Lane下次执行使用原缓冲区；不增加池、容器或线程阶段。账户仍由Lane写入，Owner有序发布。
- 验证：44项相关测试通过。128币对、窗口256、4Lane/1Matcher、ZGC真实单成员plain/profile，资金、终态及快照重启均PASS。plain17.43万/profile17.20万业务项/s，两轮降频，不能判断吞吐收益。
- JFR稳态未采到LanePublication分配栈（采样缺失不等于精确零分配）；估算443.67MB/s、2579.51字节/业务项，仍偏高。OrderRuntime、数组、ReservationRuntime仍居前；Owner仍承担结果回收及发布，Matcher→Lane直接路径和其他重控制问题未关闭。详细结果见PERFORMANCE_VALIDATION.md，原始产物摘要后清理。

### 2026-09-13 Lane订单/预留/客户别名热表

- 原因：EC墓碑计入插入阈值，固定活跃量下仍反复分配整表数组。订单、预留及客户别名反向索引已替换为项目已有Agrona回移删除容器，不新增索引。快照去掉每Lane临时整表副本，仍按原顺序hash。
- 验证：service916项及依赖测试通过，近2万轮增删保持同一底层数组、持久订单/预留及hash。128币对真实单成员plain18.01万/profile17.13万业务项/s，资金/终态/恢复PASS；降频下不判断吞吐收益。
- JFR估算411.90MB/s、2404.29字节/业务项；已替换热表的扩容栈不再出现，残留扩容集中于pendingReservationSequences。OrderRuntime仍最大，整体分配及Owner直接路径问题未完成。详情及hash见PERFORMANCE_VALIDATION.md，原始产物已清理。


### 2026-09-13 GCP饱和定位后停止

- 已定位：Owner客户端身份回收、撮合完成结果收集、终态索引维护和批量结果派发仍是串行热点；Matcher→Lane直接结果路径未完成。应合并身份生命周期和重复索引维护，再按Lane归属迁移结果消费，保留顺序、去重及恢复语义。
- Lane仍有订单/预留状态副本、FillCursor发布、客户身份及编码分配；Matcher存在多层结果封装。应优先复用已有槽/缓冲区并减少副本，避免新增容器或阶段。Lane高CPU包含大量忙等，不能视为下游计算饱和。
- 大窗口未改善吞吐且尾延迟更差；扩堆不能消除每业务项分配。没有实际Allocation Stall证据，旧计数误把零值统计行算作停顿，已更正。
- 用户要求停止进一步验证并释放GCP资源。逐档吞吐、5ms目标配置、分配/锁/内存、异常及资源释放证据统一见根目录PERFORMANCE_VALIDATION.md的GCP最终记录；不宣称整体架构优化已完成。

- GCP资源最终复核：本轮VM/启动盘/临时IP已释放，项目实例/磁盘/地址/快照等清单为空；旧压测专用VPC、子网及两条规则亦已删除。证据见PERFORMANCE_VALIDATION.md末尾。


## 2026-09-13 Matcher→Lane 直接结果路径更新

| 项目 | 实现与边界 |
|---|---|
| 普通/流水线批量下单结果绕经 Owner | Matcher 直接填充既有结算事件，Lane 原队列消费；Owner 不再先解析结果和构建结算计划才允许 Lane 执行。 |
| 批量逐项处理占用 Owner | 撮合事实、成交计数、变更ID、逐项响应元数据在 Matcher 准备；Owner 只校验证据链首尾和推进批次游标。 |
| Lane 多生产者风险 | Owner 仅按原依赖门槛入队顺序票据；Matcher 只发布载荷/唤醒，不修改 Lane 生产者游标。 |
| 提前回收事件/持仓身份 | 事件等待实际 Lane 与保守路由标记全部完成；持仓身份按既有 Lane delta 的位置键保留到有序收集，阻止前一笔退休误删后续身份。 |
| 空持仓身份与恢复差异 | 无成交不预分配持仓身份，恢复不按未成交订单生成空身份；真实持仓/风险身份沿原恢复边界重建。 |
| 必须保留的 Owner 工作 | 全局有序提交、证据链、资金/Treasury汇总、终态保留/结果索引、事实发布。删除这些工作不属于此次直接路径改造。 |

验证包含六产品线真实成交的无 Owner drain 推进、跨分区与同账户顺序、拒绝和控制续程、资金/持仓/冻结、快照恢复及事件池复用；吞吐和分配只以根目录性能记录中的实测为准。预热发现的批次游标竞争已改为 Owner 独占，失败轮次保留在性能记录中。


### 2026-09-13 客户端订单标识回收职责修复

已删除 Owner 的 `releasePreparedClientKey`、Lane→Owner 待回收缓冲和逐订单回收循环。标识在现有账户 Lane 准入/结算/控制任务内创建、回滚和回收；改单与触发子单不再先让 Owner 创建。每订单身份实体删除重复 key 和 `ClientIdentity` 包装。终态订单字符串及 Fact 固定身份保证有序发布和去重不依赖已释放实体。

必要的 Owner 撮合前只读校验仍使用原并发字典；没有同步转发查询、增加索引或新增任务。Core 终态运行态查询返回 ENTITY_NOT_FOUND，保留的是去重墓碑，不是历史订单详情。Owner 的其他完成轮询、有序提交和终态索引热点不属于本次标识职责修复。验证只记录在根目录 `PERFORMANCE_VALIDATION.md`。

### 2026-09-13 剩余热点清单与待预留表扩容修复

- 已修复：`AccountLaneState.pendingReservationSequences` 和按用户计数表改用既有 `INITIAL_ENTITY_CAPACITY` 初始化，避免持续订单进出时从默认小表反复扩容；不新增状态、不改变预留计数语义。编译及 `AccountLaneStateTest`、`PendingReservationTrackerTest`、`TradingCoreRuntimeTest` 定向回归通过。
- 仍需解决：Owner 仍承担撮合完成槽位回收、最终证据校验、终态提交和结果索引；其中 `TerminalTombstoneStore.indexClient`、批量解码和提交发布仍是 JFR 热点。Matcher 已在预留结算事件内构造不可变成交计划，Lane 按 Owner 预留的序号顺序校验并应用；Owner 仍保留有序提交屏障，避免跨线程修改权威状态。
- 仍需解决：强平/到期等低频控制的全部准备和通用 `onLane` 借权边界尚未完全收敛；批量强平虽已异步化，仍需继续消除同步直调和 Owner 编排续程。
- 仍需解决：`OrderRuntime`、成交/发布缓冲、PendingReservation 之外的中间对象分配仍高（最近稳定轮约 2.53KB/业务项）；需要按事件生命周期复用并以 JFR allocation 栈逐项验证，不能只靠扩大堆。
- 验收未完成：本机热降频下约 25.38万业务项/s、普通单 p99 5.865ms，尚未证明 30万+/s 和 p99≤5ms；仍缺少无热限制的 GCP 单节点复测、开放到达率/三段延迟、Linux 调度及长期泄漏证据。上述未完成项不能用一次 closed-loop 结果关闭。

### 2026-09-14 活跃订单索引启动容量修复

- `ActiveOrderIndex` 的 Owner primitive 主表及币对索引按固定 128 币对、256 在途基线预留容量；用户临时集合仍保持小容量并在为空时释放，避免短生命周期用户把大数组带入每次下单。
- 该改动只消除热身阶段的重复 rehash/copy，不改变索引语义或状态所有权。`ActiveOrderIndexTest` 11 项及 `ClusterCommandPipelineTest` 250 项通过。
- 未重新执行 GCP 64/128 轮次，因此不宣称吞吐、p99 或稳态分配率已经改善；仍需用同一 ZGC/绑核条件复测。

### 2026-09-14 异步撮合结算禁止 Owner 借权写入

- `MatcherSettlementDispatcher` 的普通及批量结算在异步命令作用域始终提交永久 Lane；即使 Owner 仍持有准备阶段借权，也不会内联执行账户写入。
- 同步离线/初始化路径保留原有内联语义，避免把恢复和测试夹具改成隐式异步；SPSC 生产者、序号和失败回滚协议不变。
- `TradingRuntimeStateTest`、`RiskBatchBudgetTest`、`CoreOrderedOrderBatchTest`、`ClusterCommandPipelineTest` 共 348 项通过；未重新执行云端吞吐，不宣称性能提升。

### 2026-09-14 终态候选索引分配收敛

- `TerminalStateRetention` 原先每次观察终态实体都创建临时 `EntityKey` 并重复查找候选；固定 Owner 线程增加可复用查找键，并在候选内容未变化时直接跳过替换，保留 Map 中稳定键和终态去重语义。
- 该改动只减少终态观察路径的短命对象，不改变终态 FIFO、客户号墓碑或快照格式。`TerminalStateRetentionTest` 4 项、`ClusterCommandPipelineTest` 250 项通过；完整服务回归仍为 925 项通过。
- 这不是整体分配率已达标的证据。`OrderRuntime`、发布缓冲及 Owner 有序提交仍需 JFR 稳态栈验证；30万+/s 和普通单 p99≤5ms 仍不能宣称完成。

- 进一步将候选观察的临时键改为仅在首次插入时创建；终态状态未变化时不再替换候选记录。定向回归扩大为 254 项（含依赖模块）通过。

### 2026-09-14 压测脚本口径统一

- 发现 `qualify-linear-perpetual-scale.sh` 仍默认使用 512 个 listed/active symbols，和后续固定 128 币对的验收约定冲突；已统一 probe、JMH、GC、JFR、soak、capacity、saturation、owner commit 及 JSON 校验为 128。
- `maxInFlight=256`、操作批次、JFR/NMT、ZGC 和校验门禁未改变；脚本语法检查通过。此前历史结果不改写，后续新轮次才使用该口径。

### 2026-09-14 交割结算异步续程

- `SETTLE_INSTRUMENT` 原先在异步 Cluster 作用域调用 `executeLifecycleSettlements`，Owner 会同步等待账户 Lane；已增加 `RuntimeSettlementProcessor.SettlementWork`，按撤单、账户结算准备、保险检查、账户应用四个有界阶段复用 `ControlLaneDispatcher`。
- Lane 只处理所属订单/账户和既有 `UserSettlement` 结果，Owner 只汇总 `RuntimeTreasuryDelta`、更新生命周期进度并进入原有有序提交；保险不足、分页游标、快照恢复和失败回滚语义保持不变。同步恢复/离线入口继续使用原同步实现。
- 受影响结算、恢复、Runtime 状态回归共 103 项通过；服务全量 925 项通过。尚未重新执行吞吐/JFR，因此不能据此宣称整体吞吐或分配率改善。
- 后续清理掉续程中未使用的索引/身份引用，状态机只保留命令、用户/订单页、Lane mask、准备结果和 Treasury delta。

### 2026-09-14 终态候选值复用

- `TerminalStateRetention` 的候选键此前已复用，但每次终态观察仍替换 `RetainedEntity`。候选表只由 Owner 线程访问，现改为原值就地更新；仅首次插入创建对象，快照 `copy()` 做深复制，避免把可变运行值带入快照。
- FIFO 顺序、导出序号、客户号墓碑、裁剪条件和恢复编码不变；`TerminalStateRetentionTest` 4 项及 service 全量 925 项通过。
- 该改动只消除终态观察短命对象；Owner 的有序提交、证据链和结果索引仍是一致性所需职责。整体分配率和30万+/s仍需稳态长轮验证。

### 2026-09-14 删除无调用的旧订单盖章入口

- `RuntimeCommandProcessor.stampOrderChangesByLane(TradingCoreState, ...)` 没有生产调用方，且会为每次调用创建 `ArrayList`、`HashMap` 及投影后的 `OrderRuntime` 副本；实际链路使用的是 `stampChangedOrdersByLane`。已删除这条重复入口，保留唯一的命令级盖章路径，减少维护分叉和误用风险。
- 生产调用图未改变；service 编译通过，完整 925 项回归已在同一组主链路改动上通过。

### 2026-09-14 删除第二条无调用订单盖章入口

- `RuntimeCommandProcessor.stampOrderChanges(...)` 也没有生产调用方；它会在缺少候选时物化完整订单快照，并在每个订单上重新投影 `OrderRuntime`。实际主链路使用唯一的 `stampChangedOrdersByLane(...)`。已删除该旧入口及不再需要的导入，进一步收敛为单一提交语义。
- 受影响服务编译和 `RuntimeCommandProcessorTest` 8 项通过；不改变生产调用图和协议。

### 2026-09-14 直接结算前置撤单数组复用

- 直接 Matcher 结果包含前置撤单时，原路径先在 `MatcherSettlementEvent` 创建临时 `long[]`，再由 `MatcherSettlementPlan.preCancellations` 防御性复制；一次事件产生两份相同的短命数组。
- 计划槽现在保留自己的可增长撤单缓冲。直接结果按已授权撤单逐项写入该缓冲，普通外部调用仍复制调用方数组，避免改变所有权和快照语义。
- 没有新增队列、Map 或阶段；事件收集前缓冲仍由单一计划独占，清理只重置有效长度。`MatcherSettlementPlanTest`、`TradingRuntimeStateTest`、`ClusterCommandPipelineTest` 共 314 项通过。
- 该项只消除直接结算中存在前置撤单时的双重数组分配；`OrderRuntime`、Lane 发布缓冲、Owner 有序证据/终态提交及其余控制路径仍是待用 JFR 稳态栈验证的开放项，不能据此宣称总体分配率或吞吐达标。

### 2026-09-14 结算/导出准备阶段去除无必要全 Lane 借权

- `SETTLE_INSTRUMENT` 已经具备异步交割续程，`ACK_EXPORT` 本身也不会修改账户状态；但准备门禁的默认分支仍会先请求 Owner 对全部 Lane 的写入权，造成一次全 Lane 停驻/恢复。
- 两类消息现在明确标记为无需 Owner 借权。交割的账户变更继续由已有 `SettlementWork`/Control Lane 执行，导出确认仍按原拒绝协议处理；未知未来消息仍保留保守默认值。
- `CoreMaintenanceTest`、`CoreResultLedgerTest`、`RiskBatchBudgetTest` 共 38 项通过。该项主要减少低频控制命令的 handoff 等待，不改变撮合主路径；Owner 终态提交、证据校验和其它控制入口仍需按 JFR 结果继续审查。

## 2026-09-14 结算收尾重复工作清理

- TRIGGER 结算路径原先在 `completeMatching`/`completeDispatchedMatcherSettlement` 中先把变化订单通过 Stream 物化成 `CoreOrderStateView`，随后统一收尾又调用 `materializeCommandOrderViews` 再物化一次；已删除前一份必然被覆盖的中间列表。终态响应仍在有序提交完成后按原规则生成。
- pending reservation 序号索引提升首订单时原先使用恒真谓词适配器选择元素；已改为直接使用 primitive iterator，不创建谓词/适配对象，也不改变提升顺序或 `orderIds` 的外部顺序约束。
- 定向回归：`TradingRuntimeStateTest` 57 项、`ClusterCommandPipelineTest` 250 项通过；服务模块编译通过。该修复属于确定性的重复分配/遍历削减，尚未据此宣称吞吐、p99 或整体分配率达标，仍需与固定 128 币对的 JMH/JFR 基线复测。

### 2026-09-14 Matcher→Lane 直接结果路径与候选 Lane 时序修复

- 普通 PLACE 及可流水化 PLACE_BATCH 在 Owner 完成 Matcher 提交后立即把已预留的 `MatcherSettlementEvent` 投递到目标 Lane；Matcher 线程只写入成交计划并发布 ready 位，Owner 仍负责按 Core 序号做证据校验、资金/终态提交、结果索引和事件回收。
- 修复了候选 Lane 掩码晚于 `apply()` 创建 pending 的时序缺陷：候选范围现在在 pending 入环前固化，直接事件不会因看到临时 `0` 掩码而错误广播到全部 Lane。后续分区派发仍通过 `PendingMatchingRing` 的依赖链，保持跨账户/跨订单簿有序提交。
- 已保留重复派发保护；事件已经预投递时，Owner 收尾只收集完成结果，不再次提交 SPSC Lane 队列。未就绪事件仍由 Lane 的 ready 门禁等待，Matcher 不直接写账户状态。
- Owner→Lane 直接提交已实现；Owner 的必要职责仍不可删除：Matcher 结果证明、跨 Lane 完成屏障、资金守恒、终态账本/客户标识墓碑和有序响应发布。这些步骤决定恢复、重放和快照一致性。

### 2026-09-14 衍生品成交重复 OrderRuntime 分配与单预留快路径

- `FillCursor.publish` 原先先构造一次 `OrderRuntime` 读取终态，再在 `replaceOrder(order())` 中重复构造；现在同一成交只构造并发布一个不可变订单值，调用方复用该值判断终态释放。
- 普通单序号只有一个 pending reservation 时，直接走既有标量完成路径，不再创建单元素 `long[]`、引用列表和批量完成列表；多订单命令继续使用原批量校验和顺序。
- 两项优化不增加生命周期状态、队列或索引，保持 Lane 写入、资金释放、用户 revision、回滚和快照语义。

### 2026-09-14 批量单项结算初始身份分配收敛

- 单项批量结算原先为通用计划构造一次性的 `long[]{takerOrderId}`；现在由目标 `MatcherSettlementPlan` 自有的单元素 scratch 承载初始身份。
- 该路径不改变计划校验、Lane 路由或回滚语义，只移除每个批量项的一次短命数组；新增回归覆盖计划复用和 maker/taker 路由。

## 2026-09-14 当前问题闭环审计

- **已实现**：Matcher→Lane 直接预投递、候选 Lane 掩码提前固化、Lane 单写账户状态、Owner 有序提交/证据校验/资金守恒/终态账本、Owner 空转门禁、Lane park/唤醒握手、批次状态位图、发布缓冲复用、订单/预留/客户索引热表复用、衍生品成交和单预留快路径、单项批量身份 scratch 复用。
- **必须保留的 Owner 工作**：按 Core 序号消费 Matcher 事实，验证 matcher prefix/native command，汇总跨 Lane Treasury delta，计算业务哈希，写结果账本和终态客户墓碑，释放事件与准入窗口。这些是重放、快照和幂等一致性的权威边界，不能继续下沉到 Matcher/Lane。
- **仍是代码边界而非遗漏**：普通 PLACE 的跨账户/同订单簿依赖仍由 `PendingMatchingRing` 队首和 Lane mask 控制；跨账户成交必须等待所有受影响 Lane，不能为了并行而放宽顺序。低频同步离线/恢复入口保留 `onLane` 内联调用，异步 Cluster 控制路径已迁移到永久 Lane 续程。
- **仍需外部证据才能关闭**：无热限制 Linux/GCP 的 64/128 窗口吞吐、普通下单 p99≤5ms、稳态 JFR 分配率、锁/调度和长期状态增长。现有本机短轮只能证明正确性和无明显回归，不能把 30 万+/s 宣称为已达成。

### 2026-09-14 响应视图热路径复用

- `CommandResultBuilder` 的批量订单响应现在复用 Owner 独占的 primitive 去重集合和视图缓冲；稳定响应仍在边界用 `List.copyOf` 固化，不把可变缓冲暴露给下游。
- REPLACE/AMEND 的双订单响应增加无-varargs入口，避免每次为两个订单 ID 创建临时 `long[]`。
- 该修改不改变响应顺序、重复订单去重、快照或重放语义；编译及 `TradingCoreRuntimeTest`、`ClusterCommandPipelineTest` 通过。

### 2026-09-14 批量清算准入 scratch 化

- `validatePendingLiquidationBatch` 删除每个 action 的 scope 字符串拼接、`HashSet<String>` 和装箱变更列表，改为 Owner 独占的用户/symbol scratch 与 primitive 变更集合。
- user+symbol 冲突仍做精确比较，action 顺序、生命周期门禁和响应结果保持不变；容量只在超过历史峰值时增长，不增加新的业务状态。

### 2026-09-14 撮合证据校验去除 Stream 临时对象

- direct settlement 与 `KNOWN_PREFIX_APPLIED` 证据校验改用有界循环，去掉 `matcherEvents`/取消结果上的 Stream、lambda 和中间迭代器。
- 校验条件完全保留：成交事件禁止、已接受撤单必须属于预授权集合或改单原单；仅减少 Owner/Matcher 交界处的短命对象。

### 2026-09-14 直达结果单一事实源收口

- direct PLACE 的 Matcher 结果只由 `MatcherSettlementEvent` 保存并交给 Lane；Owner 仍轮询 Matcher 完成槽位以释放 SPSC 位置和 submission token，但不再把同一对象复制到 `LaneCommandContext.completedMatchingResult`。
- `OrderedCommitCoordinator` 在首次提交和等待重试时都从 direct event 读取结果，再执行原有有序证据校验、资金汇总、结果账本、响应和释放流程；普通非直达命令与批量命令的既有完成语义保持不变。
- 这解决了 Owner 对直达结果的重复保留和重复消费职责，未删除 Owner 的一致性边界，也未把终态提交下沉到 Lane。Matcher/Lane/Runtime/Cluster 回归及 service 全量 927 项均通过。


### 2026-09-14 准入双掩码查询收口

- PLACE 准入原来对同一价格树分别计算 counterparty account mask 和 Lane mask；现在由一次遍历同时产出两个掩码，结果写入调用线程的 scratch，不增加 `ActiveOrderIndex` 实例状态。
- 该改动消除了 Owner 的重复树遍历，保持索引所有权、恢复格式、反射快照和公开兼容 API 不变；恢复一致性和 927 项 service 回归均通过。
- JFR 仍显示整体瓶颈在 Lane mutation await、有序 matching commit、TreeMap/哈希状态物化及大量数组/订单状态分配；因此这项改动只关闭一个明确的重复查询热点，不能单独宣称达到 30 万+/s 或 p99≤5ms。


### 2026-09-14 状态哈希分配收敛

- Account Lane 的状态哈希对 ASCII 文本改为直接按字符混合，只有非 ASCII 文本才编码 UTF-8，去除常见哈希路径的临时 `byte[]`；哈希字节语义保持不变。
- `TradingRuntimeStateTest`、`RuntimeCommitRecoveryTest`、`ClusterCommandPipelineTest` 的定向回归及随后 service 全量 **927 项**回归均通过；Lane mutation await 的 fast path 因并发可见性竞态已撤回，不作为可用方案。
- 该项只减少哈希分配，不能单独证明总体吞吐或 p99 达标。

## 2026-09-14：Settlement Delta 发布路径收敛

- `LaneDelta.preparePublication()` 不再把 users/orders/reservations/positions 再复制到 `LanePublication.maps/keys/values`；结算 publication 只借用已填充的 Delta 缓冲，独立 PLACE 准入仍保留原 publication 协议。
- Owner 提交边界在同一次 Delta 遍历中应用 published maps 并登记 `changedUsers/changedReservations`；随后直接清空这两类缓冲。orders/positions 继续通过 `OwnerIndexedChanges.adopt()` 交换所有权，避免逐实体复制。
- 删除持仓在 publication 替换 Owner 视图前完成 realtime capture，终态订单批量 sink、route tombstone 和 admission sequence 语义保持不变。
- HotSpot JDK 25 下 `mvn -q -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests package` 通过；service 全量回归 `927 tests, 0 failures, 0 errors, 0 skipped`，settlement/publication/独立窗口定向回归通过。
- 本次只验证了正确性和重复遍历削减，尚未重跑 GCP 16c32g 的 64/128 窗口 ZGC 吞吐、Owner/Lane/Matcher CPU、分配率及 p99；因此不能据此宣称 30 万+/s 或 p99≤5ms 已达成。服务全量测试中的 Aeron heartbeat/独立窗口时序需继续作为稳定性门禁观察。


### 2026-09-14 批量结算状态与撤单标识进一步收敛

- `OrderBatchPending` 的延后结算不再维护订单号、Lane mask、结果三份动态列表；每个 `OrderBatchItem` 唯一持有撮合结果和 Lane mask，批次只保留固定容量的 item index 数组。清理阶段按有效长度回收，批次容量上限仍由既有 256 在途预算约束。
- `PendingMatching` 接收内部不可变 primitive 订单号列表时直接保留其底层数组视图，避免衍生品自成交/容量冲突路径每笔再次创建装箱 `Object[]`；外部可变列表仍防御性复制。
- 批量结算、核心流水线和 service 全量回归均通过（全量 927 项无失败）；并行产品线定向测试偶发 Aeron heartbeat/agent 时序失败后单独重跑通过。
- 这两项只减少中间状态和短命容器，Owner 有序证据校验、资金守恒、终态提交及结果账本仍保留。没有新一轮 Linux/GCP 64/128 ZGC 运行，因此不把它们当作吞吐、p99 或分配率达标证据。

### 2026-09-14 衍生品直达结算身份查询收敛

- 直达成交的杠杆读取改用线程局部可变探针查询现有 `CoreLeverageKey` map；不改变持久键、更新、快照或回滚语义，也不把探针对象插入容器。
- Lane 侧持仓身份按 `(userId, symbol, positionSide)` 的既有确定性键直接探测。`LONG/SHORT` 名称只在身份首次创建时生成，后续成交不再重复拼接 `symbol:side` 字符串；同时仍逐字符校验身份，保留碰撞检测。
- `RuntimeIdentityRegistryTest`、`TradingRuntimeStateTest`、`RuntimeDerivativeMatchProcessorTest`、`MatcherSettlementPlanTest` 及 service 全量回归通过。该项是热路径分配削减，未进行新的吞吐/JFR 采集，不据此宣称 30 万+/s 或 p99≤5ms。

### 2026-09-14 Matcher 槽位与序号上下文收尾时序修复

- 发现 direct settlement 的 Lane 完成通知可能先于 Owner 的 matcher completion drain；Owner 已拿到事件结果却在回收序号上下文时仍持有 `submittedMatcherShard`，导致满窗口独立提交偶发 `incomplete lane command context`。
- 有序结算收尾前增加幂等 `transferMatchingCompletion`：只消费尚未释放的 Matcher SPSC 槽位，不把 direct result 再复制进 Owner 上下文；已由常规 drain 消费的路径保持空操作。
- 该修复保留 Owner 的证据、资金和终态提交职责，修复了槽位生命周期而没有放宽上下文回收校验。


### 2026-09-14 无载荷响应单例收敛

- Owner 拒绝、幂等冲突、Matcher 拒绝、结果未知、响应构建失败和实时快照不可用等无载荷分支统一复用 `TradingCoreRuntime.EMPTY_RESPONSE_DATA`，结果账本对该不可变数组跳过防御性复制。
- 不改变非空响应的所有权：正常编码结果仍由账本按既有 owned/clone 规则保存；只消除空 `byte[]` 的短命分配。
- 该项不触碰有序提交、Lane 写入或撮合证据边界；服务全量回归作为本次变更门禁。


### 2026-09-14 订单变更 primitive 直写方案保留边界

- 尝试让 PLACE/TRIGGER 直接把 settlement plan 订单 ID 写入 Owner primitive 集合，删除 `long[] + ImmutableLongArrayList` 中间结果；完整 929 项回归在独立满窗口场景复现 `incomplete lane command context`。
- 原因是该集合同时参与挂起/恢复的提交上下文生命周期，不能只按“最终列表等价”判断；该改动已完整撤回，保留原有 `commandChangedOrderIds` 交接协议。
- 结论：订单变更列表的下一步优化必须先把上下文所有权和 plan 生命周期显式拆开，再做零拷贝；当前版本以正确性优先。


### 2026-09-14 单订单结果编码与 Matcher 回调复用

- 普通成交响应只有一个订单视图时，协议层新增 `encodeSingleOrder`，直接写入既有 wire 字段；避免临时 singleton `List`，多订单和带扩展执行结果仍走通用编码器。
- Owner 的 Matcher completion consumer 改为运行时实例级复用，不在每轮 drain 重新构造 method-reference 适配器。
- 单订单优化不改变结果账本、Lane context 或响应字节格式；协议等价测试逐字节校验。


### 2026-09-14 单订单响应直接读取运行态

- 普通 PLACE/CANCEL 结果原先在 Owner 完成边界先创建 `CoreOrderStateView`，随后马上编码；现在单订单结果保存 `OrderRuntime` 引用，由 Owner 独占的可复用 `CoreOrderStateSource` 游标在编码期间读取字段，编码器不保留游标引用。
- 多订单 REPLACE/AMEND、批量响应和 TRIGGER 仍使用稳定视图列表；命令开始时显式清除游标，避免跨命令复用旧订单。协议字节格式、响应账本和 Lane context 生命周期不变。
- 这是状态物化分配的确定性削减，不改变 Owner 必须保留的有序证据校验、资金汇总、终态账本和结果索引职责。整体 `OrderRuntime`、发布缓冲和有序提交分配仍需稳态 JFR 逐项验证。


### 2026-09-14 Matcher 结算订单身份视图去复制

- `TradingCoreRuntime.boxedOrderIds(MatcherSettlementPlan)` 原来每次把 plan 的 primitive `long[]` 复制成新的数组和不可变列表；现在返回由 plan 自有数组驱动的只读 `RandomAccess` 视图。
- 视图只在 plan 仍挂在 pending sequence/context 期间有效；`LaneCommandContextRing.Context` 回收时才清空 plan，因此异步 Lane 等待和 Owner 恢复提交上下文不会看到提前释放或跨命令数据。批量结算仍使用事件自有槽位，不引用该视图。
- 该改动删除普通撮合完成路径的一次 `long[]` 和列表对象分配，不改变订单顺序、去重、终态索引、结果账本或提交边界。服务全量 929 项回归通过。
