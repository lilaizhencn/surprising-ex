package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RuntimeChangeBufferTest {
    @Test
    void eclipseDrainDeletesOverwritesAndReleasesSlotsAcrossReuse() throws Exception {
        var buffer = new RuntimeChangeBuffer<String>();
        var target = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<String>();
        var values = RuntimeChangeBuffer.class.getDeclaredField("values");
        values.setAccessible(true);
        for (int round = 0; round < 64; round++) {
            for (long key = -64; key < 64; key++) buffer.put(key, "value" + round);
            buffer.put(0, "updated");
            buffer.put(1, null);
            buffer.drainToEclipseMap(target);
            assertThat(target.size()).isEqualTo(127);
            assertThat(target.get(0)).isEqualTo("updated");
            assertThat(target.containsKey(1)).isFalse();
            assertThat(buffer.isEmpty()).isTrue();
            assertThat(buffer.containsKey(0)).isFalse();
            assertThat((Object[]) values.get(buffer)).containsOnlyNulls();
            buffer.drainToEclipseMap(target);
            assertThat(target.size()).isEqualTo(127);
        }
    }

    @Test
    void drainPreservesNullDeletesAndOverwritesAndReleasesReferencesAcrossReuse() throws Exception {
        var buffer = new RuntimeChangeBuffer<String>();
        var published = new java.util.HashMap<Long, String>();
        for (int round = 0; round < 64; round++) {
            for (long key = -64; key < 64; key++) buffer.put(key, "value" + round);
            buffer.put(0, "updated");
            buffer.put(1, null);
            int[] visits = {0};
            buffer.drainTo((key, value) -> { visits[0]++; published.put(key, value); });
            assertThat(visits[0]).isEqualTo(128);
            assertThat(published).containsEntry(0L, "updated").containsEntry(1L, null);
            assertThat(buffer.isEmpty()).isTrue();
            assertThat(buffer.indexOf(0)).isEqualTo(-1);
            var field = RuntimeChangeBuffer.class.getDeclaredField("values");
            field.setAccessible(true);
            assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
        }
        buffer.put(0, null);
        assertThat(buffer.indexOf(0)).isGreaterThanOrEqualTo(0);
        assertThat(buffer.valueAt(buffer.indexOf(0))).isNull();
        buffer.clear();
        assertThat(buffer.containsKey(0)).isFalse();
    }
    @Test
    void matchesInsertionOrderedMapAcrossGrowthOverwriteNullAndReuse() {
        var actual = new RuntimeChangeBuffer<String>();
        var expected = new java.util.LinkedHashMap<Long, String>();
        var random = new java.util.Random(25620);
        for (int round = 0; round < 4; round++) {
            for (long key : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE, -1}) {
                actual.put(key, null);
                expected.put(key, null);
            }
            for (int i = 0; i < 4096; i++) {
                long key = random.nextInt(2048) - 1024;
                String value = i % 3 == 0 ? null : "value" + i;
                int slot = actual.put(key, value);
                expected.put(key, value);
                assertThat(actual.keyAt(slot)).isEqualTo(key);
                assertThat(actual.valueAt(slot)).isEqualTo(value);
            }
            assertThat(actual.size()).isEqualTo(expected.size());
            int slot = 0;
            for (var entry : expected.entrySet()) {
                assertThat(actual.indexOf(entry.getKey())).isEqualTo(slot);
                assertThat(actual.keyAt(slot)).isEqualTo(entry.getKey());
                assertThat(actual.valueAt(slot++)).isEqualTo(entry.getValue());
            }
            actual.clear();
            for (long key : expected.keySet()) assertThat(actual.containsKey(key)).isFalse();
            expected.clear();
        }
    }

}
