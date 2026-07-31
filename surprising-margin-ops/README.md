# surprising-margin-ops

[English](README.md) | [简体中文](README_CN.md)

Margin-operation APIs and providers for risk, liquidation, funding, insurance, and ADL.

## Modules

- `surprising-risk-api` / `surprising-risk-provider`: risk snapshot query contracts, margin scanning, and liquidation candidate generation.
- `surprising-liquidation-api` / `surprising-liquidation-provider`: liquidation order query contracts and reduce-only liquidation execution.
- `surprising-funding-api` / `surprising-funding-provider`: perpetual funding-rate query contracts, publishing, settlement, and account-ledger integration.
- `surprising-insurance-api` / `surprising-insurance-provider`: insurance fund query/adjustment contracts, liquidation-fee collection, and bankruptcy deficit coverage.
- `surprising-adl-api` / `surprising-adl-provider`: ADL queue and event contracts, residual deficit allocation, and deleveraging execution.
- `surprising-margin-ops-provider`: combined deployable jar for the five providers above.

## Combined Provider Deployment

`surprising-margin-ops-provider` starts the existing risk, liquidation, funding, insurance, and ADL provider components in one JVM. This is only a packaging merge:

- Business packages remain isolated under `com.surprising.risk`, `com.surprising.liquidation`, `com.surprising.funding`, `com.surprising.insurance`, and `com.surprising.adl`.
- The five services still coordinate through their existing PostgreSQL tables, Kafka topics, outbox rows, idempotency keys, leases, and sequences.
- Funding freezes settlement mark inputs once, persists a composite position cursor, and dispatches bounded keyset pages
  in separate transactions. Each page batch-inserts payments and account outbox commands; native cached PostgreSQL
  sequences remove the former per-payment sequence-row hotspot. Account results are consumed in batches and update
  settlement counters incrementally without rescanning all payments.
- Funding persistence is split by physical table for leases, sequences, rates, settlements, payments, and account
  outbox rows, with `FundingService` aggregating them transactionally. Only online safety paths for rate inputs,
  due-rate selection, settlement candidates, command recovery, and atomic payment completion retain cross-table SQL;
  each exception is documented in source.
- Insurance persistence is split by physical table for sequences, fund balances, fund ledger, deficit coverages,
  product-scoped deficits, legacy deficits, and account outbox rows. `InsuranceService` and
  `InsuranceCoverageReconciler` aggregate them transactionally. Only the recovery lock that correlates coverage rows
  with reserve/finalize command states retains cross-table SQL, with its reason documented in source.
- Risk consumes account position events in Kafka batches, keeps only the highest revision for each exact position, and
  scans each affected user/account/settlement-asset group once. Complete position events eliminate the former
  instrument target-resolution query; scheduled keyset scans remain the safety fallback.
- Risk persistence is split by physical table into account-snapshot, position-snapshot, liquidation-candidate,
  admin-rule, and outbox repositories. `RiskPersistenceService` aggregates those repositories inside business
  transactions. `RiskRepository` retains only authoritative real-time risk inputs whose position, instrument,
  balance, deficit, reservation, and risk-bracket data must share one database snapshot; each exception is documented
  in source.
- Liquidation persistence is also split by physical table: candidate, position lock, liquidation audit, admin action,
  trading order, order event, trading outbox, instrument default fee, and user fee each have a dedicated repository.
  `LiquidationService` and `LiquidationOrderPersistenceService` aggregate them transactionally so the trading order,
  accepted event, and outbox intents commit atomically. `LiquidationRepository` retains only real-time safety queries
  that require a shared database snapshot or an atomic state check, with each exception documented in source.
- Liquidation candidate timelines no longer JOIN the primary trading database. A future finance-operations module
  should consume liquidation, order, trade, and funds events into an independent query database for timelines,
  reconciliation, and operational reports.
- High-risk account aggregation and similar admin reports no longer query the primary trading database. A future
  finance-operations module must build event-driven projections in an independent database for cross-table queries,
  reconciliation, and operational reporting.
- Funding settlement timelines, cross-account reconciliation, and operational statistics must likewise be served
  from that independent finance-operations projection rather than new JOINs in the primary trading database.
- Insurance-fund history analysis, cross-user coverage reconciliation, and operational statistics belong in the same
  independent finance-operations database rather than expanded primary-database queries.
- No module reads another module's in-memory state directly.
- The original standalone provider jars remain available for split deployment.

The combined jar defaults to port `9088`, serving the existing API paths:

```text
/api/v1/risk
/api/v1/admin/risk
/api/v1/liquidations
/api/v1/funding
/api/v1/insurance
/api/v1/adl
```

When using the combined provider behind gateway, point all margin-operation routes to the same base URL:

```bash
export GATEWAY_ROUTE_RISK_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_LIQUIDATION_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_FUNDING_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_INSURANCE_BASE_URL=http://localhost:9088
export GATEWAY_ROUTE_ADL_BASE_URL=http://localhost:9088
```

## Local Run

Combined process:

```bash
mvn -pl :surprising-margin-ops-provider -am spring-boot:run
```

Standalone processes remain available:

```bash
mvn -pl :surprising-risk-provider -am spring-boot:run
mvn -pl :surprising-liquidation-provider -am spring-boot:run
mvn -pl :surprising-funding-provider -am spring-boot:run
mvn -pl :surprising-insurance-provider -am spring-boot:run
mvn -pl :surprising-adl-provider -am spring-boot:run
```

## Verification

```bash
mvn -pl :surprising-margin-ops-provider -am test
mvn -pl :surprising-margin-ops-provider -am -DskipTests package
```
