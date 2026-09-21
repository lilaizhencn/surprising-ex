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
                Object value = new Object();
                // Warm to the fixed live population, then check actual arrays, not merely
                // the map object's identity (which also survives allocating rehashes).
                for (long key = 1; key <= 32; key++) map.applyPublished(key, value);
                for (long key = 1; key <= 32; key++) map.remove(key);
                Object table = storage.get(map);
                var keyStorage = table.getClass().getDeclaredField("keys");
                var valueStorage = table.getClass().getDeclaredField("values");
                keyStorage.setAccessible(true); valueStorage.setAccessible(true);
                Object keysBefore = keyStorage.get(table), valuesBefore = valueStorage.get(table);
                for (long base = 1; base < 8192; base += 32) {
                    for (long key = base; key < base + 32; key++) map.applyPublished(key, value);
                    assertThat(map.size()).isEqualTo(32);
                    for (long key = base; key < base + 32; key++) map.applyPublished(key, null);
                    assertThat(map.size()).isZero();
                }
                assertThat(keyStorage.get(table)).as(name + " key array").isSameAs(keysBefore);
                assertThat(valueStorage.get(table)).as(name + " value array").isSameAs(valuesBefore);
            }
        }
    }
}
