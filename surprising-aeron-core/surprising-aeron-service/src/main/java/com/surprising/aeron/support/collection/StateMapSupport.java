package com.surprising.aeron.support.collection;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 冷状态的有序只读 Map 入口。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StateMapSupport {
    private static final NavigableMap<?, ?> EMPTY_SORTED = new FrozenMap<>(new TreeMap<>());

    /** 复用已冻结视图。 */
    @SuppressWarnings("unchecked")
    public static <K, V> NavigableMap<K, V> freezeSorted(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?>) return (NavigableMap<K, V>) values;
        if (values.isEmpty()) return (NavigableMap<K, V>) EMPTY_SORTED;
        NavigableMap<K, V> sorted = values instanceof NavigableMap<?, ?> navigable
                ? (NavigableMap<K, V>) navigable : new TreeMap<>(values);
        return new FrozenMap<>(sorted);
    }

    /** 识别已由本入口冻结的冷状态，供构造器复用已校验的表。 */
    public static boolean isFrozen(Map<?, ?> values) {
        return values instanceof FrozenMap<?, ?>;
    }

    /** 保留原表身份的只读入口外壳。 */
    private static final class FrozenMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V> {
        /** 禁止从此包装入口修改的有序委托，底层数据仍来自 raw。 */
        private final NavigableMap<K, V> delegate;

        /** 保存原有序表，并创建禁止从本入口修改的委托视图。 */
        private FrozenMap(NavigableMap<K, V> raw) {
            this.delegate = Collections.unmodifiableNavigableMap(raw);
        }

        /** 通过只读委托返回当前条目数。 */
        @Override
        public int size() { return delegate.size(); }

        /** 通过只读委托检查是否为空。 */
        @Override
        public boolean isEmpty() { return delegate.isEmpty(); }

        /** 通过只读委托检查键存在性。 */
        @Override
        public boolean containsKey(Object key) { return delegate.containsKey(key); }

        /** 通过只读委托按值比较，可能遍历全部条目。 */
        @Override
        public boolean containsValue(Object value) { return delegate.containsValue(value); }

        /** 通过只读委托按键取业务值，不复制该值。 */
        @Override
        public V get(Object key) { return delegate.get(key); }

        /** 取得不能从此入口修改的条目集合。 */
        @Override
        public Set<Entry<K, V>> entrySet() { return delegate.entrySet(); }

        /** 取得不能从此入口修改的有序键集合。 */
        @Override
        public NavigableSet<K> keySet() { return delegate.navigableKeySet(); }

        /** 沿用底层比较器，null 表示自然顺序。 */
        @Override
        public Comparator<? super K> comparator() { return delegate.comparator(); }

        /** 通过只读委托取得严格小于给定键的最大条目，缺失返回 null。 */
        @Override
        public Entry<K, V> lowerEntry(K key) { return delegate.lowerEntry(key); }

        /** 通过只读委托取得严格小于给定键的最大键，缺失返回 null。 */
        @Override
        public K lowerKey(K key) { return delegate.lowerKey(key); }

        /** 通过只读委托取得不大于给定键的最大条目，缺失返回 null。 */
        @Override
        public Entry<K, V> floorEntry(K key) { return delegate.floorEntry(key); }

        /** 通过只读委托取得不大于给定键的最大键，缺失返回 null。 */
        @Override
        public K floorKey(K key) { return delegate.floorKey(key); }

        /** 通过只读委托取得不小于给定键的最小条目，缺失返回 null。 */
        @Override
        public Entry<K, V> ceilingEntry(K key) { return delegate.ceilingEntry(key); }

        /** 通过只读委托取得不小于给定键的最小键，缺失返回 null。 */
        @Override
        public K ceilingKey(K key) { return delegate.ceilingKey(key); }

        /** 通过只读委托取得严格大于给定键的最小条目，缺失返回 null。 */
        @Override
        public Entry<K, V> higherEntry(K key) { return delegate.higherEntry(key); }

        /** 通过只读委托取得严格大于给定键的最小键，缺失返回 null。 */
        @Override
        public K higherKey(K key) { return delegate.higherKey(key); }

        /** 通过只读委托取得最小条目，空表返回 null。 */
        @Override
        public Entry<K, V> firstEntry() { return delegate.firstEntry(); }

        /** 通过只读委托取得最大条目，空表返回 null。 */
        @Override
        public Entry<K, V> lastEntry() { return delegate.lastEntry(); }

        /** 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。 */
        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /** 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。 */
        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /** 取得反向键序的 Map。 */
        @Override
        public NavigableMap<K, V> descendingMap() { return delegate.descendingMap(); }

        /** 取得正向有序键集合。 */
        @Override
        public NavigableSet<K> navigableKeySet() { return delegate.navigableKeySet(); }

        /** 取得反向有序键集合。 */
        @Override
        public NavigableSet<K> descendingKeySet() { return delegate.descendingKeySet(); }

        /** 取得指定双端点范围，两个布尔参数分别决定是否包含边界。 */
        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return delegate.subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        /** 取得上界之前的范围，inclusive 决定是否包含上界。 */
        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) { return delegate.headMap(toKey, inclusive); }

        /** 取得下界之后的范围，inclusive 决定是否包含下界。 */
        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) { return delegate.tailMap(fromKey, inclusive); }

        /** 取得包含下界、排除上界的范围。 */
        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return delegate.subMap(fromKey, toKey); }

        /** 取得严格小于上界的范围。 */
        @Override
        public SortedMap<K, V> headMap(K toKey) { return delegate.headMap(toKey); }

        /** 取得不小于下界的范围。 */
        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return delegate.tailMap(fromKey); }

        /** 取得当前排序中的最小键。 */
        @Override
        public K firstKey() { return delegate.firstKey(); }

        /** 取得当前排序中的最大键。 */
        @Override
        public K lastKey() { return delegate.lastKey(); }
    }
}
