package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeStateProjectorTest {

    @Test
    void preparesUnusedInstrumentIdentityForFirstOrderAfterSnapshotRestore() {
        TradingCoreState source = new TradingCoreReducer().upsertInstrument(
                TradingCoreState.empty(ProductLine.SPOT),
                new UpsertInstrumentCommand("NEW-SPOT-USDT", 1, ContractType.SPOT.ordinal(),
                        "NEW", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        RuntimeStateProjector.project(source, identities);

        assertThat(identities.findSymbolId("NEW-SPOT-USDT")).isNotNull();
    }

    @Test
    void appliesOneRuntimePatchWithoutFreezingUntilRequested() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        before = reducer.adjustBalance(before, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        before = reducer.adjustBalance(before, 9, new BalanceAdjustmentCommand("USDT", 2_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 250));
        RuntimeCommitPatch patch = capture(runtime, identities, before, 1, before.revision());
        RuntimeProjectionState projection = projection(before);
        projection.apply(patch);
        TradingCoreState after = projection.freeze(1);

        assertThat(after).isEqualTo(RuntimeStateMaterializer.materialize(runtime, identities));
        assertThat(projection.freeze(1)).isSameAs(after);
    }

    @Test
    void capturedMutationDeltaIsImmutableAndProjectsWithoutReadingLaterRuntimeChanges() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        before = reducer.adjustBalance(before, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 250));
        RuntimeCommitPatch captured = capture(runtime, identities, before, 1, before.revision());
        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 100));

        TradingCoreState projected = project(before, captured);

        assertThat(projected.user(7).balances().get("USDT").availableUnits()).isEqualTo(1_250);
        assertThat(runtime.balance(7, identities.assetId("USDT")).availableUnits()).isEqualTo(1_350);
    }

    @Test
    void dirtyKeyDeltaKeepsDeterministicOrderWithoutPersistentTreeNodesOnTheOwner() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        before = reducer.adjustBalance(before, 9, new BalanceAdjustmentCommand("USDT", 2_000));
        before = reducer.adjustBalance(before, 7, new BalanceAdjustmentCommand("USDT", 1_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeCommandProcessor.adjustBalance(runtime, identities, 9,
                new BalanceAdjustmentCommand("USDT", 1));
        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 1));
        RuntimeCommitPatch patch = capture(runtime, identities, before, 1, before.revision());

        assertThat(patch.changedUserIds()).containsExactly(7L, 9L);
        assertThat(patch.accountLaneGroups()).isSortedAccordingTo(
                java.util.Comparator.comparingInt(RuntimeCommitPatch.AccountLaneOwnerGroup::laneId));
        assertThat(patch.accountLaneGroups().stream().flatMap(group -> group.balances().stream())
                .map(change -> change.key().userId())).containsExactlyInAnyOrder(7L, 9L);
        runtime.close();
    }

    @Test
    void emptyTypedPatchUsesImmutableOwnerGroups() {
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeCommitPatch patch = capture(runtime, identities, before, 1, before.revision());

        assertThat(patch.accountLaneGroups()).isEmpty();
        assertThat(patch.globalOwnerGroup().treasuryAssets()).isEmpty();
        assertThatThrownBy(() -> patch.accountLaneGroups().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        runtime.close();
    }

    @Test
    void riskSnapshotMutationCapturesItsAccountLaneWhenAnotherUserChanged() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState instrumentState = reducer.upsertInstrument(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new UpsertInstrumentCommand("BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0));
        TradingRuntimeState topologyProbe = new TradingRuntimeState();
        long firstUser = 1;
        long secondUser = 2;
        while (topologyProbe.topology().accountLaneId(firstUser)
                == topologyProbe.topology().accountLaneId(secondUser)) secondUser++;
        topologyProbe.close();
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", 1, 1, 100, 100, 0, 0);
        CoreUserState first = new CoreUserState(ProductLine.LINEAR_PERPETUAL, firstUser, 1,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)), Map.of(), Map.of());
        CoreUserState second = new CoreUserState(ProductLine.LINEAR_PERPETUAL, secondUser, 1,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)), Map.of(),
                Map.of(position.key(), position));
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(firstUser, first, secondUser, second), Map.of(), instrumentState.instruments(),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeCommandProcessor.adjustBalance(runtime, identities, firstUser,
                new BalanceAdjustmentCommand("USDT", 1));
        long positionKey = identities.positionKey(secondUser, position.key());
        runtime.putRiskSnapshot(positionKey, new RiskSnapshotRuntime(secondUser,
                identities.symbolId("BTC-USDT"), CorePositionSide.NET, 1,
                1_000, 0, 10, 10_000, CoreRiskStatus.NORMAL));

        RuntimeCommitPatch patch = capture(runtime, identities, before, 1, before.revision());
        TradingCoreState projected = project(before, patch);

        assertThat(projected.riskState().snapshots()).containsKey(secondUser + ":BTC-USDT");
        runtime.close();
    }

    @Test
    void typedCommitProjectsOffOwnerAndPreservesRollingHashes() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState before = reducer.adjustBalance(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                7, new BalanceAdjustmentCommand("USDT", 1_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        long patchRevision = before.revision();
        RollingBusinessStateHash businessHash = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash fundsHash = RollingFundsStateHash.create(before, identities);

        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 250));
        RuntimeCommitPatch commit = capture(runtime, identities, before, 1, patchRevision);
        TradingCoreState expected = project(before, commit);
        businessHash.update(commit);
        fundsHash.update(commit);

        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.LINEAR_PERPETUAL, before, before.businessStateHash(),
                RollingFundsStateHash.compute(before))) {
            journal.publish(commit, businessHash.value(), fundsHash.value());
            RuntimeCommitJournal.ProjectionVersion projected = journal.await(
                    1, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5), true);

            assertThat(journal.await(commit.projectionPoint())).isEqualTo(expected);
            assertThat(projected.state()).isEqualTo(expected);
            assertThat(projected.businessStateHash()).isEqualTo(expected.businessStateHash());
            assertThat(projected.fundsStateHash()).isEqualTo(RollingFundsStateHash.compute(expected));
        }
    }

    @Test
    void projectsContiguousTypedCommitsWithoutChangingTheFinalState() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState before = reducer.adjustBalance(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                7, new BalanceAdjustmentCommand("USDT", 1_000));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        long patchRevision = before.revision();
        RollingBusinessStateHash businessHash = RollingBusinessStateHash.create(before, identities);
        RollingFundsStateHash fundsHash = RollingFundsStateHash.create(before, identities);

        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 250));
        RuntimeCommitPatch first = capture(runtime, identities, before, 1, patchRevision);
        patchRevision = first.revision();
        runtime.clearChangedKeys();
        RuntimeProjectionState replica = projection(before);
        replica.apply(first);
        TradingCoreState firstState = replica.freeze(1);
        businessHash.update(first);
        fundsHash.update(first);

        RuntimeCommandProcessor.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", -100));
        RuntimeCommitPatch second = capture(runtime, identities, firstState, 2, patchRevision);
        replica.apply(second);
        TradingCoreState expected = replica.freeze(2);
        businessHash.update(second);
        fundsHash.update(second);

        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.LINEAR_PERPETUAL, before, before.businessStateHash(),
                RollingFundsStateHash.compute(before))) {
            journal.publish(first, first.businessStateHash(),
                    first.fundsStateHash());
            journal.publish(second, second.businessStateHash(), second.fundsStateHash());
            RuntimeCommitJournal.ProjectionVersion projected = journal.await(
                    2, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5), true);
            assertThat(projected.state()).isEqualTo(expected);
        }
    }

    @Test
    void keepsRuntimeInParityAcrossIncrementalPlaceAndStampTransitions() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState state = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        state = reducer.upsertInstrument(state, new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 1_000_000));
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("BTC-USDT", 1, 1_000, 1, 1));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);

        for (int index = 0; index < 50; index++) {
            long orderId = 10_000 + index;
            PlaceOrderCommand command = new PlaceOrderCommand(orderId, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.IOC, false, "incremental-" + orderId);
            ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 7, command, 1);
            RuntimeCommandProcessor.placeOrder(runtime, identities, 7, resolved, new UUID(0, orderId), 100);
            TradingCoreState placed = RuntimeStateMaterializer.materialize(runtime, identities);
            runtime.clearChangedKeys();
            RuntimeCommandProcessor.stampOrderChanges(runtime, identities, state,
                    1_000 + index, 2_000 + index, java.util.List.of(orderId));
            TradingCoreState stamped = RuntimeStateMaterializer.materialize(runtime, identities);
            runtime.clearChangedKeys();
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
    void pendingPlaceCompletionPublishesClientIndexWhenRuntimeIdentityIsUnchanged() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        before = reducer.upsertInstrument(before, new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0));
        before = reducer.adjustBalance(before, 7, new BalanceAdjustmentCommand("USDT", 1_000_000));
        before = reducer.applyMarkPrice(before, new ApplyMarkPriceCommand("BTC-USDT", 1, 1_000, 1, 1));
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        PlaceOrderCommand command = new PlaceOrderCommand(11, "BTC-USDT", 1, CoreOrderSide.BUY,
                1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "pending-client-11");
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 7, command, 1);
        RuntimeCommandProcessor.placeOrder(runtime, identities, 7, resolved, new UUID(0, 11), 100);
        runtime.markPendingReservation(7, 11, 1);
        runtime.clearChangedKeys();

        runtime.completePendingReservation(7, 11, 1);
        RuntimeCommandProcessor.stampOrderChanges(runtime, identities, before, 1_000, 2_000, List.of(11L));
        RuntimeCommitPatch patch = capture(runtime, identities, before, 1, before.revision());

        TradingCoreState projected = project(before, patch);
        assertThat(projected.order(11)).isNotNull();
        assertThat(projected.order(7, "pending-client-11").orderId()).isEqualTo(11);
        runtime.close();
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
        RuntimeCommandProcessor.cancelOrder(runtime, 7, 11);

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
        RuntimeCommandProcessor.cancelOrder(runtime, 7, 11);

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
    void materializesSharedGlobalUserStateDeterministically() {
        // Given
        OrderReservation firstReservation = new OrderReservation(11, "BTC-USDT", 4,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 500, 100, 300, 5);
        CorePositionState firstPosition = new CorePositionState("BTC-USDT", "USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 4, 2, 100, 200, 9, 100);
        OrderReservation additionalFirstReservation = new OrderReservation(22, "ETH-USDT", 3,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 400, 200, 150, 4);
        CorePositionState additionalFirstPosition = new CorePositionState("ETH-USDT", "USDT",
                CoreMarginMode.CROSS, CorePositionSide.SHORT, 3, -2, 80, 160, -7, 50);
        CoreUserState firstUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 8,
                Map.of("USDT", new AssetBalance("USDT", 700, 300)),
                Map.of(22L, additionalFirstReservation, 11L, firstReservation),
                Map.of(additionalFirstPosition.key(), additionalFirstPosition, firstPosition.key(), firstPosition),
                CorePositionMode.HEDGE);
        OrderReservation secondReservation = new OrderReservation(33, "ETH-USDT", 3,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 400, 50, 100, 4);
        CorePositionState secondPosition = new CorePositionState("ETH-USDT", "USDT",
                CoreMarginMode.CROSS, CorePositionSide.SHORT, 3, -2, 80, 160, -7, 50);
        CoreUserState secondUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 9, 5,
                Map.of("USDT", new AssetBalance("USDT", 900, 300)), Map.of(33L, secondReservation),
                Map.of(secondPosition.key(), secondPosition), CorePositionMode.HEDGE);
        TradingCoreState source = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 12,
                Map.of(9L, secondUser, 7L, firstUser), Map.of(), Map.of(), CoreRiskState.empty(),
                CoreTreasuryState.empty());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.positionKey(7, additionalFirstPosition.key());
        identities.positionKey(7, firstPosition.key());
        TradingRuntimeState runtime = RuntimeStateProjector.project(source, identities);
        runtime.removeReservation(11, 7);
        runtime.putReservation(RuntimeStateProjector.toRuntimeReservation(7, firstReservation, identities));
        long firstPositionKey = identities.positionKey(7, firstPosition.key());
        runtime.removePosition(firstPositionKey, 7);
        runtime.putPosition(firstPositionKey, new PositionRuntime(7,
                identities.symbolId(firstPosition.symbol()), identities.assetId(firstPosition.marginAsset()),
                firstPosition.marginMode(), firstPosition.positionSide(), firstPosition.instrumentVersion(),
                firstPosition.signedQuantitySteps(), firstPosition.entryPriceTicks(), firstPosition.entryValueTicks(),
                firstPosition.realizedPnlUnits(), firstPosition.positionMarginUnits()));
        RuntimeStateMaterializer.SnapshotTraversalProbe traversalProbe =
                new RuntimeStateMaterializer.SnapshotTraversalProbe();

        List<Long> runtimeFirstUserReservationOrder = new ArrayList<>();
        runtime.reservationsForSnapshot().forEachKeyValue((orderId, reservation) -> {
            if (reservation.userId() == 7) runtimeFirstUserReservationOrder.add(orderId);
        });
        List<String> runtimeFirstUserPositionOrder = new ArrayList<>();
        runtime.positionsForSnapshot().forEachKeyValue((positionKey, position) -> {
            if (position.userId() == 7) runtimeFirstUserPositionOrder.add(identities.positionKey(7, positionKey));
        });
        assertThat(runtimeFirstUserReservationOrder).isNotEqualTo(List.of(11L, 22L));
        assertThat(runtimeFirstUserPositionOrder).isNotEqualTo(List.of("BTC-USDT:LONG", "ETH-USDT:SHORT"));

        // When
        TradingCoreState first = RuntimeStateMaterializer.materialize(runtime, identities, traversalProbe);
        TradingCoreState repeated = RuntimeStateMaterializer.materialize(runtime, identities);

        // Then
        assertThat(first).isEqualTo(source);
        assertThat(first.businessStateHash()).isEqualTo(source.businessStateHash());
        assertThat(repeated).isEqualTo(first);
        assertThat(repeated.businessStateHash()).isEqualTo(first.businessStateHash());
        assertThat(new ArrayList<>(first.users().keySet())).isEqualTo(List.of(7L, 9L));
        assertThat(new ArrayList<>(first.users().get(7L).reservations().keySet())).isEqualTo(List.of(11L, 22L));
        assertThat(first.users().get(9L).reservations()).containsOnlyKeys(33L);
        assertThat(new ArrayList<>(first.users().get(7L).positions().keySet()))
                .isEqualTo(List.of("BTC-USDT:LONG", "ETH-USDT:SHORT"));
        assertThat(first.users().get(9L).positions()).containsOnlyKeys("ETH-USDT:SHORT");
        assertThat(traversalProbe.reservationTraversals()).isEqualTo(1);
        assertThat(traversalProbe.reservationEntries()).isEqualTo(3);
        assertThat(traversalProbe.positionTraversals()).isEqualTo(1);
        assertThat(traversalProbe.positionEntries()).isEqualTo(3);
    }

    @Test
    void rejectsGlobalStateOwnedByAnUnknownRuntimeUser() {
        CoreUserState user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 1_000, 0)), Map.of(), Map.of());
        TradingCoreState source = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user), Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        RuntimeIdentityRegistry reservationIdentities = new RuntimeIdentityRegistry();
        TradingRuntimeState reservationRuntime = RuntimeStateProjector.project(source, reservationIdentities);
        reservationRuntime.putReservation(new ReservationRuntime(99, 99,
                reservationIdentities.symbolId("BTC-USDT"), 1, ReservationKind.DERIVATIVE_MARGIN,
                reservationIdentities.assetId("USDT"), 100, 0, 0, 1));

        assertThatThrownBy(() -> RuntimeStateMaterializer.materialize(reservationRuntime, reservationIdentities))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation owner is not registered: 99");

        RuntimeIdentityRegistry positionIdentities = new RuntimeIdentityRegistry();
        TradingRuntimeState positionRuntime = RuntimeStateProjector.project(source, positionIdentities);
        long positionKey = positionIdentities.positionKey(99, "BTC-USDT:LONG");
        positionRuntime.replacePosition(positionKey, new PositionRuntime(99,
                positionIdentities.symbolId("BTC-USDT"), positionIdentities.assetId("USDT"),
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 1, 1, 100, 100, 0, 100));

        assertThatThrownBy(() -> RuntimeStateMaterializer.materialize(positionRuntime, positionIdentities))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position owner is not registered: 99");
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

    private static com.surprising.aeron.protocol.CoreMatcherTransition unchangedMatcher() {
        return com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0);
    }

    private static RuntimeProjectionState projection(TradingCoreState state) {
        return new RuntimeProjectionState(state, state.businessStateHash(), RollingFundsStateHash.compute(state));
    }

    private static TradingCoreState project(TradingCoreState state, RuntimeCommitPatch patch) {
        RuntimeProjectionState projection = projection(state);
        projection.apply(patch);
        return projection.freeze(patch.sequence());
    }

    private static RuntimeCommitPatch capture(TradingRuntimeState runtime,
                                              RuntimeIdentityRegistry identities,
                                              TradingCoreState previous,
                                              long projectionSequence,
                                              long previousRevision) {
        TradingCoreState current = RuntimeStateMaterializer.materialize(runtime, identities);
        TradingRuntimeState.PreparedCommit prepared = runtime.prepareCommitPatch(
                projectionSequence, identities,
                previousRevision, unchangedMatcher(), 0,
                previous.businessStateHash(), current.businessStateHash(),
                RollingFundsStateHash.compute(previous), RollingFundsStateHash.compute(current), true);
        RuntimeCommitPatch.PreparedChanges changes = prepared.prepareChanges();
        return prepared.seal(changes, current.businessStateHash(), RollingFundsStateHash.compute(current));
    }

}
