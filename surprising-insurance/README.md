# surprising-insurance

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange insurance-fund module. It manages the perpetual insurance fund, receives actually collected liquidation fees, and covers explicit bankruptcy deficits created by account settlement.

## Modules

- `surprising-insurance-api`: insurance RPC contracts and long-based response models.
- `surprising-insurance-provider`: fund adjustment API, liquidation-fee consumer, deficit coverage worker, fund ledger, and account-ledger integration.

## Long Model

Insurance uses long fixed-point asset units only:

- `amountUnits`: insurance fund deposit or withdrawal in the asset smallest unit.
- `balanceUnits`: current insurance fund balance in the asset smallest unit.
- Liquidation fee events use `amountUnits` as a positive collected amount. The event is already capped by account-provider to collateral that was actually collected from the liquidated user.
- `requestedUnits`: account deficit amount before coverage.
- `coveredUnits`: amount paid by the insurance fund.
- `remainingDeficitUnits`: user deficit still uncovered after the coverage pass.

For example, if `USDT` uses `scaleUnits = 100000000`, `100000000` means `1 USDT`.

## Core Flow

```text
account-provider
  -> account_deficits(userId, asset, deficitUnits)
  -> surprising-insurance-provider scanner
  -> SELECT ... FOR UPDATE SKIP LOCKED
  -> insurance_fund_balances(asset)
  -> insurance_deficit_coverages
  -> insurance_fund_ledger
  -> account_ledger_entries(reference_type=INSURANCE_COVERAGE)
```

The insurance provider does not change positions and does not create orders. Liquidation and matching decide the final position state; account settlement records any negative equity as `account_deficits`; insurance then absorbs that deficit when the fund has enough balance.

Liquidation-fee income uses a separate event path:

```text
account-provider
  -> account_ledger_entries(reference_type=LIQUIDATION_FEE)
  -> account_outbox_events
  -> Kafka surprising.account.liquidation-fee.events.v1, key=asset
  -> insurance_fund_balances(asset)
  -> insurance_fund_ledger(reference_type=LIQUIDATION_FEE, reference_id=tradeId:orderId)
```

Only actually collected liquidation fees are credited. If a liquidated account has no remaining collectible collateral, account-provider emits no fee event and insurance balance is not increased.

## Coverage Rules

- If the insurance fund balance is zero, the deficit row stays unchanged for a future scan.
- If the fund balance is smaller than the deficit, the provider partially covers the deficit and keeps the remaining amount in `account_deficits`.
- If the fund balance is enough, the provider fully clears the deficit.
- Coverage reduces explicit deficit. It does not credit `account_balances.available_units`.
- Fund adjustment requests are idempotent by `(referenceType, referenceId, asset)` only when the replayed `amountUnits` and `reason` match the original ledger row.
- Liquidation-fee events are idempotent by `LIQUIDATION_FEE + tradeId:orderId + asset`. Kafka replay validates the original amount and does not credit the fund twice.
- The coverage path requires the coverage row, fund deduction, deficit update, fund ledger, and account ledger to all write successfully; any expected write returning 0 fails the transaction.

## Multi-Node Safety

- Run multiple provider nodes.
- The scanner locks positive deficit rows with `FOR UPDATE SKIP LOCKED`, so nodes split work instead of covering the same deficit twice.
- The fund row is also locked before balance deduction.
- Fund ledger has a unique reference index to prevent duplicate admin adjustments, duplicate liquidation-fee events, or duplicate coverage ledger rows.
- Liquidation-fee events are keyed by asset, so all updates for one insurance fund asset can be serialized by Kafka partitioning before the PostgreSQL fund-row lock.
- Account ledger uses `account_sequences`, not insurance sequences, so ledger IDs remain global inside the account domain.
- A repeated admin fund adjustment reference returns the current balance without updating the fund again only for an identical payload; conflicting amount or reason fails the transaction.
- Deficit coverage uses the newly allocated `coverageId` as its reference; a unique-index conflict is treated as a sequence or replay invariant failure and the transaction rolls back.

## API

Deposit insurance fund:

```bash
curl -X POST 'http://localhost:9090/api/v1/insurance/admin/fund-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"asset":"USDT","amountUnits":1000000000000,"referenceId":"ops-deposit-20260701-1","reason":"INITIAL_FUND"}'
```

Withdraw insurance fund:

```bash
curl -X POST 'http://localhost:9090/api/v1/insurance/admin/fund-adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"asset":"USDT","amountUnits":-100000000,"referenceId":"ops-withdraw-20260701-1","reason":"CONTROLLED_WITHDRAWAL"}'
```

Query balances, ledger, and coverages:

```bash
curl 'http://localhost:9090/api/v1/insurance/balances?asset=USDT'
curl 'http://localhost:9090/api/v1/insurance/ledger?asset=USDT&limit=100'
curl 'http://localhost:9090/api/v1/insurance/coverages?userId=1001&asset=USDT&limit=100'
```

## Database

Root [init.sql](../init.sql) creates:

- `insurance_sequences`
- `insurance_fund_balances`
- `insurance_fund_ledger`
- `insurance_deficit_coverages`

Core indexes:

- `insurance_fund_ledger_reference_uidx`
- `insurance_fund_ledger_asset_time_idx`
- `insurance_coverages_user_time_idx`
- `insurance_coverages_asset_time_idx`

## Local Run

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-insurance-provider -am spring-boot:run
```

Port:

- `9090`: insurance service.

## Production Notes

- Protect `/admin/fund-adjustments` behind internal authentication, approval workflow, and audit logging.
- Seed the fund before production trading starts.
- Monitor `account_deficits.deficit_units`, `insurance_fund_balances.balance_units`, and partial coverage rows.
- Kafka liquidation-fee consumer fetch size is controlled by `surprising.insurance.kafka.max-poll-records` and defaults to `500`.
- Insurance is the second loss layer after user margin. `surprising-adl` handles residual losses when the insurance fund is depleted.
- Do not make insurance depend on external market data; it should consume finalized account deficits and finalized liquidation-fee collection events only.
- Never credit the insurance fund from estimated liquidation fees. Only account-provider can emit `LIQUIDATION_FEE_SETTLED` after real match settlement and actual collateral debit.
- If `failed to write insurance fund ledger insert` or a similar error appears, check sequence tables, unique-index conflicts, transaction boundaries, and manual database changes first.

## Verification

```bash
mvn -pl :surprising-insurance-provider -am test
```
