package com.surprising.aeron.service.state.change;

/**
 * Owner 有序提交视图：按账户 Lane 接管原变更缓冲，普通跨账户成交不再逐实体复制。
 * 同一实体只属于一个 Lane；同步控制写入或同 Lane 多次交接时才合并，保持最后写入语义。
 */
public final class OwnerIndexedChanges<V, I> {
    /** Owner 同步控制操作的变更；写入前合并此前接管的 Lane 结果。 */
    private final RuntimeIndexedChangeBuffer<V, I> direct;
    /** 与协议 Lane 位图上限一致，槽位按需初始化，清空后保留容量。 */
    private final RuntimeIndexedChangeBuffer<V, I>[] lanes;
    /** 本次提交中有内容的 Lane；热路径仅访问置位槽。 */
    private long laneMask;

    public OwnerIndexedChanges() { this(new RuntimeIndexedChangeBuffer<>()); }

    public @SuppressWarnings("unchecked")
    OwnerIndexedChanges(RuntimeIndexedChangeBuffer<V, I> direct) {
        this.direct = direct;
        lanes = new RuntimeIndexedChangeBuffer[Long.SIZE];
    }

    public void adopt(int laneId, RuntimeIndexedChangeBuffer<V, I> source) {
        if (laneId < 0 || laneId >= lanes.length) throw new IllegalArgumentException("invalid change Lane");
        if (source.isEmpty()) return;
        if (!direct.isEmpty()) {
            source.drainIndexedTo(direct);
            return;
        }
        var target = lanes[laneId];
        if (target == null) lanes[laneId] = target = source.emptyLike();
        if (target.isEmpty()) target.swapWith(source);
        else source.drainIndexedTo(target);
        laneMask |= 1L << laneId;
    }

    public void put(long key, V value) {
        // value may be borrowed from one of the retained Lane buffers. Freeze it in
        // direct before releasing that source; the old Lane row must not overwrite it.
        direct.put(key, value);
        consolidateExcept(key);
    }

    private void consolidateExcept(long replacementKey) {
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            var source = lanes[lane];
            for (int slot = 0; slot < source.size(); slot++) {
                long key = source.keyAt(slot);
                if (key != replacementKey)
                    direct.putIndexed(key, source.valueAt(slot), source.hasPreparedAt(slot), source.preparedAt(slot));
            }
            source.clear();
        }
        laneMask = 0;
    }

    public V get(long key) {
        int slot = direct.indexOf(key);
        if (slot >= 0) return direct.valueAt(slot);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            slot = lanes[lane].indexOf(key);
            if (slot >= 0) return lanes[lane].valueAt(slot);
        }
        return null;
    }

    public boolean containsKey(long key) {
        if (direct.indexOf(key) >= 0) return true;
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if (lanes[lane].indexOf(key) >= 0) return true;
        }
        return false;
    }

    public boolean isEmpty() { return laneMask == 0 && direct.isEmpty(); }
    public RuntimeIndexedChangeBuffer<V, I> directBuffer() { return direct; }
    public long retainedLaneMask() { return laneMask; }
    public RuntimeIndexedChangeBuffer<V, I> laneBuffer(int laneId) { return lanes[laneId]; }
    void forEachIndexed(RuntimeIndexedChangeBuffer.Consumer<V, I> consumer) {
        direct.forEachIndexed(consumer);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEachIndexed(consumer);
        }
    }
    public void forEach(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        direct.forEach(consumer);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].forEach(consumer);
        }
    }
    /** Consume changed values and clear their retained references in the same traversal. */
    void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        direct.drainTo(consumer);
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].drainTo(consumer);
        }
        laneMask = 0;
    }
    public org.eclipse.collections.api.iterator.LongIterator longIterator() {
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
    public long[] toArray() {
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
    public void clear() {
        direct.clear();
        long remaining = laneMask;
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            lanes[lane].clear();
        }
        laneMask = 0;
    }
}
