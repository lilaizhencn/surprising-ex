package com.surprising.aeron.service.state.account;

import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** Account Lane owned reverse index; only orders with multiple aliases allocate a set. */
public final class OrderClientKeyIndex {
    // Client identity keys are non-negative; zero remains a valid alias.
    private final Long2LongHashMap single = new Long2LongHashMap(-1);
    private final Long2ObjectHashMap<LongHashSet> multiple = new Long2ObjectHashMap<>();

    public void add(long orderId, long key) {
        LongHashSet keys = multiple.get(orderId);
        if (keys != null) {
            keys.add(key);
            return;
        }
        // Client identity keys are non-negative and the single table uses -1 as
        // its missing value, so one probe is enough for both presence and value.
        long previous = single.get(orderId);
        if (previous == -1) {
            single.put(orderId, key);
        } else if (previous != key) {
            keys = new LongHashSet();
            keys.add(previous);
            single.remove(orderId);
            keys.add(key);
            multiple.put(orderId, keys);
        }
    }

    public void remove(long orderId, long key) {
        LongHashSet keys = multiple.get(orderId);
        if (keys == null) {
            if (single.get(orderId) == key) single.remove(orderId);
        } else if (keys.remove(key) && keys.size() == 1) {
            single.put(orderId, keys.longIterator().next());
            multiple.remove(orderId);
        }
    }

    public void forEach(long orderId, LongProcedure consumer) {
        LongHashSet keys = multiple.get(orderId);
        if (keys != null) keys.forEach(consumer);
        else {
            long key = single.get(orderId);
            if (key != -1) consumer.value(key);
        }
    }

    public void remove(long orderId) {
        single.remove(orderId);
        multiple.remove(orderId);
    }
}
