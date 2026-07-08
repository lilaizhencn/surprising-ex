# Product-Line Split and Delivery/Options Implementation Plan

English | [简体中文](product-line-split-plan_CN.md)

## Current State

`surprising-ex` has moved from a single perpetual-futures path toward product-line-isolated trading. The codebase and schema are still shared, but business state is separated by `ProductLine`, account type, Kafka topic, consumer group, provider startup configuration, and gateway routing.

The currently validated product lines are:

| Product line | `ProductLine` | Account type | Contract type | Topic namespace | Status |
| --- | --- | --- | --- | --- | --- |
| Spot | `SPOT` | `SPOT` | `SPOT` | `surprising.spot.*.v1` | Order entry, matching, asset exchange, reservation release, product ledger |
| USDT perpetual | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `LINEAR_PERPETUAL` | `surprising.linear-perp.*.v1` | Margin, funding, risk, liquidation, insurance fund, ADL |
| USDT delivery futures | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `LINEAR_DELIVERY` | `surprising.linear-delivery.*.v1` | Delivery instrument, isolated matching flow, cash delivery event, position closeout |
| European options | `OPTION` | `OPTION` | `VANILLA_OPTION` | `surprising.option.*.v1` | Option instrument, premium/margin accounting, European exercise event, position closeout |

`INVERSE_PERPETUAL` and `INVERSE_DELIVERY` are already represented in enums, account types, and topic mapping. The current process-level smoke suite focuses on `SPOT`, `LINEAR_PERPETUAL`, `LINEAR_DELIVERY`, and `OPTION`.

## Principles

- Shared components must be business-neutral: product-line API, instrument metadata, long fixed-point units, matching wrapper, event model, gateway routing, and test utilities.
- Business state must be isolated: order books, Kafka topics, consumer groups, account type, settlement strategy, risk scans, liquidation candidates, and lifecycle events.
- The current phase intentionally keeps one shared provider codebase. Separate product-line instances are started by configuration with `product-line` and `product-topics-enabled`.
- Accounting safety has priority over abstraction purity. Every product line must prove balances, ledgers, positions, margin, liquidation fees, funding payments, delivery/exercise records, and insurance credits independently.

## Runtime Boundary

```text
SPOT:
  order-provider -> matching-provider -> account spot settlement

LINEAR_PERPETUAL:
  order-provider -> matching-provider -> account derivative settlement
  -> risk -> liquidation -> insurance -> adl
  -> funding

LINEAR_DELIVERY:
  order-provider -> matching-provider -> account derivative settlement
  -> risk -> liquidation -> insurance -> adl
  -> delivery settlement event

OPTION:
  order-provider -> matching-provider -> account derivative settlement
  -> risk -> liquidation -> insurance -> adl
  -> option exercise event
```

The matching provider still uses the same `exchange-core` wrapper, but a product-line instance consumes only its own `order.commands` topic and publishes only its own `match.results`, `match.trades`, and `orderbook.depth` topics. Consumers validate the actual Kafka topic against the configured `ProductLine` to prevent cross-product consumption.

## Shared Components

- `surprising-product-api`: `ProductLine`, account-type mapping, contract-type mapping, product topic names, and consumer groups.
- `surprising-instrument`: unified `SPOT`, `PERPETUAL`, `DELIVERY`, and `OPTION` instrument metadata, including expiry, delivery, strike, and option type fields.
- `surprising-trading`: order entry, trigger orders, algo orders, exchange-core wrapper, and product-line topic routing.
- `surprising-account`: basic balances, product balances, ledgers, product ledgers, positions, margin, funding, delivery, and exercise accounting.
- `surprising-risk` / `surprising-liquidation` / `surprising-insurance` / `surprising-adl`: shared margin-product risk, liquidation, and deficit handling, isolated by product line and account type.
- `surprising-gateway` / `surprising-websocket`: client-facing REST routing and realtime subscriptions by `productLine`.

## Delivery Futures Model

Delivery futures reuse most of the perpetual path: order entry, matching, margin, risk, and liquidation. The main difference is lifecycle:

1. Instrument rows include `expiry_time`, `delivery_time`, and `settlement_method`.
2. Before expiry, the symbol enters reduce-only mode and rejects new opening exposure.
3. At expiry, matching for that symbol is stopped and open orders are canceled.
4. A lifecycle event is published to `surprising.linear-delivery.delivery.settlements.v1`.
5. Account settlement closes open positions at the settlement price, writes `DELIVERY_SETTLEMENT` ledger entries, releases margin, and returns position quantity to zero.
6. Gateway, WebSocket, and admin pages display delivery status, settlement price, delivery ledger, and final balances.

## Options Model

The current options path is cash-settled European vanilla options. Early exercise is intentionally out of scope for the first implementation:

1. Instrument rows use `VANILLA_OPTION` and store underlying, expiry, delivery time, strike, call/put type, and exercise style.
2. Buyers pay premium; their maximum loss is the premium.
3. Sellers post margin; portfolio margin, Greeks, and volatility-surface risk are later enhancements.
4. At expiry, CALL payoff is `max(underlying settlement price - strike, 0)` and PUT payoff is `max(strike - underlying settlement price, 0)`.
5. A lifecycle event is published to `surprising.option.option.exercises.v1`.
6. Account settlement writes `OPTION_PREMIUM` and `OPTION_EXERCISE` ledger entries, releases margin, and returns position quantity to zero.

## Topic Model

Product-line topics are generated by `ProductTopicNames`:

```text
surprising.<product-segment>.order.commands.v1
surprising.<product-segment>.order.events.v1
surprising.<product-segment>.match.results.v1
surprising.<product-segment>.match.trades.v1
surprising.<product-segment>.orderbook.depth.v1
surprising.<product-segment>.account.position.events.v1
surprising.<product-segment>.account.liquidation-fee.events.v1
surprising.<product-segment>.risk.account.events.v1
surprising.<product-segment>.risk.position.events.v1
surprising.<product-segment>.liquidation.candidates.v1
surprising.<product-segment>.funding.rate.v1
surprising.<product-segment>.delivery.settlements.v1
surprising.<product-segment>.option.exercises.v1
```

Create topics locally:

```bash
DRY_RUN=true ./scripts/create-topics.sh
PRODUCT_TOPIC_LINES="spot linear-perp linear-delivery option" ./scripts/create-topics.sh
```

## Local Verification

Run one product line at a time. The four matching businesses do not need to run together:

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

The smoke suite covers:

- Market-maker quoting plus ordinary-user API taker flow.
- Open, self-close, cancellation, and order-control APIs.
- Position creation, risk scan, liquidation, liquidation fee, and insurance credit for margin products.
- Perpetual funding.
- Delivery settlement event and position closeout.
- Option exercise event, premium/exercise ledgers, and position closeout.
- Spot asset exchange, no derivative position creation, and reservation release.
- Independent funds reconciliation at the end of each product-line run.

See [Product-Line Funds Conservation and Account Reconciliation](product-line-testing-and-funds-reconciliation.md).

## Remaining Risk and Follow-Up Work

- Inverse perpetual and inverse delivery are modeled, but still need the same process-level smoke coverage as the four currently validated lines.
- Options currently use a single-leg European cash-settlement model. Portfolio margin, Greeks, volatility surface risk, risk limits, and production market making are later work.
- Delivery futures still need production expiry scheduling, multi-source TWAP settlement price, manual review, task reruns, and exception reports.
- Web, admin web, and Flutter iOS/Android must keep `productLine`, order panels, positions, ledgers, and lifecycle pages aligned.
- Before production, run multi-node, multi-broker, long-duration stress tests, failover drills, alert threshold calibration, and wallet/clearing-boundary review.
