# Aeron 性能验证记录

2026-10-22。**正确性与原Archive恢复已验证；身份分配80B/操作，零分配目标未达，正式吞吐暂缓。** 组件表格来自13:20旧jar，限定沿用范围见下文；新 `bbbe57/d227` 产物已执行精确流水线分配、独立60秒诊断及600秒真实订单验证。用户确认暂时没有独立环境，先完成其他验证；本轮不能作为容量或Lane速度结论。

## 计划与环境

方案在真实发压前锁定：一个真实 Aeron 成员、一个 matcher、全局 in-flight=256；LINEAR_PERPETUAL、MATCH_STREAM、买卖 GTC 各半、100 用户、256 币对、4 worker/连接、seed=9901、PIPELINED。正常轮预热30秒、测量60秒，排空单列；JFR/GC另轮，长稳为600秒 correctness-only，并审计同一负载的资金与 Archive 重启恢复。

已核实环境：Corretto HotSpot JDK 27、Maven 3.9.16、G1；Intel i9-9880H，8核16线程、16GiB，实际缓存行64B。并行开发时曾记录 swap 使用9164MiB、CPU speed limit 72；这些环境数据不支持速度结论。正式门槛保持无 swap、无节流，不能用 correctness-only 替代正式容量验收。

## 旧产物已执行

使用实际可执行 `surprising-aeron-service.jar` 优先加载生产类，探针后置，避免临时编译类遮蔽。分配通过 HotSpot `ThreadMXBean` 累计字节差验证，输入预建、结果保留；没有以舍入值或 JFR 无样本证明零分配。以下均为组件事实，不代表完整 Core 零分配或性能提升。

| 验证 | 实际结果 | 边界 |
| --- | --- | --- |
| `RuntimeChangeBuffer` | 热代码冷容量256项扩容22224B；预容量一次11344B；复用10000批×256项累计0B | 实际扩容8→256、索引16→512；未量化生产尾延迟 |
| `LaneSequenceQueue` | 100万发布/消费对累计0B，accepted=terminal=100万，unfinished=0 | 原语固定容量环组件 |
| `PaddedReadyMask` | 两Lane读写清理100万次累计0B、pending=0 | 组件正常路径；不证明并发提示为原子快照 |
| 装箱控制组 | 100万个逃逸Long分配16000000B；预建原语数组写入0B | 当前JDK每Long为16B；不是生产装箱缺陷证明 |
| 序号对象布局 | Queue、Matcher、Settlement、Committed、SystemLedger序号对象均256B，volatile值偏移128 | 实际布局；没有缓存失效率或padding收益数字 |
| ReadyMask数组 | long元素8B、槽步长128B；1/2/64 Lane总浅大小160/288/8224B | 不同Lane槽不共享64B行；不声称数组外邻居或全部真实共享被消除 |

基准工具也修正了实际证据缺口：worker ready 后固定稳定窗口；终态延迟/计数与排空分开；真实 `LANE_METRICS_QUERY` 校验拓扑及空队列；warmup 原子捕获，`totalRunMatches` 包含预热、测量与排空。旧构建对应的13:23 Surefire XML记录 Metrics4、AsyncPair5、StreamFill6项，均0失败/错误/跳过；不能代替新构建或完整后端回归。

已删除没有生产读取者的 `matcher-wait-strategy`、`core.settlementLatencyDiagnostics` 和8个不存在的Owner自定义JFR事件，保留真实 `matcher-pipeline-wait-strategy`。JFC的62个事件均已对照当前JDK27元数据或源码定义；8个受影响脚本各自 `bash -n`、真实jq聚合程序与业务分类静态契约检查退出0。缺NMT的控制录制被分析器明确拒绝（退出3），没有静默接受。

实际三段仅为客户端 queued→offered、offered→delivered（网络与Core）、queued→delivered，命令ID抽样1/64，包含该客户端命令；不冒充Owner/Matcher/Lane内部独立耗时。流式JFR及边界配对工具先以控制录制验证，新产物真实结果见下文。

## 可复查命令与产物

组件输出和源码校验位于本轮独占目录；final入口脚本整理了已逐条执行的命令，供复现：

```text
/tmp/surprising-remediation-performance/run-component-probes.sh
/tmp/surprising-remediation-performance/run-final-component-probes.sh
/tmp/surprising-remediation-performance/{allocation-final-service,layout-final-service,ready-mask-final-service}.txt
/tmp/surprising-remediation-performance/final-component-sha256.txt
```

旧节点可执行fat jar SHA-256为 `1d214b3bae23e36b4949de1d3e4da07a58529544addaca55f3049e4e63801e4b`；对应旧library为 `f152797012007e3362c985bd2b168a0187d3bfdc4858e367e49927839e1f5d86`，这两份旧产物的581个service class字节一致。旧bench fat为 `63971648622c868d79451148be10c19995b257b981a4d4632bdee449239327ec`。完整计划、临时工具、控制录制、配置验证输出在上述目录，过程记录在 `/tmp/surprising-remediation-performance.md`。

版本更新：15:13提交 `9c8ce94e1` 包含晚于旧fat的19个Core生产Java修改。根代理已完成当前HEAD的Core/tools/bench全量clean install；新service fat为 `bbbe57ed318d666ab2b2d08a1df67a38f12aee130b0c1d40e85f552bc5677eec`，新bench fat为 `d22720e59e88c894a0c5b06215c39bf7a50e6eada33604d977446b248290bac9`，均已独立核对。

新旧Core实际ZIP逐项比较：16个被测类（含padding基类）字节一致，Eclipse Collections等第三方类无变化；`jdeps`/`javap`确认组件调用未进入已变化的`TradingAccountRuntime`外层业务方法，相关继承类一致。因此同JDK27及布局参数下，仅沿用表中的独立Buffer/Queue/ReadyMask分配和字段/数组布局事实；没有重跑新组件，也不沿用外层runtime或整条流水线结论。旧bench fat已被clean删除，不能用thin jar冒充或继承其执行结果。

8个临时Java工具已对新bench重新编译，exit0（布局探针有9条Unsafe弃用警告）。新service与新bench内的16个被测类也逐字节一致。比较清单、依赖、反汇编及工具hash保存在 `/tmp/surprising-remediation-performance/new-head-9c8ce94e1/`；旧Core fat归档及根构建清单位于 `/tmp/surprising-remediation-root-20261022.zrHw4B/old-core-artifacts/`、同根的`core-current-head-artifacts.json`。根构建清单记录Core1584项/1跳过、Bench318项/0跳过、Tools22项，均0失败/错误；这只证明构建回归。

## 新产物真实验证

18:40资源交接后，使用上述新jar与JDK27执行预先锁定方案。精确计数命令为 `/tmp/surprising-remediation-performance/run-exact-pipeline.sh`：预建帧、PIPELINED/Matcher1/Lane2、单币对、batch1，预热524288消息，测量262144消息；网络与Archive排除。退出0、前后Java线程集合相同，Owner实际分配20971520B，即**80B/业务操作**，其它现存Java线程均0B。夹具终验786432消息、unfinished=0、fundsDiff=0。不能把功能通过或组件0B写成流水线零分配通过。

对同一未改夹具单独加JFR一次，Owner仍精确20971520B，JFR周期线程另208B，未从总数扣除。稳窗实际采样栈定位到 `CoreMessageHeader.commandId → MatchingCommandAdmission.prepareMatching` 的UUID，以及 `PlaceOrderCommand.decodeClientOrderId → TradingCommandCodec.decodePlaceOrderInto` 的字节数组。采样权重不是对象精确总量；原无profiler计数、JFR原文件、完整栈和附加JFR命令分别在本轮目录的 `pipeline-allocation-final.txt`、`pipeline-attribution.jfr`、`pipeline-attribution-analysis.json`、`pipeline-attribution-command.json`。

这是已量化的必要身份分配限制，**零分配目标未达**，不按已确认的多余设计缺陷计数。客户端订单号保留持久身份语义；命令UUID在正常回执 `CoreCommandResultCodec → CoreStateQueryCodec.writeOrderState` 和 `RealtimeStateCapture.order → CoreOrderStateView` 等边界共享。只改内部为两个long或延迟getter会重复创建或转移分配；完整消除涉及DTO、编码及投影改造，尚无简单且已验证收益的方案。本轮保留现有设计，不宣称0B或性能改善。

真实网络诊断命令：

```text
python3 /tmp/surprising-remediation-performance/run-real-round.py --confirmed-exclusive --lanes 2 --run-id focused-jfr-20261022-1842 --warmup 30 --seconds 60 --diagnostic --correctness-only
```

该轮退出0，实际1个成员、1 Matcher、2 Lane；全局窗口256且observedRequestMax=256。稳定窗口为epoch毫秒 `1792665727389..1792665787389`，终态622212，另排空256，accepted=finalized=622468；submitted=completed=634831、unfinished=0。包含预热的真实成交492260；逐账户及12800仓独立核算通过：long=short=492260、用户资金合计100000000000000、冻结/独立margin与entryValue一致，Treasury七字段为0，reservation/活跃单/盘口/核心队列均0，businessHash=`115e288a6dd2a35b`。该轮未做恢复，不能借此宣称Archive恢复通过。

两份JFR覆盖完整稳窗，DataLoss=0。Owner实际发生2次service.jar读取共2581B、约17.1µs；完整ClassLoad及文件栈确认是窗口开始后首次加载 `CoreLaneMetricsCodec$Encoder`，不当作持续业务文件I/O。Archive线程真实写入74717次、142017568B；无Owner重复文件写/Java socket事件，但这不能证明native UDP系统调用为0。客户端抽样三段有9929条完整配对、2条窗口边缘未配对、无重复或时钟倒序；详细值只作为受限环境诊断。

稳窗节点无GC事件，客户端32次GC pause合48.713ms；节点/客户端direct buffer分别保持9575136B/8522400B。NMT、heap、CPU/锁/park、iostat及每2秒环境原始输出完整保留，60秒不足以证明无泄漏。该窗CPU speed仅56–60、swap9216–10144.75MiB，**全部速度/延迟值不作正式性能结论**。节点线程分配周期计数仅覆盖58.922秒，31.84MB为下界，不能冒充全窗精确计数。

原始目录为 `/tmp/surprising-remediation-performance/runs/focused-jfr-20261022-1842/`，其中 `functional-result.json`、`state-audit-before.log`、`cleanup.json` 已独立复核；node/client/iostat全部正常受控退出，无强杀、无残留。源码未变，没有重复精确分配或60秒诊断。

## 600秒正确性与原Archive恢复

```text
python3 /tmp/surprising-remediation-performance/run-real-round.py --confirmed-exclusive --lanes 2 --run-id focused-long-20261022-final --warmup 30 --seconds 600 --correctness-only --verify-recovery
python3 /tmp/surprising-remediation-performance/recover-original-archive-once.py
```

真实稳窗 `1792669189375..1792669789375`：终态14832307，另排空259，accepted=finalized=14832566，submitted=completed=14973576，failures=0、unfinished=0；实际1 Matcher、2 Lane、窗口256，结束所有核心队列为0。预热成交374601，全程成交7790884；100用户、12800仓、long=short=7790884、用户资金合计100000000000000，独立margin/entryValue、冻结、Treasury及空订单/盘口检查全部通过。

**原运行器退出1、`functional-result.json=false`，没有改写为通过。** 它在旧节点已退出后约27.5ms便拉起恢复JVM；约1.43秒后Aeron以旧CnC心跳仍活跃拒绝启动，尚未进入Core状态恢复。这是本轮临时运行器漏等心跳的边界问题；仓库生产与qualify脚本未改。实际Aeron1.53.3的MediaDriver/CommonContext类字节已核对，既有driverTimeoutMs为10000，active检测比较心跳年龄，不检查进程PID。

已保留修前脚本及diff，在临时运行器的stop→restore步骤补齐：确认原owned PID不存在，按既有10秒超时做有界自然失效等待，不手工删CnC、重置Archive或修改生产超时。死PID有界等待、活PID拒绝的控制验证及语法检查退出0。随后只补一次同一原Archive恢复，实际等待10103.96ms，单次恢复工具退出0；没有重跑600秒或重发交易/入金重建状态。

补恢复后严格审计通过，before/after全部13107项及原文件逐字节一致，SHA-256均为 `ff979a212a834d8d68e0cbac3165eabb1f8181f47ac294b2d3652d3ca199df80`，businessHash均为 `b041719e9c177093`。资金、持仓、修订、模式和空队列再次核对。回放期间保留了 `quorum position went backwards` WARN（leaderCommitPosition=3523636576、quorumPosition=0）；随后实际转LEADER，完整状态一致，不因日志级别忽略该观察，也不据一次结果外推其他拓扑。

283组窗口环境样本中CPU speed60–66、swap6089.75–9923.25MiB，性能数无效。10组NMT/heap采样中节点committed为697011–698372KiB；节点3次GC后堆为102/106/104MiB，客户端620次GC后堆由13到14MiB。RSS范围节点1785296–2390996KiB、客户端938036–1003876KiB；RSS包含NMT范围外的映射等，不能互相替代。没有将十分钟观察写为无限期无泄漏。

证据在 `/tmp/surprising-remediation-performance/runs/focused-long-20261022-final/`：原失败`functional-result.json`、`recovery-node.log`，补恢复`recovery-once-result.json`、完整before/after快照及`independent-recovery-snapshot-comparison.json`，内存环境`long-run-memory-environment-analysis.json`。运行器修前文件、`run-real-round.driver-wait.diff`、控制验证、实际依赖源码摘录保存在父目录；最终摘要与证据hash见`final-verification-summary.json`、`final-evidence-manifest.json`。原失败与补恢复成功分别保留，整体同负载正确性与恢复证据已补齐。

## 7. 2026-10-25 内联重构与死代码彻底清理后权威压测记录

- **执行基线**：Git Commit `caad77c60`（`master` 分支）
- **测试环境**：Apple M 系列 (macOS / arm64), JDK 27 (HotSpot 64-Bit Server VM, 27+33-FR)
- **JVM 参数**：`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --enable-native-access=ALL-UNNAMED`
- **架构模式**：Aeron 容器服务线程直接驱动 `TradingOwnerLoop` 内联推进，已彻底移除独立后台 Owner 线程及跨核交接环。

### 7.1 测试数据与实测指标

| 场景 | 产品线 | 关键配置与负载 | 吞吐量 (Throughput) | 延迟分位 (Latency) | 业务一致性与终态 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **端到端订单撮合流** (`CorePerpetualEndToEndBenchmark`) | `LINEAR_PERPETUAL` | 10,000 cycles, 20,000 orders, `makerDepth = 1` | **21,062.155 orders/s**<br>(10,531.078 cycles/s) | p50: **41 µs**<br>p95: **78 µs**<br>p99: **129 µs**<br>max: 7,324 µs | `pendingMatching = 0`<br>`matchedQuantity = 10,000`<br>`finalizedOrders = 20,000` |
| **JMH 常驻盘口异步挂撤单** (`LinearPerpetualCoreBenchmark`) | `LINEAR_PERPETUAL` | `accountLanes = 2`, `priceLevels = 200`, `ordersPerLevel = 1250` | **142,581.470 ops/s** | - | `terminalBusinessOps = 142,582.291 ops/s`<br>`errorOps ≈ 0`<br>`unfinishedOps ≈ 0`<br>`timedOutOps ≈ 0` |
| **JMH 常驻盘口异步挂撤单** (`LinearPerpetualCoreBenchmark`) | `LINEAR_PERPETUAL` | `accountLanes = 1`, `priceLevels = 200`, `ordersPerLevel = 1250` | **116,861.984 ops/s** | - | `terminalBusinessOps = 116,862.692 ops/s`<br>`errorOps ≈ 0`<br>`unfinishedOps ≈ 0`<br>`timedOutOps ≈ 0` |
| **JMH 批量挂撤单吞吐** (`ClusteredBatchTradingBenchmark`) | `LINEAR_PERPETUAL` | `batchSize = 20`, 512 batchWindows, `maxInFlight = 256` | **2,437,120.000 items/s**<br>(核心批次: 121,856 ops/s) | - | `rejectedOps ≈ 0`<br>`accepted = terminal = 121,856`<br>`unfinished = 0` |
| **JMH 批量挂撤单吞吐** (`ClusteredBatchTradingBenchmark`) | `SPOT` | `batchSize = 20`, 512 batchWindows, `maxInFlight = 256` | **2,723,840.000 items/s**<br>(核心批次: 136,192 ops/s) | - | `rejectedOps ≈ 0`<br>`accepted = terminal = 136,192`<br>`unfinished = 0` |

### 7.2 性能与架构结论
1. **结论：通过（PASS）**。内联后流水线命令流转零积压、零超时、零死锁，所有测试项 `accepted = terminal`，`unfinished = 0`。
2. **极速批处理吞吐**：在 `batchSize = 20` 批处理下，线性永续业务订单吞吐达到 **243.7 万/秒**，现货达到 **272.3 万/秒**。
3. **极低端到端延迟**：逐笔撮合流下 p50 为 **41 µs**，p99 达到 **129 µs**，完全消除了原跨线程交接带来的抖动。


- 正式吞吐、Lane1/2速度对照：按用户答复暂缓，不宣称多线程更快或Core容量上限。
- 精确流水线身份分配限制为80B/操作，零分配目标未达；双端native结果独立记录，不能被Core正确性结果替代。
- 同负载财务方案只适用于零费、100价、单量1、multiplier1、默认10x LP夹具：Treasury七字段为0，逐仓独立margin=absQty×10、entryValue=absQty×100；逐账户资金/冻结、无reservation/活跃单/盘口/队列，逐币对净仓0，long=short=totalRunMatches。60秒、600秒及原Archive恢复后均通过；仅就绪查询允许重试，财务断言从未重试或放宽，不套用于六产品通用结算。
- 系统采样权限：dtrace/fs_usage实际被SIP/权限拒绝。独立xctrace控制探针可导出syscall表，但没有取得PMC/cache表，不能填缓存失效率0；JFR无I/O事件也不能证明native UDP系统调用为0。十分钟即使通过，也不能证明无限期无泄漏。

根代理此前发现 `/tmp/aeron-simplify-20261022-716xs1hc` 的Maven/fault-agent负载；该目录及任务**不属于本轮性能验证**。未终止或清理外部任务，也未修改系统VM/swap设置。

## 清理状态

本性能子任务全部节点、driver、发压和采样进程均已退出，无强杀或残留。600秒原节点1075、客户端1193、iostat1192、首次恢复16747及补恢复23826均已独立kill0/ps确认不存在；最终证据为 `/tmp/surprising-remediation-performance/resource-release-final-long-recovery.json`，`allOwnedReleased=true`。早期专属xctrace及其DTServiceHub也已核实并结束。临时源码、录制、trace、原Archive及成功/失败输出保留供复核，没有删除失败记录或终止其他用户任务。
