package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class CoreRiskStateTest {

    private final TradingCoreReducer reducer = new TradingCoreReducer();

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

        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, markPrice, 11));

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
        for (long userId = 1; userId <= 300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }

        TradingCoreState firstBatch = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1));
        assertThat(firstBatch.riskState().scan().complete()).isFalse();
        assertThat(firstBatch.riskState().scan().lastUserId()).isEqualTo(256);
        assertThat(firstBatch.riskState().snapshots()).hasSize(256);

        TradingCoreState completed = reducer.continueRiskScan(firstBatch, 256);
        assertThat(completed.riskState().scan().complete()).isTrue();
        assertThat(completed.riskState().scan().lastUserId()).isEqualTo(300);
        assertThat(completed.riskState().snapshots()).hasSize(300);
    }

    @Test
    void pendingRiskScansRemainIndependentAcrossSymbols() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument("BTC-USDT"));
        state = reducer.upsertInstrument(state, instrument("ETH-USDT"));
        for (long userId = 1; userId <= 300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 200));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
            state = withPosition(state, userId, new CorePositionState("ETH-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }

        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 1));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("ETH-USDT", 1, 80, 1));

        assertThat(state.riskState().scans()).containsOnlyKeys("BTC-USDT", "ETH-USDT");
        assertThat(state.riskState().scans().get("BTC-USDT").complete()).isTrue();
        assertThat(state.riskState().scans().get("ETH-USDT").complete()).isFalse();
        state = reducer.continueRiskScan(state, 256);
        state = reducer.continueRiskScan(state, 256);
        assertThat(state.riskState().scans().values()).allMatch(CoreRiskState.RiskScan::complete);
    }

    @Test
    void newerPriceDuringScanForcesACompleteSecondPass() {
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                instrument(ContractType.LINEAR_PERPETUAL, 1));
        for (long userId = 1; userId <= 300; userId++) {
            state = reducer.adjustBalance(state, userId, new BalanceAdjustmentCommand("USDT", 100));
            state = withPosition(state, userId, new CorePositionState("BTC-USDT", "USDT", 1,
                    1, 100, 100, 0, 10));
        }
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 90, 1));

        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 2));

        CoreRiskState.RiskScan restarted = state.riskState().scans().get("BTC-USDT");
        assertThat(restarted.priceSequence()).isEqualTo(2);
        assertThat(restarted.lastUserId()).isZero();
        assertThat(restarted.complete()).isFalse();
        state = reducer.continueRiskScan(state, 4_096);
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
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("ETH-USDT", 1, 120, 1));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 80, 2));

        CoreRiskSnapshot btc = state.riskState().snapshots().get("7:BTC-USDT");
        CoreRiskSnapshot eth = state.riskState().snapshots().get("7:ETH-USDT");
        assertThat(btc.equityUnits()).isEqualTo(1_000);
        assertThat(eth.equityUnits()).isEqualTo(1_000);
        assertThat(btc.marginRatioPpm()).isEqualTo(200_000);
        assertThat(eth.marginRatioPpm()).isEqualTo(200_000);
        assertThat(state.riskState().liquidations()).isEmpty();

        TradingCoreState moved = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("ETH-USDT", 1, 20, 3));
        assertThat(moved.riskState().snapshots().get("7:BTC-USDT").equityUnits()).isEqualTo(0);
        assertThat(moved.riskState().snapshots().get("7:BTC-USDT").status())
                .isEqualTo(CoreRiskStatus.LIQUIDATION);
        assertThat(moved.riskState().liquidations()).hasSize(2);
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
                state.bookState(), state.instruments(), state.riskState(), state.treasuryState());
    }
}
