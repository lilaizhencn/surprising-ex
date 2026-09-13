package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeIndexedChangeBufferTest {
    @Test void orderHandoffsDoNotAllocateUnusedPreparedColumns() throws Exception {
        var lane = new RuntimeIndexedChangeBuffer<String, Void>();
        var owner = new OwnerIndexedChanges<String, Void>();
        var prepared = RuntimeIndexedChangeBuffer.class.getDeclaredField("prepared");
        var present = RuntimeIndexedChangeBuffer.class.getDeclaredField("present");
        prepared.setAccessible(true); present.setAccessible(true);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 100; i++) lane.put(i, "value");
            owner.adopt(0, lane);
            assertThat(lane.isEmpty()).isTrue();
            assertThat((Object[]) prepared.get(lane)).isEmpty();
            assertThat((boolean[]) present.get(lane)).isEmpty();
            for (int i = 0; i < 100; i++) assertThat(owner.get(i)).isEqualTo("value");
            owner.clear();
        }
    }

    @Test void coalescesStateAndPreparedIndexWithoutLosingDeletionOrFallback() {
        var source = new RuntimeIndexedChangeBuffer<String, String>();
        var target = new RuntimeIndexedChangeBuffer<String, String>();
        source.put(1, "open"); source.putPrepared(1, "active");
        source.put(2, "closed"); source.putPrepared(2, null);
        source.put(3, "control");
        source.drainIndexedTo(target::putIndexed);
        assertThat(source.isEmpty()).isTrue();
        target.putIndexed(1, "filled", true, null);
        var result = new java.util.HashMap<Long, String>();
        target.forEachIndexed((key, value, present, prepared) ->
                result.put(key, value + ":" + present + ":" + prepared));
        assertThat(result).containsEntry(1L, "filled:true:null").containsEntry(2L, "closed:true:null")
                .containsEntry(3L, "control:false:null");
        assertThat(target.size()).isEqualTo(3);
    }

    @Test void reuseAndGrowthDoNotRetainPreparedValues() throws Exception {
        var buffer = new RuntimeIndexedChangeBuffer<String, String>();
        for (int i = 0; i < 100; i++) { buffer.put(i, "value"); buffer.putPrepared(i, "index"); }
        buffer.clear();
        var field = RuntimeIndexedChangeBuffer.class.getDeclaredField("prepared"); field.setAccessible(true);
        assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
        for (int i = 0; i < 100; i++) buffer.put(1000 + i, "next");
        buffer.forEachIndexed((key, value, present, prepared) -> {
            assertThat(present).isFalse(); assertThat(prepared).isNull();
        });
        assertThatThrownBy(() -> buffer.putPrepared(-1, "orphan")).isInstanceOf(IllegalStateException.class);
        buffer.drainToAgronaMap(new org.agrona.collections.Long2ObjectHashMap<>());
        assertThat(buffer.isEmpty()).isTrue();
        assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
    }
}
