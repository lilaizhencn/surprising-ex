package com.surprising.aeron.service.state;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LaneClientIdentitiesTest {
    @Test void referencesProtectAnAliasUntilBothOrdersReleaseItAndRowsRecycle() throws Exception {
        var table = new LaneClientIdentities(null);
        assertThat(table.prepare(11, 7, "客户😀", true)).isEqualTo(-11);
        assertThat(table.prepare(11, 7, "客户😀", true)).isEqualTo(11);
        table.rollback(11, 7, "客户😀");
        assertThat(table.name(11, 7)).isEqualTo("客户😀");
        assertThat(table.publishedCount()).isOne();
        table.release(11, 8);
        assertThat(table.publishedCount()).isOne();
        table.release(11, 7);
        assertThat(table.publishedCount()).isZero();
        assertThat((String[]) field(table, "names")).allMatch(v -> v == null);
        var rows = field(table, "users");
        for (long key = 12; key < 10_000; key++) {
            assertThat(table.prepare(key, 8, "other-" + key, true)).isEqualTo(-key);
            assertThat(table.find(11, 7, "客户😀")).isZero();
            table.release(key, 8);
        }
        assertThat(field(table, "users")).isSameAs(rows);
        assertThat(field(table, "nextRow")).isEqualTo(1);
        assertThat(table.publishedCount()).isZero();
    }

    @Test void collisionOverflowAndWrongRollbackDoNotChangeTheExistingIdentity() throws Exception {
        var table = new LaneClientIdentities(null);
        table.prepare(17, 7, "original", true);
        assertThatThrownBy(() -> table.prepare(17, 8, "original", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table.prepare(17, 7, "different", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table.rollback(17, 7, "different")).isInstanceOf(IllegalStateException.class);
        assertThat(table.name(17, 7)).isEqualTo("original");
        assertThat(table.find(17, 8, "original")).isZero();
        ((long[]) field(table, "references"))[0] = Long.MAX_VALUE;
        assertThatThrownBy(() -> table.prepare(17, 7, "original", true)).isInstanceOf(ArithmeticException.class);
        assertThat(((long[]) field(table, "references"))[0]).isEqualTo(Long.MAX_VALUE);
        assertThat(table.publishedCount()).isOne();
    }

    @Test void growthAndBackShiftDeletionKeepEveryLiveIdentityAcrossWraps() throws Exception {
        var table = new LaneClientIdentities(null);
        int live = 1024;
        for (long key = 1; key <= live; key++) table.prepare(key, 7, "id-" + key, true);
        var names = field(table, "names");
        for (long key = 1; key <= 10_000; key++) {
            assertThat(table.name(key, 7)).isEqualTo("id-" + key);
            table.release(key, 7);
            long next = key + live;
            table.prepare(next, 7, "id-" + next, true);
        }
        assertThat(field(table, "names")).isSameAs(names);
        assertThat(table.publishedCount()).isEqualTo(live);
        for (long key = 10_001; key <= 10_000 + live; key++)
            assertThat(table.name(key, 7)).isEqualTo("id-" + key);
    }

    @Test void initialBindingAndColdRebindTransferAuthorityWithExactReferences() {
        var identities = new RuntimeIdentityRegistry();
        long first = identities.prepareClientKey(7, "first");
        identities.prepareClientKey(7, "first");
        long second = identities.clientKey(8, "second");
        long version = identities.dictionaryVersion();
        try (var oldRuntime = new TradingRuntimeState(); var runtime = new TradingRuntimeState()) {
            identities.bindClientLanes(oldRuntime);
            var oldTable = oldRuntime.accountLanes[oldRuntime.topology().accountLaneId(7)].clientIdentities;
            assertThat(identities.clientIdentityCount()).isEqualTo(2);
            identities.bindClientLanes(runtime);
            assertThat(oldTable.publishedCount()).isZero();
            assertThat(identities.dictionaryVersion()).isEqualTo(version);
            identities.releaseClientKey(7, first);
            assertThat(identities.clientOrderId(7, first)).isEqualTo("first");
            identities.releaseClientKey(7, first);
            assertThat(identities.findClientKeyValue(7, "first")).isZero();
            assertThat(identities.clientOrderId(8, second)).isEqualTo("second");
            assertThat(identities.clientIdentityCount()).isOne();
        }
    }

    @Test void activeOwnerCannotReadOrMutateIdentityRowsAndDelayedReadersKeepOwnedNames() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = new TradingRuntimeState()) {
            identities.bindClientLanes(runtime);
            long key = identities.prepareClientKey(7, "retained-name");
            runtime.startAccountLanes();
            assertThatThrownBy(() -> identities.findClientKeyValue(7, "retained-name"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Account Lane");
            assertThatThrownBy(() -> identities.clientOrderId(7, key)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> identities.prepareClientKey(7, "foreign-write")).isInstanceOf(IllegalStateException.class);
            String name = AccountLaneQueryTestSupport.read(runtime, 7, () -> identities.clientOrderId(7, key));
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), ignored -> {
                var lane = runtime.currentLaneCommandScope();
                identities.releaseClientKeyInLane(lane, 7, key);
                identities.prepareClientKeyInLane(lane, 7, "next-name");
                return null;
            });
            await(runtime);
            assertThat(name).isEqualTo("retained-name");
            assertThat(identities.clientIdentityCount()).isOne();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> identities.findClientKeyValue(7, "retained-name"))).isZero();
            try (var otherRuntime = new TradingRuntimeState()) {
                assertThatThrownBy(() -> identities.bindClientLanes(otherRuntime)).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test void equalNumericKeysOnDifferentLanesRemainIndependentAndForeignLaneObjectsAreRejected() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = new TradingRuntimeState()) {
            identities.bindClientLanes(runtime);
            long firstUser = 7, secondUser = 8;
            while (runtime.topology().accountLaneId(secondUser) == runtime.topology().accountLaneId(firstUser)) secondUser++;
            long otherUser = secondUser;
            var firstLane = runtime.accountLanes[runtime.topology().accountLaneId(firstUser)];
            var secondLane = runtime.accountLanes[runtime.topology().accountLaneId(secondUser)];
            // Force the otherwise rare hash coincidence without changing the production hash function.
            firstLane.clientIdentities.importIdentity(19, firstUser, "first-name");
            secondLane.clientIdentities.importIdentity(19, secondUser, "second-name");
            assertThat(identities.clientOrderId(firstUser, 19)).isEqualTo("first-name");
            assertThat(identities.clientOrderId(otherUser, 19)).isEqualTo("second-name");
            identities.releaseClientKey(firstUser, 19);
            assertThat(identities.clientOrderId(otherUser, 19)).isEqualTo("second-name");
            assertThat(identities.clientIdentityCount()).isOne();
            assertThatThrownBy(() -> identities.prepareClientKeyInLane(
                    new AccountLaneState(secondLane.laneId(), 16), otherUser, "foreign"))
                    .isInstanceOf(IllegalStateException.class);
            runtime.startAccountLanes();
            var entered = new java.util.concurrent.CountDownLatch(2);
            runtime.dispatchControlLanes((1L << firstLane.laneId()) | (1L << secondLane.laneId()), laneId -> {
                var lane = runtime.currentLaneCommandScope();
                boolean first = laneId == firstLane.laneId();
                long uid = first ? firstUser : otherUser;
                String name = first ? "first-name" : "second-name";
                lane.clientIdentities.prepare(19, uid, name, true);
                entered.countDown();
                try {
                    if (!entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("the other Lane did not progress independently");
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
                assertThat(identities.clientOrderId(uid, 19)).isEqualTo(name);
                identities.releaseClientKeyInLane(lane, uid, 19);
                return null;
            });
            await(runtime);
            assertThat(identities.clientIdentityCount()).isOne();
            assertThat(AccountLaneQueryTestSupport.read(runtime, otherUser,
                    () -> identities.clientOrderId(otherUser, 19))).isEqualTo("second-name");
        }
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }

    private static void await(TradingRuntimeState runtime) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!runtime.pollControlLanes()) {
            if (System.nanoTime() > deadline) throw new AssertionError("client identity command timed out");
            Thread.onSpinWait();
        }
    }
}
