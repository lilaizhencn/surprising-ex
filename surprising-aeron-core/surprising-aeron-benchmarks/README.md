> 2026-09-13 用户约定：后续真实单节点 Aeron Cluster 压测统一使用 128 个活跃币对。普通容量入口默认 `surprising.aeron.capacity-symbol-count=128`，mixed 场景固定 128；启动参数不得覆盖为旧规模。初始化和校验随币对数计算，历史结果不改写。

# Product Core 性能验证

本模块的 `OwnerFactFrameBenchmark` 独立测量撮合完成后的 raw fact-frame owner commit 路径：fact frame
seal/publish/apply、四 Account Lane 的九索引 fanout、增量 hash 与 canonical recompute、批量
projection/Core Fact V10 编码，以及 snapshot/recovery。五个测量体都真实处理 16,384 个连续
`RuntimeFactFrame`，并以 1,024 个 fact frame 为 admission/fanout/projection 窗口；JMH 使用
`@OperationsPerInvocation(16384)` 按逻辑 owner commit 归一，不再使用固定 64 条装饰性 batch。
Core Fact 场景将 typed fact frame 交给 `CoreExportState` 的异步 materializer，并从其完成后的 encoded
`CoreMessage` 统计 V10 bytes；owner benchmark 不直接构造或读取 Core Fact payload。
增量 hash 分支在测量区逐 operation 执行 `RollingBusinessStateHash` 与 `RollingFundsStateHash`
的 prepare/transition/commit，canonical 分支则逐 fact frame 冻结当前 projection 并执行两类 full
compute，避免复用 setup 阶段的最终 hash。snapshot 场景只有在 decode、runtime restore、重新
materialize 以及完整 state/hash 等价检查全部完成后才记录 terminal。

验收入口：

```bash
SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh owner-commit
```

脚本同时检查并保存 `java -version` 和 `mvn -version`，拒绝 OpenJ9、非 JDK 25 或非
HotSpot-compatible VM，默认使用 ZGC，并固定 10,000 活跃用户、128 个活跃 symbol、4 个
Account Lane、每用户 5 个持仓和 10 个未成交单、固定 256 max in-flight、16,384 operations per
invocation。owner 场景使用 100,000 ops/s 的 open-loop constant-arrival 计划时间，入口延迟从
计划到达时刻计算，因此包含排队并修正 coordinated omission。每个 benchmark/business type
分别输出 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max。
256 in-flight 验收同时固定 256 MiB Core Fact reservation 上限；服务默认仍为 64 MiB，生产部署
必须按最大并发命令的 `FactCostEstimate` 总和配置，不能依赖事件数上限代替字节门禁。

AuxCounters 同时输出 terminal business ops、terminal Core messages、fills/trades、batch/items、
accepted-terminal gap、unfinished、最大/期末 backlog、reject/error/timeout、fact frame items/bytes、
snapshot bytes、Core Fact encoded bytes、总/最大 batch size。`fact frameBytes` 是与 commit journal
一致的 typed fact frame admission byte 估算，snapshot 编码大小只进入独立的 `snapshotBytes`。这里一个
fact frame 表示一个已被 Product Core 接受并完成 owner commit 的业务
操作；它不是 HTTP request，也不能和 fills/trades 混为一个 TPS 数字。

输出包含无 profiler 的主 JMH 结果、`-prof gc` 结果、原始 JFR、GC/safepoint 日志、NMT
baseline 与 `summary.diff`。JFR analyzer 生成 `aggregate.json`：top CPU/wall method/stack/thread、
allocation/op、GC pause/live-set、native/direct、lock/park、safepoint/VM op、JIT/code cache、
I/O/exception、system/container、thread start/end/state/peak 和逐 business type 三段延迟。
allocation/op 的 terminal operation 分母同时读取 owner、scale workload 与 saturation JFR 事件，
并保留 operations-per-invocation 参数来源，不能因非 owner recording 而输出 null。健康的 0 I/O/0 exception 不失败；
JFR DataLoss 或容器 CPU throttling 会使该轮无效。

线程时间按 thread 与完整语义角色（owner、matcher、risk、snapshot、projection、Core Fact/exporter、
Aeron、Kafka、peripheral、lane、GC、compiler）分别报告 RUNNABLE/BLOCKED/WAITING/PARKED 的 nanos
与占比。owner 命名线程仍按执行栈细分 matcher/risk/snapshot/projection/export 等实际工作；每个必需
角色必须是有观测值的 `OBSERVED`，或携带原因的显式 `N/A`，任何 unclassified/all-other 事件都会
使验收失败。四个 qualification recording 全部使用同一个
`config/owner-commit-profile.jfc`；该 JFC 同时把 ExecutionSample 和 NativeMethodSample period 明确
固定为 20 ms。RUNNABLE 优先使用 execution/native event 自带的 duration/period；事件未携带该元
数据时使用 analyzer 从同一个 JFC 读取的 period，且事件元数据必须与 JFC 值一致。单独运行 analyzer
时必须通过 `JFR_SETTINGS_FILE` 指向录制时实际使用的 JFC；manifest 保存文件路径、SHA-256 和解析后
period，`settings=profile` 或一个未绑定 recording 的环境常量都不能作为周期证据。
阻塞、等待、park 和 sleep 使用事件真实 duration。包含 `Thread.onSpinWait` 或
BusySpin 栈的 sampled CPU nanos、RUNNABLE 占比和线程清单独立输出，不能混在普通业务热点中。

异常与 I/O 是两个独立门禁：`JFR_MAX_EXCEPTIONS` 默认 0；owner 语义角色的同步 File/Socket I/O
由 `JFR_MAX_OWNER_SYNC_IO_EVENTS` 和 `JFR_MAX_OWNER_SYNC_IO_BYTES` 控制，二者默认也为 0。
只有显式设置非负阈值才能放宽，aggregate 分别保留 exception throw site/type/thread、全部 I/O 和
owner 同步 I/O 的 events/bytes/duration/top methods。

allocation 使用 `ObjectAllocationSample.weight`、new-TLAB 的 `tlabSize` 和 outside-TLAB 的
`allocationSize` 作为采样权重计算 bytes/s、bytes/op 和 top types/sites/threads。JFR 采样事件数不等于
对象数，因此 exact object count 不可用时 objects/s、objects/op 必须为 null，并携带 `N/A` 采样说明；
事件缺少精确 object size 时最大对象也必须为 null+原因，禁止静默写 0。heap 输出 committed/used
峰值与时间序列、after-GC live set；GC 输出
cause、总 GC 时间及 recording ratio、pause p50/p90/p95/p99/p99.9/max、最长 phase，以及 ZGC
allocation stall、promotion/evacuation failure 和 degeneration 信号。

Safepoint 聚合包含 reason、总/最长 pause 及 time-to-safepoint 的 p50/p90/p95/p99/p99.9/max；VM
operation 独立报告 count、duration、top、longest 和线程。JIT 聚合包含 compile count/duration/top/
longest、failure details、code cache、deoptimization、class load/unload、metaspace 序列/峰值和 threshold
事件。code-cache、metaspace、deoptimization、class-load/unload 四个必需 telemetry family 都必须
在 JFC 中 enabled，并根据 recording metadata 校验 JVM capability：支持时必须有真实事件并标为
`OBSERVED`；JVM metadata 明确不支持时才允许带 capability 原因的 `N/A`；未配置、unknown 或
配置/支持但零事件均失败，不能用 `>= 0` 通过空 telemetry。

JFR 归因轮使用同一个 `-f 0` 进程覆盖全部 owner benchmark，避免多个 JMH fork 覆盖同名 JFR/NMT
文件；它只用于归因，主吞吐仍来自独立 fork 的无 profiler 结果。每次 event 为三段延迟携带 64 个
log2 histogram bucket count；analyzer 先按 business type 合并全部 invocation 的 bucket count，再从
合并分布计算 p50/p90/p95/p99/p99.9，max 使用真实 invocation max 的全局最大值。禁止把 invocation
quantile 的中位数称为全局样本 percentile；`quantileAggregation=MERGED_LOG2_HISTOGRAM_COUNTS`
是 qualification 的强制合同。

`all` 模式依次运行统一 Maven 精确测试、probe、无 profiler JMH、GC、JFR/NMT、固定 256 in-flight 的
saturation、owner commit 和 40 分钟 soak。单 matcher SPSC pipeline 没有 Disruptor/projection wait strategy；旧环境参数不参与
生产主链路。soak 至少要求 3 个 GC 后样本，检查 live-set、direct/mapped、
线程、文件描述符和 buffer-pool balance 的增长斜率。post-GC 点来自 HotSpot GC notification
中的 GC id 与 `memoryUsageAfterGc`，不再从采样窗口内 collection count 变化推断；所有指标使用
至少 3 个真实 GC 完成点的 Theil–Sen 稳健线性斜率。怀疑泄漏时设置
`SCALE_SOAK_OLD_OBJECT_PATHS=true`：脚本会从基础 JFC 确定性生成并 XML 校验本轮
`owner-commit-oldobject.jfc`，只给 soak 绑定 `jdk.OldObjectSample` 与 `path-to-gc-roots=true`。
analyzer/manifest 记录 effective JFC、SHA-256、escalation 与证据状态；JVM 支持时要求至少一个真实
OldObject sample，不支持时只允许 recording metadata 支撑且带原因的 `N/A`。默认关闭时基础 JFC
不含 OldObject 事件。OldObject/path-to-roots 会显著增加采样、dump 和 root-path 分析开销，只用于
斜率异常后的升级诊断，不能与基础吞吐轮直接比较。

10k users/128 symbols/固定 256 in-flight 的 sustained saturation 使用 100k/s constant-arrival 调度时间戳，
在 Harness 的真实 `state.apply` 入口和 owner terminal 返回边界分别打点。期末 matcher command/completion backlog
必须为零；Core 内部 SPSC pipeline 和驱动端共同执行真实的 256 in-flight 上限，owner 每轮最多收割 64 个连续完成结果。
scale mixed workload
同样只在 JFR event enabled 时启用 open-loop recorder，并通过无 `default` 的 `CoreMessageType`
穷举 switch 分类为 PLACE_ORDER、CANCEL_ORDER、AMEND_ORDER、ORDER_BATCH、TRIGGER_ORDER、
RISK_SCAN、LIQUIDATION、FUNDING、ADL、SETTLEMENT。TAKER_FILL 只允许由真实 fill terminal
边界产生，SNAPSHOT_RECOVERY 只允许由真实恢复验证边界产生；当前两个场景均未接入这两种独立
边界，因此明确标为 `NOT_EXERCISED`，不能从 PLACE_ORDER 或 snapshot 命令名伪造。每类独立输出
entry→accepted、accepted→terminal、entry→terminal 的
p50/p90/p95/p99/p99.9/max、samples、histogram range、timeout、NANOSECONDS 与 coordinated-omission
状态。qualification 为上述 12 种业务逐项声明 `EXERCISED` 或 `NOT_EXERCISED`，观测 event 必须与
场景合同精确一致，缺失、多出或合并分类均 fail closed。latency samples 对应 terminal Core messages；
batch 的 constant-arrival schedule 按 operation weight 推进，event 必须证明
`scheduledBusinessOperations == terminalBusinessOperations`。batch 展开的 terminal business
operations 保持独立，top-level workload terminal denominator 排除这些 latency event，避免把
business ops、Core messages 或 fills 重复相加。

端到端容量仍以同一脚本的 saturation/scale 场景报告 requests、terminal business operations、
Core messages、fills/trades、backlog、尾延迟及资金不变量；内部 owner commit 分数只用于定位
seal/journal/index/hash/projection/encode/snapshot 的阶段容量，不能替代 API TPS。

强平专项由 `LinearPerpetualCoreBenchmark` 的六个真实状态机场景覆盖：

- `multiUserLiquidationPlanning`：同一标记价批量触发多用户爆仓并完成有界 Lane risk continuation；
- `liquidationManyOrderCancellation`：一个待强平持仓带 256 个合法 reduce-only 活动订单，测量 cursor 撤单及结算；
- `liquidationBurst256`：每个 invocation 提交一个原生 `EXECUTE_LIQUIDATION_BATCH`，持续处理 256 个强平 business item；
- `insuranceShortfallAllocation`：256 个未决 deficit 共享不足的保险基金，查询确定性建议份额；
- `insuranceToAdl`：按建议消耗保险份额后执行真实 `EXECUTE_ADL`，要求 deficit 清零；
- `liquidationWithTrading`：1,000 活跃用户、256 活跃 symbol；每个撮合阶段内部严格保持最多 256 个 matching Core message
  在途，按订单依赖和全局 sequence 在阶段边界提交；每轮固定选择 32 个 symbol 执行触发单、资金费/mark/risk，且真实完成一次
  强平、保险和 ADL。结果分别报告 trading 与 lifecycle terminal business operations，不能把控制流同步边界隐藏在总吞吐中。

这些 JMH teardown 均验证资金/订单/强平终态和完成态 snapshot 恢复。`liquidationBurst256` 的主分数是 batch/s，
必须使用 AuxCounters 的 `terminalBusinessOperations` 作为展开后的强平 items/s；不得把 batch/s 当成业务吞吐。

`tests` 模式覆盖 typed fact frame/service 财务矩阵、六产品线 V17
`SharedProductLineSnapshotContractTest`、protocol V10、exporter（包含 JDBC/Postgres projector）、
gateway fanout consumers、market-data `CoreMarketDataProjectionTest` 和 benchmark 小规模真实场景。
service 清单还显式覆盖 delivery option 财务矩阵、treasury、perpetual funding/fill、产品线架构、
trading snapshot 与 fee-policy snapshot codec。protocol、service、exporter、gateway、market-data 和
benchmark 六个 Maven 目标各自声明精确 class CSV；每次 invocation 前创建独立 start marker，完成后
全部调用同一个 `verify-surefire-reports.sh`。verifier 读取 XML `testsuite/@name`，按完整 FQCN 或
点号分隔后的完整简单类名匹配，拒绝文件名/后缀碰撞、零匹配和多匹配，并要求唯一报告严格晚于
本轮 marker、tests>0 且 failures/errors/skipped 全为 0。每模块生成独立 TSV；因此
`failIfNoSpecifiedTests=false` 只用于 reactor 依赖模块，不能用旧报告或漏跑目标类制造绿色结果。
`test-verify-surefire-reports.sh` 是纯 shell/XML 回归入口，覆盖 stale green、suffix collision、
multi-match、missing 与 fresh exact FQCN/简单类名成功场景，不会启动 JVM。


## 现货批量改单结算与故障等待回归

`ClusteredBatchTradingBenchmark.batchAmendRoundTripTrades` 固定256请求波次，使用真实服务日志回调、matcher和Account Lane完成双向批量改单成交；`batchSize=2`时每cycle2560业务操作、1536 Core消息、1024批次/2048条目及1024成交。SPOT用户卖出全部持有资产后改单，必须复用原单冻结；六产品线teardown均校验用户/maker资金、冻结、持仓、终态回收和快照恢复。采样参数、结果和未测范围统一见根目录 `PERFORMANCE_VALIDATION.md`，本地闭环fixture不能代表生产API/WS尾延迟或无内存泄漏。
# 连续单节点性能诊断补充

`ClusterOperationalBenchmark.continuousOperations` 的 `inFlightWindow=64/128` 必须与节点的 `surprising.aeron.owner-command-window` 一致。`tradingProfile=MIXED` 保持原混合交易组成；`FILL_HEAVY` 将卖出 IOC 提前到买方做市单撤销前，同样的请求数产生双倍成交，检查周期末做市与吃单账户持仓归零。后者要求 `surprising.aeron.mixed-trading-stream=true`、`surprising.aeron.mixed-operational=false`。两个场景分别统计，不能混为一种吞吐结果。

压测器在测量前后通过真实 Cluster 查询 Lane 计数，输出 `laneWork` 中各 Lane、各操作类型的执行次数和累计执行纳秒差，以及 `pipelineHighWater`。这些查询含有控制边界，位于计时之外；累计执行时间包含线程调度和停顿，需结合线程 CPU/JFR 判断业务饱和，不能直接当成 CPU 利用率。Owner 的待完成命令和响应背压不再使无进展轮询报告有效工作。Matcher 使用声明休眠后重查队列的通知握手。

Owner 的入口和 Matcher/Lane 完成通知采用声明休眠后重查的合并唤醒；共享通知标记与 Owner 每轮修改的退避计数隔开。有效工作计数只表达实际进展，高负载下 Owner CPU 仍可能接近一核，不能据此认定全部时间都在处理业务。

在途命令的连续轮询与有效工作计数分开：`pollCommands()` 无实际进展时返回0，但 Owner 在仍有在途命令时继续推进，只有完全空闲才退避。等待路径不在每次流水线重查之间额外插入 `onSpinWait`、yield 或 park；降低等待CPU不能以损失吞吐和尾延迟为代价。

`ClusterTriggerBoundaryBenchmark.triggerOnOwningLane` 连接真实单成员，128币对交替执行IOC无成交/成交减仓及OCO取消，检查余额、保证金、持仓和触发终态；`SmallControlReproMain verify-trigger` 可在同数据重放/快照重启后复核。JMH值包含连接、建仓及查询，不能作为稳态吞吐或纯触发延迟。

`ClusterOperationalBenchmark.continuousOperations` 的 `batchSize=1/20` 分别覆盖单项与多项批量下单；真实场景固定128币对，批次/展开业务项/Core消息/成交分别计数。`ClusterMixedCapacityMain` 直接入口及恢复核对可通过 `surprising.aeron.capacity-batch-size=1` 选择单项（默认20），恢复必须与原轮一致。

`ClusterTriggerBoundaryBenchmark.scannedTriggerOnOwningLane` 使用相同128币对金融场景，通过标记价更新及预算1风险续页触发订单，覆盖内部扫描到所属Lane的实际路径；复用 `verify-trigger` 在快照重启后核对。该SingleShotTime包含初始化、风险扫描及查询，不是持续吞吐测试。


`ClusterSequentialBatchBenchmark.proceedsAndAmendOnLane` 连接全新真实单成员 SPOT Cluster，验证128币对顺序批项资金依赖、GTX原生拒绝/余额不足拒绝、改单冻结复用及撤单解冻。SingleShotTime包含初始化与查询，不是持续吞吐；恢复后可用同类`main verify`重新校验全部账户。完整执行参数及JFR结果追加到根目录`PERFORMANCE_VALIDATION.md`。

`ClusterAdlBoundaryBenchmark.insuranceAndAdlOnOwningLanes` 复用 mixed 初始化，真实单节点执行亏损强平、部分保险与显式 ADL，并核对全体账户。标准128币对、窗口256；专用亏损账户在末币对执行一次保险/ADL，其余币对核对隔离。SingleShot 包含初始化与查询，不能作为持续吞吐或纯ADL延迟；配合节点JFR、有效snapshot及重启后 `ClusterMixedCapacityMain` verify-only 核对。统一记录见根目录 PERFORMANCE_VALIDATION.md。

`ClusterTriggerBoundaryBenchmark.rejectedTriggerOnOwningLane` 在128币对建立持仓和maker买单，再执行GTX跨价触发拒单；核对触发失败、placedOrderId=0、解冻、原持仓、maker挂单保证金及OCO取消。快照重启后使用 `SmallControlReproMain verify-trigger-reject` 重查。该SingleShot包含初始化和查询，不作纯拒单延迟或持续吞吐指标。

`ClusterTriggerBoundaryBenchmark.rejectedAmendOnOwningLane` 覆盖128币对的撤旧下新拒绝：用户自成交前置单与原单取消，新GTX买单拒绝；核对原单CANCELED响应、用户无预留且资金全释放、对手卖单预占11、无成交持仓。重启用 `SmallControlReproMain verify-amend-reject`；含初始化/查询的SingleShot不是稳态吞吐指标。

- `ClusterTriggerBoundaryBenchmark.amendCancelCycleOnOwningLane`：真实单成员128币对，依次核对GTX改单拒绝的已知撤单前缀、GTC改单成功、普通撤单和批量撤单；最终账户资金恢复初值、冻结及持仓清零。`SmallControlReproMain verify-amend-cycle`用于同Archive快照重启后的账户核对。SingleShot包含初始化/查询，不代表持续吞吐。

- `amendCancelCycleOnOwningLane`现同时覆盖两笔批量Place准入，最终批量撤销新改单和两笔准入订单；每币对双方余额恢复10000、冻结0、持仓0。控制提交异步化另以`ClusterAdlBoundaryBenchmark`覆盖强平/保险/ADL及快照恢复。

- `amendCancelCycleOnOwningLane`继续覆盖成功REPLACE（旧单CANCELED、新单OPEN）和不存在订单撤单（REJECTED/ORDER_NOT_FOUND），用于普通订单准备去除全Lane借权后的网络验证；两笔准入及三笔批量撤单逐项校验APPLIED。

- ClusterAdlBoundaryBenchmark 的有限生命周期验证增加专用亏损账户的三笔 reduce-only 挂单，以每次 maxCancelOrders=1 完成三页撤单/强平，随后核对保险、ADL、全部账户资金和快照恢复。此专用场景变更不修改常规连续吞吐场景；其 SingleShot 耗时包含初始化和查询，不作为稳态吞吐。

- 上述三页强平诊断在 JMH 客户端及恢复验证客户端均设置 `-Dsurprising.aeron.mixed-loss-balance=110`，给新增挂单预留冻结资金；全体初始资金为 1384000000135。常规场景默认亏损账户仍为100，原资金口径不变。


`ClusterDirectSettlementBenchmark.matcherToLaneLifecycle` 专门覆盖 Matcher→Lane 直接结果：连接外部真实单成员，六产品线分别在128币对、256账户上交替普通单与20项批量，双方开仓后反向平仓。每组交易前更新有效行情；交易命令在64窗口内异步提交。每次调用包含86016订单业务项和2048行情业务项，另报告10240Core消息、43008fills。逐用户逐资产核对余额、冻结、净持仓、预留和活跃订单；其 `main <ProductLine>` 用于Archive快照重启后的同一状态核对。JMH生命周期耗时和带profile分配用于路径诊断，容量使用 `ClusterOperationalBenchmark` 的持续流口径。所有参数、失败轮次、JFR与恢复结论集中记录于根目录 `PERFORMANCE_VALIDATION.md`。


`ClusterDirectSettlementBenchmark` 同时验证客户端标识的 Lane 回收：六产品线、128 币对的普通/批量成交后，逐账户按最后一个 clientOrderId 查询运行态索引，核对终态已移除并返回 ENTITY_NOT_FOUND。核对不计入交易业务项。快照重启验证入口可传第二个参数（最后一个订单 ID），按相同生命周期重建待查询标识；标准 wi1/i2 共三次调用为 `258048`。该核对避免只验证余额而遗漏终态标识丢失。
