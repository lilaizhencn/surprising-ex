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


## 2026-09-15 13:10 历史提交 4bed7e31 单节点复测

- 被测提交：`4bed7e3164fde80248cf0139eddf595f915ba27f`（2026-09-14 09:28:23 +0800），临时 detached worktree；不作为当前 master 验收基线。
- 模式：真实 Aeron Cluster 单节点，`LINEAR_PERPETUAL`，128 symbols（该提交源码固定值）、1000 retail users、4 Account Lane、2 matcher；G1、BUSY_SPIN、PIPELINED、`SHARED_NETWORK` + `YIELDING`。
- 负载：`ClusterMixedCapacityMain`，MIXED、batch20、global/session in-flight=256、30s warmup + 60s measurement、seed131001；无 JFR，仅采集 NMT。
- 结果：`mixedVerify=PASS`、`fundsDiff=0`、`unfinished=0`、accepted/terminal business 与 Core messages 相等、`peakInFlight=256`、`pipelineHighWater matcher=63 completion=63 context=246 lanes=[61,61,61,60]`。
- 吞吐：`terminalBusinessOperations=13,243,392`，`businessOpsPerSec=220,624.351`；Core messages/s=`21,127.601`；fills/s=`52,499.145`。
- 延迟（accepted→terminal，us）：PLACE p99=`20,021`，CANCEL p99=`18,186`，PLACE_BATCH p99=`25,231`，CANCEL_BATCH p99=`29,163`。
- NMT：结束时 `reserved=3,131,291KB (+27,728KB)`、`committed=705,167KB (+44,624KB)`；客户端退出码0，Core无错误。
- 原始 worktree、Archive、媒体、日志和 NMT 文件已清理；本轮只有一个 plain 样本，无统计置信区间和 JFR 热点结论。


## 2026-09-15：直达结算改造先做正确性验证（通过，性能未验收）

- 用户要求：先正确性测试，压测留到最后。范围为 Core 内部命令槽、Matcher→Lane 交接、普通/批量撤改单和触发单，以及共享资金、恢复边界。门槛：所有受影响测试通过；错误结算禁止推进提交/导出，重放保持资金和索引一致。
- 环境：本机 master，基线 `ee2897ae` 加本批工作区修改；HotSpot GraalVM JDK 25.0.1、Maven 3.9.16；磁盘可用约 459 GiB。未进行历史性能对照；未启动 GCP 或 JMH 采样。
- 业务顺序：入口绑定日志边界并预构造事件 → Matcher 发布结果 → Lane 修改真实账户 → Owner 按序发布。批量撤单把最后 Lane 序号提交合入实际撤单；批内改单按当前项原单信息识别合法撤旧后拒单。资金/持仓模型未改。

| 执行记录（`/tmp/core-correctness-20260915` 前缀） | 结果与处置 |
|---|---|
| `.log` | 停止未完成轮次：辅助等待仍只读取旧结果位置；线程栈确认后修正。 |
| `-r2.log` | 349 用例，9 失败、35 错误；定位触发子单日志边界、旧活跃订单断言及批量路径问题。 |
| `-r3.log` | 349 用例，3 失败、47 错误；末尾 Lane 提交收集早于批结果掩码初始化，调整登记时机。 |
| `-r4.log` | 296 用例，0 失败、37 错误；撤单修改未登记最终发布，以及故障注入位置不符新路径。 |
| `-r5.log` | 296 用例，2 失败；268 个集群流水线用例通过，修复余额副本注入和致命错误优先级。 |
| `-r6.log` | 28 个批量订单用例通过，含真实 Lane 失败、先前快照重放和重复请求。 |
| `-amend.log` | 新增批内 post-only 拒单交叉覆盖，6 错误；修复读取普通准入而非当前批项准入的错误。 |
| `-full.log` | 服务 952 用例中仅 5 个恢复辅助断言引用已删除字段；其余通过，新增拒单覆盖通过；直接依赖 180 用例通过。 |
| `-recovery.log` | 改查真实命令槽和快照边界后，恢复类 8 个用例全部通过。 |
| `-compile.log` / `-workloads.log` | 基准及依赖 test-compile 通过；9 个基准驱动功能测试类 188 用例通过。 |

最终按测试类最新有效报告去重：**141 个类 / 1,320 用例，失败=0、错误=0、跳过=0**。不是全部用例在同一轮首次通过。完整命令和范围见 `surprising-aeron-core/EXECUTION_REFACTOR.md`。基准驱动执行固定业务场景检查完成数、资金、持仓及快照；未把测试输出作为性能数据。

已覆盖资金守恒、订单冻结/解冻、批内结果顺序、触发终态、重复请求、故障停止提交/导出、六产品线恢复及索引重建一致性。未测持续饱和吞吐、p99、JFR 分配率、长稳、GCP；没有新的 ops/s 或 bytes/op 结论。控制业务续步、部分批内推进和通用 LaneCommitEvent 的进一步简化仍未完成。

代码差异 SHA-256（服务及基准，含新增/删除文件）：`e87d34c4f635aac0eb86ad7e7a374c5ca910cf9e11f7192c5a480dd63585fb16`。最终 XML 报告清单摘要 SHA-256：`a3796dd11967b6d0928f59593a154187e8efa342167623993f2e922a7c2c3248`。

原始日志摘要（清理后仅作历史定位）：

| 文件后缀 | 字节 | SHA-256 |
|---|---:|---|
| `-amend.log` | 31953 | `fe556af01dc23c2fd7aa940c48afc479930091e7d93c83df624b4405efba9751` |
| `-compile.log` | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `-full.log` | 214124 | `33afaf46de17feac962c56e3f7aa056016820ef92b1c2518835fd4f1e5cec12c` |
| `-r2.log` | 222569 | `82d3ce0ff5bb19ba6456390757d26cc2af9be8ce573cb1575c2beb34abe7d17b` |
| `-r3.log` | 257298 | `50d9b4de1dfb6959a1735bc974df4b3814e35e6979d27ac104b9775a4024bbea` |
| `-r4.log` | 220624 | `2143e4ea20afc89b17570c21c4448d17ed10d3b3ea9f34c83675fff1a41c3552` |
| `-r5.log` | 178017 | `8815824ac2578bb5037d1e76daabf53129eb0a5e0a01f33a73b082fd3d649a12` |
| `-r6.log` | 648 | `b2ed3e2baa8e41e37f7f426276c0c2e0465ef9c84bf3fc0f9efa18ea88db57cb` |
| `-recovery.log` | 2358 | `b70c2bd95c20a3ab3ae3ef1278cff4a0a833b8c99c4ec9be5a429bca3fe8e55b` |
| `-threads.txt` | 36961 | `552fb3ef1382687fb68d8fa6f970038b1ee4fd6bf6272ac69877ec532b47d4d7` |
| `-workloads.log` | 61978 | `30716a803ea13906449d951990e28ee63fd5970587a4158cd5dd06a7376e776d` |
| `.log` | 7832 | `d8b743af3aaafcb1eaae5c4376ede4df1caf2a335842d96224959a28706b2ef2` |

清理：所有本轮 Maven/Surefire 进程已退出；已删除 295 个确认属于本轮且摘要一致的临时日志、线程栈、测试 XML/文本报告及临时清单。测试自身临时目录由夹具关闭清理；未创建 GCP 资源或 JFR/堆转储。以上路径仅作历史定位，构建产物保留。`git diff --check` 和暂存差异检查通过。


## 2026-09-15：6ba31a05 本机短时单节点验证（采集计划）

- 用户要求短时本机压测；当前 master `6ba31a05`，生产代码无未提交修改。对照 commit：不适用（仅验证当前 master）。目标参考：terminal business ops/s >=300000、普通下单 accepted→terminal p99<=5ms；错误/超时=0，accepted=terminal、unfinished/backlog=0、资金及状态核对通过。单次短样本不能作长稳或容量验收。
- 环境：i9-9880H（8C/16T）、16GiB RAM、macOS 26.7 x86_64、HotSpot GraalVM JDK25.0.1、Maven3.9.16；可用磁盘459GiB。IDEA观测约121%CPU，WindowServer约25%，不停止用户程序；同机干扰限制比较有效性。
- 真实单成员 Aeron（网络+Archive+Core）；LINEAR_PERPETUAL、MIXED、batch20、128 symbols、1000 retail users、4 Account Lane、1 Matcher；BUSY_SPIN Matcher/Lane，Aeron SHARED_NETWORK+YIELDING。G1，Core512m/1536m、client128m/512m。沿用现有驱动初始化与做市，不改业务策略。
- 异步发压，global/session/Owner in-flight=256，seed25620；30s内部预热+30s稳定测量+驱动排空，plain和JFR各1轮。达到256后受背压，不宣称open-loop计划到达率；JMH单fork/线程、单次长业务调用，业务主吞吐使用稳定窗口终态计数，不使用JMH调用次数/s。
- plain提供主结果；同条件JFR单独归因，使用owner-commit-profile.jfc（ExecutionSample20ms，线程CPU/分配统计1s、TLAB/非TLAB、GC/锁/IO等），每进程256MiB上限；脚本保留NMT、线程栈、测量时间与Lane工作指标。JIT稳定性、排空、资金和完成性按输出核查；未覆盖项记缺口。短时不测长稳/泄漏，也不启动GCP。
- 命令：`ASYNC_RUN_ID=20260915-short-plain ASYNC_ARTIFACT_DIR=/tmp/aeron-short-20260915-plain ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。第二轮同参数，RUN_ID/ARTIFACT_DIR后缀改为profile，`ASYNC_SKIP_BUILD=true ASYNC_ENABLE_JFR=true`。

### 短时结果及归因

**结论：正确性通过；未达到30万业务ops/s及下单p99<=5ms参考线。性能为部分验证，不能推断改造前后的回退幅度。** 未修改业务策略或生产代码。

| 指标 | plain（主结果） | JFR（归因轮） |
|---|---:|---:|
| 测量时间（北京时间，驱动含排空） | 18:05:35.673–18:06:05.690 | 18:07:36.644–18:08:06.660 |
| 稳定阶段前两个10秒区间 business ops/s | 175,995.932 / 180,521.884 | 154,681.399 / 166,767.399 |
| 驱动汇总 business ops/s（含排空） | 179,585.522 | 164,551.131 |
| terminal/accepted business operations | 5,390,592 / 5,390,592 | 4,939,008 / 4,939,008 |
| terminal/accepted Core messages | 516,864 / 516,864 | 473,856 / 473,856 |
| Core messages/s / fills/s | 17,219.128 / 42,727.999 | 15,787.288 / 39,148.380 |
| unfinished / 期末backlog / 峰值在途 | 0 / 0 / 256 | 0 / 0 / 256 |
| Lane实际执行时间占比（4 Lane均值） | 25.08% | 25.63% |

plain业务延迟（客户端调用commandAsync前→收到终态；**不含256窗口外的等待，不是服务端accepted时间戳**；未校正coordinated omission）：

| 业务 | 请求数 | 业务项数 | p50 / p99 / p99.9 / max（ms） |
|---|---:|---:|---|
| PLACE_ORDER | 128256 | 128256 | 13.238 / 26.296 / 45.711 / 53.805 |
| CANCEL_ORDER | 128256 | 128256 | 12.369 / 23.396 / 41.582 / 50.036 |
| PLACE_ORDER_BATCH | 192384 | 3847680 | 15.794 / 30.818 / 48.037 / 61.046 |
| CANCEL_ORDER_BATCH | 64128 | 1282560 | 18.923 / 37.781 / 48.463 / 52.297 |
| APPLY_MARK_PRICE | 3840 | 3840 | 12.509 / 46.366 / 52.527 / 53.510 |

- 完成性：两轮mixedVerify=PASS、fundsDiff=0，population/hftPositions/reservations/loss核对通过，客户端退出码0；plain总用户1385（retail1000）、128 symbols。plain队列高水位Matcher128/completion128/context139、Lane=[56,56,54,55]，仅为全轮峰值，不能证明持续队列积压。
- 稳定吞吐口径缺口：现有runFor在drain后记录elapsed与measurementEnd，排空时间未单列。因此主判断使用前两个10秒progress区间；最后30秒汇总仅作含排空结果。脚本saturationGate的end_to_end PASS只验证在途窗口，不代表Matcher/Lane饱和。排空时长、背压时间比例、分段入口→accepted延迟、逐秒队列深度未采齐，不能作为完整容量验收。
- JFR按测量起止epoch过滤：Owner平均97.93%单核，Matcher55.62%，Lane约98.2%单核但实际执行时间仅25.3%–26%左右。Lane ExecutionSample中SettlementLaneWorker.run自耗占75%–89%，符合busy-spin空转；不能把Lane CPU高当作业务饱和。Matcher只有31个ExecutionSample，具体Matcher方法排名证据较弱。
- Owner稳定窗口1074个ExecutionSample：CommandResultLedger.find/findInsert自耗87+48个（12.57%）；finishOrderBatch包含144个（13.41%），storeOwnedResult96个（8.94%），LanePublishedMap.applyPublished88个（8.19%）。包含栈会重叠、受栈深限制，不能相加。当前证据支持Owner的结果索引、批终态收尾和状态发布仍是主要受限路径；下一次最小验证应针对这条链路减少工作，并保持相同负载核对Owner样本和下游推进。
- 发压端JMH业务线程98.34%单核，reap自耗1003/1219样本（82.28%）；它主要轮询尚未完成的请求，CPU高不能单独证明发压端先受限。没有提交率、服务队列时间序列来完全排除客户端约束。
- 分配：ThreadAllocationStatistics在稳定窗口内每线程首尾累计字节差/时间后求和，Core约336.41 MB/s，折合2044 B/business op；客户端fork约201.76 MB/s，折合1226 B/op，单独统计。Core线程分配Matcher81.78、Owner51.81、每Lane46.35–46.36 MB/s。ObjectAllocationSample加权主要为OrderRuntime、byte[]、ReservationRuntime、long[]、MatcherResult、Long；不是精确对象数/op。站点包括decodePlaceOrder、CoreMatchingResult.classify、OrderRuntime构造、preparedOrder及批响应编码。
- GC/JIT：稳定窗口Core34次GC，暂停合计209.29ms，单次GC暂停合计最大11.47ms；client78次，暂停75.49ms，最大1.57ms。Core仍有204次Compilation、client93次，30秒预热未证明所有编译稳定。JFR轮吞吐低8.37%，包含采样开销及短轮波动，不能全归因于profiler。
- 系统：测量窗口ps样本plain Core平均933%CPU/client357%CPU/IDEA63%CPU，profile分别929%/365%/66%；用户IDE及系统线程仍在争用CPU。plain/profile Core峰值RSS约1.79/1.80GiB。vm_stat结束swapins/swapouts均0。无容器配额，未测热节流、上下文切换或精确IO阻塞归因。
- NMT（启动后基线→结束，含初始化）：plain reserved3,131,338KB(+14,738)、committed717,142KB(+49,150)；profile reserved3,156,041KB(+8,584)、committed740,737KB(+45,492)。短轮不能证明无内存泄漏。
- 本轮沿用此前1320项正确性/快照重放结果；真实压测仅做驱动资金与状态核对，未单独重启真实Cluster进行Archive/snapshot恢复。未做长稳、GCP或第二轮plain重复，不能给置信区间。

采样证据：Core PID47508、JMH runner47522、client fork47527，plain对应46875/46890/46901。Core JFR105s、ExecutionSample18540、ObjectAllocationSample37720、NewTLAB37724、OutsideTLAB139、ThreadAllocationStatistics2694；client fork JFR101s、ExecutionSample3089、ObjectAllocationSample20835。三份录制DataLoss均0。原始JFR覆盖初始化/预热/测量/核对；上述归因只取约30秒测量窗口。已执行jfr summary、thread-cpu-load/hot-methods/allocation-by-class/site/thread/contention/gc/safepoints等view，并用jfr print --json按epoch过滤汇总。

原始关键产物（清理后仅作历史定位；根目录为/tmp/aeron-short-20260915-{plain,profile}/window-256/end_to_end）：

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| plain/metrics.json | 2988 | `989fa92dd52547c0655460aa45e8e3abb417056873e9bb11c06ee06c1e70ea32` |
| profile/node.jfr | 106694438 | `37344ebd64f4f14135f6efc4081c9e9618b8971d2ca6ace4e6948cbd41dfd515` |
| profile/client-47522.jfr | 7351560 | `102d52487a514747f5ad66cf8df03ad5e94c41b15d7b4c5e6cbf9cc67717487a` |
| profile/client-47527.jfr | 78596303 | `ebd1b9268b551ec5f92bcdefde8d340ac9ffc1ce5fb5235dfb0f69834e161fb6` |
| profile/metrics.json | 3121 | `870385c26c70c00cce5f9f2cdad5f8062bfc69e08fb90793bd8aae55e1f34bf6` |
| profile/window-analysis.json | 68219 | `57cb63178bcee184b8fd42bd339c494c2b18bec9f8a7a061611c1091d5b7061d` |

38个关键产物的路径/大小/摘要清单SHA-256：`d381580da36239a5bf9ceedddb4a841b95fe857b6ad29de8bdde817c31ce7211`。

清理完成：两轮Core及客户端JVM、系统采样和分析进程均已退出；删除本轮临时集群data/Archive/media、JFR、日志、汇总及分析脚本，共104个文件。未创建云资源。关键参数、结果及摘要已入档，上述临时路径不再可访问。


## 2026-09-15：Owner 冗余索引及批量撤单收尾清理（采集计划）

- 当前 master 基础 commit `9aa8dfcf`；对照 commit 不适用（仅验证当前 master）。未提交修改：结果账本删除 tombstone、合并查找；取消账户/持仓/冻结发布视图的无人读取准入索引；批量撤单复用 Lane 结果槽准备终态响应。保留幂等保留上限、订单待结算索引、有序提交和故障恢复。
- 验证假设：结果账本不再因长期插删退化为全表探测，Owner 批量撤单结果查询减少；参考线持续业务吞吐>=300000/s、普通下单 p99<=5ms、错误/超时=0、排空完成性和资金核对通过。短轮只作局部诊断，长稳/真实节点重启恢复未覆盖，不作完整容量验收。
- 环境和业务沿用上轮：i9-9880H 8C16T/16GiB/macOS26.7、HotSpot GraalVM25.0.1/Maven3.9.16；真实单成员Aeron、LINEAR_PERPETUAL、MIXED batch20、128symbols、1000retail、4Lane、1Matcher、BUSY_SPIN，G1，Core512m/1536m、client128m/512m、seed25620。全局/session窗口256，不改变发单策略；测量30s前预热30s，plain/profile各1次。IDE等同机干扰保留。
- 新增 steadyCapacity 单独统计停止发压前的终态增量/时间；drain 单列排空时间和增量；windowBlockedNanos 统计客户端256窗口等待。旧mixedCapacity汇总保留作完成性核对，不用作稳定吞吐。延迟仍为调用commandAsync前到终态，不含窗口等待，未校正coordinated omission；不是accepted→terminal分段延迟。采样结束时间戳现在在drain之前。
- 命令：`ASYNC_RUN_ID=20260915-cleanup-plain ASYNC_ARTIFACT_DIR=/tmp/aeron-cleanup-20260915-plain ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；profile轮改后缀并设`ASYNC_SKIP_BUILD=true ASYNC_ENABLE_JFR=true`。JFR沿用owner-commit-profile.jfc，每JVM独立文件最大256MiB；线程CPU/分配1s、执行采样20ms；NMT、GC、锁、IO、系统采样由脚本采集，按steady epoch窗口归因。
- 新增JMH `CommandResultLedgerBenchmark.retainAndQuery`：2048循环ID、保留128条、预先插删10000次，固定128B响应；1fork/1thread、3×1s预热+3×1s测量，plain与单独`-prof gc`，`-Xms128m -Xmx512m -XX:+UseG1GC`。仅衡量结果保留+查询，不换算业务吞吐；GC分配反映StoredResult等本路径创建，响应/ID预建不计入。
- 正确性先行：完整service测试953项，业务失败0，1个OwnerIndexChurnTest因访问已删除索引发生NPE；保留发布表存储复用断言，订单索引继续检查，删除的索引不再读取。修正后精确复跑，并运行基准驱动125项六产品线批业务/恢复测试。结果随后追加。

### 清理后的正确性与短时性能结果

结论：**正确性通过，性能部分验证；30万业务ops/s和下单p99<=5ms参考线未达到。** 没有重跑旧版本，不据历史值计算回退/提升幅度。

- 最新有效正确性报告：1258项，失败/错误/跳过=0。service953、六产品线批业务基准驱动125、protocol107、client49、公共模块24；包含资金/持仓、批内重复或拒单、故障停机及快照恢复。首轮1个过期索引测试错误已修正并精确复跑；没有把业务断言删除。
- 附加验证：汇总解析器在steady=80、含排空=100的合成日志上选择80；Lane业务占比80%通过、60%不通过70%门槛。Maven test/package、git diff --check通过。

| 指标 | plain主结果 | JFR归因轮 |
|---|---:|---:|
| 稳定窗口 terminal business ops/s | 235,901.016 | 223,508.542 |
| 稳定窗口 terminal Core messages/s | 22,582.415 | 21,402.249 |
| 稳定窗口 fills/s | 56,157.778 | 53,207.184 |
| 稳定窗口业务完成量 / 秒 | 7,086,720 / 30.041075 | 6,710,400 / 30.023014 |
| 排空时间 / 业务项 / Core消息 | 9.839ms / 2688 / 256 | 15.919ms / 2688 / 256 |
| 排空后 offered=terminal 业务项 | 7,089,408 | 6,713,088 |
| 排空后 offered=terminal Core消息 | 678,656 | 642,816 |
| unfinished / 峰值在途 | 0 / 256 | 0 / 256 |
| 客户端窗口等待占稳定阶段 | 88.14% | 86.96% |
| Lane实际业务执行占比（均值，含排空） | 32.04% | 32.93% |

plain前3个10秒区间237852.894/229841.499/240018.899 ops/s。两轮mixedVerify=PASS、fundsDiff=0，population/hftPositions/reservations/loss通过；逐业务响应校验无失败，客户端exit=0。无独立accepted速率时间序列，offered/terminal不能冒充入口→accepted分段采集。256窗口已发生背压，不是无约束open-loop容量。

plain延迟（调用commandAsync前→终态，不含窗口等待；计数包含排空；未做coordinated omission校正）：

| 业务 | requests / items | p50 / p90 / p95 / p99 / p99.9 / max (ms) |
|---|---:|---|
| PLACE_ORDER | 168704 / 168704 | 7.565 / 16.031 / 21.364 / 37.781 / 56.197 / 85.524 |
| CANCEL_ORDER | 168704 / 168704 | 7.802 / 15.851 / 20.250 / 37.945 / 85.327 / 96.731 |
| PLACE_ORDER_BATCH | 253056 / 5061120 | 9.715 / 20.742 / 28.622 / 52.690 / 84.869 / 102.432 |
| CANCEL_ORDER_BATCH | 84352 / 1687040 | 12.099 / 28.164 / 36.896 / 62.586 / 74.514 / 90.963 |
| APPLY_MARK_PRICE | 3840 / 3840 | 9.502 / 26.329 / 33.259 / 60.129 / 89.915 / 98.828 |

JFR普通下单/撤单/批下单/批撤单/标记价p99分别44.630/48.496/61.439/74.383/135.528ms。JMH端到端主分数plain30.095s/op、profile30.096s/op是一次完整workload调用时间，无重复样本置信区间；不能当业务吞吐。

热点及分配（按steady epoch窗口过滤）：

- Owner1069个执行样本、平均91.45%单核；Matcher95个样本、60.11%单核。Lane92.15%–92.19%单核而实际执行约33%，不能把busy-spin当业务饱和。客户端发压线程92.80%单核，仍以等待结果的轮询为主；未完全排除发压端/网络限制。
- `CommandResultLedger.locate`自耗5/1069=0.47%，`storeOwnedResult`包含7个样本，查找不再占据Owner主要自耗；原find/findInsert和deleted状态已从生产代码删除。
- 剩余：`finishOrderBatch`包含190/1069=17.77%，`collectMatcherSettlement`181、`LaneDelta.commitTerminalToOwner`141、`LanePublication.publish`120、`LanePublishedMap.applyPublished`71。包含栈重叠且仅展开8层，不能相加或与不同深度旧采样直接比较。Owner自耗主要Long2ObjectHashMap.getMapped65、Arrays.fill46、TerminalTombstoneStore.bucket40、decodePlaceOrder40；不能宣称批收尾已消失。下一步应检查终态索引/变更清理的重复遍历和发布所需数据，保留有消费者的状态。
- Core分配459.95 MB/s、**2057.84 B/business op**；线程为Matcher110.12、Owner68.16、每Lane64.45–64.52、clustered-service23.77 MB/s。客户端fork单独275.65 MB/s、1233.27 B/op。统计方法为测量窗内各线程ThreadAllocationStatistics首尾累计差/间隔后求和；不是精确对象数/op。分配仍主要OrderRuntime、byte[]、ReservationRuntime、long[]、MatcherResult、ResolvedPlaceOrder、Long；站点为classify、decodePlaceOrder、preparedOrder、ArrayList.grow、批响应编码。没有证据证明本批显著降低每笔分配。
- Core45次GC、暂停合计258.21ms、最大19.40ms；客户端105次、90.90ms、最大2.69ms。Core测量窗仍69次Compilation（合计2353.79ms）、client71次；Owner有2次JAR类加载读取（共16.512µs），无测量窗文件写/Socket IO。说明30秒预热未跨过全部JIT/类加载，按严格无Owner同步IO要求也不能通过完整主链路验收。GC暂停可影响尾延迟，但未逐请求对齐，不能解释为唯一原因。
- 系统：profile窗口内13个约2s间隔ps/vm_stat样本，Core平均894.66%CPU、client355.80%、IDEA4.10%、WindowServer19.38%；Core峰值RSS1,899,080KiB、client528,952KiB，swapins/swapouts=0。plain缺系统时间序列，不能据此排除宿主干扰；profile系统采样由独立ps/vm_stat采集，非资格脚本自带。
- NMT基线→结束（含初始化）：plain reserved3,140,674KB(+21,272)、committed707,662KB(+38,904)；profile reserved3,165,180KB(+19,702)、committed733,504KB(+38,238)。未做长稳、live-set增长斜率/Direct buffer峰值、上下文切换、热节流和逐秒各阶段队列深度，不能证明无泄漏或给生产配置。
- 三份原始JFR DataLoss均0。Core全录制ExecutionSample19321、ObjectAllocationSample52971，client fork ExecutionSample3256、ObjectAllocationSample29469。jfr summary及CPU/hot-methods/allocations/contention/GC/safepoint views已执行；聚合视图覆盖全录制，热点/分配/GC结论使用测量窗口JSON。展开全量客户端park(2570553次)/Core文件写(664094次)过重，停止了中间分析工具，最终排除高频IO/park事件并限制栈深8；另用jfr scrub筛选Owner IO后按窗口检查。原始采集未中断，无效中间解析不作性能证据。park的精确窗口分布仍是缺口。

局部JMH结果（1fork、3×1s测量，99.9%CI）：plain保留+查询28,012,172.553±6,925,327.449次/s；独立gc轮28,271,216.088±1,724,669.214次/s、72.000B/op、1940.698MiB/s、GC61次/37ms。它只覆盖结果保留周转，ID/响应预建，不代表下单分配或Core吞吐；短微基准置信区间较宽。完整命令在上述计划基础上：`java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main CommandResultLedgerBenchmark -wi 3 -w 1s -i 3 -r 1s -f 1 -t 1 -jvmArgs '-Xms128m -Xmx512m -XX:+UseG1GC' -rf json -rff /tmp/owner-simplify-20260915-jmh/plain.json`，gc轮换文件名并加`-prof gc`。

未测真实节点重启/Archive恢复、外围Kafka/WebSocket服务及GCP；资金/恢复正确性依据本轮service/基准业务测试和真实短测资金核对。未为30万/5ms目标宣称验收通过。

生产/驱动跟踪文件diff SHA-256：`ed48e879c0022d0c906056622d2f95b423d107d097936a04db0a9ffb2e2e06b8`；新增微基准源码SHA-256：`89d68cca94413ce2013edf6fcbc0e6dadfb3d89b6d7d34e0cb3f65445dbdffc4`。

原始关键证据（清理后仅作历史定位）：

| 路径 | 字节 | SHA-256 |
|---|---:|---|
| /tmp/aeron-cleanup-20260915-plain/window-256/end_to_end/metrics.json | 3129 | `7e932521e1e27b62ad40b11ee570a331b72ff1f38bc0e41973ab432d7d498ce2` |
| /tmp/aeron-cleanup-20260915-plain/window-256/end_to_end/client.log | 8212 | `8c28ab2ceb7648cc7ef95b0ddb3fdd47cbaa9f7afcb38f289a1d0049b7a2c89b` |
| /tmp/aeron-cleanup-20260915-profile/window-256/end_to_end/node.jfr | 112329837 | `d5f5b31adc55a43700b76dcba5e6db61514935548f821b2e7d4ee66503346d6c` |
| /tmp/aeron-cleanup-20260915-profile/window-256/end_to_end/client-65141.jfr | 7369097 | `42ca546da01fd19a7daad9c3f99921e6188ab0e4f8514c09d0ed88467a9c1dbe` |
| /tmp/aeron-cleanup-20260915-profile/window-256/end_to_end/client-65144.jfr | 86798360 | `7a98b63a0f287f32fd9b96996fb36fe4904de2478c98060bcacf430eaa2bc65a` |
| /tmp/aeron-cleanup-20260915-profile/window-256/end_to_end/window-analysis.json | 81257 | `132695881e383c8f822a47d097dfb249971fee925425d16c4290c36231c7acec` |
| /tmp/aeron-cleanup-20260915-profile/window-256/end_to_end/owner-focused.json | 1379 | `1cb3cba83402e22cb8ae1cef31c51bd9c1dcf3d88be987ae55dfed48d9756bb7` |
| /tmp/aeron-cleanup-20260915-profile/window-256/end_to_end/owner-window-io.json | 18289 | `f931a8fcadb5ef9ba66e1df8dbc911da4f852d41604ec7608dc780b330f00df0` |
| /tmp/owner-simplify-20260915-jmh/plain.json | 1833 | `2a45805c4163e932af242130263c0e5a028470f27343a510d3c7ab9647284671` |
| /tmp/owner-simplify-20260915-jmh/gc.json | 5716 | `f4931b00cced55a1465407a02e0fa77d4a4dbf9214735130ebe426277415b823` |

清理清单：386个本轮生成文件，路径/大小/摘要清单SHA-256 `92f9dedf090caa0bb8995278a0d40e99f2721ed30ecdbeb59e70888264159540`。plain测量20:18:07.908起、profile20:20:03.056起（北京时间），JFR Core PID65124、runner65141、fork65144；profile持续30.023秒。所有本轮压测、微基准、采样和分析JVM已退出。
清理完成：删除本轮临时data/Archive/media、JFR、日志、分析脚本及本轮测试报告；上述原始路径不再可访问。构建产物保留，未创建云资源。


## 2026-09-15：Owner 发布/持仓变更缓冲清理（计划）

- master基础92f4c4f2，未提交改动：账户/冻结发布与引用释放合为一次遍历；持仓预计算删除值用单槽哨兵表示，删除present数组；Owner变更查找单次探测；结算发布复用静态空数组，不预分配准入专用数组；删除生产无调用的drainToAgronaMap、putKnownAbsent。终态客户号索引有重复订单校验消费者，保留。对照commit不适用（仅验证当前master）。
- 正确性先行：完整service及六产品线ClusteredBatchTradingBenchmarkTest；验证发布隔离、删除/覆盖、缓冲复用、资金和快照恢复。新增/更新测试覆盖预计算删除哨兵的释放、发布后缓冲清空；不得改变拒单/保留窗口语义。
- 性能为短时探索：参考吞吐>=300000业务ops/s、普通下单p99<=5ms；错误/超时=0，offered=terminal且unfinished=0、资金差=0。未做长稳、真实Archive重启、完整外围服务，不作为完整容量验收。
- 环境固定：i9-9880H8C16T/16GiB、macOS26.7、HotSpot GraalVM25.0.1/Maven3.9.16；真实单节点Aeron、1Matcher/4Lane、BUSY_SPIN、128symbols、1000retail/1385总用户、LINEAR_PERPETUAL、MIXED batch20、seed25620、global/session/Owner窗口256；G1，Core512m/1536m、client128m/512m。沿用业务配比、做市和行情，不改发压策略。30s预热+30s测量、plain/profile各1次；稳定窗口与排空分开，客户端窗口等待单列。IDE等宿主程序保留，短预热JIT稳定性另核查。
- 命令：`ASYNC_RUN_ID=20260915-clean2-plain ASYNC_ARTIFACT_DIR=/tmp/aeron-clean2-plain ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；profile改后缀，设`ASYNC_SKIP_BUILD=true ASYNC_ENABLE_JFR=true`。JFR沿用owner-commit-profile.jfc、每JVM256MiB上限；线程CPU/分配1s、ExecutionSample20ms，GC/IO/锁/NMT；原始事件按测量epoch窗口过滤。独立ps/vm_stat每2s采样。
- 新增OwnerPublicationBenchmark：20实体账户发布及持仓变更交接/查询两条真实局部路径；1fork/1thread，3×1s预热+3×1s测量；plain和单独-prof gc，G1/-Xms128m/-Xmx512m。响应/用户预构造，仅计缓冲操作，不换算交易吞吐。命令：`java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main OwnerPublicationBenchmark -wi 3 -w 1s -i 3 -r 1s -f 1 -t 1 -jvmArgs '-Xms128m -Xmx512m -XX:+UseG1GC' -rf json -rff /tmp/owner-clean2-jmh/plain.json`；gc轮改输出并加`-prof gc`。

### 本轮结果

正确性通过：1258项最新有效测试，失败/错误/跳过=0（service953、六产品线批业务驱动125、protocol107、client49、公共模块24）。发布隔离、预计算删除、覆盖/复用、资金及快照恢复断言通过；Maven构建和diff检查通过。性能为部分验证，未达到30万业务ops/s及普通下单p99<=5ms参考线，不推断生产容量或历史回退幅度。

| 指标 | plain | JFR |
|---|---:|---:|
| 稳定business ops/s | 244550.745 | 245637.058 |
| 稳定Core messages/s | 23406.257 | 23510.474 |
| 稳定窗口秒数 | 30.033722 | 30.032317 |
| 排空纳秒 | 6841984 | 9599084 |
| 排空业务项 | 2687 | 2684 |
| 峰值在途 | 256 | 256 |
| Lane实际执行均值 | 0.3287506445774129 | 0.3322473753371036 |

plain：客户端窗口等待26.538s，占测量阶段88.36%；
`steadyCapacity elapsedSeconds=30.033722 terminalBusinessOperations=7344769 terminalCoreMessages=702977 fills=1748480 businessOpsPerSec=244550.745 coreMessagesPerSec=23406.257 fillsPerSec=58217.227 peakInFlight=256 windowBlockedCount=231681 windowBlockedNanos=26538000498`

`mixedCapacity=PASS elapsedSeconds=30.041 terminalBusinessOperations=7347456 offeredBusinessOperations=7347456 terminalCoreMessages=703232 offeredCoreMessages=703232 businessOpsPerSec=244584.493 coreMessagesPerSec=23409.414 fills=1748480 fillsPerSec=58203.968 queries=0 unfinished=0 peakInFlight=256 measuredCycles=683 totalCycles=1370 triggerExecutions=0`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1370 businessHash=7b90844c59f43efd`

10秒区间：239667.223, 242937.095, 250967.945 business ops/s。

| 业务 | requests / items | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---|
| PLACE_ORDER | 174848 / 174848 | 8.822 / 12.533 / 13.418 / 18.743 / 29.294 / 45.023 |
| CANCEL_ORDER | 174848 / 174848 | 9.134 / 11.616 / 12.181 / 17.317 / 38.174 / 60.293 |
| APPLY_MARK_PRICE | 3840 / 3840 | 8.364 / 13.303 / 15.532 / 19.628 / 30.490 / 30.801 |
| PLACE_ORDER_BATCH | 262272 / 5245440 | 11.075 / 16.744 / 17.694 / 22.577 / 44.171 / 60.227 |
| CANCEL_ORDER_BATCH | 87424 / 1748480 | 15.245 / 17.711 / 19.021 / 32.817 / 50.921 / 58.032 |

系统采样：14个约2秒间隔样本；{'205': {'cpuAvg': 12.29, 'rssMaxKiB': 71384, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '721': {'cpuAvg': 2.02, 'rssMaxKiB': 2321144, 'exe': '/Applications/IntelliJ IDEA.app/Contents/MacOS/idea'}, '71945': {'cpuAvg': 959.62, 'rssMaxKiB': 1967052, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '71971': {'cpuAvg': 0.0, 'rssMaxKiB': 91832, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '71976': {'cpuAvg': 369.69, 'rssMaxKiB': 474640, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。
结束swap统计：['Swapins:                                     2536329.', 'Swapouts:                                    5867575.']。
NMT基线→结束（含初始化）：Total: reserved=3148500KB +31384KB, committed=707680KB +39120KB。
测量epoch秒：[1789476102.769, 1789476132.803]。

profile：客户端窗口等待26.073s，占测量阶段86.82%；
`steadyCapacity elapsedSeconds=30.032317 terminalBusinessOperations=7377050 terminalCoreMessages=706074 fills=1756160 businessOpsPerSec=245637.058 coreMessagesPerSec=23510.474 fillsPerSec=58475.675 peakInFlight=256 windowBlockedCount=226901 windowBlockedNanos=26072888747`

`mixedCapacity=PASS elapsedSeconds=30.042 terminalBusinessOperations=7379734 offeredBusinessOperations=7379734 terminalCoreMessages=706326 offeredCoreMessages=706326 businessOpsPerSec=245647.913 coreMessagesPerSec=23511.350 fills=1756160 fillsPerSec=58456.990 queries=0 unfinished=0 peakInFlight=256 measuredCycles=686 totalCycles=1341 triggerExecutions=0`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1341 businessHash=5b8e88028879f02`

10秒区间：244099.433, 254490.395, 238147.001 business ops/s。

| 业务 | requests / items | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---|
| PLACE_ORDER | 175616 / 175616 | 8.732 / 12.386 / 13.295 / 19.955 / 50.790 / 61.145 |
| CANCEL_ORDER | 175616 / 175616 | 9.191 / 11.460 / 12.140 / 21.004 / 52.396 / 57.606 |
| APPLY_MARK_PRICE | 3862 / 3862 | 7.958 / 12.599 / 15.409 / 32.636 / 58.032 / 58.589 |
| PLACE_ORDER_BATCH | 263424 / 5268480 | 10.993 / 16.490 / 17.465 / 24.543 / 60.555 / 68.485 |
| CANCEL_ORDER_BATCH | 87808 / 1756160 | 14.868 / 17.285 / 18.890 / 34.603 / 65.372 / 68.485 |

系统采样：14个约2秒间隔样本；{'205': {'cpuAvg': 15.41, 'rssMaxKiB': 73700, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '721': {'cpuAvg': 1.89, 'rssMaxKiB': 2328304, 'exe': '/Applications/IntelliJ IDEA.app/Contents/MacOS/idea'}, '72605': {'cpuAvg': 962.06, 'rssMaxKiB': 1912428, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '72622': {'cpuAvg': 0.36, 'rssMaxKiB': 117788, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '72627': {'cpuAvg': 376.3, 'rssMaxKiB': 543120, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。
结束swap统计：['Swapins:                                     2541560.', 'Swapouts:                                    5867575.']。
NMT基线→结束（含初始化）：Total: reserved=3172737KB +25140KB, committed=733829KB +38444KB。
测量epoch秒：[1789476208.945, 1789476238.978]。

JFR测量窗口归因（执行栈最多8层，包含栈不可相加）：

- Core：分配501.44MB/s，2041.38B/business op；线程累计分配首尾差/间隔后求和。GC/JIT：{'jdk.Compilation': {'count': 68, 'sum': 1344.520357, 'max': 128.23763499999998}, 'jdk.GCPhasePause': {'count': 50, 'sum': 266.666194, 'max': 7.540212}}。
  heapUsed最大417223632, committed最大536870912字节；GC后used范围[110701792, 114381488]，短轮不证明live-set稳定。
- client-72627.jfr：分配300.78MB/s，1224.50B/business op；线程累计分配首尾差/间隔后求和。GC/JIT：{'jdk.Compilation': {'count': 67, 'sum': 1038.720179, 'max': 324.5037}, 'jdk.GCPhasePause': {'count': 116, 'sum': 97.03769, 'max': 1.37883}}。
  heapUsed最大93124048, committed最大136314880字节；GC后used范围[13874632, 14480848]，短轮不证明live-set稳定。
- Owner 97.02%单核/1019执行样本，Matcher 63.77%/90样本；Lane CPU高但实际业务占比见表，不能宣称下游饱和。Owner自耗前8：[['java.util.Arrays.fill', 56], ['org.agrona.collections.Long2ObjectHashMap.getMapped', 49], ['com.surprising.aeron.service.orchestration.TerminalTombstoneStore.bucket', 32], ['org.agrona.collections.Long2ObjectHashMap.put', 30], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 29], ['org.agrona.collections.Long2LongHashMap.get', 20], ['com.surprising.aeron.service.state.MatcherSettlementPlan.clearReferences', 19], ['java.util.HashMap.getNode', 19]]。
- Owner清理路径样本：{'trading-owner--1': {'com.surprising.aeron.service.state.LanePublication.lambda$publish$eccbf966$2': {'self': 0, 'inclusive': 15}, 'com.surprising.aeron.service.state.LanePublication.publish': {'self': 18, 'inclusive': 133}, 'com.surprising.aeron.service.state.TradingRuntimeState.applyLanePublication': {'self': 1, 'inclusive': 134}, 'com.surprising.aeron.service.state.LanePublication$$Lambda.0x00000001231c6f50.value': {'self': 11, 'inclusive': 26}, 'java.util.Arrays.fill': {'self': 56, 'inclusive': 56}, 'com.surprising.aeron.service.state.LanePublication$$Lambda.0x00000001231c6d20.value': {'self': 12, 'inclusive': 42}, 'com.surprising.aeron.service.state.LanePublication.lambda$publish$eccbf966$1': {'self': 1, 'inclusive': 30}, 'com.surprising.aeron.service.state.TradingRuntimeState.clearChangedKeys': {'self': 12, 'inclusive': 35}, 'com.surprising.aeron.service.state.TreasuryRuntime.clearChangedKeys': {'self': 0, 'inclusive': 10}, 'com.surprising.aeron.service.state.LanePublication.clear': {'self': 0, 'inclusive': 3}, 'com.surprising.aeron.service.state.OwnerIndexedChanges.forEach': {'self': 2, 'inclusive': 11}, 'com.surprising.aeron.service.state.LanePublication.lambda$publish$9fa9bb61$1': {'self': 3, 'inclusive': 5}, 'com.surprising.aeron.service.state.LanePublication$$Lambda.0x00000001231c6af0.value': {'self': 2, 'inclusive': 7}, 'com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed': {'self': 0, 'inclusive': 2}, 'com.surprising.aeron.service.state.OwnerIndexedChanges.forEachIndexed': {'self': 0, 'inclusive': 2}, 'com.surprising.aeron.service.state.OwnerIndexedChanges.clear': {'self': 1, 'inclusive': 8}, 'com.surprising.aeron.service.state.OwnerIndexedChanges.get': {'self': 0, 'inclusive': 1}, 'com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.clear': {'self': 3, 'inclusive': 7}, 'com.surprising.aeron.service.state.LanePublication$$Lambda.0x00000001231c7180.value': {'self': 1, 'inclusive': 1}}}；包含热点前12：[['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommandsInScope', 400], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatching', 339], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommands', 316], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.commitReadyMatching', 282], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.tryMatchingCommit', 274], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommands', 248], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommandPrefix', 236], ['com.surprising.aeron.service.orchestration.OrderBatchExecutor.finishOrderBatch', 200], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 193], ['com.surprising.aeron.service.orchestration.ContinuousTradingClusterService.runOwner', 167], ['com.surprising.aeron.service.state.TradingRuntimeState$LaneDelta.commitTerminalToOwner', 142], ['com.surprising.aeron.service.orchestration.TradingCoreRuntime.apply', 138]]。不能用单次采样比例证明每项优化的因果收益，下一步只针对仍有样本和消费者证据的终态维护/发布路径。
- Core分配类：[['com/surprising/aeron/service/state/OrderRuntime', 2482865672], ['[B', 1930471768], ['[J', 1087218752], ['com/surprising/aeron/service/state/ReservationRuntime', 996752048], ['exchange/core2/core/common/MatcherResult', 775592712], ['com/surprising/aeron/service/state/ResolvedPlaceOrder', 644635944], ['com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand', 504162800], ['java/lang/Long', 490842072]]；站点：[['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 1086431096], ['com.surprising.aeron.service.state.TradingRuntimeState.preparedOrder', 964059248], ['com.surprising.aeron.service.matching.CoreMatchingResult.classify', 925923992], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource', 803965520], ['java.util.List.copyOf', 759359064], ['com.surprising.aeron.service.state.model.AssetBalance.validAsset', 609652376], ['com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order', 604234400], ['org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get', 584287968]]（数字为ObjectAllocationSample权重字节，不是精确对象数）。线程MB/s：{'clustered-service-101-0': 25.93, 'trading-owner--1': 73.49, 'core-matcher-0': 122.09, 'core-account-lane-0': 69.93, 'core-account-lane-1': 69.91, 'core-account-lane-2': 70.05, 'core-account-lane-3': 70.04}。
- JFR各原始录制DataLoss：{'client-72627.jfr': 0, 'client-72622.jfr': 0, 'node.jfr': 0}；已执行summary及线程CPU、热点、分配、contention、GC、safepoints视图。聚合views覆盖全录制；上述热点/分配/GC以epoch测量窗口JSON为准。高频park/IO未全量展开，不能声称无等待或无Owner同步IO。

局部JMH（每次操作20实体，1fork/3样本，误差为99.9%CI；不代表交易ops）：

| 场景 | plain 次/s ±误差 | gc 次/s | 分配B/次 | gc次数 / ms |
|---|---:|---:|---:|---:|
| handoffAndQueryPreparedChanges | 5986893.234 ± 652171.438 | 6180098.468 | 0.001 | 0.0 / 未发生 |
| publishUsers | 4019695.863 ± 1026725.733 | 3941872.716 | 0.002 | 0.0 / 未发生 |

限制：延迟为客户端commandAsync调用前→终态，含排空请求、不含窗口等待；未校正coordinated omission、无独立accepted速率/分段延迟；256窗口已背压。短轮JIT/同机干扰限制因果判断，无重复轮置信区间。未做长稳/热节流/上下文切换/Direct峰值/逐秒各阶段队列趋势、真实Archive重启、外围Kafka/WebSocket全链路或GCP；恢复依据本轮测试，不能据此认定完整容量或无泄漏。本批改变工作缓冲消费方式，没有改变终态长期保留规则。

service改动diff SHA-256：`ac19ff5ff958702596e2577c01c51bb7d38d9699e36dae98b486961f92822114`。关键原始证据（清理后仅历史定位）：

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| /tmp/aeron-clean2-plain/window-256/end_to_end/metrics.json | 3126 | `a986e783011ac3ace86a2bc9b84b9328d488708d6ae809ff80aa07378138613d` |
| /tmp/aeron-clean2-plain/window-256/end_to_end/client.log | 8192 | `d41e4f1c9e0b69462813b2676c4f1a1e9256daa85cef1a06fe80377c77707627` |
| /tmp/aeron-clean2-profile/window-256/end_to_end/node.jfr | 119065766 | `40d31e80b752f1b9a09a6cca42dd30ce1d13441ed6aac8b1dbcfc3d1d54e143c` |
| /tmp/aeron-clean2-profile/window-256/end_to_end/client-72627.jfr | 94296860 | `47dc74dd65f555c40338dfc2f9e924d929a24c6405731b9ea0ff0655bfe4239e` |
| /tmp/aeron-clean2-profile/window-256/end_to_end/analysis.json | 107250 | `115221f62ab6e53fd34e2a3bbd54e92dac8faf2d103dc6c0edd1e77b693d0314` |
| /tmp/owner-clean2-jmh/plain.json | 3577 | `c3b23cd8bb3481ffd6ffa4394a81acfa4c104828713ab8daf66347dd1b145c78` |
| /tmp/owner-clean2-jmh/gc.json | 9796 | `eaf7c911624f5a65451577df97973df0a426d5c26a87d1510c8446f923cbe29c` |

补充定位：Owner Arrays.fill自耗56个样本中，LaneDelta回收时LongHashSet.clear路径10个，TreasuryRuntime清空IntObjectHashMap路径9个，MatcherSettlementEvent.BatchStorage.clear路径8个，OrderBatchPending.clear两条路径合计11个。剩余清空热点不只是已删除的prepared标记；下一步应核查这些容器是否空表仍扫描容量，以及哪些重复字段可以退出事件生命周期。
单独按Owner线程过滤IO再对齐测量窗口：[('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000008107S'), ('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000002549S')]；空列表表示本轮已记录的测量窗无Owner文件/Socket IO事件，不覆盖低于阈值或未启用事件。
补充证据 `/tmp/aeron-clean2-profile/window-256/end_to_end/array-clear-stacks.json`，5823字节，SHA-256 `26e6e3d797a939ea2e70ea5907f49f68c328b81c1559f3bac756f64772b2ade3`。
补充证据 `/tmp/aeron-clean2-profile/window-256/end_to_end/owner-window-io.json`，18290字节，SHA-256 `b316d44a0cc67e86a125faf525ed2cd86a8b8c691a0abf3003f27bffeb12be27`。

两次Owner文件读取栈均为BuiltinClassLoader加载JAR，总计10.656µs；测量期仍有类加载，严格Owner无同步IO/完全预热门槛未通过。

清理完成：本轮JVM、系统采样、分析进程均已退出；删除本轮临时集群data/Archive/media、JFR、日志、分析脚本及测试报告，共391个文件。路径/大小/摘要清单SHA-256 `0ef9ece824e396f19e5036e226ca301708bd92f8f7bf648df5a4b843f8f2d1e8`。上述临时路径仅历史定位，构建产物保留，未创建云资源。


## 2026-09-15：消除准入订单时间戳中间版本（计划）

- 当前master基础05eec0e4；对照commit不适用（仅验证当前master）。改动：普通/批量/替换订单首次构造即携带本命令的日志时间和位置；删除两个仅转发的准入方法及无调用的批准入重载。未成交的新订单在完成准入冻结时显式发布，避免依赖重新构造订单触发发布。释放/消耗为0、剩余冻结不变及成交计算未改变消耗量时复用原不可变ReservationRuntime。保持实际变更版本、资金/排序/去重/恢复边界，不引入对象池或可变跨线程实体。
- 假设：减少无成交订单的整份metadata复制及无变化冻结对象；参考持续业务吞吐>=300000/s、下单p99<=5ms、错误/超时=0，offered=terminal、unfinished=0、资金差=0。短轮诊断，不验收生产容量、长稳或零分配。
- 正确性先行：首轮精确308项有160个错误，原因是批准入先于commit fence建立，错误读取fence时间；改读批命令已保存的clusterTimestamp/clusterPosition。修复后精确测试通过，增加六产品线普通/批量未成交订单准入→提交保持同一对象且串行/快照一致测试，以及冻结无变化复用和非法额度校验。完整service与ClusteredBatchTradingBenchmarkTest随后运行。失败不作为性能证据。
- 环境与负载保持固定：i9-9880H8C16T/16GiB/macOS26.7、HotSpot GraalVM25.0.1/Maven3.9.16；真实单成员Aeron、LINEAR_PERPETUAL、1Matcher/4Lane、BUSY_SPIN、128symbols、1000retail/1385总用户、MIXED batch20、seed25620、全局/session/Owner窗口256。G1、Core512m/1536m、client128m/512m。不改业务配比、做市或发压策略；30s预热+30s稳定测量，plain和JFR各1次，排空单列。IDE等宿主程序保留；检查JIT稳定性与采样有效性，不把高busy-spin CPU当业务饱和。
- 命令：`ASYNC_RUN_ID=20260915-alloc3-plain ASYNC_ARTIFACT_DIR=/tmp/core-alloc3-plain ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；profile轮换后缀，加`ASYNC_SKIP_BUILD=true ASYNC_ENABLE_JFR=true`。JFR使用owner-commit-profile.jfc，每进程独立文件、最大256MiB；ExecutionSample20ms、线程CPU/分配统计1s、GC/锁/IO/NMT，独立ps/vm_stat每2s。按测量epoch归因，不全量展开百万级park/IO事件。
- 新增OrderAdmissionAllocationBenchmark.admitWithFinalMetadata，执行实际preparedOrder构造和无变化冻结更新，输入与UUID预建；1fork/1thread，3×1s预热+3×1s测量，plain与独立-prof gc，G1/-Xms128m/-Xmx512m。仅表示一份订单版本的构造成本，不代表交易ops或全部分配。命令：`java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main OrderAdmissionAllocationBenchmark -wi 3 -w 1s -i 3 -r 1s -f 1 -t 1 -jvmArgs '-Xms128m -Xmx512m -XX:+UseG1GC' -rf json -rff /tmp/core-alloc3-jmh/plain.json`，gc轮改输出并加`-prof gc`。


### 本轮结果：准入中间版本清理

正确性：1265项全部通过（service960、基准驱动125、protocol107、client49、product/instrument各12）；六产品线普通/批量订单身份复用、资金/冻结、串行一致性及快照恢复均通过。未改业务配比、窗口或线程数。


**plain（测量epoch秒 [1789477541.651, 1789477571.652]）**

`progress terminalBusinessOps=2810152 intervalBusinessOpsPerSec=281012.527 requestsInFlight=254`

`progress terminalBusinessOps=5558501 intervalBusinessOpsPerSec=274834.897 requestsInFlight=256`

`progress terminalBusinessOps=8204855 intervalBusinessOpsPerSec=264635.398 requestsInFlight=256`

`steadyCapacity elapsedSeconds=30.001595 terminalBusinessOperations=8204930 terminalCoreMessages=784898 fills=1953280 businessOpsPerSec=273483.126 coreMessagesPerSec=26161.876 fillsPerSec=65105.872 peakInFlight=256 windowBlockedCount=241169 windowBlockedNanos=26569949219`

`drain elapsedNanos=9030838 terminalBusinessOperations=2686 terminalCoreMessages=254 fills=0`

`pipelineHighWater matcher=123 completion=65 context=169 lanes=[41, 37, 42, 42]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1513 businessHash=3d9582f35d1bb4dd`

`mixedCapacity=PASS elapsedSeconds=30.011 terminalBusinessOperations=8207616 offeredBusinessOperations=8207616 terminalCoreMessages=785152 offeredCoreMessages=785152 businessOpsPerSec=273490.331 coreMessagesPerSec=26162.467 fills=1953280 fillsPerSec=65086.280 queries=0 unfinished=0 peakInFlight=256 measuredCycles=763 totalCycles=1513 triggerExecutions=0`

窗口阻塞占测量时间：88.56%；Lane业务执行占比（含排空）：[32.06, 32.14, 32.12, 31.82]。

| 业务 | requests / items | requests/s（含排空） | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---|
| PLACE_ORDER | 195328 / 195328 | 6508.55 | 7.983 / 11.304 / 12.230 / 16.203 / 23.347 / 30.588 |
| CANCEL_ORDER | 195328 / 195328 | 6508.55 | 8.085 / 10.657 / 11.214 / 16.220 / 21.676 / 25.001 |
| APPLY_MARK_PRICE | 3840 / 3840 | 127.95 | 7.679 / 13.115 / 14.729 / 16.793 / 18.219 / 18.399 |
| PLACE_ORDER_BATCH | 292992 / 5859840 | 9762.82 | 10.059 / 14.974 / 15.958 / 20.611 / 34.373 / 44.335 |
| CANCEL_ORDER_BATCH | 97664 / 1953280 | 3254.27 | 13.533 / 16.039 / 17.793 / 26.542 / 36.929 / 44.236 |

批量平均/最大20items/batch；items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 13.32, 'rssMaxKiB': 80536, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '79341': {'cpuAvg': 943.28, 'rssMaxKiB': 1910508, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '79348': {'cpuAvg': 0.0, 'rssMaxKiB': 91156, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '79357': {'cpuAvg': 370.11, 'rssMaxKiB': 508500, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap累计计数：{'Swapins': 2820541, 'Swapouts': 6118036} → {'Swapins': 2820902, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3148118KB +33210KB, committed=707034KB +38790KB。


**profile（测量epoch秒 [1789477648.028, 1789477678.024]）**

`progress terminalBusinessOps=2482391 intervalBusinessOpsPerSec=248236.211 requestsInFlight=239`

`progress terminalBusinessOps=4928116 intervalBusinessOpsPerSec=244570.628 requestsInFlight=256`

`progress terminalBusinessOps=7312440 intervalBusinessOpsPerSec=238432.397 requestsInFlight=256`

`steadyCapacity elapsedSeconds=30.002238 terminalBusinessOperations=7312539 terminalCoreMessages=699931 fills=1740800 businessOpsPerSec=243733.116 coreMessagesPerSec=23329.293 fillsPerSec=58022.338 peakInFlight=256 windowBlockedCount=228088 windowBlockedNanos=26096830086`

`drain elapsedNanos=8718607 terminalBusinessOperations=2661 terminalCoreMessages=229 fills=0`

`pipelineHighWater matcher=128 completion=63 context=128 lanes=[38, 36, 38, 38]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1338 businessHash=50c7ac6433705da5`

`mixedCapacity=PASS elapsedSeconds=30.011 terminalBusinessOperations=7315200 offeredBusinessOperations=7315200 terminalCoreMessages=700160 offeredCoreMessages=700160 businessOpsPerSec=243750.976 coreMessagesPerSec=23330.146 fills=1740800 fillsPerSec=58005.482 queries=0 unfinished=0 peakInFlight=256 measuredCycles=680 totalCycles=1338 triggerExecutions=0`

窗口阻塞占测量时间：86.98%；Lane业务执行占比（含排空）：[32.86, 33.19, 32.77, 32.49]。

| 业务 | requests / items | requests/s（含排空） | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---|
| PLACE_ORDER | 174080 / 174080 | 5800.54 | 9.019 / 12.869 / 13.770 / 19.234 / 27.623 / 32.079 |
| CANCEL_ORDER | 174080 / 174080 | 5800.54 | 9.224 / 11.575 / 12.140 / 16.252 / 30.130 / 32.112 |
| APPLY_MARK_PRICE | 3840 / 3840 | 127.95 | 8.429 / 14.303 / 15.876 / 22.413 / 28.377 / 29.917 |
| PLACE_ORDER_BATCH | 261120 / 5222400 | 8700.81 | 11.247 / 16.564 / 17.580 / 23.429 / 37.683 / 50.003 |
| CANCEL_ORDER_BATCH | 87040 / 1740800 | 2900.27 | 15.220 / 18.038 / 21.331 / 33.390 / 39.780 / 49.381 |

批量平均/最大20items/batch；items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 14.2, 'rssMaxKiB': 80560, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '79973': {'cpuAvg': 961.44, 'rssMaxKiB': 1879572, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '79986': {'cpuAvg': 0.47, 'rssMaxKiB': 118300, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '79989': {'cpuAvg': 374.51, 'rssMaxKiB': 562344, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap累计计数：{'Swapins': 2821153, 'Swapouts': 6118036} → {'Swapins': 2821629, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3172958KB +27459KB, committed=734058KB +38719KB。


JFR client-79986.jfr（按测量epoch窗口；ExecutionSample20ms/线程累计分配与CPU1s）：分配0.00MB/s，0.00B/business op；DataLoss全文件0。样本数{'jdk.Compilation': 15, 'jdk.ThreadCPULoad': 42, 'jdk.ThreadAllocationStatistics': 493, 'jdk.ExecutionSample': 1}。GC/JIT{'jdk.Compilation': {'count': 15, 'sumMs': 26.804782000000003, 'maxMs': 20.904138, 'p50Ms': 0.192688, 'p95Ms': 20.904138, 'p99Ms': 20.904138}}。


JFR node.jfr（按测量epoch窗口；ExecutionSample20ms/线程累计分配与CPU1s）：分配486.47MB/s，1995.92B/business op；DataLoss全文件0。样本数{'jdk.ObjectAllocationSample': 30555, 'jdk.ExecutionSample': 6180, 'jdk.Compilation': 55, 'jdk.ThreadCPULoad': 481, 'jdk.ThreadAllocationStatistics': 789, 'jdk.GCHeapSummary': 96, 'jdk.GCPhasePause': 48}。GC/JIT{'jdk.Compilation': {'count': 55, 'sumMs': 1557.7966600000002, 'maxMs': 220.866616, 'p50Ms': 12.245611, 'p95Ms': 125.324676, 'p99Ms': 220.866616}, 'jdk.GCPhasePause': {'count': 48, 'sumMs': 264.68450399999995, 'maxMs': 5.997973, 'p50Ms': 5.532179, 'p95Ms': 5.893766, 'p99Ms': 5.997973}}。

heapUsed最大417399552，committed最大536870912字节；GC后used范围[111215360, 114860016]。

线程分配MB/s：{'clustered-service-101-0': 25.74, 'trading-owner--1': 72.83, 'core-matcher-0': 121.02, 'core-account-lane-0': 66.75, 'core-account-lane-1': 66.62, 'core-account-lane-2': 66.75, 'core-account-lane-3': 66.75}。

单核CPU百分比：{'trading-owner--1': 98.44, 'core-matcher-0': 64.84, 'core-account-lane-0': 98.58, 'core-account-lane-1': 98.59, 'core-account-lane-2': 98.6, 'core-account-lane-3': 98.59}；执行样本数{'core-account-lane-1': 1182, 'core-account-lane-2': 1167, 'core-account-lane-3': 1141, 'driver-conductor': 88, 'consensus-module-101-0': 40, 'trading-owner--1': 1053, 'core-matcher-0': 96, 'core-account-lane-0': 1195, 'clustered-service-101-0': 174, 'archive-conductor': 29, '/tmp/core-alloc3-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]': 12, 'aeron-md-nra': 1, 'aeron-client': 1, 'JFR Periodic Tasks': 1}。

分配类（采样权重字节）：[['com/surprising/aeron/service/state/OrderRuntime', 2184087856], ['[B', 1949095608], ['com/surprising/aeron/service/state/ReservationRuntime', 999990872], ['[J', 965906824], ['exchange/core2/core/common/MatcherResult', 876335776], ['java/lang/Long', 800376120], ['com/surprising/aeron/service/state/ResolvedPlaceOrder', 597771960], ['com/surprising/aeron/service/matching/CoreMatchingResult', 452069528], ['com/surprising/aeron/protocol/PlaceOrderCommand', 428379664], ['[Ljava/lang/Object;', 425822392], ['com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand', 420034096], ['com/surprising/aeron/service/state/PositionRuntime', 322149440]]；站点：[['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 1082922768], ['com.surprising.aeron.service.state.TradingRuntimeState.preparedOrder', 982445160], ['exchange.core2.core.SynchronousMatchingEngine.execute', 876335776], ['com.surprising.aeron.service.matching.CoreMatchingResult.classify', 855872352], ['java.util.concurrent.ConcurrentHashMap.putVal', 838299112], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource', 802670648], ['com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order', 593835616], ['com.surprising.aeron.service.orchestration.CoreMessageFlyweightDecoder.decode', 589538600], ['org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get', 585987448], ['com.surprising.aeron.service.state.model.AssetBalance.validAsset', 559865144], ['com.surprising.aeron.service.state.BalanceRuntime.release', 452686344], ['com.surprising.aeron.service.state.OrderRuntime.withFill', 452229240], ['org.agrona.collections.Long2ObjectHashMap.put', 434710176], ['com.surprising.aeron.service.state.RuntimeTreasuryDelta.addClearing', 322149440], ['org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow', 302507328]]。样本权重不是精确对象数。

Owner自耗：[['org.agrona.collections.Long2ObjectHashMap.getMapped', 54], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 53], ['java.util.Arrays.fill', 42], ['com.surprising.aeron.service.orchestration.TerminalTombstoneStore.bucket', 34], ['org.agrona.collections.Long2ObjectHashMap.put', 28], ['org.agrona.collections.Long2ObjectHashMap.remove', 27], ['java.util.HashMap.getNode', 22], ['org.agrona.collections.Long2LongHashMap.get', 20], ['com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length', 20], ['com.surprising.aeron.service.state.MatcherSettlementPlan.clearReferences', 18], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommandsInScope', 16], ['com.surprising.aeron.service.state.LanePublication.publish', 15], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 15], ['org.agrona.collections.Long2LongHashMap.remove', 14], ['com.surprising.aeron.service.state.TradingRuntimeState.clearChangedKeys', 14]]；包含栈：[['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommandsInScope', 422], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommands', 347], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatching', 314], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommands', 282], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.commitReadyMatching', 250], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.tryMatchingCommit', 246], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommandPrefix', 219], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 178], ['com.surprising.aeron.service.orchestration.OrderBatchExecutor.finishOrderBatch', 174], ['com.surprising.aeron.service.orchestration.ContinuousTradingClusterService.runOwner', 168], ['com.surprising.aeron.service.state.TradingRuntimeState$LaneDelta.commitTerminalToOwner', 159], ['com.surprising.aeron.service.state.TradingRuntimeState.applyLanePublication', 154], ['com.surprising.aeron.service.state.LanePublication.publish', 154], ['com.surprising.aeron.service.orchestration.TradingCoreRuntime.apply', 141], ['com.surprising.aeron.service.orchestration.TradingCoreRuntime.prepareClusterPipelineScope', 124]]（栈最多8层，不可相加）。


JFR client-79989.jfr（按测量epoch窗口；ExecutionSample20ms/线程累计分配与CPU1s）：分配298.40MB/s，1224.27B/business op；DataLoss全文件0。样本数{'jdk.ObjectAllocationSample': 17497, 'jdk.GCHeapSummary': 230, 'jdk.GCPhasePause': 115, 'jdk.ExecutionSample': 1342, 'jdk.Compilation': 73, 'jdk.ThreadCPULoad': 350, 'jdk.ThreadAllocationStatistics': 629}。GC/JIT{'jdk.GCPhasePause': {'count': 115, 'sumMs': 98.53308399999997, 'maxMs': 1.3674659999999998, 'p50Ms': 0.845148, 'p95Ms': 1.0084, 'p99Ms': 1.2350480000000001}, 'jdk.Compilation': {'count': 73, 'sumMs': 1153.8153170000005, 'maxMs': 320.103527, 'p50Ms': 0.930466, 'p95Ms': 78.24896000000001, 'p99Ms': 320.103527}}。

heapUsed最大93232104，committed最大136314880字节；GC后used范围[12708784, 14588904]。


局部JMH：一次实际订单构造，输入预建，不能换算完整交易吞吐。1fork/3×1s；plain与独立gc轮：

plain：主分数{'score': 61630832.20921886, 'scoreError': 52625278.6615342, 'scoreConfidence': [9005553.547684662, 114256110.87075305], 'scoreUnit': 'ops/s'}；次级指标{}。

gc：主分数{'score': 63624893.007000946, 'scoreError': 16328642.821369747, 'scoreConfidence': [47296250.1856312, 79953535.82837069], 'scoreUnit': 'ops/s'}；次级指标{'gc.alloc.rate': {'score': 10676.211791620179, 'scoreUnit': 'MB/sec'}, 'gc.alloc.rate.norm': {'score': 176.0001090851686, 'scoreUnit': 'B/op'}, 'gc.count': {'score': 199.0, 'scoreUnit': 'counts'}, 'gc.time': {'score': 165.0, 'scoreUnit': 'ms'}}。


限制与结论：本轮为短轮部分验证，未达到30万/s及下单p99≤5ms目标；未做历史版本对照，单次吞吐不能证明改动因果收益。没有独立accepted速率和分段延迟、拒绝分类、连续队列深度/积压斜率、上下文切换/节流、Direct/Mapped峰值和长期增长、真实Archive重启或外围Kafka/WebSocket全链路证据。固定256窗口已背压，不能认定发压无限制或下游全部饱和。快照恢复来自正确性测试；短轮GC/JIT/native不证明无泄漏或预热完全。JFR分配以线程累计首尾差/时间求和，窗口边缘约1s误差；TLAB精确对象数不可用。GC/锁/IO/safepoint聚合视图覆盖全录制，窗口归因以analysis.json为准。


补充归因与验收限制：
- 无采样273483.126ops/s、JFR243733.116ops/s，采样轮低约10.88%，不能用采样分数替代主吞吐或认定差值全部来自profiler。JFR发压线程98.93%单核，也接近饱和，尚不能排除客户端成本；Owner98.44%/Matcher64.84%，Lane busy-spin约98.6%而业务执行占比远低于此。
- Owner仍有解码、索引探测/删除、缓冲清空与终态发布热点。下一步最小验证：沿TradingOrderBatchCodec.decodeCommand→ResolvedPlaceOrder检查重复字符串/请求字段物化；沿MatcherResult→结算检查可共享不可变结果；保留已发布旧版本，仅合并单命令内无人读取的中间版本。不能删除真实订单终态或账户变更。
- 实际GC暂停48次/264.68ms，占窗口0.882%，最大6.00ms已超过5ms目标；测量期仍有55次编译，未完全预热。局部JMH176B/次证明保留一份实际订单构造，无变化冻结更新未增加对象；不代表完整业务仅分配176B。
- 系统测量窗swap-in增加plain361页/profile476页，swap-out均无增长；未定位到具体进程，按统一标准不能将该轮当无环境干扰的性能验收，容量结论无效，仅保留已达吞吐和热点诊断。正确性测试结论不受此影响。
- Owner测量窗同步JAR读取：[('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000008698S'), ('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000002515S')]；两次共11.213µs，调用栈为BuiltinClassLoader类加载，严格Owner无同步IO门槛未通过。没有数据库调用证据不等于证明所有路径无IO。
- 全录制105s：ThreadPark2948829次（含启动/停机等待，不能当30s业务等待）；FileWrite783975次/11.8s累计，最长20.8ms；MonitorBlocked149次/23.1ms累计、最长0.680ms。未对全部IO/park展开窗口归因，不据此宣称锁是吞吐主因。

运行命令（实际记录）：

plain/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-alloc3-plain/window-256/end_to_end/data -Daeron.dir=/tmp/core-alloc3-plain/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-alloc3-plain/window-256/end_to_end/tmp -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

plain/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-alloc3-plain/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-alloc3-plain/window-256/end_to_end/jmh.json
```

profile/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-alloc3-profile/window-256/end_to_end/data -Daeron.dir=/tmp/core-alloc3-profile/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-alloc3-profile/window-256/end_to_end/tmp -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-alloc3-profile/window-256/end_to_end/node.jfr\,maxsize=256m\,dumponexit=true -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

profile/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-alloc3-profile/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-alloc3-profile/window-256/end_to_end/client-%p.jfr\,maxsize=256m\,dumponexit=true -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-alloc3-profile/window-256/end_to_end/jmh.json
```

NMT结束各类（reserved/committed，非测量峰值）：
```text
Total: reserved=3172961KB, committed=734065KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                     Class (reserved=1049236KB, committed=2708KB)
-                    Thread (reserved=71847KB, committed=2667KB)
-                      Code (reserved=261760KB, committed=49940KB)
-                        GC (reserved=92329KB, committed=71857KB)
-                 GCCardSet (reserved=16KB, committed=16KB)
-                  Compiler (reserved=413KB, committed=413KB)
-                     JVMCI (reserved=134KB, committed=134KB)
-                  Internal (reserved=1590KB, committed=1590KB)
-                     Other (reserved=9413KB, committed=9413KB)
-                    Symbol (reserved=4923KB, committed=4923KB)
-    Native Memory Tracking (reserved=3080KB, committed=3080KB)
-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
-               Arena Chunk (reserved=1803KB, committed=1803KB)
-                   Tracing (reserved=19841KB, committed=19841KB)
-                   Logging (reserved=0KB, committed=0KB)
-                    Module (reserved=295KB, committed=295KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1283KB, committed=1283KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65724KB, committed=25788KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB, committed=1KB)
```
service diff SHA-256：`a48d2dd6a963389438bb97a41d8106fcbea74f875cc5cf38888481c242dbc7e8`。

关键原始证据（清理后仅历史定位）：

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| /tmp/core-alloc3-plain/window-256/end_to_end/metrics.json | 3130 | `89ae9ce379d038a7f53ddf7934da920cbd7a99feafba33dbac8babb444a3dd40` |
| /tmp/core-alloc3-plain/window-256/end_to_end/client.log | 8190 | `980436ce83e55e42c489ef7d69704e217b9363144285186f9c8e679d21ae9f83` |
| /tmp/core-alloc3-profile/window-256/end_to_end/metrics.json | 3271 | `d4573173a9581b096b185ad845028672d91546d269b553ecfbb048003c02cb72` |
| /tmp/core-alloc3-profile/window-256/end_to_end/client.log | 8794 | `89abb23935663c5c3844cfd83339263ec0efe9e925b89190c416d9560e52336f` |
| /tmp/core-alloc3-profile/window-256/end_to_end/client-79986.jfr | 5873522 | `c1154751c1ae6a9cb21695066ab88d7ede5a2abcf25ac68a4b9799882b018f77` |
| /tmp/core-alloc3-profile/window-256/end_to_end/client-79989.jfr | 90863802 | `558ae9625aa840dabe63ebe4a2882ee97b51d83093a8b87236db0dd497c43959` |
| /tmp/core-alloc3-profile/window-256/end_to_end/node.jfr | 115481934 | `6092362d551e6dd9f73236e2edfb813b2dd95b3430beb43c6f8d25fd229f3091` |
| /tmp/core-alloc3-profile/window-256/end_to_end/analysis.json | 87059 | `1760f0f63b4bc3ff2bddcaf5cb6536ba121ed0e2179f3afadd7d5a0aaed36461` |
| /tmp/core-alloc3-profile/window-256/end_to_end/owner-window-io.json | 18286 | `5171c98d47d86406c3d8cf45ca684f244c02c9eec04ebec26316a489926d9d27` |
| /tmp/core-alloc3-profile/window-256/end_to_end/jfr-summary.txt | 12158 | `55882253fca147d45f44a23c4cfef200d0c62e64f8ff7fe7ac03a39ed4be672e` |
| /tmp/core-alloc3-jmh/plain.json | 1836 | `0f2d712c13c835196377c204a3bc1e96e299ad4496b53e87649782bb832551e6` |
| /tmp/core-alloc3-jmh/gc.json | 5722 | `e8f1506a96387397a75d49bbdbd3cb10f08c13428eb24d15b691c628f8d909c6` |
| /tmp/core-alloc3-full-tests.log | 214519 | `d96d110f563e69ac0686296bac3ececb59bcab978456059141b3be9d7d6b0d57` |
| /tmp/core-alloc3-bench-tests.log | 49747 | `db90797efc1f8f88826ae9bac03fad72320fcbf326976b4456294d6c86a88260` |

清理完成：本轮JVM/采样/分析均退出；删除临时Aeron集群、Archive、JFR、日志、分析脚本及本轮测试报告，共396个文件。路径/大小/摘要清单SHA-256 `a1785ff58d9e6ad456ecd7db694410f2e194395d46466f3945be975fcc3a1625`。构建产物保留；未创建云资源。

## 2026-09-15：命令解码临时数组清理（计划）

- 当前master基础4367268c；对照commit不适用（仅验证当前master）。改动仅TradingCommandCodec：必填/可选/转账文本直接从已校验报文区间构造String，删除中间byte[]；替换单使用已有offset/length解码入口，不复制嵌套报文；空clientOrderId共享空串。保留网络报文跨线程所有权、业务命令和校验，不新增生产类、缓存或处理阶段。预计主要减少控制/替换路径分配；MIXED以非空客户号下单/撤单为主，不预期仅此改动就明显提升主吞吐。
- 正确性：精确TradingCommandCodecTest/TradingOrderBatchCodecTest通过，新增UTF-8/空串/非零偏移/全截断/尾随字节/解码后输入覆写检查；随后完整service及ClusteredBatchTradingBenchmarkTest，涵盖共享协议依赖和六产品线资金、批序及恢复。
- 门槛：持续终态>=300000业务ops/s、下单p99<=5ms；错误/超时=0、offered=terminal、unfinished=0、资金差0。短轮仅诊断，环境swap/采样DataLoss或Owner同步IO使对应性能验收不通过；保留失败，不启动历史版本或修改发压策略。
- 固定环境：i9-9880H8C16T/16GiB/macOS26.7，HotSpot GraalVM25.0.1/Maven3.9.16，当前空闲磁盘456GiB。真实Aeron单成员/Archive，LINEAR_PERPETUAL、1Matcher/4Lane、128symbols、BUSY_SPIN、全局/session/Owner窗口256、MIXED batch20、seed25620、1000retail/1385总用户，行情/做市保持原初始化。G1 Core512m/1536m、client128m/512m；IDE等宿主进程不调整。
- 无采样与独立JFR各预热30s/测量30s/排空单列、各1轮；同窗记录JIT、GC、线程CPU/分配、业务延迟、Lane工作比例与高水位；未做长稳，不以短轮证明无泄漏、完全预热或生产容量。
- 命令：`ASYNC_RUN_ID=20260915-decode4-plain ASYNC_ARTIFACT_DIR=/tmp/core-decode4-plain ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；profile换后缀并加`ASYNC_SKIP_BUILD=true ASYNC_ENABLE_JFR=true`。owner-commit-profile.jfc、每进程独立录制/上限256MiB、执行20ms/CPU和累计分配1s，ps/vm_stat每2s，按client测量epoch对齐；记录NMT与JFR summary/views。
- 新增CommandDecodingBenchmark：PLACE_EMPTY、PLACE_BATCH（20个独立订单）、REPLACE、AMEND、BALANCE、TRANSFER，输入编码在Setup，仅返回真实解码对象。1fork/1thread/3×1s预热+3×1s测量，plain与独立-prof gc；不冒充交易吞吐。命令：`java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main CommandDecodingBenchmark -wi 3 -w 1s -i 3 -r 1s -f 1 -t 1 -jvmArgs '-Xms128m -Xmx512m -XX:+UseG1GC' -rf json -rff /tmp/core-decode4-micro/plain.json`，gc轮改文件并加`-prof gc`。


### 命令解码清理：结果

正确性1268项全部通过：service960、protocol110、client49、基准驱动125、product/instrument各12；无失败/错误/跳过。六产品线执行、资金/冻结、批量顺序及快照恢复检查通过。


**plain，测量epoch秒[1789478400.079, 1789478430.108]**

`progress terminalBusinessOps=2639660 intervalBusinessOpsPerSec=263963.887 requestsInFlight=256`

`progress terminalBusinessOps=5236064 intervalBusinessOpsPerSec=259638.720 requestsInFlight=256`

`progress terminalBusinessOps=7606004 intervalBusinessOpsPerSec=236993.999 requestsInFlight=256`

`steadyCapacity elapsedSeconds=30.028363 terminalBusinessOperations=7613587 terminalCoreMessages=728595 fills=1812480 businessOpsPerSec=253546.522 coreMessagesPerSec=24263.560 fillsPerSec=60358.935 peakInFlight=256 windowBlockedCount=236885 windowBlockedNanos=26504101955`

`drain elapsedNanos=9192744 terminalBusinessOperations=2687 terminalCoreMessages=255 fills=0`

`pipelineHighWater matcher=124 completion=78 context=128 lanes=[47, 39, 43, 36]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1482 businessHash=e1e84788627721b9`

`mixedCapacity=PASS elapsedSeconds=30.038 terminalBusinessOperations=7616274 offeredBusinessOperations=7616274 terminalCoreMessages=728850 offeredCoreMessages=728850 businessOpsPerSec=253558.381 coreMessagesPerSec=24264.624 fills=1812480 fillsPerSec=60340.462 queries=0 unfinished=0 peakInFlight=256 measuredCycles=708 totalCycles=1482 triggerExecutions=0`

窗口阻塞时间占比88.26%；Lane业务执行占比（含排空）[32.47, 32.06, 32.12, 31.95]%。

| 业务 | requests / items | requests/s（含排空） | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---|
| PLACE_ORDER | 181248 / 181248 | 6033.96 | 8.675 / 12.369 / 13.344 / 18.235 / 26.607 / 32.063 |
| CANCEL_ORDER | 181248 / 181248 | 6033.96 | 8.839 / 11.313 / 11.993 / 17.072 / 25.853 / 29.966 |
| APPLY_MARK_PRICE | 3858 / 3858 | 128.44 | 8.404 / 12.320 / 14.204 / 21.299 / 39.583 / 40.108 |
| PLACE_ORDER_BATCH | 271872 / 5437440 | 9050.94 | 10.829 / 15.917 / 16.941 / 22.069 / 35.946 / 40.697 |
| CANCEL_ORDER_BATCH | 90624 / 1812480 | 3016.98 | 14.434 / 16.908 / 19.185 / 30.670 / 38.404 / 40.697 |

批量平均/最大20items，items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 16.68, 'rssMaxKiB': 83396, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '84387': {'cpuAvg': 944.09, 'rssMaxKiB': 1891756, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '84406': {'cpuAvg': 0.0, 'rssMaxKiB': 91760, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '84409': {'cpuAvg': 375.89, 'rssMaxKiB': 501728, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap计数：{'Swapins': 2844242, 'Swapouts': 6118036} → {'Swapins': 2844353, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3148145KB +29521KB, committed=706745KB +38829KB。


**profile，测量epoch秒[1789478506.664, 1789478536.692]**

`progress terminalBusinessOps=2365340 intervalBusinessOpsPerSec=236531.256 requestsInFlight=256`

`progress terminalBusinessOps=4944696 intervalBusinessOpsPerSec=257935.599 requestsInFlight=256`

`progress terminalBusinessOps=7520408 intervalBusinessOpsPerSec=257569.911 requestsInFlight=254`

`steadyCapacity elapsedSeconds=30.028083 terminalBusinessOperations=7527558 terminalCoreMessages=720390 fills=1792000 businessOpsPerSec=250683.934 coreMessagesPerSec=23990.542 fillsPerSec=59677.469 peakInFlight=256 windowBlockedCount=229082 windowBlockedNanos=26091962630`

`drain elapsedNanos=9384112 terminalBusinessOperations=2682 terminalCoreMessages=250 fills=0`

`pipelineHighWater matcher=129 completion=57 context=256 lanes=[63, 63, 63, 63]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1365 businessHash=d1670b22c4387765`

`mixedCapacity=PASS elapsedSeconds=30.037 terminalBusinessOperations=7530240 offeredBusinessOperations=7530240 terminalCoreMessages=720640 offeredCoreMessages=720640 businessOpsPerSec=250694.905 coreMessagesPerSec=23991.370 fills=1792000 fillsPerSec=59658.825 queries=0 unfinished=0 peakInFlight=256 measuredCycles=700 totalCycles=1365 triggerExecutions=0`

窗口阻塞时间占比86.89%；Lane业务执行占比（含排空）[31.97, 32.51, 32.31, 32.11]%。

| 业务 | requests / items | requests/s（含排空） | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---|
| PLACE_ORDER | 179200 / 179200 | 5965.98 | 8.683 / 12.435 / 13.361 / 18.415 / 30.031 / 49.709 |
| CANCEL_ORDER | 179200 / 179200 | 5965.98 | 8.912 / 11.329 / 11.984 / 16.474 / 25.411 / 26.951 |
| APPLY_MARK_PRICE | 3840 / 3840 | 127.84 | 8.015 / 13.148 / 15.368 / 22.740 / 24.821 / 26.165 |
| PLACE_ORDER_BATCH | 268800 / 5376000 | 8948.96 | 10.846 / 16.302 / 17.334 / 22.822 / 39.288 / 53.772 |
| CANCEL_ORDER_BATCH | 89600 / 1792000 | 2982.99 | 14.917 / 17.645 / 20.135 / 33.882 / 50.888 / 53.673 |

批量平均/最大20items，items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 19.11, 'rssMaxKiB': 83632, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '84974': {'cpuAvg': 957.61, 'rssMaxKiB': 1901824, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '84993': {'cpuAvg': 0.48, 'rssMaxKiB': 118256, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '84998': {'cpuAvg': 377.86, 'rssMaxKiB': 566916, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap计数：{'Swapins': 2845604, 'Swapouts': 6118036} → {'Swapins': 2845796, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3172558KB +29149KB, committed=733918KB +38593KB。


JFR client-84993.jfr：测量窗分配0.00MB/s，0.00B/business op；全录制DataLoss0。窗口事件数{'jdk.Compilation': 15, 'jdk.ThreadCPULoad': 46, 'jdk.ThreadAllocationStatistics': 510, 'jdk.ExecutionSample': 1}。GC/JIT/safepoint{'jdk.Compilation': {'count': 15, 'sumMs': 39.464834999999994, 'maxMs': 18.881886, 'p50Ms': 0.253795, 'p95Ms': 18.881886, 'p99Ms': 18.881886}}。

线程CPU（单核百分比）{}。


JFR node.jfr：测量窗分配500.31MB/s，1995.79B/business op；全录制DataLoss0。窗口事件数{'jdk.ExecutionSample': 5983, 'jdk.Compilation': 69, 'jdk.SafepointBegin': 53, 'jdk.GCHeapSummary': 100, 'jdk.GCPhasePause': 50, 'jdk.ThreadCPULoad': 465, 'jdk.ThreadAllocationStatistics': 759, 'jdk.ObjectAllocationSample': 31419}。GC/JIT/safepoint{'jdk.Compilation': {'count': 69, 'sumMs': 1113.3894419999997, 'maxMs': 101.482214, 'p50Ms': 6.257829, 'p95Ms': 61.900552999999995, 'p99Ms': 101.482214}, 'jdk.SafepointBegin': {'count': 53, 'sumMs': 4.877866999999999, 'maxMs': 0.23061199999999998, 'p50Ms': 0.089408, 'p95Ms': 0.12462700000000002, 'p99Ms': 0.23061199999999998}, 'jdk.GCPhasePause': {'count': 50, 'sumMs': 276.16154100000006, 'maxMs': 9.140678, 'p50Ms': 5.3683689999999995, 'p95Ms': 7.489513, 'p99Ms': 9.140678}}。

heapUsed最大426971776，committed最大536870912字节；GC后used范围[120219472, 124444208]。

线程CPU（单核百分比）{'trading-owner--1': 98.25, 'core-matcher-0': 64.3, 'core-account-lane-0': 98.48, 'core-account-lane-1': 98.47, 'core-account-lane-2': 98.47, 'core-account-lane-3': 98.48}。

线程分配MB/s：{'clustered-service-101-0': 26.48, 'trading-owner--1': 74.97, 'core-matcher-0': 124.75, 'core-account-lane-0': 68.53, 'core-account-lane-1': 68.53, 'core-account-lane-2': 68.53, 'core-account-lane-3': 68.53}；执行样本{'trading-owner--1': 1022, 'core-account-lane-0': 1163, 'core-account-lane-1': 1142, 'core-account-lane-2': 1119, 'core-account-lane-3': 1097, 'clustered-service-101-0': 171, 'consensus-module-101-0': 57, 'archive-conductor': 28, 'core-matcher-0': 82, '/tmp/core-decode4-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]': 12, 'aeron-client': 6, 'driver-conductor': 84}。

分配类（采样权重字节）[['com/surprising/aeron/service/state/OrderRuntime', 2167223112], ['[B', 2032391728], ['com/surprising/aeron/service/state/ReservationRuntime', 993874192], ['[J', 984541200], ['java/lang/Long', 693231344], ['com/surprising/aeron/service/matching/CoreMatchingResult', 659196136], ['com/surprising/aeron/service/state/ResolvedPlaceOrder', 624946496], ['[Ljava/lang/Object;', 598809416], ['com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand', 588810504], ['exchange/core2/core/common/MatcherResult', 547868136]]；顶层分配站点[['com.surprising.aeron.service.matching.CoreMatchingResult.classify', 1641675936], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 1104773776], ['com.surprising.aeron.service.state.TradingRuntimeState.preparedOrder', 968281248], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource', 862479896], ['com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order', 618511432], ['com.surprising.aeron.service.orchestration.CoreMessageFlyweightDecoder.decode', 613427352], ['java.util.ArrayList.grow', 596668408], ['com.surprising.aeron.service.state.model.AssetBalance.validAsset', 588312152], ['org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get', 557304544], ['java.util.concurrent.ConcurrentHashMap.putVal', 475961184], ['com.surprising.aeron.service.state.BalanceRuntime.release', 449725224], ['org.agrona.collections.Long2ObjectHashMap.put', 434325856]]。权重不是精确对象总数；JIT内联站点不可直接当构造器。

Owner自耗[['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 45], ['org.agrona.collections.Long2ObjectHashMap.getMapped', 41], ['com.surprising.aeron.service.orchestration.TerminalTombstoneStore.bucket', 37], ['java.util.Arrays.fill', 32], ['java.util.HashMap.getNode', 30], ['com.surprising.aeron.service.state.MatcherSettlementPlan.clearReferences', 25], ['org.agrona.collections.Long2ObjectHashMap.put', 21], ['org.agrona.collections.Long2LongHashMap.put', 21], ['org.agrona.collections.Long2LongHashMap.get', 19], ['org.agrona.collections.Long2ObjectHashMap.remove', 17], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 17], ['com.surprising.aeron.service.state.MatcherSettlementDispatcher.prepareDirect', 15]]；包含栈[['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommandsInScope', 408], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommands', 330], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatching', 320], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.commitReadyMatching', 274], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.tryMatchingCommit', 264], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommands', 257], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommandPrefix', 248], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 197], ['com.surprising.aeron.service.orchestration.OrderBatchExecutor.finishOrderBatch', 191], ['com.surprising.aeron.service.state.TradingRuntimeState$LaneDelta.commitTerminalToOwner', 147], ['com.surprising.aeron.service.orchestration.ContinuousTradingClusterService.runOwner', 140], ['com.surprising.aeron.service.orchestration.TradingCoreRuntime.apply', 128]]（最多8层，不可相加）。


JFR client-84998.jfr：测量窗分配307.37MB/s，1226.11B/business op；全录制DataLoss0。窗口事件数{'jdk.ExecutionSample': 1329, 'jdk.Compilation': 73, 'jdk.SafepointBegin': 121, 'jdk.GCHeapSummary': 236, 'jdk.GCPhasePause': 118, 'jdk.ThreadCPULoad': 351, 'jdk.ThreadAllocationStatistics': 662, 'jdk.ObjectAllocationSample': 18009}。GC/JIT/safepoint{'jdk.Compilation': {'count': 73, 'sumMs': 1286.8583929999998, 'maxMs': 382.217874, 'p50Ms': 0.571722, 'p95Ms': 133.408698, 'p99Ms': 382.217874}, 'jdk.SafepointBegin': {'count': 121, 'sumMs': 44.58613600000001, 'maxMs': 37.069552, 'p50Ms': 0.035033, 'p95Ms': 0.076006, 'p99Ms': 2.282829}, 'jdk.GCPhasePause': {'count': 118, 'sumMs': 98.49534699999997, 'maxMs': 2.167068, 'p50Ms': 0.807992, 'p95Ms': 1.052, 'p99Ms': 1.4276639999999998}}。

heapUsed最大93119400，committed最大136314880字节；GC后used范围[13816584, 14476200]。

线程CPU（单核百分比）{'com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations-jmh-worker-1': 98.67, 'mixed-egress-dispatcher': 36.46}。


Owner测量窗IO：[('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000008844S'), ('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000002468S')]。


局部JMH：每次解码一个命令，PLACE_BATCH每次20个item；输入预建，不是交易吞吐。1fork/3×1s，plain误差为99.9%CI。

| 命令 | plain 次/s ±误差 | gc 次/s | B/次 | gc MiB/s | GC次数 / ms |
|---|---:|---:|---:|---:|---:|
| PLACE_EMPTY | 26208255.778 ± 1913805.523 | 25922093.228 | 128.000 | 3163.217 | 96.0 / 66.0 |
| PLACE_BATCH | 1033856.702 ± 232068.883 | 1040594.493 | 2728.007 | 2706.474 | 86.0 / 56.0 |
| REPLACE | 28100478.648 ± 9035573.040 | 28136586.295 | 152.000 | 4077.610 | 103.0 / 70.0 |
| AMEND | 53248616.821 ± 2224018.657 | 51752897.208 | 104.000 | 5131.775 | 112.0 / 88.0 |
| BALANCE | 58789402.557 ± 6350049.801 | 61023784.314 | 72.000 | 4188.937 | 99.0 / 80.0 |
| TRANSFER | 8608635.154 ± 556188.227 | 8582090.828 | 320.001 | 2618.324 | 83.0 / 51.0 |

限制：固定256窗口已经背压，无独立accepted速率/入口与Core分段延迟、拒单分类/连续队列深度/上下文切换和节流证据。未做长稳、Direct/Mapped峰值、真实Archive重启、Kafka/WebSocket外围及GCP，短轮不证明无泄漏或生产容量；恢复依据正确性测试。线程分配取测量窗内首尾累计差/间隔，边缘约1s误差；ObjectAllocationSample仅采样权重、未启用精确TLAB对象计数。JFR summary及CPU/分配/锁/GC/safepoint views覆盖全录制，热点与上述指标以测量epoch过滤结果为准。


本轮结论：正确性通过；无采样253546.522业务ops/s，下单p99 18.235ms；独立JFR250683.934ops/s，约1995.79B/business op。未达到30万/5ms目标。JFR与plain吞吐差约1.13%，无重复轮，不将差值全归因profiler或代码收益。MIXED测量主要是下单/撤单/批量/标记价，不包含替换、改单、转账主负载；本批只能确认这些解码临时分配点已删除，不能宣称整体分配显著下降。

环境与尾延迟限制：plain/profile测量窗系统swap-in分别增加111/192页，swap-out无增长；未定位到具体进程，按统一标准不作为无环境干扰的容量验收。Owner仍有两次JAR类加载读取合计11.312µs，严格无同步IO门槛未通过。测量期Core仍有69次编译；50次GC暂停276.16ms（占窗口约0.92%），最大9.14ms超过5ms目标。SafepointBegin仅为开始/线程同步事件，不是完整STW时间；客户端最大同步37.07ms，不能忽略客户端对尾延迟的贡献。

全录制105s视图摘要：ThreadPark3050802次（含初始化/停机）、FileWrite790091次/11.6s累计/最大26ms、MonitorBlocked135次/20.7ms累计/最大0.6ms、FileRead2917次/12.2ms累计。未逐事件对齐全部IO/park，不能将全录制等待当30s业务阻塞；不能据此认定锁竞争是主瓶颈。

下一项最小验证：Owner的TradingOrderBatchCodec.decodeCommand、索引探测/更新、MatcherSettlementPlan.clearReferences与Lane发布仍有样本。优先核查高频普通/批量路径内无人读取的重复字段物化和清空；保留协议长度校验、跨线程报文所有权与实际账户版本，不引入按字符串增长的全局缓存。

实际plain/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-decode4-plain/window-256/end_to_end/data -Daeron.dir=/tmp/core-decode4-plain/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-decode4-plain/window-256/end_to_end/tmp -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

实际plain/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-decode4-plain/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-decode4-plain/window-256/end_to_end/jmh.json
```

实际profile/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-decode4-profile/window-256/end_to_end/data -Daeron.dir=/tmp/core-decode4-profile/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-decode4-profile/window-256/end_to_end/tmp -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-decode4-profile/window-256/end_to_end/node.jfr\,maxsize=256m\,dumponexit=true -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

实际profile/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-decode4-profile/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-decode4-profile/window-256/end_to_end/client-%p.jfr\,maxsize=256m\,dumponexit=true -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-decode4-profile/window-256/end_to_end/jmh.json
```

NMT结束reserved/committed各类（非峰值）：
```text
Total: reserved=3172560KB, committed=733924KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                     Class (reserved=1049240KB, committed=2712KB)
-                    Thread (reserved=71847KB, committed=2671KB)
-                      Code (reserved=262117KB, committed=50617KB)
-                        GC (reserved=92309KB, committed=71837KB)
-                 GCCardSet (reserved=16KB, committed=16KB)
-                  Compiler (reserved=406KB, committed=406KB)
-                     JVMCI (reserved=155KB, committed=155KB)
-                  Internal (reserved=1605KB, committed=1605KB)
-                     Other (reserved=9413KB, committed=9413KB)
-                    Symbol (reserved=4922KB, committed=4922KB)
-    Native Memory Tracking (reserved=3043KB, committed=3043KB)
-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
-               Arena Chunk (reserved=1643KB, committed=1643KB)
-                   Tracing (reserved=19257KB, committed=19257KB)
-                   Logging (reserved=0KB, committed=0KB)
-                    Module (reserved=295KB, committed=295KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1284KB, committed=1284KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65735KB, committed=25735KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB, committed=1KB)
```
协议diff SHA-256：`5f3ef66301102e94d89ae2e75892f53e4ccb21a6deb30c2585081517a2eeeaf5`。

关键原始证据（清理后仅历史定位）：

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| /tmp/core-decode4-plain/window-256/end_to_end/metrics.json | 3130 | `cccc085d45cf511e3ad633140aaeb11f63c5cb6e058435549263e02c99e9904f` |
| /tmp/core-decode4-plain/window-256/end_to_end/client.log | 8192 | `b12f2547d2c7679d45ce8a9f66ad036fb91a5e3b4022cabab0551f618f6f3309` |
| /tmp/core-decode4-profile/window-256/end_to_end/metrics.json | 3274 | `92a0c0827614eefdcbad1209aa7549b092b8fd7a111fa9d9a46dab274fa0fef2` |
| /tmp/core-decode4-profile/window-256/end_to_end/client.log | 8797 | `51ceea8c52761bea5b330941406d3c4f3b486af3c4aa17800c3a7b24dbc8200d` |
| /tmp/core-decode4-profile/window-256/end_to_end/client-84993.jfr | 5855146 | `293a7beb76555703411c1cfaa5746517552ce5963d50751160bb8ca49fa96027` |
| /tmp/core-decode4-profile/window-256/end_to_end/client-84998.jfr | 93930115 | `479a7bd5a64a4ac5449a0797825e22720352c34d4e9a896697c31bf456f6f17d` |
| /tmp/core-decode4-profile/window-256/end_to_end/node.jfr | 119202233 | `41cf2ac1ca7573f705e62a0081425267c47dc3e8f0b149739005af4739c69b5e` |
| /tmp/core-decode4-profile/window-256/end_to_end/analysis.json | 78998 | `7182f50b99ef3aa5fc56277909c7dca9bc23886d316cabc951d9de268a575e00` |
| /tmp/core-decode4-profile/window-256/end_to_end/owner-window-io.json | 18289 | `50cd46f649bef31e2e2cec9d3563b63bd1380308db37b5f0e72ef3699a98d7e1` |
| /tmp/core-decode4-profile/window-256/end_to_end/jfr-summary.txt | 12159 | `4c7bb8d2e49ccdd1c118b59a554a9c3709f8b7776dc7cd566642b6faffa28629` |
| /tmp/core-decode4-micro/plain.json | 11183 | `56fbd9567b7bdd193501568e96892d99cb3cf99994050eaa24728331a0d39623` |
| /tmp/core-decode4-micro/gc.json | 34489 | `d955e992835911daf5f221cb0a4cdf415fd0bb0d242054c2da5f5774432d52f8` |
| /tmp/core-decode4-full-tests.log | 214521 | `cd3bf1d2e4987fc402aa2c8ef53d8d5e9d0b822694875db0928f671c0e9c7635` |
| /tmp/core-decode4-bench-tests.log | 49743 | `1508e01a9d4a0ef40f48d7fe46625c811823dc55e467d2a37db2fe349387d519` |

清理完成：本轮JVM、系统采样、JFR分析与微基准进程均退出；删除本轮临时集群data/Archive/media、JFR、日志、分析脚本及测试报告，共393个文件；路径/大小/摘要清单SHA-256 `030b82f66a3f33cad7348e40897b9e538c6874ff5fad6ce9fb45dc4edbd37308`。构建产物保留，未创建云资源。
