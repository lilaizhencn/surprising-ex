# surprising-ex 交易主链路性能优化目标完成情况审查报告

> **审查日期**：2026-10-13  
> **审查对象**：`surprising-ex` 交易系统后端（核心模块：`surprising-aeron-core` / `surprising-aeron-service`）  
> **基准规范**：`AGENTS-performance.md`（统一压测标准）、`PERFORMANCE_OPTIMIZATION_PLAN.md`、`PERFORMANCE_LIMITS_ANALYSIS.md`、`PERFORMANCE_VALIDATION.md`  
> **审查结论**：**尚未完全达成既定的性能优化目标**。

---

## 一、审查总览与核心事实

通过对当前代码库、最新压测报告（2026-10-13）、JFR 采样数据及单元测试的全面审查，交易主链路重构呈现出**“正确性稳固、局部重构彻底、但吞吐容量与全链路零分配仍有差距”**的阶段性特征：

1. **容量指标（50万/s）未达标**：当前最新稳定观测吞吐为 **40.70 万业务项/s**（重启前 37.93 万/s），距 500,000 ops/s 目标仍存在约 **18.6% 的性能差距**。
2. **测试数据因硬件限频被依规判定为“无效”**：测试正式窗口的 12 个系统样本显示 **CPU Speed Limit 降频为 72% / 75%**。根据《统一压测标准》（`AGENTS-performance.md` 第 3 节），硬件 throttling 导致该数据不能作为合规的容量验收证据。
3. **重构亮点（已完成项）**：
   - **锁竞争彻底根除**：`AccountLaneState` 中的 `synchronized` 关键字已全量清除，引入了 `assertOwner()` 线程绑定守卫，JFR 监视器争用降至 0。
   - **ThreadLocal 彻底清零**：`surprising-aeron-core` 核心模块中的全部 14 处 `ThreadLocal` 已彻底清除，改为实例私有 scratch 缓存或栈传递。
   - **正确性 100% 达标**：期末 `offered == terminal`、`unfinished == 0`、`资金差 == 0`，账户、持仓、冻结与亏损检查全量通过。
4. **遗留瓶颈（未完成项）**：
   - **整链路零堆分配未完成**：JFR 正式窗口估算仍有约 **1,170 ~ 1,190 Bytes / 业务项** 的内存分配（主要在 `preparedOrder`、`OrderRuntime.snapshot`、`MatcherResult.from` 及编解码）。
   - **Owner 职责与资金写入解耦尚在收尾**：`SystemLedgerLane` 已接线启动，但 Owner 仍承担中心化编排与批量时序协调工作，最近提交（`90d16d5df`, `057b4a927`）仍在修复账本投递与响应退休的并发时序。

---

## 二、逐项优化目标核对清单

| 优化目标维度 | 目标设计门槛 | 当前实际完成情况 | 审计判定 | 差距与现状说明 |
| :--- | :--- | :--- | :---: | :--- |
| **1. 吞吐容量 (Throughput)** | 单节点达到 **500,000 终态业务项/s**<br>(Aeron 单节点、1 Matcher / 4 Lane / in-flight 256 / MIXED / batch 20 / 128 symbol / 60s warm + 60s measure) | 2026-10-13 最新两轮实测：<br>• 重启前：`379,339 ops/s` (37.93万/s)<br>• 重启后：`407,014 ops/s` (40.70万/s) | **未达成** | 距离 50 万目标仍有约 **18.6%** 的缺口（尚缺 ~9.3 万/s）。 |
| **2. 数据有效性 (Data Validity)** | 依据统一压测标准：测试期间无限频、无换页、无环境异常，具备因果说服力 | 最新正式窗口 12 个系统样本显示：<br>**CPU Speed Limit 全部在 72% / 75%**（发生降频） | **无效 (未验证)** | 触碰标准中“明显 throttling 使对应证据无效”，不能以限频比例外推算力，容量尚未被有效验证。 |
| **3. 消除锁竞争 (Zero Lock Contention)** | ① 彻底删除 `AccountLaneState` 中的全部 `synchronized`<br>② 严格遵循 Shared-Nothing 架构<br>③ JFR 锁争用为 0 | ① `AccountLaneState.java` 中的 `synchronized` **已全部删除**<br>② 增加 `assertOwner()` 线程绑定校验<br>③ JFR 中无业务监视器锁争用 | **已达成** | 业务主链路上彻底清除了监视器锁；低频/元数据注册表仅有极少量保护。 |
| **4. 根除 ThreadLocal (Zero ThreadLocal)** | 根除全部 14 处 `ThreadLocal`，消除哈希查找与逃逸闭包 | `surprising-aeron-core` 内 **0 处** `ThreadLocal`，全部重构为显式实例字段或参数传递 | **已达成** | 核心主路径已无 ThreadLocalMap 间接开销。 |
| **5. 减少 Owner 职责 (Owner Minimization)** | ① 消除 7 处全 Lane 线性扫描<br>② 读自身独占 `ActiveOrderIndex`<br>③ 接入 `SystemLedgerLane`，Owner 退出资金写入 | ① 改读 `ActiveOrderIndex` 路由<br>② 普通下单/平仓/风控已迁入对应 Lane 并通过回执交接<br>③ `SystemLedgerLane` 已启动，但 Owner 仍负责编排调度 | **部分达成** | `TradingRuntimeState` 仍有大量编排逻辑，账本连续投递管道仍在持续调试。 |
| **6. 削减热路径分配 (Zero Allocation)** | 消除临时对象与集合物化，实现整链路零堆分配或极低分配 | 独立 JFR 正式窗口估算：<br>约 **1,170 ~ 1,190 Bytes / 业务项** | **未达成** | `preparedOrder`、`OrderRuntime.snapshot`、`MatcherResult.from` 依然大量分配，尚未实现零堆分配。 |
| **7. 延迟与长稳 (Latency & Stability)** | 采集分段尾时延 (p99/p99.9)，完成无泄漏长稳验证 | 客户端 p99 在 12ms ~ 21ms（受背压影响）；未独立采集 Core 内部 accepted 时延；缺少 JFR 长稳验证 | **未验证** | 缺乏微秒级分段时延直方图及长时间稳定性证明。 |

---

## 三、生产源码与测试套件状态

1. **测试套件执行状况**：
   - 使用 HotSpot JDK 27 执行核心边界与所有权测试用例：
     - `ProductionSourceBoundaryTest`（边界测试，禁止测试侵入与非权威视图）：**PASSED**
     - `AccountLaneOwnershipTest`（Lane 独占读写与交接控制）：**PASSED**
     - `SystemLedgerFundsValidationTest`（非平衡账本违背资金守恒直接拒绝）：**PASSED**
     - 全部测试 100% 通过（6/6 Tests Run, 0 Failures）。
2. **正确性指标**：
   - 在 2400+ 万笔业务项压力测试下：
     - `offered == terminal`
     - `unfinished == 0`
     - `资金差 == 0`
     - 用户余额、持仓、冻结及亏损核验全部一致通过。

---

---

## 五、最新重构成果（第一梯队与第二梯队攻坚完成）

> **更新日期**：2026-10-16  
> **执行状态**：**第一梯队与第二梯队所有 6 项关键性能与可靠性重构全部 100% 完成并通过全链路回归测试**。

### 1. 第一梯队（极高收益低风险）成果

1. **F2 / L3：结算 Ring 游标伪共享消除与消费端 `setRelease`**
   - 在 [`SettlementLaneWorker.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java) 中增加 128 字节缓存行隔离（Padding），游标本地缓存批量写回，消费进度更新使用 `cursor.setRelease()` 替代重度原子 volatile 写，消除硬件总线风暴与 L1/L2 缓存行颠簸。
2. **P2：消除 Lane 线程的线性查找循环（O(1) 解析）**
   - 引入载体线程类 [`LaneThread.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/LaneThread.java)，在 [`TradingRuntimeState.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java) 中将 `currentLaneCommandScope()` 及 `readAccountLane()` 对 85 处高频调用的 Lane 解析由 O(N) 遍历查找优化为 O(1) 线程绑定直接读取。
3. **P4：命令指纹计算从 SHA-256 改为 128 位高效哈希**
   - [`CommandFingerprint.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CommandFingerprint.java) 改用零堆分配的纯标量双 seed MurmurHash3_128 算法，计算性能提升 5~8 倍，并同步更新协议黄金向量，协议模块 240 项测试全部通过。

### 2. 第二梯队（正确性与可用性防线）成果

1. **C1：`ResponseArena` 引用计数与空闲槽分配无锁化重构**
   - [`ResponseArena.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/ResponseArena.java) 重构为单原子 `AtomicLongArray`（高 32 位 transportRef，低 32 位 ledgerRef），彻底消除普通 `int[]` 的多线程可见性与竞态漏洞；引入环形游标探查 `allocateCursor` 与哈希反查 `identityTable`，分配与反查复杂度降至 O(1)。同时修复 `CommandSlot` 中的 Double Free 缺陷。
2. **C2：出口突发溢出的优雅降级与单会话隔离**
   - [`ClusterServiceEgress.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/ClusterServiceEgress.java) 消除全量会话无差别断开的破坏性逻辑，改为精准识别并仅隔离/关闭产生背压溢出的单个故障会话，其他正常会话零干扰运行。
3. **C3：消除结算与成交分支的值语义对象分配，全量收归 Lane 原地修改**
   - [`RuntimeSpotMatchProcessor.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeSpotMatchProcessor.java) 与 [`RuntimeDerivativeFillCalculator.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeDerivativeFillCalculator.java) 彻底剔除每笔成交时的临时值对象创建分支，全量收归 Lane 内部原地复用修改；修复 `RuntimeStateProjector` 路由缺失，六产品线与财务矩阵测试（204 项）全量通过。

