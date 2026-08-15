# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每条 `ProductLine` 使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷，目标是一条产品线三个 Member。当前实现和后续改造的唯一规格是
[`docs/high-performance-trading-core-implementation.md`](../docs/high-performance-trading-core-implementation.md)。

当前状态不是“P2 已完成”：P1–P5 均有增量实现但仍有明确残留，P6 尚未完成。`TradingCoreRuntime` 已成为
Core 单写边界，exchange-core 0.5.8-emporia 是唯一可执行盘口，触发/风险/导出已经有 Core 路径；但
immutable compatibility shell、native matcher snapshot restore、协议级 epoch registry、完整四线
恢复/容量门禁仍在实施中。任何局部 smoke 或 micro benchmark 都不能替代最终资金和恢复验收。

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

三节点可以通过 `compose.yaml` 启动；canonical `scripts/` 测试脚本正在按主规格重新整理，旧业务逻辑脚本
不得直接复用。一次只启动一条产品线；删除 Archive 或数据卷前必须先确认目标产品线并使用显式的 Docker
volume 操作。脚本职责和验收命令以主规格第 18.3 节为准。

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
