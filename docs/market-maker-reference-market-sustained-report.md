# Market-Maker Reference-Market 180s Sustained Full-Stack Smoke

Date: 2026-07-05

This report records a 180-second single-node full-stack smoke where the real
market-maker provider used reference-market calibration and the scheduled engine
kept quoting while ordinary taker orders entered through the public gateway.

The run uses the same HTTP/WebSocket/Kafka/PostgreSQL path as a user-facing
deployment. It does not call matching or account internals directly.

## Command

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true \
RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=2400 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=true MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=true \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=0 \
MM_PROVIDER_ENGINE_SECONDS=180 \
MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 \
MM_PROVIDER_ENGINE_TAKER_ORDERS=30 \
MM_PROVIDER_ENGINE_ACCOUNT_RESTART=false \
POSTGRES_PORT=55432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
  ./scripts/full-stack-real-config-smoke.sh
```

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.xCbq79`

## Fix Verified Before This Run

The first 180-second attempt exposed a real WebSocket fanout bug:

- A valid spot symbol, `BTC-USDT-SPOT`, produced an order-book depth event.
- `SubscriptionTopic` only accepted two-leg symbols such as `BTC-USDT`.
- WebSocket order-book fanout threw `invalid symbol: BTC-USDT-SPOT`.
- The Kafka listener retried/seeked the depth partition, which replayed older
  `BTC-USDT` depth events into the same client stream and caused a sequence
  rollback.

The fix aligns WebSocket subscription symbol validation with the rest of the
project: `[A-Z0-9][A-Z0-9_-]{1,63}`. Regression tests now cover
`BTC-USDT-SPOT` depth subscription and fanout.

The smoke script was also tightened so final outbox checks require all pending
rows to be drained, not only rows with `attempts > 0`.

A follow-up 30-second full-stack regression then exposed and fixed an ADL/OI
consistency gap:

- ADL correctly reduced the profitable target account's ETH long position by
  `1` step.
- `trading_symbol_open_interest` still showed the pre-ADL long quantity because
  ADL updated `account_positions` directly without applying the same symbol OI
  delta used by normal account settlement.
- ADL now updates `trading_symbol_open_interest` in the same transaction as the
  target position reduction.
- The full-stack smoke now recomputes long/short/open interest from
  `account_positions` and fails if it differs from `trading_symbol_open_interest`.

## Key Results

- WebSocket depth stream: `2794` events, monotonic through sequence `3130`.
- WebSocket mark stream: `608` events.
- WebSocket funding stream: `598` events.
- Full-stack orders: `ACCEPTED=4`, `CANCELED=1352`, `FILLED=102`,
  `REJECTED=3`.
- Full-stack trades: `68` trades, `229` quantity steps.
- Account processed trades: `68`.
- Market-maker run events: `CYCLE_SUCCESS=19`, `QUOTE_RECONCILED=38`.
- Market-maker submitted quote orders: `1333`.
- Market-maker canceled quote orders: `1185`.
- Market-maker rejected quote orders: `0`.
- Engine taker orders: `30/30` filled and account-processed.
- Open market-maker quotes after cleanup: `0`.

Reference-market samples written by the provider:

| Transport | Source | Samples | Spread Ticks | Depth |
| --- | --- | ---: | ---: | ---: |
| REST | BINANCE_USDM | 2 | 1 | 20 x 20 |
| WEBSOCKET | BINANCE_USDM | 17 | 1 | 20 x 20 |

The shorter account-restart run in
`docs/market-maker-reference-market-fault-report.md` separately covered
Binance/OKX/Bybit streaming samples during an account-provider restart window.

## Funds And Risk Invariants

- Negative account balances: `0`.
- Negative product balances: `0`.
- Negative isolated position margins: `0`.
- Non-zero `account_deficits`: `0`.
- Non-zero `account_product_deficits`: `0`.
- Invalid open interest rows: `0`.
- Insurance coverages: `2`, requested `2000000`, covered `2000000`,
  remaining `0`.
- ADL events: `1`, requested `1000000`, covered `1000000`, remaining `0`.
- Runtime final outbox assertion: trading/account/risk/funding pending rows all
  reached `0` before the script printed `Full-stack real-config smoke passed`.

## Follow-Up Regression

Command: same shape as the 180-second run, with
`MM_PROVIDER_ENGINE_SECONDS=30` and `MM_PROVIDER_ENGINE_TAKER_ORDERS=5`.

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.pSoyiB`

Additional evidence from the follow-up run:

- WebSocket depth stream: `881` events.
- Market-maker run events: `CYCLE_SUCCESS=5`, `QUOTE_RECONCILED=10`.
- Reference-market samples: `REST|BINANCE_USDM=2`,
  `WEBSOCKET|BINANCE_USDM=2`, `WEBSOCKET|OKX_SWAP=1`.
- Orders: `ACCEPTED=4`, `CANCELED=408`, `FILLED=77`, `REJECTED=3`.
- Trades/account settlement: `43/43`.
- Insurance coverages: `2`, requested `2000000`, covered `2000000`,
  remaining `0`.
- ADL events: `1`, requested `1000000`, covered `1000000`, remaining `0`,
  closed `1` step.
- Post-stop outbox inspection: trading/account/risk/funding pending rows all
  `0`.
- Post-stop funds inspection: negative account balances, product balances,
  position margins, account deficits, product deficits, and invalid OI rows all
  `0`.
- Final OI after ADL: `ETH-USDT long=19 short=20 open=20`, matching
  recomputed account positions.

The previous post-stop funding residue was removed from the smoke path by
quiescing funding calculation through the provider runtime config before final
WebSocket/accounting assertions.

## Remaining Gaps

This is stronger than the earlier short smoke, but it is still a single-node,
single-Kafka-broker, single-PostgreSQL run. Production-grade evidence still
needs multi-node market-maker/account/WebSocket providers, multi-broker Kafka,
larger private WebSocket fanout, longer external reference-market outages, and
PostgreSQL/Kafka fault windows.
