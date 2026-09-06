package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.Test;

class OrderClientKeyIndexTest {
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
