package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import static com.surprising.aeron.service.orchestration.ClusterServiceEgress.NO_SESSION_ID;

/** Cluster 单写、Owner 单读的唯一输入环；消费前槽位与原日志上下文保持稳定。 */
final class OwnerIngressRing {
    static final int CAPACITY = 8192;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private final OneToOneConcurrentArrayQueue<Entry> queue = new OneToOneConcurrentArrayQueue<>(CAPACITY);
    private final Entry[] entries = new Entry[CAPACITY];
    /** Producer-only cursor/budget; a failed offer never claims or changes an entry. */
    private long producedEntries, producedBytes;
    private volatile long consumedBytes;

    OwnerIngressRing() {
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    boolean offer(CoreMessage command, long sessionId, long timestamp, long position,
                  Runnable action, long enqueuedNanos, CommandFingerprint fingerprint) {
        int bytes = command == null ? 0 : command.payloadLength();
        if (bytes > MAX_BYTES) throw new IllegalStateException("command exceeds ingress byte budget");
        if (queue.remainingCapacity() == 0 || bytes > MAX_BYTES - (producedBytes - consumedBytes)) return false;
        Entry entry = entries[(int) producedEntries & (CAPACITY - 1)];
        entry.command = command; entry.sessionId = sessionId;
        entry.timestamp = timestamp; entry.position = position;
        entry.action = action; entry.enqueuedNanos = enqueuedNanos; entry.fingerprint = fingerprint;
        // The single consumer can only increase capacity after the preceding acquire.
        if (!queue.offer(entry)) throw new IllegalStateException("owner ingress requires one producer");
        producedEntries++;
        producedBytes += bytes;
        return true;
    }

    Entry first() { return queue.peek(); }
    int size() { return queue.size(); }
    long retainedBytes() { return producedBytes - consumedBytes; }

    /** Owner transfers the command to its window, or finishes a control/action, before advancing. */
    void remove() {
        Entry entry = queue.peek();
        if (entry == null) throw new IllegalStateException("empty owner ingress");
        int bytes = entry.command == null ? 0 : entry.command.payloadLength();
        entry.clear();
        // Clear before poll releases the queue head: the producer may immediately reuse this entry.
        if (queue.poll() != entry) throw new IllegalStateException("owner ingress requires one consumer");
        consumedBytes += bytes;
    }

    /** After workers and the admitted window stop, release only commands still owned by this ring. */
    void discard() {
        Entry entry;
        while ((entry = first()) != null) {
            CoreMessage command = entry.command;
            remove();
            if (command != null) command.releaseDecoderSlot();
        }
    }

    static final class Entry {
        CoreMessage command;
        long sessionId = NO_SESSION_ID;
        long timestamp, position, enqueuedNanos;
        Runnable action;
        boolean ownerObserved;
        CommandFingerprint fingerprint;

        private void clear() {
            command = null; fingerprint = null; action = null; ownerObserved = false;
            sessionId = NO_SESSION_ID;
            timestamp = position = enqueuedNanos = 0;
        }
    }
}
