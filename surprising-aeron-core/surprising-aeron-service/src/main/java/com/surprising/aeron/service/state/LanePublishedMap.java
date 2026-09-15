package com.surprising.aeron.service.state;

import java.util.Collection;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Lane 交接后的 Owner 可见值。
 *
 * <p>Lane 不直接改表，而是把 primitive key/value 写入一个 {@link LanePublication}；Owner
 * 在有序提交点一次应用整批发布。发布表只由 Owner 访问，不需要并发容器或第二个待发布队列。</p>
 */
final class LanePublishedMap<V> {
    // Back-shift deletion retains backing storage under bounded order turnover.
    private final Long2ObjectHashMap<V> values = new Long2ObjectHashMap<>();

    /** Apply one staged value at the Owner publication boundary. */
    void applyPublished(long key, Object value) {
        @SuppressWarnings("unchecked") V typed = (V) value;
        if (typed == null) {
            values.remove(key);
        } else {
            values.put(key, typed);
        }
    }

    /** Direct owner-only update used by recovery and synchronous control paths. */
    V put(long key, V value) {
        if (value == null) return remove(key);
        return values.put(key, value);
    }

    V get(long key) { return values.get(key); }

    V remove(long key) {
        V previous = values.remove(key);
        return previous;
    }

    int size() { return values.size(); }

    void clear() {
        values.clear();
    }

    Collection<V> values() { return values.values(); }

    void stage(LanePublication publication, long key, V value) {
        if (publication == null) throw new IllegalArgumentException("publication is required");
        publication.add(this, key, value);
    }

}
