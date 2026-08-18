# surprising-market-data

统一的市场数据部署单元，按 `ProductLine` 独立运行：

- `surprising-market-data-api`：K 线查询 RPC contract 与共享 DTO。为了兼容调用方，Java 包名继续使用
  `com.surprising.candlestick.api`，artifact 已统一为 `surprising-market-data-api`。
- `surprising-market-data-provider`：合并 Matching 的 Aeron Core 行情投影与 Candlestick 的 Kafka Streams
  聚合，只保留一个 Spring Boot 应用、健康检查、数据源和部署进程。

## 状态边界

- Aeron Core 是可执行订单簿、订单、资金和持仓的唯一事实源。
- Matching 投影通过 `MatchingAeronGateway` 查询 Core，并把 L2 快照和公共逐笔发布到产品线隔离的 Kafka
  Topic；该投影可重建，不参与交易裁决。
- Candlestick 消费公共逐笔 Topic，使用独立 Kafka Streams `application-id`、RocksDB state-dir 和 changelog
  恢复 K 线状态；PostgreSQL 只保存历史 K 线查询投影。
- 两条链路共享 JVM 不代表共享业务状态。Aeron client、Kafka producer、Kafka Streams state store 和 consumer
  group 仍按职责隔离。
- Instrument snapshot 在进程内只初始化一份，由 Candlestick 的 snapshot consumer 按 revision 幂等更新。

## 接口与端口

统一端口为 `9081`：

- `GET /api/v1/trading/market/**`：盘口和公共成交查询。
- `GET /api/v1/candlestick/**`：K 线查询。
- `GET /actuator/health`：统一健康检查。

Gateway 对外路径不变，只把原 Matching 的 `9085` 路由改到 Market Data 的 `9081`。

## 本地运行

```bash
PRODUCT_LINE=LINEAR_PERPETUAL \
JAVA_TOOL_OPTIONS="--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-market-data-provider -am spring-boot:run
```

每条产品线必须配置独立的 Kafka Topic、consumer group、Kafka Streams application-id override 和 state-dir；
不得让六条产品线复用同一个本地 RocksDB 目录。

## 验证

```bash
mvn -pl :surprising-market-data-provider -am test
mvn -pl :surprising-gateway -am -Dtest=GatewayProxyServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
