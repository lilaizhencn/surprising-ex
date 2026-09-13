package com.surprising.aeron.service.state;

/**
 * Lane 准备的发布批次。
 *
 * <p>Lane 只填充一组 primitive key/value 槽位；Owner 在有序提交点一次应用整批值，
 * 然后立即清空槽位。发布表只由 Owner 访问，不再需要二阶段 visible/commit 状态。</p>
 */
final class LanePublication {
    /** 准入命令序号，用于定位账户 Lane 的批量预留收据；普通发布为0。 */
    long admissionSequence;
    /** Settlement publication borrows the already-populated LaneDelta buffers. */
    private TradingRuntimeState.LaneDelta delta;
    private TradingRuntimeState runtime;
    private LanePublishedMap<?>[] maps = new LanePublishedMap<?>[8];
    private long[] keys = new long[8];
    private Object[] values = new Object[8];
    private int size;

    void add(LanePublishedMap<?> map, long key, Object value) {
        if (map == null) throw new IllegalArgumentException("publication map is required");
        ensureCapacity(size + 1);
        maps[size] = map;
        keys[size] = key;
        values[size] = value;
        size++;
    }

    void bind(TradingRuntimeState runtime, TradingRuntimeState.LaneDelta delta) {
        if (runtime == null || delta == null || size != 0 || this.delta != null) {
            throw new IllegalStateException("invalid Lane settlement publication");
        }
        this.runtime = runtime;
        this.delta = delta;
    }

    /** Owner 线程调用；重复调用不会再次应用同一批数据。 */
    void publish() {
        if (delta != null) {
            TradingRuntimeState.LaneDelta changes = delta;
            TradingRuntimeState owner = runtime;
            changes.users.forEach((id, value) -> owner.publishedUsers.applyPublished(id, value, admissionSequence));
            changes.orders.forEach((id, value) -> owner.publishedOrders.applyPublished(
                    id, changes.removedOrderRoutes.contains(id) ? null : value, admissionSequence));
            changes.reservations.forEach((id, value) -> owner.publishedReservations.applyPublished(
                    id, changes.removedReservationRoutes.contains(id) ? null : value, admissionSequence));
            changes.positions.forEach((id, value) -> owner.publishedPositions.applyPublished(id, value, admissionSequence));
            delta = null;
            runtime = null;
            return;
        }
        if (size == 0) return;
        for (int index = 0; index < size; index++) {
            maps[index].applyPublished(keys[index], values[index], admissionSequence);
        }
        clear();
    }

    private void ensureCapacity(int required) {
        if (required <= keys.length) return;
        int capacity = keys.length;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        maps = java.util.Arrays.copyOf(maps, capacity);
        keys = java.util.Arrays.copyOf(keys, capacity);
        values = java.util.Arrays.copyOf(values, capacity);
    }

    /** Discard staged references on failed/recycled events, retaining only buffer capacity. */
    void clear() {
        delta = null;
        runtime = null;
        java.util.Arrays.fill(maps, 0, size, null);
        java.util.Arrays.fill(values, 0, size, null);
        size = 0;
        admissionSequence = 0;
    }
}
