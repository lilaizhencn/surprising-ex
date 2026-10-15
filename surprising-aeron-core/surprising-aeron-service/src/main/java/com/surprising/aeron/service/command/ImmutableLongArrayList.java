package com.surprising.aeron.service.command;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.RandomAccess;
import java.util.function.LongConsumer;

/** 命令、分页与协议 List 契约使用的不可变 primitive 存储；内部遍历使用 PrimitiveLongView。 */
public final class ImmutableLongArrayList extends AbstractList<Long>
        implements RandomAccess, com.surprising.aeron.service.command.support.PrimitiveLongView {

    private static final ImmutableLongArrayList EMPTY = new ImmutableLongArrayList(new long[0]);
    private final long[] values;

    private ImmutableLongArrayList(long[] values) {
        this.values = values;
    }

    public static ImmutableLongArrayList takeOwnership(long[] values) {
        if (values == null || values.length == 0) return EMPTY;
        return new ImmutableLongArrayList(values);
    }

    public static ImmutableLongArrayList empty() { return EMPTY; }

    /** Append-only primitive builder for short-lived command pages and results. */
    public static final class Builder {
        private long[] values;
        private int size;

        public Builder(int initialCapacity) {
            values = new long[Math.max(1, initialCapacity)];
        }

        public void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length << 1);
            values[size++] = value;
        }

        public int size() { return size; }

        public long last() {
            if (size == 0) throw new IllegalStateException("empty primitive list");
            return values[size - 1];
        }

        public ImmutableLongArrayList freeze() {
            if (size == 0) return EMPTY;
            return takeOwnership(size == values.length ? values : Arrays.copyOf(values, size));
        }
    }

    public long valueAt(int index) {
        return values[index];
    }

    @Override public int primitiveSize() { return values.length; }
    @Override public long primitiveValueAt(int index) { return values[index]; }

    public boolean containsLong(long value) {
        for (long candidate : values) if (candidate == value) return true;
        return false;
    }

    public long[] toPrimitiveArray() {
        return values.clone();
    }

    public void forEachLong(LongConsumer consumer) {
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
