package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class CoreRiskStateTest {

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void disabledRiskScanDoesNotCalculateRisk() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("BTC-USDT", "USDT", 1,
                10, 100, 1_000, 0, 100));
        state = reducer.updateRiskScanControl(state, new UpdateRiskScanControlCommand(
                1, "Paused scan", false, 1_000, 500, "admin", "maintenance"), 2_000);

        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1, 2_001));

        assertThat(marked.riskState().snapshots()).isEmpty();
        assertThat(marked.riskState().scan().riskComplete()).isTrue();
        assertThat(marked.riskState().scanControl().version()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("riskCases")
    void markPriceComputesRiskPlansLiquidationAndSurvivesSnapshot(
            ProductLine productLine,
            ContractType contractType,
            long entryPrice,
            long markPrice,
            long settleScale) {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(productLine),
                instrument(contractType, settleScale));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("BTC-USDT", "USDT", 1,
                10, entryPrice, Math.multiplyExact(entryPrice, 10), 0, 100));

        ApplyMarkPriceCommand markCommand = new ApplyMarkPriceCommand(
                "BTC-USDT", 1, markPrice, 11, 1_700_000_000_000L);
        TradingCoreState marked = reducer.applyMarkPrice(state, markCommand);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        RuntimeStateParityChecker.assertMatches(marked, identities,
                RuntimeDerivativeRiskProcessor.simulateMarkPrice(
                        state, markCommand, state.users().keySet(), identities));

        CoreRiskSnapshot risk = marked.riskState().snapshots().get("7:BTC-USDT");
        assertThat(risk.status()).isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(StateMapSupport.isDelta(marked.riskState().markPrices())).isTrue();
        assertThat(StateMapSupport.isDelta(marked.riskState().snapshots())).isTrue();
        assertThat(StateMapSupport.isDelta(marked.riskState().liquidations())).isTrue();
        assertThat(StateMapSupport.isDelta(marked.riskState().scans())).isTrue();
        assertThat(marked.riskState().liquidations()).hasSize(1);
        assertThat(marked.riskState().liquidations().get(1L).closeQuantitySteps()).isEqualTo(10);
        assertThat(marked.riskState().scan().complete()).isTrue();

        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(marked), productLine);
        assertThat(restored).isEqualTo(marked);
        assertThat(restored.businessStateHash()).isEqualTo(marked.businessStateHash());
    }

    private static Stream<Arguments> riskCases() {
        return Stream.of(
                Arguments.of(ProductLine.LINEAR_PERPETUAL, ContractType.LINEAR_PERPETUAL,
                        100, 80, 1),
                Arguments.of(ProductLine.INVERSE_PERPETUAL, ContractType.INVERSE_PERPETUAL,
                        100, 50, 10_000));
    }

    @Test
    void riskScanContinuesInBoundedBatches() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 1_300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }

        TradingCoreState firstBatch = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1, 1_700_000_000_000L));
        assertThat(firstBatch.riskState().scan().complete()).isFalse();
        assertThat(firstBatch.riskState().snapshots()).hasSizeLessThanOrEqualTo(
                firstBatch.riskState().scanControl().scanBatchSize());
        assertThat(firstBatch.riskState().snapshots()).isNotEmpty();

        TradingCoreState completed = continueRiskScans(firstBatch);
        assertThat(completed.riskState().scan().complete()).isTrue();
        LaneTopology topology = LaneTopology.configured(false);
        long expectedLastUserId = 0;
        for (long userId = 1; userId <= 1_300; userId++) {
            if (topology.accountLaneId(userId) == topology.accountLaneCount() - 1) {
                expectedLastUserId = userId;
            }
        }
        assertThat(completed.riskState().scan().accountLaneId())
                .isEqualTo(topology.accountLaneCount() - 1);
        assertThat(completed.riskState().scan().lastUserId()).isEqualTo(expectedLastUserId);
        assertThat(completed.riskState().snapshots()).hasSize(1_300);
    }

    @Test
    void laneRiskCursorSurvivesMidScanSnapshot() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 260; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }
        TradingCoreState partial = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1, 1_700_000_000_000L));
        assertThat(partial.riskState().scan().riskComplete()).isFalse();
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(partial), ProductLine.LINEAR_PERPETUAL);

        TradingCoreState expected = continueRiskScans(partial);
        TradingCoreState actual = continueRiskScans(restored);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.businessStateHash()).isEqualTo(expected.businessStateHash());
        assertThat(actual.riskState().snapshots()).hasSize(260);
    }

    @Test
    void runtimeMarkPriceOnlySchedulesHeavyRiskWork() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("BTC-USDT", "USDT", 1,
                10, 100, 1_000, 0, 100));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);
        long revisionBefore = runtime.revision();

        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(
                new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1, 1_700_000_000_000L),
                runtime, identities);

        assertThat(runtime.revision()).isEqualTo(revisionBefore + 1);
        assertThat(runtime.riskScan(identities.symbolId("BTC-USDT")).riskComplete()).isFalse();
        long positionKey = identities.preparedPositionKey(7, "BTC-USDT");
        assertThat(runtime.riskSnapshot(positionKey)).isNull();

        RuntimeDerivativeRiskProcessor.applyContinuationRuntime(64, state.users().keySet(), runtime, identities);

        assertThat(runtime.riskSnapshot(positionKey)).isNotNull();
    }

    @Test
    void markPriceBeyondHighestRiskBracketStillProducesLiquidationSnapshot() {
        UpsertInstrumentCommand command = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0, 10_000_000L, 100,
                10_000_000L, 100, List.of(new CoreRiskLimitBracket(1, 0, 100,
                        10_000_000L, 100_000, 50_000)));
        TradingCoreState state = reducer.upsertInstrument(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), command);
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 100));
        state = withPosition(state, new CorePositionState("BTC-USDT", "USDT", 1,
                10, 10, 100, 0, 100));

        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_700_000_000_000L));

        assertThat(marked.riskState().scan().complete()).isTrue();
        assertThat(marked.riskState().snapshots()).containsKey("7:BTC-USDT");
    }

    @Test
    void pendingRiskScansRemainIndependentAcrossSymbols() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("BTC-USDT"));
        state = reducer.upsertInstrument(state, instrument("ETH-USDT"));
        for (long userId = 1; userId <= 1_300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 200));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
            state = withPosition(state, userId, new CorePositionState("ETH-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }

        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1,
                1_700_000_000_000L));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("ETH-USDT", 1, 80, 1,
                1_700_000_000_000L));

        assertThat(state.riskState().scans()).containsOnlyKeys("BTC-USDT", "ETH-USDT");
        assertThat(state.riskState().scans().get("BTC-USDT").complete()).isFalse();
        assertThat(state.riskState().scans().get("ETH-USDT").complete()).isFalse();
        state = continueRiskScans(state);
        assertThat(state.riskState().scans().values()).allMatch(CoreRiskState.RiskScan::complete);
    }

    @Test
    void newerPriceDuringScanForcesACompleteSecondPass() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 1_300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 1,
                1_700_000_000_000L));

        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 2,
                1_700_000_000_000L));

        CoreRiskState.RiskScan restarted = state.riskState().scans().get("BTC-USDT");
        assertThat(restarted.priceSequence()).isEqualTo(2);
        assertThat(restarted.lastUserId()).isPositive();
        assertThat(restarted.complete()).isFalse();
        state = continueRiskScans(state);
        assertThat(state.riskState().scans().get("BTC-USDT").complete()).isTrue();
        assertThat(state.riskState().snapshots().values())
                .allMatch(snapshot -> snapshot.priceSequence() == 2);
    }

    @Test
    void crossMarginUsesPortfolioEquityAcrossSameSettlementAsset() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("BTC-USDT"));
        state = reducer.upsertInstrument(state, instrument("ETH-USDT"));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        state = withPosition(state, new CorePositionState("BTC-USDT", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 10, 100, 1_000, 0, 0));
        state = withPosition(state, new CorePositionState("ETH-USDT", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 10, 100, 1_000, 0, 0));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("ETH-USDT", 1, 120, 1,
                1_700_000_000_000L));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 2,
                1_700_000_000_000L));

        CoreRiskSnapshot btc = state.riskState().snapshots().get("7:BTC-USDT");
        CoreRiskSnapshot eth = state.riskState().snapshots().get("7:ETH-USDT");
        assertThat(btc.equityUnits()).isEqualTo(1_000);
        assertThat(eth.equityUnits()).isEqualTo(1_000);
        assertThat(btc.marginRatioPpm()).isEqualTo(200_000);
        assertThat(eth.marginRatioPpm()).isEqualTo(200_000);
        assertThat(state.riskState().liquidations()).isEmpty();

        TradingCoreState moved = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("ETH-USDT", 1, 20, 3, 1_700_000_000_000L));
        assertThat(moved.riskState().snapshots().get("7:BTC-USDT").equityUnits()).isEqualTo(0);
        assertThat(moved.riskState().snapshots().get("7:BTC-USDT").status())
                .isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(moved.riskState().liquidations()).hasSize(2);
    }

    @Test
    void crossRiskPersistsPositionCursorAndCompletesWithTheSamePortfolioResult() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("BTC-USDT"));
        state = reducer.upsertInstrument(state, instrument("ETH-USDT"));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        state = withPosition(state, new CorePositionState("BTC-USDT", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 10, 100, 1_000, 0, 0));
        state = withPosition(state, new CorePositionState("ETH-USDT", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 10, 100, 1_000, 0, 0));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("ETH-USDT", 1, 120, 1,
                1_700_000_000_000L));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 2,
                1_700_000_000_000L));

        Map<String, CoreMarkPriceState> marks = new TreeMap<>(state.riskState().markPrices());
        marks.put("BTC-USDT", new CoreMarkPriceState("BTC-USDT", 1, 80, 3, 1_000));
        CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan("BTC-USDT", 3, 3, 0, false)
                .withTriggerProgress(false, 1, 80, 91, 100, 80, 1_700_000_000_001L);
        CoreRiskState risk = new CoreRiskState(marks, state.riskState().snapshots(),
                state.riskState().liquidations(), Map.of("BTC-USDT", scan),
                state.riskState().nextLiquidationId());
        TradingCoreState pending = new TradingCoreState(state.productLine(), state.revision() + 1,
                state.users(), state.orders(), state.instruments(), risk,
                state.treasuryState(), state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(),
                state.clientOrderIndex(), state.triggerOrders());

        TradingCoreState firstPage = reducer.continueRiskScan(pending, 1);
        assertThat(firstPage.riskState().scan().riskUserId()).isEqualTo(7);
        assertThat(firstPage.riskState().scan().riskPositionCursor()).isEqualTo("BTC-USDT");
        assertThat(firstPage.riskState().scan().triggerComplete()).isFalse();
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(firstPage), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored.riskState().scan()).isEqualTo(firstPage.riskState().scan());
        assertThat(restored.businessStateHash()).isEqualTo(firstPage.businessStateHash());

        TradingCoreState paged = restored;
        for (int page = 0; page < 10 && !paged.riskState().scan().riskComplete(); page++) {
            paged = reducer.continueRiskScan(paged, 1);
        }
        TradingCoreState unpaged = reducer.continueRiskScan(pending, 4_096);
        assertThat(paged.riskState().scan().riskComplete()).isTrue();
        assertThat(paged.riskState().snapshots()).isEqualTo(unpaged.riskState().snapshots());
        assertThat(paged.riskState().liquidations()).isEqualTo(unpaged.riskState().liquidations());
    }

    @Test
    void runtimeRiskMatchesAuthoritativeMarkPriceAndPagedContinuation() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 260; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1,
                1_700_000_000_000L);
        TradingCoreState first = reducer.applyMarkPrice(state, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);
        runtime.startAccountLanes();
        try {
            RuntimeDerivativeRiskProcessor.applyMarkPrice(
                    state, command, state.users().keySet(), runtime, identities);
            for (int laneId = 0; laneId < runtime.topology().accountLaneCount(); laneId++) {
                assertThat(runtime.accountLaneById(laneId).queueDepth()).isZero();
            }
            assertThat(RuntimeStateMaterializer.materialize(runtime, identities))
                    .isEqualTo(first);
            RuntimeStateParityChecker.assertMatches(first, identities, runtime);

            while (!first.riskState().scan().riskComplete()) {
                TradingCoreState before = first;
                first = reducer.continueRiskScan(before, 64);
                RuntimeDerivativeRiskProcessor.applyContinuation(
                        before, 64, before.users().keySet(), runtime, identities);
                RuntimeStateParityChecker.assertMatches(first, identities, runtime);
            }
            long riskLaneOperations = 0;
            for (int laneId = 0; laneId < runtime.topology().accountLaneCount(); laneId++) {
                riskLaneOperations += runtime.accountLaneMetricsById(laneId).completedOperations()[
                        AccountLaneOperationType.RISK.ordinal()];
            }
            assertThat(riskLaneOperations).isPositive().isLessThan(260);
        } finally {
            runtime.close();
        }
        assertThat(first.riskState().snapshots()).hasSize(260);
        assertThat(first.riskState().liquidations()).isEmpty();
    }

    @Test
    void laneRiskAssignsDeterministicLiquidationIdsAcrossPages() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 32; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    10, 100, 1_000, 0, 100));
        }
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1,
                1_700_000_000_000L);
        TradingCoreState authoritative = reducer.applyMarkPrice(state, command);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);
        runtime.startAccountLanes();
        try {
            RuntimeDerivativeRiskProcessor.applyMarkPrice(
                    state, command, state.users().keySet(), runtime, identities);
            RuntimeStateParityChecker.assertMatches(authoritative, identities, runtime);
            while (!authoritative.riskState().scan().riskComplete()) {
                TradingCoreState before = authoritative;
                authoritative = reducer.continueRiskScan(before, 7);
                RuntimeDerivativeRiskProcessor.applyContinuation(
                        before, 7, before.users().keySet(), runtime, identities);
                RuntimeStateParityChecker.assertMatches(authoritative, identities, runtime);
            }
        } finally {
            runtime.close();
        }
        assertThat(authoritative.riskState().liquidations()).hasSize(32);
        assertThat(authoritative.riskState().nextLiquidationId()).isEqualTo(33);
    }

    @Test
    void runtimeRiskPreservesOldPassBeforeRestartingLatestPrice() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 260; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 1,
                1_700_000_000_000L));
        ApplyMarkPriceCommand latest = new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 2,
                1_700_000_000_001L);
        TradingCoreState after = reducer.applyMarkPrice(state, latest);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeDerivativeRiskProcessor.simulateMarkPrice(
                state, latest, state.users().keySet(), identities);
        RuntimeStateParityChecker.assertMatches(after, identities, runtime);
        assertThat(after.riskState().scan().scanStartPriceSequence()).isEqualTo(1);
        assertThat(after.riskState().scan().priceSequence()).isEqualTo(2);
        assertThat(after.riskState().scan().riskComplete()).isFalse();
    }

    private TradingCoreState continueRiskScans(TradingCoreState state) {
        for (int page = 0; page < 10_000 && state.riskState().hasPendingScans(); page++) {
            state = reducer.continueRiskScan(state, 64);
        }
        return state;
    }

    private static UpsertInstrumentCommand instrument(ContractType type, long settleScale) {
        return new UpsertInstrumentCommand("BTC-USDT", 1, type.ordinal(), "BTC", "USDT", "USDT",
                1, 1, settleScale, 100_000, 100_000, 0, 0, 0, -1, 0);
    }

    private static UpsertInstrumentCommand instrument(String symbol) {
        return new UpsertInstrumentCommand(symbol, 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                symbol.substring(0, symbol.indexOf('-')), "USDT", "USDT", 1, 1, 1,
                100_000, 100_000, 0, 0, 0, -1, 0);
    }

    private static TradingCoreState withPosition(TradingCoreState state, CorePositionState position) {
        return withPosition(state, 7, position);
    }

    private static TradingCoreState withPosition(
            TradingCoreState state,
            long userId,
            CorePositionState position) {
        CoreUserState current = state.user(userId);
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        AssetBalance marginBalance = balances.get(position.marginAsset());
        balances.put(position.marginAsset(), new AssetBalance(position.marginAsset(),
                Math.subtractExact(marginBalance.availableUnits(), position.positionMarginUnits()),
                Math.addExact(marginBalance.lockedUnits(), position.positionMarginUnits())));
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put(position.key(), position);
        CoreUserState user = new CoreUserState(state.productLine(), userId, current.revision() + 1,
                balances, current.reservations(), positions);
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, user);
        return new TradingCoreState(state.productLine(), state.revision() + 1, users, state.orders(),
                state.instruments(), state.riskState(), state.treasuryState());
    }
}
