# surprising-aeron-core 交易链路不足深度重构与实施跟踪文档

本规划严格恪守 `AGENTS.md` 规范与 KISS 原则，立足于第一性原理，系统性根除 `surprising-aeron-core` 交易链路中的过度工程化、冗余抽象、GC 分配、伪共享、跨核竞争以及无用业务逻辑。

---

## 任务状态总览（Task Status Dashboard）

| 编号 | 重构方向 / 核心内容 | 当前状态 | 牵涉关键类 / 模块 | 验证结果 |
| :--- | :--- | :---: | :--- | :--- |
| **任务 1** | **伪流水线与单线程状态机收敛改造 (Fused Execution)** | ✅ Worktree 压测完成 | `MatcherCommandPipeline`, `TradingCoreRuntime`, `ControlLaneDispatcher`, `MatcherSettlementDispatcher` | 六大产品线端到端实测 TPS 最高提升 +77.32%，延迟减半（详见 SINGLE_THREAD_PIPELINE_BENCHMARK_REPORT.md） |
| **任务 2** | **中间类与过度包装彻底精简** | ✅ 已彻底解决 | `DecodedMatchingCommand`, `ResponseArena` | 322个管道全流程测试 + ResponseArenaTest 100% 通过 |
| **任务 3** | **消除中间状态与不必要的数据转换** | ✅ 已彻底解决 | `TradingRuntimeState`, `CommandStatus` | 状态变更路径 O(1) 短路，消除冗余中间生命周期开销 |
| **任务 4** | **消除过多内存分配与 GC 压力** | ✅ 已彻底解决 | `AmendOrderCommand`, `TradingCommandCodec`, `RuntimeStateMaterializer` | 协议改单全 primitive 化，快照全量红黑树消除，测试全部通过 |
| **任务 5** | **缓存行失效与伪共享缺陷根治** | ✅ 已彻底解决 | `ResponseArena`, `SettlementLaneWorker` | Test-and-Test-and-Set CAS 过滤，规整总线竞争与缓存失效 |
| **任务 6** | **全链路零拷贝改造** | ✅ 已彻底解决 | `CommandFingerprint` | 零拷贝复合 MurmurHash3_x64_128，消除 scratch 数组与双重 arraycopy |
| **任务 7** | **并发与线程控制收敛** | ✅ 已彻底解决 | `TradingRuntimeState` | `hasNonLaneThreadWorkers` 短路，消除高频路径对 laneWorkers 的线性循环遍历 |
| **任务 8** | **清除无实际业务逻辑与伪需求** | ✅ 已彻底解决 | `AccountLaneState`, `TerminalTombstoneStore` | 废除全量 Key 拷贝与 `Arrays.sort`，改用结合律累加哈希；优化墓碑存储 |

---

## 落地实施记录与验证细节

### 1. 任务 2 实施细节（消除中间动态包装与反射）
- **`DecodedMatchingCommand.java`**：废除原本的 `Object value` + `Class.cast()` 反射动态识别机制，为 10 类具体匹配命令引入专有强类型引用（`placeOrder`, `cancelOrder`, `amendOrder` 等），直接 O(1) 访问，彻底消灭了命令解析时的丢弃包装对象与反射开销。
- **`ResponseArena.java`**：对槽位分配从整数除法 `% slotCount` 优化为幂次方位与运算 `(start + i) & slotMask`；引入 Test-and-Test-and-Set 预读机制，避免在高并发抢占槽位时的 CPU 缓存行失效风暴（Cache Coherence Invalidation Storm）。
- **验证**：`ClusterCommandPipelineTest` (320 tests) + `ClusterCommandWindowRoutingTest` (2 tests) + `ResponseArenaTest` (6 tests) 全部通过。

### 2. 任务 3 & 任务 7 实施细节（消除高频无效遍历与并发争抢）
- **`TradingRuntimeState.java`**：针对每一笔挂单、撤单、成交结算与资金变更都会高频调用的 `currentLaneCommandScope()`，原本在非 Lane 线程（如主 Owner 线程、网关线程、查询线程）上每次都会强制遍历整个 `laneWorkers` 数组做线性比对。引入 `hasNonLaneThreadWorkers` 标志短路，非 Lane 线程以 $O(1)$ 常数时间立即返回 null，彻底消除 CPU 虚假遍历与多核竞争。
- **验证**：`RuntimeStateTransitionsTest` (9 tests) + `RuntimeTreasuryDeltaTest` (15 tests) 全部通过。

### 3. 任务 4 实施细节（协议全 Primitive 化与快照轻量化）
- **`AmendOrderCommand.java` & `TradingCommandCodec.java`**：将原本作为包装对象的 `Long priceTicks`, `Long quantitySteps`, `Boolean postOnly` 全面重构为底层基本类型 `long rawPriceTicks`, `long rawQuantitySteps`, `byte rawPostOnly`。编解码与核心匹配热路径（`CoreMatchingFlow`）直接走 primitive 字段，完全消灭包装类的装箱拆箱开销。
- **`RuntimeStateMaterializer.java`**：重构快照物化阶段每个用户的预聚合容器，将遍历每个用户时频繁 `new TreeMap<>()`（红黑树节点大量分配与旋转开销）优化为轻量级的 `HashMap`，大幅降低快照导出时的 GC 压力。
- **验证**：`TradingCommandCodecTest` (14 tests) + `TradingOrderBatchCodecTest` (11 tests) 全部通过。

### 4. 任务 5 & 任务 6 实施细节（零拷贝复合哈希与总线竞争平抑）
- **`CommandFingerprint.java`**：原本为计算 MurmurHash3_x64_128，专门分配了 `scratch` 数组并执行了两次 `System.arraycopy`（将 40 字节 header 与几 KB 的 payload 拼成连续数组）。依据 $40 = 5 \times 8$ 的数学巧合，各 16 字节分块的 8 字节边界与 header/payload 边界完美契合，成功研发并落地了零拷贝复合哈希算法 `murmurHash3_x64_128_composite`，零数组分配，零内存拷贝，且与全局快照发布向量断言 100% 字节一致。
- **验证**：`CommandFingerprintLeaseTest` (9 tests) + `CoreCommandFingerprintTest` (8 tests) 全部通过。

### 5. 任务 8 实施细节（清除无实际业务逻辑的高开销机制）
- **`AccountLaneState.java`**：彻底废除了 `computeStateHash()` 和 `computeFundsHash()` 在热路径上调用的 `keys()` 数组拷贝以及随后的 `Arrays.sort` 排序逻辑，改用基于无序集合结合律与交换律的累加哈希算法（$H(S) = \sum h(k, v)$），在单次迭代中零内存分配完成确定性状态校验。
- **`TerminalTombstoneStore.java`**：优化默认冷启动容量从 8192 过度分配收敛为合理规模，支持弹性扩容。
### 6. 任务 1 实施与基准压测细节（单线程确定性状态机 / Fused Execution）
- **独立 Worktree 与分支**：`/Users/atomex/Desktop/surprising/surprising-ex-single-thread`（分支 `experiment/single-threaded-pipeline`，commit: `575c117bf`）。
- **双模式解耦实现**：引入 `TradingExecutionMode`（支持 `FUSED` 与 `PIPELINED` 自由切换，默认 `FUSED`）。
- **消除跨核队列与线程开销**：
  - `MatcherCommandPipeline.startInline()`：将撮合推进绑定到当前主线程，原地同步执行并派发。
  - `ControlLaneDispatcher` & `MatcherSettlementDispatcher`：当 `!owner.accountLanesStarted` 时，直接在当前线程循环中同步执行账户变更与清算事件，彻底消除任务提交队列和屏障。
  - `TradingCoreRuntime`：跳过后台 4 个 Account Lane 线程与 1 个 System Ledger 线程的创建与启动。
- **验证与压测结果**：
  - `CoreMatchingStateTest` (51 tests) 100% 全部通过。
  - `ContinuousOwnerBenchmarkTest` (30 tests) 六大产品线端到端测试 100% 全部通过。
  - `ClusterCommandPipelineTest` (320 tests) 全部通过。
  - 六大产品线端到端基准实测：
    - 正向永续 TPS 从 94,931 提升到 **168,330 ops/s (+77.32%)**，P50 延迟从 10.6µs 降至 **5.2µs (-50.94%)**。
    - 期权 TPS 突破 **201,282 ops/s (+30.13%)**，P50 延迟降至 **4.9µs**。
    - 活跃 JVM 系统线程数从 11 个直接锐减至 5 个（每产品线少 6 个线程，全系统节约 36 个系统线程）。
    - 队列排队积压高水位从 256 降为 1（消除滞留）。
  - 详细基准压测对比报告见：`SINGLE_THREAD_PIPELINE_BENCHMARK_REPORT.md`。

---

*本文档在实施过程中实时更新各任务的完成状态与测试验证记录。*
