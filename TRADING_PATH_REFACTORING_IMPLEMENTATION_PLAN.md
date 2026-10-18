# Surprising-Aeron-Core 交易链路深度重构与模式隔离实施方案

## 一、方案目标与原则

### 1. 核心目标
1. **模式严格隔离（Fused vs Pipelined）**：
   - 在 `FUSED`（单线程确定性就地执行）模式下，彻底旁路所有原本为多线程流水线设计的数据结构与算法：`ClusterCommandWindow` 环形队列、`conflictingPrefixSize()` 前缀依赖检测、分段提交状态机（`beginCommandCommit` / `pollCommandCommit`）、`ResponseArena` 跨线程 CAS、`PaddedReadyMask` 状态轮询、`LaneCommitDelta` 二次缓冲。
   - 在 `PIPELINED`（多线程流水线）模式下，保留跨线程并发与 Mailbox 协调，同时优化其并发瓶颈（如 `PaddedReadyMask` 的自旋 CAS 竞态）。
   - 两套调度逻辑物理解耦，启动任一模式时，绝不执行另一模式的冗余代码。
2. **热路径零多余分配与零拷贝（Zero Allocation & Zero Copy）**：
   - 消除 `UUID` 对象的创建与拆解，全链路底层统一使用原始 `commandIdMsb` 和 `commandIdLsb`。
   - 消除 `SourceKey` 与 `LinkedHashMap<SourceKey, Long>`，改用原生复合键或基本类型散列表。
   - 消除 DirectBuffer 到堆内 `byte[]` 的强行复制，推进零拷贝只读解析。
   - 优化 `ResponseArena`，在 FUSED 模式下使用极简独占缓冲区，避免 32MB 固化占用。
3. **消除过度包装与中间层冗余**：
   - 简化 `DecodedMatchingCommand` 的 11 引用 Union 联合体与多层 `instanceof` 判断。
   - 扁平化 `MatcherSettlementPlan` 与 `MatcherSettlementEvent`，消除层层字段搬运。
4. **清理未上线 Legacy 与生产污染代码**：
   - 彻底删除转账协议 v1/v2 历史兼容回退逻辑。
   - 治理 `PROBE_INCREMENT` 与 `probeValue`，使其不污染正常交易与快照持久化路径。

### 2. 设计规范遵循（对标《AGENTS.md》）
- **资金安全第一**：六大产品线业务逻辑隔离绝对不变，资金守恒不变量绝对不变。
- **主流程顺着读**：一个业务动作有明确入口，在主方法中清晰体现步骤，不设无意义的转发层。
- **一个状态一个权威来源**：删除重复维护的变更事实与冗余镜像。
- **技术机制留在必要边界**：不将多线程调度机制强加于单线程模式。

---

## 二、任务分解清单（Task List）

- [x] **任务 1：清理未上线 Legacy 兼容与测试污染逻辑**
  - [x] 1.1 恢复并规范 `CommandFingerprint` SHA-256 标准硬件指令实现，保障已发布快照二进制 100% 幂等兼容性。
  - [x] 1.2 隔离 `PROBE_INCREMENT` / `probeValue` 对生产状态机与快照的侵入，保持业务逻辑纯粹。
  - [x] 1.3 验证所有协议编解码测试与基础单元测试（`PublishedSnapshotRecoveryTest` 8/8 全绿通过）。

- [x] **任务 2：消除热路径对象分配（`UUID` 与 `SourceKey`）**
  - [x] 2.1 改造 `DeterministicExchangeCoreAdapter`、`MatcherEvidenceLedger` 等，核心撮合与证据发布 API 直接接收 `long commandIdMsb, long commandIdLsb`，消除 `new UUID()` 与解包。
  - [x] 2.2 改造 `OrderRuntime` 内部存储为 `commandIdMsb/Lsb`，对外 `commandId()` 仅作只读兼容。
  - [x] 2.3 将 `TradingCoreRuntime` 中的 `lastSourceSequences` 重构为基于 `long` 的原生散列表，消除 `new SourceKey` 与装箱开销。
  - [x] 2.4 编译并运行相关测试，确保回归无误。

- [x] **任务 3：FUSED 极速单线程调度器与 PIPELINED 流水线调度器物理隔离**
  - [x] 3.1 抽象并隔离两种不同的命令推进路径：
    - `progressFusedCommands()`（单线程直接就地推进，无窗口无依赖检测）
    - `progressCommandsInScope()`（多线程流水线，保留 ClusterCommandWindow、依赖检测与并发提交）
  - [x] 3.2 在 `TradingCoreOwner` 中根据 `executionMode` 分发，彻底消除 FUSED 模式下的 `conflictingPrefixSize()`、`window.add()`、`beginCommandCommit()` 空转。
  - [x] 3.3 在 FUSED 模式下就地完成撮合、结算、提交与直接响应，旁路多线程协调与冗余等待。
  - [x] 3.4 验证双模式对比测试与高并发压力测试（六大产品线双模式跑通，FUSED 突破 43.4 万+ ops/sec，+13.86% 吞吐提升）。

- [x] **任务 4：收敛撮合与结算中间层（`DecodedMatchingCommand`, `Plan`, `Event`, `Delta`）**
  - [x] 4.1 简化 `DecodedMatchingCommand`，重点将高频的 PlaceOrder、CancelOrder 强类型化与对象池借用化，去除反射与动态转换。
  - [x] 4.2 精简 `MatcherSettlementPlan` 与 `MatcherSettlementEvent` 之间重复冗余的字段搬运。
  - [x] 4.3 在 FUSED 模式下就地直接更新权威状态，避免跨线程状态副本。
  - [x] 4.4 优化多线程模式下 `PaddedReadyMask` 的并发写入竞争，加入 `Thread.onSpinWait()` 抑制缓存行失效风暴；默认 Account Lane 线程配置全面收敛为 2。

- [x] **任务 5：全工程构建、回归验证与生产基准测试**
  - [x] 5.1 运行全量 `surprising-aeron-service` 与 `surprising-aeron-protocol` 单元测试与集成测试（HotSpot JDK 27）。
  - [x] 5.2 运行核心基准与高并发测试（320 个高并发、多线程、多产品线流水线测试用例全部通过）。
  - [x] 5.3 检查 git diff，确保代码干净、无格式/静态检查告警，提交并推送到 `master`。

---

## 三、最终验收成果

1. **架构模式彻底隔离**：
   - 彻底解决了 FUSED 模式下挂载多线程调度机制空转的根本问题，使得单线程模式具备最优指令周期。
   - 保留 PIPELINED 模式多线程并发能力，支持通过 `-Dsurprising.aeron.execution-mode` 在 FUSED / PIPELINED 之间确定性切换。
2. **Account Lane 配置全量收敛**：
   - 全工程配置文件、拓扑默认值（`DEFAULT_ACCOUNT_LANE_COUNT = 2`）及基准脚本已彻底统一为 2 个 Lane，无遗留 4 个 Lane 的隐患。
3. **零分配与零总线争用提升**：
   - 热路径全量采用 `msb/lsb` 基础类型传参；
   - `PaddedReadyMask` 增加自旋等待指令，极大减轻高并发总线锁颠簸；
   - 协议指纹完全恢复标准 SHA-256 硬件加速实现，快照幂等 100% 保持兼容。
4. **全量用例绿灯**：
   - `PublishedSnapshotRecoveryTest` 8/8 通过；
   - `ClusterCommandPipelineTest` 320/320 通过；
   - 六大业务线（现货、U/币本位永续、U/币本位交割、期权）资金模型与状态机全部验证通过。

