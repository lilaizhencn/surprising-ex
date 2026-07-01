# surprising-funding

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange perpetual funding module. It calculates predicted funding rates, publishes funding-rate events for mark-price convergence, and settles due funding payments into account balances.

## Modules

- `surprising-funding-api`: funding RPC contracts and long-based response models.
- `surprising-funding-provider`: funding rate publisher, settlement engine, account ledger writer, and Kafka outbox publisher.

## Long Model

Funding core uses long fixed-point values:

- `fundingRatePpm`: funding rate in parts per million. `100` means `0.000100`, or `0.01%`.
- `premiumRatePpm`: `(markPrice - indexPrice) / indexPrice * 1_000_000`.
- `interestRatePpm`: instrument interest rate converted to ppm.
- `amountUnits`: funding payment in settlement-asset smallest units.
- Payment notional is always converted to settlement-asset units before applying `fundingRatePpm`. Linear contracts use mark-price notional; inverse contracts convert quote face value to settlement coin at the current mark price. The conversion uses shared `PerpetualContractMath` long formulas from `surprising-instrument-api`.

The provider publishes the existing `surprising.perp.funding.rate.v1` JSON shape for mark-price service compatibility. The Kafka payload contains a decimal `fundingRate`, but internal calculation and settlement use long ppm.

## Rate Calculation

For each `TRADING` instrument:

```text
premiumRatePpm = (markPrice - indexPrice) / indexPrice * 1_000_000
rawRatePpm = interestRatePpm + premiumRatePpm
fundingRatePpm = clamp(rawRatePpm, fundingRateFloorPpm, fundingRateCapPpm)
nextFundingTime = next UTC funding interval boundary
```

Inputs:

- `instruments`: funding interval, interest rate, cap, floor.
- `price_mark_ticks`: latest mark/index price plus `mark_price_units`; settlement converts this long value to
  version-specific ticks.
- `price_symbol_leases` and `price_symbol_sequences`: active-active symbol ownership and monotonic sequence.

## Settlement

At funding time, one settlement is created per `symbol + fundingTime`.

Payment direction follows standard perpetual convention:

- Positive funding rate: longs pay shorts.
- Negative funding rate: shorts pay longs.

The provider:

- Computes each position notional from `account_positions`, the position's pinned instrument version, mark price, and `account_asset_scales`, branching by `contract_type`.
- Uses each position's `instrument_version` for notional conversion, so funding settlement does not reinterpret existing positions with a newer instrument version.
- Writes one `funding_payments` row per user.
- Writes `account_ledger_entries` with `reference_type = FUNDING`.
- Applies payments to `account_balances` and `account_deficits`.
- Uses `funding_settlements(symbol, funding_time)` uniqueness for idempotency.
- Executes each due `symbol + fundingTime` settlement in its own database transaction. A failed settlement rolls back and is retried on a later scan, while other due settlements in the same scheduler pass can still complete.

## API

```bash
curl 'http://localhost:9089/api/v1/funding/rates/latest?symbol=BTC-USDT'
curl 'http://localhost:9089/api/v1/funding/rates/history?symbol=BTC-USDT&limit=100'
curl 'http://localhost:9089/api/v1/funding/settlements/latest?symbol=BTC-USDT'
curl 'http://localhost:9089/api/v1/funding/payments?userId=1001&symbol=BTC-USDT&limit=100'
```

## Database

Root [init.sql](../init.sql) creates:

- `funding_sequences`
- `funding_rate_ticks`
- `funding_settlements`
- `funding_payments`
- `funding_outbox_events`

Core indexes:

- `funding_rate_ticks_symbol_time_idx`
- `funding_settlements_symbol_time_uidx`
- `funding_payments_settlement_user_uidx`
- `funding_payments_user_time_idx`
- `funding_outbox_pending_idx`

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-funding-provider -am spring-boot:run
```

Port:

- `9089`: funding service.

## Production Notes

- Run at least two funding-provider instances. Symbol ownership uses PostgreSQL leases.
- Funding settlement is idempotent by `symbol + fundingTime`; duplicate Kafka or scheduler execution does not double-settle.
- Settlement must run after account positions are updated from matching trades and mark price is fresh.
- Funding payments can create or reduce `account_deficits`; balance columns never become negative.
- Funding charges may debit `available_units` and position-margin-backed `locked_units`, but they must not consume open-order reservation locks. Any position-backed locked debit reduces `account_position_margins` in the same transaction.
- Funding-rate inserts and `funding_outbox_events` inserts run in the same transaction. Any skipped insert/update fails fast so the database cannot commit a rate without the corresponding outbox event.
- Funding settlement transactions are isolated per `symbol + fundingTime`; one bad account row or invariant failure must not roll back unrelated symbols.
- `funding_payments(settlement_id, user_id)` conflicts are valid idempotent skips. Once a payment is inserted, account ledger, balance, deficit, and settlement-completion updates must touch their expected rows or the entire settlement rolls back.
- Outbox `published_at` and retry-failure markers must also update the expected row. A missing row indicates corrupted outbox state and should fail the process for investigation.
- Insurance fund and ADL are separate modules. Funding does not absorb bankruptcy losses.

## Verification

```bash
mvn -pl :surprising-funding-provider -am test
```
