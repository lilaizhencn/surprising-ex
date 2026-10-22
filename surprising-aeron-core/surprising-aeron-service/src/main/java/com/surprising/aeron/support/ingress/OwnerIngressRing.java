package com.surprising.aeron.support.ingress;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/**
 * 交易步骤 1：Cluster 回调单写、Owner 单读的唯一输入环。固定槽保存请求、动作和原日志元数据，槽数及载荷字节都有上限；消费前保持字段稳定，移除槽本身不等于归还命令的解码租约。
 */
public final class OwnerIngressRing {
    /** 输入环固定 8192 个条目。 */
    static final int CAPACITY = 8192;
    /** 固定二次幂容量的环形下标掩码。 */
    private static final int MASK = CAPACITY - 1;
    /** 输入环载荷字节预算六十四 MiB，不包含协议头字节。 */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** 累计消费六十四 KiB 后公布一次字节进度。 */
    private static final long BATCH_CONSUMED_BYTES_THRESHOLD = 64 * 1024;
    /** 启动时分配并循环复用的输入条目数组。 */
    private final Entry[] entries = new Entry[CAPACITY];
    /** 生产者填写条目后公布的累计条数，Owner 通过它看到新输入。 */
    private volatile long producedEntries;
    /** 生产者已交入的累计载荷字节数。 */
    private volatile long producedBytes;
    /** 生产者缓存的消费条数，仅容量不足时刷新。 */
    private long cachedConsumedEntries;
    /** 生产者缓存的已公布消费字节，仅字节预算不足时刷新。 */
    private long cachedConsumedBytes;
    /**
     * p01：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p02：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p03：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p04：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p05：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p06：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p07：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p08：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p09：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p0a：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p0b：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p0c：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p0d：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p0e：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p0f：生产游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     */
    @SuppressWarnings("unused")
    private long p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e, p0f;
    /** Owner 清掉条目后公布的累计消费条数。 */
    private volatile long consumedEntries;
    /** Owner 已批量公布的累计消费载荷字节。 */
    private volatile long consumedBytes;
    /** Owner 缓存的已发布前缀，读完才刷新。 */
    private long cachedProducedEntries;
    /** Owner 已移除但尚未公布给生产者的载荷字节增量。 */
    private long uncommittedConsumedBytes;
    /**
     * p11：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p12：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p13：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p14：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p15：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p16：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p17：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p18：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p19：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p1a：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p1b：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p1c：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p1d：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p1e：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     * p1f：消费游标之后的一项显式填充字段，不保存业务数据，也不被交易代码读写。
     */
    @SuppressWarnings("unused")
    private long p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e, p1f;

    /**
     * 启动时一次创建全部输入条目，之后按环形下标复用，不逐命令分配输入条目。
     */
    public OwnerIngressRing() {
        for (/* 当前预分配输入条目的数组下标 */ int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    /**
     * 先检查条目容量和载荷字节预算，必要时刷新消费游标；无空间返回假且不占槽。成功时先填完整字段和字节进度，再发布条目游标，让 Owner 接手。
     * @param command 需要交给 Owner 的原请求，动作条目可为 null
     * @param sessionId 原会话纯 ID，无会话使用 NO_SESSION_ID
     * @param timestamp 本条复制日志的业务时间
     * @param position 本条复制日志位置
     * @param action 可空框架动作，普通业务条目不传动作
     * @param enqueuedNanos 已有入队观测时间，未记录可传零
     * @param fingerprint 与原请求对应的可空指纹
     */
    public boolean offer(CoreMessage command, long sessionId, long timestamp, long position,
                  Runnable action, long enqueuedNanos, CommandFingerprint fingerprint) {
        // 当前请求的有效载荷字节数，动作条目为零
        int bytes = command == null ? 0 : command.payloadLength();
        if (bytes > MAX_BYTES) throw new IllegalStateException("command exceeds ingress byte budget");
        // 生产者本轮开始时的累计发布条数
        long currentProduced = producedEntries;
        if (currentProduced - cachedConsumedEntries >= CAPACITY) {
            cachedConsumedEntries = consumedEntries;
            if (currentProduced - cachedConsumedEntries >= CAPACITY) return false;
        }
        // 本轮入队前累计发布的载荷字节
        long currentProducedBytes = producedBytes;
        // 扣除尚未公布消费进度后的可用字节预算
        long availableBytes = MAX_BYTES - (currentProducedBytes - cachedConsumedBytes);
        if (bytes > availableBytes) {
            cachedConsumedBytes = consumedBytes;
            availableBytes = MAX_BYTES - (currentProducedBytes - cachedConsumedBytes);
            if (bytes > availableBytes) return false;
        }
        // 容量校验通过后，生产者本轮可以填写的空闲输入槽
        Entry entry = entries[(int) currentProduced & MASK];
        entry.command = command; entry.sessionId = sessionId;
        entry.timestamp = timestamp; entry.position = position;
        entry.action = action; entry.enqueuedNanos = enqueuedNanos; entry.fingerprint = fingerprint;
        producedBytes = currentProducedBytes + bytes;
        producedEntries = currentProduced + 1;
        return true;
    }

    /**
     * Owner 借看当前队首；已缓存的发布前缀用完才刷新生产游标，空环返回 null，不在此移除或释放对象。
     */
    public Entry first() {
        // Owner 当前队首的累计消费游标
        long currentConsumed = consumedEntries;
        if (currentConsumed >= cachedProducedEntries) {
            cachedProducedEntries = producedEntries;
            if (currentConsumed >= cachedProducedEntries) return null;
        }
        return entries[(int) currentConsumed & MASK];
    }

    /**
     * 采样已发布与已消费条目之差，最少为零；包含框架动作，不是精确的未回包业务数。
     */
    public int size() { return (int) Math.max(0L, producedEntries - consumedEntries); }
    /**
     * 返回已发布字节减去已公布消费字节；消费字节批量公布，因此可能暂时包含刚移除的载荷。
     */
    public long retainedBytes() { return producedBytes - consumedBytes; }

    /**
     * Owner 完成当前动作或交出请求租约后移除队首；先清字段再发布消费游标，防止生产者复用时被旧消费者覆盖。字节进度达到阈值或看完已缓存前缀时公布。
     */
    public void remove() {
        // Owner 当前队首的累计消费游标
        long currentConsumed = consumedEntries;
        if (currentConsumed >= cachedProducedEntries) {
            cachedProducedEntries = producedEntries;
            if (currentConsumed >= cachedProducedEntries) throw new IllegalStateException("empty owner ingress");
        }
        // 当前借出的输入条目，清空并发布消费游标后可能立即复用
        Entry entry = entries[(int) currentConsumed & MASK];
        // 当前请求的有效载荷字节数，动作条目为零
        int bytes = entry.command == null ? 0 : entry.command.payloadLength();
        entry.clear();
        // 先清槽再公布消费条数，生产者看到新游标后就可能立即复用这个条目。
        // 清完当前槽后要发布的下一消费游标
        long nextConsumed = currentConsumed + 1;
        consumedEntries = nextConsumed;
        uncommittedConsumedBytes += bytes;
        if (uncommittedConsumedBytes >= BATCH_CONSUMED_BYTES_THRESHOLD || nextConsumed >= cachedProducedEntries) {
            commitConsumedBytes();
        }
    }

    /**
     * 将 Owner 本地累计的消费字节一次公布给生产者，再清本地增量；不改变条目游标或命令解码租约。
     */
    public void commitConsumedBytes() {
        if (uncommittedConsumedBytes > 0) {
            consumedBytes += uncommittedConsumedBytes;
            uncommittedConsumedBytes = 0;
        }
    }

    /**
     * 停机时逐条移除并归还仍属于输入环的解码租约，最后公布消费字节；必须先停止可能借用这些输入的工作资源及命令窗口，已转交的命令不在这里重复释放。
     */
    public void discard() {
        // 当前借出的输入条目，清空并发布消费游标后可能立即复用
        Entry entry;
        while ((entry = first()) != null) {
            // 移除条目前保存的请求引用，用于只归还仍归输入环的解码租约
            CoreMessage command = entry.command;
            remove();
            if (command != null) command.releaseDecoderSlot();
        }
        commitConsumedBytes();
    }

    /**
     * 可复用输入条目。生产者填好后交给 Owner，Owner 移除时清空；后续窗口若仍需请求，须自行保留请求租约，不能保留这份可立即复用的条目。
     */
    public static final class Entry {
        /**  当前借给 Owner 的请求，动作条目通常为空。 借看当前请求，框架动作条目通常为 null；条目移除后不得再从这里取请求。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        CoreMessage command;
        /**  原会话 ID，空槽默认 NO_SESSION_ID。 读取原会话纯 ID，不把 Cluster 会话对象传入 Owner。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        long sessionId = NO_SESSION_ID;
        /**
         * timestamp：原复制日志业务时间。
         * position：原复制日志位置。
         * enqueuedNanos：可选入队纳秒观测时间，零表示未记录，不参与交易裁决。
         */
        long timestamp, position, enqueuedNanos;
        /**  要在 Owner 执行的框架动作，普通命令通常为空。 借看当前条目的框架动作，业务命令通常为 null。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        Runnable action;
        /**
         *  Owner 对当前代条目的观察标记，清槽时复位。
         * 读取 Owner 是否已经观察过当前代条目。
         * 记录 Owner 已观察当前代条目，供原命令推进边界使用；槽复用前会清零。
         * @param ownerObserved 当前代条目是否已被 Owner 观察
         */
        @Accessors(fluent = true, chain = false)
        @Getter
        @Setter
        boolean ownerObserved;
        /**  请求内容指纹，随请求交接并在清槽时移除引用。 借看原请求指纹，移除条目前须交给后续实际持有者。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        CommandFingerprint fingerprint;

        /**
         * 清掉请求、指纹、动作、观察标记及日志字段，恢复无会话默认值；只清槽内容，不释放命令自己的解码租约。
         */
        private void clear() {
            command = null; fingerprint = null; action = null; ownerObserved = false;
            sessionId = NO_SESSION_ID;
            timestamp = position = enqueuedNanos = 0;
        }

    /**
     * 读取原复制日志的业务时间，不用系统时间重新生成。
     */
    public long timestamp() { return timestamp; }
    /**
     * 读取原复制日志位置，保持业务推进与恢复顺序一致。
     */
    public long position() { return position; }
}
}
