# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每条 `ProductLine` 使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷，生产目标是一条产品线三个 Member。

当前 P2 已实现 User/Order、余额调整、下单预占、撤单释放、强一致查询和可恢复 Snapshot。Exchange Core、
Book、成交结算、Risk、Liquidation 和 Export State 按 P3–P5 接入；P2 smoke 不能替代后续全链路资金验收。

## 模块

| 模块 | 职责 |
| --- | --- |
| `surprising-aeron-protocol` | schema v1 固定小端二进制 envelope、六线 wire code 和端口布局。 |
| `surprising-aeron-service` | `ClusteredService`、有界幂等状态、Snapshot 和节点启动器。 |
| `surprising-aeron-client` | Leader 自动发现、切换处理和“超时即结果未知”同步客户端。 |
| `surprising-aeron-exporter` | P5 可靠 Exporter 的最小 sink 边界。 |
| `surprising-aeron-tools` | Cluster 探针、状态 hash 查询和离线 replay 骨架。 |

## 本地三节点

使用 JDK 25 构建：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="${JAVA_HOME}/bin:${PATH}"
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh build
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh up
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh wait-ready
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh probe
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh hash
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh funds-smoke
# 重启后用同一 FUNDS_SMOKE_SEED 只读验证已恢复资金
PRODUCT_LINE=SPOT FUNDS_SMOKE_SEED=1 scripts/aeron-core-local.sh funds-verify
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh down
```

一次只启动一条产品线。`down` 默认保留三个命名卷，不会删除 Archive；需要删除数据时必须先确认目标
产品线且使用显式 Docker volume 操作，脚本不提供自动清库命令。

## 协议约束

- Cluster Log 权威消息禁止 Java serialization 和无版本 JSON。
- v1 header 固定包含 `commandId`、`productLine`、`source`、`sourceId`、`sourceSequence`、`userId`、
  外部提交时间和 `correlationId`。
- 幂等结果窗口有界；窗口外仍用 `(source, sourceId)` 的序列高水位阻止旧命令再次执行。
- 同步调用超时表示结果未知。调用方必须复用同一 `commandId` 查询或重试，不能生成新 ID。
- `SurprisingAeronClient` 串行提交消息；Gateway 后续通过固定数量的单线程 client agent 扩展吞吐。

完整架构和阶段门禁见
[Aeron 统一交易核心迁移实施方案](../docs/aeron-unified-trading-core-migration-plan.md)。
