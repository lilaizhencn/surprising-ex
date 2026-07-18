# Deployment

## Kafka Topics

Use the repository script for topic creation. It is product-line aware and creates only the requested
product-line topics when `PRODUCT_LINES` is provided:

```bash
PRODUCT_LINES=LINEAR_PERPETUAL \
INCLUDE_SHARED_TOPICS=true \
INCLUDE_LEGACY_PERP_TOPICS=false \
./scripts/create-topics.sh
```

Without `PRODUCT_LINES`, `scripts/create-topics.sh` creates the full product-topic set. Shared topics
include `surprising.instrument.events.v1`, `surprising.account.position.events.v1`,
`surprising.risk.account.events.v1`, `surprising.risk.position.events.v1`, and
`surprising.account.liquidation-fee.events.v1`.

Topic creation is idempotent; it does not require deleting and recreating topics on every run. Local
test scripts may use `RESET_KAFKA=true` to remove stale local topics/offsets, but do not use topic
delete/recreate as a shared-environment or production data-clearing strategy. For clean tests on a
shared Kafka cluster, use isolated topic names or consumer groups and short retention on dedicated
test topics.

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
- Matching command, risk position-event, and liquidation-candidate consumers reject records whose
  Kafka key does not match the payload `symbol`. Account commands instead require
  `<PRODUCT_LINE>:<userId>` and are always routed to product-scoped topics.
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

## Trigger Redis Index

Static TP/SL price-range indexing always uses Spring Data Redis with Lettuce and has no feature flag. Configure
every trigger-provider or combined trading-entry node for one product line with the same Redis
deployment and key prefix:

```bash
export REDIS_HOST=redis.internal
export REDIS_PORT=6379
export REDIS_PASSWORD='replace-with-secret-manager-value'
export REDIS_DATABASE=0
export TRIGGER_REDIS_KEY_PREFIX=surprising:trigger:v1
```

- Use product-topic routing and one configured product line per provider. ZSET keys include product line, symbol,
  price source, and a Redis Cluster hash tag; TP/SL state never crosses product lines.
- Use `maxmemory-policy noeviction` (preferred) or a volatile-only eviction policy, and deploy Redis with durable
  failover. An all-keys eviction policy can remove a range ZSET while leaving the readiness marker and is therefore
  not allowed for this index. A Redis restart that loses both data and marker is safe because providers fall back
  to PostgreSQL and rebuild before marking the index ready again.
- Do not enable Spring Data Redis global transaction support. Candidate reads and two-direction removal use Lua;
  PostgreSQL and Redis are deliberately not treated as an XA transaction.
- New static TP/SL placement is fail-closed if its Redis member cannot be written.
  Existing committed orders fall back to PostgreSQL claims when the readiness marker or Redis lookup is unavailable.
- The rebuild lease uses `SET NX` with a TTL and token-checked Lua release. Do not use that lease as the trigger
  execution lock; PostgreSQL conditional state transitions and `FOR UPDATE SKIP LOCKED` remain the final guard.
- Keep Redis command timeout short relative to the price-event cadence. Monitor Redis latency/errors together with
  trigger DB fallback rate, stale candidate cleanup, `TRIGGERING` age, and Kafka consumer lag before widening rollout.

## Position Redis Read Model

Account-provider exposes user position endpoints from Redis only. Configure every account-provider instance for one
product line and the same Redis deployment:

```bash
export REDIS_HOST=redis.internal
export REDIS_PORT=6379
export REDIS_PASSWORD='replace-with-secret-manager-value'
export REDIS_DATABASE=0
export POSITION_REDIS_KEY_PREFIX=surprising:position:v1
```

- Keep `maxmemory-policy noeviction` and Redis persistence enabled. A missing readiness marker makes user position
  reads return HTTP 503; do not add a PostgreSQL fallback, because a partial Redis loss must not look like a zero
  position.
- Account-provider collects position and collateral mutations per local transaction and creates exactly one
  revisioned `POSITION_CACHE_PROJECTED` outbox row for each distinct final position key. Account outbox publishes
  product-scoped topics such as
  `surprising.linear-perp.account.position-cache.events.v1`; the cache consumer validates topic, product line, and
  `productLine:userId` Kafka key before running its Lua compare-and-set.
- The three user hashes share one Cluster tag: `state`, `margin`, and `revision` under
  `surprising:position:v1:{PRODUCT_LINE:userId}`. Never manually edit one of the three hashes.
- Startup uses a Redis token lease and a paged PostgreSQL rebuild. Revision CAS permits Kafka replay and rebuild to
  overlap safely. The ready marker is refreshed periodically and one page is reconciled each cycle.
- Do not enable Spring Redis global transaction support and do not attempt XA. PostgreSQL business rows plus the
  outbox are the atomic write; Redis is a replayable read projection. Account writes additionally offer the exact
  committed snapshot to a bounded, coalescing after-commit worker. It never queries PostgreSQL or Redis on the
  Kafka command thread; outbox/Kafka remains the recovery guarantee.
- Monitor `surprising.account.position.cache.applied`, `.stale`, `.failures`, `.rebuild.rows`,
  `.accelerator.submitted`, `.accelerator.coalesced`, `.accelerator.dropped`, the `positionCache`
  health contributor, account outbox backlog, cache-topic lag, and Redis command latency/errors.

The complete rationale, data model, recovery flow, and pre-production reset guidance are in
[position-redis-cache.md](position-redis-cache.md) and [position-redis-cache_CN.md](position-redis-cache_CN.md).

## Product-Line Provider Instances

The backend keeps one shared codebase and runs product-line instances by configuration. Use product topic routing for separated order, match, account, risk, liquidation, mark price, candlestick, WebSocket, and funding traffic.

Local multi-product startup:

```bash
mvn -q -DskipTests package

PRODUCT_LINE=LINEAR_PERPETUAL PORT_OFFSET=0 ./scripts/start-product-line-providers.sh
PRODUCT_LINE=LINEAR_DELIVERY PORT_OFFSET=100 ./scripts/start-product-line-providers.sh
PRODUCT_LINE=OPTION PORT_OFFSET=200 ./scripts/start-product-line-providers.sh

PRODUCT_LINE=OPTION PORT_OFFSET=200 ACTION=stop ./scripts/start-product-line-providers.sh
```

`PORT_OFFSET` is added to each provider's default port. For example, price/trading-entry/matching/account/margin-ops/edge use `9082/9084/9085/9086/9088/9094` for `LINEAR_PERPETUAL`, `9182/9184/9185/9186/9188/9194` for `LINEAR_DELIVERY`, and `9282/9284/9285/9286/9288/9294` for `OPTION`. Standalone split-mode mark price, risk, funding, insurance, ADL, WebSocket, gateway, and trigger providers keep their own base ports.

The startup script sets product-line Kafka topic routing, product-specific consumer groups, unique client ids, and unique coordination node ids where a provider needs them. Price, margin-ops, and funding-only work are skipped automatically when the product line does not need them. Use `BUILD_SERVICES=true` when jars are not already built, `SERVICES="trading-entry matching account"` for a subset, and `WAIT_HEALTH=false` for CI environments where Actuator health is not exposed.

For Kubernetes, systemd, or another process manager, use the same environment pattern instead of the script:

- Set `SURPRISING_*_KAFKA_PRODUCT_LINE` to one of `SPOT`, `LINEAR_PERPETUAL`, `INVERSE_PERPETUAL`, `LINEAR_DELIVERY`, `INVERSE_DELIVERY`, or `OPTION`.
- Set `SURPRISING_*_KAFKA_PRODUCT_TOPICS_ENABLED=true`.
- Keep product-line consumer groups separate when each line owns its own processing state, for example `surprising-account-option-v1` and `surprising-account-linear-delivery-v1`.
- Keep Kafka Streams state directories, matching client ids, and price/risk/funding coordination node ids unique per pod or process.
- Keep instrument-provider shared unless there is an operational reason to split it. Instrument APIs are product-line aware through `productLine` query parameters and `X-Product-Line` headers.

Configure gateway product routes after starting product-line provider instances. The variable names follow `GATEWAY_ROUTE_{SERVICE}_{PRODUCT_LINE}_BASE_URL`. Common service prefixes are `CANDLESTICK`, `PRICE_INDEX`, `PRICE_MARK`, `TRADING`, `TRADING_MARKET`, `TRADING_TRIGGER`, `ACCOUNT`, `RISK`, `LIQUIDATION`, `FUNDING`, `INSURANCE`, `ADL`, and `MARKET_MAKER`.

Example local routes for the three startup commands above:

```bash
export GATEWAY_ROUTE_TRADING_LINEAR_DELIVERY_BASE_URL=http://localhost:9184
export GATEWAY_ROUTE_TRADING_MARKET_LINEAR_DELIVERY_BASE_URL=http://localhost:9185
export GATEWAY_ROUTE_ACCOUNT_LINEAR_DELIVERY_BASE_URL=http://localhost:9186
export GATEWAY_ROUTE_RISK_LINEAR_DELIVERY_BASE_URL=http://localhost:9187
export GATEWAY_ROUTE_LIQUIDATION_LINEAR_DELIVERY_BASE_URL=http://localhost:9188
export GATEWAY_ROUTE_PRICE_MARK_LINEAR_DELIVERY_BASE_URL=http://localhost:9183
export GATEWAY_ROUTE_CANDLESTICK_LINEAR_DELIVERY_BASE_URL=http://localhost:9181

export GATEWAY_ROUTE_TRADING_OPTION_BASE_URL=http://localhost:9284
export GATEWAY_ROUTE_TRADING_MARKET_OPTION_BASE_URL=http://localhost:9285
export GATEWAY_ROUTE_ACCOUNT_OPTION_BASE_URL=http://localhost:9286
export GATEWAY_ROUTE_RISK_OPTION_BASE_URL=http://localhost:9287
export GATEWAY_ROUTE_LIQUIDATION_OPTION_BASE_URL=http://localhost:9288
export GATEWAY_ROUTE_PRICE_MARK_OPTION_BASE_URL=http://localhost:9283
export GATEWAY_ROUTE_CANDLESTICK_OPTION_BASE_URL=http://localhost:9281
```

Client deployment must point every client to the gateway and WebSocket edge, not to product providers directly:

- `surprising-ex-web`: set `VITE_API_BASE_URL` and `VITE_WS_BASE_URL`.
- `surprising-admin-web`: set `VITE_GATEWAY_BASE_URL`.
- `surprising-client`: build with `--dart-define=SURPRISING_GATEWAY_URL=...` and `--dart-define=SURPRISING_WEBSOCKET_URL=...`.

All three clients send `productLine` on REST requests and WebSocket subscription payloads. When a client switches from perpetual to delivery, option, or spot, use a full REST refresh and WebSocket resubscribe so stale product-line market data cannot be reused.
Web and mobile derivative clients also subscribe to the authenticated `triggerOrders` channel, apply full trigger-order snapshots by monotonically increasing `eventId`, remove terminal rows immediately, and reload the REST open-trigger snapshot after every private WebSocket reconnect.

## Observability

- Scrape every provider's `/actuator/prometheus` endpoint.
- For account settlement latency, monitor `surprising.account.command.processing` and `surprising.account.command.event_lag` with `outcome=processed|duplicate|failed`.
- Monitor `surprising.account.command.events{outcome=duplicate}` separately. A rising duplicate rate usually means Kafka replay, rebalance, or a downstream failure caused at-least-once redelivery; financial commands remain idempotent.
- Correlate account settlement metrics with Kafka consumer lag on `surprising.<product-segment>.account.user.commands.v1`, PostgreSQL query latency, Hikari pool usage, and `account_outbox_events` publish lag. The public `match.trades.v1` lag is a market-data health signal only and must not be used as a settlement signal.

## PostgreSQL

Initialize the schema from repository root:

```bash
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

The account-provider baseline runs 32 user-command consumers with a 40-connection Hikari pool. The
eight-connection reserve is for outbox publishing, reconciliation, and request traffic; a pool smaller
than the active command concurrency turns the connection pool into the throughput bottleneck. Tune
`ACCOUNT_USER_COMMAND_CONCURRENCY`, `ACCOUNT_DB_MAX_POOL_SIZE`, and `ACCOUNT_DB_MIN_IDLE` together.

For multiple product lines or replicas, put PgBouncer in transaction-pooling mode in front of PostgreSQL
and set `ACCOUNT_DB_URL` to the PgBouncer endpoint. Budget the database side globally rather than multiplying
40 by every pod: the sum of PgBouncer database pools must remain within PostgreSQL `max_connections` after
reserving admin, migration, and monitoring connections. Do not rely on session state in this mode; the
outbox advisory locks are transaction-scoped and are compatible with transaction pooling.

The root `init.sql` creates:

- `instruments`: immutable product-rule snapshots keyed by `(symbol, version)`.
- `instrument_current_versions`: current version pointer per symbol.
- `instrument_product_current_versions`: current version pointer per `(product_line, symbol)`, used by product-line-aware latest/list and lifecycle scans.
- `instrument_symbol_sequences`: atomic instrument version allocator per symbol.
- `instrument_risk_brackets`: risk tiers keyed by `(symbol, version, bracket_no)`.
- `instrument_index_sources`: index component source config keyed by `(symbol, version, source)`.
- `candlestick_symbols`: optional symbol registry.
- `candlestick_candles`: OHLCV storage keyed by `(symbol, period, open_time)`.
- `price_index_ticks`: three-day asynchronous index-price audit ticks keyed by `(symbol, sequence)`.
- `price_index_components`: three-day asynchronous index component audit rows keyed by `(symbol, sequence, source)`.
- `price_symbol_leases`: active publisher ownership keyed by `(module, symbol)`.
- `price_symbol_sequences`: database-allocated price sequence keyed by `(module, symbol)`.
- `price_exchange_rates`: fiat and stable-coin bridge rates keyed by `(base_currency, quote_currency)`.
- `price_mark_ticks`: three-day mark-price audit snapshots keyed by `(symbol, sequence)`, including the
  complete calculation-input JSON and fixed-point output; real-time consumers use Kafka, not this table.
- `funding_rate_ticks`: `FINAL` funding rates frozen at a settlement boundary and keyed by `(symbol, sequence)`; per-second predictions remain on Kafka/cache.
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

- Development and small deployments can run `surprising-edge-provider` on port `9094`; it exposes both REST gateway routes and `/ws/v1` WebSocket fanout in one process.
- `surprising-gateway` and `surprising-websocket` live under the `surprising-edge` Maven module, but their provider artifacts remain independently deployable.
- Production deployments with many long-lived WebSocket connections should keep `surprising-gateway-provider` and `surprising-websocket-provider` split so WebSocket fanout can scale independently.
- Public REST clients should use the edge/gateway endpoint on `/api/v1/gateway/{service}` instead of calling each provider directly.
- Internal modules still own their APIs. The gateway is an allowlisted edge/BFF proxy, not the source of business logic.
- Private gateway routes require identity (`X-User-Id` or `Authorization` in the current implementation). In production, inject `X-User-Id` only after token/session validation at the auth or ingress layer.
- The gateway accepts `X-Trace-Id`, normalizes it, returns it to the client, and forwards the normalized value to backend providers. If the client omits it, the gateway generates one.
- Do not generate a new trace id inside asynchronous trading stages. Order entry writes the trace id into order events and command payloads; matching copies the command trace id into result/trade events; account settlement copies the trade trace id into position outbox events.
- Include `trace_id`, `order_id`, `command_id`, `trade_id`, Kafka topic/partition/offset, and `symbol` in operational logs and dashboards. Database audit tables `trading_order_events`, `trading_match_results`, and `trading_match_trades` also store `trace_id`.
- Realtime clients should connect to the edge/WebSocket endpoint on `/ws/v1`.
- Use REST first for snapshots, then WebSocket for deltas. L2 book snapshots are exposed through `GET /api/v1/gateway/trading-market/orderbook?symbol=BTC-USDT&depth=50`.
- WebSocket public channels: `candles`, `trades`, `depth`, `index`, `mark`, `funding`.
- WebSocket private channels: `orders`, `matches`, `positions`, `positionRisk`, `accountRisk`.
- Order book depth uses `SNAPSHOT` and per-price-level `DELTA` events. A delta with `quantitySteps=0` removes the level; clients must reload a snapshot when `previousSequence` is not continuous.
- WebSocket connection state is local memory only: socket, authenticated user id, subscriptions, and outbound queue.
- User-related events are consumed by every WebSocket node, then locally filtered by `userId + channel + symbol`; the node that hosts the matching connection pushes, and other nodes drop the event.
- Position push is downstream of account settlement: account DB transaction -> account outbox -> Kafka `surprising.account.position.events.v1` -> WebSocket fanout.
- Backend-authoritative unrealized PnL, equity, maintenance margin, and margin ratio are published directly to Kafka only after the risk-snapshot transaction commits: risk scan transaction -> Kafka `surprising.risk.position.events.v1` / `surprising.risk.account.events.v1` -> WebSocket fanout. These are latest-state updates; a temporary send failure is healed by the next scan. Only liquidation candidates use `risk_outbox_events` for at-least-once delivery.
- Open candle updates are coalesced by `surprising.websocket.fanout.candle-partial-coalesce-window`; closed candles are pushed immediately.
- Slow WebSocket clients are closed when their bounded outbound queue is full or sends time out.
- Clients must implement ping, exponential reconnect, and full resubscribe after reconnect.
- Set `surprising.websocket.security.allowed-origins` to exact production HTTPS origins. The wildcard default is for local development only.
- Configure `surprising.gateway.http-client.connect-timeout` and `read-timeout` so unhealthy backends cannot exhaust gateway worker threads.

## Scheduled Worker Pools

- Providers that use `@Scheduled` must run on an application-local Spring scheduling pool, configured by `spring.task.scheduling.*`, not on the fallback single scheduler.
- The default configs set dedicated thread-name prefixes per provider, for example `order-scheduler-`, `matching-scheduler-`, `risk-scheduler-`, and larger pools for combined providers such as `trading-entry`, `price`, and `margin-ops`.
- In production, tune `SPRING_TASK_SCHEDULING_POOL_SIZE` per process when scheduled work becomes heavier. Do not use one undersized shared scheduler for outbox publishing, risk scans, funding settlement, and market-maker refresh loops.

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
3. Create Kafka topics with `scripts/create-topics.sh`; pass `PRODUCT_LINES=<line>` when starting only one product line.
4. Start instrument providers.
5. Start candlestick providers.
6. Start `surprising-price-provider`, or start index and mark price providers separately.
7. Start `surprising-trading-entry-provider`, matching, account, and `surprising-margin-ops-provider`.
8. If split, start funding after mark price tables have fresh data, insurance after account tables are initialized and the fund is seeded, and ADL after account/liquidation/insurance are healthy.
9. Start `surprising-edge-provider`, or start WebSocket/fanout and gateway providers separately when WebSocket needs independent scaling.
10. If split, start gateway providers after the internal REST services they expose are reachable.
11. Start market-maker providers last. They should see healthy order, matching, account, mark, and gateway services before `surprising.market-maker.engine.enabled=true` is rolled out.

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

Run the real process-level order -> matching -> account smoke against the local Homebrew PostgreSQL/Kafka instances:

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
PRODUCT_LINES=LINEAR_PERPETUAL INCLUDE_LEGACY_PERP_TOPICS=false ./scripts/create-topics.sh
```

```bash
./scripts/kafka-trading-smoke.sh
```

The basic script:

- uses local middleware by default and does not start Docker middleware;
- creates an isolated database named `surprising_smoke_<run>` by default, then applies root `init.sql`;
- creates required topics through `scripts/create-topics.sh`;
- packages and starts order, matching, and account providers for the split smoke;
- funds a maker and taker through the account admin REST API;
- submits crossing REST orders, waits for exchange-core matching, and waits for account Kafka settlement;
- republishes the same `TRADE_SIDE_SETTLE` account command to verify command-id and envelope-hash
  idempotency without changing balances or positions.

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
- Funding-rate prediction is published directly to `surprising.perp.funding.rate.v1` and cached by symbol; it performs no rate-table or outbox write. At the funding boundary, the owner freezes the cached prediction into one idempotent `FINAL` rate row before settlement. If no current prediction is available, settlement fails closed for that symbol.
- Funding-rate publication and settlement can be paused independently with `surprising.funding.calculation.enabled=false` and `surprising.funding.settlement.enabled=false`.
- Trading, matching, account, trigger, liquidation, and risk outbox publishers are at-least-once. They atomically lease only their own rows, with a lease sized for the configured bounded Kafka-send workload, then send outside a database transaction and mark the result. Account ordinary-event groups submit an ordered window of up to five sends before waiting and confirm only the continuous successful prefix; account financial-command groups wait for each acknowledgement to preserve per-user command order. Trading, matching, and account publishers confirm successful batches with one SQL update. A Kafka send failure increments `attempts`, records `last_error`, and moves `next_attempt_at` forward with capped exponential backoff; the row remains unpublished and is retried by later scans.
- The shared trading, account, and risk outbox tables retain published delivery rows for seven days. Order, matching, trigger, liquidation, account, and risk publishers each run retention cleanup once per minute and only delete their owned aggregate/product-line rows. A run performs at most ten short 10,000-row `FOR UPDATE SKIP LOCKED` delete statements, so it can catch up without one long lock-holding transaction. Cleanup is enabled by default and never selects unpublished or failed rows. `retention`, `cleanup-delay-ms`, `cleanup-batch-size`, and `cleanup-max-batches` must all be positive; invalid values fail configuration binding instead of widening the delete cutoff.
- Order-provider keeps `cancel-all-after` timers and pending/running TWAP or Iceberg parents in two Redis sorted sets. The score is the exact Unix-millisecond trigger time; a token-owned lease rebuilds the sets on startup or cache loss. Redis only narrows candidates: every timer and algo slice is atomically rechecked and claimed in PostgreSQL before execution, and cache loss falls back to the bounded PostgreSQL due scan without losing work.
- Order-provider maintains a Redis read projection for ordinary user open orders. A user has three same-slot keys under `surprising:order:v1:{PRODUCT_LINE:userId}`: `:open` is a ZSET whose member and exact score are the monotonic `order_id`; `:open:orders` is the full-order snapshot hash; `:open:revisions` retains the latest revision, including terminal tombstones. The committed order/matching outbox events cause the consumer to reload the authoritative row and execute one Lua revision compare-and-set. Only `ACCEPTED` and `PARTIALLY_FILLED` rows remain in the ZSET/hash; cancel, reject, fill, liquidation close, and any other terminal status removes the snapshot and index member while retaining the revision.
- `GET /orders/open` reads the ZSET plus hash with opaque `order_id` cursor pagination (`orderId.desc`). It falls back to the product-line-scoped PostgreSQL keyset query when Redis is unready, a user epoch is missing, or a hash/index row is incomplete; it must never return a partial page. A token lease starts periodic paged rebuild epochs; rebuilding initializes each user only once for the epoch, so concurrent Kafka projection can safely win with its higher revision. Redis is strictly a replayable read projection: cancel, amend, funds freeze/release, matching, liquidation, and final status transitions remain guarded by PostgreSQL conditional writes and transactional outbox rows. Do not enable Spring Redis global transaction support or attempt XA for this path.
- ADL consumes committed risk-position snapshots into one Redis sorted set per product line and settlement asset, for example `surprising:adl:v1:queue:{LINEAR_PERPETUAL:USDT}`. Its score is the negative ADL priority, so the highest priority is read first. Readiness markers and rebuild locks use the same product-line isolation. The consumer verifies the configured topic and ignores snapshots for a different product line. A token-owned rebuild periodically repopulates every asset from PostgreSQL before setting the readiness marker. ADL never executes from Redis data: each selected member is recalculated against a fresh mark price and locked by `AdlRepository.lockCandidate`; incomplete or unavailable Redis data uses the existing database queue.
- Liquidation candidates enter a Redis sorted set with `margin_ratio_ppm` as the score, so the most under-margined candidate is selected first. Kafka arrival writes the durable candidate payload to the queue and drains it; an expired readiness marker, missing payload, or Redis error switches the scheduled drain to a bounded PostgreSQL `NEW`-candidate scan. `LiquidationRepository.claimCandidate` remains the atomic authority, and `LiquidationService` then repeats risk-status, fresh-price, and position-lock checks before an order can be created.
- Mark-price publication requires a fresh index-price event with non-null `indexPrice` and usable status (`HEALTHY` or `DEGRADED`). `INSUFFICIENT_SOURCES`, stale, or missing index events stop fresh mark publication; ordinary order entry then fails closed through the mark-price freshness checks.
- Order entry rejects market orders when mark price is stale or missing, and enforces min/max notional using the configured mark-derived execution band. Linear market-order max-notional and initial-margin checks use the upper bound for both BUY and SELL; matching still applies the side-specific protected price before exchange-core submission and rejects taker orders that would self-trade.
- Matching startup rebuilds exchange-core order books from DB open `LIMIT` + `GTC/GTX` orders that already have successful `PLACE` results.
- Matching public depth does not use `trading_outbox_events`. After a successful exchange-core command, it offers a full snapshot to a dedicated Kafka publisher with one latest-only slot per symbol. Newer snapshots replace the pending snapshot only for the same symbol; different symbols remain isolated and publish concurrently. Kafka backpressure or failure may drop intermediate public snapshots but cannot block matching, and the next full snapshot heals consumers.
- Matching public trades also bypass `trading_outbox_events` and the database. A lightweight `PublicTradeEvent` enters an independent bounded FIFO per symbol; a scheduler enabled by default drains up to 2,000 events every 50 ms, with at most 256 from one symbol per pass and 4,096 sends in flight. Trades are not coalesced. A symbol queue above 10,000 drops only its oldest trades, and Kafka send failures are not retried on this public path. WebSocket public trades and candlestick consume this stream.
- Financial processing is isolated from both public publishers. Matching persists the full trade audit, emits maker/taker `account.user.commands.v1` through the durable matching outbox, and includes full trades in the durable `match.results.v1` used for private match notifications and open-order projection. Public queue overflow, serialization failure, or Kafka backpressure cannot abort settlement work.
- Matching startup fails if recovered orders cross the book. Fix persisted order state before restarting instead of continuing with an inconsistent book.
- Matching closes itself on unsafe Kafka partition reassignment after it has processed commands; orchestration should restart it so the new owner restores a fresh book from DB.
- Matching also closes itself when a decoded command fails during processing. Do not configure the listener to spin on in-place retries; restart the process so Kafka replay runs against a DB-recovered exchange-core book.
- Matching result, financial trade audit, order-state, margin-release, account-command, and matching-outbox writes are fail-fast. A skipped insert/update means the process must restart and recover from DB before Kafka replay.
- Matching fill persistence is guarded by open order status, `remaining_quantity_steps >= fillQty`, and `quantity_steps = executed_quantity_steps + remaining_quantity_steps`. Do not clamp overfills with `LEAST/GREATEST`; a mismatch means DB state and the exchange-core event stream have diverged.
- `surprising.trading.matching.kafka.restart-on-partition-reassignment` only controls partition-movement restarts. Command-failure restart is always enabled.
- Matching margin release is a guarded balance transition. The update requires `account_balances.locked_units >= releaseUnits`; if that invariant is broken, the matching transaction fails and should be investigated instead of masking the inconsistency by crediting available balance.
- Matching margin release allows a missing `account_margin_reservations` row only for `reduce_only = TRUE` orders. Non-reduce-only orders must have a reservation; missing reservations fail the transaction.
- Order entry inserts `trading_orders` before reserving margin. Only the partial `(user_id, client_order_id)` uniqueness conflict is idempotent; a duplicate `clientOrderId` must return the original order without writing `account_margin_reservations` or changing `account_balances`.
- Account opening fills require an existing non-reduce-only `account_margin_reservations` row. Missing reservations or skipped account margin updates must fail the trade transaction rather than creating uncollateralized positions.
- Account closing fills may skip order-reservation release only for `reduce_only = TRUE` orders. Non-reduce-only close or flip fills must find the original reservation row; otherwise the account transaction fails.
- Account `TRADE_PNL` ledger insert/backfill writes are fail-fast. Each maker/taker side is
  idempotent by its immutable `account_commands.command_id`; conflicting envelope hashes or ledger
  conflicts indicate inconsistent state. `account_trade_settlements` must reach bilateral completion.
- Account maker/taker fees are settled from the required `taker_fee_rate_ppm` / `maker_fee_rate_ppm`
  values on `trading_match_trades` and written as `TRADE_FEE` with `trade_id`, `order_id`, `symbol`,
  and `fee_rate_ppm`. Positive ppm rates debit the user; negative ppm rates rebate. Fee ledger
  insert/backfill writes are fail-fast for the same reason as `TRADE_PNL`.
- PnL and funding losses may reduce `locked_units` only through locked collateral backed by `account_position_margins`; open-order reservation locks must remain intact. If `account_position_margins` exceeds the releasable locked collateral, treat it as an accounting invariant issue.
- Account position event outbox rows are written after `account_positions` is updated. Kafka publishing is at-least-once, so clients and downstream consumers should tolerate duplicate position events by `eventId` or `tradeId`.
- The trigger consumer group also consumes account position events. A zero-position event reconciles Redis members for `POSITION_CLOSED` trigger cancellations that account committed in the same settlement transaction; monitor this topic in trigger-group lag as well as risk-group lag.
- Risk consumes account position events only as scan triggers. It does not trust the event as accounting state; it re-reads positions, balances, deficits, and instruments inside the risk transaction, while mark prices come from one fresh immutable local Kafka-cache snapshot. Missing, stale, or version-mismatched marks skip the whole account risk group.
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
- Liquidation candidate status updates and liquidation outbox publish/failure markers are fail-fast; a missing row means state has diverged and should be investigated before replay continues. The liquidation publisher first leases only `LIQUIDATION_ORDER` rows, then waits for Kafka outside the database transaction; ordinary `ORDER` rows, including liquidation-requested cancels, remain owned by order-provider.
- ADL providers only claim aged deficits when the insurance fund balance for that asset is zero.
- ADL can be paused with `surprising.adl.scanner.enabled=false`; the scanner returns before claiming deficit rows.
- ADL providers re-lock target `account_positions` rows before reducing a position; concurrent nodes skip locked positions.
- ADL position-margin release is guarded by `account_position_margins.margin_units >= releaseUnits`; skipped updates fail the ADL transaction.
- External-source failures are stored in component/audit records; unavailable index snapshots are published so
  real-time consumers invalidate earlier usable values.
- Instrument changes are immutable versions; downstream services replace local cache after reading current snapshots or consuming `surprising.instrument.events.v1`.
- Gateway route names are allowlisted. Unknown services return 404 before any backend call.
- WebSocket private subscriptions are matched against the authenticated user id; a client cannot subscribe to another user's private stream by sending a different `userId`.

## Troubleshooting

- `price_symbol_leases` owner does not move after a node dies: wait until `lease_until`; if it is far in the future, verify node clock synchronization.
- `risk_scan_leases` owner does not move after a risk node dies: wait until `lease_until`, confirm all nodes have synchronized clocks, and verify `surprising.risk.coordination.node-id` is unique per live instance.
- Price sequence has gaps: expected after failed attempts. Investigate only if a sequence moves backwards, which should not happen.
- Index price unavailable: inspect the latest index-topic snapshot status, then `price_index_components` for
  `STALE`, `OUTLIER`, `ERROR`, or conversion failure reasons.
- Binance returns `451` or Bybit returns `403`: collector egress region/IP is blocked by the venue.
- WebSocket reconnect loop: check venue connectivity, ping/pong behavior, idle timeout, and egress firewall.
- Kafka consumer lag: increase topic partitions, provider instances, or stream threads. Do not create one topic per symbol.
- WebSocket clients miss public market data on some pods: verify each WebSocket pod has a unique `surprising.websocket.kafka.group-id`.
- WebSocket private positions not updating: check account outbox lag in `account_outbox_events`, Kafka topic `surprising.account.position.events.v1`, and the client's private `positions` subscription/authenticated user id.
- WebSocket risk/PnL not updating: check risk scan freshness and the Kafka topics `surprising.risk.position.events.v1` and `surprising.risk.account.events.v1`, then the client's `positionRisk` / `accountRisk` subscriptions. `risk_outbox_events` applies only to liquidation candidates.
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
