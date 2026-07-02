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
  + optional trigger: surprising.account.position.events.v1
  -> risk_account_snapshots
  -> risk_position_snapshots
  -> surprising.risk.account.events.v1 / surprising.risk.position.events.v1
  -> risk_liquidation_candidates
  -> surprising.perp.liquidation.candidates.v1
```

`risk-provider` scans account asset groups by keyset pagination: `(userId ASC, settleAsset ASC)` with
`surprising.risk.calculation.scan-batch-size` groups per database read. A group is eligible only when every open
position in that `userId + settleAsset` group has a fresh mark price. Each account asset group is then recalculated and
processed in its own Spring transaction: account snapshot, position snapshots, candidate insert, and outbox insert
either commit together or roll back together. A failure in one group is logged and retried by the next scan; other
groups in the same scan continue.

The provider also consumes `surprising.account.position.events.v1`. A settled position update triggers an immediate scan
for the affected `userId + settleAsset` group, using the event `symbol` and pinned `instrumentVersion` to resolve the
risk group. If the event version is `0`, the provider falls back to `instrument_current_versions` so a flat-position
event can still resolve the settlement asset. This event-driven path reduces liquidation latency; the keyset scheduled
scanner remains the safety net for missed Kafka events, stale marks, replays, and operator pauses.
If the event closes the last open position in that account asset group, risk-provider still writes an account snapshot
with zero unrealized PnL, zero maintenance margin, and `NORMAL` status. It also writes a zero-quantity position snapshot
for the event symbol so latest position-risk queries do not return stale nonzero exposure.

Every committed risk scan also writes transactional outbox events for private frontend display:

- `RISK_ACCOUNT_UPDATED` includes wallet balance, unrealized PnL, equity, maintenance margin, margin ratio, and status
  for the `userId + settleAsset` group.
- `RISK_POSITION_UPDATED` includes mark price, notional, unrealized PnL, maintenance margin, position margin, margin
  ratio, and status for each affected position.

These events are the authoritative backend-calculated values for WebSocket display. A frontend may locally combine
`positions` and `mark` events for high-frequency visual interpolation, but liquidation and account risk decisions must
use risk-provider snapshots/events. When a risk scan is triggered by an account position event, the resulting risk
events preserve that event's `traceId`; scheduled fallback scans may emit events without a trace id.

## Kafka

- `surprising.account.position.events.v1`: account-settled position updates, key = `symbol`; consumed by risk-provider
  as the low-latency scan trigger.
- `surprising.risk.account.events.v1`: account risk snapshot updates, key = `userId:settleAsset`; consumed by
  websocket-provider for private `accountRisk` pushes.
- `surprising.risk.position.events.v1`: position risk snapshot updates, key = `symbol`; consumed by websocket-provider
  for private `positionRisk` pushes.
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
- `account_positions_open_scan_idx`
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
| `surprising.risk.kafka.group-id` | `surprising-risk-v1` | Shared consumer group for risk-provider nodes consuming account position events. |
| `surprising.risk.kafka.position-events-topic` | `surprising.account.position.events.v1` | Account-settled position event topic used as the event-driven scan trigger. |
| `surprising.risk.kafka.account-risk-events-topic` | `surprising.risk.account.events.v1` | Outbox topic for account risk WebSocket events. |
| `surprising.risk.kafka.position-risk-events-topic` | `surprising.risk.position.events.v1` | Outbox topic for position risk WebSocket events. |
| `surprising.risk.kafka.concurrency` | `2` | Kafka listener concurrency. Keep it no higher than useful partition parallelism per node. |
| `surprising.risk.kafka.max-poll-records` | `500` | Kafka records fetched per poll. Tune with position-event lag and scan transaction latency. |
| `surprising.risk.coordination.enabled` | `true` | Enables the scan lease. Keep this on for multi-node deployments. |
| `surprising.risk.coordination.node-id` | `${HOSTNAME:}` | Stable owner id. If empty, the process generates a local random id. |
| `surprising.risk.coordination.lease-duration` | `15s` | Failover delay for a stopped scanner. Keep it longer than the scan interval. |
| `surprising.risk.calculation.scan-batch-size` | `500` | Number of `userId + settleAsset` groups fetched per keyset page. Raise it for fewer DB round trips; lower it to reduce scan latency spikes on large accounts. |

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
  another node take over after a failure.
- The scanner uses keyset pagination over `userId + settleAsset` groups and the partial
  `account_positions_open_scan_idx` index to avoid loading the whole position set before claiming leases. Do not replace
  this with offset pagination; offset scans get slower and can skip or repeat rows while positions are changing.
- Position-event scanning and scheduled scanning both go through the same `risk_scan_leases` ownership guard and the
  same group transaction. Kafka consumer-group assignment limits duplicate event processing, while the PostgreSQL lease
  prevents duplicate writers if events are replayed or two nodes race on the same `userId + settleAsset` group.
- The position-event consumer rejects records whose Kafka key does not match payload `symbol`. This keeps symbol
  ordering and partitioning invariants aligned with matching, account, and liquidation modules.
- A position event that produces no calculated positions is not automatically treated as flat. The provider first checks
  whether the `userId + settleAsset` group still has open positions; if it does, the empty result is treated as stale or
  missing marks and no misleading `NORMAL` snapshot is written.
- If any open position in a `userId + settleAsset` group has no fresh mark price, the whole group is skipped for that
  pass. Partial account-risk aggregation is not allowed because it can understate maintenance margin.
- The database keeps at most one active `NEW/PROCESSING` liquidation candidate per `userId + symbol`; a later scan can create a new candidate only after the previous one is `COMPLETED` or `CANCELED`.
- Risk snapshot and outbox writes must affect exactly one row; skipped inserts or updates fail fast and roll back the current transaction.
- A liquidation candidate insert returning 0 only means the partial active-candidate index already has a `NEW/PROCESSING` row. Candidate-id or snapshot uniqueness conflicts are not treated as idempotent and must fail. Once a candidate is inserted, the provider must read it back and enqueue the outbox event; otherwise the scan fails to avoid a DB candidate without a Kafka event.
- Outbox `published_at` and retry-failure updates must also touch the expected row. A missing row is treated as inconsistent state and should fail the process for investigation.
- This module detects liquidation accounts and publishes candidates. `surprising-liquidation` executes reduce-only close orders after re-checking the latest risk state.

## Verification

```bash
mvn -pl :surprising-risk-provider -am test
```
