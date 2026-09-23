# Aeron 交易主链路简化验证（2026-09-23）

## 业务边界

已复制命令先进入 `TradingCoreOwner` 队列，Owner 轮询完成准入、撮合、Account Lane 结算、按序提交和响应交接。查询仍在需要时取得 Lane 读栅栏。`CommitPublication` 持有唯一已发布序号；快照屏障捕获该序号、交易状态和业务哈希，恢复后从原序号继续。异常仍按既有拒绝、回滚和致命错误边界处理。独立回放的同步 `apply` 及 `MATCHING_PENDING` 阶段结果仍保留。

## 验证

环境为 Corretto HotSpot JDK 27、Maven 3.9.16，测试前及运行中可用磁盘约 513 GiB。

| 范围 | 结果 |
| --- | --- |
| `surprising-aeron-service` 及上游模块 Maven 测试 | 95 套件、916 项，0 失败、0 错误、2 跳过；含六产品线撮合、资金、批量、恢复和 Owner 流水线测试 |
| `surprising-aeron-tools` 及 `surprising-realtime-provider` 依赖链 Maven 测试 | 分别 20 项（1 跳过）和 47 项，0 失败、0 错误 |
| `surprising-aeron-benchmarks` 与 `surprising-gateway` 依赖链 `clean test` | 清理构建及测试编译通过；基准模块 `CorePerpetualEndToEndBenchmarkTest` 1 项通过 |
| `git diff --check` | 通过 |

一次服务模块全量运行在 `ClusterCommandPipelineTest.laneCompletionPollPreservesCommitContextAndDoesNotWaitForUnrelatedWork` 的 Lane 时序断言处失败；该测试单独复跑六产品线通过，随后依赖链再次执行服务模块全量测试通过。增量编译曾漏掉基准和 Gateway 测试夹具对已删除转发方法的引用；已修复，并通过跨模块 `clean test` 编译。

未运行需外部隔离集群、Kafka 和 PostgreSQL 的 `MergedBusinessHttpIntegrationTest`、`MaintenanceIntegrationTest`，因此本轮没有实际 HTTP 模拟用户和 WebSocket 端到端验收；未启动 wallet。没有新增优化或实测瓶颈，不做 JMH/JFR 性能结论。

## 清理

测试与分析结束后未保留本轮 Maven、Aeron 或 Kafka 进程。本轮 `/tmp/surprising-aeron-*-20260923.log` 日志及本轮生成的 Surefire 报告已清理；这些路径仅作历史定位。Maven 编译产物留在忽略的 `target` 目录，供本地增量构建使用。
