# SPOT 现货产品线部署 Runbook

## 1. 运行边界

- 进程只允许使用 `PRODUCT_LINE=SPOT`，账户类型必须是 `SPOT`，instrument 合约类型必须是 `SPOT`。
- 业务 Topic 前缀固定为 `surprising.spot.`；消费组、client id、Streams 状态目录和 gateway 路由都必须带 `spot`。
- 现货只处理资产买卖、冻结、成交扣减、撤单解冻和产品账户流水，不启动资金费、强平、ADL 或交割服务。
- 订单时间线、资金对账和运营报表不得查询交易主库多表 JOIN；后续财务运营模块使用独立数据库投影。

## 2. 部署前检查

```bash
export PRODUCT_LINE=SPOT
export PRODUCT_TOPICS_ENABLED=true
export KAFKA_BOOTSTRAP_SERVERS='<MSK 地址>'
export SPRING_DATASOURCE_URL='jdbc:postgresql://<RDS>/surprising_exchange?reWriteBatchedInserts=true'

PRODUCT_LINES=SPOT INCLUDE_SHARED_TOPICS=true INCLUDE_LEGACY_PERP_TOPICS=false \
  PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 REPLICATION_FACTOR=3 \
  BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" ./scripts/create-topics.sh
```

校验 `surprising.spot.*` 业务 Topic、共享 Topic 均为 32 分区、RF=3，Broker 开启
`min.insync.replicas=2`、关闭自动建 Topic。确认数据库迁移已完成，现货 symbol 的余额、资产精度和手续费配置已复核。

## 3. 启动与停止

```bash
mvn -q -DskipTests package
SERVICES="candlestick trading-entry matching account edge market-maker" \
PRODUCT_LINE=SPOT PRODUCT_TOPICS_ENABLED=true BUILD_SERVICES=false \
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL}" \
./scripts/start-product-line-providers.sh

PRODUCT_LINE=SPOT SERVICES="candlestick trading-entry matching account edge market-maker" \
ACTION=stop ./scripts/start-product-line-providers.sh
```

每个生产实例只运行现货线。做市进程在交易链路测试期间必须保持运行；撮合、账户和 WebSocket 不得复用其它产品线消费组。

## 4. 验证流程

```bash
PRODUCT_LINES=SPOT BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=false \
  RECONCILE_FUNDS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false \
  ./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=SPOT STRICT_ZERO_OPENING=true \
  ./scripts/product-line-funds-reconcile.sh
```

至少验证：买方和卖方资产冻结、撮合成交、手续费扣减、撤单释放、部分成交、余额流水与期末余额相等；不得出现衍生品持仓、保证金、资金费或强平流水。

## 5. 监控与回滚

按 `productLine=SPOT` 监控订单/成交延迟、账户命令积压、Outbox 最老记录、余额流水失败、Kafka lag 和 WebSocket fanout。发现账账不平、重复扣减或跨线 Topic 消费时，立即停止订单入口和撮合，保留 Kafka 位点与数据库快照，修复后从 Outbox 幂等重放；禁止删除 Topic 或直接修改余额。
