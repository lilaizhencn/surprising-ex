# surprising-margin-ops

[English](README.md) | [简体中文](README_CN.md)

保证金运营链路模块，包含风险、强平、资金费、保险基金和 ADL 的 API 与 provider。

## 模块

- `surprising-risk-api` / `surprising-risk-provider`：风险快照查询契约、保证金扫描和爆仓候选生成。
- `surprising-liquidation-api` / `surprising-liquidation-provider`：强平订单查询契约和 reduce-only 强平执行。
- `surprising-funding-api` / `surprising-funding-provider`：永续资金费查询契约、费率发布、资金费结算和账户流水集成。
- `surprising-insurance-api` / `surprising-insurance-provider`：保险基金查询/调整契约、强平费入账和穿仓亏损覆盖。
- `surprising-adl-api` / `surprising-adl-provider`：ADL 队列与事件契约、剩余亏损分摊和自动减仓执行。
- `surprising-margin-ops-provider`：以上五个 provider 的合并部署 jar。

## 合并 Provider 部署

`surprising-margin-ops-provider` 会在一个 JVM 里启动现有风险、强平、资金费、保险基金和 ADL 组件。这个改动只合并部署包：

- 业务包仍然按 `com.surprising.risk`、`com.surprising.liquidation`、`com.surprising.funding`、`com.surprising.insurance`、`com.surprising.adl` 隔离。
- 五个模块仍然通过已有 PostgreSQL 表、Kafka topic、outbox、幂等键、租约和 sequence 协作。
- 资金费结算只冻结一次标记价格输入，并持久化复合持仓游标；每个 keyset 分页使用独立短事务，批量写
  payment 与账户 outbox。原生带缓存 PostgreSQL sequence 消除了逐付款争抢 sequence 行的问题；账户结果
  批量消费并增量更新结算计数，不再为每个结果重新扫描全部 payment。
- 资金费持久化已按租约、序列、费率、结算、支付和账户 outbox 等物理表拆分，由 `FundingService`
  在业务事务内聚合。只有费率输入、到期费率选择、结算候选、自愈关联和支付结果原子回写需要跨表；
  源码均标注“不可拆原因”，且这些查询只服务在线资金安全链路。
- 保险基金持久化已按序列、基金余额、基金流水、缺口覆盖、产品线缺口、兼容缺口和账户 outbox 拆分，
  `InsuranceService` 与 `InsuranceCoverageReconciler` 在事务内聚合。只有覆盖记录与 reserve/finalize
  账户命令终态的自愈锁定查询保留跨表，并在源码标注“不可拆原因”。
- ADL 持久化已按序列、事件、执行 saga 和账户 outbox 拆分，由 `AdlService`、
  `AdlExecutionPersistenceService` 与 `AdlExecutionReconciler` 在业务事务内聚合。只有在线候选安全决策
  和 saga 与账户命令终态的自愈锁定需要共享数据库快照，源码均标注“不可拆原因”。
- 风险模块批量消费账户持仓事件，同一具体持仓只保留最高 revision，并让每个受影响的
  `用户 + 账户类型 + 结算资产` 风险组只扫描一次。完整持仓事件已经能够定位风险组，不再额外查询
  instrument；定时 keyset 扫描继续作为安全兜底。
- 多节点定时扫描由每条产品线唯一的 Redis token 租约协调，不再在节点启动时清空整条投影。
  每轮对账使用独立 generation 记录已观察风险组；持仓事件在对账期间同步登记，扫描结束后只删除
  本代未观察到的陈旧组。权威数据库加载与 Redis 替换由风险组锁串行，组状态、成员关系和反向索引
  使用 Lua 一次原子切换，价格线程不会读取到半更新投影。
- 风险持久化已经按物理表拆分为账户快照、持仓快照、强平候选、管理规则和 outbox 仓储，由
  `RiskPersistenceService` 在业务事务内聚合调用。`RiskRepository` 只保留实时风控所需的权威输入查询；
  其中持仓、合约、账户余额、负债、冻结和风险档位必须在同一数据库快照内组合，源码已逐项标注
  “不可拆原因”。
- 强平持久化同样按物理表拆分：候选、持仓锁、强平审计、管理员动作、交易订单、订单事件、
  trading outbox、合约默认费率和用户费率分别由独立仓储负责。`LiquidationService` 与
  `LiquidationOrderPersistenceService` 在事务内完成聚合，保证强平订单、订单事件和 outbox 原子提交。
  `LiquidationRepository` 只保留必须共享数据库快照或原子状态检查的实时强平查询，并标注“不可拆原因”。
- 强平候选时间线不再 JOIN 交易主库。后续财务运营模块应消费强平、订单、成交和资金事件，在独立
  数据库建立查询投影后提供时间线、资金对账与运营报表。
- 高风险账户聚合等后台报表查询不再由交易主库提供。后续财务运营模块应消费领域事件建立独立投影，
  并使用独立数据库完成跨表查询、资金对账和运营报表。
- 资金费后台结算时间线、跨账户对账和运营统计同样不得在交易主库增加 JOIN；后续统一由财务运营模块的
  独立数据库投影提供。
- 保险基金历史分析、跨用户覆盖对账和运营统计也只能进入财务运营独立数据库，不能扩展交易主库查询。
- ADL 后台执行时间线、穿仓分摊对账和运营统计同样只能由财务运营独立数据库的事件投影提供。交易主库中的
  ADL 跨表查询仅允许服务实时安全决策与资金一致性自愈，不能扩展为后台报表接口。
- 任何模块都不能直接读取另一个模块的内存状态。
- 原来的独立 provider jar 仍然保留，可以随时拆分部署。

合并 jar 默认端口是 `9088`，继续提供原有 API path：

```text
/api/v1/risk
/api/v1/admin/risk
/api/v1/liquidations
/api/v1/funding
/api/v1/insurance
/api/v1/adl
```

通过 gateway 使用合并 provider 时，把保证金运营相关 route 都指向同一个 base URL：

```bash
export GATEWAY_ROUTE_RISK_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_LIQUIDATION_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_FUNDING_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_INSURANCE_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_ADL_BASE_URL=http://localhost:9088
```

## 本地运行

合并进程：

```bash
mvn -pl :surprising-margin-ops-provider -am spring-boot:run
```

独立进程仍然可用：

```bash
mvn -pl :surprising-risk-provider -am spring-boot:run
mvn -pl :surprising-liquidation-provider -am spring-boot:run
mvn -pl :surprising-funding-provider -am spring-boot:run
mvn -pl :surprising-insurance-provider -am spring-boot:run
mvn -pl :surprising-adl-provider -am spring-boot:run
```

## 验证

```bash
mvn -pl :surprising-margin-ops-provider -am test
mvn -pl :surprising-margin-ops-provider -am -DskipTests package
```
