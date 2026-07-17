# surprising-price

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约指数价格和标记价格模块。

## 模块

- `surprising-price-api`：RPC 合约和 Kafka 事件模型。
- `surprising-index-price-provider`：外部现货源采集和指数价格计算。
- `surprising-mark-price-provider`：用于风控、强平、账户权益和 WebSocket 展示的标记价格计算。
- `surprising-price-provider`：指数价格和标记价格合并部署 jar。

## 架构

```text
外部现货交易所
  -> surprising-index-price-provider
  -> surprising.perp.index.price.v1
  -> surprising-mark-price-provider
  -> surprising.perp.mark.price.v1
  -> risk / liquidation / account / websocket

法币汇率源 + USDT/USD 稳定币行情
  -> surprising-index-price-provider
  -> PostgreSQL price_exchange_rates
  -> app / api-gateway 展示本地法币价格
```

这个模块刻意和 K 线服务分开。指数价格和标记价格是风控输入，K 线是行情历史聚合。分开部署可以避免风控价格被 K 线查询或 WebSocket 推送压力影响。

外部行情采集和展示 API 保留 decimal 价格。交易、风控、强平、资金费结算和 ADL 的实时边界是已提交的
Kafka `MarkPriceEvent`；事件直接携带产品线、instrument 版本、quote asset units、可比较 ticks、sequence
和时间戳。`price_mark_ticks` 只是异步写入、保留 3 天的审计表，不能作为实时业务输入。

合约清单和交易规则来自 `surprising-instrument`。指数价格 provider 默认从 `instruments + instrument_index_sources` 当前版本动态读取 symbol 和外部指数源；`application.yml` 中的静态 BTC/ETH 源仅作为数据库未初始化时的兜底。

## 合并 Provider 部署

`surprising-price-provider` 会在一个 JVM 里启动现有指数价格和标记价格组件，适合开发环境、单节点或低成本部署。这个改动只合并部署包：

- 指数价格业务逻辑仍在 `com.surprising.price.index`。
- 标记价格业务逻辑仍在 `com.surprising.price.mark`。
- 标记价格仍然消费配置里的指数价格 Kafka topic，不直接读取 index provider 的内存变量。
- 持久边界仍然是 PostgreSQL 和 Kafka topic，所以以后拆回独立部署不需要改计算逻辑。

合并 jar 默认端口是 `9082`，同时提供 `/api/v1/price/index` 和 `/api/v1/price/mark`。使用合并部署时，mark-price 客户端和 gateway 的 mark 路由要指向同一个 base URL，例如 `GATEWAY_ROUTE_PRICE_MARK_BASE_URL=http://localhost:9082`。

## 指数价格

指数价格服务默认使用外部交易所 WebSocket 长连接接收 ticker，标准化价格，剔除过期源和异常源，重新归一化权重，然后为每个 symbol 发布一个公允指数价格。REST 只用于冷启动、WebSocket 缓存过期后的兜底，以及人工排障。

默认外部源：

- Binance Spot
- OKX Spot
- Bybit Spot
- Coinbase Exchange
- Kraken Spot

公式：

```text
sourceMid = (bestBid + bestAsk) / 2
validSources = enabled && fresh && notOutlier
indexPrice = Σ(sourceMid_i * normalizedWeight_i)
```

异常剔除：

- 先用健康源计算中位数价格。
- 如果 `abs(sourcePrice - median) / median > outlier-threshold`，剔除这个源。
- 默认 `outlier-threshold` 是 `0.01`，也就是 1%。
- 默认至少需要 `3` 个有效源。

指数价格 topic：

```text
surprising.perp.index.price.v1
surprising.perp.index.components.v1
```

components topic 会记录每个外部源的价格、权重、状态和剔除原因，用于审计。

## WebSocket 和 REST

REST 速率只适合小规模测试或兜底。如果用 REST 作为主链路，请求量大致是：

```text
requests_per_second = symbol_count * source_count / poll_interval_seconds
```

例如 500 个 symbol、5 个外部源、1 秒刷新一次，就是 2500 req/s，主流交易所通常无法稳定承受，也容易触发限流或地区策略。因此生产默认使用 WebSocket：

- 按 `websocket-url` 复用连接，同一个交易所连接里发送多个订阅。
- 收到的 ticker 写入内存最新价缓存，指数定时任务优先读取这个缓存。
- 如果缓存超过 `max-source-age`，才临时走 REST 兜底。
- 握手失败、连接关闭、连接报错、超过 `idle-timeout` 没有收到任何帧，都会触发重连。
- 重连使用指数退避加随机抖动，默认从 `1s` 到 `30s`，避免集中重连打爆外部交易所。
- WebSocket 只负责采集外部现货源；内部业务 WebSocket 推送仍然消费 Kafka 的指数价格和标记价格 topic，不和计算服务耦合。

## USD/USDT 和法币汇率

指数价格面向 USDT 合约，外部源可能是 USDT 交易对，也可能是 Coinbase 这类 USD 交易对。USD 源会先转换为 USDT 口径再参与指数：

```text
BTC-USDT price from Coinbase = BTC-USD / USDT-USD
```

默认使用 Coinbase `USDT-USD` ticker 作为稳定币换算源，`conversion-operation: DIVIDE`。如果稳定币换算源不可用，默认不会直接丢弃 Coinbase，而是按 `fallback-weight-multiplier` 折扣权重；如果风控政策更严格，可以把 `conversion-mode` 改为 `DISABLE`。

客户国家法币展示和风控价格分开处理。系统会定时拉取 USD 基准法币汇率，写入 `price_exchange_rates` 表；前端、网关或账户展示层只查询本地接口，不直接访问第三方汇率源。

默认开发配置：

- 法币汇率源：`https://open.er-api.com/v6/latest/USD`
- 稳定币桥接：Coinbase `USDT-USD`
- 转换路径示例：`USDT -> USD -> CNY`

生产建议：

- 使用付费 FX 主源，例如 Open Exchange Rates、Currencylayer、OANDA、Xignite、Refinitiv 这类带 SLA 的服务。
- 保留一个备用源，例如 ECB/Frankfurter 或另一个商业供应商。
- 法币汇率只用于展示、报表和估值提示，不应该替代合约风控里的指数价格和标记价格。
- 对强监管地区展示价格时，需要记录汇率来源、更新时间和汇率版本，方便审计。

汇率接口：

```bash
curl 'http://localhost:9082/api/v1/price/fx/latest?baseCurrency=USD&quoteCurrency=CNY'
curl 'http://localhost:9082/api/v1/price/fx/rates?baseCurrency=USD'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=100&fromCurrency=USDT&toCurrency=CNY'
```

## 标记价格

标记价格服务消费：

```text
surprising.perp.index.price.v1
surprising.perp.book.ticker.v1
surprising.perp.trade.events.v1
surprising.perp.funding.rate.v1
```

只发布一个完整事件：

```text
surprising.perp.mark.price.v1
```

消息中的 `result` 供实时消费者使用，同时携带本次计算实际使用的指数、盘口、成交、资金费和 basis
输入。独立审计 consumer group 异步持久化同一条消息，不再维护第二个审计 topic。
各输入 listener 只替换内存中的最新样本；只有固定的一秒调度器能够计算和发布标记价格，输入事件
到达本身不会触发发布。

计算方式综合了 OKX/Binance/Bybit 的常见做法：

```text
basis = (bestBid + bestAsk) / 2 - indexPrice
basisAverage = movingAverage(basis, 60s)

price1 = indexPrice * (1 + fundingRate * timeUntilFunding / fundingInterval)
price2 = indexPrice + basisAverage
rawMark = median(price1, price2, lastTradePrice)
markPrice = clamp(rawMark, indexPrice * (1 - clampRatio), indexPrice * (1 + clampRatio))
```

默认 `clampRatio` 是 `0.03`，也就是 3%。

## 多节点协调

指数价格和标记价格 provider 都支持多节点部署。因为它们会主动发布价格，不能只依赖本地内存 sequence：

- `price_symbol_leases` 保存 `module + symbol` 的发布租约。
- `price_symbol_sequences` 保存 `module + symbol` 的全局递增 sequence。
- 节点启动后用 `coordination.node-id` 标识自己，默认取 `HOSTNAME`，没有时使用随机 ID。
- 每次发布前先续租或抢占过期租约；抢不到租约的节点不发布这个 symbol。
- sequence 由 PostgreSQL 原子分配，节点重启或 failover 后不会从 1 重新开始。
- 默认 `lease-duration=15s`。节点宕机后，其他节点最长等待约一个租约周期后接管。

生产建议：

- 每个 provider 至少 2 个实例。
- 多节点共享同一个 PostgreSQL，不要每个节点使用独立数据库。
- `coordination.node-id` 在容器环境建议显式设置为 pod name、hostname 或 instance id。
- `lease-duration` 要大于正常发布周期，通常为 `poll-delay-ms` 或 `publish-interval-ms` 的 5-20 倍。
- 如果 PostgreSQL 不可用，价格服务不应继续发布，因为无法保证 sequence 和 symbol 所有权。

## 关键配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `surprising.price.index.calculation.poll-delay-ms` | `1000` | 指数价格计算周期。 |
| `surprising.price.index.calculation.max-source-age` | `5s` | 外部源 ticker 最大可接受年龄。 |
| `surprising.price.index.calculation.outlier-threshold` | `0.01` | 指数成分偏离中位数超过 1% 时剔除。 |
| `surprising.price.index.calculation.min-valid-sources` | `3` | 每个 symbol 最少有效外部源数量。 |
| `surprising.price.index.web-socket.idle-timeout` | `20s` | 外部 WS 无任何帧超过该时间后重连。 |
| `surprising.price.index.web-socket.reconnect-max-delay` | `30s` | 外部 WS 最大重连退避。 |
| `surprising.price.index.instrument.enabled` | `true` | 从 `surprising-instrument` 数据表动态读取 symbol 和指数源。 |
| `surprising.price.index.instrument.refresh-delay-ms` | `30000` | instrument 快照刷新周期。 |
| `surprising.price.index.instrument.fallback-to-static-symbols` | `true` | instrument 表为空或不可用时使用 YAML 静态 symbol。 |
| `surprising.price.index.coordination.lease-duration` | `15s` | 指数价格 symbol 租约时长。 |
| `surprising.price.index.fiat.refresh-delay-ms` | `3600000` | 法币汇率刷新周期。 |
| `surprising.price.index.fiat.stable-coin.refresh-delay-ms` | `10000` | USDT/USD 稳定币桥接刷新周期。 |
| `surprising.price.mark.kafka.concurrency` | `2` | 每个节点的标记价格输入 Kafka listener 并发数。 |
| `surprising.price.mark.kafka.max-poll-records` | `500` | 标记价格输入 consumer 每次 poll 拉取的 Kafka 记录数。 |
| `surprising.price.mark.calculation.publish-interval-ms` | `1000` | 标记价格固定频率发布周期。 |
| `surprising.price.mark.calculation.basis-window` | `60s` | basis 移动平均窗口。 |
| `surprising.price.mark.calculation.max-input-age` | `5s` | 标记价格输入最大可接受年龄。 |
| `surprising.price.mark.calculation.clamp-ratio` | `0.03` | 标记价格相对指数价格的保护带。 |
| `surprising.price.mark.coordination.lease-duration` | `15s` | 标记价格 symbol 租约时长。 |

## API

指数价格：

```http
GET /api/v1/price/index/latest?symbol=BTC-USDT
GET /api/v1/price/index/history?symbol=BTC-USDT&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

标记价格：

```http
GET /api/v1/price/mark/latest?symbol=BTC-USDT
GET /api/v1/price/mark/history?symbol=BTC-USDT&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

法币汇率：

```http
GET /api/v1/price/fx/latest?baseCurrency=USD&quoteCurrency=CNY
GET /api/v1/price/fx/rates?baseCurrency=USD
GET /api/v1/price/fx/convert?amount=100&fromCurrency=USDT&toCurrency=CNY
```

## 生产注意事项

- 每个 provider 至少部署 2 个实例。
- 所有价格输入和输出 topic 都用 `symbol` 作为 Kafka key。
- 优先使用一个共享 topic + 足够多 partition，不要按每个 symbol 建 topic。
- 风控和强平服务必须消费 `surprising.perp.mark.price.v1`，不要各节点自己计算标记价格。
- 唯一的 `surprising.perp.mark.price.v1` 消息已经包含完整审计信封；审计 consumer 按
  `symbol + sequence` 幂等重试数据库失败，不影响其它 consumer group。
- `price_mark_ticks` 使用普通非分区表和紧凑的时间 BRIN 索引，每分钟分批删除，只保留 3 天。
- 指数价格和标记价格 producer 使用 `acks=all`、幂等、`zstd` 和 `max.in.flight.requests.per.connection=5`。
- 标记价格输入 consumer 有意使用 `auto.offset.reset=latest`，因为新启动的 live mark calculator 不应该把旧输入快照重新计算成当前标记价格；但它仍然关闭 auto commit，使用 record ack 和 cooperative-sticky rebalance。
- 外部源不足时，指数服务会记录失败快照，但不会发布可用指数价格。
- USD 源必须声明 `quote-currency`、`target-quote-currency`、稳定币换算源和换算方向。
- App 法币展示必须走本地 `price_exchange_rates` 缓存，不能每个用户请求实时打第三方 FX API。
- 标记价格输入 topic 必须使用相同的 `symbol` key；建议相关 topic 使用相同 partition 数，降低跨节点输入错位概率。

## 故障排查

- Binance REST 返回 `451`：当前机器所在地区被 Binance 限制。生产要部署在允许访问的区域，或禁用该源/降低权重。
- Bybit 返回 `403`：CloudFront 地区限制或风控拦截。生产要验证采集机房 IP 和 WAF 策略。
- 指数价格不发布：检查有效外部源是否少于 `min-valid-sources`，以及 `price_index_components.reason`。
- WS 频繁重连：检查外部交易所 ping/pong、网络出口、`idle-timeout` 和 `reconnect-max-delay`。
- USD 源权重变低：通常是 USDT/USD 换算源不可用，查看成分 reason 是否包含 `conversion failed`。
- 法币换算 404：检查 `price_exchange_rates` 是否已刷新，或 FX rate 是否超过 `stale-after`。
- 多节点只有一个节点发布某个 symbol：这是租约预期行为；查看 `price_symbol_leases` 的 `owner_id` 和 `lease_until`。
- sequence 不连续：允许出现跳号，例如落库或发布前失败；但同一 `module + symbol` 不应回退。

## 验证

```bash
mvn -pl :surprising-price -am test
mvn -pl :surprising-price -am -DskipTests package
```

外部源快速检查：

```bash
curl 'https://open.er-api.com/v6/latest/USD'
curl 'https://api.exchange.coinbase.com/products/USDT-USD/ticker'
curl 'https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT'
```

## 本地运行

在仓库根目录执行：

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-price-provider -am spring-boot:run
```

需要独立扩容指数价格和标记价格时，仍可拆分进程启动：

```bash
mvn -pl :surprising-index-price-provider -am spring-boot:run
mvn -pl :surprising-mark-price-provider -am spring-boot:run
```

使用合并 provider 时，指数和标记价格 API 都在 `9082`。

查询最新价格：

```bash
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
```

使用 `surprising-price-provider` 时，标记价格也从 `9082` 查询：

```bash
curl 'http://localhost:9082/api/v1/price/mark/latest?symbol=BTC-USDT'
```

## 参考

- OKX 标记价格和移动平均 basis：https://www.okx.com/help/ii-mark-price-and-last-price
- Binance 标记价格移动平均 basis 更新：https://www.binance.com/en/support/announcement/detail/20c5bfbd9e084b6982c768516c316514
- Binance Spot book ticker：https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints
- OKX market ticker：https://www.okx.com/docs-v5/en/#order-book-trading-market-data-get-ticker
- Bybit market tickers：https://bybit-exchange.github.io/docs/v5/market/tickers
- Coinbase product ticker：https://docs.cdp.coinbase.com/exchange/reference/exchangerestapi_getproductticker
- Kraken ticker：https://docs.kraken.com/api/docs/rest-api/get-ticker-information
- ExchangeRate-API free endpoint：https://www.exchangerate-api.com/docs/free
