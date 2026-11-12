# Derivatives Lifecycle

The API/provider modules combine funding, risk, liquidation, insurance, and ADL orchestration. Provider HTTP defaults to 9087. Core remains the authority for actual funds and positions; this process schedules work and persists workflow progress.

## Funding flow

1. `FundingRateInputRepository` loads product instrument/mark inputs and validates versions.
2. `FundingService.publishRates` acquires the database lease, calculates rates, and publishes product-scoped facts.
3. `FundingService.settleDueRates` verifies maintenance state and advances a stable settlement ID/cursor in Core.
4. `FundingAeronGateway` uses `DerivativesAeronClient`; rejected and unknown outcomes remain distinct.

A completed older Core period permits the current period to begin at cursor zero. An incomplete older period or a newer Core period must not be silently overwritten. Keep historical predicted-rate inputs needed to settle downtime windows. Cache consumption progress does not itself represent funds settlement.

## Shared resources and scheduling

The process shares a datasource, instrument snapshot cache, and mark-price cache. Risk, liquidation, ADL, funding, and insurance share `DerivativesAeronClient`; it is closed by the application owner, not individual adapters. Product identity is validated at startup. Existing funding tables/migrations must be present in the shared database.

Lifecycle scheduling and funding scheduling use separate pools so funding waits do not consume every lifecycle scheduler thread. They still share JVM, client, database, CPU, and memory resources. Funding components load only for linear/inverse perpetual products; a product mismatch fails startup.

## Core execution boundaries

`DerivativesLifecycleMaintenanceTask` coordinates bounded risk and lifecycle work. Core's `RiskScanner`, `RiskMarginCalculator`, `LiquidationService`, `InsuranceFundCommands`, `TreasuryRuntime`, `AdlService`, and `ContractExpirySettlementService` perform actual ordered state transitions. Preserve resumable cursors, cancellation budgets, frozen funding inputs, exact amounts, command IDs, and unknown-result handling.

Funding payment/history and liquidation queries use committed facts and product-scoped cursors. Do not manufacture missing history from a current mark price. Administrative int64 values require exact parsing and no floating-point conversion.

## Build and verification

```bash
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider -am test
```

Configure product, datasource, Kafka, and Aeron endpoints. Verify funding periods, risk continuation, cancellation/trigger progression, insolvency convergence, funds conservation, and restart/resume for the affected scope.

## Architecture reference

See the [project architecture and class-level trading flows](../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
