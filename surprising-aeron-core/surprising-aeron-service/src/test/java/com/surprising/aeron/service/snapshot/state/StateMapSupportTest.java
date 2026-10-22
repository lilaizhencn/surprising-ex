package com.surprising.aeron.service.snapshot.state;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 冷状态排序和只读视图契约，包含范围、条目及导航入口。 */
class StateMapSupportTest {
    @Test
    void sortsUnorderedInputsAndReusesFrozenViews() {
        NavigableMap<Long, String> frozen = StateMapSupport.freezeSorted(
                new HashMap<>(Map.of(3L, "three", 1L, "one", 2L, "two")));

        assertThat(frozen.keySet()).containsExactly(1L, 2L, 3L);
        assertThat(StateMapSupport.freezeSorted(frozen)).isSameAs(frozen);
        assertThat(StateMapSupport.isFrozen(frozen)).isTrue();
    }

    @Test
    void preservesTheComparatorOfSortedInputs() {
        NavigableMap<Long, String> input = new TreeMap<>(java.util.Comparator.reverseOrder());
        input.putAll(Map.of(1L, "one", 2L, "two", 3L, "three"));
        NavigableMap<Long, String> frozen = StateMapSupport.freezeSorted(input);

        assertThat(frozen.keySet()).containsExactly(3L, 2L, 1L);
        assertThat(frozen.lowerKey(2L)).isEqualTo(3L);
        assertThat(frozen.descendingKeySet()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void rejectsMutationThroughEntriesRangesAndNavigation() {
        NavigableMap<Long, String> frozen = StateMapSupport.freezeSorted(Map.of(1L, "one", 2L, "two"));

        assertThatThrownBy(() -> frozen.put(3L, "three")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.firstEntry().setValue("changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.entrySet().iterator().next().setValue("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.subMap(1L, true, 2L, true).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.descendingMap().pollFirstEntry()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.navigableKeySet().remove(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(frozen).containsExactlyEntriesOf(new TreeMap<>(Map.of(1L, "one", 2L, "two")));
    }

    @Test
    void sharesOnlyTheImmutableEmptyView() {
        NavigableMap<Long, String> first = StateMapSupport.freezeSorted(Map.of());
        NavigableMap<Long, String> second = StateMapSupport.freezeSorted(new TreeMap<>());

        assertThat(first).isSameAs(second).isEmpty();
        assertThatThrownBy(first::pollFirstEntry).isInstanceOf(UnsupportedOperationException.class);
    }
}
