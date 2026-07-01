# Integration Verification Report

This report records the current evidence for the Surprising Exchange perpetual trading chain.
It is intentionally evidence-based: a row is marked verified only when there is a repository test,
database smoke assertion, or build gate that exercises the behavior.

## Verified Gates

Run these gates from the repository root:

```bash
./scripts/integration-smoke.sh
mvn -q test
mvn -q -DskipTests package
```

Run the optional real Kafka trading smoke when Docker is available:

```bash
./scripts/kafka-trading-smoke.sh
PAIR_COUNT=50 LOAD_CONCURRENCY=16 ./scripts/kafka-trading-load-smoke.sh
```

Run the full real-process provider chain when you need end-to-end coverage:

```bash
KEEP_TMP=true ./scripts/full-stack-real-config-smoke.sh
```

For day-to-day debugging, do not restart every provider for a small unrelated change. If services are already running and you only need targeted checks, reuse the live processes and skip state reset and fault injection:

```bash
RESET_STATE=false START_PROVIDERS=false RUN_FAILURE_SCENARIOS=false BUILD_SERVICES=false ./scripts/full-stack-real-config-smoke.sh
```

This incremental mode is for diagnosis, not a replacement for the clean full-stack smoke. Do not reset the database, delete Kafka topics, or clear RocksDB while reusing providers because in-memory service state would diverge from persistent state.

Clean generated build output after local verification:

```bash
mvn -q clean
find . -type d -name target -prune -print
```

`find` should print nothing after a successful clean.

Latest local verification:

- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-risk-provider -am test`: passed, covering keyset-paginated risk group scanning, group-level mark freshness, and multi-node scan lease behavior.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -pl :surprising-integration-test -am test`: passed, covering the Java order -> matching -> account -> risk -> liquidation -> funding -> insurance -> ADL integration chain.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q test`: passed, covering all current Maven module tests.
- `KEEP_TMP=true BUILD_SERVICES=false ./scripts/full-stack-real-config-smoke.sh`: passed, covering real PostgreSQL/Kafka, multiple providers, WebSocket, high-frequency order flow, deep order book, funding, liquidation, insurance, and ADL.

## Evidence Matrix

| Area | Current evidence |
| --- | --- |
| Schema bootstrap | `scripts/integration-smoke.sh` starts a temporary PostgreSQL instance and applies root `init.sql`. |
| Real Kafka trading smoke | `scripts/kafka-trading-smoke.sh` starts Docker Compose PostgreSQL/Kafka, applies `init.sql` to an isolated smoke database, creates topics, starts order/matching/account providers, funds two users through REST, places crossing orders through REST, waits for exchange-core matching and account Kafka settlement, then republishes the same match-trade payload and verifies account positions and processed-trade rows are unchanged. `scripts/kafka-trading-load-smoke.sh` additionally starts WebSocket, subscribes depth/private channels, and checks full fill, partial fill plus cancel, cancel-only, cancel-all, concurrent maker/taker users, position correctness, order-book depth push, and private order/match/position push reception. `scripts/full-stack-real-config-smoke.sh` starts instrument, candlestick, index-price, mark-price, order, matching, account, risk, liquidation, funding, insurance, ADL, websocket, and gateway providers, covering funding, liquidation, insurance, ADL, public/private WebSocket pushes, and managed failure-recovery scenarios. |
| Long fixed-point model | Core trading/account/risk/liquidation/funding/insurance/ADL Java paths use ticks, steps, ppm, and asset units; `CoreFixedPointArchitectureTest` rejects `BigDecimal`, `double`, and `float` in those main Java paths, and unit tests cover overflow-sensitive math and checked aggregation where wrapping would change capacity or risk. |
| Shared contract math | `PerpetualContractMathTest` verifies shared linear/inverse notional, unrealized PnL, notional-per-step, initial margin, maintenance margin, and overflow behavior used by account, risk, funding, liquidation, and ADL. |
| Instrument version pinning | `integration-smoke.sh` changes current BTC-USDT to version 2 and verifies existing version 1 positions still calculate risk and funding with version 1 rules. |
| Order validation | `TraceContextTest`, `TraceIdFilterTest`, `OrderValidatorTest`, `OrderMarginMathTest`, `ReduceOnlyValidatorTest`, `OrderServiceTest`, `OrderRepositoryTest`, `OrderMarginRepositoryTest`, `OrderMarkPriceRepositoryTest`, and `MarketPriceProtectionTest` cover trace-id normalization/request-scope cleanup/order-event and command payload propagation, order bounds, inverse notional, market execution-band notional limits, fresh mark-price checks, long-only initial-margin math including upper-bound linear market SELL collateral, reduce-only capacity with checked pending-close aggregation, scoped `clientOrderId` idempotency without duplicate margin locks, and guarded margin reservation updates. |
| Cross-module Java chain | `PerpetualTradingChainIntegrationTest` runs real exchange-core with in-memory adapters and verifies order entry -> matching -> account settlement -> user reduce-only close for both linear and inverse perpetuals, inverse coin-margin release and realized PnL, market order risk-bound reservation -> better execution -> excess-margin release, linear market SELL reservation at the upper bound before execution against a higher resting bid, user cancel -> exchange-core cancel -> reserved-margin release, partial fill -> cancel remaining -> release only unused order margin while preserving migrated position margin, plus risk candidate -> liquidation reduce-only market order -> matched liquidation close settlement. `PostLiquidationFundingInsuranceAdlIntegrationTest` verifies funding settlement -> account balance/margin movement, insurance deficit coverage, and ADL residual-deficit transfer. |
| exchange-core matching | `ExchangeCoreEngineRecoveryTest` restores DB open orders into exchange-core and rejects crossed recovery state. |
| Matching fail-fast recovery | `MatchingCommandConsumerTest`, `MatchingPartitionAssignmentGuardTest`, `MatchingResultRepositoryTest`, and `MatchingOutboxRepositoryTest` verify decoded command processing failures request matcher restart before Kafka replay, partition reassignment/loss restarts after a matcher has processed commands, match result/trade/outbox write conflicts fail fast, and undecodable payloads do not mark partitions as processed. |
| Kafka runtime semantics | `TradingOrderKafkaConfigurationTest`, `MatchingKafkaConfigurationTest`, `AccountKafkaConfigurationTest`, `RiskKafkaConfigurationTest`, `LiquidationKafkaConfigurationTest`, and `FundingKafkaConfigurationTest` lock producer settings to `acks=all`, idempotence, `zstd`, and bounded in-flight requests, and lock consumer settings to replay-safe `earliest`, auto-commit disabled, cooperative-sticky assignment, and record-level Spring Kafka acknowledgements. |
| Client gateway and WebSocket fanout | `GatewayTraceFilterTest` and `GatewayProxyControllerTest` verify gateway trace-id normalization/forwarding, allowlisted target URI construction, and private-route identity enforcement. `GatewayHttpConfigurationTest` verifies explicit gateway backend connect/read timeouts. `SubscriptionTopicTest` verifies public/private subscription normalization and user-id checks including the public depth channel. `KafkaFanoutConsumerTest` verifies order-book depth Kafka events fan out to `channel=depth`. `WebSocketKafkaConfigurationTest` verifies the per-node WebSocket fanout consumer uses `latest`, disabled auto commit, cooperative-sticky assignment, and record-level acknowledgements. `WebSocketServerConfigurationTest` verifies configurable WebSocket Origin allowlists and local-development fallback. |
| Symbol-key partition invariant | `KafkaSymbolKeyValidatorTest`, `MatchingCommandConsumerTest`, `MatchTradeConsumerTest`, and `LiquidationCandidateConsumerTest` verify that matching commands, account match trades, and liquidation candidates are rejected before business processing when the Kafka record key does not match payload `symbol`. |
| Outbox retry semantics | `OutboxRepositoryTest`, `MatchingOutboxRepositoryTest`, `RiskOutboxRepositoryTest`, and `FundingOutboxRepositoryTest` verify unpublished due rows are selected with `FOR UPDATE SKIP LOCKED`, and Kafka send failures increment attempts, store a truncated error, and schedule capped exponential retry through `next_attempt_at`. |
| Matching service output | `MatchingServiceTest` runs a real exchange-core match and verifies versioned maker/taker `MatchTradeEvent` fields, command trace-id propagation into match results/trades, order-book `SNAPSHOT`/`DELTA` output with remaining and deleted price levels, command idempotency, replay conflicts that skip duplicate result/trade side effects, and that `CANCEL` commands are not rejected by post-only liquidity checks; `MatchingResultRepositoryTest` verifies result/trade idempotency keys. |
| Matching persistence side effects | `MatchingResultRepositoryTest` verifies cancellation and terminal immediate orders release margin before remaining quantity is cleared, order fill updates are guarded instead of clamped, missing reservations fail for non-reduce-only orders, and insufficient locked balance fails margin release instead of crediting nonexistent funds back to available balance. |
| Account position, PnL, fees, and position push source | `PositionCalculatorTest`, `MarginTransferMathTest`, `PnlSettlementMathTest`, `TradeFeeMathTest`, `AccountRepositoryTest`, `AccountServiceTest`, and `ReduceOnlyOrderPrunerTest` verify linear/inverse PnL, maker/taker fee debits and rebates, balance-adjustment replay payload checks, actual fill-price margin migration, order-price/market-protection excess release, flip fills that close old exposure before consuming opening margin for the remainder, fail-fast opening/closing fills when required reservations are missing, closing margin release, fail-fast `TRADE_PNL`/`TRADE_FEE` ledger writes, `(symbol, trade_id)` duplicate trade idempotency including same-number trade ids across symbols, pruning wrong-side/excess reduce-only orders after position changes, checked reduce-only capacity math, realized losses debiting only position-margin-backed locked collateral, and enqueuing trace-linked position update events after both settled sides are written. |
| Risk and liquidation candidates | `RiskMathTest`, `RiskRepositoryTest`, `RiskOutboxRepositoryTest`, and `RiskServiceTest` cover equity/margin status math, long-only linear/inverse notional/PnL/maintenance formulas, keyset-paginated `userId + settleAsset` group scanning, whole-group fresh-mark enforcement, checked group-level PnL and maintenance-margin aggregation, snapshot/outbox fail-fast writes, per-`userId + settleAsset` risk-scan transaction isolation, PostgreSQL scan-lease acquisition/skip behavior for multi-node providers, scoped active-candidate conflict handling, and candidate event consistency; `integration-smoke.sh` verifies risk snapshots and active liquidation candidate uniqueness. |
| Liquidation execution | `LiquidationSizingPolicyTest`, `LiquidationSideResolverTest`, `LiquidationServiceTest`, `LiquidationRepositoryTest`, and `LiquidationOrderRepositoryTest` verify staged sizing, shared-math sizing notional, close side, instrument-version propagation, fresh risk-snapshot re-checks, pre-cancel of user reduce-only close orders with checked pending-close aggregation, liquidation sizing from the full live position instead of pending-close capacity, candidate-status fail-fast updates, no broad conflict suppression for liquidation trading-order inserts, and fail-fast behavior when liquidation audit/outbox writes are skipped by conflicts. |
| Funding settlement | `FundingMathTest`, `FundingTimeTest`, `FundingRepositoryTest`, `FundingOutboxRepositoryTest`, `FundingServiceTest`, and `PostLiquidationFundingInsuranceAdlIntegrationTest` verify long ppm direction, UTC funding boundaries, shared-math position-version notional conversion, rate/outbox fail-fast publication, per-`symbol + fundingTime` settlement transaction isolation, settlement/account fail-fast updates, funding charges not consuming open-order reservation locks, and funding debits reducing only position-margin-backed locked collateral before creating deficits. |
| Insurance fund | `InsuranceMathTest` verifies full and partial coverage math; `InsuranceRepositoryTest` verifies fund-adjustment idempotency, replay payload checks, coverage-ledger fail-fast behavior, partial repository-level coverage that writes negative fund ledger and positive deficit account ledger, and the empty-fund path that leaves `account_deficits` untouched for a later insurance top-up or ADL scan; `integration-smoke.sh` and `PostLiquidationFundingInsuranceAdlIntegrationTest` verify partial coverage reduces `account_deficits` without crediting available balance and leaves residual deficit for ADL after fund depletion. |
| ADL | `AdlMathTest` verifies priority math; `AdlRepositoryTest` verifies shared-math notional/profit candidate calculation, insurance-fund locking before execution, successful repository-level ADL execution with target position reduction, margin release, realized-profit transfer, deficit clearing, and fail-fast behavior when target-position or guarded margin-release updates are skipped; `integration-smoke.sh` and `PostLiquidationFundingInsuranceAdlIntegrationTest` verify ADL position reduction, margin release, realized-profit transfer, and deficit clearing. |
| Operational kill switches | `RiskServiceTest`, `LiquidationServiceTest`, `FundingServiceTest`, `InsuranceServiceTest`, and `AdlServiceTest` verify that risk scans, liquidation execution, funding-rate publication, funding settlement, insurance coverage, and ADL scanning can be paused without creating candidates, claiming liquidation work, applying account changes, or mutating deficits. |
| Price services | Index/mark tests cover outlier handling, mark-price calculation, external ticker parsing, and `mark_price_units` persistence at the price boundary before core modules convert to version-specific ticks. |
| Candlestick | Candlestick math tests and the candlestick module README cover Kafka Streams/RocksDB aggregation behavior and latest-candle fanout expectations. |

## End-To-End Chain Status

The repository now has verified coverage for the core logical chain:

```text
instrument rules
  -> order validation and margin reservation
  -> exchange-core matching
  -> protected market execution and fill-price margin migration
  -> user cancel and unused reserved-margin release
  -> partial-fill cancellation preserving migrated position margin
  -> versioned match events
  -> account position/PnL/fee/margin updates
  -> risk snapshots and liquidation candidates
  -> pre-cancel user reduce-only close orders
  -> reduce-only liquidation close order creation
  -> funding settlement
  -> insurance deficit coverage
  -> ADL residual deficit transfer
```

The Java integration tests now cover the live event handoff across order, matching, account, risk, liquidation, funding, insurance, and ADL service classes without Kafka/PostgreSQL process startup. They complement the PostgreSQL smoke test, which focuses on schema and cross-table invariants. The Kafka trading smoke adds process-level evidence for order -> matching -> account with real Kafka and PostgreSQL, and the load smoke extends that path to WebSocket depth/private push assertions when Docker Compose is available.

The local PostgreSQL smoke also verifies the cross-table invariants that are hard to see from unit tests:

- old positions are not reinterpreted by newer instrument versions;
- funding notional conversion uses the position's pinned instrument version;
- active liquidation candidates are unique per `user_id + symbol`;
- insurance coverage reduces explicit deficit only;
- ADL reduces a profitable target position and transfers realized profit to clear residual deficit.

## Residual Work Before Production

The current gates are strong development checks, but they are not a replacement for production rollout validation.
Before production, run and document:

- expanded multi-process Kafka end-to-end tests with real order, matching, account, risk, liquidation, insurance, and ADL services running together;
- rebalance/failover tests for matching partitions, Kafka outbox replay, and PostgreSQL lock contention;
- load tests for peak order rate, trade rate, mark-price update rate, and risk scan cadence;
- chaos tests for PostgreSQL restart, Kafka broker loss, stale mark price, and external venue disconnects;
- real-cluster validation of the operational pause switches, plus runbooks for insurance fund top-up, symbol halt, and instrument-version rollout.

## Current Conclusion

The codebase has a coherent long-based perpetual trading chain with exchange-core as the matcher and PostgreSQL-backed idempotency/locking around state transitions.
The verified local gates prove the important accounting formulas and cross-table invariants. Full production readiness still requires real multi-service Kafka tests and load/chaos evidence.
