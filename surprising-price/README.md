# surprising-price

[English](README.md) | [简体中文](README_CN.md)

Perpetual index price and mark price module for Surprising Exchange.

## Modules

- `surprising-price-api`: shared RPC contracts and Kafka event models.
- `surprising-index-price-provider`: external spot-source collector and index price calculator.
- `surprising-mark-price-provider`: mark price calculator for risk, liquidation, account equity, and WebSocket display.
- `surprising-price-provider`: combined deployable jar for index and mark providers.

## Architecture

```text
External spot venues
  -> surprising-index-price-provider
  -> surprising.perp.index.price.v1
  -> surprising-mark-price-provider
  -> surprising.perp.mark.price.v1
  -> risk / liquidation / account / websocket

Fiat FX source + USDT/USD stable-coin ticker
  -> surprising-index-price-provider
  -> PostgreSQL price_exchange_rates
  -> app / api-gateway local-fiat display
```

The module is intentionally separate from candlestick aggregation. Index and mark prices are risk
inputs, while candlesticks are market-data history. Keeping them separate avoids coupling risk
calculation to K-line query or WebSocket fanout load.

External price collection and display APIs keep decimal prices. The real-time boundary for trading,
risk, liquidation, funding settlement, and ADL is the committed Kafka `MarkPriceEvent`, which carries
the product line, instrument version, quote-asset units, comparable ticks, sequence, and timestamps.
`price_mark_ticks` is only the asynchronous three-day audit store and is never a business input.

The symbol universe and trading rules come from `surprising-instrument`. The index provider reads
current symbols and external index sources dynamically from `instruments + instrument_index_sources`;
static BTC/ETH YAML sources are only a fallback before database initialization.

## Combined Provider Deployment

`surprising-price-provider` starts the existing index and mark provider components in one JVM for
small development, single-node, or low-cost deployments. This is only a packaging merge:

- Index business logic remains under `com.surprising.price.index`.
- Mark business logic remains under `com.surprising.price.mark`.
- Mark price still consumes the configured index-price Kafka topic and does not read index-provider
  in-memory variables directly.
- The durable boundary remains PostgreSQL plus Kafka topics, so split deployment can be restored
  without changing the calculation code.

The combined jar defaults to port `9082`, serving both `/api/v1/price/index` and
`/api/v1/price/mark`. When using it, point mark-price clients and gateway mark routes to the same
base URL, for example `GATEWAY_ROUTE_PRICE_MARK_BASE_URL=http://localhost:9082`.

## Index Price

The index price provider uses external venue WebSocket tickers by default, normalizes prices,
removes stale or outlier sources, re-normalizes weights, and publishes one fair index price per
symbol. REST is only for cold start, stale WebSocket cache fallback, and manual troubleshooting.

Default external sources:

- Binance Spot
- OKX Spot
- Bybit Spot
- Coinbase Exchange
- Kraken Spot

Formula:

```text
sourceMid = (bestBid + bestAsk) / 2
validSources = enabled && fresh && notOutlier
indexPrice = Σ(sourceMid_i * normalizedWeight_i)
```

Outlier handling:

- Compute the median price from currently healthy sources.
- Reject a source when `abs(sourcePrice - median) / median > outlier-threshold`.
- Default `outlier-threshold` is `0.01`, or 1%.
- Default minimum valid sources is `3`.

Index topics:

```text
surprising.perp.index.price.v1
surprising.perp.index.components.v1
```

The components topic carries source snapshots and reject reasons for audit.

## WebSocket And REST

REST rate is acceptable for small tests or fallback only. If REST is the primary path, request volume
is roughly:

```text
requests_per_second = symbol_count * source_count / poll_interval_seconds
```

For 500 symbols, 5 external sources, and a 1 second refresh interval, that becomes 2500 req/s. Most
venues will not tolerate that reliably, and regional or rate-limit controls can interrupt the feed.
Production therefore uses WebSocket as the primary path:

- Connections are shared by `websocket-url`; one venue connection can send multiple subscriptions.
- Incoming tickers update an in-memory latest-price cache, and the scheduled index task reads that cache first.
- If the cache is older than `max-source-age`, the service temporarily falls back to REST.
- Handshake failure, close frames, listener errors, or no frames for longer than `idle-timeout` all trigger reconnect.
- Reconnect uses exponential backoff with jitter, defaulting from `1s` to `30s`, to avoid reconnect storms.
- This WebSocket layer only collects external spot venues. Internal business WebSocket fanout should consume Kafka index and mark topics, not couple to the calculator process.

## USD/USDT And Fiat FX

Index prices are for USDT perpetual contracts. External components can be USDT pairs or USD pairs
such as Coinbase. USD sources are converted to USDT terms before participating in the index:

```text
BTC-USDT price from Coinbase = BTC-USD / USDT-USD
```

The default stable conversion source is Coinbase `USDT-USD`, with `conversion-operation: DIVIDE`.
If the stable conversion source is unavailable, Coinbase is not blindly treated as full-weight USDT;
its configured weight is discounted by `fallback-weight-multiplier`. Stricter risk policies can set
`conversion-mode: DISABLE`.

Customer-country fiat display is separate from risk pricing. The service periodically pulls USD-based
fiat FX rates into `price_exchange_rates`; frontends, gateways, and account display services query
local APIs instead of calling third-party FX providers per user request.

Default development configuration:

- Fiat FX source: `https://open.er-api.com/v6/latest/USD`
- Stable bridge: Coinbase `USDT-USD`
- Example conversion route: `USDT -> USD -> CNY`

Production recommendations:

- Use a paid primary FX provider with SLA, such as Open Exchange Rates, Currencylayer, OANDA, Xignite, or Refinitiv.
- Keep a secondary provider, such as ECB/Frankfurter or another commercial provider.
- Use fiat FX for display, reporting, and valuation hints; do not replace contract index or mark price risk logic with display FX.
- Store provider, update time, and rate version for regulated local-currency display audit.

FX APIs:

```bash
curl 'http://localhost:9082/api/v1/price/fx/latest?baseCurrency=USD&quoteCurrency=CNY'
curl 'http://localhost:9082/api/v1/price/fx/rates?baseCurrency=USD'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=100&fromCurrency=USDT&toCurrency=CNY'
```

## Mark Price

The mark price provider consumes:

```text
surprising.perp.index.price.v1
surprising.perp.book.ticker.v1
surprising.perp.trade.events.v1
surprising.perp.funding.rate.v1
```

It publishes one complete event:

```text
surprising.perp.mark.price.v1
```

The event contains a compact `result` used by real-time consumers plus the exact index, book,
trade, funding, and basis inputs used by the calculation. A separate consumer group persists the
same record asynchronously for audit; there is no second audit topic to synchronize.
Input listeners only replace their latest in-memory sample. The fixed one-second scheduler is the
only path that calculates and publishes a mark price; input-event arrival never triggers publication.

The calculation follows the common OKX/Binance/Bybit pattern:

```text
basis = (bestBid + bestAsk) / 2 - indexPrice
basisAverage = movingAverage(basis, 60s)

price1 = indexPrice * (1 + fundingRate * timeUntilFunding / fundingInterval)
price2 = indexPrice + basisAverage
rawMark = median(price1, price2, lastTradePrice)
markPrice = clamp(rawMark, indexPrice * (1 - clampRatio), indexPrice * (1 + clampRatio))
```

Default `clampRatio` is `0.03`, or 3%.

## Multi-Node Coordination

Both index and mark providers support multi-node deployment. Because they actively publish prices,
they cannot rely on local in-memory sequences only:

- `price_symbol_leases` stores the publishing lease for `module + symbol`.
- `price_symbol_sequences` stores the global monotonic sequence for `module + symbol`.
- Each node identifies itself with `coordination.node-id`, defaulting to `HOSTNAME`, then a random ID.
- Before publishing, a node renews its lease or takes over an expired lease. A node that cannot acquire the lease does not publish that symbol.
- Sequences are allocated atomically by PostgreSQL, so restart or failover does not reset a symbol to sequence `1`.
- Default `lease-duration` is `15s`. If a node dies, another node takes over after roughly one lease window.

Production recommendations:

- Run at least two instances of each provider.
- All nodes must share the same PostgreSQL database.
- Set `coordination.node-id` explicitly to pod name, hostname, or instance id in containers.
- Keep `lease-duration` greater than the normal publish period, typically 5-20x `poll-delay-ms` or `publish-interval-ms`.
- If PostgreSQL is unavailable, price providers should not continue publishing because sequence and symbol ownership cannot be guaranteed.

## Key Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `surprising.price.index.calculation.poll-delay-ms` | `1000` | Index calculation interval. |
| `surprising.price.index.calculation.max-source-age` | `5s` | Maximum acceptable external ticker age. |
| `surprising.price.index.calculation.outlier-threshold` | `0.01` | Reject a component when it is more than 1% away from median. |
| `surprising.price.index.calculation.min-valid-sources` | `3` | Minimum valid external sources per symbol. |
| `surprising.price.index.web-socket.idle-timeout` | `20s` | Reconnect when no external WS frame arrives within this window. |
| `surprising.price.index.web-socket.reconnect-max-delay` | `30s` | Maximum external WS reconnect backoff. |
| `surprising.price.index.instrument.enabled` | `true` | Dynamically read symbols and index sources from `surprising-instrument` tables. |
| `surprising.price.index.instrument.refresh-delay-ms` | `30000` | Instrument snapshot refresh interval. |
| `surprising.price.index.instrument.fallback-to-static-symbols` | `true` | Use static YAML symbols when instrument tables are empty or unavailable. |
| `surprising.price.index.coordination.lease-duration` | `15s` | Index symbol lease duration. |
| `surprising.price.index.fiat.refresh-delay-ms` | `3600000` | Fiat FX refresh interval. |
| `surprising.price.index.fiat.stable-coin.refresh-delay-ms` | `10000` | USDT/USD stable bridge refresh interval. |
| `surprising.price.mark.kafka.concurrency` | `2` | Mark input Kafka listener concurrency per node. |
| `surprising.price.mark.kafka.max-poll-records` | `500` | Kafka records fetched per poll for mark-price inputs. |
| `surprising.price.mark.calculation.publish-interval-ms` | `1000` | Fixed-rate mark price publish interval. |
| `surprising.price.mark.calculation.basis-window` | `60s` | Moving-average basis window. |
| `surprising.price.mark.calculation.max-input-age` | `5s` | Maximum acceptable mark input age. |
| `surprising.price.mark.calculation.clamp-ratio` | `0.03` | Mark protection band around index price. |
| `surprising.price.mark.coordination.lease-duration` | `15s` | Mark symbol lease duration. |

## API

Index price:

```http
GET /api/v1/price/index/latest?symbol=BTC-USDT
GET /api/v1/price/index/history?symbol=BTC-USDT&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

Mark price:

```http
GET /api/v1/price/mark/latest?symbol=BTC-USDT
GET /api/v1/price/mark/history?symbol=BTC-USDT&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

Fiat FX:

```http
GET /api/v1/price/fx/latest?baseCurrency=USD&quoteCurrency=CNY
GET /api/v1/price/fx/rates?baseCurrency=USD
GET /api/v1/price/fx/convert?amount=100&fromCurrency=USDT&toCurrency=CNY
```

## Production Notes

- Run at least two instances of each provider.
- Use Kafka key `symbol` for all price input and output topics.
- Strongly prefer one shared topic with enough partitions over one topic per symbol.
- Risk and liquidation services must consume `surprising.perp.mark.price.v1`; they should not calculate mark price independently.
- The one `surprising.perp.mark.price.v1` record includes the complete audit envelope. The idempotent
  audit consumer retries database failures by `symbol + sequence` without affecting other consumer groups.
- `price_mark_ticks` is an ordinary, non-partitioned table. Bounded minute-level deletes and a compact
  BRIN time index retain only three days.
- Index and mark price producers use `acks=all`, idempotence, `zstd`, and `max.in.flight.requests.per.connection=5`.
- Mark-price input consumers intentionally use `auto.offset.reset=latest` because stale input snapshots should not be replayed into a live mark calculator after a fresh start. They still use disabled auto-commit, record acknowledgements, and cooperative-sticky rebalance.
- If external sources are insufficient, index provider records the failed snapshot but does not publish a usable index price.
- USD sources must declare `quote-currency`, `target-quote-currency`, stable conversion source, and conversion direction.
- App fiat display must use the local `price_exchange_rates` cache, not third-party FX API calls per user request.
- Mark input topics must use the same `symbol` key. Keep related topics at the same partition count when possible to reduce cross-node input skew.

## Troubleshooting

- Binance REST returns `451`: the current host is in a restricted region. Deploy collectors in an allowed region, or disable/lower this source.
- Bybit returns `403`: CloudFront region or WAF restriction. Validate collector egress IPs before production.
- Index price is not published: check whether valid sources are below `min-valid-sources`, and inspect `price_index_components.reason`.
- WebSocket reconnects frequently: check venue ping/pong behavior, egress network, `idle-timeout`, and `reconnect-max-delay`.
- USD source weight is reduced: USDT/USD conversion is likely unavailable; inspect component reason for `conversion failed`.
- Fiat conversion returns 404: check whether `price_exchange_rates` has refreshed or whether the rate is older than `stale-after`.
- Only one node publishes a symbol: expected lease behavior. Check `price_symbol_leases.owner_id` and `lease_until`.
- Sequence gaps: allowed after failures before publish or save; sequence must not go backwards for the same `module + symbol`.

## Verification

```bash
mvn -pl :surprising-price -am test
mvn -pl :surprising-price -am -DskipTests package
```

External-source smoke checks:

```bash
curl 'https://open.er-api.com/v6/latest/USD'
curl 'https://api.exchange.coinbase.com/products/USDT-USD/ticker'
curl 'https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT'
```

## Local Run

Run from repository root:

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-price-provider -am spring-boot:run
```

Split processes remain available when index and mark price need independent scaling:

```bash
mvn -pl :surprising-index-price-provider -am spring-boot:run
mvn -pl :surprising-mark-price-provider -am spring-boot:run
```

With the combined provider, both index and mark APIs are served on `9082`.

Query latest prices:

```bash
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
```

With `surprising-price-provider`, query mark price on `9082`:

```bash
curl 'http://localhost:9082/api/v1/price/mark/latest?symbol=BTC-USDT'
```

## References

- OKX mark price and moving-average basis: https://www.okx.com/help/ii-mark-price-and-last-price
- Binance mark price moving average basis update: https://www.binance.com/en/support/announcement/detail/20c5bfbd9e084b6982c768516c316514
- Binance Spot book ticker: https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints
- OKX market ticker: https://www.okx.com/docs-v5/en/#order-book-trading-market-data-get-ticker
- Bybit market tickers: https://bybit-exchange.github.io/docs/v5/market/tickers
- Coinbase product ticker: https://docs.cdp.coinbase.com/exchange/reference/exchangerestapi_getproductticker
- Kraken ticker: https://docs.kraken.com/api/docs/rest-api/get-ticker-information
- ExchangeRate-API free endpoint: https://www.exchangerate-api.com/docs/free
