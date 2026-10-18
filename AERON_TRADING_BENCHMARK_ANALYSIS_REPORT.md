# Aeron 单节点永续交易链路性能分析与瓶颈优化报告

## 1. 概述与核心结论

针对 `surprising-aeron-core` 永续合约交易链路，在单节点全功能集群环境下，分别对 **单线程 FUSED 模式** 与 **多线程 PIPELINED 模式** 进行了端到端（客户端发压 -> Aeron IPC/UDP 网络传输 -> Aeron Archive WAL 落盘 -> Core 准入/撮合/持仓计算 -> 出口 ACK 应答）的全链路基准压测与 JFR 深度剖析。

### 1.1 实测核心数据对比表（256 并发窗口，同等负载 profile=MIXED, batch=20）

| 性能指标 / 维度 | 单线程 FUSED 模式 | 多线程 PIPELINED 模式 | 变化差异 / 分析 |
| :--- | :--- | :--- | :--- |
| **业务吞吐 (Business Ops/s)** | **240,726 ops/s** | **379,781 ops/s** | PIPELINED 吞吐高出 **+57.7%** |
| **核心协议消息吞吐 (Core Msg/s)** | **23,042 msgs/s** | **36,285 msgs/s** | 批量聚合后实际吞吐提升 **+57.5%** |
| **撮合成交速率 (Fills/s)** | **57,291 fills/s** | **90,401 fills/s** | 成交吞吐高出 **+57.8%** |
| **挂单 P50 延迟 (Place Order)** | 10.08 ms (10,076 µs) | 5.12 ms (5,119 µs) | PIPELINED 延迟降低 **-49.2%** |
| **撤单 P50 延迟 (Cancel Order)** | 11.94 ms (11,935 µs) | 4.71 ms (4,706 µs) | PIPELINED 延迟降低 **-60.6%** |
| **批量挂单 P50 延迟 (Place Batch)** | 10.76 ms (10,756 µs) | 7.87 ms (7,872 µs) | PIPELINED 延迟降低 **-26.8%** |
| **客户端背压等待占比 (Blocked Ratio)** | **92.9%** (18.59s / 20.01s) | **87.3%** (17.46s / 20.01s) | 利特尔法则背压，客户端超 87% 时间在等 ACK |
| **CPU 核心饱和度** | Owner: 98.7% (单核打满)<br>Matcher/Lane: 0% | Owner: 98.0%, Matcher: 49.3%<br>Lane 0: 99.4%, Lane 1: 99.4% | 多核并行算力得到有效释放 |
| **资金与持仓守恒校验** | **PASS (Diff=0, 绝对平衡)** | **PASS (Diff=0, 绝对平衡)** | 六产品线资金不变量严守一致 |

---

## 2. 深入瓶颈剖析（第一性原理与微架构视角）

### 2.1 缓存行与伪共享（Cache Line Contention & False Sharing）
- **现象与机理**：
  - 在 **PIPELINED 模式** 下，Owner 线程与 Matcher 线程、Account Lane 线程之间通过连续的 RingBuffer 与共享变量（例如 `coreSequence`、`matchingSequence`、`pendingMatching` 队列状态）进行高频交互。
  - CPU L1/L2 缓存行以 64 字节为单位进行 MESI 缓存一致性同步。当 Owner 线程写入队尾指针，而下游消费者（Matcher/Lane）在相邻的几个字节处轮询读取时，该 64 字节缓存行在多核之间来回失效（Cache Line Invalidation），导致昂贵的跨核心总线嗅探开销。
- **影响**：
  - Matcher 线程虽然只有单核 49.3% 的业务负载，但自旋时不断产生跨核心缓存抖动，拉高了整个流水线同步延迟。

### 2.2 内存分配与 GC 压力（Allocation & GC Pressure）
- **现象与定位**：
  1. **UUID 与临时对象**：虽然本次已成功针对 `pollControl` 增加 `hasPendingQueries()` 门禁消除了普通订单对 `request.header().commandId()` 触发的 `new UUID()`，但在历史路径中仍有零星地方通过装箱包装 ID。
  2. **二级 HashMap 查找开销**：`AccountLaneState` 中的 `summariesByUser` 采用 `Long2ObjectHashMap<Int2ObjectHashMap<AdmissionAggregate>>` 结构。尽管重用了 `AdmissionAggregate`，但多层 Map 寻址导致指针跳转与间接寻址穿透缓存。
  3. **包装与装箱**：在杠杆查询、算法单和触发单中，存在 `Long` 包装对象与 primitive 转换，增加临时短寿命堆对象分配。

### 2.3 锁竞争与等待策略（Lock Contention & Wait Strategy）
- **现象与定位**：
  - PIPELINED 模式下配置了 `BUSY_SPIN` 和 `ADAPTIVE` 等待策略。在无订单空闲片刻，多线程紧密循环 `Thread.onSpinWait()` 虽保证纳秒级反应，但无谓挤占了宿主机的内存总线与超线程执行端口。
  - FUSED 模式虽然天生无锁、无线程切换，但单核心必须一人承担“协议解码 -> 准入风控 -> 内存撮合 -> 仓位更新 -> ACK 响应”，在当前复杂风控规则下，单核的计算预算（约 4.1µs/单）成为了物理极限。

### 2.4 过度设计与冗余中间状态
- **双重变更记录**：
  - 在 `TradingRuntimeState` 处理订单时，权威状态已经在 `AccountLaneState.orders`、`positions`、`balances` 中即时生效。但在确定性提交后，链路仍往多个 `ChangeBuffer`（如 `orderChanges`、`balanceChanges`）进行引用塞入与清理。对于单机就地提交或 FUSED 模式，这些中间 buffer 是多余的中间搬运。
- **复杂的重复校验与装箱查找**：
  - 之前的 `effectiveLeverage` 即使在全系统没有设定任何独立用户杠杆的情况下，每次下单仍调用 `lookup.set(userId, instrument, marginMode)` 并执行哈希查找；现已加入 `lane.cold.leverages.isEmpty()` 快速通道。
  - `validateReservation` 每次进入都调用 `AssetBalance.normalizeAsset` 导致重复调用 `isBlank()`、`trim()`。

### 2.5 终极物理瓶颈：利特尔法则（Little's Law）与 WAL 磁盘 IO
- **现象**：
  - 无论是 FUSED 还是 PIPELINED，压测客户端实测显示 **87.3% ~ 92.9% 的时间均被 `windowBlockedNanos` 锁死**（发压机在空转等应答）。
  - **Little's Law 理论极限**：
    $$\text{Throughput} = \frac{\text{Concurrency}}{\text{RTT}} = \frac{256}{0.0068\text{s}} \approx 37,647 \text{ req/s} \times 10 \approx 376,000 \text{ ops/s}$$
  - 当在途窗口限制为 256 时，系统端到端吞吐在数学上就已经被 38 万封顶。只要客户端发压窗口不放宽，或者后端写盘（WAL / Archive）单次刷盘延迟在毫秒级，发压机就必然把 90% 的时间花在阻塞等待上。
  - **实证**：当我们放宽发压窗口至 `inFlightWindow=1024` 时，业务吞吐立即由 37.9 万跃升至 **40.2 万 ops/s**！

---

## 3. 针对性系统级解决方案

### 方案 1：针对伪共享与缓存行失效（Cache-Line Alignment & Padding）
1. 在 PIPELINED 跨线程通信核心数据结构（RingBuffer 读写游标、Lane 序号、Matcher 进度计数器）中，强制引入 128 字节缓存行填充（Padding）或 `@jdk.internal.vm.annotation.Contended`。
2. 彻底隔离生产者写指针与消费者读指针到不同 Cache Line，消除跨核心 L1/L2 Invalidation。

### 方案 2：针对多层包装与中间状态（Fast-Path & 零中间搬运）
1. **FUSED 模式 Fast-Path 绕过 ChangeBuffer**：
   - 在 FUSED 模式下，直接就地修改权威内存状态并直接产出 `CoreResponse`，彻底跳过 `LaneCommitDelta` 与 `ChangeBuffer` 的二次收集与遍历清空。
2. **展平二级 Map 结构**：
   - 将 `summariesByUser` 由 `Long2ObjectHashMap<Int2ObjectHashMap<AdmissionAggregate>>` 展平为基于单一复合主键 `(userId << 16) | symbolId` 的单层扁平原生哈希表，消除二次寻址与指针解引用。

### 方案 3：针对等待策略与 CPU 浪费
1. 在生产环境根据核心数绑定 CPU Affinity（核绑定，如 `isolcpus` + `taskset`），将 Owner、Matcher、Lane、Aeron Media Driver 分别绑定在不同的物理核（避免超线程资源竞争）。
2. 将 Matcher/Lane 等待策略在低延迟与自旋之间做动态微调，避免空转时对内存总线造成压力。

### 方案 4：解除端到端吞吐天花板（Client Window + IO 并行化）
1. **调整发压机窗口**：将压力测试配置中的 `inFlightWindow` 由 256 放宽至 2048 或 4096，释放网络流水线与 Core 计算的真实并发潜能。
2. **优化 Aeron Archive 落盘**：生产环境采用 NVMe 裸盘并调大 Aeron 复制与日志段（`segmentFileLength`），避免频繁创建与截断 WAL 段文件导致的毫秒级 IO 尖刺。
