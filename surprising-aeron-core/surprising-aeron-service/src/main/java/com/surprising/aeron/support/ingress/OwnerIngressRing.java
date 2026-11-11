package com.surprising.aeron.support.ingress;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/** 交易输入环：单生产者（Cluster 回调线程）到单消费者（Owner）的原位待办命令环。移除槽本身不等于归还命令的解码租约。 */
public final class OwnerIngressRing {
    public static final int CAPACITY = 2048;
    private static final int MASK = CAPACITY - 1;
    private final Entry[] entries = new Entry[CAPACITY];
    /** volatile 发布条目字段，供消费者 acquire 读取。 */
    private volatile long producedEntries;
    private volatile long consumedEntries;

    /** 启动时一次创建全部输入条目，之后按环形下标复用，不逐命令分配输入条目。 */
    public OwnerIngressRing() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Entry();
        }
    }

    /** 检查条目容量。 */
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

    /** 借看当前队首条目。 */
    public Entry first() {
        if (consumedEntries >= producedEntries) {
            return null;
        }
        return entries[(int) consumedEntries & MASK];
    }

    /** 采样已发布与已消费条目之差。 */
    public int size() {
        return (int) Math.max(0L, producedEntries - consumedEntries);
    }

    /** Owner 完成当前动作或交出请求租约后移除队首并推进消费游标。 */
    public void remove() {
        if (consumedEntries >= producedEntries) {
            throw new IllegalStateException("empty owner ingress");
        }
        Entry entry = entries[(int) consumedEntries & MASK];
        entry.clear();
        consumedEntries++;
    }

    /** 停机时逐条移除并归还仍属于输入环的解码租约。 */
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

    /** 可复用输入条目。 */
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
