package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class UserRevisionReuseTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void ownedValueAndPublishedReceiptStayIndependentFromLaneAdvances(ProductLine product) {
        var original = new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY);
        var authority = original.laneValue();
        var published = authority.publicationValue();
        var receipt = authority.copyIntoCommandSlot(null);
        assertThat(authority.withState(4, CorePositionMode.HEDGE)).isSameAs(authority);
        assertThat(authority.laneValue()).isSameAs(authority);
        assertThat(original).isEqualTo(new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY));
        assertThat(published).isEqualTo(original);
        assertThat(receipt).isEqualTo(original);
        assertThat(authority).isEqualTo(new UserRuntime(product, 7, 4, CorePositionMode.HEDGE));
        var advancedOwned = original.withState(5, CorePositionMode.HEDGE);
        assertThat(advancedOwned).isNotSameAs(original);
        assertThat(original.revision()).isEqualTo(3);
        assertThatThrownBy(() -> receipt.withState(6, CorePositionMode.HEDGE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void commandBuffersReuseCellsWithoutAliasingOtherInFlightCommands() {
        var authority = new UserRuntime(7).laneValue();
        var first = new RuntimeUserChangeBuffer();
        var second = new RuntimeUserChangeBuffer();
        first.put(7, authority);
        var firstCell = first.get(7);
        authority.withState(1, CorePositionMode.HEDGE);
        second.put(7, authority);
        var secondCell = second.get(7);
        assertThat(firstCell.revision()).isZero();
        assertThat(secondCell.revision()).isOne();
        var stable = firstCell.publicationValue();
        first.clear();
        first.put(7, authority);
        assertThat(first.get(7)).isSameAs(firstCell);
        assertThat(stable.revision()).isZero();
        assertThat(second.get(7)).isSameAs(secondCell);
        for (int round = 2; round < 1000; round++) {
            authority.withState(round, CorePositionMode.ONE_WAY);
            first.put(7, authority);
            assertThat(first.get(7)).isSameAs(firstCell);
            assertThat(first.get(7).revision()).isEqualTo(round);
            assertThat(second.get(7).revision()).isOne();
            first.clear();
        }
        first.put(9, new UserRuntime(9));
        assertThat(first.get(9)).isSameAs(firstCell);
        assertThat(first.get(9).userId()).isEqualTo(9);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void coalescedSettlementKeepsAuthorityAndEarlierCommandReceipt(ProductLine product) {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var authority = new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY).laneValue();
        lane.users.put(7, authority);
        var earlier = new RuntimeUserChangeBuffer();
        earlier.put(7, authority);
        var changes = new MatcherSettlementChanges(1);
        changes.ensureActiveLanes(1);
        changes.addUserRevision(0, 7, 2);
        changes.addUserRevision(0, 7, 3);
        changes.applyUserRevisions(0, lane);
        assertThat(lane.users.get(7)).isSameAs(authority);
        assertThat(authority.revision()).isEqualTo(8);
        assertThat(changes.laneDeltas[0].users.get(7).revision()).isEqualTo(8);
        assertThat(earlier.get(7).revision()).isEqualTo(3);
        assertThat(changes.userRevisionDeltas[0].isEmpty()).isTrue();
        authority.withState(9, CorePositionMode.HEDGE);
        assertThat(changes.laneDeltas[0].users.get(7).revision()).isEqualTo(8);
    }

    @Test void grownBeforeBufferRetainsFirstValueAndAuthorityAcrossUpdatesAndRemoval() {
        var before = new RuntimeUserChangeBuffer();
        var users = new UserRuntime[40];
        for (int index = 0; index < users.length; index++) {
            users[index] = new UserRuntime(index + 1).laneValue();
            before.captureBefore(index + 1, users[index]);
            users[index].withState(9, CorePositionMode.HEDGE);
            before.captureBefore(index + 1, users[index]);
            assertThat(before.get(index + 1).revision()).isZero();
            assertThat(before.preparedAt(index)).isSameAs(users[index]);
        }
        before.captureBefore(99, null);
        assertThat(before.containsKey(99)).isTrue();
        assertThat(before.get(99)).isNull();
        assertThat(before.preparedAt(40)).isNull();
        before.clear();
        assertThat(before.isEmpty()).isTrue();
    }

    @Test void failedRevisionTraversalReleasesItsReceiptBeforeTheNextCommand() {
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        var overflowing = new UserRuntime(ProductLine.LINEAR_PERPETUAL, 7,
                Long.MAX_VALUE, CorePositionMode.ONE_WAY).laneValue();
        lane.users.put(7, overflowing);
        var failed = new MatcherSettlementChanges(1);
        failed.ensureActiveLanes(1); failed.addUserRevision(0, 7, 1);
        assertThatThrownBy(() -> failed.applyUserRevisions(0, lane)).isInstanceOf(ArithmeticException.class);
        assertThat(lane.users.get(7)).isSameAs(overflowing);
        assertThat(overflowing.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(failed.laneDeltas[0].users.isEmpty()).isTrue();
        assertThat(failed.userRevisionDeltas[0].get(7)).isEqualTo(1);

        lane.users.put(8, new UserRuntime(8).laneValue());
        var next = new MatcherSettlementChanges(1);
        next.ensureActiveLanes(1); next.addUserRevision(0, 8, 3);
        next.applyUserRevisions(0, lane);
        assertThat(lane.users.get(8).revision()).isEqualTo(3);
        assertThat(next.laneDeltas[0].users.get(8).revision()).isEqualTo(3);
        assertThat(next.userRevisionDeltas[0].isEmpty()).isTrue();
        assertThat(failed.laneDeltas[0].users.isEmpty()).isTrue();
    }

    @Test void rejectedUserArithmeticAndModeLeaveTheAuthorityUnchanged() {
        var user = new UserRuntime(ProductLine.LINEAR_PERPETUAL, 7, Long.MAX_VALUE, CorePositionMode.HEDGE).laneValue();
        assertThatThrownBy(() -> user.withState(Math.incrementExact(user.revision()), CorePositionMode.ONE_WAY))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> user.withState(-1, CorePositionMode.ONE_WAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.withState(2, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(user.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(user.positionMode()).isEqualTo(CorePositionMode.HEDGE);
    }

    @Test void delayedMetadataOutputKeepsItsCommandRevisionAfterAuthorityAndCellReuse() {
        try (var runtime = new TradingRuntimeState()) {
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(32, 65_536);
            var capture = new com.surprising.aeron.service.state.realtime.RealtimeStateCapture(
                    outbox, ProductLine.LINEAR_PERPETUAL, new RuntimeIdentityRegistry());
            runtime.realtimeCapture(capture);
            capture.begin(1, 1, 0, 0);
            var authority = new UserRuntime(7).laneValue();
            var changes = new LaneCommitDelta();
            authority.withState(3, CorePositionMode.HEDGE);
            changes.putUser(7, authority);
            authority.withState(4, CorePositionMode.ONE_WAY);
            changes.recordControlChanges(runtime, 0);
            changes.clear();
            changes.putUser(7, authority);
            authority.withState(5, CorePositionMode.HEDGE);
            capture.commit(1);
            int metadata = 0;
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                var frame = com.surprising.aeron.protocol.RealtimeFrameCodec.decode(bytes);
                if (frame.kind() != com.surprising.aeron.protocol.RealtimeFrame.Kind.METADATA) continue;
                var user = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(frame.payload());
                assertThat(user.userId()).isEqualTo(7);
                assertThat(user.revision()).isEqualTo(3);
                assertThat(user.positionMode()).isEqualTo(CorePositionMode.HEDGE);
                metadata++;
            }
            assertThat(metadata).isOne();
            assertThat(capture.failures()).isZero();
            assertThat(outbox.droppedBatches()).isZero();
        }
    }

    @Test void actualLaneRollbackRestoresRevisionModeAndAuthorityIdentity() {
        try (var runtime = new TradingRuntimeState()) {
            runtime.putUser(new UserRuntime(7));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            int laneId = runtime.topology().accountLaneId(7);
            runtime.dispatchControlLanes(1L << laneId, ignored -> {
                var lane = runtime.currentLaneCommandScope();
                runtime.setUserState(7, 3, CorePositionMode.ONE_WAY);
                runtime.accountRollback.clearNonBalanceImages();
                var authority = lane.users.get(7);
                runtime.setUserState(7, 4, CorePositionMode.HEDGE);
                runtime.setUserState(7, 5, CorePositionMode.HEDGE);
                assertThat(lane.users.get(7)).isSameAs(authority);
                runtime.accountRollback.restoreLane(lane);
                assertThat(lane.users.get(7)).isSameAs(authority);
                assertThat(authority.revision()).isEqualTo(3);
                assertThat(authority.positionMode()).isEqualTo(CorePositionMode.ONE_WAY);
                runtime.accountRollback.clearNonBalanceImages();
                var switchMode = new com.surprising.aeron.protocol.UpdatePositionModeCommand(CorePositionMode.HEDGE);
                assertThat(DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, 7, switchMode)).isTrue();
                assertThat(DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, 7, switchMode)).isFalse();
                assertThat(authority.revision()).isEqualTo(4);
                runtime.accountRollback.restoreLane(lane);
                assertThat(authority.revision()).isEqualTo(3);
                assertThat(authority.positionMode()).isEqualTo(CorePositionMode.ONE_WAY);
                runtime.accountRollback.clearNonBalanceImages();
                long absentUser = 8;
                while (runtime.topology().accountLaneId(absentUser) != laneId) absentUser++;
                runtime.setUserState(absentUser, 1, CorePositionMode.HEDGE);
                runtime.accountRollback.restoreLane(lane);
                assertThat(lane.users.get(absentUser)).isNull();
                return null;
            });
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() > deadline) throw new AssertionError("user rollback command timed out");
                Thread.onSpinWait();
            }
        }
    }
}
