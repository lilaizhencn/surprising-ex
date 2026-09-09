package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import io.aeron.cluster.service.ClientSession;

/** 已复制但尚未进入业务阶段的命令 FIFO；保留原日志时间和位置，重试不重新执行命令。 */
final class PendingClusterIngress {
    /** 有界积压；容量不足由入口背压处理，不产生依赖本地速度的业务拒绝。 */
    private static final int CAPACITY = 8192;
    /** 复用槽位，命令进入时不额外分配任务对象。 */
    private final Entry[] entries = new Entry[CAPACITY];
    /** 队首与有效条数，均只由集群服务线程更新。 */
    private int head, size;
    /** 保留 payload 的总字节预算，避免大批量命令耗尽堆。 */
    private int bytes;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    PendingClusterIngress() { for (int i = 0; i < CAPACITY; i++) entries[i] = new Entry(); }
    /** 单条超限无法通过背压恢复；暂时容量不足则由日志回调推进已有命令后重试。 */
    boolean hasCapacity(CoreMessage command) {
        int length = command == null ? 0 : command.payloadUnsafe().length;
        if (length > MAX_BYTES) throw new IllegalStateException("replicated command exceeds backlog byte capacity");
        return size < CAPACITY && length <= MAX_BYTES - bytes;
    }
    void add(ClientSession session, CoreMessage command, long timestamp, long position) {
        int length = command == null ? 0 : command.payloadUnsafe().length;
        if (length > MAX_BYTES - bytes || size == CAPACITY) throw new IllegalStateException("replicated command backlog capacity exhausted");
        bytes += length;
        Entry entry = entries[(head + size++) & (CAPACITY - 1)];
        entry.session = session; entry.command = command; entry.timestamp = timestamp; entry.position = position;
    }
    Entry first() { return size == 0 ? null : entries[head]; }
    int size() { return size; }
    void remove() {
        if (size == 0) throw new IllegalStateException("empty replicated command backlog");
        Entry entry = entries[head];
        if (entry.command != null) bytes -= entry.command.payloadUnsafe().length;
        entry.session = null; entry.command = null;
        entry.timestamp = entry.position = 0;
        head = (head + 1) & (CAPACITY - 1); size--;
    }
    void clear() { while (size != 0) remove(); }
    /** 原始日志记录上下文；出队前不会复用。 */
    static final class Entry {
        /** 响应对应的集群会话；重放时可能为空。 */
        ClientSession session;
        /** 已拥有 payload 的不可变命令。 */
        CoreMessage command;
        /** 业务必须沿用的日志时间和位置，不使用重试时刻替代。 */
        long timestamp, position;
    }
}
