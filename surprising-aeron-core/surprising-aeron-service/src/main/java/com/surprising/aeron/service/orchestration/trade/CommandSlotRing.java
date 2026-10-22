package com.surprising.aeron.service.orchestration.trade;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 按 Core 序号低位寻址的固定命令槽池。Owner 占用、初始化并在终态退休后复用对象；claimed 控制槽是否可再分配，原序号和部分业务引用可保留到下次初始化。它不按 FIFO 自动提交，也不复制订单或账户事实。
 */
public final class CommandSlotRing {
    /** 按序号低位寻址的预分配命令槽数组。 */
    private final CommandSlot[] contexts;
    /** 容量减一的环形寻址掩码。 */
    private final int mask;
    /**  当前 claimed 槽位数量。 返回池中已占用槽数，包含尚未链接到待撮合队列的预备槽。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int inFlight;
    /**  历史 claimed 数量峰值。 返回该槽池历史占用峰值。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private int highWaterMark;

    /**
     * 校验正的二次幂容量和一至六十四的 Lane 数，预分配空 CommandSlot；laneCount 仅做范围检查，不保存或据此给每槽分配 Lane 数组。
     * @param capacity 正的二次幂槽位数
     * @param laneCount 当前配置的账户 Lane 数，仅校验一至六十四
     */
    public CommandSlotRing(int capacity, int laneCount) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0
                || laneCount <= 0 || laneCount > Long.SIZE) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new CommandSlot[capacity];
        for (/* 当前预分配的槽下标。 */ int index = 0; index < capacity; index++) contexts[index] = new CommandSlot();
        mask = capacity - 1;
    }

    /**
     * 要求正 Core 序号，按掩码取得未占用槽，写入身份、标记占用并更新在途/峰值；只占槽，不完整初始化业务字段。物理位置冲突立即拒绝。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    CommandSlot claim(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        // 当前完整序号对应的命令槽引用。
        CommandSlot context = contexts[(int) coreSequence & mask];
        if (context.claimed) throw new IllegalStateException("lane command context ring is full");
        context.coreSequence = coreSequence;
        context.claimed = true;
        inFlight++;
        highWaterMark = Math.max(highWaterMark, inFlight);
        return context;
    }

    /**
     * 按低位寻址并同时检查 claimed 和完整序号匹配，否则抛出带槽位诊断的异常。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    public CommandSlot required(long coreSequence) {
        // 物理槽下标或该位置的槽对象，具体由声明类型区分。
        int slot = (int) coreSequence & mask;
        // 当前完整序号对应的命令槽引用。
        CommandSlot context = contexts[slot];
        if (!context.claimed || context.coreSequence != coreSequence) {
            throw new IllegalStateException("unknown lane command context sequence=" + coreSequence
                    + " slot=" + slot + " actual=" + context.coreSequence
                    + " inFlight=" + inFlight + " capacity=" + contexts.length);
        }
        return context;
    }

    /**
     * 按物理数组下标取槽，不检查它是否已占用；数组自己约束下标。
     * @param slot 物理槽下标
     */
    CommandSlot contextAt(int slot) { return contexts[slot]; }

    /**
     * 要求已绑定撮合结果且全部预期 Lane 完成，并且 Matcher 提交路由已释放，随后清生命周期并减在途计数；不逐个清掉所有业务引用。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    void release(long coreSequence) {
        // 当前完整序号对应的命令槽引用。
        CommandSlot context = required(coreSequence);
        if (!context.complete() || context.submittedMatcherShard() != -1)
            throw new IllegalStateException("incomplete lane command context complete=" + context.complete()
                    + " submittedMatcherShard=" + context.submittedMatcherShard()
                    + " matchingResult=" + (context.matchingResult() != null)
                    + " completedLaneMask=" + context.completedLaneMask()
                    + " expectedLaneMask=" + context.expectedLaneMask());
        context.clear();
        inFlight--;
    }

    /**
     * 检查目标物理槽已占用且完整 Core 序号相同，不仅比较低位下标。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    public boolean claimed(long coreSequence) {
        // 物理槽下标或该位置的槽对象，具体由声明类型区分。
        CommandSlot slot = contexts[(int) coreSequence & mask];
        return slot.claimed && slot.coreSequence == coreSequence;
    }

    /**
     * 直接清已占用槽的生命周期并减计数，不要求撮合、Lane 或 Matcher 路由完成；仅用于调用方确认可撤销或终止的路径。
     * @param coreSequence 需占用、查找或释放的完整 Core 序号
     */
    void discard(long coreSequence) {
        // 当前完整序号对应的命令槽引用。
        CommandSlot context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    /**
     * 返回预分配槽位总数。
     */
    int capacity() { return contexts.length; }

}
