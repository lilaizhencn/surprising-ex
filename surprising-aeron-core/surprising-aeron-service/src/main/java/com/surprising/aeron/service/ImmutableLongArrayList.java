package com.surprising.aeron.service;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.RandomAccess;
import java.util.function.LongConsumer;

/** Immutable List compatibility view backed by primitive storage. */
final class ImmutableLongArrayList extends AbstractList<Long> implements RandomAccess {

    private static final ImmutableLongArrayList EMPTY = new ImmutableLongArrayList(new long[0], true);
    private final long[] values;

    private ImmutableLongArrayList(long[] values, boolean owned) {
        this.values = owned ? values : values.clone();
    }

    static ImmutableLongArrayList copyOf(long[] values) {
        if (values == null || values.length == 0) return EMPTY;
        return new ImmutableLongArrayList(values, false);
    }

    static ImmutableLongArrayList sortedDistinct(long[] values, long additional) {
        long[] copy = Arrays.copyOf(values, values.length + 1);
        copy[values.length] = additional;
        Arrays.sort(copy);
        int size = 0;
        for (long value : copy) {
            if (size == 0 || copy[size - 1] != value) copy[size++] = value;
        }
        return size == 0 ? EMPTY : new ImmutableLongArrayList(Arrays.copyOf(copy, size), true);
    }

    static java.util.List<Long> preservePrimitive(java.util.List<Long> values) {
        if (values == null || values instanceof ImmutableLongArrayList) return values;
        long[] primitive = new long[values.size()];
        for (int index = 0; index < primitive.length; index++) primitive[index] = values.get(index);
        return copyOf(primitive);
    }

    long valueAt(int index) {
        return values[index];
    }

    long[] toPrimitiveArray() {
        return values.clone();
    }

    void forEachLong(LongConsumer consumer) {
        for (long value : values) consumer.accept(value);
    }

    @Override
    public Long get(int index) {
        return values[index];
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean contains(Object value) {
        if (!(value instanceof Long number)) return false;
        long expected = number;
        for (long candidate : values) if (candidate == expected) return true;
        return false;
    }

    @Override
    public Object[] toArray() {
        Long[] boxed = new Long[values.length];
        for (int index = 0; index < values.length; index++) boxed[index] = values[index];
        return boxed;
    }

    @Override
    public <T> T[] toArray(T[] destination) {
        return super.toArray(destination);
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        for (Object value : collection) if (!contains(value)) return false;
        return true;
    }
}
