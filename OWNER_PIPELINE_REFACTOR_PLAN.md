# Owner / 交易链路低分配重构计划（待审核）

状态：**阶段 2 已完成，阶段 3 待执行**。每个阶段必须完成代码、旧路径清理和正确性验收后才进入下一阶段；压测留到全部核心阶段完成后。

本计划最初为待审核草案，现按审核意见执行。阶段 2 已完成并通过 JDK 27 正确性验收；没有启动吞吐压测。

## 目标

把一条普通交易命令收敛为：

```text
ingress route → matcher → account lane mutation → ordered terminal commit
```

Owner 只保留确定性所需的工作：

- commandId 幂等和 source sequence 校验；
- core sequence 和提交顺序；
- 最小的撤单路由索引；
- 全局 revision、状态 hash、提交日志和结果账本；
- 按 core sequence 发送最终响应。

Matcher 只负责订单簿和撮合事实。Lane 只修改自己拥有的账户、订单、预留和持仓。跨 Lane 的变化只能通过固定槽位中的事实增量传递。

## 当前基线和事实

基线代码：`40fc7149`。

固定压测口径为 256 窗口、G1、1 Matcher、4 Lane、128 symbols、MIXED batch20、BUSY_SPIN。最近一次 JFR 结果：

- business throughput：约 419,611 ops/s；
- Owner CPU：97.5%；Matcher CPU：62.4%；Lane 线程 CPU：约 89%～90%，但有效业务执行比例只有约 47.6%；
- Core 分配约 806 MB/s，约 1.9 KB/业务操作；Owner 约 125 MB/s；
- ordinary PLACE p99 约 13.2 ms，CANCEL p99 约 10.5 ms；
- GC pause 约占测量时间 1.23%，没有分配失败或明显锁竞争；
- PLACE 的 Matcher→Lane 完成到 Owner 可见 p99 约 0.52 ms。

结论：主要瓶颈是 Owner 在热路径重复做状态协调、索引和终态物化，以及 Lane/Matcher/Owner 之间的重复对象创建。GC 和 Lane 等待不是第一瓶颈。

## 对六项主线的审查

### 1. `prepareClusterPipelineScope`

**已完成基础部分，约 70%。**

已经完成：

- Owner 侧潜在 counterparty 扫描已移除；
- 普通 PLACE 使用用户 Lane 和静态 matcher shard 路由；
- 撤单保留 `orderId → symbol/shard/lane` 最小路由索引。

仍未完成：

- `ClusterCommandWindow` 仍保留 `candidateOrders[]` 等候选集合；
- 批量命令仍逐项解析；
- 跨 shard PLACE/CANCEL batch 当前会退回确定性的逐项固定提交路径，尚未做同一 command 内的并行 shard 子批；
- CANCEL batch 仍逐项访问最小 order route index。

不能删除最小 order route index。它是撤单和恢复所必需的；可以删除的是候选集合和潜在对手方集合。

### 2. `TradingCoreRuntime.apply`

**只有铺垫，约 20%。**

已有解码缓存、`IngressRoute` 和 `PendingMatchingRing`，但 `apply()` 仍同时负责：

- 入口校验、幂等和 source sequence；
- pending matching、submission fence 和 partition fence；
- admission 通知；
- 查询分支；
- 终态前的状态判断和提交上下文。

尚未存在固定的 `IngressRecord` 接纳阶段，也没有单一的 `ADMITTED → MATCHER_DONE → LANES_DONE → COMMITTED` 状态。当前仍有 `deferredMatching`、ready mask、多个 continuation 和多套 Owner 侧 map。

### 3. `progressPlaceAdmissions`

**普通 PLACE 做过局部改造，约 35%，协议目标尚未实现。**

普通 PLACE 已不再由 Owner 同步等待 admission 完成后才提交 Matcher，但当前仍保留：

- `PlaceAdmissionEvent`；
- `placeAdmissionReadyShardMask`；
- Owner 的 admission notification drain；
- batch admission 的 Owner 收集和继续执行；
- Matcher 对 admission event 的依赖等待。

因此还没有实现真正的 `Lane → Matcher AdmissionReceipt`。`progressPlaceBatchAdmissions()` 也不能直接删除，必须先把批量路径改成固定 batch slot。

### 4. `MatcherSettlementDispatcher.dispatchDirect`

**未实现真正直达，约 10%。**

当前事件内容可以提前准备，但实际路径仍是：

```text
Owner prepareDirect → Owner dispatchDirect → Owner submit Lane queue
```

`MatcherSettlementDispatcher.dispatchDirect()` 仍要求 Owner 调用，Owner 仍然是 Lane SPSC 队列生产者。必须改成 Matcher 生产 settlement ring，Owner 只保留控制队列生产者，并在 Lane 内按 sequence 合并两条队列。

### 5. `completeDispatchedMatcherSettlement`

**未完成，约 15%。**

当前 Owner 仍调用 `collectMatcherSettlement()`，并执行：

- Lane 完成收集；
- treasury delta 合并；
- 订单和持仓变更物化；
- revision 更新；
- changed keys 和结果索引；
- 资金守恒验证；
- 提交发布和响应字段准备。

已有 active lane mask、lazy allocation 和部分 LaneDelta 复用优化，但这些只是减少遍历，尚未完成职责迁移。

### 6. `OwnerCommitPublisher` 和 `CommandResultBuilder`

**基本未完成。**

Owner 仍负责资金变更、全局事实索引、投影发布和结果构造。当前没有 `ResponseArena`，也没有 Lane 侧固定响应 descriptor。`CommandResultLedger` 本身是必要的有界幂等账本，不能删除；应删除的是账本前重复创建和复制响应数据的路径。

## 对上一轮改造方案的取舍

### 保留并合并的方案

1. **Lane 内部可变状态**：这是减少 `OrderRuntime`、`ReservationRuntime`、`PositionRuntime` 重建的最高收益改造。只在发布边界生成不可变快照，不能把全局公开状态改成可变对象。
2. **固定容量槽位**：`IngressRecord`、`AdmissionReceipt`、`SettlementRecord`、`LaneCommitDelta` 只作为逻辑字段集合，实际实现为已有 ring 的复用 slot，不能每笔 `new` Java record。
3. **Matcher→Lane 直达**：保留，但必须使用固定 SPSC 队列和 sequence gate，不能把现有 SPSC 粗暴改成 MPSC。
4. **Lane 侧响应 Arena**：保留，用 offset/length 描述响应，Owner 只持有 descriptor。
5. **低频查询和控制分支拆出 `apply()`**：保留，主要目的是降低理解和维护成本，不把它当成主要吞吐收益来源。

### 暂缓或修正的方案

- 不一次性把所有 product line、风控和控制命令迁入新协议。先完成普通 PLACE、CANCEL、REPLACE/AMEND 的共同路径，再按命令矩阵迁移。
- 不为每个阶段再增加一组 map、continuation 或上下文对象。新状态必须落在固定 `CommandSlot` 中，状态只保留四个阶段。
- 不让 Lane 直接修改全局 revision、全局 hash、全局 projection 或结果账本。Lane 只能修改自己拥有的局部状态并发布 Delta。
- 不盲目把跨 shard batch 拆开。先确认现有 batch 的原子性和响应语义；若协议要求整批一致，拆分只能是内部固定子批，最终仍由一个 batch sequence 提交。
- 不删除最小撤单路由、幂等账本、snapshot/replay 信息或终态提交顺序。
- 不把客户端进程的 byte[] 分配误判为 Core Owner 瓶颈。入口 byte[] 要优化，但排在 Lane 状态和 Owner 终态物化之后。

## 执行计划

### 阶段 0：建立可回退基线

只增加验证约束，不改变业务逻辑：

- 编译和正确性测试必须使用 JDK 27；脚本在 `java -version` 不是 27 时直接退出；
- 固定记录当前 HEAD、配置和 JFR 指标；
- 保留现有 snapshot、replay、幂等和资金守恒测试作为回归门槛。

### 阶段 1：完成静态路由收敛

涉及：`ClusterCommandWindow`、`TradingCoreRuntime.prepareClusterPipelineScope`、批量路由代码、`ActiveOrderIndex`。

改动：

- 删除 `candidateOrders[]` 及其只用于预扫描的辅助方法；
- 普通命令只写入固定 `IngressRoute` 字段；
- PLACE/CANCEL batch 按静态 symbol shard 路由；跨 shard 保持现有逐项固定提交语义，不在 Owner 建立候选集合；
- CANCEL batch 只读取最小 order route index，不建立第二份候选集合；
- 保持跨 shard batch 的现有原子响应语义。

验收：普通下单、撤单、批量撤单、跨 shard batch 路由和恢复结果一致。

### 阶段 2：把 `apply()` 收敛为接纳器

涉及：`TradingCoreRuntime`、`CommandSlot`、`PendingMatchingRing`、`MatchingCommandAdmission`。

改动：

- 将入口校验、幂等、source sequence、sequence 分配和路由集中到一个小方法；
- 查询和低频控制分支移到独立 dispatcher；
- 普通命令只使用一个固定 `CommandSlot`；
- 用 enum/byte 状态替代普通命令的 deferred map、continuation 和多组 boolean；
- 保留 pending ring 的固定容量、snapshot cursor 和恢复信息；
- 不在此阶段移动账户业务逻辑，确保每一步可回退。

目标：Owner 不再为了判断“某个阶段是否完成”扫描多套状态。

### 阶段 3：实现 Lane admission receipt

涉及：`PlaceAdmissionEvent`、`SettlementLaneWorker`、`MatcherPipelineGroup`、`MatchingCommandAdmission`。

改动：

- Owner 只向用户 Lane 发布固定 admission slot；
- Lane 在本地完成冻结和订单准入后，向目标 Matcher shard 的 receipt ring 写入 primitive receipt；
- Matcher 读取 receipt 后再执行订单簿撮合；
- 拒绝也通过 receipt 返回，Owner 最后按 sequence 提交拒绝事实；
- 普通 PLACE 删除 Owner 的 admission notification/ready mask 路径；
- batch 先使用固定 batch slot，不能继续沿用逐项 `Decision[]`、`ResolvedPlaceOrder[]` 和 `CoreMatchingOrder[]`。

并发约束：每条 receipt ring 只有一个 Lane 生产者和一个 Matcher 消费者；不得使用 MPSC CAS 代替协议设计。

### 阶段 4：实现真正的 Matcher→Lane settlement 直达

涉及：`MatcherSettlementDispatcher`、`MatcherSettlementEvent`、`SettlementLaneWorker`、`TradingRuntimeState`。

改动：

- 每个 Lane 建立 Matcher settlement SPSC ring；
- Owner 另保留一个 Lane control ring；
- Matcher 直接写 settlement slot，删除 Owner 的 `prepareDirect()` 和 `dispatchDirect()` 热路径调用；
- Lane 使用 `coreSequence/laneSequence` gate 合并两条队列，确保 admission、control、settlement 的确定性顺序；
- settlement slot 只保留 primitive 事实增量，不携带 `OrderRuntime`、`ReservationRuntime`、List 或共享可变对象；
- ring cursor、未消费 slot 和 gate 状态纳入 snapshot/replay。

这一阶段完成后，Owner 不再是 Matcher→Lane 的数据生产者。

### 阶段 5：Lane 内部就地修改，终态只发布一次

涉及：`AccountLaneState`、`OrderRuntime`、`ReservationRuntime`、`PositionRuntime`、`RuntimeDerivativeFillCalculator`、`RuntimeSpotMatchProcessor`、`TradingRuntimeState.LaneDelta`。

改动：

- Lane map 内部存放线程私有 mutable state；
- fill、reserve、release、consume 直接更新 primitive 字段；
- 删除每个 fill 的 `withFill`、`withStatus`、`withRemainingUnits` 等替换；
- 每个终态实体只生成一次不可变 published snapshot；
- `LaneDelta` 复用固定数组，携带 `coreSequence`、lane id、资金增量、变更 key 和 response descriptor；
- Owner 仍通过不可变发布视图、snapshot 和 replay 读取状态。

这是预计最大的分配下降来源，重点验证 `OrderRuntime`、`ReservationRuntime`、`PositionRuntime` 是否从热点分配中消失。

### 阶段 6：Owner 只做紧凑终态提交

涉及：`OrderedCommitCoordinator`、`TradingRuntimeState.collectMatcherSettlement`、`OwnerCommitPublisher`、`CommandResultBuilder`、`CommandResultLedger`。

改动：

- `completeDispatchedMatcherSettlement()` 改为消费已完成的 Lane Delta；
- Owner 只维护当前队头 sequence、已完成 lane mask 和固定 slot 中的 Delta；
- Owner 不再重新遍历订单、持仓和资金对象；
- Owner 只合并资金增量、更新全局 revision/hash、写 projection/log/result ledger；
- Lane 侧 `ResponseArena` 负责终态字段准备和一次性编码；
- Ledger 保存固定槽位的 response descriptor，保留幂等和重放语义。

全局事实索引若涉及多个 Lane，只在 Owner 使用 Delta 中的已确定变更 key 更新；局部订单、账户、持仓索引留在 Lane。

### 阶段 7：迁移剩余命令

在普通交易路径通过全部正确性门槛后，按同一模型逐类迁移：

1. REPLACE、AMEND、CANCEL batch；
2. 风控扫描和强平；
3. 标记价格、资金费、保险基金和结算；
4. ADL、交割、期权等特殊命令。

每类命令先划分“Lane 局部状态”和“Owner 全局事实”，禁止为每条业务线创建独立协调器。

## 正确性门槛

压测前必须全部通过：

- 普通 PLACE/CANCEL/REPLACE/AMEND 的接受、拒绝、重复请求和乱序请求；
- 多 Lane 成交、跨 Lane treasury delta 和 lane mask；
- admission 失败、Matcher 拒绝、Lane 队列满、超时和重试；
- snapshot 后 replay，ring cursor 和 pending slot 一致；
- source sequence、commandId 幂等和结果账本 retention；
- 业务状态 hash、资金守恒、订单状态和持仓状态一致；
- batch 原子性、跨 shard 子批和最终响应顺序；
- 进程重启后无重复冻结、重复成交或重复释放。

## 性能验收门槛

所有比较使用同一套配置，不改变窗口、线程数、币对数、业务比例或等待策略：

- JDK 27、G1、BUSY_SPIN、1 Matcher、4 Lane、128 symbols、256 window、MIXED batch20；
- warmup 30 秒、measure 60 秒，至少两次 plain run；
- JFR 只用于归因，不能用 JFR run 的吞吐直接替代 plain run；
- Owner 单核占用目标：先低于 85%，最终争取 60% 以下；
- Core 分配目标：从约 806 MB/s 明显降到 500～600 MB/s 以下，再继续压缩；
- 普通 PLACE p99 目标：不高于 5 ms；
- Lane 有效业务执行比例目标：超过 80%；
- unfinished、sequence、hash、幂等和资金守恒错误必须为 0。

30 万+/s 是目标，不在代码未完成前承诺结果。若 Owner 仍超过 85%，下一步只分析剩余 Owner top frame，不继续增加并发线程或复杂协调器。

## 明确不做的事情

- 不增加 Matcher 数量；
- 不把现有 SPSC 改成通用 MPSC；
- 不删除撤单最小路由索引、幂等账本、snapshot/replay 和顺序提交；
- 不把所有不可变公开状态改成跨线程可变对象；
- 不在普通命令路径保留多套 pending map、continuation 和阶段 flags；
- 不先迁移低频业务线来掩盖普通交易链路瓶颈；
- 不在 JDK 版本未确认是 27 时进行本地性能验收。

## 待审核决策

审核时只需确认三点：

1. 是否同意先按阶段 1～6 完成普通交易共同链路，再迁移剩余命令；
2. 是否接受“逻辑上的 IngressRecord/Receipt/Delta，实际使用固定 ring slot，不创建 Java 对象”的实现约束；
3. 是否同意以正确性门槛全部通过作为最终压测的前置条件。

审核后按阶段顺序执行；阶段状态和验收证据持续记录在本文件中。

## 执行记录

- 阶段 0（JDK 27 构建门槛）：已完成。父 POM、JFR 分析和资格脚本统一检查 JDK 27；Corretto 27 可直接运行。
- 阶段 1（静态路由收敛）：已完成。删除当前 ingress 的候选订单 scratch 和相关调用；冲突校验、窗口登记直接读取已解码命令；仅保留已进入窗口命令的精确订单 ID，用于撤单/重试 fence；跨 shard batch 保持确定性的逐项固定提交语义。
- 阶段 1 验收：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`，JDK 27，910 项通过、1 项既有跳过、0 失败。
- 阶段 2（`apply()` 接纳收敛）：已完成。查询/低频分支移入 `applyQuery()`，命令入口单独进入 `applyCommandIngress()`；在途 command-id 索引改为固定开放寻址 primitive 表；延后匹配的时间、位置和 source key 放入复用 `CommandSlot`，删除 `LinkedHashMap<Long, DeferredMatching>` 和 `DeferredMatching` record；匹配生命周期用 slot byte 统一表达 admitted/deferred/submitted/matcher-done/lanes-done/committed，去掉重复 submitted/deferred boolean；continuation 改为明确的 settlement/cancel 类型字段，消除通用 `Object` 状态。
- 阶段 2 验收：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`，JDK 27，910 项通过、1 项既有跳过、0 失败。补正了一个原有测试等待条件，使其等待 Lane 已建立订单后再校验准入版本；未执行吞吐压测。
- 下一阶段从 `PlaceAdmissionEvent` 的 Lane→Matcher receipt 路径开始；阶段 3 完成前不进行吞吐压测。
