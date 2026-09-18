# 性能验证记录

## 2026-09-18：e9692272 短时真实 Core 压测

### 锁定计划

- **目标**：验证最新 `master` 在 Owner、实时日志改造和快照包调整后，真实 Aeron Core 的短时交易链路仍可启动、持续处理并正确排空。
- **门槛**：单节点、单 matcher、全局 in-flight 固定 `256`；测量窗口内 `accepted == terminal`、Core 消息 accepted/terminal 相等、未完成量为零、资金差异为零、盘口为空、客户端无错误。
- **场景**：`LINEAR_PERPETUAL`，`MATCH_STREAM`，128 symbols，100 users，4 workers/4 connections，买卖 GTC 流；不启动 wallet。
- **时间**：预热 5 秒，正式测量 15 秒；当前机器只运行一个真实 Aeron Cluster 成员。
- **环境**：commit `e9692272`；Corretto HotSpot JDK 27.0.0.33.1；Maven 3.9.16；macOS x86_64；测量前磁盘可用约 420 GiB。

### 执行命令与偏差

```text
JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -q \
  -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package

java [JDK 27 Aeron opens/exports] \
  -Dsurprising.aeron.product-line=LINEAR_PERPETUAL \
  -Dsurprising.aeron.hostnames=127.0.0.1 \
  -Dsurprising.aeron.account-lanes=4 \
  -Dsurprising.aeron.matching-engines=1 \
  -Dsurprising.aeron.owner-command-window=256 \
  -cp surprising-aeron-service/target/surprising-aeron-service.jar \
  com.surprising.aeron.service.SurprisingCoreApplication

java [JDK 27 Aeron opens/exports] \
  -Dsurprising.aeron.product-line=LINEAR_PERPETUAL \
  -Dsurprising.aeron.hostnames=127.0.0.1 \
  -Dsurprising.aeron.egress-hostname=127.0.0.1 \
  -Dsurprising.aeron.capacity-workload=MATCH_STREAM \
  -Dsurprising.aeron.capacity-symbol-count=128 \
  -Dsurprising.aeron.capacity-workers=4 \
  -Dsurprising.aeron.capacity-connections=4 \
  -Dsurprising.aeron.capacity-user-count=100 \
  -Dsurprising.aeron.capacity-async-in-flight=256 \
  -Dsurprising.aeron.capacity-warmup-seconds=5 \
  -Dsurprising.aeron.capacity-duration-seconds=15 \
  -cp surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  com.surprising.aeron.benchmarks.workload.ClusterCapacityMain
```

首次把 Core 作为后台进程启动时，执行环境在启动命令返回后回收了该进程，客户端得到 `NOT_CONNECTED (offer=-1)`；该次结果标记为**无效**，没有计入吞吐。改用保持前台会话的同一 Core 配置重跑后得到以下有效结果。

### 有效结果

- Core 启动约 0.74 秒，成为 `LEADER`；客户端退出码 `0`。
- 正式窗口 `15.006s`：`452,638` accepted，`452,638` terminal；Core terminal messages `454,534`（其中行情消息 `1,896`），未完成请求 `0`，失败 `0`，重试 `0`。
- 持续终态业务吞吐：`30,162.921 ops/s`；成交数 `226,319`，成交事件约 `15,081.461/s`。
- in-flight：最大 `256`，平均占用 `252.714`；提交数/完成数均为 `454,534`。
- 端到端请求延迟：p50 `8,003µs`，p99 `15,048µs`，p99.9 `26,476µs`，max `71,041µs`；买卖两侧 p99 分别为 `15,040µs` / `15,056µs`。
- 资金与状态：`fundsDiff=0`、`bookLevels=0`；满足本轮正确性门槛。

### 归因证据与限制

- 节点 JFR 记录约 86 秒，包含启动和测量前后空闲阶段，不能把整段 CPU 百分比直接当作正式窗口 CPU。热方法聚合中 `SettlementLaneWorker.run()` 占 execution samples 的 `87.46%`，需在更长稳定轮次中进一步确认是否为实际瓶颈。
- 节点 JFR 观察到最长 G1 pause `60.7ms`，另有 `13.7ms`；客户端最长 pause 约 `4.64ms`。本轮没有做停顿与单笔尾延迟的严格时间窗关联，因此不据此认定 p99 根因。
- 节点 NMT 相对负载前 baseline：reserved `+7,637KB`，committed `+15,601KB`；线程 `+5`。这是短窗口增量，不足以证明长稳无泄漏。
- 快照/恢复专项：`CoreStateSnapshotCodecTest` 与 `RuntimeCommitRecoveryTest` 共 `22` tests，`0` failures/errors/skips。

### 证据与清理

- 有效轮原始目录：`target/short-pressure/20260918T-short-e9692272-rerun/`，总大小约 `254M`。
- 关键文件校验：`node/node.jfr` SHA-256 `c78be77cf0a78027a5b02510f3ab4f50332277d2cd080f551504d940b0046bd3`；`client/client.jfr` SHA-256 `eb3cff24505293b2487df5bee2f8b8aaef171371150ad5ad2dba497abce34d4`；`client/client.log` SHA-256 `ccf515f7c8cd28bb7e357f06076e19b68336f9815d255aa79ae75e055d9a9156`。
- 首次无效轮原始目录：`target/short-pressure/20260918T-short-e9692272/`，总大小约 `258M`；原因已记录在“执行命令与偏差”。
- 清理状态：Core、客户端和 Aeron 进程均已停止；本轮临时目录、Archive/CNC、JFR、NMT、线程转储和日志在本记录完成后移入用户回收站，不保留工作区临时集群。
- 结论：**通过（短时功能/正确性压测）**；不是容量验收，也不能外推三节点云端容量或长稳表现。

## 2026-09-18：Owner FIFO 调度减法（owner-simplification）

### 采集前计划

- 目标：删除单元素前缀状态，连续 ready 队首复用公共推进，按实际依赖末项解除入站阻塞；不改每命令资金、日志、发布和恢复边界，不新增队列或索引。
- 当前 master 工作树验证，对照 commit 不适用；工作区包含上一轮诊断改动，不能把本轮绝对吞吐或历史差值当作优化收益。JDK/Maven 均为 HotSpot Corretto 27，macOS x86_64；初始磁盘剩余 327 GiB，不启动 wallet。
- 正确性门槛：六产品线 FIFO/批量/恢复及连续 ready 回归无失败，accepted 与 terminal 相等，无缺失终态，资金及恢复状态一致。影响公共 Owner，扩大至 service、tools、benchmarks 的 Maven 测试。
- 局部性能：ContinuousOwnerBenchmark 增加 DISTINCT/PAIRED 账户模式，固定在途上限 256、1 matcher、batch20、BUSY_SPIN Lane；六产品线分别测。主分数单独运行，另跑 GC/JFR 诊断，不替代真实 Cluster 容量；每 fork 独立产物。预热/测量各 3x2s，1 fork/1 thread；短轮仅作成本诊断，未证明 JIT 长期稳定或长稳。
- 性能无业务验收下限，本轮属探索诊断；不承诺恢复历史 41.5 万。需要报告样本/置信区间，不能用单次分数宣称提升。
- 纠正前轮归因：lanesCompleteToOwner 实际采样在有序提交入口，包含 FIFO 等待，不是纯唤醒/通知延迟；此前 mailbox 单次差值不能证明稳定收益。

### 实现与功能验证

- `OwnerCommandPipelineState` 用窗口条目引用表达当前提交队首及真实依赖末项；删除 drainingSize、复制的 drainingSequence、重复 waitingPrefixSequence 和 drainedWindows。依赖末项退休前清引用，防止环形槽复用。等待标记在队首退休时失效，保留派发上限/进度/通知变化检查。
- `TradingCoreOwner` 每轮首次或新准入后推进公共工作，随后连续 ready 队首跳过全窗口收集；未 ready 队首仍推进激活/派发/续接。`OrderedCommitCoordinator` 每命令发布作用域保持不变，提交上限明确为 1；不新增框架、队列、索引或快照。
- 首轮完整模块测试发现 `LinearPerpetualSaturationWorkload.verify` 把 Owner-only 结算派发峰值用于 Matcher 直达路径：实际值 0，4096/4096 命令、2048/2048 成交、资金 80000000125/80000000125 均一致。该指标不覆盖直达派发，修正为真实 backlog 的并发/上限检查；原多 Lane、完整终态、资金、订单/身份数量核对保留。中间精确重跑仍使用修正前已编译 benchmark，记录为再次失败，未作为通过证据。
- 最终命令：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`；HotSpot JDK27，最终全部 reactor 成功，1450 项测试，失败/错误 0，条件跳过 3；其中 service 928、benchmarks 289。新增六产品线连续 ready 单轮退休/逐命令响应/快照恢复、PAIRED 同账户依赖及资金恢复，状态单测覆盖真实 blocker、控制 fence、槽复用、零撮合序号与等待失效。未启动 wallet。
- 日志历史路径：`/tmp/owner-simplification-tests.log`（初步精确回归）、`/tmp/owner-simplification-reactor.log`（首轮完整失败）、`/tmp/owner-simplification-final-tests.log`（中间精确回归）、`/tmp/owner-simplification-verified.log`（最终完整通过）。

### 局部性能结果与复现

- 基准源码基于 `7fffebb51c6f569d27ed7d748ac972999f04b71c` 的当前工作树；service/benchmarks 已跟踪源码 diff SHA-256：`40f94a1ee95d64b71979fedeae546abf051f5546c501fe8c8b988ddb902a73da`，包含前轮未提交诊断，不能解读成单改动收益。
- 本机 MacBookPro16,1，16 logical CPU、16 GiB；没有绑核。以下是内存服务+真实 Owner 线程的闭环下单/撤单基准，**没有真实网络/Archive，也不是 MATCH_STREAM 或 MIXED 成交吞吐**。每 invocation 完成 512 Core messages、10240 business operations；JMH 主分数单位 invocation/s，辅助 EVENTS 是每 iteration 计数而非速率。
- 运行 `bash /tmp/owner-simplification-jmh.sh`，产物根 `/tmp/owner-simplification-jmh-20260918`。完整等价命令如下（JFR 文件 `%p` 为每 fork PID，三个进程顺序运行）：

```bash
JAR=surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar
OUT=/tmp/owner-simplification-jmh-20260918
ARGS='-Xms512m -Xmx512m -XX:+UseG1GC --enable-native-access=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.owner-command-window=256'
java -jar "$JAR" ContinuousOwnerBenchmark.placeCancelWithoutTimers -p batchSize=20 -p laneWaitStrategy=BUSY_SPIN -wi 3 -w 2s -i 3 -r 2s -f 1 -t 1 -foe true -jvmArgsAppend "$ARGS" -rf json -rff "$OUT/main.json"
java -jar "$JAR" ContinuousOwnerBenchmark.placeCancelWithoutTimers -p batchSize=20 -p laneWaitStrategy=BUSY_SPIN -p accountPattern=DISTINCT -wi 3 -w 2s -i 3 -r 2s -f 1 -t 1 -foe true -prof gc -jvmArgsAppend "$ARGS" -rf json -rff "$OUT/gc.json"
java -jar "$JAR" ContinuousOwnerBenchmark.placeCancelWithoutTimers -p batchSize=20 -p laneWaitStrategy=BUSY_SPIN -p accountPattern=DISTINCT -wi 3 -w 2s -i 3 -r 2s -f 1 -t 1 -foe true -jvmArgsAppend "$ARGS -XX:StartFlightRecording=settings=profile,filename=$OUT/owner-%p.jfr,maxsize=64m,dumponexit=true" -rf json -rff "$OUT/jfr.json"
```

主分数 ± JMH 99.9% 误差（每场景 3 个 measurement 样本）：

| 产品线 | DISTINCT invocation/s | PAIRED invocation/s | 独立 GC 轮 bytes/business op |
| --- | ---: | ---: | ---: |
| SPOT | 97.103 ± 44.151 | 50.883 ± 32.593 | 2173.88 |
| LINEAR_PERPETUAL | 99.997 ± 9.652 | 51.415 ± 18.313 | 2172.42 |
| INVERSE_PERPETUAL | 98.290 ± 14.950 | 51.136 ± 10.523 | 2177.57 |
| LINEAR_DELIVERY | 96.137 ± 7.383 | 51.712 ± 10.719 | 2173.92 |
| INVERSE_DELIVERY | 102.485 ± 9.583 | 50.745 ± 5.530 | 2174.43 |
| OPTION | 99.157 ± 37.174 | 51.668 ± 2.754 | 2177.37 |

- 所有场景 accepted=terminal，期末 pending=0，响应必须 APPLIED；每 fork teardown 校验撤单后余额/冻结与快照恢复。DISTINCT Owner 在途峰值 238–256；PAIRED 峰值 2（同账户真实依赖，不是把全局配置改为 2），不得声称每个场景都让 Core 满载。两种模式差值不等于此次代码优化收益。
- GC 轮每场景三个 measurement 合计 44–46 次 GC、156–166ms GC time，bytes/op 含发压构造与响应编解码，不可归为 Owner 独有分配。JFR 轮 invocation/s 依产品顺序为 94.177、93.834、87.988、89.535、95.001、90.282，不替代主分数；与无 profiler 差异受单 fork 波动影响。
- JFR 使用 JDK `profile`，六份各约 3 MiB，限制 64 MiB。`jfr summary`：全部 DataLoss=0；ExecutionSample 分别 5384/5366/5379/5394/5566/5362；ObjectAllocationSample 3680/3820/3774/3782/3829/3786，ThreadAllocationStatistics 38/39/40/38/39/38；TLAB/OutsideTLAB 精确事件均为 0，不能据采样推算精确对象数。
- JFR 起始 UTC/PID/时长：SPOT 14:44:08/768/13s；U永续 14:44:22/920/13s；币永续 14:44:36/999/13s；U交割 14:44:50/1108/14s；币交割 14:45:04/1202/14s；期权 14:45:19/1268/13s。录制包括预热、测量与 teardown，不作为同窗稳态因果证据。
- `jfr view hot-methods owner-920.jfr`：SettlementLaneWorker.run 占全线程样本 45.77%，包含 BUSY_SPIN，不代表有效业务算力。用 `jfr print --json --events jdk.ExecutionSample` 筛选 trading-owner-0 后共 763 样本；top 为 Long2ObjectHashMap.getMapped 61、remove 32、LongHashSet.probe 29、Arrays.fill 28、LongHashSet.compactChain 28、LaneLongCaptures.indexOf 26。含调用栈样本 advanceMatchingProgress 5、commitReadyMatching 20、pollCommandCommit 24、readyPartitionMask 1。仅是本轮全 trial 栈分布，不能据此量化优化前后收益。

原始产物 SHA-256（目录见上，清理后仅作历史定位）：

| 文件 | SHA-256 |
| --- | --- |
| main.json | 1930ca3732971008837510aa275a8dabb25af417a4d180d0d0aa96e94cbabbf4 |
| gc.json | 6943a33cfa0c9f0a3d884aae808d119c0a78058270108c56ed0752dd60c5e543 |
| jfr.json | 2ec5ed34f177d8fcc03951bee7d9b3eebeb30120d05c7c9f2dcf78bfda8a67a3 |
| owner-768.jfr | 8799bb6dc9c04a7d97d9e1796e49dac238d62ff1c195a6497dedc47dd9133d89 |
| owner-920.jfr | be1882686a49eb8de540e921ffbe6cdb5d2eea52c45ea89bc0f22c8c699ea123 |
| owner-999.jfr | 3142c0aca0153f4bccc96f69d907cbc38a7526ed900d7bed38c40cd245a2805b |
| owner-1108.jfr | 68705de2c17499fcb44048f3fb06c8cf85dddf843609da455d7d2938af2fb51b |
| owner-1202.jfr | 48171f90d8b500e1a66fd4d9e37d4588d8dab068bfb6a094006ca6be399bb569 |
| owner-1268.jfr | 91454d20bc94d3761bc2ea02853d8249f7a6ce4f006cec1c66128be1def88e15 |

### 结论与缺口

- 功能回归通过；补充执行 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am '-Dtest=ClusterCommandPipelineTest#sampledSettlementTimesSurviveEventReuseAndCoverOrdinaryAndBatchOrders,ContinuousOwnerBenchmarkTest#recordsQueueResidenceAcrossOwnerAndTransportWithoutChangingResponses' -Dsurefire.failIfNoSpecifiedTests=false -Dcore.settlementLatencyDiagnostics=true test`，2 项采样测试通过。仍未执行的数据库种子测试缺少 `INSTRUMENT_SEED_TEST_JDBC_URL`，不涉及此次 Owner 修改。
- 性能结论为**部分验证**：完成受影响六产品线局部 JMH/GC/JFR 与资金/恢复检查；未做真实 Aeron 单节点满载/网关 API/做市成交验收、分业务端到端尾延迟、NMT/native 长稳与多次独立 fork。没有新增长期容器，仍不能用短轮证明无泄漏或容量提升；不宣称恢复历史 41.5 万。

### 清理状态

- 本轮所有 Maven/JMH fork 已退出；未启动真实 Cluster、Archive 或 wallet。JFR/JSON/日志、临时运行脚本及暂存补丁共约 23 MiB 移入 `/Users/atomex/.Trash/surprising-ex-owner-simplification-20260918/`，可恢复；原 `/tmp` 路径仅作历史定位。保留 Maven 编译产物与项目原有文件，未删除前轮产物；磁盘剩余约 327 GiB。

## 2026-09-18：Owner 减法后真实单节点压测（owner-fifo-core-20260918T145354Z）

### 采集前计划

- 用户要求最新代码压测一轮；只测 master `093183193f3d5bd7da2c8cae43943bbdeb75152b` 工作树。现有 Aeron 未提交改动是前轮 Matcher/Lane/Owner 诊断与等待策略，diff SHA-256 `12e7b18bb73dafdac20a6cabd37b689ec105637d843b81f9045f848529575ecc`；本轮不改交易代码，对照 commit 不适用，不以历史旧版本充当对照。
- 本机 MacBookPro16,1、16 logical CPU/16 GiB、macOS 26.7 x86_64，HotSpot Corretto 27/Maven 3.9.16，起始可用磁盘 327 GiB、swap=0；无其他 Java/Aeron 进程，不启动 wallet。单成员真实 Aeron 网络+Archive，LINEAR_PERPETUAL，1 matcher/4 Account Lanes、128 symbols、MIXED batch20，保持负载内做市/行情刷新。
- 全局和 session 在途上限均为 256，种子 25620；持续异步受背压发压，非无限 open-loop，需报告实际在途与 windowBlocked，不能声称测得计划到达率或排除 coordinated omission。Owner/Matcher mailbox/Settlement Lane 均 BUSY_SPIN，Owner input batch64；不绑核，因此不能外推生产独占核容量。
- node heap512m–1536m/client128m–512m，G1；预热30s、测量60s，排空独立计数，1 fork/1 thread/1 single-shot。先无 profiler 主吞吐一轮，再同配置 JFR 诊断一轮，分别创建新节点和唯一目录；不把 JFR 分数当主吞吐。本轮无预设业务吞吐下限，属于探索验证，单轮不能证明稳定增益。
- 正确性门槛：accepted=terminal、unfinished/backlog 清零、拒绝/错误/超时为0，执行现有 workload 的资金、冻结、持仓、订单终态核对。缺恢复/长稳/指标时明确部分验证，不补造数据。磁盘低于50GiB或发现swap增长/录制DataLoss时标记环境异常。
- JFR 使用现有 owner-commit-profile.jfc，node及client每进程独立文件、各上限256MiB，开启稀疏阶段事件；NMT baseline/end diff，脚本采集线程/分配/GC/锁/停顿；另在运行时检查磁盘、swap和进程资源。单独 gc-profiler 未加到此真实集群驱动，GC/分配由节点 JFR 给出并说明覆盖限制。
- 完整主轮命令：`ASYNC_RUN_ID=owner-fifo-core-20260918T145354Z-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。诊断轮相同命令，RUN_ID 后缀改为 jfr，`ASYNC_ENABLE_JFR=true ASYNC_SKIP_BUILD=true`；其余配置沿用 `config/aeron-single-node-baseline.env`。产物位于该 benchmark 模块 `target/aeron-async-stages/<run-id>/`。

### 主轮结果（不含排空）

| 指标 | 无 profiler 主轮 | 同配置 JFR 诊断轮 |
| --- | ---: | ---: |
| 测量秒数 | 60.019356 | 60.016475 |
| terminal business ops/s | **359443.810** | 359280.832 |
| terminal Core messages/s | **34349.752** | 34333.256 |
| fills/s | **85561.731** | 85523.184 |
| 测量内业务终态数 | 21573586 | 21562769 |
| 测量内 Core 终态数 | 2061650 | 2060561 |
| 测量内 fills | 5135360 | 5132800 |
| peak in-flight | 256 | 256 |
| windowBlocked | 49.911442327s / 474942次 | 50.208172178s / 617443次 |
| 独立排空耗时 | 5.683139ms | 7.433857ms |
| 排空业务/Core终态数 | 2612 / 180 | 2685 / 253 |

- 主轮 10s 区间 business ops/s：347760.897、355834.299、363106.767、347900.299、367638.998、374413.500；约 83.16% 测量时间处于窗口背压等待。峰值256只说明在途上限被打满，不等于排除了客户端或同机资源瓶颈；未补偿 coordinated omission。
- 排空后主轮 accepted=terminal：business 21576198、Core 2061830，unfinished=0、期末 pending=0；JFR 轮 business 21565454、Core 2060814，同样清零。两个 workload 均 `mixedCapacity=PASS`、`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true`，未出现拒绝/错误/超时中止。
- 初始化 users1385（retail1000）、symbols128、每普通账户余额1000000000、总核对资金1384000000125。主轮总2920周期/测量2006周期，业务hash `271a6b9d79b6bc4b`；JFR 总2937/测量2005周期，hash `4b15e37113cc9917`。各轮运行周期不同，不应要求两轮hash相等。
- MIXED 每币对每周期 84 个业务动作对应20个 fills，包含批量挂单/撤单、单笔挂单/撤单和IOC；卖IOC在买单撤销后提交。不能把此场景的业务操作吞吐当成全成交订单吞吐。业务数另包含行情命令。
- node PID 主轮4413/JFR5404；client fork4427/5423，JFR runner5412。主轮 JMH single-shot 60.050s/op 是整轮调用耗时，不是业务吞吐。主轮新打包成功，未修改代码；本轮不重跑上轮已通过的1450项功能回归。

### 主轮分业务延迟

单位 µs；统计为客户端现有请求延迟口径，含最后排空完成请求；不是服务端纯执行耗时。统计包含5.683ms排空，缺少各类型独立的稳态 requests/s、入口→accepted 和 accepted→terminal 拆分，不能用统一延迟替代这些缺口。

| 类型 | requests / items | p50 | p90 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| PLACE_ORDER | 513536 / 513536 | 5246 | 9428 | 10166 | 14032 | 26083 | 41189 |
| CANCEL_ORDER | 513536 / 513536 | 5398 | 8339 | 8970 | 11608 | 21528 | 28999 |
| APPLY_MARK_PRICE | 7686 / 7686 | 4980 | 8871 | 10199 | 14114 | 21692 | 23183 |
| PLACE_ORDER_BATCH | 770304 / 15406080 | 7294 | 12337 | 13393 | 17334 | 30965 | 52133 |
| CANCEL_ORDER_BATCH | 256768 / 5135360 | 12083 | 13508 | 14508 | 25870 | 42926 | 52232 |

批量平均/最大均20 items，批量请求总数1027072。完整稳态速率取上表 steadyCapacity，不用含排空的 mixedCapacity.businessOpsPerSec。

### JFR 同窗归因与资源

- 诊断测量 epoch ms `[1789743579703,1789743639720]`（本地22:59:39.703–23:00:39.720）。以事件 startTime 筛选，不把整个136秒trial当成稳态。阶段稀疏样本：admissionExecution/ingressToAdmission 各32006，transportToOwner32141、ownerToEgress32140、settlement28027。
- p50/p99：Owner准入等待1.750/7.180ms；准入执行4.103/13.323µs；transport→Owner0.332/3.606ms；Owner→egress4.691/57.092µs；Lane最大执行8.247/82.696µs；Matcher→Lane全完成20.573/651.737µs；Lane完成→**有序提交入口**0.269/2.379ms。最后一项包含FIFO等待，不是纯通知或唤醒耗时。
- 同窗 Owner (`trading-owner--1`) ExecutionSample 2058：首帧 TradingOwnerLoop.run126、Arrays.fill103、Long2ObjectHashMap.compactChain98、TerminalTombstoneStore.entitySlot95、completedLaneMask73、decodePlaceOrder63、LanePublishedMap.applyPublished55、ConcurrentHashMap.get54、readyPartitionMask49、firstDeferred38。含调用栈计数 advanceMatchingProgress60、commitReadyMatching107、pollCommandCommit134、readyPartitionMask52，不能加总为互斥CPU百分比。
- 节点脚本归一至单核的同窗 CPU：Owner98.45%、Matcher98.43%、四Lane约98.4%，均启用BUSY_SPIN，不能当作业务利用率；Lane累计执行时间/测量时间平均37.73%、最小37.24%。全trial hot-methods 中 Lane.run50.78%、Matcher.run12.53%也含自旋。客户端全trial首帧 space52.20%、reap6.17%、egress poll5.36%，支持背压等待显著，但未做独立发压端容量验证。
- 主轮 pipeline峰值 matcher218/completion207/context255、lanes[48,47,47,41]；JFR218/202/255、lanes[61,60,59,59]。这是峰值不是持续队列曲线，不能据峰值确定唯一瓶颈。
- 同窗116次G1New，sumOfPauses合计623.881ms，最大单次9.757ms；GCHeapSummary使用量170.0–478.5MB，最后GC后172.5MB，committed512MiB；GC可能影响尾延迟，未完成逐请求关联，不能把全部尾延迟归为Owner调度。
- ThreadAllocationStatistics 同窗各60样本首末差：Matcher8.471GB、Owner5.873GB、四Lane各约4.60GB、cluster service2.228GB（约59秒采样跨度，非完整60.016秒精确总量）；不把 allocation sample 数当对象总数。全trial主要分配类 OrderRuntime18.97%、byte[]8.32%、CoreMatchingResult7.99%、long[]6.82%。未单独执行GC profiler。
- NMT结束主轮 reserved3156387KB、committed727887KB（baseline差+9647KB）；JFR reserved3158167KB、committed736031KB（差-9078KB）。主测中节点CPU约920%、RSS约1.73GiB，client fork约330%、RSS约425MiB；并非绑核独占环境。起止及运行中swap均0，磁盘最低约320GiB，无空间异常。
- `jfr summary` 节点：14:58:43 UTC起136s，ExecutionSample28289，ObjectAllocationSample109287、InNewTLAB109265、OutsideTLAB1743、ThreadAllocationStatistics3714；节点及两个client录制DataLoss均0。所有脚本内置 `jfr view`（thread-cpu-load、hot-methods、allocation、gc、safepoints、contention、latencies）已生成；部分汇总包含初始化/排空，已标明不用于同窗稳态结论。
- 原始录制：node.jfr 114954035 bytes，SHA256 `6bdd3767be1081f0c26353cecc4042f9824dbcbf8f8f359dc9aa36d917f5ba3f`；client-5412.jfr 9834009 bytes，SHA256 `982ea6094b571b55e0a9d024d2b1641a452a08a6e72160e869f0f0fd1800f581`；client-5423.jfr 131149652 bytes，SHA256 `ba0d88db7f4f7360780eedd8a4d9e36e3740e2f8eacf0acadbfc8550548d9f21`。client起始UTC14:58:45/14:58:46、时长133/132s。都位于计划中的jfr目录 `window-256/end_to_end/`。
- 主轮 client.log / metrics.json SHA256：`00d95a88bad94a744783a6380b8739beec10d9844ded9855f4265fb831089b40` / `1290a217d1d8c900b24b0541d1e4d1e0bb99eaa78aa341ddcd3dcd6c28b098fe`；JFR轮分别 `3fad19e14ec4a12f998ae3dbdf7c9b696d64344abdf70c2f978c1dbdc7401c2f` / `78bd095659d41d6f492c316a91515b0e6950fe52692e43867be520f7385af638`。

### 结论

本轮达到约35.94万 terminal business ops/s、3.435万 terminal Core messages/s、8.556万 fills/s，资金/持仓/冻结/终态检查通过；同配置JFR轮约35.93万。单轮无 profiler 结果加一轮诊断不能证明稳定优化收益，更不能外推生产独占核/三节点容量。

定位仍是阶段证据：入站排队与FIFO有序终态等待明显，Lane纯执行较短；Owner还有发布、索引维护、数组清理和扫描成本，未证明一个唯一函数是根因。未做旧版本对照，不把与历史41.5万的差值作为本轮回归或优化幅度。

综合判定**部分验证**：本轮真实单节点短时吞吐与资金完成性通过；未覆盖真实Archive快照/重启恢复、长稳/native增长、WebSocket/网关API、其余五产品线、多个独立无profiler重复轮及完整到达率/分段延迟测量。只报告本轮已达到负载，不宣称容量上限或无泄漏。

### 最终 I/O 门槛补核

- 对节点 FileRead/FileWrite/SocketRead/SocketWrite 事件按 Owner 线程和正式测量窗口补核，发现 **2次同步 FileRead**：22:59:39.797479565 读服务JAR 30 bytes/7.081µs；22:59:39.797520640 读同一JAR 2551 bytes/2.398µs。堆栈为 ZipFile.Source.readFullyAt/readAt → ZipFileInputStream → InflaterInputStream / jdk.internal.loader.Resource.getBytes。未在同窗发现其余Owner文件/网络I/O。
- 该证据推翻“可以整体验收通过”的可能性：按统一标准 Owner 同步文件I/O判主链路验收失败，因此**本轮最终验收结论：失败（零同步I/O门槛未满足）**；上述“部分验证”仅描述场景/指标覆盖缺口。资金/终态校验通过以及35.94万实测值不受此判定改写。
- 两次读取合计9.479µs、发生于测量开始后约94ms；不能据此解释数万ops/s吞吐差距。需要另行定位延迟资源加载并验证预热覆盖，本轮仅压测，不修改生产代码或延长/重跑测量来掩盖此证据。

### I/O 边界复核修正（以本节为最终判定）

- 进一步查 `jdk.ClassLoad`：节点22:59:39.797414404首次加载 `com.surprising.aeron.protocol.CoreLaneMetricsCodec$Encoder`，duration288.760µs，两次JAR读取紧随其后。源码 `ClusterMixedCapacityMain.runFor` 在 `started=System.nanoTime()` 及打印 measurementStartEpochMillis **之前**同步执行 `lanesBefore=laneMetrics()`；query 返回并解码后才进入计时。该证据与直接按客户端epoch截取节点JFR后得到的“开始后94ms”存在边界归属冲突。
- 因未校准跨进程日志时钟与JFR时基，上一节直接判“正式窗口Owner同步I/O失败”证据不足，撤回该过早结论；这两次资源读取与计时前指标编码器首次加载相符，不应未经校准就定性成稳态交易热路径I/O。原始事件和先前判断保留供复核，不删除不利记录。
- **最终综合结论为部分验证，零同步I/O项待时窗校准确认，不标整体验收通过，也不据此认定业务失败。** 客户端单调时钟计时得到的主吞吐/终态/资金核对结果保留。阶段JFR统计是按客户端epoch近似截窗结果，精确边界归因仍有该缺口；不能据稀疏样本归因唯一瓶颈。

### 本轮清理

- 两轮节点、client及分析进程均已退出。仅将本轮临时Cluster/Archive、JFR、日志、报告和构建日志（约6.4GiB）移入 `/Users/atomex/.Trash/surprising-ex-owner-fifo-core-20260918T145354Z/`，可恢复；上述target原路径仅作历史定位。未删除用户原有文件、其他轮次产物或Maven编译产物。
- 本轮无代码修改；`git diff --check` 通过。未提交的前轮代码/文档改动保留，仅归档本轮验证记录。

## 2026-09-18 Owner 尾部执行与 FIFO 等待分解（采集前计划，run ID 日期为固定标识）

- 问题：验证是否终态尾部执行成本限制推进，区分 Lane 完成后的有序排队与 Owner 实际执行；不预设尾部为根因，不实施业务优化。仅当前 master `1ca54e12777faf396d6ec9db5b135509e4cd9b25` 加本轮诊断；旧版本对照不适用。现有工作区改动保留，完整 diff 校验于采集前记录。
- 改动：复用每命令 1/64 的 CommandBoundaryLatency，新增 commitAttemptTerminal/Waiting、factPublication、terminalBookkeeping、realtimePublication、responseAndRetirement。前两项内层成本包含于外层提交尝试，不能相加重复计算；普通/批量 terminalBookkeeping 均含资金校验及必要后续派发。新增 OwnerTurn 每 64 个非空 FIFO 轮次采样 retired/admitted/headWait/budgetExhausted；计数器仅 Owner 线程拥有，不参与业务/快照，样本不是全部轮次精确计数。
- 假设与证伪：若提交及后续尾部每命令耗时明显低于 Lane→Owner 等待，不能把等待当执行成本；若 factPublication/terminalBookkeeping 占提交尝试大头且匹配执行栈，则定位进一步优化入口。预算耗尽与 headWait 分开报告，ready 仅代表可推进，不能视为已具备终态。单轮采样不能证明唯一根因或预言提升幅度。
- 环境：本机 macOS 26.7 x86_64、16 logical CPU/16GiB，HotSpot Corretto 27+33、Maven3.9.16、G1；节点512m–1536m，客户端128m–512m；不绑核、无 wallet。磁盘起始321GiB可用，运行中检查磁盘/swap/干扰；不代表生产独占CPU部署。
- 场景：真实单 Aeron 成员，网络及 Archive 保留；LINEAR_PERPETUAL、1 matcher/4 Account Lanes、128 symbols、1000 retail/1385 total users、seed25620、MIXED batch20，沿用 baseline 初始化资金和订单簿、内部做市/标记价源。global/session in-flight256，Owner/Matcher mailbox/Lane BUSY_SPIN，Owner input64；持续异步饱和背压负载，不能声称恒定 open-loop 到达率或容量上限。
- 时间：每轮 warmup30s + measurement60s + 排空，JMH continuousOperations wi0/i1/f1/thread1；先无 profiler 主吞吐，再同配置 JFR 诊断。无业务 SLA，不制定事后吞吐通过线；错误/拒绝/超时0，排空 accepted==terminal（business/core）、unfinished/backlog0，资金差0及持仓/冻结/订单核对必须通过。
- 采样：既有 owner-commit-profile.jfc + OwnerTurn，ExecutionSample20ms、CPU/分配统计1s、I/O阈值0、各进程独立 JFR 且上限256MiB；既有 NMT baseline/final。JFR 内部所有阶段差值使用同 JVM nanoTime；跨进程 epoch 仅近似定位，主要归因使用测量中部留两侧2秒保护带，不把边缘类加载认定稳态I/O。稀疏 event 开销、嵌套计时及诊断轮吞吐偏差均报告。
- 命令：`ASYNC_RUN_ID=owner-tail-20260919-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；JFR轮替换 ID 后缀为 jfr、ENABLE_JFR=true、ASYNC_SKIP_BUILD=true。
- 测试：共享 Owner 路径覆盖六产品线 Maven 测试及已有内存 snapshot 恢复；专门开启 core.settlementLatencyDiagnostics 验证事件覆盖/计时和槽位复用。真实 Archive 重启、长稳、其他产品线真实集群、网关/WebSocket及独立 -prof gc 不在本轮诊断覆盖内，最终结论至多部分验证。完成后仅清理本轮生成物，保留记录与校验。

- 采集前校验：`git diff -- surprising-aeron-core | shasum -a 256` = `3f5af5b67881d7e96f825bc3e4430a9e20b063719040bfeceedb304ecc7f8a0a`（包括原有未提交改动）。`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package` 通过；`mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=ClusterCommandPipelineTest -Dsurefire.failIfNoSpecifiedTests=false -Dcore.settlementLatencyDiagnostics=true test` 250 tests、0 failure/error/skip，覆盖新增事件与六产品线窗口/恢复测试。完整构建后仅缩进调整，主轮仍重建 package，确保测量最终源码。

### 实测结果与有效性

- 两轮按计划执行，完整 Maven 构建共1450 tests、0 failure/error、3 skip；开启诊断的250 tests无跳过。主轮节点PID17039、client fork17053；JFR节点17893、client runner17902/fork17911。均已退出。完整命令/JVM配置位于各轮 `node.command`、`client.command`、`strategy.txt`。
- 主轮 steady 60.018449s：21,627,306 terminal business、2,066,730 terminal Core、5,148,160 fills；**360,344.303 business ops/s、34,434.912 Core messages/s、85,776.292 fills/s**。排空6.481096ms，另完成2667 business/235 Core/0 fills；最终offered=terminal=21,629,973 business、2,066,965 Core，unfinished0。窗口峰值256，windowBlocked50.243545186s/452348次，占83.71%；这是受背压负载，入口直方图不补偿未提交请求的 coordinated omission，另报等待时间。
- JFR steady60.027651s：20,498,292 business、1,959,156 Core、4,879,360 fills；341,480.831 business/s、32,637.559 Core/s、81,285.207 fills/s。排空5.842600ms/2688 business/256 Core；最终offered=terminal=20,500,980 business、1,959,412 Core，unfinished0；windowBlocked50.214398699s/575438次、峰值256。吞吐比主轮低5.23%，包含采样扰动和轮次波动，不用作主成绩或改动收益。
- 两轮 `mixedVerify=PASS`：fundsDiff0、population/hftPositions/reservations/loss均true；main总cycles2896/measured2011/hash `dd579fe2ca14f5dc`，JFR总2812/measured1906/hash `7eaf6338f4ab654`。运行时间驱动的循环数不同，hash不应跨轮相等。无业务错误/超时/未完成；内存快照恢复由六产品线测试覆盖，未做本轮真实Archive重启。
- 主轮10秒区间 business/s：341016、364008、369206、356925、376329、354474；JFR：360898、345250、361617、337109、312285、331628。单次每配置，不证明稳定提升、独占CPU容量上限或生产部署上限。
- 主轮batch请求place772224/cancel257408，每批20（平均/最大20）；items分别15,444,480/5,148,160，不能把34.4k Core请求/s和360k展开业务项/s混为一谈。普通place/cancel各514816请求，mark7701。按含排空60.024930s换算：普通place/cancel各8576.7 requests/s、mark128.3/s、place batch12865.1 batches/s、cancel batch4288.4 batches/s；稳态吞吐仍使用上方严格窗口终态增量。

主轮业务响应延迟（µs；从提交到收到终态，不含等待窗口空位；样本数为上方requests）：

| 业务 | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|
| PLACE_ORDER | 5148 | 9297 | 10035 | 14352 | 26198 | 156368 |
| CANCEL_ORDER | 5193 | 8302 | 8953 | 13377 | 22560 | 156368 |
| APPLY_MARK_PRICE | 5169 | 9215 | 10919 | 15400 | 22003 | 28164 |
| PLACE_ORDER_BATCH | 7421 | 12263 | 13320 | 18038 | 32358 | 155975 |
| CANCEL_ORDER_BATCH | 11829 | 13369 | 14761 | 25903 | 37355 | 43614 |

### Owner 分段证据

JFR客户端测量epoch约 `[1789746131077,1789746191105]`；归因取中部 `[1789746133077,1789746189105]`（56.028s，两端各排除2s）。该保护带不是跨进程时钟校准；精确稳态边缘I/O归属仍缺证据。下表计时本身全为同节点nanoTime差值。每条命令1/64采样，均为墙钟执行区间（包含可能的抢占/GC，不等同纯CPU）；提交尝试可能兼带推进其他在途工作。

| 类型 | 终态尝试n | 提交尝试均值/p50/p99 µs | 实时发布均值 µs | 回复及退休均值/p99 µs | 同ID三段合计均值 µs |
|---|---:|---:|---:|---:|---:|
| PLACE_ORDER | 7108 | 9.281 / 8.244 / 26.067 | 0.092 | 0.550 / 0.914 | 9.923 |
| CANCEL_ORDER | 7100 | 6.372 / 5.733 / 20.526 | 0.096 | 0.428 / 0.769 | 6.897 |
| PLACE_ORDER_BATCH | 10627 | 25.939 / 21.300 / 91.342 | 0.089 | 1.124 / 2.734 | 27.152 |
| CANCEL_ORDER_BATCH | 3548 | 31.569 / 29.857 / 71.623 | 0.090 | 0.972 / 2.243 | 32.631 |

- 提交尝试内：place batch事实发布均值4.059µs/p99=12.075µs、终态记账均值3.054µs/p99=7.329µs；cancel batch事实发布均值6.723µs/p99=16.969µs、记账均值2.518µs/p99=5.545µs。按同ID配对总时长，事实发布/记账分别占place batch提交15.65%/11.77%、cancel batch21.29%/7.98%；剩余约71–73%包含结果收集、Lane发布、前置批量续接、其他推进与采样开销，**不能全归为单个方法**。`ownerFactPublication` 仅包住 CommitPublication.complete，不包含此前 collectMatcherSettlement 内的 LanePublication.publish。
- 覆盖缺口：普通订单生产直连路径走 `completeDispatchedMatcherSettlement`，本轮事实发布/终态记账内层计时放在另一条 `finishAppliedMatching` 路径，因此主场景只有batch内层样本；不能拿batch分项比例外推普通命令。普通命令外层提交/发布/退休计时有效。测试验证的是事件整体覆盖，不代表每条实际分支内层均覆盖。
- 非终态提交尝试：place n12799/均值2.339µs，cancel65/0.878µs，place batch3465/0.936µs，cancel batch122/1.412µs；这些是每次尝试开销，不是整段排队耗时，notification gate跳过的轮次不在此计时内。
- Lane完成→进入有序提交：place n7097/p501.756µs/p99288.026µs；cancel n7109/p50370.925µs/p992788.935µs；place batch n10645/p50371.989µs/p992012.351µs。差异说明不同命令排队位置不同，不能把全部等待说成唤醒慢。Matcher→Lane完成 p50/p99：place4.780/35.257µs、cancel7.651/674.905µs、place batch43.479/629.210µs；Lane最大执行p50/p99分别4.279/11.842、6.781/19.452、40.932/100.033µs。
- 准入执行均值：place3.557µs、cancel2.938µs、place batch6.226µs、cancel batch8.002µs；准入前排队p50分别1.200/2.403/1.202/4.939ms。Owner→egress n28509/p505.097µs/p9968.875µs。实时发布计时仅本Core场景边界，不代表真实WebSocket推送成本。
- OwnerTurn非空轮次样本17916：零退休10380（57.94%）、1条6922（38.64%）、≥2条614（3.43%），其中64条149轮；总退休28342，多条退休轮次贡献75.58%退休量。headWait17762（99.14%），budgetExhausted667（3.72%），两者可重叠且不是墙钟占比。**连续ready批量退休确实在工作，非每轮强制只退一条；不能把加大64预算认定为主要解法。** 周期采样可能有相位偏差，非全量统计。

### 执行栈、资源及具体下一步

- 中部Owner execution samples1733（20ms采样，非方法精确CPU计时）：pollCommandCommit1087（62.72%）、commitReadyMatching1026（59.20%）、completeMatching788（45.47%）、finishOrderBatch400（23.08%）、collectMatcherSettlement352（20.31%）、LaneDelta.commitTerminalToOwner258（14.89%）、advanceMatchingProgress225（12.98%）、LanePublication.publish201（11.60%）；inclusive占比互相包含，不能相加。
- 可复核热点链路：`finishOrderBatch → collectMatcherSettlement → LaneDelta.commitTerminalToOwner → TerminalStateRetention.acceptBatch → TerminalTombstoneStore.putIfAbsent → entitySlot`（该前栈47样本；批量另一续接入口同链18样本）；`LanePublication.publish → LanePublishedMap.applyPublished/remove → Long2ObjectHashMap.compactChain`（多支栈）；`collectMatcherSettlement → releaseMatcherSettlementChanges → LaneDelta.clear → Arrays.fill`（单此前栈11样本，另含publication.clear7样本）。事实索引 `RuntimeFactIndexes.applyCurrent → ActiveOrderIndex.applyRuntime → Long2ObjectHashMap.compactChain` 前栈23样本。
- 因此，本场景主要优化入口是 **Owner收取Lane终态、应用发布与终态索引的串行工作**，不是最后offerResponse/窗口退休；同时队首未完成确实限制连续推进。还不能宣布某个单函数是唯一吞吐根因。批量提交开销也不是26–32µs/业务项，而是20项一批的Core请求耗时。
- 建议下一项最小修改：先核查 `LanePublication.publish` 对 `removedOrderRoutes/removedReservationRoutes` 在值遍历时已删除、随后再次remove的交集，保留“只有删除、无after-image”的补删分支，证明不丢终态后消除重复查表；核查 `LaneDelta.clear` primitive id/user数组是否仅按count读取、写入前全覆盖，再决定能否删除清零（引用清理仍保留）；对 `TerminalTombstoneStore.entitySlot` 实测bucket链长和重复查找再选局部改法，保留幂等/重试/终态保留语义。以上是待验证方案，本轮**未删除业务操作、未更改索引/资金/顺序边界**。不建议先换window模式、增加commit阶段或宣称唤醒能解决。
- Owner/Matcher/各Lane约98%单核CPU但均BUSY_SPIN；Lane测得有效执行比均值36.84%、最小36.48%。主/JFR matcher队列峰217/222，completion199/202，context256/256，Lane峰main[68,68,68,72]、JFR[36,56,39,55]。client整轮top为space53.76%、reap5.49%，支持窗口背压，不构成客户端/网络永不受限的证明。
- 中部GC102次，暂停总568.186ms（约1.01%），p50/p95/p99/max=5.377/6.792/7.239/9.186ms；会影响毫秒级尾延迟，不能凭总占比排除单个尾峰。heap used171,053,464–479,697,008bytes，窗口首/末GC后171,611,280/173,514,864，heap committed512MiB。主轮最大156ms响应无同期JFR，不能硬归因GC。
- 1秒ThreadAllocationStatistics中部首末差：Owner5,144,912,376bytes、Matcher7,381,094,528、各Lane4.011–4.017GB、ClusterService1,942,057,520；这是采样点覆盖区间增量，非精确窗口逐业务分配计数。整轮AllocationByClass压力：OrderRuntime18.89%、byte[]8.34%、CoreMatchingResult7.93%、long[]6.98%、ReservationRuntime6.19%。TLAB、outsideTLAB、ObjectAllocationSample事件均存在；未独立-prof gc，不填精确对象数/op。
- NMT节点末值main committed724618KB（+8018KB），JFR736264KB（-3717KB）；无长期增长斜率/FD/Direct池证据，不能证明无泄漏。中部Owner file/socket事件0；完整窗口边缘仍未校准，不能据此做零I/O全验收。整轮JFR有大量正常Archive写，不属于Owner同步I/O。
- 测量期间swap0，磁盘321→313GiB；同机有Terminal/WindowServer/其他桌面进程，未绑核。测量结束后分析失误：全量JSON展开1,509,679条FileWrite，发现异常时约8GB、终止后文件实际13,814,538,240bytes，分析Python内存增长触发约3GiB swap；立即停止自己的分析/导出PID19229/19122，改为 `java -Xmx256m /tmp/OwnerTailEvents.java <node.jfr>` 流式筛选，再Python流式汇总。该异常发生于node/client均退出后，不污染已完成主/JFR测量；全量JSON中间文件无效、不用于结论。

### 归档与结论

- 原始目录：`surprising-aeron-core/surprising-aeron-benchmarks/target/aeron-async-stages/owner-tail-20260919-{main,jfr}/window-256/end_to_end/`；node/client命令、日志、metrics/jmh JSON、NMT、JFR summary/view随轮保存。分析统计 `/tmp/owner-tail-20260919-analysis.json` 含全部分项n/mean/p50/p90/p95/p99/p99.9/max、轮次分布、热点；Java/Python分析程序一同清理归档。
- JFR node142s/119932801bytes/SHA256 `d367cdb70122857799c80aa6c70ce73bb95579d189300b11757ce8ca7b036eca`；client runner138s/10176281bytes/`5674a763113fb34658170c990fcde1b0dc059cdf2698e98fdcac3b8e59abf4e5`；client fork137s/126470776bytes/`b11b95878dd162844ab88e7d41ba831c1cadf5ce57972238b6d4b728fef56e05`。三者DataLoss0；node整轮CommandBoundary390081、Settlement39489、OwnerTurn32442、ExecutionSample25929。
- **结论：部分验证。** 已把“尾部太重”细化为Owner终态合并/应用的实际成本与FIFO队首等待，排除“最后回复退休是主要成本”和“固定每轮只能退一条”这两个解释；尚未证明单一根因或优化幅度。业务核对和共享路径测试通过；普通直连内层计时、真正独占CPU、重复轮次、Archive重启、长稳、网关/WebSocket、完整跨进程时窗校准仍有缺口。只增加稀疏诊断，未实施性能修复。

### 本轮清理与交付

- 本轮节点/client/分析进程均已退出；仅将两轮临时Cluster/Archive/JFR/报告、构建测试日志、分析脚本和无效JSON展开文件（合计约19GiB）移入 `/Users/atomex/.Trash/surprising-ex-owner-tail-20260919/`，可恢复，原target/tmp路径仅作历史定位。未删除其他轮次或用户文件，未清空Trash。
- 新增状态仅 `CoreMatchingPhaseMetrics.nonemptyOwnerTurns` 诊断采样计数及短生命周期JFR事件；无新增业务容器/状态副本/异步阶段。主流程保留单命令提交边界、异常传播与资金校验。`git diff --check` 通过；只提交本轮代码/README/验证记录，其他既有未提交改动保留。
