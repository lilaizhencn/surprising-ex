# LINEAR_PERPETUAL U 本位永续产品线部署 Runbook

## 1. 运行边界

- 进程只允许使用 `PRODUCT_LINE=LINEAR_PERPETUAL`，账户类型为 `USDT_PERPETUAL`，合约类型为 `LINEAR_PERPETUAL`。
- 业务 Topic 前缀固定为 `surprising.linear-perp.`；所有 consumer group、client id、协调节点和 gateway 路由必须带 `linear-perp`。
- 必须部署指数价、标记价、风险、强平、保险基金、ADL 和资金费；不得接收现货、交割或期权 Topic。
- 后台订单时间线、资金对账和运营报表只读未来独立财务运营库，交易主库只承载在线事实写入。

## 2. 部署前检查

```bash
export PRODUCT_LINE=LINEAR_PERPETUAL
export PRODUCT_TOPICS_ENABLED=true
export KAFKA_BOOTSTRAP_SERVERS='<MSK 地址>'
export SPRING_DATASOURCE_URL='jdbc:postgresql://<RDS>/surprising_exchange?reWriteBatchedInserts=true'

PRODUCT_LINES=LINEAR_PERPETUAL INCLUDE_SHARED_TOPICS=true INCLUDE_LEGACY_PERP_TOPICS=false \
  PARTITIONS=32 ACCOUNT_COMMAND_PARTITIONS=32 REPLICATION_FACTOR=3 \
  BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" ./scripts/create-topics.sh
```

校验 `surprising.linear-perp.*` 主题、账户命令/DLT/结果主题均为 32 分区、RF=3，Key 分别为 `symbol` 或 `LINEAR_PERPETUAL:userId`。确认标记价新鲜度、资金费周期、风险档位、强平费率、ADL 队列和保险基金初始余额已复核。

## 3. 启动与停止

```bash
mvn -q -DskipTests package
SERVICES="candlestick index-price mark-price trading-entry matching account margin-ops edge market-maker" \
PRODUCT_LINE=LINEAR_PERPETUAL PRODUCT_TOPICS_ENABLED=true BUILD_SERVICES=false \
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL}" \
./scripts/start-product-line-providers.sh

PRODUCT_LINE=LINEAR_PERPETUAL \
SERVICES="candlestick index-price mark-price trading-entry matching account margin-ops edge market-maker" \
ACTION=stop ./scripts/start-product-line-providers.sh
```

做市账户必须先充值并完成风险限额复核。撮合节点使用同一消费组；WebSocket 节点使用各自唯一消费组，避免公共行情漏发。

## 4. 验证流程

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=false \
  RECONCILE_FUNDS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false \
  ./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=LINEAR_PERPETUAL STRICT_ZERO_OPENING=true \
  ./scripts/product-line-funds-reconcile.sh
```

验证下单、撤单、成交、持仓形成、主动平仓、标记价风险、强平、强平费、保险基金、ADL、资金费、私有/公共 WebSocket 和资金守恒。资金核对必须逐项对平期初、充值/调整、成交、手续费、资金费、强平费和期末余额。

## 5. 监控与回滚

重点监控标记价新鲜度、资金费发布延迟、风险快照、强平 candidate、ADL lease、保险基金余额、账户命令积压、Outbox 最老年龄和 Redis readiness。发生错误强平或资金不平时，先停止下单入口并冻结相关 symbol，保留事件和位点，使用幂等重放恢复；禁止删除 Topic、跳过账户确认或手工改余额。
