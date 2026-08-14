# Aeron 六产品线统一运行手册

本文是 P6 之后唯一有效的交易核心运行手册。六条产品线使用同一套启动、恢复、导出和告警流程，
只允许在产品特有的资金模型和生命周期步骤上存在差异。旧 WAL、Redis Risk、Kafka 强平候选和
PostgreSQL 资金事务不得作为回退链路。

## 1. 产品线矩阵

| Product Line | Topic 段 | 结算资产 | 必验业务 |
| --- | --- | --- | --- |
| `SPOT` | `spot` | base + quote | 双资产预占、成交交换、撤单释放 |
| `LINEAR_PERPETUAL` | `linear-perp` | USDT | 资金费、Risk、强平、Insurance Treasury、ADL |
| `INVERSE_PERPETUAL` | `inverse-perp` | base asset | 反向盈亏、资金费、Risk、强平、Insurance Treasury、ADL |
| `LINEAR_DELIVERY` | `linear-delivery` | USDT | 到期结算、双边盈亏、持仓归零 |
| `INVERSE_DELIVERY` | `inverse-delivery` | base asset | 反向交割、双边盈亏、持仓归零 |
| `OPTION` | `option` | quote asset | 权利金、卖方保证金、行权/失效、持仓归零 |

每条产品线拥有独立三 Member Cluster、数据目录、端口、Cluster ID、Exporter client ID 和 Kafka
Topic。禁止跨产品线复用 Aeron 数据目录或状态快照。

## 2. 本地启动与检查

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export PRODUCT_LINE=LINEAR_PERPETUAL

./scripts/aeron-core-local.sh build
./scripts/aeron-core-local.sh up
./scripts/aeron-core-local.sh wait-ready
./scripts/aeron-core-local.sh hash
```

生产部署必须在启动外围服务前确认：三 Member 可见、唯一 Leader、Archive 正在录制、磁盘水位正常，
且当前产品线没有连接其他产品线的目录或端口。

## 3. Kafka Topic 契约

```bash
PRODUCT_LINES=LINEAR_PERPETUAL \
  INCLUDE_SHARED_TOPICS=true INCLUDE_LEGACY_PERP_TOPICS=false \
  REPLICATION_FACTOR=3 SINGLE_NODE=false \
  BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
  ./scripts/create-topics.sh
```

六条线都必须创建 `surprising.<topic-segment>.core.events.v1`，并固定为 **1 个分区**。该 Topic 保存
单调 Core Export Sequence；禁止扩分区、压缩或由多个非幂等 Producer 写入。其余行情、通知和查询
Topic 可以按 `scripts/create-topics.sh` 的产品线规则分区。

产品特有 Topic：

- 两条永续线：`funding.rate.v1`。
- 两条交割线：`delivery.settlements.v1`。
- 期权线：`option.exercises.v1`。
- 现货不得产生 funding、delivery 或 option 生命周期事件。

## 4. P7 功能与恢复门禁

```bash
./scripts/run-six-product-line-gates.sh
```

脚本逐线启动全新三 Member Cluster，不并行运行六条线。每条线必须完成：

1. 产品特有的真实撮合和结算流程。
2. User、Book、Risk、Treasury 强查询与 `fundsDiff=0`。
3. Exporter sink 失败时 cursor 不推进，恢复后 backlog drain 到零。
4. 停止实际 Leader，重新选主后状态与资金不变。
5. 创建 Snapshot，三节点全停后保卷冷启动并再次核对。
6. Core Event 经真实 Kafka 发布并在 PostgreSQL 幂等投影为唯一记录。

可使用 `PRODUCT_LINES=OPTION RESUME=true GATE_OUTPUT_DIR=<path>` 只续跑失败的产品线。每条产品线
使用固定 seed，续跑不得改变命令身份。门禁报告中的 `funds-diff.txt` 必须为 `0`，SHA256 必须通过。

## 5. 故障处理

- **Leader 不可用**：停止新写入放量，等待客户端发现新 Leader；不得切换旧 WAL 或数据库写路径。
- **Snapshot/Replay 失败**：隔离该产品线，保留 Archive、Snapshot 和错误日志，不删除数据卷重试。
- **Exporter/Kafka/PG 失败**：核心继续以有界 backlog 运行；恢复 sink 后从 Aeron Export cursor 重放。
- **状态 hash 不一致**：立即停止该产品线写入并保留三个 Member 现场，禁止以某个 PG 投影覆盖 Core。
- **资金差异非零**：最高级事故；停止下单、结算和强平入口，保留命令序列与所有投影，不手工改余额。
- **Risk/强平外围失败**：重新查询 Aeron `Liquidation Work` 并续跑；不得读取 Redis/PG 猜测候选。

## 6. 六线统一仪表盘

所有面板必须带 `product_line`、`cluster_id`、`member_id` 标签，并用相同面板模板复制到六条线：

| 面板 | 必看信号 |
| --- | --- |
| Cluster | role、leadership term、commit/append position、Member position gap |
| Aeron | errors、back pressure、NAK、retransmit、Archive recording position |
| Commands | accepted/rejected、result unknown、p50/p95/p99、幂等命中 |
| State | Snapshot 时长/大小、Replay 进度、三 Member state hash |
| Export | pending、ack position、Kafka lag、PG projection lag |
| Funds | 最近核对时间、funds diff、重复扣款、重复结算 |
| Derivatives | mark age、Risk scan backlog、Liquidation 状态、Treasury |
| Lifecycle | funding/settlement/exercise sequence 与失败数 |

现货隐藏 Derivatives/Lifecycle 中不适用的面板，但不得改变 Cluster、Commands、State、Export 和 Funds
面板的名称与告警口径。

## 7. 统一告警等级

| 等级 | 条件 | 动作 |
| --- | --- | --- |
| P0 | `funds_diff != 0`、state hash 不一致、Archive 停止录制 | 立即停止该产品线写入并保留现场 |
| P1 | 无 Leader、quorum 丢失、磁盘临界、Snapshot/Replay 失败 | 阻止发布与放量，执行恢复手册 |
| P1 | Export backlog 持续上升或 PG/Kafka 无法恢复 | 限制入口流量并修复外围 sink |
| P2 | p99、back pressure、NAK、retransmit 或 result unknown 持续超基线 | 调查网络、CPU、磁盘和客户端重试 |
| P2 | mark age、Risk scan 或 Lifecycle backlog 超基线 | 暂停相关产品执行并恢复输入链路 |

具体数值阈值不能在 P7 主观填写；P9 本机容量报告提供初始基线，P10 生产演练后才能冻结生产阈值。

## 8. 停止边界

P7 只证明六条产品线在本地真实三节点环境下功能、资金和恢复一致。P8 的正式准入报告、P9 的容量
压测以及 P10 的生产服务器演练必须分别获得确认后执行，不能把本手册或 P7 报告当作后续阶段证据。
