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
- Candlestick 消费产品线 `match.trades` 公共逐笔 Topic，逐笔链路只聚合 1 分钟 K 线。关闭的 1 分钟 K 线
  成功写入 PostgreSQL 后，才发布到产品线 `candle.events` Topic；独立的 Kafka Streams 回读分支只消费
  `CLOSED + 1m`，在 RocksDB 中异步生成配置的高周期快照并回写同一 Topic。高周期事件不会再次进入聚合，
  PostgreSQL 永远只保存关闭的 1 分钟行。
- PostgreSQL 写入成功后的 `CLOSED + 1m` 不可修订，迟到逐笔不再修改已关闭分钟。高周期以每个
  symbol/period 的活动桶水位线推进，看到下一桶或墙钟到达桶结束时间后关闭前一桶；落后于水位线的迟到分钟
  直接丢弃，已经发布的高周期 `CLOSED` 同样不可修订。实时行情允许舍弃迟到数据，不承担历史纠错职责。
- 高周期历史查询通过有界 SQL 从关闭的 1 分钟行精确计算 OHLC、成交量、成交数以及首末成交标识；
  热查询由 RocksDB 驱动的本地缓存提供。历史查询以 PostgreSQL 的关闭分钟为准；写库失败时 dirty store
  保留快照重试，且不会提前发布关闭事件。
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

Kafka Topic、consumer group 和默认 Kafka Streams application-id 均由 `ProductTopicNames` 根据
`ProductLine` 生成。每条产品线必须使用独立的 application-id/state-dir，并按一条产品线一个部署实例和
数据库 schema 运行；当前 `candlestick_candles` 表不包含 `product_line` 列，禁止多产品线共享同一 schema。

## 验证

```bash
mvn -pl :surprising-market-data-provider -am test
mvn -pl :surprising-gateway -am -Dtest=GatewayProxyServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
