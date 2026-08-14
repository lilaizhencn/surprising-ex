# Aeron 安装、六线门禁与无上限压测

本文件对应迁移计划 P7–P10。P10 仅提供部署演练材料，不假设生产服务器已经就绪。

## 1. 本地安装

要求 JDK 25、Docker、Docker Compose、PostgreSQL、Kafka 和 Valkey。Aeron Cluster 以三 Member
运行；每条产品线使用独立 `clusterId`、端口空间和三个持久卷。不要删除卷来“修复”启动问题。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd surprising-ex
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh build
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh up
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh wait-ready
PRODUCT_LINE=SPOT scripts/aeron-core-local.sh hash
```

Clash 下载 Aeron 依赖时：

```bash
export HTTPS_PROXY=http://127.0.0.1:7897
export HTTP_PROXY=http://127.0.0.1:7897
export ALL_PROXY=http://127.0.0.1:7897
```

## 2. 六线功能门禁

门禁必须逐条产品线执行，不用一条线的结果替代另一条线。脚本直接连接最终三节点 Aeron Cluster，
运行产品线核心 smoke、恢复和资金对账；任何一条失败立即停止，证据写入
`reports/product-line-gates/<UTC>/`。

```bash
./scripts/run-six-product-line-gates.sh
```

只有每条线都具备 `functional-gate=PASS` 且 `funds-diff=0`，才具备申请 P8 的条件。SPOT 不执行
衍生品专属 funding/liquidation 场景，但必须通过现货余额、双资产成交和恢复验证。P7 完成不会自动
开始 P8 或 P9。

## 3. 无预设 OPS 压测

压测不读取文档中的目标 OPS。它从 `START_TPS` 开始，以 `STEP_TPS` 递增，直到性能门禁失败；
最后一个连续通过且稳定的档位才是报告中的 `stable_last_pass_ops`。失败原因可能是延迟、错误率、
Kafka/Exporter backlog、资源、恢复或资金差异，不是“进程还没崩溃”。

```bash
PRODUCT_LINE=LINEAR_PERPETUAL \
  START_TPS=100 STEP_TPS=100 MAX_TPS=1000000 \
  ./scripts/run-uncapped-aeron-capacity.sh
```

每条线单独运行并保存环境 manifest、命令行、资源采样、延迟分位数、核心提交 OPS、撮合事件 OPS、
端到端结算 OPS、资金差异和恢复时间。不要并行压测六条线，以免无法归因。

## 4. 部署拓扑

每条产品线三台 Aeron Member（共六个 Cluster、18 个 Member）；Gateway/Order/Risk/ADL 等外围
服务通过 Aeron client pool 连接当前产品线三节点。Kafka 继续承载外围输入缓冲、行情和通知；
PostgreSQL 只承载查询/审计投影；Valkey 只承载非权威缓存。Aeron Log/Archive/Snapshot 是资金、
订单、Book、Risk、Liquidation 和 Treasury 的唯一恢复链。

Member 环境变量至少包括：

```text
PRODUCT_LINE=<one-of-six>
AERON_NODE_ID=0|1|2
AERON_HOSTNAMES=node0,node1,node2
AERON_DATA_DIR=/var/lib/surprising/aeron
```

磁盘必须使用本地 SSD/NVMe，Archive、Cluster log 和 Snapshot 分开目录；备份前执行 Archive
segment 校验并记录状态 hash。滚动升级顺序为 follower → follower → leader，任何 hash 不一致
或 replay 失败都停止发布。

## 5. 运行验收

部署前后都执行：三节点健康、leader kill、冷恢复、状态 hash 一致、Exporter 重放、Kafka/PG
短时故障、用户资金对账和六线 API smoke。P10 尚无服务器，因此这些命令目前是部署 Runbook，
不能伪造为已完成生产演练。
