# surprising-risk

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange perpetual risk module. The current implementation produces account-level margin snapshots, position risk snapshots, and liquidation candidates for the liquidation executor.

## Modules

- `surprising-risk-api`: risk query and liquidation candidate DTOs.
- `surprising-risk-provider`: risk scanning, snapshot persistence, liquidation candidate creation, and event publishing.

## Calculation Model

The Java layer remains long-based:

- `walletBalanceUnits`: `account_balances.available_units + locked_units - account_deficits.deficit_units`.
- `unrealizedPnlUnits`: unrealized PnL in smallest units.
- `equityUnits = walletBalanceUnits + unrealizedPnlUnits`.
- `maintenanceMarginUnits`: maintenance margin in smallest units.
- `marginRatioPpm = maintenanceMarginUnits / equityUnits * 1_000_000`.

Instrument stores `contract_type`, `price_tick_units`, `quantity_step_units`, `notional_multiplier_units`, and the base `maintenance_margin_rate_ppm` as long values. The price provider stores `mark_price_units` in quote-asset smallest units, and the risk provider converts that long value into version-specific `markPriceTicks` with the position's pinned `price_tick_units`. For each position, risk-provider first calculates the current notional and selects the highest matching `instrument_risk_brackets.notional_floor_units` for the same symbol and instrument version. The bracket `maintenance_margin_rate_ppm` is used for maintenance margin; the instrument base rate is only a fallback when no bracket matches. `RiskMath` then calculates notional, unrealized PnL, and maintenance margin with long inputs/outputs and exact integer intermediates.
Risk joins `account_positions.instrument_version` to `instruments`; it does not reinterpret existing exposure with the latest instrument version.
Per-account risk totals use checked long addition for `unrealizedPnlUnits` and `maintenanceMarginUnits`. Overflow rolls back only the current `userId + settleAsset` scan transaction and is retried on the next scan.

Contract formulas:

- `LINEAR_PERPETUAL`: notional and PnL use `priceTicks * quantitySteps * notional_multiplier_units`.
- `INVERSE_PERPETUAL`: position value and PnL are converted to settlement coin units with `faceValueUnits * settleScaleUnits / priceQuoteUnits` and reciprocal entry/mark prices.

Statuses:

- `NORMAL`: margin ratio below warning threshold.
- `WARNING`: margin ratio at warning threshold but below liquidation threshold.
- `LIQUIDATION`: margin ratio at or above liquidation threshold; a candidate is created.

Defaults:

- Warning threshold: `800000 ppm`.
- Liquidation threshold: `1000000 ppm`.

## Core Flow

```text
account_positions + account_balances + account_deficits
  + price_mark_ticks
  + instruments / instrument_current_versions
  + account_asset_scales
  -> risk_account_snapshots
  -> risk_position_snapshots
  -> risk_liquidation_candidates
  -> surprising.perp.liquidation.candidates.v1
```

`risk-provider` scans calculated positions by `userId + settleAsset`. Each account asset group is processed in its
own Spring transaction: account snapshot, position snapshots, candidate insert, and outbox insert either commit
together or roll back together. A failure in one group is logged and retried by the next scan; other groups in the same
scan continue.

## Kafka

- `surprising.perp.liquidation.candidates.v1`: liquidation candidate events, key = `symbol`.

`surprising-liquidation-provider` consumes this topic, claims candidates, and submits reduce-only liquidation orders.

## API

Query account risk:

```bash
curl 'http://localhost:9087/api/v1/risk/account/latest?userId=1001&settleAsset=USDT'
```

Query position risk:

```bash
curl 'http://localhost:9087/api/v1/risk/positions/latest?userId=1001'
```

Query liquidation candidates:

```bash
curl 'http://localhost:9087/api/v1/risk/liquidation-candidates?status=NEW&limit=100'
```

## Database

Root [init.sql](../init.sql) creates:

- `account_asset_scales`
- `account_deficits`
- `risk_sequences`
- `risk_scan_leases`
- `risk_account_snapshots`
- `risk_position_snapshots`
- `risk_liquidation_candidates`
- `risk_outbox_events`

Core indexes:

- `risk_account_snapshots_query_idx`
- `risk_position_snapshots_user_idx`
- `risk_scan_leases_expiry_idx`
- `risk_liquidation_candidates_status_idx`
- `risk_liquidation_candidates_active_uidx`
- `risk_outbox_pending_idx`

## Multi-Node Coordination

The provider supports active-active deployment with PostgreSQL leases per `userId + settleAsset`.
Before writing snapshots or candidates for an account asset group, a node upserts `risk_scan_leases`.
It can take the group only when it already owns the row or when the previous `lease_until` has expired.

Configuration:

| Property | Default | Purpose |
| --- | --- | --- |
| `surprising.risk.coordination.enabled` | `true` | Enables the scan lease. Keep this on for multi-node deployments. |
| `surprising.risk.coordination.node-id` | `${HOSTNAME:}` | Stable owner id. If empty, the process generates a local random id. |
| `surprising.risk.coordination.lease-duration` | `15s` | Failover delay for a stopped scanner. Keep it longer than the scan interval. |

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-risk-provider -am spring-boot:run
```

Port:

- `9087`: risk service.

## Production Notes

- risk-provider depends on fresh mark prices; stale marks older than `max-mark-age` are excluded from scans.
- Risk scans are isolated per `userId + settleAsset` transaction. A bad account group must not roll back the whole scan
  batch or block other accounts.
- Overflow in group-level PnL or maintenance-margin aggregation is treated as inconsistent state. The provider rolls back that group before writing snapshots, candidates, or outbox rows.
- A liquidation candidate is liquidation input, not execution proof.
- The liquidation executor must re-check latest mark/equity before submitting liquidation orders.
- Risk scanning and liquidation execution must both be idempotent.
- Multiple risk-provider nodes can run for availability. The `risk_scan_leases` table coordinates writers per
  `userId + settleAsset`, so only one live owner writes snapshots and candidates for that group. Lease expiry lets
  another node take over after a failure. The current scanner still calculates the global position set before claiming
  each group; for very large account counts, add keyset pagination or upstream scan sharding to reduce pre-lease read
  cost.
- The database keeps at most one active `NEW/PROCESSING` liquidation candidate per `userId + symbol`; a later scan can create a new candidate only after the previous one is `COMPLETED` or `CANCELED`.
- Risk snapshot and outbox writes must affect exactly one row; skipped inserts or updates fail fast and roll back the current transaction.
- A liquidation candidate insert returning 0 only means the partial active-candidate index already has a `NEW/PROCESSING` row. Candidate-id or snapshot uniqueness conflicts are not treated as idempotent and must fail. Once a candidate is inserted, the provider must read it back and enqueue the outbox event; otherwise the scan fails to avoid a DB candidate without a Kafka event.
- Outbox `published_at` and retry-failure updates must also touch the expected row. A missing row is treated as inconsistent state and should fail the process for investigation.
- This module detects liquidation accounts and publishes candidates. `surprising-liquidation` executes reduce-only close orders after re-checking the latest risk state.

## Verification

```bash
mvn -pl :surprising-risk-provider -am test
```
