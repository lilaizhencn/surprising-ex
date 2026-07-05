# Market-Maker Reference-Market WebSocket Full-Stack Smoke

Date: 2026-07-05

This report records a real full-stack smoke with market-maker reference-market
calibration enabled. The market-maker provider consumed external Binance/Bybit-style
WebSocket depth samples plus REST fallback, then generated normal post-only orders
through the same gateway/order/matching/account path as user traffic.

This is a single-node short smoke, not a production-duration stress test.

## Command

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true \
RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=0 \
MM_PROVIDER_ENGINE_SECONDS=30 \
MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 \
MM_PROVIDER_ENGINE_TAKER_ORDERS=5 \
POSTGRES_PORT=55432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh
```

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.W2VWYd`

Run id: `1783196395637870000`

## Scope

- Real providers: instrument, candlestick, index-price, mark-price, order, matching,
  account, risk, liquidation, funding, insurance, ADL, websocket, trigger, gateway,
  and market-maker.
- Reference market: `MM_REFERENCE_MARKET_ENABLED=true` and
  `MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true`.
- Maker path: `surprising-market-maker-provider` generated post-only GTX quotes;
  every quote still entered through order-provider and matching, without touching
  `exchange-core` internals.
- Taker path: ordinary user takers entered through gateway `trading`, then settled
  through matching/account/risk/WebSocket.

## Key Results

- BTC test price base: `632006` ticks from latest mark, safe offset `632`.
- WebSocket captures: depth `729`, mark `475`, funding `465`; private user captures
  included orders, matches, positions, positionRisk, and accountRisk events.
- Full-stack orders: `ACCEPTED=4`, `CANCELED=332`, `FILLED=77`, `REJECTED=3`.
- Full-stack trades: `43` trades, `204` quantity steps.
- Account processed trades: `43`.
- Market-maker run events: `CYCLE_SUCCESS=4`, `QUOTE_RECONCILED=8`, submitted quote
  orders `313`, canceled quote orders `157`, rejected quote orders `0`.
- Market-maker order summary after cleanup: `CANCELED=313`, executed quantity steps `5`.
- Engine taker trades: `5/5` filled.
- Engine taker trades account processed: `5/5`.
- Open market-maker quotes after cleanup: `0`.

Reference-market samples written by the provider:

| Transport | Source | Samples | Spread Ticks | Depth |
| --- | --- | ---: | ---: | ---: |
| REST | BINANCE_USDM | 2 | 1 | 20 x 20 |
| WEBSOCKET | BINANCE_USDM | 1 | 1 | 20 x 20 |
| WEBSOCKET | BYBIT_LINEAR | 1 | 1 | 20 x 20 |

## Funds And Risk Invariants

- Negative account balances: `0`.
- Negative product balances: `0`.
- Negative isolated position margins: `0`.
- Zero-quantity positions with non-zero entry price: `0`.
- Invalid open interest rows: `0`.
- Unpublished trading outbox rows: `0`.
- Failed trading outbox rows: `0`.
- Unpublished account outbox rows: `0`.
- Failed account outbox rows: `0`.
- Failed risk/funding outbox rows: `0`.
- Account deficit remaining sum: `0`.
- Product deficit remaining sum: `0`.
- Insurance coverages: `2`, requested `2000000`, covered `2000000`, remaining `0`.
- ADL events: `1`, requested `1000000`, covered `1000000`, remaining `0`.

## Fix From First Attempt

The first reference-market attempt (`/tmp/surprising-full-stack-real-config.o1CyTa`)
exposed a real calibration bug: external exchange depth quantities were mapped up to
the provider reference-market `max-quantity-steps` and could exceed the local smoke
maker accounts' funded scale. The provider submitted 80 quotes, but 18 were rejected
with `insufficient available margin`.

`QuotePlanner` now treats external quantities as calibration input, but caps each
local quote's quantity at the strategy `baseQuantitySteps`. Price distances still
follow the reference book. This keeps the local maker's risk budget authoritative
while still using Binance/OKX/Bybit-style depth for price and relative size signals.

## Notes

`market_maker_reference_samples` is observability-only evidence. It records source,
transport, depth, best bid/ask, mid, and spread per strategy cycle. It is not used as
a source of truth for balances, positions, margin reservations, deficits, or account
settlement.

Remaining gap: this run proves reference-market WebSocket calibration in a real
single-node full-stack sample. A longer 180-second single-node reference-market
scheduled-engine run is now recorded in
`docs/market-maker-reference-market-sustained-report.md`, and the first short
combined account-restart sample is recorded in
`docs/market-maker-reference-market-fault-report.md`. Production-grade evidence
still needs multi-node market-maker providers, multi-broker Kafka, larger private
WebSocket fanout, and Kafka/PostgreSQL plus multi-provider fault windows.
