package com.surprising.aeron.service.state;

import java.util.Collection;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Lane 交接后的 Owner 可见值。
 *
 * <p>Lane 不直接改表；Owner 在有序提交点登记Lane权威值。发布表只由 Owner 访问，
 * 不需要并发容器、对象镜像或第二个待发布队列。</p>
 */
final class LanePublishedMap<V> {
    // Back-shift deletion retains backing storage under bounded order turnover.
    private final Long2ObjectHashMap<V> values = new Long2ObjectHashMap<>();

    /** Apply one staged value at the Owner publication boundary. */
    void applyPublished(long key, Object value) {
        applyPublished(key, value, null);
    }

    void applyPublished(long key, Object value, OwnerSettlementMergeEvent timing) {
        @SuppressWarnings("unchecked") V typed = (V) value;
        if (typed == null) {
            values.remove(key);
        } else {
            // Keep the existing value when the Lane published an equal after-image.  This
            // preserves the admission object's identity for unchanged resting orders while
            // still replacing it whenever execution actually changes a field.
            long started = timing == null ? 0 : System.nanoTime();
            V current = values.get(key);
            if (timing != null) {
                timing.orderGets++;
                timing.orderGetNanos += System.nanoTime() - started;
                started = System.nanoTime();
            }
            boolean equal = current != null && current.equals(typed);
            if (timing != null) {
                if (current != null) timing.orderEquals++;
                if (equal) timing.orderEqualHits++;
                if (current == typed) timing.orderSameReference++;
                timing.orderEqualsNanos += System.nanoTime() - started;
            }
            if (equal) return;
            if (timing != null) started = System.nanoTime();
            values.put(key, typed);
            if (timing != null) {
                timing.orderPuts++;
                timing.orderPutNanos += System.nanoTime() - started;
            }
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

    void removePublished(long key, OwnerSettlementMergeEvent timing) {
        if (timing != null && timing.mapShape) RemovalShape.observe(values, key, timing);
        long started = timing != null && timing.mapTiming ? System.nanoTime() : 0;
        V previous = values.remove(key);
        if (timing != null && timing.mapTiming) {
            timing.removalNanos += System.nanoTime() - started;
            timing.removals++;
            if (previous == null) timing.removalMisses++;
        }
    }

    /** Read-only, bounded diagnostic of the actual Agrona table, never an alternative map implementation. */
    private static final class RemovalShape {
        private static final java.lang.reflect.Field KEYS = field("keys"), VALUES = field("values");

        private static java.lang.reflect.Field field(String name) {
            try {
                var field = Long2ObjectHashMap.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("unsupported diagnostic map layout", failure); }
        }

        static void observe(Long2ObjectHashMap<?> map, long key, OwnerSettlementMergeEvent event) {
            try {
                long[] keys = (long[]) KEYS.get(map);
                Object[] values = (Object[]) VALUES.get(map);
                int mask = values.length - 1, index = org.agrona.collections.Hashing.hash(key, mask);
                int search = 1, scans = 0, moves = 0;
                event.shapeRemovals++;
                event.shapeMaxSize = Math.max(event.shapeMaxSize, map.size());
                event.shapeMaxCapacity = Math.max(event.shapeMaxCapacity, values.length);
                while (values[index] != null && keys[index] != key && search < 128) {
                    index = (index + 1) & mask;
                    search++;
                }
                if (values[index] != null && keys[index] != key) event.shapeCensored++;
                else if (values[index] == null) event.shapeMisses++;
                else {
                    int deleteIndex = index;
                    while (scans < 128) {
                        index = (index + 1) & mask;
                        if (values[index] == null) break;
                        scans++;
                        int hash = org.agrona.collections.Hashing.hash(keys[index], mask);
                        if ((index < hash && (hash <= deleteIndex || deleteIndex <= index))
                                || (hash <= deleteIndex && deleteIndex <= index)) {
                            moves++;
                            deleteIndex = index;
                        }
                    }
                    if (scans == 128) event.shapeCensored++;
                }
                event.shapeSearchSlots += search;
                event.shapeScanSlots += scans;
                event.shapeMoves += moves;
                event.shapeMaxSearch = Math.max(event.shapeMaxSearch, search);
                event.shapeMaxScan = Math.max(event.shapeMaxScan, scans);
            } catch (IllegalAccessException failure) { throw new IllegalStateException("cannot inspect diagnostic map layout", failure); }
        }
    }

    int size() { return values.size(); }

    void clear() {
        values.clear();
    }

    Collection<V> values() { return values.values(); }

}
