package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
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
}
