package com.surprising.aeron.client;

import java.util.concurrent.atomic.LongAdder;

/** Single producer/consumer, bounded staging. Only commit makes a whole callback visible to the sender. */
public final class RealtimeOutbox {
    private final byte[][] slots;
    private final int maxBytes;
    private volatile long head;
    private volatile long tail;
    private long staged;
    private long stagedBytes;
    private long publishedBytes;
    private volatile long consumedBytes;
    private boolean failed;
    private boolean active;
    private final LongAdder dropped = new LongAdder();
    public RealtimeOutbox(int capacity, int maxBytes) {
        if (capacity < 2 || maxBytes < 64) throw new IllegalArgumentException("invalid realtime capacity");
        slots = new byte[capacity][]; this.maxBytes = maxBytes;
    }
    public void begin() {
        if (active) throw new IllegalStateException("realtime batch already active");
        staged = tail; stagedBytes = 0; failed = false; active = true;
    }
    /** Ownership of encoded bytes transfers to this queue. Never modify them after staging. */
    public boolean stage(byte[] bytes) {
        if (!active) throw new IllegalStateException("no realtime batch");
        if (failed) return false;
        if (bytes == null || bytes.length > maxBytes - (publishedBytes - consumedBytes) - stagedBytes || staged - head >= slots.length) {
            failed = true; dropped.increment(); return false;
        }
        slots[(int)(staged % slots.length)] = bytes;
        staged++; stagedBytes += bytes.length; return true;
    }
    public void commit() {
        if (!active) return;
        if (failed) { abort(); return; }
        publishedBytes += stagedBytes; tail = staged; active = false;
    }
    public void abort() {
        if (!active) return;
        for (long i=tail;i<staged;i++) slots[(int)(i % slots.length)] = null;
        staged = tail; active = false;
    }
    public byte[] poll() {
        long h = head;
        if (h == tail) return null;
        int index = (int)(h % slots.length);
        byte[] bytes = slots[index]; slots[index] = null; consumedBytes += bytes.length; head = h+1; return bytes;
    }
    public boolean active() { return active && !failed; }
    public long droppedBatches() { return dropped.sum(); }
    public long size() { return tail-head; }
}
