package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.junit.jupiter.api.Test;

class OwnerIndexChurnTest {
    @Test
    void ownerIndexesKeepTheirStorageAcrossBoundedInsertDeleteChurn() throws Exception {
        try (var state = new TradingRuntimeState()) {
            for (String name : new String[]{"pendingReservationUsers", "orderLaneIds", "reservationLaneIds", "positionLaneIds"}) {
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
                @SuppressWarnings("unchecked") var map = (Long2ObjectHashMap<Object>) field.get(target);
                var storage = Long2ObjectHashMap.class.getDeclaredField("values");
                storage.setAccessible(true);
                Object before = storage.get(map), value = new Object();
                for (long base = 1; base < 8192; base += 32) {
                    for (long key = base; key < base + 32; key++) map.put(key, value);
                    for (long key = base; key < base + 32; key++) assertThat(map.remove(key)).isSameAs(value);
                }
                assertThat(map.isEmpty()).isTrue();
                assertThat(storage.get(map)).as(name + " backing storage").isSameAs(before);
            }
        }
    }
}
