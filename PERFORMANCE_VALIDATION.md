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
