package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeCommitPatchTest {

    @Test
    void preservesDeterministicFirstTouchOrderWithoutSorting() {
        RuntimeCommitPatch first = populatedBuilder(false).seal(metadata());
        RuntimeCommitPatch reversed = populatedBuilder(true).seal(metadata());

        assertThat(first.previousCoreSequence()).isEqualTo(first.previousProjectionSequence());
        assertThat(first.coreSequence()).isEqualTo(first.projectionSequence());
        assertThat(first.laneMask()).isEqualTo((1L << 1) | (1L << 3));
        assertThat(first.accountLaneGroups()).extracting(RuntimeCommitPatch.AccountLaneOwnerGroup::laneId)
                .containsExactly(1, 3);
        assertThat(first.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId).containsExactly(2L, 9L);
        assertThat(first.fundsPostings()).extracting(RuntimeCommitPatch.FundsPosting::assetId)
                .containsExactly(2, 2);
        assertThat(first.matcherEvidence()).extracting(RuntimeCommitPatch.MatcherEvidence::matcherSequence)
                .containsExactly(8L, 7L);
        assertThat(first.terminalIds().orderIds()).containsExactly(4L, 12L);
        assertThat(reversed.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId).containsExactly(9L, 2L);
        assertThat(reversed.matcherEvidence()).extracting(RuntimeCommitPatch.MatcherEvidence::matcherSequence)
                .containsExactly(7L, 8L);
        assertThat(reversed.terminalIds().orderIds()).containsExactly(12L, 4L);
    }

    @Test
    void retainsFirstBeforeAndFinalAfterAndRepresentsDeletion() {
        UserRuntime before = user(7, 1);
        UserRuntime middle = user(7, 2);
        RuntimeCommitPatch.Builder builder = baseBuilder();
        builder.recordUser(1, before, middle);
        builder.recordUser(1, middle, null);
        builder.laneMask(1L << 1);

        RuntimeCommitPatch patch = builder.seal(metadata(1L << 1));

        assertThat(patch.accountLaneGroups().getFirst().users())
                .containsExactly(new RuntimeCommitPatch.UserChange(7, before, null));
        assertThatThrownBy(() -> patch.accountLaneGroups().add(patch.accountLaneGroups().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> patch.accountLaneGroups().getFirst().users().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInconsistentLaneMaskAndSequence() {
        assertThatThrownBy(() -> RuntimeCommitPatch.builder(ProductLine.LINEAR_PERPETUAL, 10, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
        assertThatThrownBy(() -> RuntimeCommitPatch.builder(ProductLine.LINEAR_PERPETUAL, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        RuntimeCommitPatch.Builder builder = baseBuilder();
        builder.recordUser(1, null, user(7, 1));
        builder.laneMask(1L << 1);
        assertThatThrownBy(() -> builder.seal(metadata(1L << 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lane mask");
    }

    @Test
    void rejectsProductLineConflictingLaneMaskAndSecondSeal() {
        RuntimeCommitPatch.Builder mismatch = baseBuilder();
        assertThatThrownBy(() -> mismatch.recordUser(1,
                new UserRuntime(ProductLine.SPOT, 7, 1, CorePositionMode.ONE_WAY), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product line");

        RuntimeCommitPatch.Builder conflictingLaneMask = baseBuilder();
        conflictingLaneMask.laneMask(1L << 1);
        assertThatThrownBy(() -> conflictingLaneMask.laneMask(1L << 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lane mask");

        RuntimeCommitPatch.Builder sealed = baseBuilder();
        sealed.recordUser(1, null, user(7, 1));
        sealed.laneMask(1L << 1);
        sealed.seal(metadata(1L << 1));
        assertThatThrownBy(() -> sealed.seal(metadata(1L << 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealed");
    }

    @Test
    void resetReusesBuilderWithoutMutatingOrLeakingThePreviousPatch() {
        RuntimeCommitPatch.Builder builder = baseBuilder();
        builder.recordUser(1, null, user(7, 1));
        builder.laneMask(1L << 1);
        RuntimeCommitPatch first = builder.seal(metadata(1L << 1));

        builder.reset().sequences(40, 41)
                .matcherTransition(new CoreMatcherTransition(1, 0, 6, 8, 90, 101));
        builder.recordUser(1, null, user(8, 1));
        builder.laneMask(1L << 1);
        RuntimeCommitPatch second = builder.seal(metadata(1L << 1));

        assertThat(first.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId).containsExactly(7L);
        assertThat(second.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId).containsExactly(8L);
        assertThat(first.laneMask()).isEqualTo(1L << 1);
        assertThat(second.laneMask()).isEqualTo(1L << 1);
    }

    @Test
    void primitiveChangesGrowAndResetWithoutLeakingPreviousKeys() {
        RuntimeCommitPatch.Builder ascending = baseBuilder();
        RuntimeCommitPatch.Builder descending = baseBuilder();
        for (long userId = 1; userId <= 64; userId++) {
            ascending.recordUser(1, null, user(userId, 1));
        }
        for (long userId = 64; userId >= 1; userId--) {
            descending.recordUser(1, null, user(userId, 1));
        }
        ascending.laneMask(1L << 1);
        descending.laneMask(1L << 1);

        RuntimeCommitPatch first = ascending.seal(metadata(1L << 1));
        RuntimeCommitPatch reversed = descending.seal(metadata(1L << 1));

        assertThat(first.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 64).boxed().toList());
        assertThat(reversed.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId)
                .containsExactlyElementsOf(java.util.stream.LongStream.iterate(64, value -> value - 1)
                        .limit(64).boxed().toList());

        ascending.reset().sequences(40, 41)
                .matcherTransition(new CoreMatcherTransition(1, 0, 6, 8, 90, 101));
        ascending.recordUser(1, null, user(100, 1));
        ascending.laneMask(1L << 1);
        RuntimeCommitPatch afterReset = ascending.seal(metadata(1L << 1));

        assertThat(afterReset.accountLaneGroups().getFirst().users())
                .extracting(RuntimeCommitPatch.UserChange::userId)
                .containsExactly(100L);
    }

    @Test
    void rejectsDuplicateCanonicalMetadataAndExactArithmeticOverflow() {
        RuntimeCommitPatch.Builder duplicateTerminal = baseBuilder();
        duplicateTerminal.terminalIds(List.of(4L, 4L), List.of(), List.of());
        duplicateTerminal.recordUser(1, null, user(7, 1));
        duplicateTerminal.laneMask(1L << 1);
        assertThatThrownBy(() -> duplicateTerminal.seal(metadata(1L << 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate terminal");

        RuntimeCommitPatch.Builder overflow = baseBuilder();
        overflow.recordUser(1, null, user(7, 1));
        overflow.addFundsPosting(new RuntimeCommitPatch.FundsPosting(
                1, FundsPosting.OwnerKind.USER, 7, FundsPosting.Subledger.AVAILABLE, Long.MAX_VALUE));
        overflow.addFundsPosting(new RuntimeCommitPatch.FundsPosting(
                1, FundsPosting.OwnerKind.USER, 7, FundsPosting.Subledger.AVAILABLE, 1));
        overflow.laneMask(1L << 1);
        assertThatThrownBy(() -> overflow.seal(metadata(1L << 1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsUnconservedFundsUnlessTheCommandIsAnExternalAdjustment() {
        RuntimeCommitPatch.FundsPosting unbalanced = new RuntimeCommitPatch.FundsPosting(
                1, FundsPosting.OwnerKind.USER, 7, FundsPosting.Subledger.AVAILABLE, 10);
        RuntimeCommitPatch.Builder rejected = baseBuilder();
        rejected.recordUser(1, null, user(7, 1));
        rejected.addFundsPosting(unbalanced);
        rejected.laneMask(1L << 1);
        assertThatThrownBy(() -> rejected.seal(metadata(1L << 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not conserved");

        RuntimeCommitPatch.Builder external = baseBuilder();
        external.recordUser(1, null, user(7, 1));
        external.addFundsPosting(unbalanced);
        external.laneMask(1L << 1);
        assertThat(external.seal(metadata(true)).fundsPostings()).containsExactly(unbalanced);
    }

    @Test
    void rejectsEmbeddedIdentityAndTriggerProductLineMismatch() {
        RuntimeCommitPatch.Builder userIdentity = baseBuilder();
        assertThatThrownBy(() -> userIdentity.recordUser(1, user(7, 1), user(8, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");

        RuntimeCommitPatch.Builder triggerLine = baseBuilder();
        assertThatThrownBy(() -> triggerLine.recordTriggerOrder(1, 17, null,
                trigger(17, ProductLine.SPOT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product line");
    }

    @Test
    void rejectsConflictingBeforeValueAndNegativeSealRevision() {
        RuntimeCommitPatch.Builder conflicting = baseBuilder();
        conflicting.recordUser(1, user(7, 1), user(7, 2));
        assertThatThrownBy(() -> conflicting.recordUser(1, user(7, 4), user(7, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting before-value");
        assertThatThrownBy(() -> new RuntimeCommitPatch.SealMetadata(
                -1, 0, 0, 0, 0, 0, 0, metadata().coreFactMetadata()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elidesNoOpChangesWithoutClaimingBusinessMutation() {
        RuntimeCommitPatch.Builder builder = baseBuilder();
        UserRuntime unchanged = user(7, 1);
        builder.recordUser(1, unchanged, unchanged);

        RuntimeCommitPatch patch = builder.seal(metadata(0, 8, 8));

        assertThat(patch.changesBusinessState()).isFalse();
        assertThat(patch.accountLaneGroups()).isEmpty();
    }

    @Test
    void sealedLaneMaskExactlyRepresentsSingleAllSparseAndNoOpOwnerGroups() {
        RuntimeCommitPatch.Builder single = baseBuilder();
        single.recordUser(2, null, user(2, 1));
        single.laneMask(1L << 2);
        assertThat(single.seal(metadata(1L << 2)).laneMask()).isEqualTo(1L << 2);

        RuntimeCommitPatch.Builder allFour = baseBuilder();
        for (int laneId = 0; laneId < 4; laneId++) {
            allFour.recordUser(laneId, null, user(laneId + 1L, 1));
        }
        allFour.laneMask(0b1111);
        assertThat(allFour.seal(metadata(0b1111)).laneMask()).isEqualTo(0b1111);

        RuntimeCommitPatch.Builder sparse = baseBuilder();
        sparse.recordUser(0, null, user(4, 1));
        sparse.recordUser(3, null, user(7, 1));
        sparse.laneMask((1L << 0) | (1L << 3));
        assertThat(sparse.seal(metadata((1L << 0) | (1L << 3))).laneMask())
                .isEqualTo((1L << 0) | (1L << 3));

        RuntimeCommitPatch.Builder noOp = baseBuilder();
        RuntimeCommitPatch empty = noOp.seal(metadata(0, 8, 8));
        assertThat(empty.laneMask()).isZero();
        assertThat(empty.accountLaneGroups()).isEmpty();
        assertThat(empty.changesBusinessState()).isFalse();
    }

    @Test
    void preservesCompactOrderStateForIndexAndOffOwnerFactAssembly() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        OrderRuntime order = new OrderRuntime(71, 7, symbolId, 2);
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                ProductLine.LINEAR_PERPETUAL, 0, 1)
                .matcherTransition(CoreMatcherTransition.unchanged(0, 0));
        builder.recordUser(1, null, user(7, 1));
        CoreOrderState businessOrder = RuntimeStateMaterializer.orderSnapshot(order, identities);
        builder.recordOrder(1, null, order, null, businessOrder);
        builder.laneMask(1L << 1);

        UUID factCommandId = UUID.randomUUID();
        var factMetadata = new RuntimeCommitPatch.CoreFactMetadata(factCommandId,
                fingerprint(factCommandId, 7, 1),
                com.surprising.aeron.protocol.CoreMessageType.PROBE_INCREMENT.wireCode(), 7,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, 1, 1, false);
        RuntimeCommitPatch.PrepareMetadata prepareMetadata = new RuntimeCommitPatch.PrepareMetadata(
                0, 1, 0, 0, 1L << 1, factMetadata, false);
        RuntimeCommitPatch.PreparedChanges prepared = builder.prepare(prepareMetadata, identities);
        RuntimeCommitPatch patch = builder.seal(prepared, 1, 0);
        RuntimeCommitPatch.OrderChange change = patch.accountLaneGroups().getFirst().orders().getFirst();
        TradingCoreState initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        ActiveOrderIndex index = new ActiveOrderIndex(initial, identities);
        index.apply(List.of(change), identities);
        RuntimeProjectionState projection = new RuntimeProjectionState(initial, 0, 0);
        projection.apply(patch);

        assertThat(index.orders().iterator().next()).isSameAs(change.businessAfter());
        assertThat(projection.freeze(1).orders().get(71L)).isSameAs(change.businessAfter());
        assertThat(patch.materializeCoreFactFragment().changedOrders())
                .singleElement().extracting(CoreOrderState::orderId)
                .isEqualTo(order.orderId());
    }

    @Test
    void materializesEachChangedUserFromOneGroupedTraversal() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int assetId = identities.assetId("USDT");
        int symbolId = identities.symbolId("BTC-USDT");
        long firstPositionKey = identities.positionKey(7, "BTC-USDT:LONG");
        long secondPositionKey = identities.positionKey(9, "BTC-USDT:SHORT");
        RuntimeCommitPatch.Builder builder = RuntimeCommitPatch.builder(
                ProductLine.LINEAR_PERPETUAL, 0, 1)
                .matcherTransition(CoreMatcherTransition.unchanged(0, 0));
        builder.recordUser(1, null, user(9, 1));
        builder.recordUser(1, null, user(7, 1));
        builder.recordBalance(1, 9, assetId, null, new RuntimeCommitPatch.UserBalance(900, 100, 0));
        builder.recordBalance(1, 7, assetId, null, new RuntimeCommitPatch.UserBalance(700, 300, 0));
        builder.recordReservation(1, 99, null,
                new ReservationRuntime(99, 9, symbolId, 1,
                        com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                        assetId, 100, 0, 0, 1), false, false);
        builder.recordReservation(1, 77, null,
                new ReservationRuntime(77, 7, symbolId, 1,
                        com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                        assetId, 300, 0, 0, 1), false, false);
        builder.recordPosition(1, secondPositionKey, null,
                new PositionRuntime(9, symbolId, assetId,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.SHORT,
                        1, -1, 100, 100, 0, 100));
        builder.recordPosition(1, firstPositionKey, null,
                new PositionRuntime(7, symbolId, assetId,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.LONG,
                        1, 1, 100, 100, 0, 300));
        builder.laneMask(1L << 1);

        UUID factCommandId = UUID.randomUUID();
        var factMetadata = new RuntimeCommitPatch.CoreFactMetadata(factCommandId,
                fingerprint(factCommandId, 7, 1),
                com.surprising.aeron.protocol.CoreMessageType.PROBE_INCREMENT.wireCode(), 7,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, 1, 1, true);
        RuntimeCommitPatch.PreparedChanges prepared = builder.prepare(new RuntimeCommitPatch.PrepareMetadata(
                0, 1, 0, 0, 1L << 1, factMetadata, true), identities);
        RuntimeCommitPatch.CoreFactFragment fragment = builder.seal(prepared, 1, 0)
                .materializeCoreFactFragment();

        assertThat(fragment.changedUsers()).extracting(user -> user.userId()).containsExactly(9L, 7L);
        assertThat(fragment.changedUsers().get(0).balances()).singleElement()
                .extracting(balance -> balance.availableUnits()).isEqualTo(900L);
        assertThat(fragment.changedUsers().get(0).reservations()).singleElement()
                .extracting(reservation -> reservation.orderId()).isEqualTo(99L);
        assertThat(fragment.changedUsers().get(0).positions()).singleElement()
                .extracting(position -> position.positionSide())
                .isEqualTo(com.surprising.aeron.protocol.CorePositionSide.SHORT);
        assertThat(fragment.changedUsers().get(1).balances()).singleElement()
                .extracting(balance -> balance.availableUnits()).isEqualTo(700L);
        assertThat(fragment.changedUsers().get(1).reservations()).singleElement()
                .extracting(reservation -> reservation.orderId()).isEqualTo(77L);
        assertThat(fragment.changedUsers().get(1).positions()).singleElement()
                .extracting(position -> position.positionSide())
                .isEqualTo(com.surprising.aeron.protocol.CorePositionSide.LONG);
    }

    @Test
    void assemblesCompactOrderDeletionAsTombstone() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        OrderRuntime order = new OrderRuntime(72, 7, symbolId, 2);
        RuntimeCommitPatch.Builder builder = baseBuilder();
        CoreOrderState businessOrder = RuntimeStateMaterializer.orderSnapshot(order, identities);
        builder.recordOrder(1, order, null, businessOrder, null);
        builder.laneMask(1L << 1);

        UUID factCommandId = UUID.randomUUID();
        var factMetadata = new RuntimeCommitPatch.CoreFactMetadata(factCommandId,
                fingerprint(factCommandId, 7, 41),
                com.surprising.aeron.protocol.CoreMessageType.PROBE_INCREMENT.wireCode(), 7,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 41, 401, 501, 601, false);
        RuntimeCommitPatch.PrepareMetadata prepareMetadata = new RuntimeCommitPatch.PrepareMetadata(
                3, 4, 5, 7, 1L << 1, factMetadata, false);
        RuntimeCommitPatch.PreparedChanges prepared = builder.prepare(prepareMetadata, identities);
        RuntimeCommitPatch patch = builder.seal(prepared, 9, 11);

        assertThat(patch.materializeCoreFactFragment().changedOrders()).isEmpty();
        assertThat(patch.materializeCoreFactFragment().tombstones().orderIds()).containsExactly(72L);
    }

    @Test
    void compactPatchRetainsCanonicalCommitMetadataAndTerminalIdsWithoutMaterializingPayload() {
        RuntimeCommitPatch patch = populatedBuilder(false).seal(metadata());

        assertThat(patch.coreFactMetadata()).isEqualTo(metadata().coreFactMetadata());
        assertThat(patch.matcherEvidence()).isNotEmpty();
        assertThat(patch.terminalIds().orderIds()).containsExactly(4L, 12L);
        assertThat(patch.terminalIds().liquidationIds()).containsExactly(6L);
        assertThat(patch.terminalIds().triggerOrderIds()).containsExactly(8L);
        assertThat(patch.previousCoreSequence()).isEqualTo(40);
        assertThat(patch.coreSequence()).isEqualTo(41);
        assertThat(patch.previousProjectionSequence()).isEqualTo(40);
        assertThat(patch.projectionSequence()).isEqualTo(41);
    }

    private static RuntimeCommitPatch.Builder populatedBuilder(boolean reverse) {
        RuntimeCommitPatch.Builder builder = baseBuilder();
        UserRuntime user2 = user(2, 1);
        UserRuntime user9 = user(9, 1);
        RuntimeCommitPatch.UserBalance before = new RuntimeCommitPatch.UserBalance(100, 20, 5);
        RuntimeCommitPatch.UserBalance after = new RuntimeCommitPatch.UserBalance(90, 30, 7);
        if (reverse) {
            builder.recordUser(1, null, user9);
            builder.recordBalance(1, 9, 2, before, after);
            builder.recordUser(1, null, user2);
            builder.recordUser(3, null, user(30, 1));
        } else {
            builder.recordUser(3, null, user(30, 1));
            builder.recordUser(1, null, user2);
            builder.recordBalance(1, 9, 2, before, after);
            builder.recordUser(1, null, user9);
        }
        List<RuntimeCommitPatch.MatcherEvidence> evidence = List.of(
                new RuntimeCommitPatch.MatcherEvidence(8, 2, 19, 29, 3, 101),
                new RuntimeCommitPatch.MatcherEvidence(7, 1, 17, 27, 2, 99));
        (reverse ? evidence.reversed() : evidence).forEach(builder::addMatcherEvidence);
        builder.terminalIds(reverse ? List.of(12L, 4L) : List.of(4L, 12L), List.of(6L), List.of(8L));
        builder.laneMask((1L << 1) | (1L << 3));
        return builder;
    }

    private static RuntimeCommitPatch.Builder baseBuilder() {
        return RuntimeCommitPatch.builder(ProductLine.LINEAR_PERPETUAL, 40, 41)
                .matcherTransition(new CoreMatcherTransition(1, 0, 6, 8, 90, 101));
    }

    private static RuntimeCommitPatch.SealMetadata metadata() {
        return metadata((1L << 1) | (1L << 3));
    }

    private static RuntimeCommitPatch.SealMetadata metadata(boolean externalAdjustment) {
        return metadata((1L << 1), 8, 9, externalAdjustment);
    }

    private static RuntimeCommitPatch.SealMetadata metadata(long laneMask) {
        return metadata(laneMask, 8, 9);
    }

    private static RuntimeCommitPatch.SealMetadata metadata(long laneMask, long beforeRevision, long afterRevision) {
        return metadata(laneMask, beforeRevision, afterRevision, false);
    }

    private static RuntimeCommitPatch.SealMetadata metadata(
            long laneMask, long beforeRevision, long afterRevision, boolean externalAdjustment) {
        return new RuntimeCommitPatch.SealMetadata(beforeRevision, afterRevision, 111, 113, 211, 223,
                laneMask, new RuntimeCommitPatch.CoreFactMetadata(
                UUID.fromString("00000000-0000-0000-0000-000000000041"),
                fingerprint(UUID.fromString("00000000-0000-0000-0000-000000000041"), 9, 41),
                com.surprising.aeron.protocol.CoreMessageType.PROBE_INCREMENT.wireCode(), 9,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 41, 401, 501, 601, externalAdjustment));
    }

    private static UserRuntime user(long userId, long revision) {
        return new UserRuntime(ProductLine.LINEAR_PERPETUAL, userId, revision, CorePositionMode.ONE_WAY);
    }

    private static CommandFingerprint fingerprint(UUID commandId, long userId, long sourceSequence) {
        var header = com.surprising.aeron.protocol.CoreMessageHeader.command(
                com.surprising.aeron.protocol.CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.LINEAR_PERPETUAL, com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                userId, sourceSequence, userId, 1, sourceSequence);
        return CommandFingerprint.of(new com.surprising.aeron.protocol.CoreMessage(header,
                com.surprising.aeron.protocol.CoreProtocol.probePayload(1)));
    }

    private static CoreTriggerOrderState trigger(long id, ProductLine productLine) {
        return new CoreTriggerOrderState(id, productLine, 7, "", "", "BTC-USDT",
                com.surprising.aeron.protocol.CoreOrderSide.BUY,
                com.surprising.aeron.protocol.CoreTriggerOrderType.STOP_LOSS,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL,
                100, 0, 0, 0, 0, 0,
                com.surprising.aeron.protocol.CoreOrderType.MARKET,
                com.surprising.aeron.protocol.CoreTimeInForce.IOC, 0, 1,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "", 0, 0, 1, 1, 1);
    }
}
