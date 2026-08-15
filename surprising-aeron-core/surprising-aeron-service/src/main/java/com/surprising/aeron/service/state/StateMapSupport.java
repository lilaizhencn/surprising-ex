package com.surprising.aeron.service.state;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

@SuppressWarnings("unchecked")
final class StateMapSupport {

    private static final int MAX_DELTA_DEPTH = 256;

    private StateMapSupport() {
    }

    @SuppressWarnings("unchecked")
    static <K, V> NavigableMap<K, V> freezeSorted(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?>) {
            return (NavigableMap<K, V>) values;
        }
        NavigableMap<K, V> sorted = values instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<K, V>) navigable : new TreeMap<>(values);
        return new FrozenMap<>(sorted);
    }

    @SuppressWarnings("unchecked")
    static <K, V> NavigableMap<K, V> delta(Map<K, V> values) {
        NavigableMap<K, V> base;
        if (values instanceof FrozenMap<?, ?> frozen) {
            base = ((FrozenMap<K, V>) frozen).raw();
        } else if (values instanceof NavigableMap<?, ?> navigable) {
            base = (NavigableMap<K, V>) navigable;
        } else {
            base = new TreeMap<>(values);
        }
        if (base instanceof DeltaMap<?, ?> delta && ((DeltaMap<?, ?>) delta).depth() >= MAX_DELTA_DEPTH) {
            return new DeltaMap<>(new TreeMap<>(base), (DeltaMap<K, V>) delta);
        }
        return new DeltaMap<>(base);
    }

    static boolean isDelta(Map<?, ?> values) {
        if (values instanceof DeltaMap<?, ?>) return true;
        return values instanceof FrozenMap<?, ?> frozen && isDelta(((FrozenMap<?, ?>) frozen).raw());
    }

    static boolean isFrozen(Map<?, ?> values) {
        return values instanceof FrozenMap<?, ?>;
    }

    @SuppressWarnings("unchecked")
    static <K> Set<K> changedKeys(Map<K, ?> values) {
        if (values instanceof DeltaMap<?, ?> delta) return (Set<K>) delta.changedKeys();
        if (values instanceof FrozenMap<?, ?> frozen) return changedKeys((Map<K, ?>) ((FrozenMap<?, ?>) frozen).raw());
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    static <K> Set<K> changedKeysSince(Map<K, ?> before, Map<K, ?> after) {
        NavigableMap<K, ?> beforeRaw = raw(before);
        NavigableMap<K, ?> afterRaw = raw(after);
        if (beforeRaw == null || afterRaw == null) return null;
        if (beforeRaw == afterRaw) return Set.of();
        if (!(afterRaw instanceof DeltaMap<?, ?> rawDelta)) return null;
        DeltaMap<K, ?> delta = (DeltaMap<K, ?>) rawDelta;
        TreeSet<K> keys = new TreeSet<>(delta.comparator());
        while (delta != null) {
            keys.addAll(delta.changedKeys());
            if (beforeRaw == delta.parent() || beforeRaw == delta.base()) {
                return Collections.unmodifiableSet(keys);
            }
            delta = delta.parent();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> NavigableMap<K, V> raw(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?> frozen) {
            return ((FrozenMap<K, V>) frozen).raw();
        }
        return values instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<K, V>) navigable : null;
    }

    private static final class FrozenMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V> {
        private final NavigableMap<K, V> raw;
        private final NavigableMap<K, V> delegate;

        private FrozenMap(NavigableMap<K, V> raw) {
            this.raw = raw;
            this.delegate = Collections.unmodifiableNavigableMap(raw);
        }

        private NavigableMap<K, V> raw() {
            return raw;
        }

        @Override
        public int size() { return delegate.size(); }

        @Override
        public boolean isEmpty() { return delegate.isEmpty(); }

        @Override
        public boolean containsKey(Object key) { return delegate.containsKey(key); }

        @Override
        public boolean containsValue(Object value) { return delegate.containsValue(value); }

        @Override
        public V get(Object key) { return delegate.get(key); }

        @Override
        public Set<Entry<K, V>> entrySet() { return delegate.entrySet(); }

        @Override
        public Comparator<? super K> comparator() { return delegate.comparator(); }

        @Override
        public Entry<K, V> lowerEntry(K key) { return delegate.lowerEntry(key); }

        @Override
        public K lowerKey(K key) { return delegate.lowerKey(key); }

        @Override
        public Entry<K, V> floorEntry(K key) { return delegate.floorEntry(key); }

        @Override
        public K floorKey(K key) { return delegate.floorKey(key); }

        @Override
        public Entry<K, V> ceilingEntry(K key) { return delegate.ceilingEntry(key); }

        @Override
        public K ceilingKey(K key) { return delegate.ceilingKey(key); }

        @Override
        public Entry<K, V> higherEntry(K key) { return delegate.higherEntry(key); }

        @Override
        public K higherKey(K key) { return delegate.higherKey(key); }

        @Override
        public Entry<K, V> firstEntry() { return delegate.firstEntry(); }

        @Override
        public Entry<K, V> lastEntry() { return delegate.lastEntry(); }

        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        @Override
        public NavigableMap<K, V> descendingMap() { return delegate.descendingMap(); }

        @Override
        public NavigableSet<K> navigableKeySet() { return delegate.navigableKeySet(); }

        @Override
        public NavigableSet<K> descendingKeySet() { return delegate.descendingKeySet(); }

        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return delegate.subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) { return delegate.headMap(toKey, inclusive); }

        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) { return delegate.tailMap(fromKey, inclusive); }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return delegate.subMap(fromKey, toKey); }

        @Override
        public SortedMap<K, V> headMap(K toKey) { return delegate.headMap(toKey); }

        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return delegate.tailMap(fromKey); }

        @Override
        public K firstKey() { return delegate.firstKey(); }

        @Override
        public K lastKey() { return delegate.lastKey(); }
    }

    private static final class DeltaMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V> {
        private final NavigableMap<K, V> base;
        private final NavigableMap<K, V> updates;
        private final Set<K> removals;
        private final int depth;
        private final DeltaMap<K, V> parent;
        private int size;

        private DeltaMap(NavigableMap<K, V> base) {
            this(base, base instanceof DeltaMap<?, ?> delta ? (DeltaMap<K, V>) delta : null);
        }

        private DeltaMap(NavigableMap<K, V> base, DeltaMap<K, V> parent) {
            this.base = base;
            this.parent = parent;
            this.updates = new TreeMap<>(base.comparator());
            this.removals = new TreeSet<>(base.comparator());
            this.depth = parent == null ? 1 : parent.depth + 1;
            this.size = base.size();
        }

        private int depth() {
            return depth;
        }

        private NavigableMap<K, V> base() {
            return base;
        }

        private DeltaMap<K, V> parent() {
            return parent;
        }

        private Set<K> changedKeys() {
            TreeSet<K> keys = new TreeSet<>(base.comparator());
            keys.addAll(updates.keySet());
            keys.addAll(removals);
            return Collections.unmodifiableSet(keys);
        }

        @Override
        public V get(Object key) {
            if (removals.contains(key)) return null;
            if (updates.containsKey(key)) return updates.get(key);
            return base.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return !removals.contains(key) && (updates.containsKey(key) || base.containsKey(key));
        }

        @Override
        public int size() { return size; }

        @Override
        public V put(K key, V value) {
            if (key == null || value == null) throw new NullPointerException("state map does not allow null");
            boolean present = containsKey(key);
            V previous = present ? get(key) : null;
            if (!present) size++;
            updates.put(key, value);
            removals.remove(key);
            return previous;
        }

        @Override
        public V remove(Object key) {
            if (!containsKey(key)) return null;
            V previous = get(key);
            if (base.containsKey(key)) {
                removals.add((K) key);
                updates.remove(key);
            } else {
                updates.remove(key);
            }
            size--;
            return previous;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<K, V>> iterator() { return new MergeIterator(); }

                @Override
                public int size() { return DeltaMap.this.size; }
            };
        }

        private final class MergeIterator implements Iterator<Entry<K, V>> {
            private final Iterator<Entry<K, V>> baseIterator = base.entrySet().iterator();
            private final Iterator<Entry<K, V>> updateIterator = updates.entrySet().iterator();
            private Entry<K, V> baseNext = next(baseIterator);
            private Entry<K, V> updateNext = next(updateIterator);
            private Entry<K, V> nextEntry;

            @Override
            public boolean hasNext() {
                if (nextEntry == null) advance();
                return nextEntry != null;
            }

            @Override
            public Entry<K, V> next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Entry<K, V> value = nextEntry;
                nextEntry = null;
                return value;
            }

            private void advance() {
                while (baseNext != null || updateNext != null) {
                    if (baseNext == null) {
                        Entry<K, V> candidate = updateNext;
                        updateNext = next(updateIterator);
                        if (!removals.contains(candidate.getKey())) {
                            nextEntry = immutable(candidate);
                            return;
                        }
                    } else if (updateNext == null) {
                        Entry<K, V> candidate = baseNext;
                        baseNext = next(baseIterator);
                        if (!removals.contains(candidate.getKey()) && !updates.containsKey(candidate.getKey())) {
                            nextEntry = immutable(candidate);
                            return;
                        }
                    } else {
                        int comparison = compare(baseNext.getKey(), updateNext.getKey());
                        if (comparison < 0) {
                            Entry<K, V> candidate = baseNext;
                            baseNext = next(baseIterator);
                            if (!removals.contains(candidate.getKey()) && !updates.containsKey(candidate.getKey())) {
                                nextEntry = immutable(candidate);
                                return;
                            }
                        } else if (comparison == 0) {
                            Entry<K, V> candidate = updateNext;
                            baseNext = next(baseIterator);
                            updateNext = next(updateIterator);
                            if (!removals.contains(candidate.getKey())) {
                                nextEntry = immutable(candidate);
                                return;
                            }
                        } else {
                            Entry<K, V> candidate = updateNext;
                            updateNext = next(updateIterator);
                            if (!removals.contains(candidate.getKey())) {
                                nextEntry = immutable(candidate);
                                return;
                            }
                        }
                    }
                }
            }

            private Entry<K, V> immutable(Entry<K, V> entry) {
                return new SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
            }

            private Entry<K, V> next(Iterator<Entry<K, V>> iterator) {
                return iterator.hasNext() ? iterator.next() : null;
            }
        }

        @SuppressWarnings("unchecked")
        private int compare(K left, K right) {
            Comparator<? super K> comparator = comparator();
            return comparator == null ? ((Comparable<? super K>) left).compareTo(right) : comparator.compare(left, right);
        }

        private NavigableMap<K, V> materialized() {
            TreeMap<K, V> values = new TreeMap<>(comparator());
            for (Entry<K, V> entry : entrySet()) values.put(entry.getKey(), entry.getValue());
            return values;
        }

        @Override
        public Comparator<? super K> comparator() { return base.comparator(); }

        @Override
        public Entry<K, V> lowerEntry(K key) { return materialized().lowerEntry(key); }

        @Override
        public K lowerKey(K key) { return materialized().lowerKey(key); }

        @Override
        public Entry<K, V> floorEntry(K key) { return materialized().floorEntry(key); }

        @Override
        public K floorKey(K key) { return materialized().floorKey(key); }

        @Override
        public Entry<K, V> ceilingEntry(K key) { return materialized().ceilingEntry(key); }

        @Override
        public K ceilingKey(K key) { return materialized().ceilingKey(key); }

        @Override
        public Entry<K, V> higherEntry(K key) { return materialized().higherEntry(key); }

        @Override
        public K higherKey(K key) { return materialized().higherKey(key); }

        @Override
        public Entry<K, V> firstEntry() { return materialized().firstEntry(); }

        @Override
        public Entry<K, V> lastEntry() { return materialized().lastEntry(); }

        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        @Override
        public NavigableMap<K, V> descendingMap() { return materialized().descendingMap(); }

        @Override
        public NavigableSet<K> navigableKeySet() { return materialized().navigableKeySet(); }

        @Override
        public NavigableSet<K> descendingKeySet() { return materialized().descendingKeySet(); }

        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return materialized().subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) { return materialized().headMap(toKey, inclusive); }

        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) { return materialized().tailMap(fromKey, inclusive); }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return materialized().subMap(fromKey, toKey); }

        @Override
        public SortedMap<K, V> headMap(K toKey) { return materialized().headMap(toKey); }

        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return materialized().tailMap(fromKey); }

        @Override
        public K firstKey() { return materialized().firstKey(); }

        @Override
        public K lastKey() { return materialized().lastKey(); }
    }
}
