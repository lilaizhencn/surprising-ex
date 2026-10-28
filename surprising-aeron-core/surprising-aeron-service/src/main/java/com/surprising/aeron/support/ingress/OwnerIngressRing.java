package com.surprising.aeron.support.ingress;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/**
 * 交易输入环：单线程内联模式下的原位待办命令环。
 * 固定槽保存待处理请求、来源会话及日志元数据；移除槽本身不等于归还命令的解码租约。
 */
public final class OwnerIngressRing {
    /** 输入环容量收敛至 2048 原位槽，与出口环对齐。 */
    public static final int CAPACITY = 2048;
    /** 固定二次幂容量的环形下标掩码。 */
    private static final int MASK = CAPACITY - 1;
    /** 启动时分配并循环复用的输入条目数组。 */
    private final Entry[] entries = new Entry[CAPACITY];
    /** 累计生产条数（单线程原位推进）。 */
    private long producedEntries;
    /** 累计消费条数（单线程原位推进）。 */
    private long consumedEntries;

    /**
     * 启动时一次创建全部输入条目，之后按环形下标复用，不逐命令分配输入条目。
     */
    public OwnerIngressRing() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Entry();
        }
    }

    /**
     * 检查条目容量；无空间返回 false 且不占槽。成功时填充条目字段并推进游标。
     * @param command 需要交给 Owner 的原请求
     * @param sessionId 原会话纯 ID，无会话使用 NO_SESSION_ID
     * @param timestamp 本条复制日志的业务时间
     * @param position 本条复制日志位置
     * @param fingerprint 与原请求对应的可空指纹
     */
    public boolean offer(CoreMessage command, long sessionId, long timestamp, long position,
                         CommandFingerprint fingerprint) {
        if (producedEntries - consumedEntries >= CAPACITY) {
            return false;
        }
        Entry entry = entries[(int) producedEntries & MASK];
        entry.command = command;
        entry.sessionId = sessionId;
        entry.timestamp = timestamp;
        entry.position = position;
        entry.fingerprint = fingerprint;
        entry.ownerObserved = false;
        producedEntries++;
        return true;
    }

    /**
     * 借看当前队首条目；空环返回 null，不在此移除或释放对象。
     */
    public Entry first() {
        if (consumedEntries >= producedEntries) {
            return null;
        }
        return entries[(int) consumedEntries & MASK];
    }

    /**
     * 采样已发布与已消费条目之差。
     */
    public int size() {
        return (int) Math.max(0L, producedEntries - consumedEntries);
    }

    /**
     * 返回输入环固定容量。
     */
    public int capacity() {
        return CAPACITY;
    }

    /**
     * Owner 完成当前动作或交出请求租约后移除队首并推进消费游标。
     */
    public void remove() {
        if (consumedEntries >= producedEntries) {
            throw new IllegalStateException("empty owner ingress");
        }
        Entry entry = entries[(int) consumedEntries & MASK];
        entry.clear();
        consumedEntries++;
    }

    /**
     * 停机时逐条移除并归还仍属于输入环的解码租约；已转交的命令不在这里重复释放。
     */
    public void discard() {
        Entry entry;
        while ((entry = first()) != null) {
            CoreMessage command = entry.command;
            remove();
            if (command != null) {
                command.releaseDecoderSlot();
            }
        }
    }

    /**
     * 可复用输入条目。
     */
    public static final class Entry {
        @Accessors(fluent = true, chain = false)
        @Getter
        CoreMessage command;

        @Accessors(fluent = true, chain = false)
        @Getter
        long sessionId = NO_SESSION_ID;

        long timestamp, position;

        @Accessors(fluent = true, chain = false)
        @Getter
        @Setter
        boolean ownerObserved;

        @Accessors(fluent = true, chain = false)
        @Getter
        CommandFingerprint fingerprint;

        private void clear() {
            command = null;
            fingerprint = null;
            ownerObserved = false;
            sessionId = NO_SESSION_ID;
            timestamp = position = 0;
        }

        public long timestamp() { return timestamp; }
        public long position() { return position; }
    }
}
