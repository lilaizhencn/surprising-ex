# surprising-market-maker

[简体中文](README_CN.md)

Internal market-making service for controlled liquidity, depth simulation, and long-running exchange-chain testing.

The module is not a matching shortcut. It calls the same order-entry RPCs as every other client, so price protection, margin checks, post-only handling, order outbox, exchange-core matching, account settlement, risk, WebSocket, liquidation, funding, and insurance paths are exercised normally.

## Modules

- `surprising-market-maker-api`: internal RPC contracts.
- `surprising-market-maker-provider`: scheduled quote planner and quote reconciler.

## Runtime Safety

- `surprising.market-maker.engine.enabled` defaults to `false`; no scheduled live orders are sent until explicitly enabled. A private `run-once` API call can still place orders for enabled strategies.
- Every quote is a `LIMIT + GTX + postOnly=true` order.
- The strategy queries account position before quoting. If account state is unavailable, the cycle fails closed and does not quote.
- Inventory caps stop quoting the exposure-increasing side once `maxInventorySteps` is reached.
- Existing market-maker orders are reconciled by `clientOrderId` prefix and canceled when stale, too far from the target price, or no longer desired.
- Optional reference-market calibration is fail-closed per cycle: if enabled sources are unavailable and no fresh cache exists, the provider falls back to the normal local mark/order-book quote model instead of using stale external depth.
- The service should be deployed on an internal network only. Exposing it to public clients would allow operational control of liquidity accounts.
- HTTP `X-Trace-Id` is accepted and forwarded to downstream Feign calls. Scheduled cycles create a trace id and expose the last value in `/strategies`.

## API

Provider port: `9096`

The existing internal provider API remains compatible:

```bash
curl 'http://localhost:9096/api/v1/market-maker/strategies'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/pause'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/resume'
curl -X POST 'http://localhost:9096/api/v1/market-maker/run-once' \
  -H 'X-Trace-Id: trace-mm-manual-1' \
  -H 'Content-Type: application/json' \
  -d '{"strategyId":"btc-usdt-mm-a","symbol":"BTC-USDT"}'
```

Admin operations use a separate admin path and require the gateway-injected admin identity header:

```bash
curl 'http://localhost:9096/api/v1/admin/market-maker/strategies' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/metrics?limit=200' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/pnl-attribution?windowHours=24&limit=200' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/strategy-logs?limit=200&sort=createdAt.desc' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/strategies/btc-usdt-mm-a/config' -H 'X-Admin-User-Id: 1001'
curl -X POST 'http://localhost:9096/api/v1/admin/market-maker/strategies/btc-usdt-mm-a/config' \
  -H 'X-Admin-User-Id: 1001' \
  -H 'Content-Type: application/json' \
  -d '{"baseQuantitySteps":25,"spreadTicks":40,"orderLevels":2,"reason":"quote tuning"}'
```

admin-web calls through the unified admin gateway route. Gateway maps the `market-maker` admin route to `/api/v1/admin/market-maker`:

```bash
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/strategies' -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/metrics?limit=200' -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/pnl-attribution?windowHours=24&limit=200' -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/strategy-logs?limit=200&sort=createdAt.desc' -H 'Authorization: Bearer <admin-token>'
```

`/metrics` aggregates inventory usage, owned live quotes, desired quote coverage, missing quotes, stale quotes, off-target quotes, spread, trace id, and anomalies by strategy/account/symbol. Anomaly types include `NO_LIVE_QUOTES`, `MISSING_DESIRED_QUOTES`, `STALE_QUOTES`, `OFF_TARGET_QUOTES`, `INVENTORY_LIMIT_REACHED`, `INSTRUMENT_NOT_TRADING`, and `METRIC_COLLECTION_FAILED`.

`/pnl-attribution` returns read-only attribution rows for configured strategy/account/symbol scopes only. It uses the market-maker `clientOrderId` prefix to collect owned orders, joins `trading_match_trades` for maker/taker fills, joins `account_ledger_entries` with `TRADE_FEE` references for net fees, and includes the current `account_positions` realized PnL and signed inventory snapshot.

`/strategy-logs` reads `market_maker_strategy_run_events`, which records cycle success/failure, quote reconciliation, IOC trade submit/reject outcomes, skipped cycles, error messages, counters, node id, and trace id. It supports cursor paging with `limit`, `cursor`, and `sort`; supported sort values are `createdAt.desc` and `createdAt.asc`. Responses keep `events/count` and add `nextCursor`, `hasMore`, `sort`, and `limit`. Event writes are best-effort and do not block the quoting cycle.

`/strategies/{strategyId}/config` reads and writes `market_maker_strategy_overrides`. Only enabled, base quote quantity, margin mode, spread, level spacing, inventory cap/skew, and quote levels are hot-editable; accounts and symbols remain deployment config. `null` request fields fall back to `application.yml`, and a request with all editable fields set to `null` clears the override.

## Configuration

```yaml
surprising:
  clients:
    account:
      base-url: http://localhost:9086
    instrument:
      base-url: http://localhost:9080
    mark-price:
      base-url: http://localhost:9083
    matching:
      base-url: http://localhost:9085
    order:
      base-url: http://localhost:9084
  market-maker:
    engine:
      enabled: false
      cycle-delay-ms: 250
      node-id: mm-node-a
    coordination:
      enabled: true
      lease-duration: 5s
    quoting:
      order-book-depth: 20
      order-levels: 3
      min-spread-ticks: 10
      level-spacing-ticks: 10
      refresh-threshold-ticks: 2
      max-open-orders-per-account-symbol: 30
      stale-order-max-age: 30s
      max-price-deviation-ppm: 5000
    risk:
      max-inventory-steps: 10000
      max-inventory-skew-ppm: 800000
    reference-market:
      enabled: false
      refresh-interval: 500ms
      max-age: 3s
      request-timeout: 2s
      depth-levels: 20
      quantity-scale-ppm: 1000000
      min-quantity-steps: 1
      max-quantity-steps: 1000
      sources:
        - name: BINANCE_USDM
          enabled: true
          symbol: BTC-USDT
          external-symbol: BTCUSDT
          url: https://fapi.binance.com/fapi/v1/depth?symbol={symbol}&limit=20
          parser: BINANCE_DEPTH
        - name: OKX_SWAP
          enabled: true
          symbol: BTC-USDT
          external-symbol: BTC-USDT-SWAP
          url: https://www.okx.com/api/v5/market/books?instId={symbol}&sz=20
          parser: OKX_BOOKS
        - name: BYBIT_LINEAR
          enabled: true
          symbol: BTC-USDT
          external-symbol: BTCUSDT
          url: https://api.bybit.com/v5/market/orderbook?category=linear&symbol={symbol}&limit=50
          parser: BYBIT_ORDERBOOK
    strategies:
      - strategy-id: btc-usdt-mm-a
        enabled: true
        account-ids: [900001, 900002]
        symbols: [BTC-USDT]
        base-quantity-steps: 10
        margin-mode: CROSS
```

## Quote Model

For each configured `strategyId + symbol`, the provider:

1. Acquires a PostgreSQL lease in `market_maker_strategy_leases` when coordination is enabled.
2. Reads instrument config, latest order book, latest mark price, and the market-maker account position.
3. Uses mark price as the anchor when available; otherwise it falls back to order-book midpoint.
4. If `reference-market.enabled=true`, fetches a fresh Binance/OKX/Bybit-style order book snapshot and mirrors each reference level's distance from midpoint plus its quantity into local ticks/steps, subject to local price-deviation, post-only, quantity, and inventory caps.
5. Without a fresh reference snapshot, places symmetric post-only levels around the anchor using configured spread and spacing.
6. Applies inventory skew and inventory caps before submitting orders.
7. Cancels stale or off-target owned orders and submits only missing desired quotes.
8. Propagates the cycle trace id to order-provider, so order events, matching events, account settlement, risk events, and private WebSocket pushes can be correlated.

Reference-market calibration currently uses REST depth snapshots and a short in-memory cache. It is enough to make local quoting depth, per-level spacing, and quantities follow mainstream exchange snapshots during stress tests. A production market maker should still maintain a streaming WebSocket local book for lower latency and better resilience.

## Multi-Node Deployment

Multiple nodes can run the same config. The lease key is `strategyId + symbol`, so only one node actively quotes that symbol for a strategy at a time. Use a stable `node-id` in production so logs and leases are easy to inspect.

Use distinct accounts or distinct strategy IDs for independent market-maker programs. Do not let two strategies control the same account and symbol unless the inventory caps are intentionally sized for the combined exposure.

## Build And Test

```bash
mvn -pl :surprising-market-maker-provider -am test
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```
