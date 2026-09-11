# 性能验证记录

本文件保留方案二（`experiment/lane-owned-state`，账户 Lane 分区并行）及其合入 `master` 后的测试记录。按用户要求清理方案二之前的老记录和方案一的独立测试结果；早期对照实验保留方案二数据及理解结果所需的共同参数、正确性说明和限制。

成功、失败、中止及环境无效轮次均保留，不仅保留最优结果。历史不同节点数、matcher 数、在途窗口和 profiler 配置不得混作同一容量口径；后续测试以用户最新要求和当次采集前锁定为准。

原始测试产物按当次清理说明处理；已删除路径仅表示历史来源，不作为可访问证据。后续记录继续按时间顺序追加，采集前锁定参数，采集后保留结果、资金核对和未验证范围。

## 2026-09-10 分区执行实验采集前锁定

experiment/lane-owned-state 基于 88bcbea8。订单簿分片独立派发结算，账户分区依赖保留：同一资金分区保持日志顺序；跨账户成交包含双方分区，账户状态与全仓共享语义不变。最终提交仍由有序协调器处理，当前没有宣称所有控制流程均已迁出 Owner。新增六产品真实成交阻塞分片测试、分区队列顺序/复用检查、随机盘口增删/重建的账户分区掩码检查；相关 reactor verify 通过。

失败诊断：初始测试币对前缀选择不改变分片路由造成测试进程 OOM，修改测试符号选择后消除；初始分片派发未保护物理账户 Lane，出现 incoming=11/applied=12 的确定性顺序拒绝，已补充包含双方账户的物理分区依赖并保留原断言，21 项定向检查通过。中间编译错误为拓扑构造接口不匹配，已通过显式传入恢复拓扑修正。所有失败轮次不作为性能结果。

预锁配置：本机 i9-9880H 8C16T/16 GiB/macOS26.7，HotSpot25.0.1/Maven3.9.16/G1/NMT summary。1 个命令会话+1 查询会话，global/session in-flight256、1769 users、256 symbols、batch20、4 account Lanes、2 matcher partitions，BLOCKING。真实3 JVM UDP日志复制，节点 512–768 MiB，SHARED_NETWORK/YIELDING；客户端128–512 MiB/SHARED。ClusterMixedCapacityMain seed112001、连续交易 stream=true、operational=false、30s预热/30s测量；无人为逐笔等待，最终排空。JFR profile64 MiB/dumponexit，与融合诊断相同。资金守恒、accepted=terminal、unfinished=0、三节点日志重放及快照重启 hash 一致为通过条件；不预设提升数字。硬件承载三份业务及客户端，不能据此宣称独立16C服务器扩展性。

JMH：ClusteredBatchTradingBenchmark.independentBatchWindows，六产品，2 matcher/4 Lane/batch20/maxInFlight256/realtime=false/interleavedMetrics=false/spinLimit0，wi1x1s/i1x2s/f1/t1/-prof gc；JFR profile32 MiB，每轮10240 business ops/512 Core messages，资金与快照由teardown核对。该项为分区派发归因，不与融合的不同 JMH 场景比较绝对分数。原始本轮产物 /tmp/architecture-lanes-*，分析完后清理。未覆盖长期泄漏/完整运营重负载，不作为上线容量验收。


## 2026-09-10 方案二诊断更新与重新采集锁定

双分片首轮 JMH 失败：批量订单在不同 matcher 共用控制证据 shard=-1，native sequence previous=8/next=2 触发严格校验，JSON无有效分数。立即停止尚处初始化阶段的双分片集群，不计为性能样本。修复为批量普通交易证据归实际撮合分片；保留每个原生引擎的严格native sequence校验；删除仅在批内重复校验且错误假定全批共用一个序列的 matcherTransition 容器。修复同步到两个分支，新添六产品双分片批量真实成交阻塞/恢复测试和跨订单簿控制证据测试。

后续v2采集锁定：沿用上轮所有机器/JDK/GC/JVM/JFR/用户/币对/会话/256在途/30s预热/30s测量配置；融合1 matcher（实际同线程执行），分区2 matcher/4 account Lanes。场景均ClusterMixedCapacityMain连续交易，seed112001、operational=false，真实三节点、终态资金核对及日志/快照重启。JMH分别沿用各分支已锁定的受影响场景，只作归因不交叉比较分数。新产物路径加 -v2 后缀；通过条件仍是资金、终态、恢复一致和无采样丢失；不宣称完整永久Lane所有权迁移或长期容量验收。

### 2026-09-10 方案二第二轮诊断结果与本机执行模式调整


用户最新要求：后续本机仅运行单节点 Aeron Cluster 开发部署，不再启动三节点；保留真实日志、Archive 和交易 Core，单节点结果不与三节点当成同一容量口径。


分支 experiment/lane-owned-state，基线 88bcbea8 + 当时未提交改动（后续修复不包含在本轮数据中）。

measurementStartEpochMillis=1789011475203

measurementEndEpochMillis=1789011505256

mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=233 businessHash=862f3e2d8b949a3

mixedCapacity=PASS elapsedSeconds=30.053 terminalBusinessOperations=2587904 offeredBusinessOperations=2587904 terminalCoreMessages=253184 offeredCoreMessages=253184 businessOpsPerSec=86110.855 coreMessagesPerSec=8424.536 fills=614400 fillsPerSec=20443.768 queries=0 unfinished=0 peakInFlight=256 measuredCycles=120 totalCycles=233 triggerExecutions=0

business=PLACE_ORDER items=61440 requests=61440 p50us=14721 p90us=33062 p95us=36372 p99us=41287 p999us=92602 maxus=100335

business=CANCEL_ORDER items=61440 requests=61440 p50us=17580 p90us=41320 p95us=45219 p99us=56754 p999us=177995 maxus=183500

business=APPLY_MARK_PRICE items=7424 requests=7424 p50us=24805 p90us=44695 p95us=51249 p99us=86900 p999us=90963 maxus=91226

business=PLACE_ORDER_BATCH items=1843200 requests=92160 p50us=41025 p90us=52690 p95us=57114 p99us=78512 p999us=154271 maxus=185073

business=CANCEL_ORDER_BATCH items=614400 requests=30720 p50us=37519 p90us=46661 p95us=51281 p99us=62488 p999us=102367 maxus=113049

replay: mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=233 businessHash=862f3e2d8b949a3

snapshot: mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=233 businessHash=862f3e2d8b949a3

node0: totals allocationMiBps=288.137 allocationBytes=9080007424 machineCPU=99.22 jvmCPU=25.05 heapMaxMiB=449.75 dataLoss=0 ownerIOEvents=0; threadCPU	91.897	trading-owner--1; threadCPU	89.613	/tmp/architecture-lanes-cluster-v2/node0/media-surprising-linear_perpetual-0 [sender,receiver]; threadCPU	30.937	archive-conductor; threadCPU	22.089	clustered-service-101-0; threadCPU	19.626	consensus-module-101-0; threadCPU	19.255	core-matcher-1; threadCPU	19.239	driver-conductor; threadCPU	19.164	core-matcher-0; threadCPU	16.655	core-account-lane-1; threadCPU	16.538	core-account-lane-3; threadCPU	16.531	core-account-lane-2; threadCPU	16.502	core-account-lane-0; threadCPU	5.689	JVMCI-native CompilerThread1; threadCPU	2.286	JVMCI-native CompilerThread0; threadCPU	1.322	aeron-md-nra; threadCPU	0.297	aeron-client; threadCPU	0.120	C1 CompilerThread0; threadCPU	0.099	JFR Periodic Tasks; threadCPU	0.089	JFR Recorder Thread; threadCPU	0.032	Service Thread; gc count=38 totalMs=407.276 p99Ms=11.931 maxMs=11.931; ownerInclusive	2046	java.lang.Thread.run; ownerInclusive	2046	java.lang.Thread.runWith; ownerInclusive	2046	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x0000000127128d98.run; ownerInclusive	2046	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner; ownerInclusive	2015	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands; ownerInclusive	2007	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope; ownerInclusive	1384	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix; ownerInclusive	1358	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching; ownerInclusive	1272	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand; ownerInclusive	1268	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress; ownerInclusive	755	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching; ownerInclusive	753	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands; allocationSite	458763168	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex; allocationSite	403327768	java.util.ArrayList.add; allocationSite	377470568	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand; allocationSite	347389360	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow; allocationSite	341557544	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get; allocationSite	340299296	java.util.HashMap.putVal; allocationSite	279534800	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence; allocationSite	269972104	java.nio.ByteBuffer.allocate; allocationSite	268301592	com.surprising.aeron.service.state.TradingRuntimeState.resolveFee; allocationSite	267230672	java.util.List.copyOf; allocationSite	265858240	org.agrona.collections.Long2ObjectHashMap.getMapped; allocationSite	253223792	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow; parkOrMonitorNs	934726739	core-account-lane-2; parkOrMonitorNs	933547791	core-account-lane-1; parkOrMonitorNs	907408727	core-account-lane-3; parkOrMonitorNs	864179907	core-account-lane-0; parkOrMonitorNs	425278887	aeron-md-nra; parkOrMonitorNs	373090467	core-matcher-1; parkOrMonitorNs	372359051	core-matcher-0; parkOrMonitorNs	11811374	driver-conductor; parkOrMonitorNs	10833261	trading-owner--1

NMT node0: Total: reserved=2357215KB, committed=728655KB

node1: totals allocationMiBps=292.167 allocationBytes=9207016272 machineCPU=99.22 jvmCPU=26.02 heapMaxMiB=449.55 dataLoss=0 ownerIOEvents=0; threadCPU	93.505	/tmp/architecture-lanes-cluster-v2/node1/media-surprising-linear_perpetual-1 [sender,receiver]; threadCPU	93.427	trading-owner--1; threadCPU	29.312	archive-conductor; threadCPU	25.881	driver-conductor; threadCPU	23.586	clustered-service-101-0; threadCPU	23.468	consensus-module-101-1; threadCPU	19.341	core-matcher-1; threadCPU	19.335	core-matcher-0; threadCPU	16.592	core-account-lane-2; threadCPU	16.581	core-account-lane-0; threadCPU	16.576	core-account-lane-1; threadCPU	16.469	core-account-lane-3; threadCPU	1.952	JVMCI-native CompilerThread0; threadCPU	1.287	aeron-md-nra; threadCPU	0.246	aeron-client; threadCPU	0.109	C1 CompilerThread0; threadCPU	0.095	JFR Periodic Tasks; threadCPU	0.093	JFR Recorder Thread; threadCPU	0.036	Service Thread; threadCPU	0.013	Monitor Deflation Thread; gc count=37 totalMs=404.909 p99Ms=17.836 maxMs=17.836; ownerInclusive	2205	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012b128b80.run; ownerInclusive	2205	java.lang.Thread.run; ownerInclusive	2205	java.lang.Thread.runWith; ownerInclusive	2205	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner; ownerInclusive	2190	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands; ownerInclusive	2173	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope; ownerInclusive	1444	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix; ownerInclusive	1416	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching; ownerInclusive	1379	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand; ownerInclusive	1376	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress; ownerInclusive	829	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching; ownerInclusive	817	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands; allocationSite	543926944	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex; allocationSite	511864448	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence; allocationSite	440784400	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand; allocationSite	408349920	java.util.HashMap.putVal; allocationSite	373203088	java.util.ArrayList.add; allocationSite	350292304	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow; allocationSite	347668584	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get; allocationSite	333168808	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow; allocationSite	285398744	java.lang.String.indexOfNonWhitespace; allocationSite	257005376	java.util.concurrent.ConcurrentHashMap.putVal; allocationSite	248476600	com.surprising.aeron.service.matching.CoreMatchingResult.classify; allocationSite	240736744	java.lang.ThreadLocal.get; parkOrMonitorNs	739901367	core-account-lane-2; parkOrMonitorNs	697345930	core-account-lane-1; parkOrMonitorNs	695780315	core-account-lane-3; parkOrMonitorNs	681414997	core-account-lane-0; parkOrMonitorNs	432016121	aeron-md-nra; parkOrMonitorNs	402107886	core-matcher-0; parkOrMonitorNs	401893775	core-matcher-1; parkOrMonitorNs	18672840	archive-conductor

NMT node1: Total: reserved=2356012KB, committed=730068KB

node2: totals allocationMiBps=264.185 allocationBytes=8325221312 machineCPU=99.22 jvmCPU=24.86 heapMaxMiB=440.75 dataLoss=0 ownerIOEvents=0; threadCPU	93.008	trading-owner--1; threadCPU	89.278	/tmp/architecture-lanes-cluster-v2/node2/media-surprising-linear_perpetual-2 [sender,receiver]; threadCPU	29.777	archive-conductor; threadCPU	21.515	clustered-service-101-0; threadCPU	19.454	consensus-module-101-2; threadCPU	18.584	core-matcher-1; threadCPU	18.578	core-matcher-0; threadCPU	18.379	driver-conductor; threadCPU	15.057	core-account-lane-2; threadCPU	15.038	core-account-lane-1; threadCPU	14.999	core-account-lane-0; threadCPU	14.950	core-account-lane-3; threadCPU	11.983	JVMCI-native CompilerThread1; threadCPU	5.678	JVMCI-native CompilerThread0; threadCPU	1.315	aeron-md-nra; threadCPU	0.267	aeron-client; threadCPU	0.171	C1 CompilerThread0; threadCPU	0.102	JFR Periodic Tasks; threadCPU	0.093	JFR Recorder Thread; threadCPU	0.034	Service Thread; gc count=42 totalMs=447.765 p99Ms=13.737 maxMs=13.737; ownerInclusive	2153	java.lang.Thread.run; ownerInclusive	2153	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x0000000128128b80.run; ownerInclusive	2153	java.lang.Thread.runWith; ownerInclusive	2153	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner; ownerInclusive	2087	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands; ownerInclusive	2078	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope; ownerInclusive	1893	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand; ownerInclusive	1893	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress; ownerInclusive	1312	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix; ownerInclusive	1293	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching; ownerInclusive	769	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching; ownerInclusive	626	com.surprising.aeron.service.execution.SurprisingClusteredService.awaitIngressCapacity; allocationSite	489677336	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex; allocationSite	419937488	java.util.HashMap.putVal; allocationSite	417242008	java.util.ArrayList.add; allocationSite	321617936	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get; allocationSite	314353040	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand; allocationSite	306786856	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow; allocationSite	284897544	java.util.List.copyOf; allocationSite	277482304	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence; allocationSite	276290832	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow; allocationSite	247456384	java.nio.ByteBuffer.allocate; allocationSite	207072000	com.surprising.aeron.service.matching.CoreMatchingResult.classify; allocationSite	202937624	org.agrona.collections.Long2ObjectHashMap.getMapped; parkOrMonitorNs	915397437	core-account-lane-2; parkOrMonitorNs	902677143	core-account-lane-1; parkOrMonitorNs	888669431	core-account-lane-0; parkOrMonitorNs	883916443	core-account-lane-3; parkOrMonitorNs	460989401	aeron-md-nra; parkOrMonitorNs	416549994	core-matcher-0; parkOrMonitorNs	350787333	core-matcher-1; parkOrMonitorNs	13036106	consensus-module-101-2; parkOrMonitorNs	12711351	driver-conductor; parkOrMonitorNs	12563290	archive-conductor

NMT node2: Total: reserved=2352124KB, committed=723724KB

本轮 Archive、JFR、负载与恢复原始日志已分析并清理；/tmp/architecture-lanes-cluster-v2 不再作为可访问证据。


lanes JMH：六产品功能路径诊断，场景不同，不作跨方案吞吐比较。

{"benchmark": "com.surprising.aeron.service.execution.ClusteredBatchTradingBenchmark.independentBatchWindows", "params": {"accountLanes": "4", "batchSize": "20", "interleavedMetrics": "false", "matchingEngines": "2", "maxInFlight": "256", "productLine": "SPOT", "realtime": "false", "settlementSpinLimit": "0"}, "primaryMetric": {"score": 12.326672549421577, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 12.326672549421577, "50.0": 12.326672549421577, "90.0": 12.326672549421577, "95.0": 12.326672549421577, "99.0": 12.326672549421577, "99.9": 12.326672549421577, "99.99": 12.326672549421577, "99.999": 12.326672549421577, "99.9999": 12.326672549421577, "100.0": 12.326672549421577}, "scoreUnit": "ops/s", "rawData": [[12.326672549421577]]}, "secondaryMetrics": {"acceptedBusinessOperations": {"score": 256000.0, "scoreError": "NaN", "scoreConfidence": [256000.0, 256000.0], "scorePercentiles": {"0.0": 256000.0, "50.0": 256000.0, "90.0": 256000.0, "95.0": 256000.0, "99.0": 256000.0, "99.9": 256000.0, "99.99": 256000.0, "99.999": 256000.0, "99.9999": 256000.0, "100.0": 256000.0}, "scoreUnit": "#", "rawData": [[256000.0]]}, "acceptedCoreMessages": {"score": 12800.0, "scoreError": "NaN", "scoreConfidence": [12800.0, 12800.0], "scorePercentiles": {"0.0": 12800.0, "50.0": 12800.0, "90.0": 12800.0, "95.0": 12800.0, "99.0": 12800.0, "99.9": 12800.0, "99.99": 12800.0, "99.999": 12800.0, "99.9999": 12800.0, "100.0": 12800.0}, "scoreUnit": "#", "rawData": [[12800.0]]}, "gc.alloc.rate": {"score": 487.271274300092, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 487.271274300092, "50.0": 487.271274300092, "90.0": 487.271274300092, "95.0": 487.271274300092, "99.0": 487.271274300092, "99.9": 487.271274300092, "99.99": 487.271274300092, "99.999": 487.271274300092, "99.9999": 487.271274300092, "100.0": 487.271274300092}, "scoreUnit": "MB/sec", "rawData": [[487.271274300092]]}, "gc.alloc.rate.norm": {"score": 45125359.04, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 45125359.04, "50.0": 45125359.04, "90.0": 45125359.04, "95.0": 45125359.04, "99.0": 45125359.04, "99.9": 45125359.04, "99.99": 45125359.04, "99.999": 45125359.04, "99.9999": 45125359.04, "100.0": 45125359.04}, "scoreUnit": "B/op", "rawData": [[45125359.04]]}, "gc.count": {"score": 15.0, "scoreError": "NaN", "scoreConfidence": [15.0, 15.0], "scorePercentiles": {"0.0": 15.0, "50.0": 15.0, "90.0": 15.0, "95.0": 15.0, "99.0": 15.0, "99.9": 15.0, "99.99": 15.0, "99.999": 15.0, "99.9999": 15.0, "100.0": 15.0}, "scoreUnit": "counts", "rawData": [[15.0]]}, "gc.time": {"score": 72.0, "scoreError": "NaN", "scoreConfidence": [72.0, 72.0], "scorePercentiles": {"0.0": 72.0, "50.0": 72.0, "90.0": 72.0, "95.0": 72.0, "99.0": 72.0, "99.9": 72.0, "99.99": 72.0, "99.999": 72.0, "99.9999": 72.0, "100.0": 72.0}, "scoreUnit": "ms", "rawData": [[72.0]]}, "queries": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "rejectedBusinessOperations": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBatches": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBusinessOperations": {"score": 256000.0, "scoreError": "NaN", "scoreConfidence": [256000.0, 256000.0], "scorePercentiles": {"0.0": 256000.0, "50.0": 256000.0, "90.0": 256000.0, "95.0": 256000.0, "99.0": 256000.0, "99.9": 256000.0, "99.99": 256000.0, "99.999": 256000.0, "99.9999": 256000.0, "100.0": 256000.0}, "scoreUnit": "#", "rawData": [[256000.0]]}, "terminalCoreMessages": {"score": 12800.0, "scoreError": "NaN", "scoreConfidence": [12800.0, 12800.0], "scorePercentiles": {"0.0": 12800.0, "50.0": 12800.0, "90.0": 12800.0, "95.0": 12800.0, "99.0": 12800.0, "99.9": 12800.0, "99.99": 12800.0, "99.999": 12800.0, "99.9999": 12800.0, "100.0": 12800.0}, "scoreUnit": "#", "rawData": [[12800.0]]}, "terminalItems": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalTrades": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}}}

{"benchmark": "com.surprising.aeron.service.execution.ClusteredBatchTradingBenchmark.independentBatchWindows", "params": {"accountLanes": "4", "batchSize": "20", "interleavedMetrics": "false", "matchingEngines": "2", "maxInFlight": "256", "productLine": "INVERSE_DELIVERY", "realtime": "false", "settlementSpinLimit": "0"}, "primaryMetric": {"score": 13.775757726685612, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 13.775757726685612, "50.0": 13.775757726685612, "90.0": 13.775757726685612, "95.0": 13.775757726685612, "99.0": 13.775757726685612, "99.9": 13.775757726685612, "99.99": 13.775757726685612, "99.999": 13.775757726685612, "99.9999": 13.775757726685612, "100.0": 13.775757726685612}, "scoreUnit": "ops/s", "rawData": [[13.775757726685612]]}, "secondaryMetrics": {"acceptedBusinessOperations": {"score": 286720.0, "scoreError": "NaN", "scoreConfidence": [286720.0, 286720.0], "scorePercentiles": {"0.0": 286720.0, "50.0": 286720.0, "90.0": 286720.0, "95.0": 286720.0, "99.0": 286720.0, "99.9": 286720.0, "99.99": 286720.0, "99.999": 286720.0, "99.9999": 286720.0, "100.0": 286720.0}, "scoreUnit": "#", "rawData": [[286720.0]]}, "acceptedCoreMessages": {"score": 14336.0, "scoreError": "NaN", "scoreConfidence": [14336.0, 14336.0], "scorePercentiles": {"0.0": 14336.0, "50.0": 14336.0, "90.0": 14336.0, "95.0": 14336.0, "99.0": 14336.0, "99.9": 14336.0, "99.99": 14336.0, "99.999": 14336.0, "99.9999": 14336.0, "100.0": 14336.0}, "scoreUnit": "#", "rawData": [[14336.0]]}, "gc.alloc.rate": {"score": 540.0819399926066, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 540.0819399926066, "50.0": 540.0819399926066, "90.0": 540.0819399926066, "95.0": 540.0819399926066, "99.0": 540.0819399926066, "99.9": 540.0819399926066, "99.99": 540.0819399926066, "99.999": 540.0819399926066, "99.9999": 540.0819399926066, "100.0": 540.0819399926066}, "scoreUnit": "MB/sec", "rawData": [[540.0819399926066]]}, "gc.alloc.rate.norm": {"score": 44780630.0, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 44780630.0, "50.0": 44780630.0, "90.0": 44780630.0, "95.0": 44780630.0, "99.0": 44780630.0, "99.9": 44780630.0, "99.99": 44780630.0, "99.999": 44780630.0, "99.9999": 44780630.0, "100.0": 44780630.0}, "scoreUnit": "B/op", "rawData": [[44780630.0]]}, "gc.count": {"score": 16.0, "scoreError": "NaN", "scoreConfidence": [16.0, 16.0], "scorePercentiles": {"0.0": 16.0, "50.0": 16.0, "90.0": 16.0, "95.0": 16.0, "99.0": 16.0, "99.9": 16.0, "99.99": 16.0, "99.999": 16.0, "99.9999": 16.0, "100.0": 16.0}, "scoreUnit": "counts", "rawData": [[16.0]]}, "gc.time": {"score": 78.0, "scoreError": "NaN", "scoreConfidence": [78.0, 78.0], "scorePercentiles": {"0.0": 78.0, "50.0": 78.0, "90.0": 78.0, "95.0": 78.0, "99.0": 78.0, "99.9": 78.0, "99.99": 78.0, "99.999": 78.0, "99.9999": 78.0, "100.0": 78.0}, "scoreUnit": "ms", "rawData": [[78.0]]}, "queries": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "rejectedBusinessOperations": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBatches": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBusinessOperations": {"score": 286720.0, "scoreError": "NaN", "scoreConfidence": [286720.0, 286720.0], "scorePercentiles": {"0.0": 286720.0, "50.0": 286720.0, "90.0": 286720.0, "95.0": 286720.0, "99.0": 286720.0, "99.9": 286720.0, "99.99": 286720.0, "99.999": 286720.0, "99.9999": 286720.0, "100.0": 286720.0}, "scoreUnit": "#", "rawData": [[286720.0]]}, "terminalCoreMessages": {"score": 14336.0, "scoreError": "NaN", "scoreConfidence": [14336.0, 14336.0], "scorePercentiles": {"0.0": 14336.0, "50.0": 14336.0, "90.0": 14336.0, "95.0": 14336.0, "99.0": 14336.0, "99.9": 14336.0, "99.99": 14336.0, "99.999": 14336.0, "99.9999": 14336.0, "100.0": 14336.0}, "scoreUnit": "#", "rawData": [[14336.0]]}, "terminalItems": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalTrades": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}}}

{"benchmark": "com.surprising.aeron.service.execution.ClusteredBatchTradingBenchmark.independentBatchWindows", "params": {"accountLanes": "4", "batchSize": "20", "interleavedMetrics": "false", "matchingEngines": "2", "maxInFlight": "256", "productLine": "LINEAR_PERPETUAL", "realtime": "false", "settlementSpinLimit": "0"}, "primaryMetric": {"score": 13.181382592378794, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 13.181382592378794, "50.0": 13.181382592378794, "90.0": 13.181382592378794, "95.0": 13.181382592378794, "99.0": 13.181382592378794, "99.9": 13.181382592378794, "99.99": 13.181382592378794, "99.999": 13.181382592378794, "99.9999": 13.181382592378794, "100.0": 13.181382592378794}, "scoreUnit": "ops/s", "rawData": [[13.181382592378794]]}, "secondaryMetrics": {"acceptedBusinessOperations": {"score": 276480.0, "scoreError": "NaN", "scoreConfidence": [276480.0, 276480.0], "scorePercentiles": {"0.0": 276480.0, "50.0": 276480.0, "90.0": 276480.0, "95.0": 276480.0, "99.0": 276480.0, "99.9": 276480.0, "99.99": 276480.0, "99.999": 276480.0, "99.9999": 276480.0, "100.0": 276480.0}, "scoreUnit": "#", "rawData": [[276480.0]]}, "acceptedCoreMessages": {"score": 13824.0, "scoreError": "NaN", "scoreConfidence": [13824.0, 13824.0], "scorePercentiles": {"0.0": 13824.0, "50.0": 13824.0, "90.0": 13824.0, "95.0": 13824.0, "99.0": 13824.0, "99.9": 13824.0, "99.99": 13824.0, "99.999": 13824.0, "99.9999": 13824.0, "100.0": 13824.0}, "scoreUnit": "#", "rawData": [[13824.0]]}, "gc.alloc.rate": {"score": 515.422793631883, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 515.422793631883, "50.0": 515.422793631883, "90.0": 515.422793631883, "95.0": 515.422793631883, "99.0": 515.422793631883, "99.9": 515.422793631883, "99.99": 515.422793631883, "99.999": 515.422793631883, "99.9999": 515.422793631883, "100.0": 515.422793631883}, "scoreUnit": "MB/sec", "rawData": [[515.422793631883]]}, "gc.alloc.rate.norm": {"score": 44806290.074074075, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 44806290.074074075, "50.0": 44806290.074074075, "90.0": 44806290.074074075, "95.0": 44806290.074074075, "99.0": 44806290.074074075, "99.9": 44806290.074074075, "99.99": 44806290.074074075, "99.999": 44806290.074074075, "99.9999": 44806290.074074075, "100.0": 44806290.074074075}, "scoreUnit": "B/op", "rawData": [[44806290.074074075]]}, "gc.count": {"score": 16.0, "scoreError": "NaN", "scoreConfidence": [16.0, 16.0], "scorePercentiles": {"0.0": 16.0, "50.0": 16.0, "90.0": 16.0, "95.0": 16.0, "99.0": 16.0, "99.9": 16.0, "99.99": 16.0, "99.999": 16.0, "99.9999": 16.0, "100.0": 16.0}, "scoreUnit": "counts", "rawData": [[16.0]]}, "gc.time": {"score": 79.0, "scoreError": "NaN", "scoreConfidence": [79.0, 79.0], "scorePercentiles": {"0.0": 79.0, "50.0": 79.0, "90.0": 79.0, "95.0": 79.0, "99.0": 79.0, "99.9": 79.0, "99.99": 79.0, "99.999": 79.0, "99.9999": 79.0, "100.0": 79.0}, "scoreUnit": "ms", "rawData": [[79.0]]}, "queries": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "rejectedBusinessOperations": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBatches": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBusinessOperations": {"score": 276480.0, "scoreError": "NaN", "scoreConfidence": [276480.0, 276480.0], "scorePercentiles": {"0.0": 276480.0, "50.0": 276480.0, "90.0": 276480.0, "95.0": 276480.0, "99.0": 276480.0, "99.9": 276480.0, "99.99": 276480.0, "99.999": 276480.0, "99.9999": 276480.0, "100.0": 276480.0}, "scoreUnit": "#", "rawData": [[276480.0]]}, "terminalCoreMessages": {"score": 13824.0, "scoreError": "NaN", "scoreConfidence": [13824.0, 13824.0], "scorePercentiles": {"0.0": 13824.0, "50.0": 13824.0, "90.0": 13824.0, "95.0": 13824.0, "99.0": 13824.0, "99.9": 13824.0, "99.99": 13824.0, "99.999": 13824.0, "99.9999": 13824.0, "100.0": 13824.0}, "scoreUnit": "#", "rawData": [[13824.0]]}, "terminalItems": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalTrades": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}}}

{"benchmark": "com.surprising.aeron.service.execution.ClusteredBatchTradingBenchmark.independentBatchWindows", "params": {"accountLanes": "4", "batchSize": "20", "interleavedMetrics": "false", "matchingEngines": "2", "maxInFlight": "256", "productLine": "INVERSE_PERPETUAL", "realtime": "false", "settlementSpinLimit": "0"}, "primaryMetric": {"score": 13.217948475996243, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 13.217948475996243, "50.0": 13.217948475996243, "90.0": 13.217948475996243, "95.0": 13.217948475996243, "99.0": 13.217948475996243, "99.9": 13.217948475996243, "99.99": 13.217948475996243, "99.999": 13.217948475996243, "99.9999": 13.217948475996243, "100.0": 13.217948475996243}, "scoreUnit": "ops/s", "rawData": [[13.217948475996243]]}, "secondaryMetrics": {"acceptedBusinessOperations": {"score": 276480.0, "scoreError": "NaN", "scoreConfidence": [276480.0, 276480.0], "scorePercentiles": {"0.0": 276480.0, "50.0": 276480.0, "90.0": 276480.0, "95.0": 276480.0, "99.0": 276480.0, "99.9": 276480.0, "99.99": 276480.0, "99.999": 276480.0, "99.9999": 276480.0, "100.0": 276480.0}, "scoreUnit": "#", "rawData": [[276480.0]]}, "acceptedCoreMessages": {"score": 13824.0, "scoreError": "NaN", "scoreConfidence": [13824.0, 13824.0], "scorePercentiles": {"0.0": 13824.0, "50.0": 13824.0, "90.0": 13824.0, "95.0": 13824.0, "99.0": 13824.0, "99.9": 13824.0, "99.99": 13824.0, "99.999": 13824.0, "99.9999": 13824.0, "100.0": 13824.0}, "scoreUnit": "#", "rawData": [[13824.0]]}, "gc.alloc.rate": {"score": 520.560111429451, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 520.560111429451, "50.0": 520.560111429451, "90.0": 520.560111429451, "95.0": 520.560111429451, "99.0": 520.560111429451, "99.9": 520.560111429451, "99.99": 520.560111429451, "99.999": 520.560111429451, "99.9999": 520.560111429451, "100.0": 520.560111429451}, "scoreUnit": "MB/sec", "rawData": [[520.560111429451]]}, "gc.alloc.rate.norm": {"score": 44726586.37037037, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 44726586.37037037, "50.0": 44726586.37037037, "90.0": 44726586.37037037, "95.0": 44726586.37037037, "99.0": 44726586.37037037, "99.9": 44726586.37037037, "99.99": 44726586.37037037, "99.999": 44726586.37037037, "99.9999": 44726586.37037037, "100.0": 44726586.37037037}, "scoreUnit": "B/op", "rawData": [[44726586.37037037]]}, "gc.count": {"score": 16.0, "scoreError": "NaN", "scoreConfidence": [16.0, 16.0], "scorePercentiles": {"0.0": 16.0, "50.0": 16.0, "90.0": 16.0, "95.0": 16.0, "99.0": 16.0, "99.9": 16.0, "99.99": 16.0, "99.999": 16.0, "99.9999": 16.0, "100.0": 16.0}, "scoreUnit": "counts", "rawData": [[16.0]]}, "gc.time": {"score": 76.0, "scoreError": "NaN", "scoreConfidence": [76.0, 76.0], "scorePercentiles": {"0.0": 76.0, "50.0": 76.0, "90.0": 76.0, "95.0": 76.0, "99.0": 76.0, "99.9": 76.0, "99.99": 76.0, "99.999": 76.0, "99.9999": 76.0, "100.0": 76.0}, "scoreUnit": "ms", "rawData": [[76.0]]}, "queries": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "rejectedBusinessOperations": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBatches": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBusinessOperations": {"score": 276480.0, "scoreError": "NaN", "scoreConfidence": [276480.0, 276480.0], "scorePercentiles": {"0.0": 276480.0, "50.0": 276480.0, "90.0": 276480.0, "95.0": 276480.0, "99.0": 276480.0, "99.9": 276480.0, "99.99": 276480.0, "99.999": 276480.0, "99.9999": 276480.0, "100.0": 276480.0}, "scoreUnit": "#", "rawData": [[276480.0]]}, "terminalCoreMessages": {"score": 13824.0, "scoreError": "NaN", "scoreConfidence": [13824.0, 13824.0], "scorePercentiles": {"0.0": 13824.0, "50.0": 13824.0, "90.0": 13824.0, "95.0": 13824.0, "99.0": 13824.0, "99.9": 13824.0, "99.99": 13824.0, "99.999": 13824.0, "99.9999": 13824.0, "100.0": 13824.0}, "scoreUnit": "#", "rawData": [[13824.0]]}, "terminalItems": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalTrades": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}}}

{"benchmark": "com.surprising.aeron.service.execution.ClusteredBatchTradingBenchmark.independentBatchWindows", "params": {"accountLanes": "4", "batchSize": "20", "interleavedMetrics": "false", "matchingEngines": "2", "maxInFlight": "256", "productLine": "OPTION", "realtime": "false", "settlementSpinLimit": "0"}, "primaryMetric": {"score": 11.868492950074884, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 11.868492950074884, "50.0": 11.868492950074884, "90.0": 11.868492950074884, "95.0": 11.868492950074884, "99.0": 11.868492950074884, "99.9": 11.868492950074884, "99.99": 11.868492950074884, "99.999": 11.868492950074884, "99.9999": 11.868492950074884, "100.0": 11.868492950074884}, "scoreUnit": "ops/s", "rawData": [[11.868492950074884]]}, "secondaryMetrics": {"acceptedBusinessOperations": {"score": 245760.0, "scoreError": "NaN", "scoreConfidence": [245760.0, 245760.0], "scorePercentiles": {"0.0": 245760.0, "50.0": 245760.0, "90.0": 245760.0, "95.0": 245760.0, "99.0": 245760.0, "99.9": 245760.0, "99.99": 245760.0, "99.999": 245760.0, "99.9999": 245760.0, "100.0": 245760.0}, "scoreUnit": "#", "rawData": [[245760.0]]}, "acceptedCoreMessages": {"score": 12288.0, "scoreError": "NaN", "scoreConfidence": [12288.0, 12288.0], "scorePercentiles": {"0.0": 12288.0, "50.0": 12288.0, "90.0": 12288.0, "95.0": 12288.0, "99.0": 12288.0, "99.9": 12288.0, "99.99": 12288.0, "99.999": 12288.0, "99.9999": 12288.0, "100.0": 12288.0}, "scoreUnit": "#", "rawData": [[12288.0]]}, "gc.alloc.rate": {"score": 470.61456095712, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 470.61456095712, "50.0": 470.61456095712, "90.0": 470.61456095712, "95.0": 470.61456095712, "99.0": 470.61456095712, "99.9": 470.61456095712, "99.99": 470.61456095712, "99.999": 470.61456095712, "99.9999": 470.61456095712, "100.0": 470.61456095712}, "scoreUnit": "MB/sec", "rawData": [[470.61456095712]]}, "gc.alloc.rate.norm": {"score": 45468515.333333336, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 45468515.333333336, "50.0": 45468515.333333336, "90.0": 45468515.333333336, "95.0": 45468515.333333336, "99.0": 45468515.333333336, "99.9": 45468515.333333336, "99.99": 45468515.333333336, "99.999": 45468515.333333336, "99.9999": 45468515.333333336, "100.0": 45468515.333333336}, "scoreUnit": "B/op", "rawData": [[45468515.333333336]]}, "gc.count": {"score": 15.0, "scoreError": "NaN", "scoreConfidence": [15.0, 15.0], "scorePercentiles": {"0.0": 15.0, "50.0": 15.0, "90.0": 15.0, "95.0": 15.0, "99.0": 15.0, "99.9": 15.0, "99.99": 15.0, "99.999": 15.0, "99.9999": 15.0, "100.0": 15.0}, "scoreUnit": "counts", "rawData": [[15.0]]}, "gc.time": {"score": 70.0, "scoreError": "NaN", "scoreConfidence": [70.0, 70.0], "scorePercentiles": {"0.0": 70.0, "50.0": 70.0, "90.0": 70.0, "95.0": 70.0, "99.0": 70.0, "99.9": 70.0, "99.99": 70.0, "99.999": 70.0, "99.9999": 70.0, "100.0": 70.0}, "scoreUnit": "ms", "rawData": [[70.0]]}, "queries": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "rejectedBusinessOperations": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBatches": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBusinessOperations": {"score": 245760.0, "scoreError": "NaN", "scoreConfidence": [245760.0, 245760.0], "scorePercentiles": {"0.0": 245760.0, "50.0": 245760.0, "90.0": 245760.0, "95.0": 245760.0, "99.0": 245760.0, "99.9": 245760.0, "99.99": 245760.0, "99.999": 245760.0, "99.9999": 245760.0, "100.0": 245760.0}, "scoreUnit": "#", "rawData": [[245760.0]]}, "terminalCoreMessages": {"score": 12288.0, "scoreError": "NaN", "scoreConfidence": [12288.0, 12288.0], "scorePercentiles": {"0.0": 12288.0, "50.0": 12288.0, "90.0": 12288.0, "95.0": 12288.0, "99.0": 12288.0, "99.9": 12288.0, "99.99": 12288.0, "99.999": 12288.0, "99.9999": 12288.0, "100.0": 12288.0}, "scoreUnit": "#", "rawData": [[12288.0]]}, "terminalItems": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalTrades": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}}}

{"benchmark": "com.surprising.aeron.service.execution.ClusteredBatchTradingBenchmark.independentBatchWindows", "params": {"accountLanes": "4", "batchSize": "20", "interleavedMetrics": "false", "matchingEngines": "2", "maxInFlight": "256", "productLine": "LINEAR_DELIVERY", "realtime": "false", "settlementSpinLimit": "0"}, "primaryMetric": {"score": 13.301622953924959, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 13.301622953924959, "50.0": 13.301622953924959, "90.0": 13.301622953924959, "95.0": 13.301622953924959, "99.0": 13.301622953924959, "99.9": 13.301622953924959, "99.99": 13.301622953924959, "99.999": 13.301622953924959, "99.9999": 13.301622953924959, "100.0": 13.301622953924959}, "scoreUnit": "ops/s", "rawData": [[13.301622953924959]]}, "secondaryMetrics": {"acceptedBusinessOperations": {"score": 276480.0, "scoreError": "NaN", "scoreConfidence": [276480.0, 276480.0], "scorePercentiles": {"0.0": 276480.0, "50.0": 276480.0, "90.0": 276480.0, "95.0": 276480.0, "99.0": 276480.0, "99.9": 276480.0, "99.99": 276480.0, "99.999": 276480.0, "99.9999": 276480.0, "100.0": 276480.0}, "scoreUnit": "#", "rawData": [[276480.0]]}, "acceptedCoreMessages": {"score": 13824.0, "scoreError": "NaN", "scoreConfidence": [13824.0, 13824.0], "scorePercentiles": {"0.0": 13824.0, "50.0": 13824.0, "90.0": 13824.0, "95.0": 13824.0, "99.0": 13824.0, "99.9": 13824.0, "99.99": 13824.0, "99.999": 13824.0, "99.9999": 13824.0, "100.0": 13824.0}, "scoreUnit": "#", "rawData": [[13824.0]]}, "gc.alloc.rate": {"score": 524.7772006907213, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 524.7772006907213, "50.0": 524.7772006907213, "90.0": 524.7772006907213, "95.0": 524.7772006907213, "99.0": 524.7772006907213, "99.9": 524.7772006907213, "99.99": 524.7772006907213, "99.999": 524.7772006907213, "99.9999": 524.7772006907213, "100.0": 524.7772006907213}, "scoreUnit": "MB/sec", "rawData": [[524.7772006907213]]}, "gc.alloc.rate.norm": {"score": 44896683.55555555, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 44896683.55555555, "50.0": 44896683.55555555, "90.0": 44896683.55555555, "95.0": 44896683.55555555, "99.0": 44896683.55555555, "99.9": 44896683.55555555, "99.99": 44896683.55555555, "99.999": 44896683.55555555, "99.9999": 44896683.55555555, "100.0": 44896683.55555555}, "scoreUnit": "B/op", "rawData": [[44896683.55555555]]}, "gc.count": {"score": 15.0, "scoreError": "NaN", "scoreConfidence": [15.0, 15.0], "scorePercentiles": {"0.0": 15.0, "50.0": 15.0, "90.0": 15.0, "95.0": 15.0, "99.0": 15.0, "99.9": 15.0, "99.99": 15.0, "99.999": 15.0, "99.9999": 15.0, "100.0": 15.0}, "scoreUnit": "counts", "rawData": [[15.0]]}, "gc.time": {"score": 74.0, "scoreError": "NaN", "scoreConfidence": [74.0, 74.0], "scorePercentiles": {"0.0": 74.0, "50.0": 74.0, "90.0": 74.0, "95.0": 74.0, "99.0": 74.0, "99.9": 74.0, "99.99": 74.0, "99.999": 74.0, "99.9999": 74.0, "100.0": 74.0}, "scoreUnit": "ms", "rawData": [[74.0]]}, "queries": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "rejectedBusinessOperations": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBatches": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalBusinessOperations": {"score": 276480.0, "scoreError": "NaN", "scoreConfidence": [276480.0, 276480.0], "scorePercentiles": {"0.0": 276480.0, "50.0": 276480.0, "90.0": 276480.0, "95.0": 276480.0, "99.0": 276480.0, "99.9": 276480.0, "99.99": 276480.0, "99.999": 276480.0, "99.9999": 276480.0, "100.0": 276480.0}, "scoreUnit": "#", "rawData": [[276480.0]]}, "terminalCoreMessages": {"score": 13824.0, "scoreError": "NaN", "scoreConfidence": [13824.0, 13824.0], "scorePercentiles": {"0.0": 13824.0, "50.0": 13824.0, "90.0": 13824.0, "95.0": 13824.0, "99.0": 13824.0, "99.9": 13824.0, "99.99": 13824.0, "99.999": 13824.0, "99.9999": 13824.0, "100.0": 13824.0}, "scoreUnit": "#", "rawData": [[13824.0]]}, "terminalItems": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}, "terminalTrades": {"score": 0.0, "scoreError": "NaN", "scoreConfidence": [0.0, 0.0], "scorePercentiles": {"0.0": 0.0, "50.0": 0.0, "90.0": 0.0, "95.0": 0.0, "99.0": 0.0, "99.9": 0.0, "99.99": 0.0, "99.999": 0.0, "99.9999": 0.0, "100.0": 0.0}, "scoreUnit": "#", "rawData": [[0.0]]}}}

JMH 原始 JFR、JSON 与日志已分析并清理。


JMH 原始 JFR、JSON 与日志已分析并清理。


本轮限制：本机 8C16T 共用资源；有 profiler；仅短窗口；没有长稳泄漏结论、没有证明 matcher/Lane 计算饱和、没有三段延迟与完整运营混合覆盖。测试通过不等于第二方案完整验收。新加共享资金测试首次误把 direct apply 的 accepted 当作 terminal；随后真实复现未创建订单簿的路由在恢复后误判已注册，及原生拒单走同步账户操作。恢复续单对比还修正了测试使用不同 clusterPosition 的问题，未放宽资金/hash 断言。


### 2026-09-10 单成员开发部署功能验证

用户要求本机不再启动三节点。服务端、协议地址生成和客户端现支持显式单成员配置：`-Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.node-id=0`。生产三成员默认配置保持原值。

被测为 experiment/lane-owned-state 未提交工作区 full7 构建，service JAR SHA-256 70bbca55d5645230c04c1ceeead671bed4dbb47d494141ec7971dc3cf3bade9a；不包含其后新增的异步逐仓保证金调整。HotSpot 25 / G1，节点 256–768 MiB，客户端 64–256 MiB，2 matcher / 4 Account Lane，SHARED_NETWORK / YIELDING，账户 BLOCKING / spin=0。六产品依次启动，每次仅一个真实 Cluster 成员及一个客户端；真实 UDP、Archive 日志、强制终止后的日志重放、Cluster 快照重启均保留。执行命令入口为临时 run_single_gates.py，业务入口 ClusterProductLineGateMain，seed=9701。

- SPOT: execute / crash-replay / snapshot-restart PASS, fundsDiff=0, snapshotPosition=3616
- LINEAR_PERPETUAL: execute / crash-replay / snapshot-restart PASS, fundsDiff=0, snapshotPosition=9568
- INVERSE_PERPETUAL: execute / crash-replay / snapshot-restart PASS, fundsDiff=0, snapshotPosition=9568
- LINEAR_DELIVERY: execute / crash-replay / snapshot-restart PASS, fundsDiff=0, snapshotPosition=5184
- INVERSE_DELIVERY: execute / crash-replay / snapshot-restart PASS, fundsDiff=0, snapshotPosition=5184
- OPTION: execute / crash-replay / snapshot-restart PASS, fundsDiff=0, snapshotPosition=5184

异常：首次启动缺少 JDK 25 java.util.zip opens；首次强杀后立即重启遇到 Archive 存活标记，补齐 JVM opens 并保留日志等待标记过期后重试成功。12 秒等待仅用于故障恢复启动，不属于发压逻辑。本轮是功能验证，无吞吐/JMH/JFR/三节点容错/长期泄漏结论，不代表方案二完整验收。全部节点与客户端已退出；本轮三个临时集群目录、日志与 Archive 原始产物已分析并清理，不再作为可访问证据。


### 2026-09-10 单成员方案二（原对照实验）：采集前锁定

- 两个实验分支均以 88bcbea8 为基础，含未提交改动；共同业务修复已同步，架构差异保留。仅本机一个真实 Aeron Cluster 成员，依次运行 A/FUSED、B/PIPELINED，禁止同机并行压测或同时构建。
- HotSpot 25.0.1 / G1，macOS 26.7，i9-9880H 8C16T / 16 GiB。节点 Xms512m/Xmx1536m、客户端 Xms128m/Xmx512m；SHARED_NETWORK、service YIELDING；账户 BUSY_SPIN、spin-limit=0；2 matcher、4 逻辑 Account Lane（A 单线程内联，B 永久 Lane 线程）。
- LINEAR_PERPETUAL，ClusterMixedCapacityMain 连续 FIFO 异步生产：256 symbol、1000 retail + 768 maker/taker 辅助账户 + 1 treasury fixture account；1 command session + 1 reserved query session，global/session in-flight 均256；批量20项，持续报价/批撤/普通单/IOC成交/撤余单，周期之间不排空，仅预热/测量边界排空。每循环每 symbol 有20卖报价+20撤单+1卖挂单+20买IOC+1撤余单+1买挂单+1撤单+20卖IOC；报价刷新额外计 Core 命令。初始各账户资金1,000,000,000单位，沿用真实负载核账。
- 主吞吐无 profiler：每方案2轮，预热30秒、测量60秒、结束排空与核账，不跨方案复用数据。独立诊断轮：每方案真实外部 Cluster JMH SingleShotTime 1 fork / 0 JMH warmup / 1 iteration，负载内预热30秒、测量45秒、客户端 -prof gc；节点 profile.jfc JFR maxsize128m、NMT summary，采样结果不混入无 profiler 主吞吐。
- 受影响控制路径另按六产品依次单节点运行 ClusterAccountControlBenchmark：1 fork、1 warmup、2 measurement，每 invocation 100 个同账户依赖循环，覆盖余额增减、模式修改、杠杆修改（排除现货/期权不支持项）、真实持仓逐仓保证金增减，-prof gc + 节点 JFR；这些同账户串行控制样本不作为交易容量。所有产品继续执行真实日志/快照恢复功能门禁。
- 通过门槛：所有构建回归通过；accepted/offered 等于 terminal、unfinished=0、fundsDiff=0、持仓与冻结和订单状态门禁通过，零非预期错误/超时。没有预设吞吐涨幅承诺；报告 business ops/s、Core messages/s、fills/s、分业务延迟及线程CPU/热点。若 JFR DataLoss、明显换页或磁盘不足则诊断轮无效。短样本不作为云端容量、三节点容错或长期无泄漏验收。
- 临时产物固定 /tmp/architecture-single-final-v1；采集脚本 /tmp/architecture-lanes-check/run_single_final.py，命令与进程记录写入其子目录。分析后记录摘要并清理原始大文件。全局控制、复杂清算/ADL/触发控制及失败回滚仍有有序交接边界；本轮验证真实分区交易执行，不以此声称这些路径已全部永久 Lane 化。


### 2026-09-10 单成员方案二（原对照实验）：最终采集结果

主吞吐为两轮无 profiler 的实际业务终态总数除以总耗时；每轮的轮次、参数与场景沿用上条预锁定义。结果属于本机单成员短测，不与历史三节点或直接 Core 数值混算。

#### 方案二（lanes）

- 主轮 0: mixedCapacity=PASS elapsedSeconds=60.061 terminalBusinessOperations=9046784 offeredBusinessOperations=9046784 terminalCoreMessages=875264 offeredCoreMessages=875264 businessOpsPerSec=150627.287 coreMessagesPerSec=14572.984 fills=2150400 fillsPerSec=35803.764 queries=0 unfinished=0 peakInFlight=256 measuredCycles=420 totalCycles=628 triggerExecutions=0
  business=PLACE_ORDER items=215040 requests=215040 p50us=7139 p90us=20234 p95us=22249 p99us=29310 p999us=56950 maxus=87490
  business=CANCEL_ORDER items=215040 requests=215040 p50us=7385 p90us=26165 p95us=28721 p99us=36077 p999us=66125 maxus=84017
  business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=11886 p90us=26574 p95us=28753 p99us=42205 p999us=56229 maxus=62423
  business=PLACE_ORDER_BATCH items=6451200 requests=322560 p50us=24395 p90us=30097 p95us=34308 p99us=43581 p999us=77856 maxus=89391
  business=CANCEL_ORDER_BATCH items=2150400 requests=107520 p50us=25444 p90us=29097 p95us=34635 p99us=62259 p999us=81264 maxus=87425
- 主轮 1: mixedCapacity=PASS elapsedSeconds=60.116 terminalBusinessOperations=9132800 offeredBusinessOperations=9132800 terminalCoreMessages=883456 offeredCoreMessages=883456 businessOpsPerSec=151920.872 coreMessagesPerSec=14695.976 fills=2170880 fillsPerSec=36111.815 queries=0 unfinished=0 peakInFlight=256 measuredCycles=424 totalCycles=621 triggerExecutions=0
  business=PLACE_ORDER items=217088 requests=217088 p50us=7282 p90us=20250 p95us=22151 p99us=28377 p999us=48168 maxus=55410
  business=CANCEL_ORDER items=217088 requests=217088 p50us=7286 p90us=26198 p95us=28655 p99us=36962 p999us=64880 maxus=75038
  business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=13615 p90us=27410 p95us=29016 p99us=35192 p999us=43679 maxus=55345
  business=PLACE_ORDER_BATCH items=6512640 requests=325632 p50us=23969 p90us=29687 p95us=33718 p99us=42926 p999us=78774 maxus=91357
  business=CANCEL_ORDER_BATCH items=2170880 requests=108544 p50us=25264 p90us=29442 p95us=34865 p99us=53313 p999us=62816 maxus=64684
- 两轮合并：business ops/s=151273.405，Core messages/s=14634.414，fills/s=35957.629。
- 独立采样轮：mixedCapacity=PASS elapsedSeconds=45.128 terminalBusinessOperations=6462464 offeredBusinessOperations=6462464 terminalCoreMessages=625664 offeredCoreMessages=625664 businessOpsPerSec=143201.636 coreMessagesPerSec=13864.079 fills=1536000 fillsPerSec=34036.199 queries=0 unfinished=0 peakInFlight=256 measuredCycles=300 totalCycles=496 triggerExecutions=0
- 节点测量窗口 JFR：totals allocationMiBps=483.319 allocationBytes=22871223352 machineCPU=83.65 jvmCPU=57.99 heapMaxMiB=447.51 dataLoss=0 ownerIOEvents=0
- gc count=109 totalMs=848.845 p99Ms=10.725 maxMs=16.754
- 各线程 CPU（单核100%，含空转）：threadCPU 97.478 core-account-lane-2；threadCPU 97.474 core-account-lane-0；threadCPU 97.474 core-account-lane-1；threadCPU 97.460 core-account-lane-3；threadCPU 97.253 trading-owner--1；threadCPU 96.608 /tmp/architecture-single-final-v1/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]；threadCPU 74.949 clustered-service-101-0；threadCPU 70.328 driver-conductor；threadCPU 62.179 archive-conductor；threadCPU 60.904 consensus-module-101-0；threadCPU 23.422 core-matcher-0；threadCPU 23.211 core-matcher-1；threadCPU 1.432 JVMCI-native CompilerThread0；threadCPU 1.021 aeron-md-nra；threadCPU 0.233 aeron-client；threadCPU 0.079 JFR Periodic Tasks；threadCPU 0.074 JFR Recorder Thread；threadCPU 0.068 C1 CompilerThread0；threadCPU 0.028 Service Thread；threadCPU 0.014 Monitor Deflation Thread
- nmt-before.txt: Total: reserved=3142193KB, committed=691757KB
- nmt-after.txt: Total: reserved=3164701KB, committed=723521KB
- JFR 扩展：{"measuredEventCounts": {"jdk.SafepointBegin": 111, "jdk.MetaspaceSummary": 192, "jdk.GarbageCollection": 96, "jdk.DirectBufferStatistics": 9, "jdk.Deoptimization": 8}, "directBuffers": {"first": {"startTime": "2026-09-10T12:52:48.604611601+08:00", "maxCapacity": 1610612736, "count": 8, "totalCapacity": 9575136, "memoryUsed": 9575136}, "last": {"startTime": "2026-09-10T12:53:28.779224210+08:00", "maxCapacity": 1610612736, "count": 8, "totalCapacity": 9575136, "memoryUsed": 9575136}, "peakMemoryUsed": 9575136}, "errorClasses": {}, "errorMessages": {}, "gcCauses": {"G1 Evacuation Pause": 96}}
- 恢复：真实日志重放及快照重启均 PASS，业务哈希 c51a6264e424d0af；恢复前后完整状态一致。
- Maven verify 成功，Surefire 报告汇总 tests/failures/errors/skipped=[1188, 0, 0, 1]；各一项 instrument seed JDBC 环境测试因 INSTRUMENT_SEED_TEST_JDBC_URL 未配置跳过，未改该 SQL 路径。
- 被测 service JAR SHA-256 c11be174ae2ec6bce6f1ef58588536c2fa585aac048ae34288d8e7c9859acd35
- accountControlVerify=PASS product=INVERSE_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；JMH 两测量 invocation 均值 3197.513 ms，客户端 gc.alloc.rate.norm=3217552 B/invocation；totals allocationMiBps=6.251 allocationBytes=91766584 machineCPU=41.60 jvmCPU=32.01 heapMaxMiB=51.22 dataLoss=0 ownerIOEvents=0（控制 JFR 含启动，不作稳定态分配率）。
- accountControlVerify=PASS product=INVERSE_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；JMH 两测量 invocation 均值 3213.099 ms，客户端 gc.alloc.rate.norm=3221264 B/invocation；totals allocationMiBps=6.210 allocationBytes=91168064 machineCPU=43.16 jvmCPU=32.12 heapMaxMiB=51.22 dataLoss=0 ownerIOEvents=0（控制 JFR 含启动，不作稳定态分配率）。
- accountControlVerify=PASS product=LINEAR_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；JMH 两测量 invocation 均值 3219.266 ms，客户端 gc.alloc.rate.norm=3319076 B/invocation；totals allocationMiBps=6.211 allocationBytes=91178184 machineCPU=42.58 jvmCPU=32.20 heapMaxMiB=51.51 dataLoss=0 ownerIOEvents=0（控制 JFR 含启动，不作稳定态分配率）。
- accountControlVerify=PASS product=LINEAR_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；JMH 两测量 invocation 均值 3202.207 ms，客户端 gc.alloc.rate.norm=3317580 B/invocation；totals allocationMiBps=6.187 allocationBytes=90828688 machineCPU=41.31 jvmCPU=32.17 heapMaxMiB=51.76 dataLoss=0 ownerIOEvents=0（控制 JFR 含启动，不作稳定态分配率）。
- accountControlVerify=PASS product=OPTION terminalBusinessOperations=1800 unfinished=0 fundsDiff=0 netPosition=0；JMH 两测量 invocation 均值 2444.897 ms，客户端 gc.alloc.rate.norm=2548016 B/invocation；totals allocationMiBps=7.174 allocationBytes=90273600 machineCPU=42.14 jvmCPU=31.86 heapMaxMiB=51.22 dataLoss=0 ownerIOEvents=0（控制 JFR 含启动，不作稳定态分配率）。
- accountControlVerify=PASS product=SPOT terminalBusinessOperations=600 unfinished=0 fundsDiff=0 netPosition=0；JMH 两测量 invocation 均值 830.381 ms，客户端 gc.alloc.rate.norm=1202128 B/invocation；totals allocationMiBps=11.355 allocationBytes=83343448 machineCPU=40.53 jvmCPU=28.55 heapMaxMiB=51.92 dataLoss=0 ownerIOEvents=0（控制 JFR 含启动，不作稳定态分配率）。

#### 正确性、热点及范围

- 本轮补齐杠杆变更的异步账户 Lane 路径，按已有账户索引检查敞口，删除其全量订单/持仓副本扫描；相关修改、重复命令、超过限制拒绝、无关 Lane 阻塞和快照恢复测试通过。余额、显式跨产品划转、持仓模式、逐仓保证金调整及原生拒单解冻此前迁移的路径纳入完整回归。共同业务修复已同步到融合方案；融合方案保留其内联执行方式。
- 方案二的独立 matcher/account 分区并行门禁覆盖六产品线：不同账户/订单簿可提前结算但不得越过全局日志提交顺序；共享 maker 及同账户跨币对保证金依赖仍保持顺序。不是按币对切割用户的共享资金。复杂触发控制、清算/ADL、到期控制和脏失败回滚仍有有序交接边界，本轮不声称这些控制路径全部永久 Lane 化或没有任何协调。
- 方案二 Owner CPU97.253%，matcher23.422%/23.211%；四 Lane约97.47%包含大量 busy-spin。Lane执行样本13933中，MatcherSettlementEvent.execute781、PlaceBatchAdmissionEvent.execute476、LaneCancelEvent.execute128；不能据CPU占用认定账户业务已饱和。Owner样本3040中，completeMatching1188(39.1%)、finishOrderBatch878(28.9%)、preparePipelinedPlaceBatch436(14.3%)、collectMatcherSettlement390(12.8%)，都是含子调用的比例，不可相加。主要串行成本仍在批量收尾、收集和提交。
- 节点分配抽样权重：方案二483.319MiB/s，非零分配。主要类包括 byte[]、long[]、OrderRuntime、Long；采样位置不能直接证明 VarHandle 等方法自身分配。JMH gc 指标来自客户端，B/invocation不是B/business-op；节点归因使用单独JFR。
- 恢复脚本初次60秒领导者等待超时，栈显示正在消费重放日志，放宽恢复上限至600秒后两方案均完成；未删除业务日志规避恢复。Aeron1.53.0在恢复初期记录quorum position went backwards警告：源码updateLeaderPosition使用proposeMaxRelease维持本地commitPosition单调；本次两方案恢复哈希一致。未修改或屏蔽上游警告。融合分支同步共同修改时曾因误带分区索引依赖编译失败，移除融合分支不使用的分区依赖后full8构建通过。
- 所有节点profile DataLoss=0；测量窗口无记录到的Owner文件/网络I/O事件和JavaErrorThrow。profile.jfc未启用完整TLAB/nonTLAB事件，不能给精确对象数；未采完整墙钟等待、逐阶段延迟、OS pagefault/swap前后、长期live-set趋势，不能宣称完整容量或无泄漏验收。NMT与DirectBuffer短窗稳定也不能证明长期无泄漏。未压测实时推送、Valkey、数据库、三节点复制或完整持续运营混合。
- 六产品最终单成员功能门禁（每次一产品）：
  SPOT: execute/crash-replay/snapshot-restart PASS，fundsDiff=0，snapshotPosition=3616
  LINEAR_PERPETUAL: execute/crash-replay/snapshot-restart PASS，fundsDiff=0，snapshotPosition=9568
  INVERSE_PERPETUAL: execute/crash-replay/snapshot-restart PASS，fundsDiff=0，snapshotPosition=9568
  LINEAR_DELIVERY: execute/crash-replay/snapshot-restart PASS，fundsDiff=0，snapshotPosition=5184
  INVERSE_DELIVERY: execute/crash-replay/snapshot-restart PASS，fundsDiff=0，snapshotPosition=5184
  OPTION: execute/crash-replay/snapshot-restart PASS，fundsDiff=0，snapshotPosition=5184

执行入口：HotSpot25 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`；服务SurprisingClusterNode；负载ClusterMixedCapacityMain；JMH ClusterOperationalBenchmark.continuousOperations -f1 -wi0 -i1 -p controlPageSize=0 -prof gc；控制JMH ClusterAccountControlBenchmark.accountControls -f1 -wi1 -i2 -p productLine=<对应产品> -prof gc；恢复ClusterProductLineGateMain及真实RecordingLog快照。详细部署/负载参数见预锁记录。

本轮临时根目录 /tmp/architecture-single-final-v1 与 /tmp/architecture-lanes-single-gates-v4 的原始 Archive、JFR、日志和 JSON，以及两实验工作区的测试报告，分析记录后清理；不再把这些目录当作可访问证据。分支保留编译产物用于后续工作，不触碰主工作区或云端资源。


## 2026-09-10 方案二 Owner 批量收尾精简：采集前锁定

- 被测分支 experiment/lane-owned-state，基于 0ad3e1a8 的本轮工作区；仅测方案二，不启动方案一或云端。历史同口径方案二 151273.405 terminal business ops/s 仅供趋势比较。
- 修改：借用订单字段编码批量结果；复用批量资金增量并删除中间合并；单结算事件直接持有、缓存 Owner 已观察到的 Lane 完成位；订单/持仓状态与预计算索引共用键表、提交时一次遍历。保留资金依赖、失败检查及有序提交。
- 环境固定：macOS 26.7，i9-9880H 8C16T / 16 GiB，HotSpot 兼容 GraalVM JDK 25.0.1、G1、Maven 3.9.16。单个真实 Aeron Cluster 成员，网络+Archive+业务 Core，PIPELINED，4 Account Lane、2 matcher（扩展性诊断，非单 matcher/三节点容量验收），Account BUSY_SPIN/spin-limit=0，服务 YIELDING，SHARED_NETWORK，客户端 SHARED。
- JVM：节点 Xms512m/Xmx1536m，客户端128m/512m，NMT summary；开放 jdk.internal.misc/java.util.zip，native access。脚本保留精确命令与 jar SHA256。
- 场景：LINEAR_PERPETUAL 连续异步交易流，256 symbols、1769 users、1 command session+1 query session；全局/会话在途256，batch20，生成行情；mixed-trading-stream=true、mixed-operational=false。沿用已有初始化资金/持仓及买卖挂单、IOC成交、批量撤单轮转；无逐笔等待，无固定到达率，窗口闭环，未修正 coordinated omission。六产品线分别做业务、故障重放、快照恢复功能核验，不同时启动。
- 无 profiler 主测两轮，seed131001/131002，预热30秒、测量60秒；每轮独立新集群，结束排空核账并停止进程。JMH ClusterOperationalBenchmark.continuousOperations，fork1/thread1/SingleShotTime、JMH warmup0/measurement1，内部预热30秒测量45秒，-prof gc；节点 JFR profile/maxsize128m。新增批量每项订单身份与数量一致性检查，覆盖复用编码游标；JMH分数为整轮秒数，GC规范化单位为客户端每轮，不当作每业务操作。
- 通过条件：功能回归无失败；accepted/terminal业务数与消息数相等、unfinished=0、超时/错误0、资金差额0、批量订单身份正确；重放/快照恢复业务hash一致。吞吐目标维持历史151273.405的95%以上（143709.735 ops/s），两轮合并；低于目标必须如实定位。profile用于归因，不混入主分数；无JFR DataLoss/低磁盘环境异常。样本不代表长稳泄漏、云端三节点或全部控制任务并发容量。
- 运行脚本 /tmp/lanes-owner-trim/run_load.py、run_gates.py；产物 /tmp/lanes-owner-trim-load-v1 与 /tmp/lanes-owner-trim-gates-v1。分析后记入本文件并清理本轮日志、录制、JFR、测试报告；不保留新MD。

- 采集前补充：共享状态索引影响六产品线，因此依次执行真实单成员 ClusterAccountControlBenchmark.accountControls，逐产品线 fork1/warmup1/measurement2/thread1/-prof gc，节点同样 JFR profile；每 invocation 100 次同账户控制循环及初始交易，核验资金与hash。这组是功能路径性能诊断，不混入连续交易主吞吐。

### 方案二 Owner 精简：结果（2026-09-10 13:35–13:48，本机单成员）

- 实测代码提交 `8b5e7cfe`；构建时为相同源码工作区，服务 jar SHA256 `b185484ff10928eb601e2234e2812fabe321c243ea6f0cb2bfe56e40178d91ca`。未测试/修改方案一或主工作区。
- 执行：上述 JDK25 环境下 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`；首次暴露测试对旧 `settlementEvents`、旧缓冲字段布局的反射依赖，已适配并保留原断言。随后新增压测响应断言错误地禁止已退役订单视图为空，被原测试拦住；修正为视图非空时验证订单身份/数量，补串单拒绝测试。最终定向重跑及 `package` 成功，受影响 XML 合计1193项、失败0、错误0、跳过1（未配置 INSTRUMENT_SEED_TEST_JDBC_URL）。没有将这些测试结构错误归因于资金业务。
- 两轮主测分别166705.438、167313.208 terminal business ops/s；按实际测量时间合并167009.421 ops/s，16133.068 terminal Core messages/s、39704.303 fills/s；相比历史方案二151273.405约+10.40%，超过预锁143709.735下限。仅两轮短测，无跨天环境归因/三节点容量结论。
- 每轮 offered与terminal业务数/消息数严格相等、unfinished=0、最终排空、峰值在途256；资金差额0，用户/做市持仓、冻结、亏损/清算/保险/ADL初始场景核验通过。主测 queries=0、triggerExecutions=0、mixed-operational=false，不宣称实时风控/触发/查询混合饱和。本场景无拒绝/命令错误/超时退出，但未额外采集独立 accepted 阶段直方图。

```text
lanes-main-LINEAR_PERPETUAL-0
mixedCapacity=PASS elapsedSeconds=60.073 terminalBusinessOperations=10014464 offeredBusinessOperations=10014464 terminalCoreMessages=967424 offeredCoreMessages=967424 businessOpsPerSec=166705.438 coreMessagesPerSec=16104.191 fills=2380800 fillsPerSec=39631.907 queries=0 unfinished=0 peakInFlight=256 measuredCycles=465 totalCycles=689 triggerExecutions=0
business=PLACE_ORDER items=238080 requests=238080 p50us=6639 p90us=18431 p95us=20414 p99us=25722 p999us=45973 maxus=61079
business=CANCEL_ORDER items=238080 requests=238080 p50us=6664 p90us=23674 p95us=26345 p99us=33144 p999us=54951 maxus=69337
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=12681 p90us=25247 p95us=27115 p99us=33210 p999us=42106 maxus=45645
business=PLACE_ORDER_BATCH items=7142400 requests=357120 p50us=21676 p90us=27836 p95us=30818 p99us=38895 p999us=65437 maxus=69337
business=CANCEL_ORDER_BATCH items=2380800 requests=119040 p50us=23265 p90us=27033 p95us=31342 p99us=48463 p999us=63012 maxus=65896
lanes-main-LINEAR_PERPETUAL-1
mixedCapacity=PASS elapsedSeconds=60.112 terminalBusinessOperations=10057472 offeredBusinessOperations=10057472 terminalCoreMessages=971520 offeredCoreMessages=971520 businessOpsPerSec=167313.208 coreMessagesPerSec=16161.927 fills=2391040 fillsPerSec=39776.653 queries=0 unfinished=0 peakInFlight=256 measuredCycles=467 totalCycles=675 triggerExecutions=0
business=PLACE_ORDER items=239104 requests=239104 p50us=6635 p90us=18481 p95us=20332 p99us=25608 p999us=46170 maxus=61505
business=CANCEL_ORDER items=239104 requests=239104 p50us=6676 p90us=23543 p95us=25886 p99us=33947 p999us=55934 maxus=73072
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=12132 p90us=26427 p95us=30228 p99us=39288 p999us=56918 maxus=58195
business=PLACE_ORDER_BATCH items=7173120 requests=358656 p50us=21659 p90us=27148 p95us=31047 p99us=39780 p999us=62783 maxus=79101
business=CANCEL_ORDER_BATCH items=2391040 requests=119552 p50us=22937 p90us=26804 p95us=32260 p99us=53379 p999us=74186 maxus=75628
```

- 延迟为客户端发送到终态响应，单位us，未修正coordinated omission；p50/p90/p95/p99/p99.9/max及样本数见上表。批量20项，PLACE_BATCH约5948/5966 batches/s（118966/119329 items/s），CANCEL_BATCH约1982/1989 batches/s；不将fills混为业务操作。普通下单p99为25.722/25.608ms，仍不是低于1ms。
- JMH真实外部Cluster连续轮：45.141053642秒/整轮，内部终态165078.503 business ops/s、15949.613 Core messages/s、39244.445 fills/s、7451752业务动作/719976消息均终态，unfinished0；客户端gc.alloc.rate=126.667MiB/s、gc.alloc.rate.norm=14753509552 B/整轮、gc.count190/gc.time185ms。SingleShot单样本无置信区间，不把客户端B/整轮误报成Core B/业务操作。
- JFR节点profile文件5,917,833字节，SHA256 `5a19c9bf01c9c6d7b55c04c2ccc1d249c2cfd31f4c11b113dd79023246349913`；分析仅measurementStart/End窗口45.141秒。Owner CPU97.290%单核；matcher23.390/23.299%；四Lane97.35–97.37%，大量自旋。机器CPU81.94%、节点JVM59.07%（整机口径）。Owner3311执行样本，finishOrderBatch929=28.06%，preparePipelinedPlaceBatch512=15.46%，collectMatcherSettlement398=12.02%，commitTerminalToOwner302=9.12%，visitChangedIndexes133=4.02%；inclusive调用树互相包含，不能相加。Lane14957样本中结算execute844、准入execute524，不能把近满核视为业务饱和。
- 节点ObjectAllocationSample权重估计541.695MiB/s、3440.862B/业务操作；历史3539.043B/操作，约下降2.77%，绝对分配率随吞吐提高，仍非零分配。热点包括Owner byte[]/long[]/OrderBatchItem/ResolvedPlaceOrder、Lane long[]/OrderRuntime。profile没有精确TLAB/非TLAB逐对象计数，不能宣称精确对象数/最大实际对象。
- GCPhasePause116次/982.536ms，约测量时间2.18%，p99=11.262ms/max15.712ms；GarbageCollection事件96 G1New+10 G1Old（G1Old不是10次Full GC），累计事件duration1367.015ms含并发部分；heap最大447.28MiB，测量内AfterGC采样约100–210MiB，无本轮疏散失败证据。停顿对25ms级p99有影响，未证明每条尾延迟的因果归属。
- NMT启动后/最终reserved3144228→3151459KiB、committed691712→725939KiB（+34227KiB），主要Code committed15986→40129、GC71182→71980、Thread1895→2678；Heap526336→524288KiB、Other9409→9411KiB。测量DirectBuffer count8/memory9575136字节保持不变。未完整统计mmap/Aeron池逐分配释放余额，短轮无法证明无泄漏。
- 测量SafepointBegin118次，总到达时间8.725ms/max0.533ms；VM operation120次/总986.149ms/max15.740ms。Compilation4次/836.483ms/max257.275ms、Deoptimization17次，仍有编译活动；线程峰值23。主轮和六控制轮JFR DataLoss均0，Owner采样同步文件/网络I/O事件0；profile事件阈值不能证明绝对无任何等待。未采集OS换页增量/调度节流/长稳泄漏及全产品完整阶段延迟，因此这是本轮优化的功能+性能诊断通过，不是全部生产容量指标验收。
- 六产品线真实单成员业务→SIGKILL→日志重放→快照恢复全部PASS，fundsDiff0；快照位置SPOT3616，两永续9568，两交割及OPTION5184。连续压测profile的全量日志重放及快照恢复也PASS，同一businessHash `1b18fe4bbef90ab9`，snapshotPosition1166851008。
- 恢复时Aeron1.53.0出现一次已有quorum position warning（leaderCommitPosition1166165024/quorumPosition0），随后正常LEADER且两次hash/资金核对通过；沿用此前已定位的上游恢复期quorum计算提示，不修改/屏蔽它。另有JDK25 Unsafe、SLF4J/JMH依赖提示，未出现交易EXCHANGE_CORE_FAILURE。
- 六产品线控制JMH（每invocation100循环；fork1/warmup1/measurement2，不是主吞吐；均accountControlVerify=PASS），对应节点JFR均有执行/分配样本且无DataLoss：
  - lanes-control-INVERSE_DELIVERY-0: 3135.623 ms/100循环，客户端 0.977 MiB/s、3258756 B/100循环、GC0。
  - lanes-control-INVERSE_PERPETUAL-0: 3050.973 ms/100循环，客户端 0.994 MiB/s、3216368 B/100循环、GC0。
  - lanes-control-LINEAR_DELIVERY-0: 3054.509 ms/100循环，客户端 1.176 MiB/s、3815220 B/100循环、GC0。
  - lanes-control-LINEAR_PERPETUAL-0: 3033.752 ms/100循环，客户端 0.768 MiB/s、2473500 B/100循环、GC0。
  - lanes-control-OPTION-0: 2315.876 ms/100循环，客户端 1.035 MiB/s、2548264 B/100循环、GC0。
  - lanes-control-SPOT-0: 793.161 ms/100循环，客户端 1.566 MiB/s、1375068 B/100循环、GC0。
- 执行脚本：`python3 /tmp/lanes-owner-trim/run_gates.py`、`run_load.py`、`recover.py`；所有节点及客户端结束。原始路径为本段采集前列出的两个临时目录，分析摘要已写入本文件；随后删除本轮Archive、JFR、日志、测试报告和临时分析文件。已删除路径只作历史来源，不再作为可访问证据。


## 2026-09-10 Owner瓶颈深入定位：采集前锁定
仅采样方案二8b5e7cfe/08b4abc2现有jar，不改业务，不测方案一。复用上一轮全部profile参数（单成员真实Cluster，4Lane/2matcher、256在途、1769用户/256symbol/batch20，LINEAR_PERPETUAL连续交易，30秒预热45秒测量，JDK25/G1/NMT/profile.jfc，JMH SingleShot fork1/thread1/-prof gc）。本轮目的为Owner栈的互斥阶段、自耗时叶子、等待栈与Lane有效业务占比定位，不作为新的主吞吐比较。有效条件为终态数量相等、unfinished0、fundsDiff0、DataLoss0、磁盘>10GiB；沿用上一轮已通过的六产品线及重放/快照证据，本轮无代码改动不重复恢复。原始产物/tmp/owner-pinpoint-cluster，分析后记录并清理；不以方法inclusive占比之和充当耗时分解。

### Owner深入定位结果（现有代码未改）
- 采样轮171057.769 business ops/s、16520.039 Core messages/s、40667.824 fills/s；7709831业务动作与744583消息offered/terminal均相等、unfinished0、fundsDiff0；业务hash dc694083d4f77ebf。仅诊断采样，不替代上轮无profiler主吞吐。Owner CPU96.637%单核，matcher22.803/22.804%，四Lane97.29–97.31%。
- 测量窗口1789019668613–1789019713685，Owner3252个执行样本；下表每个样本只归入一个阶段，合计100%，是CPU执行样本而非端到端耗时/精确调用次数。
  - admission.prepareBatch: 497/3252 = 15.28%。
  - finish.collectSettlement: 367/3252 = 11.29%。
  - completeMatching.other: 336/3252 = 10.33%。
  - admission.other: 276/3252 = 8.49%。
  - ingress.dependencies: 228/3252 = 7.01%。
  - other: 222/3252 = 6.83%。
  - dispatch.buildSettlement: 212/3252 = 6.52%。
  - admission.collectAndSubmit: 211/3252 = 6.49%。
  - finish.publish: 187/3252 = 5.75%。
  - finish.other: 151/3252 = 4.64%。
  - dispatch.applyMatcherResults: 149/3252 = 4.58%。
  - finish.collectCancel: 140/3252 = 4.31%。
  - coordination.other: 127/3252 = 3.91%。
  - finish.encodeResponse: 89/3252 = 2.74%。
  - dispatch.other: 40/3252 = 1.23%。
  - prefix.other: 17/3252 = 0.52%。
  - egress: 3/3252 = 0.09%。
- 批量收尾内部：结算/撤单收集507样本15.59%，发布187/5.75%，响应编码89/2.74%，其余151/4.64%。准入prepare497/15.28%与collectAndSubmit211/6.49%独立；“协调剩余”127/3.91%不能等价为全是空轮询，含健康检查等。单笔completeMatching.other336/10.33%也含实际业务工作。
- 方法inclusive证据（嵌套不可相加）：commitTerminalToOwner308/9.47%，stagePlaceBatchAdmission136/4.18%，reserveSymbolId103/3.17%，admissionIdentity93/2.86%，conflictingPrefixSize90/2.77%，clearChangedKeys88/2.71%，resolve75/2.31%，prepareClientKey70/2.15%，decodeCommand67/2.06%，deterministicKey45/1.38%，assertAccountLanesHealthy43/1.32%，PendingReservationSequenceIndex.remove38/1.17%，MatcherSettlementEvent.complete仅8/0.25%。
- 具体源码链：OrderBatchExecutor.preparePipelinedPlaceBatch逐20项做币对/行情/费率/身份解析，RuntimeIdentityRegistry.deterministicKey还调用UTF8 getBytes；Lane准入完成后TradingRuntimeState.stagePlaceBatchAdmission逐项更新publishedOrders/publishedReservations/orderLaneIds/reservationLaneIds及pending索引；结算完成collectMatcherSettlement→commitTerminalToOwner再次逐实体搬运提交视图/路由，再OwnerCommitPublisher维护索引、清理变化键。这些是实际串行工作，非一个等待通知。
- 另两处可直接定位的小开销：DeterministicExchangeCoreAdapter.reserveSymbolId整个方法synchronized，已知symbol查询也进锁；PendingReservationSequenceIndex.remove为选一个提升元素执行additionalOrderIds.toArray()[0]，产生不必要数组/遍历（是否多次提升取决于删除顺序，不能笼统说每批O(n²)）。当前无长时间锁竞争证据，不把3.17%全部称作锁等待。
- 热路径SurprisingClusteredService.pollCommandPrefix明确awaitFirst=false，不进入awaitAnyMatchingCommitReady；每条命令仍drainingSize=1并触发pump，事务/日志边界有需求，不能直接合并最终提交或删除依赖。优先削减实体级准入和提交搬运，其次才是健康检查/轮询摊销；保持共享保证金在账户Lane的正确性和确定性边界。
- Lane14966样本中worker-loop-self13471=90.01%，结算815、准入485、撤单144、其他51；busy-spin约97%CPU不代表业务饱和。Owner无ThreadPark/JavaMonitorEnter/Wait记录、无同步I/O事件，profile阈值限制仍存在。不能据此证明每次Lane空闲都由Owner造成，精确队列空闲原因/调用空转率仍需进一步事件计数；现有证据已确认Owner被实体级串行准备和发布工作占满。
- 节点分配采样566.506MiB/s、heap最大446.70MiB、GCPhasePause128次/984.930ms/p99=14.974ms/max15.213ms；DataLoss0。其余参数、NMT与采样指标限制沿用采集前定义和上一轮验收范围，不宣称长稳/三节点容量已验证。
- JFR SHA256 7fa0b2772aa4efa48e2b51dc763a66880d46afd2f5ed11bfe25d9f5b88bb09ed，大小6091907字节；临时分析器Pinpoint按每个完整Owner调用栈互斥分类，并额外统计每方法inclusive、叶子行号及Lane循环。原始目录/tmp/owner-pinpoint-cluster和/tmp/owner-pinpoint分析完清理；无业务代码修改、无重复功能测试或恢复测试。


## 2026-09-10 方案二小热点修复：采集前锁定
- 仅方案二429ca02a工作区，修改已注册symbol查询免监视器、新symbol保留同步双重检查；预留提升用detectIfNone取首个元素；身份键流式UTF8哈希保持旧键。主架构调整仅给方案，不在本轮修改账户/提交所有权。
- JDK25/G1、单成员真实Cluster、4Lane/2matcher/256在途、其余全部沿用上一轮profile参数与资金初始化，预热30秒测量45秒，JMH fork1/thread1/SingleShot/-prof gc，节点JFR profile/NMT。新增mixed-unicode-client-ids=true，每16单一个中文+emoji客户ID，以覆盖真实UTF8路径；输入变化，本轮不直接与旧ASCII吞吐比较或承诺提升百分比。六产品线控制JMH及真实业务/重放/快照按上一轮相同参数顺序执行。无主测额外轮次。
- 有效条件：测试通过、身份哈希匹配Java原始UTF8算法（全部单UTF16码元/配对及不合法代理项/随机串）、已注册查询持注册锁时可并发完成、新币对并发一致、预留逐项提升次序不变；真实负载offered=terminal/unfinished0/fundsDiff0/DataLoss0、六产品线恢复核对通过。磁盘>10GiB；原始/tmp/lane-small-hotspots-load、-gates和/tmp/lane-small-hotspots分析完成后清理。

- 追加采集前定义：Unicode诊断轮约15万级，输入改变不能判断回归；因此在其所有控制轮结束后，顺序补一次当前代码ASCII原始输入profile（mixed-unicode-client-ids默认false），其余45秒参数完全相同，输出/tmp/lane-small-hotspots-ascii。只确认原场景是否出现明显退化，不重跑旧代码、不将profile与无profiler主分数混算；资金和DataLoss等有效条件相同。

### 小热点修复结果
- 代码提交0fac657e，HotSpot25定向构建`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=RuntimeIdentityRegistryTest,TradingRuntimeStateTest,DeterministicExchangeCoreAdapterTest,ClusterCommandPipelineTest,ClusterMixedCapacityTest -Dsurefire.failIfNoSpecifiedTests=false package`成功；268项测试0失败0错误0跳过。共享身份键/预留/路由已覆盖六产品线pipeline测试；未改其余产品业务，不重跑未受影响全仓测试。
- 身份哈希穷举65536个单UTF16码元及显式非法代理项/emoji/1000随机串，与Java UTF8 getBytes原算法完全一致；持有adapter注册监视器期间，已注册symbol跨线程查询完成；32个并发新币对查询结果一致；100预留按旧set数组首元素顺序逐项提升并删除，顺序一致。新symbol冲突检查和资金依赖均保留。
- 六产品线真实单成员业务、SIGKILL后日志重放、快照恢复全部PASS、fundsDiff0，快照位置SPOT3616、两永续9568、两交割与OPTION5184。没有重复启动三个节点。
- Unicode诊断profile：156950.648 business ops/s、15173.688 Core messages/s、37309.726 fills/s，7086094业务动作/685070消息均终态、unfinished0/峰值在途256、fundsDiff0，hash d41f2c2bffdacc70。约每16单一个中文emoji客户ID，输入与旧纯ASCII不同，不据此计算性能提升/回退百分比。
- ASCII补测标记为无效性能对照：测量时外部Java8进程PID82566占237%CPU，SASE GPU约77.6%、WindowServer34.4%、Terminal27.7%；本轮节点/客户端分别约762.7%/194.5%。JFR整机CPU97.45%、节点34.98%，因此78007.266 business ops/s不作为代码性能结论。未终止外部进程，允许本轮完成核账后停止、不再重跑；3516246业务动作/344918消息均终态、unfinished0/fundsDiff0，仍可作为功能运行证据。原场景同环境性能收益尚未确认。
- Unicode节点JFR：45.149秒测量、DataLoss0/Owner同步IO事件0；Owner96.013%单核、matcher21.332/21.290%、Lane97.19–97.24%，保持自旋与有效工作区分。分配采样506.319MiB/s、约3383B/业务操作；heap最大446.75MiB；GCPhasePause92次/965.830ms/max16.323ms。此轮短采样不能证明无泄漏、全部等待消失或生产三节点容量。
- 两轮及六控制轮JMH/JFR摘要、哈希如下（gc规范化B/op为客户端一次完整benchmark调用，不是每个business operation；控制每调用100循环，所有控制accountControlVerify=PASS）：
  - lane-small-hotspots-load/lanes-control-INVERSE_DELIVERY-0: 3181.208 ms/op; gc.alloc.rate=1.150, gc.alloc.rate.norm=3888940.000, gc.count=0.000; JFR 839102 bytes SHA256 16ee69f78f5c28da36b29e705d70ed0fba25f3b6babebb0dcd4c0e40c0639ecf.
  - lane-small-hotspots-load/lanes-control-INVERSE_PERPETUAL-0: 3089.211 ms/op; gc.alloc.rate=1.166, gc.alloc.rate.norm=3814876.000, gc.count=0.000; JFR 834766 bytes SHA256 6f41beca155e3b9a136733c642b694f2426e3f7aafb15ff0b41b27f6e62eea32.
  - lane-small-hotspots-load/lanes-control-LINEAR_DELIVERY-0: 3076.017 ms/op; gc.alloc.rate=1.188, gc.alloc.rate.norm=3873008.000, gc.count=0.000; JFR 830960 bytes SHA256 54e38892493141768ed0b2c27ac382df5a6f12c2715cfac6d03a59dfdb1ae5a6.
  - lane-small-hotspots-load/lanes-control-LINEAR_PERPETUAL-0: 3142.150 ms/op; gc.alloc.rate=0.995, gc.alloc.rate.norm=3322556.000, gc.count=0.000; JFR 832121 bytes SHA256 49b2a0080b188e23011f4cd227301ab404a1b10979a3e15eecdec413f53c2269.
  - lane-small-hotspots-load/lanes-control-OPTION-0: 2374.851 ms/op; gc.alloc.rate=0.987, gc.alloc.rate.norm=2508356.000, gc.count=0.000; JFR 814609 bytes SHA256 068bc242fd8f9da878d0e24730794ec9f9f4a6061ed7515c1eb69851650d3aa9.
  - lane-small-hotspots-load/lanes-control-SPOT-0: 796.945 ms/op; gc.alloc.rate=1.400, gc.alloc.rate.norm=1238364.000, gc.count=0.000; JFR 722806 bytes SHA256 a9dc12c677da2ea2b611b96c5d271f39224d998b9253251ffcd52bf2d02ed33f.
  - lane-small-hotspots-load/lanes-profile-LINEAR_PERPETUAL-0: 45.149 s/op; gc.alloc.rate=111.287, gc.alloc.rate.norm=13073748448.000, gc.count=168.000, gc.time=183.000; JFR 5563848 bytes SHA256 c25d802f2b39b806298acb10d68e790957fe0de7259aaa12b37a0e90b657a5c8.
  - lane-small-hotspots-ascii/lanes-profile-LINEAR_PERPETUAL-0: 45.077 s/op; gc.alloc.rate=64.853, gc.alloc.rate.norm=7378732752.000, gc.count=96.000, gc.time=146.000; JFR 4318543 bytes SHA256 46b5f988b2a57c7311a82cb3fb8988bded7fd8ba2e557805cd9d199705a8d3e7.
- 原始路径/tmp/lane-small-hotspots-load、-ascii、-gates及/tmp/lane-small-hotspots分析记录后清理，测试报告清理，集群及客户端均已停止。只提交源码和本记录，README未修改。主Owner改造仍为建议方案：批内复用稳定上下文、账户准入及身份索引归属Lane，再减少Owner逐实体发布；需联合查询/恢复/依赖边界验证，不在本轮擅自改变资金归属或已提交可见性。


## 2026-09-10 方案二 Owner 三项改造：采集前锁定
- 仅 experiment/lane-owned-state，基于 e8daef2f 的本轮源码；不测方案一、不启动云端或本机三节点。修改为批内共享币对决策上下文、Account Lane 准备客户身份与逐单预留索引、Lane 准备发布实体/Owner 开放收据，删除三张重复路由表。客户键仍共用并发字典以保持跨 Lane 哈希冲突检查；发布实体的版本节点是未提交可见性与并发读取边界，不复制整张账户表。其分配成本必须由本轮采样判定。
- HotSpot Oracle GraalVM25.0.1、Maven3.9.16、Mac i9-9880H8C16T/16GiB；磁盘508GiB可用。单成员真实网络Cluster/Archive/Core，4 Account Lane、2 matcher、PIPELINED/BUSY_SPIN、spin-limit0、node SHARED_NETWORK/service YIELDING、client SHARED。G1/NMT summary，node Xms512m/Xmx1536m，client128m/512m；jdk.internal.misc opens/exports、java.util.zip opens/native-access 与之前相同。此次为2matcher诊断，非默认单matcher正式容量验收。
- 主轮两次，分别seed131001/131002，每次预热30秒、测量60秒、结束排空核账并停止冷却；输入ASCII不变。1769用户、256symbol、batch20、1命令session+1查询session，global/session在途256；LINEAR_PERPETUAL连续交易stream=true、operational=false。每循环按批量项展开：256symbol × (3×20批量下单+20批量撤单+2单笔下单+2单笔撤单)=21504业务动作，另含行情命令；撮合成交5120fills/循环，不混作订单数。初态沿用上一轮1769用户/1000零售、1768账户各10^9USDT单位及100+25调整，总资金1768000000125；持仓、初始挂单、亏损账户及做市流与既有脚本相同。
- 性能有效阈值：offered=terminal（业务和Core分别核对）、unfinished/backlog期末0、资金差0、订单客户ID逐项正确、持仓与预留终态正确、无交易异常或超时、JFR DataLoss0；磁盘至少10GiB，无明显外部CPU干扰或换页异常。持续吞吐目标至少维持既有16.7万级并争取提升，低于目标如实归因不宣称性能验收通过。此轮不重跑旧提交，历史166705/167313仅背景，非同期对照。
- 追加真实Cluster JMH continuousOperations fork1/thread1/SingleShot、预热业务30秒/测量45秒、-prof gc、controlPageSize0；节点profile.jfc/maxsize128MiB。更新逐项客户ID核对，覆盖Lane准备身份后编码边界。六产品线accountControls JMH各1warmup/2measurement，每invocation100控制循环，节点同配置JFR。功能六产品线逐一execute→SIGKILL→Archive replay→真实snapshot→restart；连续profile数据再日志重放及快照恢复核对hash。
- async窗口背压而非固定open-loop，不修正coordinated omission；只能报告现有入口至终态分类型直方图，不冒充三段完整延迟。主轮无profiler分数独立于profile；短诊断不宣称长稳无泄漏、生产三节点容量或所有线程业务饱和。原始/tmp/lane-owner-redesign，分析记录后删除本轮Archive/JFR/日志/测试报告，停止本轮进程。


### 三项改造第一轮结果与追加修正
- 第一阶段服务jar SHA256 d95f09a98e11ef91204ea24cfbc01bb11d31d4e7780e4c87c2dc8b6665c763cd；完整受影响reactor verify成功，164个测试类合计1198项（1197执行、1个既有InstrumentSeedCoreContractTest跳过），0失败/错误。随后补充账户Lane身份回滚/错误Lane、批上下文标记价隔离及逐项版本校验测试通过。最初失败为OwnerIndexChurnTest访问已删除orderLaneIds反射字段，改为验证发布可见性及终态存储清空，未改业务正确性断言。
- 第一无profiler轮60.030秒：192258.721 business ops/s、18538.181 Core messages/s、45715.932 fills/s；11541260业务动作、1112844消息全部终态，unfinished0，peak256，fundsDiff0，hash4bc1aa61f94b43bc。单笔下单p50/p99/p999/max=6627/28131/40894/48857us；批量下单=17219/37388/52330/68354us。这是过载窗口下延迟，未达到低于1ms目标。
- 第二无profiler轮175292.520 business ops/s、16922.289 Core messages/s、41676.377 fills/s，10530579业务动作/1016595消息全部终态，fundsDiff0，hash e9e0139695c9efd1；采集中外部Java8 PID1868启动且CPU曾488%，该轮标记吞吐环境受干扰，不与首轮合并稳定容量结论。没有终止外部任务。
- profile轮146360.103 business ops/s、14164.612 Core messages/s、34788.287 fills/s，6612992业务动作/640000消息全部终态，fundsDiff0，hash7d6fae26e2df11d9。全量日志重放与真实snapshot重启hash一致，snapshotPosition1102650944。六产品线execute/crash-replay/snapshot-restart全部PASS，snapshot位置现货3616/两永续9568/交割与期权5184。
- profile也存在外部Java8 PID2378/1868/2772反复占用3–7核（进程采样日志确认）；整机CPU91.35%，Owner90.305%、Lane94.0–94.3%、matcher20.8–20.9%。只作热点和功能诊断，不能将14.64万解读为优化回退。Owner3097执行样本：preparePipelinedPlaceBatch286(9.23%)、commitTerminalToOwner190(6.14%)，此前分别15.28%/9.47%，调用栈占比下降但非同机条件严格加速比。Lane14931样本，准入653/结算1114/撤单157，仍多数空转，不能称全部有效业务饱和。
- 新热点：Owner Long分配采样1546089240B，ConcurrentHashMap.get站点1884883464B（节点各线程合计）；发布表查询发生装箱。节点总分配543.093MiB/s、heap最大407.82MiB，GC pause143次/1013.978ms/p99 12.806ms/max13.687ms，DataLoss0/Owner同步IO事件0；JMH客户端gc.alloc.rate133.299MiB/s、14073099000B/整轮、181GC/196ms，不能当Core每business op分配。
- 随即将发布表键统一为私有Key，Owner/Lane使用线程私有只读查询键（绝不插入表），存储键各自独立构造且不再修改；保留相等性与碰撞检查，消除查询和删除路径装箱。新增同hash不同long连续交替查询/删除测试，禁止修改已入表键。此修正不增加第二张缓存或状态副本。

### 消除查询装箱后的采集前锁定
- 只测当前方案二修正代码，主轮参数、阈值、JDK、并发和场景完全同上；两次60秒无profiler、一次45秒JMH/JFR，六产品线控制JMH与最终jar业务/恢复核对。原始目录改为/tmp/lane-owner-redesign/perf-final、gates-final，前轮保留到汇总后一起清理。
- 若外部Java任务仍运行，新轮只标记有干扰诊断，不通过重复加压或终止外部任务制造结论。已异步请用户暂停其他Java任务，回归验证继续；即使未暂停仍完成测试，报告真实环境限制。新增版本目标为查询路径不再产生Key/Long逐调用分配，Owner/Lane发布可见性与金融不变量继续通过。


### 最终代码验证结果（69e7c99c）
- 最终业务代码69e7c99c，服务jar SHA256 63940b328a462e1ce27761761cbf3ea4e1911f923b4b43cbc1c637c6a9400cf7；后续仅修正所有权/数组生命周期注释及移除未用import。HotSpot25 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`成功：1201项中1200执行通过、1项既有数据库seed契约测试因未设置INSTRUMENT_SEED_TEST_JDBC_URL跳过，0失败/错误。新增发布可见性、终态回收、碰撞查询键、账户身份回滚和批上下文隔离测试全部通过。
- 两次无profiler和最终JMH轮均offered/terminal相等、unfinished0、期末背压排空、峰值256；所有发送若拒绝/错误/超时会直接失败，此三轮未出现该类失败。最终六产品线业务执行→SIGKILL→Archive重放→snapshot重启全部PASS，资金差0，快照位置SPOT3616、两永续9568、两交割与OPTION5184。
- 最终连续profile的8828065业务动作/851105 Core消息，压测后Archive重放、快照恢复均PASS，businessHash ca1b1bca652ba232、snapshotPosition1255928384。恢复再次出现既有Aeron1.53.0 quorum position went backwards（leaderCommitPosition1255242400/quorumPosition0）提示，随后正常LEADER，业务hash和资金正确；未屏蔽该警告。另有JDK25 Unsafe/JMH及SLF4J提示，无新的交易EXCHANGE_CORE_FAILURE。
- 最终主轮两次实测19.7847万/18.8657万business ops/s；profile为19.5845万。不能作为稳定容量验收：主轮2的pmset CPU_Speed_Limit曾70，profile预热时75，停止持续负载后恢复100；接电正常且电池/接电lowpowermode均0，Foundation thermalState为1（Fair）。只能确认系统动态性能限制，不能仅凭该值断言温控的唯一原因或按百分比折算吞吐。主轮0/1系统Swapins增量3004/4729页、Swapouts0/0，Pageouts824/1156页，亦需记录系统干扰；未调整电源/风扇/温控设置、未终止外部Java任务。

- lanes-main-LINEAR_PERPETUAL-0:
  - mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=781 businessHash=d0d255ac72b24be2
  - mixedCapacity=PASS elapsedSeconds=60.073 terminalBusinessOperations=11885312 offeredBusinessOperations=11885312 terminalCoreMessages=1145600 offeredCoreMessages=1145600 businessOpsPerSec=197846.847 coreMessagesPerSec=19070.038 fills=2826240 fillsPerSec=47046.529 queries=0 unfinished=0 peakInFlight=256 measuredCycles=552 totalCycles=781 triggerExecutions=0
  - business=PLACE_ORDER items=282624 requests=282624 p50us=6619 p90us=18382 p95us=20430 p99us=28721 p999us=58327 maxus=318242
  - business=CANCEL_ORDER items=282624 requests=282624 p50us=6410 p90us=18595 p95us=20971 p99us=29245 p999us=45219 maxus=74842
  - business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=10239 p90us=19857 p95us=23461 p99us=31784 p999us=240517 maxus=318242
  - business=PLACE_ORDER_BATCH items=8478720 requests=423936 p50us=16498 p90us=22626 p95us=26640 p99us=36536 p999us=54198 maxus=74973
  - business=CANCEL_ORDER_BATCH items=2826240 requests=141312 p50us=19087 p90us=23871 p95us=28246 p99us=37388 p999us=64225 maxus=265682
  - 批量合计约9409.352 batches/s、188187.039 items/s；平均/最大batch size均20。延迟为入口至终态，单位us，统计样本数为requests；无accepted阶段拆分，不修正coordinated omission。

- lanes-main-LINEAR_PERPETUAL-1:
  - mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=751 businessHash=99000e7555f53e3b
  - mixedCapacity=PASS elapsedSeconds=60.037 terminalBusinessOperations=11326333 offeredBusinessOperations=11326333 terminalCoreMessages=1092477 offeredCoreMessages=1092477 businessOpsPerSec=188656.513 coreMessagesPerSec=18196.790 fills=2693120 fillsPerSec=44857.822 queries=0 unfinished=0 peakInFlight=256 measuredCycles=526 totalCycles=751 triggerExecutions=0
  - business=PLACE_ORDER items=269312 requests=269312 p50us=6639 p90us=19169 p95us=20742 p99us=27426 p999us=44171 maxus=80216
  - business=CANCEL_ORDER items=269312 requests=269312 p50us=6537 p90us=19382 p95us=21463 p99us=30408 p999us=44728 maxus=55836
  - business=APPLY_MARK_PRICE items=15229 requests=15229 p50us=9723 p90us=21135 p95us=25018 p99us=45350 p999us=54919 maxus=55902
  - business=PLACE_ORDER_BATCH items=8079360 requests=403968 p50us=17760 p90us=22970 p95us=27623 p99us=36470 p999us=52363 maxus=82509
  - business=CANCEL_ORDER_BATCH items=2693120 requests=134656 p50us=20267 p90us=23969 p95us=30572 p99us=40894 p999us=89915 maxus=110100
  - 批量合计约8971.534 batches/s、179430.684 items/s；平均/最大batch size均20。延迟为入口至终态，单位us，统计样本数为requests；无accepted阶段拆分，不修正coordinated omission。

- lanes-profile-LINEAR_PERPETUAL-0:
  - mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=602 businessHash=ca1b1bca652ba232
  - mixedCapacity=PASS elapsedSeconds=45.077 terminalBusinessOperations=8828065 offeredBusinessOperations=8828065 terminalCoreMessages=851105 offeredCoreMessages=851105 businessOpsPerSec=195844.941 coreMessagesPerSec=18881.217 fills=2099200 fillsPerSec=46569.401 queries=0 unfinished=0 peakInFlight=256 measuredCycles=410 totalCycles=602 triggerExecutions=0
  - business=PLACE_ORDER items=209920 requests=209920 p50us=6828 p90us=18464 p95us=20250 p99us=29245 p999us=48463 maxus=57475
  - business=CANCEL_ORDER items=209920 requests=209920 p50us=6467 p90us=18530 p95us=20643 p99us=29196 p999us=41615 maxus=49905
  - business=APPLY_MARK_PRICE items=11425 requests=11425 p50us=11919 p90us=20185 p95us=22593 p99us=35651 p999us=44826 maxus=63897
  - business=PLACE_ORDER_BATCH items=6297600 requests=314880 p50us=16793 p90us=23265 p95us=28114 p99us=38862 p999us=54820 maxus=72417
  - business=CANCEL_ORDER_BATCH items=2099200 requests=104960 p50us=19169 p90us=24494 p95us=30408 p99us=44826 p999us=58064 maxus=70647
  - 批量合计约9313.841 batches/s、186276.815 items/s；平均/最大batch size均20。延迟为入口至终态，单位us，统计样本数为requests；无accepted阶段拆分，不修正coordinated omission。

- 最终45.078秒测量窗口JFR：Owner95.878%单核、matcher23.813/23.652%、四Lane96.645–96.659%；整机84.17%、节点57.37%（按16逻辑CPU归一）。Lane14870执行样本中12863(86.50%)是worker循环自身，结算791/准入622/回收50/其他544；实际Lane业务仍未饱和。回收任务50样本约0.34%，不是新同步等待阶段。
- Owner3082样本，方法inclusive占比（不可相加）：preparePipelinedPlaceBatch322(10.45%)、commitTerminalToOwner157(5.09%)，历史相应15.28%/9.47%仅为结构归因参考，非同机无干扰加速证明。dispatchPartitionSettlements484(15.70%)、collectMatcherSettlement261(8.47%)、collectCancel123(3.99%)、publishCommittedChanges284(9.21%)、RuntimeFactIndexes.applyCurrent164(5.32%)、conflictingPrefixSize146(4.74%)。剩余主耗时集中在结算派发/结果收集、全局事实索引和批量收尾，仍受Owner串行预算限制。
- 发布表查询装箱消除：最终Owner的LanePublishedMap.Key分配采样0，未再观察到该表查询站点的分配；线程私有查询键从不插入表，写入键独立。全节点仍非零分配：ObjectAllocationSample加权31960687792B，676.163MiB/s，约3620B/business op。此为采样估计，不能视为精确对象数或TLAB统计。Lane的Version约997MB、Key780MB、CHM Node709MB加权样本，是跨线程已提交可见性的成本；没有把这些新增对象掩盖为零分配。
- 剩余具体分配：Owner批量响应byte[]约947MB、批量OrderBatchItem673MB、ResolvedPlaceOrder约667MB、MatcherSettlementPlan490MB；releaseClientKey的Long约433MB。后者仍是提交后的身份回收路径，异步准入身份准备已经迁入Lane，但没有宣称所有身份回收工作也已迁出Owner。Lane long[]约3.08GB、OrderRuntime3.04GB，需要后续独立剖析；本轮不通过删除资金状态/终态保留语义换吞吐。
- heap最大443.52MiB；GCPhasePause166次/总1189.067ms（约2.64%测量时长）/p99 14.287ms/max16.515ms。YoungGC114，OldGarbageCollection26为G1旧代周期事件，不能直接叫26次FullGC；未做长稳live-set斜率证明。SafepointBegin168次/到达52.113ms/max2.233ms；VM operation170次/1193.706ms/max16.546ms；JIT Compilation1次/113.736ms、Deoptimization10次。Owner无采到ThreadPark/Monitor及同步File/Socket IO事件，事件阈值限制仍在，不宣称任何等待绝对不存在。
- NMT profile前后reserved3140090→3152614KiB、committed691622→727034KiB（+35412KiB），末值Thread2553KiB、Code40367KiB、GC72163KiB、Internal1582KiB、Other9411KiB committed。DirectBufferStatistics始末均8个/9575136B。NMT和DirectBuffer不完整覆盖OS映射文件、Aeron所有native映射或长期泄漏，短测试不能证明无泄漏；未采精确TLAB/非TLAB对象数、OS上下文切换/每线程wall-clock分解、完整三段业务延迟和长稳测试，因此只能交付功能验证与本机性能诊断，非全部生产性能指标验收。
- 最终主轮与六控制轮JFR DataLoss全部0。六产品线控制JMH每invocation100循环、1warmup/2measurement、fork1/thread1/-prof gc，均accountControlVerify=PASS；其分数不是单笔交易耗时。JMH分配是客户端每次完整调用，不是Core每business op。结果如下：
  - lanes-control-INVERSE_DELIVERY-0: 3161.049 ms/op; gc.alloc.rate=0.938, gc.alloc.rate.norm=3147928.0, gc.count=0.0; JFR 831260B SHA256 fa208dae582abe98f5e3b3c0919d492a5bf3359202970396d94099e5fb12a3cc.
  - lanes-control-INVERSE_PERPETUAL-0: 3101.727 ms/op; gc.alloc.rate=1.16, gc.alloc.rate.norm=3826208.0, gc.count=0.0; JFR 835913B SHA256 aad45ec49d7e2ba7770574e2f6f18661e781b355298ab6e0e761a5c5d97fd372.
  - lanes-control-LINEAR_DELIVERY-0: 3147.556 ms/op; gc.alloc.rate=1.151, gc.alloc.rate.norm=3853928.0, gc.count=0.0; JFR 848908B SHA256 4f911d199156f7a6a06771bbd61fcedbe18d87713840f68d96095177c5f9b371.
  - lanes-control-LINEAR_PERPETUAL-0: 3136.177 ms/op; gc.alloc.rate=1.158, gc.alloc.rate.norm=3861052.0, gc.count=0.0; JFR 834402B SHA256 e5caedc16bceb0c1561569833d67367ce6357db80b74ce73418b4aa7cb27608c.
  - lanes-control-OPTION-0: 2304.686 ms/op; gc.alloc.rate=1.036, gc.alloc.rate.norm=2542936.0, gc.count=0.0; JFR 809528B SHA256 732e7770011e84b6e941de289d32e335dd390a5a49a4853f7bb8008b0730a326.
  - lanes-control-SPOT-0: 833.137 ms/op; gc.alloc.rate=1.388, gc.alloc.rate.norm=1267984.0, gc.count=0.0; JFR 708350B SHA256 b7ec0516b2f9cc6a55f9a36f3c59b232f0422e6c162b23cf13d43729561dc7e0.
  - lanes-profile-LINEAR_PERPETUAL-0: 45.078 s/op; gc.alloc.rate=137.562, gc.alloc.rate.norm=16001630032.0, gc.count=206.0, gc.time=200.0; JFR 6460641B SHA256 4f04380f49e7ce8274e7b77883ad21824603ad398f4edac225d73fa5e3ae9416.

- 最终执行：run-final.py main/profile/controls、gates-final.py、recover-final.py，全部位于/tmp/lane-owner-redesign；参数与真实命令由同目录commands.json保留到分析完成，本记录承接其必要摘要。主业务代码69e7c99c，未修改方案一、原项目、README或任何云端资源。首阶段临时perf/gates已清理，最终采集亦在记录完成后停止本轮所有进程并清理Archive/JFR/临时日志/测试报告；上述临时路径随后不可访问，只是历史来源，不作为仍存在的证据链接。


## 2026-09-10 方案二四项 Owner 串行成本消减：采集前锁定

- 被测源：experiment/lane-owned-state，基于 ddf29bba 的本次工作区；仅方案二，不运行方案一或云端。变更为快速批量准入索引按需构建、累计成交一次校验、批输入零数组复制及币对元数据复用、Lane 订单/持仓变更缓冲所有权交接。
- HotSpot Oracle GraalVM 25.0.1、Maven 3.9.16、macOS Intel i9-9880H 8C16T/16GiB；G1，NMT summary。单个真实 Aeron Cluster 成员，网络及 Archive 保留，4 Account Lane、2 matcher、PIPELINED、BUSY_SPIN/spin0、SHARED_NETWORK、service YIELDING；客户端 SHARED。全局和 session in-flight 均256。
- 节点堆512m/1536m，客户端128m/512m；连续流 ClusterMixedCapacityMain，mixed-trading-stream=true、mixed-operational=false，种子131001/131002。采用既有 workload 的用户/symbol/做市资金初始化与业务比例，不改变发单限制。30秒预热、60秒测量，两轮无 profiler；随后相同场景 JMH ClusterOperationalBenchmark.continuousOperations，controlPageSize=0，fork1/wi0/i1，30秒预热45秒测量，-prof gc；节点 JFR profile/maxsize128m。各轮串行，清理上一轮进程后运行下一轮；本轮不额外强制冷却，记录 CPU 限速，受限结果仅用于诊断。
- 六产品线顺序运行 ClusterAccountControlBenchmark.accountControls，fork1/wi1/i2、-prof gc、节点JFR；另顺序执行六产品真实单成员业务、SIGKILL 后日志重放、实际 snapshot 后重启的资金及状态哈希核对。连续流 profile Archive 同样重放与 snapshot 核对。功能门禁 BLOCKING，不以其吞吐作性能结果。
- 必须：所有受影响 Maven 测试通过（数据库 seed 条件跳过单列）；accepted/terminal business 与 Core message 相等、unfinished=0、fundsDiff=0、订单/持仓/冻结正确、全局未完成订单索引无终态残留、重放及快照哈希一致。任何资金/状态失败终止性能结论。性能无预设保证增长，历史197846/188656 ops/s仅背景；CPU限速、swap或DataLoss则不作容量验收。
- 脚本：/tmp/owner-four/{perf,gates,recovery}.py；逐进程完整命令写各case commands.json。运行期间检查磁盘>10GiB、pmset及vm_stat。无独立恒定到达率/CO修正和长稳泄漏门禁，缺失的三段延迟、native池余额不得宣称已验证；本机数据不代表三节点生产上限。
- 产物暂存 /tmp/owner-four；分析后记录汇总与校验值，再清理本轮 Archive/JFR/日志/测试报告。

### 本轮结果（2026-09-10，代码提交 91b24d5d）
四项实现完成，最终 affected reactor `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`：1204 tests，1203通过，0失败/错误，1数据库种子条件跳过。第一次编译暴露新增委托字段名称错误；按需准入索引的随后回归暴露拒绝后转逐笔路径空指针，已修复，最终全受影响 reactor 重新验证通过。未掩盖或删除失败测试。
场景实际初始化1769用户（1000 retail）、256 symbol、batch20，1命令session+1保留查询session，初始资金1768000000125。本轮没有更改负载组合；普通下单/撤单各占每cycle512，批量下单15360 items、批量撤单5120 items，每cycle5120 fills，另有持续标记价格。新增测量结束后的 maker/taker 未完成订单索引查询，确保不存在终态残留。

**lanes-main-LINEAR_PERPETUAL-0**
```text
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=812 businessHash=e53df0ddc316cfcf
mixedCapacity=PASS elapsedSeconds=60.138 terminalBusinessOperations=12057547 offeredBusinessOperations=12057547 terminalCoreMessages=1162187 offeredCoreMessages=1162187 businessOpsPerSec=200498.347 coreMessagesPerSec=19325.371 fills=2867200 fillsPerSec=47677.099 queries=0 unfinished=0 peakInFlight=256 measuredCycles=560 totalCycles=812 triggerExecutions=0
business=PLACE_ORDER items=286720 requests=286720 p50us=6754 p90us=19398 p95us=21315 p99us=27705 p999us=47284 maxus=58753
business=CANCEL_ORDER items=286720 requests=286720 p50us=6709 p90us=17039 p95us=18956 p99us=27574 p999us=39223 maxus=57442
business=APPLY_MARK_PRICE items=15307 requests=15307 p50us=11362 p90us=19972 p95us=22003 p99us=37814 p999us=50331 maxus=56590
business=PLACE_ORDER_BATCH items=8601600 requests=430080 p50us=15974 p90us=20512 p95us=25165 p99us=31686 p999us=49905 maxus=65273
business=CANCEL_ORDER_BATCH items=2867200 requests=143360 p50us=19759 p90us=23756 p95us=28901 p99us=38633 p999us=64159 maxus=67829
```
CPU_Speed_Limit 66～100；系统换页计数增量 {'Swapins': 2768, 'Swapouts': 0}。accepted/terminal相等，unfinished=0，最大在途256，测量边界排空；端到端尾延迟为未做CO修正的闭环观测，未拆accepted阶段，不能当作严格容量验收。

**lanes-main-LINEAR_PERPETUAL-1**
```text
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=706 businessHash=fd8c137f1002a5ae
mixedCapacity=PASS elapsedSeconds=60.023 terminalBusinessOperations=10939136 offeredBusinessOperations=10939136 terminalCoreMessages=1055488 offeredCoreMessages=1055488 businessOpsPerSec=182247.727 coreMessagesPerSec=17584.596 fills=2600960 fillsPerSec=43332.403 queries=0 unfinished=0 peakInFlight=256 measuredCycles=508 totalCycles=706 triggerExecutions=0
business=PLACE_ORDER items=260096 requests=260096 p50us=7614 p90us=21528 p95us=23773 p99us=31932 p999us=52756 maxus=64552
business=CANCEL_ORDER items=260096 requests=260096 p50us=7290 p90us=18513 p95us=20709 p99us=29392 p999us=42369 maxus=53346
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=11534 p90us=22396 p95us=24920 p99us=44007 p999us=54525 maxus=64552
business=PLACE_ORDER_BATCH items=7802880 requests=390144 p50us=17317 p90us=23330 p95us=28311 p99us=41058 p999us=64749 maxus=101646
business=CANCEL_ORDER_BATCH items=2600960 requests=130048 p50us=21561 p90us=26968 p95us=31703 p99us=44859 p999us=86769 maxus=99090
```
CPU_Speed_Limit 64～100；系统换页计数增量 {'Swapins': 3444, 'Swapouts': 0}。accepted/terminal相等，unfinished=0，最大在途256，测量边界排空；端到端尾延迟为未做CO修正的闭环观测，未拆accepted阶段，不能当作严格容量验收。

**lanes-profile-LINEAR_PERPETUAL-0**
```text
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=569 businessHash=319cc756e662260b
mixedCapacity=PASS elapsedSeconds=45.107 terminalBusinessOperations=7817272 offeredBusinessOperations=7817272 terminalCoreMessages=754744 offeredCoreMessages=754744 businessOpsPerSec=173303.224 coreMessagesPerSec=16732.124 fills=1858560 fillsPerSec=41202.921 queries=0 unfinished=0 peakInFlight=256 measuredCycles=363 totalCycles=569 triggerExecutions=0
business=PLACE_ORDER items=185856 requests=185856 p50us=8052 p90us=22249 p95us=24756 p99us=31031 p999us=45842 maxus=58884
business=CANCEL_ORDER items=185856 requests=185856 p50us=7729 p90us=19677 p95us=21970 p99us=30294 p999us=42795 maxus=55050
business=APPLY_MARK_PRICE items=11320 requests=11320 p50us=12140 p90us=23674 p95us=25870 p99us=46891 p999us=85000 maxus=88604
business=PLACE_ORDER_BATCH items=5575680 requests=278784 p50us=18317 p90us=25034 p95us=29605 p99us=38207 p999us=55967 maxus=90963
business=CANCEL_ORDER_BATCH items=1858560 requests=92928 p50us=22577 p90us=28524 p95us=33030 p99us=40435 p999us=55836 maxus=58425
```
CPU_Speed_Limit 64～93；系统换页计数增量 {'Swapins': 960, 'Swapouts': 0}。accepted/terminal相等，unfinished=0，最大在途256，测量边界排空；端到端尾延迟为未做CO修正的闭环观测，未拆accepted阶段，不能当作严格容量验收。

JFR测量窗口1789027482456～1789027527563（45.107秒）。Owner3288样本，dispatchPartitionSettlements415(12.62%)、preparePipelinedPlaceBatch394(11.98%)、collectMatcherSettlement267(8.12%)、collectCancel141(4.29%)、publishCommittedChanges314(9.55%)（含factIndexes175/5.32%）、commitTerminalToOwner176(5.35%)。BatchAdmissionOrderIndex.update仅2个样本，正常PLACE不分配索引的六产品测试通过；顺序准入仍保留必需索引。Lane15062样本中循环自身12787（84.90%），不能视为有效业务饱和。
```text
totals allocationMiBps=592.761 allocationBytes=28036481776 machineCPU=89.79 jvmCPU=57.55 heapMaxMiB=445.36 dataLoss=0 ownerIOEvents=0
threadCPU	96.340	core-account-lane-1
threadCPU	96.327	core-account-lane-2
threadCPU	96.315	core-account-lane-3
threadCPU	96.300	core-account-lane-0
threadCPU	96.044	trading-owner--1
threadCPU	94.692	/tmp/owner-four/perf/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	67.034	clustered-service-101-0
threadCPU	66.254	driver-conductor
threadCPU	60.840	archive-conductor
threadCPU	58.302	consensus-module-101-0
threadCPU	24.126	core-matcher-0
threadCPU	24.077	core-matcher-1
threadCPU	1.650	JVMCI-native CompilerThread0
threadCPU	1.022	aeron-md-nra
threadCPU	0.249	aeron-client
threadCPU	0.091	JFR Periodic Tasks
threadCPU	0.084	JFR Recorder Thread
threadCPU	0.058	C1 CompilerThread0
threadCPU	0.014	Monitor Deflation Thread
gc count=143 totalMs=1149.473 p99Ms=17.451 maxMs=18.875
samples	3847	core-account-lane-0
samples	3807	core-account-lane-1
samples	3763	core-account-lane-2
samples	3645	core-account-lane-3
samples	3288	trading-owner--1
samples	246	driver-conductor
samples	174	clustered-service-101-0
samples	123	consensus-module-101-0
samples	116	archive-conductor
samples	46	/tmp/owner-four/perf/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	27	core-matcher-1
samples	24	core-matcher-0
ownerInclusive	3288	java.lang.Thread.run
ownerInclusive	3288	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x0000000122129a58.run
ownerInclusive	3288	java.lang.Thread.runWith
ownerInclusive	3288	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	3266	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	3246	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	2463	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand
ownerInclusive	2461	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress
ownerInclusive	1989	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	1965	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	1258	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	844	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
allocation	1233119760	trading-owner--1 [B
allocation	780511904	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchItem
allocation	771082752	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	723539008	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	700187280	core-account-lane-2 [J
allocation	676696888	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	672222760	clustered-service-101-0 [B
allocation	668602048	trading-owner--1 [J
allocation	641622800	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	605363720	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	589804856	core-account-lane-3 [J
allocation	577575824	trading-owner--1 com.surprising.aeron.service.state.ResolvedPlaceOrder
allocationSite	2952641688	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	1442049288	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	1395071864	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	1138210744	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	1081928656	java.util.ArrayList.add
allocationSite	995442848	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	976070904	java.util.HashMap.putVal
allocationSite	942317296	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	887484896	com.surprising.aeron.service.state.MatcherSettlementPlan.build
allocationSite	716439744	com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order
allocationSite	715741864	java.nio.ByteBuffer.allocate
allocationSite	698773784	com.surprising.aeron.service.matching.CoreMatchingResult.classify
parkOrMonitorNs	1196092124	aeron-md-nra
parkOrMonitorNs	985227810	core-matcher-1
parkOrMonitorNs	970362422	core-matcher-0
parkOrMonitorNs	344257602	archive-conductor
parkOrMonitorNs	230040735	consensus-module-101-0
parkOrMonitorNs	119528928	driver-conductor
eventCounts	56871	jdk.GCPhaseParallel
eventCounts	19108	jdk.ExecutionSample
eventCounts	15145	jdk.PromoteObjectInNewPLAB
eventCounts	12951	jdk.ObjectAllocationSample
eventCounts	2706	jdk.ThreadSleep
eventCounts	2100	jdk.NativeMethodSample
eventCounts	1515	jdk.TenuringDistribution
eventCounts	1215	jdk.NativeMemoryUsage
eventCounts	492	jdk.GCPhasePauseLevel1
eventCounts	488	jdk.MetaspaceChunkFreeListSummary
eventCounts	488	jdk.GCReferenceStatistics
eventCounts	330	jdk.ThreadPark

```
```text
laneSamples=15062 laneLoopSelf=12787
jdk.Deoptimization count=6 totalMs=0.0 maxMs=0.0
jdk.DirectBufferStatistics count=9 totalMs=0.0 maxMs=0.0
jdk.ExecuteVMOperation count=147 totalMs=1154.222283 maxMs=18.928909
jdk.FileWrite count=74 totalMs=642.58089 maxMs=22.141846
jdk.GCHeapSummary count=244 totalMs=0.0 maxMs=0.0
jdk.GarbageCollection count=122 totalMs=2094.809677 maxMs=57.29108
jdk.NativeMemoryUsageTotal count=45 totalMs=0.0 maxMs=0.0
jdk.SafepointBegin count=145 totalMs=51.113855 maxMs=2.150418
jdk.DirectBufferStatistics {
  startTime = 16:05:25.005 (2026-09-10)
  maxCapacity = 1.5 GB
  count = 8
  totalCapacity = 9.1 MB
  memoryUsed = 9.1 MB
}


jdk.NativeMemoryUsageTotal {
  startTime = 16:05:27.012 (2026-09-10)
  reserved = 3.0 GB
  committed = 704.6 MB
}



```
分配为ObjectAllocationSample加权估算：28036481776/7817272=约3586.48 bytes/business op；非精确对象计数。profile未启用逐次TLAB事件，不推断对象数/op、最大对象或零分配。JMH -prof gc测量的是独立客户端fork，不是Core节点：该轮gc.alloc.rate=128.465MiB/s、gc.alloc.rate.norm=15201357576B/JMH调用、gc.count196/gc.time214ms；一次JMH调用包含整段场景，不能将B/JMH调用当B/交易。单fork单iteration无可靠置信区间。
NMT节点reserved/committed由3146306/691798 KiB变为3150036/725160 KiB，committed增33362KiB；堆committed由526336降524288KiB。末尾DirectBuffer8个、capacity/memoryUsed约9.1MB。没有Aeron全部映射/文件描述符/池余额与长期斜率，因此不宣称无泄漏；短采样未见Owner文件/网络IO和DataLoss。GC最大暂停18.875ms，影响尾延迟；VM operation最长18.929ms。未收集逐类长期live-set、完整墙钟状态及native峰值，不作完整性能验收。

六产品账户控制JMH（单次调用100 cycles，以下ms/op指整个调用）均accountControlVerify=PASS；fork1/wi1/i2、-prof gc，节点均JFR。
- lanes-control-INVERSE_DELIVERY-0: 3059.775 ms/op; client allocation 1.106 MiB/s，gc.count=0.0.
- lanes-control-INVERSE_PERPETUAL-0: 3125.103 ms/op; client allocation 0.986 MiB/s，gc.count=0.0.
- lanes-control-LINEAR_DELIVERY-0: 3146.376 ms/op; client allocation 0.988 MiB/s，gc.count=0.0.
- lanes-control-LINEAR_PERPETUAL-0: 3107.790 ms/op; client allocation 1.177 MiB/s，gc.count=0.0.
- lanes-control-OPTION-0: 2355.180 ms/op; client allocation 1.029 MiB/s，gc.count=0.0.
- lanes-control-SPOT-0: 830.707 ms/op; client allocation 1.301 MiB/s，gc.count=0.0.

六产品单成员 execute→SIGKILL→日志重放→snapshot重启全部通过，fundsDiff0、bookLevels0；snapshot位置SPOT3616、永续9568、交割及期权5184。连续profile总569cycles，重放/快照恢复businessHash均319cc756e662260b，snapshotPosition1187753632。恢复出现既有Aeron WARN `quorum position went backwards: leaderCommitPosition=1186969344 quorumPosition=0`，未抑制；恢复终态与资金哈希一致。另有JDK25 Unsafe弃用、SLF4J无provider等依赖警告，未出现EXCHANGE_CORE_FAILURE。

结论：四项目标的生产代码已落实并通过正确性/恢复门禁；本机两轮200498/182248 terminal business ops/s，受64～100 CPU限速及同机干扰影响，只作诊断。Owner仍96.0%单核、matcher约24%，没有实现全线程有效工作饱和。历史dispatch约15.70%对本次12.62%、历史约3620B/op对本次3586B/op仅归因背景，不构成受控收益结论。剩余主要串行开销仍包括批量完成、准入准备、全局有序索引发布和响应构造；本任务没有删除正确性要求或以延迟索引发布换吞吐。未运行方案A、三节点或云端，未做长稳泄漏/HTTP/WebSocket验收。

产物校验（以下原始文件在记录后清理，不再作为可访问链接）：
- perf/lanes-control-INVERSE_DELIVERY-0/node.jfr: 836873 bytes, SHA256 a5a4378ccfe12d9e63d13b1c7afb610877243952920b93a7c96516812edf62cc
- perf/lanes-control-INVERSE_PERPETUAL-0/node.jfr: 845956 bytes, SHA256 9564b1c64eae2aa1f6668c7555c3ae5d3f0605f33728890273a1a9061f682366
- perf/lanes-control-LINEAR_DELIVERY-0/node.jfr: 844085 bytes, SHA256 49b1323ba49a74e4a6327ded942aff0b390bab6a4eb39c0e52183769d9ca36fb
- perf/lanes-control-LINEAR_PERPETUAL-0/node.jfr: 834728 bytes, SHA256 58faa6d34b31ed7f9eeed207a721e8701c89c6fc089df262ea838158c26b1162
- perf/lanes-control-OPTION-0/node.jfr: 814024 bytes, SHA256 a90bac4731f71dc4a3076d23fa588404c19d8901561b97d7db877c445e7d2698
- perf/lanes-control-SPOT-0/node.jfr: 721533 bytes, SHA256 328d91ff411c04767dca59867f121c6dfa9cc2e877f03fc2e08091c898d8e452
- perf/lanes-profile-LINEAR_PERPETUAL-0/node.jfr: 6191970 bytes, SHA256 ad9435b6d5c1e655e9dae57fd25e013c84390eb005877713d57576b1ff3a5941
- verify-fixed.log: SHA256 7ac7b5953141cae2e4c53a1475b2d8e26cf39912a0f37f85a88dc22adde02e58
- perf.py: SHA256 01f3a63825bcdbf72c0f77db5f436d3e3b246d571b857e16c91ffb962f511318
- gates.py: SHA256 6229037b4eee826e5bb58faf44f61df51fd038917f0ffcc00feadadac3933a70
- recovery.py: SHA256 1f1fed1d7e0ea620567d09ef752ef55de7829844f3c0c8003e8223b66505d96e
```text
325279af3bd8ab801970553539b543a70f451c22ad8e9f3585d9b69ff3822864 surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar
98a995e34e27ae515fe65ee6fe5c78b757e626c420527eda95125d3083470ae9 surprising-aeron-core/surprising-aeron-benchmarks/target/original-product-core-benchmarks.jar
9f811e8ca26928cb080b294c0c95ddef1a32065cf36a331d20e8fdf6bf53c52d surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar
```

清理完成：本轮进程均已退出，/tmp/owner-four下Archive/JFR/日志/临时分析器全部删除，已清理9个本轮Maven报告目录与本轮生成的SnapshotControl.class；构建JAR和业务源码保留。


## 2026-09-10 Owner/matcher/Lane 工作量差异定位（采集前锁定）

当前代码91b24d5d/a62cf077，只诊断方案二，不改生产逻辑。复用上一条单成员真实网络+Archive场景：HotSpot GraalVM25.0.1、G1/NMT、Intel8C16T16GiB，4 Lane/2 matcher、PIPELINED、BUSY_SPIN/spin0、SHARED_NETWORK、service YIELDING，节点512m/1536m，客户端128m/512m/SHARED，256全局与session在途、1命令session+1查询session、256symbols/1769users/batch20、seed131001、mixed-trading-stream=true/mixed-operational=false；JMH continuousOperations fork1/wi0/i1/controlPageSize0/-prof gc，内部预热30s测量45s，节点JFR profile/maxsize128m。逐组统计互斥Owner阶段、叶子方法、等待/空转和分配调用栈，禁止把inclusive百分比相加。资金/终态不变量必须PASS；CPU限速时只解释栈结构，不比较绝对容量。脚本/tmp/owner-deepdiag/run.py，完整命令存case commands.json。无生产改动不重复六产品回归及恢复，沿用同一代码上一轮已通过的证据；没有独立队列驻留计数，不能从低CPU单独推出队列空。测试结束记录后清理本轮产物。

### 定位结果
本轮HotSpot25单成员JMH/JFR复核完成；CPU_Speed_Limit 68～100，仅诊断，不作吞吐收益/上限结论。生产代码未修改。
```text
lanes profile 0 mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=643 businessHash=f3a86d45235df011
lanes profile 0 mixedCapacity=PASS elapsedSeconds=45.076 terminalBusinessOperations=8591543 offeredBusinessOperations=8591543 terminalCoreMessages=828599 offeredCoreMessages=828599 businessOpsPerSec=190600.931 coreMessagesPerSec=18382.232 fills=2042880 fillsPerSec=45320.710 queries=0 unfinished=0 peakInFlight=256 measuredCycles=399 totalCycles=643 triggerExecutions=0
lanes profile 0 business=PLACE_ORDER items=204288 requests=204288 p50us=7344 p90us=20086 p95us=21987 p99us=29982 p999us=48431 maxus=72744
lanes profile 0 business=CANCEL_ORDER items=204288 requests=204288 p50us=6955 p90us=17809 p95us=20086 p99us=29687 p999us=45744 maxus=65404
lanes profile 0 business=APPLY_MARK_PRICE items=11447 requests=11447 p50us=11747 p90us=21528 p95us=26869 p99us=42500 p999us=69599 maxus=70975
lanes profile 0 business=PLACE_ORDER_BATCH items=6128640 requests=306432 p50us=16580 p90us=22413 p95us=27656 p99us=37584 p999us=57475 maxus=65896
lanes profile 0 business=CANCEL_ORDER_BATCH items=2042880 requests=102144 p50us=20512 p90us=26591 p95us=31686 p99us=41582 p999us=53641 maxus=72417
finished lanes-profile-LINEAR_PERPETUAL-0
```
Owner3248个样本按最内层识别阶段互斥归类（每个样本仅一次，不是精确计时；other含未细分协调/轮询）：
- other: 719 / 3248 = 22.14%
- lane-result-collection: 459 / 3248 = 14.13%
- admission-other: 413 / 3248 = 12.72%
- dependency-window: 348 / 3248 = 10.71%
- batch-place-preparation: 232 / 3248 = 7.14%
- settlement-plan-dispatch: 209 / 3248 = 6.43%
- settlement-dispatch-other: 171 / 3248 = 5.26%
- global-index-publication: 164 / 3248 = 5.05%
- batch-finish-other: 155 / 3248 = 4.77%
- command-decoding: 116 / 3248 = 3.57%
- publication-other: 111 / 3248 = 3.42%
- response-encoding: 103 / 3248 = 3.17%
- result-ledger: 34 / 3248 = 1.05%
- wait-idle: 9 / 3248 = 0.28%
- funds-check: 5 / 3248 = 0.15%

线程CPU：Owner96.10%单核，matcher24.41/24.32%，Lane各约96.35%；Lane14848样本，12627(85.04%)叶子为worker.run，12419定位于SettlementLaneWorker.java:146的队列轮询，未出现大规模handoff等待分支热点。matcher的BUSY_SPIN并未随Lane配置启用：固定64次自旋后parkNanos100000ns，仅队列已消费完时执行；低CPU与Lane高CPU不可直接作为有效工作量比较。park记录matcher约0.88/0.90s为事件阈值下界，不能据此推导总等待比例或队列驻留时间。
正常pollCommandPrefix/pollControl调用commitReadyMatching均awaitFirst=false，不能将源码里awaitAnyMatchingCommitReady存在误判为在线同步等待。Owner已识别wait-idle仅9样本/0.28%，但未细分协调22.14%，不能把全部其余CPU宣布为纯业务计算。
具体证据：ClusterCommandWindow.conflictingPrefixSize反向扫描窗口、symbol/账户匹配和订单索引，156个叶子样本(4.80%)，inclusive164；同一个未获准candidate可由progressCommandsInScope继续检查，不能删除依赖校验。RuntimeState.assertAccountLanesHealthy82个叶子样本(2.52%)，从commitReadyMatching、TradingCoreRuntime.apply多入口逐次遍历所有Lane读取failure，适合共享首次故障发布+保留fail-closed语义，不能直接取消检测。TerminalStateRetention.containsOrder90、retainPrunedOrder58、trimTombstones68 inclusive样本来自准入/结果收集，不能叠加到互斥表；保留终态去重必要，但EntityKey/ClientIdentity/RetainedEntity/LinkedHashMap.Entry逐订单创建与淘汰可以用固定容量primitive结构压缩。
Owner准入preparePipelinedPlaceBatch仍逐项做终态身份查询、持仓/平仓容量前置检查、决策参数解析和CoreMatchingOrder构造。结果收集后仍有全局有序索引发布、终态去重保留、response编码；批量输入按20items展开，上游和下游Owner工作多于matcher单次订单簿处理。建议下一轮优先依赖扫描与重复健康检查，再终态身份容器及账户准入前置重复读取；后续更大改造是Lane产出提交结果、将纯编码移到响应出口，必须保留账户依赖、全局索引和去重可见性。当前证据不支持通过单纯让matcher空转到95%解决吞吐。未获取队列深度时间序列、恒定到达率/CO修正，不宣称已精确测出每阶段吞吐容量。
叶子热点与Owner分配站点（allocation为JFR加权估计，站点受JIT内联影响）：
```text
leaf	156	dependency-window @ com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize
leaf	82	other @ com.surprising.aeron.service.state.TradingRuntimeState.assertAccountLanesHealthy
leaf	74	command-decoding @ java.util.ArrayList.add
leaf	71	other @ com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
leaf	67	admission-other @ java.util.concurrent.ConcurrentHashMap.get
leaf	43	batch-place-preparation @ java.util.HashMap.getNode
leaf	41	settlement-dispatch-other @ com.surprising.aeron.service.execution.PendingMatchingRing.partitionDispatchHead
leaf	41	batch-place-preparation @ java.util.HashMap.hash
leaf	41	lane-result-collection @ org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each
leaf	40	settlement-plan-dispatch @ com.surprising.aeron.service.state.MatcherSettlementPlan.build
leaf	38	other @ com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
leaf	37	publication-other @ java.util.Arrays.fill
leaf	37	batch-place-preparation @ com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
leaf	34	lane-result-collection @ java.util.concurrent.ConcurrentHashMap.get
leaf	34	other @ com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
leaf	34	admission-other @ com.surprising.aeron.service.state.LanePublishedMap.visible
leaf	32	lane-result-collection @ java.lang.invoke.DirectMethodHandle$Holder.invokeStatic
leaf	31	admission-other @ java.lang.ThreadLocal.get
leaf	29	lane-result-collection @ java.util.Arrays.fill
leaf	28	settlement-plan-dispatch @ java.util.concurrent.ConcurrentHashMap.get
leaf	27	dependency-window @ java.util.HashMap.getNode
leaf	26	lane-result-collection @ com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed
leaf	26	command-decoding @ com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
leaf	25	response-encoding @ com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
leaf	25	lane-result-collection @ java.util.concurrent.ConcurrentHashMap.replaceNode
leaf	22	batch-finish-other @ java.util.Arrays.fill
leaf	21	other @ java.util.HashMap.hash
leaf	21	lane-result-collection @ com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
leaf	21	dependency-window @ com.surprising.aeron.service.execution.ClusterCommandWindow.add
leaf	20	other @ com.surprising.aeron.service.execution.MatcherPipelineGroup.drainMatchingCompletions
leaf	19	response-encoding @ java.nio.HeapByteBuffer.put
leaf	19	lane-result-collection @ java.util.HashMap.putVal
leaf	19	global-index-publication @ org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.add
leaf	19	lane-result-collection @ com.surprising.aeron.service.state.RuntimeChangeBuffer.resetIndex
leaf	19	other @ com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
leaf	18	lane-result-collection @ com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey
leaf	18	settlement-plan-dispatch @ org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.add
leaf	18	admission-other @ com.surprising.aeron.service.execution.MatchingCommandAdmission.prepareMatching
leaf	17	other @ com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
leaf	17	batch-place-preparation @ com.surprising.aeron.service.state.CoreOrderDecisionResolver.resolve
leaf	17	response-encoding @ jdk.internal.misc.Unsafe.putIntUnaligned
leaf	17	settlement-dispatch-other @ com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchPartitionSettlements
leaf	16	other @ com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchReadyPlaceSettlements
leaf	16	other @ java.util.HashMap.getNode
leaf	16	publication-other @ com.surprising.aeron.service.state.TradingRuntimeState.clearCapturedChanges
leaf	15	settlement-dispatch-other @ java.util.ArrayList.add
leaf	15	dependency-window @ com.surprising.aeron.service.execution.TradingCoreRuntime.addPlaceScope
leaf	14	other @ com.surprising.aeron.service.execution.MatcherCommandPipeline.completedMatchingSequence
leaf	14	other @ com.surprising.aeron.service.execution.TradingCoreRuntime.bindOwner
leaf	14	batch-finish-other @ java.util.concurrent.ConcurrentHashMap.get
leaf	14	admission-other @ com.surprising.aeron.service.execution.OrderBatchExecutor.decodeOrderBatch
leaf	14	lane-result-collection @ java.util.LinkedHashMap$LinkedHashIterator.remove
leaf	13	result-ledger @ java.util.HashMap.getNode
leaf	13	batch-place-preparation @ com.surprising.aeron.service.state.model.AssetBalance.validAsset
leaf	13	other @ java.util.Arrays.fill
leaf	13	batch-place-preparation @ java.util.HashMap.containsKey
leaf	13	response-encoding @ com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length
leaf	13	admission-other @ java.util.HashMap.putVal
leaf	13	dependency-window @ java.util.concurrent.ConcurrentHashMap.get
leaf	13	global-index-publication @ org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probeThree
leaf	13	batch-place-preparation @ com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length
leaf	13	global-index-publication @ org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.remove
leaf	13	admission-other @ java.util.HashMap.getNode
leaf	13	settlement-plan-dispatch @ com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch
leaf	12	lane-result-collection @ com.surprising.aeron.service.execution.TerminalStateRetention.trimTombstones
ownerAllocation	982919056	[B @ com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
ownerAllocation	724054776	com.surprising.aeron.service.execution.OrderBatchItem @ com.surprising.aeron.service.execution.OrderBatchExecutor.decodeOrderBatch
ownerAllocation	569589896	com.surprising.aeron.service.state.ResolvedPlaceOrder @ com.surprising.aeron.service.state.model.AssetBalance.validAsset
ownerAllocation	528842664	com.surprising.aeron.service.state.MatcherSettlementPlan @ com.surprising.aeron.service.state.MatcherSettlementPlan.build
ownerAllocation	498908568	com.surprising.aeron.protocol.PlaceOrderCommand @ com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
ownerAllocation	497711720	[B @ com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
ownerAllocation	371347576	java.lang.Long @ com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey
ownerAllocation	339096712	com.surprising.aeron.service.execution.TerminalStateRetention$RetainedEntity @ com.surprising.aeron.service.execution.TerminalStateRetention.retainPrunedOrder
ownerAllocation	275680680	java.util.LinkedHashMap$Entry @ com.surprising.aeron.service.execution.TerminalStateRetention.retainPrunedOrder
ownerAllocation	269482256	com.surprising.aeron.service.matching.CoreMatchingOrder @ com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
ownerAllocation	263080504	[J @ com.surprising.aeron.service.state.index.ActiveOrderIndex.add
ownerAllocation	249670688	java.lang.String @ com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
ownerAllocation	234268272	[J @ com.surprising.aeron.service.state.MatcherSettlementPlan.build
ownerAllocation	221069568	java.util.HashMap$Node @ com.surprising.aeron.service.execution.TerminalStateRetention.indexTombstone
ownerAllocation	192857856	com.surprising.aeron.service.execution.TerminalStateRetention$ClientIdentity @ com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length
ownerAllocation	148840536	[B @ com.surprising.aeron.protocol.CoreCommandResultCodec.encode
ownerAllocation	145631712	com.surprising.aeron.service.state.PositionCloseCapacity @ com.surprising.aeron.service.state.PositionCloseCapacity.inspectRuntime
ownerAllocation	136428392	com.surprising.aeron.service.execution.TerminalStateRetention$EntityKey @ com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
ownerAllocation	123171440	com.surprising.aeron.service.execution.TerminalStateRetention$EntityKey @ com.surprising.aeron.service.execution.TerminalStateRetention.retainPrunedOrder
ownerAllocation	121810792	com.surprising.aeron.service.execution.TerminalStateRetention$ClientIdentity @ com.surprising.aeron.service.execution.TerminalStateRetention.indexTombstone
ownerAllocation	111120136	com.surprising.aeron.protocol.CoreOrderStateView @ com.surprising.aeron.service.state.RuntimeIdentityRegistry.preparedSymbol
ownerAllocation	105852624	com.surprising.aeron.protocol.CancelOrderCommand @ com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
ownerAllocation	98152160	[I @ com.surprising.aeron.service.state.TradingRuntimeState.appendFundsDelta
ownerAllocation	91753040	com.surprising.aeron.protocol.CoreMessageHeader @ com.surprising.aeron.protocol.CoreMessageHeader.response
ownerAllocation	68992856	[Ljava.lang.Object; @ com.surprising.aeron.protocol.PlaceOrderBatchCommand.<init>
cpu core-account-lane-0 96.35530114173889
cpu core-account-lane-1 96.32548838853836
cpu core-account-lane-2 96.37057483196259
cpu core-account-lane-3 96.34715169668198
cpu core-matcher-0 24.406088888645172
cpu core-matcher-1 24.322157725691795
cpu trading-owner--1 96.09595239162445
```
本轮资金差额0，accepted/terminal business及Core消息均相等，unfinished0，峰值在途256，DataLoss0。代码未变化，不重复前一轮已通过的六产品及重放快照测试。采样脚本SHA256 c62999d20e65695cc0b45cee610696fd299e3cfb7637b5cc8bdd7b99486506dd；节点JFR 6585486 bytes SHA256 523fa45be4457057abd9443e023a2c780a862086a068eaacfbb2b482490e8c3c；OwnerStages.java SHA256 26f6983ded15d603ff25cf382b4e052a907e64e849d9681603f95ca16d7281f4。原产物/tmp/owner-deepdiag在记录后清理，不再可访问。

本轮诊断进程均已退出，/tmp/owner-deepdiag及其Archive、JFR、日志、临时分析器已清理；未改生产代码及构建JAR。


## 2026-09-10 Lane 准入及结果交接、Owner 精简（采集前锁定）

- 源码基于1b98d76c，仅方案二当前工作区。Owner按批/币对固定决策上下文，Lane解析订单、检查账户和平仓容量、冻结并构建撮合输入；最终响应订单在Lane准备，保留原终态淘汰返回null约定；删除批量到普通单结果容器的重复ID复制；收集只访问相关Lane；依赖窗口按掩码位图定位候选槽后精确判断；首次Lane故障统一发布；终态去重查询/淘汰复用专用查询键。
- 沿用前轮标准：HotSpot GraalVM25.0.1/Maven3.9.16，Intel i9-9880H8C16T/16GiB/macOS，G1/NMT summary，真实Aeron单成员（网络/Archive/Core保留），4 Lane、2 matcher、PIPELINED、BUSY_SPIN/spin0、SHARED_NETWORK、service YIELDING；节点512m/1536m，客户端128m/512m/SHARED。256全局与session在途，1命令session+1查询session，256symbol、1769user、batch20、seed131001/131002、持续做市资金初始1768000000125。mixed-trading-stream=true/mixed-operational=false，沿用原循环动作比例不改发单窗口。
- 先六产品逐一执行真实业务/SIGKILL重放/实际快照重启门禁（BLOCKING）；然后两轮main30s预热60s测量；JMH continuousOperations fork1/wi0/i1/controlPageSize0/-prof gc，内部30s预热45s测量、节点JFR profile/maxsize128m；六产品accountControls fork1/wi1/i2/-prof gc/JFR；连续流profile日志重放/快照恢复校验。全部串行，不启动本机三节点或云端。无额外强制冷却，记录CPU限速/vm_stat/磁盘>10GiB。
- 阈值：受影响reactor全部通过，资金差额0、accepted=terminal business/Core、unfinished=0、批返回订单账户/币对和成交账户一致、未完成索引无终态残留、重放/快照哈希一致。有错即失败；CPU限速/swap/DataLoss时无容量验收结论。不预设吞吐增长幅度；上一轮数字仅背景，分阶段JFR比例不可直接相加。无CO修正、无完整三段延迟/长稳泄漏/native池余额，结果限定本机诊断。
- /tmp/owner-lane-completion/{gates,perf,recovery}.py，完整命令记录case commands.json。最终验证记录后停止进程并清理本轮产物。README不变。

### 验证结果：c8943e9a
最终HotSpot25 affected reactor verify：1208 tests，1207通过，0失败/错误，1 InstrumentSeedCoreContractTest数据库条件跳过。首次编译修正局部变量slot重名；首轮回归发现Lane带回已淘汰终态对象改变原响应null约定，已按原协议修复，保留旧测试断言；健康探测测试改为注入首次故障信号，并另加真实Lane异常传播测试。随后完整验证通过，最终增加返回账户/币对/成交身份校验后再次验证全部通过。最后仅修正文档注释描述Lane写结果的所有权。
修改范围：快速PLACE批的逐项决策/账户平仓容量检查和撮合输入构造移到Lane；Owner固定一次每币对的行情、费率、资产ID和全局持仓量上下文。遇reduce-only关闭容量冲突仍回到原有有序撤单准入；普通独立命令和特殊逐项批量路径保留原契约。Lane在最终元数据提交阶段准备结果槽，Owner通过acquire完成回执后编码，不借用会复用的事件缓冲；已淘汰订单保持null。移除batch向普通命令结果容器的重复ID复制。Owner收集只访问涉及Lane，未索引预留不重复逐plan查询。依赖槽位图先筛候选，仍做精确订单/账户/价格依赖检查；故障信号保留fail-closed；终态查询键绝不入表，不改变去重/淘汰顺序及snapshot编码。

**lanes-main-LINEAR_PERPETUAL-0**
```text
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=851 businessHash=19cd157be5be0610
mixedCapacity=PASS elapsedSeconds=60.051 terminalBusinessOperations=13562624 offeredBusinessOperations=13562624 terminalCoreMessages=1305344 offeredCoreMessages=1305344 businessOpsPerSec=225850.807 coreMessagesPerSec=21737.165 fills=3225600 fillsPerSec=53714.116 queries=0 unfinished=0 peakInFlight=256 measuredCycles=630 totalCycles=851 triggerExecutions=0
business=PLACE_ORDER items=322560 requests=322560 p50us=6283 p90us=18939 p95us=20561 p99us=25165 p999us=41746 maxus=51314
business=CANCEL_ORDER items=322560 requests=322560 p50us=6033 p90us=14647 p95us=16162 p99us=25296 p999us=33505 maxus=57606
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=9928 p90us=17891 p95us=19824 p99us=29818 p999us=40501 maxus=51838
business=PLACE_ORDER_BATCH items=9676800 requests=483840 p50us=13402 p90us=17039 p95us=22364 p99us=29294 p999us=49643 maxus=58851
business=CANCEL_ORDER_BATCH items=3225600 requests=161280 p50us=18104 p90us=22560 p95us=27508 p99us=35848 p999us=48726 maxus=57540
```
CPU_Speed_Limit=70～100；系统换页计数增量{'Swapins': 128, 'Swapouts': 0}。终态business/Core均与接收相等、unfinished0、最大在途256、边界排空。

**lanes-main-LINEAR_PERPETUAL-1**
```text
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=849 businessHash=d7709fa70d8410c4
mixedCapacity=PASS elapsedSeconds=60.044 terminalBusinessOperations=12337038 offeredBusinessOperations=12337038 terminalCoreMessages=1188750 offeredCoreMessages=1188750 businessOpsPerSec=205464.975 coreMessagesPerSec=19797.823 fills=2933760 fillsPerSec=48859.777 queries=0 unfinished=0 peakInFlight=256 measuredCycles=573 totalCycles=849 triggerExecutions=0
business=PLACE_ORDER items=293376 requests=293376 p50us=6914 p90us=20856 p95us=22691 p99us=28557 p999us=47382 maxus=67567
business=CANCEL_ORDER items=293376 requests=293376 p50us=6668 p90us=16089 p95us=17760 p99us=26591 p999us=36798 maxus=53182
business=APPLY_MARK_PRICE items=15246 requests=15246 p50us=10747 p90us=20348 p95us=23740 p99us=32391 p999us=39845 maxus=46759
business=PLACE_ORDER_BATCH items=8801280 requests=440064 p50us=14770 p90us=19251 p95us=24412 p99us=32292 p999us=46989 maxus=63045
business=CANCEL_ORDER_BATCH items=2933760 requests=146688 p50us=19791 p90us=24674 p95us=28999 p99us=37224 p999us=54624 maxus=69533
```
CPU_Speed_Limit=66～100；系统换页计数增量{'Swapins': 320, 'Swapouts': 0}。终态business/Core均与接收相等、unfinished0、最大在途256、边界排空。

**lanes-profile-LINEAR_PERPETUAL-0**
```text
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=661 businessHash=7b76cfca8088dfef
mixedCapacity=PASS elapsedSeconds=45.064 terminalBusinessOperations=8677592 offeredBusinessOperations=8677592 terminalCoreMessages=836824 offeredCoreMessages=836824 businessOpsPerSec=192561.954 coreMessagesPerSec=18569.721 fills=2063360 fillsPerSec=45787.430 queries=0 unfinished=0 peakInFlight=256 measuredCycles=403 totalCycles=661 triggerExecutions=0
business=PLACE_ORDER items=206336 requests=206336 p50us=7520 p90us=21987 p95us=23953 p99us=31506 p999us=51576 maxus=73531
business=CANCEL_ORDER items=206336 requests=206336 p50us=7053 p90us=16859 p95us=18546 p99us=28459 p999us=41844 maxus=56950
business=APPLY_MARK_PRICE items=11480 requests=11480 p50us=12034 p90us=21315 p95us=24854 p99us=32899 p999us=71827 maxus=91422
business=PLACE_ORDER_BATCH items=6190080 requests=309504 p50us=15572 p90us=20529 p95us=26050 p99us=35422 p999us=71172 maxus=104267
business=CANCEL_ORDER_BATCH items=2063360 requests=103168 p50us=21053 p90us=26869 p95us=31899 p99us=45350 p999us=73531 maxus=80478
```
CPU_Speed_Limit=66～100；系统换页计数增量{'Swapins': 319, 'Swapouts': 0}。终态business/Core均与接收相等、unfinished0、最大在途256、边界排空。

JFR执行样本计数：sampleTotals={lane=14839, laneLoopSelf=12172, matcher=44, owner=3212, peripheral=859}。以下inclusive指标包含子调用，不相加。
```text
834	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
416	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchPartitionSettlements
312	com.surprising.aeron.service.execution.OrderedCommitCoordinator.publishCommittedChanges
297	com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
179	com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize
173	com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
156	com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
90	com.surprising.aeron.service.execution.TerminalStateRetention.trimTombstones
62	com.surprising.aeron.service.execution.TerminalStateRetention.containsOrder
36	com.surprising.aeron.service.state.CoreOrderDecisionResolver.context
17	com.surprising.aeron.service.execution.ClusterCommandWindow.indexSlots
14	com.surprising.aeron.service.state.CoreOrderDecisionResolver.resolve
4	com.surprising.aeron.service.state.TradingRuntimeState.assertAccountLanesHealthy
```
Owner批量准备173/3212=5.39%（前轮约12%）；健康检查4/3212=0.12%（前轮约2.5%）；Lane看到PlaceBatchAdmissionEvent.execute977、CoreOrderDecisionResolver.resolve25、LaneOrderResultTarget.capture46样本。依赖检查179/3212=5.57%，并未显示下降，位图碰撞后仍须精确匹配，不能声称全部热点消失。
```text
totals allocationMiBps=654.422 allocationBytes=30923399872 machineCPU=87.25 jvmCPU=59.11 heapMaxMiB=443.82 dataLoss=0 ownerIOEvents=0
threadCPU	96.512	core-account-lane-3
threadCPU	96.506	core-account-lane-0
threadCPU	96.503	core-account-lane-2
threadCPU	96.488	core-account-lane-1
threadCPU	96.299	trading-owner--1
threadCPU	96.067	/tmp/owner-lane-completion/perf/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	73.840	clustered-service-101-0
threadCPU	73.136	driver-conductor
threadCPU	65.692	archive-conductor
threadCPU	63.593	consensus-module-101-0
threadCPU	26.197	core-matcher-0
threadCPU	26.121	core-matcher-1
threadCPU	1.808	JVMCI-native CompilerThread0
threadCPU	0.980	aeron-md-nra
threadCPU	0.245	aeron-client
threadCPU	0.087	C1 CompilerThread0
threadCPU	0.085	JFR Periodic Tasks
threadCPU	0.081	JFR Recorder Thread
threadCPU	0.013	Monitor Deflation Thread
gc count=159 totalMs=1251.015 p99Ms=16.765 maxMs=17.758
samples	3816	core-account-lane-0
samples	3766	core-account-lane-1
samples	3686	core-account-lane-2
samples	3571	core-account-lane-3
samples	3212	trading-owner--1
samples	293	driver-conductor
samples	199	clustered-service-101-0
samples	156	consensus-module-101-0
samples	140	archive-conductor
samples	66	/tmp/owner-lane-completion/perf/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	24	core-matcher-0
samples	20	core-matcher-1
ownerInclusive	3212	java.lang.Thread.run
ownerInclusive	3212	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x0000000133129a58.run
ownerInclusive	3212	java.lang.Thread.runWith
ownerInclusive	3212	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	3169	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	3154	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	2326	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand
ownerInclusive	2317	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress
ownerInclusive	2035	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	1986	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	1261	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	860	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
allocation	1528976032	trading-owner--1 [B
allocation	821910520	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	803651520	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	798178656	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	795824488	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	786880912	core-account-lane-0 [J
allocation	757630120	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchItem
allocation	743858000	clustered-service-101-0 [B
allocation	740645616	core-account-lane-3 [J
allocation	736117192	core-account-lane-1 [J
allocation	718288304	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	668931168	core-account-lane-2 [J
allocationSite	2894884208	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	1735505784	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	1683508544	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	1304827952	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	1283512896	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	1249331448	java.util.ArrayList.add
allocationSite	1203213912	java.util.HashMap.putVal
allocationSite	1199554936	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	928256224	java.nio.ByteBuffer.allocate
allocationSite	831056680	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	777755968	com.surprising.aeron.service.state.model.AssetBalance.validAsset
allocationSite	743553136	com.surprising.aeron.service.state.MatcherSettlementPlan.build
parkOrMonitorNs	1277767658	aeron-md-nra
parkOrMonitorNs	901391495	core-matcher-1
parkOrMonitorNs	801423730	core-matcher-0
parkOrMonitorNs	386478417	consensus-module-101-0
parkOrMonitorNs	234082180	archive-conductor
parkOrMonitorNs	141100261	driver-conductor
eventCounts	63997	jdk.GCPhaseParallel
eventCounts	18954	jdk.ExecutionSample
eventCounts	16592	jdk.PromoteObjectInNewPLAB
eventCounts	12903	jdk.ObjectAllocationSample
eventCounts	2702	jdk.ThreadSleep
eventCounts	2081	jdk.NativeMethodSample
eventCounts	1665	jdk.TenuringDistribution
eventCounts	1215	jdk.NativeMemoryUsage
eventCounts	549	jdk.GCPhasePauseLevel1
eventCounts	540	jdk.MetaspaceChunkFreeListSummary
eventCounts	540	jdk.GCReferenceStatistics
eventCounts	332	jdk.ThreadPark

```
节点加权分配30923399872/8677592=约3563.59 B/business op；这是ObjectAllocationSample估计，不代表精确对象数/op。Owner96.30%单核、matcher各约26.2%；Lane高CPU仍含大量循环空转。节点GC暂停159段共1251.015ms、p99 16.765ms/max17.758ms，影响尾延迟；Owner未见同步IO事件，DataLoss0。
NMT before: Total: reserved=3139949KB, committed=691421KB；Java Heap (reserved=1572864KB, committed=526336KB)。
NMT after: Total: reserved=3149571KB, committed=726935KB；Java Heap (reserved=1572864KB, committed=524288KB)。
lanes-control-INVERSE_DELIVERY-0: JMH 3074.9830545 ms/op；client -prof gc {'gc.alloc.rate': 1.006454730566888, 'gc.alloc.rate.norm': 3279404.0, 'gc.count': 0.0}。
lanes-control-INVERSE_PERPETUAL-0: JMH 3089.3053585 ms/op；client -prof gc {'gc.alloc.rate': 1.1702149426305906, 'gc.alloc.rate.norm': 3830924.0, 'gc.count': 0.0}。
lanes-control-LINEAR_DELIVERY-0: JMH 3071.3459405 ms/op；client -prof gc {'gc.alloc.rate': 0.9873926680794651, 'gc.alloc.rate.norm': 3225544.0, 'gc.count': 0.0}。
lanes-control-LINEAR_PERPETUAL-0: JMH 3056.733402 ms/op；client -prof gc {'gc.alloc.rate': 0.8098783610657678, 'gc.alloc.rate.norm': 2625564.0, 'gc.count': 0.0}。
lanes-control-OPTION-0: JMH 2333.3659505 ms/op；client -prof gc {'gc.alloc.rate': 0.7473735272665805, 'gc.alloc.rate.norm': 1855644.0, 'gc.count': 0.0}。
lanes-control-SPOT-0: JMH 808.5341895 ms/op；client -prof gc {'gc.alloc.rate': 1.4665781331470695, 'gc.alloc.rate.norm': 1305160.0, 'gc.count': 0.0}。
lanes-profile-LINEAR_PERPETUAL-0: JMH 45.064403031 s/op；client -prof gc {'gc.alloc.rate': 148.71126408559897, 'gc.alloc.rate.norm': 17660646328.0, 'gc.count': 228.0, 'gc.time': 230.0}。
JMH continuousOperations的一次调用含整段工作负载，gc.alloc.rate.norm不是B/交易；-prof gc只测客户端fork，节点分配使用JFR。accountControls每次调用100 cycles，六产品均PASS。
真实单成员六产品execute、SIGKILL后replay、snapshot重启全部通过，资金差额0；snapshot位置SPOT3616、两个永续9568、交割及期权5184。连续负载661cycles重放/快照businessHash均7b76cfca8088dfef，snapshotPosition1378338368。恢复日志出现既有Aeron WARN quorum position went backwards（leaderCommitPosition1377554080/quorumPosition0），未抑制，恢复终态一致。没有EXCHANGE_CORE_FAILURE。
结论：本次约定的批量Lane准入、结果交接、Owner收集精简及依赖/健康检查调整已完成；两轮无profiler为225850.807与205464.975 terminal business ops/s，实测高于历史背景，但受CPU限速及同机环境影响，不作为受控收益比例或三节点生产上限。Owner仍是主要串行阶段，依赖检查、终态管理、全局发布及响应编码仍有成本；响应编码仍在Owner，不声称整个主链路零分配或无Owner协调。没有重跑方案A、云端或三本机节点。未做长稳泄漏、native池余额、完整三段延迟/CO修正，性能仅部分验证；没有因此放宽资金/状态正确性。
产物校验（记录后清理，以下原文件不再可访问）：
- perf/lanes-control-INVERSE_DELIVERY-0/node.jfr 828319 bytes SHA256 246e0fd29c9098129effb942de7bef177addf1ac0da7e6bad9f4e7ff5041fcf6
- perf/lanes-control-INVERSE_PERPETUAL-0/node.jfr 830424 bytes SHA256 4d4e09a146c8a3857c4b687b258a8879e946553cb254907517bfa547de9809fa
- perf/lanes-control-LINEAR_DELIVERY-0/node.jfr 842661 bytes SHA256 82b7cd5a3aed3bf00a8c96750ee9c6ea9c9b3f41c4e5b02d60454cf9efa5f33c
- perf/lanes-control-LINEAR_PERPETUAL-0/node.jfr 830919 bytes SHA256 7486ac26f30366a294daa983b660fc327e87d8a98c08979900e51ffb50d7b072
- perf/lanes-control-OPTION-0/node.jfr 808459 bytes SHA256 19bed44adfbac3581680f6f7db3ee98b97c3d630dac7ccb7ccdc95054f002461
- perf/lanes-control-SPOT-0/node.jfr 703725 bytes SHA256 626f269622f2cce1f5cab716a301d323f90342940b8f91a67ca52c9021cabe06
- perf/lanes-profile-LINEAR_PERPETUAL-0/node.jfr 6609568 bytes SHA256 feb30943c3b7fa884c0e89203a625fa93c69f90c70b20a40e09bd765b2c90d7a
- verify-final.log SHA256 1f71db21c09fd06d87587dbf95204d860cb3a89ecf0b28df1f09f21b289879e9
- gates.py SHA256 a609d3dda8984e9c02b5e0bedebb6545d8c7422902d935d2d62fa4b65ad796e1
- perf.py SHA256 a00a71bdfd258162a2892183ff4a367c95ace674529b26bce530b1d98464bf95
- recovery.py SHA256 bc40057ad10d3b595209352c3f5345fa27f4f2ee4929ce19d7f8d914c5b37493
```text
9ad5fd14df8a3b2bab9a560e9f9bd3edee27689d7856c43b6c50036dc9737024 surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar
48ee0415d0d1275a3b16483388d397e74bb3509568f61afe4e719229d7152bf0 surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar
```

清理完成：删除本轮临时目录 `/tmp/owner-lane-completion`（约 8 GB）、9 个本轮测试报告目录及本轮生成的 `SnapshotControl.class`；保留构建 JAR 和源码。未发现仍运行的本轮负载进程。

## 2026-09-10 Owner 深层调用归因（采集前锁定）
仅方案二7e355f84，无生产改动。沿用上一轮profile完整场景：真实单成员、4 Lane/2 matcher、BUSY_SPIN、256在途、batch20、连续交易、LINEAR_PERPETUAL；HotSpot GraalVM25.0.1/G1，节点512m/1536m客户端128m/512m，预热30s测量45s，JMH fork1 wi0 i1 gc，JFR profile/stackdepth128/max128m。无额外冷却。环境同上一轮Intel8C16T，记录CPU限速，磁盘至少10GiB。入口/资金/终态校验沿用基准；阈值accepted=terminal、unfinished0、fundsDiff0、无DataLoss；仅CPU归因诊断，不作容量或变更验收，不重跑六产品恢复（本轮不改业务）。重点按Owner叶方法、行号与互斥调用路径统计，禁止把inclusive百分比相加。命令/tmp/owner-focus/run.py及run下commands.json。分析后保存摘要并清理。

### Owner 深层调用归因结果（生产代码无变更）
lanes profile 0 mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=716 businessHash=a9d815fa0e7e72d8
lanes profile 0 mixedCapacity=PASS elapsedSeconds=45.079 terminalBusinessOperations=9215232 offeredBusinessOperations=9215232 terminalCoreMessages=888064 offeredCoreMessages=888064 businessOpsPerSec=204424.771 coreMessagesPerSec=19700.240 fills=2191360 fillsPerSec=48611.719 queries=0 unfinished=0 peakInFlight=256 measuredCycles=428 totalCycles=716 triggerExecutions=0
lanes profile 0 business=PLACE_ORDER items=219136 requests=219136 p50us=7135 p90us=20660 p95us=22527 p99us=29048 p999us=49741 maxus=61702
lanes profile 0 business=CANCEL_ORDER items=219136 requests=219136 p50us=6844 p90us=15892 p95us=17711 p99us=27607 p999us=40173 maxus=55345
lanes profile 0 business=APPLY_MARK_PRICE items=11520 requests=11520 p50us=11059 p90us=20676 p95us=24510 p99us=31686 p999us=54525 maxus=55148
lanes profile 0 business=PLACE_ORDER_BATCH items=6574080 requests=328704 p50us=14712 p90us=20168 p95us=25182 p99us=33374 p999us=44433 maxus=55869
lanes profile 0 business=CANCEL_ORDER_BATCH items=2191360 requests=109568 p50us=19791 p90us=24805 p95us=28377 p99us=39124 p999us=57245 maxus=61145
finished lanes-profile-LINEAR_PERPETUAL-0

样本窗口1789031700920～1789031745999，Owner3257个ExecutionSample；单核CPU以JFR占机器比例乘16换算，Owner96.086%、matcher26.192%/26.085%，四Lane约96.3%。Lane14792样本中12197为run自循环叶帧（82.46%），不能按CPU占用认定业务饱和。无Owner File/Socket IO、MonitorEnter/ThreadPark事件；未观察到不等于证明绝无等待。DataLoss0。GCPhasePause164段/1259.918ms。CPU限速存在，仅归因诊断。
互斥阶段采用从叶向根首次匹配，归因规则保存在以下分析器SHA；未分类294/3257。百分比仅ExecutionSample，并非墙钟或可直接兑现的收益。
```text
459	lane_collect
371	other_admission
308	global_publish
308	other_matching_commit
294	other
230	lane_dispatch
174	commit_dispatch_poll
170	batch_admission
169	completion_poll
156	batch_finish_other
151	dependency
148	ingress_scope
121	response_encode
113	ingress_decode
85	matcher_results_prepare
```
跨阶段热点如下，各自inclusive；不得与上表相加。清理三项clearChangedKeys/releaseMatcherSettlementChanges/releaseOrderBatchPending在当前调用树独立，258/3257=7.92%。终态去重229/3257=7.03%，客户身份回收90/3257=2.76%。
```text
229	TerminalStateRetention.
160	MatcherSettlementPlan.build
146	ActiveOrderIndex.applySnapshot
109	clearChangedKeys
93	releaseMatcherSettlementChanges
90	RuntimeIdentityRegistry.releaseClientKey
56	releaseOrderBatchPending
```
direct_child_MatcherSettlementDispatcher.dispatchMatcherSettlementBatch（前20）
```text
141	com.surprising.aeron.service.state.MatcherSettlementPlan.buildBatchItem:89 callerLine=190
31	com.surprising.aeron.service.state.TradingRuntimeState.order:2661 callerLine=173
12	com.surprising.aeron.service.state.MatcherSettlementEvent.prepareBatch:172 callerLine=198
9	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:165
6	com.surprising.aeron.service.state.TradingRuntimeState.order:2659 callerLine=173
4	com.surprising.aeron.service.state.SettlementLaneWorker.submit:92 callerLine=208
3	com.surprising.aeron.service.state.MatcherSettlementEvent.prepareBatch:173 callerLine=198
3	com.surprising.aeron.service.state.MatcherSettlementEvent.prepareBatch:171 callerLine=198
2	com.surprising.aeron.service.state.MatcherSettlementPlan$BatchValidationScratch.clear:72 callerLine=161
2	com.surprising.aeron.service.state.RuntimeIdentityRegistry.assetId:80 callerLine=180
1	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:197
1	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:198
1	com.surprising.aeron.service.state.TradingRuntimeState.instrument:2833 callerLine=177
1	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:155
1	com.surprising.aeron.service.state.SettlementLaneWorker.depth:113 callerLine=207
1	com.surprising.aeron.service.state.RuntimeIdentityRegistry.symbol:128 callerLine=177
1	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:202
1	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:148
1	com.surprising.aeron.service.state.MatcherSettlementEvent.prepareBatch:175 callerLine=198
1	SELF com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch:149
```
direct_child_conflictingPrefixSize（前20）
```text
69	SELF com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:134
52	SELF com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:137
8	com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict:166 callerLine=132
6	SELF com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:142
6	SELF com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:133
2	SELF com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:120
2	com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict:172 callerLine=132
1	SELF com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:143
1	com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict:174 callerLine=132
1	com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict:161 callerLine=132
1	com.surprising.aeron.service.execution.ClusterCommandWindow.intersectingSlots:21 callerLine=118
1	com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict:163 callerLine=132
1	com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict:165 callerLine=132
```
owner_leaf（前20）
```text
95	java.util.concurrent.ConcurrentHashMap.get:949
93	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1729
87	java.util.ArrayList.add:486
76	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:213
69	com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:134
52	com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize:137
52	java.util.HashMap.hash:338
48	java.util.HashMap.getNode:577
47	java.util.HashMap.getNode:586
41	java.util.Arrays.fill:3143
40	com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length:342
37	com.surprising.aeron.service.state.RuntimeChangeBuffer.resetIndex:193
36	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each:571
35	com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey:240
34	java.util.HashMap.removeNode:854
34	java.lang.ThreadLocal.get:171
34	java.nio.HeapByteBuffer.put:221
33	com.surprising.aeron.service.execution.TerminalStateRetention$ClientIdentity.hashCode:514
33	java.util.Arrays.fill:3426
32	jdk.internal.misc.Unsafe.putIntUnaligned:3715
```
owner_alloc_class（前20）
```text
1544217848	[B
836727400	[J
659015088	com.surprising.aeron.service.state.MatcherSettlementPlan
622706232	com.surprising.aeron.service.execution.OrderBatchItem
573472472	java.lang.Long
494217440	com.surprising.aeron.protocol.PlaceOrderCommand
342492376	java.lang.String
328311024	com.surprising.aeron.service.execution.TerminalStateRetention$RetainedEntity
321663824	java.util.HashMap$Node
255777104	java.util.LinkedHashMap$Entry
213221392	[Ljava.lang.Object;
199108592	com.surprising.aeron.service.execution.TerminalStateRetention$ClientIdentity
171586680	[I
152777016	com.surprising.aeron.service.execution.CommandResultLedger$StoredResult
112903552	com.surprising.aeron.protocol.CoreMessageHeader
112000592	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001251e8460
94677800	com.surprising.aeron.protocol.CoreResponse
89971648	com.surprising.aeron.service.execution.TerminalStateRetention$EntityKey
83584976	com.surprising.aeron.protocol.CoreOrderStateView
62235224	java.util.HashMap$KeyIterator
```
源码核对：TradingRuntimeState.clearChangedKeys逐次访问所有Lane捕获数组及多类控制状态Map；MatcherSettlementChanges.clear遍历所有Lane而不只涉及Lane；OrderBatchPending.clear清理多套批量数组/容器。不可直接删除引用释放，但可按已触及Lane/实际写入的缓冲清理。TerminalStateRetention仍逐终态创建持久key/RetainedEntity/LinkedHashMap节点并淘汰，前轮仅复用查询key；应保持去重语义及保留窗口，优化存储和回收结构。MatcherSettlementPlan.build在Owner逐fill查权威订单、维护剩余数量和位置identity；计划构造141样本、派发中taker读取37样本，真正submit仅4样本。不能把整个派发热点称为队列入队。后续用现成不可变撮合证据和Lane准入结果减少二次查表/逐单plan容器，数量与身份校验迁移必须保持跨账户异常fail-closed。
ClusterCommandWindow.conflictingPrefixSize151样本中121在134/137行订单ID hash探测循环（80.13%），是粗64位掩码碰撞后的精确查找成本，不是资金风险计算。考虑精确orderId到窗口槽索引替换当前粗候选二次查询，保留账户/币对/持仓量依赖。readyLaneMask93样本（2.86%）仍遍历SPSC游标；压缩重复pump/仅轮询在途Lane，比移除完成信号合理；SPSC已用padding，不能凭本轮JFR断言false sharing。
全局发布308/3257=9.46%，其中ActiveOrderIndex.applySnapshot146（4.48%），clearChangedKeys109（3.35%）；索引用于准入/对手账户依赖/业务查询，不能全部异步化到外部。响应编码121（3.72%），不是最大热点。入口范围构造含解码261（8.01%），逐symbol instrument/路由查表及decoded DTO分配可后续压缩。结论：Owner受多个逐订单串行处理和循环协调累加限制，未发现一个占绝大多数的同步IO/锁瓶颈。本轮不改生产代码、不宣称优化收益，未重跑快照/六产品/无profiler主结果/长稳泄漏/三段CO延迟。
CPU_Speed_Limit观察值：[68, 70, 72, 75, 77, 79, 93, 100]。
/tmp/owner-focus/run.py SHA256 22bb652563ee3641588e9dacb9fe44ee40e312a53eef038995b65a983b4b3c32 size=5424
/tmp/owner-focus/Focus.java SHA256 38e25b81cd43e32f89b86195431d45e640133bd9773e6c57985fa4ab0db989be size=5698
/tmp/owner-focus/focus.txt SHA256 4460a9b256ebf9f2900609634c37ed19be4346e245e84ed1904161598714edbe size=154827
/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/node.jfr SHA256 1226ed869387548ff73469f8e07b8a252862087258024c48b9d906242d38b767 size=6902585
/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/commands.json SHA256 a7b98586cb26b4de2e799124005c62d6a232f1e6448c8fdcfd158fe9985834fb size=3566
完整执行参数：
```json
[
  [
    "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseG1GC",
    "-XX:NativeMemoryTracking=summary",
    "-Dsurprising.aeron.hostnames=127.0.0.1",
    "-Dsurprising.aeron.egress-hostname=127.0.0.1",
    "-Dsurprising.aeron.node-id=0",
    "-Dsurprising.aeron.account-lanes=4",
    "-Dsurprising.aeron.matching-engines=2",
    "-Dsurprising.aeron.settlement-spin-limit=0",
    "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
    "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
    "-Dsurprising.aeron.execution-mode=PIPELINED",
    "-Xms512m",
    "-Xmx1536m",
    "-Djava.io.tmpdir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/tmp",
    "-Daeron.dir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/media",
    "-Dsurprising.aeron.data-dir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/data",
    "-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK",
    "-Dsurprising.aeron.service.idle-strategy=YIELDING",
    "-XX:FlightRecorderOptions=stackdepth=128",
    "-XX:StartFlightRecording=settings=profile,maxsize=128m,dumponexit=true,filename=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/node.jfr",
    "-cp",
    "/Users/atomex/Desktop/surprising/experiments/surprising-ex-lanes/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar",
    "com.surprising.aeron.service.cluster.SurprisingClusterNode"
  ],
  [
    "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseG1GC",
    "-XX:NativeMemoryTracking=summary",
    "-Dsurprising.aeron.hostnames=127.0.0.1",
    "-Dsurprising.aeron.egress-hostname=127.0.0.1",
    "-Dsurprising.aeron.node-id=0",
    "-Dsurprising.aeron.account-lanes=4",
    "-Dsurprising.aeron.matching-engines=2",
    "-Dsurprising.aeron.settlement-spin-limit=0",
    "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
    "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
    "-Dsurprising.aeron.execution-mode=PIPELINED",
    "-Xms128m",
    "-Xmx512m",
    "-Djava.io.tmpdir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/tmp",
    "-Daeron.dir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/clientmedia",
    "-Dsurprising.aeron.client.threading-mode=SHARED",
    "-Dsurprising.aeron.capacity-async-in-flight=256",
    "-Dsurprising.aeron.capacity-session-in-flight=256",
    "-Dsurprising.aeron.capacity-warmup-seconds=30",
    "-Dsurprising.aeron.capacity-duration-seconds=45",
    "-Dsurprising.aeron.capacity-seed=131001",
    "-Dsurprising.aeron.mixed-trading-stream=true",
    "-Dsurprising.aeron.mixed-operational=false",
    "-cp",
    "/Users/atomex/Desktop/surprising/experiments/surprising-ex-lanes/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar",
    "org.openjdk.jmh.Main",
    "ClusterOperationalBenchmark.continuousOperations",
    "-f",
    "1",
    "-wi",
    "0",
    "-i",
    "1",
    "-p",
    "controlPageSize=0",
    "-prof",
    "gc",
    "-rf",
    "json",
    "-rff",
    "/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/jmh.json"
  ]
]
```
JMH结果：
```json
[{"jmhVersion": "1.37", "benchmark": "com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations", "mode": "ss", "threads": 1, "forks": 1, "jvm": "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java", "jvmArgs": ["-XX:ThreadPriorityPolicy=1", "-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCIProduct", "-XX:+EnableJVMCI", "-XX:-UnlockExperimentalVMOptions", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC", "-XX:NativeMemoryTracking=summary", "-Dsurprising.aeron.hostnames=127.0.0.1", "-Dsurprising.aeron.egress-hostname=127.0.0.1", "-Dsurprising.aeron.node-id=0", "-Dsurprising.aeron.account-lanes=4", "-Dsurprising.aeron.matching-engines=2", "-Dsurprising.aeron.settlement-spin-limit=0", "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN", "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL", "-Dsurprising.aeron.execution-mode=PIPELINED", "-Xms128m", "-Xmx512m", "-Djava.io.tmpdir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/tmp", "-Daeron.dir=/tmp/owner-focus/run/lanes-profile-LINEAR_PERPETUAL-0/clientmedia", "-Dsurprising.aeron.client.threading-mode=SHARED", "-Dsurprising.aeron.capacity-async-in-flight=256", "-Dsurprising.aeron.capacity-session-in-flight=256", "-Dsurprising.aeron.capacity-warmup-seconds=30", "-Dsurprising.aeron.capacity-duration-seconds=45", "-Dsurprising.aeron.capacity-seed=131001", "-Dsurprising.aeron.mixed-trading-stream=true", "-Dsurprising.aeron.mixed-operational=false"], "jdkVersion": "25.0.1", "vmName": "Java HotSpot(TM) 64-Bit Server VM", "vmVersion": "25.0.1+8-LTS-jvmci-b01", "warmupIterations": 0, "warmupTime": "single-shot", "warmupBatchSize": 1, "measurementIterations": 1, "measurementTime": "single-shot", "measurementBatchSize": 1, "params": {"controlPageSize": "0"}, "primaryMetric": {"score": 45.079254484, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 45.079254484, "50.0": 45.079254484, "90.0": 45.079254484, "95.0": 45.079254484, "99.0": 45.079254484, "99.9": 45.079254484, "99.99": 45.079254484, "99.999": 45.079254484, "99.9999": 45.079254484, "100.0": 45.079254484}, "scoreUnit": "s/op", "rawData": [[45.079254484]]}, "secondaryMetrics": {"gc.alloc.rate": {"score": 161.16701250031747, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 161.16701250031747, "50.0": 161.16701250031747, "90.0": 161.16701250031747, "95.0": 161.16701250031747, "99.0": 161.16701250031747, "99.9": 161.16701250031747, "99.99": 161.16701250031747, "99.999": 161.16701250031747, "99.9999": 161.16701250031747, "100.0": 161.16701250031747}, "scoreUnit": "MB/sec", "rawData": [[161.16701250031747]]}, "gc.alloc.rate.norm": {"score": 19110878832.0, "scoreError": "NaN", "scoreConfidence": ["NaN", "NaN"], "scorePercentiles": {"0.0": 19110878832.0, "50.0": 19110878832.0, "90.0": 19110878832.0, "95.0": 19110878832.0, "99.0": 19110878832.0, "99.9": 19110878832.0, "99.99": 19110878832.0, "99.999": 19110878832.0, "99.9999": 19110878832.0, "100.0": 19110878832.0}, "scoreUnit": "B/op", "rawData": [[19110878832.0]]}, "gc.count": {"score": 246.0, "scoreError": "NaN", "scoreConfidence": [246.0, 246.0], "scorePercentiles": {"0.0": 246.0, "50.0": 246.0, "90.0": 246.0, "95.0": 246.0, "99.0": 246.0, "99.9": 246.0, "99.99": 246.0, "99.999": 246.0, "99.9999": 246.0, "100.0": 246.0}, "scoreUnit": "counts", "rawData": [[246.0]]}, "gc.time": {"score": 243.0, "scoreError": "NaN", "scoreConfidence": [243.0, 243.0], "scorePercentiles": {"0.0": 243.0, "50.0": 243.0, "90.0": 243.0, "95.0": 243.0, "99.0": 243.0, "99.9": 243.0, "99.99": 243.0, "99.999": 243.0, "99.9999": 243.0, "100.0": 243.0}, "scoreUnit": "ms", "rawData": [[243.0]]}}}]
```

本轮节点和客户端已退出，已清理/tmp/owner-focus全部原始产物（约1.56 GiB）；以上原始路径不可再访问，未删除源码/构建JAR。

## 2026-09-10 Owner四项优化（采集前锁定）
基于3eddf2a4方案二；精确订单窗口索引、连续FIFO终态存储、参与Lane清理/空缓冲快速返回、事件所有的复用结算计划与单批权威订单引用、Owner登记在途完成Lane并复用SPSC已观察游标。保留资金依赖、同maker累计量校验、终态保留数量及snapshot VERSION2格式。
沿用前轮机器Intel i9-9880H8C16T/16GiB、HotSpot GraalVM25.0.1、Maven3.9.16、G1/NMT；真实单成员网络/Archive/Core，4 Lane2matcher、PIPELINED、BUSY_SPIN、SHARED_NETWORK/serviceYIELDING；节点512m/1536m，客户端128m/512m/SHARED。256全局/session在途、1命令+1查询session，256symbol1769users，batch20，seed131001/131002，初始资金1768000000125；continuous trading=true operational=false。动作比例沿用现有循环：普通下/撤各512，批量下15360项、撤5120项，每cycle5120fills。
先受影响reactor verify和六产品真实节点execute/SIGKILL/replay/snapshot，再main两轮warm30s/measure60s；JMH continuous fork1/wi0/i1/gc内部warm30s/measure300s，JFR profile/stackdepth128/max128m，用5分钟持续轮次检查多次FIFO回绕和GC后趋势。六产品accountControls fork1/wi1/i2/gc/JFR，最后持续轮次全日志重放及快照重启校验。全部串行，本机不启三节点/云端。无额外冷却，每5s采pmset/vm_stat，磁盘低于10GiB停止。
阈值：测试无失败，accepted=terminal business/Core，unfinished0，fundsDiff0，订单/仓位/冻结校验一致，重放snapshot哈希一致；DataLoss/CPU限速/swap出现不得作容量验收。本轮新churn guard要求终态订单生命周期至少跨两次65536保留窗口。持续轮JFR归因不替代主吞吐；5分钟仅初步内存趋势，未完成长周期生产泄漏、完整三段延迟/CO修正、全native池余额，不宣称完全性能验收。脚本/tmp/owner-four/{gates,perf,recovery}.py及各case commands.json。

被测代码提交64204608。最终verify-final2（HotSpot25）1213 tests：1212通过、0失败/错误、1数据库条件跳过。首轮verify1的2失败来自旧内部结构断言（已移除槽的primitive数组要求清零；测试直接发布通知却未模拟Owner派发登记），更新后保留精确依赖/无丢通知校验。新增FIFO对照原LinkedHashMap字节/哈希冲突/回绕/复制隔离、同maker跨项累计量、复用缓存失效、稀疏Lane缓冲回收、同订单多窗口槽依赖及晚到通知测试。verify2成功；期间较晚启动的一次重叠构建已终止，其结果未采用，最终verify-final2独立完成后才启动真实节点验证。六产品execute/SIGKILL replay/snapshot全部PASS，资金差额0，位置SPOT3616/永续9568/交割期权5184。

### 结算缓存精简后的最终采集锁定
64204608五分钟轮正确性通过；初步Owner依赖186/21545=0.86%、清理三处1005/21545=4.66%，终态1157/21545=5.37%，计划构造1074/21545=4.98%未下降。复查发现整批订单Map和独立位置准备Set在maker不重复时增加维护成本，因此最终删除两容器，只保留相邻maker只读引用，位置是否已准备直接利用已有累计数量表。计划与数组池、FIFO、依赖与轮询改动保留。追加深度成交到单fill的复用路由测试。
等待旧轮recovery结束后单独final verify，再六产品gates-final、一次main30/60、profile JMH30/45和六产品controls，最后final profile日志/快照恢复。其余机器/窗口/业务/通过阈值与本轮原锁定完全一致；最终短profile不替代64204608五分钟稳定趋势，也不声称最终版本已做完整长期泄漏验收。目录/tmp/owner-four/{gates-final,perf-final}，脚本同名.py及recovery-final.py。CPU限速仍只作诊断。

### 最终代码137c40c5与采样结果
最终完整verify-final3：1214 tests，1213通过、0失败/错误、1数据库条件跳过。深度maker索引到单fill复用、累计maker数量、精确订单依赖/多槽回绕、FIFO字节/复制隔离、延迟通知及跨Lane缓冲回收均通过。无生产测试开关。

64204608 lanes-control-INVERSE_DELIVERY-0
```text

```
CPU_Speed_Limit=64～64；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3023.6899015 ms/op；client GC {'gc.alloc.rate': 1.1788600966183187, 'gc.alloc.rate.norm': 3789264.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf/lanes-control-INVERSE_DELIVERY-0/node.jfr size=848509 SHA256=04b702bfb4ae0701e985b658c75773e8b4cf2b1019da9cfb19823e92769f5b33（记录后清理）。

64204608 lanes-control-INVERSE_PERPETUAL-0
```text

```
CPU_Speed_Limit=68～68；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3107.6316260000003 ms/op；client GC {'gc.alloc.rate': 0.9839127983441567, 'gc.alloc.rate.norm': 3247736.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf/lanes-control-INVERSE_PERPETUAL-0/node.jfr size=853371 SHA256=8d270d524043221a1e123dc4a5a363b5e4378df980a7fc644baa73a8c9fc4fdf（记录后清理）。

64204608 lanes-control-LINEAR_DELIVERY-0
```text

```
CPU_Speed_Limit=64～68；Swapins系统页计数增量=121；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=2993.9195025 ms/op；client GC {'gc.alloc.rate': 0.7761686796106534, 'gc.alloc.rate.norm': 2475948.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf/lanes-control-LINEAR_DELIVERY-0/node.jfr size=856695 SHA256=53e2a063ceceba75fe47932374f197f484d601a0f3b3253ea91ed9eada87d64b（记录后清理）。

64204608 lanes-control-LINEAR_PERPETUAL-0
```text

```
CPU_Speed_Limit=66～66；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3025.9076855000003 ms/op；client GC {'gc.alloc.rate': 0.9975228502101369, 'gc.alloc.rate.norm': 3216588.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf/lanes-control-LINEAR_PERPETUAL-0/node.jfr size=847646 SHA256=7ab3074a6dd9cfb099fdd1ba4048860512c8d751372223d79420c4fde47f2bbe（记录后清理）。

64204608 lanes-control-OPTION-0
```text

```
CPU_Speed_Limit=68～68；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=2320.705865 ms/op；client GC {'gc.alloc.rate': 1.0331044489101884, 'gc.alloc.rate.norm': 2557324.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf/lanes-control-OPTION-0/node.jfr size=841067 SHA256=b468ead4bdbe497d12266d2a04aeeafa97678ee81bc52f0a4db836554109a2e2（记录后清理）。

64204608 lanes-control-SPOT-0
```text

```
CPU_Speed_Limit=64～64；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=807.250875 ms/op；client GC {'gc.alloc.rate': 1.5307305456576683, 'gc.alloc.rate.norm': 1366532.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf/lanes-control-SPOT-0/node.jfr size=693313 SHA256=1403fd759b10a001475e240003291d0651762342bb6974cc3aa49b6ad14c696d（记录后清理）。

64204608 lanes-main-LINEAR_PERPETUAL-0
```text
ownerChurnVerify=PASS completedOrderLifecycles=12410880 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=808 businessHash=c504a1215546c11b
mixedCapacity=PASS elapsedSeconds=60.066 terminalBusinessOperations=12487523 offeredBusinessOperations=12487523 terminalCoreMessages=1203043 offeredCoreMessages=1203043 businessOpsPerSec=207897.262 coreMessagesPerSec=20028.740 fills=2969600 fillsPerSec=49439.085 queries=0 unfinished=0 peakInFlight=256 measuredCycles=580 totalCycles=808 triggerExecutions=0
business=PLACE_ORDER items=296960 requests=296960 p50us=7262 p90us=19496 p95us=21463 p99us=27770 p999us=48234 maxus=63864
business=CANCEL_ORDER items=296960 requests=296960 p50us=6811 p90us=16138 p95us=18104 p99us=25542 p999us=56131 maxus=89784
business=APPLY_MARK_PRICE items=15203 requests=15203 p50us=11263 p90us=19382 p95us=21528 p99us=35815 p999us=52396 maxus=86441
business=PLACE_ORDER_BATCH items=8908800 requests=445440 p50us=14573 p90us=19038 p95us=21954 p99us=34668 p999us=69926 maxus=96993
business=CANCEL_ORDER_BATCH items=2969600 requests=148480 p50us=19152 p90us=24461 p95us=27492 p99us=39288 p999us=55541 maxus=84475
```
CPU_Speed_Limit=62～100；Swapins系统页计数增量=552；Swapouts系统页计数增量=0；

64204608 lanes-main-LINEAR_PERPETUAL-1
```text
ownerChurnVerify=PASS completedOrderLifecycles=12334080 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=803 businessHash=76870daec331646f
mixedCapacity=PASS elapsedSeconds=60.067 terminalBusinessOperations=11455272 offeredBusinessOperations=11455272 terminalCoreMessages=1104680 offeredCoreMessages=1104680 businessOpsPerSec=190709.001 coreMessagesPerSec=18390.870 fills=2723840 fillsPerSec=45346.876 queries=0 unfinished=0 peakInFlight=256 measuredCycles=532 totalCycles=803 triggerExecutions=0
business=PLACE_ORDER items=272384 requests=272384 p50us=8003 p90us=21348 p95us=23674 p99us=30752 p999us=54001 maxus=66584
business=CANCEL_ORDER items=272384 requests=272384 p50us=7540 p90us=17481 p95us=19431 p99us=26017 p999us=41680 maxus=91947
business=APPLY_MARK_PRICE items=15144 requests=15144 p50us=11116 p90us=23674 p95us=28409 p99us=42860 p999us=56295 maxus=73400
business=PLACE_ORDER_BATCH items=8171520 requests=408576 p50us=15949 p90us=21217 p95us=24018 p99us=35520 p999us=61603 maxus=96862
business=CANCEL_ORDER_BATCH items=2723840 requests=136192 p50us=20905 p90us=26492 p95us=29196 p99us=45547 p999us=67371 maxus=93650
```
CPU_Speed_Limit=58～85；Swapins系统页计数增量=383；Swapouts系统页计数增量=0；

64204608 lanes-profile-LINEAR_PERPETUAL-0
```text
ownerChurnVerify=PASS completedOrderLifecycles=38400000 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=2500 businessHash=4644dd54c63d77b4
mixedCapacity=PASS elapsedSeconds=300.041 terminalBusinessOperations=50287659 offeredBusinessOperations=50287659 terminalCoreMessages=4857899 offeredCoreMessages=4857899 businessOpsPerSec=167602.582 coreMessagesPerSec=16190.780 fills=11955200 fillsPerSec=39845.211 queries=0 unfinished=0 peakInFlight=256 measuredCycles=2335 totalCycles=2500 triggerExecutions=0
business=PLACE_ORDER items=1195520 requests=1195520 p50us=8953 p90us=23724 p95us=26279 p99us=33538 p999us=53772 maxus=75169
business=CANCEL_ORDER items=1195520 requests=1195520 p50us=8888 p90us=19791 p95us=22200 p99us=31064 p999us=46399 maxus=78249
business=APPLY_MARK_PRICE items=75819 requests=75819 p50us=14319 p90us=23642 p95us=26951 p99us=42795 p999us=68485 maxus=113508
business=PLACE_ORDER_BATCH items=35865600 requests=1793280 p50us=18153 p90us=24248 p95us=27607 p99us=40730 p999us=61767 maxus=113639
business=CANCEL_ORDER_BATCH items=11955200 requests=597760 p50us=23298 p90us=29016 p95us=32489 p99us=45776 p999us=63799 maxus=93126
```
CPU_Speed_Limit=54～75；Swapins系统页计数增量=2038；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations primary=300.041733299 s/op；client GC {'gc.alloc.rate': 172.45877359567658, 'gc.alloc.rate.norm': 66332480448.0, 'gc.count': 851.0, 'gc.time': 792.0}。每次调用为整段场景，gc.norm不是每笔交易。
```text
totals allocationMiBps=549.115 allocationBytes=172773549272 machineCPU=90.75 jvmCPU=57.88 heapMaxMiB=462.37 dataLoss=0 ownerIOEvents=0
threadCPU	96.789	core-account-lane-3
threadCPU	96.787	core-account-lane-1
threadCPU	96.770	core-account-lane-0
threadCPU	96.764	core-account-lane-2
threadCPU	95.981	trading-owner--1
threadCPU	94.695	/tmp/owner-four/perf/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	66.722	clustered-service-101-0
threadCPU	65.728	driver-conductor
threadCPU	62.344	archive-conductor
threadCPU	58.815	consensus-module-101-0
threadCPU	27.237	core-matcher-1
threadCPU	27.175	core-matcher-0
threadCPU	1.222	aeron-md-nra
threadCPU	1.042	JVMCI-native CompilerThread0
threadCPU	0.225	aeron-client
threadCPU	0.155	C1 CompilerThread1
threadCPU	0.101	JFR Recorder Thread
threadCPU	0.097	JFR Periodic Tasks
threadCPU	0.058	C1 CompilerThread0
threadCPU	0.012	Monitor Deflation Thread
gc count=1595 totalMs=5787.464 p99Ms=8.979 maxMs=11.951
samples	25630	core-account-lane-0
samples	25315	core-account-lane-1
samples	24838	core-account-lane-2
samples	24127	core-account-lane-3
samples	21545	trading-owner--1
samples	1843	driver-conductor
samples	1250	clustered-service-101-0
samples	903	consensus-module-101-0
samples	901	archive-conductor
samples	388	/tmp/owner-four/perf/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	175	core-matcher-1
samples	169	core-matcher-0
ownerInclusive	21545	java.lang.Thread.run
ownerInclusive	21545	java.lang.Thread.runWith
ownerInclusive	21545	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	21545	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012a12a730.run
ownerInclusive	21238	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	21060	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	14440	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	13981	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	13793	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand
ownerInclusive	13756	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress
ownerInclusive	8384	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	7544	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
allocation	8624529536	trading-owner--1 [B
allocation	6842534208	trading-owner--1 [J
allocation	4630528600	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchItem
allocation	4449944376	core-account-lane-0 [J
allocation	4379734600	core-account-lane-2 [J
allocation	4302982552	core-account-lane-1 [J
allocation	4297815816	core-account-lane-3 [J
allocation	4279431608	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	4273162368	clustered-service-101-0 [B
allocation	4210644904	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	4199182368	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	4152151392	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocationSite	17768220136	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	10304208184	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	9762359648	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	8978622840	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	7179286480	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	6845300296	java.util.ArrayList.add
allocationSite	6205188720	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	5224055160	java.nio.ByteBuffer.allocate
allocationSite	4536010064	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
allocationSite	4289753088	com.surprising.aeron.service.state.model.AssetBalance.validAsset
allocationSite	4264763240	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	4141265888	java.lang.StringConcatHelper.stringSize
parkOrMonitorNs	300524166918	Common-Cleaner
parkOrMonitorNs	169681837	aeron-md-nra
parkOrMonitorNs	122296450	core-matcher-1
parkOrMonitorNs	88922312	core-matcher-0
parkOrMonitorNs	33355850	archive-conductor
parkOrMonitorNs	11019824	driver-conductor
parkOrMonitorNs	10918439	consensus-module-101-0
eventCounts	410855	jdk.GCPhaseParallel
eventCounts	127099	jdk.ExecutionSample
eventCounts	110604	jdk.PromoteObjectInNewPLAB
eventCounts	86619	jdk.ObjectAllocationSample
eventCounts	19057	jdk.PromoteObjectOutsidePLAB
eventCounts	18133	jdk.ThreadSleep
eventCounts	14034	jdk.NativeMethodSample
eventCounts	13125	jdk.TenuringDistribution
eventCounts	8073	jdk.NativeMemoryUsage
eventCounts	5862	jdk.GCPhasePauseLevel1
eventCounts	4944	jdk.MetaspaceChunkFreeListSummary
eventCounts	4940	jdk.GCReferenceStatistics

```
节点加权分配约3435.7 B/business op（JFR抽样估算）。
```text
crosscut
1157	TerminalStateRetention.
1074	MatcherSettlementPlan.build
974	TerminalTombstoneStore.
843	ActiveOrderIndex.applySnapshot
651	readyLaneMask
637	RuntimeIdentityRegistry.releaseClientKey
548	clearChangedKeys
313	releaseOrderBatchPending
144	releaseMatcherSettlementChanges
```
```text
exclusiveStage
11525	other
2809	lane_collect
1830	lane_dispatch
1815	global_publish
1064	batch_finish_other
937	batch_admission
789	response_encode
590	matcher_results_prepare
186	dependency
```
```text
ownerAllocationClass
8624529536	[B
6842534208	[J
4630528600	com.surprising.aeron.service.execution.OrderBatchItem
2774208352	com.surprising.aeron.protocol.PlaceOrderCommand
2376973160	java.lang.Long
1550584784	java.lang.String
1416197072	[I
1064002512	[Ljava.lang.Object;
794653568	com.surprising.aeron.protocol.CoreResponse
622954704	com.surprising.aeron.service.execution.CommandResultLedger$StoredResult
601012560	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x000000012a1e8230
453092328	com.surprising.aeron.protocol.CancelOrderCommand
436735280	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x000000012a1e3c88
436453408	java.util.ImmutableCollections$ListItr
419287512	com.surprising.aeron.service.execution.ImmutableLongArrayList
391302048	com.surprising.aeron.protocol.CoreOrderStateView
388727520	com.surprising.aeron.protocol.CoreMessageHeader
359473096	java.util.HashMap$Node
352856288	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x000000012a1e8000
304576184	com.surprising.aeron.service.state.CoreOrderDecisionResolver$Context
271491184	java.util.LinkedHashMap$Entry
267808736	com.surprising.aeron.service.state.ResolvedPlaceOrder
```
```text
ownerLeaf
877	java.util.concurrent.ConcurrentHashMap.get:949
646	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:213
542	java.util.ArrayList.add:486
376	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1772
367	java.util.HashMap.getNode:577
350	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each:571
231	java.util.HashMap.hash:338
224	jdk.internal.misc.Unsafe.putIntUnaligned:3715
212	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1036
211	java.util.concurrent.ConcurrentHashMap.get:957
203	java.nio.HeapByteBuffer.put:221
196	com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length:342
194	java.lang.ThreadLocal.get:171
185	com.surprising.aeron.service.execution.PendingMatchingRing.partitionDispatchHead:209
173	java.util.Arrays.fill:3143
171	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
163	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.add:216
148	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.probe:1043
148	java.util.HashMap.getNode:585
135	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
135	com.surprising.aeron.service.execution.TerminalTombstoneStore.clientSlot:62
131	com.surprising.aeron.service.execution.TradingCoreRuntime.bindOwner:2478
```
afterGCminute=0 samples=254 minMiB=89.466 avgMiB=111.891 maxMiB=247.311
afterGCminute=1 samples=224 minMiB=87.797 avgMiB=104.190 maxMiB=125.207
afterGCminute=2 samples=260 minMiB=90.187 avgMiB=102.697 maxMiB=129.000
afterGCminute=3 samples=233 minMiB=91.210 avgMiB=104.026 maxMiB=126.389
afterGCminute=4 samples=265 minMiB=89.535 avgMiB=102.521 maxMiB=133.254
nmt-before.txt
```text
53804:

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=3146072KB, committed=691552KB
       malloc: 72536KB #94649, peak=70873KB #94651
       mmap:   reserved=3073536KB, committed=619016KB

-                 Java Heap (reserved=1572864KB, committed=526336KB)
                            (mmap: reserved=1572864KB, committed=526336KB, at peak)

-                     Class (reserved=1048910KB, committed=1678KB)
                            (classes #3934)
                            (  instance classes #3544, array classes #390)
                            (malloc=334KB tag=Class #7416) (at peak)
                            (mmap: reserved=1048576KB, committed=1344KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=18496KB)
                            (    used=18353KB)
                            (    waste=143KB =0.78%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1344KB)
                            (    used=1220KB)
                            (    waste=124KB =9.26%)

-                    Thread (reserved=58505KB, committed=1941KB)
                            (threads #48)
                            (stack: reserved=58368KB, committed=1804KB, peak=1804KB)
                            (malloc=91KB tag=Thread #275) (peak=102KB #283)
                            (arena=46KB #78) (peak=428KB #76)

-                      Code (reserved=250307KB, committed=15975KB)
                            (malloc=2619KB tag=Code #15023) (peak=2619KB #15024)
                            (mmap: reserved=247688KB, committed=13356KB, at peak)
                            (arena=1KB #1) (peak=133KB #5)

-                        GC (reserved=91619KB, committed=71179KB)
                            (malloc=27539KB tag=GC #4380) (peak=27592KB #4906)
                            (mmap: reserved=64080KB, committed=43640KB, at peak)
                            (arena=0KB #0) (peak=12KB #13)

-                 GCCardSet (reserved=2KB, committed=2KB)
                            (malloc=2KB tag=GCCardSet #9) (peak=2KB #10)

-                  Compiler (reserved=271KB, committed=271KB)
                            (malloc=131KB tag=Compiler #185) (peak=147KB #191)
                            (arena=139KB #19) (peak=7667KB #24)

-                     JVMCI (reserved=58KB, committed=58KB)
                            (malloc=58KB tag=JVMCI #158) (at peak)
                            (arena=0KB #0) (peak=66KB #2)

-                  Internal (reserved=1458KB, committed=1458KB)
                            (malloc=1426KB tag=Internal #5078) (at peak)
                            (mmap: reserved=32KB, committed=32KB, at peak)

-                     Other (reserved=9409KB, committed=9409KB)
                            (malloc=9409KB tag=Other #28) (at peak)

-                    Symbol (reserved=4337KB, committed=4337KB)
                            (malloc=3721KB tag=Symbol #42771) (at peak)
                            (arena=616KB #1) (at peak)

-    Native Memory Tracking (reserved=1692KB, committed=1692KB)
                            (malloc=29KB tag=Native Memory Tracking #478) (peak=29KB #479)
                            (tracking overhead=1664KB)

-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=14000KB, peak=14208KB)

-               Arena Chunk (reserved=7341KB, committed=7341KB)
                            (malloc=7341KB tag=Arena Chunk #354) (peak=8636KB #366)

-                   Tracing (reserved=16353KB, committed=16353KB)
                            (malloc=16353KB tag=Tracing #3410) (at peak)

-                   Logging (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Logging #2) (peak=6KB #4)

-                Statistics (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Statistics #2) (peak=2KB #6)

-                    Module (reserved=291KB, committed=291KB)
                            (malloc=291KB tag=Module #3301) (at peak)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=621KB, committed=621KB)
                            (malloc=621KB tag=Synchronization #11682) (at peak)

-            Serviceability (reserved=17KB, committed=17KB)
                            (malloc=17KB tag=Serviceability #18) (peak=20KB #22)

-                 Metaspace (reserved=65620KB, committed=18580KB)
                            (malloc=84KB tag=Metaspace #47) (at peak)
                            (mmap: reserved=65536KB, committed=18496KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=String Deduplication #8) (at peak)

-           Object Monitors (reserved=4KB, committed=4KB)
                            (malloc=4KB tag=Object Monitors #18) (at peak)


```
nmt-after.txt
```text
53804:

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=3148877KB, committed=725925KB
       malloc: 81485KB #170931, peak=80757KB #185180
       mmap:   reserved=3067392KB, committed=644440KB

-                 Java Heap (reserved=1572864KB, committed=524288KB)
                            (mmap: reserved=1572864KB, committed=524288KB, peak=526336KB)

-                     Class (reserved=1049144KB, committed=2552KB)
                            (classes #4924)
                            (  instance classes #4462, array classes #462)
                            (malloc=568KB tag=Class #12692) (peak=586KB #14625)
                            (mmap: reserved=1048576KB, committed=1984KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=25280KB)
                            (    used=24945KB)
                            (    waste=335KB =1.33%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1984KB)
                            (    used=1760KB)
                            (    waste=224KB =11.31%)

-                    Thread (reserved=52371KB, committed=2299KB)
                            (threads #49)
                            (stack: reserved=52224KB, committed=2152KB, peak=2152KB)
                            (malloc=92KB tag=Thread #296) (peak=114KB #324)
                            (arena=55KB #94) (peak=727KB #94)

-                      Code (reserved=255636KB, committed=41044KB)
                            (malloc=7947KB tag=Code #31499) (peak=13573KB #41905)
                            (mmap: reserved=247688KB, committed=33096KB, at peak)
                            (arena=1KB #1) (peak=133KB #5)

-                        GC (reserved=92695KB, committed=72215KB)
                            (malloc=28615KB tag=GC #9507) (peak=28877KB #13660)
                            (mmap: reserved=64080KB, committed=43600KB, peak=43640KB)
                            (arena=0KB #0) (peak=12KB #13)

-                 GCCardSet (reserved=39KB, committed=39KB)
                            (malloc=39KB tag=GCCardSet #91) (peak=54KB #123)

-                  Compiler (reserved=424KB, committed=424KB)
                            (malloc=292KB tag=Compiler #820) (peak=310KB #592)
                            (arena=131KB #5) (peak=9396KB #6)

-                     JVMCI (reserved=89KB, committed=89KB)
                            (malloc=89KB tag=JVMCI #250) (at peak)
                            (arena=0KB #0) (peak=99KB #3)

-                  Internal (reserved=1589KB, committed=1589KB)
                            (malloc=1557KB tag=Internal #9964) (at peak)
                            (mmap: reserved=32KB, committed=32KB, at peak)

-                     Other (reserved=9413KB, committed=9413KB)
                            (malloc=9413KB tag=Other #37) (at peak)

-                    Symbol (reserved=4861KB, committed=4861KB)
                            (malloc=4213KB tag=Symbol #51374) (at peak)
                            (arena=648KB #1) (at peak)

-    Native Memory Tracking (reserved=3058KB, committed=3058KB)
                            (malloc=54KB tag=Native Memory Tracking #933) (at peak)
                            (tracking overhead=3005KB)

-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=14000KB, peak=14208KB)

-               Arena Chunk (reserved=2883KB, committed=2883KB)
                            (malloc=2883KB tag=Arena Chunk #205) (peak=10142KB #401)

-                   Tracing (reserved=20125KB, committed=20125KB)
                            (malloc=20125KB tag=Tracing #25320) (peak=22906KB #41756)

-                   Logging (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Logging #2) (peak=6KB #4)

-                Statistics (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Statistics #2) (peak=2KB #6)

-                    Module (reserved=293KB, committed=293KB)
                            (malloc=293KB tag=Module #3343) (peak=293KB #3345)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=1285KB, committed=1285KB)
                            (malloc=1285KB tag=Synchronization #24423) (at peak)

-            Serviceability (reserved=17KB, committed=17KB)
                            (malloc=17KB tag=Serviceability #18) (peak=20KB #22)

-                 Metaspace (reserved=65699KB, committed=25443KB)
                            (malloc=163KB tag=Metaspace #138) (at peak)
                            (mmap: reserved=65536KB, committed=25280KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=String Deduplication #8) (at peak)

-           Object Monitors (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=Object Monitors #3) (peak=22KB #114)


```
/tmp/owner-four/perf/lanes-profile-LINEAR_PERPETUAL-0/node.jfr size=24487101 SHA256=99d927892966170b0907646e38518a0cf72e302e09d30ad9f7fee3bed1c6bed1（记录后清理）。

137c40c5 lanes-control-INVERSE_DELIVERY-0
```text

```
CPU_Speed_Limit=70～70；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3056.737279 ms/op；client GC {'gc.alloc.rate': 1.0045845081695293, 'gc.alloc.rate.norm': 3251644.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf-final/lanes-control-INVERSE_DELIVERY-0/node.jfr size=869331 SHA256=047d69dbe8c6d4a9b1cb34125db5c9c1c36c1d591b9369d06df68d7024eafc7c（记录后清理）。

137c40c5 lanes-control-INVERSE_PERPETUAL-0
```text

```
CPU_Speed_Limit=68～70；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3039.136187 ms/op；client GC {'gc.alloc.rate': 1.1861071483564984, 'gc.alloc.rate.norm': 3829420.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf-final/lanes-control-INVERSE_PERPETUAL-0/node.jfr size=839791 SHA256=d8aaa47665df443a892ff50a4ff778d6139780c3f74d15380c91da1f4512566e（记录后清理）。

137c40c5 lanes-control-LINEAR_DELIVERY-0
```text

```
CPU_Speed_Limit=68～68；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3013.7878825 ms/op；client GC {'gc.alloc.rate': 1.2171817665004014, 'gc.alloc.rate.norm': 3905508.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf-final/lanes-control-LINEAR_DELIVERY-0/node.jfr size=856838 SHA256=f73a4e230db638fa069e93ea645d1e320b822f1dd3a9893ee936c0a023d5ef3e（记录后清理）。

137c40c5 lanes-control-LINEAR_PERPETUAL-0
```text

```
CPU_Speed_Limit=70～70；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=3052.46988 ms/op；client GC {'gc.alloc.rate': 1.0309365578705656, 'gc.alloc.rate.norm': 3349672.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf-final/lanes-control-LINEAR_PERPETUAL-0/node.jfr size=850719 SHA256=13c161755705787f8c67ef35b3fa07345a9a1d98f39ad26e79ac7c89c91c9c87（记录后清理）。

137c40c5 lanes-control-OPTION-0
```text

```
CPU_Speed_Limit=68～70；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=2289.3788155 ms/op；client GC {'gc.alloc.rate': 1.042310409464882, 'gc.alloc.rate.norm': 2548204.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf-final/lanes-control-OPTION-0/node.jfr size=802765 SHA256=37c2a6185cf69cfb24aed21e594863c89b0378f67a96405a19b6c676b7793d8d（记录后清理）。

137c40c5 lanes-control-SPOT-0
```text

```
CPU_Speed_Limit=70～70；Swapins系统页计数增量=0；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls primary=803.2415745000001 ms/op；client GC {'gc.alloc.rate': 1.424254492226582, 'gc.alloc.rate.norm': 1268112.0, 'gc.count': 0.0}。每次调用为整段场景，gc.norm不是每笔交易。
/tmp/owner-four/perf-final/lanes-control-SPOT-0/node.jfr size=712568 SHA256=facd4110cfedb2454833eab45d86d188ff3d4fc83fe2325d4fccab8be2615757（记录后清理）。

137c40c5 lanes-main-LINEAR_PERPETUAL-0
```text
ownerChurnVerify=PASS completedOrderLifecycles=10045440 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=654 businessHash=d9f4301489b1119
mixedCapacity=PASS elapsedSeconds=60.068 terminalBusinessOperations=10164992 offeredBusinessOperations=10164992 terminalCoreMessages=981760 offeredCoreMessages=981760 businessOpsPerSec=169223.773 coreMessagesPerSec=16344.049 fills=2416640 fillsPerSec=40231.506 queries=0 unfinished=0 peakInFlight=256 measuredCycles=472 totalCycles=654 triggerExecutions=0
business=PLACE_ORDER items=241664 requests=241664 p50us=8888 p90us=23707 p95us=25821 p99us=30982 p999us=52625 maxus=66912
business=CANCEL_ORDER items=241664 requests=241664 p50us=8683 p90us=19415 p95us=21053 p99us=26296 p999us=45842 maxus=68026
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=13574 p90us=23281 p95us=26001 p99us=41779 p999us=59932 maxus=62390
business=PLACE_ORDER_BATCH items=7249920 requests=362496 p50us=18284 p90us=23003 p95us=25575 p99us=39976 p999us=59211 maxus=75497
business=CANCEL_ORDER_BATCH items=2416640 requests=120832 p50us=23232 p90us=28426 p95us=31293 p99us=50102 p999us=58916 maxus=66027
```
CPU_Speed_Limit=54～100；Swapins系统页计数增量=192；Swapouts系统页计数增量=0；

137c40c5 lanes-profile-LINEAR_PERPETUAL-0
```text
ownerChurnVerify=PASS completedOrderLifecycles=7818240 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=509 businessHash=79ae078780d18fbb
mixedCapacity=PASS elapsedSeconds=45.123 terminalBusinessOperations=7774220 offeredBusinessOperations=7774220 terminalCoreMessages=750604 offeredCoreMessages=750604 businessOpsPerSec=172289.970 coreMessagesPerSec=16634.664 fills=1848320 fillsPerSec=40961.923 queries=0 unfinished=0 peakInFlight=256 measuredCycles=361 totalCycles=509 triggerExecutions=0
business=PLACE_ORDER items=184832 requests=184832 p50us=8675 p90us=22806 p95us=24903 p99us=32309 p999us=60325 maxus=93716
business=CANCEL_ORDER items=184832 requests=184832 p50us=8634 p90us=19054 p95us=21004 p99us=28966 p999us=46825 maxus=54951
business=APPLY_MARK_PRICE items=11276 requests=11276 p50us=14680 p90us=23134 p95us=26066 p99us=40304 p999us=56360 maxus=62816
business=PLACE_ORDER_BATCH items=5544960 requests=277248 p50us=17907 p90us=22921 p95us=25919 p99us=43384 p999us=62685 maxus=80347
business=CANCEL_ORDER_BATCH items=1848320 requests=92416 p50us=22462 p90us=27295 p95us=30015 p99us=51347 p999us=80805 maxus=91947
```
CPU_Speed_Limit=54～70；Swapins系统页计数增量=2198；Swapouts系统页计数增量=0；
JMH com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations primary=45.123332337 s/op；client GC {'gc.alloc.rate': 114.71069707633416, 'gc.alloc.rate.norm': 13571369328.0, 'gc.count': 175.0, 'gc.time': 243.0}。每次调用为整段场景，gc.norm不是每笔交易。
```text
totals allocationMiBps=563.286 allocationBytes=26637031120 machineCPU=89.44 jvmCPU=59.24 heapMaxMiB=463.22 dataLoss=0 ownerIOEvents=0
threadCPU	97.428	core-account-lane-3
threadCPU	97.415	core-account-lane-1
threadCPU	97.413	core-account-lane-2
threadCPU	97.406	core-account-lane-0
threadCPU	97.113	trading-owner--1
threadCPU	96.544	/tmp/owner-four/perf-final/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	70.828	clustered-service-101-0
threadCPU	70.402	driver-conductor
threadCPU	67.910	archive-conductor
threadCPU	63.982	consensus-module-101-0
threadCPU	28.569	core-matcher-0
threadCPU	28.416	core-matcher-1
threadCPU	4.048	JVMCI-native CompilerThread0
threadCPU	1.253	aeron-md-nra
threadCPU	0.254	aeron-client
threadCPU	0.128	C1 CompilerThread0
threadCPU	0.100	JFR Recorder Thread
threadCPU	0.092	JFR Periodic Tasks
threadCPU	0.013	Monitor Deflation Thread
gc count=181 totalMs=746.782 p99Ms=11.386 maxMs=11.820
samples	3848	core-account-lane-0
samples	3797	core-account-lane-1
samples	3739	core-account-lane-2
samples	3638	core-account-lane-3
samples	3215	trading-owner--1
samples	316	driver-conductor
samples	175	clustered-service-101-0
samples	172	consensus-module-101-0
samples	148	archive-conductor
samples	58	/tmp/owner-four/perf-final/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	33	core-matcher-0
samples	33	core-matcher-1
ownerInclusive	3215	java.lang.Thread.run
ownerInclusive	3215	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012812a730.run
ownerInclusive	3215	java.lang.Thread.runWith
ownerInclusive	3215	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	3167	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	3153	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	2134	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	2105	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand
ownerInclusive	2102	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress
ownerInclusive	2071	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	1288	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	1079	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
allocation	1474205072	trading-owner--1 [B
allocation	1183359176	trading-owner--1 [J
allocation	764208896	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	762742608	core-account-lane-2 [J
allocation	696079920	core-account-lane-3 [J
allocation	683762608	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	644278216	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	642894688	clustered-service-101-0 [B
allocation	634782440	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	625703912	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	622906568	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	608974336	core-account-lane-1 [J
allocationSite	2842372640	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	1750862104	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	1482347352	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	1371321392	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	1025216192	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	1004292384	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	941312128	java.nio.ByteBuffer.allocate
allocationSite	859890104	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	772859520	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
allocationSite	711890208	java.util.ArrayList.add
allocationSite	673678456	java.lang.invoke.VarHandleLongs$Array.setRelease
allocationSite	663561424	com.surprising.aeron.service.state.model.AssetBalance.validAsset
parkOrMonitorNs	48851125	aeron-md-nra
parkOrMonitorNs	45721941	core-matcher-1
parkOrMonitorNs	35103084	core-matcher-0
eventCounts	57210	jdk.GCPhaseParallel
eventCounts	19173	jdk.ExecutionSample
eventCounts	16561	jdk.PromoteObjectInNewPLAB
eventCounts	13190	jdk.ObjectAllocationSample
eventCounts	4138	jdk.PromoteObjectOutsidePLAB
eventCounts	2727	jdk.ThreadSleep
eventCounts	2127	jdk.NativeMethodSample
eventCounts	1755	jdk.TenuringDistribution
eventCounts	1188	jdk.NativeMemoryUsage
eventCounts	639	jdk.GCPhasePauseLevel1
eventCounts	596	jdk.MetaspaceChunkFreeListSummary
eventCounts	596	jdk.GCReferenceStatistics

```
节点加权分配约3426.33 B/business op（JFR抽样估算）。
```text
crosscut
173	TerminalStateRetention.
159	MatcherSettlementPlan.build
150	TerminalTombstoneStore.
116	ActiveOrderIndex.applySnapshot
98	clearChangedKeys
95	readyLaneMask
87	RuntimeIdentityRegistry.releaseClientKey
45	releaseOrderBatchPending
18	releaseMatcherSettlementChanges
```
```text
exclusiveStage
1712	other
434	lane_collect
286	global_publish
231	lane_dispatch
143	response_encode
142	batch_finish_other
129	batch_admission
109	matcher_results_prepare
29	dependency
```
```text
ownerAllocationClass
1474205072	[B
1183359176	[J
607661312	com.surprising.aeron.service.execution.OrderBatchItem
517249776	java.lang.Long
446171632	com.surprising.aeron.protocol.PlaceOrderCommand
240731352	java.lang.String
202779224	[I
173195872	com.surprising.aeron.service.execution.CommandResultLedger$StoredResult
167203616	[Ljava.lang.Object;
92037704	com.surprising.aeron.protocol.CoreOrderStateView
84552448	com.surprising.aeron.protocol.CoreResponse
67848456	com.surprising.aeron.protocol.CoreMessageHeader
53320728	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001281e78a8
45248592	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001281e7678
39822552	com.surprising.aeron.service.state.RuntimeProjectionPoint
36619592	java.util.LinkedHashMap$Entry
35770160	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001281e7448
33092984	com.surprising.aeron.service.state.ResolvedPlaceOrder
31998760	org.eclipse.collections.impl.set.mutable.primitive.IntHashSet
31962856	com.surprising.aeron.protocol.CancelOrderCommand
31008456	java.util.HashMap$Node
29518616	com.surprising.aeron.service.execution.ImmutableLongArrayList
```
```text
ownerLeaf
112	java.util.ArrayList.add:486
109	java.util.concurrent.ConcurrentHashMap.get:949
91	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:213
57	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each:571
46	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1772
37	jdk.internal.misc.Unsafe.putIntUnaligned:3715
36	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1036
36	java.nio.HeapByteBuffer.put:221
34	java.util.HashMap.hash:338
32	java.util.HashMap.get:565
32	java.util.concurrent.ConcurrentHashMap.get:957
30	com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length:342
28	com.surprising.aeron.service.execution.PendingMatchingRing.partitionDispatchHead:209
28	com.surprising.aeron.service.state.LaneSequenceQueue.hasPending:45
27	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.matcherShardId:1141
26	java.util.Arrays.fill:3451
24	java.lang.ThreadLocal.get:171
24	java.util.HashMap.getNode:585
23	org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap.removeKeyAtIndex:750
23	java.util.concurrent.ConcurrentHashMap.get:960
22	java.util.Arrays.fill:3143
21	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
```
afterGCminute=0 samples=149 minMiB=92.518 avgMiB=120.712 maxMiB=246.990
nmt-before.txt
```text
59799:

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=3142029KB, committed=691617KB
       malloc: 72589KB #94525, peak=70928KB #94527
       mmap:   reserved=3069440KB, committed=619028KB

-                 Java Heap (reserved=1572864KB, committed=526336KB)
                            (mmap: reserved=1572864KB, committed=526336KB, at peak)

-                     Class (reserved=1048908KB, committed=1676KB)
                            (classes #3932)
                            (  instance classes #3542, array classes #390)
                            (malloc=332KB tag=Class #7400) (at peak)
                            (mmap: reserved=1048576KB, committed=1344KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=18560KB)
                            (    used=18348KB)
                            (    waste=212KB =1.14%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1344KB)
                            (    used=1219KB)
                            (    waste=125KB =9.27%)

-                    Thread (reserved=54404KB, committed=1884KB)
                            (threads #46)
                            (stack: reserved=54272KB, committed=1752KB, peak=1752KB)
                            (malloc=87KB tag=Thread #267) (peak=100KB #277)
                            (arena=46KB #78) (peak=428KB #76)

-                      Code (reserved=250284KB, committed=15952KB)
                            (malloc=2595KB tag=Code #14934) (at peak)
                            (mmap: reserved=247688KB, committed=13356KB, at peak)
                            (arena=1KB #1) (peak=68KB #4)

-                        GC (reserved=91619KB, committed=71179KB)
                            (malloc=27539KB tag=GC #4409) (peak=27581KB #4908)
                            (mmap: reserved=64080KB, committed=43640KB, at peak)
                            (arena=0KB #0) (peak=12KB #13)

-                 GCCardSet (reserved=2KB, committed=2KB)
                            (malloc=2KB tag=GCCardSet #9) (peak=2KB #10)

-                  Compiler (reserved=274KB, committed=274KB)
                            (malloc=137KB tag=Compiler #209) (peak=152KB #218)
                            (arena=137KB #15) (peak=7754KB #12)

-                     JVMCI (reserved=54KB, committed=54KB)
                            (malloc=54KB tag=JVMCI #147) (at peak)
                            (arena=0KB #0) (peak=66KB #2)

-                  Internal (reserved=1453KB, committed=1453KB)
                            (malloc=1421KB tag=Internal #5066) (at peak)
                            (mmap: reserved=32KB, committed=32KB, at peak)

-                     Other (reserved=9409KB, committed=9409KB)
                            (malloc=9409KB tag=Other #28) (at peak)

-                    Symbol (reserved=4337KB, committed=4337KB)
                            (malloc=3722KB tag=Symbol #42781) (at peak)
                            (arena=616KB #1) (at peak)

-    Native Memory Tracking (reserved=1690KB, committed=1690KB)
                            (malloc=28KB tag=Native Memory Tracking #474) (at peak)
                            (tracking overhead=1662KB)

-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=14000KB, peak=14208KB)

-               Arena Chunk (reserved=7439KB, committed=7439KB)
                            (malloc=7439KB tag=Arena Chunk #356) (peak=8732KB #368)

-                   Tracing (reserved=16343KB, committed=16343KB)
                            (malloc=16343KB tag=Tracing #3334) (at peak)

-                   Logging (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Logging #2) (peak=6KB #4)

-                Statistics (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Statistics #2) (peak=2KB #6)

-                    Module (reserved=291KB, committed=291KB)
                            (malloc=291KB tag=Module #3301) (at peak)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=622KB, committed=622KB)
                            (malloc=622KB tag=Synchronization #11707) (at peak)

-            Serviceability (reserved=17KB, committed=17KB)
                            (malloc=17KB tag=Serviceability #18) (peak=20KB #22)

-                 Metaspace (reserved=65621KB, committed=18645KB)
                            (malloc=85KB tag=Metaspace #50) (at peak)
                            (mmap: reserved=65536KB, committed=18560KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=String Deduplication #8) (at peak)

-           Object Monitors (reserved=3KB, committed=3KB)
                            (malloc=3KB tag=Object Monitors #17) (at peak)


```
nmt-after.txt
```text
59799:

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=3150553KB, committed=727205KB
       malloc: 83161KB #179947, peak=80684KB #186712
       mmap:   reserved=3067392KB, committed=644044KB

-                 Java Heap (reserved=1572864KB, committed=524288KB)
                            (mmap: reserved=1572864KB, committed=524288KB, peak=526336KB)

-                     Class (reserved=1049140KB, committed=2548KB)
                            (classes #4917)
                            (  instance classes #4455, array classes #462)
                            (malloc=564KB tag=Class #12686) (peak=577KB #14463)
                            (mmap: reserved=1048576KB, committed=1984KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=25152KB)
                            (    used=24833KB)
                            (    waste=319KB =1.27%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1984KB)
                            (    used=1753KB)
                            (    waste=231KB =11.63%)

-                    Thread (reserved=52371KB, committed=2603KB)
                            (threads #49)
                            (stack: reserved=52224KB, committed=2456KB, peak=2456KB)
                            (malloc=92KB tag=Thread #296) (peak=112KB #320)
                            (arena=55KB #94) (peak=428KB #76)

-                      Code (reserved=255654KB, committed=40482KB)
                            (malloc=7965KB tag=Code #30981) (peak=13432KB #41850)
                            (mmap: reserved=247688KB, committed=32516KB, at peak)
                            (arena=1KB #1) (peak=101KB #5)

-                        GC (reserved=92535KB, committed=72063KB)
                            (malloc=28455KB tag=GC #9418) (peak=28654KB #13684)
                            (mmap: reserved=64080KB, committed=43608KB, peak=43640KB)
                            (arena=0KB #0) (peak=12KB #13)

-                 GCCardSet (reserved=27KB, committed=27KB)
                            (malloc=27KB tag=GCCardSet #106) (peak=55KB #124)

-                  Compiler (reserved=411KB, committed=411KB)
                            (malloc=280KB tag=Compiler #800) (peak=321KB #606)
                            (arena=131KB #5) (peak=10066KB #19)

-                     JVMCI (reserved=87KB, committed=87KB)
                            (malloc=87KB tag=JVMCI #248) (at peak)
                            (arena=0KB #0) (peak=66KB #2)

-                  Internal (reserved=1587KB, committed=1587KB)
                            (malloc=1555KB tag=Internal #9802) (at peak)
                            (mmap: reserved=32KB, committed=32KB, at peak)

-                     Other (reserved=9411KB, committed=9411KB)
                            (malloc=9411KB tag=Other #33) (peak=9411KB #36)

-                    Symbol (reserved=4859KB, committed=4859KB)
                            (malloc=4212KB tag=Symbol #51349) (at peak)
                            (arena=648KB #1) (at peak)

-    Native Memory Tracking (reserved=3216KB, committed=3216KB)
                            (malloc=53KB tag=Native Memory Tracking #919) (at peak)
                            (tracking overhead=3163KB)

-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=14000KB, peak=14208KB)

-               Arena Chunk (reserved=2884KB, committed=2884KB)
                            (malloc=2884KB tag=Arena Chunk #206) (peak=10884KB #444)

-                   Tracing (reserved=21838KB, committed=21838KB)
                            (malloc=21838KB tag=Tracing #35610) (at peak)

-                   Logging (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Logging #2) (peak=6KB #4)

-                Statistics (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Statistics #2) (peak=2KB #6)

-                    Module (reserved=293KB, committed=293KB)
                            (malloc=293KB tag=Module #3343) (peak=293KB #3345)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=1260KB, committed=1260KB)
                            (malloc=1260KB tag=Synchronization #23952) (at peak)

-            Serviceability (reserved=17KB, committed=17KB)
                            (malloc=17KB tag=Serviceability #18) (peak=20KB #22)

-                 Metaspace (reserved=65705KB, committed=25321KB)
                            (malloc=169KB tag=Metaspace #159) (at peak)
                            (mmap: reserved=65536KB, committed=25152KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=String Deduplication #8) (at peak)

-           Object Monitors (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=Object Monitors #3) (peak=21KB #108)


```
/tmp/owner-four/perf-final/lanes-profile-LINEAR_PERPETUAL-0/node.jfr size=6046082 SHA256=8eec870f49d357223d1e384afd4b40460c869e07e0e1f2c6012bf99f4a64d2aa（记录后清理）。

解释：最终Owner3215样本。依赖29/3215=0.90%（前定位151/3257=4.64%）；三处清理98+45+18=161/3215=5.01%（原258/3257=7.92%）；终态管理173/3215=5.38%（原229/3257=7.03%）。这些仅执行样本占比，inclusive父子不可相加；不是同机受控耗时收益。结算计划159/3215=4.95%、派发231/3215=7.19%、readyLaneMask95/3215=2.95%，CPU占比未见明显下降，不能声称这几处热点消失。批量计划变为事件拥有的稳定槽，普通单仍分配Plan；最终Plan分配抽样约7892416B/7774220ops≈1.02B/op，对照前定位659015088/9215232≈71.51B/op，抽样估计不能当精确对象计数。终态FIFO热路径不再创建RetainedEntity/EntityKey/ClientIdentity，快照/导出候选边界仍使用不可变对象。
最终无profiler169223.773 business ops/s，未证实总体吞吐提高，不能把限速环境下低于历史的数字单独归因为代码回退或全部归因为硬件；后续需稳定硬件环境验证。Owner97.113%单核、matcher28.569%/28.416%，Lane仍有大量空转。代码范围完成但没有达到所有线程有效业务饱和。记录保留两版全部结果，没有挑选最高值替代最终版。
64204608五分钟50,287,659终态业务操作资金差额0，GC后逐分钟平均111.891/104.190/102.697/104.026/102.521MiB，未观察到持续增长；最终137c40c5为45s诊断，未证明最终版本长期无泄漏。最终无Owner同步File/Socket事件、DataLoss0；未覆盖完整三段延迟/CO修正、所有native池余额/长期文件描述符趋势，仍属部分性能验证。全共享正确性由reactor、六产品控制及真实重放/快照验证，不声称云端三节点容量。
最终JAR：
```text
2a268edd15a2b9abe17d2aafede6ada294d1720db5f74b3d5c56a9c7e61d0ac4 surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar
2b3696decacc1e7c012e8739b9a180c2974c12413dc613d14f0f9c5693b9486a surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar

```
verify-final3.log SHA256=b2d95ea100e55194f8c0327a9b0c809f163f66bb44317b79d70234a6ff60cc46
gates-final.py SHA256=539f93e189c4bc12a3a2da9d21a52830a4017254d8d0c54fd11a784432337c90
perf-final.py SHA256=b218c3fbde995f7d26861233f46ae8eadd9b3ac6f8339f92819cc60285e9b8d0
recovery-final.py SHA256=2310ab57fc78ec0aa58b9d7baf54d341b1bd1fafd97470e8204e515bb4346655
OwnerFourAnalysis.java SHA256=711d61939cb0478c247b9de75b292521d030c6a7527342034d19edb9ac7ff90f
AnalyzeJfr.java SHA256=c3d0493742987250289a85f5e53a275dba1fc5d7eafc564e254ca49578e26f59

最终137c40c5六产品gates-final及accountControls均PASS；连续交易重放/快照恢复结果：
```text
lanes replay PASS businessHash=79ae078780d18fbb
lanes snapshotPosition=1063471744
lanes snapshot PASS businessHash=79ae078780d18fbb

```
64204608五分钟恢复结果：
```text
lanes replay PASS businessHash=4644dd54c63d77b4
lanes snapshotPosition=5201683328
lanes snapshot PASS businessHash=4644dd54c63d77b4

```
节点异常/WARN摘要（未抑制既有quorum回退警告）：{'io.aeron.cluster.client.ClusterEvent: WARN - quorum position went backwards: leaderCommitPosition=5200899040 quorumPosition=0': 1, 'io.aeron.cluster.client.ClusterEvent: WARN - quorum position went backwards: leaderCommitPosition=1062687456 quorumPosition=0': 1}。未发现EXCHANGE_CORE_FAILURE。

清理完成：本轮所有节点/客户端/分析进程已结束，已删除/tmp/owner-four全部临时集群Archive、JFR、日志和分析器（约23.29GiB）、9个本轮测试报告目录及本轮生成SnapshotControl.class；保留源码与构建JAR。以上临时产物路径现已不可访问。未运行云端/三本机节点，未改README。

## 2026-09-10 master Owner 定位（采集前定义）

- 当前代码 d8f02b7b；仅分析，不修改生产逻辑。对照 commit 不适用（仅当前 master）。Maven package -DskipTests 已成功，HotSpot Oracle GraalVM 25.0.1、Maven 3.9.16。
- 本机 Intel i9 8C16T，单个真实 Aeron Cluster 成员、网络与 Archive；LINEAR_PERPETUAL，PIPELINED，4 Account Lane、2 matcher（诊断配置），Lane BUSY_SPIN、服务 YIELDING、SHARED_NETWORK；G1，节点 Xms512m/Xmx1536m，客户端128m/512m，NMT summary。
- ClusterMixedCapacityMain 连续交易，1769 用户、256 symbols、20项批量、256全局/会话在途、1命令会话+1查询会话；内置 maker/taker 持续交易，初始化每账户 BALANCE=1000000000，具体资产/持仓由当前 setup 决定；trading-stream=true，operational=false，seed131001。预热30s、测量45s、结束排空并校验资金/持仓/订单。不是六产品混合控制业务或三节点容量验收。
- JFR profile衍生配置，ExecutionSample2ms、CPU1s、park/monitor阈值1ms、上限128MiB；NMT前后及系统限速采样。仅一次带profiler诊断，无无profiler吞吐对照，无JMH新验收、快照重启、长稳泄漏或完整三段CO修正延迟；不改业务，无需重复上一轮恢复门禁。
- 门槛：业务PASS、offered=terminal、unfinished=0、资金核对正确；JFR DataLoss=0。发生明显限速则吞吐不作有效性能验收，仅保留受干扰诊断。目的：细分Owner轮询/完成/发布调用栈，不能将CPU高等同于有效业务饱和，也不能从执行样本直接推算调用次数。
- 临时路径 /tmp/owner-master-diagnosis，命令/JFC/摘要/校验值分析后记录并清理，空闲磁盘约510GiB；运行中低于10GiB停止，客户端总截止300s，节点/客户端结束后停止，不启云端或wallet。不修改README。

### master Owner 定位结果

- 本轮为单成员真实网络连续交易45s诊断；代码未改。package成功（skipTests），没有声称重新执行功能测试套件。业务本身验证PASS；下文包含测量值，硬件限速导致本轮不满足性能验收有效性条件。
- Owner执行样本13871；Lane样本64675，其中52198（80.71%）叶子定位worker.run:154（队列读取附近），不是handoff等待分支，不能将Lane约96.6% CPU解释为有效业务饱和。Owner94.299%、matcher25.398/25.388%，机器92.81%；瞬时/平均CPU不等于吞吐上限。
- Owner park/monitor阈值1ms，记录park2.158ms，均来自runOwner→BackoffIdleStrategy；未记录Owner monitor enter/wait或IO事件。这仅排除已观测到的长等待，不排除短锁/缓存争用或线程调度成本。
- 新的直接证据：ClusterCommandWindow.add的orderSlots使用EC11 LongLongHashMap；删除留下sentinel，occupiedWithData+sentinels超过阈值时rehashAndGrow重新分配数组，即使有效数量未上升。独立容器功能复现（非Core性能跑分）见下文；同类路径TerminalTombstoneStore.entities也有数组分配采样。不是内存泄漏结论，也不等于整个吞吐瓶颈都来自rehash。
- 批量入口：TradingOrderBatchCodec.decodeCommand 465/13871=3.35%，ArrayList.add叶子358中344明确来自该解码路径；decodeOrderBatch继续把decoded DTO包装成OrderBatchItem（整轮Owner该类分配加权649879840B）。窗口decoded缓存已复用解码结果，未发现每轮重复完整解码；问题是逐项解码与包装成本，不是误报双重解码。
- 相同symbol路由重复读取已定位prepareClusterPipelineScope与preparePipelinedPlaceBatch、orderBatchMatcherShard；matcherShardId包含调用372/13871=2.68%。在已有symbol常态走CHM只读，未发现注册symbol的synchronized为本轮热点。
- 客户标识释放：releaseClientKey 406/13871=2.93%，Map<Long,...>的get/remove仍会装箱；对应分配加权290418864B。其引用计数和终态去重不能删，只能考虑压缩重复查找/装箱。
- 收尾多阶段：collectMatcherSettlement1245（8.98%），collectCancel578（4.17%）；发布1239（8.93%），其中ActiveOrderIndex.applySnapshot590（4.25%）；批量响应编码558（4.02%）；结算派发947（6.83%）含Plan build家族628（4.53%）。这些是inclusive样本，父子不能相加、不能直接作优化收益；发布索引服务于后续资金/对手依赖与业务查询，不能全部移除。
- 调度重复轮询的机制明确：processIngress每条输入立即progress，runOwner每最多64条后又pollCommands；progressCommandsInScope每轮pollCommandPrefix→commitReadyMatching→pump→准入/撮合/结算检查和分区派发。pollCommands以pending是否非空报告工作，不是实际进展；readyLaneMask481（3.47%）。主路径未调用hasMatchingNotifications。没有逐轮进展计数，不能把pump的全部3274（23.60%）样本都算空轮询，里面包括真实派发。
- 队首依赖仍可能阻止后续准入，但本轮conflictingPrefixSize110（0.79%）、checkProgressDeadline3；未采窗口驻留/依赖等待墙钟分解，无法从此推断阻塞比例。同步控制/handoff在当前连续普通交易测量中未成为采样热点，不能外推全部控制业务无等待。
- 下一步候选优先级：窗口/终态索引churn，批量逐项包装和重复路由，客户身份装箱查表，然后按实际进展压缩轮询与收尾遍历。保留日志顺序、资金依赖、失败关闭与可见性边界；本轮仅定位，未实现优化、未证明收益。

- 系统采样CPU_Speed_Limit范围56–100，Java分配为JFR加权估计、JIT内联可影响分配站点，不能把每项路径权重当精确对象数；GC后live-set/native长期趋势与三段CO修正延迟未验收。原始JFR无DataLoss。

业务结果
```text
mixedConfig globalWindow=256 sessionWindow=256 commandSessions=1 reservedQuerySessions=1 batchSize=20 tradingStream=true
mixedSetup=PASS users=1769 retail=1000 symbols=256 initialFunds=1768000000125
measurementStartEpochMillis=1789048743479
measurementEndEpochMillis=1789048788516
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=525 businessHash=c27c390957a872f5
mixedCapacity=PASS elapsedSeconds=45.036 terminalBusinessOperations=7086087 offeredBusinessOperations=7086087 terminalCoreMessages=685063 offeredCoreMessages=685063 businessOpsPerSec=157341.429 coreMessagesPerSec=15211.328 fills=1684480 fillsPerSec=37402.658 queries=0 unfinished=0 peakInFlight=256 measuredCycles=329 totalCycles=525 triggerExecutions=0
business=PLACE_ORDER items=168448 requests=168448 p50us=10002 p90us=25133 p95us=28016 p99us=39976 p999us=62521 maxus=69599
business=CANCEL_ORDER items=168448 requests=168448 p50us=9846 p90us=20398 p95us=23248 p99us=36438 p999us=49872 maxus=64061
business=APPLY_MARK_PRICE items=11271 requests=11271 p50us=14467 p90us=26640 p95us=30965 p99us=47448 p999us=84869 maxus=85786
business=PLACE_ORDER_BATCH items=5053440 requests=252672 p50us=18923 p90us=25935 p95us=29900 p99us=45842 p999us=75169 maxus=98238
business=CANCEL_ORDER_BATCH items=1684480 requests=84224 p50us=23871 p90us=30507 p95us=36405 p99us=52363 p999us=68616 maxus=73596
```

JFR测量窗口摘要
```text
totals allocationMiBps=514.603 allocationBytes=24302002048 machineCPU=92.81 jvmCPU=56.85 heapMaxMiB=460.86 dataLoss=0 ownerIOEvents=0
threadCPU	96.579	core-account-lane-2
threadCPU	96.569	core-account-lane-3
threadCPU	96.553	core-account-lane-1
threadCPU	96.552	core-account-lane-0
threadCPU	94.299	trading-owner--1
threadCPU	93.676	/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	65.329	clustered-service-101-0
threadCPU	62.544	driver-conductor
threadCPU	57.064	archive-conductor
threadCPU	55.928	consensus-module-101-0
threadCPU	25.398	core-matcher-0
threadCPU	25.388	core-matcher-1
threadCPU	6.670	JVMCI-native CompilerThread0
threadCPU	1.879	aeron-md-nra
threadCPU	0.345	C1 CompilerThread0
threadCPU	0.281	JFR Periodic Tasks
threadCPU	0.273	aeron-client
threadCPU	0.145	JFR Recorder Thread
threadCPU	0.103	Monitor Deflation Thread
gc count=193 totalMs=800.491 p99Ms=10.302 maxMs=14.392
samples	16565	core-account-lane-0
samples	16383	core-account-lane-1
samples	16079	core-account-lane-2
samples	15648	core-account-lane-3
samples	13871	trading-owner--1
samples	1217	driver-conductor
samples	865	clustered-service-101-0
samples	749	consensus-module-101-0
samples	584	archive-conductor
samples	246	/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	144	core-matcher-0
samples	135	core-matcher-1
ownerInclusive	13871	java.lang.Thread.run
ownerInclusive	13871	java.lang.Thread.runWith
ownerInclusive	13871	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012f12f1c8.run
ownerInclusive	13871	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	13638	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	13572	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	9385	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	9075	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	8404	com.surprising.aeron.service.execution.SurprisingClusteredService.acceptCommittedCommand
ownerInclusive	8379	com.surprising.aeron.service.execution.SurprisingClusteredService.processIngress
ownerInclusive	5569	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	5391	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
allocation	1095946720	trading-owner--1 [B
allocation	1034317768	trading-owner--1 [J
allocation	649879840	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchItem
allocation	637345808	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	626374592	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	622752200	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	617748328	core-account-lane-3 [J
allocation	614096456	core-account-lane-1 [J
allocation	611712280	clustered-service-101-0 [B
allocation	598690568	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	595705520	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	576967496	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocationSite	2747722104	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	1541357328	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	1425059368	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	1254830440	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	1097383168	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	1002042088	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	911647024	java.util.ArrayList.add
allocationSite	824396936	com.surprising.aeron.service.state.OrderRuntime.<init>
allocationSite	808672304	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	655750280	java.nio.ByteBuffer.allocate
allocationSite	638073088	java.lang.StringConcatHelper.stringSize
allocationSite	594954504	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
parkOrMonitorNs	44590789201	aeron-md-nra
parkOrMonitorNs	1033535482	consensus-module-101-0
parkOrMonitorNs	954291166	driver-conductor
parkOrMonitorNs	813855484	core-matcher-0
parkOrMonitorNs	793360402	core-matcher-1
parkOrMonitorNs	710682627	archive-conductor
parkOrMonitorNs	640496065	/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
parkOrMonitorNs	2158131	trading-owner--1
eventCounts	82496	jdk.ExecutionSample
eventCounts	55099	jdk.GCPhaseParallel
eventCounts	43250	jdk.ThreadPark
eventCounts	15152	jdk.PromoteObjectInNewPLAB
eventCounts	12738	jdk.ObjectAllocationSample
eventCounts	3801	jdk.PromoteObjectOutsidePLAB
eventCounts	2723	jdk.ThreadSleep
eventCounts	2149	jdk.NativeMethodSample
eventCounts	1725	jdk.TenuringDistribution
eventCounts	1215	jdk.NativeMemoryUsage
eventCounts	728	jdk.ThreadCPULoad
eventCounts	696	jdk.GCPhasePauseLevel1
```

Owner定向统计
```text
focusedOwnerInclusive {ActiveOrderIndex.applySnapshot=590, ClusterCommandWindow.add=244, ClusterCommandWindow.conflictingPrefixSize=110, DeterministicExchangeCoreAdapter.matcherShardId=372, MatcherSettlementDispatcher.dispatchMatcherSettlementBatch=947, MatcherSettlementPlan.build=628, OrderBatchExecutor.decodeOrderBatch=88, OrderBatchExecutor.preparePipelinedPlaceBatch=548, RuntimeIdentityRegistry.findPositionIdentity=98, RuntimeIdentityRegistry.releaseClientKey=406, SurprisingClusteredService.checkProgressDeadline=3, TerminalStateRetention.=634, TerminalTombstoneStore.put=218, TradingCoreRuntime.prepareClusterPipelineScope=1191, TradingOrderBatchCodec.decodeCommand=465, TradingOrderBatchCodec.encodeResultSource=558, TradingRuntimeState.clearChangedKeys=353, TradingRuntimeState.collectCancel=578, TradingRuntimeState.collectMatcherSettlement=1245, TradingRuntimeState.readyLaneMask=481}
focusedOwnerAllocationBytes {ActiveOrderIndex.applySnapshot=224440000, ClusterCommandWindow.add=544507016, DeterministicExchangeCoreAdapter.matcherShardId=252452904, MatcherSettlementPlan.build=25142920, OrderBatchExecutor.decodeOrderBatch=649879840, OrderBatchExecutor.preparePipelinedPlaceBatch=54117880, RuntimeIdentityRegistry.releaseClientKey=290418864, TerminalStateRetention.=268577072, TerminalTombstoneStore.put=268577072, TradingCoreRuntime.prepareClusterPipelineScope=1246248016, TradingOrderBatchCodec.decodeCommand=1148840808, TradingOrderBatchCodec.encodeResultSource=655750280, TradingRuntimeState.collectCancel=204676672, TradingRuntimeState.collectMatcherSettlement=449794360}
ownerParkMonitorNs {jdk.ThreadPark jdk.internal.misc.Unsafe.park:-1 <- java.util.concurrent.locks.LockSupport.parkNanos:408 <- org.agrona.concurrent.BackoffIdleStrategy.idle:225 <- org.agrona.concurrent.BackoffIdleStrategy.idle:186 <- com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner:213 <- com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012f12f1c8.run:-1 <- java.lang.Thread.runWith:1487 <- java.lang.Thread.run:1474 <- =2158131}
```

有界索引复现
```text
boundedMapChurn=PASS active=1280 measuredUpdates=100000 warmupUpdates=20000 arrayReplacements=130 arrayPayloadBytes=8519680 minArrayLength=8192 maxArrayLength=8192
```

执行参数
```text
[
  [
    "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseG1GC",
    "-XX:NativeMemoryTracking=summary",
    "-Dsurprising.aeron.hostnames=127.0.0.1",
    "-Dsurprising.aeron.egress-hostname=127.0.0.1",
    "-Dsurprising.aeron.node-id=0",
    "-Dsurprising.aeron.account-lanes=4",
    "-Dsurprising.aeron.matching-engines=2",
    "-Dsurprising.aeron.settlement-spin-limit=0",
    "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
    "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
    "-Dsurprising.aeron.execution-mode=PIPELINED",
    "-Xms512m",
    "-Xmx1536m",
    "-Djava.io.tmpdir=/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/tmp",
    "-Daeron.dir=/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/media",
    "-Dsurprising.aeron.data-dir=/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/data",
    "-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK",
    "-Dsurprising.aeron.service.idle-strategy=YIELDING",
    "-XX:StartFlightRecording=settings=/tmp/owner-master-diagnosis/owner.jfc,maxsize=128m,dumponexit=true,filename=/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/node.jfr",
    "-cp",
    "/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar",
    "com.surprising.aeron.service.cluster.SurprisingClusterNode"
  ],
  [
    "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseG1GC",
    "-XX:NativeMemoryTracking=summary",
    "-Dsurprising.aeron.hostnames=127.0.0.1",
    "-Dsurprising.aeron.egress-hostname=127.0.0.1",
    "-Dsurprising.aeron.node-id=0",
    "-Dsurprising.aeron.account-lanes=4",
    "-Dsurprising.aeron.matching-engines=2",
    "-Dsurprising.aeron.settlement-spin-limit=0",
    "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
    "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
    "-Dsurprising.aeron.execution-mode=PIPELINED",
    "-Xms128m",
    "-Xmx512m",
    "-Djava.io.tmpdir=/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/tmp",
    "-Daeron.dir=/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/clientmedia",
    "-Dsurprising.aeron.client.threading-mode=SHARED",
    "-Dsurprising.aeron.capacity-async-in-flight=256",
    "-Dsurprising.aeron.capacity-session-in-flight=256",
    "-Dsurprising.aeron.capacity-warmup-seconds=30",
    "-Dsurprising.aeron.capacity-duration-seconds=45",
    "-Dsurprising.aeron.capacity-seed=131001",
    "-Dsurprising.aeron.mixed-trading-stream=true",
    "-Dsurprising.aeron.mixed-operational=false",
    "-cp",
    "/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar",
    "com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain"
  ]
]
```

校验信息
```text
/tmp/owner-master-diagnosis/run.py: bytes=5462 sha256=9e71c07f9947ffbb9e523cb177128055689ffb4d3057c10b0c196b20252eba76
/tmp/owner-master-diagnosis/owner.jfc: bytes=39826 sha256=dca13cdc2a8f9d4a88f6dc954dd26cf8d2f85da5e4f922cac4b71f6c86c671d3
/tmp/owner-master-diagnosis/OwnerStacks.java: bytes=2446 sha256=bb9e40e3cdae837b10fd073b08aad1a76bea9889b9970c5632e573736fb5d949
/tmp/owner-master-diagnosis/FocusedJfr.java: bytes=2513 sha256=cbd68498e83ecf3a1a8b5df53a7577e51a7ac672a74d51dcf1978130fcf971a0
/tmp/owner-master-diagnosis/MapChurnCheck.java: bytes=1012 sha256=54a08e6765b22981d45de7948bf8b8ae5c86cac48e40515ea9a76e2b7b314bff
/tmp/owner-master-diagnosis/run/master-diagnostic-LINEAR_PERPETUAL-0/node.jfr: bytes=14939793 sha256=166549aa5874b6010222a5f1e8325d8023adfc8580f054bcc3232daee05e7f9d
surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar: bytes=60906719 sha256=289405492e8db704ce6f8cdd569e7f330717c193a0d0b2d271b734f7a3af093b
surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar: bytes=81418748 sha256=7e63d8163cc1f3e9335ed91a80e33150b87a779790a621487af05479e582e082
```

nmt-before.txt
```text
Total: reserved=3144135KB, committed=691707KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                     Class (reserved=1048910KB, committed=1742KB)
-                    Thread (reserved=56455KB, committed=1855KB)
-                      Code (reserved=250311KB, committed=15979KB)
-                        GC (reserved=91622KB, committed=71182KB)
-                  Internal (reserved=1440KB, committed=1440KB)
-                     Other (reserved=9409KB, committed=9409KB)
```

nmt-after.txt
```text
Total: reserved=3148287KB, committed=723551KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                     Class (reserved=1049142KB, committed=2550KB)
-                    Thread (reserved=54421KB, committed=2501KB)
-                      Code (reserved=255553KB, committed=41089KB)
-                        GC (reserved=92555KB, committed=72075KB)
-                  Internal (reserved=1584KB, committed=1584KB)
-                     Other (reserved=9413KB, committed=9413KB)
```

异常：首次独立容器复现启动因同目录解出的LongLongHashMap.java干扰source launcher编译而失败；移为txt后成功，不是交易失败。节点仅出现SLF4J无provider的NOP日志提示。进程已由runner停止；分析摘要记录后将删除本轮/tmp/owner-master-diagnosis原始产物，下一条确认清理状态。未修改README或生产/测试Java源码。

清理确认：本轮节点/客户端PID [81936, 81953]均已退出；已删除本轮临时目录（1277755899字节，约1.19GiB），含Archive、JFR、临时源码/编译文件、日志和报告。上列原始路径已不可访问，保留构建JAR及本记录；用户未跟踪文件未动。

## 2026-09-10 master Owner 优化：采集前定义

- 被测代码080758da；对照commit不适用（仅当前master）。变更：Agrona primitive索引删除整理替换EC sentinel重建；批上下文复用OrderBatchItem且提交后清引用；解码直接构建不可变列表；命令内最后币对路由复用；线程私有客户键查询探针；跳过无需发布/释放的存活持仓遍历；连续Owner按入口收取批统一推进，同一次推进未完成时不重复收集。
- 验证：HotSpot Oracle GraalVM25.0.1、Maven3.9.16。完整 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify` 成功，最后补充测试后 package 成功；当前surefire汇总1223项、1222通过、0失败/错误、1数据库条件skip。回归包含六产品延后入口与串行执行/快照等价、依赖流水、终态索引持续淘汰、不同批大小槽回收、客户身份并发读取/恢复/引用释放。当前没有生产测试开关。
- 压测前依次完成六产品真实单成员execute→SIGKILL→replay→snapshot重启，验证fundsDiff0/bookLevels0。使用既有ClusterProductLineGateMain和SnapshotControl，root改为master工作区；本机不启动三节点，不启云端/wallet。
- 性能只测试当前master，一个真实Aeron Cluster成员保留网络/Archive/Core，LINEAR_PERPETUAL、PIPELINED、4 Account Lane、2 matcher（归因配置，不当单matcher正式容量），Lane BUSY_SPIN，服务YIELDING，SHARED_NETWORK；HotSpot25/G1，节点Xms512m/Xmx1536m，客户端128m/512m，NMT summary。机器Intel i9-9880H 8C16T/16GiB，macOS；磁盘可用约510GiB，低于10GiB停止。
- 当前ClusterMixedCapacityMain：1769用户、256币对、batch20，global/session in-flight256，1命令会话+1查询保留会话，seed131001，trading-stream=true，operational=false，setup每账户BALANCE1000000000，含初始化清算/保险/ADL校验，maker/taker由编排持续产生；主测下单/撤单/批量下单撤单/标记价，运行中不启全套控制旁路。测量门禁要求measuredCycles本身跨两次65536终态保留窗口，不能仅靠预热。
- 先一次无profiler主测（预热30s、测量60s）；再一次实际网络JMH continuousOperations（fork1/线程1/wi0/i1，业务预热30s、测量300s，gc profiler，controlPageSize0），节点JFR profile衍生ExecutionSample2ms/CPU1s/park与monitor1ms，192MiB上限，覆盖持续复用/淘汰；测量结束排空/验证，客户端总超时600s。再六产品各一次accountControls JMH（fork1/线程1/wi1/i2、gc/JFR），之后对长测录制日志重放、生成实际快照并重启验证同一businessHash。
- 通过门槛：资金、净持仓、冻结、订单终态PASS，offered=terminal，unfinished=0，无非预期失败或timeout；JFR DataLoss0，Owner无同步IO，slot/索引分配热点下降为观察目标而非预设收益。硬件明显降频/调度干扰时只作诊断，不能认定吞吐优化已验收。主测与带profiler分开报告；不重跑历史分支，不将5min测试宣称生产长期无泄漏，不宣称三节点容量或完整CO修正三段尾延迟。
- 临时目录/tmp/owner-master-opt，保存构建/验证/命令/JFR/NMT/温控/恢复结果摘要与sha256后清理。原始产物不长期保留，README不改。无生产语义或协议版本改变；公开订单DTO仍不可变，不强称零分配。

### 080758da 第一轮结果与后续修正原因

- 六产品执行/强杀重放/快照重启均PASS；长测录制日志重放与实际snapshot重启businessHash均b2cce7f5c89a82d0、snapshotPosition5106394048。6产品accountControls JMH均有PASS（下列保存结果）；不把控制场景SingleShot耗时当持续交易吞吐。
- 无profiler主测159651.521 business ops/s，60.033s；JMH+JFR长测166090.689 business ops/s，300.053s；两者不能混合成优化对照。资金/持仓/冻结/终态核对PASS，offered=terminal、unfinished0，峰值in-flight256。
- 测量阶段Owner样本98220：readyLaneMask871=0.887%（前定位3.47%）、matcherShardId643=0.655%（前2.68%）、批量decodeCommand2725=2.774%（前3.35%），不同持续时长与JIT/硬件条件，仅归因参考，不能当严格耗时收益。Owner allocation sample中OrderBatchItem为0，窗口/终态索引重建与客户身份释放路径分配抽样0；节点加权分配约3191.8B/business op，前定位约3429.5，约7%下降，非精确逐对象计数。
- 发现新取舍：终态管理10059/98220=10.24%（前定位634/13871=4.57%），Agrona地图查找/删除成为显著Owner叶子；分配下降不能当CPU优化成功。继续将TerminalTombstoneStore四个实体Map替换为FIFO槽位直接链索引，淘汰已知槽位O(1)解链，不复制ID、不产生sentinel或搬探测链。其余已验证改动不变，最终代码需重新验证和采样，本轮不是最终交付性能。
- Owner97.608%单核、matcher24.537/24.472%，Lane约97.6%且大量空转；未达全部有效业务饱和。Owner无park/monitor>=1ms或同步IO事件，JFRDataLoss0；JVMCI compiler在测量中仍有CPU活动，未满足充分稳定JIT的强容量验收条件。
- GC后heap每分钟均值104.134/106.074/108.001/110.219/112.841MiB有缓慢上升；仅young-GC后占用不能等同真正live-set，当前未定位为业务泄漏，不能宣称长期无泄漏。新代码的持仓路径核对：移除时publishPosition和changedPosition均null；存活非null才跳过重复查表，没有把已删除持仓误保留的证据。后续最终长测增加测量结束的显式GC/heap统计，区分待回收旧对象与保留对象，但单次GC也不足证明无泄漏。

master-main-LINEAR_PERPETUAL-0 CPU_Speed_Limit=60–100，明显限速，不作有效容量验收。
```text
measurementStartEpochMillis=1789050212901
measurementEndEpochMillis=1789050272934
ownerChurnVerify=PASS completedOrderLifecycles=6835200 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=647 businessHash=f08276e160689883
mixedCapacity=PASS elapsedSeconds=60.033 terminalBusinessOperations=9584384 offeredBusinessOperations=9584384 terminalCoreMessages=926464 offeredCoreMessages=926464 businessOpsPerSec=159651.521 coreMessagesPerSec=15432.540 fills=2278400 fillsPerSec=37952.363 queries=0 unfinished=0 peakInFlight=256 measuredCycles=445 totalCycles=647 triggerExecutions=0
business=PLACE_ORDER items=227840 requests=227840 p50us=9347 p90us=22888 p95us=25182 p99us=34603 p999us=62685 maxus=71172
business=CANCEL_ORDER items=227840 requests=227840 p50us=8421 p90us=19726 p95us=22265 p99us=32407 p999us=76349 maxus=144048
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=14745 p90us=24297 p95us=29016 p99us=50692 p999us=80871 maxus=82968
business=PLACE_ORDER_BATCH items=6835200 requests=341760 p50us=20529 p90us=25640 p95us=28737 p99us=48005 p999us=90505 maxus=196476
business=CANCEL_ORDER_BATCH items=2278400 requests=113920 p50us=22216 p90us=27066 p95us=30359 p99us=53313 p999us=81264 maxus=183369
```

master-profile-LINEAR_PERPETUAL-0 CPU_Speed_Limit=60–77，明显限速，不作有效容量验收。
```text
measurementStartEpochMillis=1789050344456
measurementEndEpochMillis=1789050644511
ownerChurnVerify=PASS completedOrderLifecycles=35543040 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=2454 businessHash=b2cce7f5c89a82d0
mixedCapacity=PASS elapsedSeconds=300.053 terminalBusinessOperations=49836045 offeredBusinessOperations=49836045 terminalCoreMessages=4814861 offeredCoreMessages=4814861 businessOpsPerSec=166090.689 coreMessagesPerSec=16046.690 fills=11847680 fillsPerSec=39485.263 queries=0 unfinished=0 peakInFlight=256 measuredCycles=2314 totalCycles=2454 triggerExecutions=0
business=PLACE_ORDER items=1184768 requests=1184768 p50us=8568 p90us=22265 p95us=24281 p99us=27541 p999us=55640 maxus=163708
business=CANCEL_ORDER items=1184768 requests=1184768 p50us=8228 p90us=18677 p95us=20594 p99us=24805 p999us=47972 maxus=183894
business=APPLY_MARK_PRICE items=75789 requests=75789 p50us=13557 p90us=22085 p95us=24608 p99us=38371 p999us=60948 maxus=214040
business=PLACE_ORDER_BATCH items=35543040 requests=1777152 p50us=20201 p90us=24051 p95us=26361 p99us=40370 p999us=56524 maxus=215351
business=CANCEL_ORDER_BATCH items=11847680 requests=592384 p50us=22118 p90us=25903 p95us=27328 p99us=46170 p999us=62226 maxus=69271
```

profile-summary.txt
```text
totals allocationMiBps=505.572 allocationBytes=159068350760 machineCPU=87.44 jvmCPU=58.54 heapMaxMiB=400.87 dataLoss=0 ownerIOEvents=0
threadCPU	97.653	core-account-lane-3
threadCPU	97.645	core-account-lane-2
threadCPU	97.640	core-account-lane-0
threadCPU	97.622	core-account-lane-1
threadCPU	97.608	trading-owner--1
threadCPU	97.307	/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	75.546	clustered-service-101-0
threadCPU	73.732	driver-conductor
threadCPU	66.342	archive-conductor
threadCPU	64.593	consensus-module-101-0
threadCPU	25.737	JVMCI-native CompilerThread1
threadCPU	24.537	core-matcher-0
threadCPU	24.472	core-matcher-1
threadCPU	3.807	JVMCI-native CompilerThread0
threadCPU	1.648	aeron-md-nra
threadCPU	0.338	JFR Periodic Tasks
threadCPU	0.279	C1 CompilerThread0
threadCPU	0.245	C1 CompilerThread1
threadCPU	0.183	aeron-client
threadCPU	0.176	JFR Recorder Thread
gc count=533 totalMs=3196.377 p99Ms=8.910 maxMs=16.712
samples	112789	core-account-lane-0
samples	111445	core-account-lane-1
samples	109416	core-account-lane-2
samples	106829	core-account-lane-3
samples	98220	trading-owner--1
samples	11152	driver-conductor
samples	5772	clustered-service-101-0
samples	4981	consensus-module-101-0
samples	4780	archive-conductor
samples	1320	/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	1163	core-matcher-1
samples	1160	core-matcher-0
ownerInclusive	98220	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012912f708.run
ownerInclusive	98220	java.lang.Thread.run
ownerInclusive	98220	java.lang.Thread.runWith
ownerInclusive	98220	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	97422	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
ownerInclusive	93844	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	93388	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	65079	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	61705	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	41619	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	29467	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
ownerInclusive	17698	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
allocation	8390744720	trading-owner--1 [B
allocation	4429400072	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	4399533112	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	4346012200	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	4327835808	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	4238442016	core-account-lane-1 [J
allocation	4224592200	clustered-service-101-0 [B
allocation	4100254784	core-account-lane-2 [J
allocation	4073348464	core-account-lane-0 [J
allocation	3957456904	core-account-lane-3 [J
allocation	3843564680	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	3770236000	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocationSite	17837521600	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	8503132952	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	7786772128	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	7339155248	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	6605509824	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	6519364968	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	5823540512	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	4933762120	java.nio.ByteBuffer.allocate
allocationSite	4468327936	java.util.List.copyOf
allocationSite	4281826984	com.surprising.aeron.service.state.model.AssetBalance.validAsset
allocationSite	4238118168	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
allocationSite	4194251872	java.lang.invoke.VarHandleLongs$Array.setRelease
parkOrMonitorNs	300524626978	Common-Cleaner
parkOrMonitorNs	297702661018	aeron-md-nra
parkOrMonitorNs	4829171129	archive-conductor
parkOrMonitorNs	3481059980	core-matcher-1
parkOrMonitorNs	3377125108	core-matcher-0
parkOrMonitorNs	3126715904	consensus-module-101-0
parkOrMonitorNs	1356182323	driver-conductor
parkOrMonitorNs	370282348	/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
eventCounts	569035	jdk.ExecutionSample
eventCounts	299601	jdk.GCPhaseParallel
eventCounts	279875	jdk.ThreadPark
eventCounts	87010	jdk.ObjectAllocationSample
eventCounts	45758	jdk.PromoteObjectInNewPLAB
eventCounts	18108	jdk.ThreadSleep
eventCounts	14353	jdk.NativeMethodSample
eventCounts	8073	jdk.NativeMemoryUsage
eventCounts	7995	jdk.TenuringDistribution
eventCounts	4561	jdk.ThreadCPULoad
eventCounts	2750	jdk.PromoteObjectOutsidePLAB
eventCounts	2132	jdk.MetaspaceChunkFreeListSummary

```

profile-owner.txt
```text
ownerSamples=98220
focusedSamples
11210	TradingRuntimeState.collectMatcherSettlement
10059	TerminalStateRetention.
8636	TerminalTombstoneStore.
6482	TradingCoreRuntime.prepareClusterPipelineScope
5085	TradingRuntimeState.collectCancel
5027	OrderBatchExecutor.preparePipelinedPlaceBatch
3895	MatcherSettlementPlan.build
3893	ActiveOrderIndex.applySnapshot
2998	RuntimeIdentityRegistry.releaseClientKey
2725	TradingOrderBatchCodec.decodeCommand
2620	TradingRuntimeState.clearChangedKeys
1310	TerminalTombstoneStore.put
1127	ClusterCommandWindow.add
871	TradingRuntimeState.readyLaneMask
643	DeterministicExchangeCoreAdapter.matcherShardId
455	OrderBatchExecutor.decodeOrderBatch
exactOrderBatchItemAllocation=0
ownerInclusive
98220	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012912f708.run
98220	java.lang.Thread.run
98220	java.lang.Thread.runWith
98220	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
97422	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
93844	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
93388	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
65079	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
61685	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
41619	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
29467	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
17698	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
14187	com.surprising.aeron.service.execution.TradingCoreRuntime.applyDecodedCommand
14057	com.surprising.aeron.service.execution.TradingCoreRuntime.apply
12464	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchReadyPlaceSettlements
12153	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchPartitionSettlements
11210	com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
9136	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed
8872	com.surprising.aeron.service.execution.OrderBatchExecutor.beginOrderBatchMatching
8337	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeCommitPublicationBatch
8331	com.surprising.aeron.service.execution.OrderedCommitCoordinator.publishCommittedChanges
8315	com.surprising.aeron.service.execution.OrderedCommitCoordinator$OwnerCommitPublisher.execute
8034	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.commitTerminalToOwner
6523	com.surprising.aeron.service.execution.OrderBatchExecutor.tryActivatePipelinedOrderBatch
6482	com.surprising.aeron.service.execution.TradingCoreRuntime.prepareClusterPipelineScope
6414	com.surprising.aeron.service.execution.OrderBatchExecutor.dispatchOrderBatchLaneWork
6214	com.surprising.aeron.service.state.TradingRuntimeState.dispatchMatcherSettlementBatch
6208	com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch
5159	org.agrona.collections.Long2LongHashMap.get
5104	com.surprising.aeron.service.state.RuntimeFactIndexes.applyCurrent
5085	com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
5027	com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
5003	com.surprising.aeron.service.state.TradingRuntimeState.visitChangedIndexes
4708	com.surprising.aeron.service.state.OwnerIndexedChanges.forEachIndexed
4353	com.surprising.aeron.service.execution.MatchingCommandAdmission.beginMatching
4321	com.surprising.aeron.service.execution.MatchingCommandAdmission.prepareMatching
4052	com.surprising.aeron.service.execution.TradingCoreRuntime.drainMatchingCompletions
3972	com.surprising.aeron.service.state.TradingRuntimeState.lambda$visitChangedIndexes$0
3972	com.surprising.aeron.service.state.RuntimeFactIndexes.preparedOrder
3972	com.surprising.aeron.service.state.TradingRuntimeState$$Lambda.0x0000000129194000.accept
3893	com.surprising.aeron.service.state.index.ActiveOrderIndex.applySnapshot
3738	com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
3672	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeDispatchedMatcherSettlement
3670	com.surprising.aeron.service.state.MatcherSettlementPlan.build
3600	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeDispatchedCancel
3566	com.surprising.aeron.service.state.MatcherSettlementPlan.buildBatchItem
3478	org.agrona.collections.Long2LongHashMap.remove
3456	com.surprising.aeron.service.execution.TradingCoreRuntime.requireOrderIdentityAvailable
3456	com.surprising.aeron.service.execution.TerminalStateRetention.containsOrder
3455	com.surprising.aeron.service.execution.TerminalStateRetention.contains
3356	org.agrona.collections.Long2LongHashMap.containsKey
3332	com.surprising.aeron.service.execution.TerminalStateRetention.completeSequence
3322	com.surprising.aeron.service.execution.TerminalStateRetention.trimTombstones
3311	com.surprising.aeron.service.execution.TerminalTombstoneStore.contains
3271	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges$$Lambda.0x00000001291b9180.accept
3271	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.lambda$commitTerminalToOwner$0
3271	com.surprising.aeron.service.execution.TerminalStateRetention.accept
3258	com.surprising.aeron.service.execution.DeferredSessionResponses.poll
3231	com.surprising.aeron.service.execution.TerminalTombstoneStore.trim
3212	java.util.concurrent.ConcurrentHashMap.get
3170	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges$ClientIdentityReleaseBuffer.release
3170	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.releaseRetiredClientIdentities
3164	com.surprising.aeron.service.execution.ClusterCommandWindow.decoded
3152	com.surprising.aeron.service.execution.TerminalStateRetention.retainPrunedOrder
3031	com.surprising.aeron.service.execution.DecodedMatchingCommand.decode
ownerLeaf
4300	org.agrona.collections.Long2LongHashMap.get:208
3664	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:226
3111	com.surprising.aeron.service.execution.DeferredSessionResponses.poll:85
2428	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:294
2262	java.util.concurrent.ConcurrentHashMap.get:949
1626	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each:571
1435	org.agrona.collections.Long2LongHashMap.remove:760
1323	com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length:342
1302	jdk.internal.misc.Unsafe.putIntUnaligned:3715
1177	org.agrona.collections.Long2LongHashMap.compactChain:878
988	java.util.HashMap.hash:338
978	com.surprising.aeron.service.execution.PendingMatchingRing.partitionDispatchHead:209
968	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed:56
956	java.nio.HeapByteBuffer.put:221
950	com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey:256
870	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching:1140
767	java.util.Arrays.fill:3143
761	com.surprising.aeron.service.state.RuntimeCommitJournal.beginPublicationBatch:196
706	java.util.concurrent.ConcurrentHashMap.get:957
699	org.agrona.collections.Long2LongHashMap.get:218
592	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchPartitionSettlements:1209
590	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix:298
584	java.lang.ThreadLocal.get:171
577	com.surprising.aeron.service.execution.ClusterCommandWindow.complete:200
554	com.surprising.aeron.service.execution.TerminalTombstoneStore.unlinkClient:67
552	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1040
545	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1769
536	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
524	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.add:216
520	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
511	org.agrona.collections.Long2LongHashMap.compactChain:882
510	java.util.HashMap.getNode:585
493	com.surprising.aeron.service.execution.PendingMatchingRing.findFirst:161
492	com.surprising.aeron.service.state.TradingRuntimeState.assertAccountLanesHealthy:503
489	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed:54
ownerAllocationClass
8390744720	[B
3182181784	com.surprising.aeron.protocol.PlaceOrderCommand
2150658240	[J
1731892360	java.lang.String
842359624	[I
798280672	com.surprising.aeron.service.execution.CommandResultLedger$StoredResult
782612224	[Ljava.lang.Object;
770366872	java.lang.Long
756272408	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001291ee0c0
663345896	com.surprising.aeron.protocol.CoreResponse
379323928	com.surprising.aeron.protocol.CoreOrderStateView
356081728	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001291edc60
351955288	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001291ede90
312432176	com.surprising.aeron.protocol.CoreMessageHeader
310500608	com.surprising.aeron.service.state.ResolvedPlaceOrder
308947168	java.util.HashMap$Node
292192208	java.nio.HeapByteBuffer
289461648	java.util.LinkedHashMap$Entry
288635232	com.surprising.aeron.protocol.CancelOrderCommand
286078328	java.util.ImmutableCollections$ListItr
271552208	com.surprising.aeron.service.state.CoreOrderDecisionResolver$Context
252126384	com.surprising.aeron.service.execution.ImmutableLongArrayList
245081112	org.eclipse.collections.impl.set.mutable.primitive.IntHashSet
239603496	java.util.ArrayList$Itr
231174792	com.surprising.aeron.service.execution.ContinuousTradingClusterService$Output
ownerAllocationPaths
8449912968	TradingCoreRuntime.prepareClusterPipelineScope
8041554344	TradingOrderBatchCodec.decodeCommand
1800319472	ActiveOrderIndex.applySnapshot
579297888	TradingRuntimeState.collectMatcherSettlement
396597928	OrderBatchExecutor.preparePipelinedPlaceBatch
228417352	TradingRuntimeState.collectCancel
171288504	MatcherSettlementPlan.build
laneLeaf
363031	com.surprising.aeron.service.state.SettlementLaneWorker.run:154
6727	java.lang.ThreadLocal.get:171
4823	com.surprising.aeron.service.state.SettlementLaneWorker.run:136
4395	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
2868	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probeThree:3090
2487	java.lang.ThreadLocal.get:184
2284	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
1412	java.util.concurrent.ConcurrentHashMap.get:949
1190	java.util.ImmutableCollections$ListItr.next:397
1089	com.surprising.aeron.service.state.OrderRuntime.<init>:47
1083	com.surprising.aeron.service.state.SettlementLaneWorker.run:137
830	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.probeThree:1242
ownerParkMonitorNs
afterGCminute=0 count=104 minMiB=100.747 averageMiB=104.134 maxMiB=107.121
afterGCminute=1 count=106 minMiB=102.882 averageMiB=106.074 maxMiB=109.260
afterGCminute=2 count=103 minMiB=104.498 averageMiB=108.001 maxMiB=111.972
afterGCminute=3 count=110 minMiB=106.855 averageMiB=110.219 maxMiB=114.886
afterGCminute=4 count=110 minMiB=109.762 averageMiB=112.841 maxMiB=116.614

```

gates.log
```text
SPOT execute productLineGate=PASS mode=execute productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT replay productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT snapshotPosition=3616
SPOT snapshot productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL execute productLineGate=PASS mode=execute productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL replay productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL snapshotPosition=9568
LINEAR_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL execute productLineGate=PASS mode=execute productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL replay productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL snapshotPosition=9568
INVERSE_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY execute productLineGate=PASS mode=execute productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY replay productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY snapshotPosition=5184
LINEAR_DELIVERY snapshot productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY execute productLineGate=PASS mode=execute productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY replay productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY snapshotPosition=5184
INVERSE_DELIVERY snapshot productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
OPTION execute productLineGate=PASS mode=execute productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION replay productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION snapshotPosition=5184
OPTION snapshot productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701

```

recovery.log
```text
master replay PASS businessHash=b2cce7f5c89a82d0
master snapshotPosition=5106394048
master snapshot PASS businessHash=b2cce7f5c89a82d0

```

master-control-INVERSE_DELIVERY-0
```text
Iteration   2: accountControlVerify=PASS product=INVERSE_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
ClusterAccountControlBenchmark.accountControls                     INVERSE_DELIVERY    ss    2     3089.138           ms/op
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate       INVERSE_DELIVERY    ss    2        0.988          MB/sec
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate.norm  INVERSE_DELIVERY    ss    2  3243904.000            B/op
ClusterAccountControlBenchmark.accountControls:gc.count            INVERSE_DELIVERY    ss    2          ≈ 0          counts
```

master-control-INVERSE_PERPETUAL-0
```text
Iteration   2: accountControlVerify=PASS product=INVERSE_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
ClusterAccountControlBenchmark.accountControls                     INVERSE_PERPETUAL    ss    2     3075.371           ms/op
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate       INVERSE_PERPETUAL    ss    2        1.207          MB/sec
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate.norm  INVERSE_PERPETUAL    ss    2  3936768.000            B/op
ClusterAccountControlBenchmark.accountControls:gc.count            INVERSE_PERPETUAL    ss    2          ≈ 0          counts
```

master-control-LINEAR_DELIVERY-0
```text
Iteration   2: accountControlVerify=PASS product=LINEAR_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
ClusterAccountControlBenchmark.accountControls                     LINEAR_DELIVERY    ss    2     3028.005           ms/op
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate       LINEAR_DELIVERY    ss    2        1.211          MB/sec
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate.norm  LINEAR_DELIVERY    ss    2  3896040.000            B/op
ClusterAccountControlBenchmark.accountControls:gc.count            LINEAR_DELIVERY    ss    2          ≈ 0          counts
```

master-control-LINEAR_PERPETUAL-0
```text
Iteration   2: accountControlVerify=PASS product=LINEAR_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
ClusterAccountControlBenchmark.accountControls                     LINEAR_PERPETUAL    ss    2     3125.722           ms/op
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate       LINEAR_PERPETUAL    ss    2        1.041          MB/sec
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate.norm  LINEAR_PERPETUAL    ss    2  3465008.000            B/op
ClusterAccountControlBenchmark.accountControls:gc.count            LINEAR_PERPETUAL    ss    2          ≈ 0          counts
```

master-control-OPTION-0
```text
Iteration   2: accountControlVerify=PASS product=OPTION terminalBusinessOperations=1800 unfinished=0 fundsDiff=0 netPosition=0
ClusterAccountControlBenchmark.accountControls                            OPTION    ss    2     2299.593           ms/op
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate              OPTION    ss    2        1.217          MB/sec
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate.norm         OPTION    ss    2  2989496.000            B/op
ClusterAccountControlBenchmark.accountControls:gc.count                   OPTION    ss    2          ≈ 0          counts
```

master-control-SPOT-0
```text
Iteration   2: accountControlVerify=PASS product=SPOT terminalBusinessOperations=600 unfinished=0 fundsDiff=0 netPosition=0
ClusterAccountControlBenchmark.accountControls                              SPOT    ss    2      819.381           ms/op
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate                SPOT    ss    2        1.401          MB/sec
ClusterAccountControlBenchmark.accountControls:gc.alloc.rate.norm           SPOT    ss    2  1270736.000            B/op
ClusterAccountControlBenchmark.accountControls:gc.count                     SPOT    ss    2          ≈ 0          counts
```

第一轮原始校验信息（结束后统一清理）
```text
/tmp/owner-master-opt/perf.py bytes=5651 sha256=87986fa17c2cc275c89a77dc82f9adbe3cd2ac42be8a441641267c70475ad3d4
/tmp/owner-master-opt/owner.jfc bytes=39826 sha256=dca13cdc2a8f9d4a88f6dc954dd26cf8d2f85da5e4f922cac4b71f6c86c671d3
/tmp/owner-master-opt/gates.py bytes=5026 sha256=24ab47dbca207380c725a54b4b69ae2ea4248d90e731e2b25faf75e73b76e6fb
/tmp/owner-master-opt/recovery.py bytes=3367 sha256=40aff5a0e6eeb36f1f6330569ca0c31538ff10ab386cf255f52eab7fae126874
/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/node.jfr bytes=40967391 sha256=20c56d2dd5b78446d088b2632ba32b9723c4d43f425343edf8aa1daaf825400d
serviceJarSha256=9dddc6c3c4a39d297716088fa3cb2ee69fb97272e8cbf6ddf9e4831d6e689a66
```

### 最终索引修正版：重新采集前定义

- 最终代码987ff387；完整verify成功，当前surefire汇总[1224, 0, 0, 1]（tests/failures/errors/skipped）。上一版初次编译因遗留entities.length引用失败，改为协议实体类别数量常量后全部通过；不是交易运行失败。实体FIFO覆写保留插入顺序、客户索引替换/淘汰、槽复用与类型隔离已补回归。
- 本轮保持前述锁定参数、seed、业务、CPU/JVM、256在途、单成员/4Lane/2matcher配置不变；重新执行六产品execute/强杀replay/snapshot门禁；无profiler60s主测、30s预热+300s JMH/JFR长测，随后六产品accountControls JMH/JFR与长日志replay/snapshot。不重跑080758da或其他旧分支作对照。
- 产物目录/tmp/owner-master-opt/gates-final与perf-final；测量后校验结束，再执行一次GC.run、GC.heap_info、GC.class_histogram -all，均在测量窗口之外，不把它们混入吞吐或延迟。记录GC前后堆占用，用于解释young-GC后趋势，仍不作为长期无泄漏证明。其他通过标准与局限不变；若最终热点不能改善如实记录，不以零分配代替CPU/吞吐收益。

第一轮补充记录与产物清理

master-control-INVERSE_DELIVERY-0
```text
nmt-before.txt
Total: reserved=3148565KB, committed=692149KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=60555KB, committed=1967KB)
-                      Code (reserved=250322KB, committed=15926KB)
-                        GC (reserved=91619KB, committed=71179KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138342KB, committed=696994KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2583KB)
-                      Code (reserved=252477KB, committed=22917KB)
-                        GC (reserved=91791KB, committed=71311KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2266698 sha256=c47c28348aa92e9d8fe01c19c06d933f0f71352ccdbcc4051d177ef00326ee4e
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"INVERSE_DELIVERY"},"primaryMetric":{"score":3089.138256,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3084.044618,"50.0":3089.138256,"90.0":3094.231894,"95.0":3094.231894,"99.0":3094.231894,"99.9":3094.231894,"99.99":3094.231894,"99.999":3094.231894,"99.9999":3094.231894,"100.0":3094.231894},"scoreUnit":"ms/op","rawData":[[3094.231894,3084.044618]]},"secondaryMetrics":{"gc.alloc.rate":{"score":0.9880015423463451,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.8405212840433199,"50.0":0.9880015423463451,"90.0":1.1354818006493703,"95.0":1.1354818006493703,"99.0":1.1354818006493703,"99.9":1.1354818006493703,"99.99":1.1354818006493703,"99.999":1.1354818006493703,"99.9999":1.1354818006493703,"100.0":1.1354818006493703},"scoreUnit":"MB/sec","rawData":[[0.8405212840433199,1.1354818006493703]]},"gc.alloc.rate.norm":{"score":3243904.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2727432.0,"50.0":3243904.0,"90.0":3760376.0,"95.0":3760376.0,"99.0":3760376.0,"99.9":3760376.0,"99.99":3760376.0,"99.999":3760376.0,"99.9999":3760376.0,"100.0":3760376.0},"scoreUnit":"B/op","rawData":[[2727432.0,3760376.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=INVERSE_DELIVERY","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-control-INVERSE_DELIVERY-0/jmh.json"]]
```

master-control-INVERSE_PERPETUAL-0
```text
nmt-before.txt
Total: reserved=3144490KB, committed=692154KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=56455KB, committed=1883KB)
-                      Code (reserved=250348KB, committed=16016KB)
-                        GC (reserved=91621KB, committed=71181KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138367KB, committed=696563KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2579KB)
-                      Code (reserved=252515KB, committed=22567KB)
-                        GC (reserved=91775KB, committed=71295KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2277686 sha256=b7c3a2e79bf9273a48deab12332fa1a67bc605c4fdb95283ecd3512b3201c0ff
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"INVERSE_PERPETUAL"},"primaryMetric":{"score":3075.371246,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3035.486631,"50.0":3075.371246,"90.0":3115.255861,"95.0":3115.255861,"99.0":3115.255861,"99.9":3115.255861,"99.99":3115.255861,"99.999":3115.255861,"99.9999":3115.255861,"100.0":3115.255861},"scoreUnit":"ms/op","rawData":[[3115.255861,3035.486631]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.2072081431773771,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.0329410731726085,"50.0":1.2072081431773771,"90.0":1.381475213182146,"95.0":1.381475213182146,"99.0":1.381475213182146,"99.9":1.381475213182146,"99.99":1.381475213182146,"99.999":1.381475213182146,"99.9999":1.381475213182146,"100.0":1.381475213182146},"scoreUnit":"MB/sec","rawData":[[1.0329410731726085,1.381475213182146]]},"gc.alloc.rate.norm":{"score":3936768.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3374488.0,"50.0":3936768.0,"90.0":4499048.0,"95.0":4499048.0,"99.0":4499048.0,"99.9":4499048.0,"99.99":4499048.0,"99.999":4499048.0,"99.9999":4499048.0,"100.0":4499048.0},"scoreUnit":"B/op","rawData":[[3374488.0,4499048.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=INVERSE_PERPETUAL","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-control-INVERSE_PERPETUAL-0/jmh.json"]]
```

master-control-LINEAR_DELIVERY-0
```text
nmt-before.txt
Total: reserved=3146378KB, committed=692038KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=58505KB, committed=1929KB)
-                      Code (reserved=250357KB, committed=16025KB)
-                        GC (reserved=91615KB, committed=71175KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138303KB, committed=696699KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2519KB)
-                      Code (reserved=252479KB, committed=22791KB)
-                        GC (reserved=91800KB, committed=71320KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2271412 sha256=7059e5116dae1ac086f98f5089304d3cb185430632b766fdf8c134e232604bda
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"LINEAR_DELIVERY"},"primaryMetric":{"score":3028.0046380000003,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2994.026812,"50.0":3028.0046380000003,"90.0":3061.982464,"95.0":3061.982464,"99.0":3061.982464,"99.9":3061.982464,"99.99":3061.982464,"99.999":3061.982464,"99.9999":3061.982464,"100.0":3061.982464},"scoreUnit":"ms/op","rawData":[[3061.982464,2994.026812]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.2110009117238374,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.0382370187154537,"50.0":1.2110009117238374,"90.0":1.383764804732221,"95.0":1.383764804732221,"99.0":1.383764804732221,"99.9":1.383764804732221,"99.99":1.383764804732221,"99.999":1.383764804732221,"99.9999":1.383764804732221,"100.0":1.383764804732221},"scoreUnit":"MB/sec","rawData":[[1.0382370187154537,1.383764804732221]]},"gc.alloc.rate.norm":{"score":3896040.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3333840.0,"50.0":3896040.0,"90.0":4458240.0,"95.0":4458240.0,"99.0":4458240.0,"99.9":4458240.0,"99.99":4458240.0,"99.999":4458240.0,"99.9999":4458240.0,"100.0":4458240.0},"scoreUnit":"B/op","rawData":[[3333840.0,4458240.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=LINEAR_DELIVERY","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-control-LINEAR_DELIVERY-0/jmh.json"]]
```

master-control-LINEAR_PERPETUAL-0
```text
nmt-before.txt
Total: reserved=3144339KB, committed=692083KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=56455KB, committed=1963KB)
-                      Code (reserved=250291KB, committed=15959KB)
-                        GC (reserved=91616KB, committed=71176KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138231KB, committed=696779KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2607KB)
-                      Code (reserved=252399KB, committed=22711KB)
-                        GC (reserved=91806KB, committed=71326KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2273270 sha256=e839b1089f2ef0c28e028a465738cb1fef0bf4d97914bfdb7363f3bd9a204cd9
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"LINEAR_PERPETUAL"},"primaryMetric":{"score":3125.7224785,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3124.176648,"50.0":3125.7224785,"90.0":3127.268309,"95.0":3127.268309,"99.0":3127.268309,"99.9":3127.268309,"99.99":3127.268309,"99.999":3127.268309,"99.9999":3127.268309,"100.0":3127.268309},"scoreUnit":"ms/op","rawData":[[3124.176648,3127.268309]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.0409644194114438,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.7565717636868786,"50.0":1.0409644194114438,"90.0":1.3253570751360089,"95.0":1.3253570751360089,"99.0":1.3253570751360089,"99.9":1.3253570751360089,"99.99":1.3253570751360089,"99.999":1.3253570751360089,"99.9999":1.3253570751360089,"100.0":1.3253570751360089},"scoreUnit":"MB/sec","rawData":[[0.7565717636868786,1.3253570751360089]]},"gc.alloc.rate.norm":{"score":3465008.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2478688.0,"50.0":3465008.0,"90.0":4451328.0,"95.0":4451328.0,"99.0":4451328.0,"99.9":4451328.0,"99.99":4451328.0,"99.999":4451328.0,"99.9999":4451328.0,"100.0":4451328.0},"scoreUnit":"B/op","rawData":[[2478688.0,4451328.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=LINEAR_PERPETUAL","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-control-LINEAR_PERPETUAL-0/jmh.json"]]
```

master-control-OPTION-0
```text
nmt-before.txt
Total: reserved=3148337KB, committed=691969KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=60555KB, committed=1951KB)
-                      Code (reserved=250311KB, committed=15979KB)
-                        GC (reserved=91622KB, committed=71182KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3137979KB, committed=695855KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2515KB)
-                      Code (reserved=252320KB, committed=22116KB)
-                        GC (reserved=91787KB, committed=71307KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2048835 sha256=5f3354862d909ee95c44ed97432eeb26005d57a0026cf5972f481fee736b71b1
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-OPTION-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"OPTION"},"primaryMetric":{"score":2299.593095,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2277.445828,"50.0":2299.593095,"90.0":2321.740362,"95.0":2321.740362,"99.0":2321.740362,"99.9":2321.740362,"99.99":2321.740362,"99.999":2321.740362,"99.9999":2321.740362,"100.0":2321.740362},"scoreUnit":"ms/op","rawData":[[2321.740362,2277.445828]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.2172745432534602,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.01887599625656,"50.0":1.2172745432534602,"90.0":1.4156730902503603,"95.0":1.4156730902503603,"99.0":1.4156730902503603,"99.9":1.4156730902503603,"99.99":1.4156730902503603,"99.999":1.4156730902503603,"99.9999":1.4156730902503603,"100.0":1.4156730902503603},"scoreUnit":"MB/sec","rawData":[[1.01887599625656,1.4156730902503603]]},"gc.alloc.rate.norm":{"score":2989496.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2480832.0,"50.0":2989496.0,"90.0":3498160.0,"95.0":3498160.0,"99.0":3498160.0,"99.9":3498160.0,"99.99":3498160.0,"99.999":3498160.0,"99.9999":3498160.0,"100.0":3498160.0},"scoreUnit":"B/op","rawData":[[2480832.0,3498160.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-OPTION-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-control-OPTION-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-control-OPTION-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-OPTION-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=OPTION","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-control-OPTION-0/jmh.json"]]
```

master-control-SPOT-0
```text
nmt-before.txt
Total: reserved=3142406KB, committed=692098KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1860KB)
-                      Code (reserved=250348KB, committed=16016KB)
-                        GC (reserved=91626KB, committed=71186KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3137311KB, committed=693403KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2467KB)
-                      Code (reserved=252054KB, committed=21138KB)
-                        GC (reserved=91803KB, committed=71323KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=1624558 sha256=4849cbd33d796eaa604a0d2bf67a088ef65dd814a37fbf1bd9c1e75db0cd65ca
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-SPOT-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"SPOT"},"primaryMetric":{"score":819.3810785000001,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":791.17611,"50.0":819.3810785000001,"90.0":847.586047,"95.0":847.586047,"99.0":847.586047,"99.9":847.586047,"99.99":847.586047,"99.999":847.586047,"99.9999":847.586047,"100.0":847.586047},"scoreUnit":"ms/op","rawData":[[847.586047,791.17611]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.4013060992239277,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.9015324381600077,"50.0":1.4013060992239277,"90.0":1.9010797602878478,"95.0":1.9010797602878478,"99.0":1.9010797602878478,"99.9":1.9010797602878478,"99.99":1.9010797602878478,"99.999":1.9010797602878478,"99.9999":1.9010797602878478,"100.0":1.9010797602878478},"scoreUnit":"MB/sec","rawData":[[0.9015324381600077,1.9010797602878478]]},"gc.alloc.rate.norm":{"score":1270736.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":801488.0,"50.0":1270736.0,"90.0":1739984.0,"95.0":1739984.0,"99.0":1739984.0,"99.9":1739984.0,"99.99":1739984.0,"99.999":1739984.0,"99.9999":1739984.0,"100.0":1739984.0},"scoreUnit":"B/op","rawData":[[801488.0,1739984.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-SPOT-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-control-SPOT-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-control-SPOT-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-control-SPOT-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=SPOT","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-control-SPOT-0/jmh.json"]]
```

master-main-LINEAR_PERPETUAL-0
```text
nmt-before.txt
Total: reserved=3120506KB, committed=667318KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=51323KB, committed=1743KB)
-                      Code (reserved=249344KB, committed=12432KB)
-                        GC (reserved=91611KB, committed=71171KB)
-                     Other (reserved=9385KB, committed=9385KB)
nmt-after.txt
Total: reserved=3128109KB, committed=704217KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=49290KB, committed=2118KB)
-                      Code (reserved=261784KB, committed=46352KB)
-                        GC (reserved=92496KB, committed=72024KB)
-                     Other (reserved=9387KB, committed=9387KB)
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-main-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-main-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-main-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-main-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-main-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=60","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain"]]
```

master-profile-LINEAR_PERPETUAL-0
```text
nmt-before.txt
Total: reserved=3140267KB, committed=690799KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=52354KB, committed=1874KB)
-                      Code (reserved=250248KB, committed=14756KB)
-                        GC (reserved=91615KB, committed=71175KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3168479KB, committed=736259KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=64673KB, committed=2813KB)
-                      Code (reserved=262492KB, committed=50348KB)
-                        GC (reserved=92400KB, committed=71928KB)
-                     Other (reserved=9413KB, committed=9413KB)
JFR bytes=40967391 sha256=20c56d2dd5b78446d088b2632ba32b9723c4d43f425343edf8aa1daaf825400d
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":0,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":1,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"controlPageSize":"0"},"primaryMetric":{"score":300.053717812,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":300.053717812,"50.0":300.053717812,"90.0":300.053717812,"95.0":300.053717812,"99.0":300.053717812,"99.9":300.053717812,"99.99":300.053717812,"99.999":300.053717812,"99.9999":300.053717812,"100.0":300.053717812},"scoreUnit":"s/op","rawData":[[300.053717812]]},"secondaryMetrics":{"gc.alloc.rate":{"score":168.28002737626585,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":168.28002737626585,"50.0":168.28002737626585,"90.0":168.28002737626585,"95.0":168.28002737626585,"99.0":168.28002737626585,"99.9":168.28002737626585,"99.99":168.28002737626585,"99.999":168.28002737626585,"99.9999":168.28002737626585,"100.0":168.28002737626585},"scoreUnit":"MB/sec","rawData":[[168.28002737626585]]},"gc.alloc.rate.norm":{"score":65004661176.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":65004661176.0,"50.0":65004661176.0,"90.0":65004661176.0,"95.0":65004661176.0,"99.0":65004661176.0,"99.9":65004661176.0,"99.99":65004661176.0,"99.999":65004661176.0,"99.9999":65004661176.0,"100.0":65004661176.0},"scoreUnit":"B/op","rawData":[[65004661176.0]]},"gc.count":{"score":833.0,"scoreError":"NaN","scoreConfidence":[833.0,833.0],"scorePercentiles":{"0.0":833.0,"50.0":833.0,"90.0":833.0,"95.0":833.0,"99.0":833.0,"99.9":833.0,"99.99":833.0,"99.999":833.0,"99.9999":833.0,"100.0":833.0},"scoreUnit":"counts","rawData":[[833.0]]},"gc.time":{"score":701.0,"scoreError":"NaN","scoreConfidence":[701.0,701.0],"scorePercentiles":{"0.0":701.0,"50.0":701.0,"90.0":701.0,"95.0":701.0,"99.0":701.0,"99.9":701.0,"99.99":701.0,"99.999":701.0,"99.9999":701.0,"100.0":701.0},"scoreUnit":"ms","rawData":[[701.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterOperationalBenchmark.continuousOperations","-f","1","-wi","0","-i","1","-p","controlPageSize=0","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf/master-profile-LINEAR_PERPETUAL-0/jmh.json"]]
```

第一轮已停止进程并清理perf/gates目录，共13581884940字节。第一轮原始JFR/Archive/日志路径不可再访问；最终修正版的gates-final/perf-final继续运行，未删除其产物。

### 987ff387 索引解链版结果与最终小修

- 六产品execute/强杀replay/snapshot与六产品accountControls JMH/JFR均PASS；长测重放和snapshot重启hash a9d128a7f70e70cc、snapshotPosition5296936992。无profiler主测194554.083 business ops/s；5min JMH/JFR171555.851 business ops/s，资金/持仓/冻结/终态PASS、unfinished0。降频不一致，不能给受控加速比。
- Owner样本98474，终态管理8179=8.306%，比上一版10.24%下降但仍高于最初4.57%；entitySlot叶子3641=3.697%。继续增加每类一个long保守ID上界：仅id大于所有已插入ID上界才省去确定不命中的查询；乱序和旧ID仍精确查表，淘汰不降低上界，恢复从当前FIFO重建。没有假设客户端顺序发号或删除去重。
- readyLaneMask3672=3.729%，上一版0.887%的占比下降未维持，不能宣称轮询热点彻底消失；matcherShardId587=0.596%，decodeCommand2838=2.882%。OrderBatchItem分配采样0，窗口/终态索引与client-key释放路径分配采样0。节点约3193.9B/business op，仍非零分配。Owner97.217%，matcher24.165/24.444%，Lane约97.4%但大量队列轮询，未全部有效饱和。
- GC后heap分钟均值106.876/108.282/109.746/111.404/113.327MiB；结束校验后used335128KiB，显式GC后83929KiB（81.96MiB）。存在可回收部分，不能把young-GC后趋势当作泄漏，也不能证明长期heap/native无泄漏。
- 最后一小修仅增加固定4个long界，不改引用生命周期/协议/快照；重跑受影响回归、六产品恢复、主测60s与JMH/JFR90s、六产品控制及日志恢复。5min证据归属于987ff387，不能冒称最后小修也运行5min。

final-summary.txt
```text
totals allocationMiBps=522.545 allocationBytes=164390947008 machineCPU=88.34 jvmCPU=57.72 heapMaxMiB=401.36 dataLoss=0 ownerIOEvents=0
threadCPU	97.432	core-account-lane-3
threadCPU	97.432	core-account-lane-2
threadCPU	97.407	core-account-lane-1
threadCPU	97.391	core-account-lane-0
threadCPU	97.217	trading-owner--1
threadCPU	96.174	/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	72.698	clustered-service-101-0
threadCPU	71.370	driver-conductor
threadCPU	63.728	archive-conductor
threadCPU	62.237	consensus-module-101-0
threadCPU	24.444	core-matcher-1
threadCPU	24.165	core-matcher-0
threadCPU	2.065	JVMCI-native CompilerThread0
threadCPU	1.579	aeron-md-nra
threadCPU	1.559	C1 CompilerThread1
threadCPU	0.332	JFR Periodic Tasks
threadCPU	0.244	C1 CompilerThread0
threadCPU	0.183	aeron-client
threadCPU	0.180	JFR Recorder Thread
threadCPU	0.102	Monitor Deflation Thread
gc count=551 totalMs=3313.011 p99Ms=10.165 maxMs=14.058
samples	113384	core-account-lane-0
samples	112012	core-account-lane-1
samples	110018	core-account-lane-2
samples	107479	core-account-lane-3
samples	98474	trading-owner--1
samples	11146	driver-conductor
samples	5863	clustered-service-101-0
samples	4279	consensus-module-101-0
samples	4040	archive-conductor
samples	1490	/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	1200	core-matcher-1
samples	1139	core-matcher-0
ownerInclusive	98474	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012912f708.run
ownerInclusive	98474	java.lang.Thread.run
ownerInclusive	98474	java.lang.Thread.runWith
ownerInclusive	98474	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	97619	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
ownerInclusive	97024	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	96017	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	68338	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	64940	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	41502	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	28826	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
ownerInclusive	21668	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
allocation	8704988320	trading-owner--1 [B
allocation	4723335776	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	4512273920	core-account-lane-3 [J
allocation	4443351576	core-account-lane-2 [J
allocation	4431316344	core-account-lane-1 [J
allocation	4422985936	core-account-lane-0 [J
allocation	4419957352	clustered-service-101-0 [B
allocation	4340954224	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	4282550512	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	4260589904	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	4126675160	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	3991686432	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocationSite	18803848160	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	10075444632	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	9138441016	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	8276393136	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	7692044360	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	6223508688	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	5033122080	java.nio.ByteBuffer.allocate
allocationSite	4601586896	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
allocationSite	4595727128	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	4329202792	java.lang.invoke.VarHandleLongs$Array.setRelease
allocationSite	4178369328	com.surprising.aeron.service.execution.CoreMessageFlyweightDecoder.decode
allocationSite	4160949488	com.surprising.aeron.service.state.model.AssetBalance.validAsset
parkOrMonitorNs	300524994181	Common-Cleaner
parkOrMonitorNs	297785382577	aeron-md-nra
parkOrMonitorNs	3721027363	archive-conductor
parkOrMonitorNs	3668795961	core-matcher-0
parkOrMonitorNs	3566544834	core-matcher-1
parkOrMonitorNs	2462139791	consensus-module-101-0
parkOrMonitorNs	1261161557	driver-conductor
parkOrMonitorNs	541783271	/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
eventCounts	570541	jdk.ExecutionSample
eventCounts	309954	jdk.GCPhaseParallel
eventCounts	278701	jdk.ThreadPark
eventCounts	85994	jdk.ObjectAllocationSample
eventCounts	46051	jdk.PromoteObjectInNewPLAB
eventCounts	18113	jdk.ThreadSleep
eventCounts	14444	jdk.NativeMethodSample
eventCounts	8265	jdk.TenuringDistribution
eventCounts	8073	jdk.NativeMemoryUsage
eventCounts	4540	jdk.ThreadCPULoad
eventCounts	2629	jdk.PromoteObjectOutsidePLAB
eventCounts	2204	jdk.MetaspaceChunkFreeListSummary

```

final-owner.txt
```text
ownerSamples=98474
focusedSamples
10952	TradingRuntimeState.collectMatcherSettlement
8179	TerminalStateRetention.
7466	TerminalTombstoneStore.
6445	TradingCoreRuntime.prepareClusterPipelineScope
4698	TradingRuntimeState.collectCancel
4595	OrderBatchExecutor.preparePipelinedPlaceBatch
4027	ActiveOrderIndex.applySnapshot
3837	MatcherSettlementPlan.build
3672	TradingRuntimeState.readyLaneMask
3230	RuntimeIdentityRegistry.releaseClientKey
2838	TradingOrderBatchCodec.decodeCommand
2729	TradingRuntimeState.clearChangedKeys
1237	TerminalTombstoneStore.put
1135	ClusterCommandWindow.add
587	DeterministicExchangeCoreAdapter.matcherShardId
445	OrderBatchExecutor.decodeOrderBatch
exactOrderBatchItemAllocation=0
ownerInclusive
98474	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012912f708.run
98474	java.lang.Thread.run
98474	java.lang.Thread.runWith
98474	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
97619	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
97024	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
96017	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
68338	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
64916	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
41502	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
28826	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
21668	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
13723	com.surprising.aeron.service.execution.TradingCoreRuntime.applyDecodedCommand
13591	com.surprising.aeron.service.execution.TradingCoreRuntime.apply
12889	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchReadyPlaceSettlements
11953	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchPartitionSettlements
10952	com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
9609	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed
8655	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeCommitPublicationBatch
8649	com.surprising.aeron.service.execution.OrderedCommitCoordinator.publishCommittedChanges
8640	com.surprising.aeron.service.execution.OrderedCommitCoordinator$OwnerCommitPublisher.execute
8575	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.commitTerminalToOwner
8492	com.surprising.aeron.service.execution.OrderBatchExecutor.beginOrderBatchMatching
6823	com.surprising.aeron.service.execution.TradingCoreRuntime.drainMatchingCompletions
6445	com.surprising.aeron.service.execution.TradingCoreRuntime.prepareClusterPipelineScope
6344	com.surprising.aeron.service.execution.OrderBatchExecutor.dispatchOrderBatchLaneWork
6154	com.surprising.aeron.service.execution.OrderBatchExecutor.tryActivatePipelinedOrderBatch
6125	com.surprising.aeron.service.state.TradingRuntimeState.dispatchMatcherSettlementBatch
6117	com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch
5312	com.surprising.aeron.service.state.RuntimeFactIndexes.applyCurrent
5212	com.surprising.aeron.service.state.TradingRuntimeState.visitChangedIndexes
4856	com.surprising.aeron.service.state.OwnerIndexedChanges.forEachIndexed
4771	com.surprising.aeron.service.execution.TradingCoreRuntime.progressPlaceAdmissions
4698	com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
4595	com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
4489	java.util.concurrent.ConcurrentHashMap.get
4086	com.surprising.aeron.service.state.TradingRuntimeState.lambda$visitChangedIndexes$0
4086	com.surprising.aeron.service.state.RuntimeFactIndexes.preparedOrder
4086	com.surprising.aeron.service.state.TradingRuntimeState$$Lambda.0x0000000129194000.accept
4082	com.surprising.aeron.service.execution.MatchingCommandAdmission.beginMatching
4053	com.surprising.aeron.service.execution.MatchingCommandAdmission.prepareMatching
4027	com.surprising.aeron.service.state.index.ActiveOrderIndex.applySnapshot
3979	com.surprising.aeron.service.execution.TerminalTombstoneStore.entitySlot
3874	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeDispatchedMatcherSettlement
3793	com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
3745	com.surprising.aeron.service.execution.TerminalTombstoneStore.contains
3696	java.util.HashMap.getNode
3672	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
3630	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeDispatchedCancel
3597	com.surprising.aeron.service.state.MatcherSettlementPlan.build
3562	java.util.HashMap.get
3554	com.surprising.aeron.service.state.MatcherSettlementPlan.buildBatchItem
3462	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges$$Lambda.0x00000001291b93b0.accept
3462	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.lambda$commitTerminalToOwner$0
3439	com.surprising.aeron.service.execution.TradingCoreRuntime.drainLaneReadyNotifications
3431	com.surprising.aeron.service.execution.TerminalStateRetention.accept
3364	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges$ClientIdentityReleaseBuffer.release
3364	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.releaseRetiredClientIdentities
3354	com.surprising.aeron.service.execution.TerminalStateRetention.retainPrunedOrder
3230	com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey
3227	com.surprising.aeron.service.execution.ClusterCommandWindow.decoded
3133	com.surprising.aeron.service.execution.DecodedMatchingCommand.decode
3070	com.surprising.aeron.service.execution.TradingCoreRuntime.requireOrderIdentityAvailable
3070	com.surprising.aeron.service.execution.TerminalStateRetention.containsOrder
3068	com.surprising.aeron.service.execution.TerminalStateRetention.contains
ownerLeaf
3641	com.surprising.aeron.service.execution.TerminalTombstoneStore.entitySlot:57
3527	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:226
3061	java.util.concurrent.ConcurrentHashMap.get:949
2190	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:294
2165	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1773
1679	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each:571
1468	java.util.HashMap.getNode:577
1304	jdk.internal.misc.Unsafe.putIntUnaligned:3715
1250	org.agrona.collections.Long2LongHashMap.get:208
1105	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed:56
1031	java.util.HashMap.hash:338
1026	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1036
1020	java.nio.HeapByteBuffer.put:221
1003	com.surprising.aeron.service.execution.PendingMatchingRing.partitionDispatchHead:209
953	com.surprising.aeron.service.state.LaneSequenceQueue.hasPending:45
856	com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length:342
749	org.agrona.collections.Long2LongHashMap.compactChain:878
721	com.surprising.aeron.service.execution.TerminalTombstoneStore.unlinkEntity:80
720	java.util.Arrays.fill:3143
712	java.util.concurrent.ConcurrentHashMap.get:957
670	com.surprising.aeron.service.execution.TerminalTombstoneStore.unlinkClient:97
649	java.lang.ThreadLocal.get:171
640	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
611	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
608	com.surprising.aeron.service.execution.TradingCoreRuntime.bindOwner:2478
587	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1040
584	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.add:216
554	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1769
544	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probeThree:3090
534	com.surprising.aeron.service.execution.ClusterCommandWindow.complete:200
504	com.surprising.aeron.service.execution.PendingMatchingRing.findFirst:161
500	com.surprising.aeron.service.execution.TerminalTombstoneStore.clientSlot:92
497	java.util.HashMap.getNode:585
494	com.surprising.aeron.service.state.LanePublishedMap.visible:71
486	com.surprising.aeron.service.state.RuntimeChangeBuffer.drainTo:156
ownerAllocationClass
8704988320	[B
3199208328	com.surprising.aeron.protocol.PlaceOrderCommand
2434030584	[J
1836875424	java.lang.String
914400568	java.lang.Long
894648200	[Ljava.lang.Object;
870190184	[I
672191640	com.surprising.aeron.service.execution.CommandResultLedger$StoredResult
579086296	com.surprising.aeron.protocol.CoreResponse
573261840	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001291ee0c0
374332768	com.surprising.aeron.protocol.CoreMessageHeader
360294800	com.surprising.aeron.protocol.CoreOrderStateView
357507544	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001291edc60
326975488	java.util.LinkedHashMap$Entry
314363544	java.util.HashMap$Node
304054464	org.eclipse.collections.impl.set.mutable.primitive.IntHashSet
301657424	com.surprising.aeron.service.execution.ImmutableLongArrayList
301635176	com.surprising.aeron.protocol.CancelOrderCommand
295811256	com.surprising.aeron.service.state.ResolvedPlaceOrder
290815496	java.nio.HeapByteBuffer
277012400	java.util.ArrayList$Itr
270735640	com.surprising.aeron.service.execution.ContinuousTradingClusterService$Output
224610296	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x00000001291ede90
202592816	com.surprising.aeron.service.state.CoreOrderDecisionResolver$Context
193108840	java.util.HashMap$KeyIterator
ownerAllocationPaths
9046936912	TradingCoreRuntime.prepareClusterPipelineScope
8592308768	TradingOrderBatchCodec.decodeCommand
1963010288	ActiveOrderIndex.applySnapshot
614042536	TradingRuntimeState.collectMatcherSettlement
334429904	OrderBatchExecutor.preparePipelinedPlaceBatch
225800472	TradingRuntimeState.collectCancel
190768840	MatcherSettlementPlan.build
laneLeaf
365257	com.surprising.aeron.service.state.SettlementLaneWorker.run:154
6356	java.lang.ThreadLocal.get:171
5060	com.surprising.aeron.service.state.SettlementLaneWorker.run:136
4621	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
2905	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probeThree:3090
2344	java.lang.ThreadLocal.get:184
2167	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
1980	java.util.concurrent.ConcurrentHashMap.get:949
1200	java.util.ImmutableCollections$ListItr.next:397
1166	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probe:3057
1064	com.surprising.aeron.service.state.OrderRuntime.<init>:47
917	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.containsKey:978
ownerParkMonitorNs
afterGCminute=0 count=110 minMiB=103.337 averageMiB=106.876 maxMiB=109.828
afterGCminute=1 count=102 minMiB=105.623 averageMiB=108.282 maxMiB=111.409
afterGCminute=2 count=108 minMiB=107.063 averageMiB=109.746 maxMiB=113.299
afterGCminute=3 count=115 minMiB=108.797 averageMiB=111.404 maxMiB=114.211
afterGCminute=4 count=116 minMiB=110.226 averageMiB=113.327 maxMiB=116.489

```

gates-final.log
```text
SPOT execute productLineGate=PASS mode=execute productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT replay productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT snapshotPosition=3616
SPOT snapshot productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL execute productLineGate=PASS mode=execute productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL replay productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL snapshotPosition=9568
LINEAR_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL execute productLineGate=PASS mode=execute productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL replay productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL snapshotPosition=9568
INVERSE_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY execute productLineGate=PASS mode=execute productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY replay productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY snapshotPosition=5184
LINEAR_DELIVERY snapshot productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY execute productLineGate=PASS mode=execute productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY replay productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY snapshotPosition=5184
INVERSE_DELIVERY snapshot productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
OPTION execute productLineGate=PASS mode=execute productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION replay productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION snapshotPosition=5184
OPTION snapshot productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701

```

recovery-final.log
```text
master replay PASS businessHash=a9d128a7f70e70cc
master snapshotPosition=5296936992
master snapshot PASS businessHash=a9d128a7f70e70cc

```

master-control-INVERSE_DELIVERY-0
```text
Iteration   2: accountControlVerify=PASS product=INVERSE_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=75–77
nmt-before.txt
Total: reserved=3146450KB, committed=692114KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=58505KB, committed=1933KB)
-                      Code (reserved=250325KB, committed=15993KB)
-                        GC (reserved=91630KB, committed=71190KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138172KB, committed=696720KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2607KB)
-                      Code (reserved=252417KB, committed=22793KB)
-                        GC (reserved=91808KB, committed=71328KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2271614 sha256=12ec7e3a38209489e19c34423c77e15d80348cbb259290affeffd3dce0ce521f
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"INVERSE_DELIVERY"},"primaryMetric":{"score":3075.9786885,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3025.743266,"50.0":3075.9786885,"90.0":3126.214111,"95.0":3126.214111,"99.0":3126.214111,"99.9":3126.214111,"99.99":3126.214111,"99.999":3126.214111,"99.9999":3126.214111,"100.0":3126.214111},"scoreUnit":"ms/op","rawData":[[3126.214111,3025.743266]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.1416583051536224,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.9344738059489444,"50.0":1.1416583051536224,"90.0":1.3488428043583005,"95.0":1.3488428043583005,"99.0":1.3488428043583005,"99.9":1.3488428043583005,"99.99":1.3488428043583005,"99.999":1.3488428043583005,"99.9999":1.3488428043583005,"100.0":1.3488428043583005},"scoreUnit":"MB/sec","rawData":[[0.9344738059489444,1.3488428043583005]]},"gc.alloc.rate.norm":{"score":3715828.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3063568.0,"50.0":3715828.0,"90.0":4368088.0,"95.0":4368088.0,"99.0":4368088.0,"99.9":4368088.0,"99.99":4368088.0,"99.999":4368088.0,"99.9999":4368088.0,"100.0":4368088.0},"scoreUnit":"B/op","rawData":[[3063568.0,4368088.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=INVERSE_DELIVERY","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-control-INVERSE_DELIVERY-0/jmh.json"]]
```

master-control-INVERSE_PERPETUAL-0
```text
Iteration   2: accountControlVerify=PASS product=INVERSE_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=79–79
nmt-before.txt
Total: reserved=3140341KB, committed=692065KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=52354KB, committed=1842KB)
-                      Code (reserved=250320KB, committed=15988KB)
-                        GC (reserved=91630KB, committed=71190KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3142355KB, committed=696827KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=56472KB, committed=2568KB)
-                      Code (reserved=252478KB, committed=22854KB)
-                        GC (reserved=91797KB, committed=71317KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2294208 sha256=67af0d171fdab2bf7c45a2439cef69aef338c47b8e911c29a3586b85e3394017
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"INVERSE_PERPETUAL"},"primaryMetric":{"score":3077.6745469999996,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3037.252893,"50.0":3077.6745469999996,"90.0":3118.096201,"95.0":3118.096201,"99.0":3118.096201,"99.9":3118.096201,"99.99":3118.096201,"99.999":3118.096201,"99.9999":3118.096201,"100.0":3118.096201},"scoreUnit":"ms/op","rawData":[[3118.096201,3037.252893]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.197172934866551,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.0237529944390948,"50.0":1.197172934866551,"90.0":1.370592875294007,"95.0":1.370592875294007,"99.0":1.370592875294007,"99.9":1.370592875294007,"99.99":1.370592875294007,"99.999":1.370592875294007,"99.9999":1.370592875294007,"100.0":1.370592875294007},"scoreUnit":"MB/sec","rawData":[[1.0237529944390948,1.370592875294007]]},"gc.alloc.rate.norm":{"score":3917328.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3347528.0,"50.0":3917328.0,"90.0":4487128.0,"95.0":4487128.0,"99.0":4487128.0,"99.9":4487128.0,"99.99":4487128.0,"99.999":4487128.0,"99.9999":4487128.0,"100.0":4487128.0},"scoreUnit":"B/op","rawData":[[3347528.0,4487128.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=INVERSE_PERPETUAL","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-control-INVERSE_PERPETUAL-0/jmh.json"]]
```

master-control-LINEAR_DELIVERY-0
```text
Iteration   2: accountControlVerify=PASS product=LINEAR_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=77–79
nmt-before.txt
Total: reserved=3146555KB, committed=692255KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=58505KB, committed=1969KB)
-                      Code (reserved=250366KB, committed=16034KB)
-                        GC (reserved=91617KB, committed=71177KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138298KB, committed=696910KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2543KB)
-                      Code (reserved=252497KB, committed=22937KB)
-                        GC (reserved=91806KB, committed=71326KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2284200 sha256=5a3943703bc9e729159f41fadcb40b6114e2381e115120b43d564266b156af9a
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"LINEAR_DELIVERY"},"primaryMetric":{"score":3109.175388,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3097.032877,"50.0":3109.175388,"90.0":3121.317899,"95.0":3121.317899,"99.0":3121.317899,"99.9":3121.317899,"99.99":3121.317899,"99.999":3121.317899,"99.9999":3121.317899,"100.0":3121.317899},"scoreUnit":"ms/op","rawData":[[3121.317899,3097.032877]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.1831279021232741,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.0355895756305329,"50.0":1.1831279021232741,"90.0":1.3306662286160154,"95.0":1.3306662286160154,"99.0":1.3306662286160154,"99.9":1.3306662286160154,"99.99":1.3306662286160154,"99.999":1.3306662286160154,"99.9999":1.3306662286160154,"100.0":1.3306662286160154},"scoreUnit":"MB/sec","rawData":[[1.0355895756305329,1.3306662286160154]]},"gc.alloc.rate.norm":{"score":3914860.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3389728.0,"50.0":3914860.0,"90.0":4439992.0,"95.0":4439992.0,"99.0":4439992.0,"99.9":4439992.0,"99.99":4439992.0,"99.999":4439992.0,"99.9999":4439992.0,"100.0":4439992.0},"scoreUnit":"B/op","rawData":[[3389728.0,4439992.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=LINEAR_DELIVERY","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-control-LINEAR_DELIVERY-0/jmh.json"]]
```

master-control-LINEAR_PERPETUAL-0
```text
Iteration   2: accountControlVerify=PASS product=LINEAR_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=79–79
nmt-before.txt
Total: reserved=3142348KB, committed=692052KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1872KB)
-                      Code (reserved=250346KB, committed=16014KB)
-                        GC (reserved=91627KB, committed=71187KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138138KB, committed=696786KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2579KB)
-                      Code (reserved=252419KB, committed=22859KB)
-                        GC (reserved=91812KB, committed=71332KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2284735 sha256=cd69e0bb699473add93f28021de2e6043cc4dea57dd92912a128706aaedec7eb
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"LINEAR_PERPETUAL"},"primaryMetric":{"score":3056.6253125,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3050.992011,"50.0":3056.6253125,"90.0":3062.258614,"95.0":3062.258614,"99.0":3062.258614,"99.9":3062.258614,"99.99":3062.258614,"99.999":3062.258614,"99.9999":3062.258614,"100.0":3062.258614},"scoreUnit":"ms/op","rawData":[[3062.258614,3050.992011]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.1939364434994937,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.044227531607748,"50.0":1.1939364434994937,"90.0":1.3436453553912393,"95.0":1.3436453553912393,"99.0":1.3436453553912393,"99.9":1.3436453553912393,"99.99":1.3436453553912393,"99.999":1.3436453553912393,"99.9999":1.3436453553912393,"100.0":1.3436453553912393},"scoreUnit":"MB/sec","rawData":[[1.044227531607748,1.3436453553912393]]},"gc.alloc.rate.norm":{"score":3878372.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3353392.0,"50.0":3878372.0,"90.0":4403352.0,"95.0":4403352.0,"99.0":4403352.0,"99.9":4403352.0,"99.99":4403352.0,"99.999":4403352.0,"99.9999":4403352.0,"100.0":4403352.0},"scoreUnit":"B/op","rawData":[[3353392.0,4403352.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=LINEAR_PERPETUAL","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-control-LINEAR_PERPETUAL-0/jmh.json"]]
```

master-control-OPTION-0
```text
Iteration   2: accountControlVerify=PASS product=OPTION terminalBusinessOperations=1800 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=75–75
nmt-before.txt
Total: reserved=3142347KB, committed=692051KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1872KB)
-                      Code (reserved=250306KB, committed=15974KB)
-                        GC (reserved=91619KB, committed=71179KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3140065KB, committed=696329KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=54422KB, committed=2566KB)
-                      Code (reserved=252339KB, committed=22523KB)
-                        GC (reserved=91781KB, committed=71301KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2058352 sha256=4cb13efce401152a0cd09febbac239055daa65cce583d386b5a6a82972320a22
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"OPTION"},"primaryMetric":{"score":2317.268875,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2275.954712,"50.0":2317.268875,"90.0":2358.583038,"95.0":2358.583038,"99.0":2358.583038,"99.9":2358.583038,"99.99":2358.583038,"99.999":2358.583038,"99.9999":2358.583038,"100.0":2358.583038},"scoreUnit":"ms/op","rawData":[[2358.583038,2275.954712]]},"secondaryMetrics":{"gc.alloc.rate":{"score":0.7542551484411626,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.5438736317741244,"50.0":0.7542551484411626,"90.0":0.9646366651082007,"95.0":0.9646366651082007,"99.0":0.9646366651082007,"99.9":0.9646366651082007,"99.99":0.9646366651082007,"99.999":0.9646366651082007,"99.9999":0.9646366651082007,"100.0":0.9646366651082007},"scoreUnit":"MB/sec","rawData":[[0.5438736317741244,0.9646366651082007]]},"gc.alloc.rate.norm":{"score":1866416.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1345224.0,"50.0":1866416.0,"90.0":2387608.0,"95.0":2387608.0,"99.0":2387608.0,"99.9":2387608.0,"99.99":2387608.0,"99.999":2387608.0,"99.9999":2387608.0,"100.0":2387608.0},"scoreUnit":"B/op","rawData":[[1345224.0,2387608.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-OPTION-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=OPTION","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-control-OPTION-0/jmh.json"]]
```

master-control-SPOT-0
```text
Iteration   2: accountControlVerify=PASS product=SPOT terminalBusinessOperations=600 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=79–79
nmt-before.txt
Total: reserved=3148641KB, committed=692325KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=60555KB, committed=2003KB)
-                      Code (reserved=250375KB, committed=16043KB)
-                        GC (reserved=91628KB, committed=71188KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3137266KB, committed=693294KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2467KB)
-                      Code (reserved=251963KB, committed=20983KB)
-                        GC (reserved=91783KB, committed=71303KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=1619994 sha256=8c0caf04ea323ae5fd72ab89b874d6f418a85a038d39cff5e784c50503c51c20
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"SPOT"},"primaryMetric":{"score":852.1031035000001,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":823.64787,"50.0":852.1031035000001,"90.0":880.558337,"95.0":880.558337,"99.0":880.558337,"99.9":880.558337,"99.99":880.558337,"99.999":880.558337,"99.9999":880.558337,"100.0":880.558337},"scoreUnit":"ms/op","rawData":[[880.558337,823.64787]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.4492007501927526,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.9302775605276508,"50.0":1.4492007501927526,"90.0":1.9681239398578545,"95.0":1.9681239398578545,"99.0":1.9681239398578545,"99.9":1.9681239398578545,"99.99":1.9681239398578545,"99.999":1.9681239398578545,"99.9999":1.9681239398578545,"100.0":1.9681239398578545},"scoreUnit":"MB/sec","rawData":[[0.9302775605276508,1.9681239398578545]]},"gc.alloc.rate.norm":{"score":1366076.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":859192.0,"50.0":1366076.0,"90.0":1872960.0,"95.0":1872960.0,"99.0":1872960.0,"99.9":1872960.0,"99.99":1872960.0,"99.999":1872960.0,"99.9999":1872960.0,"100.0":1872960.0},"scoreUnit":"B/op","rawData":[[859192.0,1872960.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-control-SPOT-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=SPOT","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-control-SPOT-0/jmh.json"]]
```

master-main-LINEAR_PERPETUAL-0
```text
measurementStartEpochMillis=1789051580364
measurementEndEpochMillis=1789051640460
ownerChurnVerify=PASS completedOrderLifecycles=8340480 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=758 businessHash=a5ae8f998c9925d7
mixedCapacity=PASS elapsedSeconds=60.095 terminalBusinessOperations=11691779 offeredBusinessOperations=11691779 terminalCoreMessages=1127171 offeredCoreMessages=1127171 businessOpsPerSec=194554.083 coreMessagesPerSec=18756.403 fills=2780160 fillsPerSec=46262.547 queries=0 unfinished=0 peakInFlight=256 measuredCycles=543 totalCycles=758 triggerExecutions=0
business=PLACE_ORDER items=278016 requests=278016 p50us=7622 p90us=18710 p95us=21004 p99us=27197 p999us=49053 maxus=59015
business=CANCEL_ORDER items=278016 requests=278016 p50us=6836 p90us=15785 p95us=17858 p99us=23789 p999us=43319 maxus=150601
business=APPLY_MARK_PRICE items=15107 requests=15107 p50us=9936 p90us=21086 p95us=26198 p99us=35225 p999us=55443 maxus=174194
business=PLACE_ORDER_BATCH items=8340480 requests=417024 p50us=16695 p90us=21495 p95us=23969 p99us=39682 p999us=156631 maxus=334757
business=CANCEL_ORDER_BATCH items=2780160 requests=139008 p50us=18235 p90us=22970 p95us=25624 p99us=40370 p999us=79233 maxus=348651
CPU_Speed_Limit=64–100
nmt-before.txt
Total: reserved=3118384KB, committed=666568KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=49273KB, committed=1709KB)
-                      Code (reserved=249296KB, committed=11740KB)
-                        GC (reserved=91615KB, committed=71175KB)
-                     Other (reserved=9385KB, committed=9385KB)
nmt-after.txt
Total: reserved=3128182KB, committed=703874KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=49290KB, committed=2154KB)
-                      Code (reserved=261910KB, committed=45962KB)
-                        GC (reserved=92357KB, committed=71885KB)
-                     Other (reserved=9387KB, committed=9387KB)
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-main-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-main-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-main-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-main-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-main-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=60","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain"]]
```

master-profile-LINEAR_PERPETUAL-0
```text
measurementStartEpochMillis=1789051711637
measurementEndEpochMillis=1789052011660
ownerChurnVerify=PASS completedOrderLifecycles=36710400 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=2546 businessHash=a9d128a7f70e70cc
mixedCapacity=PASS elapsedSeconds=300.021 terminalBusinessOperations=51470365 offeredBusinessOperations=51470365 terminalCoreMessages=4970525 offeredCoreMessages=4970525 businessOpsPerSec=171555.851 coreMessagesPerSec=16567.255 fills=12236800 fillsPerSec=40786.473 queries=0 unfinished=0 peakInFlight=256 measuredCycles=2390 totalCycles=2546 triggerExecutions=0
business=PLACE_ORDER items=1223680 requests=1223680 p50us=8347 p90us=21741 p95us=23822 p99us=27770 p999us=52690 maxus=69468
business=CANCEL_ORDER items=1223680 requests=1223680 p50us=7991 p90us=17743 p95us=19578 p99us=24117 p999us=45744 maxus=109772
business=APPLY_MARK_PRICE items=75805 requests=75805 p50us=13000 p90us=21463 p95us=24035 p99us=40534 p999us=72548 maxus=78053
business=PLACE_ORDER_BATCH items=36710400 requests=1835520 p50us=19382 p90us=23216 p95us=25985 p99us=38371 p999us=60686 maxus=113049
business=CANCEL_ORDER_BATCH items=12236800 requests=611840 p50us=21495 p90us=25411 p95us=26984 p99us=45776 p999us=61571 maxus=77987
CPU_Speed_Limit=64–79
nmt-before.txt
Total: reserved=3144354KB, committed=691986KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=56519KB, committed=1979KB)
-                      Code (reserved=250302KB, committed=15970KB)
-                        GC (reserved=91619KB, committed=71179KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3155103KB, committed=734331KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2099KB)
-                      Code (reserved=261458KB, committed=49118KB)
-                        GC (reserved=92522KB, committed=72042KB)
-                     Other (reserved=9413KB, committed=9413KB)
JFR bytes=40910934 sha256=8c0ebe69069ba4ce448a9ea6d84e379db698d146b2834cfc58b995d3bbb551b3
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":0,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":1,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"controlPageSize":"0"},"primaryMetric":{"score":300.021634487,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":300.021634487,"50.0":300.021634487,"90.0":300.021634487,"95.0":300.021634487,"99.0":300.021634487,"99.9":300.021634487,"99.99":300.021634487,"99.999":300.021634487,"99.9999":300.021634487,"100.0":300.021634487},"scoreUnit":"s/op","rawData":[[300.021634487]]},"secondaryMetrics":{"gc.alloc.rate":{"score":174.89453707152256,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":174.89453707152256,"50.0":174.89453707152256,"90.0":174.89453707152256,"95.0":174.89453707152256,"99.0":174.89453707152256,"99.9":174.89453707152256,"99.99":174.89453707152256,"99.999":174.89453707152256,"99.9999":174.89453707152256,"100.0":174.89453707152256},"scoreUnit":"MB/sec","rawData":[[174.89453707152256]]},"gc.alloc.rate.norm":{"score":67460267144.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":67460267144.0,"50.0":67460267144.0,"90.0":67460267144.0,"95.0":67460267144.0,"99.0":67460267144.0,"99.9":67460267144.0,"99.99":67460267144.0,"99.999":67460267144.0,"99.9999":67460267144.0,"100.0":67460267144.0},"scoreUnit":"B/op","rawData":[[67460267144.0]]},"gc.count":{"score":865.0,"scoreError":"NaN","scoreConfidence":[865.0,865.0],"scorePercentiles":{"0.0":865.0,"50.0":865.0,"90.0":865.0,"95.0":865.0,"99.0":865.0,"99.9":865.0,"99.99":865.0,"99.999":865.0,"99.9999":865.0,"100.0":865.0},"scoreUnit":"counts","rawData":[[865.0]]},"gc.time":{"score":725.0,"scoreError":"NaN","scoreConfidence":[725.0,725.0],"scorePercentiles":{"0.0":725.0,"50.0":725.0,"90.0":725.0,"95.0":725.0,"99.0":725.0,"99.9":725.0,"99.99":725.0,"99.999":725.0,"99.9999":725.0,"100.0":725.0},"scoreUnit":"ms","rawData":[[725.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=300","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterOperationalBenchmark.continuousOperations","-f","1","-wi","0","-i","1","-p","controlPageSize=0","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-final/master-profile-LINEAR_PERPETUAL-0/jmh.json"]]
```

服务JAR sha256=c31981b0dbbcad3bb886dddf95ea91ff0ea81b189c8af6f3370a84747f900df6

显式GC后类直方图
```text
97619:
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         39108       16873328  [J (java.base@25.0.1)
   2:        153379       12986360  [B (java.base@25.0.1)
   3:         18343       12250992  [Ljava.lang.Object; (java.base@25.0.1)
   4:         20055        9644664  [I (java.base@25.0.1)
   5:        132087        3170088  java.lang.String (java.base@25.0.1)
   6:           385        2775944  [Ljdk.internal.vm.FillerElement; (java.base@25.0.1)
   7:         30229        2418320  com.surprising.aeron.protocol.PlaceOrderCommand
   8:         40318        1290176  java.util.concurrent.ConcurrentHashMap$Node (java.base@25.0.1)
   9:         10115         890120  exchange.core2.core.orderbook.OrderBookDirectImpl$DirectOrder
  10:          4995         879120  com.surprising.aeron.service.state.OrderRuntime
  11:          4995         879120  com.surprising.aeron.service.state.model.CoreOrderState
  12:            95         673520  [Ljava.util.concurrent.ConcurrentHashMap$Node; (java.base@25.0.1)
  13:          5092         660528  java.lang.Class (java.base@25.0.1)
  14:          4096         622592  com.surprising.aeron.service.execution.PendingMatching
  15:         15528         621120  com.surprising.aeron.service.state.LanePublishedMap$Version
  16:          1136         575080  [Ljava.lang.String; (java.base@25.0.1)
  17:         14017         448544  com.surprising.aeron.service.state.LanePublication
  18:         18470         443280  java.lang.Long (java.base@25.0.1)
  19:          4995         399600  com.surprising.aeron.service.state.ReservationRuntime
  20:          9563         382520  org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
  21:         15533         372792  com.surprising.aeron.service.state.LanePublishedMap$Key
  22:          4995         359640  com.surprising.aeron.service.state.index.OrderParticipantIndex$Node
  23:         10860         347520  java.util.UUID (java.base@25.0.1)
  24:          3769         331672  com.surprising.aeron.service.state.PositionRuntime
  25:          4096         327680  com.surprising.aeron.protocol.CoreMessageHeader
  26:          4096         327680  com.surprising.aeron.service.execution.LaneCommandContextRing$Context
  27:          8192         327680  com.surprising.aeron.service.execution.PendingClusterIngress$Entry
  28:         11297         271128  com.surprising.aeron.protocol.CancelOrderCommand
  29:          8391         268512  java.util.HashMap$Node (java.base@25.0.1)
  30:          8192         262144  com.surprising.aeron.service.execution.MatcherCommandPipeline$Slot
  31:          2365         245960  com.surprising.aeron.service.state.AccountLaneState$AdmissionAggregate
  32:          2194         241512  [Ljava.util.HashMap$Node; (java.base@25.0.1)
  33:          3257         234504  com.surprising.aeron.service.state.RiskSnapshotRuntime
  34:          2667         213560  [Z (java.base@25.0.1)
  35:          2349         206712  com.surprising.aeron.service.state.MatcherSettlementPlan
  36:          4737         189480  com.surprising.aeron.service.state.RuntimeFundsAccumulator
  37:          4476         179040  java.util.LinkedHashMap$Entry (java.base@25.0.1)
```

987ff387轮进程均结束，已清理perf-final/gates-final原始产物13850504700字节；原始路径不可访问。小修使用独立perf-upper/gates-upper目录。

### 2a60fed1 最后小修采集前锁定

- 定向回归（TerminalTombstoneStoreTest、TerminalStateRetentionTest、六产品ClusterCommandPipelineTest、TradingStateSnapshotCodecTest、OwnerIndexedChangesTest）及reactor package通过；结合上一轮完整verify，当前报告汇总[1225, 0, 0, 1]。没有因小修重复无关集成模块。
- 当前master代码2a60fed1，其余参数/正确性门槛同987ff387锁定；gates-upper执行六产品真实单成员恢复；perf-upper无profiler主测60s、预热30s+90s JMH/JFR，6产品控制，recovery-upper执行本轮日志与snapshot恢复。性能数据仅当前master，不重跑旧版。
- 上界优化只对超过已知上界的ID省去索引查询；压测订单ID递增，乱序正确性由新增测试验证，不将此快路收益外推到任意ID分布。固定4个long不改变引用或池生命周期；5min内存证据仍注明上一提交，最终小修90s不作长期无泄漏验收。

### 最终交付：2a60fed1

- 最后小修在当前master完成定向回归及package；结合987ff387完整verify，报告累计1225项、1224通过、0失败/错误、1数据库条件跳过；不是冒称最后小修重跑全套。六产品真实单成员execute/强杀replay/snapshot与六产品accountControls JMH/JFR全部PASS。最终90s录制重放及snapshot重启businessHash 302d26290618b164，snapshotPosition1973235296。
- 最终无profiler：60.056s，12100352终态业务操作、1166080Core消息、2877440fills；201483.078 business ops/s、19416.409 Core messages/s、47912.281 fills/s。最终JMH/JFR：90.133s，16000263业务操作、1544455Core消息、3804160fills；177517.466 business ops/s、17135.202 Core messages/s、42205.859 fills/s。两者分开报告，均offered=terminal、unfinished0、峰值in-flight256、资金/持仓/冻结/终态PASS。非全生产控制混合流，非三节点容量；持续查询计数0，不能当查询并发验收。
- Owner29611样本，终态管理1828=6.174%，相较中间两版10.24%/8.306%下降，但仍高于最初定位4.57%；不同JIT/时长/限速条件，仅归因参考，不作严格加速比。最终readyLaneMask1185=4.002%，未证实总通知轮询占比下降；批量入口统一推进与轮内避免重复收集并不意味着所有轮询可删除。
- 最终路由196=0.662%（最初2.68%）、批量准入879=2.969%；decodeCommand942=3.181%，整体解码CPU下降不大。客户身份release1029=3.475%，CPU占比没有明显改善，但分配抽样已移除。结算收集3255=10.992%、撤单收集1389=4.691%、ActiveOrderIndex1279=4.319%，仍是Owner串行工作；这些inclusive不能随意相加。
- OrderBatchItem分配抽样0；指定窗口/终态索引重建及client-key释放路径分配采样0，不是宣称所有对象零分配。节点50686695656B/16000263≈3167.9B/business op（最初约3429.5B/op），约7.6%较低，为JFR抽样估计。Owner97.042%单核，matcher24.493/24.464%，Lane约97.2%但大量自旋，未实现所有线程有效饱和，未达到普通单全链路1ms目标。
- JFR DataLoss0、Owner无同步IO或>=1ms park/monitor事件；短等待/缓存争用和真实系统调度不能据此全部排除。最后90s GC后分钟均值104.900/106.328MiB；测量外显式GC后used84315KiB≈82.34MiB。前一提交同池/索引生命周期的5min趋势及显式GC已记录；最终小修固定4long不改变引用生命周期，但不能将前一提交的5min冒称当前提交长测，更不能声称长期heap/native无泄漏。
- 修复范围：索引churn、逐项包装、重复路由/装箱、无需处理的存活持仓重复查表，以及入口逐条重复推进。保留确定性日志提交、资金/订单依赖、结果可见性和fail-closed，不为CPU占用删除正确性检查。部分热点仍在，不能宣称全部性能问题已根除。

upper-summary.txt
```text
totals allocationMiBps=536.303 allocationBytes=50686695656 machineCPU=86.54 jvmCPU=58.07 heapMaxMiB=393.31 dataLoss=0 ownerIOEvents=0
threadCPU	97.219	core-account-lane-3
threadCPU	97.187	core-account-lane-2
threadCPU	97.184	core-account-lane-0
threadCPU	97.174	core-account-lane-1
threadCPU	97.042	trading-owner--1
threadCPU	96.366	/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	74.115	clustered-service-101-0
threadCPU	72.433	driver-conductor
threadCPU	65.031	archive-conductor
threadCPU	63.280	consensus-module-101-0
threadCPU	24.493	core-matcher-0
threadCPU	24.464	core-matcher-1
threadCPU	3.855	JVMCI-native CompilerThread0
threadCPU	1.576	aeron-md-nra
threadCPU	0.331	C1 CompilerThread0
threadCPU	0.320	JFR Periodic Tasks
threadCPU	0.219	JFR Recorder Thread
threadCPU	0.212	aeron-client
threadCPU	0.102	Monitor Deflation Thread
threadCPU	0.100	Service Thread
gc count=170 totalMs=1026.864 p99Ms=9.684 maxMs=10.835
samples	33988	core-account-lane-0
samples	33586	core-account-lane-1
samples	32998	core-account-lane-2
samples	32233	core-account-lane-3
samples	29611	trading-owner--1
samples	3212	driver-conductor
samples	1673	clustered-service-101-0
samples	1523	consensus-module-101-0
samples	1425	archive-conductor
samples	488	/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	347	core-matcher-1
samples	339	core-matcher-0
ownerInclusive	29611	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012f12f708.run
ownerInclusive	29611	java.lang.Thread.run
ownerInclusive	29611	java.lang.Thread.runWith
ownerInclusive	29611	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	29354	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
ownerInclusive	29123	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	28827	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	20722	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	19662	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	12494	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	8667	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
ownerInclusive	6621	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
allocation	2916014120	trading-owner--1 [B
allocation	1521886920	core-account-lane-1 [J
allocation	1410427968	clustered-service-101-0 [B
allocation	1388359872	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	1360891048	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	1346296104	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	1341257200	core-account-lane-0 [J
allocation	1333447968	core-account-lane-2 [J
allocation	1329644936	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	1267267432	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	1222980608	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	1218190176	core-account-lane-3 [J
allocationSite	5871937496	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	2711756848	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	2489123568	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	2480362096	com.surprising.aeron.protocol.TradingCommandCodec.decodePlaceOrder
allocationSite	2177875456	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	2028877760	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	1760783128	java.nio.ByteBuffer.allocate
allocationSite	1390583400	java.lang.invoke.VarHandleLongs$Array.setRelease
allocationSite	1372445792	exchange.core2.core.common.cmd.OrderCommand.processMatcherEvents
allocationSite	1348193808	java.util.List.copyOf
allocationSite	1327342280	com.surprising.aeron.service.execution.CoreMessageFlyweightDecoder.decode
allocationSite	1203897296	com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order
parkOrMonitorNs	89449226073	aeron-md-nra
parkOrMonitorNs	60104816383	Common-Cleaner
parkOrMonitorNs	1458681746	core-matcher-1
parkOrMonitorNs	1426214263	core-matcher-0
parkOrMonitorNs	1090081861	archive-conductor
parkOrMonitorNs	760265295	consensus-module-101-0
parkOrMonitorNs	364564600	driver-conductor
parkOrMonitorNs	173502646	/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
eventCounts	171429	jdk.ExecutionSample
eventCounts	96366	jdk.GCPhaseParallel
eventCounts	83586	jdk.ThreadPark
eventCounts	25984	jdk.ObjectAllocationSample
eventCounts	14300	jdk.PromoteObjectInNewPLAB
eventCounts	5432	jdk.ThreadSleep
eventCounts	4298	jdk.NativeMethodSample
eventCounts	2550	jdk.TenuringDistribution
eventCounts	2430	jdk.NativeMemoryUsage
eventCounts	1394	jdk.ThreadCPULoad
eventCounts	1044	jdk.NativeLibrary
eventCounts	857	jdk.ModuleExport

```

upper-owner.txt
```text
ownerSamples=29611
focusedSamples
3255	TradingRuntimeState.collectMatcherSettlement
2069	TradingCoreRuntime.prepareClusterPipelineScope
1828	TerminalStateRetention.
1512	TerminalTombstoneStore.
1389	TradingRuntimeState.collectCancel
1279	ActiveOrderIndex.applySnapshot
1185	TradingRuntimeState.readyLaneMask
1161	MatcherSettlementPlan.build
1029	RuntimeIdentityRegistry.releaseClientKey
942	TradingOrderBatchCodec.decodeCommand
879	OrderBatchExecutor.preparePipelinedPlaceBatch
791	TradingRuntimeState.clearChangedKeys
704	TerminalTombstoneStore.put
356	ClusterCommandWindow.add
196	DeterministicExchangeCoreAdapter.matcherShardId
159	OrderBatchExecutor.decodeOrderBatch
exactOrderBatchItemAllocation=0
ownerInclusive
29611	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012f12f708.run
29611	java.lang.Thread.run
29611	java.lang.Thread.runWith
29611	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
29354	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
29123	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
28827	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
20722	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
19656	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
12494	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
8667	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
6621	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
3904	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchReadyPlaceSettlements
3679	com.surprising.aeron.service.execution.TradingCoreRuntime.applyDecodedCommand
3630	com.surprising.aeron.service.execution.TradingCoreRuntime.apply
3627	com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchPartitionSettlements
3255	com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
2811	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed
2656	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeCommitPublicationBatch
2654	com.surprising.aeron.service.execution.OrderedCommitCoordinator.publishCommittedChanges
2650	com.surprising.aeron.service.execution.OrderedCommitCoordinator$OwnerCommitPublisher.execute
2353	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.commitTerminalToOwner
2172	com.surprising.aeron.service.execution.TradingCoreRuntime.drainMatchingCompletions
2146	com.surprising.aeron.service.execution.OrderBatchExecutor.beginOrderBatchMatching
2069	com.surprising.aeron.service.execution.TradingCoreRuntime.prepareClusterPipelineScope
1924	com.surprising.aeron.service.execution.OrderBatchExecutor.dispatchOrderBatchLaneWork
1870	com.surprising.aeron.service.state.TradingRuntimeState.dispatchMatcherSettlementBatch
1866	com.surprising.aeron.service.state.MatcherSettlementDispatcher.dispatchMatcherSettlementBatch
1664	com.surprising.aeron.service.state.RuntimeFactIndexes.applyCurrent
1651	com.surprising.aeron.service.state.TradingRuntimeState.visitChangedIndexes
1561	com.surprising.aeron.service.state.OwnerIndexedChanges.forEachIndexed
1540	com.surprising.aeron.service.execution.TradingCoreRuntime.progressPlaceAdmissions
1389	com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
1347	com.surprising.aeron.service.execution.OrderBatchExecutor.tryActivatePipelinedOrderBatch
1303	com.surprising.aeron.service.state.TradingRuntimeState.lambda$visitChangedIndexes$0
1303	com.surprising.aeron.service.state.TradingRuntimeState$$Lambda.0x000000012f194000.accept
1303	com.surprising.aeron.service.state.RuntimeFactIndexes.preparedOrder
1279	com.surprising.aeron.service.state.index.ActiveOrderIndex.applySnapshot
1205	com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
1203	com.surprising.aeron.service.execution.MatchingCommandAdmission.beginMatching
1196	com.surprising.aeron.service.execution.MatchingCommandAdmission.prepareMatching
1193	java.util.HashMap.getNode
1185	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
1157	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeDispatchedMatcherSettlement
1148	com.surprising.aeron.service.execution.TradingCoreRuntime.drainLaneReadyNotifications
1144	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeDispatchedCancel
1094	com.surprising.aeron.service.state.MatcherSettlementPlan.build
1089	com.surprising.aeron.service.execution.ClusterCommandWindow.decoded
1084	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges$ClientIdentityReleaseBuffer.release
1084	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.releaseRetiredClientIdentities
1060	com.surprising.aeron.service.execution.DecodedMatchingCommand.decode
1057	com.surprising.aeron.service.state.MatcherSettlementPlan.buildBatchItem
1045	java.util.concurrent.ConcurrentHashMap.get
1029	com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey
1015	java.util.HashMap.get
1005	com.surprising.aeron.service.state.TradingRuntimeState.takePlaceAdmissionReadyLaneMask
942	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
921	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodePlaceOrderBatch
896	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges$$Lambda.0x000000012f1bc000.accept
896	com.surprising.aeron.service.state.TradingRuntimeState$PublishedLaneChanges.lambda$commitTerminalToOwner$0
896	com.surprising.aeron.service.execution.TerminalStateRetention.accept
879	com.surprising.aeron.service.execution.OrderBatchExecutor.preparePipelinedPlaceBatch
871	com.surprising.aeron.service.execution.TerminalStateRetention.retainPrunedOrder
863	java.util.Arrays.fill
831	com.surprising.aeron.protocol.TradingOrderBatchCodec$$Lambda.0x000000012f1e4218.decode
ownerLeaf
1127	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:226
743	java.util.concurrent.ConcurrentHashMap.get:949
731	com.surprising.aeron.protocol.TradingCommandCodec.decodePlaceOrder:251
727	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1773
499	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.each:571
488	com.surprising.aeron.service.execution.TerminalTombstoneStore.indexEntity:77
465	java.util.HashMap.getNode:577
410	jdk.internal.misc.Unsafe.putIntUnaligned:3715
396	org.agrona.collections.Long2LongHashMap.get:208
340	java.nio.HeapByteBuffer.put:221
297	com.surprising.aeron.service.state.LaneSequenceQueue.hasPending:45
283	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed:56
280	com.surprising.aeron.protocol.CoreStateQueryCodec.utf8Length:342
253	com.surprising.aeron.service.execution.TerminalTombstoneStore.unlinkEntity:86
250	com.surprising.aeron.service.state.RuntimeIdentityRegistry.releaseClientKey:256
233	java.util.HashMap.hash:338
226	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1036
221	java.util.Arrays.fill:3451
215	java.util.concurrent.ConcurrentHashMap.get:957
215	org.agrona.collections.Long2LongHashMap.compactChain:878
215	java.util.Arrays.fill:3143
212	com.surprising.aeron.service.execution.TradingCoreRuntime.bindOwner:2478
209	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
204	java.lang.ThreadLocal.get:171
204	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.probe:1043
197	com.surprising.aeron.service.execution.TerminalTombstoneStore.unlinkClient:103
190	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
182	com.surprising.aeron.service.state.RuntimeIndexedChangeBuffer.forEachIndexed:54
180	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probeThree:3090
179	com.surprising.aeron.service.execution.ClusterCommandWindow.complete:200
177	com.surprising.aeron.service.execution.TerminalTombstoneStore.clientSlot:98
176	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands:542
173	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.add:216
173	java.util.HashMap.getNode:579
161	com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask:1769
ownerAllocationClass
2916014120	[B
965686896	com.surprising.aeron.protocol.PlaceOrderCommand
719606096	[J
610396808	java.lang.String
275082648	java.lang.Long
238815848	[Ljava.lang.Object;
224406920	[I
209062608	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x000000012f1ec230
198750208	com.surprising.aeron.protocol.CoreResponse
158668288	com.surprising.aeron.service.execution.CommandResultLedger$StoredResult
132991936	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x000000012f1e3c60
130448720	com.surprising.aeron.protocol.CoreMessageHeader
111748008	java.nio.HeapByteBuffer
110406328	com.surprising.aeron.service.execution.OrderBatchExecutor$$Lambda.0x000000012f1ec000
108601464	java.util.HashMap$Node
87127344	com.surprising.aeron.service.execution.ContinuousTradingClusterService$Output
84878104	com.surprising.aeron.protocol.CoreOrderStateView
84538728	com.surprising.aeron.protocol.CancelOrderCommand
84031856	com.surprising.aeron.service.execution.ImmutableLongArrayList
80961360	java.util.HashMap$KeyIterator
80376736	com.surprising.aeron.service.state.ResolvedPlaceOrder
76697584	java.util.ArrayList$Itr
74973224	org.eclipse.collections.impl.set.mutable.primitive.IntHashSet
74869160	com.surprising.aeron.service.state.RuntimeProjectionPoint
65003376	java.util.LinkedHashMap$Entry
ownerAllocationPaths
2772272296	TradingCoreRuntime.prepareClusterPipelineScope
2626934304	TradingOrderBatchCodec.decodeCommand
518611000	ActiveOrderIndex.applySnapshot
131897776	TradingRuntimeState.collectMatcherSettlement
73340200	TradingRuntimeState.collectCancel
68787864	OrderBatchExecutor.preparePipelinedPlaceBatch
34303736	MatcherSettlementPlan.build
laneLeaf
108269	com.surprising.aeron.service.state.SettlementLaneWorker.run:154
2066	java.lang.ThreadLocal.get:171
1474	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.getIfAbsent:2362
1463	com.surprising.aeron.service.state.SettlementLaneWorker.run:136
912	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.probeThree:3090
877	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.get:2340
731	java.lang.ThreadLocal.get:184
517	java.util.concurrent.ConcurrentHashMap.get:949
383	com.surprising.aeron.service.state.OrderRuntime.<init>:47
361	java.util.ImmutableCollections$ListItr.next:397
275	java.lang.ThreadLocal.get:193
269	com.surprising.aeron.service.state.LanePublishedMap$Version.reclaim:49
ownerParkMonitorNs
afterGCminute=0 count=112 minMiB=101.757 averageMiB=104.900 maxMiB=109.628
afterGCminute=1 count=58 minMiB=103.655 averageMiB=106.328 maxMiB=110.263

```

gates-upper.log
```text
SPOT execute productLineGate=PASS mode=execute productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT replay productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT snapshotPosition=3616
SPOT snapshot productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL execute productLineGate=PASS mode=execute productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL replay productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL snapshotPosition=9568
LINEAR_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL execute productLineGate=PASS mode=execute productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL replay productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL snapshotPosition=9568
INVERSE_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY execute productLineGate=PASS mode=execute productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY replay productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY snapshotPosition=5184
LINEAR_DELIVERY snapshot productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY execute productLineGate=PASS mode=execute productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY replay productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY snapshotPosition=5184
INVERSE_DELIVERY snapshot productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
OPTION execute productLineGate=PASS mode=execute productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION replay productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION snapshotPosition=5184
OPTION snapshot productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701

```

recovery-upper.log
```text
master replay PASS businessHash=302d26290618b164
master snapshotPosition=1973235296
master snapshot PASS businessHash=302d26290618b164

```

master-control-INVERSE_DELIVERY-0
```text
Iteration   2: accountControlVerify=PASS product=INVERSE_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=97–97（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3148584KB, committed=692212KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=60555KB, committed=1947KB)
-                      Code (reserved=250358KB, committed=16026KB)
-                        GC (reserved=91623KB, committed=71183KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138233KB, committed=696757KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52372KB, committed=2584KB)
-                      Code (reserved=252426KB, committed=22802KB)
-                        GC (reserved=91806KB, committed=71326KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2269032 sha256=8c90320f4ce8467636d04cf14bfec22b721f3e897b87d2ce16a88ead50f4f3eb
 Duration: 14 s
 jdk.ThreadPark                          42963       1203251
 jdk.ExecutionSample                     18655        205176
 jdk.ObjectAllocationSample                198          2930
 jdk.GCPhasePauseLevel1                     15           595
 jdk.GCPhasePauseLevel2                     10           348
 jdk.GCPhasePause                            4           100
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"INVERSE_DELIVERY"},"primaryMetric":{"score":3114.275092,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3062.450018,"50.0":3114.275092,"90.0":3166.100166,"95.0":3166.100166,"99.0":3166.100166,"99.9":3166.100166,"99.99":3166.100166,"99.999":3166.100166,"99.9999":3166.100166,"100.0":3166.100166},"scoreUnit":"ms/op","rawData":[[3062.450018,3166.100166]]},"secondaryMetrics":{"gc.alloc.rate":{"score":0.7456773947671981,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.7226884448968361,"50.0":0.7456773947671981,"90.0":0.76866634463756,"95.0":0.76866634463756,"99.0":0.76866634463756,"99.9":0.76866634463756,"99.99":0.76866634463756,"99.999":0.76866634463756,"99.9999":0.76866634463756,"100.0":0.76866634463756},"scoreUnit":"MB/sec","rawData":[[0.7226884448968361,0.76866634463756]]},"gc.alloc.rate.norm":{"score":2469268.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2320920.0,"50.0":2469268.0,"90.0":2617616.0,"95.0":2617616.0,"99.0":2617616.0,"99.9":2617616.0,"99.99":2617616.0,"99.999":2617616.0,"99.9999":2617616.0,"100.0":2617616.0},"scoreUnit":"B/op","rawData":[[2320920.0,2617616.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=INVERSE_DELIVERY","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-control-INVERSE_DELIVERY-0/jmh.json"]]
```

master-control-INVERSE_PERPETUAL-0
```text
Iteration   2: accountControlVerify=PASS product=INVERSE_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=93–97（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3142336KB, committed=692084KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1916KB)
-                      Code (reserved=250329KB, committed=15997KB)
-                        GC (reserved=91616KB, committed=71176KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138170KB, committed=696826KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2587KB)
-                      Code (reserved=252419KB, committed=22859KB)
-                        GC (reserved=91803KB, committed=71323KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2260311 sha256=926eaf7c8b1eaac881e458d6b7090059cc658585087576180c3a15f80d0799fc
 Duration: 14 s
 jdk.ThreadPark                          42970       1203172
 jdk.ExecutionSample                     18600        204572
 jdk.ObjectAllocationSample                163          2413
 jdk.GCPhasePauseLevel1                     15           595
 jdk.GCPhasePauseLevel2                     10           348
 jdk.GCPhasePause                            4           100
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"INVERSE_PERPETUAL"},"primaryMetric":{"score":3055.6487129999996,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3045.353859,"50.0":3055.6487129999996,"90.0":3065.943567,"95.0":3065.943567,"99.0":3065.943567,"99.9":3065.943567,"99.99":3065.943567,"99.999":3065.943567,"99.9999":3065.943567,"100.0":3065.943567},"scoreUnit":"ms/op","rawData":[[3065.943567,3045.353859]]},"secondaryMetrics":{"gc.alloc.rate":{"score":0.8753512412875928,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.6065236438415315,"50.0":0.8753512412875928,"90.0":1.144178838733654,"95.0":1.144178838733654,"99.0":1.144178838733654,"99.9":1.144178838733654,"99.99":1.144178838733654,"99.999":1.144178838733654,"99.9999":1.144178838733654,"100.0":1.144178838733654},"scoreUnit":"MB/sec","rawData":[[0.6065236438415315,1.144178838733654]]},"gc.alloc.rate.norm":{"score":2845036.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1950096.0,"50.0":2845036.0,"90.0":3739976.0,"95.0":3739976.0,"99.0":3739976.0,"99.9":3739976.0,"99.99":3739976.0,"99.999":3739976.0,"99.9999":3739976.0,"100.0":3739976.0},"scoreUnit":"B/op","rawData":[[1950096.0,3739976.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=INVERSE_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=INVERSE_PERPETUAL","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-control-INVERSE_PERPETUAL-0/jmh.json"]]
```

master-control-LINEAR_DELIVERY-0
```text
Iteration   2: accountControlVerify=PASS product=LINEAR_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=97–100（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3142274KB, committed=691970KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1864KB)
-                      Code (reserved=250348KB, committed=16016KB)
-                        GC (reserved=91615KB, committed=71175KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3140286KB, committed=696842KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=54422KB, committed=2602KB)
-                      Code (reserved=252430KB, committed=22870KB)
-                        GC (reserved=91804KB, committed=71324KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2267686 sha256=507a7df4014b66cf3e68ba89e41c6355a12f9b53fed3e35b1d7d0c9282d9e18a
 Duration: 14 s
 jdk.ThreadPark                          42912       1201500
 jdk.ExecutionSample                     18458        203002
 jdk.ObjectAllocationSample                166          2453
 jdk.GCPhasePauseLevel1                     15           595
 jdk.GCPhasePauseLevel2                     10           348
 jdk.GCPhasePause                            4           100
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"LINEAR_DELIVERY"},"primaryMetric":{"score":3105.5732335,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3063.967124,"50.0":3105.5732335,"90.0":3147.179343,"95.0":3147.179343,"99.0":3147.179343,"99.9":3147.179343,"99.99":3147.179343,"99.999":3147.179343,"99.9999":3147.179343,"100.0":3147.179343},"scoreUnit":"ms/op","rawData":[[3147.179343,3063.967124]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.2111073898944045,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":1.084522738958506,"50.0":1.2111073898944045,"90.0":1.3376920408303028,"95.0":1.3376920408303028,"99.0":1.3376920408303028,"99.9":1.3376920408303028,"99.99":1.3376920408303028,"99.999":1.3376920408303028,"99.9999":1.3376920408303028,"100.0":1.3376920408303028},"scoreUnit":"MB/sec","rawData":[[1.084522738958506,1.3376920408303028]]},"gc.alloc.rate.norm":{"score":3994860.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3579328.0,"50.0":3994860.0,"90.0":4410392.0,"95.0":4410392.0,"99.0":4410392.0,"99.9":4410392.0,"99.99":4410392.0,"99.999":4410392.0,"99.9999":4410392.0,"100.0":4410392.0},"scoreUnit":"B/op","rawData":[[3579328.0,4410392.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_DELIVERY","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=LINEAR_DELIVERY","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-control-LINEAR_DELIVERY-0/jmh.json"]]
```

master-control-LINEAR_PERPETUAL-0
```text
Iteration   2: accountControlVerify=PASS product=LINEAR_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=89–91（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3144541KB, committed=692221KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=56455KB, committed=1899KB)
-                      Code (reserved=250335KB, committed=16003KB)
-                        GC (reserved=91627KB, committed=71187KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3138186KB, committed=696770KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52372KB, committed=2516KB)
-                      Code (reserved=252428KB, committed=22868KB)
-                        GC (reserved=91790KB, committed=71310KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2271813 sha256=8c3199a27ee69ebb92437d95485efb53f397d74da693c7cff6db3a83491c21de
 Duration: 14 s
 jdk.ThreadPark                          43062       1206032
 jdk.ExecutionSample                     18675        205392
 jdk.ObjectAllocationSample                172          2542
 jdk.GCPhasePauseLevel1                     15           595
 jdk.GCPhasePauseLevel2                     10           348
 jdk.GCPhasePause                            4           100
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"LINEAR_PERPETUAL"},"primaryMetric":{"score":3087.313232,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":3053.10797,"50.0":3087.313232,"90.0":3121.518494,"95.0":3121.518494,"99.0":3121.518494,"99.9":3121.518494,"99.99":3121.518494,"99.999":3121.518494,"99.9999":3121.518494,"100.0":3121.518494},"scoreUnit":"ms/op","rawData":[[3053.10797,3121.518494]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.0011952755164253,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.855144581097114,"50.0":1.0011952755164253,"90.0":1.1472459699357365,"95.0":1.1472459699357365,"99.0":1.1472459699357365,"99.9":1.1472459699357365,"99.99":1.1472459699357365,"99.999":1.1472459699357365,"99.9999":1.1472459699357365,"100.0":1.1472459699357365},"scoreUnit":"MB/sec","rawData":[[0.855144581097114,1.1472459699357365]]},"gc.alloc.rate.norm":{"score":3285224.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2737944.0,"50.0":3285224.0,"90.0":3832504.0,"95.0":3832504.0,"99.0":3832504.0,"99.9":3832504.0,"99.99":3832504.0,"99.999":3832504.0,"99.9999":3832504.0,"100.0":3832504.0},"scoreUnit":"B/op","rawData":[[2737944.0,3832504.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=LINEAR_PERPETUAL","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-control-LINEAR_PERPETUAL-0/jmh.json"]]
```

master-control-OPTION-0
```text
Iteration   2: accountControlVerify=PASS product=OPTION terminalBusinessOperations=1800 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=97–100（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3142414KB, committed=692094KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1848KB)
-                      Code (reserved=250343KB, committed=16011KB)
-                        GC (reserved=91618KB, committed=71178KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3140181KB, committed=696169KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=54422KB, committed=2606KB)
-                      Code (reserved=252408KB, committed=22268KB)
-                        GC (reserved=91811KB, committed=71339KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=2064154 sha256=315d198389453985665c10eeacf8b861da6731fbb53a0e539d88ef28787b679f
 Duration: 12 s
 jdk.ThreadPark                          37876       1060502
 jdk.ExecutionSample                     15132        166419
 jdk.ObjectAllocationSample                168          2479
 jdk.GCPhasePauseLevel1                     15           595
 jdk.GCPhasePauseLevel2                     10           348
 jdk.GCPhasePause                            4           100
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"OPTION"},"primaryMetric":{"score":2324.8788335,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2293.524667,"50.0":2324.8788335,"90.0":2356.233,"95.0":2356.233,"99.0":2356.233,"99.9":2356.233,"99.99":2356.233,"99.999":2356.233,"99.9999":2356.233,"100.0":2356.233},"scoreUnit":"ms/op","rawData":[[2356.233,2293.524667]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.0405914347104994,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.8352149562262927,"50.0":1.0405914347104994,"90.0":1.2459679131947061,"95.0":1.2459679131947061,"99.0":1.2459679131947061,"99.9":1.2459679131947061,"99.99":1.2459679131947061,"99.999":1.2459679131947061,"99.9999":1.2459679131947061,"100.0":1.2459679131947061},"scoreUnit":"MB/sec","rawData":[[0.8352149562262927,1.2459679131947061]]},"gc.alloc.rate.norm":{"score":2579164.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":2063856.0,"50.0":2579164.0,"90.0":3094472.0,"95.0":3094472.0,"99.0":3094472.0,"99.9":3094472.0,"99.99":3094472.0,"99.999":3094472.0,"99.9999":3094472.0,"100.0":3094472.0},"scoreUnit":"B/op","rawData":[[2063856.0,3094472.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=OPTION","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=OPTION","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-control-OPTION-0/jmh.json"]]
```

master-control-SPOT-0
```text
Iteration   2: accountControlVerify=PASS product=SPOT terminalBusinessOperations=600 unfinished=0 fundsDiff=0 netPosition=0
CPU_Speed_Limit=85–87（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3146462KB, committed=692106KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=58505KB, committed=1913KB)
-                      Code (reserved=250332KB, committed=16000KB)
-                        GC (reserved=91620KB, committed=71180KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3139131KB, committed=693519KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=54422KB, committed=2490KB)
-                      Code (reserved=251853KB, committed=21261KB)
-                        GC (reserved=91760KB, committed=71280KB)
-                     Other (reserved=9411KB, committed=9411KB)
JFR bytes=1341072 sha256=375ad4c31d8f8f20d5bb2ccc9cf360aed80de0e31605bb58a7b4bd34bff9df5a
 Duration: 6 s
 jdk.ThreadPark                          18885        528750
 jdk.ExecutionSample                      5319         58478
 jdk.ObjectAllocationSample                186          2748
 jdk.GCPhasePauseLevel1                     15           595
 jdk.GCPhasePauseLevel2                     10           348
 jdk.GCPhasePause                            4           100
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":1,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":2,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"productLine":"SPOT"},"primaryMetric":{"score":810.2058045,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":795.129565,"50.0":810.2058045,"90.0":825.282044,"95.0":825.282044,"99.0":825.282044,"99.9":825.282044,"99.99":825.282044,"99.999":825.282044,"99.9999":825.282044,"100.0":825.282044},"scoreUnit":"ms/op","rawData":[[825.282044,795.129565]]},"secondaryMetrics":{"gc.alloc.rate":{"score":1.2943141938368932,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":0.8208618011670447,"50.0":1.2943141938368932,"90.0":1.7677665865067418,"95.0":1.7677665865067418,"99.0":1.7677665865067418,"99.9":1.7677665865067418,"99.99":1.7677665865067418,"99.999":1.7677665865067418,"99.9999":1.7677665865067418,"100.0":1.7677665865067418},"scoreUnit":"MB/sec","rawData":[[0.8208618011670447,1.7677665865067418]]},"gc.alloc.rate.norm":{"score":1151748.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":710544.0,"50.0":1151748.0,"90.0":1592952.0,"95.0":1592952.0,"99.0":1592952.0,"99.9":1592952.0,"99.99":1592952.0,"99.999":1592952.0,"99.9999":1592952.0,"100.0":1592952.0},"scoreUnit":"B/op","rawData":[[710544.0,1592952.0]]},"gc.count":{"score":0.0,"scoreError":"NaN","scoreConfidence":[0.0,0.0],"scorePercentiles":{"0.0":0.0,"50.0":0.0,"90.0":0.0,"95.0":0.0,"99.0":0.0,"99.9":0.0,"99.99":0.0,"99.999":0.0,"99.9999":0.0,"100.0":0.0},"scoreUnit":"counts","rawData":[[0.0,0.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=SPOT","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterAccountControlBenchmark.accountControls","-f","1","-wi","1","-i","2","-p","productLine=SPOT","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-control-SPOT-0/jmh.json"]]
```

master-main-LINEAR_PERPETUAL-0
```text
measurementStartEpochMillis=1789052823736
measurementEndEpochMillis=1789052883792
ownerChurnVerify=PASS completedOrderLifecycles=8632320 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=759 businessHash=ad5b4645664a3f98
adminActionRetries=70
mixedCapacity=PASS elapsedSeconds=60.056 terminalBusinessOperations=12100352 offeredBusinessOperations=12100352 terminalCoreMessages=1166080 offeredCoreMessages=1166080 businessOpsPerSec=201483.078 coreMessagesPerSec=19416.409 fills=2877440 fillsPerSec=47912.281 queries=0 unfinished=0 peakInFlight=256 measuredCycles=562 totalCycles=759 triggerExecutions=0
business=PLACE_ORDER items=287744 requests=287744 p50us=7385 p90us=18628 p95us=20479 p99us=24690 p999us=49905 maxus=62554
business=CANCEL_ORDER items=287744 requests=287744 p50us=6709 p90us=15261 p95us=17022 p99us=21954 p999us=44204 maxus=58359
business=APPLY_MARK_PRICE items=15104 requests=15104 p50us=11804 p90us=19644 p95us=21954 p99us=35487 p999us=51740 maxus=53411
business=PLACE_ORDER_BATCH items=8632320 requests=431616 p50us=16498 p90us=19726 p95us=21790 p99us=34209 p999us=53280 maxus=61538
business=CANCEL_ORDER_BATCH items=2877440 requests=143872 p50us=18169 p90us=21577 p95us=23101 p99us=32292 p999us=58327 maxus=62619
CPU_Speed_Limit=64–100（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3118415KB, committed=666683KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=49273KB, committed=1729KB)
-                      Code (reserved=249329KB, committed=11837KB)
-                        GC (reserved=91608KB, committed=71168KB)
-                     Other (reserved=9385KB, committed=9385KB)
nmt-after.txt
Total: reserved=3130903KB, committed=706659KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=49290KB, committed=2478KB)
-                      Code (reserved=261863KB, committed=45719KB)
-                        GC (reserved=92460KB, committed=71988KB)
-                     Other (reserved=9387KB, committed=9387KB)
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-main-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-main-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-main-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-main-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-main-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=60","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain"]]
```

master-profile-LINEAR_PERPETUAL-0
```text
measurementStartEpochMillis=1789052956937
measurementEndEpochMillis=1789053047070
ownerChurnVerify=PASS completedOrderLifecycles=11412480 terminalIndexEmpty=true reservationsEmpty=true
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=947 businessHash=302d26290618b164
adminActionRetries=92
mixedCapacity=PASS elapsedSeconds=90.133 terminalBusinessOperations=16000263 offeredBusinessOperations=16000263 terminalCoreMessages=1544455 offeredCoreMessages=1544455 businessOpsPerSec=177517.466 coreMessagesPerSec=17135.202 fills=3804160 fillsPerSec=42205.859 queries=0 unfinished=0 peakInFlight=256 measuredCycles=743 totalCycles=947 triggerExecutions=0
business=PLACE_ORDER items=380416 requests=380416 p50us=8323 p90us=21397 p95us=23429 p99us=27934 p999us=51019 maxus=63012
business=CANCEL_ORDER items=380416 requests=380416 p50us=7835 p90us=17055 p95us=18939 p99us=25001 p999us=46759 maxus=63340
business=APPLY_MARK_PRICE items=22791 requests=22791 p50us=13049 p90us=21004 p95us=23871 p99us=42434 p999us=60391 maxus=63864
business=PLACE_ORDER_BATCH items=11412480 requests=570624 p50us=18431 p90us=22200 p95us=25116 p99us=39124 p999us=53739 maxus=66420
business=CANCEL_ORDER_BATCH items=3804160 requests=190208 p50us=20905 p90us=25001 p95us=27328 p99us=42631 p999us=58228 maxus=62914
CPU_Speed_Limit=64–85（明显限速，性能验收无效，仅诊断）
nmt-before.txt
Total: reserved=3142157KB, committed=691273KB
-                 Java Heap (reserved=1572864KB, committed=526336KB)
-                    Thread (reserved=54404KB, committed=1864KB)
-                      Code (reserved=250287KB, committed=15375KB)
-                        GC (reserved=91616KB, committed=71176KB)
-                     Other (reserved=9409KB, committed=9409KB)
nmt-after.txt
Total: reserved=3156810KB, committed=736030KB
-                 Java Heap (reserved=1572864KB, committed=524288KB)
-                    Thread (reserved=52371KB, committed=2603KB)
-                      Code (reserved=261002KB, committed=48278KB)
-                        GC (reserved=92332KB, committed=71852KB)
-                     Other (reserved=9413KB, committed=9413KB)
JFR bytes=19088620 sha256=625c8d5ef5a5744ae1f2dfde5e22b684ac86d030b22862f08c8a9934bd124fb0
 Duration: 164 s
 jdk.ExecutionSample                    296087       3538400
 jdk.ThreadPark                         235145       6783616
 jdk.ObjectAllocationSample              35642        576832
 jdk.GCPhasePauseLevel1                    674         28359
 jdk.GCPhasePause                          223          5450
 jdk.GCPhasePauseLevel2                     18           694
 jdk.JavaMonitorEnter                        1            23
 jdk.DataLoss                                0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
JMH [{"jmhVersion":"1.37","benchmark":"com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations","mode":"ss","threads":1,"forks":1,"jvm":"/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","jvmArgs":["-XX:ThreadPriorityPolicy=1","-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCIProduct","-XX:+EnableJVMCI","-XX:-UnlockExperimentalVMOptions","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true"],"jdkVersion":"25.0.1","vmName":"Java HotSpot(TM) 64-Bit Server VM","vmVersion":"25.0.1+8-LTS-jvmci-b01","warmupIterations":0,"warmupTime":"single-shot","warmupBatchSize":1,"measurementIterations":1,"measurementTime":"single-shot","measurementBatchSize":1,"params":{"controlPageSize":"0"},"primaryMetric":{"score":90.133993075,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":90.133993075,"50.0":90.133993075,"90.0":90.133993075,"95.0":90.133993075,"99.0":90.133993075,"99.9":90.133993075,"99.99":90.133993075,"99.999":90.133993075,"99.9999":90.133993075,"100.0":90.133993075},"scoreUnit":"s/op","rawData":[[90.133993075]]},"secondaryMetrics":{"gc.alloc.rate":{"score":150.99037582514833,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":150.99037582514833,"50.0":150.99037582514833,"90.0":150.99037582514833,"95.0":150.99037582514833,"99.0":150.99037582514833,"99.9":150.99037582514833,"99.99":150.99037582514833,"99.999":150.99037582514833,"99.9999":150.99037582514833,"100.0":150.99037582514833},"scoreUnit":"MB/sec","rawData":[[150.99037582514833]]},"gc.alloc.rate.norm":{"score":25141963424.0,"scoreError":"NaN","scoreConfidence":["NaN","NaN"],"scorePercentiles":{"0.0":25141963424.0,"50.0":25141963424.0,"90.0":25141963424.0,"95.0":25141963424.0,"99.0":25141963424.0,"99.9":25141963424.0,"99.99":25141963424.0,"99.999":25141963424.0,"99.9999":25141963424.0,"100.0":25141963424.0},"scoreUnit":"B/op","rawData":[[25141963424.0]]},"gc.count":{"score":323.0,"scoreError":"NaN","scoreConfidence":[323.0,323.0],"scorePercentiles":{"0.0":323.0,"50.0":323.0,"90.0":323.0,"95.0":323.0,"99.0":323.0,"99.9":323.0,"99.99":323.0,"99.999":323.0,"99.9999":323.0,"100.0":323.0},"scoreUnit":"counts","rawData":[[323.0]]},"gc.time":{"score":304.0,"scoreError":"NaN","scoreConfidence":[304.0,304.0],"scorePercentiles":{"0.0":304.0,"50.0":304.0,"90.0":304.0,"95.0":304.0,"99.0":304.0,"99.9":304.0,"99.99":304.0,"99.999":304.0,"99.9999":304.0,"100.0":304.0},"scoreUnit":"ms","rawData":[[304.0]]}}}]
commands [["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms512m","-Xmx1536m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/media","-Dsurprising.aeron.data-dir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/data","-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK","-Dsurprising.aeron.service.idle-strategy=YIELDING","-XX:StartFlightRecording=settings=/tmp/owner-master-opt/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/node.jfr","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar","com.surprising.aeron.service.cluster.SurprisingClusterNode"],["/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED","--add-opens=java.base/java.util.zip=ALL-UNNAMED","--enable-native-access=ALL-UNNAMED","-XX:+UseG1GC","-XX:NativeMemoryTracking=summary","-Dsurprising.aeron.hostnames=127.0.0.1","-Dsurprising.aeron.egress-hostname=127.0.0.1","-Dsurprising.aeron.node-id=0","-Dsurprising.aeron.account-lanes=4","-Dsurprising.aeron.matching-engines=2","-Dsurprising.aeron.settlement-spin-limit=0","-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN","-Dsurprising.aeron.product-line=LINEAR_PERPETUAL","-Dsurprising.aeron.execution-mode=PIPELINED","-Xms128m","-Xmx512m","-Djava.io.tmpdir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/tmp","-Daeron.dir=/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/clientmedia","-Dsurprising.aeron.client.threading-mode=SHARED","-Dsurprising.aeron.capacity-async-in-flight=256","-Dsurprising.aeron.capacity-session-in-flight=256","-Dsurprising.aeron.capacity-warmup-seconds=30","-Dsurprising.aeron.capacity-duration-seconds=90","-Dsurprising.aeron.capacity-seed=131001","-Dsurprising.aeron.mixed-trading-stream=true","-Dsurprising.aeron.mixed-operational=false","-Dsurprising.aeron.owner-churn-validation=true","-cp","/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar","org.openjdk.jmh.Main","ClusterOperationalBenchmark.continuousOperations","-f","1","-wi","0","-i","1","-p","controlPageSize=0","-prof","gc","-rf","json","-rff","/tmp/owner-master-opt/perf-upper/master-profile-LINEAR_PERPETUAL-0/jmh.json"]]
```

校验信息
```text
/tmp/owner-master-opt/perf-upper.py bytes=6034 sha256=eb4ba323997d13c205f44d86dacd1a73241396fd74dd6cc420d8f9963d3598b0
/tmp/owner-master-opt/gates-upper.py bytes=5032 sha256=59545b8c56cfa66e25cb984ba36e6b9e0d397b13729dd38aac98acb05dd2ff44
/tmp/owner-master-opt/recovery-upper.py bytes=3373 sha256=dbdccc655a11c6503494e167e5b39e0e46ddfbc135f7994251e61d9d3ad1c76d
/tmp/owner-master-opt/owner.jfc bytes=39826 sha256=dca13cdc2a8f9d4a88f6dc954dd26cf8d2f85da5e4f922cac4b71f6c86c671d3
/tmp/owner-master-opt/OwnerProfile.java bytes=3905 sha256=39c6ab01dee521d451642468486e5489d68f492423dfa94c00778326d8c6c8c2
surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar bytes=60908837 sha256=40739bcc0b84c12ac18f87eec4292e79bf60b76e2bd6db960d2aab1f6ddf4c4c
surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar bytes=81420865 sha256=241fa3a3eac792ac0a6453c34a815daf5bc5530d99108c2aa7423f0eab8b5862

```

显式GC后类直方图（前35项）
```text
4883:
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         39068       16866496  [J (java.base@25.0.1)
   2:        154874       13188352  [B (java.base@25.0.1)
   3:         18429       12259744  [Ljava.lang.Object; (java.base@25.0.1)
   4:         20056        9645656  [I (java.base@25.0.1)
   5:        133586        3206064  java.lang.String (java.base@25.0.1)
   6:           809        2844800  [Ljdk.internal.vm.FillerElement; (java.base@25.0.1)
   7:         31744        2539520  com.surprising.aeron.protocol.PlaceOrderCommand
   8:         40279        1288928  java.util.concurrent.ConcurrentHashMap$Node (java.base@25.0.1)
   9:         10115         890120  exchange.core2.core.orderbook.OrderBookDirectImpl$DirectOrder
  10:          4995         879120  com.surprising.aeron.service.state.OrderRuntime
  11:          4995         879120  com.surprising.aeron.service.state.model.CoreOrderState
  12:          5085         659616  java.lang.Class (java.base@25.0.1)
  13:            95         640752  [Ljava.util.concurrent.ConcurrentHashMap$Node; (java.base@25.0.1)
  14:          4096         622592  com.surprising.aeron.service.execution.PendingMatching
  15:         15528         621120  com.surprising.aeron.service.state.LanePublishedMap$Version
  16:          1136         575080  [Ljava.lang.String; (java.base@25.0.1)
  17:         14017         448544  com.surprising.aeron.service.state.LanePublication
  18:         18446         442704  java.lang.Long (java.base@25.0.1)
  19:          4995         399600  com.surprising.aeron.service.state.ReservationRuntime
  20:          9563         382520  org.eclipse.collections.impl.set.mutable.primitive.LongHashSet
  21:         15533         372792  com.surprising.aeron.service.state.LanePublishedMap$Key
  22:          4995         359640  com.surprising.aeron.service.state.index.OrderParticipantIndex$Node
  23:         10860         347520  java.util.UUID (java.base@25.0.1)
  24:          3769         331672  com.surprising.aeron.service.state.PositionRuntime
  25:          4096         327680  com.surprising.aeron.protocol.CoreMessageHeader
  26:          4096         327680  com.surprising.aeron.service.execution.LaneCommandContextRing$Context
  27:          8192         327680  com.surprising.aeron.service.execution.PendingClusterIngress$Entry
  28:         11264         270336  com.surprising.aeron.protocol.CancelOrderCommand
  29:          8383         268256  java.util.HashMap$Node (java.base@25.0.1)
  30:          8192         262144  com.surprising.aeron.service.execution.MatcherCommandPipeline$Slot
  31:          2365         245960  com.surprising.aeron.service.state.AccountLaneState$AdmissionAggregate
  32:          2193         241432  [Ljava.util.HashMap$Node; (java.base@25.0.1)
  33:          3257         234504  com.surprising.aeron.service.state.RiskSnapshotRuntime
  34:          2667         213560  [Z (java.base@25.0.1)
  35:          2304         202752  com.surprising.aeron.service.state.MatcherSettlementPlan
  36:          4737         189480  com.surprising.aeron.service.state.RuntimeFundsAccumulator
  37:          4476         179040  java.util.LinkedHashMap$Entry (java.base@25.0.1)
```

异常/提示：JMH的sun.misc.Unsafe过时API及SLF4J缺少provider提示属于测试工具/日志绑定，未出现EXCHANGE_CORE_FAILURE。adminActionRetries来自Aeron Publication.ADMIN_ACTION offer暂缓，非后台业务失败；结果已单独保留。辅助记录命令一次因工作目录少写前导斜杠未启动，纠正后执行，无业务影响。所有原始产物下条清理后不可访问，保留本记录与构建JAR，README未改。

最终清理确认：本轮节点/客户端均已退出，恢复编排完整退出；已删除剩余/tmp/owner-master-opt（10468873618字节），以及本轮生成的338个测试报告文件，9个空报告目录。前两轮另已清理13581884940和13850504700字节。原始JFR、Archive、临时日志/源码/类/报告不可访问；本记录、构建JAR和用户原有未跟踪文件保留。

### 2026-09-10 Owner 收尾与未完成轮询诊断（采集前锁定）

- 被测 master：86e07b9997856047ff1f60c855658f40fc373254，业务代码 2a60fed1；对照 commit：不适用（只测当前 master）。本轮先定位，不修改资金、索引或提交边界。
- 本机 Intel i9-9880H 8C16T/16GiB，HotSpot Oracle GraalVM 25.0.1，Maven 3.9.16，G1；节点 512m/1536m，客户端 128m/512m，NMT summary。仅一个真实 Aeron Cluster 成员，保留网络和 Archive，PIPELINED，4 Account Lane BUSY_SPIN、2 matcher，SHARED_NETWORK，service YIELDING。两 matcher 仅用于瓶颈诊断，不作正式容量结论。
- 场景：LINEAR_PERPETUAL，持续异步 mixed-trading-stream=true，mixed-operational=false，1769 用户、256 symbol、batch 20、in-flight/session-in-flight 256，单命令连接及保留查询连接；seed 131001，沿用现有负载初始化资金/持仓、做市买卖单。测量期间不并发执行完整风险/清算/查询业务。30s 预热、60s 测量、最终排空和资金/终态核对，结束即停止进程。
- JMH ClusterOperationalBenchmark.continuousOperations，fork1/thread1/wi0/i1/controlPageSize0，-prof gc。节点 profile.jfc 衍生 JFR，ExecutionSample 2ms、ThreadCPU 1s、park/monitor threshold 1ms、maxsize192MiB。
- 额外诊断 agent 仅位于 /tmp/owner-followup，不改生产类源码；在 Owner 方法入口累计次数，每1024次记录一次耗时及累计返回结果到 surprising.OwnerWork。false/null 只代表该方法未完成提交，不代表其内部没有派发或收集工作。按测量时间过滤累计计数，相邻事件差统计存在首尾最多约1024次误差；抽样墙钟耗时包含调度与嵌套调用，不能与 CPU 样本相加。agent 会影响 JIT/耗时，吞吐只能作诊断，不能作优化提升结论。
- 数据条件：accepted=terminal、unfinished=0、fundsDiff=0、业务状态检查 PASS、Owner 无失败/JFR无DataLoss；记录 thermal throttling，出现明显降频不作性能验收。磁盘初始506GiB可用，低于10GiB停止。原始路径 /tmp/owner-followup/runs；分析完成后记录摘要、校验并清理。

- 首次诊断启动中止：节点 fat jar 自带旧 ASM 遮蔽 agent 的 ASM 9.9.1，导致 `Unsupported class file major version 69`，转换器未生效。不是业务异常；不使用该轮数据。已停止客户端及 fork，runner 收尾停止节点。重启诊断轮保持上述场景不变，仅将 agent.jar 放在节点 classpath 前，确认全部目标成功插桩后才接受计数。
- 第二次诊断仅启动阶段失败：agent 在条件 return 中新建局部变量产生非法 StackMap，离线 Class.forName 复现为 VerifyError；改用返回栈 DUP 保留原值，七个目标类离线验证全部通过。未开始业务测量，不归因于 Core。第三轮仍按锁定场景，路径 runs3；另记录 readyLaneMask 非零返回比例。

#### 诊断结果及本轮修改

- 诊断业务核对 PASS：8552263 business ops、828231 Core messages、2032640 fills，60.165s，142147.390 business ops/s，仅探针诊断数据。Owner 96.819%，matcher 25.189/25.152%，CPU_Speed_Limit 68，不能作容量验收。
- 关键计数（首尾最多约1024调用误差）：pollCommandPrefix 23037952，完成798087（3.464%）；pumpMatchingCommitCompletions 23351296；readyLaneMask 46903296，非零1076650（2.295%）；finishOrderBatch 505856，完成404740（80.011%）。未完成前缀不等于内部完全没有业务推进；系统性每1024次墙钟采样受调度/周期混叠影响，外推总时间超过窗口，不采用其估算 CPU 比例。
- 修改：完整批量响应由用户 Lane 在 final commit event 完成回执前编码，Owner 接收不可变 byte[]；部分未捕获结果仍按原提交点补齐。Owner 前缀轮询仅在已无本地工作、进度/派发上限/前缀未变且无通知时跳过全套推进；保持入站轮询、超时/健康检测与按序提交。跳过已由 Lane 准备发布时的两个无效删除遍历。
- 首轮业务回归发现7个顺序批量用例不推进：原因是 Lane 所有权交接没有普通完成通知。已将未 started 的队首批量视为本地工作，不能进入仅通知等待；修正后282个受影响服务测试通过，包含六产品线并行/阻塞恢复、资金共享、部分拒单、原始日志时间、快照及响应字节一致性。

WorkReport.txt
```text
id=0 calls=22264832 success=22264832 successPct=100.000 timingSamples=22095 avgNs=3461.2 successAvgNs=3461.2 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=78.311
id=1 calls=23037952 success=798087 successPct=3.464 timingSamples=22862 avgNs=1944.7 successAvgNs=38301.6 incompleteAvgNs=513.3 sampledInclusiveWallSeconds=45.527
id=2 calls=505856 success=404740 successPct=80.011 timingSamples=495 avgNs=29482.1 successAvgNs=36943.8 incompleteAvgNs=2098.9 sampledInclusiveWallSeconds=14.944
id=3 calls=23351296 success=23351296 successPct=100.000 timingSamples=23174 avgNs=692.0 successAvgNs=692.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=16.420
id=4 calls=497664 success=497664 successPct=100.000 timingSamples=495 avgNs=12010.8 successAvgNs=12010.8 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=6.088
id=5 calls=299008 success=299008 successPct=100.000 timingSamples=297 avgNs=8520.4 successAvgNs=8520.4 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=2.591
id=6 calls=46903296 success=1076650 successPct=2.295 timingSamples=46547 avgNs=118.4 successAvgNs=242.2 incompleteAvgNs=114.5 sampledInclusiveWallSeconds=5.644
id=7 calls=848896 success=798200 successPct=94.028 timingSamples=843 avgNs=3922.1 successAvgNs=4175.1 incompleteAvgNs=150.7 sampledInclusiveWallSeconds=3.386
id=8 calls=813056 success=813056 successPct=100.000 timingSamples=808 avgNs=3451.2 successAvgNs=3451.2 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=2.855
id=9 calls=398336 success=398336 successPct=100.000 timingSamples=396 avgNs=3291.0 successAvgNs=3291.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=1.335
```

AnalyzeJfr.txt
```text
totals allocationMiBps=429.481 allocationBytes=27095336128 machineCPU=91.77 jvmCPU=57.44 heapMaxMiB=392.89 dataLoss=0 ownerIOEvents=0
threadCPU	97.572	core-account-lane-3
threadCPU	97.535	core-account-lane-2
threadCPU	97.529	core-account-lane-0
threadCPU	97.525	core-account-lane-1
threadCPU	96.819	trading-owner--1
threadCPU	96.444	/tmp/owner-followup/runs3/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	69.554	clustered-service-101-0
threadCPU	68.146	driver-conductor
threadCPU	63.348	archive-conductor
threadCPU	61.492	consensus-module-101-0
threadCPU	25.189	core-matcher-1
threadCPU	25.152	core-matcher-0
threadCPU	2.713	JVMCI-native CompilerThread0
threadCPU	1.870	aeron-md-nra
threadCPU	0.390	JFR Periodic Tasks
threadCPU	0.248	JFR Recorder Thread
threadCPU	0.229	C1 CompilerThread0
threadCPU	0.215	aeron-client
threadCPU	0.102	Monitor Deflation Thread
gc count=90 totalMs=603.640 p99Ms=11.975 maxMs=11.975
samples	22227	core-account-lane-0
samples	21964	core-account-lane-1
samples	21563	core-account-lane-2
samples	21014	core-account-lane-3
samples	19060	trading-owner--1
samples	1716	driver-conductor
samples	1000	consensus-module-101-0
samples	967	clustered-service-101-0
samples	920	archive-conductor
samples	354	/tmp/owner-followup/runs3/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	219	core-matcher-0
samples	177	core-matcher-1
ownerInclusive	19060	java.lang.Thread.run
ownerInclusive	19060	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012e130d98.run
ownerInclusive	19060	java.lang.Thread.runWith
ownerInclusive	19060	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	18791	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
ownerInclusive	18742	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	18682	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	13340	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	12708	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	7841	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	5344	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
ownerInclusive	4475	com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions
allocation	1501978168	trading-owner--1 [B
allocation	834888240	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	821351256	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	746021952	core-account-lane-3 [J
allocation	736979936	clustered-service-101-0 [B
allocation	719006544	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	713465640	core-account-lane-1 [J
allocation	707903104	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	671660616	core-account-lane-0 [J
allocation	650173064	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	641592056	core-account-lane-2 [J
allocation	627466680	trading-owner--1 com.surprising.aeron.protocol.PlaceOrderCommand
allocationSite	2931359344	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	1509087944	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	1506296184	com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand
allocationSite	1256148168	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	1072016840	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	980947688	com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.bindMatcherEvidence
allocationSite	949357192	java.util.List.copyOf
allocationSite	891764472	java.nio.ByteBuffer.allocate
allocationSite	812627024	com.surprising.aeron.service.matching.CoreMatchingResult.classify
allocationSite	736268768	com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order
allocationSite	732899544	java.lang.invoke.VarHandleLongs$Array.setRelease
allocationSite	701489904	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
parkOrMonitorNs	60105181077	Common-Cleaner
parkOrMonitorNs	59641433818	aeron-md-nra
parkOrMonitorNs	795165985	archive-conductor
parkOrMonitorNs	777375463	consensus-module-101-0
parkOrMonitorNs	593203368	core-matcher-0
parkOrMonitorNs	589083670	driver-conductor
parkOrMonitorNs	549842402	core-matcher-1
parkOrMonitorNs	422617887	/tmp/owner-followup/runs3/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
eventCounts	118012	surprising.OwnerWork
eventCounts	111184	jdk.ExecutionSample
eventCounts	56504	jdk.ThreadPark
eventCounts	50690	jdk.GCPhaseParallel
eventCounts	17474	jdk.ObjectAllocationSample
eventCounts	7454	jdk.PromoteObjectInNewPLAB
eventCounts	3642	jdk.ThreadSleep
eventCounts	2882	jdk.NativeMethodSample
eventCounts	1593	jdk.NativeMemoryUsage
eventCounts	1350	jdk.TenuringDistribution
eventCounts	1048	jdk.NativeLibrary
eventCounts	919	jdk.ThreadCPULoad
```

诊断 node.jfr SHA256=dc25ddc5314507342da1fc98819bbf2094d2e8804f3b97d6885c981f367744e3；原始文件将在本轮全部分析后删除。

#### 修改后采集标准（采集前锁定）

- 被测 master 工作树基于86e07b99，最终源码及JAR SHA256另附；对照不适用。仅本机单成员，其他JDK/机器/JVM/4Lane2matcher/网络/Archive参数同上。
- U本位持续负载：无探针无profiler主测30s预热/60s测量；JMH+JFR无探针30s预热/90s测量；额外agent诊断30s预热/60s测量用于调用比例，不作吞吐比较。JMH fork1/thread1/wi0/i1/-prof gc/controlPageSize0。in-flight256，1769用户，256币对，batch20，所有资金/终态/响应字段检查必须PASS。新增撤单响应验证：不允许返回已淘汰活动订单。
- 六产品线顺序运行新增 ClusterBatchResponseBenchmark.lanePreparedResponses：外部真实单成员，32账户、1币对、每项1单位、20项/批、每轮32批下单+32批撤单（先连续发送，再收集响应），每次JMH调用8轮，共10240业务动作；fork1/thread1/wi1/i2，-prof gc，节点JFR。限额256但该回归实际单轮最多64请求，不作饱和吞吐结论；用户初始1000000结算资产、无持仓，下单80/标记100，验证逐项身份、未成交下单视图、撤单空视图、余额回原值/冻结及订单预留归零。另逐产品执行原有功能→kill→日志恢复→快照→重启核对。
- 通过阈值：所有已接收业务终态、无积压/资金差/业务失败；现有及新增功能测试通过；JFR无DataLoss/Owner同步I/O。归因目标为最终agent的pump调用数低于prefix调用数的25%，且完整批量编码可在Lane采样观察到；若未达到须如实报告。降频或探针开销下不宣称容量验收或加速比。
- 仅新响应byte[]从Owner移到Lane分配，无新增长期缓存；连续负载覆盖多次槽复用，快照/重启验证状态，短采样不宣称无泄漏。数据目录/JFR192MiB上限、磁盘低于10GiB停止；结束清理。

- 最终编译：服务282测试及基准验证器9测试均PASS。新增外部JMH首次编译误用了用户视图的orders方法，已改为独立USER_OPEN_ORDERS_QUERY并重新构建成功。业务代码后续未修改。六产品线batchResponseVerify均PASS，每产品30720业务动作（含预热），实际最大单轮64请求。

六产品线 JMH 主分数单位为 ms/整次10240操作调用，不能直接当作连续业务吞吐：
- lanes-control-INVERSE_DELIVERY-0: 322.142541 ms/invocation；GC客户端 B/invocation=17010408；编码线程证据如下（全trial含预热，短采样不作容量结论）。
```text
6	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
2	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
6	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
49	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
1	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasLocalMatchingWork
48	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
13	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
3	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
39	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
39	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```
- lanes-control-INVERSE_PERPETUAL-0: 220.145314 ms/invocation；GC客户端 B/invocation=15773308；编码线程证据如下（全trial含预热，短采样不作容量结论）。
```text
8	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
9	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
40	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
1	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasLocalMatchingWork
49	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
8	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
2	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
42	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
41	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```
- lanes-control-LINEAR_DELIVERY-0: 300.136147 ms/invocation；GC客户端 B/invocation=13406008；编码线程证据如下（全trial含预热，短采样不作容量结论）。
```text
9	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
4	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
5	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
61	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
1	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasLocalMatchingWork
40	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
15	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
3	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
34	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
33	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```
- lanes-control-LINEAR_PERPETUAL-0: 217.455128 ms/invocation；GC客户端 B/invocation=13725220；编码线程证据如下（全trial含预热，短采样不作容量结论）。
```text
11	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
4	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
2	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
1	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
40	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
2	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasLocalMatchingWork
49	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
9	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
4	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
31	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
29	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```
- lanes-control-OPTION-0: 248.532191 ms/invocation；GC客户端 B/invocation=13637208；编码线程证据如下（全trial含预热，短采样不作容量结论）。
```text
6	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
5	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
10	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
41	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
49	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
15	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
2	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
41	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
41	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```
- lanes-control-SPOT-0: 224.008386 ms/invocation；GC客户端 B/invocation=15798028；编码线程证据如下（全trial含预热，短采样不作容量结论）。
```text
9	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
6	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
2	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
43	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
2	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasLocalMatchingWork
52	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
6	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
4	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
45	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
44	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```

main 结果
```text
measurementStartEpochMillis=1789055453741
measurementEndEpochMillis=1789055513860
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=713 businessHash=6f171e74ee070bb0
adminActionRetries=58
mixedCapacity=PASS elapsedSeconds=60.119 terminalBusinessOperations=10079051 offeredBusinessOperations=10079051 terminalCoreMessages=973643 offeredCoreMessages=973643 businessOpsPerSec=167650.476 coreMessagesPerSec=16195.147 fills=2396160 fillsPerSec=39856.666 queries=0 unfinished=0 peakInFlight=256 measuredCycles=468 totalCycles=713 triggerExecutions=0
business=PLACE_ORDER items=239616 requests=239616 p50us=9076 p90us=23166 p95us=25034 p99us=29376 p999us=54198 maxus=73007
business=CANCEL_ORDER items=239616 requests=239616 p50us=8232 p90us=18219 p95us=20103 p99us=27820 p999us=65339 maxus=88014
business=APPLY_MARK_PRICE items=15179 requests=15179 p50us=13074 p90us=22675 p95us=24592 p99us=47939 p999us=64913 maxus=74055
business=PLACE_ORDER_BATCH items=7188480 requests=359424 p50us=19480 p90us=23199 p95us=26099 p99us=44531 p999us=68943 maxus=95223
business=CANCEL_ORDER_BATCH items=2396160 requests=119808 p50us=21037 p90us=25296 p95us=26902 p99us=40304 p999us=61898 maxus=80150
```

profile 结果
```text
measurementStartEpochMillis=1789055601367
measurementEndEpochMillis=1789055691517
mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=844 businessHash=967e6816a230d702
adminActionRetries=79
mixedCapacity=PASS elapsedSeconds=90.149 terminalBusinessOperations=13806848 offeredBusinessOperations=13806848 terminalCoreMessages=1335552 offeredCoreMessages=1335552 businessOpsPerSec=153155.811 coreMessagesPerSec=14814.935 fills=3281920 fillsPerSec=36405.494 queries=0 unfinished=0 peakInFlight=256 measuredCycles=641 totalCycles=844 triggerExecutions=0
business=PLACE_ORDER items=328192 requests=328192 p50us=10182 p90us=25264 p95us=27475 p99us=31047 p999us=58195 maxus=74121
business=CANCEL_ORDER items=328192 requests=328192 p50us=9453 p90us=20004 p95us=22020 p99us=28704 p999us=48037 maxus=73007
business=APPLY_MARK_PRICE items=22784 requests=22784 p50us=15335 p90us=24608 p95us=26738 p99us=33882 p999us=50561 maxus=56819
business=PLACE_ORDER_BATCH items=9845760 requests=492288 p50us=20938 p90us=25657 p95us=28655 p99us=44367 p999us=60784 maxus=81133
business=CANCEL_ORDER_BATCH items=3281920 requests=164096 p50us=23265 p90us=28327 p95us=30097 p99us=44269 p999us=62062 maxus=73007
```

- 无探针JFR：Owner96.681%，matcher26.103/25.910%；encodeResultSource在四Lane分别69/151/201/224个样本，Owner0样本。DataLoss0，Owner同步I/O0。全节点分配44607939056B/13806848≈3230.9B/business op，非零分配。readyLaneMask仍有3230个Owner样本：只是把无效整套推进改为通知探测，未消除busy-spin或全部轮询。CPU降频，不声称吞吐提升。

AnalyzeJfr.txt
```text
totals allocationMiBps=471.896 allocationBytes=44607939056 machineCPU=91.02 jvmCPU=58.40 heapMaxMiB=393.63 dataLoss=0 ownerIOEvents=0
threadCPU	97.607	core-account-lane-3
threadCPU	97.589	core-account-lane-0
threadCPU	97.572	core-account-lane-2
threadCPU	97.567	core-account-lane-1
threadCPU	96.681	trading-owner--1
threadCPU	96.613	/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
threadCPU	77.295	clustered-service-101-0
threadCPU	69.408	driver-conductor
threadCPU	65.340	archive-conductor
threadCPU	63.072	consensus-module-101-0
threadCPU	26.103	core-matcher-1
threadCPU	25.910	core-matcher-0
threadCPU	3.390	JVMCI-native CompilerThread0
threadCPU	1.847	aeron-md-nra
threadCPU	0.362	JFR Periodic Tasks
threadCPU	0.322	C1 CompilerThread0
threadCPU	0.219	JFR Recorder Thread
threadCPU	0.214	aeron-client
threadCPU	0.100	Monitor Deflation Thread
threadCPU	0.100	Service Thread
gc count=149 totalMs=979.634 p99Ms=11.271 maxMs=11.705
samples	32751	core-account-lane-0
samples	32311	core-account-lane-1
samples	31771	core-account-lane-2
samples	31090	core-account-lane-3
samples	28224	trading-owner--1
samples	4441	clustered-service-101-0
samples	2822	driver-conductor
samples	1366	consensus-module-101-0
samples	1359	archive-conductor
samples	455	/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
samples	412	core-matcher-1
samples	410	core-matcher-0
ownerInclusive	28224	com.surprising.aeron.service.execution.ContinuousTradingClusterService$$Lambda.0x000000012f12f708.run
ownerInclusive	28224	java.lang.Thread.run
ownerInclusive	28224	java.lang.Thread.runWith
ownerInclusive	28224	com.surprising.aeron.service.execution.ContinuousTradingClusterService.runOwner
ownerInclusive	27665	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommands
ownerInclusive	27579	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommands
ownerInclusive	27439	com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope
ownerInclusive	18976	com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix
ownerInclusive	14899	com.surprising.aeron.service.execution.OrderedCommitCoordinator.commitReadyMatching
ownerInclusive	10798	com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching
ownerInclusive	7060	com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
ownerInclusive	6442	com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
allocation	1341172568	core-account-lane-2 com.surprising.aeron.service.state.OrderRuntime
allocation	1326107232	core-account-lane-0 com.surprising.aeron.service.state.OrderRuntime
allocation	1312064600	core-account-lane-3 com.surprising.aeron.service.state.OrderRuntime
allocation	1231250592	core-account-lane-1 [J
allocation	1210698696	core-account-lane-0 [J
allocation	1190890992	core-account-lane-1 com.surprising.aeron.service.state.OrderRuntime
allocation	1183930032	core-account-lane-2 [J
allocation	1168537528	clustered-service-101-0 [B
allocation	1150467712	core-matcher-1 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	1109117544	core-matcher-0 com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand
allocation	1106055056	core-account-lane-3 [J
allocation	877453528	trading-owner--1 [B
allocationSite	4909040424	java.util.concurrent.ConcurrentHashMap.putVal
allocationSite	3192990240	java.lang.invoke.VarHandleLongs$Array.setRelease
allocationSite	2477014056	org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap.addKeyValueAtIndex
allocationSite	2045582456	org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow
allocationSite	2007195512	org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap.get
allocationSite	1855797656	com.surprising.aeron.protocol.TradingCommandCodec.decodePlaceOrder
allocationSite	1397892488	java.util.ArrayList.add
allocationSite	1386783976	org.eclipse.collections.impl.set.mutable.primitive.LongHashSet.rehashAndGrow
allocationSite	1313426440	java.util.List.copyOf
allocationSite	1258942976	java.nio.ByteBuffer.allocate
allocationSite	1191462056	com.surprising.aeron.service.state.RuntimeDerivativeFillCalculator$FillCursor.order
allocationSite	1120924640	com.surprising.aeron.service.state.BalanceRuntime.release
parkOrMonitorNs	89362330532	aeron-md-nra
parkOrMonitorNs	60104839165	Common-Cleaner
parkOrMonitorNs	1417165242	consensus-module-101-0
parkOrMonitorNs	1286709801	archive-conductor
parkOrMonitorNs	1068691222	driver-conductor
parkOrMonitorNs	876073507	core-matcher-1
parkOrMonitorNs	827383958	core-matcher-0
parkOrMonitorNs	776348806	/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/media-surprising-linear_perpetual-0 [sender,receiver]
eventCounts	167419	jdk.ExecutionSample
eventCounts	85452	jdk.ThreadPark
eventCounts	84664	jdk.GCPhaseParallel
eventCounts	26086	jdk.ObjectAllocationSample
eventCounts	12561	jdk.PromoteObjectInNewPLAB
eventCounts	5450	jdk.ThreadSleep
eventCounts	4285	jdk.NativeMethodSample
eventCounts	2403	jdk.NativeMemoryUsage
eventCounts	2235	jdk.TenuringDistribution
eventCounts	1382	jdk.ThreadCPULoad
eventCounts	1044	jdk.NativeLibrary
eventCounts	857	jdk.ModuleExport
```

EncodingReport.txt
```text
69	core-account-lane-0 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
151	core-account-lane-1 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
201	core-account-lane-2 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
224	core-account-lane-3 com.surprising.aeron.protocol.TradingOrderBatchCodec.encodeResultSource
7060	trading-owner--1 com.surprising.aeron.service.execution.OrderBatchExecutor.finishOrderBatch
11	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasLocalMatchingWork
3221	trading-owner--1 com.surprising.aeron.service.execution.TradingCoreRuntime.hasMatchingNotifications
1212	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectCancel
2915	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.collectMatcherSettlement
3094	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.hasMatchingNotifications
3230	trading-owner--1 com.surprising.aeron.service.state.TradingRuntimeState.readyLaneMask
```

#### 最终诊断计数校正与边界

- JFR RecordingFile 不保证按事件时间排序，初版读取器误用文件遇到的首尾事件作为累计计数边界，约低估1–2%；现改为测量窗口内累计计数的min/max配对。上文初版计数仅保留审计，以本节为准，无须重跑。1024次采样首尾截断误差仍存在。

改动前诊断
```text
id=0 calls=22624256 success=22624256 successPct=100.000 timingSamples=22095 avgNs=3461.2 successAvgNs=3461.2 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=78.311
id=1 calls=23409664 success=811852 successPct=3.468 timingSamples=22862 avgNs=1944.7 successAvgNs=38301.6 incompleteAvgNs=513.3 sampledInclusiveWallSeconds=45.527
id=2 calls=505856 success=404740 successPct=80.011 timingSamples=495 avgNs=29482.1 successAvgNs=36943.8 incompleteAvgNs=2098.9 sampledInclusiveWallSeconds=14.944
id=3 calls=23729152 success=23729152 successPct=100.000 timingSamples=23174 avgNs=692.0 successAvgNs=692.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=16.420
id=4 calls=505856 success=505856 successPct=100.000 timingSamples=495 avgNs=12010.8 successAvgNs=12010.8 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=6.088
id=5 calls=303104 success=303104 successPct=100.000 timingSamples=297 avgNs=8520.4 successAvgNs=8520.4 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=2.591
id=6 calls=47663104 success=1095153 successPct=2.298 timingSamples=46547 avgNs=118.4 successAvgNs=242.2 incompleteAvgNs=114.5 sampledInclusiveWallSeconds=5.644
id=7 calls=862208 success=810605 successPct=94.015 timingSamples=843 avgNs=3922.1 successAvgNs=4175.1 incompleteAvgNs=150.7 sampledInclusiveWallSeconds=3.386
id=8 calls=826368 success=826368 successPct=100.000 timingSamples=808 avgNs=3451.2 successAvgNs=3451.2 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=2.855
id=9 calls=404480 success=404480 successPct=100.000 timingSamples=396 avgNs=3291.0 successAvgNs=3291.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=1.335
```

最终诊断
```text
id=0 calls=35383296 success=35383296 successPct=100.000 timingSamples=34555 avgNs=2205.2 successAvgNs=2205.2 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=78.028
id=1 calls=36401152 success=1027993 successPct=2.824 timingSamples=35549 avgNs=1264.4 successAvgNs=28073.4 incompleteAvgNs=339.2 sampledInclusiveWallSeconds=46.028
id=2 calls=641024 success=512896 successPct=80.012 timingSamples=627 avgNs=21520.1 successAvgNs=26467.8 incompleteAvgNs=1450.1 sampledInclusiveWallSeconds=13.817
id=3 calls=2593792 success=2593792 successPct=100.000 timingSamples=2534 avgNs=4538.0 successAvgNs=4538.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=11.775
id=4 calls=641024 success=641024 successPct=100.000 timingSamples=627 avgNs=8819.0 successAvgNs=8819.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=5.662
id=5 calls=385024 success=385024 successPct=100.000 timingSamples=377 avgNs=6932.1 successAvgNs=6932.1 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=2.676
id=6 calls=74959872 success=1738154 successPct=2.319 timingSamples=73204 avgNs=101.3 successAvgNs=213.8 incompleteAvgNs=98.4 sampledInclusiveWallSeconds=7.597
id=7 calls=1120256 success=1027261 successPct=91.699 timingSamples=1095 avgNs=3645.0 successAvgNs=3950.3 incompleteAvgNs=152.3 sampledInclusiveWallSeconds=4.087
id=8 calls=1042432 success=1042432 successPct=100.000 timingSamples=1019 avgNs=3063.4 successAvgNs=3063.4 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=3.196
id=9 calls=0 success=0 successPct=0.000 timingSamples=0 avgNs=0.0 successAvgNs=0.0 incompleteAvgNs=0.0 sampledInclusiveWallSeconds=0.000
```

- id映射：0 pollCommands；1 pollCommandPrefix；2 finishOrderBatch；3 pumpMatchingCommitCompletions；4 collectMatcherSettlement；5 collectCancel；6 readyLaneMask；7 prepareClusterPipelineScope；8 RuntimeFactIndexes.applyCurrent；9 encodeResultSource。计数只统计Owner线程；void/int返回的success列不代表业务进展，不应解读。id9没有Owner事件，仅能说明未达到1024次事件阈值，配合无探针JFR的Owner0/Lane645编码样本验证下沉，不能把0事件声称为数学意义零调用。
- 最终prefix36401152次，pump2593792次，pump/prefix=7.126%，达到预锁定小于25%的目标；prefix完成1027993次，约每个完成前缀2.52次pump。readyLaneMask74959872次，非零1738154（2.319%），说明便宜通知探测仍大量存在；Owner96.681%不是有效业务利用率，不能说已全部移除串行工作。
- 长负载日志重放业务哈希967e6816a230d702一致；首次快照工具因SnapshotControl.class已清理导致ClassNotFoundException，已重新编译到本轮临时目录，再执行快照验证。无交易代码异常。重放选举期间记录一次`quorum position went backwards` WARN，随后选主并通过全量业务状态核对；不把WARN隐藏成完全无告警。

#### 2026-09-11 最终功能、恢复与审计

- 服务282项+顺序批量/结算交接/发布表32项+基准验证器9项，共323项测试全部通过。六产品线真实节点 execute→SIGKILL→replay→snapshot→restart 全通过；长负载 replay 与 snapshot 重启 businessHash=967e6816a230d702，snapshotPosition=1760679040。
- 六产品线恢复明细：
```text
SPOT execute productLineGate=PASS mode=execute productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT replay productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
SPOT snapshotPosition=3616
SPOT snapshot productLineGate=PASS mode=verify productLine=SPOT fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL execute productLineGate=PASS mode=execute productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL replay productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_PERPETUAL snapshotPosition=9568
LINEAR_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=LINEAR_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL execute productLineGate=PASS mode=execute productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL replay productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
INVERSE_PERPETUAL snapshotPosition=9568
INVERSE_PERPETUAL snapshot productLineGate=PASS mode=verify productLine=INVERSE_PERPETUAL fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY execute productLineGate=PASS mode=execute productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY replay productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
LINEAR_DELIVERY snapshotPosition=5184
LINEAR_DELIVERY snapshot productLineGate=PASS mode=verify productLine=LINEAR_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY execute productLineGate=PASS mode=execute productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY replay productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
INVERSE_DELIVERY snapshotPosition=5184
INVERSE_DELIVERY snapshot productLineGate=PASS mode=verify productLine=INVERSE_DELIVERY fundsDiff=0 bookLevels=0 seed=9701
OPTION execute productLineGate=PASS mode=execute productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION replay productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
OPTION snapshotPosition=5184
OPTION snapshot productLineGate=PASS mode=verify productLine=OPTION fundsDiff=0 bookLevels=0 seed=9701
```

- 长负载恢复：
```text
lanes replay PASS businessHash=967e6816a230d702
lanes snapshotPosition=1760679040
lanes snapshot PASS businessHash=967e6816a230d702
```

- JVM审计（仅测量窗口）：
```text
jdk.Compilation count=2 totalMs=286.700986 maxMs=153.7986
jdk.Deoptimization count=6 totalMs=0.0 maxMs=0.0
jdk.ExecuteVMOperation count=158 totalMs=986.940762 maxMs=11.740641
jdk.SafepointBegin count=156 totalMs=12.793773 maxMs=0.26177
owner jdk.Deoptimization count=2 totalMs=0.0 maxMs=0.0
afterGC 30sBucket=0 count=48 avgMiB=104.12034130096436 minMiB=101.55311584472656 maxMiB=108.84136962890625
afterGC 30sBucket=1 count=51 avgMiB=105.08250427246094 minMiB=102.39507293701172 maxMiB=109.94570922851562
afterGC 30sBucket=2 count=50 avgMiB=106.0282154083252 minMiB=103.49980926513672 maxMiB=109.98350524902344
```

- 90s after-GC平均占用按30s桶约104.12→105.08→106.03MiB；未做长稳full-GC/live-set回归，不能宣称无泄漏。新增提前编码只改变既有响应byte[]的分配线程与时机，仍按有界命令窗口持有并在批量上下文回收时清引用。
- CPU_Speed_Limit主测58–100、JFR56–100、agent62–100，明显降频；容量性能验收未通过环境有效性要求。未验证云端三节点容量、普通单1ms、完整并发风险/清算/查询/推送；本轮只对Owner改动做功能和归因验证。

NMT before（边界值，不是native峰值）
```text
18268:

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=3146412KB, committed=692136KB
       malloc: 72876KB #95824, peak=71192KB #95826
       mmap:   reserved=3073536KB, committed=619260KB

-                 Java Heap (reserved=1572864KB, committed=526336KB)
                            (mmap: reserved=1572864KB, committed=526336KB, at peak)

-                     Class (reserved=1048910KB, committed=1742KB)
                            (classes #3929)
                            (  instance classes #3549, array classes #380)
                            (malloc=334KB tag=Class #7437) (at peak)
                            (mmap: reserved=1048576KB, committed=1408KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=18624KB)
                            (    used=18404KB)
                            (    waste=220KB =1.18%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1408KB)
                            (    used=1221KB)
                            (    waste=187KB =13.28%)

-                    Thread (reserved=58505KB, committed=1993KB)
                            (threads #48)
                            (stack: reserved=58368KB, committed=1856KB, peak=1856KB)
                            (malloc=91KB tag=Thread #275) (peak=100KB #279)
                            (arena=46KB #78) (peak=748KB #76)

-                      Code (reserved=250334KB, committed=16002KB)
                            (malloc=2645KB tag=Code #15113) (at peak)
                            (mmap: reserved=247688KB, committed=13356KB, at peak)
                            (arena=1KB #1) (peak=134KB #6)

-                        GC (reserved=91619KB, committed=71179KB)
                            (malloc=27539KB tag=GC #4424) (peak=27584KB #4943)
                            (mmap: reserved=64080KB, committed=43640KB, at peak)
                            (arena=0KB #0) (peak=12KB #13)

-                 GCCardSet (reserved=2KB, committed=2KB)
                            (malloc=2KB tag=GCCardSet #9) (peak=2KB #10)

-                  Compiler (reserved=253KB, committed=253KB)
                            (malloc=114KB tag=Compiler #171) (peak=129KB #172)
                            (arena=139KB #19) (peak=7696KB #22)

-                     JVMCI (reserved=54KB, committed=54KB)
                            (malloc=54KB tag=JVMCI #146) (peak=56KB #145)
                            (arena=0KB #0) (peak=67KB #3)

-                  Internal (reserved=1455KB, committed=1455KB)
                            (malloc=1423KB tag=Internal #5096) (at peak)
                            (mmap: reserved=32KB, committed=32KB, at peak)

-                     Other (reserved=9409KB, committed=9409KB)
                            (malloc=9409KB tag=Other #28) (at peak)

-                    Symbol (reserved=4338KB, committed=4338KB)
                            (malloc=3723KB tag=Symbol #42810) (at peak)
                            (arena=616KB #1) (at peak)

-    Native Memory Tracking (reserved=1713KB, committed=1713KB)
                            (malloc=29KB tag=Native Memory Tracking #479) (at peak)
                            (tracking overhead=1684KB)

-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=14000KB, peak=14208KB)

-               Arena Chunk (reserved=7407KB, committed=7407KB)
                            (malloc=7407KB tag=Arena Chunk #357) (peak=8688KB #361)

-                   Tracing (reserved=16596KB, committed=16596KB)
                            (malloc=16596KB tag=Tracing #4307) (at peak)

-                   Logging (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Logging #2) (peak=6KB #4)

-                Statistics (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Statistics #2) (peak=2KB #6)

-                    Module (reserved=291KB, committed=291KB)
                            (malloc=291KB tag=Module #3301) (at peak)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=625KB, committed=625KB)
                            (malloc=625KB tag=Synchronization #11763) (at peak)

-            Serviceability (reserved=17KB, committed=17KB)
                            (malloc=17KB tag=Serviceability #18) (peak=20KB #22)

-                 Metaspace (reserved=65623KB, committed=18711KB)
                            (malloc=87KB tag=Metaspace #55) (at peak)
                            (mmap: reserved=65536KB, committed=18624KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=String Deduplication #8) (at peak)

-           Object Monitors (reserved=3KB, committed=3KB)
                            (malloc=3KB tag=Object Monitors #17) (at peak)

```

NMT after（边界值，不是native峰值）
```text
18268:

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=3173956KB, committed=737636KB
       malloc: 90180KB #182069, peak=90505KB #177044
       mmap:   reserved=3083776KB, committed=647456KB

-                 Java Heap (reserved=1572864KB, committed=524288KB)
                            (mmap: reserved=1572864KB, committed=524288KB, peak=526336KB)

-                     Class (reserved=1049216KB, committed=2624KB)
                            (classes #4936)
                            (  instance classes #4483, array classes #453)
                            (malloc=640KB tag=Class #15348) (at peak)
                            (mmap: reserved=1048576KB, committed=1984KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=25344KB)
                            (    used=24988KB)
                            (    waste=356KB =1.40%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1984KB)
                            (    used=1764KB)
                            (    waste=220KB =11.11%)

-                    Thread (reserved=68774KB, committed=2750KB)
                            (threads #57)
                            (stack: reserved=68608KB, committed=2584KB, peak=2584KB)
                            (malloc=110KB tag=Thread #328) (peak=124KB #342)
                            (arena=55KB #94) (peak=1303KB #94)

-                      Code (reserved=261916KB, committed=49836KB)
                            (malloc=14227KB tag=Code #42469) (at peak)
                            (mmap: reserved=247688KB, committed=35608KB, at peak)
                            (arena=1KB #1) (peak=134KB #6)

-                        GC (reserved=92339KB, committed=71867KB)
                            (malloc=28259KB tag=GC #13125) (peak=28327KB #18715)
                            (mmap: reserved=64080KB, committed=43608KB, peak=43640KB)
                            (arena=0KB #0) (peak=44KB #13)

-                 GCCardSet (reserved=18KB, committed=18KB)
                            (malloc=18KB tag=GCCardSet #104) (peak=18KB #60)

-                  Compiler (reserved=438KB, committed=438KB)
                            (malloc=298KB tag=Compiler #933) (peak=322KB #870)
                            (arena=141KB #21) (peak=15136KB #27)

-                     JVMCI (reserved=140KB, committed=140KB)
                            (malloc=140KB tag=JVMCI #379) (at peak)
                            (arena=0KB #0) (peak=99KB #3)

-                  Internal (reserved=1595KB, committed=1595KB)
                            (malloc=1563KB tag=Internal #9879) (at peak)
                            (mmap: reserved=32KB, committed=32KB, at peak)

-                     Other (reserved=9413KB, committed=9413KB)
                            (malloc=9413KB tag=Other #37) (at peak)

-                    Symbol (reserved=4878KB, committed=4878KB)
                            (malloc=4230KB tag=Symbol #51690) (at peak)
                            (arena=648KB #1) (at peak)

-    Native Memory Tracking (reserved=3258KB, committed=3258KB)
                            (malloc=57KB tag=Native Memory Tracking #997) (at peak)
                            (tracking overhead=3200KB)

-        Shared class space (reserved=16384KB, committed=14000KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=14000KB, peak=14208KB)

-               Arena Chunk (reserved=6313KB, committed=6313KB)
                            (malloc=6313KB tag=Arena Chunk #335) (peak=15985KB #605)

-                   Tracing (reserved=19113KB, committed=19113KB)
                            (malloc=19113KB tag=Tracing #18734) (peak=22745KB #41677)

-                   Logging (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Logging #2) (peak=6KB #4)

-                Statistics (reserved=0KB, committed=0KB)
                            (malloc=0KB tag=Statistics #2) (peak=2KB #6)

-                    Module (reserved=293KB, committed=293KB)
                            (malloc=293KB tag=Module #3345) (at peak)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=1272KB, committed=1272KB)
                            (malloc=1272KB tag=Synchronization #24170) (at peak)

-            Serviceability (reserved=17KB, committed=17KB)
                            (malloc=17KB tag=Serviceability #18) (peak=20KB #22)

-                 Metaspace (reserved=65705KB, committed=25513KB)
                            (malloc=169KB tag=Metaspace #157) (at peak)
                            (mmap: reserved=65536KB, committed=25344KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=String Deduplication #8) (at peak)

-           Object Monitors (reserved=1KB, committed=1KB)
                            (malloc=1KB tag=Object Monitors #3) (peak=24KB #121)

```

JFR summary
```text

 Version: 2.1
 Chunks: 2
 Start: 2026-09-10 15:52:28 (UTC)
 Duration: 161 s

 Event Type                              Count  Size (bytes)
=============================================================
 jdk.ExecutionSample                    287475       3431179
 jdk.ThreadPark                         226958       6545128
 jdk.GCPhaseParallel                    113152       3142586
 jdk.ObjectAllocationSample              35402        572084
 jdk.PromoteObjectInNewPLAB              17821        330841
 jdk.ThreadSleep                          9729        193742
 jdk.NativeMethodSample                   7242         86394
 jdk.NativeMemoryUsage                    4320         65149
 jdk.TenuringDistribution                 3000         35055
 jdk.ThreadCPULoad                        2675         45192
 jdk.NativeLibrary                        2087        190877
 jdk.ModuleExport                         1714         20262
 jdk.SystemProcess                        1560        157458
 jdk.PromoteObjectOutsidePLAB             1283         21601
 jdk.BooleanFlag                           992         30934
 jdk.Checkpoint                            815       2633337
 jdk.GCReferenceStatistics                 804          9127
 jdk.MetaspaceChunkFreeListSummary         804         15556
 jdk.ActiveSetting                         752         19768
 jdk.GCPhasePauseLevel1                    609         25603
 jdk.Deoptimization                        533         12771
 jdk.FileWrite                             409         11022
 jdk.G1HeapSummary                         402         11382
 jdk.GCHeapSummary                         402         16418
 jdk.MetaspaceSummary                      402         20240
 jdk.ModuleRequire                         382          4202
 jdk.LongFlag                              284          9230
 jdk.JavaExceptionThrow                    254          9232
 jdk.ExecuteVMOperation                    227          4133
 jdk.SafepointBegin                        213          3275
 jdk.G1MMU                                 202          2897
 jdk.GCCPUTime                             202          3654
 jdk.GCPhasePause                          202          4925
 jdk.GarbageCollection                     201          4894
 jdk.EvacuationInformation                 200          6250
 jdk.G1AdaptiveIHOP                        200          8664
 jdk.G1BasicIHOP                           200          8137
 jdk.G1EvacuationOldStatistics             200          7074
 jdk.G1EvacuationYoungStatistics           200          8995
 jdk.G1GarbageCollection                   200          2871
 jdk.YoungGarbageCollection                200          2871
 jdk.UnsignedLongFlag                      186          6381
 jdk.ClassLoadingStatistics                160          1746
 jdk.CompilerStatistics                    160          4940
 jdk.ExceptionStatistics                   160          1743
 jdk.JavaThreadStatistics                  160          1906
 jdk.NativeMemoryUsageTotal                160          2866
 jdk.ResidentSetSize                       160          2866
 jdk.CPULoad                               159          3167
 jdk.InitialEnvironmentVariable            120          9860
 jdk.JavaErrorThrow                        118          1801
 jdk.OldObjectSample                       110          3790
 jdk.ThreadAllocationStatistics            107          1225
 jdk.UnsignedIntFlag                       100          3378
 jdk.NetworkUtilization                     93          1417
 jdk.InitialSecurityProperty                88          4388
 jdk.IntFlag                                88          3170
 jdk.Compilation                            85          2521
 jdk.StringFlag                             74          2655
 jdk.CompilerQueueUtilization               64          1330
 jdk.InitialSystemProperty                  58          4026
 jdk.ThreadStart                            55           653
 jdk.ThreadEnd                              42           402
 jdk.DoubleFlag                             34          1409
 jdk.DirectBufferStatistics                 32           734
 jdk.StringTableStatistics                  16           591
 jdk.SymbolTableStatistics                  16           623
 jdk.ThreadContextSwitchRate                15           180
 jdk.ClassLoaderStatistics                  12           353
 jdk.CodeCacheStatistics                    12           357
 jdk.GCHeapMemoryPoolUsage                  12           463
 jdk.GCPhasePauseLevel2                     11           377
 jdk.GCPhaseConcurrent                       6           275
 jdk.GCConfiguration                         4           103
 jdk.GCHeapMemoryUsage                       4            89
 jdk.GCPhaseConcurrentLevel1                 4           148
 jdk.JavaMonitorStatistics                   4            35
 jdk.PhysicalMemory                          4            71
 jdk.SwapSpace                               4            71
 jdk.ActiveRecording                         2           241
 jdk.CPUInformation                          2          3489
 jdk.CPUTimeStampCounter                     2            39
 jdk.CodeCacheConfiguration                  2            85
 jdk.CompilerConfiguration                   2            23
 jdk.DeprecatedInvocation                    2            42
 jdk.GCHeapConfiguration                     2            55
 jdk.GCSurvivorConfiguration                 2            21
 jdk.GCTLABConfiguration                     2            25
 jdk.JVMInformation                          2          3175
 jdk.Metadata                                2        221057
 jdk.MetaspaceGCThreshold                    2            32
 jdk.OSInformation                           2           295
 jdk.ThreadDump                              2         41079
 jdk.VirtualizationInformation               2            71
 jdk.YoungGenerationConfiguration            2            35
 jdk.FileForce                               1           120
 jdk.JavaMonitorEnter                        1            23
 jdk.JavaMonitorWait                         1            31
 jdk.MetaspaceAllocationFailure              1            14
 jdk.NativeLibraryLoad                       1           115
 jdk.OldGarbageCollection                    1            12
 jdk.Shutdown                                1            42
 jdk.AllocationRequiringGC                   0             0
 jdk.BooleanFlagChanged                      0             0
 jdk.CPUTimeSample                           0             0
 jdk.CPUTimeSamplesLost                      0             0
 jdk.ClassDefine                             0             0
 jdk.ClassLoad                               0             0
 jdk.ClassRedefinition                       0             0
 jdk.ClassUnload                             0             0
 jdk.CodeCacheFull                           0             0
 jdk.CompilationFailure                      0             0
 jdk.CompilerInlining                        0             0
 jdk.CompilerPhase                           0             0
 jdk.ConcurrentModeFailure                   0             0
 jdk.ContainerCPUThrottling                  0             0
 jdk.ContainerCPUUsage                       0             0
 jdk.ContainerConfiguration                  0             0
 jdk.ContainerIOUsage                        0             0
 jdk.ContainerMemoryUsage                    0             0
 jdk.ContinuationFreeze                      0             0
 jdk.ContinuationFreezeFast                  0             0
 jdk.ContinuationFreezeSlow                  0             0
 jdk.ContinuationThaw                        0             0
 jdk.ContinuationThawFast                    0             0
 jdk.ContinuationThawSlow                    0             0
 jdk.DataLoss                                0             0
 jdk.Deserialization                         0             0
 jdk.DoubleFlagChanged                       0             0
 jdk.DumpReason                              0             0
 jdk.EvacuationFailed                        0             0
 jdk.FileRead                                0             0
 jdk.FinalizerStatistics                     0             0
 jdk.Flush                                   0             0
 jdk.G1HeapRegionInformation                 0             0
 jdk.G1HeapRegionTypeChange                  0             0
 jdk.GCPhaseConcurrentLevel2                 0             0
 jdk.GCPhasePauseLevel3                      0             0
 jdk.GCPhasePauseLevel4                      0             0
 jdk.HeapDump                                0             0
 jdk.IntFlagChanged                          0             0
 jdk.JITRestart                              0             0
 jdk.JavaAgent                               0             0
 jdk.JavaMonitorDeflate                      0             0
 jdk.JavaMonitorInflate                      0             0
 jdk.JavaMonitorNotify                       0             0
 jdk.LongFlagChanged                         0             0
 jdk.MetaspaceOOM                            0             0
 jdk.MethodTiming                            0             0
 jdk.MethodTrace                             0             0
 jdk.NativeAgent                             0             0
 jdk.NativeLibraryUnload                     0             0
 jdk.ObjectAllocationInNewTLAB               0             0
 jdk.ObjectAllocationOutsideTLAB             0             0
 jdk.ObjectCount                             0             0
 jdk.ObjectCountAfterGC                      0             0
 jdk.PSHeapSummary                           0             0
 jdk.ParallelOldGarbageCollection            0             0
 jdk.ProcessStart                            0             0
 jdk.PromotionFailed                         0             0
 jdk.RedefineClasses                         0             0
 jdk.ReservedStackActivation                 0             0
 jdk.RetransformClasses                      0             0
 jdk.SafepointEnd                            0             0
 jdk.SafepointLatency                        0             0
 jdk.SafepointStateSynchronization           0             0
 jdk.SecurityPropertyModification            0             0
 jdk.SecurityProviderService                 0             0
 jdk.SerializationMisdeclaration             0             0
 jdk.ShenandoahEvacuationInformation         0             0
 jdk.ShenandoahHeapRegionInformation         0             0
 jdk.ShenandoahHeapRegionStateChange         0             0
 jdk.SocketRead                              0             0
 jdk.SocketWrite                             0             0
 jdk.StringFlagChanged                       0             0
 jdk.SyncOnValueBasedClass                   0             0
 jdk.SystemGC                                0             0
 jdk.TLSHandshake                            0             0
 jdk.UnsignedIntFlagChanged                  0             0
 jdk.UnsignedLongFlagChanged                 0             0
 jdk.VirtualThreadEnd                        0             0
 jdk.VirtualThreadPinned                     0             0
 jdk.VirtualThreadStart                      0             0
 jdk.VirtualThreadSubmitFailed               0             0
 jdk.X509Certificate                         0             0
 jdk.X509Validation                          0             0
 jdk.ZAllocationStall                        0             0
 jdk.ZOldGarbageCollection                   0             0
 jdk.ZPageAllocation                         0             0
 jdk.ZRelocationSet                          0             0
 jdk.ZRelocationSetGroup                     0             0
 jdk.ZStatisticsCounter                      0             0
 jdk.ZStatisticsSampler                      0             0
 jdk.ZThreadPhase                            0             0
 jdk.ZUncommit                               0             0
 jdk.ZYoungGarbageCollection                 0             0
```

实际JMH/节点命令（其他场景按预锁定产品、kind及测量时长替换）：
```json
[
  [
    "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseG1GC",
    "-XX:NativeMemoryTracking=summary",
    "-Dsurprising.aeron.hostnames=127.0.0.1",
    "-Dsurprising.aeron.egress-hostname=127.0.0.1",
    "-Dsurprising.aeron.node-id=0",
    "-Dsurprising.aeron.account-lanes=4",
    "-Dsurprising.aeron.matching-engines=2",
    "-Dsurprising.aeron.settlement-spin-limit=0",
    "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
    "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
    "-Dsurprising.aeron.execution-mode=PIPELINED",
    "-Xms512m",
    "-Xmx1536m",
    "-Djava.io.tmpdir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/tmp",
    "-Daeron.dir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/media",
    "-Dsurprising.aeron.data-dir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/data",
    "-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK",
    "-Dsurprising.aeron.service.idle-strategy=YIELDING",
    "-XX:StartFlightRecording=settings=/tmp/owner-followup/owner.jfc,maxsize=192m,dumponexit=true,filename=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/node.jfr",
    "-cp",
    "/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar",
    "com.surprising.aeron.service.cluster.SurprisingClusterNode"
  ],
  [
    "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseG1GC",
    "-XX:NativeMemoryTracking=summary",
    "-Dsurprising.aeron.hostnames=127.0.0.1",
    "-Dsurprising.aeron.egress-hostname=127.0.0.1",
    "-Dsurprising.aeron.node-id=0",
    "-Dsurprising.aeron.account-lanes=4",
    "-Dsurprising.aeron.matching-engines=2",
    "-Dsurprising.aeron.settlement-spin-limit=0",
    "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
    "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
    "-Dsurprising.aeron.execution-mode=PIPELINED",
    "-Xms128m",
    "-Xmx512m",
    "-Djava.io.tmpdir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/tmp",
    "-Daeron.dir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/clientmedia",
    "-Dsurprising.aeron.client.threading-mode=SHARED",
    "-Dsurprising.aeron.capacity-async-in-flight=256",
    "-Dsurprising.aeron.capacity-session-in-flight=256",
    "-Dsurprising.aeron.capacity-warmup-seconds=30",
    "-Dsurprising.aeron.capacity-duration-seconds=90",
    "-Dsurprising.aeron.capacity-seed=131001",
    "-Dsurprising.aeron.mixed-trading-stream=true",
    "-Dsurprising.aeron.mixed-operational=false",
    "-cp",
    "/Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar",
    "org.openjdk.jmh.Main",
    "ClusterOperationalBenchmark.continuousOperations",
    "-f",
    "1",
    "-wi",
    "0",
    "-i",
    "1",
    "-p",
    "controlPageSize=0",
    "-prof",
    "gc",
    "-rf",
    "json",
    "-rff",
    "/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/jmh.json"
  ]
]
```

最终JMH结果（SingleShotTime分数是整轮时长，业务吞吐以上文terminal计数为准；gc profiler度量客户端fork）：
```json
[
    {
        "jmhVersion" : "1.37",
        "benchmark" : "com.surprising.aeron.benchmarks.workload.ClusterOperationalBenchmark.continuousOperations",
        "mode" : "ss",
        "threads" : 1,
        "forks" : 1,
        "jvm" : "/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java",
        "jvmArgs" : [
            "-XX:ThreadPriorityPolicy=1",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+EnableJVMCIProduct",
            "-XX:+EnableJVMCI",
            "-XX:-UnlockExperimentalVMOptions",
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseG1GC",
            "-XX:NativeMemoryTracking=summary",
            "-Dsurprising.aeron.hostnames=127.0.0.1",
            "-Dsurprising.aeron.egress-hostname=127.0.0.1",
            "-Dsurprising.aeron.node-id=0",
            "-Dsurprising.aeron.account-lanes=4",
            "-Dsurprising.aeron.matching-engines=2",
            "-Dsurprising.aeron.settlement-spin-limit=0",
            "-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN",
            "-Dsurprising.aeron.product-line=LINEAR_PERPETUAL",
            "-Dsurprising.aeron.execution-mode=PIPELINED",
            "-Xms128m",
            "-Xmx512m",
            "-Djava.io.tmpdir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/tmp",
            "-Daeron.dir=/tmp/owner-followup/final/lanes-profile-LINEAR_PERPETUAL-0/clientmedia",
            "-Dsurprising.aeron.client.threading-mode=SHARED",
            "-Dsurprising.aeron.capacity-async-in-flight=256",
            "-Dsurprising.aeron.capacity-session-in-flight=256",
            "-Dsurprising.aeron.capacity-warmup-seconds=30",
            "-Dsurprising.aeron.capacity-duration-seconds=90",
            "-Dsurprising.aeron.capacity-seed=131001",
            "-Dsurprising.aeron.mixed-trading-stream=true",
            "-Dsurprising.aeron.mixed-operational=false"
        ],
        "jdkVersion" : "25.0.1",
        "vmName" : "Java HotSpot(TM) 64-Bit Server VM",
        "vmVersion" : "25.0.1+8-LTS-jvmci-b01",
        "warmupIterations" : 0,
        "warmupTime" : "single-shot",
        "warmupBatchSize" : 1,
        "measurementIterations" : 1,
        "measurementTime" : "single-shot",
        "measurementBatchSize" : 1,
        "params" : {
            "controlPageSize" : "0"
        },
        "primaryMetric" : {
            "score" : 90.149471097,
            "scoreError" : "NaN",
            "scoreConfidence" : [
                "NaN",
                "NaN"
            ],
            "scorePercentiles" : {
                "0.0" : 90.149471097,
                "50.0" : 90.149471097,
                "90.0" : 90.149471097,
                "95.0" : 90.149471097,
                "99.0" : 90.149471097,
                "99.9" : 90.149471097,
                "99.99" : 90.149471097,
                "99.999" : 90.149471097,
                "99.9999" : 90.149471097,
                "100.0" : 90.149471097
            },
            "scoreUnit" : "s/op",
            "rawData" : [
                [
                    90.149471097
                ]
            ]
        },
        "secondaryMetrics" : {
            "gc.alloc.rate" : {
                "score" : 135.04114983239677,
                "scoreError" : "NaN",
                "scoreConfidence" : [
                    "NaN",
                    "NaN"
                ],
                "scorePercentiles" : {
                    "0.0" : 135.04114983239677,
                    "50.0" : 135.04114983239677,
                    "90.0" : 135.04114983239677,
                    "95.0" : 135.04114983239677,
                    "99.0" : 135.04114983239677,
                    "99.9" : 135.04114983239677,
                    "99.99" : 135.04114983239677,
                    "99.999" : 135.04114983239677,
                    "99.9999" : 135.04114983239677,
                    "100.0" : 135.04114983239677
                },
                "scoreUnit" : "MB/sec",
                "rawData" : [
                    [
                        135.04114983239677
                    ]
                ]
            },
            "gc.alloc.rate.norm" : {
                "score" : 2.2420121752E10,
                "scoreError" : "NaN",
                "scoreConfidence" : [
                    "NaN",
                    "NaN"
                ],
                "scorePercentiles" : {
                    "0.0" : 2.2420121752E10,
                    "50.0" : 2.2420121752E10,
                    "90.0" : 2.2420121752E10,
                    "95.0" : 2.2420121752E10,
                    "99.0" : 2.2420121752E10,
                    "99.9" : 2.2420121752E10,
                    "99.99" : 2.2420121752E10,
                    "99.999" : 2.2420121752E10,
                    "99.9999" : 2.2420121752E10,
                    "100.0" : 2.2420121752E10
                },
                "scoreUnit" : "B/op",
                "rawData" : [
                    [
                        2.2420121752E10
                    ]
                ]
            },
            "gc.count" : {
                "score" : 288.0,
                "scoreError" : "NaN",
                "scoreConfidence" : [
                    288.0,
                    288.0
                ],
                "scorePercentiles" : {
                    "0.0" : 288.0,
                    "50.0" : 288.0,
                    "90.0" : 288.0,
                    "95.0" : 288.0,
                    "99.0" : 288.0,
                    "99.9" : 288.0,
                    "99.99" : 288.0,
                    "99.999" : 288.0,
                    "99.9999" : 288.0,
                    "100.0" : 288.0
                },
                "scoreUnit" : "counts",
                "rawData" : [
                    [
                        288.0
                    ]
                ]
            },
            "gc.time" : {
                "score" : 312.0,
                "scoreError" : "NaN",
                "scoreConfidence" : [
                    312.0,
                    312.0
                ],
                "scorePercentiles" : {
                    "0.0" : 312.0,
                    "50.0" : 312.0,
                    "90.0" : 312.0,
                    "95.0" : 312.0,
                    "99.0" : 312.0,
                    "99.9" : 312.0,
                    "99.99" : 312.0,
                    "99.999" : 312.0,
                    "99.9999" : 312.0,
                    "100.0" : 312.0
                },
                "scoreUnit" : "ms",
                "rawData" : [
                    [
                        312.0
                    ]
                ]
            }
        }
    }
]



```

校验清单（以下原始产物将在归档摘要后清理，不作为可访问链接）：
- final/lanes-agent-LINEAR_PERPETUAL-0/node.jfr 21691303 bytes SHA256=52d232912f93b45590e15978dab458398d6227515e50cfeb4e80a8e664931245
- final/lanes-control-INVERSE_DELIVERY-0/node.jfr 1431789 bytes SHA256=47c7b41090a3896242492a58440a680dd12f24c508aa3bc48846fa5b4841e975
- final/lanes-control-INVERSE_PERPETUAL-0/node.jfr 1406610 bytes SHA256=b521d6c993e9937045a02e770a1d25074bdf0fc609fce643aa117ecaf380c68b
- final/lanes-control-LINEAR_DELIVERY-0/node.jfr 1464218 bytes SHA256=feafed7278bd14cb51e0821a548fed7480f6a706a2eec93baeb3f6147e0ac742
- final/lanes-control-LINEAR_PERPETUAL-0/node.jfr 1414002 bytes SHA256=0118941eda8033217b1caf292fd26e08e958897de288b15929807cacc69a9178
- final/lanes-control-OPTION-0/node.jfr 1495199 bytes SHA256=5f3f372e914621650ae201cafa0a5379d46c36e47bfa924a9df96d86bbddfe47
- final/lanes-control-SPOT-0/node.jfr 1405237 bytes SHA256=f64ebc15a7cf6e627dd0ca04b0a3534849d905dd2fd9a3a677666f2e7a5ae11a
- final/lanes-profile-LINEAR_PERPETUAL-0/node.jfr 18077377 bytes SHA256=2bdcef9d467325a4a0590aa52f3906a4c6e451cae6ead8b20a40ef7bf8c9b830
- runs/lanes-profile-LINEAR_PERPETUAL-0/node.jfr 4802609 bytes SHA256=54c7e82571ddd139b4336f420cf7a36f7930cbe724013d5c90b19f8afe9234e7
- runs2/lanes-profile-LINEAR_PERPETUAL-0/node.jfr 0 bytes SHA256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
- runs2/lanes-profile-LINEAR_PERPETUAL-0/tmp/2026_09_10_23_32_45_12203/2026_09_10_23_32_45.jfr 951847 bytes SHA256=885ec395a7e4c6785311e166220d67f0fdf26addef303f407950ec0abfe9f357
- runs3/lanes-profile-LINEAR_PERPETUAL-0/node.jfr 19880855 bytes SHA256=dc25ddc5314507342da1fc98819bbf2094d2e8804f3b97d6885c981f367744e3
- 构建JAR保留：surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar SHA256=49054427431f9ace680bd388c37abfa9ef14d14baa150fb7b520199a27e6cee1
- 构建JAR保留：surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar SHA256=2440a9927d654cf910d9e655776bf6e28a780813f48b0fa7053dcbc243cdd538
- 源码 surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/benchmarks/workload/ClusterBatchResponseBenchmark.java SHA256=199b585d68688b33b53ac7aed8e1b84a2d48f2763c09e14b829c0c4eb2cb589a
- 源码 surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/benchmarks/workload/ClusterMixedCapacityMain.java SHA256=9987403b9cf11c8c1e7bc27103c40766eef92e07a3dd8a88e9f85489b5400288
- 源码 surprising-aeron-core/surprising-aeron-benchmarks/src/test/java/com/surprising/aeron/benchmarks/workload/ClusterMixedCapacityTest.java SHA256=a0611c07c65766b26dde4be62016b3f8c9c07df6efc091aac8f75d4db3e9d512
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/execution/OrderBatchExecutor.java SHA256=066d943a68e17b17f2bbcb74539509aecb7a57ab11a3ad25164f18bea76647d0
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/execution/OrderBatchPending.java SHA256=0f97affc013e735032d35c84119087317e3c85dd80c53febeaa9ca51a2f65bfa
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/execution/PendingMatchingRing.java SHA256=01a2ca982dd1a6f0e75ff21a53667ab385bc4fb1b98d2ac8585db4b40622ae3d
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/execution/SurprisingClusteredService.java SHA256=e78a84da1e94d3cbb5254e71b362160bfd144340d4bd4b53a81ecdcb0e532edc
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/execution/TradingCoreRuntime.java SHA256=afbdcefbc7c7247d629559206734b791c5c4e72c6a541f5c354e84f3a0af2bc1
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LaneOrderResultTarget.java SHA256=7f1d99bc6f0001bb7f5017032c0ee6423d76795e6fa68cbce622185818a091f6
- 源码 surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java SHA256=16aa2d1895961426ef6460e2fcfa92b8a1ecd2c6945325ab3156191d8510c14d
- 源码 surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/execution/ClusterCommandPipelineTest.java SHA256=9c5fac881d72615d76cdb00e8630a46becd05c0c20117cb2cbff6a1a749562f5
- 源码 surprising-aeron-core/surprising-aeron-service/src/test/java/com/surprising/aeron/service/execution/OrderBatchSlotReuseTest.java SHA256=8cc64593e4e484732e999b2d538e0f5fcc51de4bec45617eaeaae331bd4a5e35

- 清理审计：所有本轮Java节点/客户端均已停止；删除本轮临时根目录 /tmp/owner-followup（14077939113 bytes逻辑文件大小，含Archive/JFR/诊断agent/日志），删除已确认本轮产生的24个测试报告（401257 bytes）。只保留构建JAR及本文摘要，既有用户未跟踪文件不动。以上临时artifact路径已失效。

## 2026-09-11 Owner 资金缓冲交接及非终态扫描：采集前锁定

- 被测：master 基于 a8f8ff38 的本轮工作区；对照不适用（仅当前 master）。改动是挂起/恢复时交接已汇总资金数组，Lane 原遍历记录终态标志，使 Owner 跳过全部开放订单的终态扫描。守恒校验、提交顺序和终态保留规则不变。
- 环境：本机 Intel i9-9880H 8C16T/16GiB；HotSpot Oracle GraalVM 25.0.1、Maven 3.9.16、G1。每次一个真实 Aeron 成员，网络+Archive+Core；4 Account Lane，2 matcher（诊断档，不作为单 matcher 或云端容量验收），PIPELINED、BUSY_SPIN、SHARED_NETWORK、service YIELDING。
- JVM：node Xms512m/Xmx1536m，client Xms128m/Xmx512m，NMT summary；jdk.internal.misc opens/exports、java.util.zip opens、native-access ALL-UNNAMED。JFR profile 基础，ExecutionSample 2ms、ThreadCPULoad 1s、Park/MonitorEnter 1ms，maxsize192m，退出写盘。gc profiler 仅客户端；分配归因以节点 JFR 为准。
- 六产品 JMH：ClusterBatchResponseBenchmark.lanePreparedResponses，batchSize=1/20 分别全新节点数据；32用户、1连接、1币对，初始资金每用户1000000、持仓0、标记价100，buy80不成交后撤单（各50%），8轮/调用，每轮持续发32下单批量+32撤单批量后核对；配置上限256 in-flight，实际此场景至多64。无独立做市进程。1线程/1fork/1预热/2测量（SingleShotTime），每次调用512或10240 business ops。每产品分别采节点JFR。
- 连续诊断：ClusterMixedCapacityMain 无 profiler 预热30s/测量60s；ClusterOperationalBenchmark.continuousOperations JMH 1线程/1fork/wi0/i1，业务内部预热30s/测量45s，controlPageSize=0、gc profiler及节点JFR。U本位永续，1769用户、256币对、1连接、in-flight256，seed131001、mixed-trading-stream=true、mixed-operational=false，沿用当前固定循环交易组合与内嵌maker订单；初态及动作计数由客户端输出并守恒核对。持续异步闭环、无目标到达率、不修正 coordinated omission；不据此承诺开放到达尾延迟。
- 冷却：每场终态/查询校验后停止节点；不通过等待温控反复重跑。热节流、swap、DataLoss 导致容量数字无效，只保留诊断；不把 busy-spin CPU 当有效工作占比。通过条件：命令无失败/超时、accepted=terminal、unfinished=0、fundsDiff=0，缓冲复用及订单生命周期测试通过，六产品日志重放及快照重启状态一致。没有预设吞吐提升承诺。
- 恢复：六产品 ClusterProductLineGateMain execute→kill→replay→snapshot restart；恢复 gate 用 BLOCKING 以减少功能验证空转，不用于性能数据。当前轮原始目录 /tmp/owner-funds-handoff，分析后清理并记录摘要。不验证云端三节点、HTTP/WS、长期泄漏；最终只能给局部优化和诊断结论。

### 本轮结果（2026-09-11 09:46–09:55 +08:00）

- `JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home`，PATH 优先该 JDK；`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=RuntimeFundsAccumulatorTest,LaneCommandContextRingTest,LaneTerminalSummaryTest,ClusterCommandPipelineTest,CoreOrderedOrderBatchTest,OrderBatchSettlementWaitTest,TerminalStateRetentionTest,TradingStateSnapshotCodecTest,CoreMatchingStateTest,ClusterMixedCapacityTest -Dsurefire.failIfNoSpecifiedTests=false package`：296 tests、0 failure/error/skipped，BUILD SUCCESS。首次新增测试跨包读取 package-private postings 编译失败，改用递归值比较；未放宽生产 API。
- 真实 JMH 命令主体：`java <锁定参数> -cp product-core-benchmarks.jar org.openjdk.jmh.Main ClusterBatchResponseBenchmark.lanePreparedResponses -f 1 -wi 1 -i 2 -p productLine=<产品> -p batchSize=<1或20> -prof gc -rf json -rff <case>/jmh.json`。每个组合全新节点，12/12 PASS；每场含预热终态条目1536/30720，均无未完成、fundsDiff=0、活动订单和冻结清零。下表单位为整次512/10240操作调用，非单订单延迟；仅2测量样本，无有效置信区间。

| 产品/批大小 | ms/调用 | 客户端分配MiB/s | B/调用 | 客户端GC次/ms |
|---|---:|---:|---:|---:|
| SPOT/1 |147.784|7.881|2417676|0/0|
| SPOT/20 |190.796|44.509|13757196|1/4|
| LINEAR_PERPETUAL/1 |145.649|14.385|4022944|0/0|
| LINEAR_PERPETUAL/20 |247.445|36.036|13777216|1/5|
| INVERSE_PERPETUAL/1 |106.816|9.900|2202500|0/0|
| INVERSE_PERPETUAL/20 |199.615|41.925|13691916|1/5|
| LINEAR_DELIVERY/1 |134.504|8.479|2364940|0/0|
| LINEAR_DELIVERY/20 |219.976|43.359|15525500|1/6|
| INVERSE_DELIVERY/1 |110.473|10.708|2390504|0/0|
| INVERSE_DELIVERY/20 |256.919|34.265|13713968|1/5|
| OPTION/1 |111.886|9.618|2283120|0/0|
| OPTION/20 |266.715|33.055|13663024|1/5|

- 无 profiler 连续60.113s：offered=terminal business ops **10100480**、Core messages **975616**；**168023.753 business ops/s、16229.591 Core messages/s、39945.832 fills/s**，fills2401280。peakInFlight256、期末unfinished0、fundsDiff0；620总循环、469测量循环、businessHash=4e3ab487f3e718a。业务计数：普通下单240128、普通撤单240128、标记价15104、批量下单7203840 items/360192 requests、批量撤单2401280 items/120064 requests（batch均20）。持续窗内查询/触发执行0，初始化强平/保险/ADL检查PASS。CPU_Speed_Limit **58–85**，容量数据无效，只保留诊断。
- 上述入口→终态延迟us（p50/p90/p95/p99/p99.9/max）：PLACE 9003/22544/24674/33390/81592/111345；CANCEL 8335/17711/19660/28704/56623/77594；MARK 13008/22134/25919/48398/84213/84869；PLACE_BATCH 18956/24051/27836/49020/76808/110690；CANCEL_BATCH 20496/25722/28688/45907/99614/111869。样本数为前述各类requests；未拆分accepted阶段，未修正coordinated omission，不作尾延迟验收。
- JMH连续命令主体：`java <锁定参数> -cp product-core-benchmarks.jar org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -f 1 -wi 0 -i 1 -p controlPageSize=0 -prof gc -rf json -rff <case>/jmh.json`。45.100207112s/调用；offered=terminal business ops7688448、Core messages742656；170476.601 business ops/s、16466.974 messages/s、40528.849 fills/s（1827840 fills），peak256、unfinished0、fundsDiff0，businessHash9742c90bc80f9780。客户端gc profiler：125.003MiB/s、14755490208B/调用、190GC/216ms；这是整次含内部预热的客户端分配，不能作Core分配。CPU_Speed_Limit **60–75**，亦不作容量或版本提升结论。
- 节点JFR：`jfr configure --input profile.jfc --output owner.jfc jdk.ExecutionSample#period=2ms jdk.ThreadCPULoad#period=1s jdk.ThreadPark#threshold=1ms jdk.JavaMonitorEnter#threshold=1ms`；连续场采集116s，分析仅measurementStart/End时间窗45.1s。DataLoss0；machineCPU90.53%、JVM58.29%（整机口径）；单核口径Owner97.304%、matcher25.949/25.898%、4Lane97.462–97.482%。Lane含大量忙轮询；不能当成同等业务饱和。
- Owner14371 execution samples；inclusive（不可相加）：finishOrderBatch3697（25.73%）、collectMatcherSettlement1527（10.63%）、collectCancel674（4.69%）、readyLaneMask1546（10.76%）、commitTerminalToOwner1075（7.48%）、RuntimeFactIndexes.applyCurrent886（6.17%）。剩余串行批量完成、Lane结果收集和全局索引发布仍明显；此次只去除两次资金重合并和全开放订单终态遍历，未证明整体加速，CPU仍高。
- 分配：节点ObjectAllocationSample加权24502277696B，518.143MiB/s、3186.9B/business op；不是精确对象计数。热点含matcher NativeCommand、Lane OrderRuntime/long[]、Owner byte[]；site含ConcurrentHashMap.putVal、LongObjectHashMap.addKeyValueAtIndex、批量decodeCommand。NewTLAB/OutsideTLAB未启用，不能把0事件说成零分配；最大对象与对象数/op未测。
- GC/heap：83次youngGC，总507.753ms（约1.13%），最大及p99 pause12.434ms；无fullGC或evacuation failure事件；heapUsed峰386.66MiB，按30s桶GC后均值98.856→98.850MiB。短窗不能证明无泄漏。NMT reserved3144359→3148241KB、committed692095→726701KB；heap committed526336→524288KB、Code15991→48151KB、GC71181→71835KB、Thread1955→2555KB、Class1742→2620KB。DirectBuffer采样峰/末9575136B、8个；未测所有mapped/native pool峰值、FD和长期存活趋势。
- Owner同步File/Socket I/O事件0、MonitorEnter0；2个ThreadPark合计5.946ms，栈均为ContinuousTradingClusterService.runOwner→BackoffIdleStrategy.idle→parkNanos（要求100us，实际4.15/1.80ms），不是业务Future/锁等待。全JVM41725 parks、2723 sleeps不可归到Owner。85 SafepointBegin累计到达9.554ms；87 VM操作累计511.131ms主要随GC；2 compilation累计360.131ms/max188.757ms，13 deoptimization。JFR异常逐次事件未启用，不能报告精确异常数；业务结果未见失败/超时。
- 六产品JFR为各自短批量窗口，覆盖受影响业务执行，但不足以构成逐产品容量/长稳证明。未作新旧版本对比、长稳泄漏验收、云三成员、HTTP/WS及持续风险重业务压测。结论为正确性回归和局部诊断，非吞吐达标。
- 主节点JAR SHA256=3acee16fa59061c7c051bc421cd86479fe3e9711a49861c22f18d8fd33b24936；连续节点JFR13562089B/SHA256=ad5c9576f9cb5c0191ddd499731340f668056e81a56773aea845233c79e59b80。原始路径均在/tmp/owner-funds-handoff（runs各场commands.json、client/node.log、JMH JSON、node.jfr、NMT，根部AnalyzeJfr/PartitionJfr/Details汇总）；清理后路径失效。
- 最终恢复：6/6产品各execute、kill后replay、snapshot restart全部PASS，18次fundsDiff=0；snapshot position：SPOT3616，U/币永续9568，U/币交割及OPTION5184。benchmark JAR SHA256=bd552f077a7ba6a03854c5f27a8da52644b24fd7eb3979267c7648e604811c64。无需改动协议或快照格式。
- 清理完成：本轮节点/客户端已全部停止；删除/tmp/owner-funds-handoff约10.17GB逻辑文件（含Archive、JFR、日志、临时分析程序）及20个本轮测试报告356299B。以上临时路径已失效；保留构建JAR及本记录，用户未跟踪文件未改动。

## 2026-09-11 Owner 行级定位：采集前锁定

- 仅诊断 master a06fe123，无生产代码修改/旧版本对照。复用已验证且SHA256匹配的JAR；HotSpot GraalVM25.0.1/Maven3.9.16，本机i9-9880H8C16T/16GiB。一次一个真实Cluster成员，网络/Archive/Core保留；PIPELINED、4Lane BUSY_SPIN、2matcher诊断、SHARED_NETWORK、service YIELDING，node512m/1536m、client128m/512m、G1/NMT summary，继承上一记录JVM opens/native参数。
- 仅U本位连续混合交易：ClusterOperationalBenchmark.continuousOperations、controlPageSize0、1thread/1fork/wi0/i1，内部预热30s/测量45s；seed131001，1769用户、256币对，1command session+1reserved query session、in-flight256、batch20、mixed-trading-stream=true/mixed-operational=false；初始1768000000125资金、持仓初态与固定交易循环沿用上一轮，内嵌maker流。持续闭环不限制到达率、不修正coordinated omission，收尾校验后停机，无额外冷却重跑。
- JFR profile，2ms execution samples、stackdepth128、CPU1s、Park/Monitor1ms、maxsize192m；gc profiler。按Owner栈的最深业务阶段互斥分组，并统计热点方法的子调用/行号及self samples；采样次数不冒充方法调用次数，inclusive不相加。无instrumentation，避免逐方法计时扰动。通过条件：样本完整/DataLoss0、资金差额0、offered=terminal/unfinished0；热节流仅保留归因诊断，无吞吐提升/容量承诺。
- 临时产物 /tmp/owner-line-diagnosis，分析后清理。无需重跑未修改代码的六产品恢复；引用上一轮同源JAR恢复证据。未验证长期泄漏、HTTP/WS、云端三节点或其他产品线新容量。

### 行级诊断结果

- 当前JAR业务逻辑未变。执行命令由/tmp/owner-line-diagnosis/run.py按上条锁定参数启动SurprisingClusterNode及`org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations -f 1 -wi 0 -i 1 -p controlPageSize=0 -prof gc -rf json -rff <case>/jmh.json`。内部measurementStart/End=1789092107008/1789092152128；45.121s，offered=terminal business ops7537916、Core messages728316，167058.345 business ops/s、16141.234 messages/s、1792000 fills/39715.029 fills/s，peak256、unfinished0、fundsDiff0、businessHash=d06921a997cdbb85。CPU_Speed_Limit60–100，不作容量/版本对比结论。
- 14369个Owner执行样本，互斥阶段分类：collection2139/14.89%，publication1361/9.47%，batchFinishOther886/6.17%，settlementDispatch1696/11.80%，admissionDispatch1832/12.75%，readyNotification1590/11.07%，commitPumpOther1610/11.20%，idle62/0.43%，otherOwner3193/22.22%。其他包含命令作用域准备、调度循环、窗口/响应处理，不应误当成空闲。分类按栈中collection→publication→finish→dispatch→admission→notification→pump→idle优先级；sample不是调用次数或精确耗时。
- `finishOrderBatch` inclusive3726（25.93%）：collectMatcherSettlement1386、collectCancel458、completeCommitPublicationBatch1001，三者2845即其76.36%；去掉嵌套阶段后剩886（6.17%，分类因嵌套派发顺序与简单相减有差别）。所以不能再把“批量收尾26%”与“收集15%/发布9%”相加。
- 明确热点及源码：`TradingRuntimeState.readyLaneMask:1773–1777`1543/10.74%（self1183、hasPending360；后者338落在LaneSequenceQueue:45读取producerSequence附近）。这是预期Lane通知检查，不是撮合算术；没有硬件计数器证据，不能断言false sharing/cache miss。SurprisingClusteredService:231窗口满检查另有639self样本、:239依赖头检查96；可证明轮询/调度成本，不能仅凭采样证明所有通知检查都无效。
- 结果收集的主要项：TerminalStateRetention.accept425+completeSequence267=692/4.82%，具体是TerminalTombstoneStore.indexEntity/unlinkEntity/unlinkClient以及客户号UTF8长度校验；RuntimeIdentityRegistry.releaseClientKey490/3.41%，主要:256 ConcurrentHashMap.get及:258引用计数到0后的remove。collectMatcherSettlement1515中，发布终态666、客户号释放351、tombstone裁剪168；collectCancel624中对应265/170/99。去重记录有真实语义，不能直接删除FIFO/重用客户号防护。
- RuntimeFactIndexes.applyCurrent868/6.04%，其中ActiveOrderIndex.applySnapshot578/4.02%：add281、remove183、ordersById.get106。Owner仍维护按用户/币对活动订单及参与者索引；后者被准入调用，不能把整套索引无序异步化。查询视图和准入所需参与者状态可以作为后续拆分方向。
- 准入作用域prepareClusterPipelineScope988/6.88%，其中批量decoded416、addPlaceScope252等；TradingOrderBatchCodec.decodeCommand437/3.04%包含在此类前置路径内。已核对decodeOrderBatch复用DecodedMatchingCommand，**没有重复反序列化**；其方法名不能作为重复解码证据。
- MatcherSettlementPlan.build578/4.02%：逐fill核对maker/taker身份、剩余数量、参与用户/Lane、持仓身份准备，Owner在发往Lane之前仍有这段串行工作；验证具有正确性意义，不可直接删除。批量收尾中releaseOrderBatchPending314+releaseMatcherSettlement100=414/2.88%，具体OrderBatchPending:291逐item.clear、MatcherSettlementEvent.BatchStorage:62遍历plan清引用；只占小部分，不能当唯一主因。
- 排除错误主因：RuntimeFundsAccumulator.add42/0.29%，requireConserved15/0.10%；MatcherSettlementChanges.appendFundsDelta49/0.34%。资金汇总/守恒不是本样本主瓶颈，保留校验。publishLaneReceipt57/0.40%也很小；该收据可见性切换不是主要成本。上述inclusive小项不可相加推算总量。
- JFR Owner单核口径94.994%，matcher24.545/24.671%，Lane97.130–97.173%（含busy-spin）；machine91.36%、JVM54.54%。Owner观察到同步File/Socket I/O0，DataLoss0；不能由高CPU推断全部为有效计算。GC81次、490.703ms、p99/max11.202ms；heapUsed峰389.17MiB。节点加权分配24216314864B、511.846MiB/s，热点含ConcurrentHashMap.putVal、bindMatcherEvidence、primitive map增长和decodeCommand；不是精确对象数。NMT reserved3148526→3155765KB、committed692234→734813KB。短采样未覆盖长期泄漏、全native pool峰值及精确阻塞时间，保持部分诊断结论。
- JMH45.122036203s/调用，单测量无有效CI；客户端gc profiler133.0318489MiB/s、15580887256B/整次调用（含内部预热）、201GC/218ms，不能当Core每单分配。节点JFR13204331B/SHA256=50595bd211c025af129ac9e436f83d16864b2953e2c23e7dfeb87d6d141ea840；行级报告Lines.txt103358B/SHA256=49b48976bfbcf6e10274ad8dd2bb85203bf6698de99110ed1f34bd8cf544a104。代码/业务不变，仅新增记录。
- 结论：瓶颈分布在Owner的通知扫描、终态/客户号维护、准入与结算计划准备、全局索引维护；没有定位到一个占绝大多数的资金计算或同步阻塞。优先减少重复通知探测，其次压缩逐终态索引和客户号释放，再拆分查询视图与准入参与者状态；收益需要后续独立改动验证，不能把样本占比当吞吐提升幅度。
- 本轮节点/客户端已停止；清理/tmp/owner-line-diagnosis全部1410154204B产物，上述原始路径已失效。未新增Maven测试报告；用户文件、既有构建JAR及README未改动。

## 2026-09-11 通知与终态交接优化：采集前锁定

- 被测master基于b5e58def工作区，仅当前版本，无旧版本对照。修改：Owner正向就绪缓存及合并两类通知遍历；Lane交接既有客户号实体，Owner不再get；终态已确认缺失后不重复查实体桶，客户号插入复用桶哈希。全局索引、资金校验和提交边界不变。退休缓冲由两套long数组替换为实体引用数组，clear释放引用；字典实体增加原始key字段8B，无逐退休项新对象。
- 环境/参数沿用上一功能轮：HotSpot GraalVM25.0.1/Maven3.9.16，i9-9880H8C16T/16GiB；每次仅一个真实Aeron成员（网络/Archive/Core），4Lane BUSY_SPIN、2matcher诊断、PIPELINED、SHARED_NETWORK、service YIELDING；node512m/1536m、client128m/512m、G1/NMT summary，opens/native参数同前。JFR profile、ExecutionSample2ms、stackdepth128、CPU1s、Park/Monitor1ms、maxsize192m。
- 六产品ClusterBatchResponseBenchmark.lanePreparedResponses分别batchSize1/20、全新节点数据，客户号改为多字节；1thread/1fork/wi1/i2 SingleShotTime、gc profiler，每次512/10240业务操作；32用户、1币对、mark100/buy80后撤单、初始每用户1000000、持仓0，配置in-flight256（此回归实际最多64），无独立做市。核对回复客户号、终态、冻结释放和资金。
- U本位连续流：ClusterMixedCapacityMain预热30s/测量60s无profiler；ClusterOperationalBenchmark.continuousOperations预热30s/测量45s、wi0/i1/f1/thread1、controlPageSize0/gc profiler及节点JFR。1769用户、256币对、1command及1reserved-query连接、in-flight256、batch20、seed131001、trading-stream=true/operational=false，初态与固定循环同前（初始资金1768000000125），内嵌maker，持续闭环、无恒定到达率、不修正coordinated omission。结束校验后停机，不等待温控反复重跑。
- 通过条件：offered=terminal、unfinished0、资金差额0；身份引用计数、恢复/旧引用不能删除新实体、缓存队列后到通知、终态FIFO测试通过。六产品execute→kill→replay→snapshot restart（恢复功能档BLOCKING/256m-768m，不用于性能）。热节流/swap/DataLoss不作容量结论；无预设提升幅度，按当前JFR定位剩余热点。临时/tmp/owner-notify-retire分析后清理；不测云端三节点、HTTP/WS、长稳泄漏，结果为局部正确性和性能诊断。

### 第一轮结果及继续调整原因

- 326测试通过；六产品batch1/20共12场JMH、六产品execute/replay/snapshot共18阶段PASS。无profiler60.028s：10186496业务操作/983808Core消息、169697.126ops/s、16389.285messages/s、40344.169fills/s；未完成0、资金差额0。JFR45.035s：7365637操作/711685消息、163554.168ops/s，资金差额0、hash544f30d68aecbf7f。当前主节点JAR仅用于此轮，不与最终JAR混淆。
- 关键反证：13592 Owner样本，readyLaneMask只有41，但新的TradingRuntimeState.hasMatchingNotifications有1429（10.51%），说明空探测成本主要迁移了方法名，没有被解决；不据此宣称扫描消除。客户号准备189样本在Lane，Owner releasePreparedClientKey330样本；旧releaseClientKey不在Owner样本中。Owner97.727%、matcher25.954/26.104%、Lane97.758–97.799%；最终方案继续调整通知实现，保留客户号/终态改动。
- JFR DataLoss0/Owner同步IO0；节点加权分配23630530632B/500.418MiB/s，79GC/458.345ms/max6.512ms、heapUsed峰389.47MiB；15s桶GC后100.994→101.109→101.155MiB，不作泄漏证明。JMH45.035308105s/调用，客户端108.355549MiB/s、12855081064B/含预热整调用、166GC/210ms。profile CPU_Speed_Limit56–70，容量无效。第一轮完整指标/参数按末尾归档汇总，原始产物最后统一清理。

### 第二轮最终实现：采集前锁定

- 当前工作区继续优化，不运行旧版本对照。用两个AtomicLong就绪字替换Owner探测缓存；Lane先写原SPSC序号队列再原子置位，Owner非空时先清位后消费，只过滤被通知队列。空闲探测仅两个就绪字读取，不遍历预期Lane。存在一次发布/清位竞态时，队列是权威，允许一次滞后空通知，不允许丢序号。新增两Lane并发发布/Owner消费8000条通知测试；共享置位可能增加Lane争用，必须观察JFR而不预设收益。
- 环境、node/client JVM、六产品、in-flight256、2matcher/4Lane、JFR配置及资金/恢复门槛同本节第一轮；本轮六产品JMH只测batch20（覆盖每Lane批量终态释放），wi1/i2/f1/thread1，每调用10240操作，客户号多字节；单条/混合通知由单测和连续流覆盖。连续无profiler仍30s预热/60s测量，JMH内部30s/45s；同seed/用户/币对/资金/到达模型。六产品恢复重跑最终JAR。输出/tmp/owner-notify-retire-v2；热节流仍仅作诊断，不承诺吞吐/CPU占用下降，结束清理两轮产物。

### 最终验证与热点结论

- 最终构建：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=MatchingNotificationProbeTest,RuntimeIdentityRegistryTest,TerminalTombstoneStoreTest,TerminalStateRetentionTest,ClusterCommandPipelineTest,SurprisingClusteredServiceTest,CoreOrderedOrderBatchTest,CoreMatchingStateTest,OrderBatchSettlementWaitTest,TradingStateSnapshotCodecTest,ClusterMixedCapacityTest -Dsurefire.failIfNoSpecifiedTests=false package`，HotSpot25、327 tests/0 failure/error/skipped，BUILD SUCCESS；两Lane并发发布/清位/消费8000序号通过。代码复核：先入原SPSC队列再原子置位；先清通知位再消费；后到通知保持下一轮可见，重复滞后位最多一次空收集；未改资金和业务提交顺序。
- 六产品最终batch20 JMH全PASS；六产品execute→kill→replay→snapshot restart共18阶段PASS/fundsDiff0。snapshot position：SPOT3616、U/币永续9568、U/币交割/OPTION5184。身份引用在Owner提交时减少，清理数组不保留对象；旧退休引用不能删除重建同键实体、快照重建键、终态FIFO/碰撞/覆盖/淘汰均通过。
- 无profiler60.102s：10164992 offered=terminal业务操作、981760 offered=terminal Core消息，169127.921 business ops/s、16334.792 messages/s、40208.718 fills/s（2416640fills）。peak256/unfinished0/fundsDiff0，hash2baefb60de422680，634总循环/472测量循环，窗内query/trigger0。JMH/JFR45.106s：6441055业务操作/623711Core消息，142799.239 ops/s、13827.774 messages/s、33939.859 fills/s；unfinished0/fundsDiff0、hashcc24b179424e1866。两者均有热节流，不作吞吐提升或容量结论，不能比较不同profiler/温控状态。
- 无profiler入口→终态延迟us（p50/p90/p95/p99/p99.9/max）：PLACE 9314/23347/25362/29982/58130/69271（241664样本）；CANCEL 8413/17661/19513/24608/44335/57507（241664）；MARK 15106/21872/24674/39976/53510/66519（15104）；PLACE_BATCH 19103/22953/25395/40108/56295/63275（362496批/7249920items）；CANCEL_BATCH 20987/25919/28000/42270/64159/69009（120832批/2416640items）。batch均20，无accepted阶段拆分或coordinated omission修正，未作尾延迟验收。
- 最终Owner13911执行样本：readyLaneMask20/0.14%、TradingRuntimeState.hasMatchingNotifications15/0.11%；不再遍历所有空队列。**0.25%只指这两个方法，不代表全部通知成本归零**：takeReadyLaneMask273/1.96%（含原子领取和候选队列过滤）、全Owner AtomicLong.getAndAccumulate253/1.82%（与领取存在重叠）、TradingCoreRuntime.hasMatchingNotifications379/2.72%（含matcher/健康检查）。Lane62863样本中原子置位getAndAccumulate17，未形成主要采样热点；没有CAS重试/硬件计数器，不能宣称无竞争。
- 客户号prepareClientRelease184样本在Lane，Owner releasePreparedClientKey381/2.74%，仍含引用计数和条件remove；没有把释放提前到Lane。Owner仍96.800%，matcher26.389/26.418%，4Lane97.193–97.287%含busy-spin；剩余inclusive热点finishOrderBatch3305、pumpMatchingCommitCompletions3199、collectMatcherSettlement1287、OwnerCommitPublisher1272、RuntimeFactIndexes796。不同嵌套层不可相加，不声称Owner瓶颈彻底消失。全局参与者索引还影响准入，本轮没有异步化。
- 节点JFR117s，分析只45.106s测量窗；DataLoss0、观察到Owner同步File/Socket IO0；machineCPU92.15%、JVM57.20%。加权分配20682877104B/437.297MiB/s，GC70次455.708ms/max及p9913.738ms，heapUsed峰393.06MiB；15s桶GC后104.709→105.403→105.272MiB，末桶不足1s为103.859MiB，不证明无泄漏。NMT reserved3142425→3162158KB、committed692197→740818KB；无全native pool峰值/长稳证明。新增每Runtime两个AtomicLong对象，非逐命令分配；生产未增加测试计数器或代理。
- 两轮采集记录如下补充；历史中间方案仅作本次诊断审计，不作为最终源码或性能对照。置信区间、精确对象数/op、TLAB大小、全native峰值、长期泄漏以及云端/HTTP/WS均未验收，保持局部正确性与性能诊断结论。

|轮次/产品/批大小|JMH ms/调用|客户端MiB/s|B/调用|GC次/ms|温控Speed范围|
|---|---:|---:|---:|---:|---|
|中间/INVERSE_DELIVERY-1|123.367|9.550|2425064|0/0|64–64|
|中间/INVERSE_DELIVERY-20|334.741|30.404|15157964|1/6|64–64|
|中间/INVERSE_PERPETUAL-1|137.146|8.965|2500968|0/0|91–100|
|中间/INVERSE_PERPETUAL-20|201.802|46.696|15480224|1/5|79–79|
|中间/LINEAR_DELIVERY-1|123.527|9.391|2485956|0/0|52–66|
|中间/LINEAR_DELIVERY-20|239.753|39.584|15195208|1/5|66–66|
|中间/LINEAR_PERPETUAL-1|111.564|11.042|2486324|0/0|100–100|
|中间/LINEAR_PERPETUAL-20|203.849|47.558|15442500|1/5|100–100|
|中间/OPTION-1|151.286|14.254|3965316|0/0|62–62|
|中间/OPTION-20|325.827|30.938|15491264|1/6|62–62|
|中间/SPOT-1|119.627|9.707|2438444|0/0|100–100|
|中间/SPOT-20|211.074|44.350|15217812|1/4|100–100|

- 中间main CPU_Speed_Limit=54–70；service JAR SHA256=39bc7bcdc5ff3af579d53f3bb8263bfe5e1e388c9c60319cc54f5589a2396621

- 中间profile CPU_Speed_Limit=56–70；service JAR SHA256=39bc7bcdc5ff3af579d53f3bb8263bfe5e1e388c9c60319cc54f5589a2396621
- 中间 JFR=12974801B，SHA256=623582738f46fd38d322992355fa479a83c8d75421fe0e85b60a8539c1f6cd3a。
- 中间连续JMH=45.035308105s/调用；客户端gc profiler={'gc.alloc.rate': 108.35554891666027, 'gc.alloc.rate.norm': 12855081064.0, 'gc.count': 166.0, 'gc.time': 210.0}

|轮次/产品/批大小|JMH ms/调用|客户端MiB/s|B/调用|GC次/ms|温控Speed范围|
|---|---:|---:|---:|---:|---|
|最终/INVERSE_DELIVERY-20|206.687|50.247|16916072|1/5|70–89|
|最终/INVERSE_PERPETUAL-20|243.711|45.455|17384916|1/5|100–100|
|最终/LINEAR_DELIVERY-20|316.637|36.214|17281812|1/7|64–89|
|最终/LINEAR_PERPETUAL-20|253.730|37.485|15061780|1/5|100–100|
|最终/OPTION-20|239.951|43.943|16849212|1/5|47–97|
|最终/SPOT-20|228.228|42.455|15185008|1/5|100–100|

- 最终main CPU_Speed_Limit=43–100；service JAR SHA256=36749e6f0e4d0c6adb1e27ca2159b7310b62a07953aa3aefb2dfbe0a5e72e122

- 最终profile CPU_Speed_Limit=50–68；service JAR SHA256=36749e6f0e4d0c6adb1e27ca2159b7310b62a07953aa3aefb2dfbe0a5e72e122
- 最终 JFR=12991125B，SHA256=27ef5cbff9e600b5bbbccad2bc3fe475e3f7b8a2dbf0ff4406761ecff6e7b4f0。
- 最终连续JMH=45.106234396s/调用；客户端gc profiler={'gc.alloc.rate': 92.5864539563557, 'gc.alloc.rate.norm': 10986576160.0, 'gc.count': 142.0, 'gc.time': 214.0}

- 最终benchmark JAR SHA256=6e32067b7f8c305ebbedf10cff7ae98cb6bf43005eeb8257d14ba6cf600ebfef
- 清理完成：两轮节点/客户端已停止；已删除/tmp/owner-notify-retire及/tmp/owner-notify-retire-v2中的Archive、JFR、日志、诊断程序和22个本轮测试报告。以上原始路径失效；保留构建JAR和本文摘要，README及用户文件未改动。

## 2026-09-11 恢复满速后的持续压测：采集前锁定

- 仅测当前master e5619569，无代码改动/旧版本对照。HotSpot GraalVM25.0.1/Maven3.9.16，i9-9880H8C16T/16GiB，开始CPU_Speed_Limit100、可用磁盘497GiB。复用上一轮已验证JAR，执行前核对service SHA256=36749e6f0e4d0c6adb1e27ca2159b7310b62a07953aa3aefb2dfbe0a5e72e122、benchmark SHA256=6e32067b7f8c305ebbedf10cff7ae98cb6bf43005eeb8257d14ba6cf600ebfef。
- 单个真实Aeron Cluster成员，网络/Archive/Core，U本位永续；PIPELINED，4Lane BUSY_SPIN、2matcher诊断（非单matcher/三节点容量验收）、SHARED_NETWORK、service YIELDING。node Xms512m/Xmx1536m，client128m/512m，G1/NMT summary，opens/native参数沿用前述命令。
- ClusterMixedCapacityMain连续异步闭环，warmup30s/measurement60s，seed131001、in-flight256（全局及session）、1command+1reserved-query连接、1769用户、256币对、batch20、trading-stream=true/operational=false；固定混合交易循环和内嵌maker，初始资金1768000000125、持仓及初始化强平/保险/ADL核对同前。无独立做市进程/恒定到达率/coordinated omission修正，测量窗不含持续风控、WS或查询负载。
- 本轮无profiler，不重跑同代码JMH/JFR/六产品恢复（引用前轮同JAR证据）；不能据此报告当前OwnerCPU/分配热点。通过条件offered=terminal、unfinished0、fundsDiff0、余额/持仓/冻结/订单核对通过。每2s采温控，出现降频保留实际受限吞吐，不称为算力上限或改善；不为了降温反复重跑。收尾校验后立即停止节点并清理/tmp/owner-fullspeed-check，记录尾延迟和环境限制。

### 实测结果

- 命令：`java <上述JVM及-D参数> -cp surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar com.surprising.aeron.benchmarks.workload.ClusterMixedCapacityMain`，独立SurprisingClusterNode，执行参数记录于本轮临时commands.json。60.079s测量，10831706 offered=terminal business ops、1045338 offered=terminal Core messages；**180292.177 business ops/s、17399.500 messages/s、42866.494 fills/s**（2575360fills）。peakInFlight256/unfinished0/fundsDiff0；population/hftPositions/reservations/loss均PASS，777总循环/503测量循环、hash734ad4d461f283ec。窗内queries0/triggerExecutions0。
- 两个10s区间约202096/202103 business ops/s，非持续上限；CPU_Speed_Limit62–100，正式测量开始已降频，不能称满速容量验收、也不与历史版本作提升比较。
- 入口→终态延迟us（p50/p90/p95/p99/p99.9/max，未拆accepted阶段）：PLACE 8781/21823/24854/30687/48398/63995（257536样本）；CANCEL 7958/16826/19169/24575/35291/53247（257536）；MARK 14516/21594/24920/36569/47087/50692（15194）；PLACE_BATCH 17498/23150/25444/33947/46071/54755（386304requests/7726080items）；CANCEL_BATCH 19660/26165/28344/41123/62128/65437（128768requests/2575360items）。两类batch均20items。
- NMT reserved3124835→3126870KB、committed667787→703402KB；未采线程CPU、GC暂停或分配率，不能给出本轮Owner有效饱和程度或长期泄漏结论。代码未改动，无新增测试报告。client.log SHA256=de8e6370e9942f4cc425521233def9df1f17d3ed509c4c4a159aa15a1471eec6；通过但属于本机受限负载表现，不是云端三节点容量结论。
- 节点/客户端已停止；清理/tmp/owner-fullspeed-check全部1799421539B产物（Archive/日志/脚本/NMT等），原始路径已失效。用户文件、构建JAR和README不变。

## 2026-09-11 Owner 协调计数诊断：采集前锁定

- 当前master f31576ab（生产代码e5619569），对照不适用，不运行旧版本。沿用HotSpot GraalVM25.0.1、Intel i9-9880H8C16T/16GiB、G1/NMT summary；单个真实Aeron成员/网络/Archive/Core，4Lane BUSY_SPIN、2matcher、PIPELINED、SHARED_NETWORK/service YIELDING；node512m/1536m，client128m/512m/SHARED。
- U永续连续交易，1769用户/256币对、batch20、seed131001、全局/session在途256、1命令session+1保留查询session；mixed-trading-stream=true、mixed-operational=false，普通下/撤各512、批下15360项/批撤5120项、每cycle5120fills，初始资金1768000000125。JMH continuousOperations f1/wi0/i1/controlPageSize0/gc，内部预热30s/测量45s，无额外冷却。只诊断当前路径，不作完整生产查询/控制并发验收。
- 外部临时Java agent使用JDK ASM对当前JAR加载时插桩，生产源码/JAR不变：Owner单线程计数prefix调用/未完成返回、pump、通知消费/忽略/缺失pending、有效signal、ready探测/空返回、finishBatch和发布。每65536次计数生成一次JFR累计事件，使用测量窗内首末事件差及其覆盖时间，不能用整个warmup累计数除测量业务量。计数和profile有开销，不能拿本轮吞吐做回退/提升结论；没有硬件CAS重试指标。
- node JFR profile/max128MiB，记录计数事件、CPU/GC/分配/NMT；每2s监测温控与磁盘>10GiB，client最长300s。通过要求资金/状态校验PASS、offered=terminal business/Core、unfinished0、无DataLoss；限速/swap即仅诊断。无生产改动，不重复六产品恢复门禁；恢复沿用e5619569已通过18阶段，不冒称本轮重新验证。原始产物/tmp/owner-coordination-audit，提取摘要后停止清理。

### 第一轮及补充计数锁定

- 工具准备：HotSpot25不再暴露内部ASM，改用本机Maven缓存ASM9.9.1。首次启动服务fat JAR的旧ASM遮蔽agent依赖，未成功插桩，主动终止；第二次插桩栈映射校验失败，节点未启动，改为ASM9/COMPUTE_FRAMES并加启动插桩检查。两次均非业务失败、未形成有效测量，不采纳其性能数据；生产源码/JAR未改。
- 第一轮有效45.028s，7236649业务操作/699433Core消息、160716.060业务ops/s、15533.449消息/s、1720320fills/38205.950每秒；offered=terminal、unfinished0、fundsDiff0、hash4cc8598f65bb61e1。Owner97.360%、matcher26.046/25.933%、Lane97.576–97.747%含自旋；DataLoss0/Owner IO0。分配495.211MiB/s（加权23381527064B），GC78/489.217ms/max11.329ms，heap峰389.02MiB；thermal58–100，本轮不作容量结论。
- 计数事件1129个，均trading-owner--1；测量内首末覆盖44.975s：ingress698601、prefix31094370/未完成30406958、pump1767464、准入通知429568/忽略0、结算通知773428/缺失pending445/继续signal772983、readyProbe3706960/空2801806、finishBatch429285、commitPublish1042740。每Core入口prefix44.51/pump2.53；缺失pending仅0.0575%，不能把全部结算通知归类迟到冗余。prefixIncomplete不等于无进展。
- 补充轮仍当前master/相同JAR/场景/30s预热45s测量/JMH/JFR/所有门槛；新添两个Owner计数：明确走prefix快速等待返回分支、pump前后matchingProgressSequence不变。后者仅表示准入/撮合/结算通知等进度序号未变化，不等同证明没有任何派发/检查工作。不更改第一轮定义；新采集前移走第一轮输出，完整保留至摘要提取后统一清理。

### 补充轮结果与结论

- 45.029s，offered=terminal：8333568业务操作/804096Core消息；185071.627业务ops/s、17857.340消息/s、1981440fills/44003.760每秒。unfinished0/peak256/fundsDiff0，资金/持仓/冻结/损失场景PASS，hash529b6c6220a6328；query/trigger计数0，非全运营负载。CPU_Speed_Limit60–100，插桩和JFR开销存在，不作容量或历史回退因果结论。
- 1681个累计事件均由trading-owner--1发布，测量内首末44.983s：ingress802023；prefix32515176/未完成31724450/快速门禁返回30814219；pump2008900/前后matchingProgressSequence不变892132；准入通知494107/忽略0；结算通知889541/缺失pending451/继续signal889090；readyProbe4215459/空3163144；finishBatch494474；commitPublish1197314。每Core入口prefix40.54、pump2.50；94.77%prefix被门禁挡住；44.41%pump进度序号不变；缺失pending仅0.0507%。正常双Lane结果、批量项通知仍有必要，不能把未缺失pending的通知一概判冗余；未完成返回不等于零工作。
- Owner3306执行样本，互斥分类：collection439(13.28%)、publication310(9.38%)、finishOther191(5.78%)、pumpOther802(24.26%)、commitOther263(7.96%)、prefixOutsideCommit204(6.17%)、instrumentation9(0.27%)、other1088(32.91%)。pumpOther包含真实准入/撮合收集/结算派发，不是24%空转；prefixOutsideCommit也包含完成前缀处理，不是纯门禁耗时。inclusive finishOrderBatch796(24.08%)、takeReadyLaneMask64(1.94%)、AtomicLong.getAndAccumulate60(1.81%)、RuntimeFactIndexes.applyCurrent193(5.84%)；不可相加。没有共享原子更新占主导的证据，不能据sample小就排除缓存竞争。
- Owner97.995%、matcher26.865/26.810%、四Lane98.132–98.152%含busy-spin。machine88.76%、JVM58.94%；DataLoss0/Owner同步IO0。加权分配26644619576B/564.309MiB/s，约3197B/业务操作（抽样非精确对象数）；GC89次/524.171ms/p99=max10.197ms，heapUsed峰389.43MiB；NMT reserved3144610→3165456KB、committed696946→734556KB。无长稳、全native池余额、硬件CAS/缓存计数和精确空pump耗时，未记录本轮swap增量，不作泄漏或完整环境验收。
- 第二轮入口→终态us，按p50/p90/p95/p99/p99.9/max：PLACE8249/21315/23576/26951/43843/59539（198144）；CANCEL7622/16228/18186/21839/39026/46202（198144）；MARK12607/20578/22626/31768/36929/38764（11520）；PLACE_BATCH17711/22085/24150/32718/48103/56066（297216批/5944320项）；CANCEL_BATCH19251/24231/25673/29999/55574/59113（99072批/1981440项）。batch均20，缺accepted阶段拆分/CO修正，不作尾延迟验收。首轮普通PLACE p50/p99/max9584/33882/85524us，后续轮不是相同环境的加速对照。
- JMH首轮45.028227525s/invocation，client125.955798MiB/s、14879709176B/invocation、192GC/225ms；补充45.029403907s/invocation，151.220894MiB/s、17816474144B/invocation、229GC/227ms。client归一分配包含内部预热，不是每业务操作；单iteration无有效置信区间。首轮NMT reserved3145277→3170295KB/committed697541→740755KB。两轮JFR均profile/stack128、已生成summary及测量窗口分组分析；首轮5525522B/SHA256=5335ef8f5633ea96bf68c0fb8a06ffaad674cf89097769c2f88901faceaf2e1e，补充6005909B/SHA256=396ed5f3fb979b4c827b4dc8f2b555cb44781da618f0fdcea74292ddb0c21114。
- 本次确认：迟到且pending缺失不是主负担；前缀门禁已拦截多数空检查，仍有每Core约1.11次进度序号不变的完整pump值得逐路径精简；Owner的实际结果收集、终态/索引/发布和准入派发仍是大项。没有证明上述任一项造成昨天到今天的13%差额，也不据此删除资金依赖、健康检查或确定性提交。生产代码/JAR未改，无新增生产诊断变量，无需重跑功能套件；仅记录诊断结果。
- 清理完成：全部记录的节点/客户端已退出；/tmp/owner-coordination-audit共3852720281B（含无效启动、两轮Archive/JFR/日志/临时agent与分析器）已删除，原始路径不可访问。补充agent源码SHA256=ff60851e4b927e0069b39d3ee365abf35c75ac01701c004b0fefa7d69480bce2。保留现有构建JAR、用户文件及README；本次git diff --check作为记录变更检查。

## 2026-09-11 matcher/Lane 扩容诊断：采集前锁定

- 仅当前master dc5b22e3（生产e5619569），无旧版本对照、不改业务代码；本机单个真实Aeron成员含网络/Archive/Core。依次4Lane/2matcher、4Lane/4matcher、8Lane/2matcher，逐组全新目录并停止上一节点；一次只改变一个线程维度。增加线程不等于提高客户端到达压力，仍固定全局/session在途256。比较仅描述本机指定负载下的配置表现，降频/swap/DataLoss时不作扩容收益或容量结论。
- HotSpot GraalVM25.0.1/Maven3.9.16，Intel i9-9880H8C16T/16GiB/macOS，G1/NMT summary；node512m/1536m，client128m/512m；PIPELINED、Lane BUSY_SPIN/spin0、SHARED_NETWORK/service YIELDING、client SHARED。所有组相同seed131001/U永续/1769用户/256symbol/batch20/1命令session+1保留查询session，初始资金1768000000125；持续交易mixed-trading-stream=true、operational=false，普通下撤各512、批下15360项/撤5120项、每cycle5120fills；沿用内嵌做市流。
- 各组JMH continuousOperations f1/wi0/i1/controlPageSize0/-prof gc，内部warm30s/measure45s，无额外冷却；node JFR profile/stack128/max128m，不带上一轮计数agent。JMH/JFR只作分组诊断，不替代无profiler主吞吐。每2s采温控，每组vm_stat前后/NMT前后，磁盘>10GiB、client超时300s；若发现业务失败终止后续组。通过要求offered=terminal业务/Core、unfinished0、资金/状态核验PASS、真实线程数匹配配置。记录普通/批量延迟、Owner/matcher/Lane CPU及采样，分辨忙自旋与实际业务。没有六产品/查询风控持续并发、恢复/长稳或硬件缓存计数验收；生产未改不重跑此前恢复门禁。原始产物/tmp/owner-thread-scaling，提取后清理；README不变。

## 2026-09-11 提高现有 matcher/Lane 有效负载：纠正目标后采集前锁定

- 用户澄清目标是提高发压量和现有matcher/Lane工作饱和度，禁止把它替换成增加线程。已停止扩线程流程，4Lane/4matcher组未完成终检不采纳，8Lane组未启动。此前已完成4Lane/2matcher/256组保留为本次同代码同负载起点：45.136s/157954.248业务ops/s/15274.185Core消息/s/fundsDiff0/unfinished0；不重跑旧版或此基准组。
- 当前master/原始JAR不改，固定4Lane/2matcher，其余环境/JMH/JFR/产品线/用户/币对/资金/混合动作/30s预热45s测量全部同上一条锁定。按用户最新加压要求覆盖旧256上限，本轮把全局与唯一命令session在途同时提高到512、1024；仍持续异步FIFO提交，只有上限背压及业务资金/顺序约束，不放宽正确性校验。若1024相对512测得吞吐增加超过10%且普通下单p99<1s并无错误，可再加2048；否则停止扩大积压。此处比较为本机诊断趋势，温控/系统干扰存在时不作配置净收益或理论上限结论。
- 实际源码已确认客户端支持1..65536在途，但服务ClusterCommandWindow.CAPACITY=64；提高客户端压力不自动扩大Owner内部独立命令窗口。本轮验证是否存在供应不足，不修改Owner调度与提交逻辑。通过要求终态/资金/持仓/冻结PASS、无遗留、实际线程数仍4/2、区分matcher处理与Lane busy-spin；记录全部延迟/进程线程采样/分配GC/NMT/温控与swap。新输出/tmp/owner-offer-pressure，连同误解任务的产物一起提取并清理。

### 加压实测结果

- lanes4-matchers2：mixedCapacity=PASS elapsedSeconds=45.136 terminalBusinessOperations=7129344 offeredBusinessOperations=7129344 terminalCoreMessages=689408 offeredCoreMessages=689408 businessOpsPerSec=157954.248 coreMessagesPerSec=15274.185 fills=1694720 fillsPerSec=37547.385 queries=0 unfinished=0 peakInFlight=256 measuredCycles=331 totalCycles=571 triggerExecutions=0；mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=571 businessHash=ce301e2c20121a04
  - totals allocationMiBps=481.101 allocationBytes=22770302616 machineCPU=90.05 jvmCPU=57.09 heapMaxMiB=392.45 dataLoss=0 ownerIOEvents=0；gc count=77 totalMs=512.384 p99Ms=13.055 maxMs=13.055
  - CPU 98.008 core-account-lane-3, 98.000 core-account-lane-1, 97.991 core-account-lane-0, 97.990 core-account-lane-2, 95.075 trading-owner--1, 25.714 core-matcher-1, 25.684 core-matcher-0, 2.060 JVMCI-native CompilerThread0；CPU_Speed_Limit=[56, 58, 60, 62, 64, 66, 68, 70, 72, 79, 100]；vm页增量={'Swapins': 1789, 'Swapouts': 0, 'Pageouts': 796, 'Pageins': 167689}
  - {'before': 'Total: reserved=3144352KB, committed=692032KB', 'after': 'Total: reserved=3157754KB, committed=732770KB'}；JMH秒/调用=45.135975931；client GC={'gc.alloc.rate': 128.4971625789923, 'gc.alloc.rate.norm': 15192768784.0, 'gc.count': 196.0, 'gc.time': 224.0}；JFR bytes=5405103/SHA256=972282d88798b0f9f8fca6bf2b491788c976bc42e650218ba3f6905b7c0b721f
  - 延迟us（p50/p90/p95/p99/p99.9/max；items/requests）：PLACE_ORDER items=169472 requests=169472 p50us=10412 p90us=24526 p95us=26705 p99us=30064 p999us=58327 maxus=77201；CANCEL_ORDER items=169472 requests=169472 p50us=9125 p90us=19202 p95us=20987 p99us=26853 p999us=40992 maxus=56459；APPLY_MARK_PRICE items=11520 requests=11520 p50us=15761 p90us=25116 p95us=28868 p99us=46202 p999us=55017 maxus=57638；PLACE_ORDER_BATCH items=5084160 requests=254208 p50us=20234 p90us=25034 p95us=28278 p99us=39845 p999us=64749 maxus=82313；CANCEL_ORDER_BATCH items=1694720 requests=84736 p50us=22446 p90us=27213 p95us=28721 p99us=51085 p999us=67371 maxus=78118
  - Lane采样（eventExecute包含所有子调用，非精确CPU忙比例）：core-account-lane-0 samples=3905 accountEventExecuteInclusive=326；core-account-lane-1 samples=3856 accountEventExecuteInclusive=588；core-account-lane-2 samples=3783 accountEventExecuteInclusive=850；core-account-lane-3 samples=3689 accountEventExecuteInclusive=874
- window512：mixedCapacity=PASS elapsedSeconds=45.059 terminalBusinessOperations=6268931 offeredBusinessOperations=6268931 terminalCoreMessages=607235 offeredCoreMessages=607235 businessOpsPerSec=139126.205 coreMessagesPerSec=13476.349 fills=1489920 fillsPerSec=33065.752 queries=0 unfinished=0 peakInFlight=512 measuredCycles=291 totalCycles=455 triggerExecutions=0；mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=455 businessHash=d81d63abac922fca
  - totals allocationMiBps=426.432 allocationBytes=20148438264 machineCPU=94.83 jvmCPU=56.43 heapMaxMiB=395.36 dataLoss=0 ownerIOEvents=0；gc count=68 totalMs=491.123 p99Ms=13.610 maxMs=13.610
  - CPU 97.719 core-account-lane-2, 97.713 core-account-lane-0, 97.711 core-account-lane-3, 97.704 core-account-lane-1, 97.595 trading-owner--1, 27.112 core-matcher-1, 27.095 core-matcher-0, 2.547 JVMCI-native CompilerThread0；CPU_Speed_Limit=[50, 52, 54, 56, 58, 60, 62, 66, 72, 83, 89, 93, 95, 100]；vm页增量={'Swapins': 1831, 'Swapouts': 0, 'Pageouts': 1659, 'Pageins': 163409}
  - {'before': 'Total: reserved=3142335KB, committed=691979KB', 'after': 'Total: reserved=3156374KB, committed=735350KB'}；JMH秒/调用=45.059927016；client GC={'gc.alloc.rate': 103.588665065083, 'gc.alloc.rate.norm': 12139205152.0, 'gc.count': 157.0, 'gc.time': 250.0}；JFR bytes=4910996/SHA256=f1c894d56b7c7ae9f94662488a0b6f77c2452857c8e5b094231e09c0fcbc27e0
  - 延迟us（p50/p90/p95/p99/p99.9/max；items/requests）：PLACE_ORDER items=148992 requests=148992 p50us=38502 p90us=52723 p95us=55279 p99us=63111 p999us=123863 maxus=142344；CANCEL_ORDER items=148992 requests=148992 p50us=31457 p90us=38273 p95us=42565 p99us=55017 p999us=93585 maxus=95485；APPLY_MARK_PRICE items=11267 requests=11267 p50us=38371 p90us=51118 p95us=54231 p99us=60358 p999us=93650 maxus=119341；PLACE_ORDER_BATCH items=4469760 requests=223488 p50us=41123 p90us=50266 p95us=53084 p99us=75825 p999us=116916 maxus=122617；CANCEL_ORDER_BATCH items=1489920 requests=74496 p50us=49610 p90us=56950 p95us=60096 p99us=94240 p999us=124846 maxus=162267
  - Lane采样（eventExecute包含所有子调用，非精确CPU忙比例）：core-account-lane-0 samples=3908 accountEventExecuteInclusive=348；core-account-lane-1 samples=3878 accountEventExecuteInclusive=617；core-account-lane-2 samples=3819 accountEventExecuteInclusive=874；core-account-lane-3 samples=3735 accountEventExecuteInclusive=950
- window1024：mixedCapacity=PASS elapsedSeconds=45.141 terminalBusinessOperations=6096896 offeredBusinessOperations=6096896 terminalCoreMessages=590848 offeredCoreMessages=590848 businessOpsPerSec=135063.056 coreMessagesPerSec=13088.912 fills=1448960 fillsPerSec=32098.459 queries=0 unfinished=0 peakInFlight=1024 measuredCycles=283 totalCycles=375 triggerExecutions=0；mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=375 businessHash=45982fbb261e92a3
  - totals allocationMiBps=412.954 allocationBytes=19547103496 machineCPU=94.96 jvmCPU=57.73 heapMaxMiB=395.03 dataLoss=0 ownerIOEvents=0；gc count=66 totalMs=458.302 p99Ms=8.339 maxMs=8.339
  - CPU 97.824 trading-owner--1, 97.806 core-account-lane-0, 97.786 core-account-lane-1, 97.777 core-account-lane-2, 97.760 core-account-lane-3, 28.287 JVMCI-native CompilerThread0, 26.500 core-matcher-0, 26.457 core-matcher-1；CPU_Speed_Limit=[50, 52, 54, 56, 58, 60, 62, 64]；vm页增量={'Swapins': 320, 'Swapouts': 0, 'Pageouts': 3049, 'Pageins': 157939}
  - {'before': 'Total: reserved=3148380KB, committed=692016KB', 'after': 'Total: reserved=3157853KB, committed=736389KB'}；JMH秒/调用=45.141740099；client GC={'gc.alloc.rate': 85.0764913226679, 'gc.alloc.rate.norm': 10022794128.0, 'gc.count': 131.0, 'gc.time': 238.0}；JFR bytes=4754178/SHA256=1c91e89dd914039c59f33e76b8301aa04437429fc72577edad58022a0a5b9129
  - 延迟us（p50/p90/p95/p99/p99.9/max；items/requests）：PLACE_ORDER items=144896 requests=144896 p50us=86310 p90us=102694 p95us=109969 p99us=189530 p999us=262275 maxus=276299；CANCEL_ORDER items=144896 requests=144896 p50us=73662 p90us=93126 p95us=101974 p99us=163577 p999us=305135 maxus=323223；APPLY_MARK_PRICE items=11264 requests=11264 p50us=66879 p90us=102957 p95us=120258 p99us=265027 p999us=315359 maxus=321126；PLACE_ORDER_BATCH items=4346880 requests=217344 p50us=68354 p90us=97320 p95us=105119 p99us=161611 p999us=214040 maxus=322699；CANCEL_ORDER_BATCH items=1448960 requests=72448 p50us=83230 p90us=98303 p95us=106627 p99us=182452 p999us=216006 maxus=231342
  - Lane采样（eventExecute包含所有子调用，非精确CPU忙比例）：core-account-lane-0 samples=3865 accountEventExecuteInclusive=388；core-account-lane-1 samples=3825 accountEventExecuteInclusive=654；core-account-lane-2 samples=3748 accountEventExecuteInclusive=818；core-account-lane-3 samples=3663 accountEventExecuteInclusive=888
- 各组均真实4Lane/2matcher，Owner窗口highWaterMark64/pending0；256/512/1024组Owner CPU95.075/97.595/97.824%，matcher约25.7/27.1/26.5%，未达到有效饱和。四Lane event.execute样本占比约8–25%，其余主要SettlementLaneWorker.run，不能把约98%CPU当满负载。1024组JVMCI编译线程28.287%也是额外干扰，matcher样本每线程仅35–41，不能精确归因其内部热点。
- 加压后没有观察到终态吞吐增长，普通下单p99从30.064→63.111→189.530ms；1024相对512未满足增长10%的预锁条件，不再执行2048。各组都降频且有系统swapin/pageout，因此仅证明本轮没有实现用户期望的有效工作饱和，不据此宣称容量下降或64槽为唯一根因。
- 源码检查：ClusterCommandWindow除CAPACITY=64外，账户/币对索引用long位图、订单索引值也是long槽位集合，conflictingPrefixSize依赖rotateRight，增删槽使用&63。支持更大Owner窗口必须一起调整这些索引及回绕/依赖测试，不能只改常量。beginCommandPrefix固定逐命令日志边界不等于所有后续命令都不能并行；准入仍允许独立命令进入窗口。
- 4matcher误解组被用户纠正后停止，未完成最终资金/终态核验，不采纳其测量结果；8Lane没有启动。生产源码/JAR不变，不补跑六产品/恢复；所有结果仅当前U永续连续混合交易，查询/触发持续计数0。缺完整accepted阶段延迟、CO修正、TLAB逐对象数量、native池余额及长期泄漏证据，单JMH iteration无有效CI；每组已生成JFR summary和测量窗线程/分配/GC分析。
- 所有记录的测试进程已退出；/tmp/owner-thread-scaling 2401128948B、/tmp/owner-offer-pressure 1998002090B已清理，包括Archive/JFR/日志/临时工具，原始路径不可访问。用户文件和构建JAR保留；文档变更git diff --check通过。

## 2026-09-11 Owner 可配置分段窗口：采集前锁定

- 基于master564f612e当前工作区，不运行旧版。owner-command-window默认256，支持64/128/256/512/1024；账户/币对分段位图，精确订单ID只保留最新槽位+1（FIFO退窗保证较旧引用先消失），无逐命令新位图对象。保留逐日志命令提交、资金及潜在对手方依赖。新增五档随机串行参考模型/环形回绕/重复ID/跨分段币对/非法容量测试，六产品完整窗口回归扩到256；外部JMH新增inFlightWindow参数，须与实际node参数配套。
- 单机真实Aeron一成员，HotSpot GraalVM25.0.1/Maven3.9.16，Intel i9-9880H8C16T/16GiB/macOS，G1/NMT summary，node512m/1536m、client128m/512m；固定4Lane BUSY_SPIN/2matcher、PIPELINED、SHARED_NETWORK/serviceYIELDING/clientSHARED。逐组Owner/客户端256/256、512/512、1024/1024，最新用户授权覆盖旧固定256档；同seed131001、U永续、1769用户/256symbol/batch20，1命令+1保留查询session，初始资金1768000000125；普通下撤各512、批下15360项/批撤5120项、每cycle5120fills，持续交易true/operationalfalse。不额外冷却，每2s温控、vm_stat/NMT前后、磁盘>10GiB。
- 先定向reactor测试，再六产品单成员execute/强杀replay/真实snapshot重启；每产品控制JMH f1/wi1/i2/gc及nodeJFR，连续U永续各窗口JMH f1/wi0/i1/controlPageSize0/inFlightWindow对应容量/gc，内部warm30s/measure45s、JFR profile/stack128/max128m。最后对1024连续流的日志重放及快照重启核对hash。均在当前源码/JAR，不对旧commit加速比作结论；profile值仅诊断，无额外无profiler容量宣称。
- 门槛：无测试失败、offered=terminal business/Core、unfinished0/fundsDiff0、余额/持仓/冻结/订单状态正确、恢复hash一致；实际窗口容量/峰值及线程数符合配置。温控/swap/DataLoss时无容量结论。无查询风控持续混合、完整accepted延迟/CO修正、长稳/native池余额或硬件缓存计数结论；索引随固定有界窗口清理，长期泄漏未验收。全部原始输出/tmp/owner-window-validation，完成提取后清理，README不写性能信息。

### 验证结果

- HotSpot25定向reactor package最终286 tests（service277/bench9），0失败/错误/跳过；包括五档随机串行参考模型、跨64槽/回绕/重复订单及六产品256条独立命令流水线与snapshot核对。初次实现后精简精确订单索引，再重跑相同受影响套件通过。生产JAR SHA256=deadb37be7dfb505e4cede6d08eeccfd7781f0d679d61208b4f8d99c027ccfdf；bench=cf237043e7422ee425fc2314e291b8addc6a0b64407bf6803d6f724c85c9b5fd。
- 六产品execute/强杀replay/snapshot共18阶段全部PASS/fundsDiff0；snapshot positions现货3616、两永续9568、两交割/期权5184。1024连续流重放与snapshot重启hash均fd80cee343640b，snapshotPosition893623424。重放选主时出现quorum position went backwards警告，随后LEADER与两次完整资金/业务hash核对成功；不将它归因为本轮窗口逻辑失败。
- Owner/客户端窗口256/256：mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=403 businessHash=8ff1aa460b496cf6；mixedCapacity=PASS elapsedSeconds=45.065 terminalBusinessOperations=6527081 offeredBusinessOperations=6527081 terminalCoreMessages=631913 offeredCoreMessages=631913 businessOpsPerSec=144837.436 coreMessagesPerSec=14022.296 fills=1551360 fillsPerSec=34425.037 queries=0 unfinished=0 peakInFlight=256 measuredCycles=303 totalCycles=403 triggerExecutions=0；Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=256 pending=0 windows=833596 commands=833596 dependencyFences=1812 controlFences=156519
  - totals allocationMiBps=443.336 allocationBytes=20949896736 machineCPU=93.02 jvmCPU=57.91 heapMaxMiB=453.62 dataLoss=0 ownerIOEvents=0；gc count=76 totalMs=609.972 p99Ms=12.908 maxMs=12.908；NMT={'before': 'Total: reserved=3142108KB, committed=691752KB', 'after': 'Total: reserved=3164090KB, committed=734634KB'}
  - CPU 97.543 core-account-lane-1, 97.536 core-account-lane-3, 97.533 core-account-lane-2, 97.525 core-account-lane-0, 97.304 trading-owner--1, 25.459 core-matcher-1, 25.453 core-matcher-0, 23.592 JVMCI-native CompilerThread0；CPU_Speed_Limit=[52, 54, 56, 58, 60, 62, 64, 70, 75, 79, 83, 85, 89, 93, 97, 100]；系统vm增量={'Swapins': 508, 'Swapouts': 0, 'Pageins': 240042, 'Pageouts': 2270}
  - JMH秒/调用=45.065554254，client GC={'gc.alloc.rate': 91.1833388039174, 'gc.alloc.rate.norm': 10781238456.0, 'gc.count': 139.0, 'gc.time': 205.0}；JFR bytes=5007383/SHA256=a16e7ebdacbc1d8ee877f3fca5f1a827ad89fea982f72d1350681e2d2889ebae
  - 入口→终态延迟us（p50/p90/p95/p99/p99.9/max，requests为批次数）：business=PLACE_ORDER items=155136 requests=155136 p50us=11157 p90us=25608 p95us=27623 p99us=34832 p999us=58753 maxus=66125；business=CANCEL_ORDER items=155136 requests=155136 p50us=12214 p90us=20414 p95us=23363 p99us=29573 p999us=49020 maxus=63799；business=APPLY_MARK_PRICE items=11369 requests=11369 p50us=13180 p90us=22577 p95us=26836 p99us=48988 p999us=55377 maxus=61046；business=PLACE_ORDER_BATCH items=4654080 requests=232704 p50us=22331 p90us=28704 p95us=32653 p99us=48070 p999us=62029 maxus=68157；business=CANCEL_ORDER_BATCH items=1551360 requests=77568 p50us=21037 p90us=27099 p95us=30818 p99us=48791 p999us=74579 maxus=77791
  - laneSamples=15122 laneLoopSelf=12048 ownerSamples=3414 windowInclusive={com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict=49, com.surprising.aeron.service.execution.ClusterCommandWindow.add=23, com.surprising.aeron.service.execution.ClusterCommandWindow.candidateOrder=10, com.surprising.aeron.service.execution.ClusterCommandWindow.complete=62, com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize=103, com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingWord=79, com.surprising.aeron.service.execution.ClusterCommandWindow.decoded=85, com.surprising.aeron.service.execution.ClusterCommandWindow.indexSlots=9, com.surprising.aeron.service.execution.ClusterCommandWindow.intersectingSlots=8, com.surprising.aeron.service.execution.ClusterCommandWindow.removePrefix=57, com.surprising.aeron.service.execution.ClusterCommandWindow.resetCandidate=4}
- Owner/客户端窗口512/512：mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=423 businessHash=9fb35dabfc4a4936；mixedCapacity=PASS elapsedSeconds=45.091 terminalBusinessOperations=5752832 offeredBusinessOperations=5752832 terminalCoreMessages=558080 offeredCoreMessages=558080 businessOpsPerSec=127583.633 coreMessagesPerSec=12376.839 fills=1367040 fillsPerSec=30317.577 queries=0 unfinished=0 peakInFlight=512 measuredCycles=267 totalCycles=423 triggerExecutions=0；Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=319 pending=0 windows=874556 commands=874556 dependencyFences=1814 controlFences=76263
  - totals allocationMiBps=387.377 allocationBytes=18316097320 machineCPU=92.45 jvmCPU=56.68 heapMaxMiB=450.60 dataLoss=0 ownerIOEvents=0；gc count=76 totalMs=630.850 p99Ms=15.918 maxMs=15.918；NMT={'before': 'Total: reserved=3146287KB, committed=691935KB', 'after': 'Total: reserved=3160856KB, committed=734252KB'}
  - CPU 97.245 core-account-lane-3, 97.220 core-account-lane-2, 97.218 core-account-lane-0, 97.209 core-account-lane-1, 97.109 trading-owner--1, 24.087 core-matcher-1, 24.084 core-matcher-0, 4.369 JVMCI-native CompilerThread0；CPU_Speed_Limit=[52, 54, 56, 58, 60, 62, 64, 66]；系统vm增量={'Swapins': 510, 'Swapouts': 0, 'Pageins': 161219, 'Pageouts': 619}
  - JMH秒/调用=45.091350956，client GC={'gc.alloc.rate': 95.38650020613169, 'gc.alloc.rate.norm': 11295778768.0, 'gc.count': 146.0, 'gc.time': 244.0}；JFR bytes=4989576/SHA256=2ae5dff82375f65ce0297d3f0702422c51a106138f97363990ab595bf8e6a2fa
  - 入口→终态延迟us（p50/p90/p95/p99/p99.9/max，requests为批次数）：business=PLACE_ORDER items=136704 requests=136704 p50us=33882 p90us=50823 p95us=53903 p99us=83427 p999us=126943 maxus=154140；business=CANCEL_ORDER items=136704 requests=136704 p50us=31014 p90us=41287 p95us=47808 p99us=77725 p999us=134479 maxus=142475；business=APPLY_MARK_PRICE items=11264 requests=11264 p50us=35782 p90us=48857 p95us=56229 p99us=115802 p999us=139329 maxus=139853；business=PLACE_ORDER_BATCH items=4101120 requests=205056 p50us=50233 p90us=62259 p95us=67698 p99us=111804 p999us=138149 maxus=146407；business=CANCEL_ORDER_BATCH items=1367040 requests=68352 p50us=43384 p90us=53084 p95us=57966 p99us=93192 p999us=134610 maxus=153878
  - laneSamples=15177 laneLoopSelf=12637 ownerSamples=3396 windowInclusive={com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict=79, com.surprising.aeron.service.execution.ClusterCommandWindow.add=25, com.surprising.aeron.service.execution.ClusterCommandWindow.candidateOrder=5, com.surprising.aeron.service.execution.ClusterCommandWindow.complete=63, com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize=153, com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingWord=124, com.surprising.aeron.service.execution.ClusterCommandWindow.decoded=106, com.surprising.aeron.service.execution.ClusterCommandWindow.indexSlots=10, com.surprising.aeron.service.execution.ClusterCommandWindow.intersectingSlots=7, com.surprising.aeron.service.execution.ClusterCommandWindow.matchingRangesConflict=1, com.surprising.aeron.service.execution.ClusterCommandWindow.removePrefix=38, com.surprising.aeron.service.execution.ClusterCommandWindow.resetCandidate=1}
- Owner/客户端窗口1024/1024：mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=427 businessHash=fd80cee343640b；mixedCapacity=PASS elapsedSeconds=45.138 terminalBusinessOperations=5903360 offeredBusinessOperations=5903360 terminalCoreMessages=572416 offeredCoreMessages=572416 businessOpsPerSec=130784.794 coreMessagesPerSec=12681.474 fills=1402880 fillsPerSec=31079.821 queries=0 unfinished=0 peakInFlight=1024 measuredCycles=274 totalCycles=427 triggerExecutions=0；Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=319 pending=0 windows=882748 commands=882748 dependencyFences=1822 controlFences=89823
  - totals allocationMiBps=400.436 allocationBytes=18953285520 machineCPU=91.15 jvmCPU=57.58 heapMaxMiB=451.38 dataLoss=0 ownerIOEvents=0；gc count=74 totalMs=586.953 p99Ms=15.819 maxMs=15.819；NMT={'before': 'Total: reserved=3150316KB, committed=692000KB', 'after': 'Total: reserved=3154105KB, committed=729153KB'}
  - CPU 97.740 core-account-lane-3, 97.727 core-account-lane-1, 97.716 trading-owner--1, 97.714 core-account-lane-2, 97.660 core-account-lane-0, 24.608 core-matcher-0, 24.558 core-matcher-1, 2.359 JVMCI-native CompilerThread0；CPU_Speed_Limit=[52, 54, 56, 58, 60, 62, 64]；系统vm增量={'Swapins': 767, 'Swapouts': 0, 'Pageins': 155127, 'Pageouts': 596}
  - JMH秒/调用=45.138573939，client GC={'gc.alloc.rate': 97.10682011651447, 'gc.alloc.rate.norm': 11397839192.0, 'gc.count': 148.0, 'gc.time': 229.0}；JFR bytes=5022525/SHA256=2fe940a796275812329b4bf04134ecf04fcbda0b3891a65d26be9cb87a6ae3ab
  - 入口→终态延迟us（p50/p90/p95/p99/p99.9/max，requests为批次数）：business=PLACE_ORDER items=140288 requests=140288 p50us=78249 p90us=107216 p95us=112852 p99us=170262 p999us=249561 maxus=270008；business=CANCEL_ORDER items=140288 requests=140288 p50us=68878 p90us=82182 p95us=86310 p99us=154140 p999us=213254 maxus=228851；business=APPLY_MARK_PRICE items=11264 requests=11264 p50us=63143 p90us=86507 p95us=100794 p99us=200409 p999us=225968 maxus=227147；business=PLACE_ORDER_BATCH items=4208640 requests=210432 p50us=81264 p90us=92864 p95us=97779 p99us=175636 p999us=215351 maxus=228982；business=CANCEL_ORDER_BATCH items=1402880 requests=70144 p50us=94109 p90us=106758 p95us=111280 p99us=198836 p999us=251527 maxus=269484
  - laneSamples=15225 laneLoopSelf=12639 ownerSamples=3404 windowInclusive={com.surprising.aeron.service.execution.ClusterCommandWindow.accountsConflict=91, com.surprising.aeron.service.execution.ClusterCommandWindow.add=18, com.surprising.aeron.service.execution.ClusterCommandWindow.candidateOrder=6, com.surprising.aeron.service.execution.ClusterCommandWindow.complete=59, com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingPrefixSize=160, com.surprising.aeron.service.execution.ClusterCommandWindow.conflictingWord=143, com.surprising.aeron.service.execution.ClusterCommandWindow.decoded=101, com.surprising.aeron.service.execution.ClusterCommandWindow.indexSlots=3, com.surprising.aeron.service.execution.ClusterCommandWindow.intersectingSlots=12, com.surprising.aeron.service.execution.ClusterCommandWindow.removePrefix=45, com.surprising.aeron.service.execution.ClusterCommandWindow.resetCandidate=4}
- 以上三组JFR profile/stack128均已生成summary及测量窗线程/分配/GC归因。所有offered=terminal、unfinished0、资金/持仓/冻结/损失场景核验PASS，query/trigger持续计数0；batch始终20，业务计数不是Aeron消息数。node allocation为采样加权估计，client B/invocation含内部预热，不是每业务操作；无精确TLAB/对象数量/native池余额/CO修正或长稳泄漏结论，单iteration没有有效CI。各轮均降频并有系统换页，不能作容量验收或旧版回退/加速因果结论。
- 六产品控制JMH（ms/整段调用，f1/wi1/i2，nodeJFR实际profile默认stack64，与连续流128区分；下面node指标包含启动/预热/收尾，不作稳态性能比较）：
  - lanes-control-INVERSE_DELIVERY-0 2987.7268435ms；clientGC={'gc.alloc.rate': 0.975463813323191, 'gc.alloc.rate.norm': 3096488.0, 'gc.count': 0.0}；accountControlVerify=PASS product=INVERSE_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；totals allocationMiBps=6.379 allocationBytes=90697984 machineCPU=48.78 jvmCPU=33.05 heapMaxMiB=51.22 dataLoss=0 ownerIOEvents=0；gc count=4 totalMs=11.560 p99Ms=4.794 maxMs=4.794；JFR 837701B/SHA256=1bd8ef3bd433ccdd7a1492d5b319c34a30ddd3dc8d73df724f36c25c874c326b
  - lanes-control-INVERSE_PERPETUAL-0 3028.428779ms；clientGC={'gc.alloc.rate': 1.2045601521583607, 'gc.alloc.rate.norm': 3878900.0, 'gc.count': 0.0}；accountControlVerify=PASS product=INVERSE_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；totals allocationMiBps=6.408 allocationBytes=91115384 machineCPU=48.48 jvmCPU=32.72 heapMaxMiB=51.62 dataLoss=0 ownerIOEvents=0；gc count=4 totalMs=12.642 p99Ms=6.329 maxMs=6.329；JFR 820197B/SHA256=b20a47cd1f36c5bd30ce92486025ce9e3432e0c20aba75128794f5ad3973d9ce
  - lanes-control-LINEAR_DELIVERY-0 3044.80272ms；clientGC={'gc.alloc.rate': 0.6397643476419168, 'gc.alloc.rate.norm': 2076880.0, 'gc.count': 0.0}；accountControlVerify=PASS product=LINEAR_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；totals allocationMiBps=6.393 allocationBytes=90941352 machineCPU=48.48 jvmCPU=33.15 heapMaxMiB=51.92 dataLoss=0 ownerIOEvents=0；gc count=4 totalMs=12.590 p99Ms=5.611 maxMs=5.611；JFR 833967B/SHA256=b3ce7574d3c6812c51166d34fec8fab051a9e82149a8d9dc819804a77ead394e
  - lanes-control-LINEAR_PERPETUAL-0 3031.9833735ms；clientGC={'gc.alloc.rate': 1.1732806276591017, 'gc.alloc.rate.norm': 3781964.0, 'gc.count': 0.0}；accountControlVerify=PASS product=LINEAR_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；totals allocationMiBps=6.371 allocationBytes=90516496 machineCPU=53.26 jvmCPU=32.88 heapMaxMiB=51.55 dataLoss=0 ownerIOEvents=0；gc count=4 totalMs=12.197 p99Ms=5.483 maxMs=5.483；JFR 848817B/SHA256=3fa49eb360998343be9c93e5b6d5e0856a2ccd4aa8de9e433a3a6a702c16f59e
  - lanes-control-OPTION-0 2300.562651ms；clientGC={'gc.alloc.rate': 1.2103578703161575, 'gc.alloc.rate.norm': 2977144.0, 'gc.count': 0.0}；accountControlVerify=PASS product=OPTION terminalBusinessOperations=1800 unfinished=0 fundsDiff=0 netPosition=0；totals allocationMiBps=7.443 allocationBytes=89912560 machineCPU=49.76 jvmCPU=32.57 heapMaxMiB=52.76 dataLoss=0 ownerIOEvents=0；gc count=4 totalMs=12.747 p99Ms=6.091 maxMs=6.091；JFR 787218B/SHA256=949c17428359cc20a99993142e76d626aaabc4d161ab514c2e0bec7e4a4bdbc4
  - lanes-control-SPOT-0 799.5164545ms；clientGC={'gc.alloc.rate': 1.5291505378335866, 'gc.alloc.rate.norm': 1353932.0, 'gc.count': 0.0}；accountControlVerify=PASS product=SPOT terminalBusinessOperations=600 unfinished=0 fundsDiff=0 netPosition=0；totals allocationMiBps=11.890 allocationBytes=82775184 machineCPU=49.07 jvmCPU=28.75 heapMaxMiB=51.22 dataLoss=0 ownerIOEvents=0；gc count=4 totalMs=11.942 p99Ms=5.125 maxMs=5.125；JFR 712407B/SHA256=905974b7fdf53f15dd4e8a4c5e663e7bfe25c8b6787ce1a029a046286feb9495
- 本轮扩大窗口已生效：峰值256→319→319；更大两档未用满。4Lane/2matcher保持不变，Owner约97%，matcher约24–25%；Lane顶层SettlementLaneWorker.run样本占79.7–83.3%，不能把97%CPU当有效业务饱和。1024组Owner3404样本：conflictingPrefixSize160(4.70%)、complete59(1.73%)、pump811(23.82%)、finishOrderBatch774(22.74%)；inclusive嵌套不能相加。仍受Owner推进/准入/收集/发布等路径约束，本轮未证明吞吐提升，319峰值的具体分解未做新计数，不能断言是单一硬上限。
- 配置为-Dsurprising.aeron.owner-command-window=256（默认；64/128/256/512/1024）。客户端全局/session与JMH inFlightWindow配套同档，Owner容量不由客户端远程控制。精确订单最新槽位依赖FIFO生命周期，随机模型和恢复已验证；逐命令提交语义不变。最终代码只新增有界窗口配置/分段索引及测试，生产不包含诊断agent/计数器；README不变。
- 全部本轮节点/客户端已退出；已清理/tmp/owner-window-validation共9710583091B（Archive/JFR/日志/临时脚本与分析器）及16个本轮测试报告320263B、两份构建日志。原始路径已失效，现有JAR、用户文件保留；提交前git diff --check通过。

## 2026-09-11 重启后无 profiler 配套窗口压测：采集前锁定

- 当前master ae02158a，仅运行现有已验证JAR，不改生产代码、不重跑旧版。启动前CPU_Speed_Limit100、swap used0。HotSpot GraalVM25.0.1/i9-9880H8C16T/16GiB/macOS，G1/NMT summary；node512m/1536m、client128m/512m，单个真实Aeron成员/网络/Archive/Core，4Lane BUSY_SPIN/2matcher、PIPELINED、SHARED_NETWORK/serviceYIELDING/clientSHARED。
- 依次Owner/global/session窗口256、512、1024，配套一致；每组独立数据目录，预热30s/测量60s，无额外冷却；ClusterMixedCapacityMain，不带JMH/JFR/agent。U永续、1769用户/256币对/batch20、seed131001，单命令session+保留查询session、初始资金1768000000125，持续混合交易true/operationalfalse；普通下撤各512、批下15360项/批撤5120项、每cycle5120fills，内嵌做市流，发单仅受在途背压/金融依赖约束。
- 同步监测每2s温控、vm_stat前后、NMT前后、磁盘>10GiB；启动60s/客户端300s超时。门槛offered=terminal business/Core、unfinished0/fundsDiff0/资金持仓冻结订单核验PASS；异常即停止后续档位。记录实际窗口峰值、吞吐/fills/分类型延迟及温控换页；无本轮JFR线程CPU/GC/分配证据，不宣称下游饱和或长期泄漏验收。若仍降频/换页，不解释为版本净回退/提升；同代码此前带profiler数字仅背景，不作加速对照。原始产物/tmp/owner-reboot-check，结束提取并清理，不重跑功能/恢复（生产代码未变）。

### 用户调整配置后的采集前锁定

- 用户要求改为1matcher，Owner窗口64及128；保持此前两端配套要求，本轮Owner/global/session分别同时64、同时128，4Lane不变。其余同上：当前ae02158a/JAR不变、无profiler、相同业务/seed/用户/币对/批量、预热30s/测量60s与全部正确性门槛。刚启动的2matcher/256档已停止，不纳入本轮结果；不执行512/1024。单matcher结果不能与此前双matcher混作同一容量结论；最新授权覆盖旧固定256在途规则。

### 单 matcher 重启后结果

- 节点/客户端JAR校验与ae02158a已验证产物相同：service deadb37be7dfb505e4cede6d08eeccfd7781f0d679d61208b4f8d99c027ccfdf，bench cf237043e7422ee425fc2314e291b8addc6a0b64407bf6803d6f724c85c9b5fd；HotSpot25，无profiler/agent。两档实际启动参数均1matcher/4Lane，Owner/global/session容量一致。
- 窗口64：mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1017 businessHash=41ccaa61b8ae1897；mixedCapacity=PASS elapsedSeconds=60.021 terminalBusinessOperations=15433728 offeredBusinessOperations=15433728 terminalCoreMessages=1483776 offeredCoreMessages=1483776 businessOpsPerSec=257139.306 coreMessagesPerSec=24720.996 fills=3671040 fillsPerSec=61162.713 queries=0 unfinished=0 peakInFlight=64 measuredCycles=717 totalCycles=1017 triggerExecutions=0；Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=64 pending=0 windows=2091068 commands=2091068 dependencyFences=1668 controlFences=36675
  - 入口→终态us（p50/p90/p95/p99/p99.9/max，requests为命令数量，batch均20）：business=PLACE_ORDER items=367104 requests=367104 p50us=1246 p90us=2996 p95us=4114 p99us=5492 p999us=9658 maxus=99024；business=CANCEL_ORDER items=367104 requests=367104 p50us=1012 p90us=2379 p95us=3293 p99us=5128 p999us=8163 maxus=16859；business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=2600 p90us=5140 p95us=5779 p99us=7577 p999us=14131 maxus=19218；business=PLACE_ORDER_BATCH items=11013120 requests=550656 p50us=3559 p90us=4337 p95us=4747 p99us=9232 p999us=11788 maxus=29081；business=CANCEL_ORDER_BATCH items=3671040 requests=183552 p50us=3991 p90us=4837 p95us=5079 p99us=9953 p999us=14041 maxus=100401
  - 正式测量温控样本30，CPU_Speed_Limit=[68, 77]，100%样本=0；全阶段vm增量={'Swapins': 0, 'Swapouts': 0, 'Pageins': 165118, 'Pageouts': 447}；NMT={'before': 'Total: reserved=3120640KB, committed=667596KB', 'after': 'Total: reserved=3127981KB, committed=703301KB'}；client.log SHA256=06acf2abcf30f095db59d6f38af1ce991f510ccebe657056c8bd5991ad076482
- 窗口128：mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=992 businessHash=2e9722ce48f4c81e；mixedCapacity=PASS elapsedSeconds=60.059 terminalBusinessOperations=14659584 offeredBusinessOperations=14659584 terminalCoreMessages=1410048 offeredCoreMessages=1410048 businessOpsPerSec=244086.113 coreMessagesPerSec=23477.688 fills=3486720 fillsPerSec=58054.849 queries=0 unfinished=0 peakInFlight=128 measuredCycles=681 totalCycles=992 triggerExecutions=0；Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=128 pending=0 windows=2039868 commands=2039868 dependencyFences=1625 controlFences=70385
  - 入口→终态us（p50/p90/p95/p99/p99.9/max，requests为命令数量，batch均20）：business=PLACE_ORDER items=348672 requests=348672 p50us=2838 p90us=6770 p95us=8339 p99us=10092 p999us=18006 maxus=28917；business=CANCEL_ORDER items=348672 requests=348672 p50us=2535 p90us=4820 p95us=5787 p99us=7901 p999us=13451 maxus=21331；business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=4403 p90us=8699 p95us=9797 p99us=14352 p999us=22478 maxus=25231；business=PLACE_ORDER_BATCH items=10460160 requests=523008 p50us=7462 p90us=9256 p95us=10477 p99us=16424 p999us=24150 maxus=39845；business=CANCEL_ORDER_BATCH items=3486720 requests=174336 p50us=7716 p90us=10043 p95us=10584 p99us=15892 p999us=27934 maxus=34471
  - 正式测量温控样本30，CPU_Speed_Limit=[68, 68]，100%样本=0；全阶段vm增量={'Swapins': 0, 'Swapouts': 0, 'Pageins': 156332, 'Pageouts': 276}；NMT={'before': 'Total: reserved=3122764KB, committed=667676KB', 'after': 'Total: reserved=3134310KB, committed=707950KB'}；client.log SHA256=fcdb47ba6b623c19330f0126c5a55f5be97cb20201624c9bad5506b530c3bf9e
- 两档offered=terminal业务操作及Core消息、unfinished0、资金/持仓/冻结/损失场景核对PASS，持续query/trigger0；64档60.021秒均值25.71万业务ops/s（2.47万Core消息/s、6.12万fills/s），128档60.059秒24.41万（2.35万消息/s、5.81万fills/s），不是把10秒峰值当持续吞吐。64档PLACE p50/p99为1.246/5.492ms，但最大99.024ms；128档2.838/10.092ms，最大28.917ms，不能仅凭p99隐去极端尾延迟。
- 重启后swap used0，两档无swap-in/out，但仍有系统pageout且测量期CPU限速68–77/68；容量验收条件仍未满足。无线程CPU/GC/JFR/逐操作分配数据，不宣称owner/matcher/Lane有效饱和、无泄漏、配置净收益或重启单因素提升；不同于上一轮双matcher带JFR，不直接计算版本加速比。生产代码未改，无需重复已通过的功能/恢复套件；本轮无完整accepted延迟拆分/CO修正，无长稳验证。初始2matcher/256中止组不纳入结果。
- 全部记录的测试进程已退出；/tmp/owner-reboot-check共5387559659B（含中止组/两档Archive/日志/脚本/NMT）已删除，原始路径不可访问。没有新增测试报告，保留用户文件/现有JAR，README不变；记录变更git diff --check通过。

## 2026-09-11 单 matcher / 128 窗口定向采样：采集前锁定

- 当前master4ce3bba8（生产ae02158a），不改生产代码/不运行旧版；单真实Aeron成员/网络/Archive/Core、1matcher/4Lane BUSY_SPIN、Owner/global/session128，PIPELINED/SHARED_NETWORK/serviceYIELDING/clientSHARED。HotSpot GraalVM25.0.1、i9-9880H8C16T/16GiB、G1/NMT summary，node512m/1536m、client128m/512m；启动CPU限速100，磁盘>10GiB。
- U永续1769用户/256symbol/batch20/seed131001，1命令session+保留查询session；持续交易true/operationalfalse，初始资金1768000000125，动作与上一轮相同（普通下撤各512、批下15360项/批撤5120项、5120fills/cycle）。外部JMH continuousOperations f1/wi0/i1/controlPageSize0/inFlightWindow128/gc，内部预热30s/测量60s，无额外冷却。node JFR基于profile，ExecutionSample周期2ms、ThreadPark/JavaMonitorEnter阈值1ms、stack128/max256m；每2s温控，vm_stat/NMT前后。
- 分析测量时间窗内Owner互斥阶段/行级self热点、matcher和Lane业务与空转、线程CPU和>=1ms park/monitor、分配/GC/native；不把inclusive重复相加，不把JFR样本当调用计数或排队长度。通过门槛资金/状态PASS、offered=terminal业务/Core、unfinished0、无DataLoss；降频/换页时只做定位，不与无profiler吞吐作加速比较。生产未改，不重复功能/恢复套件；不承诺采样本身证明全部等待或长期泄漏，产物/tmp/owner128-profile结束提取清理，README不变。

### 128 窗口采样结果与源码归因

- 测量2026-09-11 13:19:59.413–13:20:59.430（上海），当前master4ce3bba8、生产ae02158a；对照commit不适用，仅当前master。节点启动SurprisingClusterNode，客户端JMH ClusterOperationalBenchmark.continuousOperations -f 1 -wi 0 -i 1 -p controlPageSize=0 -p inFlightWindow=128 -prof gc；其余JVM/业务参数见上述采集前锁定。未修改生产代码。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=923 businessHash=c08d609c05f740de；mixedCapacity=PASS elapsedSeconds=60.015 terminalBusinessOperations=14702455 offeredBusinessOperations=14702455 terminalCoreMessages=1414007 offeredCoreMessages=1414007 businessOpsPerSec=244977.838 coreMessagesPerSec=23560.717 fills=3496960 fillsPerSec=58267.663 queries=0 unfinished=0 peakInFlight=128 measuredCycles=683 triggerExecutions=0。节点结束highWaterMark128/pending0/windows1898556/commands1898556/dependencyFences1625/controlFences69920，后三项为全生命周期计数，非测量区间计数。
- 入口到终态延迟us（p50/p90/p95/p99/p99.9/max）：PLACE_ORDER requests349696：2787/6848/8302/10043/15867/25804；CANCEL_ORDER requests349696：2576/5021/6004/8089/15204/24788；APPLY_MARK_PRICE requests15223：3987/9756/11182/13877/21544/23445；PLACE_ORDER_BATCH requests524544/items10490880：7286/9175/10412/15548/23773/34832；CANCEL_ORDER_BATCH requests174848/items3496960：8028/10067/10559/15081/26165/34832。未新增accepted分段或CO修正。
- JFR ThreadCPULoad按16逻辑核换算单核利用率：Owner96.079%、matcher39.511%、Lane0/1/2/3为97.511/97.508/97.515/97.536%。Lane队列判断SettlementLaneWorker.run:154的self样本分别19433/22391、17444/22147、15553/21788、15043/21410（70.3–86.8%），该行是读取producerSequence的队列判断，不能把全部样本等同空队列，但明显不是账户业务方法；高CPU不代表Lane业务饱和。
- Owner20163执行样本按栈互斥分组：collection3071(15.23%)、publication2065(10.24%)、finishOther1272(6.31%)、pumpOther4070(20.19%)、commitOther2036(10.10%)、prefixOther838(4.16%)、other6811(33.78%)。优先级依次收集→发布→finish→pump→commit→prefix→other，避免inclusive重复相加；pump包含派发/准入推进，不能全部称为无效轮询。
- Owner具体self：progressCommandsInScope:230为829(4.11%，循环/前缀推进判断)、DeferredSessionResponses.poll:85为760(3.77%，返回处，不能据此认定响应背压)、drainLaneReadyNotifications:1610为757(3.75%)、TradingOrderBatchCodec.decodeCommand:294为572(2.84%)、TerminalTombstoneStore.indexEntity:87为346(1.72%)、CoreStateQueryCodec.utf8Length:342为201(1.00%)、AtomicLong.getAndAccumulate185(0.92%)、ClusterCommandWindow.complete:234为161(0.80%)。分段窗口complete扫描并非本轮最大热点。
- 结合源码：pump先drainMatchingCompletions再逐分区dispatchPartitionSettlements，后者处理批量结果/恢复证据/派发Lane；collectMatcherSettlement收集结果，OwnerCommitPublisher经commitTerminalToOwner及RuntimeFactIndexes.applyCurrent更新终态索引。它们是具体串行路径，不是单一锁阻塞。下一步候选为减少无进展时重复推进、减少Lane→Owner逐项终态/索引工作、压缩批量解码及响应编码分配；任何改造须保留依赖/提交顺序及查询可见性，不能直接删金融检查或版本发布机制。
- 阈值>=1ms事件中Owner无ThreadPark/JavaMonitorEnter，无记录到的Owner文件/网络IO事件；不能排除短等待/自旋/未启用事件。matcher的>=1ms park累计约603ms（按事件起始落入测量窗并裁剪尾部），栈在MatcherCommandPipeline.run:243，源码是无已提交命令时park；短park未计入，不能称总等待只有603ms。未采集在途阶段逐项计数，因此不能精确分解128个槽位各等待多少，也不能证明Owner是唯一瓶颈。
- 节点ObjectAllocationSample权重估计754.24MiB/s、3228.46B/终态业务操作（统计估计，非精确对象数）。线程权重：Owner6.241GB、matcher10.638GB、4Lane合计29.032GB、clustered-service1.556GB。主要类型long[]6.201GB、byte[]5.015GB、OrderRuntime4.906GB、NativeCommand2.305GB、Object[]2.265GB、MatcherResult2.139GB。首个业务栈归因热点LanePublishedMap.stage:81约3.579GB、OrderRuntime构造2.253GB、CoreMatchingResult.classify2.079GB、批量decodeCommand2.052GB、encodeResultSource1.521GB。归因是采样权重，受采样偏差影响；stage源码每次建立Version/Key、批解码建立数组/命令对象、响应编码分配输出buffer，均有实际分配，不能称零分配。
- 节点GCPhasePause159次，总951.563ms（约1.59%测量时长），p50/p95/p99/max=5.744/7.249/8.533/15.245ms；GC暂停不足以单独解释整个吞吐平台。heap committed512MiB，最后AfterGC used120.3MiB；短采样不证明泄漏与否。NMT全阶段reserved3142350→3153560KB、committed692122→733192KB；DirectBuffer首末8个/9.1MiB；线程首末22；metaspace committed25.9→26.0MiB。JIT Compilation1次549.52ms、deopt13，SafepointBegin164次/10.467ms/max0.322ms，VMOperation166次/957.554ms/max15.269ms；这些时间可嵌套不能与GC相加。CodeCache记录fullCount0。JFR DataLoss0。
- JMH主分数60.016s/op是整次工作负载调用；客户端gc.alloc.rate181.583MiB/s、gc.alloc.rate.norm24503838824B/JMH调用、gc.count315、gc.time248ms，内部setup/warmup影响口径，不能当成节点每笔订单分配或业务吞吐。
- 测量期29次温控CPU_Speed_Limit72–75，vm全阶段Swapins/Swapouts增量0/0、Pageins163015/Pageouts4315。节点CPULoad首末machine83.45/83.37%、JVM58.18/55.62%。因此本轮为诊断数据、非容量验收；不能与上一轮无profiler数字推断配置净收益，也不宣称全部线程有效饱和。未做六产品全场景/长稳/新恢复验证，原因是本轮仅对已有产物采样、没有生产变更。
- 原始node.jfr17713754B，SHA256=e81012d77ab285052d94bd5059f3dc8ad09f2946420f5185012ca6053ae0d5f8；client.log7998B，SHA256=9c93d03e3f9c675b068e3114d6eed0012a8f07a07886e39f990db9365e4ecac0。已生成jfr summary及基于RecordingFile的测量窗聚合；原始目录/tmp/owner128-profile将在提取后按用户要求删除，保留本记录中的汇总与校验，不保留可访问的原始采样链接。
- 清理完成：确认节点/客户端PID已退出，删除/tmp/owner128-profile共2085681685B（Archive、JFR、日志、临时脚本/分析器等），原始路径已失效。未产生新测试报告，未改README/生产代码/用户未跟踪文件；git diff --check通过。

## 2026-09-11 分配、收集与分阶段推进精简：采集前锁定

- 当前master基点5a3f16b5及本轮工作区修改，对照不适用，只验证当前代码。改动覆盖批量容器/响应编码、撮合占位证据及重复校验、Lane发布键、撤单重复查询、终态摘要、收集合并、通知分阶段推进。HotSpot GraalVM25.0.1/G1/NMT summary，i9-9880H8C16T/16GiB/macOS26.7；Java/Maven已核实。测试构建时磁盘508GiB；每组监测磁盘>10GiB、2s温控、vm/NMT前后，单组客户端最长300s，节点启动60s。
- 每次仅一个真实Aeron成员、网络/Archive/Core；1matcher、4Lane BUSY_SPIN、PIPELINED、Owner/global/session128（用户本轮要求覆盖历史固定256）；SHARED_NETWORK/serviceYIELDING/clientSHARED；node512m/1536m、client128m/512m。node JFR profile/2msExecutionSample/1msThreadPark与MonitorEnter/stack128/max256MiB。产物/tmp/owner-simplify-validation，提取后清理。
- 六产品依次执行ClusterProductLineGateMain execute、强制停止后日志恢复verify，并执行ClusterAccountControlBenchmark.accountControls -f1 -wi1 -i2 -p productLine=对应产品 -prof gc；每组独立目录，控制场景为同账户资金/模式/杠杆/逐仓保证金操作、非吞吐容量。另运行相关六产品快照恢复测试。
- U永续持续交易JMH ClusterOperationalBenchmark.continuousOperations -f1 -wi0 -i1 -p controlPageSize=0 -p inFlightWindow=128 -prof gc；内部预热30s/测量60s，无额外冷却。1769用户/256symbol/batch20/seed131001，1命令session+保留查询session，初始资金1768000000125、trading-stream=true/operational=false；业务配比与前轮相同。测量仅比较本轮线程工作、分配与延迟，不重跑旧代码。降频或换页时只做诊断，不作提升验收。
- 门槛：测试无失败、六产品Gate资金/订单/持仓核验通过、重启verify通过；持续交易offered=terminal业务及Core消息、unfinished0/fundsDiff0/状态PASS；JFR无DataLoss。验证终态摘要混合批次/扩容/复用、发布旧值可见性、协议字节一致性/边界/输入数组所有权、原生结果绑定前后不可变及序号、无新命令时异步完成。短采样不能证明无长期泄漏或三节点云端容量；保留最终不可变响应、发布版本、全局提交及恢复证据。

### 实现及正确性检查

- 三阶段统一完成后才启动外部验证。第一阶段：解码器独占的批量数组不再复制为第二套列表存储；外部集合仍防御复制；响应直接在限长输出窗口写成交记录，删除每项slice和重复order游标访问。撤单沿用同Lane已校验订单/预留，终态已携带元数据则不再次查表补写。撮合原生序号直接读取、空前缀共享、证据绑定复用既有分类/不可变集合、成功路径不拼接错误字符串；组合撮合所需原生结果与最终证据结果仍保持独立不可变，没有为了消除包装而提前发布可变对象。
- 第二阶段：更新发布表已有实体不分配新Key；保留Version及可见性。Lane在现有准备遍历中保存终态引用，Owner只遍历终态项；结算完成数、资金增量与同Lane结果收集合并遍历。全局索引/资金核验/发布水位顺序未改变，终态引用缓冲受事件生命周期约束并清空复用。
- 第三阶段：准入、结算分别按通知就绪探测；保留placeAdmissionReadyShardMask本地续跑。结算派发以matchingProgressSequence、PendingMatchingRing.dispatchRevision、throughSequence作为输入水位，无变化则跳过重复分区依赖扫描；队列增删/路由/依赖Lane/派发前缀变化均失效，保存检查前水位以便跨分区释放依赖后继续推进；直接poll取得matcher结果也走统一完成发布。未删除失败健康检查或确定性提交边界。
- 最终HotSpot25 Maven targeted package通过：protocol11+service313=324测试，0失败/错误/跳过。涵盖TradingOrderBatchCodecTest、LanePublishedMapTest、LaneTerminalSummaryTest、MatchingNotificationProbeTest、TradingStateSnapshotCodecTest、OrderBatchSettlementWaitTest、CoreNativeSnapshotProductLineTest、SurprisingClusteredServiceTest、SharedProductLineSnapshotContractTest、ProductRecoveryLifecycleTest、PendingMatchingRingTest、ClusterCommandPipelineTest、CoreOrderedOrderBatchTest、RuntimeCommitRecoveryTest、OrderBatchSlotReuseTest、DeterministicExchangeCoreAdapterTest。早期构建在测试编译阶段发现已移除hasTerminalOrders字段的旧断言，改为终态行为及扩容/复用验证后通过，不作为运行故障。
- 外部恢复启动异常如实记录：SPOT强杀后立即复用Driver目录触发ActiveDriverException；改为临时独立Driver目录、保留原业务Archive。随后U永续立即重启仍触发Archive active mark file保护；确认进程已退出后等待12s存活租约失效再恢复。两个失败启动未进入业务验证/容量结果；后续强杀恢复均等待租约，不改变持续发单节奏。已通过的execute不重做、不清空业务日志，继续验证原日志恢复。

### 统一外部验证结果

- 六产品Gate execute及强杀后原日志verify共12次PASS/fundsDiff0；六产品外部JMH账户控制均PASS。快照恢复由前述六产品测试覆盖，本轮外部重启验证为日志恢复，不冒充外部快照重启。控制JMH使用其既有客户端容量配置256且逐命令等待、实际在途1；前锁定的配套128仅在持续交易场景实现，因此控制结果只作功能/分配诊断，不作128并发容量结果。
- INVERSE_DELIVERY：JMH 3109.917724ms/调用；clientGC={'gc.alloc.rate': 0.9773646885064747, 'gc.alloc.rate.norm': 3225944.0, 'gc.count': 0.0}；accountControlVerify=PASS product=INVERSE_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；节点全生命周期JFR OwnerCPU=14.106%、allocationBytes=84486880、GCcount=4 GCtotalMs=9.596134；这些节点指标包含初始化/预热，不是仅JMH测量区间。
- INVERSE_PERPETUAL：JMH 3368.7271204999997ms/调用；clientGC={'gc.alloc.rate': 0.898991224625391, 'gc.alloc.rate.norm': 3191924.0, 'gc.count': 0.0}；accountControlVerify=PASS product=INVERSE_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；节点全生命周期JFR OwnerCPU=13.694%、allocationBytes=84593072、GCcount=4 GCtotalMs=10.919386；这些节点指标包含初始化/预热，不是仅JMH测量区间。
- LINEAR_DELIVERY：JMH 3156.355549ms/调用；clientGC={'gc.alloc.rate': 0.9630843019170943, 'gc.alloc.rate.norm': 3218000.0, 'gc.count': 0.0}；accountControlVerify=PASS product=LINEAR_DELIVERY terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；节点全生命周期JFR OwnerCPU=13.885%、allocationBytes=83773568、GCcount=4 GCtotalMs=10.8614；这些节点指标包含初始化/预热，不是仅JMH测量区间。
- LINEAR_PERPETUAL：JMH 3293.5526855ms/调用；clientGC={'gc.alloc.rate': 0.9186473014118037, 'gc.alloc.rate.norm': 3212624.0, 'gc.count': 0.0}；accountControlVerify=PASS product=LINEAR_PERPETUAL terminalBusinessOperations=2400 unfinished=0 fundsDiff=0 netPosition=0；节点全生命周期JFR OwnerCPU=13.360%、allocationBytes=85001328、GCcount=4 GCtotalMs=9.994362；这些节点指标包含初始化/预热，不是仅JMH测量区间。
- OPTION：JMH 2343.3177025ms/调用；clientGC={'gc.alloc.rate': 0.8770831629765129, 'gc.alloc.rate.norm': 2194580.0, 'gc.count': 0.0}；accountControlVerify=PASS product=OPTION terminalBusinessOperations=1800 unfinished=0 fundsDiff=0 netPosition=0；节点全生命周期JFR OwnerCPU=12.129%、allocationBytes=85267864、GCcount=4 GCtotalMs=9.6333；这些节点指标包含初始化/预热，不是仅JMH测量区间。
- SPOT：JMH 859.46783ms/调用；clientGC={'gc.alloc.rate': 1.4351252447169918, 'gc.alloc.rate.norm': 1365244.0, 'gc.count': 0.0}；accountControlVerify=PASS product=SPOT terminalBusinessOperations=600 unfinished=0 fundsDiff=0 netPosition=0；节点全生命周期JFR OwnerCPU=无采样、allocationBytes=77571520、GCcount=4 GCtotalMs=9.846789000000001；这些节点指标包含初始化/预热，不是仅JMH测量区间。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=788 businessHash=153ab785c5c05a19
- mixedCapacity=PASS elapsedSeconds=60.047 terminalBusinessOperations=12681074 offeredBusinessOperations=12681074 terminalCoreMessages=1221490 offeredCoreMessages=1221490 businessOpsPerSec=211185.266 coreMessagesPerSec=20342.180 fills=3015680 fillsPerSec=50221.865 queries=0 unfinished=0 peakInFlight=128 measuredCycles=589 totalCycles=788 triggerExecutions=0
- business=PLACE_ORDER items=301568 requests=301568 p50us=3192 p90us=7790 p95us=9502 p99us=12017 p999us=25935 maxus=38797
- business=CANCEL_ORDER items=301568 requests=301568 p50us=2996 p90us=5812 p95us=7081 p99us=11567 p999us=29458 maxus=37421
- business=APPLY_MARK_PRICE items=15218 requests=15218 p50us=4321 p90us=8749 p95us=10174 p99us=18071 p999us=25935 maxus=34734
- business=PLACE_ORDER_BATCH items=9047040 requests=452352 p50us=8273 p90us=10960 p95us=13049 p99us=18923 p999us=29507 maxus=44990
- business=CANCEL_ORDER_BATCH items=3015680 requests=150784 p50us=9256 p90us=11788 p95us=12779 p99us=17498 p999us=28524 maxus=40337
- 连续测量13:54:52.931–13:55:52.980（上海）；节点highWaterMark128/pending0/windows1622076/commands1622076/dependencyFences1620/controlFences109635（节点计数含预热）。JMH整次调用60.047680022s，clientGC alloc154.2686MiB/s、20943691352B/JMH调用、269次/246ms；不可误作每笔业务分配。
- 连续区间OwnerCPU95.488%、matcher36.150%、Lane0/1/2/3为97.494/97.495/97.508/97.518%；Owner20244样本互斥阶段collection15.09%、publication9.97%、pumpOther22.75%、commitOther10.57%、finishOther6.12%、prefixOther5.00%、other30.50%。pump包含有效派发，非全部空转；Lane大量self仍在队列判断，不能宣称业务饱和。
- 连续节点采样分配37942857024B，估计602.59MiB/s、2992.09B/终态业务操作。类型权重long[]5.293GB、OrderRuntime4.173GB、byte[]3.401GB、Object[]1.837GB、Version1.691GB、NativeCommand0.982GB、MatcherPrefix0.564GB、Key0.429GB；对象权重不是精确对象数。Owner分配5.359GB、matcher6.751GB，仍有必要状态/结果分配，不宣称零分配。
- Owner首要self为MatcherPipelineGroup.drainMatchingCompletions:74（1016/20244=5.02%）、progressCommandsInScope:230（904=4.47%）、TradingCommandCodec.decodePlaceOrder:251（507=2.50%）、TerminalTombstoneStore.bucket:99（439=2.17%）；窗口complete153=0.76%。仍存在串行收集/推进成本，三阶段删减没有证明吞吐瓶颈全部解决。
- 连续节点GC127次、总857.037ms（1.43%）、p50/p95/p99/max6.507/8.954/11.030/12.094ms；GC后heap首末109.4/109.2MiB。NMT全阶段reserved3144392→3153177KB、committed692068→732965KB；DirectBuffer8个/9.1MiB首末不变。编译1次132.037ms、deopt11、SafepointBegin132次9.696ms、VMOperation134次862.503ms，嵌套时间不可相加。Owner仅1次4.41ms park，栈为runOwner→BackoffIdleStrategy（请求100us），非资金逻辑同步等待；无Owner记录IO或MonitorEnter，未覆盖短于1ms的等待。
- 正式区间30个温控样本CPU_Speed_Limit64–72；续跑全阶段vm增量Swapins0/Swapouts0/Pageins1939256/Pageouts6599。所有已分析JFR DataLoss0。正式吞吐211185.266业务ops/s、20342.180Core消息/s、50221.865fills/s，unfinished0/fundsDiff0；受降频/换页影响，本轮只作诊断，不能据此宣称净吞吐提升或退化，未完成长期泄漏/稳定满频容量验收。
- 被测产物 surprising-aeron-service.jar SHA256=fa429392c82208a0ea93d04c8c329bbff001f1a3f07cd92ae02983b68ac40c80
- 被测产物 product-core-benchmarks.jar SHA256=1213187c3c6ebec7d050bb62aab66fc82ddcbe3e113adff238294017c9a0b269
- 原始JFR均已生成jfr summary及RecordingFile聚合；清理前文件大小/SHA256如下（全部位于/tmp/owner-simplify-validation，清理后不可访问）：
  - INVERSE_DELIVERY/control/node.jfr 2073488B e8ad6e4ee08d7e295791269bcf6def6f4034473f578a42c85aa7501d74b151c0
  - INVERSE_DELIVERY/node.jfr 1826104B add3e75bb3d262e687dcd55208bb6414a4e1f0b689a9cf27b5cfc7e99d93ff0c
  - INVERSE_DELIVERY/replay.jfr 1039981B 8e42406b8621d08175ed1b4e5c100cb9dabd56f28926d3cc10b998456be5d10f
  - INVERSE_PERPETUAL/control/node.jfr 2049631B ed06bed438d1c2857feb69885f08c2016adb00a36022c771893f927b0d5b6016
  - INVERSE_PERPETUAL/node.jfr 1089174B 24a0bbe029ddaeaa23889a269f0b6619dd64294fc01e8b5f2e1500ec220537f8
  - INVERSE_PERPETUAL/replay.jfr 1032116B 63d22de1a4766d87b78ea16a96cda5ff5e6c9ceed596907bfe37e63e40d2d45a
  - LINEAR_DELIVERY/control/node.jfr 2052087B 8f9b69795ec5f9a00f4b868eab6c2af143c47d30813bba87b039ad9d6b2fd50b
  - LINEAR_DELIVERY/node.jfr 1677285B e9f58bf2f4ee26614975eb15e55a1bd17d871c39cb46bb97a983aa8821477f13
  - LINEAR_DELIVERY/replay.jfr 1007873B 38be4910aa68b4a2ac9db9521cd73a1cc6aaea5a05ed2d77b8190a0b6603f314
  - LINEAR_PERPETUAL/control/node.jfr 2062609B 860b5642b2a49d727c64378609a41f2d5799e96342f95c5d64ac59c13da8c778
  - LINEAR_PERPETUAL/node.jfr 1067820B 721de3a3f7b7680b0f455bfb9e11df0de76479ad8c0b15050cd7fa694b2955b5
  - LINEAR_PERPETUAL/replay.jfr 1025357B 0cef10a26ab42eaffc801483ef865b39e2b987c06c151a861026609f3627785c
  - OPTION/control/node.jfr 1843428B 255ed4615519c6710013e95d169b36a5368107f82f500b6efe835b6ed06d2876
  - OPTION/node.jfr 1735286B 507a456e6ab5036d6d346f9d450f3a1bb2808d6dd2cc033f3a14f7dae3297901
  - OPTION/replay.jfr 1050979B b88228035bedf0be8be1894be65fc0def0d027af5edc78c95e6bc409efb36291
  - SPOT/control/node.jfr 1253303B d508fb33b966e4da4d6694dd1fd366d199569fd3024189d553f9e38bf7fa4847
  - SPOT/node.jfr 959159B 897292fb153ec2b302faafab9355e4154d7244dd5279e9558a0458cfa8a724df
  - SPOT/replay.jfr 979520B 8fb6976699e5750045f8c3975260eafff3b4bb7d686c719536e25a9105d8b142
  - continuous/node.jfr 16831717B 0473cfb1928d0b6c181824c59ac7e4f048284968efa3ca68c95a4604c67069f2

- 已确认本轮全部节点/客户端/runner退出；删除/tmp/owner-simplify-validation共6258532850B及32份本轮测试报告470632B、三份构建日志。原始路径已失效，保留上述汇总与SHA；现有JAR/用户未跟踪文件保留，README未改，提交前git diff --check通过。功能及恢复验证通过，受限速影响性能仅部分验证，不宣称长期无泄漏或吞吐瓶颈已消除。

### 2026-09-11 余额标记、ADL 成员和捕获清理优化：采集前锁定

- 被测 master 基于 edf0709828cc03366b7e3650115327dccf342ff8 加本轮修改；对照 commit 不适用（仅验证当前 master）。删除余额变更明细副本，保留 dirty；ADL 保留非零同资产成员但更新最新值；合并捕获清理遍历、空余额缓冲跳过和 primitive 客户单槽位免清零。先全部完成并通过功能测试，再采样。
- HotSpot GraalVM25.0.1/Maven3.9.16，macOS26.7 i9-9880H 8C16T/16GiB；真实单成员 localhost、网络/Archive/Core 保留，1 matcher/4 Lane、PIPELINED/BUSY_SPIN、settlement-spin-limit=0、Owner128。节点 G1 Xms512m/Xmx1536m/NMT summary，SHARED_NETWORK、service idle YIELDING；客户端 G1 Xms128m/Xmx512m、SHARED。JFR profile、ExecutionSample2ms、park/monitor1ms、stackdepth128/maxsize256m；额外 profiler 开销仅用于归因。
- 六产品线逐个执行 ClusterProductLineGateMain execute，再停止并等待12s标记租约后以新 media 目录、原 Archive 重启 verify；每次只有一个节点。六产品线独立数据运行更新后的 ClusterAccountControlBenchmark.accountControls：f1/wi1/i2/threads1/-prof gc，同账户依赖命令串行完成，client capacity256但实际1在途；100轮余额±10、衍生品模式/杠杆/保证金控制，3账户各1000000，持仓±10，校验每账户数量及总资金/净持仓。此场景不作为128持续容量指标。
- 连续 U 永续 ClusterOperationalBenchmark.continuousOperations：f1/wi0/i1/-prof gc，内部预热30s/测量60s，Owner/global/session128、controlPageSize0、seed131001、mixed-trading-stream=true/mixed-operational=false；1769账户、256币对、1命令会话+查询会话，batch20、初始资金1768000000125，持续异步填满窗口；每周期普通下单/撤单512/512、批量下单/撤单15360/5120 items、成交5120，mark1s。不修正 coordinated omission，不属于固定到达率模型。前后核对 funds/population/positions/reservations/loss；本轮不测持续查询和触发执行。
- 正确性通过阈值：测试零失败；六线 gate execute/replay/control PASS；资金差0、逐账户持仓匹配、accepted=terminal、unfinished0、无超时/交易异常、JFR DataLoss0。性能仅诊断，不预设必然提升；采样报告业务ops/Core消息/fills、各类型尾延迟、线程热点/分配/GC/NMT/等待。温控限速、swap、数据丢失使容量结果无效；未覆盖长期泄漏和云端三节点，不作容量验收。
- 临时产物 /tmp/owner-publish-validation，按每2s温控、前后vm_stat/NMT及磁盘采集；磁盘<10GiB停止。结束后摘要/SHA写本记录，停止节点、清理本轮Archive/JFR/日志/测试报告；不修改README。
### 2026-09-11 本轮结果

- HotSpot25构建成功，115项针对性测试全部通过（含新增ADL成员/失配保护、余额dirty/发布、primitive缓冲复用，以及六线Snapshot/恢复、批量提交/回滚）。六产品线真实单成员execute、原Archive重启verify、账户控制JMH全部PASS；资金差0、逐账户持仓和净持仓正确。测试命令：mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=AdlPositionIndexTest,CaptureBufferReuseTest,TradingRuntimeStateTest,RuntimeChangedIndexCommitTest,RuntimeCommitRecoveryTest,ProductRecoveryLifecycleTest,CoreNativeSnapshotProductLineTest,SharedProductLineSnapshotContractTest,CoreOrderedOrderBatchTest,OrderBatchSettlementWaitTest,OrderBatchSlotReuseTest,LaneTerminalSummaryTest -Dsurefire.failIfNoSpecifiedTests=false package。
- 控制JMH按SPOT/U永续/币永续/U交割/币交割/OPTION排序：单次100循环ms为835.844/3140.608/3301.600/3322.390/3337.033/2392.968；client分配MiB/s为1.388/1.003/0.917/1.081/1.079/1.008，B/JMH调用1271496/3339172/3210912/3809088/3822304/2569268，均0 GC。f1/wi1/i2不足有效置信区间；不将整次循环B/op当单笔业务分配。控制节点JFR包含启动和setup，不作为稳定吞吐数据。
- 连续测量上海14:23:06.191–14:24:06.245；elapsed60.052s，offered/terminal业务11842360、Core消息1141560，197201.331终态业务ops/s、19009.484Core消息/s、2816000fills（46892.591/s）；unfinished0、峰值在途128、measuredCycles550/totalCycles788、queries0/triggerExecutions0。fundsDiff0/population/hftPositions/reservations/loss全部true；businessHash230b0179bfe34067。节点含预热highWater128/pending0/windows1622076/commands1622076/dependencyFences1702/controlFences61475。
- 入口至终态延迟us（p50/p90/p95/p99/p999/max）：普通下单281600请求3446/8261/10174/12689/27295/40632；撤单281600请求3131/5947/7217/12296/22233/44498；mark15160请求5746/12697/14508/18235/22577/28983；批量下单422400请求8448000items为8945/11345/13811/19349/33374/44662；批量撤单140800请求2816000items为10149/12378/12967/18890/34603/44367。批量固定20。未单独拆分accepted段、未修正coordinated omission，非尾延迟容量验收。
- 连续JMH整次调用60.052610881s，client分配153.6784MiB/s、20928705168B/JMH调用、269GC/264ms；f1/i1无有效置信区间。节点采样分配权重34511582984B，约548.05MiB/s、2914.25B/终态业务操作；Owner4.905GB、matcher5.692GB、各Lane约5.66GB。主类型long[]4.665GB/OrderRuntime4.286GB/byte[]3.069GB/Object[]1.732GB/ReservationRuntime1.593GB/Version1.556GB；主业务站点LanePublishedMap.stage2.541GB、place适配2.158GB、decodePlaceOrder1.679GB。权重非精确对象数，未启用逐对象TLAB事件，不宣称零分配。
- Owner CPU95.989%、matcher36.467%、四Lane97.573/97.573/97.586/97.609%；节点进程CPU占全机56.674%，机器88.668%。Owner精确按trading-owner-线程前缀统计，排除路径名含owner的Aeron线程。20186个Owner样本：collection2924（14.49%）、publication1833（9.08%）、pumpOther4719（23.38%）、commitOther2178（10.79%）、finishOther1390（6.89%）、prefixOther587（2.91%）、other6555（32.47%）；阶段互斥，pump含有效工作。self主要drainMatchingCompletions1072（5.31%）、progressCommandsInScope823（4.08%）、decodePlaceOrder496（2.46%）、CHM.get462（2.29%）、tombstone.bucket397（1.97%）。clearChangedKeys inclusive394（1.95%）、ADL.apply3（0.015%），未采到markBalancesChanged，不等于零耗时。四Lane合计87819个样本，队列检查run:154占68056（77.49%），不能将97%忙旋CPU误判有效工作饱和。
- Owner测量窗口未记录ThreadPark、MonitorEnter、File/Socket IO（park/monitor阈值1ms）；其他线程的park不能归因Owner。19份JFR DataLoss均0。节点116次youngGC，总816.942ms（1.36%），pause p50/p95/p99/max6.955/8.635/10.178/10.550ms；GC后heap111.68→117.92MiB，60秒不能证明无泄漏。Direct8个、9575136B首末及峰值不变；活跃线程22不变；metaspace used26625568→26688312B。无采样窗口Compilation事件、deopt5；SafepointBegin121次8.866ms、VMOperation123次822.048ms，与GC嵌套不相加。
- NMT节点启动后至结束reserved3144370→3156100KB、committed692054→732064KB；测量窗口JFR native reserved首3227546575/末3221031495/峰3228846544B，committed首746320847/末740072007/峰747887056B。全流程vm_stat Swapins/Swapouts增量0/0，Pageins2384134/Pageouts1999（非仅测量窗口）；测量29个温控读数64–66，系统上下文切换采样首190103/末180840每秒。明显降频，本轮仅功能和热点诊断，不宣称吞吐净提升/退化、稳定满频容量、长期无泄漏；未测云端三节点、持续查询/触发和长期native池余额。
- 本轮没有改金额计算、提交顺序或Lane发布可见性。清理保留原余额发布边界，仍遍历配置的Lane数量，仅合并遍历及跳过空缓冲，不宣称已实现按触达Lane位图清理。实现没有添加跨Lane共享dirty位图，避免为小开销引入额外同步协议。
- service JAR SHA256 04ea7a274cc2a9e06a26777ac912234b3141022ff82504e2b070ccbafc9c3a8b；benchmark JAR SHA256 09688fc554bf3104180bb2ce6619ec946d54e910666c30fa18e0516555ac8f57。构建后仅删除一行重复注释，业务字节码未变。JFR均生成summary和RecordingFile聚合；临时profile工具先按线程名子串误纳入路径含owner的Aeron线程，发现后改为trading-owner-前缀并重新离线分析全部记录，上述结果均为修正后，无重跑负载。
- 清理前JFR清单（相对于/tmp/owner-publish-validation，依次为bytes/SHA256；清理后路径失效）：
  - INVERSE_DELIVERY/control/node.jfr 2591392 1e95c32555dbd8a61179a53e186b1aaf7bc41116509fa488dfbb2374f1d51d53
  - INVERSE_DELIVERY/node.jfr 2168150 07d37def4b209911c8a3b2470e6f91f488ae14c987d48b8a4c2bc42467f8cb71
  - INVERSE_DELIVERY/replay.jfr 1539240 e33e868d074fbbaa9ab7d528d3a6e0c2658e61471d7f7c602b51b6511afebfde
  - INVERSE_PERPETUAL/control/node.jfr 2592624 13d6dee70d8c0aad855c92f372adca92a39bbb45047333680888462d9d4c0b8c
  - INVERSE_PERPETUAL/node.jfr 1532709 820b8865ad778e9de12126ab10871ced159c8623c033828892bd4bf27d3edfa5
  - INVERSE_PERPETUAL/replay.jfr 1573261 422da343b1afa5cdd2e39bc6aadf397ce41c300a5e3e13ea3a47edeb25eb09a8
  - LINEAR_DELIVERY/control/node.jfr 2599344 cabcf8a0a0ead47f702e2273fab275628e6cc245c5aea3623a7be9c1906f0594
  - LINEAR_DELIVERY/node.jfr 2163133 3e4d695b8d4fff504a634e018ecc24a7a9e75337463e22e83435eefdb1b6bd40
  - LINEAR_DELIVERY/replay.jfr 1554376 e3232b144be08c551477e3724d63bf8c6647897dfbfec99a7354b2204d6db20c
  - LINEAR_PERPETUAL/control/node.jfr 2582334 4b40799103fa5ff7355140b88f2d9da6c6c14343d24b8bd1adddacd4185f532d
  - LINEAR_PERPETUAL/node.jfr 1569037 52bfa1cbde757059a34d5c2df3c8086d55b70bd0103b0c53d671a7af082149e4
  - LINEAR_PERPETUAL/replay.jfr 1592428 1e6925e77240f3b305640de7e63efa2a47c262e16c307a14e24db98350a2b930
  - OPTION/control/node.jfr 2341171 93cc8178a6f2db40611c1eb67ac8cbd85e8a666dd8db59946108c6b6d288d3e8
  - OPTION/node.jfr 2161160 87de9c8e725441bd1f604144a6a34789417349a9eba36d1182f33625e97c7c0d
  - OPTION/replay.jfr 1560260 22ff8eb1ae02ebfdace82bd02d3cb83f4c215ee4af0fb7fb3e6a5843aaa7e03c
  - SPOT/control/node.jfr 1756108 a2c0b4f71db19c79b55fc695e03e3d0bcabbaf23e385585893a4d1f111b8ff85
  - SPOT/node.jfr 1436917 0e1dfcbdcff67a8eb1cb8f0edec4d41650d6912d4f753ac89eb8198fcd43aabf
  - SPOT/replay.jfr 1523952 2320e796bf346c09c74842a8daf19804aba141ff4abeb8c4d5d4ce6c57de5aa4
  - continuous/node.jfr 16335378 27afc12a96e4204431bc7a2e33226b7c096bb45a2c3013cbc42a1dea0f09f971
- 已确认19次启动的节点全部退出、runner/JMH完成；清理本轮临时目录4414923614B、24份测试报告260735B和构建日志58490B。上述原始路径不再可访问。用户未跟踪文件保留，README未改，git diff --check通过；功能验证通过，性能为受降频影响的部分验证。

## 2026-09-11 Google Cloud 16vCPU/32GiB 单成员 64/128：采集前锁定

- 用户明确要求重新创建云实例，采用本机真实单成员验证方式，Owner/global/session窗口64和128分别验证；覆盖历史停止云测试及固定256窗口约定。项目surprising-ae591，实例aeron-single-perf-20260911，asia-southeast1-b，c2d-highcpu-16，AMD EPYC 7B13、8核/16线程、32GiB，100GB pd-ssd，Ubuntu24.04/kernel7.0.0-1011-gcp。网络沿用surprising-validation，IAP SSH；无实例服务账号。仅一个真实Aeron成员，localhost UDP、Archive和Core保留；客户端另一个JVM同机，不作三成员或跨机复制容量结论。
- 当前master182bbbbf1cd7418b6019700290ca5604b60956c0，业务提交87ac8d37；不改生产代码、不跑旧版。沿用本轮前已通过115测试/六产品真实单成员execute/replay/control的JAR，service SHA256=04ea7a274cc2a9e06a26777ac912234b3141022ff82504e2b070ccbafc9c3a8b，bench=09688fc554bf3104180bb2ce6619ec946d54e910666c30fa18e0516555ac8f57；远端重新校验。CodeGraph CLI返回旧包路径且sync称无变化，本轮结构定位结果不适用于当前文件，按已定位的实际文件读取，未改索引。
- JDK为Oracle GraalVM HotSpot25.0.1，执行前检查java及mvn实际版本。G1，node Xms512m/Xmx1536m/NMT summary，client Xms128m/Xmx512m；1matcher/4Account Lane、BUSY_SPIN/spin0，Owner流水线、SHARED_NETWORK/service YIELDING/client SHARED，默认风险引擎0。线程数与等待策略两组完全相同，不绑核，不增加线程。共享物理核/调度干扰须记录。
- U永续连续混合交易：ClusterOperationalBenchmark.continuousOperations，controlPageSize0、seed131001、1769用户/256symbol、batch20、1命令session+1查询session，mixed-trading-stream=true/operational=false，初始资金1768000000125；内嵌持续做市，周期普通下/撤512/512，批下15360项/撤5120项，5120fills，mark1s。异步FIFO填满相应窗口，只有背压及业务顺序约束；closed-loop且不修正coordinated omission，不以此宣称固定到达率尾延迟验收。
- 顺序64 baseline、128 baseline、64 profile、128 profile，各轮全新目录/全新状态，JMH f1/wi0/i1/threads1，内部预热30s、测量60s、边界排空与核验，无额外冷却。baseline不带JFR/GC profiler；profile client -prof gc、node JFR profile自定义ExecutionSample2ms/NativeMethodSample2ms/stack128，park/monitor1ms、TLAB及outsideTLAB事件、ThreadAllocationStatistics1s、NMT/DirectBuffer1s，JFR上限256MiB。profiling值只做归因；每档单轮没有有效CI，若波动/预热不足仅报告诊断结果，不预设吞吐提升阈值。
- 通过条件：mixedCapacity/mixedVerify PASS，offered=terminal业务和Core消息、unfinished0、fundsDiff0/population/hftPositions/reservations/loss全部true，无超时/异常，实际窗口/线程配置匹配；JFR DataLoss0、无swap/throttling，磁盘剩余>10GiB（运行中2s检查，低于阈值终止）。记录每类入口到终态p50/p90/p95/p99/p999/max、计数、峰值在途及期末pending。accepted阶段未单独测量不得宣称完整三段延迟。
- 指标：系统CPU/steal、进程/线程CPU、调度/上下文切换、RSS/缺页/swap/FD、磁盘IO、GC日志和heap、NMT前后、JFR热点self/inclusive/分配类型线程站点/TLAB/堆外/GC暂停/JIT/deopt/safepoint/锁/IO/异常；Owner只匹配trading-owner-前缀。区分Lane空转与有效execute、matcher等待与撮合，分配sample权重不是精确对象数。无代码更改，本轮不重复六产品恢复；未测长期泄漏、完整native池余额、持续查询/触发/六产品并发和三节点。
- 临时路径本机与云端/tmp/aeron-perf-20260911；先记录摘要、配置、SHA及异常，再清理本轮Archive/JFR/日志等大文件并停止测试进程。结束停止该专用VM，保留实例供用户后续使用；实例磁盘存在状态明确交付，不持续运行发压。

### 启动异常及重试锁定

- 首次64 baseline在Archive初始化前因缺少java.base/java.util.zip开放参数退出（Crc32反射InaccessibleObjectException），未启动客户端、未采集性能数据，不算业务失败。修正所有节点/客户端统一添加--add-opens=java.base/java.util.zip=ALL-UNNAMED，保留jdk.internal.misc、sun.nio.ch开放及enable-native-access；其余上条锁定参数不变，四轮从全新目录重新执行。原失败目录标记bootstrap-failed，清理前保留错误摘要。包安装后台任务必须结束后才启动正式负载。

### 采样干扰发现与无测量中attach补充轮：采集前锁定

- 前四轮业务/资金均通过。离线JFR确认测量开始后为关联线程名执行的jcmd Thread.print产生PrintThreads VM operation停顿：64 profile 34.853688ms，128 profile 22.328590ms（均超过业务p99）；因此前四轮尾部max不能用作无干扰容量证据。原始结果保留，不删除事件、不从直方图扣除停顿。JFR离线解析器首次遇到native线程无Java线程名报错，已空值分组修复并重新分析原JFR，未重跑负载或损坏原数据。
- 新增w64-clean、w128-clean，仍同一当前JAR、同机器/JVM/GC/NMT/用户/币对/做市/资金/窗口/30s预热60s测量/JMH f1/wi0/i1，均不加JFR及gc profiler；仅2s读取/proc，测量期间不执行jcmd，线程转储移到客户端完成核验后，NMT仍前后采集。两轮顺序、新目录、新状态；上条所有正确性/磁盘/有效性条件保持不变。最终主吞吐及尾延迟以这两轮为准，前四轮只作诊断。每档仍只有一次测量，无置信区间/长期稳定性结论。

### 云端各轮完整结果（按执行顺序）

#### w64-baseline

- 测量UTC 2026-09-11T06:48:14.886000+00:00 至 2026-09-11T06:49:14.909000+00:00；上海时间为UTC+8。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1084 businessHash=43b64abdf511db14
- mixedCapacity=PASS elapsedSeconds=60.022 terminalBusinessOperations=16035840 offeredBusinessOperations=16035840 terminalCoreMessages=1541120 offeredCoreMessages=1541120 businessOpsPerSec=267164.192 coreMessagesPerSec=25675.741 fills=3814400 fillsPerSec=63549.592 queries=0 unfinished=0 peakInFlight=64 measuredCycles=745 totalCycles=1084 triggerExecutions=0
- business=PLACE_ORDER items=381440 requests=381440 p50us=1127 p90us=2240 p95us=3467 p99us=4665 p999us=6221 maxus=9256
- business=CANCEL_ORDER items=381440 requests=381440 p50us=1048 p90us=1817 p95us=2592 p99us=4114 p999us=5799 maxus=8519
- business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=1631 p90us=5074 p95us=6066 p99us=7475 p999us=10338 maxus=10649
- business=PLACE_ORDER_BATCH items=11443200 requests=572160 p50us=3229 p90us=4411 p95us=5083 p99us=7421 p999us=10289 maxus=59342
- business=CANCEL_ORDER_BATCH items=3814400 requests=190720 p50us=4435 p90us=5214 p95us=5808 p99us=6717 p999us=8212 maxus=13164
- 延迟单位us，顺序p50/p90/p95/p99/p999/max，items与requests分开；batch固定20。
- Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=64 pending=0 windows=2228284 commands=2228284 dependencyFences=1601 controlFences=50957（节点计数含初始化/预热）。
- 系统采样区间56.542s/29点，整机CPU 68.370%、iowait 0.377%、steal 0.000%；VM增量{'pswpin': 0, 'pswpout': 0, 'pgfault': 470235, 'pgmajfault': 0, 'pgpgin': 0, 'pgpgout': 1940676}；最小剩余磁盘88.97GiB。
- 节点CPU 861.873%（100%为1逻辑核，整机16逻辑核）；RSS首/末/峰值1707.49/1691.86/1722.14MiB，minor/majorFault=365876/0，FD首末41/41，线程数首末46/46；read/write_bytes增量0/2284224512B。
- 线程CPU/voluntary switches/involuntary switches/scheduler等待ms（操作系统计数，nid关联完整线程名）：core-account-lane-3 98.829%/207/129/1.871；core-account-lane-0 98.812%/214/91/2.854；core-account-lane-1 98.812%/205/91/1.654；core-account-lane-2 98.812%/221/94/1.711；trading-owner--1 96.795%/11201/614/21.812；core-matcher-0 34.753%/620492/557/140.059。
- Aeron/外围线程CPU：clustered-service-101-0 98.829%；/tmp/aeron-perf-20260911/w64-baseline/media-surprising-linear_perpetual-0 [sender,receiver] 94.868%；driver-conductor 57.975%；consensus-module-101-0 37.636%；archive-conductor 33.214%。
- NMT nmt-after.txt: Total: reserved=3143927KB, committed=711719KB; nmt-before.txt: Total: reserved=3128007KB, committed=668519KB；覆盖节点就绪至客户端验证结束，非仅测量段。
- GC日志测量段：{'young': 160}，pause总624.158ms（1.040%），p50/p95/p99/max=3.765/5.252/5.662/5.715ms。

#### w128-baseline

- 测量UTC 2026-09-11T06:49:59.936000+00:00 至 2026-09-11T06:50:59.970000+00:00；上海时间为UTC+8。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1110 businessHash=cf1a074d11e70436
- mixedCapacity=PASS elapsedSeconds=60.034 terminalBusinessOperations=16852992 offeredBusinessOperations=16852992 terminalCoreMessages=1618944 offeredCoreMessages=1618944 businessOpsPerSec=280721.837 coreMessagesPerSec=26966.899 fills=4008960 fillsPerSec=66777.615 queries=0 unfinished=0 peakInFlight=128 measuredCycles=783 totalCycles=1110 triggerExecutions=0
- business=PLACE_ORDER items=400896 requests=400896 p50us=2383 p90us=5873 p95us=7303 p99us=9166 p999us=12443 maxus=27721
- business=CANCEL_ORDER items=400896 requests=400896 p50us=2246 p90us=4179 p95us=5119 p99us=6995 p999us=10141 maxus=16269
- business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=3528 p90us=8396 p95us=9830 p99us=12410 p999us=25870 maxus=27639
- business=PLACE_ORDER_BATCH items=12026880 requests=601344 p50us=5959 p90us=8445 p95us=9576 p99us=12173 p999us=15171 maxus=25100
- business=CANCEL_ORDER_BATCH items=4008960 requests=200448 p50us=7917 p90us=9805 p95us=10641 p99us=13664 p999us=16048 maxus=29196
- 延迟单位us，顺序p50/p90/p95/p99/p999/max，items与requests分开；batch固定20。
- Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=128 pending=0 windows=2281532 commands=2281532 dependencyFences=1703 controlFences=72248（节点计数含初始化/预热）。
- 系统采样区间58.554s/30点，整机CPU 68.236%、iowait 0.449%、steal 0.000%；VM增量{'pswpin': 0, 'pswpout': 0, 'pgfault': 446872, 'pgmajfault': 0, 'pgpgin': 0, 'pgpgout': 2014460}；最小剩余磁盘86.76GiB。
- 节点CPU 863.735%（100%为1逻辑核，整机16逻辑核）；RSS首/末/峰值1718.42/1706.13/1783.45MiB，minor/majorFault=296756/0，FD首末41/41，线程数首末46/46；read/write_bytes增量0/2102878208B。
- 线程CPU/voluntary switches/involuntary switches/scheduler等待ms（操作系统计数，nid关联完整线程名）：core-account-lane-0 98.798%/225/100/3.074；core-account-lane-1 98.798%/225/102/5.112；core-account-lane-3 98.798%/218/93/1.798；core-account-lane-2 98.781%/224/110/4.157；trading-owner--1 97.739%/6489/376/15.240；core-matcher-0 35.181%/666087/308/156.427。
- Aeron/外围线程CPU：clustered-service-101-0 98.764%；/tmp/aeron-perf-20260911/w128-baseline/media-surprising-linear_perpetual-0 [sender,receiver] 93.743%；driver-conductor 58.288%；consensus-module-101-0 37.214%；archive-conductor 32.380%；JVMCI-native CompilerThread0 1.844%。
- NMT nmt-after.txt: Total: reserved=3156688KB, committed=712688KB; nmt-before.txt: Total: reserved=3122523KB, committed=668027KB；覆盖节点就绪至客户端验证结束，非仅测量段。
- GC日志测量段：{'young': 170}，pause总678.419ms（1.130%），p50/p95/p99/max=3.904/4.782/5.977/6.267ms。

#### w64-profile

- 测量UTC 2026-09-11T06:51:43.379000+00:00 至 2026-09-11T06:52:43.401000+00:00；上海时间为UTC+8。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1037 businessHash=89e1eed4c6f68a35
- mixedCapacity=PASS elapsedSeconds=60.022 terminalBusinessOperations=15326208 offeredBusinessOperations=15326208 terminalCoreMessages=1473536 offeredCoreMessages=1473536 businessOpsPerSec=255341.105 coreMessagesPerSec=24549.733 fills=3645440 fillsPerSec=60734.572 queries=0 unfinished=0 peakInFlight=64 measuredCycles=712 totalCycles=1037 triggerExecutions=0
- business=PLACE_ORDER items=364544 requests=364544 p50us=1176 p90us=2551 p95us=3694 p99us=4870 p999us=6004 maxus=11739
- business=CANCEL_ORDER items=364544 requests=364544 p50us=1145 p90us=1940 p95us=2680 p99us=4126 p999us=6320 maxus=38797
- business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=2379 p90us=6062 p95us=7180 p99us=8396 p999us=10657 maxus=13484
- business=PLACE_ORDER_BATCH items=10936320 requests=546816 p50us=3327 p90us=4718 p95us=5410 p99us=7766 p999us=9879 maxus=39321
- business=CANCEL_ORDER_BATCH items=3645440 requests=182272 p50us=4661 p90us=5517 p95us=5844 p99us=6361 p999us=7512 maxus=12238
- 延迟单位us，顺序p50/p90/p95/p99/p999/max，items与requests分开；batch固定20。
- Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=64 pending=0 windows=2132028 commands=2132028 dependencyFences=1643 controlFences=40126（节点计数含初始化/预热）。
- 系统采样区间56.564s/29点，整机CPU 67.619%、iowait 0.350%、steal 0.000%；VM增量{'pswpin': 0, 'pswpout': 0, 'pgfault': 394863, 'pgmajfault': 0, 'pgpgin': 0, 'pgpgout': 1692352}；最小剩余磁盘84.69GiB。
- 节点CPU 854.940%（100%为1逻辑核，整机16逻辑核）；RSS首/末/峰值1710.64/1717.13/1762.83MiB，minor/majorFault=271648/0，FD首末43/44，线程数首末49/49；read/write_bytes增量0/1880477696B。
- 线程CPU/voluntary switches/involuntary switches/scheduler等待ms（操作系统计数，nid关联完整线程名）：core-account-lane-2 98.172%/23256/156/9.959；core-account-lane-3 98.172%/22693/125/7.809；core-account-lane-0 98.136%/23637/128/13.636；core-account-lane-1 98.136%/23522/130/5.778；trading-owner--1 95.007%/39186/468/14.100；core-matcher-0 32.406%/597453/338/207.483。
- Aeron/外围线程CPU：clustered-service-101-0 98.578%；/tmp/aeron-perf-20260911/w64-profile/media-surprising-linear_perpetual-0 [sender,receiver] 92.744%；driver-conductor 55.494%；consensus-module-101-0 35.747%；archive-conductor 31.628%；JFR Sampler Thr 6.417%；JVMCI-native CompilerThread0 1.803%；aeron-md-nra 1.220%。
- NMT nmt-after.txt: Total: reserved=3164076KB, committed=736612KB; nmt-before.txt: Total: reserved=3147983KB, committed=692143KB；覆盖节点就绪至客户端验证结束，非仅测量段。
- GC日志测量段：{'young': 153}，pause总611.812ms（1.019%），p50/p95/p99/max=3.895/4.969/5.841/6.477ms。
- JFR Owner样本21686，四Lane样本97454，其中run self队列/交接检查73653（75.58%）；这不是精确busy业务时长。Owner热点self前6：1466	owner | com.surprising.aeron.service.execution.MatcherPipelineGroup.drainMatchingCompletions:74；563	owner | java.util.concurrent.ConcurrentHashMap.get:949；505	owner | com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:237；382	owner | com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:297；380	owner | com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:230；371	owner | com.surprising.aeron.service.execution.TerminalTombstoneStore.bucket:99。
- Owner inclusive（互相嵌套，不能相加）：14932	owner | com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix:313；5554	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching:136；3747	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1051；3740	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchReadyPlaceSettlements:1215；2512	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1049。
- matcher self前5：197	core-matcher-0 | com.surprising.aeron.service.matching.MatcherPrefixDigest.mix:61；77	core-matcher-0 | com.surprising.aeron.service.execution.OrderBatchExecutor.submitPreparedPipelinedPlaceBatch:256；37	core-matcher-0 | com.surprising.aeron.service.execution.MatcherCommandPipeline.run:237；28	core-matcher-0 | java.util.concurrent.ConcurrentHashMap.get:949；22	core-matcher-0 | exchange.core2.core.common.cmd.OrderCommand.processMatcherEvents:158；matcher样本少于Owner，内部比例只作定位线索。
- ThreadAllocationStatistics测量内首末增量45103878048B/59.162s，727.061MiB/s；按本轮终态吞吐归一约2985.73B/business op（分配和吞吐窗口边缘不完全一致，估算）。分线程计数：core-account-lane-1	bytes=7361456424	ms=59162；core-account-lane-2	bytes=7364652728	ms=59162；core-account-lane-3	bytes=7362861608	ms=59162；clustered-service-101-0	bytes=1607109192	ms=59162；trading-owner--1	bytes=6134059424	ms=59162；core-matcher-0	bytes=7908382896	ms=59162；core-account-lane-0	bytes=7365171880	ms=59162。
- ObjectAllocationSample类型权重前10（bytes，非精确对象数）：6761564584	[J；5466336240	com.surprising.aeron.service.state.OrderRuntime；3793467840	[B；2131691224	com.surprising.aeron.service.state.ReservationRuntime；2105148112	[Ljava.lang.Object;；1712038512	com.surprising.aeron.service.state.LanePublishedMap$Version；1644881920	com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand；1456388832	com.surprising.aeron.service.matching.CoreMatchingResult；1413342720	com.surprising.aeron.service.state.ResolvedPlaceOrder；1318372112	java.lang.Long。
- 分配站点前8（Graal优化会将分配采样归因到合并后的指令/内联栈，不把栈顶API误当对象构造器）：1644881920	core-matcher-0 | java.lang.invoke.VarHandleLongs$Array.setRelease:814 | com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand；1256891392	other:clustered-service-101-0 | com.surprising.aeron.service.execution.CoreMessageFlyweightDecoder.decode:22 | [B；902299744	owner | com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:286 | com.surprising.aeron.protocol.PlaceOrderCommand；872405168	core-matcher-0 | java.lang.invoke.VarHandleLongs$Array.setRelease:814 | exchange.core2.core.common.MatcherResult；805663728	core-matcher-0 | java.lang.invoke.VarHandleLongs$Array.setRelease:814 | com.surprising.aeron.service.matching.CoreMatchingResult；753843112	core-account-lane-1 | org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow:1160 | [J；737909296	owner | com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:286 | [B；735655728	core-account-lane-2 | org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow:1160 | [J。
- TLAB refill事件95565、TLAB容量合计45494219680B（含剩余/浪费空间，不等于精确已用分配）；outsideTLAB事件6334、实际bytes=432884832，记录的最大触发/非TLAB对象262160B。未逐对象追踪全部TLAB内对象，不能给出精确objects/op。
- heapUsed.After GC 首/末/最小/峰值=103651216/106323104/102581920/110191296（内存bytes）。
- heapUsed.Before GC 首/末/最小/峰值=404619760/407913712/404157936/408135488（内存bytes）。
- jdk.DirectBufferStatistics.total.count 首/末/最小/峰值=8/8/8/8（内存bytes）。
- jdk.DirectBufferStatistics.total.memoryUsed 首/末/最小/峰值=9575136/9575136/9575136/9575136（内存bytes）。
- jdk.NativeMemoryUsageTotal.total.reserved 首/末/最小/峰值=3229055492/3227080758/3224562613/3235433168（内存bytes）。
- jdk.NativeMemoryUsageTotal.total.committed 首/末/最小/峰值=743905796/742398006/739679157/746388176（内存bytes）。
- NMT各category committed首/末/峰值KiB：Arena Chunk 67/751/1039；Arguments 0/0/0；Class 2486/2488/2488；Code 46156/46680/46680；Compiler 435/437/500；GC 71864/71875/71875；GCCardSet 18/18/18；Internal 543/552/552；JVMCI 135/135/135；Java Heap 524288/524288/524288；Logging 0/0/0；Metaspace 24651/24779/24779；Module 293/293/293；Native Memory Tracking 3048/2782/3218；Object Monitors 13/1/13；Other 9421/9423/9423；Safepoint 8/8/8；Serviceability 17/17/18；Shared class space 13968/13968/13968；Statistics 0/0/0；String Deduplication 1/1/1；Symbol 4744/4744/4744；Synchronization 750/756/756；Test 0/0/0；Thread Stack 2328/2328/2360；Thread 157/157/189；Tracing 21081/18518/22707。
- JIT/停顿：durationMs.jdk.Compilation count=1 sumMs=563.521 maxMs=563.521；durationMs.jdk.ExecuteVMOperation count=162 sumMs=653.807 maxMs=34.854；durationMs.jdk.SafepointBegin count=160 sumMs=12.664 maxMs=0.803；VM operation和GC嵌套不相加。
- owner无记录到ThreadPark/MonitorEnter/File/Socket IO；park、monitor enter、IO阈值1ms，monitor wait10ms，不能据此排除阈值以下事件。matcher ThreadPark [159.0, 580.0952140000002, 1.0473, 35.120098, 3.91765, 1.32445]（count/sumMs/min/max/first/last）；短于1ms park大量不记录。
- I/O计时：durationMs.jdk.FileWrite.other:archive-conductor [11.0, 42.54705799999999, 1.26856, 5.97948, 4.402519, 3.60149]；其余过程CPU热点及各类事件已用RecordingFile聚合。
- 客户端JMH gc profiler：{'gc.alloc.rate': 260.3140783668001, 'gc.alloc.rate.norm': 27505506104.0, 'gc.count': 353.0, 'gc.time': 285.0}；B/JMH调用包含setup/预热/测量，不能当B/business op；不把客户端分配率误作节点分配率。
- ExceptionStatistics throwables首末205/205；Metaspace used首末26575184/26646920B；deopt事件7，code cache fullCount最大0。
- JFR GCPhasePause：GC_PAUSES count=153 totalMs=606.860688; p0.5=3.86435; p0.95=4.92432; p0.99=5.74602; p1.0=6.38436；与GC日志的计时边界不同，分别报告、不相加。
- JFR全录制Duration: 105 s，DataLoss0；上述分析仅按测量epoch边界过滤。设置Execution/NativeSample2ms、ThreadCPU10s（主CPU取/proc）、ObjectAllocationSample300/s、分配/NMT/DirectBuffer1s，TLAB及outsideTLAB enabled、stackdepth128/maxsize256m。

#### w128-profile

- 测量UTC 2026-09-11T06:53:28.672000+00:00 至 2026-09-11T06:54:28.736000+00:00；上海时间为UTC+8。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1034 businessHash=959cb62a320235d7
- mixedCapacity=PASS elapsedSeconds=60.064 terminalBusinessOperations=14853120 offeredBusinessOperations=14853120 terminalCoreMessages=1428480 offeredCoreMessages=1428480 businessOpsPerSec=247288.737 coreMessagesPerSec=23782.681 fills=3532800 fillsPerSec=58817.383 queries=0 unfinished=0 peakInFlight=128 measuredCycles=690 totalCycles=1034 triggerExecutions=0
- business=PLACE_ORDER items=353280 requests=353280 p50us=2775 p90us=6705 p95us=8421 p99us=10420 p999us=12746 maxus=20152
- business=CANCEL_ORDER items=353280 requests=353280 p50us=2676 p90us=4595 p95us=5611 p99us=7618 p999us=12353 maxus=29261
- business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=3784 p90us=10346 p95us=12476 p99us=15663 p999us=17006 maxus=17219
- business=PLACE_ORDER_BATCH items=10598400 requests=529920 p50us=6676 p90us=9273 p95us=10338 p99us=13402 p999us=17252 maxus=30195
- business=CANCEL_ORDER_BATCH items=3532800 requests=176640 p50us=9183 p90us=11165 p95us=11862 p99us=13508 p999us=17842 maxus=19824
- 延迟单位us，顺序p50/p90/p95/p99/p999/max，items与requests分开；batch固定20。
- Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=128 pending=0 windows=2125884 commands=2125884 dependencyFences=1719 controlFences=65333（节点计数含初始化/预热）。
- 系统采样区间56.578s/29点，整机CPU 67.935%、iowait 0.351%、steal 0.000%；VM增量{'pswpin': 0, 'pswpout': 0, 'pgfault': 385502, 'pgmajfault': 1, 'pgpgin': 124, 'pgpgout': 1673696}；最小剩余磁盘82.61GiB。
- 节点CPU 857.951%（100%为1逻辑核，整机16逻辑核）；RSS首/末/峰值1729.90/1737.29/1776.11MiB，minor/majorFault=250929/0，FD首末43/44，线程数首末49/49；read/write_bytes增量0/1811529728B。
- 线程CPU/voluntary switches/involuntary switches/scheduler等待ms（操作系统计数，nid关联完整线程名）：core-account-lane-1 98.166%/23140/170/15.351；core-account-lane-3 98.148%/22234/145/8.923；core-account-lane-0 98.130%/23379/157/7.242；core-account-lane-2 98.130%/22825/338/8.394；trading-owner--1 97.194%/27496/488/16.211；core-matcher-0 31.885%/626815/469/196.040。
- Aeron/外围线程CPU：clustered-service-101-0 98.448%；/tmp/aeron-perf-20260911/w128-profile/media-surprising-linear_perpetual-0 [sender,receiver] 93.765%；driver-conductor 55.234%；consensus-module-101-0 36.304%；archive-conductor 31.762%；JFR Sampler Thr 6.681%；JVMCI-native CompilerThread0 1.414%；aeron-md-nra 1.290%。
- NMT nmt-after.txt: Total: reserved=3168991KB, committed=737375KB; nmt-before.txt: Total: reserved=3149168KB, committed=692304KB；覆盖节点就绪至客户端验证结束，非仅测量段。
- GC日志测量段：{'young': 145}，pause总620.917ms（1.034%），p50/p95/p99/max=4.205/5.700/6.623/6.817ms。
- JFR Owner样本22000，四Lane样本95965，其中run self队列/交接检查73424（76.51%）；这不是精确busy业务时长。Owner热点self前6：1355	owner | com.surprising.aeron.service.execution.MatcherPipelineGroup.drainMatchingCompletions:74；753	owner | com.surprising.aeron.service.execution.SurprisingClusteredService.progressCommandsInScope:230；467	owner | java.util.concurrent.ConcurrentHashMap.get:949；383	owner | com.surprising.aeron.service.execution.TerminalTombstoneStore.bucket:99；347	owner | com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:297；333	owner | com.surprising.aeron.service.execution.TerminalTombstoneStore.unlinkClient:116。
- Owner inclusive（互相嵌套，不能相加）：15145	owner | com.surprising.aeron.service.execution.SurprisingClusteredService.pollCommandPrefix:313；5873	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.completeMatching:136；3708	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1051；3703	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.dispatchReadyPlaceSettlements:1215；2425	owner | com.surprising.aeron.service.execution.OrderedCommitCoordinator.pumpMatchingCommitCompletions:1049。
- matcher self前5：172	core-matcher-0 | com.surprising.aeron.service.matching.MatcherPrefixDigest.mix:61；102	core-matcher-0 | com.surprising.aeron.service.execution.OrderBatchExecutor.submitPreparedPipelinedPlaceBatch:256；47	core-matcher-0 | java.util.ImmutableCollections$ListItr.next:397；38	core-matcher-0 | java.util.concurrent.ConcurrentHashMap.get:949；30	core-matcher-0 | com.surprising.aeron.service.execution.MatcherCommandPipeline.run:237；matcher样本少于Owner，内部比例只作定位线索。
- ThreadAllocationStatistics测量内首末增量42019475904B/58.148s，689.153MiB/s；按本轮终态吞吐归一约2922.21B/business op（分配和吞吐窗口边缘不完全一致，估算）。分线程计数：core-account-lane-1	bytes=6938641368	ms=58148；core-account-lane-2	bytes=6932471472	ms=58148；core-account-lane-3	bytes=6945113760	ms=58148；clustered-service-101-0	bytes=1529173864	ms=58148；trading-owner--1	bytes=5867292648	ms=58148；core-matcher-0	bytes=6857818856	ms=58148；core-account-lane-0	bytes=6948537544	ms=58148。
- ObjectAllocationSample类型权重前10（bytes，非精确对象数）：6183606664	[J；4769236480	com.surprising.aeron.service.state.OrderRuntime；3947642888	[B；2599589160	[Ljava.lang.Object;；2203173104	com.surprising.aeron.service.state.LanePublishedMap$Version；2016328192	com.surprising.aeron.service.state.ReservationRuntime；1374641056	com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand；1291378544	exchange.core2.core.common.MatcherResult；1226777768	com.surprising.aeron.service.matching.CoreMatchingResult；1201195416	com.surprising.aeron.service.state.ResolvedPlaceOrder。
- 分配站点前8（Graal优化会将分配采样归因到合并后的指令/内联栈，不把栈顶API误当对象构造器）：1209666160	other:clustered-service-101-0 | com.surprising.aeron.service.execution.CoreMessageFlyweightDecoder.decode:22 | [B；975939616	core-matcher-0 | java.util.ArrayList.add:485 | com.surprising.aeron.service.matching.CoreMatchingResult$NativeCommand；920638328	core-matcher-0 | java.util.ArrayList.add:485 | exchange.core2.core.common.MatcherResult；833442136	owner | com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:286 | com.surprising.aeron.protocol.PlaceOrderCommand；789258736	core-matcher-0 | java.util.ArrayList.add:485 | com.surprising.aeron.service.matching.CoreMatchingResult；777572760	owner | com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeCommand:286 | [B；684124952	core-account-lane-0 | org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow:1160 | [J；606826344	core-account-lane-3 | org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap.rehashAndGrow:1160 | [J。
- TLAB refill事件90800、TLAB容量合计43120971384B（含剩余/浪费空间，不等于精确已用分配）；outsideTLAB事件6920、实际bytes=464357632，记录的最大触发/非TLAB对象262160B。未逐对象追踪全部TLAB内对象，不能给出精确objects/op。
- heapUsed.After GC 首/末/最小/峰值=122738864/124655920/117993792/128143800（内存bytes）。
- heapUsed.Before GC 首/末/最小/峰值=421357616/424564800/421062080/424902384（内存bytes）。
- jdk.DirectBufferStatistics.total.count 首/末/最小/峰值=8/8/8/8（内存bytes）。
- jdk.DirectBufferStatistics.total.memoryUsed 首/末/最小/峰值=9575136/9575136/9575136/9575136（内存bytes）。
- jdk.NativeMemoryUsageTotal.total.reserved 首/末/最小/峰值=3229677298/3227442467/3224817938/3232015470（内存bytes）。
- jdk.NativeMemoryUsageTotal.total.committed 首/末/最小/峰值=744621810/742784291/740090130/747287662（内存bytes）。
- NMT各category committed首/末/峰值KiB：Arena Chunk 143/751/879；Arguments 0/0/0；Class 2486/2491/2491；Code 46577/47023/47023；Compiler 414/429/479；GC 71939/71940/71940；GCCardSet 18/18/18；Internal 558/567/567；JVMCI 128/128/128；Java Heap 524288/524288/524288；Logging 0/0/0；Metaspace 24650/24778/24778；Module 293/293/293；Native Memory Tracking 3070/2789/3263；Object Monitors 16/1/16；Other 9421/9423/9423；Safepoint 8/8/8；Serviceability 17/17/18；Shared class space 13968/13968/13968；Statistics 0/0/0；String Deduplication 1/1/1；Symbol 4744/4744/4744；Synchronization 751/756/756；Test 0/0/0；Thread Stack 2232/2232/2232；Thread 157/157/157；Tracing 21292/18574/23145。
- JIT/停顿：durationMs.jdk.Compilation count=1 sumMs=391.502 maxMs=391.502；durationMs.jdk.ExecuteVMOperation count=154 sumMs=650.326 maxMs=22.329；durationMs.jdk.SafepointBegin count=152 sumMs=22.616 maxMs=0.627；VM operation和GC嵌套不相加。
- owner无记录到ThreadPark/MonitorEnter/File/Socket IO；park、monitor enter、IO阈值1ms，monitor wait10ms，不能据此排除阈值以下事件。matcher ThreadPark [142.0, 558.5181499999999, 1.01179, 22.83, 2.227151, 4.62503]（count/sumMs/min/max/first/last）；短于1ms park大量不记录。
- I/O计时：durationMs.jdk.FileWrite.other:archive-conductor [7.0, 24.284889999999997, 1.10252, 4.60144, 1.10252, 4.39588]；其余过程CPU热点及各类事件已用RecordingFile聚合。
- 客户端JMH gc profiler：{'gc.alloc.rate': 259.73092910019307, 'gc.alloc.rate.norm': 27430674616.0, 'gc.count': 352.0, 'gc.time': 306.0}；B/JMH调用包含setup/预热/测量，不能当B/business op；不把客户端分配率误作节点分配率。
- ExceptionStatistics throwables首末209/209；Metaspace used首末26600056/26665112B；deopt事件13，code cache fullCount最大0。
- JFR GCPhasePause：GC_PAUSES count=145 totalMs=616.109371; p0.5=4.17015; p0.95=5.603191; p0.99=6.59309; p1.0=6.72366；与GC日志的计时边界不同，分别报告、不相加。
- JFR全录制Duration: 105 s，DataLoss0；上述分析仅按测量epoch边界过滤。设置Execution/NativeSample2ms、ThreadCPU10s（主CPU取/proc）、ObjectAllocationSample300/s、分配/NMT/DirectBuffer1s，TLAB及outsideTLAB enabled、stackdepth128/maxsize256m。

#### w64-clean

- 测量UTC 2026-09-11T07:00:45.272000+00:00 至 2026-09-11T07:01:45.348000+00:00；上海时间为UTC+8。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1092 businessHash=9e35a1009b550b67
- mixedCapacity=PASS elapsedSeconds=60.076 terminalBusinessOperations=16229376 offeredBusinessOperations=16229376 terminalCoreMessages=1559552 offeredCoreMessages=1559552 businessOpsPerSec=270146.912 coreMessagesPerSec=25959.603 fills=3860480 fillsPerSec=64259.818 queries=0 unfinished=0 peakInFlight=64 measuredCycles=754 totalCycles=1092 triggerExecutions=0
- business=PLACE_ORDER items=386048 requests=386048 p50us=1130 p90us=2283 p95us=3366 p99us=4513 p999us=5844 maxus=7655
- business=CANCEL_ORDER items=386048 requests=386048 p50us=1002 p90us=1777 p95us=2455 p99us=4087 p999us=5746 maxus=8871
- business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=1663 p90us=4788 p95us=5709 p99us=7233 p999us=9027 maxus=9838
- business=PLACE_ORDER_BATCH items=11581440 requests=579072 p50us=3205 p90us=4456 p95us=5251 p99us=7376 p999us=9797 maxus=13918
- business=CANCEL_ORDER_BATCH items=3860480 requests=193024 p50us=4284 p90us=5058 p95us=5603 p99us=6647 p999us=7643 maxus=10207
- 延迟单位us，顺序p50/p90/p95/p99/p999/max，items与requests分开；batch固定20。
- Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=64 pending=0 windows=2244668 commands=2244668 dependencyFences=1655 controlFences=59390（节点计数含初始化/预热）。
- 系统采样区间56.327s/29点，整机CPU 68.808%、iowait 0.382%、steal 0.000%；VM增量{'pswpin': 0, 'pswpout': 0, 'pgfault': 426366, 'pgmajfault': 0, 'pgpgin': 0, 'pgpgout': 1995548}；最小剩余磁盘80.43GiB。
- 节点CPU 866.392%（100%为1逻辑核，整机16逻辑核）；RSS首/末/峰值1689.44/1691.85/1727.03MiB，minor/majorFault=333542/0，FD首末41/41，线程数首末46/46；read/write_bytes增量0/2163437568B。
- 线程CPU/voluntary switches/involuntary switches/scheduler等待ms（操作系统计数，nid关联完整线程名）：core-account-lane-3 98.941%/198/64/0.521；core-account-lane-0 98.923%/207/110/2.593；core-account-lane-1 98.923%/198/91/4.353；core-account-lane-2 98.923%/200/82/3.407；trading-owner--1 96.792%/12358/178/5.697；core-matcher-0 35.560%/638002/371/103.821。
- Aeron/外围线程CPU：clustered-service-101-0 98.941%；/tmp/aeron-perf-20260911/w64-clean/media-surprising-linear_perpetual-0 [sender,receiver] 95.124%；driver-conductor 59.066%；consensus-module-101-0 38.330%；archive-conductor 34.353%。
- NMT nmt-after.txt: Total: reserved=3148179KB, committed=712011KB; nmt-before.txt: Total: reserved=3120605KB, committed=668109KB；覆盖节点就绪至客户端验证结束，非仅测量段。
- GC日志测量段：{'young': 162}，pause总621.019ms（1.034%），p50/p95/p99/max=3.741/4.754/6.214/7.235ms。

#### w128-clean

- 测量UTC 2026-09-11T07:02:30.021000+00:00 至 2026-09-11T07:03:30.050000+00:00；上海时间为UTC+8。
- mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=1105 businessHash=ac9167ff043a670f
- mixedCapacity=PASS elapsedSeconds=60.029 terminalBusinessOperations=16250880 offeredBusinessOperations=16250880 terminalCoreMessages=1561600 offeredCoreMessages=1561600 businessOpsPerSec=270717.764 coreMessagesPerSec=26014.152 fills=3865600 fillsPerSec=64395.687 queries=0 unfinished=0 peakInFlight=128 measuredCycles=755 totalCycles=1105 triggerExecutions=0
- business=PLACE_ORDER items=386560 requests=386560 p50us=2541 p90us=6037 p95us=7528 p99us=9469 p999us=11935 maxus=17301
- business=CANCEL_ORDER items=386560 requests=386560 p50us=2445 p90us=4337 p95us=5214 p99us=7086 p999us=9887 maxus=15220
- business=APPLY_MARK_PRICE items=15360 requests=15360 p50us=3618 p90us=8404 p95us=10280 p99us=13680 p999us=16744 maxus=18726
- business=PLACE_ORDER_BATCH items=11596800 requests=579840 p50us=6127 p90us=8585 p95us=9584 p99us=12394 p999us=16433 maxus=22118
- business=CANCEL_ORDER_BATCH items=3865600 requests=193280 p50us=8245 p90us=10215 p95us=11001 p99us=13467 p999us=16973 maxus=19759
- 延迟单位us，顺序p50/p90/p95/p99/p999/max，items与requests分开；batch固定20。
- Aeron core command-window productLine=LINEAR_PERPETUAL highWaterMark=128 pending=0 windows=2271292 commands=2271292 dependencyFences=1705 controlFences=92714（节点计数含初始化/预热）。
- 系统采样区间58.367s/30点，整机CPU 67.966%、iowait 0.403%、steal 0.000%；VM增量{'pswpin': 0, 'pswpout': 0, 'pgfault': 310512, 'pgmajfault': 0, 'pgpgin': 0, 'pgpgout': 1813092}；最小剩余磁盘78.23GiB。
- 节点CPU 860.878%（100%为1逻辑核，整机16逻辑核）；RSS首/末/峰值1710.42/1714.05/1746.94MiB，minor/majorFault=221040/0，FD首末41/41，线程数首末46/46；read/write_bytes增量0/1949380608B。
- 线程CPU/voluntary switches/involuntary switches/scheduler等待ms（操作系统计数，nid关联完整线程名）：core-account-lane-0 98.840%/216/87/1.566；core-account-lane-1 98.840%/201/107/1.715；core-account-lane-2 98.840%/216/84/2.247；core-account-lane-3 98.840%/211/92/3.771；trading-owner--1 97.846%/6106/148/6.626；core-matcher-0 33.820%/666758/390/117.250。
- Aeron/外围线程CPU：clustered-service-101-0 98.840%；/tmp/aeron-perf-20260911/w128-clean/media-surprising-linear_perpetual-0 [sender,receiver] 94.025%；driver-conductor 58.132%；consensus-module-101-0 36.836%；archive-conductor 32.621%。
- NMT nmt-after.txt: Total: reserved=3147714KB, committed=711858KB; nmt-before.txt: Total: reserved=3120168KB, committed=668772KB；覆盖节点就绪至客户端验证结束，非仅测量段。
- GC日志测量段：{'young': 160}，pause总654.589ms（1.090%），p50/p95/p99/max=4.008/4.936/5.956/6.017ms。

### 证据校验与清理前清单

- w128-profile/node.jfr 16599029B SHA256=e7ed63b4f26f95e36216b630ce8af90add43c0a71a3c60f43a928b2bcc4ec041
- w128-profile/system.jsonl 8448225B SHA256=4298b3c051d6ef56d25fd38cad62e14c5b21b2d9d044e0f2b146a2950a375185
- w64-baseline/system.jsonl 8140826B SHA256=09a7412930ddf7b1a49d145371792c6a0d977aba1384182e31559e81f5d0823a
- w128-clean/system.jsonl 8109377B SHA256=7d7ecc89d4d53243baab1942532529d240d0b63187b9631117be21c0fe212747
- w64-profile/node.jfr 16606386B SHA256=68cbccc113cdb7b51e5c4f7af8e647ea54fb0bb1de0cb5413cc0a88f878cc7a5
- w64-profile/system.jsonl 8472526B SHA256=33dee45c63ff4afa8f21fc5ca6ad045aa3017460980d711e6fb8aa4fae68b6f6
- w64-clean/system.jsonl 8109079B SHA256=5474947cec542990f32d39bbef4d9a41d2df8acece0648980dd411e09ac9a3ef
- w128-baseline/system.jsonl 7947218B SHA256=fe7636b05cf724fc2e645adb2a3bb9cb2dcccf4fcc04e967c6c5fb1065180451

### 本轮诊断结论与适用边界

- 最终主结果采用clean轮：64窗口270146.912 terminal business ops/s、25959.603 terminal Core messages/s、64259.818 fills/s；128窗口270717.764/26014.152/64395.687。128吞吐仅高0.211%，普通下单p99由4.513ms升至9.469ms，批下p99由7.376ms升至12.394ms。两档单轮没有统计置信区间，因此结论是“本次未观察到有意义的吞吐增长，排队/尾延迟更高”，不是证明两档理论容量完全相等。当前组合优先保留64窗口。
- 高CPU原因有源码与采样共同支持：`SurprisingClusteredService.pollCommands:554–570`只要pending非零就返回work=1，`ContinuousTradingClusterService.runOwner:195–215`据此调用idle.idle(work)，等待Lane/matcher但没有实际推进时也不退避。因此Owner CPU接近100%包含轮询，不能等同100%业务计算；本轮没有增加精确空轮询计数器，不能把全部progress样本当作空转比例。
- Owner有效工作也集中：`pollCommandPrefix:313` inclusive为14932/21686=68.86%和15145/22000=68.84%，覆盖收集、派发、commit及状态发布；`drainMatchingCompletions:74` self为6.76%和6.16%，还有CHM读写、终态tombstone、批量解码、变更收集成本。`beginCommandPrefix:289`固定drainingSize=1保留每条日志确定性提交，窗口扩大不等于串行提交成本消失。inclusive不能相加，也不能从采样直接证明唯一瓶颈。
- Lane和matcher CPU不能直接横比：`SettlementLaneWorker.run:154/173`采用BUSY_SPIN；四Lane run self样本占75.58%/76.51%，Lane0约88.16%/90.27%，其余约70–73%，涵盖队列检查与控制交接等待。JIT内联/采样归因也会影响分线程样本比例，不能仅据这些比例认定账户负载不均。matcher则`MatcherCommandPipeline.run:240–244`空闲自旋64次后park100us，实际低CPU符合任务供给不足/等待而非持续计算饱和。clean轮matcher CPU35.56%/33.82%，Owner96.79%/97.85%，四Lane98.84–98.94%；profile轮matcher scheduler等待仅207.48/196.04ms每约56.6秒，不能把其余闲置归因为OS抢不到CPU。
- 分配也是后续优化对象：节点ThreadAllocationStatistics约727.06/689.15MiB/s、2985.73/2922.21B每业务操作；四Lane合计分配占主要部分，Owner约6.1/5.9GB，matcher约7.9/6.9GB（计数窗口见各轮）。long[]、OrderRuntime、byte[]、ReservationRuntime、LanePublishedMap.Version/CHM节点和matcher结果包装是主要类型。GC暂停约1%，无Full GC，故不能用GC单独解释Owner满载，也不能因GC占比低忽略分配和缓存成本。未采硬件cache miss/IPC/perf PMU，未证明哪种数组或版本对象可安全删除。
- 后续优化优先级：先对“真实推进计数/通知等待”做有正确性与尾延迟门槛的独立对照，再精简Owner结果收集/终态索引/发布，以及Lane固定缓冲与matcher结果分配；不靠扩大窗口或增加线程掩盖问题，不改变每条日志提交、唯一Lane所有权和资金边界。本任务只诊断，未修改生产热路径。
- 六轮均mixedVerify/mixedCapacity PASS、offered=terminal业务/Core、unfinished0、fundsDiff0、population/hftPositions/reservations/loss=true、节点pending0；压力期间queries/triggerExecutions=0。未单独导出accepted阶段延迟、拒绝率/错误率（正常业务拒绝不能混作系统异常）、业务类型独立实时backlog/objects-op、全部native池分配释放余额，未跑长期泄漏、当前云节点快照恢复及六产品并发；既有相同JAR六产品恢复门禁只作前置功能证据，不冒充本轮恢复。属于完成指定单节点窗口诊断、部分性能验证，不作三节点或生产容量验收。
- clean轮均swapin/out=0、majorFault=0、steal=0，整机CPU68.81%/67.97%，不存在整机16逻辑CPU都耗尽的证据。profile128系统出现1次majorFault（节点0），单独保留，不归因交易错误。当前SSH/测试进程位于/system.slice/ssh.service；该层和祖先未暴露cpu.max/nr_throttled，未取得独立CPU频率/节流时间序列，不宣称完整容器/宿主机节流验收。/proc监控捕获节点与JMH控制进程，JMH fork由其他Java线程创建，未被主线程children文件捕获；客户端fork CPU/RSS缺口明确保留，客户端分配仅用JMH gc profiler，整机CPU含发压端。
- profile最大VM操作明确为采集引入的PrintThreads34.854/22.329ms；它解释部分异常尾部，并非资金逻辑锁等待。已保留前四轮全部数据且补跑clean，不从直方图人为减去停顿。两份JFR native线程无Java名称的离线解析错误已修复，均成功生成summary/完整聚合，DataLoss0；异常计数测量内未增长、code cache fullCount0。约60秒GC后heap和Direct稳定只证明短期观察，不证明无泄漏；RSS与NMT统计范围不同，不能将差值直接视作泄漏。
- Oracle GraalVM25.0.1 Linux x64安装包SHA256 d4ab02ba1029e639f03374fdf91c242e1d0d49079880e1af1932ea7b7c431837，与官方.sha256一致；Maven3.9.16实际运行HotSpot25.0.1。云端两JAR校验与采集前锁定一致。apt包下载结束后才正式发压，未改变业务依赖。

### 执行命令口径

- 节点共同参数：`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xms512m -Xmx1536m -XX:NativeMemoryTracking=summary`；GC/safepoint日志轮转3×16m；`-Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=localhost -Dsurprising.aeron.egress-hostname=localhost -Dsurprising.aeron.node-id=0 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN -Dsurprising.aeron.settlement-spin-limit=0 -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING -Dsurprising.aeron.owner-command-window=W -Dsurprising.aeron.data-dir=ROUND/data -Daeron.dir=ROUND/media -jar surprising-aeron-service.jar`，W分别64/128，ROUND每轮独立绝对路径。
- 客户端沿用共同开放/产品/拓扑参数，`-Xms128m -Xmx512m -Dsurprising.aeron.client.threading-mode=SHARED -Daeron.dir=ROUND/client-media -Dsurprising.aeron.capacity-seed=131001 -Dsurprising.aeron.capacity-warmup-seconds=30 -Dsurprising.aeron.capacity-duration-seconds=60 -Dsurprising.aeron.mixed-trading-stream=true -Dsurprising.aeron.mixed-operational=false -jar product-core-benchmarks.jar ClusterOperationalBenchmark.continuousOperations -p controlPageSize=0 -p inFlightWindow=W -f 1 -wi 0 -i 1 -t 1 -to 300s -rf json -rff ROUND/jmh.json`；仅profile轮加`-prof gc`，只读/proc每2s。
- profile节点额外`-XX:FlightRecorderOptions=stackdepth=128 -XX:StartFlightRecording=name=diag,settings=diag.jfc,filename=ROUND/node.jfr,dumponexit=true,maxsize=256m`；JFC用`jfr configure --input profile.jfc`设置ExecutionSample/NativeMethodSample2ms、ThreadPark/JavaMonitorEnter1ms、ObjectAllocationInNewTLAB/ObjectAllocationOutsideTLAB enabled、ThreadAllocationStatistics/DirectBufferStatistics/NativeMemoryUsage/NativeMemoryUsageTotal1s。余项取该JDK profile设置，见上文具体阈值。
- 分析通过`jfr summary`、Java RecordingFile按measurementStart/EndEpochMillis过滤，并用`jfr print --json --events jdk.ExecuteVMOperation,jdk.ExceptionStatistics,jdk.CodeCacheStatistics,jdk.MetaspaceSummary,jdk.GCPhasePauseLevel1,jdk.Deoptimization`核对停顿/异常；资金/状态/终态由真实ClusterMixedCapacityMain核验。未另跑无关Maven套件，未改业务字节码。

### 清理及交付核验

- 已独立复核六轮offered/terminal业务与Core一致、unfinished0、资金/持仓/冻结/loss核验全部PASS；两份JFR DataLoss0；clean两轮GC/safepoint日志确认测量期间PrintThreads为0。生成的脚本语法检查通过，文档git diff --check通过，生产代码未改。
- 摘要保存后确认云端无本轮Java/runner/post进程，删除云端/tmp/aeron-perf-20260911共15828451023B（含本轮Archive、录制、JFR、临时运行时/上传副本、日志和分析工具），磁盘恢复93GiB可用；本机同名临时目录23377643B已删除。原始路径全部失效，以上SHA只作历史校验，不能再将这些路径当作可下载证据。本机仓库原有JAR及用户未跟踪文件保留。
- 清理前results.tgz本机/云端SHA256均633485fe75d5ce87d14ae62254cab91279ae3848b2e911efc578a4788b1416bc；提取结果已写本文，压缩包随本轮产物清理。没有保留堆转储或另建性能结果文件。
- Google Cloud停止操作operation-1789110469540-65b2fbe003926-741abbdc-7056e2f0于2026-09-11 07:10:09.801 UTC完成DONE（上海15:10:09）；再次查询实例状态TERMINATED，临时公网IP已释放。停止命令等待阶段曾因本机代理ProxyError退出，已通过服务端operation及实例状态双重复核成功，不误报停止失败。保留专用实例及100GB启动盘供后续使用，未继续运行计费CPU；磁盘仍保留。
