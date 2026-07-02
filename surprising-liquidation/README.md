# surprising-liquidation

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange perpetual liquidation execution module. It consumes liquidation candidates from risk-provider, claims candidates, re-checks latest risk state, and creates reduce-only market close orders through the existing order and matching path.

## Modules

- `surprising-liquidation-api`: liquidation order query DTOs.
- `surprising-liquidation-provider`: candidate consumption, task claiming, risk re-check, staged sizing, reduce-only preemption, and liquidation order submission.

## Core Flow

```text
surprising.perp.liquidation.candidates.v1
  -> surprising-liquidation-provider
  -> claim risk_liquidation_candidates(status NEW -> PROCESSING)
  -> re-check latest risk_account_snapshots
  -> request cancel for existing user reduce-only close orders
  -> trading_orders(reduceOnly=true, MARKET, IOC)
  -> trading_outbox_events
  -> surprising.perp.order.commands.v1
  -> surprising-matching-provider / exchange-core
```

## Liquidation Order Rules

- Liquidating a long position creates a `SELL` close order.
- Liquidating a short position creates a `BUY` close order.
- `orderType = MARKET`
- `timeInForce = IOC`
- `reduceOnly = true`
- `postOnly = false`
- `quantitySteps` is capped by `abs(livePosition)` and is not reduced by existing user reduce-only orders.

Before submitting the order, the service reads the latest fresh risk state again: cross-margin candidates re-check the account-level `userId + settleAsset` state, and isolated candidates re-check the position-level `userId + symbol + marginMode` state. If risk is no longer in `LIQUIDATION`, or the latest risk snapshot is older than `surprising.liquidation.risk.max-snapshot-age`, the candidate is marked `CANCELED` and no order is submitted.

Before submitting a liquidation order, the provider also reads the latest `risk_position_snapshots` row and the account snapshot with the same `snapshot_id` to calculate audit prices:

- `bankruptcy_price_ticks`: the adverse price where current equity reaches zero.
- `takeover_price_ticks`: the adverse price where current equity equals the configured liquidation-fee buffer.
- `liquidation_fee_rate_ppm`: fee rate frozen from `surprising.liquidation.execution.liquidation-fee-rate-ppm`.
- `liquidation_fee_units`: estimated liquidation fee from mark notional and `surprising.liquidation.execution.liquidation-fee-rate-ppm`; the default is `3000 ppm` or `0.3%`.

The calculation uses `PerpetualContractMath` plus long/BigInteger arithmetic, not floating point. Linear and inverse contracts use the same boundary-search logic. If the fresh risk snapshot quantity differs from the locked live `account_positions` quantity, the candidate is canceled with `RISK_POSITION_CHANGED` and the next risk scan must create a fresh candidate.

## Staged Liquidation

The provider follows a staged liquidation policy:

- Lock the live `account_positions` row.
- Lock open reduce-only orders for the same `userId + symbol + closeSide`.
- Sum pending reduce-only close quantity with checked long addition; overflow rolls back the candidate transaction instead of increasing liquidation capacity.
- Write `CANCEL_REQUESTED` events for those user orders and enqueue cancel commands in the same symbol outbox partition before the liquidation place command.
- Compute available close quantity as `abs(livePosition)`; liquidation must not be blocked by a far-away user reduce-only GTC order.
- Compute live notional and notional-per-step with shared `PerpetualContractMath` long formulas using the position's pinned `instrument_version`.
- If the current notional is in a higher risk bracket, reduce enough quantity to move toward the lower bracket floor.
- If already in the lowest bracket, close a configured percentage of the remaining reducible quantity.
- If the margin ratio is above `full-close-margin-ratio-ppm`, close all available reducible quantity.

One liquidation candidate submits one reduce-only order. If the account remains liquidatable after that order is matched, the next risk scan creates another candidate and the process repeats.

## API

Query liquidation orders:

```bash
curl 'http://localhost:9088/api/v1/liquidations/orders?limit=100'
curl 'http://localhost:9088/api/v1/liquidations/orders?userId=1001&limit=100'
curl 'http://localhost:9088/api/v1/liquidations/orders/by-candidate?candidateId=1'
```

## Database

Root [init.sql](../init.sql) creates:

- `liquidation_sequences`
- `liquidation_orders`

Liquidation orders are also written to:

- `trading_orders`
- `trading_order_events`
- `trading_outbox_events`

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-liquidation-provider -am spring-boot:run
```

Port:

- `9088`: liquidation execution service.

## Production Notes

- Run multiple liquidation-provider nodes; candidate claiming is protected by PostgreSQL conditional updates.
- Kafka candidate/match-result consumer fetch size is controlled by `surprising.liquidation.kafka.max-poll-records` and defaults to `500`.
- A liquidation candidate is not an execution command; latest risk must be re-checked before order submission.
- The re-check requires a fresh risk snapshot. Default `surprising.liquidation.risk.max-snapshot-age` is `5s`; stale or missing snapshots are treated as non-liquidatable.
- Liquidation orders go through the unified order/matching path and do not modify positions directly.
- `liquidation_orders` freezes bankruptcy price, takeover price, fee rate, and estimated liquidation fee for audit, dispute handling, and insurance/ADL reconciliation. This module does not transfer the estimated fee. After the liquidation order actually fills, account-provider charges the actual fill-based liquidation fee capped by collectible collateral and publishes `surprising.account.liquidation-fee.events.v1`; insurance-provider credits only that collected amount.
- Realized liquidation losses are still recorded by account-provider as `account_deficits`, then processed by insurance-provider and ADL.
- Liquidation sizing is staged and risk-bracket aware. Full close is reserved for severe margin-ratio cases.
- Existing same-side reduce-only close orders are preemptively canceled before submitting liquidation. `CANCEL_REQUESTED` orders may still be live in exchange-core, so liquidation re-enqueues cancel commands; matching-provider post-only checks apply only to `PLACE` and must never block cancel.
- Pending close aggregation is fail-fast on overflow. A broken order set must not allow liquidation sizing or user close capacity to wrap around.
- Liquidation pre-cancel commands, liquidation place commands, and later match events use symbol as Kafka key. Preserve outbox order within the same symbol partition; do not route cancel and liquidation place to different partitions.
- Liquidation order creation is fail-fast. `trading_orders` does not suppress unique-key conflicts; `trading_order_events`, outbox rows, and `liquidation_orders` audit rows must also be inserted in the same transaction. A write failure rolls the candidate transaction back.
- Match-result lifecycle updates first move the local `liquidation_orders` audit row out of `SUBMITTED`/`PARTIALLY_FILLED`; when that succeeds, the matching `risk_liquidation_candidates` row must also move from `PROCESSING` to `COMPLETED`/`CANCELED` in the same transaction. A missing or already-terminal candidate is treated as inconsistent state and fails fast.
- Candidate status updates from `PROCESSING` to `COMPLETED`/`CANCELED` must touch exactly one row. Outbox `published_at` and retry-failure markers must also update the expected row.
- Post-liquidation positions and realized PnL are still updated by account-provider from match trades.

## Verification

```bash
mvn -pl :surprising-liquidation-provider -am test
```
