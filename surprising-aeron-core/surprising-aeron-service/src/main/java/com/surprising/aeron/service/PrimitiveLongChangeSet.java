package com.surprising.aeron.service;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

final class PrimitiveLongChangeSet extends AbstractCollection<Long> {

    private final LongHashSet membership = new LongHashSet();
    private final LongArrayList insertionOrder = new LongArrayList();

    boolean add(long value) {
        if (!membership.add(value)) return false;
        insertionOrder.add(value);
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends Long> values) {
        boolean changed = false;
        if (values instanceof ImmutableLongArrayList primitive) {
            for (int index = 0; index < primitive.size(); index++) changed |= add(primitive.valueAt(index));
            return changed;
        }
        for (Long value : values) changed |= add(value);
        return changed;
    }

    @Override
    public boolean add(Long value) {
        if (value == null) throw new NullPointerException("primitive change id is required");
        return add(value.longValue());
    }

    @Override
    public boolean contains(Object value) {
        return value instanceof Long id && membership.contains(id.longValue());
    }

    long[] toPrimitiveArray() {
        return insertionOrder.toArray();
    }

    @Override
    public Iterator<Long> iterator() {
        LongIterator iterator = insertionOrder.longIterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public Long next() {
                if (!iterator.hasNext()) throw new NoSuchElementException();
                return iterator.next();
            }
        };
    }

    @Override
    public int size() {
        return insertionOrder.size();
    }

    @Override
    public void clear() {
        membership.clear();
        insertionOrder.clear();
    }
}
