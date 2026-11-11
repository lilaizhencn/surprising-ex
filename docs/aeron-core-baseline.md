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

## 说明

- 之前基于 `2a6673f17` 的评审报告针对的是重构前的类（`AccountLaneState`、`CommandSlot`、`MatcherSettlementEvent` 等），这些类已被远端重构删除或改名，旧行数已失效，因此不随本次提交推送；本地备份在 `backup/aeron-cleanup-20261111`。
- 远端重构把文件数和字段数降下来了，但 `TradingAccountRuntime` 仍有 5,663 行、`OrderCommand` 仍有 2,951 行，包互赖仍有 45 对；下一阶段仍建议按状态所有权继续拆，而不是按行数拆。

## 下一步

1. 录 10 条黄金轨迹（输入/响应字节 + `stateHash` + `fundsHash`），用于证明后续重构行为不变。
2. 继续清理 `OrderCommitService`、`OrderAdmissionService` 等大类的复述型注释。
3. 优先处理 `OrderCommand`（155 个 public 声明）和 `TradingAccountRuntime`（341 个 public 声明）的公开面。
