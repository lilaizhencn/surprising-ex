# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Multi-product exchange backend services for Surprising.

This repository is the root reactor for exchange backend modules. It currently supports product-line-isolated spot, USDT perpetual, USDT delivery futures, and European vanilla options flows while keeping one shared service architecture. Each business module keeps its own detailed README and deployment notes.

## Project Deep Dive

To understand the project architecture and implementation details, read the Surprising-EX source code and product-line articles:

- Source deep dive: [English](https://tokdou.com/en/tutorials/surprising-ex-perpetual-contract-exchange-source-code-deep-dive) / [简体中文](https://tokdou.com/tutorials/surprising-ex-perpetual-contract-exchange-source-code-deep-dive)
- Four product-line architecture: [English](https://tokdou.com/en/tutorials/surprising-ex-four-product-line-architecture-okx-binance-comparison) / [简体中文](https://tokdou.com/tutorials/surprising-ex-four-product-line-architecture-okx-binance-comparison)
- Delivery futures and options beginner guide: [English](https://tokdou.com/en/tutorials/delivery-contract-and-options-beginner-guide) / [简体中文](https://tokdou.com/tutorials/delivery-contract-and-options-beginner-guide)

## Modules

- `surprising-parent`: shared parent POM with centralized dependency and plugin version management.
- `surprising-product-api`: shared product-line enum, account-type mapping, and product-topic naming.
- `surprising-instrument`: instrument configuration and product-rule center for spot, perpetual, delivery, and option instruments.
- `surprising-candlestick`: product-line-aware candlestick service.
- `surprising-price`: index price and mark price services for derivative product lines.
- `surprising-trading`: order entry, trigger orders, algo orders, product-line Kafka routing, and exchange-core matching.
- `surprising-account`: account balances, ledgers, product balances, positions, margin, spot settlement, derivative settlement, delivery, and option exercise accounting.
- `surprising-margin-ops`: risk snapshots, liquidation candidates, liquidation, funding, insurance, and ADL APIs/providers, plus a combined deployable provider.
- `surprising-edge`: frontend access layer. It contains the REST gateway module, the WebSocket fanout module, and a combined edge provider for development and small deployments.
- `surprising-market-maker`: internal market-maker quoting and exchange-chain stress strategy service.

## Supported Product Lines

| Product line | `ProductLine` | Account type | Contract type | Funding | Lifecycle |
| --- | --- | --- | --- | --- | --- |
| Spot | `SPOT` | `SPOT` | `SPOT` | No | Immediate asset exchange, no position |
| USDT perpetual | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `LINEAR_PERPETUAL` | Yes | No expiry |
| USDT delivery futures | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `LINEAR_DELIVERY` | No | Cash delivery at expiry |
| European vanilla option | `OPTION` | `OPTION` | `VANILLA_OPTION` | No | Cash exercise at expiry |

`INVERSE_PERPETUAL` and `INVERSE_DELIVERY` are represented in shared product-line APIs and topic mapping, but the current process-level smoke suite focuses on the four product lines above.

## Module Documentation

- [Docs index](docs/README.md)
- [Deployment guide](docs/deployment.md)
- [Database design](docs/database.md)
- [Product-line split and delivery/options plan](docs/product-line-split-plan.md)
- [Product-line funds conservation and account reconciliation](docs/product-line-testing-and-funds-reconciliation.md)
- [Full-chain test checklist](docs/full-chain-test-plan_CN.md)
- [Four product-line funds and performance report](docs/full-chain-funds-performance-report.md)
- [Matching symbol sharding and capacity notes](docs/matching-symbol-sharding-and-capacity_CN.md)
- [Perpetual contract tutorial and implementation notes](docs/perpetual-contract-tutorial_CN.md)
- [surprising-candlestick](surprising-candlestick/README.md)
- [surprising-instrument](surprising-instrument/README.md)
- [surprising-price](surprising-price/README.md)
- [surprising-trading](surprising-trading/README.md)
- [surprising-account](surprising-account/README.md)
- [surprising-margin-ops](surprising-margin-ops/README.md)
- [surprising-edge](surprising-edge/README.md)
- [surprising-edge/surprising-websocket](surprising-edge/surprising-websocket/README.md)
- [surprising-edge/surprising-gateway](surprising-edge/surprising-gateway/README.md)
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

Provider packaging keeps the normal jar as a dependency artifact and attaches the executable Spring Boot jar with the `exec` classifier, for example `surprising-trading-entry-provider-1.0.0-SNAPSHOT-exec.jar`.

## Database Initialization

```bash
brew services start postgresql@18
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

This is a new project, so all initial schema is kept in root [init.sql](init.sql). Flyway is not used.
Local integration tests use Homebrew PostgreSQL/Kafka on `localhost:5432` and `localhost:9092`; see [docs/local-homebrew-infra.md](docs/local-homebrew-infra.md).

## Local Startup Order

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
mvn -pl :surprising-candlestick-provider -am spring-boot:run
mvn -pl :surprising-price-provider -am spring-boot:run
mvn -pl :surprising-trading-entry-provider -am spring-boot:run
JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" \
mvn -pl :surprising-matching-provider -am spring-boot:run
mvn -pl :surprising-account-provider -am spring-boot:run
mvn -pl :surprising-margin-ops-provider -am spring-boot:run
mvn -pl :surprising-edge-provider -am spring-boot:run
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```

Ports:

- `9080`: instrument configuration service.
- `9081`: candlestick service.
- `9082`: combined price service for index price, mark price, and FX; in split mode this is the index price service.
- `9083`: mark price service in split mode.
- `9084`: trading-entry combined service for order entry and trigger orders.
- `9085`: exchange-core matching service.
- `9086`: account and position service.
- `9087`: risk service in split mode.
- `9088`: margin-ops combined service, or liquidation execution service in split mode.
- `9089`: funding service in split mode.
- `9090`: insurance fund service in split mode.
- `9091`: ADL service in split mode.
- `9093`: client WebSocket fanout service in split mode.
- `9094`: edge combined service for public REST API gateway and `/ws/v1`, or standalone REST gateway in split mode.
- `9095`: trigger order service in split mode.
- `9096`: internal market-maker service.

## Kafka Topics

- `surprising.instrument.events.v1`: instrument configuration change events.
- `surprising.perp.candle.events.v1`: candlestick snapshot output.
- Legacy perpetual topics remain available for backward-compatible single-line startup. Product-line instances use `surprising.<product-segment>.*.v1`, for example `surprising.spot.order.commands.v1`, `surprising.linear-perp.match.trades.v1`, `surprising.linear-delivery.delivery.settlements.v1`, and `surprising.option.option.exercises.v1`.
- `surprising.perp.order.commands.v1`: legacy perpetual order matching commands.
- `surprising.perp.order.events.v1`: order entry events.
- `surprising.perp.trigger-order.events.v1`: transactional TP/SL status snapshots for authenticated private WebSocket fanout.
- `surprising.perp.match.results.v1`: matching result events.
- `surprising.perp.match.trades.v1`: matching trade events with long fixed-point values, consumed by candlestick and public trade fanout.
- `surprising.perp.orderbook.depth.v1`: exchange-core L2 order book `SNAPSHOT`/`DELTA` events for frontend depth fanout.
- `surprising.account.position.events.v1`: account position update events for private WebSocket fanout.
- `surprising.perp.liquidation.candidates.v1`: liquidation candidate events.
- `surprising.perp.index.price.v1`: index price output.
- `surprising.perp.book.ticker.v1`: perpetual best bid/ask input.
- `surprising.perp.funding.rate.v1`: funding rate output consumed by mark price.
- `surprising.perp.mark.price.v1`: single mark-price output containing the business result and complete audit inputs.

All market-data topics use `symbol` as the Kafka key.

## API Smoke Checks

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9082/api/v1/price/mark/latest?symbol=BTC-USDT'
curl -X POST 'http://localhost:9084/api/v1/trading/orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientOrderId":"cli-1001-1","symbol":"BTC-USDT","side":"BUY","orderType":"LIMIT","timeInForce":"GTC","priceTicks":650000,"quantitySteps":10,"reduceOnly":false,"postOnly":false}'
curl -X POST 'http://localhost:9084/api/v1/trading/trigger-orders' -H 'Content-Type: application/json' -d '{"userId":1001,"clientTriggerOrderId":"tp-1001-1","ocoGroupId":"bracket-1001-1","symbol":"BTC-USDT","side":"SELL","triggerType":"TAKE_PROFIT","triggerPriceTicks":700000,"orderType":"MARKET","timeInForce":"IOC","priceTicks":0,"quantitySteps":10,"marginMode":"CROSS"}'
curl 'http://localhost:9088/api/v1/funding/rates/latest?symbol=BTC-USDT'
curl 'http://localhost:9088/api/v1/insurance/balances?asset=USDT'
curl 'http://localhost:9088/api/v1/adl/queue?asset=USDT&limit=100'
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
```

Frontend traffic should normally enter through `surprising-edge` on port `9094`: REST uses `/api/v1/gateway/**`, and realtime traffic uses `/ws/v1`.
For production deployments with many long-lived WebSocket connections, keep `surprising-gateway-provider` and `surprising-websocket-provider` split so WebSocket fanout can scale independently.

## Local Integration Smoke

Run a database-level smoke test with a temporary PostgreSQL instance:

```bash
./scripts/integration-smoke.sh
```

The script applies [init.sql](init.sql), verifies instrument version pinning, linear and inverse perpetual calculations, funding notional conversion, risk snapshots, liquidation candidate idempotency, insurance deficit coverage, and ADL deficit transfer accounting. Set `RUN_MAVEN=true` to also run unit tests in the same pass.

Run one process-level product-line API flow at a time:

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

The product-line smoke starts only the current line's required providers, keeps the market maker running, simulates API order flow from ordinary users, covers position creation, user close, liquidation, risk, matching, funding where applicable, delivery/exercise where applicable, and then runs `scripts/product-line-funds-reconcile.sh`. The reconciliation compares opening balance, adjustments, trades, fees, funding, liquidation fees, delivery/exercise ledgers, and final balance exactly in integer units.

Run the real Kafka/PostgreSQL process-level trading smoke:

```bash
./scripts/kafka-trading-smoke.sh
```

It creates an isolated smoke database, starts order/matching/account providers, places crossing REST orders, waits for exchange-core matching and account Kafka settlement, then replays the same match-trade payload to verify account idempotency.
For full product-line user-flow, market-maker, liquidation/lifecycle, and funds reconciliation coverage, use `scripts/product-line-api-flow-smoke.sh`.

Market-depth clients should initialize from the public REST snapshot, then apply WebSocket deltas:

```bash
curl 'http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50'
```

## Production Notes

- Run at least two instances of each provider.
- Use Kafka topic replication factor `3` in production.
- Instrument is the single source for symbols and trading rules.
- Product-line instances must use product-specific Kafka topics, consumer groups, matching client ids, price/risk/funding coordination ids, and gateway routes. See [docs/deployment.md](docs/deployment.md).
- Trading order entry uses long fixed-point values: `priceTicks`, `quantitySteps`, and asset `units` align with exchange-core and avoid BigDecimal on the core order, matching, account, risk, liquidation, funding, insurance, and ADL paths. Decimal values are only allowed at external market-data/FX parsing, admin input, display, and reporting boundaries.
- Matching uses the real `exchange-core` order book to consume order commands and emit long-based matching results and trade events.
- Matching restores open `LIMIT` + `GTC/GTX` books from PostgreSQL on startup and restarts on unsafe Kafka partition reassignment so failover uses a fresh DB recovery.
- Matching also exits on decoded command processing failure, so Kafka replay happens after DB order-book recovery instead of retrying against a possibly mutated in-memory book.
- Matching requires sufficient `locked_units` when releasing reserved order margin; insufficient locked balance fails fast and triggers recovery instead of silently crediting available balance.
- Market orders use fresh mark-price execution-band notional checks at order entry, then matching submits the side-specific protected price to exchange-core. Linear contracts reserve market-order initial margin at the upper band for both BUY and SELL so a market SELL can safely execute against higher resting bids. Matching rejects self-trading taker orders.
- Ordinary order amend uses order-provider cancel-replace semantics for open LIMIT orders; replacement orders get a new `newClientOrderId` and run the same validation/fund reservation path while original-order release follows cancel matching/account settlement.
- TWAP and Iceberg algo orders are order-provider parent orders outside the live book. Their scheduled child orders are ordinary order-provider orders, so fills still pass through exchange-core matching, account settlement, risk, liquidation checks, and WebSocket fanout. Active algo orders block margin-mode and position-mode switches until canceled or completed.
- Take-profit, stop-loss, and trailing stop are conditional trigger orders driven by the Kafka mark-price event. The shared consumer validates product line, fixed-point ticks, event/publish timestamps, and a 3-second freshness limit. Trigger-provider evaluates only the newest fresh sample per symbol once per second and never re-reads the audit table. Due rows submit an idempotent `reduceOnly=true` close order through order-provider with `clientOrderId=trigger-<triggerOrderId>`. Trailing stop uses integer `callbackRatePpm` (`1000` = `0.1%`, `100000` = `10%`) plus optional `activationPriceTicks` and tracks the sampled post-activation high/low watermark.
- Static TP/SL always uses a Spring Data Redis + Lettuce sorted-set candidate index. Redis narrows price-range candidates; PostgreSQL still performs the exact price/status/expiry/OCO claim and remains the only authoritative state. User open-order queries also remain database-authoritative. Cancel, expiry, OCO cancellation, successful execution, rejected execution, and transaction rollback remove or reconcile index members; unavailable/not-ready Redis falls back to the existing database claim path for already committed orders.
- Paired TP/SL can share `ocoGroupId`; when one pending trigger in the same user/symbol/margin group is claimed, pending siblings are canceled in the same database statement before the generated close order is submitted.
- When a normal close, liquidation fill, delivery settlement, or option exercise reduces a position to zero, account settlement cancels its remaining `PENDING` trigger orders and writes their `CANCELED` status snapshots to the trading outbox in the same PostgreSQL transaction. The committed position event makes trigger-provider remove the corresponding static TP/SL Redis members, while `trigger-order.events` drives authenticated WebSocket refresh; `GET /open` immediately follows the authoritative canceled database state.
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
- Every mark-price Kafka event carries product line, instrument version, fixed-point units/ticks,
  calculation time, and publication time. Its audit envelope contains every calculation input and is
  batch-written asynchronously to PostgreSQL for three days; business consumers never wait for that insert.
- Order entry uses PostgreSQL idempotency keys, atomic sequences, and outbox `FOR UPDATE SKIP LOCKED` for multi-node deployment.
- Trigger-provider uses a Redis Lua range lookup as an optional prefilter and PostgreSQL `FOR UPDATE SKIP LOCKED` as the final multi-node claim, plus Kafka price replay and stale `TRIGGERING` reset for retry after downstream order-provider failures. A token-owned Redis lease prevents duplicate index rebuild work but is not a correctness lock. The provider does not mutate balances or positions directly.
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
- Insurance absorbs `account_deficits`; ADL handles residual deficits when insurance is depleted. The current consolidated evidence is the four product-line report: each line ran with 20 active symbols, 20 maker accounts, 2000 taker users, continuous market making, and `funds-reconcile` finished with `Violations=0`. Production rollout still needs longer multi-node/multi-broker stress testing, monitoring thresholds, real-cluster kill-switch drills, and multi-process Kafka evidence.
- WebSocket fanout should be a separate service consuming Kafka output topics, not part of the calculators.
- Every WebSocket node uses a unique Kafka consumer group so public market data reaches all nodes for local client fanout; do not share one WebSocket group across pods.
- Account position pushes are emitted from the account transactional outbox after settlement, then consumed by WebSocket and filtered by authenticated user subscription.
- The public REST gateway is stateless and allowlisted. Business modules still keep their internal APIs; frontend/BFF clients should use the gateway instead of configuring every module endpoint independently.
- External venue market data should use WebSocket as the primary path and REST only for cold start and fallback.
- Fiat FX is for display and valuation hints; it must not replace contract index or mark price risk logic.

## Next-Phase Plan: Production-Grade Multi-Node Matching

The matching service already has the first-stage multi-node foundation: order commands are keyed by `symbol`, Kafka assigns each partition to one live consumer in a shared matching group, and a restarted node rebuilds open order books from PostgreSQL. The next phase will harden this model into an explicitly managed matching cluster. It will preserve the single-writer rule for each symbol; multiple nodes may own different symbol shards, but must never concurrently mutate the same order book.

### Phase 1: Productionize the Existing Partition Model

- Keep each product line isolated with its own order-command topic, consumer group, client identities, instruments, accounts, risk model, and downstream topics.
- Pre-allocate enough Kafka partitions for the expected matching parallelism. Do not increase a live keyed topic's partition count without a product-line maintenance, migration, and replay plan, because it can change `symbol -> partition` placement.
- Deploy multiple matching providers with the same product-line `group-id` and a stable unique `client-id` per node. Keep `restart-on-partition-reassignment=true`, use controlled rolling updates and a PodDisruptionBudget, and avoid high-frequency autoscaling.
- Restore only the symbols owned by the node's assigned partitions instead of loading every open order book into every process. Readiness must remain false until assignment, recovery, and consistency checks finish.
- Add operational visibility for `symbol -> partition -> client-id`, consumer lag, command P99 latency, recovery duration, open-order count, outbox backlog, settlement latency, restart causes, and reconciliation failures.
- Add multi-broker and multi-node failure drills covering graceful drain, hard node loss, Kafka rebalance, database interruption, command replay, and outbox replay.

### Phase 2: Explicit Shards and Fast Failover

- Introduce versioned `symbol -> matching shard` metadata rather than relying only on Kafka's default key hash. Hot symbols may receive dedicated shards while cold symbols share shards.
- Add a monotonically increasing shard ownership epoch/fencing token. Every matching state transition, checkpoint, and shard lease must reject writes from an expired owner so a delayed old node cannot become a second writer.
- Persist an ordered matching command journal, periodic order-book snapshots, and Kafka offset/checkpoint metadata. Recovery should load the latest verified snapshot and replay only commands after its checkpoint.
- Support shard-level drain, handoff, recovery, and health state so partition movement does not require rebuilding every book or restarting the whole JVM.
- If recovery-time objectives require it, add leader/follower replication per shard; only the fenced leader may emit authoritative match results, trades, depth sequences, or settlement events.
- Keep a single symbol serial within one shard. Adding nodes improves aggregate throughput across symbols; it does not make multiple nodes concurrently match one BTC-USDT book.

### Acceptance Gates

- Run one product line at a time with market making kept online, using simulated user APIs for place, cancel, match, position open/close, liquidation, risk events, and public/private WebSocket flows.
- Prove deterministic recovery: before and after each failover, compare open orders, price-time priority, positions, balances, depth sequence continuity, persisted checkpoints, and Kafka offsets.
- Reconcile user and market-maker funds item by item: opening balance, adjustments, trades, fees, funding, liquidation fees, delivery/exercise ledgers where applicable, and closing balance must balance exactly.
- Validate product-specific boundaries: perpetual funding/mark price/liquidation/ADL/insurance, delivery expiry settlement, option premium/exercise/expiry, and spot asset freeze/debit/release.
- Require sustained hotspot-symbol stress tests and repeated node-kill/rebalance tests with no double match, lost command, duplicate settlement, crossed recovered book, stale-owner write, or reconciliation violation before production rollout.

## Documentation

- [Deployment](docs/deployment.md)
- [Database design](docs/database.md)
- [Docs index](docs/README.md)
- [Four product-line funds and performance report](docs/full-chain-funds-performance-report.md)
