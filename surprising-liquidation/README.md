# Surprising Liquidation

强平权威状态和资金结算位于每条产品线的 Aeron Cluster。`surprising-liquidation` 是无状态
Liquidation Coordinator，不保存候选、不创建交易订单，也不参与资金裁决。

## 运行流程

1. 定时调用 `LIQUIDATION_WORK_QUERY`，取得有界 `PLANNED`/`ORDERED` action、每个 action 的独占取消游标和
   精确的 Risk Scan continuation。
2. 将本次 work 原样编码为一个 `EXECUTE_LIQUIDATION_BATCH`，按
   `productLine + canonical batch payload` 派生稳定 `commandId`。Core 在一个命令内共享最多 1,024 笔撤单预算，
   按 action 顺序返回 `applied/pending/obsolete/processedOrders` 结果，并持久化下一页 cursor。
3. Provider 正常周期不逐 action 往返、不调用 `CONTINUE_RISK_SCAN`，也不做业务重试或建立无界积压队列；一次
   work 查询只对应一次批量提交。命令超时后复用同一稳定 `commandId`，由 Core 的幂等结果和 continuation 处理。
4. Core 对同 symbol 结算、同 user+symbol 强平和订单变更执行生命周期栅栏；不同 symbol 仍可并行。Core matcher
   尝试超时或异常时只做一次有界 rebuild/resubmit，第二次失败返回 `MATCHING_CONTINUATION_FAILED`。
5. REST 历史和后台列表只读 `core_liquidation_projection`。

## 依赖边界

- 实时必需：当前产品线三节点 Aeron Cluster。
- 查询必需：PostgreSQL Core Query Projection。
- 不依赖：Redis/Valkey、强平 Kafka candidate、match-result 回环、Account API、Instrument API、Price
  Consumer、应用 WAL、RocksDB、PG 强平事务或 outbox。

强平 Coordinator 不消费指数价或标记价 Kafka topic，也不维护价格副本。Core 的价格入口是
`ApplyMarkPriceCommand`，Core 在同一状态机内完成风险扫描、候选生成和强平执行校验；Coordinator
只领取 Core 的有界强平工作并提交 `EXECUTE_LIQUIDATION`。因此统一的
`surprising.<product-line>.price.events.v1` 只影响价格发布、审计、网关和共享缓存消费者，不改变强平的唯一裁决路径。

## 配置

- `surprising.liquidation.product-line`
- `surprising.liquidation.aeron.hostnames`：恰好三个 Member hostname。
- `surprising.liquidation.aeron.egress-hostname`
- `surprising.liquidation.coordinator.delay-ms`
- `surprising.liquidation.coordinator.work-batch-size`：`1..1000`，只控制 Core work 查询返回的 action 数量；
  单条执行命令的撤单预算固定不超过 1,024。
- `surprising.liquidation.coordinator.risk-scan-batch-size`：`1..4096`，作为批量命令中的 Risk Scan continuation
  用户预算。
- `surprising.liquidation.aeron.client-connections`：仅保留连接池容量配置；正常批处理路径不按 action 并发发送
  Aeron 请求。
- `surprising.liquidation.execution.enabled`
- `surprising.liquidation.execution.liquidation-fee-rate-ppm`：`0..1000000`。

运行参数只从部署配置读取。管理 API 可查看当前配置，但不提供单实例内存热修改，避免多副本配置漂移。
