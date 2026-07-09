# surprising-margin-ops

[English](README.md) | [简体中文](README_CN.md)

保证金运营链路模块，包含强平、资金费、保险基金和 ADL 的 API 与 provider。

## 模块

- `surprising-liquidation-api` / `surprising-liquidation-provider`：强平订单查询契约和 reduce-only 强平执行。
- `surprising-funding-api` / `surprising-funding-provider`：永续资金费查询契约、费率发布、资金费结算和账户流水集成。
- `surprising-insurance-api` / `surprising-insurance-provider`：保险基金查询/调整契约、强平费入账和穿仓亏损覆盖。
- `surprising-adl-api` / `surprising-adl-provider`：ADL 队列与事件契约、剩余亏损分摊和自动减仓执行。
- `surprising-margin-ops-provider`：以上四个 provider 的合并部署 jar。

## 合并 Provider 部署

`surprising-margin-ops-provider` 会在一个 JVM 里启动现有强平、资金费、保险基金和 ADL 组件。这个改动只合并部署包：

- 业务包仍然按 `com.surprising.liquidation`、`com.surprising.funding`、`com.surprising.insurance`、`com.surprising.adl` 隔离。
- 四个模块仍然通过已有 PostgreSQL 表、Kafka topic、outbox、幂等键、租约和 sequence 协作。
- 任何模块都不能直接读取另一个模块的内存状态。
- 原来的独立 provider jar 仍然保留，可以随时拆分部署。

合并 jar 默认端口是 `9088`，继续提供原有 API path：

```text
/api/v1/liquidations
/api/v1/funding
/api/v1/insurance
/api/v1/adl
```

通过 gateway 使用合并 provider 时，把四条 route 都指向同一个 base URL：

```bash
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
