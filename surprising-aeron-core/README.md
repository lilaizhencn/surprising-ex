# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每个 `ProductLine` 变体使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷；一个逻辑 Core 固定由三个 Member 组成，并管理该变体全部 symbol。当前实现和后续改造的唯一规格是
[`docs/high-performance-trading-core-implementation.md`](../docs/high-performance-trading-core-implementation.md)。

当前 P2 已按 O(delta) persistent state 通过阶段出口，P3 仍为 `IN_PROGRESS`：`TradingCoreRuntime` 已成为
Core 单写边界，撮合命令只进入 exchange-core 0.5.8-emporia，但 `TradingCoreState` 仍保存
`CoreBookState` priority map，matcher 恢复仍会 `cleanStart` 后逐单回放。下一阻断项是把 fork 的
`ISerializationProcessor`/`ApiPersistState` 接入 Aeron 配对 snapshot，改为 snapshot-only restore，随后删除
`CoreBookState` 和生产 rebuild 路径。现有 Core-only 恢复 smoke 证明过渡路径可恢复，不等于单一盘口 P3 已完成。

部署基线不按 margin mode 或热点 symbol 分 Core：CROSS 和 ISOLATED 都由同一个 ProductLine Core 裁决；
CROSS 只共享该 Core 内权益，ISOLATED 绑定 position identity。当前仅保留 `coreShardId=default` 和
`routeVersion=1` 的协议语义；字段按主规格 W1/W3 版本化落地，完成前不接受非默认路由，也不启用热点分片
或跨 Core 全仓余额共享。

## 模块

| 模块 | 职责 |
| --- | --- |
| `surprising-aeron-protocol` | schema v1 固定小端二进制 envelope、六线 wire code 和端口布局。 |
| `surprising-aeron-service` | `ClusteredService`、有界幂等状态、Snapshot 和节点启动器。 |
| `surprising-aeron-client` | Leader 自动发现、切换处理和“超时即结果未知”同步客户端。 |
| `surprising-aeron-exporter` | P5 可靠 Exporter 的最小 sink 边界。 |
| `surprising-aeron-tools` | Cluster 探针、状态 hash 查询和离线 replay 骨架。 |

## 本地构建与三节点运行

使用 JDK 25 构建：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="${JAVA_HOME}/bin:${PATH}"
mvn -pl :surprising-aeron-client,:surprising-aeron-tools -am test
```

三节点可以通过 `compose.yaml` 启动；仓库根目录的 canonical `scripts/` 已按主规格提供 Core-only 启停、
探针、export、资金对账、恢复和容量入口。一次只启动
一条产品线；删除 Archive 或数据卷前必须先确认目标产品线并使用显式的 Docker volume 操作。脚本职责和验收
命令以主规格第 18.3 节为准；这些入口不会伪装成已经运行的 HTTP provider、做市进程或 Kafka 集群。

## 协议约束

- Cluster Log 权威消息禁止 Java serialization 和无版本 JSON。
- v1 header 固定包含 `commandId`、`productLine`、`source`、`sourceId`、`sourceSequence`、`userId`、
  外部提交时间和 `correlationId`。
- Instrument Provider 通过版本化 `UpsertInstrumentCommand` 下发保证金率、risk brackets、最大杠杆和
  最大持仓名义价值；CoreInstrumentState 是运行时唯一参数副本，Risk Provider 只能查询 Core 快照。
- 目标状态由 exchange-core 0.5.8-emporia 独占价格树/FIFO；`GTX` 使用原生 post-only 语义，外层不得查 book 后模拟。当前 `CoreBookState` 是待 W2 删除的过渡性第二份优先级状态，不能作为撮合或恢复的长期权威。
- adapter 固定使用 `RiskProcessingMode.MATCHING_ONLY` 并禁用 exchange-core margin trading；内部 user/symbol/risk module 是需随 matcher snapshot 恢复的技术状态，不是业务资金、持仓或保证金权威。
- Core owner 线程只提交 exchange-core 异步命令；撮合结果通过 Cluster timer continuation 按序回到 owner，普通下单、撤单、改单、强平、结算和标记价触发子单均不在 owner 线程等待 ring future。adapter 不再提供同步交易入口；恢复和离线工具也通过显式异步 continuation drain 完成。
- 强平和交割/行权结算的订单撤销均按确定性 cursor 分批执行，单个 Core 命令最多处理 1,024 笔订单；强平 provider
  通过一个 `EXECUTE_LIQUIDATION_BATCH` 同时提交有序 action 和可选 Risk Scan continuation，订单阶段完成后才推进用户阶段。
  每个批次共享最多 `1024` 笔撤单预算，Core 以 `nextCursorOrderId` 保存独占下一页位置。
  生命周期进度和 matcher attempt 元数据写入 Core snapshot，异常、超时或重启后最多 rebuild/resubmit 一次，第二次失败返回
  `MATCHING_CONTINUATION_FAILED`；生命周期期间同 symbol 的普通订单被拒绝，其他 symbol 仍可提交。
- exchange-core 的异步提交按 symbol lane 串行，同一 symbol 保序、不同 symbol 重叠，snapshot/hash/settlement 等全局操作使用 barrier；
  当前物理 matcher 保持 `matchingEnginesNum(1)`，lane dispatcher 使用 adapter 自有线程，不依赖公共 ForkJoinPool。

生命周期批量协议使用 `EXECUTE_LIQUIDATION_BATCH` wire code 43。`CoreLiquidationWorkCodec` 返回 action 的
`ORDERED` cursor 和精确 Risk Scan token；`CoreLiquidationBatchResultCodec` 返回 offered/applied/pending/obsolete/
processed 计数。`LIFECYCLE_IN_PROGRESS` 明确拒绝重叠生命周期，`MATCHING_CONTINUATION_FAILED` 表示 matcher
超时、异常或一次 rebuild/resubmit 后仍无法完成。旧的单 action 强平和 `CONTINUE_RISK_SCAN` 命令仅保留给直接工具，
正常 provider 周期不调用它们。

| 生产者 / Core 组合 | 结果 |
| --- | --- |
| 新 Work v2 + 新 Core | 支持批量 action、共享 1,024 撤单预算和 cursor 恢复 |
| 新 Work v2 / batch producer + 旧 Core | 版本校验失败并拒绝，不按旧 payload 猜测解码 |
| 旧单 action producer + 新 Core | 仅在直接工具路径使用旧协议；provider 正常维护周期不走该路径 |
| 旧单 action producer + batch-only provider | 不支持，必须升级 provider 后再启用维护周期 |
- 下单、撮合、资金、风险、强平和生命周期热路径不访问 JDBC、Redis、Kafka 或 HTTP；这些系统只做输入桥、
  异步导出、投影、审计和查询。
- 幂等结果窗口有界；窗口外仍用 `(source, sourceId)` 的序列高水位阻止旧命令再次执行。
- 同步调用超时表示结果未知。调用方必须复用同一 `commandId` 查询或重试，不能生成新 ID。
- `SurprisingAeronClient` 串行提交消息；Gateway 后续通过固定数量的单线程 client agent 扩展吞吐。

完整架构、阶段台账、问题追踪、风险参数所有权、脚本矩阵和验收门禁均在 `docs/` 主规格中维护；任何
代码阶段完成后必须同步更新该文档的状态和证据。
