package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.UserRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LanePublishedMapTest {
    @Test void terminalPublicationCapturesDeletedPositionOnceBeforeReplacingOwnerView() {
        for (boolean prepared : new boolean[]{false, true}) {
            try (var runtime = new TradingRuntimeState()) {
                var identities = new RuntimeIdentityRegistry();
                int symbol = identities.symbolId("BTC-USDT");
                int asset = identities.assetId("USDT");
                var outbox = new com.surprising.aeron.client.RealtimeOutbox(32, 65536);
                var capture = new com.surprising.aeron.service.state.realtime.RealtimeStateCapture(
                        outbox, com.surprising.product.api.ProductLine.LINEAR_PERPETUAL, identities);
                runtime.realtimeCapture(capture);
                var position = new PositionRuntime(7, symbol, asset,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET, 1, 2, 100, 200, 17, 20);
                runtime.publishedPositions.put(1, position.snapshot());
                var delta = new TradingRuntimeState.LaneDelta();
                delta.positions.put(1, null);
                if (prepared) delta.preparePublication(runtime);
                capture.begin(1, 100, 0);
                delta.commitTerminalToOwner(runtime, 0, null, 1);
                capture.commit();
                assertThat(runtime.publishedPositions.get(1)).isNull();
                int removals = 0;
                byte[] bytes;
                while ((bytes = outbox.poll()) != null) {
                    var frame = com.surprising.aeron.protocol.RealtimeFrameCodec.decode(bytes);
                    if (frame.kind() != com.surprising.aeron.protocol.RealtimeFrame.Kind.POSITION) continue;
                    removals++;
                    var closed = com.surprising.aeron.protocol.CoreStateQueryCodec
                            .decodeUserState(frame.payload()).positions().getFirst();
                    assertThat(closed.signedQuantitySteps()).isZero();
                    assertThat(closed.realizedPnlUnits()).isEqualTo(17);
                }
                assertThat(removals).isOne();
                assertThat(capture.failures()).isZero();
            }
        }
    }

    @Test void sampledOrderOperationsPreserveIdentityAndCountOnlyExecutedWork() {
        var map = new LanePublishedMap<String>();
        var event = new OwnerSettlementMergeEvent();
        event.mapTiming = true;
        String value = new String("resting");
        map.applyPublished(1, value, event);
        map.applyPublished(1, value, event);
        map.applyPublished(1, new String("resting"), event);
        assertThat(map.get(1)).isSameAs(value);
        map.applyPublished(1, "changed", event);
        assertThat(map.get(1)).isEqualTo("changed");
        map.removePublished(1, event);
        map.removePublished(1, event);
        assertThat(event.orderGets).isEqualTo(4);
        assertThat(event.orderEquals).isEqualTo(3);
        assertThat(event.orderEqualHits).isEqualTo(2);
        assertThat(event.orderSameReference).isEqualTo(1);
        assertThat(event.orderPuts).isEqualTo(2);
        assertThat(event.removals).isEqualTo(2);
        assertThat(event.removalMisses).isEqualTo(1);
        assertThat(event.orderGetNanos + event.orderEqualsNanos + event.orderPutNanos + event.removalNanos).isPositive();
    }

    @Test void boundedShapeInspectionMatchesWrappedDeletionAndDoesNotMutateTheTable() throws Exception {
        var map = new LanePublishedMap<String>();
        var values = recordRemovals(map);
        int mask = values.capacity() - 1;
        long[] collisions = new long[4];
        int count = 0;
        for (long key = 1; count < collisions.length; key++)
            if (org.agrona.collections.Hashing.hash(key, mask) == mask) collisions[count++] = key;
        for (int i = 0; i < 3; i++) map.put(collisions[i], "value" + i);
        var event = new OwnerSettlementMergeEvent();
        event.mapShape = true;
        map.removePublished(collisions[0], event);
        assertThat(event.shapeSearchSlots).isEqualTo(1);
        assertThat(event.shapeScanSlots).isEqualTo(2);
        assertThat(event.shapeMoves).isEqualTo(2);
        assertThat(map.size()).isEqualTo(2);
        assertThat(map.get(collisions[1])).isEqualTo("value1");
        assertThat(map.get(collisions[2])).isEqualTo("value2");
        map.removePublished(collisions[3], event);
        assertThat(event.shapeSearchSlots).isEqualTo(4);
        assertThat(event.shapeMisses).isEqualTo(1);
        assertThat(event.shapeCensored).isZero();
        assertThat(event.shapeMaxSearch).isEqualTo(3);
        assertThat(event.shapeMaxScan).isEqualTo(2);
        assertThat(event.shapeRemovals).isEqualTo(2);
        assertThat(values.removals).containsEntry(collisions[0], 1).containsEntry(collisions[3], 1);
    }

    @Test void shapeInspectionCapsPathologicalChainsWithoutChangingDeletion() throws Exception {
        var map = new LanePublishedMap<String>();
        var values = recordRemovals(map);
        for (long key = 1; key <= 512; key++) map.put(key, "warm capacity");
        map.clear();
        int mask = values.capacity() - 1;
        long[] collisions = new long[130];
        int count = 0;
        for (long key = 1; count < collisions.length; key++)
            if (org.agrona.collections.Hashing.hash(key, mask) == mask) collisions[count++] = key;
        for (long key : collisions) map.put(key, "retained");
        var event = new OwnerSettlementMergeEvent();
        event.mapShape = true;
        map.removePublished(collisions[0], event);
        assertThat(event.shapeCensored).isEqualTo(1);
        assertThat(event.shapeScanSlots).isEqualTo(128);
        assertThat(map.size()).isEqualTo(129);
        for (int i = 1; i < collisions.length; i++) assertThat(map.get(collisions[i])).isEqualTo("retained");
    }

    @Test void terminalRoutesDeleteEachPublishedKeyOnceIncludingMissingAfterImages() throws Exception {
        for (boolean collectChangedIds : new boolean[]{false, true}) {
            try (var runtime = new TradingRuntimeState()) {
                var orderRemovals = recordRemovals(runtime.publishedOrders);
                var reservationRemovals = recordRemovals(runtime.publishedReservations);
                for (long id = 1; id <= 5; id++) {
                    runtime.publishedOrders.put(id, new OrderRuntime(id, 7, 0, 1).snapshot());
                    runtime.publishedReservations.put(id, new ReservationRuntime(id, 7, 0, 1).snapshot());
                }
                var resting = runtime.publishedOrders.get(4);
                var delta = new TradingRuntimeState.LaneDelta();
                // 1: deleted with an after-image; 2: explicit null plus a route deletion;
                // 3: route only; 4: unchanged live value; 5: null only; 6: already absent.
                delta.orders.put(1, new OrderRuntime(1, 7, 0, 1, true).snapshot());
                delta.reservations.put(1, new ReservationRuntime(1, 7, 0, 1).snapshot());
                for (long id : new long[]{2, 5, 6}) {
                    delta.orders.put(id, null);
                    delta.reservations.put(id, null);
                }
                delta.orders.put(4, resting.snapshot());
                delta.reservations.put(4, runtime.publishedReservations.get(4).snapshot());
                for (long id : new long[]{1, 2, 3, 6}) {
                    delta.removeOrderRoute(id);
                    delta.removeReservationRoute(id);
                }
                var expectedChanged = new java.util.LinkedHashSet<Long>();
                delta.orders.forEach((id, value) -> expectedChanged.add(id));
                delta.reservations.forEach((id, value) -> expectedChanged.add(id));
                delta.removedOrderRoutes.forEach(expectedChanged::add);
                var changedOrders = collectChangedIds
                        ? new com.surprising.aeron.service.command.support.PrimitiveLongChangeSet() : null;
                var changedUsers = collectChangedIds
                        ? new com.surprising.aeron.service.command.support.PrimitiveLongChangeSet() : null;
                delta.preparePublication(runtime);
                var publication = delta.publication;
                publication.publish(changedUsers, changedOrders);
                for (long id : new long[]{1, 2, 3, 5, 6}) {
                    assertThat(runtime.publishedOrders.get(id)).isNull();
                    assertThat(runtime.publishedReservations.get(id)).isNull();
                    assertThat(orderRemovals.removals).containsEntry(id, 1);
                    assertThat(reservationRemovals.removals).containsEntry(id, 1);
                }
                assertThat(orderRemovals.removals).hasSize(5);
                assertThat(reservationRemovals.removals).hasSize(5);
                assertThat(runtime.publishedOrders.get(4)).isSameAs(resting);
                assertThat(runtime.publishedReservations.get(4)).isNotNull();
                if (collectChangedIds) {
                    assertThat(changedOrders).containsExactlyElementsOf(expectedChanged);
                    assertThat(changedUsers).containsExactly(7L);
                }
                assertThat(delta.removedOrderRoutes.isEmpty()).isTrue();
                assertThat(delta.removedReservationRoutes.isEmpty()).isTrue();
                publication.publish(changedUsers, changedOrders);
                assertThat(orderRemovals.removals.values()).containsOnly(1);
                assertThat(reservationRemovals.removals.values()).containsOnly(1);
            }
        }
    }

    /** Test-only operation counts: no counters or injection hooks in the production map. */
    private static <V> RemovalCounts<V> recordRemovals(LanePublishedMap<V> map) throws Exception {
        var counts = new RemovalCounts<V>();
        var field = LanePublishedMap.class.getDeclaredField("values");
        field.setAccessible(true);
        field.set(map, counts);
        return counts;
    }

    private static final class RemovalCounts<V> extends org.agrona.collections.Long2ObjectHashMap<V> {
        final java.util.Map<Long, Integer> removals = new java.util.HashMap<>();

        @Override public V remove(long key) {
            removals.merge(key, 1, Integer::sum);
            return super.remove(key);
        }
    }

    @Test void recycledBatchAdmissionDiscardsUnpublishedValuesAndRetainsCapacity() throws Exception {
        var event = new PlaceBatchAdmissionEvent();
        var publication = new LanePublication();
        var map = new LanePublishedMap<String>();
        for (int i = 0; i < 41; i++) map.stage(publication, i, "discarded");
        var field = PlaceBatchAdmissionEvent.class.getDeclaredField("publication");
        field.setAccessible(true);
        field.set(event, publication);
        var completed = PlaceBatchAdmissionEvent.class.getDeclaredField("completed");
        completed.setAccessible(true);
        completed.setBoolean(event, true);
        var values = LanePublication.class.getDeclaredField("values");
        values.setAccessible(true);
        Object[] capacity = (Object[]) values.get(publication);
        event.clear();
        assertThat(event.publication()).isSameAs(publication);
        assertThat(values.get(publication)).isSameAs(capacity);
        assertThat(capacity).containsOnlyNulls();
        publication.publish();
        assertThat(map.size()).isZero();
        map.stage(publication, 99, "next");
        publication.publish();
        assertThat(map.size()).isOne();
        assertThat(map.get(99)).isEqualTo("next");
        event.clear();
        assertThat(map.get(99)).isEqualTo("next");
    }

    @Test void settlementReusesPublicationAndDiscardsUnpublishedReferences() {
        var runtime = new TradingRuntimeState();
        var delta = new TradingRuntimeState.LaneDelta();
        runtime.publishedOrders.put(99, new OrderRuntime(99, 7, 0, 1));
        runtime.publishedReservations.put(99, new ReservationRuntime(99, 7, 0, 1));
        delta.removeOrderRoute(99);
        delta.removeReservationRoute(99);
        delta.users.put(7, new UserRuntime(7));
        delta.preparePublication(runtime);
        var buffer = delta.publication;
        // Failure before publication: recycling must not publish user 7 on the next command.
        delta.clear();
        delta.users.put(8, new UserRuntime(8));
        delta.preparePublication(runtime);
        org.assertj.core.api.Assertions.assertThat(delta.publication).isSameAs(buffer);
        delta.publication.publish();
        assertThat(delta.users.isEmpty()).isTrue();
        assertThat(delta.reservations.isEmpty()).isTrue();
        org.assertj.core.api.Assertions.assertThat(runtime.publishedUsers.get(7)).isNull();
        org.assertj.core.api.Assertions.assertThat(runtime.publishedUsers.get(8)).isNotNull();
        assertThat(runtime.publishedOrders.get(99)).isNotNull();
        assertThat(runtime.publishedReservations.get(99)).isNotNull();
        delta.clear();
        for (int i = 10; i < 50; i++) delta.users.put(i, new UserRuntime(i));
        delta.preparePublication(runtime);
        delta.publication.publish();
        delta.clear();
        delta.preparePublication(runtime);
        org.assertj.core.api.Assertions.assertThat(delta.publication).isSameAs(buffer);
        delta.publication.publish();
        org.assertj.core.api.Assertions.assertThat(runtime.publishedUsers.get(7)).isNull();
    }

    @Test void publicationReplacesAnExistingValueAtTheOwnerBoundary() {
        var map = new LanePublishedMap<String>();
        map.put(7, "before");
        var receipt = new LanePublication();
        map.stage(receipt, 7, "after");
        assertThat(map.get(7)).isEqualTo("before");
        receipt.publish();
        assertThat(map.get(7)).isEqualTo("after");
    }
    @Test void reusedLookupNeverMutatesStoredKeysIncludingHashCollisions() {
        var map = new LanePublishedMap<String>();
        long first = 1, collision = 1L << 32;
        assertThat(Long.hashCode(first)).isEqualTo(Long.hashCode(collision));
        map.put(first, "first");
        map.put(collision, "collision");
        for (int i = 0; i < 1000; i++) {
            assertThat(map.get(first)).isEqualTo("first");
            assertThat(map.get(collision)).isEqualTo("collision");
            assertThat(map.get(3)).isNull();
        }
        assertThat(map.remove(first)).isEqualTo("first");
        assertThat(map.get(collision)).isEqualTo("collision");
    }

    @Test void unpublishedSuccessorsCannotLeakAndReclaimCannotEraseThem() throws Exception {
        var map = new LanePublishedMap<String>();
        map.put(7, "committed");
        var first = new LanePublication();
        var second = new LanePublication();
        map.stage(first, 7, "first");
        map.stage(second, 7, "second");
        assertThat(map.get(7)).isEqualTo("committed");
        first.publish();
        assertThat(map.get(7)).isEqualTo("first");
        second.publish();
        assertThat(map.get(7)).isEqualTo("second");
        var deleted = new LanePublication();
        var replacement = new LanePublication();
        map.stage(deleted, 7, null);
        map.stage(replacement, 7, "replacement");
        deleted.publish();
        assertThat(map.get(7)).isNull();
        replacement.publish();
        assertThat(map.get(7)).isEqualTo("replacement");
        var terminal = new LanePublication();
        map.stage(terminal, 7, null);
        terminal.publish();
        assertThat(map.size()).isZero();
    }

    @Test void oneReceiptPublishesEveryEntityWithoutCopyingTheLiveMaps() throws Exception {
        var orders = new LanePublishedMap<String>();
        var positions = new LanePublishedMap<String>();
        var receipt = new LanePublication();
        Thread lane = new Thread(() -> {
            for (int i = 1; i <= 1000; i++) orders.stage(receipt, i, "order" + i);
            positions.stage(receipt, 9, "position");
        });
        lane.start(); lane.join();
        assertThat(orders.values()).isEmpty();
        assertThat(positions.get(9)).isNull();
        receipt.publish();
        assertThat(orders.size()).isEqualTo(1000);
        assertThat(positions.get(9)).isEqualTo("position");
        assertThat(orders.get(1000)).isEqualTo("order1000");
    }

    @Test void terminalPublicationRemovesValue() {
        var map = new LanePublishedMap<String>();
        map.applyPublished(0, "admitted");
        map.applyPublished(0, "committed");
        assertThat(map.get(0)).isEqualTo("committed");
        map.applyPublished(0, "readmitted");
        assertThat(map.remove(0)).isEqualTo("readmitted");
    }

    @Test void clearRemovesPublishedValues() {
        var map = new LanePublishedMap<String>();
        map.applyPublished(7, "order");
        map.clear();
        assertThat(map.get(7)).isNull();
    }
}
