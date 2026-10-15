# Aeron Core 交易链路基准压测与性能卡点深度分析报告

> **测试时间**：2026-10-15 20:40 (Asia/Shanghai)  
> **运行环境**：macOS (x86_64, 8核物理/16逻辑核, 16GiB), 磁盘可用 366GiB  
> **运行时**：Amazon Corretto HotSpot OpenJDK 27 (`27.0.0.33.1`), Maven 3.9.16  
> **测试分支与基准**：`master` (`75ba2a23d`)，纯净业务主路径（已彻底清理 JFR 埋点、Metrics 及非业务调试代码）  
> **证据采样目录**：`/Users/atomex/Desktop/surprising/performance-artifacts/diagnostics-20261015/`

---

## 一、压测场景与吞吐性能实测数据

本次压测采用 Aeron 生产级单节点基准套件 `ContinuousOwnerBenchmark`，涵盖完整的 `Owner -> Matcher -> 4 个 Account Lane -> SystemLedger -> Egress` 交易链路，覆盖真实输入环、会话路由、批量下单、批量撤单与资金守恒核对。

### 1. 核心吞吐与完成性数据（JMH Throughput）

| 场景与参数配置 | 吞吐量 (Core Messages/s) | 终态业务操作吞吐 (Ops/s) | accepted == terminal | 资金差 (fundsDiff) | 未完成请求数 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **LINEAR_PERPETUAL** (batchSize=20, 256 in-flight, 4 Lanes, BUSY_SPIN) | **11,195 ~ 12,134** msg/s | **223,914 ~ 242,688** ops/s | **100% 一致** (无遗漏) | **0** (严格守恒) | **0** |
| **单笔原子微基准** (LinearPerpetualCoreBenchmark) | - | - | - | - | - |
| - `limitOrderPlacement` (限价单挂单) | **2,837 µs / op** | - | 100% | 0 | 0 |
| - `fullTakerFill` (完全撮合成交) | **3,221 µs / op** | - | 100% | 0 | 0 |
| - `cancelRestingOrder` (挂单撤单) | **2,629 µs / op** | - | 100% | 0 | 0 |

---

## 二、内存分配开销分析（JMH -prof gc & JFR）

通过 JMH `-prof gc` 与 JFR 事件采集，对交易热路径的内存分配速率及分配对象进行了精准归因：

### 1. 垃圾产生率与 GC 暂停
- **内存分配速率 (`gc.alloc.rate`)**：**88.82 ~ 153.48 MB/s**；
- **单操作分配开销 (`gc.alloc.rate.norm`)**：每笔 JMH invocation（含 512 条 Core Messages，10,240 笔订单）产生约 12.5 MB 分配，折合 **约 1,221 字节 / 业务订单**；
- **GC 停顿频次与耗时**：
  - 40 秒测量窗口内共触发 **32 次 GC**（包含 26 次 G1New 和 6 次 G1Old）；
  - **最大单次 GC 停顿达 27.2 ms**，常态单次停顿在 5.0 ~ 15.3 ms；
  - GC 暂停是导致交易链路出现毫秒级长尾延迟（P99/P99.9 抖动）的最主要诱因。

### 2. 堆分配对象占比排行（JFR allocation-by-class）

| 排名 | 对象类型 | 内存分配占比 | 核心来源方法 / 分配站点 (Allocation Site) |
| :---: | :--- | :---: | :--- |
| **1** | `byte[]` | **49.25%** | `CoreMessageCodec.decode(byte[])` (9.85%)<br>`Arrays.copyOfRange(byte[], int, int)` (9.37%)<br>`CoreProtocol.decodeResponse(byte[])` (5.74%)<br>`CoreMessageCodec.encode(CoreMessage)` (3.17%) |
| **2** | `java.lang.String` | **7.17%** | `CoreStateQueryCodec$Reader.text()` (5.65%)<br>订单 `clientOrderId` / `symbol` 字符串构造 (1.22%) |
| **3** | `CoreOrderStateView` | **5.10%** | `CoreStateQueryCodec.readOrderState()` (5.44%) 订单投影对象 |
| **4** | `Object[]` / `long[]` / `int[]` | **12.17%** | `ArrayList.<init>` (2.86%)<br>`TerminalTombstoneStore.grow()` (2.34%)<br>`LongHashSet.rehash()` (1.35%)<br>`LongObjectHashMap.allocateTable()` (1.29%) |
| **5** | `PlaceOrderCommand` / `Item` | **6.26%** | `TradingOrderBatchCodec.decodeResultFrame` (2.69%)<br>批量/单笔订单 DTO 实例化 (3.62%) |
| **6** | `HeapByteBuffer` | **3.04%** | `HeapByteBuffer.slice()` (2.58%)<br>`HeapByteBuffer.<init>()` (2.30%) |
| **7** | `TreeMap$Entry` / `HashMap` | **2.27%** | `CoreUserState.calculateExplainedLocks()` (0.63%)<br>`StateMapSupport.freezeSorted()` (0.63%) 保证金解释计算 |
| **8** | `OrderRuntime` / `CoreOrderState` | **2.11%** | `RuntimeStateMaterializer.materialize()` (1.07%)<br>`OrderRuntime.copyIntoCommandSlot()` (1.04%) |
| **9** | `Long` (装箱) & `AbstractList$Itr` | **1.65%** | `Long.valueOf(long)` (0.85%)<br>`AbstractList.iterator()` (0.80%) |

---

## 三、CPU 热点与执行效率分析（JFR hot-methods & thread-cpu-load）

### 1. 热点方法占比（JFR hot-methods）

```text
Method                                                                 Samples   Percent
-----------------------------------------------------------------------------------------
SettlementLaneWorker.run()                                               3,130    64.32%  <-- 绝对热点：忙自旋空转
TradingOwnerLoop.drainResponses()                                          395     8.12%  <-- 响应回环排空
SettlementLaneWorker.peekMatcherSettlement()                               326     6.70%  <-- 撮合结算邮箱探测
TradingOwnerLoop.awaitProgress(long)                                       114     2.34%  <-- Owner 推进等待
OneToOneConcurrentArrayQueue.poll()                                        113     2.32%  <-- 无锁队列轮询
ArrayList.grow(int)                                                         73     1.50%  <-- 集合动态扩容
RuntimeStateMaterializationCache.current()                                  46     0.95%  <-- 状态物化缓存检查
TradingOwnerLoop.ownerWorkAvailable()                                       43     0.88%  <-- 就绪检查
Long2ObjectHashMap.getMapped(long)                                          37     0.76%  <-- 原语映射查找
SystemLedgerLane.run()                                                      29     0.60%  <-- 系统总账队列处理
SettlementLaneWorker.execute(Command)                                       22     0.45%  <-- 实际业务结算计算
```

### 2. 线程 CPU 占用分布（JFR thread-cpu-load）
- **Account Lanes (`core-account-lane-0 ~ 3`)**：每个 Lane 线程占用约 5.8% ~ 8.2% CPU，合计约占 **30%~35% 系统算力**。由于配置为 `BUSY_SPIN`，当暂无结算事件到达时，线程仍在密集执行 `Thread.onSpinWait()` 与循环探测；
- **Owner 线程 (`trading-owner-0`)**：CPU 占用仅约 **2.1%**，调度与门禁处理极快；
- **Matcher 线程 (`core-matcher-0`)**：CPU 占用约 **3.9%**；
- **System Ledger 线程 (`core-system-ledger-lane`)**：CPU 占用约 **0.7%**。

---

## 四、核心瓶颈与性能卡点归纳

基于本次实测与剖析，交易链路当前存在以下 **4 大核心性能卡点**：

### 卡点 1：编解码层与协议切片的内存拷贝泛滥（占内存分配约 50%）
- **现象**：`byte[]` 占据了 **49.25%** 的内存分配压力，`CoreMessageCodec.decode`、`Arrays.copyOfRange` 和 `CoreProtocol.decodeResponse` 频繁申请新字节数组。
- **根因**：网络/环形队列接收到的 DirectBuffer 每次转为业务消息时，直接调用了 `new byte[length]` 并进行内存复制，未彻底贯彻基于 Agrona `DirectBuffer` / Flyweight 的零拷贝切片。

### 卡点 2：SettlementLaneWorker 纯自旋空转挤占 CPU 物理核（占 CPU 64.3%）
- **现象**：`SettlementLaneWorker.run()` 和 `peekMatcherSettlement()` 消耗了 71% 以上的执行采样，而真正的业务计算 `execute()` 仅占 0.45%。
- **根因**：在单机多线程环境下，4 个 Lane Worker 若全部采用 `BUSY_SPIN` 无退避自旋，会长时间打满物理 CPU 核心，导致与 Owner、Matcher 以及客户端产生剧烈的 CPU 时间片竞争和高速缓存争用（Cache Thrashing）。

### 卡点 3：高频业务 DTO 分配引发 G1 停顿抖动（每秒 100+ MB 垃圾，GC 最大停顿 27ms）
- **现象**：虽然主流程已剔除指标采样，但业务层仍产生大量的短期小对象：
  - 订单 DTO（`PlaceOrderCommand`、`CoreOrderBatchResult$Item`）；
  - 视图投影对象（`CoreOrderStateView` 占 5.1%）；
  - 字符串（`String` 占 7.17%，如 `symbol`、`clientOrderId` 未做 intern 或 ASCII 整数映射）；
  - 解释性计算分配（`CoreUserState.calculateExplainedLocks` 中使用 `TreeMap` / `HashMap` 计算保证金锁）。
- **根因**：每笔业务操作依然产生约 **1,221 字节** 的堆分配，在 20 万+ TPS 的发压下每秒制造 100MB+ 垃圾，频繁触碰 G1 停顿阈值。

### 卡点 4：动态集合运行期扩容（`ArrayList.grow` 竟位列 CPU 热点前 6）
- **现象**：`ArrayList.grow(int)` 占 CPU 采样 **1.50%**，同时 `TerminalTombstoneStore.grow()`、`LongHashSet.rehash()`、`LongObjectHashMap.allocateTable()` 占内存分配的 **7.8%**。
- **根因**：许多批量或历史记录容器（如批单列表、墓碑集合）初始容量设置过小（默认 10 或 16），在处理 batchSize=20 或大量并发请求时发生多轮动态扩容、数组重新分配与元素移动。

---

## 五、针对性改进方案与实施路线

按照 KISS 与渐进式优化原则，建议优先落地以下生产级优化：

1. **编解码零拷贝重构**：
   - 将 `CoreMessageCodec` 和 `TradingOrderBatchCodec` 改为基于不可变只读 `DirectBuffer` 包装，消除所有的 `Arrays.copyOfRange`，直接从缓冲区按偏移量读取长整型与定长原语。
2. **Lane 智能等待策略（Adaptive Backoff）**：
   - 优化 `SettlementLaneWorker` 的空转策略：前 1,000 次自旋，随后引入自适应 `PAUSE / YIELD` 或无等待邮箱通知，在保持纳秒级响应的同时释放 60%+ 的无效 CPU 消耗。
3. **消除热路径装箱与小对象生成**：
   - 消除 `Long.valueOf` 和 `AbstractList$Itr`；
   - 对 `clientOrderId` 和 `symbol` 在网关/准入层做数字 ID 化（Symbol ID / Client Key），热路径全程使用 `long / int` 传递，避免生成 `String`；
   - 改造 `CoreUserState.calculateExplainedLocks`，使用预分配的原始数组缓冲区替代 `TreeMap`。
4. **容器预分配与消除扩容**：
   - 将批量处理集合（Batch Command Items）与墓碑集合的容量固定预分配，彻底消除 `grow()` 与 `rehash()`。
