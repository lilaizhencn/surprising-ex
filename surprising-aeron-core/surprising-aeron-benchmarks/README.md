# Product Core 性能验证

本模块的 `OwnerCommitPatchBenchmark` 独立测量撮合完成后的 typed owner commit 路径：patch
seal/publish/apply、四 Account Lane 的九索引 fanout、增量 hash 与 canonical recompute、批量
projection/Core Fact V10 编码，以及 snapshot/recovery。五个测量体都真实处理 16,384 个连续
`RuntimeCommitPatch`，并以 1,024 个 patch 为 admission/fanout/projection 窗口；JMH 使用
`@OperationsPerInvocation(16384)` 按逻辑 owner commit 归一，不再使用固定 64 条装饰性 batch。
Core Fact 场景将 typed patch 交给 `CoreExportState` 的异步 materializer，并从其完成后的 encoded
`CoreMessage` 统计 V10 bytes；owner benchmark 不直接构造或读取 Core Fact payload。
增量 hash 分支在测量区逐 operation 执行 `RollingBusinessStateHash` 与 `RollingFundsStateHash`
的 prepare/transition/commit，canonical 分支则逐 patch 冻结当前 projection 并执行两类 full
compute，避免复用 setup 阶段的最终 hash。snapshot 场景只有在 decode、runtime restore、重新
materialize 以及完整 state/hash 等价检查全部完成后才记录 terminal。

验收入口：

```bash
SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh owner-commit
```

脚本同时检查并保存 `java -version` 和 `mvn -version`，拒绝 OpenJ9、非 JDK 25 或非
HotSpot-compatible VM，默认使用 ZGC，并固定 10,000 活跃用户、512 个活跃 symbol、4 个
Account Lane、每用户 5 个持仓和 10 个未成交单、1,024 max in-flight、16,384 operations per
invocation。owner 场景使用 100,000 ops/s 的 open-loop constant-arrival 计划时间，入口延迟从
计划到达时刻计算，因此包含排队并修正 coordinated omission。每个 benchmark/business type
分别输出 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max。
1,024 in-flight 验收同时固定 256 MiB Core Fact reservation 上限；服务默认仍为 64 MiB，生产部署
必须按最大并发命令的 `FactCostEstimate` 总和配置，不能依赖事件数上限代替字节门禁。

AuxCounters 同时输出 terminal business ops、terminal Core messages、fills/trades、batch/items、
accepted-terminal gap、unfinished、最大/期末 backlog、reject/error/timeout、patch items/bytes、
snapshot bytes、Core Fact encoded bytes、总/最大 batch size。`patchBytes` 是与 commit journal
一致的 typed patch admission byte 估算，snapshot 编码大小只进入独立的 `snapshotBytes`。这里一个
patch 表示一个已被 Product Core 接受并完成 owner commit 的业务
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

`all` 模式依次运行统一 Maven 精确测试、probe、无 profiler JMH、GC、JFR/NMT、BUSY_SPIN 与
YIELDING（均为 max-in-flight 1,024）的 saturation、owner commit 和 40 分钟 soak。等待策略可用
`MATCHER_WAIT_STRATEGY`、`PROJECTION_WAIT_STRATEGY` 和逗号分隔的
`SATURATION_WAIT_STRATEGIES` 切换。soak 至少要求 3 个 GC 后样本，检查 live-set、direct/mapped、
线程、文件描述符和 buffer-pool balance 的增长斜率。post-GC 点来自 HotSpot GC notification
中的 GC id 与 `memoryUsageAfterGc`，不再从采样窗口内 collection count 变化推断；所有指标使用
至少 3 个真实 GC 完成点的 Theil–Sen 稳健线性斜率。怀疑泄漏时设置
`SCALE_SOAK_OLD_OBJECT_PATHS=true`：脚本会从基础 JFC 确定性生成并 XML 校验本轮
`owner-commit-oldobject.jfc`，只给 soak 绑定 `jdk.OldObjectSample` 与 `path-to-gc-roots=true`。
analyzer/manifest 记录 effective JFC、SHA-256、escalation 与证据状态；JVM 支持时要求至少一个真实
OldObject sample，不支持时只允许 recording metadata 支撑且带原因的 `N/A`。默认关闭时基础 JFC
不含 OldObject 事件。OldObject/path-to-roots 会显著增加采样、dump 和 root-path 分析开销，只用于
斜率异常后的升级诊断，不能与基础吞吐轮直接比较。

10k users/512 symbols/1,024 in-flight 的 sustained saturation 使用 100k/s constant-arrival 调度时间戳，
在 Harness 的真实 `state.apply` 接受边界和 matching terminal 边界分别打点。scale mixed workload
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

`tests` 模式覆盖 typed patch/service 财务矩阵、六产品线 V17
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
