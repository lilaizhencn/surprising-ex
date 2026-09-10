package com.surprising.aeron.service.state;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lane 写入、Owner 按收据读取的不可变实体版本。替代 Owner 二次写入的发布表。
 * CHM 只负责跨线程安全定位；未开放版本沿 previous 读取，已开放版本由 Lane 异步回收。
 */
final class LanePublishedMap<V> {
    /** 同一实体的业务写入仍由账户 Lane 串行执行；Owner 同步控制写入受现有交接屏障保护。 */
    private final ConcurrentHashMap<Key, Version<V>> values = new ConcurrentHashMap<>();

    /** 查询键只属于当前线程且从不存入表，避免 Owner 每次读取装箱；写入键始终独立。 */
    private static final ThreadLocal<Key> LOOKUP_KEY = ThreadLocal.withInitial(() -> new Key(0));

    private static final class Key {
        /** 表中的键构造后不变；只有线程私有查询键会更新此值。 */
        private long value;
        Key(long value) { this.value = value; }
        @Override public int hashCode() { return Long.hashCode(value); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && key.value == value;
        }
    }

    private static Key lookupKey(long value) {
        Key key = LOOKUP_KEY.get();
        key.value = value;
        return key;
    }

    static final class Version<V> {
        final long key;
        final V value;
        final LanePublication publication;
        final LanePublishedMap<V> map;
        volatile Version<V> previous;
        Version<?> cleanupNext;
        Version(long key, V value, LanePublication publication, LanePublishedMap<V> map, Version<V> previous) {
            this.key = key; this.value = value; this.publication = publication; this.map = map; this.previous = previous;
        }
        void reclaim() {
            if (value == null) map.values.remove(lookupKey(key), this);
            previous = null;
        }
    }

    long admissionSequence(long key) {
        Version<V> version = values.get(lookupKey(key));
        while (version != null) {
            if (version.publication == null) return 0;
            if (version.publication.visible) return version.value == null ? 0 : version.publication.admissionSequence;
            Version<V> previous = version.previous;
            if (previous == null && version.publication.visible) {
                return version.value == null ? 0 : version.publication.admissionSequence;
            }
            version = previous;
        }
        return 0;
    }

    V get(long key) { return visible(values.get(lookupKey(key))); }

    private V visible(Version<V> version) {
        while (version != null) {
            if (version.publication == null || version.publication.visible) return version.value;
            Version<V> previous = version.previous;
            // 清理可与只读遍历并行；读到清理后的尾部时重新取得发布标记。
            if (previous == null && version.publication.visible) return version.value;
            version = previous;
        }
        return null;
    }

    void stage(LanePublication publication, long key, V value) {
        Version<V> version = new Version<>(key, value, publication, this, values.get(lookupKey(key)));
        values.put(new Key(key), version);
        publication.add(version);
    }

    V put(long key, V value) {
        if (value == null) return remove(key);
        return visible(values.put(new Key(key), new Version<>(key, value, null, this, null)));
    }
    V remove(long key) { return visible(values.remove(lookupKey(key))); }
    V removeKey(long key) { return remove(key); }
    int size() { int count = 0; for (V ignored : values()) count++; return count; }
    void clear() { values.clear(); }

    Collection<V> values() {
        return new AbstractCollection<>() {
            public int size() { return LanePublishedMap.this.size(); }
            public Iterator<V> iterator() {
                var source = values.values().iterator();
                return new Iterator<>() {
                    V next;
                    public boolean hasNext() {
                        while (next == null && source.hasNext()) next = visible(source.next());
                        return next != null;
                    }
                    public V next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        V result = next; next = null; return result;
                    }
                };
            }
        };
    }
}
