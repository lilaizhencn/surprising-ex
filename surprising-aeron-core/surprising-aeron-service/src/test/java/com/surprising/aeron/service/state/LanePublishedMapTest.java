package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LanePublishedMapTest {
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
        var lane = new AccountLaneState(0, 16);
        map.put(7, "committed");
        var first = new LanePublication();
        var second = new LanePublication();
        map.stage(first, 7, "first");
        map.stage(second, 7, "second");
        assertThat(map.get(7)).isEqualTo("committed");
        first.visible = true;
        first.execute(lane);
        assertThat(map.get(7)).isEqualTo("first");
        second.visible = true;
        second.execute(lane);
        assertThat(map.get(7)).isEqualTo("second");
        var deleted = new LanePublication();
        var replacement = new LanePublication();
        map.stage(deleted, 7, null);
        map.stage(replacement, 7, "replacement");
        deleted.visible = true;
        deleted.execute(lane);
        assertThat(map.get(7)).isNull();
        replacement.visible = true;
        replacement.execute(lane);
        assertThat(map.get(7)).isEqualTo("replacement");
        var terminal = new LanePublication();
        map.stage(terminal, 7, null);
        terminal.visible = true;
        terminal.execute(lane);
        var storage = LanePublishedMap.class.getDeclaredField("values");
        storage.setAccessible(true);
        assertThat((java.util.Map<?, ?>)storage.get(map)).isEmpty();
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
        receipt.visible = true;
        assertThat(orders.size()).isEqualTo(1000);
        assertThat(positions.get(9)).isEqualTo("position");
        receipt.execute(new AccountLaneState(0, 16));
        assertThat(orders.get(1000)).isEqualTo("order1000");
    }
}
