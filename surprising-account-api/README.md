# Account API

Shared account/position RPC contracts, DTOs, and settlement calculations. Implementations are in Gateway under `com.surprising.account.provider`; no standalone account provider is deployed.

## Financial authority

Core Account Lanes own balances, frozen amounts, reservations, positions, position margin, and leverage. Balance/margin adjustments, orders, fills, funding, liquidation, delivery, and exercise enter as ordered Core commands. PostgreSQL stores workflow records, audit, reconciliation, and committed projections; repositories do not adjudicate live balances.

Ordinary user-facing balance and position queries use Realtime read views. Explicit Core command and administrative/control queries use account Aeron gateways where defined. Do not restore online financial authority from old SQL balance tables. Core restart restores a snapshot and later cluster-log commands.

## Settlement contract

All trading amounts use checked `long` values in asset units, ticks, steps, or ppm. Preserve signed PnL, precise rounding, overflow handling, and ownership. Spot settlement transfers the actual traded assets. Derivative fills update positions, opening averages, order-to-position margin, realized PnL, and frozen fees. Options additionally handle premiums and product-specific exercise cash flows.

Funds postings retain immutable operation identities and explicit owner/asset/account dimensions. Enforce per-asset conservation; do not hide a missing debit/credit by netting unrelated accounts. Treasury, insurance, rounding residuals, deficit resolution, and liquidation work debt are distinct accounting responsibilities. Preserve each operation's frozen inputs and rounding contract.

## Transfers and idempotency

`ProductTransferCoordinator` persists cross-product workflow progress. Product account commands use stable IDs through transfer-out, transfer-in, and completion. Unknown network outcomes resume with the same identity; a new ID must not replace unresolved work. Reconciliation advances persisted intent only after confirming the corresponding Core result.

Recent command results and durable funds identities serve different retention needs. Durable funds identity capacity exhaustion fails explicitly. Source-sequence progress alone cannot replace funds identity verification. Core snapshots and log recovery preserve those identities.

## Persistence and queries

`AccountQueryService` handles ledger, transfer, and adjustment records. Core facts must pass source continuity and committed-watermark checks before derived projections become usable. Delayed exports cannot rewrite Core authority. Public int64 identifiers/amounts must retain exact precision across JSON consumers.

## Build and verification

```bash
mvn -pl surprising-account-api -am test
mvn -pl surprising-gateway -am test
```

Financial changes require conservation, holds, positions, posting identity, timeout/unknown-result, duplicate/replay, and recovery tests for the affected product lines.

## Architecture reference

See the [project architecture and class-level trading flows](../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
