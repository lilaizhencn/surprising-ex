# Trading API

Shared order RPC contracts, DTOs, command/event models, and trading calculations. Implementations and their tests live in `surprising-gateway/src`, primarily `com.surprising.trading`; this module does not start an order provider.

## Processing boundary

`OrderService` normalizes requests and validates instrument rules. `AeronOrderCommandService` encodes the final request; `OrderAeronGateway` sends it to Core. Normal placement submits one authoritative `PLACE_ORDER`, not a separate preflight followed by placement. Explicit test/dry-run endpoints may preflight, but the result is not an authorization or cached reservation.

Core owns idempotency, reservation, matching, fees, and positions. Current order/account queries use Realtime read views and committed history where supported; an unavailable view does not silently fall back to Core.

## Integer units and prices

| Field | Meaning |
| --- | --- |
| `priceTicks` | Display price = ticks × price tick units / quote asset scale |
| `quantitySteps` | Display quantity = steps × quantity step units / base asset scale |
| `ppm` | Parts per million for rates and bands |
| Asset units | Integer smallest units of the relevant asset |

`MARKET` requires `priceTicks=0` and IOC/FOK. Core derives protection boundaries from its fresh canonical mark price; never disguise a reservation or mark price as an order limit. Linear notional uses price × quantity × multiplier; inverse notional uses quantity × quote-face-value multiplier. Use checked integer arithmetic, preserving operation-specific rounding and overflow rejection. Decimal parsing/formatting belongs at external boundaries, not financial execution.

Keep four price concepts distinct: client limit price, actual fragment execution price, conservative reservation price, and risk-reference mark price. Fresh-mark limit bands constrain aggressive buys/sells without rejecting passive prices on the other side of the market.

## Reservation and reduce-only capacity

Ordinary non-reduce-only orders reserve the full possible opening quantity at the reservation boundary plus the worst positive fee. Visible depth or predicted fill rate cannot reduce this reservation. Core releases unused amounts only through actual fill/cancel/reject/IOC/FOK transitions. Missing, negative, or overflowing reservations fail closed.

`PositionCloseCapacity` is Core's exclusive commitment keyed by user, instrument, margin mode, position side, and position revision. A long closes with SELL; a short closes with BUY. Active reduce-only orders cannot promise the same position quantity twice. Conflicts converge deterministically newest-first by Core sequence, then larger order ID; resolve affected commitments before submitting them to Matcher. A reduce-only flag skips new opening margin, not direction, ownership, product, asset, or capacity checks.

## Fees and amendments

Core freezes the accepted maker/taker rates. Separate role buckets accumulate executed notional and already-posted fees. The next fee delta is cumulative rounded fee minus the amount already posted; do not round each fragment independently or recompute history from later policy changes. Negative fees are explicit rebate credits in the relevant role bucket.

Cancel, replace, and amend preserve stable request/order identities and return command-specific terminal results. Read the original and replacement outcomes instead of inferring success from transport acceptance. Batch APIs preserve per-item results and units.

## Conditional and algorithmic orders

Triggers and trailing stops are Core command state. Generated execution follows ordinary matching and accounting. TWAP `intervalSeconds` is 1–86400 and must also satisfy configured duration/range constraints; TWAP uses IOC and clears irrelevant PostOnly input. ICEBERG ignores/clears interval input and reports zero, preserves GTC/GTX, and maps PostOnly to GTX. Invalid TIF values remain rejected.

## Build and verification

```bash
mvn -pl surprising-trading-api -am test
mvn -pl surprising-gateway -am test
```

Update affected HTTP/WebSocket consumers for contract changes. Verify actual order, reservation, fee, terminal-state, and recovery behavior; shared API compilation alone is insufficient.

## Architecture reference

See the [project architecture and class-level trading flows](../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
