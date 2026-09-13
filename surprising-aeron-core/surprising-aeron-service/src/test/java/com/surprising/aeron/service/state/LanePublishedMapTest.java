package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LanePublishedMapTest {
    @Test void recycledBatchAdmissionDiscardsUnpublishedValuesAndRetainsCapacity() throws Exception {
        var event = new PlaceBatchAdmissionEvent();
        var publication = new LanePublication();
        var map = new LanePublishedMap<String>();
        for (int i = 0; i < 41; i++) map.stage(publication, i, "discarded");
        publication.admissionSequence = 17;
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
        assertThat(publication.admissionSequence).isZero();
        publication.publish();
        assertThat(map.size()).isZero();
        map.stage(publication, 99, "next");
        publication.admissionSequence = 18;
        publication.publish();
        assertThat(map.size()).isOne();
        assertThat(map.get(99)).isEqualTo("next");
        assertThat(map.admissionSequence(99)).isEqualTo(18);
        event.clear();
        assertThat(map.get(99)).isEqualTo("next");
    }

    @Test void settlementReusesPublicationAndDiscardsUnpublishedReferences() {
        var runtime = new TradingRuntimeState();
        var delta = new TradingRuntimeState.LaneDelta();
        delta.users.put(7, new UserRuntime(7));
        delta.preparePublication(runtime);
        var buffer = delta.publication;
        // Failure before publication: recycling must not publish user 7 on the next command.
        delta.clear();
        delta.users.put(8, new UserRuntime(8));
        delta.preparePublication(runtime);
        org.assertj.core.api.Assertions.assertThat(delta.publication).isSameAs(buffer);
        delta.publication.publish();
        org.assertj.core.api.Assertions.assertThat(runtime.publishedUsers.get(7)).isNull();
        org.assertj.core.api.Assertions.assertThat(runtime.publishedUsers.get(8)).isNotNull();
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

    @Test void terminalPublicationRemovesAdmissionMetadataWithoutRemovingTheValue() throws Exception {
        var map = new LanePublishedMap<String>();
        map.applyPublished(0, "admitted", 17);
        assertThat(map.admissionSequence(0)).isEqualTo(17);
        map.applyPublished(0, "committed", 0);
        assertThat(map.get(0)).isEqualTo("committed");
        assertThat(map.admissionSequence(0)).isZero();
        var field = LanePublishedMap.class.getDeclaredField("admissionSequences");
        field.setAccessible(true);
        assertThat(((org.agrona.collections.Long2LongHashMap) field.get(map)).isEmpty()).isTrue();
        map.applyPublished(0, "readmitted", 18);
        assertThat(map.remove(0)).isEqualTo("readmitted");
        assertThat(map.admissionSequence(0)).isZero();
    }

    @Test void clearRemovesValuesAndTheirAdmissionMetadata() {
        var map = new LanePublishedMap<String>();
        map.applyPublished(7, "order", 42);
        assertThat(map.admissionSequence(7)).isEqualTo(42);
        map.clear();
        assertThat(map.get(7)).isNull();
        assertThat(map.admissionSequence(7)).isZero();
    }
}
