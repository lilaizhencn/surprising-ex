# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每条 `ProductLine` 使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷，目标是一条产品线三个 Member。当前实现和后续改造的唯一规格是
[`docs/high-performance-trading-core-implementation.md`](../docs/high-performance-trading-core-implementation.md)。

当前 P2/P3 已按 O(delta) persistent state、exchange-core 唯一 executable book 和 Core snapshot/受控
matcher rebuild 通过阶段出口；P4 生产触发路径已由 Core-only 门禁收敛，P5 已具备批量导出/投影故障语义，
P6 已完成六条产品线恢复和 20 秒容量证据，真实 provider/Kafka/PG 故障与长时容量仍在门禁中。
`TradingCoreRuntime` 已成为 Core 单写边界，exchange-core 0.5.8-emporia 是唯一可执行盘口，触发/风险/导出
已经有 Core 路径；native matcher persistence 不作为当前版本生产依赖，局部 smoke 或 micro benchmark
不能替代生产容量结论。

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
- exchange-core 0.5.8-emporia 是唯一可执行 book；`GTX` 使用原生 post-only 语义，外层不得查 book 后模拟。
- 下单、撮合、资金、风险、强平和生命周期热路径不访问 JDBC、Redis、Kafka 或 HTTP；这些系统只做输入桥、
  异步导出、投影、审计和查询。
- 幂等结果窗口有界；窗口外仍用 `(source, sourceId)` 的序列高水位阻止旧命令再次执行。
- 同步调用超时表示结果未知。调用方必须复用同一 `commandId` 查询或重试，不能生成新 ID。
- `SurprisingAeronClient` 串行提交消息；Gateway 后续通过固定数量的单线程 client agent 扩展吞吐。

完整架构、阶段台账、问题追踪、风险参数所有权、脚本矩阵和验收门禁均在 `docs/` 主规格中维护；任何
代码阶段完成后必须同步更新该文档的状态和证据。
