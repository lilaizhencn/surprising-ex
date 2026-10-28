# Aeron 验证与诊断工具

执行前读取根 [性能规范](../../AGENTS-performance.md)，使用 HotSpot JDK 27。局部 JMH、同进程夹具与真实 Aeron 网络场景分别记录，功能验证不推导吞吐或分配结论。

## 当前脚本

| 入口 | 作用 |
| --- | --- |
| `bin/qualify-aeron-async-stages.sh` | 真实单成员分阶段验证，默认一个 Matcher、全局窗口 256，支持独立 GC/JFR 诊断 |
| `bin/qualify-linear-perpetual-scale.sh` | 业务规模及稳定测量窗口，保留资金与终态核对 |
| `bin/qualify-linear-perpetual-scenarios.sh` | 选定永续业务场景的真实节点验证 |
| `bin/analyze-owner-commit-jfr.sh` | 按录制及实际测量区间分析 CPU、延迟、分配与覆盖范围 |
| `bin/summarize-aeron-async-stage.py` | 汇总阶段日志和录制证据 |
| `bin/verify-surefire-reports.sh` | 检查 Maven 测试报告的真实结果 |
| `bin/test-business-latency-contract-static.sh` | 检查业务延迟分类穷尽性、权重及合并分位数契约，不启动发压 |
| `bin/test-verify-surefire-reports.sh`、`bin/tests` | 脚本自身的功能测试 |
| `bin/generate-oldobject-jfc.sh` | 生成局部对象保留诊断配置 |

旧 `qualify-linear-perpetual.sh` 携带已无读取方的 projection/matching-completion 配置，并重复旧的进程内资格流程，已删除。使用上表当前入口选择场景。

## 夹具边界

- `LinearPerpetualBenchmarkSupport` 经实际命令入口推进，异步账户和 Matcher 必须完成后才收终态。使用规范数字标的 ID。
- 批量响应借用 arena；跨命令保存时必须在归还租约前复制有效响应字节。
- `MatcherTreasuryReceiptBenchmark` 等局部基准只测具体阶段；Treasury 由 Owner 同步处理，没有独立账本线程。
- `InProcessTradingAllocationBenchmark` 使用进程内传输夹具；不代表 UDP、Consensus、Archive 或完整 Core 容量。
- 无业务消费者的方法及其专用基准一起删除，不能为保留基准而保留生产接口。

夹具的 Lane 指标使用现有指标编码/查询路径；已删除单独构造指标视图的生产入口。实时请求队列的夹具访问留在测试或基准源集，生产 Owner 不再提供转发访问器。

## 结果要求

核对 offered、accepted、terminal、unfinished 和账户资金、冻结、持仓、终态及恢复。JMH fork 失败、缺少资金核对或录制窗口截断都不能记为通过。

无 profiler 的主吞吐与 GC/JFR 分开测量。`pipelineInterval` 和录制事件中的 MEASUREMENT 区间用于归因；DataLoss、swap、限频或驱动上限按实际证据报告。历史数据见根 [性能验证记录](../../PERFORMANCE_VALIDATION.md)，不作为当前修改的性能结论。

仅编译本模块可用：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test-compile -DskipTests
```

当前清理仅运行相关功能测试和脚本检查，未执行压测、JMH/JFR。
