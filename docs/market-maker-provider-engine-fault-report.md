# Market-Maker Provider Scheduled Engine Account-Restart Fault Smoke

Date: 2026-07-05

This report records a real full-stack fault-window smoke where the
`surprising-market-maker-provider` scheduled engine keeps running while the real
account provider is stopped and restarted. The goal is to verify that user-path
orders can still be matched during the account outage, account settlement catches up
after restart, and market-maker temporary cycle failures do not leave bad funds,
open quote residue, or unpublished outbox rows.

This is a single-node short fault smoke, not a production-duration failover test.

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
MM_PROVIDER_ENGINE_SECONDS=45 \
MM_PROVIDER_ENGINE_CYCLE_DELAY_MS=100 \
MM_PROVIDER_ENGINE_TAKER_ORDERS=5 \
MM_PROVIDER_ENGINE_ACCOUNT_RESTART=true \
MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS=5 \
MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS=5 \
POSTGRES_PORT=55432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/surprising_exchange \
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  ./scripts/full-stack-real-config-smoke.sh
```

Result: passed.

Logs: `/tmp/surprising-full-stack-real-config.F0V0h2`

Run id: `1783197787393287000`

## Scope

- Real providers: instrument, candlestick, index-price, mark-price, order, matching,
  account, risk, liquidation, funding, insurance, ADL, websocket, trigger, gateway,
  and market-maker.
- Market-maker path: the provider starts in safe mode, then the script restarts it
  with `surprising.market-maker.engine.enabled=true` and `cycle-delay-ms=100`.
- Fault window: after quotes are accepted, the script stops the real account
  provider, submits 5 ordinary-user taker orders through gateway `trading`, waits
  for matching fills, waits 5 seconds, restarts the account provider, and then
  requires a later market-maker `CYCLE_SUCCESS`.
- User path: taker orders enter through gateway, order-provider, matching, Kafka
  match trades, account-provider settlement, risk, and WebSocket. No database-only
  shortcut is used for fills or account settlement.

## Key Results

- WebSocket captures: depth `823`, mark `487`, funding `478`; private user captures
  included orders, matches, positions, positionRisk, and accountRisk events.
- Full-stack orders: `ACCEPTED=4`, `CANCELED=379`, `FILLED=77`, `REJECTED=3`.
- Full-stack trades: `43` trades, `204` quantity steps.
- Account processed trades: `43`.
- Market-maker run events: `CYCLE_SUCCESS=4`, `QUOTE_RECONCILED=9`, submitted quote
  orders `360`, canceled quote orders `200`, rejected quote orders `0`.
- Expected temporary cycle failures while account was down: `CYCLE_FAILED=103`.
- Market-maker account orders after cleanup: `CANCELED=360`, executed quantity steps
  `5`.
- Engine taker trades: `5/5` filled.
- Engine taker trades account processed after account restart: `5/5`.
- Open market-maker quotes after cleanup: `0`.

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

The script now exposes:

- `MM_PROVIDER_ENGINE_ACCOUNT_RESTART`
- `MM_PROVIDER_ENGINE_ACCOUNT_RESTART_DELAY_SECONDS`
- `MM_PROVIDER_ENGINE_ACCOUNT_DOWN_SECONDS`

When account restart is enabled, `CYCLE_FAILED` events are expected during the
outage because the market-maker provider cannot complete all account-dependent
reconciliation calls. The pass condition is recovery: at least one later
`CYCLE_SUCCESS`, all user taker fills account-settled, no rejected market-maker
quotes, no open quote residue, no negative funds, and drained outboxes.

Remaining gap: this run proves a short account-provider restart window on a
single-node local stack. Production-grade evidence still needs longer duration,
multi-node account providers, multi-broker Kafka, PostgreSQL fault windows, larger
WebSocket fanout, and longer reference-market fault-window runs. The first short
combined reference-market account-restart sample is now recorded in
`docs/market-maker-reference-market-fault-report.md`.
