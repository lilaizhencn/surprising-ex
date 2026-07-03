# surprising-adl

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange auto-deleveraging module. It handles residual bankruptcy deficits after user margin and the insurance fund are exhausted.

The design follows the common futures-exchange loss waterfall: user margin -> liquidation -> insurance fund -> ADL. ADL reduces profitable high-priority opposing positions and uses part of the realized profit to cover the remaining deficit.

## Modules

- `surprising-adl-api`: ADL queue and event RPC contracts.
- `surprising-adl-provider`: residual-deficit scanner, ADL queue selector, position reduction, balance transfer, and event audit writer.

## Long Model

ADL uses long fixed-point values:

- `closedQuantitySteps`: reduced position size in instrument quantity steps.
- `entryPriceTicks` / `markPriceTicks`: long price ticks.
- `realizedProfitUnits`: settlement-asset units realized by the ADL close.
- `coveredUnits`: part of realized profit transferred to cover bankruptcy deficit.
- `priorityScorePpm`: ADL priority score in ppm-style long arithmetic.

No Java `BigDecimal` is used on the ADL path.
Candidate notional and unrealized profit are calculated in settlement-asset units with shared `PerpetualContractMath` long formulas. Linear contracts use mark-price notional; inverse contracts convert quote face value and reciprocal entry/mark prices into settlement coin units.
ADL queue calculation joins positions by `account_positions.instrument_version`.

## Priority Model

Queue priority is based on the same principle used by major perpetual venues: profitable and highly leveraged accounts are deleveraged first.

```text
profitRatePpm = unrealizedProfitUnits / notionalUnits * 1_000_000
effectiveLeveragePpm = notionalUnits / positionMarginUnits * 1_000_000
priorityScorePpm = profitRatePpm * effectiveLeveragePpm / 1_000_000
```

If `positionMarginUnits` is zero, the position receives the maximum leverage score and moves to the front of the queue.

## Core Flow

```text
account_deficits(asset, deficitUnits)
  -> insurance fund has zero balance for asset
  -> deficit row is older than minDeficitAgeMs
  -> surprising-adl-provider locks deficit row
  -> selects profitable same-asset ADL queue candidates
  -> locks target account_positions row with FOR UPDATE SKIP LOCKED
  -> reduces target position at latest mark price
  -> releases proportional position margin
  -> records ADL_REALIZED_PNL for target
  -> transfers ADL coveredUnits from target to deficit coverage
  -> reduces deficit user's account_deficits
  -> writes adl_events
```

The provider does not publish order commands and does not pretend ADL is a normal book trade. ADL is a system settlement event after liquidation and insurance are insufficient.
The execution path requires target position updates, margin release, balance updates, deficit updates, account ledger writes, and `adl_events` writes to all succeed; any expected write returning 0 fails the transaction.
Target position-margin release uses a guarded `margin_units >= releaseUnits` update; inconsistent margin state rolls the ADL transaction back instead of allowing negative position margin.

## Multi-Node Safety

- Multiple ADL providers can run at the same time.
- Deficit rows are locked with `FOR UPDATE SKIP LOCKED`.
- Candidate positions are re-read and locked before execution.
- The scanner only claims deficits when `insurance_fund_balances.balance_units = 0` for the asset.
- Account ledger IDs use `account_sequences`; ADL audit IDs use `adl_sequences`.
- Queue selection and execution re-check require a fresh mark price within `surprising.adl.scanner.max-mark-age-ms`.
- Execution re-locks the insurance fund balance. If the fund was topped up after scanning, ADL skips the execution and leaves the deficit for insurance first.

## API

Query the current ADL queue:

```bash
curl 'http://localhost:9091/api/v1/adl/queue?asset=USDT&limit=100'
```

Query ADL events:

```bash
curl 'http://localhost:9091/api/v1/adl/events?asset=USDT&limit=100'
curl 'http://localhost:9091/api/v1/adl/events?userId=1001&limit=100'
```

Admin ADL queries must go through the admin gateway with an admin bearer token:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/adl/admin/queue?asset=USDT&limit=100&sort=priorityScorePpm.desc'

curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://localhost:8080/api/v1/admin/gateway/adl/admin/events?asset=USDT&limit=100&sort=createdAt.desc'
```

The admin ADL queue supports live-ranking cursor paging with `priorityScorePpm.desc`; ADL events support
`createdAt.desc` and `createdAt.asc`. Responses keep `positions/events/count` and add `nextCursor`,
`hasMore`, `sort`, and `limit`.

## Database

Root [init.sql](../init.sql) creates:

- `adl_sequences`
- `adl_events`
  - `target_position_side` records the `NET`, `LONG`, or `SHORT` position bucket reduced by ADL.

Core indexes:

- `adl_events_deficit_user_time_idx`
- `adl_events_target_user_time_idx`
- `adl_events_asset_symbol_time_idx`

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
mvn -pl :surprising-adl-provider -am spring-boot:run
```

Port:

- `9091`: ADL service.

## Production Notes

- Run ADL only after account, risk, liquidation, and insurance providers are deployed.
- Keep `surprising.adl.scanner.min-deficit-age-ms` high enough to let insurance coverage run first.
- Keep `surprising.adl.scanner.max-mark-age-ms` close to the mark-price publish interval; stale mark prices must not trigger ADL.
- ADL precision depends on instrument `quantity_step_units`; coarse contract steps can over-close by one step.
- Monitor residual `account_deficits`, ADL event count, and the top queue users per asset.
- ADL is a last-resort solvency tool. Use alerts and manual operations whenever ADL starts firing.
- If a `failed to write ADL ...` error appears, check unexpected target-position changes, missing balance/deficit rows, account-ledger unique conflicts, and whether the transaction boundary was split.

## Verification

```bash
mvn -pl :surprising-adl-provider -am test
```
