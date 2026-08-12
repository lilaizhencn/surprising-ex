# OPTION 欧式期权产品线部署 Runbook

## 1. 运行边界

- 进程只允许使用 `PRODUCT_LINE=OPTION`，账户类型为 `OPTION`，合约类型为 `VANILLA_OPTION`。
- 业务 Topic 前缀固定为 `surprising.option.`；到期行权事件使用 `surprising.option.option.exercises.v1`。
- 部署指数价、标记价、风险、强平、保险基金和 ADL；不部署资金费。当前模型为单腿、现金结算、欧式自动行权，不允许提前行权。
- 权利金、保证金、行权和运营报表的查询投影由未来独立财务运营库提供，不在交易主库执行多表 JOIN。

## 2. 部署前检查

```bash
export PRODUCT_LINE=OPTION
export PRODUCT_TOPICS_ENABLED=true
export KAFKA_BOOTSTRAP_SERVERS='<MSK 地址>'
export SPRING_DATASOURCE_URL='jdbc:postgresql://<RDS>/surprising_exchange?reWriteBatchedInserts=true'

PRODUCT_LINES=OPTION INCLUDE_SHARED_TOPICS=true INCLUDE_LEGACY_PERP_TOPICS=false \
  PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 REPLICATION_FACTOR=3 \
  BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" ./scripts/create-topics.sh
```

逐个复核标的、到期日、行权价、CALL/PUT、结算价来源和行权账户边界。确认买方权利金、卖方保证金、行权 payoff、保险基金和风险限额配置，Topic 分区为 32、RF=3。

## 3. 启动与停止

```bash
mvn -q -DskipTests package
SERVICES="candlestick index-price mark-price order trigger matching account risk liquidation funding insurance adl gateway websocket market-maker" \
PRODUCT_LINE=OPTION PRODUCT_TOPICS_ENABLED=true BUILD_SERVICES=false \
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL}" \
./scripts/start-product-line-providers.sh

PRODUCT_LINE=OPTION \
SERVICES="candlestick index-price mark-price order trigger matching account risk liquidation funding insurance adl gateway websocket market-maker" \
ACTION=stop ./scripts/start-product-line-providers.sh
```

资金费服务必须保持停止。做市策略和行情源的每一项配置都必须显式写 `OPTION`，禁止混入永续或现货 symbol。

## 4. 验证流程

```bash
PRODUCT_LINES=OPTION BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=false \
  RECONCILE_FUNDS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false \
  ./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=OPTION STRICT_ZERO_OPENING=true \
  ./scripts/product-line-funds-reconcile.sh
```

必须验证权利金支付、卖方保证金冻结、成交与撤单、风险/强平、到期排空、CALL/PUT payoff、`OPTION_PREMIUM` 与 `OPTION_EXERCISE` 流水、行权后持仓归零和 WebSocket 私有/公共事件。不得出现资金费或其它产品线账户类型流水。

## 5. 监控与回滚

监控标的结算价新鲜度、行权事件幂等键、行权流水、保证金释放、持仓归零、强平 candidate、Outbox 和 Kafka lag。行权价格或账户流水异常时，暂停到期任务和下单入口，保留 instrument 版本及原始事件，修复后按事件幂等重放；禁止手工改 payoff、余额或持仓。
