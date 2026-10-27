# 业务逻辑深度清理与过度设计根除总结

## 1. 任务背景与核心目标
针对生产交易撮合引擎在高并发、几十万用户体量、大持仓及厚盘口混合负载下的性能要求，对业务核心链路中长期积累的“过度设计”、“过度保护”、“套娃层层转发包装”、“重复查表”以及“多余内存分配/装箱”进行了全面彻底的清理与重构：
1. **彻底拍平准入与撤单重载链路**：消除 `RuntimeOrderAdmission` 与 `TradingAccountRuntime.cancelOrder` 中多层套娃重载及针对同一原单/预留的重复哈希查找；
2. **彻底消灭热路径临时堆分配与装箱**：
   - 消除准入热路径上的临时 Record（`AdmissionIdentity`）与撤单临时 Record（`CanceledOrder`）；
   - 打通 `PrimitiveLongChangeSet` 的 `PrimitiveLongView` 原语直通，彻底消除批次提交链路中成千上万次的 `Long` 自动装箱与 `Iterator` 堆分配；
   - 池化复用现货结算累加器中的 `SpotBalanceState`，实现现货多笔成交结算的完全零 GC；
3. **消除撤单与终态释放链路上的重复 HashMap 查表**：在 `LaneCancelEvent`、`MatcherSettlementEvent`、`RuntimeSpotMatchProcessor`、`RuntimeDerivativeMatchProcessor` 中实现已解析对象引用直通，消除每次撤单/成交终态释放对同一订单和预留的 2~3 次重复 HashMap 探测；
4. **消除批量处理中的冗余并行数组**：统一 `OrderBatchPending` 槽位，删除 `resolvedOrderSlots`，消除双重清空与对象数组分配；
5. **针对高频场景优化风控扫描**：针对做市商与高频账户 99% 的单持仓/单向持仓场景，实现 $O(1)$ 常数时间直通游标推进，消除字典树扫描与全量哈希查找；
6. **严密规范系统账本与控制命令生命周期**：恢复 `systemLedgerStarted` 严格生命周期状态管理，确保业务拒绝及时抛出并遵循物化提交契约。

---

## 2. 核心重构与优化细节

### 2.1 准入层拍平与零堆分配（`RuntimeOrderAdmission` & `ReplacementAdmissionEvent`）
- **拍平 8 层嵌套套娃**：彻底废弃多层重载转发，私有准入底层统一收敛为 Primitive 原语 + 已准备对象直通模式；
- **改单原单直通**：改单准入 `ReplacementAdmissionEvent` 在外层已完成原单核验后，直接将 `original: OrderRuntime` 透传给底层的准入校验，消除了对同一 Lane HashMap 的 2 次重复哈希查找与冲突探测；
- **消除 `AdmissionIdentity` 临时堆分配**：热路径改用原始 `lane` 与对象引用，避免每次下单改单触发堆对象分配与垃圾回收压力。

### 2.2 撤单与终态释放链路消除重复查表与临时包装（`TradingAccountRuntime`, `LaneCancelEvent`, `MatcherSettlementEvent`, `RuntimeSpotMatchProcessor`, `RuntimeDerivativeMatchProcessor`）
- **撤单热路径对象直通**：
  - 重构 `cancelOrderInLane` / `releaseOrderToTerminalInLane`，支持直接传入外层已查得的 `OrderRuntime` 引用，消除 `LaneCancelEvent.execute` 与 `MatcherSettlementEvent.applyPlan` 中每次撤单在底层重复执行的 `lane.order(orderId)` 哈希查找；
  - `LaneCancelEvent` 预留默认复用数组容量（8槽），避免单笔撤单与小批次撤单交替复用时频繁扩缩容分配；
- **终态预留释放零重查**：
  - 重构 `releaseTerminalReservationInLane`，提供接收已解析 `OrderRuntime` 与 `ReservationRuntime` 的直通重载；
  - 在 `RuntimeSpotMatchProcessor` 与 `RuntimeDerivativeMatchProcessor` 中直接复用当前游标或外层已持有的订单/预留引用，并直接消费方法返回的 `releaseUnits`，消除了每次终态释放前外部与内部重复进行的 2~3 次 `lane.order` / `lane.reservation` 查找；
- **`requireOpen` 状态校验零重查**：
  - 为 `RuntimeSpotMatchProcessor` 和 `RuntimeDerivativeMatchProcessor` 提供 `requireOpen(OrderRuntime order, long orderId)` 重载，对已查出的对手单 `maker` 直接校验开放状态，省去二次查表；
- **收敛 `cancelOrder` 5 层套娃与删除 `CanceledOrder`**：
  - 将 `TradingAccountRuntime.cancelOrder` 的 5 层透传重载收敛为直白的 2 层结构，原位发布订单与预留变更，彻底删除临时包装 `record CanceledOrder`，消灭撤单路径上的临时对象分配。

### 2.3 批次提交变更集原语直通与零装箱（`PrimitiveLongChangeSet`, `TradingAccountRuntime`, `LaneCommitEvent`）
- **`PrimitiveLongChangeSet` 实现 `PrimitiveLongView`**：暴露 `primitiveSize()`、`primitiveValueAt(int index)` 与 `forEachLong(LongConsumer)` 原语访问接口；
- **消除用户与订单变更集装箱**：
  - `TradingAccountRuntime.addLaneUsers` 与 `LaneCommitEvent.prepareMetadata` 增加 `PrimitiveLongView` 原语快速通道，直接按原语下标遍历并预分配 `LongArrayList` 容量，彻底消除了批量订单提交时成百上千次 `Long` 自动装箱与 `Iterator` 对象分配。

### 2.4 现货撮合累加器 `SpotBalanceState` 池化复用（`RuntimeSpotMatchProcessor`）
- **补齐对象池闭环**：在 `SpotSettlementAccumulator` 中新增 `freeBalanceStates` 池，`SpotBalanceState` 改为支持 `reset(userId, assetId, current)` 复用，并在 `clear()` 时归还池中，彻底消除多笔现货成交累加时的临时对象分配。

### 2.5 批处理内存与槽位统一（`OrderBatchPending` & `PlaceBatchAdmissionEvent`）
- **剔除冗余并行数组**：删除了独立的 `resolvedOrderSlots` 数组字段，`resolvedOrderSlot(index)` 直接复用 `preparedOrders[index]` 槽位，单批次内存分配减少 1 个对象数组；
- **拍平批次清空循环**：`clear()` 方法从双重循环与全数组清空收敛为单次前缀 `Arrays.fill(preparedOrders, 0, preparedCount, null)`。

### 2.6 风控游标 $O(1)$ 常数时间直通与系统账本契约治理（`TradingAccountRuntime` & `RuntimeLiquidationResolution`）
- **单持仓场景极速直通**：在 `nextRiskPositionKey` 与 `nextRiskReservationId` 中增加单元素特化分支，常数时间直通返回；
- **系统账本生命周期与控制命令保护**：严格管理 `systemLedgerStarted` 状态字段，保持未提交控制命令排他性断言与强平决议异常拦截。

---

## 3. 全量回归与验证结果
- **JDK 版本**：OpenJDK 27 (HotSpot)
- **编译状态**：生产源码文件与测试源码文件编译 100% 成功；
- **全量单元测试套件**：**1591 个用例全部通过，0 Errors, 0 Failures**。
