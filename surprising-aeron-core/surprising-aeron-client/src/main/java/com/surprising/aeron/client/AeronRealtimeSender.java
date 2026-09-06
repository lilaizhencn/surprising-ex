package com.surprising.aeron.client;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import org.agrona.concurrent.UnsafeBuffer;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/** Owns the publication on its worker. No transport calls run on the trading owner. */
public final class AeronRealtimeSender implements AutoCloseable {
    private final RealtimeOutbox outbox;
    private final Thread worker;
    private volatile boolean running = true;
    private final LongAdder sent = new LongAdder(), dropped = new LongAdder(), failures = new LongAdder();
    public AeronRealtimeSender(RealtimeOutbox outbox, String directory, String channel, int streamId) {
        this.outbox = outbox;
        worker = Thread.ofPlatform().name("realtime-aeron-sender").unstarted(() -> run(directory, channel, streamId));
        worker.start();
    }
    private void run(String directory, String channel, int streamId) {
        while (running) {
            try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory).driverTimeoutMs(1000));
                 ExclusivePublication publication = aeron.addExclusivePublication(channel, streamId)) {
                UnsafeBuffer buffer = new UnsafeBuffer(new byte[0]);
                while (running) {
                    byte[] bytes = outbox.poll();
                    if (bytes == null) { LockSupport.parkNanos(100_000); continue; }
                    buffer.wrap(bytes);
                    long result = publication.offer(buffer, 0, bytes.length);
                    if (result > 0) sent.increment(); else dropped.increment();
                    if (result == io.aeron.Publication.CLOSED || result == io.aeron.Publication.MAX_POSITION_EXCEEDED) break;
                }
            } catch (RuntimeException failure) {
                failures.increment();
                if (running) LockSupport.parkNanos(1_000_000_000L);
            }
        }
    }
    public long sent() { return sent.sum(); }
    public long dropped() { return dropped.sum(); }
    public long failures() { return failures.sum(); }
    @Override public void close() {
        running = false; worker.interrupt();
        try { worker.join(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
