package com.surprising.aeron.support.concurrent;

/**
 * 从一条账户 Lane 到 Owner 的预分配序号队列，双方各自独占生产或消费，按实际发布顺序交接正值 Core 序号；就绪提示与 Owner 唤醒由外层协议负责。
 */
public final class LaneSequenceQueue {
    /** 预分配的正值 Core 序号数组，零表示空槽。 */
    private final long[] sequences;
    /** 二次幂数组容量减一，用于原始游标定位物理槽。 */
    private final int indexMask;
    /**
     * consumerCursor：仅 Owner 消费者修改的本地下一个读取位置。
     * observedProducer：Owner 缓存的 Lane 发布上界，未耗尽时不重新读跨线程游标。
     */
    private long consumerCursor, observedProducer;
    /** 唯一 Lane 生产者发布的上界，volatile 交出数组内容可见性。 */
    private final PaddedSequence producerSequence = new PaddedSequence();
    /** Owner 清槽后发布的消费上界，使 Lane 可以复用槽。 */
    private final PaddedSequence consumerSequence = new PaddedSequence();

    /**
     * 要求正容量并向上取二次幂，建立固定原始数组；容量计算溢出直接报错。
     * @param requestedCapacity 需要的正值最小容量，实际容量向上取二次幂
     */
    public LaneSequenceQueue(int requestedCapacity) {
        if (requestedCapacity <= 0) throw new IllegalArgumentException("ready queue capacity must be positive");
        // 构造时逐次翻倍的实际数组容量
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        sequences = new long[capacity];
        indexMask = capacity - 1;
    }

    /**
     * 唯一 Lane 发布正值 Core 序号，满时抛错，先写内容再发布生产上界；只保证 FIFO 发布顺序，不校验序号数值单调递增。
     * @param coreSequence 要按发布顺序交接的正值业务 Core 序号
     */
    public void publish(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("core sequence must be positive");
        // Lane 本次要写入的零起始位置
        long next = producerSequence.value;
        if (next - consumerSequence.value >= sequences.length) {
            throw new IllegalStateException("lane sequence queue is full");
        }
        sequences[(int) next & indexMask] = coreSequence;
        producerSequence.value = next + 1;
    }

    /**
     * Owner 先探测是否有发布项，空时返回零；否则校验正值、清槽、推进本地及可见消费位置，再返回原序号。
     */
    public long poll() {
        // Owner 当前本地尚未消费的位置
        long next = consumerCursor;
        if (!hasPending()) return 0;
        // 读取位置经掩码换算的物理槽下标
        int index = (int) next & indexMask;
        // 该槽实际发布的正值 Core 序号，零表示发布协议出现缺口
        long coreSequence = sequences[index];
        if (coreSequence <= 0) throw new IllegalStateException("lane sequence publication gap");
        sequences[index] = 0;
        consumerCursor = next + 1;
        consumerSequence.value = consumerCursor;
        return coreSequence;
    }

    /**
     * Owner 优先使用已见发布上界，耗尽后刷新，仅读取门禁不消费序号。
     */
    private boolean hasPending() {
        if (consumerCursor < observedProducer) return true;
        observedProducer = producerSequence.value;
        return consumerCursor < observedProducer;
    }

    /**
     * 生产者根据已发布与已消费位置差判断是否还有槽，不预留位置。
     */
    boolean hasCapacity() {
        return producerSequence.value - consumerSequence.value < sequences.length;
    }

    /**
     * 返回实际向上取整后的固定物理容量。
     */
    int capacity() { return sequences.length; }

    /**
     * 保存已发布或已消费位置的 volatile 游标槽。
     */
    private static final class PaddedSequence {
        /** 该一方写入、另一方观察的发布或消费位置。 */
        protected volatile long value;
    }
}
