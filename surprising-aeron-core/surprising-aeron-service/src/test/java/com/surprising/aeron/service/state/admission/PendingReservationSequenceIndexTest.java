package com.surprising.aeron.service.state.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PendingReservationSequenceIndexTest {
@Test
    void promotionPreservesSetOrderWithoutArrayMaterializationAcrossEveryRemoval() {
        var index = new PendingReservationTracker.PendingReservationSequenceIndex(4);
        for (long order = 1; order <= 100; order++) index.add(19, order);
        while (index.containsKey(19)) {
            long first = index.firstOrderBySequence.get(19);
            var additional = index.additionalOrdersBySequence.get(19);
            long expected = additional == null || additional.isEmpty() ? 0 : additional.toArray()[0];
            index.remove(19, first);
            assertThat(index.firstOrderBySequence.get(19)).isEqualTo(expected);
        }
        assertThat(index.additionalOrdersBySequence.isEmpty()).isTrue();
    }

@Test
    void pendingReservationSequenceIndexKeepsSingleOrderOnPrimitivePath() {
        PendingReservationTracker.PendingReservationSequenceIndex index =
                new PendingReservationTracker.PendingReservationSequenceIndex(4);

        index.add(101, 1_001);

        assertThat(index.containsKey(101)).isTrue();
        assertThat(index.contains(101, 1_001)).isTrue();
        assertThat(index.orderIds(101)).containsExactly(1_001);

        index.remove(101, 1_001);

        assertThat(index.isEmpty()).isTrue();
        assertThat(index.orderIds(101)).isEmpty();
    }

@Test
    void pendingReservationSequenceRolloverPreservesTheActivePrefixAndLongKeys() {
        var index = new PendingReservationTracker.PendingReservationSequenceIndex(4);
        for (long sequence = 1; sequence <= 20_000; sequence++) {
            index.add(sequence, Long.MAX_VALUE - sequence);
            if (sequence > 256) index.remove(sequence - 256, Long.MAX_VALUE - (sequence - 256));
            assertThat(index.firstOrderId(sequence)).isEqualTo(Long.MAX_VALUE - sequence);
            assertThat(index.firstOrderId(Math.max(1, sequence - 255)))
                    .isEqualTo(Long.MAX_VALUE - Math.max(1, sequence - 255));
            if (sequence > 256) assertThat(index.containsKey(sequence - 256)).isFalse();
        }
        index.add(Long.MAX_VALUE, 1);
        index.add(Long.MAX_VALUE, 2);
        index.remove(Long.MAX_VALUE, 1);
        assertThat(index.firstOrderId(Long.MAX_VALUE)).isEqualTo(2);
        index.clear();
        assertThat(index.isEmpty()).isTrue();
        assertThat(index.additionalOrdersBySequence.isEmpty()).isTrue();
        index.add(1, 11);
        assertThat(index.orderIds(1)).containsExactly(11);
    }

@Test
    void pendingReservationSequenceIndexPromotesBatchOrdersWhenFirstCompletes() {
        PendingReservationTracker.PendingReservationSequenceIndex index =
                new PendingReservationTracker.PendingReservationSequenceIndex(4);
        index.add(101, 1_001);
        index.add(101, 1_002);
        index.add(101, 1_003);
        index.add(102, 2_001);

        assertThat(index.orderIds(101)).containsExactlyInAnyOrder(1_001, 1_002, 1_003);
        assertThatThrownBy(() -> index.add(101, 1_002))
                .isInstanceOf(IllegalStateException.class);

        index.remove(101, 1_001);
        assertThat(index.orderIds(101)).containsExactlyInAnyOrder(1_002, 1_003);
        index.remove(101, 1_002);
        index.remove(101, 1_003);

        assertThat(index.containsKey(101)).isFalse();
        assertThat(index.orderIds(102)).containsExactly(2_001);
        index.clear();
        assertThat(index.isEmpty()).isTrue();
    }
}
