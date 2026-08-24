package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.matching.CoreMatch;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimePerpetualMatchProcessorTest {

    @Test
    void persistentApplyMutatesProvidedRuntimeAndMatchesAuthoritativeReducer() {
        CoreInstrumentState instrument = instrument();
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 1);
        CoreOrderState maker = order(12, 8, CoreOrderSide.SELL, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 100), 8L, user(8, maker, 100)),
                Map.of(11L, taker, 12L, maker), Map.of(instrument.symbol(), instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        List<CoreMatch> matches = List.of(new CoreMatch(12, 8, 100, 1, true, true));
        TradingCoreState expected = new TradingCoreReducer().applyMatches(before, 11, "BTC", "USDT", matches);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        TradingRuntimeState applied = RuntimePerpetualMatchProcessor.apply(
                before, 11, matches, runtime, identities);

        assertThat(applied).isSameAs(runtime);
        RuntimeStateParityChecker.assertMatches(expected, identities, applied);
    }

    @Test
    void persistentApplyRejectsMissingMakerWithoutMutatingRuntime() {
        CoreInstrumentState instrument = instrument();
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 2);
        CoreOrderState maker = order(12, 8, CoreOrderSide.SELL, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 200), 8L, user(8, maker, 100)),
                Map.of(11L, taker, 12L, maker),
                Map.of(instrument.symbol(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimePerpetualMatchProcessor.apply(before, 11,
                List.of(new CoreMatch(12, 8, 100, 1, true, true),
                        new CoreMatch(99, 9, 100, 1, true, true)), runtime, identities))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime matched order is not open: 99");
        RuntimeStateParityChecker.assertMatches(before, identities, runtime);
    }

    @Test
    void transitionOnlyRevisesUsersInAuthoritativeDelta() {
        CoreInstrumentState instrument = instrument();
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 1);
        CoreOrderState maker = order(12, 8, CoreOrderSide.SELL, 1);
        CoreUserState unrelated = CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 99);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 100), 8L, user(8, maker, 100), 99L, unrelated),
                Map.of(11L, taker, 12L, maker), Map.of(instrument.symbol(), instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        List<CoreMatch> matches = List.of(new CoreMatch(12, 8, 100, 1, true, true));
        TradingCoreState expected = new TradingCoreReducer().applyMatches(before, 11, "BTC", "USDT", matches);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        runtime.putUser(new UserRuntime(ProductLine.LINEAR_PERPETUAL, 99, 999, unrelated.positionMode()));

        RuntimePerpetualMatchProcessor.applyTransition(before, expected, 11, matches, runtime, identities);

        assertThat(runtime.user(99).revision()).isEqualTo(999);
    }

    @Test
    void multiMatchSimulationEqualsAuthoritativeReducer() {
        CoreInstrumentState instrument = instrument();
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 2);
        CoreOrderState makerOne = order(12, 8, CoreOrderSide.SELL, 1);
        CoreOrderState makerTwo = order(13, 9, CoreOrderSide.SELL, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 200), 8L, user(8, makerOne, 100), 9L, user(9, makerTwo, 100)),
                Map.of(11L, taker, 12L, makerOne, 13L, makerTwo),
                Map.of(instrument.symbol(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        List<CoreMatch> matches = List.of(
                new CoreMatch(12, 8, 100, 1, true, false),
                new CoreMatch(13, 9, 100, 1, true, true));
        TradingCoreState after = new TradingCoreReducer().applyMatches(before, 11, "BTC", "USDT", matches);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState simulated = RuntimePerpetualMatchProcessor.simulate(before, 11, matches, identities);

        int assetId = identities.assetId("USDT");
        assertThat(simulated.treasury().fee(assetId))
                .isEqualTo(after.treasuryState().feeBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insurance(assetId))
                .isEqualTo(after.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insuranceDeficit(assetId))
                .isEqualTo(after.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));

        RuntimeStateParityChecker.assertMatches(after, identities, simulated);
    }

    @Test
    void partialCloseSimulationEqualsAuthoritativeReducer() {
        assertCloseParity(1, 100);
    }

    @Test
    void reversalSimulationEqualsAuthoritativeReducer() {
        assertCloseParity(3, 200);
    }

    @Test
    void emptyMarketMatchReleasesTerminalReservationLikeReducer() {
        CoreInstrumentState instrument = instrument();
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 2,
                false, CoreOrderType.MARKET, CoreTimeInForce.IOC);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 200)), Map.of(11L, taker),
                Map.of(instrument.symbol(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        TradingCoreState after = new TradingCoreReducer().applyMatches(before, 11, "BTC", "USDT", List.of());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        TradingRuntimeState simulated = RuntimePerpetualMatchProcessor.simulate(before, 11, List.of(), identities);

        RuntimeStateParityChecker.assertMatches(after, identities, simulated);
    }

    @Test
    void betterPricedSellActiveCloseSettlesExactFeeAndMatchesRuntime() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState state = reducer.upsertInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                liveInstrument());
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 10_000_000_000L));
        state = reducer.adjustBalance(state, 8, new BalanceAdjustmentCommand("USDT", 2_000_000_000_000L));
        PlaceOrderCommand open = marketOrder(11, CoreOrderSide.BUY, false, 788_640);
        TradingCoreState underfunded = state;
        assertThatThrownBy(() -> reducer.placeOrder(underfunded, 7, open))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));

        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 990_000_000_000L));
        TradingCoreState invalidOpen = reducer.placeOrder(state, 8,
                limitOrder(21, CoreOrderSide.SELL, 773_332, false));
        invalidOpen = reducer.placeOrder(invalidOpen, 7,
                marketOrder(22, CoreOrderSide.BUY, false, 773_022));
        TradingCoreState invalidOpenFill = invalidOpen;
        assertThatThrownBy(() -> reducer.applyMatches(invalidOpenFill, 22, "BTC", "USDT",
                List.of(new CoreMatch(21, 8, 773_332, 1, true, true))))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_ORDER_RESERVATION"));

        state = reducer.placeOrder(state, 8, limitOrder(12, CoreOrderSide.SELL, 773_333, false));
        state = reducer.placeOrder(state, 7, open);
        state = reducer.applyMatches(state, 11, "BTC", "USDT",
                List.of(new CoreMatch(12, 8, 773_333, 1, true, true)));
        assertThat(state.user(7).balances().get("USDT"))
                .isEqualTo(new AssetBalance("USDT", 918_800_035_000L, 77_333_300_000L));

        state = reducer.placeOrder(state, 8, limitOrder(13, CoreOrderSide.BUY, 773_332, true));
        state = reducer.placeOrder(state, 7, marketOrder(14, CoreOrderSide.SELL, true, 773_022));
        assertThat(state.user(7).reservations().get(14L).remainingUnits()).isEqualTo(3_865_110_000L);

        TradingCoreState readyToClose = state;
        TradingCoreState boundaryFill = reducer.applyMatches(readyToClose, 14, "BTC", "USDT",
                List.of(new CoreMatch(13, 8, 773_022, 1, true, true)));
        assertThat(boundaryFill.user(7).positions().get("BTC-USDT").signedQuantitySteps()).isZero();

        List<CoreMatch> betterFill = List.of(new CoreMatch(13, 8, 773_332, 1, true, true));
        long availableBeforeClose = readyToClose.user(7).balances().get("USDT").availableUnits();
        TradingCoreState marginFundedClose = reducer.adjustBalance(readyToClose, 7,
                new BalanceAdjustmentCommand("USDT", Math.negateExact(availableBeforeClose)));
        marginFundedClose = reducer.applyMatches(marginFundedClose, 14, "BTC", "USDT", betterFill);
        assertThat(marginFundedClose.user(7).balances().get("USDT"))
                .isEqualTo(new AssetBalance("USDT", 77_321_750_000L, 0));

        TradingCoreState closed = reducer.applyMatches(readyToClose, 14, "BTC", "USDT", betterFill);
        assertThat(closed.user(7).positions().get("BTC-USDT").signedQuantitySteps()).isZero();
        assertThat(closed.user(7).reservations().get(14L).reservedUnits()).isEqualTo(3_865_110_000L);
        assertThat(closed.user(7).reservations().get(14L).remainingUnits()).isZero();
        assertThat(closed.user(7).balances().get("USDT"))
                .isEqualTo(new AssetBalance("USDT", 992_256_675_000L, 0));
        assertThat(closed.treasuryState().feeBalances()).containsEntry("USDT", 7_733_325_000L);
        assertThat(closed.user(7).totalUnits("USDT") + closed.user(8).totalUnits("USDT")
                + closed.treasuryState().feeBalances().get("USDT")).isEqualTo(3_000_000_000_000L);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimePerpetualMatchProcessor.simulateTransition(
                readyToClose, closed, 14, betterFill, identities);
        RuntimeStateParityChecker.assertMatches(closed, identities, runtime);
    }

    private static void assertCloseParity(long quantity, long takerReservation) {
        CoreInstrumentState instrument = instrument();
        CoreOrderState taker = order(11, 7, CoreOrderSide.SELL, quantity);
        CoreOrderState maker = order(12, 8, CoreOrderSide.BUY, quantity);
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", 1,
                2, 100, 200, 0, 20);
        CoreUserState takerUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 20 + takerReservation)),
                Map.of(11L, OrderReservation.create(11, "BTC-USDT", 1,
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", takerReservation, quantity)),
                Map.of(position.key(), position));
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, takerUser, 8L, user(8, maker, 300)), Map.of(11L, taker, 12L, maker),
                Map.of(instrument.symbol(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        List<CoreMatch> matches = List.of(new CoreMatch(12, 8, 120, quantity, true, true));
        TradingCoreState after = new TradingCoreReducer().applyMatches(before, 11, "BTC", "USDT", matches);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        TradingRuntimeState simulated = RuntimePerpetualMatchProcessor.simulate(before, 11, matches, identities);

        int assetId = identities.assetId("USDT");
        assertThat(simulated.treasury().fee(assetId))
                .isEqualTo(after.treasuryState().feeBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insurance(assetId))
                .isEqualTo(after.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insuranceDeficit(assetId))
                .isEqualTo(after.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));

        RuntimeStateParityChecker.assertMatches(after, identities, simulated);
    }

    private static CoreUserState user(long userId, CoreOrderState order, long reservedUnits) {
        OrderReservation reservation = OrderReservation.create(order.orderId(), order.symbol(),
                order.instrumentVersion(), ReservationKind.DERIVATIVE_MARGIN, "USDT", reservedUnits,
                order.quantitySteps());
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, userId, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, reservedUnits)),
                Map.of(order.orderId(), reservation), Map.of());
    }

    private static CoreOrderState order(long orderId, long userId, CoreOrderSide side, long quantity) {
        return order(orderId, userId, side, quantity, false, CoreOrderType.LIMIT, CoreTimeInForce.GTC);
    }

    private static CoreOrderState order(long orderId, long userId, CoreOrderSide side, long quantity,
                                        boolean reduceOnly, CoreOrderType orderType, CoreTimeInForce timeInForce) {
        return new CoreOrderState(orderId, ProductLine.LINEAR_PERPETUAL, userId, "BTC-USDT", 1,
                side, orderType == CoreOrderType.MARKET ? 0 : 100, quantity, 0, quantity, reduceOnly,
                CoreMarginMode.CROSS, CorePositionSide.NET, orderType, timeInForce, false, "", new UUID(0, orderId),
                10_000, 10_000, CoreOrderStatus.OPEN, 1);
    }

    private static CoreInstrumentState instrument() {
        return CoreInstrumentState.from(ProductLine.LINEAR_PERPETUAL,
                new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0,
                        10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000,
                                10_000_000, 100_000, 50_000))));
    }

    private static UpsertInstrumentCommand liveInstrument() {
        return new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 10_000_000L, 10_000_000L, 100_000_000L,
                        10_000L, 5_000L, 0, 500L, 0, -1, 0,
                        100_000_000L, 1_000_000_000_000_000L, 1_000_000L, 25_000_000_000_000L,
                        List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L,
                                100_000_000L, 5_000L, 5_000L)));
    }

    private static PlaceOrderCommand marketOrder(long orderId, CoreOrderSide side,
                                                  boolean reduceOnly, long matchingPriceTicks) {
        return new PlaceOrderCommand(
                orderId,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                side,
                matchingPriceTicks,
                matchingPriceTicks,
                matchingPriceTicks,
                matchingPriceTicks,
                1,
                reduceOnly,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                CoreOrderType.MARKET,
                CoreTimeInForce.IOC,
                false,
                "order-" + orderId,
                0,
                500
        );
    }

    private static PlaceOrderCommand limitOrder(long orderId, CoreOrderSide side,
                                                 long priceTicks, boolean reduceOnly) {
        return new PlaceOrderCommand(
                orderId,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                side,
                priceTicks,
                priceTicks,
                priceTicks,
                priceTicks,
                1,
                reduceOnly,
                CoreMarginMode.CROSS,
                CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN,
                "USDT",
                0,
                CoreOrderType.LIMIT,
                CoreTimeInForce.GTC,
                false,
                "order-" + orderId,
                0,
                0
        );
    }
}
