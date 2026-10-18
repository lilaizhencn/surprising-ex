# 性能验证记录

## 2026-10-18：单成员 Aeron 永续 FUSED / PIPELINED 对比

### 采集前锁定计划（20261018T032558Z-perpetual-modes）

- 任务：按用户要求，在最新 `master` 对比两种执行模式。基准 commit `0bc0667301b335e97477a06a8fc8a10edc7554e5`，已 fetch 确认与 `origin/master` 一致；开始时工作树干净。对照 commit：不适用（仅验证当前 master）。本轮不修改交易逻辑，新增文件仅为验证记录。
- 业务入口：现有 `qualify-aeron-async-stages.sh` 的 `end_to_end` 阶段 → `ClusterOperationalBenchmark.continuousOperations` → `ClusterMixedCapacityMain`，保留真实 UDP、Consensus、Archive 和 Core。先注册币对、注入资金和标记价、建立零售持仓与挂单，完成一次强平/保险/ADL 生命周期；然后持续异步批量挂撤单和 IOC 成交，最后排空并查询账户/国库，核对资金、持仓、预约、订单终态及 accepted/terminal。任何拒绝、超时或资金不符记业务失败。
- 唯一对照因素：通过 `JAVA_TOOL_OPTIONS=-Dsurprising.aeron.execution-mode=FUSED` 或 `PIPELINED` 显式设置 `TradingExecutionMode.configured()`。同一构建产物、相同 seed=25620、相同初始化；运行顺序为吞吐 FUSED→PIPELINED、GC PIPELINED→FUSED、JFR FUSED→PIPELINED，始终串行启动，每次新建隔离节点。
- 场景：仅 `LINEAR_PERPETUAL`（U 本位永续），1 个真实 Aeron 成员、1 matcher、2 Account Lane；MIXED，128 symbols、1000 零售用户、384 做市/持仓账户及1个损失账户；每个普通账户注入1,000,000,000单位 USDT，损失账户100单位，保险25单位，总额1,384,000,000,125。20项批量，maker/taker 连续提交；每周期批量新单60项、批量撤单20项、普通下单2项、普通撤单2项（每币对），20 fills；初始零售挂单按用户序号 mod 11 设置。
- 负载：全局及命令 session 在途上限固定256；1个命令 session、1个保留查询 session。采用现有持续异步窗口背压模型，没有恒定计划到达率；不宣称 open-loop 或容量饱和。客户端在窗口前等待时间单列；延迟为实际提交→终态，未做 coordinated omission 校正，缺少计划到达→提交排队和分段 accepted 延迟时明确列为缺口。
- 时间：无 profiler 主吞吐各1轮，预热30秒、稳定60秒；独立 `-prof gc` 各1轮，预热30秒、测量30秒；独立 JFR 各1轮，预热30秒、测量30秒。JMH均1 fork/1 thread/1 SingleShot，内部自行区分预热与稳定测量，排空和查询不计入稳定吞吐。单轮比较不估计统计显著性，无长稳结论。
- 环境：本机 MacBookPro16,1，Intel i9-9880H 2.30GHz，8核16线程，16GiB RAM，macOS 26.7.2 x86_64；Corretto 27+33 HotSpot，Maven 3.9.16。初始剩余磁盘341GiB，已存在约2GiB系统 swap，需以测量窗口内 swap-in/out 增量和热控采样判定环境有效性。无既存 Java 服务；桌面应用及系统进程构成同机干扰，不停止用户程序。
- JVM/资源：G1；节点 Xms512m/Xmx1536m、AlwaysPreTouch、DisableExplicitGC、NMT summary；客户端 Xms128m/Xmx512m。matcher/settlement BUSY_SPIN，owner及matcher-pipeline ADAPTIVE，service YIELDING，SHARED_NETWORK；BUSY_SPIN CPU不当作有效业务饱和证据。每5秒记录进程CPU/RSS、vm_stat、swap、pmset热控、磁盘及本轮目录大小；可用磁盘低于10GiB、目录超过6GiB或单日志超过128MiB时停止本轮并记录异常。
- 采样：JFR使用仓库 `config/owner-commit-profile.jfc`，CPU及线程/分配统计1秒、execution/native sample 20ms、锁/park/I/O阈值0；节点及各客户端进程独立录制，上限各256MiB。检查DataLoss和稳定窗口覆盖，归档summary/view、关键栈、原文件路径/大小/SHA256；只将覆盖有效的证据用于归因。`-prof gc` 只测客户端JMH fork，不能当作节点Core分配，节点分配另由JFR事件估算。
- 判定：用户未给业务吞吐/SLA门槛，本轮为探索对比，预先不设置吞吐及P99通过线。正确性要求拒绝/异常/超时0、accepted=terminal、unfinished及期末backlog0、fundsDiff=0、持仓/订单/预约符合夹具。热控/测量期间swap或录制缺失将对应性能证据记为无效/部分验证；业务错误始终记失败。完整验收还缺真实快照重启恢复、持续资金费/止盈止损负载、币本位永续、HTTP/WebSocket链路和长稳，不能用本轮替代。
- 构建命令：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`（已先核对 `java -version` / `mvn -version`）；只构建直接依赖，不启动 wallet、Kafka、HTTP 或其他产品线。用本轮压测自身业务核对验证执行路径，不运行包含六产品线的旧对比测试。
- 复现命令模板（在仓库根目录，MODE取FUSED/PIPELINED；主吞吐W=30/D=60/JFR=false/PROF为空；GC为30/30/false/gc；JFR为30/30/true/空）：

```sh
JAVA_TOOL_OPTIONS="-Dsurprising.aeron.execution-mode=$MODE" \
ASYNC_RUN_ID="20261018T032558Z-perpetual-modes-$MODE-$KIND" \
ASYNC_ARTIFACT_DIR="$PWD/performance-artifacts/20261018T032558Z-perpetual-modes/$MODE-$KIND" \
ASYNC_SKIP_BUILD=true ASYNC_ONLY_STAGE=end_to_end ASYNC_WINDOWS=256 \
ASYNC_ACCOUNT_LANES=2 ASYNC_MATCHING_ENGINES=1 ASYNC_SYMBOLS=128 \
ASYNC_BATCH_SIZE=20 ASYNC_TRADING_PROFILE=MIXED \
ASYNC_WARMUP_SECONDS="$W" ASYNC_MEASURE_SECONDS="$D" \
ASYNC_ENABLE_JFR="$JFR" ASYNC_JMH_PROFILER="$PROF" \
bash surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-aeron-async-stages.sh
```

- 清理：分析入档后停止本轮进程，核实 PID 和本轮目录边界，清理本轮临时集群、Archive、JFR、日志、临时报告；保留本文件及可复核的精简结果/证据。原始路径作为历史定位，并追加清理状态。

### 执行结果与证据

**业务正确性：6/6轮通过。性能验收：无效；本次仅提供受限宿主环境下的观测对比。** 所有轮次均存在热控和测量窗口附近的 swap-in，不能以这些数据认定模式固有性能、容量上限或回归幅度。用户未提供SLA，不追加事后门槛。

构建20个 reactor 模块成功，未修改生产逻辑。全部数据来自同一构建、同一 commit，没有旧版本对照。完整计数、延迟、逐轮JVM命令、PID、资源摘要、JMH结果、JFR summary/view、稳定窗口聚合、NMT及原文件大小/SHA256见 [本轮验证JSON](docs/validation/perpetual-modes-single-node-20261018.json)。

#### 无 profiler 主轮

同为MIXED、batch20、128币对、1 matcher、2 Lane、全局窗口256，内部预热30秒/正式60秒，各1次；表中吞吐只取 `steadyCapacity`，不计排空。业务项含夹具标记价更新；批量按item展开，不能等同HTTP请求/s或独立订单/s。Core消息/s按批次/单条命令计数。

| 指标 | FUSED | PIPELINED |
|---|---:|---:|
| 正式窗口（本地时间） | 11:28:05.422–11:29:05.426 | 11:30:00.048–11:31:00.075 |
| 稳定窗口秒数 | 60.004376 | 60.026885 |
| 持续终态业务项/s | 271,370.860 | 383,937.266 |
| 持续终态Core消息/s | 25,959.424 | 36,681.664 |
| 成交fills/s | 64,592.623 | 91,393.715 |
| 窗口内终态业务项 | 16,283,439 | 23,046,558 |
| 窗口内终态Core消息 | 1,557,679 | 2,201,886 |
| 排空后 offered=terminal 业务项 | 16,286,127 | 23,049,245 |
| 排空后 offered=terminal Core消息 | 1,557,935 | 2,202,141 |
| 排空业务项/消息数 | 2,688 / 256 | 2,687 / 255 |
| 峰值在途 / 期末未完成 | 256 / 0 | 256 / 0 |
| 窗口背压时间占比 | 93.97% | 88.08% |
| 节点平均CPU（100%=1逻辑CPU） | 515.9% | 873.6% |
| 发压fork平均CPU | 296.8% | 297.5% |
| 节点RSS最大采样值 | 1,780.1MiB | 1,777.4MiB |
| CPU频率上限采样范围 | 70–75% | 68–72% |
| 窗口前后包络swap-in | 1,586页，约6.20MiB | 2,356页，约9.20MiB |
| 窗口前后包络swap-out | 0页 | 0页 |
| 资金差额 | 0 | 0 |

本轮观测到 PIPELINED 的持续业务项吞吐高约41.5%，但热控、换页、单次重复和没有递增负载平台证据限制了结论。窗口背压已改变实际提交节奏，不能声称实现了固定到达率。发压端也占用约3个逻辑CPU；未通过多连接/多提交线程单因素诊断排除客户端瓶颈。节点总CPU包含网络、Archive、Consensus、owner以及BUSY_SPIN，不代表等量有效业务工作。

以下为主轮客户端实际提交→终态延迟，单位ms。批量为整条批次请求延迟，不能再除以20。统计包含该测量轮提交请求在排空时取得的终态，尾部排空完成量已从稳定吞吐中排除。精度为HDR三位有效数字，直方图范围1ns–60s；连接/请求超时30s，单次JMH主轮超时180s。

| 业务 / 模式 | 请求样本数 | P50 | P90 | P95 | P99 | P99.9 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|
| 普通下单 / FUSED | 387,584 | 9.191 | 12.517 | 13.271 | 17.252 | 35.848 | 53.542 |
| 普通下单 / PIPELINED | 548,608 | 5.160 | 8.806 | 9.658 | 11.739 | 21.987 | 43.253 |
| 普通撤单 / FUSED | 387,584 | 11.796 | 13.524 | 14.295 | 17.940 | 40.632 | 54.919 |
| 普通撤单 / PIPELINED | 548,608 | 4.620 | 7.127 | 7.716 | 9.527 | 20.774 | 44.662 |
| 批量下单 / FUSED | 581,376 | 9.773 | 14.630 | 15.745 | 20.414 | 44.302 | 66.748 |
| 批量下单 / PIPELINED | 822,912 | 7.876 | 12.935 | 14.090 | 16.121 | 31.735 | 61.276 |
| 批量撤单 / FUSED | 193,792 | 14.868 | 17.022 | 18.399 | 26.329 | 52.920 | 66.584 |
| 批量撤单 / PIPELINED | 274,304 | 10.346 | 12.115 | 13.205 | 19.415 | 34.734 | 61.210 |
| 标记价 / FUSED | 7,599 | 7.725 | 12.582 | 15.147 | 26.066 | 39.878 | 41.025 |
| 标记价 / PIPELINED | 7,709 | 4.907 | 9.027 | 10.215 | 13.762 | 22.921 | 40.075 |

FUSED六个10秒进度区间前五个为26.33/27.38/28.19/27.14/27.35万业务项/s；PIPELINED为37.83/39.36/39.62/36.90/38.78万业务项/s，末段通过稳定总数核对。未观察到基于高水位即可证明的持续队列饱和；脚本两轮均给 `saturationGate=UNCONFIRMED`。

#### 完成性、资金与模式生效

- 两种模式的主轮、独立GC轮、独立JFR轮均为 `mixedCapacity=PASS`、`mixedVerify=PASS`，全局窗口峰值256、期末unfinished=0，逐响应及批量item均要求APPLIED；夹具遇拒单、异常或超时会失败退出，本轮全部脚本退出0。主轮排空约11.01ms/7.14ms，精确纳秒值见JSON。
- 每轮均校验零售用户持仓/挂单、做市与taker实际持仓、空预约、终态订单不残留，以及所有用户available+locked与国库各资金科目合计；总额均为1,384,000,000,125单位USDT、`fundsDiff=0`。不同模式完成周期数不同，所以持仓数量与businessHash按各自周期校验，不要求两轮哈希相等。此夹具费率为0，不证明非零手续费路径。
- 强平、保险基金、ADL和风险排空在初始化边界均执行且通过；它们没有进入稳定窗口的持续吞吐。资金费与止盈止损未连续发压，不能将上述交易吞吐扩展到这些动作。
- 每个节点日志和fork JVM参数均明确记录所选模式。主轮FUSED节点PID38656、发压fork38686；PIPELINED节点39564、fork39586。FUSED线程快照无独立matcher、Account Lane及System Ledger worker；PIPELINED包含 `core-matcher-0`、`core-account-lane-0/1` 和 `core-system-ledger-lane`。其余四轮PID及起止时间见JSON。

#### 独立分配、GC及JFR诊断

GC与JFR均为预热30秒、正式30秒，各模式各1轮；没有把诊断分数代替主轮吞吐。诊断时长与主轮不同且宿主热控变化，未获得可分离的纯profiler开销，不作百分比性能归因。

| 诊断指标 | FUSED | PIPELINED |
|---|---:|---:|
| 独立GC轮观测业务项/s | 275,994.627 | 376,129.100 |
| JMH客户端分配率（全调用生命周期） | 978.321MiB/s | 814.161MiB/s |
| JMH客户端GC次数/时间（全调用生命周期） | 1,056 / 710ms | 862 / 689ms |
| 独立JFR轮观测业务项/s | 276,557.715 | 385,987.339 |
| 节点分配估算B/终态业务项 | 297.61 | 107.65 |
| 发压fork分配估算B/终态业务项 | 3,642.79 | 2,120.63 |
| 节点名义稳定窗口GC次数 / 总暂停 | 8 / 22.62ms | 4 / 16.06ms |
| 节点最大GC暂停 | 2.94ms | 4.21ms |
| 发压fork窗口GC次数 / 总暂停 | 409 / 264.73ms | 321 / 259.33ms |
| 发压fork最大GC暂停 | 1.11ms | 1.47ms |
| JFR owner单逻辑CPU占用 | 99.60% | 98.53% |
| JFR matcher单逻辑CPU占用 | 不适用，无独立线程 | 50.44% |
| JFR Account Lane单逻辑CPU占用 | 不适用，无独立线程 | 99.73% / 99.73% |
| Direct buffer采样 | 8个，9,575,136B，窗口内不变 | 8个，9,575,136B，窗口内不变 |
| DataLoss（节点/runner/fork） | 0 / 0 / 0 | 0 / 0 / 0 |

分配估算用名义稳定窗口内各线程 `ThreadAllocationStatistics` 累计值末减首，再除以该窗口终态业务项。它包含整个节点的网络/Archive/服务线程，遗漏边界不足1秒的区间及短命线程，并受下述JFR时间对齐缺口限制，不是精确对象计数或严格生产分配验收。`ObjectAllocationSample`、NewTLAB、OutsideTLAB及线程分配事件均开启；样本权重不是实际对象个数。JMH `gc.alloc.rate.norm` 为每次完整SingleShot调用82,829,701,872B / 67,541,895,784B，包含夹具生命周期，绝不能写成每业务订单的B/op。

FUSED分配样本主要定位到 `TradingRuntimeState.preparedOrder` 的 `OrderRuntime`（加权约1.20GB）、`OrderRuntime.prepareReservation` 的 `ReservationRuntime`（约456MB）；PIPELINED主要为 `PlaceOrderCommand.decodeClientOrderId`、`CoreMessage.decoderPayloadBuffer`、`DeterministicExchangeCoreAdapter.ensureSymbol`。对应源码位于service模块的 `state/TradingRuntimeState.java`、`state/OrderRuntime.java`、`matching/DeterministicExchangeCoreAdapter.java` 和protocol模块。额外对象构造是FUSED分配差异的候选原因，尚未通过局部JMH/单因素修改验证其对吞吐的贡献。

FUSED owner执行样本出现 `RuntimeChangeBuffer.put`、`RuntimeIdentityRegistry.hashUtf8`、`RuntimeOrderAdmission.requiredReservation` 等；PIPELINED还包含大量 `SystemLedgerLane.run`、`SettlementLaneWorker.run` 和 `OwnerIdleStrategy.idle`。BUSY_SPIN与等待占用未完整拆开，不能用这些CPU排名认定Account Lane或Ledger业务饱和。FUSED输出的Lane executionNanos=0代表该worker计时不适用，不能解释成没有账户计算。

两轮Archive线程名义窗口文件写分别665,199次/5.99s、809,301次/7.90s，最长约20.69ms/20.35ms；真实网络和Archive被保留。PIPELINED matcher约300,950次park、累计17.43s，FUSED不存在独立matcher；CPU和等待证据只能说明线程活动分布，尚未定位吞吐瓶颈。没有基于短轮次声称无泄漏；NMT的逐类reserved/committed、峰值及首末变化已保留在JSON，Direct统计不涵盖所有mapped Archive和native分配，RSS也不能与NMT committed直接等同。

#### 时间边界、I/O门槛与未测范围

JFR文件均可解析，CPU周期事件覆盖客户端报告的稳定窗口，节点和各客户端均无DataLoss，也没有maxsize滚动截断证据。但跨JVM的JFR epoch与客户端 `System.currentTimeMillis` 未做显式校准：两种模式均在名义窗口开始约70–72ms处记录owner的两次文件读（合计2,581B），栈为类加载→`TradingCoreRuntime.encodeLaneMetrics`→查询。源码 `ClusterMixedCapacityMain.runFor` 在设置测量起点之前同步查询Lane metrics；因此不能将这些事件直接归因为交易订单热路径I/O，也不能悄然把它们从原始窗口统计中删除。

**owner名义窗口同步I/O门槛未通过；精确时间归属未完成。** 记录中保留了逐事件精确时间、路径和栈。没有观测到owner文件写或数据库调用的JFR证据，但原生I/O和数据库事件覆盖有限，不能据此断言不存在。正式主链路验收必须先校准窗口或增加同进程边界事件并预热指标查询，再复核这条门槛。JFR分配、CPU和GC聚合均标为名义窗口近似，不将跨进程时间直接相减计算业务延迟。

仍未覆盖：真实snapshot→Archive重启→恢复一致性、长稳及native/FD增长斜率、持续资金费/止盈止损负载、币本位永续、HTTP/API和WebSocket完整链路、有效处理/空转/依赖等待的持续队列时间序列、入口→accepted及accepted→terminal分段延迟。普通下单/批量/撤单延迟已分开，强平/ADL初始化不提供稳定窗口吞吐或分位数。未启动wallet、Kafka及其他产品线。

计划执行偏差：macOS `ps` 不支持 `nlwp`，原进程采样未取到PID；在首轮正式窗口之前已启动兼容的补充采样，覆盖了全部正式窗口，最终PID/CPU/RSS来自补充记录。已完成轮次的隔离集群及Archive在下轮预热期间清理，防止目录累积超过6GiB；JFR及文本证据保留至分析入档。构建和本轮业务夹具是执行验证，未额外运行全量Maven单元测试；没有生产代码变更，最终文档执行 `git diff --check` 及JSON完成性核验。

下一项最小验证：在无热控、无测量期间换页的宿主上，以同一commit/产物/参数执行两种模式各至少3轮，保持窗口256和单matcher；先预热Lane指标查询并校准同进程测量边界，再取得可比较的正式吞吐、尾延迟和owner I/O证据。若仍需解释FUSED分配，沿上述两个对象构造入口做局部JMH与单因素归因；本轮不修改这些业务实现。

### 清理与交付状态（2026-10-18 11:42:24 +08:00）

6轮节点、JMH runner/fork、采样和分析进程均已结束；删除前再次核实本轮目录没有存活JVM引用。分阶段清理了24个本轮隔离集群/Archive/临时目录，分析归档后删除运行根目录 `performance-artifacts/20261018T032558Z-perpetual-modes` 及其剩余195个文件（约283MB，含JFR、日志和临时汇总/分析工具）。JFR路径仅供历史定位，原始大小/SHA256、summary/view和关键窗口聚合保留于验证JSON；未删除其他轮次或其他任务文件。清理后可用磁盘约341.6GiB，本轮未触发空间/日志/录制超限停止。

最终保留本记录和验证JSON；JSON检查覆盖6轮组合、模式实际生效、资金差额0、业务项与Core消息完成性、窗口256、各进程JFR文件完整性和清理完成状态。文档暂存后执行 `git diff --cached --check`。无生产代码改动，不新增类、接口、运行时状态或业务阶段。
