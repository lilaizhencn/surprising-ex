package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;

import static com.surprising.aeron.service.orchestration.ClusterServiceEgress.NO_SESSION_ID;

/** Cluster 单写、Owner 单读的唯一输入环；消费前槽位与原日志上下文保持稳定。 */
final class OwnerIngressRing {
    static final int CAPACITY = 8192;
    private static final int MASK = CAPACITY - 1;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long BATCH_CONSUMED_BYTES_THRESHOLD = 64 * 1024;
    private final Entry[] entries = new Entry[CAPACITY];
    /** Producer-only cursor/budget; a failed offer never claims or changes an entry. */
    private volatile long producedEntries;
    private volatile long producedBytes;
    private long cachedConsumedEntries;
    private long cachedConsumedBytes;
    @SuppressWarnings("unused")
    private long p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e, p0f;
    /** Consumer-only cursor/budget; updated only by the Owner thread. */
    private volatile long consumedEntries;
    private volatile long consumedBytes;
    private long cachedProducedEntries;
    private long uncommittedConsumedBytes;
    @SuppressWarnings("unused")
    private long p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e, p1f;

    OwnerIngressRing() {
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry();
    }

    boolean offer(CoreMessage command, long sessionId, long timestamp, long position,
                  Runnable action, long enqueuedNanos, CommandFingerprint fingerprint) {
        int bytes = command == null ? 0 : command.payloadLength();
        if (bytes > MAX_BYTES) throw new IllegalStateException("command exceeds ingress byte budget");
        long currentProduced = producedEntries;
        if (currentProduced - cachedConsumedEntries >= CAPACITY) {
            cachedConsumedEntries = consumedEntries;
            if (currentProduced - cachedConsumedEntries >= CAPACITY) return false;
        }
        long currentProducedBytes = producedBytes;
        long availableBytes = MAX_BYTES - (currentProducedBytes - cachedConsumedBytes);
        if (bytes > availableBytes) {
            cachedConsumedBytes = consumedBytes;
            availableBytes = MAX_BYTES - (currentProducedBytes - cachedConsumedBytes);
            if (bytes > availableBytes) return false;
        }
        Entry entry = entries[(int) currentProduced & MASK];
        entry.command = command; entry.sessionId = sessionId;
        entry.timestamp = timestamp; entry.position = position;
        entry.action = action; entry.enqueuedNanos = enqueuedNanos; entry.fingerprint = fingerprint;
        producedBytes = currentProducedBytes + bytes;
        producedEntries = currentProduced + 1;
        return true;
    }

    Entry first() {
        long currentConsumed = consumedEntries;
        if (currentConsumed >= cachedProducedEntries) {
            cachedProducedEntries = producedEntries;
            if (currentConsumed >= cachedProducedEntries) return null;
        }
        return entries[(int) currentConsumed & MASK];
    }

    int size() { return (int) Math.max(0L, producedEntries - consumedEntries); }
    long retainedBytes() { return producedBytes - consumedBytes; }

    /** Owner transfers the command to its window, or finishes a control/action, before advancing. */
    void remove() {
        long currentConsumed = consumedEntries;
        if (currentConsumed >= cachedProducedEntries) {
            cachedProducedEntries = producedEntries;
            if (currentConsumed >= cachedProducedEntries) throw new IllegalStateException("empty owner ingress");
        }
        Entry entry = entries[(int) currentConsumed & MASK];
        int bytes = entry.command == null ? 0 : entry.command.payloadLength();
        entry.clear();
        // Clear before advancing consumedEntries: the producer may immediately reuse this entry.
        long nextConsumed = currentConsumed + 1;
        consumedEntries = nextConsumed;
        uncommittedConsumedBytes += bytes;
        if (uncommittedConsumedBytes >= BATCH_CONSUMED_BYTES_THRESHOLD || nextConsumed >= cachedProducedEntries) {
            commitConsumedBytes();
        }
    }

    void commitConsumedBytes() {
        if (uncommittedConsumedBytes > 0) {
            consumedBytes += uncommittedConsumedBytes;
            uncommittedConsumedBytes = 0;
        }
    }

    /** After workers and the admitted window stop, release only commands still owned by this ring. */
    void discard() {
        Entry entry;
        while ((entry = first()) != null) {
            CoreMessage command = entry.command;
            remove();
            if (command != null) command.releaseDecoderSlot();
        }
        commitConsumedBytes();
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
