package com.surprising.aeron.service.state.change;

import com.surprising.aeron.service.state.order.ReservationRuntime;

/**
 * 一个 Lane 输出缓冲独占的预留后值。活引用在发布前复制为复用发布槽，后续预留原位扣减不改旧回执；消费者结束后清空租约内容，保留槽对象。
 */
public final class RuntimeReservationChangeBuffer extends RuntimeChangeBuffer<ReservationRuntime> {
    /** 本预留缓冲独占的可复用发布槽数组，不属于 Lane 权威预留表。 */
    private ReservationRuntime[] copies = new ReservationRuntime[8];

    /**
     * 先挂接 Lane 的活预留引用，不立即复制，发布前应冻结后值。
     * @param key 预留对应的订单编号
     * @param value 要挂接或复制的预留，null 表示删除
     */
    public void putLive(long key, ReservationRuntime value) { super.put(key, value); }

    /**
     * 逐有效项把当前预留复制进本缓冲自己的发布槽，null 删除项仍保留键。
     */
    public void freezeValues() {
        for (/* 当前正在冻结、写入或清理的预留连续槽。 */ int slot = 0; slot < size; slot++) put(keyAt(slot), valueAt(slot));
    }

    /**
     * 按键取得连续位置，把非空预留复制进同槽复用发布值，再记录该值。
     * @param key 预留对应的订单编号
     * @param value 要挂接或复制的预留，null 表示删除
     */
    public @Override int put(long key, ReservationRuntime value) {
        // 当前正在冻结、写入或清理的预留连续槽。
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    /**
     * 只清理有效前缀与已分配发布槽长度的交集，再清基础引用；未物化的内联活变化也能清理，不访问不存在的发布槽。
     */
    public @Override void clear() {
        // 内联活变化的数量可能超过已物化的发布槽数量，只清理实际存在的槽。
        for (/* 当前正在冻结、写入或清理的预留连续槽。 */ /* 有效变化数与已分配发布槽长度的较小值，避免未冻结的活变化访问数组外位置。 */ int slot = 0, count = Math.min(size, copies.length); slot < count; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}
