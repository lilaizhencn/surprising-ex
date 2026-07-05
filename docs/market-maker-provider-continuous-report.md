# Market Maker Provider Continuous Full-Stack Smoke

Date: 2026-07-05

This report records the first full-stack smoke where `surprising-market-maker-provider`
is exercised as the quoting engine through the same gateway path that users use for
orders. It is a short functional smoke, not a production-duration stress test.

## Command

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true \
RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=900 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=8 \
MM_PROVIDER_CONTINUOUS_INTERVAL_SECONDS=1 \
MM_PROVIDER_CONTINUOUS_TAKER_ORDERS=2 \
POSTGRES_PORT=55432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh
```

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.KW6Dsn`

Run id: `1783190191489090000`

## Scope

- Real providers: instrument, candlestick, index-price, mark-price, order, matching,
  account, risk, liquidation, funding, insurance, ADL, websocket, trigger, gateway,
  and market-maker.
- Market-maker path: gateway `market-maker/run-once` invokes the market-maker
  provider quote engine; generated quotes enter gateway/order/matching/account as
  normal post-only orders.
- Taker path: ordinary user orders enter through gateway `trading` and settle through
  matching, account, risk, and WebSocket.
- The script now syncs BTC scenario prices from the latest mark before user order
  scenarios. This avoids false failures when the index/mark providers refresh from
  live Binance/OKX/Bybit REST prices while the script uses fixed test prices.

## Key Results

- BTC test price base: `631107` ticks from the latest mark, safe offset `631`.
- Full-stack orders: `ACCEPTED=4`, `CANCELED=181`, `FILLED=72`, `REJECTED=3`.
- Full-stack trades: `39` trades, `191` quantity steps.
- Account processed trades: `39`.
- Market-maker run events: `CYCLE_SUCCESS=2`, `QUOTE_RECONCILED=4`, submitted quote
  orders `160`, failed cycles `0`.
- Market-maker account orders: `CANCELED=160`, `FILLED=2`.
- Continuous taker trades against market-maker quotes: `2` trades, `2` account
  processed trades.
- WebSocket captures: depth `418`, mark `412`, funding `403`; private user captures
  included orders, matches, positions, positionRisk, and accountRisk events.

## Funds And Risk Invariants

- Negative account balances: `0`.
- Negative product balances: `0`.
- Negative isolated position margins: `0`.
- Zero-quantity positions with non-zero entry price: `0`.
- Invalid open interest rows: `0`.
- Open market-maker quotes after cleanup: `0`.
- Unpublished trading outbox rows: `0`.
- Unpublished account outbox rows: `0`.
- Failed trading/account/risk/funding outbox rows: `0`.
- Account deficit remaining sum: `0`.
- Product deficit remaining sum: `0`.
- Insurance coverages: `2`, requested `2000000`, covered `2000000`, remaining `0`.
- ADL events: `1`, requested `1000000`, covered `1000000`, remaining `0`.
- Liquidation candidates: `COMPLETED=1`, `CANCELED=2`.
- Funding settlements: `COMPLETED=1`; funding payments `13`, net amount `0`.

## Notes

An earlier run failed before reaching the continuous market-maker scenario because
the script used a fixed BTC price (`600000` ticks) while mark price had refreshed to
about `631000` ticks from live index data. A SELL IOC open order was correctly
rejected by limit-price protection. The smoke script now derives BTC order scenario
prices from the latest mark and keeps all same-price maker/taker scenarios within
the protection band.

Remaining gap: this is still a short single-node smoke. Reference-market WebSocket
calibration now has a separate short full-stack smoke in
`docs/market-maker-reference-market-report.md`; production-grade evidence still
needs multi-node, multi-broker, larger WebSocket fanout, longer duration, and
provider/Kafka/PostgreSQL fault windows while market making continues.
