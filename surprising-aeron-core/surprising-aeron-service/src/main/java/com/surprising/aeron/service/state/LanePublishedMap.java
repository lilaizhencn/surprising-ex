package com.surprising.aeron.service.state;

import java.util.Collection;
import org.agrona.collections.Long2LongHashMap;
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
    private final Long2LongHashMap admissionSequences = new Long2LongHashMap(0);

    /** Apply one staged value at the Owner publication boundary. */
    void applyPublished(long key, Object value, long admissionSequence) {
        @SuppressWarnings("unchecked") V typed = (V) value;
        if (typed == null) {
            values.remove(key);
            admissionSequences.remove(key);
        } else {
            values.put(key, typed);
            if (admissionSequence == 0) admissionSequences.remove(key);
            else admissionSequences.put(key, admissionSequence);
        }
    }

    long admissionSequence(long key) { return admissionSequences.get(key); }

    /** Direct owner-only update used by recovery and synchronous control paths. */
    V put(long key, V value) {
        V previous = values.get(key);
        applyPublished(key, value, 0);
        return previous;
    }

    V get(long key) { return values.get(key); }

    V remove(long key) {
        V previous = values.remove(key);
        admissionSequences.remove(key);
        return previous;
    }

    int size() { return values.size(); }

    void clear() {
        values.clear();
        admissionSequences.clear();
    }

    Collection<V> values() { return values.values(); }

    void stage(LanePublication publication, long key, V value) {
        if (publication == null) throw new IllegalArgumentException("publication is required");
        publication.add(this, key, value);
    }

}
