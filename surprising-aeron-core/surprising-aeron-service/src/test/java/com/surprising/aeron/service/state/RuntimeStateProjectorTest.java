package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeStateProjectorTest {

    @Test
    void keepsRuntimeInParityAcrossIncrementalPlaceAndStampTransitions() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState state = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        state = reducer.upsertInstrument(state, new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 1_000_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);

        for (int index = 0; index < 50; index++) {
            long orderId = 10_000 + index;
            PlaceOrderCommand command = new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                    "BTC", "USDT", "USDT", CoreOrderSide.BUY, 1_000, 1, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, ReservationKind.DERIVATIVE_MARGIN,
                    "USDT", 1_000, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 1_000, false,
                    "incremental-" + orderId, 0, 0);
            TradingCoreState placed = reducer.placeOrder(state, 7, command, new UUID(0, orderId), 0);
            RuntimeStateDeltaApplier.apply(state, placed, runtime, identities);
            TradingCoreState stamped = placed.stampOrderChanges(state, 1_000 + index, 2_000 + index,
                    java.util.List.of(orderId));
            RuntimeStateDeltaApplier.apply(placed, stamped, runtime, identities);
            state = stamped;
        }

        TradingCoreState materialized = RuntimeStateParityChecker.assertMatches(state, identities, runtime);
        assertThat(materialized).isEqualTo(state);
        assertThat(materialized.businessStateHash()).isEqualTo(state.businessStateHash());
        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(995_000);
        assertThat(runtime.balance(7, identities.assetId("USDT")).lockedUnits()).isEqualTo(5_000);
        assertThat(runtime.order(10_049).clusterPosition()).isEqualTo(2_049);
    }

    @Test
    void projectsBalancesOrdersAndReservationsWithoutChangingFunds() {
        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 200, 2);
        CoreUserState user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 200)),
                Map.of(11L, reservation), Map.of());
        CoreOrderState order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState source = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 3,
                Map.of(7L, user), Map.of(11L, order), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        TradingRuntimeState runtime = RuntimeStateProjector.project(source, new RuntimeIdentityRegistry());
        TradingRuntimeSnapshot snapshot = runtime.snapshot(source.revision());

        assertThat(snapshot.users()).containsKey(7L);
        assertThat(snapshot.orders()).containsKey(11L);
        assertThat(snapshot.reservations()).containsKey(11L);
        assertThat(snapshot.totalAvailableUnits()).isEqualTo(800);
        assertThat(snapshot.totalLockedUnits()).isEqualTo(200);
        assertThat(snapshot.orders().get(11L).quantitySteps()).isEqualTo(2);
    }

    @Test
    void clientKeysAreScopedByUser() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        long first = identities.clientKey(7, "same-client-id");
        long second = identities.clientKey(8, "same-client-id");

        assertThat(first).isNotEqualTo(second);
        assertThat(identities.clientKey(7, "same-client-id")).isEqualTo(first);
    }

    @Test
    void appliesOnlyThePlaceOrderDeltaToRuntime() {
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)), Map.of(), Map.of());
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 200, 2);
        CoreUserState afterUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 2,
                Map.of("USDT", new AssetBalance("USDT", 800, 200)),
                Map.of(11L, reservation), Map.of());
        CoreOrderState order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2,
                Map.of(7L, afterUser), Map.of(11L, order), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        RuntimePlaceOrderDeltaApplier.apply(before, after, 7, 11, runtime, identities);

        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(800);
        assertThat(runtime.balance(7, identities.assetId("USDT")).lockedUnits()).isEqualTo(200);
        assertThat(runtime.order(11).quantitySteps()).isEqualTo(2);
    }

    @Test
    void rejectsMismatchedPlaceOrderDeltaBeforeRuntimeMutation() {
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)), Map.of(), Map.of());
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 2);
        CoreUserState afterUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 2,
                Map.of("USDT", new AssetBalance("USDT", 800, 200)), Map.of(11L, reservation), Map.of());
        CoreOrderState order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2,
                Map.of(7L, afterUser), Map.of(11L, order), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimePlaceOrderDeltaApplier.apply(before, after, 7, 11, runtime, identities))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runtime.order(11)).isNull();
        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(1_000);
        assertThat(runtime.balance(7, identities.assetId("USDT")).lockedUnits()).isZero();
    }

    @Test
    void appliesCancellationReleaseAndMarksRuntimeOrderTerminal() {
        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 200, 2);
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 200)), Map.of(11L, reservation), Map.of());
        CoreOrderState open = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(11L, open), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        CoreUserState afterUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 2,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)),
                Map.of(11L, reservation.releaseAll()), Map.of());
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2,
                Map.of(7L, afterUser), Map.of(11L, open.cancel()), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        RuntimeCancelOrderDeltaApplier.apply(before, after, 7, 11, runtime, identities);

        assertThat(runtime.order(11).canceled()).isTrue();
        assertThat(runtime.reservation(11).reservedUnits()).isZero();
        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(1_000);
        assertThat(runtime.balance(7, identities.assetId("USDT")).lockedUnits()).isZero();
    }

    @Test
    void appliesCancellationAfterPartialFillWithoutReleasingConsumedReservation() {
        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 200, 2);
        OrderReservation partiallyConsumed = reservation.consume(100);
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 2,
                Map.of("USDT", new AssetBalance("USDT", 800, 100)), Map.of(11L, partiallyConsumed), Map.of());
        CoreOrderState open = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false,
                CoreOrderStatus.OPEN, 1);
        open = open.fill(1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(11L, open), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        CoreUserState afterUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 3,
                Map.of("USDT", new AssetBalance("USDT", 900, 0)),
                Map.of(11L, partiallyConsumed.release(100)), Map.of());
        CoreOrderState partiallyFilledAndCanceled = open.cancel();
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2,
                Map.of(7L, afterUser), Map.of(11L, partiallyFilledAndCanceled), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty());

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        RuntimeCancelOrderDeltaApplier.apply(before, after, 7, 11, runtime, identities);

        assertThat(runtime.order(11).canceled()).isTrue();
        assertThat(runtime.order(11).executedQuantitySteps()).isEqualTo(1);
        assertThat(runtime.reservation(11).consumedUnits()).isEqualTo(100);
        assertThat(runtime.reservation(11).releasedUnits()).isEqualTo(100);
        assertThat(runtime.reservation(11).reservedUnits()).isZero();
        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(900);
        assertThat(runtime.balance(7, identities.assetId("USDT")).lockedUnits()).isZero();
        RuntimeStateParityChecker.assertMatches(after, identities, runtime);
    }

    @Test
    void rejectsInvalidCancellationWithoutChangingRuntime() {
        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 200, 2);
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 200)), Map.of(11L, reservation), Map.of());
        CoreOrderState open = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(11L, open), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        CoreUserState invalidAfterUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 2,
                Map.of("USDT", new AssetBalance("USDT", 999, 0)),
                Map.of(11L, reservation.releaseAll()), Map.of());
        TradingCoreState invalidAfter = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2,
                Map.of(7L, invalidAfterUser), Map.of(11L, open.cancel()), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty());

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimeCancelOrderDeltaApplier.apply(before, invalidAfter,
                7, 11, runtime, identities)).isInstanceOf(IllegalStateException.class);
        assertThat(runtime.order(11).canceled()).isFalse();
        assertThat(runtime.reservation(11).reservedUnits()).isEqualTo(200);
        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(800);
        assertThat(runtime.balance(7, identities.assetId("USDT")).lockedUnits()).isEqualTo(200);
    }

    @Test
    void projectsPerpetualPositionsAndTreasury() {
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", 1,
                2, 100, 200, 3, 40);
        CoreUserState user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 960, 40)), Map.of(),
                Map.of(position.key(), position));
        CoreTreasuryState treasury = new CoreTreasuryState(
                Map.of("USDT", 7L), Map.of("USDT", 11L), Map.of(), Map.of(), Map.of());
        TradingCoreState source = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user), Map.of(), Map.of(), CoreRiskState.empty(), treasury);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(source, identities);
        PositionRuntime projected = runtime.position(identities.positionKey(7, position.key()));

        assertThat(projected.signedQuantitySteps()).isEqualTo(2);
        assertThat(projected.positionMarginUnits()).isEqualTo(40);
        assertThat(runtime.treasury().fee(identities.assetId("USDT"))).isEqualTo(7);
        assertThat(runtime.treasury().insurance(identities.assetId("USDT"))).isEqualTo(11);
    }

    @Test
    void materializesEveryOrderReservationAndUserAuditField() {
        UUID commandId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        OrderReservation reservation = new OrderReservation(11, "BTC-USDT", 4,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 500, 100, 200, 5);
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", CoreMarginMode.ISOLATED,
                CorePositionSide.LONG, 4, 2, 100, 200, 9, 100);
        CoreUserState user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 8,
                Map.of("USDT", new AssetBalance("USDT", 600, 300)), Map.of(11L, reservation),
                Map.of(position.key(), position), CorePositionMode.HEDGE);
        CoreOrderState order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7, "BTC-USDT", 4,
                CoreOrderSide.BUY, 100, 5, 2, 3, false, CoreMarginMode.ISOLATED, CorePositionSide.LONG,
                CoreOrderType.LIMIT, CoreTimeInForce.GTX, true, "client-11", commandId, 12, 34,
                1_000, 1_100, 99, CoreOrderStatus.OPEN, 3);
        TradingCoreState source = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 12,
                Map.of(7L, user), Map.of(11L, order), Map.of(), CoreRiskState.empty(),
                new CoreTreasuryState(Map.of("USDT", -3L), Map.of("USDT", 7L), Map.of(),
                        Map.of("BTC-USDT", 2L), Map.of()));

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(source, identities);
        TradingCoreState restored = RuntimeStateMaterializer.materialize(runtime, identities);

        assertThat(restored).isEqualTo(source);
        assertThat(restored.businessStateHash()).isEqualTo(source.businessStateHash());
    }

    @Test
    void restoresIdentityRegistryWithoutRenumberingKeys() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int assetId = identities.assetId("USDT");
        int symbolId = identities.symbolId("BTC-USDT");
        long clientKey = identities.clientKey(7, "client-11");
        long positionKey = identities.positionKey(7, "BTC-USDT:LONG");

        RuntimeIdentityRegistry restored = RuntimeIdentityRegistry.restore(identities.snapshot());

        assertThat(restored.asset(assetId)).isEqualTo("USDT");
        assertThat(restored.symbol(symbolId)).isEqualTo("BTC-USDT");
        assertThat(restored.clientOrderId(7, clientKey)).isEqualTo("client-11");
        assertThat(restored.positionKey(7, positionKey)).isEqualTo("BTC-USDT:LONG");
        assertThat(restored.assetId("USDT")).isEqualTo(assetId);
        assertThat(restored.symbolId("BTC-USDT")).isEqualTo(symbolId);
    }

    @Test
    void appliesGenericDeltaAndMaterializesTheExactNextState() {
        OrderReservation reservation = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 200, 2);
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 200)), Map.of(11L, reservation), Map.of());
        CoreOrderState open = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(11L, open), Map.of(), CoreRiskState.empty(),
                CoreTreasuryState.empty());
        CoreUserState afterUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 2,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)),
                Map.of(11L, reservation.releaseAll()), Map.of());
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2,
                Map.of(7L, afterUser), Map.of(11L, open.cancel()), Map.of(), CoreRiskState.empty(),
                new CoreTreasuryState(Map.of("USDT", 3L), Map.of(), Map.of(), Map.of(), Map.of()));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeStateDeltaApplier.apply(before, after, runtime, identities);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(after);
        RuntimeStateParityChecker.assertMatches(after, identities, runtime);
    }

    @Test
    void appliesAllNestedReservationRemovalsForOneUser() {
        OrderReservation first = OrderReservation.create(11, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 1).consume(100);
        OrderReservation second = OrderReservation.create(12, "BTC-USDT", 1,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 1).consume(100);
        CoreUserState beforeUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)), Map.of(11L, first, 12L, second), Map.of());
        CoreOrderState firstOrder = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1).fill(1);
        CoreOrderState secondOrder = new CoreOrderState(12, ProductLine.LINEAR_PERPETUAL, 7,
                "BTC-USDT", 1, CoreOrderSide.SELL, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1).fill(1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, beforeUser), Map.of(11L, firstOrder, 12L, secondOrder), Map.of(), CoreRiskState.empty(),
                CoreTreasuryState.empty());

        Map<Long, OrderReservation> firstRemoval = StateMapSupport.delta(beforeUser.reservations());
        firstRemoval.remove(11L);
        CoreUserState intermediate = beforeUser.transition(2, beforeUser.balances(), firstRemoval,
                beforeUser.positions(), beforeUser.positionMode());
        Map<Long, OrderReservation> secondRemoval = StateMapSupport.delta(intermediate.reservations());
        secondRemoval.remove(12L);
        CoreUserState afterUser = intermediate.transition(3, intermediate.balances(), secondRemoval,
                intermediate.positions(), intermediate.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(before.users());
        users.put(7L, intermediate);
        users.put(7L, afterUser);
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(before.orders());
        orders.remove(11L);
        orders.remove(12L);
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, users, orders, Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeStateDeltaApplier.apply(before, after, runtime, identities);

        assertThat(runtime.reservation(11)).isNull();
        assertThat(runtime.reservation(12)).isNull();
        RuntimeStateParityChecker.assertMatches(after, identities, runtime);
    }

}
