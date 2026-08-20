# exchange-core 交易主链路性能与恢复审计报告

> 状态：`AS_IS_REVIEW_BASELINE`
>
> 审计分支：`codex/aeron-unified-core`
>
> 审计日期：2026-08-20
>
> 目标：单产品线稳定完成 100,000 条最终裁决交易命令/秒；JVM 长稳、故障可恢复；非成交热路径平均 `O(1)`；禁止与全局状态规模成比例的复制、扫描和分配。

## 1. 结论

当前架构方向正确，但现状还不具备 100,000 条最终裁决命令/秒的容量基础。exchange-core 撮合器不是首要
瓶颈，主要瓶颈位于 Product Core 外层状态迁移：每条命令仍可能执行全状态投影、完整物化和 parity 比较；
永续成交、资金费、风险和强平的部分 Runtime processor 也仍从完整 immutable state 重建 Runtime。

当前最优先的改造不是增加 Matching Engine 数量或单独调整 GC，而是让 `TradingRuntimeState` 成为在线唯一
可变状态，由 Product Core owner thread 对本次命令触及的实体执行原地、可验证的增量提交。完整
`TradingCoreState` 只应在快照、恢复、离线对账或抽样一致性检查中生成。

本报告是当前源码和本地诊断基准的现状审计。既有实施规格中的“功能已接入”“完整 parity 已完成”不等于
生产性能门禁已经通过；凡涉及 100k/s、无全量复制和热路径复杂度的结论，以本报告列出的源码出口和正式
容量门禁为准。

## 2. exchange-core 可借鉴的设计

项目固定使用 `exchange.core2:exchange-core:0.5.15-emporia`，fork commit 为
`627ddf68fbb0594b07e4b59a1a0e3377354e26b9`。其核心流水线为：

```text
API / RingBuffer
    -> Grouping / optional Journal
    -> Risk R1
    -> Matching Engine（按 symbol 分片）
    -> Risk R2
    -> Results Handler
```

固定版本源码：

- [ExchangeCore 流水线](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/ExchangeCore.java)
- [Direct OrderBook](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/orderbook/OrderBookDirectImpl.java)
- [PerformanceConfiguration](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/src/main/java/exchange/core2/core/common/config/PerformanceConfiguration.java)
- [官方基准说明](https://github.com/lilaizhencn/exchange-core/blob/627ddf68fbb0594b07e4b59a1a0e3377354e26b9/README.md)

值得保留和借鉴的机制：

- Disruptor 预分配 `OrderCommand` ring，避免传统线程池、锁竞争和无界任务队列。
- 撮合与风险状态由固定 owner thread 修改，不让多个线程并发写同一本订单簿。
- symbol 固定映射到 Matching Engine shard，订单簿不会在处理过程中跨线程迁移。
- Direct OrderBook 使用订单 ID 索引、价格桶和桶内双向链表；按订单查找、取消和 FIFO 摘除不扫描整本订单簿。
- 订单、价格桶和 matcher event 可通过池化降低稳定负载下的 GC 压力。
- 核心命令通过固定流水线推进，而不是为每笔交易创建任意异步任务。
- snapshot、serialization 和 journal 都与确定性状态边界对齐。

exchange-core README 中的峰值不能直接换算为 Surprising-EX 端到端容量。其基准主要覆盖内存撮合和 Risk
阶段，不包含 Aeron Cluster、业务账户、资金预留、持仓、replicated outbox、Kafka、网络、快照和故障恢复。

还必须接受两个复杂度边界：

- 增加 Matching Engine 数量只能提高多 symbol 总吞吐，不能并行处理同一热门 symbol 的价格时间优先队列。
- 一笔 taker 订单命中 `k` 个 maker 时，处理下界为 `O(k)`，不能宣称严格 `O(1)`。

## 3. 当前架构中应保留的边界

当前分支已经具备正确的可靠性骨架：

- 每个 `ProductLine` 使用独立 Product Core，六条产品线的账户、订单、topic、instrument、风险和快照保持隔离。
- exchange-core 是唯一可执行订单簿，Product Core 不维护第二本价格时间优先 book。
- Aeron Cluster Log/Archive 是命令权威；exchange-core command journal 被关闭，避免双权威日志。
- Product Core 在 matcher 前裁决业务校验与资金预留，matcher 结果回到 owner thread 后再完成资金、持仓和风险提交。
- Audit Exporter、Kafka 和 History Projector 位于在线裁决链路之外，PostgreSQL 不参与当前态交易决策。
- 业务快照和 exchange-core 原生 `ME/RE` 快照配对恢复；manifest、hash 或 open-order set 不一致时 fail closed。
- matcher continuation 异步返回，Aeron owner thread 不直接阻塞等待撮合 future。

以上边界不应为了吞吐量被推倒。性能改造应集中在 Product Core 内部状态表示、continuation 调度、协议复制和
outbox 编码上。

## 4. P0：全状态投影和物化阻塞 100k/s

### 4.1 每次状态迁移仍完整 materialize

[`CoreProbeState.adoptState`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreProbeState.java)
当前执行：

1. `RuntimeStateDeltaApplier.apply` 将候选 immutable state 的差分应用到 Runtime。
2. `RuntimeStateParityChecker.assertMatches` 对 Runtime 执行完整物化。
3. 将完整 materialized state 与候选 state 做 equals 和 business hash 比较。
4. 将 materialized state 继续作为兼容读取和后续 transition 的权威视图。

[`RuntimeStateMaterializer`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateMaterializer.java)
会重建全部用户、余额、订单、reservation、position、risk、liquidation、treasury、client-order index、algo、
timer 和 trigger map。更严重的是，它在每个用户循环内再次扫描全局 reservation 和 position 集合，因此最坏
复杂度可能接近：

```text
O(U * R + U * P + O + Risk + Treasury)
```

其中 `U` 为用户数，`R` 为 reservation 数，`P` 为 position 数，`O` 为订单数。这不仅是 CPU 扫描问题，
还会为 `TreeMap`、immutable state 和各层业务对象产生大量短命分配。

[`RuntimeStateParityChecker`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeStateParityChecker.java)
适合作为迁移期 fail-closed 门禁，但不能继续存在于 100k/s 的每命令生产热路径。

### 4.2 永续 processor 仍从完整状态重建 Runtime

以下处理器仍存在 `RuntimeStateProjector.project(before, identities)`：

- `RuntimePerpetualMatchProcessor`
- `RuntimePerpetualFundingProcessor`
- `RuntimePerpetualRiskProcessor`
- `RuntimePerpetualLiquidationProcessor`

例如 [`RuntimePerpetualMatchProcessor`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimePerpetualMatchProcessor.java)
在处理单个成交批次前投影完整状态，随后还遍历 `expected.users()` 对齐用户 revision，最后再进入完整 parity
materialize。资金费、风险和强平也存在相同类别的全量投影出口。

这说明当前 Runtime processor 的主要价值仍是迁移期结果对照，尚未成为 exchange-core 式的持久 owner-thread
原地状态机。

### 4.3 本地诊断基准

本轮使用当前分支执行了短基准，仅用于定位瓶颈，不作为生产容量证明：

| 测试 | 结果 |
| --- | ---: |
| exchange-core adapter，20,000 个 IOC 无成交订单 | 134,127 completion/s |
| 完整 Core 内存链路，200 组下单+撤单 | 51.3 组/s |
| 跳过真实 matcher submit 后的完整 Core | 54.8 组/s |

完整 Core 的 prepare/apply 平均时间会随状态增长，从约 `2.6/1.8 ms` 增长到约 `6.4/5.6 ms`；同一轮中
exchange-core 阶段约为 `0.2 ms`。跳过 matcher 只提升约 7%，说明当前主要耗时位于业务状态包装层。

限制：该基准状态规模小、无真实 fill、无三节点 Aeron、无 Kafka、不是开放环 JMH，并使用本地开发机 JVM；
因此只能用于定位，不能用于宣称已经达到或接近 100k/s。

## 5. 其他高优先级问题

### 5.1 pending matching 调度随在途请求放大

[`SurprisingClusteredService`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/SurprisingClusteredService.java)
当前为每个 pending matching 设置 timer；每完成一条 continuation，又遍历全部 pending sequence 并重新调用
`scheduleTimer`。同时：

- `matchingSequence(commandId)` 通过 stream 扫描全部 pending command，平均工作量为 `O(P)`。
- 单次完成会产生 `O(P)` timer 调度调用。
- 一批 `P` 个请求完成时，累计调度调用可能接近 `O(P^2)`。
- `scheduleTimer` 失败时在 Aeron owner thread 上 busy idle。

目标实现应改为：matcher callback 写有界 MPSC completion queue；owner thread 使用一个 drain wakeup，每次最多
处理固定数量；sequence 到 pending 使用数组/ring 或 primitive map；commandId 到 sequence 使用直接索引，并
设置明确的 max in-flight 和入口背压。

### 5.2 协议入口和出口重复复制

`SurprisingClusteredService.onSessionMessage` 先把 `DirectBuffer` 复制到新 `byte[]`；
[`CoreMessageCodec.decode`](../surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreMessageCodec.java)
随后又为 payload 创建第二个 `byte[]`。响应编码也会创建新的完整数组。

目标实现：

- 增加 `DirectBuffer + offset + length` flyweight decoder。
- 只解析当前命令需要的字段。
- pending 生命周期必须持有数据时，只复制一次到紧凑 command struct。
- 响应直接编码到复用 Agrona buffer。
- 禁止把 Aeron callback 的临时 slice 保留到 callback 生命周期之外。

### 5.3 replicated outbox 存在对象、编码和 ACK 放大

[`CoreExportState`](../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/CoreExportState.java)
为每个事实创建 event、payload 和 `CoreMessage`；ACK 时重新解码 event 以查找终态订单。batch 查询和 Aeron
响应还会再次组合编码。

建议保留 replicated outbox 的可靠性边界，但改为：

- entry 保存预编码 frame 和 terminal metadata。
- batch 查询只做一次连续 buffer copy。
- ACK 按 sequence 和 side metadata 清理，不重新 decode event。
- 以 pending bytes 为主要容量门禁。
- 审查 matching pending 和 completion 是否都必须暴露为 Kafka Core Fact；若外部只需要最终事实，内部
  continuation 可只保留在 Aeron Log 中。

### 5.4 快照可能产生长 owner-thread 暂停

当前 snapshot 需要 pending matching 清空，并同步生成 business snapshot 和 matcher snapshot。持续高流量下，
如果没有 admission fence，很难自然进入稳定的零 pending 窗口。

建议流程：

```text
关闭新入口
    -> drain 已提交 continuation
    -> 固定 snapshot epoch
    -> 按确定性顺序分段编码 Runtime State
    -> 配对 matcher snapshot
    -> 恢复入口
```

snapshot 和 recovery 可以是 `O(N)` 冷路径，但必须限制 owner-thread 暂停时间；避免先生成完整 immutable state，
再生成一份完整 snapshot byte array。还需按目标用户数和订单数验证当前 snapshot 大小上限与 Archive retention。

### 5.5 JVM 与依赖稳定性风险

[`surprising-aeron-core/compose.yaml`](../surprising-aeron-core/compose.yaml) 默认使用 `-Xms512m -Xmx512m`。
该容量需要同时容纳 matcher、Runtime State、replicated outbox、snapshot、当前 materialize 瞬时副本和 GC
安全余量，不能作为生产默认容量。

构建 shaded jar 时还观察到：

- Aeron 与 exchange-core 传递的 Agrona 类重叠。
- LZ4 1.10.1 与 1.8.0 类重叠。
- JDK 25 对 Unsafe、native access 和 Chronicle internal access 给出警告。
- Docker 只配置 `jdk.internal.misc` 的 `--add-opens`，还未显式覆盖全部 native/export 要求。

必须统一 Aeron、Agrona、LZ4 版本并增加 duplicate-class 构建门禁；固定 Temurin/HotSpot 25 发行版和参数；
根据真实 live set 决定 heap，使用真实负载比较 ZGC 与 G1。保留 `Xms=Xmx` 和 pre-touch，同时为 direct
memory、Archive page cache 和操作系统留足物理内存。

## 6. 目标热链路

```text
Aeron owner thread
    -> flyweight decode
    -> O(1) idempotency/source-sequence lookup
    -> 产品线独立 handler
    -> 校验本次涉及的账户、订单、余额、持仓和 reservation
    -> Runtime State 原地提交
    -> matcher ring submit
    -> bounded completion queue
    -> 按 global sequence 完成资金/持仓裁决
    -> append compact Core Fact
    -> direct buffer response
```

实施约束：

- Runtime State 只允许 Product Core owner thread 写。
- 命令使用 validate-before-commit；复杂资金变更使用只记录 touched entities 的 compact undo/change set。
- 正常命令不生成完整 `TradingCoreState`。
- parity checker 只运行在 debug、抽样 replay、快照边界或 follower shadow check。
- 热路径只校验 touched 用户、余额、订单、reservation、position 和 treasury delta。
- 资金守恒使用增量断言，不扫描全部账户。
- 每个用户维护直接的 reservation、position 和 active-order 索引，禁止按用户扫描全局集合。
- 六产品线共享 Runtime 基础设施，但账户、保证金、资金费、交割、期权行权继续由独立 handler 裁决。
- 如果修改 exchange-core fork，优先增加 sequence/callback 或 batch publish API，减少每命令
  `CompletableFuture`、lambda 和结果对象分配；不得改变价格时间优先语义。

## 7. 复杂度门禁

| 操作 | 目标复杂度 |
| --- | --- |
| 非成交限价单校验、冻结和索引插入 | 平均 `O(1)` |
| 按 orderId/clientOrderId 查询与撤单 | 平均 `O(1)` |
| 最优价访问 | `O(1)` |
| 单次成交 | `O(k)`，`k` 为 maker/fill 数 |
| 市价/FOK 扫多个价格档 | `O(k + levels)` |
| 用户余额、持仓、冻结读取 | 平均 `O(1)` |
| 资金费、风险扫描、ADL、交割、行权 | `O(batch)` continuation |
| snapshot/recovery | `O(N)`，仅允许出现在冷路径 |
| 热路径分配 | 不随全局状态规模增长，持续压到近零 |

任何单用户下单、撤单或单笔成交都不得产生与全局用户数、订单数、持仓数成比例的扫描、排序、状态复制或
对象分配。

## 8. 分阶段改造路线

### P0：消除全状态工作

- 让 Runtime State 成为在线唯一状态。
- 移除每命令 projector、materializer 和完整 parity。
- 将永续成交、资金费、风险和强平改为持久 Runtime 原地 continuation。
- 为 reservation、position 和 pending matching 建立直接索引。

### P1：控制 continuation 与分配

- completion queue、单一 drain wakeup、max in-flight 和入口背压。
- DirectBuffer flyweight 解码和复用响应 buffer。
- 移除 matcher/result 热路径中的 stream、`toList`、`List.copyOf` 和无界 future 分配。
- outbox 预编码及 ACK metadata 化。
- 统一依赖版本和 JDK 25 模块参数。

### P2：快照与故障恢复

- snapshot admission barrier 和分段 Runtime snapshot。
- 三节点 leader kill、snapshot corruption、Archive 重放测试。
- Kafka 不可用、outbox 达上限、Archive 磁盘满和 follower 落后测试。

### P3：容量与分片

优先把单 Product Core owner thread 做到目标。Matching Engine 可以按 symbol 扩展，但 Product Core 不能简单
按 symbol 拆分：同一用户的跨 symbol 余额、全仓保证金和风险属于同一个 risk domain。真正按账户/风险域分片
会引入订单簿和结算跨 shard 协调，只能在单核热链路完成 P0-P2 后重新评审。

## 9. 100k/s 验收口径

本报告把“100k/s”定义为：单个产品线每秒完成 100,000 条已经被 Aeron Cluster 提交并完成最终业务裁决的
交易命令，不是 HTTP 收包数、matcher ring publish 数或短时峰值。

正式容量门禁至少包括：

- 开放环 100k/s 持续 60 分钟，不能用闭环客户端隐藏排队。
- 2 倍突发持续 10 秒，验证有界背压而不是 OOM。
- 24 小时 soak，heap occupancy、direct memory、pending 和 outbox 不持续增长。
- 至少 100 万用户和 400 万活动订单的固定状态规模。
- 热门 symbol 和热门用户倾斜。
- 无成交、1 maker、10 maker、100 maker 扫单分别测试。
- 下单、撤单、改单、成交、标记价、资金费、强平、ADL、交割和行权按产品线分别覆盖。
- p50/p99/p99.9 使用 acceptance-to-finalization 延迟，并修正 coordinated omission。
- 无 Full GC、无不可接受的 safepoint、无状态 hash 分歧。
- 满负载 kill leader 后，无已提交命令丢失、无重复最终事实。
- Kafka/Projector 停止期间交易按设计继续；outbox 达上限后确定性背压。
- 用户和做市账户逐项核对期初、充值/调整、成交、手续费、资金费、强平费、交割/行权和期末资金守恒。

## 10. 本轮验证范围

已执行：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests package
```

构建通过，并执行 exchange-core adapter、完整 Core 和 skip-matcher 对照短基准。工作区在审计前保持干净。

本轮未执行三节点 Aeron 容量、Kafka/PostgreSQL 历史链路、leader failover、快照恢复、磁盘故障、六产品线
资金守恒和长时间 JVM soak。原因是当前全状态投影/物化已经构成明确 P0 阻塞；在消除该阻塞前扩大压测不能
证明目标架构成立。

### 10.1 分阶段证据

| 阶段 | 结果 | parent commit | UTC | 固定负载 | 最终裁决吞吐中位数 | 修正后 p50 / p99 / p99.9 | 证据 |
| --- | --- | --- | --- | --- | ---: | ---: | --- |
| Stage 1 | **PASS（基准工具门禁）** | `9ec69899a8096d3e2c1b74e33ea393d26b1853c3` | `2026-08-20T04:42:46Z` | seeds `9901..9903`，每 fork：adapter 500、accept/freeze 25、完整内存 25、并发入口 50、永续最终裁决 50 | `218.359/s` | `5,292 / 18,857 / 20,856 us` | `.omo/evidence/task-1/task-1-baseline-result.json`；三个 `task-1-baseline-fork-*.jfr`；五个 `jfr-*.txt` |

Stage 1 的 PASS 仅表示可重复执行的度量/JFR 契约通过，不是生产容量认证。该短样本明显低于 100k/s，且
`p99=18.857 ms` 未达到后续容量门禁的 `10 ms` 默认预算；后续阶段不得把本行解释为性能 SLO 已通过。

运行环境为 macOS 26.7（Darwin 25.6.0）、Intel i9-9880H、16 logical CPU、16 GiB RAM；运行时未置于
容器，数据卷剩余约 98 GiB。`/usr/libexec/java_home -v 25` 指向不产出所需 JFR 的 OpenJ9，因此 runner
明确选择本机 JFR-capable Oracle GraalVM Java 25.0.1 HotSpot，并在 JSON 记录实际路径和 build。固定参数为
`-Xms256m -Xmx256m -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED`
以及 `jdk.internal.misc` 的 `--add-opens/--add-exports`，JFR settings 为 `profile`。

精确功能测试为：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-tools -am \
  -Dtest=ClusterCapacityMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

RED 在缺少 `CapacityMetrics` 时以 AssertJ assertion 失败；GREEN 验证 offered/accepted/finalized 分离、
outbox sequence gauge，以及 `recordValueWithExpectedInterval` 对 `100 ms` observation、`10 ms` expected
interval 生成 10 个修正样本。基准命令为：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
scripts/run-exchange-core-hot-path-stage.sh --stage task-1-baseline \
  --attempt-dir .omo/evidence/task-1 --benchmark-suite baseline --forks 3 --jfr-settings profile
```

三个 fork 的最终裁决速率为 `220.118 / 178.798 / 218.359 per second`。并发入口观察到的
`pendingMatching` 最大值和 ingress queue 最大值均为 `50`；当前短本地基准没有暴露 replicated outbox
占用，JSON 使用 `null`，不得解释为零。JFR 每 fork 观察到两次 Young GC 和一次 `Old Garbage Collection`，
GC 后 heap 为 `18.8-19.0 MiB`，最长列出的 pause 为 `5.81 ms`；没有 `Full GC` 标签。该 JFR view 未提供
可用的 direct-memory 数值，safepoint duration 显示 `Indefinite`，两项保留为未测，不能据此通过长稳门禁。

CPU top frames 为 `ProcessingSequenceBarrier.checkAlert`、`WaitSpinningHelper.tryWaitFor`、
`ProcessingSequenceBarrier.getCursor` 和 `Util.getMinimumSequence`。allocation top frames 为
`ObjectsPool$ArrayStack.<init>`、`Arrays.copyOf(byte[], int)`、`RollingBusinessStateHash.stable` 和
`Unsafe.allocateUninitializedArray`；`RuntimeStateMaterializer.materialize` 仍出现在 allocation view，符合
本报告的 P0 诊断。Stage 1 没有暴露可签名 state hash，也没有执行逐项资金守恒查询，因此 JSON 明确记录
`stateHash=not-exposed-by-local-baseline`、`fundsDelta=null`、`bookEmpty=not-queried`。

与 4.3 的旧短基准不做数值增益宣称：旧值是 200 组下单+撤单的 raw closed-loop `51.3 groups/s`，本行是
25 个永续成交 cycle、50 条最终裁决命令的修正后短样本，分子和负载不同。Stage 1 的 rollback boundary 是
单独回退 `perf(core): establish JFR hot-path baseline`；它未修改 Product Core 资金、订单或撮合语义。

### 10.2 Decision register

| 决策 | Stage 1 结论 | 证据 / 后续归属 |
| --- | --- | --- |
| HdrHistogram vs in-repo histogram | 采用 HdrHistogram `2.2.2` | `ClusterCapacityMetricsTest` 直接验证 coordinated-omission 修正计数和 percentiles。 |
| simple validate-before-commit vs multi-entity change set | simple 使用 validate-before-commit；多实体使用 touched-entity compact change set | Stage 1 不改交易状态；Task 2 用相同 runner 验证。 |
| primitive map vs dense ring | 默认 primitive map | matching sequence 允许 gap，未取得可证明的密度约束；Task 5 负责实测。 |
| pooled owned command vs compact owned copy | 默认一次 compact owned copy | Aeron callback 生命周期外必须拥有数据；Task 6 负责 allocation 对照。 |
| segmented snapshot writer API | Aeron-publication chunk writer | Stage 1 只冻结 API 方向；Task 10 验证 pause、checksum 和恢复。 |
| G1 vs ZGC | 两者保留 | 本轮仅记录 HotSpot 默认 collector；Task 9 在相同负载/JDK/heap 下执行矩阵，不在 Stage 1 提前选择。 |

### 10.3 not yet run

- 三节点 Aeron committed-command 开放环负载、leader kill、follower lag 和 Archive replay。
- 60 分钟 100k/s、10 秒 200k/s burst、24 小时 soak，以及四个连续 15 分钟增长窗口。
- 100 万用户、400 万活动订单、热门 symbol/user、0/1/10/100 maker fill-depth。
- 六产品线资金、持仓、手续费、资金费、强平费、保险基金、ADL、交割、行权和到期逐项守恒。
- Kafka/Projector outage、outbox 上限、Archive 磁盘满、snapshot corruption 和 fail-closed restore。
- direct memory、有效 safepoint duration、replicated outbox maxima、签名 state/funds hash 和 G1/ZGC 对照。

剩余风险：Stage 1 负载很短且状态很小，fork 间最终裁决吞吐离散约 19%；JFR 本身和 JVM 启动占比较高；
accept/freeze 与并发入口基准刻意不完成 matching，只作为分阶段 control，不可纳入最终裁决分子。
