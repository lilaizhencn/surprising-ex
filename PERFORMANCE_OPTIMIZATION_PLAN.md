# 架构重构与性能极限优化方案：消除锁竞争、减负Owner与彻底清除ThreadLocal

## 一、目标与约束
1. **消除锁竞争 (Zero Lock Contention)**：
   - 彻底删除 `AccountLaneState` 中的全部 `synchronized` 关键字；
   - 严格遵循 Shared-Nothing 架构：每个 `AccountLaneState` 的状态（`reservations`, `orders`, `pendingReservationSequences`, `positions` 等）仅由其绑定的 `SettlementLaneWorker` 独占读写；
   - Owner 线程绝不允许跨线程直接读取或遍历 `accountLanes` 的内部集合。
2. **减少 Owner 职责 (Owner Minimization)**：
   - 根除 `TradingRuntimeState` 中 7 处 `for (int i = 0; i < accountLanes.length; i++)` 全 Lane 线性扫描；
   - Owner 校验/查询订单和路由时，仅查询自身独占的 `activeOrderIndex`；
   - 统计聚合数据（如 `hasPublishedInstrumentExposure`, `publishedPositionCount`）由 Lane 异步上报或事件驱动维护，或者在必要时由各 LaneWorker 本地处理，禁止 Owner 阻塞式跨线程全扫描。
3. **彻底清除 ThreadLocal (Zero ThreadLocal)**：
   - 根除全部 14 处 `ThreadLocal`：
     1. `CoreMessageFlyweightDecoder.java`: `READER_HOLDER` -> 解码器实例私有对象或栈传递
     2. `DeterministicExchangeCoreAdapter.java`: `commandScope` -> 适配器实例私有对象
     3. `TradingRuntimeState.java`: `LEVERAGE_LOOKUP` -> 实例私有对象
     4. `TradingRuntimeState.java`: `laneCommandScope` -> 显式方法栈传递
     5. `TradingRuntimeState.java`: `matcherSettlementChangesScope` -> 显式方法栈传递
     6. `RuntimeDerivativeMatchProcessor.java`: `ACCUMULATOR` -> 实例私有 scratch 缓存
     7. `RuntimeIdentityRegistry.java`: `POSITION_LOOKUP`, `CLIENT_LOOKUP`, `POSITION_MAP_LOOKUP` -> 实例私有 scratch 缓存
     8. `RiskLaneProcessor.java`: `POSITION_RISK` -> 实例私有 scratch 缓存
     9. `ActiveOrderIndex.java`: `RECYCLED_ORDERS` -> 实例私有 ArrayDeque 缓存
     10. `MatcherSettlementPlan.java`: `DIRECT_ORDER_KEYS` -> 实例私有 scratch 缓存
     11. `RuntimeDerivativeFillCalculator.java`: `SINGLE`, `TAKER` -> 实例私有 FillCursor 缓存
     12. `RuntimeSpotMatchProcessor.java`: `ACCUMULATOR` -> 实例私有 scratch 缓存
4. **回归与压测验证**：
   - 单元测试与集成测试 100% 通过；
   - 单节点 Aeron 压测脚本验证无死锁、无异常、吞吐稳定。

## 二、任务分解与执行顺序
- [ ] 任务 1：全方位分析 `AccountLaneState` 与 `TradingRuntimeState` 中跨线程访问与锁的根因
- [ ] 任务 2：重构 `TradingRuntimeState`，移除 7 处全 Lane 扫描循环，Owner 仅使用不可变路由索引
- [ ] 任务 3：彻底移除 `AccountLaneState` 中的所有 `synchronized` 监视器锁
- [ ] 任务 4：全局清除 14 处 `ThreadLocal`，改造为显式实例字段或参数传递
- [ ] 任务 5：运行测试套件，修复编译与单元测试问题
- [ ] 任务 6：执行 `qualify-aeron-async-stages.sh` 真实单节点压测并验证
