# Surprising Liquidation

强平权威状态和资金结算位于每条产品线的 Aeron Cluster。`surprising-liquidation` 是无状态
Liquidation Coordinator，不保存候选、不创建交易订单，也不参与资金裁决。

## 运行流程

1. 定时调用 `LIQUIDATION_WORK_QUERY`，取得有界 `PLANNED` action 和 Risk Scan pending 标记。
2. 每个 action 使用 `productLine + liquidationId + triggerSequence + markPrice + feeRate` 派生稳定
   `commandId`，提交 `EXECUTE_LIQUIDATION`。
3. Core 原子校验并执行 takeover；陈旧计划视为无害过期，不回退 Kafka、Redis 或 PG。
4. 存在未完成扫描时提交一条 `CONTINUE_RISK_SCAN`。
5. REST 历史和后台列表只读 `core_liquidation_projection`。

## 依赖边界

- 实时必需：当前产品线三节点 Aeron Cluster。
- 查询必需：PostgreSQL Core Query Projection。
- 不依赖：Redis/Valkey、强平 Kafka candidate、match-result 回环、Account API、Instrument API、Price
  Consumer、应用 WAL、RocksDB、PG 强平事务或 outbox。

## 配置

- `surprising.liquidation.product-line`
- `surprising.liquidation.aeron.hostnames`：恰好三个 Member hostname。
- `surprising.liquidation.aeron.egress-hostname`
- `surprising.liquidation.coordinator.delay-ms`
- `surprising.liquidation.coordinator.work-batch-size`：`1..1000`。
- `surprising.liquidation.coordinator.risk-scan-batch-size`：`1..4096`。
- `surprising.liquidation.execution.enabled`
- `surprising.liquidation.execution.liquidation-fee-rate-ppm`：`0..1000000`。

运行参数只从部署配置读取。管理 API 可查看当前配置，但不提供单实例内存热修改，避免多副本配置漂移。
