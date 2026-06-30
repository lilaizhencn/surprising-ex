# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Surprising 交易所后端服务。

这个仓库是交易所后端模块的根聚合工程。每个业务模块维护自己的详细 README 和部署说明。

## 模块

- `surprising-dependencies`：从 `surprising-wallet` 复制过来的统一依赖版本管理模块。
- `surprising-parent`：从 `surprising-wallet` 复制过来的公共父 POM。
- `surprising-candlestick`：合约 K 线服务。
- `surprising-price`：合约指数价格和标记价格服务。

## 模块文档

- [surprising-candlestick](surprising-candlestick/README_CN.md)
- [surprising-price](surprising-price/README_CN.md)

## 构建

```bash
mvn test
mvn -DskipTests package
```

## 数据库初始化

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

本仓库是新项目，数据库 schema 统一放在根目录 [init.sql](init.sql)，不使用 Flyway。

## 本地启动顺序

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-candlestick-provider -am spring-boot:run
mvn -pl :surprising-index-price-provider -am spring-boot:run
mvn -pl :surprising-mark-price-provider -am spring-boot:run
```

端口：

- `9081`：K 线服务。
- `9082`：指数价格和法币汇率服务。
- `9083`：标记价格服务。

## Kafka Topics

- `surprising.perp.trade.events.v1`：合约成交输入。
- `surprising.perp.candle.events.v1`：K 线快照输出。
- `surprising.perp.index.price.v1`：指数价格输出。
- `surprising.perp.index.components.v1`：指数成分审计输出。
- `surprising.perp.book.ticker.v1`：合约盘口最优价输入。
- `surprising.perp.funding.rate.v1`：资金费率输入。
- `surprising.perp.mark.price.v1`：标记价格输出。
- `surprising.perp.mark.price.audit.v1`：标记价格审计输出。

所有市场数据 topic 都使用 `symbol` 作为 Kafka key。

## API 快速检查

```bash
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
```

## 生产部署要点

- 每个 provider 至少部署 2 个实例。
- Kafka topic replication factor 生产建议为 `3`。
- K 线服务用 Kafka Streams + RocksDB 分区状态，按 Kafka partition 水平扩展。
- 指数价格和标记价格服务用 PostgreSQL 的 symbol 租约和数据库 sequence 避免多节点重复发布和 sequence 回退。
- WebSocket 推送服务应独立消费 Kafka 输出 topic，不要放进 K 线或价格计算服务里。
- 外部交易所行情采集以 WebSocket 为主，REST 只做冷启动和兜底。
- 法币汇率只用于展示和估值提示，不参与合约风控核心价格替代。

## 文档

- [部署说明](docs/deployment.md)
- [数据库设计](docs/database.md)
