# Owner / 交易链路低分配重构计划（待审核）

状态：**阶段 8.4a 已完成，进入阶段 8.4b**。每个阶段必须完成代码、旧路径清理和正确性验收后才进入下一阶段；压测留到全部核心阶段完成后。

本计划最初为待审核草案，现按审核意见执行。阶段 4 已完成并通过 JDK 27 正确性验收（全量 1,090 项，0 failures，0 errors，1 skipped）；没有启动吞吐压测。

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

**普通 PLACE 的 Lane→Matcher receipt 路径已完成（100%）。**

已经完成：

- 每个 `(account lane, matcher shard)` 使用固定容量的 SPSC `AdmissionReceiptRing`；
- `PlaceAdmissionEvent` 只在用户 Lane 执行冻结、准入和本地订单写入，然后发布 primitive receipt；
- Matcher 直接消费 receipt，拒绝也走同一 receipt，不再从 Owner 读取 `PlaceAdmissionEvent` 的业务结果；
- Owner 不再等待 admission 才能提交 Matcher；Owner 侧通知只用于非阻塞地发布本地准入视图和唤醒被提交顺序挡住的后续命令；
- Lane 使用独立 admission mailbox，即使主队列头是尚未发布的 Matcher 结算，也不会阻塞后续准入。

批量 PLACE admission 仍使用旧的批量协调器和通知游标，属于阶段 7 的批量迁移范围；本阶段没有把普通路径与批量路径混成两套新的中间状态。

### 4. `MatcherSettlementDispatcher.dispatchDirect`

**阶段 4 已完成（100%，异步普通命令路径）。**

阶段 4 之前的实际路径是：

```text
Owner prepareDirect → Owner dispatchDirect → Owner submit Lane queue
```

现在异步普通 PLACE、CANCEL、REPLACE/AMEND、TRIGGER 的路径是：

```text
Owner reserve pooled event → Matcher builds fact → Matcher publishes per-shard SPSC rings → Lane merge gate
```

每个 Lane 为每个 Matcher shard 建立独立的固定引用 SPSC；Owner 仍只生产控制队列。Lane 先处理 admission，再在交接屏障之外按 `coreSequence` 合并控制队列和 Matcher rings。环满时 Matcher 采用健康检查忙等背压，Lane 故障或关闭会立即退出，不把有效命令误报成队列满。

`dispatchDirect()` 只保留同步调用和阶段 7 尚未迁移的 batch 兼容路径，异步普通命令不再调用。阶段 5 才迁移事件内部的 `OrderRuntime`/`MatcherSettlementPlan` 引用为 Lane-owned primitive delta；这是状态就地修改和终态发布的一部分，不能与 transport 阶段混为一项。

快照只在 Owner pending、Lane control 和 Matcher settlement rings 全部清空后建立；运行时会拒绝任何残留环游标，恢复从空 transport cursor 重放 cluster log，避免把线程间引用写入快照。

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

**状态：已完成（100%）。** 普通 PLACE 已使用按 Lane×Matcher shard 的固定 SPSC receipt；全量服务测试通过后才允许进入阶段 4。批量 admission 按阶段 7 迁移，不作为本阶段的未完成项。

### 阶段 4：实现真正的 Matcher→Lane settlement 直达

涉及：`MatcherSettlementDispatcher`、`MatcherSettlementEvent`、`SettlementLaneWorker`、`TradingRuntimeState`。

改动：

- 每个 Lane 建立 Matcher settlement SPSC ring；
- Owner 另保留一个 Lane control ring；
- Matcher 直接写 settlement slot，删除 Owner 的 `prepareDirect()` 和 `dispatchDirect()` 热路径调用；
- Lane 使用 `coreSequence/laneSequence` gate 合并两条队列，确保 admission、control、settlement 的确定性顺序；
- settlement slot 只保留 primitive 事实增量，不携带 `OrderRuntime`、`ReservationRuntime`、List 或共享可变对象；
- ring cursor、未消费 slot 和 gate 状态纳入 snapshot/replay。

**状态：已完成（100%，异步普通命令路径）。** Matcher 不再通过 Owner 向 Lane 提交异步普通结算；Owner 只保留控制 ring 生产者。batch 的旧 dispatch 归阶段 7，事件内部对象和响应内容的迁移归阶段 5/6。

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

**状态：已完成（100%）。** `OrderRuntime`、`ReservationRuntime`、`PositionRuntime` 已改为 Lane 私有字段就地更新；Matcher 结算只在发布边界生成不可变 after-image，Owner 继续读取隔离的发布视图。终态 reservation/order route removal 独立消费，避免零 reservation 终态因跳过快照而残留旧索引。保留同步、恢复、snapshot、replay 所需的值语义和兼容构造路径。

验证：JDK 27 全量服务正确性套件 931 项，0 failures，0 errors，1 skipped；批量真实成交后的故障恢复定向测试通过。尚未进行吞吐压测。

### 阶段 6：Owner 只做紧凑终态提交

涉及：`OrderedCommitCoordinator`、`TradingRuntimeState.collectMatcherSettlement`、`OwnerCommitPublisher`、`CommandResultBuilder`、`CommandResultLedger`。

状态：**已完成（100%，普通单笔结算路径）**。

改动：

- `MatcherSettlementEvent`/`LaneCancelEvent` 直接把 Lane 已产生的 primitive changed-key 集合交给 Owner；Owner 不再重走 `MatcherSettlementPlan`，也不再为终态提交重新查找订单运行时对象。
- `completeDispatchedMatcherSettlement()` 和同步撮合路径只合并已完成 Lane Delta，Owner 保留资金增量、队头顺序、全局 revision/hash、projection/log/result ledger 和最终响应发送。
- `CommandSlot` 为单笔普通命令复用固定 `LaneResultTarget`，Lane 保存订单 after-image，并在结果已完整时完成单订单响应编码；Owner 只消费 response descriptor。双订单 REPLACE/AMEND 和 batch 继续走既有兼容编码，保留原有终态/原子性语义。
- `CommandResultBuilder` 使用 Lane 提供的 changed-key 集合时跳过 Owner 全局 changed-user 扫描；Lane 局部账户、订单和持仓状态仍由 Lane 所有。

全局事实索引若涉及多个 Lane，只在 Owner 使用 Delta 中的已确定变更 key 更新；局部订单、账户、持仓索引留在 Lane。完整的 slab/arena 响应存储和剩余命令迁移属于阶段 7，不能在本阶段虚报完成。

验证：JDK 27 编译通过；服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；普通撮合、批量原子性、恢复和 admission 目标测试通过。

### 阶段 7：迁移剩余命令

在普通交易路径通过全部正确性门槛后，按同一模型逐类迁移：

1. REPLACE、AMEND、CANCEL batch；
2. 风控扫描和强平；
3. 标记价格、资金费、保险基金和结算；
4. ADL、交割、期权等特殊命令。

每类命令先划分“Lane 局部状态”和“Owner 全局事实”，禁止为每条业务线创建独立协调器。

#### 阶段 7.1a：批量结果传输先固定化

状态：**已完成（100%）**。CANCEL batch 和流水 PLACE batch 已使用 `OrderBatchPending` 内预分配的
`CoreMatchingResult[]` 及计数器；Matcher direct publish 不再把数组包装成 `List`，Owner 侧完成校验、遍历和回收也只访问固定槽位。批量 admission 的 Owner 等待和逐项迁移仍属于后续 7.1b，不在本子阶段虚报完成。

#### 阶段 7.1b：完成标记和唤醒状态简化

状态：**已完成（100%）**。准入、批量准入和 Lane 撤单事件的完成发布改为单个 `volatile` 标志，删除仅用于单布尔字段的反射式 VarHandle 初始化。Matcher SPSC 提交改为无条件 `unpark`，依靠 JVM 的粘性唤醒令牌关闭发布与休眠竞态，删除 `parkRequested` 状态机。该批次不改变队列顺序、完成栅栏、snapshot/replay 或业务结果。

正确性验收：JDK 27 下服务模块全量回归 931 项，0 failures、0 errors、1 skipped；快照/重入测试单独重跑通过。批量准入的 Owner 等待和逐项业务迁移仍属于下一批，不能在此处虚报完成。

#### 阶段 7.2a：删除批量准入通知中的普通命令冗余分支

状态：**已完成（100%）**。普通 PLACE 的准入完成只发布到独立的 `admissionReceiptReady` 队列；`placeAdmissionReady` 队列只服务仍保留的流水 PLACE batch。删除通知消费器中对普通 PLACE 的重复 `collectPlaceAdmissionIfReady` 调用，并移除随之重复的空判断。批量准入顺序、Owner before-state 捕获和恢复边界未改变。

正确性验收：JDK 27 下服务模块全量回归 931 项，0 failures、0 errors、1 skipped；批量、通知探测、管线和恢复目标测试通过。未执行吞吐压测。

#### 阶段 7.2b：批量准入完成通知收敛为 shard 唤醒位

状态：**已完成（100%）**。批量准入不再为每个 Account Lane 分配/维护完成序号队列、Owner 期望计数和逐条轮询；Lane 只按目标 Matcher shard 置位一次唤醒位，`OrderBatchPending.placeBatchAdmissionEvent.complete()` 是唯一完成事实。Owner 仅在提交 shard 队头读取事件并保留未完成位，避免同 shard 批次通知合并时丢唤醒。普通 PLACE 的 admission receipt 队列、批量 before-state 捕获、Matcher 顺序和恢复边界保持不变。

正确性验收：JDK 27 下服务模块全量回归 931 项，0 failures、0 errors、1 skipped；批量 admission、并发通知、管线和恢复目标测试通过。未执行吞吐压测。

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
- 阶段 3（普通 PLACE 的 Lane→Matcher admission receipt）：已完成。新增按 Lane×Matcher shard 的固定 SPSC `AdmissionReceiptRing`；Lane 完成冻结后直接发布 primitive receipt，Matcher 在自己的线程消费并决定是否撮合；Owner 不再把 `PlaceAdmissionEvent` 结果转交给 Matcher。Lane 新增独立 admission mailbox，未发布的 Matcher 结算不会阻塞后续准入；批量 PLACE 旧协调路径保留到阶段 7。
- 阶段 3 验收：JDK 27 下 `mvn -pl surprising-aeron-core/surprising-aeron-service -am test`，931 项通过、1 项既有跳过、0 失败、0 错误；其中 `ClusterCommandPipelineTest` 为 244 项通过、1 项既有跳过。未执行吞吐压测。
- 阶段 4（Matcher→Lane 结算直达）：已完成。普通异步结算使用 Matcher 唯一生产、Lane 唯一消费的固定 SPSC；Owner 只保留控制队列生产者，旧 `dispatchDirect()` 兼容入口不再用于普通路径。
- 阶段 5（Lane 就地状态更新）：已完成。订单、reservation、position 和账户状态在 Lane 内复用对象就地更新；只有发布边界生成不可变 after-image，终态 route 清理单独处理，避免 Owner/Matcher 间重复物化。
- 阶段 6（紧凑终态提交）：已完成。Lane 直接提供 primitive changed-key 集合和单笔响应 after-image；Owner 不再重走 settlement plan、不再为单笔终态重新查找运行时订单或编码响应，只合并 Delta 并完成全局有序提交。REPLACE/AMEND 双订单和 batch 仍保留兼容语义，待阶段 7 迁移。
- 阶段 4～6 验收：JDK 27 编译及服务模块全量回归通过，`931 tests, 0 failures, 0 errors, 1 skipped`；普通撮合、跨 Lane 结算、批量原子性、恢复和 admission 目标测试通过。未执行吞吐压测。
- 阶段 7.1a（批量结果固定槽位）：已完成。`OrderBatchPending` 的批量 Matcher 结果由复用 `CoreMatchingResult[]` 承载，CANCEL/流水 PLACE 的 Matcher direct publish 和 Owner 收集不再经过 `ArrayList`。
- 阶段 7.1a 验收：JDK 27 下批量/恢复/直达结算目标测试通过；服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`。未执行吞吐压测。
- 阶段 7.1b（完成标记和唤醒状态简化）：已完成。准入、批量准入和撤单事件移除仅为单布尔字段服务的反射式 VarHandle；Matcher 提交不再维护 `parkRequested` 状态机，改为无条件唤醒。完整服务回归 `931 tests, 0 failures, 0 errors, 1 skipped`；未执行吞吐压测。
- 阶段 7.2a（批量准入通知冗余清理）：已完成。普通 PLACE 不再从批量通知游标重复收集；完整服务回归 `931 tests, 0 failures, 0 errors, 1 skipped`；未执行吞吐压测。
- 阶段 7.2b（批量准入完成通知收敛）：已完成。删除批量准入 Lane 序号通知队列、Owner 期望计数及逐条消费，改为 Matcher shard 唤醒位；完整服务回归 `931 tests, 0 failures, 0 errors, 1 skipped`；未执行吞吐压测。
- 阶段 7.2c（批量准入 Owner 交接去重）：已完成。批量准入的 Lane publication 在 Matcher 提交前已经由 Owner 应用；提交阶段改为只接管 primitive changed-key、余额和资金增量，删除重复的 `LaneDelta.commitTerminalToOwner` 全量用户/订单/预留遍历与再次写入。保留失败回滚、资金守恒、快照和有序提交边界。
- 阶段 7.2c 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；批量 admission、并发通知、管线和恢复目标测试通过；未执行吞吐压测。
- 阶段 7.3（批量 PLACE 终态响应交接）：已完成。流水 PLACE batch 的结果目标明确声明其所有项属于同一 Account Lane；Lane 在结算完成后从私有订单表补齐未发生变化的订单 after-image，并在完成回执前编码批量响应。Owner 不再为这类批量逐项查询 `runtimeOrder` 或承担响应编码；CANCEL/AMEND、顺序 PLACE 及终态撤单继续使用原有兼容语义。
- 阶段 7.3 验收：JDK 27 下 `ClusterCommandPipelineTest`、`CoreOrderedOrderBatchTest`、`RuntimeCommitRecoveryTest` 定向回归通过，随后服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；未执行吞吐压测。
- 阶段 7.4（风控扫描/强平续扫分配收敛）：已完成。`RiskScanCoordinator` 的每 Lane 输入、结果和预算数组跨 CONTINUE_RISK_SCAN 复用；RiskCommands 使用单一 Owner continuation，删除每次异步续扫的匿名 `BooleanSupplier` 和协调器对象。异常退出会在下一个串行 direct 命令重新清空协调器，保持清算编号溢出回滚后的可恢复性。
- 阶段 7.4 验收：JDK 27 下风险并行、清算预算溢出恢复、管线和恢复测试通过；服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；未执行吞吐压测。
- 阶段 7.5（资金费异步批处理复用）：已完成。异步 FundingWork 复用各 Lane 的用户分组数组和列表容量；PerpetualFundingCommands 使用单一可复用 continuation，删除每次分页命令的匿名回调。资金费分页、资金守恒和恢复语义未改变。
- 阶段 7.5 验收：JDK 27 下资金费、结算、风险管线和恢复定向测试通过；服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；未执行吞吐压测。
- 阶段 7.6（ADL、强平、交割/期权结算临时对象收敛）：已完成。ADL、保险/强平结算和单笔清算执行改为可复用的 Lane 操作 continuation；固定命令槽保留各自的结算工作与完成回调，避免共享异步对象覆盖并发序列。结算按 Lane 的用户/订单分组、prepared 缓冲和 treasury delta 在槽位复用时清空重用；同一结算工作实现统一的 phase operation，删除 CANCEL/PREPARE/APPLY 三个阶段的匿名 Lane lambda。命令回收时主动清理 command/page/runtime 引用，保留数组容量。
- 阶段 7.6 验收：JDK 27 下 ADL、强平、结算、风控、跨产品财务矩阵和恢复定向测试通过；服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`。未执行吞吐压测。
- 下一阶段：最终正确性门槛审计（普通、批量、风控、资金费、强平、ADL、交割/期权、snapshot/replay、幂等和资金守恒），全部通过后使用统一 JDK 27/G1/BUSY_SPIN 配置执行 64/128 窗口吞吐与 JFR 分配归因。
- 阶段 7.7a（顺序批量结算直达）：已完成。顺序 PLACE、AMEND 和 CANCEL batch 的 pooled settlement event 在提交 Matcher 前标记为 Matcher-owned publication；Matcher 生成事实后直接发布到 Lane，Owner 不再预先把同一事件提交到 Lane。保留流水 PLACE batch 的分区队首门，避免跨命令提前发布破坏批量顺序。
- 阶段 7.7a 验收：JDK 27 下服务模块批量、管线、结算和恢复定向回归通过；完整服务回归 `931 tests, 0 failures, 0 errors, 1 skipped`。未执行吞吐压测。
- 阶段 7.7b（流水批量结算门控直达）：已完成。流水 PLACE batch 的 settlement event 在 Matcher 提交前预留为 Matcher-owned publication；Matcher 线程完成批量事实后直接写入各 Lane 的 shard ring。Owner 只记录一次逻辑分区 dispatch 位和 in-flight 计数，不再在 Matcher 完成后调用 `dispatchDirectMatcherSettlement`；Lane 以 `coreSequence` 合并控制与结算，保留跨命令顺序。
- 阶段 7.7b 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；批量流水、跨 Lane 结算、恢复和快照目标测试通过。未执行吞吐压测。
- 阶段 7.8（删除异步普通路径的旧 dispatch 兼容调用）：已完成。旧 `dispatchDirectMatcherSettlement`/`dispatchDirect` API 已删除并替换为明确限定同步兼容用途的 `dispatchOwnerControlledSettlement`；异步普通和批量路径只使用 Matcher-owned publication，避免后续代码误把 Owner 当作 settlement producer。同步测试和恢复仍保留必要的 Owner-controlled handoff。
- 阶段 7.8 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；全仓源码无 `dispatchDirectMatcherSettlement` 或 `dispatchDirect` 调用。未执行吞吐压测。
- 阶段 8.1（全命令生命周期统一槽位）：已完成。资金费、风险扫描、保险/强平结算和 ADL 的异步工作及完成续步统一由固定 `CommandSlot` 持有；命令类不再各自保存 continuation 或工作对象。标记价、交割/期权使用已有同步或结算工作路径，不另建控制层；风险分页、触发扫描、资金费进度、清算状态和恢复字段保持原语义。
- 阶段 8.1 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；资金费、风险扫描、ADL、强平、交割/期权结算和恢复目标均通过。未执行吞吐压测。
- 阶段 8.2（普通控制命令分配收敛）：待完成。将余额、转账、杠杆和持仓模式/保证金调整的 per-command work 与 Lane operation 从匿名 lambda 收敛到 `CommandSlot` 固定工作区；保留失败回滚、幂等、revision 和资金守恒语义。
- 阶段 8.2（普通控制命令分配收敛）：已完成。余额、转账、杠杆、持仓模式和逐仓保证金工作对象改为可复用实例，Lane 派发直接使用工作对象的 `IntFunction`，删除每笔命令的匿名 Lane lambda 和重复 Owner continuation；失败回滚、幂等、revision、转账 hash 和资金守恒语义保持不变。
- 阶段 8.2 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；余额、转账、杠杆、持仓调整及恢复目标通过。未执行吞吐压测。
- 阶段 8.3（触发订单控制续步收敛）：待完成。将算法单、撤单定时器、触发执行和 OCO 扫描的剩余匿名 Owner/Lane continuation 收敛到固定 `CommandSlot`，去除重复的 mutation/execute 中间回调，同时保留触发订单顺序、幂等和恢复语义。
- 阶段 8.3（触发订单控制续步收敛）：已完成直接命令部分。算法单 upsert、触发单 upsert、撤单、claim、complete、trailing、expire、retry 改为固定 `CommandSlot` 的 mutation 操作和 primitive 参数，删除这些命令的匿名 Owner/Lane continuation；触发顺序、幂等、OCO 状态和结果视图保持不变。
- 阶段 8.3 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；触发订单/算法单、异步 Lane、恢复和幂等目标通过。未执行吞吐压测。
- 阶段 8.4（触发扫描动态续步）：拆为 8.4a/8.4b，避免把扫描状态和动态 mutation 混成一个难以验证的通用对象。
- 阶段 8.4a：已完成。标记价后的 `PendingTriggerScan` 和触发子单执行续步改为命令实例内的单实例可复用工作；消除每次分页扫描和每个触发子单的捕获对象图，保留 cursor、预算、OCO 顺序和子单提交顺序。
- 阶段 8.4a 验收：JDK 27 下服务模块全量回归 `931 tests, 0 failures, 0 errors, 1 skipped`；触发扫描、子单执行、恢复和幂等目标通过。未执行吞吐压测。
- 阶段 8.4b：待完成。将扫描内部的过期、trailing、OCO sibling mutation 及 collect 回调改成同一工作对象的显式阶段字段，消除每个候选项的匿名 lambda；保持扫描分页和失败重放语义。
