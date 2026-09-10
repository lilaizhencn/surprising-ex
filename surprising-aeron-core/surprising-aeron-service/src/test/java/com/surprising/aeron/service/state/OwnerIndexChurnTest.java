package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.agrona.collections.Long2LongHashMap;
import org.junit.jupiter.api.Test;

class OwnerIndexChurnTest {
    @Test
    void ownerIndexesKeepTheirStorageAcrossBoundedInsertDeleteChurn() throws Exception {
        try (var state = new TradingRuntimeState()) {
            for (String name : new String[]{"pendingReservationUsers"}) {
                Object target = name.equals("pendingReservationUsers") ? state.pendingReservations : state;
                var field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                var map = (Long2LongHashMap) field.get(target);
                var storage = Long2LongHashMap.class.getDeclaredField("entries");
                storage.setAccessible(true);
                Object before = storage.get(map);
                for (long base = 1; base < 8192; base += 32) {
                    for (long key = base; key < base + 32; key++) map.put(key, key + 1);
                    for (long key = base; key < base + 32; key++) {
                        assertThat(map.remove(key)).isEqualTo(key + 1);
                        assertThat(map.get(key)).isZero();
                    }
                }
                assertThat(map.isEmpty()).isTrue();
                assertThat(storage.get(map)).as(name + " backing storage").isSameAs(before);
            }
            for (String name : new String[]{"publishedOrders", "publishedReservations"}) {
                Object target = name.equals("pendingReservationUsers") ? state.pendingReservations : state;
                var field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                @SuppressWarnings("unchecked") var map = (LanePublishedMap<Object>) field.get(target);
                var storage = LanePublishedMap.class.getDeclaredField("values");
                storage.setAccessible(true);
                Object before = storage.get(map), value = new Object();
                var lane = new AccountLaneState(0, 16);
                for (long base = 1; base < 8192; base += 32) {
                    var admission = new LanePublication();
                    for (long key = base; key < base + 32; key++) map.stage(admission, key, value);
                    assertThat(map.size()).isZero();
                    admission.visible = true;
                    admission.execute(lane);
                    assertThat(map.size()).isEqualTo(32);
                    var terminal = new LanePublication();
                    for (long key = base; key < base + 32; key++) map.stage(terminal, key, null);
                    terminal.visible = true;
                    terminal.execute(lane);
                    assertThat((java.util.Map<?, ?>) storage.get(map)).isEmpty();
                }
                assertThat(storage.get(map)).as(name + " storage identity").isSameAs(before);
            }
        }
    }
}
