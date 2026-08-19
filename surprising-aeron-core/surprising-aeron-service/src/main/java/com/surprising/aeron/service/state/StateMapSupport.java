package com.surprising.aeron.service.state;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

@SuppressWarnings("unchecked")
public final class StateMapSupport {

    private StateMapSupport() {
    }

    static <K, V> NavigableMap<K, V> freezeSorted(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?>) {
            return (NavigableMap<K, V>) values;
        }
        NavigableMap<K, V> sorted = values instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<K, V>) navigable : new TreeMap<>(values);
        return new FrozenMap<>(sorted);
    }

    public static <K, V> NavigableMap<K, V> delta(Map<K, V> values) {
        NavigableMap<K, V> base = raw(values);
        if (base == null) {
            base = new TreeMap<>(values);
        }
        if (base instanceof DeltaMap<?, ?> previous) {
            DeltaMap<K, V> typed = (DeltaMap<K, V>) previous;
            return new DeltaMap<>(typed.tree, typed, typed);
        }
        return new DeltaMap<>(PersistentTreeMap.from(base), null, base);
    }

    static boolean isDelta(Map<?, ?> values) {
        if (values instanceof DeltaMap<?, ?>) return true;
        return values instanceof FrozenMap<?, ?> frozen && isDelta(frozen.raw());
    }

    static boolean isFrozen(Map<?, ?> values) {
        return values instanceof FrozenMap<?, ?>;
    }

    static <K> Set<K> changedKeys(Map<K, ?> values) {
        if (values instanceof DeltaMap<?, ?> delta) return (Set<K>) delta.changedKeys();
        if (values instanceof FrozenMap<?, ?> frozen) return changedKeys((Map<K, ?>) frozen.raw());
        return Set.of();
    }

    /**
     * Returns every key changed between two related persistent-map versions. A single state transition may
     * create several nested deltas for the same map, for example while pruning multiple orders of one user.
     */
    static <K> Set<K> changedKeys(Map<K, ?> before, Map<K, ?> after) {
        if (before == after) return Set.of();
        NavigableMap<K, ?> rawBefore = (NavigableMap<K, ?>) rawUntyped(before);
        NavigableMap<K, ?> current = (NavigableMap<K, ?>) rawUntyped(after);
        if (rawBefore != null && current instanceof DeltaMap<?, ?>) {
            Set<K> changed = new TreeSet<>(rawBefore.comparator());
            while (current instanceof DeltaMap<?, ?> delta) {
                changed.addAll((Set<K>) delta.changedKeys());
                if (delta.base() == rawBefore) return Collections.unmodifiableSet(changed);
                current = (NavigableMap<K, ?>) delta.base();
            }
        }
        Set<K> changed = new TreeSet<>(rawBefore == null ? null : rawBefore.comparator());
        changed.addAll(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        return Collections.unmodifiableSet(changed);
    }

    static boolean isDirectDeltaOf(Map<?, ?> before, Map<?, ?> after) {
        if (before == after) return true;
        NavigableMap<?, ?> rawBefore = rawUntyped(before);
        NavigableMap<?, ?> rawAfter = rawUntyped(after);
        return rawAfter instanceof DeltaMap<?, ?> delta && delta.base() == rawBefore;
    }

    static boolean isDeltaDescendantOf(Map<?, ?> before, Map<?, ?> after) {
        if (before == after) return true;
        NavigableMap<?, ?> rawBefore = rawUntyped(before);
        NavigableMap<?, ?> current = rawUntyped(after);
        while (current instanceof DeltaMap<?, ?> delta) {
            if (delta.base() == rawBefore) return true;
            current = delta.base();
        }
        return false;
    }

    private static NavigableMap<?, ?> rawUntyped(Map<?, ?> values) {
        if (values instanceof FrozenMap<?, ?> frozen) return frozen.raw();
        return values instanceof NavigableMap<?, ?> navigable ? navigable : null;
    }

    private static <K, V> NavigableMap<K, V> raw(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?> frozen) {
            return (NavigableMap<K, V>) frozen.raw();
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
        private final DeltaMap<K, V> parent;
        private final TreeSet<K> changedKeys;
        private PersistentTreeMap<K, V> tree;

        private DeltaMap(PersistentTreeMap<K, V> tree,
                         DeltaMap<K, V> parent,
                         NavigableMap<K, V> base) {
            this.tree = tree;
            this.parent = parent;
            this.base = base;
            this.changedKeys = new TreeSet<>(tree.comparator());
        }

        private NavigableMap<K, V> base() {
            return base;
        }

        private DeltaMap<K, V> parent() {
            return parent;
        }

        private Set<K> changedKeys() {
            return Collections.unmodifiableSet(changedKeys);
        }

        @Override
        public V get(Object key) { return tree.get(key); }

        @Override
        public boolean containsKey(Object key) { return tree.containsKey(key); }

        @Override
        public int size() { return tree.size(); }

        @Override
        public V put(K key, V value) {
            if (key == null || value == null) throw new NullPointerException("state map does not allow null");
            V previous = tree.get(key);
            tree = tree.withPut(key, value);
            changedKeys.add(key);
            return previous;
        }

        @Override
        public V remove(Object key) {
            if (!tree.containsKey(key)) return null;
            V previous = tree.get(key);
            tree = tree.without(key);
            changedKeys.add((K) key);
            return previous;
        }

        @Override
        public Set<Entry<K, V>> entrySet() { return tree.entrySet(); }

        @Override
        public Comparator<? super K> comparator() { return tree.comparator(); }

        @Override
        public Entry<K, V> lowerEntry(K key) { return tree.lowerEntry(key); }

        @Override
        public K lowerKey(K key) { return tree.lowerKey(key); }

        @Override
        public Entry<K, V> floorEntry(K key) { return tree.floorEntry(key); }

        @Override
        public K floorKey(K key) { return tree.floorKey(key); }

        @Override
        public Entry<K, V> ceilingEntry(K key) { return tree.ceilingEntry(key); }

        @Override
        public K ceilingKey(K key) { return tree.ceilingKey(key); }

        @Override
        public Entry<K, V> higherEntry(K key) { return tree.higherEntry(key); }

        @Override
        public K higherKey(K key) { return tree.higherKey(key); }

        @Override
        public Entry<K, V> firstEntry() { return tree.firstEntry(); }

        @Override
        public Entry<K, V> lastEntry() { return tree.lastEntry(); }

        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        @Override
        public NavigableMap<K, V> descendingMap() { return tree.materialized().descendingMap(); }

        @Override
        public NavigableSet<K> navigableKeySet() { return tree.materialized().navigableKeySet(); }

        @Override
        public NavigableSet<K> descendingKeySet() { return tree.materialized().descendingKeySet(); }

        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return tree.materialized().subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return tree.materialized().headMap(toKey, inclusive);
        }

        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return tree.materialized().tailMap(fromKey, inclusive);
        }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return tree.materialized().subMap(fromKey, toKey); }

        @Override
        public SortedMap<K, V> headMap(K toKey) { return tree.materialized().headMap(toKey); }

        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return tree.materialized().tailMap(fromKey); }

        @Override
        public K firstKey() { return tree.firstKey(); }

        @Override
        public K lastKey() { return tree.lastKey(); }
    }

    private static final class PersistentTreeMap<K, V> extends AbstractMap<K, V>
            implements NavigableMap<K, V> {
        private final Node<K, V> root;
        private final Comparator<? super K> comparator;

        private PersistentTreeMap(Node<K, V> root, Comparator<? super K> comparator) {
            this.root = root;
            this.comparator = comparator;
        }

        private static <K, V> PersistentTreeMap<K, V> from(Map<K, V> source) {
            Comparator<? super K> comparator = source instanceof SortedMap<?, ?> sorted
                    ? ((SortedMap<K, V>) sorted).comparator() : null;
            PersistentTreeMap<K, V> result = new PersistentTreeMap<>(null, comparator);
            for (Entry<K, V> entry : source.entrySet()) {
                result = result.withPut(entry.getKey(), entry.getValue());
            }
            return result;
        }

        private PersistentTreeMap<K, V> withPut(K key, V value) {
            return new PersistentTreeMap<>(insert(root, key, value), comparator);
        }

        private PersistentTreeMap<K, V> without(Object key) {
            return new PersistentTreeMap<>(delete(root, (K) key), comparator);
        }

        private int compare(K left, K right) {
            return comparator == null
                    ? ((Comparable<? super K>) left).compareTo(right)
                    : comparator.compare(left, right);
        }

        private Node<K, V> insert(Node<K, V> node, K key, V value) {
            if (node == null) return new Node<>(key, value, null, null);
            int comparison = compare(key, node.key);
            if (comparison == 0) return new Node<>(key, value, node.left, node.right);
            if (comparison < 0) {
                return balance(new Node<>(node.key, node.value, insert(node.left, key, value), node.right));
            }
            return balance(new Node<>(node.key, node.value, node.left, insert(node.right, key, value)));
        }

        private Node<K, V> delete(Node<K, V> node, K key) {
            if (node == null) return null;
            int comparison = compare(key, node.key);
            if (comparison < 0) return balance(new Node<>(node.key, node.value, delete(node.left, key), node.right));
            if (comparison > 0) return balance(new Node<>(node.key, node.value, node.left, delete(node.right, key)));
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            Node<K, V> successor = minimum(node.right);
            return balance(new Node<>(successor.key, successor.value, node.left, deleteMinimum(node.right)));
        }

        private static <K, V> Node<K, V> deleteMinimum(Node<K, V> node) {
            if (node.left == null) return node.right;
            return balance(new Node<>(node.key, node.value, deleteMinimum(node.left), node.right));
        }

        private static <K, V> Node<K, V> minimum(Node<K, V> node) {
            Node<K, V> current = node;
            while (current.left != null) current = current.left;
            return current;
        }

        private static <K, V> Node<K, V> balance(Node<K, V> node) {
            int factor = height(node.left) - height(node.right);
            if (factor > 1) {
                if (height(node.left.left) < height(node.left.right)) {
                    Node<K, V> left = rotateLeft(node.left);
                    node = new Node<>(node.key, node.value, left, node.right);
                }
                return rotateRight(node);
            }
            if (factor < -1) {
                if (height(node.right.right) < height(node.right.left)) {
                    Node<K, V> right = rotateRight(node.right);
                    node = new Node<>(node.key, node.value, node.left, right);
                }
                return rotateLeft(node);
            }
            return node;
        }

        private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
            Node<K, V> right = node.right;
            Node<K, V> moved = right.left;
            return new Node<>(right.key, right.value,
                    new Node<>(node.key, node.value, node.left, moved), right.right);
        }

        private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
            Node<K, V> left = node.left;
            Node<K, V> moved = left.right;
            return new Node<>(left.key, left.value,
                    left.left, new Node<>(node.key, node.value, moved, node.right));
        }

        private static int height(Node<?, ?> node) { return node == null ? 0 : node.height; }

        private static int size(Node<?, ?> node) { return node == null ? 0 : node.size; }

        private NavigableMap<K, V> materialized() {
            TreeMap<K, V> values = new TreeMap<>(comparator);
            values.putAll(this);
            return values;
        }

        private Entry<K, V> entry(Node<K, V> node) {
            return node == null ? null : new SimpleImmutableEntry<>(node.key, node.value);
        }

        private Node<K, V> seek(K key, boolean lower, boolean inclusive) {
            Node<K, V> current = root;
            Node<K, V> candidate = null;
            while (current != null) {
                int comparison = compare(key, current.key);
                boolean take = lower
                        ? comparison > 0 || (inclusive && comparison == 0)
                        : comparison < 0 || (inclusive && comparison == 0);
                if (take) {
                    candidate = current;
                    current = lower ? current.right : current.left;
                } else {
                    current = lower ? current.left : current.right;
                }
            }
            return candidate;
        }

        @Override
        public int size() { return size(root); }

        @Override
        public boolean isEmpty() { return root == null; }

        @Override
        public boolean containsKey(Object key) { return find((K) key) != null; }

        @Override
        public boolean containsValue(Object value) {
            for (Entry<K, V> entry : entrySet()) {
                if (java.util.Objects.equals(entry.getValue(), value)) return true;
            }
            return false;
        }

        @Override
        public V get(Object key) {
            Node<K, V> node = find((K) key);
            return node == null ? null : node.value;
        }

        private Node<K, V> find(K key) {
            Node<K, V> current = root;
            while (current != null) {
                int comparison = compare(key, current.key);
                if (comparison == 0) return current;
                current = comparison < 0 ? current.left : current.right;
            }
            return null;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<K, V>> iterator() {
                    return new Iterator<>() {
                        private final Deque<Node<K, V>> stack = initialize();

                        private Deque<Node<K, V>> initialize() {
                            Deque<Node<K, V>> values = new ArrayDeque<>();
                            pushLeft(root, values);
                            return values;
                        }

                        private void pushLeft(Node<K, V> node, Deque<Node<K, V>> values) {
                            Node<K, V> current = node;
                            while (current != null) {
                                values.push(current);
                                current = current.left;
                            }
                        }

                        @Override
                        public boolean hasNext() { return !stack.isEmpty(); }

                        @Override
                        public Entry<K, V> next() {
                            if (stack.isEmpty()) throw new java.util.NoSuchElementException();
                            Node<K, V> node = stack.pop();
                            pushLeft(node.right, stack);
                            return new SimpleImmutableEntry<>(node.key, node.value);
                        }
                    };
                }

                @Override
                public int size() { return PersistentTreeMap.this.size(); }
            };
        }

        @Override
        public Comparator<? super K> comparator() { return comparator; }

        @Override
        public Entry<K, V> lowerEntry(K key) { return entry(seek(key, true, false)); }

        @Override
        public K lowerKey(K key) { return keyOf(lowerEntry(key)); }

        @Override
        public Entry<K, V> floorEntry(K key) { return entry(seek(key, true, true)); }

        @Override
        public K floorKey(K key) { return keyOf(floorEntry(key)); }

        @Override
        public Entry<K, V> ceilingEntry(K key) { return entry(seek(key, false, true)); }

        @Override
        public K ceilingKey(K key) { return keyOf(ceilingEntry(key)); }

        @Override
        public Entry<K, V> higherEntry(K key) { return entry(seek(key, false, false)); }

        @Override
        public K higherKey(K key) { return keyOf(higherEntry(key)); }

        private K keyOf(Entry<K, V> entry) { return entry == null ? null : entry.getKey(); }

        @Override
        public Entry<K, V> firstEntry() { return entry(root == null ? null : minimum(root)); }

        @Override
        public Entry<K, V> lastEntry() {
            if (root == null) return null;
            Node<K, V> current = root;
            while (current.right != null) current = current.right;
            return entry(current);
        }

        @Override
        public K firstKey() {
            Entry<K, V> entry = firstEntry();
            if (entry == null) throw new java.util.NoSuchElementException();
            return entry.getKey();
        }

        @Override
        public K lastKey() {
            Entry<K, V> entry = lastEntry();
            if (entry == null) throw new java.util.NoSuchElementException();
            return entry.getKey();
        }

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
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return materialized().headMap(toKey, inclusive);
        }

        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return materialized().tailMap(fromKey, inclusive);
        }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return materialized().subMap(fromKey, toKey); }

        @Override
        public SortedMap<K, V> headMap(K toKey) { return materialized().headMap(toKey); }

        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return materialized().tailMap(fromKey); }

        private static final class Node<K, V> {
            private final K key;
            private final V value;
            private final Node<K, V> left;
            private final Node<K, V> right;
            private final int height;
            private final int size;

            private Node(K key, V value, Node<K, V> left, Node<K, V> right) {
                this.key = key;
                this.value = value;
                this.left = left;
                this.right = right;
                this.height = 1 + Math.max(height(left), height(right));
                this.size = 1 + size(left) + size(right);
            }

        }
    }
}
