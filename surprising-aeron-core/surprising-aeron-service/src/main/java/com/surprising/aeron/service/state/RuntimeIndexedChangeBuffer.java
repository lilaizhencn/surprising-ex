package com.surprising.aeron.service.state;

/**
 * Lane 变更与可选的持仓预计算索引共用实体键索引。
 * 订单只交接运行态引用，不分配 prepared 列；持仓按需分配第二列。
 */
class RuntimeIndexedChangeBuffer<V, I> extends RuntimeChangeBuffer<V> {
    /** 预计算索引列按需分配，与基础变更槽对齐。 */
    private static final Object[] NO_PREPARED = new Object[0];
    /** 非null哨兵表示已预计算的删除；null槽表示尚未预计算。 */
    private static final Object DELETED_INDEX = new Object();
    private Object[] prepared = NO_PREPARED;

    @FunctionalInterface
    interface Consumer<V, I> {
        void accept(long key, V value, boolean hasPrepared, I indexValue);
    }

    RuntimeIndexedChangeBuffer<V, I> emptyLike() { return new RuntimeIndexedChangeBuffer<>(); }

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

    boolean hasPreparedAt(int slot) { return slot < prepared.length && prepared[slot] != null; }

    @SuppressWarnings("unchecked") I preparedAt(int slot) {
        Object value = slot < prepared.length ? prepared[slot] : null;
        return value == DELETED_INDEX ? null : (I) value;
    }

    void drainIndexedTo(Consumer<V, I> consumer) {
        forEachIndexed(consumer);
        clear();
    }

    /** Copy between owned buffers without allocating a capturing callback, then release the source lease. */
    void drainIndexedTo(RuntimeIndexedChangeBuffer<V, I> target) {
        if (target == this) throw new IllegalArgumentException("change source and target must differ");
        for (int slot = 0; slot < size; slot++)
            target.putIndexed(keyAt(slot), valueAt(slot), hasPreparedAt(slot), preparedAt(slot));
        clear();
    }

    @Override void drainTo(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<V> consumer) {
        forEach(consumer);
        // Also release prepared facts and specialized publication cells before reuse.
        clear();
    }

    @Override void clear() {
        if (size == 0) return;
        int length = Math.min(size, prepared.length);
        java.util.Arrays.fill(prepared, 0, length, null);
        super.clear();
    }
}

/** Position after-images move with their physical buffer until its consumer releases them. */
final class RuntimePositionChangeBuffer
        extends RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> {
    private PositionRuntime[] copies = new PositionRuntime[8];

    @Override int put(long key, PositionRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    @Override RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> emptyLike() {
        return new RuntimePositionChangeBuffer();
    }

    @Override void swapWith(RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> other) {
        if (!(other instanceof RuntimePositionChangeBuffer positions))
            throw new IllegalStateException("position after-images require a position change buffer");
        super.swapWith(other);
        PositionRuntime[] previous = copies;
        copies = positions.copies;
        positions.copies = previous;
    }

    @Override void clear() {
        for (int slot = 0; slot < size; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}

/** User before/after values belong to the existing command buffer, never to the live Lane table. */
final class RuntimeUserChangeBuffer extends RuntimeIndexedChangeBuffer<com.surprising.aeron.service.state.account.UserRuntime, com.surprising.aeron.service.state.account.UserRuntime> {
    private com.surprising.aeron.service.state.account.UserRuntime[] copies = new com.surprising.aeron.service.state.account.UserRuntime[8];

    @Override int put(long key, com.surprising.aeron.service.state.account.UserRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /** Preserve the original authority identity as well as its independent before-value. */
    void captureBefore(long key, com.surprising.aeron.service.state.account.UserRuntime authority) {
        if (containsKey(key)) return;
        put(key, authority);
        putPrepared(key, authority);
    }

    @Override void clear() {
        for (int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}

/** Command-local order after-images. Their objects move with the physical change buffer. */
final class RuntimeOrderChangeBuffer extends RuntimeIndexedChangeBuffer<OrderRuntime, Void> {
    private OrderRuntime[] copies = new OrderRuntime[8];

    /** Only the Lane uses live references, until its final publication fence. */
    void putLive(long key, OrderRuntime value) { super.put(key, value); }

    void freezeValues() {
        for (int slot = 0; slot < size; slot++) put(keyAt(slot), valueAt(slot));
    }

    @Override int put(long key, OrderRuntime value) {
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    @Override RuntimeIndexedChangeBuffer<OrderRuntime, Void> emptyLike() {
        return new RuntimeOrderChangeBuffer();
    }

    @Override void swapWith(RuntimeIndexedChangeBuffer<OrderRuntime, Void> other) {
        if (!(other instanceof RuntimeOrderChangeBuffer orders))
            throw new IllegalStateException("order after-images require an order change buffer");
        super.swapWith(other);
        OrderRuntime[] previous = copies; copies = orders.copies; orders.copies = previous;
    }

    @Override void clear() {
        for (int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}

