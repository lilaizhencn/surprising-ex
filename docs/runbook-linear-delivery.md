# LINEAR_DELIVERY U 本位交割产品线部署 Runbook

## 1. 运行边界

- 进程只允许使用 `PRODUCT_LINE=LINEAR_DELIVERY`，账户类型为 `USDT_DELIVERY`，合约类型为 `LINEAR_DELIVERY`。
- 业务 Topic 前缀固定为 `surprising.linear-delivery.`；交割事件使用 `surprising.linear-delivery.delivery.settlements.v1`。
- 部署指数价、标记价、风险、强平、保险基金和 ADL；不部署资金费计算与结算。到期通过 lifecycle drain 排空订单、触发单和账户确认。
- 交割和运营报表由未来财务运营库承载，交易主库不允许新增后台多表 JOIN。

## 2. 部署前检查

```bash
export PRODUCT_LINE=LINEAR_DELIVERY
export PRODUCT_TOPICS_ENABLED=true
export KAFKA_BOOTSTRAP_SERVERS='<MSK 地址>'
export SPRING_DATASOURCE_URL='jdbc:postgresql://<RDS>/surprising_exchange?reWriteBatchedInserts=true'

PRODUCT_LINES=LINEAR_DELIVERY INCLUDE_SHARED_TOPICS=true INCLUDE_LEGACY_PERP_TOPICS=false \
  PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 REPLICATION_FACTOR=3 \
  BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" ./scripts/create-topics.sh
```

确认每个 instrument 的 `expiry_time`、`delivery_time`、结算价来源和 `settlement_method`，并预置只减仓时间窗、结算人工复核人和重跑策略。校验交割 Topic 与普通产品线 Topic 均为 32 分区、RF=3。

## 3. 启动与停止

```bash
mvn -q -DskipTests package
SERVICES="candlestick index-price mark-price trading-entry matching account margin-ops edge market-maker" \
PRODUCT_LINE=LINEAR_DELIVERY PRODUCT_TOPICS_ENABLED=true BUILD_SERVICES=false \
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL}" \
./scripts/start-product-line-providers.sh

PRODUCT_LINE=LINEAR_DELIVERY \
SERVICES="candlestick index-price mark-price trading-entry matching account margin-ops edge market-maker" \
ACTION=stop ./scripts/start-product-line-providers.sh
```

资金费服务必须保持停止。到期调度只能调用 Service，不能由 task 直接操作 Repository；订单和触发单确认后才能推进 instrument 状态。

## 4. 验证流程

```bash
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=false \
  RECONCILE_FUNDS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false \
  ./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=LINEAR_DELIVERY STRICT_ZERO_OPENING=true \
  ./scripts/product-line-funds-reconcile.sh
```

必须验证到期前只减仓、生命周期版本、ORDER/TRIGGER/ACCOUNT 三类 drain 确认、挂单撤销、冻结资金释放、现金交割流水和持仓归零；报告中不得出现资金费流水。

## 5. 监控与回滚

监控到期窗口、lifecycle 版本、排空确认缺口、交割结算价、交割流水、持仓归零耗时、账户 Outbox 和 Kafka lag。任一确认缺失时暂停结算推进并保留 `symbol + version`；修复后只允许从幂等事件重放，禁止强制把 instrument 改为 `CLOSED` 或手工清理持仓。
