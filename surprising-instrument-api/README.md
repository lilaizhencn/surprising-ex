# Instrument and Product Contracts

Shared product identity, permanent instrument IDs, configuration snapshots, risk brackets, source definitions, and contract mathematics. `com.surprising.product.api` is part of this module; there is no separate `surprising-product-api` reactor module.

| Package | Responsibility |
| --- | --- |
| `com.surprising.product.api` | Six product identities and product configuration validation |
| `com.surprising.product.api.utils` | `InstrumentIds`, product topics, and consumer-group names |
| `com.surprising.instrument.api.model` | Contract configuration, risk brackets, price sources, and change/lifecycle events |
| `com.surprising.instrument.api.math` | Pure linear/inverse notional, PnL, and margin formulas |
| `com.surprising.instrument.api.cache` | Per-process snapshots and startup loading |
| `client`, `kafka`, `utils` | Query contracts and snapshot/event adapters |

## Configuration lifecycle

Instrument administration is implemented inside Gateway. Persist accepted configuration, publish versioned product-scoped facts, load consumer snapshots, and synchronize canonical configuration into the relevant Core. Core trading rules operate on the bound canonical instrument. Administrative persistence is not direct mutation of Core trading state.

Permanent instrument IDs, version/revision ordering, product identity, trading status, integer precision, fee policies, and lifecycle state must remain consistent across producers and consumers. Existing instruments must not be silently reassigned to a different identity or product. Retain the distinction between availability for new trading and historical/public lookup after delivery expiry.

## Mathematics boundary

`PerpetualContractMath` contains shared pure formulas. Core-specific adaptation and rejection behavior remain in Aeron service. Classes depending on `CoreInstrument`, Core state, account data, or runtime policy must not be moved wholesale into a shared API. Preserve signs, rounding, overflow, and exception contracts when extracting pure arithmetic.

Display price = `priceTicks * priceTickUnits / quoteAssetScale`; display quantity = `quantitySteps * quantityStepUnits / baseAssetScale`. Convert decimal external input once at the boundary. Do not mix linear and inverse multiplier meanings.

## Build and verification

```bash
mvn -pl surprising-instrument-api -am test
```

Shared contract changes require testing every affected consumer, including Gateway, Core, Price, Realtime, Lifecycle, and Maker. Verify startup snapshots, version continuity, dynamic changes, and product isolation.

## Architecture reference

See the [project architecture and class-level trading flows](../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
