package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.util.HashMap;

class OwnerIndexedChangesTest {
    @Test void laneStorageMovesWithoutCopyAndCanBeReusedBeforeOwnerPublication() throws Exception {
        var owner = new OwnerIndexedChanges<String, String>();
        var first = new RuntimeIndexedChangeBuffer<String, String>();
        var second = new RuntimeIndexedChangeBuffer<String, String>();
        first.putIndexed(1, "a", true, "index-a");
        second.putIndexed(2, "b", true, "index-b");
        var keys = RuntimeChangeBuffer.class.getDeclaredField("keys"); keys.setAccessible(true);
        Object originalKeys = keys.get(first);
        owner.adopt(0, first); owner.adopt(3, second);
        assertThat(first.isEmpty()).isTrue(); assertThat(second.isEmpty()).isTrue();
        var lanes = OwnerIndexedChanges.class.getDeclaredField("lanes"); lanes.setAccessible(true);
        assertThat(keys.get(((Object[])lanes.get(owner))[0])).isSameAs(originalKeys);
        first.put(1, "next-command"); second.clear();
        assertThat(owner.get(1)).isEqualTo("a");
        assertThat(owner.toArray()).containsExactly(1, 2);
        var prepared = new HashMap<Long, String>();
        owner.forEachIndexed((id, value, flag, index) -> { assertThat(flag).isTrue(); prepared.put(id, index); });
        assertThat(prepared).containsEntry(1L,"index-a").containsEntry(2L,"index-b");
        owner.clear(); assertThat(owner.isEmpty()).isTrue();
        assertThat(first.get(1)).isEqualTo("next-command");
    }
    @Test void sameLaneAndOwnerControlUpdatesKeepLastValueAndInvalidateOldPreparedIndex() {
        var owner = new OwnerIndexedChanges<String, String>();
        var lane = new RuntimeIndexedChangeBuffer<String, String>();
        lane.putIndexed(9, "old", true, "old-index"); owner.adopt(2, lane);
        lane.putIndexed(9, "new", true, "new-index"); owner.adopt(2, lane);
        assertThat(owner.toArray()).containsExactly(9);
        assertThat(owner.get(9)).isEqualTo("new");
        owner.put(9, null);
        owner.forEachIndexed((id, value, flag, index) -> { assertThat(value).isNull(); assertThat(flag).isFalse(); assertThat(index).isNull(); });
        lane.putIndexed(9, "after-control", true, "last-index"); owner.adopt(2,lane);
        assertThat(owner.get(9)).isEqualTo("after-control");
        owner.forEachIndexed((id, value, flag, index) -> { assertThat(flag).isTrue(); assertThat(index).isEqualTo("last-index"); });
    }
}
