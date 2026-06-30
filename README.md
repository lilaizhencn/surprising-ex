# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Exchange backend services for Surprising.

This repository is the root reactor for exchange backend modules. Each business module keeps its own detailed README and deployment notes.

## Modules

- `surprising-dependencies`: centralized dependency versions copied from `surprising-wallet`.
- `surprising-parent`: shared parent POM copied from `surprising-wallet`.
- `surprising-candlestick`: perpetual candlestick service.
- `surprising-price`: perpetual index price and mark price services.

## Module Documentation

- [surprising-candlestick](surprising-candlestick/README.md)
- [surprising-price](surprising-price/README.md)

## Build

```bash
mvn test
mvn -DskipTests package
```

## Database Initialization

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

This is a new project, so all initial schema is kept in root [init.sql](init.sql). Flyway is not used.

## Local Startup Order

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-candlestick-provider -am spring-boot:run
mvn -pl :surprising-index-price-provider -am spring-boot:run
mvn -pl :surprising-mark-price-provider -am spring-boot:run
```

Ports:

- `9081`: candlestick service.
- `9082`: index price and FX service.
- `9083`: mark price service.

## Kafka Topics

- `surprising.perp.trade.events.v1`: perpetual trade input.
- `surprising.perp.candle.events.v1`: candlestick snapshot output.
- `surprising.perp.index.price.v1`: index price output.
- `surprising.perp.index.components.v1`: index component audit output.
- `surprising.perp.book.ticker.v1`: perpetual best bid/ask input.
- `surprising.perp.funding.rate.v1`: funding rate input.
- `surprising.perp.mark.price.v1`: mark price output.
- `surprising.perp.mark.price.audit.v1`: mark price audit output.

All market-data topics use `symbol` as the Kafka key.

## API Smoke Checks

```bash
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
```

## Production Notes

- Run at least two instances of each provider.
- Use Kafka topic replication factor `3` in production.
- Candlestick state scales with Kafka Streams partitions and local RocksDB state stores.
- Index and mark price providers use PostgreSQL symbol leases and database sequences to avoid duplicate multi-node publishing and sequence rollback.
- WebSocket fanout should be a separate service consuming Kafka output topics, not part of the calculators.
- External venue market data should use WebSocket as the primary path and REST only for cold start and fallback.
- Fiat FX is for display and valuation hints; it must not replace contract index or mark price risk logic.

## Documentation

- [Deployment](docs/deployment.md)
- [Database design](docs/database.md)
