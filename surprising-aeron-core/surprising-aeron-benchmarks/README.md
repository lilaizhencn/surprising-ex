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

## 夹具边界

- `LinearPerpetualBenchmarkSupport` 经实际命令入口推进，异步账户和 Matcher 必须完成后才收终态。使用规范数字标的 ID。
- 批量响应借用 arena；跨命令保存时必须在归还租约前复制有效响应字节。
- `MatcherTreasuryReceiptBenchmark` 等局部基准只测具体阶段；Treasury 由 Owner 同步处理，没有独立账本线程。
- `InProcessTradingAllocationBenchmark` 使用进程内传输夹具；不代表 UDP、Consensus、Archive 或完整 Core 容量。
- 无业务消费者的方法及其专用基准一起删除，不能为保留基准而保留生产接口。

夹具的 Lane 指标使用现有编码/查询路径；请求队列与故障注入字段访问留在本源集。撮合事实由 `BenchmarkMatchingAwait` 等待，终态直接交有序提交器。

冷状态通过 `users/orders` 的 Long 键映射读取；数据构造明确传入模式、进度及各子账，金额合计由本源集核对。索引规模在 Owner 工作完成后读取原表大小，不为观察在生产维护额外 ADL 计数；清理检查使用真实分页与候选入口。

规模脚本的 Probe/Soak 入口均指向当前 `service.core` 包。验证范围以每次记录为准，最近一次单节点网络实测见下文。

## 结果要求

核对 offered、accepted、terminal、unfinished 和账户资金、冻结、持仓、终态及恢复。JMH fork 失败、缺少资金核对或录制窗口截断都不能记为通过。

`ClusterCapacityMain` 在预热及测量边界最多等待 30 秒，确认实际队列和在途水位全部排空；
边界等待不计入稳定测量窗口。拓扑不符立即失败，排空超时保留最后一次观测指标。

无 profiler 的主吞吐与 GC/JFR 分开测量。`pipelineInterval` 和录制事件中的 MEASUREMENT 区间用于归因；DataLoss、swap、限频或驱动上限按实际证据报告。历史数据见根 [性能验证记录](../../PERFORMANCE_VALIDATION.md)，不作为当前修改的性能结论。

仅编译本模块可用：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test-compile -DskipTests
```

## 最近一次单节点永续观测

2026-10-30 持续发压：普通单约3.97万单/秒，batch20约27.11–28.54万单/秒；资金、终态和两次快照恢复通过。
本机存在CPU限频，部分档有系统换页，正式速度验收无效、Core业务饱和未确认；详见[本轮报告](../../docs/validation/perpetual-saturation-20261030/REPORT.md)。
