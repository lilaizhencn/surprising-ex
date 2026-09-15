package com.surprising.aeron.service.state;

/**
 * Lane 变更与可选的持仓预计算索引共用实体键索引。
 * 订单只交接运行态引用，不分配 prepared 列；持仓按需分配第二列。
 */
final class RuntimeIndexedChangeBuffer<V, I> extends RuntimeChangeBuffer<V> {
    /** 预计算索引列按需分配，与基础变更槽对齐。 */
    private static final Object[] NO_PREPARED = new Object[0];
    /** 非null哨兵表示已预计算的删除；null槽表示尚未预计算。 */
    private static final Object DELETED_INDEX = new Object();
    private Object[] prepared = NO_PREPARED;

    @FunctionalInterface
    interface Consumer<V, I> {
        void accept(long key, V value, boolean hasPrepared, I indexValue);
    }

    /** 将 Lane 的实体和预计算索引一起交给 Owner，交还空缓冲以供事件池复用。 */
    void swapWith(RuntimeIndexedChangeBuffer<V, I> other) {
        swapStorage(other);
        Object[] values = prepared; prepared = other.prepared; other.prepared = values;
    }

    @Override int put(long key, V value) {
        int slot = super.put(key, value);
        if (slot < prepared.length) prepared[slot] = null;
        return slot;
    }

    void putPrepared(long key, I value) {
        int slot = indexOf(key);
        if (slot < 0) throw new IllegalStateException("prepared index has no state change");
        setPrepared(slot, value);
    }

    void putIndexed(long key, V value, boolean hasPrepared, I indexValue) {
        int slot = put(key, value);
        if (hasPrepared) setPrepared(slot, indexValue);
    }

    private void setPrepared(int slot, I value) {
        if (slot >= prepared.length) {
            int length = Math.max(8, prepared.length);
            while (length <= slot) length = Math.multiplyExact(length, 2);
            prepared = java.util.Arrays.copyOf(prepared, length);
        }
        prepared[slot] = value == null ? DELETED_INDEX : value;
    }

    void forEachIndexed(Consumer<V, I> consumer) {
        for (int slot = 0; slot < size; slot++) {
            Object stored = slot < prepared.length ? prepared[slot] : null;
            boolean hasPrepared = stored != null;
            @SuppressWarnings("unchecked") I indexValue = stored == DELETED_INDEX ? null : (I) stored;
            consumer.accept(keyAt(slot), valueAt(slot), hasPrepared, indexValue);
        }
    }

    void drainIndexedTo(Consumer<V, I> consumer) {
        forEachIndexed(consumer);
        clear();
    }

    @Override void clear() {
        if (size == 0) return;
        int length = Math.min(size, prepared.length);
        java.util.Arrays.fill(prepared, 0, length, null);
        super.clear();
    }
}
