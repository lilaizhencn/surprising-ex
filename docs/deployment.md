# Deployment

## Kafka Topics

Create the perpetual trade input topic with enough partitions for your active symbols and expected throughput.

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic surprising.instrument.events.v1 \
  --partitions 24 \
  --replication-factor 3

kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic surprising.perp.trade.events.v1 \
  --partitions 24 \
  --replication-factor 3

kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic surprising.perp.candle.events.v1 \
  --partitions 24 \
  --replication-factor 3
```

For local Docker Compose, use replication factor `1`.
The repository script `scripts/create-topics.sh` creates the full topic set, including `surprising.account.position.events.v1` for private position pushes and risk scan triggers, `surprising.risk.account.events.v1` / `surprising.risk.position.events.v1` for backend-calculated private risk/PnL pushes, and `surprising.account.liquidation-fee.events.v1` for actual liquidation-fee credits to the insurance fund.

## Java Runtime

Use JDK 21 for all services. `surprising-matching-provider` uses exchange-core/OpenHFT Chronicle and should run with these Java module flags:

```bash
export JAVA_TOOL_OPTIONS="--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
```

Keep the matching provider on JDK 21 unless exchange-core and Chronicle are revalidated on a newer runtime.

## Multi-Node Rules

- All candlestick provider nodes must use the same `surprising.candlestick.kafka.application-id`.
- Every node must have a unique local `surprising.candlestick.stream.state-dir`.
- Run at least as many Kafka partitions as the desired maximum parallelism.
- Scale by increasing service instances and Kafka partitions, not by creating per-symbol consumers.
- Kafka Streams restores RocksDB state from changelog topics during rebalance or restart.
- Matching command records must use `symbol` as the Kafka key, so all commands for one symbol stay ordered in one partition.
- Matching command, account match-trade, risk position-event, and liquidation-candidate consumers reject records whose Kafka key does not match the payload `symbol`.
- Matching provider nodes share the same `surprising.trading.matching.kafka.group-id`; Kafka assigns each partition to one live matcher.
- Matching restores open order books from PostgreSQL on startup. If a running matcher receives a new partition after processing commands, it closes the Spring context and should be restarted by Kubernetes/systemd.
- Instrument, order, matching, price, risk, liquidation, and funding Kafka producers use `acks=all`, `enable.idempotence=true`, `compression.type=zstd`, and `max.in.flight.requests.per.connection=5`.
- Matching, account, risk, liquidation, and insurance Kafka consumers use `enable.auto.commit=false`, `auto.offset.reset=earliest`, cooperative-sticky assignment, and Spring Kafka `AckMode.RECORD`.
- Tune `surprising.*.kafka.max-poll-records` with consumer lag, processing latency, and database transaction time. Defaults are intentionally conservative for local development.
- Keep `surprising.trading.matching.kafka.restart-on-partition-reassignment=true` in production. Disable it only for local debugging.
- Keep `surprising.trading.matching.kafka.partition-assignment-startup-grace-ms` large enough for concurrent listener containers to finish initial assignment; default is `30000`.
- Avoid high-frequency autoscaling of matching providers. Partition movement causes restart-and-recover by design.
- Account position pushes use `account_outbox_events` and `surprising.account.position.events.v1`; account nodes publish with `acks=all` and idempotent producers, and WebSocket consumers must handle at-least-once delivery.
- Actual liquidation-fee collection also uses `account_outbox_events`; events are published to `surprising.account.liquidation-fee.events.v1` keyed by settlement asset. Insurance-provider consumers must be in one shared group and rely on `insurance_fund_ledger(reference_type, reference_id, asset)` for idempotency.
- Every WebSocket provider node must use a unique `surprising.websocket.kafka.group-id`. Public market data must be consumed by every WebSocket node for local fanout.
- Do not put all WebSocket providers in one shared consumer group. That would split Kafka records across nodes and make clients connected to other nodes miss updates.
- WebSocket nodes keep session/subscription state in process only. Clients must resubscribe after reconnect.
- Gateway providers are stateless. Scale them behind a load balancer and point route `base-url` values at Kubernetes Services or your service-discovery layer.
- Market-maker providers coordinate by `market_maker_strategy_leases(strategy_id, symbol)`. Multiple nodes may run the same config, but only the lease owner quotes a strategy-symbol pair. Keep the provider on an internal network and keep `surprising.market-maker.engine.enabled=false` unless liquidity accounts are funded and risk caps are reviewed.

## Observability

- Scrape every provider's `/actuator/prometheus` endpoint.
- For account settlement latency, monitor `surprising.account.match_trade.processing` and `surprising.account.match_trade.event_lag` with `outcome=processed|duplicate|failed`.
- Monitor `surprising.account.match_trade.events{outcome=duplicate}` separately. A rising duplicate rate usually means Kafka replay, rebalance, or a downstream failure caused at-least-once redelivery; it should not change balances because `(symbol, trade_id)` is the idempotency key.
- Correlate account settlement metrics with Kafka consumer lag on `surprising.perp.match.trades.v1`, PostgreSQL query latency, Hikari pool usage, and `account_outbox_events` publish lag. Increasing `surprising.account.kafka.concurrency` only helps when the topic has enough partitions and load is spread across symbols; one hot symbol remains ordered on one partition.

## PostgreSQL

Initialize the schema from repository root:

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

The root `init.sql` creates:

- `instruments`: immutable product-rule snapshots keyed by `(symbol, version)`.
- `instrument_current_versions`: current version pointer per symbol.
- `instrument_symbol_sequences`: atomic instrument version allocator per symbol.
- `instrument_risk_brackets`: risk tiers keyed by `(symbol, version, bracket_no)`.
- `instrument_index_sources`: index component source config keyed by `(symbol, version, source)`.
- `candlestick_symbols`: optional symbol registry.
- `candlestick_candles`: OHLCV storage keyed by `(symbol, period, open_time)`.
- `price_index_ticks`: index price ticks keyed by `(symbol, sequence)`.
- `price_index_components`: index component audit rows keyed by `(symbol, sequence, source)`.
- `price_symbol_leases`: active publisher ownership keyed by `(module, symbol)`.
- `price_symbol_sequences`: database-allocated price sequence keyed by `(module, symbol)`.
- `price_exchange_rates`: fiat and stable-coin bridge rates keyed by `(base_currency, quote_currency)`.
- `price_mark_ticks`: mark price snapshots keyed by `(symbol, sequence)`, including `mark_price_units` for
  long-unit consumers.
- `funding_rate_ticks`: predicted funding rates keyed by `(symbol, sequence)`.
- `funding_settlements`: idempotent funding settlement batches keyed by `(symbol, funding_time)`.
- `funding_payments`: per-user funding payments keyed by `(settlement_id, user_id)`.
- `trading_orders`: accepted/rejected order state using long ticks and steps.
- `trading_order_events`: order-entry audit events.
- `trading_outbox_events`: order command/event outbox rows.
- `trading_matching_assets` and `trading_matching_symbols`: stable exchange-core asset and symbol ids.
- `trading_match_results` and `trading_match_trades`: idempotent matching output; trades are keyed by `(symbol, trade_id)`.
- `account_margin_reservations`: initial margin reserved by order entry.
- `account_position_margins`: margin migrated into live positions after fills.
- `account_outbox_events`: account-side Kafka outbox rows, used for position update events and actual liquidation-fee settlement events.
- `risk_sequences`: database-allocated risk snapshot and candidate ids.
- `risk_scan_leases`: active risk scanner ownership keyed by `(user_id, settle_asset)`.
- `risk_account_snapshots`: account-level margin snapshots keyed by `snapshot_id`.
- `risk_position_snapshots`: position-level risk snapshots keyed by `(snapshot_id, symbol)`.
- `risk_liquidation_candidates`: liquidation inputs with one live `NEW/PROCESSING` candidate per `(user_id, symbol)`.
- `risk_outbox_events`: liquidation-candidate Kafka outbox rows.
- `insurance_fund_balances`: insurance fund balance keyed by `asset`.
- `insurance_fund_ledger`: idempotent fund movements keyed by `(reference_type, reference_id, asset)`.
- `insurance_deficit_coverages`: account deficit coverage records.
- `adl_events`: auto-deleveraging executions after insurance depletion.

The query API uses fixed table names and parameter binding. It does not concatenate user input into table names.

Recommended production settings:

- Use PostgreSQL 16 or newer.
- Put `candlestick_candles` on fast storage.
- Keep the primary key `(symbol, period, open_time)`.
- For very large history, add native PostgreSQL range partitioning by `open_time` or move closed candles into TimescaleDB hypertables.
- Keep `surprising.candlestick.flush.max-batch-size` between `500` and `5000` depending on DB latency.

## Price Provider Feeds

- Run the index provider with external venue WebSocket enabled for production.
- REST polling is only a cold-start and stale-cache fallback; do not size production around REST for every symbol/source pair.
- Keep `surprising.price.index.web-socket.reconnect-initial-delay` and `reconnect-max-delay` conservative enough to avoid reconnect storms after provider incidents.
- Store customer-facing fiat conversion rates in `price_exchange_rates`; app and gateway requests should query local APIs.
- Use a paid FX provider with SLA in production, and keep the default public endpoint only for development or backup.
- Keep `surprising.price.*.coordination.enabled=true` for multi-node deployments.
- Set `surprising.price.*.coordination.node-id` to a stable pod name, hostname, or instance id.
- Keep `coordination.lease-duration` several times longer than the publish interval. Default is `15s`.
- Index and mark price producers use `acks=all`, idempotence, `zstd`, and bounded in-flight requests.
- Mark-price input consumers use live-feed `latest` startup semantics, plus disabled auto-commit, record ack, cooperative-sticky rebalance, and configurable `surprising.price.mark.kafka.concurrency` / `max-poll-records`.
- Do not publish index or mark prices when PostgreSQL is unavailable; lease and sequence guarantees depend on PostgreSQL.

## Gateway And WebSocket

- Public REST clients should use `surprising-gateway` on `/api/v1/gateway/{service}` instead of calling each provider directly.
- Internal modules still own their APIs. The gateway is an allowlisted edge/BFF proxy, not the source of business logic.
- Private gateway routes require identity (`X-User-Id` or `Authorization` in the current implementation). In production, inject `X-User-Id` only after token/session validation at the auth or ingress layer.
- The gateway accepts `X-Trace-Id`, normalizes it, returns it to the client, and forwards the normalized value to backend providers. If the client omits it, the gateway generates one.
- Do not generate a new trace id inside asynchronous trading stages. Order entry writes the trace id into order events and command payloads; matching copies the command trace id into result/trade events; account settlement copies the trade trace id into position outbox events.
- Include `trace_id`, `order_id`, `command_id`, `trade_id`, Kafka topic/partition/offset, and `symbol` in operational logs and dashboards. Database audit tables `trading_order_events`, `trading_match_results`, and `trading_match_trades` also store `trace_id`.
- Realtime clients should connect to `surprising-websocket` on `/ws/v1`.
- Use REST first for snapshots, then WebSocket for deltas. L2 book snapshots are exposed through `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`.
- WebSocket public channels: `candles`, `trades`, `depth`, `index`, `mark`, `funding`.
- WebSocket private channels: `orders`, `matches`, `positions`, `positionRisk`, `accountRisk`.
- Order book depth uses `SNAPSHOT` and per-price-level `DELTA` events. A delta with `quantitySteps=0` removes the level; clients must reload a snapshot when `previousSequence` is not continuous.
- WebSocket connection state is local memory only: socket, authenticated user id, subscriptions, and outbound queue.
- User-related events are consumed by every WebSocket node, then locally filtered by `userId + channel + symbol`; the node that hosts the matching connection pushes, and other nodes drop the event.
- Position push is downstream of account settlement: account DB transaction -> account outbox -> Kafka `surprising.account.position.events.v1` -> WebSocket fanout.
- Backend-authoritative unrealized PnL, equity, maintenance margin, and margin ratio are pushed through risk-provider outbox events: risk scan transaction -> `risk_outbox_events` -> Kafka `surprising.risk.position.events.v1` / `surprising.risk.account.events.v1` -> WebSocket fanout.
- Open candle updates are coalesced by `surprising.websocket.fanout.candle-partial-coalesce-window`; closed candles are pushed immediately.
- Slow WebSocket clients are closed when their bounded outbound queue is full or sends time out.
- Clients must implement ping, exponential reconnect, and full resubscribe after reconnect.
- Set `surprising.websocket.security.allowed-origins` to exact production HTTPS origins. The wildcard default is for local development only.
- Configure `surprising.gateway.http-client.connect-timeout` and `read-timeout` so unhealthy backends cannot exhaust gateway worker threads.

## Risk Provider Coordination

- Keep `surprising.risk.coordination.enabled=true` when more than one risk-provider instance is running.
- Set `surprising.risk.coordination.node-id` to a stable pod name, hostname, or instance id. The default config uses `HOSTNAME`; if it is empty, the process generates a local random id.
- Keep `surprising.risk.coordination.lease-duration` longer than the scan interval and shorter than your tolerated failover delay. The default is `15s` with a `1s` scan delay.
- Risk consumes `surprising.account.position.events.v1` as a low-latency trigger and also runs keyset-paginated scheduled scans as the fallback. Both paths claim `risk_scan_leases` before calculating and writing each `userId + settleAsset` group.
- Keep `surprising.risk.kafka.group-id` identical across risk-provider nodes. Scale by increasing Kafka partitions and provider nodes; do not create per-symbol consumers.
- Do not let risk-provider write snapshots when PostgreSQL is unavailable. Lease, snapshot id, candidate uniqueness, and outbox guarantees all depend on PostgreSQL.

## Kafka Client Identity

- Set a stable, unique Kafka `client.id` per provider instance for stateful or settlement consumers, especially `surprising.trading.matching.kafka.client-id` and `surprising.account.kafka.client-id`.
- Keep the `group-id` identical across replicas of the same service, but keep `client-id` unique per pod or process. This makes `kafka-consumer-groups --describe` usable for mapping `symbol` partitions to the exact matching/account node during incident response.
- For matching, compute the keyed partition for a symbol and verify its current `client-id` owner before intentionally killing or draining a node. A process that receives new matching partitions after it has already processed commands may exit by design and must be restarted so it can restore open orders from PostgreSQL.

## Deployment Order

1. Start PostgreSQL and Kafka.
2. Apply `init.sql`.
3. Create Kafka topics with `scripts/create-topics.sh`.
4. Start instrument providers.
5. Start candlestick providers.
6. Start index price providers.
7. Start mark price providers after index, book ticker, trade, and funding-rate topics are available.
8. Start funding providers after mark price tables have fresh data.
9. Start order, matching, account, risk, and liquidation providers.
10. Start insurance providers after account tables are initialized and the fund is seeded.
11. Start ADL providers after account, liquidation, and insurance providers.
12. Start WebSocket/fanout services that consume candle, index, mark, order, match, funding, and position topics.
13. Start gateway providers after the internal REST services they expose are reachable.
14. Start market-maker providers last. They should see healthy order, matching, account, mark, and gateway services before `surprising.market-maker.engine.enabled=true` is rolled out.

## Operational Kill Switches

Keep these switches in your centralized configuration system and protect changes with operational approval.
They are designed for incident response, stale-market-data investigations, and controlled rollout pauses.

| Switch | Default | Effect |
| --- | --- | --- |
| `surprising.risk.calculation.enabled` | `true` | Stops scheduled risk scans from reading positions or creating new liquidation candidates. Existing snapshots and candidates remain unchanged. |
| `surprising.liquidation.execution.enabled` | `true` | Rejects liquidation candidate processing before claiming the candidate. Kafka delivery should be retried instead of acknowledging a paused liquidation event. |
| `surprising.funding.calculation.enabled` | `true` | Stops predicted funding-rate publication and its Kafka outbox writes. |
| `surprising.funding.settlement.enabled` | `true` | Stops due funding settlements from applying account balance, margin, ledger, or deficit updates. |
| `surprising.insurance.coverage.enabled` | `true` | Stops insurance deficit coverage scans. Existing `account_deficits` stay untouched. |
| `surprising.adl.scanner.enabled` | `true` | Stops residual-deficit ADL scans. Existing deficits and profitable target positions stay untouched. |

Recommended incident order:

1. Pause liquidation execution before changing risk thresholds or stale mark-price handling.
2. Pause ADL before manual insurance fund top-up or deficit reconciliation.
3. Pause funding settlement before correcting a bad funding-rate tick.
4. Resume scanners one layer at a time and monitor outbox lag, `account_deficits`, and account ledger writes.

## API Smoke Tests

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
curl 'http://localhost:9081/api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9082/api/v1/price/index/latest?symbol=BTC-USDT'
curl 'http://localhost:9082/api/v1/price/fx/convert?amount=1&fromCurrency=USDT&toCurrency=CNY'
curl 'http://localhost:9083/api/v1/price/mark/latest?symbol=BTC-USDT'
curl 'http://localhost:9089/api/v1/funding/rates/latest?symbol=BTC-USDT'
curl 'http://localhost:9090/api/v1/insurance/balances?asset=USDT'
curl 'http://localhost:9091/api/v1/adl/queue?asset=USDT&limit=100'
curl 'http://localhost:9094/api/v1/gateway/candlestick/candles/latest?symbol=BTC-USDT&period=1m'
curl 'http://localhost:9094/api/v1/gateway/account/1001/positions' -H 'X-User-Id: 1001'
```

## Local Integration Smoke

Use this script before pushing schema or core trading changes:

```bash
./scripts/integration-smoke.sh
```

It starts a temporary PostgreSQL instance, applies `init.sql`, then checks:

- existing positions keep their original `instrument_version` even after current product rules move forward;
- linear USDT-margined notional and unrealized PnL are calculated from long ticks/steps/asset units;
- funding payment notional conversion uses the position's pinned instrument version;
- inverse coin-margined notional and PnL are calculated from contract face value and settlement-asset units;
- risk snapshots and liquidation candidates keep the pinned instrument version;
- duplicate active liquidation candidates for the same `user_id + symbol` are ignored.
- insurance coverage reduces explicit `account_deficits` without crediting available balance;
- ADL reduces a profitable target position, releases proportional margin, transfers realized profit, and clears the residual deficit.

Set `RUN_MAVEN=true` to run `mvn -q test` after the database checks. Set `PG_BIN=/path/to/postgresql/bin` if PostgreSQL tools are not on `PATH`.

## Kafka Trading Smoke

When Docker is available, run a real process-level order -> matching -> account smoke. The scripts use `docker compose`, `docker-compose`, or direct `docker run` when only the Docker daemon is available:

```bash
./scripts/kafka-trading-smoke.sh
```

For the heavier process-level trading/WebSocket smoke:

```bash
PAIR_COUNT=50 LOAD_CONCURRENCY=16 ./scripts/kafka-trading-load-smoke.sh
```

This starts order, matching, account, and WebSocket providers, then verifies full fills, partial-fill cancellation, cancel-only, cancel-all, concurrent maker/taker users, account positions, REST order-book snapshots, depth deltas, and private order/match/position pushes.

The basic script:

- starts Docker PostgreSQL and Kafka unless `START_INFRA=false`;
- uses `KAFKA_IMAGE` to choose the direct-Docker Kafka image when compose is unavailable;
- creates an isolated database named `surprising_smoke_<run>` by default, then applies root `init.sql`;
- creates all topics through `scripts/create-topics.sh`;
- packages and starts order, matching, and account providers;
- funds a maker and taker through the account admin REST API;
- submits crossing REST orders, waits for exchange-core matching, and waits for account Kafka settlement;
- republishes the same match-trade payload to verify `(symbol, trade_id)` account idempotency.

Useful options:

```bash
STOP_INFRA=true ./scripts/kafka-trading-smoke.sh
BUILD_SERVICES=false ./scripts/kafka-trading-smoke.sh
DB_NAME=surprising_smoke_manual ./scripts/kafka-trading-smoke.sh
```

Do not point this script at a shared development database. Matching restores open books from PostgreSQL on startup, so stale open orders in a reused database can make recovery fail correctly with a crossed-book error.

## Failure Behavior

- Duplicate trades are dropped by `symbol + tradeId`.
- Old replays are also guarded by per-symbol `sequence`.
- Dirty candle snapshots are stored in a persistent RocksDB state store before PostgreSQL flush.
- PostgreSQL writes are full-snapshot upserts, so retrying the same dirty snapshot is idempotent.
- Perpetual candle update Kafka events are separate from DB persistence. A websocket service should consume the candle topic and maintain its own client fanout.
- Index and mark providers use `price_symbol_leases` so only one live node publishes a given `module + symbol`.
- Index and mark providers use `price_symbol_sequences` so a failover cannot reset sequence numbers.
- Funding providers also use `price_symbol_leases` and `price_symbol_sequences`; settlement is additionally guarded by `funding_settlements(symbol, funding_time)`.
- Risk providers use `risk_scan_leases` so only one live node writes snapshots and candidates for a given `userId + settleAsset`; lease expiry allows another node to take over after the owner dies.
- Market-maker providers use `market_maker_strategy_leases` so only one live node quotes a given `strategyId + symbol`. If the owner dies, lease expiry allows another node to continue. The module places normal `LIMIT + GTX + postOnly` orders through order-provider and never writes matching state directly.
- Funding-rate publication is a rate-row plus outbox transaction. If the funding rate insert or outbox enqueue/mark update is skipped, the provider fails instead of committing a one-sided state change.
- Funding-rate publication and settlement can be paused independently with `surprising.funding.calculation.enabled=false` and `surprising.funding.settlement.enabled=false`.
- Trading, matching, risk, and funding outbox publishers are at-least-once. A Kafka send failure increments `attempts`, records `last_error`, and moves `next_attempt_at` forward with capped exponential backoff; the row remains unpublished and is retried by later scans.
- Order entry rejects market orders when mark price is stale or missing, and enforces min/max notional using the configured mark-derived execution band. Linear market-order max-notional and initial-margin checks use the upper bound for both BUY and SELL; matching still applies the side-specific protected price before exchange-core submission and rejects taker orders that would self-trade.
- Matching startup rebuilds exchange-core order books from DB open `LIMIT` + `GTC/GTX` orders that already have successful `PLACE` results.
- Matching startup fails if recovered orders cross the book. Fix persisted order state before restarting instead of continuing with an inconsistent book.
- Matching closes itself on unsafe Kafka partition reassignment after it has processed commands; orchestration should restart it so the new owner restores a fresh book from DB.
- Matching also closes itself when a decoded command fails during processing. Do not configure the listener to spin on in-place retries; restart the process so Kafka replay runs against a DB-recovered exchange-core book.
- Matching result, trade, order-state, margin-release, and matching-outbox writes are fail-fast. A skipped insert/update means the process must restart and recover from DB before Kafka replay.
- Matching fill persistence is guarded by open order status, `remaining_quantity_steps >= fillQty`, and `quantity_steps = executed_quantity_steps + remaining_quantity_steps`. Do not clamp overfills with `LEAST/GREATEST`; a mismatch means DB state and the exchange-core event stream have diverged.
- `surprising.trading.matching.kafka.restart-on-partition-reassignment` only controls partition-movement restarts. Command-failure restart is always enabled.
- Matching margin release is a guarded balance transition. The update requires `account_balances.locked_units >= releaseUnits`; if that invariant is broken, the matching transaction fails and should be investigated instead of masking the inconsistency by crediting available balance.
- Matching margin release allows a missing `account_margin_reservations` row only for `reduce_only = TRUE` orders. Non-reduce-only orders must have a reservation; missing reservations fail the transaction.
- Order entry inserts `trading_orders` before reserving margin. Only the partial `(user_id, client_order_id)` uniqueness conflict is idempotent; a duplicate `clientOrderId` must return the original order without writing `account_margin_reservations` or changing `account_balances`.
- Account opening fills require an existing non-reduce-only `account_margin_reservations` row. Missing reservations or skipped account margin updates must fail the trade transaction rather than creating uncollateralized positions.
- Account closing fills may skip order-reservation release only for `reduce_only = TRUE` orders. Non-reduce-only close or flip fills must find the original reservation row; otherwise the account transaction fails.
- Account `TRADE_PNL` ledger insert/backfill writes are fail-fast. Duplicate trade delivery is handled before settlement by `account_processed_trades(symbol, trade_id)`; ledger conflicts during a new trade transaction indicate inconsistent state.
- Account maker/taker fees are settled from the side-specific `trading_orders` fee snapshot and written as `TRADE_FEE` with `trade_id`, `order_id`, `symbol`, and `fee_rate_ppm`. Positive ppm rates debit the user; negative ppm rates rebate. Fee ledger insert/backfill writes are fail-fast for the same reason as `TRADE_PNL`.
- PnL and funding losses may reduce `locked_units` only through locked collateral backed by `account_position_margins`; open-order reservation locks must remain intact. If `account_position_margins` exceeds the releasable locked collateral, treat it as an accounting invariant issue.
- Account position event outbox rows are written after `account_positions` is updated. Kafka publishing is at-least-once, so clients and downstream consumers should tolerate duplicate position events by `eventId` or `tradeId`.
- Risk consumes account position events only as scan triggers. It does not trust the event as accounting state; it re-reads positions, balances, deficits, instruments, and mark prices inside the risk transaction before writing snapshots or candidates.
- Funding settlement account ledger, balance, deficit, and settlement completion updates are fail-fast after a `funding_payments` row is inserted.
- Risk snapshot writes, liquidation-candidate outbox enqueue, and outbox publish/failure markers are fail-fast. Only the partial active-candidate `NEW/PROCESSING user_id + symbol + margin_mode` uniqueness conflict may skip a candidate write; candidate-id or snapshot uniqueness conflicts must fail. A successfully inserted candidate must always be readable and enqueued before the transaction commits.
- Risk scan leases are best-effort ownership, not accounting state. A node may take over only when the prior row is owned by itself or `lease_until <= updated_at`; clock synchronization matters for predictable failover.
- Risk scans can be paused with `surprising.risk.calculation.enabled=false`; both the scheduled scanner and position-event trigger return before reading positions or opening a transaction.
- Insurance providers split `account_deficits` rows with `FOR UPDATE SKIP LOCKED` and lock the fund balance row before every deduction.
- Insurance coverage can be paused with `surprising.insurance.coverage.enabled=false`; deficits remain explicit and unchanged.
- If the insurance fund is empty, deficits remain in `account_deficits` and will be retried after the fund is topped up.
- Liquidation providers lock live positions, preempt existing same-side reduce-only close orders, then submit a staged close order sized from the live position.
- Liquidation execution can be paused with `surprising.liquidation.execution.enabled=false`; the service fails before claiming the candidate so Kafka replay or a later risk scan can retry after the pause is lifted.
- Liquidation risk re-checks require a fresh `risk_position_snapshots` row for the candidate `user_id + symbol + margin_mode + instrument_version`. If the latest snapshot is older than `surprising.liquidation.risk.max-snapshot-age` or missing, the candidate is canceled rather than executed.
- Liquidation pre-cancel commands and the liquidation place command must keep the same symbol Kafka key so the matching provider receives them in partition order. `CANCEL_REQUESTED` orders are re-canceled because they may still be live in exchange-core.
- Liquidation order creation is an atomic outbox write. `trading_orders` uniqueness conflicts are not suppressed; if a `trading_orders`, `trading_order_events`, `trading_outbox_events`, or `liquidation_orders` audit write fails or is skipped, the provider fails the transaction instead of marking the candidate completed.
- Liquidation candidate status updates and liquidation outbox publish/failure markers are fail-fast; a missing row means state has diverged and should be investigated before replay continues.
- ADL providers only claim aged deficits when the insurance fund balance for that asset is zero.
- ADL can be paused with `surprising.adl.scanner.enabled=false`; the scanner returns before claiming deficit rows.
- ADL providers re-lock target `account_positions` rows before reducing a position; concurrent nodes skip locked positions.
- ADL position-margin release is guarded by `account_position_margins.margin_units >= releaseUnits`; skipped updates fail the ADL transaction.
- External-source failures are stored in component/audit records; unusable index prices are not published.
- Instrument changes are immutable versions; downstream services replace local cache after reading current snapshots or consuming `surprising.instrument.events.v1`.
- Gateway route names are allowlisted. Unknown services return 404 before any backend call.
- WebSocket private subscriptions are matched against the authenticated user id; a client cannot subscribe to another user's private stream by sending a different `userId`.

## Troubleshooting

- `price_symbol_leases` owner does not move after a node dies: wait until `lease_until`; if it is far in the future, verify node clock synchronization.
- `risk_scan_leases` owner does not move after a risk node dies: wait until `lease_until`, confirm all nodes have synchronized clocks, and verify `surprising.risk.coordination.node-id` is unique per live instance.
- Price sequence has gaps: expected after failed attempts. Investigate only if a sequence moves backwards, which should not happen.
- Index price missing: inspect `price_index_components` for `STALE`, `OUTLIER`, `ERROR`, or conversion failure reasons.
- Binance returns `451` or Bybit returns `403`: collector egress region/IP is blocked by the venue.
- WebSocket reconnect loop: check venue connectivity, ping/pong behavior, idle timeout, and egress firewall.
- Kafka consumer lag: increase topic partitions, provider instances, or stream threads. Do not create one topic per symbol.
- WebSocket clients miss public market data on some pods: verify each WebSocket pod has a unique `surprising.websocket.kafka.group-id`.
- WebSocket private positions not updating: check account outbox lag in `account_outbox_events`, Kafka topic `surprising.account.position.events.v1`, and the client's private `positions` subscription/authenticated user id.
- WebSocket risk/PnL not updating: check risk scan freshness, `risk_outbox_events` pending rows, Kafka topics `surprising.risk.position.events.v1` and `surprising.risk.account.events.v1`, then the client's `positionRisk` / `accountRisk` subscriptions.
- Gateway private routes return 401: verify the auth layer forwards `X-User-Id` or `Authorization`.
- Gateway routes return 404: verify the `{service}` segment is configured under `surprising.gateway.routes`.
- RocksDB restore is slow: check changelog topic retention, local state directory persistence, disk throughput, and container file descriptor limits.
- Market orders rejected with `MARK_PRICE_UNAVAILABLE`: check mark-price freshness and `surprising.trading.*.market-max-mark-age-ms`.
- Limit orders rejected with `mark price unavailable`: check mark-price freshness and `surprising.trading.order.risk.limit-price-max-mark-age-ms`; this protects price-band validation and should not be used to hide a broken mark-price pipeline in production.
- Orders rejected with `SELF_TRADE_PREVENTED`: cancel or wait for the user's marketable opposite-side resting order.
- Matching provider exits after a rebalance: expected when it receives a new partition after processing commands. Check pod restart policy and DB recovery time.
- Matching provider exits while processing a command: expected fail-fast behavior after a decoded command failure. Inspect DB/outbox/Kafka errors, then let orchestration restart and replay from the last committed offset.
- Matching recovery fails with crossed open orders: inspect `trading_orders` and `trading_match_results` for inconsistent open order state on the symbol.
- Insurance coverage is not happening: verify `insurance_fund_balances.balance_units`, positive `account_deficits.deficit_units`, and provider database connectivity.
- ADL is not firing: verify the deficit age, zero insurance fund balance for the asset, `surprising.adl.scanner.max-mark-age-ms`, fresh mark prices, and profitable opposing queue candidates.

## Trading Integration Checklist

1. Produce one Kafka record per executed trade.
2. Use `symbol` as the Kafka key.
3. Ensure all trades for one symbol are sent to the same Kafka topic with monotonic `sequence`.
4. Include `tradeId`, `sequence`, `tradeTime`, `price`, `quantity`, and real `side`.
5. Do not aggregate trades before this service unless the aggregated trade has its own unique id and sequence.

## Dynamic Symbols

Default behavior:

```yaml
surprising:
  candlestick:
    symbols:
      accept-unknown-symbols: false
      source: INSTRUMENT
```

With this mode, a new trading pair starts producing candles after `surprising-instrument` exposes it
in the current `instruments` snapshot. The index price provider also reads current symbols and index
sources from `instruments + instrument_index_sources`.

Legacy registry fallback:

```yaml
surprising:
  candlestick:
    symbols:
      accept-unknown-symbols: false
      source: CANDLESTICK_SYMBOLS
      refresh-delay-ms: 30000
```

Then insert or update the symbol registry:

```sql
INSERT INTO candlestick_symbols(symbol, base_asset, quote_asset, enabled)
VALUES ('BTC-USDT', 'BTC', 'USDT', TRUE)
ON CONFLICT (symbol) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now();
```

## API

Range query:

```http
GET /api/v1/candlestick/candles?symbol=BTC-USDT&period=1m&startTime=2026-06-30T10:00:00Z&endTime=2026-06-30T11:00:00Z&limit=500
```

Latest candle:

```http
GET /api/v1/candlestick/candles/latest?symbol=BTC-USDT&period=1m
```

Supported periods are configured by:

```yaml
surprising:
  candlestick:
    periods: [ "1m", "5m", "15m", "30m", "1h", "4h", "1d" ]
```
