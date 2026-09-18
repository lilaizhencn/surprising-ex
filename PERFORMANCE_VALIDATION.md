# 性能验证记录

## 2026-09-18：e9692272 短时真实 Core 压测

### 锁定计划

- **目标**：验证最新 `master` 在 Owner、实时日志改造和快照包调整后，真实 Aeron Core 的短时交易链路仍可启动、持续处理并正确排空。
- **门槛**：单节点、单 matcher、全局 in-flight 固定 `256`；测量窗口内 `accepted == terminal`、Core 消息 accepted/terminal 相等、未完成量为零、资金差异为零、盘口为空、客户端无错误。
- **场景**：`LINEAR_PERPETUAL`，`MATCH_STREAM`，128 symbols，100 users，4 workers/4 connections，买卖 GTC 流；不启动 wallet。
- **时间**：预热 5 秒，正式测量 15 秒；当前机器只运行一个真实 Aeron Cluster 成员。
- **环境**：commit `e9692272`；Corretto HotSpot JDK 27.0.0.33.1；Maven 3.9.16；macOS x86_64；测量前磁盘可用约 420 GiB。

### 执行命令与偏差

```text
JAVA_HOME=/Users/atomex/.sdkman/candidates/java/27.0.0-amzn mvn -q \
  -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package

java [JDK 27 Aeron opens/exports] \
  -Dsurprising.aeron.product-line=LINEAR_PERPETUAL \
  -Dsurprising.aeron.hostnames=127.0.0.1 \
  -Dsurprising.aeron.account-lanes=4 \
  -Dsurprising.aeron.matching-engines=1 \
  -Dsurprising.aeron.owner-command-window=256 \
  -cp surprising-aeron-service/target/surprising-aeron-service.jar \
  com.surprising.aeron.service.SurprisingCoreApplication

java [JDK 27 Aeron opens/exports] \
  -Dsurprising.aeron.product-line=LINEAR_PERPETUAL \
  -Dsurprising.aeron.hostnames=127.0.0.1 \
  -Dsurprising.aeron.egress-hostname=127.0.0.1 \
  -Dsurprising.aeron.capacity-workload=MATCH_STREAM \
  -Dsurprising.aeron.capacity-symbol-count=128 \
  -Dsurprising.aeron.capacity-workers=4 \
  -Dsurprising.aeron.capacity-connections=4 \
  -Dsurprising.aeron.capacity-user-count=100 \
  -Dsurprising.aeron.capacity-async-in-flight=256 \
  -Dsurprising.aeron.capacity-warmup-seconds=5 \
  -Dsurprising.aeron.capacity-duration-seconds=15 \
  -cp surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  com.surprising.aeron.benchmarks.workload.ClusterCapacityMain
```

首次把 Core 作为后台进程启动时，执行环境在启动命令返回后回收了该进程，客户端得到 `NOT_CONNECTED (offer=-1)`；该次结果标记为**无效**，没有计入吞吐。改用保持前台会话的同一 Core 配置重跑后得到以下有效结果。

### 有效结果

- Core 启动约 0.74 秒，成为 `LEADER`；客户端退出码 `0`。
- 正式窗口 `15.006s`：`452,638` accepted，`452,638` terminal；Core terminal messages `454,534`（其中行情消息 `1,896`），未完成请求 `0`，失败 `0`，重试 `0`。
- 持续终态业务吞吐：`30,162.921 ops/s`；成交数 `226,319`，成交事件约 `15,081.461/s`。
- in-flight：最大 `256`，平均占用 `252.714`；提交数/完成数均为 `454,534`。
- 端到端请求延迟：p50 `8,003µs`，p99 `15,048µs`，p99.9 `26,476µs`，max `71,041µs`；买卖两侧 p99 分别为 `15,040µs` / `15,056µs`。
- 资金与状态：`fundsDiff=0`、`bookLevels=0`；满足本轮正确性门槛。

### 归因证据与限制

- 节点 JFR 记录约 86 秒，包含启动和测量前后空闲阶段，不能把整段 CPU 百分比直接当作正式窗口 CPU。热方法聚合中 `SettlementLaneWorker.run()` 占 execution samples 的 `87.46%`，需在更长稳定轮次中进一步确认是否为实际瓶颈。
- 节点 JFR 观察到最长 G1 pause `60.7ms`，另有 `13.7ms`；客户端最长 pause 约 `4.64ms`。本轮没有做停顿与单笔尾延迟的严格时间窗关联，因此不据此认定 p99 根因。
- 节点 NMT 相对负载前 baseline：reserved `+7,637KB`，committed `+15,601KB`；线程 `+5`。这是短窗口增量，不足以证明长稳无泄漏。
- 快照/恢复专项：`CoreStateSnapshotCodecTest` 与 `RuntimeCommitRecoveryTest` 共 `22` tests，`0` failures/errors/skips。

### 证据与清理

- 有效轮原始目录：`target/short-pressure/20260918T-short-e9692272-rerun/`，总大小约 `254M`。
- 关键文件校验：`node/node.jfr` SHA-256 `c78be77cf0a78027a5b02510f3ab4f50332277d2cd080f551504d940b0046bd3`；`client/client.jfr` SHA-256 `eb3cff24505293b2487df5bee2f8b8aaef171371150ad5ad2dba497abce34d4`；`client/client.log` SHA-256 `ccf515f7c8cd28bb7e357f06076e19b68336f9815d255aa79ae75e055d9a9156`。
- 首次无效轮原始目录：`target/short-pressure/20260918T-short-e9692272/`，总大小约 `258M`；原因已记录在“执行命令与偏差”。
- 清理状态：Core、客户端和 Aeron 进程均已停止；本轮临时目录、Archive/CNC、JFR、NMT、线程转储和日志在本记录完成后移入用户回收站，不保留工作区临时集群。
- 结论：**通过（短时功能/正确性压测）**；不是容量验收，也不能外推三节点云端容量或长稳表现。
