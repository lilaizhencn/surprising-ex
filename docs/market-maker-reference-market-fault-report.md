# Market-Maker Reference-Market Account-Restart Fault Smoke

Date: 2026-07-05

This report records a real full-stack smoke where the market-maker provider uses
reference-market WebSocket calibration while its scheduled engine keeps quoting
through an account-provider restart window. The goal is to prove the combined path:
external Binance/OKX/Bybit-style order-book calibration, provider scheduled quoting,
ordinary-user gateway taker fills, account restart recovery, funds correctness, and
outbox drainage.

This is still a single-node short smoke, not a production-duration multi-node stress
test.

## Command

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true \
RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1500 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=0 \
MM_PROVIDER_ENGINE_SECONDS=90 \
MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 \
MM_PROVIDER_ENGINE_TAKER_ORDERS=10 \
MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true \
MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS=10 \
MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS=10 \
POSTGRES_PORT=55432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh
```

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.8XwMJ0`

Run id: `1783199230759000000`

## Scope

- Real providers: instrument, candlestick, index-price, mark-price, order, matching,
  account, risk, liquidation, funding, insurance, ADL, websocket, trigger, gateway,
  and market-maker.
- Reference market: `MM_REFERENCE_MARKET_ENABLED=true` and
  `MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true`.
- Market-maker path: the provider starts in safe mode, then the script restarts it
  with `surprising.market-maker.engine.enabled=true` and `cycle-delay-ms=100`.
- Fault window: after scheduled-engine quotes are accepted, the script stops the real
  account provider, submits 10 ordinary-user taker orders through gateway `trading`,
  waits for matching fills, waits 10 seconds, restarts the account provider, and then
  requires a later market-maker `CYCLE_SUCCESS`.
- User path: taker orders enter through gateway, order-provider, matching, Kafka
  match trades, account-provider settlement, risk, and WebSocket. No database-only
  shortcut is used for fills or account settlement.

## Key Results

- WebSocket captures: depth `1057`, mark `529`, funding `519`; private user captures
  included orders, matches, positions, positionRisk, and accountRisk events.
- Full-stack orders: `ACCEPTED=4`, `CANCELED=492`, `FILLED=85`, `REJECTED=3`.
- Full-stack trades: `48` trades, `209` quantity steps.
- Account processed trades: `48`.
- Market-maker run events: `CYCLE_SUCCESS=6`, `QUOTE_RECONCILED=12`, submitted quote
  orders `476`, canceled quote orders `315`, rejected quote orders `0`.
- Expected temporary cycle failures while account was down: `CYCLE_FAILED=144`.
- Market-maker order summary after cleanup: `CANCELED=473`, `FILLED=3`, executed
  quantity steps `10`.
- Engine taker trades: `10/10` filled.
- Engine taker trades account processed after account restart: `10/10`.
- Open market-maker quotes after cleanup: `0`.

Reference-market samples written by the provider:

| Transport | Source | Samples | Spread Ticks | Depth |
| --- | --- | ---: | ---: | ---: |
| REST | BINANCE_USDM | 2 | 1 | 20 x 20 |
| WEBSOCKET | BINANCE_USDM | 142 | 1 | 20 x 20 |
| WEBSOCKET | BYBIT_LINEAR | 4 | 1 | 20 x 20 |
| WEBSOCKET | OKX_SWAP | 2 | 1 | 20 x 20 |

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

## Notes

This run combines the two previously separate short smokes:

- `docs/market-maker-reference-market-report.md` proved enabled reference-market
  WebSocket quoting and settlement.
- `docs/market-maker-provider-engine-fault-report.md` proved a scheduled-engine
  account-provider restart window without reference-market calibration.

The combined run proves both at once on a single-node local stack. Production-grade
evidence still needs longer duration, larger taker flow, multi-node market-maker and
account providers, multi-broker Kafka, PostgreSQL fault windows, and larger private
WebSocket fanout.
