# surprising-aeron-core 交易链路八大缺陷具体解决方案与落地指南

本方案针对 [`SURPRISING_AERON_CORE_DEFICIENCIES_ANALYSIS.md`](file:///Users/atomex/Desktop/surprising/surprising-ex/SURPRISING_AERON_CORE_DEFICIENCIES_ANALYSIS.md) 中揭示的 8 个核心问题，依照项目规范（`AGENTS.md`）中的核心设计约束：**“业务语义与可读性优先”、“主流程应能顺着读”、“每层都要承担实际职责”、“一个状态一个权威来源”、“技术机制留在必要边界”以及“资金安全优先于过度复杂并发”**，制定具体、严谨、生产级别的解决方案。

---

## 目录
1. [方案一：执行模型重构——回归单线程确定性状态机](#方案一执行模型重构回归单线程确定性状态机)
2. [方案二：中间类与包装精简——合并8处缓冲与拆解上帝类](#方案二中间类与包装精简合并8处缓冲与拆解上帝类)
3. [方案三：消除中间状态与转换——拍平订单11重变形记](#方案三消除中间状态与转换拍平订单11重变形记)
4. [方案四：零分配与低GC改造——编解码与协议全Primitive化](#方案四零分配与低gc改造编解码与协议全primitive化)
5. [方案五：缓存行与内存布局优化——扁平连续存储消灭伪共享与Miss](#方案五缓存行与内存布局优化扁平连续存储消灭伪共享与miss)
6. [方案六：全链路零拷贝——滑动环与In-place写入](#方案六全链路零拷贝滑动环与in-place写入)
7. [方案七：并发与线程控制收敛——消灭Handoff与客户端异步化](#方案七并发与线程控制收敛消灭handoff与客户端异步化)
8. [方案八：清除伪需求逻辑——剥离Fingerprint墓碑与热路径排序](#方案八清除伪需求逻辑剥离fingerprint墓碑与热路径排序)
9. [演进落地路线图与验收指标](#演进落地路线图与验收指标)

---

## 方案一：执行模型重构——回归单线程确定性状态机

### 1.1 问题定位与重构目标
* **问题本质**：原系统在 Aeron Cluster 单线程 `onSessionMessage` 之后，强行拆分出 `TradingOwnerLoop` 线程、`MatcherPipelineGroup` 线程池、`SettlementLaneWorker` 线程池，制造了 5 次线程交接、极高的调度排队延迟以及死锁超时风险。
* **重构目标**：回归 Aeron Cluster 官方标准的 **Single-Threaded Shared-Nothing Engine（单线程确定性无共享状态机）**。

### 1.2 具体重构实施
1. **删除多余线程组件**：
   - 彻底删除 `TradingOwnerLoop.java`、`OwnerIngressRing.java`、`ClusterCommandWindow.java`。
   - `AeronTradingClusterService.onSessionMessage` 直接在 Aeron Agent 回调线程中同步调用核心交易执行器。
2. **重构后的确定性主流程设计**：
   ```java
   // 改造后：主流程顺着读，单一入口，串行确定性推进
   @Override
   public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer,
                               int offset, int length, Header header) {
       // 1. 原地解码为入站命令（零堆分配）
       PlaceOrderCommand command = commandDecoder.decodePlaceOrder(buffer, offset, length);
       
       // 2. 幂等检查（基于全局唯一的 commandId 与已提交位置）
       if (resultLedger.isDuplicate(command.commandId())) {
           sendDuplicateResponse(session, command.commandId());
           return;
       }
       
       // 3. 业务前置准入校验与资金冻结（在当前线程直接访问账户模型，无需锁和跨线程等待）
       AdmissionResult admission = balanceEngine.tryFreezeOrderFunds(command);
       if (!admission.isSuccess()) {
           sendRejectionResponse(session, command, admission.resultCode());
           return;
       }
       
       // 4. 原地直接撮合（调用底层 exchange-core，耗时百纳秒级，直接在当前线程返回成交事件）
       MatchingResult result = matcherEngine.matchOrder(command);
       
       // 5. 确定性结算与持仓更新（当前线程直接更新 Maker/Taker 余额与持仓）
       settlementEngine.applyMatching(result);
       
       // 6. 响应直接发回客户端（Zero-Copy Egress）
       egressWriter.sendSuccess(session, command, result);
   }
   ```
* **收益对比**：
  - 端到端延迟由 **15~80 微秒骤降至 1~3 微秒**；
  - 彻底消灭了跨线程通信队列、CAS 争用以及超时死锁判定逻辑。

---

## 方案二：中间类与包装精简——合并8处缓冲与拆解上帝类

### 2.1 8 处重叠缓冲区精简方案
将原有的 8 重存储收敛为 **2 处权威组件**：
1. **输入缓冲**：直接复用 Aeron Cluster 原生的 `DirectBuffer`（LogBuffer 原生帧），无需额外的 `OwnerIngressRing` 和 `ClusterCommandWindow`。
2. **幂等账本**：简化为紧凑的 Primitive 环形去重表 `SimpleResultLedger`：
   ```java
   public final class SimpleResultLedger {
       private final long[] commandIds;      // 环形数组存储最近处理的 commandId
       private final short[] resultCodes;    // 对应的执行结果码
       private final int mask;
       
       public SimpleResultLedger(int capacity) {
           this.mask = capacity - 1;
           this.commandIds = new long[capacity];
           this.resultCodes = new short[capacity];
       }
       
       public boolean isDuplicate(long commandId) {
           int slot = (int) (commandId & mask);
           return commandIds[slot] == commandId;
       }
       
       public void record(long commandId, short resultCode) {
           int slot = (int) (commandId & mask);
           commandIds[slot] = commandId;
           resultCodes[slot] = resultCode;
       }
   }
   ```
3. **彻底删除的无用容器**：
   - 彻底删除 `CommandSlotRing`、`PendingMatchingRing`、`ResponseArena`、`TerminalTombstoneStore`。

### 2.2 拆解 `CommandSlot`（1355 行代码）与反模式包装
1. **废除 `CommandSlot`**：
   - 废除所有预包装内部上下文（`PlaceSlotContext`、`TriggerSlotContext`、`LiquidationSlotContext`、`DeferredSlotContext`、`ReplaceSlotContext` 等）。
   - 所有的上下文参数直接在 Java 方法调用栈帧（Call Stack Frame）中通过局部变量或紧凑的 Record 传递，随方法返回自然销毁，无需在堆上常驻复杂槽位。
2. **重构 `DecodedMatchingCommand` 为强类型密封类（Sealed Interface）**：
   ```java
   // 废除 Object value + Class.cast 运行期强转，恢复编译期静态类型检查
   public sealed interface TradingCommand 
       permits PlaceOrderCommand, CancelOrderCommand, AmendOrderCommand, TransferFundsCommand {
       long commandId();
       long userId();
       String instrumentId();
   }
   ```

---

## 方案三：消除中间状态与转换——拍平订单11重变形记

### 3.1 订单生命周期模型收敛至 3 态
将原来的 11 种转换形态彻底收敛为 3 种权威表现形式：
```
1. 协议入站态 (Flyweight DirectBuffer / PlaceOrderCommand)
       │  (直接执行业务校验并进入撮合器)
       ▼
2. 权威运行态 (OrderRuntime)
       │  (常驻内存核心，包含所有价格、数量、已成交、费用、状态字段)
       ▼
3. 对外导出态 (CoreOrderView)
       │  (仅在生成快照或发送 WebSocket/Kafka 时按需流式投影)
```
* **彻底删除的冗余类**：
  删除 `DecodedMatchingCommand`、`ResolvedPlaceOrder`、`CoreMatchingOrder`、`ResolvedMatchingAdmission`、`CommandSlot`。
* **消除字段搬运**：
  `OrderRuntime` 统一作为交易核心内部唯一的权威事实载体，从撮合到结算直接操作同一对象引用，不再来回从一个对象拷贝到另一个对象。

### 3.2 消除在途窗口阻塞（`Conflict.ACCOUNT`）
* **原理**：由于回归单线程状态机，每一笔订单都会在当前方法中**立即可见地完成资金冻结与持仓变更**。
* **动作**：彻底删除 `ClusterCommandWindow.conflictingPrefixSize()` 和 `orderSlots` / `userSlots` 索引，后续订单到达时读取到的永远是已落地的权威状态，无需任何在途 Fence 和挂起等待。

---

## 方案四：零分配与低GC改造——编解码与协议全Primitive化

### 4.1 零分配编解码改造（Zero-Allocation Codec）
* **改造措施**：
  彻底废除 `TradingCommandCodec` 中的所有 `ByteBuffer.allocate(...)` 和临时 `new byte[]`。
  所有编解码器直接接收 Agrona 的 `MutableDirectBuffer` 和 `DirectBuffer`，实现原地序列化：
  ```java
  public final class TradingCommandCodec {
      // 原地直接编码，返回写入长度，零堆内存分配
      public static int encodePlaceOrder(MutableDirectBuffer buffer, int offset, PlaceOrderCommand cmd) {
          buffer.putInt(offset, PLACE_ORDER_VERSION);
          buffer.putLong(offset + 4, cmd.orderId());
          buffer.putLong(offset + 12, cmd.userId());
          buffer.putLong(offset + 20, cmd.symbolCode()); // 紧凑型 8 字节符号编码
          buffer.putByte(offset + 28, cmd.sideWireCode());
          buffer.putLong(offset + 29, cmd.priceTicks());
          buffer.putLong(offset + 37, cmd.quantitySteps());
          buffer.putByte(offset + 45, (byte) (cmd.postOnly() ? 1 : 0));
          return 46;
      }
      
      // 原地直接解码，基于 Flyweight 零堆对象分配
      public static void decodePlaceOrder(DirectBuffer buffer, int offset, PlaceOrderCommand target) {
          target.wrap(
              buffer.getLong(offset + 4),
              buffer.getLong(offset + 12),
              buffer.getLong(offset + 20),
              buffer.getByte(offset + 28),
              buffer.getLong(offset + 29),
              buffer.getLong(offset + 37),
              buffer.getByte(offset + 45) == 1
          );
      }
  }
  ```

### 4.2 基础协议全 Primitive 化
* **重构 `AmendOrderCommand`**：
  ```java
  // 废除 Long, Boolean 包装类型，全部采用基本类型与 Sentinel 值
  public record AmendOrderCommand(
          long originalOrderId,
          long replacementOrderId,
          long priceTicks,          // -1L 表示不修改
          long quantitySteps,       // -1L 表示不修改
          byte timeInForceWireCode, // -1 表示不修改
          byte postOnlyFlag) {      // -1 表示不修改, 0 表示 false, 1 表示 true
      
      public boolean hasPriceChange() { return priceTicks > 0; }
      public boolean hasQuantityChange() { return quantitySteps > 0; }
  }
  ```

### 4.3 快照序列化流式改造（彻底废除 `RuntimeStateMaterializer`）
* **现状痛点**：原 `RuntimeStateMaterializer` 在快照时创建几十万个 `TreeMap` 和红黑树节点。
* **重构方案**：直接从底层 Agrona Primitive 哈希表中以流式方式二进制序列化到快照 `ExclusivePublication`：
  ```java
  public void writeSnapshot(ExclusivePublication publication, MutableDirectBuffer scratch) {
      // 直接遍历活跃订单表，直接流式二进制写入，无需构建任何中间 Map 或对象
      runtimeOrders.forEachKeyValue((orderId, order) -> {
          int length = order.writeToBuffer(scratch, 0);
          while (publication.offer(scratch, 0, length) < 0) {
              idleStrategy.idle();
          }
      });
  }
  ```

---

## 方案五：缓存行与内存布局优化——扁平连续存储消灭伪共享与Miss

### 5.1 彻底解决伪共享
* **重构方案**：
  单线程化后，消除原 `SettlementLaneWorker` 中手写的 5 层继承类 `p00..p07`。
  对于必要的跨线程边界（如网络出口发布器），采用官方规范的 `@jdk.internal.vm.annotation.Contended` 并在启动参数加入 `-XX:-RestrictContended`，确保由 JVM 权威布局 128 字节物理隔离。

### 5.2 扁平连续数据结构重构（提升 L1/L2 Cache 命中率）
1. **拍平账户余额两层 Map**：
   - 废除 `LongObjectHashMap<IntObjectHashMap<BalanceRuntime>>`。
   - 采用单一复合 Key：`long balanceKey = (userId << 32) | (assetId & 0xFFFFFFFFL);`
   - 使用单层 Agrona 高性能 `Long2ObjectHashMap<AssetBalance>`，单次哈希寻址直接定位。
2. **侵入式链表关联用户订单（消灭每人一个 `LongHashSet`）**：
   - 在 `OrderRuntime` 结构体内直接内嵌 `prevUserOrderId` 与 `nextUserOrderId`：
     ```java
     public final class OrderRuntime {
         private long orderId;
         private long userId;
         // 侵入式链表指针，消灭独立的 LongHashSet activeOrderIdsByUser
         private long prevUserOrderId;
         private long nextUserOrderId;
         ...
     }
     ```
   - 消除系统中散落的数万个独立小哈希表，内存连续性得到保证，大幅减少 Cache Miss。

---

## 方案六：全链路零拷贝——滑动环与In-place写入

### 1. 移除指纹计算内存搬运
直接废除 `CommandFingerprint` 中的 `this.header` 和 `scratch` 数组的两次 `System.arraycopy`。

### 2. 出口背压滑动环重构（废除 `DeferredSessionResponses` 的 `new byte[]`）
* **重构方案**：
  预分配单一块固定大小的堆外/直接内存环形缓冲区 `DirectSlidingBuffer`（如 8MB）。
  遇到背压时，直接将数据追加写入环中并记录 offset 与 length；发送成功时移动头指针，彻底消除背压时的堆内存分配与垃圾回收。

---

## 方案七：并发与线程控制收敛——消灭Handoff与客户端异步化

### 7.1 彻底废除“所有权移交协议（Ownership Handoff Protocol）”
* **重构措施**：
  - 彻底删除 `TradingRuntimeState.tryAcquireOwnerLaneAccess()`、`requestHandoff()`、`resumeHandoff()`。
  - 单线程模型下，Aeron 回调线程独占全部数据读写权；无论是快照、批量划转还是风控，直接在当前线程内顺序执行，**彻底消除全局 Stop-The-World 自旋停顿风险**。

### 7.2 客户端改造为真正非阻塞事件驱动模型
* **重构措施**：
  - 废除 `SurprisingAeronClient` 中的全局 `ReentrantLock` 和 `while(true)` 阻塞轮询。
  - 客户端通过单生产者队列（SPSC Ring）提交命令，并返回非阻塞的 `CompletableFuture<CoreResponse>`：
  ```java
  public final class SurprisingAeronClient {
      private final Long2ObjectHashMap<CompletableFuture<CoreResponse>> pendingRequests = new Long2ObjectHashMap<>();
      
      // 非阻塞异步发送
      public CompletableFuture<CoreResponse> sendAsync(CoreMessage message) {
          CompletableFuture<CoreResponse> future = new CompletableFuture<>();
          long correlationId = message.header().correlationId();
          pendingRequests.put(correlationId, future);
          cluster.offer(message.buffer(), message.offset(), message.length());
          return future;
      }
      
      // 由后台 Poller 线程驱动 Egress 回调，触发完成
      public void onEgressMessage(long correlationId, DirectBuffer buffer, int offset, int length) {
          CompletableFuture<CoreResponse> future = pendingRequests.remove(correlationId);
          if (future != null) {
              future.complete(decodeResponse(buffer, offset, length));
          }
      }
  }
  ```

---

## 方案八：清除伪需求逻辑——剥离Fingerprint墓碑与热路径排序

### 8.1 彻底移除 `CommandFingerprint` 实时哈希计算
* **措施**：
  依靠 Aeron 日志自带的全序特性与客户端生成的 128 位 `commandId`，直接使用 `SimpleResultLedger` 进行去重，彻底删除 `CommandFingerprint.java`。

### 8.2 彻底移除 `TerminalTombstoneStore` 墓碑存储
* **措施**：
  强一致复制状态机天然具备单调全序性，彻底删除 `TerminalTombstoneStore.java` 及其 8192 容量的双重哈希桶。终态订单直接在活跃订单索引中剔除。

### 8.3 彻底移除 Lane 内部全量 Key 排序的 Rolling Hash
* **措施**：
  彻底删除 `AccountLaneState.java` 中的 `rebuildLocalHashes()`、`mixMap()` 以及内部的 `Arrays.sort`。业务热路径严禁执行全量排序与规范化哈希计算。

---

## 演进落地路线图与验收指标

| 阶段 | 核心任务 | 消除的类与组件 | 预期核心收益 |
| :--- | :--- | :--- | :--- |
| **第一阶段（减法优先）** | 剥离无用伪需求与防御逻辑 | `CommandFingerprint`, `TerminalTombstoneStore`, `rebuildLocalHashes` | 消除热路径 MurmurHash3 与 `Arrays.sort`，吞吐提升 30% |
| **第二阶段（扁平收敛）** | 拍平订单形态、Primitive 化与零分配编解码 | `CommandSlot`, `DecodedMatchingCommand`, `ResponseArena`, 包装类装箱 | 订单热路径达到 **Zero-Allocation（零GC堆分配）** |
| **第三阶段（架构归一）** | 回归单线程确定性模型，删除伪流水线与 Handoff | `TradingOwnerLoop`, `OwnerIngressRing`, `ClusterCommandWindow`, `SettlementLaneWorker` | 彻底消灭全局 STW 停顿，P99 延迟降低 80% 以上 |

---
*方案实施指南已同步保存至项目根目录：`SURPRISING_AERON_CORE_REFACTORING_SOLUTIONS.md`*
