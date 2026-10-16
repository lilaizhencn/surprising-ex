# D11 彻底解决报告：`surprising-aeron-client` 与 `surprising-aeron-protocol` 结构清理与编解码零冗余分配重构

> **完成日期**：2026-10-16  
> **关联审计项**：[`docs/trading-path-code-audit-20261015.md`](file:///Users/atomex/Desktop/surprising/surprising-ex/docs/trading-path-code-audit-20261015.md) — **D11（client / protocol）**  
> **分支**：`master`  
> **构建与测试环境**：HotSpot JDK 27（Corretto-27.0.0.33.1）

---

## 一、 问题背景与前期调研结论

审计文档 D11 指出了 `surprising-aeron-client` 与 `surprising-aeron-protocol` 模块的两个核心方向：
1. **Core 入口与客户端/查询编解码的对称性差异**：Core 入口已使用 flyweight 零分配解码，但客户端 Egress 响应接收路径、网关 `AeronClientPool` 请求发送路径以及 `surprising-aeron-protocol` 中的状态查询/结果/控制命令 Codec 仍存在多处冗余 `byte[]` 拷贝、临时包装对象分配、逐字段 `ByteBuffer.allocate(4/8)` + `ByteArrayOutputStream` 扩容、未指定初始容量的 `new ArrayList<>()` 以及 `Enum.values()` 反复克隆数组问题。
2. **`AeronClientPool` 内部 `Session` / `SessionFactory` 边界核实**：经代码调用链核查，`Session` 与 `SessionFactory` 为包级（package-private）SPI，存在两个职责明确的实现：生产实现 `SurprisingAeronClient::beginConnect`（对接真实 Aeron Cluster MediaDriver 会话）与测试故障注入实现 `AeronClientPoolTest.ScriptedSession`（用于模拟背压、断连、重连与异步超时且无需启动真实集群），符合 `AGENTS.md` 隔离外部传输依赖的边界约束，予以保留。

---

## 二、 核心改动明细

### 1. 客户端 `SurprisingAeronClient` 零中间拷贝解码与死代码清理
- **文件**：[`SurprisingAeronClient.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/SurprisingAeronClient.java)
- **消除 Egress 响应接收的 2 次多余 `byte[]` 拷贝与临时包装对象**：
  - 原先 `onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset, int length, Header header)` 先分配 `new byte[length]` 拷贝整帧报文，再调用 `CoreMessageCodec.decode(bytes)` 再次分配 `new byte[payloadLength]`、`new UUID(...)`、`traceId String`、`CoreMessageHeader` 与 `CoreMessage`，最后才提取 `CoreResponse`；
  - 重构后在 `onMessage` 中直接基于 `DirectBuffer` 偏移量原地完成魔数（`MAGIC`）、版本（`VERSION`）、消息类型（`RESPONSE`）、结果码（`CoreResultCode`）、`traceId` 长度及字符合法性（`TraceIds.isValidChar`）校验，仅对业务响应体 `data` 执行唯一一次必要的 `byte[]` 切片读取（`payloadLength == 0` 时直接复用 `CoreResponse.EMPTY_DATA`），直通构造 `CoreResponse.decoded(...)`。
- **清理死代码构造器与冗余字段**：
  - 删除生产与测试均 0 调用的阻塞连接构造器 `private SurprisingAeronClient(AeronCluster.Context, IdleStrategy, long, EgressPoller)` 及 5 参数静态工厂 `connect(Context, ProductLine, IdleStrategy, long, EgressPoller)`；
  - 删除恒为 `AeronCluster::pollEgress` 的 `egressPoller` 成员字段，在 `pollEgress()` 中直接调用 `aeronCluster.pollEgress()`。

### 2. 客户端连接池 `AeronClientPool` 发送热路径收敛
- **文件**：[`AeronClientPool.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java)
  [`CoreMessageHeader.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageHeader.java)
- **`CoreMessageHeader` 一步构造**：
  - 在 `CoreMessageHeader` 新增接收 `String traceId` 的 10 参数 `command(...)` / `query(...)` 静态工厂重载；
  - `AeronClientPool.encodeHeader` 直接一步构造 `CoreMessageHeader`，消除原先先构造默认 `traceId = commandId.toString()` 再立即通过 `.withTraceId(...)` 丢弃并二次分配 `CoreMessageHeader` 的浪费。
- **消除重复读取 `MDC.get("traceId")`**：
  - `Request.reset(...)` 仅在入参 `traceId` 为空白时调用一次 `currentTraceId()`，避免调用方已传入 `traceId` 时仍无条件查询 SLF4J `MDC`。
- **收敛 `claimedCorrelations` 并发集合开销**：
  - 普通请求（`submitCommand` / `submitQuery`）的 `correlationId` 由 `nextCorrelation.incrementAndGet()` 单调唯一生成，不再向 `ConcurrentHashMap.KeySetView<Long, Boolean> claimedCorrelations` 执行装箱 `add` / `remove`；
  - 新增布尔标识 `preparedCorrelation`，仅在显式调用 `prepareCommand` / `submitPrepared` 时使用 `claimedCorrelations` 防止外部重复提交同一预备请求。

### 3. 协议层 `surprising-aeron-protocol` 全量 Codec 零冗余分配重构
涵盖 16 个协议核心类，在不改变任何二进制线协议（Wire Format）的前提下彻底消除冗余内存分配：
1. **[`TraceIds.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TraceIds.java) & [`CoreMessageCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java) & [`CoreResponse.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResponse.java)**：
   - 提取 `TraceIds.isValidChar(int c)` 供字符串与 `DirectBuffer` 字节级校验共用；
   - `CoreMessageCodec.decode` 直接使用 `new String(encoded, offset, traceIdLength, UTF_8)` 解码 `traceId`，消除中间 `byte[]` 分配；
   - `CoreResponse.EMPTY_DATA` 与 `CoreResponse.decoded(...)` 开放给客户端直接复用。
2. **[`CoreCommandResultCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreCommandResultCodec.java)**：
   - `decode` 直接调用 `CoreStateQueryCodec.decodeOpenOrdersList(encoded, buffer.position(), ordersLength)` 原地解码挂单列表，消除 `new byte[ordersLength]` 二次数组拷贝与 `CoreOpenOrdersView` 临时包装对象。
3. **[`CoreStateQueryCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreStateQueryCodec.java)**：
   - 新增包级 `decodeOpenOrdersList(byte[] payload, int offset, int length)`；
   - 在 `decodeTreasuryState`、`decodeUserState`、`decodeOpenOrdersList` 中根据线上读取的 `count`（结合剩余字节上界保护）预分配 `new ArrayList<>(...)` 初始容量，消除反序列化大列表时的多次扩容拷贝；
   - 为 `encodeUserQuery`、`encodeOpenOrdersQuery`、`encodeOrderQuery`、`encodePositionsQuery`、`encodeRiskQuery`、`encodeFeePolicyQuery`、`encodeFundingPreviewQuery`、`encodeInstrumentSettlementQuery`、`encodeTransferHistoryQuery`、`encodeLedgerEntriesQuery`、`encodeOrderExecutionQuery`、`encodePendingTransfersQuery` 全部预算精确字节容量；
   - `Writer.text` / `Writer.optionalText` 改用 UTF-8 字节计数与直接编码写入，消除每次写字符串时的 `String.getBytes(UTF_8)` 临时 `byte[]` 分配；
   - 将 `Reader.version(int... supported)` 可变参数方法替换为单参数 `void version(int expected)` 并删除未使用的 `requireVersion()`，消除每次解码时的 `int[]` 可变参数数组分配。
4. **[`CoreAlgoOrderCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreAlgoOrderCodec.java) & [`CoreCancelAllAfterCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreCancelAllAfterCodec.java)**：
   - 彻底移除逐字段 `ByteBuffer.allocate(4)` / `ByteBuffer.allocate(8)` 配合 `ByteArrayOutputStream` 的编码写法，改为按精确字节长度一次性分配单个 `ByteBuffer` 顺序写入；
   - `Reader.text()` 直接使用 `new String(bytes, offset, length, UTF_8)`，消除中间 `byte[]` 分配。
5. **[`CoreTriggerOrderCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreTriggerOrderCodec.java)**：
   - 缓存 `TYPES`、`CONDITIONS`、`STATUSES`、`PRICE_SOURCES` 四个静态枚举数组，消除每次解码时调用 `Enum.values()` 产生的数组克隆；
   - `decodeList` 直接在原 `ByteBuffer` 上连续读取全部 `CoreTriggerOrderView` 元素并按 `count` 预分配 `ArrayList` 容量，消除逐元素 `Arrays.copyOfRange` 切片拷贝；
   - `encode` / `encodeList` 改用精确容量的单个 `ByteBuffer`，消除 `ByteArrayOutputStream`。
6. **[`TradingCommandCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java) & [`CorePendingTransferCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CorePendingTransferCodec.java)**：
   - 在 `TradingCommandCodec` 新增包级 `decodeTransferFunds(byte[] payload, int offset, int length, long committedUserId)` 并缓存 `LIQUIDATION_RESOLUTIONS` 枚举数组，移除未使用的私有 `putShort/putInt/putLong` 方法；
   - `CorePendingTransferCodec.decode` 直接基于原数组偏移调用 `TradingCommandCodec.decodeTransferFunds(...)`，消除每条待划转记录的 `new byte[commandLength]` 切片拷贝，并改用精确容量 `ByteBuffer` 编码。
7. **其余查询与控制 Codec 全面收敛**：
   - [`CoreRiskQueryCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreRiskQueryCodec.java)、[`CoreOpenInterestCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreOpenInterestCodec.java)、[`CoreAdlQueryCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreAdlQueryCodec.java)、[`CoreOrderPreflightCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreOrderPreflightCodec.java)、[`CoreRiskScanControlCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreRiskScanControlCodec.java)、[`CoreMaintenanceCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMaintenanceCodec.java)、[`CoreLiquidationWorkCodec.java`](file:///Users/atomex/Desktop/surprising/surprising-ex/surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreLiquidationWorkCodec.java)：
   - 全部消除编码时对同一字符串调用两次 `getBytes(UTF_8)`、解码时先 `new byte[length]` 再 `new String(...)` 的冗余拷贝、以及 `CoreLiquidationWorkCodec` 中的 `ByteArrayOutputStream` 与 `Enum.values()` 重复克隆。

---

## 三、 测试验证与范围说明

### 1. 已执行测试范围（HotSpot JDK 27，合计 399 项测试用例 100% 通过）
- **`surprising-aeron-protocol` 全量单元测试**（29 个测试类，**253 项用例**，0 失败 / 0 错误 / 0 跳过）：
  - 覆盖全部消息头与响应编解码（`CoreMessageCodecTest`、`CoreResponseTest`、`CoreResultCodeTest`、`CoreTracePropagationTest`）、命令与批量订单编解码（`TradingCommandCodecTest`、`TradingOrderBatchCodecTest`、`CoreCommandResultCodecTest`）、状态与业务查询编解码（`CoreStateQueryCodecTest`、`CoreTriggerOrderCodecTest`、`CoreAlgoOrderCodecTest`、`CoreCancelAllAfterCodecTest`、`CoreRiskQueryCodecTest`、`CoreOpenInterestCodecTest`、`CoreOrderPreflightCodecTest`、`CoreLiquidationWorkCodecTest`、`CoreLiquidationBatchCodecTest`、`CoreMaintenanceCodecTest`、`CoreRiskScanControlCodecTest` 等）。
- **`surprising-aeron-client` 全量单元测试**（12 个测试类，**66 项用例**，0 失败 / 0 错误 / 0 跳过）：
  - 覆盖 `SurprisingAeronClientConcurrencyTest`（4 项）、`AeronClientAgentTest`（23 项）、`AeronClientPoolTest`（8 项）、`SurprisingAeronClientMediaDriverTest`（3 项）、`SurprisingAeronClientThreadingModeTest`（4 项）、`AeronRealtimeTransportTest`、`RealtimeOutboxTest`（4 项）等全部客户端并发、超时、重连与协议边界场景。
- **`surprising-aeron-service` 下游核心交易与快照/查询回归测试**（**80 项用例**，0 失败 / 0 错误 / 0 跳过）：
  - `TradingCoreOwnerTest`（45 项）：覆盖六产品线交易全链路、状态查询、主被动平仓与资金守恒；
  - `CoreTransferLaneLifecycleTest`（6 项）：覆盖资金划转与待划转查询编解码；
  - `TriggerOrderIndexTest`（5 项）& `CoreAlgoLaneQueryTest`（4 项）：覆盖触发单与算法单索引及查询；
  - `TradingStateSnapshotCodecTest`（6 项）、`PublishedSnapshotRecoveryTest`（8 项）、`CoreNativeSnapshotProductLineTest`（6 项）：覆盖六产品线快照持久化与恢复一致性。

### 2. 未测范围与边界说明
- 按 `AGENTS.md` 测试边界原则，本次改动集中在 `surprising-aeron-protocol` 与 `surprising-aeron-client` 的内存编解码实现及客户端构造清理，未改动二进制线协议格式、Kafka Topic 或外部 wallet 服务，因此未启动独立外部 wallet 进程或全集群压测。
