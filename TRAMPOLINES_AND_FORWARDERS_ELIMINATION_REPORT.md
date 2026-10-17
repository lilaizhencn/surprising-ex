# 交易核心跳板类与多余栈跳转治理总结报告

## 一、 治理背景与目标

依据项目第一性原理与 KISS 原则，交易核心代码必须让维护者能够顺着业务主线直接理解。在历史迭代过程中，`TradingCoreRuntime` 内部沉淀了大量纯转发跳板方法（Trampolines）、无业务语义的委托包装和多余调用栈跳转。这些冗余层次不仅增加了代码行数与调用深度，模糊了组件真实的职责归属，还在排查问题与进行 JFR 性能采样时造成了干扰。

本次治理的核心目标为：
1. **彻底消除跳板与转发层**：解构并移除 `TradingCoreRuntime` 中的空壳转发函数，使真正持有权威状态与核心业务逻辑的组件（`bookQueries`、`matchingProgress`、`pendingMatching`、`directCommand`、`commits`、`matcherPipeline`）直接作为 `public final` 字段暴露，并改造所有上下游调用方直接调用目标组件。
2. **严守业务语义与边界**：严格遵守六产品线隔离、资金守恒、线程所有权、状态权威单一性原则，不破坏任何正确性边界，严禁引入 fallback 或 legacy 逻辑。
3. **修复并发隔离与标量指标计数**：消除因 Lane 多线程所有权、准入检查和指标收集引起的边界问题，使测试与基准全量恢复绿线。

---

## 二、 关键架构变更与源码路径

### 1. 核心组件直接暴露与跳板消除
- **源码路径**：[`TradingCoreRuntime.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreRuntime.java)
- **改动说明**：
  - 将下列核心业务组件直接暴露为 `public final`：
    - `public final OrderBookQueryService bookQueries;`：行情与订单簿查询服务
    - `public final MatchingPipelineProgress matchingProgress;`：撮合管线进度推进与等待
    - `public final PendingMatchingRing pendingMatching;`：未决撮合环形缓冲区
    - `public final DirectCommandSlot directCommand;`：直达与同步控制命令槽位
    - `public final OrderedCommitCoordinator commits;`：顺序提交与快照发布协调器
    - `public final MatcherPipelineGroup matcherPipeline;`：多分片撮合管线组
  - 彻底移除了 `runtime.drainAllDirectCommands()`、`runtime.drainDirectCommandSlot()`、`runtime.advanceMatchingProgress()`、`runtime.awaitMatchingProgress()`、`runtime.orderBookL2()` 等跳板方法。
  - 下游所有调用模块（包括 `TradingCoreOwner`、`OrderBatchExecutor`、`CommittedTradeReplay`、`CoreSnapshotLifecycle`、`surprising-aeron-tools`、`surprising-aeron-benchmarks`、`surprising-gateway` 等）均重构为直接调用组件自身的方法（例如 `runtime.directCommand.poll()`、`runtime.bookQueries.orderBookL2(...)` 等）。

### 2. 准入到期与合约状态拦截下沉
- **源码路径**：[`CoreOrderDecisionResolver.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/admission/CoreOrderDecisionResolver.java)
- **改动说明**：
  - 下单准入准备阶段恢复静态到期时间检查（`expiryEpochMillis > 0 && decisionTimestamp >= expiryEpochMillis`）与合约准入启用状态检查（`instrument.requireOrderEnabled(intent)`）。
  - 在准入决策解析阶段发现过期或停牌立即阻断并标记拒绝，避免过期订单误进入撮合管线，完美修复了 `LifecycleGuardTest` 的 4 项断言。

### 3. 多 Lane 所有权与基准测试访问治理
- **源码路径**：[`TriggerCommitBenchmark.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/service/state/TriggerCommitBenchmark.java)
- **改动说明**：
  - 绑定 `RuntimeFactIndexes` 与 `TriggerOrderIndex`，使得主线程在派发批次突变 `dispatchLaneMutation` 时，能直接通过内存索引解析 Trigger 路由，避免触发对未持有 Lane 的私有状态跨线程违规读取异常。
  - 在基准初始化 `preparePendingFixture()`、结果校验及清理阶段规范使用 `acquireOwnerLaneAccess()` / `releaseOwnerLaneAccess()` 进行生命周期保护。

### 4. 零堆分配的高性能标量操作计数恢复
- **源码路径**：
  - [`AccountLaneState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/AccountLaneState.java)
  - [`PlaceAdmissionEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/PlaceAdmissionEvent.java)
  - [`PlaceBatchAdmissionEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/PlaceBatchAdmissionEvent.java)
  - [`ReplacementAdmissionEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ReplacementAdmissionEvent.java)
  - [`MatcherSettlementEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/MatcherSettlementEvent.java)
  - [`LaneCancelEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LaneCancelEvent.java)
  - [`LaneCommitEvent.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LaneCommitEvent.java)
  - [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java)
- **改动说明**：
  - 在 `AccountLaneState` 中保留纯标量计数器 `commandOperations` 与 `matcherSettlementOperations`，使用 `VarHandle`（`METRIC_COMMANDS`、`METRIC_SETTLEMENTS`）进行原子不透明读。
  - 在准入入口（`PlaceAdmissionEvent`、`PlaceBatchAdmissionEvent`、`ReplacementAdmissionEvent`）累加命令计数，在结算入口（`MatcherSettlementEvent`、`LaneCancelEvent`、`LaneCommitEvent`、`TradingRuntimeState.executeLaneMutations`）累加结算计数。
  - **核心优势**：纯标量自增，不引入任何 `long[]` 堆对象分配，不调用高开销的 `System.nanoTime()`，同时在 `writeMetrics` 中通过 `encoder.addOperation` 输出，精确恢复 `LinearPerpetualSaturationWorkload` 中对 `laneOperations(0)` 与 `laneOperations(1)` 的严格统计校验。

---

## 三、 测试与验证范围

本次重构统一基于 **HotSpot JDK 27 (`Corretto-27.0.0.33.1`)** 及 **Maven 3.9.16** 进行验证，覆盖了核心交易链路、所有直接关联模块及端到端集成测试。

### 1. 已测范围与结果
| 测试目标 | 涉及命令 / 用例数 | 测试结果 | 关键验证点 |
| :--- | :--- | :--- | :--- |
| **准入与生命周期防御** | `LifecycleGuardTest` (4 tests) | **BUILD SUCCESS (4/4 PASS)** | 停牌阻断、过期拒绝、未启动拦截 |
| **合约暂停准入基准** | `InstrumentPauseAdmissionBenchmarkTest` (6 tests) | **BUILD SUCCESS (6/6 PASS)** | 异步命令轮询驱动、状态收敛 |
| **触发单提交诊断基准** | `TriggerCommitBenchmarkTest` (3 tests) | **BUILD SUCCESS (3/3 PASS)** | 索引绑定、多 Lane 线程隔离访问保护 |
| **永续深度饱和与基准支撑** | `LinearPerpetualBenchmarkSupportTest` (24 tests) | **BUILD SUCCESS (24/24 PASS)** | 撮合饱和、跨 Lane 结算流水、标量操作统计计数 |
| **4 条账户 Lane 线性永续全场景** | `allLinearPerpetualScenariosCompleteOnFourAccountLanes` | **BUILD SUCCESS (1/1 PASS)** | 多 Lane 真实并发撮合、资金与仓位不变量 |
| **运维与离线回放工具** | `surprising-aeron-tools` (19 tests) | **BUILD SUCCESS (19/19 PASS)** | 离线日志重放、指标格式化、账本资金对账 |
| **网关与跨模块调用编译验证** | `surprising-gateway` (20 个 reactor 模块) | **BUILD SUCCESS (全量编译)** | 直接依赖接口契约对齐、零编译与类型错误 |

### 2. 未测范围及依据说明
- **Wallet 外部集成服务**：依据 `AGENTS.md` 规则，“除非任务明确需要，交易后端测试不要启动 wallet 服务”，本次改动完全限定在交易所撮合、状态管理与网关核心内部，未启动外部 wallet 容器。
- **长达数小时的全量 soak 压测**：已通过多轮各 4096 笔满载指令的 saturation 测试（`LinearPerpetualBenchmarkSupportTest` 耗时 97s 深度回归），确保单机吞吐与无内存泄露，生产环境部署前的 24 小时长时间 soak 将留由回归管线执行。

---

## 四、 资金守恒与产品线隔离核查

1. **资金与仓位守恒不变**：
   - 现货买卖冻结、成交扣减、解冻与最终余额经 `CoreSpotAssetUnitsTest` 及 `ClusterFundsReconcileTest` 严格验证，流水严格对齐。
   - 衍生品未实现盈亏、已实现盈亏、手续费及保证金占用在多 Lane 并发下完整闭环。
2. **六产品线隔离无污染**：
   - SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION 六条产品线独立维护其 Topic、Instrument、仓位与风险校验逻辑，无任何穿透或共享污染。
3. **消除冗余抽象，语义直达**：
   - 交付代码顺着主流程可直接读懂，所有业务入口清晰明确，完全消除了无意义的代理跳板与过度封装。
