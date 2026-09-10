package com.surprising.aeron.service.state;

/**
 * Owner 有序提交视图：按账户 Lane 接管原变更缓冲，普通跨账户成交不再逐实体复制。
 * 同一实体只属于一个 Lane；同步控制写入或同 Lane 多次交接时才合并，保持最后写入语义。
 */
final class OwnerIndexedChanges<V, I> {
    /** Owner 同步控制操作的变更；写入前合并此前接管的 Lane 结果。 */
    private final RuntimeIndexedChangeBuffer<V, I> direct = new RuntimeIndexedChangeBuffer<>();
    /** 与协议 Lane 位图上限一致，槽位按需初始化，清空后保留容量。 */
    private final RuntimeIndexedChangeBuffer<V, I>[] lanes;
    /** 本次提交中有内容的 Lane；热路径仅访问置位槽。 */
    private long laneMask;

    @SuppressWarnings("unchecked")
    OwnerIndexedChanges() { lanes = new RuntimeIndexedChangeBuffer[Long.SIZE]; }

    void adopt(int laneId, RuntimeIndexedChangeBuffer<V, I> source) {
        if (laneId < 0 || laneId >= lanes.length) throw new IllegalArgumentException("invalid change Lane");
        if (source.isEmpty()) return;
        if (!direct.isEmpty()) {
            source.drainIndexedTo(direct::putIndexed);
            return;
        }
        var target = lanes[laneId];
        if (target == null) lanes[laneId] = target = new RuntimeIndexedChangeBuffer<>();
        if (target.isEmpty()) target.swapWith(source);
        else source.drainIndexedTo(target::putIndexed);
        laneMask |= 1L << laneId;
    }

    void put(long key, V value) {
        consolidate();
        direct.put(key, value);
    }

    private void consolidate() {
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].drainIndexedTo(direct::putIndexed);
        }
        laneMask = 0;
    }

    V get(long key) {
        if (direct.containsKey(key)) return direct.get(key);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            if (lanes[lane].containsKey(key)) return lanes[lane].get(key);
        }
        return null;
    }

    boolean isEmpty() { return laneMask == 0 && direct.isEmpty(); }
    void forEachIndexed(RuntimeIndexedChangeBuffer.Consumer<V, I> consumer) {
        direct.forEachIndexed(consumer);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEachIndexed(consumer);
        }
    }
    void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        direct.forEach(consumer);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEach(consumer);
        }
    }
    org.eclipse.collections.api.iterator.LongIterator longIterator() {
        return new org.eclipse.collections.api.iterator.LongIterator() {
            long remaining = laneMask;
            RuntimeIndexedChangeBuffer<V, I> current = direct;
            int offset;
            public boolean hasNext() {
                while (offset == current.size() && remaining != 0) {
                    int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
                    current = lanes[lane]; offset = 0;
                }
                return offset < current.size();
            }
            public long next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                return current.keyAt(offset++);
            }
        };
    }
    long[] toArray() {
        int size = direct.size();
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            size += lanes[lane].size();
        }
        long[] result = new long[size];
        var iterator = longIterator();
        for (int i = 0; i < size; i++) result[i] = iterator.next();
        return result;
    }
    void clear() {
        direct.clear();
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].clear();
        }
        laneMask = 0;
    }
}
