package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StateMapSupportTest {

    @Test
    void emptyDeferredDeltaReusesTheFrozenBaseWithoutLoadingValues() {
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one")));
        AtomicInteger loads = new AtomicInteger();

        NavigableMap<Long, String> result = StateMapSupport.deferredDelta(
                base, Set.of(), Set.of(), ignored -> {
                    loads.incrementAndGet();
                    return "unexpected";
                });

        assertThat(result).isSameAs(base);
        assertThat(loads).hasValue(0);
    }

    @Test
    void deferredDeltaResolvesOneSharedValueAcrossConcurrentReaders() throws Exception {
        NavigableMap<Long, Object> base = StateMapSupport.freezeSorted(new TreeMap<>());
        AtomicInteger loads = new AtomicInteger();
        Object expected = new Object();
        NavigableMap<Long, Object> result = StateMapSupport.deferredDelta(
                base, new TreeSet<>(Set.of(7L)), new TreeSet<>(Set.of(7L)), ignored -> {
                    loads.incrementAndGet();
                    return expected;
                });

        List<CompletableFuture<Object>> readers = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            readers.add(CompletableFuture.supplyAsync(() -> result.get(7L)));
        }
        for (CompletableFuture<Object> reader : readers) {
            assertThat(reader.get(5, TimeUnit.SECONDS)).isSameAs(expected);
        }
        assertThat(result.entrySet()).containsExactly(Map.entry(7L, expected));
        assertThat(loads).hasValue(1);
    }

    @Test
    void deltaMapSharesBaseAndKeepsDeterministicOrder() {
        NavigableMap<Long, String> base = new TreeMap<>(Map.of(1L, "one", 2L, "two", 3L, "three"));
        NavigableMap<Long, String> delta = StateMapSupport.delta(base);

        delta.put(2L, "updated");
        delta.put(4L, "four");
        delta.remove(1L);

        assertThat(new ArrayList<>(delta.keySet())).containsExactly(2L, 3L, 4L);
        assertThat(delta).containsEntry(2L, "updated").containsEntry(3L, "three").containsEntry(4L, "four");
        assertThat(base).containsExactly(Map.entry(1L, "one"), Map.entry(2L, "two"), Map.entry(3L, "three"));
        assertThat(StateMapSupport.changedKeys(delta)).containsExactly(1L, 2L, 4L);

        NavigableMap<Long, String> frozen = StateMapSupport.freezeSorted(delta);
        assertThat(StateMapSupport.isDelta(frozen)).isTrue();
        assertThatThrownBy(() -> frozen.put(5L, "five"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nestedDeltaTracksOnlyItsOwnChanges() {
        NavigableMap<Long, String> base = new TreeMap<>(Map.of(1L, "one", 2L, "two"));
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(1L, "uno");
        NavigableMap<Long, String> second = StateMapSupport.delta(first);
        second.put(3L, "three");
        second.remove(2L);

        assertThat(StateMapSupport.changedKeys(first)).containsExactly(1L);
        assertThat(StateMapSupport.changedKeys(second)).containsExactly(2L, 3L);
    }

    @Test
    void directDeltaLineageRequiresTheImmediateParent() {
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one")));
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(2L, "two");
        NavigableMap<Long, String> frozenFirst = StateMapSupport.freezeSorted(first);
        NavigableMap<Long, String> second = StateMapSupport.delta(frozenFirst);
        second.put(3L, "three");
        NavigableMap<Long, String> frozenSecond = StateMapSupport.freezeSorted(second);

        assertThat(StateMapSupport.isDirectDeltaOf(base, base)).isTrue();
        assertThat(StateMapSupport.isDirectDeltaOf(base, frozenFirst)).isTrue();
        assertThat(StateMapSupport.isDirectDeltaOf(frozenFirst, frozenSecond)).isTrue();
        assertThat(StateMapSupport.isDirectDeltaOf(base, frozenSecond)).isFalse();
        assertThat(StateMapSupport.isDirectDeltaOf(base, new TreeMap<>(base))).isFalse();
    }

    @Test
    void descendantDeltaLineageIncludesEarlierParents() {
        NavigableMap<Long, String> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, "one")));
        NavigableMap<Long, String> first = StateMapSupport.delta(base);
        first.put(2L, "two");
        NavigableMap<Long, String> second = StateMapSupport.delta(StateMapSupport.freezeSorted(first));
        second.put(3L, "three");

        assertThat(StateMapSupport.isDeltaDescendantOf(base, second)).isTrue();
        assertThat(StateMapSupport.isDeltaDescendantOf(first, second)).isTrue();
        assertThat(StateMapSupport.isDeltaDescendantOf(base, new TreeMap<>(second))).isFalse();
    }

    @Test
    void deltaLineageValidationDoesNotCompareEveryStateValue() {
        AtomicInteger comparisons = new AtomicInteger();
        CountingValue original = new CountingValue("before", comparisons);
        NavigableMap<Long, CountingValue> base = StateMapSupport.freezeSorted(
                new TreeMap<>(Map.of(1L, original)));
        NavigableMap<Long, CountingValue> delta = StateMapSupport.delta(base);
        delta.put(1L, new CountingValue("after", comparisons));
        comparisons.set(0);

        StateMapSupport.requireDeltaLineage(base, delta, "users");

        assertThat(comparisons).as("delta lineage must be checked before full map equality").hasValue(0);
    }

    @Test
    void persistentPathCopiesKeepValuesWithoutPeriodicCompaction() {
        NavigableMap<Long, String> base = new TreeMap<>(Map.of(1L, "one"));
        NavigableMap<Long, String> previous = base;
        for (int index = 0; index < 300; index++) {
            NavigableMap<Long, String> next = StateMapSupport.delta(previous);
            next.put((long) index + 2, "value-" + index);
            previous = next;
        }

        assertThat(previous.get(301L)).isEqualTo("value-299");
    }

    private record CountingValue(String value, AtomicInteger comparisons) {
        @Override
        public boolean equals(Object other) {
            comparisons.incrementAndGet();
            return other instanceof CountingValue that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
