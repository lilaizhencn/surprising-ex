package com.surprising.aeron.service;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

final class PrimitiveLongChangeSet extends AbstractCollection<Long> {

    private long[] values = new long[8];
    private long[] indexKeys = new long[16];
    private int[] indexGenerations = new int[16];
    private int generation = 1;
    private int size;

    boolean add(long value) {
        if (indexOf(value) >= 0) return false;
        ensureCapacity(size + 1);
        values[size++] = value;
        int position = emptyIndexPosition(value);
        indexKeys[position] = value;
        indexGenerations[position] = generation;
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
        return value instanceof Long id && indexOf(id.longValue()) >= 0;
    }

    long[] toPrimitiveArray() {
        return Arrays.copyOf(values, size);
    }

    ImmutableLongArrayList toImmutableList() {
        return ImmutableLongArrayList.takeOwnership(toPrimitiveArray());
    }

    @Override
    public Iterator<Long> iterator() {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() { return index < size; }

            @Override
            public Long next() {
                if (index >= size) throw new NoSuchElementException();
                return values[index++];
            }
        };
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        size = 0;
        if (++generation == 0) {
            Arrays.fill(indexGenerations, 0);
            generation = 1;
        }
    }

    private int indexOf(long value) {
        int mask = indexKeys.length - 1;
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) {
            if (indexKeys[position] == value) return position;
            position = (position + 1) & mask;
        }
        return -1;
    }

    private int emptyIndexPosition(long value) {
        int mask = indexKeys.length - 1;
        int position = longHash(value) & mask;
        while (indexGenerations[position] == generation) position = (position + 1) & mask;
        return position;
    }

    private void ensureCapacity(int required) {
        if (required > values.length) values = Arrays.copyOf(values, values.length << 1);
        if (required * 2 < indexKeys.length) return;
        indexKeys = new long[indexKeys.length << 1];
        indexGenerations = new int[indexKeys.length];
        generation = 1;
        for (int index = 0; index < size; index++) {
            int position = emptyIndexPosition(values[index]);
            indexKeys[position] = values[index];
            indexGenerations[position] = generation;
        }
    }

    private static int longHash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }
}
