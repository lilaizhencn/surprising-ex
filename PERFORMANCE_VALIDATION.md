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

## 2026-09-18：beccd8b7 单节点 MATCH_STREAM 吞吐探索（20260918T074901Z）

### 锁定计划（采集前）

- **问题与门槛**：验证当前 `master` 的真实单节点交易 Core 在固定全局 in-flight `256` 下，`MATCH_STREAM` 异步 GTC maker + IOC taker 能达到的持续终态吞吐。目标是得到当前配置的有效吞吐上限探索值，不建立旧版本对照。有效条件：`acceptedBusinessOperations == terminalBusinessOperations`、accepted/terminal Core messages 相等、unfinished/backlog 为零、失败/拒绝/超时为零、`fundsDiff=0`、`bookLevels=0`；不满足则标记失败或无效，不把排空计数算入测量吞吐。
- **环境与复现**：时间 `2026-09-18 15:49:01 CST`；commit `beccd8b7879e7d68fd60a905cda0d7ad60e4698e`；未提交改动仅为用户既有的 `AGENTS.md`（`1 insertion/58 deletions`，diff SHA-256 `35324a6c43103d81e805fe54377069b7609f144a615f98c153d6341525a92773`），不改动业务代码；MacBookPro16,1，16 logical CPU，16 GiB，macOS 26.7 x86_64；HotSpot Corretto JDK 27.0.0.33.1，Maven 3.9.16；开始前磁盘可用约 424 GiB；同机不启动 wallet，先检查并记录无残留 Java/Aeron/Maven 进程；随机种子 `9901`。
- **业务场景**：`LINEAR_PERPETUAL`；单个真实 Aeron Cluster 成员、1 个 matcher、4 个 Account Lane；`MATCH_STREAM`，4 workers/4 connections，128 symbols，100 users（50 maker/taker pairs）；每个异步工作单元连续提交 GTC maker + IOC taker，买卖方向交替；做市状态由基准 workload 初始化，测量边界前排空；全局 async/session in-flight 固定 `256`。
- **负载与时间**：恒定持续异步发压，窗口内不逐笔等待 maker 终态再发 taker；预热 30 秒，正式稳定测量 60 秒，单次主吞吐轮；结束后只做自然排空和资金/订单簿核对，不把排空计入吞吐。JFR 归因使用同一场景、独立新节点、30 秒预热 + 60 秒测量；客户端 `requestToTerminal` 以客户端单调时钟记录，窗口吞吐使用测量窗口终态增量/墙钟时长。
- **采样配置**：主轮不启用 profiler；节点/客户端记录 GC 日志、NMT summary、线程转储和 macOS `top`/`vm_stat`/`iostat` 时间序列。归因轮启用 `owner-commit-profile.jfc`，节点和客户端各自独立 JFR 文件，`maxsize=256m`、`dumponexit=true`；每个进程单独 artifact。直接 `ClusterCapacityMain` 不是 JMH invocation，JMH `-prof gc` 不适用，本轮用 HotSpot GC 日志与 JFR GC/分配视图替代并在结果中标明缺口。
- **完整复现命令（计划）**：构建 `JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -f pom.xml -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`；节点按 `surprising-aeron.service.SurprisingCoreBootstrap` 启动，参数固定为 `-Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN`；客户端按 `com.surprising.aeron.benchmarks.workload.ClusterCapacityMain` 启动，参数固定为 `-Dsurprising.aeron.capacity-workload=MATCH_STREAM -Dsurprising.aeron.capacity-symbol-count=128 -Dsurprising.aeron.capacity-workers=4 -Dsurprising.aeron.capacity-connections=4 -Dsurprising.aeron.capacity-user-count=100 -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=60 -Dsurprising.aeron.capacity-seed=9901`；实际命令、PID、起止时间及 artifact 校验在本节结果中补录。

### 实际执行与偏差

- 构建命令按计划执行并成功：`JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -f pom.xml -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`，总耗时 `26.831s`。JDK/Maven/OS 与锁定计划一致。
- 计划中的 `SurprisingCoreBootstrap` 不存在于当前 service jar；首次启动仅产生 `ClassNotFoundException`，没有启动交易节点和业务流量，结果不计入吞吐。实际修正为 service jar manifest 的 `java -jar surprising-aeron-service/target/surprising-aeron-service.jar`，主类为现有 `SurprisingCoreApplication`。无业务错误、无残留进程；无效尝试原始目录为 `target/pressure/20260918T074901Z/main/`。
- 实际主轮命令由 `target/pressure/20260918T074901Z/main-rerun/node/node.command` 与 `target/pressure/20260918T074901Z/main-rerun/client/client.command` 保存；节点 PID `5294`，客户端 PID `5314`。节点 `07:52:58Z` 启动、`07:53:00Z` ready；客户端 `07:53:00Z` 启动、`07:54:33Z` 结束，退出码 `0`。主轮节点使用 G1、`-Xms512m -Xmx1536m`、NMT summary；客户端使用 G1、`-Xms128m -Xmx512m`；两端 GC 日志 10 MiB 文件滚动、最多 4 个文件。
- JFR 归因轮使用同一业务参数和独立节点/客户端，节点 PID `6209`、客户端 PID `6242`，`07:56:13Z` 启动、节点 `07:56:16Z` ready、客户端 `07:57:50Z` 结束，退出码 `0`。使用 `config/owner-commit-profile.jfc`，节点/客户端各自 `maxsize=256m,dumponexit=true`。JFR 轮包含 profiler 开销，不与主轮绝对吞吐比较。

### 主轮结果（只取 60.007 秒稳定测量窗口）

- **持续吞吐**：`capacity=PASS`；`MATCH_STREAM`，`offered=2,216,728`、`accepted=2,216,728`、`finalized=2,216,728`，即 `36,941.445 terminal business ops/s`；成交 `1,108,364`，即 `18,470.723 matches/s`。Core 终态消息 `2,224,338`，约 `37,070.9 messages/s`；行情前置 `7,610`，`126.820/s`。订单业务操作按一单一个 operation 计，fill 没有混入 business ops。
- **完成性**：`failures=0`、`transientOfferRetries=0`、`unfinishedRequests=0`；`submittedRequests=2,224,338`、`completedRequests=2,224,338`；accepted/terminal business 与 Core message 计数均相等。全局窗口 `256`，观察到请求峰值 `256`、1 秒采样 59 次的平均占用 `251.593`，说明实际到达率被 in-flight 窗口和终态 RTT 背压约束，不能解释为无限 open-loop 到达率；本轮未配置固定 offered rate，未做 coordinated-omission 校正（`expectedInterval=0`）。
- **分段尾延迟**：客户端单调时钟的 `request → terminal`，直方图样本 `2,216,728`，总体 p50 `6,553µs`、p99 `15,507µs`、p99.9 `24,264µs`、max `38,633µs`。
  - sell：`1,108,364` samples，p50/p90/p95/p99/p99.9/max = `6,569/7,921/8,626/15,523/24,264/38,633µs`。
  - buy：`1,108,364` samples，p50/p90/p95/p99/p99.9/max = `6,541/7,892/8,593/15,499/24,297/37,683µs`。
  - 本次 workload 只有订单命令；没有独立撤单、改单、触发、风险、强平、资金费、ADL 或结算动作样本，相关业务延迟项不适用，不填零。
- **业务状态**：benchmark 末尾核对 `fundsDiff=0`、`bookLevels=0`；订单请求无 unfinished。该输出是聚合资金/订单簿不变量检查，没有导出逐用户余额、冻结、持仓和手续费流水明细，不能替代完整账务对账。

### JFR/系统/GC 证据（归因轮，不替代主分数）

- 归因轮 `MATCH_STREAM` 的有效性仍为 PASS：`1,822,200` accepted/finalized，`911,100` matches，`failures=0`，Core messages `1,829,752`，unfinished `0`，`fundsDiff=0`、`bookLevels=0`；JFR 开销下吞吐为 `30,366.087 ops/s`，仅用于归因。客户端 p50/p99/p99.9 为 `8,118/17,498/22,069µs`；窗口平均占用 `247.576`，峰值仍为 `256`。
- **CPU 与线程**：JFR 轮 macOS `top` 分别采集节点和客户端；节点稳定样本约 `784.7–848.7% CPU`，客户端约 `477.4–664.0% CPU`，测量期间机器 idle 接近 `0%`，无 swap。JFR ExecutionSample 热点：`SettlementLaneWorker.run()` `15,669/20,950 = 74.79%`，`SettlementLaneWorker.peekMatcherSettlement()` `9.43%`，`TradingOwnerLoop.run()` `1.62%`；这支持“结算 lane/确定性 owner 交接是首要瓶颈假设”，但不是仅凭热点认定根因。客户端热点为 `ClusterCapacityMain.streamMatch(...)` `89.72%`，代表发压循环本身占比高。
- **分配**：节点 allocation sample 的主要 class pressure 为 `OrderRuntime 12.10%`、`long[] 8.29%`、`ResolvedPlaceOrder 6.08%`、`byte[] 5.83%`、`CoreMessageHeader 4.64%`；线程 pressure 主要在 `trading-owner--1 40.39%`、`core-matcher-0 11.42%` 和四条 account lane `10.99/10.18/9.41/7.15%`。客户端主要为 `capacity-egress-dispatcher 82.50%`。这些是 JFR sample pressure，不是精确 bytes/op；本轮没有可比的直接 `-prof gc` 结果，因为实际网络 workload 是 `ClusterCapacityMain`，不是 JMH invocation。
- **GC/停顿**：JFR summary 节点 `GarbageCollection=33`、客户端 `229`，无 `jdk.DataLoss`；稳定测量时间段内 GC 最大 pause 节点 `4.140ms`、客户端 `3.030ms`，对应 safepoint 最大约 `4.590ms`、`3.350ms`。JFR 文件含预热/启动/关闭阶段，关闭阶段另见节点 `55.5ms` safepoint，未与业务窗口关联，不能拿来解释主轮 p99。主轮 GC log 记录节点 40 个、客户端 257 个 GC 事件；退出 heap snapshot 为节点 used `158,095K/committed 524,288K`、客户端 used `66,639K/committed 131,072K`，是短轮末值，不构成长期稳定性证明。
- **阻塞与 I/O**：节点 JFR 记录 `ThreadPark` `3,036,682` 次，p99 `1.22ms`；Java monitor blocked 仅 `22` 次，最大 `0.760ms`。节点 `FileWrite` `1,614,801` 次，p99 `0.0231ms`、max `36.5ms`，客户端 `2,514` 次、max `0.0667ms`；该聚合包含 Archive/JFR/外围文件事件，尚未把每次写入栈完整归因到交易 owner，下一轮需按窗口和 stack 单独确认是否存在主链路同步 I/O。
- **Native/线程/长稳**：节点 NMT 相对 ready 后 baseline 的总量为 reserved `-24,136KB`、committed `-16,448KB`；类别中 code committed `+15,777KB`、thread committed `+594KB`，线程数增量 `+6`。这是单轮短时 diff，不能证明无泄漏；未执行长稳 20 分钟/多轮 GC 后 old-object、FD 和 native 增长斜率检查。JFR DirectBuffer/NativeMemory 事件已启用，但未将其时间序列汇总为精确峰值，相关项保留缺口。
- **系统采样偏差**：主轮 `iostat`/`vm_stat` 正常记录，磁盘可用空间约从 `423GiB` 降至 `422GiB`、swap 始终 `0`；主轮的 macOS `top` 多 PID 参数不被系统接受，未得到主轮 per-process CPU 时间序列，因此 per-process CPU 仅引用独立 JFR 归因轮，不用 profiler 分数替代主分数。

### 正确性、恢复与证据

- Maven 精确测试命令：`JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -f pom.xml -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreStateSnapshotCodecTest,RuntimeCommitRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`；`CoreStateSnapshotCodecTest` `14/14`、`RuntimeCommitRecoveryTest` `8/8`，共 `22` tests，0 failures/errors/skips。
- 原始 artifact（清理前）路径、大小与校验：主轮目录 `target/pressure/20260918T074901Z/main-rerun/` `1.5G`；JFR 目录 `target/pressure/20260918T074901Z/jfr/` `915M`；全轮目录 `2.4G`。主轮 `client.log` SHA-256 `d18cc1b272526fd80f4c3b89697944d8648d41292b4d300267db16b5c06caadb`；JFR 节点 `node.jfr` `139,657,875B`、SHA-256 `20c9bb731822eea13180b86eda29ff2cb01eb137cf668f3d232777f6f232bc5e`；JFR 客户端 `client.jfr` `64,886,014B`、SHA-256 `017a0e2733b2599d0e7baee0feee82faf700db9b5402ff55ea7656d30b9e22c5`。JFR `summary` 与 views 位于对应 role 目录的 `jfr-summary.txt`、`thread-cpu-load.txt`、`hot-methods.txt`、`allocation-by-{class,site,thread}.txt`、`contention-by-thread.txt`、`gc.txt`、`safepoints.txt`、`latencies-by-type.txt`；原始文件在本节记录后清理。

### 结论与下一步

- **结论：部分验证（有效短时吞吐探索）**。在当前 MacBookPro16,1、JDK 27、单节点/单 matcher、4 lanes、`MATCH_STREAM`、全局 in-flight `256` 配置下，当前 `master` 稳定达到约 **3.69 万 terminal business ops/s**（约 **1.85 万 matches/s**），且本轮业务终态、Core 消息、资金聚合和订单簿核对通过。它不是三节点云端容量，也不是无背压的 open-loop 上限。
- 主要现象是窗口长期接近满载且节点 CPU 饱和；JFR 证据把下一步最小验证指向 `SettlementLaneWorker.peekMatcherSettlement()`/owner-lane 交接。应只改变一个因素，优先做同场景 `BUSY_SPIN` 与受控等待策略的单变量对照，并保留 256 window；预期若 owner/lane 是瓶颈，相关热点和 p99/吞吐应同步变化，若仅 JFR 热点变化而吞吐不变则证伪该假设。之后再做 20 分钟长稳，采集 old/live set、FD、线程、NMT/native buffer 斜率；最后按需要补 API 入口分类型延迟和独立 `-prof gc`/JMH 证据。
- **清理状态**：主轮/JFR 进程已停止，snapshot/recovery 测试已结束；本轮 `target/pressure/20260918T074901Z/` 下确认生成的临时集群、Archive/CNC、JFR、heap/NMT、GC、系统采样、日志和报告文件（约 `2.4G`）已整体移入用户回收站 `/Users/atomex/.Trash/surprising-ex-pressure-20260918T074901Z`，可恢复；工作区临时目录已移除。`AGENTS.md` 用户既有未提交改动保留。清理不影响本节已记录的结果、命令、路径、大小和校验信息。

## 2026-09-18：beccd8b7 历史 MIXED batch20 满载定位（20260918T081159Z）

### 锁定计划（采集前）

- **问题与门槛**：在当前 `master` 的真实单节点交易 Core 上，按历史 `MIXED batch20` 业务口径验证满载持续吞吐，并定位限制端到端推进的 owner、matcher、Account Lane、客户端发压或外围 I/O。有效条件为 `acceptedBusinessOperations == terminalBusinessOperations`、accepted/terminal Core messages 相等、`unfinished/backlog=0`、拒绝/错误/超时为零、`fundsDiff=0`、资金/持仓/冻结/订单终态及订单簿核对通过；任何正确性错误标记失败，不以高吞吐抵销。
- **环境与范围**：只测当前 `master` commit `beccd8b7879e7d68fd60a905cda0d7ad60e4698e`，不检出或重跑历史 commit；保留用户既有 `AGENTS.md` 文档改动，不改业务代码；单真实 Aeron Cluster 成员、1 matcher、4 Account Lane、`LINEAR_PERPETUAL`，不启动 wallet；HotSpot Corretto JDK 27、G1、节点 `512m/1536m`、客户端 `128m/512m`、MacBookPro16,1 x86_64，开始前及运行中检查磁盘、swap 和残留进程。
- **业务场景**：`ClusterMixedCapacityMain` 的连续 `MIXED` 交易流，128 symbols、1000 retail users、1 command session + 1 reserved query session、`batchSize=20`、`mixed-trading-stream=true`、`mixed-operational=false`、`mixed-fill-heavy=false`；全局/session/owner in-flight 固定 `256`；matcher 与 settlement 均 `BUSY_SPIN`，Core `SHARED_NETWORK` + `YIELDING`。批量 item 按 business operation 展开，另报 Core messages、fills 和 batches/items，避免把批量计数当作单订单吞吐。
- **负载与时间**：持续异步 FIFO 发压，不逐笔等待响应；预热 `30s`、稳定测量 `60s`、测量前排空、测量后自然排空和完整状态核对；主轮不启用 profiler 作为吞吐成绩，独立 JFR 轮使用同一业务参数与全局窗口，仅用于归因并记录采样开销。满载判据为 in-flight 峰值 `256`、窗口阻塞时间/比例显著、pipeline high-water mark 及 Account Lane 完成/执行时间可解释；若发压端先饱和，单独标明不能归因 Core 容量。
- **采样配置**：节点和客户端各自独立 JFR，`config/owner-commit-profile.jfc`、`maxsize=256m`、`dumponexit=true`；记录 `jfr summary`、CPU/热点、分配 class/site/thread、GC、safepoint、阻塞/park、线程转储、NMT summary/diff、macOS `top`/`vm_stat`/`iostat`，JFR 轮不与主轮绝对吞吐比较。主轮记录 GC 日志和系统采样，限制 GC 日志滚动大小。
- **完整复现命令（计划）**：先执行 `JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -f pom.xml -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`；节点固定 `-Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN`；客户端固定 `-Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.mixed-fill-heavy=false -Dsurprising.aeron.capacity-batch-size=20 -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=60`，入口为 `com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain`；实际命令、PID、时间、artifact 大小/校验及偏差在本节结果补录。

### 受控等待单变量诊断计划（采集前）

- 在主轮和 JFR 归因轮完成且正确性通过后，使用同一当前 `master`、同一 `MIXED batch20`、同一单节点/1 matcher/4 lanes、同一全局/session/owner in-flight `256`、同一 JDK27/G1、同一 30s/60s 时间，仅把节点 `settlement-wait-strategy` 从 `BUSY_SPIN` 改为 `BLOCKING`，`settlement-spin-limit=0` 保持不变；无 JFR，只比较稳定窗口吞吐、延迟、窗口阻塞、Lane 高水位和 CPU/系统负载。
- **预期与证伪**：若忙等抢占 owner/matcher 或造成无效 CPU 消耗是主要限制，`BLOCKING` 应在保持满载/正确性不变的同时降低节点 CPU、减少 `SettlementLaneWorker.run()` 空转并改善吞吐或窗口阻塞；若吞吐不变或下降，则不能把忙等当作主因，保留“结算交接/Owner FIFO/业务分配路径”假设，下一步转向对应阶段的单变量诊断。

### 实际执行与偏差

- 构建按计划完成：`JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -f pom.xml -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`，耗时 `26.024s`；JDK 27/Maven 3.9.16/OS 与锁定计划一致。当前 service jar manifest 没有 `SurprisingCoreBootstrap`，实际节点命令使用其 manifest 主类 `java -jar surprising-aeron-service.jar`；这只修正启动入口，不改变业务参数。
- 主轮无 JFR：节点 PID `12350`，客户端 PID `12378`；节点 `08:15:10Z` 启动，客户端 `08:15:14Z` 启动，客户端 `08:17:26Z` 退出 `0`。JFR 归因轮：节点 PID `13281`，客户端 PID `13318`；节点 `08:18:27Z` 启动，客户端 `08:18:32Z` 启动，客户端 `08:20:44Z` 退出 `0`，进程及视图在 `08:20:46Z` 完成。`BLOCKING` 单变量轮：节点 PID `14916`，客户端 PID `14942`；节点 `08:24:00Z` 启动，客户端 `08:24:04Z` 启动，客户端 `08:26:16Z` 退出 `0`。
- 三轮均保持 `LINEAR_PERPETUAL`、单节点/1 matcher/4 lanes、128 symbols、1000 retail users、`MIXED batch20`、`BUSY_SPIN` matcher、全局/session/owner window `256`、G1、JDK27、30s warmup/60s measurement；JFR 轮仅额外打开 `owner-commit-profile.jfc`。无 wallet、无残留交易进程。

### 主轮结果：满载且正确（无 JFR，仅此轮作为吞吐成绩）

- `60.006254s` 稳定窗口：`terminalBusinessOperations=19,898,900`、`offeredBusinessOperations=19,898,900`，`businessOpsPerSec=331,582.599`；Core `terminal/offered messages=1,902,100`，`coreMessagesPerSec=31,695.383`；`fills=4,736,000`，`fillsPerSec=78,917.688`。
- 完成性：`mixedCapacity=PASS`、`unfinished=0`、accepted/terminal business 与 Core message 均相等、`peakInFlight=256`、`mixedVerify=PASS`、`fundsDiff=0`、用户/持仓/冻结/订单终态/清算资金边界及订单簿核对通过；`adminActionRetries=114` 为控制动作 offer 重试，不是业务拒绝或错误。
- 满载证据：`windowBlockedCount=491,427`、`windowBlockedNanos=50,641,078,616`，占测量窗口 `84.39%`；客户端 JFR 的 `ClusterMixedCapacityMain.space()` 占 execution samples `57.22%`，说明发压端主要在等待 256 窗口释放，而非发不满。末尾 pipeline high-water：matcher `220/256`、completion `130/256`、command context `255/256`、Account Lane `[45,45,45,67]`；不是“请求少”，而是终态链路持续把固定窗口顶满。
- 分业务 `request → terminal` 延迟 p99/p99.9/max（微秒）：`PLACE_ORDER 14,426/23,166/33,816`，`CANCEL_ORDER 13,705/23,019/31,457`，`APPLY_MARK_PRICE 16,441/28,262/33,914`，`PLACE_ORDER_BATCH 17,481/29,343/42,270`，`CANCEL_ORDER_BATCH 23,789/37,093/42,827`。批量撤单是本场景最高的 p99/p99.9 终态路径。
- Lane 工作计数（operation `0=COMMAND`、`1=SETTLEMENT`）在四条 Lane 均衡：每条约 `296,000` command、`1,006,400` settlement；每条 Lane 实际业务执行约占 `34.1%–34.7%` 的 60 秒窗口，其余时间在等待/轮询，不是某一条 Lane 单独排队失衡。

### JFR 归因轮结果（仅用于定位，不替代主轮）

- 归因轮有效：`terminalBusinessOperations=19,791,379`、`terminalCoreMessages=1,891,859`、`fills=4,710,400`、`businessOpsPerSec=329,800.236`、`coreMessagesPerSec=31,525.623`、`fillsPerSec=78,493.319`；`mixedVerify=PASS`、`unfinished=0`、`fundsDiff=0`、`peakInFlight=256`。该轮含 JFR 开销，不与 `331,582.599` 比绝对吞吐。
- 节点 JFR `137s`，客户端 JFR `131s`，两份均 `jdk.DataLoss=0`。节点 `ExecutionSample=26,518`，其中 `SettlementLaneWorker.run()` `18,105/26,518=68.27%`；其后单个方法均不超过 `1.30%`，`TradingOwnerLoop.run()` `0.46%`。四条 `core-account-lane-*` 各约 `5.86%–5.90%` user CPU，`clustered-service-101-0` 约 `3.89%` user + `1.37%` system，matcher 线程约 `0.25%` user；这支持“结算 Lane 的等待/交接循环是首要阶段瓶颈”，不支持“matcher 计算本身已饱和”。
- `SettlementLaneWorker.run()` 的 68.27% 不能直接等同 68.27% 业务执行：源码在 [SettlementLaneWorker.java:204](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java:204) 反复检查 admission、matcher settlement、主队列和 handoff；当队列头的 Matcher payload 未就绪时，`BUSY_SPIN` 在 [SettlementLaneWorker.java:265](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java:265) 进入短自旋后 park，空队列路径在 [SettlementLaneWorker.java:280](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java:280) 继续按策略等待。主轮 Lane 实际执行仅约三分之一，说明大量样本是等待/轮询成本。
- 节点 allocation sample 主要类型：`OrderRuntime 19.12%`、`CoreMatchingResult 8.28%`、`long[] 6.96%`、`MatcherResult 6.30%`、`ReservationRuntime 6.30%`、`ResolvedPlaceOrder 5.06%`、`PlaceOrderCommand 3.36%`。主要分配站点：`OrderRuntime.snapshot()` `10.33%`、`TradingRuntimeState.preparedOrder(...)` `8.79%`、`CoreMatchingResult.fromNativeWithEvidence(...)` `8.28%`、`MatcherResult.from(...)` `6.31%`、`CoreMessageFlyweightDecoder.decode(...)` `5.63%`、`TradingCommandCodec.decodePlaceOrder(...)` `5.52%`、`TradingRuntimeState.placeOrderInLane(...)` `5.20%`、`CoreOrderDecisionResolver.resolveValues(...)` `5.06%`。这些是第二优先级的真实成本，不能仅凭 sample pressure 当作精确 bytes/op。
- 节点 JFR 共 `156` 次 GC、`4,025,227` 次 ThreadPark、`84` 次 Java monitor enter；稳定测量窗口内 GC 记录 `106` 次、最大 pause `8.97ms`，JFR 无 DataLoss。NMT ready 后 diff 为 reserved `3,169,010KB (-16,818KB)`、committed `744,478KB (-2,550KB)`；短轮未发现 native 单调增长证据，但不替代长稳泄漏测试。无 swap，磁盘空间从约 `422GiB` 降到 `419GiB`，没有磁盘不足或环境无效证据。

### 单变量 BLOCKING 对照：证伪“直接改阻塞等待”

- `60.024984s` 稳定窗口：`businessOpsPerSec=307,301.971`、`coreMessagesPerSec=29,382.606`、`fillsPerSec=73,136.675`，`terminalBusinessOperations=18,447,360`，`mixedVerify=PASS`、`unfinished=0`、`fundsDiff=0`、`peakInFlight=256`。
- 与无 JFR `BUSY_SPIN` 主轮相比吞吐下降 `7.32%`；窗口阻塞从 `84.39%` 上升到 `87.82%`，command context 高水位从 `255` 到 `256`，matcher/completion 高水位为 `225/162`，Account Lane 高水位反而只有 `[34,35,40,38]`。CPU 明显下降，但等待唤醒损失吞吐；因此当前生产基线保留 `BUSY_SPIN`，不能把全局切换 `BLOCKING` 作为修复方案。

### 分析结论与解决方案

#### 结论

当前最新 `master` 按历史 `MIXED batch20` 口径已达到应用满载：稳定 `331,583 business ops/s`，不是发压端不足，也不是请求没有进入 Core。真正的卡点是：

1. Owner command context 长期接近满窗，客户端 `84.39%` 时间在等待终态释放；
2. Account Lane 的 `SettlementLaneWorker` 在大量时间执行等待/轮询和 matcher settlement/handoff 检查，JFR 把该循环放大为首要热点；
3. 每笔 batch item 的订单快照、撮合结果、解码和准备对象分配进一步拖慢终态提交，但目前证据支持它是次要成本，不是先把 matcher 重写或把 window 调大。

因此不能简单归因成“Matcher 慢”或“CPU 还没跑满”。16 个逻辑 CPU 的宿主没有全机 CPU 饱和；本轮的“满载”是固定 256 in-flight 的交易流水线满载，瓶颈在终态/结算交接的推进速度。

#### 推荐落地顺序

1. **先做 Settlement Lane 自适应等待优化，保留 BUSY_SPIN 基线。** 在 [SettlementLaneWorker.java:204](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java:204) 的无工作、handoff 等待和“队头未 ready”三种状态分别统计并采用短自旋 + 有信号 park 的混合策略；不得直接全局改 `BLOCKING`。必须保证 producer 在发布 admission/matcher event/owner resume 时唤醒对应 Lane，避免丢唤醒和确定性顺序变化。新增诊断只记聚合计数/时间：`idleWaitNanos`、`notReadyWaitNanos`、`handoffWaitNanos`、`executeNanos`、`parkCount`，不做逐命令快照。
2. **再压缩终态热路径分配。** 优先围绕 `OrderRuntime.snapshot()`、`CoreMatchingResult.fromNativeWithEvidence()`、`MatcherResult.from()`、`CoreMessageFlyweightDecoder.decode()` 和 `TradingCommandCodec.decodePlaceOrder()` 做一次性 publication/解码引用复用；只在同一 Owner commit、同一 Lane 所有权和不可变发布边界内复用，不能删除资金/持仓/订单终态需要的 immutable after-image。为 `OrderRuntime.publicationValue`、批量 PLACE/CANCEL 解码、matcher result 转换补 JMH `-prof gc`，再用同口径无 JFR 端到端复测。
3. **最后检查 Owner FIFO/terminal retirement 的 ready gate。** 用已有 `TradingOwnerLoop`、`PendingMatchingRing`、`OwnerCommandPipelineState` 的聚合 `owner poll no-progress`、ready partition、context retire latency 诊断，确认 command context `255/256` 是被哪类 settlement/handoff 事件挡住；若证实是跨 Lane 的 ready gate，再在现有 owner/pending ring 边界做局部索引或批量退窗优化，不新增状态副本，也不把 in-flight 从 `256` 调大掩盖问题。
4. **验证门槛**：每个优化只改一个因素，先跑受影响 JMH 与服务测试，再跑同口径 30s/60s 无 JFR、独立 JFR、资金/持仓/冻结/订单终态、snapshot/recovery；目标是保持 `peakInFlight=256`、`unfinished=0`、`fundsDiff=0`，同时降低 window blocked、`SettlementLaneWorker.run()` 空转占比和 batch cancel p99。若吞吐提升但资金或恢复不一致，直接判失败。

### 测试、产物与清理

- 构建：上述 JDK27 Maven package 成功。
- 精确恢复测试：`CoreStateSnapshotCodecTest` `14/14`、`RuntimeCommitRecoveryTest` `8/8`，共 `22` tests，失败/错误/跳过均为 `0`。
- 原始 artifact 清理前大小：主轮 `mixed-plain` `3.1G`，JFR 轮 `mixed-jfr` `2.9G`，BLOCKING 对照 `mixed-blocking` `2.9G`；主轮 client log SHA-256 `620cd1dd8436164d8e25ade56036dd014764dcbe2938ae06f5bc28ccfdd4cb2f`，JFR node/client SHA-256 分别为 `d740a8543433c36359e21fdc54e30f68a245e3cfe984867da13018eac2ab0a9b` / `42a7c330f8aa187c37537e5a678545ce8c922aa411ddb62c12e6dbecc7d9e86e`，BLOCKING client log SHA-256 `cc865b1db4578df99b0e843cc75319d21b969b549ebf04808b076d5b56b00ff6`。
- JFR summary/views、GC/NMT/系统采样和完整日志均位于本轮 `target/pressure/20260918T081159Z/` 下，记录完成后只清理本轮生成目录；工作区保留 `AGENTS.md` 用户既有改动和本报告。
- **本轮结论：部分验证**。吞吐、满载、正确性、JFR 归因和单变量等待对照已完成；尚未实施代码优化，也未完成 20 分钟长稳/old-live set/FD/native 增长斜率，因此不宣称无泄漏或最终容量验收。
- **清理状态**：确认本轮 Java/Aeron/Maven 进程已停止，`git diff --check` 通过；本轮 `target/pressure/20260918T081159Z/` 生成的主轮、JFR、BLOCKING 对照及 Archive/CNC/GC/NMT/系统采样目录已整体移入用户回收站 `/Users/atomex/.Trash/surprising-ex-pressure-20260918T081159Z`，可恢复；工作区临时压测目录已移除，清理后磁盘可用约 `413GiB`。

## 2026-09-18：Owner gate 细分诊断（diagnose-owner）

### 目的与方法

- 在不改变业务窗口、产品线、消息顺序或等待策略的前提下，打开 `-Dsurprising.owner.poll-diagnostics=true`；随后把诊断从仅在 terminate 输出改为每 5 秒输出一次，并把 Owner gate 的拒绝原因聚合为 `waiting-no-completion`、`pending-ingress-not-ready`、`no-drain-work`。默认关闭时不执行这些计数和日志路径。
- 代码入口为 [TradingCoreOwner.java:347](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java:347)：`ownerWorkAvailable()` 只有在 pending ingress 可推进、完成通知到达或 Runtime 存在本地 matching drain work 时才调用完整 `pollCommands()`；队首等待异步完成时会进入 `waitingForMatchingNotification`。
- 最终诊断轮为 `target/pressure/diagnose-owner-20260918T085522Z/`，仍是单节点/1 matcher/4 lanes、`LINEAR_PERPETUAL`、`MIXED batch20`、window `256`、matcher/settlement `BUSY_SPIN`、JDK27/G1、30s warmup/60s measurement。诊断日志只用于定位，吞吐不与无诊断主轮比较。

### 证据与结论

- 最终轮业务结果仍完整：`terminalBusinessOperations=16,985,088`、`businessOpsPerSec=283,001.854`、`peakInFlight=256`、`unfinished=0`、`mixedVerify=PASS`、`fundsDiff=0`；pipeline high-water 为 `matcher=218`、`completion=148`、`context=255`、`lanes=[55,90,82,70]`。
- Owner 采样到 `gateSkippedPending=26,705,885`。在可分类的 `26,573,276` 次中：`waitingNoCompletion=17,796,159`（`66.97%`）、`pendingIngressNotReady=6,582,055`（`24.77%`）、`noDrainWork=2,195,062`（`8.26%`）。少量未归类项来自 producer 在两次只读检查之间发布了完成通知，不影响主结论。
- `waitingNoCompletion` 期间的状态快照反复显示 `runtimeSettlementNotifications=false`、`matcherCompletions=false`、`completionAvailable=false`，说明 Owner 不是拿到通知后处理不过来，而是在等 Matcher/Lane 发布终态完成事实；通知到达时 Owner 能继续推进。`noProgress` 为 `616,938/1,902,682=32.42%` 次 poll，但累计 `noProgressNanos=308,833,293`，只占约 `0.51%` 的 60 秒窗口，故 Owner 的 Java 计算本身不是主耗时。
- 因此问题已从“Owner gate 可能写错”收敛为：**终态命令窗口的队首长期等待 Matcher→Account Lane→Owner 的完成通知；其中约四分之一的入站命令进一步被同一窗口队首/容量挡住。** Owner gate 大量跳过是背压的表现和保护机制，不是当前第一根因。
- 结合上一轮 JFR 的 `SettlementLaneWorker.run()` `68.27%` samples、四条 Lane 实际执行约三分之一窗口、Matcher 线程仅约 `0.25%` user CPU，以及 `BLOCKING` 比 `BUSY_SPIN` 低 `7.32%`，当前最具体的卡点是 **Account Lane 结算事件的 ready/handoff/通知交接路径**；Matcher 计算、Owner poll 计算和简单切换 BLOCKING 均已被排除为首要根因。对象分配仍是次级优化项。

### 下一步方案

1. 保留 `BUSY_SPIN` 基线，在 [SettlementLaneWorker.java:204](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java:204) 只增加聚合计时：`idleWaitNanos`、`notReadyWaitNanos`、`handoffWaitNanos`、`executeNanos`、`parkCount`，并分别记录 Matcher payload ready、Lane handoff resume、Owner completion notification 的发布到消费延迟。
2. 若 `notReadyWaitNanos` 占主导，修复 Matcher payload 发布/唤醒与队头检查的交接；若 `handoffWaitNanos` 占主导，优化 owner-lane ownership barrier；若 `executeNanos` 占主导，再处理 JFR 已发现的 snapshot/result/decode 分配。每次只改一个因素，并保持 `peakInFlight=256`、`unfinished=0`、`fundsDiff=0`。
3. 诊断代码对应的 Owner pipeline/recovery 测试已用依赖闭包执行：`ClusterCommandPipelineTest` `244` tests，失败/错误 `0`、跳过 `1`；`RuntimeCommitRecoveryTest` `8/8`，失败/错误/跳过 `0`。直接只跑 service 模块曾因旧 protocol class 未随 reactor 重编译而失败，改用 `-am` 后通过。

### 清理状态

- 四轮 `diagnose-owner-*` 目录总计约 `10.5GiB`，已确认进程均停止；分析完成后将本轮生成目录整体移入 `/Users/atomex/.Trash/surprising-ex-owner-diagnostics-20260918T085522Z`，可恢复，不在工作区保留临时压测产物。

## 2026-09-18：Owner、Matcher、Settlement Lane 联合诊断（diagnose-upstream-20260918T092300Z）

### 目的与口径

- 本轮继续使用历史 `MIXED batch20` 口径：单节点、`LINEAR_PERPETUAL`、1 matcher、4 Account Lane、128 symbols、1000 retail users、全局/session/owner window `256`、G1、JDK 27、30s warmup/60s measurement；无 wallet、无 JFR。
- 只打开默认关闭的三个诊断开关：`surprising.owner.poll-diagnostics=true`、`surprising.matcher.wait-diagnostics=true`、`surprising.settlement.wait-diagnostics=true`；不改业务窗口、产品线、消息顺序或等待策略。因此该轮用于定位，不作为最终吞吐成绩。
- 诊断代码分别聚合 Owner gate 原因、Matcher worker 的 idle/execute/park 时间、Settlement Lane 的 idle/not-ready/handoff/execute 时间及队列深度，不记录逐命令状态。

### 结果

- `60.037983s` 稳定窗口：`terminalBusinessOperations=18,888,206`、`offeredBusinessOperations=18,888,206`，`businessOpsPerSec=314,609.572`；Core `terminal/offered messages=1,805,838`，`coreMessagesPerSec=30,078.766`；`fills=4,495,360`，`fillsPerSec=74,876.528`。
- 完成性仍为 `mixedCapacity=PASS`、`unfinished=0`、`peakInFlight=256`、`mixedVerify=PASS`、`fundsDiff=0`。诊断开销使吞吐低于无诊断主轮 `331,582.599 businessOps/s`，不作绝对吞吐比较。
- 客户端在途窗口阻塞 `windowBlockedNanos=51,308,863,542`，约占稳定窗口 `85.47%`；说明发压端是被终态释放速度反压，不能用“提高发压线程数”解释差距。
- Owner 最终聚合为 `waitingNoCompletion=7,505,774`、`pendingIngressNotReady=3,494,811`、`noDrainWork=1,304,564`，可分类 gate skip 中约 `61.00%/28.40%/10.60%`。但累计 `noProgressNanos=339,490,538`，只约占 60 秒窗口 `0.57%`，Owner Java poll 本身不是主耗时。

### 关键排除证据

- 队首 direct settlement 的多个采样均为 `settlementDispatched=true`、`settlementReady=true`、`settlementResultPrepared=true`、`settlementCompletedLanes=requiredLanes`、`settlementComplete=true`；没有发现“Lane 已收到但一直等不到 payload/完成标志”的卡死。
- 四条 Settlement Lane 的 `notReadyWaitNanos=0`、`parkCount=0`，队列深度采样为 0；Lane 的累计 `idleWaitNanos` 远大于 `executeNanos`，说明 JFR 中 `SettlementLaneWorker.run()` 的高样本主要是等待/轮询循环，不能当作结算业务计算占满。
- Matcher worker 最终约 `idleWaitNanos=94.06s`、`executeNanos=33.79s`、`executeCount=2,583,062`、`parkCount=1,098,344`，submission/completion depth 均为 0；Matcher 没有形成持续计算 backlog。
- Owner gate 的 `waitingNoCompletion` 是窗口背压表现：它在等待有序终态事实；不是 Owner 处理完成通知太慢。直接把 Owner gate 放开会重复探测同一个未完成前缀，破坏有序提交边界。

### 定位结论

真正的限制点已经从“某个 Matcher/Lane 队列卡死”收敛为：**固定 256 in-flight 窗口下，批量业务的终态释放 cadence 受跨线程交接和有序提交延迟限制；同时 4 条 Lane 在没有工作时仍长期 BUSY_SPIN，持续消耗共享节点 CPU，放大 Owner、Aeron service 和 Matcher/Lane 之间的争用。**

也就是说，`windowBlocked` 是表象，Owner gate 是保护机制，Matcher/Lane 的 ready 标志没有证据显示错误；当前最值得改的是 Lane 的空闲/交接等待策略及其唤醒边界，而不是调大窗口、绕过 FIFO，或重写撮合算法。订单快照、撮合结果、解码对象分配仍是已由 JFR 找到的次级成本，待等待机制验证后再处理。

### 解决方案与验证顺序

1. 在 [SettlementLaneWorker.java:286](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/lane/SettlementLaneWorker.java:286) 做单变量的自适应等待：保留短 BUSY_SPIN，随后对 `IDLE`、`NOT_READY`、`HANDOFF` 分别使用有界 `yield/park`；所有 admission、Matcher result、handoff resume、Owner completion 的发布路径必须唤醒对应 Lane，保持现有 SPSC 所有权和确定性顺序。不能直接全局切 `BLOCKING`，因为既有对照吞吐下降 `7.32%`。
2. 用同一 `window=256`、同一 `MIXED batch20` 做无 JFR A/B：比较 `businessOps/s`、`windowBlocked%`、terminal p99、Lane idle/not-ready/handoff 比例，并再次核对 `unfinished=0`、`fundsDiff=0`、余额/冻结/持仓/订单终态。
3. 等等待机制验证后，再处理 JFR 已确认的 `OrderRuntime.snapshot()`、`CoreMatchingResult.fromNativeWithEvidence()`、`MatcherResult.from()`、解码与准备对象分配；每次只改一处，并用受影响 JMH 的 `-prof gc` 和端到端压测证明收益。
4. 保持 `peakInFlight=256` 作为验收约束。调大窗口只能隐藏终态延迟，不能证明卡点已经解决；若 A/B 后仍有明显窗口阻塞，再用 per-thread JFR/CPU 采样定位 Aeron service 或提交阶段的剩余成本。

### 构建、测试、产物与清理

- JDK27 Maven package 成功；受影响测试：`ClusterCommandPipelineTest` `244` tests，失败/错误 `0`、跳过 `1`；`RuntimeCommitRecoveryTest` `8/8`，失败/错误/跳过 `0`。
- 最新诊断产物清理前：`diagnose-upstream-20260918T092300Z` `2.9G`、`diagnose-upstream-20260918T091900Z` `2.7G`、`diagnose-lane-20260918T090718Z` `2.7G`。最新 client/node log SHA-256 分别为 `b5d15c0d5e294baf07ea76b0e1e93d2c93d2e4e8ada29283682587483d857c62` / `0fac22b027775c4006521352648fe960be4bb059c51bc008fe08d3a268ea027f`。
- 本轮诊断已完成，未实施业务优化；测试和压测进程均已停止。上述三个诊断目录随后整体移入 `/Users/atomex/.Trash/surprising-ex-upstream-diagnostics-20260918T092300Z`，可恢复；不删除编译产物或用户已有文件。
- 本节结论为“机制层根因已定位、代码修复尚未实施”。后续实现自适应等待后必须重新跑受影响测试和同口径压测，不能把诊断轮吞吐当作修复后的成绩。

## 2026-09-18：阶段墙钟耗时确认（confirm-stages-20260918T175500Z）

### 计划、环境与偏差

- 目标是区分 Owner 业务处理、Owner 输入队列、Matcher→Lane、Lane 执行、Lane 完成→Owner 观察哪一段限制终态释放；不改变 BUSY_SPIN、window `256`、产品线或业务顺序。
- 使用现有 `qualify-aeron-async-stages.sh` 的 `end_to_end` 阶段，单节点/1 matcher/4 lanes、`LINEAR_PERPETUAL`、`MIXED batch20`、128 symbols、1000 retail users、JDK27/G1、30s warmup/60s measure，并启用 `core.settlementLatencyDiagnostics=true` 和 JFR。服务端只按约 `1/64` 采样 `SettlementLatency`、`CommandBoundaryLatency`，不记录逐命令日志。
- 本机是 macOS x86_64 单节点，脚本没有设置 OS CPU affinity；因此本轮只确认阶段墙钟耗时，不把本机调度条件等同于生产独占 CPU。生产若保持 Matcher/Lane/Owner 独占 CPU，BUSY_SPIN 仍可作为生产基线。
- 前两次脚本执行无效：第一次使用了已删除的 `SurprisingCoreBootstrap` 入口，第二次 `jcmd JFR.dump` 使用了 JDK27 不支持的 `compress=true`；两次客户端均为 `NOT_CONNECTED` 或无服务端 JFR，不纳入结论。脚本现已改为使用 shaded jar manifest 入口、停止前主动 dump JFR，并将 artifact root 转为绝对路径。

### 有效结果与阶段数据

- 稳定窗口 `60.006092s`：`businessOpsPerSec=259,044.082`、`coreMessagesPerSec=24,786.662`、`fillsPerSec=61,646.689`；`mixedCapacity=PASS`、`unfinished=0`、`peakInFlight=256`、`mixedVerify=PASS`、`fundsDiff=0`。该轮含 JFR 和阶段事件采样，只用于归因。
- JFR 服务端 `DataLoss=0`；`SettlementLatency` 样本 `30,780`，`CommandBoundaryLatency` 样本 `141,524`。阶段事件是采样值，单位均为纳秒：

| 阶段 | p50 | p90 | p99 | max | 解释 |
|---|---:|---:|---:|---:|---|
| `admissionExecution` | `4.3us` | `8.1us` | `21.2us` | `241.8us` | Owner 真正执行准入/准备逻辑 |
| `ingressToAdmission` | `3.60ms` | `6.93ms` | `14.06ms` | `226.62ms` | Owner 输入队列/批处理到准入的等待 |
| Matcher→最后一条 Lane 开始 | `2.2us` | `83.3us` | `607.6us` | `11.64ms` | Matcher publication 到 Lane 开始执行 |
| Matcher→所有 Lane 完成 | `19.5us` | `109.0us` | `624.4us` | `11.65ms` | 包含 Lane 执行 |
| Lane 实际执行最长值 | `10.5us` | `55.2us` | `92.5us` | `5.97ms` | 账户状态实际修改时间 |
| Lane 完成→Owner 观察 | `371.6us` | `2.05ms` | `3.47ms` | `10.18ms` | Owner 观察完成通知并进入有序提交前的间隔 |
| `ownerToEgress` | `4.7us` | `20.5us` | `298.0us` | `10.99ms` | 结果出口，不是主要阶段 |

- JFR Thread CPU：四条 Lane 各约 `5.97%–5.99% user`、`0.25%–0.26% system`；`trading-owner--1` 约 `0.45% user`、`0.36% system`；`core-matcher-0` 约 `0.42% user`、`0.36% system`。Lane 的实际业务执行只占测量墙钟约 `28.1%–29.2%`，因此 `SettlementLaneWorker.run()` 的高 ExecutionSample 主要是等待/自旋循环，不能当作 Lane 业务计算饱和。

### 确认结论

1. **不是 Owner 业务处理耗时长。** `admissionExecution` p99 只有 `21.2us`；Owner CPU 也很低。
2. **主要可见延迟在 Owner 输入排队/批处理。** `ingressToAdmission` 的 p50 已达 `3.60ms`，明显高于实际准入执行；当前 `TradingOwnerLoop` 每轮最多取 64 条输入后再统一推进，且还受 pending ingress、依赖 fence 和固定窗口 gate 影响。这是 Owner 调度/队列 residence 成本，不是 Owner 业务计算成本。
3. **终态侧 Lane 计算不是主要成本。** Lane 实际修改账户状态 p99 `92.5us`，但 Lane 完成到 Owner 观察 p99 `3.47ms`，说明主要应检查 completion notification 被 Owner 观察及有序退休的间隔。
4. **BUSY_SPIN 不是当前已证实的根因。** 生产独占 CPU 时可以保留；本轮没有证据支持通过 park/unpark 解决吞吐问题。唤醒策略只能作为资源利用或尾延迟的独立 A/B，不能替代阶段确认。

### 下一项最小验证与方案

- 下一轮只改变 Owner 输入批处理预算/推进时机：保持 `window=256`、BUSY_SPIN、同一 workload，比较 `ingressToAdmission`、`lanesCompleteToOwner`、window blocked、terminal p99 与吞吐；同时验证 `unfinished=0`、`fundsDiff=0`、余额/冻结/持仓/订单终态和恢复一致。
- 优先在现有 [TradingOwnerLoop.java:156](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingOwnerLoop.java:156) 输入批处理边界与 [TradingCoreOwner.java:141](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java:141) pending ingress 推进边界做局部 A/B，不绕过 `OrderedCommitCoordinator` 的 FIFO ready gate。
- 若降低输入批处理等待后 `ingressToAdmission` 明显下降而吞吐不降，卡点就是 Owner 输入调度；若该段不变，再单独追 `lanesCompleteToOwner` 的通知/退休路径。当前不再把“Owner 处理慢”或“BUSY_SPIN 抢 CPU”作为结论。

### 清理与状态

- 有效产物清理前约 `2.4G`；client log SHA-256 为 `775d02867481428b7ecb17409332b81457c24de40f04a932c960dcacb2117c09`，node log SHA-256 为 `8f1918f99019aef50ece4f4db3ba801a9d517fc54c98dbf8926d044169e24902`；服务端 JFR 位于该 run 目录下的相对路径展开目录中，JFR summary 显示 `DataLoss=0`。
- 本节结论为“Owner 业务执行已排除，Owner 输入排队和 Lane→Owner 观察间隔已量化；下一步是单变量验证 Owner 批处理/推进时机”。本轮未修改交易业务逻辑。

## 2026-09-18：服务器 Core Aeron-Service MATCH_STREAM 吞吐（废弃口径）

> 修订：本节使用 `ClusterCapacityMain + MATCH_STREAM`，输出是单订单终态请求吞吐，不能与本地 `MIXED batch20` 的 `businessOpsPerSec` 比较。该入口后续不再使用；原始结果仅保留作历史记录。

### 口径与环境

- 目标仅为 `surprising-aeron-core/surprising-aeron-service` 的 Core/Aeron 服务吞吐；服务器为 `surprising-ex`（主机 `vmi3458356`），代码提交 `7fffebb5`，Linux、12 vCPU、47 GiB RAM、HotSpot JDK 27、Maven 3.8.7。
- 单节点、1 个 matcher、4 条 account lane、`LINEAR_PERPETUAL`、128 symbols、100 users、4 workers、4 connections、BUSY_SPIN、DEDICATED、全局/会话 in-flight window `256`；30 秒 warmup、60 秒测量；未启动 wallet。
- 基准包通过 `mvn -B -ntp -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package` 构建。服务器已有 Kafka/wallet 进程未启动、未修改，因此本结果不是隔离硬件容量上限。
- 首次启动因 JDK 27 缺少 `--add-opens=java.base/java.util.zip=ALL-UNNAMED` 未进入业务流量，已排除；补齐 JVM opens/exports 后重新执行有效轮次。

### 结果

- `capacity=PASS`，有效时长 `60.607s`；提交/接受/终态完成均为 `15,684`，撮合事件 `7,842`。
- Core 终态吞吐 `258.781/s`，撮合事件吞吐 `129.391/s`；市场数据命令 `1,952`，Core terminal messages `17,636`。
- 正确性：`failures=0`、临时重试 `0`、未完成请求 `0`、提交数=完成数 `17,636`、`fundsDiff=0`、`bookLevels=0`，峰值在途 `255/256`。
- 端到端 terminal latency：p50 `391,905us`、p99 `2,019,557us`、p99.9 `3,279,945us`。
- JVM 采样：退出前 teardown 采样 RSS 约 `654,560K`、CPU 约 `413%`，不是严格测量窗口 CPU 序列；堆已提交 `524,288K`、使用 `156,333K`，NMT reserved/committed 为 `3,116,404/683,044 KB`；Core/client GC pause 事件分别为 `8/14`。

### 清理与结论

- Core/client 及无效首次轮次均已停止；本轮临时目录、日志和报告已清理，服务器保留已构建 jar；未触碰已有 Kafka/wallet 进程，磁盘空间正常。
- 本轮可确认 Core Aeron-Service 在该服务器配置下完成了短时吞吐和业务一致性验证；由于服务器存在其他服务且未采集严格隔离的时间序列资源曲线，不将其作为硬件峰值，也不与本机历史吞吐直接比较。

## 2026-09-18：服务器 Core Aeron-Service MIXED batch20 吞吐

### 口径与环境

- 使用 `ClusterMixedCapacityMain`，`MIXED batch20`；单节点、1 matcher、4 account lanes、128 symbols、1000 retail users、全局/session in-flight `256`、`LINEAR_PERPETUAL`，30 秒预热、60 秒测量。
- 服务器 `surprising-ex`（`vmi3458356`），提交 `7fffebb5`，HotSpot JDK 27、Maven 3.8.7、12 个 AMD EPYC 2.0GHz vCPU、47 GiB RAM；Core 使用 `SHARED_NETWORK + YIELDING`，matcher/settlement 使用 `BUSY_SPIN`。本机未推送的改动未混入本轮。
- 服务器已有 Kafka/wallet 进程未启动、未修改；本轮未创建持久化压测脚本，仅使用临时启动命令，结束后已清理。

### 结果

- `mixedCapacity=PASS`；有效窗口 `60.485s`，`terminalBusinessOperations=490,045`，`offeredBusinessOperations=490,045`，`businessOpsPerSec=8,101.880`。
- Core terminal messages `52,285`，`coreMessagesPerSec=864.424`；fills `115,200`，`fillsPerSec=1,904.594`；`unfinished=0`、`peakInFlight=256`、`mixedVerify=PASS`、`fundsDiff=0`。
- 业务延迟 p50/p99/p99.9（微秒）：`PLACE_ORDER 173,539/983,039/1,275,068`，`CANCEL_ORDER 199,098/980,942/1,274,019`，`APPLY_MARK_PRICE 223,346/1,628,438/1,897,922`，`PLACE_ORDER_BATCH 228,851/1,608,515/1,906,311`，`CANCEL_ORDER_BATCH 227,672/1,373,634/1,800,404`。
- 测量期间 Core 退出前采样约 `534% CPU`、RSS `1,650,412K`；堆 committed/used `944,128K/334,393K`；NMT reserved/committed `3,121,979/1,128,763KB`。该 CPU 采样不是完整时间序列，且宿主存在其他服务。

### 结论与清理

- 本轮业务正确性通过，但吞吐仅约 `8.1k businessOps/s`，与本地 `41.5 万 ops/s` 不同量级；由于代码提交、硬件/虚拟化环境和同机干扰均不完全相同，不能据此判断代码退化，也不能作为服务器容量上限。
- 本轮 Core/client 已停止，约 `298M` 临时目录、日志和 Aeron 数据已删除；删除前 client/node 日志 SHA-256 分别为 `0d814d9362840ab5197aae2812447d0e641a7bb4d20b2093372afda8a23d427d` / `6fb0290289f308fdb858afd351194c40c8ef759899f761aadd963c2b5805baeb`；服务器磁盘剩余约 `256G`。

## 2026-09-18：Owner 输入批处理 A/B（owner-batch-20260918T201300Z~20260918T202500Z）

### 目的、改动与固定口径

- 目标是验证阶段墙钟确认提出的最小假设：Owner 每轮读取 ingress 的预算是否造成额外队列 residence，缩小预算能否降低 `windowBlocked` 并提高满载吞吐。
- 只新增运行时参数 `-Dsurprising.aeron.owner-input-batch-size=N`，默认仍为 `64`；代码入口为 [TradingOwnerLoop.java:171](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingOwnerLoop.java:171)。没有改变 FIFO ready gate、跨线程所有权、Matcher/Lane 顺序或业务语义。
- 四档首轮为 `64/32/16/8`，另对 `64`、`16` 各复跑一次。固定单节点、1 matcher、4 lanes、`LINEAR_PERPETUAL`、128 symbols、`MIXED batch20`、G1、JDK27、`SHARED_NETWORK + YIELDING`、Matcher/Settlement `BUSY_SPIN`、全局/session/owner window `256`、30s warmup/60s measurement、无 JFR。
- 本机未设置 OS CPU affinity，因此结果用于同机相对 A/B，不等价于生产独占 CPU 的绝对容量；每轮均要求 `peakInFlight=256`、`unfinished=0`、`mixedVerify=PASS`、`fundsDiff=0`。

### 结果

| Owner batch | businessOps/s | 相对首轮 64 | core messages/s | fills/s | windowBlocked | 正确性 |
|---:|---:|---:|---:|---:|---:|---|
| 64 | 316,094 | 基线 | 30,220 | 75,234 | 50.846s / 84.74% | PASS |
| 32 | 290,334 | -8.15% | 27,767 | 69,095 | 51.056s / 85.09% | PASS |
| 16 | 300,952 | -4.79% | 28,778 | 71,628 | 50.665s / 84.44% | PASS |
| 8 | 297,418 | -5.91% | 28,440 | 70,784 | 50.488s / 84.15% | PASS |
| 64（复跑） | 295,833 | — | 28,290 | 70,410 | 50.874s / 84.76% | PASS |
| 16（复跑） | 309,709 | — | 29,612 | 73,711 | 50.288s / 83.80% | PASS |

- 64 两次均值为 `305,964 businessOps/s`，16 两次均值为 `305,331 businessOps/s`，差异为 `-0.21%`；64 单次范围 `295.8k~316.1k`，已显示本机单次抖动约 `6.4%`，因此首轮 `64` 与 `16` 的 `4.79%` 差异不能视为确定性收益或回退。
- 四档首轮的 `windowBlocked` 均在 `84.15%~85.09%`，批处理缩小没有释放固定 `window=256` 的主要反压；`pipelineHighWater.context` 每轮仍为 `255`，说明在途窗口持续满载。
- 六轮均 `mixedCapacity=PASS`、`unfinished=0`、`peakInFlight=256`；六轮 `mixedVerify=PASS`、`fundsDiff=0`，并且 `offeredBusinessOperations=terminalBusinessOperations`，没有丢单或未终态。

### 结论与方案

1. **“Owner 一次取 64 条导致 Owner 处理太慢”没有被 A/B 证实。** 缩小到 32/16/8 没有降低窗口反压，也没有稳定提高吞吐；64 与 16 的重复均值基本相同。
2. 当前应保留默认批预算 `64`，不把 32/16/8 作为优化方案；它们增加轮次切换/调度机会，却没有改变跨线程终态交接和有序提交 cadence。
3. 生产方案继续保持独占 CPU + `BUSY_SPIN` 基线，不引入未经证实的唤醒/park 改造；下一步若继续优化，应针对已量化的 `ingressToAdmission` 和 `lanesCompleteToOwner` 做事件级测量，检查 Owner 推进与完成通知观察的交错时机。不得绕过 `OrderedCommitCoordinator` 的有序提交约束，也不能只调大窗口掩盖 cadence 问题。
4. 本轮只验证了 Owner 输入批预算这一单变量；它排除了“缩小批次即可解决”的方案，但没有证明 completion cadence 的实现已优化。若要获得稳定提升，需要在生产等价 CPU 隔离条件下对交接/提交路径做单变量代码改动，再重复同口径压测。

### 构建、测试、产物与清理

- HotSpot JDK27 Maven package 成功。受影响测试：`ClusterCommandPipelineTest` `244` tests，失败/错误 `0`、跳过 `1`；`RuntimeCommitRecoveryTest` `8/8`，失败/错误/跳过 `0`；`bash -n qualify-aeron-async-stages.sh` 通过。
- 每轮原始目录约 `2.8G~3.0G`，共约 `14.5G`。client/node 日志 SHA-256 已在清理前记录；例如首轮 64 为 `d49a52e37e2b52fcdefa781a2b646a11f7b2ba9bc351538181ffab5a6c27ac36` / `59a647ec755cb549c3f084a257503d7d2d5b315fdf73ea424a7b3c91860a8240`，复跑 16 为 `aee43bdc6fbf18a7ad6e8d3dff1f98636c757ab2e906db0a9143809e46ef2a38` / `37af35ff76f9eba7a31156e74be279d89931e1818edf7295d8bba8d94ad139d6`。
- 六个本轮目录在分析完成后整体移入 `/Users/atomex/.Trash/surprising-ex-owner-batch-ab-20260918T203000Z`，可恢复；不删除编译产物或用户已有文件。清理后确认无本轮节点/客户端进程残留，并检查磁盘空间。

## 2026-09-18：终态交接具体位置复核（lane-commit-signal-20260918T204300Z）

### 已定位的代码链路

1. **LaneCommit 完成发布点**：`LaneCommitEvent.execute()` 在 [LaneCommitEvent.java:184](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LaneCommitEvent.java:184) 用 `LONGS.setRelease(completedLanes, ...)` 写完成位，但没有调用 `TradingRuntimeState.signalOwnerCompletion()`。该事件由批量终态提交 [OrderBatchExecutor.java:951](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderBatchExecutor.java:951)、控制命令 [OrderedCommitCoordinator.java:488](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderedCommitCoordinator.java:488) 和直接命令 [CoreDirectCommandFlow.java:123](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/CoreDirectCommandFlow.java:123) 使用。
2. **Owner gate 不认识该完成源**：Owner 在 [TradingCoreOwner.java:350](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java:350) 处于 `waitingForMatchingNotification` 时，只检查 `ownerCompletionAvailable()`；而 [TradingCoreOwner.java:565](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java:565) 只检查 Matcher completion 和 `hasMatchingNotifications()`。`LaneCommitEvent` 的完成位没有进入这两个通知源。
3. **实际延迟位置**：因此 Lane 完成后，Owner 只能走 [OwnerIdleStrategy.java:43](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/cluster/OwnerIdleStrategy.java:43) 的下一次 bounded idle probe（有 pending command 时最多 `10us`），再回到 `pollCommands()` 的 [TradingCoreOwner.java:571](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java:571) 和 `laneCommitComplete()` 检查。这里是“完成已发生，但 Owner 尚未观察”的具体 cadence 缺口。

### 排除与验证边界

- 普通 Matcher→Lane settlement 不是同一个遗漏：其完成路径在 [MatcherSettlementEvent.java:750](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/MatcherSettlementEvent.java:750) 发布完成通知，并在 [TradingRuntimeState.java:2293](/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java:2293) 设置 ready 位并 signal Owner。
- 临时在 `LaneCommitEvent` 完成位后补 `signalOwnerCompletion()` 的单轮结果为 `314,753 businessOps/s`，在此前同机 64 档 `295,833~316,094 businessOps/s` 范围内，不能把它认定为全局吞吐修复；该临时改动已撤回。受影响测试在临时改动下仍为 `252` 个通过、`0` 失败/错误。
- 所以当前准确结论是：**已定位一个确定存在的通知/门禁缺口，属于终态交接 cadence 的具体实现问题；但它不是目前全局吞吐下降的唯一根因。** 下一步应为 LaneCommit 增加与完成位一致的 ready 事实/通知，再验证 Owner gate 是否消费该事实；不能只补无状态 wakeup，也不能直接绕过有序提交。

## 2026-09-18：JDK27 口径复核与 Matcher mailbox cadence 定位（20260918T213700Z~20260918T221500Z）

### 先校正 41.5 万历史结果的可比性

- 历史 `compare256-fixed-20260916T1320` / `profile256-20260916T1345` 使用的是 Oracle GraalVM `25.0.1`；本轮按 `AGENTS.md` 要求使用 Corretto HotSpot JDK `27.0.0.33.1`。两者不是同一 JVM，历史 `415,217~419,611 businessOps/s` 不能作为当前 JDK27 的直接基线。
- 当前 JDK27、同一 `MIXED batch20`、单节点/1 matcher/4 lanes、window `256`、30s/60s 的 ADAPTIVE mailbox 对照为 `331,930 businessOps/s`、`31,728 coreMessages/s`，正确性 PASS；因此先前把 41.5 万与当前 3.3~3.4 万 core msg/s 直接比较，会把 JVM 差异误判成业务代码退化。

### 单变量 A/B

| 配置 | businessOps/s | coreMessages/s | windowBlocked | peak | 正确性 |
|---|---:|---:|---:|---:|---|
| Owner ADAPTIVE + Matcher mailbox ADAPTIVE | 331,930 | 31,728 | 51.34s | 256 | PASS |
| Owner BUSY_SPIN + Matcher mailbox ADAPTIVE | 292,091 | 27,934 | 50.66s | 256 | PASS |
| Owner ADAPTIVE + Matcher mailbox BUSY_SPIN | 342,283 | 32,714 | 50.32s | 256 | PASS |
| 上行同配置，window 512 | 344,041 | 32,878 | 50.74s | 512 | PASS |

- Owner 全程 BUSY_SPIN 在未做 OS CPU affinity 的本机反而下降约 `5.5%`，说明它会和 `SHARED_NETWORK` 的 Aeron 线程争用 CPU；不能仅凭“生产独占核”假设就把 Owner BUSY_SPIN 当作已验证修复。
- Matcher mailbox 在 64 次自旋后固定 `parkNanos(100us)`；切换为可配置 `-Dsurprising.aeron.matcher-pipeline-wait-strategy=BUSY_SPIN` 后提升约 `3.1%`，说明这是一个真实但局部的 cadence 成本。默认仍为 ADAPTIVE，生产独占核环境可单独启用并复测。
- window `256→512` 只提升约 `0.5%`，且窗口阻塞仍约 `50s/60s`；扩大窗口只能增加在途量，不能消除下游终态退休瓶颈。

### JFR 阶段证据

- JDK27、Matcher mailbox BUSY_SPIN 的 JFR 轮保持 `clientPass=true`、`unfinished=0`、`fundsDiff=0`。Owner/Matcher/4 Lane 的单核 CPU 约为 `97.18%/98.26%/98.25~98.29%`，说明当前 JDK27 是满载状态，不是发压不足。
- `admissionExecution` 为 p50 `4.3us`、p99 `17.5us`；但 `ingressToAdmission` 为 p50 `1.86ms`、p99 `9.44ms`。等待主要发生在 `PendingClusterIngress` 到准入之间，不在准入业务函数本身。
- Matcher mailbox BUSY_SPIN 后，`matcherToLastLaneStart` p99 约 `418us`，相对此前 ADAPTIVE JFR 的 `553us` 有改善；但普通 settlement 的 `lanesCompleteToOwner` 仍为 p50 `341us`、p99 `2.96ms`，没有被该改动消除。
- 最终 `LaneCommit` 的 `completeToOwner` 多数为微秒级，`ownerToRelease` p50 约 `0.22us`；因此最终 LaneCommit release 不是全局吞吐主因。Owner ExecutionSample 的首个业务栈主要落在 `TradingRuntimeState` 与 `OrderedCommitCoordinator`/`TradingCoreOwner`，表明 Owner 同时承担入口准入、异步结果收集、资金/订单/持仓 publication 和有序退休，队首完成时常被下一轮 Owner 工作延后观察。

### 当前真正的卡点与方案

当前不是单一的“Owner 处理函数太慢”，而是两层 cadence 叠加：

1. Matcher mailbox 的 park/wakeup 放大了 Matcher→Lane 的提交间隔；这是可局部配置的优化，生产独占核可使用 BUSY_SPIN，并保留 ADAPTIVE 作为非独占核默认。
2. 更大的剩余成本在 Owner 单线程的 ingress 排队和 FIFO 有序终态退休：`ingressToAdmission` 毫秒级，而 `admissionExecution` 微秒级；`lanesCompleteToOwner` 仍有毫秒级尾延迟。不能绕过 `OrderedCommitCoordinator` 或复制业务状态来“提前释放”窗口。

下一步真正值得实现的优化是围绕现有 Owner 所有权边界减少一次命令一次退休的调度/扫描成本：在不改变日志顺序、资金不变量和 Lane 单写者的前提下，验证连续 ready 命令的批量收集/退休，以及 Owner 入口与终态收集的交错预算。Owner 输入批量 `64/32/16/8` 已证明单独改预算没有稳定收益，因此不再把它作为方案。任何批量退休改动都必须用资金守恒、订单终态、恢复和同口径 JFR 重新验证。

### 本轮验证与清理

- JDK27 精确测试：`ClusterCommandPipelineTest`、`RuntimeCommitRecoveryTest`、`OwnerIdleStrategyTest`、`MatcherCommandPipelineTest` 共 `261` 个，失败 `0`、错误 `0`、跳过 `1`；benchmark Maven package 成功；脚本 `bash -n` 和 `git diff --check` 通过。
- 所有本轮节点/客户端已停止；本轮压测 artifact、JFR、日志和中间解析文件在记录完成后移入 `/Users/atomex/.Trash/`，可恢复；未删除编译产物或用户已有文件。磁盘空间充足，未启动 wallet。

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

## 2026-09-19 Lane 终态合并减法（实现完成，运行验证受环境限制）

### 范围与静态依据

- 用户要求优先验证并消除合并路径的重复删除、查表及多余清零。基于当前 master `6d1082dca7cf4b05319ede997c32a31ef5fb3037`，不检出/重跑旧版本，不将历史压测作为收益对照。本轮保留既有未提交改动，尚未 commit/push。
- 业务顺序：Lane 完成结算并交接不可变发布值/删除 ID → Owner 应用仍存活的值 → 统一应用终态删除并收集变更 ID → 按原提交顺序消费终态保留收据 → 复用缓冲。异常处理及结算完成条件不变，不新增线程、业务状态、容器或提交阶段。
- `LanePublication.publish` 原先对值缓冲和 removed routes 交集中的订单/预留，在 applyPublished(null) 与最终 remove 遍历各删一次；后一次仍要计算哈希并探测已不存在的键。现在有 removed route 的值不再先调用 applyPublished，统一由路由删除集合处理一次。没有 after-image 的删除仍执行；没有路由但显式 null 的值仍由 applyPublished 删除，避免漏删。
- 删除 ID 收集并入原删除遍历，省去单独遍历 removedOrderRoutes；保留值遍历中的变更 ID 添加位置，维持 PrimitiveLongChangeSet 的首次出现顺序。未把 contains 改成 remove 来消费删除集合，避免把发布表重复查找换成集合删除/压缩成本；仍保留判别终态的必要成员检查。
- `TradingRuntimeState.LaneDelta` 的 terminalOrderIds/terminalOrderUsers 私有数组由 recordTerminalOrder 完整写入后才递增 count。所有现有生产消费者（TerminalStateRetention.acceptBatch 及 TerminalOrderSink adapter）均按 terminalOrderCount 读取；不改变快照/业务模型。clear 去掉两列 primitive 的 Arrays.fill，重置 count 即限定有效区间；客户号/OrderRuntime引用仍按旧有效区间清空，其他实体缓冲和删除集合清理保留。原语容量没有增长或新增副本。
- `TerminalTombstoneStore.putIfAbsent` 已是单次实体探测再插入；本轮没有证据证明该探测冗余，保留重试/幂等、乱序 ID、FIFO 保留及淘汰语义。`LanePublishedMap.applyPublished` 非空值的 get/equals 也保留，确保等值 resting order 的原发布对象身份不被改变。

### 已补但尚未运行的回归验证

- `LanePublishedMapTest` 新增删除次数断言：在测试空实例上替换内部 Agrona map 为测试计数子类，无生产计数器/注入入口；覆盖 after-image+route、null+route、route-only、null-only、已不存在键、正常等值对象、重复 publish，以及 changed IDs 开/关。每个待删 ID 预期一次 remove；原逻辑交集会触发两次。另扩展异常回收用例，确认未发布的删除不会在下次复用时误删订单/预留。
- `SettlementChangesReuseTest` 新增 65→1→0→7→0 的终态收据增长/缩小/空批复用，核对 count 内订单/用户 ID 正确、所有客户号/订单引用在回收后为空、未发布收据不泄漏。测试不依赖 count 外的原语值为零。
- `ContinuousOwnerBenchmark`（既有六产品线 JMH）加强 trial teardown 的恢复断言：整张活动订单表为空，所有256账户预留为空，余额/冻结和终态计数保持原检查；核对移出计时区域，不改变测量工作负载。`ContinuousOwnerBenchmarkTest` 新增六产品线20→1→20批大小切换，预期20992 business终态、1536 Core终态且 accepted=terminal；此切换仅在JUnit复用验证，JMH仍每fork固定参数。

### 当前环境阻断与待执行计划

- 本轮实际读取的 `AGENTS.md` 明确禁止本机 Java 构建/测试/性能验证，也停用旧服务器 surprising-ex；在用户指定新服务器前，不发起远程测试。本轮仅阅读/编辑/静态检查，已询问新验证服务器和工作目录；没有执行 java、Maven、测试或压测进程。CodeGraph工具未暴露，结构核查退回本地源码。
- 已执行：`git diff --check` 通过；人工核对发布前后状态/变更 ID 顺序、终态数组全部读写方、Maven依赖和基准恢复边界。**未编译、未运行任何新增或既有测试、未采 JMH/JFR；不得记作功能或性能通过。** 无本轮Cluster/Archive/JFR/日志生成物需要清理。
- 新服务器确认后先核对 HotSpot JDK27 的 `java -version`/`mvn -version`、磁盘/CPU/内存/系统干扰并追加机器信息。精确回归：`mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=LanePublishedMapTest,SettlementChangesReuseTest,ClusterCommandPipelineTest,TerminalTombstoneStoreTest,TerminalStateRetentionTest -Dsurefire.failIfNoSpecifiedTests=false test`；共享状态改动还需 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`，覆盖六产品线直接受影响测试及基准集成测试。
- 性能待锁定服务器参数后执行：当前 master+本轮修改，单真实Aeron成员、1 matcher、global/session in-flight256、内部做市持续运行，LINEAR_PERPETUAL MIXED batch20、4 Lane、BUSY_SPIN；无profiler主轮与JFR归因分开，warmup30s/measure60s/排空单报。六产品线 ContinuousOwnerBenchmark 采用固定batch1/20、DISTINCT/PAIRED、BUSY_SPIN、fork1/thread1，分别无profiler、-prof gc及JFR；局部JMH不冒充真实集群吞吐。长稳、真实Archive恢复与精确测量时窗也需补验，参数在服务器确定后追加，不能凭当前静态分析给收益百分比。
- 验收要求：每个终态删除键一次发布表remove；资金差0、订单/预留清除、对象身份/变更顺序/快照恢复一致，accepted=terminal、unfinished/backlog0。性能无新增业务SLA，报告实际稳态吞吐/各类尾延迟/分配/热点及缺口；吞吐改善需要新实测支持，代码删除次数减少不能替代性能证据。
- 结论：**已实现局部减法，静态检查通过，运行验证待新服务器，暂不提交推送。** 无新增生产类/状态；仅测试增加操作计数替身与测试容器，所有资金安全和确定性边界保持原流程。

### 2026-09-19 用户指定本机：恢复验证及采集前计划

- 用户最新明确回复“本机”，本轮据此解除上节环境阻断；仅覆盖本轮验证，不擅自改写全局 AGENTS.md。HotSpot Corretto27+33、Maven3.9.16，macOS26.7/x86_64，16 logical CPU/16GiB，磁盘可用300GiB；当前master `6d1082dc`，Aeron完整未提交diff SHA256=`051fb12134c1129d6580675f1471c76e7a0c8df534108f998ec9cdd61e1ccbe8`。其他原有工作区改动保留。
- 初检存量swap1829.50MiB，vm_stat累计Swapins407466/Swapouts867081（包括历史分析进程），不把存量当本轮换页；采集前及测量期间记录换页增量/磁盘，出现持续换页/资源干扰时对应性能证据判无效，不擅自结束用户桌面进程或清理系统swap。
- 已启动 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`，包含精确回归及六产品线相关测试，日志 `/tmp/lane-merge-20260919-build.log`；构建失败则先修复本轮问题，不启动性能采集。正确性要求延续上节，业务错误不得作为环境无效掩盖。
- 真实集群无profiler主轮：`ASYNC_RUN_ID=lane-merge-20260919-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；另独立JFR轮替换run ID为 `lane-merge-20260919-jfr`、ENABLE_JFR=true。两者沿用MIXED/batch20/seed25620/128symbols/1000retail、1matcher/4Lane、Owner input64、global/session256、warmup30s/measure60s/排空、node512m–1536m/client128m–512m/G1、不绑核、无wallet；每配置1次探索验证，无旧版本对照、不保证收益百分比。
- 受影响路径的本地JMH：`ContinuousOwnerBenchmark.placeCancelWithoutTimers`，六产品线、batch1/20、DISTINCT/PAIRED、BUSY_SPIN、固定256在途上限，线程1/fork1、warmup1×1s/measurement2×1s，依次无profiler与 `-prof gc`，JFR用于实际单集群归因。短JMH仅验证各产品线改动路径/恢复与分配，不是稳态容量或长期收益结论；其他五产品线JFR、长稳和真实Archive重启未覆盖时明确缺口，不标全验收通过。
- JFR保留既有owner-commit-profile.jfc，独立进程文件、上限256MiB；分析使用流式筛选和有界内存，禁止全量展开Archive写事件。阶段差值只用同JVM nanoTime；近似测量窗口留两端2秒保护带，不能替代时钟校准。主吞吐按正式终态增量计，排空单报；JFR成绩只作扰动参考。

### 本机执行结果（以上节用户指定为准）

- 完整 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package` 成功，耗时2:08；1458 tests、0 failure/error、3 conditional skip，service930、benchmarks295。LanePublishedMapTest9项、SettlementChangesReuseTest2项均通过；ContinuousOwnerBenchmarkTest31项中30通过/1诊断开关条件跳过，包含新增六产品线20→1→20收据复用/恢复核对。无需修改测试或放宽断言。每终态键单次删除、缺失after-image、异常回收、引用释放均获得运行证据。
- 主轮node PID73195，JFR node74009、runner74027/fork74030，按上方完整命令执行；无wallet、未启动远程服务。两轮client/节点正常退出。现有基线初始化资金1,384,000,000,125units、1385用户/128symbols，内部做市/标记价持续运行。

| 项目 | 无profiler主轮 | JFR诊断轮 |
|---|---:|---:|
| 正式窗口秒数 | 60.009563 | 60.024376 |
| 正式终态business | 21,713,287 | 21,100,437 |
| 正式终态Core | 2,074,887 | 2,016,533 |
| 正式fills | 5,168,640 | 5,022,720 |
| business ops/s | **361,830.444** | 351,531.136 |
| Core messages/s | **34,575.939** | 33,595.235 |
| fills/s | **86,130.272** | 83,678.005 |
| 排空ms | 4.497396 | 6.105282 |
| 排空business/Core/fills | 2685 / 253 / 0 | 2688 / 256 / 0 |
| 最终offered=terminal business | 21,715,972 | 21,103,125 |
| 最终offered=terminal Core | 2,075,140 | 2,016,789 |
| unfinished / peak in-flight | 0 / 256 | 0 / 256 |
| windowBlocked秒/次数 | 49.972914415 / 475935 | 50.118089275 / 595601 |

- 两轮mixedCapacity/mixedVerify均PASS，fundsDiff0，population/hftPositions/reservations/loss均true；主轮总cycles2904/测量2019/hash `7fd01e5fc22071c8`，JFR总2890/测量1962/hash `f4365e2ceedf5d8c`。时间驱动不同循环数，不要求跨轮hash相等。排空后无未完成/余额或冻结偏差；准入拒绝/错误/超时未见报告。真实Archive重启未做，快照恢复由六产品线测试及每个JMH trial teardown覆盖。
- 主轮每10秒business/s：359766、344059、357880、369645、357588、382100。JFR吞吐较同代码主轮低约2.85%，含采样扰动和轮次波动，不作为代码收益。没有重跑旧版本，不用历史成绩计算本次改动提升幅度。
- 主轮业务requests：PLACE/CANCEL各516864，MARK7684，PLACE_BATCH775296/CANCEL_BATCH258432；batch平均/最大20，items15,505,920/5,168,640。以上计数含排空，对应总时长60.014060s；严格稳态吞吐使用表内窗口增量。窗口背压约83.27%，未校正coordinated omission；这些延迟不含尚未提交前的空位等待，也不代表恒定到达率负载。

主轮提交→终态延迟（µs，samples为对应requests）：

| 业务 | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|
| PLACE_ORDER | 5189 | 9445 | 10207 | 14041 | 24215 | 35454 |
| CANCEL_ORDER | 5324 | 8253 | 8888 | 11010 | 20529 | 27328 |
| APPLY_MARK_PRICE | 5091 | 9543 | 11034 | 14729 | 25755 | 29769 |
| PLACE_ORDER_BATCH | 7249 | 12263 | 13377 | 17350 | 28262 | 41058 |
| CANCEL_ORDER_BATCH | 12066 | 13508 | 14188 | 25690 | 33882 | 41123 |

### JFR 归因与资源

- 归因中部epoch `[1789779227519,1789779283543]`，约56.024s，两端各留2秒；同节点nanoTime计时有效，epoch近似对齐仍非时钟校准。节点141s录制、DataLoss0。阶段采样每命令1/64，包含抢占/GC等墙钟成本，内层分项不可与外层重复相加。
- 终态提交尝试均值/p99（µs）：PLACE n7329=8.951/24.952，CANCEL n7302=6.701/20.154，PLACE_BATCH n10963=25.381/141.146，CANCEL_BATCH n3652=29.317/63.675。回复及退休均值分别0.497/0.426/1.120/0.975µs。batch事实发布均值place4.467/cancel6.281µs，terminalBookkeeping2.929/2.298µs；普通直连路径的内层分项仍未覆盖，不作推断。
- Lane完成→有序提交p50/p99（µs）：PLACE n7310=1.773/272.407，CANCEL n7288=337.223/2698.156，PLACE_BATCH n10946=366.334/1967.359。它们是包含FIFO排队的间隔，不是执行成本，更不是唤醒延迟。
- OwnerTurn非空轮次样本18608：0条10861、1条7127、≥2条620；64条退休157轮，累计退休29763。headWait18440、budgetExhausted683，二者可重叠，不是墙钟时间占比。连续退休仍有效，没有理由把问题归为固定单条cadence。
- Owner执行样本1707：pollCommandCommit1047、commitReadyMatching1005、completeMatching778、finishOrderBatch393、collectMatcherSettlement320、LaneDelta.commitTerminalToOwner236、LanePublication.publish192、CommitPublication.publish169（inclusive互相包含）。主要剩余工作仍是终态合并/发布，不声称此局部改动已解决全部吞吐瓶颈。
- top frame：Arrays.fill90、Long2ObjectHashMap.remove60、TerminalTombstoneStore.indexEntity52、LanePublishedMap.applyPublished38。剩余fill栈包括 CommandSlot.LaneResultTarget.clear→initialize17、同clear→release9、LanePublication.clear→PlaceBatchAdmissionEvent.prepare9、LongHashSet.clear→LanePublication.publish7、OrderBatchPending.clear7；这些不是本轮删除的terminalOrderIds/Users清零，不应因方法名相同认定改动没生效。
- Owner单核归一CPU98.47%，BUSY_SPIN不能当有效计算；Lane有效执行比均值37.66%/最小37.56%。client整轮space55.01%/reap5.46%支持窗口背压，不证明客户端/网络永远非瓶颈。Owner中部file/socket事件0，不能替代全时窗零同步I/O认证。
- 中部GC105次，sum pauses574.897ms，p50/p95/p99/max=5.348/6.335/6.788/9.525ms，约1.03%窗口；会影响毫秒级尾延迟，未对每个尾峰做停顿关联。heap used167,750,320–475,415,368bytes，GC后首/末168,018,408/169,651,112，committed512MiB。NMT末值main721303KB（+6028KB）、JFR745168KB（+6319KB）；未证明长稳无泄漏。
- ThreadAllocationStatistics中部首末差：Owner5,228,623,816bytes、Matcher7,598,298,920、各Lane4.122–4.127GB、ClusterService1,999,583,208；是采样点区间增量，不是逐对象精确统计。全录制allocation压力OrderRuntime18.96%、byte[]8.25%、CoreMatchingResult8.07%、long[]6.87%、ReservationRuntime6.23%；TLAB/outsideTLAB/sample事件及各view已保留。
- 系统背景：存量swap始1829.50MiB/末1797.50MiB，所有观测点Swapouts均867081，未发现新增swap-out；main周边Swapins407786→408104、JFR408213→408597（4KiB页，时点含启动/结束，不等同节点正式窗口换页）。仍有背景换入与桌面进程干扰，非独占CPU/零干扰环境；只报告本机观察值，不外推生产容量。磁盘300→293GiB，未出现上一轮分析内存膨胀；本轮通过256MiB Java流式筛选及Python逐行分析完成。

### 六产品线局部 JMH / GC

- 执行 `bash /tmp/lane-merge-20260919-jmh.sh`，完整命令见该脚本：JMH Main / ContinuousOwnerBenchmark.placeCancelWithoutTimers、-p batchSize=1,20/accountPattern=DISTINCT,PAIRED/laneWaitStrategy=BUSY_SPIN，六产品线默认参数，-wi1 -w1s -i2 -r1s -f1 -t1 -foe true；fork固定512MiB/G1、1matcher/4Lane/window256，依次无profiler和-prof gc。初次脚本因macOS Bash3.2空数组+nounset在JVM启动前退出，已修复为非空参数数组后完整运行，未产生失败性能样本。
- 无profiler24组、GC24组全部完成；每组accepted/terminal两种计数相等，全部trial恢复断言通过。每invocation=512 Core/512×batchSize business，JMH原分数25.398–120.385 invocation/s，不能直接叫业务ops/s；以下按512×batchSize换算。本地闭环下单撤单，不含网络/Archive/持续撮合成交，短warmup与2次measurement不证明充分JIT预热或稳定容量，误差/置信区间不足，不作为改善幅度依据。

| 产品线 | batch1 DISTINCT业务/s | batch1 PAIRED业务/s | batch20 DISTINCT业务/s | batch20 PAIRED业务/s | batch20 GC bytes/business D / P |
|---|---:|---:|---:|---:|---:|
| SPOT | 53161.6 | 55608.2 | 507078.4 | 348074.9 | 2289.97 / 2288.29 |
| LINEAR_PERPETUAL | 51923.9 | 61637.0 | 476438.8 | 312653.1 | 2262.26 / 2351.19 |
| INVERSE_PERPETUAL | 52294.0 | 57548.1 | 457761.3 | 260071.4 | 2269.57 / 2313.57 |
| LINEAR_DELIVERY | 50808.2 | 54299.3 | 495012.3 | 322079.8 | 2315.69 / 2309.61 |
| INVERSE_DELIVERY | 47943.3 | 51870.9 | 491651.0 | 342336.5 | 2266.03 / 2314.24 |
| OPTION | 51290.2 | 51420.8 | 483451.2 | 340458.4 | 2258.01 / 2303.63 |

- bytes/business = gc.alloc.rate.norm（bytes/invocation）÷512÷batchSize；全部配置2258.01–5171.70bytes/business。batch20各组gc.alloc.rate439.72–712.42MB/s、GC8–10次/31–50ms（JMH汇总语义，不是单集群60秒窗口）；不能将稀疏JFR对象采样当精确对象数。主/GC JSON保留每组原分数、原始iterations、采样单位及辅助计数。

### 结论及原始证据

- **功能验证通过，性能为部分验证。** 已通过测试证明单次终态删除、缺失after-image/空值/异常回收正确、收据复用和六产品线资金/快照恢复不变；实现没有新增生产状态/阶段。真实集群本机观测361830.444 business/s、34575.939 Core/s；没有足够证据宣称稳定收益幅度或已消除所有瓶颈。
- 未覆盖：其他五产品线真实集群JFR、网关/真实WebSocket用户API、真实Archive重启、长稳/native增长斜率、生产独占CPU、重复轮次稳定性与完整时钟校准；相关结论不能给“全量性能验收通过”。下一步若继续优化，应围绕剩余结果合并/发布热点单独定位，不删幂等检查或有效引用清理。
- 原始目录：`surprising-aeron-core/surprising-aeron-benchmarks/target/aeron-async-stages/lane-merge-20260919-{main,jfr}/`；局部JMH `/tmp/lane-merge-20260919-jmh/{main,gc}.{log,json}`；本轮build/main/jfr/JMH启动日志及analysis/heap JSON在 `/tmp/lane-merge-20260919-*`。复用上一轮已归档的流式分析程序，本轮未改写该历史文件。
- node.jfr141s/120436483bytes/SHA256=`6891fe323a0da389f722af230537beb4b230ae2e194c8f5352ba48f0a3ed2d57`；client-74027.jfr138s/10733936bytes/`20f489fec2327515ebbf20fecf9df2e40812dc2ffc4f7ba59916e3e899c00311`；client-74030.jfr137s/126840114bytes/`d59e2b6547a98141f0c8a118e43186eb4048574b756c5356f6575bcbe10e50da`。三者DataLoss0，各summary/view、命令及NMT文件随目录归档。

### 本轮清理与提交范围

- 全部本轮节点/client/JMH fork/分析进程已退出；仅将本轮临时Cluster/Archive/JFR、局部JMH、日志和分析文件约6.4GiB移入 `/Users/atomex/.Trash/surprising-ex-lane-merge-20260919/`，可恢复；上方原始target/tmp路径仅作历史定位。未清空Trash、未改动上一轮分析脚本或其他用户文件。
- `git diff --check` 通过，只提交 LanePublication/TradingRuntimeState 的局部减法、相关回归/JMH恢复断言、README及本节记录；既有其他未提交改动（含AGENTS.md）保持原样。本轮用户允许本机验证不等于修改全局环境政策。

## 2026-09-19 Owner 结算合并内部分段定位（merge-detail-20260919）

### 采集前计划

- 当前 master `ef63cf8e`，对照 commit 不适用（仅验证当前 master）；保留既有未提交修改，本轮只增加 Owner-local、按 sequence 哈希 1/64 的 JFR 分段计时，不改变业务状态/顺序。现有命令事件以相同 sequence 关联。collection 包含 lane，publication 包含各发布表，禁止重复累加嵌套时间；异常 collection 标记 completed=false。Lane 异常不产生完成事件。
- 假设：Owner 合并时间主要消耗在发布表、终态索引或回收之一；若分段不集中，不宣称单点根因。拆分 users/orders/reservations/positions/removals、terminalIndex、changedIndex，以及 collection 的 trim/release。Lane 事件覆盖调用 commitTerminalToOwner 的撤单等路径，collection 只覆盖 collectMatcherSettlement，不将未覆盖路径算零。
- 本轮为探索诊断，没有预设吞吐增幅或容量验收线。要求错误/超时/未完成均为零、accepted=terminal（business/Core）、资金差额零且冻结/持仓/终态/恢复检查通过；环境异常、DataLoss 或窗口错位对应证据无效。不得靠 busy-spin CPU 宣称计算满载。
- 用户已明确允许本机。本机 macOS26.7 x86_64 / MacBookPro16,1 / 16逻辑CPU /16GiB，JDK HotSpot Corretto27+33-FR、Maven3.9.16；磁盘297GiB可用，既存swap1797.5MiB，运行中检查swap增量与空间。CodeGraph 工具不可用，按源码与Maven依赖确认影响范围。
- 业务/资源配置沿用当前 baseline.env：真实单成员Aeron（网络、Archive保留）、LINEAR_PERPETUAL MIXED、做市开启、1matcher/4Account Lane、全局/session in-flight256、128symbols、1000retail/1385total users、batch20、seed25620、全部BUSY_SPIN、Owner input64。节点512m–1536m、client128m–512m、G1、不绑核，不能外推生产独占CPU。
- 预热30s、测量60s、独立排空；先无profiler，再同配置JFR各一轮，记录波动而不声称稳定收益。诊断开关仅JFR轮开启；录制每进程独立、上限256m，使用现有 owner-commit-profile.jfc（20ms execution sample），NMT/GC/CPU随脚本采集。只用流式JFR分析，禁止展开全量FileWrite JSON。业务窗口客户端epoch对JFR仅取两端2s保护区，跨进程未完整校准，不相减计算延迟。
- 命令：先 `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=ClusterCommandPipelineTest -Dsurefire.failIfNoSpecifiedTests=false -Dcore.settlementLatencyDiagnostics=true test`，再 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`。压测：`ASYNC_RUN_ID=merge-detail-20260919-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；JFR轮runID改为merge-detail-20260919-jfr、ENABLE_JFR=true。本轮不改业务算法，先验证诊断覆盖与归因；不拿历史代码或局部JMH冒充当前Core容量。
- 缺口预先声明：真实六产品线全链路、网关/WebSocket、真实Archive重启、独占CPU、长稳和完整跨进程时钟校准不在本次定位覆盖内；最终仅给部分验证。
- 补充局部回归采集：沿用已覆盖合并路径及全账户恢复断言的 ContinuousOwnerBenchmark.placeCancelWithoutTimers，六产品线×batch1/20×DISTINCT/PAIRED，无profiler/-prof gc 各24fork；wi1×1s/i2×1s/f1/t1，512MiB/G1、1matcher/4Lane/window256、BUSY_SPIN。命令 `bash /tmp/merge-detail-20260919-jmh.sh`；本轮新增的是真实集群内部分段JFR，不新增重复JMH模型。短微基准只检查诊断关闭路径、分配和恢复，不证明稳态容量。

### 实测结果与归因

- 本轮构建前工作区 tracked diff SHA256=`cb671ef50476b4f1c6ccf5696d0a287383dc5f42995000dba29445ac33d7065b`（含既有修改及计划，README结果段随后追加）；新事件源文件SHA256=`b4ea5c2565c4997366a3401ce7c55ff8d9eb809997f72c57f1a946098659e01c`。默认关闭时不读取时钟、不分配逐命令事件；唯一新增类承担JFR观测边界，无业务副本/索引/线程。
- 诊断开启的 ClusterCommandPipelineTest 250项全通过；完整 benchmarks -am package 2:08成功，1458项、0失败/错误、3条件跳过，包含六产品线连续交易及快照恢复断言。新增断言验证事件完成标记、scope、合法序号、非负耗时与子段≤父段。未用测试替代真实集群性能。

| 当前代码轮次 | 稳定窗口s | terminal business/s | terminal Core/s | fills/s | 窗口背压占比 |
|---|---:|---:|---:|---:|---:|
| 无profiler主轮 | 60.021243 | 347966.718 | 33255.742 | 82829.341 | 83.874% |
| JFR归因轮 | 60.019794 | 342779.767 | 32761.509 | 81594.415 | 83.448% |

- 主轮窗口内20885395 business/1996051 Core/4971520 fills；排空5.791597ms另完成2686 business/254 Core/0fill，期末offered=terminal20888081 business/1996305 Core、unfinished=0。JFR窗口内20573571 business/1966339 Core/4897280 fills；排空6.138650ms另完成2685/253/0，期末20576256 business/1966592 Core均对齐、unfinished=0。两轮peak256，windowBlockedCount458228/607153；背压50.342102120s/50.085274965s，非持续无背压open-loop、延迟未做coordinated-omission校正，不能宣称无限到达率下容量。
- 两轮mixedVerify=PASS、fundsDiff0、population/hftPositions/reservations/loss均true，无错误/超时退出；主轮1942测量cycles/2724总cycles/hash4d36eab35275e5fe，JFR1913/2719/hasha660dba419ff9299。真实网关API、WebSocket和真实Archive重启未验证。主轮与JFR仅差−1.49%，这是采样开销与单轮波动混合，不是优化收益。

主轮入口到终态延迟（µs；requests/items计数包括独立排空，非稳定窗口吞吐分子；入口→accepted、accepted→terminal未分别输出，属缺口）：

| 业务 | requests/items | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| PLACE_ORDER | 497152/497152 | 5390 | 9846 | 10928 | 16236 | 25608 | 80347 |
| CANCEL_ORDER | 497152/497152 | 5459 | 8609 | 9469 | 15507 | 30916 | 81723 |
| APPLY_MARK_PRICE | 7697/7697 | 5324 | 9822 | 12558 | 25477 | 166330 | 168427 |
| PLACE_ORDER_BATCH | 745728/14914560 | 7843 | 12500 | 13811 | 20348 | 34373 | 168427 |
| CANCEL_ORDER_BATCH | 248576/4971520 | 11788 | 13705 | 15507 | 25821 | 38305 | 167247 |

批量平均/最大20items，请勿把Core消息与business items作同一口径。主轮最后尾样本最高168ms，主轮无JFR，不能事后拿另一轮GC替它归因。

#### 合并内部：可定位到具体操作，但没有证据证明一个调用是全部吞吐差距的根因

- 中部窗口epoch `[1789781952371,1789782008391]`，56.020s（两端各2s保护，不是完整跨进程时钟校准）。全部录制92716条OwnerSettlementMerge；中部60452条按下面实际n统计：PLACE_ORDER7121 collection/7121 lane，CANCEL_ORDER7115/7115，PLACE_ORDER_BATCH10656/14210，UNJOINED3557/3557。每组按sequence先汇总Lane，不把每Lane均值误作每命令均值。UNJOINED不得推定成批量撤单；类型关联存在缺口。

每命令分段均值/p99（µs）；publication包含五个发布子段，collection包含lane、trim、release。它们不可重复求和：

| 分段 | 普通下单 n7121 | 普通撤单 n7115 | 批量下单 n10656 | 未关联 n3557 |
|---|---:|---:|---:|---:|
| 收集合计 | 5.325/13.474 | 5.641/15.118 | 14.029/39.916 | 14.667/35.606 |
| 发布表合计 | 1.326/2.731 | 2.058/4.907 | 5.677/17.753 | 6.277/17.296 |
| 其中账户 | 0.083/0.183 | 0.365/1.028 | 0.377/1.595 | 0.391/1.042 |
| 其中订单 | 0.355/0.732 | 0.280/0.873 | 1.601/5.308 | 1.084/3.128 |
| 其中冻结 | 0.066/0.149 | 0.278/0.920 | 0.479/1.896 | 0.957/3.149 |
| 其中持仓 | 0.064/0.142 | 0.065/0.154 | 0.303/1.453 | 0.067/0.155 |
| 其中删除路由/集合清理 | 0.322/0.698 | 0.638/1.612 | 2.238/6.889 | 3.225/7.162 |
| 终态索引 acceptBatch | 0.064/0.147 | 0.390/1.115 | 2.156/7.472 | 3.213/9.006 |
| 变更索引 adopt | 0.178/0.326 | 0.168/0.470 | 0.261/0.760 | 0.194/0.512 |
| 保留窗口淘汰 | 0.108/0.165 | 0.180/0.464 | 0.556/2.310 | 0.786/2.449 |
| 结算缓冲回收 | 0.272/0.509 | 0.303/0.741 | 1.237/3.712 | 0.403/0.954 |

- 批量下单：发布表5.677µs，占采样collection14.029µs的40.5%；终态索引2.156µs占15.4%；回收1.237µs占8.8%；adopt0.261µs占1.9%。发布内删除路由2.238µs、订单1.601µs最重，两者合占发布约67.6%。因此优先继续删清零或adopt不能解释主体成本。**这些是带诊断的墙钟分段，不是无采样纯CPU成本；父段包含内部JFR事件提交，余差不能直接全算成未定位业务工作。** 亚微秒子段也包含读时钟成本。
- 方法证据：`LanePublication.publish → LanePublishedMap.remove → Long2ObjectHashMap.remove/compactChain` 为实际删除及后移探测链；`LanePublishedMap.applyPublished → OrderRuntime.equals` 是现存订单全字段比较；`TerminalStateRetention.acceptBatch → TerminalTombstoneStore.putIfAbsent → entitySlot` 是终态幂等探测。indexEntity/trim仍有成本，但本轮不能仅凭热点说哈希发生病态冲突，也未证明可安全删掉幂等查找。之前已消除的是重复删除，不等于实际删除变免费。
- Owner中部1936 execution samples，inclusive pollCommandCommit1236(63.8%)、completeMatching850(43.9%)、collectMatcherSettlement377(19.5%)、LanePublication217(11.2%)、CommitPublication159(8.2%)；这些有父子/其他调用覆盖，不能相加。完整入口/准入还有276样本、完成推进/ready检查也有成本。合并不是Owner的全部工作，不能把某一局部节点等同全系统唯一瓶颈。
- 排队放大证据：Lane完成→Owner观察 p50/p99 下单1.707/302.329µs、撤单349.421/2910.327µs、批量下单355.833/2235.203µs；同一JVM nanoTime有效。它包含FIFO前序等待，不能读作当前命令执行耗时。OwnerTurn中部18110样本，退休0/1/多条=10435/7078/597，64条135次，headWait17969、budgetExhausted652（可重叠，不代表时长）。存在连续退休能力，但大多数抽样轮次没形成长ready前缀。

外层独立抽样（与内层采样器不同，不逐均值相减）：

| 阶段/业务 | n | meanµs | p99µs |
|---|---:|---:|---:|
| ownerCommitAttemptTerminal / CANCEL_ORDER | 7112 | 6.164 | 20.423 |
| ownerCommitAttemptTerminal / CANCEL_ORDER_BATCH | 3556 | 30.529 | 73.969 |
| ownerCommitAttemptTerminal / PLACE_ORDER | 7175 | 8.877 | 24.952 |
| ownerCommitAttemptTerminal / PLACE_ORDER_BATCH | 10672 | 25.428 | 111.144 |
| ownerResponseAndRetirement / CANCEL_ORDER | 7112 | 0.416 | 0.757 |
| ownerResponseAndRetirement / CANCEL_ORDER_BATCH | 3556 | 0.968 | 2.576 |
| ownerResponseAndRetirement / PLACE_ORDER | 7175 | 0.484 | 0.954 |
| ownerResponseAndRetirement / PLACE_ORDER_BATCH | 10672 | 1.077 | 3.081 |

#### 资源与证据有效性

- JFR中部Owner单核98.23%、matcher98.19%、各Lane约98.18%，全部busy-spin，不能当作有用工作100%；Lane有效执行全测量窗口平均38.00%（主轮37.16%）。主轮matcher/completion/context高水位218/201/255，Lane[66,63,39,77]；JFR224/197/256，Lane[58,43,37,40]，无期末积压。发压fork主轮进程CPU抽查73.5%，不是完整排除发压端/网络瓶颈的证明。
- 中部Owner同步file/socket事件0，仅限保护窗口。GC102次，总pause569.996ms/56.020s=1.02%，p50/p95/p99/max5.397/6.828/7.250/9.959ms。能影响毫秒尾延迟，但不能解释全部吞吐；未做逐请求GC相关性。heapUsed171202448–478854720 bytes，committed512MiB，首末afterGC171629808→171624000 bytes，仅短时稳定，不能证明无泄漏。
- 中部ThreadAllocationStatistics区间差：Owner5114145352B、matcher7365938400B、Lane0..3分别3996523456/3997542960/3991400872/3996436720B、cluster-service1937802232B；线程首次/末次采样覆盖略短于保护窗口，不直接冒充精确bytes/op。整段录制allocation-by-site最大OrderRuntime.snapshot10.72%、preparedOrder8.23%、CoreMatchingResult.fromNativeWithEvidence8.01%；不是中部专属分配比例。TLAB101164/OutsideTLAB1574/AllocationSample101173均有事件，不等于精确对象数。NMT主轮committed721915KB(+3641KB)，JFR736622KB(−5530KB)，末次独立summary739139KB，采样时点不同。缺Direct/Mapped长期斜率、FD趋势和真实恢复长稳。
- 全录制136s含启动/预热：Compilation8844、Deoptimization672、SafepointBegin176，不归作60s窗口计数；相关view保留，未把全录制统计冒充中部停顿归因。主轮/本轮JFR前后swap1797.5→1765.5→1701.5MiB，Swapouts持续867081未增；Swapins425647→426543→437730，有背景换入（JFR约44MiB），本机非独占/已有VM与桌面进程，存在干扰，不能证明生产独占CPU容量。磁盘最低约290GiB可用，无空间问题。

#### 局部JMH及恢复检查

- 24主fork+24 GC fork均完成，accepted/terminal business/Core均相等，trial teardown全账户冻结/持仓/订单及快照恢复检查无失败。JMH主分数单位invocations/s，每次512Core、512×batchSize business；不是API请求吞吐。两次短measurement的误差/CI为NaN且有明显预热波动，**此微基准仅部分验证路径与分配，不作为稳态性能通过**。GC轮主分数单列，不代替无profiler分数。

| 产品 | 账户模式 | batch | 主inv/s | GC轮inv/s | GC B/inv | 换算B/business | 分配MiB/s | GC次数/ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| SPOT | DISTINCT | 1 | 99.797 | 79.063 | 2636842 | 5150.1 | 137.76 | 5/29 |
| LINEAR_PERPETUAL | DISTINCT | 1 | 98.465 | 76.071 | 2621189 | 5119.5 | 135.68 | 5/26 |
| INVERSE_PERPETUAL | DISTINCT | 1 | 84.471 | 79.835 | 2616569 | 5110.5 | 142.52 | 5/27 |
| LINEAR_DELIVERY | DISTINCT | 1 | 86.539 | 86.819 | 2573503 | 5026.4 | 152.09 | 5/28 |
| INVERSE_DELIVERY | DISTINCT | 1 | 89.750 | 80.352 | 2606064 | 5090.0 | 142.67 | 5/29 |
| OPTION | DISTINCT | 1 | 96.949 | 72.288 | 2630241 | 5137.2 | 124.31 | 5/28 |
| SPOT | DISTINCT | 20 | 54.595 | 20.860 | 25057525 | 2447.0 | 354.93 | 7/44 |
| LINEAR_PERPETUAL | DISTINCT | 20 | 44.577 | 27.034 | 24108555 | 2354.4 | 445.15 | 8/50 |
| INVERSE_PERPETUAL | DISTINCT | 20 | 44.950 | 21.748 | 24716570 | 2413.7 | 354.82 | 8/51 |
| LINEAR_DELIVERY | DISTINCT | 20 | 45.447 | 31.004 | 23932795 | 2337.2 | 515.12 | 9/49 |
| INVERSE_DELIVERY | DISTINCT | 20 | 45.984 | 32.171 | 23549088 | 2299.7 | 519.94 | 8/38 |
| OPTION | DISTINCT | 20 | 44.104 | 40.057 | 23307113 | 2276.1 | 630.53 | 9/48 |
| SPOT | PAIRED | 1 | 121.981 | 108.239 | 2387037 | 4662.2 | 164.70 | 5/21 |
| LINEAR_PERPETUAL | PAIRED | 1 | 102.481 | 92.371 | 2408951 | 4705.0 | 136.26 | 5/22 |
| INVERSE_PERPETUAL | PAIRED | 1 | 108.781 | 99.611 | 2420863 | 4728.2 | 153.95 | 5/22 |
| LINEAR_DELIVERY | PAIRED | 1 | 105.223 | 111.105 | 2392521 | 4672.9 | 173.77 | 5/22 |
| INVERSE_DELIVERY | PAIRED | 1 | 115.752 | 108.626 | 2404833 | 4696.9 | 164.74 | 5/23 |
| OPTION | PAIRED | 1 | 111.852 | 66.709 | 2576928 | 5033.1 | 110.92 | 5/22 |
| SPOT | PAIRED | 20 | 32.760 | 17.861 | 24772246 | 2419.2 | 287.58 | 7/39 |
| LINEAR_PERPETUAL | PAIRED | 20 | 32.751 | 29.482 | 23741103 | 2318.5 | 461.36 | 8/35 |
| INVERSE_PERPETUAL | PAIRED | 20 | 32.332 | 23.537 | 24368713 | 2379.8 | 389.70 | 8/45 |
| LINEAR_DELIVERY | PAIRED | 20 | 28.802 | 24.549 | 24385070 | 2381.4 | 402.20 | 8/49 |
| INVERSE_DELIVERY | PAIRED | 20 | 28.911 | 23.542 | 24383322 | 2381.2 | 384.64 | 8/43 |
| OPTION | PAIRED | 20 | 32.374 | 26.941 | 24034324 | 2347.1 | 430.69 | 8/44 |

### 定位结论与最小后续方案

- **部分验证，不是“性能问题已解决”。** 已定位到合并内的具体重成本：Owner上的发布表实际删除/订单比较与更新，加上终态保留索引；再与其他串行准入、事实发布、ready推进共同影响FIFO推进。已排除“主要只差多余清零”这一优先级判断；还不能证明某个单独操作决定3.3万Core/s，也不能把业务操作41.5万与Core消息3.3万直接比较。
- 优先方案：先验证发布表的删除探测/后移链长度与orders相等比较命中率，再做一个局部变化（例如在已有Lane变化事实能证明未变时保留原对象，减少无效发布；或在实测长探测链时调整现有表容量/负载率）。不先改Owner调度、加barrier或换整体并发模型；不能直接跳过删除、把Owner发布表交给Lane并发写，或删除资金/终态校验。
- 第二优先：明确新终态收据的唯一性/重复交付边界后，才评估终态索引能否减少探测；未证明前保留putIfAbsent。回收与changed-index只做低风险附带减法，不承诺靠它们大幅提升。
- 收益验收需下一轮单因素当前代码对照诊断：对应分段下降、无采样持续终态吞吐同步改善、资金/完成性/恢复不变。此次没有实施性能修复，不给收益百分比；也没有证据支持生产必须放弃busy-spin或只能达到本机这档容量。

### 原始证据

- 原始目录 `surprising-aeron-core/surprising-aeron-benchmarks/target/aeron-async-stages/merge-detail-20260919-{main,jfr}/`，包含命令、日志、metrics、NMT、录制summary/views。主轮node86843；JFRnode87659/runner87675/fork87686。node.jfr136s/119835965B/SHA256=0047fccddda9bb288d8befc06bd8f719c5cbe1d9f907f36d687572dd1049c28b；client-87675.jfr10349672B/bcb3589fc70240b7ac321c3eeccff65637732362143978e804ea7a556161c542；client-87686.jfr122100439B/ac6dd37dbffea0004141fce18f60ce1f9c17eb4d85ca35d46e914a357a8f18f8。各进程独立录制，DataLoss需以各自summary核对。
- `/tmp/merge-detail-20260919-{analysis,outer,heap}.json` 与 `/tmp/MergeDetailEvents.java`、`/tmp/merge-detail-20260919-analyze.py` 保存分段全部分位、样本数和top stacks；流式分析没有全量FileWrite JSON展开。JMH完整命令 `/tmp/merge-detail-20260919-jmh.sh`，rawData/CI与日志在 `/tmp/merge-detail-20260919-jmh/`。上一轮分析程序只读复用，未修改历史文件。

### 清理与交付核对

- 三份JFR分别136s/133s/132s，均DataLoss0；本轮node、client、JMH fork和分析进程已全部退出。
- 只将本轮两个Cluster/Archive/JFR目录、JMH输出、日志和分析脚本约6.2GiB移至 `/Users/atomex/.Trash/surprising-ex-merge-detail-20260919/`，可恢复；原target/tmp路径仅作历史定位，没有删除或改写其他轮次及用户产物。
- 提交范围仅本轮诊断事件、TradingRuntimeState/LanePublication采样、ClusterCommandPipelineTest断言、profile新增事件、README和本节记录。既有AGENTS.md、Owner/Matcher/Lane等未提交修改及历史文档追加段保留原样，不纳入本次提交。

## 2026-09-19 发布表操作与删除链验证（map-detail-20260919）

### 采集前计划

- 用户要求验证具体耗时与彻底消除工作的方案；当前master a4935954+既有工作区，旧版本对照不适用，不检出历史代码。本轮先诊断，不改变资金/终态/顺序，不先更换哈希表或取消校验。
- 假设A：删除存在长探测/后移链；用实际表的只读稀疏观察检验，搜索/扫描各最多128槽，超限计censored，不做全表扫描。假设B：订单发布主要耗在相等对象的比较或重复get+put；记录get/equals/put耗时和次数、相等/同引用命中、删除路由跳过数。若链短或比较占比低，否定该方向的“大幅收益”预期。
- 使用现有OwnerSettlementMerge事件，1/2048计时、另一不相交1/2048观察删除链；结构检查会预热缓存，禁止把其删除时间混入计时。仅diagnostic调用以反射只读访问Agrona keys/values，模拟compactChain槽位移动数量但不修改数组；无副本/新业务状态，默认关闭不执行该检查。先测试碰撞、环绕、删除缺失、对象身份保留及原有单次删除边界。
- 用户此前已明确本机，本轮沿用该授权；不修改AGENTS全局政策。HotSpot Corretto27+33-FR/Maven3.9.16/macOS26.7 x86_64/16逻辑CPU/16GiB，磁盘344GiB可用。CodeGraph工具不可用，按源码和依赖定位；Agrona删除算法核对本地sources.jar及运行时回归。
- 真实单节点Aeron网络/Archive保留，LINEAR_PERPETUAL MIXED、maker运行，1matcher/4Lane、global/session in-flight256、batch20、128symbols、1000retail/1385total、seed25620、BUSY_SPIN、Owner input64；节点512m–1536m/client128m–512m/G1，不绑核。预热30s/稳定60s/独立排空，无profiler和JFR各1轮。JFR沿用profile，分进程max256m，20ms执行采样、NMT/GC/CPU；中部epoch两端2s保护仍非完整跨进程校准。
- 正确性门槛：零错误/超时/未完成，accepted=terminal business/Core，资金差额0、冻结/持仓/终态/恢复通过；无业务容量SLO，结论只作探索诊断。DataLoss/显著swap或窗口异常使对应性能证据无效。长稳、生产独占CPU、全六产品线真实集群、网关/WebSocket/Archive重启不覆盖。
- 命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=LanePublishedMapTest,ClusterCommandPipelineTest -Dsurefire.failIfNoSpecifiedTests=false -Dcore.settlementLatencyDiagnostics=true test`；再 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`。压测 `ASYNC_RUN_ID=map-detail-20260919-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`，JFR轮改runID为map-detail-20260919-jfr与ENABLE_JFR=true。不与历史轮绝对吞吐作收益对照。
- 局部JMH沿用覆盖真实合并及资金/快照断言的ContinuousOwnerBenchmark：六产品线×batch1/20×DISTINCT/PAIRED、BUSY_SPIN、1matcher/4Lane/window256、fork1/thread1、wi1×1s/i2×1s，分别无profiler与-prof gc；只验证路径/分配，不作稳定容量证明。新单测覆盖本轮观测逻辑，不新建重复业务基准。
- 源码审查追加验证（不并行干扰压测）：`PlaceBatchAdmissionEvent.execute` 直接stage `lane.orders`引用，`LanePublication`本身不冻结。压测后用 `/tmp/PublicationAliasProbe.java` 调用同一stage/publish/applyPublished链，修改Lane原对象提交元数据，核对Owner引用是否随之变化及相等after-image是否保留别名；以调用者传入snapshot作为隔离对照。仅证明机制，不冒充真实并发资金错误或端到端隔离验收，不擅自实施新业务修复。

### 实测结果与定位

- 采集版本为a4935954+工作区，采集前tracked diff SHA256=`7e0f3bde1258391468968b64d756710bc33bb2ab7e2e38c769d77ef55cd5c350`；随后仅追加文档/临时分析脚本。diagnostic=true的目标回归261项无失败；新增128槽截断测试后完整package2:20成功，1461项、0失败/错误、3条件跳过（service933、benchmarks295）。LanePublishedMapTest12项覆盖操作计数、对象身份、碰撞/环绕/缺失/长链截断及原有单次删除、复用边界。

| 轮次 | 窗口s | terminal business/s | terminal Core/s | fills/s | 窗口背压占比 |
|---|---:|---:|---:|---:|---:|
| 无profiler | 60.002604 | 368861.109 | 35245.720 | 87804.189 | 83.512% |
| JFR | 60.031365 | 354356.560 | 33864.897 | 84350.572 | 83.495% |

- 主轮测量窗口22132627business/2114835Core/5268480fills；独立排空5.339305ms另完成2688/256/0，最终offered=terminal22135315business/2115091Core、unfinished0。JFR窗口21272508/2032956/5063680，排空5.890690ms另2680/248/0，最终21275188/2033204对齐、unfinished0。peak256，windowBlockedCount477153/581805；负载受256窗口背压，不宣称无限到达率容量，延迟未做coordinated-omission修正。
- 两轮mixedVerify均PASS/fundsDiff0/population/hftPositions/reservations/loss=true，测量/总cycles分别2058/3012、1978/2921，hash8f4f260c04c9d000/4df2907c4a829b5f。JFR吞吐较同轮无profiler低3.93%，采样开销与单轮波动混合，不是性能修复收益。后面发现的引用隔离风险没有被这些资金终态检查覆盖，不能宣称整体隔离正确性通过。

主轮各业务入口→终态延迟（µs，请求计数含排空；入口→accepted和accepted→terminal未分拆，仍有缺口）：

| 业务 | requests/items | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| PLACE_ORDER | 526848/526848 | 5074 | 9215 | 9961 | 14540 | 27099 | 46891 |
| CANCEL_ORDER | 526848/526848 | 5218 | 8171 | 8855 | 13582 | 25313 | 43057 |
| APPLY_MARK_PRICE | 7699/7699 | 4804 | 8626 | 10199 | 17006 | 27361 | 32309 |
| PLACE_ORDER_BATCH | 790272/15805440 | 7118 | 11976 | 13066 | 18219 | 32292 | 54329 |
| CANCEL_ORDER_BATCH | 263424/5268480 | 11665 | 13156 | 14163 | 25427 | 46530 | 58130 |

#### 1. 删除不是病态长链，不能凭remove热点就更换容器

- 中部窗口epoch [1789815898652,1789815954684]，56.032s，两端各2s保护，不是完整跨进程时钟校准。全录制99645个OwnerSettlementMerge。仅对lane scope分别汇总timing/shape，结构观察样本不参与操作耗时；计时含读时钟和分支，不是扣净后的纯CPU耗时。

| 关联业务 | 计时Lane事件n | 实际删除次数/未命中 | 平均ns/删除 | 结构样本删除数 | 平均搜索槽/后续扫描槽/移动元素 | 最大搜索/扫描 | 截断 |
|---|---:|---:|---:|---:|---:|---:|---:|
| CANCEL_ORDER | 238 | 476/0 | 137.5 | 470 | 1.549/0.460/0.004 | 9/5 | 0 |
| PLACE_ORDER_BATCH | 460 | 9080/0 | 101.3 | 9120 | 1.540/0.749/0.117 | 12/19 | 0 |
| UNJOINED | 121 | 4840/0 | 110.6 | 4120 | 1.543/0.906/0.172 | 11/16 | 0 |

- 总计结构观察13710次删除，未命中0、截断0。批量下单最大搜索12槽、后续扫描19槽，平均只移动0.117个元素；Owner表观测最大size7643/capacity16384（两个最大值不用于构造精确负载率）。否定“这轮吞吐主要被病态删除长链拖住”的假设；不能由稀疏样本证明所有输入都无长链。
- 批量下单计时样本删除阶段1742289ns，其中实际map.remove累计919723ns（52.8%）；其余含集合遍历、变更标记、集合清理及诊断开销，不能全归给HashMap。每批最多40次订单/冻结删除，正常的单次开销乘以大量实体仍会形成热点。无删除未命中证据，不以containsKey预检再删除，避免再加一次查表。

#### 2. 相等after-image很多，但必须先区分未变与可变别名

| 关联业务 | 计时Lane事件n | 订单遍历/路由跳过 | get/equals | equals命中/同引用 | put | get/equals/put平均ns |
|---|---:|---:|---:|---:|---:|---:|
| PLACE_ORDER | 223 | 223/0 | 223/223 | 223/0 | 0 | 89.0/147.2/未执行 |
| PLACE_ORDER_BATCH | 460 | 7034/4540 | 2494/2494 | 2473/0 | 21 | 83.0/86.6/89.9 |

- 批量下单2473/2494=99.16%的比较内容相等，但同引用0；普通下单223/223相等、同引用0。批量下单7034条订单遍历中4540条（64.54%）已有删除路由，直接跳过发布；真正put仅21次。它不是大量昂贵put；有大量“交接一份对象→查旧值→全字段比较→保留旧对象”的工作。21次put样本太少，不给其稳定尾延迟结论。
- `TradingRuntimeState.completeMatcherPendingReservations` 明确在新订单未成交时也调用publishOrder，承担“完成标记”；随后 `MatcherSettlementChanges.prepareLaneTerminal → orders.freezeValues(OrderRuntime::publicationValue)` 生成交接值，`LanePublishedMap.applyPublished` 再比较。可变Lane对象的publicationValue会snapshot，完成标记与实体变更复用同一缓冲，是可减少工作的一处设计耦合。
- **不能把99.16%直接当可删比例**：当前准入阶段可能发布Lane可变引用，旧Owner引用可能已随Lane变更；此时equals为true并不代表业务没变。应先校正引用隔离，再统计真正未变的发布量。
- 不能用revision相等代替equals：`OrderRuntime.applyCommitMetadataInPlace/withCommitMetadata` 改变时间和clusterPosition但不改变revision，直接跳过会丢提交元数据。

#### 3. 发布边界的机制风险已复现，但未证明真实并发资金异常

- 源码链：`PlaceBatchAdmissionEvent.execute` 将`lane.orders.get`放入admittedOrders并stage；`stagePlaceBatchAdmission → applyLanePublication → LanePublication.publish` 直接应用该引用。LanePublication不负责冻结。Lane订单由AccountLaneState.putOrder/laneValue保证可变；Owner注释所述的不可变发布边界不能仅由容器保证。
- 最小复现编译/执行：`javac -cp surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar -d /tmp/map-detail-20260919-probe /tmp/PublicationAliasProbe.java`；`java -cp /tmp/map-detail-20260919-probe:surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar com.surprising.aeron.service.state.PublicationAliasProbe`。
- 输出：`sameReference=true ownerPositionBefore=0 ownerPositionAfterLaneOnlyMutation=456`；`equalAfterImageRetainsAlias=true`；不可变snapshot收据对照`immutableReceiptControl=PASS`。即没有再次发布时，修改Lane原对象也会改变Owner引用；随后相等不可变after-image被拒绝替换，旧别名仍保留。
- 此探针是机制复现，不是Aeron真实并发资金失败复现。真实调度/依赖fence能否使问题外显、查询与回滚是否受影响还需集成验证；没有据此声称已经发生资金错误。但这是优化前必须处理/证明安全的状态所有权风险，不能用本轮资金终态检查掩盖，也不能直接把复制全部删掉。

#### 4. 更彻底的候选方案与边界

- **第一优先：一份业务版本对应一份不可变交接值。** 在准入交接边界冻结订单/冻结信息，Lane继续持有私有可变状态，Owner仅接收不可变版本；审计普通下单、批量下单、准入回滚、后续成交对旧引用的影响。不可变版本可复用，不能复用正在被Lane修改的对象。先补“Lane继续变化不影响已交接版本”的集成断言。
- **第二优先：分开完成事实和实体更新。** 继续使用现有command/pending序号推进完成、资金核对、响应和Core Fact；只有成交数量/状态/费用/提交元数据等实际变化才物化并发布新实体。不能简单删掉completeMatcherPendingReservations里的publishOrder：还要保留现有changed-index、响应、终态与恢复依赖。优先利用现有命令订单ID/准入收据，不加第二套Map、dirty set或通用提交框架。
- **更大范围候选：消除Owner临时实体插入→删除。** Matcher直接消费现有准入收据，准入状态留在既有Lane/pending权威来源，Owner在有序完成点发布最终版本；即时终态订单不必先进入Owner活动表再删除。这可以从源头减少哈希操作，但需审计Owner查询/风控/依赖、准入前镜像、资金核对、Core Fact与恢复读取，尚不能判定可直接删除当前准入发布。它不是把同一份额外工作搬到另一线程。
- 验证顺序：共享引用隔离→保持资金/快照/顺序的完成标记分离→统计不可变实体创建/发布/删除次数是否下降→同配置无profiler终态吞吐改善。六产品线覆盖GTC未成交、部分/全部成交、IOC/市价、撤单/拒单、相同revision但元数据变化、Owner阻塞而后续Lane已完成、异常回滚与恢复。每步独立验证，禁止同时改调度、容器和生命周期后用一个总分归因。
- 结论：定位进展是**排除病态删除链，发现发布版本/完成标记耦合及引用隔离风险**，不是“已彻底解决性能”。不建议先换HashMap、删幂等检查或改busy-spin；这轮没有实施业务修复，不能承诺达到某吞吐。相关方案待集成验证，整体为部分验证。

#### 资源、等待与覆盖限制

- JFR保护窗口内Owner1720个执行样本，pollCommandCommit1103、completeMatching810、collectMatcherSettlement356；父子样本不可相加，合并不是Owner全部工作。Owner约98.43%单核、matcher98.42%、Lane98.42%均含busy-spin；Lane有效执行平均37.02%，不能从98%直接宣称计算满载。JFR matcher/completion/context高水位218/208/255、Lane[63,45,41,45]；主轮217/202/255、Lane[45,38,42,46]，期末无未完成。
- Lane完成→Owner观察 p50/p99（µs）普通下单1.634/273.554、撤单335.175/2817.030、批量下单359.618/2063.649，来自同JVM nanoTime，包含FIFO等待；不是单次map操作耗时。OwnerTurn19699样本、headWait19533、budgetExhausted734，计数可重叠。
- 中部Owner file/socket事件0；GC108次、pause总579.171ms/56.032s=1.03%，p50/p95/p99/max5.177/6.447/6.772/7.039ms。主轮无JFR，不把JFR轮GC直接归因到主轮尾样本；未作逐请求GC相关性。
- ThreadAllocationStatistics保护窗口首末样本差：Owner5479637496B、matcher7743652960B、Lane0..3为4207872624/4209723856/4205969696/4209383272B、cluster-service2037526800B；采样覆盖略短于保护窗口，不当精确bytes/op。NMT主轮committed722959KB(+9755KB)、JFR737911KB(+1469KB)，短轮不能证明无泄漏。
- 构建后主轮前Swapins511411/Swapouts867081，JFR后观察Swapins511603/Swapouts867081，既有swap1413.5MiB未增长，磁盘最低约337GiB可用。真实压测期间没有并行JMH/Java探针；发压fork JFR期CPU抽查345.7%、节点946.4%，均为进程口径，未证明发压端、共享CPU、网络全部不限制容量。生产独占CPU/长稳/Direct-Mapped与FD增长、真实Archive重启、网关/WebSocket仍未覆盖。

- 补充heap：保护窗口216条GCHeapSummary，used172288080–480348176B，committed512MiB；首末afterGC174601392→175214608B，不能据此声称长期稳定。全录制AllocationSample109180/InNewTLAB109152/OutsideTLAB1659，allocation-by-site的OrderRuntime.snapshot10.62%、preparedOrder8.19%、CoreMatchingResult7.92%是含启动预热的采样压力，不是精确对象总数或中部专属比例。全录制Compilation8972/Deoptimization712/SafepointBegin189，保留views，不与测量窗口计数混用。

### 局部JMH：功能完成，性能分数不作稳态证据

- `/tmp/map-detail-20260919-jmh.sh` 执行24主fork+24 GC fork，进程退出0，全部accepted/terminal business/Core对齐，trial teardown资金/冻结/活动订单/持仓和恢复断言无失败。每次invocation=512Core、512×batchSize business；短测量2次，误差/CI为NaN，不能给稳定容量置信区间。
- 每个fork出现一次Chronicle ClassUtil模块访问ERROR级提示（主轮24、GC轮24），但未出现JMH业务断言/fork失败。本地chronicle-core2026.5 sources显示privateLookupIn(AccessibleObject)失败被捕获后返回null；本轮脚本未开放java.lang.reflect。此为明确环境/预热缺口，不标记微基准性能验收通过，也不把它删出证据；真实Aeron主轮/JFR轮与该微基准分别报告。下一次正式微基准应补相应模块访问参数并延长预热至越过初始化/JIT，再收稳定分数。

| 产品 | 账户模式 | batch | 主inv/s（非容量） | GC轮inv/s | GC B/inv | 换算B/business | 分配MiB/s | GC次数/ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| SPOT | DISTINCT | 1 | 108.572 | 79.764 | 2615442 | 5108.3 | 142.70 | 5/31 |
| LINEAR_PERPETUAL | DISTINCT | 1 | 114.705 | 116.608 | 2454479 | 4793.9 | 191.42 | 6/29 |
| INVERSE_PERPETUAL | DISTINCT | 1 | 114.855 | 96.780 | 2525447 | 4932.5 | 156.08 | 5/29 |
| LINEAR_DELIVERY | DISTINCT | 1 | 114.356 | 87.986 | 2552215 | 4984.8 | 152.78 | 5/27 |
| INVERSE_DELIVERY | DISTINCT | 1 | 104.655 | 104.716 | 2499651 | 4882.1 | 173.70 | 5/26 |
| OPTION | DISTINCT | 1 | 90.741 | 107.931 | 2517631 | 4917.2 | 179.18 | 5/31 |
| SPOT | DISTINCT | 20 | 36.876 | 39.156 | 23539822 | 2298.8 | 626.85 | 9/45 |
| LINEAR_PERPETUAL | DISTINCT | 20 | 45.377 | 47.742 | 23067314 | 2252.7 | 718.75 | 11/50 |
| INVERSE_PERPETUAL | DISTINCT | 20 | 40.850 | 41.429 | 23371487 | 2282.4 | 674.61 | 10/46 |
| LINEAR_DELIVERY | DISTINCT | 20 | 42.334 | 37.320 | 23466676 | 2291.7 | 577.18 | 9/45 |
| INVERSE_DELIVERY | DISTINCT | 20 | 44.185 | 44.061 | 23062021 | 2252.2 | 696.21 | 10/43 |
| OPTION | DISTINCT | 20 | 34.548 | 48.102 | 23031942 | 2249.2 | 745.30 | 11/49 |
| SPOT | PAIRED | 1 | 114.325 | 114.577 | 2381103 | 4650.6 | 185.77 | 5/22 |
| LINEAR_PERPETUAL | PAIRED | 1 | 114.334 | 119.279 | 2380797 | 4650.0 | 179.63 | 5/24 |
| INVERSE_PERPETUAL | PAIRED | 1 | 119.151 | 110.859 | 2395302 | 4678.3 | 162.30 | 5/24 |
| LINEAR_DELIVERY | PAIRED | 1 | 116.202 | 112.629 | 2421445 | 4729.4 | 183.37 | 5/25 |
| INVERSE_DELIVERY | PAIRED | 1 | 101.896 | 116.839 | 2380773 | 4649.9 | 183.86 | 5/25 |
| OPTION | PAIRED | 1 | 117.056 | 85.414 | 2482029 | 4847.7 | 137.22 | 5/23 |
| SPOT | PAIRED | 20 | 32.460 | 33.237 | 23428916 | 2288.0 | 526.43 | 9/37 |
| LINEAR_PERPETUAL | PAIRED | 20 | 28.041 | 32.370 | 23647189 | 2309.3 | 525.60 | 8/34 |
| INVERSE_PERPETUAL | PAIRED | 20 | 30.583 | 31.991 | 23651135 | 2309.7 | 494.75 | 8/33 |
| LINEAR_DELIVERY | PAIRED | 20 | 33.023 | 31.396 | 23744290 | 2318.8 | 504.96 | 9/49 |
| INVERSE_DELIVERY | PAIRED | 20 | 32.349 | 32.611 | 23863266 | 2330.4 | 529.20 | 8/34 |
| OPTION | PAIRED | 20 | 30.113 | 32.161 | 23718334 | 2316.2 | 517.36 | 8/36 |

### 证据归档与交付范围

- 原始目录 `surprising-aeron-core/surprising-aeron-benchmarks/target/aeron-async-stages/map-detail-20260919-{main,jfr}/`；主轮node46546，JFRnode47475/runner47486/fork47489，含完整命令、NMT、日志、summary/views、metrics。node146s/130785957B/SHA256=d411863f8898de1ae243e26cd273f3944da6f7a8bd5670ac0e11b609524f1631；runner143s/11236112B/c2a9fb9ae81ac0bf467daf6d1214e6ed416e7681e132d3b81be64a3c1124be6f；fork142s/128400866B/0eed4e773828b74eafc5e5ce76e080716732a22e2371a4a97c968d1d8b6c1e50，三者DataLoss0。
- `/tmp/map-detail-20260919-{analysis,outer,heap}.json` 保存全部分段分位/计数/归因；`MapDetailEvents.java`、`map-detail-20260919-analyze.py` 为有界流式分析；`PublicationAliasProbe.java`及probe.log记录机制复现；JMH脚本/rawData/log单独保存。未重新检出历史代码、未改写上一轮原始记录。
- 本轮代码仅增加现有事件上的互斥稀疏计数/计时与只读删除链观察、对应回归，不改变交易算法和状态所有权。Reflection字段仅因Agrona公开API不暴露探测链而用于诊断，默认关闭时不加载该探针；没有新增业务Map/快照缓存/队列/线程。已发现的准入引用隔离风险未在本轮修复，交付不称问题已解决。
- 清理完成：本轮全部node/client/JMH/分析与探针进程已退出；仅将本轮约6.6GiB的Cluster/Archive/JFR、日志、JMH、分析和探针文件移至 `/Users/atomex/.Trash/surprising-ex-map-detail-20260919/`，可恢复。上方原target/tmp路径仅为历史定位，未清空Trash或动其他轮次产物。
- 提交仅含本轮LanePublishedMap/LanePublication/OwnerSettlementMergeEvent、LanePublishedMapTest、README及本节追加记录；既有AGENTS、Owner/Matcher/Lane与压测配置、历史文档未提交内容保持原样。

## 2026-09-19 Owner 准入交接与完成复用试验（owner-handoff-20260919）

### 采集前计划与实现边界

- 用户批准尝试三项：不可变准入交接、完成事实与实体变化分离、取消临时订单发布。本轮只在当前 master（起点 aa67d351）工作；对照 commit 不适用，不检出旧版本，不把历史吞吐作为本轮对照。沿用用户“本机”授权，HotSpot Corretto27+33-FR/Maven3.9.16/macOS26.7 x86_64/16逻辑CPU/16GiB，测试前磁盘337GiB可用。
- 第一步：PlaceAdmissionEvent/PlaceBatchAdmissionEvent 在 Lane 内冻结订单和 reservation；MatcherSettlementEvent 保留不可变准入版本，不能把 Lane 原对象交给实时输出。定向262项测试261通过、1诊断条件跳过；六产品线覆盖 Lane 已完成成交、Owner 尚未提交期间旧版本不变，以及恢复一致性。
- 第二步采用局部减法：completeMatcherPendingReservations 保留完成计数和变更ID；已有实体变化不重复覆盖，无变化且完整字段相等时复用现有不可变准入收据，避免重复 snapshot。不是彻底拆掉发布索引；时间戳/clusterPosition 不由 revision 完整表示，不采用仅 revision 比较。没有新增 Map、索引、缓存、线程或 barrier。
- 第三步已实际试删普通/批量准入的 publishedOrders.stage，262项回归出现6 failure/18 error/1 skip：MatcherSettlementDispatcher 非直连批量/普通入口依赖 Owner taker 查询，报 taker order is missing；independentFills 的实时 TRADE 数 expected2/actual0。已撤销这两处试删并恢复原断言，保留失败日志。不能仅因直接 Matcher 路径有收据就删除其他路径的发布；后续需要同时迁移结算输入及实时成交取数契约，本轮不交付该失败变体。
- 构建及正确性：mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package；六产品线核心/客户端/bench模块测试，含资金、冻结、订单终态、快照恢复。JMH teardown 新增在途未成交订单的快照/角色切换检查，防止只查清空后状态而漏掉新订单完成事实。
- 本轮是探索诊断，无用户给定吞吐/SLA验收线，不事后设线。正确性门槛：零业务失败/错误/超时，accepted=terminal（business和Core），unfinished=0，资金差0，冻结/持仓/终态和恢复一致；缺少长稳/生产外围/充分JIT稳定证据时标记部分验证，不宣称生产容量或问题彻底解决。
- 真实集群：1成员、网络和Archive开启、1matcher/4Lane、全局和session窗口256、BUSY_SPIN；LINEAR_PERPETUAL/MIXED/batch20，沿用baseline种子25620/1385账户/128symbol和做市人口初始化。warmup30s/measure60s/排空单列；无profiler主轮一次，独立同配置JFR一次，非统计性版本比较。命令：ASYNC_RUN_ID=owner-handoff-20260919-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh；JFR轮run后缀jfr/ENABLE_JFR=true。仅本轮package通过后skip build。
- JFR沿用owner-commit-profile.jfc，各node/runner/fork独立最大256MiB，NMT baseline/final、系统swap/disk。流式分析，不展开全部FileWrite；按client epoch保护窗±2秒归因，跨进程绝对时钟未充分校准是缺口。busy-spin CPU不代表有效计算饱和；窗口背压不消除coordinated omission。
- JMH：ContinuousOwnerBenchmark.placeCancelWithoutTimers，六产品线×batch1/20，DISTINCT/BUSY_SPIN；1fork/1thread，warmup3×3s、measurement3×3s，512MiB/G1、1matcher/4Lane/window256。无profiler和-prof gc分开共24fork，单fork短轮只作路径/分配诊断；PAIRED保留功能测试覆盖但不采本轮JMH。补充java.lang.reflect add-opens修正上一轮Chronicle访问提示。脚本/tmp/owner-handoff-20260919-jmh.sh。全部运行串行以免CPU互扰，记录完整结果和环境异常；采集后仅清理本轮产物。

### 构建、主轮和环境结果

- 构建通过，1462 tests/0 failure/0 error/3 conditional skip，service934、bench295；耗时2:22。新增单测验证不可变版本不二次冻结、提交元数据变化不漏掉；强化六产品线真实并发测试，保留原身份、资金/实时成交及恢复断言。未启动wallet、HTTP网关/实际WebSocket/Kafka外围，不能据此宣布完整生产链路验收。
- 采集前tracked diff SHA256=14219859dcdba216b7bc99d289394d390efc4dde41fc027c5248d1f9bf1f3b73，包含既有用户改动及本轮代码、计划、README；结果追加后hash会变化。主轮node56897；JFR node57875/runner57901/fork57908。node512m–1536m/client128m–512m/G1；同机Terminal/WindowServer/输入法/虚拟机等干扰存在，未绑独占CPU，不外推生产独占CPU结果。
- 无profiler稳定窗60.029303s：21,326,259 business /2,038,067 Core /5,076,480 fills，即 **355264.146 business/s、33951.202 Core/s、84566.699 fills/s**。排空6.298507ms，2678business/246Core/0fill；最终offered=terminal21,328,937business/2,038,313Core，unfinished0。测量1983cycles/总2858，businessHash=1fe3b63a90fb22a4，资金差0，population/hftPositions/reservations/loss全部true。
- 峰值窗口256，背压441954次/49.721545926s（稳定窗约82.8%）；前五个10秒区间业务速率331813/365697/361752/351423/372888/s。计划为尽力持续异步到达，没有实现不受限open-loop，也没有对coordinated omission做补偿；报告已达负载而非Core容量上限。matcher/completion/context队列峰值218/203/255，Lane峰值36/53/34/53；Lane有效执行占比平均40.60%，与忙轮询CPU分开。
- 主轮延迟为客户端请求→响应直方图，单位µs（不能把它等同于accepted→terminal内部阶段）；分段内部以JFR另报，完整accepted时间语义及端到端SLA仍是缺口：

| 业务 | 请求样本 | p50 | p90 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| PLACE_ORDER | 507648 | 5255 | 9625 | 10485 | 14811 | 31965 | 154009 |
| CANCEL_ORDER | 507648 | 5443 | 8437 | 9076 | 13434 | 27344 | 66748 |
| APPLY_MARK_PRICE | 7721 | 5357 | 10248 | 12632 | 20086 | 31522 | 32784 |
| PLACE_ORDER_BATCH | 761472 | 7213 | 12484 | 13615 | 18284 | 37093 | 166592 |
| CANCEL_ORDER_BATCH | 253824 | 12287 | 13828 | 14647 | 26394 | 68222 | 167772 |

- batch平均/最大均20，计数表含排空响应；placebatch15,229,440items/cancelbatch5,076,480items。严格稳定窗总量采用steadyCapacity，不用上述含排空计数推算稳态分类requests/s。分类稳定边界计数尚未单独导出。
- 主轮swap-in512432/swap-out867081保持不变；JFR中swap-in到527250（+14818页），swap-out仍867081，swap占用1413.5→1381.5MiB；因此 **JFR绝对耗时/性能对比归因判无效**，只保留正确性和引用复用/结构计数证据，性能差不能全算采样开销。磁盘最低观测331GiB可用，没有磁盘压力。
- JFR原始稳定窗60.021580s：17,831,809business/1,705,217Core/4,244,480fills，即297089.962/28410.065/70715.899每秒；排空6.449013ms/2687business/255Core/0fill；最终17,834,496business/1,705,472Core全部完成，资金/持仓/冻结/人口检查通过，hash=b1965a34af01a5e7。这些吞吐仅用于异常轮留档，不作主性能分数。

### 机制验证及剩余成本（JFR环境限制见上）

- client测量epoch1789817982317–1789818042338，分析保护窗1789817984317–1789818040338（56.021s）；无DataLoss，三份录制完整。继续使用上一轮只读流式MapDetailEvents.java、map-detail-20260919-analyze.py与owner-tail-20260919-analyze.py，输出本轮tail-selected.ndjson和/tmp/owner-handoff-20260919-{map,outer,heap}.json，不改旧轮产物。
- 批量深采372个Lane事件：5692订单访问、3760路由删除跳过、1932get/equals，其中1840相等且**全部同一引用（95.24%）**，92put。证实不可变准入收据在完成阶段得到复用，不再为这些等值订单二次snapshot；不是“所有订单不用发布”，也不是运行耗时改善证明。
- 普通下单深采204次：204get/equals/相等、同一引用0。普通准入发布与direct settlement准入捕获仍各持不可变版本；不得把批量收益外推普通单。此次不新增跨线程共享缓存去消除它。
- 独立shape采样：batch384事件，平均search1.531/scan0.770/moves0.130；cancel189事件1.413/0.519/0.005；unjoined103事件1.554/0.918/0.192。仍无证据支持删除长链是主要问题。深计时含时钟/分支且受swap干扰，不用于成本排名。
- 保留异常窗观测供复核，不作有效性能证据：粗采每Lane batch发布均值4.861µs（订单1.289、删除2.110、终态索引1.650，索引在发布之外），普通发布1.630µs、cancel2.443µs、未关联7.545µs；Owner终态提交尝试+实时发布+回复退休均值/p99µs：普通11.246/32.004、cancel7.473/22.212、placebatch29.089/88.900、cancelbatch35.272/81.300。并非全Core命令耗时，且嵌套阶段不能相加。
- Owner execution samples1927，pollCommandCommit1198、completeMatching875、collectMatcherSettlement381、LanePublication.publish208，为包含调用关系的样本，不相加。观察仍集中在有序提交路径，不能仅凭此认定单一根因；Lane→Owner排队仍含FIFO前序命令等待，不能说是唤醒开销。Owner/Matcher/Lane单核CPU约96.4%，BUSY_SPIN不等于有效计算。
- 保护窗GC97次/554.761ms（约0.99%），pause p50/p95/p99/max=5.690/6.399/10.723/10.723ms；heap used175714768–483991760B，afterGC首尾176785648→178453552B，committed512MiB。NMT主轮committed720713KB(+6711)、JFR742502KB(-856)，是各自baseline差，不能跨轮作内存优化结论。
- ThreadAllocationStatistics保护窗增量：Owner4,509,139,024B、Matcher6,355,278,000B、Lane各约4.04GB、clustered-service1,672,375,840B。全录制分配站点OrderRuntime.snapshot15.13%、preparedOrder7.59%、CoreMatchingResult7.48%、ReservationRuntime.snapshot5.41%；含启动/预热，不当作精确窗口占比或objects/op。TLAB/非TLAB/sample/statistics均启用；没有长稳/FD/native buffer增长斜率及OldObject证据，不宣称无泄漏。
- 全node录制Compilation8930、Deoptimization698、SafepointBegin177，含启动不能当作稳态；保护窗Owner同步file/socket事件0，但不是所有路径无I/O的证明。发压端JFR时CPU也明显上升，未排除采样端资源影响，不能用这一轮定义Owner容量天花板。
- 证据：node141s/116052998B/SHA256=a6a623da760a592198a79e9ffd8becd024bbde76c66a593efb27506f6b1e86ff；runner137s/10861657B/bedf9cb63b4f4a3c99caaa8e7e85cc993c12eb19fa03f4157e83328abbbf4c96；fork136s/116527518B/263db0febad54e7ae76c3d8b80c5ba74bf82db9562fdafd40b952cbc9f722290。summary/views/NMT/命令/日志在target/aeron-async-stages/owner-handoff-20260919-{main,jfr}/内。

### 设计取舍与下一项最小验证

- 保留第一项正确性修复，第二项只完成“不覆盖已有实体变化、复用批量不可变版本”的局部实现；完成ID仍借用现有变更缓冲，不声称完全拆掉完成/发布耦合。跨线程不可变版本承担真实所有权边界，无新增类、接口、Map/Set、缓存、线程、排序或barrier。
- 不采用第三项直接删除发布的版本。具体依赖：CoreMatchingFlow.collectPlaceAdmissionIfReady 完成准入后为 pending.realtimeTakerOrder 查询 runtimeOrder；TradingCoreRuntime.applyMatchesOnAccountLanes 的非直连路径也读取该值；MatcherSettlementDispatcher.dispatchMatcherSettlement/dispatchMatcherSettlementBatch 通过 Owner order 表获取 taker 和instrument元数据。直接路径的 batch.preparedAdmittedOrders 已够用，不代表其他消费者已完成迁移。
- 后续最小改造应先明确这些入口接收现有不可变准入收据（含生命周期、异常回收、实时输出读取时点），让普通/批量、直接/非直连四条路径和成交推送均不再借临时发布表取输入，再尝试删除临时order发布。不得为兼容旧读取再加全局pendingOrder副本Map，亦不得删除资金冻结、完成标记、FIFO、实时EXECUTION/TRADE、Core Fact及snapshot恢复边界。
- 当前只能称部分验证：功能修复/批量复用证据通过；吞吐仍在本轮33951Core/s的已达负载，未证明性能瓶颈被消除；第三项失败撤回；JFR绝对时延证据受环境干扰无效，仍缺稳定环境重采、多重复、充分长稳和生产外围验收。

### JMH 与 GC 补充结果

- 24fork全部完成；每个fork的3次测量中accepted/terminal业务和Core原始计数均相等，teardown中的在途未成交订单快照、角色切换、撤单后全256用户资金/冻结/订单与恢复检查通过。无ERROR/exception/failure；补充java.lang.reflect模块开放后Chronicle ERROR提示未再出现，JMH Unsafe弃用WARNING仍在。
- 本地连续Owner基准每次调用512Core消息，业务数512×batch，不含真实网络/Archive，不得把下面约百万business/s称作真实Core容量。主分数和误差为JMH输出（99.9% CI）；单fork/3样本CI较宽，部分batch测量逐轮下降约5–7%，未证明充分稳态。GC结果来自独立-prof gc fork，不与主吞吐拼成同一轮资源效率；B/business由该GC轮gc.alloc.rate.norm除以512×batch，包含客户端/框架分配，不是对象数或纯Owner分配。

| 产品线 | batch | 主分数 cycles/s ± error | 换算 Core/s | 换算 business/s | GC轮 B/business | GC轮 MB/s | GC count/time(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SPOT | 1 | 187.162 ± 39.479 | 95827.1 | 95827.1 | 4359.83 | 299.22 | 14/27 |
| LINEAR_PERPETUAL | 1 | 182.323 ± 9.837 | 93349.3 | 93349.3 | 4432.64 | 304.49 | 14/24 |
| INVERSE_PERPETUAL | 1 | 182.414 ± 43.150 | 93396.0 | 93396.0 | 4413.12 | 300.77 | 14/29 |
| LINEAR_DELIVERY | 1 | 184.606 ± 33.491 | 94518.4 | 94518.4 | 4322.97 | 296.25 | 14/27 |
| INVERSE_DELIVERY | 1 | 182.770 ± 64.605 | 93578.1 | 93578.1 | 4350.43 | 292.24 | 14/25 |
| OPTION | 1 | 184.644 ± 13.804 | 94537.9 | 94537.9 | 4397.71 | 299.71 | 15/29 |
| SPOT | 20 | 107.251 ± 33.163 | 54912.7 | 1098254.5 | 2205.73 | 1636.08 | 68/233 |
| LINEAR_PERPETUAL | 20 | 99.905 ± 55.455 | 51151.4 | 1023027.3 | 2218.25 | 1483.68 | 63/221 |
| INVERSE_PERPETUAL | 20 | 99.637 ± 56.308 | 51014.4 | 1020287.7 | 2193.54 | 1452.00 | 62/226 |
| LINEAR_DELIVERY | 20 | 99.752 ± 68.205 | 51073.1 | 1021462.5 | 2217.81 | 1649.09 | 69/240 |
| INVERSE_DELIVERY | 20 | 99.086 ± 48.149 | 50731.8 | 1014635.8 | 2217.55 | 1593.51 | 66/232 |
| OPTION | 20 | 100.203 ± 40.395 | 51304.2 | 1026083.8 | 2215.98 | 1626.47 | 67/232 |

- JMH期间swap-out867081不增，swap-in527250→527314（64页），无持续交换，swap占用1381.5MiB；原始JSON包含每fork/JVM参数、主/GC轮rawData和置信区间，日志保留预热过程。源码/脚本/结果说明清楚微基准与真实集群不同口径。本轮未做有效的版本性能对照，不能声称上述数值说明修复提高了多少百分比。

### 清理与提交范围

- JMH main.json SHA256=b496daaf2155af9cffa116e3917263a492a9af5b970723b20f6f50903196f574，gc.json=af7ff744fa2ea0de717e5d763cb38a3bcda859dc9a1d36382de7e6989e9b7037。原始main/gc日志、JSON及汇总脚本/输出一并归档，失败变体测试日志也保留。
- 本轮全部node/client/JMH/分析进程退出，jps仅剩检查命令本身；约6.1GiB的本轮Cluster/Archive/JFR、日志、分析及JMH产物已移至 `/Users/atomex/.Trash/surprising-ex-owner-handoff-20260919/`，可恢复。上文原target/tmp路径只作历史定位；未删除其他轮次或清空Trash。
- 仅提交四个state实现文件（PlaceAdmissionEvent、PlaceBatchAdmissionEvent、MatcherSettlementEvent、TradingRuntimeState）、两个回归测试、ContinuousOwnerBenchmark收尾检查、README及本节追加记录。既有Owner/Matcher/Lane、AGENTS、配置、脚本及368行未提交历史文档改动保持原样，不纳入本次提交。

## 2026-09-19 重启后无 swap 复测（owner-reboot-20260919）

### 采集前计划

- 用户明确授权本轮本机例外，并要求删除AGENTS.md旧服务器相关约束；只删除工作区中“旧surprising-ex停用/等待新服务器/本机只静态检查”一条，不恢复或提交该文件其他既有改动。该限制原为未提交新增行，删除后相对HEAD无新增可提交hunk；在此记录执行结果。
- 当前master=470133ac，业务代码不改，不检出历史代码；对照commit不适用。本轮验证重启后swap为0时的持续吞吐、Owner提交/合并、Lane→Owner排队及GC证据；不把上一轮受swap干扰的JFR作为性能对照，不预设改善百分比。
- 本机macOS26.7/x86_64/16逻辑CPU/16GiB，启动20:00:37；开始时swap占用/in/out与压缩均0，磁盘332GiB可用；JDK Corretto27+33-FR HotSpot、Maven3.9.16已核对。无独占CPU绑核，不终止用户其他应用。
- 构建/恢复检查：mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=ClusterCommandPipelineTest,SettlementChangesReuseTest,ContinuousOwnerBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false package；共享交接六产品线资金/实时/快照回归，其他测试本轮不重跑（业务代码未改，上一轮完整1462项已记录）。不新增JMH模型、不重跑上一轮独立24fork微基准；真实集群仍通过ClusterOperationalBenchmark JMH入口执行。
- 正式配置不变：1真实Aeron成员，网络/Archive开启，LINEAR_PERPETUAL/MIXED/batch20，1matcher/4Lane，全局/session窗口256，Owner/Matcher/Lane BUSY_SPIN；seed25620，1385用户（1000retail）/128symbol，做市及风险人口初始化保留。node512m–1536m/client128m–512m/G1，warmup30s/measure60s/排空单列。主轮无profiler一次，随后独立同配置JFR一次；新run ID分别owner-reboot-20260919-main/jfr，二者串行且不与测试/分析并发。
- 复现：ASYNC_RUN_ID=owner-reboot-20260919-main ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh；JFR更换后缀jfr/ENABLE_JFR=true。仅本轮package通过后skip build。包装脚本/tmp/owner-reboot-20260919-run.sh每10秒记录UTC、swapusage/vm_stat/disk和top进程CPU。
- JFR沿用owner-commit-profile.jfc，各进程独立录制max256MiB，NMT baseline/final，20msexecution、1sCPU/分配/内存，稀疏OwnerTurn/OwnerSettlementMerge/SettlementLatency/CommandBoundaryLatency。有界流式解析，不展开全部FileWrite。以client epoch保护窗±2秒选取同窗数据；未完全校准跨JVM时钟，不跨进程相减延迟。
- 正确性要求：零业务错误/超时，accepted=terminal business/Core、unfinished=0、期末backlog0、fundsDiff0，冻结/持仓/订单终态及恢复检查通过。无吞吐/尾延迟业务SLA，作为诊断而非容量验收；持续swap/磁盘不足/DataLoss/错窗使相关证据无效。窗口限速与coordinated omission如实报告，无长期状态修改，本轮不是长稳/泄漏或生产外围验收。

### 执行结果与有效性

- 采集前tracked diff SHA256=2e34ae853d703195fc580c58080552e95375f6bc25214b25f990631c9fcaa853（含既有用户改动、本轮约束删除和计划）；业务源码不变。package成功45.932s；ClusterCommandPipelineTest250项（1诊断skip）、SettlementChangesReuseTest3项、ContinuousOwnerBenchmarkTest31项（1诊断skip），合计284/0 failure/0 error/2 skip，涵盖六产品线资金/成交推送/交接/快照恢复。未启动wallet或外围网关/WebSocket/Kafka服务。
- 主轮node6025/clientfork6054，JFR node6887/runner6898/fork6901。主轮14个、JFR17个10秒系统样本：swap total/used=0MB、Swapins/Swapouts始终0；累计计数也排除采样间隙发生swap，普通pagein/out不等于swap。磁盘最低观测326GiB可用；同机仍有Terminal、WindowServer、system_profiler等干扰，不宣称独占CPU容量。
- 主轮稳定窗60.008299s，terminal20,562,823business/1,965,319Core/4,894,720fills，即 **342666.320 business/s、32750.787 Core/s、81567.385 fills/s**。排空5.900907ms、2681business/249Core/0fill。最终offered=terminal20,565,504business/1,965,568Core，unfinished0；fundsDiff0，population/hftPositions/reservations/loss全部true，1912测量cycles/2852总cycles，hash588964b897091d7b。
- 窗口峰值256；windowBlocked475920次/49.235097508s（82.05%稳定窗）；matcher/completion/context队列峰值220/197/255，Lane峰值76/54/50/60。Lane有效执行平均42.54%（来源测量期operation executionNanos，非CPU利用率）。客户端space()在pending满256时reap+spin，单有序session仅检查响应队首；这也可能放大前序响应等待。尽力异步而非不受限open-loop，未补偿coordinated omission，不认定Core容量上限。
- 主轮客户端请求→响应直方图如下，单位µs；样本含排空，稳定窗吞吐严格使用steadyCapacity，未拿含排空数冒充稳定分类型速率。入口→accepted及accepted→terminal的完整业务分段仍有缺口，不能把这些值当成内部结算延迟。

| 业务 | 请求样本 | p50 | p90 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| PLACE_ORDER | 489472 | 5558 | 10362 | 11436 | 14303 | 27213 | 43581 |
| CANCEL_ORDER | 489472 | 5517 | 8273 | 9060 | 13131 | 21381 | 28147 |
| APPLY_MARK_PRICE | 7680 | 5394 | 10723 | 12902 | 19890 | 31326 | 32636 |
| PLACE_ORDER_BATCH | 734208 | 6963 | 13262 | 14606 | 18006 | 32882 | 49938 |
| CANCEL_ORDER_BATCH | 244736 | 13508 | 15261 | 16244 | 29425 | 39747 | 47480 |

- batch平均/最大20，placebatch14,684,160items/cancelbatch4,894,720items。JFR稳定窗60.016266s，18,552,195business/1,773,827Core/4,416,000fills，即309119.446/29555.771/73580.052每秒；排空6.708932ms、2685business/253Core/0fill；最终18,554,880business/1,774,080Core全部完成，资金等检查全部通过，hash=e2e0df9fcffd3f94。JFR与本轮主轮相差约9.79%，含采样及轮次波动，不是单独精确的profiler开销估计。

### 同窗归因：Owner 串行提交累积成本仍最明确，不能归咎于 swap

- 分析保护窗epoch1789820269943–1789820325960（56.017s，client测量首尾各剔2秒）；三份录制DataLoss均0。本轮时间归因没有上轮swap失效条件，但没有多重复/完全跨进程时钟校准及独占核，仍是诊断证据，不是唯一瓶颈或提升幅度证明。
- Owner1790个execution samples，pollCommandCommit1159（64.75%）、completeMatching866、collectMatcherSettlement374（20.89%）、LaneDelta.commitTerminalToOwner256、LanePublication.publish230。这些为包含调用关系的计数，不相加。Matcher1806样本中execute190、batch提交451；Lane0共1814样本中SettlementLaneWorker.execute436，其余大量停留在worker主循环。忙轮询单核CPU Owner/Matcher/Lane约98.2%，不能把它解释为所有阶段都算满。
- 样本匹配的“ownerCommitAttemptTerminal + ownerRealtimePublication + ownerResponseAndRetirement”，单位µs：

| 业务 | 样本 | 均值 | p50 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| PLACE_ORDER | 6368 | 11.069 | 9.931 | 31.258 | 133.881 |
| CANCEL_ORDER | 6416 | 7.584 | 6.969 | 23.455 | 204.520 |
| PLACE_ORDER_BATCH | 9600 | 29.883 | 24.706 | 114.246 | 6129.795 |
| CANCEL_ORDER_BATCH | 3208 | 35.204 | 33.793 | 77.729 | 183.434 |

- 上表是采样墙钟，包含探针开销及被调度/GC暂停时间，不是无采样纯CPU成本。批量下单Owner终态提交尝试均值28.459µs，其中fact publication5.612、terminal bookkeeping3.593已包含，不能重复相加；实时发布0.108、回复退休1.316在其外。批量撤单相应33.947/7.920/2.663/0.103/1.154µs。当前benchmark没有生产实时外部消费者，不外推真实WebSocket/Kafka发布成本。
- collectMatcherSettlement按collection样本均值：普通6.699µs、cancel6.936µs、placebatch16.326µs、未关联19.431µs；collection包含Lane事件提交与回收，不能视为纯算法成本。批量每sequence的Lane合计发布6.614µs（订单1.628、删除2.921为其中子项），终态索引2.201、变更索引0.373在发布之外；这是多个串行阶段累计，不是一项异常慢的Map删除。未关联事件不擅自标成cancelbatch。
- Lane完成→Owner观察 p50/p99µs：普通2.042/283.259，cancel365.677/2605.570，placebatch367.198/1707.907。含FIFO前序命令等待与观察时机；本轮Owner/Matcher/Lane保护窗park/monitor等待和Ownerfile/socket事件均0，不能将该排队直接归因于唤醒操作。
- 批量深采401个Lane事件，5911订单访问/4200删除路由跳过，1711get/equals，其中1600同一引用（93.51%）、111put。普通206次相等但同一引用0，批量复用机制仍有效、普通单仍有不同不可变版本。深采删除8400次，均值116.7ns/次包含计时时钟/分支。独立shape批量387事件，search1.540/scan0.687/moves0.101每次，未发现支持删除长链病态的证据；这些数字不用于推断全负载单指令CPU成本。
- 发压端保护窗worker2211样本、send1972；egress425样本，pollSession195。结合窗口背压，表明客户端大量受在途窗口/有序响应约束，但未做连接数/到达率独立诊断，不能宣称完全排除了客户端影响。Archive后台FileWrite923904事件/累计13.158s（非Owner），保留网络与Archive成本；不能把后台I/O墙钟直接加到Owner耗时。

### GC、内存、JVM 与证据边界

- 保护窗GC102次、pause总571.581ms（1.02%），p50/p95/p99/max=5.593/5.999/6.085/6.119ms。暂停可能影响尾延迟，但其时间占比不足以单独解释持续吞吐；没有逐条命令与暂停区间join，未声称每个长尾都由GC引起。
- heap used169562832–478609520B，afterGC首尾169864104→172654952B，committed512MiB。NMT主轮committed722258KB(+9624)，JFR742634KB(-2864)，各对自身baseline；短轮不证明无泄漏，缺FD/native池增长斜率及长期live-set证据。
- ThreadAllocationStatistics保护窗增量Owner4,725,493,848B、Matcher6,670,937,680B、Lane各约4.25GB、clustered-service1,755,400,960B，计数采样首尾不恰好覆盖完整56.017s。ObjectAllocationSample同窗估算权重30,841,273,320B，OrderRuntime占22.44%、ReservationRuntime9.08%、byte[]7.44%、CoreMatchingResult7.29%、long[]6.37%；权重非精确对象总数/objects-op，不与ThreadAllocationStatistics直接当相同分母。InNewTLAB/OutsideTLAB/Sample/ThreadStatistics均启用。
- 保护窗Compilation50次，累计1.282s/最长920.243ms（编译线程工作不是STW）；Deoptimization4次，SafepointBegin106次，ExecuteVMOperation108次累计574.195ms。仍有JIT活动，未证明完全稳态。全录制Compilation8846/Deoptimization663/SafepointBegin176含启动，不用于稳态结论。Owner无观测到Java异常/错误事件，其他未覆盖异常路径不填零。
- node139s/113346158B/SHA256=9265cf07110f990f6aa84233db989378fc9a208792632befdd10ea3bd7bdd7e9；runner136s/8345065B/0490fa2219b832df38891f23990fb8483234d1ed79a48e258209728040bacaad；fork135s/113666826B/6d7d8e0ed408bf2d1e89622beb2d16a27111bf2e4a4766f203b469050101c32f。summary/views/NMT/命令/日志位于target/aeron-async-stages/owner-reboot-20260919-{main,jfr}/，系统时间线在/tmp同runId文件。
- 复用MapDetailEvents.java、owner-tail/map-detail/merge-detail三个只读分析脚本；新增临时WindowEvidence.java对node/client同窗执行栈、分配权重、I/O/锁/JVM事件聚合，Xmx256m流式，不展开百万FileWrite原始事件。duration聚合按事件开始时间入窗，长事件可能跨窗，仅作事件记录，不当作窗口内时间占比。
- 结论 **部分验证**：本轮swap干扰确实不存在，业务完成性/资金/恢复验证通过；Owner有序提交及合并是最明确的热点，尾部有多个累积成本，单个删除长链、swap、唤醒均不是现有证据支持的主要解释。仍不宣称唯一因果瓶颈/生产容量/SLA或彻底解决。下一项实现应先迁移非直连结算及实时成交读取到既有不可变准入收据，完成四路径/实时推送/资金/恢复回归，再尝试撤掉临时发布；本轮只诊断，不擅自再次修改该业务契约。

### 清理与交付

- 全部本轮Java/集群/分析进程退出，jps仅检查命令本身；约6.2GiB的本轮Cluster/Archive/JFR、系统监测、构建日志及分析脚本/结果移至 `/Users/atomex/.Trash/surprising-ex-owner-reboot-20260919/`，可恢复。上方target/tmp路径仅作历史定位，未删除其他轮次或清空Trash。
- git diff --check通过，仅提交本节追加报告；AGENTS.md要求的限制行已从工作区删除（该行原本不在HEAD，无该行可提交差异），该文件既有统一压测标准移出等改动不代为提交。其他既有Owner/Matcher/Lane、脚本/配置及368行历史报告改动保持原样。

## 2026-09-19 Owner/Lane 局部减法（owner-simplify-20260919）

### 采集前计划

- 用户授权实现上一轮列出的减法并本机复测。当前master=40810e08；对照commit不适用，只测当前工作区，不检出/重跑旧版本，不以历史数字计算优化百分比。既有未提交Owner/Matcher/Lane与脚本配置不改动、不代为提交。
- 业务路径：Lane校验和冻结准入后，Owner发布与Matcher收据共享同一不可变订单；Matcher把引用转入既有结算槽，消费后清空ring槽，结算完成后按既有FIFO提交/回收。拒单、资金校验和实时输出失败处理不变。不删除临时Owner订单发布。终态记录/清理合并遍历；pending已有订单变更不再查表；删除持仓捕获并入发布遍历；Eclipse Map drain不重复清零。唯一新增存储是既有SPSC收据的固定容量引用列，不新建缓存/事实副本/每命令收据对象。
- JDK已核对Corretto27+33-FR HotSpot、Maven3.9.16；macOS26.7/x86_64/16逻辑CPU/16GiB，开始磁盘326GiB、swap0。无独占绑核，记录同机干扰，不终止用户其他应用。
- 正确性先行：`JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`，运行依赖链全量模块测试，覆盖六产品线资金、冻结/持仓、拒单、实时成交/持仓、终态和快照恢复；新增收据同引用/槽位回收及Map删除/复用断言。未涉及网关/Kafka/wallet，不启动外围服务。
- 正式诊断配置：1真实Aeron成员、网络/Archive保留，LINEAR_PERPETUAL/MIXED/batch20，1matcher/4Lane，全局/session窗口256，Owner/Matcher/Lane BUSY_SPIN，seed25620，1385用户/128symbol，保留做市和风险初始化。G1，node512m–1536m/client128m–512m。预热30s、稳定测量60s、排空单列；无profiler重复3轮，随后独立同配置JFR1轮。同配置主轮极差/均值超过10%则标记明显波动，不挑最大值当稳定能力。
- 命令：`ASYNC_RUN_ID=owner-simplify-20260919-main{1,2,3} ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`（三个run ID顺序展开执行，不传字面花括号）；JFR后缀jfr且ENABLE_JFR=true。采集脚本/tmp/owner-simplify-20260919-run.sh每10秒记录UTC/swap/vm_stat/磁盘/进程CPU；swap发生或磁盘低于20GiB停止本轮。
- JFR沿用owner-commit-profile.jfc，独立进程文件max256MiB，NMT baseline/final，20ms执行采样、1sCPU/分配；保留稀疏Owner/合并/交接/命令阶段事件。以client epoch测量窗两端各剔2s分析，不跨未校准JVM相减计算延迟，检查DataLoss。各线程分配与类采样权重分开，采样不是精确对象数。
- JMH：现有ContinuousOwnerBenchmark.placeCancelWithoutTimers，六产品线×batch1/20、DISTINCT/BUSY_SPIN、1matcher/4Lane/window256，1fork/1thread、warmup3×3s/measurement3×3s，主轮及独立-prof gc各12fork；只作实际交接路径/资金恢复与分配诊断，不冒充真实网络吞吐。更新OwnerPublicationBenchmark.drainChangesToMap覆盖本次清零修改，单独主/GC各1fork、3×1s预热/测量。脚本/tmp/owner-simplify-20260919-jmh.sh记录完整JVM参数。
- 诊断无业务吞吐/尾延迟SLA，不预设改善幅度；正确性要求零错误/超时、accepted=terminal business/Core、unfinished/backlog0、资金差0与状态恢复一致。swap/DataLoss/磁盘不足/错窗判相关性能证据无效，业务错误判失败。满256后的背压比例与coordinated omission缺口明确报告，不把已达负载称容量上限。不改变长期业务存储，固定收据引用做反复回收测试；本轮短测不宣称无泄漏或长稳通过。

### 实现与正确性结果

- 完成五项局部修改，未新增类/接口/处理阶段：`TradingRuntimeState.prepareLaneTerminal` 合并终态记录与清理遍历；`completeMatcherPendingReservations` 将Lane订单查表移入需要发布的分支；`LanePublication.publish` 在删除持仓前捕获旧值，删除Owner预扫描并保留失败处理；`RuntimeChangeBuffer.drainToEclipseMap` 同遍清引用后只重置索引；`PlaceAdmissionEvent`→`AdmissionReceiptRing`→`MatcherSettlementEvent` 复用既有不可变订单。收据订单ID/用户ID校验保留，拒单无订单引用，先清引用再release槽位；不引用会被Owner提前回收的准入事件。
- 全依赖链package成功（2分06秒）：1465项，0 failure/0 error/3既有skip，覆盖六产品线；新增收据64次交替接受/拒绝及槽位复用/满队列/错误ID测试、Map drain覆盖/删除/引用释放测试。六产品线跨线程测试确认：Owner收回准入事件后，Lane完成结算，settlement.admittedOrder仍与Owner准入版本同引用且原值未被修改，资金、实时成交、终态及快照恢复一致。
- 另补`LanePublishedMapTest.terminalPublicationCapturesDeletedPositionOnceBeforeReplacingOwnerView`，prepared与非prepared路径均断言删除持仓只推送一次、归零且保留realizedPnl；对应13项测试0失败。共有1466个测试条目（其中13项局部复跑），3既有skip。构建后的新增变更仅此测试，无运行时代码变化。CodeGraph工具本会话不可用，影响面按源码调用、模块依赖与事件边界检查。
- 采集前tracked diff SHA256=5fd273821661a105d0d6dc64ca36a0f2352f35a4d63e291fe3110a34d35b30cb（包括既有用户diff和本轮计划，未跟踪的新测试另随提交保存）；全程使用JDK27。没有改公共协议/topic/产品资金模型，没有启动wallet/外围网关、Kafka或真实WebSocket服务，不能将本轮Core实时帧测试等同外围端到端验收。

### 真实单成员集群结果

| 轮次 | 稳定窗s | 稳定business/Core/fills计数 | business/s | Core/s | fills/s | 排空ms；business/Core |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| main1 | 60.005367 | 20982192 / 2005296 / 4994560 | 349671.920 | 33418.611 | 83235.221 | 5.118845；2680 / 248 |
| main2 | 60.021045 | 19358616 / 1850648 / 4608000 | 322530.470 | 30833.318 | 76773.071 | 6.064436；2676 / 244 |
| main3 | 60.011919 | 19466121 / 1860873 / 4633600 | 324370.915 | 31008.390 | 77211.329 | 6.625354；2682 / 250 |
| JFR（独立归因） | 60.015470 | 18165134 / 1736974 / 4323840 | 302674.196 | 28942.105 | 72045.425 | 6.750075；2688 / 256 |

- 三个无profiler轮算术均值332191.102business/s、31753.440Core/s、79073.207fills/s；business极差/均值8.17%，未超预锁10%但仍有明显波动。main1的10秒区间394423→383088→328375→337567→336582→318091business/s，不能声称预热后已完全稳定。没有旧版本对照，不报告性能提升百分比；局部减法已生效，不代表吞吐瓶颈消失。
- 最终offered=terminal business/Core分别为main1 20984872/2005544、main2 19361292/1850892、main3 19468803/1861123、JFR18167822/1737230；四轮unfinished0、期末backlog0、fundsDiff0，population/hftPositions/reservations/loss全部true，排空fill均0。hash依次4d7528a8f4537db8、9be11f73a98aa3cd、3c25b59f45e442fb、f900caae98e65b34；持续时间负载完成量不同，不要求跨轮hash相等。初始化adminActionRetries=120不计作稳定交易错误；稳定交易检查没有拒绝/超时失败。
- 主轮窗口峰值均256；blocked次数456566/437502/434599，时长49.4960/49.4653/49.1223s（约82.49%/82.41%/81.85%）。matcher/completion/context峰值220/198/256、221/195/255、216/194/255；Lane峰值[68,68,63,70]、[51,47,43,62]、[55,43,55,59]。Lane有效执行墙钟均值41.80%/41.19%/42.92%，不是CPU利用率。
- 这是尽力异步、单session有序响应的受限负载，不是不受限open-loop；未校正coordinated omission。client worker同窗2429样本、send2169，egress466样本、pollSession209，仍未单独验证多连接/计划到达率，不能排除客户端有序队首等待影响，不能宣称Core容量上限。
- 主轮客户端请求→响应延迟（µs；包含排空的直方图，不冒充稳定窗内部分段）。各类请求/批次及item可由下面样本核对，batch平均/最大20；分类稳定速率未导出，不以含排空计数冒充。

| 轮次/业务 | 请求数 | p50 | p90 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| main1 PLACE | 499456 | 5468 | 10182 | 11304 | 14475 | 26427 | 39419 |
| main1 CANCEL | 499456 | 5439 | 8196 | 8904 | 11976 | 21037 | 31014 |
| main1 MARK | 7720 | 5574 | 10059 | 11894 | 16326 | 26509 | 32948 |
| main1 PLACE_BATCH | 749184 | 6885 | 12967 | 14327 | 16809 | 32309 | 45711 |
| main1 CANCEL_BATCH | 249728 | 13246 | 15048 | 15884 | 30523 | 38076 | 45023 |
| main2 PLACE | 460800 | 5877 | 10878 | 11780 | 15065 | 27344 | 42041 |
| main2 CANCEL | 460800 | 6340 | 8970 | 9748 | 13901 | 24100 | 29687 |
| main2 MARK | 7692 | 5791 | 10362 | 12148 | 19857 | 33521 | 39550 |
| main2 PLACE_BATCH | 691200 | 7454 | 14049 | 15237 | 19070 | 32882 | 57311 |
| main2 CANCEL_BATCH | 230400 | 14131 | 15540 | 16498 | 29851 | 40337 | 56033 |
| main3 PLACE | 463360 | 5943 | 11419 | 12451 | 15663 | 28246 | 51380 |
| main3 CANCEL | 463360 | 6344 | 8224 | 8986 | 12877 | 25264 | 31621 |
| main3 MARK | 7683 | 5918 | 11018 | 12754 | 28557 | 35717 | 50200 |
| main3 PLACE_BATCH | 695040 | 7008 | 14147 | 15376 | 19202 | 37191 | 91357 |
| main3 CANCEL_BATCH | 231680 | 14639 | 15974 | 17334 | 31653 | 42467 | 96206 |

### JFR 同窗证据与归因

- 保护窗epoch1789822494025–1789822550042，56.017s；三个JFR文件DataLoss均0。普通单deep timing样本183次get/equals，183次same-reference、无不同版本put；批量406个Lane样本，same-reference/equals=94.81%。结合六产品线同引用回归，证明普通单第二次准入快照已消除；不是把所有OrderRuntime分配都消除了，也不能从抽样命中率外推全部业务。
- Owner1995个execution samples：pollCommandCommit1244（62.36%）、completeMatching884、collectMatcherSettlement369、LaneDelta.commitTerminalToOwner252、LanePublication.publish219；调用嵌套不能相加。Owner/Matcher/Lane单核CPU均约98.3–98.4%，其中含busy-spin，不能当作全部有效计算。
- 匹配到的Owner终态提交尝试+实时发布+回复退休，普通6305样本均值10.839µs/p99 34.219µs；撤单6310样本7.758/22.121；批量下单9453样本32.678/214.079；批量撤单3156样本35.227/81.057。是含探针/调度/GC的墙钟，不是无采样CPU成本。批量fact publication均值4.868/7.408µs、terminal bookkeeping3.325/2.767µs已在终态尝试内部，不能重复相加。
- 批量collectMatcherSettlement均值17.145µs，包含Lane诊断事件提交和回收，不是纯Map合并。每sequence各Lane发布合计6.722µs，其中orders1.617、removals3.065；terminal index2.193和changed index0.363在发布之外。批量删除深采128.8ns/次含计时时钟开销，现有证据不支持把单个Map删除当唯一病态瓶颈。
- Lane完成→Owner观察p50/p99µs：普通2.047/277.501、撤单353.736/2262.186、批量343.108/1529.535，包含FIFO等待。保护窗Owner/Matcher/Lane未观测到park/monitor或Owner文件/网络事件；Archive后台924116次FileWrite、累计14.350s不能加到Owner耗时。没有生产外部实时消费者，不能外推实际WebSocket/Kafka发布成本。
- GC100次，总暂停551.783ms（保护窗0.985%），p50/p95/p99/max=5.467/5.897/6.037/9.294ms。heap used170401152–478234672B，GC后首尾172466088→172708504B，committed512MiB。GC可能贡献尾延迟，但没有逐命令暂停区间join，不能把所有长尾归因GC。
- ThreadAllocationStatistics保护窗增量：Owner4659885880B、Matcher6546394072B、Lane0–3依次4162251856/4164793672/4161060552/4161501816B、cluster1722611128B；采样首尾不精确覆盖整窗。ObjectAllocationSample权重30336147512B，OrderRuntime22.43%、ReservationRuntime8.76%、byte[]7.59%、CoreMatchingResult7.51%、long[]6.58%、nativeMatcherResult5.59%、ResolvedPlaceOrder4.63%；是采样估计，不是精确对象数/可删除分配比例。四类TLAB/非TLAB/采样/线程分配事件均启用，完整类/站点/线程view保存。
- 同窗Compilation44次/406.128ms/最长150.239ms（编译线程工作非STW）、Deoptimization2、SafepointBegin104、VMOperation106次/554.492ms，仍有编译，不能宣称完全越过JIT。NMT committed主轮721697KB(+2586)/722379KB(+11426)/719767KB(+6170)，JFR743317KB(+569)，均相对各自baseline。
- 四轮58个10秒系统样本，swap占用始终0，累计Swapins/Swapouts也核对；无CPU独占/绑核，桌面进程仍存在。node PID16364/17284/18229/19122；JFR runner19145/fork19149。node138s/110738536B/SHA256=2d61b9ffdc4ce891e0ce357d9492a1448685cf28c1e6f90dd9848125ecd3a926；runner135s/8382162B/00cee8500162577de2b177137b1c3962db42cf6970148a05179add9aacdea5fc；fork134s/110995292B/b2760cca1b6901f222a1406e35e32bead2d4d1f298e223f558c8c74ccf8323ef。
- 复用有界MapDetailEvents与WindowEvidence流式解析及owner-tail/map-detail/merge-detail脚本；聚合按事件开始时间选窗，长事件可能跨窗。原始summary/view/命令/NMT与client日志在`target/aeron-async-stages/owner-simplify-20260919-{main1,main2,main3,jfr}`；同窗JSON/TSV/heap摘要在/tmp同run ID，清理后路径只作历史定位。
- 当前结论：**局部减法及正确性已验证，整体性能仍为部分验证**。剩余最明确热点仍是Owner有序提交的多个串行成本；未证明唯一因果瓶颈、显著吞吐提升或生产容量。完整入口→accepted→terminal分类延迟、独立客户端容量、长期FD/native池/old/live-set斜率等仍缺，不宣称长稳或无泄漏。下一步若继续优化，应针对批量提交发布/终态索引/fact的具体成本做单因素验证，不能继续把希望押在普通单重复快照或唤醒上。

### 六产品线 JMH 与分配结果

- ContinuousOwner主轮/独立GC轮各12fork全部完成，每fork三次测量的accepted/terminal business/Core原始计数逐项相等，teardown资金/冻结/订单终态/在途快照恢复与角色切换检查通过。没有ERROR/Exception/failure；新增drain主轮/GC轮也完成。主/GC的12组顺序均为batch1六产品线，再batch20六产品线；每个fork独立，日志、JSON保存原始测量和完整参数。
- 每次基准调用512Core消息，业务数512×batch；不含真实网络/Archive，以下数值不能当实际集群容量。主分数为cycles/s，error为JMH 99.9% CI误差；只有1fork/3个样本，OPTION普通单CI甚至宽于均值，不能宣称充分稳定。GC轮B/business=gc.alloc.rate.norm÷(512×batch)，含负载端/框架分配；不是纯Owner分配或精确对象数，不与主轮吞吐拼成同一轮资源效率。

| 产品线 | batch | 主分数 cycles/s ± error | 换算 Core/s | 换算 business/s | GC轮 B/business | GC轮 MB/s（JMH原单位） | GC count/time(ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SPOT | 1 | 196.388 ± 8.043 | 100550.4 | 100550.4 | 4221.71 | 345.76 | 14/26 |
| LINEAR_PERPETUAL | 1 | 195.529 ± 34.271 | 100110.9 | 100110.9 | 4253.57 | 316.62 | 13/27 |
| INVERSE_PERPETUAL | 1 | 190.684 ± 39.030 | 97630.3 | 97630.3 | 4334.70 | 306.65 | 13/26 |
| LINEAR_DELIVERY | 1 | 187.176 ± 61.001 | 95834.1 | 95834.1 | 4288.91 | 315.80 | 13/25 |
| INVERSE_DELIVERY | 1 | 174.730 ± 58.619 | 89461.9 | 89461.9 | 4314.24 | 318.98 | 13/24 |
| OPTION | 1 | 169.212 ± 214.476 | 86636.6 | 86636.6 | 4297.24 | 317.50 | 13/25 |
| SPOT | 20 | 90.666 ± 49.815 | 46421.2 | 928424.9 | 2221.71 | 1784.41 | 64/226 |
| LINEAR_PERPETUAL | 20 | 91.791 ± 6.840 | 46996.9 | 939937.7 | 2230.76 | 1739.18 | 63/224 |
| INVERSE_PERPETUAL | 20 | 94.176 ± 21.179 | 48218.0 | 964360.5 | 2223.32 | 1666.12 | 60/222 |
| LINEAR_DELIVERY | 20 | 91.642 ± 12.406 | 46920.6 | 938412.9 | 2216.58 | 1808.14 | 65/230 |
| INVERSE_DELIVERY | 20 | 94.065 ± 20.151 | 48161.2 | 963225.0 | 2219.03 | 1748.45 | 62/223 |
| OPTION | 20 | 90.809 ± 13.432 | 46494.3 | 929885.7 | 2217.91 | 1739.99 | 62/225 |

- 新增`OwnerPublicationBenchmark.drainChangesToMap`每调用20项写入（含一项覆盖成删除），主分数6336993±1202425次/s；独立GC轮6320410±1336468次/s、0.001001B/调用、0.006031MB/s、GC0。表明预热后的固定缓冲drain接近零分配，不代表完整交易链路零分配，也未对旧实现做性能对照。
- JMH期间50个10秒系统样本，swap占用及累计in/out始终0；全轮磁盘最低312.96GiB。主JSON SHA256=d34cfa0acfa08f7323aa9b06ffccae1e4a20c47d85ebac3b401ee986b0bc041f，GC JSON=50b4bdbbca06819c58cf45671feb602b5b93c304beb45693d7a89fbd0e93691c。全部Java/集群/JMH/分析进程已退出，jps只剩检查进程。
- JFR保护窗非TLAB事件636个，最大观测分配65552B的long[]；仅代表已录制的非TLAB事件，不能声称全堆最大对象。采样分配对象数/op仍不可精确计算，直接/映射/native池细分与长期斜率缺口保留。
- 按ThreadAllocationStatistics实际首尾54.586670s计算，Owner85366736B/s、Matcher119926606B/s、cluster31557359B/s、Lane0–3约76250334/76296899/76228510/76236594B/s；是各线程累计字节差/采样时间差，不使用56.017s保护窗替代真实采样跨度。未将近似同窗业务计数强行混作精确B/business；业务归一化分配使用上表独立GC JMH结果。

### 清理与交付

- 已停止本轮全部进程，将约12GiB的四轮Cluster/Archive/JFR、JMH结果、系统监测、测试日志及分析结果移至`/Users/atomex/.Trash/surprising-ex-owner-simplify-20260919/`，可恢复；未清空Trash或删除其他轮次。原target/tmp路径仅作历史定位，完整命令/summary/views/校验和与本节保留复核依据。
- 仅提交本轮六个state实现文件、四个回归测试、OwnerPublicationBenchmark、README及本节追加记录。既有AGENTS、Owner/Matcher/Lane及LaneCommitEvent、压测脚本/配置、OwnerIdleStrategyTest和368行历史报告diff保持原样，不纳入本次提交。未扩大为临时Owner发布删除或架构重写；`git diff --check`通过。
## 2026-09-19 admission/Owner 减法最终验证（owner-final-cleanup-20260919）

### 采集前锁定计划

- 仅验证当前 `master=4acc76e7` 加工作区既有 Owner/Lane 调度改动；不检出旧版本，对照 commit 不适用。新增实现已依次删除单线程 exchange-core 的阻塞池、Owner 迭代器/callback、空 primitive 索引反复分配、终态重复遍历、admission 临时 User/Order/Reservation publication，以及普通/批量 `CoreMatchingOrder` 重复 DTO。`MatcherResult/CoreMatchingResult` 保留为 matcher 内部事件池回收前的不可变跨线程所有权边界，不在无新证据时引入 window 槽归还协议。
- 问题：确认上述减法后真实 Aeron 持续终态吞吐、请求尾延迟与分配是否改善，并重新定位首要限制。探索门槛沿用当前已达口径：三轮无 profiler 均须零业务错误/超时、accepted=terminal、unfinished=0、资金差0、快照恢复一致；持续 Core 吞吐不低于 30,000/s，业务吞吐不低于 300,000/s；PLACE/CANCEL/批量请求 p99 不高于 30ms。任一正确性失败判失败；swap 增长、JFR DataLoss/损坏、磁盘不足、窗口错位使对应性能证据无效。
- 环境：本机 macOS x86_64、16 逻辑 CPU/16GiB；HotSpot Corretto JDK 27、Maven 3.9.16、G1。采集前记录磁盘、swap、进程、commit 与 tracked/untracked diff 摘要；不终止用户应用，不声称 CPU 物理独占或三节点生产容量。
- 真实集群固定：单成员，网络与 Archive 保留；LINEAR_PERPETUAL，MIXED，batch=20，128 symbols，既有固定种子；4 Account Lane、1 matcher；全局/session in-flight=256；settlement/matcher-pipeline/Owner 均 BUSY_SPIN。持续异步发压，30s 预热、60s 稳态、排空单列。
- 主轮三次且无 profiler：run ID `owner-final-cleanup-20260919-main{1,2,3}`；独立 JFR 一次：`owner-final-cleanup-20260919-jfr`，使用 `owner-commit-profile.jfc`，node/runner/fork 各自录制且每份最大 256MiB，另采 NMT、线程、GC/safepoint/allocation views。JFR 分数不与主轮混算。
- 真实集群完整命令统一为：`ASYNC_RUN_ID=<run> ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=<false|true> ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。先用 JDK 27 执行 benchmarks reactor package，之后才允许 skip build。
- 分配补充：`ContinuousOwnerBenchmark.placeCancelWithoutTimers` 仅跑 LINEAR_PERPETUAL、batch20、DISTINCT/BUSY_SPIN、1 fork/1 thread、3×3s warmup、3×3s measurement、512MiB/G1；无 profiler 与独立 `-prof gc` 各一轮。每 invocation 的 Core/business 换算、B/business、GC 次数/时间单列；它不含真实网络/Archive，不能代替集群吞吐。
- 有效性：主吞吐取三轮稳定窗终态增量/窗口秒数并报告均值、范围和波动；JFR 只在无 DataLoss、无 swap 增长且保护窗对齐时用于热点/分配/GC归因。窗口达到256后的背压比例与 coordinated omission 缺口必须披露。完成后停止所有本轮进程，将本轮 artifact 移入 Trash（可恢复），不删除其他轮次；结果、校验和和清理状态追加在本节。

### 构建与版本

- 正确 fork 为 `/Users/atomex/Desktop/surprising/exchange-core-lilaizhencn`，`master=ad920d8815cd2cd0bfff2d7a8885d0e6a9d26e7c` 且工作区干净；主项目 `surprising-parent/pom.xml` 锁定同一完整 SHA，service 构建期 provenance 校验通过。主项目为 `master=4acc76e7` 加采集前已存在的 Owner/Lane 调度诊断改动。
- HotSpot Corretto JDK 27、Maven 3.9.16；benchmarks reactor `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package` 成功，耗时 2 分 15 秒。核心 service 939 项测试 0 failure/0 error/1 既有 skip；整个 reactor 成功。采集前后 swap 均为 0，磁盘最低约 301 GiB 可用。

### 真实单成员集群结果

| 轮次 | 稳态秒 | business/s | Core/s | fills/s | 最大业务类型 p99 | window 阻塞占比 | Lane 有效执行均值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| main1 | 60.010712 | 281376.965 | 26913.595 | 66974.709 | 31.244 ms | 83.51% | 42.87% |
| main2 | 60.012988 | 334219.236 | 31946.535 | 79556.112 | 31.047 ms | 81.83% | 46.20% |
| main3 | 60.009381 | 344630.803 | 32937.667 | 82035.174 | 28.213 ms | 82.47% | 44.79% |
| JFR（仅归因） | 60.020582 | 329519.250 | 31498.812 | 78437.094 | 30.015 ms | 82.53% | 44.22% |

- 三轮无 profiler 算术均值为 320075.668 business/s、30599.266 Core/s；范围分别 281376.965–344630.803、26913.595–32937.667，极差/均值 19.76%/19.69%，CV 8.65%/8.62%。吞吐均值勉强超过探索门槛，但 main1 未达到 300k/30k，轮间波动也超过 10%，因此稳定性验收失败，不宣称容量达到或问题已解决。
- 三轮最大 p99 分别 31.244/31.047/28.213 ms。main1/main2 的 `CANCEL_ORDER_BATCH` p99 超过 30 ms，尾延迟门槛失败；main3 通过。普通 PLACE p99 16.482/14.499/14.229 ms，普通 CANCEL 15.720/13.238/13.205 ms，PLACE_BATCH 22.315/18.644/17.711 ms。
- 四轮均 `mixedCapacity=PASS`、`mixedVerify=PASS`，最终 offered=terminal business/Core、unfinished=0、排空后 backlog=0、fundsDiff=0，population/hftPositions/reservations/loss 均 true。初始化 admin retry 为 97/115/118/114，不计作稳态交易失败；稳定交易没有错误或超时。
- matcher/completion/context high-water：main1 221/209/255，main2 222/202/255，main3 223/207/255；Lane high-water 仅 `[82,72,78,73]`、`[47,40,51,54]`、`[61,56,64,62]`。客户端窗口长期满 256，而 Lane 业务执行只占约 43%–46%，故瓶颈不是四条 Lane 算力打满。
- 本轮是单 session、尽力异步、有序响应负载，仍存在 coordinated omission，且本机没有 CPU 绑核/物理独占；不能把最好一轮称为生产容量。历史同口径均值 332191 business/s/31753 Core/s 与本轮均值相差约 -3.6%，但本轮自身极差接近 20%，没有统计依据判定为代码回退或提升。

### JFR、分配与根因

- JFR node 135 秒、两个 client 132/131 秒，三份 `jdk.DataLoss=0`；保护窗为稳态首尾各剔除 2 秒。Owner、matcher、四条 Lane 的单核 CPU 均约 98.4%，其中含 busy-spin，不能把该数字当有效业务计算比例。保护窗 101 次 GC，总暂停 554.586 ms、最大 10.500 ms，约占保护窗 0.99%；GC 会贡献长尾，但不是 3 万 Core/s 的主因。
- 稀疏 `OwnerTurn` 样本 20060 个（每 64 个非空 turn 采一）：平均每 turn 只退休 1.398 条，p50 为 0，19910 个样本标记 head wait，约 99.25%；窗口起始平均 73.3。说明满载下绝大多数 Owner cadence 没有连续终态可退休，严格 FIFO 队首完成可见性是首要限制。
- Lane 真正执行很短：PLACE/CANCEL/PLACE_BATCH 的 Lane execution p99 分别 16.377/23.328/129.593 µs；但 Lane 完成到 Owner 观察 p99 分别 285.060/2017.236/1280.539 µs。差值是完成通知、Owner 调度及前序 FIFO 等待，不是 owner 单次业务处理本身。
- Owner 终态提交尝试均值/p99 17.838/59.013 µs；其中 fact publication 均值 5.021 µs、terminal bookkeeping 3.127 µs、实时发布 0.102 µs、响应及槽退休 0.867 µs。`OwnerSettlementMerge` 58164 个样本均值/p99 8.764/28.011 µs，publication 2.273 µs、removals 0.827 µs、terminal index 0.613 µs、changed index 0.111 µs。重复删除/查表/清零已不是唯一或主要瓶颈。
- 命令边界进一步显示：admission execution 均值/p99 5.246/14.296 µs，transport→Owner 632/3369 µs，ingress→admission 2050/7145 µs；Owner 内部本地处理远小于排队/交接等待。源码对应 `TradingCoreOwner.pollCommandCommit()` 每次调用 `commitReadyMatching(1)`，用逐命令 realtime capture/响应边界换取确定性；`progressCommandsInScope()` 遇到一次队首未完成后，本 turn 不再重复收集。这是当前 3 万 Core/s cadence 的直接机制解释。
- 稳态线程分配增量约 matcher 6.57 GiB、Owner 4.86 GiB、每条 Lane 4.15 GiB、cluster service 1.86 GiB。JFR 采样分配头部为 `OrderRuntime` 26.57%（其中 `snapshot()` 18.63%）、`CoreMatchingResult` 8.66%、`ReservationRuntime` 8.35%、byte[] 8.05%、long[] 6.36%、native `MatcherResult` 5.65%、`ResolvedPlaceOrder` 5.00%。这些是采样权重，不是精确对象数。
- 聚焦 JMH 普通轮 106.241±7.361 cycles/s；每 cycle 512 Core/10240 business，换算约 54395 Core/s、1087908 business/s。独立 GC 轮 103.159 cycles/s，`gc.alloc.rate.norm=23240013 B/cycle`，即约 2269.5 B/business、45390.7 B/Core；三次测量共 73 次 GC/277 ms。accepted=terminal business/Core。JMH 不含 Aeron/Archive，说明纯 Owner/Lane 路径更快，同时证实分配仍高。

### 结论与彻底方案边界

- 已完成的局部减法（临时准入 publication、重复 matcher DTO、重复终态遍历/清零/查表、迭代器/callback、空索引分配）正确性通过，但没有消除主瓶颈。当前首要问题是**跨线程完成可见性叠加全局 FIFO 的逐命令提交 cadence**；其次是约 2.27 KiB/business 的快照/结果对象分配。不是单纯 owner 某个方法“处理太久”，也不是 busy-spin 唤醒方式或 Lane 尾部算力不足。
- 不能安全地把 `commitReadyMatching(1)` 直接改成更大：当前 realtime capture、终态 publication、响应和窗口槽退休按每条复制命令形成确定性边界，一次提交多条会把不同命令的实时增量合并，改变重放及客户端可见顺序。
- 下一项可实施优化应在 Owner 内部做 **ready-run retirement**：一次读取完成游标/通知并收集当前连续 ready 的命令槽，随后仍按顺序逐条执行 capture→终态 apply/publication→response→slot release；复用一次 matcher/Lane 完成扫描、publication batch 与固定 scratch，不新增线程、任务、barrier、Map 或业务状态副本。必须先加“多条连续 ready 与中间一条未 ready”的重放/实时分组测试，再做单因素 JFR；若每命令 realtime 边界无法从 `commitReadyMatching` 中显式拆开，则不实施该优化。
- 分配的后续顺序是先消除 `OrderRuntime.snapshot()` 在未跨边界场景的重复副本，再评估固定 window result slot + 显式归还协议替代 `CoreMatchingResult`；后者涉及 matcher→Lane→Owner 所有权，不能以对象池名义直接复用。协议解码 byte[]/`PlaceOrderCommand` 则在 Aeron ingress 边界改 flyweight。每项单独验证资金、恢复、六产品线隔离和分配，不能与 ready-run 同轮混改。
- 因本轮性能门槛部分失败，最终状态为：**正确性通过，减法完成，根因已定位；整体性能目标未解决**。当前证据不支持声称只能有 3 万 Core/s，也不支持声称已恢复历史 41.5 万 business/s。

### 证据与清理

- summary SHA256：main1 `f85d9326363f9f633ad116ee3288435035a721b40aa82295d043ad007b53c833`，main2 `e6239af29f7a3188fcc47dcb4ed6b6fff8fa36eacfb366a35f51ced20853b595`，main3 `9e1725da93ae43fc538c701c1b051474decfc3059415248f84040b3beb271b7b`，JFR `047c36e25f84da1f77f733c2fc6e7154c164f80c62229f0a02c58d7b66968ae1`。node JFR SHA256 `212c51c0ac1ba02962dd81d9c18a8d492ef13f590a72344ab66ac52770caa8a7`；JMH main/GC JSON 为 `bd2c46d60d88c1887be2258a00dee4ade9466ff42f8fdd0a1226705f18dd921a` / `63b8c019abedde039a164e8e7954a8efad9d269d545d9457cf0d152dc6e46481`。
- 现有 `analyze-owner-commit-jfr.sh` 仍要求已移除的 `OwnerCommitMeasurement`，严格模式在确认 DataLoss=0 后退出；本轮按现行 `OwnerTurn`、`OwnerSettlementMerge`、`SettlementLatency`、`CommandBoundaryLatency` 直接聚合。该兼容性缺口不影响录制有效性，但脚本需后续更新，不能把缺事件当性能失败。
- 本轮全部 Java/Cluster/JMH 进程已退出；四个真实集群 run、JFR 分析中间文件及两份 JMH JSON 共约 12 GiB 已移至 `/Users/atomex/.Trash/surprising-ex-owner-final-cleanup-20260919/`，可恢复。原 target 路径仅作历史定位；未删除其他轮次或清空 Trash。清理后 swap 仍为 0、磁盘约 301 GiB 可用，`git diff --check` 通过。

## 2026-09-19 Owner 终态直返与完成索引删除（owner-direct-retire-20260919）

### 改动和正确性边界

- 代码审计确认 `progressCommandsInScope()` 已经在一轮内连续退休最多 64 个 ready 队首，并在首条之后跳过公共 Matcher/Lane 推进；再增加 ready 数组、window commit 阶段或新 barrier 会重复现有机制。因此本轮不新增阶段，只让有正序号的 FIFO 队首从 `OrderedCommitCoordinator` 直接返回 `CoreResponse` 给 Owner，并删除 `ClusterCommandWindow.completionSlots` 的逐命令 put/get/remove。控制命令和回放兼容回调仍保留线性兜底；`sequence=0` 的同步终态命令直接使用准入阶段已有响应。
- 第一版错误地把 `sequence=0` 的“无待撮合项”当成“尚未 ready”，`queryAndSessionCloseDrainTheFinalPartialWindowAndRetryIsIdempotent` 和 `deferredSequentialBatchUsesOriginalLogTimeEvenWhenLaterIngressIsAfterExpiry` 超时；加入明确分流后两项及完整参数化流水线通过。该失败结果未用于压测。
- 曾验证终态 `OrderRuntime` 原对象能否代替 `snapshot()`。`RuntimeCommitRecoveryTest.replayAfterRestoreProducesIdenticalResponsesAndState` 证明对象仍可能被后续流程观察/改变，导致历史响应 data 从空值变成旧 OPEN 订单数据；该尝试已完整回退。结论是当前对象模型下 `publicationValue()` 的不可变快照是恢复和响应隔离边界，不能仅凭 terminal status 删除。
- HotSpot Corretto JDK 27、Maven 3.9.16。定向回归 298 项 0 失败；service 完整 940 项 0 失败/1 个既有跳过；benchmarks reactor package 成功。正确 fork 仍为 `/Users/atomex/Desktop/surprising/exchange-core-lilaizhencn` 的 `ad920d8815cd2cd0bfff2d7a8885d0e6a9d26e7c`。未启动 wallet。

### 同口径真实 Aeron 结果

- 配置不变：本机、单成员真实网络与 Archive、LINEAR_PERPETUAL/MIXED/batch20、1 Matcher/4 Lane、全局/session window 256、Owner/Matcher/Lane BUSY_SPIN、30 秒预热/60 秒稳态/单独排空。无 profiler 三轮 `owner-direct-retire-20260919-main{1,2,3}`，独立 JFR 轮 `owner-direct-retire-20260919-jfr`；命令格式与上一节相同，仅替换 run ID。

| 轮次 | 稳态秒 | business/s | Core/s | 最大业务类型 p99 | 排空 ms | window 阻塞占比 | Lane 有效执行均值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| main1 | 60.004418 | 366341.926 | 35006.322 | 25.346 ms | 6.462 | 82.74% | 43.68% |
| main2 | 60.030471 | 359197.230 | 34325.418 | 26.525 ms | 16.764 | 82.64% | 44.88% |
| main3 | 60.006439 | 346261.356 | 33093.898 | 27.983 ms | 5.581 | 82.07% | 45.12% |

- 三轮均值 **357266.837 business/s、34141.879 Core/s**；范围 346261.356–366341.926 business/s、33093.898–35006.322 Core/s；极差/均值 5.62%/5.60%，CV 2.33%/2.32%。三轮窗口峰值均 256，client/saturation gate 均 PASS。每轮最终 offered=terminal、unfinished=0；main3 为 business 20780589、Core 1986093，资金差 0，population/HFT positions/reservations/loss 全部一致。
- 相对上一节三轮均值为 +11.62% business/Core，p99 也全部回到 30 ms 门槛内；但这不是同机交错 A/B，上一组自身极差约 20%，所以只能确认当前代码达到并稳定复现该水平，不能把 11.62% 全部归因于删除 Map。JFR 轮 349111.314 business/s、33365.061 Core/s、p99 26.705 ms，仅用于诊断。

### JFR、分配和最终判断

- 三份 JFR `jdk.DataLoss=0`，保护窗为稳态首尾各剔除 2 秒。Owner 终态提交均值/p99 17.227/54.353 µs（上一轮 17.838/59.013 µs）；response+retirement 0.813/2.107 µs，realtime publication 0.113/0.206 µs。局部减法有效，但节省量不足以解释毫秒级 FIFO 等待。
- 保护窗 `OwnerTurn` 稀疏样本 21470 个，平均退休 1.462，p50/p99 均为 0，99.24% 标记 head wait，窗口起始均值 67.3。该事件按每 64 个非空 turn 采样且 busy-spin 会产生大量无进展 turn，不能把百分比当业务阻塞率；它说明 ready 命令会以少量 64 条级 burst 退休，绝大多数轮次仍在等严格 FIFO 队首完成可见。
- Lane 执行 p99：PLACE 14.036 µs、CANCEL 20.552 µs、PLACE_BATCH 116.414 µs；Lane 完成到 Owner p99：241.967/2078.873/1278.203 µs。主剩余成本仍是跨线程完成可见性、Owner 调度及前序 FIFO 等待，而不是 Owner 的 completion Map 或业务尾部计算。
- 分配热点几乎未变：`OrderRuntime` 26.26%（`snapshot()` 18.51%）、`CoreMatchingResult` 8.67%、`ReservationRuntime` 8.27%、byte[] 7.86%、long[] 6.52%、native `MatcherResult` 5.48%。保护窗 109 次 GC、总暂停 610.715 ms、最大 10.110 ms，约占 56 秒窗口 1.09%；swap 始终为 0。
- 聚焦 JMH 普通轮 108.385±11.623 cycles/s，即约 55493 Core/s、1109862 business/s；独立 GC 轮 105.919 cycles/s，`gc.alloc.rate.norm=23241329 B/cycle`，约 **2269.7 B/business、45393.2 B/Core**，三次测量 75 次 GC/291 ms。与上一轮 2269.5 B/business 实质相同，说明本轮不解决分配。
- 最终结论：**Owner 完成索引和同线程响应往返已彻底移除，当前同口径性能与 p99 达标；但架构级 FIFO 等待和发布快照分配仍未消除。** 下一步不能再把 `OrderRuntime.snapshot()` 直接省掉，必须改变发布表示：让 Lane 把只含本命令 after-image 的固定容量不可变值/编码缓冲交给 Owner，且生命周期覆盖响应、实时发布和恢复校验；随后单独设计 `CoreMatchingResult` 的固定 window slot 与显式归还协议。两项都必须各自做恢复重放、六产品线资金一致性和 JFR 分配验证，不能混改。
- 本轮 Java/Cluster/JMH 进程均已退出；四个真实集群 run 与两份 JMH JSON 共约 13 GiB 已移动到 `/Users/atomex/.Trash/surprising-ex-owner-direct-retire-20260919/`，可恢复。清理后 swap 为 0，磁盘约 289 GiB 可用，`git diff --check` 通过。

## 2026-09-20 canonical instrument 完整迁移验证（canonical-instrument-20260920）

### 采集前锁定计划

- 本轮只验证当前 `master=7dbcaebdb032b06c61a3a5b22d84887e15c4e9d0` 加工作区 canonical instrument 完整迁移；对照 commit 不适用，不检出旧版本，也不把历史 `41.5万 business/s` 当成本轮基线。采集前 tracked diff SHA-256 为 `3b4f42e6696a4f24672a3eaf3f8031480799e4ef4364e2168805df76e1546d09`，status 摘要 SHA-256 为 `0d5009b4326f2bad6c32a6d4c6f08f08ea134b5c2aaf053c3041014e0544338a`。
- 测试修正后、正式采集前的最终 tracked diff SHA-256 为 `8238aa34aa1d314df93787e3f115492bcb8c5d6d3140edee3384c776454ae433`，status 摘要 SHA-256 为 `dfa08bb009f8a21e7c8017e0912a95e37f7f7895d850edee9709771135a39ef6`。偏差来自删除最后的 price-provider 历史尺度测试、把 `InstrumentCoreSyncService` 改为首次缓存就绪时一次冻结并注册完整启动集合，以及同步文档；性能参数不变。
- 首个 `canonical-instrument-20260920-main1` 在功能检查阶段因压测夹具逐 symbol 交错执行 register/mark、触发新 registry seal 而立即失败，未进入预热或测量，数据作废。夹具已改为先注册全部 128 个 instrument、再统一发布 mark；重建后采集源码 tracked diff SHA-256 为 `7eb3e22f34d0526d2845536c89ee8c1de776d0dbdfd8a033fe2fb0fc1f96811b`，作废轮不占三轮样本。
- 改动目标：instrument 在 Core 启动注册后封存为唯一 canonical `CoreInstrument` 对象，订单、持仓、标记价、触发单及结算进度只在确需合约计算的长期状态保留该引用；删除 `CoreInstrumentState`、`instrumentChangeId` 的 Core 协议/撮合 evidence/查询/快照传播、reservation 重复引用、instrument 专用 rolling/snapshot hash、兼容命令与死 API。恢复仍依赖 snapshotId、Core/matcher sequence、业务/资金状态 hash、active-order hash、模块 checksum 和快照 CRC；不增加线程、barrier、状态副本或 fallback。
- 正确性门槛：JDK 27 下全仓测试零 failure/error（环境条件测试允许既有 skip）；六产品线资金、冻结、持仓、订单终态、风险/强平/资金费/交割/期权及快照恢复保持一致；真实集群每轮零业务错误/超时，accepted=terminal business/Core、unfinished=0、期末 backlog=0、fundsDiff=0、population/HFT positions/reservations/loss 均通过。任一业务或恢复错误判失败。
- 环境：本机用户明确授权；macOS 26.7 x86_64、16 logical CPU、16 GiB，HotSpot Corretto 27.0.0.33.1、Maven 3.9.16、G1；开始磁盘 285 GiB 可用、swap total/used/in/out 均为 0。无 CPU 绑核或物理独占，不终止 IntelliJ 等用户进程，结果不能外推三节点生产容量。
- 全仓测试结束后、正式采集前 swap used 从 0 增至 128.5 MiB，且 Java/Aeron 进程已全部退出；这是本机共享环境偏差。每轮额外记录 swap 与 `vm_stat`，要求 used 不继续增长且测量窗无持续 page-in/page-out；不满足则该轮无效，不能与历史零 swap 轮作严格 A/B。
- 真实集群固定同前口径：单 Aeron 成员，网络与 Archive 保留；LINEAR_PERPETUAL，MIXED，batch=20，128 symbols，seed=25620，1385 users；4 Account Lane、1 matcher，全局/session in-flight=256，Owner/Matcher/Lane BUSY_SPIN；30s 预热、60s 稳态、排空单列。无 profiler 三轮 `canonical-instrument-20260920-main{1,2,3}`；独立 JFR 一轮 `canonical-instrument-20260920-jfr`，不把 profiler 吞吐与主轮混算。
- 探索门槛沿用当前同口径：每个有效主轮持续 business throughput 不低于 300,000/s、Core throughput 不低于 30,000/s，各类请求 p99 不高于 30ms；三轮极差/均值超过 10%标记不稳定。窗口满 256 后报告背压占比和 coordinated-omission 缺口，不把最好轮或短峰值称为容量。
- JFR 使用现有 owner profile，node/runner/fork 每份最大 256MiB；要求 `jdk.DataLoss=0`、无 swap 增长，采集 owner/matcher/lane CPU与等待、GC/safepoint、ThreadAllocationStatistics、TLAB/非TLAB及 allocation samples。保护窗按稳定期首尾各剔 2s；采样权重不冒充精确对象数。
- 分配补充使用现有 `ContinuousOwnerBenchmark.placeCancelWithoutTimers`：LINEAR_PERPETUAL、batch20、DISTINCT/BUSY_SPIN、1 matcher/4 Lane/window256、1 fork/1 thread、3×3s warmup、3×3s measurement、512MiB/G1；主轮和独立 `-prof gc` 分开。报告 B/business、B/Core、GC count/time；JMH 不含真实网络/Archive，不替代集群吞吐。
- 完整命令：先 `mvn test` 和 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`；集群按 `ASYNC_RUN_ID=<run> ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=<false|true> ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。采集后停止进程并仅清理本轮 Archive/JFR/log/report，结果与清理状态追加在本节。

### 构建、正确性与真实集群结果

- HotSpot Corretto 27.0.0.33.1 / Maven 3.9.16 下根 reactor `mvn test` 通过；此前完整 benchmark reactor package 通过，修正 `ClusterMixedCapacityMain` 启动顺序后 `-DskipTests package` 与 `ClusterMixedCapacityTest` 定向测试通过。Core 内 `instrumentChangeId|CoreInstrumentState|instrumentRegistryHash|hashInstrument|instrumentHash` 残留扫描为零，`git diff --check` 通过。
- 首个无效轮只暴露夹具问题：旧夹具每注册一个 symbol 就发 mark，首个 mark 会封存 registry，第二次注册被 `INVALID_COMMAND` 拒绝。现改为先连续注册全部 128 个 instrument，再统一发布 mark；生产启动器同时改为首次 cache 就绪时冻结完整启动集合并在一次 reconcile 内顺序注册，后续配置事件不替换 Core canonical 对象。

| run | 稳态秒 | business/s | Core/s | 最大业务 p99 | drain | window blocked | gate |
|---|---:|---:|---:|---:|---:|---:|---|
| main1-valid | 60.055805 | 368355.247 | 35197.380 | 26.509 ms | 16.079 ms | 49.199 s | PASS |
| main2 | 60.013397 | 338875.204 | 32390.035 | 30.113 ms | 5.361 ms | 49.332 s | PASS |
| main3 | 60.008596 | 325643.011 | 31129.473 | 30.867 ms | 5.674 ms | 49.027 s | PASS |

- 三轮均值 **344291.154 business/s、32905.629 Core/s**；极差/均值 12.406%/12.362%，CV 6.351%/6.328%，超过 10% 稳定性门槛。相对上一节 357266.837/34141.879 均值低 3.63%/3.62%，但本轮测试后有 128.5 MiB 冷页留在 swap 且三轮单向走低，不能把差异归因于 canonical instrument 改造，也不能作严格同环境 A/B。
- main2/main3 p99 分别比 30 ms 门槛高 0.113/0.867 ms，因此结论是吞吐下限门槛通过、延迟与稳定性门槛未完全通过。三轮均达到 in-flight 256，offered=terminal business/Core、unfinished=0、期末 backlog=0、fundsDiff=0，population/HFT positions/reservations/loss 全部通过；swap used 始终 128.5 MiB，轮后连续采样未见 swapin/swapout 增量。
- JFR 诊断轮为 318414.015 business/s、30441.035 Core/s、p99 31.965 ms，仅用于归因。Owner、matcher 和四条 Lane 都约 98.1% 单核占用，说明独占 CPU 下 BUSY_SPIN 正常工作，瓶颈不是阻塞唤醒；context/matcher/completion high-water 分别 255/225/208，Lane high-water 45–62，Owner 与 matcher 饱和而 Lane 实际业务执行占比约 44.8%。

### JFR、分配与结论

- node/runner/fork 三份 JFR 均 `jdk.DataLoss=0`。56 秒保护窗内 96 次 G1 young GC，总暂停 522.480 ms、最大 12.068 ms，约占保护窗 0.93%；GC 会放大尾延迟，但不是 3 万 Core/s 的唯一限制。
- Owner 终态提交均值/p99 18.600/66.157 µs，其中 fact publication 5.496/14.323 µs、terminal bookkeeping 3.080/7.525 µs、realtime publication 0.118/0.232 µs、response+retirement 0.914/2.158 µs。`OwnerSettlementMerge` 的 923 个 timing 样本总耗时均值/p99 9.116/21.192 µs；publication 7.267/17.486 µs、removals 3.345/10.919 µs、terminal index 1.152/4.256 µs。删除 instrument hash/版本传播没有引入新的 Owner 热点。
- Lane 执行 p99：PLACE 17.751 µs、CANCEL 20.751 µs、PLACE_BATCH 137.252 µs；Lane 完成到 Owner p99：283.841/1686.546/1113.767 µs。`OwnerTurn` 21394 个稀疏样本中平均退休 1.184，p50/p99 均为 0，14203 个零退休、6553 个单条、638 个多条，21305 个标记 head wait。真正的主限制仍是跨线程完成可见性、严格 FIFO 队首等待与 Owner/matcher cadence，不是 instrument 对象引用或唤醒策略。
- JFR 分配权重：`OrderRuntime` 25.91%（`snapshot()` 18.03%）、`CoreMatchingResult` 8.06%、byte[] 8.01%、`ReservationRuntime` 7.71%（`snapshot()` 4.45%）、long[] 6.85%、native `MatcherResult` 5.99%。canonical instrument、instrument hash 和版本包装不在分配头部。
- 聚焦 JMH 普通轮 105.818±22.740 cycles/s，约 54179 Core/s、1083575 business/s；独立 GC 轮 104.885 cycles/s，`gc.alloc.rate.norm=22468371 B/cycle`，折合 **2194.2 B/business、43883.5 B/Core**，三次测量 72 次 GC/259 ms。相对上一轮 2269.7 B/business 下降约 **3.33%**，说明本轮删减有效但没有触及主要快照分配。
- 最终判断：canonical instrument 迁移本身已完成，Core 每个 symbol 只有启动时创建的单一对象，订单/持仓/mark/触发/结算仅在需要合约数学时持有该引用；reservation 重复引用、Core 版本字段、撮合 evidence 版本、instrument rolling/snapshot hash、兼容命令和死 API 已删除。保留的 `CanonicalHasher` 只服务资金与业务恢复状态校验，原本即存在且不再包含 instrument 配置，不是 instrument canonicalization 层；删除它会移除恢复一致性边界，不能作为本轮减法。
- 性能没有退回“只能 3 万”：当前真实网络/Archive 满载为 31.1k–35.2k Core/s、32.6万–36.8万 business/s；纯内存 Owner/Lane JMH 约 54.2k Core/s。要继续提升并压低 p99，下一项应单独重构 `OrderRuntime.snapshot()`/`ReservationRuntime.snapshot()` 的发布表示，再处理 `CoreMatchingResult` 固定 window slot；instrument 路径已不是下一瓶颈。
- 证据 SHA-256：main1/main2/main3/JFR summary 分别为 `07b21c63e303ee9ff6d00ca0875c458772909635bd00434287d8b0d12e089002`、`37cdec1a2eafdedd156db7be21d82eaedad068d4101f59bf52f041be93c697a0`、`5fc9e995b5e978eba288683d4c54f4ae676d350a638a6e83bac7fbc0849edc18`、`4ee18438d71a4467e770c1ed72001b6133eafd04262dbd5343d519266fecc86f`；node JFR `425f06002bc0d403dd805381b9cf547a7170e25d89a1cf7c39fd0a753ff9aeda`；JMH main/GC JSON `4ddf02919677ee06697f84f5bc95f700228ac96f3a99cf7137352f1424009a7c` / `c9ad20d76560467120ad584fa6f5e8bcbe37f3ea75e35d6dbf3b7994e83131c6`。
- 本轮全部 Java/Aeron/JMH 进程已退出；5 个真实集群 run（含一个启动功能检查作废轮）和 2 份 JMH JSON 共约 12 GiB 已移动到 `/Users/atomex/.Trash/surprising-ex-canonical-instrument-20260920/`，可恢复，未移动或删除其他轮次。清理后 swap used 仍为 128.5 MiB、磁盘约 271 GiB 可用。

## 2026-09-20 Core 兼容层与导出链删除后的本机验证（core-cleanup-20260920）

### 采集前锁定计划

- 本轮仅验证当前 `master=49647e0e70bcb60be7c990b3997e5ec8bc5ba19c` 加工作区未提交改动，工作区状态 SHA-256 为 `d8764969eab2fc25e44c8cd318ed3eb88dd5005f3a8c87d3e4eb25d8aa81cb37`；不检出旧版本，对照 commit 不适用。主要改动为删除 matcher prefix/hash 与 evidence 包装、Core export/ACK/query/snapshot 链、`RuntimeCommandProcessor`、旧 snapshot/cursor/Lane 别名及 Core 回执导出序号，并升级相关协议格式。
- 问题与门槛：先确认协议、快照、撮合及交易调用方能够构建并通过受影响测试，再验证真实单成员 Aeron 满载运行是否存在正确性或明显性能问题。探索门槛沿用当前口径：稳定窗 terminal business throughput 不低于 300,000/s、terminal Core throughput 不低于 30,000/s，分业务最大 p99 不高于 30ms；accepted=terminal、unfinished=0、期末 backlog=0、资金差0、快照/恢复一致且无业务错误/超时。任一正确性失败判失败；swap/page-in 增长、磁盘不足、进程异常或测量窗错位使性能证据无效。
- 环境：本机 macOS 26.7 x86_64，Intel Core i9-9880H，16 logical CPU、16GiB；HotSpot Corretto JDK 27.0.0.33.1、Maven 3.9.16、G1；磁盘采集前可用约270GiB。无CPU绑核或物理独占，不外推三节点生产容量；采集前确认没有既有 Java/Aeron 压测进程。
- 业务场景：单真实 Aeron Cluster 成员，网络与 Archive 保留；LINEAR_PERPETUAL、MIXED batch20、128 symbols、既有固定种子和做市初始化；4 Account Lane、1 matcher；全局/session/owner in-flight 固定256；Owner、matcher pipeline、settlement 均 BUSY_SPIN。持续异步发压，30s预热、60s稳定测量、排空单列。
- 执行顺序：先运行受影响 reactor 的编译与测试，并构建 benchmarks shaded artifacts；失败则停止，不产生性能结论。通过后只运行一次无 profiler 主轮 `core-cleanup-20260920-main1`，完整命令为 `ASYNC_RUN_ID=core-cleanup-20260920-main1 ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。
- 采样与有效性：本轮用户要求“一次压测”，因此只给单轮当前代码结果，不声称稳定容量或回归幅度；无 profiler 主轮用于吞吐和业务尾延迟，脚本同时保存 NMT、线程、GC日志、窗口/队列与完成性指标。分配只能使用脚本现有 GC/NMT 证据，若需要精确 B/business 需另跑独立 `-prof gc`，本轮不以缺失值填零。完成后停止本轮进程，只清理确认属于本轮的 Archive/JFR/log/report，并把结果、偏差与清理状态追加到本节。

### 构建、满载故障与修复

- JDK 27 受影响 reactor 首轮暴露出本次删除未闭环：通用 `MatchingResult` 迁移未覆盖工具/benchmark，快照 inspect 仍调用已删除的 export 状态；已删除这些旧类型/API 依赖，benchmark reactor `-DskipTests package` 通过。service 完整测试 936 项中仍有 5 个失败：4 个为 snapshot/hash 删除后的恢复断言或校验行为未同步，1 个为 fault-injection 终态准备失败仍返回 APPLIED；因此当前工作区不能标记为完整正确性通过。
- `core-cleanup-20260920-main1` 在预热满载时失败，结果无效：window high-water 254、pending 61 时，Owner 将 direct 槽中的原生 `exchange-core MatcherResult` 当普通 `CoreMatchingResult` 消费，cluster 终止，客户端出现 `ResultUnknownException`，无吞吐/p99 数据。
- 两次短诊断稳定复现到竞态：`completedMatchingSequence()` 先调用 `skipReadyDirectSlots()`，若 matcher 恰在扫描后、completed-position 读取前发布 direct 槽，旧代码会直接暴露其 token，后续 `poll()` 进入错误类型分支。修复是在暴露 token 前重检槽位并继续退休 direct 槽，不新增线程、队列、barrier 或业务状态。Matcher pipeline/group、批量顺序定向测试通过；修复后 5s/10s 诊断轮 PASS，347451.835 business/s、33206.926 Core/s、最大 p99 29.081 ms、unfinished=0。

### 单轮正式结果

| run | 稳态秒 | business/s | Core/s | 最大业务 p99 | drain | window blocked | gate |
|---|---:|---:|---:|---:|---:|---:|---|
| main2 | 60.028610 | 291681.916 | 27895.165 | 37.289 ms | 7.003 ms | 48.623 s | PASS |

- 正式轮达到 in-flight 256，context/matcher/completion high-water 为 255/211/200，Lane high-water 48–60；offered=terminal、unfinished=0、pending=0，未再出现 matcher 类型竞态或集群错误。四条 Lane 有效执行占比 46.65%–46.92%，不是 Lane 算力打满。
- 本轮低于探索门槛：business -2.77%、Core -7.02%，最大 p99 高 7.289 ms；相对 canonical instrument 三轮均值 344291.154/32905.629 约低 15.28%。但只有一轮且本机 swap used 全程为 96.5 MiB，不能声称形成稳定回归幅度，也不能与历史零 swap 轮作严格 A/B。
- 分业务 p99：PLACE 17.989 ms、CANCEL 15.884 ms、MARK 19.857 ms、PLACE_BATCH 21.757 ms、CANCEL_BATCH 37.289 ms；尾延迟主要由批量撤单拉高。window 阻塞约占稳态 81.00%，仍是严格窗口/完成 cadence 下的强背压。
- 本轮未启用 JFR/`-prof gc`，不能从 NMT 给出精确 B/business；分配结论保持“未测”，不能用此前约 2.2 KiB/business 代替当前代码。summary SHA-256：正式轮 `3936ae42677a81828ce4d8615c3ee9b1acd76fa4febe30719b9f9bb4803df0a9`，通过诊断轮 `de0894218feceafb6807af07acc29147cc6172ecafb9477eca49ee62b1505e2c`。
- 本轮 Java/Aeron/JMH 进程均已退出，磁盘约 269 GiB 可用，`git diff --check` 通过。保留本轮报告用于继续分析，未删除其他数据。

### 重启后同口径复测

- 用户重启本机后，采集前 swap total/used 均为 0，无遗留 Java/Aeron 进程；JDK 27、Maven 3.9.16、master 分支和压测参数均与 main2 相同。执行 `core-cleanup-20260920-main3-reboot`，30 秒预热、60 秒稳态，无 profiler。
- 结果为 **385746.464 business/s、36853.928 Core/s、最大业务 p99 25.821 ms**，drain 6.173 ms；in-flight 峰值 256，offered=terminal、unfinished=0、pending=0，client/saturation gate PASS。分业务 p99：PLACE 13.402 ms、CANCEL 11.124 ms、MARK 18.333 ms、PLACE_BATCH 16.490 ms、CANCEL_BATCH 25.821 ms。
- 相比重启前 main2，business/Core 吞吐均提高约 32.25%，最大 p99 从 37.289 ms 降至 25.821 ms；同一代码已超过 300k/30k/30ms 门槛，并高于 canonical instrument 三轮均值。证据不支持“代码修改导致稳定容量降至 29 万”；低轮主要受重启前本机状态影响。重启同时清除了 swap、冷页/热状态及潜在调度干扰，因此不能把改善单独归因于 swap 数字。
- 四条 Lane 有效执行占比仍约 46.44%–46.79%，matcher/completion/context high-water 为 211/206/255，说明业务结构和满载点未改变；提升来自单位时间完成 cadence 恢复，不是压测未打满或降低了工作量。正式 summary SHA-256 为 `9088bbbfcbd35bfd48daaa1cbe89e1412b8177c916f11b1e92f2d564adfc751b`。轮后 swap 仍为 0，所有压测进程已退出；本轮报告、日志、Archive 等生成物已移至 `/Users/atomex/.Trash/surprising-ex-core-cleanup-reboot-20260920/`，可恢复，未移动其他轮次。
- 该结论只回答吞吐低值归因；前述 service 完整测试的 5 个正确性失败仍需独立修复，不能因性能门槛通过而视为可发布。

## 2026-09-20 Core 热路径减法后的本机单轮压测（core-reduction-20260920）

### 采集前锁定计划

- 本轮只验证当前干净 `master=2789ec8f`，对照 commit 不适用（仅验证当前 master），不检出或重跑旧代码。改动范围是删除命令响应逐笔 state hash、无用 pending 元数据、旧 matcher 结果包装和派生快照 metadata；其中响应与 Owner 退休路径可能影响稳态，快照字段减法主要影响持久化边界。
- 环境为本机 macOS 26.7/x86_64、Intel Core i9-9880H、16 logical CPU、16 GiB；HotSpot Corretto JDK 27.0.0.33.1、Maven 3.9.16、G1；采集前磁盘 265 GiB 可用，swap total/used 为 0。无 CPU 绑核或物理独占，IntelliJ 等用户进程保留，因此结果不外推三节点生产容量。
- 业务场景固定为单真实 Aeron Cluster 成员，保留网络与 Archive；LINEAR_PERPETUAL、MIXED、batch=20、128 symbols、既有 seed=25620 和做市初始化；4 Account Lane、1 matcher，全局/session in-flight=256，Owner/Matcher/Lane 使用 BUSY_SPIN。持续异步提交，30 秒预热、60 秒稳定测量、排空单列。
- 探索门槛沿用当前同口径：稳定窗 terminal business throughput 不低于 300,000/s、terminal Core throughput 不低于 30,000/s，各业务 p99 不高于 30 ms；业务错误/超时为零，accepted=terminal business/Core、unfinished=0、期末 backlog=0、fundsDiff=0，population/HFT positions/reservations/loss 与快照恢复检查通过。swap 增长、磁盘不足、进程异常或测量窗错位使性能证据无效。
- 本轮按用户要求先执行一次无 profiler 主轮，run ID `core-reduction-20260920-main1`；单轮只报告当前吞吐和尾延迟，不声称稳定容量或精确回归幅度。命令：`ASYNC_RUN_ID=core-reduction-20260920-main1 ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。
- 执行前构建 benchmarks reactor；本轮代码已在 JDK 27 下通过 service 全套 919 项（1 skip）及全仓 test-compile。无 profiler 轮用于主吞吐和业务尾延迟；分配若无独立 `-prof gc`/JFR 证据则明确写未测，不引用历史值。结束后停止本轮进程，归档摘要并只清理本轮生成物。

### 单轮结果与结论

| run | 稳态秒 | business/s | Core/s | fills/s | 最大业务 p99 | drain | gate |
|---|---:|---:|---:|---:|---:|---:|---|
| main1 | 60.013228 | 369871.053 | 35342.141 | 88044.589 | 28.393 ms | 5.991 ms | PASS |

- 稳态完成 22,197,156 business、2,120,996 Core、5,283,840 fills；最终 offered=terminal（22,199,820 business、2,121,228 Core），unfinished=0、pending/backlog=0。`fundsDiff=0`，population、HFT positions、reservations、loss 校验全部通过，业务 hash 为 `66b013468bdb4166`。
- 分业务 p99：PLACE 12.722 ms、CANCEL 11.665 ms、MARK 15.359 ms、PLACE_BATCH 15.982 ms、CANCEL_BATCH 28.393 ms；全部低于 30 ms 探索门槛。峰值 in-flight=256，context/matcher/completion high-water 为 255/217/207，Lane high-water 为 58/54/55/61，证明持续满窗而非发压不足。
- window blocked 49.248 s，约占 60.013 s 稳态窗的 82.06%；四条 Lane 有效执行占比为 44.69%–45.04%。当前单轮通过 300,000 business/s、30,000 Core/s 与 30 ms p99 门槛，且结果仍显示主要背压位于窗口、跨线程完成可见性和 Owner/matcher 完成 cadence，Lane 业务计算没有打满。
- 相比重启后历史单轮 385746.464 business/s、36853.928 Core/s，本轮低约 4.11%；两者不是同 commit 的严格 A/B，且都只有单轮，不能据此归因代码或宣称容量回归。本轮证明当前 `master` 没有退回 3 万以下 Core/s，但也不能据此宣称达到历史 41.5 万峰值或稳定容量。
- 本轮未启用 JFR/`-prof gc`，NMT 只能描述 native committed 变化，不能换算精确 B/business，因此当前代码分配明确记为未测，不复用旧版本约 2.2 KiB/business 的结果。summary/metrics/JMH JSON SHA-256 分别为 `975d2603ba103126e8adead33e72f087f4465c81cedf82124184c558788e122a`、`9a672f777cc1fe2b4caf2568d37c1e334f50e71eaddb06bf2580f8480df0285a`、`7d490df1c0869ecb9d80151ef2b792aef801b4e2520921d4e8b4e850339623a4`。
- 压测结束后 swap total/used 仍为 0，日志未发现 ERROR、异常或失败，Aeron/benchmark Java 进程均已退出；本轮产物约 3.2 GiB，清理状态见下文。
- 本轮约 3.2 GiB 的 Archive、日志、NMT 与报告已移动到 `/Users/atomex/.Trash/surprising-ex-core-reduction-20260920/core-reduction-20260920-main1/`，可恢复；未移动或删除其他轮次产物。

## 2026-09-20 Owner 派发重复扫描定位与修复（owner-dispatch-revision-20260920）

- 本轮针对 `master=1af93018` 的普通 `MATCH_STREAM` 生产式异步流水线做本机定位。固定 1 matcher、4 Account Lane、10,000 users、128 symbols、in-flight=256、每 invocation 16,384 条普通 PLACE；HotSpot Corretto JDK 27.0.0.33.1、Maven 3.9.16。该场景一条 business operation 对应一条 Core message，不能与 `MIXED batch20` 的 business item/s 直接比较。
- 历史 36.9万–41.5万是 batch20 展开后的 business item/s，对应 Core message 约 3.15万–3.7万/s；41.5万峰值还使用 GraalVM 25。当前 3.4万普通 Core message/s 与历史 Core 口径处在同一量级，不是删除 hash/version/包装后从 41.5万退化到 3万。
- JFR 定位到 `PendingMatchingRing` 用同一个 revision 同时表示“异步完成唤醒”和“partition 候选拓扑改变”。完成通知并不改变候选拓扑，却让 `readyPartitionMask()` 反复扫描窗口。提交 `272a2d33` 将 Owner 唤醒 revision 与 partition topology revision 分离，严格 FIFO、Lane 所有权、terminal publication 和 slot release 顺序不变。
- 无 profiler 同口径由修复前约 **31,894 Core/s** 提升到 **33,949.811 Core/s**，约 **+6.4%**；五次测量范围 33,654.750–34,122.469 Core/s，trades 均值 16,974.906/s，错误、拒绝、超时、unfinished 均为 0。复采样中 `readyPartitionMask` CPU 权重由 4.46% 降至 1.32%，与改动目标一致。
- `ResponseArena` 轮转 claim 起点实验为 34,031.158 Core/s，相对 33,949.811 仅 +0.24%，落在误差内，已撤回，未把无证据复杂度带入生产代码。`assertAccountLanesHealthy()` 中重复的 Owner 线程身份检查已删除（提交 `1af93018`）；其读的是原有跨线程 failure 原子引用，失败传播不变。该减法后无 profiler 均值 34,286.764 Core/s，但相对前轮约 +1.0%、置信区间重叠，不把它单独宣称为稳定性能收益。
- 尝试让 deferred PLACE 强制走 matcher-owned direct publication 时，正确性门禁立即报 `matcher result escaped its admitted Lane dependency scope`：Matcher 才能发现的 maker Lane 超出 admission 初始 Lane。随后验证 Owner-controlled direct event 时，高并发 pipeline 出现无进展忙轮询。两项实验均已完整撤回；它们说明 fallback `CoreMatchingResult` 不是可直接删除的兼容垃圾。彻底移除它需要一次完整的 deferred settlement 协议迁移：Matcher 将 native result 写入 command-owned event，Owner 在结果就绪后补齐实际 Lane 路由并可靠唤醒/派发，同时保留 shard/FIFO/恢复证据，不能局部强开。
- 完整 service reactor 测试 **916 tests、0 failures、0 errors、1 skipped**；六产品线 `ClusterCommandPipelineTest` 与 matching state 定向覆盖通过。最终 GC profiler 轮为 33,210.372 Core/s、16,605.186 trades/s、unfinished=0；`gc.alloc.rate.norm=97,836,124 B/invocation`，折合约 **5,972 B/business**。该 JMH 数字包含命令构造、codec、校验及 harness；JFR allocation samples 中 81.38% 位于 JMH worker，不能当成纯 Core 分配。Core matcher 上仍可见 native `MatcherResult` 与 deferred fallback `CoreMatchingResult`，后者约占采样分配 1%。
- JFR 开环目标为 100,000/s，超过本机约 34,000/s 容量，所以 entry→terminal p99 为 323–334 ms，主要是发压排队；accepted→terminal p99 为 **7.40–7.45 ms**。窗口没有长期 full sample，refill 约 33.7k/s；普通命令每笔约产生 1 次 admission Lane 和通常 2 次 settlement Lane，实测 lane operations 约 84.9k/s，对应约 34.0k Core/s。剩余主成本是严格 FIFO 队首完成、Owner/Lane 完成 mask 轮询、响应 arena 扫描和业务 after-image 分配，而不是已删除的 instrument/hash/version 逻辑。
- 随后按历史 37万口径补跑真实单成员 Aeron：LINEAR_PERPETUAL、MIXED batch20、128 symbols、seed=25620、4 Account Lane、1 matcher、全局/session in-flight=256、Owner/Matcher BUSY_SPIN，30秒预热、60秒稳定窗、排空单列；HotSpot Corretto JDK 27/G1，不启用 profiler。沿用 300,000 business/s、30,000 Core/s、各业务 p99 30ms、零错误/超时/unfinished、资金与业务状态校验通过的探索门槛。完整命令为 `ASYNC_RUN_ID=<run> ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=<false|true> bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。
- 首轮 `owner-dispatch-revision-20260920-main1` 是**正确性失败且性能数据无效**：客户端已完成约 14,137,975 business operations 后，Matcher 在 `PlaceAdmissionEvent.awaitMatcherReceipt()` 抛出 NPE。根因是 Matcher 先以 volatile 写发布 `matcherConsumed=true`，Owner 随即回收事件并把 `runtime` 清空，而 Matcher 下一行仍经事件字段调用完成通知。这不是业务算力退化，而是池化事件跨线程交接的发布后使用竞态。
- 提交 `6e52dfb8` 在发布消费标志前将通知目标捕获为 Matcher 局部引用，并规定发布后不再读取可回收事件；没有新增对象、锁、线程、barrier、状态副本或每命令操作。修复后 service 完整 reactor 再次通过 **916 tests、0 failures、0 errors、1 skipped**，其中六产品线集群/撮合定向测试 284 项通过。

| run | 稳态秒 | business/s | Core/s | fills/s | 最大业务 p99 | drain | 完成性 |
|---|---:|---:|---:|---:|---:|---:|---|
| racefix1 | 60.009469 | 397308.134 | 37955.777 | 94577.075 | 26.050 ms | 5.188 ms | PASS |
| racefix2 | 60.021101 | 366239.599 | 34996.092 | 87180.007 | 28.377 ms | 5.474 ms | PASS |

- 两轮 business/Core 吞吐算术均值为 **381,773.867 / 36,475.935 ops/s**，范围 366,239.599–397,308.134 / 34,996.092–37,955.777；business 极差/均值约 8.14%。第一轮超过37万，第二轮低约1.02%，所以37万位于该无绑核桌面本机的轮间波动区间，不能作为每轮稳定硬边界；证据不支持删除 hash/version/包装使代码稳定退化。
- 两轮均达到 in-flight 256，context high-water 255；matcher/completion high-water 分别为216/209和215/215。稳定窗分别完成23,842,250和21,982,104 business operations；最终 offered=terminal business/Core、unfinished=0、pending/backlog=0，`mixedVerify=PASS`、`fundsDiff=0`，population/HFT positions/reservations/loss 全部通过，日志无 ERROR/Exception。Lane 有效执行均值约45.23%/45.58%，Lane业务计算仍未打满。
- `racefix1` 分业务p99：PLACE 11.960ms、CANCEL 11.780ms、MARK 12.476ms、PLACE_BATCH 15.425ms、CANCEL_BATCH 26.050ms；`racefix2` 分别为13.066/12.107/15.491/16.465/28.377ms，均通过30ms探索门槛。两轮 window blocked 49.341s/49.310s，说明256窗口持续背压，吞吐仍主要受跨线程完成与严格FIFO推进 cadence 限制。
- 本轮真实集群主轮未启用JFR/`-prof gc`，因此不从其推导分配；分配仍引用本节独立GC JMH的约5,972 B/business及其“包含harness、不是纯Core”的限制。主轮 summary SHA-256：失败轮 `e2e5658bbb8f7be5733b93a0aa2722a4730a2fdb02e031b50d3f835accd5b7ac`，racefix1 `b7e510d09723e165d246d25b4890857d1fbd93a0b7d8889113c61ce78abd5bfa`，racefix2 `6f288d54001baf48d21c4b12190978521d370c7666f0d832fb5b81067f0efd7d`。压测前后swap total/used均为0，磁盘最低约253GiB；结果只代表本机单成员，不外推三节点生产容量。
- 全部本轮 Java/Aeron/JMH 进程已退出；失败轮、两次修复后主轮及本轮临时 JFR/测试日志共约9.2GiB，已移动到 `/Users/atomex/.Trash/surprising-ex-owner-dispatch-revision-20260920/`，可恢复，未移动或删除其他轮次产物。原 target/tmp 路径仅作历史定位。

## 2026-09-21 最新 master batch20 分配复测（owner-allocation-20260921）

### 采集前锁定计划

- 只测当前干净 `master=91964956f6f5ffa918d572af7ef824cadfa07e5e`，不检出旧版本；对照使用此前同一基准、同一参数的当前分支历史结果 **2194.2 B/business**，仅用于判断分配是否下降，不比较跨配置吞吐。
- 场景固定为 `ContinuousOwnerBenchmark.placeCancelWithoutTimers`、LINEAR_PERPETUAL、batch20、DISTINCT、BUSY_SPIN、1 matcher、4 Account Lane、owner window 256；每 invocation 为512 Core messages、10,240 business operations。该内存闭环包含负载构造、codec、响应与恢复校验，不含真实网络/Archive，结果不是纯 Core 分配或实际集群容量。
- HotSpot Corretto JDK 27.0.0.33.1、G1、512MiB固定堆；1 fork/1 thread，3×3s预热、3×3s测量，独立 `-prof gc`。记录JMH主分数、`gc.alloc.rate.norm`、换算B/business与B/Core、分配率、GC次数/时间；accepted/terminal business/Core必须相等，teardown资金、冻结、订单终态和恢复检查不得失败。
- 本轮问题是“最新代码同口径分配是否低于2194.2 B/business”。小于该值且测量有效才判下降；相同或更高则判未下降。单fork短轮不证明长稳、对象存活量、泄漏或真实Aeron精确B/business。采集前swap为0、磁盘253GiB可用、无残留Java/Aeron进程；结束后停止进程并只清理本轮生成物。

### 结果与结论

- JDK 27 benchmarks reactor `-DskipTests package` 成功，使用当前 master 重新生成 `product-core-benchmarks.jar`。最终有效命令在上述固定参数外使用 `-wi 3 -w 3s -i 3 -r 3s -f 1 -t 1 -foe true -prof gc`，JVM参数为512MiB固定堆/G1及既有JDK模块开放；补充 `java.lang.reflect` 开放后，最终轮无 Chronicle模块访问ERROR。
- 最终GC轮主分数 **109.731 ± 10.806 invocation/s**；三次测量109.053–110.146 invocation/s。`gc.alloc.rate.norm=19,514,138.247 B/invocation`，三次范围19,351,709.146–19,838,491.480 B/invocation；按每invocation 10,240 business / 512 Core换算为 **1,905.68 B/business、38,113.55 B/Core**。分配率1577.123 MB/s；65次GC、总GC时间234ms。
- 三次测量合计 accepted/terminal business均为10,137,600，accepted/terminal Core均为506,880，逐项相等；trial teardown未失败，日志无ERROR/Exception/failure。相对此前同基准同参数 `22,468,371 B/invocation = 2,194.18 B/business`，本轮减少 **288.50 B/business，下降13.15%**，因此“最新代码同口径分配已下降”成立。
- 该1,905.68 B/business仍包含命令与batch集合构造、codec、响应和JMH harness，不是纯Owner/Core分配；它也不证明真实Aeron、对象存活量、old/live set或长期泄漏表现。最新普通MATCH_STREAM约5,972 B/business属于batch1式固定成本未摊薄场景，不能与本次batch20数值直接比较。
- 最终JSON/日志SHA-256分别为 `84adfa9dd9233707310aff904a34aa60b44eae3dca2c1d36b53873ce3c925f3f` / `22991ec663bb9352cc586fdaa9f2da1d9f1653155f4e8a2a3c8631d94b132bd7`。结束后无Java/Aeron/JMH进程，swap total/used均为0，磁盘约252GiB可用。
- 本轮构建日志、两次GC JSON及日志共约104KiB，已移动到 `/Users/atomex/.Trash/surprising-ex-owner-allocation-20260921/`，可恢复；未移动或删除其他轮次产物。

## 2026-09-21 七阶段减法后的最终验证（seven-stage-cleanup-20260921）

### 采集前锁定计划

- 只验证当前干净 `master=9386fb61`，不检出旧版本；本轮前六阶段已依次删除死的命令结果 view、`LanePublication` 转发层、Owner 重复订单物化、热路径缓存/rolling audit hash、`CoreRuntimeStateView`/`AccountLaneView`，以及整套不再被生产入口使用的 immutable reducer 写状态机。第七阶段检查 matcher 结果包装；只有 JFR 证明其仍是主要分配或执行热点时才改动，不以破坏 pooled settlement event 生命周期、Lane 路由、严格 FIFO 或恢复正确性换取表面减法。
- 业务流程保持：命令入口完成准入和冻结，matcher 生成确定性不可变结果，实际涉及的 Account Lane 串行应用账户/持仓变化，Owner 按 FIFO 发布终态、响应并释放槽位；异常结果不得被当作成功退休。前六阶段后的 service reactor 已通过 913 tests、0 failure/0 error、1 skip；第七阶段若改生产代码，重新执行受影响定向测试及完整 service reactor。
- JMH 固定为 `ContinuousOwnerBenchmark.placeCancelWithoutTimers`、LINEAR_PERPETUAL、batch20、DISTINCT/BUSY_SPIN、1 matcher、4 Account Lane、owner window 256；每 invocation 为 512 Core messages、10,240 business operations。主轮与独立 `-prof gc` 各 1 fork/1 thread、3×3s warmup、3×3s measurement、512MiB/G1；JFR 独立轮同场景、最大 128MiB，不拿 profiler 分数替代主吞吐。
- JMH 通过条件：accepted/terminal business 与 Core 逐项相等，teardown 资金、冻结、订单终态和快照恢复通过，无异常；报告 invocation/s、换算 Core/business吞吐、`gc.alloc.rate.norm`、B/business、B/Core、GC 次数/时间。分配对照仅使用同配置最新当前分支历史值 1,905.68 B/business；JMH 不含真实网络/Archive，不能冒充实际集群容量。
- 真实单成员最终轮固定 LINEAR_PERPETUAL、MIXED batch20、128 symbols、4 Lane、1 matcher、全局/session in-flight=256、Owner/Matcher BUSY_SPIN，30s预热、60s稳定窗和独立排空；目标为 business吞吐≥300,000/s、Core吞吐≥30,000/s、各业务p99≤30ms、零错误/超时/unfinished、资金差0及快照恢复一致。单轮用于当前 master 最终检查，不声称统计稳定容量，也不把历史41.5万峰值当作本轮硬门槛。
- JFR 重点核对 matcher native/result包装、Owner/Lane分配、GC和线程执行热点。若 `CoreMatchingResult` 仅是 deferred matcher→Owner 生命周期所需且占比很小，第七阶段结论为“不可安全删除并保留”，记录证据而不新增协议；若有重复包装则只删除重复层并复测。
- 环境为本机 MacBookPro16,1、16逻辑CPU/16GiB、macOS 26.7，HotSpot Corretto JDK 27.0.0.33.1、Maven 3.9.16；采集前 swap 0、磁盘约252GiB可用。桌面进程存在且未绑核。任何 swap 增长、磁盘不足、JFR DataLoss/损坏、正确性失败使对应数据失败或无效。结束后停止本轮进程，只清理本轮生成物并记录状态。

### 七阶段结果与正确性

- 前六阶段生产减法分别由 `2c67377e` 与 `9386fb61` 完成并推送：删除死 `CoreOrderStateView` 结果分支、`LanePublication` 转发包装、Owner 重复订单物化、热路径缓存/rolling audit hash、`CoreRuntimeStateView`/`AccountLaneView`，以及23个不再被生产入口使用的 immutable reducer 写状态类；生产源码净删除约3,492行。保留产品线隔离、Lane状态所有权、严格FIFO、终态发布、响应及槽位释放顺序。
- service完整reactor为913 tests、0 failure/0 error、1 skip；其中旧reducer的123项资金/持仓/风险规则测试迁移到测试侧适配器后继续通过。六产品线、在途快照恢复、终态订单/冻结/资金校验均覆盖。最终构建时发现 `OwnerPublicationBenchmark` 仍调用已删除的 `LanePublication` API，已在 `542be6f5` 改为直接 `preparePublication`→`commitTerminalToOwner`，benchmarks reactor在JDK27下package成功并已推送。
- 第七阶段JFR确认普通/批量主路径已直接使用 pooled `MatcherSettlementEvent`：`CoreMatchingResult` 未进入主要分配类或站点。剩余 `exchange-core` 原生 `MatcherResult`/`MatcherEvent` 是撮合事实本体，占局部JMH采样4.09%/1.80%、真实节点7.19%/3.44%；它们必须跨 matcher→Lane 保持不可变，不能作为重复包装删除。deferred/control fallback `CoreMatchingResult` 保留，因为此前强制删除曾复现实际Lane路由扩展和池化事件提前回收竞态；本轮不新增window、barrier、状态副本或线程。

### JMH吞吐、分配与JFR

- 无profiler主轮为 **116.302 ± 21.299 invocation/s**，换算约 **59,546.6 Core messages/s、1,190,932.5 business ops/s**。三次测量115.616–117.650 invocation/s，accepted/terminal business均为10,741,760、Core均为537,088；teardown资金、冻结、订单终态、角色切换和快照恢复通过。
- 独立GC轮为108.208 invocation/s，`gc.alloc.rate.norm=18,747,069.931 B/invocation`，按10,240 business/512 Core换算为 **1,830.77 B/business、36,615.37 B/Core**；62次GC、总GC时间226ms。相对同配置最新历史1,905.68 B/business再下降 **74.91 B/business（3.93%）**；它仍含命令/batch集合构造、codec、响应解码和JMH框架，不是纯服务节点分配。
- 独立局部JFR 30s、DataLoss=0；分配前列为byte[] 43.92%、`OrderRuntime` 9.51%、原生`MatcherResult` 4.09%、`ReservationRuntime` 3.96%、`PlaceOrderCommand` 3.54%、查询/响应 `CoreOrderStateView` 3.09%、`ResolvedPlaceOrder` 2.80%、原生`MatcherEvent` 1.80%。byte[]主要位于测试端消息复制/解码；服务侧下一项明确成本是订单双所有权，不是matcher包装。
- 真实节点JFR 140s、DataLoss=0；稳定窗内 `ThreadAllocationStatistics` 的共同58.652s采样区间合计21,731,600,928B，按同窗业务完成量线性归一约 **1,219.57 B/business、370.52 MB/s**。其中四条Lane约612.34、Owner 279.13、Matcher 230.16、cluster ingress 97.93 B/business。该服务节点口径不含客户端，和JMH 1,830.77 B/business的差额方向一致。
- 真实节点采样分配：`OrderRuntime` 20.60%（`preparedOrder` 10.17%、`snapshot` 10.10%）、byte[] 10.51%、long[] 8.68%、原生`MatcherResult` 7.19%、`ReservationRuntime` 5.92%、`ResolvedPlaceOrder` 5.64%、原生`MatcherEvent` 3.44%。`preparedOrder`是Lane可变权威订单，`snapshot`是Owner稳定镜像；直接共享会让后续成交/撤单改写已提交视图。copy-on-write虽不必加锁，但会新增shared/mutable状态和首次修改分支，而且在本轮“下单后成交或撤单”的完整生命周期仍需第二份对象，只会延迟分配，因此明确排除、不实施。不能把20.60%全部当作可删除上限。彻底删除第二份只能取消Owner `publishedOrders`全量镜像，以紧凑primitive路由索引承担撤单/撮合字段，并在查询/快照建立Lane fence；这是独立架构改造，也不混入本轮局部减法。
- 真实节点完整录制共117次GC pause、总672ms，p50/p95/p99/max为5.57/6.30/18.8/19.2ms（包含启动和预热）；没有evacuation/promotion failure。分配按线程为Owner 23.11%、matcher 18.80%、四Lane各约12.46%、cluster service 7.96%。

### 真实单成员结果与结论

| 轮次 | 稳态秒 | business/s | Core/s | fills/s | 最大业务p99 | Lane有效执行均值 | 结果 |
|---|---:|---:|---:|---:|---:|---:|---|
| main1 | 60.022 | 282,757.011 | 27,045.051 | 67,303.284 | 47.939ms | 37.05% | 性能门槛失败，正确性通过 |
| main2 | 60.020 | 318,774.784 | 30,475.772 | 75,870左右 | 32.784ms | 43.77% | 吞吐通过，尾延迟失败，正确性通过 |
| JFR归因 | 60.003 | 303,810.548 | 29,050.141 | 72,316.036 | 29.573ms | 约41.1% | 仅归因，不作主吞吐验收 |

- 两个无profiler主轮均达到in-flight 256，最终offered=terminal、unfinished=0、backlog=0、`mixedCapacity=PASS`、`mixedVerify=PASS`、fundsDiff=0，population/HFT positions/reservations/loss及快照恢复全部通过；swap始终0，日志无业务ERROR/Exception。两轮平均 **300,765.898 business/s、28,760.412 Core/s**，但范围和p99未达到预锁门槛，整体吞吐/尾延迟结论为失败，不能声称恢复历史37万–41.5万容量。
- main1→main2在相同代码/配置下由28.3万恢复到31.9万，同时Lane有效执行从37.05%恢复到43.77%，证明当前未绑核桌面机器存在显著调度干扰；它不能解释为死reducer删除后的确定性回归。JFR归因轮在profiler开销下为30.4万business/s，批量撤单p99 29.573ms。当前用户已确认本机有资源占用，因此本轮重点结论限定为“分配下降且主要站点已定位”，吞吐容量待资源空闲后按同命令三轮复验。
- 主/GC/JFR JSON SHA-256分别为`c91b11826297207261aa090e67444fd1fdae2adc42164d5ebd3b416a74767319`、`560bd161abd25dfd52b688b1bbf8b46c63fcd95fde9506d21dfae8a78a9dc5f8`、局部JFR `c6eb3aeec2014defc8d21f8cfe2565a433e3143aa5d4e1fe4345c2e25a34341c`；真实main1/main2 summary为`124c12c2a5c5f11e9a464d42dfc4f9c9367c7d6bfc918319a0e34020e1206ba2`/`6fcd38de2c5e570dd70ae26f1a60d132de258733bfafe8c918dcb348df66116e`，node JFR为`6fa57109d7a05324a3025dcff1859334793224af882b005630a98eba8da54271`。
- 一次完整分析脚本试图逐条物化137万Archive `FileWrite`，临时目录增长到21GiB；确认与订单分配无关后已停止并删除该明确属于本轮的临时目录，保留原始JFR及有界class/site/thread汇总。所有本轮Java/Aeron/JMH进程已停止，swap仍为0；约7.9GiB的三轮Cluster/Archive、原始JFR、局部JMH和有界汇总已移动到`/Users/atomex/.Trash/surprising-ex-seven-stage-cleanup-20260921/`，可恢复，未移动或删除其他轮次产物。

## 2026-09-21 APFS 快照清理后单轮复测（apfs-cleanup-20260921）

### 采集前锁定计划

- 只验证当前干净 `master=14a4c82bed6d0c72778f78b0a60770a8b04d4006`，不检出旧版本、不修改业务代码；问题是清理本地 APFS/Time Machine 快照并重启后的本机环境能否恢复同口径真实单成员吞吐。单轮只作为当前环境检查，不据此声明稳定容量或代码性能变化。
- 场景沿用历史 37 万口径：单 Aeron Cluster 成员及真实网络/Archive，LINEAR_PERPETUAL、MIXED、batch20、128 symbols、seed25620、1385 users、4 Account Lane、1 matcher、全局/session in-flight 256，Owner/Matcher BUSY_SPIN；30s 预热、60s 稳态、排空单列，不启用 profiler。
- 探索门槛保持 business throughput≥300,000/s、Core throughput≥30,000/s、各业务请求 p99≤30ms；零业务错误/拒绝/超时，accepted=terminal business/Core、unfinished=0、期末 backlog=0、fundsDiff=0，population/HFT positions/reservations/loss及快照恢复全部通过。窗口满载后的背压与 coordinated-omission 缺口照实报告。
- 环境为 MacBookPro16,1、16逻辑CPU/16GiB、macOS 26.7，HotSpot Corretto JDK 27.0.0.33.1、Maven3.9.16、G1；采集前根卷可用546GiB、swap total/used=0、Time Machine未运行且根卷没有本地快照。桌面进程存在、未绑核，不外推生产或三节点容量。
- 使用当前 commit 在 10:08 构建的 benchmark jar，完整命令：`ASYNC_RUN_ID=apfs-cleanup-20260921-main1 ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 ASYNC_OWNER_WAIT_STRATEGY=BUSY_SPIN ASYNC_MATCHER_PIPELINE_WAIT_STRATEGY=BUSY_SPIN ASYNC_ENABLE_JFR=false ASYNC_SKIP_BUILD=true bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。运行中记录磁盘、swap和进程资源；任何swap增长、磁盘不足或正确性失败按标准判失败/无效。结束后停止进程，只清理本轮Cluster/Archive/log/report并追加结果。

### 执行结果与结论

- 稳定窗60.016524s完成22,734,766 business operations、2,172,206 Core messages和5,411,840 fills，持续吞吐为 **378,808.445 business/s、36,193.466 Core/s、90,172.500 fills/s**。排空12.670ms，另完成2,679 business/247 Core，不计入稳态吞吐。
- 各业务请求p99：PLACE_ORDER 12.632ms、CANCEL_ORDER 11.509ms、APPLY_MARK_PRICE 17.006ms、PLACE_ORDER_BATCH 15.351ms、CANCEL_ORDER_BATCH 26.181ms；最大业务p99低于30ms门槛。该延迟为客户端请求到响应，入口→accepted与accepted→terminal的独立分段本轮未采集。
- in-flight峰值256，window blocked 49.334s/60.017s（82.20%）；matcher/completion/context高水位213/208/256，四条Lane高水位47/47/75/54，Lane有效执行均值43.27%。这是持续背压下的已达吞吐，仍受窗口和coordinated omission限制，不等同无限open-loop容量。
- 最终offered=terminal：22,737,445 business、2,172,453 Core；unfinished=0、期末backlog=0，`mixedCapacity=PASS`、`mixedVerify=PASS`、fundsDiff=0，population/HFT positions/reservations/loss全部通过，日志无业务ERROR/Exception。
- 本轮通过全部探索门槛。相对七阶段减法后最近有效无profiler main2的318,774.784 business/s提高18.83%，相对当时两轮均值300,765.898提高25.95%；相对更早racefix两轮均值381,773.867低0.78%，说明当前本机已恢复到约37万同口径区间。由于只有一轮、commit和后台调度状态也不同，不能把提升全部因果归于删除APFS快照；可以确认本轮没有快照、swap或磁盘压力时，当前代码并不存在“只能约30万business/s”的现象。
- 采集前后swap total/used及swap-in/out始终为0、Pages throttled为0；根卷最低可用543GiB，Time Machine未运行且没有新本地快照。本轮未启用JFR或GC profiler，因此不新增分配、GC停顿或方法级瓶颈结论；沿用上一轮分配结论须明确不是本轮实测。
- summary/metrics SHA-256分别为`11d54fbf36b9bc077a7ac1fa646ebe46cdc4ac14bd61430592bc12fe17a14c7e`/`553ccf3b22ceb1c398c7743eaff5c6672a6e5c56d11984d4afb8aa836a6ec673`。全部Java/Aeron/JMH进程已退出；本轮生成物约3.3GiB，清理状态见下。
- 已逐项删除确认属于本轮的 `target/aeron-async-stages/apfs-cleanup-20260921-main1`（约3.3GiB，Archive约2.8GiB、Aeron约438MiB），不可从项目目录恢复；未删除或移动其他轮次产物。清理后根卷可用546GiB、swap仍为0，且无残留Java/Aeron/JMH进程。

## 2026-09-21 U本位永续独立规模场景（linear-perpetual-population-20260921）

### 采集前锁定计划

- 不修改、也不调用现有 `qualify-aeron-async-stages.sh` 主交易容量基线；新增 `qualify-linear-perpetual-scenarios.sh`，每个真实Cluster业务边界使用全新节点/数据目录，规模型Core场景每项使用独立JMH fork和状态模板。当前工作树还包含上一轮仅追加的性能记录；本轮代码影响限于benchmark模型、测试和新脚本，不改生产交易逻辑。
- 本轮先执行用户指定的三组人口形态：①深订单簿：单热点symbol、10个价格档、每档5,000单（共5万驻留订单，maker按4条账户lane分布），计时区只做同档下单→撤单并保持驻留规模不变；另单独执行256个taker、每个成交128个maker订单的同档深度成交；②稳定大持仓：10,000持仓账户，标记价100→99并完成全量风险扫描，要求爆仓任务为0；③大行情：扫描10,000个风险账户，并单独执行协议单批上限1,000账户的强平波次。各组不互相混合，也不与37万主吞吐比较。旧混合工作负载用5,000账户/约25万挂单预跑时在准备阶段触发当前64 MiB核心恢复快照容量保护，单独记录为恢复容量缺口，不混入热路径吞吐。
- HotSpot Corretto JDK27、G1、1 fork/1 thread；人口场景4GiB固定堆、1×2s预热、3×2s测量，1 matcher/4 Account Lane、BUSY_SPIN。报告JMH主分数、accepted/terminal/unfinished（可用时）、场景人口及正确性；小于实际生产人口时只报告已测规模，不外推。
- 正确性门槛：所有场景无异常；accepted=terminal、unfinished=0；深度成交资金/净持仓/终态订单一致；稳定持仓扫描无任何liquidation work且快照恢复hash一致；强平波次每个账户到达COMPLETED或INSURANCE_REQUIRED边界并通过快照恢复。性能没有预设SLA，本轮建立独立基线。
- 测试前JDK27定向新增门禁2项已通过；完整`LinearPerpetualBenchmarkSupportTest`和benchmark package需在采集前通过。采集前后核对swap/磁盘；任一OOM、swap增长或正确性错误使对应场景失败。完成后停止进程，提取结果并仅清理本轮生成物。

### 实施、偏差与正确性

- 新脚本提供 `orders|triggers|account|funding|liquidation|adl|book|positions|risk-storm|population|all` 独立入口；未改动原主吞吐脚本。规模场景均为独立fork，`book`、`positions`、`risk-storm`可分别运行；`SCENARIO_POPULATION_PROFILER=gc`用于单独分配轮。
- 首次旧混合订单簿预跑使用5,000账户、约25万挂单，在JMH Trial准备阶段由`SectionedCoreSnapshotWriter.encode`拒绝：`core snapshot exceeds maximum size`。它未进入测量，不计为性能结果；根因是当前`SectionedCoreSnapshotCodec.MAX_SNAPSHOT_BYTES=64 MiB`，说明该规模不能用“每次调用恢复完整快照”的基准方法，同时也是生产恢复容量需要单独验证的边界。
- 随后将订单簿场景改为简单的常驻状态：Trial一次建立10档×5,000单，计时区只执行同档GTC下单后撤单，结束校验仍为50,000单；没有增加生产线程、状态、锁或业务包装。风险扫描会改变标记价和清算状态，仍采用每次恢复模板的端到端口径，不能用其GC值代表纯扫描分配。
- JDK27 `LinearPerpetualBenchmarkSupportTest`最终24 tests、0 failure/error/skip；新增覆盖50,000单场景的小规模可逆性、10,000稳定持仓零爆仓和协议上限1,000户强平。benchmarks reactor `-am -DskipTests package`成功。所有有效JMH场景退出码0；可用业务计数均为accepted=terminal、unfinished=0，teardown的资金、净持仓、订单数量及恢复校验未失败。

### 无profiler结果

| 独立场景 | 固定规模 | JMH结果 | 业务换算/结论 |
|---|---:|---:|---:|
| 常驻密集订单簿下单→撤单 | 10档×5,000单 | 2,183.307 ± 788.582 pair/s | **4,366.613 business ops/s**；每pair严格2个终态业务动作，结束仍50,000挂单 |
| 单档深度成交 | 256 taker×128 maker | 19.382 ± 4.807 burst/s | **4,961.814 taker ops/s、635,112.238 fills/s**；fill不混作业务订单吞吐 |
| 稳定行情持仓扫描 | 10,000持仓，mark 100→99 | **71,642.981 ± 6,968.424 us/全量扫描** | 扫描完成且liquidation work=0 |
| 大行情风险发现 | 10,000风险账户 | **88,300.651 ± 8,175.277 us/全量扫描** | 完成全量风险扫描和恢复校验 |
| 大行情强平执行 | 1,000户/批 | 38.522 ± 24.665 batch/s | **38,521.851账户/s**；accepted=terminal=38,521.851/s、unfinished=0 |

- 最终订单簿、持仓和风险轮使用1×2s预热、5×3s测量、1 fork/1 thread、4GiB/G1、1 matcher/4 Lane/BUSY_SPIN；深度成交与前述订单簿在同一最终`book`轮。强平批次方差仍较大，当前只建立数量级基线，不设发布SLA，也不与真实Aeron主链路37.9万business/s横向比较。
- 这些局部JMH没有真实网络/Archive，也没有开放环分段直方图，因此不提供入口→accepted/terminal的p99；平均全量扫描时间不能冒充单账户p99。后续若用于容量验收，应把相同人口装入真实单成员场景，分别采集风险发现、强平执行和订单响应尾延迟。

### 分配、环境与清理

- 独立GC轮中，常驻5万订单簿为2,253.820 pair/s，`gc.alloc.rate=13.645 MB/s`、`gc.alloc.rate.norm=6,511.279 B/pair`，即约 **3,255.640 B/business op**；1次GC、15ms。该值含命令编码、响应及JMH harness，但已排除逐调用大快照恢复。
- 深度成交GC轮为339,902,258 B/burst，旧风险/强平GC轮为112–336 MB/invocation；它们的共同主因是每次invocation恢复大人口快照，不能作为生产每单/每持仓分配结论，故不据此提出热路径优化。
- 有效JSON SHA-256：常驻订单簿`3363ba7274a31d9fe4b7e8e64a59d5aa1d8b64657fb6d4165c0f6477becd81b5`、深档成交`ca6c5b2c26072248956ae1635286509ae62914f1930f33da74455ef5eaef19be`、稳定持仓`332d51870cb1e4c5c39eb4fe90118b7688e4db5893314c19ff20cbc74ffc7e57`、风险扫描`049afc69e3b22f92f0aa3c1732a1ec4ef5f8d30c8a40e39df70e335df26a9193`、强平批次`21fe7b2d711993804aed905cec0a5d73770286e1ad8591a5db4c211cc2d47d29`、常驻订单簿GC`6c1173a22ba369ad699a18128731bb9611e872c4658f4806b9eee4869ffad3fd`。
- 采集前后swap均为0，根卷可用546GiB，场景产物合计不足1MiB，无JMH/Aeron/Java残留进程。原始路径仅作历史定位；清理状态见下。
- 已删除确认属于本轮的8个`target/linear-perpetual-scenarios/*20260921*`失败/主轮/GC轮目录；结果不可从项目目录恢复，关键参数、分数、正确性和校验和已记录在上文，未删除其他轮次产物。清理后swap仍为0、根卷可用546GiB。

## 2026-09-21 大快照与200档订单簿验证

### 改动与验证口径

- 移除生产快照固定64 MiB上限：默认总上限改为1 GiB，可用`surprising.aeron.snapshot.max-bytes`配置，仍保留长度、分段和CRC校验；合法配置范围为64 MiB至Java单数组上限。集群各成员必须使用相同值，堆容量必须覆盖权威状态、分段编码缓冲和恢复缓冲。
- 生产Aeron写入改为直接发布Owner捕获的分段块，恢复改为fragment逐段写入有界`RecoveryBuffer`，不再先拼出额外的完整快照byte数组；matcher编码也直接复制内部有界缓冲到最终section，删除一次完整matcher快照复制。快照超时默认由30秒调整为300秒，可用`surprising.aeron.snapshot-timeout-seconds`覆盖。
- 深订单簿模型按生产人口重建：200个价格档、每档1,250单，共250,000个常驻maker订单，默认分布到5,000个账户，平均50单/账户。4账户版本使每账户约62,500个活动订单，准备阶段线程栈停在`MatchingCommandAdmission.preMatchingSelfTradeCancellations`，测到的是单账户自成交预检查的近二次扫描，不是200档订单簿容量，因此该轮作废且不计性能结果。

### 容量、正确性与探索结果

- 精确复现此前失败的人口配置：5,000活跃用户、128个listed/active symbols、每用户最多100个活动订单、约248,725个活动订单和5,004个持仓。初始快照80,304,108 B、最终快照80,317,054 B，均明确超过旧64 MiB边界；捕获2,291.901ms，最终恢复5,488.695ms，资金不变量和恢复校验通过，状态为PASS。该轮峰值已用堆约2,463,105,024 B、swap=0。
- 200档×1,250单、5,000账户的独立标准轮通过，使用1×2s预热、3×2s测量：`denseResidentBookPlaceCancel`为36,764.121 ± 12,736.228 pair/s，即73,528.242 terminal business ops/s；`deepFillBurst256`为15.675 ± 54.377 burst/s，即4,012.891 taker ops/s、513,650.016 fills/s。两项accepted=terminal、unfinished=0、拒绝/错误/超时均为0。方差仍较大，结果只作为该规模的局部Core基线，不与真实Aeron混合业务吞吐横比，也不提供请求p99。
- JDK27服务模块全量reactor测试914项：0 failure、0 error、1 skipped；快照定向测试44项全部通过；最终benchmark支持测试24项全部通过。没有运行六产品线真实集群的超64 MiB Archive重启恢复，因此本轮证明的是核心捕获/编码/恢复及Aeron适配层测试正确，生产部署前仍需在目标堆配置下完成真实成员snapshot→Archive→restart验收。
- 大快照probe日志SHA-256为`e681edb8a8187ccdf96ced3d74f233d04eefc1946339aa7b759a1306d925572f`；200档标准轮summary/常驻订单簿/深度成交结果分别为`0884af4a021845f5d5bd6f00a51ccb597178b8e1c42c81e5b0844b9916cf111e`/`a4a16529174497fcf0df72b08e3b21429dffc614da2e9f8bddc1e47e4a61d457`/`ae8d5c9f6aab5b856dcfb085c5b5adc2eace778be587df200ee8f10ee17581c4`。本轮不提供p99或分配结论；快照probe的堆占用不能解释为每单分配。
- 本轮JMH、probe及失败的4账户探索产物已移动到`/Users/atomex/.Trash/surprising-ex-large-snapshot-200-levels-20260921/`，可恢复；项目目录无本轮残留Java/Aeron/JMH进程，swap为0，根卷可用545 GiB。

## 2026-09-21 200档订单簿十分钟长稳

### 采集前锁定计划

- 问题是200个价格档、每档1,250单、共250,000个常驻maker订单分布到5,000账户后，Core在持续下单→撤单并维持订单簿规模的10分钟稳态窗口内是否崩溃、OOM、失去终态或出现持续内存增长。本轮是稳定性/JFR诊断，不设发布吞吐门槛，也不以局部JMH替代真实Aeron容量。
- 通过条件：完整600秒测量和teardown正常退出；accepted=terminal、unfinished=0、拒绝/错误/超时=0；结束仍为250,000个常驻订单且快照恢复/资金校验通过；无JVM fatal error、OOM、业务异常、GC失败、swap或磁盘压力。失败条件为任一正确性错误或进程异常退出；JFR损坏、明显同机干扰或系统资源异常则性能数据无效，但不掩盖正确性失败。
- 当前`master=f3e99f1f6f81e4013ef0be45fa2cc977dd317c32`，工作树仅含本节预先追加的验证记录；对照commit不适用。机器MacBookPro16,1、16逻辑CPU/16GiB、macOS26.7，HotSpot Corretto JDK27.0.0.33.1、Maven3.9.16、G1，4GiB固定堆，1 matcher/4 Account Lane、BUSY_SPIN，1 fork/1 JMH线程。开始前根卷可用545GiB、swap=0。
- 负载使用`LinearPerpetualCoreBenchmark.denseResidentBookPlaceCancel`，先初始化25万订单并完成30秒预热，再单次连续测量600秒；每次invocation严格执行一个GTC下单和对应撤单，accepted/terminal按两个business operations计数。JMH超时900秒，进程结束后排空和teardown检查，不混入深度成交、风险、强平或真实网络/Archive场景。
- 启用JFR profile录制，最大512MiB，并记录GC日志；运行中约每30秒核对进程RSS/CPU、系统swap和磁盘。JFR用于崩溃、分配、GC和线程归因，因此本轮吞吐只作带采样开销的长稳观测，不与无profiler历史分数横比。原始结果使用统一run ID `dense-book-10m-20260921`，结束后记录命令、时间、校验和、JFR摘要和清理状态。

### 首次执行失败与场景修正

- 首次执行在约81秒进程时长、正式测量开始后失败：第5,121,025个已应用命令附近，正常`PLACE_ORDER`被Core以`STALE_MARK_PRICE`拒绝，JMH按`-foe true`退出。进程没有OOM或fatal error；退出前8次G1 young GC，末次2845MiB→395MiB、7.535ms，退出已用堆约694MiB，swap=0。该轮按正确性门槛记为失败，不计吞吐。
- 原因是场景模型只在初始化写入一次mark price，而Core要求mark age不超过5,000逻辑毫秒；benchmark每1,024条命令推进1逻辑毫秒，因此约512万命令后必然过期。生产稳定行情也有同价、递增sequence的mark heartbeat。基准已仅在`DenseResidentBook`中按逻辑时间低频调用既有`refreshMarkPricesIfDue`，不修改生产代码、资金或订单逻辑；定向24项测试通过并重新打包。随后使用独立run ID `dense-book-10m-rerun-20260921`从头执行相同30秒预热和600秒测量。
- 首次失败JFR时长81秒、无DataLoss，JFR/GC日志SHA-256分别为`13d6660e614224723290b85e4606ef612e1a2c6da975a5d05792796d8bb5d876`/`482c1cdf8f853f362ef7c6b5211f997383396fcb3eced394ce51686bbb4083c6`；原始路径仅作本轮结束前定位。
- 第一次修正原计划仅按逻辑时间临近过期时刷新；用户要求贴近生产持续输入后，该轮在正式结果产生前被主动终止，不计结果。最终场景每1秒真实墙钟提交一次同价、递增sequence和新generated-at的`APPLY_MARK_PRICE`心跳，订单循环每4,096个pair检查一次截止时间，最大调度误差只受该小段处理时间影响；不另起线程，避免给Harness引入非生产状态竞争。最终run ID改为`dense-book-10m-continuous-mark-20260921`。

### 最终十分钟结果

- 最终命令为JDK27执行`product-core-benchmarks.jar '^com.surprising.aeron.service.orchestration.LinearPerpetualCoreBenchmark.denseResidentBookPlaceCancel$' -p accountLanes=4 -p priceLevels=200 -p ordersPerLevel=1250 -wi 1 -w 30s -i 1 -r 600s -f 1 -t 1 -foe true -to 900s`，fork附加4GiB固定堆、G1、AlwaysPreTouch、1 matcher/4 Lane全BUSY_SPIN、JFR profile/512MiB上限及GC日志。总进程时长643.948秒，JMH报告完整600秒测量并正常退出。
- 持续结果为**34,998.174 place+cancel pair/s**，即**69,996.347 terminal business ops/s**，约2,099.9万pair、4,199.8万业务终态；accepted=terminal，unfinished business/Core、rejected、error、timeout均为0。teardown未报错，说明结束仍为250,000个常驻订单，资金和快照恢复校验通过。稳定同价mark heartbeat持续输入后，没有再次出现`STALE_MARK_PRICE`。
- 结论为该局部Core长稳场景**通过**：无JVM crash、OOM、fatal error、业务异常、GC/promotion/evacuation failure、进程残留或swap。它证明200档/25万订单/5,000账户在持续下撤单和生产型mark心跳下可连续运行10分钟；由于不是实际Aeron网络/Archive、只有单fork且带JFR，不能据此声明真实集群吞吐、请求p99或多小时无泄漏。

### JFR、GC与资源

- JFR时长643秒、8.3MiB、DataLoss=0，包含268,756个execution samples、108,842个allocation samples、639个RSS样本和48次young GC。48次GC含初始化，合计暂停约467ms；启动建簿前三次较长暂停为22.018/65.926/34.318ms。排除前60秒后42次稳态暂停合计311.134ms，平均7.408ms，p50/p95/p99/max为7.338/8.335/8.616/8.616ms，无old/full GC。
- 稳态GC后堆长期为391–392MiB，old regions保持146→146；退出时1,268,732KiB包含距离末次GC后新产生的812MiB Eden，不能当作live set。JFR第60秒至结束的579个RSS样本从4,579,790,848B到4,582,199,296B，仅增加2,408,448B（约2.3MiB），范围4,579,790,848–4,582,199,296B；固定4GiB堆已预触页。swap始终0，根卷可用545GiB。
- JFR采样分配前列为byte[] 17.37%、`OrderRuntime` 6.35%、long[] 4.75%、`CoreResponse` 4.29%、`CoreMatchingResult` 4.04%、`ResolvedPlaceOrder` 3.44%、`CoreMessageHeader` 3.17%、int[] 3.16%、Lane commit lambda 2.95%、`ReservationRuntime` 2.89%。本轮未启用JMH GC profiler或NMT，采样占比不能换算成精确bytes/business或native分类，因此不补估计值。
- CPU采样主要是独占线程busy spin：`SettlementLaneWorker.run` 61.66%、`MatcherCommandPipeline.run` 16.50%；有效业务站点中`dispatchPlaceAdmission`为6.45%。这是局部单线程驱动场景，空闲Lane/matcher的busy-spin样本不能解释为业务计算瓶颈。
- 最终result/JFR/GC日志SHA-256分别为`cf1c1c4e9c759cdb4f11b72fb04df612a2cb3e81b50e3123c71ea0647bf04749`/`d63ff8c6f3ca4eada66754671988bedd8bbbc7ead02d4a515a8ab2f106f62d59`/`183a30c8fe5f1707337087a53423ecb8661e017420e619b8276b4b2b4b61eef2`。原始路径仅作清理前定位：`target/linear-perpetual-scenarios/dense-book-10m-continuous-mark-20260921`。
- 首次失败、主动终止和最终有效轮的三个目录已移动到`/Users/atomex/.Trash/surprising-ex-dense-book-10m-20260921/`，可恢复；项目目录无本轮产物和Java/JMH残留进程，清理后swap仍为0、根卷可用545GiB。

## 2026-09-21 200档订单簿异步生产调用长稳

### 采集前锁定计划

- 前一轮虽然有25万常驻订单和5,000账户，但计时流量固定使用单账户并逐命令等待终态，只能证明串行稳定性，不能定位满载Core瓶颈。本轮把局部Core场景改为5000账户轮转、最多256个matching命令持续在途；每账户最多一个有依赖的命令在途，下单终态后把该订单撤单放入执行窗口，但发压循环不等待撤单完成，持续用其他账户补满窗口。每次JMH invocation异步完成16,384个下单/撤单动作并在边界排空，保证accepted=terminal且不把未完成量虚报为吞吐。
- mark price仍每1秒墙钟产生同价、递增sequence/generated-at的心跳。局部Harness没有生产Owner的外层FIFO命令队列，因此心跳到期后在当前最多16,384操作的小窗口退休边界应用，避免越过Account Lane matching cursor；短轮曾直接跨在途matching执行并按正确性失败保留，不修改生产顺序约束。
- 固定200档×1,250单、250,000常驻订单、5,000账户、4 Account Lane、1 matcher、全BUSY_SPIN、4GiB G1、JDK27；30秒预热、600秒单次测量、1 fork/1 JMH线程、JFR profile最大512MiB和GC日志。开始前定向24项测试和重新打包通过；短标准轮3×2秒为57,094.447 async trading ops/s、accepted=terminal约57,095.400/s、拒绝/错误/超时/unfinished为0，teardown和快照恢复通过，只作功能预检。
- 通过门槛为完整600秒和teardown正常退出、窗口达到256且无producer starvation、accepted=terminal、unfinished/拒绝/错误/超时为0、结束恢复为25万订单并通过快照恢复，无OOM/fatal/GC failure/swap。吞吐不预设门槛；JFR按有效工作线程、Owner/Harness退休、matcher、Lane、分配和GC定位限制点。局部Harness没有真实Aeron网络/Archive和外部API入口，结果不外推真实集群p99。

### 实现与业务顺序

- `DenseResidentBook`保留建簿时创建的5,000个账户并以primitive环形队列轮转，不再固定使用第一个账户。发压线程持续把窗口补到256；某账户下单完成后才为同一订单排入撤单，撤单完成后再排下一笔下单，但该依赖只暂停该账户，其他4,999个账户继续提交，因此没有逐命令或全局等待。
- 每次JMH invocation提交并退休16,384个业务动作；一次最多批量退休64个连续ready的FIFO头，随后立即补窗。窗口边界完全排空用于JMH计数隔离，未把未完成请求计入吞吐。mark heartbeat仍按1秒墙钟到期，在局部Harness已排空的FIFO边界异步提交；生产Owner拥有统一外层FIFO，不需要这一Harness限制。
- 新增窗口采样、满窗采样、补窗操作和producer-starvation计数；teardown清理动态订单后核对250,000个常驻订单，并以business-state hash验证快照恢复。没有新增生产线程、业务状态副本、barrier或通用抽象，改动仅限独立基准、测试和脚本入口。

### 测试与结果

- JDK为Corretto HotSpot 27.0.0.33.1，Maven 3.9.16，macOS 26.7 x86_64、16 CPU/16GiB；当前master基线commit为`b042e7f5`，本轮基准改动尚未提交。执行前后根卷可用544GiB，运行中swap为0。定向`LinearPerpetualBenchmarkSupportTest`共24项通过，benchmark模块package通过；短轮1×2秒预热、3×2秒测量为57,094.447 ops/s且恢复校验通过。
- 正式命令为`denseResidentBookAsyncPlaceCancel -p accountLanes=4 -p priceLevels=200 -p ordersPerLevel=1250 -wi 1 -w 30s -i 1 -r 600s -f 1 -t 1 -foe true -to 900s`，JVM为4GiB G1/AlwaysPreTouch、1 matcher、4 Account Lane、全部BUSY_SPIN，另开512MiB上限JFR和GC日志。完整运行耗时11分钟并正常退出。
- 正式持续吞吐为**53,408.860个异步下单/撤单业务动作每秒**；同价mark heartbeat使accepted/terminal Core消息为53,409.678/s。accepted=terminal，unfinished business/Core、拒绝、错误和超时均为0，teardown恢复250,000个常驻订单并通过快照恢复。
- 满窗采样52,577.043/s、总窗口采样53,408.280/s，即**98.44%采样点保持256在途**；补窗53,405.600/s，producer starvation为0，最大backlog达到256。因此53.4k不是发压端未供给，而是该局部Core链路在严格FIFO和256上限下的持续退休能力。
- 本轮结论为**部分验证**：异步5000账户分布、完成性、10分钟稳定性及局部Core饱和有效；未采集入口→accepted/terminal直方图，也未经过真实Aeron网络、Archive和外部API入口，不能报告请求p99或推导生产集群容量。场景是深订单簿上的不成交下单/撤单，成交、持仓、强平等独立场景不与本结果混算。

### 明确卡点与分配

- JFR共659秒、9.0MiB、DataLoss=0、280,222个execution samples和84,136个allocation samples。4个`SettlementLaneWorker.run`合计61.38%及单matcher的`MatcherCommandPipeline.run` 16.42%主要包含独占CPU busy-spin；4个Lane的有效`peekMatcherSettlement`仅3.71%，没有证据表明Account Lane计算量限制吞吐。
- 发压/Owner线程同样占满一个核，最大有效站点是`TradingRuntimeState.dispatchPlaceAdmission` 4.39%总样本（约一个核的26%），其后是`Long2ObjectHashMap.getMapped` 1.84%、`ResponseArena.acquire` 1.21%、`PendingMatchingRing.firstDeferred` 0.61%、`readyLaneMask` 0.56%和连续完成/FIFO探测。结合98.44%满窗和producer starvation=0，已定位的主要限制在**单Owner准入与有序退休热路径**，不是5000账户生成器或4个Account Lane。matcher独占核有16.42%自循环样本，但当前采样不能把其busy-spin与有效撮合成本拆开，不能单独判定matcher为第一瓶颈。
- `ResponseArena.acquire`每次从槽0开始在线性512槽数组上CAS查找；这是明确可消除的Owner扫描成本。下一项最小验证应只把它改成Owner维护的空闲槽游标/primitive free ring，并保持transport/ledger双引用生命周期不变，再看该站点、吞吐和正确性是否同步改善。随后才验证place admission的事件交接和FIFO头重复探测，不应增加线程、barrier或状态副本。
- 正式JFR分配占比前列为`byte[]` 19.59%、`OrderRuntime` 7.10%、`long[]` 6.73%、`CoreResponse` 4.50%、`CoreMatchingResult` 4.13%、native `MatcherResult` 3.84%、`ResolvedPlaceOrder` 3.59%、`CoreMessageHeader` 3.13%和`ReservationRuntime` 3.05%。独立`-prof gc`轮前两个正常测量迭代为2,494.853和2,444.201 B/op；第三迭代包含trial teardown快照物化，7,852.842 B/op不代表热路径。正式GC日志按13个稳态GC区间、2MiB region估算为125.66MiB/s、**2,467 B/business op**，与前两个profiler样本一致。
- 正式轮37次GC加退出前一次metadata-threshold GC，总暂停574ms；暂停p50/p95/p99/max为12.1/48.6/64.9/64.9ms。稳态young GC后堆约427–430MiB，Old region在后半段保持142→142，无promotion/evacuation failure。正式窗口607个RSS样本为4.266–4.295GiB，首末增加21.2MiB；固定4GiB堆已预触页，10分钟内未见持续Old增长。DirectBufferStatistics均为0；本轮未启用NMT，native分类不可用。
- 正式JFR/result/GC日志SHA-256分别为`1ae1e34a0c0627d83f7606999866fd018b50e8ff329c5ee58587aeb0b4fd83b2`、`3eebc2df6c016bd7d4317dc7cddc0c941f27ca9df6bb447b5c64e111445a4382`、`61a6f38a9e8a74e634f58cee39e59ee63777ecac67d41f1084be2299eb926e35`；独立GC-profiler result为`2d671f34231e764518b921a8959bf8a7acf13fed0de2cd22592e57bcfaebf027`。原始路径仅作清理前定位：`target/linear-perpetual-scenarios/dense-book-async-10m-20260921`和`dense-book-async-gc-20260921`。
- 正式轮、GC-profiler轮及两次短轮目录均已移动到`/Users/atomex/.Trash/surprising-ex-dense-book-async-20260921/`，可恢复；项目目录无本轮artifact和Java/JMH残留进程，清理后根卷可用544GiB。

## 2026-09-21 交易热路径四阶段减法

### 实施前锁定计划

- 目标按依赖顺序完成四项减法：先消除place admission重复深度扫描、无效参数和重复字段；再把`ResolvedPlaceOrder`的不可变字段写入已有池化事件；随后让matcher普通下单结果直接写入池化`MatcherSettlementEvent`，删除中间`CoreMatchingResult`包装；最后把订单和预留状态统一为Account Lane权威来源，移除Owner侧`publishedCopy`镜像。不得增加线程、锁、barrier、业务状态副本或fallback/legacy路径。
- 业务顺序保持为：Owner解析并校验下单输入 → Account Lane预留资金并发布准入回执 → matcher按确定性顺序撮合 → 对应Lane逐条应用不可变成交结果并发布终态 → Owner按FIFO退休和响应。资金预留必须先于matcher消费；同一账户仍只由所属Lane写入；池化对象只能在Lane、matcher和Owner都完成消费后复用。
- 当前基线为`master=c4aa8f83`。性能对照沿用修复Harness租约后的异步场景：前两个有效GC-profiler样本65,815.575/66,130.630 ops/s和2,209.827/2,269.732 B/op；有效JFR中`dispatchPlaceAdmission`占4.80%，主要分配对象包含`OrderRuntime`、`CoreMatchingResult/MatcherResult`、`ResolvedPlaceOrder`和`ReservationRuntime`。
- 每阶段先用CodeGraph影响面确定测试范围；当前会话未暴露`codegraph_*`工具时，使用源码符号引用、Maven模块依赖和事件边界作保守替代。每阶段执行JDK 27版本检查、受影响service定向测试和benchmark支持测试，正确性通过后独立commit并push；四阶段全部完成后才跑相同异步200档/5000账户/256在途GC-profiler与JFR压测。
- 通过条件：资金、冻结、持仓、订单终态、matcher顺序、快照恢复和accepted=terminal均不变；测试无失败；性能轮无拒绝、错误、超时和unfinished。若某个包装承载必要跨线程生命周期或恢复语义，允许保留其语义但必须把对象分配并入现有池化槽位，不能用新抽象替代旧抽象。

### 四阶段完成结果

- 四项生产改造已经按依赖顺序完成并推送：`b23542fa`合并place admission容量预检并删除无效matcher shard/重复symbol字段；`9550ebf8`让池化`PlaceAdmissionEvent`直接承载解析后的下单输入；`9e4207cb`强制异步普通PLACE使用池化`MatcherSettlementEvent`，`fe56dc77`同时删除自成交预检中的重复order resolve；`6b4dd525`让Owner发布索引直接引用Account Lane权威`OrderRuntime`/`ReservationRuntime`并删除两个`publishedCopy`。没有增加线程、锁、barrier、业务状态副本或兼容fallback。
- 最终JFR发现压测器虽维持256个调用在途，却仍通过同步`state.apply()`进入Core，因而错误采到了`CoreMatchingResult`。`00b1138a`在25万订单同步初始化完成后启用已有cluster async ingress；`57452662`使用命令槽已有的`clusterIndependent`事实，在命令跨Owner推进轮次、重新获得matcher shard提交头时恢复异步提交作用域。生产Owner原本整轮处于该作用域，正常路径不会新增切换；只修正延迟恢复命令和本地Harness的语义。
- JDK27验证通过：`ClusterCommandPipelineTest` 250项、0 failure/error、1 skip；`LinearPerpetualBenchmarkSupportTest` 24项、0 failure/error/skip；benchmark及其reactor依赖package/install成功。最初直接执行子模块曾因本地旧protocol/realtime依赖产物编译失败，改由当前reactor源码安装依赖后通过，不属于代码回归。
- 最终有效JFR为1×20秒预热、1×30秒测量，200档×1,250单、5,000账户、4 Lane、1 matcher、256在途、BUSY_SPIN；正式窗口为**46,411.424 business ops/s**，accepted=terminal 46,412.313/s，拒绝、错误、超时和unfinished均为0，满窗占窗口采样98.45%，producer starvation为0。该轮带JFR，只用于归因，不作为无profiler容量结论。
- JFR中47个`CoreMatchingResult`样本全部发生在15:35:51–15:35:58的25万订单同步初始化阶段；之后20秒预热和30秒异步测量窗口内为0，且`CoreMatchingResult.fromNative`已从allocation-by-site前列消失。这证明普通异步PLACE/CANCEL不再生成service结果包装，而是直接写既有池化settlement event。native `MatcherResult`仍占3.21%采样分配，它是exchange-core生成的撮合事实本体，不是被删除的service二次包装。
- 最终独立GC-profiler前三个测量样本为51,925.717/51,639.750/54,475.574 ops/s；前两个正常热路径样本为2,516.212/2,432.187 B/op，均值**2,474.199 B/business**。第三个7,654.263 B/op包含trial teardown的状态物化，按既定口径排除。该值高于此前2,181.733 B/op，是因为此前场景实际测的是同步Core入口；修正后增加了真实cluster window/decode/异步交接，二者口径不同，不能解释为四项生产减法导致分配回升。
- 有效JFR result/JFR SHA-256分别为`5bb96ab6a0917710acbab80c65093ee28cd65c195318c1f7b669e544b4c2cc99`/`c6bee6d5cdbddcd9ef25f3e0d8271b20c0f21f138d9ed0c31617ff7eba0dc07`；最终GC result为`a03dc33acef4c13f16ba1e0487745e7ecc58e2f2614bf8a44cb138ef27eb6454`。第一次JFR因控制进程与fork共用路径被覆盖，明确判无效；它及修正前的同步入口轮不用于最终分配归因。采集结束swap仍为0，磁盘约543GiB可用；全部9个`four-stage-*`目录约13MiB已移动到`/Users/atomex/.Trash/surprising-ex-four-stage-20260921/`，可恢复，未移动其他轮次产物。

## 2026-09-21 ResponseArena伪热点校正与交易热路径对象审计

### 采集前锁定计划

- 上轮JFR把`ResponseArena.acquire`列为Owner有效热点和byte[]主要分配站点；重新核对生产`ClusterServiceEgress/OwnerResponsePublisher`与局部Harness后发现，生产出口会调用`releaseResponse`，而Harness校验终态响应后直接丢弃对象，未释放transport lease。512个arena槽因此永久耗尽，后续命令全部走精确数组fallback；这是压测器生命周期缺失，不是已证实的生产arena缺陷。
- 本轮只补齐Harness作为终端transport consumer的释放动作，不改生产`ResponseArena`、交易业务状态、线程、barrier或队列。入口仍为异步5000账户、256在途、200档×1,250单、250,000常驻订单；终态校验完成后释放响应槽，ledger引用仍独立保留到淘汰，业务顺序和恢复校验不变。
- 先执行JDK27定向24项测试与模块打包；性能先跑独立`-prof gc`短轮验证fallback是否消失，再以30秒预热、至少60秒JFR诊断有效CPU和分配站点。通过条件为accepted=terminal、无拒绝/错误/超时/unfinished、满窗且producer starvation为0、teardown与快照恢复通过；若`ResponseArena.acquire`仍有稳态fallback分配才考虑修改arena领取算法。
- 同轮静态审计`OrderRuntime`、`CoreResponse`、`CoreMatchingResult/MatcherResult`、`ResolvedPlaceOrder`、`ReservationRuntime`和codec数组：按权威长期状态、跨线程不可变交接、协议输出三类标注必要边界，并找出同一事实重复封装/复制。`dispatchPlaceAdmission`在校正Harness后重新归因；不因类名含Runtime/Result就删除资金、FIFO或跨线程可见性边界。

### 修复、测试与性能结果

- Harness现在在即时终态、单条matching排空和批量ready退休三条路径中，完成业务校验及completion callback后调用`TradingCoreRuntime.releaseResponse`。这与生产egress编码完成后的transport lease释放一致；ledger lease仍由`CommandResultLedger`独立持有到替换/淘汰。没有修改生产`ResponseArena`，也没有新增free ring、游标、锁或线程。
- 首次只指定benchmark模块的定向命令因本地依赖jar落后于源码而编译失败；改用`-am`从当前源码重建依赖后，`LinearPerpetualBenchmarkSupportTest` 24项全部通过，随后benchmark及直接依赖模块JDK27 package通过。
- 相同`-prof gc`配置（1×20秒预热、3×10秒测量）的前两个正常测量样本从修复前55,575.839/51,525.073提高到**65,815.575/66,130.630 ops/s**，均值约提升23.2%。第三样本仍包含trial teardown的快照物化，不能用于热路径分配。accepted=terminal，满窗约98.44%，producer starvation、拒绝、错误、超时和unfinished均为0。
- 前两个有效GC-profiler样本为**2,209.827/2,269.732 B/op**，相对修复前2,494.853/2,444.201 B/op均值下降约9.3%。修复后有效JFR中`ResponseArena.acquire`从上轮1.21%总CPU样本降至0.17%，只在启动预热阶段为未初始化的64KiB槽分配10个采样对象；稳态fallback所在源码行的allocation sample为0，`ResponseArena.acquire`不再出现在allocation-by-site前25名。
- 有效JFR使用独立fork路径，30秒预热、120秒测量，JFR时长180秒、4.0MiB、DataLoss=0，业务分数49,807.770 ops/s。该分数受JFR及当时同机负载影响，只用于归因，不替代无JFR/GC-profiler吞吐。第一次误把控制进程与fork写入同一JFR路径，文件被控制进程覆盖，已判无效且不用于任何结论。

### 对象与`dispatchPlaceAdmission`审计结论

- `OrderRuntime`不是纯包装：它是Account Lane内订单的权威可变状态，承载剩余量、累计手续费、状态、revision和提交位置；整体删除会丢失资金/订单终态。但每个新订单目前还通过`publishedCopy`创建一份Owner mirror，JFR站点占3.40%，另有`preparedOrder` 2.92%。热路径的可消除部分是**Owner镜像和准备副本**，不是Lane权威订单本体。彻底方案是Owner只保留路由/顺序索引，查询与快照通过Lane fence读取权威状态；这是状态所有权改造，不能混在局部对象优化中。
- `ReservationRuntime`同样是冻结/保证金消耗与释放的权威Lane状态，不能直接删除；其`publishedCopy`占1.43%，表明可消除的是Owner reservation mirror。若Owner不再直接读取完整reservation对象，可只接收终态资金增量和必要索引，冷路径快照在Lane fence物化。
- `ResolvedPlaceOrder`占3.36%，它把外部下单意图与当时的instrument、mark/index/forward price、手续费及冻结资产绑定成确定性输入，语义必须保留；但没有必要逐单创建record。可把这些已解析primitive/reference直接写进现有`CommandSlot/PlaceAdmissionEvent`固定槽，由Lane和matcher在槽退休前只读，删除record及其二次字段转发。
- `MatcherResult` 3.60%与`CoreMatchingResult` 3.86%是当前最明确的双层结果：native结果创建事件列表后，service wrapper再次保存同一native对象、events和marketData，并附加命令证据。普通单命令路径应让matcher把primitive结果直接写入现有`MatcherSettlementEvent/CommandSlot`，由该池化事件作为唯一不可变跨线程事实；native sequence/shard保留，command ID/order ID从绑定的slot校验，不再复制进第二个result。批量/跨分片聚合仍单独处理，不能用它迫使普通路径保留包装。
- `CoreResponse`占4.20%，是egress协议边界，最终对外响应仍需要；内部`MATCHING_PENDING`、终态结果和ledger不必各自物化同构对象。可让Owner命令槽保存status/resultCode/sequence和arena slice，egress最后一步才构造或直接编码协议响应；duplicate/query边界再按需构造。它应后于matcher result去包装实施。
- codec的`byte[]`总占13.35%，但本地Harness同时承担调用端encode和Core入口，`CoreMessageHeader.command`、`encodePlaceOrder`、`Harness.command/submitCommand`不是纯生产Core成本。真实Aeron异步入口仍必须跨回调保存payload，因为订阅buffer回调后失效；正确优化是复制到256个window slot预分配buffer并就地decode，而不是让异步Core引用Aeron临时buffer。对外`CoreResponse.data()`的防御性copy只留在公开读取边界。
- 校正后`TradingRuntimeState.dispatchPlaceAdmission`仍为最大Owner有效CPU站点，占总execution samples 4.80%（约单个Owner核的29%），所以它是真热点，但不是因为`PlaceAdmissionEvent`本身多余。该事件池化后承担Owner→Account Lane的唯一资金准入交接，以及`completed/matcherConsumed`两个必要可见性条件；撮合在资金冻结成功前不能生效。
- 其中存在可删除成本：`ensurePlaceAdmissionDispatchCapacity`带有未使用的`matcherShard`参数；一次下单会在capacity preflight、high-water统计和`submit`中重复读取/扫描Lane depth；`symbolId`同时存在于`ResolvedPlaceOrder`和event；完成后Owner又把Lane状态复制成mirror。推荐顺序是：①删除重复depth读取及无用参数；②把resolved字段并入池化slot/event；③普通matcher结果直接写池化settlement event；④最后以Lane单一权威状态消除order/reservation mirror。前3项是局部热路径减法，第4项是独立架构迁移。

### 证据与清理

- 有效JFR/result/GC日志SHA-256分别为`ce959219f1444122ac8a99f21431f83ffe6f349b70283eaa1b12c76aeeee1ea9`、`4e54e2c470d6b6e831da2de54a8ab3a654d60250dde6a73cba427fc87fb23c86`、`32688d0d80b41955e2603d8dec8a83f69657b85b85b3df0ea61ebf84861f2c39`；GC-profiler result为`fc263c3e736780f13c26df51ba790dfd87fb0e4af4551968d257857182529ef2`。原始路径仅作清理前定位：`response-release-jfr2-20260921`、`response-release-gc-20260921`；被覆盖的无效轮为`response-release-jfr-20260921`。
- 三个目录均已移动到`/Users/atomex/.Trash/surprising-ex-response-release-20260921/`，可恢复；项目目录无本轮artifact和Java/JMH残留进程，清理后根卷可用544GiB。
