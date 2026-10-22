package com.surprising.aeron.service.snapshot.state;

import java.lang.ref.WeakReference;
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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 状态快照值使用的有序 Map、路径复制增量和延迟取值工具。普通增量共享不可变树节点并记录本代变化，延迟增量按键缓存加载结果，FrozenMap 只封闭本入口写操作。原有序 Map、借入集合和业务键值没有统一深拷贝保证，发布后必须遵守输入稳定与所有权约定；这些状态表示不替代 Account Lane 的权威账户。
 * 构造约定：工具类不创建实例；各 Map 保存的是状态表示，不新增业务权威数据。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings("unchecked")
public final class StateMapSupport {

    /** 各状态快照共用的只读空有序 Map。 */
    private static final NavigableMap<?, ?> EMPTY_SORTED =
            new FrozenMap<>(new TreeMap<>());

    /**
     * 已有 FrozenMap 直接复用，空表共用只读空表；普通无序 Map 复制为 TreeMap，已有 NavigableMap 则直接借用。非空增量表先冻结血缘再套只读入口，未深拷贝键和值；调用方必须停止通过原 Map 引用修改数据。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    public static <K, V> NavigableMap<K, V> freezeSorted(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?>) {
            return (NavigableMap<K, V>) values;
        }
        if (values.isEmpty()) {
            return (NavigableMap<K, V>) EMPTY_SORTED;
        }
        // 本次要包装的有序表，或来源表的排序接口视图。
        NavigableMap<K, V> sorted = values instanceof NavigableMap<?, ?> /* 已实现有序导航接口的输入，可直接借用而不重新排序。 */ navigable
                ? (NavigableMap<K, V>) navigable : new TreeMap<>(values);
        if (sorted instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta) {
            ((DeltaMap<K, V>) delta).freezeLineage();
        }
        return new FrozenMap<>(sorted);
    }

    /**
     * 解开只读外壳并取得有序基表；已有增量表共享其不可变树根，否则从已排序基表构建平衡树。返回独立的可写增量入口，后续 put/remove 只替换本代树路径并记录变化键。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    public static <K, V> NavigableMap<K, V> delta(Map<K, V> values) {
        // 解包后的父版本或当前沿血缘取得的父表。
        NavigableMap<K, V> base = raw(values);
        if (base == null) {
            base = new TreeMap<>(values);
        }
        if (base instanceof DeltaMap<?, ?> /* 已确认原表为普通增量，可直接共享其当前不可变树。 */ previous) {
            // 恢复泛型后可直接读取不可变树根的上一增量。
            DeltaMap<K, V> typed = (DeltaMap<K, V>) previous;
            return new DeltaMap<>(typed.tree, typed);
        }
        return new DeltaMap<>(PersistentTreeMap.from(base), base);
    }

    /**
     * 要求变化键、仍存在键及加载器有效，且仍存在键属于变化键。无变化直接冻结复用基表；否则建立按键延迟取值的只读增量。集合未全部复制，调用方须保持输入稳定，并保证变化键排序与基表比较器一致。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     * @param changedKeys 本代所有变化键，包含新增、更新和删除
     * @param presentKeys 变化键中本代仍存在的子集
     * @param loader 按存在的变化键加载非空业务值的函数
     */
    static <K extends Comparable<? super K>, V> NavigableMap<K, V> deferredDelta(
            Map<K, V> values, Set<K> changedKeys, Set<K> presentKeys, Function<K, V> loader) {
        if (changedKeys == null || presentKeys == null || loader == null
                || !changedKeys.containsAll(presentKeys)) {
            throw new IllegalArgumentException("invalid deferred state delta");
        }
        if (changedKeys.isEmpty()) return freezeSorted(values);
        // 解包后的父版本或当前沿血缘取得的父表。
        NavigableMap<K, V> base = raw(values);
        if (base == null) base = new TreeMap<>(values);
        // 按已给顺序借用的有序变化集，或由普通集合按自然序复制出的集合。
        Set<K> orderedChangedKeys = changedKeys instanceof NavigableSet<?>
                ? changedKeys : Collections.unmodifiableSet(new TreeSet<>(changedKeys));
        return new LazyDeltaMap<>(base, orderedChangedKeys, presentKeys, loader, true);
    }

    /**
     * 识别直接或经 FrozenMap 包装的普通、延迟增量表，不把一般 Map 的内容相等当作增量关系。
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    public static boolean isDelta(Map<?, ?> values) {
        if (values instanceof DeltaMap<?, ?> || values instanceof LazyDeltaMap<?, ?>) return true;
        return values instanceof FrozenMap<?, ?> /* 用于递归解包的只读 Map 外壳。 */ frozen && isDelta(frozen.raw());
    }

    /**
     * 识别延迟增量的创建标记，并递归解开 FrozenMap；该标记不会随所有值已经解析而自动变成 false。
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    public static boolean isDeferred(Map<?, ?> values) {
        if (values instanceof LazyDeltaMap<?, ?> /* 已确认是延迟增量的输入表。 */ lazy) return lazy.deferred();
        return values instanceof FrozenMap<?, ?> /* 用于递归解包的只读 Map 外壳。 */ frozen && isDeferred(frozen.raw());
    }

    /**
     * 同一对象、能找到增量祖先或内容相等都允许通过，先检查身份和血缘以避免无必要的全表 equals；三者都不满足则拒绝该在线状态变化。
     * @param before 作为祖先边界或对照基准的原状态 Map
     * @param after 需要检查血缘或汇总变化的后续状态 Map
     * @param name 拒绝时用于标明业务状态表的名称
     */
    public static void requireDeltaLineage(Map<?, ?> before, Map<?, ?> after, String name) {
        if (before == after || isDeltaDescendantOf(before, after)
                || java.util.Objects.equals(before, after)) return;
        throw new IllegalStateException("online runtime transition is not a delta: " + name);
    }

    /**
     * 只检查最外层是否本工具的 FrozenMap；不证明底层 Map 或其键值已被深度复制。
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    public static boolean isFrozen(Map<?, ?> values) {
        return values instanceof FrozenMap<?, ?>;
    }

    /**
     * 解开外壳后检查普通增量是否仍强引用父版本，仅供内存血缘检查，不适用于延迟增量。
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    static boolean retainsStrongDeltaBase(Map<?, ?> values) {
        // 解开 FrozenMap 后的真实有序表，没有有序结构时为空。
        NavigableMap<?, ?> raw = rawUntyped(values);
        return raw instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta && delta.retainsStrongBase();
    }

    /**
     * 取得当前增量代记录的变化键，普通增量冻结时可能已经合并尚未冻结的祖先变化；外壳递归解开，非增量返回空集，不执行两表内容对比。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    public static <K> Set<K> changedKeys(Map<K, ?> values) {
        if (values instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta) return (Set<K>) delta.changedKeys();
        if (values instanceof LazyDeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta) return (Set<K>) delta.changedKeys();
        if (values instanceof FrozenMap<?, ?> /* 用于递归解包的只读 Map 外壳。 */ frozen) return changedKeys((Map<K, ?>) frozen.raw());
        return Set.of();
    }

    /**
     * 优先沿可找到的增量血缘合并期间的变化键，包括同一业务动作内多层增量。若祖先弱引用已失效或无法证明关系，则遍历两表键并按当前值排除相等项；血缘路径保留曾变化后又改回的键，不保证仅含最终值不同的键。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param before 作为祖先边界或对照基准的原状态 Map
     * @param after 需要检查血缘或汇总变化的后续状态 Map
     */
    public static <K> Set<K> changedKeys(Map<K, ?> before, Map<K, ?> after) {
        if (before == after) return Set.of();
        // 解包后作为血缘终点比较的原有序表身份。
        NavigableMap<K, ?> rawBefore = (NavigableMap<K, ?>) rawUntyped(before);
        // 当前正在向上追溯的增量或基表。
        NavigableMap<K, ?> current = (NavigableMap<K, ?>) rawUntyped(after);
        if (rawBefore != null && (current instanceof DeltaMap<?, ?> || current instanceof LazyDeltaMap<?, ?>)) {
            // 已收集的有序变化键；无可用血缘时改为两表键并集后按值筛选。
            Set<K> changed = new TreeSet<>(rawBefore.comparator());
            while (current instanceof DeltaMap<?, ?> || current instanceof LazyDeltaMap<?, ?>) {
                // 解包后的父版本或当前沿血缘取得的父表。
                NavigableMap<K, ?> base;
                if (current instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta) {
                    changed.addAll((Set<K>) delta.changedKeys());
                    base = (NavigableMap<K, ?>) delta.base();
                } else {
                    // 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。
                    LazyDeltaMap<?, ?> delta = (LazyDeltaMap<?, ?>) current;
                    changed.addAll((Set<K>) delta.changedKeys());
                    base = (NavigableMap<K, ?>) delta.base();
                }
                if (base == rawBefore) return Collections.unmodifiableSet(changed);
                current = base;
            }
        }
        // 已收集的有序变化键；无可用血缘时改为两表键并集后按值筛选。
        Set<K> changed = new TreeSet<>(rawBefore == null ? null : rawBefore.comparator());
        changed.addAll(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(/* 当前变化或访问的状态键。 */ key -> java.util.Objects.equals(before.get(key), after.get(key)));
        return Collections.unmodifiableSet(changed);
    }

    /**
     * 同一对象直接成立，否则要求解开外壳后的父引用身份恰好等于 before；内容相等或更早祖先不算直接父版本。
     * @param before 作为祖先边界或对照基准的原状态 Map
     * @param after 需要检查血缘或汇总变化的后续状态 Map
     */
    public static boolean isDirectDeltaOf(Map<?, ?> before, Map<?, ?> after) {
        if (before == after) return true;
        // 解包后作为血缘终点比较的原有序表身份。
        NavigableMap<?, ?> rawBefore = rawUntyped(before);
        // 解包后需要检查直接父身份的结果表。
        NavigableMap<?, ?> rawAfter = rawUntyped(after);
        if (rawAfter instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta) return delta.base() == rawBefore;
        return rawAfter instanceof LazyDeltaMap<?, ?> /* 已确认是延迟增量的输入表。 */ lazy && lazy.base() == rawBefore;
    }

    /**
     * 沿普通或延迟增量的父引用寻找 before 的对象身份；弱血缘失效后可能无法证明祖先关系，本方法不退回全表值比较。
     * @param before 作为祖先边界或对照基准的原状态 Map
     * @param after 需要检查血缘或汇总变化的后续状态 Map
     */
    public static boolean isDeltaDescendantOf(Map<?, ?> before, Map<?, ?> after) {
        if (before == after) return true;
        // 解包后作为血缘终点比较的原有序表身份。
        NavigableMap<?, ?> rawBefore = rawUntyped(before);
        // 当前正在向祖先推进的状态表。
        NavigableMap<?, ?> current = rawUntyped(after);
        while (current instanceof DeltaMap<?, ?> || current instanceof LazyDeltaMap<?, ?>) {
            // 解包后的父版本或当前沿血缘取得的父表。
            NavigableMap<?, ?> base = current instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta
                    ? delta.base() : ((LazyDeltaMap<?, ?>) current).base();
            if (base == rawBefore) return true;
            current = base;
        }
        return false;
    }

    /**
     * 解开一层 FrozenMap，或返回已经是 NavigableMap 的输入；普通 Map 返回 null，不复制也不排序。
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    private static NavigableMap<?, ?> rawUntyped(Map<?, ?> values) {
        if (values instanceof FrozenMap<?, ?> /* 用于递归解包的只读 Map 外壳。 */ frozen) return frozen.raw();
        return values instanceof NavigableMap<?, ?> /* 已实现有序导航接口的输入，可直接借用而不重新排序。 */ navigable ? navigable : null;
    }

    /**
     * 带泛型的内部解包入口，只借出有序底层 Map；输入不是有序表时返回 null，由调用者决定是否复制。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     * @param values 需要冻结、查询增量性质或建立下一代的状态 Map
     */
    private static <K, V> NavigableMap<K, V> raw(Map<K, V> values) {
        if (values instanceof FrozenMap<?, ?> /* 用于递归解包的只读 Map 外壳。 */ frozen) {
            return (NavigableMap<K, V>) frozen.raw();
        }
        return values instanceof NavigableMap<?, ?> /* 已实现有序导航接口的输入，可直接借用而不重新排序。 */ navigable
                ? (NavigableMap<K, V>) navigable : null;
    }

    /**
     * 保留原表身份的只读入口外壳。阻止从本外壳和其只读视图修改，但借入原有序表及键值不深拷贝。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     */
    private static final class FrozenMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V> {
        /**  借入的原有序表，用来保留内部身份与增量血缘，不是深拷贝。 仅供本工具内部取得原 Map 身份和血缘，不复制底层数据。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PRIVATE)
        private final NavigableMap<K, V> raw;
        /** 禁止从此包装入口修改的有序委托，底层数据仍来自 raw。 */
        private final NavigableMap<K, V> delegate;

        /**
         * 保存原有序表，并创建禁止从本入口修改的委托视图；原引用及其中可变值仍由调用方负责保持稳定。
         * @param raw 需要借用并从当前入口封闭写操作的原有序表
         */
        private FrozenMap(NavigableMap<K, V> raw) {
            this.raw = raw;
            this.delegate = Collections.unmodifiableNavigableMap(raw);
        }

        /**
         * 通过只读委托返回当前条目数。
         */
        @Override
        public int size() { return delegate.size(); }

        /**
         * 通过只读委托检查是否为空。
         */
        @Override
        public boolean isEmpty() { return delegate.isEmpty(); }

        /**
         * 通过只读委托检查键存在性。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public boolean containsKey(Object key) { return delegate.containsKey(key); }

        /**
         * 通过只读委托按值比较，可能遍历全部条目。
         * @param value 当前插入、查询相等性或尝试设置的业务值
         */
        @Override
        public boolean containsValue(Object value) { return delegate.containsValue(value); }

        /**
         * 通过只读委托按键取业务值，不复制该值。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public V get(Object key) { return delegate.get(key); }

        /**
         * 取得不能从此入口修改的条目集合。
         */
        @Override
        public Set<Entry<K, V>> entrySet() { return delegate.entrySet(); }

        /**
         * 取得不能从此入口修改的有序键集合。
         */
        @Override
        public NavigableSet<K> keySet() { return delegate.navigableKeySet(); }

        /**
         * 沿用底层比较器，null 表示自然顺序。
         */
        @Override
        public Comparator<? super K> comparator() { return delegate.comparator(); }

        /**
         * 通过只读委托取得严格小于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> lowerEntry(K key) { return delegate.lowerEntry(key); }

        /**
         * 通过只读委托取得严格小于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K lowerKey(K key) { return delegate.lowerKey(key); }

        /**
         * 通过只读委托取得不大于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> floorEntry(K key) { return delegate.floorEntry(key); }

        /**
         * 通过只读委托取得不大于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K floorKey(K key) { return delegate.floorKey(key); }

        /**
         * 通过只读委托取得不小于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> ceilingEntry(K key) { return delegate.ceilingEntry(key); }

        /**
         * 通过只读委托取得不小于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K ceilingKey(K key) { return delegate.ceilingKey(key); }

        /**
         * 通过只读委托取得严格大于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> higherEntry(K key) { return delegate.higherEntry(key); }

        /**
         * 通过只读委托取得严格大于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K higherKey(K key) { return delegate.higherKey(key); }

        /**
         * 通过只读委托取得最小条目，空表返回 null。
         */
        @Override
        public Entry<K, V> firstEntry() { return delegate.firstEntry(); }

        /**
         * 通过只读委托取得最大条目，空表返回 null。
         */
        @Override
        public Entry<K, V> lastEntry() { return delegate.lastEntry(); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 取得反向键序的 Map。返回只读视图；不深拷贝其中业务值。
         */
        @Override
        public NavigableMap<K, V> descendingMap() { return delegate.descendingMap(); }

        /**
         * 取得正向有序键集合。返回只读视图；不深拷贝其中业务值。
         */
        @Override
        public NavigableSet<K> navigableKeySet() { return delegate.navigableKeySet(); }

        /**
         * 取得反向有序键集合。返回只读视图；不深拷贝其中业务值。
         */
        @Override
        public NavigableSet<K> descendingKeySet() { return delegate.descendingKeySet(); }

        /**
         * 取得指定双端点范围，两个布尔参数分别决定是否包含边界。返回只读视图；不深拷贝其中业务值。
         * @param fromKey 范围起点键
         * @param fromInclusive 是否包含范围起点
         * @param toKey 范围终点键
         * @param toInclusive 是否包含范围终点
         */
        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return delegate.subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        /**
         * 取得上界之前的范围，inclusive 决定是否包含上界。返回只读视图；不深拷贝其中业务值。
         * @param toKey 范围终点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) { return delegate.headMap(toKey, inclusive); }

        /**
         * 取得下界之后的范围，inclusive 决定是否包含下界。返回只读视图；不深拷贝其中业务值。
         * @param fromKey 范围起点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) { return delegate.tailMap(fromKey, inclusive); }

        /**
         * 取得包含下界、排除上界的范围。返回只读视图；不深拷贝其中业务值。
         * @param fromKey 范围起点键
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return delegate.subMap(fromKey, toKey); }

        /**
         * 取得严格小于上界的范围。返回只读视图；不深拷贝其中业务值。
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> headMap(K toKey) { return delegate.headMap(toKey); }

        /**
         * 取得不小于下界的范围。返回只读视图；不深拷贝其中业务值。
         * @param fromKey 范围起点键
         */
        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return delegate.tailMap(fromKey); }

        /**
         * 取得当前排序中的最小键；空表抛出 NoSuchElementException。
         */
        @Override
        public K firstKey() { return delegate.firstKey(); }

        /**
         * 取得当前排序中的最大键；空表抛出 NoSuchElementException。
         */
        @Override
        public K lastKey() { return delegate.lastKey(); }
    }

    /**
     * 稳定基表上的只读延迟增量，按变化键存在位和加载槽工作；单键加载原子协调，完整表物化可重复构建。调用方负责输入排序、集合和加载源的本代稳定性。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     */
    private static final class LazyDeltaMap<K extends Comparable<? super K>, V>
            extends AbstractMap<K, V> implements NavigableMap<K, V> {
        /**  本延迟代强引用的稳定基表。 返回此代直接借用的基表，不展开整张延迟表。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PRIVATE)
        private final NavigableMap<K, V> base;
        /**  创建时借入的变化键集合，数组另存其当时顺序。 返回创建时保存的变化键集合引用，不额外做只读复制。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PRIVATE)
        private final Set<K> changedKeys;
        /** 变化键中本代仍存在的键集合，构造时据此记录存在位。 */
        private final Set<K> presentKeys;
        /** 为本代仍存在的变化键加载非空业务值的函数，成功结果按键缓存。 */
        private final Function<K, V> loader;
        /**  创建方式标记，不随解析或物化完成自动变化。 返回此实例创建时的延迟标记，不统计还有多少键未加载。 */
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PRIVATE)
        private final boolean deferred;
        /**  由基表大小及变化键存在性预先计算的结果条目数。 直接返回构造时按存在位算出的条目数，不展开业务值。 */
        @Accessors(fluent = true, chain = false)
        @Getter(onMethod_ = {@Override})
        private final int size;
        /** 构造时复制的变化键顺序数组，二分查找要求与基表比较器一致。 */
        private final Object[] changedKeysByIndex;
        /** 与变化键数组配对的本代存在位，false 表示删除。 */
        private final boolean[] presentByIndex;
        /** 每个变化键的原子解析槽，null 未加载、RESERVING 加载中、其余为成功业务值。 */
        private final AtomicReferenceArray<Object> resolvedValues;
        /** 完整只读结果 Map 的跨线程发布缓存，并发时允许重复构建等价结果。 */
        private volatile NavigableMap<K, V> materialized;

        /**
         * 保存基表、变化键和加载器，把变化键排入数组并预先计算存在标记和结果大小。只按存在性调整数量，尚不加载业务值；所借集合和基表须由调用方保持稳定。
         * @param base 本代直接父表或稳定有序基表，调用方须保持其数据不再修改
         * @param changedKeys 本代所有变化键，包含新增、更新和删除
         * @param presentKeys 变化键中本代仍存在的子集
         * @param loader 按存在的变化键加载非空业务值的函数
         * @param deferred 保存本实例延迟创建方式的标记
         */
        private LazyDeltaMap(NavigableMap<K, V> base, Set<K> changedKeys, Set<K> presentKeys,
                             Function<K, V> loader, boolean deferred) {
            this.base = base;
            this.changedKeys = changedKeys;
            this.presentKeys = presentKeys;
            this.loader = loader;
            this.deferred = deferred;
            changedKeysByIndex = changedKeys.toArray();
            presentByIndex = new boolean[changedKeysByIndex.length];
            resolvedValues = new AtomicReferenceArray<>(changedKeysByIndex.length);
            // 加入或删除键之后当前结果应有的条目数。
            int nextSize = base.size();
            for (/* 当前变化键在键、存在位和原子值数组中的共同下标。 */ int index = 0; index < changedKeysByIndex.length; index++) {
                // 当前变化或访问的状态键。
                K key = (K) changedKeysByIndex[index];
                // 当前变化键在基表中是否存在。
                boolean existed = base.containsKey(key);
                // 当前变化键在本代结果中是否仍存在。
                boolean exists = this.presentKeys.contains(key);
                presentByIndex[index] = exists;
                if (existed && !exists) nextSize--;
                else if (!existed && exists) nextSize++;
            }
            size = nextSize;
        }

        /**
         * 有缓存则复用；否则复制基表并依次应用变化键，对存在项按需解析，对删除项移除，最后发布只读完整表。并发读者可能各建一份等价 Map，单键值仍由 CAS 协调加载。
         */
        private NavigableMap<K, V> materialized() {
            // 已经发布的完整结果表，尚未生成时为空。
            NavigableMap<K, V> current = materialized;
            if (current != null) return current;
            // 复制基表后逐个应用变化的临时可变 TreeMap。
            TreeMap<K, V> next = new TreeMap<>(base.comparator());
            next.putAll(base);
            for (/* 当前变化键在键、存在位和原子值数组中的共同下标。 */ int index = 0; index < changedKeysByIndex.length; index++) {
                // 当前变化或访问的状态键。
                K key = (K) changedKeysByIndex[index];
                // 当前变化键解析出的业务值，删除项使用 null。
                V value = presentByIndex[index] ? resolvedValue(index, key) : null;
                if (value == null) next.remove(key); else next.put(key, value);
            }
            current = Collections.unmodifiableNavigableMap(next);
            materialized = current;
            return current;
        }

        /**
         * 对变化数组二分定位：未变化则读基表，已删除返回 null，仍存在才解析并缓存该键值，不因此展开整表。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public V get(Object key) {
            // 当前变化键在键、存在位和原子值数组中的共同下标。
            int index = changedIndex(key);
            if (index < 0) return base.get(key);
            return presentByIndex[index] ? resolvedValue(index, (K) key) : null;
        }

        /**
         * 优先检查变化键的存在位，其余读基表；不调用业务加载器。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public boolean containsKey(Object key) {
            // 当前变化键在键、存在位和原子值数组中的共同下标。
            int index = changedIndex(key);
            return index < 0 ? base.containsKey(key) : presentByIndex[index];
        }

        /** 单键加载正在进行的内部哨兵，不会作为业务值返回。 */
        private static final Object RESERVING = new Object();

        /**
         * 用原子槽争取单键加载权，成功后缓存非空业务值供所有读者复用；加载失败清空保留位后重新抛出，后续可重试。其他读者自旋等待，当前实现没有超时或同键递归检测。
         * @param index 变化键在原子缓存和存在位数组中的共同下标
         * @param key 当前查询、插入或删除使用的键
         */
        private V resolvedValue(int index, K key) {
            while (true) {
                // 当前原子槽内容，区分未加载、加载哨兵和成功值。
                Object current = resolvedValues.get(index);
                if (current != null && current != RESERVING) {
                    return (V) current;
                }
                if (current == null && resolvedValues.compareAndSet(index, null, RESERVING)) {
                    // 本线程取得加载权后生成的非空业务值。
                    Object loaded;
                    try {
                        loaded = java.util.Objects.requireNonNull(loader.apply(key),
                                "deferred state value is required");
                    } catch (/* 本次加载抛出的异常；清空加载哨兵后原样向外传递。 */ Throwable failure) {
                        resolvedValues.set(index, null);
                        throw failure;
                    }
                    resolvedValues.set(index, loaded);
                    return (V) loaded;
                }
                Thread.onSpinWait();
            }
        }

        /**
         * 按基表比较器或键自然顺序在变化键数组中二分查找，空键或未找到返回负一；输入数组必须按同一比较规则排序。
         * @param key 当前查询、插入或删除使用的键
         */
        private int changedIndex(Object key) {
            if (key == null) return -1;
            // 当前二分查找键，或搜索到的最近边界候选节点。
            K candidate = (K) key;
            // 当前基表或来源的键比较器，null 表示自然顺序。
            Comparator<? super K> comparator = base.comparator();
            // 二分查找尚未排除范围的左下标。
            int low = 0;
            // 二分查找尚未排除范围的右下标。
            int high = changedKeysByIndex.length - 1;
            while (low <= high) {
                // 当前二分范围中间下标。
                int middle = (low + high) >>> 1;
                // 变化键数组中用于本次比较的中间键。
                K current = (K) changedKeysByIndex[middle];
                // 当前目标键与节点或变化数组键的排序比较结果。
                int comparison = comparator == null
                        ? ((Comparable<? super K>) current).compareTo(candidate)
                        : comparator.compare(current, candidate);
                if (comparison < 0) low = middle + 1;
                else if (comparison > 0) high = middle - 1;
                else return middle;
            }
            return -1;
        }

        /**
         * 先物化完整只读表，再取得只读条目集合；与单键 get 的按需加载范围不同。
         */
        @Override
        public Set<Entry<K, V>> entrySet() { return materialized().entrySet(); }

        /**
         * 沿用基表比较器，null 表示键的自然顺序。
         */
        @Override
        public Comparator<? super K> comparator() { return base.comparator(); }

        /**
         * 先物化完整只读表，再取得严格小于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> lowerEntry(K key) { return materialized().lowerEntry(key); }

        /**
         * 先物化完整只读表，再取得严格小于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K lowerKey(K key) { return materialized().lowerKey(key); }

        /**
         * 先物化完整只读表，再取得不大于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> floorEntry(K key) { return materialized().floorEntry(key); }

        /**
         * 先物化完整只读表，再取得不大于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K floorKey(K key) { return materialized().floorKey(key); }

        /**
         * 先物化完整只读表，再取得不小于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> ceilingEntry(K key) { return materialized().ceilingEntry(key); }

        /**
         * 先物化完整只读表，再取得不小于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K ceilingKey(K key) { return materialized().ceilingKey(key); }

        /**
         * 先物化完整只读表，再取得严格大于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> higherEntry(K key) { return materialized().higherEntry(key); }

        /**
         * 先物化完整只读表，再取得严格大于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K higherKey(K key) { return materialized().higherKey(key); }

        /**
         * 先物化完整只读表，再取最小条目，空表返回 null。
         */
        @Override
        public Entry<K, V> firstEntry() { return materialized().firstEntry(); }

        /**
         * 先物化完整只读表，再取最大条目，空表返回 null。
         */
        @Override
        public Entry<K, V> lastEntry() { return materialized().lastEntry(); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 取得反向键序的 Map。返回物化表的只读视图。
         */
        @Override
        public NavigableMap<K, V> descendingMap() { return materialized().descendingMap(); }

        /**
         * 取得正向有序键集合。返回物化表的只读视图。
         */
        @Override
        public NavigableSet<K> navigableKeySet() { return materialized().navigableKeySet(); }

        /**
         * 取得反向有序键集合。返回物化表的只读视图。
         */
        @Override
        public NavigableSet<K> descendingKeySet() { return materialized().descendingKeySet(); }

        /**
         * 取得指定双端点范围，两个布尔参数分别决定是否包含边界。返回物化表的只读视图。
         * @param fromKey 范围起点键
         * @param fromInclusive 是否包含范围起点
         * @param toKey 范围终点键
         * @param toInclusive 是否包含范围终点
         */
        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return materialized().subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        /**
         * 取得上界之前的范围，inclusive 决定是否包含上界。返回物化表的只读视图。
         * @param toKey 范围终点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return materialized().headMap(toKey, inclusive);
        }

        /**
         * 取得下界之后的范围，inclusive 决定是否包含下界。返回物化表的只读视图。
         * @param fromKey 范围起点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return materialized().tailMap(fromKey, inclusive);
        }

        /**
         * 取得包含下界、排除上界的范围。返回物化表的只读视图。
         * @param fromKey 范围起点键
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return materialized().subMap(fromKey, toKey); }

        /**
         * 取得严格小于上界的范围。返回物化表的只读视图。
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> headMap(K toKey) { return materialized().headMap(toKey); }

        /**
         * 取得不小于下界的范围。返回物化表的只读视图。
         * @param fromKey 范围起点键
         */
        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return materialized().tailMap(fromKey); }

        /**
         * 先物化完整只读表，再取得最小键；空表抛出 NoSuchElementException。
         */
        @Override
        public K firstKey() { return materialized().firstKey(); }

        /**
         * 先物化完整只读表，再取得最大键；空表抛出 NoSuchElementException。
         */
        @Override
        public K lastKey() { return materialized().lastKey(); }
    }

    /**
     * 一个状态转换期间使用的可写增量入口，当前内容由不可变持久树表示。发布冻结后须放弃原可写引用；血缘冻结位不实现写保护。
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     */
    private static final class DeltaMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V> {
        /** 本代尚未冻结时的强父引用，冻结血缘后清空。 */
        private NavigableMap<K, V> base;
        /** 冻结后对可追溯祖先边界的弱引用，回收后允许血缘查询失去该路径。 */
        private WeakReference<NavigableMap<K, V>> lineageBase;
        /** 本代实际更新或删除的有序键集合，冻结时可合并未冻结祖先。 */
        private final TreeSet<K> changedKeys;
        /** 本代最新不可变树根表示，put/remove 以新树替换该引用。 */
        private PersistentTreeMap<K, V> tree;
        /** 血缘已经合并并转弱引用的标记，不是 put/remove 的写保护开关。 */
        private boolean frozen;

        /**
         * 保存当前不可变树根和直接父表，创建使用相同比较器的本代变化键集合。
         * @param tree 本代可共享的不可变有序树表示
         * @param base 本代直接父表或稳定有序基表，调用方须保持其数据不再修改
         */
        private DeltaMap(PersistentTreeMap<K, V> tree, NavigableMap<K, V> base) {
            this.tree = tree;
            this.base = base;
            this.changedKeys = new TreeSet<>(tree.comparator());
        }

        /**
         * 未冻结时返回强父引用；冻结后尝试弱血缘，父表已回收则返回当前树作为无法继续追溯的终点。
         */
        private NavigableMap<K, V> base() {
            // 先尝试强父引用，再尝试弱血缘引用得到的父表。
            NavigableMap<K, V> current = base;
            if (current != null) return current;
            current = lineageBase.get();
            return current == null ? tree : current;
        }

        /**
         * 合并连续未冻结祖先的变化键，以弱引用保留更早边界并释放本代强父引用。frozen 只表示血缘已整理，put/remove 不检查该位；调用方在发布只读外壳后须停止写原增量入口。
         */
        private void freezeLineage() {
            if (frozen) return;
            // 冻结过程中继续向上合并的祖先边界。
            NavigableMap<K, V> lineage = base;
            while (lineage instanceof DeltaMap<?, ?> /* 已确认类型的当前普通或延迟增量，用于查询变化键或血缘。 */ delta && !delta.frozen) {
                // 当前尚未冻结的祖先增量，合并其变化键后继续向上追溯。
                DeltaMap<K, V> previous = (DeltaMap<K, V>) delta;
                changedKeys.addAll(previous.changedKeys);
                lineage = previous.base;
            }
            lineageBase = new WeakReference<>(lineage);
            base = null;
            frozen = true;
        }

        /**
         * 返回本代是否仍持有非空强父引用。
         */
        private boolean retainsStrongBase() {
            return base != null;
        }

        /**
         * 返回本代已记录变化键的只读包装，不复制集合。
         */
        private Set<K> changedKeys() {
            return Collections.unmodifiableSet(changedKeys);
        }

        /**
         * 在当前不可变树根直接查键，不沿父 Map 一层层回查。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public V get(Object key) { return tree.get(key); }

        /**
         * 通过当前树根查键是否存在。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public boolean containsKey(Object key) { return tree.containsKey(key); }

        /**
         * 返回当前树根记录的条目数。
         */
        @Override
        public int size() { return tree.size(); }

        /**
         * 拒绝空键和值；原值相等时直接返回且不登记变化，否则按路径复制产生新树根并登记该键，返回原值。冻结血缘标记本身不拦截此操作。
         * @param key 当前查询、插入或删除使用的键
         * @param value 当前插入、查询相等性或尝试设置的业务值
         */
        @Override
        public V put(K key, V value) {
            if (key == null || value == null) throw new NullPointerException("state map does not allow null");
            // 上一增量版本或当前写入前的原业务值。
            V previous = tree.get(key);
            if (java.util.Objects.equals(previous, value)) return previous;
            tree = tree.withPut(key, value, previous != null);
            changedKeys.add(key);
            return previous;
        }

        /**
         * 键不存在则不改变本代；存在时按路径复制删除并登记变化键，返回原值。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public V remove(Object key) {
            if (!tree.containsKey(key)) return null;
            // 上一增量版本或当前写入前的原业务值。
            V previous = tree.get(key);
            tree = tree.without(key);
            changedKeys.add((K) key);
            return previous;
        }

        /**
         * 返回当前树根的有序条目集合，迭代不能修改节点值。
         */
        @Override
        public Set<Entry<K, V>> entrySet() { return tree.entrySet(); }

        /**
         * 返回当前树使用的比较器，null 表示自然顺序。
         */
        @Override
        public Comparator<? super K> comparator() { return tree.comparator(); }

        /**
         * 在当前持久树中取得严格小于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> lowerEntry(K key) { return tree.lowerEntry(key); }

        /**
         * 在当前持久树中取得严格小于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K lowerKey(K key) { return tree.lowerKey(key); }

        /**
         * 在当前持久树中取得不大于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> floorEntry(K key) { return tree.floorEntry(key); }

        /**
         * 在当前持久树中取得不大于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K floorKey(K key) { return tree.floorKey(key); }

        /**
         * 在当前持久树中取得不小于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> ceilingEntry(K key) { return tree.ceilingEntry(key); }

        /**
         * 在当前持久树中取得不小于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K ceilingKey(K key) { return tree.ceilingKey(key); }

        /**
         * 在当前持久树中取得严格大于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> higherEntry(K key) { return tree.higherEntry(key); }

        /**
         * 在当前持久树中取得严格大于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K higherKey(K key) { return tree.higherKey(key); }

        /**
         * 直接从当前树取得最小只读条目，空表返回 null。
         */
        @Override
        public Entry<K, V> firstEntry() { return tree.firstEntry(); }

        /**
         * 直接从当前树取得最大只读条目，空表返回 null。
         */
        @Override
        public Entry<K, V> lastEntry() { return tree.lastEntry(); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 取得反向键序的 Map。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         */
        @Override
        public NavigableMap<K, V> descendingMap() { return tree.materialized().descendingMap(); }

        /**
         * 取得正向有序键集合。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         */
        @Override
        public NavigableSet<K> navigableKeySet() { return tree.materialized().navigableKeySet(); }

        /**
         * 取得反向有序键集合。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         */
        @Override
        public NavigableSet<K> descendingKeySet() { return tree.materialized().descendingKeySet(); }

        /**
         * 取得指定双端点范围，两个布尔参数分别决定是否包含边界。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         * @param fromKey 范围起点键
         * @param fromInclusive 是否包含范围起点
         * @param toKey 范围终点键
         * @param toInclusive 是否包含范围终点
         */
        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return tree.materialized().subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        /**
         * 取得上界之前的范围，inclusive 决定是否包含上界。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         * @param toKey 范围终点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return tree.materialized().headMap(toKey, inclusive);
        }

        /**
         * 取得下界之后的范围，inclusive 决定是否包含下界。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         * @param fromKey 范围起点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return tree.materialized().tailMap(fromKey, inclusive);
        }

        /**
         * 取得包含下界、排除上界的范围。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         * @param fromKey 范围起点键
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return tree.materialized().subMap(fromKey, toKey); }

        /**
         * 取得严格小于上界的范围。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> headMap(K toKey) { return tree.materialized().headMap(toKey); }

        /**
         * 取得不小于下界的范围。先复制为临时 TreeMap，再返回其可变视图；修改不写回本代树。
         * @param fromKey 范围起点键
         */
        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return tree.materialized().tailMap(fromKey); }

        /**
         * 取得当前排序中的最小键；空表抛出 NoSuchElementException。
         */
        @Override
        public K firstKey() { return tree.firstKey(); }

        /**
         * 取得当前排序中的最大键；空表抛出 NoSuchElementException。
         */
        @Override
        public K lastKey() { return tree.lastKey(); }
    }

    /**
     * 按路径复制的不可变平衡搜索树。新版本共享未变节点，直接查询和中序遍历不物化；范围与反序接口按当前实现返回独立临时 TreeMap 的可变视图。
     * 构造约定：固定保存一个不可变根节点、排序规则和大小，后续变化产生新树版本。
     * 参数 root：此树版本固定根节点，空表可为空
     * 参数 comparator：键排序规则，为空时使用自然顺序
     * 参数 size：本次构造的树或子树所含条目数
     * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
     * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class PersistentTreeMap<K, V> extends AbstractMap<K, V>
            implements NavigableMap<K, V> {
        /** 此树版本固定的不可变根节点，可与其他版本共享未变子树。 */
        private final Node<K, V> root;
        /**  此版本固定的键比较器，null 使用自然顺序。 返回本树固定比较器，null 表示自然顺序。 */
        @Accessors(fluent = true, chain = false)
        @Getter(onMethod_ = {@Override})
        private final Comparator<? super K> comparator;
        /**  此树版本固定的条目数。 返回本树版本创建时固定的条目数。 */
        @Accessors(fluent = true, chain = false)
        @Getter(onMethod_ = {@Override})
        private final int size;

        /**
         * 按已排序且调用期间稳定的来源迭代器构造平衡树，继承 SortedMap 比较器；本方法本身不重排普通 Map。条目过多或过少时报大小变化，不提供并发快照锁。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param source 已按键序迭代且调用期间稳定的来源 Map
         */
        private static <K, V> PersistentTreeMap<K, V> from(Map<K, V> source) {
            // 当前基表或来源的键比较器，null 表示自然顺序。
            Comparator<? super K> comparator = source instanceof SortedMap<?, ?> /* 本次要包装的有序表，或来源表的排序接口视图。 */ sorted
                    ? ((SortedMap<K, V>) sorted).comparator() : null;
            // 构造平衡树时按来源键序读取的条目迭代器。
            Iterator<Entry<K, V>> entries = source.entrySet().iterator();
            // 从来源全部条目构造出的平衡树根。
            Node<K, V> root = balanced(entries, source.size());
            if (entries.hasNext()) throw new IllegalStateException("state map size changed during projection");
            return new PersistentTreeMap<>(root, comparator, source.size());
        }

        /**
         * 按中序消费指定数量条目，递归构造左右子树，避免逐项插入；迭代器不足时拒绝。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param entries 中序消费的有序条目迭代器
         * @param size 本次构造的树或子树所含条目数
         */
        private static <K, V> Node<K, V> balanced(Iterator<Entry<K, V>> entries, int size) {
            if (size == 0) return null;
            // 按当前条目数一半分给左子树的数量。
            int leftSize = size >>> 1;
            // 本次新建或旋转后的左子树根。
            Node<K, V> left = balanced(entries, leftSize);
            if (!entries.hasNext()) throw new IllegalStateException("state map size changed during projection");
            // 当前消费、遍历或查询得到的键值条目。
            Entry<K, V> entry = entries.next();
            // 本次新建或旋转后的右子树根。
            Node<K, V> right = balanced(entries, size - leftSize - 1);
            return new Node<>(entry.getKey(), entry.getValue(), left, right);
        }

        /**
         * 复制插入路径并重平衡，根身份未变则复用原树；新增键时精确增加大小，已存在标记由调用者提供。
         * @param key 当前查询、插入或删除使用的键
         * @param value 当前插入、查询相等性或尝试设置的业务值
         * @param existed 调用者已确认该键原先是否存在，用于确定新树大小
         */
        private PersistentTreeMap<K, V> withPut(K key, V value, boolean existed) {
            // 插入或删除路径复制后得到的新树根。
            Node<K, V> next = insert(root, key, value);
            if (next == root) return this;
            // 加入或删除键之后当前结果应有的条目数。
            int nextSize = existed ? size : Math.incrementExact(size);
            return new PersistentTreeMap<>(next, comparator, nextSize);
        }

        /**
         * 复制删除路径并重平衡，根未变则复用原树，实际删除后精确减少大小。
         * @param key 当前查询、插入或删除使用的键
         */
        private PersistentTreeMap<K, V> without(Object key) {
            // 插入或删除路径复制后得到的新树根。
            Node<K, V> next = delete(root, (K) key);
            return next == root ? this : new PersistentTreeMap<>(next, comparator, Math.decrementExact(size));
        }

        /**
         * 优先用显式比较器，否则按左键自然顺序比较两键。
         * @param left 本次排序比较的左侧键
         * @param right 本次排序比较的右侧键
         */
        private int compare(K left, K right) {
            return comparator == null
                    ? ((Comparable<? super K>) left).compareTo(right)
                    : comparator.compare(left, right);
        }

        /**
         * 沿二叉搜索路径插入或替换值，相等值复用原节点；只复制发生变化的路径，再按高度修复平衡，未经过的子树仍共享。
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         * @param key 当前查询、插入或删除使用的键
         * @param value 当前插入、查询相等性或尝试设置的业务值
         */
        private Node<K, V> insert(Node<K, V> node, K key, V value) {
            if (node == null) return new Node<>(key, value, null, null);
            // 当前目标键与节点或变化数组键的排序比较结果。
            int comparison = compare(key, node.key);
            if (comparison == 0) {
                return java.util.Objects.equals(value, node.value)
                        ? node : new Node<>(key, value, node.left, node.right);
            }
            if (comparison < 0) {
                // 本次新建或旋转后的左子树根。
                Node<K, V> left = insert(node.left, key, value);
                return left == node.left ? node
                        : balance(new Node<>(node.key, node.value, left, node.right));
            }
            // 本次新建或旋转后的右子树根。
            Node<K, V> right = insert(node.right, key, value);
            return right == node.right ? node
                    : balance(new Node<>(node.key, node.value, node.left, right));
        }

        /**
         * 沿比较路径定位删除键，未变化分支复用；双子节点时用右子树最小节点替换，再删除该后继并重平衡。
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         * @param key 当前查询、插入或删除使用的键
         */
        private Node<K, V> delete(Node<K, V> node, K key) {
            if (node == null) return null;
            // 当前目标键与节点或变化数组键的排序比较结果。
            int comparison = compare(key, node.key);
            if (comparison < 0) {
                // 本次新建或旋转后的左子树根。
                Node<K, V> left = delete(node.left, key);
                return left == node.left ? node
                        : balance(new Node<>(node.key, node.value, left, node.right));
            }
            if (comparison > 0) {
                // 本次新建或旋转后的右子树根。
                Node<K, V> right = delete(node.right, key);
                return right == node.right ? node
                        : balance(new Node<>(node.key, node.value, node.left, right));
            }
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            // 删除双子节点时，从右子树取得的最小后继节点。
            Node<K, V> successor = minimum(node.right);
            return balance(new Node<>(successor.key, successor.value, node.left, deleteMinimum(node.right)));
        }

        /**
         * 删除非空子树最左节点，逐层复制返回路径并修复平衡。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private static <K, V> Node<K, V> deleteMinimum(Node<K, V> node) {
            if (node.left == null) return node.right;
            return balance(new Node<>(node.key, node.value, deleteMinimum(node.left), node.right));
        }

        /**
         * 沿非空子树左侧找到最小键节点，不改变树。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private static <K, V> Node<K, V> minimum(Node<K, V> node) {
            // 沿左孩子逐步推进、最终找到最小键的节点。
            Node<K, V> current = node;
            while (current.left != null) current = current.left;
            return current;
        }

        /**
         * 比较左右高度差，必要时先对子树旋转再旋转根，返回高度平衡的新根；已经平衡则复用输入节点。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private static <K, V> Node<K, V> balance(Node<K, V> node) {
            // 当前节点左高度减右高度，决定是否需要旋转。
            int factor = height(node.left) - height(node.right);
            if (factor > 1) {
                if (height(node.left.left) < height(node.left.right)) {
                    // 本次新建或旋转后的左子树根。
                    Node<K, V> left = rotateLeft(node.left);
                    node = new Node<>(node.key, node.value, left, node.right);
                }
                return rotateRight(node);
            }
            if (factor < -1) {
                if (height(node.right.right) < height(node.right.left)) {
                    // 本次新建或旋转后的右子树根。
                    Node<K, V> right = rotateRight(node.right);
                    node = new Node<>(node.key, node.value, node.left, right);
                }
                return rotateLeft(node);
            }
            return node;
        }

        /**
         * 以右孩子为新根向左旋转，重建受影响两个节点并共享其余子树。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
            // 本次新建或旋转后的右子树根。
            Node<K, V> right = node.right;
            // 旋转时从一侧孩子移到另一子树的中间分支。
            Node<K, V> moved = right.left;
            return new Node<>(right.key, right.value,
                    new Node<>(node.key, node.value, node.left, moved), right.right);
        }

        /**
         * 以左孩子为新根向右旋转，重建受影响两个节点并共享其余子树。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
            // 本次新建或旋转后的左子树根。
            Node<K, V> left = node.left;
            // 旋转时从一侧孩子移到另一子树的中间分支。
            Node<K, V> moved = left.right;
            return new Node<>(left.key, left.value,
                    left.left, new Node<>(node.key, node.value, moved, node.right));
        }

        /**
         * 空节点高度为零，非空直接使用节点缓存高度。
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private static int height(Node<?, ?> node) { return node == null ? 0 : node.height; }

        /**
         * 把当前树完整复制为新的可变 TreeMap，不缓存该副本；修改副本不会写回原不可变根。
         */
        private NavigableMap<K, V> materialized() {
            // 从当前树完整复制的可变 TreeMap，只属于本次调用。
            TreeMap<K, V> values = new TreeMap<>(comparator);
            values.putAll(this);
            return values;
        }

        /**
         * 把节点直接作为只读 Map.Entry 返回，空节点仍返回 null。
         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
         */
        private Entry<K, V> entry(Node<K, V> node) { return node; }

        /**
         * 在树上寻找指定键下界或上界候选，lower 决定方向、inclusive 决定是否接纳相等，不物化整表。
         * @param key 当前查询、插入或删除使用的键
         * @param lower 是否查找小于方向的边界，false 表示大于方向
         * @param inclusive 是否包含本次单端点边界
         */
        private Node<K, V> seek(K key, boolean lower, boolean inclusive) {
            // 当前正在检查是否可作为上下界候选的节点。
            Node<K, V> current = root;
            // 当前二分查找键，或搜索到的最近边界候选节点。
            Node<K, V> candidate = null;
            while (current != null) {
                // 当前目标键与节点或变化数组键的排序比较结果。
                int comparison = compare(key, current.key);
                // 当前节点是否满足查找方向及边界包含规则。
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

        /**
         * 检查根节点是否为空。
         */
        @Override
        public boolean isEmpty() { return root == null; }

        /**
         * 按树排序规则查找节点，存在即返回 true。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public boolean containsKey(Object key) { return find((K) key) != null; }

        /**
         * 按键序遍历条目，用 Objects.equals 比较业务值；本方法可能遍历整树。
         * @param value 当前插入、查询相等性或尝试设置的业务值
         */
        @Override
        public boolean containsValue(Object value) {
            for (Entry<K, V> /* 当前消费、遍历或查询得到的键值条目。 */ entry : entrySet()) {
                if (java.util.Objects.equals(entry.getValue(), value)) return true;
            }
            return false;
        }

        /**
         * 沿树搜索键并返回节点值，未找到返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public V get(Object key) {
            // 当前查到或从遍历栈弹出的不可变节点。
            Node<K, V> node = find((K) key);
            return node == null ? null : node.value;
        }

        /**
         * 按比较器逐层选择左或右孩子定位键，未找到返回 null，不扫描其他分支。
         * @param key 当前查询、插入或删除使用的键
         */
        private Node<K, V> find(K key) {
            // 按目标键比较结果向左或向右推进的搜索节点。
            Node<K, V> current = root;
            while (current != null) {
                // 当前目标键与节点或变化数组键的排序比较结果。
                int comparison = compare(key, current.key);
                if (comparison == 0) return current;
                current = comparison < 0 ? current.left : current.right;
            }
            return null;
        }

        /**
         * 创建当前不可变树的有序条目集合视图，迭代器用栈中序遍历，不先复制全部条目。
         */
        @Override
        public Set<Entry<K, V>> entrySet() {
            return /* 当前树的轻量条目集合视图，每次迭代创建自己的栈。 */ new AbstractSet<>() {
                /**
                 * 为本次遍历创建独立栈迭代器；树节点不可变，不支持通过迭代器改写树。
                 */
                @Override
                public Iterator<Entry<K, V>> iterator() {
                    return /* 按键序中序访问不可变树节点的单次迭代器。 */ new Iterator<>() {
                        /** 当前中序迭代器私有的待访问节点栈。 */
                        private final Deque<Node<K, V>> stack = initialize();

                        /**
                         * 创建遍历栈，并把根到最左节点的路径压入，供第一次取最小条目。
                         */
                        private Deque<Node<K, V>> initialize() {
                            // 本次迭代私有的待访问节点栈。
                            Deque<Node<K, V>> values = new ArrayDeque<>();
                            pushLeft(root, values);
                            return values;
                        }

                        /**
                         * 把给定子树的整条左侧路径压入指定栈，栈顶保持下一条最小键。
                         * @param node 当前要查找、修改路径、旋转或读取高度的节点/子树根
                         * @param values 本迭代器待访问节点的栈，方法会在其上压入左侧路径
                         */
                        private void pushLeft(Node<K, V> node, Deque<Node<K, V>> values) {
                            // 正在沿给定子树最左路径压栈的节点。
                            Node<K, V> current = node;
                            while (current != null) {
                                values.push(current);
                                current = current.left;
                            }
                        }

                        /**
                         * 栈非空时仍有条目可取。
                         */
                        @Override
                        public boolean hasNext() { return !stack.isEmpty(); }

                        /**
                         * 弹出下一个中序节点并压入其右子树左路径；栈为空时报没有更多元素。
                         */
                        @Override
                        public Entry<K, V> next() {
                            if (stack.isEmpty()) throw new java.util.NoSuchElementException();
                            // 当前查到或从遍历栈弹出的不可变节点。
                            Node<K, V> node = stack.pop();
                            pushLeft(node.right, stack);
                            return node;
                        }
                    };
                }

                /**
                 * 返回该集合所属树版本的条目数。
                 */
                @Override
                public int size() { return PersistentTreeMap.this.size(); }
            };
        }

        /**
         * 沿当前不可变树搜索取得严格小于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> lowerEntry(K key) { return entry(seek(key, true, false)); }

        /**
         * 沿当前不可变树搜索取得严格小于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K lowerKey(K key) { return keyOf(lowerEntry(key)); }

        /**
         * 沿当前不可变树搜索取得不大于给定键的最大条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> floorEntry(K key) { return entry(seek(key, true, true)); }

        /**
         * 沿当前不可变树搜索取得不大于给定键的最大键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K floorKey(K key) { return keyOf(floorEntry(key)); }

        /**
         * 沿当前不可变树搜索取得不小于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> ceilingEntry(K key) { return entry(seek(key, false, true)); }

        /**
         * 沿当前不可变树搜索取得不小于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K ceilingKey(K key) { return keyOf(ceilingEntry(key)); }

        /**
         * 沿当前不可变树搜索取得严格大于给定键的最小条目，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public Entry<K, V> higherEntry(K key) { return entry(seek(key, false, false)); }

        /**
         * 沿当前不可变树搜索取得严格大于给定键的最小键，缺失返回 null。
         * @param key 当前查询、插入或删除使用的键
         */
        @Override
        public K higherKey(K key) { return keyOf(higherEntry(key)); }

        /**
         * 从可空条目取得键，没有条目时返回 null。
         * @param entry 要提取键的可空条目
         */
        private K keyOf(Entry<K, V> entry) { return entry == null ? null : entry.getKey(); }

        /**
         * 空树返回 null，否则返回最左节点条目。
         */
        @Override
        public Entry<K, V> firstEntry() { return entry(root == null ? null : minimum(root)); }

        /**
         * 空树返回 null，否则沿右侧找到最大键节点条目。
         */
        @Override
        public Entry<K, V> lastEntry() {
            if (root == null) return null;
            // 沿右孩子推进以找到最大键条目的节点。
            Node<K, V> current = root;
            while (current.right != null) current = current.right;
            return entry(current);
        }

        /**
         * 取得当前排序中的最小键；空表抛出 NoSuchElementException。
         */
        @Override
        public K firstKey() {
            // 当前消费、遍历或查询得到的键值条目。
            Entry<K, V> entry = firstEntry();
            if (entry == null) throw new java.util.NoSuchElementException();
            return entry.getKey();
        }

        /**
         * 取得当前排序中的最大键；空表抛出 NoSuchElementException。
         */
        @Override
        public K lastKey() {
            // 当前消费、遍历或查询得到的键值条目。
            Entry<K, V> entry = lastEntry();
            if (entry == null) throw new java.util.NoSuchElementException();
            return entry.getKey();
        }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollFirstEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 始终拒绝取出并删除边界条目，即使表为空也抛 UnsupportedOperationException。
         */
        @Override
        public Entry<K, V> pollLastEntry() { throw new UnsupportedOperationException("state map is immutable"); }

        /**
         * 取得反向键序的 Map。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         */
        @Override
        public NavigableMap<K, V> descendingMap() { return materialized().descendingMap(); }

        /**
         * 取得正向有序键集合。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         */
        @Override
        public NavigableSet<K> navigableKeySet() { return materialized().navigableKeySet(); }

        /**
         * 取得反向有序键集合。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         */
        @Override
        public NavigableSet<K> descendingKeySet() { return materialized().descendingKeySet(); }

        /**
         * 取得指定双端点范围，两个布尔参数分别决定是否包含边界。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         * @param fromKey 范围起点键
         * @param fromInclusive 是否包含范围起点
         * @param toKey 范围终点键
         * @param toInclusive 是否包含范围终点
         */
        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return materialized().subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        /**
         * 取得上界之前的范围，inclusive 决定是否包含上界。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         * @param toKey 范围终点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return materialized().headMap(toKey, inclusive);
        }

        /**
         * 取得下界之后的范围，inclusive 决定是否包含下界。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         * @param fromKey 范围起点键
         * @param inclusive 是否包含本次单端点边界
         */
        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return materialized().tailMap(fromKey, inclusive);
        }

        /**
         * 取得包含下界、排除上界的范围。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         * @param fromKey 范围起点键
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) { return materialized().subMap(fromKey, toKey); }

        /**
         * 取得严格小于上界的范围。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         * @param toKey 范围终点键
         */
        @Override
        public SortedMap<K, V> headMap(K toKey) { return materialized().headMap(toKey); }

        /**
         * 取得不小于下界的范围。先复制为临时 TreeMap，再返回其可变视图；修改不写回本树。
         * @param fromKey 范围起点键
         */
        @Override
        public SortedMap<K, V> tailMap(K fromKey) { return materialized().tailMap(fromKey); }

        /**
         * 不可变 AVL 树节点兼 Map.Entry，只固定引用和高度，不深度冻结键和值。
         * @param <K> 有序状态表的键类型，使用自然顺序或所给比较器
         * @param <V> 状态表存放的业务值类型，本工具不深拷贝该值
         */
        private static final class Node<K, V> implements Entry<K, V> {
            /**  此条目的键引用，调用方须保持影响排序的内容稳定。 返回节点持有的键引用。 */
            @Getter(onMethod_ = {@Override})
            private final K key;
            /**  此条目的业务值引用，不在节点内深拷贝。 返回节点持有的业务值引用。 */
            @Getter(onMethod_ = {@Override})
            private final V value;
            /** 此版本左子树的不可变节点引用。 */
            private final Node<K, V> left;
            /** 此版本右子树的不可变节点引用。 */
            private final Node<K, V> right;
            /** 根据左右孩子计算并固定的节点高度。 */
            private final int height;

            /**
             * 固定保存键、值和左右子树引用，并由孩子高度计算本节点高度；不深拷贝业务键和值。
             * @param key 当前查询、插入或删除使用的键
             * @param value 当前插入、查询相等性或尝试设置的业务值
             * @param left 此新节点借用的不可变左子树，可为空
             * @param right 此新节点借用的不可变右子树，可为空
             */
            private Node(K key, V value, Node<K, V> left, Node<K, V> right) {
                this.key = key;
                this.value = value;
                this.left = left;
                this.right = right;
                this.height = 1 + Math.max(height(left), height(right));
            }

            /**
             * 始终拒绝通过 Map.Entry 原位改值，写入必须创建新的树路径。
             * @param value 当前插入、查询相等性或尝试设置的业务值
             */
            @Override
            public V setValue(V value) { throw new UnsupportedOperationException("state map is immutable"); }

            /**
             * 按 Map.Entry 规则分别比较键和值，不比较子树或缓存高度。
             * @param other 按 Map.Entry 规则进行相等性比较的对象
             */
            @Override
            public boolean equals(Object other) {
                return other instanceof Entry<?, ?> /* 当前消费、遍历或查询得到的键值条目。 */ entry
                        && java.util.Objects.equals(key, entry.getKey())
                        && java.util.Objects.equals(value, entry.getValue());
            }

            /**
             * 按 Map.Entry 规则异或键和值的哈希，不把整棵子树纳入条目哈希。
             */
            @Override
            public int hashCode() {
                return java.util.Objects.hashCode(key) ^ java.util.Objects.hashCode(value);
            }

        }
    }
}
