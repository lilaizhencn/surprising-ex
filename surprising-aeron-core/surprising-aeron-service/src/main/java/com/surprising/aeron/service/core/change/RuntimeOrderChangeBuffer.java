package com.surprising.aeron.service.core.change;

import com.surprising.aeron.service.order.state.OrderRuntime;

/**
 * 命令独占的订单后值缓冲。Lane 可先用 putLive 登记活订单引用，发布完成前必须冻结；冻结槽数组随基础物理缓冲一起交给 Owner，消费结束后才能清空和复用。
 */
public final class RuntimeOrderChangeBuffer extends RuntimeIndexedChangeBuffer<OrderRuntime, Void> {
    /** 属于当前物理订单变化缓冲的复用命令槽数组，随 swapWith 一起移动。 */
    private OrderRuntime[] copies = new OrderRuntime[8];

    /**
     * 临时保存所属 Lane 的活订单引用，尚不复制；调用方在发布或释放该前缀前完成 freezeValues。
     * @param key 订单编号原语键
     * @param value 本次订单后值，putLive 接受活引用，put 会复制到命令槽，null 表示删除
     */
    public void putLive(long key, OrderRuntime value) { super.put(key, value); }

    /**
     * 逐有效项通过 put 复制为本物理缓冲的命令槽值，使随后活订单变化不再影响本回执。
     */
    public void freezeValues() {
        for (/* 当前正在冻结、写入或清理的连续订单槽。 */ int slot = 0; slot < size; slot++) put(keyAt(slot), valueAt(slot));
    }

    /**
     * 定位订单槽并按需增长命令值槽数组，把非空订单复制到当前槽，再保存复制值及清除旧预计算事实。
     * @param key 订单编号原语键
     * @param value 本次订单后值，putLive 接受活引用，put 会复制到命令槽，null 表示删除
     */
    public @Override int put(long key, OrderRuntime value) {
        // 当前正在冻结、写入或清理的连续订单槽。
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length) copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoCommandSlot(copies[slot]);
        return super.put(key, value);
    }

    /**
     * 创建同样携带订单命令槽的空缓冲，保证交接时保持专用值槽类型。
     */
    @Override RuntimeIndexedChangeBuffer<OrderRuntime, Void> emptyLike() {
        return new RuntimeOrderChangeBuffer();
    }

    /**
     * 要求对方也是订单专用缓冲，同时交换键值、预计算列和命令槽数组，防止换完存储后清掉另一方仍在使用的值。
     * @param other 已完成独占交接的另一订单专用变化缓冲
     */
    public @Override void swapWith(RuntimeIndexedChangeBuffer<OrderRuntime, Void> other) {
        if (!(other instanceof RuntimeOrderChangeBuffer /* 类型校验后的另一订单专用缓冲。 */ orders))
            throw new IllegalStateException("order after-images require an order change buffer");
        super.swapWith(other);
        // 交换期间暂存本物理缓冲的命令槽数组。
        OrderRuntime[] previous = copies; copies = orders.copies; orders.copies = previous;
    }

    /**
     * 清除当前有效命令槽内容后清基础缓冲，保留物理槽对象；调用方须已完成活引用冻结且消费者已经释放租约。
     */
    public @Override void clear() {
        for (/* 当前正在冻结、写入或清理的连续订单槽。 */ int slot = 0; slot < size; slot++) if (copies[slot] != null) copies[slot].clearCommandSlot();
        super.clear();
    }
}
