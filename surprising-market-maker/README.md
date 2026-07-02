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
- The service should be deployed on an internal network only. Exposing it to public clients would allow operational control of liquidity accounts.
- HTTP `X-Trace-Id` is accepted and forwarded to downstream Feign calls. Scheduled cycles create a trace id and expose the last value in `/strategies`.

## API

Provider port: `9096`

```bash
curl 'http://localhost:9096/api/v1/market-maker/strategies'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/pause'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/resume'
curl -X POST 'http://localhost:9096/api/v1/market-maker/run-once' \
  -H 'X-Trace-Id: trace-mm-manual-1' \
  -H 'Content-Type: application/json' \
  -d '{"strategyId":"btc-usdt-mm-a","symbol":"BTC-USDT"}'
```

Through gateway, use the private route:

```bash
curl 'http://localhost:9094/api/v1/gateway/market-maker/strategies' -H 'X-User-Id: ops'
```

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
4. Places symmetric post-only levels around the anchor.
5. Applies inventory skew and inventory caps before submitting orders.
6. Cancels stale or off-target owned orders and submits only missing desired quotes.
7. Propagates the cycle trace id to order-provider, so order events, matching events, account settlement, risk events, and private WebSocket pushes can be correlated.

## Multi-Node Deployment

Multiple nodes can run the same config. The lease key is `strategyId + symbol`, so only one node actively quotes that symbol for a strategy at a time. Use a stable `node-id` in production so logs and leases are easy to inspect.

Use distinct accounts or distinct strategy IDs for independent market-maker programs. Do not let two strategies control the same account and symbol unless the inventory caps are intentionally sized for the combined exposure.

## Build And Test

```bash
mvn -pl :surprising-market-maker-provider -am test
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```
