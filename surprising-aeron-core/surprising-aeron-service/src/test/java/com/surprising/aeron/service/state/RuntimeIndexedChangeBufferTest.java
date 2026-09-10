package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeIndexedChangeBufferTest {
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
        buffer.drain(new org.agrona.collections.Long2ObjectHashMap<>(), null, 0);
        assertThat(buffer.isEmpty()).isTrue();
        assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
    }
}
