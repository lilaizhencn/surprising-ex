package com.surprising.aeron.service.state;

import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** Account Lane owned reverse index; only orders with multiple aliases allocate a set. */
final class OrderClientKeyIndex {
    // Client identity keys are non-negative; zero remains a valid alias.
    private final Long2LongHashMap single = new Long2LongHashMap(-1);
    private final Long2ObjectHashMap<LongHashSet> multiple = new Long2ObjectHashMap<>();

    void add(long orderId, long key) {
        LongHashSet keys = multiple.get(orderId);
        if (keys != null) {
            keys.add(key);
        } else if (!single.containsKey(orderId)) {
            single.put(orderId, key);
        } else if (single.get(orderId) != key) {
            keys = new LongHashSet();
            keys.add(single.get(orderId));
            single.remove(orderId);
            keys.add(key);
            multiple.put(orderId, keys);
        }
    }

    void remove(long orderId, long key) {
        LongHashSet keys = multiple.get(orderId);
        if (keys == null) {
            if (single.containsKey(orderId) && single.get(orderId) == key) single.remove(orderId);
        } else if (keys.remove(key) && keys.size() == 1) {
            single.put(orderId, keys.longIterator().next());
            multiple.remove(orderId);
        }
    }

    void forEach(long orderId, LongProcedure consumer) {
        LongHashSet keys = multiple.get(orderId);
        if (keys != null) keys.forEach(consumer);
        else if (single.containsKey(orderId)) consumer.value(single.get(orderId));
    }

    void remove(long orderId) {
        single.remove(orderId);
        multiple.remove(orderId);
    }
}
