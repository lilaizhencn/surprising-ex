# 性能验证记录

> 本文件按时间顺序追加。当前仅验证 `master`，不与历史版本做性能对照。

## 2026-09-15 11:20 修复后 focused MATCH_STREAM 单节点验证（采集前锁定）

- 被测 commit：工作区修复 `MatcherCommandPipeline` 直达结算槽位生命周期；采集前以当前 `master` 工作树为准
- 对照 commit：不适用（仅验证当前 master）
- JDK/JVM：HotSpot JDK 25（Oracle GraalVM 25.0.1，`Java HotSpot(TM) 64-Bit Server VM`）
- 机器：本机 macOS x86_64，Intel Core i9-9880H，16 logical CPU；运行前检查可用磁盘空间
- 模式：Aeron Cluster 单节点开发模式；一个真实成员，保留网络、Archive、交易 Core；不启动三节点
- 产品线：`LINEAR_PERPETUAL`
- 业务场景：`MATCH_STREAM`，独立普通订单，GTC 买/卖各半；做市账号与模拟用户 API 均运行
- 负载：1 个 symbol，256 users（128 buy/sell pairs），4 workers，4 connections，256 global/session in-flight；单 matcher，4 Account Lane
- 阶段：预热 30s，稳定测量 60s，测量前排空预热请求，测量后排空并执行撤单/资金/订单簿核对
- 通过条件：`acceptedBusinessOperations == terminalBusinessOperations`；Core accepted/terminal messages 相等；`unfinished* == 0`；拒绝、错误、超时为 0；`fundsDiff == 0`；`bookLevels == 0`
- 性能参考阈值：`terminal business ops/s >= 300000`，入口到 terminal `p99 <= 5ms`；仅在全部正确性条件满足时才形成验收结论
- 指标口径：API 报 `requests/s`；Core 报 `terminal business ops/s`、`terminal Core messages/s`、`fills/s`；分别记录 accepted→terminal 延迟 p50/p90/p95/p99/p99.9/max、backlog、拒绝/错误/超时
- JFR：Core 与 client 各采样；Core 使用明确 recording 配置并保存原始 `.jfr`，同时输出 `jfr summary`；记录 CPU、分配、GC、native/direct、线程/锁、safepoint、JIT、I/O、异常和 DataLoss
- 正确性：测量后执行用户/做市账号余额与持仓、资金守恒、订单终态、空订单簿、快照恢复检查
- 数据有效性：Core 不得 fatal；所有 accepted 命令必须 terminal；backlog、unfinished、队列深度测量结束归零；无 swap/throttling/JFR DataLoss；原始产物在本轮记录后清理

### 修复说明（采集前）

本轮修复 `MatcherCommandPipeline`：直达结算槽位增加独立于可回收 `MatcherSettlementEvent` 的稳定类型标记。此前同一 matcher 分片前序普通结果阻塞时，后续直达槽位可能已完成但尚未消费；事件被 owner 回收后，该槽位会被误判为普通撮合结果，继续查询已回收的 `LaneCommandContext`，触发 `unknown lane command context`。本轮验证用于确认该 fatal 路径消失。

### 执行结果

待采集。

## 2026-09-15 11:35 修复后 128 币对 mixed 单节点验证（采集前锁定）

- 被测 commit：当前 `master` 工作树；包含 `MatcherCommandPipeline` 直达结算槽位生命周期修复及诊断信息
- 对照 commit：不适用（仅验证当前 master）
- JDK/JVM：HotSpot JDK 25（Oracle GraalVM 25.0.1，`Java HotSpot(TM) 64-Bit Server VM`）
- 机器：本机 macOS x86_64，Intel Core i9-9880H，16 logical CPU；采集前检查磁盘空间
- 模式：Aeron Cluster 单节点开发模式；一个真实成员，保留网络、Archive、交易 Core；不启动三节点
- 产品线：`LINEAR_PERPETUAL`；单 matcher；4 Account Lane；做市/模拟用户链路运行
- 业务场景：`ClusterMixedCapacityMain` 原 mixed 连续交易方案，128 个币对，1000 个用户；普通单、批量单、撤单、行情/资金控制及成交结算按原编排执行；不使用单币对 focused MATCH_STREAM
- 负载模型：连续异步 open-loop 发压，1 个命令 session + 1 个查询 session；全局 256 in-flight，session 256 in-flight；batch size=20；`mixed-trading-stream=true`、`mixed-operational=false`、`mixed-fill-heavy=false`
- 阶段：预热 30s，稳定测量 60s；测量前排空预热请求，测量后排空并执行资金、持仓、订单终态和订单簿核对
- JVM：Core `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC`；client `-Xms1g -Xmx1g -XX:+UseG1GC`；Core 启用 NMT summary
- JFR：Core 与 client 均使用 `owner-commit-profile.jfc`，最大 256m，保存原始 `.jfr`；同时采集 `jfr summary`、CPU/热点、分配、GC、线程/锁、safepoint、JIT、I/O、异常视图及 Core NMT
- 通过条件：`acceptedBusinessOperations == terminalBusinessOperations`；Core accepted/terminal messages 相等；`unfinished* == 0`；拒绝、错误、超时为 0；`fundsDiff == 0`；持仓和订单簿终态正确；Core 不得 fatal
- 性能参考阈值：`terminal business ops/s >= 300000`，入口到 terminal `p99 <= 5ms`；仅在全部正确性条件和产物有效性满足时形成验收结论
- 指标口径：报告 `requests/s`、`terminal business ops/s`、`terminal Core messages/s`、`fills/s`、最大/期末 backlog、拒绝率、错误率、超时率，以及 accepted→terminal 延迟 p50/p90/p95/p99/p99.9/max；busy-spin/CPU 使用单独说明
- 数据有效性：无 Core fatal、无 backlog/unfinished 残留、无 swap/throttling/JFR DataLoss；本轮结束后先追加结果，再清理本轮临时集群目录、Aeron 文件、JFR、JFR 视图、NMT、日志和报告

### 执行命令（已锁定）

```text
Core: -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -XX:+UseG1GC -Xms2g -Xmx2g
Client: -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=60 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.mixed-fill-heavy=false -XX:+UseG1GC -Xms1g -Xmx1g -cp product-core-benchmarks.jar com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain
```

### 执行结果

本轮已完成，结果为有效的单节点诊断轮次，不作为性能通过验收：

- 业务正确性：`offeredBusinessOperations=10,297,344`，`terminalBusinessOperations=10,297,344`；`offeredCoreMessages=987,648`，`terminalCoreMessages=987,648`；`unfinished=0`；`peakInFlight=256`；拒绝/错误/超时为 0；`fundsDiff=0`；population、持仓、reservation、loss 和订单簿终态核对通过；未再出现 `unknown lane command context`。
- 吞吐：测量 `60.087s`；`terminal business ops/s=171,373.169`；`terminal Core messages/s=16,436.896`；`fills=2,449,920`，`fills/s=40,772.704`；Core command-window `highWaterMark=191`、`pending=0`。
- 并发：128 symbols、1000 retail users、1 matcher、4 Account Lane、1 command session + 1 reserved query session、global/session in-flight=256、batch=20、`measuredCycles=957`。
- 延迟（accepted→terminal，us）：`PLACE_ORDER` p50/p90/p95/p99/p99.9/max=`15384/20414/21463/27672/49938/56918`；`CANCEL_ORDER`=`11821/15466/16777/23576/41123/47710`；`APPLY_MARK_PRICE`=`14737/19349/21135/25772/36536/37519`；`PLACE_ORDER_BATCH`=`18071/22020/23216/30621/53510/62029`；`CANCEL_ORDER_BATCH`=`15319/20070/21495/30261/49151/57016`。
- JFR/NMT：Core JFR 256,397,891 bytes、client JFR 167,469,640 bytes、均 `DataLoss=0`；Core 24 次 generic GC，最大暂停 19.3ms；client 28 次，最大 3.80ms；Core NMT 结束 `reserved=3,690,587KB`、`committed=2,338,519KB`，相对 baseline `+40,159KB/+51,687KB`。
- 归因：client JFR `ClusterMixedCapacityMain.reap()` 占 execution samples 的 80.35%；Core `MatcherCommandPipeline.skipReadyDirectSlots()` 约 1.49%，当前没有证据表明本次修复本身造成 39% 级别的回退。
- 口径复核：本轮启动命令未显式设置 `-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN`，服务默认使用 `BLOCKING`；历史约 280k 轮次显式使用 `BUSY_SPIN`。同时历史轮次为 256 symbols、2 matcher、window=64、plain 无 JFR，而本轮为 128 symbols、1 matcher、window=256、Core/client 带 JFR。因此本轮 `171,373.169 ops/s` 不能与历史约 280k 横向比较，也不能据此判断修复回退。
- 未测：长期稳定泄漏斜率、snapshot fence/恢复重启、三节点容量和无 profiler 对照；因此结论仅为“128 币对、G1、单节点真实 Aeron 链路下正确性通过，终态吞吐 171,373.169 ops/s”，相对 300,000 ops/s 参考线未达标。
- 原始产物已记录后清理：`/tmp/surprising-128-mixed.7J2h2a` 下的 Core/client、Archive、Aeron、日志、JFR、JFR 视图、NMT 和报告均属于本轮并已删除。

## 2026-09-15 11:52 单节点 Aeron 吞吐基线统一（采集前锁定）

- 变更目的：将项目标准单节点 Aeron 吞吐入口统一到昨日约 280,202 terminal business ops/s 的参数；本条只锁定配置，不构成新的吞吐结果。
- 被测 commit：当前 `master` 工作树；不检出或比较旧版本。
- 对照 commit：不适用（仅验证当前 master）。
- 配置源：`surprising-aeron-core/surprising-aeron-benchmarks/config/aeron-single-node-baseline.env`；脚本为 `bin/qualify-aeron-async-stages.sh`。
- 模式：Aeron Cluster 单节点开发模式；一个真实成员，保留网络、Archive 日志及交易 Core；不启动三节点。
- 产品线/业务：`LINEAR_PERPETUAL`，`ClusterOperationalBenchmark.continuousOperations`，连续异步 mixed trading；1 个 command session + 1 个 query session；256 symbols、1000 retail users、总用户数 1769、4 个 Account Lane、1 个 matching engine。
- 在途与批量：全局 in-flight=64、command session in-flight=64、Core `owner-command-window=64`、batch size=20、`MIXED`、`mixed-trading-stream=true`、`mixed-operational=false`、`mixed-fill-heavy=false`。
- JVM/GC：HotSpot JDK 25；Core `-Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC`；client `-Xms128m -Xmx512m -XX:+UseG1GC`；4 Lane、1 matcher；`SHARED_NETWORK` + `YIELDING`；settlement `BUSY_SPIN`、spin limit=0；标准吞吐默认不启用 JFR，JFR 只能用 `ASYNC_ENABLE_JFR=true` 单独采集，不能和 plain 吞吐混比。
- 阶段与口径：预热 30s，测量 60s；测量前排空预热请求，测量后排空；报告 `terminal business ops/s`、`terminal Core messages/s`、`fills/s`、accepted/terminal 差值、unfinished、backlog、拒绝/错误/超时和分业务延迟。
- 正确性门槛：`acceptedBusinessOperations == terminalBusinessOperations`、Core accepted/terminal 相等、两个 `unfinished* == 0`、资金守恒、持仓/冻结/订单终态和快照恢复检查通过；任何 Core fatal、积压、JFR DataLoss、swap 或明显 throttling 都使该轮无效。
- 执行命令：`ASYNC_ONLY_STAGE=end_to_end surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；如采集归因轮，另加 `ASYNC_ENABLE_JFR=true` 并单独记录，不能作为 plain 吞吐结果。
- 采集状态：待采集；本轮尚未启动压测进程，暂无结果、JFR、NMT 或原始 artifact。
- 未测范围：本条未执行长稳泄漏、三节点容量、其他产品线和独立本地 Core/JMH 诊断；它们不纳入该标准单节点吞吐基线。

## 2026-09-15 12:00 昨日基线 plain 单节点压测结果

- 被测 commit：`79c4f288`（Java 基线为 `a5d1e2a1`；本轮脚本只修复可选 JFR 参数拼接并补充 client JFR，不改变业务负载）。
- 对照 commit：不适用（仅验证当前 master）。
- 环境：HotSpot JDK 25.0.1，Oracle GraalVM；macOS x86_64，16 logical CPU；采集前磁盘可用约 506GiB，`ulimit -n=1048575`。
- 场景：真实 Aeron Cluster 单成员，`LINEAR_PERPETUAL`，256 symbols、1000 retail users、总用户 1769、4 Account Lane、1 matcher、1 command session + 1 query session；MIXED、batch20、global/session/owner window=64、G1、BUSY_SPIN、`SHARED_NETWORK` + `YIELDING`；30s warmup + 60s measurement；plain，无 JFR。
- 执行命令：`ASYNC_SKIP_BUILD=true ASYNC_ONLY_STAGE=end_to_end ASYNC_ENABLE_JFR=false ASYNC_ARTIFACT_DIR=/tmp/surprising-aeron-baseline.tD2IBb surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。

### 结果

- 测量时长：`60.070s`；JMH single-shot：`60.093s/op`，单样本 `scoreError=NaN`，不能形成统计置信区间。
- 吞吐：`terminal business ops/s=236,879.369`；`terminal Core messages/s=22,790.083`；`fills/s=56,339.286`；`terminalBusinessOperations=14,229,424`；`terminalCoreMessages=1,369,008`；`fills=3,384,320`。
- 完整性：accepted/offered 与 terminal 相等；Core offered 与 terminal 相等；`unfinished=0`；拒绝/错误/超时未发现；`peakInFlight=64`；matcher/completion/context high-water 均为64；测量后 pending=0。
- 资金与状态：`mixedVerify=PASS`，`fundsDiff=0`，population、跨 Lane 做市/吃单持仓、reservations、loss/insurance/ADL、订单簿终态均通过；初始资金 `1,768,000,000,125`；`businessHash=c306d2c2212fc22d`。
- 分业务延迟（accepted→terminal，us）：`PLACE_ORDER` p50/p90/p95/p99/p99.9/max=`1260/3723/4636/5824/9723/22183`；`CANCEL_ORDER`=`1247/3018/4444/5955/9404/24985`；`APPLY_MARK_PRICE`=`2760/5718/6447/8568/26165/31014`；`PLACE_ORDER_BATCH`=`3512/4435/4698/8253/19562/37388`；`CANCEL_ORDER_BATCH`=`4976/5578/5783/9920/21643/25460`。
- Lane：4 个 Lane useful execution ratio 为 `25.76%`、`25.92%`、`25.80%`、`25.97%`，平均 `25.86%`；本轮没有 Lane 计算饱和证据。
- NMT：Core 结束 `reserved=3,147,841KB (+30,953KB)`、`committed=714,425KB (+46,281KB)`；线程 `52 (+11)`，Code `committed=49,422KB (+36,791KB)`，Metaspace `committed=23,073KB (+7,116KB)`。

### 缺陷与未决项

- 相对历史约 `280,202.176 terminal business ops/s`，本轮为 `236,879.369`，低 `15.46%`；Core messages/s 和 fills/s 也约低 `15.3%`。本轮参数已对齐，说明仍存在未定位的吞吐差距，但不能仅凭单轮归因。
- 尾延迟偏高：历史记录的 `PLACE_ORDER` p99 为约 `4.571ms`，本轮为 `5.824ms`；本轮 batch cancel p99 为 `9.920ms`、max `25.460ms`，mark price max `31.014ms`，需要 JFR 归因。
- Core Archive/Cluster 数据增长异常大：本轮临时目录 `data` 约 `1.9GiB`、Aeron media 约 `47MiB`，60 秒 plain 轮不应长期保留；已列为压测产物膨胀/日志上限缺陷，目录将在本记录后删除。
- `adminActionRetries=82`，没有造成 terminal/资金错误，但表示管理动作存在 Aeron offer/backpressure 重试，需区分 setup 控制流与测量期业务流。
- plain 轮没有 JFR，因此 CPU 分线程、GC pause/分配、热点、锁竞争、safepoint、JIT 和系统调度尚未验证；JMH 只有一个 single-shot 样本，也没有误差区间。
- 本轮初次启动曾因脚本默认关闭 JFR 时展开空数组触发 `jfr_args[@]: unbound variable`，未进入节点；已在 `79c4f288` 修复，失败临时目录已清理。
- 未测：独立 JFR 归因轮、长稳泄漏斜率、snapshot/recovery、三节点容量和其他产品线；本轮不是最终性能验收结论。

### 原始 artifact 校验（清理前）

- `/tmp/surprising-aeron-baseline.tD2IBb/window-64/end_to_end/client.log`：`f8dcd247eb3ddd49fed7394e9307c2ca408e578099a5a7655aeee474953371a6`
- `/tmp/surprising-aeron-baseline.tD2IBb/window-64/end_to_end/jmh.json`：`0fc6ae929c19bbe1db159d040700a01a7f247641939062ffb784d96c8bd5a254`
- `/tmp/surprising-aeron-baseline.tD2IBb/window-64/end_to_end/metrics.json`：`6a288841440842c406505b3da7d452b861698857f0e5318ed779fda1531c3ed0`
- `/tmp/surprising-aeron-baseline.tD2IBb/window-64/end_to_end/nmt-summary.diff.txt`：`3d7c884ea905760b5e0e5f7ef50347cd59a8a1d9d84e680182b08e0786d10452`
- 原始产物已记录后清理；上述路径不再作为后续可访问证据。

## 2026-09-15 12:04 昨日基线 JFR 归因轮次（部分无效）

- 被测 commit：`79c4f288`（Java 基线为 `a5d1e2a1`；沿用上一条已锁定的昨日基线场景）
- 对照 commit：不适用（仅验证当前 master）
- 场景和参数：与 12:00 plain 轮一致：真实 Aeron Cluster 单成员、`LINEAR_PERPETUAL`、256 symbols、1000 retail users、总用户 1769、4 Account Lane、1 matcher、MIXED、batch20、global/session/owner window=64、G1、Core `512m/1536m`、client `128m/512m`、30s warmup + 60s measurement、`SHARED_NETWORK` + `YIELDING`、BUSY_SPIN。
- 执行命令：`ASYNC_SKIP_BUILD=true ASYNC_ONLY_STAGE=end_to_end ASYNC_ENABLE_JFR=true ASYNC_ARTIFACT_DIR=/tmp/surprising-aeron-baseline-jfr.OlvyyH surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`

### 结果

- 正确性和排空：`clientPass=true`；accepted/terminal business operations 相等；Core accepted/terminal messages 相等；`unfinished=0`；`peakInFlight=64`；资金/状态核对通过；测量后 pending=0。
- 吞吐：`terminal business ops/s=228,676.046`；`terminal Core messages/s=22,007.806`；`fills/s=54,386.379`；JFR profiler 会改变绝对吞吐，本轮不与 plain 轮横向比较。
- 延迟（accepted→terminal，us）：`PLACE_ORDER` p50/p90/p95/p99/p99.9/max=`1269/3063/4505/6119/20709/36012`；`CANCEL_ORDER`=`1272/2486/3192/5713/12730/23838`；`APPLY_MARK_PRICE`=`3094/6529/7622/12296/19464/37650`；`PLACE_ORDER_BATCH`=`3606/4501/5091/8683/23461/268959`；`CANCEL_ORDER_BATCH`=`5287/6168/6647/12804/24363/46465`。
- CPU/饱和归因：owner 单核约 `91.283%`，matcher `46.313%`，Lane 最大约 `97.478%`；JFR gate 在 end_to_end 仍为 `PASS`，但 owner 饱和、matcher/Lane 阈值未同时满足；Lane useful ratio 平均约 `26.215%`、最小 `26.044%`。
- Core JFR：约 140s，`DataLoss=0`；142 次 GC，最长暂停约 `12.4ms`；无 `AllocationRequiringGC`；记录到 `FileWrite=1,030,030`、`ThreadPark=4,591,623`、`ObjectAllocationInNewTLAB=88,902`、`ExecutionSample` 和 safepoint 事件；Core NMT 结束 `reserved=3,175,025KB (+31,605KB)`、`committed=739,929KB (+44,729KB)`，线程 `57 (+12)`、Code `52,356KB (+36,403KB)`、Metaspace `25,768KB (+6,925KB)`。
- 热点：`SettlementLaneWorker.run()` 占 execution samples `74.41%`；分配热点包含 `OrderRuntime=16.06%`、`byte[]=12.95%`、`long[]=7.72%`、`ReservationRuntime=6.48%`、`MatcherResult=4.36%`；分配站点包含 `CoreMatchingResult.classify=10.11%`、`TradingCommandCodec.decodePlaceOrder=7.26%`、`IntObjectHashMap.get=6.28%`。

### 缺陷与有效性

- client JFR 文件不可读：脚本让 JMH parent/fork 共用同一个 `client.jfr`，最终文件大小约 141MiB，`jfr summary` 报 `Not a Flight Recorder file`。因此本轮只能使用 Core JFR 做归因，标记为部分无效，不能作为完整 JFR 验收证据。
- 该缺陷已在 `cf521eaf` 修复：client JFR 改为按 PID 分文件，并分别生成 summary/view；本轮临时目录和原始产物均已清理，路径不再作为后续可访问证据。

### 原始 artifact 校验（清理前）

- `/tmp/surprising-aeron-baseline-jfr.OlvyyH/window-64/end_to_end/node.jfr`：`fc0b54bbb440a619eb187a406c0e8d5e5bc1f4a6d08aec58068bbc86eda1550d`，约 168MiB，Core JFR 有效。
- `/tmp/surprising-aeron-baseline-jfr.OlvyyH/window-64/end_to_end/client.jfr`：`237e6382a2161ffc002bbc218dbc79676474979083a481da5610ccde1f00566e`，约 141MiB，JFR 无效。
- 原始产物已记录后清理；上述路径不再作为后续可访问证据。

## 2026-09-15 12:08 昨日基线有效 JFR 归因结果

- 被测 commit：`cf521eaf`（在 `79c4f288` 基础上修复 JMH parent/fork 的 client JFR 文件冲突；业务场景和压测参数不变）
- 对照 commit：不适用（仅验证当前 master）
- 采集性质：沿用 11:52 已锁定的标准场景，属于 profiler 诊断轮次，不替代 plain 吞吐结果；Core 和 client 均为有效 JFR，client 以测量 fork `client-59164.jfr` 为主，`client-59161.jfr` 为 JMH parent/setup 进程。
- 执行命令：`ASYNC_SKIP_BUILD=true ASYNC_ONLY_STAGE=end_to_end ASYNC_ENABLE_JFR=true ASYNC_ARTIFACT_DIR=/tmp/surprising-aeron-baseline-jfr-valid.rfqWgm surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`

### 结果

- 正确性和排空：`mixedVerify=PASS`，`fundsDiff=0`，population、hftPositions、reservations、loss、订单簿终态通过；`offeredBusinessOperations=terminalBusinessOperations=13,455,334`；`offeredCoreMessages=terminalCoreMessages=1,295,334`；`unfinished=0`；`peakInFlight=64`；测量后 pending=0；`adminActionRetries=77`。
- 吞吐：测量 `60.118s`；`terminal business ops/s=223,816.445`；`terminal Core messages/s=21,546.626`；`fills=3,200,000`，`fills/s=53,228.900`；JMH 仍是 single-shot，不能提供误差/置信区间。
- 延迟（accepted→terminal，us）：`PLACE_ORDER` p50/p90/p95/p99/p99.9/max=`1353/3223/4612/6365/24002/47349`；`CANCEL_ORDER`=`1329/2668/3549/6213/23199/46137`；`APPLY_MARK_PRICE`=`2930/5435/6549/8462/13926/27377`；`PLACE_ORDER_BATCH`=`3758/4841/5160/8593/22495/53542`；`CANCEL_ORDER_BATCH`=`5386/6328/6631/9379/23085/39124`。
- Pipeline/Lane：matcher、completion、context high-water 均 `64`，4 Lane high-water 为 `28/27/29/29`；Lane useful execution ratio 为 `26.4438%/26.6485%/26.6135%/26.6700%`，平均 `26.59395%`，最小 `26.4438%`。
- JFR CPU：owner 单核 `92.579%`；matcher `46.814%`；4 个 Lane 单核 `98.447%/98.439%/98.434%/98.416%`；JFR 线程 CPU 样本 `910`，逻辑 CPU `16`。这说明当前平台更接近 owner/Account Lane/结算路径受限，而非 matcher 单独饱和；Lane 高 CPU 与只有约 26.6% useful execution ratio 同时出现，存在 busy-spin/调度/协调开销。
- JFR 热点：`SettlementLaneWorker.run()` 占 execution samples `74.35%`；其后为 `Long2ObjectHashMap.getMapped=1.25%`、`ThreadLocal.get=1.05%`、`MatcherCommandPipeline.skipReadyDirectSlots=0.47%`、`SurprisingClusteredService.progressCommandsInScope=0.44%`。
- Core 分配：按类为 `OrderRuntime=17.20%`、`byte[]=12.91%`、`ReservationRuntime=6.81%`、`long[]=6.37%`、`MatcherResult=5.90%`、`ResolvedPlaceOrder=4.09%`；按站点为 `CoreMatchingResult.classify=8.84%`、`TradingOrderBatchCodec.decodeCommand=7.05%`、`IntObjectHashMap.get=6.67%`、`TradingOrderBatchCodec.encodeResultSource=4.96%`、`FillCursor.order=4.24%`、`AssetBalance.validAsset=4.08%`。
- Client 测量 fork 分配：`byte[]=67.51%`；站点主要为 `String.getBytes=15.67%`、`CoreMessageCodec.decode=11.32%`、`AsyncConnection.onMessage=11.02%`、`CoreProtocol.decodeResponse=10.81%`、`AeronClientPool.AgentLane.commandRequest=7.45%`；client execution samples 中 `ClusterMixedCapacityMain.reap()` 占 `79.26%`。
- GC/调度：Core 138 次 GC，暂停总和约 `681.180ms`，最长 `12.800ms`；client 测量 fork 315 次 GC，暂停总和约 `210.466ms`，最长 `7.430ms`；两端 `DataLoss=0`、`AllocationRequiringGC=0`、Container CPU throttling 事件为 0。Core 记录 `ThreadPark=4,126,013`、`FileWrite=977,825`、`JavaExceptionThrow=239`、`JavaErrorThrow=107`；这些计数包含 setup/运行期事件，不能直接等同于业务失败。
- Safepoint/NMT：Core 154 次 safepoint begin，存在一次约 `33.5ms` 的 safepoint 记录；NMT 结束 `reserved=3,173,476KB (+26,085KB)`、`committed=738,860KB (+43,645KB)`、线程 `56 (+9)`、Code committed `50,694KB (+34,686KB)`、Metaspace committed `25,759KB (+6,916KB)`；未见本轮可证明的 native 泄漏，但短轮不能证明长稳无泄漏。

### 已验证缺陷、可能原因和未决范围

- 已验证吞吐缺口：plain 轮 `236,879.369 terminal business ops/s`，比昨日约 `280,202.176` 低 `15.46%`；JFR 轮只用于归因，不能拿 `223,816.445` 与 plain 直接比较。当前证据支持“吞吐回退仍存在”，不支持把回退归因到单一代码改动。
- 已验证尾延迟问题：plain `PLACE_ORDER` p99 `5.824ms`，已超过锁定的 `5ms` 参考线；有效 JFR 轮 `PLACE_ORDER` p99 `6.365ms`、batch cancel p99 `9.379ms`，profiler 会放大绝对值，但说明尾延迟需要继续治理。
- 最强瓶颈候选：`SettlementLaneWorker.run()` 占 74.35% execution samples，4 个 Lane 各约 98.4% CPU，而 useful execution 仅约 26.6%；应优先检查 Settlement/Account Lane 的 busy-spin、空转和 owner-to-lane 协调，再做无 profiler A/B 复测。matcher 只有约 46.8%，不应先把 matcher 作为唯一瓶颈。
- 已验证产物膨胀缺陷：有效 JFR 轮 `/tmp/surprising-aeron-baseline-jfr-valid.rfqWgm` 约 `2.1GiB`，其中 `data` 约 `1.8GiB`；60s 压测产生的 Archive/Cluster 数据没有合理上限或轮后自动回收策略。原始目录已清理，不能作为后续访问路径。
- 已验证管理控制流重试：`adminActionRetries=77`；本轮没有造成业务 terminal、资金或状态错误，但应拆分 setup/控制流重试与测量期业务拒绝率，避免把控制流 backpressure 混入业务指标。
- 已修复压测脚本缺陷：`set -u` 下关闭 JFR 时空数组展开会导致节点启动前退出；JMH 多进程共用 client JFR 文件会产生不可读 recording；分别已由 `79c4f288`、`cf521eaf` 修复并推送。
- 未测/不能下结论：长稳多轮 GC 后 live set/old object 增长、Direct/native buffer 释放斜率、snapshot fence/recovery、三节点容量、无 profiler 的 JMH 置信区间、其他产品线；本轮仅完成当前 master 的 G1、真实 Aeron 单节点、昨日基线参数的正确性和诊断性性能验证。

### 原始 artifact 校验（清理前）

- `/tmp/surprising-aeron-baseline-jfr-valid.rfqWgm/window-64/end_to_end/node.jfr`：162,275,752 bytes，`9465755f1a46315e657b384beec171770182d1c031dde29d933427bcdd93dcd0`
- `/tmp/surprising-aeron-baseline-jfr-valid.rfqWgm/window-64/end_to_end/client-59164.jfr`：131,069,556 bytes，`928c018a1c878f766f9bb4dbaf0f4d9fc3a86091e08fccd4d65aec7571869e3c`
- `/tmp/surprising-aeron-baseline-jfr-valid.rfqWgm/window-64/end_to_end/client-59161.jfr`：10,107,538 bytes，`c5921bae27559c71b6b49be069fab888f733b9fa51f3ce5258851574752ddb88`
- `client.log`：`e44eb09cfe38c6ae15f45b3a274492a17d41f86312ce85c8260deade7f70f495`
- `jmh.json`：`45e736d28a7c8c5710b8aada104d704f875e471cc45380e7b0e0161327d9ccc8`
- `metrics.json`：`71077e15b26212f5899e49b77f524233e2106f916fe53203893ac7793f8089b4`
- `nmt-summary.diff.txt`：`0a74f1e31c8dfed81c68a12bd01f481dd3584700f6864f154407a52878d70654`
- 原始产物已记录后清理；上述路径不再作为后续可访问证据。

### 本轮结论

- 正确性：通过。业务/Core accepted 与 terminal 对齐，`unfinished=0`，资金守恒和状态核对通过，未复现 `unknown lane command context`。
- 性能：未通过昨日约 280k 参考表现及 `PLACE_ORDER p99 <= 5ms` 参考线；当前结果是“正确性通过、吞吐和尾延迟部分验证且存在缺口”，不是最终容量验收。
