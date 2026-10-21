package com.surprising.aeron.service.state.change;

import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;

/**
 * 命令独占的持仓后值及预计算索引缓冲。写入即复制为当前缓冲的发布槽，交换时连同值槽和索引列一起移动；Owner 消费后才能清理这些可复用值。
 */
public final class RuntimePositionChangeBuffer
        extends RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> {
    /** 随本物理持仓变化缓冲移动的复用发布槽数组。 */
    private PositionRuntime[] copies = new PositionRuntime[8];

    /**
     * 按键定位并复制当前持仓到独立发布槽，清除该槽之前的预计算索引事实；null 表示删除。
     * @param key 运行时持仓键
     * @param value 要复制的持仓后值，null 表示删除
     */
    public @Override int put(long key, PositionRuntime value) {
        // 当前持仓对应或正在清理的有效连续槽。
        int slot = indexOf(key);
        if (slot < 0) slot = super.put(key, null);
        if (slot >= copies.length)
            copies = java.util.Arrays.copyOf(copies, Math.max(slot + 1, copies.length * 2));
        if (value != null) value = copies[slot] = value.copyIntoPublicationSlot(copies[slot]);
        return super.put(key, value);
    }

    /**
     * 创建具有持仓发布槽的空同类缓冲，供物理存储交换使用。
     */
    @Override RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> emptyLike() {
        return new RuntimePositionChangeBuffer();
    }

    /**
     * 只允许与另一持仓专用缓冲交接，基础存储、预计算索引及发布槽数组同步交换。
     * @param other 已经受控交接的另一持仓专用缓冲
     */
    @Override void swapWith(RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> other) {
        if (!(other instanceof RuntimePositionChangeBuffer /* 通过专用类型检查后的另一持仓缓冲。 */ positions))
            throw new IllegalStateException("position after-images require a position change buffer");
        super.swapWith(other);
        // 交换期间暂存当前持仓发布槽数组。
        PositionRuntime[] previous = copies;
        copies = positions.copies;
        positions.copies = previous;
    }

    /**
     * 清除有效持仓发布槽中的业务引用，再清基础值和索引事实，保留对象供后续命令复用。
     */
    public @Override void clear() {
        for (/* 当前持仓对应或正在清理的有效连续槽。 */ int slot = 0; slot < size; slot++)
            if (copies[slot] != null) copies[slot].clearPublicationSlot();
        super.clear();
    }
}
