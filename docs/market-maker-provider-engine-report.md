# Market Maker Provider Scheduled Engine Full-Stack Smoke

Date: 2026-07-05

This report records a 60-second full-stack smoke where the real
`surprising-market-maker-provider` scheduled engine is enabled after all funding and
fixture setup is complete. This is stronger evidence than an external run-once loop
because the provider's own `@Scheduled` task drives quoting.

It remains a single-node smoke, not a production-duration stress test.

## Command

```bash
START_INFRA=false STOP_INFRA=false RESET_STATE=true START_PROVIDERS=true STOP_PROVIDERS=true \
RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false KEEP_TMP=true WS_TIMEOUT=1200 \
PAIR_COUNT=1 LOAD_CONCURRENCY=1 BOOK_DEPTH_LEVELS=2 \
FULL_STACK_RISK_BACKGROUND_LOAD_ENABLED=false \
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP=false \
MM_REFERENCE_MARKET_ENABLED=false MM_REFERENCE_MARKET_WEBSOCKET_ENABLED=false \
MM_TRADE_ENABLED=false \
MM_PROVIDER_CONTINUOUS_SECONDS=0 \
MM_PROVIDER_ENGINE_SECONDS=60 \
MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 \
MM_PROVIDER_ENGINE_TAKER_ORDERS=20 \
POSTGRES_PORT=55432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh
```

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.MQjxfE`

Run id: `1783193849023170000`

## Scope

- Real providers: instrument, candlestick, index-price, mark-price, order, matching,
  account, risk, liquidation, funding, insurance, ADL, websocket, trigger, gateway,
  and market-maker.
- Startup safety: market-maker starts with `engine.enabled=false` while accounts are
  funded and other scenarios run.
- Engine smoke: after the run-once quote smoke, the script stops the market-maker
  provider and restarts it with `surprising.market-maker.engine.enabled=true` and
  `cycle-delay-ms=100`.
- Taker path: ordinary user takers enter through gateway `trading`, then settle
  through order, matching, account, risk, and WebSocket.

## Key Results

- BTC test price base: `633550` ticks from the latest mark, safe offset `633`.
- Full-stack orders: `ACCEPTED=4`, `CANCELED=499`, `FILLED=92`, `REJECTED=3`.
- Full-stack trades: `58` trades, `219` quantity steps.
- Account processed trades: `58`.
- WebSocket captures: depth `1078`, mark `514`, funding `506`; private user captures
  included orders, matches, positions, positionRisk, and accountRisk events.

Scheduled-engine-only market-maker results, filtered from engine restart time:

- `CYCLE_SUCCESS=5`.
- `QUOTE_RECONCILED=10`.
- Submitted post-only quote orders: `400`.
- Canceled quote orders during refresh/cleanup: `320`.
- Rejected quote orders: `0`.
- Engine taker trades: `20` trades, `20` quantity steps.
- Engine taker trades account processed: `20`.
- Market-maker account orders after engine window: `CANCELED=400`, executed quantity steps `20`.
- Open market-maker quotes after cleanup: `0`.

Whole market-maker section including the earlier run-once smoke produced
`QUOTE_RECONCILED=12`, submitted quote orders `480`, canceled quote orders `320`,
and `CYCLE_SUCCESS=6`.

## Funds And Risk Invariants

- Negative account balances: `0`.
- Negative product balances: `0`.
- Negative isolated position margins: `0`.
- Zero-quantity positions with non-zero entry price: `0`.
- Invalid open interest rows: `0`.
- Market-maker/taker negative balances: `0`.
- Unpublished trading outbox rows: `0`.
- Unpublished account outbox rows: `0`.
- Failed trading/account outbox rows: `0`.
- Account deficit remaining sum: `0`.
- Product deficit remaining sum: `0`.
- Insurance coverages: `2`, requested `2000000`, covered `2000000`, remaining `0`.
- ADL events: `1`, requested `1000000`, covered `1000000`, remaining `0`.
- Trigger-provider log check: no `index price ticks unavailable` retry after adding
  the no-pending-trigger guard.

## Notes

The full-stack script now exposes:

- `MM_PROVIDER_ENGINE_SECONDS`
- `MM_PROVIDER_ENGINE_CYCLE_DELAY_MS`
- `MM_PROVIDER_ENGINE_TAKER_ORDERS`
- `MM_ENGINE_ENABLED`

Default behavior remains safe: market-maker starts with `engine.enabled=false`.
Scheduled-engine validation is opt-in and runs after funds and fixtures are ready.

This 60-second run first exposed a fixture-isolation problem: the isolated-margin
scenario left a live BTC position that the real risk/liquidation background services
could liquidate while the trailing-stop fixture was preparing close liquidity. The
script now closes that isolated position with real reduce-only gateway orders before
later trigger scenarios, so the smoke remains an end-to-end user-path test without
cross-scenario liquidity contamination.

Remaining gap: reference-market WebSocket calibration now has a separate short
full-stack smoke in `docs/market-maker-reference-market-report.md`, and an
account-provider restart window now has a separate short smoke in
`docs/market-maker-provider-engine-fault-report.md`. Production-grade evidence still
needs longer duration, multi-node, multi-broker, larger WebSocket fanout, and
Kafka/PostgreSQL plus multi-provider fault windows while the scheduled engine keeps
quoting.
