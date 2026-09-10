package com.surprising.aeron.service.state;

/**
 * 订单/持仓变更与其预计算索引共用一个实体键索引。
 * Lane 完成后一次交接两列，Owner 在有序提交点消费；不再为索引值另建键表。
 */
final class RuntimeIndexedChangeBuffer<V, I> extends RuntimeChangeBuffer<V> {
    /** 与基础变更槽对齐的索引值；null 可以表示删除。 */
    private Object[] prepared = new Object[8];
    /** 区分未预计算与预计算出的删除，生命周期与对应变更槽相同。 */
    private boolean[] present = new boolean[8];

    @FunctionalInterface
    interface Consumer<V, I> {
        void accept(long key, V value, boolean hasPrepared, I indexValue);
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
            int length = prepared.length;
            while (length <= slot) length = Math.multiplyExact(length, 2);
            prepared = java.util.Arrays.copyOf(prepared, length);
            present = java.util.Arrays.copyOf(present, length);
        }
        prepared[slot] = value;
        present[slot] = true;
    }

    void forEachIndexed(Consumer<V, I> consumer) {
        for (int slot = 0; slot < size; slot++) {
            boolean hasPrepared = slot < present.length && present[slot];
            @SuppressWarnings("unchecked") I indexValue = hasPrepared ? (I) prepared[slot] : null;
            consumer.accept(keyAt(slot), valueAt(slot), hasPrepared, indexValue);
        }
    }

    void drainIndexedTo(Consumer<V, I> consumer) {
        forEachIndexed(consumer);
        clear();
    }

    @Override void clear() {
        int length = Math.min(size, prepared.length);
        java.util.Arrays.fill(prepared, 0, length, null);
        java.util.Arrays.fill(present, 0, length, false);
        super.clear();
    }
}
