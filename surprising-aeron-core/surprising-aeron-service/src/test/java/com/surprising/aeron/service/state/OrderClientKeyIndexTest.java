package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.Test;

class OrderClientKeyIndexTest {
    @Test void churnDoesNotRebuildTheSingleAliasTableOrLoseZeroAliases() throws Exception {
        var index = new OrderClientKeyIndex();
        index.add(1, 0);
        var field = OrderClientKeyIndex.class.getDeclaredField("single");
        field.setAccessible(true);
        var map = (org.agrona.collections.Long2LongHashMap) field.get(index);
        var entries = map.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        Object storage = entries.get(map);
        for (long id = 2; id < 20_000; id++) {
            index.add(id, id);
            index.remove(id, id);
        }
        assertThat(entries.get(map)).isSameAs(storage);
        assertThat(map.size()).isOne();
        assertThat(keys(index, 1)).containsExactly(0);
    }

    @Test
    void handlesSingleAliasPromotionDemotionAndCompleteRetirement() {
        OrderClientKeyIndex index = new OrderClientKeyIndex();
        index.add(11, 0);
        index.add(11, 0);
        index.add(12, 99);
        assertThat(keys(index, 11)).containsExactly(0);
        index.add(11, 91);
        index.add(11, 92);
        index.remove(11, 777);
        assertThat(keys(index, 11)).containsExactlyInAnyOrder(0, 91, 92);
        index.remove(11, 0);
        index.remove(11, 91);
        assertThat(keys(index, 11)).containsExactly(92);
        index.add(11, 93);
        index.remove(11);
        assertThat(keys(index, 11)).isEmpty();
        assertThat(keys(index, 12)).containsExactly(99);
        index.add(11, 94);
        index.remove(11, 93);
        assertThat(keys(index, 11)).containsExactly(94);
        index.remove(11, 94);
        assertThat(keys(index, 11)).isEmpty();
    }

    private static long[] keys(OrderClientKeyIndex index, long orderId) {
        LongHashSet keys = new LongHashSet();
        index.forEach(orderId, keys::add);
        return keys.toArray();
    }
}
