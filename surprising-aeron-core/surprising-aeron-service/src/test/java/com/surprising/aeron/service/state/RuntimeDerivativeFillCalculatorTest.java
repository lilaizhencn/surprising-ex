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

class RuntimeDerivativeFillCalculatorTest {

    @Test
    void takerCursorPreservesPerFillRoundingReversalAndRevisionCounts() {
        var identities = new RuntimeIdentityRegistry();
        var instrument = instrument();
        int symbol = identities.symbolId(instrument.symbol()), asset = identities.assetId(instrument.settleAsset());
        long key = identities.positionKey(7, instrument.symbol());
        try (var sequential = runtimeWithPosition(symbol, asset, key, CoreOrderSide.SELL, 4, false, 10_000, 120, 100);
             var batched = runtimeWithPosition(symbol, asset, key, CoreOrderSide.SELL, 4, false, 10_000, 120, 100)) {
            var expectedTreasury = new RuntimeTreasuryDelta();
            var actualTreasury = new RuntimeTreasuryDelta();
            var cursor = RuntimeDerivativeFillCalculator.beginTaker(batched, instrument, batched.order(11),
                    key, 10_000_000, asset, 555, 999);
            var before = batched.snapshot(1);
            try {
                for (long price : new long[]{111, 109, 113, 107}) {
                    RuntimeDerivativeFillCalculator.apply(sequential, identities, instrument, sequential.order(11),
                            key, price, 1, true, 10_000_000, asset, expectedTreasury, 555, 999);
                    cursor.applyNext(price, 1, actualTreasury);
                }
                assertThat(batched.snapshot(1)).as("intermediate scalar state must not escape").isEqualTo(before);
                cursor.publish(batched);
            } finally { cursor.clear(); }
            expectedTreasury.apply(sequential.treasury());
            actualTreasury.apply(batched.treasury());
            assertThat(batched.snapshot(1)).isEqualTo(sequential.snapshot(1));
            assertThat(batched.position(key).signedQuantitySteps()).isEqualTo(-2);
            assertThat(batched.order(11).revision()).isEqualTo(5);
            assertThat(batched.user(7).revision()).isEqualTo(4);
        }
    }

    @Test
    void abortedTakerCursorDoesNotPublishPartialStateOrLeakIntoNextUse() {
        var identities = new RuntimeIdentityRegistry();
        var instrument = instrument();
        int symbol = identities.symbolId(instrument.symbol()), asset = identities.assetId(instrument.settleAsset());
        long key = identities.positionKey(7, instrument.symbol());
        try (var runtime = runtime(symbol, asset, 200)) {
            var before = runtime.snapshot(1);
            var cursor = RuntimeDerivativeFillCalculator.beginTaker(runtime, instrument, runtime.order(11),
                    key, 10_000_000, asset, 555, 999);
            try {
                cursor.applyNext(100, 1, new RuntimeTreasuryDelta());
                assertThatThrownBy(() -> cursor.applyNext(100, 2, new RuntimeTreasuryDelta()))
                        .isInstanceOf(IllegalArgumentException.class);
            } finally { cursor.clear(); }
            assertThat(runtime.snapshot(1)).isEqualTo(before);
            var retry = RuntimeDerivativeFillCalculator.beginTaker(runtime, instrument, runtime.order(11),
                    key, 10_000_000, asset, 555, 999);
            try {
                retry.applyNext(100, 2, new RuntimeTreasuryDelta());
                retry.publish(runtime);
            } finally { retry.clear(); }
            assertThat(runtime.order(11).executedQuantitySteps()).isEqualTo(2);
            assertThat(runtime.user(7).revision()).isEqualTo(1);
        }
    }

    @Test
    void opensLinearPositionAndPreservesExplainedLockedFunds() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        CoreInstrumentState instrument = instrument();
        int symbolId = identities.symbolId(instrument.symbol());
        int assetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(7, instrument.symbol());
        TradingRuntimeState runtime = runtime(symbolId, assetId, 200);
        OrderRuntime order = runtime.order(11);

        RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument, order,
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

        assertThatThrownBy(() -> RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument,
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

        RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument, runtime.order(11),
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
        assertThat(runtime.treasury().insurance(assetId)).isZero();
        assertThat(runtime.treasury().clearingPnl(assetId)).isEqualTo(-10);
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

        RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument, runtime.order(11),
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

        RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument, runtime.order(11),
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
        assertThat(runtime.treasury().insurance(assetId)).isZero();
        assertThat(runtime.treasury().clearingPnl(assetId)).isEqualTo(-40);
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

        assertThatThrownBy(() -> RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument,
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
