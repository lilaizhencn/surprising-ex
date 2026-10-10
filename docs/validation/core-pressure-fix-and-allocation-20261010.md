# 当前 Core 压测故障修复与分配诊断（2026-10-10）

本轮确实同时存在**过时的压测代码**和**新实现中的运行时故障**。已分别修复，并在最新固定构建上完成真实单成员 Aeron Cluster 的 60 秒诊断、300 秒功能长稳、Archive 全量重放和实际快照重启。资金、持仓、订单及业务状态核对通过。**正式性能证据无效**：本机 JFR 测量期 CPU_Speed_Limit 最低 62，后续长稳最低 60，无 profiler 的三个主轮和独立 GC profiler 轮已取消，不能据此宣称优化收益百分比或服务器容量。

完整命令、源码/JAR 指纹、失败日志、逐类型延迟、系统时间序列、各进程 JFR 汇总、测试结果和清理状态见 [JSON 证据](core-pressure-fix-and-allocation-20261010.json)。采集前计划和变更记录见 [PERFORMANCE_VALIDATION.md](../../PERFORMANCE_VALIDATION.md)。所有临时绝对路径仅用于定位本轮历史文件；清理后以入档证据为准。

## 排查和修复

| 问题 | 证据及影响 | 修复 |
|---|---|---|
| 删除账户镜像后旧微基准无法编译 | `OwnerPublicationBenchmark` 仍调用已经删除的发布接口 | 删除两个已经没有对应生产路径的基准，保留四个实际缓冲/收集路径基准 |
| 旧压力断言要求多个账户工作线程并行 | 当前账户由 Owner 写入，工作线程和队列已经移除，旧队列高水位为 0 | 改验实际分区完成计数、资金守恒、预期持仓、订单及 accepted/terminal 完成性，未降低业务正确性要求 |
| 池化结算事件过早发布 ready | 真实 Cluster 预热时 Matcher 抛 `invalid Matcher-owned settlement publication`；Owner 可看到 ready 并回收事件，生产者随后还访问该对象 | 发布分派元数据、释放回调和诊断字段均放在 volatile ready 之前；之后只用捕获的 runtime/lane mask 唤醒，不再读写可回收事件 |
| 预派发提前结算，以及较晚结果越过较早结果 | 测试复现 `direct result is not published`；随后复现较晚序号 526 在较早 518 之前更新同一账户分区 | 预派发只登记。即使已经 ready，也必须由 Owner 的有序提交头推进账户结算 |
| Matcher 发布异常导致线程退出而 Owner 等待超时 | 初轮 pending=71、ingress=185，合计窗口 256；发布异常来自 `finally`，旧错误处理未覆盖 | 工作循环兜底记录 publicationFailure、停止接受新任务、通知 Owner；Owner 查询/提交及时传播故障 |
| JMH 启动器返回 0 掩盖 fork 业务失败 | 首轮客户端失败，但旧编排仍返回成功 | 脚本额外要求 `mixedCapacity=PASS` 和 `mixedVerify=PASS`，缺少资金/状态核对也失败 |
| 默认跳过的旧延迟诊断断言 | 开启诊断后仍要求已经删除的 `OwnerSettlementMerge` 事件，测试失败 | 删除镜像合并阶段的旧断言，保留现有 SettlementLatency、OwnerHead、CommandBoundaryLatency、OwnerTurn、OwnerPublication 的顺序/耗时/复用核对 |
| 有限大小 JFR 滚动录制误判完整窗口 | 长稳 node.jfr 保留最后约 146 秒；DataLoss=0 仍会丢掉早期录制 | 汇总增加 CPU 事件首尾时间与请求窗口的覆盖判定；部分/未限定窗口不作为完整测量证据，新增覆盖回归测试 |

新增回归覆盖 Owner 提前登记、较晚结果先 ready、Matcher 唤醒尚未返回时 Owner 已回收复用，以及发布异常到 Owner 的传播。修复依赖工作区已有的账户单写者改造，相关 Aeron 源码及其回归测试按可构建的整体提交。

## 固定构建及验证范围

- 源码：master `ec024555a16dba3a033079ad06835daf6847398f` 加采集前工作区优化与本轮修复，独立复制后从零构建；不运行历史版本。完整文件 manifest 入档。最终 Python 覆盖判定修复发生在采集后，只重新分析相同录制，没有替换被测交易 JAR。
- JDK：Corretto HotSpot 27+33；Maven 3.9.16。service JAR SHA-256 `61ee1202ad710135015d1b018a06825137cb09f9412b47a0e60eac367f749361`；benchmark JAR SHA-256 `cfa094022acac46c5e057e16f14f19e753a6724dcb4738e67197b471671b823d`。
- 本机：Intel i9-9880H，8 核/16 逻辑 CPU，16 GiB 内存，macOS 26.7.2；已有 swap 和后续热降频进入证据，不能视为专用稳定压测机器。
- 真实 UDP/Archive/Core，LINEAR_PERPETUAL，单成员、1 Matcher、4 个逻辑账户分区、0 个账户工作线程；global/session in-flight=256（含诊断），MIXED/batch20/128 合约/seed25620，60 秒业务预热。G1；节点 512m–1536m，客户端 128m–512m；Owner/Matcher BUSY_SPIN。
- 修复后 70 项业务/资金/恢复/基准检查和 88 项六产品线检查通过（15 项结算回归重复执行）；另有产品 API/协议/客户端 312 项通过。Python 汇总判定 11 项通过。最后受影响服务 11 个测试类复验 397 项：0 失败、0 错误、396 通过，1 项要求诊断开关的测试随后单独开启并通过（修正旧镜像事件断言），结果见 JSON；没有把各阶段重复用例相加当成独立覆盖数，也没有宣称全后端测试通过。

初轮 79 项检查中 3 项失败，实际 Cluster 也在预热失败；后续修复迭代中的失败和测试编排错误均保留。初轮没有稳定测量窗口、资金终态核对，不能当作优化前对照。

## 实际业务结果

| 场景 | 测量时间 | 测量期业务终态项 | 测量期 Core 消息 | 排空后 accepted=terminal | 未完成/资金差 |
|---|---:|---:|---:|---|---|
| 独立 JFR 诊断 | 60.043289 秒 | 11,896,709 | 1,139,973 | 11,899,392 项 / 1,140,224 消息 | 0 / 0 |
| 功能长稳 | 300.018234 秒 | 62,128,408 | 5,951,640 | 62,131,096 项 / 5,951,896 消息 | 0 / 0 |

JFR 轮测量期 2,831,360 fills，排空另 2,683 项/12.201 ms，业务哈希 `80596660e5509ce8`；长稳 14,784,000 fills，排空另 2,688 项/11.114 ms，业务哈希 `59fdf5d7a64e5b31`。预热不并入上述测量计数。批量按业务 item 展开，成交数量单列，不能把业务项、Core 消息和 fills 混为同一种操作。

观测终态速率分别约 198,136 和 207,082 业务项/秒，Core 消息约 18,986 和 19,838/秒；这些是**带 profiler 且降频的诊断读数**，不是主吞吐成绩。窗口背压占比约 93.56%/93.60%，峰值 256；没有持续队列占用、有效计算与自旋分离或递增加载平台证明，不能宣布 Core 饱和。闭环发压没有恒定计划到达率或 coordinated omission 修正。

长稳数据实际执行：停机保留 Archive → 全量重放到 Leader → 资金/持仓/订单验证与只读状态哈希 → ClusterTool 请求实际快照（新增 2 条 valid 记录）→ 停机等待驱动心跳过期 → 快照重启 → 重复验证。两次业务与状态哈希均为 `59fdf5d7a64e5b31`，资金核对通过。全量重放、查询及快照一轮约 316 秒；快照恢复及查询一轮约 34 秒，均含编排/查询/停机，不能直接当作生产恢复 SLA。

## 分配和线程同步的实际证据

60 秒诊断仅在客户端给出的测量窗口内流式分析节点 JFR，ThreadAllocationStatistics 对同一个线程 ID 做首尾差分，按各线程可见约 58.89 秒区间估算速率，再除以业务终态速率。节点约 **230.84 MB/s（220.15 MiB/s）、1,165 B/业务项**；每 Core 消息约 12.16 KB，因为一条批量消息包含多项业务。边界及短命线程未完整捕获，不能当作精确总分配或对象数/op。

Owner 约 164.22 MB/s，Matcher 约 45.64 MB/s，Cluster 服务回调约 20.98 MB/s。加权分配采样热点为 `OrderRuntime`、`long[]`、`byte[]`、`MatcherResult`、`ResolvedPlaceOrder`、`Object[]` 和 `ReservationRuntime`；主要调用点为 `TradingRuntimeState.preparedOrder`、`OrderRuntime.snapshot`、`MatcherResult.from`、`CoreMessageFlyweightDecoder.decode`、`TradingCommandCodec.decodePlaceOrder`。最大观测 TLAB/非 TLAB 对象为 65,552 B 的 long[]；没有精确对象数量证据。

节点发生 45 次 G1 GC，`GCPhasePause` p50/p95/p99/max 为 5.237/5.873/7.975/7.975 ms。客户端 fork 在同窗 248 次 GC，最大 phase pause 1.884 ms，launcher 不承担业务负载；客户端分配不能写成服务端分配。GC 事件中的 TICKS 时长用 `RecordedEvent.getDuration()` 转换，避免把硬件计时单位误标为纳秒。各事件、最长 pause、TLAB、safepoint、编译明细见 JSON；没有由这些数据推算跨进程尾延迟因果。

账户结算工作线程未出现；Owner/Matcher 在该窗口没有记录到 ThreadPark、JavaMonitorEnter 或 JavaMonitorWait，线程启停也没有持续增长。两者单核 CPU 约 99%，包含 BUSY_SPIN；仍有 Matcher/Owner 交接、volatile/atomic 和 Cluster 服务回调，**不能称为无同步成本、零分配或已经达到完整单线程 LMAX**。本轮只验证当前 master，没有可比的优化前后采集，无法给出分配或同步成本下降百分比。

Owner 同窗另有 2 次 Java FileRead，合计约 0.0095 ms，栈来自只读 `encodeLaneMetrics` 的类加载，贴近测量边界；没有观察到交易业务数据库 I/O，但不能声称 Owner 所有 I/O 为零。Archive 写盘发生在 archive-conductor，native/mmap I/O 仍是 Java 事件的覆盖缺口。

## 长稳和未完成的性能验收

长稳功能计数覆盖完整 300 秒；node.jfr 因 256 MiB 上限滚动，只保留约 146 秒录制，测量内可见统计区间约 130 秒。更新后的汇总明确标为 `PARTIAL_MEASUREMENT`，即使 DataLoss=0 也不能视为完整 JFR。该尾部有 98 次 GC phase pause，最大 11.070 ms；GC 后 heap 首/末约 172.11/178.41 MiB，范围 170.76–178.89 MiB，驻留仍有增长，不能证明无泄漏。可见 direct buffer 为 8 个、9,575,136 B，NMT Other 在可见区间为 9,657,736 B，端点稳定也不能替代完整长期趋势或全部 native buffer 账目。

本轮未完成无 profiler 主轮、独立 GC profiler、稳定 JIT/热控环境、完整长稳 JFR、恒定到达/分段延迟、OS 上下文切换和 Core 饱和证明，也未压测 HTTP/Gateway/Kafka/WebSocket、资金费/强平/ADL 的独立压力或云服务器容量。六产品线测试通过不等于六条产品线都执行了真实 Cluster 压测。

后续应先解决压测机器散热及同机干扰，按同一配置重新锁定无 profiler/GC/JFR 独立轮次，并采用更合适的事件量或分段落盘确保完整长稳覆盖。继续优化分配应优先针对上述订单快照、解码对象和 MatcherResult 热点，保留资金、顺序、池化所有权及恢复回归，不依据过时的并行工作线程断言判断收益。

本轮进程和临时数据已清理：5094 个文件、12,375,454,068 逻辑 bytes，详见 JSON cleanup 字段。其他工作区部署改动未纳入本次提交。
