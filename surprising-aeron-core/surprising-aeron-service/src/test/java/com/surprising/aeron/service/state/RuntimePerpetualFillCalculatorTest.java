package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimePerpetualFillCalculatorTest {

    @Test
    void opensLinearPositionAndPreservesExplainedLockedFunds() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtime(symbolId, assetId, 200);
        OrderRuntime order = runtime.order(11);

        RuntimePerpetualFillCalculator.apply(runtime, identities, instrument, order,
                positionKey, 100, 2, true, 10_000_000, assetId);
        runtime.releaseTerminalReservation(11);

        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(2);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.positionMarginUnits()).isEqualTo(20);
        assertThat(runtime.reservation(11).reservedUnits()).isZero();
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(978);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(20);
        assertThat(runtime.reservation(11).reservedUnits() + position.positionMarginUnits())
                .isEqualTo(runtime.balance(7, assetId).lockedUnits());
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(2);
        assertThat(runtime.order(11).executedQuantitySteps()).isEqualTo(2);
        assertThat(runtime.order(11).canceled()).isTrue();
    }

    @Test
    void rejectsInsufficientReservationBeforeAnyRuntimeMutation() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtime(symbolId, assetId, 21);
        TradingRuntimeSnapshot before = runtime.snapshot(1);

        assertThatThrownBy(() -> RuntimePerpetualFillCalculator.apply(runtime, identities, instrument,
                runtime.order(11), positionKey, 100, 2, true, 10_000_000, assetId))
                .isInstanceOf(CoreStateRejectedException.class);

        assertThat(runtime.snapshot(1)).isEqualTo(before);
    }

    @Test
    void partiallyClosesPositionAndRealizesProfit() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.SELL, 1, false, 800, 120, 100);

        RuntimePerpetualFillCalculator.apply(runtime, identities, instrument, runtime.order(11),
                positionKey, 110, 1, true, 10_000_000, assetId);
        runtime.releaseTerminalReservation(11);

        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(1);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.realizedPnlUnits()).isEqualTo(10);
        assertThat(position.positionMarginUnits()).isEqualTo(10);
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(918);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(10);
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(2);
        assertThat(runtime.treasury().insurance(assetId)).isEqualTo(-10);
        assertThat(runtime.treasury().insuranceDeficit(assetId)).isZero();
    }

    @Test
    void addsMarginOnlyForTheNewQuantityAtItsFillPrice() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.BUY, 1, false, 800, 40, 20);

        RuntimePerpetualFillCalculator.apply(runtime, identities, instrument, runtime.order(11),
                positionKey, 120, 1, true, 10_000_000, assetId);
        runtime.releaseTerminalReservation(11);

        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(3);
        assertThat(position.positionMarginUnits()).isEqualTo(32);
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(806);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(32);
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(2);
    }

    @Test
    void reversesPositionAtFillPrice() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.SELL, 3, false, 800, 220, 200);

        RuntimePerpetualFillCalculator.apply(runtime, identities, instrument, runtime.order(11),
                positionKey, 120, 3, true, 10_000_000, assetId);
        runtime.releaseTerminalReservation(11);

        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(-1);
        assertThat(position.entryPriceTicks()).isEqualTo(120);
        assertThat(position.realizedPnlUnits()).isEqualTo(40);
        assertThat(position.positionMarginUnits()).isEqualTo(12);
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(1_044);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(12);
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(4);
        assertThat(runtime.treasury().insurance(assetId)).isEqualTo(-40);
        assertThat(runtime.treasury().insuranceDeficit(assetId)).isZero();
    }

    @Test
    void rejectsReduceOnlyReversalWithoutMutation() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.SELL, 3, true, 800, 220, 200);
        TradingRuntimeSnapshot before = runtime.snapshot(1);

        assertThatThrownBy(() -> RuntimePerpetualFillCalculator.apply(runtime, identities, instrument,
                runtime.order(11), positionKey, 120, 3, true, 10_000_000, assetId))
                .isInstanceOf(CoreStateRejectedException.class);
        assertThat(runtime.snapshot(1)).isEqualTo(before);
    }

    private static TradingRuntimeState runtime(int symbolId, int assetId, long reservationUnits) {
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.putUser(new UserRuntime(7));
        runtime.putBalance(new BalanceRuntime(7, assetId, 800, 200));
        runtime.putOrder(new OrderRuntime(11, 7, symbolId, 1, CoreOrderSide.BUY, 100, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 10_000, 2, 0, 2, false));
        runtime.putReservation(new ReservationRuntime(11, 7, assetId, reservationUnits));
        return runtime;
    }

    private static TradingRuntimeState runtimeWithPosition(int symbolId, int assetId, long positionKey,
                                                           CoreOrderSide side, long quantity, boolean reduceOnly,
                                                           long available, long locked, long reservationUnits) {
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.putUser(new UserRuntime(7));
        runtime.putBalance(new BalanceRuntime(7, assetId, available, locked));
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId, CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 2, 100, 200, 0, 20));
        runtime.putOrder(new OrderRuntime(11, 7, symbolId, 1, side, 120, reduceOnly,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 10_000, quantity, 0, quantity, false));
        runtime.putReservation(new ReservationRuntime(11, 7, assetId, reservationUnits));
        return runtime;
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
