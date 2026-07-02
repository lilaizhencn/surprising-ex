# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Exchange backend services for Surprising.

This repository is the root reactor for exchange backend modules. Each business module keeps its own detailed README and deployment notes.

## Modules

- `surprising-dependencies`: centralized dependency versions copied from `surprising-wallet`.
- `surprising-parent`: shared parent POM copied from `surprising-wallet`.
- `surprising-instrument`: perpetual instrument configuration and product-rule center.
- `surprising-candlestick`: perpetual candlestick service.
- `surprising-price`: perpetual index price and mark price services.
- `surprising-trading`: perpetual order entry, trigger orders, and exchange-core matching.
- `surprising-account`: account balances, ledger, and perpetual positions.
- `surprising-risk`: margin ratio, risk snapshots, and liquidation candidate service.
- `surprising-liquidation`: liquidation candidate executor and reduce-only close order service.
- `surprising-funding`: perpetual funding-rate publishing and settlement service.
- `surprising-insurance`: insurance fund and bankruptcy deficit coverage service.
- `surprising-adl`: auto-deleveraging service for residual deficits after insurance is depleted.
- `surprising-websocket`: horizontally scalable client WebSocket fanout for market data, orders, matches, and positions.
- `surprising-gateway`: allowlisted public REST gateway for frontend/BFF traffic.
- `surprising-market-maker`: internal market-maker quoting and exchange-chain stress strategy service.
- `surprising-integration-test`: cross-module verification for order, matching, account, risk, liquidation, funding, insurance, and ADL flows.

## Module Documentation

- [surprising-candlestick](surprising-candlestick/README.md)
- [surprising-instrument](surprising-instrument/README.md)
- [surprising-price](surprising-price/README.md)
- [surprising-trading](surprising-trading/README.md)
- [surprising-account](surprising-account/README.md)
- [surprising-risk](surprising-risk/README.md)
- [surprising-liquidation](surprising-liquidation/README.md)
- [surprising-funding](surprising-funding/README.md)
- [surprising-insurance](surprising-insurance/README.md)
- [surprising-adl](surprising-adl/README.md)
- [surprising-websocket](surprising-websocket/README.md)
- [surprising-gateway](surprising-gateway/README.md)
- [surprising-market-maker](surprising-market-maker/README.md)

## Build

Use JDK 21. The matching provider depends on exchange-core/OpenHFT Chronicle and should be run with the Java module flags shown below.

```bash
mvn test
mvn -DskipTests package
```

```bash
export JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
```

Provider packaging keeps the normal jar as a dependency artifact and attaches the executable Spring Boot jar with the `exec` classifier, for example `surprising-order-provider-1.0.0-SNAPSHOT-exec.jar`.

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
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-candlestick-provider -am spring-boot:run
mvn -pl :surprising-index-price-provider -am spring-boot:run
mvn -pl :surprising-mark-price-provider -am spring-boot:run
mvn -pl :surprising-order-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
mvn -pl :surprising-account-provider -am spring-boot:run
mvn -pl :surprising-risk-provider -am spring-boot:run
mvn -pl :surprising-liquidation-provider -am spring-boot:run
mvn -pl :surprising-funding-provider -am spring-boot:run
mvn -pl :surprising-insurance-provider -am spring-boot:run
mvn -pl :surprising-adl-provider -am spring-boot:run
mvn -pl :surprising-websocket-provider -am spring-boot:run
mvn -pl :surprising-trigger-provider -am spring-boot:run
mvn -pl :surprising-gateway-provider -am spring-boot:run
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```

Ports:

- `9080`: instrument configuration service.
- `9081`: candlestick service.
- `9082`: index price and FX service.
- `9083`: mark price service.
- `9084`: order entry service.
- `9085`: exchange-core matching service.
- `9086`: account and position service.
- `9087`: risk service.
- `9088`: liquidation execution service.
- `9089`: funding service.
- `9090`: insurance fund service.
- `9091`: ADL service.
- `9093`: client WebSocket fanout service.
- `9094`: public REST API gateway.
- `9095`: take-profit and stop-loss trigger order service.
- `9096`: internal market-maker service.

## Kafka Topics

- `surprising.instrument.events.v1`: instrument configuration change events.
- `surprising.perp.trade.events.v1`: perpetual trade input.
- `surprising.perp.candle.events.v1`: candlestick snapshot output.
- `surprising.perp.order.commands.v1`: order matching commands.
- `surprising.perp.order.events.v1`: order entry events.
- `surprising.perp.match.results.v1`: matching result events.
- `surprising.perp.match.trades.v1`: matching trade events with long fixed-point values.
- `surprising.perp.orderbook.depth.v1`: exchange-core L2 order book `SNAPSHOT`/`DELTA` events for frontend depth fanout.
- `surprising.account.position.events.v1`: account position update events for private WebSocket fanout.
- `surprising.perp.liquidation.candidates.v1`: liquidation candidate events.
- `surprising.perp.index.price.v1`: index price output.
- `surprising.perp.index.components.v1`: index component audit output.
- `surprising.perp.book.ticker.v1`: perpetual best bid/ask input.
- `surprising.perp.funding.rate.v1`: funding rate output consumed by mark price.
- `surprising.perp.mark.price.v1`: mark price output.
- `surprising.perp.mark.price.audit.v1`: mark price audit output.

All market-data topics use `symbol` as the Kafka key.

## API Smoke Checks

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
curl -X POST 'http://localhost:9084/api/v1/trading/orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientOrderId":"cli-1001-1","symbol":"BTC-USDT","side":"BUY","orderType":"LIMIT","timeInForce":"GTC","priceTicks":650000,"quantitySteps":10,"reduceOnly":false,"postOnly":false}'
curl -X POST 'http://localhost:9095/api/v1/trading/trigger-orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientTriggerOrderId":"tp-1001-1","ocoGroupId":"bracket-1001-1","symbol":"BTC-USDT","side":"SELL","triggerType":"TAKE_PROFIT","triggerPriceType":"MARK_PRICE","triggerPriceTicks":700000,"orderType":"MARKET","timeInForce":"IOC","priceTicks":0,"quantitySteps":10,"marginMode":"CROSS"}'
curl 'http://localhost:9089/api/v1/funding/rates/latest?symbol=BTC-USDT'
curl 'http://localhost:9090/api/v1/insurance/balances?asset=USDT'
curl 'http://localhost:9091/api/v1/adl/queue?asset=USDT&limit=100'
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
```

Frontend REST traffic should normally enter through `surprising-gateway` on port `9094`.
Realtime traffic should use `surprising-websocket` on `/ws/v1`; public channels include candles/trades/depth/index/mark/funding, and private channels include orders/matches/positions.

## Local Integration Smoke

Run a database-level smoke test with a temporary PostgreSQL instance:

```bash
./scripts/integration-smoke.sh
mvn -pl :surprising-integration-test -am test
```

The script applies [init.sql](init.sql), verifies instrument version pinning, linear and inverse perpetual calculations, funding notional conversion, risk snapshots, liquidation candidate idempotency, insurance deficit coverage, and ADL deficit transfer accounting. Set `RUN_MAVEN=true` to also run unit tests in the same pass.
The integration-test module runs real exchange-core with in-memory Kafka/DB adapters and verifies order entry, matching, account settlement, linear and inverse user reduce-only close, risk candidate creation, liquidation order generation, liquidation close settlement, funding settlement, insurance coverage, and ADL residual-deficit transfer in Java chains.

When Docker is available, run the real Kafka/PostgreSQL process-level trading smoke:

```bash
./scripts/kafka-trading-smoke.sh
```

It creates an isolated smoke database, starts order/matching/account providers, places crossing REST orders, waits for exchange-core matching and account Kafka settlement, then replays the same match-trade payload to verify account idempotency.
For a heavier process-level run that also starts WebSocket and checks full fill, partial fill plus cancel, cancel-only, cancel-all, concurrent users, position correctness, and depth/private push reception:

```bash
PAIR_COUNT=50 LOAD_CONCURRENCY=16 ./scripts/kafka-trading-load-smoke.sh
```

This script uses `docker compose`, `docker-compose`, or direct `docker run` when only the Docker daemon is available. `PAIR_COUNT` controls how many concurrent maker/taker user pairs are submitted in the load section. Set `KAFKA_IMAGE` to reuse a locally available Kafka image.

Market-depth clients should initialize from the public REST snapshot, then apply WebSocket deltas:

```bash
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
```

## Production Notes

- Run at least two instances of each provider.
- Use Kafka topic replication factor `3` in production.
- Instrument is the single source for symbols and trading rules.
- Trading order entry uses long fixed-point values: `priceTicks`, `quantitySteps`, and asset `units` align with exchange-core and avoid BigDecimal on the core order, matching, account, risk, liquidation, funding, insurance, and ADL paths. Decimal values are only allowed at external market-data/FX parsing, admin input, display, and reporting boundaries.
- Matching uses the real `exchange-core` order book to consume order commands and emit long-based matching results and trade events.
- Matching restores open `LIMIT` + `GTC/GTX` books from PostgreSQL on startup and restarts on unsafe Kafka partition reassignment so failover uses a fresh DB recovery.
- Matching also exits on decoded command processing failure, so Kafka replay happens after DB order-book recovery instead of retrying against a possibly mutated in-memory book.
- Matching requires sufficient `locked_units` when releasing reserved order margin; insufficient locked balance fails fast and triggers recovery instead of silently crediting available balance.
- Market orders use fresh mark-price execution-band notional checks at order entry, then matching submits the side-specific protected price to exchange-core. Linear contracts reserve market-order initial margin at the upper band for both BUY and SELL so a market SELL can safely execute against higher resting bids. Matching rejects self-trading taker orders.
- Take-profit and stop-loss are conditional trigger orders. They stay in `trading_trigger_orders` until a mark-price event crosses the trigger price; then `surprising-trigger-provider` submits an idempotent `reduceOnly=true` close order through order-provider with `clientOrderId=trigger-<triggerOrderId>`.
- Paired TP/SL can share `ocoGroupId`; when one pending trigger in the same user/symbol/margin group is claimed, pending siblings are canceled in the same database statement before the generated close order is submitted.
- Account consumes matching trades, updates long-based net positions idempotently by `tradeId`, migrates filled opening margin into position margin, and settles realized PnL into balances.
- `CROSS` and `ISOLATED` margin modes are carried from order entry through matching, account, risk, funding, and liquidation. Cross losses may use cross available balance and cross position collateral; isolated losses only use that symbol's isolated position collateral before recording a deficit.
- Isolated position margin can be manually added or removed through account-provider. Additions move available balance into position collateral; removals require a fresh risk snapshot and must leave equity above maintenance margin plus the configured buffer.
- User leverage settings are keyed by `userId + symbol + marginMode`; order entry re-checks the configured leverage against the current risk bracket before reserving initial margin.
- Automatic VIP fee tiers are calculated in order-provider from 30-day filled notional plus account asset value and written back as user-global `VIP` schedules. Manual risk/user/promotion/market-maker schedules keep higher source priority.
- Account and funding loss settlement only debit position-margin-backed locked collateral; open-order reservation locks are not consumed by PnL or funding charges.
- Risk uses mark price, instrument risk parameters, and account positions/balances to produce cross account snapshots, isolated position snapshots, and liquidation candidates.
- Risk providers coordinate scans with PostgreSQL `risk_scan_leases` per `userId + settleAsset`, so multi-node deployments do not duplicate snapshot/candidate writes for the same risk group.
- Liquidation consumes candidates, re-checks risk, pre-cancels same-side user reduce-only close orders, and creates staged reduce-only market close orders from the full live position through the unified order/matching path.
- Liquidation treats stale or missing risk snapshots as non-liquidatable; `surprising.liquidation.risk.max-snapshot-age` defaults to `5s`.
- Liquidation order creation is fail-fast: skipped trading order, order event, or audit inserts roll back the candidate transaction.
- Risk scans, liquidation execution, funding-rate publication, funding settlement, insurance coverage, and ADL scanners all have explicit `*.enabled` operational switches for incident pauses.
- Funding calculates long ppm funding rates from mark/index premium plus instrument interest/cap/floor, then settles due funding payments into account balances.
- Insurance covers explicit account deficits with long asset units and writes both fund ledger and account ledger records.
- ADL handles residual deficits after insurance depletion by reducing profitable high-priority positions and transferring realized profit to deficit coverage.
- Candlestick state scales with Kafka Streams partitions and local RocksDB state stores.
- Index and mark price providers use PostgreSQL symbol leases and database sequences to avoid duplicate multi-node publishing and sequence rollback.
- Order entry uses PostgreSQL idempotency keys, atomic sequences, and outbox `FOR UPDATE SKIP LOCKED` for multi-node deployment.
- Trigger-provider uses PostgreSQL `FOR UPDATE SKIP LOCKED` to claim due TP/SL orders, Kafka mark-price replay for trigger detection, and stale `TRIGGERING` reset for retry after downstream order-provider failures. It does not mutate balances or positions directly.
- Matching is wired to real exchange-core order books and uses long ticks/steps end to end.
- Matching command topics must be keyed by `symbol`; scale matching with Kafka partitions and controlled instance count, not one thread per symbol.
- Matching publishes L2 order book depth as `SNAPSHOT` then per-price-level absolute-state `DELTA` events on `surprising.perp.orderbook.depth.v1`. A delta level with `quantitySteps=0` deletes that price level. Clients should load `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`, then apply WebSocket deltas whose `previousSequence` matches the local sequence; on gaps or reconnects, discard the local book and reload a fresh snapshot before applying deltas again.
- REST clients may send `X-Trace-Id`; the gateway and order provider normalize or generate it, and trading events carry it through order events, matching results, match trades, and account position pushes. Matching-engine events must keep this field because they are the bridge between the HTTP request, Kafka replay, database audit rows, and WebSocket/private-account updates.
- Trading-chain Kafka producers use idempotent `acks=all` publishing; Kafka consumers use auto-commit disabled, cooperative-sticky assignment, and record-level acknowledgements so failed records replay through idempotent DB state transitions.
- Consumers reject records when the Kafka key does not match the payload `symbol`, because wrong keys break per-symbol ordering guarantees.
- Outbox publishing is at-least-once with capped exponential retry after Kafka send failures; downstream state transitions stay idempotent.
- Order entry reserves initial margin; account processing moves filled opening margin into position margin and releases old position margin on closing trades.
- User-initiated close orders are protected by reduce-only validation before entering the matching command path.
- Risk detects liquidation candidates; liquidation converts candidates into staged reduce-only close orders and is not blocked by existing user reduce-only orders.
- Insurance absorbs `account_deficits`; ADL handles residual deficits when insurance is depleted. Production rollout still needs stress testing, monitoring thresholds, real-cluster kill-switch drills, and multi-process Kafka evidence.
- WebSocket fanout should be a separate service consuming Kafka output topics, not part of the calculators.
- Every WebSocket node uses a unique Kafka consumer group so public market data reaches all nodes for local client fanout; do not share one WebSocket group across pods.
- Account position pushes are emitted from the account transactional outbox after settlement, then consumed by WebSocket and filtered by authenticated user subscription.
- The public REST gateway is stateless and allowlisted. Business modules still keep their internal APIs; frontend/BFF clients should use the gateway instead of configuring every module endpoint independently.
- External venue market data should use WebSocket as the primary path and REST only for cold start and fallback.
- Fiat FX is for display and valuation hints; it must not replace contract index or mark price risk logic.

## Documentation

- [Deployment](docs/deployment.md)
- [Database design](docs/database.md)
- [Integration verification report](docs/integration-report.md)
