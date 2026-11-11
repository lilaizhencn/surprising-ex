# Aeron Core 基线（2026-11-11，远端重构后）

## 环境

- 代码基线：`b843fd3d5`（远端 `master`，已包含业务化重构）
- JDK：27.0.1
- Maven：3.10.0
- 命令：

```bash
cd surprising-ex
mvn -pl surprising-aeron-core/surprising-aeron-service -am test
```

## 测试结果

| 项目 | 结果 |
| --- | --- |
| 构建 | **BUILD SUCCESS** |
| 测试总数 | **1,573** |
| 失败 / 错误 / 跳过 | **0 / 0 / 1** |
| 全 Reactor 耗时 | **约 2:14 min** |

Nacos `127.0.0.1:8848` 连接告警是本地环境噪声，不影响结果。

## 本次修掉的问题

### 1. 远端 `master` 的 7 个确定性失败

拉取远端后发现 7 个测试失败，全部是重构后测试夹具仍反射旧字段：

| 测试 | 原因 | 修复 |
| --- | --- | --- |
| `DeterministicExchangeCoreAdapterTest`（4 个） | `MatcherEventFixtures` 反射 `TradeSettlement.directNativeResult`，该字段已移入内部 `ExecutionState` | 先取 `state`，再取其中的 `directNativeResult` |
| `MatcherPipelineGroupTest`（1 个） | 同上 | 同上 |
| `SectionedCoreSnapshotCodecTest`（2 个） | `CoreFaults` 反射 `TradingCoreRuntime.matchingAdapter`，该字段已收进 `CoreComponents` | 改用现有公开入口 `state.matchingAdapter()` |

修复后全量测试通过。

### 2. 两个并发偶发失败

| 测试 | 根因 | 修复 |
| --- | --- | --- |
| `OwnerIngressRingTest#concurrentCommandsKeepTheirFieldsAcrossThreeWraps` | `producedEntries` / `consumedEntries` 是普通 `long`，生产者/消费者跨线程时缺少 happens-before，消费者可能看到游标已推进但条目字段仍为 `null` | 两个游标改为 `volatile`，建立“写字段 → 发布游标”的可见性 |
| `CoreOrderedOrderBatchTest#isolatesOverlappingBatchesUntilTheActiveBatchCompletes` | 测试假设第一次 `completeMatching` 必须返回 `null`；但已就绪的两个批内完成可以在一次推进中合法完成整批 | 把“后续批次不能提前完成”的断言移到推进前；允许首次推进直接拿到整批终态，结果项断言仍要求两个订单都在 |

定向复跑：两个测试各 **5/5 通过**。

## 注释清理（远端重构后的第二批）

同一规则：删除复述参数名、局部变量旁白和表达式内注释；保留线程/所有权、租约/栅栏、快照/恢复、顺序、兼容性、失败路径等约束说明。

| 类 | 之前 | 之后 | 净减 |
| --- | ---: | ---: | ---: |
| `TradingAccountRuntime` | 6,350 | 5,663 | 687 |
| `OrderCommand` | 3,197 | 2,951 | 246 |
| `OrderBatchProcessor` | 2,787 | 2,531 | 256 |
| `TradingCoreRuntime` | 2,634 | 2,502 | 132 |
| `TradeSettlement` | 2,309 | 2,109 | 200 |
| `CoreQueryService` | 2,298 | 2,126 | 172 |
| `OrderBookEngine` | 2,206 | 1,923 | 283 |

合计净减 **1,976 行**：删除 `@param` 复述 **1,525 条**、局部旁白 **451 条**、表达式内注释 **266 处**。

每个类都校验了“去掉注释和空白后代码 token 完全一致”；全量测试 **2/2 通过**。

## 当前度量

| 指标 | 重构后基线 | 注释清理后 |
| --- | ---: | ---: |
| service main 行数 | 66,706 | **64,730** |
| 注释占比 | 31.4% | **29.3%** |
| 最大类 | 6,350 | **5,663** |
| 类型声明 | 810 | 810 |
| 包互赖对数 | 45 | 45 |

复现：`python3 scripts/aeron-core-metrics.py`。

## 黄金轨迹（第 1 步，本轮新增）

在继续拆类之前先固化「行为不变」的字节证据。**本轮未改动任何生产代码**。

| 项 | 内容 |
| --- | --- |
| 测试类 | `surprising-aeron-service/src/test/java/com/surprising/aeron/service/core/AeronCoreGoldenTraceTest.java` |
| 基线文件 | `surprising-aeron-service/src/test/resources/aeron-core-golden-trace-v1.txt`（提交入库，51 个字段） |
| 十条命令 | 限价下单、吃单成交、撤单、改单、批量下单、批量撤单、强平、资金费、划转、快照→恢复 |
| 每条记录 | 输入帧字节、响应负载字节、`stateHash`、`fundsHash`、关键账户/订单/持仓/财库快照 |
| 完成标准 | 同输入连跑两遍逐字段一致，且与已提交基线一致；所有命令必须为 `APPLIED` |

实现约定：

- 复用 `TradingCoreRuntime`、`CoreTestCompletion`、`CoreMessageCodec.encode`、`CoreProtocol.responsePayload`、`FundsStateHash` 和 `TradingCoreRuntime.stateHash()`；未新增执行框架。
- 确定性来源全部固定：单产品线 `LINEAR_PERPETUAL`、固定业务时间 `1_700_000_000_000L`、固定 UUID 前缀加场景/序号派生编号、固定 `sourceId`/`sourceSequence`/`correlationId`；只用同步执行模式，不加载 `PipelinedExecutionExtension`，不启动真实 Aeron Cluster。
- 快照恢复一条的 `input` 记快照字节的 SHA-256 与长度，另记 `tailInput`（恢复后继续下单的完整输入帧）。快照内层是字段编号自描述格式，字段重排仍恢复同一状态，因此不把快照字节本身当黄金。
- `stateHash` 和 `fundsHash` 都不覆盖订单与持仓明细，故额外记录按键排序的规范化状态快照，补足资金、冻结和终态证据。
- 默认只对比基线；重新录制须显式 `-Daeron.golden.record=true`，避免重构把基线自动改写掉。

验证结果：

- 全量 `mvn -pl surprising-aeron-core/surprising-aeron-service -am test`：**1,574 个测试，0 失败 / 0 错误 / 1 跳过，BUILD SUCCESS，约 2:37 min**（比上表多 1 个 `@Test`，即本测试）。
- `python3 scripts/aeron-core-metrics.py`：service main **64,730 行**、注释 **29.3%**、**810** 类型声明、**45** 对包互赖、最大类 **5,663** 行、public 声明数 —— 与上表完全一致；仅测试文件 286→287、`@Test` 781→782。
- 录制模式与默认模式各通过，跨 JVM 结果一致。业务语义已核对：挂单冻结、成交形成等量反向持仓、撤单释放、改单生成新 ID、强平产生保险 220 / 亏空 1760、划转扣减 30、恢复后继续成交。

未测范围：六产品线只录 `LINEAR_PERPETUAL`（其余五线未录）；划转只录单运行时 `TRANSFER_OUT`，跨产品线 `TRANSFER_IN` / `COMPLETE_TRANSFER` 未录；本轮不改热路径，未跑 JMH/JFR。

## 说明

- 之前基于 `2a6673f17` 的评审报告针对的是重构前的类（`AccountLaneState`、`CommandSlot`、`MatcherSettlementEvent` 等），这些类已被远端重构删除或改名，旧行数已失效，因此不随本次提交推送；本地备份在 `backup/aeron-cleanup-20261111`。
- 远端重构把文件数和字段数降下来了，但 `TradingAccountRuntime` 仍有 5,663 行、`OrderCommand` 仍有 2,951 行，包互赖仍有 45 对；下一阶段仍建议按状态所有权继续拆，而不是按行数拆。

## 下一步

1. ~~录 10 条黄金轨迹~~ 已完成，见上一节。后续**每次重构前后**都必须跑 `AeronCoreGoldenTraceTest`，且不得用重新录制掩盖差异。
2. 收敛 `TradingAccountRuntime` 公开面：先做零风险的 10 个改 `private`、14 个改 `package-private`（341→307），再做按状态所有权的结构拆分。注意 269 个方法有异包生产调用方、23 个测试类在不同包，且 `accountLanes` / `laneLifecycle` / `changedUsers` / `changedReservations` / `changedOrders` / `changedRiskSnapshots` / `owner` 七个字段名被反射或 VarHandle 以字符串锁定。
3. 处理 `OrderCommand`：先按「谁创建、谁修改、谁提交、谁回收」收敛跨组写入路径，不按行数拆类。注意 `AdmissionState` / `MatchingTasks` / `SettlementState` / `CommitControlState` 已是同名内部状态类，`Direct`（614 行）是并列的第二条命令路径；`CoreFaultAgent` 按类名后缀 `OrderCommand` 插桩 `result()`，搬走该方法会让故障注入静默失效。
4. 包依赖：先用值对象/工具类归位消掉 `model` 相关的 5 对环，再处理 runtime 反向回调；并给 `metrics.py` 的 mutual 判定补一条「只减不增」断言，当前仓库没有任何包依赖门禁。
5. 继续清理 `OrderCommitService`、`OrderAdmissionService` 等大类的复述型注释。

口径提醒：任务描述里的 `OrderCommand 159 个 public 声明 / 68 个 public 方法`、`TradingAccountRuntime 308 个 public 方法` 与代码和度量脚本都对不上。脚本 `public decls` 列是 `^\s{1,8}public ... name\(` 正则，实测 **155 / 341**；源码里 `TradingAccountRuntime` 顶层 public 方法 341（另 3 构造器、8 static、5 个 Lombok 合成 getter，且 52 个签名跨行会被逐行正则漏计）。「public 只降不升」验收前须先固定同一口径。
