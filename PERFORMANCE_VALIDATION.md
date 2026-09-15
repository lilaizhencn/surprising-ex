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

## 2026-09-15：p99分段定位与Owner空表清理（计划）

- 当前master基础4171b028；对照commit不适用（仅验证当前master）。已有证据：约25万business ops/s实际约2.4万Core消息/s，256全局窗口，Owner约98%单核；GC/客户端safepoint可超过5ms。不能把业务ops直接用于估计单条请求排队，也不能仅凭CPU排名定位p99。
- 业务改动：LaneDelta路由删除集合、Treasury三类变更集合/三类before表为空时不再整表clear；非空仍清理，容量和资金/发布/恢复语义不变，无新业务状态或阶段。
- 诊断：core.settlementLatencyDiagnostics默认false；JFR轮启用。Matcher按序号混合hash约1/64采样发布时刻，Lane复用完成标记cache-line padding记录开始/完成；Owner确认完整完成后生成surprising.SettlementLatency JFR事件，记录Matcher→最后Lane开始、最长Lane执行、Matcher→全部Lane完成、全部Lane完成→Owner进入提交、Owner本次提交耗时。只覆盖直接结算普通单/整批事件，不覆盖逐项异步批处理全部生命周期、接入/准入/Matcher之前或响应出口；跨Lane最长等待与最长执行可能不是同一个Lane，不能相加。纳秒来自同一JVM单调时钟；采样对象只在诊断开启且JFR启用时创建，不作为业务状态。
- 基准追加每种业务meanus，来源现有Histogram，无热路径新时钟/队列；用请求加权均值与Core消息速率说明排队规模，仅Little定律近似，不能把不同阶段p99相减。
- 首次编译发现诊断访问包私有routedLaneMask，已改用完成后公开completedLaneMask，未扩大路由API。修复后精确测试通过，新增JFR普通/批量事件复用时序测试（显式开启诊断）、Treasury连续清理仍保留资金及后续变更测试。随后完整service（诊断开启）及基准驱动测试；关闭诊断路径通过单节点plain轮验证。
- 验收参考：持续终态>=300000业务ops/s、普通下单p99<=5ms，错误/超时0，offered=terminal、unfinished0、资金差0；不足标失败/部分验证，系统swap/Owner同步IO不作严格容量通过。假设空表跳过减少Owner清理工作，尾延迟是否改善以新证据为准；不预期小改动独立消除GC与全部排队。
- 固定环境与负载：i9-9880H8C16T/16GiB/macOS26.7、HotSpot GraalVM25.0.1/Maven3.9.16、可用磁盘456GiB；真实单成员Aeron/Archive、LINEAR_PERPETUAL、1Matcher/4Lane、128symbols、1000retail/1385总用户、MIXED batch20/seed25620、BUSY_SPIN、全局/session/Owner窗口256，G1 Core512m/1536m/client128m/512m，行情/做市/配比和宿主进程不调整。
- plain与JFR各30s预热+30s测量，各1轮，排空单列，独立ps/vm_stat每2s；JFR owner-commit-profile.jfc/执行20ms/线程CPU与累计分配1s、每进程最大256MiB，并启用本轮稀疏分段事件；记录NMT、summary/views和按client测量epoch过滤的事件。短轮无长稳，检查JIT/GC/采样损失，不能证明无泄漏或完全预热。
- 命令：`ASYNC_RUN_ID=20260915-latency5-plain ASYNC_ARTIFACT_DIR=/tmp/core-latency5-plain ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR=false bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`；profile换后缀并加`ASYNC_SKIP_BUILD=true ASYNC_ENABLE_JFR=true`，脚本仅JFR轮增加诊断开关。
- JMH更新OwnerPublicationBenchmark.clearRecycledBuffers，执行真实LaneDelta.clear/Treasury.clearChangedKeys，同时复测publishUsers；1fork/1thread/3×1s预热+3×1s测量、G1 128m/512m，plain与独立-prof gc。命令：`java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main 'OwnerPublicationBenchmark.(clearRecycledBuffers|publishUsers)' -wi 3 -w 1s -i 3 -r 1s -f 1 -t 1 -jvmArgs '-Xms128m -Xmx512m -XX:+UseG1GC' -rf json -rff /tmp/core-latency5-micro/plain.json`；gc轮换输出并加`-prof gc`。这是缓冲操作吞吐，不是业务ops。

### 追加单因素验证计划：Owner优先提交已就绪前缀

- 首轮分段诊断已定位：批量下单Lane执行p99约0.157ms，Lane完成→Owner最终提交入口p99约7.85ms；普通单分别约0.009ms与4.05ms。Owner最终一次提交本身约几十微秒。该证据支持优先核查就绪结果排队，而不是继续修改账户算术。
- 当前progressCommandsInScope每提交一条就穿插新命令准入；试验改为提交成功后直接进入下一轮既有循环，优先消费后续已就绪的有序前缀。每轮64工作上限不变，遇未就绪头仍准入独立命令；不跨越提交顺序、控制fence，不扩大窗口或改变客户端发压/做市/配比。
- 只验证当前master工作树上的这一调度差异；首轮原始diff保存于/tmp/core-latency5-source.diff。不是历史版本回跑。再次完整service及基准驱动正确性测试后，使用同一命令与配置，artifact/runId后缀改为latency5-priority-plain/profile，仍30s预热+30s测量、256窗口、1Matcher/4Lane/128symbols/G1/BUSY_SPIN。
- 预期：减少已结算等待Owner的时长；是否降低端到端p99、是否影响吞吐必须同时判断。单次样本不足以证明因果或稳定容量；若显著恶化吞吐/尾延迟或破坏正确性，撤销此调度试验，保留原始证据与已验证的空表清理/诊断能力。


### p99分段与Owner空表清理：结果

正确性：完整service（诊断开启）通过，基准驱动125项通过；最后调整JFR enabled检查后重新执行真实录制/事件复用测试通过。覆盖六产品线资金、冻结、批量顺序及快照恢复。编译曾有包访问错误，修正后无测试失败；plain轮验证诊断关闭路径。


**plain，测量epoch秒[1789479667.441, 1789479697.452]**

`progress terminalBusinessOps=2474500 intervalBusinessOpsPerSec=247447.353 requestsInFlight=256`

`progress terminalBusinessOps=4827038 intervalBusinessOpsPerSec=235253.798 requestsInFlight=256`

`progress terminalBusinessOps=7377055 intervalBusinessOpsPerSec=254988.113 requestsInFlight=228`

`steadyCapacity elapsedSeconds=30.000087 terminalBusinessOperations=7377030 terminalCoreMessages=706054 fills=1756160 businessOpsPerSec=245900.284 coreMessagesPerSec=23535.065 fillsPerSec=58538.496 peakInFlight=256 windowBlockedCount=242827 windowBlockedNanos=26477758099`

`drain elapsedNanos=9127161 terminalBusinessOperations=2685 terminalCoreMessages=253 fills=0`

`pipelineHighWater matcher=126 completion=43 context=128 lanes=[43, 38, 42, 44]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1389 businessHash=3e1cab97c08bcfad`

`mixedCapacity=PASS elapsedSeconds=30.009 terminalBusinessOperations=7379715 offeredBusinessOperations=7379715 terminalCoreMessages=706307 offeredCoreMessages=706307 businessOpsPerSec=245914.967 coreMessagesPerSec=23536.337 fills=1756160 fillsPerSec=58520.692 queries=0 unfinished=0 peakInFlight=256 measuredCycles=686 totalCycles=1389 triggerExecutions=0`

窗口阻塞时间占比88.26%；Lane业务执行占比（含排空）[32.94, 33.21, 32.88, 33.03]%。

| 业务 | requests / items | requests/s（含排空） | mean ms | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---:|---|
| PLACE_ORDER | 175616 / 175616 | 5852.11 | 9.238 | 8.814 / 12.623 / 13.631 / 19.283 / 29.474 / 37.453 |
| CANCEL_ORDER | 175616 / 175616 | 5852.11 | 8.561 | 8.863 / 11.517 / 12.091 / 16.539 / 30.818 / 34.668 |
| APPLY_MARK_PRICE | 3843 / 3843 | 128.06 | 9.000 | 7.839 / 14.786 / 16.359 / 25.231 / 40.730 / 42.369 |
| PLACE_ORDER_BATCH | 263424 / 5268480 | 8778.17 | 11.824 | 11.034 / 16.654 / 17.678 / 22.986 / 44.072 / 60.522 |
| CANCEL_ORDER_BATCH | 87808 / 1756160 | 2926.06 | 15.853 | 15.237 / 17.907 / 20.398 / 34.635 / 45.907 / 56.131 |

请求加权平均延迟10.855ms，steady Core消息速率×平均延迟≈255.48在途请求（Little定律近似，均值含排空、时间窗略有不同，不是测得的平均队列深度）。批量平均/最大20items，items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 20.09, 'rssMaxKiB': 84224, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '90988': {'cpuAvg': 944.7, 'rssMaxKiB': 1859344, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '91003': {'cpuAvg': 0.0, 'rssMaxKiB': 91352, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '91006': {'cpuAvg': 368.35, 'rssMaxKiB': 495128, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap计数：{'Swapins': 2848881, 'Swapouts': 6118036} → {'Swapins': 2848881, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3148643KB +29810KB, committed=707999KB +39850KB。


**profile，测量epoch秒[1789479772.536, 1789479802.594]**

`progress terminalBusinessOps=2395432 intervalBusinessOpsPerSec=239540.216 requestsInFlight=256`

`progress terminalBusinessOps=4827621 intervalBusinessOpsPerSec=243216.878 requestsInFlight=256`

`progress terminalBusinessOps=7143748 intervalBusinessOpsPerSec=231612.699 requestsInFlight=256`

`steadyCapacity elapsedSeconds=30.058037 terminalBusinessOperations=7151232 terminalCoreMessages=684544 fills=1702400 businessOpsPerSec=237914.137 coreMessagesPerSec=22774.075 fillsPerSec=56637.098 peakInFlight=256 windowBlockedCount=235907 windowBlockedNanos=26352173839`

`drain elapsedNanos=19431563 terminalBusinessOperations=2688 terminalCoreMessages=256 fills=0`

`pipelineHighWater matcher=128 completion=128 context=128 lanes=[35, 37, 38, 35]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1316 businessHash=6046040eb2b2862c`

`mixedCapacity=PASS elapsedSeconds=30.077 terminalBusinessOperations=7153920 offeredBusinessOperations=7153920 terminalCoreMessages=684800 offeredCoreMessages=684800 businessOpsPerSec=237849.801 coreMessagesPerSec=22767.873 fills=1702400 fillsPerSec=56600.507 queries=0 unfinished=0 peakInFlight=256 measuredCycles=665 totalCycles=1316 triggerExecutions=0`

窗口阻塞时间占比87.67%；Lane业务执行占比（含排空）[32.71, 32.74, 32.6, 32.25]%。

| 业务 | requests / items | requests/s（含排空） | mean ms | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---:|---|
| PLACE_ORDER | 170240 / 170240 | 5660.14 | 9.517 | 9.068 / 13.033 / 14.098 / 20.316 / 32.784 / 55.934 |
| CANCEL_ORDER | 170240 / 170240 | 5660.14 | 8.834 | 9.379 / 11.927 / 12.763 / 20.021 / 32.604 / 53.641 |
| APPLY_MARK_PRICE | 3840 / 3840 | 127.67 | 9.421 | 8.253 / 13.402 / 16.351 / 33.718 / 41.058 / 52.920 |
| PLACE_ORDER_BATCH | 255360 / 5107200 | 8490.21 | 12.272 | 11.370 / 17.072 / 18.137 / 28.786 / 55.705 / 63.143 |
| CANCEL_ORDER_BATCH | 85120 / 1702400 | 2830.07 | 16.302 | 15.482 / 18.169 / 20.660 / 36.765 / 57.999 / 60.522 |

请求加权平均延迟11.218ms，steady Core消息速率×平均延迟≈255.47在途请求（Little定律近似，均值含排空、时间窗略有不同，不是测得的平均队列深度）。批量平均/最大20items，items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样15点：{'205': {'cpuAvg': 32.67, 'rssMaxKiB': 84304, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '91586': {'cpuAvg': 937.0, 'rssMaxKiB': 1880768, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '91593': {'cpuAvg': 0.41, 'rssMaxKiB': 126800, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '91605': {'cpuAvg': 364.68, 'rssMaxKiB': 489284, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap计数：{'Swapins': 2848994, 'Swapouts': 6118036} → {'Swapins': 2849122, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3171632KB +26166KB, committed=733404KB +38106KB。


JFR client-91593.jfr：测量窗分配0.00MB/s，0.00B/business op；全录制DataLoss0。事件数{'jdk.ThreadCPULoad': 45, 'jdk.ThreadAllocationStatistics': 510, 'jdk.ExecutionSample': 1, 'jdk.Compilation': 14}。GC/JIT/safepoint指标（毫秒）{'jdk.Compilation': {'count': 14, 'mean': 1.8223590714285716, 'sum': 25.513027, 'max': 20.436394, 'p50': 0.236573, 'p90': 2.389623, 'p95': 20.436394, 'p99': 20.436394, 'p99.9': 20.436394}}。

线程CPU（单核百分比）{}。


JFR node.jfr：测量窗分配476.30MB/s，2001.98B/business op；全录制DataLoss0。事件数{'jdk.ExecutionSample': 6401, 'surprising.SettlementLatency': 9290, 'jdk.ThreadCPULoad': 477, 'jdk.ThreadAllocationStatistics': 787, 'jdk.Compilation': 55, 'jdk.SafepointBegin': 49, 'jdk.GCHeapSummary': 92, 'jdk.GCPhasePause': 46, 'jdk.ObjectAllocationSample': 29651}。GC/JIT/safepoint指标（毫秒）{'jdk.Compilation': {'count': 55, 'mean': 24.14708014545455, 'sum': 1328.0894080000003, 'max': 172.20566000000002, 'p50': 14.127811000000001, 'p90': 65.292249, 'p95': 121.264182, 'p99': 172.20566000000002, 'p99.9': 172.20566000000002}, 'jdk.SafepointBegin': {'count': 49, 'mean': 0.3160314285714286, 'sum': 15.48554, 'max': 0.582112, 'p50': 0.338175, 'p90': 0.5149729999999999, 'p95': 0.5318360000000001, 'p99': 0.582112, 'p99.9': 0.582112}, 'jdk.GCPhasePause': {'count': 46, 'mean': 5.384769217391304, 'sum': 247.699384, 'max': 6.031999, 'p50': 5.340353, 'p90': 5.6849680000000005, 'p95': 5.84443, 'p99': 6.031999, 'p99.9': 6.031999}}。

heapUsed最大417131976，committed最大536870912字节；GC后used范围[110762952, 114453688]。

线程CPU（单核百分比）{'trading-owner--1': 97.46, 'core-matcher-0': 63.54, 'core-account-lane-0': 97.97, 'core-account-lane-1': 98.01, 'core-account-lane-2': 97.89, 'core-account-lane-3': 97.96}。

线程分配MB/s{'clustered-service-101-0': 25.41, 'trading-owner--1': 72.33, 'core-matcher-0': 119.66, 'core-account-lane-0': 64.73, 'core-account-lane-1': 64.69, 'core-account-lane-2': 64.68, 'core-account-lane-3': 64.8}；执行样本{'core-account-lane-3': 1190, 'driver-conductor': 76, 'trading-owner--1': 1075, 'core-account-lane-0': 1243, 'core-account-lane-1': 1238, 'core-account-lane-2': 1217, 'consensus-module-101-0': 40, 'clustered-service-101-0': 187, '/tmp/core-latency5-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]': 14, 'core-matcher-0': 96, 'archive-conductor': 22, 'aeron-client': 3}。

分配类（采样权重字节）[['com/surprising/aeron/service/state/OrderRuntime', 2137228192], ['[B', 1893176328], ['com/surprising/aeron/service/state/ReservationRuntime', 1000159072], ['[J', 935905600], ['java/lang/Long', 658731072], ['com/surprising/aeron/service/matching/CoreMatchingResult', 630494784], ['exchange/core2/core/common/MatcherResult', 625881960], ['com/surprising/aeron/service/state/ResolvedPlaceOrder', 581926680], ['[Ljava/lang/Object;', 552648568], ['com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand', 462246792]]；顶层分配站点[['com.surprising.aeron.service.matching.CoreMatchingResult.classify', 1687127048], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 1043639976], ['com.surprising.aeron.service.state.TradingRuntimeState.preparedOrder', 923595856], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource', 781812408], ['java.util.ArrayList.grow', 764322464], ['java.util.concurrent.ConcurrentHashMap.putVal', 750849568], ['com.surprising.aeron.service.orchestration.CoreMessageFlyweightDecoder.decode', 580204600], ['com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order', 577462960], ['org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get', 568974120], ['com.surprising.aeron.service.state.model.AssetBalance.validAsset', 547888384], ['org.agrona.collections.Long2ObjectHashMap.put', 475078752], ['com.surprising.aeron.service.state.BalanceRuntime.release', 463151616]]。权重不是精确对象总数；JIT内联站点不可直接当构造器。

Owner自耗[['org.agrona.collections.Long2ObjectHashMap.getMapped', 48], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand', 38], ['org.agrona.collections.Long2ObjectHashMap.put', 33], ['java.util.Arrays.fill', 32], ['java.util.HashMap.getNode', 31], ['com.surprising.aeron.service.orchestration.TerminalTombstoneStore.bucket', 31], ['org.agrona.collections.Long2ObjectHashMap.remove', 27], ['com.surprising.aeron.service.state.RuntimeChangeBuffer.forEach', 26], ['com.surprising.aeron.service.state.LanePublication.publish', 23], ['org.agrona.collections.Long2LongHashMap.get', 19], ['com.surprising.aeron.service.state.MatcherSettlementDispatcher.prepareDirect', 19], ['org.agrona.collections.Long2LongHashMap.put', 19]]；包含栈[['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommandsInScope', 380], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatchingCommand', 333], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommands', 311], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatching', 277], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommands', 268], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.commitReadyMatching', 234], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.tryMatchingCommit', 209], ['com.surprising.aeron.service.orchestration.OrderBatchExecutor.finishOrderBatch', 207], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommandPrefix', 199], ['com.surprising.aeron.service.orchestration.ContinuousTradingClusterService.runOwner', 188], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 177], ['com.surprising.aeron.service.state.TradingRuntimeState.applyLanePublication', 153]]（最多8层，不可相加）。


JFR client-91605.jfr：测量窗分配289.73MB/s，1217.78B/business op；全录制DataLoss0。事件数{'jdk.ExecutionSample': 1404, 'jdk.Compilation': 73, 'jdk.SafepointBegin': 113, 'jdk.GCHeapSummary': 220, 'jdk.GCPhasePause': 110, 'jdk.ThreadCPULoad': 343, 'jdk.ThreadAllocationStatistics': 631, 'jdk.ObjectAllocationSample': 16811}。GC/JIT/safepoint指标（毫秒）{'jdk.Compilation': {'count': 73, 'mean': 11.160082506849315, 'sum': 814.686023, 'max': 92.48662, 'p50': 0.608414, 'p90': 46.025989, 'p95': 62.16208, 'p99': 92.48662, 'p99.9': 92.48662}, 'jdk.SafepointBegin': {'count': 113, 'mean': 0.44405533628318583, 'sum': 50.178253, 'max': 23.8246, 'p50': 0.053193, 'p90': 0.078901, 'p95': 0.08892599999999999, 'p99': 20.485188, 'p99.9': 23.8246}, 'jdk.GCPhasePause': {'count': 110, 'mean': 0.8565628000000003, 'sum': 94.22190800000003, 'max': 1.356884, 'p50': 0.83517, 'p90': 0.942031, 'p95': 1.0118449999999999, 'p99': 1.173909, 'p99.9': 1.356884}}。

heapUsed最大93163240，committed最大136314880字节；GC后used范围[13645528, 14520040]。

线程CPU（单核百分比）{'com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations-jmh-worker-1': 98.06, 'mixed-egress-dispatcher': 37.81}。


Owner测量窗IO：[('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000022533S'), ('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000007185S')]。


**直接结算分段采样**：约1/64序号混合采样，按Owner本次最终提交开始落入测量窗口筛选；均为墙钟毫秒，含调度/GC。

| 类型 | 阶段 | 样本 | mean / p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---|---:|---|
| PLACE_ORDER_BATCH | matcherToLastLaneStartMs | 3970 | 0.068495 / 0.000540 / 0.251437 / 0.371513 / 0.611495 / 1.133242 / 2.076680 |
| PLACE_ORDER_BATCH | maxLaneExecutionMs | 3970 | 0.060595 / 0.052862 / 0.091832 / 0.098494 / 0.156573 / 0.392579 / 5.682160 |
| PLACE_ORDER_BATCH | matcherToLanesCompleteMs | 3970 | 0.121493 / 0.053726 / 0.323967 / 0.443982 / 0.697744 / 1.692270 / 5.750558 |
| PLACE_ORDER_BATCH | lanesCompleteToOwnerMs | 3970 | 3.308589 / 3.148179 / 5.137979 / 5.952831 / 7.849998 / 18.790211 / 48.035407 |
| PLACE_ORDER_BATCH | ownerFinalCommitMs | 3970 | 0.030854 / 0.026437 / 0.035211 / 0.039839 / 0.061933 / 0.925849 / 6.141910 |
| PLACE_ORDER_BATCH | publicationToCommitMs | 3970 | 3.460937 / 3.290813 / 5.310422 / 6.057539 / 8.144626 / 18.839121 / 49.035598 |
| PLACE_ORDER | matcherToLastLaneStartMs | 2661 | 0.516798 / 0.000412 / 2.274685 / 3.056427 / 5.266939 / 8.305775 / 8.939760 |
| PLACE_ORDER | maxLaneExecutionMs | 2661 | 0.003048 / 0.002750 / 0.003858 / 0.005144 / 0.008552 / 0.045426 / 0.049945 |
| PLACE_ORDER | matcherToLanesCompleteMs | 2661 | 0.519847 / 0.003357 / 2.277750 / 3.062544 / 5.270829 / 8.309990 / 8.943476 |
| PLACE_ORDER | lanesCompleteToOwnerMs | 2661 | 1.469631 / 1.400174 / 2.412332 / 2.805902 / 4.045848 / 7.363437 / 7.895862 |
| PLACE_ORDER | ownerFinalCommitMs | 2661 | 0.009537 / 0.008837 / 0.011160 / 0.013456 / 0.029778 / 0.057126 / 0.132553 |
| PLACE_ORDER | publicationToCommitMs | 2661 | 1.999014 / 1.638034 / 3.400972 / 4.268778 / 6.156574 / 8.559760 / 10.228024 |
| CANCEL_ORDER | matcherToLastLaneStartMs | 2659 | 0.010905 / 0.000415 / 0.000730 / 0.002923 / 0.315672 / 0.873846 / 5.766803 |
| CANCEL_ORDER | maxLaneExecutionMs | 2659 | 0.006214 / 0.005552 / 0.008357 / 0.010523 / 0.019784 / 0.052568 / 0.078595 |
| CANCEL_ORDER | matcherToLanesCompleteMs | 2659 | 0.017120 / 0.006012 / 0.010594 / 0.018995 / 0.320185 / 0.878789 / 5.774291 |
| CANCEL_ORDER | lanesCompleteToOwnerMs | 2659 | 1.909399 / 1.541514 / 3.225044 / 3.898386 / 5.240129 / 9.260474 / 48.019537 |
| CANCEL_ORDER | ownerFinalCommitMs | 2659 | 0.010143 / 0.009317 / 0.012447 / 0.015455 / 0.030487 / 0.049426 / 0.055678 |
| CANCEL_ORDER | publicationToCommitMs | 2659 | 1.936661 / 1.567006 / 3.249104 / 3.941366 / 5.287991 / 9.279119 / 48.040575 |

注：publicationToCommitMs按每个样本的matcherToLanesComplete+lanesCompleteToOwner+ownerFinalCommit求和后统计。最长Lane排队和最长执行可能来自不同Lane，不能相加；不能用上述分位数与客户端分位数相减。ownerFinalCommit只计最终返回终态的那次调用，不包括此前多次Owner准备/轮询；未覆盖接入、准入、Matcher之前及响应出口。SafepointBegin为开始同步阶段，不是完整STW时间。


局部JMH：每次缓冲操作，publishUsers每次20用户；不是交易ops。1fork/3×1s，plain误差99.9%CI。

| 方法 | plain 次/s ±误差 | gc 次/s | B/次 | gc MiB/s | GC次数 / ms |
|---|---:|---:|---:|---:|---:|
| clearRecycledBuffers | 82395102.066 ± 4028200.088 | 81714248.390 | 0.000 | 0.007 | 0.0 / 未发生 |
| publishUsers | 4080119.128 ± 1106728.990 | 4084181.582 | 0.002 | 0.007 | 0.0 / 未发生 |

限制：固定256窗口已背压，无独立accepted速率/入口分段延迟、拒单分类、连续队列深度/上下文切换/节流证据。未做长稳、Direct/Mapped峰值、真实Archive重启、Kafka/WebSocket外围及GCP，短轮不证明无泄漏或生产容量；恢复依据正确性测试。线程分配为测量窗内首尾累计差/间隔，边缘约1s误差；ObjectAllocationSample仅采样权重、未启用精确TLAB对象计数。summary和CPU/分配/锁/GC/safepoint views覆盖全录制，以上指标按client epoch过滤。

试验保留门槛（第二轮采集前固定，仅用于选择是否保留，不是统计显著性结论）：plain持续吞吐不低于首轮的95%（233605.27ops/s），普通下单p99至少降低5%（≤18.319ms），同时profile批量Lane完成→Owner等待p99至少降低10%（≤7.065ms）。任一未满足则撤销调度试验，不以热点移动或单项好看代替整体收益；正式30万/5ms目标不变。


### Owner就绪提交优先试验：结果

正确性：完整service（诊断开启）通过，基准驱动125项通过；最后调整JFR enabled检查后重新执行真实录制/事件复用测试通过。覆盖六产品线资金、冻结、批量顺序及快照恢复。编译曾有包访问错误，修正后无测试失败；plain轮验证诊断关闭路径。


**plain，测量epoch秒[1789480299.906, 1789480329.935]**

`progress terminalBusinessOps=3730910 intervalBusinessOpsPerSec=373086.968 requestsInFlight=256`

`progress terminalBusinessOps=7001553 intervalBusinessOpsPerSec=327062.177 requestsInFlight=256`

`progress terminalBusinessOps=10324541 intervalBusinessOpsPerSec=332298.799 requestsInFlight=256`

`steadyCapacity elapsedSeconds=30.028315 terminalBusinessOperations=10333857 terminalCoreMessages=987681 fills=2460160 businessOpsPerSec=344137.088 coreMessagesPerSec=32891.655 fillsPerSec=81928.006 peakInFlight=256 windowBlockedCount=212940 windowBlockedNanos=25520322831`

`drain elapsedNanos=6792823 terminalBusinessOperations=2685 terminalCoreMessages=253 fills=0`

`pipelineHighWater matcher=128 completion=65 context=128 lanes=[47, 47, 46, 47]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1927 businessHash=64d73a3211d054d4`

`mixedCapacity=PASS elapsedSeconds=30.035 terminalBusinessOperations=10336542 offeredBusinessOperations=10336542 terminalCoreMessages=987934 offeredCoreMessages=987934 businessOpsPerSec=344148.652 coreMessagesPerSec=32892.640 fills=2460160 fillsPerSec=81909.477 queries=0 unfinished=0 peakInFlight=256 measuredCycles=961 totalCycles=1927 triggerExecutions=0`

窗口阻塞时间占比84.99%；Lane业务执行占比（含排空）[40.74, 40.6, 41.13, 40.77]%。

| 业务 | requests / items | requests/s（含排空） | mean ms | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---:|---|
| PLACE_ORDER | 246016 / 246016 | 8190.98 | 6.562 | 5.869 / 10.256 / 11.313 / 14.295 / 26.247 / 38.436 |
| CANCEL_ORDER | 246016 / 246016 | 8190.98 | 5.555 | 5.591 / 7.946 / 8.732 / 12.697 / 25.559 / 32.129 |
| APPLY_MARK_PRICE | 3870 / 3870 | 128.85 | 6.785 | 6.094 / 10.625 / 11.935 / 15.589 / 18.989 / 19.316 |
| PLACE_ORDER_BATCH | 369024 / 7380480 | 12286.47 | 8.061 | 6.774 / 13.328 / 14.589 / 18.153 / 30.769 / 40.042 |
| CANCEL_ORDER_BATCH | 123008 / 2460160 | 4095.49 | 13.612 | 13.271 / 15.548 / 16.990 / 27.869 / 36.306 / 40.337 |

请求加权平均延迟7.750ms，steady Core消息速率×平均延迟≈254.90在途请求（Little定律近似，均值含排空、时间窗略有不同，不是测得的平均队列深度）。批量平均/最大20items，items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 18.49, 'rssMaxKiB': 84392, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '94447': {'cpuAvg': 937.31, 'rssMaxKiB': 1883892, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '94466': {'cpuAvg': 0.07, 'rssMaxKiB': 91084, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '94473': {'cpuAvg': 344.72, 'rssMaxKiB': 508672, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap计数：{'Swapins': 2849441, 'Swapouts': 6118036} → {'Swapins': 2849441, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3146050KB +28708KB, committed=706894KB +38168KB。


**profile，测量epoch秒[1789480407.273, 1789480437.294]**

`progress terminalBusinessOps=3182512 intervalBusinessOpsPerSec=318247.330 requestsInFlight=256`

`progress terminalBusinessOps=6402532 intervalBusinessOpsPerSec=321999.456 requestsInFlight=256`

`progress terminalBusinessOps=9445806 intervalBusinessOpsPerSec=304327.286 requestsInFlight=252`

`steadyCapacity elapsedSeconds=30.021485 terminalBusinessOperations=9452178 terminalCoreMessages=903698 fills=2250240 businessOpsPerSec=314847.122 coreMessagesPerSec=30101.709 fillsPerSec=74954.321 peakInFlight=256 windowBlockedCount=207265 windowBlockedNanos=24983647226`

`drain elapsedNanos=8827441 terminalBusinessOperations=2677 terminalCoreMessages=245 fills=0`

`pipelineHighWater matcher=128 completion=128 context=128 lanes=[45, 49, 43, 44]`

`mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1753 businessHash=afd3763bc11cf`

`mixedCapacity=PASS elapsedSeconds=30.030 terminalBusinessOperations=9454855 offeredBusinessOperations=9454855 terminalCoreMessages=903943 offeredCoreMessages=903943 businessOpsPerSec=314843.715 coreMessagesPerSec=30101.019 fills=2250240 fillsPerSec=74932.288 queries=0 unfinished=0 peakInFlight=256 measuredCycles=879 totalCycles=1753 triggerExecutions=0`

窗口阻塞时间占比83.22%；Lane业务执行占比（含排空）[41.63, 41.73, 41.7, 41.64]%。

| 业务 | requests / items | requests/s（含排空） | mean ms | p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---:|---:|---:|---|
| PLACE_ORDER | 225024 / 225024 | 7493.31 | 7.162 | 6.352 / 10.969 / 11.976 / 16.793 / 51.019 / 60.588 |
| CANCEL_ORDER | 225024 / 225024 | 7493.31 | 6.123 | 6.406 / 8.634 / 9.420 / 14.827 / 47.939 / 53.051 |
| APPLY_MARK_PRICE | 3847 / 3847 | 128.11 | 7.727 | 6.639 / 12.681 / 15.343 / 18.841 / 23.265 / 27.869 |
| PLACE_ORDER_BATCH | 337536 / 6750720 | 11239.96 | 8.820 | 7.352 / 14.319 / 15.466 / 21.921 / 52.330 / 63.504 |
| CANCEL_ORDER_BATCH | 112512 / 2250240 | 3746.65 | 14.676 | 14.106 / 16.326 / 18.989 / 30.654 / 60.620 / 64.028 |

请求加权平均延迟8.460ms，steady Core消息速率×平均延迟≈254.66在途请求（Little定律近似，均值含排空、时间窗略有不同，不是测得的平均队列深度）。批量平均/最大20items，items/s=对应batches/s×20。延迟为commandAsync调用前→终态，样本含排空、不含窗口等待；未校正coordinated omission。

系统2s采样14点：{'205': {'cpuAvg': 42.04, 'rssMaxKiB': 84108, 'exe': '/System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer'}, '95120': {'cpuAvg': 935.85, 'rssMaxKiB': 1902936, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '95133': {'cpuAvg': 0.39, 'rssMaxKiB': 118544, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}, '95138': {'cpuAvg': 364.72, 'rssMaxKiB': 541420, 'exe': '/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java'}}。

测量首末swap计数：{'Swapins': 2850132, 'Swapouts': 6118036} → {'Swapins': 2850260, 'Swapouts': 6118036}。

NMT基线→结束（含初始化）：Total: reserved=3168916KB +19146KB, committed=732868KB +37350KB。


JFR client-95133.jfr：测量窗分配0.00MB/s，0.00B/business op；全录制DataLoss0。事件数{'jdk.ThreadCPULoad': 45, 'jdk.ThreadAllocationStatistics': 510, 'jdk.ExecutionSample': 2, 'jdk.Compilation': 11}。GC/JIT/safepoint指标（毫秒）{'jdk.Compilation': {'count': 11, 'mean': 2.270824, 'sum': 24.979064, 'max': 19.666358, 'p50': 0.27490099999999995, 'p90': 3.120845, 'p95': 19.666358, 'p99': 19.666358, 'p99.9': 19.666358}}。

线程CPU（单核百分比）{}。


JFR node.jfr：测量窗分配630.39MB/s，2002.22B/business op；全录制DataLoss0。事件数{'jdk.ExecutionSample': 6033, 'surprising.SettlementLatency': 12319, 'jdk.SafepointBegin': 65, 'jdk.GCHeapSummary': 126, 'jdk.GCPhasePause': 63, 'jdk.Compilation': 61, 'jdk.ThreadCPULoad': 467, 'jdk.ThreadAllocationStatistics': 762, 'jdk.ObjectAllocationSample': 39532}。GC/JIT/safepoint指标（毫秒）{'jdk.SafepointBegin': {'count': 65, 'mean': 0.09009561538461543, 'sum': 5.856215000000002, 'max': 0.280306, 'p50': 0.084487, 'p90': 0.11489600000000001, 'p95': 0.125224, 'p99': 0.280306, 'p99.9': 0.280306}, 'jdk.GCPhasePause': {'count': 63, 'mean': 5.172759126984126, 'sum': 325.88382499999994, 'max': 9.209339, 'p50': 5.10062, 'p90': 5.446293, 'p95': 5.483721, 'p99': 9.209339, 'p99.9': 9.209339}, 'jdk.Compilation': {'count': 61, 'mean': 20.561981918032785, 'sum': 1254.2808969999999, 'max': 133.451363, 'p50': 9.987241, 'p90': 62.791990000000006, 'p95': 87.387306, 'p99': 133.451363, 'p99.9': 133.451363}}。

heapUsed最大408513312，committed最大536870912字节；GC后used范围[101907928, 104586176]。

线程CPU（单核百分比）{'trading-owner--1': 97.09, 'core-matcher-0': 54.74, 'core-account-lane-0': 97.58, 'core-account-lane-1': 97.52, 'core-account-lane-2': 97.48, 'core-account-lane-3': 97.51}。

线程分配MB/s{'clustered-service-101-0': 33.36, 'trading-owner--1': 94.33, 'core-matcher-0': 157.11, 'core-account-lane-0': 86.34, 'core-account-lane-1': 86.37, 'core-account-lane-2': 86.35, 'core-account-lane-3': 86.54}；执行样本{'trading-owner--1': 1051, 'core-account-lane-0': 1095, 'core-account-lane-1': 1073, 'core-account-lane-2': 1073, 'core-account-lane-3': 1064, 'core-matcher-0': 379, 'clustered-service-101-0': 108, 'driver-conductor': 86, 'consensus-module-101-0': 56, '/tmp/core-latency5-priority-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]': 12, 'archive-conductor': 34, 'aeron-client': 2}。

分配类（采样权重字节）[['com/surprising/aeron/service/state/OrderRuntime', 2841221536], ['[B', 2503690184], ['com/surprising/aeron/service/state/ReservationRuntime', 1304384360], ['[J', 1183932480], ['exchange/core2/core/common/MatcherResult', 918654904], ['com/surprising/aeron/service/state/ResolvedPlaceOrder', 759885472], ['com/surprising/aeron/service/matching/CoreMatchingResult', 654615824], ['java/lang/Long', 638844880], ['com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand', 620282424], ['[Ljava/lang/Object;', 595301504]]；顶层分配站点[['com.surprising.aeron.protocol.TradingCommandCodec.decodePlaceOrder', 1429677936], ['com.surprising.aeron.service.state.TradingRuntimeState.preparedOrder', 1245868840], ['com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource', 995342368], ['com.surprising.aeron.service.matching.CoreMatchingResult.classify', 968413600], ['java.util.List.copyOf', 918654904], ['com.surprising.aeron.service.orchestration.CoreMessageFlyweightDecoder.decode', 776651320], ['org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get', 775544080], ['com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order', 771892504], ['com.surprising.aeron.service.state.model.AssetBalance.validAsset', 714410272], ['java.util.concurrent.ConcurrentHashMap.putVal', 705105016], ['com.surprising.aeron.service.state.OrderRuntime.withFill', 591948240], ['com.surprising.aeron.service.state.BalanceRuntime.release', 588190888]]。权重不是精确对象总数；JIT内联站点不可直接当构造器。

Owner自耗[['org.agrona.collections.Long2ObjectHashMap.getMapped', 58], ['com.surprising.aeron.protocol.TradingCommandCodec.decodePlaceOrder', 45], ['com.surprising.aeron.service.orchestration.TerminalTombstoneStore.bucket', 37], ['org.agrona.collections.Long2ObjectHashMap.put', 33], ['java.util.HashMap.getNode', 25], ['java.util.Arrays.fill', 21], ['com.surprising.aeron.service.state.MatcherSettlementPlan.clearReferences', 19], ['org.agrona.collections.Long2LongHashMap.get', 18], ['org.agrona.collections.Long2LongHashMap.put', 17], ['org.agrona.collections.Long2ObjectHashMap.remove', 17], ['com.surprising.aeron.service.state.LanePublication.publish', 17], ['com.surprising.aeron.service.orchestration.OrderBatchExecutor.preparePipelinedPlaceBatch', 17]]；包含栈[['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommandsInScope', 392], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatchingCommand', 315], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.completeMatching', 272], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.progressCommands', 265], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.commitReadyMatching', 222], ['com.surprising.aeron.service.orchestration.OrderedCommitCoordinator.tryMatchingCommit', 204], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommandPrefix', 198], ['com.surprising.aeron.service.orchestration.SurprisingClusteredService.pollCommands', 195], ['com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement', 166], ['com.surprising.aeron.service.orchestration.OrderBatchExecutor.finishOrderBatch', 157], ['com.surprising.aeron.service.orchestration.TradingCoreRuntime.apply', 152], ['com.surprising.aeron.service.orchestration.ContinuousTradingClusterService.runOwner', 144]]（最多8层，不可相加）。


JFR client-95138.jfr：测量窗分配387.18MB/s，1229.74B/business op；全录制DataLoss0。事件数{'jdk.ExecutionSample': 1282, 'jdk.Compilation': 78, 'jdk.SafepointBegin': 151, 'jdk.GCHeapSummary': 296, 'jdk.GCPhasePause': 148, 'jdk.ThreadCPULoad': 353, 'jdk.ThreadAllocationStatistics': 636, 'jdk.ObjectAllocationSample': 22648}。GC/JIT/safepoint指标（毫秒）{'jdk.Compilation': {'count': 78, 'mean': 29.850989987179485, 'sum': 2328.377219, 'max': 644.9190209999999, 'p50': 0.962843, 'p90': 54.657416, 'p95': 93.288383, 'p99': 644.9190209999999, 'p99.9': 644.9190209999999}, 'jdk.SafepointBegin': {'count': 151, 'mean': 0.39030867549668874, 'sum': 58.93661, 'max': 30.056156, 'p50': 0.035326, 'p90': 0.06835, 'p95': 0.086362, 'p99': 22.38288, 'p99.9': 30.056156}, 'jdk.GCPhasePause': {'count': 148, 'mean': 0.8566282027027026, 'sum': 126.78097399999997, 'max': 2.23452, 'p50': 0.828971, 'p90': 0.9480200000000001, 'p95': 1.058184, 'p99': 1.58465, 'p99.9': 2.23452}}。

heapUsed最大93529352，committed最大136314880字节；GC后used范围[14212256, 14886152]。

线程CPU（单核百分比）{'com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations-jmh-worker-1': 97.75, 'mixed-egress-dispatcher': 39.84}。


Owner测量窗IO：[('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000009414S'), ('jdk.FileRead', '/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar', 'PT0.000004462S')]。


**直接结算分段采样**：约1/64序号混合采样，按Owner本次最终提交开始落入测量窗口筛选；均为墙钟毫秒，含调度/GC。

| 类型 | 阶段 | 样本 | mean / p50 / p90 / p95 / p99 / p99.9 / max ms |
|---|---|---:|---|
| CANCEL_ORDER | matcherToLastLaneStartMs | 3524 | 0.048072 / 0.000372 / 0.152958 / 0.340263 / 0.672381 / 1.229953 / 5.619601 |
| CANCEL_ORDER | maxLaneExecutionMs | 3524 | 0.005052 / 0.004524 / 0.006534 / 0.008587 / 0.013092 / 0.031877 / 0.137563 |
| CANCEL_ORDER | matcherToLanesCompleteMs | 3524 | 0.053125 / 0.005104 / 0.158118 / 0.345516 / 0.693211 / 1.235729 / 5.623898 |
| CANCEL_ORDER | lanesCompleteToOwnerMs | 3524 | 0.371150 / 0.315288 / 0.670924 / 0.848226 / 1.139552 / 3.463633 / 6.735191 |
| CANCEL_ORDER | ownerFinalCommitMs | 3524 | 0.007559 / 0.006972 / 0.008748 / 0.010820 / 0.024809 / 0.042180 / 0.198758 |
| CANCEL_ORDER | publicationToCommitMs | 3524 | 0.431833 / 0.343169 / 0.852445 / 1.038448 / 1.371483 / 3.707240 / 12.094075 |
| PLACE_ORDER | matcherToLastLaneStartMs | 3505 | 0.079771 / 0.000371 / 0.018899 / 0.650635 / 1.140729 / 1.812320 / 16.539629 |
| PLACE_ORDER | maxLaneExecutionMs | 3505 | 0.002825 / 0.002465 / 0.003661 / 0.004863 / 0.007665 / 0.034223 / 0.096531 |
| PLACE_ORDER | matcherToLanesCompleteMs | 3505 | 0.082596 / 0.002879 / 0.030083 / 0.652594 / 1.148334 / 1.820402 / 16.543276 |
| PLACE_ORDER | lanesCompleteToOwnerMs | 3505 | 0.416914 / 0.401039 / 0.637609 / 0.736948 / 1.115663 / 7.711075 / 18.030492 |
| PLACE_ORDER | ownerFinalCommitMs | 3505 | 0.007080 / 0.006442 / 0.008030 / 0.010534 / 0.023443 / 0.038565 / 0.620128 |
| PLACE_ORDER | publicationToCommitMs | 3505 | 0.506590 / 0.439765 / 0.791829 / 1.093448 / 1.638813 / 8.094741 / 35.193896 |
| PLACE_ORDER_BATCH | matcherToLastLaneStartMs | 5290 | 0.100541 / 0.000515 / 0.348898 / 0.491706 / 0.892260 / 1.912013 / 5.275453 |
| PLACE_ORDER_BATCH | maxLaneExecutionMs | 5290 | 0.060388 / 0.052555 / 0.092247 / 0.098502 / 0.142522 / 0.331957 / 5.819895 |
| PLACE_ORDER_BATCH | matcherToLanesCompleteMs | 5290 | 0.152136 / 0.053672 / 0.417969 / 0.564850 / 0.974468 / 2.243901 / 6.392086 |
| PLACE_ORDER_BATCH | lanesCompleteToOwnerMs | 5290 | 0.339246 / 0.254988 / 0.717251 / 0.955181 / 1.437818 / 2.356587 / 6.725014 |
| PLACE_ORDER_BATCH | ownerFinalCommitMs | 5290 | 0.022189 / 0.021043 / 0.028872 / 0.032853 / 0.053380 / 0.090535 / 0.127446 |
| PLACE_ORDER_BATCH | publicationToCommitMs | 5290 | 0.513570 / 0.411500 / 1.002253 / 1.273693 / 1.660772 / 4.611604 / 7.527244 |

注：publicationToCommitMs按每个样本的matcherToLanesComplete+lanesCompleteToOwner+ownerFinalCommit求和后统计。最长Lane排队和最长执行可能来自不同Lane，不能相加；不能用上述分位数与客户端分位数相减。ownerFinalCommit只计最终返回终态的那次调用，不包括此前多次Owner准备/轮询；未覆盖接入、准入、Matcher之前及响应出口。SafepointBegin为开始同步阶段，不是完整STW时间。


局部JMH沿用首轮：此试验未修改缓冲代码，未重复计时；每次缓冲操作，publishUsers每次20用户；不是交易ops。1fork/3×1s，plain误差99.9%CI。

| 方法 | plain 次/s ±误差 | gc 次/s | B/次 | gc MiB/s | GC次数 / ms |
|---|---:|---:|---:|---:|---:|
| clearRecycledBuffers | 82395102.066 ± 4028200.088 | 81714248.390 | 0.000 | 0.007 | 0.0 / 未发生 |
| publishUsers | 4080119.128 ± 1106728.990 | 4084181.582 | 0.002 | 0.007 | 0.0 / 未发生 |

限制：固定256窗口已背压，无独立accepted速率/入口分段延迟、拒单分类、连续队列深度/上下文切换/节流证据。未做长稳、Direct/Mapped峰值、真实Archive重启、Kafka/WebSocket外围及GCP，短轮不证明无泄漏或生产容量；恢复依据正确性测试。线程分配为测量窗内首尾累计差/间隔，边缘约1s误差；ObjectAllocationSample仅采样权重、未启用精确TLAB对象计数。summary和CPU/分配/锁/GC/safepoint views覆盖全录制，以上指标按client epoch过滤。


### 最终选择与结论

保留Owner就绪提交优先：三个预定保留门槛全部满足。无采样持续终态245900.284→344137.088业务ops/s（本次单因素短轮观测约+39.95%），普通下单p99 19.283→14.295ms；JFR批量Lane完成→Owner等待p99 7.850→1.438ms，普通单4.046→1.116ms。普通单Matcher发布→本次提交p99 6.157→1.639ms。调整仅是在既有64次循环内优先提交就绪前缀，未就绪时继续准入独立命令；没有新增业务状态、队列或等待锁，也没有调整负载。

正确性1270项通过：service962、基准驱动125、protocol110、client49、product/instrument各12。最终基准驱动以默认关闭诊断运行，service完整测试及专门录制测试显式开启诊断。业务完成数与offered一致、unfinished0、资金差0；快照/串行一致性来自测试，未声称真实Archive重启验收。

目标状态：30万吞吐门槛在当前本机配置的30s无采样段达到，独立JFR段314847.122ops/s；下单p99≤5ms未达到，整体性能验收仍不通过。没有重复轮/长稳，不把一次提升当生产容量保证。plain窗口系统swap计数不变；两轮profile均有128页swap-in、无swap-out增长，按标准对应容量证据受环境限制。Owner仍有测量期JAR类加载IO（最终两次合计13.876µs），严格无同步IO/完全预热门槛未通过。

原因证据与剩余定位：

| 问题 | 本轮证据 | 处理/下一步 |
|---|---|---|
| 已完成结果仍排队等Owner | 批量等待p99由7.850降至1.438ms，最终提交本身p99约0.053ms | 已保留有界优先提交，避免每次提交穿插新准入；仍保持全局有序提交 |
| Lane算术不是主要耗时 | 最终普通Lane执行p99 0.008ms，批量0.143ms；业务执行占比32.6%→41.7%（含排空） | 不为微秒级账户修改增加池/协调/新状态 |
| 接入/准入/Matcher前排队与响应出口尚未细分 | 最终普通单客户端p99 16.793ms（JFR轮），Matcher发布→提交p99 1.639ms；这些分位数不能相减 | 下一轮在跨线程接入队列、Owner准入→Matcher发布、输出队列分别做稀疏计时；CANCEL_ORDER_BATCH与控制指令本次没有完整分段样本 |
| 满窗口与低延迟预算不匹配 | 无采样请求加权平均7.750ms，λW约254.90在途；是请求而非业务item口径 | 当前平均在途规模若把平均降至5ms，Little近似需约5.1万Core消息/s（约53万business ops/s，按当前配比），这不是容量预测；仅到30万不会自动保证p99≤5ms。本轮未调窗口/发压策略 |
| 分配和JVM停顿仍高 | 约2002B/business op；最终profile约630MB/s，63次GC/325.884ms、最大9.209ms | 下一步继续减少高频准入/订单版本/响应编码分配；不能把尾延迟全部归因GC |

GC相关性（按JFR事件开始时刻倒推发布区间，误差包含采样字段构造的微小耗时；只说明重叠，不证明因果）：首轮批量512个发布→提交≥5ms样本仅28个重叠Core GC，最长49ms没有重叠；最终批量5290个样本中仅5个≥5ms，5个均重叠GC。最终普通3505个样本仅4个≥5ms，均未重叠GC；仍有最长35ms离群值，具体调度/前序依赖未完全定位。

最终全录制约105s视图：ThreadPark2416453次（含初始化/停机），FileWrite916090次/13.6s累计/最大22.6ms，MonitorBlocked156次/19.5ms累计/最大0.571ms。上述全录制等待不作为测量窗锁瓶颈结论；SafepointBegin仅开始同步阶段，不是完整STW。

实际latency5/plain/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-latency5-plain/window-256/end_to_end/data -Daeron.dir=/tmp/core-latency5-plain/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-latency5-plain/window-256/end_to_end/tmp -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

实际latency5/plain/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-latency5-plain/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-latency5-plain/window-256/end_to_end/jmh.json
```

实际latency5/profile/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-latency5-profile/window-256/end_to_end/data -Daeron.dir=/tmp/core-latency5-profile/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-latency5-profile/window-256/end_to_end/tmp -Dcore.settlementLatencyDiagnostics=true -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-latency5-profile/window-256/end_to_end/node.jfr\,maxsize=256m\,dumponexit=true -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

实际latency5/profile/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-latency5-profile/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-latency5-profile/window-256/end_to_end/client-%p.jfr\,maxsize=256m\,dumponexit=true -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-latency5-profile/window-256/end_to_end/jmh.json
```

NMT结束reserved/committed各类（非峰值）：
```text
Total: reserved=3171635KB, committed=733411KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                     Class (reserved=1049241KB, committed=2713KB)
-                    Thread (reserved=71847KB, committed=2763KB)
-                      Code (reserved=262198KB, committed=50954KB)
-                        GC (reserved=92306KB, committed=71834KB)
-                 GCCardSet (reserved=16KB, committed=16KB)
-                  Compiler (reserved=425KB, committed=425KB)
-                     JVMCI (reserved=145KB, committed=145KB)
-                  Internal (reserved=1605KB, committed=1605KB)
-                     Other (reserved=9413KB, committed=9413KB)
-                    Symbol (reserved=4923KB, committed=4923KB)
-    Native Memory Tracking (reserved=3021KB, committed=3021KB)
-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
-               Arena Chunk (reserved=1642KB, committed=1642KB)
-                   Tracing (reserved=18273KB, committed=18273KB)
-                   Logging (reserved=0KB, committed=0KB)
-                    Module (reserved=295KB, committed=295KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1287KB, committed=1287KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65724KB, committed=25788KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB, committed=1KB)
```

实际latency5-priority/plain/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-latency5-priority-plain/window-256/end_to_end/data -Daeron.dir=/tmp/core-latency5-priority-plain/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-latency5-priority-plain/window-256/end_to_end/tmp -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

实际latency5-priority/plain/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-latency5-priority-plain/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-latency5-priority-plain/window-256/end_to_end/jmh.json
```

实际latency5-priority/profile/node.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-latency5-priority-profile/window-256/end_to_end/data -Daeron.dir=/tmp/core-latency5-priority-profile/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-latency5-priority-profile/window-256/end_to_end/tmp -Dcore.settlementLatencyDiagnostics=true -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-latency5-priority-profile/window-256/end_to_end/node.jfr\,maxsize=256m\,dumponexit=true -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
```

实际latency5-priority/profile/client.command：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-latency5-priority-profile/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-latency5-priority-profile/window-256/end_to_end/client-%p.jfr\,maxsize=256m\,dumponexit=true -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-latency5-priority-profile/window-256/end_to_end/jmh.json
```

NMT结束reserved/committed各类（非峰值）：
```text
Total: reserved=3168918KB, committed=732874KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                     Class (reserved=1049243KB, committed=2715KB)
-                    Thread (reserved=69796KB, committed=2696KB)
-                      Code (reserved=262465KB, committed=51417KB)
-                        GC (reserved=92285KB, committed=71813KB)
-                 GCCardSet (reserved=16KB, committed=16KB)
-                  Compiler (reserved=442KB, committed=442KB)
-                     JVMCI (reserved=140KB, committed=140KB)
-                  Internal (reserved=1603KB, committed=1603KB)
-                     Other (reserved=9413KB, committed=9413KB)
-                    Symbol (reserved=4923KB, committed=4923KB)
-    Native Memory Tracking (reserved=3069KB, committed=3069KB)
-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
-               Arena Chunk (reserved=163KB, committed=163KB)
-                   Tracing (reserved=18777KB, committed=18777KB)
-                   Logging (reserved=0KB, committed=0KB)
-                    Module (reserved=295KB, committed=295KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1287KB, committed=1287KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65725KB, committed=25789KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB, committed=1KB)
```

关键原始证据（清理后仅历史定位）：

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| /tmp/core-latency5-source.diff | 17160 | `865574a41e7f92398bb8430bb9dc0953fff89c5010742f8274c8dab690e73395` |
| /tmp/core-latency5-plain/window-256/end_to_end/metrics.json | 3129 | `40f339565affa05841f66e035fb16d6719d0ecab8da2260a13849a8b9d384bf5` |
| /tmp/core-latency5-plain/window-256/end_to_end/client.log | 8276 | `720d79e3648f03fdd765a9542a86d78c0d4f13871bfbc6b9d6f3741525a355d7` |
| /tmp/core-latency5-profile/window-256/end_to_end/metrics.json | 3279 | `d043dd7e757486d69a25f2c03554651c0e18333a20e6cf295d45351f02f97982` |
| /tmp/core-latency5-profile/window-256/end_to_end/client.log | 8884 | `91f2903fe94eab84a372e70f930ce8990a1ed43c06fc83ab9ceab299df0006b6` |
| /tmp/core-latency5-profile/window-256/end_to_end/client-91593.jfr | 5756919 | `bc62fc013c858c67e6e8e95832693a14102b1dc5dbaa0df2f7287dcf7aef428e` |
| /tmp/core-latency5-profile/window-256/end_to_end/client-91605.jfr | 89344095 | `eec7fc1012f430b6825f545549494be4878cb1ec4796169fa7090c3b263eaa1b` |
| /tmp/core-latency5-profile/window-256/end_to_end/node.jfr | 117978145 | `0ce66e4544779276bc7d5cc34c32bbf6e5487ec0e8404228c405bbc404c370fb` |
| /tmp/core-latency5-profile/window-256/end_to_end/analysis.json | 77241 | `3d97299d82642727a48a91707585925a89ae06ab76250202250b961b94a1d6d3` |
| /tmp/core-latency5-profile/window-256/end_to_end/phases.json | 4969 | `688938b0e991c4aec1a9e4c167354c10420d6be624a54b58a8f979c286e9aff4` |
| /tmp/core-latency5-profile/window-256/end_to_end/settlement-correlation.json | 2263034 | `af27e3f129809f4798228e778b8b0700162f4044ba9dfecc8a9f3ecf14b32a44` |
| /tmp/core-latency5-profile/window-256/end_to_end/owner-window-io.json | 18289 | `9ceb5875290e4df2f7426eb969750c6a5ca8e2bd9344053822008bc904be7138` |
| /tmp/core-latency5-profile/window-256/end_to_end/jfr-summary.txt | 12220 | `a990b7712a56998c92650e0a718c5edf85a4757e4cf5f4e7d06ab0890f237a46` |
| /tmp/core-latency5-priority-source.diff | 18725 | `9f1ddb9b35121f6c882af1ed045c33aadba5763197306784dd97d87498ba2b1e` |
| /tmp/core-latency5-priority-plain/window-256/end_to_end/metrics.json | 3133 | `6cc01cc910b9153bc396b42be8e43a12a2c9eb069178707715447c2b07aee7de` |
| /tmp/core-latency5-priority-plain/window-256/end_to_end/client.log | 8295 | `582103474444d037280be8c849aa66c26b055327bbf20f5580bacaa83e0e2606` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/metrics.json | 3280 | `6d01cde7ad272568df6a020a1df3f652dcba925977315fd55fb499d9f6d96a07` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/client.log | 8904 | `5dde9983b024b69b3bbfedc0fa5d55076d8bc131f130f9d53dbfb7fc2dfa65b0` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/client-95133.jfr | 5913439 | `cc037c5320bde69b79f9ae898e5dc4ed662a2e2a5dad58b2127507825ae06702` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/client-95138.jfr | 95092044 | `2d92f0b6fcc016658be939c74a4866d8e1bdb4c0c9bf41160e4f10f82961bb66` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/node.jfr | 105576603 | `53f29b504af127eac22ede58ed563dfb679ce5faa704bfa09995d9ca32a12273` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/analysis.json | 86363 | `105b918bde85f0b08057013f5c31a6d69fe12da02d7de855cd127cc9a678a6ef` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/phases.json | 5024 | `e8f923748a9bc577db2a2751b7d07653e7dc7d6cde08280490f15877715dd669` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/settlement-correlation.json | 3013572 | `cffee1d8831b8515a8375e2eef3d0b52188935b85ed7cd71d4b4490551ed4a08` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/owner-window-io.json | 18289 | `ef4647c4d9d0dd2e2f7bfa3dcd6911a5854042bdc2b81ba2f6889ef3044e23ab` |
| /tmp/core-latency5-priority-profile/window-256/end_to_end/jfr-summary.txt | 12219 | `5c4433f39cee675c896a36f215a457d520e90652b8c025a67dc23e8d19bcd2de` |
| /tmp/core-latency5-micro/plain.json | 3587 | `7f3bb2ec5ffbfe70ac612d335654190b1db685217e28e76e0db3cddb19772f45` |
| /tmp/core-latency5-micro/gc.json | 9816 | `a0aa2184c2123ce3b11b84a6bc44a26e8e3e79d6de264a39872214640cbee159` |
| /tmp/core-latency5-test-counts.json | 216 | `e6cf9fbe8ad96576c3819da51fb3f9e3073fbc806d015d7b3fa5964e93322f8b` |
| /tmp/core-latency5-priority-full-tests.log | 215092 | `146a783cc18439c2ce8ee9add66833ccc089e7d6e35dc79705bf5d417b9a6b84` |
| /tmp/core-latency5-priority-bench-tests.log | 49641 | `6169c3dc7ca5d15721c7fc2709d0bc8d4fafe71c2a48c5ac7ba7d8d6dcb8dede` |

分段事件校验latency5：{"laneCountHistogram": {"1": 7968, "2": 1322}, "negativeStageSamples": 0}；仅在完成标记acquire后读取Lane时刻。原始摘要文件/tmp/core-latency5-profile/window-256/end_to_end/latency-sanity.json，SHA-256 `2e1af6048ea442622dc2cb81f94a7012be1b0bdc6ae9703ac04bd1ae05731496`。

分段事件校验latency5-priority：{"laneCountHistogram": {"1": 10555, "2": 1764}, "negativeStageSamples": 0}；仅在完成标记acquire后读取Lane时刻。原始摘要文件/tmp/core-latency5-priority-profile/window-256/end_to_end/latency-sanity.json，SHA-256 `0221ee587545baa1435ce485ddd37a85f6634cacb6a05b8013eae08f86fa42f5`。

清理完成：本批两组单节点plain/JFR、系统采样、分析与JMH均退出；删除本轮临时data/Archive/media、JFR、日志、脚本及测试报告，共531个文件，路径/大小/摘要清单SHA-256 `77d4bcbc979444f0c3b6f64183201aabd24aeb7126a65598bdd362f81b8f679d`。构建产物保留；未创建云资源。上述临时路径仅历史定位。


## 2026-09-15 latency6：补齐 Owner 前置与响应出口耗时（采集前计划）

- 当前 master `6e108584a2cc99ae951a830f34e4beedd5c1db6f`；对照 commit 不适用，仅本工作树先诊断、再单因素修改。问题：后撮合段已缩短，完整普通订单 p99 仍未达 5ms，不能相减不同分位数推断前置耗时。
- 业务步骤：日志复制输入→Owner输入FIFO→依赖满足后的准入/冻结→既有Matcher/Lane→有序提交→输出FIFO→编码与egress。仅在现有输入信封、复用入口槽、输出信封携带采样时间；同一commandId散列1/64，新增JFR边界事件标明commandId两段long，无逐命令Map、日志、业务状态或协议修改。默认关闭；生命周期控制项不采样。输出采样截止编码前，不含慢会话重试。
- 先 correctness：JDK25 HotSpot/GraalVM25.0.1、Maven3.9.16已核对；服务及依赖，ContinuousOwnerBenchmarkTest（含六线资金/快照/慢出口）、ClusteredBatchTradingBenchmarkTest。诊断开启验证真实跨线程事件；默认关闭回归。之后基线plain与独立JFR各30s预热+30s测量，沿用既有脚本固定全局/session/Owner256、1Matcher/4Lane、128symbols、MIXED20、seed25620、G1、BUSY_SPIN，Core512/1536m/client128/512m，单节点本机，零云资源，不改发压策略。
- 命令：`ASYNC_RUN_ID=20260915-latency6-{plain,profile} ASYNC_ARTIFACT_DIR=/tmp/core-latency6-{plain,profile} ASYNC_WINDOWS=256 ASYNC_WARMUP_SECONDS=30 ASYNC_MEASURE_SECONDS=30 ASYNC_ONLY_STAGE=end_to_end ASYNC_COLLECTOR=G1 ASYNC_ENABLE_JFR={false,true} ASYNC_SKIP_BUILD={false,true} bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh`。各独立节点/客户端JFR上限256m，沿用owner-commit-profile.jfc，NMT、线程、两秒ps/vm_stat/磁盘样本；按客户端epoch窗口分析分段、CPU、分配、GC与I/O。采集结束才解析大文件。
- 此步是诊断，不预设性能提升；业务验收持续终态>=300000ops/s且普通订单p99<=5ms，零错误/超时/拒绝/unfinished、资金差0、快照正确。环境swap增长/DataLoss或主要预热未完成记录无效/缺口，不当容量结论；端到端256背压负载报告实际速率，未做coordinated-omission修正。锁定后续修改及保留门槛须在第二次采集之前追加。


### latency6-decode：采样定位后的单因素修改计划（修改及采集前锁定）

latency6同工作树基线：plain316894.539业务ops/s、普通p9916.162ms；JFR302736.963ops/s，Owner97.43%单核。普通输入FIFO均值1.280/p994.080ms、入口→准入均值3.982/p999.867ms、准入执行p990.044ms、输出FIFO p990.092ms；同commandId Core已复制输入→编码前p9913.214ms（3386样本）。批量下单入口等待p998.324ms、准入执行p990.025ms。Owner执行栈首位TradingOrderBatchCodec.decodeCommand（正式窗63叶样本），分配抽样也居首，主要位于prepareClusterPipelineScope；不是账户计算一次执行就耗费10ms。约1985.7B/business op；GC60次/313.222ms，最大7.250ms。后撮合、输入和输出队列不同分位数不可相加；端到端仍包括尚未测到的客户端/网络与延迟重试。

本次仅移动四种高频订单命令（普通/批量下单、撤单）的纯协议解码到ContinuousTradingClusterService已有Aeron输入线程，Input与既有PendingClusterIngress槽携带同一个DecodedMatchingCommand；Owner既有窗口缓存接收该对象，路由缓存仍只由Owner写。无新线程、Map或第二套业务事件。非法payload在解码对象中携带原异常，到Owner原有校验/拒绝路径再抛出；不在传输线程拒单、丢弃日志或读取账户，非高频控制路径照旧。快照/角色切换仍FIFO，释放槽时清空解码引用。

预锁保留门槛：正确性全通过；同配置plain持续吞吐>=301049.81（基线95%）且普通p99<=15.354ms（基线降低5%），JFR Owner解码叶样本消失/搬到服务线程、普通入口→准入p99<=8.880ms（降低10%）。若未达先记录并撤回本次业务改动，保留诊断证据；不靠调整负载/窗口/GC参数过线。业务5ms验收目标不变。先服务及依赖、跨线程/六线快照/批量测试；再latency6-decode/plain、profile同脚本同参数，同工作树source.diff哈希。JMH更新ContinuousOwnerBenchmark覆盖输入线程预解码，batch1/20，LINEAR_PERPETUAL、BUSY_SPIN、3x1s预热/3x1s测量、1fork/1thread、G1独立plain及-prof gc；微基准仅验证真实交接路径成本不作交易容量。


采集有效性补查（latency6-decode采集前）：latency6/plain正式窗15个系统样本有128pages swap-in、0swap-out，因此按严格环境门槛该容量对照无效；profile14样本swap-in/out均0、JFR DataLoss0，阶段归因可用。上述plain数值保留为现象，不能据此宣称精确提升百分比。保留判断追加：修改后plain须无swap增长、吞吐>=300000且普通p99<=15ms，并满足原JFR准入等待降低/热点迁移条件；最终仍只能部分验证，5ms门槛不降。若希望严格百分比比较需另锁新基线，不回跑历史commit。

首轮latency6-decode correctness：新增畸形命令测试6条产品线断言失败，原因是串行对照把6条setup成功响应也计入待比较列表；生产两边均返回4条INVALID_COMMAND。已在测试setup后清空响应列表，后续重跑，不将失败掩盖成已通过。


latency6-decode/plain首次结果：355051.838ops/s、普通p9914.516ms、资金差0、offered=terminal10659140业务/1018692消息、unfinished0。但正式窗15个系统样本出现282pages swap-in，容量结论按标准无效，不能据此算提升。当前profile仍在运行；锁定一次同代码、同参数、同30+30时长plain复测`latency6-decode-repeat`（仅plain、skipBuild=true，不变负载），用于环境有效性核查。若仍有swap或预热缺口，最终性能保留部分验证，不把环境异常解释成代码性能回退。


latency6-decode拒绝保留：profile正式窗无swap、DataLoss0，Owner解码叶样本从63变0，服务线程出现32解码叶样本；但普通入口→准入p999.504ms（原9.867ms）未达预锁<=8.880ms，仅均值3.982→3.363ms。profile327448.626ops/s、普通p9916.424ms、约1996.8B/op（没有降低分配），GC65次/336.410ms/max6.154ms。按预案撤回解码前移及其专属测试/交接字段，保留最初低比例诊断。未启动的decode-repeat取消，不以额外复测挑选有利结果。该尝试正确性最终通过，但性能保留门槛失败。

### latency6-index：终态活跃订单索引去重复查找（修改及采集前锁定）

Owner getMapped的完整栈集中于ActiveOrderIndex.applyRuntime→RuntimeFactIndexes.order→visitChangedIndexes，终态分支当前get(orderId)再remove(orderId)探测同一键。仅改为remove直接取得原索引项，再移除账户/币对/对手方二级索引；不存在项仍无操作，OPEN更新分支不变，删除原仅承担重复删除的private remove方法。无新增状态、字段、缓存或协议。输入解码保持本轮基线位置。

验收：六线资金/依赖/快照及索引终态/重复删除/共享用户币对回归；新增JMH真实ActiveOrderIndex终态退出，普通空索引与同用户多订单存量混合，当前工作树单因素前后各3x1s/3x1s、1fork/1thread/G1，plain与-prof gc分开。局部保留要求正确性全通过、终态退出微基准无>5%回退且无新增分配；单节点plain/JFR同原固定256/1Matcher/4Lane/128symbols/MIXED20/30+30参数，主链路退化门槛profile普通准入等待p99不超过基线9.867ms的105%=10.360ms，plain>=300000ops/s。环境无效只能部分验证不计算提升；5ms业务门槛保持未达，不承诺这项小优化独自解决全部尾延迟。

## 2026-09-15 latency6 最终归档：准入排队定位与终态索引清理
结论：正确性通过；保留终态活跃订单索引直接remove取得旧项、删除重复get和private remove转发；保留默认关闭的1/64队列诊断。解码前移按预锁门槛撤回，其字段和专属测试已删除。最终索引微基准均值改善约6%～8%，区间重叠，不能据此证明Core吞吐提升。所有plain及最终index/profile出现系统swap-in，容量验收无效；原始latency6/profile无swap且DataLoss0，足以定位前置排队。普通p99≤5ms仍未满足，整体为部分验证。
环境：本机i9-9880H 2.30GHz，8物理核/16逻辑CPU，16GiB内存，macOS26.7/25G229；非容器，未设置CPU配额，未绑核；HotSpot Oracle GraalVM25.0.1、Maven3.9.16。开始约456GiB/结束约445GiB可用。均为当前master 6e108584及本轮工作树改动；对照commit不适用，未检出旧版。无云资源。固定配置及业务初始化见上述锁定计划和最终完整命令；六次独立新建单节点，未改负载/窗口/GC/币对/Matcher数。
业务步骤及所有权：Owner消费已复制命令→既有FIFO等待依赖/构建scope→准入调用→Matcher/Lane→Owner按序发布终态→移除活跃订单及二级索引→输出FIFO。终态删除只改变索引查找次数，无新业务状态；重复终态、未知ID无操作，共享账户/币对的其他订单保留。新增JFR类只负责实际线程/队列边界，复用既有CoreMatchingPhaseMetrics；Input/Output各多一个long，入口槽多一个long，不新增逐命令容器或业务对象。默认关闭时不创建JFR事件、不读nanoTime；现有输入/输出信封尺寸约各增8B，不能声称完全零内存成本。
测试命令及范围：
```text
mvn -q -pl surprising-aeron-core/surprising-aeron-service -am -Dcore.settlementLatencyDiagnostics=true test
mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=ClusteredBatchTradingBenchmarkTest,ContinuousOwnerBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```
最终XML计数：`{"surprising-product-api": {"tests": 12, "failures": 0, "errors": 0, "skipped": 0}, "surprising-instrument/surprising-instrument-api": {"tests": 12, "failures": 0, "errors": 0, "skipped": 0}, "surprising-aeron-core/surprising-aeron-benchmarks": {"tests": 144, "failures": 0, "errors": 0, "skipped": 1}, "surprising-aeron-core/surprising-aeron-service": {"tests": 963, "failures": 0, "errors": 0, "skipped": 0}, "surprising-aeron-core/surprising-aeron-client": {"tests": 49, "failures": 0, "errors": 0, "skipped": 0}, "surprising-aeron-core/surprising-aeron-protocol": {"tests": 110, "failures": 0, "errors": 0, "skipped": 0}}`。共1290个用例，最终执行1289、默认关闭诊断时跳过1个；该跨线程诊断用例已在本轮初始开启诊断时通过。六线资金、订单终态、依赖、快照恢复及慢出口由服务/批量/ContinuousOwner测试覆盖。未启动wallet或外围服务，未做三节点/GCP/长稳；本次没有新增长期索引或缓存，不据短测声称无泄漏。
吞吐均取steady终态增量/steady秒数；排空另报。业务ops按批量item展开，Core消息单列，fill不计入订单ops。MIXED每place/cancel批20项，平均约10.46业务项/Core消息；行情更新另报。吞吐是256背压闭环的已达负载，没有预设恒定到达率，没有coordinated-omission修正；延迟含测量边界后排空的请求完成，终态时刻取客户端回调，不是FIFO reap时刻。
| 变体/模式 | steady业务ops/s | Core消息/s | fills/s | 普通p99 ms | drain ms/业务项 | 满窗等待占测量时间 | 系统swap-in/out pages |
|---|---:|---:|---:|---:|---|---:|---|
| latency6/plain | 316894.539 | 30296.514 | 75441.844 | 16.162 | 22.941/2684 | 85.05% | 128/0 |
| latency6/profile | 302736.963 | 28948.145 | 72071.012 | 17.203 | 7.779/2678 | 84.81% | 0/0 |
| latency6-decode/plain | 355051.838 | 33932.478 | 84526.418 | 14.516 | 7.324/2681 | 84.64% | 282/0 |
| latency6-decode/profile | 327448.626 | 31302.117 | 77954.614 | 16.424 | 7.869/2682 | 84.11% | 0/0 |
| latency6-index/plain | 350929.052 | 33538.295 | 83545.196 | 15.179 | 7.400/2679 | 85.48% | 64/0 |
| latency6-index/profile | 321164.586 | 30703.597 | 76458.412 | 16.269 | 7.367/2679 | 83.18% | 256/0 |
系统采样2s一次，表中为落在正式epoch窗口内14～15个样本首尾差；边缘约2s分辨率，swap-out均0不代表无swap干扰。各轮JFR都无DataLoss；基线plain128、decode/plain282、index/plain64、index/profile256pages swap-in已保留，不算作有效容量提升。

latency6/plain 原始业务/完成性摘要（单位us，meanus也为us；所有类型未采独立accepted延迟，不能用terminal直方图替代）：
```text
measurementStartEpochMillis=1789481662357
measurementEndEpochMillis=1789481692389
steadyCapacity elapsedSeconds=30.031079 terminalBusinessOperations=9516685 terminalCoreMessages=909837 fills=2265600 businessOpsPerSec=316894.539 coreMessagesPerSec=30296.514 fillsPerSec=75441.844 peakInFlight=256 windowBlockedCount=216196 windowBlockedNanos=25540432343
pipelineHighWater matcher=119 completion=89 context=136 lanes=[41, 44, 48, 44]
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1807 businessHash=8287433a83d42acd
mixedCapacity=PASS elapsedSeconds=30.054 terminalBusinessOperations=9519369 offeredBusinessOperations=9519369 terminalCoreMessages=910089 offeredCoreMessages=910089 businessOpsPerSec=316741.947 coreMessagesPerSec=30281.772 fills=2265600 fillsPerSec=75384.257 queries=0 unfinished=0 peakInFlight=256 measuredCycles=885 totalCycles=1807 triggerExecutions=0
business=PLACE_ORDER items=226560 requests=226560 p50us=6393 p90us=11198 p95us=12189 p99us=16162 p999us=28868 maxus=47710 meanus=7159.038
business=CANCEL_ORDER items=226560 requests=226560 p50us=6430 p90us=8527 p95us=9420 p99us=14540 p999us=24903 maxus=50921 meanus=6054.936
business=APPLY_MARK_PRICE items=3849 requests=3849 p50us=6217 p90us=11886 p95us=14516 p99us=26640 p999us=42958 maxus=45252 meanus=7443.549
business=PLACE_ORDER_BATCH items=6796800 requests=339840 p50us=7307 p90us=14401 p95us=15548 p99us=19496 p999us=35061 maxus=50921 meanus=8728.121
business=CANCEL_ORDER_BATCH items=2265600 requests=113280 p50us=14368 p90us=16343 p95us=17432 p99us=31735 p999us=44466 maxus=49905 meanus=14772.264
```
Lane usefulExecutionRatio（测量+排空；busy CPU不是业务饱和）：{'0': 0.4108, '1': 0.4094, '2': 0.4127, '3': 0.409}。期末offered=terminal，unfinished=0，资金差0；失败/超时会使客户端PASS失败，本轮均PASS。普通持续单笔MATCH_STREAM未另跑，本轮仅MIXED，不混用口径。

latency6/profile 原始业务/完成性摘要（单位us，meanus也为us；所有类型未采独立accepted延迟，不能用terminal直方图替代）：
```text
measurementStartEpochMillis=1789481767016
measurementEndEpochMillis=1789481797031
steadyCapacity elapsedSeconds=30.014842 terminalBusinessOperations=9086602 terminalCoreMessages=868874 fills=2163200 businessOpsPerSec=302736.963 coreMessagesPerSec=28948.145 fillsPerSec=72071.012 peakInFlight=256 windowBlockedCount=216961 windowBlockedNanos=25456282587
pipelineHighWater matcher=128 completion=55 context=128 lanes=[40, 39, 42, 43]
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1676 businessHash=eb149db84584d152
mixedCapacity=PASS elapsedSeconds=30.023 terminalBusinessOperations=9089280 offeredBusinessOperations=9089280 terminalCoreMessages=869120 offeredCoreMessages=869120 businessOpsPerSec=302747.725 coreMessagesPerSec=28948.839 fills=2163200 fillsPerSec=72052.339 queries=0 unfinished=0 peakInFlight=256 measuredCycles=845 totalCycles=1676 triggerExecutions=0
business=PLACE_ORDER items=216320 requests=216320 p50us=6655 p90us=11476 p95us=12509 p99us=17203 p999us=46497 maxus=87949 meanus=7492.658
business=CANCEL_ORDER items=216320 requests=216320 p50us=6586 p90us=9003 p95us=9854 p99us=16039 p999us=31145 maxus=51347 meanus=6382.125
business=APPLY_MARK_PRICE items=3840 requests=3840 p50us=6660 p90us=12238 p95us=14319 p99us=20234 p999us=28164 maxus=29179 meanus=7707.608
business=PLACE_ORDER_BATCH items=6489600 requests=324480 p50us=7696 p90us=14942 p95us=16130 p99us=20938 p999us=38207 maxus=91684 meanus=9141.759
business=CANCEL_ORDER_BATCH items=2163200 requests=108160 p50us=14704 p90us=16957 p95us=19054 p99us=35454 p999us=88145 maxus=91619 meanus=15328.925
```
Lane usefulExecutionRatio（测量+排空；busy CPU不是业务饱和）：{'0': 0.4094, '1': 0.404, '2': 0.4027, '3': 0.4022}。期末offered=terminal，unfinished=0，资金差0；失败/超时会使客户端PASS失败，本轮均PASS。普通持续单笔MATCH_STREAM未另跑，本轮仅MIXED，不混用口径。

latency6-decode/plain 原始业务/完成性摘要（单位us，meanus也为us；所有类型未采独立accepted延迟，不能用terminal直方图替代）：
```text
measurementStartEpochMillis=1789482438920
measurementEndEpochMillis=1789482468988
steadyCapacity elapsedSeconds=30.013812 terminalBusinessOperations=10656459 terminalCoreMessages=1018443 fills=2536960 businessOpsPerSec=355051.838 coreMessagesPerSec=33932.478 fillsPerSec=84526.418 peakInFlight=256 windowBlockedCount=218648 windowBlockedNanos=25404744830
pipelineHighWater matcher=116 completion=69 context=138 lanes=[40, 48, 43, 45]
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=2014 businessHash=9fdd3bead1af810e
mixedCapacity=PASS elapsedSeconds=30.021 terminalBusinessOperations=10659140 offeredBusinessOperations=10659140 terminalCoreMessages=1018692 offeredCoreMessages=1018692 businessOpsPerSec=355054.524 coreMessagesPerSec=33932.494 fills=2536960 fillsPerSec=84505.797 queries=0 unfinished=0 peakInFlight=256 measuredCycles=991 totalCycles=2014 triggerExecutions=0
business=PLACE_ORDER items=253696 requests=253696 p50us=5730 p90us=10182 p95us=11223 p99us=14516 p999us=23003 maxus=44367 meanus=6433.629
business=CANCEL_ORDER items=253696 requests=253696 p50us=5390 p90us=7467 p95us=8245 p99us=11870 p999us=17760 maxus=24969 meanus=5297.835
business=APPLY_MARK_PRICE items=3908 requests=3908 p50us=5705 p90us=10174 p95us=11829 p99us=15613 p999us=30244 maxus=30539 meanus=6532.544
business=PLACE_ORDER_BATCH items=7610880 requests=380544 p50us=6369 p90us=12902 p95us=14049 p99us=17989 p999us=29868 maxus=53805 meanus=7716.568
business=CANCEL_ORDER_BATCH items=2536960 requests=126848 p50us=13123 p90us=15065 p95us=17416 p99us=26656 p999us=41779 maxus=53247 meanus=13503.819
```
Lane usefulExecutionRatio（测量+排空；busy CPU不是业务饱和）：{'0': 0.4243, '1': 0.4273, '2': 0.4251, '3': 0.4249}。期末offered=terminal，unfinished=0，资金差0；失败/超时会使客户端PASS失败，本轮均PASS。普通持续单笔MATCH_STREAM未另跑，本轮仅MIXED，不混用口径。

latency6-decode/profile 原始业务/完成性摘要（单位us，meanus也为us；所有类型未采独立accepted延迟，不能用terminal直方图替代）：
```text
measurementStartEpochMillis=1789482547514
measurementEndEpochMillis=1789482577530
steadyCapacity elapsedSeconds=30.015414 terminalBusinessOperations=9828506 terminalCoreMessages=939546 fills=2339840 businessOpsPerSec=327448.626 coreMessagesPerSec=31302.117 fillsPerSec=77954.614 peakInFlight=256 windowBlockedCount=219698 windowBlockedNanos=25245905305
pipelineHighWater matcher=128 completion=85 context=133 lanes=[43, 42, 41, 44]
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1821 businessHash=2ec206119dada4f5
mixedCapacity=PASS elapsedSeconds=30.023 terminalBusinessOperations=9831188 offeredBusinessOperations=9831188 terminalCoreMessages=939796 offeredCoreMessages=939796 businessOpsPerSec=327452.130 coreMessagesPerSec=31302.240 fills=2339840 fillsPerSec=77934.182 queries=0 unfinished=0 peakInFlight=256 measuredCycles=914 totalCycles=1821 triggerExecutions=0
business=PLACE_ORDER items=233984 requests=233984 p50us=6139 p90us=11051 p95us=12107 p99us=16424 p999us=29163 maxus=55050 meanus=6964.455
business=CANCEL_ORDER items=233984 requests=233984 p50us=6103 p90us=7884 p95us=8699 p99us=14893 p999us=21069 maxus=46104 meanus=5698.372
business=APPLY_MARK_PRICE items=3860 requests=3860 p50us=6344 p90us=11952 p95us=14172 p99us=31014 p999us=37650 maxus=38141 meanus=7481.423
business=PLACE_ORDER_BATCH items=7019520 requests=350976 p50us=6758 p90us=14008 p95us=15138 p99us=20463 p999us=52101 maxus=59899 meanus=8377.094
business=CANCEL_ORDER_BATCH items=2339840 requests=116992 p50us=14131 p90us=16113 p95us=19103 p99us=31948 p999us=55672 maxus=59506 meanus=14687.070
```
Lane usefulExecutionRatio（测量+排空；busy CPU不是业务饱和）：{'0': 0.4409, '1': 0.4373, '2': 0.4374, '3': 0.4313}。期末offered=terminal，unfinished=0，资金差0；失败/超时会使客户端PASS失败，本轮均PASS。普通持续单笔MATCH_STREAM未另跑，本轮仅MIXED，不混用口径。

latency6-index/plain 原始业务/完成性摘要（单位us，meanus也为us；所有类型未采独立accepted延迟，不能用terminal直方图替代）：
```text
measurementStartEpochMillis=1789483204168
measurementEndEpochMillis=1789483234198
steadyCapacity elapsedSeconds=30.029255 terminalBusinessOperations=10538138 terminalCoreMessages=1007130 fills=2508800 businessOpsPerSec=350929.052 coreMessagesPerSec=33538.295 fillsPerSec=83545.196 peakInFlight=256 windowBlockedCount=213023 windowBlockedNanos=25669540437
pipelineHighWater matcher=120 completion=71 context=128 lanes=[45, 44, 46, 43]
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1988 businessHash=edeef2b9057769bb
mixedCapacity=PASS elapsedSeconds=30.037 terminalBusinessOperations=10540817 offeredBusinessOperations=10540817 terminalCoreMessages=1007377 offeredCoreMessages=1007377 businessOpsPerSec=350931.786 coreMessagesPerSec=33538.255 fills=2508800 fillsPerSec=83524.613 queries=0 unfinished=0 peakInFlight=256 measuredCycles=980 totalCycles=1988 triggerExecutions=0
business=PLACE_ORDER items=250880 requests=250880 p50us=5713 p90us=9887 p95us=10928 p99us=15179 p999us=47644 maxus=53411 meanus=6459.167
business=CANCEL_ORDER items=250880 requests=250880 p50us=5554 p90us=7823 p95us=8511 p99us=13336 p999us=29245 maxus=49217 meanus=5518.166
business=APPLY_MARK_PRICE items=3857 requests=3857 p50us=6025 p90us=10960 p95us=13049 p99us=46399 p999us=49283 maxus=49446 meanus=7219.783
business=PLACE_ORDER_BATCH items=7526400 requests=376320 p50us=6619 p90us=12804 p95us=14049 p99us=18612 p999us=43057 maxus=52789 meanus=7876.152
business=CANCEL_ORDER_BATCH items=2508800 requests=125440 p50us=12713 p90us=14966 p95us=16859 p99us=30916 p999us=50823 maxus=55279 meanus=13217.809
```
Lane usefulExecutionRatio（测量+排空；busy CPU不是业务饱和）：{'0': 0.4042, '1': 0.4067, '2': 0.403, '3': 0.4019}。期末offered=terminal，unfinished=0，资金差0；失败/超时会使客户端PASS失败，本轮均PASS。普通持续单笔MATCH_STREAM未另跑，本轮仅MIXED，不混用口径。

latency6-index/profile 原始业务/完成性摘要（单位us，meanus也为us；所有类型未采独立accepted延迟，不能用terminal直方图替代）：
```text
measurementStartEpochMillis=1789483310993
measurementEndEpochMillis=1789483341022
steadyCapacity elapsedSeconds=30.033582 terminalBusinessOperations=9645723 terminalCoreMessages=922139 fills=2296320 businessOpsPerSec=321164.586 coreMessagesPerSec=30703.597 fillsPerSec=76458.412 peakInFlight=256 windowBlockedCount=211093 windowBlockedNanos=24982369489
pipelineHighWater matcher=128 completion=70 context=133 lanes=[46, 44, 45, 38]
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1826 businessHash=898ced4faeeee684
mixedCapacity=PASS elapsedSeconds=30.041 terminalBusinessOperations=9648402 offeredBusinessOperations=9648402 terminalCoreMessages=922386 offeredCoreMessages=922386 businessOpsPerSec=321175.000 coreMessagesPerSec=30704.289 fills=2296320 fillsPerSec=76439.661 queries=0 unfinished=0 peakInFlight=256 measuredCycles=897 totalCycles=1826 triggerExecutions=0
business=PLACE_ORDER items=229632 requests=229632 p50us=6266 p90us=10895 p95us=11878 p99us=16269 p999us=32161 maxus=73596 meanus=7026.740
business=CANCEL_ORDER items=229632 requests=229632 p50us=6213 p90us=8368 p95us=9199 p99us=13795 p999us=23035 maxus=46759 meanus=5938.483
business=APPLY_MARK_PRICE items=3858 requests=3858 p50us=6582 p90us=11943 p95us=14065 p99us=17924 p999us=32358 maxus=37453 meanus=7459.507
business=PLACE_ORDER_BATCH items=6888960 requests=344448 p50us=7147 p90us=14155 p95us=15278 p99us=19922 p999us=44236 maxus=77004 meanus=8614.981
business=CANCEL_ORDER_BATCH items=2296320 requests=114816 p50us=13967 p90us=16179 p95us=18661 p99us=34013 p999us=73596 maxus=77004 meanus=14622.678
```
Lane usefulExecutionRatio（测量+排空；busy CPU不是业务饱和）：{'0': 0.4076, '1': 0.4118, '2': 0.4136, '3': 0.4079}。期末offered=terminal，unfinished=0，资金差0；失败/超时会使客户端PASS失败，本轮均PASS。普通持续单笔MATCH_STREAM未另跑，本轮仅MIXED，不混用口径。

latency6 JFR正式窗口：
| 边界/类型 | n | mean ms | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| admissionExecution/CANCEL_ORDER | 3372 | 0.008 | 0.004 | 0.018 | 0.020 | 0.031 | 0.043 | 0.059 |
| admissionExecution/CANCEL_ORDER_BATCH | 1686 | 0.008 | 0.008 | 0.009 | 0.011 | 0.022 | 0.046 | 0.122 |
| admissionExecution/PLACE_ORDER | 3386 | 0.011 | 0.008 | 0.020 | 0.024 | 0.044 | 0.101 | 0.124 |
| admissionExecution/PLACE_ORDER_BATCH | 5069 | 0.009 | 0.007 | 0.009 | 0.011 | 0.025 | 0.052 | 5.704 |
| ingressToAdmission/CANCEL_ORDER | 3372 | 2.993 | 2.924 | 3.971 | 5.199 | 8.774 | 14.877 | 18.537 |
| ingressToAdmission/CANCEL_ORDER_BATCH | 1686 | 5.443 | 5.349 | 7.553 | 8.550 | 12.259 | 16.556 | 17.881 |
| ingressToAdmission/PLACE_ORDER | 3386 | 3.982 | 3.201 | 7.190 | 7.870 | 9.867 | 15.718 | 19.160 |
| ingressToAdmission/PLACE_ORDER_BATCH | 5069 | 2.590 | 2.548 | 4.098 | 4.938 | 8.324 | 13.868 | 16.738 |
| ingressToControl/APPLY_MARK_PRICE | 32 | 5.438 | 4.356 | 8.960 | 9.931 | 11.249 | 11.249 | 12.643 |
| ownerToEgress/APPLY_MARK_PRICE | 32 | 0.011 | 0.005 | 0.025 | 0.038 | 0.038 | 0.038 | 0.057 |
| ownerToEgress/CANCEL_ORDER | 3372 | 0.011 | 0.006 | 0.023 | 0.031 | 0.072 | 0.391 | 0.951 |
| ownerToEgress/CANCEL_ORDER_BATCH | 1686 | 0.011 | 0.007 | 0.021 | 0.030 | 0.079 | 0.175 | 0.194 |
| ownerToEgress/PLACE_ORDER | 3386 | 0.013 | 0.008 | 0.026 | 0.035 | 0.092 | 0.378 | 0.948 |
| ownerToEgress/PLACE_ORDER_BATCH | 5070 | 0.016 | 0.007 | 0.025 | 0.035 | 0.105 | 0.371 | 9.113 |
| transportToOwner/APPLY_MARK_PRICE | 32 | 0.654 | 0.420 | 1.265 | 1.552 | 1.861 | 1.861 | 2.184 |
| transportToOwner/CANCEL_ORDER | 3372 | 1.129 | 0.601 | 2.858 | 3.285 | 3.991 | 7.718 | 9.873 |
| transportToOwner/CANCEL_ORDER_BATCH | 1686 | 1.279 | 1.250 | 2.091 | 2.372 | 3.959 | 7.717 | 7.768 |
| transportToOwner/PLACE_ORDER | 3388 | 1.280 | 0.942 | 2.983 | 3.403 | 4.080 | 7.927 | 11.187 |
| transportToOwner/PLACE_ORDER_BATCH | 5068 | 0.660 | 0.488 | 1.497 | 1.748 | 2.194 | 5.651 | 7.757 |
`ingressToAdmission`截止实际apply前，包含FIFO等待及prepareClusterPipelineScope工作；`admissionExecution`仅Owner apply/绑定，不等于Lane完成冻结；`transportToOwner`含指纹计算、Input FIFO（decode变体还含前移解码）；`ownerToEgress`截止编码前，不含网络发送重试。只有相同唯一commandId的当前负载样本可关联，重试/重放不能直接用此离线join。不要相加/相减各阶段分位数，也不要跨进程相减nanoTime。
同commandId已复制输入→编码前（同一JVM近似边界，包含JFR事件创建的微小耗时）：`{"CANCEL_ORDER": {"n": 3372, "mean": 4.690395298180631, "p50": 4.188002348754883, "p90": 6.695348721191406, "p95": 7.738996576416016, "p99": 11.132082643310547, "p999": 18.2727785078125, "max": 20.380835002075194}, "PLACE_ORDER": {"n": 3386, "mean": 5.804632945199803, "p50": 5.302061231079102, "p90": 8.622789245483398, "p95": 9.352637604614257, "p99": 13.21415485583496, "p999": 18.26406367492676, "max": 22.842400432128905}, "PLACE_ORDER_BATCH": {"n": 5068, "mean": 4.946888224670217, "p50": 4.796626985351563, "p90": 6.365408796508789, "p95": 7.3274162506103515, "p99": 11.160999053100586, "p999": 16.651660216918945, "max": 20.816413532348633}, "CANCEL_ORDER_BATCH": {"n": 1686, "mean": 8.225312178947549, "p50": 7.994406151245117, "p90": 10.448919580688477, "p95": 11.939820245483398, "p99": 15.89168907385254, "p999": 22.412291885253907, "max": 26.162228690429686}, "APPLY_MARK_PRICE": {"n": 32, "mean": 6.131658116436005, "p50": 5.137310948364258, "p90": 10.022377838134766, "p95": 10.134971500976562, "p99": 11.29863485925293, "p999": 11.29863485925293, "max": 14.240959616943359}}`。
Matcher发布→最终提交每样本完整区间p99/ms：`{"PLACE_ORDER_BATCH": {"matcherToLastLaneStartNanos": 0.671, "maxLaneExecutionNanos": 0.1404, "matcherToLanesCompleteNanos": 0.7341, "lanesCompleteToOwnerNanos": 1.4747, "ownerFinalCommitMs": 0.0536, "publicationToCommitMs": 1.6242}, "CANCEL_ORDER": {"matcherToLastLaneStartNanos": 0.4929, "maxLaneExecutionNanos": 0.0159, "matcherToLanesCompleteNanos": 0.4982, "lanesCompleteToOwnerNanos": 1.3418, "ownerFinalCommitMs": 0.0259, "publicationToCommitMs": 1.3925}, "PLACE_ORDER": {"matcherToLastLaneStartNanos": 1.1182, "maxLaneExecutionNanos": 0.008, "matcherToLanesCompleteNanos": 1.1231, "lanesCompleteToOwnerNanos": 1.2411, "ownerFinalCommitMs": 0.0238, "publicationToCommitMs": 1.6931}}`。Owner finalCommit只覆盖最终返回的调用；普通撤批/行情无完整Matcher分段，未填零。跨Lane最大启动与最大执行可来自不同Lane。
CPU折算单核百分比均值：`{"JVMCI-native CompilerThread0": 6.01, "C1 CompilerThread0": 0.97, "JFR Recorder Thread": 0.51, "JFR Periodic Tasks": 0.51, "aeron-md-nra": 1.78, "driver-conductor": 68.45, "/tmp/core-latency6-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]": 97.3, "archive-conductor": 72.37, "consensus-module-101-0": 61.7, "aeron-client": 0.24, "clustered-service-101-0": 77.17, "trading-owner--1": 97.43, "core-matcher-0": 56.21, "core-account-lane-0": 98.26, "core-account-lane-1": 98.28, "core-account-lane-2": 98.26, "core-account-lane-3": 98.28, "Monitor Deflation Thread": 0.1, "JVMCI-native CompilerThread1": 25.6}`；Owner叶热点：`[["com/surprising/aeron/protocol/TradingOrderBatchCodec.decodeCommand", 63], ["org/agrona/collections/Long2ObjectHashMap.getMapped", 44], ["org/agrona/collections/Long2ObjectHashMap.put", 42], ["java/util/HashMap.getNode", 31], ["java/util/Arrays.fill", 31], ["com/surprising/aeron/service/orchestration/TerminalTombstoneStore.bucket", 30], ["com/surprising/aeron/service/state/MatcherSettlementPlan.clearReferences", 27], ["com/surprising/aeron/service/orchestration/OrderBatchExecutor.preparePipelinedPlaceBatch", 25], ["com/surprising/aeron/service/state/LanePublication.publish", 22], ["org/agrona/collections/Long2LongHashMap.get", 19], ["com/surprising/aeron/service/state/MatcherSettlementDispatcher.prepareDirect", 17], ["org/agrona/collections/Long2ObjectHashMap.remove", 17]]`。
分配ThreadAllocationStatistics各线程首尾斜率之和601.149MB/s，除以该profile steady吞吐为1985.713B/business op；约1s采样边缘误差。线程bytes/s：`{"clustered-service-101-0": 32180296, "trading-owner--1": 90666941, "core-matcher-0": 147976435, "core-account-lane-0": 82573678, "core-account-lane-1": 82590058, "core-account-lane-2": 82581061, "core-account-lane-3": 82577398}`。
分配抽样权重top class（bytes，不是精确对象数）：`[["com/surprising/aeron/service/state/OrderRuntime", 2693965568], ["[B", 2377372080], ["com/surprising/aeron/service/state/ReservationRuntime", 1270007864], ["[J", 1222652192], ["exchange/core2/core/common/MatcherResult", 1023836488], ["com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand", 706467144], ["com/surprising/aeron/service/state/ResolvedPlaceOrder", 705129616], ["java/lang/Long", 609337032], ["com/surprising/aeron/protocol/PlaceOrderCommand", 537553040], ["com/surprising/aeron/service/matching/CoreMatchingResult$MatcherPrefix", 507299968], ["exchange/core2/core/common/MatcherResult$MatcherEvent", 449563280], ["com/surprising/aeron/service/matching/CoreMatchingResult", 439704768]]`；top site：`[["com/surprising/aeron/protocol/TradingOrderBatchCodec.decodeCommand", 1317943720], ["com/surprising/aeron/service/state/TradingRuntimeState.preparedOrder", 1181471208], ["com/surprising/aeron/protocol/TradingOrderBatchCodec.encodeResultSource", 963956848], ["java/util/ArrayList.add", 845145112], ["com/surprising/aeron/service/matching/CoreMatchingResult.classify", 838370872], ["java/util/List.copyOf", 782095448], ["org/eclipse/collections/impl/map/mutable/primitive/LongObjectHashMap.get", 772728608], ["com/surprising/aeron/service/orchestration/CoreMessageFlyweightDecoder.decode", 752948232], ["com/surprising/aeron/service/state/RuntimeDerivativeFillCalculator$FillCursor.order", 731059456], ["java/util/concurrent/ConcurrentHashMap.putVal", 715892944], ["com/surprising/aeron/service/state/model/AssetBalance.validAsset", 659498424], ["com/surprising/aeron/service/state/OrderRuntime.withFill", 593796080]]`。JIT内联归因不等同源码实际分配点，如classify本身返回枚举。
GC pause ms：`{"n": 60, "mean": 5.2203678, "p50": 5.152724, "p90": 5.466124, "p95": 5.631983999999999, "p99": 6.6115829999999995, "p999": 6.6115829999999995, "max": 7.250182}`；总313.222ms/1.044%正式窗。编译耗时ms：`{"n": 61, "mean": 23.293951672131147, "p50": 5.63914, "p90": 54.463347, "p95": 88.05789299999999, "p99": 187.24104599999998, "p999": 187.24104599999998, "max": 223.992409}`；SafepointBegin仅同步开始阶段：`{"n": 62, "mean": 0.1724116935483871, "p50": 0.09043300000000001, "p90": 0.11286299999999999, "p95": 0.118009, "p99": 0.126975, "p999": 0.126975, "max": 5.237812}`，不能当完整STW。
heap bytes：`{"committed": 536870912, "afterGcFirst": 98517296, "afterGcLast": 101196512, "afterGcMin": 98517296, "afterGcMax": 101327408, "beforeGcMax": 405428896}`；没有old/live-set长稳斜率、逐对象总数或增长归因，不推断无泄漏。
正式窗事件汇总 `[count,totalNs,maxNs,bytes或TLAB首对象size总和,maxObjectBytes,tlabBytes]`：`{"jdk.ClassLoad": [6, 634905, 340011, 0, 0, 0], "jdk.Deoptimization": [9, 0, 0, 0, 0, 0], "jdk.ExecuteVMOperation": [64, 314289319, 7264164, 0, 0, 0], "jdk.FileRead": [2, 11096, 8268, 2581, 0, 0], "jdk.FileWrite": [466068, 7251926457, 7429564, 869523712, 0, 0], "jdk.GarbageCollection": [60, 313222097, 7250183, 0, 0, 0], "jdk.ObjectAllocationInNewTLAB": [37783, 0, 0, 21448504, 16400, 18164889488], "jdk.ObjectAllocationOutsideTLAB": [135, 0, 0, 2214000, 16400, 0], "jdk.SafepointEnd": [62, 1631298, 40840, 0, 0, 0], "jdk.SafepointStateSynchronization": [62, 10408666, 5232840, 0, 0, 0], "jdk.ThreadEnd": [1, 0, 0, 0, 0, 0], "jdk.ThreadPark": [511177, 54826913297, 11909761, 0, 0, 0], "jdk.ThreadStart": [1, 0, 0, 0, 0, 0]}`。TLAB首对象/OutsideTLAB事件是部分对象，不能推算精确objects/op；最大观测对象见maxObjectBytes，分配抽样weight不当对象size。
Owner/GC细目（列依次为event/threadOrGcCause/count/totalNs/maxNs/bytes/maxObjectBytes/tlabBytes）：
```text
jdk.ClassLoad	trading-owner--1	6	634905	340011	0	0	0
jdk.Deoptimization	trading-owner--1	2	0	0	0	0	0
jdk.FileRead	trading-owner--1	2	11096	8268	2581	0	0
jdk.GarbageCollection	G1New:G1 Evacuation Pause	60	313222097	7250183	0	0	0
jdk.ObjectAllocationInNewTLAB	trading-owner--1	5679	0	0	432064	4112	2738514576
jdk.ObjectAllocationOutsideTLAB	core-account-lane-0	24	0	0	393600	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-1	35	0	0	574000	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-2	35	0	0	574000	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-3	41	0	0	672400	16400	0
jdk.ThreadPark	trading-owner--1	2828	241860136	226307	0	0	0
```
每轮Owner测量期均6次类加载、2次本地jar读取，合计约11～13us；这是预热与严格Owner同步I/O门槛缺口，耗时不足解释9ms排队。Owner park最大约0.17～0.23ms，不能归因大锁阻塞；最终profile存在7次Owner deoptimization及78次编译，预热未彻底稳定。系统上下文切换、FD/Direct/Mapped峰值、完整异常throw-site尚缺；JFR FileWrite/park全文用流式窗口汇总，未用全录制初始化事件冒充正式窗等待。
客户端窗口：`{"file": "/tmp/core-latency6-profile/window-256/end_to_end/client-2744.jfr", "cpuMeanSingleCorePercent": {"C1 CompilerThread1": 0.315552288, "JVMCI-native CompilerThread0": 7.119449523723636, "C1 CompilerThread0": 0.4338446212, "JFR Recorder Thread": 1.5696563254518519, "JFR Periodic Tasks": 0.4831277892965517, "com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations-jmh-worker-1": 98.11592883862069, "aeron-md-nra": 1.722067256827586, "driver-conductor": 51.52360173793104, "sender": 75.03881462068965, "receiver": 85.31880548965518, "mixed-egress-dispatcher": 40.31586123034483, "aeron-client": 0.17992866361379312, "benchmark-mark-price-source": 1.0829116446896552, "JVMCI-native CompilerThread1": 3.89608146832, "JVMCI-native CompilerThread2": 11.475278099733334, "JVMCI-native CompilerThread3": 33.65545352, "JVMCI-native CompilerThread4": 3.4865228160000004, "Service Thread": 0.1008334832, "Monitor Deflation Thread": 0.09899455600000001}, "gcCount": 143, "gcTotalMs": 124.017271, "gcMaxMs": 2.1124669999999997, "dataLoss": []}`。发压线程接近单核100%包含满窗忙等，egress未满核；不能把发压busy CPU当作全部编码工作，也不能宣称Core容量上限已完全测出。
NMT结束各类reserved/committed（不是峰值；增量基线早于初始化，不是30s稳定增长率）：
```text
Total: reserved=3171212KB +23510KB, committed=733052KB +37574KB
-                 Java Heap (reserved=1572864KB, committed=524288KB -2048KB)
-                     Class (reserved=1049241KB +328KB, committed=2713KB +968KB)
-                    Thread (reserved=71847KB +15392KB, committed=2771KB +840KB)
-                      Code (reserved=262087KB +11738KB, committed=50907KB +34954KB)
-                        GC (reserved=92283KB +660KB, committed=71803KB +620KB)
-                 GCCardSet (reserved=16KB +14KB, committed=16KB +14KB)
-                  Compiler (reserved=440KB +187KB, committed=440KB +187KB)
-                     JVMCI (reserved=142KB +84KB, committed=142KB +84KB)
-                  Internal (reserved=1599KB +155KB, committed=1599KB +155KB)
-                     Other (reserved=9413KB +4KB, committed=9413KB +4KB)
-                    Symbol (reserved=4924KB +553KB, committed=4924KB +553KB)
-    Native Memory Tracking (reserved=3057KB +1096KB, committed=3057KB +1096KB)
-        Shared class space (reserved=16384KB, committed=14000KB)
-               Arena Chunk (reserved=1643KB -5859KB, committed=1643KB -5859KB)
-                   Tracing (reserved=17935KB -1609KB, committed=17935KB -1609KB)
-                    Module (reserved=295KB +2KB, committed=295KB +2KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1290KB +669KB, committed=1290KB +669KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65726KB +99KB, committed=25790KB +6947KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB -3KB, committed=1KB -3KB)
```
JFR summary（全录制，仅验证事件配置/数量，不作正式窗性能）：
```text
 Start: 2026-09-15 14:15:11 (UTC)
 Duration: 103 s
 jdk.ThreadPark                        2342399      69652258
 jdk.FileWrite                          903964      19482875
 jdk.ObjectAllocationInNewTLAB           76199       1319389
 jdk.ObjectAllocationSample              76179       1217860
 jdk.Compilation                          7583        199291
 jdk.ClassLoad                            3188         54286
 jdk.ObjectAllocationOutsideTLAB           431          6835
 jdk.JavaMonitorEnter                      154          3522
 jdk.ClassLoaderStatistics                   0             0
 jdk.ClassLoadingStatistics                  0             0
 jdk.CompilationFailure                      0             0
 jdk.DataLoss                                0             0
```

latency6-decode JFR正式窗口：
| 边界/类型 | n | mean ms | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| admissionExecution/CANCEL_ORDER | 3646 | 0.008 | 0.004 | 0.018 | 0.020 | 0.028 | 0.050 | 0.119 |
| admissionExecution/CANCEL_ORDER_BATCH | 1824 | 0.008 | 0.007 | 0.009 | 0.011 | 0.021 | 0.052 | 0.102 |
| admissionExecution/PLACE_ORDER | 3630 | 0.011 | 0.007 | 0.020 | 0.024 | 0.042 | 0.097 | 0.136 |
| admissionExecution/PLACE_ORDER_BATCH | 5467 | 0.008 | 0.007 | 0.009 | 0.011 | 0.020 | 0.034 | 0.394 |
| ingressToAdmission/CANCEL_ORDER | 3646 | 2.525 | 2.446 | 3.523 | 4.169 | 8.494 | 13.523 | 41.975 |
| ingressToAdmission/CANCEL_ORDER_BATCH | 1824 | 4.335 | 4.155 | 6.340 | 7.486 | 11.207 | 29.195 | 50.335 |
| ingressToAdmission/PLACE_ORDER | 3630 | 3.363 | 2.718 | 6.096 | 6.897 | 9.504 | 14.682 | 37.437 |
| ingressToAdmission/PLACE_ORDER_BATCH | 5467 | 1.781 | 1.552 | 3.189 | 3.733 | 6.911 | 13.549 | 26.957 |
| ingressToControl/APPLY_MARK_PRICE | 79 | 4.413 | 3.792 | 7.727 | 8.841 | 11.659 | 11.659 | 19.980 |
| ownerToEgress/APPLY_MARK_PRICE | 79 | 0.008 | 0.005 | 0.015 | 0.021 | 0.069 | 0.069 | 0.078 |
| ownerToEgress/CANCEL_ORDER | 3646 | 0.012 | 0.006 | 0.023 | 0.033 | 0.096 | 0.481 | 1.500 |
| ownerToEgress/CANCEL_ORDER_BATCH | 1824 | 0.009 | 0.005 | 0.016 | 0.024 | 0.060 | 0.264 | 1.521 |
| ownerToEgress/PLACE_ORDER | 3630 | 0.014 | 0.008 | 0.027 | 0.037 | 0.099 | 0.550 | 0.696 |
| ownerToEgress/PLACE_ORDER_BATCH | 5468 | 0.016 | 0.007 | 0.026 | 0.037 | 0.105 | 0.487 | 18.284 |
| transportToOwner/APPLY_MARK_PRICE | 79 | 0.594 | 0.531 | 1.323 | 1.382 | 1.577 | 1.577 | 2.040 |
| transportToOwner/CANCEL_ORDER | 3648 | 0.799 | 0.456 | 2.109 | 2.641 | 3.529 | 6.460 | 9.843 |
| transportToOwner/CANCEL_ORDER_BATCH | 1824 | 1.007 | 0.948 | 1.733 | 2.057 | 2.916 | 7.474 | 8.118 |
| transportToOwner/PLACE_ORDER | 3631 | 1.195 | 0.949 | 2.551 | 2.965 | 3.773 | 7.573 | 8.926 |
| transportToOwner/PLACE_ORDER_BATCH | 5466 | 0.597 | 0.433 | 1.375 | 1.614 | 2.114 | 6.549 | 7.662 |
`ingressToAdmission`截止实际apply前，包含FIFO等待及prepareClusterPipelineScope工作；`admissionExecution`仅Owner apply/绑定，不等于Lane完成冻结；`transportToOwner`含指纹计算、Input FIFO（decode变体还含前移解码）；`ownerToEgress`截止编码前，不含网络发送重试。只有相同唯一commandId的当前负载样本可关联，重试/重放不能直接用此离线join。不要相加/相减各阶段分位数，也不要跨进程相减nanoTime。
同commandId已复制输入→编码前（同一JVM近似边界，包含JFR事件创建的微小耗时）：`{"CANCEL_ORDER_BATCH": {"n": 1824, "mean": 6.704207816615256, "p50": 6.408545854492187, "p90": 9.043284884521483, "p95": 10.6309124921875, "p99": 14.018353760131836, "p999": 30.65962668481445, "max": 53.39768470446777}, "PLACE_ORDER": {"n": 3630, "mean": 5.060073078265465, "p50": 4.490069304931641, "p90": 7.6129147247314455, "p95": 8.311001369262696, "p99": 11.949464529174804, "p999": 16.437638166870116, "max": 44.61534647814941}, "PLACE_ORDER_BATCH": {"n": 5466, "mean": 3.912596684805118, "p50": 3.7611416313476562, "p90": 5.280579516479492, "p95": 6.056851083007812, "p99": 9.964345945922851, "p999": 17.420940205566406, "max": 49.77694094128418}, "CANCEL_ORDER": {"n": 3646, "mean": 3.876909762545252, "p50": 3.6478723892822265, "p90": 5.28531931262207, "p95": 6.403525435791016, "p99": 10.098793999389649, "p999": 17.330169488037107, "max": 43.70954752038574}, "APPLY_MARK_PRICE": {"n": 79, "mean": 5.049326727783203, "p50": 4.202249621826172, "p90": 8.23434398034668, "p95": 9.281366536010742, "p99": 13.770176673217772, "p999": 13.770176673217772, "max": 20.08823791772461}}`。
Matcher发布→最终提交每样本完整区间p99/ms：`{"PLACE_ORDER": {"matcherToLastLaneStartNanos": 1.048, "maxLaneExecutionNanos": 0.0094, "matcherToLanesCompleteNanos": 1.0505, "lanesCompleteToOwnerNanos": 1.0583, "ownerFinalCommitMs": 0.0224, "publicationToCommitMs": 1.5612}, "PLACE_ORDER_BATCH": {"matcherToLastLaneStartNanos": 0.7546, "maxLaneExecutionNanos": 0.1373, "matcherToLanesCompleteNanos": 0.8393, "lanesCompleteToOwnerNanos": 1.2415, "ownerFinalCommitMs": 0.0577, "publicationToCommitMs": 1.4304}, "CANCEL_ORDER": {"matcherToLastLaneStartNanos": 0.5376, "maxLaneExecutionNanos": 0.0151, "matcherToLanesCompleteNanos": 0.5427, "lanesCompleteToOwnerNanos": 1.1784, "ownerFinalCommitMs": 0.0241, "publicationToCommitMs": 1.2879}}`。Owner finalCommit只覆盖最终返回的调用；普通撤批/行情无完整Matcher分段，未填零。跨Lane最大启动与最大执行可来自不同Lane。
CPU折算单核百分比均值：`{"JVMCI-native CompilerThread0": 4.99, "C1 CompilerThread0": 1.29, "JFR Recorder Thread": 0.51, "JFR Periodic Tasks": 0.48, "aeron-md-nra": 1.67, "driver-conductor": 68.27, "/tmp/core-latency6-decode-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]": 97.41, "archive-conductor": 73.84, "consensus-module-101-0": 62.57, "aeron-client": 0.22, "clustered-service-101-0": 70.77, "trading-owner--1": 97.4, "core-matcher-0": 57.45, "core-account-lane-0": 97.74, "core-account-lane-1": 97.72, "core-account-lane-2": 97.88, "core-account-lane-3": 97.73, "JVMCI-native CompilerThread1": 16.93, "Monitor Deflation Thread": 0.1}`；Owner叶热点：`[["org/agrona/collections/Long2ObjectHashMap.getMapped", 70], ["org/agrona/collections/Long2ObjectHashMap.put", 39], ["com/surprising/aeron/service/orchestration/TerminalTombstoneStore.bucket", 35], ["java/util/HashMap.getNode", 31], ["org/agrona/collections/Long2ObjectHashMap.remove", 24], ["com/surprising/aeron/service/orchestration/OrderBatchExecutor.preparePipelinedPlaceBatch", 24], ["java/util/Arrays.fill", 22], ["com/surprising/aeron/service/orchestration/TerminalTombstoneStore.unlinkClient", 21], ["com/surprising/aeron/service/state/MatcherSettlementPlan.clearReferences", 20], ["org/agrona/collections/Long2LongHashMap.get", 18], ["com/surprising/aeron/service/state/LanePublication.publish", 17], ["org/agrona/collections/Long2ObjectHashMap.compactChain", 16]]`。
分配ThreadAllocationStatistics各线程首尾斜率之和653.845MB/s，除以该profile steady吞吐为1996.787B/business op；约1s采样边缘误差。线程bytes/s：`{"clustered-service-101-0": 88524705, "trading-owner--1": 44136085, "core-matcher-0": 162888372, "core-account-lane-0": 89590961, "core-account-lane-1": 89578868, "core-account-lane-2": 89558380, "core-account-lane-3": 89564916}`。
分配抽样权重top class（bytes，不是精确对象数）：`[["com/surprising/aeron/service/state/OrderRuntime", 2904835224], ["[B", 2728508424], ["com/surprising/aeron/service/state/ReservationRuntime", 1325662008], ["[J", 1244831536], ["exchange/core2/core/common/MatcherResult", 1017397288], ["java/lang/Long", 938649248], ["com/surprising/aeron/service/state/ResolvedPlaceOrder", 783645792], ["com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand", 721406072], ["com/surprising/aeron/service/matching/CoreMatchingResult", 652451232], ["[Ljava/lang/Object;", 602302448], ["com/surprising/aeron/protocol/PlaceOrderCommand", 522233224], ["exchange/core2/core/common/MatcherResult$MatcherEvent", 513098416]]`；top site：`[["com/surprising/aeron/service/matching/CoreMatchingResult.classify", 1315381696], ["com/surprising/aeron/protocol/TradingOrderBatchCodec.decodeCommand", 1311246424], ["com/surprising/aeron/service/state/TradingRuntimeState.preparedOrder", 1283613728], ["com/surprising/aeron/protocol/TradingOrderBatchCodec.encodeResultSource", 1035073872], ["exchange/core2/core/SynchronousMatchingEngine.execute", 1017397288], ["com/surprising/aeron/service/orchestration/CoreMessageFlyweightDecoder.decode", 1004392408], ["java/util/concurrent/ConcurrentHashMap.putVal", 989478944], ["org/eclipse/collections/impl/map/mutable/primitive/LongObjectHashMap.get", 792835656], ["com/surprising/aeron/service/state/RuntimeDerivativeFillCalculator$FillCursor.order", 789314848], ["com/surprising/aeron/service/state/model/AssetBalance.validAsset", 736682560], ["com/surprising/aeron/service/state/OrderRuntime.withFill", 614616856], ["com/surprising/aeron/service/state/BalanceRuntime.release", 609871264]]`。JIT内联归因不等同源码实际分配点，如classify本身返回枚举。
GC pause ms：`{"n": 65, "mean": 5.175531861538461, "p50": 5.131126, "p90": 5.432172, "p95": 5.637858, "p99": 5.936337, "p999": 5.936337, "max": 6.154467}`；总336.410ms/1.121%正式窗。编译耗时ms：`{"n": 53, "mean": 22.42623769811321, "p50": 7.228091, "p90": 48.792156000000006, "p95": 88.800742, "p99": 144.361777, "p999": 144.361777, "max": 203.17269299999998}`；SafepointBegin仅同步开始阶段：`{"n": 67, "mean": 0.24155846268656717, "p50": 0.193076, "p90": 0.49021299999999995, "p95": 0.51691, "p99": 0.52169, "p999": 0.52169, "max": 0.5559620000000001}`，不能当完整STW。
heap bytes：`{"committed": 536870912, "afterGcFirst": 110303504, "afterGcLast": 110074936, "afterGcMin": 107831288, "afterGcMax": 111122120, "beforeGcMax": 414464848}`；没有old/live-set长稳斜率、逐对象总数或增长归因，不推断无泄漏。
正式窗事件汇总 `[count,totalNs,maxNs,bytes或TLAB首对象size总和,maxObjectBytes,tlabBytes]`：`{"jdk.ClassLoad": [6, 669571, 365417, 0, 0, 0], "jdk.Deoptimization": [3, 0, 0, 0, 0, 0], "jdk.ExecuteVMOperation": [69, 337764971, 6168260, 0, 0, 0], "jdk.FileRead": [2, 12764, 8416, 2581, 0, 0], "jdk.FileWrite": [503585, 7621343425, 18060932, 940329184, 0, 0], "jdk.GarbageCollection": [65, 336409596, 6154467, 0, 0, 0], "jdk.JavaMonitorEnter": [1, 22339, 22339, 0, 0, 0], "jdk.ObjectAllocationInNewTLAB": [41022, 0, 0, 21429176, 16400, 19737064688], "jdk.ObjectAllocationOutsideTLAB": [177, 0, 0, 2902800, 16400, 0], "jdk.SafepointEnd": [67, 1737283, 39009, 0, 0, 0], "jdk.SafepointStateSynchronization": [67, 15858430, 552242, 0, 0, 0], "jdk.ThreadEnd": [2, 0, 0, 0, 0, 0], "jdk.ThreadPark": [486274, 51425157818, 11401343, 0, 0, 0], "jdk.ThreadStart": [2, 0, 0, 0, 0, 0]}`。TLAB首对象/OutsideTLAB事件是部分对象，不能推算精确objects/op；最大观测对象见maxObjectBytes，分配抽样weight不当对象size。
Owner/GC细目（列依次为event/threadOrGcCause/count/totalNs/maxNs/bytes/maxObjectBytes/tlabBytes）：
```text
jdk.ClassLoad	trading-owner--1	6	669571	365417	0	0	0
jdk.Deoptimization	trading-owner--1	1	0	0	0	0	0
jdk.FileRead	trading-owner--1	2	12764	8416	2581	0	0
jdk.GarbageCollection	G1New:G1 Evacuation Pause	65	336409596	6154467	0	0	0
jdk.JavaMonitorEnter	trading-owner--1	1	22339	22339	0	0	0
jdk.ObjectAllocationInNewTLAB	trading-owner--1	3357	0	0	366576	4112	1336532312
jdk.ObjectAllocationOutsideTLAB	core-account-lane-0	40	0	0	656000	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-1	53	0	0	869200	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-2	37	0	0	606800	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-3	47	0	0	770800	16400	0
jdk.ThreadPark	trading-owner--1	1618	79941481	167154	0	0	0
```
每轮Owner测量期均6次类加载、2次本地jar读取，合计约11～13us；这是预热与严格Owner同步I/O门槛缺口，耗时不足解释9ms排队。Owner park最大约0.17～0.23ms，不能归因大锁阻塞；最终profile存在7次Owner deoptimization及78次编译，预热未彻底稳定。系统上下文切换、FD/Direct/Mapped峰值、完整异常throw-site尚缺；JFR FileWrite/park全文用流式窗口汇总，未用全录制初始化事件冒充正式窗等待。
客户端窗口：`{"file": "/tmp/core-latency6-decode-profile/window-256/end_to_end/client-7014.jfr", "cpuMeanSingleCorePercent": {"JVMCI-native CompilerThread0": 5.399544156411428, "C1 CompilerThread0": 0.3500223610666667, "JFR Recorder Thread": 0.7184601242030769, "JFR Periodic Tasks": 0.47220031413333335, "com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations-jmh-worker-1": 98.050782336, "aeron-md-nra": 1.6378176714666668, "driver-conductor": 52.555964026666665, "sender": 75.74286810666666, "receiver": 88.56443333333333, "mixed-egress-dispatcher": 39.898631968000004, "aeron-client": 0.19647443933333333, "benchmark-mark-price-source": 1.0373097850666666, "C1 CompilerThread1": 1.549068576, "JVMCI-native CompilerThread1": 3.6947547544000003, "JVMCI-native CompilerThread2": 3.3774573604, "JVMCI-native CompilerThread3": 40.344439359999996, "Monitor Deflation Thread": 0.1001284384}, "gcCount": 154, "gcTotalMs": 130.281227, "gcMaxMs": 1.448159, "dataLoss": []}`。发压线程接近单核100%包含满窗忙等，egress未满核；不能把发压busy CPU当作全部编码工作，也不能宣称Core容量上限已完全测出。
NMT结束各类reserved/committed（不是峰值；增量基线早于初始化，不是30s稳定增长率）：
```text
Total: reserved=3169004KB +21560KB, committed=732280KB +36952KB
-                 Java Heap (reserved=1572864KB, committed=524288KB -2048KB)
-                     Class (reserved=1049237KB +325KB, committed=2709KB +965KB)
-                    Thread (reserved=69796KB +13342KB, committed=2668KB +694KB)
-                      Code (reserved=261894KB +11537KB, committed=50202KB +34177KB)
-                        GC (reserved=92402KB +787KB, committed=71922KB +747KB)
-                 GCCardSet (reserved=16KB +14KB, committed=16KB +14KB)
-                  Compiler (reserved=436KB +168KB, committed=436KB +168KB)
-                     JVMCI (reserved=124KB +70KB, committed=124KB +70KB)
-                  Internal (reserved=1596KB +149KB, committed=1596KB +149KB)
-                     Other (reserved=9413KB +4KB, committed=9413KB +4KB)
-                    Symbol (reserved=4924KB +553KB, committed=4924KB +553KB)
-    Native Memory Tracking (reserved=3080KB +1124KB, committed=3080KB +1124KB)
-        Shared class space (reserved=16384KB, committed=14000KB)
-               Arena Chunk (reserved=195KB -7082KB, committed=195KB -7082KB)
-                   Tracing (reserved=19312KB -192KB, committed=19312KB -192KB)
-                    Module (reserved=295KB +2KB, committed=295KB +2KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1287KB +668KB, committed=1287KB +668KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65722KB +95KB, committed=25786KB +6943KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB -3KB, committed=1KB -3KB)
```
JFR summary（全录制，仅验证事件配置/数量，不作正式窗性能）：
```text
 Start: 2026-09-15 14:28:10 (UTC)
 Duration: 105 s
 jdk.ThreadPark                        2322984      69062440
 jdk.FileWrite                          986778      21393620
 jdk.ObjectAllocationInNewTLAB           82647       1430631
 jdk.ObjectAllocationSample              82628       1321209
 jdk.Compilation                          7580        199154
 jdk.ClassLoad                            3185         54259
 jdk.ObjectAllocationOutsideTLAB           454          7189
 jdk.JavaMonitorEnter                      148          3383
 jdk.ClassLoaderStatistics                   0             0
 jdk.ClassLoadingStatistics                  0             0
 jdk.CompilationFailure                      0             0
 jdk.DataLoss                                0             0
```

latency6-index JFR正式窗口：
| 边界/类型 | n | mean ms | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| admissionExecution/CANCEL_ORDER | 3580 | 0.008 | 0.004 | 0.017 | 0.019 | 0.028 | 0.039 | 0.119 |
| admissionExecution/CANCEL_ORDER_BATCH | 1790 | 0.007 | 0.007 | 0.009 | 0.011 | 0.024 | 0.044 | 0.064 |
| admissionExecution/PLACE_ORDER | 3595 | 0.011 | 0.008 | 0.019 | 0.023 | 0.043 | 0.100 | 0.152 |
| admissionExecution/PLACE_ORDER_BATCH | 5387 | 0.007 | 0.007 | 0.008 | 0.010 | 0.018 | 0.036 | 0.045 |
| ingressToAdmission/CANCEL_ORDER | 3580 | 2.688 | 2.666 | 3.579 | 4.330 | 8.148 | 13.217 | 17.457 |
| ingressToAdmission/CANCEL_ORDER_BATCH | 1790 | 5.065 | 4.945 | 6.991 | 7.777 | 11.483 | 13.924 | 64.933 |
| ingressToAdmission/PLACE_ORDER | 3595 | 3.602 | 2.867 | 6.587 | 7.181 | 9.796 | 14.452 | 16.772 |
| ingressToAdmission/PLACE_ORDER_BATCH | 5387 | 2.327 | 2.330 | 3.693 | 4.438 | 7.850 | 11.335 | 14.660 |
| ingressToControl/APPLY_MARK_PRICE | 28 | 3.890 | 3.844 | 5.402 | 6.702 | 7.118 | 7.118 | 7.163 |
| ownerToEgress/APPLY_MARK_PRICE | 28 | 0.009 | 0.005 | 0.019 | 0.022 | 0.031 | 0.031 | 0.061 |
| ownerToEgress/CANCEL_ORDER | 3580 | 0.011 | 0.005 | 0.019 | 0.030 | 0.087 | 0.829 | 1.545 |
| ownerToEgress/CANCEL_ORDER_BATCH | 1790 | 0.013 | 0.005 | 0.017 | 0.025 | 0.087 | 0.605 | 5.639 |
| ownerToEgress/PLACE_ORDER | 3595 | 0.012 | 0.006 | 0.022 | 0.032 | 0.099 | 0.641 | 1.836 |
| ownerToEgress/PLACE_ORDER_BATCH | 5386 | 0.015 | 0.006 | 0.021 | 0.031 | 0.107 | 1.021 | 8.283 |
| transportToOwner/APPLY_MARK_PRICE | 28 | 1.341 | 0.937 | 2.368 | 3.004 | 3.461 | 3.461 | 7.943 |
| transportToOwner/CANCEL_ORDER | 3580 | 1.046 | 0.561 | 2.623 | 3.063 | 3.880 | 7.272 | 7.700 |
| transportToOwner/CANCEL_ORDER_BATCH | 1789 | 1.198 | 1.149 | 1.944 | 2.206 | 3.253 | 7.367 | 62.171 |
| transportToOwner/PLACE_ORDER | 3595 | 1.225 | 0.880 | 2.846 | 3.219 | 3.854 | 7.895 | 63.829 |
| transportToOwner/PLACE_ORDER_BATCH | 5387 | 0.626 | 0.458 | 1.409 | 1.655 | 2.106 | 5.789 | 7.258 |
`ingressToAdmission`截止实际apply前，包含FIFO等待及prepareClusterPipelineScope工作；`admissionExecution`仅Owner apply/绑定，不等于Lane完成冻结；`transportToOwner`含指纹计算、Input FIFO（decode变体还含前移解码）；`ownerToEgress`截止编码前，不含网络发送重试。只有相同唯一commandId的当前负载样本可关联，重试/重放不能直接用此离线join。不要相加/相减各阶段分位数，也不要跨进程相减nanoTime。
同commandId已复制输入→编码前（同一JVM近似边界，包含JFR事件创建的微小耗时）：`{"CANCEL_ORDER_BATCH": {"n": 1789, "mean": 7.617800515132215, "p50": 7.371285120605469, "p90": 9.773425165527344, "p95": 11.077562986572266, "p99": 14.314423510742188, "p999": 20.924370800048827, "max": 70.11251228845215}, "PLACE_ORDER": {"n": 3595, "mean": 5.331871088526002, "p50": 4.822461921264648, "p90": 7.860310094482422, "p95": 8.503852453491211, "p99": 11.983875059448241, "p999": 16.63287098522949, "max": 72.024516819458}, "PLACE_ORDER_BATCH": {"n": 5385, "mean": 4.551900950716985, "p50": 4.419364966064453, "p90": 5.933879485839844, "p95": 6.817364249877929, "p99": 10.398631523803711, "p999": 14.898546404785156, "max": 67.2053104576416}, "CANCEL_ORDER": {"n": 3580, "mean": 4.269462060482907, "p50": 3.886639661254883, "p90": 6.016982147583008, "p95": 6.996600392944336, "p99": 10.141713224365235, "p999": 13.951662834838867, "max": 18.630139423461912}, "APPLY_MARK_PRICE": {"n": 28, "mean": 5.265704767163958, "p50": 5.1091533286132815, "p90": 7.1779088333740235, "p95": 8.361279233154297, "p99": 9.304786505249023, "p999": 9.304786505249023, "max": 11.728638036132812}}`。
Matcher发布→最终提交每样本完整区间p99/ms：`{"PLACE_ORDER": {"matcherToLastLaneStartNanos": 1.1135, "maxLaneExecutionNanos": 0.0077, "matcherToLanesCompleteNanos": 1.1166, "lanesCompleteToOwnerNanos": 1.1341, "ownerFinalCommitMs": 0.0218, "publicationToCommitMs": 1.6237}, "PLACE_ORDER_BATCH": {"matcherToLastLaneStartNanos": 0.6223, "maxLaneExecutionNanos": 0.1399, "matcherToLanesCompleteNanos": 0.6862, "lanesCompleteToOwnerNanos": 1.4351, "ownerFinalCommitMs": 0.0547, "publicationToCommitMs": 1.6124}, "CANCEL_ORDER": {"matcherToLastLaneStartNanos": 0.489, "maxLaneExecutionNanos": 0.0118, "matcherToLanesCompleteNanos": 0.4925, "lanesCompleteToOwnerNanos": 1.2853, "ownerFinalCommitMs": 0.0251, "publicationToCommitMs": 1.3345}}`。Owner finalCommit只覆盖最终返回的调用；普通撤批/行情无完整Matcher分段，未填零。跨Lane最大启动与最大执行可来自不同Lane。
CPU折算单核百分比均值：`{"JVMCI-native CompilerThread0": 4.11, "C1 CompilerThread0": 0.83, "JFR Recorder Thread": 1.04, "JFR Periodic Tasks": 0.5, "aeron-md-nra": 1.67, "driver-conductor": 70.56, "/tmp/core-latency6-index-profile/window-256/end_to_end/aeron-surprising-linear_perpetual-0 [sender,receiver]": 97.26, "archive-conductor": 73.34, "consensus-module-101-0": 63.23, "aeron-client": 0.24, "clustered-service-101-0": 71.21, "trading-owner--1": 97.49, "core-matcher-0": 55.25, "core-account-lane-0": 98.12, "core-account-lane-1": 98.11, "core-account-lane-2": 98.11, "core-account-lane-3": 98.12, "JVMCI-native CompilerThread1": 6.32, "JVMCI-native CompilerThread2": 5.55, "Monitor Deflation Thread": 0.1}`；Owner叶热点：`[["com/surprising/aeron/protocol/TradingOrderBatchCodec.decodeCommand", 54], ["org/agrona/collections/Long2ObjectHashMap.remove", 40], ["org/agrona/collections/Long2ObjectHashMap.put", 36], ["org/agrona/collections/Long2ObjectHashMap.getMapped", 34], ["java/util/HashMap.getNode", 32], ["java/util/Arrays.fill", 27], ["com/surprising/aeron/service/orchestration/TerminalTombstoneStore.bucket", 27], ["com/surprising/aeron/service/orchestration/TerminalTombstoneStore.unlinkEntity", 20], ["org/agrona/collections/Long2LongHashMap.get", 20], ["com/surprising/aeron/service/state/LanePublication.publish", 17], ["org/agrona/collections/Long2LongHashMap.put", 16], ["org/agrona/collections/Long2ObjectHashMap.compactChain", 16]]`。
分配ThreadAllocationStatistics各线程首尾斜率之和639.006MB/s，除以该profile steady吞吐为1989.654B/business op；约1s采样边缘误差。线程bytes/s：`{"clustered-service-101-0": 34207381, "trading-owner--1": 96217009, "core-matcher-0": 157252445, "core-account-lane-0": 87775441, "core-account-lane-1": 87799983, "core-account-lane-2": 87974599, "core-account-lane-3": 87776514}`。
分配抽样权重top class（bytes，不是精确对象数）：`[["com/surprising/aeron/service/state/OrderRuntime", 2922546536], ["[B", 2600845024], ["com/surprising/aeron/service/state/ReservationRuntime", 1329092608], ["[J", 1209300424], ["exchange/core2/core/common/MatcherResult", 989566408], ["com/surprising/aeron/service/state/ResolvedPlaceOrder", 761742760], ["com/surprising/aeron/service/matching/CoreMatchingResult$NativeCommand", 753373848], ["java/lang/Long", 634575888], ["com/surprising/aeron/service/matching/CoreMatchingResult$MatcherPrefix", 593360688], ["exchange/core2/core/common/MatcherResult$MatcherEvent", 564670200], ["com/surprising/aeron/protocol/PlaceOrderCommand", 537482624], ["com/surprising/aeron/service/matching/CoreMatchingResult", 475307104]]`；top site：`[["com/surprising/aeron/protocol/TradingOrderBatchCodec.decodeCommand", 1432027360], ["com/surprising/aeron/service/state/TradingRuntimeState.preparedOrder", 1286441336], ["java/nio/ByteBuffer.allocate", 1044399856], ["java/util/List.copyOf", 989566408], ["java/util/ArrayList.add", 916124080], ["com/surprising/aeron/service/matching/CoreMatchingResult.classify", 898587280], ["com/surprising/aeron/service/orchestration/CoreMessageFlyweightDecoder.decode", 812408208], ["com/surprising/aeron/service/state/RuntimeDerivativeFillCalculator$FillCursor.order", 791465320], ["org/eclipse/collections/impl/map/mutable/primitive/LongObjectHashMap.get", 765013792], ["java/util/concurrent/ConcurrentHashMap.putVal", 743886656], ["com/surprising/aeron/service/state/model/AssetBalance.validAsset", 718006736], ["com/surprising/aeron/service/state/OrderRuntime.withFill", 615621200]]`。JIT内联归因不等同源码实际分配点，如classify本身返回枚举。
GC pause ms：`{"n": 63, "mean": 5.05957607936508, "p50": 4.973546, "p90": 5.271917999999999, "p95": 5.440738, "p99": 6.622225, "p999": 6.622225, "max": 9.382923}`；总318.753ms/1.061%正式窗。编译耗时ms：`{"n": 78, "mean": 16.147927, "p50": 5.027159, "p90": 40.731753000000005, "p95": 73.910212, "p99": 97.25765799999999, "p999": 97.25765799999999, "max": 110.421166}`；SafepointBegin仅同步开始阶段：`{"n": 66, "mean": 0.9269047121212121, "p50": 0.085161, "p90": 0.12084700000000001, "p95": 0.129472, "p99": 0.43849299999999997, "p999": 0.43849299999999997, "max": 55.074752999999994}`，不能当完整STW。
heap bytes：`{"committed": 536870912, "afterGcFirst": 106217912, "afterGcLast": 106949152, "afterGcMin": 105833392, "afterGcMax": 108599376, "beforeGcMax": 412684352}`；没有old/live-set长稳斜率、逐对象总数或增长归因，不推断无泄漏。
正式窗事件汇总 `[count,totalNs,maxNs,bytes或TLAB首对象size总和,maxObjectBytes,tlabBytes]`：`{"jdk.ClassLoad": [6, 666870, 344059, 0, 0, 0], "jdk.Deoptimization": [14, 0, 0, 0, 0, 0], "jdk.ExecuteVMOperation": [68, 319970927, 9396818, 0, 0, 0], "jdk.FileRead": [2, 12077, 9330, 2581, 0, 0], "jdk.FileWrite": [491566, 7213720489, 18691799, 923364992, 0, 0], "jdk.GarbageCollection": [63, 318753318, 9382923, 0, 0, 0], "jdk.ObjectAllocationInNewTLAB": [40127, 0, 0, 22124800, 16400, 19296450080], "jdk.ObjectAllocationOutsideTLAB": [158, 0, 0, 2591200, 16400, 0], "jdk.SafepointEnd": [66, 1746063, 46185, 0, 0, 0], "jdk.SafepointStateSynchronization": [66, 60840467, 55070792, 0, 0, 0], "jdk.ThreadEnd": [3, 0, 0, 0, 0, 0], "jdk.ThreadPark": [549486, 55400910692, 61330448, 0, 0, 0], "jdk.ThreadStart": [3, 0, 0, 0, 0, 0]}`。TLAB首对象/OutsideTLAB事件是部分对象，不能推算精确objects/op；最大观测对象见maxObjectBytes，分配抽样weight不当对象size。
Owner/GC细目（列依次为event/threadOrGcCause/count/totalNs/maxNs/bytes/maxObjectBytes/tlabBytes）：
```text
jdk.ClassLoad	trading-owner--1	6	666870	344059	0	0	0
jdk.Deoptimization	trading-owner--1	7	0	0	0	0	0
jdk.FileRead	trading-owner--1	2	12077	9330	2581	0	0
jdk.GarbageCollection	G1New:G1 Evacuation Pause	63	318753318	9382923	0	0	0
jdk.ObjectAllocationInNewTLAB	trading-owner--1	5981	0	0	470808	16400	2906020792
jdk.ObjectAllocationOutsideTLAB	core-account-lane-0	45	0	0	738000	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-1	34	0	0	557600	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-2	40	0	0	656000	16400	0
jdk.ObjectAllocationOutsideTLAB	core-account-lane-3	39	0	0	639600	16400	0
jdk.ThreadPark	trading-owner--1	2426	183513586	169208	0	0	0
```
每轮Owner测量期均6次类加载、2次本地jar读取，合计约11～13us；这是预热与严格Owner同步I/O门槛缺口，耗时不足解释9ms排队。Owner park最大约0.17～0.23ms，不能归因大锁阻塞；最终profile存在7次Owner deoptimization及78次编译，预热未彻底稳定。系统上下文切换、FD/Direct/Mapped峰值、完整异常throw-site尚缺；JFR FileWrite/park全文用流式窗口汇总，未用全录制初始化事件冒充正式窗等待。
客户端窗口：`{"file": "/tmp/core-latency6-index-profile/window-256/end_to_end/client-11161.jfr", "cpuMeanSingleCorePercent": {"JVMCI-native CompilerThread4": 13.377131679999998, "Service Thread": 0.1020313744, "JVMCI-native CompilerThread0": 7.1921256527138455, "C1 CompilerThread0": 0.32376445648, "JFR Periodic Tasks": 0.4522673363310345, "com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations-jmh-worker-1": 98.2745255613793, "aeron-md-nra": 1.5959974344827585, "driver-conductor": 51.89081776551724, "sender": 75.02104783448276, "receiver": 85.73730620689655, "mixed-egress-dispatcher": 39.6057291475862, "aeron-client": 0.1699207571310345, "benchmark-mark-price-source": 1.0252666300689655, "C1 CompilerThread1": 0.3938883008, "JVMCI-native CompilerThread1": 12.691761712000002, "JVMCI-native CompilerThread2": 11.4859924912, "JVMCI-native CompilerThread3": 24.16946112, "JFR Recorder Thread": 1.4671911741230768, "Monitor Deflation Thread": 0.10109342560000001}, "gcCount": 151, "gcTotalMs": 123.844919, "gcMaxMs": 1.4495630000000002, "dataLoss": []}`。发压线程接近单核100%包含满窗忙等，egress未满核；不能把发压busy CPU当作全部编码工作，也不能宣称Core容量上限已完全测出。
NMT结束各类reserved/committed（不是峰值；增量基线早于初始化，不是30s稳定增长率）：
```text
Total: reserved=3170903KB +27505KB, committed=734263KB +39069KB
-                 Java Heap (reserved=1572864KB, committed=524288KB -2048KB)
-                     Class (reserved=1049239KB +327KB, committed=2711KB +967KB)
-                    Thread (reserved=69796KB +17442KB, committed=2680KB +830KB)
-                      Code (reserved=261911KB +11585KB, committed=50283KB +34353KB)
-                        GC (reserved=92374KB +760KB, committed=71902KB +728KB)
-                 GCCardSet (reserved=16KB +14KB, committed=16KB +14KB)
-                  Compiler (reserved=433KB +167KB, committed=433KB +167KB)
-                     JVMCI (reserved=126KB +73KB, committed=126KB +73KB)
-                  Internal (reserved=1605KB +166KB, committed=1605KB +166KB)
-                     Other (reserved=9413KB +4KB, committed=9413KB +4KB)
-                    Symbol (reserved=4925KB +554KB, committed=4925KB +554KB)
-    Native Memory Tracking (reserved=3062KB +1107KB, committed=3062KB +1107KB)
-        Shared class space (reserved=16384KB, committed=14000KB)
-               Arena Chunk (reserved=1612KB -5763KB, committed=1612KB -5763KB)
-                   Tracing (reserved=19805KB +301KB, committed=19805KB +301KB)
-                    Module (reserved=295KB +2KB, committed=295KB +2KB)
-                 Safepoint (reserved=8KB, committed=8KB)
-           Synchronization (reserved=1289KB +668KB, committed=1289KB +668KB)
-            Serviceability (reserved=17KB, committed=17KB)
-                 Metaspace (reserved=65727KB +100KB, committed=25791KB +6948KB)
-      String Deduplication (reserved=1KB, committed=1KB)
-           Object Monitors (reserved=1KB -3KB, committed=1KB -3KB)
```
JFR summary（全录制，仅验证事件配置/数量，不作正式窗性能）：
```text
 Start: 2026-09-15 14:40:54 (UTC)
 Duration: 105 s
 jdk.ThreadPark                        2468366      73074393
 jdk.FileWrite                          979555      21222646
 jdk.ObjectAllocationInNewTLAB           82742       1432851
 jdk.ObjectAllocationSample              82725       1322751
 jdk.Compilation                          7544        198200
 jdk.ClassLoad                            3185         54229
 jdk.ObjectAllocationOutsideTLAB           452          7170
 jdk.JavaMonitorEnter                      141          3231
 jdk.ClassLoaderStatistics                   0             0
 jdk.ClassLoadingStatistics                  0             0
 jdk.CompilationFailure                      0             0
 jdk.DataLoss                                0             0
```

微基准同一工作树单因素前后，单位索引生命周期/s（1 lifecycle=OPEN插入+终态移除，@OperationsPerInvocation(20)，不是业务ops）：
| users | before plain ±error | after plain ±error | 均值变化 | before/after prof gc B/lifecycle |
|---|---:|---:|---:|---|
| 1 | 12071462.492 ±2260090.402 | 13013448.630 ±481112.977 | 7.80% | 86.40058050340309/86.40053570442605 |
| 20 | 7116804.556 ±1068778.101 | 7552220.965 ±941839.065 | 6.12% | 248.00097259064714/248.0009469892365 |
JMH置信区间重叠，1fork/3次measurement不足宣称统计显著；局部无观测回退且分配不增，保留索引简化。prof gc所有副指标（bytes/s、GC次数/时间等）：
before：`[{"users": "1", "primary": {"score": 12071042.993182527, "scoreError": 1072860.280608786, "scoreConfidence": [10998182.71257374, 13143903.273791313], "scorePercentiles": {"0.0": 12026140.648824718, "50.0": 12049379.443389747, "90.0": 12137608.887333117, "95.0": 12137608.887333117, "99.0": 12137608.887333117, "99.9": 12137608.887333117, "99.99": 12137608.887333117, "99.999": 12137608.887333117, "99.9999": 12137608.887333117, "100.0": 12137608.887333117}, "scoreUnit": "ops/s", "rawData": [[12026140.648824718, 12137608.887333117, 12049379.443389747]]}, "secondary": {"gc.alloc.rate": {"score": 994.2433073648326, "scoreError": 87.99830318473558, "scoreConfidence": [906.245004180097, 1082.2416105495681], "scorePercentiles": {"0.0": 990.6142151166637, "50.0": 992.3988557631214, "90.0": 999.7168512147127, "95.0": 999.7168512147127, "99.0": 999.7168512147127, "99.9": 999.7168512147127, "99.99": 999.7168512147127, "99.999": 999.7168512147127, "99.9999": 999.7168512147127, "100.0": 999.7168512147127}, "scoreUnit": "MB/sec", "rawData": [[990.6142151166637, 999.7168512147127, 992.3988557631214]]}, "gc.alloc.rate.norm": {"score": 86.40058050340309, "scoreError": 0.00016144359887945638, "scoreConfidence": [86.40041905980421, 86.40074194700198], "scorePercentiles": {"0.0": 86.40057347811845, "50.0": 86.40057759003707, "90.0": 86.40059044205377, "95.0": 86.40059044205377, "99.0": 86.40059044205377, "99.9": 86.40059044205377, "99.99": 86.40059044205377, "99.999": 86.40059044205377, "99.9999": 86.40059044205377, "100.0": 86.40059044205377}, "scoreUnit": "B/op", "rawData": [[86.40057759003707, 86.40057347811845, 86.40059044205377]]}, "gc.count": {"score": 39.0, "scoreError": "NaN", "scoreConfidence": [39.0, 39.0], "scorePercentiles": {"0.0": 13.0, "50.0": 13.0, "90.0": 13.0, "95.0": 13.0, "99.0": 13.0, "99.9": 13.0, "99.99": 13.0, "99.999": 13.0, "99.9999": 13.0, "100.0": 13.0}, "scoreUnit": "counts", "rawData": [[13.0, 13.0, 13.0]]}, "gc.time": {"score": 26.0, "scoreError": "NaN", "scoreConfidence": [26.0, 26.0], "scorePercentiles": {"0.0": 8.0, "50.0": 9.0, "90.0": 9.0, "95.0": 9.0, "99.0": 9.0, "99.9": 9.0, "99.99": 9.0, "99.999": 9.0, "99.9999": 9.0, "100.0": 9.0}, "scoreUnit": "ms", "rawData": [[8.0, 9.0, 9.0]]}}}, {"users": "20", "primary": {"score": 7210590.470237062, "scoreError": 949028.935932927, "scoreConfidence": [6261561.5343041355, 8159619.406169989], "scorePercentiles": {"0.0": 7151430.489443895, "50.0": 7231164.98283, "90.0": 7249175.938437293, "95.0": 7249175.938437293, "99.0": 7249175.938437293, "99.9": 7249175.938437293, "99.99": 7249175.938437293, "99.999": 7249175.938437293, "99.9999": 7249175.938437293, "100.0": 7249175.938437293}, "scoreUnit": "ops/s", "rawData": [[7151430.489443895, 7231164.98283, 7249175.938437293]]}, "secondary": {"gc.alloc.rate": {"score": 1704.9115207483164, "scoreError": 222.36853433013346, "scoreConfidence": [1482.5429864181829, 1927.28005507845], "scorePercentiles": {"0.0": 1691.0250524334479, "50.0": 1709.8696590677205, "90.0": 1713.839850743781, "95.0": 1713.839850743781, "99.0": 1713.839850743781, "99.9": 1713.839850743781, "99.99": 1713.839850743781, "99.999": 1713.839850743781, "99.9999": 1713.839850743781, "100.0": 1713.839850743781}, "scoreUnit": "MB/sec", "rawData": [[1691.0250524334479, 1709.8696590677205, 1713.839850743781]]}, "gc.alloc.rate.norm": {"score": 248.00097259064714, "scoreError": 0.0001712432616289969, "scoreConfidence": [248.0008013473855, 248.00114383390877], "scorePercentiles": {"0.0": 248.00096317108947, "50.0": 248.0009726572751, "90.0": 248.00098194357676, "95.0": 248.00098194357676, "99.0": 248.00098194357676, "99.9": 248.00098194357676, "99.99": 248.00098194357676, "99.999": 248.00098194357676, "99.9999": 248.00098194357676, "100.0": 248.00098194357676}, "scoreUnit": "B/op", "rawData": [[248.0009726572751, 248.00096317108947, 248.00098194357676]]}, "gc.count": {"score": 69.0, "scoreError": "NaN", "scoreConfidence": [69.0, 69.0], "scorePercentiles": {"0.0": 23.0, "50.0": 23.0, "90.0": 23.0, "95.0": 23.0, "99.0": 23.0, "99.9": 23.0, "99.99": 23.0, "99.999": 23.0, "99.9999": 23.0, "100.0": 23.0}, "scoreUnit": "counts", "rawData": [[23.0, 23.0, 23.0]]}, "gc.time": {"score": 46.0, "scoreError": "NaN", "scoreConfidence": [46.0, 46.0], "scorePercentiles": {"0.0": 14.0, "50.0": 16.0, "90.0": 16.0, "95.0": 16.0, "99.0": 16.0, "99.9": 16.0, "99.99": 16.0, "99.999": 16.0, "99.9999": 16.0, "100.0": 16.0}, "scoreUnit": "ms", "rawData": [[16.0, 14.0, 16.0]]}}}]`
after：`[{"users": "1", "primary": {"score": 13004084.155150065, "scoreError": 1705988.2784547126, "scoreConfidence": [11298095.876695354, 14710072.433604777], "scorePercentiles": {"0.0": 12948305.689562403, "50.0": 12951905.45199294, "90.0": 13112041.323894856, "95.0": 13112041.323894856, "99.0": 13112041.323894856, "99.9": 13112041.323894856, "99.99": 13112041.323894856, "99.999": 13112041.323894856, "99.9999": 13112041.323894856, "100.0": 13112041.323894856}, "scoreUnit": "ops/s", "rawData": [[12948305.689562403, 12951905.45199294, 13112041.323894856]]}, "secondary": {"gc.alloc.rate": {"score": 1071.152114091855, "scoreError": 137.18809119646414, "scoreConfidence": [933.9640228953909, 1208.3402052883193], "scorePercentiles": {"0.0": 1066.6246950250797, "50.0": 1066.999174952911, "90.0": 1079.832472297575, "95.0": 1079.832472297575, "99.0": 1079.832472297575, "99.9": 1079.832472297575, "99.99": 1079.832472297575, "99.999": 1079.832472297575, "99.9999": 1079.832472297575, "100.0": 1079.832472297575}, "scoreUnit": "MB/sec", "rawData": [[1066.6246950250797, 1066.999174952911, 1079.832472297575]]}, "gc.alloc.rate.norm": {"score": 86.40053570442605, "scoreError": 6.709783892608352e-05, "scoreConfidence": [86.40046860658713, 86.40060280226497], "scorePercentiles": {"0.0": 86.40053346562792, "50.0": 86.40053369852774, "90.0": 86.4005399491225, "95.0": 86.4005399491225, "99.0": 86.4005399491225, "99.9": 86.4005399491225, "99.99": 86.4005399491225, "99.999": 86.4005399491225, "99.9999": 86.4005399491225, "100.0": 86.4005399491225}, "scoreUnit": "B/op", "rawData": [[86.40053346562792, 86.40053369852774, 86.4005399491225]]}, "gc.count": {"score": 43.0, "scoreError": "NaN", "scoreConfidence": [43.0, 43.0], "scorePercentiles": {"0.0": 14.0, "50.0": 14.0, "90.0": 15.0, "95.0": 15.0, "99.0": 15.0, "99.9": 15.0, "99.99": 15.0, "99.999": 15.0, "99.9999": 15.0, "100.0": 15.0}, "scoreUnit": "counts", "rawData": [[14.0, 14.0, 15.0]]}, "gc.time": {"score": 28.0, "scoreError": "NaN", "scoreConfidence": [28.0, 28.0], "scorePercentiles": {"0.0": 9.0, "50.0": 9.0, "90.0": 10.0, "95.0": 10.0, "99.0": 10.0, "99.9": 10.0, "99.99": 10.0, "99.999": 10.0, "99.9999": 10.0, "100.0": 10.0}, "scoreUnit": "ms", "rawData": [[10.0, 9.0, 9.0]]}}}, {"users": "20", "primary": {"score": 7372565.004786654, "scoreError": 474850.0156484408, "scoreConfidence": [6897714.989138214, 7847415.020435095], "scorePercentiles": {"0.0": 7343854.536060374, "50.0": 7379223.137277763, "90.0": 7394617.341021825, "95.0": 7394617.341021825, "99.0": 7394617.341021825, "99.9": 7394617.341021825, "99.99": 7394617.341021825, "99.999": 7394617.341021825, "99.9999": 7394617.341021825, "100.0": 7394617.341021825}, "scoreUnit": "ops/s", "rawData": [[7379223.137277763, 7394617.341021825, 7343854.536060374]]}, "secondary": {"gc.alloc.rate": {"score": 1743.0323787176076, "scoreError": 118.04508155825388, "scoreConfidence": [1624.9872971593536, 1861.0774602758615], "scorePercentiles": {"0.0": 1735.8419228317703, "50.0": 1744.8698462804118, "90.0": 1748.38536704064, "95.0": 1748.38536704064, "99.0": 1748.38536704064, "99.9": 1748.38536704064, "99.99": 1748.38536704064, "99.999": 1748.38536704064, "99.9999": 1748.38536704064, "100.0": 1748.38536704064}, "scoreUnit": "MB/sec", "rawData": [[1744.8698462804118, 1748.38536704064, 1735.8419228317703]]}, "gc.alloc.rate.norm": {"score": 248.0009469892365, "scoreError": 0.000335745899043234, "scoreConfidence": [248.00061124333746, 248.00128273513553], "scorePercentiles": {"0.0": 248.0009350484835, "50.0": 248.00093773635183, "90.0": 248.00096818287417, "95.0": 248.00096818287417, "99.0": 248.00096818287417, "99.9": 248.00096818287417, "99.99": 248.00096818287417, "99.999": 248.00096818287417, "99.9999": 248.00096818287417, "100.0": 248.00096818287417}, "scoreUnit": "B/op", "rawData": [[248.0009350484835, 248.00093773635183, 248.00096818287417]]}, "gc.count": {"score": 70.0, "scoreError": "NaN", "scoreConfidence": [70.0, 70.0], "scorePercentiles": {"0.0": 23.0, "50.0": 23.0, "90.0": 24.0, "95.0": 24.0, "99.0": 24.0, "99.9": 24.0, "99.99": 24.0, "99.999": 24.0, "99.9999": 24.0, "100.0": 24.0}, "scoreUnit": "counts", "rawData": [[23.0, 24.0, 23.0]]}, "gc.time": {"score": 43.0, "scoreError": "NaN", "scoreConfidence": [43.0, 43.0], "scorePercentiles": {"0.0": 14.0, "50.0": 14.0, "90.0": 15.0, "95.0": 15.0, "99.0": 15.0, "99.9": 15.0, "99.99": 15.0, "99.999": 15.0, "99.9999": 15.0, "100.0": 15.0}, "scoreUnit": "ms", "rawData": [[14.0, 15.0, 14.0]]}}}]`
JMH命令：`java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ActiveOrderIndexBenchmark.openThenTerminal -wi 3 -w 1s -i 3 -r 1s -f 1 -t 1 -jvmArgs "-Xms128m -Xmx512m -XX:+UseG1GC" -rf json -rff /tmp/core-latency6-index-micro-{before,after}/{plain,gc}.json`；gc轮单独加`-prof gc`。

最终实际完整命令（其他两组除artifact目录/代码外参数一致；运行角色PID见NMT首行及JFR文件名）：
plain：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-latency6-index-plain/window-256/end_to_end/data -Daeron.dir=/tmp/core-latency6-index-plain/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-latency6-index-plain/window-256/end_to_end/tmp -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-latency6-index-plain/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-latency6-index-plain/window-256/end_to_end/jmh.json ```
profile：
```text
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.owner-command-window=256 -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.data-dir=/tmp/core-latency6-index-profile/window-256/end_to_end/data -Daeron.dir=/tmp/core-latency6-index-profile/window-256/end_to_end/aeron -Djava.io.tmpdir=/tmp/core-latency6-index-profile/window-256/end_to_end/tmp -Dcore.settlementLatencyDiagnostics=true -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-latency6-index-profile/window-256/end_to_end/node.jfr\,maxsize=256m\,dumponexit=true -cp /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar com.surprising.aeron.service.cluster.SurprisingClusterNode
/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms128m -Xmx512m -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=/tmp/core-latency6-index-profile/window-256/end_to_end/client-aeron -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=30 -Dsurprising.aeron.capacity-seed=25620 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.capacity-symbols=128 -Dsurprising.aeron.mixed-operational=false -Dsurprising.aeron.capacity-async-in-flight=256 -Dsurprising.aeron.capacity-session-in-flight=256 -XX:StartFlightRecording=settings=/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc\,filename=/tmp/core-latency6-index-profile/window-256/end_to_end/client-%p.jfr\,maxsize=256m\,dumponexit=true -jar /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=256 -p tradingProfile=MIXED -p batchSize=20 -wi 0 -i 1 -f 1 -t 1 -to 150s -rf json -rff /tmp/core-latency6-index-profile/window-256/end_to_end/jmh.json ```

剩余瓶颈与下一步：
- 已确认：Owner前置FIFO/依赖scope阶段主导排队；基线有效JFR普通入口→准入p999.867ms，最终观测9.796ms；撮合发布→提交约1.6ms、Lane单次执行普通p99约8us/批量140us、出口FIFO约0.1ms。没有证据再把主要等待归给Lane账户修改或Owner锁。
- Owner仍约97.5%单核：批量解码、订单/账户索引访问、终态客户号桶维护和批量准入仍占成本；下一项应分别量化这些单位命令成本，避免一次迁移多个职责。解码前移虽消除了Owner解码热点，却没有通过尾延迟保留线，本轮不保留。
- 分配约2KB/business op并未解决；主要OrderRuntime、byte[]、ReservationRuntime、long[]与Matcher结果。GC最终最大9.383ms已超过5ms预算；需要从这些真实构造/版本变更路径减少冗余副本，再在无swap且预热稳定的窗口做验证，不能删除资金不变量或确定性边界。
- 本轮固定256满窗，客户端长期背压；不能把降窗口、降低发压、改GC或改批量口径包装为代码收益。各类accepted分段、网络重试尾部、CPU调度/长稳仍缺，普通p99≤5ms尚未验收。

关键文件/原始证据SHA-256（归档后清理，临时路径仅历史定位）：
| 文件 | bytes | SHA-256 |
|---|---:|---|
| /tmp/core-latency6-plain/window-256/end_to_end/metrics.json | 3135 | `06ad90a3ce29507febc8f2e82b5304fd90535eae67b666c8442de29a498e82f1` |
| /tmp/core-latency6-plain/window-256/end_to_end/client.log | 8274 | `833a8a693bc7ab52923ca9f3770f7edb855a445640d8b42d3ac5cffc4f3bbf1e` |
| /tmp/core-latency6-plain/window-256/end_to_end/node.command | 1365 | `c88bbf02938328738891f63c73b7702f5cce6131a10ec58ab8ede23f32693df3` |
| /tmp/core-latency6-plain/window-256/end_to_end/client.command | 1259 | `3e40b09ed60cfbf61bd22b0ec1e1c67db50523e2259746d48ef0701e1ab1f66d` |
| /tmp/core-latency6-plain/system-summary.json | 152 | `00534d36182d091d97c84871b174d344fe72b5882673725019f022ce9b3a4fad` |
| /tmp/core-latency6-profile/window-256/end_to_end/metrics.json | 3274 | `faf4aa65a198dd1fa02339df13da634b0d60ab6ee57b3f189d8a04ed2388e3a6` |
| /tmp/core-latency6-profile/window-256/end_to_end/client.log | 8877 | `0325272f8e0c0cd5869407605c994409af7b34b8b252c51e737693e12cf157ed` |
| /tmp/core-latency6-profile/window-256/end_to_end/node.command | 1674 | `e711de1bfb9fb6a12cfa3f77a0696617cad3e6d21244c50adb9d6e21a9fd9d7a` |
| /tmp/core-latency6-profile/window-256/end_to_end/client.command | 1530 | `a86ff4187edc9394be5e831b1d51982f4b203973821ce51c540e459230a8bc90` |
| /tmp/core-latency6-profile/system-summary.json | 150 | `d1cc35ae5ea47ef3100f8eb2d36b750d5f7141e36ab8d38ada850b59d52ad721` |
| /tmp/core-latency6-decode-plain/window-256/end_to_end/metrics.json | 3133 | `d04ca7ed754c33b9e50532127012c2269e9c6f35de4a0e7eebcd32f5c99c037e` |
| /tmp/core-latency6-decode-plain/window-256/end_to_end/client.log | 8294 | `c4e53f921b9cf36fbc824242fc5710f2ca1346c218270dbcf6abfa63a9cde4bb` |
| /tmp/core-latency6-decode-plain/window-256/end_to_end/node.command | 1386 | `6f5d6c60917144cd619a804c68405807a87260bc33620fd69b02ad3f76e78729` |
| /tmp/core-latency6-decode-plain/window-256/end_to_end/client.command | 1273 | `2c777f72c7182335539b682d9178eadef20466345957b495b5a7fe785410d2ab` |
| /tmp/core-latency6-decode-plain/system-summary.json | 89 | `9572ed56ce4ffb695195595fe6f1a21ea2a0640e197a44567666187c45065a3e` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/metrics.json | 3266 | `0858b70c3b3e2ac04142b668ea0333d94518d322e1118d5472015a014d2d11be` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/client.log | 8898 | `2dd8a1b6afa448c04a10574f5bda73630717b7b0b6569654f9e4bc0452f24d83` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/node.command | 1702 | `3c359b0064c982c34cb05a49ca0d0b6d6be99866d30025f64c694727485bf631` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/client.command | 1551 | `b3c9b12ccfbafe4e0caa111c73b9f5267bee74e03046f28ad9f3ebf9db9b5b49` |
| /tmp/core-latency6-decode-profile/system-summary.json | 87 | `4c604877a13ce4d49a58acf5a5c505ca7fb4a04269b9169ec596af6e79afd3fd` |
| /tmp/core-latency6-index-plain/window-256/end_to_end/metrics.json | 3134 | `7fec0876d6e757b02e273f40ed9b1e34ca6c69501884b49da6efa6620602ee81` |
| /tmp/core-latency6-index-plain/window-256/end_to_end/client.log | 8291 | `99249e5c842cab8e7a8e9faad5f5539740794486f239f09eea84c3c75177b4eb` |
| /tmp/core-latency6-index-plain/window-256/end_to_end/node.command | 1383 | `2a8d97b646dbd0ae275140f28f7cfb93c494094d7f7194ade55708e2a2cef1e6` |
| /tmp/core-latency6-index-plain/window-256/end_to_end/client.command | 1271 | `164fd1becfbc4805094d9de341f4f9d3a784e522c50295920c812c05fd59f59d` |
| /tmp/core-latency6-index-plain/system-summary.json | 89 | `a31ffe7e123a29601f4e6a22bd4d80af693c5cd2c38c1e756ad1636a16480e16` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/metrics.json | 3278 | `52c73e4c6b69b8bfb69416658d1c0f10d623bd8c42d96f384135498a5885e264` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/client.log | 8897 | `e6c11802b722157ecc13d2294240d3c6a4afec4629e718688793dd8352bfd8e4` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/node.command | 1698 | `05d63a67453f7347ec2819899dce28978a5be2d3eb975585bbbc48e69aea368d` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/client.command | 1548 | `617ef5f89e9f111bf4f08b962ffc1e422f3a28435764c6bf7357e780943ad162` |
| /tmp/core-latency6-index-profile/system-summary.json | 90 | `b7429bf85045e5a69c02494ab0d02ca8e7a5905a4054d22bf9dea99235d45404` |
| /tmp/core-latency6-profile/window-256/end_to_end/node.jfr | 107250524 | `eda569138d7fdbca559906d49c730100df2450da932092677642ff0f8bcb9a06` |
| /tmp/core-latency6-profile/window-256/end_to_end/client-2744.jfr | 90739336 | `179f215b3e66314b89629910e78b219da1e49d948e05c43515f49e2157ed873f` |
| /tmp/core-latency6-profile/window-256/end_to_end/client-2741.jfr | 5784731 | `cdabf53bcbe7d4f5ca1b444581fc6dd9f45f804b20e311d3e60b1fbfa5d3b4d4` |
| /tmp/core-latency6-profile/window-256/end_to_end/analysis.json | 103112 | `b02d95dcfb3db25b4791907fce241e91a1104e9be52b5fbad9c0a45b541a1c10` |
| /tmp/core-latency6-profile/window-256/end_to_end/window-events.tsv | 2683 | `1fa41d572db757dae65bb98fb702ca1d9ded2c12537bb81b7e84b121eff09a88` |
| /tmp/core-latency6-profile/window-256/end_to_end/client-window-summary.json | 1165 | `5dd117c91c577277eb04ee19b6a7d3b5224fd38ca33dd7722a29385a73f6bc00` |
| /tmp/core-latency6-profile/window-256/end_to_end/jfr-summary.txt | 12280 | `f042fd3e999d6b411976b9dfed7dafd7a069c02e0c11051ccb0af0314b760e84` |
| /tmp/core-latency6-profile/window-256/end_to_end/nmt-summary.diff.txt | 4628 | `a59b8d6e1ae6b852289c528426727c27d3158bea9736d42448f7e5044830083d` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/node.jfr | 109386145 | `5a8e5b7dd20d1b09ad976354f28f5abb15ed94a235f8af4843def361ba253f26` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/client-7009.jfr | 5942704 | `4f4a51191e0519313fa7577ffd5e2a06448ee806afac7c7f10bcc954ff14fd15` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/client-7014.jfr | 93831772 | `0f450aa5114132180d05ba24e39976c55d1ec0dfe830de87a22ea206e46e86be` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/analysis.json | 109059 | `b3027458968067c00d4d0714ace9c38d6d379b561a915543b2c487df663db5b7` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/window-events.tsv | 2595 | `700bb27038be0ef72df8dbc0819400a714c2308b021333a499bb61681333fb25` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/client-window-summary.json | 1074 | `d99a6752f9d0824615bca65ac45ac9fe13fdabd3a772e9998ab4261a4d82120a` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/jfr-summary.txt | 12280 | `e9c560cd5ac0e5c623ade8a9d9a00e712de3dbd265c0bba7df6e5207a71f6784` |
| /tmp/core-latency6-decode-profile/window-256/end_to_end/nmt-summary.diff.txt | 4622 | `5444ff1b22aab21e6820ae78dc46449cc670d3533c54d3cf03d917f43d6d336d` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/node.jfr | 113275940 | `80110c44696b994d7997e6581e9424f097e56dfd76cdc696902d2cafd99d5eb5` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/client-11150.jfr | 5872019 | `4e7cbcd51e865aba10d138e5cbe13cedd0bb33d25e9362f39068bca88c4672c6` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/client-11161.jfr | 99378650 | `4f423542bbf7a8c40e578d1851a25b36165e692a545275c3ef08e49440d60a72` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/analysis.json | 106474 | `654eb35ba5c2f28ebc17c05fd875b20063325c1638be20ee615ff3f9237e3f11` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/window-events.tsv | 2845 | `4f2812690f6b07e5d6a27e2508e035bb154baba8a1e07053146cef641eeafab8` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/client-window-summary.json | 1173 | `dbc9e578c9440f4b3839cd5cff1fc8c0110063e5694b8b9d656cf64a1c765a53` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/jfr-summary.txt | 12280 | `6769137b6dc4ff4d04ca9e2bd07660381fcad0d4d4d41a66072f63bc38ef1adb` |
| /tmp/core-latency6-index-profile/window-256/end_to_end/nmt-summary.diff.txt | 4630 | `4e2d584fcb4c106da1c0db8212fbee5793c513c7b2e62790544ce975adc2ed49` |
| /tmp/core-latency6-index-micro-before/plain.json | 3715 | `f10ed49d78ee17be74e3451c52d28f973ac04fc5174f0bb3fdf1c026ac5ace3e` |
| /tmp/core-latency6-index-micro-before/gc.json | 11460 | `8607d68d99a77650dec7f3cc314e2e57c7b6e1f99cafb771c2b35f6c62fbce4e` |
| /tmp/core-latency6-index-micro-after/plain.json | 3731 | `22cba19a03c0274fab72bd98bd04b6829208e40f4f1c577b0b5ba68d42e5baa2` |
| /tmp/core-latency6-index-micro-after/gc.json | 11463 | `f34cdb6a2d588a614b3992fab89653dbba00560488ab6da179c5ad0141fbdeed` |
| /tmp/core-latency6-source.diff | 13804 | `cda486aabe7dde539ba41cc899b502305c33ccd7ca4cdfa324c56910b0ae575b` |
| /tmp/core-latency6-decode-source.diff | 29304 | `0fea14dd4cdef657db26d1bd1535d14759ec6540fb941e29130d716fc1803ddc` |
| /tmp/core-latency6-index-source.diff | 19350 | `7416eeba2806215c37485e43966c4871917902d4c0611baea764316a28a0a0b2` |
| /tmp/core-latency6-index-counts.json | 759 | `2ad27a31695087b465ecf9823b5e66c8feeff76be55ad57c878e8c6156c2195e` |
| /tmp/core-latency6-index-full-tests.log | 215089 | `780548f312de2ea8daa56be90ec4db3f7c9c4f538d703c350fde851d5e17ad8a` |
| /tmp/core-latency6-index-bench-tests.log | 57100 | `ec07addc6e65f03d2638ac37f21efb75cb6221ba5866b64d444ff8bd3f59c6c6` |
| /tmp/core-latency6-decode-tests.log | 199046 | `8ce88d249dc4824b067b958677764f2f6cd7fdce83099e489071ecae60d904f6` |
| /tmp/core-latency6-decode-tests-fixed.log | 249938 | `1bb67ed2c5be0f6181be93dcd70d61def93930ebb5801a4d165ce79a48d98c9a` |
| /tmp/core-latency6-run.py | 1252 | `7ab07e3b76e29c9bd6ac0ef4b663c783a8232d5aa9c5a2976b3e6ba297eddbbd` |
| /tmp/core-latency6-analyze.py | 5143 | `bbe2d118b0f6d8b750f9880266e72193fdd7a914f5862433b2e5afaa35a30fcb` |
| /tmp/CoreLatency6Window.java | 1937 | `b2f82cdc9cfa47d3dc519dfb223f9cec90e3a21ce55b252ed5c5a21a034ee4b7` |
| /Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/service/state/index/ActiveOrderIndexBenchmark.java | 2284 | `1594e7539a356d833d8f3dbbc2ab1b435b9c048a3fe62810a6fb6562f71784b5` |


清理完成：6轮本机单节点、3次分析与4组微基准进程已退出；删除本轮临时data/Archive/media/JFR/日志/脚本及受影响模块本轮生成的测试报告，共677文件、逻辑大小15176588159bytes，路径/大小/摘要清单SHA-256 `93726f58d30e75ac43be8f7c3caca50e4beb1c16ea98facf83bf92a0a15adab4`。保留构建jar/classes及上述归档；未创建GCP资源。所有/tmp/core-latency6路径仅历史定位。JMH fork PID未单独记录、系统上下文切换/FD/直接缓冲区峰值未完整采集，作为部分验证缺口保留，不补造零值。

## 2026-09-15：准入客户索引原始值微优化（计划）

锁定单因素：新增 `TradingRuntimeState.orderIdByClientValue(userId, clientKey)`，用 `0` 表示缺失；仅替换 `RuntimeOrderAdmission` 和 `RuntimeCommandProcessor` 的撮合准入重复客户号校验。原有 `orderIdByClient` 保留给查询、快照和兼容调用，业务状态、顺序提交、Lane 所有权和拒单语义不变。

验证门槛：服务模块正确性测试必须全绿；新增/复用微基准需确认准入查询路径不再产生缺失值 `Long` 装箱；没有可重复的分配下降或出现吞吐/尾延迟回退则不保留该改动。压测仍使用固定单节点、1 matcher、4 lanes、128 symbols、window256、BUSY_SPIN、G1、MIXED batch20 口径。

结果：服务模块 963 项测试通过（1 项既有跳过）。JMH `ClientIndexLookupBenchmark` 在当前 JDK 25 HotSpot 下，nullable/primitive 的命中与未命中均约 91–101M ops/s，四者分配均约 `0.0001 B/op`；C2 已标量替换临时 `Long`，没有可重复的分配或吞吐收益。因此回退该别名及临时基准，不纳入后续压测比较。

## 2026-09-15：普通 PLACE 复用已解析订单（计划）

锁定单因素：普通 PLACE 的 Owner→Lane 准入已经持有 `ResolvedPlaceOrder`，Lane 完成后不再创建只含 7 个撮合字段的 `CoreMatchingOrder` 副本。`PlaceAdmissionEvent` 和 `CommandSlot` 直接交接解析订单；Matcher 增加读取 `ResolvedPlaceOrder` 的重载。替换、触发、批量和恢复路径继续使用原有 `CoreMatchingOrder`，不改变撮合证据、顺序提交、Lane 所有权或拒单语义。

验证门槛：服务模块正确性测试全绿；固定单节点、1 matcher、4 lanes、128 symbols、window256、BUSY_SPIN、G1、MIXED batch20 口径下做短稳态复测，观察业务 ops、普通订单 p99、G1 暂停与分配率。若没有可重复的分配/吞吐收益或出现尾延迟回退，则回退该改动。

结果：服务模块 963 项测试通过（1 项既有跳过）。固定本机单节点复测 15.024 秒，业务吞吐 `394,444.610/s`，Core 消息 `37,682.076/s`，fills `93,884.877/s`，`fundsDiff=0`、`unfinished=0`、完整生命周期校验通过。JMH GC 统计分配 `140.724 MB/s`，约 `1,509 B/business op`；历史 latency6-index profile 约 `1,989.65 B/business op`，方向上减少约 24%，但两次不是同一 OS 时间片，不能把差值作为严格因果增益。普通 PLACE p99 `11.526 ms`、p999 `18.219 ms`，仍未达到普通订单 p99≤5ms 目标；本改动只删除普通 PLACE 的撮合 DTO 副本，未解决 Owner FIFO/终态提交排队。JMH 结果已写入 `/tmp/core-place-reuse2/jmh.json`，节点和客户端进程均已退出，临时目录待本轮收尾清理。

## 2026-09-15：批量 PLACE 复用已解析订单（计划）

锁定单因素：批量 PLACE 的 `PlaceBatchAdmissionEvent` 不再为每项创建 `CoreMatchingOrder`，Matcher 直接读取 `OrderBatchPending.preparedOrders` 中已经完成准入的 `ResolvedPlaceOrder`；删除对应的批量 DTO 数组及清理逻辑。普通、替换、触发、清算和恢复路径保持原状，批量结果顺序、证据绑定与资金结算不变。

验证门槛：服务模块正确性测试全绿；固定单节点、1 matcher、4 lanes、128 symbols、window256、BUSY_SPIN、G1、MIXED batch20 口径下做带 `-prof gc` 的短稳态复测，比较业务吞吐、分配率、普通/批量 p99 和完整生命周期校验。无可重复收益或出现回退则回退该改动。

结果：服务模块回归全绿，固定口径短稳态完整校验通过；批量复用轮业务吞吐 `369,230.365/s`、分配约 `1,521 B/business op`、普通 PLACE p99 `14.843 ms`，低于前一轮普通 PLACE 复用基线 `394,444.610/s`、约 `1,509 B/business op`、p99 `11.526 ms`。单轮不足以证明因果，且无分配下降，按门槛回退批量改动及其 DTO 数组删除；该实验不纳入主线结果。节点和客户端进程已退出，临时目录待本轮收尾清理。

### 2026-09-15 Owner 批内准入上下文查找去 Map 化

计划：`preparePipelinedPlaceBatch` 的批内唯一币对数量受批量上限约束，改用已有 `preparedSymbols` 配套的固定决策数组线性查找，移除每批 `HashMap<String, Decision>` 的节点探测与状态；不改变准入顺序、跨币对校验、Matcher/Lane 交接或响应语义。先执行服务全量正确性测试，再按固定单节点、1 matcher、4 lanes、128 symbols、window256、BUSY_SPIN、G1、MIXED batch20 口径做短稳态吞吐/分配/p99 对比；无可重复收益或发生回退则回退。

结果：服务模块 963 项测试通过（1 项既有跳过），完整生命周期校验通过。相同脚本的两轮无 JFR 吞吐为 `368,287/s` 与 `392,706/s`，JFR 轮为 `399,230/s`；相对 `394,445/s` 基线处于单轮抖动范围，没有可归因的吞吐提升或回退。JFR 中 Owner `97.33%` 单核、Matcher `55.06%`、4 个 Lane 各约 `98.31%`；`HashMap.getNode` 不再出现在热点列表，批量准备热点降至 `0.30%`，但 Owner 的 `Long2ObjectHashMap` 访问、终态桶和状态索引仍是主要成本。保留该改动作为无语义变化的去节点优化，后续不把它当作吞吐收益；分配主项仍为 `OrderRuntime`、`byte[]`、`ReservationRuntime`、`long[]` 和 Matcher 结果。节点/客户端均已退出，压测临时目录保留在 `/tmp` 供审计。

### 2026-09-16：Owner 活跃订单索引包装复用（计划与结果）

计划：`ActiveOrderIndex` 终态移除后清空并在线程本地池中复用 `IndexedOrder` 包装，避免每次新订单重新分配包装对象。池不属于快照或业务状态，索引映射、二级索引、终态删除和恢复语义均保持不变；无跨线程共享，也不改变订单值对象的生命周期。

验证：服务模块 `978` 项测试通过，失败/错误 `0`，既有跳过 `1`；基准模块重新打包成功。`ActiveOrderIndexBenchmark.openThenTerminal`（G1、JDK 25、1 fork、5 次测量）分配从 `86.4005` 降至 `62.4009 B/lifecycle`（1 用户，约 `27.8%`），从 `248.0010` 降至 `224.0010 B/lifecycle`（20 用户，约 `9.7%`）；该微基准的吞吐区间重叠，不宣称吞吐提升。固定单节点端到端（1 Matcher、4 Lane、128 symbols、window256、BUSY_SPIN、MIXED batch20）完整校验通过：`379,144.119 business ops/s`、`36,228.289 core messages/s`、fills `90,283.595/s`、普通 PLACE p99 `11.567 ms`、最差业务 p99 `18.268 ms`、`unfinished=0`、`fundsDiff=0`。相对同口径 `392,705.617/s` 基线约低 `3.5%`，未超过预设 `5%` 回退门槛，单轮不能归因收益或回退。

结论：保留该无语义变化的包装复用，降低 Owner 活跃索引的短命对象分配；它没有解决 Owner 的主要 CPU 瓶颈。当前端到端仍由 Owner 串行索引/终态提交和前置排队限制，Owner/Lane 业务状态未删除。原始压测目录 `/tmp/core-index-reuse-20260916`、JMH 分配结果 `/tmp/active-index-reuse-gc.json` 保留供审计；节点与客户端均已退出。

### 2026-09-16：移除 Owner 发布表准入序号副索引（计划与结果）

计划：删除 `LanePublishedMap` 中仅用于查询批量 pending 的 `Long2LongHashMap`，同时移除 `LanePublication.admissionSequence` 及每次发布的序号维护。`PendingReservationTracker.pendingReservation` 改为读取已有 `PendingBatch` 收据中的订单数组；普通单订单仍走 `pendingReservationUsers`，批次注册、完成、回滚和快照语义不变。

验证：完整服务回归在改动后通过 `963` 项（失败/错误 `0`、既有跳过 `1`）；新增批量 pending 查询用例精确通过，单类回归 `59` 项全绿。固定单节点（1 Matcher、4 Lane、128 symbols、window256、BUSY_SPIN、G1、MIXED batch20）两轮完整生命周期均通过：第一轮 `361,726.234 business ops/s`、最差业务 p99 `30.785 ms`，第二轮 `418,283.703 business ops/s`、最差业务 p99 `17.874 ms`，均 `clientPass=true`、`peakInFlight=256`。两轮均值 `390,004.969/s`，相对既有同口径 `392,705.617/s` 约低 `0.7%`，小于 `5%` 回退门槛；短测方差较大，不能宣称吞吐提升或回退。

结论：保留该改动。Owner 发布边界不再维护重复的订单→准入序号 Map，减少发布/终态提交的哈希操作和一份中间状态；批量 pending 查询从已有收据读取，查询频率低时的批次线性扫描不进入交易热路径。原始目录 `/tmp/core-published-seq-remove-20260916`、`/tmp/core-published-seq-remove-rerun-20260916` 保留审计；节点与客户端均已退出。
