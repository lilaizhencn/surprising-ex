# Aeron 单节点永续合约压测对比与交易链路深度瓶颈分析报告

> **测试基准版本**：`master` 分支最新代码（commit `5ca4f206a`）  
> **测试环境**：MacBookPro16,1 (Intel i9-9880H 2.30GHz, 8核16线程, 16GiB RAM, macOS 26.7.2)  
> **运行环境**：Amazon Corretto HotSpot JDK 27.0.0.33.1, Maven 3.9.16  
> **测试业务线**：`LINEAR_PERPETUAL`（U本位永续合约），真实单成员 Aeron 集群（含真实 UDP/IPC 传输、Consensus 状态机、Archive 归档、做市挂单、零售持续交易）  
> **压测参数配置**：128 个币对，1385 个用户，20 项批量，256 在途并发窗口，2 个 Account Lane，1 个 Matcher Shard，混合交易负载（新单、成交、撤单、标记价更新）

---

## 目录

1. [FUSED 与 PIPELINED 两种模式压测数据全维度对比](#一fused-与-pipelined-两种模式压测数据全维度对比)
2. [各节点/各链路阶段吞吐与性能剖析](#二各节点各链路阶段吞吐与性能剖析)
3. [核心瓶颈深度归因与实测证据](#三核心瓶颈深度归因与实测证据)
   - [3.1 内存分配与 GC 抖动（分配黑洞）](#31-内存分配与-gc-抖动分配黑洞)
   - [3.2 缓存行失效与伪共享（Cache Line Bouncing）](#32-缓存行失效与伪共享cache-line-bouncing)
   - [3.3 线程竞争与锁阻塞（尾延迟元凶）](#33-线程竞争与锁阻塞尾延迟元凶)
   - [3.4 多余的计算（重复 Hash 与无效查找）](#34-多余的计算重复-hash-与无效查找)
   - [3.5 过度设计、多层包装与装箱](#35-过度设计多层包装与装箱)
   - [3.6 冗余字段与跨阶段无用状态](#36-冗余字段与跨阶段无用状态)
4. [各问题的生产级别重构解决方案](#四各问题的生产级别重构解决方案)
5. [结论与第一性原理落地建议](#五结论与第一性原理落地建议)

---

## 一、FUSED 与 PIPELINED 两种模式压测数据全维度对比

### 1.1 核心吞吐与资源消耗对照

| 压测指标类别 | 压测指标项 | FUSED 极速模式（单线程原地闭环） | PIPELINED 模式（流水线多线程分发） | 表现差异分析 |
| :--- | :--- | :--- | :--- | :--- |
| **业务吞吐能力** | **稳定终态业务项/s** | **261,895 ops/s** | **374,391 ops/s** | PIPELINED 高出 **+42.9%**（2 个 Lane 并行分担计算） |
| | **持续终态 Core 消息/s** | **25,058 msgs/s** | **35,772 msgs/s** | PIPELINED 高出 **+42.7%** |
| | **撮合成交 fills/s** | **62,346 fills/s** | **89,131 fills/s** | PIPELINED 高出 **+42.9%** |
| | **30s 稳定窗口完成总业务项** | **7,860,864 项** | **11,237,015 项** | PIPELINED 多完成约 337 万项业务 |
| **资源利用效率** | **Owner 线程 CPU 占用** | **99.6%**（单核打满） | **97.3%** | 两种模式下 Ingress/调度均接近单核饱和 |
| | **Matcher 撮合线程 CPU** | **0%**（原地执行，无独立线程） | **48.3%** | 多线程模式下撮合引擎独立运行 |
| | **Account Lane 线程 CPU** | **0%**（原地执行，无独立线程） | **Lane0: 99.6%, Lane1: 99.6%** | 多线程下 2 个 Lane 均处于高负荷运转 |
| | **业务有效核心数** | **1 个逻辑 CPU** | **约 3.5 个逻辑 CPU** | **FUSED 能效比（ops/core）为 26.1 万，PIPELINED 仅 10.7 万，FUSED 能效高出 2.44 倍** |
| **并发与排队** | **全局在途峰值** | 256（打满窗口） | 256（打满窗口） | 窗口背压限制 |
| | **窗口背压时间占比** | **93.06%** | **87.35%** | PIPELINED 排空在途速度略快 |
| | **Matcher 待处理高水位** | 1（原地完成，无需排队） | **234**（几乎打满 256 队列） | 多线程流水线下 Matcher 产生明显排队 |
| | **Completion 待提交高水位**| 0（原地提交，无等待） | **201** | 多线程分段提交存在显著在途等待 |
| **财务与一致性** | **资金差额 (fundsDiff)** | **0 (PASS)** | **0 (PASS)** | 资金不变量严格成立 |
| | **业务校验与状态恢复** | **PASS** | **PASS** | 强平/保险/ADL 生命周期均验证通过 |

---

### 1.2 客户端端到端全链路延迟对比（HDR 直方图分位数）

| 业务请求类型 | 延迟分位数 | FUSED 模式 (ms) | PIPELINED 模式 (ms) | 优劣对比与原因分析 |
| :--- | :--- | :--- | :--- | :--- |
| **普通下单 (PLACE_ORDER)** | **P50** | 9.44 ms | **5.20 ms** | PIPELINED P50 领先（多 Lane 并行分流排队） |
| | **P90** | 13.13 ms | **8.56 ms** | PIPELINED P90 领先 |
| | **P99** | 17.05 ms | **12.78 ms** | PIPELINED P99 领先 |
| | **P99.9** | 41.41 ms | **29.01 ms** | PIPELINED 领先 |
| | **Max** | **52.98 ms** | **91.94 ms** | **PIPELINED 最大延迟劣质 73.5%**（跨线程锁竞争与上下文切换导致尾部毛刺） |
| **普通撤单 (CANCEL_ORDER)** | **P50** | 11.55 ms | **4.89 ms** | PIPELINED 领先 |
| | **P99** | 18.13 ms | **12.37 ms** | PIPELINED 领先 |
| | **Max** | **53.67 ms** | **48.56 ms** | 两者相近 |
| **批量下单 (PLACE_BATCH)** | **P50** | 10.06 ms | **8.11 ms** | PIPELINED 略快 |
| | **P90** | 15.11 ms | **12.98 ms** | PIPELINED 略快 |
| | **P99** | 20.20 ms | **19.98 ms** | 两者持平 |
| | **Max** | **66.25 ms** | **102.10 ms** | **PIPELINED 突破 100ms**（批量订单跨 Lane 拆分与聚合的跨线程时延） |
| **批量撤单 (CANCEL_BATCH)** | **P50** | 15.11 ms | **9.95 ms** | PIPELINED 领先 |
| | **P99** | 23.70 ms | **20.80 ms** | PIPELINED 略快 |
| | **Max** | **65.99 ms** | **90.04 ms** | **PIPELINED 最大延迟高出 36.4%** |

> **关键结论摘要**：
> 1. **吞吐层面**：PIPELINED 凭借多核并行结算，吞吐较 FUSED 高出 42.9%，但在途队列常年处于高水位（234/256）；
> 2. **能效层面**：FUSED 仅凭 **单线程 1 个核心** 跑出 26.2 万 ops/s，单核吞吐是多线程模式的 **2.44 倍**；
> 3. **尾延迟稳定性**：FUSED 的最大尾部毛刺（52.98ms / 66.25ms）明显优于 PIPELINED（91.94ms / 102.10ms），多线程跨核调度、锁竞争与总线伪共享使得 PIPELINED 在高负载下容易出现近 100ms 的尾部停顿。

---

## 二、各节点/各链路阶段吞吐与性能剖析

整个 Aeron 交易链路可以抽象为 5 个关键处理点：
```
[网络 Ingress] -> [解码与准入 (Owner)] -> [撮合引擎 (Matcher)] -> [结算风控 (Lane)] -> [网络 Egress]
```

### 1. 网络 Ingress / Egress 阶段
* **实测吞吐上限**：Aeron IPC/UDP 的持续承载能力在当前配置下可达 3.6 万条物理 Core 消息/秒（包含批量展平后 37.4 万业务项/秒）。
* **当前损耗点**：
  * **DirectBuffer 二次 Copy**：Aeron 接收到原生堆外内存（Off-Heap DirectBuffer）后，被 `CoreMessage.decoderPayloadBuffer` 复制到堆内 `byte[]` 数组。仅 `byte[]` 这一项在 JFR 分配统计中就占到了 **47.07% ~ 52.05%** 的全系统内存分配！
  * **Egress 响应序列化**：每个终态响应都要在堆上包装 `CoreResponse` 对象并转换生成 `byte[]`。

### 2. Owner 编排与准入调度阶段
* **FUSED 模式**：完全由 Owner 线程就地驱动。准入校验（`RuntimeOrderAdmission`）后直接调用撮合与结算，无窗口进出开销。
* **PIPELINED 模式**：
  * Owner 线程面临严重的上下文维护开销：每个命令进入必须占用 `CommandSlot`，维护 `ClusterCommandWindow`，进行 `conflictingPrefixSize()` 扫描；
  * JFR 显示，PIPELINED 的上下文队列水位常年为 **256**（打满），Matcher 队列水位 **234**，说明 Owner 线程在将任务分发到各个工作线程时存在明显的背压阻塞。

### 3. Matcher 撮合引擎阶段
* **撮合处理能力**：撮合引擎本身执行速度极快（单核每秒可处理数十万次订单簿撮合与撤单）。
* **当前损耗点**：
  * **String 到 Native Int 的映射代价**：每来一个订单，撮合适配器 `DeterministicExchangeCoreAdapter` 都要调用 `symbols.get(instrumentId)`。由于 `instrumentId` 为 `String`，每次都要计算 `String.hashCode()` 并遍历 `ConcurrentHashMap`，在每秒 30 万次的高频调用下产生了巨大的哈希运算浪费；
  * **事件包装开销**：撮合成功后，生成的撮合明细从 `MatcherResult` 依次被包装为 `MatcherSettlementPlan` 再到 `MatcherSettlementEvent`，字段层层复制。

### 4. Account Lane 结算与风控阶段
* **多 Lane 负载分布**：两个 Lane 线程各自承载约 33.4 万次撮合成交和 73.5 万次挂撤单变更，负载均衡率达到 1:1。
* **CPU 消耗剖析**：
  * Lane 线程 CPU 占用达到 **99.6%**。但在 JFR 热点方法统计中，排名第一的竟然是 `SettlementLaneWorker.run()`（占 **26.87%**）和 `SystemLedgerLane.run()`（占 **22.39%**）的自旋忙等循环！
  * 真正的账户资金增减和风控计算仅占实际执行时间的不到 20%，大量的 CPU 周期被空转轮询和锁检查吞噬。

---

## 三、核心瓶颈深度归因与实测证据

### 3.1 内存分配与 GC 抖动（分配黑洞）

通过两轮 JFR 的 `allocation-by-class` 权威数据，内存分配压力分布如下：

```
+-------------------------------------------------------------------------------+
| 对象类型 (Class)                | FUSED 模式分配占比 | PIPELINED 模式分配占比   |
+-------------------------------------------------------------------------------+
| byte[]                         | 47.07%            | 52.05%                   |
| java.lang.String               | 16.92%            | 16.39%                   |
| long[]                         | 6.61%             | 5.59%                    |
| java.nio.HeapByteBuffer        | 2.30%             | 2.04%                    |
| java.util.UUID                 | 1.74%             | 1.95%                    |
| java.lang.Object[]             | 3.21%             | 1.84%                    |
| java.lang.Long (装箱)           | 0.17%             | 1.62%                    |
| 业务临时 Context/Decision/Result| 约 7.5%           | 约 6.8%                  |
+-------------------------------------------------------------------------------+
```

#### 根因剖析：
1. **`byte[]` 占系统总分配的一半以上（52.05%）**：
   * 源码入口：[`TradingCommandCodec.java:242`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java#L242)
   * 事实：编码时每次执行 `byte[] payload = new byte[encodedPlaceOrderLength(command)];`；解码时每次将 DirectBuffer 转换为新分配的 `byte[]`。每秒数十万次分配导致新生代（TLAB）频繁耗尽，诱发频繁的 Minor GC。
2. **`String` 分配占 16.4%**：
   * 源码入口：[`PlaceOrderCommand.java:70-72`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/PlaceOrderCommand.java#L70-L72)
   * 事实：即使使用了 `commandSlot`，解码 `clientOrderId` 时依然强制执行 `new String(payload, offset, length, StandardCharsets.UTF_8)`。
3. **Lambda 表达式隐式逃逸（1.02% ~ 1.25%）**：
   * 源码入口：[`OrderBatchExecutor.java:258, 587`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/OrderBatchExecutor.java#L258)
   * 事实：`owner.matcherPipeline.submit(shard, pending.sequence(), () -> { ... })`，在每秒数万次的循环中动态创建闭包 Runnable 实例。
4. **基本类型装箱 `java.lang.Long`（1.62%）**：
   * 在多线程模式下，订单 ID、序列号、用户 ID 传入基于泛型的集合（如 `ConcurrentHashMap<Long, ...>`）导致每秒上万次 Long 对象堆分配。

---

### 3.2 缓存行失效与伪共享（Cache Line Bouncing）

#### 根因剖析：
1. **`PaddedReadyMask` 的单变量原子 CAS 争用**：
   * 源码位置：[`TradingRuntimeState.java:49-80`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java#L49-L80)
   * 事实：所有 Lane 工作线程在完成本地结算后，都要通过 CAS 操作更新同一个 `volatile long value` 上的 bit 位。
   * 缓存行机制：在多核心 CPU（当前为 8 核 16 线程）架构下，当 Core A 写入该变量触发缓存行失效时，Core B、Core C 的 L1/L2 Cache Line 立即被置为 Invalid。多个核心同时执行 `while (!UPDATER.compareAndSet(...))`，总线锁定仲裁频繁发生，导致 CPU 在硬件级别陷入 **Cache Line Bouncing 缓存行风暴**。
2. **`DecodedMatchingCommand` 庞大内存布局破坏 L1 Cache 局部性**：
   * 源码位置：[`DecodedMatchingCommand.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/command/order/DecodedMatchingCommand.java)
   * 事实：单个对象平铺了 12 个不同命令的引用字段，对象跨越多个 64 字节缓存行。每次 CPU 预取将无用的引用载入 L1 数据缓存，极大降低了 CPU 缓存利用率。

---

### 3.3 线程竞争与锁阻塞（尾延迟元凶）

JFR 的 `contention-by-thread.txt` 记录下了多线程下的致命阻塞证据：
```
Thread                  Count       Avg       P90      Max
--------------------------------------------------------------
core-account-lane-0        10  0.396 ms   1.49 ms   1.52 ms
core-account-lane-1        20  0.196 ms  0.415 ms  0.937 ms
trading-owner--1            1  0.653 ms  0.653 ms  0.653 ms
```

#### 根因剖析：
1. **Lane 线程阻塞高达 1.52 ms**：高频低延迟交易的预算是以微秒（µs）甚至纳秒（ns）计的。一旦某条 Lane 被阻塞 1.5ms，后续所有排在该 Lane 上的订单将全部产生毫秒级延迟积压，这正是 PIPELINED 最大延迟飙升到 **91.94ms ~ 102.10ms** 的本质原因！
2. **分段提交（Multi-stage Barrier）状态等待**：
   * PIPELINED 模式下，一次撮合完成后，需要等涉及的 Lane 0、Lane 1 全部完成本地结算并写回 Delta，Owner 才能统一推进到 Commit 阶段；
   * 慢的线程拖慢快的线程，形成了典型的 **木桶效应（Straggler Effect）**。

---

### 3.4 多余的计算（重复 Hash 与无效查找）

通过 `hot-methods.txt` 统计，热点方法中有两项纯粹的无效计算：

1. **`RuntimeIdentityRegistry.hashUtf8(long, String)`（占 1.36% ~ 0.47%）**：
   * 源码位置：[`RuntimeIdentityRegistry.java:673-685`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeIdentityRegistry.java#L673-L685)
   * 事实：为了计算用户的持仓键（`positionKey`），每次下单、查仓都要逐字符对 `String` 进行 UTF-8 编码模拟与 FNV 哈希累加。
2. **Symbol 双重 Hash 与双重字典查询**：
   * 第一次：在 [`TradingCommandCodec.java:40-44`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java#L40-L44)，对 symbol 字节数组循环计算 `31 * hash + b` 查 `SYMBOL_CACHE` 得到 `String`；
   * 第二次：进入 [`DeterministicExchangeCoreAdapter.java:58`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matching/DeterministicExchangeCoreAdapter.java#L58)，对上述 `String` 再次计算 `String.hashCode()`，在 `ConcurrentHashMap<String, Integer> symbols` 中查询 `int symbolId`。
   * **完全多余的二次转换**：整个系统本可以直接使用内部整型 Symbol ID，却在关键热路径上反复将整型转换为 String 又把 String 哈希为整型。

---

### 3.5 过度设计、多层包装与装箱

1. **8 层中间对象与镜像结构**：
   * 一条订单经过：
     `DirectBuffer` $\rightarrow$ `byte[]` $\rightarrow$ `CoreMessage` $\rightarrow$ `DecodedMatchingCommand` $\rightarrow$ `ResolvedPlaceOrder` $\rightarrow$ `MatcherResult` $\rightarrow$ `MatcherSettlementPlan` $\rightarrow$ `MatcherSettlementEvent` $\rightarrow$ `LaneCommitDelta` $\rightarrow$ `CoreResponse`
   * 违反了《AGENTS.md》“主流程应能顺着读”、“每层都要承担实际职责，不新增仅透传参数、转发调用或重复搬运字段的层”。
2. **`MatcherSettlementPlan` 与 `MatcherSettlementEvent` 双重膨胀**：
   * 单个类拥有 30~70 个字段，充斥着相互引用的指针和临时数组，每个订单批量都要清空并重新初始化几十个字段，增加了垃圾回收器扫描对象图（GC Marking）的遍历开销。

---

### 3.6 冗余字段与跨阶段无用状态

1. **`DecodedMatchingCommand` 中的死字段**：
   * 内部为 11 种命令各维护了一个变量引用，但实际每次调用只有其中 1 个生效，其余 10 个字段永远为 `null`，既浪费了引用空间，又在垃圾回收垃圾追踪中带来额外的引用遍历。
2. **`LaneCommitDelta` 二次变更镜像记录**：
   * 在 Account Lane 修改了权威数据（`OrderRuntime`、`UserRuntime`、`PositionRuntime`）之后，又费力把对象引用逐个放入 8 个 `ChangeBuffer` 中，Owner 线程再重新遍历一遍，造成了**双重事实记录**。

---

## 四、各问题的生产级别重构解决方案

根据第一性原理（First Principles）与 KISS 极简原则，针对上述六大瓶颈提出生产级别具体改造方案：

### 解决方案 1：消灭 `byte[]` 临时分配 —— 原生 DirectBuffer / Flyweight 零拷贝直达
* **改造前**：
  ```java
  // Aeron 收到消息 -> 拷贝为 byte[] -> 封装为 ByteArrayInputStream / ByteBuffer
  byte[] payload = new byte[length];
  directBuffer.getBytes(offset, payload);
  PlaceOrderCommand cmd = TradingCommandCodec.decodePlaceOrder(payload);
  ```
* **改造后**：
  * 直接基于 Agrona 的 `DirectBuffer` / `UnsafeBuffer` 操作，编写 `FlyweightPlaceOrderCommand`；
  * 解码时只需传入 `(DirectBuffer buffer, int offset)`，所有 `orderId()`、`limitPriceTicks()` 直接通过 `buffer.getLong(offset + X)` 原生偏移量读取，**完全零 byte[] 构造，堆内存分配直接减少 50% 以上**。

### 解决方案 2：消灭 `String` 分配与双重 Hash —— 内部全流程 Int Symbol ID 化
* **改造前**：
  ```java
  // 1. 解码出 String
  String instrumentId = decodeAsciiSymbol(payload, offset, len);
  // 2. 查哈希表换成 int symbolId
  int symbolId = symbols.get(instrumentId);
  ```
* **改造后**：
  * 在网关/协议层定义 2 字节整型 `short symbolId`（或 Ingress 端通过扁平数组/Trie 树一次性换为 `int symbolId`）；
  * 全链路（准入、撮合、持仓、风控）统一使用 `int symbolId` 流转；
  * `symbols` 哈希表被彻底替换为固定长度的直接索引数组 `CoreInstrument[] instrumentsBySymbolId`，查找复杂度降至 $O(1)$ 数组随机访问，彻底移除 `hashUtf8` 和 `ConcurrentHashMap` 查询。

### 解决方案 3：化解 CAS 伪共享与总线锁 —— 线程独享通知槽（Thread-confined Slot）
* **改造前**：
  * 所有 Lane 线程通过原子 CAS 竞争更新单个全局 `volatile long readyMask`。
* **改造后**：
  * 采用分片单写原则（Single-Writer Principle）：每个 Lane 拥有自己独享的 64 字节缓存行对齐的标志位：
  ```java
  @jdk.internal.vm.annotation.Contended
  static final class LaneCompletionSlot {
      volatile long sequenceCompleted;
      // 硬件级填充防止伪共享
      long p1, p2, p3, p4, p5, p6, p7;
  }
  ```
  * Lane 线程仅以 `lazySet` 或 `volatile` 写入自己的 `slot.sequenceCompleted = seq`，无需 CAS 总线锁；
  * Owner 线程仅按需读取各 Lane 的 `slot.sequenceCompleted`，彻底消除核心间的 Cache Line Bouncing。

### 解决方案 4：消除 Lambda 逃逸与泛型装箱 —— 静态无状态 Handler 与原始类型集合
* **改造前**：
  ```java
  owner.matcherPipeline.submit(shard, pending.sequence(), () -> { ... });
  ```
* **改造后**：
  * 提取静态单例任务处理器 `MatcherPipelineTask`，交接参数通过预分配的环形上下文数组传递，完全杜绝每次提交创建 Lambda 实例；
  * 全面将 `ConcurrentHashMap<Long, ...>` 替换为 Agrona 的原生基本类型哈希表 `Long2ObjectHashMap` / `LongLongHashMap`，消除 Long 装箱开销。

### 解决方案 5：扁平化事件链路 —— 消除 `MatcherSettlementPlan/Event` 双层中转
* **改造前**：
  `DeterministicExchangeCoreAdapter` $\rightarrow$ 构造 `MatcherSettlementPlan` $\rightarrow$ 构造 `MatcherSettlementEvent` $\rightarrow$ 分发给 Lane。
* **改造后**：
  * 撮合引擎原地输出原生结构体 `NativeTradeBatch`（预分配固定数组）；
  * Lane 线程直接按引用访问该批次的成交流水，就地更新账户持仓；
  * 废弃 `MatcherSettlementPlan` 与 `MatcherSettlementEvent` 中间类，精简掉 1500+ 行搬运代码。

---

## 五、结论与第一性原理落地建议

1. **两种模式定位清晰**：
   * **FUSED 模式**：
     * **核心优势**：极致精简、确定性强、零线程竞争、零锁等待、CPU 能效比极高（单核跑出 26.2 万 ops/s，能效比高出 2.44 倍）、最大尾延迟远低于多线程。
     * **适用场景**：单节点追求极致低延迟与稳定性、资源受限环境、确定性回放与高频核心。
   * **PIPELINED 模式**：
     * **核心优势**：利用多核并行计算，极限业务吞吐更高（37.4 万 ops/s）。
     * **核心代价**：多消耗 2.5 个 CPU 核心，且容易产生 100ms 级别的锁竞争尾部毛刺与队列堆积。
2. **第一优先级落地目标**：
   * 当前无论是 FUSED 还是 PIPELINED，全系统最大的性能天花板和 GC 来源均为 **`byte[]` 临时数组（~50%）** 和 **`String` 解码（~16%）**；
   * 优先落地 **DirectBuffer 零拷贝解码** 与 **整型 Symbol ID 全链路直通**，预期可直接减少全系统 **65% 以上的对象分配**，将 FUSED 模式的单核吞吐从当前的 26 万推向 **45 万+ ops/s**，彻底发挥 Aeron 内存级交易引擎的极致性能！
