# 交易链路主代码非业务侵入（指标/采样/JFR/测试后门）清理总结

## 1. 清理背景与目标
在先前的性能排查中，交易链路核心热路径（Owner 循环、Matcher 撮合流水线、SettlementLaneWorker 结算线程、OrderedCommitCoordinator 提交协调器）存在大量诊断性与阶段耗时采样（如 JFR 埋点、`WAIT_DIAGNOSTICS`、`LATENCY_DIAGNOSTICS`、`CoreMatchingPhaseMetrics`、纳秒打点、阶段计时器及无谓的 try-finally 包裹）。这些代码不仅造成隐式对象分配、时钟读取开销与缓存行失效，还严重破坏了业务主流程的代码可读性。

本轮按照 KISS 原则、业务语义优先原则与 AGENTS.md 约束，对主代码（`src/main/java`）中与生产业务无关的代码进行了彻底剔除与纯粹化重构。

---

## 2. 核心清理与重构清单

### 2.1 彻底删除阶段性 Metrics 采样与对应测试
- **已删除主代码类**：`CoreMatchingPhaseMetrics.java`
- **已删除对应测试类**：`CoreMatchingPhaseMetricsTest.java`
- **清理影响**：完全移除了在撮合准入（`MatchingCommandAdmission`）、所有者循环中对阶段耗时统计数组的构造、纳秒计时与累加调用。

### 2.2 彻底清理事件载荷上的 JFR 与纳秒诊断埋点
- **`LaneCommitEvent.java`**：
  - 彻底移除了 `LATENCY_DIAGNOSTICS` 开关常量；
  - 移除了 JFR 飞行记录器事件 `LaneCommitLatency` 及其字段与 commit 提交逻辑；
  - 移除了 `dispatchedNanos`、`ownerObservedNanos`、`recordDispatched()`、`recordOwnerObserved()` 等非业务诊断时间戳字段与方法。
- **`MatcherSettlementEvent.java`**：
  - 彻底移除了 `LATENCY_DIAGNOSTICS` 开关常量；
  - 移除了 `matcherPublishedNanos`、`laneStartedNanos`、`laneFinishedNanos` 等事件级时间戳字段及相关存取方法。

### 2.3 彻底消除流水线与 Worker 等待诊断
- **`MatcherCommandPipeline.java`**：
  - 彻底删除了 `WAIT_DIAGNOSTICS` 常量；
  - 移除了 `cumulativeWaitNanos`、`minWaitNanos`、`maxWaitNanos`、`waitSamples` 等全部诊断字段与采样逻辑，恢复纯净的自旋与等待。
- **`SettlementLaneWorker.java`**：
  - 彻底删除了 `WAIT_DIAGNOSTICS` 常量；
  - 移除了 Worker 循环内的所有非业务耗时统计与采样分支。

### 2.4 重构 Owner 调度循环，恢复主流程直接可读性
- **`TradingCoreOwner.java`**：
  - 彻底删除了 `sampleOwnerTurn`、`turn` 变量及相关循环耗时打点；
  - 移除了调度循环外层的无谓 `try ... finally` 采样包裹，使 Owner 调度主循环恢复为直观顺畅的业务状态流转；
  - 同步清理 `TradingOwnerLoop.java`、`OrderedCommitCoordinator.java`、`CommandSlot.java`、`ClusterCommandWindow.java`、`ClusterServiceEgress.java`、`CommitPublication.java`、`OrderBatchExecutor.java` 等协作类中的冗余度量传参与字段。

### 2.5 控制命令契约规范与致命分歧 fail-fast
- **`TradingCoreRuntime.java`**：
  - 规范了直接控制命令（如 `PROBE_INCREMENT`、`ADJUST_BALANCE`）在同步 `apply(...)` 入口下的等待与终态收敛契约（当处于异步 Worker 模式时，循环等待直至 Worker 完成并调用 `pollDirectCommand()` 收敛状态），彻底根治了测试直接调用时结果为 null 以及 `unfinished business work outside cluster log callback direct=true` 状态挂起泄漏问题。
- **`OrderedCommitCoordinator.java`**：
  - 将致命撮合分歧判定（`matchingResultNeedsRecovery`，如 `EXCHANGE_CORE_FAILURE`）提前至 `completeMatching` 入口最前面 fail-fast 执行，保证在撮合分歧时立即抛出 `FatalMatchingDivergenceException` 中断，避免被后续状态就绪门禁拦截。

---

## 3. 验证结果
- **Java 环境**：HotSpot JDK 27（`27.0.0-amzn`）
- **核心单测验证**：
  - `TradingCoreOwnerTest`：**45 项测试全部通过（45/45，0 Failures，0 Errors）**；
  - `ContinuousOwnerBenchmarkTest`：**30 项测试全部通过（30/30，0 Failures，0 Errors）**，覆盖全产品线：`SPOT`、`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION`。
- **代码规范检查**：
  - `git diff --check`：100% 通过，无多余空白或格式问题；
  - 严禁且未引入任何 `ThreadLocal` 状态；
  - 严格保持在 `master` 分支提交。
