# 交易链路代码审计：性能、并发与过度设计（2026-10-15）

## 0. 阅读说明

**范围**：`surprising-aeron-core`（`surprising-aeron-service` 约 315 个文件、6.2 万行，另含 `surprising-aeron-client`、`surprising-aeron-protocol`）。链路为：网关 `AeronClientPool` → Aeron Cluster 入口 → Owner 线程 → Matcher → Account Lane / 结算 → 响应出口。

**方法**：本文来自 5 路并行的只读静态审计（接入与 Owner 循环、撮合与批量下单、账户 Lane 与结算状态、全模块并发原语与缓存行横扫、过度设计与结构复杂度），审计时没有改代码，也没有运行 Maven、JMH 或 JFR。

**重要限制**：
- 全部结论是静态阅读得出的。文中「每单约 N 个对象」「每命令 N 次」之类的数字是估算，没有 `-prof gc`、JFR 或 `perf c2c` 数据。按 `AGENTS.md`「复杂优化必须有实测依据」，动手前必须先用第 8 节的验证方式拿到基线。
- 每条发现带状态标记：
  - **【已核】**：整理时直接读过源码，现象与描述一致。
  - **【报告】**：子审计报告的结论，整理时未逐条重读源码。
  - **【假设】**：需要 JCStress、JFR 或 `perf c2c` 才能证实或证伪。
- 已被最近 perf 提交处理过的项不重复列出：命令槽 UTF8 解码工作区复用、单命令入口与响应存储复用、指纹槽复用。
- 路径均相对 `surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/`，另有说明的除外。行号对应审计时的 `master`（工作区含未提交改动），改动后会漂移。

**编号规则**：`C` 正确性与可用性风险，`P` 热路径性能，`F` 缓存行与伪共享，`L` 锁与线程模型，`D` 过度设计，`X` 已核实无问题（不需处理）。

## 1. 总览与优先级

| 优先级 | 编号 | 一句话 | 风险 |
|---|---|---|---|
| 先确认 | C1 | `ResponseArena` 引用计数疑似跨线程竞争 | 正确性，需 JCStress |
| 先确认 | C2 | 出口队列溢出会关闭全部会话 | 可用性 |
| 先确认 | C3 | 成交结算有 lane 原地修改与值语义两套资金算术 | 资金，需对账测试 |
| 高收益低风险 | P1 / F1 | 每笔订单无条件 `unpark` | 低 |
| 高收益低风险 | F2 / L3 | 结算 ring 游标相邻 volatile，消费端多余 fence | 低 |
| 高收益低风险 | P2 | Lane 线程每次线性查找自己的 lane（85 处调用） | 低 |
| 高收益低风险 | P3 | 市价单为读最优价走完整引擎命令 | 低到中 |
| 高收益低风险 | P4 | 每命令 SHA-256 指纹 | 低（未上线可直接改格式） |
| 中 | P5–P13、F3–F9、L1–L6 | 见正文 | 中 |
| 已完成 | D1 | 已移除假 `Cluster` / `ClientSession` 实现，Owner 按会话 ID 路由 | 已验证，见 D1 |
| 已完成 | D2 / L8 | 已删除旧 Owner 直达派发、窗口扫描回调及 Lane 响应回退 | 已验证，见 D2 |
| 已完成 | D3 / L7 | 交易统一 Direct，删除旧 Future/API、Owner 补结算与分区扫描 | 已验证，见 D3 |
| 已完成 | D4 | Owner 直接消费唯一输入环，删除重复 FIFO 与回收队列 | 已验证，见 D4 |
| 删除类 | D5–D9 | 空壳接口等 | 低到中 |
| 最后做 | D10 | `TradingCoreRuntime` 状态枢纽、状态重复 | 中高 |

建议执行顺序见第 9 节。

## 2. 正确性与可用性风险（C）

### C1【已核（代码）/ 假设（竞争）】`ResponseArena` 引用计数疑似跨线程竞争
- **位置**：`orchestration/ResponseArena.java:136-193`（`release`、`retain`、`retainLedger`、`releaseLedger`）。
- **证据**：这四个方法都用 `for (index < slots.length) if (slots[index] == storage)` 按引用线性扫描（最多 512 个槽），然后对普通 `int[]` 的 `transportReferences[index]` 或 `ledgerReferences[index]` 做 `--` 或 `++`，再读对方计数决定是否 `leaseStates.compareAndSet(index, 1, 0)`。`acquireExactSlot` 从下标 0 起对每个槽做 CAS。
- **线程模型（子审计报告，未核实）**：release 在 Cluster 线程出口（`ClusterServiceEgress:157` → `TradingCoreRuntime:755 releaseResponse` → `responseArena.release`），retain / acquire 在 Owner 线程。
- **后果**：若两线程对同一计数读改写，会丢更新，导致槽泄漏（永不回收）或提前复用，已保留的幂等响应可能被覆盖。
- **方案**：
  1. `Slot` 描述符携带下标，release / retain 直接按下标 O(1) 操作，去掉线性扫描。
  2. 把 transport 与 ledger 两个计数合并成一个原子引用计数（`AtomicIntegerArray` 并按缓存行 stride 布局），归零时释放。
  3. 空闲槽用游标或 freelist，不再从 0 开始逐个 CAS。
- **验证**：先用 JCStress 或线程审计确认 retain 与 release 是否真的在不同线程；再 JMH `-prof perfnorm`；`ResponseArena` 单测补并发用例。

### C2【已核】出口队列溢出会关闭所有会话
- **位置**：`orchestration/ClusterServiceEgress.java:109-142`。
- **证据**：`offerResponse` 在容量不足、`availableOutput` 取不到槽、`output.offer` 失败三处只置 `outputOverflow = true` 并丢弃该响应；下一轮 `drainResponses`（:138-142）执行 `sessions.forEachValue(TransportSession::close)` 并 `deferred.clear()`。
- **后果**：任何一次突发溢出都会让所有用户的会话断开，重连后集中重试，高负载下可能放大。
- **方案**：溢出只关闭受影响的会话，或对该会话做背压；评估丢弃的响应是否由幂等账本补发。
- **验证**：构造出口队列溢出的压测场景，观察会话关闭数量、重连与重试放大。

### C3【报告】成交结算有两套资金算术
- **位置**：`state/RuntimeSpotMatchProcessor.java:425-455`，`state/RuntimeDerivativeFillCalculator.java:170-190`（`FillCursor.order()` 在 141 行）。
- **证据**：`matcherSettlementChanges != null` 时走 lane 原地修改；否则走值语义，`new Order/Reservation/PositionRuntime`（28 参构造）。
- **后果**：两套资金算术必须逐位一致；也与 `AGENTS.md`「不要 fallback / legacy」冲突。
- **方案**：先用 `codegraph_callers` 确认非 lane 路径的真实调用方（恢复、强平、ADL 等）；让这些调用方也走 lane 路径；最后删掉值语义分支。
- **验证**：`RuntimeDerivativeFillCalculatorTest`；快照恢复一致性；用户与做市账户余额、冻结、持仓、订单终态和资金守恒核对。

## 3. 热路径性能问题（P）

### P1【已核】每笔订单无条件 `LockSupport.unpark(worker)`
- **位置**：`matcher/MatcherCommandPipeline.java:184-187`（owner 线程，per-order）。
- **证据**：`submissionHighWaterMark = Math.max(...)` 后无条件 `LockSupport.unpark(worker)`，注释称这样可以不加状态标志。
- **机制**：HotSpot 的 `Parker::unpark` 无论目标是否在 park 都要拿 mutex，worker 真在 park 时 owner 线程还要付一次 futex wake。
- **参照**：`OwnerIdleStrategy.java:30-49` 与 `SettlementLaneWorker.signalWork()`（`parked` 标志握手，:146-150）已有正确写法。
- **方案**：worker park 前置位 padded `requested` 标志并复查队列，owner 只在标志为 1 时 unpark。
- **验证**：JFR `jdk.NativeMethodSample`、`ThreadPark`；owner 线程 syscall 次数；p99 对比。

### P2【已核】Lane 线程每次线性查找自己的 lane
- **位置**：`state/TradingRuntimeState.java:655-668`（`currentLaneCommandScope()`），共 85 处调用；非显式 lane 的 `publishOrder/Reservation/User`（如 3651、3676）都会走到。
- **证据**：`for (i < laneWorkers.length) if (worker.thread() == current) {...}`，最多追 64 个 worker。
- **方案**：Lane 线程使用 `LaneThread extends Thread { final AccountLaneState lane; }`，`instanceof` 一步取得 lane；热路径统一改用已有的显式 lane 重载。
- **验证**：JFR 看该方法占比；`AccountLaneCommitBenchmark` 前后对比。

### P3【报告】市价单为读最优价走完整引擎命令
- **位置**：`matching/DeterministicExchangeCoreAdapter.java:1005-1010`（per-market-order）。
- **证据**：`engine(symbolId).orderBook(symbolId, 1)` 经 `SynchronousMatchingEngine.java:140-145 execute()`，消耗一个 native sequence，再经 `router.processOrder` 派发；`MatcherResult.from`（`MatcherResult.java:135`）新建 events 的 `ArrayList`，`MarketData.from`（:265）新建两个 `ArrayList` 与 `Level` 记录，`toMarketData` 再建 6 个数组，估计每单约十个对象（估算）。
- **方案**：给 `OrderBookDirectImpl` 加 `bestAskPrice()` / `bestBidPrice()`（它已持有 `bestAskOrder` / `bestBidOrder`），经 `SynchronousMatchingEngine` 暴露；不占 sequence，不分配。
- **验证**：市价单 JMH `-prof gc` 看 B/op；JFR `ObjectAllocationSample`。

### P4【报告】Cluster 服务线程每条命令计算 SHA-256
- **位置**：`CommandFingerprint:99-116`（所在模块未核实；我在 `surprising-aeron-service` 目录 grep `MessageDigest` 无命中），调用点 `cluster/AeronTradingClusterService.java:104`。
- **机制**：Cluster 服务线程同时承担入口解码和全部出口，是串行关键路径。
- **方案**：换 128 位非密码学哈希（xxh3 或 murmur3-128）；产品未上线，可直接改格式。
- **验证**：`CommandDecodingBenchmark`；cluster 线程 JFR `ExecutionSample`。

### P5【报告】traceId 默认等于 `commandId.toString()`，每条命令上线
- **位置**：`surprising-aeron-client/.../AeronClientPool.java:1246`，编码 `CoreMessageCodec:86`，服务端 `DirectMessageReader:151-157`（protocol 与 client 模块）。
- **后果**：每条命令多 38B 线上字节并复制进 Raft 日志；Cluster 服务线程每条命令还会 `new char[36]` 加 `String`，约 170B 垃圾（估算）。
- **方案**：仅在 MDC 有值或被采样时携带；服务端只保存 offset，诊断时才解码。
- **验证**：`CommandDecodingBenchmark -prof gc`；JFR `ObjectAllocationSample`（cluster 线程）。

### P6【报告】解码槽的分配与原子操作
- **位置**：`CoreMessageFlyweightDecoder:104,46,108`、`CoreMessage:13,20,66`（ingress 子包）。
- **证据与后果**：
  - 每条命令 `new UUID`，而 Ledger 与 Index 已用 msb / lsb 两个 long。
  - 缓存只有 512 槽，入口队列有 8192；超过后每条命令新分配 `CoreMessage`、header 与 payload。
  - 按精确长度缓存，长度一变就 `new byte[len]`。
  - 每槽的 `volatile` 与 `AtomicInteger` 引用计数，在两个线程上都做原子读改写。
- **方案**：命令 id 用两个 long；槽数不小于入口队列数，或让队列按槽数背压；payload 固定 16KB 容量加显式长度。
- **验证**：`-prof gc`；JFR `ObjectAllocationOutsideTLAB`。

### P7【报告】网关 `AeronClientPool` 的锁与分配
- **位置**：`surprising-aeron-client/.../AeronClientPool.java`（含未提交改动）。
- **证据**：
  - :602-623 两个 `ArrayBlockingQueue`，每命令 4 次 `ReentrantLock` 加锁（`offer / peek / poll / isEmpty / removeIf` 都加锁）。
  - :613 `ConcurrentHashMap.newKeySet()` 的 `claimedCorrelations` 每命令装箱 add / remove；关联号来自 `nextCorrelation.incrementAndGet()`（:664），本身唯一，该检查冗余。
  - :611、1007 `LinkedHashMap<Long,Request>` 装箱加节点分配。
  - :722 每次入队都 `unpark`。
  - :989-992 header 构造 3 次（`command` → `withTraceId` → `owned/immutableValue`）。
  - :672、1245 `Instant.now()` 与 `MDC.get`；每请求一个 `CompletableFuture`。
  - 有在途请求时 `parkNanos(100µs)`，响应延迟下限约 100µs 加 timer slack。
  - 未提交改动中 `synchronized` 换 VarHandle CAS 方向正确，但 `reset()` 仍调 `MDC.get`。
- **方案**：mailbox 换 Agrona `ManyToOneConcurrentArrayQueue`，空闲池用 `MpmcArrayQueue`；`pending` 改 `Long2ObjectHashMap`；删除 `claimedCorrelations`；仅在 dispatcher 已 park 时才 `unpark`（参考 `OwnerIdleStrategy:82`）；等待用 `BackoffIdleStrategy`；时间用 `currentTimeMillis`；header 一次构造。
- **验证**：JFR `JavaMonitorEnter`、`ThreadPark`；`-prof gc`；`HttpOpenLoopWorkload`。

### P8【报告】出口先编码到堆数组再拷贝；其余入口小项
- **位置与方案**：
  - `ClusterServiceEgress.java:149-155`：响应先编码进 `sendScratch`（堆 `byte[]`）再由 Aeron 拷贝 → 改用 `offer(DirectBufferVector[])` 或直接编码进 claim 的 buffer。
  - `AeronTradingClusterService:109`：每条消息都调 `drainResponses`（内含两次 `nanoTime`）→ 先判空。
  - `CoreCommandIngress:118-133`：为模拟重入保存并恢复 4 个字段；:224-229 每条命令 3 次哈希查询 → 评估合并为一次查询并移除模拟重入。
  - `CommandResultLedger:24-26`：三个并行数组，每次查询 3 次 cache miss → 交错成一个 `long[]`。
  - `DeferredSessionResponses`：背压重试无退避。

### P9【报告】每单重复查符号与线程作用域
- **位置**：`DeterministicExchangeCoreAdapter.java:1725-1727`（`ensureSymbol`）、:95-102（`currentCommandScope()`）。
- **证据**：先查 `ConcurrentHashMap<String,Integer>`，再 `registeredSymbols.contains(symbolId)`，symbolId 大于 127 时装箱；`currentCommandScope()` 线性扫描 `shardThreads`，每单约 3-4 次；`beginSynchronousCommand` 已返回 scope。
- **方案**：symbolId 在 instrument 加载时预算，存入 `CoreInstrument` 或 `OrderRuntime`；scope 沿调用链向下传。
- **验证**：JMH `-prof gc` 比较 B/op。

### P10【报告】`recordUser` 是热路径上的重复状态
- **位置**：`DeterministicExchangeCoreAdapter.java:104-106`。
- **证据**：每笔订单往 `LongHashSet` 加用户，该集合只给 `snapshotUsers()` 用，后者还转成 `TreeSet<Long>`（排序加装箱）。
- **方案**：快照边界从 `TradingCoreState` 这个权威来源派生，热路径不再维护。

### P11【报告】`List<Long>` 外壳套原生数组
- **位置**：`MatcherSettlementPlan.java:617`（`OrderIdView.get()` 装箱）、`MatcherSettlementEvent.java:566`、`CommandResultBuilder.java:233`、`TradingCoreRuntime.java:1405-1423`；`MatcherSettlementEvent.java:536`。
- **证据**：同一业务事实有两种形态，靠 `instanceof PrimitiveLongView` 区分；`appendDistinct` 用 `LinkedHashSet` 去重；:536 每次合并新建 `LongHashSet` 与数组并排序。
- **方案**：签名统一为原生 `LongList`，删除 `PrimitiveLongView`；`authorizedCancellations` 已有序，直接做归并。
- **验证**：`OrderAdmissionAllocationBenchmark -prof gc`。

### P12【假设】撮合线程里构建结算计划
- **位置**：`MatcherSettlementEvent.java:379-420`（`publishDirectNativeResult` 调用 `buildDirectNative`）。
- **担忧**：每笔成交的费用、保证金计算和结算计划构建都跑在单个 matcher 线程，可能拉长每分片的串行服务时间。
- **下一步**：先拿 `core-matcher-*` 线程 CPU 样本，看撮合本身与 `buildDirectNative` 各占多少，再决定是否交给 Lane。

### P13【报告】热路径上的冷操作与小分配
- `orchestration/RuntimeStateMaterializationCache.current()` 在 Owner 线程 `while (!tryAcquire…) onSpinWait()`，为查 hash 会物化整个状态；需确认调用是否只在查询边界。
- `state/TradingRuntimeState.java:4302` 终态订单清理路径上 `new ArrayList<>`；是否热路径待确认。
- `matching/PendingMatchingRing`（`orchestration/PendingMatchingRing.java`）用 8 个并行 `int[]` 做双向链表，对 cache locality 不利；只有 owner 线程使用，无伪共享；【假设】，需 JMH 证实。

## 4. 缓存行与伪共享（F）

跨线程写入的相邻字段统一处理原则：写者分离到独立缓存行，单写者游标用 release / acquire 而非 volatile 全 fence，对端游标本地缓存。

### F1【已核（P1 同源）】撮合流水线 volatile 字段无填充
- **位置**：`matcher/MatcherCommandPipeline.java:34-48,184,395`。
- **证据**：owner 每次提交写 `submissionHighWaterMark = Math.max(...)`（值没变也写）；matcher 每次完成写 `completionHighWaterMark`（:395）并读 owner 写的 `consumedPosition`（:396）；这两个诊断字段与 `worker`、`accepting`、`publicationFailure` 在同一对象，后三者 owner 每次 submit / poll 都读，matcher 的写会使其所在缓存行反复失效。`Slot`（:537）是 7 个字段的小对象，数组里相邻分配，owner 写 N+1 时 matcher 写 N。
- **方案**：两个 HWM 仅在 `WAIT_DIAGNOSTICS` 时更新，或改线程本地普通 long；`accepting`、`publicationFailure` 等只读标志放进 padded 持有类；`Slot` 做 64B 填充（见 F9）。

### F2【已核】结算 ring 游标相邻 volatile
- **位置**：`lane/SettlementLaneWorker.java:594-595`（`MatcherSettlementRing`）。
- **证据**：`private volatile long producerPosition; private volatile long consumerPosition;` 相邻同对象；同文件 `PaddedSequence`（:51-54、:582）已有填充却没被 ring 使用。
- **机制**：Matcher 写 producer，Lane 写 consumer，每次 publish 与 consume 互相使缓存行失效；`events[]`、`mask` 也在同一行；相邻槽位 Matcher 写入、Lane 置 null 也同行；publish 还读对端 volatile 并做一次 StoreLoad 写。
- **方案**：游标改 `PaddedSequence`，生产者缓存 consumerPosition、消费者缓存 producerPosition，仅在判满 / 判空时刷新；去掉槽位置 null，改用游标判空。
- **注意**：`parked` 握手需要 StoreLoad，生产端 volatile 要保留（见 L3）。
- **验证**：双线程 SPSC JMH + `-prof perfnorm`；`perf c2c` 看 HITM；JFR 看 Lane 线程占比。

### F3【报告】Owner 入口队列与出口队列
- **位置**：`orchestration/TradingOwnerLoop.java:44-45,185,297-322`；`ClusterServiceEgress.java:34-35`。
- **证据**：
  - `inputProduced`（回调线程写）与 volatile `inputConsumed`（Owner 每命令写，带 StoreLoad）相邻。
  - 8192 个约 56B 的 `Input` 连续分配；生产者 `prepare()` 与 Owner `clear()` 写相邻对象，伪共享；每条命令还要走一次 `availableInput` 回收往返。
  - **D4 后续修复**：旧 `Input` 与 `availableInput` 已删除，改为唯一 `OwnerIngressRing`；固定 Entry 的缓存行布局及本条其它伪共享假设仍未验收。
  - 出口：`outputProduced`（Owner 写）、`volatile outputConsumed`（Cluster 线程逐响应 `+=`）、`outputOverflow` 同对象，被两个线程逐响应写，伪共享加全 fence；`Output` 槽同一模式。
- **方案**：单个 2 的幂环，字段拆成数组，计数器各自填充，用 ordered 写发布；去掉回收队列（见 D4）；消费端 `setRelease`，各计数放独立 padded 单元。
- **验证**：`perf c2c`；`OwnerPublicationBenchmark -prof perfnorm`。

### F4【报告】lane 下标数组伪共享，且与 `AccountLaneState` 重复
- **位置**：`state/TradingRuntimeState.java:156,956,1145,1321`。
- **证据**：`publishedLaneCommittedSequences[laneId] = lane.committedSequence();` 不同 Lane 线程写同一 `long[]` 相邻槽位（一行约 8 个 lane）；Owner 在 :1321 普通读，没有 acquire；该值在 `AccountLaneState.committedSequence` 已有，违反「一个状态一个权威来源」。`recordLaneOperation` 还有 4 个 `long[][]` 指标。
- **方案**：删除这个数组，Owner 对 `METRIC_COMMITTED` 做 acquire 读；4 个 `long[][]` 指标并入 `AccountLaneState` 的每 lane 指标对象，用 16-long stride（`LaneCommitEvent` 已有此做法）。

### F5【报告】ring 槽位同行读写
- **位置**：`state/LaneSequenceQueue.java:30-37`，`SettlementLaneWorker` 的 `commands[]`、`admissionCommands[]`。
- **证据**：生产者写槽 i+1，消费者清槽 i，同一缓存行。
- **方案**：不清槽，用序列号单调判空，让消费者只读槽。

### F6【已核】就绪位图 `AtomicLong`
- **位置**：`state/TradingRuntimeState.java:1584-1594`（`settlementReadyLanes`、`directSettlementReadyLanes`、`placeAdmissionReadyLanes`，另有 `accountAdmissionReadyShards`）。
- **证据**：3 到 4 个 `AtomicLong` 对象连续声明，大概率连续堆分配到同一缓存行；多个 Lane / Matcher 线程用带 lambda 的 `getAndAccumulate(.., SET_READY_BITS)` 逐次置位（:1616、1635、1672、1681 没有预检；:1602 清位有预检），Owner 空转时轮询 `get()`。
- **方案**：每个位图独立 padding；置位前先判 `(get() & bit) != 0`，已置则不写。
- **说明**：子审计建议改 `getAndBitwiseOr/And`；整理时的判断是它在 x86 上同样走 CAS 循环，主要收益是省掉 lambda，真正收益来自 padding 与预检。
- **验证**：`perf c2c`。

### F7【报告】`MatcherPipelineGroup` 完成掩码
- **位置**：`matcher/MatcherPipelineGroup.java:18-29,83`。
- **证据**：所有 matcher 线程对同一个未填充的 `AtomicLong` 做 `getAndAccumulate`，Owner 每轮 poll 读它；每个分片的 `completedPosition` 已是权威来源，这个掩码是第二份状态。
- **方案**：Owner 直接读各分片 `completedPosition`，删除掩码（多 matcher 时才明显）。

### F8【报告】`dispatchInFlight` 与 `matcherFailure`
- **位置**：`matching/DeterministicExchangeCoreAdapter.java:64,66`。
- **证据**：`dispatchInFlight`（`AtomicInteger`）跨 matcher 分片共享，且与每命令都读的 `matcherFailure`（:64）大概率同一缓存行。
- **方案**：每分片一个计数（多 matcher 时才明显）；`matcherFailure` 与计数分到不同缓存行。

### F9【报告】padding 只有 56B；槽位对象相邻
- **位置**：`SettlementLaneWorker.java:582`、`MatcherCommandPipeline.java:559`、`LaneSequenceQueue.java:55`、`OwnerIdleStrategy.java:74`。
- **证据**：7 个 long 的 padding 只有 56B，对相邻行预取不足；HotSpot 可能重排字段；环槽位对象（`Slot` 约 48B）相邻，可能伪共享。
- **方案**：统一扩到 128B（15 个 long）；本机（Apple Silicon）缓存行为 128B，x86 相邻行预取也会放大伪共享；用 JOL 确认实际字段布局。

### F10【假设】`CommandSlot` 里 matcher 与 owner 共写字段
- **位置**：`orchestration/CommandSlot.java:660,663,707-713`。
- **证据**：`completedMatchingResult`、`submittedMatcherShard` 为 volatile，matcher 与 owner 都写，且和 owner 改的热字段在同一个大对象里；每单 matcher 做两次 volatile 写，使 owner 字段所在缓存行失效。
- **方案**：拆成单独的填充小交接对象，用 `setRelease`。
- **验证**：`perf c2c`。

## 5. 锁、fence 与线程模型（L）

### L1【已核（P1 / F1）】每笔订单 unpark 与 volatile 读改写
见 P1。附：`submissionHighWaterMark = Math.max(...)` 是一次 volatile 读改写（全 fence），建议改 plain / opaque 且只在新高时写。

### L2【报告】重复且全 volatile 的位置游标
- **位置**：`matcher/MatcherCommandPipeline.java:384-385`。
- **证据**：`workerPosition.value = position; completedPosition.value = position;` 两者永远相等，`workerPosition` 只用于 `submissionDepth()` 诊断；再加 owner 的 `consumedPosition` 与 `submittedPosition`，每命令共 4 次 volatile 存储（x86 上每次是 `lock` 指令）。
- **方案**：删除 `workerPosition`；单写者游标改 `VarHandle.setRelease / getAcquire`。

### L3【报告】单写者游标多余 fence
- **位置**：`lane/SettlementLaneWorker.java:280,319`（`MatcherSettlementRing.poll` 的 `consumerPosition`）。
- **证据**：消费端每命令一次 volatile 写（lock 前缀），没有 Dekker 对端。
- **方案**：消费端 `setRelease`；生产端保持 volatile，因为要与 `parked` 配对（:146-150）。

### L4【已核（C2 同源）】出口溢出关闭全部会话
见 C2。

### L5【报告】Lane 等待策略
- **位置**：`SettlementLaneWorker.java:188`、:99、:566。
- **证据**：环满时 `parkNanos(1L)` 实际约 50µs；`spinLimit` 默认 0（已核，:99 `SPIN_LIMIT_PROPERTY` 默认值 "0"）；默认等待策略是否为 BLOCKING 未核实（`configuredWaitStrategy()`）。若是 BLOCKING，空闲后每次唤醒都是 futex 系统调用。
- **方案**：评估有限自旋加 park 的退避；环满使用退避而非 `parkNanos(1)`。

### L6【报告】无 CPU 亲和与线程超订风险
- **证据**：全模块 grep 无 CPU pinning；Lane、Matcher、Owner、SystemLedger、dispatcher 都在自旋。
- **方案**：生产与压测环境对关键线程做 pin，并隔离核心；记录线程数与核数关系。

### L7【已完成，随 D3】删除交易指令的 `CompletableFuture` 包装
- `executeWithEvidence*`、`executeControlWithEvidence*` 和交易 Async 家族已删除。普通交易使用预构建 Direct 事实，强平/交割控制撤单采用显式同步入口并在原 Matcher worker 执行；没有把同一包装改名保留。
- 查询、注册及快照边界原有 Future 契约仍保留，未宣称所有异步机制均已删除。必要功能验证见 D3；本轮未采集动态分配数据。

### L8【已完成，随 D2】`ClusterCommandWindow` 兼容回调
- 原 O(n) `complete()` 已删除。普通订单由有序队首直接接收响应；控制命令在交易窗口排空后按序号保存自身响应，内部触发子单独立提交，不再扫描窗口。验证见 D2。

## 6. 过度设计（D）

对照 `AGENTS.md`「设计约束：业务语义与可读性优先」。

### D1【已完成】假 `Cluster` 实现
- **原问题（审计时）**：`orchestration/OwnerLogContext.java` 实现 `Cluster`，多个 SDK 方法抛 `UnsupportedOperationException`；`orchestration/ClusterServiceEgress.java` 的 `TransportSession` 同样伪实现 `ClientSession`，职责边界不清。
- **完成提交**：`c2a7c5a6e`（2026-10-15）。`OwnerLogContext` 已改为普通日志元数据对象，不再实现 `Cluster`；`TransportSession` 为服务线程内的具体出口类，不再实现 `ClientSession`，已删除未实现或无调用的 SDK 方法。
- **线程边界**：Owner 入口、命令窗口及响应出口槽仅携带 `long sessionId`；真实 `ClientSession` 的连接、发送和关闭由 Cluster 服务线程独占。响应消费时按 ID 查既有会话表，关闭、移除或旧任期的响应丢弃并归还租约；保留背压 FIFO 与有序提交。
- **验证**：111 项必要用例通过（service 109 项、受影响 benchmark 夹具 2 项），失败、错误、跳过均为 0，详见 [D1 功能验证](validation/d1-complete-20261015.json)。该记录仅覆盖所选必要用例，不代表全量测试或其他审计项完成。

### D2【已完成】与「不要 fallback / legacy」冲突的残留
- **完成日期**：2026-10-15。以下为原审计项的逐项处理结果。
- **旧结算派发**：删除 `MatcherSettlementDispatcher.dispatchOwnerControlled`、`TradingRuntimeState.dispatchOwnerControlledSettlement` 及 Owner 侧预派发分支；改单统一预留 Matcher 发布槽，由 Matcher 将事实投递至 Lane。删除 `MatcherSettlementEvent` 的旧派发标记、后补提交 fence 入口及过时说明；发布校验要求已预留的 Matcher 槽。
- **订单 ID 兼容集合**：删除 `ActiveOrderIndex.ids()` 的四种 boxed `NavigableSet` API，调用方改用现有 primitive 查询。删除仅由旧查询路径使用、已无调用的 `PositionCloseCapacity.inspect/inspectRuntime`，平仓准入只保留实际使用的显式 Lane 检查。
- **不可变 ID 列表**：`ImmutableLongArrayList` 仍用于命令、资金费分页及协议的实际 `List<Long>` 契约，不是第二套业务路径；删除无调用的 `copyOf`、`sortedDistinct`、`preservePrimitive` 和防御复制构造分支，内部继续使用 primitive 存储及访问。
- **窗口完成回调**：删除 O(n) `ClusterCommandWindow.complete()`。普通订单响应由有序队首保存；控制命令回调只保存自身序号的结果，保留触发/OCO 内部子单的独立完成语义。
- **Lane 响应**：`LaneOrderResultTarget` 必须实现响应与长度，不再默认返回 null 让 Owner 重查、重编码。Lane 捕获未变化订单及终态后镜像，空结果明确使用空数组；Owner 直接消费捕获结果。删除已无作用的单笔 Matcher 结果回调。
- **响应存储核查**：合并 `ResponseArena` 的重复申请实现，删除 `acquireExactSlot()`。大响应及所有池槽仍被传输/幂等结果持有时，独立存储是完成响应所需的容量机制，已确认必须保留；不得因池满丢失已提交交易的响应。新增用例验证其与池内租约、复用及数据内容相互隔离。C1 的引用计数并发审计仍独立待办。
- **验证**：HotSpot JDK 27 下 153 项必要功能用例通过（service 151 项、受影响 benchmark 夹具 2 项），失败、错误、跳过均为 0；包含六产品线成交资金核对、跨 Lane IOC、改单/撤单、批量、触发/OCO、连续 Ledger 与槽位复用。详见 [D2 功能验证](validation/d2-complete-20261015.json)。未执行压测、快照、恢复或 wallet，不据此宣称其他 D 项或整体性能目标完成。
- **复审补齐（2026-10-15，基于 `53f778a40`）**：上一轮仍漏掉直达事件的非 Matcher 发布开关、单笔编码异常转为空成功，以及批量无响应池构造分支。本次删除模式开关，直达发布统一要求已预留；单笔/批量编码成功后才公开结果，失败清理租约并上抛；批量必须使用实际响应池，测试也按真实租约转移和复用执行。同时修正裸数组独立存储路径额外创建 `Slot` 描述符的问题，仍共享唯一池槽申请逻辑。
- **复审验证**：3 个新增回归用例在修复前均失败；修复后 158 项必要功能用例通过（service 156 项、基准夹具 2 项），失败、错误、跳过均为 0。额外覆盖批量重编码失败及真实四 Lane 延迟消费票据时的回收保护，详见 [D2 复审验证](validation/d2-review-20261015.json)。原 D2 清单内未再发现未处理项；C1 等仍按各自范围待办（D3 后续完成见下节），未执行压测、快照或恢复。

### D3【已完成】交易统一 Direct，清除适配器与 Owner 双路径
- **入口**：`MatcherCommandSubmission.prepareMatchingCommand` 只组装既有命令槽任务。普通 PLACE 必须持有同一次 Lane 准入输出；顺序批量持有冻结的 `OrderRuntime` 收据，触发单持有已冻结的子单输入；不再回读 Owner 重建订单。CANCEL 使用既有路由索引；REPLACE/AMEND 使用已解析准入。
- **删除**：适配器的裸同步交易、Async、WithEvidence、通用 evidence/前置撤单 Supplier 包装及无调用的替换重载；`MatchingSubmission`、旧证据 DTO 工厂；批量结果数组、延后结算输入接口及对应派发入口；Owner 收到撮合结果后重建/补派发结算、旧 cancel continuation、分区扫描与缓存游标。两个已失去生产调用的 Owner `matchingOrder` 方法也已删除。
- **前置撤单**：在复用的 `CommandSlot` 任务中按顺序执行并记录原 Direct 事实的 primitive 前缀，首次失败停止；保留 post-only 业务拒绝与致命部分变更的原区分。普通单和批量仍按 Lane、Ledger 完成条件有序退休。
- **控制路径**：强平、批量强平及交割保留真实控制撤单业务，使用 `cancelControlOrders` / `cancelControlOrder` / `completeControlCancellations` 显式入口。每条原生序号在实际撮合分片校验；汇总只绑定控制序列，不比较不同订单簿的最大 native sequence。去掉旧通用 callback/Future 链和临时 `CancelBatchOutcome`。
- **测试及基准**：测试迁到真实预留 Direct 槽；删除仅服务于旧 Future 与旧分区扫描的测试实现，保留真实分片并发、停机毒化、序号、部分撤单、资金与槽回收验证。移除多生产者直接调用旧适配器的 `ExchangeCoreConcurrentBenchmark`、裸 API 的 `MarketSlippageGuardBenchmark` 及容量入口的旧调用，保留使用实际交易流水线的基准。
- **验证**：HotSpot JDK 27，195 项必要功能用例通过（service 193、基准夹具 2），失败、错误、跳过均为 0；直接依赖及基准模块编译通过。覆盖六产品线资金守恒、Direct 批量共享 maker、跨分片强平撤单、交割/行权及连续 Ledger。详见 [D3 功能验证](validation/d3-complete-20261015.json)。
- **范围限制**：最终选择器不包含快照/恢复、wallet、JMH/JFR 或压测。首轮误选夹带快照的旧批量测试，产生的失败单独留存，不计入最终验收；没有据此宣称快照问题已修复。D3 为结构及功能验收，不宣称已达到百字节或吞吐目标；C1/C3 等仍独立待办（D4 后续完成见下节）。

### D4【已完成】删除重复中继，Owner 直接消费唯一输入环
- **唯一入口**：`TradingOwnerLoop.input` 与 `TradingCoreOwner.ingress` 引用同一个 `OwnerIngressRing`。它沿用 Agrona SPSC 队列和固定预分配槽，承担 Cluster→Owner 的真实线程交接；Owner 直接 peek 原始日志记录，不再搬运到另一 FIFO。
- **删除**：`PendingClusterIngress`、Owner `enqueueCommittedCommand` / `awaitIngressCapacity`、`availableInput`、`unpublishedInput`、`TradingOwnerLoop.Input` 及回收往返。`OwnerCommandPipelineState` 只持有已准入窗口与控制阶段引用，不再拥有待处理队列。
- **顺序及租约**：交易进入原 `ClusterCommandWindow` 后释放输入槽，窗口继续持有消息租约；控制命令完成前保留原环头。槽先清空，再由 SPSC poll 发布消费进度，生产者才能绕回重用。失败退出先停止 workers、清理窗口，再释放环内未准入消息；服务端入队失败归还未交接的 decoder 租约。
- **边界任务**：初始化、角色切换和终止动作继续使用同一个有序输入环；等待前缀只推进其前面的业务及窗口，不能等待当前动作自身或越过它执行后缀。入口阻塞不靠“队列非空”重复唤醒完整 Owner 流程。
- **容量及配置**：唯一输入环为 8192 槽 / 64 MiB，完整队列或字节预算耗尽时由 Cluster 入口等待进展，不产生业务拒绝。删除第二 FIFO 后总积压预算相应收敛，不再叠加两份 64 MiB。`surprising.aeron.owner-input-batch-size` 仍接受 1–64、默认 64，改为约束每轮直接交易准入数量；原完成工作预算及截止时间保留。输入诊断以同一环的入队时刻计时，不再存在中继阶段驻留。
- **验证**：HotSpot JDK 27，85 项必要功能用例通过（service 83、基准夹具 2），失败、错误和跳过均为 0；直接依赖及基准模块编译通过。覆盖 8192 槽满环稳定性、两次完整绕回、64 MiB 背压、中断后重试、消息租约、角色边界、六产品线交易/查询及实际 Owner 流水线。见 [D4 功能验证](validation/d4-complete-20261015.json)。
- **范围**：新增 `OwnerIngressRing` 只封装真实 SPSC 交接及其容量/槽生命周期，没有新增业务镜像、接口、线程、队列层或任务阶段。未执行快照/恢复、wallet、压测或 JMH/JFR；F8 其余缓存行问题及性能收益不在本次完成结论中。

### D5【报告】空壳 Context 接口层
- **位置**：`command/` 下 `BalanceCommandContext:6`、`FeePolicyCommandContext:6` 是空 marker；其余 6 个子接口都 `extends CommandResultContext`。
- **证据**：grep 显示实际落地只有 `TradingCoreRuntime` 与 `DirectCommandSlot`，未逐一确认。
- **违反**：禁止 Context 后缀；单一实现不建接口。
- **方案**：删空接口，命令类直接依赖具体 owner 类型。

### D6【报告】死代码与待清理项
- `state/TradingRuntimeState.java:172-178`：`singleWriterInline` 常量、空实现 setter；`isPublishMirrorEnabled` 恒返回 false。
- `orchestration/TerminalStateRetention.java:52-75`：`observe / observeAcknowledgedOrders`，及装箱排序的 `forEachSorted`（:445）：CodeGraph 查不到调用方，应删除（删前再查一次）。
- `matching/MatcherEvidenceLedger.java:49-54`：`issuedSequences` 与 `shardSequences` 单线程下同步前进，有两份冗余。
- 基准污染 main：`TradingCoreRuntime.probeValue` 只被测试使用；`TradingRuntimeState:4955` 注释写明为基准提供 detached 值；`CoreMatchingPhaseMetrics` 在主流程有 43 处探针。基准类本身已隔离在 benchmarks 模块（这点是好的）。

### D7【报告】超大类与职责混杂
- `state/TradingRuntimeState.java`：5332 行、约 350 到 390 个方法，混有 lane 拓扑、指标、对象池、变更缓冲、控制任务、转账、触发、算法单、物化，以及 `TreeMap<Long,Long> pendingTransferUsers`（:198）。
- `orchestration/CommandSlot.java`：承载 PLACE / CANCEL / REPLACE / AMEND / TRIGGER / BATCH 的万能可变记录，另有一个 `DirectCommandSlot`。
- 其余大类：`TradingCoreRuntime` 1604 行、`OrderedCommitCoordinator` 1444 行、`OrderBatchExecutor` 1328 行。
- **方案**：不要机械拆类。先只迁出指标（到 `AccountLaneState`）和待划转（到 `TreasuryRuntime`）；`CommandSlot` 先把「只被单个操作写入」的字段下沉。

### D8【报告】`OrderedCommitCoordinator` 收尾逻辑重复
- **位置**：`orchestration/OrderedCommitCoordinator.java`：`finishAppliedMatching`（457-540）与 `completeDispatchedMatcherSettlement`（524-620）收尾逻辑重复，前者带缩进错位的拷贝残留。
- **方案**：合并为一个收尾方法，并保持顺序语义。

### D9【报告】命名与层数
- 同一个 Owner 有多个名字：`TradingOwnerLoop` 里字段叫 `processor`（:59），类叫 `TradingCoreOwner`，下面是 `TradingCoreRuntime`。
- 下单链路约 9 跳：Cluster → OwnerLoop → CoreOwner → Runtime → Admission / MatchingFlow / Submission → CommandSlot → MatcherPipeline → Adapter（交接次数未逐一数）。
- **方案**：统一命名（`processor` 字段改名）；在 D10 之后再评估可合并的层。

### D10【报告】`TradingCoreRuntime` 是「拆文件不拆状态」的枢纽，另有状态重复
- **位置**：`TradingCoreRuntime:110-187` 持有约 25 个子对象，每个都拿 `this`。子类对 `owner.` 的访问次数：`OrderedCommitCoordinator` 317、`OrderBatchExecutor` 221、`MatchingCommandAdmission` 117、`CoreMatchingFlow` 116、`CoreRuntimeLifecycle` 53。例：`CoreMatchingFlow.recordRejectedMatching`（:54-79）直接改写 `owner.appliedCommandCount`、`lastSourceSequences`、`pendingMatching`。
- **状态重复（须列出权威来源表）**：
  - 订单（4 份）：`TradingRuntimeState` 的 lane 订单、`ActiveOrderIndex`、`AdmissionOrderIndex`、撮合器自己的簿。
  - 命令（6 份）：`pendingMatching`、`PendingCommandIdIndex`、`SourceSequenceIndex`、`CommandResultLedger`、`TerminalStateRetention`、`TerminalTombstoneStore`。
  - `TradingCoreRuntime` 另有 8 个业务索引，一致性关系需文档化。
- **违反**：一个状态一个权威来源；不为缩短文件机械拆类。
- **方案**：把 `pendingMatching`、`appliedCommandCount`、`committedCoreSequence` 的变更收进一个持有者；先合并 `CoreMatchingFlow` 与 `MatchingCommandAdmission` 中改状态的部分。风险中高，涉及确定性顺序，必须分步做并跑恢复测试。

### D11【报告】client / protocol
- Core 入口用 flyweight，没有问题；`byte[]` / `ArrayList` 分配主要在客户端与查询解码。
- `AeronClientPool` 的包级 `Session` / `SessionFactory` 是否单一实现未验证。

## 7. 已核实无问题（X，不需处理）

- `TradingCoreState.fullBusinessStateHash` 只在查询路径调用，符合 `AGENTS.md`。
- `requireConserved` 实际是 O(n)，在 SystemLedger 线程执行。
- 控制任务 `completedLanes` 已按 16-long 对齐。
- 身份注册表使用查找暂存对象，没有每次分配 key。
- `StateMapSupport.LazyDeltaMap` 的 `synchronized` 和装箱 `TreeMap` 在快照物化路径，属冷路径。
- `MatcherEvidenceLedger`：没有排序、rolling hash 或 canonicalization 落在热路径；数组按 128B 步长填充，用 acquire / release，实现正确。
- `OrderBookDirectImpl`：ART 加对象池，没有明显问题。
- `LaneSequenceQueue` 已缓存 `observedProducer`；主环是 SPSC，无 CAS。
- `PendingMatchingRing`：只有 owner 线程使用，无伪共享（局部性问题见 P13）。

## 8. 验证与基线要求

按 `AGENTS-performance.md` 的统一压测标准，改动前后都要覆盖受影响路径：

| 要验证的问题 | 手段 |
|---|---|
| C1 引用计数竞争 | JCStress；`ResponseArena` 并发单测；线程审计 |
| C2 溢出关会话 | 出口队列溢出压测，观察会话关闭数、重连放大 |
| C3 双套算术 | `RuntimeDerivativeFillCalculatorTest`；快照恢复一致性；资金守恒（期初、成交、手续费、资金费、强平、交割、期末） |
| P1 / F1 / L1 unpark 与 volatile | JMH `-prof perfnorm`；JFR `ThreadPark`、`jdk.NativeMethodSample`；owner 线程 syscall 数 |
| F2 / F3 / F4 / F5 / F6 / F9 / F10 伪共享 | `perf c2c`（HITM）；JMH `-prof perfnorm`；JOL 看字段布局 |
| P2 Lane scope | JFR 方法占比；`AccountLaneCommitBenchmark` 前后对比 |
| 所有分配类（P3、P5、P6、P7、P9、P10、P11） | JMH `-prof gc`（B/op）；JFR `ObjectAllocationSample`、`ObjectAllocationOutsideTLAB`、`ThreadAllocationStatistics` |
| P4 指纹 | `CommandDecodingBenchmark`；cluster 线程 `ExecutionSample` |
| P7 网关 | JFR `JavaMonitorEnter`、`ThreadPark`；`HttpOpenLoopWorkload` |
| P12 结算计划位置 | `core-matcher-*` 线程 CPU 样本 |
| 删除类（D 系列） | 先 `codegraph_callers` / `codegraph_impact`；再跑受影响模块测试与快照恢复 |

每项主链路改动按标准新增或更新覆盖真实改动路径的 JMH，并只在当前 `master` 上取基线；诊断一次只改一个因素，预先写明预期变化和证伪条件；结果追加到 `PERFORMANCE_VALIDATION.md`。

## 9. 建议执行顺序

1. **确认类**（先于一切性能优化）：C1（JCStress）、C2（决定溢出策略）。
2. **局部低风险改动**（先写 JMH，再改，再测）：P1+F1+L1（unpark 标志与 HWM）、F2+L3（结算 ring 填充与 `setRelease`）、P2（Lane scope）。
3. **删除类**（风险低）：D5、D6。D1 已于 `c2a7c5a6e` 完成；D2、L8 已于 2026-10-15 完成。
4. **中等改动**：P3（最优价）、P4（指纹）、P5（traceId）、P7（网关 mailbox）、P9–P11、F3–F7、F9、L2。
5. **需要先补测试与对账**：C3、D8（D3、D4 已完成）。
6. **最后**：D7、D9、D10（状态枢纽与重复状态），分步进行并跑恢复测试。

## 10. 未覆盖与不确定项

- 只做了模式搜索、未细读：`state/index/TriggerOrderIndex`、`state/index/ActiveOrderIndex`、`RuntimeDerivativeLiquidationProcessor`、`RuntimePerpetualFundingProcessor`。
- `OrderedCommitCoordinator`、`OrderBatchExecutor`、`DeterministicExchangeCoreAdapter` 内部大部分没有逐行读。
- `CommandFingerprint` 所在模块未核实（P4）。
- C1 的线程模型（retain 与 release 是否确实跨线程）未核实。
- L5 中默认等待策略是否为 BLOCKING 未核实。
- `AeronClientPool` 的 `Session` / `SessionFactory` 是否单一实现未验证。
- 全部「每单对象数」「每命令次数」为静态估算，必须以 JMH / JFR 实测替代。
- 工作区有未提交改动（`AeronClientPool.java`、`CoreTestCompletion.java` 等），行号以审计时为准。
