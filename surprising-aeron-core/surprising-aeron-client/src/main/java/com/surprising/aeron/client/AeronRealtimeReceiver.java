package com.surprising.aeron.client;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import java.util.function.Consumer;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/** Receiver owns its subscription and copies fragments before their Aeron buffer is reused. */
public final class AeronRealtimeReceiver implements AutoCloseable {
    private final Thread worker;
    private volatile boolean running = true;
    private final LongAdder failures = new LongAdder();
    public AeronRealtimeReceiver(String directory, String channel, int streamId, Consumer<RealtimeFrame> handler) {
        worker = Thread.ofPlatform().name("realtime-aeron-receiver").unstarted(() -> {
            while (running) {
                try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory).driverTimeoutMs(1000));
                     var subscription = aeron.addSubscription(channel, streamId)) {
                    var assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                        try {
                            if (length > RealtimeFrameCodec.MAX_FRAME_BYTES) throw new IllegalArgumentException("oversized realtime message");
                            byte[] bytes = new byte[length]; buffer.getBytes(offset, bytes);
                            handler.accept(RealtimeFrameCodec.decode(bytes));
                        } catch (RuntimeException failure) { failures.increment(); }
                    });
                    while (running) {
                        if (subscription.poll(assembler, 64) == 0) LockSupport.parkNanos(100_000);
                    }
                } catch (RuntimeException failure) {
                    failures.increment();
                    if (running) LockSupport.parkNanos(1_000_000_000L);
                }
            }
        });
        worker.start();
    }
    public long failures() { return failures.sum(); }
    @Override public void close() {
        running = false; worker.interrupt();
        try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
